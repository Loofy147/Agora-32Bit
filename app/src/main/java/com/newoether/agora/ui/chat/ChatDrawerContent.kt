package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.ui.chat.search.DrawerSearchBar
import com.newoether.agora.ui.chat.search.SearchResultItem
import com.newoether.agora.ui.chat.search.rememberDrawerSearchState
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.closeWithMotionPolicy
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.verticalEdgeFade
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class DrawerConversationIndicator {
    NONE,
    GENERATING,
    UNREAD,
}

internal fun resolveDrawerConversationIndicator(
    isGenerating: Boolean,
    isSelected: Boolean,
    hasUnreadGeneration: Boolean,
): DrawerConversationIndicator = when {
    isGenerating -> DrawerConversationIndicator.GENERATING
    hasUnreadGeneration && !isSelected -> DrawerConversationIndicator.UNREAD
    else -> DrawerConversationIndicator.NONE
}

/**
 * The conversation navigation drawer: search box, new-chat button, conversation list with
 * per-item context menu, and the settings button. Reads its own flows from [viewModel];
 * shared host state (drawer slide progress, settings-button position, dialog requests) is
 * written back through callbacks so [ChatApp] keeps owning it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ChatDrawerContent(
    viewModel: ChatViewModel,
    drawerWidth: Dp,
    drawerState: DrawerState,
    scope: CoroutineScope,
    inputFocusRequester: FocusRequester,
    onDrawerProgress: (Float) -> Unit,
    onSettingsButtonTop: (Float) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasks: () -> Unit,
    onRequestRename: (String, String) -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    val haptics = LocalAgoraHaptics.current
    val motionPolicy = LocalAgoraMotionPolicy.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val drawerWidthPx = with(density) { drawerWidth.toPx() }

    val conversationList by viewModel.conversations.collectAsState()
    val conversations = conversationList.orEmpty()
    val isConversationListLoading = conversationList == null
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val isSwitching by viewModel.isSwitching.collectAsState()
    val generatingConversationIds by viewModel.generatingConversationIds.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()

    ModalDrawerSheet(
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerTonalElevation = 1.dp,
        modifier = Modifier
            .width(drawerWidth)
            .onGloballyPositioned { coords ->
                val x = coords.positionInWindow().x
                if (!x.isNaN() && drawerWidthPx > 0f) {
                    onDrawerProgress((1f + x / drawerWidthPx).coerceIn(0f, 1f))
                }
            }
            .graphicsLayer {
                clip = true
            }
    ) {
        val drawerListState = rememberLazyListState()
        LaunchedEffect(viewModel, drawerListState) {
            viewModel.firstMessageCommitted.collect { conversationId ->
                if (viewModel.currentConversationId.value == conversationId) {
                    drawerListState.scrollToItem(0)
                }
            }
        }
        val atTop by remember {
            derivedStateOf {
                drawerListState.firstVisibleItemIndex == 0 &&
                    drawerListState.firstVisibleItemScrollOffset == 0
            }
        }
        val atBottom by remember {
            derivedStateOf {
                val layoutInfo = drawerListState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                if (totalItems == 0) {
                    true
                } else {
                    val lastVisibleItem = layoutInfo.visibleItemsInfo.maxByOrNull { it.index }
                    lastVisibleItem != null &&
                        lastVisibleItem.index == totalItems - 1 &&
                        lastVisibleItem.offset + lastVisibleItem.size <= layoutInfo.viewportEndOffset
                }
            }
        }
        val stw by animateFloatAsState(if (atTop) 0f else 1f, tween(200))
        val sbw by animateFloatAsState(if (atBottom) 0f else 1f, tween(200))
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .clearFocusOnTap()
        ) {
            Text(stringResource(R.string.conversations), style = ChatType.conversationsTitle)
            Spacer(modifier = Modifier.height(12.dp))

            val search = rememberDrawerSearchState(viewModel)

            DrawerSearchBar(
                query = search.query,
                onQueryChange = { search.query = it },
                searching = search.isSearching,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (!search.isActive) {
                FilledTonalButton(
                    onClick = {
                        focusManager.clearFocus()
                        onOpenTasks()
                        scope.launch { drawerState.closeWithMotionPolicy(motionPolicy) }
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Repeat, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.tasks), style = ChatType.drawerButton)
                }

                Spacer(modifier = Modifier.height(10.dp))

                val newChatDisabled = isSwitching
                val newChatContainer by animateColorAsState(
                    if (newChatDisabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.primary,
                    tween(300), label = "newChatContainer"
                )
                val newChatContent by animateColorAsState(
                    if (newChatDisabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    else MaterialTheme.colorScheme.onPrimary,
                    tween(300), label = "newChatContent"
                )
                Button(
                    onClick = {
                        if (!newChatDisabled) {
                            viewModel.createNewChat()
                            scope.launch {
                                drawerState.closeWithMotionPolicy(motionPolicy)
                                inputFocusRequester.requestFocus()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    enabled = true,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = newChatContainer,
                        contentColor = newChatContent
                    )
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.new_chat), style = ChatType.drawerButton)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            if (search.isActive && search.results.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.search_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(state = drawerListState, modifier = Modifier.fillMaxSize().verticalEdgeFade(edgeFadeDp = 40f, topWeight = stw, bottomWeight = sbw)) {
                if (search.isActive) {
                    val grouped = search.results.groupBy { it.first.conversationId }
                    val titleMap = conversations.associate { it.id to it.title }
                    items(
                        grouped.entries.toList(),
                        key = { "search:${it.key}" },
                    ) { (convId, entries) ->
                        val bestScore = entries.maxOfOrNull { it.second } ?: 0f
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(
                                    fadeInSpec = tween(180),
                                    placementSpec = null,
                                    fadeOutSpec = null,
                                ),
                        ) {
                            SearchResultItem(
                                title = titleMap[convId] ?: stringResource(R.string.unknown),
                                messages = entries.map { it.first },
                                score = bestScore,
                                query = search.query,
                                customProviders = customProviders,
                                onClick = {
                                    viewModel.selectConversation(convId)
                                    scope.launch {
                                        drawerState.closeWithMotionPolicy(motionPolicy)
                                    }
                                },
                            )
                        }
                    }
                } else {
                    items(
                        conversations,
                        key = { "conversation:${it.id}" },
                    ) { conversation ->
                        val isSelected = conversation.id == currentConversationId
                        val isGenerating = conversation.id in generatingConversationIds
                        val indicator = resolveDrawerConversationIndicator(
                            isGenerating = isGenerating,
                            isSelected = isSelected,
                            hasUnreadGeneration = conversation.hasUnreadGeneration,
                        )
                        val menuEnabled = !isSwitching && !isGenerating
                        val unreadDescription =
                            stringResource(R.string.conversation_unread_generation)
                        var showMenu by remember { mutableStateOf(false) }
                        var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
                        var lastPosition by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                        val density = LocalDensity.current

                        Box(
                            modifier = Modifier.animateItem(
                                fadeInSpec = null,
                                placementSpec = null,
                                fadeOutSpec = tween(180),
                            ),
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .padding(vertical = 2.dp)
                                    .clip(CircleShape)
                                    .pointerInput(showMenu) {
                                        if (!showMenu) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    lastPosition = event.changes.first().position
                                                }
                                            }
                                        }
                                    }
                                    .combinedClickable(
                                        enabled = !isSwitching,
                                        hapticFeedbackEnabled = false,
                                        onClick = {
                                            viewModel.selectConversation(conversation.id)
                                            scope.launch {
                                                drawerState.closeWithMotionPolicy(motionPolicy)
                                            }
                                        },
                                        onLongClick = {
                                            haptics.longPress()
                                            pressOffset = with(density) {
                                                val x = lastPosition.x.toDp().coerceIn(16.dp, 200.dp)
                                                DpOffset(x, lastPosition.y.toDp() - 28.dp)
                                            }
                                            showMenu = true
                                        }
                                    ),
                                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                shape = CircleShape
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = replaceCustomProviderIdsForDisplay(
                                            conversation.title,
                                            customProviders,
                                        ),
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier.size(18.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible =
                                                indicator ==
                                                    DrawerConversationIndicator.GENERATING,
                                            enter = fadeIn(tween(200)),
                                            exit = fadeOut(tween(200)),
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.onSecondaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                },
                                            )
                                        }
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible =
                                                indicator ==
                                                    DrawerConversationIndicator.UNREAD,
                                            enter = fadeIn(tween(200)),
                                            exit = fadeOut(tween(200)),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.primary,
                                                        CircleShape,
                                                    )
                                                    .semantics {
                                                        contentDescription = unreadDescription
                                                    },
                                            )
                                        }
                                    }
                                }
                            }

                            DropdownMenu(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 16.dp,
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                offset = pressOffset,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.generate_title)) },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    enabled = menuEnabled,
                                    onClick = {
                                        showMenu = false
                                        viewModel.generateTitle(conversation.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                enabled = menuEnabled,
                                onClick = {
                                    showMenu = false
                                    onRequestRename(conversation.id, conversation.title)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete), color = if (menuEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = if (menuEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                enabled = menuEnabled,
                                onClick = {
                                    showMenu = false
                                    onRequestDelete(conversation.id)
                                }
                            )
                        }
                    }
                }
            }
            }

                androidx.compose.animation.AnimatedVisibility(
                    visible = isConversationListLoading,
                    modifier = Modifier.fillMaxSize(),
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            FilledTonalButton(
                onClick = {
                    focusManager.clearFocus()
                    onOpenSettings()
                    scope.launch { drawerState.closeWithMotionPolicy(motionPolicy) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .onGloballyPositioned { coords ->
                        val buttonTopPx = coords.positionInWindow().y
                        onSettingsButtonTop((windowHeightPx - buttonTopPx) / density.density)
                    },
                shape = CircleShape
            ) {
                Icon(Icons.Default.Settings, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings), style = ChatType.drawerButton)
            }
        }
    }
}
