param(
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    [string]$Serial = 'emulator-5554',
    [string]$Output = 'performance-results/q3.6-playlist/authority-1'
)
$ErrorActionPreference = 'Stop'
function Adb([string[]]$Arguments) {
    $result = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $Arguments : $result" }
    return ($result -join "`n")
}
function Shell([string]$Command) { Adb @('shell', $Command) }
if ($Serial -notmatch '^emulator-\d+$') { throw 'Physical devices are refused' }
if ((Shell 'getprop ro.kernel.qemu').Trim() -ne '1') { throw 'Not an emulator' }
if ((Shell 'getprop ro.build.version.sdk').Trim() -ne '36') { throw 'Not API 36' }
if ((Shell 'getprop ro.boot.qemu.avd_name').Trim() -ne 'LibrePlayer_Benchmark_API_36') { throw 'Unexpected AVD' }
if (Test-Path -LiteralPath $Output) { throw 'Output exists; preserve evidence and choose a fresh explicitly authorized run directory' }
$null = New-Item -ItemType Directory -Path $Output
$Output = (Resolve-Path -LiteralPath $Output).Path
$root = '/storage/emulated/0/Music/LibrePlayerBenchmark/Q36_PLAYLIST_AUTHORITY'
$corpus = (Resolve-Path 'performance-results/q3.5-artwork/fixtures').Path
$manifest = Get-Content -LiteralPath (Join-Path $corpus 'fixture-manifest.json') -Raw | ConvertFrom-Json
$copies = [ordered]@{
    '01-normal-jpeg.mp3' = 'One.mp3'
    '04-partial-green.mp3' = '音楽 café [1].mp3'
    '07-greatest-a.mp3' = 'left/Same.mp3'
    '08-greatest-b.mp3' = 'right/Same.mp3'
}
$provisioned = $false
$installed = $false
Start-Transcript -Path (Join-Path $Output 'transcript.txt') | Out-Null
try {
    "serial=$Serial qemu=1 api=36 avd=LibrePlayer_Benchmark_API_36"
    $null = Shell "test ! -e $root"
    $rows = Shell 'content query --uri content://media/external/audio/media --projection _id:_data'
    if ($rows.Contains('Q36_PLAYLIST_AUTHORITY')) { throw 'Unexpected pre-existing Q36 rows' }
    $null = Shell "mkdir -p $root/left $root/right"
    $provisioned = $true
    foreach ($source in $copies.Keys) {
        $local = Join-Path $corpus $source
        $record = $manifest.tracks | Where-Object relative_path -eq $source
        $hash = (Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash
        if ($hash -ne $record.sha256) { throw "Fixture hash mismatch: $source" }
        "fixture=$source sha256=$hash copy=$($copies[$source])"
        Adb @('push', $local, "$root/$($copies[$source])")
        $null = Shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://$root/$($copies[$source])'"
    }
    foreach ($apk in @('app/build/outputs/apk/benchmark/app-benchmark.apk', 'benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk')) {
        Get-FileHash -LiteralPath $apk -Algorithm SHA256 | Format-List
        Adb @('install', '-r', (Resolve-Path -LiteralPath $apk).Path)
    }
    $installed = $true
    $null = Shell 'pm grant com.libreplayer android.permission.READ_MEDIA_AUDIO'
    Start-Sleep -Seconds 3
    $result = Shell 'am instrument -w -r -e class com.libreplayer.benchmark.PlaylistAuthorityBenchmark com.libreplayer.benchmark/androidx.test.runner.AndroidJUnitRunner'
    $result | Set-Content -LiteralPath (Join-Path $Output 'instrumentation.txt')
    $result
    if ($result -notmatch 'OK \(1 test\)') { throw 'Q3.6 integrated authority failed; inspect preserved instrumentation/logcat' }
} finally {
    if ($installed) {
        Adb @('logcat', '-d', '-v', 'threadtime') | Set-Content -LiteralPath (Join-Path $Output 'logcat.txt')
        Shell 'content call --uri content://com.libreplayer.playlist-authority --method cleanup'
    }
    if ($provisioned) {
        foreach ($name in $copies.Values) {
            $where = "_data = '$root/$name'"
            Shell "content delete --uri content://media/external/audio/media --where `"$where`""
            $null = Shell "rm -f '$root/$name'"
        }
        $null = Shell "rmdir $root/left $root/right $root"
        $null = Shell "test ! -e $root"
        $rows = Shell 'content query --uri content://media/external/audio/media --projection _id:_data'
        if ($rows.Contains('Q36_PLAYLIST_AUTHORITY')) { throw 'Q36 MediaStore cleanup failed' }
        if ($installed) { Shell 'content call --uri content://com.libreplayer.playlist-authority --method empty' }
        'cleanup: four Q36 temporary copies removed; root absent; zero matching MediaStore rows'
    }
    Stop-Transcript | Out-Null
}
