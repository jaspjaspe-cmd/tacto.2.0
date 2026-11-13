package com.tacto.app

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * TACTO · MVP Corrección
 *
 * REPOSO: 3 golpes -> activa directamente la cámara.
 * CORRECCIÓN: foto -> lectura -> recuento háptico -> resolución automática -> Morse.
 * Volumen corto: hacer foto en cámara / anterior-siguiente respuesta en resultados.
 * 2 golpes: repetir el último resultado.
 * 4 golpes: volver a reposo.
 */
class MainActivity : ComponentActivity(), MotionSensor.Listener {

    private enum class Estado { REPOSO, CAMARA, ESPERANDO_QUIETO, CAPTURANDO, ANALIZANDO, CORRIGIENDO, RESULTADOS }

    private var estado = Estado.REPOSO

    private lateinit var prefs: SharedPreferences
    private lateinit var haptics: Haptics
    private lateinit var motion: MotionSensor
    private lateinit var morse: MorsePlayer

    // Interfaz
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var textoView: TextView
    private lateinit var panel: View
    private lateinit var debugView: TextView

    // Cámara
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var esperaQuieto: Runnable? = null

    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var umbralNitidez = 60.0

    // Sesión de corrección actual
    private var sesion: Sesion? = null
    private var secuencia = 0    // invalida callbacks diferidos al cambiar de estado

    // Corrección
    private var resultados: List<String> = emptyList()
    private var resIdx = 0

    // Pulso de "pensando"
    private val pensando = object : Runnable {
        override fun run() {
            haptics.play(Patron.PENSANDO)
            handler.postDelayed(this, 1500)
        }
    }

    private val lineas = ArrayDeque<String>()
    private val horaFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val pedirPermiso =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) log("Permiso de cámara concedido") else {
                status("Sin permiso de cámara")
                haptics.play(Patron.ERROR)
            }
        }

    // ================================================================== ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("tacto", MODE_PRIVATE)
        haptics = Haptics(this)
        motion = MotionSensor(this, haptics, this)
        morse = MorsePlayer(haptics)
        motion.umbral = prefs.getFloat("umbral_golpe", 4.0f)
        umbralNitidez = prefs.getFloat("umbral_nitidez", 60f).toDouble()
        morse.ppm = prefs.getInt("morse_ppm", 10)
        morse.espacio = prefs.getFloat("morse_espacio", 1.5f)
        haptics.intensidad = prefs.getInt("intensidad_vibracion", 3)

        construirUi()
        if (!tienePermisoCamara()) pedirPermiso.launch(Manifest.permission.CAMERA)
        aReposo(vibrar = false)
    }

    override fun onResume() {
        super.onResume()
        ocultarBarras()
        if (!motion.start()) status("Este móvil no tiene acelerómetro")
    }

    override fun onPause() {
        super.onPause()
        motion.stop()
        if (estado != Estado.REPOSO) aReposo(vibrar = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        bg.shutdown()
    }

    // ================================================================== golpes

    override fun onTapSequence(count: Int) {
        log("Secuencia: $count golpes")
        when (count) {
            3 -> if (estado == Estado.REPOSO) activarCamara() else log("TACTO ya está activo")
            4 -> if (estado != Estado.REPOSO) aReposo(vibrar = true)
            2 -> repetir()
            else -> log("Sin acción para $count golpes")
        }
    }

    override fun onDebug(text: String) = log(text)

    private fun repetir() {
        when (estado) {
            Estado.RESULTADOS -> reproducirResultado()
            else -> log("No hay respuesta para repetir")
        }
    }

    private fun aReposo(vibrar: Boolean) {
        secuencia++
        morse.stop()
        handler.removeCallbacks(pensando)
        cancelarEsperaQuieto()
        pararCamara()
        estado = Estado.REPOSO
        motion.golpesActivos = true
        status("En reposo · 3 golpes para activar")
        texto("")
        if (vibrar) haptics.play(Patron.CANCELADO)
    }

    // ================================================================== botones de volumen

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (esVolumen(keyCode)) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (esVolumen(keyCode)) {
            onVolumenCorto(keyCode)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun esVolumen(k: Int) =
        k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN

    private fun onVolumenCorto(k: Int) {
        val mas = k == KeyEvent.KEYCODE_VOLUME_UP
        when (estado) {
            Estado.REPOSO -> haptics.play(Patron.INACTIVO)
            Estado.CAMARA -> esperarQuietoYFoto()
            Estado.RESULTADOS -> {
                morse.stop()
                if (mas) {
                    if (resIdx < resultados.size - 1) {
                        resIdx++
                        reproducirResultado()
                    } else {
                        status("Fin de la corrección")
                        haptics.play(Patron.FIN)
                    }
                } else {
                    if (resIdx > 0) resIdx--
                    reproducirResultado()
                }
            }
            else -> log("Volumen ignorado en $estado")
        }
    }

    // ================================================================== cámara y foto

    private fun activarCamara() {
        if (!tienePermisoCamara()) {
            haptics.play(Patron.ERROR)
            pedirPermiso.launch(Manifest.permission.CAMERA)
            return
        }
        if (urlWorker().isBlank() || tokenWorker().isBlank()) {
            status("Falta configurar el servidor (panel ⚙)")
            haptics.play(Patron.ERROR)
            return
        }
        status("Activando cámara…")
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            try {
                val provider = futuro.get()
                cameraProvider = provider
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic)
                imageCapture = ic

                // Zoom 0,7 (gran angular) para que quepa el A4 sin levantar mucho el móvil.
                var zoomAplicado = false
                camera.cameraInfo.zoomState.observe(this) { zs ->
                    if (zoomAplicado || zs == null) return@observe
                    zoomAplicado = true
                    val objetivo = maxOf(ZOOM_DESEADO, zs.minZoomRatio)
                    camera.cameraControl.setZoomRatio(objetivo)
                    log("Zoom ${"%.2f".format(objetivo)} (mínimo del móvil ${"%.2f".format(zs.minZoomRatio)})")
                }

                estado = Estado.CAMARA
                status("Corrección · pulsa volumen para la foto")
                texto("")
                haptics.play(Patron.ACTIVADO)
            } catch (e: Exception) {
                log("Error cámara: ${e.message}")
                status("Error de cámara")
                haptics.play(Patron.ERROR)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun pararCamara() {
        cameraProvider?.unbindAll()
        imageCapture = null
    }

    private fun esperarQuietoYFoto() {
        estado = Estado.ESPERANDO_QUIETO
        motion.golpesActivos = false
        status("Mantén el móvil quieto…")
        haptics.play(Patron.ESPERA_QUIETO)

        val inicio = SystemClock.elapsedRealtime()
        val r = object : Runnable {
            override fun run() {
                if (estado != Estado.ESPERANDO_QUIETO) return
                val ahora = SystemClock.elapsedRealtime()
                if (ahora >= haptics.ocupadoHastaMs && motion.estaQuieto()) {
                    log("Quieto tras ${ahora - inicio} ms")
                    capturar()
                    return
                }
                if (ahora - inicio > 5000) {
                    estado = Estado.CAMARA
                    motion.golpesActivos = true
                    status("No se quedó quieto · repite")
                    haptics.play(Patron.NO_QUIETO)
                    return
                }
                handler.postDelayed(this, 100)
            }
        }
        esperaQuieto = r
        handler.postDelayed(r, 100)
    }

    private fun cancelarEsperaQuieto() {
        esperaQuieto?.let { handler.removeCallbacks(it) }
        esperaQuieto = null
    }

    private fun capturar() {
        val ic = imageCapture ?: return
        estado = Estado.CAPTURANDO
        status("Haciendo foto…")
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        val file = File(dir, "tacto_${System.currentTimeMillis()}.jpg")
        val opciones = ImageCapture.OutputFileOptions.Builder(file).build()

        ic.takePicture(opciones, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    bg.execute {
                        val v = SharpnessCheck.varianzaLaplaciano(file.absolutePath)
                        runOnUiThread { fotoTerminada(file, v) }
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    log("Error foto: ${exc.message}")
                    if (estado == Estado.CAPTURANDO) {
                        estado = Estado.CAMARA
                        motion.golpesActivos = true
                    }
                    status("Error al hacer la foto")
                    haptics.play(Patron.ERROR)
                }
            })
    }

    private fun fotoTerminada(file: File, nitidez: Double) {
        if (estado != Estado.CAPTURANDO) return
        motion.golpesActivos = true
        log("Foto ${file.name} · nitidez ${"%.0f".format(nitidez)}")
        if (nitidez < umbralNitidez) {
            estado = Estado.CAMARA
            status("Foto movida · repite")
            haptics.play(Patron.AVISO)
            return
        }
        haptics.play(Patron.FOTO_OK)
        analizar(file)
    }

    private fun analizar(file: File) {
        estado = Estado.ANALIZANDO
        status("Leyendo la página…")
        val miSecuencia = ++secuencia
        handler.postDelayed(pensando, 800)
        val api = Api(urlWorker(), tokenWorker())
        bg.execute {
            try {
                val jpeg = ImagenUtil.prepararJpeg(file.absolutePath)
                val r = api.leer(jpeg)
                runOnUiThread { if (miSecuencia == secuencia) lecturaRecibida(r) }
            } catch (e: Exception) {
                runOnUiThread { if (miSecuencia == secuencia) errorServidor(e, volverA = Estado.REPOSO) }
            }
        }
    }

    private fun lecturaRecibida(r: JSONObject) {
        handler.removeCallbacks(pensando)
        val lectura = Lectura.desdeRespuesta(r)
        val s = lectura.sesion
        log("Leídas ${s.preguntas.size} · cortadas ${lectura.incompletas} · saltos ${lectura.saltos}")

        if (s.preguntas.isEmpty()) {
            estado = Estado.CAMARA
            status("No encuentro enunciados completos · repite la foto")
            morse.play("NO HAY PREGUNTAS", Patron.AVISO.tiempos)
            return
        }

        pararCamara()
        sesion = s

        // Un pulso por cada enunciado completo y numerado reconocido.
        status("${s.preguntas.size} preguntas reconocidas")
        val pulsos = Haptics.pulsosAgrupados(s.preguntas.size)
        haptics.playRaw(pulsos)

        val avisos = buildString {
            if (lectura.incompletas.isNotEmpty()) {
                append("PREGUNTA CORTADA ").append(lectura.incompletas.joinToString(" ")).append(' ')
            }
            if (lectura.saltos.isNotEmpty()) {
                append("SALTO NUMERACION ").append(lectura.saltos.joinToString(" "))
            }
        }.trim()

        val miSecuencia = secuencia
        // Tras el recuento: pausa exacta de 3 s y dos vibraciones largas.
        handler.postDelayed({
            if (miSecuencia != secuencia) return@postDelayed
            if (avisos.isNotEmpty()) {
                texto(avisos)
                morse.play(avisos, Patron.AVISO.tiempos, onFin = {
                    if (miSecuencia == secuencia) anunciarInicioYResolver(s)
                })
            } else anunciarInicioYResolver(s)
        }, pulsos.sum() + 3000)

    }

    // ================================================================== corrección

    private fun anunciarInicioYResolver(s: Sesion) {
        // Dos señales largas anuncian que comienza la corrección.
        haptics.playRaw(longArrayOf(0, 450, 250, 450))
        handler.postDelayed({ resolverPreguntas(s) }, 1400)
    }

    private fun resolverPreguntas(s: Sesion) {
        if (s.preguntas.isEmpty()) {
            estado = Estado.CAMARA
            status("No hay preguntas completas para resolver")
            haptics.play(Patron.ERROR)
            return
        }
        if (urlWorker().isBlank() || tokenWorker().isBlank()) {
            estado = Estado.REPOSO
            status("Configura el servidor en el panel de pruebas · 3 golpes para activar")
            haptics.play(Patron.ERROR)
            return
        }

        estado = Estado.CORRIGIENDO
        status("Resolviendo ${s.preguntas.size} preguntas…")
        texto("Preparando respuestas")
        haptics.play(Patron.ACTIVADO)
        val miSecuencia = ++secuencia
        handler.postDelayed(pensando, 1000)
        val api = Api(urlWorker(), tokenWorker())
        bg.execute {
            try {
                val r = api.resolver(s.preguntas)
                runOnUiThread {
                    if (miSecuencia == secuencia) resultadosRecibidos(r)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (miSecuencia == secuencia) errorServidor(e, volverA = Estado.REPOSO)
                }
            }
        }
    }

    private fun resultadosRecibidos(r: JSONObject) {
        handler.removeCallbacks(pensando)
        val lista = mutableListOf<String>()
        val arr = r.optJSONArray("resultados")
        if (arr != null) {
            val ordenados = (0 until arr.length())
                .map { arr.getJSONObject(it) }
                .sortedBy { it.optInt("numero", Int.MAX_VALUE) }

            for (o in ordenados) {
                val n = o.optInt("numero")
                val estadoRespuesta = o.optString("estado")
                val confianza = o.optString("confianza")
                val respuesta = o.optString("letra").uppercase().take(1)
                val item = if (estadoRespuesta == "resuelta" && respuesta.isNotBlank()) {
                    if (confianza == "baja") "$n NO SEGURA $respuesta" else "$n $respuesta"
                } else {
                    "$n NO DETERMINABLE"
                }
                lista.add(item)
            }
        }

        if (lista.isEmpty()) {
            lista.add("NO HAY SOLUCIONES")
        }

        log("Soluciones: ${lista.joinToString(" | ")}")
        resultados = lista
        resIdx = 0
        estado = Estado.RESULTADOS
        reproducirResultado()
    }

    private fun reproducirResultado() {
        if (resultados.isEmpty()) return
        val item = resultados[resIdx].trim().split(Regex("\\s+"), limit = 2)
        val numero = item.firstOrNull().orEmpty()
        val respuesta = item.getOrNull(1).orEmpty()
        status("Pregunta ${resIdx + 1} de ${resultados.size}")
        texto("Pregunta $numero · $respuesta")
        // Una vibración corta identifica cada pregunta; después se transmite solo la respuesta.
        haptics.playRaw(longArrayOf(0, 120))
        handler.postDelayed({
            if (estado != Estado.RESULTADOS) return@postDelayed
            if (respuesta.matches(Regex("[A-D]"))) {
                morse.play(respuesta, onFin = { programarSiguienteResultado() })
            } else {
                morse.play("NO DETERMINABLE", onFin = { programarSiguienteResultado() })
            }
        }, 350)
    }

    private fun programarSiguienteResultado() {
        handler.postDelayed({
            if (estado != Estado.RESULTADOS) return@postDelayed
            if (resIdx < resultados.size - 1) {
                resIdx++
                reproducirResultado()
            } else {
                status("Fin de la corrección")
                haptics.play(Patron.FIN)
            }
        }, 5000)
    }

    private fun errorServidor(e: Exception, volverA: Estado) {
        handler.removeCallbacks(pensando)
        log("Error servidor: ${e.message}")
        haptics.play(Patron.ERROR)
        estado = volverA
        status(if (volverA == Estado.REPOSO) {
            "Error de conexión · 3 golpes para volver a intentar"
        } else {
            "Error de lectura · repite la fotografía"
        })
    }

    // ================================================================== ajustes guardados

    private fun urlWorker() = prefs.getString("worker_url", "") ?: ""
    private fun tokenWorker() = prefs.getString("worker_token", "") ?: ""

    // ================================================================== interfaz (para quien ayuda en las pruebas)

    private fun construirUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))

        val capa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(16))
        }

        statusView = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        capa.addView(statusView)

        textoView = TextView(this).apply {
            textSize = 22f
            setTextColor(0xFFFFD400.toInt())
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setPadding(0, dp(12), 0, 0)
        }
        capa.addView(textoView)

        capa.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        val botonPanel = Button(this).apply {
            text = "⚙ Panel de pruebas"
            setOnClickListener { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        capa.addView(botonPanel, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END })

        val scroll = ScrollView(this).apply { setBackgroundColor(0xDD000000.toInt()) }
        val contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        scroll.addView(contenido)
        panel = scroll
        panel.visibility = View.GONE

        // Configuración técnica para pruebas
        contenido.addView(etiqueta("URL del Worker"))
        contenido.addView(campo(urlWorker(), "https://tacto.tu-cuenta.workers.dev", secreto = false) {
            prefs.edit().putString("worker_url", it).apply()
        })
        contenido.addView(etiqueta("Token del Worker"))
        contenido.addView(campo(tokenWorker(), "TACTO_TOKEN", secreto = true) {
            prefs.edit().putString("worker_token", it).apply()
        })

        // Golpes
        val lblGolpe = etiqueta("")
        fun pintarGolpe() { lblGolpe.text = "Umbral golpe: ${"%.1f".format(motion.umbral)} m/s² (más bajo = más sensible)" }
        pintarGolpe()
        contenido.addView(lblGolpe)
        contenido.addView(barra(100, ((motion.umbral - 1.5f) / 10.5f * 100).toInt()) { p ->
            motion.umbral = 1.5f + p / 100f * 10.5f
            prefs.edit().putFloat("umbral_golpe", motion.umbral).apply()
            pintarGolpe()
        })

        // Nitidez
        val lblNit = etiqueta("")
        fun pintarNit() { lblNit.text = "Umbral nitidez: ${"%.0f".format(umbralNitidez)} (por debajo = movida)" }
        pintarNit()
        contenido.addView(lblNit)
        contenido.addView(barra(990, (umbralNitidez - 10).toInt().coerceIn(0, 990)) { p ->
            umbralNitidez = 10.0 + p
            prefs.edit().putFloat("umbral_nitidez", umbralNitidez.toFloat()).apply()
            pintarNit()
        })

        // Intensidad de vibración: cinco niveles persistentes.
        val lblIntensidad = etiqueta("")
        fun pintarIntensidad() {
            val nombres = listOf("", "Mínima · biblioteca", "Baja · interior", "Media · habitual", "Alta · calle/transporte", "Máxima · entorno ruidoso")
            lblIntensidad.text = "Intensidad de vibración: ${haptics.intensidad}/5 · ${nombres[haptics.intensidad]}"
        }
        pintarIntensidad()
        contenido.addView(lblIntensidad)
        contenido.addView(barra(4, haptics.intensidad - 1) { p ->
            haptics.intensidad = p + 1
            prefs.edit().putInt("intensidad_vibracion", haptics.intensidad).apply()
            pintarIntensidad()
        })
        contenido.addView(Button(this).apply {
            text = "Probar intensidad"
            setOnClickListener { haptics.playRaw(longArrayOf(0, 180, 180, 180, 180, 180)) }
        })

        // Morse
        val lblPpm = etiqueta("")
        fun pintarPpm() { lblPpm.text = "Velocidad Morse: ${morse.ppm} palabras/min" }
        pintarPpm()
        contenido.addView(lblPpm)
        contenido.addView(barra(22, morse.ppm - 3) { p ->
            morse.ppm = p + 3
            prefs.edit().putInt("morse_ppm", morse.ppm).apply()
            pintarPpm()
        })

        val lblEsp = etiqueta("")
        fun pintarEsp() { lblEsp.text = "Pausa entre letras: ×${"%.1f".format(morse.espacio)}" }
        pintarEsp()
        contenido.addView(lblEsp)
        contenido.addView(barra(30, ((morse.espacio - 1f) * 10).toInt()) { p ->
            morse.espacio = 1f + p / 10f
            prefs.edit().putFloat("morse_espacio", morse.espacio).apply()
            pintarEsp()
        })

        contenido.addView(Button(this).apply {
            text = "Probar Morse (SOS)"
            setOnClickListener { morse.play("SOS") }
        })

        debugView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFB8B8B8.toInt())
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
        }
        contenido.addView(debugView)

        capa.addView(scroll, LinearLayout.LayoutParams(MATCH, dp(380)))
        root.addView(capa, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.TOP))
        setContentView(root)
    }

    private fun etiqueta(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(0xFFFFD400.toInt())
        setPadding(0, dp(8), 0, 0)
    }

    private fun campo(valor: String, pista: String, secreto: Boolean, onCambio: (String) -> Unit) =
        EditText(this).apply {
            setText(valor)
            hint = pista
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
            setSingleLine(true)
            inputType = if (secreto) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            doAfterTextChanged { onCambio(it?.toString()?.trim() ?: "") }
        }

    private fun barra(max: Int, inicial: Int, onCambio: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        progress = inicial.coerceIn(0, max)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onCambio(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun status(t: String) {
        statusView.text = t
    }

    private fun texto(t: String) {
        textoView.text = t
    }

    private fun resaltar(palabra: String) {
        log("» $palabra")
    }

    private fun log(t: String) {
        lineas.addFirst("${horaFmt.format(Date())}  $t")
        while (lineas.size > 14) lineas.removeLast()
        debugView.text = lineas.joinToString("\n")
    }

    private fun tienePermisoCamara() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun ocultarBarras() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val ZOOM_DESEADO = 0.7f
    }
}
