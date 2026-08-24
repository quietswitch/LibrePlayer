# Performance Development Authority

Q1.1 separates infrastructure validation from performance claims. Q1.1b defines deterministic inputs and a reproducible v1.0.4 reference; it does not define performance thresholds or startup authority.

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

The reference is commit `d2c212640ea3955591799a21d5bd8e382a628a57` at immutable tag `v1.0.4`. The benchmark harness revision is `304af67ad1efcbe0b4cdb9019cdaaf70c38c4a68`.

Prepare a detached worktree outside the normal repository:

```powershell
python tools/performance-reference/reference_tool.py prepare --repo . --worktree C:/temporary/LibrePlayer-v1.0.4-reference
```

The helper verifies the tag, applies only an exact seven-file benchmark-infrastructure allowlist, excludes the documentation-only Q1.1a change, and fails closed on any `app/src/main/` or unexpected path. Build the resulting reference with:

```powershell
& C:/temporary/LibrePlayer-v1.0.4-reference/gradlew.bat --gradle-user-home C:/path/to/local-gradle-cache :app:assembleBenchmark :benchmark:assembleBenchmark --console=plain
```

Remove it only through the guarded helper:

```powershell
python tools/performance-reference/reference_tool.py cleanup --repo . --worktree C:/temporary/LibrePlayer-v1.0.4-reference --gradle-user-home C:/path/to/local-gradle-cache
```

The public tag and v1.0.4 history are never modified.

## Run metadata

Future comparisons must record one JSON document conforming to `tools/performance-reference/metadata.schema.json`: reference and development commits, harness revision, fixture profile/fingerprint, AVD or device identity, API/ABI, AGP/Benchmark/build-tools identity, compilation mode, iteration count, UTC timestamp, raw result path, and trace paths. Paths should be relative to an ignored run directory such as `performance-results/<timestamp>/`.

Any timing produced before Q1.1c must be labeled `INFRASTRUCTURE VALIDATION ONLY — NOT A PERFORMANCE BASELINE`.
