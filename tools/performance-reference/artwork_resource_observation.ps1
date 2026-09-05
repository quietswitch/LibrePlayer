param(
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    [string]$Serial = 'emulator-5554',
    [string]$Dataset = 'performance-results/q3.5-artwork/fixtures',
    [string]$Output = 'performance-results/q3.5-artwork/resource-closeout'
)

# One bounded observation using the existing APK and four unchanged corpus files.
# A pre-existing output directory is refused so failures cannot be silently rerun.
$ErrorActionPreference = 'Stop'
function Invoke-Adb([string[]]$Command) {
    $result = & $Adb -s $Serial @Command 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $Command : $result" }
    return ($result -join "`n")
}
function Invoke-Shell([string]$Command) { Invoke-Adb @('shell', $Command) }
function Probe([string]$Method, [string]$Extra = '') {
    Invoke-Shell "content call --uri content://com.libreplayer.synchronization-probe --method $Method $Extra"
}
function Window-Xml {
    $null = Invoke-Shell 'uiautomator dump /sdcard/q35-resource-window.xml'
    return [xml](Invoke-Shell 'cat /sdcard/q35-resource-window.xml')
}
function Tap-Node([string]$XPath) {
    $node = (Window-Xml).SelectSingleNode($XPath)
    if ($null -eq $node) { throw "UI node missing: $XPath" }
    $bounds = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (!$bounds.Success) { throw 'UI bounds missing' }
    $x = [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
    $y = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
    $null = Invoke-Shell "input tap $x $y"
    Start-Sleep -Milliseconds 700
}
function Sample([string]$Label) {
    $currentPid = (Invoke-Shell 'pidof com.libreplayer').Trim()
    if ($currentPid -ne $script:appPid) { throw "Process changed at $Label" }
    $cache = Probe 'q3.5-catalog'
    $meminfo = Invoke-Shell 'dumpsys meminfo com.libreplayer'
    $status = Invoke-Shell "cat /proc/$currentPid/status"
    $sample = [ordered]@{
        label = $Label
        timestamp_utc = [DateTime]::UtcNow.ToString('o')
        elapsed_seconds = [math]::Round($script:timer.Elapsed.TotalSeconds, 2)
        pid = $currentPid
        pss_kib = [long]([regex]::Match($meminfo, 'TOTAL PSS:\s*(\d+)').Groups[1].Value)
        rss_kib = [long]([regex]::Match($status, 'VmRSS:\s*(\d+)').Groups[1].Value)
        threads = [int]([regex]::Match($status, 'Threads:\s*(\d+)').Groups[1].Value)
        cache_bytes = [int]([regex]::Match($cache, 'q35CacheBytes=(\d+)').Groups[1].Value)
        cache_max_bytes = [int]([regex]::Match($cache, 'q35CacheMaxBytes=(\d+)').Groups[1].Value)
    }
    if ($sample.pss_kib -le 0 -or $sample.cache_max_bytes -le 0 -or $sample.cache_bytes -gt $sample.cache_max_bytes) {
        throw "Invalid resource sample: $($sample | ConvertTo-Json -Compress)"
    }
    $script:samples.Add($sample)
    $sample | ConvertTo-Json -Compress | Write-Output
    $meminfo | Set-Content (Join-Path $Output "$Label-meminfo.txt")
    $status | Set-Content (Join-Path $Output "$Label-status.txt")
    $cache | Set-Content (Join-Path $Output "$Label-catalog.txt")
}

if ($Serial -notmatch '^emulator-\d+$') { throw 'Physical devices are refused' }
if ((Invoke-Shell 'getprop ro.kernel.qemu').Trim() -ne '1') { throw 'Not an emulator' }
if ((Invoke-Shell 'getprop ro.build.version.sdk').Trim() -ne '36') { throw 'Not API 36' }
if ((Invoke-Shell 'getprop ro.boot.qemu.avd_name').Trim() -ne 'LibrePlayer_Benchmark_API_36') { throw 'Unexpected AVD' }
if (Test-Path -LiteralPath $Output) { throw 'Observation output already exists; do not rerun automatically' }
$Dataset = (Resolve-Path -LiteralPath $Dataset).Path
$null = New-Item -ItemType Directory -Path $Output
$Output = (Resolve-Path -LiteralPath $Output).Path
$manifest = Get-Content (Join-Path $Dataset 'fixture-manifest.json') -Raw | ConvertFrom-Json
$files = @('01-normal-jpeg.mp3', '04-partial-green.mp3', '11-corrupt-art.mp3', '13-large-webp.mp3')
$remoteRoot = '/sdcard/Music/LibrePlayerBenchmark/Q35_ARTWORK_AUTHORITY'
$mediaRoot = '/storage/emulated/0/Music/LibrePlayerBenchmark/Q35_ARTWORK_AUTHORITY'
$samples = [System.Collections.Generic.List[object]]::new()
$timer = [Diagnostics.Stopwatch]::StartNew()
$provisioned = $false
$failure = $null
$appPid = $null
Start-Transcript -Path (Join-Path $Output 'transcript.txt') | Out-Null
try {
    $localApk = (Resolve-Path 'app/build/outputs/apk/benchmark/app-benchmark.apk').Path
    $installedPath = (Invoke-Shell 'pm path com.libreplayer').Trim() -replace '^package:', ''
    $installedHash = (Invoke-Shell "sha256sum $installedPath").Split(' ')[0]
    $localHash = (Get-FileHash $localApk -Algorithm SHA256).Hash
    if ($installedHash -ne $localHash) { throw 'Installed APK differs from the preserved final APK' }
    "apk_sha256=$localHash serial=$Serial avd=LibrePlayer_Benchmark_API_36 api=36"
    $rows = Invoke-Shell 'content query --uri content://media/external/audio/media --projection _id:_data'
    if ($rows.Contains('Q35_ARTWORK_AUTHORITY')) { throw 'Unexpected pre-existing Q3.5 rows' }
    $null = Invoke-Shell "test ! -e $remoteRoot"
    $null = Invoke-Shell "mkdir $remoteRoot"
    $provisioned = $true
    foreach ($name in $files) {
        $record = $manifest.tracks | Where-Object relative_path -eq $name
        $path = Join-Path $Dataset $name
        if ((Get-FileHash $path -Algorithm SHA256).Hash -ne $record.sha256) { throw "Fixture hash mismatch: $name" }
        Invoke-Adb @('push', $path, "$remoteRoot/$name")
        $null = Invoke-Shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$remoteRoot/$name"
    }
    Start-Sleep -Seconds 3
    $sync = Probe 'q3.5-sync'
    if ($sync -notmatch 'q35MediaStoreCount=4\b') { throw "Expected four resource fixtures: $sync" }
    $null = Invoke-Shell 'am force-stop com.libreplayer'
    $null = Invoke-Shell 'am start -W -n com.libreplayer/com.libreplayer.app.MainActivity'
    Start-Sleep -Seconds 5
    $appPid = (Invoke-Shell 'pidof com.libreplayer').Trim()
    Sample 'baseline'
    for ($cycle = 1; $cycle -le 4; $cycle++) {
        Tap-Node '//node[@content-desc="Search library"]'
        Tap-Node '//node[@class="android.widget.EditText"]'
        # Emit only literal, single-character shell arguments. Verify each Compose
        # update before appending the next character at the end; never retry input.
        # Back retains the query, so later cycles verify/reuse exact Q35.
        $field = (Window-Xml).SelectSingleNode('//node[@class="android.widget.EditText"]')
        if ($field.text -eq '') {
            $expectedQuery = ''
            foreach ($character in @('Q', '3', '5')) {
                $null = Invoke-Shell 'input keyevent KEYCODE_MOVE_END'
                $null = Invoke-Shell "input text $character"
                Start-Sleep -Seconds 1
                $expectedQuery += $character
                $field = (Window-Xml).SelectSingleNode('//node[@class="android.widget.EditText"]')
                if ($field.text -cne $expectedQuery) {
                    throw "Resource query prefix was not exactly ${expectedQuery}: $($field.text)"
                }
            }
        }
        $field = (Window-Xml).SelectSingleNode('//node[@class="android.widget.EditText"]')
        if ($field.text -cne 'Q35') { throw "Resource query was not exactly Q35: $($field.text)" }
        "cycle=$cycle verified_query=$($field.text)"
        $null = Invoke-Shell 'input keyevent 4'
        Start-Sleep -Seconds 1
        $window = Window-Xml
        if (!$window.SelectSingleNode('//node[@text="Normal JPEG"]')) { throw 'Expected artwork search row absent' }
        foreach ($name in $files) {
            $variant = if ($name -eq '13-large-webp.mp3') { 'FULL' } else { 'LIST' }
            $load = Probe 'q3.5-load' "--arg $name --extra q35LoadVariant:s:$variant"
            $load | Set-Content (Join-Path $Output "cycle-$cycle-$name-load.txt")
            $expected = if ($name -eq '11-corrupt-art.mp3') { 'failed' } else { 'loaded' }
            if ($load -notmatch "q35LoadStatus=$expected\b") { throw "Unexpected decode: $load" }
        }
        for ($scroll = 0; $scroll -lt 3; $scroll++) {
            $null = Invoke-Shell 'input swipe 540 1600 540 500 350'
            $null = Invoke-Shell 'input swipe 540 500 540 1600 350'
        }
        Tap-Node '//node[@text="Back"]'
        Start-Sleep -Seconds 5
        Sample "cycle-$cycle-settled"
    }
    Start-Sleep -Seconds 8
    Sample 'quiet-settle-1'
    Start-Sleep -Seconds 8
    Sample 'quiet-settle-2'
    $logs = Invoke-Adb @('logcat', '-d', '-v', 'threadtime', '--pid', $appPid)
    $logs | Set-Content (Join-Path $Output 'app-logcat.txt')
    if ($logs -match 'FATAL EXCEPTION|OutOfMemoryError|ANR in com.libreplayer|was already used') { throw 'Fatal/resource/key failure in app log' }
    'OBSERVATION_COMPLETED: inspect the time series; no universal memory threshold applied.'
} catch {
    $failure = $_.ToString()
    Write-Output "OBSERVATION_FAILED: $failure"
    if ($appPid) {
        Invoke-Adb @('logcat', '-d', '-v', 'threadtime', '--pid', $appPid) |
            Set-Content (Join-Path $Output 'failure-app-logcat.txt')
    }
    Invoke-Shell 'cat /sdcard/q35-resource-window.xml' |
        Set-Content (Join-Path $Output 'failure-window.xml')
} finally {
    $samples.ToArray() | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $Output 'samples.json')
    if ($provisioned) {
        foreach ($name in $files) {
            $where = "_data = '$mediaRoot/$name'"
            Invoke-Shell "content delete --uri content://media/external/audio/media --where `"$where`""
            $null = Invoke-Shell "rm -f $remoteRoot/$name"
        }
        $null = Invoke-Shell "rmdir $remoteRoot"
        $null = Probe 'q3.5-sync'
        $null = Invoke-Shell "test ! -e $remoteRoot"
        $rows = Invoke-Shell 'content query --uri content://media/external/audio/media --projection _id:_data'
        if ($rows.Contains('Q35_ARTWORK_AUTHORITY')) { throw 'Resource fixture MediaStore cleanup failed' }
        'cleanup: resource fixture root absent; zero Q3.5 MediaStore rows'
    }
    Stop-Transcript | Out-Null
}
if ($null -ne $failure) { throw $failure }
