package ai.darshj.djproxy.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.darshj.djproxy.compat.CapabilityDetector
import ai.darshj.djproxy.ui.components.GlassSurface
import ai.darshj.djproxy.ui.components.StepBadge
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.vpn.FeatureRegistry
import ai.darshj.djproxy.vpn.VpnState
import ai.darshj.djproxy.vpn.seams.LocationCapability
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The settings HOST screen (§9.5, §11). This lane owns only this hosting shell: the header, the
 * always-honest "Network" info panel (DNS has no manual-mode seam — see below), the numbered
 * Developer-Options / mock-location instructions card, and the generic loop that renders whatever
 * [FeatureRegistry.settingsPanels] the location/hotspot/diagnostics lanes have registered. Each
 * panel renders its OWN Compose content in its OWN package (§9.5) — this file never reaches into
 * location/hotspot/diag internals, only the seam surfaces (`FeatureRegistry`, `CapabilityDetector`,
 * `VpnState.health`) that core/compat expose publicly.
 */
@Composable
fun SettingsScreen(
    vpnState: VpnState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAbout: () -> Unit = {},
    onOpenServers: () -> Unit = {},
) {
    val context = LocalContext.current
    var locationMatchingEnabled by remember { mutableStateOf(LocationPreference.isEnabled(context)) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DjColors.TextPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Text("Settings", style = MaterialTheme.typography.headlineSmall, color = DjColors.TextPrimary)
        }

        SectionHeader("PROXIES", topPadding = 4.dp)
        GlassSurface(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenServers)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Saved proxies", style = MaterialTheme.typography.titleMedium, color = DjColors.TextPrimary)
                    Text(
                        "Your proxy vault, live status, and the free public server list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DjColors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = DjColors.TextSecondary,
                )
            }
        }

        SectionHeader("NETWORK")
        NetworkInfoPanel(vpnState)

        SectionHeader("LOCATION MATCHING")
        LocationMatchingToggleCard(
            enabled = locationMatchingEnabled,
            onToggle = { newValue ->
                locationMatchingEnabled = newValue
                LocationPreference.setEnabled(context, newValue)
            },
        )
        if (locationMatchingEnabled) {
            Spacer(Modifier.height(12.dp))
            MockLocationInstructionsCard()
        }

        SectionHeader("MORE")
        FeaturePanelsHost(locationMatchingEnabled = locationMatchingEnabled)

        SectionHeader("ABOUT")
        GlassSurface(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAbout)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("About DJProxy", style = MaterialTheme.typography.titleMedium, color = DjColors.TextPrimary)
                    Text(
                        "Version, licenses, and source code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DjColors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = DjColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Top-level, explicit opt-in toggle for GPS location matching — mirrors the same choice offered in
 * [OnboardingSheet], persisted through the same [LocationPreference] flag so the two surfaces never
 * disagree. When OFF, the grant-instructions card and the location lane's own capability panel are
 * both hidden (see [FeaturePanelsHost]) — there is nothing to configure for a feature the user chose
 * not to use. Flipping this OFF does not itself revoke the OS-level mock-location app-op grant (only
 * the user can do that in Developer Options); it flips DJProxy's own gate so spoofing stays off
 * regardless of that grant, per the location lane's honest-capability contract.
 */
@Composable
private fun LocationMatchingToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Location matching",
                        style = MaterialTheme.typography.titleMedium,
                        color = DjColors.TextPrimary,
                    )
                    Text(
                        "Match your GPS coordinate to the proxy's exit region.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DjColors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DjColors.AccentCyan,
                        checkedTrackColor = DjColors.AccentCyanDeep,
                    ),
                )
            }
            Text(
                text = if (enabled) {
                    "On — DJProxy will try to publish the proxy exit's location once the mock-location " +
                        "grant below is set. Your explicit choice; turn it off any time."
                } else {
                    "Off — this is opt-in. DJProxy only routes your traffic; your real GPS location is " +
                        "never touched, even if the mock-location app-op happens to be granted."
                },
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextTertiary,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * DNS is resolved automatically at the proxy exit (§3): DoH:443 -> DoT:853 -> TCP:53, sticky on
 * whichever transport last worked. There is NO seam to force a mode (core ships one working
 * composite strategy, by design, so no lane is required to wire DNS) — so rather than fake a
 * selector that does nothing, this panel honestly shows which transport is active right now.
 */
@Composable
private fun NetworkInfoPanel(vpnState: VpnState) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text("Network", style = MaterialTheme.typography.titleMedium, color = DjColors.TextPrimary)
            Text(
                "DNS resolves through the proxy exit automatically — DoH:443 first, falling back to " +
                    "DoT:853 then TCP:53 only if the exit blocks the faster transport. There is no manual " +
                    "mode to pick; the label below shows whichever transport is currently serving.",
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            val health = vpnState.health
            val label = when {
                vpnState.stage != ai.darshj.djproxy.vpn.VpnStage.CONNECTED &&
                    vpnState.stage != ai.darshj.djproxy.vpn.VpnStage.RECONNECTING -> "Not connected"
                health == null -> "Checking…"
                health.activeDnsStrategy.isNotBlank() -> "Mode: Auto — currently ${health.activeDnsStrategy}"
                else -> "Mode: Auto"
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, color = DjColors.TextPrimary)
            if (health != null) {
                Spacer(Modifier.height(10.dp))
                AdvisoryChipsRow(health = health)
            }
        }
    }
}

/**
 * Renders every registered [SettingsPanel] EXCEPT the location lane's own panel (id "location") when
 * the user has not opted in to location matching — see [LocationMatchingToggleCard]. This is a
 * display-only filter in the ui lane; it never reaches into the location lane's internals, it just
 * declines to render its panel until the persisted opt-in choice says otherwise.
 */
@Composable
private fun FeaturePanelsHost(locationMatchingEnabled: Boolean) {
    val panels = remember(locationMatchingEnabled) {
        FeatureRegistry.settingsPanels
            .filter { locationMatchingEnabled || it.id != "location" }
            .sortedBy { it.order }
    }
    if (panels.isEmpty()) {
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text("More settings", style = MaterialTheme.typography.titleMedium, color = DjColors.TextPrimary)
                Text(
                    "Location spoofing, hotspot sharing, and diagnostics reporting each ship their own " +
                        "settings panel here once that module is installed and registered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DjColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        panels.forEach { panel ->
            GlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(panel.title, style = MaterialTheme.typography.titleMedium, color = DjColors.TextPrimary)
                    Spacer(Modifier.height(10.dp))
                    panel.Content()
                }
            }
        }
    }
}

/** Uppercase section label used to group the Settings host cards into scannable clusters. */
@Composable
private fun SectionHeader(text: String, topPadding: androidx.compose.ui.unit.Dp = 12.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = DjColors.TextTertiary,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = topPadding, bottom = 8.dp),
    )
}

// OEM dev-options copy (buildNumberFieldLabel / devOptionsPathHint / manufacturerIs*) now lives in the
// shared ui/DevOptionsGuidance.kt so Onboarding and Settings can never disagree on the exact path.

private fun openSettingsIntent(action: String, context: Context, fallbackToRoot: Boolean = true) {
    val intent = Intent(action)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        if (fallbackToRoot) {
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }
}

/**
 * Numbered, copy-exact steps to (1) unlock Developer Options and (2) select DJProxy as the mock
 * location app, with one-tap deep links where the OS provides one. There is no OS intent action
 * that jumps directly to "Select mock location app" (only to Developer options itself), so step 2
 * is manual navigation from there — the guide says so honestly rather than pretending a deep link
 * exists. The detected OEM's actual path is shown; other OEMs default to the AOSP path. Live grant
 * status flips to "Granted" automatically (polls [CapabilityDetector.mockLocationGranted] and the
 * location lane's own [ai.darshj.djproxy.vpn.seams.LocationController.capability], when present).
 */
@Composable
private fun MockLocationInstructionsCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    var polledGranted by remember { mutableStateOf(false) }
    // Only poll while the card is expanded and the grant isn't confirmed yet — no always-on CPU/battery
    // drip while collapsed, and it self-terminates the moment a grant is detected.
    LaunchedEffect(expanded, polledGranted) {
        while (expanded && !polledGranted) {
            polledGranted = runCatching { CapabilityDetector.mockLocationGranted(context) }.getOrDefault(false)
            if (polledGranted) break
            delay(1500)
        }
    }
    val locationFlow = remember(FeatureRegistry.locationController) {
        FeatureRegistry.locationController?.capability ?: MutableStateFlow(LocationCapability.UNAVAILABLE)
    }
    val locationCapability by locationFlow.collectAsState()
    val granted = polledGranted || locationCapability != LocationCapability.UNAVAILABLE

    val emulatorNote = remember {
        if (runCatching { CapabilityDetector.isEmulator() }.getOrDefault(false)) {
            val name = runCatching { CapabilityDetector.emulatorName() }.getOrNull()
            "Detected: running on an emulator${if (name != null) " ($name)" else ""}. Developer options " +
                "is usually already unlocked here — open it directly with the button below."
        } else null
    }

    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Enable location spoofing (Developer Options)",
                        style = MaterialTheme.typography.titleMedium,
                        color = DjColors.TextPrimary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        GrantDot(granted)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (granted) "Mock-location grant detected" else "Mock-location grant not detected yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (granted) DjColors.Emerald else DjColors.TextTertiary,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = DjColors.TextSecondary,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
            ) {
                Column(Modifier.padding(top = 14.dp)) {
                    emulatorNote?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = DjColors.AccentCyan)
                        Spacer(Modifier.height(12.dp))
                    }

                    StepRow(
                        number = 1,
                        title = "Unlock Developer Options",
                        body = "Settings > About phone > tap \"${buildNumberFieldLabel()}\" 7 times in a row " +
                            "until you see \"You are now a developer\".",
                    )
                    OutlinedButton(
                        onClick = { openSettingsIntent(Settings.ACTION_DEVICE_INFO_SETTINGS, context) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DjColors.AccentCyan),
                        modifier = Modifier.padding(start = 34.dp, top = 4.dp, bottom = 14.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open About phone")
                    }

                    StepRow(
                        number = 2,
                        title = "Select DJProxy as the mock location app",
                        body = "${devOptionsPathHint()} > scroll to \"Select mock location app\" > choose DJProxy. " +
                            "Steps vary a little by manufacturer (Samsung/Xiaomi/stock Android/emulator) — the " +
                            "path above is the one detected for this device.",
                    )
                    OutlinedButton(
                        onClick = { openSettingsIntent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, context) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DjColors.AccentCyan),
                        modifier = Modifier.padding(start = 34.dp, top = 4.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Developer options")
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Without this grant DJProxy still tunnels every byte through the proxy — only the " +
                            "GPS coordinate itself will not match the exit's country until you grant it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DjColors.TextTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun GrantDot(granted: Boolean) {
    val color = if (granted) DjColors.Emerald else DjColors.TextTertiary
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun StepRow(number: Int, title: String, body: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        StepBadge(index = number, done = false)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = DjColors.TextPrimary)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
