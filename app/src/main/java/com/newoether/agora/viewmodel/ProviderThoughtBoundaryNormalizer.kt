package com.newoether.agora.viewmodel

import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.util.IncrementalThinkingParser

/**
 * Repairs non-standard relays that keep final answer bytes in a structured thought channel and
 * use a model-template close marker as the only thought-to-answer boundary.
 */
internal class ProviderThoughtBoundaryNormalizer {
    private data class ThoughtMetadata(
        val title: String?,
        val signature: String?,
    )

    private val parser = IncrementalThinkingParser(startInThinking = true)
    private var lastMetadata = ThoughtMetadata(title = null, signature = null)

    suspend fun emit(
        event: StreamEvent,
        downstream: suspend (StreamEvent) -> Unit,
    ) {
        if (event !is StreamEvent.ThoughtChunk) {
            flushPending(downstream)
            downstream(event)
            return
        }

        val wasInThinking = parser.inThinking
        lastMetadata = ThoughtMetadata(event.title, event.signature)
        if (event.thought.isEmpty()) {
            if (
                wasInThinking ||
                event.title != null ||
                event.signature != null
            ) {
                downstream(event)
            }
            return
        }

        var emittedThought = false
        val pendingText = mutableListOf<String>()
        parser.feed(
            content = event.thought,
            thinkingEnabled = true,
            onText = { text ->
                if (text.isNotEmpty()) pendingText += text
            },
            onThought = { thought ->
                if (thought.isNotEmpty()) {
                    emittedThought = true
                    downstream(
                        StreamEvent.ThoughtChunk(
                            thought = thought,
                            title = event.title,
                            signature = event.signature,
                        )
                    )
                }
            },
        )
        if (
            wasInThinking &&
            !emittedThought &&
            (event.title != null || event.signature != null)
        ) {
            downstream(event.copy(thought = ""))
        }
        pendingText.forEach { text -> downstream(StreamEvent.TextChunk(text)) }
    }

    suspend fun finish(downstream: suspend (StreamEvent) -> Unit) {
        flushPending(downstream)
    }

    private suspend fun flushPending(downstream: suspend (StreamEvent) -> Unit) {
        parser.flush(
            thinkingEnabled = true,
            onText = { text ->
                if (text.isNotEmpty()) downstream(StreamEvent.TextChunk(text))
            },
            onThought = { thought ->
                if (thought.isNotEmpty()) {
                    downstream(
                        StreamEvent.ThoughtChunk(
                            thought = thought,
                            title = lastMetadata.title,
                            signature = lastMetadata.signature,
                        )
                    )
                }
            },
        )
    }
}
