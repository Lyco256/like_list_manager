param(
    [switch]$FullRebuildTest,
    [string]$DeviceConfig = (Join-Path $PSScriptRoot "..\test-device.local.properties")
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
    IntegrationTest = 0
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

function Prepare-ComposeTestDevice {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial
    )

    Invoke-LoggedAdb -Adb $Adb -Arguments @("-s", $Serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
    Invoke-LoggedAdb -Adb $Adb -Arguments @("-s", $Serial, "shell", "wm", "dismiss-keyguard") | Out-Null
    $powerState = Invoke-LoggedAdb -Adb $Adb -Arguments @("-s", $Serial, "shell", "dumpsys", "power")
    if (-not ($powerState | Select-String "mWakefulness=Awake")) {
        throw "The test device did not become awake before Compose tests."
    }
}

function Wait-AllowedDevice {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [int]$TimeoutSeconds = 90
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $deviceLines = @()
    do {
        Write-SafeLog ""
        Write-SafeLog "> $Adb devices -l"
        $deviceLines = @(& $Adb devices -l 2>&1)
        foreach ($line in $deviceLines) {
            Write-SafeLog ($line | Out-String).TrimEnd()
        }
        $selected = @($deviceLines | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device\s" })
        if ($selected.Count -eq 1) {
            return
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Allowed test device $Serial did not reconnect exactly once within $TimeoutSeconds seconds."
}

function Resolve-AllowedDevice {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$HardwareSerial,
        [int]$TimeoutSeconds = 90
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $devices = @(Invoke-LoggedAdb -Adb $Adb -Arguments @("devices", "-l"))
        $matchingEndpoints = @()
        foreach ($candidate in @($devices | Where-Object { $_ -match "^\S+\s+device\s" })) {
            $endpoint = [regex]::Match($candidate, "^(.*?)\s+device(?:\s|$)").Groups[1].Value.Trim()
            $reportedSerial = ((Invoke-LoggedAdb -Adb $Adb -Arguments @("-s", $endpoint, "shell", "getprop", "ro.serialno")) -join "").Trim()
            if ($reportedSerial -eq $HardwareSerial) {
                $matchingEndpoints += $endpoint
            }
        }
        $connectedMatches = @($matchingEndpoints | Select-Object -Unique)
        if ($connectedMatches.Count -gt 0) {
            $selected = if ($connectedMatches -contains $HardwareSerial) {
                $HardwareSerial
            } else {
                $connectedMatches |
                    Sort-Object @{ Expression = { if ($_ -match " \(\d+\)\._adb-tls-connect\._tcp$") { 1 } else { 0 } } }, @{ Expression = { $_ } } |
                    Select-Object -First 1
            }
            foreach ($extra in @($connectedMatches | Where-Object { $_ -ne $selected })) {
                Invoke-LoggedAdb -Adb $Adb -Arguments @("disconnect", $extra) | Out-Null
                Write-SafeLog "Disconnected duplicate ADB endpoint $extra for hardware serial $HardwareSerial."
            }
            return $selected
        }

        $matchingServices = @()
        foreach ($line in @(Invoke-LoggedAdb -Adb $Adb -Arguments @("mdns", "services"))) {
            if ($line -match "^\s*(.+?)\s+_adb-tls-connect\._tcp\s+([^\s]+)\s*$") {
                $serviceName = $Matches[1].Trim()
                if ($serviceName -match [regex]::Escape($HardwareSerial)) {
                    $matchingServices += $serviceName
                }
            }
        }
        $selectedService = $matchingServices |
            Sort-Object @{ Expression = { if ($_ -match " \(\d+\)$") { 1 } else { 0 } } }, @{ Expression = { $_ } } |
            Select-Object -First 1
        if ($selectedService) {
            Invoke-LoggedAdb -Adb $Adb -Arguments @("connect", "${selectedService}._adb-tls-connect._tcp") | Out-Null
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Allowed device with hardware serial $HardwareSerial did not resolve to a unique USB or wireless endpoint within $TimeoutSeconds seconds."
}

function Get-PackageMetadata {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName
    )
    $path = ((Invoke-LoggedAdb -Adb $Adb -Arguments @("-s", $Serial, "shell", "pm", "path", $PackageName)) -join "`n").Trim()
    if (-not $path.StartsWith("package:")) {
        throw "Required package is not installed: $PackageName"
    }
    $details = (Invoke-LoggedAdb -Adb $Adb -Arguments @("-s", $Serial, "shell", "dumpsys", "package", $PackageName) |
        Select-String "firstInstallTime=|lastUpdateTime=|versionCode=|codePath=" |
        ForEach-Object { $_.Line.Trim() }) -join "`n"
    $uid = ((Invoke-LoggedAdb -Adb $Adb -Arguments @("-s", $Serial, "shell", "cmd", "package", "list", "packages", "-U", $PackageName)) -join "`n").Trim()
    if ($uid -notmatch "package:$([regex]::Escape($PackageName))\s+uid:\d+") {
        throw "Could not verify Android UID for $PackageName`: $uid"
    }
    return "$path`n$uid`n$details"
}

Start-SafeScript -Name "run-safe-integration-check" -RepoRoot $repoRoot
Set-Location $repoRoot

$configPath = ""
$hardwareSerial = ""
$serial = ""
$productionPackage = ""
$testPackage = ""
$testRunnerComponent = ""
$adb = ""
$targetApk = ""
$androidTestApk = ""
$productionBefore = ""
$buildFingerprint = ""
$unitMainFingerprint = ""
$unitFingerprint = ""
$lintFingerprint = ""
$runError = $null

try {
    Invoke-SafePhase -Name "Preflight" -Action {
        $script:configPath = [System.IO.Path]::GetFullPath($DeviceConfig)
        if (-not (Test-Path -LiteralPath $script:configPath -PathType Leaf)) {
            throw "Missing local device allow-list: $script:configPath. Copy test-device.local.properties.example and register the co-located test app device."
        }

        $properties = @{}
        Get-Content -LiteralPath $script:configPath -Encoding UTF8 | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
                $name, $value = $line.Split("=", 2)
                $properties[$name.Trim()] = $value.Trim()
            }
        }

        $script:hardwareSerial = $properties["testDeviceSerial"]
        if ([string]::IsNullOrWhiteSpace($script:hardwareSerial)) { throw "testDeviceSerial is required in $script:configPath" }
        $allowCoLocated = $properties["allowCoLocatedProductionApp"]
        if ($allowCoLocated -ne "true") { throw "allowCoLocatedProductionApp=true is required in $script:configPath" }
        $script:productionPackage = $properties["productionPackage"]
        $script:testPackage = $properties["testPackage"]
        if ($script:productionPackage -ne "com.lyco256.llm") { throw "productionPackage must be com.lyco256.llm" }
        if ($script:testPackage -ne "com.lyco256.llm.test") { throw "testPackage must be com.lyco256.llm.test" }
        if ($script:productionPackage -eq $script:testPackage) { throw "Production and test package IDs must be different." }
        $script:testRunnerComponent = "$($script:testPackage).test/androidx.test.runner.AndroidJUnitRunner"

        $script:adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        if (-not (Test-Path -LiteralPath $script:adb)) { throw "adb was not found: $script:adb" }
        $script:serial = Resolve-AllowedDevice -Adb $script:adb -HardwareSerial $script:hardwareSerial
        Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
        $env:ANDROID_SERIAL = $script:serial

        if (Test-Path -LiteralPath "C:\Program Files\Android\Android Studio\jbr") {
            $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
            Write-SafeLog "JAVA_HOME=$env:JAVA_HOME"
        }
        $script:buildFingerprint = Get-SafeValidationFingerprint -RepoRoot $repoRoot -InputPaths $buildInputs -CacheVersion "safe-shared-build-v1"
        $script:unitMainFingerprint = Get-SafeValidationFingerprint -RepoRoot $repoRoot -InputPaths $unitMainInputs -CacheVersion "safe-unit-main-v1"
        $script:unitFingerprint = Get-SafeValidationFingerprint -RepoRoot $repoRoot -InputPaths $unitInputs -CacheVersion "safe-unit-validation-v3"
        $script:lintFingerprint = Get-SafeValidationFingerprint -RepoRoot $repoRoot -InputPaths $lintInputs -CacheVersion "safe-lint-validation-v2"

        $script:productionBefore = Get-PackageMetadata -Adb $script:adb -Serial $script:serial -PackageName $script:productionPackage
    }

    try {
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

        Invoke-SafePhase -Name "Install" -Action {
            $script:targetApk = Join-Path $repoRoot "app\build\outputs\apk\integrationTest\app-integrationTest.apk"
            $script:androidTestApk = Join-Path $repoRoot "app\build\outputs\apk\androidTest\integrationTest\app-integrationTest-androidTest.apk"
            if (-not (Test-Path -LiteralPath $script:targetApk -PathType Leaf)) {
                throw "Integration test APK was not found: $script:targetApk"
            }
            if (-not (Test-Path -LiteralPath $script:androidTestApk -PathType Leaf)) {
                throw "Integration test androidTest APK was not found: $script:androidTestApk"
            }
            $aapt = Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools\*\aapt.exe") |
                Sort-Object { [version]$_.Directory.Name } -Descending |
                Select-Object -First 1
            if (-not $aapt) { throw "aapt was not found; cannot verify the target APK package before installation." }
            $asciiApk = Join-Path $env:TEMP "like-list-manager-integration-test.apk"
            Copy-Item -LiteralPath $script:targetApk -Destination $asciiApk -Force
            $badgingOutput = @(& $aapt.FullName dump badging $asciiApk 2>&1)
            foreach ($line in $badgingOutput) {
                Write-SafeLog ($line | Out-String).TrimEnd()
            }
            $badging = $badgingOutput | Select-Object -First 1
            if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($badging) -or $badging -notmatch "package: name='$([regex]::Escape($script:testPackage))'") {
                throw "Refusing installation because target APK is not $script:testPackage`: $badging"
            }
            $asciiAndroidTestApk = Join-Path $env:TEMP "like-list-manager-integration-test-androidTest.apk"
            Copy-Item -LiteralPath $script:androidTestApk -Destination $asciiAndroidTestApk -Force
            $androidTestBadgingOutput = @(& $aapt.FullName dump badging $asciiAndroidTestApk 2>&1)
            foreach ($line in $androidTestBadgingOutput) {
                Write-SafeLog ($line | Out-String).TrimEnd()
            }
            $androidTestBadging = $androidTestBadgingOutput | Select-Object -First 1
            $expectedAndroidTestPackage = [regex]::Escape("$($script:testPackage).test")
            if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($androidTestBadging) -or $androidTestBadging -notmatch "package: name='$expectedAndroidTestPackage'") {
                throw "Refusing installation because androidTest APK is not $script:testPackage`.test`: $androidTestBadging"
            }
            Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
            Invoke-SafeNativeCommand -FilePath $script:adb -Arguments @("-s", $script:serial, "install", "-r", $script:targetApk) -TimeoutSeconds $timeouts.Install -WorkingDirectory $repoRoot
            Invoke-SafeNativeCommand -FilePath $script:adb -Arguments @("-s", $script:serial, "install", "-r", $script:androidTestApk) -TimeoutSeconds $timeouts.Install -WorkingDirectory $repoRoot
        }

        Invoke-SafePhase -Name "IntegrationTest" -Action {
            Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
            Prepare-ComposeTestDevice -Adb $script:adb -Serial $script:serial
            Invoke-SafeNativeCommand -FilePath $script:adb -Arguments @("-s", $script:serial, "shell", "am", "instrument", "-w", "-r", $script:testRunnerComponent) -TimeoutSeconds $timeouts.IntegrationTest -WorkingDirectory $repoRoot
            $testPath = ((Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "pm", "path", $script:testPackage)) -join "`n").Trim()
            if (-not $testPath.StartsWith("package:")) {
                Invoke-SafeNativeCommand -FilePath $script:adb -Arguments @("-s", $script:serial, "install", "-r", $script:targetApk) -TimeoutSeconds $timeouts.Install -WorkingDirectory $repoRoot
            }
        }
    } catch {
        $script:runError = $_.Exception
    }

    Write-SafeLog ""
    Write-SafeLog "## PostCheck"
    try {
        Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
        $productionAfter = Get-PackageMetadata -Adb $script:adb -Serial $script:serial -PackageName $script:productionPackage
        if ($script:productionBefore -ne $productionAfter) {
            throw [SafePhaseException]::new("IntegrationTest", "Production package metadata changed during integration tests.", "")
        }
        if ($script:runError) {
            throw $script:runError
        }
        $testMetadata = Get-PackageMetadata -Adb $script:adb -Serial $script:serial -PackageName $script:testPackage
        $productionUserId = [regex]::Match($productionAfter, "uid:(\d+)").Groups[1].Value
        $testUserId = [regex]::Match($testMetadata, "uid:(\d+)").Groups[1].Value
        if (-not $productionUserId -or -not $testUserId -or $productionUserId -eq $testUserId) {
            throw [SafePhaseException]::new("IntegrationTest", "Production and test apps do not have distinct Android UIDs.", "")
        }
        $script:serial = Resolve-AllowedDevice -Adb $script:adb -HardwareSerial $script:hardwareSerial
        $env:ANDROID_SERIAL = $script:serial
    } catch {
        if ($_.Exception -is [SafePhaseException]) {
            throw
        }
        throw [SafePhaseException]::new("IntegrationTest", $_.Exception.Message, "")
    }

    Complete-SafeScript
    Write-Host "Success"
    exit 0
} catch {
    Stop-SafeScriptWithFailure -Exception $_.Exception
}
