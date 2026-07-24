# LibrePlayer

LibrePlayer is a freeware, open-source, offline-first Android music player for local audio files.

It is built to stay simple:

- free to use
- open source
- local-first
- no ads
- no analytics
- no user accounts
- no cloud sync by default
- no media uploaded off-device

## Features

- Local library scan from `MediaStore`
- Optional folder import through the Storage Access Framework
- Songs, albums, artists, playlists, favorites, and recently played
- Global search across songs, albums, and artists
- Queue playback with shuffle and repeat
- Background playback with `MediaSessionService`
- Lock-screen, notification, Bluetooth, and headset controls
- Persistent queue and playback position restore
- Local playlists stored in Room
- Settings for theme, default sort, metadata fallback, and rescanning

## Screenshots

Screenshot placeholders:

- Library tabs
- Now Playing
- Playlist detail
- Settings / privacy

## Architecture Overview

The app uses a practical single-module architecture:

- `app / navigation`
  - Activity entry point, app container, navigation graph
- `data / database / repository`
  - Room entities, DAOs, repositories, and domain models
- `library / scanner / metadata`
  - MediaStore scan, SAF folder traversal, metadata extraction
- `media / playback / service`
  - Media3 controller connection, playback persistence, background service
- `ui / screens / components / theme`
  - Compose screens, reusable UI, Material 3 theme
- `settings`
  - DataStore-backed app settings
- `util`
  - Formatting, permissions, and search helpers

## Privacy Statement

LibrePlayer is designed to respect user privacy:

- No ads
- No analytics
- No telemetry
- No user account
- No cloud sync by default
- No media or metadata uploaded off-device
- No `MANAGE_EXTERNAL_STORAGE`
- No `INTERNET` permission

## Build And Run

1. Open the project folder in Android Studio.
2. Let Android Studio sync the Gradle project.
3. Confirm the Android SDK for API 36 is installed if Studio prompts for it.
4. Select an emulator or a physical Android device.
5. Run the `app` configuration.
6. Grant local audio access when the app asks for it.

## Development Notes

- Kotlin
- Jetpack Compose with Material 3
- Media3 / ExoPlayer
- Room
- DataStore
- Coroutines + Flow

## Roadmap

### Version 1.1

- Better queue editing from the now-playing queue screen
- Folder view for library browsing
- More metadata cleanup and album-art caching polish
- Lightweight instrumented UI tests
- Optional per-tab sort preferences

## Contributing

Issues and pull requests are welcome.

Guidelines:

- keep the app local-only
- keep permissions minimal
- avoid analytics, ads, accounts, and remote dependencies
- prefer readable code over abstraction-heavy rewrites
- add tests for pure logic where practical

## License

MIT. See [LICENSE](LICENSE).
