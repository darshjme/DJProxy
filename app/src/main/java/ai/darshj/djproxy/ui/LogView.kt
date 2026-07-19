package ai.darshj.djproxy.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.DjMono
import ai.darshj.djproxy.vpn.LogEvent
import ai.darshj.djproxy.vpn.LogLevel
import java.text.SimpleDateFormat
import java.util.Locale

private fun colorFor(level: LogLevel) = when (level) {
    LogLevel.DEBUG -> DjColors.LogDebug
    LogLevel.INFO -> DjColors.LogInfo
    LogLevel.WARN -> DjColors.LogWarn
    LogLevel.ERROR -> DjColors.LogError
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

/** Scrollable live log, newest at the bottom, auto-scrolling as new lines arrive unless the user
 *  has scrolled up to read history (standard "stick to bottom" behaviour). */
@Composable
fun LogView(events: List<LogEvent>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) {
            val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?.let { it >= events.size - 2 } ?: true
            if (atBottom) {
                listState.animateScrollToItem(events.size - 1)
            }
        }
    }

    if (events.isEmpty()) {
        Text(
            "No activity yet.",
            style = MaterialTheme.typography.bodySmall,
            color = DjColors.TextTertiary,
            modifier = modifier.padding(12.dp),
        )
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
    ) {
        // Key MUST be unique within the list. timeMs+tag+hash collides when the same message logs
        // twice in one millisecond (e.g. a burst of identical DNS lines) — that duplicate-key throw
        // crashed the whole app. The position index guarantees uniqueness.
        itemsIndexed(events, key = { index, e -> "$index-${e.timeMs}-${e.tag}" }) { _, event ->
            LogLine(event)
        }
    }
}

@Composable
private fun LogLine(event: LogEvent) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = timeFormat.format(event.timeMs),
            fontFamily = DjMono,
            style = MaterialTheme.typography.bodySmall,
            color = DjColors.TextTertiary,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = "[${event.tag}]",
            fontFamily = DjMono,
            style = MaterialTheme.typography.bodySmall,
            color = colorFor(event.level),
        )
        Text(
            text = event.message,
            fontFamily = DjMono,
            style = MaterialTheme.typography.bodySmall,
            color = DjColors.TextSecondary,
        )
    }
}
