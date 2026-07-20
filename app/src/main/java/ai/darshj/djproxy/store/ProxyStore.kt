package ai.darshj.djproxy.store

import android.content.Context
import android.content.SharedPreferences
import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.vpn.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The vault contract — save / list / reuse / edit / delete / reorder / default for proxies.
 *
 * The password is the only secret: it is persisted **only** as ciphertext via the existing
 * AES-256-GCM AndroidKeyStore path ([vpn.CredentialStore]) and is materialised on demand by
 * [resolve] right before an apply. Everything else (host/port/type/name/username/dns) is plain
 * metadata in a hand-serialised prefs blob ([VaultCodec]) so the vault is zero-dependency and
 * fully unit-testable.
 *
 * Reuse funnels through the UNCHANGED `VpnController.apply(ProxyConfig)` path: [resolve] simply
 * reconstructs the full [ProxyConfig]; it adds no connect/dial logic of its own.
 */
interface ProxyStore {

    /** Reactive vault, already ordered by [SavedProxy.order]; the default entry is flagged. */
    val proxies: StateFlow<List<SavedProxy>>

    /** The default entry id to preselect on the hero, or null. */
    val defaultId: StateFlow<String?>

    /**
     * Insert a new saved proxy (assigns a UUID + appends to the order). A non-empty
     * [ProxyConfig.password] is encrypted and stored as ciphertext; the returned [SavedProxy] never
     * carries the plaintext.
     */
    suspend fun save(
        name: String,
        config: ProxyConfig,
        origin: ProxyOrigin = ProxyOrigin.USER,
    ): SavedProxy

    /**
     * Update the metadata of an existing entry. If [config] carries a non-empty password it is
     * re-encrypted and overwrites the stored blob; an empty password clears any stored blob (the
     * user removed it). No-op if [id] is unknown.
     */
    suspend fun update(id: String, name: String, config: ProxyConfig)

    /** Remove an entry and its ciphertext blob; clears the default if it pointed at [id]. */
    suspend fun delete(id: String)

    /** Persist a new ordering. Ids not present keep their relative order after the listed ones. */
    suspend fun reorder(orderedIds: List<String>)

    /** Set (or clear, with null) the default entry. Unknown ids are ignored. */
    suspend fun setDefault(id: String?)

    /**
     * Reconstruct the FULL [ProxyConfig] incl. the decrypted password for a reuse/apply. Returns
     * null if the entry is gone. If a saved password blob cannot be decrypted (Keystore dropped /
     * API 21-22), the config is returned with an **empty** password — fail-closed: a dropped key
     * never becomes plaintext, and the subsequent pre-flight surfaces AuthRejected honestly.
     */
    suspend fun resolve(id: String): ProxyConfig?
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Persistence seams — abstracted so the store is unit-testable off-device. The android-backed
// impls below are thin adapters; tests inject in-memory fakes (see ProxyStoreTest).
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** Minimal string key-value backend (a SharedPreferences shape) the store persists through. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

/**
 * At-rest secret protection seam. The android impl delegates to the frozen [vpn.CredentialStore]
 * (AES-256-GCM, AndroidKeyStore) WITHOUT editing it; tests inject a reversible fake. Returning null
 * from [encrypt] means "not persisted" (never plaintext) — the store degrades to hasPassword=false.
 */
interface SecretCipher {
    fun encrypt(plaintext: String): String?
    fun decrypt(blob: String): String?
}

/** Reuses the existing module-visible [vpn.CredentialStore] object verbatim — no core edit. */
object CredentialStoreCipher : SecretCipher {
    override fun encrypt(plaintext: String): String? = CredentialStore.encrypt(plaintext)
    override fun decrypt(blob: String): String? = CredentialStore.decrypt(blob)
}

/** SharedPreferences-backed [KeyValueStore] over the private `djproxy_vault` file. */
class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Implementation
// ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The single-writer, single-process vault implementation. Metadata → [KeyValueStore] under
 * [KEY_VAULT]; the default id under [KEY_DEFAULT]; each password ciphertext under `pw.<id>`.
 * All mutations are serialised with a [Mutex] and re-emit the reactive [proxies] / [defaultId].
 */
class SharedPreferencesProxyStore(
    private val kv: KeyValueStore,
    private val cipher: SecretCipher = CredentialStoreCipher,
) : ProxyStore {

    private val mutex = Mutex()

    private val _proxies = MutableStateFlow<List<SavedProxy>>(emptyList())
    override val proxies: StateFlow<List<SavedProxy>> = _proxies.asStateFlow()

    private val _defaultId = MutableStateFlow<String?>(null)
    override val defaultId: StateFlow<String?> = _defaultId.asStateFlow()

    init {
        // Synchronous initial load from persistence — cheap (tens of rows), no I/O beyond prefs.
        _defaultId.value = kv.getString(KEY_DEFAULT)?.ifEmpty { null }
        _proxies.value = readAndOverlay()
    }

    override suspend fun save(name: String, config: ProxyConfig, origin: ProxyOrigin): SavedProxy =
        mutex.withLock {
            val id = UUID.randomUUID().toString()
            val nextOrder = (_proxies.value.maxOfOrNull { it.order } ?: -1) + 1

            val stored = persistPassword(id, config.password)
            val entry = SavedProxy.fromConfig(
                id = id,
                name = name,
                config = config,
                origin = origin,
                isDefault = false,
                order = nextOrder,
                hasPassword = stored,
            )

            val next = (_proxies.value + entry).sortedBy { it.order }
            persistMetadata(next)
            _proxies.value = overlay(next)
            _proxies.value.first { it.id == id }
        }

    override suspend fun update(id: String, name: String, config: ProxyConfig) = mutex.withLock {
        val existing = _proxies.value.firstOrNull { it.id == id } ?: return@withLock
        // Empty password on update means "clear the stored secret"; a non-empty one re-encrypts.
        val stored = if (config.password.isNotEmpty()) {
            persistPassword(id, config.password)
        } else {
            kv.remove(pwKey(id))
            false
        }
        val updated = SavedProxy.fromConfig(
            id = id,
            name = name,
            config = config,
            origin = existing.origin,
            isDefault = existing.isDefault,
            order = existing.order,
            hasPassword = stored,
        )
        val next = _proxies.value.map { if (it.id == id) updated else it }.sortedBy { it.order }
        persistMetadata(next)
        _proxies.value = overlay(next)
    }

    override suspend fun delete(id: String) = mutex.withLock {
        if (_proxies.value.none { it.id == id }) return@withLock
        kv.remove(pwKey(id))
        val next = _proxies.value.filterNot { it.id == id }
        persistMetadata(next)
        if (_defaultId.value == id) {
            kv.putString(KEY_DEFAULT, "")
            _defaultId.value = null
        }
        _proxies.value = overlay(next)
    }

    override suspend fun reorder(orderedIds: List<String>) = mutex.withLock {
        val byId = _proxies.value.associateBy { it.id }
        val listed = orderedIds.mapNotNull { byId[it] }
        val rest = _proxies.value.filter { it.id !in orderedIds }
        val reordered = (listed + rest).mapIndexed { index, p -> p.copy(order = index) }
        persistMetadata(reordered)
        _proxies.value = overlay(reordered)
    }

    override suspend fun setDefault(id: String?) = mutex.withLock {
        if (id != null && _proxies.value.none { it.id == id }) return@withLock
        kv.putString(KEY_DEFAULT, id ?: "")
        _defaultId.value = id
        _proxies.value = overlay(_proxies.value)
    }

    override suspend fun resolve(id: String): ProxyConfig? = mutex.withLock {
        val entry = _proxies.value.firstOrNull { it.id == id } ?: return@withLock null
        val password = kv.getString(pwKey(id))?.let { cipher.decrypt(it) } ?: ""
        entry.toConfig(password)
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    /** Encrypt + persist a non-empty password; returns true iff a ciphertext blob was stored. */
    private fun persistPassword(id: String, password: String): Boolean {
        if (password.isEmpty()) {
            kv.remove(pwKey(id))
            return false
        }
        val blob = cipher.encrypt(password)
        return if (blob != null) {
            kv.putString(pwKey(id), blob)
            true
        } else {
            // API 21-22 / Keystore unavailable: never store plaintext. Session-only password.
            kv.remove(pwKey(id))
            false
        }
    }

    private fun persistMetadata(list: List<SavedProxy>) {
        kv.putString(KEY_VAULT, VaultCodec.encode(list.sortedBy { it.order }))
    }

    /** Read the persisted blob and overlay derived flags (hasPassword, isDefault). */
    private fun readAndOverlay(): List<SavedProxy> = overlay(VaultCodec.decode(kv.getString(KEY_VAULT)))

    /** Recompute derived flags from the current persistence + default id, keeping order. */
    private fun overlay(list: List<SavedProxy>): List<SavedProxy> {
        val default = _defaultId.value
        val presentKeys = kv.keys()
        return list.sortedBy { it.order }.map { p ->
            p.copy(
                hasPassword = pwKey(p.id) in presentKeys,
                isDefault = p.id == default,
            )
        }
    }

    private fun pwKey(id: String) = "$PW_PREFIX$id"

    companion object {
        const val PREFS_FILE = "djproxy_vault"
        const val KEY_VAULT = "vault.v1"
        const val KEY_DEFAULT = "vault.default"
        const val PW_PREFIX = "pw."

        /** Build the production store bound to the private `djproxy_vault` prefs + Keystore cipher. */
        fun fromContext(context: Context): SharedPreferencesProxyStore {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            return SharedPreferencesProxyStore(SharedPreferencesKeyValueStore(prefs))
        }
    }
}
