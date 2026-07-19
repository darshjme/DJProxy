package ai.darshj.djproxy.vpn.seams

/**
 * Seam (§9.4): the diagnostics lane implements this to turn a critical failure into a mailto report.
 * Core calls it (always wrapped in runCatching). Implementations MUST NOT throw and MUST return fast
 * (post the heavy work to their own scope) — core may call this from bring-up or an uncaught handler.
 */
interface CriticalFailureSink {
    fun onCriticalFailure(failure: CriticalFailure)
}

data class CriticalFailure(
    val category: Category,
    val reason: String,
    val timeMs: Long = System.currentTimeMillis(),
) {
    /**
     * SELF_TEST is retained for taxonomy though it is NO LONGER a hard-fail (leak checks are
     * advisory in v3); it is reported only as an FYI category if a lane chooses to surface it.
     */
    enum class Category { SELF_TEST, ENGINE_DEATH, UNCAUGHT, BRINGUP_FAILED }
}
