# Spotui v4 — optimización conservadora (base v3 estable)

## Comparación con Spotui-actualizado-v2 (rápido pero inestable)

La versión de estudio redujo varias esperas y ejecutaba búsquedas especulativas en paralelo, pero también cambió la selección/validación de formatos YouTube y utilizó `MergingMediaSource` en el mismo reproductor de audio para habilitar vídeo (`setMediaSource` + `prepare`), operación que reinicia la fuente y no permite alternar instantáneamente. Esos cambios de mayor riesgo NO se trasplantan.

## Cambios implementados

1. **Música — coincidencia intacta:** métodos `resolveVideoCandidates`, `resolveYtPlayback` y bloque de orden/prioridades de proveedores idénticos a v3. El resolutor YT de audio también es idéntico. Siete normalizaciones deterministas (títulos, artistas, álbumes y banderas de versión) reutilizan resultados con memoria LRU acotada; no modifican criterios ni orden de candidatos.
2. **Evitar recuperación de URLs vencidas:** la caché en RAM comprueba la expiración conocida de las URLs CDN con 30 s de margen, en lugar de devolver una URL caducada a Media3.
3. **Audio podcast — rápido al volver a un episodio:** la fuente temporal del mismo ID y calidad se reutiliza solo mientras su firma siga vigente. Se comprueba identidad de episodio, nivel de calidad y tiempo de caducidad. Si no coincide, se usa el procedimiento original.
4. **Vídeo podcast — menos competencia:** dos clientes de vídeo se consultan en paralelo y el tercero solo se emplea si ambos fallan. La ventana de espera para calidades secundarias baja de 400 a 180 ms. La resolución audiovisual y el reloj de audio siguen separados: nunca se prepara/reinicia el audio para mostrar vídeo.
5. **Vistas previas:** reutilizan la caché de formatos del episodio o metadatos verificados del resolutor de audio; ya no lanzan consultas redundantes cuando hay fuentes recientes. Las URLs de vídeo de vista previa dejan de persistir durante 10 min y siguen la caducidad prudente de 90 s.
6. **Vídeo continuo:** un poco más de buffer después de pausas de red (9–35 s; umbral de inicio 0,8 s y rebúfer 1,5 s), sin bucles de seek ni alteraciones de velocidad o audio.

## Validación realizada

- Comparación automatizada: selección de candidatos, ranking de proveedores, `resolveYtPlayback`, y `YTPlayerUtils` idénticos a la v3.
- Compilación JVM de la utilidad de memoización y prueba de concurrencia / evicción LRU: OK.
- Comprobación de integridad de ZIP.

**Sin validación end-to-end:** este entorno no tiene Android SDK ni dispositivo para compilar el APK, probar desincronización o medir latencias reales. Por ello no se garantiza que la primera resolución remota sea instantánea. Verificar en dispositivo con Logcat `SpotuiStartup`, `SpotuiPodcastVideo`, `SongPlayer`; probar canciones frías/FLAC, podcast nuevo y repetido, alternancia vídeo, seek, PiP y navegación rápida entre episodios.

## Instalación

Para mantener todos los recursos del proyecto original, es preferible sustituir los **tres archivos** del ZIP de cambios en la base v3 que ya funciona. El ZIP de fuentes es de consulta y excluye tipografías y outputs de build del proyecto original.
