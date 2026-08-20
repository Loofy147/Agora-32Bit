package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.ContextWindowUsage
import com.newoether.agora.api.util.contextWindowRetainedMessageIds
import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.CancellationException

internal data class ConversationContextProjection(
    val usage: ContextWindowUsage,
    val retainedMessageIds: Set<String>,
)

/** Builds UI context accounting from the same durable/provider projection used by generation. */
internal class ConversationContextProjector(
    private val conversations: ConversationRepository,
    private val requestBuilder: GenerationRequestBuilder,
    private val generationManager: () -> GenerationManager,
    private val toBranchMessage: (MessageEntity) -> ChatMessage,
    private val newChatSystemPromptId: () -> String? = { null },
) {
    suspend fun project(
        conversationId: String?,
        selectedModelId: String,
        tokenBudget: Int,
    ): ConversationContextProjection {
        val effectiveConversationId = conversationId ?: CONTEXT_PREVIEW_CONVERSATION_ID
        val entities = conversationId?.let {
            conversations.getMessagesForConversationSnapshot(it)
        }.orEmpty()
        val selections = conversationId?.let {
            conversations.restoreBranchSelections(it)
        }.orEmpty()
        val selectedPath = ConversationUiState.resolvePath(
            allMessages = entities.map(toBranchMessage),
            streamingMsg = null,
            selectedChildren = selections,
        )
        val byId = entities.associateBy(MessageEntity::id)
        val selectedEntities = selectedPath.mapNotNull { byId[it.id] }

        val snapshot = selectedModelId.takeIf(String::isNotBlank)?.let { modelId ->
            try {
                requestBuilder.captureContextProjectionSnapshot(
                    conversationId = effectiveConversationId,
                    modelId = modelId,
                    systemPromptIdOverride = if (conversationId == null) {
                        newChatSystemPromptId()
                    } else {
                        null
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        val durableProviderMessages = projectProviderMessages(
            entities = ApiPathAssembler.assemble(selectedEntities, entities),
            // Count stored transcription text conservatively if configuration cannot be resolved.
            includeStoredTranscriptions = snapshot?.context?.imageTranscriptionEnabled ?: true,
        )
        val contextMessages = snapshot?.let {
            projectGenerationInputMessages(
                messages = durableProviderMessages,
                // Transcription-enabled models receive descriptions instead of raw images at
                // dispatch; the bottom-bar estimate must match.
                includeImages = !it.context.imageTranscriptionEnabled,
                userPrepend = it.config.userPrepend,
                userPostpend = it.config.userPostpend,
            )
        } ?: durableProviderMessages
        val fixedTokenCost = snapshot?.let {
            generationManager().fixedContextTokenCost(it.config, it.context)
        } ?: 0
        val usage = contextWindowUsage(
            messages = contextMessages,
            tokenBudget = tokenBudget,
            fixedTokenCost = fixedTokenCost,
        )
        return ConversationContextProjection(
            usage = usage,
            retainedMessageIds = contextWindowRetainedMessageIds(
                messages = contextMessages,
                tokenBudget = tokenBudget,
                fixedTokenCost = fixedTokenCost,
            ),
        )
    }

    private companion object {
        const val CONTEXT_PREVIEW_CONVERSATION_ID = "context-preview-conversation"
    }
}
