package com.tacto.app

import android.graphics.BitmapFactory

/**
 * Nitidez = varianza del laplaciano sobre una versión reducida de la foto.
 * Valores altos = bordes definidos (texto nítido). Valores bajos = foto movida o desenfocada.
 * El umbral se calibra desde la pantalla de pruebas.
 */
object SharpnessCheck {

    fun varianzaLaplaciano(ruta: String): Double {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(ruta, limites)
        if (limites.outWidth <= 0) return 0.0

        var muestreo = 1
        while (limites.outWidth / muestreo > 1000) muestreo *= 2

        val bmp = BitmapFactory.decodeFile(ruta, BitmapFactory.Options().apply { inSampleSize = muestreo })
            ?: return 0.0
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()

        val g = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            g[i] = 0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) + 0.114f * (c and 0xff)
        }

        var n = 0
        var media = 0.0
        var m2 = 0.0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val l = (4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]).toDouble()
                n++
                val d = l - media
                media += d / n
                m2 += d * (l - media)
            }
        }
        return if (n > 1) m2 / (n - 1) else 0.0
    }
}
