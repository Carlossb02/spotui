# Spotui v5 — correcciones desde v4

Esta actualización se construye sobre las fuentes v4 suministradas anteriormente. El archivo recomendado para aplicar es **Spotui_v5_archivos_modificados.zip**, que contiene únicamente `SongPlayer.kt` y `PodcastVideoFrame.kt` además de este informe. Se deben reemplazar sobre el proyecto v4 existente; no usar el ZIP rápido e inestable como base.

## Vídeo negro

- Los formatos directos de vídeo con parámetro de throttling `n` se pasan por el descifrador antes de intentar reproducirlos. Un HTTPS por sí solo no acredita una fuente válida.
- La respuesta de vídeo reutilizada de la resolución de audio se considera únicamente *metadatos*, no una prueba de que el vídeo vaya a funcionar. Se valida la URL de arranque con una petición `Range` (como máximo 1024 bytes), comprobando código HTTP y recepción de datos. Se consulta una segunda calidad solo si falla la primera. Se efectúa fuera del hilo UI. Las URLs no verificadas no se marcan como fuente lista.
- Se conserva un catálogo de otras calidades, pero el arranque Auto elige preferentemente la variante cuya URL se ha comprobado.
- Se ha corregido el estado de carga: `Player.STATE_READY` no significa que Media3 haya mostrado un fotograma. Se pasa a listo con `onRenderedFirstFrame` y se registra `video FIRST_FRAME` en Logcat.
- Si el decodificador queda en READY sin imagen durante 5,5 segundos mientras el audio está sonando y existe una superficie, se recupera la fuente visual con límites. No se reinicia ni se modifica el reproductor de audio. Se muestra indicador de carga, y en el último fallo se muestra un mensaje en vez de un negro silencioso.

## Podcasts largos

- Los episodios reanudados se envían a Media3 con `setMediaItem(item, resumePosition)` **antes** de `prepare()`; antes se iniciaba la preparación a 0 y luego se hacía `seekTo`, provocando potencialmente la apertura de un segmento de red innecesario y un segundo inicio de extracción.
- Para los podcasts se evita consultar toda la tabla de descargas de canciones al construir los metadatos de la portada, manteniendo el ID y el campo de portada.
- No se realiza búsqueda de coincidencia musical: el audio sigue resolviéndose por el ID exacto del episodio. La primera autorización de YouTube sigue necesitando su red y tokens.

## Música

- Se **mantienen sin cambios** la puntuación de candidatos, duración, versión, artista, álbum, orden y espera de proveedores y la validación/reproducción de YouTube.
- Una reparación exitosa de los metadatos de Spotify se recuerda durante 10 minutos **para ese ID de canción y contenido exacto**; evita repetir peticiones para remasters, ediciones especiales o versiones cuyas banderas de versión impedían usar el atajo normal en siguientes reproducciones.
- Se registran `ytSearch metadataGate` y `ytSearch networkGate` en `SpotuiStartup`. Esto permitirá saber si una búsqueda inicial lenta está esperando Spotify o alguna de las consultas de YouTube sin degradar la selección correcta.

## Verificación

Validación estructural de cambios frente a v4 y comprobación automatizada de que el bloque de clasificación de candidatos, la decisión de proveedores y `resolveYtPlayback` son idénticos a v4. Comprobación de ZIP. **No hay compilación Android ni prueba en dispositivo en este entorno.** No se afirma que una primera búsqueda sin caché sea instantánea, ya que necesita consultas externas y la clasificación completa. En Android, comprobar el vídeo tanto en modo compacto como en pantalla completa, Wi-Fi/datos, cambio vídeo-audio-vídeo, reanudación de podcast tras varias horas, canciones de versiones cercanas y Logcat con etiquetas `SpotuiStartup` y `SpotuiPodcastVideo`.
