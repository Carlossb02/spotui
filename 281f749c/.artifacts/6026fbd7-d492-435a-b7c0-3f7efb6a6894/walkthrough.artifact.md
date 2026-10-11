# Walkthrough - Switch Podcast Source from Spotify to YouTube (YT)

Fixed all errors in `PodcastProvider.kt`, ensuring clean compilation and strict separation of YouTube podcasts (`PodcastItem`, `EpisodeItem`) from songs (`SongItem`), while keeping music searching on Spotify untouched.

## Changes

### Podcast Provider

#### [PodcastProvider.kt](file:///C:/Users/talib/AndroidStudioProjects/Spotui/app/src/main/java/com/music/spotui/providers/PodcastProvider.kt)
- Cleaned up all references to non-existent InnerTube methods or filters, using valid `YouTube.SearchFilter.FILTER_SONG` and `FILTER_VIDEO`.
- Strictly filtered search and episode retrieval to accept only `PodcastItem` (shows) and `EpisodeItem` (episodes), entirely ignoring `SongItem` (music tracks).
- Ensured 1:1 direct YouTube playback URLs for podcast episodes (`https://www.youtube.com/watch?v=<videoId>`).

## Verification Results

### Automated Build
- Ran `app:assembleDebug`: **Build finished successfully** with 0 errors and 0 warnings.
