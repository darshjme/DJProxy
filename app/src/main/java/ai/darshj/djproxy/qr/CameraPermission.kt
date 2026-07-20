package ai.darshj.djproxy.qr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** The four honest states the ScanSheet renders. Never claims access it doesn't have. */
enum class CameraAccess {
    /** No camera hardware at all (some tablets, TV boxes, emulators without a virtual camera). */
    NO_CAMERA,

    /** CAMERA granted — the live preview + analyzer can run. */
    GRANTED,

    /** Not yet requested — prompt the user (auto-launched once by the scanner). */
    NEEDS_REQUEST,

    /** Requested and denied, but re-askable — show rationale + a retry button. */
    DENIED_RATIONALE,

    /** Denied with "don't ask again" — the OS will ignore further requests; guide to Settings. */
    PERMANENTLY_DENIED,
}

/** Stateless helpers (no Compose) so non-UI callers can query capability too. */
object CameraPermission {
    fun deviceHasCamera(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}

/**
 * Handle the ScanSheet holds: current [status], a [request] to launch the system permission dialog,
 * and [openAppSettings] for the permanently-denied path.
 */
@Stable
class CameraPermissionController internal constructor(
    val status: CameraAccess,
    private val onRequest: () -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    fun request() = onRequest()
    fun openAppSettings() = onOpenSettings()
}

/**
 * Owns the CAMERA runtime-permission flow entirely inside the Composable, per DESIGN_V4 §4: the
 * `ActivityResultContracts.RequestPermission` launcher is created here via
 * [rememberLauncherForActivityResult], so no `MainActivity` edit is needed. Re-checks the grant on
 * `ON_RESUME` (the user may flip it in system Settings and come back).
 */
@Composable
fun rememberCameraPermission(): CameraPermissionController {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val hasCamera = remember(context) { CameraPermission.deviceHasCamera(context) }

    var granted by remember { mutableStateOf(CameraPermission.isGranted(context)) }
    var requestedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        granted = isGranted
        requestedOnce = true
    }

    // A user who grants from system Settings won't re-trigger the launcher callback; re-check on resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = CameraPermission.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val canAskAgain = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ?: true
    val status = when {
        !hasCamera -> CameraAccess.NO_CAMERA
        granted -> CameraAccess.GRANTED
        !requestedOnce -> CameraAccess.NEEDS_REQUEST
        canAskAgain -> CameraAccess.DENIED_RATIONALE
        else -> CameraAccess.PERMANENTLY_DENIED
    }

    return remember(status, launcher, activity) {
        CameraPermissionController(
            status = status,
            onRequest = { launcher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = { activity?.let { openAppSettings(it) } },
        )
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

/** Walk the Context wrapper chain to the hosting Activity (needed for rationale + settings intent). */
internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
