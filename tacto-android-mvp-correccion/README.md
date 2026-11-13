# TACTO · MVP 0.4 — Modo Corrección

## Objetivo de esta versión

Esta versión contiene **un único modo: CORRECCIÓN**. El Modo Enseñanza y cualquier flujo de selección manual de respuestas quedan fuera del MVP. Se podrán incorporar en una fase posterior, cuando el modo Corrección esté probado y sea estable.

TACTO permite fotografiar una página de preguntas tipo test, reconocer sus enunciados, confirmar el recuento mediante vibraciones y transmitir las respuestas correctas mediante Morse, ordenadas por número.

## Flujo de uso

1. En reposo, la persona da **3 golpes** en la parte trasera del teléfono.
2. TACTO activa directamente la cámara. No hay menú ni selección de modalidad.
3. La persona apunta al folio que quiere consultar y pulsa brevemente un botón de volumen para hacer la fotografía.
4. TACTO espera a que el teléfono esté quieto y comprueba la nitidez.
5. El servicio de IA transcribe las preguntas y opciones visibles.
6. Solo se consideran preguntas los **enunciados completos y numerados**. No se cuentan opciones A/B/C/D, títulos, instrucciones, ejemplos ni fragmentos.
7. TACTO emite **una vibración por cada enunciado completo reconocido**. Para facilitar el recuento, agrupa las vibraciones en bloques de cinco con una pausa más larga.
8. Si detecta preguntas cortadas o saltos en la numeración visible, comunica el aviso mediante Morse.
9. El servidor resuelve las preguntas con opciones completas. No recibe respuestas marcadas por la persona.
10. TACTO organiza los resultados de menor a mayor número y transmite el número de pregunta seguido de la letra correcta.
11. Si no se puede determinar una respuesta con confianza suficiente, transmite `NO DETERMINABLE`; no inventa una solución.

## Controles físicos

| Estado | Gesto / botón | Acción |
|---|---|---|
| Reposo | 3 golpes en la parte trasera | Activar directamente la cámara de Corrección |
| Cámara | Pulsación corta de volumen + o − | Esperar a que el móvil esté quieto y hacer la foto |
| Resultados | Pulsación corta de volumen + | Pasar a la respuesta siguiente |
| Resultados | Pulsación corta de volumen − | Volver a la respuesta anterior |
| Resultados | 2 golpes | Repetir la respuesta actual |
| Cualquier estado activo | 4 golpes | Cancelar y volver a reposo |

No existe menú de modos en esta versión.

## Validaciones y límites

- La app comprueba nitidez mediante el análisis local de la imagen.
- El endpoint `/leer` pide a la IA transcribir, no resolver.
- La app cuenta únicamente enunciados completos con número identificable.
- La integridad del enunciado y la integridad de las opciones se evalúan por separado: una pregunta puede contar en el pulso aunque falte una opción, pero esa pregunta no se resuelve.
- Los números duplicados se marcan como indeterminados.
- Si una pregunta depende de un texto no fotografiado o de contexto ausente, el sistema debe abstenerse.
- La confianza declarada por el modelo no sustituye las pruebas reales de precisión. Este MVP requiere validación con ejercicios revisados por una persona.

## Servidor Cloudflare Worker

Variables y secretos:
- `ANTHROPIC_API_KEY`: clave API de Anthropic, guardada como secreto del Worker.
- `TACTO_TOKEN`: token compartido entre la app y el Worker.
- `MODEL` (opcional): modelo de Anthropic configurado para el Worker.

Endpoints:
- `POST /leer`: recibe una imagen JPEG en base64 y devuelve preguntas transcritas, estado de integridad de enunciados/opciones e incidencias.
- `POST /resolver`: recibe preguntas reconocidas sin respuestas del usuario y devuelve resultados ordenados por número: `resuelta` con letra, o `indeterminada`.

La clave de Anthropic no se incluye en la aplicación. Las llamadas al proveedor se realizan desde el Worker.

## Configuración y pruebas

1. Despliega `worker/tacto-worker.js` en Cloudflare Workers.
2. Configura `ANTHROPIC_API_KEY` y `TACTO_TOKEN` como secretos.
3. Abre el panel de pruebas de la aplicación y configura la URL del Worker y el mismo `TACTO_TOKEN`.
4. Cierra el panel.
5. En reposo, da tres golpes para activar la cámara y probar el flujo.

El panel de pruebas sirve para configuración y diagnóstico; no forma parte de la interacción principal accesible.

## Compilación

El workflow de GitHub Actions genera el APK de depuración al subir cambios al repositorio o al ejecutarse manualmente.

### Protocolo de vibración
El recuento inicial utiliza pulsos cortos agrupados de cinco en cinco. Tras el recuento hay una pausa de 3 segundos y dos pulsos largos anuncian el inicio de la corrección. Cada pregunta se identifica con un pulso corto, seguido de su respuesta en Morse; después se esperan 5 segundos antes de la siguiente pregunta. La intensidad se configura en cinco niveles desde el panel de pruebas y queda guardada en el teléfono. La intensidad real puede variar según el motor de vibración del dispositivo.
