// IEngineControl.aidl  (engine lane)
//
// The control seam between the MAIN process (DjVpnService + its watchdog, vpn lane)
// and the isolated :engine process (EngineService, this lane). The main process owns
// the tun fd; it dup()s it and hands the copy across this Binder as a
// ParcelFileDescriptor, together with the loopback SOCKS parameters. The engine
// never opens the tun itself and never dials anything but loopback, so no native
// socket ever needs protect().
package ai.darshj.djproxy.engine;

interface IEngineControl {

    /**
     * Start (or restart) the native hev loop against the given tun fd.
     *
     * @param tun        a dup()'d packet-bridge fd owned by the main process; the engine takes
     *                   ownership of THIS copy and closes it on stop/crash.
     * @param socksPort  loopback port of the Kotlin LocalSocksServer (policy front).
     * @param mtu        tun MTU (must match the VpnService.Builder MTU).
     * @param logLevel   hev log verbosity: debug|info|warn|error|none.
     * Idempotent: calling start while running is a no-op that keeps the current loop.
     *
     * UDP is unconditionally dropped in the router (WebRTC/QUIC leak closed), so hev is always
     * pinned to SOCKS5 TCP-only; there is no UDP-relay parameter.
     */
    void start(in ParcelFileDescriptor tun, int socksPort, int mtu, String logLevel);

    /** Ask the native loop to quit and join it. Idempotent. */
    void stop();

    /**
     * Current engine lifecycle, encoded for the watchdog:
     *   0 = STOPPED, 1 = STARTING, 2 = RUNNING, 3 = CRASHED.
     * The watchdog treats anything != RUNNING while it expects RUNNING as fail-closed.
     */
    int state();

    /** hev packet counters: [txPackets, txBytes, rxPackets, rxBytes]; all zero when not running. */
    long[] stats();
}
