package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageGenerationBoundaryResolver
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.bottombar.contextUsageAtCapacity
import com.newoether.agora.ui.chat.bottombar.contextUsageExceedsCompactThreshold
import com.newoether.agora.ui.chat.message.ContextCompactPillPresentation
import com.newoether.agora.ui.chat.message.SegmentSheetBackAction
import com.newoether.agora.ui.chat.message.contextCompactPillPresentation
import com.newoether.agora.ui.chat.message.segmentSheetBackAction
import com.newoether.agora.ui.chat.message.usesVirtualizedSegmentDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactMessagePresentationTest {
    @Test
    fun compactMessagesRemainInTurnsEvenWhenTheirSummaryIsBlank() {
        val compact = message("compact_boundary", Participant.MODEL).copy(text = "")

        val leading = buildMessageListTurns(listOf(compact))
        val insideTurn = buildMessageListTurns(
            listOf(message("user", Participant.USER), compact)
        )

        assertEquals(listOf("compact_boundary"), leading.single().messages.map { it.id })
        assertEquals(
            listOf("user", "compact_boundary"),
            insideTurn.single().messages.map { it.id },
        )
    }

    @Test
    fun legacyUserCompactDoesNotCreateAUserFullPageTurn() {
        val legacyCompact = message("compact_boundary", Participant.USER)

        val turns = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
                legacyCompact,
                message("later-assistant", Participant.MODEL),
            ),
        )

        assertFalse(MessageGenerationBoundaryResolver.isRealUser(legacyCompact))
        assertEquals(1, turns.size)
        assertEquals(
            listOf("user", "assistant", "compact_boundary", "later-assistant"),
            turns.single().messages.map { it.id },
        )
    }

    @Test
    fun historicalTerminalThoughtCanUseTheVirtualizedDetailLoader() {
        assertTrue(
            usesVirtualizedSegmentDetail(
                selectedSegmentCount = 1,
                segmentType = "thought",
                segmentContentIsBlank = false,
                isStreaming = false,
                hasFooter = false,
            ),
        )
        assertFalse(
            usesVirtualizedSegmentDetail(
                selectedSegmentCount = 1,
                segmentType = "thought",
                segmentContentIsBlank = false,
                isStreaming = false,
                hasFooter = true,
            ),
        )
    }

    @Test
    fun compactUsesItsGraphPositionImmediatelyAfterThePrecedingMessage() {
        val renderedIds = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
                message("compact_boundary", Participant.MODEL).copy(text = ""),
                message("later-assistant", Participant.MODEL),
            ),
        ).flatMap { it.messages }.map { it.id }

        assertEquals(
            listOf("user", "assistant", "compact_boundary", "later-assistant"),
            renderedIds,
        )
    }

    @Test
    fun contextProgressUsesConfiguredCompactThresholdBoundaries() {
        assertFalse(contextUsageExceedsCompactThreshold(50, 100, 50))
        assertTrue(contextUsageExceedsCompactThreshold(51, 100, 50))
        assertFalse(contextUsageExceedsCompactThreshold(90, 100, 90))
        assertTrue(contextUsageExceedsCompactThreshold(91, 100, 90))
        assertFalse(contextUsageExceedsCompactThreshold(100, 100, 100))
        assertFalse(contextUsageExceedsCompactThreshold(1, 0, 90))
    }

    @Test
    fun contextProgressUsesTheSameCapacityThresholdInBothPresentations() {
        assertFalse(contextUsageAtCapacity(99, 100))
        assertTrue(contextUsageAtCapacity(100, 100))
        assertTrue(contextUsageAtCapacity(101, 100))
        assertFalse(contextUsageAtCapacity(1, 0))
    }

    @Test
    fun bottomSheetBackDismissesTheListAndReturnsDetailsToTheList() {
        assertEquals(
            SegmentSheetBackAction.DISMISS,
            segmentSheetBackAction(true, true, detailPageIndex = -1),
        )
        assertEquals(
            SegmentSheetBackAction.SHOW_LIST,
            segmentSheetBackAction(true, true, detailPageIndex = 0),
        )
        assertEquals(
            SegmentSheetBackAction.DISMISS,
            segmentSheetBackAction(false, true, detailPageIndex = 0),
        )
    }

    @Test
    fun compactActionsAreDisabledForEveryBusyConversationState() {
        assertTrue(compactMessageActionsEnabled(false, false, false))
        assertFalse(compactMessageActionsEnabled(true, false, false))
        assertFalse(compactMessageActionsEnabled(false, true, false))
        assertFalse(compactMessageActionsEnabled(false, false, true))
    }

    @Test
    fun newlyCreatedCompactUsesTheSharedOneShotEntrance() {
        val compact = message("compact_boundary", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
        )

        assertTrue(
            shouldAnimateMessageLifecycleEntrance(
                message = compact,
                isKnown = false,
                isLoading = true,
                isStreaming = true,
                lastUserMessageId = null,
                requestedTargetMessageId = null,
            ),
        )
        assertFalse(
            shouldAnimateMessageLifecycleEntrance(
                message = compact,
                isKnown = true,
                isLoading = true,
                isStreaming = true,
                lastUserMessageId = null,
                requestedTargetMessageId = null,
            ),
        )
    }

    @Test
    fun compactGenerationDoesNotOwnTheOrdinaryAssistantStreamingTail() {
        val compact = message("compact_boundary", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
        )
        val assistant = message("assistant", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
        )

        assertFalse(
            shouldShowStreamingTailIndicator(
                isLoading = true,
                isStopping = false,
                message = compact,
            ),
        )
        assertTrue(
            shouldShowStreamingTailIndicator(
                isLoading = true,
                isStopping = false,
                message = assistant,
            ),
        )
    }

    @Test
    fun stoppedCompactUsesDedicatedStoppedPillPresentation() {
        assertEquals(
            ContextCompactPillPresentation.STOPPED,
            contextCompactPillPresentation(MessageStatus.STOPPED),
        )
    }

    private fun message(id: String, participant: Participant) = ChatMessage(
        id = id,
        text = id,
        participant = participant,
    )
}
