/**
 * TACTO · Worker de Cloudflare
 *
 * Variables (Settings → Variables and Secrets):
 *   ANTHROPIC_API_KEY  (secreto)  clave de la API de Anthropic
 *   TACTO_TOKEN        (secreto)  cualquier cadena larga; la misma que se pone en la app
 *   MODEL              (opcional) por defecto claude-sonnet-5-5
 *
 * Endpoints (POST, cabecera X-Tacto-Token):
 *   /leer      { imagen: <jpeg base64> }
 *              -> { preguntas:[{numero, enunciado, opciones, enunciado_completo, opciones_completas}], incidencias:[...] }
 *              Solo transcribe. Nunca devuelve respuestas.
 *   /resolver  { preguntas:[{numero, enunciado, opciones}] }
 *              -> { resultados:[{numero, estado:"resuelta"|"indeterminada", letra?, confianza}] }
 *              Resuelve todas las preguntas completas, sin pedir respuestas marcadas al usuario.
 */

const API_URL = "https://api.anthropic.com/v1/messages";

export default {
  async fetch(req, env) {
    if (req.method !== "POST") return json({ error: "Solo POST" }, 405);
    if (!env.TACTO_TOKEN || req.headers.get("x-tacto-token") !== env.TACTO_TOKEN) {
      return json({ error: "No autorizado" }, 401);
    }
    const ruta = new URL(req.url).pathname;
    let body;
    try { body = await req.json(); } catch { return json({ error: "JSON no válido" }, 400); }

    try {
      if (ruta === "/leer") return json(await leer(body, env));
      if (ruta === "/resolver") return json(await resolver(body, env));
      return json({ error: "Ruta desconocida" }, 404);
    } catch (e) {
      return json({ error: String(e.message || e) }, 502);
    }
  }
};

// ---------------------------------------------------------------- /leer

async function leer(body, env) {
  if (!body.imagen) throw new Error("Falta la imagen");

  const sistema =
    "Eres el lector de TACTO, una aplicación que da acceso a personas sordociegas a hojas de ejercicios impresas. " +
    "Tu trabajo es TRANSCRIBIR, nunca resolver: no indicas ni insinúas qué opción es correcta.";

  const instrucciones = `Transcribe las preguntas tipo test de esta foto de una página.

Devuelve SOLO un JSON con esta forma exacta, sin texto alrededor:
{"preguntas":[{"numero":51,"enunciado":"texto literal","opciones":[{"letra":"A","texto":"texto literal"}],"enunciado_completo":true,"opciones_completas":true}],"incidencias":["..."]}

Reglas:
- Incluye solo enunciados de pregunta con número. No cuentes títulos, instrucciones, ejemplos ni fragmentos sueltos.
- Transcribe el texto tal como aparece, sin resumir.
- "enunciado_completo": false solo si el enunciado está cortado o es ilegible.
- "opciones_completas": false si falta o es ilegible alguna opción de respuesta.
- No confundas la integridad del enunciado con la integridad de las opciones. El enunciado puede estar completo aunque falte una opción.
- Ignora marcas, tachones o rodeos hechos a mano sobre las opciones; no informes de ellos.
- Ordena las preguntas de menor a mayor número.
- En "incidencias" anota brevemente problemas de la foto (borrosa, inclinada, falta parte de la página). Lista vacía si no hay.
- Si no hay preguntas tipo test, devuelve "preguntas": [].`;

  const texto = await claude(env, sistema, [
    { type: "image", source: { type: "base64", media_type: "image/jpeg", data: body.imagen } },
    { type: "text", text: instrucciones }
  ], 4000);

  const datos = extraerJson(texto);

  // Lista blanca de campos: aunque el modelo añadiera algo, solo sale la transcripción.
  const preguntas = (datos.preguntas || []).map(p => ({
    numero: parseInt(p.numero, 10),
    enunciado: String(p.enunciado || ""),
    opciones: (p.opciones || []).map(o => ({ letra: String(o.letra || "").toUpperCase().slice(0, 1), texto: String(o.texto || "") })),
    enunciado_completo: p.enunciado_completo !== false && p.completa !== false,
    opciones_completas: p.opciones_completas !== false
  })).filter(p => Number.isFinite(p.numero) && p.enunciado);

  preguntas.sort((a, b) => a.numero - b.numero);
  return { preguntas, incidencias: (datos.incidencias || []).map(String).slice(0, 5) };
}

// ---------------------------------------------------------------- /resolver

async function resolver(body, env) {
  const recibidas = Array.isArray(body.preguntas) ? body.preguntas : [];
  if (recibidas.length === 0) throw new Error("No hay preguntas para resolver");
  if (recibidas.length > 40) throw new Error("Demasiadas preguntas en una sola página");

  const preguntas = recibidas
    .map(p => ({
      numero: Number.parseInt(p.numero, 10),
      enunciado: String(p.enunciado || "").trim(),
      opciones: Array.isArray(p.opciones) ? p.opciones.map(o => ({
        letra: String(o.letra || "").trim().toUpperCase().slice(0, 1),
        texto: String(o.texto || "").trim()
      })) : [],
      opciones_completas: p.opciones_completas !== false
    }))
    .filter(p => Number.isInteger(p.numero) && p.enunciado)
    .sort((a, b) => a.numero - b.numero);

  if (preguntas.length === 0) throw new Error("No hay preguntas completas y válidas");

  const conteo = new Map();
  for (const p of preguntas) conteo.set(p.numero, (conteo.get(p.numero) || 0) + 1);
  const duplicados = new Set([...conteo.entries()].filter(([, n]) => n > 1).map(([num]) => num));
  const unicas = preguntas.filter(p => !duplicados.has(p.numero) && p.opciones_completas && p.opciones.length >= 2);

  let soluciones = [];
  if (unicas.length > 0) {
    const sistema =
      "Eres el motor de resolución de TACTO, una herramienta de accesibilidad para personas sordociegas. " +
      "Debes resolver preguntas tipo test con rigor y nunca inventar una respuesta. " +
      "Si falta información esencial, el enunciado está ambiguo o la respuesta depende de un texto/documento no proporcionado, debes abstenerte.";

    const listado = unicas.map(p =>
      `PREGUNTA ${p.numero}: ${p.enunciado}\n` +
      p.opciones.map(o => `  ${o.letra}) ${o.texto}`).join("\n")
    ).join("\n\n");

    const instrucciones = `${listado}

Resuelve cada pregunta usando únicamente el enunciado, las opciones y conocimiento general fiable.
No presupongas que una opción es correcta por parecer familiar.
Si hay más de una respuesta defendible, falta contexto, depende de un texto no incluido o no puedes determinarla con seguridad, devuelve letra:null y confianza:"baja".
Devuelve confianza:"alta" solo cuando la respuesta sea inequívoca.

Devuelve SOLO JSON válido, sin Markdown ni texto adicional:
{"soluciones":[{"numero":51,"letra":"B","confianza":"alta"},{"numero":52,"letra":null,"confianza":"baja"}]}`;

    const datos = extraerJson(await claude(env, sistema, [{ type: "text", text: instrucciones }], 5000));
    soluciones = Array.isArray(datos.soluciones) ? datos.soluciones : [];
  }

  const mapa = new Map();
  for (const s of soluciones) {
    const n = Number.parseInt(s.numero, 10);
    if (!Number.isInteger(n) || mapa.has(n)) continue;
    mapa.set(n, {
      letra: s.letra == null ? null : String(s.letra).trim().toUpperCase().slice(0, 1),
      confianza: String(s.confianza || "baja").toLowerCase()
    });
  }

  const resultados = preguntas.map(p => {
    if (duplicados.has(p.numero)) {
      return { numero: p.numero, estado: "indeterminada", confianza: "baja", motivo: "numero_duplicado" };
    }
    if (!p.opciones_completas || p.opciones.length < 2) {
      return { numero: p.numero, estado: "indeterminada", confianza: "baja", motivo: "opciones_incompletas" };
    }
    const sol = mapa.get(p.numero);
    const letrasPermitidas = new Set(p.opciones.map(o => o.letra));
    if (!sol || !sol.letra || !letrasPermitidas.has(sol.letra) || sol.confianza !== "alta") {
      return { numero: p.numero, estado: "indeterminada", confianza: "baja" };
    }
    return { numero: p.numero, estado: "resuelta", letra: sol.letra, confianza: "alta" };
  });

  return { resultados };
}

// ---------------------------------------------------------------- utilidades

async function claude(env, system, content, maxTokens) {
  const r = await fetch(API_URL, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": env.ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01"
    },
    body: JSON.stringify({
      model: env.MODEL || "claude-sonnet-5-5",
      max_tokens: maxTokens,
      system,
      messages: [{ role: "user", content }]
    })
  });
  if (!r.ok) throw new Error(`Anthropic ${r.status}: ${(await r.text()).slice(0, 200)}`);
  const j = await r.json();
  return (j.content || []).filter(b => b.type === "text").map(b => b.text).join("");
}

function extraerJson(texto) {
  const i = texto.indexOf("{");
  const f = texto.lastIndexOf("}");
  if (i < 0 || f < i) throw new Error("La IA no devolvió JSON");
  return JSON.parse(texto.slice(i, f + 1));
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" }
  });
}
