param(
    [string]$DeviceConfig = (Join-Path $PSScriptRoot "..\test-device.local.properties")
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "SafeScriptCommon.ps1")

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$timeouts = @{
    Preflight = 300
    Build = 1800
    Install = 600
    Macrobenchmark = 5400
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

function Wait-AllowedDevice {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [int]$TimeoutSeconds = 90
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
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
    $uidLines = @(Invoke-LoggedAdb -Adb $Adb -Arguments @("-s", $Serial, "shell", "cmd", "package", "list", "packages", "-U", $PackageName))
    $uid = ($uidLines | Where-Object { $_ -match "^package:$([regex]::Escape($PackageName))\s+uid:\d+$" } | Select-Object -First 1)
    if (-not $uid) {
        throw "Could not verify Android UID for $PackageName`: $(($uidLines -join '; ').Trim())"
    }
    return "$path`n$uid`n$details"
}

function Get-Aapt {
    $aapt = Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools\*\aapt.exe") |
        Sort-Object { [version]$_.Directory.Name } -Descending |
        Select-Object -First 1
    if (-not $aapt) {
        throw "aapt was not found; cannot verify APK packages before installation."
    }
    return $aapt.FullName
}

function Assert-ApkPackage {
    param(
        [Parameter(Mandatory = $true)][string]$Aapt,
        [Parameter(Mandatory = $true)][string]$ApkPath,
        [Parameter(Mandatory = $true)][string]$ExpectedPackage,
        [Parameter(Mandatory = $true)][string]$TempName
    )
    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
        throw "APK was not found: $ApkPath"
    }
    $asciiApk = Join-Path $env:TEMP $TempName
    Copy-Item -LiteralPath $ApkPath -Destination $asciiApk -Force
    $badgingOutput = @(& $Aapt dump badging $asciiApk 2>&1)
    foreach ($line in $badgingOutput) {
        Write-SafeLog ($line | Out-String).TrimEnd()
    }
    $badging = $badgingOutput | Select-Object -First 1
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($badging) -or $badging -notmatch "package: name='$([regex]::Escape($ExpectedPackage))'") {
        throw "Refusing APK because it is not $ExpectedPackage`: $badging"
    }
}

Start-SafeScript -Name "run-safe-macrobenchmark-check" -RepoRoot $repoRoot
Set-Location $repoRoot

$serial = ""
$productionPackage = ""
$benchmarkPackage = ""
$macrobenchmarkHostPackage = ""
$adb = ""
$aapt = ""
$benchmarkApk = ""
$macrobenchmarkHostApk = ""
$macrobenchmarkTestApk = ""
$productionBefore = ""
$runError = $null

try {
    Invoke-SafePhase -Name "Preflight" -Action {
        $configPath = [System.IO.Path]::GetFullPath($DeviceConfig)
        if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
            throw "Missing local device allow-list: $configPath. Copy test-device.local.properties.example and register the co-located benchmark device."
        }

        $properties = @{}
        Get-Content -LiteralPath $configPath -Encoding UTF8 | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
                $name, $value = $line.Split("=", 2)
                $properties[$name.Trim()] = $value.Trim()
            }
        }

        $script:serial = $properties["testDeviceSerial"]
        if ([string]::IsNullOrWhiteSpace($script:serial)) { throw "testDeviceSerial is required in $configPath" }
        if ($properties["allowCoLocatedProductionApp"] -ne "true") { throw "allowCoLocatedProductionApp=true is required in $configPath" }
        $script:productionPackage = $properties["productionPackage"]
        $script:benchmarkPackage = $properties["benchmarkPackage"]
        $script:macrobenchmarkHostPackage = $properties["macrobenchmarkHostPackage"]
        if ([string]::IsNullOrWhiteSpace($script:benchmarkPackage)) { $script:benchmarkPackage = "com.lyco256.llm.test.benchmark" }
        if ([string]::IsNullOrWhiteSpace($script:macrobenchmarkHostPackage)) { $script:macrobenchmarkHostPackage = "com.lyco256.llm.macrobenchmark.host" }
        if ($script:productionPackage -ne "com.lyco256.llm") { throw "productionPackage must be com.lyco256.llm" }
        if ($script:benchmarkPackage -ne "com.lyco256.llm.test.benchmark") { throw "benchmarkPackage must be com.lyco256.llm.test.benchmark" }
        if ($script:macrobenchmarkHostPackage -ne "com.lyco256.llm.macrobenchmark.host") { throw "macrobenchmarkHostPackage must be com.lyco256.llm.macrobenchmark.host" }
        if ($script:productionPackage -eq $script:benchmarkPackage -or $script:productionPackage -eq $script:macrobenchmarkHostPackage) {
            throw "Production package ID must be distinct from benchmark packages."
        }

        $script:adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        if (-not (Test-Path -LiteralPath $script:adb)) { throw "adb was not found: $script:adb" }
        $script:aapt = Get-Aapt
        Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
        $env:ANDROID_SERIAL = $script:serial

        if (Test-Path -LiteralPath "C:\Program Files\Android\Android Studio\jbr") {
            $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
            Write-SafeLog "JAVA_HOME=$env:JAVA_HOME"
        }

        $script:productionBefore = Get-PackageMetadata -Adb $script:adb -Serial $script:serial -PackageName $script:productionPackage
    }

    try {
        Invoke-SafePhase -Name "Build" -Action {
            Invoke-SafeNativeCommand -FilePath ".\gradlew.bat" -Arguments @(":app:assembleBenchmark", ":app:verifyTestEnvironmentIsolation", ":macrobenchmark:assembleBenchmark", ":macrobenchmark:assembleBenchmarkAndroidTest", "--console=plain", "--no-daemon") -TimeoutSeconds $timeouts.Build -WorkingDirectory $repoRoot
            $script:benchmarkApk = Join-Path $repoRoot "app\build\outputs\apk\benchmark\app-benchmark.apk"
            $script:macrobenchmarkHostApk = Join-Path $repoRoot "macrobenchmark\build\outputs\apk\benchmark\macrobenchmark-benchmark.apk"
            $script:macrobenchmarkTestApk = Join-Path $repoRoot "macrobenchmark\build\outputs\apk\androidTest\benchmark\macrobenchmark-benchmark-androidTest.apk"
            Assert-ApkPackage -Aapt $script:aapt -ApkPath $script:benchmarkApk -ExpectedPackage $script:benchmarkPackage -TempName "like-list-manager-benchmark-target.apk"
            Assert-ApkPackage -Aapt $script:aapt -ApkPath $script:macrobenchmarkHostApk -ExpectedPackage $script:macrobenchmarkHostPackage -TempName "like-list-manager-macrobenchmark-host.apk"
            if (-not (Test-Path -LiteralPath $script:macrobenchmarkTestApk -PathType Leaf)) {
                throw "Macrobenchmark androidTest APK was not found: $script:macrobenchmarkTestApk"
            }
        }

        Invoke-SafePhase -Name "InstallBenchmarkTarget" -Action {
            Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
            Invoke-SafeNativeCommand -FilePath $script:adb -Arguments @("-s", $script:serial, "install", "-r", $script:benchmarkApk) -TimeoutSeconds $timeouts.Install -WorkingDirectory $repoRoot
            $targetMetadata = Get-PackageMetadata -Adb $script:adb -Serial $script:serial -PackageName $script:benchmarkPackage
            $productionUserId = [regex]::Match($script:productionBefore, "uid:(\d+)").Groups[1].Value
            $targetUserId = [regex]::Match($targetMetadata, "uid:(\d+)").Groups[1].Value
            if (-not $productionUserId -or -not $targetUserId -or $productionUserId -eq $targetUserId) {
                throw "Production and benchmark target apps do not have distinct Android UIDs."
            }
        }

        Invoke-SafePhase -Name "Macrobenchmark" -Action {
            Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
            Invoke-SafeNativeCommand -FilePath ".\gradlew.bat" -Arguments @(":macrobenchmark:connectedBenchmarkAndroidTest", "--console=plain", "--no-daemon") -TimeoutSeconds $timeouts.Macrobenchmark -WorkingDirectory $repoRoot
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
            throw [SafePhaseException]::new("Macrobenchmark", "Production package metadata changed during macrobenchmark.", "")
        }
        if ($script:runError) {
            throw $script:runError
        }
        $targetMetadata = Get-PackageMetadata -Adb $script:adb -Serial $script:serial -PackageName $script:benchmarkPackage
        $productionUserId = [regex]::Match($productionAfter, "uid:(\d+)").Groups[1].Value
        $targetUserId = [regex]::Match($targetMetadata, "uid:(\d+)").Groups[1].Value
        if (-not $productionUserId -or -not $targetUserId -or $productionUserId -eq $targetUserId) {
            throw [SafePhaseException]::new("Macrobenchmark", "Production and benchmark target apps do not have distinct Android UIDs.", "")
        }
    } catch {
        if ($_.Exception -is [SafePhaseException]) {
            throw
        }
        throw [SafePhaseException]::new("Macrobenchmark", $_.Exception.Message, "")
    }

    Complete-SafeScript
    Write-Host "Success"
    exit 0
} catch {
    Stop-SafeScriptWithFailure -Exception $_.Exception
}
