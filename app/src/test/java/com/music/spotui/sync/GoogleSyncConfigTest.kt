package com.music.spotui.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GoogleSyncConfigTest {
    private fun configuration():JSONObject {
        fun client(name:String,id:String)=JSONObject()
            .put("client_info",JSONObject().put("mobilesdk_app_id",id).put("android_client_info",JSONObject().put("package_name",name)))
            .put("api_key",JSONArray().put(JSONObject().put("current_key","public-test-identifier")))
            .put("oauth_client",JSONArray().put(JSONObject().put("client_type",3).put("client_id","web-test-client")))
        return JSONObject().put("project_info",JSONObject().put("project_id","test-project")
            .put("firebase_url","https://test-project-default-rtdb.europe-west1.firebasedatabase.app/"))
            .put("client",JSONArray().put(client("com.music.spotidroid","release-app"))
                .put(client("com.music.spotidroid.debug","debug-app")))
    }
    @Test fun eachInstalledVariantUsesItsOwnAppRegistration() {
        val raw=configuration().toString()
        assertEquals("release-app",GoogleSyncConfig.fromJson(raw,"com.music.spotidroid").appId)
        assertEquals("debug-app",GoogleSyncConfig.fromJson(raw,"com.music.spotidroid.debug").appId)
    }
    @Test fun unknownVariantReportsTheMissingPackageInsteadOfClaimingAnOldVersion() {
        val error=assertThrows(GoogleSyncConfig.ConfigurationException::class.java) {GoogleSyncConfig.fromJson(configuration().toString(),"com.music.other")}
        assertEquals("GOOGLE_PACKAGE_MISSING",error.code)
    }
    @Test fun missingWebClientHasASpecificActionableCode() {
        val root=configuration();root.getJSONArray("client").getJSONObject(0).put("oauth_client",JSONArray())
        val error=assertThrows(GoogleSyncConfig.ConfigurationException::class.java) {GoogleSyncConfig.fromJson(root.toString(),"com.music.spotidroid")}
        assertEquals("GOOGLE_WEB_CLIENT_MISSING",error.code)
    }
    @Test fun endpointIsNormalizedWithoutChangingTheFirebaseAppIdentity() {
        val config=GoogleSyncConfig.fromJson(configuration().toString(),"com.music.spotidroid")
        assertFalse(config.databaseUrl.endsWith('/'))
    }
    @Test fun endpointCannotCarryCredentialsOrPointToAnotherService() {
        listOf("http://test.firebaseio.com","https://user:password@test.firebaseio.com","https://example.org").forEach {url->
            val root=configuration();root.getJSONObject("project_info").put("firebase_url",url)
            assertThrows(IllegalArgumentException::class.java) {GoogleSyncConfig.fromJson(root.toString(),"com.music.spotidroid")}
        }
    }
    @Test fun adminKeysAreNotAcceptedAsAndroidConfiguration() {
        val raw=configuration().put("private_key","not-a-real-key").toString()
        assertThrows(IllegalArgumentException::class.java) {GoogleSyncConfig.fromJson(raw,"com.music.spotidroid")}
    }
    @Test fun internalExceptionDetailsAreNotExposedInTheVisibleMessage() {
        val message=GoogleSyncConfig.failureMessage(IllegalStateException("private-diagnostic-value"))
        assertFalse(message.contains("private-diagnostic-value"));assertTrue(message.contains("GOOGLE_INIT_IllegalStateException"))
    }
}
