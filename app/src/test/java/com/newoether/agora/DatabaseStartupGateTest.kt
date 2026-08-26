package com.newoether.agora

import com.newoether.agora.data.local.DatabaseCompatibility
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseStartupGateTest {
    @Test
    fun `future database stays closed until explicit clear then starts once`() = runTest {
        var compatibility: DatabaseCompatibility = DatabaseCompatibility.FutureVersion(23, 22)
        var openCalls = 0
        var deleteCalls = 0
        var startCalls = 0

        val gate = DatabaseStartupGate(
            inspectDatabase = { compatibility },
            openResource = {
                openCalls += 1
                "database"
            },
            closeResource = {},
            deleteDatabase = {
                deleteCalls += 1
                compatibility = DatabaseCompatibility.Missing
                true
            },
            startProcessServices = { startCalls += 1 },
            reportFailure = {},
        )

        val blocked = gate.initialize()
        assertTrue(blocked is DatabaseStartupState.Blocked)
        assertEquals(0, openCalls)
        assertEquals(0, startCalls)
        assertNull(gate.awaitReadyResource())

        assertTrue(gate.clearBlockedDatabase())
        assertEquals(DatabaseStartupState.Ready, gate.state.value)
        assertEquals(1, deleteCalls)
        assertEquals(1, openCalls)
        assertEquals(1, startCalls)
        assertEquals("database", gate.requireReadyResource())

        assertEquals(DatabaseStartupState.Ready, gate.initialize())
        assertEquals(1, openCalls)
        assertEquals(1, startCalls)
    }

    @Test
    fun `unreadable database fails closed without opening`() = runTest {
        val failure = IOException("unreadable")
        val reported = mutableListOf<Throwable>()
        var opened = false

        val gate = DatabaseStartupGate(
            inspectDatabase = { DatabaseCompatibility.Unreadable(failure) },
            openResource = {
                opened = true
                "database"
            },
            closeResource = {},
            deleteDatabase = { true },
            startProcessServices = {},
            reportFailure = reported::add,
        )

        val state = gate.initialize()

        assertTrue(state is DatabaseStartupState.Blocked)
        assertFalse(opened)
        assertSame(failure, reported.single())
        assertNull(gate.awaitReadyResource())
    }

    @Test
    fun `failed explicit delete leaves the original block in place`() = runTest {
        var openCalls = 0
        val gate = DatabaseStartupGate(
            inspectDatabase = { DatabaseCompatibility.FutureVersion(23, 22) },
            openResource = {
                openCalls += 1
                "database"
            },
            closeResource = {},
            deleteDatabase = { false },
            startProcessServices = {},
            reportFailure = {},
        )
        val original = gate.initialize()

        assertFalse(gate.clearBlockedDatabase())
        assertEquals(original, gate.state.value)
        assertEquals(0, openCalls)
    }

    @Test
    fun `concurrent initialization publishes and starts one resource`() = runTest {
        var openCalls = 0
        var startCalls = 0
        val gate = DatabaseStartupGate(
            inspectDatabase = { DatabaseCompatibility.Supported(22) },
            openResource = {
                delay(10)
                openCalls += 1
                "database"
            },
            closeResource = {},
            deleteDatabase = { true },
            startProcessServices = { startCalls += 1 },
            reportFailure = {},
        )

        List(4) { async { gate.initialize() } }.awaitAll()

        assertEquals(1, openCalls)
        assertEquals(1, startCalls)
        assertEquals(DatabaseStartupState.Ready, gate.state.value)
    }

    @Test
    fun `ready waits for process services to finish`() = runTest {
        val servicesStarted = CompletableDeferred<Unit>()
        val releaseServices = CompletableDeferred<Unit>()
        val gate = DatabaseStartupGate(
            inspectDatabase = { DatabaseCompatibility.Supported(22) },
            openResource = { "database" },
            closeResource = {},
            deleteDatabase = { true },
            startProcessServices = {
                servicesStarted.complete(Unit)
                releaseServices.await()
            },
            reportFailure = {},
        )
        val initialization = async { gate.initialize() }
        servicesStarted.await()
        assertEquals(DatabaseStartupState.Checking, gate.state.value)
        assertFalse(initialization.isCompleted)
        releaseServices.complete(Unit)
        assertEquals(DatabaseStartupState.Ready, initialization.await())
        assertEquals(DatabaseStartupState.Ready, gate.state.value)
    }
    @Test
    fun `service start failure closes resource and remains blocked`() = runTest {
        val failure = IllegalStateException("start failed")
        var closeCalls = 0
        val reported = mutableListOf<Throwable>()
        val gate = DatabaseStartupGate(
            inspectDatabase = { DatabaseCompatibility.Supported(22) },
            openResource = { "database" },
            closeResource = { closeCalls += 1 },
            deleteDatabase = { true },
            startProcessServices = { throw failure },
            reportFailure = reported::add,
        )

        val state = gate.initialize()

        assertTrue(state is DatabaseStartupState.Blocked)
        assertEquals(1, closeCalls)
        assertSame(failure, reported.last())
        assertNull(gate.awaitReadyResource())
    }
}
