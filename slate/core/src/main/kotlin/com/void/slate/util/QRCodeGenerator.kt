package com.void.slate.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Utility for generating QR codes.
 *
 * Best practices from ZXing documentation:
 * - High error correction (30% recovery) for better scanning
 * - Proper margin (4 modules) for quiet zone
 * - UTF-8 character encoding for international support
 */
object QRCodeGenerator {

    /**
     * Generate a QR code bitmap from JSON string.
     *
     * @param content The content to encode (typically JSON)
     * @param size The size of the QR code in pixels (square)
     * @return Bitmap containing the QR code
     */
    fun generateQRCode(
        content: String,
        size: Int = 512
    ): Bitmap {
        val hints = hashMapOf<EncodeHintType, Any>().apply {
            // Use HIGH error correction (30% recovery) for better scanning reliability
            // Recommended for QR codes that may be displayed on screens with glare
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H)

            // Margin of 4 modules (quiet zone) - ZXing default and recommended
            put(EncodeHintType.MARGIN, 4)

            // UTF-8 encoding for proper character support
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
        }

        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }

        return bitmap
    }
}
