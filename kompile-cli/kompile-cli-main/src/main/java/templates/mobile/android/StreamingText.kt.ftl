package {{packageName}}.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

/**
 * A composable that animates text appearance character by character,
 * simulating a typing/streaming effect.
 */
@Composable
fun StreamingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    characterDelayMs: Long = 15L,
    onComplete: (() -> Unit)? = null
) {
    var displayedLength by remember { mutableIntStateOf(0) }
    var previousText by remember { mutableStateOf("") }
    var isComplete by remember { mutableStateOf(false) }

    // When text changes, determine how much new content to animate
    LaunchedEffect(text) {
        if (text.startsWith(previousText)) {
            // Text was appended - animate only the new characters
            val startFrom = displayedLength
            for (i in startFrom..text.length) {
                displayedLength = i
                if (i < text.length) {
                    delay(characterDelayMs)
                }
            }
        } else {
            // Text was replaced entirely - animate from scratch
            displayedLength = 0
            for (i in 0..text.length) {
                displayedLength = i
                if (i < text.length) {
                    delay(characterDelayMs)
                }
            }
        }
        previousText = text

        if (displayedLength >= text.length && !isComplete) {
            isComplete = true
            onComplete?.invoke()
        }
    }

    val displayText = if (displayedLength <= text.length) {
        text.substring(0, displayedLength)
    } else {
        text
    }

    // Add a blinking cursor effect while streaming
    val cursorChar = if (!isComplete && displayedLength < text.length) "\u258C" else ""

    Text(
        text = displayText + cursorChar,
        modifier = modifier,
        style = style
    )
}

/**
 * Streaming text that shows content as it arrives via a flow,
 * without per-character animation. Useful for real-time token display.
 */
@Composable
fun TokenStreamText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    showCursor: Boolean = true
) {
    val cursor = if (showCursor) "\u258C" else ""

    Text(
        text = text + cursor,
        modifier = modifier,
        style = style
    )
}
