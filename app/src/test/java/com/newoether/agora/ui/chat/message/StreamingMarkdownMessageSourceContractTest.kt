package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownMessageSourceContractTest {
    @Test
    fun `all streaming Markdown UI enters through the parameterized message component`() {
        val root = locateMainSourceRoot()
        val wrapper = source(root, "StreamingMarkdownMessage.kt")
        val incremental = source(root, "IncrementalStreamingMarkdown.kt")
        val assistant = source(root, "AssistantMessageContent.kt")
        val timeline = source(root, "MessageItemTimeline.kt")
        val detail = source(root, "SegmentDetailSheet.kt")

        assertTrue(wrapper.contains("internal fun StreamingMarkdownMessage("))
        assertTrue(wrapper.contains("IncrementalStreamingMarkdownContent("))
        assertFalse(wrapper.contains("showStreamingIndicator"))
        assertTrue(incremental.contains("computeBlockFadeSpecs("))
        assertEquals(
            2,
            Regex("LocalStreamingGlyphFadeSpec provides ").findAll(incremental).count(),
        )
        assertFalse(incremental.contains("takeIf { showStreamingIndicator }"))
        assertTrue(wrapper.contains("emptyStreamingTextStyle: TextStyle"))
        assertTrue(wrapper.contains("AnimatedVisibility("))
        assertTrue(wrapper.contains(".padding(top = 8.dp)"))

        listOf(assistant, timeline, detail).forEach {
            assertTrue(it.contains("StreamingMarkdownMessage("))
            assertFalse(it.contains("IncrementalStreamingMarkdownContent("))
            assertFalse(it.contains("ChatStreamingMarkdown("))
        }
        assertFalse(detail.contains("showStreamingIndicator"))
        assertTrue(detail.contains("observedStreamingMarkdown"))
    }

    @Test
    fun `finalized virtualized Thinking detail remains selectable without auto scroll`() {
        val detail = source(locateMainSourceRoot(), "SegmentDetailSheet.kt")
        val virtualizedDetail = detail
            .substringAfter("val detailPageContent")
            .substringAfter("if (usesVirtualizedSingleMarkdown)")
            .substringBefore("} else {")

        assertTrue(virtualizedDetail.contains("NoAutoScrollSelectionContainer("))
        assertTrue(virtualizedDetail.contains("LazyMarkdownTextContent("))
        assertTrue(detail.contains("selectionEnabled = !isStreaming"))
    }

    @Test
    fun `generation error bar is one stateless sibling rather than Markdown state`() {
        val root = locateMainSourceRoot()
        val wrapper = source(root, "StreamingMarkdownMessage.kt")
        val errorBar = source(root, "GenerationErrorBar.kt")
        val assistant = source(root, "AssistantMessageContent.kt")
        val detail = source(root, "SegmentDetailSheet.kt")

        assertTrue(errorBar.contains("internal fun GenerationErrorBar("))
        assertFalse(errorBar.contains("mutableState"))
        assertFalse(errorBar.contains("MessageStatus"))
        assertFalse(wrapper.contains("GenerationErrorBar"))
        assertTrue(assistant.contains("GenerationErrorBar("))
        assertTrue(assistant.contains("precededByCard = terminalImmediatelyFollowsCard"))
        assertTrue(detail.contains("GenerationErrorBar(it)"))
    }

    @Test
    fun `Compact detail uses real empty content and ordinary durable error state`() {
        val source = source(locateMainSourceRoot(), "MessageItem.kt")

        assertTrue(source.contains("R.string.context_compact_streaming"))
        assertTrue(source.contains("directMarkdownContent = compactDetailText"))
        assertTrue(source.contains("errorText = detailErrorText"))
        assertFalse(source.contains("\\u200B"))
        assertTrue(source.contains("R.string.context_compact_error"))
        assertTrue(source.contains("R.string.context_compact_stopped"))
        assertTrue(source.contains("animateColorAsState("))
        assertTrue(source.contains("Icons.Default.Error"))
        assertTrue(source.contains("MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)"))
        assertTrue(source.contains("MaterialTheme.colorScheme.error.copy(alpha = 0.8f)"))
        assertFalse(source.contains("targetValue = if (error) {\n            MaterialTheme.colorScheme.errorContainer\n"))
    }

    @Test
    fun `normal and incremental Markdown roots explicitly forward inline content`() {
        val root = locateMainSourceRoot()
        val normal = source(root, "MessageItemMarkdown.kt")
        val incremental = source(root, "IncrementalStreamingMarkdown.kt")
        val timeline = source(root, "MessageItemTimeline.kt")
        val citation = source(root, "CitationMessageContent.kt")

        listOf(normal, incremental).forEach { owner ->
            assertTrue(owner.contains("val inlineContent = LocalMarkdownInlineContent.current"))
            assertTrue(owner.contains("inlineContent = inlineContent,"))
        }
        assertEquals(
            2,
            Regex("isStreaming = answerIsStreaming").findAll(timeline).count(),
        )
        assertTrue(citation.contains("internal fun citationMarkdownProjection("))
        assertFalse(citation.contains("terminalCitationMarkdownProjection"))
        assertTrue(citation.contains("val unsupported by lazy(LazyThreadSafetyMode.NONE)"))
        assertTrue(citation.contains("boundedTrailingCitationWrapperStart("))
        assertFalse(citation.contains("answerText.lastIndexOf(\"([\")"))
    }

    @Test
    fun `low level incremental renderer has exactly one UI caller`() {
        val root = locateMainSourceRoot()
        val consumers = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("IncrementalStreamingMarkdownContent(") }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("IncrementalStreamingMarkdown.kt", "StreamingMarkdownMessage.kt"),
            consumers,
        )
    }

    private fun source(root: File, name: String): String =
        File(root, "com/newoether/agora/ui/chat/message/$name").readText()

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
