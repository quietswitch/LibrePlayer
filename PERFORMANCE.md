# Performance Development Authority

Q1.1 separates infrastructure validation from performance claims. Q1.1b defines deterministic inputs and a reproducible v1.0.4 reference. Q1.1c adds controlled cold-start regression authority for a cached MEDIUM library. Q1.1d adds controlled interactive library-UI authority on API 36. Q1.1e adds controlled MediaStore-to-LibrePlayer synchronization authority. None of these channels defines universal device performance or a product pass/fail threshold.

## Fixture profiles

Canonical profiles live in `tools/performance-fixtures/profiles.json`:

| Profile | Tracks | Albums | Artists |
| --- | ---: | ---: | ---: |
| EMPTY | 0 | 0 | 0 |
| SMALL | 100 | 10 | 10 |
| MEDIUM | 2,000 | 200 | 100 |
| LARGE | 10,000 | 1,000 | 500 |
| STRESS | 50,000 | 5,000 | 2,000 |

The fixed generator configuration covers repeated albums and artists, distinct files with duplicate visible metadata, missing metadata and artwork, Unicode/combining/emoji/right-to-left/long text, deterministic track/disc numbers, multidisc albums, album-level ordinary and oversized generated artwork, favorites, and playlist memberships.

Generate and validate SMALL or MEDIUM with Python 3:

```powershell
python tools/performance-fixtures/fixture_tool.py generate --profile SMALL --output tools/performance-fixtures/generated/SMALL
python tools/performance-fixtures/fixture_tool.py validate --input tools/performance-fixtures/generated/SMALL
```

Generation uses a committed original 31-second MP3 payload and deterministic ID3v2 metadata. It does not require ffmpeg. Acceptance validation uses `ffprobe` to decode a representative file in addition to internal structure and hash checks. Outputs are byte-deterministic; timestamps are fixed where the filesystem permits, but timestamps are not part of the byte-level fingerprint. Generated output is ignored by Git.

Each output contains `fixture-manifest.json`. Its dataset fingerprint covers the canonical logical manifest, including every relative path, metadata value, membership assignment, artwork reference, and content SHA-256. It never includes an absolute machine path.

## Emulator provisioning

Provisioning is allowed only on an explicitly selected adb serial matching `emulator-<digits>`. The helper enumerates devices first and refuses physical serials. It uses only `/sdcard/Music/LibrePlayerBenchmark/<PROFILE>`; cleanup refuses broader paths.

```powershell
python tools/performance-fixtures/provision_android.py --adb "$env:ANDROID_HOME/platform-tools/adb.exe" --serial emulator-5554 --dataset tools/performance-fixtures/generated/SMALL
```

Use path-specific ordinary MediaScanner broadcasts, then verify catalog entries through MediaStore:

```powershell
python tools/performance-fixtures/provision_android.py --adb "$env:ANDROID_HOME/platform-tools/adb.exe" --serial emulator-5554 --dataset tools/performance-fixtures/generated/SMALL --scan
python tools/performance-fixtures/provision_android.py --adb "$env:ANDROID_HOME/platform-tools/adb.exe" --serial emulator-5554 --dataset tools/performance-fixtures/generated/SMALL --verify
```

API 28 does not permit the shell user to send the protected volume-wide `MEDIA_MOUNTED` broadcast, so `--scan` deliberately addresses only manifest-listed fixture files. Large profiles can therefore take materially longer to index on that API. The directory layout is also suitable for a later manually granted SAF tree; Q1.1b intentionally does not automate SAF grants.

Cleanup is emulator-only and path-constrained. It deletes only matching MediaStore rows and the corresponding dedicated profile directory:

```powershell
python tools/performance-fixtures/provision_android.py --adb "$env:ANDROID_HOME/platform-tools/adb.exe" --serial emulator-5554 --dataset tools/performance-fixtures/generated/SMALL --cleanup
```

Provisioning and indexing times are operational diagnostics, not LibrePlayer performance measurements. Never connect or target the everyday Samsung for this workflow.

## Immutable v1.0.4 reference

The reference is commit `d2c212640ea3955591799a21d5bd8e382a628a57` at immutable tag `v1.0.4`. The benchmark foundation commit is `304af67ad1efcbe0b4cdb9019cdaaf70c38c4a68`. Q1.1c identifies its two-file startup overlay by the content identity `q1.1c-sha256:462af179b5cccd5f4a847711d86c69b8d09731fffe5586283f51a6a3f337aaea`; the approved production hook file SHA-256 is `d3367152a56b16ed13c49dad6fddd2c5c128f9a23d48057900ce5649a8b33a93`.

Prepare a detached worktree outside the normal repository:

```powershell
python tools/performance-reference/reference_tool.py prepare --repo . --worktree C:/temporary/LibrePlayer-v1.0.4-reference
```

The helper verifies the tag and the Q1.1a seven-file benchmark-infrastructure allowlist, then applies exactly two content-hashed Q1.1c files (the cached-start benchmark and approved `LibrePlayerApp.kt` fully-drawn hook) plus the content-hashed Q1.1d `LibraryUiBenchmark.kt`. It rejects every other `app/src/main/` delta and proves the detached worktree remains at v1.0.4. Q1.1d adds no production selector or semantics overlay. Build the resulting reference with:

```powershell
& C:/temporary/LibrePlayer-v1.0.4-reference/gradlew.bat --gradle-user-home C:/path/to/local-gradle-cache :app:assembleBenchmark :benchmark:assembleBenchmark --console=plain
```

Remove it only through the guarded helper:

```powershell
python tools/performance-reference/reference_tool.py cleanup --repo . --worktree C:/temporary/LibrePlayer-v1.0.4-reference --gradle-user-home C:/path/to/local-gradle-cache
```

The public tag and v1.0.4 history are never modified.

Songs and Albums retain the initially accepted Q1.1d overlay identity `q1.1d-sha256:af5602916b3493c0ab09ce3dabaa777a9a643a167810138e02b820638fa71a1d`. The Search/Resume acceptance-closeout identity is `q1.1d-sha256:b922993b8fe04c3e6a285533e04c26ae1b5607cc92e4b947d007b3c988f319ac`. Its only changed overlay file is `benchmark/src/main/java/com/libreplayer/benchmark/LibraryUiBenchmark.kt`, SHA-256 `1a0e4d4cfff586986857ac0e6ab4919fce9ca2631118b5f499bfd0854c6096ee`. The closeout orchestration script SHA-256 is `414a5786858c18cbd51cc2e8f4b13c80089fb2c15e0960418e660242724e25d0`.

## Cached startup semantics

`StartupTimingMetric` supplies time to initial display. Fully drawn means the initial Songs destination has rendered cached Songs content and is meaningfully usable. The top-level Compose `ReportDrawnWhen` predicate becomes true as soon as cached songs are available, even if an ordinary library refresh is still active. It also resolves safely for a legitimate empty, permission-required, or error UI after initial loading; it never waits for asynchronous library maintenance merely to improve the metric.

The authoritative test is `CachedLibraryStartupBenchmark.cachedSongsColdStartup`. It uses `StartupMode.COLD`, `CompilationMode.Full()`, `StartupTimingMetric`, a trace-derived peak `MemoryUsageMetric` trend, and 10 measured iterations. The benchmark asserts that the Songs destination and a known MEDIUM fixture title are visible. Any post-draw wait for the existing “Updating library” banner is outside the startup timing events.

Each reference or development run is a clean app session: remove only the two known emulator packages when present (which also prevents stale disposable-debug-key conflicts), install the selected benchmark target and test APKs, clear only `com.libreplayer` on the selected emulator, grant API 28 audio-read permission, launch once, wait for `mediaRows=2000` plus visible cached Songs content and a settled refresh, force-stop, then run the 10 cold starts without clearing the catalog between iterations.

## Repeat the startup authority

Use only a running `LibrePlayer_API_28` serial matching `emulator-<digits>`. Enumerate devices before selecting the serial, then validate it:

```powershell
& "$env:ANDROID_HOME/platform-tools/adb.exe" devices -l
python tools/performance-reference/startup_authority.py device --adb "$env:ANDROID_HOME/platform-tools/adb.exe" --serial emulator-5554
```

Generate, accept-validate, provision, scan, and verify MEDIUM through the fixture tooling documented above. Build the development target and test APKs, and use `reference_tool.py prepare` plus the documented reference Gradle build for the reference APKs.

Run one side with the guarded command below. `RESULT_DIR` must be new or empty and should live under ignored `performance-results/`. Substitute the selected reference or development APKs and variant. The AndroidX emulator suppression only acknowledges that this is a controlled emulator comparison; it does not turn the values into device-performance claims.

```powershell
python tools/performance-reference/startup_authority.py run `
  --adb "$env:ANDROID_HOME/platform-tools/adb.exe" `
  --serial emulator-5554 `
  --dataset tools/performance-fixtures/generated/MEDIUM `
  --target-apk TARGET_APK `
  --test-apk TEST_APK `
  --result-dir RESULT_DIR `
  --variant reference `
  --session 1 `
  --reference-commit d2c212640ea3955591799a21d5bd8e382a628a57 `
  --development-commit DEVELOPMENT_COMMIT `
  --harness-revision q1.1c-sha256:462af179b5cccd5f4a847711d86c69b8d09731fffe5586283f51a6a3f337aaea `
  --measurement-hook-sha256 d3367152a56b16ed13c49dad6fddd2c5c128f9a23d48057900ce5649a8b33a93 `
  --build-tools BUILD_TOOLS_VERSION
```

Use balanced sequential ordering: session 1 reference then development, session 2 development then reference, session 3 reference then development. Never run the shared-application-ID builds simultaneously. Preserve all raw values and outliers. Compare session medians; approximately 10% between-session variation is only an emulator measurement-quality diagnostic, never a regression threshold.

After all six sides complete, write the ignored aggregate comparison with:

```powershell
python tools/performance-reference/startup_authority.py compare --results-root performance-results/RUN_ID
```

The curated immutable-reference distribution is the small source-controlled `performance-baselines/startup-v1.0.4.json` record. Raw JSON and traces remain outside Git.

## Run metadata

Startup comparisons record one schema-v2 JSON document per side conforming to `tools/performance-reference/metadata.schema.json`: reference and development commits, content-addressed harness and measurement-hook identities, fixture profile/fingerprint, AVD identity and fixed device configuration, API/ABI, AGP/Benchmark/build-tools identity, cold/full mode, iteration count, precondition evidence, UTC timestamp, relative raw-result path, summary path, and trace paths. Generated JSON and traces remain under ignored `performance-results/<timestamp>/`.

Any timing produced before Q1.1c must be labeled `INFRASTRUCTURE VALIDATION ONLY — NOT A PERFORMANCE BASELINE`.

## API 36 library-UI environment

Q1.1d uses one dedicated `LibrePlayer_Benchmark_API_36` AVD: Android 16/API 36, `system-images;android-36;google_apis;x86_64`, Pixel 3 profile, 1080×2160 at 440 dpi, approximately 2 GiB RAM, portrait, and emulator 36.4.10.0. The accepted channel used host OpenGL acceleration, fixed `en-US`, font scale 1.0, and all three Android animation scales set to zero. Launch without snapshots or boot animation:

```powershell
& "$env:ANDROID_HOME/emulator/emulator.exe" -avd LibrePlayer_Benchmark_API_36 -port 5556 -no-snapshot -no-boot-anim -gpu auto -netdelay none -netspeed full
```

Only one emulator may run during authoritative sessions. Enumerate devices before every operational sequence, pass an explicit serial matching `^emulator-\d+$`, and never use implicit adb selection or a physical serial:

```powershell
& "$env:ANDROID_HOME/platform-tools/adb.exe" devices -l
python tools/performance-reference/library_ui_authority.py device --adb "$env:ANDROID_HOME/platform-tools/adb.exe" --serial emulator-5556
```

The runner rejects the wrong AVD, API, ABI, resolution, density, disconnected serial, and every non-emulator serial. It records system/build identity, RAM, locale, rotation/animation settings, host CPU/platform, and exposed thermal state. API 36 emulator values are controlled regression evidence only, not physical-phone timing claims. API 28 remains the compatibility and cached-start reference environment.

## Library-UI journey semantics

All four journeys use `CompilationMode.Full()` and `FrameTimingMetric`. Songs and Albums retain their accepted five-iteration distributions. Search uses 15 iterations, and Background→foreground uses 20 iterations plus `StartupTimingMetric` with `StartupMode.HOT`. Every setup starts only after the accepted MEDIUM fixture is indexed and the ordinary refresh has settled.

- **Songs scrolling:** relaunch at the top of Songs, verify `Duplicate Display Metadata` plus its unique supporting path, then measure six normalized swipes from 78% to 31% screen height with 100 injection steps. Passing requires the first path to leave the viewport while Songs content remains visible.
- **Albums scrolling:** relaunch to make LibrePlayer's in-memory artwork cache cold, navigate to Albums outside measurement, and verify `Album 00006`. Measure the same six swipes. Passing requires the first album to leave the viewport and later fixture albums to remain visible. OS filesystem/page caches are deliberately unspecified; this is only a cold application artwork-cache channel.
- **Search:** relaunch, open Search through its accessibility description, verify the empty search UI, focus the standard EditText, then measure progressive states `D`, `Du`, `Dup`, `Dupl`, `Duplicate`. `Duplicate Display Metadata` must be visible after every state. No production instrumentation is added. The 15-iteration closeout still captures only about 8–16 target frames per iteration, and no percentile is stable across both reference and development channels. Search is therefore **CORRECTNESS + TRACE AUTHORITY**; every numeric frame value is diagnostic only. UIAutomator's polling/wait boundary is not sufficiently precise for a defensible query-to-result timer.
- **Background → foreground:** relaunch to usable cached Songs, then measure a normal Home transition and `startActivityAndWait()` return while keeping the process and Activity resident. AndroidX `StartupMode.HOT` preserves that exact semantic, and `StartupTimingMetric.timeToInitialDisplayMs` measures normal launch intent to first displayed destination frame. Passing still requires Songs, the known title, and its path to be present with no blank scanning state. Hot TTID is **LIMITED NUMERIC AUTHORITY**. FrameTimingMetric observes zero or one target frame per iteration and remains diagnostic only.

Visible text and the existing search content description were sufficient selectors, so no production semantics/test IDs were introduced. Coordinates are used only for gestures after semantic destination/content validation.

## Repeat the library-UI authority

Generate, accept-validate, provision, scan, and verify MEDIUM through the Q1.1b public-storage/MediaStore tooling above. Provisioning and initial indexing are outside measurement. Build development and guarded reference benchmark APK pairs, then run each side sequentially:

```powershell
python tools/performance-reference/library_ui_authority.py run `
  --adb "$env:ANDROID_HOME/platform-tools/adb.exe" `
  --serial emulator-5556 `
  --dataset tools/performance-fixtures/generated/MEDIUM `
  --target-apk TARGET_APK `
  --test-apk TEST_APK `
  --result-dir performance-results/RUN_ID/session-1/reference `
  --variant reference `
  --session 1 `
  --reference-commit d2c212640ea3955591799a21d5bd8e382a628a57 `
  --development-commit DEVELOPMENT_COMMIT `
  --harness-revision q1.1d-sha256:b922993b8fe04c3e6a285533e04c26ae1b5607cc92e4b947d007b3c988f319ac `
  --emulator-version 36.4.10.0 `
  --journeys search,resume
```

Use balanced ordering: session 1 reference→development, session 2 development→reference, session 3 reference→development. The shared package ID means the two builds are installed sequentially, never concurrently. Each side clears the target, grants `READ_MEDIA_AUDIO`, populates and verifies exactly 2,000 MediaStore fixture tracks, waits for refresh settlement, and then executes only the selected journeys. Do not rerun accepted Songs/Albums during Search/Resume closeout.

Compare all six successful sides with:

```powershell
python tools/performance-reference/library_ui_authority.py compare --results-root performance-results/RUN_ID --journeys search,resume
```

The summary preserves raw AndroidX aggregate percentiles, per-iteration samples, per-iteration P50/P90/P95/P99, extrema, frame counts, and the median of iteration percentiles. The comparison retains three session medians per percentile and emits a warning above roughly 10% between-session range. That warning diagnoses emulator noise; it is not a regression threshold. Negative `frameOverrunMs` is valid headroom, so percentage deltas around or across zero are especially unsuitable as pass/fail criteria.

Keep `instrumentation.txt`, AndroidX JSON, all Perfetto traces, per-side metadata/summary, and `comparison.json` under ignored `performance-results/<RUN_ID>/`. An invalid run must be preserved and labeled rather than overwritten. The initial accepted Q1.1d run retained one failed Search-selector attempt before an unchanged retry succeeded. During closeout, one successful development side exposed an empty resume frame sample during post-processing; the parser was corrected to preserve empty frame iterations and recovered the completed measurement without rerunning it.

The accepted closeout produced 35 traces per side: 15 Search and 20 hot-return iterations. Search passed 450/450 query-state validations across both channels but has no numeric regression metric. Hot return passed 120/120 cached-content validations; reference TTID session medians were 65.8234, 61.07105, and 60.82235 ms (8.19% range), while development medians were 66.089, 60.5561, and 65.8088 ms (8.41% range). The median-of-session-medians values are 61.07105 ms reference and 65.8088 ms development. The approximately 10% diagnostic is not a regression threshold.

The concise immutable-reference distribution and authority classifications are in `performance-baselines/library-ui-v1.0.4.json`. Raw closeout results and 210 Perfetto traces remain ignored under `performance-results/q1.1d-closeout-20260824/`.

## Synchronization authority

Q1.1e measures LibrePlayer's existing real `LibraryRepository.rescanLibrary()` and `LibraryRepository.rebuildLibrary()` paths; it does not add or optimize production synchronization behavior. A provider compiled only in the benchmark variant invokes those suspend calls. `SystemClock.elapsedRealtimeNanos()` starts immediately before dispatch to the repository operation and ends immediately after that call returns. Post-operation Room catalog loading, path-set hashing, mutation assertions, fixture mutation/restoration, MediaStore convergence, installation, and trace transfer are outside the duration.

Macrobenchmark uses `CompilationMode.Full()` and `MemoryUsageMetric(Mode.Max)` to preserve one Perfetto trace per operation. The memory values are diagnostic only. AndroidX custom trace-section values are not synchronization authority because repeated harness-development runs showed intermittent trace-section scalar extraction and package attribution even when the operation, trace, exact probe duration, and catalog assertion all succeeded. The final content identity is `q1.1e-sha256:b2edbb194d2392eb70020115a5f6831a299bfc8e03994e7608391d52c4706d24`; the benchmark-only probe SHA-256 is `e7b54d20ebb8abbad7b8a151c63cd73f01a3d6c24092bf8f630e4993b307565e`.

Every mutation iteration independently restores canonical MEDIUM. The host then applies exactly one deterministic filesystem mutation, sends the path-specific media scan, waits until MediaStore's complete fixture path set and required title match, and only then invokes LibrePlayer. MediaStore indexing latency is recorded separately and never added to the synchronization duration. Every operation subsequently validates expected count, unique count, zero duplicate identities, the SHA-256 of the complete sorted fixture-relative-path set, and exact selected identity presence/title semantics.

The accepted balanced matrix used `LibrePlayer_Benchmark_API_36` and the same reference/development order as Q1.1d. It produced 168 correct operations and 168 traces: 60 unchanged refreshes, 30 Add, 30 Delete, 30 Modify, and 18 full rebuilds across both channels. Median-of-three-session-median results are:

| Journey | v1.0.4 reference | Development | Development delta | Reference / development session spread | Classification |
| --- | ---: | ---: | ---: | ---: | --- |
| Unchanged refresh | 109.844 ms | 107.826 ms | -1.84% | 11.00% / 15.97% | LIMITED NUMERIC AUTHORITY |
| Add one | 203.242 ms | 200.079 ms | -1.56% | 24.64% / 4.93% | LIMITED NUMERIC AUTHORITY |
| Delete one | 196.240 ms | 195.054 ms | -0.60% | 29.57% / 16.47% | LIMITED NUMERIC AUTHORITY |
| Modify one | 197.036 ms | 200.427 ms | +1.72% | 13.63% / 12.73% | LIMITED NUMERIC AUTHORITY |
| Full rebuild | 193.261 ms | 209.016 ms | +8.15% | 6.38% / 8.09% | NUMERIC REGRESSION AUTHORITY |

Approximately 10% session divergence is only a measurement-quality warning, never a product regression threshold. Unchanged/Add/Delete/Modify remain useful limited comparisons but are not promoted to full numeric authority because at least one channel crossed that warning. Rebuild session medians remained below the warning in both channels; its three samples per side/session support raw values and median/range, not tail percentiles. The Android-visible Modify replacement was deterministic in all 30 operations and therefore was not platform-deferred.

The separately observed MediaStore indexing median-of-session-medians was about 1.150 s Add, 1.142 s Delete, and 1.184 s Modify for reference, and 1.147 s, 1.139 s, and 1.188 s respectively for development. These are operational indexing diagnostics, not LibrePlayer performance values.

Run one side only after explicitly enumerating exactly one serial matching `^emulator-\d+$` and validating the API 36 authority AVD:

```powershell
python tools/performance-reference/synchronization_authority.py run `
  --adb "$env:ANDROID_HOME/platform-tools/adb.exe" `
  --serial emulator-5556 `
  --dataset tools/performance-fixtures/generated/MEDIUM `
  --target-apk TARGET_APK `
  --test-apk TEST_APK `
  --result-dir performance-results/RUN_ID/session-1/reference `
  --variant reference `
  --session 1 `
  --reference-commit d2c212640ea3955591799a21d5bd8e382a628a57 `
  --development-commit DEVELOPMENT_COMMIT `
  --harness-revision q1.1e-sha256:b2edbb194d2392eb70020115a5f6831a299bfc8e03994e7608391d52c4706d24 `
  --instrumentation-revision e7b54d20ebb8abbad7b8a151c63cd73f01a3d6c24092bf8f630e4993b307565e
```

After all six sequential sides, generate the ignored comparison with `python tools/performance-reference/synchronization_authority.py compare --results-root performance-results/RUN_ID`. The concise reference distribution, raw reference probe durations, exact mutation identities, result classifications, and indexing separation are in `performance-baselines/synchronization-v1.0.4.json`. The accepted raw results and traces remain ignored under `performance-results/q1.1e-accepted-20260825/`.

Five harness-development failure modes remain preserved in earlier ignored Q1.1e result roots and are excluded from authority: adb metadata argument splitting with spaces; sparse AndroidX custom-section extraction; a package-attribution race after full compilation; intermittent extraction even with `targetPackageOnly=false`; and the resulting final separation of exact probe timing from trace preservation. None was a LibrePlayer synchronization failure, and no timing from a pre-`b2edbb` harness appears in the curated distribution.

## Playback-under-load authority

Q1.1f establishes observable Media3 and MediaSession playback continuity while LibrePlayer executes its existing real library work. It adds no production playback or synchronization behavior. The benchmark-only provider starts the canonical track through the normal `PlaybackConnection` path, observes the `PlaybackService` MediaSession through an independent `MediaController`, and invokes the unchanged `LibraryRepository.rescanLibrary()` or `rebuildLibrary()` path. All controller interactions run on the required application thread.

The deterministic playing track is `audio/artist-00010/album-00010/disc-01/track-00010.mp3` (`Track 00010`, SHA-256 `e36b35ecf92aad64312fd4f3507a1fd2d72ac36f0190c50887c7b30c6d0dbb85`), a 31.176-second MP3 distinct from every synchronization mutation target. Each operation begins after real playback is READY, playing, unsuppressed, connected, and stabilized. Over an approximately two-second observation interval, the same non-empty MediaItem must remain active, position must advance by at least 1,000 ms, and player errors, MediaItem transitions, position discontinuities, and session disconnects must all remain zero.

Synchronization timing retains Q1.1e's accepted `SystemClock.elapsedRealtimeNanos()` boundary around only the complete repository call. Playback setup, fixture mutation/restoration, MediaStore indexing, catalog validation, the remainder of the observation window, and artifact transfer remain outside it. `CompilationMode.Full()` and `MemoryUsageMetric(Mode.Max)` preserve an independent Perfetto trace and diagnostic memory sample for every operation.

The accepted content identities are `q1.1f-sha256:72a584f18fb60f047ebb659ce7c96f88888a4d04db2e1fb21602cb4828ffccca` for the identical reference/development overlay and `5011f642278c535195039743993e7f8ba42be64688bbf621aa586c104ad547d9` for the benchmark-only playback provider. The immutable reference remains v1.0.4 commit `d2c212640ea3955591799a21d5bd8e382a628a57`; its only `app/src/main` overlay is the previously accepted Q1.1c fully-drawn hook.

The balanced API 36 matrix ran reference→development, development→reference, then reference→development. Each side/session contained 10 unchanged refreshes, 5 isolated Add refreshes, and 5 full rebuilds. All 120 primary operations passed exact playback and catalog assertions and produced 120 traces, with zero player errors, unexpected transitions, position discontinuities, session losses, position-progress failures, or catalog failures.

| Journey | v1.0.4 reference | Development | Development delta | Reference / development session spread | Classification |
| --- | ---: | ---: | ---: | ---: | --- |
| Playback + unchanged refresh | 48.617 ms | 48.943 ms | +0.67% | 22.26% / 17.78% | PLAYBACK CONTINUITY AUTHORITY + LIMITED NUMERIC LOAD AUTHORITY |
| Playback + Add one | 50.263 ms | 51.100 ms | +1.67% | 29.35% / 13.52% | PLAYBACK CONTINUITY AUTHORITY + LIMITED NUMERIC LOAD AUTHORITY |
| Playback + full rebuild | 75.103 ms | 75.800 ms | +0.93% | 22.04% / 13.56% | PLAYBACK CONTINUITY AUTHORITY + LIMITED NUMERIC LOAD AUTHORITY |

Values are medians of three session medians. The approximately 10% spread diagnostic limits numeric precision; it is not a product threshold and does not weaken the exact continuity result. Session 3 was completed after an overnight task interruption and both paired channels reflected the slower resumed host environment.

The first pre-authority reference setup is preserved separately and excluded: it issued a controller command from the provider Binder/IO thread, and Media3 correctly rejected it. After moving controller interactions to the application thread, both identical APK overlays were rebuilt and all accepted results were collected under `performance-results/q1.1f-authority-20260825-r2/`. Session 2 reference completed after the conversation cutoff and was recovered intact rather than rerun.

LARGE stress is deferred because no generated/provisioned LARGE fixture was present and creating/indexing a new 10,000-track dataset would substantially expand this phase. Exact audio underrun counts are also unavailable in the release-like benchmark variant because the existing diagnostic is debug-only. **AUDIO UNDERRUN / ACOUSTIC DROPOUT AUTHORITY NOT ESTABLISHED BY Q1.1f EMULATOR CHANNEL.** Q1.1f proves emulator-observable Media3/session continuity, not acoustic, DAC, Bluetooth, or physical-device AudioTrack continuity.

Run a side only after explicitly enumerating exactly one serial matching `^emulator-\d+$` and validating `LibrePlayer_Benchmark_API_36`:

```powershell
python tools/performance-reference/playback_under_load_authority.py run `
  --adb "$env:ANDROID_HOME/platform-tools/adb.exe" `
  --serial emulator-5554 `
  --dataset tools/performance-fixtures/generated/MEDIUM `
  --target-apk TARGET_APK `
  --test-apk TEST_APK `
  --result-dir performance-results/RUN_ID/session-1/reference `
  --variant reference --session 1 --side 1 `
  --reference-commit d2c212640ea3955591799a21d5bd8e382a628a57 `
  --development-commit DEVELOPMENT_COMMIT `
  --harness-revision q1.1f-sha256:72a584f18fb60f047ebb659ce7c96f88888a4d04db2e1fb21602cb4828ffccca `
  --instrumentation-revision 5011f642278c535195039743993e7f8ba42be64688bbf621aa586c104ad547d9
```

After all six sequential sides, run `python tools/performance-reference/playback_under_load_authority.py compare --results-root performance-results/RUN_ID`. The concise distributions and exact limitations are in `performance-baselines/playback-under-load-v1.0.4.json`; raw results and traces remain ignored.
