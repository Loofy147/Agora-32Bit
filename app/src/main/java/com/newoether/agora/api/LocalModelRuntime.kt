package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import java.io.File
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal sealed interface LocalModelIdentity {
    val canonicalPath: String

    data class Chat(
        override val canonicalPath: String,
        val nCtx: Int,
    ) : LocalModelIdentity

    data class Embedding(
        override val canonicalPath: String,
    ) : LocalModelIdentity
}

/** Fair process-wide admission gate for every embedded llama.cpp operation. */
internal class LocalModelTaskQueue {
    private val permit = Semaphore(1)

    suspend fun <T> run(block: suspend () -> T): T = permit.withPermit { block() }
}

/**
 * Canonical owner of the one llama.cpp model/context that may be resident in this process.
 *
 * A complete Chat request or Embedding batch holds [tasks] until its native work and cleanup have
 * finished. Kotlin's coroutine Semaphore is FIFO, so remote work stays parallel while every local
 * waiter observes one strict order. Resident identity changes always close the old native owner
 * before attempting the new load; a failed replacement therefore leaves no model resident.
 */
internal object LocalModelRuntime {
    private const val TAG = "LocalModelRuntime"

    private sealed interface Resident {
        val identity: LocalModelIdentity

        data class Chat(
            override val identity: LocalModelIdentity.Chat,
            val engine: LlamaChatEngine,
        ) : Resident

        data class Embedding(
            override val identity: LocalModelIdentity.Embedding,
        ) : Resident
    }

    private val tasks = LocalModelTaskQueue()
    private var resident: Resident? = null

    @Volatile
    private var activeChatEngine: LlamaChatEngine? = null

    suspend fun runChat(
        modelPath: String,
        nCtx: Int,
        block: suspend (LlamaChatEngine) -> Unit,
    ): Boolean = tasks.run {
        val identity = LocalModelIdentity.Chat(canonicalize(modelPath), nCtx)
        val current = resident
        val engine = if (current is Resident.Chat && current.identity == identity) {
            current.engine.resetContext()
            current.engine
        } else {
            unloadResident()
            val loaded = LlamaChatEngine(identity.canonicalPath, identity.nCtx)
            if (!loaded.load()) {
                loaded.close()
                return@run false
            }
            resident = Resident.Chat(identity, loaded)
            loaded
        }

        activeChatEngine = engine
        try {
            block(engine)
            true
        } finally {
            activeChatEngine = null
        }
    }

    suspend fun <T> runEmbedding(
        modelPath: String,
        block: () -> T,
    ): T? = tasks.run {
        val identity = LocalModelIdentity.Embedding(canonicalize(modelPath))
        if (resident?.identity != identity) {
            unloadResident()
            if (!LlamaEngine.loadResident(identity.canonicalPath)) return@run null
            resident = Resident.Embedding(identity)
        }
        block()
    }

    fun cancelActiveChat() {
        activeChatEngine?.cancel()
    }

    private fun unloadResident() {
        val description = when (val current = resident ?: return) {
            is Resident.Chat -> {
                current.engine.close()
                "Chat"
            }
            is Resident.Embedding -> {
                LlamaEngine.unloadResident()
                "Embedding"
            }
        }
        resident = null
        DebugLog.d(TAG, "Unloaded resident $description")
    }

    private fun canonicalize(path: String): String {
        val file = File(path)
        return runCatching(file::getCanonicalPath).getOrElse { file.absolutePath }
    }
}
