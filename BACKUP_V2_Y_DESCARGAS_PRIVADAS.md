# Spotui — Backup v2 y descargas privadas

## Qué se conserva

- Las playlists locales con sus canciones y orden; canciones favoritas y ajustes que ya incluía el formato v1.
- Las playlists externas marcadas como favoritas (referencias de solo lectura, no copias editables).
- La organización de álbumes/playlists descargados y los metadatos de cada canción que estaba descargada.
- Las descargas que quedan pendientes de recuperar siguen estando en los backups posteriores hasta que se descarguen o se descarten.

## Compatibilidad con archivos antiguos

- `BackupHelper.validateBackupJson` acepta **versiones 1 y 2**.
- Todos los campos nuevos de v2 son opcionales al importar; un JSON v1 continúa restaurándose.
- Los archivos antiguos no contienen información que no se grababa en v1: no es posible recuperar favoritos externos o descargas desde esos JSON si no estaban ya almacenados en el dispositivo.

## Dónde se guarda la música

- **Audio para escucha sin conexión:** `context.filesDir/downloads/<songId>.(m4a|flac|mp3)`.
- **Carátulas privadas:** `context.filesDir/downloads/`.
- NO se guarda música offline en `cacheDir` ni en la carpeta pública `Music/` (salvo si el usuario usa expresamente «Export to Music», que crea una copia independiente fuera de la app).
- Una actualización normal con el mismo identificador de paquete y firma conserva `filesDir`.
- Una desinstalación completa elimina los archivos privados de Spotui. Las reglas de copia automática de Android excluyen `downloads/` para evitar restaurar el audio al reinstalar.

## Qué contiene exactamente el backup

- **JSON v2:** metadatos e identificadores, no bytes de audio. No guarda rutas privadas absolutas reutilizables de otro dispositivo.
- **Restauración en el mismo dispositivo:** si el archivo privado sigue presente y es válido, se recupera el registro como descargado.
- **Restauración en otro dispositivo:** las canciones no se muestran falsamente como reproducibles offline. Aparecen en `Downloaded > Recover from backup`, con botones individuales o «Download all» para volver a descargarlas expresamente.
- La recuperación depende de que la canción siga disponible en la fuente configurada. No se inicia automáticamente para no consumir datos móviles sin intervención del usuario.

## Borrado desde Spotui

- «Remove download» borra físicamente todos los formatos privados relacionados con esa canción, además de portada y registro.
- Si Android impide eliminar algún archivo, Spotui conserva el registro y muestra un error en el menú, en lugar de informar falsamente de un borrado correcto.
- «Clear all» cancela descargas activas, elimina los ficheros offline y limpia las referencias pendientes si la eliminación fue completa.

## Copias automáticas

Con la copia automática habilitada y una carpeta de copia configurada, las modificaciones de playlists, favoritos externos y descargas programan una actualización del JSON agrupada a los pocos segundos.

## Pruebas recomendadas en un dispositivo real

1. Crear playlist local y marcar una ajena como favorita; hacer backup v2, restaurarlo y comprobar ambos elementos.
2. Importar un backup v1 y verificar que se conservan playlists, favoritos de canciones y ajustes.
3. Descargar audio, actualizar la APK sin borrar datos y comprobar reproducción offline.
4. Eliminar una canción desde Spotui, comprobar que desaparece del listado y que se elimina su archivo privado.
5. Restaurar el JSON v2 en otro móvil: comprobar «Recover from backup» y descargar explícitamente una canción.
6. Desinstalar completamente y reinstalar: comprobar que el audio offline anterior no reaparece por Auto Backup.
7. Exportar una canción a `Music/spotui`: comprobar que es una copia explícita que permanece fuera de la app.

**Limitación de validación:** se han revisado los archivos Kotlin, las reglas XML y la integridad ZIP, pero no ha sido posible ejecutar pruebas instrumentadas Android ni compilar una APK en este entorno.
