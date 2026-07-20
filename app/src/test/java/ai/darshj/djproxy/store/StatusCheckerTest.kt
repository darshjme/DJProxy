package ai.darshj.djproxy.store

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.proxy.ProxyError
import ai.darshj.djproxy.proxy.SocketProtector
import ai.darshj.djproxy.proxy.ValidationResult
import ai.darshj.djproxy.proxy.Validator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * State-machine + concurrency + battery-policy tests for [ValidatorStatusChecker] against a fake
 * [Validator] — no sockets, no device, JVM-only. Confirms Success→Reachable, every ProxyError→
 * Unreachable (verbatim message/hint), Checking transition, bounded concurrency, and the stale /
 * debounce / power-save auto policy.
 */
class StatusCheckerTest {

    private val noopProtector = SocketProtector { true }

    private class FakeValidator(
        val onValidate: suspend (ProxyConfig) -> ValidationResult,
    ) : Validator {
        override suspend fun validate(config: ProxyConfig): ValidationResult = onValidate(config)
    }

    private fun checker(
        validator: Validator,
        maxConcurrency: Int = 6,
        powerSave: Boolean = false,
        clock: () -> Long = { 1_000L },
    ) = ValidatorStatusChecker(
        validatorFactory = { validator },
        protector = noopProtector,
        maxConcurrency = maxConcurrency,
        powerSaveMode = { powerSave },
        clock = clock,
    )

    private val cfg = ProxyConfig(host = "1.2.3.4", port = 1080)

    @Test
    fun `success maps to reachable with latency and exit ip`() = runBlocking {
        val v = FakeValidator { ValidationResult.Success(latencyMs = 142, probeStatus = 204, exitIp = "9.9.9.9") }
        val checker = checker(v, clock = { 5_000L })
        val status = checker.check("k", cfg)
        assertTrue(status is ProxyStatus.Reachable)
        status as ProxyStatus.Reachable
        assertEquals(142, status.latencyMs)
        assertEquals("9.9.9.9", status.exitIp)
        assertEquals(5_000L, status.checkedAt)
        assertEquals(status, checker.statuses.value["k"])
    }

    @Test
    fun `each proxy error maps to unreachable with verbatim reason and hint`() = runBlocking {
        val errors = listOf(
            ProxyError.DnsResolutionFailed("bad.host"),
            ProxyError.ConnectionRefused("1.2.3.4", 1080),
            ProxyError.Timeout("connect"),
            ProxyError.AuthRejected,
            ProxyError.NotASocks5Server,
            ProxyError.HttpStatus(502, "Bad Gateway"),
            ProxyError.ConnectRefusedByProxy("1.2.3.4", 80),
            ProxyError.HandshakeMalformed("truncated"),
            ProxyError.ProbeFailed("dead upstream"),
            ProxyError.Io("reset"),
        )
        for (err in errors) {
            val checker = checker(FakeValidator { ValidationResult.Failure(err) })
            val status = checker.check("k", cfg)
            assertTrue("expected Unreachable for $err", status is ProxyStatus.Unreachable)
            status as ProxyStatus.Unreachable
            assertEquals(err.message, status.reason)
            assertEquals(err.hint, status.hint)
        }
    }

    @Test
    fun `check publishes Checking before result`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val observed = mutableListOf<ProxyStatus?>()
        val v = FakeValidator {
            gate.await()
            ValidationResult.Success(1, 204, null)
        }
        val checker = checker(v)
        val job = launch { checker.check("k", cfg) }
        // Let the coroutine run up to the suspended validator.
        while (checker.statuses.value["k"] == null) delay(1)
        observed.add(checker.statuses.value["k"])
        gate.complete(Unit)
        job.join()
        observed.add(checker.statuses.value["k"])
        assertEquals(ProxyStatus.Checking, observed[0])
        assertTrue(observed[1] is ProxyStatus.Reachable)
    }

    @Test
    fun `checkAll respects bounded concurrency`() = runBlocking {
        val live = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val v = FakeValidator {
            val now = live.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(40)
            live.decrementAndGet()
            ValidationResult.Success(1, 204, null)
        }
        val checker = checker(v, maxConcurrency = 2)
        val targets = (1..8).map { "k$it" to cfg }
        checker.checkAll(targets)
        assertTrue("peak concurrency ${peak.get()} exceeded 2", peak.get() <= 2)
        assertEquals(8, checker.statuses.value.size)
        assertTrue(checker.statuses.value.values.all { it is ProxyStatus.Reachable })
    }

    @Test
    fun `checkStale skips fresh entries and re-checks stale ones`() = runBlocking {
        var t = 1_000_000L
        val calls = AtomicInteger(0)
        val v = FakeValidator { calls.incrementAndGet(); ValidationResult.Success(1, 204, null) }
        val checker = ValidatorStatusChecker(
            validatorFactory = { v },
            protector = noopProtector,
            staleMs = 5 * 60 * 1000L,
            minIntervalMs = 0L,
            clock = { t },
        )
        // First pass: both unknown → both checked.
        val scheduled1 = checker.checkStale(listOf("a" to cfg, "b" to cfg))
        assertEquals(setOf("a", "b"), scheduled1.toSet())
        assertEquals(2, calls.get())
        // Immediately: both fresh → none re-checked.
        val scheduled2 = checker.checkStale(listOf("a" to cfg, "b" to cfg))
        assertTrue(scheduled2.isEmpty())
        // Advance beyond staleMs → both due again.
        t += 6 * 60 * 1000L
        val scheduled3 = checker.checkStale(listOf("a" to cfg, "b" to cfg))
        assertEquals(setOf("a", "b"), scheduled3.toSet())
    }

    @Test
    fun `checkStale is skipped under power save but manual check still works`() = runBlocking {
        val calls = AtomicInteger(0)
        val v = FakeValidator { calls.incrementAndGet(); ValidationResult.Success(1, 204, null) }
        val checker = checker(v, powerSave = true)
        assertTrue(checker.checkStale(listOf("a" to cfg)).isEmpty())
        assertEquals(0, calls.get())
        // Manual check bypasses the power-save guard.
        checker.check("a", cfg)
        assertEquals(1, calls.get())
    }

    @Test
    fun `per-key debounce blocks rapid auto re-checks`() = runBlocking {
        val calls = AtomicInteger(0)
        val v = FakeValidator { calls.incrementAndGet(); ValidationResult.Failure(ProxyError.Timeout("connect")) }
        var t = 1_000L
        val checker = ValidatorStatusChecker(
            validatorFactory = { v },
            protector = noopProtector,
            staleMs = 0L,             // always stale by age…
            minIntervalMs = 60_000L,  // …but debounced by min interval
            clock = { t },
        )
        checker.checkStale(listOf("a" to cfg))
        assertEquals(1, calls.get())
        t += 1_000L // within debounce window
        assertTrue(checker.checkStale(listOf("a" to cfg)).isEmpty())
        assertEquals(1, calls.get())
        t += 60_000L // past debounce
        checker.checkStale(listOf("a" to cfg))
        assertEquals(2, calls.get())
    }

    @Test
    fun `retain drops stale keys from the status map`() = runBlocking {
        val v = FakeValidator { ValidationResult.Success(1, 204, null) }
        val checker = checker(v)
        checker.check("keep", cfg)
        checker.check("drop", cfg)
        assertEquals(setOf("keep", "drop"), checker.statuses.value.keys)
        checker.retain(setOf("keep"))
        assertEquals(setOf("keep"), checker.statuses.value.keys)
    }
}
