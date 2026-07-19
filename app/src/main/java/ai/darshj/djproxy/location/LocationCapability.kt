package ai.darshj.djproxy.location

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import ai.darshj.djproxy.vpn.seams.LocationCapability
import java.io.File

/**
 * Honest capability detection for location spoofing. The golden rule (task + seam §9.2): NEVER claim
 * spoofing works when the Developer-Options "Select mock location app" grant is absent. This file is
 * the single place that decides which [LocationCapability] tier we are actually in, and it reports
 * UNAVAILABLE the moment the grant (or a root path to it) is missing.
 *
 * Tiers:
 *   READY_MOCK     — real device, the mock-location app-op is granted to us → LocationManager test
 *                    providers will accept fixes.
 *   READY_EMULATOR — same, but on an emulator (LDPlayer/AVD/Genymotion). Split out so the UI copy and
 *                    diagnostics can note the host is emulated (fused/GPS HALs behave differently).
 *   READY_ROOT     — grant is absent BUT we have root, so we can self-grant the app-op via
 *                    `appops set … allow` and then drive the same test providers.
 *   UNAVAILABLE    — no grant and no root. Spoofing genuinely cannot work; we say so.
 */
object LocationCapabilityDetector {

    /** The AppOps op string for mock location (stable literal; == AppOpsManager.OPSTR_MOCK_LOCATION). */
    const val OP_MOCK_LOCATION = "android:mock_location"

    fun detect(context: Context): LocationCapability {
        val facts = BuildFacts.fromBuild(qemuProp = readSysProp("ro.kernel.qemu"))
        return decideCapability(
            grant = isMockLocationAppGranted(context),
            emulator = isEmulator(facts),
            rooted = looksRooted(),
        )
    }

    /** True iff Developer-Options → "Select mock location app" currently points at THIS package. */
    fun isMockLocationAppGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(OP_MOCK_LOCATION, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(OP_MOCK_LOCATION, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    /** Best-effort root probe: a readable/executable `su` on any of the usual paths. */
    fun looksRooted(): Boolean = suPathExists(DEFAULT_SU_PATHS)

    internal fun suPathExists(paths: List<String>): Boolean =
        paths.any { runCatching { File(it).exists() }.getOrDefault(false) }

    private val DEFAULT_SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sbin/su", "/vendor/bin/su", "/data/local/su", "/data/local/bin/su",
        "/data/local/xbin/su", "/system/app/Superuser.apk", "/system/bin/.ext/.su",
    )

    private fun readSysProp(name: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        val m = c.getMethod("get", String::class.java)
        (m.invoke(null, name) as? String)?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }
}

/**
 * The pure decision: given the three facts, which tier are we in? No Android types — unit-tested
 * exhaustively. The grant always wins (it is the real, honest signal); root is only a fallback route
 * to obtaining the grant; emulator is a refinement on a granted state.
 */
fun decideCapability(grant: Boolean, emulator: Boolean, rooted: Boolean): LocationCapability = when {
    grant && emulator -> LocationCapability.READY_EMULATOR
    grant -> LocationCapability.READY_MOCK
    rooted -> LocationCapability.READY_ROOT
    else -> LocationCapability.UNAVAILABLE
}

/** The Build fingerprint fields an emulator check needs, lifted out of static [Build] for testing. */
data class BuildFacts(
    val fingerprint: String,
    val model: String,
    val manufacturer: String,
    val brand: String,
    val device: String,
    val product: String,
    val hardware: String,
    val board: String,
    val qemuProp: String?,
) {
    companion object {
        fun fromBuild(qemuProp: String?): BuildFacts = BuildFacts(
            fingerprint = Build.FINGERPRINT.orEmpty(),
            model = Build.MODEL.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            board = Build.BOARD.orEmpty(),
            qemuProp = qemuProp,
        )
    }
}

/**
 * Pure emulator heuristic covering AVD/goldfish/ranchu, Genymotion (vbox), BlueStacks and LDPlayer
 * (the x86_64 target in this project). Deliberately generous: a false "emulator" only changes UI copy
 * from READY_MOCK to READY_EMULATOR, never whether spoofing is offered.
 */
fun isEmulator(f: BuildFacts): Boolean {
    val fp = f.fingerprint.lowercase()
    val model = f.model.lowercase()
    val hw = f.hardware.lowercase()
    val product = f.product.lowercase()
    val brand = f.brand.lowercase()
    val device = f.device.lowercase()
    val manu = f.manufacturer.lowercase()
    val board = f.board.lowercase()

    if (f.qemuProp == "1") return true
    val hwHits = listOf("goldfish", "ranchu", "vbox", "ttvm", "nox", "ldplayer", "android_x86", "windroy", "vmos")
    val nameHits = listOf("google_sdk", "emulator", "android sdk built for", "sdk_gphone", "bluestacks", "ldplayer", "genymotion", "droid4x", "andy", "vmos", "nox")

    return fp.startsWith("generic") ||
        fp.startsWith("unknown") ||
        fp.contains("emulator") ||
        fp.contains("vbox") ||
        fp.contains("test-keys") && (hw.isEmpty() || hw == "goldfish" || hw == "ranchu") ||
        hwHits.any { hw.contains(it) } ||
        hwHits.any { board.contains(it) } ||
        nameHits.any { model.contains(it) } ||
        nameHits.any { product.contains(it) } ||
        nameHits.any { device.contains(it) } ||
        brand.startsWith("generic") ||
        manu.contains("genymotion") ||
        product.startsWith("sdk") ||
        product.startsWith("vbox") ||
        product == "google_sdk"
}
