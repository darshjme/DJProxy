package ai.darshj.djproxy.diag

import ai.darshj.djproxy.vpn.Health
import ai.darshj.djproxy.vpn.HealthReport
import ai.darshj.djproxy.vpn.seams.CriticalFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load-bearing guarantee of this lane: no credential can survive into a report body. These are
 * pure (no Android APIs), exercising [ReportBuilder.redact] and [ReportBuilder.assemble] directly.
 */
class ReportBuilderRedactionTest {

    private val env = DiagEnv(
        appId = "ai.darshj.djproxy",
        appVersion = "1.0.0 (1)",
        deviceModel = "Pixel-Test",
        manufacturer = "Google",
        androidRelease = "16",
        sdkInt = 35,
        abis = listOf("arm64-v8a"),
    )

    private val PASSWORD = "SuperSecretP@ss123"
    private val USERNAME = "bob_the_user"

    @Test
    fun `literal password never survives redaction`() {
        val raw = "auth line user=$USERNAME pass=$PASSWORD trailing"
        val out = ReportBuilder.redact(raw, listOf(PASSWORD, USERNAME))
        assertFalse("password leaked", out.contains(PASSWORD))
        assertFalse("username leaked", out.contains(USERNAME))
    }

    @Test
    fun `url userinfo is stripped even when secret is unknown`() {
        val raw = "dialing socks5://alice:hunter2@1.2.3.4:1080 now"
        val out = ReportBuilder.redact(raw, emptyList())
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("alice:hunter2"))
        assertTrue(out.contains("[REDACTED]@1.2.3.4:1080"))
    }

    @Test
    fun `keyed credentials are stripped without a known secret list`() {
        val raw = "token: abc.def.ghi\npassword=zzz\nAuthorization: Bearer XYZ"
        val out = ReportBuilder.redact(raw, emptyList())
        assertFalse(out.contains("abc.def.ghi"))
        assertFalse(out.contains("zzz"))
        assertFalse(out.contains("Bearer XYZ"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun `assembled body contains device info and failure but no password`() {
        val failure = CriticalFailure(
            category = CriticalFailure.Category.ENGINE_DEATH,
            reason = "engine exited; last config used pass=$PASSWORD",
            timeMs = 1_700_000_000_000L,
        )
        // Password planted in EVERY plausible place: reason, a log line, and a URL.
        val logs = listOf(
            "2026-01-01 00:00:00.000 I/proxy: connecting socks5://$USERNAME:$PASSWORD@9.9.9.9:1080",
            "2026-01-01 00:00:01.000 E/engine: died",
        )
        val report = ReportBuilder.assemble(
            env = env,
            failure = failure,
            proxyRedacted = "socks5://•••@9.9.9.9:1080",
            stage = "RECONNECTING",
            errorText = "Io: something with pass=$PASSWORD",
            health = HealthReport(
                ipv6 = Health.OK, udp = Health.OK, dns = Health.DEGRADED,
                activeDnsStrategy = "DoH:443", checkedAtMs = 1_700_000_001_000L,
            ),
            stats = "up=1B down=2B",
            logLines = logs,
            secrets = listOf(PASSWORD, USERNAME),
            nowMs = 1_700_000_002_000L,
        )

        // The guarantee.
        assertFalse("password leaked into body", report.body.contains(PASSWORD))
        assertFalse("username leaked into body", report.body.contains(USERNAME))
        assertFalse("password leaked into subject", report.subject.contains(PASSWORD))

        // Useful, non-sensitive content is present.
        assertTrue(report.body.contains("Pixel-Test"))
        assertTrue(report.body.contains("API 35"))
        assertTrue(report.body.contains("ENGINE_DEATH"))
        assertTrue(report.body.contains("DoH:443"))
        assertTrue(report.body.contains("[REDACTED]"))
        assertEquals(CriticalFailure.Category.ENGINE_DEATH, report.category)
    }

    @Test
    fun `blank secrets do not blank the whole document`() {
        val raw = "harmless text with spaces"
        // A blank/empty secret must be ignored, not turned into a match-everything replace.
        val out = ReportBuilder.redact(raw, listOf("", "  "))
        assertEquals(raw, out)
    }
}
