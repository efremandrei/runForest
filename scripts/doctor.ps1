param(
    [string]$ApkPath = ".\artifacts\runForest-v0.2.0-build-2-arm64-v8a-debug.apk"
)

$ErrorActionPreference = "Stop"

function Write-Check($Name, $Value) {
    Write-Host ("[{0}] {1}" -f $Name, $Value)
}

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) { throw "java is not available on PATH." }
Write-Check "java" $java.Source

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not (Test-Path $sdk)) { throw "Android SDK was not found. Checked ANDROID_HOME, ANDROID_SDK_ROOT, and $sdk." }
Write-Check "sdk" $sdk

$buildTools = Get-ChildItem -Path (Join-Path $sdk "build-tools") -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $buildTools) { throw "No Android build-tools directory found under $sdk." }
Write-Check "build-tools" $buildTools.FullName

$aapt = Join-Path $buildTools.FullName "aapt.exe"
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"
if (-not (Test-Path $aapt)) { throw "aapt.exe not found in $($buildTools.FullName)." }
if (-not (Test-Path $apksigner)) { throw "apksigner.bat not found in $($buildTools.FullName)." }

if (-not (Test-Path $ApkPath)) { throw "APK not found at $ApkPath. Run .\gradlew.bat packageDebugApks first." }
Write-Check "apk" (Resolve-Path $ApkPath)

& $aapt dump badging $ApkPath | Select-String -Pattern "package:|application-label:|native-code"
& $apksigner verify --verbose $ApkPath
Get-FileHash -Algorithm SHA256 $ApkPath | Format-List

$adb = Join-Path $sdk "platform-tools\adb.exe"
if (Test-Path $adb) {
    Write-Check "adb" $adb
    & $adb devices
} else {
    Write-Check "adb" "not installed"
}
