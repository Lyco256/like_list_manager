param(
    [ValidateSet("usb", "wireless")]
    [string]$DebugMethod = "usb",
    [string]$DeviceConfig = (Join-Path $PSScriptRoot "..\test-device.local.properties"),
    [switch]$VerifyOnly,
    [switch]$ValidationOnly
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "SafeScriptCommon.ps1")

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script:repoRoot = $repoRoot
$script:productionPackage = "com.lyco256.llm"
$script:instrumentationPackage = "com.lyco256.llm.rgb565backfill"
$script:runner = "com.lyco256.llm.rgb565backfill.Rgb565BackfillRunner"
$script:instrumentationClass = "com.lyco256.llm.rgb565backfill.ProductionRgb565BackfillInstrumentation"
$script:instrumentationInstalled = $false
$script:productionInvariantFailed = $false

function Invoke-LoggedAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    Write-SafeLog ""
    Write-SafeLog "> $script:adb $(Join-SafeCommandArguments -Arguments $Arguments)"
    $output = @(& $script:adb @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    foreach ($line in $output) {
        Write-SafeLog ($line | Out-String).TrimEnd()
    }
    if ($exitCode -ne 0) {
        $script:SafeLastErrorSummary = Get-SafeErrorSummaryFromText -Lines @(
            $output | ForEach-Object { $_.ToString() }
        )
        throw "adb failed with exit code $exitCode"
    }
    return $output
}

function Wait-AllowedDevice {
    param([string]$Serial, [int]$TimeoutSeconds = 90)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $devices = @(Invoke-LoggedAdb -Arguments @("devices", "-l"))
        $selected = @($devices | Where-Object {
                $_ -match "^$([regex]::Escape($Serial))\s+device\s"
            })
        $all = @($devices | Where-Object { $_ -match "^\S+\s+device\s" })
        if ($selected.Count -eq 1 -and $all.Count -eq 1) { return }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Allowed device did not resolve to exactly one connected adb target."
}

function Resolve-WirelessAllowedDevice {
    param([string]$HardwareSerial, [int]$TimeoutSeconds = 90)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $services = @(Invoke-LoggedAdb -Arguments @("mdns", "services"))
        foreach ($line in $services) {
            if ($line -match "^\s*(adb-[^\s]+)\s+_adb-tls-connect\._tcp\s+([^\s]+)\s*$" -and
                $line -match [regex]::Escape($HardwareSerial)
            ) {
                Invoke-LoggedAdb -Arguments @(
                    "connect",
                    "$($Matches[1])._adb-tls-connect._tcp"
                ) | Out-Null
            }
        }
        $devices = @(Invoke-LoggedAdb -Arguments @("devices", "-l"))
        $all = @($devices | Where-Object { $_ -match "^\S+\s+device\s" })
        $matches = @()
        foreach ($device in $all) {
            $endpoint = ($device -split "\s+")[0]
            $reported = ((Invoke-LoggedAdb -Arguments @(
                        "-s", $endpoint, "shell", "getprop", "ro.serialno"
                    )) -join "").Trim()
            if ($reported -eq $HardwareSerial) { $matches += $endpoint }
        }
        if ($matches.Count -eq 1 -and $all.Count -eq 1) { return $matches[0] }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Wireless allow-listed device did not resolve to exactly one endpoint."
}

function Get-Aapt {
    $aapt = Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools\*\aapt.exe") |
        Sort-Object { [version]$_.Directory.Name } -Descending |
        Select-Object -First 1
    if (-not $aapt) { throw "aapt was not found." }
    return $aapt.FullName
}

function Assert-ApkPackage {
    param([string]$Apk, [string]$ExpectedPackage)
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
        throw "APK was not found: $Apk"
    }
    $asciiApk = Join-Path $env:TEMP "llm-rgb565-backfill.apk"
    Copy-Item -LiteralPath $Apk -Destination $asciiApk -Force
    $badging = @(& $script:aapt dump badging $asciiApk 2>&1)
    foreach ($line in $badging) { Write-SafeLog ($line | Out-String).TrimEnd() }
    if ($LASTEXITCODE -ne 0 -or
        ($badging | Select-Object -First 1) -notmatch "package: name='$([regex]::Escape($ExpectedPackage))'"
    ) {
        throw "Instrumentation APK package is not $ExpectedPackage."
    }
    if ($ExpectedPackage -eq $script:productionPackage) {
        throw "Instrumentation package must differ from production."
    }
    $manifest = @(& $script:aapt dump xmltree $asciiApk AndroidManifest.xml 2>&1)
    foreach ($line in $manifest) { Write-SafeLog ($line | Out-String).TrimEnd() }
    if ($LASTEXITCODE -ne 0 -or
        ($manifest -join "`n") -notmatch "android:targetPackage[^`n]*=`"$([regex]::Escape($script:productionPackage))`"" -or
        ($manifest -join "`n") -notmatch "android:name[^`n]*=`"$([regex]::Escape($script:runner))`""
    ) {
        throw "Instrumentation target package or runner is not the dedicated backfill configuration."
    }
}

function Get-ProductionMetadata {
    $pathLines = @(Invoke-LoggedAdb -Arguments @(
            "-s", $script:serial, "shell", "pm", "path", $script:productionPackage
        ))
    $pathLine = $pathLines | Where-Object { $_ -match "^package:" } | Select-Object -First 1
    if (-not $pathLine) { throw "Production package is not installed." }
    $details = @(Invoke-LoggedAdb -Arguments @(
            "-s", $script:serial, "shell", "dumpsys", "package", $script:productionPackage
        ))
    $uidLines = @(Invoke-LoggedAdb -Arguments @(
            "-s", $script:serial, "shell", "cmd", "package", "list", "packages", "-U",
            $script:productionPackage
        ))
    $uidLine = $uidLines | Where-Object {
        $_ -match "^package:$([regex]::Escape($script:productionPackage))\s+uid:(\d+)$"
    } | Select-Object -First 1
    if (-not $uidLine) { throw "Production UID could not be read." }
    $uid = [regex]::Match($uidLine, "uid:(\d+)").Groups[1].Value
    $firstInstallLine = $details | Where-Object {
        $_ -match "firstInstallTime="
    } | Select-Object -First 1
    if (-not $firstInstallLine) { throw "Production firstInstallTime could not be read." }
    $firstInstall = $firstInstallLine.ToString().Substring(
        $firstInstallLine.ToString().IndexOf("firstInstallTime=") + 17
    ).Trim()
    $debuggable = (($details -join "`n") -match "(?m)pkgFlags=\[[^\]]*\bDEBUGGABLE\b")
    return [pscustomobject]@{
        Uid = $uid
        FirstInstallTime = $firstInstall
        ApkPath = $pathLine.ToString().Substring("package:".Length).Trim()
        Debuggable = $debuggable
    }
}

function Assert-FeatInstallEvidence {
    $logRoot = Join-Path $repoRoot "build\safe-script-logs\run-safe-debug-check"
    $evidence = Get-ChildItem -LiteralPath $logRoot -Filter "*.log" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Where-Object {
            $text = Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8
            $text -match "## feat/media-grid-rgb565-pack-cache" -and
            $text -match "## Install" -and
            $text -match "BUILD SUCCESSFUL" -and
            $text -match "After: uid=\d+.*firstInstallTime="
        } |
        Select-Object -First 1
    if (-not $evidence) {
        throw "No successful feat safe-install evidence was found."
    }
    Write-SafeLog "Feat safe-install evidence: $($evidence.Name)"
}

function Assert-TempBranchAndCleanTree {
    $branch = (& git branch --show-current).Trim()
    if ($branch -ne "temp/media-grid-rgb565-pack-backfill") {
        throw "Current branch must be temp/media-grid-rgb565-pack-backfill."
    }
    $status = @(& git status --porcelain)
    if ($status.Count -ne 0) { throw "Git working tree must be clean." }
    $feat = (& git rev-parse "origin/feat/media-grid-rgb565-pack-cache").Trim()
    $parent = (& git rev-parse "HEAD^").Trim()
    if ($parent -ne $feat) { throw "Temp branch must contain exactly one commit over pushed feat." }
    $ahead = [int]((& git rev-list --count "origin/feat/media-grid-rgb565-pack-cache..HEAD").Trim())
    if ($ahead -ne 1) { throw "Temp branch must contain exactly one commit over feat." }
    $remote = (& git rev-parse "origin/temp/media-grid-rgb565-pack-backfill").Trim()
    $head = (& git rev-parse "HEAD").Trim()
    if ($head -ne $remote) { throw "Temp branch must be pushed and match origin exactly." }
}

function Invoke-Instrumentation {
    param([Parameter(Mandatory = $true)][string]$Method)
    $component = "$($script:instrumentationPackage)/$($script:runner)"
    $output = @(Invoke-LoggedAdb -Arguments @(
            "-s", $script:serial, "shell", "am", "instrument", "-w", "-r",
            "-e", "class", "$($script:instrumentationClass)#$Method",
            $component
        ))
    $text = $output -join "`n"
    if ($text -notmatch "(?m)^OK \(1 test\)$") {
        throw "Instrumentation method failed: $Method"
    }
    return $text
}

function Assert-StatusValue {
    param([string]$Text, [string]$Name, [string]$Expected)
    if ($Text -notmatch "(?m)^INSTRUMENTATION_STATUS: $([regex]::Escape($Name))=$([regex]::Escape($Expected))$") {
        throw "Instrumentation status $Name=$Expected was not reported."
    }
}

Start-SafeScript -Name "run-media-grid-rgb565-backfill" -RepoRoot $repoRoot
Set-Location $repoRoot

try {
    Invoke-SafePhase -Name "Preflight" -Action {
        if (Test-Path -LiteralPath "C:\Program Files\Android\Android Studio\jbr") {
            $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        }
        $script:aapt = Get-Aapt
        if ($ValidationOnly) {
            if ((& git branch --show-current).Trim() -ne "temp/media-grid-rgb565-pack-backfill") {
                throw "ValidationOnly must run on the temp backfill branch."
            }
            return
        }
        Assert-TempBranchAndCleanTree
        $configPath = [IO.Path]::GetFullPath($DeviceConfig)
        if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
            throw "Missing device allow-list: $configPath"
        }
        $properties = @{}
        Get-Content -LiteralPath $configPath -Encoding UTF8 | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
                $name, $value = $line.Split("=", 2)
                $properties[$name.Trim()] = $value.Trim()
            }
        }
        $hardwareSerial = $properties["testDeviceSerial"]
        if ([string]::IsNullOrWhiteSpace($hardwareSerial)) {
            throw "testDeviceSerial is required."
        }
        if ($properties["allowCoLocatedProductionApp"] -ne "true") {
            throw "allowCoLocatedProductionApp=true is required."
        }
        if ($properties["productionPackage"] -ne $script:productionPackage) {
            throw "productionPackage must be $($script:productionPackage)."
        }
        $script:adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        if (-not (Test-Path -LiteralPath $script:adb)) { throw "adb was not found." }
        if ($DebugMethod -eq "wireless") {
            $script:serial = Resolve-WirelessAllowedDevice -HardwareSerial $hardwareSerial
        } else {
            $script:serial = $hardwareSerial
        }
        Wait-AllowedDevice -Serial $script:serial
        $env:ANDROID_SERIAL = $script:serial
        $script:productionBefore = Get-ProductionMetadata
        if (-not $script:productionBefore.Debuggable) {
            throw "Production package is not a debuggable build."
        }
        Assert-FeatInstallEvidence
    }

    Invoke-SafePhase -Name "Build" -Action {
        Invoke-SafeNativeCommand -FilePath (Join-Path $repoRoot "gradlew.bat") -Arguments @(
            ":rgb565backfill:assembleDebug",
            "--console=plain",
            "--no-daemon"
        ) -TimeoutSeconds 1800
        $script:instrumentationApk = Join-Path $repoRoot "rgb565backfill\build\outputs\apk\debug\rgb565backfill-debug.apk"
        Assert-ApkPackage -Apk $script:instrumentationApk -ExpectedPackage $script:instrumentationPackage
    }

    if ($ValidationOnly) {
        Complete-SafeScript
        Write-Host "Success"
        exit 0
    }

    Invoke-SafePhase -Name "InstallInstrumentation" -Action {
        Invoke-LoggedAdb -Arguments @(
            "-s", $script:serial, "install", "-r", $script:instrumentationApk
        ) | Out-Null
        $script:instrumentationInstalled = $true
    }

    Invoke-SafePhase -Name "ReadOnlyPreflight" -Action {
        $preflight = Invoke-Instrumentation -Method "preflightReadOnly"
        Assert-StatusValue -Text $preflight -Name "read_only_db" -Expected "true"
        Assert-StatusValue -Text $preflight -Name "raw_inside_files_dir" -Expected "true"
    }

    if ($VerifyOnly) {
        Invoke-SafePhase -Name "VerifyRawRead" -Action {
            $verified = Invoke-Instrumentation -Method "verifyFeatReaderAndAllSlots"
            Assert-StatusValue -Text $verified -Name "feat_reader_rgb565" -Expected "true"
        }
    } else {
        Invoke-SafePhase -Name "Backfill" -Action {
            $backfill = Invoke-Instrumentation -Method "runBackfillToCompletion"
            Assert-StatusValue -Text $backfill -Name "complete" -Expected "true"
            Assert-StatusValue -Text $backfill -Name "write_failed" -Expected "0"
            Assert-StatusValue -Text $backfill -Name "protected_data_unchanged" -Expected "true"
        }
    }

    Invoke-SafePhase -Name "ProductionInvariant" -Action {
        $after = Get-ProductionMetadata
        if ($after.Uid -ne $script:productionBefore.Uid -or
            $after.FirstInstallTime -ne $script:productionBefore.FirstInstallTime
        ) {
            $script:productionInvariantFailed = $true
            throw "Production UID or firstInstallTime changed."
        }
    }

    Invoke-SafePhase -Name "CleanupInstrumentation" -Action {
        Invoke-LoggedAdb -Arguments @(
            "-s", $script:serial, "uninstall", $script:instrumentationPackage
        ) | Out-Null
        $script:instrumentationInstalled = $false
    }

    Complete-SafeScript
    Write-Host "Success"
} catch {
    if ($script:instrumentationInstalled -and -not $script:productionInvariantFailed) {
        try {
            Invoke-LoggedAdb -Arguments @(
                "-s", $script:serial, "uninstall", $script:instrumentationPackage
            ) | Out-Null
        } catch {
            Write-SafeLog "Instrumentation cleanup failed."
        }
    }
    Stop-SafeScriptWithFailure -Exception $_.Exception
}
