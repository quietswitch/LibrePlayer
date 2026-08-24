# Performance Development Authority

Q1.1 separates infrastructure validation from performance claims. Q1.1b defines deterministic inputs and a reproducible v1.0.4 reference. Q1.1c adds controlled cold-start regression authority for a cached MEDIUM library. It does not define universal device performance or a pass/fail threshold.

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

The helper verifies the tag and the Q1.1a seven-file benchmark-infrastructure allowlist, then applies exactly two content-hashed Q1.1c files: the cached-start benchmark and the approved `LibrePlayerApp.kt` fully-drawn hook. It rejects every other `app/src/main/` delta and proves the detached worktree remains at v1.0.4. Build the resulting reference with:

```powershell
& C:/temporary/LibrePlayer-v1.0.4-reference/gradlew.bat --gradle-user-home C:/path/to/local-gradle-cache :app:assembleBenchmark :benchmark:assembleBenchmark --console=plain
```

Remove it only through the guarded helper:

```powershell
python tools/performance-reference/reference_tool.py cleanup --repo . --worktree C:/temporary/LibrePlayer-v1.0.4-reference --gradle-user-home C:/path/to/local-gradle-cache
```

The public tag and v1.0.4 history are never modified.

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
