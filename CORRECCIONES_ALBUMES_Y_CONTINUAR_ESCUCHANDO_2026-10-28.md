# Correcciones: álbumes y «Seguir escuchando» (28/10/2026)

## Pantalla de álbumes

- La barra superior reserva su altura real a través de `Scaffold` y `innerPadding.calculateTopPadding()`, evitando que la carátula, botones o búsqueda se superpongan.
- Cabecera unificada con playlists: fondo degradado, flecha circular semitransparente, imagen centrada con tamaño y borde de playlists, título de 30 sp, artista, número de canciones, espaciados, botones de biblioteca, cola, descarga, aleatorio y botón de reproducción verde de 52 dp.
- `CollectionPullSearch` y `CollectionSortRow` siguen compartidos con playlists. La cabecera se oculta al activar búsqueda y se restaura al cerrarla.

## Inicio > Podcasts

- «Seguir escuchando» se muestra como primera sección después de «Podcasts y categorías», **encima de «Podcasts populares en España»**. Si aún no hay historial, muestra una indicación en lugar de desaparecer.
- Carrusel horizontal de tarjetas de episodios: carátula 16:9, título, programa, fragmento textual cuando está disponible, tiempo pendiente, reproducción y acciones. Barra de progreso en tiempo real.
- Botón «Marcar como visto»: retira el episodio del carrusel inmediatamente y persiste esa decisión en preferencias locales durante 30 días, sin que las actualizaciones del reproductor vuelvan a mostrarlo.
- Para episodios de YouTube (`yt_ep:`), al colocar el cursor sobre la carátula y mantenerlo durante 600 ms se solicita una fuente de vídeo de baja calidad y se reproduce una **vista previa animada y silenciada** en la carátula. Se utiliza un ExoPlayer propio y temporal, no el de audio ni el de la sesión de vídeo principal. Se libera al retirar el cursor o abandonar la pantalla. Si no hay un vídeo disponible se mantiene la miniatura. Las URL de la vista previa se almacenan durante 10 minutos para evitar resolverlas en cada hover.
- Los episodios cuyo origen es Spotify (`episode:`) permanecen como imagen estática porque no existe una fuente de vídeo de YouTube asociada por el identificador Spotify.
- El progreso ahora comprueba que la reproducción web esté realmente activa antes de leer sus marcas de tiempo.

## Comprobaciones efectuadas

- Analizador sintáctico oficial de Kotlin (PSI) en `AlbumScreen.kt`, `HomeScreen.kt`, `ContinuePodcastPreview.kt`, `PodcastResumeStore.kt`, `PodcastVideoFrame.kt` y `SongPlayer.kt`: sin errores de sintaxis.
- No ha sido posible realizar compilación completa con Gradle o ejecución en dispositivo: este entorno no tiene la distribución de Gradle requerida, ni SDK Android configurado.

## Pruebas aconsejadas en Android Studio

1. Abrir un álbum, observar el margen superior y los botones, tirar hacia abajo, buscar y cerrar búsqueda.
2. Entrar a Inicio > Podcasts con historial vacío: la sección «Seguir escuchando» debe estar visible sobre «Podcasts populares en España».
3. Reproducir un podcast de YouTube a mitad de capítulo: debe aparecer una tarjeta sin reiniciar la pantalla; comprobar el progreso, reanudación y persistencia tras reiniciar.
4. Con ratón, mantener el puntero sobre la carátula: se muestra vídeo sin interrumpir la música y se detiene al retirar el cursor.
5. Pulsar «Marcar como visto» durante una reproducción: debe retirarse inmediatamente y permanecer oculto tras reiniciar la aplicación.
