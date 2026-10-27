# Spotui — cambios de interfaz y descargas (25/10/2026)

1. Dock: degradado inferior reforzado **solo en móvil**; tablet conserva su color original.
2. Reproductor: tick de canción guardada en verde Spotify (#1ED760).
3. Cola de reproducción: gesto de reordenación gestionado por el contenedor estable de la lista, desplazamiento automático continuado cerca de sus límites, y previsualización flotante del elemento mientras se arrastra.
4. Biblioteca: el índice de canciones/colecciones descargadas se calcula en un dispatcher IO, sin deserializar las carátulas ni recalcular la lista de descargas para cada elemento filtrado.
5. Historial de escucha: acceso trasladado a Ajustes > Datos y aplicación.
6. Biblioteca: entrada "Downloaded" oculta salvo al seleccionar el filtro "Descargado"; entrada especial "Archivos locales" eliminada de biblioteca (acceso conservado en Ajustes).
7. Iconos: indicador verde de canción descargada debajo del título / delante del artista en los listados principales; se actualiza al terminar descargas individuales o por lotes y cuando se eliminan.

## Pruebas
- Archivos fuente revisados y empaquetados.
- **Compilación Android no ejecutada**: no hay Android SDK configurado en este entorno. Abrir con Android Studio para sincronizar Gradle y ejecutar pruebas reales en móvil y tablet.
- Se han omitido del ZIP las carpetas `build`, `.gradle`, `.git`, `.idea` y `.artifacts` del ZIP original: son artefactos generados o historial, no necesarios para abrir y compilar el proyecto.

## Corrección posterior: arrastre y cola de descargas
- Cola: el gesto desde el asa de reordenar se reconoce en el pase inicial del puntero del contenedor LazyColumn, antes de que el desplazamiento o el swipe de la fila consuman el movimiento. Al mover el dedo hasta los extremos continúa el auto-scroll; se conserva la vista flotante.
- Descargas: se registra todo el lote como tareas pendientes desde el principio, limitado a dos transferencias activas. Un StateFlow publica estado (en espera/descargando), porcentaje y el orden completo. La pantalla muestra cancelar por canción y cancelar todos los pendientes.
- Finalizadas: la pantalla observa la señal downloadsVersion de los archivos guardados y carga la lista desde Dispatchers.IO; desaparece el sondeo por tamaño de una instantánea incompleta.
- Cancelación: se cancela el Job de la tarea en espera o activa, se interrumpe la conexión HTTP si existe y se limpian los ficheros parciales, sin reintentos tras la cancelación.

### Verificación pendiente en Android
- Arrastrar desde el asa en la cola por encima y por debajo de la ventana visible sin soltar. Comprobar también scroll normal, pulsación y gesto lateral para eliminar.
- Descargar una playlist larga y comprobar que todas las canciones en espera aparecen en la cola, que se actualizan las terminadas y que cancelar una/all detiene las tareas seleccionadas.
- No se puede confirmar una compilación o pruebas instrumentadas en este entorno sin Android SDK.
