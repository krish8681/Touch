# Windows build (PowerShell): ecj -> d8 -> aapt2 -> zipalign -> apksigner. Needs only Java 11+ on PATH.
# Usage (from this folder):  powershell -ExecutionPolicy Bypass -File build.ps1
# The keystore password is read from KS_PASS, or asked for. Same steps as build.sh.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$onWindows = ($null -eq $IsWindows) -or $IsWindows   # Windows PowerShell 5.1 has no $IsWindows

function Run($exe, [string[]]$a) {
    & $exe @a
    if ($LASTEXITCODE -ne 0) { throw "$exe failed (exit $LASTEXITCODE)" }
}
function WriteUtf8($path, $text) { [IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding $false)) }

if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw "Java not found. Install a JDK or JRE 11+ (e.g. https://adoptium.net) and reopen the window." }
if (-not (Test-Path keystore/release.jks)) { throw "keystore/release.jks not found. Copy the keystore the installed app was signed with there (a different key cannot update the app)." }

# ---- toolchain: downloaded once into .tools (same package build.sh uses)
$T = Join-Path $PSScriptRoot '.tools/package/tools'
if (-not (Test-Path (Join-Path $T 'd8.jar'))) {
    Write-Host "Downloading build tools (one time)..."
    New-Item -ItemType Directory -Force .tools | Out-Null
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -UseBasicParsing 'https://registry.npmjs.org/@drxiaozhi/minapk/-/minapk-0.4.0.tgz' -OutFile .tools/minapk.tgz
    Run tar @('-xzf', '.tools/minapk.tgz', '-C', '.tools')
}
$AAPT2 = if ($env:AAPT2) { $env:AAPT2 } else { Join-Path $T 'aapt2.exe' }
$ZIPALIGN = if ($env:ZIPALIGN) { $env:ZIPALIGN } else { Join-Path $T 'zipalign.exe' }
$JAR = Join-Path $T 'android.jar'
$STUB = Join-Path $PSScriptRoot 'tools/lambda-stubs.jar'

if (-not $env:KS_PASS) {
    $sec = Read-Host -AsSecureString "Keystore password"
    $env:KS_PASS = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
}

# ---- version from the manifest into BuildInfo + app name
$VER = [regex]::Match((Get-Content AndroidManifest.xml -Raw), 'versionName="([^"]*)"').Groups[1].Value
$bi = 'src/com/krish/niftydirection/BuildInfo.java'
WriteUtf8 $bi ((Get-Content $bi -Raw) -replace 'VERSION = "[^"]*"', "VERSION = `"$VER`"")
WriteUtf8 res/values/strings.xml ((Get-Content res/values/strings.xml -Raw) -replace '<string name="app_name">[^<]*<', "<string name=`"app_name`">Nifty Direction $VER<")

$B = 'build'
if (Test-Path $B) { Remove-Item -Recurse -Force $B }
foreach ($d in 'cls', 'dex', 'res', 'gen') { New-Item -ItemType Directory -Force (Join-Path $B $d) | Out-Null }

Write-Host "Resources..."
Run $AAPT2 @('compile', '--dir', 'res', '-o', (Join-Path $B 'res/res.zip'))
Run $AAPT2 @('link', '-I', $JAR, '--manifest', 'AndroidManifest.xml', '--rename-manifest-package', 'com.krish.niftydirection.pure',
    '-o', (Join-Path $B 'unsigned.apk'), (Join-Path $B 'res/res.zip'), '--java', (Join-Path $B 'gen'),
    '--min-sdk-version', '26', '--target-sdk-version', '34', '--version-name', $VER)

Write-Host "Compiling..."
$java = Get-ChildItem -Recurse -Filter *.java -Path src, (Join-Path $B 'gen') | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
WriteUtf8 (Join-Path $B 'sources.txt') ($java -join "`n")
& java -jar (Join-Path $T 'ecj-3.45.0.jar') -1.8 -nowarn -encoding UTF-8 -bootclasspath $JAR -cp $STUB -d (Join-Path $B 'cls') ('@' + (Join-Path $B 'sources.txt'))
if (-not (Get-ChildItem -Recurse -Path (Join-Path $B 'cls') -Filter MainActivity.class)) { throw "compile failed" }

Write-Host "Dexing..."
$cls = Get-ChildItem -Recurse -Filter *.class -Path (Join-Path $B 'cls') | ForEach-Object { Resolve-Path -Relative $_.FullName }
Run java (@('-cp', (Join-Path $T 'd8.jar'), 'com.android.tools.r8.D8', '--release', '--min-api', '26', '--lib', $JAR, '--output', (Join-Path $B 'dex')) + $cls)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open((Join-Path $PSScriptRoot "$B/unsigned.apk"), 'Update')
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $PSScriptRoot "$B/dex/classes.dex"), 'classes.dex') | Out-Null } finally { $zip.Dispose() }

Write-Host "Aligning and signing..."
if ($ZIPALIGN.EndsWith('.py')) { Run python3 @($ZIPALIGN, (Join-Path $B 'unsigned.apk'), (Join-Path $B 'aligned.apk')) }
else { Run $ZIPALIGN @('-p', '-f', '4', (Join-Path $B 'unsigned.apk'), (Join-Path $B 'aligned.apk')) }
$ist = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow, $(if ($onWindows) { 'India Standard Time' } else { 'Asia/Kolkata' }))
$OUT = Join-Path $B ("NiftyDirection_" + $VER.Replace(' ', '_') + "_" + $ist.ToString('yyyyMMdd_HHmm') + ".apk")
Run java @('-jar', (Join-Path $T 'apksigner.jar'), 'sign', '--ks', 'keystore/release.jks', '--ks-pass', 'env:KS_PASS', '--key-pass', 'env:KS_PASS', '--out', $OUT, (Join-Path $B 'aligned.apk'))
Run java @('-jar', (Join-Path $T 'apksigner.jar'), 'verify', $OUT)
Write-Host "BUILT $OUT"
