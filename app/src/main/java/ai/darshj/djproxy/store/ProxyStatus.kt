package ai.darshj.djproxy.store

/**
 * Live liveness of a proxy row, produced by [StatusChecker] running the frozen pre-flight
 * [proxy.PreflightValidator] — a real TCP connect + real SOCKS5/HTTP handshake + real probe through
 * the proxy, **never** a tunnel bring-up.
 *
 * [Unreachable.reason] / [Unreachable.hint] are copied verbatim from the typed
 * [proxy.ProxyError.message] / [proxy.ProxyError.hint], so the status list speaks the same honest
 * vocabulary as the connect flow (DNS fail / refused / timeout / auth rejected / not-a-socks5 / …).
 */
sealed interface ProxyStatus {

    /** Never checked. */
    data object Unknown : ProxyStatus

    /** A check is in flight. */
    data object Checking : ProxyStatus

    /** Pre-flight succeeded. [latencyMs] is the probe round-trip through the proxy. */
    data class Reachable(
        val latencyMs: Long,
        val exitIp: String?,
        val checkedAt: Long,
    ) : ProxyStatus

    /** Pre-flight failed. [reason]/[hint] mirror the typed ProxyError verbatim. */
    data class Unreachable(
        val reason: String,
        val hint: String,
        val checkedAt: Long,
    ) : ProxyStatus

    /** Millis of the last completed check, or null while Unknown/Checking. Convenience for the UI. */
    val checkedAtOrNull: Long?
        get() = when (this) {
            is Reachable -> checkedAt
            is Unreachable -> checkedAt
            else -> null
        }
}
