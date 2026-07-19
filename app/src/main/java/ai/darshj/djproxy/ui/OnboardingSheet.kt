package ai.darshj.djproxy.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.darshj.djproxy.location.LocationCapabilityDetector
import ai.darshj.djproxy.ui.components.GlassSurface
import ai.darshj.djproxy.ui.theme.DjBackgroundBrush
import ai.darshj.djproxy.ui.theme.DjColors
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DjBackgroundBrush)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
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
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        color = DjColors.TextPrimary,
    )
    Text(
        text = "Route your whole phone through one proxy. That's it — no extra setup needed.",
        fontSize = 14.sp,
        color = DjColors.TextSecondary,
    )

    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = DjColors.AccentCyan)
                Text(
                    "Do you want to match your GPS location to the proxy's region?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DjColors.TextPrimary,
                )
            }
            Text(
                "This is entirely optional. Some streaming and region-locked apps also check your GPS " +
                    "coordinate, not just your network address. If you turn this on, DJProxy will set your " +
                    "device's reported location to match wherever the proxy exits — but that requires you to " +
                    "grant it as your \"mock location app\" in Developer Options first. If you'd rather not " +
                    "touch Developer Options at all, choose \"No thanks\" and DJProxy will only route your " +
                    "traffic — your real GPS location is never touched.",
                fontSize = 13.sp,
                color = DjColors.TextSecondary,
            )
        }
    }

    Spacer(Modifier.height(4.dp))

    Button(
        onClick = onChooseYes,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Enable location matching")
    }
    OutlinedButton(
        onClick = onChooseNo,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DjColors.TextSecondary),
    ) {
        Icon(Icons.Filled.LocationOff, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("No thanks, just proxy my traffic")
    }
}

@Composable
private fun GrantStepsContent(context: Context, granted: Boolean, onFinish: () -> Unit) {
    Text(
        text = "Set up location matching",
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        color = DjColors.TextPrimary,
    )
    Text(
        text = "You chose to match your GPS location to the proxy's region. Grant these two things once — " +
            "you can skip and set them up later from Settings; the proxy itself works without them.",
        fontSize = 14.sp,
        color = DjColors.TextSecondary,
    )

    StepCard(
        index = 1,
        icon = { Icon(Icons.Filled.DeveloperMode, contentDescription = null, tint = DjColors.AccentCyan) },
        title = "Enable Developer Options",
        body = "Open Settings → About phone → tap \"Build number\" 7 times until it says " +
            "\"You are now a developer\". (Samsung: About phone → Software information → Build number.)",
        done = false,
        action = {
            OutlinedButton(onClick = { openDeviceInfoSettings(context) }) {
                Text("Open About phone")
            }
        },
    )

    StepCard(
        index = 2,
        icon = { Icon(Icons.Filled.LocationOn, contentDescription = null, tint = DjColors.AccentCyan) },
        title = "Set DJProxy as the mock location app",
        body = "Open Settings → System → Developer options → \"Select mock location app\" → choose " +
            "DJProxy. On some phones it's under Developer options → Debugging.",
        done = granted,
        action = {
            OutlinedButton(onClick = { openDeveloperSettings(context) }) {
                Text(if (granted) "Granted — open again" else "Open Developer options")
            }
        },
    )

    Spacer(Modifier.height(4.dp))

    Button(
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(if (granted) "Done — continue" else "Continue")
    }
    TextButton(
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Skip for now", color = DjColors.TextSecondary)
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (done) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "done",
                        tint = DjColors.Emerald,
                        modifier = Modifier.size(26.dp),
                    )
                } else {
                    Text(
                        text = index.toString(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .size(26.dp)
                            .background(DjColors.AccentCyan, CircleShape)
                            .padding(top = 2.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                icon()
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DjColors.TextPrimary)
            }
            Text(body, fontSize = 13.sp, color = DjColors.TextSecondary)
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
