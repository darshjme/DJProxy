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

    /** Info / endpoint / status cards inside sheets. */
    val Card = RoundedCornerShape(16.dp)

    /** Source-strip pill at rest. */
    val SourceRest = RoundedCornerShape(20.dp)

    /** Source-strip pill while pressed (a touch rounder for the squish). */
    val SourcePressed = RoundedCornerShape(26.dp)

    /** The hero glass surface / large elevated cards. */
    val Hero = RoundedCornerShape(28.dp)

    /** Fully-rounded pill. */
    val Pill = RoundedCornerShape(999.dp)
}
