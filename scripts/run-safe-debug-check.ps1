param(
    [switch]$InstallToDevice,
    [string]$PackageName = "com.lyco256.llm",
    [string]$ApkPath = "app\build\outputs\apk\debug\app-debug.apk",
    [string]$JavaHome = "C:\Program Files\Android\Android Studio\jbr"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "SafeScriptCommon.ps1")

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$timeouts = @{
    Preflight = 300
    Build = 1800
    UnitTest = 1800
    Lint = 1800
    Install = 600
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

function Invoke-LoggedAdb {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    Write-SafeLog ""
    Write-SafeLog "> $Adb $(Join-SafeCommandArguments -Arguments $Arguments)"
    $output = @(& $Adb @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    foreach ($line in $output) {
        Write-SafeLog ($line | Out-String).TrimEnd()
    }
    if ($exitCode -ne 0) {
        $script:SafeLastErrorSummary = Get-SafeErrorSummaryFromText -Lines @($output | ForEach-Object { $_.ToString() })
        throw "adb failed with exit code $exitCode"
    }
    return $output
}

function Get-SingleDevice {
    param([string]$Adb)
    $lines = Invoke-LoggedAdb -Adb $Adb -Arguments @("devices", "-l")
    $devices = @($lines | Where-Object { $_ -match "\sdevice\s" })
    if ($devices.Count -ne 1) {
        throw "Expected exactly one connected adb device, found $($devices.Count). Output: $($lines -join ' | ')"
    }
    return $devices[0]
}

function Get-PackageSnapshot {
    param(
        [string]$Adb,
        [string]$Package
    )
    $dump = Invoke-LoggedAdb -Adb $Adb -Arguments @("shell", "dumpsys", "package", $Package)
    if (-not ($dump | Select-String -Pattern "pkg=Package\{.* $([regex]::Escape($Package))\}|versionName=")) {
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

Start-SafeScript -Name "run-safe-debug-check" -RepoRoot $repoRoot
Set-Location $repoRoot

try {
    Invoke-SafePhase -Name "Preflight" -Action {
        Write-SafeLog "Root: $repoRoot"
        Invoke-SafeNativeCommand -FilePath "git" -Arguments @("status", "--short", "--branch") -TimeoutSeconds $timeouts.Preflight -WorkingDirectory $repoRoot
        Invoke-SafeNativeCommand -FilePath "git" -Arguments @("diff", "--check") -TimeoutSeconds $timeouts.Preflight -WorkingDirectory $repoRoot
        if (Test-Path -LiteralPath $JavaHome) {
            $env:JAVA_HOME = $JavaHome
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
            Write-SafeLog "JAVA_HOME=$env:JAVA_HOME"
        } else {
            Write-SafeLog "JAVA_HOME candidate not found: $JavaHome"
            Write-SafeLog "Using existing JAVA_HOME/PATH."
        }
    }

    Invoke-SafePhase -Name "Build" -Action {
        Invoke-SafeNativeCommand -FilePath ".\gradlew.bat" -Arguments @(":app:assembleDebug", "--console=plain", "--no-daemon") -TimeoutSeconds $timeouts.Build -WorkingDirectory $repoRoot
    }

    Invoke-SafePhase -Name "UnitTest" -Action {
        Invoke-SafeNativeCommand -FilePath ".\gradlew.bat" -Arguments @(":app:testDebugUnitTest", "--console=plain", "--no-daemon") -TimeoutSeconds $timeouts.UnitTest -WorkingDirectory $repoRoot
    }

    Invoke-SafePhase -Name "Lint" -Action {
        Invoke-SafeNativeCommand -FilePath ".\gradlew.bat" -Arguments @(":app:lintDebug", "--console=plain", "--no-daemon") -TimeoutSeconds $timeouts.Lint -WorkingDirectory $repoRoot
    }

    if ($InstallToDevice) {
        Invoke-SafePhase -Name "Install" -Action {
            if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
                throw "APK not found: $ApkPath"
            }
            $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
            $adb = Get-AdbPath
            Write-SafeLog "adb: $adb"
            $deviceLine = Get-SingleDevice -Adb $adb
            Write-SafeLog "Device: $deviceLine"

            $before = Get-PackageSnapshot -Adb $adb -Package $PackageName
            Write-SafeLog "Before: uid=$($before.Uid) appId=$($before.AppId) firstInstallTime=$($before.FirstInstallTime) lastUpdateTime=$($before.LastUpdateTime) version=$($before.VersionName)"

            Invoke-SafeNativeCommand -FilePath $adb -Arguments @("install", "-r", $resolvedApk) -TimeoutSeconds $timeouts.Install -WorkingDirectory $repoRoot

            $after = Get-PackageSnapshot -Adb $adb -Package $PackageName
            Write-SafeLog "After: uid=$($after.Uid) appId=$($after.AppId) firstInstallTime=$($after.FirstInstallTime) lastUpdateTime=$($after.LastUpdateTime) version=$($after.VersionName)"

            if ($before.Uid -ne $after.Uid -or $before.AppId -ne $after.AppId -or $before.FirstInstallTime -ne $after.FirstInstallTime) {
                throw "Package identity changed after reinstall. Check device data before continuing."
            }
        }
    }

    Complete-SafeScript
    Write-Host "Success"
    exit 0
} catch {
    Stop-SafeScriptWithFailure -Exception $_.Exception
}
