# LibrePlayer Library Semantics

This document is the Q3.1 authority for local-library identity and metadata and the Q3.2 authority for browse-group membership. It describes the current Room schema (version 1), ingestion, and browsing behavior. It does not introduce a schema migration.

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

Artist groups use normalized track artist. Album membership does not change a song's artist identity. Missing artists use the presentation label `Unknown artist`, but their typed group identity remains distinct from a literal source tag such as `Unknown Artist`.

Album and artist IDs are deterministic grouping keys. Each normalized metadata component is typed as missing or present and length-prefixed, preventing delimiter collisions and preserving the difference between missing metadata and literal fallback-like text. Playlist membership, favorites, recent-play history, and playback queue restoration reference song IDs, never album or artist grouping keys.

## Track, disc, year, genre, and folder semantics

MediaStore's encoded track field is decoded as `disc * 1000 + track`. Only positive components are retained: `0` is missing, `1000` means disc 1 with unknown track, and `2007` means disc 2 track 7. Retriever ordinals accept either `n` or `n/total`; malformed, zero, and negative values remain null. Duplicate track numbers are allowed. Library ordering is disc, then track, with title/ID tie-break behavior owned by the existing sort layer; numbering never defines song identity.

The core model stores year precision only. A positive four-digit-or-less integer is accepted; a full date is not truncated or inferred. Genre is not retained in the core library row. Audio Details may display retriever-provided album artist and genre as raw presentation metadata, but those fields do not currently affect grouping or identity. Multi-value genre parsing is therefore deferred.

Folder information means the containing source-relative directory, not an assumed absolute filesystem path:

- MediaStore uses `RELATIVE_PATH` where the platform exposes it.
- SAF derives a provider/document-relative parent representation.

LibrePlayer currently has no authoritative folder entity or folder-browsing screen. Folder strings aid display and conservative cross-source matching; they do not define a portable global identity.

## Persistence and synchronization

Rescans upsert by song ID and preserve the favorite bit for an existing ID. Removed source IDs remove the song row and rebuild album/artist aggregates through the existing synchronization transaction. Playlists store song IDs, recent plays store song IDs, and playback snapshots restore source occurrence IDs. The Q3.1 changes do not alter any of those IDs, tables, foreign-key policies, or database version.

## Browsing and grouping semantics

Group membership is Q3.2 authority. Comprehensive group and song ordering remains Q3.3 authority; Q3.2 preserves the existing ordering behavior where a deterministic list is required.

### Songs

The Songs surface contains every currently available playable `Song` occurrence exactly once after Q3.1 cross-source reconciliation. Its lazy-list key is the occurrence `Song.id`. Missing-title text is derived for display and does not affect membership or row identity. Selecting a row passes the displayed song list and selected occurrence index to playback unchanged.

### Albums and album detail

The Albums surface contains one aggregate for each accepted album group ID: typed normalized album title plus typed normalized grouping artist. Disc number is not part of album identity, so all discs belonging to the same album group remain together. Same-title albums with different artists remain separate. Different source occurrences with identical tags remain separate songs inside the same album.

Album rows use `Album.id` as their Compose and navigation identity. Navigation percent-encodes the compact ID in the route, and Navigation supplies the decoded string argument to the destination; the destination does not decode it a second time. This preserves literal percent sequences, slashes, query characters, fragments, and separators in source metadata.

Album detail membership is recomputed from the current observable song catalog using the same group-ID helper used by aggregate construction. Its displayed rows use `Song.id`. The Room aggregate's `songCount` and duration are built from that same membership rule. There is no separate Play Album action; selecting a detail row hands exactly the displayed membership list and occurrence index to Q2 playback.

Missing album metadata appears under the presentation label `Unknown album`, using a missing-value group component. A literal `Unknown Album` tag has a distinct present-value group component even though the two labels differ only by source casing. Under Room v1's track-artist fallback, compilation-like albums with different track artists remain split; Q3.2 does not infer compilation state.

### Artists and artist detail

The Artists surface groups by typed, normalized persisted track artist. Case and surrounding whitespace normalize together; punctuation and articles do not. Missing track artist uses a distinct missing-value ID and the `Unknown artist` presentation label. A literal `Unknown Artist` source tag remains a different group.

Artist rows use `Artist.id`; artist-detail song rows use `Song.id`; nested album rows use the full `Album.id`. Artist detail derives its songs from the current catalog using the artist group ID and derives its album list from those songs' full album group IDs. Therefore same-title albums cannot navigate across artist groups. The displayed artist song count and album count use the same current memberships. There is no separate Play Artist action; selecting a song passes the displayed artist membership and occurrence index unchanged.

### Refresh coherence and adjacent domains

Room song, album, and artist rows are updated inside the existing synchronization transaction. The current observable Song occurrence snapshot is the authoritative input for album and artist browsing: it produces the displayed aggregates and the detail memberships. Persisted Room album and artist rows remain synchronization-maintained derived/cache data, but browsing correctness does not depend on regenerating them after an application upgrade. Legacy or stale aggregate IDs therefore cannot hide a valid current group, and adopting the Q3.2 group representation requires no schema migration, destructive reset, or forced library scan.

The single Song collector derives both browse projections on a background dispatcher only when that Song snapshot emits. Settings changes, playlist changes, playback-position ticks, and Compose recomposition do not rerun grouping. A later normal synchronization updates its persisted aggregate cache through the existing transaction and causes the Song-backed browse projection to refresh when membership changes, without changing an active playback media ID.

Folders and genres have no current browse surfaces. Q3.1's source-relative folder definition remains available for later browsing work. Genre remains Audio Details presentation metadata and is not a Q3.2 group. Favorites, playlists, and search are adjacent existing surfaces; Q3.2 does not redesign them, and Q3.3 owns comprehensive sorting and search authority.

## Q3.2 coverage matrix

| Browse behavior | Authority | Classification |
| --- | --- | --- |
| Songs membership and row keys | Every reconciled occurrence once; `Song.id` | Correct and covered |
| Ordinary and multidisc albums | Full typed album group ID; disc excluded from identity | Correct and covered |
| Same album title / different artist | Distinct full album group IDs | Correct and covered |
| Identical metadata occurrences | Separate song rows within one group | Correct and covered |
| Missing versus literal fallback tags | Distinct typed group IDs | Corrected and covered |
| Delimiter/special-character metadata | Length-prefixed group components; one route decode | Corrected and covered |
| Album count/detail parity | Shared group helper and current occurrence membership | Correct and covered |
| Artist membership and nested albums | Track-artist group plus full album IDs | Correct and covered |
| Artist count/detail parity | Shared group helper and current occurrence membership | Correct and covered |
| Browse-to-playback identity | Displayed list/index; occurrence ID/URI retained | Correct and covered |
| Refresh add/remove | Existing transaction plus observable current membership | Correct and covered |
| Compilation album-artist behavior | Track-artist fallback under Room v1 | Known limitation |
| Folder and genre browsing | No current browse surfaces | Deferred |
| Comprehensive ordering and search | Outside Q3.2 membership authority | Deferred to Q3.3 |

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
