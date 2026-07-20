package ai.darshj.djproxy.ui.components

import android.graphics.Paint
import android.graphics.SweepGradient
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.DjShapes

/**
 * The app's lettermark (§ui-center): a bold "DJ" glyph pair painted with the exact same monochrome
 * steel sweep as [ai.darshj.djproxy.ui.theme.djBrandTriBrush] (the SplashHandoff sweep / orb aura),
 * inside a dark rounded badge so the gradient reads crisp against any
 * background. One composable serves every size DJProxy needs it at — ~36-40dp beside the header
 * wordmark ([showBadge] on), larger and badge-less floating over the Splash wash, or as a plain
 * standalone mark on the About screen.
 *
 * Text, not a hand-rolled vector outline: at 24dp a hinted system glyph stays legible where a
 * from-scratch "D"+"J" path risks smearing into an inkblot, and the whole point of a monogram is
 * legibility at small sizes. It is still genuinely *Canvas*-drawn (not a `Text` composable) because
 * the gradient has to be painted as the glyph's own fill: `Paint.shader` takes a platform
 * `android.graphics.Shader`, not a Compose [Brush], so the sweep is rebuilt here as a plain
 * `SweepGradient` with the identical steel stops as `djBrandTriBrush` rather than trying to convert
 * one. v8: the stops are the obsidian-theme steel tokens (no more cyan/violet rainbow).
 */
@Composable
fun DjMonogram(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    showBadge: Boolean = true,
) {
    Canvas(
        modifier = modifier
            .let { base ->
                if (showBadge) {
                    base.size(size).clip(DjShapes.Card).background(badgeBrush())
                } else {
                    base.size(size)
                }
            },
    ) {
        // Named local, not a bare `size` reference: this composable's own parameter is also called
        // `size` (a `Dp`), so the DrawScope's pixel `Size` is captured once up front to keep the two
        // completely unambiguous rather than relying on receiver-shadowing rules.
        val canvasSize = this.size
        val cx = canvasSize.width / 2f
        val cy = canvasSize.height / 2f
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
                textSize = canvasSize.height * 0.52f
                textAlign = Paint.Align.CENTER
                letterSpacing = -0.02f
                shader = SweepGradient(
                    cx, cy,
                    intArrayOf(
                        DjColors.SteelEdge.toArgb(),
                        DjColors.AccentViolet.toArgb(),
                        DjColors.SteelEdgeDeep.toArgb(),
                        DjColors.SteelEdge.toArgb(),
                    ),
                    floatArrayOf(0f, 0.33f, 0.66f, 1f),
                )
            }
            // Centre on font metrics, not a fixed fudge factor, so the glyph sits dead-centre at
            // every size this is rendered at (header 36-40dp, About/Splash larger).
            val metrics = paint.fontMetrics
            val baseline = cy - (metrics.ascent + metrics.descent) / 2f
            canvas.nativeCanvas.drawText("DJ", cx, baseline, paint)
        }
    }
}

/** A steady dark glass tone behind the gradient glyph — never the brand gradient itself, or the
 *  gradient-on-gradient would wash out instead of popping. */
private fun badgeBrush(): Brush = Brush.verticalGradient(
    listOf(DjColors.CharcoalRaised, DjColors.Charcoal),
)
