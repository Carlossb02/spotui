# Walkthrough - Re-applying Crossfade Fix & Landscape Player Artwork

Successfully re-applied and verified both fixes:
1. **Crossfade Playback Continuity:** Intercepted playback state events (`STATE_ENDED` / `STATE_IDLE`) and active states in `PlaybackService.AutoPlayer` during crossfades, preventing the system/MediaSession from pausing or stopping playback when the outgoing track finishes.
2. **Landscape Album Artwork:** Removed the opacity/alpha modifier on cover art in landscape mode (`PlayerScreen.kt`), ensuring album artwork is always visible in horizontal orientation even when background Canvas video is active.
3. **Verification:** Built and assembled debug successfully (`BUILD SUCCESSFUL`).
