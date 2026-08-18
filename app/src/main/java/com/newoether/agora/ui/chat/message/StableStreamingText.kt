package com.newoether.agora.ui.chat.message

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle

/**
 * Plain-text companion to streaming Markdown. The text/layout input is always the final,
 * undecorated value; only draw alpha changes while new glyphs settle.
 */
@Composable
internal fun StableStreamingText(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle,
    color: Color,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fadeState = rememberStreamingGlyphFadeDrawState(
        content = text,
        enabled = streaming,
    )
    Text(
        text = text,
        modifier = modifier.stableStreamingGlyphFade(
            fadeState = fadeState,
            layoutResult = { layoutResult },
        ),
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layoutResult = it },
    )
}
