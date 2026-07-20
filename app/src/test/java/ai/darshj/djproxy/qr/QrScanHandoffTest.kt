package ai.darshj.djproxy.qr

import ai.darshj.djproxy.core.ProxyConfig
import ai.darshj.djproxy.core.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode -> parse handoff (DESIGN_V4 §4/§13), tested with a **fake decoder** and a **fake parser** —
 * no CameraX, no ZXing, no device. Proves the qr lane correctly turns a decoded QR string into the
 * downstream result (a `ProxyConfig` or a typed error), and that it degrades honestly when the frame
 * has no code or the decoder blows up.
 *
 * In production the injected parser is the config lane's `ConfigImporter::import`; here it is a stand-in
 * that mirrors that contract (SOCKS5 URI -> config, anything else -> typed error).
 */
class QrScanHandoffTest {

    /** A fake parser result mirroring the config lane's `ImportResult` shape (Single | Rejected). */
    private sealed interface FakeParse {
        data class Config(val cfg: ProxyConfig) : FakeParse
        data class Rejected(val reason: String) : FakeParse
    }

    /** Stand-in for `ConfigImporter::import`: accepts a socks5 URI, rejects everything else. */
    private val fakeParser: (String) -> FakeParse = { raw ->
        val m = Regex("""socks5://(?:([^:@]+):([^@]*)@)?([^:/]+):(\d+)""").find(raw)
        if (m != null) {
            FakeParse.Config(
                ProxyConfig(
                    type = ProxyType.SOCKS5,
                    username = m.groupValues[1],
                    password = m.groupValues[2],
                    host = m.groupValues[3],
                    port = m.groupValues[4].toInt(),
                ),
            )
        } else {
            FakeParse.Rejected("unrecognised: $raw")
        }
    }

    private val anyFrame = LumaFrame(data = ByteArray(4), width = 2, height = 2, rowStride = 2)

    @Test
    fun decoded_qr_is_parsed_into_a_proxy_config() {
        val handoff = QrScanHandoff(
            decoder = { "socks5://alice:s3cr3t@198.51.100.7:1080" },
            parse = fakeParser,
        )

        val outcome = handoff.scan(anyFrame)

        assertTrue(outcome is QrScanOutcome.Decoded)
        val decoded = outcome as QrScanOutcome.Decoded
        assertEquals("socks5://alice:s3cr3t@198.51.100.7:1080", decoded.raw)
        val parsed = decoded.parsed
        assertTrue(parsed is FakeParse.Config)
        val cfg = (parsed as FakeParse.Config).cfg
        assertEquals(ProxyType.SOCKS5, cfg.type)
        assertEquals("198.51.100.7", cfg.host)
        assertEquals(1080, cfg.port)
        assertEquals("alice", cfg.username)
        assertEquals("s3cr3t", cfg.password)
    }

    @Test
    fun decoded_but_unparseable_qr_yields_a_typed_rejection_not_a_crash() {
        val handoff = QrScanHandoff(
            decoder = { "https://example.com/not-a-proxy" },
            parse = fakeParser,
        )

        val outcome = handoff.scan(anyFrame)

        assertTrue(outcome is QrScanOutcome.Decoded)
        val parsed = (outcome as QrScanOutcome.Decoded).parsed
        assertTrue(parsed is FakeParse.Rejected)
    }

    @Test
    fun no_qr_in_frame_reports_NoCodeFound() {
        val handoff = QrScanHandoff(decoder = { null }, parse = fakeParser)
        assertEquals(QrScanOutcome.NoCodeFound, handoff.scan(anyFrame))
    }

    @Test
    fun blank_decode_is_treated_as_no_code() {
        val handoff = QrScanHandoff(decoder = { "   \n " }, parse = fakeParser)
        assertEquals(QrScanOutcome.NoCodeFound, handoff.scan(anyFrame))
    }

    @Test
    fun surrounding_whitespace_is_trimmed_before_parsing() {
        val handoff = QrScanHandoff(
            decoder = { "  socks5://198.51.100.7:1080\n" },
            parse = fakeParser,
        )
        val outcome = handoff.scan(anyFrame) as QrScanOutcome.Decoded
        assertEquals("socks5://198.51.100.7:1080", outcome.raw)
        assertTrue(outcome.parsed is FakeParse.Config)
    }

    @Test
    fun decoder_throwing_is_contained_as_DecodeFailed() {
        val handoff = QrScanHandoff<FakeParse>(
            decoder = { error("camera buffer exploded") },
            parse = fakeParser,
        )
        val outcome = handoff.scan(anyFrame)
        assertTrue(outcome is QrScanOutcome.DecodeFailed)
        assertEquals("camera buffer exploded", (outcome as QrScanOutcome.DecodeFailed).reason)
    }

    @Test
    fun a_misbehaving_parser_that_throws_never_crashes_the_scan() {
        val handoff = QrScanHandoff<FakeParse>(
            decoder = { "socks5://198.51.100.7:1080" },
            parse = { throw RuntimeException("parser bug") },
        )
        val outcome = handoff.scan(anyFrame)
        assertTrue(outcome is QrScanOutcome.DecodeFailed)
        assertEquals("parser bug", (outcome as QrScanOutcome.DecodeFailed).reason)
    }
}
