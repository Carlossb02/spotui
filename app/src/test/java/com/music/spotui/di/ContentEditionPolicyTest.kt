package com.music.spotui.di

import org.junit.Assert.*
import org.junit.Test

class ContentEditionPolicyTest {
    private fun rank(title:String,badge:Boolean=false,album:String?="Don't Leave")=
        ContentEditionPolicy.rank(true,badge,title,album,"Don't Leave","Don't Leave")
    @Test fun explicitSpotifyRecordingRejectsDeclaredCleanEditions() {
        for(title in listOf("Don't Leave (Clean)","Don't Leave [Clean Version]","Don't Leave - Clean","Don't Leave (Censored)","Don't Leave (Clean Audio)"))
            assertEquals(title,ContentEditionPolicy.REJECT_CLEAN,rank(title))
        assertEquals(ContentEditionPolicy.REJECT_CLEAN,rank("Don't Leave",album="Don't Leave (Clean)"))
    }
    @Test fun titleMarkersOverrideContradictoryExplicitBadges() {
        assertEquals(ContentEditionPolicy.REJECT_CLEAN,rank("Don't Leave (Clean)",badge=true))
    }
    @Test fun unmarkedVideosRemainAvailableWhenEditionCannotBeDetermined() {
        assertEquals(ContentEditionPolicy.UNKNOWN,rank("Don't Leave (Official Video)"))
    }
    @Test fun missingBadgeDoesNotRejectAnUncensoredUpload() {
        for(title in listOf("Don't Leave (Explicit)","Don't Leave (Uncensored)","Don't Leave (Not Censored)"))
            assertEquals(title,ContentEditionPolicy.LABEL_EXPLICIT,rank(title))
    }
    @Test fun verifiedExplicitEvidenceWinsBeforeDurationStageTiebreaks() {
        data class Candidate(val edition:Int,val stage:Int)
        val explicit=Candidate(ContentEditionPolicy.BADGE_EXPLICIT,6)
        val unknown=Candidate(ContentEditionPolicy.UNKNOWN,1)
        assertEquals(listOf(explicit),ContentEditionPolicy.preferred(listOf(unknown,explicit)){it.edition})
    }
    @Test fun noCleanCandidateSurvivesTheLastResortFilter() {
        assertTrue(ContentEditionPolicy.preferred(listOf("Don't Leave (Clean)","Don't Leave (Censored)")){rank(it)}.isEmpty())
    }
    @Test fun genuineTitlesContainingCleanAreNotTreatedAsEditionMarkers() {
        assertEquals(ContentEditionPolicy.BADGE_EXPLICIT,
            ContentEditionPolicy.rank(true,true,"Clean",null,"Clean",null))
    }
    @Test fun unflaggedSpotifyTracksKeepExistingPreferenceOrder() {
        for(want in listOf(false,null)) {
            assertEquals(0,ContentEditionPolicy.rank(want,true,"Don't Leave (Explicit)",null,"Don't Leave",null))
            assertEquals(0,ContentEditionPolicy.rank(want,false,"Don't Leave (Clean)",null,"Don't Leave",null))
        }
    }
}
