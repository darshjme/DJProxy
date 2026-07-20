package ai.darshj.djproxy.qr

import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises the **real** ZXing pipeline end-to-end with zero device: encode a payload to a matrix
 * ([QrEncoder.encodeMatrix]), render it to a [LumaFrame], and decode it back with the production
 * [ZxingQrDecoder]. This proves the actual scanner decoder (not a fake) reads what the actual
 * encoder writes — the encode side that the hotspot ShareLan QR depends on, and the decode side that
 * the camera analyzer depends on, verified together.
 *
 * ZXing `core` is pure Java, so this runs as a plain JVM unit test.
 */
class ZxingRoundTripTest {

    private val decoder = ZxingQrDecoder()

    /** Dark module -> luminance 0 (black), light -> 255 (white); rowStride == width (no padding). */
    private fun BitMatrix.toLumaFrame(): LumaFrame {
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                data[row + x] = if (get(x, y)) 0 else 255.toByte()
            }
        }
        return LumaFrame(data = data, width = width, height = height, rowStride = width)
    }

    private fun roundTrip(payload: String, size: Int = 320): String? =
        decoder.decode(QrEncoder.encodeMatrix(payload, size).toLumaFrame())

    @Test
    fun socks5_uri_survives_encode_then_decode() {
        val payload = "socks5://alice:s3cr3t@198.51.100.7:1080"
        assertEquals(payload, roundTrip(payload))
    }

    @Test
    fun plain_host_port_endpoint_survives_round_trip() {
        val payload = "192.168.43.1:8787"
        assertEquals(payload, roundTrip(payload))
    }

    @Test
    fun shadowsocks_sip002_uri_survives_round_trip() {
        val payload = "ss://YWVzLTI1Ni1nY206cGFzcw==@203.0.113.9:8388#Tokyo-01"
        assertEquals(payload, roundTrip(payload))
    }

    @Test
    fun a_blank_white_frame_decodes_to_nothing_not_a_false_positive() {
        val blank = LumaFrame(
            data = ByteArray(64 * 64) { 255.toByte() },
            width = 64,
            height = 64,
            rowStride = 64,
        )
        assertNull(decoder.decode(blank))
    }

    @Test
    fun malformed_frame_dimensions_are_rejected_safely() {
        // rowStride smaller than width would shear the source; the decoder must bail, not throw.
        val bad = LumaFrame(data = ByteArray(10), width = 20, height = 20, rowStride = 5)
        assertNull(decoder.decode(bad))
    }

    /**
     * Regression: a spec-permitted YUV_420_888 Y plane whose LAST row omits its trailing padding
     * (common on Samsung/Qualcomm, incl. the Fold7 target) has length (height-1)*rowStride + width —
     * strictly less than rowStride*height. The old guard rejected every such frame and the scanner
     * silently never decoded. ZXing's PlanarYUVLuminanceSource only needs (height-1)*rowStride+width.
     */
    @Test
    fun padded_frame_with_unpadded_last_row_still_decodes() {
        val payload = "socks5://alice:s3cr3t@198.51.100.7:1080"
        val m = QrEncoder.encodeMatrix(payload, 320)
        val rowStride = m.width + 16 // camera row padding
        val size = (m.height - 1) * rowStride + m.width // last row unpadded → not rowStride*height
        val data = ByteArray(size) { 255.toByte() } // white background + inter-row padding
        for (y in 0 until m.height) {
            val row = y * rowStride
            for (x in 0 until m.width) {
                val idx = row + x
                if (idx < size) data[idx] = if (m.get(x, y)) 0 else 255.toByte()
            }
        }
        val frame = LumaFrame(data = data, width = m.width, height = m.height, rowStride = rowStride)
        assertEquals(payload, decoder.decode(frame))
    }
}
