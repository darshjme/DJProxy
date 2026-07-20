package ai.darshj.djproxy.hotspot

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.qr.QrEncoder
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.vpn.seams.HotspotCapability
import ai.darshj.djproxy.vpn.seams.SettingsPanel
import ai.darshj.djproxy.vpn.seams.ShareState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch

/**
 * The hotspot lane's own settings section (§9.5). Contributed to [ai.darshj.djproxy.vpn.FeatureRegistry.settingsPanels]
 * by [HotspotRegistrar]; the ui host renders [Content] inside its GlassSurface without editing the ui lane.
 *
 * Honest UI: it shows the detected tier, only offers "Transparent (root)" when root is actually
 * available, and — because it never fakes success — surfaces the exact failure reason from the
 * controller if a share cannot start.
 */
class HotspotSettingsPanel(
    private val controller: HotspotControllerImpl,
) : SettingsPanel {
    override val id: String = "hotspot"
    override val title: String = "Share proxy over Wi-Fi / hotspot"
    override val order: Int = 40

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val clip = LocalClipboardManager.current
        val cap by controller.capability.collectAsState()
        val share by controller.share.collectAsState()
        val cscope = rememberCoroutineScope()

        var status by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) { controller.refreshCapability(ctx) }

        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when (cap) {
                    HotspotCapability.ROOT_TRANSPARENT_AVAILABLE ->
                        "Tier: root available — clients can be proxied transparently, or via a LAN endpoint."
                    HotspotCapability.LAN_PROXY_ONLY ->
                        "Tier: unrooted — share a LAN proxy endpoint other devices point at."
                    HotspotCapability.UNAVAILABLE ->
                        "Tier: no LAN/hotspot interface — enable your hotspot or join a network."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = DjColors.TextPrimary,
            )

            when (val s = share) {
                is ShareState.Off -> {
                    Text(
                        "Access is always password-protected: a one-time username/password is generated " +
                            "and shown once the share starts. Share it only with your own devices — the " +
                            "endpoint relays through your paid proxy exit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DjColors.TextSecondary,
                    )
                    Button(
                        onClick = {
                            cscope.launch {
                                val r = controller.startLanShare(requireAuth = true)
                                status = describe(r)
                            }
                        },
                        enabled = cap != HotspotCapability.UNAVAILABLE,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start LAN proxy endpoint") }

                    if (cap == HotspotCapability.ROOT_TRANSPARENT_AVAILABLE) {
                        OutlinedButton(
                            onClick = {
                                cscope.launch {
                                    val r = controller.startRootTransparent()
                                    status = describe(r)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Start transparent redirect (root)") }
                    }
                }

                is ShareState.LanProxy -> {
                    val cred = LanCredential.parse(s.cred)
                    val httpUrl = QrPayload.forHttp(s.addr, s.port, cred)
                    val socksUrl = QrPayload.forSocks5(s.addr, s.port, cred)
                    val payload = controller.qrPayload()
                    val qrBitmap = remember(payload) {
                        payload?.let { runCatching { QrEncoder.encodeQr(it, 512) }.getOrNull() }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(DjColors.GlassFill)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "LAN proxy live at ${s.addr}:${s.port}",
                            style = MaterialTheme.typography.titleSmall,
                            color = DjColors.AccentCyan,
                        )
                        qrBitmap?.let {
                            Image(
                                bitmap = it,
                                contentDescription = "Scan to configure this device as a proxy client",
                                modifier = Modifier
                                    .size(200.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White)
                                    .padding(12.dp),
                            )
                        }
                        Text(
                            text = QrPayload.humanHint(s.addr, s.port, cred),
                            style = MaterialTheme.typography.bodySmall,
                            color = DjColors.TextSecondary,
                        )
                        Text(
                            text = "HTTP:  $httpUrl",
                            style = MaterialTheme.typography.bodySmall,
                            color = DjColors.TextPrimary,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = "SOCKS: $socksUrl",
                            style = MaterialTheme.typography.bodySmall,
                            color = DjColors.TextPrimary,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    OutlinedButton(
                        onClick = { clip.setText(AnnotatedString(httpUrl)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Copy proxy URL") }
                    Button(
                        onClick = { controller.stopShare(); status = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Stop sharing") }
                }

                is ShareState.RootTransparent -> {
                    Text(
                        "Transparent redirect active — hotspot clients are proxied automatically.",
                        style = MaterialTheme.typography.titleSmall,
                        color = DjColors.Emerald,
                    )
                    Button(
                        onClick = { controller.stopShare(); status = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Stop sharing") }
                }
            }

            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = DjColors.Rose)
            }
        }
    }

    private fun describe(r: ai.darshj.djproxy.vpn.seams.ShareResult): String? = when (r) {
        is ai.darshj.djproxy.vpn.seams.ShareResult.Ok -> null
        is ai.darshj.djproxy.vpn.seams.ShareResult.Fail -> r.reason
    }
}
