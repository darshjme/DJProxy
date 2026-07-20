package ai.darshj.djproxy.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ai.darshj.djproxy.ui.theme.DjColors
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * The QR scanner surface the ui `ScanSheet` embeds (DESIGN_V4 §4). Decodes a QR to a raw string and
 * hands it up via [onResult] — the ui/ViewModel then feeds that string into the config lane's
 * `ConfigImporter.import(...)`. The ui never imports CameraX/ZXing; that all lives behind this seam.
 *
 * Graceful failure is baked in:
 *  - no camera hardware  -> [onError] + an inline "no camera" panel;
 *  - permission needed   -> auto-request once, then a rationale/retry or a Settings deep-link;
 *  - bad / absent QR      -> nothing fires (the analyzer just keeps hunting);
 *  - camera bind failure  -> [onError] with the reason.
 *
 * @param onResult decoded raw QR text (already marshalled to the main thread).
 * @param onError  human-readable, non-fatal error string for the host sheet to show.
 */
@Composable
fun QrCameraScanner(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val permission = rememberCameraPermission()

    // Auto-launch the system dialog once on first entry (a scanner with no prompt is a dead screen).
    LaunchedEffect(permission.status) {
        if (permission.status == CameraAccess.NEEDS_REQUEST) permission.request()
    }

    Box(modifier = modifier) {
        when (permission.status) {
            CameraAccess.GRANTED ->
                CameraPreview(onResult = onResult, onError = onError, modifier = Modifier.fillMaxSize())

            CameraAccess.NO_CAMERA -> {
                LaunchedEffect(Unit) { onError("This device has no camera to scan a QR code.") }
                ScannerNotice(
                    title = "No camera available",
                    body = "Paste the proxy link or import a file instead.",
                    modifier = Modifier.fillMaxSize(),
                )
            }

            CameraAccess.NEEDS_REQUEST,
            CameraAccess.DENIED_RATIONALE ->
                ScannerNotice(
                    title = "Camera permission needed",
                    body = "DJProxy uses the camera only to read a proxy QR code. Nothing is recorded or sent.",
                    actionLabel = "Allow camera",
                    onAction = { permission.request() },
                    modifier = Modifier.fillMaxSize(),
                )

            CameraAccess.PERMANENTLY_DENIED ->
                ScannerNotice(
                    title = "Camera blocked",
                    body = "Camera access was turned off for DJProxy. Enable it in system Settings to scan, " +
                        "or paste the proxy link instead.",
                    actionLabel = "Open settings",
                    onAction = { permission.openAppSettings() },
                    modifier = Modifier.fillMaxSize(),
                )
        }
    }
}

@Composable
private fun CameraPreview(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    var torchOn by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            setImageAnalysisAnalyzer(
                analyzerExecutor,
                ZxingLuminanceAnalyzer(
                    onResult = { text -> mainExecutor.execute { onResult(text) } },
                ),
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val bound = runCatching { controller.bindToLifecycle(lifecycleOwner) }
        bound.exceptionOrNull()?.let { onError("Camera could not start: ${it.message ?: "unknown error"}") }
        onDispose {
            controller.unbind()
            analyzerExecutor.shutdown()
        }
    }

    // cameraInfo becomes available only after bind; poll briefly to reveal the torch toggle.
    LaunchedEffect(controller) {
        repeat(12) {
            val info = controller.cameraInfo
            if (info != null) {
                hasFlash = info.hasFlashUnit()
                return@LaunchedEffect
            }
            delay(150)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        ReticleOverlay(modifier = Modifier.fillMaxSize())

        if (hasFlash) {
            FilledIconToggleButton(
                checked = torchOn,
                onCheckedChange = {
                    torchOn = it
                    controller.enableTorch(it)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
            ) {
                Icon(
                    imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = if (torchOn) "Turn torch off" else "Turn torch on",
                )
            }
        }
    }
}

/** A pulsing cyan viewfinder reticle with corner brackets — a gentle breath that reads as "aim here". */
@Composable
private fun ReticleOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "reticle")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "reticlePulse",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .aspectRatio(1f),
        ) {
            val stroke = 4.dp.toPx()
            val corner = 28.dp.toPx()
            val arm = size.minDimension * 0.18f
            val color = DjColors.AccentCyan.copy(alpha = pulse)

            // Soft full frame.
            drawRoundRect(
                color = DjColors.AccentCyan.copy(alpha = pulse * 0.35f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
                style = Stroke(width = stroke * 0.6f),
            )

            // Four bright corner brackets.
            // top-left
            drawLine(color, Offset(0f, corner), Offset(0f, 0f + arm), strokeWidth = stroke)
            drawLine(color, Offset(0f, 0f), Offset(arm, 0f), strokeWidth = stroke)
            // top-right
            drawLine(color, Offset(size.width - arm, 0f), Offset(size.width, 0f), strokeWidth = stroke)
            drawLine(color, Offset(size.width, 0f), Offset(size.width, arm), strokeWidth = stroke)
            // bottom-left
            drawLine(color, Offset(0f, size.height - arm), Offset(0f, size.height), strokeWidth = stroke)
            drawLine(color, Offset(0f, size.height), Offset(arm, size.height), strokeWidth = stroke)
            // bottom-right
            drawLine(color, Offset(size.width - arm, size.height), Offset(size.width, size.height), strokeWidth = stroke)
            drawLine(color, Offset(size.width, size.height - arm), Offset(size.width, size.height), strokeWidth = stroke)
        }
    }
}

@Composable
private fun ScannerNotice(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(DjColors.CharcoalRaised)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DjColors.Charcoal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.NoPhotography,
                contentDescription = null,
                tint = DjColors.TextSecondary,
            )
        }
        Text(
            text = title,
            color = DjColors.TextPrimary,
            modifier = Modifier.padding(top = 16.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            color = DjColors.TextSecondary,
            modifier = Modifier.padding(top = 8.dp),
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                Text(actionLabel)
            }
        }
    }
}
