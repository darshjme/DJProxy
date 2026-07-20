package ai.darshj.djproxy.qr

/**
 * Pure, Android-free heart of the QR lane.
 *
 * Everything here is plain-JVM so the decode -> parse handoff can be unit-tested with a fake
 * decoder (no CameraX, no ZXing, no `ImageProxy`). The Android/ZXing/CameraX-bound classes
 * ([ZxingQrDecoder], [ZxingLuminanceAnalyzer], [QrCameraScanner], [QrEncoder]) all sit on top of
 * these abstractions.
 */

/**
 * A single grayscale (luminance-only) camera frame, extracted from the Y-plane of a YUV_420_888
 * `ImageProxy`. Kept as a value type with no Android types so decoders are testable off-device.
 *
 * @param data    row-major luminance bytes (Y plane). May contain row padding — see [rowStride].
 * @param width   cropped image width in pixels.
 * @param height  image height in pixels.
 * @param rowStride bytes per row in [data]; `>= width` when the camera pads rows. A decoder must
 *                  honour this so padded rows don't shear the decoded matrix.
 */
data class LumaFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int = width,
) {
    // data class over a ByteArray: identity-equality is meaningless, so give value semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LumaFrame) return false
        return width == other.width &&
            height == other.height &&
            rowStride == other.rowStride &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + rowStride
        return result
    }
}

/**
 * Decodes a QR payload out of a [LumaFrame].
 *
 * Returns the decoded text, or `null` when no QR code is present in the frame (the common,
 * non-exceptional case while the camera hunts for a code). Implementations must not throw for a
 * simple "not found" — only genuinely unexpected failures may propagate.
 */
fun interface QrDecoder {
    fun decode(frame: LumaFrame): String?
}

/**
 * Outcome of a single decode -> parse pass. Generic over [R] (the parser's product) so this lane
 * never has to depend on the config lane's concrete `ImportResult`/`ProxyConfig` types at compile
 * time — the parser is injected. In production [R] is the config lane's import result; in tests it
 * is a fake.
 */
sealed interface QrScanOutcome<out R> {
    /** A QR was decoded and handed to the parser, which produced [parsed] from [raw]. */
    data class Decoded<out R>(val raw: String, val parsed: R) : QrScanOutcome<R>

    /** The frame contained no readable QR code (keep scanning). */
    data object NoCodeFound : QrScanOutcome<Nothing>

    /** The decoder failed unexpectedly (not a plain "not found"); [reason] is human-readable. */
    data class DecodeFailed(val reason: String) : QrScanOutcome<Nothing>
}

/**
 * The decode -> parse seam the SSOT (DESIGN_V4 §4/§13) asks this lane to build and test: take a raw
 * camera frame, decode a QR string, then hand that string to a parser (in production the config
 * lane's `ConfigImporter::import`, injected by the ui/ViewModel) that turns it into a `ProxyConfig`
 * or a typed error.
 *
 * Kept parser-agnostic on purpose: the qr lane owns *decoding*; the config lane owns *parsing*. This
 * class binds them without either lane compile-depending on the other, and is fully unit-testable
 * with a fake [QrDecoder] and a fake parse function.
 *
 * @param decoder how a frame becomes a string (real: [ZxingQrDecoder]; test: a fake).
 * @param parse   how a decoded string becomes a result (real: `config.ConfigImporter::import`).
 */
class QrScanHandoff<R>(
    private val decoder: QrDecoder,
    private val parse: (String) -> R,
) {
    fun scan(frame: LumaFrame): QrScanOutcome<R> {
        val raw = try {
            decoder.decode(frame)
        } catch (t: Throwable) {
            return QrScanOutcome.DecodeFailed(t.message ?: t.javaClass.simpleName)
        } ?: return QrScanOutcome.NoCodeFound

        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return QrScanOutcome.NoCodeFound

        val parsed = try {
            parse(trimmed)
        } catch (t: Throwable) {
            // A well-behaved parser (ConfigImporter) returns a typed error instead of throwing; this
            // guard means even a misbehaving parser can never crash the camera analysis thread.
            return QrScanOutcome.DecodeFailed(t.message ?: t.javaClass.simpleName)
        }
        return QrScanOutcome.Decoded(trimmed, parsed)
    }
}
