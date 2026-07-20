package ai.darshj.djproxy.adblock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.vpn.seams.SettingsPanel

/**
 * The adblock lane's own settings section, contributed through the [SettingsPanel] seam so it renders
 * in the app's Settings screen WITHOUT the ui lane editing anything (same mechanism as tor/location/
 * hotspot). Shows the HONEST live state — the blocklist size, the toggle, and the connect-time caveat —
 * so the user knows exactly what "Block ads" does and why a blocked host briefly spins before failing.
 */
class AdblockSettingsPanel(
    private val controller: AdblockController,
) : SettingsPanel {

    override val id: String = "adblock"
    override val title: String = "Block ads & trackers"
    override val order: Int = 50 // after Location (30) and Tor (40), before VPN Gate (60)

    @Composable
    override fun Content() {
        val enabled by controller.enabled.collectAsState()

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (enabled) "Ad-blocking is on" else "Ad-blocking is off",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = DjColors.TextPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${controller.blocklistSize} ad & tracker domains in the blocklist",
                        style = MaterialTheme.typography.bodySmall,
                        color = DjColors.TextSecondary,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { controller.setEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DjColors.AccentIndigo,
                        checkedTrackColor = DjColors.AccentIndigoDeep,
                        uncheckedThumbColor = DjColors.TextSecondary,
                        uncheckedTrackColor = DjColors.GlassFillStrong,
                    ),
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = detail(enabled),
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextSecondary,
            )
        }
    }

    private fun detail(enabled: Boolean): String = if (enabled) {
        // The honest caveat: with route-DNS on (the shipping default), the ad host is refused at CONNECT
        // time — a brief loading spinner, then host-unreachable — not an instant DNS-level failure. This
        // is correct and leak-safe (the exit still does geo-consistent DNS for everything else).
        "Ad and tracker hosts are refused the moment an app tries to connect (a brief loading, then " +
            "\"can't reach\") while everything else routes normally through your proxy. Blocking covers " +
            "sub-domains too — one entry stops the whole family. Some ad slots may still show a blank " +
            "placeholder; only the tracking/ad connection is cut."
    } else {
        "When on, DJProxy quietly refuses connections to known ad and tracker hosts for the whole " +
            "device. It stays off by default so nothing is over-blocked without your say-so."
    }
}
