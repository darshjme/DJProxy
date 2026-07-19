package ai.darshj.djproxy.engine

/**
 * Engine-lane constants and small factories shared by [EngineService] (in the :engine process)
 * and any main-process client that binds [IEngineControl].
 *
 * These are the *only* magic values the engine owns; everything else (routes, protect(), the tun
 * fd's lifecycle) belongs to the vpn lane. Keeping them here means the loopback contract between
 * hev, the LocalSocksServer, and the VpnService is defined once.
 */
object TunnelConfig {

    /** The sidecar process suffix. The vpn lane's manifest declares EngineService with this. */
    const val PROCESS_SUFFIX = ":engine"

    /** Loopback host hev dials for SOCKS5. Never a real interface, so it is never protect()'d. */
    const val LOOPBACK_HOST = "127.0.0.1"

    /** Foreground-service notification wiring for the :engine process. */
    const val NOTIFICATION_CHANNEL_ID = "djproxy_engine"
    const val NOTIFICATION_CHANNEL_NAME = "DJProxy tunnel engine"
    const val NOTIFICATION_ID = 0x0DEE

    /** Encoded [EngineState] values crossing the [IEngineControl.state] Binder boundary. */
    const val STATE_STOPPED = 0
    const val STATE_STARTING = 1
    const val STATE_RUNNING = 2
    const val STATE_CRASHED = 3

    fun encode(state: EngineState): Int = when (state) {
        EngineState.Stopped -> STATE_STOPPED
        EngineState.Starting -> STATE_STARTING
        EngineState.Running -> STATE_RUNNING
        is EngineState.Crashed -> STATE_CRASHED
    }

    /**
     * Builds the [EngineConfig] the sidecar feeds to hev. [tunFd] is the fd number of the
     * ParcelFileDescriptor the main process handed us (already dup()'d into this process).
     */
    fun engineConfig(
        tunFd: Int,
        socksPort: Int,
        mtu: Int,
        logLevel: String,
    ): EngineConfig = EngineConfig(
        tunFd = tunFd,
        mtu = mtu,
        socksHost = LOOPBACK_HOST,
        socksPort = socksPort,
        ipv4Only = true,          // ::/0 is blackholed in-tun by the VpnService, never here.
        logLevel = normalizeLogLevel(logLevel),
        taskStackSize = 20480,
    )

    private val VALID_LOG_LEVELS = setOf("debug", "info", "warn", "error", "none")

    fun normalizeLogLevel(level: String?): String {
        val v = level?.trim()?.lowercase().orEmpty()
        return if (v in VALID_LOG_LEVELS) v else "warn"
    }
}
