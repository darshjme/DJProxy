package ai.darshj.djproxy.hotspot

import ai.darshj.djproxy.vpn.seams.ShareState

/**
 * A LAN-share credential pair. When a share is started with `requireAuth = false` there is no
 * credential and clients connect anonymously; when `requireAuth = true` the controller mints a fresh
 * random pair so a device on the same Wi-Fi cannot silently ride the proxy without the QR/secret.
 */
data class LanCredential(val user: String, val pass: String) {
    /** The `user:pass` form embedded in [ShareState.LanProxy.cred] and in a proxy URI's userinfo. */
    fun asUserInfo(): String = "$user:$pass"

    companion object {
        /** Mints an unguessable credential. `pass` is 12 url-safe chars from a CSPRNG. */
        fun random(): LanCredential {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
            val rnd = java.security.SecureRandom()
            val sb = StringBuilder(12)
            repeat(12) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
            return LanCredential(user = "djproxy", pass = sb.toString())
        }

        /** Parses a `user:pass` string back into a credential, or null if it is not a valid pair. */
        fun parse(userInfo: String?): LanCredential? {
            if (userInfo.isNullOrEmpty()) return null
            val i = userInfo.indexOf(':')
            if (i <= 0 || i == userInfo.length - 1) return null
            return LanCredential(userInfo.substring(0, i), userInfo.substring(i + 1))
        }
    }
}

/**
 * Builds the one-tap client-setup payloads for a running LAN share. The LAN server speaks BOTH HTTP
 * (CONNECT + absolute-form) and SOCKS5 on the SAME port, so a single `host:port` works for either
 * scheme a client prefers. The QR encodes the HTTP proxy URI (the most widely supported "set a system
 * proxy" form); [forSocks5] is offered for clients that want SOCKS5 explicitly.
 *
 * Pure string building — deliberately free of Android types so it is unit-tested on the JVM.
 */
object QrPayload {

    /** `http://[user:pass@]host:port` — an HTTP proxy URI most OSes/browsers accept verbatim. */
    fun forHttp(host: String, port: Int, cred: LanCredential?): String =
        "http://${userInfo(cred)}$host:$port"

    /** `socks5://[user:pass@]host:port` for clients configured to use SOCKS5. */
    fun forSocks5(host: String, port: Int, cred: LanCredential?): String =
        "socks5://${userInfo(cred)}$host:$port"

    /** The payload embedded in a share QR / one-tap link. Defaults to the HTTP proxy URI. */
    fun forState(state: ShareState.LanProxy): String =
        forHttp(state.addr, state.port, LanCredential.parse(state.cred))

    /** A short human-readable multi-line hint a settings panel can show next to the QR. */
    fun humanHint(host: String, port: Int, cred: LanCredential?): String = buildString {
        appendLine("On the other device, set its proxy to:")
        appendLine("  Host: $host")
        appendLine("  Port: $port")
        if (cred != null) {
            appendLine("  User: ${cred.user}")
            appendLine("  Pass: ${cred.pass}")
        } else {
            appendLine("  (no username / password)")
        }
        append("Works as HTTP or SOCKS5 on the same port.")
    }

    private fun userInfo(cred: LanCredential?): String =
        if (cred == null) "" else "${cred.user}:${cred.pass}@"
}
