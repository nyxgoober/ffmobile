package us.crafties.ffmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.crafties.ffmobile.ui.LogLine

/** Max lines actually composed. The ViewModel keeps up to 2000; only the tail is drawn. */
private const val MAX_VISIBLE_LINES = 600

@Composable
fun LogConsole(lines: List<LogLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // Stable keys (monotonic ids) so appending one line doesn't rebind the whole list.
    val visible = remember(lines) {
        if (lines.size > MAX_VISIBLE_LINES) lines.takeLast(MAX_VISIBLE_LINES) else lines
    }
    // scrollToItem, not animateScrollToItem: instant jump, no per-line animation jank
    // while ffmpeg streams dozens of progress lines per second.
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) listState.scrollToItem(visible.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B0B0E))
            .padding(12.dp)
    ) {
        items(visible, key = { it.id }, contentType = { "log" }) { line ->
            Text(
                text = line.text,
                color = colorForLine(line.text),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
    }
}

private fun colorForLine(line: String): Color = when {
    line.contains("error", ignoreCase = true) -> Color(0xFFFF6B6B)
    line.contains("warning", ignoreCase = true) -> Color(0xFFFFC857)
    line.startsWith("frame=") || line.contains("frame=") -> Color(0xFF7FE3B4)
    else -> Color(0xFFD8D8DE)
}
