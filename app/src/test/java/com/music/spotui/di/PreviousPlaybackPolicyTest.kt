package com.music.spotui.di
import org.junit.Assert.*
import org.junit.Test
class PreviousPlaybackPolicyTest {
 @Test fun firstSongRestartsWhenRepeatIsOff(){assertEquals(0,previousPlaybackIndex(0,5,0,false))}
 @Test fun firstSongRestartsAfterSeveralSeconds(){assertEquals(0,previousPlaybackIndex(0,5,9_000,false))}
 @Test fun laterSongRestartsAfterThreeSeconds(){assertEquals(3,previousPlaybackIndex(3,5,3_000,false))}
 @Test fun laterSongMovesBackBeforeThreeSeconds(){assertEquals(2,previousPlaybackIndex(3,5,2_999,false))}
 @Test fun repeatedTapAfterRestartCanMoveBack(){val restart=previousPlaybackIndex(3,5,5_000,false)!!;assertEquals(2,previousPlaybackIndex(restart,5,0,false))}
 @Test fun repeatAllWrapsOnlyNearTheBeginning(){assertEquals(4,previousPlaybackIndex(0,5,500,true));assertEquals(0,previousPlaybackIndex(0,5,5_000,true))}
 @Test fun oneSongQueueAlwaysRestarts(){assertEquals(0,previousPlaybackIndex(0,1,0,false));assertEquals(0,previousPlaybackIndex(0,1,8_000,true))}
 @Test fun emptyQueueHasNoAction(){assertNull(previousPlaybackIndex(0,0,0,false))}
}
