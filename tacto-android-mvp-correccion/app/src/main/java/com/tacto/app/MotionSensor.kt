package com.tacto.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Lee el acelerómetro para dos cosas:
 *  1) Detectar secuencias de golpes en la trasera (picos cortos y aislados).
 *  2) Saber si el móvil está quieto, para disparar la foto sin que salga movida.
 */
class MotionSensor(
    context: Context,
    private val haptics: Haptics,
    private val listener: Listener
) : SensorEventListener {

    interface Listener {
        fun onTapSequence(count: Int)
        fun onDebug(text: String)
    }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Umbral del pico (m/s², sin gravedad). Más bajo = más sensible. */
    var umbral = 4.0f

    /** Se desactiva mientras se espera quietud o se hace la foto. */
    var golpesActivos = true

    // --- Parámetros de detección (ajustables tras las pruebas) ---
    private val maxDuracionPicoNs = 60_000_000L    // un golpe dura < 60 ms; más = movimiento
    private val refractarioNs = 100_000_000L       // ignora el rebote del mismo golpe
    private val maxHuecoEntreGolpesNs = 600_000_000L
    private val finSecuenciaMs = 650L              // silencio que cierra la secuencia

    // Filtro de gravedad (paso bajo) para quedarnos con los cambios bruscos
    private val gravedad = FloatArray(3)
    private var gravedadIniciada = false

    private var porEncima = false
    private var inicioPicoNs = 0L
    private var picoMax = 0f
    private var ultimoGolpeNs = 0L
    private var golpes = 0

    private val handler = Handler(Looper.getMainLooper())
    private val cerrarSecuencia = Runnable {
        if (golpes > 0) {
            val n = golpes
            golpes = 0
            listener.onTapSequence(n)
        }
    }

    // Buffer circular del módulo de aceleración (con gravedad) para medir quietud
    private val tam = 1024
    private val magBuf = FloatArray(tam)
    private val tBuf = LongArray(tam)
    private var idx = 0
    private var cuenta = 0

    fun start(): Boolean {
        val s = accel ?: return false
        gravedadIniciada = false
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST)
        return true
    }

    fun stop() {
        sm.unregisterListener(this)
        handler.removeCallbacks(cerrarSecuencia)
        golpes = 0
        porEncima = false
        cuenta = 0
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val t = e.timestamp

        // Quietud: guardamos el módulo bruto
        magBuf[idx] = sqrt(x * x + y * y + z * z)
        tBuf[idx] = t
        idx = (idx + 1) % tam
        if (cuenta < tam) cuenta++

        if (!gravedadIniciada) {
            gravedad[0] = x; gravedad[1] = y; gravedad[2] = z
            gravedadIniciada = true
            return
        }
        val a = 0.1f
        gravedad[0] += (x - gravedad[0]) * a
        gravedad[1] += (y - gravedad[1]) * a
        gravedad[2] += (z - gravedad[2]) * a
        val hx = x - gravedad[0]
        val hy = y - gravedad[1]
        val hz = z - gravedad[2]
        val mag = sqrt(hx * hx + hy * hy + hz * hz)

        if (!golpesActivos || SystemClock.elapsedRealtime() < haptics.ocupadoHastaMs) {
            porEncima = false
            return
        }

        if (mag > umbral) {
            if (!porEncima) {
                porEncima = true
                inicioPicoNs = t
                picoMax = mag
            } else {
                if (mag > picoMax) picoMax = mag
                if (t - inicioPicoNs > maxDuracionPicoNs && golpes > 0) {
                    // Movimiento sostenido (coger el móvil, dejarlo...): anula la secuencia
                    golpes = 0
                    handler.removeCallbacks(cerrarSecuencia)
                    listener.onDebug("Movimiento: secuencia anulada")
                }
            }
        } else if (porEncima) {
            porEncima = false
            val duracion = t - inicioPicoNs
            if (duracion <= maxDuracionPicoNs && t - ultimoGolpeNs > refractarioNs) {
                registrarGolpe(t)
            }
        }
    }

    private fun registrarGolpe(t: Long) {
        if (golpes > 0 && t - ultimoGolpeNs > maxHuecoEntreGolpesNs) golpes = 0
        golpes++
        ultimoGolpeNs = t
        listener.onDebug("Golpe $golpes · pico ${"%.1f".format(picoMax)}")
        handler.removeCallbacks(cerrarSecuencia)
        handler.postDelayed(cerrarSecuencia, finSecuenciaMs)
    }

    /** Desviación típica del módulo en la última ventana. -1 si no hay datos suficientes. */
    fun desviacion(ventanaMs: Long = 500): Float {
        if (cuenta < 10) return -1f
        val ultimo = tBuf[(idx - 1 + tam) % tam]
        val desde = ultimo - ventanaMs * 1_000_000L
        var n = 0
        var media = 0.0
        var m2 = 0.0
        for (k in 0 until cuenta) {
            val i = (idx - 1 - k + tam * 2) % tam
            if (tBuf[i] < desde) break
            n++
            val v = magBuf[i].toDouble()
            val d = v - media
            media += d / n
            m2 += d * (v - media)
        }
        if (n < 10) return -1f
        return sqrt(m2 / (n - 1)).toFloat()
    }

    fun estaQuieto(maxDesviacion: Float = 0.25f): Boolean {
        val d = desviacion()
        return d in 0f..maxDesviacion
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
