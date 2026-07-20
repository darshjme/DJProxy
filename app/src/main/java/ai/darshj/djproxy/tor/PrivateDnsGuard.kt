package ai.darshj.djproxy.tor

import android.content.Context
import android.provider.Settings

/**
 * Detects Android's system-wide Private DNS strict mode — the one device setting that silently
 * defeats the MapDNS + SOCKS5-domain-CONNECT `.onion` path. In "hostname" (strict) mode ALL system
 * DNS is wrapped in DNS-over-TLS straight to the named provider, bypassing the tun's plaintext UDP:53
 * interception entirely (LeakPolicy only classifies plaintext UDP-to-sentinel as tunnelled DNS). A
 * `.onion` name then gets a real NXDOMAIN (RFC 7686) with no error surfaced anywhere else in the app.
 *
 * Read-only Settings.Global probe; never throws. "off" / "opportunistic" (Automatic) are unaffected
 * — this only flags the explicit custom-provider case a privacy-hardened user is likely to have set.
 */
object PrivateDnsGuard {
    fun isStrictModeActive(context: Context): Boolean = runCatching {
        Settings.Global.getString(context.contentResolver, "private_dns_mode") == "hostname"
    }.getOrDefault(false)
}
