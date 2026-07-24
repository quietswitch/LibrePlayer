# Status

## Current Development Status

Version `1.0.4` release preparation complete; public distribution requires external APK signing.

## What Is Working

- Core local library scan and browsing
- Playback, queueing, shuffle, repeat, and background controls
- Local playlists, favorites, and recently played
- Search across songs, albums, and artists
- Audio Details for technical metadata and tags
- Song, mini-player, now playing, album, and artist artwork display
- In-app permission request flow with retry and settings fallback

## What Is Partially Implemented

- Artwork is representative for albums and artists rather than deeply curated
- Permission UX is robust for normal and stricter devices, but still depends on Android OEM behavior

## What Is Not Implemented

- Folder view
- Streaming or remote-library features
- Lyrics, casting, or smart recommendations
- Rich instrumentation/UI test suite
- Explicit Room migrations for future schema evolution

## Highest Current Risks

- Future schema changes can still force destructive migration if not addressed
- OEM-specific permission behavior can still vary beyond app control
- Album and artist artwork remains representative, not canonical

## Last Meaningful Milestone Reached

Version `1.0.4`: stable local playback, Audio Details, deduped library rows, working artwork across primary surfaces, improved in-app permission recovery, and corrected multiline Now Playing layout.

## Next Recommended Step

Sign and verify the `1.0.4` release APK using private credentials stored outside the repository.
