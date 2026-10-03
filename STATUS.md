# LocalTracklist Status

## Current Development Status

LocalTracklist, formerly LibrePlayer, is a local music player for Android published by quietswitch. LibrePlayer `v1.1.0` is published from commit `28d9b63c2960560f62fb2fe45937a6c0956c2d13`; that verified release tree is the base of this controlled rebrand. The rebrand retains `versionName` `1.1.0` and `versionCode` 6 for independent review. A future LocalTracklist release version will be decided separately.

## What Is Working

- Incremental MediaStore and optional SAF library synchronization, with manual full rebuild
- Source-scoped library provenance with explicit Room v1-to-v2 migration
- Core local library browsing with additions, changes, and removals reconciled on rescan
- Deterministic library identity, grouping, ordering, search, metadata fallback, and artwork behavior
- M3U/M3U8 playlist import and export through local-only Android document access
- Playback, queueing, seeking, transitions, shuffle, repeat, audio focus, background controls, and restoration
- Local playlists, favorites, and recently played
- Audio Details for technical metadata and tags
- In-app permission request flow with retry and settings fallback
- Packaged Baseline Profile and reusable benchmark/performance authority

## What Is Partially Implemented

- Q3.7 storage provenance is implemented and has passed its recorded closeout authorities, but remains open pending the explicitly deferred removable-volume and LARGE authority
- Artwork is representative for albums and artists rather than deeply curated
- Permission UX is robust for normal and stricter devices, but still depends on Android OEM behavior
- Codec and container support depends on Android's platform codecs and the device

## What Is Not Implemented

- Folder-view library browsing
- Streaming or remote-library features
- Lyrics, casting, or smart recommendations
- A broad instrumentation/UI suite beyond the focused migration, playback, storage, and performance authorities
- Future Room schema migrations beyond schema v2

## Highest Current Risks

- The deferred local Q3.7 removable-volume/LARGE instrumentation apparatus is not part of the 1.1.0 release tree; it still targets the removed Songs destination and must be revised before any future Q3.7 continuation
- Q3.7 removable-volume and LARGE authority remain deferred
- OEM-specific permission and storage behavior can still vary beyond app control
- Album and artist artwork remains representative, not canonical

## Last Meaningful Milestone Reached

Q1.1 Performance Authority and Q2 Playback Mastery are closed. Q3.1 through Q3.6 have accepted library authorities. Q3.7 adds source-scoped storage provenance and the Room v1-to-v2 migration; its implementation and recorded closeout are preserved, but Q3.7 remains open at the deferred storage-authority boundary.

## Next Recommended Step

Review the controlled LocalTracklist rebrand against the published 1.1.0 source tree. The existing core verification must still pass 199 unit tests with zero failures/skips, lint, debug/release assembly, Android-test assembly, and benchmark assembly. Preserve `com.libreplayer`, data identities, the permanent signing key/certificate, and the accepted test authorities. No release signing, publication, repository rename, or version bump is part of this rebrand; published tags and historical artifacts remain unchanged. The repository-native `Release verification` workflow remains the authority for future release-commit checks and artifact evidence.

## Benchmark Foundation

The repository includes the accepted Q1.1 benchmark and performance authority, deterministic fixtures, controlled reference comparisons, and packaged Baseline Profile. Historical `v1.0.4` reference artifacts remain immutable and continue to serve as the pre-1.1.0 comparison baseline.
