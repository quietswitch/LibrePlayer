# LibrePlayer Library Semantics

This document covers Q3.1 identity/metadata, Q3.2 browse grouping, Q3.3 sorting/search, Q3.4 metadata pathology, Q3.5 artwork, and Q3.6 playlists/local M3U interchange. It describes the current Room schema (version 1) without introducing a schema migration.

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

MediaStore's encoded track field is decoded as `disc * 1000 + track`. Only positive components are retained: `0` is missing, `1000` means disc 1 with unknown track, and `2007` means disc 2 track 7. Retriever ordinals accept either `n` or `n/total`; malformed, zero, and negative values remain null. Duplicate track numbers are allowed. Q3.3 album-detail ordering is numeric disc, then numeric track, then normalized title and Song ID; numbering never defines song identity.

The core model stores year precision only. Both integer and textual sources accept only `1..9999`; a full date is not truncated or inferred, and overflow-sized text is missing. Genre is not retained in the core library row. Audio Details may display retriever-provided album artist and genre as raw presentation metadata, but those fields do not currently affect grouping or identity. Multi-value genre parsing is therefore deferred.

Folder information means the containing source-relative directory, not an assumed absolute filesystem path:

- MediaStore uses `RELATIVE_PATH` where the platform exposes it.
- SAF derives a provider/document-relative parent representation.

LibrePlayer currently has no authoritative folder entity or folder-browsing screen. Folder strings aid display and conservative cross-source matching; they do not define a portable global identity.

## Persistence and synchronization

Rescans upsert by song ID and preserve the favorite bit for an existing ID. Removed source IDs remove the song row and rebuild album/artist aggregates through the existing synchronization transaction. Playlists store song IDs, recent plays store song IDs, and playback snapshots restore source occurrence IDs. The Q3.1 changes do not alter any of those IDs, tables, foreign-key policies, or database version.

## Browsing and grouping semantics

Group membership is Q3.2 authority. Q3.3 controls only the presentation order of those accepted groups and occurrences and the matching subset shown by search.

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

## Sorting and search semantics

Q3.3 consumes Q3.1 Song identity and Q3.2 Album/Artist identities and memberships. Sorting changes presentation order; search filters the current presentation. Neither operation rewrites stored metadata, collapses duplicate occurrences, changes group membership, or creates playback identity.

### Deterministic ordering

Text ordering trims surrounding whitespace and lowercases with `Locale.ROOT`. It retains punctuation, articles, accents, and internal whitespace; display text itself is never rewritten. Every comparator ends in the relevant semantic identity, so equal visible metadata never relies on repository or collection input order.

The Songs surface exposes one persisted sort preference and no direction toggle:

- Title: resolved title, resolved artist, resolved album, then Song ID; ascending.
- Artist: resolved artist, resolved album, resolved title, then Song ID; ascending.
- Album: resolved album, resolved artist, positive disc number, positive track number, resolved title, then Song ID; ascending. Missing/non-positive ordinals sort after valid ordinals.
- Duration: positive duration descending, then title, artist, album, and Song ID ascending. Missing/non-positive duration sorts last.
- Date added: positive timestamp descending, then title, artist, album, and Song ID ascending. Missing/non-positive timestamp sorts last.

Thus descending modes reverse only their primary numeric field; deterministic textual and identity ties remain ascending. The same Song preference orders Favorites and artist-detail Songs. Artist detail filters the already ordered Song list, and its Album rows filter the already ordered top-level Album list; it does not maintain independent comparators.

Albums have one current default order and no user-selectable Album sort mode: normalized title, resolved grouping-artist presentation, then full semantic Album ID. Artists likewise have one default order: normalized display name, then semantic Artist ID. `The` and punctuation are not stripped, so `Beatles` and `The Beatles`, and `R.E.M.` and `REM`, remain distinct and follow ordinary deterministic lexical ordering.

Album detail does not inherit the global Song preference. Its members always sort by positive disc number, positive track number, normalized resolved title, then Song ID. Valid ordinals precede missing/non-positive ordinals at each numeric level. Track 2 therefore precedes Track 10 numerically; duplicate ordinals and duplicate visible metadata remain deterministic independent occurrences.

The Song sort preference is stored in the existing Settings DataStore as the enum name. Every supported value survives recreation and restart; a missing, old, or invalid value safely selects Title. This intentionally shared preference controls the current Song-derived surfaces. Changing it only recomputes an in-memory presentation and neither rescans the library nor mutates Room.

### Local search

Search is global across the local library and presents separate Song, Album, and Artist result sections. It is filtering, not ranking or identity resolution. The query is trimmed and lowercased with `Locale.ROOT`; punctuation and internal spaces are preserved. Matching is a case-insensitive contiguous substring test. There is no token splitting, stemming, transliteration, fuzzy matching, typo correction, scoring, network access, or search index.

The accepted fields are:

- Song: resolved displayed title, resolved artist, resolved album, and display filename.
- Album: accepted Album presentation title and non-missing grouping artist.
- Artist: accepted Artist display name.

Private paths, source/internal IDs, content URIs, and debug metadata are not searched. Presentation fallbacks participate for Songs, so a missing artist or album can match `Unknown artist` or `Unknown album`; those matches retain a missing-value identity distinct from literal user-authored fallback-like metadata. Filename fallback and filename search do not replace Song ID.

Each result retains its original `Song.id` and content URI, `Album.id`, or `Artist.id`. Song rows are keyed by occurrence ID and playback receives the exact filtered Song list plus selected occurrence index. Albums and Artists navigate by their full semantic IDs. Duplicate physical occurrences are never collapsed.

Search filters the already ordered Song, Album, and Artist lists and therefore preserves the active surface order without introducing a competing rank. Changing sort while a query is active changes order but not matching membership or query text. Changing the query changes membership but preserves the persisted sort preference.

A blank normalized query produces the established non-search state (`Search your library`) rather than “match everything.” Clearing restores the full current browse membership under the active sort after returning from search. Query state belongs to `LibraryViewModel`: it survives ordinary configuration recreation and navigation while that ViewModel remains alive, is not persisted across process death, and remains until explicitly edited or cleared.

Query updates use a single `StateFlow` with no debounce and no child coroutine per keystroke. Each evaluation is synchronous on the background projection dispatcher, so a rapid replacement settles on the latest state and cannot be overwritten later by an older asynchronous job. The ordered current catalog is shared between catalog and search presentation; there is no duplicate Song collector. Search recomputes only when the query, current Song snapshot, or active Song sort changes—not for playlist, favorite, playback-position, or unrelated metadata emissions. A synchronization refresh therefore updates an active query directly from the same current Song authority used by Q3.2, with coherent group counts and no secondary cache/index rebuild.

For `n` Songs, the accepted implementation sorts in approximately `O(n log n)` and filters in `O(n)` per query evaluation; sorting plus filtering does not perform a Room query or nested full-library scan per row. Canonical 2,000-song UI authority remains bounded here; huge-library scale remains later Q3 work.

## Metadata pathology semantics

Q3.4 distinguishes behavior defined by LibrePlayer from values selected or transformed by Android's media stack. It does not make LibrePlayer a tag parser, repair malformed tags, or infer metadata that Android did not expose.

### LibrePlayer-defined behavior

MediaStore title, artist, and album projections and retriever title, artist, and album strings are stored as received. Nullable Room fields remain nullable and have no application-level length cap. LibrePlayer does not persist filename, `Unknown artist`, or `Unknown album` presentation fallbacks into those raw fields; it also does not lowercase, trim, strip punctuation, or Unicode-normalize them. Derived sort keys, group IDs, and display fallbacks are separate values. Physical identity remains source ID plus content URI and never depends on metadata.

Null, empty, and whitespace-only title/artist/album values are unusable for presentation and grouping. Title then uses the filename stem; artist and album use their established fallback labels. Raw whitespace is not rewritten. Empty and whitespace-only artist/album values use the typed missing group component, so they cannot create an invisible group. A literal `Unknown Artist` or `Unknown Album` is a present source value and therefore has a different typed group ID from missing metadata.

Grouping and ordering retain punctuation, internal whitespace, scripts, combining sequences, and supplementary-plane characters. Surrounding whitespace and case are the only grouping normalizations; search/sort likewise trim and lowercase using their documented locale-independent key. There is deliberately no NFC, NFD, NFKC, or NFKD entity merge: precomposed `é` and decomposed `e` plus combining acute remain distinct source strings and distinct group IDs. Length-prefixed typed components keep separators such as `|`, `:`, `/`, `\`, `%`, `?`, and `#` collision-free.

Newline, carriage-return, tab, and other unusual strings are not automatically sanitized. Song, Album, and Artist list rows render through bounded one-line Compose `Text` with ellipsis, while identity and navigation retain the full semantic value. A presentation-only sanitizer would require a demonstrated correctness or unsafe-control defect; unusual but stable platform text is not sufficient reason to destroy source meaning.

The accepted bounded long-value authority is exactly 1,024 UTF-16 code units for title and 4,096 each for artist and album. At those bounds, Room projection, typed group generation, deterministic equality, `O(length)` normalization/search, `O(n log n)` sorting, percent-encoded navigation, and playback handoff complete without truncation, collision, or exception. This is a tested bound, not a universal provider or navigation maximum and not a production tag-length cap.

Retriever track/disc values accept a positive `n` or positive numerator in `n/total`. `1`, `1/12`, `01/12`, and `1/0` therefore produce ordinal 1; zero, negative, blank, malformed, and overflow-sized numerators are missing. MediaStore's packed integer retains only positive decoded components. Both MediaStore and retriever years use the same `1..9999` contract. Audio Details uses these same parsers and cannot reintroduce invalid values. Album-detail ordering remains numeric, so track 2 precedes track 10 and missing follows valid positive ordinals.

When MediaStore and SAF can be proven to represent the same canonical primary-storage path, source precedence is deterministic: MediaStore supplies the durable Song identity and its usable metadata wins; only missing/unusable fields are filled from the SAF observation. Reversing scan input order produces the same merged row. If a canonical path cannot be established, the observations remain distinct occurrences. This policy does not guess sameness from tag content.

### Android/platform-observed behavior

The API-36 authority used 11 deterministic MP3 fixtures with ID3v2.3 tags (or no tag for the absent case) on `LibrePlayer_Benchmark_API_36`. Android indexed every file as one MediaStore occurrence. It preserved representative accented Latin, Greek, Cyrillic, CJK, Arabic, emoji, punctuation, precomposed/decomposed distinctions, and the exact tested long values. Absent and whitespace-only embedded strings were projected as filename, `<unknown>` artist, and containing-directory album fallbacks. Those strings are Android projections received by LibrePlayer; LibrePlayer does not claim they are the original embedded values.

For deliberately repeated conflicting ID3 frames, this platform build exposed the first title, artist, album, track, and year frames (`Q34 Conflict First`, `Conflict Artist First`, `Conflict Album First`, track 2, year 2024). LibrePlayer receives one MediaStore value and defines no first-tag-wins or last-tag-wins rule. Other extractors, formats, API levels, and OEM builds may choose differently.

The control-character fixture was indexed and usable, but Android reprojected its embedded control-bearing text into unusual non-control glyph sequences. LibrePlayer safely grouped, sorted, searched, and rendered the platform result; it does not claim raw tag-byte preservation through MediaStore. The pure semantic layer separately proves that newline, carriage-return, and tab strings received directly by LibrePlayer remain stable and safe.

The malformed-tag fixture retained the same intact 31.176-second MPEG audio payload as the control. MediaStore indexed it with filename/directory/unknown metadata fallbacks. The shared SAF metadata extractor could still read its duration while returning no title, and playback prepared and played it with the exact Song ID and content URI. If metadata extraction fails entirely, a cached SAF occurrence is retained; a previously unseen SAF file without a readable duration is not admitted because the established 30-second library filter cannot be evaluated. MediaStore exposure remains usable when Android supplies a qualifying row. Media decode failures remain Q2.7 behavior, not metadata repair.

Two unchanged repository reconciliations over the fixture set produced identical Song IDs, content URIs, raw and resolved projections, ordinals, group IDs, sort/search membership, and a canonical fingerprint. No duplicate rows or aggregate oscillation occurred. The API run also navigated through the full 4-KiB album/artist semantic route and confirmed the selected pathological Song's `Song.id == MediaItem.mediaId` and unchanged content URI.

An actual imported-tree scan of the same physical file simultaneously exposed through MediaStore and SAF remains provider/platform-dependent. Q3.4 proves the merge rule in both input orders and exercises the shared SAF extractor against the same content source, but does not claim that every provider supplies a comparable canonical path.

### Q3.4 coverage matrix

| Pathology | Authority | Classification |
| --- | --- | --- |
| Null/empty/whitespace/padded text | Raw preserved; presentation fallback and typed missing group | Correct and covered |
| Literal fallback-like values | Present typed identity remains distinct from missing | Correct and covered |
| Unicode/scripts/emoji/NFC versus NFD | Exact source strings; deterministic, no canonical merge | Correct and covered |
| Punctuation/route separators/percent | Length-prefixed groups plus one route decode | Correct and covered |
| Newline/CR/tab | Pure semantic preservation; API-36 platform reprojection remained safe | Correct and covered within platform observation |
| 1,024/4,096/4,096 text bounds | Store/group/sort/search/route/UI complete exactly | Correct and covered at tested bounds |
| Track/disc malformed and overflow text | Positive `n[/total]`; invalid values missing | Corrected and covered |
| MediaStore/retriever year range | Shared `1..9999` boundary with overflow-safe conversion | Corrected and covered |
| Conflicting embedded tags | Android exposed first frames on tested stack | Platform-observed; no LibrePlayer precedence contract |
| Malformed tag with valid audio | Platform fallback, retriever duration, exact playable handoff | Correct and covered on tested stack |
| Cross-source disagreement | Deterministic MediaStore precedence and missing-field fill | Correct and covered in semantic layer; live dual-provider exposure limited |
| Unchanged refresh | Stable canonical metadata/group/identity fingerprint | Correct and covered |
| Universal tag-format/OEM behavior | Outside application-owned authority | Platform-limited / deferred |

Q3.4 does not add fuzzy or semantic metadata matching, online metadata repair, tag editing, a custom tag parser, persisted album-artist/compilation/genre/full-date fields, artwork authority, M3U parsing, huge-library limits, removable-storage/OEM matrices, Folder/Genre browse surfaces, or advanced visual/accessibility behavior.

## Artwork semantics

Artwork is local presentation state, never Song, Album, or Artist identity. Artwork equality does not merge physical occurrences, differing artwork does not split an accepted group, and artwork locators are not Compose row keys. Song rows use `Song.id`; Album and Artist rows use their accepted semantic group IDs. MediaStore `ALBUM_ID` remains artwork lookup metadata and never replaces the Q3.2 Album ID.

### Song sources and loading

A Song carries one persisted local artwork locator and its playable content URI. For MediaStore rows, the primary locator is `content://media/external/audio/albumart/<ALBUM_ID>` when Android supplies a positive album ID, otherwise the audio row URI. For SAF rows, the document content URI is both the artwork candidate and audio locator. LibrePlayer does not copy embedded images into app storage.

The UI tries each distinct candidate in order: persisted artwork locator, then playable content URI when different. On API 29 and newer it first asks `ContentResolver.loadThumbnail` for the surface-specific target; if that fails, it asks `MediaMetadataRetriever` for the local audio source's single platform-exposed embedded picture and decodes it with `BitmapFactory`. The accepted targets are 160 px for list/group rows, 144 px for the mini-player, and 768 px for Now Playing. Power-of-two sampling downsamples fallback decoding at the tested bounds. Image bytes are decoded on demand by a composed surface. Its coroutine is cancelled when the request changes or leaves composition, with cancellation checks between candidates; a synchronous platform decode already in progress is not forcibly interrupted.

An empty candidate set is internally `Missing`; candidates that all fail are `Failed`. Both preserve the existing neutral initial-letter placeholder without persisting a fabricated URI or error state. A missing, stale, malformed, or unreadable image never removes its Song, changes membership, or prevents playback. There is no network fallback and no automatic refresh or retry loop.

### Group representative projection

An Album uses its accepted Q3.2 member list sorted by the Q3.2 album-track comparator: disc number, track number, title, then Song ID, with missing ordinals last. Each member contributes its primary and content-URI candidates in that order. The first candidate the loader can actually decode is the representative. This makes partial, stale, and corrupt leading members fall through to a later usable member while keeping the preference deterministic. Conflicting valid covers intentionally use the earliest ordered member; disc does not create another Album or artwork identity.

Album candidates are built independently per full semantic Album ID. Consequently `Greatest Hits / Artist A` and `Greatest Hits / Artist B` cannot share a group candidate chain merely because their displayed titles match. Duplicate Song occurrences with identical metadata remain separate candidates tied to their occurrence locators.

The current product also renders Artist artwork. Artist members use a deterministic album, disc, track, title, and Song-ID order and the same first-decodable rule. Group artwork is enriched from the current observable Song snapshot; stale persisted Album/Artist aggregate artwork is not allowed to override current membership. Persisted aggregates are nevertheless generated deterministically for non-UI consumers. Removing the chosen member and refreshing moves selection to the next usable current member; unchanged refresh input yields the same chain.

### Cache identity and invalidation

LibrePlayer owns an in-memory decoded-bitmap cache only; it adds no artwork disk cache. Its collision-safe request key contains the requested size variant plus every ordered candidate's length-prefixed local URI and source `dateModified` revision. It is never based on album title, artist display name, or artwork pixels. Duplicate URIs fold to their newest revision without changing first occurrence order.

The cache is byte-sized by each bitmap's allocation rather than entry count. Its budget is one sixteenth of the process maximum heap, clamped to 4–24 MiB; the API-36 authority environment selected 12,582,912 bytes. Changed source modification state creates a new request key, while a failed or placeholder result is not cached and therefore cannot poison a later success. The accepted same-source fixture retained its Song ID, content URI, and semantic Album ID while a refresh changed red artwork to blue and changed the revision-bearing cache key. Detection depends on the source exposing a changed locator or modification revision; silent byte replacement with an unchanged timestamp is not established. The cache budget covers retained cache bitmap allocations, not all in-flight decode bytes, UI-held bitmaps, keys, or platform caches.

### Surface and playback boundary

Songs, search Song results, Album and Artist rows, Album/Artist detail Songs, playlist/favorite rows, mini-player, Now Playing, and queue use the same loader. Song-oriented surfaces use that exact Song's candidates and never substitute another Album member's cover. Group rows use the deterministic group candidate chain. Audio Details reports whether the platform retriever exposed embedded artwork but does not create another artwork authority.

Playback and MediaSession retain the exact selected `Song.id` as `MediaItem.mediaId` and the unchanged audio content URI. Existing Media3 metadata publishes the Song's local artwork URI; it does not control library identity or the app UI cache. Valid, corrupt, and missing-art fixtures all prepared and played through the same occurrence-preserving path. Notification styling and Media3/platform byte caching are not redefined by Q3.5.

### Tested platform and resource bounds

The API-36 authority used deterministic 31.176-second MP3 audio with ID3v2.3 APIC data. JPEG, PNG, and static WebP were established locally. A 2,048×2,048 WebP was returned as a bounded 683×683 bitmap by the API-36 thumbnail path, allocating 1,865,956 bytes. Corrupt bytes failed safely. The tested truncated PNG was platform-observed to decode as a bounded 256×256 all-zero bitmap rather than throw; LibrePlayer accepts either a bounded platform decode or safe failure and makes no claim about visual validity beyond Android's decoder result.

The authority journey covered search, Album detail, a playing Song, mini-player, Now Playing, queue, Artist detail, and repeated artwork-list scrolls. The initial corrected run passed in 19.065 seconds with PSS 57,350 → 89,514 KiB and threads 37 → 47. The controlling final-APK run passed in 21.411 seconds with PSS 59,607 → 90,552 KiB and threads 35 → 46; the decoded cache was 2,590,500 of 12,582,912 bytes after ordinary and large loads. These observations establish completion without an observed OOM/crash and a bounded cache sample. Because the harness force-stops the app between the pre-UI and post-UI measurements, they do not establish a same-process retained-memory plateau or exclude a monotonic ratchet.

The first 2026-09-05 closeout resource attempt failed before workload cycles: shell text injection left the search field as `5Q` instead of `Q35`. Its baseline was PSS 94,730 KiB, RSS 174,108 KiB, 37 threads, and cache 0 / 12,582,912 bytes. This remains a preserved harness failure, not evidence of an artwork or playback defect; all four temporary device files and their MediaStore rows were removed. No automatic retry occurred.

Under renewed explicit one-run authorization, the host script was corrected to append literal `Q`, `3`, and `5` individually with case-sensitive prefix verification, then reuse verified `Q35` across four workload cycles. Exactly one corrected observation completed on the approved API-36 emulator using the unchanged final APK and four existing synthetic files (normal JPEG, partial green PNG, corrupt art, and large WebP). No Android source or APK changed. The transcript spans 170 seconds including provisioning and cleanup; the final measurement is at 163.31 seconds. All seven baseline/workload/quiet samples retained PID 4670, spanning 145.29 seconds from baseline to final sample. The initial force-stop occurs before the baseline, never between these samples.

| Ordered phase | Elapsed seconds | PSS KiB | RSS KiB | Threads | Cache bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline | 18.02 | 93,926 | 179,344 | 37 | 0 |
| Cycle 1 settled | 58.22 | 92,667 | 191,784 | 39 | 2,331,084 |
| Cycle 2 settled | 87.63 | 98,492 | 190,740 | 40 | 2,331,084 |
| Cycle 3 settled | 116.36 | 98,880 | 191,356 | 41 | 2,331,084 |
| Cycle 4 settled | 144.96 | 99,430 | 191,956 | 41 | 2,331,084 |
| Quiet settle 1 | 154.13 | 92,105 | 191,184 | 41 | 2,331,084 |
| Quiet settle 2 | 163.31 | 91,347 | 190,996 | 41 | 2,331,084 |

Targeted Q1.1g: **PASS** at this bound. Cache usage plateaued below the unchanged 12,582,912-byte budget; entry count is not exposed. Threads stabilized at 41. Sampled PSS peaked at 99,430 KiB then declined during quiet settling; RSS stayed above cold baseline after warm-up but did not sustain monotonic growth. The standard `dumpsys meminfo` measurement sequence includes explicit GC events in logcat, so this is sampled settled-state behavior, not continuous allocation profiling. Each cycle's large-art result remained 683×683 / 1,865,956 allocation bytes, and corrupt-art failure remained safe and expected. No OOM, crash, unexpected restart, ANR, or uncontrolled cache/thread growth was observed. The app returned from each search workload to its browse state, responded through both final samples, and completed cleanup. All four temporary files were removed, zero matching MediaStore rows remained, and the emulator was stopped; local originals and prior evidence were preserved.

The bounded Q3.5 same-process resource observation shows no obvious retained-memory ratchet or uncontrolled bitmap-cache growth under the tested artwork workload. This is not universal leak-freedom, OEM memory authority, unlimited artwork safety, or a universal PSS/RSS threshold. No prior functional authority or Gradle gate was rerun for closeout.

Candidate construction is `O(k)` after deterministic sorting (`O(k log k)`) for `k` group members. It does not decode during grouping, sorting, search matching, scanning, or playback-position updates; decoding remains demand-driven by visible requests. There is no Room query per artwork row or scanner extraction triggered by Compose recomposition.

The accepted Baseline Profile source remains unchanged and its binary profile assets remain packaged. Normal release-cycle reassessment should cover the changed Album/Artist constructors and copy methods, `ArtworkThumbnail`, `SongRow`, `ArtworkRequest`, candidate merging/key generation, representative selection, and the byte-budgeted loader. D8 reports stale signatures from the preserved profile; Q3.5 does not regenerate or reopen Q1.1h.

### Q3.5 coverage matrix

| Case | Authority | Classification |
| --- | --- | --- |
| A1 normal valid art | Local JPEG/PNG/WebP candidate decodes at a surface target | Correct and covered |
| A2 missing art | Internal missing/failed state; stable local placeholder | Correct and covered |
| A3 same Album, partial art | Ordered chain skips unusable member and selects later usable art | Corrected and covered |
| A4 same Album, conflicting art | First decodable candidate in accepted track order | Corrected and covered |
| A5 same title, different artist | Full Album IDs and distinct source-bearing keys isolate covers | Correct and covered |
| A6 duplicate metadata, different source | Distinct Songs and source locators retained | Correct and covered |
| A7 corrupt art | Decode failure falls back; audio remains playable | Correct and covered |
| A8 truncated art | Bounded platform decode or safe fallback | Platform-observed and covered at tested bound |
| A9 large art | 2,048-square static WebP requested/returned at bounded size | Corrected and covered |
| A10 same-source art A→B | Stable Song/Album identity; revision key exposes B after refresh | Corrected and covered |
| A11 representative deletion | Remaining group chooses next deterministic usable member | Corrected and covered |
| A12 unchanged refresh | Candidate projections and canonical fingerprint stable | Correct and covered |
| A13 search/detail/playback handoff | Shared projection and exact Song ID/content URI | Correct and covered |
| A14 SAF | Local document URI, embedded extraction, identity, and cleanup | Correct and covered on the benchmark provider |

Android and OEM thumbnail selection, multiple APIC-frame precedence, platform cache internals, and formats beyond tested JPEG, PNG, and static WebP are platform-limited and not claimed. Q3.5 does not add online artwork retrieval, remote URLs, artwork editing or repair, permanent extracted-cover storage, palette/dominant-color theming, animated-art authority, a custom codec, fuzzy metadata matching, M3U behavior, huge-library/removable-storage matrices, new browse surfaces, or broader visual/accessibility redesign.

## Playlist & M3U semantics

### Internal playlist identity and naming

An internal playlist is ordered user data identified by the existing auto-generated `PlaylistEntity.id` (Long), never by its display name. Names are not unique: two playlists may have the same name. Create trims the name and substitutes `New playlist` when blank; rename trims a nonblank replacement or keeps the existing name when blank. Rename preserves ID, creation timestamp, membership, positions and entry-added timestamps. Delete targets the exact playlist ID; its foreign-key cascade removes only that playlist's membership, never Songs or source media.

### Membership, duplicates, ordering and mutations

Room v1 intentionally permits each Song ID at most once per playlist: `playlist_songs` has the composite primary key `(playlistId, songId)`. The pre-Q3.6 repository and its `addSongs appends songs without duplicates` test explicitly enforce that rule. Q3.6 retains it as a product limitation, not an accidental M3U parser deduplication. Different Q3.1 source occurrences with identical metadata remain different Song IDs and can coexist. Supporting repeated references to the same Song within one internal playlist would require a separately reviewed, playlist-preserving schema migration; none is performed here.

The persisted integer `position` is order authority. Queries explicitly order by position, then Song ID as a deterministic tie-break for legacy collisions. They do not use insertion order or the global Q3.3 Song sort. Add takes requested Song-ID order, ignores already-present IDs, ignores unavailable requested Songs, and appends new unique references. SQL `IN` result order is not trusted. Add/remove/reorder reindex all stored rows contiguously. Remove targets the unique `(playlistId, songId)` relation. Reorder moves the displayed available subsequence and preserves hidden references in their existing slots. Existing entry-added timestamps survive all these operations.

Multi-row mutation reads, replacement and playlist timestamp updates run in one Room transaction. Import creates a new playlist and its resolved references atomically, rechecking that every selected Song ID still exists before insertion. Failure rolls back instead of modifying an existing playlist or leaving a partial newly created one. Playlist summaries use a single observed aggregate join; Song disappearance/return updates the available count without a query per playlist row.

### Missing Songs, refresh, row keys and playback

There is a playlist foreign key but deliberately no Song foreign key on `playlist_songs`. Source removal leaves its raw Song-ID reference stored; the existing inner join hides it from displayed/playable rows. Q3.6 corrects mutations that previously rebuilt membership from only the visible join and could silently erase these hidden references. The same exact Song ID reappearing reconnects through the join. A new ID, moved source or metadata match does not reconnect it. There is no persisted unavailable-Song snapshot or new placeholder row.

Playlist rows use `Playlist.id`; detail Song rows use Song ID, which is unique within the retained v1 playlist contract and remains stable across reorder. Selecting a detail row passes the displayed, persisted-order Song list and selected index unchanged to Q2 playback. Like existing Album detail, there is no separate Play All action: selecting the first row starts the full playlist at position zero. Queue order is never globally sorted or deduplicated by the playlist handoff. Q2's general duplicate-queue occurrence semantics remain unchanged; internal playlist duplicates are independently prohibited as described above.

Favorites remain separate Song user data and a separate smart collection. Playlist and M3U operations do not toggle, convert, reorder or reset Favorites, and do not rewrite playback identity, library metadata or media bytes.

### M3U/M3U8 text contract

Import accepts `.m3u` and `.m3u8` case-insensitively. Both use strict UTF-8, with an optional leading UTF-8 BOM. Legacy locale encodings are not guessed. Malformed UTF-8 rejects the document before playlist creation. Limits are 1 MiB encoded input/output, 10,000 media-reference lines/entries and 8,192 characters per line; these are safety bounds, not huge-playlist performance authority.

LF, CRLF, mixed line endings and a final line without newline are accepted. Blank lines and lines beginning `#` are ignored as entries. `#EXTM3U` is optional. `#EXTINF` is advisory and never becomes Song title, artist, duration or identity; malformed/orphan EXTINF is counted for diagnostics, not executed. Path text is not trimmed: spaces, Unicode, parentheses, brackets and literal percent/plus characters remain data. A leading-`#` filename must be qualified (for example `./#song.mp3`) to distinguish it from a comment; an interior `#` is literal path text. Parser output preserves media-line order and duplicates.

### Conservative reference resolution

Import resolves against a current-library index and never creates or scans a Song from a playlist entry. Exact existing content URIs resolve only when they identify one Song; zero matches are missing and multiple Song IDs are ambiguous. HTTP/HTTPS and protocol-relative remote references are explicitly unsupported and are never opened or fetched. Unknown schemes, including drive-letter/streaming/intent schemes, are unsupported. File URIs require an empty authority and no query/fragment, with strict percent-encoded UTF-8 path decoding. Invalid controls, encoding or syntax are malformed rather than guessed.

Absolute paths need evidence from a current source. MediaStore path evidence is read in bounded ID batches using its read-only DATA column; those paths are never opened, executed or written. Provider refusal or absent path evidence falls back only to exact existing URI support, not an invented primary-storage path. Known platform external-storage document IDs can provide primary/volume-qualified paths. Generic SAF providers do not get fabricated filesystem paths.

Local paths are normalized lexically for `.`/`..`, retaining volume distinctions and the accepted Q3.1 case-insensitive shared-storage comparison. Q3.1 canonical path authority is reused with an additional lossless discriminator so percent/plus/query-like filename text cannot accidentally match a different path through lossy URI decoding. An exact canonical local path mapping to zero/one/multiple Song IDs produces missing/resolved/ambiguous respectively. No title/artist matching or fuzzy basename fallback exists.

Relative references use the selected playlist document's containing directory only when its provider exposes a supported real path context (the platform external-storage document provider). Sibling and parent paths may match only already-indexed, previously accessible library Songs; lexical traversal never grants additional filesystem access. An opaque/generic SAF document reports `relative context unavailable`. A basename with multiple exact current-library names may be reported ambiguous, but even a globally unique basename is not guessed without a directory context. Arbitrary provider hierarchy, removable-volume spelling/OEM matrices and universal path portability are not claimed.

### Import reporting, naming and SAF lifetime

The preview reports total reference lines, resolved references, unique Songs to import, repeated references omitted under the internal v1 rule, missing, ambiguous, unsupported remote/scheme, unavailable relative context, malformed references and malformed/orphan EXTINF. The user confirms this preview before creating a new playlist. Resolved duplicate lines remain in the report in input order; persistence uses first-occurrence order of unique Song IDs and explicitly reports every omission. Unresolved lines never create fake Song rows. Empty/comment-only input may explicitly create an empty playlist after confirmation.

The default name is the selected filename stem, trimmed with the normal blank-name fallback; identical existing names do not trigger aggressive renaming or replace an existing playlist. User-facing diagnostics show counts rather than enumerating sensitive full source paths.

The UI uses ACTION_OPEN_DOCUMENT and ACTION_CREATE_DOCUMENT with local-only picker intent and no new broad-storage permission or web intent filter. It takes no persistable grant for these one-time operations. Streams/cursors use `use`, byte limits are checked while reading, and coroutine cancellation is propagated with checks between reads/provider batches. A synchronous provider call already in progress is platform-controlled. Cancellation before confirmed import leaves playlists unchanged; interrupted export can leave an incomplete newly selected output document, reported as such. Provider availability/readability beyond the one-time grant is not assumed.

### Export and round trip

Export is explicit from the existing Playlists surface. It writes deterministic UTF-8 `.m3u8`, LF line endings, a leading `#EXTM3U`, one local reference per available entry in persisted order, and a final newline. No EXTINF metadata rewrite or source mutation occurs. Proven absolute local paths are preferred; otherwise an existing supported content URI is emitted, with a warning that provider/library-local URIs are not portable filesystem paths. No fabricated SAF path or relative export rewriting is introduced. Invalid/unrepresentable local references reject export before the destination stream opens; current-library Song destinations are refused.

The writer itself preserves repeated input entries, while internal v1 playlists supply only unique Song IDs. Reimport against the same unchanged library preserves supported Song identities and order. Raw unavailable references cannot be exported as truthful playable locators because v1 has no source snapshot; export reports their omission without deleting those stored references. A partial/failed import or export never corrupts an existing playlist.

### Cost and verification scope

For n indexed Songs and p bounded references, import builds URI/path/name indexes once, then resolves in O(n + p + c) expected hash/index work (plus total text/path length), where c is the total candidate-bucket entries visited by per-reference Song-ID deduplication. Ordinary singleton buckets give approximately O(n + p); repeated lookups into a pathological n-sized ambiguous bucket can still cost O(n * p). The resolver does not scan the full library for every line. Memory is O(n + p + bounded document bytes); there is no per-line Room query, scanner ingestion, coroutine or remote fetch. Playlist mutations are O(p) row rewrites within the existing representation, in addition to database query/ordering costs; summaries use one aggregate query. IO is demand-driven by user actions, not playback ticks. Baseline Profile sources are retained without regeneration; repository constructor/summary flow, generated playlist DAO, transaction, parser/resolver/writer and file-action paths belong in normal release-cycle reassessment.

Q3.6 authority combines pure parser/repository tests, actual Room transaction/rollback and hidden-reference reconnection checks, synthetic API-36 import/export IO and semantic round trip, restart/order preservation, actual playlist UI reorder/remove and picker cancellation, and queue position/identity assertions. Local-file relative semantics and platform document-path mapping are distinguished from opaque-provider IO; this is not a universal third-party SAF-provider or OEM matrix. Final run results and release audit are recorded in the Q3.6 closeout report.

Q3.6 closeout accepts the corrected API-36 authority: `emulator-5554`, qemu `1`, AVD `LibrePlayer_Benchmark_API_36`, `OK (1 test)`, 26.750 seconds; the earlier 27.194-second pass is superseded for relative-directory evidence. Preserved final XML and HTML unit reports agree on **179 tests in 31 suites, zero failures/errors/skips**, correcting the handoff's 178-test tally. Final assemblies and standalone lint passed on the unchanged source; the preserved lint file predates the corrected Android run. Resume inspection changed documentation only and reran no unit, build, lint or Android gate. Cleanup left zero fixture MediaStore rows and no temporary Q3.6 documents/media; the emulator is stopped. Room v1, version 1.0.4/code 5, local-only release permissions and accepted packaged Baseline Profile remain unchanged. Q3.6 is accepted for handoff to Q3.7, which is not implemented here. Detailed limits, hashes and evidence remain in the local ignored `performance-results/q3.6-playlist/closeout-final.md` report.

## Q3.7 additive storage provenance migration

This migration is a separate review point. **Q3.7 remains OPEN**; removable-volume and LARGE authority have not been run. Room advances from v1 to v2; application version remains 1.0.4/code 5. The exported `app/schemas/com.libreplayer.data.database.AppDatabase/1.json` is the actual pre-change Room export, retained alongside v2. All seven existing table definitions are identical between the exports. `MIGRATION_1_2`, registered in the normal application builder, creates and populates only additive tables. The pre-existing destructive fallback is not used for this transition; exact v1 row comparisons through the production builder prove migration selection.

### Identity, provenance, and references

`Song.id` remains the compatibility identity. **No pre-existing Song ID is rewritten**, including legacy `media:<id>` and document IDs. Provenance and synchronization authority live outside `SongEntity`, whose fields and indexes are unchanged.

| Persisted reference | Migration contract |
| --- | --- |
| `songs.id`, `songs.isFavorite` | Every field retained byte-for-byte during migration |
| `playlists.id`, `playlist_songs.songId`, position and timestamps | No writes; existing hidden-reference/order semantics retained |
| `recently_played.songId` and timestamp | No writes; exact-ID visibility remains |
| DataStore playback queue JSON, current index, position, repeat, shuffle and play intent | No reads or writes by migration; exact persisted bytes verified |
| Current-item restoration | Existing queue IDs and saved duplicate-occurrence index remain its authority |
| Artwork cache | In-memory URI/revision/size cache; no persisted Song-ID cache to migrate |

Album/artist IDs are presentation groups, not Song-ID references. Settings and the old global refresh checkpoint contain no additional Song IDs. Active MediaItems and queue state are in memory. No metadata-based retry or playback behavior is introduced as migration evidence.

### V2 tables and constraints

`library_sources` stores a primary-key source ID, kind, provider authority, exact volume/root locator, an occurrence-incarnation counter, checkpoint version/generation, and the last committed reconciliation time. Source ID does not include checkpoint version or generation.

`song_sources` stores `(sourceId, incarnation, itemKey)` as a composite primary key, plus `songId`, exact observed content URI, an optional proven physical-path key, and `present`. Its source foreign key cascades only if the source record itself is deleted; production retains source records. Explicit indexes cover `songId` and `physicalKey`; the composite primary key also indexes `sourceId`. There is intentionally no Song foreign key: inactive aliases must survive Song deletion for exact reappearance, like existing hidden playlist/recent references. Multiple source/root memberships may reference one Song.

`legacy_song_protection` stores primary-key `songId` and a reason. It protects ambiguous legacy rows and rows retired across an unproven MediaStore epoch transition. None of these additions modifies an existing user table.

### Proof and new occurrence allocation

Migration claims a unique, exact volume-specific MediaStore URI, or a provider-qualified document with an exact persisted imported-root URI association. A tree/document URI must carry the exact registered tree ID; opaque document-ID prefixes are never evidence of ancestry. Duplicate legacy evidence remains protected, including the same provider document represented by conflicting old IDs through different roots. Unknown, aggregate `external`, malformed, or otherwise ambiguous rows remain unscoped. No title, artist, album, duration, filename alone, artwork, track/disc, or guessed volume/root can claim them.

Modern MediaStore scans enumerate the platform's actual attached volume names and query each volume's collection. Item identity includes its source scope and numeric `_ID`. New qualified IDs use deterministic length-framed components; delimiter and Unicode content cannot change component boundaries. An existing proven alias wins over the new spelling. A pre-existing arbitrary ID that already occupies a new spelling receives no overwrite: the new occurrence gets a framed deterministic disambiguator and a retained alias.

New SAF IDs use the exact provider authority and decoded document ID, independently of the tree grant used to reach that document. Two roots exposing the same provider document can therefore share one Song while retaining two exact grant URIs. Equal opaque IDs from different providers remain separate.

Physical equivalence is restricted to exact, volume-qualified MediaStore paths and the platform external-storage document provider's known `volume:path` convention, using the accepted Q3.1 path normalization. It can join MediaStore and SAF observations. It cannot collapse two distinct MediaStore item IDs, even when their relative paths match; multiple candidate Songs are ambiguous. Arbitrary providers never acquire filesystem identity from equal-looking opaque IDs. These rules supersede the pre-migration scanner's unsafe aggregate/path deduplication; the Q3.6 explicit playlist-path helper is unchanged.

### Legacy visibility and limits

Protected rows remain stored with their original favorites and user references, but are excluded from playable Song lookups, favorites, recents, playlist joins, and rebuilt album/artist projections. This prevents an old aggregate URI from silently resolving a newly mounted/replacement occurrence. It also avoids displaying both unresolved legacy rows and newly proven occurrences as playable duplicates. An exact future provider/document observation may claim an unscoped row; otherwise it stays unresolved. **A v1 aggregate MediaStore row generally cannot be automatically reconnected from its relative path alone.** Existing references may consequently remain hidden after upgrade; they are retained, not reassigned to similar new tracks.

The final compatibility audit checked the actual v1 scanner: it emitted aggregate `external` MediaStore URIs and saved only `RELATIVE_PATH` on API 29+, or null on older APIs. Those rows lack durable volume evidence; merely finding the same numeric ID on today's primary volume cannot prove the historical occurrence. This limitation can affect ordinary v1 MediaStore references. A legacy volume-specific URI is accepted without needing an extra v1 volume column, and exact registered SAF tree/document associations are accepted. Proven rows retain their IDs and playable visibility; equal metadata, an opaque prefix, or a currently unique aggregate match never substitutes for missing historical provenance.

Within a continuous platform namespace, an exact provider item ID remains the occurrence authority. A version change or generation rollback forces full enumeration and a new retained incarnation for new MediaStore occurrences; old unproven rows remain protected. Version strings and transient generation values are never serialized into Song IDs. An incarnation identifies a discontinuity in item-ID continuity, not ordinary synchronization progress.

API 30+ uses volume-specific version/generation and still checks current IDs for deletions. API 29 uses volume-specific version with full enumeration. API 26–28 accepts only platform-proven emulated primary storage, with exact canonical `_DATA` path included in the occurrence key and the legacy platform version API used for epoch detection. Versions are checked around enumeration; APIs without generation support use a fixed zero checkpoint sentinel and always enumerate fully. An old physical primary card or other unprovable namespace supplies no deletion/claim authority; an exact SAF grant is the available conservative path. These older-API branches are not removable-device authority. Provider violations of document-ID permanence, identical-path file replacement without observable epoch evidence, and cross-API storage remapping are not solved by metadata guessing.

### Reconciliation and reappearance

Each source collects its complete observation set before Room writes begin. Null cursors, loading SAF cursors, unreadable metadata, permission loss, provider exceptions, a volume disappearing, or a version/generation change during enumeration withhold that source's authority. A partial root result is discarded. Directory traversal uses exact visited document IDs to avoid cycles, without interpreting opaque IDs as paths. Cancellation propagates.

Each successful source has a Room transaction that reads current rows, attaches/upserts mappings, marks only its absent memberships inactive, deletes only unprotected Songs with no remaining present membership, updates visible album/artist groups, and commits its own checkpoint. Actual failure at the checkpoint write rolls back Song, group, membership, protection, and checkpoint changes together. Other source successes can commit independently. Legacy DataStore checkpoints are neither copied into sources nor consumed for deletion; DataStore retains only scheduling responsibility, and scheduling success is recorded after Room success.

Inactive mappings remain durable. An exact occurrence returning within the same proven namespace reconnects to the same Song ID, including a legacy ID; a different volume with the same numeric ID cannot steal its hidden playlist/recent/snapshot references. Normal authoritative Song deletion retains the existing row-bound favorite behavior; this migration does not add a separate favorite tombstone store. Losing one membership preserves a Song that has another. The remaining membership supplies its own content/grant URI when necessary. Explicit Remove Folder detaches only that root's memberships, retains aliases, and does not advance a scan checkpoint; revoked or unavailable registered roots preserve their prior authority.

### Bounded verification and profile follow-up

Authority includes synthetic v1 databases constructed from the exported schema, exact snapshots of all old tables, real playback DataStore byte comparisons, normal Room reopen, cross-scope/provider collisions, overlapping roots, protected ambiguity, deterministic allocation, source symmetry, confirmed absence/reappearance, and a real SQLite abort inside Room reconciliation. The API-36 test runner redirects application databases and preferences into a fresh cache directory; original app database/WAL/preferences hashes remain unchanged. Its provider and schema fixtures exist only in the instrumentation APK. A normal refresh uses the existing 2,000-row primary catalog, with zero metadata queries on an unchanged incremental pass; no media is provisioned or modified.

Targeted Q1.1e covers unchanged/add/delete/failure/reappearance/checkpoints. Q3.6 covers raw IDs, ordering, hidden-reference exclusion and exact reconnect. Focused Q2 invokes the existing MediaItem conversion for one preserved legacy and one new qualified Song. The changed playable projection receives a focused Q1.1d membership/group-count check and application-open smoke. There is no new long-lived resource owner, so no Q1.1g rerun. No broad Q2, historical synchronization matrix, removable-volume, LARGE, or Q3.8 continuation is included.

Baseline and startup profile source files are preserved without regeneration. Scanner enumeration/cache reuse, source identity/provenance reconciliation, generated source/song/playlist DAOs, migration/backfill, repository refresh, and checkpoint methods require the already-planned release-cycle profile reassessment. Packaging an accepted profile is not a claim that every old method descriptor still matches. Final logs, exact snapshots, APK/privacy inspection and the migration review report are under local ignored `performance-results/q3.7-storage/`.

Platform contracts: [MediaStore volumes, versions and generation](https://developer.android.com/reference/android/provider/MediaStore), [provider document identity](https://developer.android.com/reference/android/provider/DocumentsContract.Document#COLUMN_DOCUMENT_ID). The earlier equal-ID namespace cases are semantic injections, not an observation that API 36 allocated equal IDs on actual volumes.

Closeout recovered the completed final-code authorities on 2026-09-10: **199 unit tests in 33 suites, zero failures/errors/skips**; standalone lint (zero errors), debug/release assemblies, app/benchmark test assemblies, and the final API-36 run all passed. The final Android log reports **OK (2 tests), 5.518 seconds**, including application open after the test-only explicit `Unit` return correction. The separate provider's earlier Kotlin-runtime failure was also corrected only in test code. The saved screenshot shows the Songs screen; synthetic fixture URIs show an expected unavailable-file state, so this is application-open evidence, not audio-decoding authority. Recovery reused these results, compared every pre/post-migration ID and original table snapshot directly, and verified the recorded original-data and current APK/profile hashes. No build or Android authority was rerun, no production source changed during closeout, and the preserved host database was not recreated. No emulator was attached at recovery; its prior isolated cache artifacts were not accessed and no emulator was started for cleanup. The additive migration is accepted as the next review point; **Q3.7 remains OPEN**. The complete A–AQ report is local ignored `performance-results/q3.7-storage/migration-closeout-final.md`.

## Q3.3 coverage matrix

| Sort/search behavior | Authority | Classification |
| --- | --- | --- |
| All five exposed Song modes | Exact primary direction plus ascending deterministic ties and Song ID | Correct and covered |
| Equal/case/whitespace Song metadata | `Locale.ROOT` presentation keys; occurrence ID final tie | Corrected and covered |
| Album same-title ordering | Title, grouping artist, full Album ID | Corrected and covered |
| Artist punctuation/articles/unknown labels | Display key plus Artist ID; no stripping or merge | Corrected and covered |
| Album detail multidisc/2-vs-10/missing/duplicate ordinals | Numeric positive ordinals, missing last, title/ID tie | Corrected and covered |
| Sort persistence and invalid value | Existing DataStore enum; Title fallback | Correct and covered |
| Song/Album/Artist field scope | Explicit presentation fields; no path/ID/URI | Corrected and covered |
| Locale/case/punctuation/multi-word matching | `Locale.ROOT` contiguous substring | Corrected and covered |
| Missing versus literal Unknown search | Same searchable presentation allowed; identities remain distinct | Corrected and covered |
| Duplicate occurrence search results | Both Song IDs/content URIs retained | Correct and covered |
| Blank/no-match/clear | Non-search, empty result, full current browse after clear | Correct and covered |
| Rapid replacement | Single StateFlow; latest query wins | Correct and covered |
| Search plus sort | Membership retained; active order changes | Correct and covered |
| Active-query refresh | Same observable current Song snapshot; removed result disappears | Corrected and covered |
| Search-to-playback | Exact selected Song ID and content URI | Correct and covered |
| Fuzzy, typo-tolerant, transliterated search | Not a current product feature | Deferred |

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
