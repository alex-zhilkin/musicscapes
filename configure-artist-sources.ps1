param([Parameter(Mandatory=$true)][string]$KeysFile,[string]$Device='192.168.1.95:5555')
$ErrorActionPreference='Stop'
$adb=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../work/android-tools/platform-tools/adb.exe'))
$keys=(Resolve-Path -LiteralPath $KeysFile).Path
$config=Get-Content -LiteralPath $keys -Raw | ConvertFrom-Json
foreach($name in @('fanartKey','flickrKey')){if($null -ne $config.$name -and $config.$name -ne '' -and $config.$name -notmatch '^[a-fA-F0-9]{32}$'){throw 'A provider key has an invalid format.'}}
$remote='/sdcard/Android/data/local.musicscapes.tv/files/setup/artist-source-keys.json'
try{
 & $adb -s $Device shell mkdir -p /sdcard/Android/data/local.musicscapes.tv/files/setup
 if($LASTEXITCODE -ne 0){throw 'TV setup storage unavailable.'}
 & $adb -s $Device push $keys $remote
 if($LASTEXITCODE -ne 0){throw 'Could not transfer private source configuration.'}
 $result=& $adb -s $Device shell am broadcast -a local.musicscapes.tv.ARTIST_SOURCES -n local.musicscapes.tv/.ArtistSourcesReceiver --ez import true
 if($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'result=0'){throw 'Source setup request failed.'}
 $result
}finally{& $adb -s $Device shell rm -f $remote}
