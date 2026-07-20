package ai.darshj.djproxy.store

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType

/**
 * The display / metadata projection of a vaulted proxy.
 *
 * It deliberately carries **no plaintext password** so it is safe to log, snapshot, and recompose.
 * The password lives only as ciphertext (see [SharedPreferencesProxyStore], via the AES-256-GCM
 * AndroidKeyStore path) and is materialised on demand by [ProxyStore.resolve] into a full
 * [ProxyConfig] right before an apply. [hasPassword] tells the UI whether an encrypted blob exists
 * for this entry (false on API 21-22 where the Keystore GCM path is unavailable — the entry is
 * still saved, the password is simply session-only, never stored in the clear).
 *
 * @property id          stable UUID assigned on save.
 * @property name        user label, e.g. "DE residential".
 * @property type        SOCKS5 / HTTP — reuses the core [ProxyType] enum.
 * @property host        proxy host (IPv4 literal or hostname).
 * @property port        proxy port.
 * @property username    plain login (not a secret; the password is the secret).
 * @property dnsServer   upstream DNS resolved through the proxy — mirrors [ProxyConfig.dnsServer].
 * @property hasPassword true if an encrypted password blob is persisted for this id.
 * @property origin      [ProxyOrigin.USER] or [ProxyOrigin.FREE_PUBLIC].
 * @property isDefault   true if this is the preselected default entry.
 * @property order       stable sort key (ascending) controlling list position.
 */
data class SavedProxy(
    val id: String,
    val name: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String,
    val dnsServer: String,
    val hasPassword: Boolean,
    val origin: ProxyOrigin,
    val isDefault: Boolean,
    val order: Int,
) {
    /**
     * Rebuild a full [ProxyConfig] for a reuse/apply. The [password] is supplied by the store after
     * decrypting the ciphertext blob (empty string when there is no saved password — fail-closed:
     * a dropped credential never becomes plaintext, and the subsequent pre-flight surfaces
     * AuthRejected honestly rather than the app inventing a credential).
     */
    fun toConfig(password: String = ""): ProxyConfig = ProxyConfig(
        type = type,
        host = host,
        port = port,
        username = username,
        password = password,
        dnsServer = dnsServer,
    )

    /** Redacted one-liner for the row subtitle; reuses the core redaction so no secret ever leaks. */
    fun redacted(): String = toConfig().redacted()

    companion object {
        /**
         * Pure mapper: project a [ProxyConfig] into vault metadata. The password is intentionally
         * dropped here (it is persisted separately as ciphertext); [hasPassword] records only whether
         * one was present. Used by the store on save/update and unit-tested directly.
         */
        fun fromConfig(
            id: String,
            name: String,
            config: ProxyConfig,
            origin: ProxyOrigin = ProxyOrigin.USER,
            isDefault: Boolean = false,
            order: Int = 0,
            hasPassword: Boolean = config.password.isNotEmpty(),
        ): SavedProxy = SavedProxy(
            id = id,
            name = name,
            type = config.type,
            host = config.host,
            port = config.port,
            username = config.username,
            dnsServer = config.dnsServer,
            hasPassword = hasPassword,
            origin = origin,
            isDefault = isDefault,
            order = order,
        )
    }
}
