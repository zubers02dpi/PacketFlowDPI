#requires -version 5.1
<#
    PacketFlowDPI - open build (no Gradle).

    Requirements:
      * JDK 17 (JAVA_HOME must point to it)
      * Android SDK with build-tools and a platform
        (ANDROID_SDK_ROOT or ANDROID_HOME must point to it)

    Usage:
      .\build.ps1
      .\build.ps1 -Install      build and install on a device over adb
#>
param([switch]$Install)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

function Fail([string]$message) {
    Write-Host "ERROR: $message" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- toolchain

$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk -or -not (Test-Path $sdk)) {
    Fail "set ANDROID_SDK_ROOT (or ANDROID_HOME) to your Android SDK directory"
}
$sdk = (Resolve-Path $sdk).Path

$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk "bin\javac.exe"))) {
    Fail "set JAVA_HOME to a JDK 17 installation (javac.exe not found)"
}

$buildTools = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^\d+(\.\d+)*$' } |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if (-not $buildTools) { Fail "no build-tools found in $sdk\build-tools" }

$platform = Get-ChildItem (Join-Path $sdk "platforms") -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^android-(\d+)$' } |
    Sort-Object { [int]($_.Name -replace '^android-', '') } -Descending |
    Select-Object -First 1
if (-not $platform) { Fail "no Android platform found in $sdk\platforms" }

$androidJar = Join-Path $platform.FullName "android.jar"
$aapt2      = Join-Path $buildTools.FullName "aapt2.exe"
$zipalign   = Join-Path $buildTools.FullName "zipalign.exe"
$apksigner  = Join-Path $buildTools.FullName "apksigner.bat"
$d8         = Join-Path $buildTools.FullName "d8.bat"
$javac      = Join-Path $jdk "bin\javac.exe"
$keytool    = Join-Path $jdk "bin\keytool.exe"

foreach ($tool in @($androidJar, $aapt2, $zipalign, $apksigner, $d8, $javac, $keytool)) {
    if (-not (Test-Path $tool)) { Fail "missing build tool: $tool" }
}

$env:JAVA_HOME = $jdk
$env:PATH = (Join-Path $jdk "bin") + ";" + $env:PATH

Write-Host "SDK:        $sdk"
Write-Host "build-tools: $($buildTools.Name)"
Write-Host "platform:   $($platform.Name)"
Write-Host "JDK:        $jdk"

# ---------------------------------------------------------------- layout

$appDir   = Join-Path $root "app"
$manifest = Join-Path $appDir "AndroidManifest.xml"
$resDir   = Join-Path $appDir "res"
$srcDir   = Join-Path $appDir "java"
$outDir   = Join-Path $root "build"
$genDir   = Join-Path $outDir "gen"
$clsDir   = Join-Path $outDir "classes"
$dexDir   = Join-Path $outDir "dex"
$unsigned = Join-Path $outDir "app-unsigned.apk"
$aligned  = Join-Path $outDir "app-aligned.apk"
$final    = Join-Path $root "PacketFlowDPI.apk"
$keystore = Join-Path $root "debug.keystore"

if (Test-Path $outDir) {
    Get-ChildItem $outDir -Force | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
} else {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
}
New-Item -ItemType Directory -Force -Path $genDir, $clsDir, $dexDir | Out-Null

# ---------------------------------------------------------------- build

Write-Host "[1/6] aapt2 compile + link" -ForegroundColor Cyan
$resZip = Join-Path $outDir "res.zip"
& $aapt2 compile --dir $resDir -o $resZip
if ($LASTEXITCODE -ne 0) { Fail "aapt2 compile failed" }

& $aapt2 link -o $unsigned -I $androidJar --manifest $manifest $resZip `
    --min-sdk-version 30 --target-sdk-version 33 `
    --version-code 1 --version-name "1.0" --auto-add-overlay
if ($LASTEXITCODE -ne 0) { Fail "aapt2 link failed" }

Write-Host "[2/6] javac" -ForegroundColor Cyan
$sources = @(Get-ChildItem -Recurse -Path $srcDir -Filter *.java | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { Fail "no Java sources under $srcDir" }
& $javac -source 11 -target 11 -encoding UTF-8 -Xlint:-options -classpath $androidJar -d $clsDir @sources
if ($LASTEXITCODE -ne 0) { Fail "javac failed" }

Write-Host "[3/6] d8" -ForegroundColor Cyan
$classFiles = @(Get-ChildItem -Recurse -Path $clsDir -Filter *.class | ForEach-Object { $_.FullName })
& $d8 --lib $androidJar --min-api 30 --output $dexDir @classFiles
if ($LASTEXITCODE -ne 0) { Fail "d8 failed" }

Write-Host "[4/6] package" -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, "Update")
try {
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, (Join-Path $dexDir "classes.dex"), "classes.dex",
        [System.IO.Compression.CompressionLevel]::Optimal)
} finally {
    $zip.Dispose()
}

Write-Host "[5/6] zipalign" -ForegroundColor Cyan
& $zipalign -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { Fail "zipalign failed" }

Write-Host "[6/6] sign" -ForegroundColor Cyan
if (-not (Test-Path $keystore)) {
    Write-Host "generating debug.keystore" -ForegroundColor Yellow
    & $keytool -genkeypair -keystore $keystore -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
    if ($LASTEXITCODE -ne 0) { Fail "keytool failed" }
}

& $apksigner sign --ks $keystore --ks-key-alias androiddebugkey `
    --ks-pass pass:android --key-pass pass:android `
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    --out $final $aligned
if ($LASTEXITCODE -ne 0) { Fail "apksigner failed" }
& $apksigner verify --print-certs $final

$size = [math]::Round((Get-Item $final).Length / 1KB, 1)
Write-Host "Built: $final  ($size KB)" -ForegroundColor Green

# ---------------------------------------------------------------- install

if ($Install) {
    $adb = $null
    $fromSdk = Join-Path $sdk "platform-tools\adb.exe"
    if (Test-Path $fromSdk) {
        $adb = $fromSdk
    } else {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($cmd) { $adb = $cmd.Source }
    }
    if (-not $adb) { Fail "adb not found (install platform-tools or add adb to PATH)" }

    Write-Host "Installing on device ..." -ForegroundColor Cyan
    & $adb install -r -d $final
    if ($LASTEXITCODE -ne 0) { Fail "adb install failed" }
}
