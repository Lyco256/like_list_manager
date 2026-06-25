param(
    [switch]$InstallToDevice,
    [string]$PackageName = "com.lyco256.llm",
    [string]$ApkPath = "app\build\outputs\apk\debug\app-debug.apk",
    [string]$JavaHome = "C:\Program Files\Android\Android Studio\jbr"
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message"
}

function Invoke-Checked {
    param(
        [string]$Label,
        [scriptblock]$Command
    )
    Write-Step $Label
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

function Get-AdbPath {
    $candidate = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path -LiteralPath $candidate) {
        return $candidate
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw "adb.exe was not found. Install Android SDK platform-tools or add adb to PATH."
}

function Get-SingleDevice {
    param([string]$Adb)
    $lines = & $Adb devices -l
    if ($LASTEXITCODE -ne 0) {
        throw "adb devices failed with exit code $LASTEXITCODE"
    }
    $devices = @($lines | Where-Object { $_ -match "\sdevice\s" })
    if ($devices.Count -ne 1) {
        throw "Expected exactly one connected adb device, found $($devices.Count). Output:`n$($lines -join "`n")"
    }
    return $devices[0]
}

function Get-PackageSnapshot {
    param(
        [string]$Adb,
        [string]$Package
    )
    $dump = & $Adb shell dumpsys package $Package
    if ($LASTEXITCODE -ne 0 -or -not ($dump | Select-String -Pattern "pkg=Package\{.* $([regex]::Escape($Package))\}|versionName=")) {
        throw "Package $Package is not currently installed. Refusing to use adb install -r as a fresh install."
    }

    function Match-First {
        param([string]$Pattern)
        $match = $dump | Select-String -Pattern $Pattern | Select-Object -First 1
        if (-not $match) {
            return ""
        }
        return $match.Matches.Groups[1].Value.Trim()
    }

    $uid = Match-First "^\s*uid=(\d+)"
    $appId = Match-First "^\s*appId=(\d+)"
    $firstInstallTime = Match-First "firstInstallTime=(.+)$"
    $lastUpdateTime = Match-First "lastUpdateTime=(.+)$"
    $versionName = Match-First "versionName=(.+)$"

    if (-not $uid -or -not $appId -or -not $firstInstallTime) {
        throw "Could not read package identity fields for $Package."
    }

    [pscustomobject]@{
        Uid = $uid
        AppId = $appId
        FirstInstallTime = $firstInstallTime
        LastUpdateTime = $lastUpdateTime
        VersionName = $versionName
    }
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

Write-Step "Repository"
Write-Host "Root: $repoRoot"
git status --short --branch
if ($LASTEXITCODE -ne 0) {
    throw "git status failed with exit code $LASTEXITCODE"
}

Invoke-Checked "Whitespace check" {
    git diff --check
}

if (Test-Path -LiteralPath $JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    Write-Host "JAVA_HOME=$env:JAVA_HOME"
} else {
    Write-Host "JAVA_HOME candidate not found: $JavaHome"
    Write-Host "Using existing JAVA_HOME/PATH."
}

Invoke-Checked "Gradle assembleDebug, testDebugUnitTest, lintDebug" {
    .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain --no-daemon
}

if (-not $InstallToDevice) {
    Write-Step "Done"
    Write-Host "Local debug build, unit tests, and lint completed. Device reinstall was not requested."
    exit 0
}

$resolvedApk = Resolve-Path $ApkPath
if (-not (Test-Path -LiteralPath $resolvedApk)) {
    throw "APK not found: $ApkPath"
}

$adb = Get-AdbPath
Write-Step "Device preflight"
Write-Host "adb: $adb"
$deviceLine = Get-SingleDevice -Adb $adb
Write-Host "Device: $deviceLine"

$before = Get-PackageSnapshot -Adb $adb -Package $PackageName
Write-Host "Before: uid=$($before.Uid) appId=$($before.AppId) firstInstallTime=$($before.FirstInstallTime) lastUpdateTime=$($before.LastUpdateTime) version=$($before.VersionName)"

Invoke-Checked "Safe reinstall with adb install -r" {
    & $adb install -r $resolvedApk
}

$after = Get-PackageSnapshot -Adb $adb -Package $PackageName
Write-Host "After:  uid=$($after.Uid) appId=$($after.AppId) firstInstallTime=$($after.FirstInstallTime) lastUpdateTime=$($after.LastUpdateTime) version=$($after.VersionName)"

if ($before.Uid -ne $after.Uid -or $before.AppId -ne $after.AppId -or $before.FirstInstallTime -ne $after.FirstInstallTime) {
    throw "Package identity changed after reinstall. Check device data before continuing."
}

Write-Step "Done"
Write-Host "Build/test/lint passed, and adb install -r completed without changing package uid/appId/firstInstallTime."
