package com.newoether.agora.data.local

import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.ToolExecutionStates
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDaoRunRecoveryTest {
    @Test
    fun recoveryExecutesTheExactSnapshotEffectAndStopsItsInFlightModel() = runTest {
        val dao = mockk<ChatDao>()
        val run = liveRun(status = RunStatus.ACTIVE)
        val message = MessageEntity(
            id = "message",
            conversationId = CONVERSATION_ID,
            text = "partial",
            status = MessageStatus.THINKING,
            participant = Participant.MODEL,
            timestamp = 2L,
            runId = RUN_ID,
            runSequence = 0,
        )
        val checkpoint = slot<MessageStreamCheckpoint>()
        coEvery { dao.recoverOrphanedRuns(any()) } coAnswers { callOriginal() }
        coEvery { dao.getOrphanedLiveRuns() } returns listOf(run)
        coEvery { dao.getMessagesForRuns(listOf(RUN_ID)) } returns listOf(message)
        coEvery { dao.updateMessageCheckpoint(capture(checkpoint)) } returns 1
        coEvery {
            dao.terminalizeLiveRun(
                RUN_ID,
                RunStatus.STOPPED,
                RunEndReason.PROCESS_RECOVERED,
                99L,
            )
        } returns 1

        assertEquals(1, dao.recoverOrphanedRuns(99L))
        assertEquals(MessageStatus.STOPPED, checkpoint.captured.status)
        coVerify(exactly = 1) {
            dao.terminalizeLiveRun(
                RUN_ID,
                RunStatus.STOPPED,
                RunEndReason.PROCESS_RECOVERED,
                99L,
            )
        }
    }

    @Test
    fun recoveryRejectsALostExactRunUpdate() = runTest {
        val dao = mockk<ChatDao>()
        coEvery { dao.recoverOrphanedRuns(any()) } coAnswers { callOriginal() }
        coEvery { dao.getOrphanedLiveRuns() } returns listOf(liveRun(RunStatus.STOPPING))
        coEvery { dao.getMessagesForRuns(listOf(RUN_ID)) } returns emptyList()
        coEvery { dao.terminalizeLiveRun(any(), any(), any(), any()) } returns 0

        val failure = runCatching { dao.recoverOrphanedRuns(99L) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun recoveryStopsTheDurableCompactRowInsteadOfRemovingIt() = runTest {
        val dao = mockk<ChatDao>()
        val compact = MessageEntity(
            id = "compact_inflight",
            conversationId = CONVERSATION_ID,
            parentId = "user",
            text = "partial summary",
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
            timestamp = 2L,
            runId = RUN_ID,
            runSequence = 1,
        )
        val checkpoint = slot<MessageStreamCheckpoint>()
        coEvery { dao.recoverOrphanedRuns(any()) } coAnswers { callOriginal() }
        coEvery { dao.getOrphanedLiveRuns() } returns listOf(liveRun(RunStatus.ACTIVE))
        coEvery { dao.getMessagesForRuns(listOf(RUN_ID)) } returns listOf(compact)
        coEvery { dao.updateMessageCheckpoint(capture(checkpoint)) } returns 1
        coEvery { dao.terminalizeLiveRun(any(), any(), any(), any()) } returns 1

        assertEquals(1, dao.recoverOrphanedRuns(99L))
        assertEquals(compact.id, checkpoint.captured.id)
        assertEquals("partial summary", checkpoint.captured.text)
        assertEquals(MessageStatus.STOPPED, checkpoint.captured.status)
    }

    @Test
    fun recoveryPreservesUnknownToolFieldsWhileStoppingLiveTools() = runTest {
        val dao = mockk<ChatDao>()
        val raw =
            """[{"type":"tool","toolName":"shell","toolState":"running","future":{"v":1}},""" +
                """{"type":"tool","toolName":"background","toolState":"background_running","futureFlag":true}]"""
        val message = MessageEntity(
            id = "message-with-future-fields",
            conversationId = CONVERSATION_ID,
            text = "partial",
            status = MessageStatus.STOPPED,
            participant = Participant.MODEL,
            timestamp = 2L,
            toolCallJson = raw,
            runId = RUN_ID,
            runSequence = 0,
        )
        val checkpoint = slot<MessageStreamCheckpoint>()
        coEvery { dao.recoverOrphanedRuns(any()) } coAnswers { callOriginal() }
        coEvery { dao.getOrphanedLiveRuns() } returns listOf(liveRun(RunStatus.ACTIVE))
        coEvery { dao.getMessagesForRuns(listOf(RUN_ID)) } returns listOf(message)
        coEvery { dao.updateMessageCheckpoint(capture(checkpoint)) } returns 1
        coEvery { dao.terminalizeLiveRun(any(), any(), any(), any()) } returns 1
        assertEquals(1, dao.recoverOrphanedRuns(99L))
        val segments = Json.parseToJsonElement(
            requireNotNull(checkpoint.captured.toolCallJson)
        ).jsonArray
        assertEquals(
            ToolExecutionStates.STOPPED,
            segments[0].jsonObject["toolState"]?.jsonPrimitive?.content,
        )
        assertEquals("{\"v\":1}", segments[0].jsonObject["future"]?.toString())
        assertEquals(
            ToolExecutionStates.BACKGROUND_RUNNING,
            segments[1].jsonObject["toolState"]?.jsonPrimitive?.content,
        )
        assertEquals("true", segments[1].jsonObject["futureFlag"]?.toString())
    }
    private fun liveRun(status: RunStatus) = RunEntity(
        id = RUN_ID,
        conversationId = CONVERSATION_ID,
        parentRunId = null,
        status = status,
        activeSlot = 1,
        startedAt = 1L,
        lastCheckpointAt = 2L,
        stopRequestedAt = if (status == RunStatus.STOPPING) 2L else null,
        currentPass = 3,
    )

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val RUN_ID = "run"
    }
}
