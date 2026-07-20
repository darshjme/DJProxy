package ai.darshj.djproxy.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.MotionTokens
import ai.darshj.djproxy.ui.theme.djBackgroundBrush
import ai.darshj.djproxy.ui.theme.djBrandTriBrush
import ai.darshj.djproxy.ui.theme.rememberAnimationsEnabled
import kotlinx.coroutines.launch

/**
 * The branded Compose hand-off (§10). The system SplashScreen can only show a static icon; this is
 * the ~900 ms first frame of MainActivity that gives DJProxy a real launch moment: the brand ring
 * draws itself on (arc 0->360°, tri-tone sweep), the "DJProxy" wordmark fades and rises, and
 * "by darshj.ai" fades in beneath. The ring is the SAME shape + brush as the ConnectRing, so the
 * splash flows continuously into the control surface. Calls [onFinished] when the beat completes.
 */
@Composable
fun SplashHandoff(onFinished: () -> Unit, modifier: Modifier = Modifier, start: Boolean = true) {
    val animate = rememberAnimationsEnabled()
    val ringSweep = remember { Animatable(if (animate) 0f else 360f) }
    val wordmark = remember { Animatable(if (animate) 0f else 1f) }
    val attribution = remember { Animatable(if (animate) 0f else 1f) }
    val exit = remember { Animatable(0f) }

    // One coroutine drives the whole beat so onFinished() fires only AFTER the attribution lockup has
    // fully rendered and the overlay has cross-faded out — previously onFinished ran right after the
    // ring sweep, tearing the splash down before "by darshj.ai" was visible and skipping the fade.
    LaunchedEffect(start, animate) {
        if (!start) return@LaunchedEffect
        if (!animate) {
            onFinished()
            return@LaunchedEffect
        }
        launch { ringSweep.animateTo(360f, tween(600, easing = MotionTokens.ScreenEasing)) }
        launch {
            kotlinx.coroutines.delay(200)
            wordmark.animateTo(1f, tween(320, easing = MotionTokens.ScreenEasing))
        }
        // Attribution rises after the wordmark; wait for it to finish before dismissing.
        kotlinx.coroutines.delay(520)
        attribution.animateTo(1f, tween(320, easing = MotionTokens.ScreenEasing))
        // Brief hold so "by darshj.ai" reads, then exhale the overlay into the control surface.
        kotlinx.coroutines.delay(180)
        exit.animateTo(1f, tween(260, easing = MotionTokens.ScreenEasing))
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(djBackgroundBrush())
            .alpha(1f - exit.value),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(modifier = Modifier.size(120.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                val inset = 10.dp.toPx()
                drawArc(
                    brush = djBrandTriBrush(c),
                    startAngle = -90f,
                    sweepAngle = ringSweep.value,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    style = stroke,
                )
                // inner squircle body hint (matches ConnectRing IDLE body colour)
                drawCircle(
                    color = DjColors.AccentCyan.copy(alpha = 0.12f * (ringSweep.value / 360f)),
                    radius = size.minDimension * 0.28f,
                    center = c,
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                "DJProxy",
                style = MaterialTheme.typography.displayMedium,
                color = DjColors.TextPrimary,
                modifier = Modifier
                    .alpha(wordmark.value)
                    .riseBy((1f - wordmark.value) * 8f),
            )

            Spacer(Modifier.height(8.dp))

            // attribution lockup: "by" · hairline cyan dot · "darshj.ai"
            AttributionLockup(alpha = attribution.value)
        }
    }
}

/** Rises the content up by [dpAmount] density-independent pixels (used for the wordmark entrance). */
private fun Modifier.riseBy(dpAmount: Float): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(IntOffset(0, dpAmount.dp.roundToPx()))
    }
}

@Composable
private fun AttributionLockup(alpha: Float) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.alpha(alpha),
    ) {
        Text("by ", style = MaterialTheme.typography.labelMedium, color = DjColors.TextTertiary)
        Spacer(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(DjColors.AccentCyan),
        )
        Spacer(Modifier.width(6.dp))
        Text("darshj.ai", style = MaterialTheme.typography.labelMedium, color = DjColors.TextTertiary)
    }
}
