package com.newoether.agora.viewmodel

import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.model.citationRecords
import com.newoether.agora.tool.ToolExecutionEvent
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.tool.ToolPresentationMetadata
import com.newoether.agora.tool.ToolProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationToolBatchEffectExecutorTest {
    @Test
    fun `malformed citation segments fail closed during overlay restore and append`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) = null
            },
            providerName = "provider",
        )
        val answer = MessageSegment(type = "answer", content = "answer")
        val malformed = MessageSegment(type = "citation", content = "{")

        overlay.replaceAll(listOf(answer, malformed))
        overlay.append(malformed)

        assertEquals(listOf(answer), overlay.snapshot())
    }

    @Test
    fun `late thought signature updates the completed thought without adding a segment`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) = null
            },
            providerName = "provider",
        )
        overlay.append(MessageSegment(type = "thought", content = "reason"))
        overlay.append(MessageSegment(type = "answer", content = "answer"))

        assertTrue(
            overlay.updateLastThoughtMetadata(
                signature = "signature",
                signatureProvider = "provider",
            )
        )

        val snapshot = overlay.snapshot()
        assertEquals(listOf("answer", "thought", "answer"), snapshot.map { it.type })
        assertEquals("signature", snapshot[1].signature)
        assertEquals("provider", snapshot[1].signatureProvider)
    }

    @Test
    fun `overlay uniquely owns stream indices metadata and terminal presentation`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) =
                    ToolPresentationMetadata(displayName = "Display $name", target = "initial")
            },
            providerName = "provider",
        )

        assertTrue(overlay.upsert("stream", null, "tool", "{", "signature"))
        assertEquals(false, overlay.upsert("stream", "call", "", "{}", null))
        overlay.start(call())
        overlay.applyProgress("call", ToolExecutionEvent.TargetResolved("resolved"))
        overlay.applyProgress("call", ToolExecutionEvent.OutputDelta("partial"))
        overlay.applyProgress("call", ToolExecutionEvent.OutputSnapshot("first\n"))
        overlay.applyProgress("call", ToolExecutionEvent.OutputSnapshot("first\nsecond\n"))
        assertEquals(
            "first\nsecond\n",
            overlay.snapshot().single { it.type == "tool" }.toolProgress,
        )
        val completed = overlay.complete(
            call(),
            ToolExecutionResult(text = "done", displayText = "shown"),
        )

        assertEquals("done", completed.data.result)
        assertEquals("Display tool", completed.data.displayName)
        assertEquals("resolved", completed.segment.toolTarget)
        assertEquals("first\nsecond\n", completed.segment.toolProgress)
        assertEquals(ToolExecutionStates.SUCCEEDED, completed.segment.toolState)
        assertEquals("provider", completed.segment.signatureProvider)

        overlay.replaceAll(emptyList())
        assertNull(overlay.snapshot().singleOrNull())
    }

    @Test
    fun `citation overlay preserves first source order and merges repeated terminal updates`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) = null
            },
            providerName = "provider",
        )
        val answer = "Alpha Beta"
        fun citation(
            title: String,
            url: String,
            anchor: CitationAnchor? = null,
        ) = requireNotNull(
            CitationPolicy.create(
                provider = "test",
                kind = "web",
                title = title,
                url = url,
                anchors = listOfNotNull(anchor),
                answerText = answer,
            ),
        )
        val firstInitial = citation("First", "https://example.com/first")
        val second = citation(
            "Second",
            "https://example.com/second",
            CitationAnchor(6, 10, "Beta"),
        )
        val firstFinal = citation(
            "First",
            "https://example.com/first",
            CitationAnchor(0, 5, "Alpha"),
        )

        assertTrue(overlay.upsertCitation(firstInitial))
        assertTrue(overlay.upsertCitation(second))
        assertTrue(overlay.upsertCitation(firstFinal))
        assertEquals(false, overlay.upsertCitation(firstFinal))

        val snapshot = overlay.snapshot()
        assertEquals(listOf("answer", "citation", "citation"), snapshot.map { it.type })
        val citations = snapshot.citationRecords(answer)
        assertEquals(listOf("First", "Second"), citations.map { it.title })
        assertEquals(listOf(CitationAnchor(0, 5, "Alpha")), citations[0].anchors)
        assertEquals(listOf(CitationAnchor(6, 10, "Beta")), citations[1].anchors)
    }

    @Test
    fun `provider hosted tool lifecycle becomes one terminal display segment`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) =
                    ToolPresentationMetadata(displayName = "OpenAI Search")
            },
            providerName = "OpenAI",
        )
        val active = StreamEvent.HostedToolCallUpdate(
            streamKey = "ws_1",
            name = "openai_search",
            arguments = "{}",
        )

        assertTrue(overlay.upsertHosted(active))
        assertEquals(ToolExecutionStates.RUNNING, overlay.snapshot().last().toolState)
        assertEquals(
            false,
            overlay.upsertHosted(
                active.copy(
                    arguments = """{"type":"search","query":"latest Agora"}""",
                    result = """{"type":"web_search_call","status":"completed"}""",
                ),
            ),
        )

        val segment = overlay.snapshot().last()
        assertEquals("openai_search", segment.toolName)
        assertEquals("OpenAI Search", segment.toolDisplayName)
        assertEquals(ToolExecutionStates.SUCCEEDED, segment.toolState)
        assertTrue(segment.toolResult?.contains("web_search_call") == true)
    }

    @Test
    fun `snapshot clipping never starts with half of a surrogate pair`() {
        assertEquals("😀b", takeLastWholeCodePoints("a😀b", 3))
        assertEquals("b", takeLastWholeCodePoints("a😀b", 2))
        assertEquals("", takeLastWholeCodePoints("😀", 1))
        assertEquals("😀", takeLastWholeCodePoints("😀", 2))
    }

    @Test
    fun `accepted batch executes in order and returns the same identity without continuation`() = runTest {
        val provider = StreamingToolProvider()
        val tools = GenerationToolExecutor.forTest(listOf(provider))
        var now = 0L
        val executor = GenerationToolBatchEffectExecutor(tools) { now += 100L; now }
        val overlay = GenerationToolOverlay(tools, "provider")
        overlay.upsert("stream", "call", "tool", "{}", null)
        val forces = mutableListOf<Boolean>()
        val publishedAt = mutableListOf<Long>()

        val outcome = executor.execute(
            request = AuthorizedToolBatchRequest(
                effect = RunEffect.ExecuteToolBatch(IDENTITY),
                calls = listOf(call()),
                context = GenerationContext(),
                conversationId = "conversation",
                authorizedToolNames = setOf("tool"),
            ),
            overlay = overlay,
            callbacks = ToolBatchProgressCallbacks(
                publish = { forces += it },
                onPublishedAt = publishedAt::add,
            ),
        )

        assertEquals(IDENTITY, outcome.identity)
        assertEquals(listOf("tool"), provider.executedNames)
        assertEquals(listOf(true, false, false, false), forces)
        assertEquals(4, publishedAt.size)
        assertEquals("done", outcome.calls.single().result)
        assertEquals(ToolExecutionStates.SUCCEEDED, outcome.segments.single().toolState)
        assertTrue(outcome.generatedImages.isEmpty())
    }

    private fun call() = StreamEvent.ToolCallRequest(
        id = "call",
        name = "tool",
        arguments = "{}",
        streamKey = "stream",
        signature = "signature",
    )

    private class StreamingToolProvider : ToolProvider {
        val executedNames = mutableListOf<String>()

        override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

        override fun handles(name: String): Boolean = name == "tool"

        override fun presentationMetadata(name: String) = ToolPresentationMetadata("Display")

        override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
            error("Streaming adapter expected")

        override fun executeEvents(
            name: String,
            arguments: String,
            ctx: GenerationContext,
        ): Flow<ToolExecutionEvent> {
            executedNames += name
            return flowOf(
                ToolExecutionEvent.TargetResolved("target"),
                ToolExecutionEvent.OutputDelta("partial"),
                ToolExecutionEvent.Completed(ToolExecutionResult(text = "done")),
            )
        }
    }

    private companion object {
        val IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 7,
            runId = "run",
            pass = 2,
            effectId = "tool-batch-provider-2-0",
        )
    }
}
