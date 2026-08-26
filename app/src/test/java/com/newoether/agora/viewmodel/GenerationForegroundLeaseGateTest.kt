package com.newoether.agora.viewmodel

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationForegroundLeaseGateTest {
    @Test
    fun localGenerationFailsClosedWhenForegroundLeaseIsUnavailable() = runTest {
        var acquireCalled = false
        val failure = runCatching {
            acquireGenerationForegroundLease(managedExternally = false) {
                acquireCalled = true
                false
            }
        }.exceptionOrNull()
        assertTrue(acquireCalled)
        assertTrue(failure is GenerationForegroundServiceUnavailableException)
    }

    @Test
    fun localGenerationContinuesOnlyAfterForegroundLeaseIsAcquired() = runTest {
        assertTrue(
            acquireGenerationForegroundLease(managedExternally = false) { true }
        )
    }

    @Test
    fun externallyManagedGenerationDoesNotAcquireAgoraLease() = runTest {
        var acquireCalled = false
        val acquired = acquireGenerationForegroundLease(managedExternally = true) {
            acquireCalled = true
            true
        }
        assertFalse(acquireCalled)
        assertEquals(false, acquired)
    }
}
