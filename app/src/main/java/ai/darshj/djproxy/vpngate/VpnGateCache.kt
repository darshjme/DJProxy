package ai.darshj.djproxy.vpngate

import ai.darshj.djproxy.config.Base64Compat
import ai.darshj.djproxy.config.ImportResult
import ai.darshj.djproxy.config.OvpnParser
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * TTL cache for the VPN Gate catalog. Holds the last successful parse in memory and mirrors it to a
 * hand-serialised blob (via the injected [Persistence]) so the catalog survives process death and a
 * failed refresh can serve stale rows (SSOT acceptance: "degrades to stale cache").
 *
 * TTL is [TTL_MS] (1 h — VPN Gate rotates volunteer nodes far faster than the free-proxy lists, so a
 * shorter TTL than `FreeProxyCache`'s 6 h is honest): [getFresh] returns only a within-TTL snapshot;
 * [getAny] returns whatever is stored. The blob is encoded/decoded by [VpnGateCodec] — pure and
 * JVM-testable (no JSON, no Android). Production [Persistence] is a `SharedPreferences` blob; tests
 * inject an in-memory one. Structural clone of `freeproxy.FreeProxyCache`.
 */
class VpnGateCache(
    private val persistence: Persistence = InMemoryPersistence(),
    private val ttlMs: Long = TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A stored catalog plus the epoch-millis it was fetched. */
    data class Snapshot(val servers: List<VpnGateServer>, val fetchedAt: Long)

    /** Storage seam: read/clear the single blob. Injected so the cache is unit-testable off-device. */
    interface Persistence {
        fun read(): String?
        fun write(value: String)
        fun clear()
    }

    @Volatile private var memory: Snapshot? = null

    /** The freshest snapshot within the TTL, or null (never fetched, or expired). */
    fun getFresh(): Snapshot? {
        val s = load() ?: return null
        return if (clock() - s.fetchedAt in 0..ttlMs) s else null
    }

    /** Any stored snapshot, regardless of age — used for the serve-stale-on-failure path. */
    fun getAny(): Snapshot? = load()

    /** Replace the cache with [servers] fetched at [fetchedAt], persisting the blob. */
    fun put(servers: List<VpnGateServer>, fetchedAt: Long) {
        val snapshot = Snapshot(servers, fetchedAt)
        memory = snapshot
        persistence.write(VpnGateCodec.encode(snapshot))
    }

    /** Drop the in-memory + persisted cache. */
    fun clear() {
        memory = null
        persistence.clear()
    }

    private fun load(): Snapshot? {
        memory?.let { return it }
        val decoded = persistence.read()?.let(VpnGateCodec::decode) ?: return null
        memory = decoded
        return decoded
    }

    companion object {
        /** 1 hour. */
        const val TTL_MS: Long = 60L * 60 * 1000
    }

    /** Simple process-lifetime persistence for tests / no-context callers. */
    class InMemoryPersistence : Persistence {
        @Volatile private var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }
}

/**
 * Pure serializer for [VpnGateCache.Snapshot]. Line 1 is `fetchedAt`; each following line is a
 * unit-separator (``)-delimited record. The bulky `.ovpn` is NOT stored decoded — only the compact
 * original `configB64` is persisted (base64 has no separator/newline char), and both the decoded text
 * and the dialable flag/[dialConfig] are re-derived on [decode] via the existing [OvpnParser], so a
 * stored row can never drift from what the parser would produce. Malformed records are skipped, so a
 * corrupt blob degrades to a smaller (or empty) catalog rather than throwing.
 *
 * Record layout (9 fields):
 * `host␟ip␟urlEnc(countryLong)␟countryShort␟ping␟score␟speed␟sessions␟uptimeMs␟configB64`
 */
object VpnGateCodec {
    private const val SEP = '' // ASCII unit separator - never in base64 or our fields
    private const val FIELDS = 10

    fun encode(snapshot: VpnGateCache.Snapshot): String {
        val sb = StringBuilder()
        sb.append(snapshot.fetchedAt).append('\n')
        for (s in snapshot.servers) {
            sb.append(s.hostName).append(SEP)
                .append(s.ip).append(SEP)
                .append(enc(s.countryLong)).append(SEP)
                .append(s.countryShort).append(SEP)
                .append(s.ping).append(SEP)
                .append(s.score).append(SEP)
                .append(s.speed).append(SEP)
                .append(s.sessions).append(SEP)
                .append(s.uptimeMs).append(SEP)
                .append(s.configB64).append('\n')
        }
        return sb.toString()
    }

    fun decode(blob: String): VpnGateCache.Snapshot? {
        val lines = blob.split('\n')
        if (lines.isEmpty()) return null
        val fetchedAt = lines[0].trim().toLongOrNull() ?: return null
        val servers = ArrayList<VpnGateServer>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val f = line.split(SEP)
            if (f.size != FIELDS) continue
            val configB64 = f[9]
            val ovpn = Base64Compat.decodeToString(configB64) ?: continue
            val dialConfig = when (val r = OvpnParser.parse(ovpn)) {
                is ImportResult.Single -> r.config
                else -> null
            }
            servers.add(
                VpnGateServer(
                    hostName = f[0],
                    ip = f[1],
                    countryLong = dec(f[2]),
                    countryShort = f[3],
                    ping = f[4].toIntOrNull() ?: -1,
                    score = f[5].toLongOrNull() ?: 0L,
                    speed = f[6].toLongOrNull() ?: 0L,
                    sessions = f[7].toIntOrNull() ?: 0,
                    uptimeMs = f[8].toLongOrNull() ?: 0L,
                    ovpn = ovpn,
                    configB64 = configB64,
                    directlyDialable = dialConfig != null,
                    dialConfig = dialConfig,
                ),
            )
        }
        return VpnGateCache.Snapshot(servers, fetchedAt)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
