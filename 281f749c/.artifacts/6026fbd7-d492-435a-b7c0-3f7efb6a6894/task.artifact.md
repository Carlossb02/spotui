# Task List - Switch Podcast Source from Spotify to YouTube (YT)

- [ ] Update `PodcastProvider.kt` to use YouTube (`YouTube.search`, `PodcastItem`, `EpisodeItem`) as the primary podcast source and ensure 1:1 playable YouTube video/audio URLs.
- [ ] Update `Api.kt` to remove Spotify podcast retrieval (`show` and `episode` types in `Spotify.search`, `Spotify.show`, `Spotify.showEpisodes`) and delegate podcast search/shows to `PodcastProvider`.
- [ ] Verify search screen and home podcasts tab functionality.
- [ ] Create walkthrough artifact.
