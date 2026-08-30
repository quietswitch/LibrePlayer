# Playback and queue semantics

This document is the Q2.1-Q2.2 contract for LibrePlayer 1.0.4. It describes current
product behavior; later playback work should preserve it unless a milestone
explicitly changes the contract.

## Authority and projections

- `PlaybackService` owns the one `ExoPlayer` and the one `MediaSession`.
- The player timeline is the authoritative queue. The player's current media
  item and index are authoritative for current identity and position.
- `PlaybackConnection` owns one app-scoped `MediaController`. Its `cachedQueue`
  is only a `Song` metadata projection of the controller timeline; it is not a
  second queue. `PlaybackViewModel` further refreshes that metadata from the
  live library by media ID without rewriting the player timeline.
- UI recreation reconnects to the app-scoped connection. A new controller that
  connects to a still-running service observes the existing player state.

## Commands

- Selecting a song in a library, album, artist, search result, or playlist
  replaces the player timeline with that displayed list in its displayed
  order. The selected occurrence becomes current at position zero and begins
  playing immediately. Existing repeat and shuffle settings are retained.
- Play and pause are Media3 commands and are idempotent. The UI toggle uses
  `playWhenReady` (playback intent), so it can pause buffering or temporarily
  suppressed playback as well as actively audible playback. Controller
  reconnection reads the service's state rather than replaying a UI command.
- Manual next uses `seekToNextMediaItem()`. It follows Media3's shuffle order,
  wraps only for repeat-all, and is a no-op at the final item with repeat off.
  Repeat-one affects natural completion but is deliberately ignored by manual
  next/previous, matching Media3.
- Previous restarts the current item when position is greater than 5 seconds.
  At or before 5 seconds it selects the Media3 previous item. If none exists,
  it is a no-op. In shuffle mode, “previous” means the previous item in the
  active shuffle traversal.
- Selecting an occurrence on the Queue screen seeks within the existing player
  timeline and starts playback. It does not reconstruct the queue or shuffle
  order. Occurrences are identified by index plus media ID, so duplicate songs
  are valid queue entries.
- “Add to queue” appends one item and preserves the current item. LibrePlayer
  exposes no playback-queue remove, move, or clear command in 1.0.4; playlist
  editing is separate and does not mutate the active player timeline.

## Shuffle and repeat

- Media3 creates and owns the shuffle order. Toggling shuffle keeps the current
  item current; next/previous then follow the Media3 traversal. The public queue
  remains in original timeline order, and its indices always refer to that
  order. Replacing a queue retains whether shuffle is enabled but creates the
  order appropriate to the new timeline.
- The UI repeat cycle is OFF → ALL → ONE → OFF. OFF naturally ends after the
  final item, ONE repeats the current item on natural completion, and ALL wraps
  the timeline. Manual navigation follows the command rules above.

## Seeking and position semantics

- The `ExoPlayer` owned by `PlaybackService` is the only live position clock.
  `Player.currentPosition`, expressed in milliseconds, is projected through the
  app-scoped controller to UI state. The projection lower-bounds transient
  framework sentinel values at zero; it does not run a second clock. Buffering,
  pause, and playback suppression therefore expose Media3's current position.
  A paused position remains stable. While playing, the existing 500 ms UI ticker
  samples the controller; it does not write persistence or issue seek commands.
- Duration comes from the controller when Media3 reports a positive value, with
  the current library song's positive duration as metadata fallback. Zero,
  negative, and `TIME_UNSET` duration values are unknown. The progress slider
  keeps a transient drag value so controller samples cannot overwrite an active
  drag, and sends one seek when the drag finishes rather than one per drag frame.
- An in-app seek keeps the current timeline and occurrence. Before forwarding it,
  LibrePlayer normalizes a negative request to zero, clamps it to a positive known
  duration, and caps an otherwise unbounded value to the largest millisecond value
  that can be converted safely to Media3's microsecond timebase. With unknown
  duration, a safe nonnegative value is forwarded and Media3 resolves it against
  the eventual timeline. No application-side pending-seek queue is maintained;
  ordered successive commands are sent directly, so the final accepted request is
  authoritative.
- Seeking does not alter `playWhenReady`: a playing or play-intended item remains
  intended to play, while a paused item remains paused. Seeking to zero means the
  start of the same occurrence. Seeking just before the known duration preserves
  that position; seeking to or beyond it is bounded to the duration. Completion or
  transition caused at the end remains Media3 behavior and is Q2.3 authority, not
  a separate Q2.2 promise.
- Queue replacement and library selection start the selected occurrence at zero.
  Queue-screen selection uses Media3's default position for the selected existing
  occurrence. Manual next/previous likewise use Media3 item-default positions;
  the Q2.1 previous-after-5-seconds restart explicitly seeks the current item to
  zero. A position from the old item is never supplied to the newly selected item.
- MediaSession controllers use Media3's standard seek command directly. The app's
  slider and ViewModel use the normalized in-app boundary above. Both command paths
  still act on the same service player and expose the same resulting player state.

### Position persistence and restoration

- Player timeline, item-transition, position-discontinuity, state, play-intent,
  repeat, and shuffle events request a debounced snapshot write. While there is
  meaningful active state, one periodic snapshot is also written every five
  seconds; the 500 ms UI sampling loop does not write DataStore. Service teardown
  performs one best-effort final save. A user seek is therefore captured promptly
  without per-frame or per-tick I/O.
- A snapshot contains timeline media IDs, current index/occurrence, nonnegative
  position, repeat/shuffle state, and `playWhenReady`. Restoration resolves the
  saved occurrence using the Q2.1 ID-plus-occurrence rule and bounds its position
  with that restored song's known duration. Negative positions become zero;
  positions beyond known duration become the duration; a safe nonnegative position
  is retained when duration is unknown. Positions just before the end are retained.
- If the saved occurrence no longer exists, its old position is discarded and the
  item at the clamped surviving index starts at zero. If no items survive, nothing
  is restored. Missing items before or after a surviving occurrence and duplicate
  IDs follow the Q2.1 rules. Restoration preserves saved `playWhenReady`, so paused
  intent stays paused and play intent stays requested; readiness and audible output
  remain subject to normal Media3 lifecycle and suppression behavior.

## Persistence and library changes

- The service periodically persists timeline media IDs in original order,
  current occurrence/index, position, repeat mode, shuffle-enabled state, and
  `playWhenReady`. An ordinary process restart restores those fields for songs
  still present in the library. The exact pre-restart randomized shuffle
  permutation is not persisted.
- If items before the saved current occurrence disappear, that occurrence is
  found by media ID and occurrence number and keeps its position. If the saved
  occurrence is missing, restoration selects the surviving item at the clamped
  saved index and resets position to zero. Missing items after current do not
  change current identity or position. If no items survive, the queue is empty.
- A library refresh does not rewrite a running timeline. Metadata is refreshed
  by media ID, with the media item's embedded metadata retained as a fallback.
  A stale-file playback error may replace only the current occurrence after a
  unique rediscovery match; otherwise the player remains stopped with an error.
- There is no explicit product “stop and forget queue” command. Service teardown
  saves the last meaningful snapshot; platform process removal is therefore
  subject to the same best-effort DataStore persistence behavior.

## Q2.1 coverage map

| Behavior | Coverage | Q2.1 classification |
| --- | --- | --- |
| list selection, queue replacement, current index, immediate play | bounded Media3/service check | correct but previously undertested |
| idempotent play/pause and controller reconnection | `PlaybackSemanticsTest` and bounded Media3/service check | buffering/suppression toggle defect corrected |
| next, final-item next, repeat modes, shuffle traversal | bounded Media3/service check | ambiguous tests replaced with real Media3 coverage |
| 5-second previous rule | `PlaybackSemanticsTest` and Media3/service check | correct but previously undertested |
| Queue-screen occurrence selection | unit validation and Media3/service check | actual defect corrected |
| duplicate occurrences in Queue UI | Compose occurrence key plus restoration tests | actual defect corrected |
| append | bounded Media3/service check | correct but previously undertested |
| remove, move, clear active queue | not applicable; no product command | defer unless a later block adds the feature |
| persistence with missing and duplicate items | `PlaybackRestoreTest` | correct and covered |
| exact shuffle permutation after process restart | explicitly not promised | defer unless the product contract changes |

## Q2.2 coverage map

| Behavior | Coverage | Q2.2 classification |
| --- | --- | --- |
| live position, duration fallback, 500 ms UI projection | architecture audit and bounded Media3/service check | correct but previously undertested |
| playing and paused seek intent | bounded Media3/service check | correct but previously undertested |
| rapid successive seeks, zero, ordinary middle position | semantic unit tests and bounded Media3/service check | correct but previously undertested |
| negative, extreme, known-duration, and unknown-duration requests | `PlaybackSemanticsTest` | ambiguous boundary corrected |
| slider drag isolation and one command on release | UI code audit | correct and covered by direct state-flow inspection |
| queue replacement, queue occurrence selection, next/previous default positions | Q2.1 unit and Media3/service checks plus Q2.2 stale-position check | correct and covered |
| event-driven and five-second position persistence | service code audit and bounded restoration check | correct but previously undertested |
| missing and duplicate restored occurrences | `PlaybackRestoreTest` | correct and covered |
| negative and beyond-duration restored position | `PlaybackRestoreTest` | actual unbounded-restoration defect corrected |
| natural completion and seek-to-end transition result | contract boundary only | deferred to Q2.3 |
