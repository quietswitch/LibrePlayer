# Status

## Current Development Status

Version `1.0.4` (`versionCode` 5) is the current release candidate. Permanent signing and public distribution remain external human steps.

## What Is Working

- Incremental MediaStore and optional SAF library synchronization, with manual full rebuild
- Core local library browsing with additions, changes, and removals reconciled on rescan
- Playback, queueing, shuffle, repeat, and background controls
- Local playlists, favorites, and recently played
- Search across songs, albums, and artists
- Audio Details for technical metadata and tags
- Song, mini-player, now playing, album, and artist artwork display
- In-app permission request flow with retry and settings fallback
- Playback error reporting and playlist-delete confirmation

## What Is Partially Implemented

- Artwork is representative for albums and artists rather than deeply curated
- Permission UX is robust for normal and stricter devices, but still depends on Android OEM behavior
- Codec and container support depends on Android's platform codecs and the device

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
- API 26–28 behavior is covered by compatibility-focused unit tests and lint, but still merits a physical-device or emulator smoke test

## Last Meaningful Milestone Reached

Version `1.0.4` release candidate: stable local playback, incremental library synchronization, corrected SAF rescans, Audio Details, deduped library rows, working artwork across primary surfaces, permission recovery, and playback-state restoration.

## Next Recommended Step

After the pre-publication verification is accepted, create the permanent pseudonymous release key outside the repository, then sign and verify the `1.0.4` APK according to `RELEASE.md`.
