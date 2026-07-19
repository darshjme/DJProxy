package ai.darshj.djproxy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors

/**
 * The big paste box: accepts any of the seven formats [ai.darshj.djproxy.core.ProxyParser]
 * understands. Two-way bound with [ProxyFields] via the shared [ai.darshj.djproxy.core.ProxyConfig]
 * in the view model — typing here re-parses on every change; editing a field there re-renders
 * this box's canonical text via the caller.
 */
@Composable
fun PasteBox(
    text: String,
    onTextChange: (String) -> Unit,
    parseError: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Paste a proxy",
            style = MaterialTheme.typography.titleMedium,
            color = DjColors.TextPrimary,
        )
        Text(
            "socks5://user:pass@host:port  ·  http://host:port  ·  host:port:user:pass  ·  and more",
            style = MaterialTheme.typography.bodySmall,
            color = DjColors.TextTertiary,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 88.dp),
            placeholder = { Text("Paste your proxy line here…", color = DjColors.TextTertiary) },
            leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null, tint = DjColors.TextTertiary) },
            isError = parseError != null,
            supportingText = {
                if (parseError != null) {
                    Text(parseError, color = DjColors.Rose)
                } else {
                    Text("Fields below stay in sync automatically.", color = DjColors.TextTertiary)
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DjColors.GlassFill,
                unfocusedContainerColor = DjColors.GlassFill,
                focusedTextColor = DjColors.TextPrimary,
                unfocusedTextColor = DjColors.TextPrimary,
                focusedBorderColor = DjColors.AccentCyan,
                unfocusedBorderColor = DjColors.HairlineStrong,
                cursorColor = DjColors.AccentCyan,
            ),
        )
    }
}
