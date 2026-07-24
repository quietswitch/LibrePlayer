# LibrePlayer Release Procedure

This procedure produces and verifies a signed LibrePlayer APK without placing signing material in the repository. Commands are for Windows PowerShell and must be run from the repository root.

## Prerequisites

- Java 21 available to Gradle
- Android SDK Platform 36
- A recent Android SDK Build Tools package containing `zipalign` and `apksigner`
- Android SDK Platform Tools containing `adb`
- A private Android release keystore and alias supplied through secure, non-repository storage
- A connected Android 8.0 (API 26) or newer device for installation and manual acceptance checks

Use placeholders such as `<ANDROID_SDK>`, `<BUILD_TOOLS_VERSION>`, `<SECURE_KEYSTORE_PATH>`, and `<KEY_ALIAS>` locally. Do not replace them with private values in tracked files.

## Clean Verification

```powershell
.\gradlew.bat clean --console=plain
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleRelease --console=plain
git diff --check
git status --short
```

The unsigned release APK is expected at:

```text
app\build\outputs\apk\release\app-release-unsigned.apk
```

## Align and Sign

Set temporary PowerShell variables for the local session. The keystore path and alias shown here are placeholders:

```powershell
$buildTools = Join-Path '<ANDROID_SDK>' 'build-tools\<BUILD_TOOLS_VERSION>'
$unsignedApk = '.\app\build\outputs\apk\release\app-release-unsigned.apk'
$alignedApk = '.\app\build\outputs\apk\release\app-release-aligned.apk'
$signedApk = '.\app\build\outputs\apk\release\LibrePlayer-1.0.4.apk'
$keyStore = '<SECURE_KEYSTORE_PATH>'
$keyAlias = '<KEY_ALIAS>'

& (Join-Path $buildTools 'zipalign.exe') -p -f 4 $unsignedApk $alignedApk
& (Join-Path $buildTools 'apksigner.bat') sign --ks $keyStore --ks-key-alias $keyAlias --out $signedApk $alignedApk
```

Allow `apksigner` to request passwords interactively. Do not place passwords on the command line, in PowerShell history, in Gradle properties, or in repository files.

## Verify the Signed APK

```powershell
& (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $signedApk
Get-FileHash -Algorithm SHA256 $signedApk
```

Record the SHA-256 value next to the distributed artifact through the chosen release channel.

## Install

Use the `adb` executable from the local Android SDK:

```powershell
$adb = Join-Path '<ANDROID_SDK>' 'platform-tools\adb.exe'
& $adb devices
& $adb install -r $signedApk
```

## Manual Acceptance Checklist

- Install or update succeeds on a supported Android device.
- The app launches and requests only the expected audio permission.
- MediaStore songs appear after permission is granted.
- Optional folder import uses the system picker and imports the selected folder.
- A song plays, pauses, seeks, and advances through the queue.
- Shuffle and repeat behave as selected.
- Notification, lock-screen, headset, or Bluetooth controls operate during background playback.
- Now Playing handles multiline title and artist text without overlapping controls.
- Favorites, playlists, settings, queue, and playback position survive an app restart.
- Airplane-mode playback works and no network permission appears in app details.

## Signing-Key Protection

- Keep the keystore, alias, and passwords outside the repository and outside shared build artifacts.
- Store the keystore in encrypted primary storage with at least one encrypted offline backup.
- Store passwords separately from the keystore and limit access to the release owner.
- Never commit signing properties, shell transcripts containing credentials, or signed-key backups.
- Preserve the same release key for future updates; losing it can prevent compatible update distribution.
- Verify backups periodically without copying private material into the project directory.
