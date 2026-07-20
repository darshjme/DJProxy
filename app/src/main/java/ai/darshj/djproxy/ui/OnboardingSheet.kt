package ai.darshj.djproxy.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.location.LocationCapabilityDetector
import ai.darshj.djproxy.ui.components.CenteredScreen
import ai.darshj.djproxy.ui.components.DjButton
import ai.darshj.djproxy.ui.components.DjOutlineButton
import ai.darshj.djproxy.ui.components.GlassSurface
import ai.darshj.djproxy.ui.components.StepBadge
import ai.darshj.djproxy.ui.theme.DjBackgroundBrush
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.DjSpacing
import kotlinx.coroutines.delay

/**
 * First-run onboarding. Fires once after install (gated by [OnboardingState]).
 *
 * Location matching (a.k.a. GPS spoofing) is an explicit, OPT-IN choice — never forced on anyone.
 * The flow is two stages:
 *
 *   [Stage.CHOICE]      — "Do you want to match your GPS location to the proxy's region?" with a
 *                          clear Yes ("Enable location matching") / No ("No thanks, just proxy my
 *                          traffic") pair. The choice is persisted via [LocationPreference] the
 *                          instant it is made, so it survives even if onboarding is killed mid-flow.
 *   [Stage.GRANT_STEPS] — shown ONLY after choosing Yes. Walks the user through the two grants
 *                          location-spoofing needs on unrooted Android:
 *                            1. Enable Developer Options (About phone → tap Build number 7×).
 *                            2. Select DJProxy as the "mock location app" (Developer Options →
 *                               Select mock location app).
 *
 * Choosing No skips straight to the app — no dev-options detour is ever shown to someone who didn't
 * ask for location matching. It never blocks the core proxy flow either way — the user can Skip and
 * still paste-and-connect; location spoofing simply stays UNAVAILABLE (honestly reported) until both
 * the opt-in choice AND the grant are in place.
 *
 * OEM paths differ (Samsung One UI, Xiaomi HyperOS, stock, emulators), so the copy states the
 * generic path and the deep-link buttons jump as close as each OS allows; we never claim a single
 * exact tap-path works everywhere.
 */
@Composable
fun OnboardingSheet(onFinish: () -> Unit) {
    val context = LocalContext.current
    var stage by rememberSaveable { mutableStateOf(Stage.CHOICE) }

    var granted by remember { mutableStateOf(LocationCapabilityDetector.isMockLocationAppGranted(context)) }
    // Poll so the "mock location" step self-confirms when the user comes back from Settings.
    // Only runs once the user has opted in and reached the grant-steps stage.
    LaunchedEffect(stage) {
        if (stage != Stage.GRANT_STEPS) return@LaunchedEffect
        while (true) {
            granted = LocationCapabilityDetector.isMockLocationAppGranted(context)
            delay(1500)
        }
    }

    // §ui-center: CenteredScreen replaces the hand-rolled fillMaxSize+verticalScroll+padding Column —
    // Onboarding is the same "short, fixed cluster" shape as Home, so it gets the same ~600dp reading
    // column (no more edge-to-edge stretch on the Fold7 unfolded pane) and the same centred-at-rest /
    // scrolls-if-it-overflows behaviour, instead of a bespoke 22/28dp padding nobody else shares.
    CenteredScreen(
        modifier = Modifier.background(DjBackgroundBrush),
        verticalArrangement = Arrangement.spacedBy(DjSpacing.xl, Alignment.CenterVertically),
    ) {
        when (stage) {
            Stage.CHOICE -> LocationChoiceContent(
                onChooseYes = {
                    LocationPreference.setEnabled(context, true)
                    stage = Stage.GRANT_STEPS
                },
                onChooseNo = {
                    LocationPreference.setEnabled(context, false)
                    onFinish()
                },
            )
            Stage.GRANT_STEPS -> GrantStepsContent(
                context = context,
                granted = granted,
                onFinish = onFinish,
            )
        }
    }
}

private enum class Stage { CHOICE, GRANT_STEPS }

@Composable
private fun LocationChoiceContent(onChooseYes: () -> Unit, onChooseNo: () -> Unit) {
    Text(
        text = "Welcome to DJProxy",
        style = MaterialTheme.typography.headlineSmall,
        color = DjColors.TextPrimary,
    )
    Text(
        text = "Route your whole phone through one proxy. That's it — no extra setup needed.",
        style = MaterialTheme.typography.bodyMedium,
        color = DjColors.TextSecondary,
    )

    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(DjSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DjSpacing.md)) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = DjColors.AccentCyan)
                Text(
                    "Do you want to match your GPS location to the proxy's region?",
                    style = MaterialTheme.typography.titleMedium,
                    color = DjColors.TextPrimary,
                )
            }
            Text(
                "Optional. Some streaming / region-locked apps also check your GPS, not just your network " +
                    "address. Turn this on to match your reported location to the proxy's exit — it needs a " +
                    "one-time \"mock location app\" grant in Developer Options. Prefer not to? Choose " +
                    "\"No thanks\" and DJProxy only routes your traffic; your real GPS is never touched.",
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextSecondary,
            )
        }
    }

    Spacer(Modifier.height(4.dp))

    DjButton(
        onClick = onChooseYes,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(DjSpacing.lg))
        Spacer(Modifier.width(8.dp))
        Text("Enable location matching")
    }
    DjOutlineButton(
        onClick = onChooseNo,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.LocationOff, contentDescription = null, modifier = Modifier.size(DjSpacing.lg))
        Spacer(Modifier.width(8.dp))
        Text("No thanks, just proxy my traffic")
    }
}

@Composable
private fun GrantStepsContent(context: Context, granted: Boolean, onFinish: () -> Unit) {
    Text(
        text = "Set up location matching",
        style = MaterialTheme.typography.headlineSmall,
        color = DjColors.TextPrimary,
    )
    Text(
        text = "You chose to match your GPS location to the proxy's region. Grant these two things once — " +
            "you can skip and set them up later from Settings; the proxy itself works without them.",
        style = MaterialTheme.typography.bodyMedium,
        color = DjColors.TextSecondary,
    )

    StepCard(
        index = 1,
        icon = { Icon(Icons.Filled.DeveloperMode, contentDescription = null, tint = DjColors.AccentCyan) },
        title = "Enable Developer Options",
        body = "Open Settings → About phone → tap \"${buildNumberFieldLabel()}\" 7 times until it says " +
            "\"You are now a developer\".",
        done = false,
        action = {
            DjOutlineButton(onClick = { openDeviceInfoSettings(context) }, contentColor = DjColors.AccentCyan) {
                Text("Open About phone")
            }
        },
    )

    StepCard(
        index = 2,
        icon = { Icon(Icons.Filled.LocationOn, contentDescription = null, tint = DjColors.AccentCyan) },
        title = "Set DJProxy as the mock location app",
        body = "${devOptionsPathHint()} → \"Select mock location app\" → choose DJProxy.",
        done = granted,
        action = {
            DjOutlineButton(onClick = { openDeveloperSettings(context) }, contentColor = DjColors.AccentCyan) {
                Text(if (granted) "Granted — open again" else "Open Developer options")
            }
        },
    )

    Spacer(Modifier.height(4.dp))

    DjButton(
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (granted) "Done — continue" else "Continue")
    }
    DjOutlineButton(
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Skip for now")
    }
}

@Composable
private fun StepCard(
    index: Int,
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    done: Boolean,
    action: @Composable () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(DjSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DjSpacing.md)) {
                StepBadge(index = index, done = done)
                icon()
                Text(title, style = MaterialTheme.typography.titleMedium, color = DjColors.TextPrimary)
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = DjColors.TextSecondary)
            action()
        }
    }
}

/** Deep-link to the device-info page where Build number lives; falls back to top-level Settings. */
private fun openDeviceInfoSettings(context: Context) {
    val intents = listOf(
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    launchFirstResolvable(context, intents)
}

/** Deep-link straight to Developer options; falls back to top-level Settings if the OEM blocks it. */
private fun openDeveloperSettings(context: Context) {
    val intents = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    launchFirstResolvable(context, intents)
}

private fun launchFirstResolvable(context: Context, intents: List<Intent>) {
    for (i in intents) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(i) }.onSuccess { return }
        }
    }
}

/** One-line first-run flag. Persisted in a tiny prefs file so onboarding shows exactly once. */
object OnboardingState {
    private const val PREFS = "djproxy_onboarding"
    private const val KEY_SEEN = "seen_v1"

    fun shouldShow(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SEEN, true).apply()
    }
}
