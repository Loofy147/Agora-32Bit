package com.newoether.agora.ui.chat.message

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.newoether.agora.util.NoAutoScrollSelectionContainer
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

private const val STREAM_TAIL_FADE_CODE_POINTS = 42
private const val STREAM_TAIL_ALPHA_BANDS = 6
private const val STREAM_TAIL_NEWEST_ALPHA = 0.38f
private const val STREAM_TAIL_ALPHA_PER_SECOND = 2f
private const val STREAM_TAIL_FADE_TICK_MS = 40L
private const val LONG_DOCUMENT_THRESHOLD_CHARS = 8_000
private const val LONG_DOCUMENT_RENDER_INTERVAL_MS = 120L

/**
 * A parsed block whose source range is closed and can therefore keep the same identity for the
 * rest of an append-only generation. Its Markdown tree is built exactly once off the main thread.
 */
@Stable
internal class StableMarkdownBlock(
    val startOffset: Int,
    val endOffset: Int,
    val sourceContent: String,
    val root: ASTNode,
) {
    val identity: Int = 31 * startOffset + sourceContent.hashCode()
}

/**
 * The only mutable Markdown block in an append-only document. It is reparsed off the main thread,
 * but it is always rendered through the exact same Markdown component tree as stable and final
 * blocks. Keeping a dedicated tail identity also preserves its composition across stream → final.
 */
@Stable
internal class LiveMarkdownBlock(
    val startOffset: Int,
    val sourceContent: String,
    val root: ASTNode,
)

@Immutable
internal data class StreamingMarkdownSnapshot(
    val inputContent: String,
    val stableBlocks: List<StableMarkdownBlock>,
    val tail: String,
    val liveBlock: LiveMarkdownBlock?,
    val isStreaming: Boolean,
    val fadeSample: StreamingTailFadeSample? = null,
)

private data class StreamingMarkdownInput(
    val revision: Long,
    val content: String,
    val isStreaming: Boolean,
)

/**
 * Latest-value commit gate used while an embedded code block owns a horizontal gesture.
 *
 * Parsing continues and conflates normally, but Compose keeps the currently measured tree until
 * every active interaction ends. The newest completed snapshot is then committed exactly once.
 */
internal class StreamingInteractionCommitGate<T : Any> {
    private val activeOwners = mutableSetOf<Any>()
    private var pending: T? = null

    fun offer(value: T): T? {
        if (activeOwners.isNotEmpty()) {
            pending = value
            return null
        }
        return value
    }

    fun setActive(owner: Any, active: Boolean): T? {
        if (active) {
            activeOwners += owner
            return null
        }
        activeOwners -= owner
        if (activeOwners.isNotEmpty()) return null
        return pending.also { pending = null }
    }
}

@Stable
internal interface StreamingMarkdownInteractionController {
    fun setCodeBlockScrolling(owner: Any, active: Boolean)
}

internal val LocalStreamingMarkdownInteractionController =
    staticCompositionLocalOf<StreamingMarkdownInteractionController?> { null }

/**
 * Per-block fade window: the final [tailCodePoints] code points of a block's source text overlap
 * the document-level fade window, and [birthTimesMs] holds their per-code-point birth times
 * (oldest first, size == tailCodePoints). Markdown text components map this onto their own node
 * ranges so the tail gradient survives AST restructures, block promotion, and subtree re-keying,
 * without changing any typography, padding, measurement, or Markdown structure.
 */
@Immutable
internal data class StreamingGlyphFadeSpec(
    val tailCodePoints: Int,
    val birthTimesMs: LongArray,
)

internal val LocalStreamingGlyphFadeSpec =
    compositionLocalOf<StreamingGlyphFadeSpec?> { null }

/**
 * Per-node slice of the block fade window: the node's final [tailCodePoints] code points overlap
 * the window and [birthTimesMs] holds their birth times (oldest first).
 */
@Immutable
internal data class StreamingGlyphNodeFade(
    val tailCodePoints: Int,
    val birthTimesMs: LongArray,
)

/**
 * Maps a block-level [StreamingGlyphFadeSpec] onto one text node, using code-point offsets within
 * the node's block source content. Returns null when the node does not overlap the fade window.
 */
internal fun StreamingGlyphFadeSpec?.nodeFade(
    blockContent: String,
    nodeStart: Int,
    nodeEnd: Int,
): StreamingGlyphNodeFade? {
    if (this == null || tailCodePoints <= 0) return null
    val nodeStartCp = blockContent.codePointCount(0, nodeStart)
    val nodeEndCp = blockContent.codePointCount(0, nodeEnd)
    val windowStartCp = blockContent.codePointCount(0, blockContent.length) - tailCodePoints
    val overlapStart = max(nodeStartCp, windowStartCp)
    val overlapCount = nodeEndCp - overlapStart
    if (overlapCount <= 0) return null
    val sliceStart = max(0, nodeStartCp - windowStartCp)
    return StreamingGlyphNodeFade(
        tailCodePoints = overlapCount,
        birthTimesMs = birthTimesMs.copyOfRange(sliceStart, sliceStart + overlapCount),
    )
}

/**
 * Splits the document-level fade sample into one spec per rendered block (stable blocks first,
 * live block last). The sample's birth array covers the final code points of the whole rendered
 * source; each block keeps the slice overlapping its own range, so a just-promoted stable block
 * keeps aging its tail instead of snapping to solid.
 */
internal fun computeBlockFadeSpecs(
    snapshot: StreamingMarkdownSnapshot,
): List<StreamingGlyphFadeSpec?> {
    val births = snapshot.fadeSample?.birthTimesMs ?: LongArray(0)
    if (births.isEmpty()) return emptyList()
    val blocks = snapshot.stableBlocks
    val live = snapshot.liveBlock
    val blockCount = blocks.size + (if (live != null) 1 else 0)
    if (blockCount == 0) return emptyList()

    val cpCounts = IntArray(blockCount) { index ->
        val text = if (index < blocks.size) blocks[index].sourceContent else live!!.sourceContent
        text.codePointCount(0, text.length)
    }
    val totalCp = cpCounts.sum()
    val windowStartCp = max(0, totalCp - births.size)

    val specs = ArrayList<StreamingGlyphFadeSpec?>(blockCount)
    var cursorCp = 0
    for (index in 0 until blockCount) {
        val blockStart = cursorCp
        val blockEnd = cursorCp + cpCounts[index]
        cursorCp = blockEnd
        val overlapStart = max(blockStart, windowStartCp)
        val tailCp = blockEnd - overlapStart
        if (tailCp <= 0) {
            specs.add(null)
            continue
        }
        val sliceStart = max(0, blockStart - windowStartCp)
        specs.add(
            StreamingGlyphFadeSpec(
                tailCodePoints = tailCp,
                birthTimesMs = births.copyOfRange(sliceStart, sliceStart + tailCp),
            )
        )
    }
    return specs
}

@Immutable
internal data class StreamingTailFadeSample(
    val observedAtMs: Long,
    val birthTimesMs: LongArray,
)

/**
 * Retains the arrival time of only the bounded fading suffix. Appends never reset older glyphs;
 * when the Markdown scanner promotes a prefix, suffix timestamps are retained as well.
 *
 * [update] takes a [newBirths] provider that supplies per-code-point birth times for the last
 * [min][kotlin.math.min](appendedCount, capacity) code points of the appended range. Callers
 * without per-token arrival data can rely on the default, which stamps every new glyph with
 * [nowMs].
 */
internal class StreamingTailFadeTracker(
    private val capacity: Int = STREAM_TAIL_FADE_CODE_POINTS,
) {
    private var previousText = ""
    private val birthTimesMs = java.util.ArrayDeque<Long>()

    fun update(
        text: String,
        nowMs: Long,
        newBirths: (count: Int) -> LongArray = { count -> LongArray(count) { nowMs } },
    ): StreamingTailFadeSample {
        require(nowMs >= 0L)
        if (capacity <= 0) {
            previousText = text
            birthTimesMs.clear()
            return StreamingTailFadeSample(nowMs, LongArray(0))
        }

        when {
            text == previousText -> Unit
            text.startsWith(previousText) -> {
                val appendedCount = text.codePointCount(previousText.length, text.length)
                appendBirths(appendedCount, newBirths)
            }
            previousText.endsWith(text) -> {
                // A closed Markdown block was promoted out of the live tail. The remaining text is
                // the old suffix, so its glyph ages remain valid.
                val keep = min(text.codePointCount(0, text.length), capacity)
                while (birthTimesMs.size > keep) birthTimesMs.removeFirst()
            }
            else -> {
                birthTimesMs.clear()
                appendBirths(min(text.codePointCount(0, text.length), capacity), newBirths)
            }
        }
        previousText = text
        return StreamingTailFadeSample(
            observedAtMs = nowMs,
            birthTimesMs = birthTimesMs.map { it }.toLongArray(),
        )
    }

    private fun appendBirths(count: Int, newBirths: (Int) -> LongArray) {
        if (count <= 0) return
        val keep = min(count, capacity)
        val births = newBirths(keep)
        if (count >= capacity) {
            birthTimesMs.clear()
        }
        birthTimesMs.addAll(births.asIterable())
        while (birthTimesMs.size > capacity) birthTimesMs.removeFirst()
    }
}

private data class MarkdownFence(
    val marker: Char,
    val length: Int,
)

/**
 * Kelivo-style append-only scanner with stronger CommonMark fence handling. Only appended code
 * units are scanned. Closed blocks are parsed once; only the still-open tail is reparsed as tokens
 * arrive, and both kinds of block use the same Markdown rendering path.
 */
internal class IncrementalMarkdownDocument(
    private val flavour: MarkdownFlavourDescriptor,
) {
    private var source = ""
    private val stableBlocks = mutableListOf<StableMarkdownBlock>()
    private var scanCursor = 0
    private var lineStart = 0
    private var blockStart = 0
    private var fence: MarkdownFence? = null
    private var finalized = false
    private var liveBlock: LiveMarkdownBlock? = null

    internal var scannedCodeUnits: Long = 0L
        private set

    fun update(
        preparedSource: String,
        inputContent: String,
        isStreaming: Boolean,
    ): StreamingMarkdownSnapshot {
        if (
            preparedSource == source &&
            ((isStreaming && !finalized) || (!isStreaming && finalized))
        ) {
            return snapshot(inputContent, isStreaming)
        }

        val appendOnly = !finalized && preparedSource.startsWith(source)
        if (!appendOnly) {
            reset()
            scannedCodeUnits += preparedSource.length
        } else {
            scannedCodeUnits += preparedSource.length - source.length
        }
        source = preparedSource
        finalized = false

        scanCompletedLines()
        if (!isStreaming) {
            // Do not promote the live tail at terminalization. Its dedicated keyed composition
            // must survive stream → final so selection/status changes cannot replace the Markdown
            // subtree or briefly collapse its measured height.
            finalized = true
        }
        updateLiveBlock()
        return snapshot(inputContent, isStreaming)
    }

    private fun reset() {
        source = ""
        stableBlocks.clear()
        scanCursor = 0
        lineStart = 0
        blockStart = 0
        fence = null
        finalized = false
        liveBlock = null
    }

    private fun scanCompletedLines() {
        while (scanCursor < source.length) {
            val newline = source.indexOf('\n', scanCursor)
            if (newline < 0) {
                // Resume from this position when the incomplete line receives more characters.
                scanCursor = source.length
                return
            }

            val line = source.substring(lineStart, newline).removeSuffix("\r")
            updateFence(line)
            if (fence == null && line.isBlank() && lineStart > blockStart) {
                val end = newline + 1
                if (commit(blockStart, end)) {
                    blockStart = end
                    liveBlock = null
                }
            }
            lineStart = newline + 1
            scanCursor = newline + 1
        }
    }

    private fun updateFence(line: String) {
        var indent = 0
        while (indent < line.length && indent < 4 && line[indent] == ' ') indent++
        if (indent > 3 || indent >= line.length) return

        val marker = line[indent]
        if (marker != '`' && marker != '~') return
        var markerEnd = indent
        while (markerEnd < line.length && line[markerEnd] == marker) markerEnd++
        val markerLength = markerEnd - indent
        if (markerLength < 3) return

        val active = fence
        if (active == null) {
            fence = MarkdownFence(marker, markerLength)
        } else if (
            marker == active.marker &&
            markerLength >= active.length &&
            line.substring(markerEnd).isBlank()
        ) {
            fence = null
        }
    }

    private fun commit(start: Int, end: Int): Boolean {
        if (end <= start) return true
        val blockText = source.substring(start, end)
        if (blockText.isBlank()) return true
        val root = runCatching {
            MarkdownParser(flavour).buildMarkdownTreeFromString(blockText)
        }.getOrNull() ?: return false
        stableBlocks += StableMarkdownBlock(
            startOffset = start,
            endOffset = end,
            sourceContent = blockText,
            root = root,
        )
        return true
    }

    private fun updateLiveBlock() {
        val tail = source.substring(blockStart.coerceIn(0, source.length))
        if (tail.isEmpty()) {
            liveBlock = null
            return
        }
        if (liveBlock?.startOffset == blockStart && liveBlock?.sourceContent == tail) return
        val root = runCatching {
            MarkdownParser(flavour).buildMarkdownTreeFromString(tail)
        }.getOrNull() ?: return
        liveBlock = LiveMarkdownBlock(
            startOffset = blockStart,
            sourceContent = tail,
            root = root,
        )
    }

    private fun snapshot(
        inputContent: String,
        isStreaming: Boolean,
    ): StreamingMarkdownSnapshot = StreamingMarkdownSnapshot(
        inputContent = inputContent,
        stableBlocks = stableBlocks.toList(),
        tail = source.substring(blockStart.coerceIn(0, source.length)),
        liveBlock = liveBlock,
        isStreaming = isStreaming,
    )
}

/**
 * One persistent worker per rendered message. A conflated channel keeps only the newest pending
 * snapshot while the current delta is parsed, so CPU parsing is sequential and can never pile up.
 *
 * Fade birth times live here, not in composition: [offer] records every token arrival (the
 * conflated parse pipeline never sees intermediate content), and the parsed snapshot carries a
 * fade sample aligned with the exact source it renders. Node restructures, block promotion, and
 * subtree re-keying therefore cannot reset or skip the gradient.
 */
@Stable
private class StreamingMarkdownRenderState(
    flavour: MarkdownFlavourDescriptor,
    private val parseInlineDollarMath: Boolean,
    initialContent: String,
    initialIsStreaming: Boolean,
) : StreamingMarkdownInteractionController {
    private val document = IncrementalMarkdownDocument(flavour)
    private val inputs = Channel<StreamingMarkdownInput>(Channel.CONFLATED)
    private val offeredRevision = AtomicLong(0L)
    private val interactionCommitGate =
        StreamingInteractionCommitGate<StreamingMarkdownSnapshot>()
    private val fadeTracker = StreamingTailFadeTracker()
    private val arrivals = kotlin.collections.ArrayDeque<ArrivalRecord>()
    private var lastInputContent = ""
    private var lastPreparedSource = ""
    private val _snapshot = MutableStateFlow(
        StreamingMarkdownSnapshot(
            inputContent = initialContent,
            stableBlocks = emptyList(),
            tail = initialContent,
            liveBlock = null,
            isStreaming = initialIsStreaming,
        )
    )
    val snapshot: StateFlow<StreamingMarkdownSnapshot> = _snapshot.asStateFlow()

    fun offer(content: String, isStreaming: Boolean) {
        val revision = offeredRevision.incrementAndGet()
        if (!content.startsWith(lastInputContent)) {
            // Retraction or replacement: per-arrival history no longer describes the content.
            arrivals.clear()
        }
        arrivals.addLast(ArrivalRecord(content.length, SystemClock.uptimeMillis()))
        while (arrivals.size > MAX_ARRIVAL_RECORDS) arrivals.removeFirst()
        lastInputContent = content
        inputs.trySend(StreamingMarkdownInput(revision, content, isStreaming))
    }

    override fun setCodeBlockScrolling(owner: Any, active: Boolean) {
        interactionCommitGate.setActive(owner, active)?.let { _snapshot.value = it }
    }

    suspend fun run() {
        var lastRenderedAtMs = 0L
        for (received in inputs) {
            var input = received
            while (true) {
                // Inputs are conflated while a long tail waits/parses. Consume the newest value
                // before every cadence decision. Polling at most once per display frame lets a
                // terminal/stop snapshot bypass the long-document 120 ms cadence immediately.
                while (true) {
                    val newer = inputs.tryReceive().getOrNull() ?: break
                    input = newer
                }
                val minimumIntervalMs =
                    if (
                        input.isStreaming &&
                        input.content.length >= LONG_DOCUMENT_THRESHOLD_CHARS
                    ) {
                        LONG_DOCUMENT_RENDER_INTERVAL_MS
                    } else {
                        0L
                    }
                val remainingDelay =
                    (lastRenderedAtMs + minimumIntervalMs - SystemClock.uptimeMillis())
                        .coerceAtLeast(0L)
                if (remainingDelay <= 0L) break
                delay(minOf(remainingDelay, 16L))
            }
            val (next, preparedSource) = withContext(Dispatchers.Default) {
                val preparedSource =
                    input.content.toRenderableMarkdownText(parseInlineDollarMath)
                val next = document.update(
                    preparedSource = preparedSource,
                    inputContent = input.content,
                    isStreaming = input.isStreaming,
                )
                next to preparedSource
            }
            // Fade state is updated here, on the main dispatcher: the arrival history is
            // appended by offer() on the main thread and ArrayDeque is not thread-safe.
            val nowMs = SystemClock.uptimeMillis()
            val fadeSample = fadeTracker.update(preparedSource, nowMs) { keep ->
                distributeArrivalBirths(
                    inputContent = input.content,
                    preparedSource = preparedSource,
                    appendStart = if (preparedSource.startsWith(lastPreparedSource)) {
                        lastPreparedSource.length
                    } else {
                        0
                    },
                    keep = keep,
                    nowMs = nowMs,
                )
            }
            // lastPreparedSource must advance with the tracker even when the revision gate
            // below drops this parse, so the next parse's appended range stays aligned with
            // the tracker's previous text.
            lastPreparedSource = preparedSource
            // Parsing is not cooperatively cancellable. A revision gate provides mapLatest
            // semantics anyway: if tokens arrived during parsing, keep the previous measured tree
            // until the newest parse succeeds instead of flashing this stale snapshot.
            if (offeredRevision.get() == input.revision) {
                interactionCommitGate.offer(next.copy(fadeSample = fadeSample))
                    ?.let { _snapshot.value = it }
                lastRenderedAtMs = SystemClock.uptimeMillis()
            }
        }
    }

    private fun distributeArrivalBirths(
        inputContent: String,
        preparedSource: String,
        appendStart: Int,
        keep: Int,
        nowMs: Long,
    ): LongArray = distributeArrivalBirths(
        arrivals = arrivals,
        inputContent = inputContent,
        preparedSource = preparedSource,
        appendStart = appendStart,
        keep = keep,
        nowMs = nowMs,
    )

    fun close() {
        inputs.close()
    }
}

private const val MAX_ARRIVAL_RECORDS = 256

internal data class ArrivalRecord(
    val length: Int,
    val timeMs: Long,
)

/**
 * Produces per-code-point birth times for the last [keep] code points of the appended range
 * `[appendStart, preparedSource.length)`, walking the offer-time arrival history backward.
 * Positions are approximated as char indices — surrogate pairs may skew by one position, which
 * is invisible inside the bounded fading window. Falls back to [nowMs] when the prepared source
 * diverges from the raw input (LaTeX/dollar transforms) or the range was replaced outright.
 */
internal fun distributeArrivalBirths(
    arrivals: List<ArrivalRecord>,
    inputContent: String,
    preparedSource: String,
    appendStart: Int,
    keep: Int,
    nowMs: Long,
): LongArray {
    if (inputContent != preparedSource || appendStart <= 0) {
        return LongArray(keep) { nowMs }
    }
    val births = LongArray(keep)
    var recordIndex = arrivals.lastIndex
    var fallbackTime = nowMs
    for (i in keep - 1 downTo 0) {
        val position = preparedSource.length - keep + i
        while (recordIndex >= 0 && arrivals[recordIndex].length > position) recordIndex--
        births[i] = if (recordIndex + 1 < arrivals.size) {
            arrivals[recordIndex + 1].timeMs
        } else {
            fallbackTime
        }
        fallbackTime = births[i]
    }
    return births
}

@Composable
internal fun IncrementalStreamingMarkdownContent(
    content: String,
    isStreaming: Boolean,
    renderContext: ChatMarkdownRenderContext,
    modifier: Modifier = Modifier,
    selectionEnabled: Boolean = !isStreaming,
) {
    var hasStreamed by remember { mutableStateOf(isStreaming) }
    SideEffect {
        if (isStreaming) hasStreamed = true
    }

    // A historical message can use the library's normal full-document path. A message that was
    // observed streaming stays on the incremental path during terminalization, preserving every
    // already-rendered stable block instead of replacing the whole subtree at Stop/Done.
    if (!isStreaming && !hasStreamed) {
        MarkdownSelectionHost(selectionEnabled) {
            Column(modifier = modifier) {
                MarkdownTextContent(
                    text = content,
                    renderContext = renderContext,
                )
            }
        }
        return
    }

    val state = remember(renderContext.flavour, renderContext.parseInlineDollarMath) {
        StreamingMarkdownRenderState(
            flavour = renderContext.flavour,
            parseInlineDollarMath = renderContext.parseInlineDollarMath,
            initialContent = content,
            initialIsStreaming = isStreaming,
        )
    }
    LaunchedEffect(state) {
        state.run()
    }
    LaunchedEffect(state, content, isStreaming) {
        // One offer per actual input snapshot. A SideEffect here also ran after fade-clock
        // recompositions and needlessly woke the parser worker for unchanged text.
        state.offer(content, isStreaming)
    }
    DisposableEffect(state) {
        onDispose(state::close)
    }

    val snapshot by state.snapshot.collectAsState()
    val blockFadeSpecs = remember(snapshot) { computeBlockFadeSpecs(snapshot) }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStreamingMarkdownInteractionController provides state,
    ) {
        MarkdownSelectionHost(selectionEnabled) {
            Column(modifier = modifier) {
                snapshot.stableBlocks.forEachIndexed { index, block ->
                    key(block.startOffset, block.identity) {
                        // A stable block inside the document fade window (the just-promoted
                        // block) keeps aging its tail instead of snapping to solid.
                        androidx.compose.runtime.CompositionLocalProvider(
                            LocalStreamingGlyphFadeSpec provides blockFadeSpecs.getOrNull(index),
                        ) {
                            StableMarkdownBlockContent(block, renderContext)
                        }
                    }
                }
                snapshot.liveBlock?.let { block ->
                    // The key is the tail's document start, not its changing content. Appending
                    // text and terminalization retain the Markdown subtree, fade clocks, and the
                    // code block's horizontal ScrollState.
                    key("live-tail", block.startOffset) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            LocalStreamingGlyphFadeSpec provides blockFadeSpecs.lastOrNull(),
                        ) {
                            ParsedMarkdownBlockContent(
                                sourceContent = block.sourceContent,
                                root = block.root,
                                renderContext = renderContext,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownSelectionHost(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    // A stable selection owner is cheaper and safer than replacing the complete Markdown subtree
    // at stream completion. This mirrors Kelivo's persistent SelectionArea; the renderer never
    // changes call sites merely because the message became terminal.
    NoAutoScrollSelectionContainer(
        enabled = enabled,
        content = content,
    )
}

@Composable
private fun StableMarkdownBlockContent(
    block: StableMarkdownBlock,
    renderContext: ChatMarkdownRenderContext,
) {
    ParsedMarkdownBlockContent(
        sourceContent = block.sourceContent,
        root = block.root,
        renderContext = renderContext,
    )
}

@Composable
private fun ParsedMarkdownBlockContent(
    sourceContent: String,
    root: ASTNode,
    renderContext: ChatMarkdownRenderContext,
) {
    val inlineContent = LocalMarkdownInlineContent.current
    val state = remember(sourceContent, root) {
        State.Success(
            node = root,
            content = sourceContent,
            linksLookedUp = false,
            referenceLinkHandler = ReferenceLinkHandlerImpl(),
        )
    }
    com.mikepenz.markdown.compose.Markdown(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        colors = renderContext.colors,
        typography = renderContext.typography,
        padding = renderContext.padding,
        components = renderContext.components,
        annotator = renderContext.annotator,
        imageTransformer = renderContext.imageTransformer,
        inlineContent = inlineContent,
        animations = markdownAnimations { this },
        success = { successState, components, successModifier ->
            Column(successModifier) {
                successState.node.children.forEach { node ->
                    MarkdownElement(
                        node = node,
                        components = components,
                        content = successState.content,
                        // The normal full-document renderer includes the block spacer for its
                        // first node too. Matching that contract removes live/final padding drift.
                        includeSpacer = true,
                    )
                }
            }
        },
    )
}

/**
 * Applies alpha directly to a bounded glyph suffix. Spatial alpha makes the newest glyphs lighter;
 * `alpha(x,t) = min(1, alphaSpatial(x) + k*t)` independently makes every glyph solid with age.
 * Adjacent glyphs with equal alpha are coalesced, keeping the number of spans bounded.
 */
internal fun streamingTailAnnotatedString(
    text: String,
    color: Color,
    fadeCodePoints: Int = STREAM_TAIL_FADE_CODE_POINTS,
    bands: Int = STREAM_TAIL_ALPHA_BANDS,
    newestAlpha: Float = STREAM_TAIL_NEWEST_ALPHA,
    birthTimesMs: LongArray? = null,
    nowMs: Long = 0L,
    alphaPerSecond: Float = STREAM_TAIL_ALPHA_PER_SECOND,
): AnnotatedString = streamingTailAnnotatedString(
    text = AnnotatedString(text),
    color = color,
    fadeCodePoints = fadeCodePoints,
    bands = bands,
    newestAlpha = newestAlpha,
    birthTimesMs = birthTimesMs,
    nowMs = nowMs,
    alphaPerSecond = alphaPerSecond,
)

/**
 * Adds only foreground-color spans to an already-rendered Markdown [AnnotatedString]. Existing
 * emphasis, links, inline-code, search highlights, font metrics, and paragraph layout are retained.
 */
internal fun streamingTailAnnotatedString(
    text: AnnotatedString,
    color: Color,
    fadeCodePoints: Int = STREAM_TAIL_FADE_CODE_POINTS,
    bands: Int = STREAM_TAIL_ALPHA_BANDS,
    newestAlpha: Float = STREAM_TAIL_NEWEST_ALPHA,
    birthTimesMs: LongArray? = null,
    nowMs: Long = 0L,
    alphaPerSecond: Float = STREAM_TAIL_ALPHA_PER_SECOND,
): AnnotatedString {
    if (text.isEmpty() || fadeCodePoints <= 0 || bands <= 0) return text
    val rawText = text.text
    val codePointCount = rawText.codePointCount(0, rawText.length)
    val fadedCount = min(codePointCount, fadeCodePoints)
    if (fadedCount == 0) return text

    val prefixCodePoints = codePointCount - fadedCount
    val actualBands = min(bands, fadedCount)
    val builder = AnnotatedString.Builder().apply { append(text) }
    var rangeStartCodePoint = prefixCodePoints
    var rangeAlpha: Float? = null

    fun flushRange(endCodePoint: Int) {
        val alpha = rangeAlpha ?: return
        if (alpha < 0.999f) {
            builder.addStyle(
                SpanStyle(color = color.copy(alpha = color.alpha * alpha)),
                rawText.offsetByCodePoints(0, rangeStartCodePoint),
                rawText.offsetByCodePoints(0, endCodePoint),
            )
        }
    }

    for (suffixIndex in 0 until fadedCount) {
        val band = suffixIndex * actualBands / fadedCount
        val progress = (band + 1).toFloat() / actualBands.toFloat()
        val spatialAlpha = 1f - progress * (1f - newestAlpha.coerceIn(0f, 1f))
        val bornAt = birthTimesMs
            ?.takeIf { it.size == fadedCount }
            ?.get(suffixIndex)
        val ageSeconds = if (bornAt == null) {
            0f
        } else {
            (nowMs - bornAt).coerceAtLeast(0L) / 1_000f
        }
        val alpha = (spatialAlpha + alphaPerSecond.coerceAtLeast(0f) * ageSeconds)
            .coerceIn(0f, 1f)
        if (rangeAlpha == null) {
            rangeAlpha = alpha
        } else if (kotlin.math.abs(checkNotNull(rangeAlpha) - alpha) > 0.0001f) {
            flushRange(prefixCodePoints + suffixIndex)
            rangeStartCodePoint = prefixCodePoints + suffixIndex
            rangeAlpha = alpha
        }
    }
    flushRange(codePointCount)
    return builder.toAnnotatedString()
}

/**
 * Owns the bounded time component of the glyph fade inside the final Markdown text composable.
 * Only this leaf recomposes while alpha changes; the parser, block column, and LazyColumn do not.
 * Birth times come from the document-level fade sample (via [StreamingGlyphNodeFade]), so node
 * restructures and subtree re-keying cannot reset or skip the gradient.
 */
@Composable
internal fun rememberStreamingGlyphFade(
    content: AnnotatedString,
    color: Color,
    fade: StreamingGlyphNodeFade?,
): AnnotatedString {
    if (fade == null || fade.tailCodePoints <= 0 || content.isEmpty()) return content
    val effective = remember(fade, content.text.length) {
        val displayCodePoints = content.text.codePointCount(0, content.text.length)
        val fadedCount = min(fade.tailCodePoints, displayCodePoints)
        when {
            fadedCount <= 0 -> null
            fadedCount == fade.birthTimesMs.size -> fade
            else -> {
                // Display text can outgrow the node's source range (citation superscripts).
                // Fade the final code points with the newest slice of the birth array.
                StreamingGlyphNodeFade(
                    tailCodePoints = fadedCount,
                    birthTimesMs = fade.birthTimesMs.copyOfRange(
                        fade.birthTimesMs.size - fadedCount,
                        fade.birthTimesMs.size,
                    ),
                )
            }
        }
    }
    if (effective == null) return content
    var fadeClockMs by remember(effective) {
        mutableLongStateOf(SystemClock.uptimeMillis())
    }
    LaunchedEffect(effective) {
        while (
            streamingTailFadeActive(
                birthTimesMs = effective.birthTimesMs,
                nowMs = fadeClockMs,
            )
        ) {
            delay(STREAM_TAIL_FADE_TICK_MS)
            fadeClockMs = SystemClock.uptimeMillis()
        }
    }
    return remember(content, color, effective, fadeClockMs) {
        streamingTailAnnotatedString(
            text = content,
            color = color,
            fadeCodePoints = effective.tailCodePoints,
            birthTimesMs = effective.birthTimesMs,
            nowMs = fadeClockMs,
        )
    }
}

/**
 * Standalone variant for plain-text streaming companions (timeline entries, tool summaries)
 * that do not render through the markdown block pipeline. Each instance keeps its own tracker.
 */
@Composable
internal fun rememberStreamingGlyphFade(
    content: AnnotatedString,
    color: Color,
    enabled: Boolean,
): AnnotatedString {
    if (!enabled || content.isEmpty()) return content

    val fadeTracker = remember { StreamingTailFadeTracker() }
    val fadeSample = remember(content.text, fadeTracker) {
        fadeTracker.update(content.text, SystemClock.uptimeMillis())
    }
    var fadeClockMs by remember(fadeSample) {
        mutableLongStateOf(fadeSample.observedAtMs)
    }
    LaunchedEffect(fadeSample) {
        while (
            streamingTailFadeActive(
                birthTimesMs = fadeSample.birthTimesMs,
                nowMs = fadeClockMs,
            )
        ) {
            delay(STREAM_TAIL_FADE_TICK_MS)
            fadeClockMs = SystemClock.uptimeMillis()
        }
    }
    return remember(content, color, fadeSample, fadeClockMs) {
        streamingTailAnnotatedString(
            text = content,
            color = color,
            birthTimesMs = fadeSample.birthTimesMs,
            nowMs = fadeClockMs,
        )
    }
}

internal fun streamingTailFadeActive(
    birthTimesMs: LongArray,
    nowMs: Long,
    newestAlpha: Float = STREAM_TAIL_NEWEST_ALPHA,
    alphaPerSecond: Float = STREAM_TAIL_ALPHA_PER_SECOND,
): Boolean {
    if (birthTimesMs.isEmpty() || alphaPerSecond <= 0f) return false
    val newestAgeSeconds = (nowMs - birthTimesMs.last()).coerceAtLeast(0L) / 1_000f
    return newestAlpha.coerceIn(0f, 1f) + alphaPerSecond * newestAgeSeconds < 0.999f
}
