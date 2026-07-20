package ai.darshj.djproxy.store

/**
 * Provenance of a saved proxy. Drives the "public" badge and the untrusted-server consent gate:
 * a [FREE_PUBLIC] entry came from the community free-list and must never be treated as trusted.
 *
 * Persisted by [VaultCodec] as the enum [name] so it survives a hand-serialised prefs blob with
 * zero JSON dependency (fully unit-testable under `unitTests.isReturnDefaultValues=true`).
 */
enum class ProxyOrigin {
    /** Entered/edited by the user, or imported from a config/subscription. */
    USER,

    /** Pulled from a maintained free public proxy list — unvetted, treat as untrusted. */
    FREE_PUBLIC;

    companion object {
        /** Lenient parse used by the codec; unknown/blank degrades to [USER] (never crashes a load). */
        fun fromName(raw: String): ProxyOrigin =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: USER
    }
}
