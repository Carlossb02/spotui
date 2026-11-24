# Reglas de Firebase para el reproductor y las novedades

`database.playback.rules.fragment.json` contiene únicamente las reglas para `rules.users.$uid.playback`. Integra ese subárbol en las reglas existentes de Realtime Database y conserva las reglas de autorización, presencia y migración que ya use el proyecto. El fragmento no es un archivo de despliegue completo.

Las reglas requieren que la cuenta esté autenticada, use su propio UID y esté autorizada en `authorizedUsers/$uid`. La transacción de cambio de dispositivo requiere lectura y escritura sobre `playback/session`. El aviso de novedades usa `playback/releaseChecks`, con cuatro marcas de tiempo que solo avanzan. Las reglas de un nodo padre pueden conceder permisos adicionales a todos sus descendientes. Revisa también esas reglas y valida con el simulador de Firebase para el UID autorizado, un UID ajeno y una cuenta sin autorización antes de publicar.

[Reglas de seguridad de Realtime Database](https://firebase.google.com/docs/database/security)
