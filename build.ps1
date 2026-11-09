param(
  [string]$ToolsRoot = (Join-Path $PSScriptRoot '../../work/android-tools'),
  [string]$BuildRoot = (Join-Path $PSScriptRoot '../../work/musicscapes-build')
)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$ToolsRoot = [IO.Path]::GetFullPath($ToolsRoot)
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
$jdkRoot = (Get-ChildItem -LiteralPath (Join-Path $ToolsRoot 'jdk') -Directory | Select-Object -First 1).FullName
$buildTools = Join-Path $ToolsRoot 'build-tools/android-15'
$androidJar = Join-Path $ToolsRoot 'platform/android-34/android.jar'
$env:JAVA_HOME = $jdkRoot
New-Item -ItemType Directory -Path $BuildRoot,(Join-Path $BuildRoot 'classes'),(Join-Path $BuildRoot 'dex') -Force | Out-Null
function Invoke-Checked([string]$Command, [string[]]$Arguments) {
  & $Command @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Build command failed: $Command (exit $LASTEXITCODE)" }
}
Invoke-Checked (Join-Path $buildTools 'aapt2.exe') @('compile','--dir',(Join-Path $projectRoot 'res'),'-o',(Join-Path $BuildRoot 'resources.zip'))
Invoke-Checked (Join-Path $buildTools 'aapt2.exe') @('link','-I',$androidJar,'--manifest',(Join-Path $projectRoot 'AndroidManifest.xml'),'--min-sdk-version','26','--target-sdk-version','34','-A',(Join-Path $projectRoot 'assets'),'-o',(Join-Path $BuildRoot 'base.apk'),(Join-Path $BuildRoot 'resources.zip'))
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$libraries=@(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'libs') -Filter '*.jar'|ForEach-Object FullName)
$classPath=(@($androidJar)+$libraries)-join [IO.Path]::PathSeparator
Invoke-Checked (Join-Path $jdkRoot 'bin/javac.exe') (@('--release','8','-encoding','UTF-8','-classpath',$classPath,'-d',(Join-Path $BuildRoot 'classes')) + $sources)
Invoke-Checked (Join-Path $jdkRoot 'bin/jar.exe') @('--create','--file',(Join-Path $BuildRoot 'classes.jar'),'-C',(Join-Path $BuildRoot 'classes'),'.')
Invoke-Checked (Join-Path $buildTools 'd8.bat') (@('--lib',$androidJar,'--min-api','26','--output',(Join-Path $BuildRoot 'dex'),(Join-Path $BuildRoot 'classes.jar'))+$libraries)
Copy-Item -LiteralPath (Join-Path $BuildRoot 'base.apk') -Destination (Join-Path $BuildRoot 'with-dex.apk') -Force
Invoke-Checked (Join-Path $jdkRoot 'bin/jar.exe') @('--update','--file',(Join-Path $BuildRoot 'with-dex.apk'),'-C',(Join-Path $BuildRoot 'dex'),'classes.dex')
Invoke-Checked (Join-Path $jdkRoot 'bin/jar.exe') @('--update','--file',(Join-Path $BuildRoot 'with-dex.apk'),'-C',$projectRoot,'lib')
# Windows aapt2 writes nested asset names with backslashes. Android's asset
# manager requires forward slashes inside the ZIP, independent of build host.
Add-Type -AssemblyName System.IO.Compression
$assetArchive = [IO.Compression.ZipFile]::Open((Join-Path $BuildRoot 'with-dex.apk'), [IO.Compression.ZipArchiveMode]::Update)
try {
  foreach ($assetEntry in @($assetArchive.Entries)) {
    if ($assetEntry.FullName.Contains('\')) {
      $fixedEntry = $assetArchive.CreateEntry($assetEntry.FullName.Replace('\','/'), [IO.Compression.CompressionLevel]::Optimal)
      $readStream = $assetEntry.Open(); $writeStream = $fixedEntry.Open()
      try { $readStream.CopyTo($writeStream) } finally { $readStream.Dispose(); $writeStream.Dispose() }
      $assetEntry.Delete()
    }
  }
} finally { $assetArchive.Dispose() }
Invoke-Checked (Join-Path $buildTools 'zipalign.exe') @('-f','4',(Join-Path $BuildRoot 'with-dex.apk'),(Join-Path $BuildRoot 'aligned.apk'))
$key = Join-Path $ToolsRoot 'musicscapes-local.keystore'
if (!(Test-Path -LiteralPath $key)) {
  Invoke-Checked (Join-Path $jdkRoot 'bin/keytool.exe') @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','musicscapes','-dname','CN=MusicScapes Personal Build','-keyalg','RSA','-keysize','2048','-validity','10000','-noprompt')
}
$apk = Join-Path (Split-Path $projectRoot) 'MusicScapes.apk'
Invoke-Checked (Join-Path $buildTools 'apksigner.bat') @('sign','--ks',$key,'--ks-pass','pass:android','--key-pass','pass:android','--out',$apk,(Join-Path $BuildRoot 'aligned.apk'))
Invoke-Checked (Join-Path $buildTools 'apksigner.bat') @('verify','--verbose',$apk)
Write-Output "Built $apk"
