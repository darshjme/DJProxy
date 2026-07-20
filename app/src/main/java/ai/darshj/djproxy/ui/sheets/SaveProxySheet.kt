package ai.darshj.djproxy.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors

/**
 * v6 (§5.4): the name-and-save-to-vault sheet. Reached from the manual editor ("Save to vault") and
 * from a connected proxy ("Save this proxy"), and from the Free-servers list ("Save to vault"). The
 * name is the only input — the config (and its encrypted password) is already held by the view
 * model, so pressing Save just labels it. When [showPublicCaveat] is set (saving a FREE_PUBLIC
 * entry) the untrusted-server note is shown so the provenance is never lost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveProxySheet(
    redacted: String,
    suggestedName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    showPublicCaveat: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable(suggestedName) { mutableStateOf(suggestedName) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DjColors.CharcoalRaised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Save to vault", style = MaterialTheme.typography.headlineSmall, color = DjColors.TextPrimary)
            Text(
                redacted,
                style = MaterialTheme.typography.bodyMedium,
                color = DjColors.TextTertiary,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. DE residential") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            if (showPublicCaveat) {
                Text(
                    "Public server — unvetted. It may be slow, go offline, log traffic, or inject " +
                        "content. Saved with a \"Public\" badge so you always know its origin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DjColors.Amber,
                )
            }

            Text(
                "The password (if any) is encrypted at rest with the device keystore — never stored in " +
                    "plain text.",
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextTertiary,
            )

            Button(
                onClick = {
                    onSave(name.trim())
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
