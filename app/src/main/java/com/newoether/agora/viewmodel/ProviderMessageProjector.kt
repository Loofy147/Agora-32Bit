package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.util.Constants
import kotlinx.serialization.json.Json

/**
 * Lossless Room-to-provider projection shared by generation and Compact.
 *
 * UI queries deliberately omit synthetic tool payloads and therefore must never feed request or
 * token-accounting paths. This projector decodes the durable protocol rows and attachment text
 * without consulting mutable UI state.
 */
internal fun projectProviderMessages(
    entities: List<MessageEntity>,
    includeStoredTranscriptions: Boolean,
): List<ChatMessage> {
    val toolHistoryCompactor = ToolRoundHistoryCompactor()
    return entities.map { entity ->
        val decodedSegments = entity.toolCallJson?.let { json ->
            runCatching { Json.decodeFromString<List<MessageSegment>>(json) }.getOrNull()
        }
        val recovered = recoverPersistedThinkingBoundary(
            participant = entity.participant,
            text = entity.text,
            thoughts = entity.thoughts,
            segments = decodedSegments,
        )
        val providerSegments = recovered.segments?.filterNot { it.type == "citation" }
        val segments = if (
            providerSegments != null && entity.id.startsWith(Constants.TOOL_MSG_PREFIX)
        ) {
            toolHistoryCompactor.compact(entity.runId, providerSegments)
        } else {
            providerSegments
        }
        val toolCall = segments?.lastOrNull { it.type == "tool" }?.let { segment ->
            ToolCallData(
                toolName = segment.toolName ?: "",
                arguments = segment.toolArgs ?: "{}",
                result = segment.toolResult ?: "",
                signature = segment.signature,
                toolCallId = segment.toolCallId,
                resultImages = segment.toolImages,
                displayName = segment.toolDisplayName,
                resultText = segment.toolResultText,
                structuredResult = segment.toolStructuredResult,
                responseOutputItems = segment.responseOutputItems,
                responseOutputItemProvider = segment.responseOutputItemProvider,
            )
        }
        val attachmentMeta = entity.attachmentMeta?.let { json ->
            runCatching { Json.decodeFromString<AttachmentMeta>(json) }.getOrNull()
        }
        val attachmentText = attachmentMeta?.items?.mapNotNull { item ->
            when {
                item.textContent != null -> {
                    val label = item.fileName ?: "file"
                    "\n\n--- File: $label ---\n${item.textContent}"
                }
                includeStoredTranscriptions && !item.transcription.isNullOrBlank() -> {
                    val label = item.fileName ?: "image"
                    "\n\n--- Image Transcription: $label ---\n${item.transcription}"
                }
                else -> null
            }
        }?.joinToString("").orEmpty()
        val hasTranscription = includeStoredTranscriptions &&
            attachmentMeta?.items?.any { !it.transcription.isNullOrBlank() } == true
        ChatMessage(
            id = entity.id,
            parentId = entity.parentId,
            text = recovered.text + attachmentText,
            images = if (hasTranscription) emptyList() else entity.images,
            thoughts = recovered.thoughts,
            thoughtTitle = entity.thoughtTitle,
            tokenCount = entity.tokenCount,
            tokenUsage = TokenUsage.fromPersisted(
                totalTokenCount = entity.tokenCount,
                inputTokenCount = entity.inputTokenCount,
                cachedInputTokenCount = entity.cachedInputTokenCount,
                uncachedInputTokenCount = entity.uncachedInputTokenCount,
                outputTokenCount = entity.outputTokenCount,
                reasoningTokenCount = entity.reasoningTokenCount,
            ),
            status = entity.status,
            participant = entity.participant,
            timestamp = entity.timestamp,
            thoughtTimeMs = entity.thoughtTimeMs,
            modelName = entity.modelName,
            segments = segments,
            toolCall = toolCall,
            attachmentMeta = attachmentMeta,
            runId = entity.runId,
            runSequence = entity.runSequence,
            consumedAtPass = entity.consumedAtPass,
        )
    }
}
