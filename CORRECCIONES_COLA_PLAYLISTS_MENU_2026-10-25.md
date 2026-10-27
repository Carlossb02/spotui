# Correcciones: cola, creación de playlists Spotify y menús

## Cola
- Antes: una mutación de la cola de reproducción por cada movimiento del dedo y por cada frame de scroll; al llegar a la parte superior, `LazyColumn` recolocaba el ancla y las filas parpadeaban o se bloqueaban.
- Ahora: la lista mantiene el orden y sus claves durante el gesto; muestra una fila flotante y marca la posición de destino. `scrollBy` continua al sostener el dedo cerca de los bordes; se guarda **un solo cambio** en el reproductor al soltar.
- Las canciones duplicadas se distinguen por su posición original, no por `song.id`.

## Spotify
- La app obtiene un bearer token de la sesión del reproductor web (`sp_dc`). Antes se intentaba crear playlists mediante el REST público `POST /v1/me/playlists`, que puede rechazar este tipo de token.
- La creación usa ahora el endpoint de playlists del Web Player, con una operación `UPDATE_LIST_ATTRIBUTES`. Las altas de canciones y los cambios de nombre de las listas enlazadas siguen la misma vía.
- La creación remota fallida se expone en la interfaz (texto de error en la playlist y aviso); ya no se presenta como sincronizada. Desde el menú de la lista se puede reintentar; si la lista existe remotamente, se reintenta añadir las canciones pendientes sin crear un duplicado.
- El método depende de un endpoint interno **no documentado de Spotify**, que puede cambiar. Sin las credenciales del usuario y sin probar en un dispositivo no es posible garantizar su aceptación.

## Menús
- Se han modificado **ambas implementaciones**: `PlayerOptionsSheet` (menú del reproductor) y `SongOptionsSheet` (menú de las canciones).
- Color carbón, esquinas redondeadas, iconos lineales de aspecto Spotify, opciones principales agrupadas y extras propios de Spotui conservados.
- Menús desplazables y altura máxima adaptable: la última opción y los elementos inferiores deben ser accesibles incluso en móviles pequeños y con letra grande.
- Se conserva compartir, listas, cola, álbum, artista, descargas, me gusta, fuente alternativa, limpieza de caché y temporizador.

## Verificación
- Comprobada la integridad del ZIP y la presencia de los cambios en código.
- No ha sido posible ejecutar la compilación de Gradle: el entorno no dispone de un SDK de Android ni puede descargar la distribución requerida de Gradle. No se ha realizado prueba real del arrastre ni operación de escritura con una cuenta Spotify.

## Comprobación en el móvil
1. Pon 30 canciones en la cola, mantén pulsado el asa de una del final, arrástrala hacia el borde superior y sostén: debe desplazar la lista sin saltos. Suelta y verifica la posición.
2. Crea una lista en Biblioteca; espera el estado «Sincronizada con Spotify», y comprueba en la aplicación oficial que aparece. Si da error, consulta el mensaje y prueba «Sincronizar con Spotify» desde el menú.
3. Abre los tres puntos tanto de una canción de playlist como del reproductor; desplaza el menú hasta abajo y verifica las últimas opciones.
