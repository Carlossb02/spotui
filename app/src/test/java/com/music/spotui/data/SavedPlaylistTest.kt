package com.music.spotui.data
import com.music.spotui.data.entity.*
import org.junit.Assert.*
import org.junit.Test
class SavedPlaylistTest {
 private fun song(id:String,title:String="Title")=SongsModel(1,title,"Album","Artist","","spotify:track:$id",spotifyTrackId=id)
 @Test fun spotifyIdentityIncludesUriNormalization(){val p=SavedPlaylist("p","List",trackIds=setOf("abc"));assertTrue(p.contains(song("spotify:track:abc")));assertFalse(p.contains(song("other")))}
 @Test fun sameTitleAndHashCannotMatchDifferentSpotifyRecording(){val p=SavedPlaylist("p","List",extraSongs=listOf(song("abc")));assertFalse(p.contains(song("other")));assertTrue(p.contains(song("abc","Edited title")))}
 @Test fun localAndYoutubeTracksUseExactUrl(){val a=SongsModel().copy(id=4,url="yt_local:123",title="Title");val p=SavedPlaylist("p","List",extraSongs=listOf(a));assertTrue(p.contains(a.copy(title="Edited")));assertFalse(p.contains(a.copy(url="yt_local:456")))}
 @Test fun playerWithOnlyLocalSongIdCanFindItsLibraryEntry(){val p=SavedPlaylist("p","List",extraSongs=listOf(SongsModel().copy(id=6,url="file://song")));assertTrue(p.contains(SongsModel().copy(id=6)));assertFalse(p.contains(SongsModel()))}
 @Test fun emptyLibraryIsNotSaved(){assertFalse(SavedLibrarySnapshot().contains(song("abc")))}
 @Test fun snapshotIndexReflectsAddAndRemoveAcrossPlaylists() {
  val one=SavedPlaylist("one","One",trackIds=setOf("abc"))
  val two=SavedPlaylist("two","Two",trackIds=setOf("abc","def"))
  val snapshot=SavedLibrarySnapshot(listOf(one,two))
  assertTrue(snapshot.contains(song("spotify:track:abc")))
  val removedFromOne=snapshot.copy(playlists=listOf(one.copy(trackIds=emptySet()),two))
  assertTrue(removedFromOne.contains(song("abc")))
  val removedEverywhere=removedFromOne.copy(playlists=removedFromOne.playlists.map { it.copy(trackIds=it.trackIds-"abc") })
  assertFalse(removedEverywhere.contains(song("abc")))
  assertTrue(removedEverywhere.contains(song("def")))
  assertTrue(snapshot.contains(song("abc")))
 }
}
