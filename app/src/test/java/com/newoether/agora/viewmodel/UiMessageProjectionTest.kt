package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.citationRecords
import com.newoether.agora.model.toMessageSegment
import com.newoether.agora.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiMessageProjectionTest {
    @Test
    fun branchMutationProjectionPreservesPersistedThoughtAndToolSegments() {
        val segments = listOf(
            MessageSegment(
                type = "thought",
                content = "reasoning",
                durationMs = 42L,
            ),
            MessageSegment(
                type = "tool",
                toolName = "shell",
                toolArgs = """{"command":"pwd"}""",
                toolResult = "workspace",
                toolDisplayName = "Run shell",
                toolResultText = "workspace",
                toolStructuredResult = """{"path":"workspace"}""",
            ),
        )
        val entity = messageEntity(
            id = "assistant",
            text = "answer",
            toolCallJson = Json.encodeToString(segments),
        )

        val projected = entity.toUiChatMessage { value -> "formatted:$value" }

        assertEquals("formatted:answer", projected.text)
        assertEquals(segments, projected.segments)
        assertEquals("shell", projected.toolCall?.toolName)
        assertEquals("""{"command":"pwd"}""", projected.toolCall?.arguments)
        assertEquals("formatted:workspace", projected.toolCall?.result)
        assertEquals("Run shell", projected.toolCall?.displayName)
        assertEquals("workspace", projected.toolCall?.resultText)
        assertEquals("""{"path":"workspace"}""", projected.toolCall?.structuredResult)
    }

    @Test
    fun roomHistoryReloadPreservesCitationSegments() {
        val answer = "Claim"
        val citation = requireNotNull(
            CitationPolicy.create(
                provider = "test",
                kind = "web",
                title = "Source",
                url = "https://example.com/source",
                anchors = listOf(CitationAnchor(0, answer.length, answer)),
                answerText = answer,
            ),
        )
        val segment = citation.toMessageSegment()
        val entity = messageEntity(
            id = "assistant",
            text = answer,
            toolCallJson = Json.encodeToString(listOf(segment)),
        )

        val projected = entity.toUiChatMessage { it }

        assertEquals(listOf(segment), projected.segments)
        assertEquals(citation, projected.citationRecords().single())
    }

    @Test
    fun syntheticProtocolRowsRemainStructuralOnly() {
        val entity = messageEntity(
            id = Constants.TOOL_MSG_PREFIX + "call",
            text = "large provider payload",
            toolCallJson = Json.encodeToString(
                listOf(MessageSegment(type = "thought", content = "hidden"))
            ),
        )

        val projected = entity.toUiChatMessage { value -> "formatted:$value" }

        assertEquals("", projected.text)
        assertEquals(emptyList<String>(), projected.images)
        assertNull(projected.segments)
        assertNull(projected.toolCall)
    }

    private fun messageEntity(
        id: String,
        text: String,
        toolCallJson: String?,
        thoughts: String? = null,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = "user",
        text = text,
        images = listOf("image"),
        thoughts = thoughts,
        participant = Participant.MODEL,
        timestamp = 1L,
        toolCallJson = toolCallJson,
        runId = "run",
    )
    @Test
    fun persistedImplicitThinkingCloseIsRecoveredForUi() {
        val segments = listOf(
            MessageSegment(type = "thought", content = "reason</thinking>answer"),
            MessageSegment(type = "error", content = "truncated"),
        )
        val entity = messageEntity(
            id = "assistant",
            text = "",
            thoughts = "reason</thinking>answer",
            toolCallJson = Json.encodeToString(segments),
        )

        val projected = entity.toUiChatMessage { value -> "formatted:$value" }

        assertEquals("formatted:answer", projected.text)
        assertEquals("reason", projected.thoughts)
        assertEquals(
            listOf("thought", "answer", "error"),
            projected.segments?.map { it.type },
        )
    }

}
