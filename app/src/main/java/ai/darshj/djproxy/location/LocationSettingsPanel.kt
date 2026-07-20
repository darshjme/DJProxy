package ai.darshj.djproxy.location

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import ai.darshj.djproxy.vpn.seams.LocationCapability
import ai.darshj.djproxy.vpn.seams.SettingsPanel
import kotlinx.coroutines.launch

/**
 * The location lane's own settings UI, contributed through the [SettingsPanel] seam so it renders in
 * the app's settings screen WITHOUT the ui lane editing anything. It shows the HONEST capability
 * state, a deep-link to enable the mock-location app in Developer Options when the grant is missing,
 * the currently published spoof, and a manual lat/lng override.
 */
class LocationSettingsPanel(
    private val controller: LocationControllerImpl,
    private val appContext: Context,
) : SettingsPanel {

    override val id: String = "location"
    override val title: String = "Location spoofing"
    override val order: Int = 30

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val capability by controller.capability.collectAsState()
        val current by controller.current.collectAsState()

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = capabilityHeadline(capability),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = capabilityDetail(capability),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (capability == LocationCapability.UNAVAILABLE) {
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = { openDeveloperOptions(ctx) }) {
                        Text("Open Developer options")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { controller.refreshCapability(appContext) }) {
                        Text("Re-check")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "In Developer options → \"Select mock location app\", pick DJProxy, then tap Re-check.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = current?.let { "Publishing: ${it.label}\n${fmt(it.lat)}, ${fmt(it.lng)}  ·  ${it.source}" }
                    ?: "Publishing: nothing (connect the proxy to spoof the exit location).",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(12.dp))
            ManualOverride(controller, capability != LocationCapability.UNAVAILABLE)

            Spacer(Modifier.height(12.dp))
            SelfTest(controller)
        }
    }

    /**
     * The "Test mock location" self-test (task): writes a known fix (Eiffel Tower) into the providers
     * and reads it back, then restores whatever was live, reporting the HONEST result — write-confirmed,
     * read-back-confirmed, or an honest "can't run / refused". Never blocks the UI thread.
     */
    @Composable
    private fun SelfTest(controller: LocationControllerImpl) {
        val scope = rememberCoroutineScope()
        var running by remember { mutableStateOf(false) }
        var report by remember { mutableStateOf<ai.darshj.djproxy.location.SelfTestReport?>(null) }

        Text(
            text = "Self-test",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Writes a known fix (Eiffel Tower) into the GPS + network providers and reads it back to " +
                "confirm the spoof actually took. Restores your location afterward.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Button(
            enabled = !running,
            onClick = {
                running = true
                scope.launch {
                    report = controller.runSelfTest()
                    running = false
                }
            },
        ) { Text(if (running) "Testing…" else "Test mock location") }

        report?.let { r ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = r.summary,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    !r.ran -> MaterialTheme.colorScheme.onSurfaceVariant
                    r.passed -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
            )
            if (r.ran && r.probes.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = r.probes.joinToString("\n") { p ->
                        val write = if (p.accepted) "accepted" else "refused"
                        val read = when (val rb = p.readBack) {
                            is ai.darshj.djproxy.location.ReadBack.Match ->
                                "read back ✓ (${"%.0f".format(rb.distanceM)} m)"
                            is ai.darshj.djproxy.location.ReadBack.Mismatch ->
                                "read back ✗ (${"%.0f".format(rb.distanceM)} m off)"
                            ai.darshj.djproxy.location.ReadBack.Unreadable -> "read back n/a"
                        }
                        "• ${p.provider}: $write, $read"
                    } + if (r.fusedActive) "\n• fused: active" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun ManualOverride(controller: LocationControllerImpl, enabled: Boolean) {
        var lat by remember { mutableStateOf("") }
        var lng by remember { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        Text(
            text = "Manual override",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = lat,
                onValueChange = { lat = it },
                label = { Text("Latitude") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(150.dp),
            )
            OutlinedTextField(
                value = lng,
                onValueChange = { lng = it },
                label = { Text("Longitude") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(150.dp),
            )
        }
        errorMsg?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = enabled,
                onClick = {
                    val la = lat.trim().toDoubleOrNull()
                    val ln = lng.trim().toDoubleOrNull()
                    errorMsg = when {
                        la == null || ln == null -> "Enter numeric latitude and longitude."
                        la !in -90.0..90.0 -> "Latitude must be between -90 and 90."
                        ln !in -180.0..180.0 -> "Longitude must be between -180 and 180."
                        else -> { controller.setManualLocation(la, ln); null }
                    }
                },
            ) { Text("Set manual") }
            OutlinedButton(
                enabled = enabled,
                onClick = { controller.clearManual(); errorMsg = null },
            ) { Text("Clear → use exit") }
        }
    }

    private fun capabilityHeadline(c: LocationCapability): String = when (c) {
        LocationCapability.READY_MOCK -> "Ready — mock location granted"
        LocationCapability.READY_EMULATOR -> "Ready — emulator (mock location granted)"
        LocationCapability.READY_ROOT -> "Ready — root (app-op will be self-granted)"
        LocationCapability.UNAVAILABLE -> "Unavailable — mock location not granted"
    }

    private fun capabilityDetail(c: LocationCapability): String = when (c) {
        LocationCapability.READY_MOCK ->
            "GPS + network test providers will report the proxy exit location while connected."
        LocationCapability.READY_EMULATOR ->
            "Using LocationManager test providers (the in-app path). The emulator's own console geo " +
                "channel is out of an app's reach; test providers are the reliable mechanism here."
        LocationCapability.READY_ROOT ->
            "The mock-location app-op is off, but root is present — it will be enabled via appops on connect."
        LocationCapability.UNAVAILABLE ->
            "Spoofing is genuinely disabled: no \"mock location app\" grant and no root to self-grant. " +
                "The device's real location is unchanged."
    }

    private fun fmt(d: Double): String = String.format("%.5f", d)

    private fun openDeveloperOptions(ctx: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }.onFailure {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
