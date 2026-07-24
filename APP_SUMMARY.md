# LibrePlayer

LibrePlayer is a local-first Android music player for people who want a simple, privacy-respecting app for playing audio files already on their device. It is designed as a focused offline player, not a media ecosystem.

## Core Purpose

Play and organize local audio files on Android with a clean UI, background playback, local playlists, and zero cloud dependency.

## Primary Platform

Android

## Current Implemented Features

- Local library scan from `MediaStore`
- Optional folder import through the Storage Access Framework
- Songs, albums, artists, playlists, favorites, and recently played
- Global search across songs, albums, and artists
- Queue playback with shuffle and repeat
- Background playback with `MediaSessionService`
- Lock-screen, notification, Bluetooth, and headset controls
- Persistent queue and playback position restore
- Audio Details screen for tags and technical file properties
- Artwork in song rows, mini-player, now playing, and representative album/artist rows
- Theme, default sort, metadata fallback, and manual rescan settings

## Important Non-Goals

- Streaming services
- Accounts, sync, or cloud libraries
- Ads, analytics, telemetry, or crash-reporting SDKs
- Broad file-management features
- Social, recommendation, or discovery features

## Privacy And Data Posture

Local-first. No ads. No analytics. No accounts. No cloud sync by default. No media uploaded off-device. No `INTERNET` permission. Minimal storage permissions only.

## Current Limitations

- No folder-view library browser yet
- No advanced queue editing beyond playlist ordering
- No lyrics, casting, or streaming integrations
- Limited automated UI/instrumentation coverage
- Room schema changes still need explicit migrations before broader long-term release use
