param(
    [string]$OutputDirectory = "app/src/debug/assets/q2_4"
)

$ErrorActionPreference = "Stop"

$ffmpeg = (Get-Command ffmpeg -ErrorAction Stop).Source
$output = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\$OutputDirectory"))
[System.IO.Directory]::CreateDirectory($output) | Out-Null
$destination = Join-Path $output "audio-focus.flac"

& $ffmpeg `
    -hide_banner -loglevel error -y `
    -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=20" `
    -map_metadata -1 -fflags +bitexact -flags:a +bitexact `
    -ac 1 -ar 48000 -c:a flac -compression_level 8 `
    $destination

if ($LASTEXITCODE -ne 0) {
    throw "ffmpeg failed for $destination"
}

Get-FileHash -LiteralPath $destination -Algorithm SHA256 |
    Select-Object @{Name="File";Expression={$_.Path.Substring($output.Length + 1)}}, Hash
