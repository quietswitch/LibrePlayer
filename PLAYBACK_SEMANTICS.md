# Playback and queue semantics

LocalTracklist retains this accepted LibrePlayer playback contract unchanged. The original product name and version below identify the authority under which it was established.

This document is the controlling Q2 contract for LibrePlayer 1.0.4. It describes
current product behavior; later playback work should preserve it unless a
milestone explicitly changes the contract.

## Playback Mastery — Q2 Controlling Contract

- `PlaybackService` owns one `ExoPlayer` and one `MediaSession`; foreground UI,
  notification, media keys, and external controllers operate on that shared
  player authority. Playback remains service-owned without an Activity.
- The Media3 timeline, current item, and occurrence index are authoritative.
  Duplicate media IDs remain distinct by occurrence/index. Media3
  `currentPosition` is the live clock; persisted position is restoration metadata,
  not a second position authority.
- Playback intent is represented by `playWhenReady`, audible progress by
  `isPlaying`, and temporary constraint by `playbackSuppressionReason`. Final
  `STATE_ENDED` and fatal `PlaybackException` state remain distinct.
- Previous uses one foreground/system boundary: `> 5,000 ms` restarts the current
  occurrence; `<= 5,000 ms` selects the previous occurrence or is a first-item
  no-op.
- Seeks preserve the current occurrence and play intent, use the bounded Q2.2
  normalization rules, and leave end-boundary transitions to Media3. Natural,
  repeat, shuffle, and manual traversal all retain Media3 timeline authority and
  the single gapless-capable playlist path without app stop/reprepare.
- Media3 owns audio focus. Transient focus suppression retains play intent and may
  resume on gain; user Pause cancels that intent. Media3 becoming-noisy handling
  instead creates a true safety pause with no suppression and requires explicit
  Play.
- Failure recovery is user-directed. A fatal item remains current with a stable
  error; Play retries it once, while Next, Previous, or direct selection may leave
  it and recover. LibrePlayer has no auto-skip, auto-retry, or failure watchdog.
- Q2.8 establishes bounded long-session continuity, transition, lifecycle,
  recovery, and resource behavior. It adds no watchdog, reset architecture,
  second player/session, or universal device-endurance claim.

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
- Foreground and MediaSession/system Previous use one boundary: position greater
  than 5,000 ms restarts the current occurrence; at or below 5,000 ms it selects
  the Media3 previous occurrence, or is a no-op at the first occurrence. In
  shuffle mode, “previous” means the previous item in the active shuffle
  traversal.
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

## Track transition and gapless semantics

### Natural and manual boundaries

- Media3 owns automatic playlist transitions on the single service `ExoPlayer`.
  For an ordinary A → B boundary, LibrePlayer does not call `stop()`, clear or
  rebuild the timeline, create another player, or call `prepare()`. Media3 emits
  `MEDIA_ITEM_TRANSITION_REASON_AUTO` and
  `DISCONTINUITY_REASON_AUTO_TRANSITION`; B becomes current at its default
  position with play intent active. The original timeline and MediaSession stay
  intact, and the player does not enter `STATE_ENDED` between playable neighbors.
- With repeat off, natural completion of the final item retains that final media
  ID and index, keeps the timeline, reaches `STATE_ENDED`, and reports
  `isPlaying == false`. Media3 retains `playWhenReady == true`; LibrePlayer treats
  the visible Play action in this state as a request to restart the same final
  occurrence at its default position, rather than requiring a misleading pause
  action first. Replacing the queue after ended state starts the requested new
  occurrence normally.
- Repeat one naturally restarts the same logical item at zero. With Media3 1.9.2
  on the API 36 authority emulator this is observable as an automatic position
  discontinuity without a logical media-item identity transition. Manual next
  remains the Q2.1 rule: it selects the next timeline item and produces the
  normal seek transition even while repeat one is enabled.
- Repeat all naturally wraps the final item to the first with Media3's automatic
  transition reason. Shuffle follows `nextMediaItemIndex` from Media3's current
  shuffle traversal; LibrePlayer neither predicts nor replaces that order.
- A manual next/previous updates current identity and starts the selected item at
  its default position. It does not destabilize later automatic traversal. The
  Q2.1 five-second previous restart remains an in-item seek, not a track change.
- A near-end seek first produces the expected seek discontinuity; when the
  remaining media completes, the ordinary automatic transition follows. A Q2.2
  seek normalized exactly to a non-final item's duration produces the same
  automatic successor transition on Media3 1.9.2: the successor is current at
  its default position, with an automatic transition and discontinuity after the
  seek discontinuity. Seeking to the final item's duration follows final-item
  ended semantics.
- A paused item does not progress or transition merely because wall-clock time
  passes. Natural transitions require media progression.

### Projection, background, and persistence

- Transition identity and index propagate from the controller timeline through
  `PlaybackConnection` to ViewModel/UI state. No Activity is required: the
  service completes transitions while the app is backgrounded, and foreground
  return observes the already-current successor. A controller released before a
  boundary and reconnected afterward observes the same service player, correct
  successor, active play intent, and near-start position.
- Media-item transition and position-discontinuity events use the existing
  bounded persistence path. The next persisted snapshot contains the successor
  occurrence/index and that item's own position; the completed item's terminal
  position is not copied into it. No additional write loop or transition-specific
  database cadence is introduced.
- Adjacent preparation remains Media3-owned. LibrePlayer makes no promise about
  an exact preload instant and adds no second player or application preloader.

### Gapless authority levels

- **Level 1 — playlist transition correctness: established.** Same player and
  MediaSession, intact timeline, expected automatic/repeat/seek events, correct
  successor position and intent, no player error, no unexpected session loss,
  and no intermediate ended state were verified with real Media3 playback.
- **Level 2 — gapless-capable playback-path evidence: established for the tested
  pairs only.** Debug-only observation of 48 kHz mono WAV→WAV, FLAC→FLAC,
  MP3→MP3, and AAC/M4A→AAC/M4A pairs found no audio underrun, codec/sink error,
  application pause/stop/reprepare, intermediate ended state, or active
  AudioTrack teardown before the successor was established. Media3 exposed
  input-format and sink configuration at both sides. The MP3 pair exposed
  encoder delay 576 and padding 1344; AAC/M4A exposed delay 1024 and padding 0;
  WAV and FLAC exposed zero delay/padding. These observations demonstrate that
  LibrePlayer preserves Media3's metadata-aware, gapless-capable path, not that
  every platform decoder or format pair is acoustically seamless.
- **Level 3 — sample-perfect/acoustic gapless authority: NOT ESTABLISHED.** No
  captured PCM or acoustic output proves that zero samples were inserted,
  dropped, or altered at a physical output boundary. Event timing, emulator
  playback, metadata, and absence of underruns are insufficient for that claim.

## Audio focus and interruption semantics

### Authority and play-intent model

- The single service `ExoPlayer` is configured with Media3 audio attributes
  `USAGE_MEDIA` and `AUDIO_CONTENT_TYPE_MUSIC`, with automatic focus handling
  enabled. Media3 owns `AudioManager` focus requests, losses, gains, and ducking;
  LibrePlayer has no second production focus request, focus listener, or
  application pause/resume state machine.
- `playWhenReady` represents user playback intent. `isPlaying` reports whether
  media is actually advancing, and `playbackSuppressionReason` explains a
  temporary platform constraint. Therefore `isPlaying == false` is not by
  itself a user Pause.
- The primary transport control represents the action it will perform. Ordinary
  playing, buffering with active play intent, and transient focus suppression
  display Pause because activating the control cancels intended playback or
  automatic resume. An explicitly paused player displays Play. Q2.3's final
  `STATE_ENDED` case remains an exception: it displays Play and restarts the
  final occurrence even when Media3 retains `playWhenReady == true`.

### Focus changes

- On API 36 with Media3 1.9.2, transient loss retains the item, queue, index,
  `STATE_READY`, and `playWhenReady == true`, sets
  `PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS`, stops `isPlaying`,
  and prevents material position advancement. Gain clears suppression and
  automatically resumes only while that play intent remains true.
- If the user presses Pause during transient loss, `playWhenReady` becomes
  false with `PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST`, transient suppression
  clears, and later focus gain does not resume playback.
- With music attributes, `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` granted to another
  app leaves LibrePlayer ready, unsuppressed, and playing with position
  advancing. Media3/platform owns the output-level change. Q2.4 establishes the
  policy and state path, not an exact acoustic attenuation.
- A permanent focus loss leaves the current item and queue intact but changes
  `playWhenReady` to false with
  `PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS`; the player remains ready,
  unsuppressed, and not playing. Merely abandoning the competing focus grant
  does not restart playback. A later explicit Play is a user request that
  reacquires focus and continues from the coherent current position.

### Background, reconnection, and persistence

- Focus handling belongs to `PlaybackService` and does not require an Activity.
  The service remains foreground during transient suppression, resumes after
  gain when intent remains active, and projects the same state when the UI
  returns.
- A controller connected during transient suppression observes the same item,
  index, ready state, active play intent, transient suppression reason, stopped
  position, and MediaSession. It observes the resumed state after gain.
- Existing event-driven/five-second persistence stores `playWhenReady == true`
  during temporary suppression rather than inventing a user Pause. Explicit
  Pause and permanent focus loss eventually persist false. No focus-event log,
  database write loop, or increased persistence cadence is introduced.
- Authority is bounded to Android audio-focus grants on the API 36 reference
  emulator. It does not represent a cellular/VoIP call, OEM compatibility
  matrix, output-route or becoming-noisy policy, or acoustic duck measurement.

## Background, MediaSession, and system-control semantics

### Service and session ownership

- `PlaybackService` is the sole playback owner. Its one `ExoPlayer` is the media
  state authority and its one `MediaSession` is the system-facing authority.
  Controllers, including LibrePlayer's application-scoped `PlaybackConnection`,
  observe and command that session; they do not own duplicate playback state.
- Media3 creates the service when the first controller connects, maintains its
  foreground media notification, and applies the platform foreground-service
  lifecycle. Service destruction releases the session, player, listeners, save
  jobs, and service scope exactly once.
- Intentional playback continues when the Activity backgrounds, is recreated, or
  its task is dismissed. No Activity callback is required. Reopening the Activity
  observes the existing queue, occurrence, position, modes, play intent,
  suppression, and playback state without replacing or restarting them.
- A controller released while the service remains alive can reconnect to the
  existing session. The session's queue and player state replace any stale local
  projection; reconnection does not create a player or session.

### External transport commands

- Standard controller commands for Play/Pause, seeking, next/previous traversal,
  repeat, and shuffle remain available when supported by the current timeline.
  LibrePlayer adds no custom session command.
- External Pause clears play intent, including while audio-focus-suppressed, and
  therefore cancels pending automatic resume. External Play resumes ordinary
  paused playback. At final repeat-off `STATE_ENDED`, the first external Play
  restarts the final occurrence from its default position, matching Q2.3.
- External Next follows Media3 timeline traversal, including manual traversal
  while repeat-one is selected and shuffle's authoritative next occurrence.
  External Previous uses the same accepted Q2.1 boundary as the foreground
  control: greater than 5,000 ms restarts the current occurrence; at or below
  5,000 ms it selects the previous timeline occurrence when one exists, or is a
  no-op at the first occurrence.
- External ordinary seeks remain Media3 commands against the live player. The
  player supplies duration/timeline normalization and remains the live position
  authority; Q2.5 does not add another seek state or polling path.

### Notification and lifecycle policy

- Media3 owns the single media notification and derives it from the same session,
  player state, available commands, and `MediaItem.MediaMetadata`. Automatic
  transitions update its synthetic-tested identity without an Activity. Q2.5
  does not customize notification layout, artwork, colors, or typography.
- Dismissing the task during active intentional playback retains the service,
  foreground-service state, one notification key, session, and advancing
  position. On the API 36 authority emulator, a paused task dismissal retained
  the service, session, queue, position, and Play notification while Media3
  removed the notification's foreground-service flag. A final-ended isolated
  projection retained the final item and one notification with a Play action;
  Media3 owns the bounded transition out of foreground state and eventual idle
  service destruction. LibrePlayer does not make the service immortal or add a
  competing task-removal policy.
- There is no separate user-facing Stop or notification-dismiss contract. Test
  probes may clear their synthetic queue for cleanup, but product controls do not
  expose that operation.
- While a session is active, routed media-key Play/Pause, Next, and Previous use
  the same session-player command paths. Cold media-button playback resumption is
  not established: LibrePlayer does not declare `MediaButtonReceiver` or add the
  required cold-resumption callback in Q2.5.
- Normal backgrounding, Activity recreation, task dismissal, and process death
  are distinct. Existing persisted restoration can reconstruct a saved local
  queue, occurrence, normalized position, repeat, shuffle, and play intent after
  process recreation; it does not promise uninterrupted playback while the
  process is dead or universal process-survival behavior.

## Q2.5 coverage map

| Behavior | Coverage | Classification |
|---|---|---|
| Foreground to background | B1 real service/session/notification and position observation | Established |
| Activity destroy/recreate | B2 configuration recreation with PID/session/queue continuity | Established |
| Controller reconnect | B3 real external controller release/reconnect, 500 ms projection tolerance | Established |
| System Pause/Play | B4 external `MediaController` commands | Established |
| System Next | B5 external traversal of the Media3 timeline | Established |
| System Previous | B6 external previous-item and restart cases; focused 4,999/5,000/5,001 ms boundary | Established |
| System seek | B7 one ordinary external seek against the live player | Established |
| Background natural transition | B8 short-to-long synthetic successor plus session, notification, and reopened UI | Established |
| Final-ended restart | B9 final `STATE_ENDED` and first external Play restart; isolated notification Play proof | Established |
| Focus suppression | B10 transient real focus aggressor and external Pause cancellation | Established |
| Playing task removal | B11 real Recents dismissal and reopen | Established |
| Paused task removal | B12 real Recents dismissal with retained paused session/notification and non-foreground notification | Established |
| Media keys | B13 routed Pause, Play, Next, and Previous key events | Established for an active API 36 session |
| Process death | Accepted Q2.1/Q2.2 restoration authority; no new matrix | Bounded |
| Cold media-button resumption | No receiver/callback contract | Deferred |

## Device switching and output robustness semantics

### Output-route authority and safety policy

- Android owns physical audio routing and Media3 owns LibrePlayer's route-loss
  safety response. `PlaybackService` configures music/media `AudioAttributes`,
  delegates focus to Media3, and explicitly enables
  `setHandleAudioBecomingNoisy(true)` on its sole `ExoPlayer`. There is no
  LibrePlayer route manager, Bluetooth state machine, wired-headset receiver,
  `AudioDeviceCallback`, route polling loop, or second player/session.
- Media3 1.9.2's installed bytecode registers an application-context receiver
  for `android.media.AUDIO_BECOMING_NOISY` while the player is enabled and
  unregisters it on release. Its callback sets `playWhenReady` to false with
  `PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY` (3).
- A becoming-noisy event while playing is therefore an actual safety pause:
  the current item, occurrence, queue, ready state, and coherent position are
  retained; `playWhenReady == false`, `isPlaying == false`, and suppression
  remains `PLAYBACK_SUPPRESSION_REASON_NONE`. Audio must not continue merely
  because Android could reroute it to a speaker.
- Output availability by itself never creates a LibrePlayer Play request. After
  a safety pause, route return leaves playback paused and explicit user/system
  Play is required. A noisy event while already paused cannot start playback or
  alter the queue. Media3 may report the noisy reason when it replaces another
  unchanged paused reason; repeated identical noisy events are state-idempotent.
- LibrePlayer does not pause merely because an `AudioDeviceInfo` changed. A safe
  handoff remains Android/AudioFlinger/Media3 routing authority and should keep
  playback live. The API 36 emulator exposed no supported route-switch command,
  wired endpoint, or A2DP endpoint, so that distinct handoff was not simulated.

### Service, session, persistence, and focus distinction

- The noisy receiver belongs to the service-owned player, so it works without a
  foreground Activity. The existing `MediaSession`, controller projection, UI,
  and Media3 notification all observe the paused state. The notification changes
  its primary action to Play; the first Play resumes the retained item.
- Controller release/reconnect after the event observes the same item, index,
  queue, position, ready state, false play intent, no suppression, and the same
  single session. Existing event-driven persistence stores
  `playWhenReady == false`; later controller or process restoration cannot infer
  permission to resume merely from output availability.
- Route loss is not transient focus loss. A noisy pause clears play intent with
  reason 3 and uses no playback suppression. Transient focus loss retains
  `playWhenReady == true`, uses transient-audio-focus suppression, and resumes on
  gain unless the user cancels intent. Q2.4 remains the focus authority.
- Current debug observation uses only privacy-safe `AudioManager` output device
  type integers and Player callbacks. It registers no device callback and stores
  no endpoint name or address. The protected noisy broadcast cannot be originated
  by the Android 16 shell or app UID; the userdebug reference AVD therefore used
  one bounded root-origin broadcast to the real Media3 receiver. This apparatus
  and output-type observation are absent from release.

### Hardware authority limits

- The API 36 authority emulator's active media output was its built-in speaker;
  it had no connected wired or Bluetooth A2DP endpoint. Q2.6 establishes the
  shared Android/Media3 becoming-noisy state contract, not a claim that every OEM
  emits an identical sequence for every accessory.
- Wired insertion/removal and Bluetooth A2DP disconnect/reconnect remain limited
  to the established platform/noisy contract; physical endpoint confirmation is
  deferred. No Bluetooth permission, physical-reference package, existing user
  installation, app data, personal media, or device identifier was used.
- Universal Bluetooth behavior, OEM routing variations, user-selectable output,
  codec/DAC paths, latency, bit-perfect output, and acoustic continuity remain
  outside Q2.6.

## Q2.6 coverage map

| Behavior | Coverage | Classification |
| --- | --- | --- |
| Baseline output and active playback | D1 real service/player/session plus privacy-safe device-type observation | Established on API 36 emulator |
| Becoming noisy while playing | D2 protected system broadcast to Media3 receiver; exact Player reason/state | Correct but previously untested |
| Route return and explicit resume | D3 stable safety pause plus D4 normal Play | State policy established; physical return deferred |
| Noisy while paused | D5 protected broadcast against an already paused player | Correct and covered |
| Background handling and notification | D6 background service event, Play action, reopened UI | Correct and covered |
| Controller reconnect | D7 external controller release/reconnect | Correct and covered |
| Safety-pause persistence | D8 existing snapshot-store observation | Correct and covered |
| Repeated noisy sequence | D9 two bounded protected broadcasts | Correct and covered |
| Safe route handoff without noisy | No supported route switch on reference emulator | Limited / deferred |
| Natural transition near route event | D11 one automatic A→B then noisy, retained successor timeline | Correct and covered |
| Wired removal | No wired endpoint or credible emulator control | Physical confirmation deferred |
| Bluetooth route loss | No A2DP endpoint; no OEM/device matrix | Physical/OEM confirmation deferred |

## Adversarial media and failure recovery semantics

### Failure authority and taxonomy

- Media3 remains the playback and error-code authority. LibrePlayer does not add
  a decoder, extractor, failure classifier, retry worker, recovery queue, or
  scanner trigger. A fatal local-source error leaves the one `ExoPlayer` and one
  `MediaSession` alive with the failed occurrence current and the queue intact.
- The API 36 / Media3 1.9.2 authority matrix established these real paths:
  unavailable file → `ERROR_CODE_IO_FILE_NOT_FOUND` (2005) backed by
  `FileDataSourceException`; zero bytes and deterministic garbage →
  `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` (3003) backed by
  `UnrecognizedInputFormatException`; deterministically truncated FLAC →
  `ERROR_CODE_IO_UNSPECIFIED` (2000) backed by `EOFException`.
- A separate deterministic decoder initialization/runtime failure was not
  available from the supported synthetic formats. Decoder-specific behavior is
  therefore deferred rather than inferred from extractor or I/O failures.
- Deleting a benchmark-owned successor or current FLAC after the queue started
  did not interrupt playback on the authority emulator: Media3 retained an
  already-open/buffered file descriptor. This establishes state coherence, not
  a promise that deletion must be observed immediately on every filesystem.

### Accepted recovery policy: USER-DIRECTED

- LibrePlayer stops on the failed occurrence. It does not automatically skip,
  retry, remove, blacklist, or rebuild around the item. Stable failure state is
  `STATE_IDLE`, `playWhenReady == false`, `isPlaying == false`, suppression
  `NONE`, current failed identity/index retained, and a bounded UI message.
- Next, Previous, or selecting a different occurrence is one recovery action.
  If that action actually leaves an errored occurrence, the session player
  prepares and plays the selected occurrence. Normal transport is unchanged
  when no error exists, and Next at the failed final item does not retry it.
- Play on the failed occurrence remains a deliberate retry. The existing
  library lookup can replace a stale item with a uniquely rediscovered record;
  otherwise the unavailable message remains. Each press is one attempt—there is
  no automatic or unbounded retry.
- A valid→bad→valid queue stops on the bad successor. It never leaps to the next
  valid item without a command. Consecutive bad items fail once per explicit
  traversal; an all-invalid three-item queue settles on its final error with no
  state oscillation, busy loop, repeated prepare, or rapid transition cycle.

### UI, service, session, and persistence

- `PlaybackConnection` projects Media3 errors into the existing mini-player and
  Now Playing error text without hiding item/queue identity. A transition onto
  a bad successor no longer clears that message while `playerError` is active;
  successful prepare clears the stale message through `onPlayerErrorChanged`.
- The safety pause is persisted when play intent changes, including the case
  where playback is already non-playing at the fatal callback. The snapshot
  retains queue IDs, failed index, coherent position, and
  `playWhenReady == false`; exception codes, messages, causes, and blacklists are
  not persisted. Restoration therefore cannot inherit stale active play intent.
- Background failure retains the service, single session, failed item metadata,
  and a notification with Play. An external session Next recovers to a valid
  item and updates the notification to Pause without opening the Activity.
  Controller release/reconnect observes the same stable error and session.
- Recovery does not clear the queue or mutate media, playlists, favorites, Room
  song records, or the library catalog. Playback failure and library
  reconciliation remain separate: a later normal MediaStore/SAF synchronization
  may reconcile a genuinely missing record, but Q2.7 never triggers one.

### Deterministic fixture authority

- Debug-only fixture generation copies existing synthetic FLAC assets into the
  app cache and creates the malformed variants from fixed bytes. The files are
  regenerated for each scenario and removed during probe cleanup. No malformed
  file, fixture provider, personal metadata, or benchmark component is packaged
  in release.

| Fixture | Size | SHA-256 / identity | Intended category |
| --- | ---: | --- | --- |
| `good-a.flac`, `good-c.flac` | 34,617 B each | `99ba43f6984bb05a8753f0edf3df44f2f10a371f0d2f4a161a7401c1e1b91122` | valid baseline/transition |
| `good-long.flac`, `deletable.flac` | 272,022 B each | `92cf7501065e8c0c0582f5e157940fb240b9bf0aeed6eeca06520fc592ae94f4` | valid long/deletion |
| `missing.flac` | absent | deterministic nonexistent cache path | source unavailable |
| `zero.flac` | 0 B | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` | empty container |
| `garbage.mp3` | 3,072 B | `33986ba530755f39dbc3d5f8a412108357bc4e05f8dee94edbc0cb4091e1472d` | unrecognized container |
| `truncated.flac` | 8,192 B | `6c5886891619a5805992ceeb1ef580608cbabbd4af3c571c4d32aef3cf729260` | truncated I/O |

## Q2.7 coverage map

| Behavior | Coverage | Classification |
| --- | --- | --- |
| Valid baseline | E1 real local file, service, session, and advancing position | Established |
| Missing source and recovery | E2/E3 real absent file plus one UI Next | Recovery defect corrected |
| Zero and garbage files | E4/E5 real extractor failures plus system Next/direct selection | Established |
| Truncated source | E6 real EOF-backed FLAC failure plus Previous | Established |
| Bad natural successor | E7 automatic valid→bad boundary and explicit recovery | Error-message/recovery defect corrected |
| Consecutive/all-invalid queues | E8 one error per command and stable final failure | Bounded; no auto-skip loop |
| Background/system recovery | E9/E11 notification, session, external Next, reopened UI | Established |
| Controller reconnect | E10 live errored session projection | Established |
| Normal transition after error | E12 recovered valid A→C | Established |
| Seek/focus/noisy after recovery | E13/E14 plus focused Q2.6 path | Established |
| Failed play-intent persistence | Existing snapshot store observed after error | Stale-intent defect corrected |
| File deleted after queue/start | Cache-only successor/current deletion | Open-descriptor continuation established |
| Decoder init/runtime failure | No deterministic supported fixture | Deferred |

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

## Q2.3 coverage map

| Behavior | Coverage | Q2.3 classification |
| --- | --- | --- |
| ordinary A→B→C automatic completion and near-start successor position | bounded Media3 transition matrix | correct but previously undertested |
| final item with repeat off and replacement after ended | bounded Media3 transition matrix | final Play-action defect corrected |
| repeat-one natural versus manual next | bounded Media3 transition matrix plus semantic unit test | previously ambiguous; now established |
| repeat-all wrap and authoritative shuffle successor | bounded Media3 transition matrix | correct but previously undertested |
| near-end and exact-duration seeks | bounded Media3 transition matrix | Q2.2 boundary consequence established |
| paused non-progression | bounded Media3 transition matrix | correct and covered |
| background transition and foreground projection | dedicated API 36 integration | correct but previously undertested |
| controller release/reconnect around boundary | dedicated API 36 integration | correct but previously undertested |
| successor occurrence/index and position persistence | DataStore observation after automatic transition | correct but previously undertested |
| WAV, FLAC, MP3, and AAC/M4A path continuity signals | debug AnalyticsListener/AudioSink observation | Level 2 established for tested pairs |
| sample-perfect emitted-audio continuity | no PCM/acoustic capture authority | explicitly not established |

## Q2.4 coverage map

| Behavior | Coverage | Q2.4 classification |
| --- | --- | --- |
| music/media attributes and Media3-owned focus | service architecture audit plus F1 | correct and covered |
| transient loss/gain and position suppression | real API 36 focus grants, F2 | correct but previously untested |
| primary control during buffering/suppression and final ended | shared semantic helper unit test plus F2/F3 | actual UI/action mismatch corrected |
| user Pause during transient loss | real API 36 focus grant and UI-command path, F3 | correct after shared-control correction |
| transient-can-duck music policy | real API 36 focus grant, F4 | platform/Media3 policy established; attenuation unclaimed |
| permanent loss and explicit later Play | real API 36 focus grants and Player reasons, F5/F6 | correct but previously untested |
| background service focus behavior | real API 36 background sequence, F7 | correct but previously untested |
| controller reconnect while suppressed | real API 36 reconnect sequence, F8 | correct but previously untested |
| rapid bounded loss/gain sequence | two real transient cycles, F9 | correct and covered |
| play-intent persistence during transient/permanent loss | DataStore observation during F2/F3/F5 | correct but previously untested |

## Long-session playback reliability

Q2.8 adds a controlled long-session reference on the API 36
`LibrePlayer_Benchmark_API_36` emulator. It does not add production playback
machinery. The service still owns one `ExoPlayer`, one `MediaSession`, one
fixed service listener, one lifecycle-bound service scope, the existing
event-driven plus approximately five-second active-state persistence path, and
the application-scoped controller with its replace-on-connect UI ticker. No
listener or job is registered per transition, reconnect, or Activity
recreation; no watchdog, player reset, queue reset, service restart, or retry
loop was introduced.

### Wall-clock authority

- One 45-minute run completed in 2,700.186 seconds. The same app PID and one
  MediaSession remained present from T+0 through T+45, the playback service
  stayed foreground, and the final state was READY, playing, unsuppressed,
  connected, and coherent at queue index 0.
- The schedule included background plus a bounded two-minute screen-off
  interval, one controller release/reconnect, one Activity recreation, one
  seek, three unchanged library refreshes, and one stable Pause/Play cycle.
  Screen-off establishes only **BOUNDED SCREEN-OFF PLAYBACK CONTINUITY**; it is
  not Doze, physical battery, or OEM power-management authority.
- The run completed 135 natural transitions with exactly 135 automatic
  discontinuities, two explicit seek discontinuities, zero player errors, and
  zero unexpected session disconnects. All three refreshes retained the
  original catalog count and healthy playback.
- PSS ranged from 150,239 to 168,298 KiB after startup, RSS declined from
  261,880 to 213,436 KiB, and thread checkpoints ranged from 40 to 50. The
  result is **bounded fluctuation**, not a retained-memory ratchet. The Android
  shell could not enumerate `/proc/<pid>/fd`, so no descriptor-count claim is
  made.
- Persisted state existed at every checkpoint, remained 415–416 bytes, and
  ended with coherent queue, item, index, position, and play intent. The
  existing event-driven and approximately five-second active snapshot cadence
  remains unchanged; the 500 ms UI ticker has no persistence write path.

### Transition and lifecycle authority

- The transition-count axis completed the bounded target of 200 natural
  transitions in 400.468 seconds over five unique items. Checkpoints at 0, 50,
  100, and 200 retained coherent wrap/index state, one player/session, zero
  errors or disconnects, and exact automatic transition/discontinuity parity.
- A supplementary sequence completed 12 shuffle-authoritative transitions and
  6 repeat-all transitions. Disabling shuffle and restoring ordinary
  repeat-all remained coherent; the final total was 218/218 automatic events.
- Control/lifecycle churn completed 10 Pause/Play cycles, 10 seeks, 12 Next,
  6 Previous, 10 controller reconnects, 5 rotations, 5 background/foreground
  cycles, and 5 unchanged refreshes. It ended READY and playing with exactly
  one session, no controller accumulation, no stale exception, and no stuck
  suppression.
- One late missing-source case retained the Q2.7 user-directed policy: error
  2005 was observed, one system Next recovered, and three later natural
  transitions completed with no stale error contamination. The complete Q2.7
  E1–E14 matrix was not rerun.

### Supporting authority and limits

One targeted Q1.1f unchanged refresh against the canonical 2,000-track MEDIUM
catalog retained the exact identity fingerprint, advanced the same known item
by 1,994 ms in a 2.002-second observation, and produced zero transition,
discontinuity, disconnect, or error events. A separate Q1.1g rerun was not
needed: production lifecycle/resource code did not change, Q1.1g remains the
controlled short-session authority, and Q2.8 supplies playback-specific
long-session evidence. Process-death restoration remains bounded by accepted
Q2.1/Q2.2 authority. Baseline Profiles were not regenerated because Q2.8 adds
no production path.

This reference does not establish multi-day endurance, universal OEM behavior,
physical thermal/battery behavior, Bluetooth endurance, Android Auto behavior,
or a universal memory threshold. Exact checkpoints, fixture hashes, recovery
history, and the invalid benchmark-only post-wake race are retained in
`performance-baselines/q2.8-long-session.json`.

## Q2 regression routing and authority limits

| Change surface | Required focused authority |
| --- | --- |
| Queue or core playback commands | Q2.1 |
| Seek, position, or restoration | Q2.2 |
| Transition, repeat, shuffle, or gapless-capable path | Q2.3 |
| Audio focus, play intent, or suppression | Q2.4 |
| MediaSession, notification, background, or media keys | Q2.5 |
| Becoming noisy or output safety | Q2.6 |
| Playback error or recovery | Q2.7 |
| Service, player, listener, or long-lived resource behavior | Q2.8 |
| Broad playback release candidate | One bounded Q2.9 integrated acceptance |
| Previous-threshold behavior | Q2.1 and Q2.5 focused exact-boundary authority |

Do not run every playback authority for every change. Route a change to the
smallest controlling authority above, expanding only when its architecture or
cross-surface behavior requires it.

Q2 does not establish sample-perfect/acoustic gapless output, exact acoustic
duck attenuation, physical wired or Bluetooth/OEM route behavior, Bluetooth
endurance, cold media-button resumption without an active session,
deterministic decoder-init/runtime-failure behavior, multi-day endurance,
universal OEM behavior, physical battery/thermal endurance, Android Auto,
exact DAC/output-path behavior, or a high-resolution/DSP/advanced audio engine.
Those explicit deferrals do not weaken the controlled playback semantics Q2
does establish.
