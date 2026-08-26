package com.newoether.agora.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.message.AssistantInlineActivityMode
import com.newoether.agora.ui.chat.message.assistantInlineActivityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListStreamingTailTest {
    @Test
    fun attachedStreamingTailSurvivesContentGrowth() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.INACTIVE,
            StreamingTailFollowEvent.GenerationChanged(
                active = true,
            ),
        )
        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = false,
            ),
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.GenerationChanged(active = true),
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)
    }

    @Test
    fun realUserDragDetachesUntilAnExplicitBottomRequestCompletes() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.INACTIVE,
            StreamingTailFollowEvent.GenerationChanged(
                active = true,
            ),
        )
        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = false,
            ),
        )
        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.UserDragStarted,
        )
        assertEquals(StreamingTailFollowMode.DETACHED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.GenerationChanged(active = true),
        )
        assertEquals(StreamingTailFollowMode.DETACHED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = true,
            ),
        )
        assertEquals(StreamingTailFollowMode.DETACHED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = false,
            ),
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)
    }

    @Test
    fun detachedTailIgnoresStreamingGeometryUntilExplicitUserReturn() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.DETACHED,
            StreamingTailFollowEvent.GenerationChanged(
                active = true,
            ),
        )
        assertEquals(StreamingTailFollowMode.DETACHED, mode)
    }

    @Test
    fun attachedGenerationSettlesFinalLayoutBeforeReleasingTailFollow() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.ATTACHED,
            StreamingTailFollowEvent.GenerationChanged(
                active = false,
            ),
        )

        assertEquals(StreamingTailFollowMode.SETTLING, mode)
        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.SettlingFinished,
        )
        assertEquals(StreamingTailFollowMode.INACTIVE, mode)
    }

    @Test
    fun detachedGenerationDoesNotReattachWhileFinishing() {
        val mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.DETACHED,
            StreamingTailFollowEvent.GenerationChanged(
                active = false,
            ),
        )

        assertEquals(StreamingTailFollowMode.INACTIVE, mode)
    }

    @Test
    fun streamingTailAttachesOnlyAfterNearBottomMotionSettles() {
        var mode = reduceStreamingTailFollow(
            StreamingTailFollowMode.INACTIVE,
            StreamingTailFollowEvent.GenerationChanged(
                active = true,
            ),
        )
        assertEquals(StreamingTailFollowMode.ARMED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = true,
            ),
        )
        assertEquals(StreamingTailFollowMode.ARMED, mode)

        mode = reduceStreamingTailFollow(
            mode,
            StreamingTailFollowEvent.ViewportProximityChanged(
                withinAttachThreshold = true,
                scrollInProgress = false,
            ),
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)
    }

    @Test
    fun programmaticSendScrollPausesTailWithoutCreatingASecondScrollOwner() {
        var mode = reduceStreamingTailGenerationAvailability(
            current = StreamingTailFollowMode.INACTIVE,
            active = true,
            autoFollowEnabled = false,
            autoFollowPaused = true,
        )
        assertEquals(StreamingTailFollowMode.INACTIVE, mode)

        mode = reduceStreamingTailGenerationAvailability(
            current = mode,
            active = true,
            autoFollowEnabled = true,
            autoFollowPaused = false,
        )
        assertEquals(StreamingTailFollowMode.ARMED, mode)
    }

    @Test
    fun absoluteBottomScrollIsAFollowHandoffRatherThanDetachment() {
        val availability = streamingTailAvailability(
            generationActive = true,
            blocked = false,
            programmaticHandoff = true,
        )

        assertFalse(availability.enabled)
        assertTrue(availability.paused)
        assertEquals(
            StreamingTailFollowMode.ATTACHED,
            reduceStreamingTailGenerationAvailability(
                current = StreamingTailFollowMode.ATTACHED,
                active = true,
                autoFollowEnabled = availability.enabled,
                autoFollowPaused = availability.paused,
            ),
        )
    }

    @Test
    fun realCompetingUiStillDisablesStreamingFollow() {
        val availability = streamingTailAvailability(
            generationActive = true,
            blocked = true,
            programmaticHandoff = true,
        )

        assertFalse(availability.enabled)
        assertFalse(availability.paused)
    }

    @Test
    fun nonScrollCompetitionStillDetachesStreamingTail() {
        val mode = reduceStreamingTailGenerationAvailability(
            current = StreamingTailFollowMode.ATTACHED,
            active = true,
            autoFollowEnabled = false,
            autoFollowPaused = false,
        )

        assertEquals(StreamingTailFollowMode.DETACHED, mode)
    }

    @Test
    fun inlineAndAnswerStatesHaveExactlyOneWhiteDotOwner() {
        val empty = ChatMessage(
            id = "empty",
            text = "",
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
        )
        val retryBeforeOutput = empty.copy(
            id = "retry-before-output",
            retryText = "Retrying 1/5",
        )
        val retryAfterPartialAnswer = empty.copy(
            id = "retry-after-answer",
            text = "Partial answer",
            segments = listOf(MessageSegment(type = "answer", content = "Partial answer")),
            retryText = "Retrying 1/5",
        )
        val answer = empty.copy(
            id = "answer",
            text = "Answer",
            segments = listOf(MessageSegment(type = "answer", content = "Answer")),
        )
        val answerWithCitation = answer.copy(
            id = "answer-with-citation",
            segments = checkNotNull(answer.segments) +
                MessageSegment(type = "citation", content = "metadata"),
        )
        val cardThenAnswer = answer.copy(
            id = "card-then-answer",
            segments = listOf(
                MessageSegment(type = "thought", content = "Reasoning"),
                MessageSegment(type = "answer", content = "Answer"),
            ),
        )

        val activeOwners = listOf(
            Triple(
                "empty",
                assistantInlineActivityMode(true, false, false, null) !=
                    AssistantInlineActivityMode.NONE,
                shouldShowStreamingTailIndicator(true, false, empty),
            ),
            Triple(
                "retry before output",
                assistantInlineActivityMode(true, false, false, retryBeforeOutput.retryText) !=
                    AssistantInlineActivityMode.NONE,
                shouldShowStreamingTailIndicator(true, false, retryBeforeOutput),
            ),
            Triple(
                "retry after partial answer",
                assistantInlineActivityMode(true, true, false, retryAfterPartialAnswer.retryText) !=
                    AssistantInlineActivityMode.NONE,
                shouldShowStreamingTailIndicator(true, false, retryAfterPartialAnswer),
            ),
            Triple(
                "answer",
                assistantInlineActivityMode(true, true, false, null) !=
                    AssistantInlineActivityMode.NONE,
                shouldShowStreamingTailIndicator(true, false, answer),
            ),
            Triple(
                "answer with citation",
                assistantInlineActivityMode(true, true, false, null) !=
                    AssistantInlineActivityMode.NONE,
                shouldShowStreamingTailIndicator(true, false, answerWithCitation),
            ),
            Triple(
                "card followed by answer",
                assistantInlineActivityMode(true, true, true, null) !=
                    AssistantInlineActivityMode.NONE,
                shouldShowStreamingTailIndicator(true, false, cardThenAnswer),
            ),
        )

        activeOwners.forEach { (label, inlineVisible, tailVisible) ->
            assertEquals(label, 1, listOf(inlineVisible, tailVisible).count { it })
        }
    }

    @Test
    fun visibleCardTailsHaveNoWhiteDotOwner() {
        val empty = ChatMessage(
            id = "empty",
            text = "",
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
        )
        val thinking = empty.copy(
            id = "thinking",
            status = MessageStatus.THINKING,
            segments = listOf(MessageSegment(type = "thought", content = "Reasoning")),
        )
        val tool = empty.copy(
            id = "tool",
            status = MessageStatus.TOOL_CALLING,
            segments = listOf(MessageSegment(type = "tool", toolState = "running")),
        )
        val transcription = empty.copy(
            id = "transcription",
            status = MessageStatus.TRANSCRIBING,
            segments = listOf(MessageSegment(type = "transcription", content = "Image text")),
        )
        val answerSegment = MessageSegment(type = "answer", content = "Earlier answer")
        val cardTailMessages = listOf(
            thinking,
            tool,
            transcription,
            thinking.copy(id = "sending-thinking", status = MessageStatus.SENDING),
            tool.copy(id = "sending-tool", status = MessageStatus.SENDING),
            transcription.copy(id = "sending-transcription", status = MessageStatus.SENDING),
            thinking.copy(
                id = "answer-then-thinking",
                text = answerSegment.content,
                status = MessageStatus.SENDING,
                segments = listOf(answerSegment) + checkNotNull(thinking.segments),
            ),
            tool.copy(
                id = "answer-then-tool",
                text = answerSegment.content,
                status = MessageStatus.SENDING,
                segments = listOf(answerSegment) + checkNotNull(tool.segments),
            ),
            transcription.copy(
                id = "answer-then-transcription",
                text = answerSegment.content,
                status = MessageStatus.SENDING,
                segments = listOf(answerSegment) + checkNotNull(transcription.segments),
            ),
        )

        cardTailMessages.forEach { message ->
            assertEquals(
                message.id,
                AssistantInlineActivityMode.NONE,
                assistantInlineActivityMode(
                    generationActive = true,
                    hasAnswer = message.text.isNotBlank(),
                    hasVisibleInfoSegment = true,
                    retryText = null,
                ),
            )
            assertFalse(message.id, shouldShowStreamingTailIndicator(true, false, message))
        }
    }

    @Test
    fun stoppingAndTerminalGenerationStatesHaveNoWhiteDotOwner() {
        val activeAnswer = ChatMessage(
            id = "active-answer",
            text = "Answer",
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
            segments = listOf(MessageSegment(type = "answer", content = "Answer")),
        )
        val terminalMessages = listOf(
            activeAnswer.copy(id = "success", status = MessageStatus.SUCCESS),
            activeAnswer.copy(id = "stopped", status = MessageStatus.STOPPED),
            activeAnswer.copy(id = "error", status = MessageStatus.ERROR),
        )

        assertEquals(
            AssistantInlineActivityMode.NONE,
            assistantInlineActivityMode(true, true, false, null),
        )
        assertFalse(shouldShowStreamingTailIndicator(true, true, activeAnswer))
        terminalMessages.forEach { message ->
            assertEquals(
                AssistantInlineActivityMode.NONE,
                assistantInlineActivityMode(false, true, false, null),
            )
            assertFalse(shouldShowStreamingTailIndicator(true, false, message))
        }
    }

    @Test
    fun stickToBottomOnKeepsExistingAutoFollowAvailability() {
        val stickToBottom = true
        val availability = streamingTailAvailability(
            generationActive = true,
            blocked = false,
            programmaticHandoff = false,
        )

        assertEquals(
            StreamingTailFollowMode.ARMED,
            reduceStreamingTailGenerationAvailability(
                current = StreamingTailFollowMode.INACTIVE,
                active = true,
                autoFollowEnabled = availability.enabled && stickToBottom,
                autoFollowPaused = availability.paused,
            ),
        )
    }

    @Test
    fun stickToBottomOffWaitsForProgrammaticHandoffThenDetaches() {
        val stickToBottom = false
        val handoff = streamingTailAvailability(
            generationActive = true,
            blocked = false,
            programmaticHandoff = true,
        )
        var mode = reduceStreamingTailGenerationAvailability(
            current = StreamingTailFollowMode.ATTACHED,
            active = true,
            autoFollowEnabled = handoff.enabled && stickToBottom,
            autoFollowPaused = handoff.paused,
        )
        assertEquals(StreamingTailFollowMode.ATTACHED, mode)

        val available = streamingTailAvailability(
            generationActive = true,
            blocked = false,
            programmaticHandoff = false,
        )
        mode = reduceStreamingTailGenerationAvailability(
            current = mode,
            active = true,
            autoFollowEnabled = available.enabled && stickToBottom,
            autoFollowPaused = available.paused,
        )

        assertEquals(StreamingTailFollowMode.DETACHED, mode)
    }

    @Test
    fun stoppingHidesTheAnswerTailWhileRetainingOnlyItsStatusSlot() {
        val answer = ChatMessage(
            id = "answer-tail",
            text = "Answer",
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
            segments = listOf(MessageSegment(type = "answer", content = "Answer")),
        )
        val active = streamingTailPresentation(true, false, answer)
        val stopping = streamingTailPresentation(false, true, answer)
        val stopped = streamingTailPresentation(
            isLoading = false,
            isStopping = false,
            message = answer.copy(status = MessageStatus.STOPPED),
        )

        assertTrue(active.visible)
        assertFalse(active.retainLayout)
        assertFalse(stopping.visible)
        assertTrue(stopping.retainLayout)
        assertFalse(stopped.visible)
        assertFalse(stopped.retainLayout)
    }

    @Test
    fun activeStreamingPayloadAlwaysUsesLatestSnapshot() {
        val latest = ChatMessage(
            id = "active",
            text = "new delta",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
        )
        val stale = latest.copy(text = "old delta")

        assertSame(
            latest,
            resolveMessagePayloadForRender(latest, "active", stale, stale),
        )
    }

    @Test
    fun historicalPayloadRetainsLazyHydrationPriority() {
        val stub = ChatMessage(id = "history", text = "stub", participant = Participant.MODEL)
        val cached = stub.copy(text = "cached")
        val observed = stub.copy(text = "observed")

        assertSame(observed, resolveMessagePayloadForRender(stub, null, observed, cached))
        assertSame(cached, resolveMessagePayloadForRender(stub, null, null, cached))
        assertSame(stub, resolveMessagePayloadForRender(stub, null, null, null))
    }

    @Test
    fun coalescedTailStepIsBoundedAndMovesTowardTarget() {
        assertEquals(
            32f,
            coalescedScrollStep(
                errorPx = 500f,
                elapsedSeconds = 0.016f,
                timeConstantSeconds = 0.055f,
                maximumVelocityPxPerSecond = 2_000f,
                minimumStepPx = 2f,
            ),
            0.001f,
        )
        assertTrue(
            coalescedScrollStep(
                errorPx = -20f,
                elapsedSeconds = 0.016f,
                timeConstantSeconds = 0.055f,
                maximumVelocityPxPerSecond = 2_000f,
                minimumStepPx = 2f,
            ) < 0f,
        )
    }

    @Test
    fun sendEasingOnlyShapesStartupThenReturnsTheAdaptiveTailUnchanged() {
        val adaptiveStep = 120f
        val startupSpec = FeedbackScrollStartupSpec(
            durationMillis = 240L,
            easing = FastOutSlowInEasing,
        )
        val sendSpec = DefaultFeedbackScrollSpec.copy(startup = startupSpec)
        val initial = applyFeedbackScrollStartup(
            adaptiveStepPx = adaptiveStep,
            elapsedNanos = 0L,
            startup = sendSpec.startup,
        )
        val startup = applyFeedbackScrollStartup(
            adaptiveStepPx = adaptiveStep,
            elapsedNanos = 120_000_000L,
            startup = sendSpec.startup,
        )
        val adaptiveTail = applyFeedbackScrollStartup(
            adaptiveStepPx = adaptiveStep,
            elapsedNanos = 240_000_000L,
            startup = sendSpec.startup,
        )
        val bottomButtonStep = applyFeedbackScrollStartup(
            adaptiveStepPx = -adaptiveStep,
            elapsedNanos = 0L,
            startup = DefaultFeedbackScrollSpec.startup,
        )

        assertEquals(
            DefaultFeedbackScrollSpec,
            sendSpec.copy(startup = null),
        )
        assertEquals(0f, initial, 0.001f)
        assertTrue(startup in 0f..adaptiveStep)
        assertEquals(adaptiveStep, adaptiveTail, 0.001f)
        assertEquals(-adaptiveStep, bottomButtonStep, 0.001f)
    }

}
