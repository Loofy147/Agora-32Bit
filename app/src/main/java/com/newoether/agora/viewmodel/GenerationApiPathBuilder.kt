package com.newoether.agora.viewmodel

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.util.projectGenerationStatusesForApi
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class GenerationApiPathRequest(
    val parentId: String?,
    val conversationId: String,
    val config: GenerationConfig,
    val context: GenerationContext,
    val loadedMessages: List<MessageEntity>? = null,
)

internal data class GenerationApiPath(
    val messages: List<ChatMessage>,
    val providerConfig: ProviderConfig,
)

internal fun interface GenerationToolDefinitionSource {
    fun definitions(context: GenerationContext): List<ToolDefinition>
}

/**
 * Builds the immutable Provider request path from one durable Room snapshot.
 *
 * It may read Room when the caller has not supplied a snapshot, but it performs no writes and has
 * no runtime, Provider, tool-execution, continuation or finalization authority.
 */
internal class GenerationApiPathBuilder(
    private val conversations: ConversationRepository,
    private val toolDefinitions: GenerationToolDefinitionSource,
) {
    suspend fun build(request: GenerationApiPathRequest): GenerationApiPath =
        withContext(Dispatchers.Default) {
            val dbMessages = request.loadedMessages
                ?: conversations.getMessagesForConversationSnapshot(request.conversationId)
            val messagesById = dbMessages.associateBy { it.id }
            val pathEntities = mutableListOf<MessageEntity>()
            var currentId: String? = request.parentId
            while (currentId != null) {
                val message = messagesById[currentId] ?: break
                pathEntities.add(0, message)
                if (
                    message.id.startsWith(Constants.COMPACT_MSG_PREFIX) &&
                    message.status == MessageStatus.SUCCESS
                ) break
                currentId = message.parentId
            }
            // Inject each persisted tool protocol row exactly once. A queued intervention may have
            // a result_ ancestor while that same round is also reachable as a side chain of the
            // visible model message; ApiPathAssembler owns that overlap and prevents replay.
            val expanded = ApiPathAssembler.assemble(pathEntities, dbMessages)
            val currentPath = projectProviderMessages(
                entities = expanded,
                includeStoredTranscriptions = request.context.imageTranscriptionEnabled,
            ).let(::projectGenerationStatusesForApi)

            val config = request.config
            val definitions = toolDefinitions.definitions(request.context)
            val fixedTokenCost = ContextTokenEstimator.estimateFixed(
                systemPrompt = config.effectiveSystemPrompt,
                tools = definitions,
                initialUserPrompt = config.initialUserPrompt,
            )
            GenerationApiPath(
                messages = currentPath,
                providerConfig = ProviderConfig(
                    apiKey = config.apiKey,
                    modelId = config.modelId,
                    // Transcription-enabled models receive image DESCRIPTIONS instead of raw
                    // images on every path; sending image_url parts to a non-vision model is a
                    // hard provider 400.
                    includeImages = !request.context.imageTranscriptionEnabled,
                    systemPrompt = config.effectiveSystemPrompt,
                    maxContextWindow = (config.maxContextWindow - fixedTokenCost).coerceAtLeast(1),
                    codeExecutionEnabled = config.codeExecutionEnabled,
                    googleSearchEnabled = config.googleSearchEnabled,
                    thinkingEnabled = config.thinkingEnabled,
                    thinkingLevel = config.thinkingLevel,
                    thinkingBudgetEnabled = config.thinkingBudgetEnabled,
                    thinkingBudgetTokens = config.thinkingBudgetTokens,
                    openAiServiceTier = config.openAiServiceTier,
                    responsesApiEnabled = config.responsesApiEnabled,
                    openAiWebSearchEnabled = config.openAiWebSearchEnabled,
                    baseUrl = config.baseUrl,
                    tools = definitions,
                    userPrepend = config.userPrepend,
                    userPostpend = config.userPostpend,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                    topP = config.topP,
                    frequencyPenalty = config.frequencyPenalty,
                    presencePenalty = config.presencePenalty,
                ),
            )
        }
}
