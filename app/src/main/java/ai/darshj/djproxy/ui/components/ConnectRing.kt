package ai.darshj.djproxy.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.MotionTokens
import ai.darshj.djproxy.ui.theme.djBrandTriBrush
import ai.darshj.djproxy.ui.theme.rememberAnimationsEnabled
import ai.darshj.djproxy.vpn.VpnStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * The v4 hero (§1.5). Wraps — never replaces — [ConnectButton]: it honours the exact same
 * `(stage: VpnStage, onClick)` contract and the same [stageLabel] word, so state and visuals can
 * never drift. The only addition is [torBootstrapPct]: when non-null and the tunnel has not yet
 * begun its own VALIDATING/CONNECTING, the ring renders the UI-synthetic `PREPARING_TOR` stage with
 * a determinate arc tracking real Tor bootstrap progress (§3). VpnState.stage remains the sole
 * authority for VALIDATING -> CONNECTED -> ERROR; PREPARING_TOR is a purely visual pre-stage.
 *
 * Composition, outside-in: ambient bloom -> progress arc (tri-tone sweep) -> morphing superellipse
 * body (softens on CONNECTED, hardens on ERROR) -> power glyph core. The morph is hand-rolled on a
 * Canvas (superellipse exponent + lobe modulation animated per state), so the ring is fully
 * self-contained and needs no shape library.
 */
@Composable
fun ConnectRing(
    stage: VpnStage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    torBootstrapPct: Int? = null,
) {
    val preparingTor = torBootstrapPct != null &&
        (stage == VpnStage.IDLE || stage == VpnStage.ERROR)
    val visual = ringVisualFor(stage, preparingTor)
    val busy = stage == VpnStage.VALIDATING || stage == VpnStage.CONNECTING ||
        stage == VpnStage.STOPPING || preparingTor

    val animate = rememberAnimationsEnabled()
    val haptics = LocalHapticFeedback.current

    // The signature "lock": the ring closes and the body pops the moment CONNECTED is reached.
    LaunchedEffect(stage) {
        if (stage == VpnStage.CONNECTED) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Morph parameters — animated so state changes glide rather than snap.
    // §1.4/§1.5: the shape morph carries the expressive spring (a touch of overshoot) so IDLE->CONNECTED
    // softens and ->ERROR hardens with life, rather than easing in flatly on a cubic tween.
    val squareness by animateFloatAsState(
        targetValue = visual.squareness,
        animationSpec = MotionTokens.ShapeSpring,
        label = "squareness",
    )
    val lobeAmp by animateFloatAsState(
        targetValue = visual.lobeAmp,
        animationSpec = MotionTokens.ShapeSpring,
        label = "lobe-amp",
    )
    val lockProgress by animateFloatAsState(
        targetValue = if (stage == VpnStage.CONNECTED) 1f else 0f,
        animationSpec = tween(MotionTokens.LOCK_MS, easing = MotionTokens.LockEasing),
        label = "lock",
    )
    val bloomFlash by animateFloatAsState(
        targetValue = if (stage == VpnStage.CONNECTED) 1f else 0f,
        animationSpec = tween(MotionTokens.BLOOM_FLASH_MS, easing = MotionTokens.ScreenEasing),
        label = "bloom-flash",
    )

    // Infinite loops (breath / spin / pulse), all gated behind reduced-motion.
    val infinite = rememberInfiniteTransition(label = "ring-infinite")
    val breath = if (animate) {
        infinite.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(visual.breathMs, easing = MotionTokens.BreathEasing),
                RepeatMode.Reverse,
            ),
            label = "breath",
        ).value
    } else 0.5f
    val spin = if (animate) {
        infinite.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(visual.spinMs), RepeatMode.Restart),
            label = "spin",
        ).value
    } else 0f
    val pulse = if (animate) {
        infinite.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(MotionTokens.PULSE_RECONNECT_MS, easing = MotionTokens.BreathEasing),
                RepeatMode.Reverse,
            ),
            label = "pulse",
        ).value
    } else 0.5f

    // ERROR single shake (one damped horizontal wobble, then still).
    val shake by animateFloatAsState(
        targetValue = if (stage == VpnStage.ERROR) 1f else 0f,
        animationSpec = tween(MotionTokens.SHAKE_MS),
        label = "shake",
    )
    val shakeOffset = if (stage == VpnStage.ERROR) sin(shake * PI.toFloat() * 3f) * 4f * (1f - shake) else 0f

    // Body breathing scale (idle ±3% / connected calm).
    val bodyScale = when {
        !animate -> 1f
        stage == VpnStage.IDLE -> 1f + (breath - 0.5f) * 0.06f
        stage == VpnStage.CONNECTED -> 1f + (breath - 0.5f) * 0.03f + lockProgress * 0f
        stage == VpnStage.RECONNECTING -> 1f + (pulse - 0.5f) * 0.08f
        else -> 1f
    }

    val interaction = remember { MutableInteractionSource() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(200.dp)
            .selectable(
                selected = stage == VpnStage.CONNECTED,
                interactionSource = interaction,
                indication = null,
                enabled = !busy,
                onClick = onClick,
            )
            .semantics {
                contentDescription = if (preparingTor) {
                    "Building Tor circuit ${torBootstrapPct ?: 0} percent"
                } else {
                    stageLabel(stage)
                }
            },
    ) {
        Canvas(
            modifier = Modifier
                .size(200.dp)
                .scale(1f),
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val bloomAlpha = when {
                stage == VpnStage.CONNECTED -> 0.28f + bloomFlash * 0.32f
                stage == VpnStage.RECONNECTING -> 0.20f + pulse * 0.2f
                busy -> 0.22f
                else -> 0.14f + (breath) * 0.08f
            }

            // 1) ambient bloom (200 dp radial)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(visual.glow.copy(alpha = bloomAlpha), Color.Transparent),
                    center = c,
                    radius = size.minDimension / 2f,
                ),
                radius = size.minDimension / 2f,
                center = c,
            )

            // 2) progress arc (168 dp)
            val arcInset = (size.minDimension - 168.dp.toPx()) / 2f
            val arcSize = Size(size.width - arcInset * 2f, size.height - arcInset * 2f)
            val arcTopLeft = Offset(arcInset, arcInset)
            val strokePx = 6.dp.toPx()
            drawProgressArc(
                stage = stage,
                preparingTor = preparingTor,
                torPct = torBootstrapPct ?: 0,
                center = c,
                topLeft = arcTopLeft,
                arcSize = arcSize,
                strokePx = strokePx,
                spin = spin,
                pulse = pulse,
                lockProgress = lockProgress,
                accent = visual.accent,
            )

            // 3) morphing superellipse body (128 dp), translated by the ERROR shake
            rotate(0f, pivot = c) {
                val bodyR = 64.dp.toPx() * bodyScale
                val path = superellipsePath(
                    center = Offset(c.x + shakeOffset.dp.toPx(), c.y),
                    radius = bodyR,
                    squareness = squareness,
                    lobeCount = visual.lobeCount,
                    lobeAmp = lobeAmp,
                )
                drawPath(path = path, brush = visual.bodyBrush)
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = 0.22f),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // 4) glyph core — cross-fades between states, sits above the Canvas body.
        AnimatedContent(
            targetState = if (preparingTor) VpnStage.CONNECTING else stage,
            label = "ring-glyph",
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.85f)) togetherWith fadeOut(tween(150))
            },
        ) { s ->
            when {
                preparingTor -> CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    color = DjColors.TextPrimary,
                    strokeWidth = 3.dp,
                )
                s == VpnStage.VALIDATING || s == VpnStage.CONNECTING || s == VpnStage.STOPPING ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        color = DjColors.TextOnAccent,
                        strokeWidth = 3.dp,
                    )
                s == VpnStage.CONNECTED -> PowerCoreGlyph(scale = 1f + lockProgress * 0.12f * (1f - lockProgress) * 4f)
                s == VpnStage.ERROR -> PowerCoreGlyph(error = true)
                else -> PowerCoreGlyph()
            }
        }
    }
}

/** The power glyph at the ring's core — a rounded vertical bar over an implied broken circle. */
@Composable
private fun PowerCoreGlyph(error: Boolean = false, scale: Float = 1f) {
    Box(
        modifier = Modifier.size(32.dp).scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(32.dp)) {
            val color = DjColors.TextOnAccent
            val cx = size.width / 2f
            val strokeW = size.width * 0.11f
            // vertical bar
            drawLine(
                color = color,
                start = Offset(cx, size.height * (if (error) 0.24f else 0.16f)),
                end = Offset(cx, size.height * 0.52f),
                strokeWidth = strokeW,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            // arc under it (open power ring)
            val inset = size.width * 0.2f
            drawArc(
                color = color,
                startAngle = if (error) 130f else 120f,
                sweepAngle = if (error) 280f else 300f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(width = strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}

/** Draws the state-appropriate ring (dormant outline / spinning sweep / closed lock / broken gap). */
private fun DrawScope.drawProgressArc(
    stage: VpnStage,
    preparingTor: Boolean,
    torPct: Int,
    center: Offset,
    topLeft: Offset,
    arcSize: Size,
    strokePx: Float,
    spin: Float,
    pulse: Float,
    lockProgress: Float,
    accent: Color,
) {
    val triBrush = djBrandTriBrush(center)
    val stroke = Stroke(width = strokePx, cap = androidx.compose.ui.graphics.StrokeCap.Round)

    when {
        preparingTor -> {
            // determinate Tor bootstrap arc
            drawArc(color = DjColors.TorPurple.copy(alpha = 0.18f), startAngle = 0f, sweepAngle = 360f,
                useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            drawArc(color = DjColors.TorPurple, startAngle = -90f, sweepAngle = torPct.coerceIn(0, 100) * 3.6f,
                useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        }
        stage == VpnStage.VALIDATING || stage == VpnStage.CONNECTING -> {
            drawArc(color = accent.copy(alpha = 0.14f), startAngle = 0f, sweepAngle = 360f,
                useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            drawArc(brush = triBrush, startAngle = spin, sweepAngle = 270f,
                useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        }
        stage == VpnStage.CONNECTED -> {
            drawArc(color = DjColors.Emerald.copy(alpha = 0.16f), startAngle = 0f, sweepAngle = 360f,
                useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            drawArc(color = DjColors.Emerald, startAngle = -90f, sweepAngle = 360f * lockProgress,
                useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        }
        stage == VpnStage.RECONNECTING -> {
            drawArc(color = DjColors.Amber.copy(alpha = 0.12f + pulse * 0.2f), startAngle = 0f, sweepAngle = 360f,
                useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            drawArc(color = DjColors.Amber.copy(alpha = 0.5f + pulse * 0.5f), startAngle = spin, sweepAngle = 160f,
                useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        }
        stage == VpnStage.STOPPING -> {
            drawArc(brush = triBrush, startAngle = spin, sweepAngle = 200f,
                useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        }
        stage == VpnStage.ERROR -> {
            // broken ring with a deliberate gap
            drawArc(color = DjColors.Rose, startAngle = -60f, sweepAngle = 300f,
                useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        }
        else -> {
            // IDLE — thin dormant outline
            drawArc(color = DjColors.AccentCyan.copy(alpha = 0.25f), startAngle = 0f, sweepAngle = 360f,
                useCenter = false, topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokePx * 0.5f))
        }
    }
}

/**
 * Builds a closed superellipse path with optional lobe modulation. [squareness] 2 ≈ circle, growing
 * toward a hard-cornered square as it increases; [lobeAmp] gives the idle body its soft breathing
 * lobes and the error body its sharpened 4-lobe silhouette.
 */
private fun superellipsePath(
    center: Offset,
    radius: Float,
    squareness: Float,
    lobeCount: Int,
    lobeAmp: Float,
    steps: Int = 96,
): Path {
    val path = Path()
    val exp = 2f / squareness.coerceAtLeast(2f)
    for (i in 0..steps) {
        val t = (2.0 * PI * i / steps)
        val ct = cos(t).toFloat()
        val st = sin(t).toFloat()
        val x = sign(ct) * abs(ct).toDouble().pow(exp)
        val y = sign(st) * abs(st).toDouble().pow(exp)
        val mod = 1f + lobeAmp * cos(lobeCount * t).toFloat()
        val px = center.x + radius * mod * x
        val py = center.y + radius * mod * y
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    return path
}

private fun Double.pow(e: Float): Float = Math.pow(this, e.toDouble()).toFloat()

/** All per-state ring visuals in one place so nothing drifts. */
private data class RingVisual(
    val squareness: Float,
    val lobeCount: Int,
    val lobeAmp: Float,
    val bodyBrush: Brush,
    val accent: Color,
    val glow: Color,
    val breathMs: Int,
    val spinMs: Int,
)

private fun cyanBody() = Brush.verticalGradient(listOf(DjColors.AccentCyan, DjColors.AccentIndigo))
private fun connectingBody() = Brush.verticalGradient(listOf(DjColors.AccentCyan, DjColors.AccentViolet))
private fun emeraldBody() = Brush.verticalGradient(listOf(DjColors.Emerald, DjColors.EmeraldDeep))
private fun amberBody() = Brush.verticalGradient(listOf(DjColors.Amber, DjColors.AmberDeep))
private fun roseBody() = Brush.verticalGradient(listOf(DjColors.Rose, DjColors.RoseDeep))
private fun torBody() = Brush.verticalGradient(listOf(DjColors.TorPurple, DjColors.TorPurpleDeep))

private fun ringVisualFor(stage: VpnStage, preparingTor: Boolean): RingVisual {
    if (preparingTor) {
        return RingVisual(2f, 0, 0f, torBody(), DjColors.TorPurple, DjColors.GlowTor,
            MotionTokens.BREATH_CONN_MS, MotionTokens.SPIN_CONNECT_MS)
    }
    return when (stage) {
        VpnStage.IDLE -> RingVisual(3.2f, 6, 0.03f, cyanBody(), DjColors.AccentCyan, DjColors.GlowCyan,
            MotionTokens.BREATH_IDLE_MS, MotionTokens.SPIN_CONNECT_MS)
        VpnStage.VALIDATING -> RingVisual(2.0f, 0, 0f, cyanBody(), DjColors.AccentCyan, DjColors.GlowCyan,
            MotionTokens.BREATH_IDLE_MS, MotionTokens.SPIN_VALIDATE_MS)
        VpnStage.CONNECTING -> RingVisual(2.2f, 0, 0f, connectingBody(), DjColors.AccentViolet, DjColors.GlowCyan,
            MotionTokens.BREATH_IDLE_MS, MotionTokens.SPIN_CONNECT_MS)
        VpnStage.CONNECTED -> RingVisual(3.6f, 0, 0f, emeraldBody(), DjColors.Emerald, DjColors.GlowEmerald,
            MotionTokens.BREATH_CONN_MS, MotionTokens.SPIN_CONNECT_MS)
        VpnStage.RECONNECTING -> RingVisual(2.6f, 0, 0f, amberBody(), DjColors.Amber, DjColors.GlowEmerald,
            MotionTokens.BREATH_CONN_MS, MotionTokens.SPIN_CONNECT_MS)
        VpnStage.STOPPING -> RingVisual(2.4f, 0, 0f, cyanBody(), DjColors.AccentCyan, DjColors.GlowCyan,
            MotionTokens.BREATH_IDLE_MS, MotionTokens.SPIN_CONNECT_MS)
        VpnStage.ERROR -> RingVisual(8f, 4, 0.04f, roseBody(), DjColors.Rose, DjColors.Rose.copy(alpha = 0.4f),
            MotionTokens.BREATH_IDLE_MS, MotionTokens.SPIN_CONNECT_MS)
    }
}
