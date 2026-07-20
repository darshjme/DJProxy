package ai.darshj.djproxy.vpngate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.vpn.seams.SettingsPanel
import kotlinx.coroutines.launch

/**
 * The vpngate lane's own settings section, contributed through the [SettingsPanel] seam so it renders in
 * the app's Settings screen WITHOUT the ui lane editing anything (same mechanism as tor/location/hotspot).
 *
 * Shows the HONEST scope up front: VPN Gate is a **server catalog + `.ovpn` hand-off**, not a tunnel
 * DJProxy runs. Surfaces the live catalog size + last-updated, and a Refresh control. It deliberately
 * does NOT connect anything — routing a (rare) directly-dialable row is the servers-tab/ViewModel's job.
 */
class VpnGateSettingsPanel(
    private val controller: VpnGateController,
) : SettingsPanel {

    override val id: String = "vpngate"
    override val title: String = "VPN Gate catalog"
    override val order: Int = 60 // after Location (30) / Tor (40) / Adblock (50)

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val servers by controller.servers.collectAsState()
        val refreshState by controller.refreshState.collectAsState()

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = headline(refreshState, servers.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = DjColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = CAVEAT,
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextSecondary,
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val loading = refreshState is VpnGateRefreshState.Loading
                Button(
                    enabled = !loading,
                    onClick = { scope.launch { controller.refresh(force = true) } },
                ) { Text(if (loading) "Refreshing…" else "Refresh catalog") }
            }
        }
    }

    private fun headline(state: VpnGateRefreshState, count: Int): String = when (state) {
        is VpnGateRefreshState.Loading -> "Refreshing VPN Gate catalog…"
        is VpnGateRefreshState.Error -> "Could not refresh (${state.reason})"
        is VpnGateRefreshState.Success -> "VPN Gate catalog — ${state.count} servers"
        VpnGateRefreshState.Idle ->
            if (count > 0) "VPN Gate catalog — $count servers" else "VPN Gate catalog"
    }

    companion object {
        /** The unmissable honest scope line — no fake-tunnel claim anywhere. */
        const val CAVEAT: String =
            "A browsable list of VPN Gate's public volunteer servers. These are OpenVPN servers — " +
                "DJProxy can't tunnel them itself, so each row exports its .ovpn to an external OpenVPN " +
                "app. The rare server that also exposes a SOCKS/HTTP proxy can be used directly, behind " +
                "the untrusted-server warning. Nothing here connects automatically."
    }
}
