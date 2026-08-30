param(
    [string]$OutputDirectory = "app/src/debug/assets/q2_3"
)

$ErrorActionPreference = "Stop"

$ffmpeg = (Get-Command ffmpeg -ErrorAction Stop).Source
$output = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\$OutputDirectory"))
[System.IO.Directory]::CreateDirectory($output) | Out-Null

$formats = @(
    @{ Extension = "wav"; Arguments = @("-c:a", "pcm_s16le") },
    @{ Extension = "flac"; Arguments = @("-c:a", "flac", "-compression_level", "8") },
    @{ Extension = "mp3"; Arguments = @("-c:a", "libmp3lame", "-b:a", "128k", "-write_xing", "1", "-id3v2_version", "0") },
    @{ Extension = "m4a"; Arguments = @("-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart") }
)

foreach ($format in $formats) {
    $partCount = if ($format.Extension -eq "mp3") { 3 } else { 2 }
    foreach ($part in 1..$partCount) {
        $destination = Join-Path $output ("transition-{0}-{1}.{0}" -f $format.Extension, $part)
        $arguments = @(
            "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
            "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
            "-ac", "1", "-ar", "48000"
        ) + $format.Arguments + @($destination)
        & $ffmpeg @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "ffmpeg failed for $destination"
        }
    }
}

Get-ChildItem -LiteralPath $output -File |
    Sort-Object Name |
    Get-FileHash -Algorithm SHA256 |
    Select-Object @{Name="File";Expression={$_.Path.Substring($output.Length + 1)}}, Hash
