package ai.darshj.djproxy.diag

import ai.darshj.djproxy.vpn.seams.CriticalFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the framework-free surface of the mail path: the fixed recipient, the mailto URI, the
 * subject derivation, and — critically — that the [DiagnosticReport] handed to the composer carries
 * no credential. (The Intent object itself is Android-framework code exercised on-device; here we
 * assert the pure inputs, which is where a leak or wrong-recipient bug would actually live.)
 */
class MailIntentFactoryTest {

    private val env = DiagEnv(
        appId = "ai.darshj.djproxy",
        appVersion = "1.0.0 (1)",
        deviceModel = "Fold7",
        manufacturer = "Samsung",
        androidRelease = "16",
        sdkInt = 35,
        abis = listOf("arm64-v8a", "x86_64"),
    )

    @Test
    fun `recipient is the owner inbox`() {
        assertEquals("darshjme@gmail.com", MailIntentFactory.RECIPIENT)
        assertEquals("mailto:darshjme@gmail.com", MailIntentFactory.mailtoUri())
    }

    @Test
    fun `subject reflects the report`() {
        val report = ReportBuilder.assemble(
            env = env,
            failure = CriticalFailure(CriticalFailure.Category.UNCAUGHT, "boom"),
            proxyRedacted = "socks5://•••@1.1.1.1:1080",
            stage = "ERROR",
            errorText = null,
            health = null,
            stats = null,
            logLines = emptyList(),
            secrets = emptyList(),
            nowMs = 1_700_000_000_000L,
        )
        assertEquals(report.subject, MailIntentFactory.subjectFor(report))
        assertTrue(report.subject.contains("UNCAUGHT"))
        assertTrue(report.subject.contains("Fold7"))
    }

    @Test
    fun `report body handed to the mail path never contains a credential`() {
        val password = "N0t-In-Email!"
        val username = "carol"
        val report = ReportBuilder.assemble(
            env = env,
            failure = CriticalFailure(CriticalFailure.Category.BRINGUP_FAILED, "auth pass=$password"),
            proxyRedacted = "socks5://•••@1.1.1.1:1080",
            stage = "IDLE",
            errorText = "user=$username",
            health = null,
            stats = null,
            logLines = listOf("http://$username:$password@1.1.1.1"),
            secrets = listOf(password, username),
            nowMs = 1_700_000_000_000L,
        )
        // This is exactly what MailIntentFactory puts in EXTRA_TEXT / EXTRA_SUBJECT.
        assertFalse(MailIntentFactory.subjectFor(report).contains(password))
        assertFalse(report.body.contains(password))
        assertFalse(report.body.contains(username))
    }
}
