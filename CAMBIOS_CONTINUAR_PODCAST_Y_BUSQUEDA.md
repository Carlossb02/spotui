# Spotui: episodios, búsqueda de álbumes y búsqueda global

## Cambios incluidos

- `PodcastResumeStore`: historial local persistente de episodios reproducidos, con estado observable por `StateFlow`, expiración a 30 días, identificación estable para vídeos YouTube, y descarte cuando la reproducción alcanza el 99 % o quedan menos de 60 segundos.
- `SongPlayer`: muestreo de progreso desde el reproductor cada segundo, en segundo plano y con la pantalla cerrada, guardado periódico y vaciado al cambiar de pista o pausar. Se distingue el reloj de ExoPlayer del reproductor web para no usar posiciones de otro motor.
- `HomeScreen`: carrusel horizontal «Seguir escuchando» en Inicio > Podcasts, con imagen 16:9, metadatos, acción de reanudar desde el punto guardado, añadir a la cola y menú del episodio.
- `EpisodeArtwork`: componente reutilizable de imagen de episodio con barra de progreso enlazada al estado. Integrado en programas, búsqueda, cola, historial, mini-reproductor y reproductor completo.
- `AlbumScreen`: búsqueda revelada al deslizar hacia abajo sobre la lista usando `CollectionPullSearch`, igual que en playlists; conserva filtrado, ordenación y cabecera y maneja teclado, retroceso y cierre.
- `Api` y `SearchScreen`: las playlists recuperadas por Spotify pasan a los resultados visibles, con filas navegables y registro de recientes. Orden conjunto por similitud de título y un sesgo moderado hacia canciones y artistas.

## Pruebas / limitaciones de este entorno

Se ha comprobado que los fuentes Kotlin editados pasan por el analizador sintáctico del compilador; no se detectaron errores de sintaxis. No se ha podido ejecutar una compilación Android real ni pruebas en dispositivo porque falta la distribución Gradle 9.6.1 y el SDK Android en el entorno y la descarga de Gradle falló por ausencia de acceso a internet.

## Recomendación de comprobación local

Abrir la carpeta `Spotui` en Android Studio, sincronizar Gradle y compilar con la configuración Android existente. Probar en dispositivo: episodio a la mitad, progreso con aplicación en segundo plano, cambio de audio a vídeo, reproducción al 99 %, menos de un minuto restante, reinicio de aplicación, navegación entre pantallas, gesto de búsqueda en álbum y orden de playlists en búsqueda global.
