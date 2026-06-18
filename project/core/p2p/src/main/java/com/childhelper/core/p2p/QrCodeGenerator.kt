package com.childhelper.core.p2p

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeGenerator {

    /**
     * Generate a QR code Bitmap using ZXing for encoding + Android Canvas for rendering.
     */
    fun generate(data: String, sizePx: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx)

            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixelW = sizePx.toFloat() / width
            val pixelH = sizePx.toFloat() / height

            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (bitMatrix.get(x, y)) {
                        canvas.drawRect(
                            x * pixelW, y * pixelH,
                            (x + 1) * pixelW, (y + 1) * pixelH,
                            paint
                        )
                    }
                }
            }

            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
