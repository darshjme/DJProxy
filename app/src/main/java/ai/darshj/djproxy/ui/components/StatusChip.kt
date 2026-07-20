package ai.darshj.djproxy.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.store.ProxyStatus
import ai.darshj.djproxy.ui.theme.DjColors
import androidx.compose.foundation.Canvas

/**
 * v6 (§3.4): the compact liveness badge shown on every [ProxyRow]. A coloured dot + a short label —
 * green Reachable (with latency), rose Unreachable (with the typed [ProxyError] reason), an amber
 * spinner while Checking, and a grey "Tap to check" for Unknown. The relative last-checked time is
 * rendered from the status' own `checkedAt` so the list speaks honest, current information.
 *
 * The colours map to the shipped [DjColors] status family (never stock Material), so the chip reads
 * as part of the same dark-glass language as the connect ring's stage colours.
 */
@Composable
fun StatusChip(status: ProxyStatus, modifier: Modifier = Modifier, nowMs: Long = System.currentTimeMillis()) {
    val (dot, label, semantic) = describe(status, nowMs)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(dot.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics { contentDescription = semantic },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (status is ProxyStatus.Checking) {
            CheckingSpinner(color = dot)
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = dot)
    }
}

/** A tiny amber arc that rotates while a check is in flight — respects the reduced-motion setting. */
@Composable
private fun CheckingSpinner(color: Color) {
    val animate = ai.darshj.djproxy.ui.theme.rememberAnimationsEnabled()
    val infinite = rememberInfiniteTransition(label = "check-spin")
    val turn = if (animate) {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
            label = "check-turn",
        ).value
    } else {
        0f
    }
    Canvas(modifier = Modifier.size(10.dp)) {
        rotate(turn) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

private data class ChipSpec(val dot: Color, val label: String, val semantic: String)

private fun describe(status: ProxyStatus, nowMs: Long): ChipSpec = when (status) {
    ProxyStatus.Unknown -> ChipSpec(DjColors.TextTertiary, "Tap to check", "Status unknown. Tap to check.")
    ProxyStatus.Checking -> ChipSpec(DjColors.Amber, "Checking…", "Checking this proxy now.")
    is ProxyStatus.Reachable -> {
        val ago = relativeTime(status.checkedAt, nowMs)
        ChipSpec(
            dot = DjColors.Emerald,
            label = "${status.latencyMs} ms · $ago",
            semantic = "Reachable, ${status.latencyMs} milliseconds, checked $ago.",
        )
    }
    is ProxyStatus.Unreachable -> {
        val ago = relativeTime(status.checkedAt, nowMs)
        ChipSpec(
            dot = DjColors.Rose,
            label = "$ago · ${status.reason}",
            semantic = "Unreachable, checked $ago. ${status.reason}.",
        )
    }
}

/**
 * A compact relative time ("just now", "3 min ago", "2 h ago", "5 d ago") for the last-checked
 * stamp. Pure and deterministic given [nowMs], so it is trivially testable and never allocates a
 * Calendar / DateFormat on the composition path.
 */
fun relativeTime(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val delta = (nowMs - thenMs).coerceAtLeast(0)
    val sec = delta / 1000
    return when {
        sec < 10 -> "just now"
        sec < 60 -> "${sec}s ago"
        sec < 3600 -> "${sec / 60} min ago"
        sec < 86_400 -> "${sec / 3600} h ago"
        else -> "${sec / 86_400} d ago"
    }
}
