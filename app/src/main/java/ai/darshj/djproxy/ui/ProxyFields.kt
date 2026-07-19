package ai.darshj.djproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType
import ai.darshj.djproxy.ui.theme.DjColors

private val fieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = DjColors.GlassFill,
        unfocusedContainerColor = DjColors.GlassFill,
        focusedTextColor = DjColors.TextPrimary,
        unfocusedTextColor = DjColors.TextPrimary,
        focusedBorderColor = DjColors.AccentCyan,
        unfocusedBorderColor = DjColors.HairlineStrong,
        cursorColor = DjColors.AccentCyan,
        focusedLabelColor = DjColors.AccentCyan,
        unfocusedLabelColor = DjColors.TextTertiary,
    )

/**
 * Labelled inputs that mirror [PasteBox] one field at a time. Every `onXChange` calls back into
 * the view model with a full [ProxyConfig] copy — the view model is the single source of truth,
 * this composable holds no state of its own beyond password visibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyFields(
    config: ProxyConfig,
    onConfigChange: (ProxyConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    var typeMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        ExposedDropdownMenuBox(
            expanded = typeMenuExpanded,
            onExpandedChange = { typeMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = config.type.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Type") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
                colors = fieldColors,
            )
            ExposedDropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                ProxyType.entries.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.label) },
                        onClick = {
                            onConfigChange(config.copy(type = t))
                            typeMenuExpanded = false
                        },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = config.host,
                onValueChange = { onConfigChange(config.copy(host = it.trim())) },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.weight(2f),
                colors = fieldColors,
            )
            OutlinedTextField(
                value = if (config.port == 0) "" else config.port.toString(),
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(5)
                    onConfigChange(config.copy(port = digits.toIntOrNull() ?: 0))
                },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                colors = fieldColors,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = config.username,
                onValueChange = { onConfigChange(config.copy(username = it)) },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = fieldColors,
            )
            OutlinedTextField(
                value = config.password,
                onValueChange = { onConfigChange(config.copy(password = it)) },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            tint = DjColors.TextTertiary,
                        )
                    }
                },
                modifier = Modifier.weight(1f),
                colors = fieldColors,
            )
        }

        HorizontalDivider(color = DjColors.HairlineLight)

        Column(modifier = Modifier.fillMaxWidth()) {
            Text("UDP blocked", style = MaterialTheme.typography.titleSmall, color = DjColors.TextPrimary)
            Text(
                "All UDP is dropped so WebRTC/QUIC can't leak your real IP — browsers fall back to TCP " +
                    "through the proxy. DNS is resolved over TCP through the proxy too.",
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextTertiary,
            )
        }

        OutlinedTextField(
            value = config.dnsServer,
            onValueChange = { onConfigChange(config.copy(dnsServer = it.trim())) },
            label = { Text("Upstream DNS (tunnelled over TCP)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
    }
}
