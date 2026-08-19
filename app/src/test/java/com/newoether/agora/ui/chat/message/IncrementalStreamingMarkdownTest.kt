package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalStreamingMarkdownTest {
    private val flavour = GFMFlavourDescriptor()

    @Test
    fun appendOnlyUpdate_scansOnlyDeltaAndReusesStableBlock() {
        val document = IncrementalMarkdownDocument(flavour)
        val first = "First paragraph.\n\nSecond"
        val firstSnapshot = document.update(first, first, isStreaming = true)
        val stable = firstSnapshot.stableBlocks.single()
        val scannedAfterFirst = document.scannedCodeUnits

        val second = "$first paragraph grows"
        val secondSnapshot = document.update(second, second, isStreaming = true)

        assertSame(stable, secondSnapshot.stableBlocks.single())
        assertEquals(
            (second.length - first.length).toLong(),
            document.scannedCodeUnits - scannedAfterFirst,
        )
        assertEquals("Second paragraph grows", secondSnapshot.tail)
    }

    @Test
    fun blankLineInsideFence_doesNotPromoteIncompleteCodeBlock() {
        val document = IncrementalMarkdownDocument(flavour)
        val incomplete = "```kotlin\nval answer = 42\n\n"

        val streaming = document.update(incomplete, incomplete, isStreaming = true)

        assertTrue(streaming.stableBlocks.isEmpty())
        assertEquals(incomplete, streaming.tail)

        val complete = "$incomplete```\n\nFollowing"
        val closed = document.update(complete, complete, isStreaming = true)
        assertEquals(1, closed.stableBlocks.size)
        assertEquals("Following", closed.tail)
    }

    @Test
    fun terminalUpdate_keepsTheLiveTailIdentityAndLayoutPath() {
        val document = IncrementalMarkdownDocument(flavour)
        val text = "Stable.\n\nFinal **tail**"
        val streaming = document.update(text, text, isStreaming = true)
        val stable = streaming.stableBlocks.single()
        val liveStart = streaming.liveBlock?.startOffset

        val terminal = document.update(text, text, isStreaming = false)

        assertSame(stable, terminal.stableBlocks.first())
        assertEquals(1, terminal.stableBlocks.size)
        assertEquals("Final **tail**", terminal.tail)
        assertEquals(liveStart, terminal.liveBlock?.startOffset)
        assertFalse(terminal.isStreaming)
    }

    @Test
    fun streamingAfterTerminal_resetsTheFinalizedDocument() {
        val document = IncrementalMarkdownDocument(flavour)
        val terminalText = "Finished"
        document.update(terminalText, terminalText, isStreaming = false)

        val restarted = document.update(terminalText, terminalText, isStreaming = true)

        assertTrue(restarted.stableBlocks.isEmpty())
        assertEquals(terminalText, restarted.tail)
        assertTrue(restarted.isStreaming)
    }

    @Test
    fun directGlyphAlpha_isBoundedMonotonicAndUnicodeSafe() {
        val text = "older text 😀 最新文字"
        val annotated = streamingTailAnnotatedString(
            text = text,
            color = Color.White,
            fadeCodePoints = 8,
            bands = 4,
            newestAlpha = 0.4f,
        )
        val styles = annotated.spanStyles

        assertEquals(4, styles.size)
        assertEquals(text.length, styles.last().end)
        assertEquals(0.4f, styles.last().item.color.alpha, 0.0001f)
        styles.zipWithNext().forEach { (older, newer) ->
            assertTrue(older.item.color.alpha > newer.item.color.alpha)
        }
        styles.forEach { range ->
            assertFalse(range.start.splitsSurrogatePair(text))
            assertFalse(range.end.splitsSurrogatePair(text))
        }
    }

    @Test
    fun temporalAlpha_usesPerAppendBirthTimesAndEventuallyBecomesSolid() {
        val tracker = StreamingTailFadeTracker(capacity = 8)
        tracker.update("ab", nowMs = 1_000L)
        val appended = tracker.update("abcd", nowMs = 1_100L)

        assertArrayEquals(
            longArrayOf(1_000L, 1_000L, 1_100L, 1_100L),
            appended.birthTimesMs,
        )

        val fading = streamingTailAnnotatedString(
            text = "abcd",
            color = Color.White,
            fadeCodePoints = 4,
            bands = 4,
            newestAlpha = 0.4f,
            birthTimesMs = appended.birthTimesMs,
            nowMs = 1_200L,
            alphaPerSecond = 2f,
        )
        assertEquals(0.6f, fading.spanStyles.last().item.color.alpha, 0.0001f)
        assertTrue(streamingTailFadeActive(appended.birthTimesMs, nowMs = 1_200L))

        val solid = streamingTailAnnotatedString(
            text = "abcd",
            color = Color.White,
            fadeCodePoints = 4,
            bands = 4,
            newestAlpha = 0.4f,
            birthTimesMs = appended.birthTimesMs,
            nowMs = 2_000L,
            alphaPerSecond = 2f,
        )
        assertTrue(solid.spanStyles.isEmpty())
        assertFalse(streamingTailFadeActive(appended.birthTimesMs, nowMs = 2_000L))
    }

    @Test
    fun directGlyphAlpha_preservesExistingMarkdownSpansAndMetrics() {
        val base = AnnotatedString.Builder().apply {
            append("bold tail")
            addStyle(
                SpanStyle(fontWeight = FontWeight.Bold),
                start = 0,
                end = 4,
            )
        }.toAnnotatedString()

        val faded = streamingTailAnnotatedString(
            text = base,
            color = Color.White,
            fadeCodePoints = 4,
            bands = 2,
            newestAlpha = 0.4f,
        )

        assertEquals(base.text, faded.text)
        assertTrue(
            faded.spanStyles.any {
                it.start == 0 && it.end == 4 && it.item.fontWeight == FontWeight.Bold
            }
        )
        assertEquals(3, faded.spanStyles.size)
    }

    @Test
    fun directGlyphAlphaPreservesInlineContentAnnotation() {
        val base = AnnotatedString.Builder().apply {
            appendInlineContent(
                id = "citation-inline:test",
                alternateText = "[openai.com]",
            )
        }.toAnnotatedString()

        val faded = streamingTailAnnotatedString(
            text = base,
            color = Color.White,
            fadeCodePoints = 4,
            bands = 2,
            newestAlpha = 0.4f,
        )

        assertEquals("[openai.com]", faded.text)
        assertTrue(
            faded.getStringAnnotations(start = 0, end = faded.length)
                .any { it.item == "citation-inline:test" },
        )
    }

    @Test
    fun promotedTail_retainsOriginalGlyphAges() {
        val tracker = StreamingTailFadeTracker(capacity = 8)
        tracker.update("closed\n\nlive", nowMs = 1_000L)

        val promoted = tracker.update("live", nowMs = 1_200L)

        assertArrayEquals(
            longArrayOf(1_000L, 1_000L, 1_000L, 1_000L),
            promoted.birthTimesMs,
        )
    }

    @Test
    fun interactionCommitGate_holdsOnlyTheLatestSnapshotUntilGestureEnds() {
        val gate = StreamingInteractionCommitGate<String>()
        val codeBlock = Any()

        assertEquals("initial", gate.offer("initial"))
        assertNull(gate.setActive(codeBlock, active = true))
        assertNull(gate.offer("stream one"))
        assertNull(gate.offer("stream two"))
        assertEquals("stream two", gate.setActive(codeBlock, active = false))
        assertEquals("terminal", gate.offer("terminal"))
    }

    @Test
    fun interactionCommitGate_waitsForEveryActiveCodeBlockOwner() {
        val gate = StreamingInteractionCommitGate<String>()
        val first = Any()
        val second = Any()

        gate.setActive(first, active = true)
        gate.setActive(second, active = true)
        assertNull(gate.offer("latest"))
        assertNull(gate.setActive(first, active = false))
        assertEquals("latest", gate.setActive(second, active = false))
    }

    @Test
    fun distributeArrivalBirths_assignsPerTokenTimesAcrossConflatedBursts() {
        // Two tokens arrived between parses: "abc" at 1_050 and "abcd" at 1_100, while the last
        // rendered text was "ab". The conflated parse appends 2 code points; each must receive
        // its own token's arrival time instead of one shared timestamp.
        val births = distributeArrivalBirths(
            arrivals = listOf(
                ArrivalRecord(length = 3, timeMs = 1_050L),
                ArrivalRecord(length = 4, timeMs = 1_100L),
            ),
            inputContent = "abcd",
            preparedSource = "abcd",
            appendStart = 2,
            keep = 2,
            nowMs = 1_200L,
        )

        assertArrayEquals(longArrayOf(1_050L, 1_100L), births)
    }

    @Test
    fun distributeArrivalBirths_fallsBackToUniformWhenPreparedDivergesFromInput() {
        val births = distributeArrivalBirths(
            arrivals = listOf(ArrivalRecord(length = 4, timeMs = 1_100L)),
            inputContent = "a\$b\$c\$d",
            preparedSource = "abcd",
            appendStart = 2,
            keep = 2,
            nowMs = 1_200L,
        )

        assertArrayEquals(longArrayOf(1_200L, 1_200L), births)
    }

    @Test
    fun tracker_usesPerCodePointProviderBirthsForConflatedAppends() {
        val tracker = StreamingTailFadeTracker(capacity = 8)
        tracker.update("ab", nowMs = 1_000L)

        val appended = tracker.update("abcd", nowMs = 1_100L) { count ->
            assertEquals(2, count)
            longArrayOf(1_050L, 1_100L)
        }

        assertArrayEquals(
            longArrayOf(1_000L, 1_000L, 1_050L, 1_100L),
            appended.birthTimesMs,
        )
    }

    @Test
    fun blockFadeSpecs_keepPromotedBlockTailAging() {
        // Document: "para one\n\n" (10 cp, promoted) + "cont" (4 cp, live). The fade sample
        // covers the final 6 cp (window start cp 8): the promoted block's last 2 cp and the
        // live block's 4 cp. Both blocks must keep aging instead of snapping to solid.
        val parser = MarkdownParser(flavour)
        val snapshot = StreamingMarkdownSnapshot(
            inputContent = "para one\n\ncont",
            stableBlocks = listOf(
                StableMarkdownBlock(
                    startOffset = 0,
                    endOffset = 10,
                    sourceContent = "para one\n\n",
                    root = parser.buildMarkdownTreeFromString("para one\n\n"),
                )
            ),
            tail = "cont",
            liveBlock = LiveMarkdownBlock(
                startOffset = 10,
                sourceContent = "cont",
                root = parser.buildMarkdownTreeFromString("cont"),
            ),
            isStreaming = true,
            fadeSample = StreamingTailFadeSample(
                observedAtMs = 2_000L,
                birthTimesMs = longArrayOf(1_000L, 1_010L, 1_020L, 1_030L, 1_040L, 1_050L),
            ),
        )

        val specs = computeBlockFadeSpecs(snapshot)

        assertEquals(2, specs.size)
        assertEquals(2, specs[0]?.tailCodePoints)
        assertArrayEquals(longArrayOf(1_000L, 1_010L), specs[0]!!.birthTimesMs)
        assertEquals(4, specs[1]?.tailCodePoints)
        assertArrayEquals(longArrayOf(1_020L, 1_030L, 1_040L, 1_050L), specs[1]!!.birthTimesMs)
    }

    @Test
    fun nodeFade_mapsWindowOverlapAcrossNodes() {
        val spec = StreamingGlyphFadeSpec(
            tailCodePoints = 3,
            birthTimesMs = longArrayOf(1_000L, 1_010L, 1_020L),
        )
        // Block cp count = 10, window covers cp 7..10. Node [char 5, char 8) = cp 5..8 overlaps
        // cp 7..8 (1 cp, first birth entry).
        val midNode = spec.nodeFade(blockContent = "0123456789", nodeStart = 5, nodeEnd = 8)
        assertEquals(1, midNode?.tailCodePoints)
        assertArrayEquals(longArrayOf(1_000L), midNode!!.birthTimesMs)

        // Node fully inside the window (cp 8..10): the last two birth entries.
        val tailNode = spec.nodeFade(blockContent = "0123456789", nodeStart = 8, nodeEnd = 10)
        assertEquals(2, tailNode?.tailCodePoints)
        assertArrayEquals(longArrayOf(1_010L, 1_020L), tailNode!!.birthTimesMs)

        // Node entirely outside the window.
        assertNull(spec.nodeFade(blockContent = "0123456789", nodeStart = 0, nodeEnd = 4))
    }

    private fun Int.splitsSurrogatePair(text: String): Boolean =
        this in 1 until text.length &&
            Character.isHighSurrogate(text[this - 1]) &&
            Character.isLowSurrogate(text[this])
}
