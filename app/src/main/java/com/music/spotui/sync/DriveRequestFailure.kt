package com.music.spotui.sync

import org.json.JSONObject

/** Parse only machine-readable reasons. Never display provider payloads, account IDs or tokens. */
internal class DriveRequestFailure private constructor(
    val statusCode:Int,val reason:String,val requiresConsent:Boolean,val stopsRetries:Boolean,message:String
):IllegalStateException(message) {
    companion object {
        fun parse(code:Int,body:String):DriveRequestFailure {
            val error=runCatching {JSONObject(body).optJSONObject("error")}.getOrNull()
            val reasons=linkedSetOf<String>()
            listOf("errors","details").forEach {key->error?.optJSONArray(key)?.let {rows->
                for(i in 0 until rows.length())rows.optJSONObject(i)?.optString("reason")?.takeIf {it.isNotBlank()}?.let(reasons::add)
            }}
            val disabled=reasons.any {it in setOf("accessNotConfigured","SERVICE_DISABLED","API_DISABLED","SERVICE_NOT_ACTIVATED")}
            val consent=code==401 || !disabled && reasons.any {it in setOf("insufficientPermissions","ACCESS_TOKEN_SCOPE_INSUFFICIENT","authError","invalidCredentials")}
            val throttled=code==429 || reasons.any {it in setOf("rateLimitExceeded","userRateLimitExceeded","RATE_LIMIT_EXCEEDED")}
            val knownReasons=setOf("accessNotConfigured","SERVICE_DISABLED","API_DISABLED","SERVICE_NOT_ACTIVATED",
                "insufficientPermissions","ACCESS_TOKEN_SCOPE_INSUFFICIENT","authError","invalidCredentials",
                "rateLimitExceeded","userRateLimitExceeded","RATE_LIMIT_EXCEEDED","storageQuotaExceeded",
                "domainPolicy","DOMAIN_POLICY","appNotAuthorizedToFile","insufficientFilePermissions",
                "forbidden","dailyLimitExceeded","notFound","backendError","badRequest")
            val reason=reasons.firstOrNull {it in knownReasons} ?: "unknown"
            val text=when {
                disabled->"Google Drive API no está habilitada en el proyecto de esta app. El propietario debe habilitarla en Google Cloud y después pulsar Reintentar conexión con Drive."
                consent->"El permiso de Google Drive ha caducado o es insuficiente. Pulsa Autorizar Google Drive y acepta el permiso para los datos de Spotui."
                throttled->"Google Drive ha limitado temporalmente las peticiones. La sincronización se reintentará más tarde."
                "storageQuotaExceeded" in reasons->"La cuenta no tiene espacio disponible en Google Drive. Libera espacio y pulsa Reintentar conexión con Drive."
                "domainPolicy" in reasons || "DOMAIN_POLICY" in reasons->"La organización de esta cuenta bloquea el acceso a Drive. Consulta con su administrador."
                "appNotAuthorizedToFile" in reasons || "insufficientFilePermissions" in reasons->"Esta instalación no tiene permiso para los datos de sincronización. Comprueba que los dispositivos usen la misma cuenta y el mismo proyecto de Google."
                code==403->"Google Drive ha rechazado el acceso. Comprueba los permisos de la cuenta y la configuración del proyecto."
                else->"No se pudo sincronizar con Google Drive."
            }
            return DriveRequestFailure(code,reason,consent,!consent && !throttled && code in setOf(400,403),"$text [DRIVE_$code:$reason]")
        }
    }
}
