package ai.darshj.djproxy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The single source of truth for corner-radius shape tokens (the shape analogue of [DjColors] and
 * [MotionTokens]). Colour, motion, and now shape all live in one tunable place so the Material 3
 * Expressive language stays coherent instead of scattering ad-hoc `RoundedCornerShape(Ndp)` literals
 * across a dozen files. Pills stay fully-rounded (999.dp); everything else steps through a declared
 * scale.
 */
object DjShapes {
    /** Small numbered-step / index badges. */
    val Badge = RoundedCornerShape(8.dp)

    /** Selected segment inside a segmented control. */
    val Tab = RoundedCornerShape(12.dp)

    /** The shell around a segmented-control row. */
    val TabShell = RoundedCornerShape(14.dp)

    /** Raw radius behind [Card] — named separately so [ai.darshj.djproxy.ui.components.GlassSurface]
     *  can default its `Dp`-typed `cornerRadius` parameter to this exact value instead of a second,
     *  driftable `16.dp` literal. */
    val CardRadius = 16.dp

    /** Info / endpoint / status cards inside sheets. */
    val Card = RoundedCornerShape(CardRadius)

    /** Rectangular filled/outline CTAs ([ai.darshj.djproxy.ui.components.DjButton] family) — the
     *  same 14dp step Onboarding's primary buttons already hard-coded, now named and shared. */
    val Button = RoundedCornerShape(14.dp)

    /** Raw radii behind [SourceRest] / [SourcePressed] — named separately (not just baked into the
     *  two `Shape` vals) because the press-morph corner *animates* between them
     *  (`animateDpAsState`, e.g. [ai.darshj.djproxy.ui.components.DjTonalButton]), and Compose has
     *  no built-in way to animate two `Shape` instances directly — only their underlying `Dp`. */
    val SourceRestRadius = 20.dp
    val SourcePressedRadius = 26.dp

    /** Source-strip pill at rest. */
    val SourceRest = RoundedCornerShape(SourceRestRadius)

    /** Source-strip pill while pressed (a touch rounder for the squish). */
    val SourcePressed = RoundedCornerShape(SourcePressedRadius)

    /** The hero glass surface / large elevated cards. */
    val Hero = RoundedCornerShape(28.dp)

    /** Fully-rounded pill. */
    val Pill = RoundedCornerShape(999.dp)
}
