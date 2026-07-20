package ai.darshj.djproxy.store

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The `.ovpn` vault contract — save / list / reuse / delete / reorder for VPN Gate OpenVPN profiles.
 *
 * A direct mirror of [ProxyStore], with ONE deliberate divergence: there is **no secret**. VPN Gate
 * volunteer profiles are anonymous (no username/password baked in), so — unlike the proxy vault's
 * AES-256-GCM AndroidKeyStore path — nothing here is encrypted. The bulky `.ovpn` text is plain
 * metadata persisted verbatim under a per-id key; [SavedOvpn] is a text-free display projection and
 * [resolve] materialises the full profile text on demand right before an external hand-off.
 *
 * AT-REST NOTE (auditors): because there is no cipher, a saved `.ovpn` is stored **plaintext**. That
 * is acceptable ONLY because VPN Gate profiles carry no credentials — a `<cert>`/`<key>` block or an
 * `auth-user-pass` secret would be at-rest cleartext. Do not repurpose this vault for cred-bearing
 * configs; the proxy vault ([SharedPreferencesProxyStore]) is the encrypted path for secrets.
 *
 * Reuse funnels through the UNCHANGED external-app hand-off (`vpngate.VpnGateController.connectOvpn`):
 * [resolve] simply returns the stored profile text; it adds no connect/tunnel logic of its own
 * (DJProxy still does NOT tunnel OpenVPN).
 */
interface OvpnVault {

    /** Reactive list of saved profiles, already ordered by [SavedOvpn.order]. */
    val saved: StateFlow<List<SavedOvpn>>

    /**
     * Insert a new saved profile (assigns a UUID + appends to the order). The [ovpn] text is persisted
     * verbatim under its own per-id key; the returned [SavedOvpn] never carries that text.
     */
    suspend fun save(
        name: String,
        countryShort: String,
        countryLong: String,
        hostName: String,
        ovpn: String,
    ): SavedOvpn

    /** Remove a profile and its stored `.ovpn` text blob. No-op if [id] is unknown. */
    suspend fun delete(id: String)

    /** Persist a new ordering. Ids not present keep their relative order after the listed ones. */
    suspend fun reorder(orderedIds: List<String>)

    /** Return the full stored `.ovpn` text for a reuse/hand-off, or null if the entry is gone. */
    suspend fun resolve(id: String): String?
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Implementation
// ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The single-writer, single-process `.ovpn` vault. Reuses the SAME persistence seam as the proxy
 * vault ([KeyValueStore] + [SharedPreferencesKeyValueStore]) but over a SEPARATE private prefs file
 * ([PREFS_FILE] = `djproxy_ovpn_vault`) so it can never collide with the proxy vault's `djproxy_vault`.
 * Metadata → [KeyValueStore] under [KEY_VAULT]; each profile's `.ovpn` text under `ovpn.<id>` (the
 * text-blob analogue of the proxy vault's `pw.<id>`). All mutations are serialised with a [Mutex] and
 * re-emit the reactive [saved] flow — the exact single-writer idiom of [SharedPreferencesProxyStore].
 */
class SharedPreferencesOvpnVault(
    private val kv: KeyValueStore,
) : OvpnVault {

    private val mutex = Mutex()

    private val _saved = MutableStateFlow<List<SavedOvpn>>(emptyList())
    override val saved: StateFlow<List<SavedOvpn>> = _saved.asStateFlow()

    init {
        // Synchronous initial load from persistence — cheap (a handful of rows), no I/O beyond prefs.
        _saved.value = OvpnVaultCodec.decode(kv.getString(KEY_VAULT)).sortedBy { it.order }
    }

    override suspend fun save(
        name: String,
        countryShort: String,
        countryLong: String,
        hostName: String,
        ovpn: String,
    ): SavedOvpn = mutex.withLock {
        val id = UUID.randomUUID().toString()
        val nextOrder = (_saved.value.maxOfOrNull { it.order } ?: -1) + 1
        val entry = SavedOvpn(
            id = id,
            name = name.ifBlank { hostName },
            countryShort = countryShort,
            countryLong = countryLong,
            hostName = hostName,
            order = nextOrder,
        )
        // Write BOTH the bulky text blob and the metadata index (mirrors save writing pw.<id> + index).
        kv.putString(ovpnKey(id), ovpn)
        val next = (_saved.value + entry).sortedBy { it.order }
        persistMetadata(next)
        _saved.value = next
        entry
    }

    override suspend fun delete(id: String) = mutex.withLock {
        if (_saved.value.none { it.id == id }) return@withLock
        // Remove BOTH the text blob and the metadata row.
        kv.remove(ovpnKey(id))
        val next = _saved.value.filterNot { it.id == id }
        persistMetadata(next)
        _saved.value = next
    }

    override suspend fun reorder(orderedIds: List<String>) = mutex.withLock {
        val byId = _saved.value.associateBy { it.id }
        val listed = orderedIds.mapNotNull { byId[it] }
        val rest = _saved.value.filter { it.id !in orderedIds }
        val reordered = (listed + rest).mapIndexed { index, p -> p.copy(order = index) }
        persistMetadata(reordered)
        _saved.value = reordered
    }

    override suspend fun resolve(id: String): String? = mutex.withLock {
        if (_saved.value.none { it.id == id }) return@withLock null
        kv.getString(ovpnKey(id))
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    private fun persistMetadata(list: List<SavedOvpn>) {
        kv.putString(KEY_VAULT, OvpnVaultCodec.encode(list.sortedBy { it.order }))
    }

    private fun ovpnKey(id: String) = "$OVPN_PREFIX$id"

    companion object {
        const val PREFS_FILE = "djproxy_ovpn_vault"
        const val KEY_VAULT = "ovpnvault.v1"
        const val OVPN_PREFIX = "ovpn."

        /** Build the production vault bound to the private `djproxy_ovpn_vault` prefs file. */
        fun fromContext(context: Context): SharedPreferencesOvpnVault {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            return SharedPreferencesOvpnVault(SharedPreferencesKeyValueStore(prefs))
        }
    }
}
