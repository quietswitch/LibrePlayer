# LibrePlayer Library Semantics

This document is the Q3.1 authority for local-library identity, metadata, and grouping. It describes the current Room schema (version 1) and ingestion behavior. It does not introduce a schema migration.

## Physical and logical song identity

One `Song` row represents one playable local source occurrence. Two different files remain two songs even when their filenames, tags, durations, or audio bytes are identical. Metadata equality is never sufficient evidence that two occurrences are the same file.

The stable song ID is source-namespaced:

- MediaStore: `media:<MediaStore.Audio.Media._ID>`. The appended MediaStore row URI is the playback locator. `ALBUM_ID` is artwork lookup data only. MediaStore version and generation values are synchronization checkpoints, not song identity.
- Storage Access Framework (SAF): `document:<document URI>`. Provider authority plus tree/document identity belongs to the source occurrence. The document URI is also the playback locator; an imported tree URI identifies scan scope, not the song.

A move or provider identity change can therefore appear as deletion of one occurrence and insertion of another. The app does not claim cross-move identity continuity.

Cross-source deduplication is deliberately conservative. MediaStore and SAF observations merge only when a canonical physical path can be established; MediaStore wins as the durable occurrence and missing metadata may be filled from the SAF observation. Otherwise they remain distinct by source ID. Primary shared-storage spellings are normalized so `primary:Music/...` and `Music/...` can match. Non-primary volume identifiers remain in the key, so matching relative paths on different volumes do not collapse. Paths are compared case-insensitively as an Android shared-storage interoperability rule; no metadata-only or fuzzy fallback is used.

## Identity versus presentation

Raw nullable title, artist, and album values are retained in `SongEntity`. Presentation fallbacks are derived:

- title: filename without its final extension;
- artist: `Unknown artist`;
- album: `Unknown album`.

These fallback labels and normalized sort/group keys do not replace the stored tag values and never change song identity. A literal source tag such as `Unknown Artist` remains source metadata; it is not silently converted to missing data.

Grouping normalization is limited to trimming surrounding whitespace and `Locale.US` lowercase. It is deterministic but not fuzzy: punctuation, abbreviations, featured-artist text, and `and` versus `&` remain distinct.

## Albums, artists, and compilations

An album group key is normalized album title plus grouping artist. The grouping-artist rule is album artist when available, otherwise track artist. This keeps equal album titles by unrelated artists separate and allows a genuine shared album artist such as `Various Artists` to unify compilation tracks.

The current version-1 core row does not retain album-artist or compilation tags. Consequently the active repository uses track artist as the documented fallback. A multi-artist album is not guessed to be a compilation, and tracks with different track artists currently form separate albums unless their stored artist values are the same (for example, a literal `Various Artists`). Adding album-artist/compilation authority requires an explicit future schema and migration, not a Q3.1 heuristic.

Artist groups use normalized track artist. Album membership does not change a song's artist identity. Missing artists share the presentation group `Unknown artist`.

Album IDs and artist IDs remain deterministic presentation/grouping keys. Playlist membership, favorites, recent-play history, and playback queue restoration reference song IDs, never album or artist grouping keys.

## Track, disc, year, genre, and folder semantics

MediaStore's encoded track field is decoded as `disc * 1000 + track`. Only positive components are retained: `0` is missing, `1000` means disc 1 with unknown track, and `2007` means disc 2 track 7. Retriever ordinals accept either `n` or `n/total`; malformed, zero, and negative values remain null. Duplicate track numbers are allowed. Library ordering is disc, then track, with title/ID tie-break behavior owned by the existing sort layer; numbering never defines song identity.

The core model stores year precision only. A positive four-digit-or-less integer is accepted; a full date is not truncated or inferred. Genre is not retained in the core library row. Audio Details may display retriever-provided album artist and genre as raw presentation metadata, but those fields do not currently affect grouping or identity. Multi-value genre parsing is therefore deferred.

Folder information means the containing source-relative directory, not an assumed absolute filesystem path:

- MediaStore uses `RELATIVE_PATH` where the platform exposes it.
- SAF derives a provider/document-relative parent representation.

LibrePlayer currently has no authoritative folder entity or folder-browsing screen. Folder strings aid display and conservative cross-source matching; they do not define a portable global identity.

## Persistence and synchronization

Rescans upsert by song ID and preserve the favorite bit for an existing ID. Removed source IDs remove the song row and rebuild album/artist aggregates through the existing synchronization transaction. Playlists store song IDs, recent plays store song IDs, and playback snapshots restore source occurrence IDs. The Q3.1 changes do not alter any of those IDs, tables, foreign-key policies, or database version.

## Q3.1 coverage matrix

| Area | Current authority | Classification |
| --- | --- | --- |
| MediaStore song identity | Source namespace + audio row `_ID`; content URI locator | Implemented and tested |
| SAF song identity | Source namespace + document URI/provider identity | Implemented and tested |
| Same physical file through MediaStore and SAF | Canonical primary-storage path; MediaStore preferred | Implemented and tested |
| Distinct files with identical metadata | Never metadata-deduplicated | Corrected and tested |
| Same path on separate storage volumes | Non-primary volume token preserved | Corrected and tested |
| Title/artist/album fallbacks | Derived presentation only; raw nulls retained | Implemented and tested |
| Album grouping | Album title + album artist, falling back to track artist | Rule defined; track-artist fallback active |
| Album artist / compilation tags | Not retained in Room v1 | Explicitly deferred; no heuristic |
| Artist grouping | Deterministic normalized track artist | Implemented and tested |
| Track/disc ordinals | Positive MediaStore encoding or `n[/total]` retriever value | Corrected and tested |
| Year | Explicit positive year only | Implemented and tested |
| Full release date | Not retained | Explicitly deferred |
| Genre | Audio Details presentation only, not core/grouping | Explicitly deferred |
| Folder | Source-relative containing directory; no folder entity | Documented current behavior |
| Favorites/playlists/playback restoration | Stable song occurrence ID | Preserved; no schema or media-ID change |

## Deferred work

Future work may add album-artist, compilation, full-date, structured genre, stronger provider-specific file identity, move reconciliation, or a folder entity. Any persisted-field addition requires an explicit non-destructive Room migration and compatibility tests. None is implied by this Q3.1 authority, and Q3.2 must build on the identities above rather than redefine them implicitly.
