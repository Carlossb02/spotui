package com.music.spotui.sync

import org.junit.Assert.*
import org.junit.Test

class DriveRequestFailureTest {
    private fun failure(reason:String,code:Int=403)=DriveRequestFailure.parse(code,"""{"error":{"errors":[{"reason":"$reason"}]}}""")
    @Test fun disabledApiRequiresProjectFixWithoutConsentLoop() {
        val error=failure("accessNotConfigured")
        assertTrue(error.stopsRetries);assertFalse(error.requiresConsent)
        assertTrue(error.message.orEmpty().contains("Google Drive API"))
    }
    @Test fun modernDisabledServiceDetailsAreRecognized() {
        val error=DriveRequestFailure.parse(403,"""{"error":{"status":"PERMISSION_DENIED","details":[{"reason":"SERVICE_DISABLED"}]}}""")
        assertTrue(error.stopsRetries);assertFalse(error.requiresConsent)
        assertTrue(error.message.orEmpty().contains("Google Drive API"))
    }
    @Test fun insufficientScopeRequestsAuthorizationInsteadOfGenericRejection() {
        listOf("insufficientPermissions","ACCESS_TOKEN_SCOPE_INSUFFICIENT").forEach {reason->
            val error=failure(reason);assertTrue(error.requiresConsent);assertFalse(error.stopsRetries)
        }
    }
    @Test fun expiredTokenRequestsAuthorization() {
        val error=DriveRequestFailure.parse(401,"");assertTrue(error.requiresConsent);assertFalse(error.stopsRetries)
    }
    @Test fun filePermissionsDoNotCauseRepeatedConsentPrompts() {
        val error=failure("insufficientFilePermissions");assertFalse(error.requiresConsent);assertTrue(error.stopsRetries)
    }
    @Test fun rateLimitRemainsRetryable() {
        listOf(403,429).forEach {code->val error=failure("rateLimitExceeded",code)
            assertFalse(error.requiresConsent);assertFalse(error.stopsRetries)}
    }
    @Test fun malformedRejectionStillHasSafeDiagnostic() {
        val error=DriveRequestFailure.parse(403,"<html>blocked</html>")
        assertTrue(error.stopsRetries);assertTrue(error.message.orEmpty().endsWith("[DRIVE_403:unknown]"))
    }
    @Test fun providerAccountIdsAndTokensAreNeverDisplayed() {
        val error=DriveRequestFailure.parse(403,"""{"error":{"message":"secret-access-token user@example.com","errors":[{"reason":"secretAccessToken12345"}]}}""")
        assertFalse(error.message.orEmpty().contains("secretAccessToken12345"));assertFalse(error.message.orEmpty().contains("secret-access-token"));assertFalse(error.message.orEmpty().contains("user@example.com"))
    }
    @Test fun serverFailureRemainsRetryable() {
        val error=DriveRequestFailure.parse(503,"");assertFalse(error.stopsRetries);assertFalse(error.requiresConsent)
    }
    @Test fun storageQuotaFailureStopsAutomaticRetries() {
        val error=failure("storageQuotaExceeded");assertTrue(error.stopsRetries);assertTrue(error.message.orEmpty().contains("espacio"))
    }
}
