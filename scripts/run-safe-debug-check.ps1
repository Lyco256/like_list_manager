param(
    [switch]$InstallToDevice,
    [switch]$FullRebuildTest,
    [string]$PackageName = "com.lyco256.llm",
    [string]$ApkPath = "app\build\outputs\apk\debug\app-debug.apk",
    [string]$JavaHome = "C:\Program Files\Android\Android Studio\jbr"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "SafeScriptCommon.ps1")

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$timeouts = @{
    Preflight = 0
    Build = 0
    UnitTest = 0
    Lint = 0
    Install = 0
}
$validationConfigInputs = @(
    "app\build.gradle.kts",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "local.properties",
    "gradle",
    "gradlew",
    "gradlew.bat",
    "scripts\SafeScriptCommon.ps1",
    "scripts\run-safe-debug-check.ps1",
    "scripts\run-safe-integration-check.ps1"
)
$buildInputs = $validationConfigInputs + @(
    "app\src\main",
    "app\src\androidTest",
    "app\src\integrationTest",
    "app\src\benchmark"
)
$unitMainInputs = $validationConfigInputs + @("app\src\main")
$unitInputs = $unitMainInputs + @("app\src\test")
$lintInputs = $validationConfigInputs + @("app\src\main")
$buildFingerprint = ""
$unitMainFingerprint = ""
$unitFingerprint = ""
$lintFingerprint = ""

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

function Get-SinglePhysicalDevice {
    param([string]$Adb)
    $lines = Invoke-LoggedAdb -Adb $Adb -Arguments @("devices", "-l")
    $deviceLines = @($lines | Where-Object { $_ -match "^\S+\s+device(?:\s|$)" })
    if ($deviceLines.Count -eq 0) {
        throw "Expected exactly one physical adb device, found no connected device. Output: $($lines -join ' | ')"
    }

    $devicesByIdentity = @{}
    foreach ($deviceLine in $deviceLines) {
        $endpointMatch = [regex]::Match($deviceLine, "^(.*?)\s+device(?:\s|$)")
        $endpoint = $endpointMatch.Groups[1].Value.Trim()
        $reportedSerial = ((Invoke-LoggedAdb -Adb $Adb -Arguments @("-s", $endpoint, "shell", "getprop", "ro.serialno")) -join "").Trim()
        if ([string]::IsNullOrWhiteSpace($reportedSerial) -or $reportedSerial -eq "unknown") {
            throw "Could not verify the physical device hardware serial for ADB endpoint $endpoint."
        }
        $identity = "hardware:$reportedSerial"
        if (-not $devicesByIdentity.ContainsKey($identity)) {
            $devicesByIdentity[$identity] = @()
        }
        $devicesByIdentity[$identity] += [pscustomobject]@{
            Endpoint = $endpoint
            HardwareSerial = $reportedSerial
            Line = $deviceLine
        }
    }

    if ($devicesByIdentity.Count -ne 1) {
        throw "Expected exactly one physical adb device, found $($devicesByIdentity.Count) across $($deviceLines.Count) connected entries. Output: $($lines -join ' | ')"
    }

    $identityKey = @($devicesByIdentity.Keys)[0]
    $candidates = @($devicesByIdentity[$identityKey])
    $hardwareSerial = $candidates[0].HardwareSerial
    $usbCandidate = if (-not [string]::IsNullOrWhiteSpace($hardwareSerial) -and $hardwareSerial -ne "unknown") {
        $candidates | Where-Object { $_.Endpoint -eq $hardwareSerial } | Select-Object -First 1
    }
    $selected = if ($usbCandidate) {
        $usbCandidate
    } else {
        $candidates |
            Sort-Object @{ Expression = { if ($_.Endpoint -match " \(\d+\)\._adb-tls-connect\._tcp$") { 1 } else { 0 } } }, Endpoint |
            Select-Object -First 1
    }
    foreach ($extra in @($candidates | Where-Object { $_.Endpoint -ne $selected.Endpoint })) {
        Invoke-LoggedAdb -Adb $Adb -Arguments @("disconnect", $extra.Endpoint) | Out-Null
        Write-SafeLog "Disconnected duplicate ADB endpoint $($extra.Endpoint) for hardware serial $hardwareSerial."
    }
    Write-SafeLog "Selected ADB endpoint $($selected.Endpoint) for one physical device across $($deviceLines.Count) connected entries."
    return $selected
}

function Get-PackageSnapshot {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Package
    )
    $dump = Invoke-LoggedAdb -Adb $Adb -Arguments @("-s", $Serial, "shell", "dumpsys", "package", $Package)
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
        $script:buildFingerprint = Get-SafeValidationFingerprint -RepoRoot $repoRoot -InputPaths $buildInputs -CacheVersion "safe-shared-build-v1"
        $script:unitMainFingerprint = Get-SafeValidationFingerprint -RepoRoot $repoRoot -InputPaths $unitMainInputs -CacheVersion "safe-unit-main-v1"
        $script:unitFingerprint = Get-SafeValidationFingerprint -RepoRoot $repoRoot -InputPaths $unitInputs -CacheVersion "safe-unit-validation-v3"
        $script:lintFingerprint = Get-SafeValidationFingerprint -RepoRoot $repoRoot -InputPaths $lintInputs -CacheVersion "safe-lint-validation-v2"
    }

    Invoke-SafePhase -Name "Build" -Action {
        if ($FullRebuildTest -or -not (Test-SafeValidationCache -RepoRoot $repoRoot -Key "app-build" -Fingerprint $script:buildFingerprint)) {
            $buildArguments = @()
            if ($FullRebuildTest) { $buildArguments += ":app:clean" }
            $buildArguments += @(":app:assembleDebug", ":app:verifyTestEnvironmentIsolation", ":app:assembleIntegrationTest", ":app:assembleIntegrationTestAndroidTest", "--console=plain", "--no-daemon")
            Invoke-SafeNativeCommand -FilePath ".\gradlew.bat" -Arguments $buildArguments -TimeoutSeconds $timeouts.Build -WorkingDirectory $repoRoot
            Set-SafeValidationCache -RepoRoot $repoRoot -Key "app-build" -Fingerprint $script:buildFingerprint -OutputPaths @(
                "app\build\outputs\apk\debug\app-debug.apk",
                "app\build\outputs\apk\integrationTest\app-integrationTest.apk",
                "app\build\outputs\apk\androidTest\integrationTest\app-integrationTest-androidTest.apk"
            )
        }
    }

    Invoke-SafePhase -Name "UnitTest" -Action {
        Invoke-SafeUnitTestValidation -RepoRoot $repoRoot -Fingerprint $script:unitFingerprint -MainFingerprint $script:unitMainFingerprint -TimeoutSeconds $timeouts.UnitTest -ForceFull:$FullRebuildTest
    }

    Invoke-SafePhase -Name "Lint" -Action {
        if ($FullRebuildTest -or -not (Test-SafeValidationCache -RepoRoot $repoRoot -Key "app-lint" -Fingerprint $script:lintFingerprint)) {
            Invoke-SafeNativeCommand -FilePath ".\gradlew.bat" -Arguments @(":app:lintDebug", "--console=plain", "--no-daemon") -TimeoutSeconds $timeouts.Lint -WorkingDirectory $repoRoot
            Set-SafeValidationCache -RepoRoot $repoRoot -Key "app-lint" -Fingerprint $script:lintFingerprint
        }
    }

    if ($InstallToDevice) {
        Invoke-SafePhase -Name "Install" -Action {
            if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
                throw "APK not found: $ApkPath"
            }
            $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
            $adb = Get-AdbPath
            Write-SafeLog "adb: $adb"
            $device = Get-SinglePhysicalDevice -Adb $adb
            $serial = $device.Endpoint
            Write-SafeLog "Device: $($device.Line)"

            $before = Get-PackageSnapshot -Adb $adb -Serial $serial -Package $PackageName
            Write-SafeLog "Before: uid=$($before.Uid) appId=$($before.AppId) firstInstallTime=$($before.FirstInstallTime) lastUpdateTime=$($before.LastUpdateTime) version=$($before.VersionName)"

            Invoke-SafeNativeCommand -FilePath $adb -Arguments @("-s", $serial, "install", "-r", $resolvedApk) -TimeoutSeconds $timeouts.Install -WorkingDirectory $repoRoot

            $after = Get-PackageSnapshot -Adb $adb -Serial $serial -Package $PackageName
            Write-SafeLog "After: uid=$($after.Uid) appId=$($after.AppId) firstInstallTime=$($after.FirstInstallTime) lastUpdateTime=$($after.LastUpdateTime) version=$($after.VersionName)"

            if ($before.Uid -ne $after.Uid -or $before.AppId -ne $after.AppId -or $before.FirstInstallTime -ne $after.FirstInstallTime) {
                throw "Package identity changed after reinstall. Check device data before continuing."
            }
            $postDevice = Get-SinglePhysicalDevice -Adb $adb
            if ($postDevice.HardwareSerial -ne $device.HardwareSerial) {
                throw "Physical device identity changed during reinstall."
            }
        }
    }

    Complete-SafeScript
    Write-Host "Success"
    exit 0
} catch {
    Stop-SafeScriptWithFailure -Exception $_.Exception
}
