package ai.darshj.djproxy.vpn

/**
 * Live counters for the active tunnel. Immutable snapshot — the service publishes a fresh copy
 * on each tick; the UI just renders it. All byte counts are cumulative for the current session.
 */
data class TunnelStats(
    /** Bytes sent from device -> proxy (upload) this session. */
    val bytesUp: Long = 0,
    /** Bytes received proxy -> device (download) this session. */
    val bytesDown: Long = 0,
    /** TCP flows currently open through the proxy. */
    val activeConnections: Int = 0,
    /** Total TCP flows opened this session (monotonic). */
    val totalConnections: Long = 0,
    /** UDP datagrams dropped this session (WebRTC/QUIC kill count). Non-zero is expected & good. */
    val udpDropped: Long = 0,
    /** DNS queries answered by tunnelling over TCP to the configured upstream. */
    val dnsQueries: Long = 0,
) {
    companion object {
        val EMPTY = TunnelStats()
    }
}
