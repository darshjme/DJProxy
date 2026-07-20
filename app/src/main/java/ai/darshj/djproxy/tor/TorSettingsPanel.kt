package ai.darshj.djproxy.tor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.vpn.seams.SettingsPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The tor lane's own settings section, contributed through the [SettingsPanel] seam so it renders in
 * the app's Settings screen WITHOUT the ui lane editing anything (same mechanism as location/hotspot/
 * diagnostics). Shows the HONEST live state — bootstrap %, active flag, the `.onion` note — and offers
 * enable / restart / disable controls.
 *
 * Note: enabling here bootstraps Tor and leaves the loopback SOCKS5 ready; it deliberately does NOT
 * call `VpnController.apply` from settings — routing is the ui/ViewModel's job (it applies
 * `controller.proxyConfig()` on the hero). Settings only manages Tor's own lifecycle.
 */
class TorSettingsPanel(
    private val controller: TorController,
) : SettingsPanel {

    override val id: String = "tor"
    override val title: String = "Tor (.onion)"
    override val order: Int = 40 // after Location (30), before About-ish sections

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val active by controller.active.collectAsState()
        val progress by controller.bootstrapProgress.collectAsState()
        val phase by controller.phase.collectAsState()

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = headline(phase, active),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = DjColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail(phase, active, progress, context),
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextSecondary,
            )

            if (phase == TorPhase.BOOTSTRAPPING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active) {
                    OutlinedButton(
                        enabled = phase != TorPhase.BOOTSTRAPPING,
                        onClick = { controller.stop() },
                    ) { Text("Disable Tor") }
                    Button(
                        enabled = phase != TorPhase.BOOTSTRAPPING,
                        onClick = {
                            // Serialize stop+start in ONE coroutine with a settle delay so the OS
                            // finishes unbind/stopService before the re-bind/start-foreground fires
                            // (no start-after-stop churn / bind racing an in-flight teardown).
                            scope.launch {
                                controller.stop()
                                delay(250)
                                controller.start()
                            }
                        },
                    ) { Text(if (phase == TorPhase.BOOTSTRAPPING) "Restarting…" else "Restart") }
                } else if (phase == TorPhase.BOOTSTRAPPING) {
                    Button(enabled = false, onClick = {}) { Text("Starting…") }
                    // Never a silent, uncancellable wait — let the user abort a hanging bootstrap.
                    OutlinedButton(onClick = { controller.stop() }) { Text("Cancel") }
                } else {
                    Button(onClick = { scope.launch { controller.start() } }) { Text("Enable Tor") }
                }
            }
        }
    }

    private fun headline(phase: TorPhase, active: Boolean): String = when {
        active -> "Tor active — .onion enabled"
        phase == TorPhase.BOOTSTRAPPING -> "Starting Tor…"
        phase == TorPhase.FAILED -> "Tor could not start"
        else -> "Tor is off"
    }

    private fun detail(
        phase: TorPhase,
        active: Boolean,
        progress: Int,
        context: android.content.Context,
    ): String = when {
        active -> {
            val base = "The whole device routes through Tor. Open a .onion address in Chrome — it " +
                "resolves inside Tor over the existing tunnel. Turn Tor off to use a normal proxy."
            if (PrivateDnsGuard.isStrictModeActive(context)) {
                base + " Note: your device's Private DNS is set to a custom provider — that can stop " +
                    ".onion sites from resolving (set Private DNS to Automatic to fix it; this is a " +
                    "device setting, not a Tor/DJProxy issue)."
            } else {
                base
            }
        }
        phase == TorPhase.BOOTSTRAPPING ->
            "Building a Tor circuit… ${progress.coerceIn(0, 100)}%. This can take a moment on a slow network."
        phase == TorPhase.FAILED ->
            "Tor did not finish bootstrapping (often no network, or the network blocks Tor). Try again, " +
                "or use a normal proxy."
        else ->
            "When enabled, DJProxy embeds Tor and routes everything through it (SOCKS5 127.0.0.1:9050), " +
                "so .onion sites work in any app. Chained after a custom proxy is out of scope for now."
    }
}
