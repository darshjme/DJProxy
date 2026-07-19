package ai.darshj.djproxy.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Configuration handed to the native hev-socks5-tunnel engine running in the :engine process.
 *
 * hev owns ONE job: read/write the bridge fd, terminate each TCP flow with lwIP, and forward every
 * flow as SOCKS5 to the Kotlin LocalSocksServer on loopback. It is pinned IPv4-only ([ipv4Only]),
 * so ::/0 packets die in-tun and never reach native code, and it only ever dials loopback, so no
 * native socket needs protect() — the single protect() seam lives in the main process instead.
 *
 * UDP is always dropped by the router before it can reach hev, so hev is pinned to SOCKS5 TCP-only
 * (udp: 'tcp'); there is no UDP-relay knob to misconfigure.
 */
data class EngineConfig(
    /** A dup() of the packet-bridge fd owned by the MAIN process. hev must NOT close the original. */
    val tunFd: Int,
    val mtu: Int = 1500,
    /** Loopback address of the Kotlin LocalSocksServer (the policy front). */
    val socksHost: String = "127.0.0.1",
    val socksPort: Int,
    /** Pin to IPv4; ::/0 is blackholed in-tun by the VpnService, not handled here. */
    val ipv4Only: Boolean = true,
    /** hev log verbosity: one of "debug", "info", "warn", "error", "none". */
    val logLevel: String = "warn",
    val taskStackSize: Int = 20480,
    /**
     * MapDNS (fake-IP DNS). When on, hev answers DNS sent to [mapDnsAddress]:53 locally with a
     * synthetic IP from [mapDnsNetwork]/[mapDnsNetmask], then — when the app connects to that fake IP —
     * dials the proxy with a SOCKS5 CONNECT carrying the ORIGINAL DOMAIN NAME, so the proxy's EXIT
     * resolves it. This is what makes DNS work on residential proxies that block outbound port 53
     * (nsocks/iproyal/luxsocks) AND makes DNS geolocate to the proxy region (OTT/geo unblocking),
     * with zero DNS packets ever leaving the device. The tun's addDnsServer points at [mapDnsAddress].
     */
    val mapDns: Boolean = true,
    val mapDnsAddress: String = "198.18.0.2",   // == TunConfig.DNS_SENTINEL
    val mapDnsPort: Int = 53,
    val mapDnsNetwork: String = "100.64.0.0",    // CGNAT shared space — unroutable, no real-dest clash
    val mapDnsNetmask: String = "255.192.0.0",   // /10 → ~4M synthetic slots
    val mapDnsCacheSize: Int = 10000,
) {
    /** Renders the exact YAML hev-socks5-tunnel parses via hev_socks5_tunnel_main_from_str(). */
    fun toYaml(): String = buildString {
        append("tunnel:\n")
        append("  mtu: ").append(mtu).append('\n')
        append("  ipv4: '198.18.0.1'\n")
        if (!ipv4Only) append("  ipv6: 'fc00::1'\n")
        append("socks5:\n")
        append("  address: '").append(socksHost).append("'\n")
        append("  port: ").append(socksPort).append('\n')
        append("  udp: 'tcp'\n")
        append("  mark: 0\n")
        if (mapDns) {
            append("mapdns:\n")
            append("  address: '").append(mapDnsAddress).append("'\n")
            append("  port: ").append(mapDnsPort).append('\n')
            append("  network: '").append(mapDnsNetwork).append("'\n")
            append("  netmask: '").append(mapDnsNetmask).append("'\n")
            append("  cache-size: ").append(mapDnsCacheSize).append('\n')
        }
        append("misc:\n")
        append("  task-stack-size: ").append(taskStackSize).append('\n')
        append("  log-level: '").append(logLevel).append("'\n")
    }
}

/** Lifecycle of the native engine as observed by the watchdog. */
sealed interface EngineState {
    object Stopped : EngineState
    object Starting : EngineState
    object Running : EngineState
    /** Native side exited unexpectedly; the watchdog holds routes fail-closed and restarts. */
    data class Crashed(val exitCode: Int, val reason: String) : EngineState
}

/** Snapshot of hev's own packet counters, read across the JNI boundary. */
data class EngineStats(
    val txPackets: Long = 0,
    val txBytes: Long = 0,
    val rxPackets: Long = 0,
    val rxBytes: Long = 0,
) {
    companion object { val EMPTY = EngineStats() }
}

/**
 * High-level driver of the native engine, living in the :engine process. Implemented by the
 * engine lane. START/STOP are idempotent; [state] feeds the main-process watchdog which owns the
 * restart-with-backoff policy and the fail-closed guarantee.
 */
interface EngineController {
    val state: StateFlow<EngineState>

    /** Blocks until the engine is running (or fails); safe to call again after a crash. */
    fun start(config: EngineConfig)

    /** Signals the native loop to quit and joins it. */
    fun stop()

    /** Best-effort live counters; returns [EngineStats.EMPTY] when not running. */
    fun stats(): EngineStats
}

/**
 * The raw JNI boundary to vendored hev-socks5-tunnel (MIT, C, lwIP), built from source via
 * externalNativeBuild/CMake with NDK r27+ (16KB-page aligned). The engine lane owns the C glue
 * and the CMakeLists; these signatures are the fixed contract the Kotlin side codes against.
 *
 * Call [load] exactly once (from the :engine process) before any native method. It is NOT invoked
 * from a static initializer on purpose, so merely referencing this object in unit tests / the main
 * process does not attempt to load the .so.
 */
object HevBridge {
    @Volatile private var loaded = false

    /** Loads libdjproxy-engine.so. Idempotent; throws UnsatisfiedLinkError if the ABI is missing. */
    @Synchronized
    fun load() {
        if (loaded) return
        System.loadLibrary("djproxy-engine")
        loaded = true
    }

    /**
     * Runs the hev tunnel loop. BLOCKS until [quit] is called or the loop errors.
     * @param configYaml output of [EngineConfig.toYaml].
     * @param tunFd a dup()'d tun fd; hev reads/writes it and does not close the caller's copy.
     * @return 0 on clean shutdown, non-zero on error.
     */
    external fun runBlocking(configYaml: String, tunFd: Int): Int

    /** Asks the running loop to stop; safe to call from another thread. */
    external fun quit()

    /** Returns [txPackets, txBytes, rxPackets, rxBytes]; all zero when not running. */
    external fun statsRaw(): LongArray
}
