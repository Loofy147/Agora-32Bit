package com.newoether.agora.api.local

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlamaOwnershipSourceContractTest {
    @Test
    fun `title generation delegates local serialization to the Provider`() {
        val source = mainSource("com/newoether/agora/viewmodel/ConversationTitleGenerator.kt")

        assertFalse(source.contains("LocalModelSerializer"))
    }

    @Test
    fun `Provider serializes before engine mutation and releases in finally`() {
        val source = mainSource("com/newoether/agora/api/local/LocalProvider.kt")
        val lock = source.indexOf("LocalModelSerializer.mutex.lock()")
        val ensure = source.indexOf("ensureEngineLoaded(modelConfig)")
        val unlock = source.indexOf("LocalModelSerializer.mutex.unlock()")

        assertTrue(lock >= 0)
        assertTrue(ensure > lock)
        assertTrue(unlock > ensure)
        assertTrue(source.substring(lock, unlock).contains("finally"))
    }

    @Test
    fun `native mutation is exclusive while cancellation remains concurrent`() {
        val source = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")

        listOf("loadMmproj", "unloadMmproj", "resetContext").forEach { functionName ->
            assertTrue(functionSection(source, functionName).contains("lock.writeLock().lock()"))
        }
        assertTrue(functionSection(source, "cancel").contains("lock.readLock().lock()"))
    }

    @Test
    fun `stream delivery blocks for capacity and native cancellation is atomic`() {
        val engine = mainSource("com/newoether/agora/api/LlamaChatEngine.kt")
        val native = mainCppSource("llama_chat_jni.cpp")

        assertTrue(engine.contains("trySendBlocking(token)"))
        assertFalse(engine.contains("trySend(token)"))
        assertTrue(native.contains("std::atomic<bool> cancelled"))
        assertFalse(native.contains("volatile bool cancelled"))
    }

    private fun functionSection(source: String, functionName: String): String = source
        .substringAfter("fun $functionName(")
        .substringBefore("\n    fun ")

    private fun mainSource(relativePath: String): String = locateSourceRoot("java")
        .resolve(relativePath)
        .readText()

    private fun mainCppSource(fileName: String): String = locateSourceRoot("cpp")
        .resolve(fileName)
        .readText()

    private fun locateSourceRoot(kind: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/$kind"),
                File(directory, "src/main/$kind"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main $kind source directory")
    }
}
