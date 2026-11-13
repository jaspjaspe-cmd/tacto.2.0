package com.tacto.app

import android.os.Handler
import android.os.Looper

object Morse {
    val CODIGOS: Map<Char, String> = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
        'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
        'M' to "--", 'N' to "-.", 'Ñ' to "--.--", 'O' to "---", 'P' to ".--.", 'Q' to "--.-",
        'R' to ".-.", 'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--",
        'X' to "-..-", 'Y' to "-.--", 'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '-' to "-....-", '/' to "-..-."
    )

    /** Mayúsculas, sin tildes (conserva Ñ), solo caracteres con código Morse. */
    fun normalizar(texto: String): String {
        val sb = StringBuilder()
        for (ch in texto.uppercase()) {
            val c: Char? = when (ch) {
                'Á', 'À', 'Â', 'Ä' -> 'A'
                'É', 'È', 'Ê', 'Ë' -> 'E'
                'Í', 'Ì', 'Î', 'Ï' -> 'I'
                'Ó', 'Ò', 'Ô', 'Ö' -> 'O'
                'Ú', 'Ù', 'Û', 'Ü' -> 'U'
                'Ç' -> 'C'
                ':', ';' -> ','
                '!' -> '.'
                '¿', '¡' -> null
                '\n', '\t', '\r' -> ' '
                else -> ch
            }
            if (c == null) continue
            if (c == ' ' || CODIGOS.containsKey(c)) sb.append(c)
        }
        return sb.toString().replace(Regex(" +"), " ").trim()
    }
}

/**
 * Reproduce texto en Morse palabra a palabra, para poder pararlo en cualquier momento
 * y mostrar en pantalla la palabra en curso.
 */
class MorsePlayer(private val haptics: Haptics) {

    /** Palabras por minuto (velocidad de cada letra). */
    var ppm = 10

    /** Multiplicador de las pausas entre letras y palabras (Farnsworth). 1 = estándar. */
    var espacio = 1.5f

    var reproduciendo = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var token = 0

    fun play(
        texto: String,
        prefijo: LongArray? = null,
        onPalabra: ((String) -> Unit)? = null,
        onFin: (() -> Unit)? = null
    ) {
        stop()
        val miToken = ++token
        val palabras = Morse.normalizar(texto).split(' ').filter { it.isNotEmpty() }
        val u = (1200L / ppm.coerceIn(3, 40))
        val huecoLetra = (3 * u * espacio).toLong()
        val huecoPalabra = (7 * u * espacio).toLong()
        reproduciendo = true

        val paso = object : Runnable {
            var i = 0
            override fun run() {
                if (miToken != token) return
                if (i >= palabras.size) {
                    reproduciendo = false
                    onFin?.invoke()
                    return
                }
                val palabra = palabras[i++]
                onPalabra?.invoke(palabra)
                val onda = onda(palabra, u, huecoLetra)
                haptics.playRaw(onda)
                handler.postDelayed(this, onda.sum() + huecoPalabra)
            }
        }

        if (prefijo != null && prefijo.size >= 2) {
            haptics.playRaw(prefijo)
            handler.postDelayed(paso, prefijo.sum() + 450)
        } else {
            handler.post(paso)
        }
    }

    fun stop() {
        token++
        handler.removeCallbacksAndMessages(null)
        if (reproduciendo) haptics.cancel()
        reproduciendo = false
    }

    private fun onda(palabra: String, u: Long, huecoLetra: Long): LongArray {
        val l = mutableListOf(0L)
        val letras = palabra.filter { Morse.CODIGOS.containsKey(it) }
        letras.forEachIndexed { ci, ch ->
            val code = Morse.CODIGOS.getValue(ch)
            code.forEachIndexed { si, s ->
                l.add(if (s == '.') u else 3 * u)
                val ultimoSimbolo = si == code.length - 1
                val ultimaLetra = ci == letras.length - 1
                if (!(ultimoSimbolo && ultimaLetra)) l.add(if (ultimoSimbolo) huecoLetra else u)
            }
        }
        return l.toLongArray()
    }
}
