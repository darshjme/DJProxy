package ai.darshj.djproxy.ui.sheets

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ai.darshj.djproxy.qr.QrCameraScanner
import ai.darshj.djproxy.ui.theme.DjColors

/**
 * TIER 2 Scan sheet (§4). Pure bottom-sheet chrome that hosts the qr lane's [QrCameraScanner]; the
 * ui never imports CameraX/ZXing directly. The CAMERA runtime permission is requested here (from the
 * Composable via [rememberLauncherForActivityResult], so no MainActivity edit is needed) and the
 * camera preview only mounts once granted. A decoded string is handed straight to
 * [onResult] (= `viewModel::ingestExternal`), which runs the one config-import facade.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSheet(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        denied = !result
    }

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
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    granted -> QrCameraScanner(
                        onResult = { raw ->
                            onResult(raw)
                            onDismiss()
                        },
                        onError = { denied = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    else -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            if (denied) {
                                "Camera permission is off. Grant it to scan, or paste the proxy instead."
                            } else {
                                "DJProxy needs the camera to scan a QR code."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = DjColors.TextSecondary,
                        )
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Allow camera")
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
