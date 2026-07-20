package ai.darshj.djproxy.location

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One coordinate fix pushed into the mock providers. */
data class Fix(val lat: Double, val lng: Double, val accuracyM: Float = 12f)

/**
 * Great-circle distance in metres between two lat/lng points (haversine). Pure; used by the self-test
 * to decide whether a read-back fix matches the known fix we wrote.
 */
internal fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/** Outcome of reading a written fix back out of a provider. Honest: a refused/blocked read is [Unreadable]. */
sealed interface ReadBack {
    /** The provider returned a fix within tolerance of the one we wrote. */
    data class Match(val lat: Double, val lng: Double, val distanceM: Double) : ReadBack
    /** The provider returned a fix, but it is NOT where we wrote (something else owns the provider). */
    data class Mismatch(val lat: Double, val lng: Double, val distanceM: Double) : ReadBack
    /** No read was possible — the app holds no location-read permission, or the provider returned null. */
    data object Unreadable : ReadBack
}

/**
 * Per-provider self-test result. [accepted] is the AUTHORITATIVE signal (the platform took the write
 * without throwing); [readBack] is a best-effort confirmation that only succeeds when the app can also
 * read location. We never treat a non-accepting provider as working (honesty seam).
 */
data class ProviderProbe(val provider: String, val accepted: Boolean, val readBack: ReadBack)

/** Aggregate self-test outcome across the test providers plus whether the fused mock is active. */
data class SelfTestOutcome(val probes: List<ProviderProbe>, val fusedActive: Boolean)

/**
 * The minimal publish surface [LocationControllerImpl] depends on — an interface so the controller
 * (and its pref-gate / capability / state-machine flow) can be unit-tested on the JVM with a fake,
 * while production wires the real [MockLocationEngine]. Every method is side-effect-only and never
 * throws (implementations swallow platform exceptions).
 */
interface MockPublisher {
    fun ensureGrant(): Boolean
    fun start(fix: Fix): Boolean
    fun publish(fix: Fix): Boolean
    fun stop()
    /** Write a KNOWN fix and read it back to prove the providers actually took it. */
    fun selfTest(fix: Fix): SelfTestOutcome
    val fusedActive: Boolean
    val activeProviders: Set<String>
}

/**
 * The mutable side effects the provider state machine needs, abstracted so the machine itself is pure
 * and unit-testable with a fake. The Android implementation is [AndroidLocationSink]; tests use a
 * recording fake.
 */
interface LocationSink {
    /** Register a test provider; returns false if the platform refused (e.g. app-op not granted). */
    fun add(name: String): Boolean
    fun enable(name: String)
    fun disable(name: String)
    fun remove(name: String)
    /** Push one fix into an already-added provider; returns false if the platform rejected it. */
    fun push(name: String, fix: Fix): Boolean

    /**
     * Best-effort read-back of the platform's last fix for [name]; null if unreadable (the app holds no
     * location-read permission, or the provider has no fix). Default null so existing sinks/fakes that
     * do not implement read-back compile unchanged.
     */
    fun read(name: String): Fix? = null
}

enum class MockState { STOPPED, ACTIVE }

/**
 * The provider state machine (task: "unit-test … the provider state machine"). Pure: it holds no
 * Android types, only the set of providers it manages and which ones the [LocationSink] actually
 * accepted. Transitions:
 *
 *   STOPPED --start()--> ACTIVE   (adds+enables each provider; keeps only the ones that succeeded)
 *   ACTIVE  --publish()->  ACTIVE (pushes a fix to every accepted provider)
 *   ACTIVE  --stop()---> STOPPED  (disables+removes every accepted provider)
 *
 * Invariants enforced (and tested): publish() before start() is a no-op returning false; start() is
 * idempotent; stop() is idempotent and always leaves no providers registered; a provider the sink
 * refuses to add is never treated as active (so we never *claim* a fix went out when it did not).
 */
class MockProviderMachine(
    private val sink: LocationSink,
    val providers: List<String> = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
) {
    var state: MockState = MockState.STOPPED
        private set

    private val activeSet = LinkedHashSet<String>()

    /** Providers that were successfully added AND enabled. Read-only view for callers/tests. */
    val active: Set<String> get() = activeSet

    /** @return true if at least one provider is now live (i.e. spoofing can actually publish). */
    fun start(): Boolean {
        if (state == MockState.ACTIVE) return activeSet.isNotEmpty()
        activeSet.clear()
        for (p in providers) {
            if (sink.add(p)) {
                sink.enable(p)
                activeSet.add(p)
            }
        }
        state = MockState.ACTIVE
        return activeSet.isNotEmpty()
    }

    /** @return true if the fix reached at least one live provider. */
    fun publish(fix: Fix): Boolean {
        if (state != MockState.ACTIVE) return false
        var any = false
        for (p in activeSet) {
            if (sink.push(p, fix)) any = true
        }
        return any
    }

    fun stop() {
        if (state == MockState.STOPPED) return
        for (p in activeSet) {
            runCatching { sink.disable(p) }
            runCatching { sink.remove(p) }
        }
        activeSet.clear()
        state = MockState.STOPPED
    }

    /**
     * Write [fix] to every provider and read it back to prove the write landed. Ensures the machine is
     * started first (idempotent). Every declared provider is reported: one that was refused (not active)
     * comes back `accepted=false, Unreadable` — we never fabricate a probe that "took". [toleranceM] is
     * the read-back match radius (GPS test fixes are exact, but network providers may snap/round).
     */
    fun selfTest(fix: Fix, toleranceM: Double = 100.0): List<ProviderProbe> {
        if (state != MockState.ACTIVE) start()
        return providers.map { p ->
            if (p !in activeSet) {
                ProviderProbe(p, accepted = false, readBack = ReadBack.Unreadable)
            } else {
                val accepted = sink.push(p, fix)
                ProviderProbe(p, accepted, classifyRead(sink.read(p), fix, toleranceM))
            }
        }
    }

    private fun classifyRead(got: Fix?, target: Fix, toleranceM: Double): ReadBack {
        if (got == null) return ReadBack.Unreadable
        val d = haversineMeters(got.lat, got.lng, target.lat, target.lng)
        return if (d <= toleranceM) {
            ReadBack.Match(got.lat, got.lng, d)
        } else {
            ReadBack.Mismatch(got.lat, got.lng, d)
        }
    }
}

/**
 * [LocationSink] backed by the system [LocationManager] test-provider API. Every call is defensive:
 * if the mock-location app-op is not granted the platform throws [SecurityException] on add, which we
 * translate to `false` so the machine never marks the provider active. This is the honesty seam — a
 * refused add can never masquerade as a working spoof.
 */
class AndroidLocationSink(private val lm: LocationManager) : LocationSink {

    override fun add(name: String): Boolean = try {
        // Remove any stale registration from a previous crashed run first.
        runCatching { lm.removeTestProvider(name) }
        @Suppress("DEPRECATION")
        lm.addTestProvider(
            name,
            /* requiresNetwork = */ false,
            /* requiresSatellite = */ false,
            /* requiresCell = */ false,
            /* hasMonetaryCost = */ false,
            /* supportsAltitude = */ true,
            /* supportsSpeed = */ true,
            /* supportsBearing = */ true,
            /* powerRequirement = */ Criteria.POWER_LOW,
            /* accuracy = */ Criteria.ACCURACY_FINE,
        )
        true
    } catch (_: SecurityException) {
        false // app-op not granted for this provider
    } catch (_: IllegalArgumentException) {
        false // provider not mockable on this device
    } catch (_: Throwable) {
        false
    }

    override fun enable(name: String) {
        runCatching { lm.setTestProviderEnabled(name, true) }
    }

    override fun disable(name: String) {
        runCatching { lm.setTestProviderEnabled(name, false) }
    }

    override fun remove(name: String) {
        runCatching { lm.removeTestProvider(name) }
    }

    override fun push(name: String, fix: Fix): Boolean = try {
        lm.setTestProviderLocation(name, buildLocation(name, fix))
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: Throwable) {
        false
    }

    /**
     * Read the provider's last fix back. This needs a location-READ permission
     * (ACCESS_FINE/COARSE_LOCATION) which DJProxy does not declare (only the mock-location app-op is
     * used). So on a shipping build this returns null (→ [ReadBack.Unreadable]) and the self-test
     * relies on the authoritative write signal instead — never a fake confirmation.
     */
    @Suppress("MissingPermission")
    override fun read(name: String): Fix? = try {
        val loc = lm.getLastKnownLocation(name) ?: return null
        Fix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 0f)
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: Throwable) {
        null
    }

    private fun buildLocation(provider: String, fix: Fix): Location = Location(provider).apply {
        latitude = fix.lat
        longitude = fix.lng
        accuracy = fix.accuracyM
        altitude = 0.0
        speed = 0f
        bearing = 0f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        // Fused/GPS on API 26+ silently drops fixes missing these accuracy fields.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bearingAccuracyDegrees = 0.5f
            speedAccuracyMetersPerSecond = 0.5f
            verticalAccuracyMeters = fix.accuracyM
        }
    }
}

/**
 * Drives a device-wide mock location: the [LocationManager] test providers (gps + network) via the
 * [MockProviderMachine], plus — when Google Play services is present — Fused mock mode (so apps using
 * FusedLocationProviderClient, the common case, also see the spoof). Fused is reached by reflection so
 * the project takes no play-services dependency; absence is silently fine (test providers still work).
 *
 * The Android "emulator geo channel" (`adb emu geo fix` / telnet console) cannot be driven from inside
 * an app process, so on emulators we use the SAME test-provider path — which is the reliable in-app
 * mechanism there too. See [LocationSettingsPanel] for the honest capability copy.
 */
class MockLocationEngine(context: Context) : MockPublisher {

    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val machine = MockProviderMachine(AndroidLocationSink(lm))

    /** Reflection handle to a FusedLocationProviderClient, or null if Play services is absent. */
    private val fused: FusedMock? = FusedMock.tryCreate(appContext)

    /**
     * If the mock-location app-op is missing but root is available, self-grant it via `appops`. Returns
     * true if, after any attempt, the grant is now present. Called before [start] by the controller.
     */
    override fun ensureGrant(): Boolean {
        if (LocationCapabilityDetector.isMockLocationAppGranted(appContext)) return true
        if (!LocationCapabilityDetector.looksRooted()) return false
        runCatching {
            val cmd = "appops set ${appContext.packageName} ${LocationCapabilityDetector.OP_MOCK_LOCATION} allow"
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor()
        }
        return LocationCapabilityDetector.isMockLocationAppGranted(appContext)
    }

    /** @return true if at least one provider (or fused) is live and the first fix was accepted. */
    override fun start(fix: Fix): Boolean {
        val started = machine.start()
        val pushed = if (started) machine.publish(fix) else false
        val fusedOk = fused?.start(fix) ?: false
        return pushed || fusedOk
    }

    override fun publish(fix: Fix): Boolean {
        val a = machine.publish(fix)
        val b = fused?.push(fix) ?: false
        return a || b
    }

    override fun stop() {
        machine.stop()
        fused?.stop()
    }

    /**
     * Write a KNOWN [fix] into the test providers (and fused, if present) and read it back to prove the
     * providers took it. The per-provider write result is authoritative; the read-back is best-effort
     * (see [AndroidLocationSink.read]). Fused exposes no in-app read-back, so we report only whether the
     * fused mock is active after the write.
     */
    override fun selfTest(fix: Fix): SelfTestOutcome {
        val probes = machine.selfTest(fix)
        val fusedOk = fused?.start(fix) ?: false
        return SelfTestOutcome(probes, fusedActive = fusedOk || (fused?.active == true))
    }

    override val activeProviders: Set<String> get() = machine.active
    override val fusedActive: Boolean get() = fused?.active == true
}

/**
 * Reflective wrapper over com.google.android.gms.location.FusedLocationProviderClient's
 * setMockMode / setMockLocation. All calls are best-effort and swallow every throwable — if the
 * classes are missing (no Play services) [tryCreate] returns null and the caller ignores fused.
 */
private class FusedMock private constructor(private val client: Any) {

    @Volatile
    var active: Boolean = false
        private set

    fun start(fix: Fix): Boolean {
        // Set `active` BEFORE pushing: push() guards on `!active`, so the old
        // `active = invokeBool(...) && push(fix)` evaluated push() while active was still false and it
        // short-circuited to a no-op — mock mode was toggled on but no fix was ever published.
        if (invokeBool("setMockMode", true)) {
            active = true
            if (!push(fix)) {
                invokeBool("setMockMode", false)
                active = false
            }
        }
        return active
    }

    fun push(fix: Fix): Boolean {
        if (!active) return false
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = fix.lat
            longitude = fix.lng
            accuracy = fix.accuracyM
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                bearingAccuracyDegrees = 0.5f
                speedAccuracyMetersPerSecond = 0.5f
                verticalAccuracyMeters = fix.accuracyM
            }
        }
        return runCatching {
            val m = client.javaClass.getMethod("setMockLocation", Location::class.java)
            m.invoke(client, loc)
            true
        }.getOrDefault(false)
    }

    fun stop() {
        runCatching { invokeBool("setMockMode", false) }
        active = false
    }

    private fun invokeBool(method: String, arg: Boolean): Boolean = runCatching {
        val m = client.javaClass.getMethod(method, Boolean::class.javaPrimitiveType)
        m.invoke(client, arg)
        true
    }.getOrDefault(false)

    companion object {
        fun tryCreate(context: Context): FusedMock? = runCatching {
            val servicesCls = Class.forName("com.google.android.gms.location.LocationServices")
            val getClient = servicesCls.getMethod("getFusedLocationProviderClient", Context::class.java)
            val client = getClient.invoke(null, context) ?: return null
            FusedMock(client)
        }.getOrNull()
    }
}
