# Spotui — Podcast: audio/vídeo continuo y pantalla completa

Base: `Spotui_backup_v2_descargas_privadas.zip` (se conservan los cambios y backups anteriores).

## Cambios realizados

### 1. Cambio audio/vídeo sin cortes de sonido
- Antes, `PodcastVideoSession` invocaba `SongPlayer.enablePodcastVideo()` y `SongPlayer.disablePodcastVideo()`. Ambos sustituían el MediaSource del reproductor audible mediante `setMediaSource`/`setMediaItem` y `prepare`, causando rebuffering.
- Ahora el sonido de `SongPlayer`/`MediaSession` no se toca nunca al alternar el modo vídeo. Se crea un ExoPlayer de vídeo independiente, silenciado y con pista de audio desactivada. Al cerrar vídeo se libera exclusivamente ese reproductor.
- Se sigue el reloj de `SongPlayer`: posición inicial, reproducción/pausa y corrección espaciada de desviaciones superiores a 1 s. Solo se corrige cuando el vídeo está preparado, para evitar ciclos de búsqueda durante el buffering. No se cambia el volumen ni la posición del audio por este sincronizador.
- La calidad de vídeo solo reconfigura el reproductor visual; la sesión audible sigue intacta.
- Limitación: el primer fotograma de vídeo puede tardar en cargar por red; eso NO interrumpe el audio.

### 2. Pantalla completa renovada
- El overlay de vídeo a pantalla completa se aloja una única vez en `MainActivity` y su estado se conserva en `PodcastVideoSession`.
- Oculta la barra de estado mientras está visible y restaura la configuración al cerrarlo; la imagen usa toda el área disponible sin recortes (FIT).
- Controles minimalistas flotantes semitransparentes, gradientes, botón central, ±15 s, calidad, PiP, cerrar, barra de progreso y tiempo. Ocultación automática de controles.
- Doble toque a la izquierda: -15 segundos; doble toque a la derecha: +15 segundos; toque simple: mostrar/ocultar controles. Los saltos actúan sobre `SongPlayer`.

### 3. Rotación
- La pantalla completa ya no es estado `remember` de un frame dentro de la rama horizontal o vertical de PlayerScreen. Se conserva al girar y mantiene el vídeo existente.
- Si el reproductor estaba minimizado, no se fuerza pantalla completa al girar.
- Se mantiene el PiP y la configuración `configChanges` de la actividad.

## Archivos alterados
- `app/src/main/java/com/music/spotui/ui/screens/PodcastVideoFrame.kt`
- `app/src/main/java/com/music/spotui/MainActivity.kt`

## Verificaciones
- Revisada correspondencia de llaves y delimitadores Kotlin.
- `kotlinc` sin AndroidX en el entorno no encontró errores de *parseo*, pero no permite validar símbolos de Android/Compose.
- Comprobaciones estáticas de: ausencia de reconfiguración de SongPlayer en el cambio audio/vídeo, uso de companion silenciado, controles ±15 s, estado fullscreen global, barra de estado y manejo de orientación.
- Verificada integridad del ZIP.

**Pendiente de prueba en Android Studio/dispositivo:** compilar, reproducir podcast al pasar audio↔vídeo, doble toque en ambas orientaciones, restauración de status bar y PiP. El entorno no dispone de SDK de Android ni todas las dependencias Gradle.
