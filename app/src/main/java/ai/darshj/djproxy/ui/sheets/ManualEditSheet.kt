package ai.darshj.djproxy.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.ui.PasteBox
import ai.darshj.djproxy.ui.ProxyFields
import ai.darshj.djproxy.ui.theme.DjColors

/**
 * TIER 2 ManualEdit sheet (§2). The v3 `ProxyFields` + `PasteBox` left the home surface (they were
 * the clutter); this is where power users still get every knob. Paste and per-field edits stay
 * two-way bound through the same view-model config. "Connect" hands off to the same one-tap apply
 * path.
 *
 * v6 (§5.4): two modes.
 *  - **Author** (default): a "Save to vault" affordance lets the user name & persist the current
 *    config (via [onSaveToVault], which raises the [SaveProxySheet]).
 *  - **Edit** ([editingName] non-null): the sheet is editing an existing vault entry. A Name field
 *    appears and the primary button becomes "Save changes" ([onCommitEdit] → `ProxyStore.update`).
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
    editingName: String? = null,
    onEditNameChange: (String) -> Unit = {},
    onCommitEdit: () -> Unit = {},
    onSaveToVault: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val editing = editingName != null

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
            Text(
                if (editing) "Edit saved proxy" else "Edit proxy",
                style = MaterialTheme.typography.headlineSmall,
                color = DjColors.TextPrimary,
            )

            if (editing) {
                OutlinedTextField(
                    value = editingName.orEmpty(),
                    onValueChange = onEditNameChange,
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            PasteBox(text = pasteText, onTextChange = onPasteChange, parseError = pasteError)
            ProxyFields(config = config, onConfigChange = onConfigChange)

            if (editing) {
                Button(
                    onClick = {
                        onCommitEdit()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save changes")
                }
                TextButton(
                    onClick = {
                        onCommitEdit()
                        onConnect()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save & connect", color = DjColors.AccentCyan)
                }
            } else {
                Button(
                    onClick = {
                        onConnect()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Connect with these settings")
                }
                if (onSaveToVault != null) {
                    TextButton(
                        // onSaveToVault raises the SaveProxy sheet (a sheet-state transition). Do NOT also
                        // call onDismiss() here: onDismiss sets the sheet back to None and OVERRIDES the
                        // transition, so the save sheet opened then instantly closed and nothing saved
                        // ("save to vault not happening"). The state change alone dismisses this editor.
                        onClick = onSaveToVault,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save to vault", color = DjColors.AccentCyan)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
