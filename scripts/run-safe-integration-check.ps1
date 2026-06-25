param(
    [string]$DeviceConfig = (Join-Path $PSScriptRoot "..\test-device.local.properties")
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$configPath = [System.IO.Path]::GetFullPath($DeviceConfig)
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Missing local device allow-list: $configPath. Copy test-device.local.properties.example and register the co-located test app device."
}

$properties = @{}
Get-Content -LiteralPath $configPath -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
        $name, $value = $line.Split("=", 2)
        $properties[$name.Trim()] = $value.Trim()
    }
}
$serial = $properties["testDeviceSerial"]
if ([string]::IsNullOrWhiteSpace($serial)) { throw "testDeviceSerial is required in $configPath" }
$allowCoLocated = $properties["allowCoLocatedProductionApp"]
if ($allowCoLocated -ne "true") { throw "allowCoLocatedProductionApp=true is required in $configPath" }
$productionPackage = $properties["productionPackage"]
$testPackage = $properties["testPackage"]
if ($productionPackage -ne "com.lyco256.llm") { throw "productionPackage must be com.lyco256.llm" }
if ($testPackage -ne "com.lyco256.llm.test") { throw "testPackage must be com.lyco256.llm.test" }
if ($productionPackage -eq $testPackage) { throw "Production and test package IDs must be different." }

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) { throw "adb was not found: $adb" }
function Wait-AllowedDevice([int]$TimeoutSeconds = 90) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $deviceLines = @(& $adb devices -l | Where-Object { $_ -match "\sdevice\s" })
        $selected = @($deviceLines | Where-Object { $_ -match "^$([regex]::Escape($serial))\s" })
        if ($selected.Count -eq 1) { return }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Allowed test device $serial did not reconnect exactly once within $TimeoutSeconds seconds.`n$($deviceLines -join "`n")"
}
Wait-AllowedDevice

$env:ANDROID_SERIAL = $serial
function Get-PackageMetadata([string]$PackageName) {
    $path = ((& $adb -s $serial shell pm path $PackageName) -join "`n").Trim()
    if (-not $path.StartsWith("package:")) { throw "Required package is not installed: $PackageName" }
    $details = (& $adb -s $serial shell dumpsys package $PackageName |
        Select-String "firstInstallTime=|lastUpdateTime=|versionCode=|codePath=" |
        ForEach-Object { $_.Line.Trim() }) -join "`n"
    $uid = ((& $adb -s $serial shell cmd package list packages -U $PackageName) -join "`n").Trim()
    if ($uid -notmatch "package:$([regex]::Escape($PackageName))\s+uid:\d+") {
        throw "Could not verify Android UID for $PackageName`: $uid"
    }
    return "$path`n$uid`n$details"
}

$productionBefore = Get-PackageMetadata $productionPackage

$runError = $null
Push-Location $repoRoot
try {
    & (Join-Path $PSScriptRoot "run-safe-debug-check.cmd")
    if ($LASTEXITCODE -ne 0) { throw "Local safety gate failed." }
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    & .\gradlew.bat verifyTestEnvironmentIsolation assembleIntegrationTest assembleIntegrationTestAndroidTest --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Integration test APK build failed." }
    $targetApk = Join-Path $repoRoot "app\build\outputs\apk\integrationTest\app-integrationTest.apk"
    $aapt = Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools\*\aapt.exe") |
        Sort-Object { [version]$_.Directory.Name } -Descending |
        Select-Object -First 1
    if (-not $aapt) { throw "aapt was not found; cannot verify the target APK package before installation." }
    $asciiApk = Join-Path $env:TEMP "like-list-manager-integration-test.apk"
    Copy-Item -LiteralPath $targetApk -Destination $asciiApk -Force
    $badgingOutput = & $aapt.FullName dump badging $asciiApk 2>&1
    $aaptExitCode = $LASTEXITCODE
    $badging = $badgingOutput | Select-Object -First 1
    if ($aaptExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($badging) -or $badging -notmatch "package: name='$([regex]::Escape($testPackage))'") {
        throw "Refusing installation because target APK is not $testPackage`: $badging"
    }
    Wait-AllowedDevice
    & .\gradlew.bat connectedIntegrationTestAndroidTest --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Integration test gate failed." }
    $testPath = ((& $adb -s $serial shell pm path $testPackage) -join "`n").Trim()
    if (-not $testPath.StartsWith("package:")) {
        & $adb -s $serial install -r $targetApk
        if ($LASTEXITCODE -ne 0) { throw "Could not keep the isolated test app installed after the test run." }
    }
} catch {
    $runError = $_
} finally {
    Pop-Location
}

Wait-AllowedDevice
$productionAfter = Get-PackageMetadata $productionPackage
if ($productionBefore -ne $productionAfter) { throw "Production package metadata changed during integration tests." }
if ($runError) { throw $runError }
$testMetadata = Get-PackageMetadata $testPackage
$productionUserId = [regex]::Match($productionAfter, "uid:(\d+)").Groups[1].Value
$testUserId = [regex]::Match($testMetadata, "uid:(\d+)").Groups[1].Value
if (-not $productionUserId -or -not $testUserId -or $productionUserId -eq $testUserId) {
    throw "Production and test apps do not have distinct Android UIDs."
}
Write-Host "Integration tests completed with $productionPackage and $testPackage co-located on device $serial."
