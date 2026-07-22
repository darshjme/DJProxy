package ai.darshj.djproxy.freeproxy

import ai.darshj.djproxy.core.ProxyType
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * TTL cache for the free public list. Holds the last successful merge in memory and mirrors it to a
 * small hand-serialised blob (via the injected [Persistence]) so the list survives process death.
 *
 * TTL is [TTL_MS] (6 h): [getFresh] returns only a within-TTL snapshot; [getAny] returns whatever is
 * stored (used to serve stale results when a refresh fails). The blob is encoded/decoded by
 * [FreeProxyCodec] — pure and JVM-testable (no JSON, no Android). The production [Persistence] is a
 * `SharedPreferences` blob; tests inject an in-memory one.
 */
class FreeProxyCache(
    private val persistence: Persistence = InMemoryPersistence(),
    private val ttlMs: Long = TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A stored list plus the epoch-millis it was fetched. */
    data class Snapshot(val entries: List<FreeProxyEntry>, val fetchedAt: Long)

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

    /** Replace the cache with [entries] fetched at [fetchedAt], persisting the blob. */
    fun put(entries: List<FreeProxyEntry>, fetchedAt: Long) {
        val snapshot = Snapshot(entries, fetchedAt)
        memory = snapshot
        persistence.write(FreeProxyCodec.encode(snapshot))
    }

    /** Drop the in-memory + persisted cache. */
    fun clear() {
        memory = null
        persistence.clear()
    }

    private fun load(): Snapshot? {
        memory?.let { return it }
        val decoded = persistence.read()?.let(FreeProxyCodec::decode) ?: return null
        memory = decoded
        return decoded
    }

    companion object {
        /** 6 hours. */
        const val TTL_MS: Long = 6L * 60 * 60 * 1000
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
 * Pure serializer for [FreeProxyCache.Snapshot]. Line 1 is `fetchedAt`; each following line is a
 * unit-separator (``)-delimited record `type␟host␟port␟urlEncodedLabel`. URL-encoding the label
 * neutralises the delimiter and any control chars. Malformed records are skipped, so a corrupt blob
 * degrades to a smaller (or empty) list rather than throwing.
 *
 * v2 records append the liveness fields the health sweep sets — `alive(0|1)`, `latencyMs`,
 * `urlEncodedExitIp`, `lastCheckedAt` (empty string for nulls) — after the original four fields.
 * Legacy 4-field v1 records still decode (liveness defaults), so an old blob degrades gracefully.
 */
object FreeProxyCodec {
    private const val SEP = ''

    fun encode(snapshot: FreeProxyCache.Snapshot): String {
        val sb = StringBuilder()
        sb.append(snapshot.fetchedAt).append('\n')
        for (e in snapshot.entries) {
            sb.append(e.type.name).append(SEP)
                .append(e.host).append(SEP)
                .append(e.port).append(SEP)
                .append(enc(e.sourceLabel)).append(SEP)
                .append(if (e.alive) '1' else '0').append(SEP)
                .append(e.latencyMs?.toString() ?: "").append(SEP)
                .append(e.exitIp?.let(::enc) ?: "").append(SEP)
                .append(e.lastCheckedAt?.toString() ?: "").append('\n')
        }
        return sb.toString()
    }

    fun decode(blob: String): FreeProxyCache.Snapshot? {
        val lines = blob.split('\n')
        if (lines.isEmpty()) return null
        val fetchedAt = lines[0].trim().toLongOrNull() ?: return null
        val entries = ArrayList<FreeProxyEntry>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val f = line.split(SEP)
            // 4 fields = legacy v1 record (liveness defaults); 8 fields = v2 with liveness.
            if (f.size != 4 && f.size != 8) continue
            val type = runCatching { ProxyType.valueOf(f[0]) }.getOrNull() ?: continue
            val host = f[1]
            val port = f[2].toIntOrNull() ?: continue
            val label = dec(f[3])
            if (f.size == 4) {
                entries.add(FreeProxyEntry(type, host, port, label))
            } else {
                entries.add(
                    FreeProxyEntry(
                        type = type,
                        host = host,
                        port = port,
                        sourceLabel = label,
                        alive = f[4] == "1",
                        latencyMs = f[5].toLongOrNull(),
                        exitIp = f[6].takeIf { it.isNotEmpty() }?.let(::dec),
                        lastCheckedAt = f[7].toLongOrNull(),
                    )
                )
            }
        }
        return FreeProxyCache.Snapshot(entries, fetchedAt)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
