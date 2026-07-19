package ai.darshj.djproxy.diag

import ai.darshj.djproxy.vpn.seams.SettingsPanel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The diagnostics lane's own settings section (§9.5 seam). The ui lane's settings HOST iterates
 * [ai.darshj.djproxy.vpn.FeatureRegistry.settingsPanels] and renders this [Content] inside a
 * GlassSurface — so this UI ships WITHOUT editing any ui-lane file. Do NOT wrap our own surface.
 *
 * Two controls, exactly as requested:
 *   • a "Send diagnostic reports" toggle (auto-open the mail composer on a critical failure), and
 *   • a "Send diagnostic report" action (build + open the composer right now).
 */
class DiagnosticsSettingsPanel : SettingsPanel {

    override val id: String = "diagnostics"
    override val title: String = "Diagnostics"
    override val order: Int = 60

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val prefs = remember(context) { DiagPrefs.get(context) }
        val enabled by prefs.enabled.collectAsState()
        val pending by Diagnostics.pending.collectAsState()

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.padding(end = 12.dp)) {
                    Text("Send diagnostic reports")
                    Text(
                        "On a critical failure, offer to e-mail a redacted report " +
                            "(logs + device info — never your proxy password).",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { prefs.setEnabled(it) })
            }

            Spacer(Modifier.height(12.dp))

            if (pending != null) {
                Text(
                    "A failure report is ready to send.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { pending?.let { Diagnostics.send(context, it) } }) {
                        Text("Send failure report", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { Diagnostics.clearPending() }) {
                        Text("Dismiss", maxLines = 1)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedButton(
                onClick = { Diagnostics.sendManualReport(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send diagnostic report")
            }
        }
    }
}
