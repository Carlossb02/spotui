# Revisión de reproducción — versión 3

## Motivo de esta revisión

Se descartan las modificaciones anteriores que cambiaban la selección temprana de proveedores y calentaban un segundo decodificador de vídeo. Esta versión parte del ZIP original `Spotui (3).zip`, no del ZIP optimizado anterior.

## Música

- Los métodos de búsqueda de candidatos, puntuación y validación de versiones (`resolveVideoCandidates` y `resolveYtPlayback`) **no se han modificado**.
- Se mantienen intactos el algoritmo de orden de proveedores, `withTimeoutOrNull(8000L)`, la espera de todos los resultados y el fallback a Deezer/YouTube.
- Al **terminar** de decidir el resultado, se cancelan las tareas de proveedores todavía pendientes. Eran tareas lanzadas sobre el scope general, y podían seguir consumiendo red mientras se resuelve el siguiente tema. Se evita esa contención sin alterar la decisión ya tomada.
- No se suprime ninguna validación de artista, álbum, versión, duración, idioma, formato o ID.

Este cambio por sí solo NO garantiza que una búsqueda fría sea instantánea. La latencia de Spotify/YouTube y los servicios de proveedores sigue existiendo y no se pueden eludir sus comprobaciones sin cambiar el resultado.

## Vídeopodcasts

- Se elimina el prewarm del decodificador adicional introducido en la versión anterior; ya no arranca en segundo plano mientras suena el podcast.
- El resolutor de audio guarda durante 90 segundos **únicamente** una respuesta de YouTube ya verificada para el ID exacto, cuando incluye formatos de vídeo con URL HTTPS directa. El reproductor visual reutiliza esos formatos si están disponibles, evitando peticiones adicionales al activar vídeo. El audio no usa esta caché para elegir fuentes.
- Si no existe ese resultado, se mantiene la resolución por clientes, compartida por episodio entre el prefetch Wi-Fi y el botón de vídeo; ya no se duplican las consultas simultáneas.
- Se prefieren formatos de vídeo-only H.264/MP4 aptos para cadencia de 24 a 30 fps, con fallback a otros codecs y a URLs cifradas.
- Se corrige la primera alineación para realizarse al contar con la superficie visible, no darla por realizada cuando el vídeo estaba listo sin superficie.
- El vídeo no cambia periódicamente de velocidad ni busca keyframes continuamente. Se permiten ajustes puntuales por seek real, primera activación o desincronización muy grande. El audio sigue siendo la referencia y no se toca.
- La opción Auto puede bajar calidad si hay buffering continuado, pero no sube de resolución automáticamente durante la reproducción: con los streams progresivos que proporciona YouTube, subir requeriría sustituir el MediaItem y provocaría un corte de imagen. La selección manual sigue disponible.
- Ante una URL de vídeo caducada, se permite una única recarga de fuentes verificadas (sin parar el audio), evitando reintentos infinitos.

## Limitaciones y verificación

No se ha compilado el APK ni se ha validado en un teléfono Android real en este entorno; no hay SDK de Android. Se han verificado diferencias con el ZIP original y la estructura de los ZIP. Antes de considerarlo resuelto, comprobar:

1. Música fría: canción normal, remix, edición single, remaster y versión en directo; resultado idéntico al algoritmo anterior.
2. Podcast: primer vídeo con audio ya sonando, vídeo-audio-vídeo, avance de 15 segundos, pausas, rotación, Picture-in-Picture y cambio de episodio.
3. Wi-Fi y red móvil; cancelaciones de precarga; URLs que devuelvan 403 y recuperación posterior.
4. En Logcat filtrar `SpotuiStartup`, `SpotuiPodcastVideo` y `SongPlayer` para medir latencias, desajuste del reloj y reinicios de fuente.
