package ai.darshj.djproxy.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.qr.QrCameraScanner
import ai.darshj.djproxy.ui.theme.DjColors

/**
 * TIER 2 Scan sheet (§4). Pure bottom-sheet chrome that hosts the qr lane's [QrCameraScanner], which
 * owns the ENTIRE CAMERA permission flow end-to-end (NEEDS_REQUEST auto-launch, DENIED_RATIONALE
 * retry, PERMANENTLY_DENIED → Settings, and NO_CAMERA) — this sheet no longer re-implements a cruder
 * granted/denied gate that could dead-end the permanently-denied user. A decoded string is handed to
 * [onResult] (= `viewModel::ingestExternal`); the sheet stays open showing "Reading…" until the
 * config lane resolves, and surfaces a rejection inline (so a scanned `vmess://` doesn't vanish
 * silently) with a Scan-again path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSheet(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
    rejectionMessage: String? = null,
    onRetry: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var awaitingResult by remember { mutableStateOf(false) }

    // A fresh rejection means the last scan didn't parse — drop out of "Reading…" and surface it.
    LaunchedEffect(rejectionMessage) {
        if (rejectionMessage != null) awaitingResult = false
    }

    ModalBottomSheet(
        onDismissRequest = { onRetry(); onDismiss() },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Scan a proxy QR", style = MaterialTheme.typography.headlineSmall, color = DjColors.TextPrimary)
            Text(
                "Point the camera at a proxy or subscription QR code. The result is parsed the same way " +
                    "as a paste — nothing connects until you confirm it.",
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextSecondary,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    rejectionMessage != null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Couldn't read that code", style = MaterialTheme.typography.titleSmall, color = DjColors.Rose)
                        Text(rejectionMessage, style = MaterialTheme.typography.bodySmall, color = DjColors.TextSecondary)
                        Button(onClick = { onRetry(); awaitingResult = false }) { Text("Scan again") }
                    }
                    awaitingResult -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = DjColors.TextPrimary)
                        Text("Reading…", style = MaterialTheme.typography.bodySmall, color = DjColors.TextSecondary)
                    }
                    else -> QrCameraScanner(
                        onResult = { raw ->
                            awaitingResult = true
                            onResult(raw)
                        },
                        onError = { /* QrCameraScanner renders its own notice for every non-granted state. */ },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
      }
    }
}
