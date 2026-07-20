package ai.darshj.djproxy.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.qr.QrEncoder
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.vpn.FeatureRegistry
import ai.darshj.djproxy.vpn.seams.HotspotCapability
import ai.darshj.djproxy.vpn.seams.ShareResult
import ai.darshj.djproxy.vpn.seams.ShareState
import kotlinx.coroutines.launch

/**
 * TIER 2 ShareLan sheet (§6). Reads the EXISTING `FeatureRegistry.hotspotController` and renders its
 * honest tier: LAN proxy endpoint (works unrooted — other devices point their proxy at host:port),
 * or the root transparent redirect (only claimed when actually applied). The QR is drawn from the
 * lane's `qrPayload()` via the qr lane's [QrEncoder]. Never claims tethered clients are transparently
 * proxied unless the share state is RootTransparent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLanSheet(onDismiss: () -> Unit) {
    val controller = FeatureRegistry.hotspotController
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DjColors.CharcoalRaised,
    ) {
      Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Share connection", style = MaterialTheme.typography.headlineSmall, color = DjColors.TextPrimary)

            if (controller == null) {
                Text(
                    "Connection sharing isn't available in this build.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DjColors.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                return@Column
            }

            val capability by controller.capability.collectAsState()
            val share by controller.share.collectAsState()
            var shareError by remember { mutableStateOf<String?>(null) }

            // Clear a stale failure the moment a share actually goes live.
            LaunchedEffect(share) { if (share !is ShareState.Off) shareError = null }

            Text(
                when (capability) {
                    HotspotCapability.ROOT_TRANSPARENT_AVAILABLE ->
                        "Root detected — you can transparently route tethered clients through the tunnel, " +
                            "or run the plain LAN proxy endpoint."
                    HotspotCapability.LAN_PROXY_ONLY ->
                        "Other devices can use this phone as a proxy at the address below. On unrooted " +
                            "Android, tethered traffic itself is not transparently proxied — point the " +
                            "other device's proxy settings at this endpoint."
                    HotspotCapability.UNAVAILABLE ->
                        "No Wi-Fi hotspot or network to share over. Turn on your Wi-Fi hotspot (or join a " +
                            "Wi-Fi network), then reopen this sheet."
                },
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextSecondary,
            )

            val active = share !is ShareState.Off
            if (!active && capability != HotspotCapability.UNAVAILABLE) {
                // The LAN share is ALWAYS password-protected (the controller mints a credential
                // regardless) — state that honestly instead of a switch that implies an open relay.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = DjColors.AccentCyan,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Always password-protected", style = MaterialTheme.typography.labelMedium, color = DjColors.TextSecondary)
                }
                Button(
                    onClick = {
                        scope.launch {
                            val result = controller.startLanShare(true)
                            shareError = (result as? ShareResult.Fail)?.reason
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start LAN proxy") }
                if (capability == HotspotCapability.ROOT_TRANSPARENT_AVAILABLE) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val result = controller.startRootTransparent()
                                shareError = (result as? ShareResult.Fail)?.reason
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start transparent redirect (root)") }
                }
            }

            shareError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = DjColors.Rose)
            }

            when (val s = share) {
                is ShareState.LanProxy -> {
                    EndpointCard(endpoint = "${s.addr}:${s.port}", cred = s.cred)
                    QrBlock(payload = controller.qrPayload())
                    Button(onClick = { controller.stopShare() }, modifier = Modifier.fillMaxWidth()) { Text("Stop sharing") }
                }
                ShareState.RootTransparent -> {
                    Text(
                        "Transparent redirect active — tethered clients are routed through the tunnel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DjColors.Emerald,
                    )
                    Button(onClick = { controller.stopShare() }, modifier = Modifier.fillMaxWidth()) { Text("Stop sharing") }
                }
                ShareState.Off -> Unit
            }
            Spacer(Modifier.height(2.dp))
        }
      }
    }
}

@Composable
private fun EndpointCard(endpoint: String, cred: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DjColors.GlassFill)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Proxy endpoint", style = MaterialTheme.typography.labelMedium, color = DjColors.TextTertiary)
        Text(endpoint, style = MaterialTheme.typography.titleMedium, color = DjColors.AccentCyan)
        if (cred != null) {
            Text("Credentials: $cred", style = MaterialTheme.typography.bodySmall, color = DjColors.TextSecondary)
        }
    }
}

@Composable
private fun QrBlock(payload: String?) {
    if (payload.isNullOrBlank()) return
    // Encode at (or above) the actual physical-pixel size of the 220dp box so the QR is native-res on
    // high-density panels instead of being upscaled from a fixed 512; FilterQuality.None keeps module
    // edges hard so the exact surface meant to be scanned stays crisp.
    val density = LocalDensity.current
    val qrPx = remember(density) { with(density) { 220.dp.roundToPx() }.coerceIn(384, 768) }
    val bitmap = remember(payload, qrPx) { runCatching { QrEncoder.encodeQr(payload, qrPx) }.getOrNull() }
    if (bitmap != null) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Scan to configure this device as a proxy client",
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(12.dp),
            )
        }
    }
}
