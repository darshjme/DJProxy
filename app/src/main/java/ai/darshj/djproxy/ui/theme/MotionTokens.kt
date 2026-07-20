package ai.darshj.djproxy.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The single source of truth for every DJProxy v4 motion value (§1.4). Nothing else in the ui lane
 * hard-codes a duration, spring, or easing — the ConnectRing, screen swaps, chip cascade, stat
 * counters, splash hand-off, and Tor atmosphere drift all reference these so the product's motion
 * language stays coherent and tunable from one place.
 */
object MotionTokens {

    /** Expressive spring for shape/scale morphs — a touch of overshoot so state changes feel alive. */
    val SpatialSpring = spring<Float>(dampingRatio = 0.55f, stiffness = 380f)
    val ShapeSpring = spring<Float>(dampingRatio = 0.55f, stiffness = 380f)

    /** Color/alpha transitions — no bounce, so tints and glows settle cleanly. */
    val EffectTween = tween<Float>(durationMillis = 300, easing = EaseOutCubic)

    /** The "click shut" easing for the CONNECTED lock. */
    val LockEasing: Easing = EaseOutBack

    /** Screen-swap easing (AnimatedContent). */
    val ScreenEasing: Easing = EaseOutCubic

    /** Gentle in/out used for breathing loops. */
    val BreathEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    const val LOCK_MS = 420
    const val BREATH_IDLE_MS = 1800
    const val BREATH_CONN_MS = 2400
    const val SPIN_VALIDATE_MS = 1100
    const val SPIN_CONNECT_MS = 800
    const val PULSE_RECONNECT_MS = 900
    const val COLLAPSE_MS = 300
    const val SHAKE_MS = 320
    const val SCREEN_MS = 280
    const val CHIP_STAGGER_MS = 60
    const val TOR_TINT_MS = 1200
    const val ONION_ORBIT_MS = 8000
    const val BLOOM_FLASH_MS = 500
    const val SPLASH_MS = 900

    // --- v8 obsidian-orb motion (§orb). The orb reads ALL its durations/amplitudes from here so
    // ObsidianOrb.kt hard-codes nothing; it reuses BREATH_/SPIN_/PULSE_/SHAKE_/BLOOM_FLASH_MS above
    // wherever they already fit and only ADDS the few genuinely orb-specific values below. ---

    /** Idle "float" bob period — deliberately co-prime-ish with WOBBLE so the two never phase-lock. */
    const val ORB_FLOAT_MS = 3800
    /** Idle "wobble" sway period — a different period from FLOAT gives a non-repeating Lissajous drift. */
    const val ORB_WOBBLE_MS = 5200
    /** Idle breathing period — the same gentle cadence as the v4 ring breath. */
    const val ORB_BREATHE_MS = BREATH_IDLE_MS
    /** Specular highlight orbit period at rest — slow enough to read as a 3D surface turning, not a spin. */
    const val ORB_SPECULAR_DRIFT_MS = 9000

    /** Peak vertical float travel (dp) at full IDLE amplitude. */
    const val ORB_FLOAT_DP = 6f
    /** Peak horizontal wobble travel (dp) at full IDLE amplitude. */
    const val ORB_WOBBLE_DP = 4f
    /** Peak breathing scale delta (±) at full IDLE amplitude. */
    const val ORB_BREATHE_SCALE = 0.06f
    /** Busy-state (VALIDATING/CONNECTING/STOPPING) float+wobble damping — motion pulls in ~40%. */
    const val ORB_BUSY_DAMP = 0.4f
    /** CONNECTED settle — breathing survives at ~1/3, float/wobble go to zero. */
    const val ORB_CONNECTED_DAMP = 0.34f
    /** ERROR peak shake travel (dp) of the sphere centre for the single damped flinch. */
    const val ORB_SHAKE_DP = 4f
}

/**
 * Reduced-motion gate (§1.4): honours the system `ANIMATOR_DURATION_SCALE`. When the user has turned
 * animations off (scale == 0), every infinite breath/spin/pulse in the ui falls back to a static
 * per-state frame with only cross-fades. Color is never the sole meaning-carrier regardless — every
 * state also keeps its `stageLabel()` word — so this is purely a comfort/accessibility toggle.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember(context) { mutableStateOf(animationsEnabled(context)) }

    // Reactive, not a one-shot snapshot: observe ANIMATOR_DURATION_SCALE so flipping the OS
    // "Remove animations" / reduced-motion setting while DJProxy is running takes effect immediately
    // (previously it required a process restart).
    DisposableEffect(context) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                enabled = animationsEnabled(context)
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        runCatching { context.contentResolver.registerContentObserver(uri, false, observer) }
        // Re-check in case the value changed between initial capture and registration.
        enabled = animationsEnabled(context)
        onDispose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }

    return enabled
}

fun animationsEnabled(context: Context): Boolean {
    val scale = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    return scale != 0f
}
