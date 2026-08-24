# Changelog

## 1.0.4 (versionCode 5) — Unreleased

- Provides offline, local-first library browsing and playback for device audio.
- Includes songs, albums, artists, playlists, favorites, history, search, queue controls, background playback, and playback-state restoration.
- Adds incremental MediaStore and optional SAF library synchronization for additions, changes, and removals, with periodic and on-demand full reconciliation.
- Fixes SAF rescans so unchanged imported files can reuse cached metadata without dropping imported songs.
- Preserves cached library data when media permission is temporarily unavailable.
- Improves restored-file playback recovery, playback error reporting, and playback snapshot restoration.
- Repairs release blockers affecting Android 8–9 MediaStore compatibility, backup exclusions, menu actions, and playlist-delete confirmation.
- Removes an unused dependency-contributed network-state permission; LibrePlayer does not request Internet access.
- Includes audio details, artwork handling, local settings, and debug-only playback-pipeline diagnostics; release playback remains on Media3's stable default output path.
- Improves library deduplication and permission recovery behavior.
- Corrects the multiline Now Playing layout so longer metadata is presented consistently.
- Adds release, privacy, permission, build, and verification documentation for pseudonymous distribution.
