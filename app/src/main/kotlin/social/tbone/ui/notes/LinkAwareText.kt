package social.tbone.ui.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import social.tbone.ui.theme.BonyColors

/** Matches http/https URLs (no trailing punctuation). */
private val URL_REGEX = Regex("""https?://[^\s<>"')\]]+""")

/**
 * Text with web links recognized and made interactive:
 *  - links are tinted + underlined,
 *  - TAPPING a link opens it in the browser,
 *  - LONG-PRESSING a link copies the URL to the clipboard.
 *
 * Plain segments pass taps through to the parent's clickable, so a row built
 * with this still opens on a normal tap of its non-link text.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LinkSegmentedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val context = LocalContext.current
    val segments = remember(text) { splitByUrls(text) }

    Row(modifier = modifier) {
        segments.forEach { (segment, isLink) ->
            if (segment.isEmpty()) return@forEach
            if (isLink) {
                Text(
                    text = segment,
                    style = style.copy(
                        color = BonyColors.Link,
                        textDecoration = TextDecoration.Underline,
                    ),
                    maxLines = maxLines,
                    overflow = overflow,
                    modifier = Modifier.combinedClickable(
                        onClick = { openUrl(context, segment) },
                        onLongClick = { copyUrl(context, segment) },
                    ),
                )
            } else {
                Text(
                    text = segment,
                    style = style,
                    maxLines = maxLines,
                    overflow = overflow,
                )
            }
        }
    }
}

/** Splits [text] into (segment, isLink) pairs, preserving order. */
private fun splitByUrls(text: String): List<Pair<String, Boolean>> {
    if (text.isEmpty()) return emptyList()
    val matches = URL_REGEX.findAll(text).toList()
    if (matches.isEmpty()) return listOf(text to false)

    val out = ArrayList<Pair<String, Boolean>>()
    var cursor = 0
    matches.forEach { m ->
        if (m.range.first > cursor) out += text.substring(cursor, m.range.first) to false
        out += m.value to true
        cursor = m.range.last + 1
    }
    if (cursor < text.length) out += text.substring(cursor) to false
    return out
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(Intent.createChooser(intent, "open link"))
    }
}

private fun copyUrl(context: Context, url: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", url))
    }
}
