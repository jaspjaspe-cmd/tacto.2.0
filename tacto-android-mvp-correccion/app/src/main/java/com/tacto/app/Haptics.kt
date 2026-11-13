package com.tacto.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Patrones de vibración fijos.
 * Formato: [espera, vibra, pausa, vibra, ...] en milisegundos.
 */
enum class Patron(val tiempos: LongArray) {
    ACTIVADO(longArrayOf(0, 60, 80, 60, 80, 250)),         // corto-corto-largo
    CANCELADO(longArrayOf(0, 450)),                         // una larga
    ESPERA_QUIETO(longArrayOf(0, 25)),                      // toque suave
    FOTO_OK(longArrayOf(0, 40, 70, 40)),                    // doble corto
    AVISO(longArrayOf(0, 150, 100, 150, 100, 150)),         // tres medias (foto movida, avisos)
    NO_QUIETO(longArrayOf(0, 80, 80, 80, 80, 80, 80, 80)),  // cuatro rápidas
    INACTIVO(longArrayOf(0, 200, 150, 200)),                // "eso no se puede aquí"
    ERROR(longArrayOf(0, 500, 150, 500, 150, 500)),         // tres largas
    PENSANDO(longArrayOf(0, 25)),                           // pulso cada 1,5 s mientras piensa
    MARCA_PREGUNTA(longArrayOf(0, 350)),                    // antes de un enunciado
    MARCA_OPCION(longArrayOf(0, 50)),                       // antes de una opción
    MARCA_ELEGIDA(longArrayOf(0, 50, 80, 50, 80, 50)),      // antes de la opción que ya marcaste
    RESPUESTA_GUARDADA(longArrayOf(0, 40, 60, 40, 60, 40, 60, 300)),
    FIN(longArrayOf(0, 700, 200, 700))                      // fin de examen / resumen
}

class Haptics(context: Context) {

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /**
     * Mientras el motor vibra, el acelerómetro recibe "golpes" falsos.
     * MotionSensor ignora los datos hasta este instante (reloj elapsedRealtime).
     */
    @Volatile
    var ocupadoHastaMs: Long = 0L
        private set

    /** Intensidad seleccionable 1..5. Algunos motores pueden limitar esta función. */
    @Volatile
    var intensidad: Int = 3
        set(value) { field = value.coerceIn(1, 5) }

    fun play(p: Patron) = playRaw(p.tiempos)

    fun playRaw(tiempos: LongArray) {
        if (tiempos.size < 2) return
        vibrator.cancel()

        // En móviles con control de amplitud reducimos mucho el nivel mínimo.
        // En móviles sin ese soporte, Android ignora las amplitudes: reducimos
        // la duración de cada pulso para evitar que el nivel 1 salga a potencia máxima.
        val tieneControlAmplitud =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()

        val tiemposAplicados = if (tieneControlAmplitud) {
            tiempos
        } else {
            val factorDuracion = floatArrayOf(0f, 0.25f, 0.40f, 0.58f, 0.78f, 1.0f)[intensidad]
            LongArray(tiempos.size) { i ->
                if (i % 2 == 1) (tiempos[i] * factorDuracion).toLong().coerceAtLeast(1L)
                else tiempos[i]
            }
        }

        if (tieneControlAmplitud) {
            val amplitud = intArrayOf(0, 12, 28, 55, 100, 160)[intensidad]
            val amplitudes = IntArray(tiemposAplicados.size) { i ->
                if (i % 2 == 0) 0 else amplitud
            }
            vibrator.vibrate(VibrationEffect.createWaveform(tiemposAplicados, amplitudes, -1))
        } else {
            vibrator.vibrate(VibrationEffect.createWaveform(tiemposAplicados, -1))
        }
        ocupadoHastaMs = SystemClock.elapsedRealtime() + tiemposAplicados.sum() + 150
    }

    fun cancel() {
        vibrator.cancel()
        ocupadoHastaMs = SystemClock.elapsedRealtime() + 100
    }

    companion object {
        /** n pulsos en grupos de 5, con una pausa larga entre grupos. 12 = 5 · 5 · 2 */
        fun pulsosAgrupados(n: Int): LongArray {
            val l = mutableListOf(0L)
            for (i in 1..n) {
                l.add(120)
                if (i < n) l.add(if (i % 5 == 0) 700L else 200L)
            }
            return l.toLongArray()
        }
    }
}
