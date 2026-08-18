package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.newoether.agora.api.util.ContextWindowUsage
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.ConversationContextProjection

/** Every value capable of changing fixed system-prompt or tool-definition token cost. */
@Composable
internal fun rememberContextProjectionInvalidationKey(
    viewModel: ChatViewModel,
    requestConfiguration: List<Any?>,
): List<Any?> {
    val settings = viewModel.settings
    val activePromptId by settings.activeSystemPromptId.collectAsState()
    val pendingPromptId by viewModel.pendingSystemPromptId.collectAsState()
    val systemPrompts by settings.systemPrompts.collectAsState()
    val accessSavedMemories by settings.accessSavedMemories.collectAsState()
    val accessActiveMemory by settings.accessActiveMemory.collectAsState()
    val accessSkills by settings.accessSkills.collectAsState()
    val accessPastConversations by settings.accessPastConversations.collectAsState()
    val imageGenEnabled by settings.imageGenEnabled.collectAsState()
    val imageGenModel by settings.imageGenModel.collectAsState()
    val automationToolsEnabled by settings.automationToolsEnabled.collectAsState()
    val sandboxEnabled by settings.sandboxEnabled.collectAsState()
    val sandboxSharedStorageEnabled by settings.sandboxSharedStorageEnabled.collectAsState()
    val mcpServers by viewModel.mcpServerSnapshots.collectAsState()
    val activeMemoryRevision by viewModel.memoryManager.activeMemoryRevision.collectAsState()
    val skillCatalogRevision by viewModel.skillManager.catalogRevision.collectAsState()
    return requestConfiguration + listOf(
        activePromptId,
        pendingPromptId,
        systemPrompts,
        accessSavedMemories,
        accessActiveMemory,
        accessSkills,
        accessPastConversations,
        imageGenEnabled,
        imageGenModel,
        automationToolsEnabled,
        sandboxEnabled,
        sandboxSharedStorageEnabled,
        mcpServers,
        activeMemoryRevision,
        skillCatalogRevision,
    )
}

/** Asynchronously refreshes exact durable/provider context accounting without blocking Compose. */
@Composable
internal fun rememberConversationContextProjection(
    viewModel: ChatViewModel,
    conversationId: String?,
    selectedModelId: String,
    tokenBudget: Int,
    durableMessages: List<ChatMessage>,
    toolConfigurationKey: Any,
): ConversationContextProjection = produceState(
    initialValue = ConversationContextProjection(
        usage = ContextWindowUsage(0, tokenBudget, 0, false),
        retainedMessageIds = emptySet(),
    ),
    conversationId,
    selectedModelId,
    tokenBudget,
    durableMessages,
    toolConfigurationKey,
) {
    value = viewModel.projectConversationContext(
        conversationId = conversationId,
        selectedModelId = selectedModelId,
        tokenBudget = tokenBudget,
    )
}.value
