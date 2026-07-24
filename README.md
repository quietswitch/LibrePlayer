# LibrePlayer

LibrePlayer is a freeware, open-source Android music player for audio files stored on the device. It is offline and local-first: playback, library data, playlists, favorites, settings, and history remain on the device.

## Features

- MediaStore library scanning with optional Storage Access Framework folder import
- Songs, albums, artists, playlists, favorites, and recently played
- Search across songs, albums, and artists
- Queue playback with shuffle and repeat
- Background playback through a Media3 `MediaSessionService`
- Lock-screen, notification, Bluetooth, and headset controls
- Persistent queue and playback-position restoration
- Local playlists stored with Room
- Audio details, artwork, theme, sorting, metadata fallback, and rescan settings

## Android Requirements

- Android 8.0 (API 26) or newer
- The project compiles and targets Android API 36
- Android SDK Platform 36 and a Java 21 runtime available to Gradle are required to build the project

## Permissions

LibrePlayer requests only the permissions needed for local playback:

- `READ_MEDIA_AUDIO`: reads audio indexed by MediaStore on Android 13 and newer.
- `READ_EXTERNAL_STORAGE`: reads local audio on Android 12L and older; the manifest limits this permission to API 32.
- `FOREGROUND_SERVICE`: keeps active playback running in a foreground service.
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: identifies that foreground service as media playback on supported Android versions.

Folder import uses Android's system document picker, which grants access only to locations selected by the user. LibrePlayer does not request `MANAGE_EXTERNAL_STORAGE` or `INTERNET`.

## Privacy

- No ads, analytics, telemetry, or user accounts
- No cloud sync
- No media or metadata uploaded off-device
- App backups are disabled
- Library data, playlists, favorites, settings, and playback state are stored locally

## Build and Verify on Windows

Prerequisites are Android Studio or the Android SDK command-line tools, Android SDK Platform 36, and a Java 21 runtime. Run these commands from the repository root in Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat assembleRelease --console=plain
```

Build artifacts are written to:

- Debug APK: `app\build\outputs\apk\debug\app-debug.apk`
- Unsigned release APK: `app\build\outputs\apk\release\app-release-unsigned.apk`

To build and install the debug variant on a connected device or emulator:

```powershell
.\gradlew.bat installDebug --console=plain
```

The release task intentionally produces an unsigned APK. A public release APK must be signed with a private release key before installation or distribution. Signing keys, passwords, aliases, and credential files must remain outside Git. See [RELEASE.md](RELEASE.md) for the release procedure.

## Architecture

LibrePlayer is a single-module Kotlin application using Jetpack Compose, Material 3, Media3/ExoPlayer, Room, DataStore, coroutines, and Flow.

## Current Limitations

- Album and artist artwork uses a deterministic representative track and may not be canonical.
- Permission behavior and settings surfaces can vary between Android device manufacturers.
- Folder import is available, but there is no dedicated folder-browsing library view.
- Streaming, remote libraries, lyrics, casting, and recommendations are outside the current scope.
- Future Room schema changes require explicit migrations; the current database configuration can recreate local app data during an incompatible schema upgrade.
- Automated coverage focuses on unit-tested logic rather than a broad instrumentation/UI suite.

## Contributing

Issues and pull requests are welcome. Keep the app local-first, permissions minimal, and changes within the offline music-player scope. Add tests for pure logic where practical.

## License

LibrePlayer is available under the MIT License. See [LICENSE](LICENSE).
