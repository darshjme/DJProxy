package ai.darshj.djproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.DjShapes

/**
 * A frosted-glass card: a translucent gradient fill over a diagonal hairline border, sitting on
 * a soft shadow. No real backdrop blur is available (no extra render dependency in this module),
 * so the "frosted" read comes from layered alpha + a light-catch gradient border instead — this
 * is the honest, dependency-free approximation of glassmorphism on stock Compose.
 *
 * Default corner radius is [DjShapes.Card] (16dp) — the shared shape-token scale, not a bare
 * literal. The hero call sites that want the larger 28dp treatment ([DjShapes.Hero]) pass it
 * explicitly (e.g. the validation-error card, sheets) exactly as before; this only changes the
 * *default* every other `GlassSurface { }` call inherits.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = DjShapes.CardRadius,
    borderBrush: Brush = Brush.linearGradient(
        listOf(DjColors.GlassBorderTop, DjColors.GlassBorderBottom),
    ),
    fill: Brush = Brush.verticalGradient(
        listOf(DjColors.GlassFillStrong, DjColors.GlassFill),
    ),
    contentPadding: Dp = 20.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .shadow(elevation = 18.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(fill)
            .border(1.dp, borderBrush, shape)
            .padding(contentPadding),
    ) {
        content()
    }
}

/** A thin gradient hairline used to separate sections without a hard Material divider. */
@Composable
fun GradientHairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, DjColors.HairlineStrong, Color.Transparent),
                ),
                RectangleShape,
            ),
    )
}
