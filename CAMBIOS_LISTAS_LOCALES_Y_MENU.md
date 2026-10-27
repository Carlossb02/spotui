# Spotui - Favoritos de playlists y menús (25/10/2026)

## Cambios

- Las playlists creadas desde Spotui permanecen en el dispositivo. Se han eliminado los disparadores de creación, cambios de nombre y canciones de `LocalSpotifyMirror`, así como los estados y avisos de sincronización. No se eliminan listas remotas previamente creadas.
- Nueva preferencia `FavoritePlaylistsPref`: permite guardar o quitar de Biblioteca los accesos a playlists ajenas sin dar permiso de edición ni copiarlas. Se guarda nombre, portada y propietario. La Biblioteca los mezcla con las listas actuales, evita duplicados y funciona con la biblioteca en caché.
- En las playlists de solo lectura hay un corazón verde/blanco en la barra de controles y opción equivalente en el menú de la lista. No aparece «Añadir canciones» ni se ofrece eliminar canciones desde el menú de pista.
- En `SongOptionsSheet` y `PlayerOptionsSheet` se evita que el gesto de la hoja compita con el desplazamiento vertical de una lista larga: `sheetGesturesEnabled = false`. El tirador superior permite cerrar con toque o gesto hacia abajo. También se puede cerrar pulsando Atrás o fuera del menú.
- Los mensajes de creación muestran explícitamente que la lista se guarda solo en Spotui. Se conservan las demás funciones.

## Limitaciones

- Favorito es LOCAL y no es un seguimiento (follow) de Spotify; Spotify no muestra ese favorito en sus aplicaciones. Requiere conexión para consultar cambios en la playlist ajena, salvo datos ya almacenados sin conexión.
- No se ha ejecutado compilación Android ni pruebas táctiles en dispositivo: SDK Android y distribución de dependencias Gradle no disponibles en el entorno de ejecución.
