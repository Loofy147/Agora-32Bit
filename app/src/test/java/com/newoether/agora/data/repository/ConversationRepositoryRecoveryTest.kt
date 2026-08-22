package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.util.DebugLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationRepositoryRecoveryTest {
    @Test
    fun transientRecoveryFailureRetriesBeforeOpeningGenerationBarrier() = runTest {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } returns Unit
        try {
            val dao = mockk<ChatDao>()
            coEvery { dao.recoverOrphanedRuns(any()) } throws
                IllegalStateException("database temporarily busy") andThen 1
            val repository = ConversationRepository(dao, database = null)

            repository.ensureRunRecovery()
            // The completed barrier is process-idempotent.
            repository.ensureRunRecovery()

            coVerify(exactly = 2) { dao.recoverOrphanedRuns(any()) }
        } finally {
            unmockkObject(DebugLog)
        }
    }
}
