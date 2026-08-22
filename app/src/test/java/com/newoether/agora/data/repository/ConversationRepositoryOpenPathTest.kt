package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationRepositoryOpenPathTest {
    @Test
    fun `stuck-state repair does not materialize the complete conversation graph`() = runTest {
        val dao = mockk<ChatDao>(relaxed = true)
        every { dao.getMessagesForConversation("conversation") } returns flowOf(
            listOf(
                MessageEntity(
                    id = "stuck",
                    conversationId = "conversation",
                    text = "payload that the open path must not materialize",
                    status = MessageStatus.SENDING,
                    participant = Participant.MODEL,
                    timestamp = 1L,
                    runId = "run",
                    runSequence = 0,
                ),
            ),
        )
        val repository = ConversationRepository(dao, database = null)

        repository.fixStuckMessages("conversation")

        verify(exactly = 0) { dao.getMessagesForConversation(any()) }
        coVerify(exactly = 0) { dao.upsertMessage(any()) }
        coVerify(exactly = 1) { dao.stopStuckMessagesForConversation("conversation") }
    }
}
