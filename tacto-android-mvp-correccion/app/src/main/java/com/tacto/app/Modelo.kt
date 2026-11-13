package com.tacto.app

import org.json.JSONArray
import org.json.JSONObject

data class Opcion(
    val letra: String,
    val texto: String
)

data class Pregunta(
    val numero: Int,
    val enunciado: String,
    val opciones: List<Opcion>,
    val opcionesCompletas: Boolean = true
)

/** Preguntas reconocidas en una única fotografía, ordenadas por número. */
data class Sesion(
    val fecha: Long,
    val preguntas: List<Pregunta>
)

/** Resultado de leer una página: enunciados completos y avisos de integridad. */
data class Lectura(
    val sesion: Sesion,
    val incompletas: List<Int>,
    val saltos: List<Int>
) {
    companion object {
        fun desdeRespuesta(r: JSONObject): Lectura {
            val arr = r.optJSONArray("preguntas") ?: JSONArray()
            val completas = mutableListOf<Pregunta>()
            val incompletas = mutableListOf<Int>()

            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val numero = p.optInt("numero", -1)
                val enunciado = p.optString("enunciado", "").trim()
                val enunciadoCompleto = p.optBoolean(
                    "enunciado_completo",
                    p.optBoolean("completa", true)
                )

                if (numero < 0 || enunciado.isBlank()) continue

                if (!enunciadoCompleto) {
                    incompletas.add(numero)
                    continue
                }

                val opciones = mutableListOf<Opcion>()
                val oa = p.optJSONArray("opciones") ?: JSONArray()
                for (k in 0 until oa.length()) {
                    val o = oa.optJSONObject(k) ?: continue
                    val letra = o.optString("letra", "").trim().uppercase().take(1)
                    val texto = o.optString("texto", "").trim()
                    if (letra.isNotBlank() && texto.isNotBlank()) {
                        opciones.add(Opcion(letra, texto))
                    }
                }

                completas.add(
                    Pregunta(
                        numero = numero,
                        enunciado = enunciado,
                        opciones = opciones,
                        opcionesCompletas = p.optBoolean("opciones_completas", true)
                    )
                )
                // Las opciones ausentes no bloquean toda la pregunta: se evalúan
                // las opciones visibles y el motor puede abstenerse si no son concluyentes.
            }

            val ordenadas = completas.sortedBy { it.numero }
            val numeros = (ordenadas.map { it.numero } + incompletas).distinct().sorted()
            val saltos = mutableListOf<Int>()
            for (k in 1 until numeros.size) {
                var n = numeros[k - 1] + 1
                while (n < numeros[k] && saltos.size < 5) {
                    saltos.add(n++)
                }
            }

            return Lectura(
                sesion = Sesion(System.currentTimeMillis(), ordenadas),
                incompletas = incompletas.distinct().sorted(),
                saltos = saltos
            )
        }
    }
}
