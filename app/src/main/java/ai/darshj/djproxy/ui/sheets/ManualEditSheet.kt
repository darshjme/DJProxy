package ai.darshj.djproxy.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.ui.PasteBox
import ai.darshj.djproxy.ui.ProxyFields
import ai.darshj.djproxy.ui.theme.DjColors

/**
 * TIER 2 ManualEdit sheet (§2). The v3 `ProxyFields` + `PasteBox` left the home surface (they were
 * the clutter); this is where power users still get every knob. Paste and per-field edits stay
 * two-way bound through the same view-model config. "Save" just closes (the config is already live);
 * "Connect" hands off to the same one-tap apply path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEditSheet(
    config: ProxyConfig,
    pasteText: String,
    pasteError: String?,
    onConfigChange: (ProxyConfig) -> Unit,
    onPasteChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DjColors.CharcoalRaised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Edit proxy", style = MaterialTheme.typography.headlineSmall, color = DjColors.TextPrimary)

            PasteBox(text = pasteText, onTextChange = onPasteChange, parseError = pasteError)
            ProxyFields(config = config, onConfigChange = onConfigChange)

            Button(
                onClick = {
                    onConnect()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect with these settings")
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
