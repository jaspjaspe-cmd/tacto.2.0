package com.tacto.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max

object ImagenUtil {

    /**
     * Reduce la foto a [ladoMax] px en el lado largo, la endereza según EXIF
     * y la comprime a JPEG. Un A4 a 2000 px se lee bien y pesa unos cientos de KB.
     */
    fun prepararJpeg(ruta: String, ladoMax: Int = 2000): ByteArray {
        val lim = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(ruta, lim)
        var muestreo = 1
        while (max(lim.outWidth, lim.outHeight) / (muestreo * 2) >= ladoMax) muestreo *= 2

        val bmp = BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = muestreo })
            ?: throw IllegalStateException("No se pudo leer la foto")

        val m = Matrix()
        val escala = ladoMax.toFloat() / max(bmp.width, bmp.height)
        if (escala < 1f) m.postScale(escala, escala)

        val grados = when (ExifInterface(ruta).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (grados != 0f) m.postRotate(grados)

        val salida = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val out = ByteArrayOutputStream()
        salida.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (salida !== bmp) salida.recycle()
        bmp.recycle()
        return out.toByteArray()
    }
}
