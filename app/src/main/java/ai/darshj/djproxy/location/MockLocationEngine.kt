package ai.darshj.djproxy.location

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock

/** One coordinate fix pushed into the mock providers. */
data class Fix(val lat: Double, val lng: Double, val accuracyM: Float = 12f)

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
class MockLocationEngine(context: Context) {

    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val machine = MockProviderMachine(AndroidLocationSink(lm))

    /** Reflection handle to a FusedLocationProviderClient, or null if Play services is absent. */
    private val fused: FusedMock? = FusedMock.tryCreate(appContext)

    /**
     * If the mock-location app-op is missing but root is available, self-grant it via `appops`. Returns
     * true if, after any attempt, the grant is now present. Called before [start] by the controller.
     */
    fun ensureGrant(): Boolean {
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
    fun start(fix: Fix): Boolean {
        val started = machine.start()
        val pushed = if (started) machine.publish(fix) else false
        val fusedOk = fused?.start(fix) ?: false
        return pushed || fusedOk
    }

    fun publish(fix: Fix): Boolean {
        val a = machine.publish(fix)
        val b = fused?.push(fix) ?: false
        return a || b
    }

    fun stop() {
        machine.stop()
        fused?.stop()
    }

    val activeProviders: Set<String> get() = machine.active
    val fusedActive: Boolean get() = fused?.active == true
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
