package ai.darshj.djproxy.compat

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.io.File

/**
 * Single source of runtime capability truth (SSOT DESIGN_V3 §7.3), read by `ui` + core + every
 * feature lane. Every check here is honest-by-construction: on any doubt (missing API, reflection
 * failure, exception) it reports the SAFE / false answer rather than claiming a capability that may
 * not actually be present. Nothing here ever crashes the caller.
 */
object CapabilityDetector {

    /** Current API level. Trivial wrapper kept here so every lane reads capability truth from one place. */
    fun apiLevel(): Int = Build.VERSION.SDK_INT

    /** True if this process is very likely running inside an emulator/virtual Android image. */
    fun isEmulator(): Boolean = emulatorName(liveFingerprints()) != null

    /**
     * Best-effort emulator vendor label ("LDPlayer", "BlueStacks", "Nox Player", "Genymotion",
     * "Android Studio AVD"), or null if this does not look like an emulator at all.
     */
    fun emulatorName(): String? = emulatorName(liveFingerprints())

    /**
     * True if Google Play services appear to be installed and usable on this device. Guards any use
     * of `FusedLocationProviderClient` (location lane): absent on de-Googled ROMs, most emulators
     * without a Google Play system image, and some OEM skus. Never throws.
     */
    fun hasPlayServices(context: Context): Boolean = try {
        val availability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
        availability.isGooglePlayServicesAvailable(context) ==
            com.google.android.gms.common.ConnectionResult.SUCCESS
    } catch (_: Throwable) {
        // ClassNotFoundException/NoClassDefFoundError if the optional GMS jar is stripped, or any
        // runtime failure talking to the (possibly absent) Play services process.
        false
    }

    /**
     * Best-effort root detection: a stock su binary on a known path, or a `test-keys` build tag
     * (AOSP/rooted signing, never present on a genuine retail build). Heuristic, not a security
     * boundary — used only to decide whether to *offer* the root-transparent hotspot tier / root
     * mock-location grant path, never to gate the core tunnel.
     */
    fun isRooted(): Boolean = isRooted(SU_PATHS, Build.TAGS)

    /**
     * True if this app currently holds the Android "mock location app" grant (Developer Options,
     * pre-Q) or the AppOps `MOCK_LOCATION` op (Q+). Read-only check; never requests the grant itself
     * (the OS provides no programmatic path — the user must flip it in Developer Options).
     */
    fun mockLocationGranted(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            val mode = appOps?.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ALLOW_MOCK_LOCATION) == "1"
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Heuristic emulator/VPN-bypass detector (SSOT §2.2 / §7.3): a protected control socket reaching
     * the network while the in-tun connectivity probe fails to complete is the signature of a guest
     * networking stack (common on LDPlayer/BlueStacks/Nox) routing tethered/bridged traffic AROUND
     * the guest `VpnService` tun entirely. Used by `HealthMonitor` to publish an honest
     * `emulatorBypassSuspected` chip instead of silently claiming full protection.
     */
    fun suspectVpnBypass(inTunOk: Boolean, controlNetOk: Boolean): Boolean = !inTunOk && controlNetOk

    // ---- testable cores (no live android.os.Build field reads — fixture-injectable) -------------

    /** Grouped raw identity fields read once from [Build], passed down to the pure detection code. */
    internal data class Fingerprints(
        val fingerprint: String,
        val hardware: String,
        val product: String,
        val model: String,
        val manufacturer: String,
        val brand: String,
        val device: String,
    )

    private fun liveFingerprints() = Fingerprints(
        fingerprint = Build.FINGERPRINT.orEmpty(),
        hardware = Build.HARDWARE.orEmpty(),
        product = Build.PRODUCT.orEmpty(),
        model = Build.MODEL.orEmpty(),
        manufacturer = Build.MANUFACTURER.orEmpty(),
        brand = Build.BRAND.orEmpty(),
        device = Build.DEVICE.orEmpty(),
    )

    /**
     * Pure, fixture-testable emulator detector. Every field is folded into one lowercase haystack and
     * matched against known vendor/AOSP-emulator tokens — the same signal set SSOT §7.3 specifies:
     * `generic|goldfish|ranchu|vbox|nox|ldplayer|bluestacks|genymotion`. Vendor-specific tokens are
     * checked before the generic AOSP-emulator fallback so a vendor build that also happens to run on
     * a goldfish/ranchu kernel is still attributed to the vendor.
     */
    internal fun emulatorName(f: Fingerprints): String? {
        val hay = listOf(f.fingerprint, f.hardware, f.product, f.model, f.manufacturer, f.brand, f.device)
            .joinToString("|") { it.lowercase() }

        return when {
            "ldplayer" in hay -> "LDPlayer"
            "bluestacks" in hay -> "BlueStacks"
            "nox" in hay -> "Nox Player"
            "genymotion" in hay -> "Genymotion"
            "vbox" in hay -> "Genymotion/VirtualBox"
            "goldfish" in hay || "ranchu" in hay || "generic" in hay -> "Android Studio AVD"
            else -> null
        }
    }

    /** Pure, fixture-testable root detector: `su` on any known path, OR a `test-keys` build tag. */
    internal fun isRooted(suPaths: List<String>, buildTags: String?): Boolean {
        if (buildTags?.contains("test-keys") == true) return true
        return suPaths.any { path ->
            try {
                File(path).exists()
            } catch (_: SecurityException) {
                false
            }
        }
    }

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/su",
        "/system/bin/.ext/.su",
        "/system/usr/we-need-root/su-backup",
        "/system/xbin/mu",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su",
        "/system/app/Superuser.apk",
        "/system/app/SuperSU.apk",
    )
}
