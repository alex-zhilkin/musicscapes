param(
  [string]$Device = '192.168.1.95:5555',
  [ValidateRange(5,30)][int]$SampleSeconds = 20,
  [ValidateRange(1,10)][int]$Samples = 3
)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$adb=Join-Path $workspace 'work/android-tools/platform-tools/adb.exe'
$package='local.musicscapes.tv'
function Read-Device([string]$Command){
  $result=& $adb -s $Device shell $Command
  if($LASTEXITCODE -ne 0){throw "Device read failed: $Command"}
  return ($result -join "`n").Trim()
}
function Assert-Visible {
  $activity=Read-Device 'dumpsys activity activities'
  if(-not ($activity -split "`n" | Where-Object {$_ -match 'mResumedActivity|topResumedActivity' -and $_ -match [regex]::Escape($package)})){
    $dream=Read-Device 'dumpsys dreams'
    if($dream -notmatch 'mCurrentDream[^\r\n]*local\.musicscapes\.tv'){
      throw 'MusicScapes is not visibly active. Open it or its screensaver first; this script never launches apps or changes playback.'
    }
  }
}
Assert-Visible
$appProcessId=Read-Device "pidof $package"
if($appProcessId -notmatch '^\d+$'){throw 'Expected one MusicScapes process.'}
$tickRate=[double](Read-Device 'getconf CLK_TCK')
if($tickRate -le 0){throw 'CPU clock tick rate unavailable.'}
$out=Join-Path $workspace ('outputs/performance-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $out | Out-Null
function Snapshot {
  $raw=Read-Device "cat /proc/$appProcessId/stat"
  $fields=$raw.Substring($raw.LastIndexOf(')')+1).Trim() -split '\s+'
  if($fields.Count -lt 22){throw 'Process CPU counters unavailable on this Android build.'}
  return @{ticks=([double]$fields[11]+[double]$fields[12]);at=[Diagnostics.Stopwatch]::GetTimestamp()}
}
Read-Device 'dumpsys thermalservice' | Set-Content -Encoding utf8 (Join-Path $out 'thermal-before.txt')
$measurements=@()
for($sample=1;$sample -le $Samples;$sample++){
  Assert-Visible
  $first=Snapshot
  Start-Sleep -Seconds $SampleSeconds
  if((Read-Device "pidof $package") -ne $appProcessId){throw 'App process changed; discard interrupted sample.'}
  Assert-Visible
  $last=Snapshot
  $elapsed=($last.at-$first.at)/[Diagnostics.Stopwatch]::Frequency
  $cpu=100*($last.ticks-$first.ticks)/$tickRate/$elapsed
  $memory=Read-Device "dumpsys meminfo $appProcessId"
  $memory | Set-Content -Encoding utf8 (Join-Path $out "memory-$sample.txt")
  $pss=if($memory -match 'TOTAL PSS:\s+(\d+)'){[int]$Matches[1]}else{$null}
  $value=[ordered]@{sample=$sample;seconds=[math]::Round($elapsed,2);appCpuPercentOfOneCore=[math]::Round($cpu,2);appPssKiB=$pss}
  $measurements+=$value
  Write-Output ($value|ConvertTo-Json -Compress)
}
Read-Device 'dumpsys thermalservice' | Set-Content -Encoding utf8 (Join-Path $out 'thermal-after.txt')
Read-Device 'cat /proc/meminfo' | Set-Content -Encoding utf8 (Join-Path $out 'system-memory.txt')
Read-Device "dumpsys package $package" | Select-String 'versionCode=|versionName=' | Set-Content -Encoding utf8 (Join-Path $out 'version.txt')
[ordered]@{device=$Device;model=(Read-Device 'getprop ro.product.model');measurements=$measurements;limits='App process only: excludes separate WebView renderer, SurfaceFlinger, GPU and Spotify. Thermal data may be unsupported. Short samples do not establish sustained thermal safety.'} | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $out 'summary.json')
Write-Output "Local measurements: $out"
