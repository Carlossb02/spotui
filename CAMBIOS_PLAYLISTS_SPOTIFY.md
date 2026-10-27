# Ajustes de playlists y menús — 25/10/2026

## Implementación

- `SpotifyPlaylist`, `Spotify.playlist()`: obtiene el propietario y los miembros **identificables** de la respuesta GraphQL; incorpora URL de avatar y consulta de perfil.
- `PlaylistAccess`: autorización conservadora: lista local, propietario autenticado o miembro identificado. «Seguir» una lista nunca concede derecho a editar. Si no puede verificarse, se trata como solo lectura.
- `PlaylistScreen`: muestra propietario y avatares solapados de perfiles reales; oculta «Añadir canciones» cuando no hay derecho; permite copiar a nueva lista o añadir canciones a una lista editable existente. El menú de canción recibe permiso para ocultar «Quitar de esta lista».
- `SavedInSheet`: solo muestra destinos remotos verificados como editables (incluye colaboradores cuando pueden verificarse).
- `PlaylistCopySheet`: selección de destino al estilo Spotify, con nueva lista, listas locales y listas propias/colaborativas verificadas.
- `LocalPlaylistPref` + `LocalSpotifyMirror`: la creación desde Biblioteca, «Añadir a lista» o «Copiar playlist» crea también una lista en Spotify si existe sesión válida. Se persiste la asociación entre ID local y remoto, y se replican altas/bajas/cambio de nombre de las listas locales enlazadas cuando hay ID de pista Spotify.
- `Api.getLibrary`: no duplica en la Biblioteca la playlist remota vinculada a una lista local recién creada.
- `PlayerOptionsSheet` y `SongOptionsSheet`: menú oscuro, carátula y tipografía al estilo Spotify, iconos de listas, álbum y artistas, preservando compartir, descargar, fuente alternativa, caché, cola y temporizador que ya existían.

## Limitaciones reales

- Spotify no ofrece una lista pública completa y universal de colaboradores. Solo se representan propietarios/miembros suministrados por la respuesta o perfiles verificables; no se inventan.
- La creación/escritura remota requiere sesión/token Spotify válido. Si Spotify rechaza la operación, se conserva la lista local y se ofrece «Sincronizar con Spotify» en el menú de la playlist para reintentar. No se ha implementado importación en tiempo real de cambios hechos directamente en Spotify sobre playlists locales enlazadas.
- Canciones locales/YouTube sin ID de pista Spotify permanecen en la lista local y no pueden trasladarse con exactitud al catálogo de Spotify.
- El soporte remoto depende del cliente Spotify existente basado en web/token y puede fallar si Spotify modifica sus endpoints.
- El entorno de este cambio no dispone del Android SDK ni pudo descargar el Gradle wrapper; no se ha compilado un APK ni se ha probado el comportamiento en un dispositivo.
