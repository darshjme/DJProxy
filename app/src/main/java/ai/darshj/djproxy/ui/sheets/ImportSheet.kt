package ai.darshj.djproxy.ui.sheets

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.config.NamedConfig
import ai.darshj.djproxy.proxy.ProxyError
import ai.darshj.djproxy.ui.ImportPreview
import ai.darshj.djproxy.ui.components.GlassSurface
import ai.darshj.djproxy.ui.theme.DjColors

private enum class ImportTab(val label: String) {
    Paste("Paste"), Scan("Scan"), Subscription("Subscription"), File("File")
}

/**
 * TIER 2 Import sheet (§5). Four front doors — Paste, Scan, Subscription URL, `.ovpn` File — all
 * converging on the one `viewModel.ingestExternal` facade. A parsed single config becomes an
 * [ImportPreview] (Connect requires one tap — the security seam, §11); a subscription becomes a
 * pick-list of [NamedConfig]s; a rejected import shows in the inline rose card on Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    preview: ImportPreview?,
    choices: List<NamedConfig>?,
    error: ProxyError?,
    busy: Boolean,
    onIngest: (raw: String, autoConnect: Boolean) -> Unit,
    onChoose: (NamedConfig) -> Unit,
    onConnectPreview: () -> Unit,
    onOpenScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf(ImportTab.Paste) }
    var pasteRaw by remember { mutableStateOf("") }
    var subUrl by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!text.isNullOrBlank()) onIngest(text, false)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DjColors.CharcoalRaised,
    ) {
      // Cap the reading/content width so the sheet stays a focused surface on the Fold7 unfolded
      // (~900dp) pane instead of stretching every field/tab edge-to-edge.
      Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Import a proxy", style = MaterialTheme.typography.headlineSmall, color = DjColors.TextPrimary)

            // segmented tab row — each tab is a >=44dp touch target so the four are not mis-tappable.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DjColors.GlassFill),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ImportTab.entries.forEach { t ->
                    val selected = t == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) DjColors.AccentCyan else Color.Transparent)
                            .clickable { tab = t },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            t.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) DjColors.TextOnAccent else DjColors.TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            when (tab) {
                ImportTab.Paste -> {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)?.coerceToText(context)
                            if (!clip.isNullOrBlank()) pasteRaw = clip.toString()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Paste from clipboard") }
                    OutlinedTextField(
                        value = pasteRaw,
                        onValueChange = { pasteRaw = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        placeholder = { Text("socks5://user:pass@host:port, ss://…, or a proxy line", color = DjColors.TextTertiary) },
                        leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null, tint = DjColors.TextTertiary) },
                        colors = importFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onIngest(pasteRaw, false) }),
                    )
                    Button(onClick = { onIngest(pasteRaw, false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = DjColors.TextOnAccent)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (busy) "Parsing…" else "Parse & preview")
                    }
                }
                ImportTab.Scan -> {
                    Text(
                        "Open the camera to scan a proxy or subscription QR.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DjColors.TextSecondary,
                    )
                    Button(onClick = onOpenScan, modifier = Modifier.fillMaxWidth()) { Text("Scan QR") }
                }
                ImportTab.Subscription -> {
                    Text(
                        "A subscription URL returns a list of proxies. DJProxy fetches it over HTTPS, " +
                            "decodes it, and lets you pick one — it never auto-connects.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DjColors.TextSecondary,
                    )
                    OutlinedTextField(
                        value = subUrl,
                        onValueChange = { subUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://provider.example/sub/…", color = DjColors.TextTertiary) },
                        colors = importFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onIngest(subUrl, false) }),
                    )
                    Button(onClick = { onIngest(subUrl, false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = DjColors.TextOnAccent)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (busy) "Fetching…" else "Fetch list")
                    }
                }
                ImportTab.File -> {
                    Text(
                        "Open an .ovpn file. DJProxy reads only its proxy directives (http-proxy / " +
                            "socks-proxy) — it is a SOCKS/HTTP proxy app, not an OpenVPN client, and will " +
                            "say so if the file has no proxy line.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DjColors.TextSecondary,
                    )
                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Choose .ovpn file")
                    }
                }
            }

            // Inline error surface — a rejected/unsupported import (e.g. a vmess:// link) now shows a
            // reason INSIDE the sheet instead of the sheet sitting inert while the error hides behind
            // the scrim on Home.
            error?.let { e ->
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 16.dp,
                    borderBrush = Brush.linearGradient(listOf(DjColors.Rose, DjColors.RoseDeep)),
                    fill = Brush.verticalGradient(
                        listOf(DjColors.Rose.copy(alpha = 0.14f), DjColors.Rose.copy(alpha = 0.05f)),
                    ),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(e.message, style = MaterialTheme.typography.titleSmall, color = DjColors.TextPrimary)
                        Text(
                            e.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = DjColors.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // Subscription pick-list
            choices?.takeIf { it.isNotEmpty() }?.let { list ->
                Text("Choose a server", style = MaterialTheme.typography.titleMedium, color = DjColors.TextPrimary)
                list.forEach { named ->
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(named) },
                        contentPadding = 14.dp,
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(named.name.ifBlank { "Server" }, style = MaterialTheme.typography.titleSmall, color = DjColors.TextPrimary)
                            Text(named.config.redacted(), style = MaterialTheme.typography.bodySmall, color = DjColors.TextSecondary)
                        }
                    }
                }
            }

            // Single-config preview → one confirmation tap to connect
            preview?.let { p ->
                GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ready to connect", style = MaterialTheme.typography.titleMedium, color = DjColors.TextPrimary)
                        Text(p.config.redacted(), style = MaterialTheme.typography.bodyMedium, color = DjColors.AccentCyan)
                        Text(
                            "Review the target above, then connect.",
                            style = MaterialTheme.typography.bodySmall,
                            color = DjColors.TextTertiary,
                        )
                        Button(
                            onClick = {
                                onConnectPreview()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (p.autoConnect) "Connect now" else "Connect")
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
      }
    }
}

@Composable
private fun importFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = DjColors.GlassFill,
    unfocusedContainerColor = DjColors.GlassFill,
    focusedTextColor = DjColors.TextPrimary,
    unfocusedTextColor = DjColors.TextPrimary,
    focusedBorderColor = DjColors.AccentCyan,
    unfocusedBorderColor = DjColors.HairlineStrong,
    cursorColor = DjColors.AccentCyan,
)
