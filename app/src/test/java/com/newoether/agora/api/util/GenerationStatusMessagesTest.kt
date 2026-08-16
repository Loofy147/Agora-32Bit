package com.newoether.agora.api.util

import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.model.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStatusMessagesTest {
    @Test
    fun standaloneError_remainsAssistantAndIncludesRawPersistedDetail() {
        val rawError = """{"balance":0.57,"error":{"message":"raw provider failure"}}"""
        val error = ChatMessage(
            id = "error",
            text = "partial answer",
            images = listOf("/private/image.png"),
            thoughts = "private reasoning",
            thoughtTitle = "Thinking",
            tokenCount = 42,
            tokenUsage = TokenUsage(totalTokenCount = 42, outputTokenCount = 42),
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
            thoughtTimeMs = 100,
            modelName = "model",
            toolCall = ToolCallData("tool", "{}", "result"),
            segments = listOf(
                MessageSegment(type = "answer", content = "partial answer"),
                MessageSegment(type = "error", content = rawError),
            ),
            attachmentMeta = AttachmentMeta(),
            retryText = "retry",
        )

        val projected = projectGenerationStatusesForApi(listOf(error)).single()

        assertEquals(Participant.MODEL, projected.participant)
        assertEquals(MessageStatus.SUCCESS, projected.status)
        assertEquals(
            "partial answer\n\n" +
                "[Generation status: ERROR]\n" +
                "The previous assistant generation failed before completing.\n" +
                "Details:\n$rawError",
            projected.text,
        )
        assertEquals(1, Regex(Regex.escape(rawError)).findAll(projected.text).count())
        assertTrue(projected.images.isEmpty())
        assertEquals(null, projected.thoughts)
        assertEquals(null, projected.toolCall)
        assertEquals(null, projected.segments)
        assertEquals(null, projected.attachmentMeta)
        assertEquals(null, projected.tokenUsage)
    }

    @Test
    fun stoppedStatus_staysAsSeparateAssistantBeforeFollowingUserMessage() {
        val stopped = ChatMessage(
            id = "stopped",
            text = "partial answer",
            status = MessageStatus.STOPPED,
            participant = Participant.MODEL,
        )
        val followUp = ChatMessage(
            id = "follow-up",
            text = "continue",
            participant = Participant.USER,
        )

        val projected = projectGenerationStatusesForApi(listOf(stopped, followUp))

        assertEquals(2, projected.size)
        assertEquals("stopped", projected[0].id)
        assertEquals(Participant.MODEL, projected[0].participant)
        assertEquals(MessageStatus.SUCCESS, projected[0].status)
        assertEquals(
            "partial answer\n\n" +
                "[Generation status: STOPPED]\n" +
                "The previous assistant generation was stopped before completing.",
            projected[0].text,
        )
        assertSame(followUp, projected[1])
    }

    @Test
    fun emptyStoppedTurn_becomesSubstantiveAssistantText() {
        val stopped = ChatMessage(
            id = "stopped",
            text = "",
            status = MessageStatus.STOPPED,
            participant = Participant.MODEL,
        )

        val projected = projectGenerationStatusesForApi(listOf(stopped)).single()

        assertEquals(Participant.MODEL, projected.participant)
        assertEquals(
            "[Generation status: STOPPED]\n" +
                "The previous assistant generation was stopped before completing.",
            projected.text,
        )
    }

    @Test
    fun legacyErrorParticipant_becomesAssistantWithStoredRawDetail() {
        val projected = projectGenerationStatusesForApi(
            listOf(
                ChatMessage(
                    id = "legacy-error",
                    text = "legacy failure",
                    status = MessageStatus.SUCCESS,
                    participant = Participant.ERROR,
                ),
            )
        ).single()

        assertEquals(Participant.MODEL, projected.participant)
        assertEquals(MessageStatus.SUCCESS, projected.status)
        assertEquals(
            "[Generation status: ERROR]\n" +
                "The previous assistant generation failed before completing.\n" +
                "Details:\nlegacy failure",
            projected.text,
        )
    }

    @Test
    fun terminalProjection_isIdempotent() {
        val rawError = """{"error":{"message":"once"}}"""
        val error = ChatMessage(
            id = "error",
            text = "",
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
            segments = listOf(MessageSegment(type = "error", content = rawError)),
        )

        val once = projectGenerationStatusesForApi(listOf(error))
        val twice = projectGenerationStatusesForApi(once)

        assertSame(once, twice)
        assertEquals(1, Regex(Regex.escape(rawError)).findAll(twice.single().text).count())
        assertEquals(
            1,
            Regex(Regex.escape("[Generation status: ERROR]"))
                .findAll(twice.single().text)
                .count(),
        )
    }

    @Test
    fun toolProtocolStatus_isNeverRewritten() {
        val tool = ChatMessage(
            id = "tool_call",
            text = "",
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
        )

        val projected = projectGenerationStatusesForApi(listOf(tool))

        assertSame(tool, projected.single())
        assertFalse(projected.single().text.contains("[Generation status:"))
    }

    @Test
    fun successfulMessages_areReturnedUnchanged() {
        val success = ChatMessage(
            id = "success",
            text = "answer",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
        )
        val messages = listOf(success)

        assertSame(messages, projectGenerationStatusesForApi(messages))
    }
}
