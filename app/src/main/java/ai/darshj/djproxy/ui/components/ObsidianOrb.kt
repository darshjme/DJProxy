package ai.darshj.djproxy.ui.components

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.MotionTokens
import ai.darshj.djproxy.ui.theme.djBrandTriBrush
import ai.darshj.djproxy.ui.theme.rememberAnimationsEnabled
import ai.darshj.djproxy.vpn.VpnStage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The v8 hero (§orb): a reactive obsidian-black floating/dancing orb that REPLACES the teal
 * [ConnectRing] look. It is a drop-in replacement — same `(stage: VpnStage, onClick, …)` contract,
 * the same reduced-motion gate, the same haptic-on-CONNECTED, the same semantics/StageLabel so state
 * (and never colour alone) is always conveyed — and `vpnState.stage` stays the sole authority.
 *
 * The sphere itself is ALWAYS black/gray: a glossy graphite radial body with a single travelling
 * specular fleck and a blurred rim halo. Only the aura / rim-light / specular TINT reacts per state
 * (steel idle -> muted-emerald connected -> muted-amber reconnecting -> muted-rose error -> graphite
 * Tor purple preparing); the fill is never tinted.
 *
 * Rendering is two-tier and consumes ONE set of [OrbVisual] + motion values so both look identical:
 *  - REQUIRED (API 21+): a pure-Canvas radial+specular sphere, blurred rim via [BlurMaskFilter].
 *  - ENHANCED (guarded, API 33+): an AGSL [android.graphics.RuntimeShader] sphere (analytic radial
 *    body + additive specular) drawn via [ShaderBrush] in the SAME DrawScope. Strictly additive — if
 *    the shader were ever unavailable the guard silently ships the Canvas path with zero risk.
 *
 * The idle "dance" is deliberately non-metronomic: three independent-period sine phases (float dy,
 * wobble dx at a different period+phase, breathing scale) plus a slow specular orbit, all off one
 * [rememberInfiniteTransition] and all gated behind [rememberAnimationsEnabled] with static
 * mid-value fallbacks (exactly as ConnectRing gates breath/spin/pulse). Every duration/amplitude is
 * read from [MotionTokens] — nothing is hard-coded here.
 */
// Process-global capability flag: flipped false the first time the BlurMaskFilter rim draw throws on a
// hostile/emulated GPU, permanently skipping that one cosmetic native draw so the orb never crashes.
@Volatile
private var orbRimBlurOk = true

@Composable
fun ObsidianOrb(
    stage: VpnStage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    orbSize: Dp = 200.dp,
    torBootstrapPct: Int? = null,
) {
    // --- State-machine plumbing carried over verbatim from ConnectRing (NOT ring-specific) ---
    val preparingTor = torBootstrapPct != null &&
        (stage == VpnStage.IDLE || stage == VpnStage.ERROR)
    val visual = orbVisualFor(stage, preparingTor)
    val busy = stage == VpnStage.VALIDATING || stage == VpnStage.CONNECTING ||
        stage == VpnStage.STOPPING || preparingTor
    // Tor bootstrap is the ONE "busy" pre-stage that stays tappable — a second tap is the documented
    // cancel gesture (ProxyViewModel.onRingTap -> cancelTorPrepare). Every genuine tunnel busy-stage
    // stays non-interactive.
    val clickable = !busy || preparingTor

    val animate = rememberAnimationsEnabled()
    val haptics = LocalHapticFeedback.current

    // The signature "lock" moment carried over: a single confirming pulse the instant CONNECTED lands.
    LaunchedEffect(stage) {
        if (stage == VpnStage.CONNECTED) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Per-state reaction targets, animated so state changes glide (mirrors ConnectRing's squareness/
    // lobeAmp animateFloatAsState on MotionTokens.ShapeSpring).
    val floatAmp by animateFloatAsState(
        targetValue = visual.floatAmp,
        animationSpec = MotionTokens.ShapeSpring,
        label = "orb-float-amp",
    )
    val breatheAmp by animateFloatAsState(
        targetValue = visual.breatheAmp,
        animationSpec = MotionTokens.ShapeSpring,
        label = "orb-breathe-amp",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = visual.glowAlpha,
        animationSpec = MotionTokens.ShapeSpring,
        label = "orb-glow-alpha",
    )
    val specAlpha by animateFloatAsState(
        targetValue = visual.specAlpha,
        animationSpec = MotionTokens.ShapeSpring,
        label = "orb-spec-alpha",
    )
    // One-shot specular "pop" that fires at the CONNECTED transition (replaces the old ring-lock),
    // on the BLOOM_FLASH_MS timing.
    val bloomFlash by animateFloatAsState(
        targetValue = if (stage == VpnStage.CONNECTED) 1f else 0f,
        animationSpec = tween(MotionTokens.BLOOM_FLASH_MS, easing = MotionTokens.ScreenEasing),
        label = "orb-bloom-flash",
    )

    // --- Idle "dance": >=3 independent periods so it never reads as a single metronome ---
    val infinite = rememberInfiniteTransition(label = "orb-infinite")
    val floatPhase = if (animate) {
        infinite.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(MotionTokens.ORB_FLOAT_MS, easing = LinearEasing),
                RepeatMode.Reverse,
            ),
            label = "orb-float",
        ).value
    } else 0.5f
    val wobblePhase = if (animate) {
        infinite.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(MotionTokens.ORB_WOBBLE_MS, easing = LinearEasing),
                RepeatMode.Reverse,
            ),
            label = "orb-wobble",
        ).value
    } else 0.5f
    val breathe = if (animate) {
        infinite.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(MotionTokens.ORB_BREATHE_MS, easing = MotionTokens.BreathEasing),
                RepeatMode.Reverse,
            ),
            label = "orb-breathe",
        ).value
    } else 0.5f
    // Specular orbit — period is state-driven (slow at rest, faster while validating/connecting), so
    // the highlight travels the surface and sells the 3D read even when the body is still.
    val specularDrift = if (animate) {
        infinite.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(
                tween(visual.specularDriftMs, easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "orb-specular",
        ).value
    } else STATIC_SPECULAR_DEG
    // RECONNECTING re-introduces amplitude at pulse rate (faster, shakier wobble).
    val pulse = if (animate) {
        infinite.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(MotionTokens.PULSE_RECONNECT_MS, easing = MotionTokens.BreathEasing),
                RepeatMode.Reverse,
            ),
            label = "orb-pulse",
        ).value
    } else 0.5f

    // ERROR single damped shake (identical form to ConnectRing.kt:155-160), applied as a translation
    // of the sphere centre.
    val shake by animateFloatAsState(
        targetValue = if (stage == VpnStage.ERROR) 1f else 0f,
        animationSpec = tween(MotionTokens.SHAKE_MS),
        label = "orb-shake",
    )
    val shakeFrac = if (stage == VpnStage.ERROR) sin(shake * PI.toFloat() * 3f) * (1f - shake) else 0f

    // Derived, gated dance offsets (fractions in [-1, 1]).
    val floatFrac = if (animate) sin(floatPhase * TWO_PI) else 0f
    val wobbleFrac = if (animate) sin(wobblePhase * TWO_PI + WOBBLE_PHASE) else 0f
    val reconnectFrac = if (animate && stage == VpnStage.RECONNECTING) sin(pulse * TWO_PI) else 0f

    // AGSL shader is remembered once (construction is the version-gated/expensive part); uniform sets
    // per frame are cheap. remember{} runs on all APIs, so the guard lives INSIDE the lambda and the
    // RuntimeShader class is only touched on API 33+.
    val useShader = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val orbShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) buildOrbShader() else null
    }
    // Reused native paint for the blurred rim halo (BlurMaskFilter is API 21+; NOT a RuntimeShader).
    val rimPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }

    // Immediate down-press acknowledgement so the primary CTA never reads as unresponsive.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(120),
        label = "orb-press",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(orbSize)
            .scale(pressScale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = clickable,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = if (preparingTor) {
                    "Building Tor circuit ${torBootstrapPct ?: 0} percent. Tap to cancel."
                } else {
                    stageLabel(stage)
                }
                stateDescription = if (stage == VpnStage.CONNECTED) "Connected" else "Disconnected"
            },
    ) {
        Canvas(modifier = Modifier.size(orbSize)) {
            val baseC = Offset(size.width / 2f, size.height / 2f)
            val orbR = size.minDimension * ORB_RADIUS_FRAC *
                (if (animate) 1f + (breathe - 0.5f) * MotionTokens.ORB_BREATHE_SCALE * breatheAmp else 1f)

            // Dance translation of the sphere centre (float dy / wobble dx / reconnect shimmy / shake).
            val dx = (wobbleFrac * MotionTokens.ORB_WOBBLE_DP * floatAmp +
                reconnectFrac * MotionTokens.ORB_WOBBLE_DP +
                shakeFrac * MotionTokens.ORB_SHAKE_DP).dp.toPx()
            val dy = (floatFrac * MotionTokens.ORB_FLOAT_DP * floatAmp).dp.toPx()
            val c = Offset(baseC.x + dx, baseC.y + dy)

            // Key light fixed above-left (the sphere's form light stays put); only the small specular
            // fleck orbits, so the black body reads solid rather than swimming.
            val gradCenter = Offset(c.x - orbR * 0.35f, c.y - orbR * 0.35f)
            val gradRadius = orbR * 1.15f
            val driftRad = specularDrift * DEG2RAD
            val specAnchor = Offset(c.x - orbR * 0.10f, c.y - orbR * 0.10f)
            val specCenter = Offset(
                specAnchor.x + cos(driftRad) * orbR * 0.30f,
                specAnchor.y + sin(driftRad) * orbR * 0.30f,
            )
            val specR = orbR * 0.18f

            val bloomAlpha = when {
                stage == VpnStage.CONNECTED -> 0.24f + bloomFlash * 0.28f
                stage == VpnStage.RECONNECTING -> 0.18f + pulse * 0.18f
                busy -> 0.20f
                stage == VpnStage.ERROR -> 0.22f
                else -> 0.12f + (if (animate) breathe else 0.5f) * 0.10f
            }

            // 1) ambient bloom — fixed at the untranslated centre so the sphere dances within its halo.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(visual.accent.copy(alpha = bloomAlpha), Color.Transparent),
                    center = baseC,
                    radius = size.minDimension / 2f,
                ),
                radius = size.minDimension / 2f,
                center = baseC,
            )

            // 2) blurred rim halo BEHIND the body: the inner half is covered by the opaque sphere, so
            //    only the outer bleed shows — a soft state-accent glow hugging the black edge.
            //    A BlurMaskFilter on the hardware-accelerated nativeCanvas is fragile on some emulated
            //    GPUs (LDPlayer / older-Android software renderers) and can throw in the draw phase,
            //    which would crash the whole app on the very first frame. Guard it: on the first failure
            //    disable the rim glow permanently (purely cosmetic) so the orb still renders everywhere.
            if (orbRimBlurOk) {
                val drew = runCatching {
                    rimPaint.style = android.graphics.Paint.Style.STROKE
                    rimPaint.strokeWidth = orbR * 0.06f
                    rimPaint.color = visual.accent.copy(alpha = glowAlpha).toArgb()
                    rimPaint.maskFilter = BlurMaskFilter(orbR * 0.16f, BlurMaskFilter.Blur.NORMAL)
                    drawContext.canvas.nativeCanvas.drawCircle(c.x, c.y, orbR, rimPaint)
                }.isSuccess
                if (!drew) orbRimBlurOk = false
            }

            // 3) the sphere body — glossy graphite radial. Shader path (API 33+) folds the specular in;
            //    Canvas path draws body + specular separately. Both consume the identical values.
            if (useShader && orbShader != null) {
                drawShaderSphere(
                    shader = orbShader,
                    center = c,
                    radius = orbR,
                    gradCenter = gradCenter,
                    gradRadius = gradRadius,
                    specCenter = specCenter,
                    specRadius = specR,
                    spec = specAlpha,
                    highlight = DjColors.OrbHighlight,
                    mid = DjColors.Slate,
                    low = DjColors.CharcoalRaised,
                    rim = DjColors.VoidBlack,
                )
            } else {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to DjColors.OrbHighlight,
                        0.35f to DjColors.Slate,
                        0.75f to DjColors.CharcoalRaised,
                        1f to DjColors.VoidBlack,
                        center = gradCenter,
                        radius = gradRadius,
                    ),
                    radius = orbR,
                    center = c,
                )
                // Travelling specular fleck (glossy read).
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(DjColors.TextPrimary.copy(alpha = specAlpha), Color.Transparent),
                        center = specCenter,
                        radius = specR,
                    ),
                    radius = specR,
                    center = specCenter,
                )
            }

            // 4) icy edge-light crescent on the lit upper-right terminator — the single cool accent
            //    the icon brief foreshadows; drawn ON the black body at low alpha.
            val cInset = orbR * 0.02f
            drawArc(
                color = visual.accent.copy(alpha = (glowAlpha * 0.9f).coerceAtMost(0.9f)),
                startAngle = -115f,
                sweepAngle = 95f,
                useCenter = false,
                topLeft = Offset(c.x - orbR + cInset, c.y - orbR + cInset),
                size = Size((orbR - cInset) * 2f, (orbR - cInset) * 2f),
                style = Stroke(width = orbR * 0.045f, cap = StrokeCap.Round),
            )

            // 5) progress ornaments AROUND the sphere (never on the black body):
            //    - preparingTor: determinate graphite Tor bootstrap arc.
            //    - busy: swirling steel aura arc, rotating at the specular period.
            val auraR = orbR * 1.18f
            val auraTl = Offset(c.x - auraR, c.y - auraR)
            val auraSize = Size(auraR * 2f, auraR * 2f)
            val auraStroke = Stroke(width = orbR * 0.05f, cap = StrokeCap.Round)
            when {
                preparingTor -> {
                    drawArc(
                        color = DjColors.TorPurple.copy(alpha = 0.18f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = auraTl, size = auraSize, style = auraStroke,
                    )
                    drawArc(
                        color = DjColors.TorPurple,
                        startAngle = -90f,
                        sweepAngle = (torBootstrapPct ?: 0).coerceIn(0, 100) * 3.6f,
                        useCenter = false, topLeft = auraTl, size = auraSize, style = auraStroke,
                    )
                }
                visual.showAura -> {
                    drawArc(
                        color = visual.accent.copy(alpha = 0.12f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = auraTl, size = auraSize, style = auraStroke,
                    )
                    drawArc(
                        brush = djBrandTriBrush(c),
                        startAngle = specularDrift, sweepAngle = 270f, useCenter = false,
                        topLeft = auraTl, size = auraSize, style = auraStroke,
                    )
                }
            }
        }
    }
}

/** All per-state orb visuals in one place so nothing drifts (mirrors ConnectRing's RingVisual). The
 *  [accent] is the ONLY reactive colour — it tints the rim halo, edge-light crescent, aura and bloom;
 *  the sphere body is always graphite. Amplitudes are fractions of the [MotionTokens] peaks. */
private data class OrbVisual(
    val accent: Color,
    val floatAmp: Float,
    val breatheAmp: Float,
    val specAlpha: Float,
    val glowAlpha: Float,
    val specularDriftMs: Int,
    val showAura: Boolean,
)

private fun orbVisualFor(stage: VpnStage, preparingTor: Boolean): OrbVisual {
    if (preparingTor) {
        // Tor bootstrap uses the determinate arc, not the swirling aura; motion is damped like busy.
        return OrbVisual(
            accent = DjColors.TorPurple,
            floatAmp = MotionTokens.ORB_BUSY_DAMP,
            breatheAmp = MotionTokens.ORB_BUSY_DAMP,
            specAlpha = 0.5f,
            glowAlpha = 0.5f,
            specularDriftMs = MotionTokens.SPIN_CONNECT_MS,
            showAura = false,
        )
    }
    return when (stage) {
        VpnStage.IDLE -> OrbVisual(
            accent = DjColors.SteelEdge,
            floatAmp = 1f, breatheAmp = 1f,
            specAlpha = 0.55f, glowAlpha = 0.30f,
            specularDriftMs = MotionTokens.ORB_SPECULAR_DRIFT_MS, showAura = false,
        )
        VpnStage.VALIDATING -> OrbVisual(
            accent = DjColors.SteelEdge,
            floatAmp = MotionTokens.ORB_BUSY_DAMP, breatheAmp = MotionTokens.ORB_BUSY_DAMP,
            specAlpha = 0.5f, glowAlpha = 0.42f,
            specularDriftMs = MotionTokens.SPIN_VALIDATE_MS, showAura = true,
        )
        VpnStage.CONNECTING -> OrbVisual(
            accent = DjColors.AccentViolet,
            floatAmp = MotionTokens.ORB_BUSY_DAMP, breatheAmp = MotionTokens.ORB_BUSY_DAMP,
            specAlpha = 0.5f, glowAlpha = 0.45f,
            specularDriftMs = MotionTokens.SPIN_CONNECT_MS, showAura = true,
        )
        VpnStage.CONNECTED -> OrbVisual(
            accent = DjColors.Emerald,
            floatAmp = 0f, breatheAmp = MotionTokens.ORB_CONNECTED_DAMP,
            specAlpha = 0.7f, glowAlpha = 0.5f,
            specularDriftMs = MotionTokens.SPIN_CONNECT_MS, showAura = false,
        )
        VpnStage.RECONNECTING -> OrbVisual(
            accent = DjColors.Amber,
            floatAmp = 0.9f, breatheAmp = 0.9f,
            specAlpha = 0.5f, glowAlpha = 0.45f,
            specularDriftMs = MotionTokens.PULSE_RECONNECT_MS, showAura = false,
        )
        VpnStage.STOPPING -> OrbVisual(
            accent = DjColors.SteelEdge,
            floatAmp = MotionTokens.ORB_BUSY_DAMP, breatheAmp = MotionTokens.ORB_BUSY_DAMP,
            specAlpha = 0.5f, glowAlpha = 0.4f,
            specularDriftMs = MotionTokens.SPIN_CONNECT_MS, showAura = true,
        )
        VpnStage.ERROR -> OrbVisual(
            accent = DjColors.Rose,
            floatAmp = 0f, breatheAmp = 0.6f,
            // The orb "flinches": specular flattens/dims on error.
            specAlpha = 0.22f, glowAlpha = 0.5f,
            specularDriftMs = MotionTokens.SPIN_CONNECT_MS, showAura = false,
        )
    }
}

/** AGSL source for the enhanced sphere: analytic radial body (highlight -> mid -> low -> rim by
 *  normalised distance from the key-light centre) plus an additive specular lobe. No samplers/
 *  textures, so it carries no external dependency. Body geometry is clipped by the drawCircle radius,
 *  so the shader returns opaque and never needs edge-alpha maths. */
private const val ORB_AGSL = """
uniform float2 uGradCenter;
uniform float uGradRadius;
uniform float2 uSpecCenter;
uniform float uSpecRadius;
uniform float uSpec;
uniform float3 uHighlight;
uniform float3 uMid;
uniform float3 uLow;
uniform float3 uRim;
half4 main(float2 frag) {
    float d = clamp(length(frag - uGradCenter) / uGradRadius, 0.0, 1.0);
    float3 col = mix(uHighlight, uMid, smoothstep(0.0, 0.35, d));
    col = mix(col, uLow, smoothstep(0.35, 0.75, d));
    col = mix(col, uRim, smoothstep(0.75, 1.0, d));
    float s = clamp(length(frag - uSpecCenter) / uSpecRadius, 0.0, 1.0);
    col = col + uSpec * (1.0 - smoothstep(0.0, 1.0, s));
    return half4(col, 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun buildOrbShader(): android.graphics.RuntimeShader = android.graphics.RuntimeShader(ORB_AGSL)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawShaderSphere(
    shader: android.graphics.RuntimeShader,
    center: Offset,
    radius: Float,
    gradCenter: Offset,
    gradRadius: Float,
    specCenter: Offset,
    specRadius: Float,
    spec: Float,
    highlight: Color,
    mid: Color,
    low: Color,
    rim: Color,
) {
    shader.setFloatUniform("uGradCenter", gradCenter.x, gradCenter.y)
    shader.setFloatUniform("uGradRadius", gradRadius)
    shader.setFloatUniform("uSpecCenter", specCenter.x, specCenter.y)
    shader.setFloatUniform("uSpecRadius", specRadius)
    shader.setFloatUniform("uSpec", spec)
    shader.setFloatUniform("uHighlight", highlight.red, highlight.green, highlight.blue)
    shader.setFloatUniform("uMid", mid.red, mid.green, mid.blue)
    shader.setFloatUniform("uLow", low.red, low.green, low.blue)
    shader.setFloatUniform("uRim", rim.red, rim.green, rim.blue)
    drawCircle(brush = ShaderBrush(shader), radius = radius, center = center)
}

// Layout ratios (not motion) — the sphere occupies ~34% of the min dimension, comfortably inside the
// TorOnionOrbit wrapper. Kept local; every *motion* value lives in MotionTokens.
private const val ORB_RADIUS_FRAC = 0.34f
private const val TWO_PI = (2.0 * PI).toFloat()
private const val DEG2RAD = (PI / 180.0).toFloat()
/** Wobble phase offset so float/wobble form a Lissajous, not a diagonal line. */
private const val WOBBLE_PHASE = 1.20f
/** Static specular angle when reduced-motion is on — fixed top-left (cos/sin both negative). */
private const val STATIC_SPECULAR_DEG = 225f
