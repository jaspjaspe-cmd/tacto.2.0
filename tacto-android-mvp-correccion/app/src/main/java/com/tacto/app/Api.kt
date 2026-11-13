package com.tacto.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente del Worker de TACTO. La API key de Anthropic vive en el Worker, nunca en la app.
 * Las llamadas son bloqueantes: usar siempre desde un hilo de fondo.
 */
class Api(private val baseUrl: String, private val token: String) {

    fun leer(jpeg: ByteArray): JSONObject =
        post("/leer", JSONObject().put("imagen", Base64.encodeToString(jpeg, Base64.NO_WRAP)))

    /**
     * Resuelve todas las preguntas completas de la página, sin respuestas introducidas por el usuario.
     * El Worker devuelve las soluciones ordenadas por número e indica las que no puede determinar.
     */
    fun resolver(preguntas: List<Pregunta>): JSONObject {
        val arr = JSONArray()
        preguntas.sortedBy { it.numero }.forEach { p ->
            val opciones = JSONArray()
            p.opciones.forEach { o ->
                opciones.put(JSONObject().put("letra", o.letra).put("texto", o.texto))
            }
            arr.put(
                JSONObject()
                    .put("numero", p.numero)
                    .put("enunciado", p.enunciado)
                    .put("opciones", opciones)
                    .put("opciones_completas", p.opcionesCompletas)
            )
        }
        return post("/resolver", JSONObject().put("preguntas", arr))
    }

    private fun post(ruta: String, body: JSONObject): JSONObject {
        val c = URL(baseUrl.trim().trimEnd('/') + ruta).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("X-Tacto-Token", token.trim())
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val texto = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${texto.take(160)}")
            return JSONObject(texto)
        } finally {
            c.disconnect()
        }
    }
}
