package ai.darshj.djproxy.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR *encoding* half of the lane (the same ZXing dep that scans also generates — one 3.5 MB library
 * covers both, fully offline, no ML Kit / Play-services). Used by the hotspot ShareLan sheet to draw
 * a scannable "point your proxy here" QR via the ui seam `qr.QrEncoder.encodeQr(text, sizePx)`.
 */
object QrEncoder {

    /**
     * Render [text] as a QR code [ImageBitmap] of `sizePx` x `sizePx`.
     *
     * @param text        payload (e.g. `socks5://host:port` or a `host:port` LAN endpoint).
     * @param sizePx      target edge length in pixels (clamped to a sane minimum).
     * @param darkColor   module colour (ARGB int). Default opaque black for maximum scan reliability.
     * @param lightColor  background colour (ARGB int). Default opaque white.
     */
    fun encodeQr(
        text: String,
        sizePx: Int,
        darkColor: Int = Color.BLACK,
        lightColor: Int = Color.WHITE,
    ): ImageBitmap {
        val matrix = encodeMatrix(text, sizePx)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) darkColor else lightColor
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap.asImageBitmap()
    }

    /**
     * The pure ZXing step: text -> [BitMatrix]. Exposed (not private) so it can be unit-tested and
     * round-tripped through [ZxingQrDecoder] without allocating an Android [Bitmap].
     */
    fun encodeMatrix(text: String, sizePx: Int): BitMatrix {
        require(text.isNotEmpty()) { "QR payload must not be empty" }
        val edge = sizePx.coerceAtLeast(MIN_SIZE_PX)
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
        )
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, edge, edge, hints)
    }

    private const val MIN_SIZE_PX = 64
    private const val QUIET_ZONE_MODULES = 2
}
