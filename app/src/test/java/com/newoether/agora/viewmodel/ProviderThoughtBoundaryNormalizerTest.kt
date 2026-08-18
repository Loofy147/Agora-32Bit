package com.newoether.agora.viewmodel

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.StreamEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderThoughtBoundaryNormalizerTest {
    @Test
    fun `implicit close splits thought and answer while preserving metadata`() = runTest {
        val events = normalize(
            listOf(
                StreamEvent.ThoughtChunk(
                    thought = "reason</thinking>answer",
                    title = "Thinking",
                    signature = "signature",
                )
            )
        )

        assertEquals(
            listOf(
                StreamEvent.ThoughtChunk("reason", "Thinking", "signature"),
                StreamEvent.TextChunk("answer"),
            ),
            events,
        )
    }

    @Test
    fun `every chunk boundary and mixed case close produces the same content`() = runTest {
        val source = "reason</ThInKiNg>answer"
        for (split in 0..source.length) {
            val events = normalize(
                listOf(
                    StreamEvent.ThoughtChunk(source.substring(0, split)),
                    StreamEvent.ThoughtChunk(source.substring(split)),
                )
            )

            assertEquals(
                "split=$split",
                "reason",
                events.filterIsInstance<StreamEvent.ThoughtChunk>()
                    .joinToString("") { it.thought },
            )
            assertEquals(
                "split=$split",
                "answer",
                events.filterIsInstance<StreamEvent.TextChunk>()
                    .joinToString("") { it.text },
            )
        }
    }

    @Test
    fun `metadata arriving with a split close is applied before answer text`() = runTest {
        val events = normalize(
            listOf(
                StreamEvent.ThoughtChunk("reason</thin"),
                StreamEvent.ThoughtChunk(
                    thought = "king>answer",
                    title = "Thinking",
                    signature = "signature",
                ),
            )
        )

        assertEquals(
            listOf(
                StreamEvent.ThoughtChunk("reason"),
                StreamEvent.ThoughtChunk("", "Thinking", "signature"),
                StreamEvent.TextChunk("answer"),
            ),
            events,
        )
    }

    @Test
    fun `late signature metadata remains available after recovered answer text`() = runTest {
        val events = normalize(
            listOf(
                StreamEvent.ThoughtChunk("reason</thinking>answer"),
                StreamEvent.ThoughtChunk("", "Thinking", "signature"),
            )
        )

        assertEquals(
            listOf(
                StreamEvent.ThoughtChunk("reason"),
                StreamEvent.TextChunk("answer"),
                StreamEvent.ThoughtChunk("", "Thinking", "signature"),
            ),
            events,
        )
    }

    @Test
    fun `after implicit close later thought chunks remain answer text`() = runTest {
        val events = normalize(
            listOf(
                StreamEvent.ThoughtChunk("reason</thinking>first"),
                StreamEvent.ThoughtChunk(" second"),
            )
        )

        assertEquals(
            "reason",
            events.filterIsInstance<StreamEvent.ThoughtChunk>()
                .joinToString("") { it.thought },
        )
        assertEquals(
            "first second",
            events.filterIsInstance<StreamEvent.TextChunk>()
                .joinToString("") { it.text },
        )
    }

    @Test
    fun `close markers in markdown code stay in thought`() = runTest {
        val code = "`</thinking>`\n```\n</thinking>\n```\n"
        val events = normalize(
            (code + "reason</thinking>answer")
                .map { character -> StreamEvent.ThoughtChunk(character.toString()) }
        )

        assertEquals(
            code + "reason",
            events.filterIsInstance<StreamEvent.ThoughtChunk>()
                .joinToString("") { it.thought },
        )
        assertEquals(
            "answer",
            events.filterIsInstance<StreamEvent.TextChunk>()
                .joinToString("") { it.text },
        )
    }

    @Test
    fun `tool and terminal error ordering survives normalization`() = runTest {
        val tool = StreamEvent.ToolCallUpdate(
            streamKey = "stream",
            id = "call",
            name = "shell",
            arguments = "{}",
        )
        val error = StreamEvent.Error(
            GenerationError.Api("bad", "request", "failure")
        )
        val events = normalize(
            listOf(
                StreamEvent.ThoughtChunk("before"),
                tool,
                StreamEvent.ThoughtChunk("after</thinking>answer"),
                error,
            )
        )

        assertEquals(
            listOf(
                StreamEvent.ThoughtChunk("before"),
                tool,
                StreamEvent.ThoughtChunk("after"),
                StreamEvent.TextChunk("answer"),
                error,
            ),
            events,
        )
        assertTrue(events.indexOf(tool) < events.indexOf(error))
        assertFalse(events.any { it is StreamEvent.ThoughtChunk && it.thought.contains("</thinking>") })
    }

    private suspend fun normalize(events: List<StreamEvent>): List<StreamEvent> {
        val normalized = mutableListOf<StreamEvent>()
        val normalizer = ProviderThoughtBoundaryNormalizer()
        events.forEach { event -> normalizer.emit(event, normalized::add) }
        normalizer.finish(normalized::add)
        return normalized
    }
}
