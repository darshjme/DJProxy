package ai.darshj.djproxy.qr

import android.graphics.ImageFormat
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * The real, ZXing-backed [QrDecoder]. Pure JVM (ZXing `core` is a plain Java library), so it can be
 * exercised in unit tests via [LumaFrame] round-trips without any device/emulator.
 *
 * Pipeline per frame: luminance bytes -> [PlanarYUVLuminanceSource] (honours `rowStride`) ->
 * [HybridBinarizer] -> [MultiFormatReader] hinted to QR only. A single inverted retry handles
 * light-on-dark QR codes (dark-theme share sheets render them that way).
 */
class ZxingQrDecoder : QrDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    override fun decode(frame: LumaFrame): String? {
        if (frame.width <= 0 || frame.height <= 0) return null
        if (frame.rowStride < frame.width) return null
        // The Y plane only needs to address (height-1) full padded rows plus `width` bytes of the last
        // row. Many devices (Samsung/Qualcomm — including the Fold7 target) omit the trailing padding on
        // the final row, a spec-permitted YUV_420_888 layout. Requiring rowStride*height rejected every
        // real frame on those devices and the scanner silently never decoded; PlanarYUVLuminanceSource
        // itself only reads (height-1)*rowStride + width, so validate exactly that.
        if (frame.data.size < (frame.height - 1) * frame.rowStride + frame.width) return null

        val source = PlanarYUVLuminanceSource(
            frame.data,
            frame.rowStride,
            frame.height,
            0,
            0,
            frame.width,
            frame.height,
            false,
        )
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            // Retry inverted for light-modules-on-dark-background QR codes.
            try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
            } catch (_: NotFoundException) {
                null
            }
        } finally {
            reader.reset()
        }
    }
}

/**
 * CameraX [ImageAnalysis.Analyzer] that pulls the Y-plane out of each frame, decodes it with the
 * injected [decoder], debounces repeated reads of the same code, and forwards the raw string.
 *
 * The analyzer stays thin: all real decoding lives in [QrDecoder] (testable off-device). Every frame
 * is closed in `finally` so the camera pipeline never stalls. `onResult` is invoked on the analyzer
 * thread — the composable marshals it to the main thread before touching Compose state.
 *
 * @param decoder    QR decoder (default: [ZxingQrDecoder]).
 * @param debounceMs ignore an identical decode seen again within this window (prevents the same
 *                   sticky QR from firing dozens of times a second).
 * @param onResult   receives the decoded raw string (analyzer thread).
 */
class ZxingLuminanceAnalyzer(
    private val decoder: QrDecoder = ZxingQrDecoder(),
    private val debounceMs: Long = 2_500L,
    private val onResult: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile private var lastText: String? = null
    @Volatile private var lastAtMs: Long = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val frame = image.toLumaFrame() ?: return
            val text = decoder.decode(frame)?.trim()
            if (text.isNullOrEmpty()) return

            val now = SystemClock.elapsedRealtime()
            if (text == lastText && now - lastAtMs < debounceMs) return
            lastText = text
            lastAtMs = now
            onResult(text)
        } catch (_: Throwable) {
            // Decode misses and transient buffer hiccups are normal; swallow and keep scanning.
        } finally {
            image.close()
        }
    }
}

/** Extracts the luminance (Y) plane of a YUV_420_888 frame into a plain [LumaFrame]. */
private fun ImageProxy.toLumaFrame(): LumaFrame? {
    if (format != ImageFormat.YUV_420_888) return null
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    return LumaFrame(
        data = data,
        width = width,
        height = height,
        rowStride = plane.rowStride,
    )
}
