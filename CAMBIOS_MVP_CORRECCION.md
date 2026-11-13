# TACTO MVP 0.4 — Solo Modo Corrección

## Decisión de alcance
Se elimina la selección entre modos y se retira del flujo activo el modo Examen/Aprendizaje. La aplicación arranca con un único modo: Corrección automática. El Modo Enseñanza se podrá reincorporar en una fase posterior.

## Cambios
- Tres golpes en reposo activan directamente la cámara.
- Se elimina el menú y el selector de modos.
- Se eliminan del flujo principal la lectura paso a paso de opciones y la marcación manual de respuestas.
- La foto se transcribe mediante `/leer`.
- Se emite un pulso por cada enunciado completo y numerado reconocido.
- Se advierten en Morse las preguntas cortadas y los saltos de numeración.
- `/resolver` determina las respuestas sin recibir respuestas introducidas por el usuario.
- Los resultados se ordenan ascendentemente por número.
- La persona navega entre resultados con los botones de volumen, repite con dos golpes y cancela con cuatro golpes.
- Se simplifica el modelo de datos para no guardar respuestas manuales del modo anterior.
- Se corrige el retorno tras un error del servidor: si la cámara ya se cerró, TACTO vuelve a reposo y permite reintentar con tres golpes.

## Qué se conserva
- Cámara y captura de imagen.
- Comprobación de nitidez.
- Sensor de golpes.
- Vibraciones hápticas.
- Lectura y codificación Morse.
- Panel de pruebas para configurar el Worker y ajustar parámetros.

## Despliegue
Hay que desplegar `worker/tacto-worker.js` en Cloudflare y compilar/instalar el APK nuevo. No se debe reemplazar la versión instalada hasta que el APK nuevo se compile y se pruebe en un teléfono.

## Validación pendiente
Este paquete contiene cambios de código fuente. Antes de considerarlo funcional, hay que compilarlo con GitHub Actions o Android Studio y probarlo en un móvil real. La detección de golpes, nitidez, recuento de enunciados, orden y exactitud de las respuestas requieren pruebas reales.

## Protocolo háptico actualizado
- Tras la foto, cuenta las preguntas reconocidas con pulsos cortos agrupados de cinco en cinco.
- Al terminar el conteo, espera 3 segundos y emite dos vibraciones largas para anunciar el inicio de la corrección.
- Para cada resultado, emite un pulso corto que identifica la pregunta y después la letra de respuesta en Morse.
- Espera 5 segundos después de cada respuesta antes de pasar a la siguiente.
- Si una respuesta no es determinable, transmite «NO DETERMINABLE» en Morse.
- Ajuste persistente de intensidad en cinco niveles, con botón para probarla. La amplitud efectiva depende del hardware del teléfono.
