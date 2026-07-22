package ai.darshj.djproxy.freeproxy

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType

/**
 * One entry from a **maintained, community-listed public proxy list** (jetkai / proxifly).
 *
 * These servers are **untrusted**: unvetted, community-listed endpoints that can be slow, go offline,
 * log traffic, or inject content. The UI surfaces that caveat ([UNTRUSTED_CAVEAT]) and gates apply/save
 * behind a one-tap consent. An entry carries **no credentials** — public proxies are auth-less, so
 * [toConfig] yields a `ProxyConfig` with empty user/pass.
 *
 * [key] is stable (`free:<scheme>:<host>:<port>`) and is used both as the status-map key and as the
 * seed identity when a free entry is saved into the vault (origin `FREE_PUBLIC`).
 */
data class FreeProxyEntry(
    val type: ProxyType,
    val host: String,
    val port: Int,
    /** Human label of the upstream list this entry came from, e.g. "jetkai · socks5". */
    val sourceLabel: String,
    // ---- liveness (set by FreeProxyHealthChecker via copy(); defaults preserve legacy behavior) ----
    /** Probe round-trip through the proxy from the last health sweep, or null (never checked). */
    val latencyMs: Long? = null,
    /** Egress IP observed by the probe, when the endpoint reflected one. Null otherwise. */
    val exitIp: String? = null,
    /** Epoch-millis of the last health check, or null (never checked). */
    val lastCheckedAt: Long? = null,
    /** True only when the last sweep got a full green [proxy.ValidationResult.Success]. */
    val alive: Boolean = false,
) {
    /** Stable identity used in the live-status map and as a vault seed. */
    val key: String get() = "free:${type.scheme}:$host:$port"

    /** Display form (no auth to redact — public proxies are credential-less). */
    val display: String get() = "${type.scheme}://$host:$port"

    /** Full dial config for the existing pre-flight / apply path. No auth. */
    fun toConfig(): ProxyConfig = ProxyConfig(type = type, host = host, port = port)

    companion object {
        /**
         * Honest, unmissable caveat the UI must show above the free-servers section (and again as a
         * one-tap consent before an apply/save). Kept here so the wording has a single source of truth;
         * the ui lane may mirror it into `strings.xml`.
         */
        const val UNTRUSTED_CAVEAT: String =
            "Untrusted public proxies. These are unvetted, community-listed servers. They can be " +
                "slow, go offline, log traffic, or inject content. Don't send sensitive data. For " +
                "real privacy use your own proxy or Tor."
    }
}
