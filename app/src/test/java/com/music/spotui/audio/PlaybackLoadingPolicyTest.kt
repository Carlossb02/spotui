package com.music.spotui.audio
import org.junit.Assert.*
import org.junit.Test
class PlaybackLoadingPolicyTest {
 @Test fun preloadingWaitsForForegroundButCanRunBeforeFirstPlay(){assertTrue(PlaybackLoadingPolicy.canPreload(false,false,false));assertTrue(PlaybackLoadingPolicy.canPreload(false,true,true));assertFalse(PlaybackLoadingPolicy.canPreload(false,true,false));assertFalse(PlaybackLoadingPolicy.canPreload(true,false,false));assertFalse(PlaybackLoadingPolicy.canPreload(true,true,true))}
 @Test fun fastPathRequiresEveryIdentityGate(){
  fun match(flags:List<Boolean>,stage:Int=1,duration:Int?=2,official:Boolean=true,upload:Boolean=false)=PlaybackLoadingPolicy.exactCandidate(stage,flags[0],flags[1],flags[2],flags[3],flags[4],flags[5],duration,official,upload)
  val flags=List(6){true};assertTrue(match(flags));assertTrue(match(flags,duration=0))
  for(i in flags.indices){assertFalse("gate $i",match(flags.mapIndexed{j,v->if(j==i)false else v}))}
  assertFalse(match(flags,stage=2));assertFalse(match(flags,duration=null));assertFalse(match(flags,duration=3));assertFalse(match(flags,duration=-1));assertFalse(match(flags,official=false));assertFalse(match(flags,upload=true))
 }
}
