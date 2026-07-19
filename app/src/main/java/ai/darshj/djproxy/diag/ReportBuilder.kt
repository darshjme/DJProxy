package ai.darshj.djproxy.diag

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.vpn.CrashCatcher
import ai.darshj.djproxy.vpn.HealthReport
import ai.darshj.djproxy.vpn.LogBus
import ai.darshj.djproxy.vpn.LogEvent
import ai.darshj.djproxy.vpn.VpnRuntime
import ai.darshj.djproxy.vpn.VpnState
import ai.darshj.djproxy.vpn.seams.CriticalFailure
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The finished, ready-to-send diagnostic report. [body] is ALREADY redacted — no credential can
 * survive into it (see [ReportBuilder.redact]). Nothing outside this lane constructs one directly;
 * it is produced by [ReportBuilder.assemble] / [ReportBuilder.collect].
 */
data class DiagnosticReport(
    val subject: String,
    val body: String,
    val generatedAtMs: Long,
    /** Null for a user-initiated ("Send diagnostic report") report; set for a critical failure. */
    val category: CriticalFailure.Category?,
)

/** Context-free snapshot of the build/device so [ReportBuilder.assemble] stays pure & testable. */
data class DiagEnv(
    val appId: String,
    val appVersion: String,
    val deviceModel: String,
    val manufacturer: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: List<String>,
) {
    companion object {
        /** Reads the real build/device facts. Never throws — falls back to "unknown" on any miss. */
        fun of(context: Context): DiagEnv {
            val app = context.applicationContext
            val (name, code) = runCatching {
                val pm = app.packageManager
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(app.packageName, 0)
                val vc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION") info.versionCode.toLong()
                }
                (info.versionName ?: "?") to vc
            }.getOrDefault("?" to 0L)
            return DiagEnv(
                appId = app.packageName ?: "ai.darshj.djproxy",
                appVersion = "$name ($code)",
                deviceModel = Build.MODEL ?: "unknown",
                manufacturer = Build.MANUFACTURER ?: "unknown",
                androidRelease = Build.VERSION.RELEASE ?: "unknown",
                sdkInt = Build.VERSION.SDK_INT,
                abis = (Build.SUPPORTED_ABIS ?: emptyArray()).toList(),
            )
        }
    }
}

/**
 * Assembles the diagnostic text and — the load-bearing part — GUARANTEES no credential survives.
 *
 * Redaction is defence-in-depth: the [LogBus] contract already forbids secrets in log messages and
 * [ProxyConfig.redacted] never reveals the password/username, but we still scrub the whole finished
 * body so that even an accidental leak upstream cannot escape into an e-mail. Three passes:
 *   1. literal removal of the known secret strings (the live proxy password + username), then
 *   2. URL user-info stripping (`scheme://user:pass@host` → `scheme://[REDACTED]@host`), then
 *   3. `key=value` credential stripping (`password=…`, `token:…`, …) for unknown secrets.
 */
object ReportBuilder {

    private const val RED = "[REDACTED]"

    // scheme://userinfo@host  →  the whole `userinfo@` (which may embed a password) is dropped.
    private val URL_USERINFO = Regex("(?i)([a-z][a-z0-9+.\\-]*://)[^/@\\s]+@")

    // key <sep> value, where key is any credential-ish word. Captures key(1) sep(2) value(3).
    private val KEYED_SECRET = Regex(
        "(?i)\\b(password|passwd|pwd|pass|secret|token|api[_-]?key|apikey|auth|authorization|credential)\\b(\\s*[=:]\\s*)(\\S+)",
    )

    private val TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        .apply { timeZone = TimeZone.getDefault() }

    /**
     * Pure redaction over arbitrary text. Public so the unit tests can hammer it directly.
     * [secrets] are literal values (e.g. the proxy password) removed verbatim wherever they appear.
     */
    fun redact(raw: String, secrets: Collection<String>): String {
        var s = raw
        // Pass 1: literal secrets. Longest-first so a password that contains the username is fully
        // scrubbed before the shorter substring is touched.
        secrets.asSequence()
            .filter { it.isNotBlank() && it.length >= 1 }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { secret -> s = s.replace(secret, RED) }
        // Pass 2: URL user-info (catches `socks5://user:pass@host` even if the value was unknown).
        s = URL_USERINFO.replace(s) { m -> "${m.groupValues[1]}$RED@" }
        // Pass 3: keyed credentials (`pass=...`, `token: ...`).
        s = KEYED_SECRET.replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]}$RED" }
        return s
    }

    /**
     * Pure assembly: given already-gathered inputs, renders the report and applies [redact] to the
     * WHOLE body. No Android APIs touched, so the redaction contract is fully unit-testable.
     */
    fun assemble(
        env: DiagEnv,
        failure: CriticalFailure?,
        proxyRedacted: String?,
        stage: String,
        errorText: String?,
        health: HealthReport?,
        stats: String?,
        logLines: List<String>,
        secrets: Collection<String>,
        nativeCrash: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): DiagnosticReport {
        val sb = StringBuilder(4096)
        sb.appendLine("DJProxy diagnostic report")
        sb.appendLine("Generated: ${TS.format(Date(nowMs))}")
        sb.appendLine()

        sb.appendLine("== Failure ==")
        if (failure != null) {
            sb.appendLine("Category : ${failure.category}")
            sb.appendLine("Reason   : ${failure.reason}")
            sb.appendLine("At       : ${TS.format(Date(failure.timeMs))}")
        } else {
            sb.appendLine("Category : (user-initiated report — no failure)")
        }
        sb.appendLine()

        sb.appendLine("== App / device ==")
        sb.appendLine("App      : ${env.appId} ${env.appVersion}")
        sb.appendLine("Device   : ${env.manufacturer} ${env.deviceModel}")
        sb.appendLine("Android  : ${env.androidRelease} (API ${env.sdkInt})")
        sb.appendLine("ABIs     : ${env.abis.joinToString(", ").ifBlank { "unknown" }}")
        sb.appendLine()

        sb.appendLine("== Tunnel ==")
        sb.appendLine("Stage    : $stage")
        sb.appendLine("Proxy    : ${proxyRedacted ?: "(none applied)"}") // ProxyConfig.redacted(): never the password
        sb.appendLine("Error    : ${errorText ?: "(none)"}")
        sb.appendLine("Stats    : ${stats ?: "(n/a)"}")
        sb.appendLine()

        sb.appendLine("== Advisory health ==")
        // NOTE: v3 leak checks are advisory (HealthReport). The legacy LeakCheckReport is deprecated
        // and core keeps VpnState.leakChecks null, so we surface the real advisory data instead and
        // say so honestly rather than printing a fabricated pass/fail.
        if (health != null) {
            sb.appendLine("IPv6     : ${health.ipv6} (OK = blackholed)")
            sb.appendLine("UDP      : ${health.udp} (OK = dropped)")
            sb.appendLine("DNS      : ${health.dns} via ${health.activeDnsStrategy.ifBlank { "?" }}")
            sb.appendLine("EmuBypass: ${health.emulatorBypassSuspected}")
            sb.appendLine("Checked  : ${if (health.checkedAtMs > 0) TS.format(Date(health.checkedAtMs)) else "(not yet)"}")
        } else {
            sb.appendLine("(no post-connect health snapshot — tunnel never reached CONNECTED)")
        }
        sb.appendLine()

        if (!nativeCrash.isNullOrBlank()) {
            sb.appendLine("== Last captured crash (CrashCatcher) ==")
            sb.appendLine(nativeCrash.trim())
            sb.appendLine()
        }

        sb.appendLine("== Recent log (last ${logLines.size} lines) ==")
        if (logLines.isEmpty()) {
            sb.appendLine("(log buffer empty)")
        } else {
            logLines.forEach { sb.appendLine(it) }
        }

        val subject = buildString {
            append("DJProxy diagnostic")
            if (failure != null) append(" — ${failure.category}")
            append(" — ${env.deviceModel} / API ${env.sdkInt}")
        }

        // The one guarantee: redact the ENTIRE assembled body (subject is credential-free by
        // construction but we scrub it too, for symmetry).
        return DiagnosticReport(
            subject = redact(subject, secrets),
            body = redact(sb.toString(), secrets),
            generatedAtMs = nowMs,
            category = failure?.category,
        )
    }

    /** Formats a [LogBus] event exactly as it appears in the report's log section. */
    fun formatLog(e: LogEvent): String {
        val lvl = e.level.name.first() // D/I/W/E
        return "${TS.format(Date(e.timeMs))} $lvl/${e.tag}: ${e.message}"
    }

    /**
     * Gathers everything from the live process (Android build facts, [VpnRuntime] state, the
     * [LogBus] replay buffer) and produces a finished, redacted [DiagnosticReport]. Never throws.
     */
    fun collect(context: Context, failure: CriticalFailure?): DiagnosticReport {
        val env = runCatching { DiagEnv.of(context) }.getOrDefault(
            DiagEnv("ai.darshj.djproxy", "?", "unknown", "unknown", "unknown", 0, emptyList()),
        )
        val state: VpnState = runCatching { VpnRuntime.state.value }.getOrDefault(VpnState.IDLE)
        val config: ProxyConfig? = runCatching { VpnRuntime.currentConfig }.getOrNull()
        val health: HealthReport? = state.health ?: runCatching { VpnRuntime.lastHealthReport }.getOrNull()

        // The live secrets we scrub verbatim. Password ALWAYS; username too (never leak half a pair).
        val secrets = buildList {
            config?.password?.takeIf { it.isNotBlank() }?.let { add(it) }
            config?.username?.takeIf { it.isNotBlank() }?.let { add(it) }
        }

        val logLines = runCatching {
            LogBus.events.replayCache.map { formatLog(it) }
        }.getOrDefault(emptyList())

        // Pull in the last uncaught/native stack the CrashCatcher persisted (owner-requested #3), then
        // delete it so a stale crash is not attached to an unrelated future report. Redacted with the
        // rest of the body below for defence in depth.
        val nativeCrash = runCatching {
            val f = File(context.filesDir, CrashCatcher.CRASH_FILE)
            if (f.exists()) f.readText().also { runCatching { f.delete() } } else null
        }.getOrNull()

        val stats = runCatching {
            val s = state.stats
            "up=${s.bytesUp}B down=${s.bytesDown}B conns=${s.activeConnections}/${s.totalConnections} " +
                "udpDropped=${s.udpDropped} dns=${s.dnsQueries}"
        }.getOrNull()

        return assemble(
            env = env,
            failure = failure,
            proxyRedacted = state.proxyRedacted ?: config?.redacted(),
            stage = state.stage.name,
            errorText = state.error?.let { "${it::class.simpleName}: ${it.message}" },
            health = health,
            stats = stats,
            logLines = logLines,
            secrets = secrets,
            nativeCrash = nativeCrash,
        )
    }
}
