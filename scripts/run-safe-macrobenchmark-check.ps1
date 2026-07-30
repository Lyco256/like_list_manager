param(
    [string]$DeviceConfig = (Join-Path $PSScriptRoot "..\test-device.local.properties"),
    [switch]$CleanupOnly,
    [switch]$RecoverMetricsOnly,
    [switch]$DiagnosticsOnly
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "SafeScriptCommon.ps1")

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script:repoRoot = $repoRoot
$timeouts = @{
    Preflight = 300
    Build = 1800
    Install = 600
    Macrobenchmark = 10800
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

function Capture-BenchmarkFailureLog {
    if (-not $script:adb -or -not $script:serial) { return }
    try {
        $lines = @(& $script:adb -s $script:serial logcat -d -t 3000 -v brief 2>&1)
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            Write-SafeLog "Filtered benchmark log capture failed with exit code $exitCode."
            return
        }
        Write-SafeLog "Filtered benchmark runtime log (failure diagnostics):"
        foreach ($line in $lines) {
            $text = ($line | Out-String).TrimEnd()
            if ($text -match 'com\.lyco256\.llm\.test\.benchmark|com\.lyco256\.llm\.macrobenchmark|AndroidJUnitRunner|PersistentBenchmarkRunner|AndroidRuntime|FATAL EXCEPTION|Benchmark target|BenchmarkSnapshotSetup|media-grid') {
                Write-SafeLog $text
            }
        }
    } catch {
        Write-SafeLog "Filtered benchmark log capture failed: $($_.Exception.Message)"
    }
}

function Send-BenchmarkSnapshotToInternalHandoff {
    param(
        [Parameter(Mandatory = $true)][string]$Archive,
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName
    )
    $handoff = "files/benchmark-handoff/media-grid-snapshot.zip"
    $arguments = @("-s", $Serial, "exec-in", "run-as", $PackageName, "dd", "of=$handoff", "bs=64K", "conv=fsync")
    Write-SafeLog ""
    Write-SafeLog "> $Adb $(Join-SafeCommandArguments -Arguments $arguments)"

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $Adb
    $startInfo.Arguments = Join-SafeCommandArguments -Arguments $arguments
    $startInfo.WorkingDirectory = $script:repoRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $stream = $null
    try {
        if (-not $process.Start()) { throw "Could not start adb exec-in for internal benchmark handoff." }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $stream = [System.IO.File]::OpenRead($Archive)
        $localLength = $stream.Length
        $stream.CopyTo($process.StandardInput.BaseStream)
        $blockSize = 64 * 1024
        $padding = ($blockSize - ($localLength % $blockSize)) % $blockSize
        if ($padding -gt 0) {
            $zeroPadding = [byte[]]::new([int]$padding)
            $process.StandardInput.BaseStream.Write($zeroPadding, 0, $zeroPadding.Length)
        }
        $process.StandardInput.BaseStream.Flush()
        $process.StandardInput.Close()
        if (-not $process.WaitForExit($timeouts.Install * 1000)) {
            try { $process.Kill() } catch { }
            throw "Timed out transferring benchmark snapshot to internal app storage. exec-in is required; no external-storage fallback is allowed."
        }
        $stdout = $stdoutTask.Result
        $stderr = $stderrTask.Result
        if ($stdout) { Write-SafeLog $stdout.TrimEnd() }
        if ($stderr) { Write-SafeLog $stderr.TrimEnd() }
        if ($process.ExitCode -ne 0) {
            throw "adb exec-in internal benchmark handoff failed with exit code $($process.ExitCode). exec-in is required; no external-storage fallback is allowed."
        }
    } finally {
        if ($stream) { $stream.Dispose() }
        $process.Dispose()
    }

    $local = Get-Item -LiteralPath $Archive -ErrorAction Stop
    $handoffAbsolute = "/data/user/0/$PackageName/$handoff"
    $trimmedHandoff = "$handoffAbsolute.trimmed"
    Invoke-LoggedAdb -Adb $Adb -Arguments @(
        "-s", $Serial, "exec-out", "run-as", $PackageName, "sh", "-c",
        "toybox head -c $($local.Length) '$handoffAbsolute' > '$trimmedHandoff' && mv '$trimmedHandoff' '$handoffAbsolute'"
    ) | Out-Null
    $localHash = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant()
    $remoteHashText = (Invoke-LoggedAdb -Adb $Adb -Arguments @("-s", $Serial, "shell", "run-as", $PackageName, "sha256sum", $handoff)) -join "`n"
    $remoteHashMatch = [regex]::Match($remoteHashText, "(?i)([0-9a-f]{64})")
    if (-not $remoteHashMatch.Success) { throw "Could not read SHA-256 for internal benchmark handoff." }
    $remoteHash = $remoteHashMatch.Groups[1].Value.ToLowerInvariant()
    $remoteSizeText = ((Invoke-LoggedAdb -Adb $Adb -Arguments @("-s", $Serial, "shell", "run-as", $PackageName, "stat", "-c", "%s", $handoff)) -join "").Trim()
    [long]$remoteSize = 0
    if (-not [long]::TryParse($remoteSizeText, [ref]$remoteSize)) { throw "Could not read size for internal benchmark handoff: $remoteSizeText" }
    if ($local.Length -ne $remoteSize -or $localHash -ne $remoteHash) {
        throw "Internal benchmark handoff verification failed: localSize=$($local.Length) remoteSize=$remoteSize localSha256=$localHash remoteSha256=$remoteHash"
    }
    Write-SafeLog "Internal benchmark handoff verified: size=$remoteSize sha256=$remoteHash"
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

function Resolve-RemovableStorageRoot {
    param([switch]$Required)
    $volumes = @(Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "sm", "list-volumes", "all"))
    $uuids = @($volumes | ForEach-Object {
            $match = [regex]::Match($_.ToString(), "^public:\S+\s+mounted\s+(\S+)$")
            if ($match.Success) { $match.Groups[1].Value }
        } | Where-Object { $_ } | Select-Object -Unique)
    if ($uuids.Count -ne 1) {
        if ($Required) { throw "Exactly one mounted removable SD volume is required; found $($uuids.Count)." }
        return ""
    }
    $root = "/storage/$($uuids[0])"
    & $script:adb -s $script:serial shell test -d $root 2>$null
    if ($LASTEXITCODE -ne 0) {
        if ($Required) { throw "Mounted removable SD root is not accessible: $root" }
        return ""
    }
    Write-SafeLog "Removable benchmark output root: $root"
    return $root
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
        Select-String "firstInstallTime=|lastUpdateTime=|versionCode=|codePath=|dataDir=" |
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

function Invoke-QuietAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    Write-SafeLog ""
    Write-SafeLog "> $script:adb $(Join-SafeCommandArguments -Arguments $Arguments)"
    $output = @(& $script:adb @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        foreach ($line in $output) { Write-SafeLog ($line | Out-String).TrimEnd() }
        $script:SafeLastErrorSummary = Get-SafeErrorSummaryFromText -Lines @($output | ForEach-Object { $_.ToString() })
        throw "adb failed while preparing the read-only snapshot: $($script:SafeLastErrorSummary)"
    }
    return $output
}

function Invoke-RunAsText {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return Invoke-QuietAdb -Arguments (@("-s", $script:serial, "shell", "run-as", $script:productionPackage) + $Arguments)
}

function Test-RunAsFile {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    # Pass the test command as argv instead of embedding it in sh -c. The
    # latter is interpreted differently by adb.exe on Windows and can make a
    # successful run-as probe look like a missing file.
    $arguments = @("-s", $script:serial, "shell", "run-as", $script:productionPackage, "test", "-f", $RelativePath)
    $output = @(& $script:adb @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) { return $true }
    if ($exitCode -eq 1) { return $false }
    foreach ($line in $output) { Write-SafeLog ($line | Out-String).TrimEnd() }
    $script:SafeLastErrorSummary = Get-SafeErrorSummaryFromText -Lines @($output | ForEach-Object { $_.ToString() })
    throw "Could not inspect production file through run-as: $RelativePath ($($script:SafeLastErrorSummary))"
}

function Test-RunAsDirectory {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    $arguments = @("-s", $script:serial, "shell", "run-as", $script:productionPackage, "test", "-d", $RelativePath)
    $output = @(& $script:adb @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) { return $true }
    if ($exitCode -eq 1) { return $false }
    foreach ($line in $output) { Write-SafeLog ($line | Out-String).TrimEnd() }
    throw "Could not inspect production directory through run-as: $RelativePath"
}

function Copy-RunAsBinary {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    $destinationParent = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $destinationParent | Out-Null
    # Keep the privilege boundary at run-as. On this Samsung wireless target,
    # exec-out/cmd redirection is unreliable for this external app-storage
    # path on the wireless target. Capture adb's binary stdout through
    # Process.BaseStream while keeping the command itself run-as-only.
    $arguments = @("-s", $script:serial, "exec-out", "run-as", $script:productionPackage, "cat", $RelativePath)
    $argumentText = Join-SafeCommandArguments -Arguments $arguments
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $script:adb
    $process.StartInfo.Arguments = $argumentText
    $process.StartInfo.WorkingDirectory = $script:repoRoot
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $outputStream = $null
    $stderrTask = $null
    try {
        if (-not $process.Start()) { throw "Could not copy the production snapshot file: $RelativePath" }
        $outputStream = [IO.File]::Open($Destination, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.StandardOutput.BaseStream.CopyTo($outputStream)
        if (-not $process.WaitForExit(120000)) {
            $process.Kill()
            throw "Timed out copying the production snapshot file: $RelativePath"
        }
        $copyError = if ($stderrTask) { $stderrTask.Result.Trim() } else { "no adb stderr" }
    } finally {
        if ($outputStream) { $outputStream.Dispose() }
    }
    $destinationExists = Test-Path -LiteralPath $Destination -PathType Leaf
    if ($process.ExitCode -ne 0 -or -not $destinationExists) {
        $destinationLength = if ($destinationExists) { (Get-Item -LiteralPath $Destination).Length } else { 0 }
        $copyError = ($copyError -replace "\s+", " ").Trim()
        Write-SafeLog "read-only copy failed for $RelativePath (adbExit=$($process.ExitCode), destinationBytes=$destinationLength, stderr=$copyError)"
        Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
        throw "Could not copy the production snapshot file: $RelativePath"
    }
    $process.Dispose()
}

function Get-RunAsFiles {
    param([Parameter(Mandatory = $true)][string]$FindRoot)
    # Keep find arguments separate. Quoting a complete shell command here
    # caused adb.exe to lose find's predicates on the wireless device.
    $lines = Invoke-RunAsText -Arguments @("find", $FindRoot, "-type", "f", "-print")
    return @($lines | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ -and $_ -notmatch "^find:" })
}

function Get-ProductionDataFingerprint {
    $paths = @("$($script:productionDbRoot)/like_list_manager.db")
    foreach ($optionalPath in @("$($script:productionDbRoot)/like_list_manager.db-wal", "$($script:productionDbRoot)/like_list_manager.db-shm")) {
        if (Test-RunAsFile -RelativePath $optionalPath) { $paths += $optionalPath }
    }
    $paths += @(Get-RunAsFiles -FindRoot $script:productionImagesRoot)
    if (Test-RunAsDirectory -RelativePath $script:productionPreviewRoot) {
        $paths += @(Get-RunAsFiles -FindRoot $script:productionPreviewRoot)
    }
    $hashes = foreach ($path in $paths) {
        $lines = Invoke-RunAsText -Arguments @("sha256sum", $path)
        $line = $lines | Select-Object -First 1
        if ($line -notmatch "^([0-9a-fA-F]{64})\s+") { throw "Could not verify production data hash." }
        "$path=$($Matches[1].ToLowerInvariant())"
    }
    return ($hashes -join "`n")
}

function Resolve-ProductionStoragePaths {
    $script:productionDbRoot = "databases"
    $script:productionImagesRoot = "files/images"
    $script:productionPreviewRoot = "files/media_grid_previews/v1"
    if (Test-RunAsFile -RelativePath "$($script:productionDbRoot)/like_list_manager.db") { return }

    # Read only the storage-location selector to locate the active database. The
    # preference file itself is never copied into the snapshot or to the PC.
    $selectedPathLines = @()
    if (Test-RunAsFile -RelativePath "shared_prefs/post_storage_settings.xml") {
        $selectedPathLines = @(Invoke-RunAsText -Arguments @("cat", "shared_prefs/post_storage_settings.xml"))
    }
    $selectedPath = $null
    foreach ($line in $selectedPathLines) {
        if ($line.ToString() -match '<string name="selected_path">([^<]+)</string>') {
            $selectedPath = $Matches[1]
            break
        }
    }
    if ($selectedPath -and (Test-RunAsFile -RelativePath "$selectedPath/like_list_manager.db")) {
        $script:productionDbRoot = $selectedPath.TrimEnd('/')
        $script:productionImagesRoot = "$($script:productionDbRoot)/images"
        return
    }

    $privateDbCandidates = @(Invoke-RunAsText -Arguments @(
            "find", ".", "-type", "f", "-print"
        ) | ForEach-Object { $_.ToString().Trim() } | Where-Object {
            $_ -and $_ -notmatch "^find:" -and $_ -match "(^|/)like_list_manager\.db$"
        } | ForEach-Object { $_.TrimStart('./') })
    Write-SafeLog "run-as private database candidates: $($privateDbCandidates -join ', ')"
    $privateDb = $privateDbCandidates | Where-Object { $_ -match "(^|/)like_list_manager\.db$" } | Select-Object -First 1
    if ($privateDb -and $privateDb -match "^files/post_data/like_list_manager\.db$") {
        $script:productionDbRoot = "files/post_data"
        $script:productionImagesRoot = "files/post_data/images"
        return
    }

    $externalPattern = "/Android/data/$([regex]::Escape($script:productionPackage))/files/post_data/like_list_manager\.db$"
    $externalDbCandidates = @()
    foreach ($externalRoot in @("/sdcard", "/storage")) {
        try {
            $externalDbCandidates += @(Invoke-RunAsText -Arguments @(
                    "find", $externalRoot, "-type", "f", "-print"
                ) | ForEach-Object { $_.ToString().Trim() } | Where-Object {
                    $_ -and $_ -notmatch "^find:" -and $_ -match $externalPattern
                })
        } catch {
            # Some Android versions deny one of these aliases to run-as. The
            # other root, or the selected_path/internal lookup, may still work.
        }
    }
    $externalDb = $externalDbCandidates | Select-Object -First 1
    if ($externalDb) {
        $script:productionDbRoot = $externalDb.Substring(0, $externalDb.Length - "/like_list_manager.db".Length)
        $script:productionImagesRoot = "$($script:productionDbRoot)/images"
        return
    }
    throw "Production Room database was not found through run-as in internal or external storage."
}

function Select-RepresentativeOriginals {
    param([Parameter(Mandatory = $true)][string[]]$Paths)
    $ordered = @($Paths | Sort-Object { [IO.Path]::GetExtension($_).ToLowerInvariant() }, { [IO.Path]::GetFileName($_).ToLowerInvariant() })
    if ($ordered.Count -le 256) { return $ordered }
    $selected = New-Object System.Collections.Generic.List[string]
    for ($index = 0; $index -lt 256; $index++) {
        $candidate = $ordered[[math]::Floor($index * $ordered.Count / 256)]
        if (-not $selected.Contains($candidate)) { $selected.Add($candidate) }
    }
    return @($selected)
}

function Get-Sha256Text {
    param([Parameter(Mandatory = $true)][string]$Value)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return (($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)) | ForEach-Object { $_.ToString("x2") }) -join "")
    } finally {
        $sha.Dispose()
    }
}

function New-ProductionSnapshot {
    $snapshotRoot = Join-Path $script:repoRoot "build\media-grid-benchmark-snapshot"
    $archive = Join-Path $script:repoRoot "build\media-grid-benchmark-snapshot.zip"
    $script:snapshotRoot = $snapshotRoot
    $script:snapshotArchive = $archive
    Remove-Item -LiteralPath $snapshotRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Join-Path $snapshotRoot "db"), (Join-Path $snapshotRoot "previews"), (Join-Path $snapshotRoot "originals") | Out-Null
    Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "am", "force-stop", $script:productionPackage) | Out-Null
    $runAsCheck = Invoke-RunAsText -Arguments @("id")
    if (($runAsCheck -join " ") -notmatch "uid=\d+") { throw "run-as $($script:productionPackage) is unavailable; refusing any fallback." }
    Resolve-ProductionStoragePaths
    $script:productionDataBefore = Get-ProductionDataFingerprint
    $dbPaths = @(
        "$($script:productionDbRoot)/like_list_manager.db",
        "$($script:productionDbRoot)/like_list_manager.db-wal",
        "$($script:productionDbRoot)/like_list_manager.db-shm"
    )
    foreach ($path in $dbPaths) {
        if (Test-RunAsFile -RelativePath $path) { Copy-RunAsBinary -RelativePath $path -Destination (Join-Path $snapshotRoot ("db/" + ([IO.Path]::GetFileName($path)))) }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $snapshotRoot "db/like_list_manager.db") -PathType Leaf)) { throw "Production Room database was not found through run-as." }
    if (-not (Test-RunAsDirectory -RelativePath $script:productionPreviewRoot)) {
        throw "Production persistent preview directory is missing; the benchmark snapshot cannot be prepared."
    }
    $previewPaths = @(Get-RunAsFiles -FindRoot $script:productionPreviewRoot | Where-Object { [IO.Path]::GetExtension($_).ToLowerInvariant() -eq ".jpg" })
    if ($previewPaths.Count -eq 0) {
        throw "Production persistent preview directory contains no JPEGs; the benchmark snapshot cannot be prepared."
    }
    foreach ($path in $previewPaths) {
        $destination = Join-Path $snapshotRoot ("previews/" + ([IO.Path]::GetFileName($path)))
        Copy-RunAsBinary -RelativePath $path -Destination $destination
    }
    $originals = Select-RepresentativeOriginals -Paths @(Get-RunAsFiles -FindRoot $script:productionImagesRoot)
    $originalMetadata = New-Object System.Collections.Generic.List[object]
    foreach ($path in $originals) {
        $extension = [IO.Path]::GetExtension($path).ToLowerInvariant()
        $snapshotName = "$(Get-Sha256Text -Value $path)$extension"
        $sizeText = ((Invoke-RunAsText -Arguments @("stat", "-c", "%s", $path)) -join "").Trim()
        $modifiedText = ((Invoke-RunAsText -Arguments @("stat", "-c", "%Y", $path)) -join "").Trim()
        if ($sizeText -notmatch "^\d+$" -or $modifiedText -notmatch "^\d+$") {
            throw "Could not read production media metadata: $path (size=$sizeText modified=$modifiedText)"
        }
        Copy-RunAsBinary -RelativePath $path -Destination (Join-Path $snapshotRoot "originals/$snapshotName")
        $originalMetadata.Add([pscustomobject]@{
            snapshotName = $snapshotName
            originalPath = $path
            size = [long]$sizeText
            modified = [long]$modifiedText * 1000L
        })
    }
    [IO.File]::WriteAllText(
        (Join-Path $snapshotRoot "original-metadata.json"),
        ($originalMetadata | ConvertTo-Json -Compress),
        [Text.Encoding]::UTF8
    )
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($snapshotRoot, $archive)
    return $archive
}

function Import-SnapshotToBenchmarkTarget {
    Send-BenchmarkSnapshotToInternalHandoff -Archive $script:snapshotArchive -Adb $script:adb -Serial $script:serial -PackageName $script:benchmarkPackage
}

function Assert-BenchmarkSnapshotReady {
    param(
        [Parameter(Mandatory = $true)][string]$PackageMetadata,
        [Parameter(Mandatory = $true)][string]$ExpectedDataInode
    )
    $validationRoot = "/sdcard/Android/data/$($script:benchmarkPackage)/files/media-grid-validation"
    $markerText = (Invoke-LoggedAdb -Adb $script:adb -Arguments @(
            "-s", $script:serial, "shell", "cat", "$validationRoot/snapshot.json"
        )) -join "`n"
    try {
        $marker = $markerText | ConvertFrom-Json
    } catch {
        throw "Benchmark snapshot marker is not valid JSON: $markerText"
    }
    if ($marker.ready -ne $true) { throw "Benchmark snapshot marker is not ready: $markerText" }
    foreach ($field in @("activeClips", "activeMediaAssets", "taggedMediaClips", "localMediaAssets", "persistentPreviews")) {
        $value = 0L
        if (-not [long]::TryParse(([string]$marker.$field), [ref]$value) -or $value -le 0) {
            throw "Benchmark snapshot validation count is not positive: $field=$($marker.$field) marker=$markerText"
        }
    }
    $dataDirMatch = [regex]::Match($PackageMetadata, "(?m)^dataDir=(\S+)$")
    if (-not $dataDirMatch.Success) { throw "Benchmark package dataDir was not found in package metadata." }
    $expectedDatabasePath = "$($dataDirMatch.Groups[1].Value)/databases/like_list_manager_benchmark.db"
    if ([string]$marker.databasePath -ne $expectedDatabasePath) {
        throw "Benchmark snapshot runtime DB path mismatch: marker=$($marker.databasePath) expected=$expectedDatabasePath"
    }
    [long]$markerInode = 0
    [long]$expectedInodeValue = 0
    if (-not [long]::TryParse(([string]$marker.dataInode), [ref]$markerInode) -or
        -not [long]::TryParse($ExpectedDataInode, [ref]$expectedInodeValue) -or
        $markerInode -ne $expectedInodeValue) {
        throw "Benchmark data inode changed across APK update: setup=$ExpectedDataInode marker=$($marker.dataInode)"
    }
    Write-SafeLog "Benchmark snapshot ready: activeClips=$($marker.activeClips) activeMediaAssets=$($marker.activeMediaAssets) taggedMediaClips=$($marker.taggedMediaClips) localMediaAssets=$($marker.localMediaAssets) persistentPreviews=$($marker.persistentPreviews) databasePath=$($marker.databasePath) dataInode=$($marker.dataInode)"
}

function Pull-MediaGridBenchmarkMetrics {
    $remoteRoot = "$($script:removableStorageRoot)/Android/data/$($script:benchmarkPackage)/files/media-grid-metrics"
    $localRoot = Join-Path $script:repoRoot "build\reports\media-grid-benchmark\raw"
    $script:metricsRemoteRoot = $remoteRoot
    $script:metricsLocalRoot = $localRoot
    Remove-Item -LiteralPath $localRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $script:repoRoot "build\reports\media-grid-benchmark\results.csv") -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $localRoot | Out-Null
    $asciiPullRoot = Join-Path $env:TEMP "like-list-manager-metrics-$PID"
    Remove-Item -LiteralPath $asciiPullRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $asciiPullRoot | Out-Null
    try {
        Invoke-SafeNativeCommand -FilePath $script:adb -Arguments @("-s", $script:serial, "pull", $remoteRoot, $asciiPullRoot) -TimeoutSeconds $timeouts.Install -WorkingDirectory $script:repoRoot
        Copy-Item -Path (Join-Path $asciiPullRoot "*") -Destination $localRoot -Recurse -Force
    } finally {
        Remove-Item -LiteralPath $asciiPullRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
    $files = @(Get-ChildItem -LiteralPath $localRoot -Filter "*.json" -File -Recurse -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) { throw "Benchmark target exported no media-grid metric JSON files." }
}

function Install-AndStartDetachedMacrobenchmark {
    $testPackage = "$($script:macrobenchmarkHostPackage).test"
    $runner = "$testPackage/com.lyco256.llm.macrobenchmark.PersistentBenchmarkRunner"
    $runRoot = "$($script:removableStorageRoot)/Android/data/$($script:macrobenchmarkHostPackage)/files/media-grid-run"
    $remoteLauncher = "/data/local/tmp/llm-media-grid-benchmark-launch.sh"
    $remoteOutput = "/data/local/tmp/llm-media-grid-benchmark-output.txt"
    $localLauncher = Join-Path $env:TEMP "llm-media-grid-benchmark-launch-$PID.sh"
    $script:benchmarkRunRoot = $runRoot
    Invoke-SafeNativeCommand -FilePath $script:adb -Arguments @("-s", $script:serial, "install", "-r", $script:macrobenchmarkHostApk) -TimeoutSeconds $timeouts.Install -WorkingDirectory $script:repoRoot
    Invoke-SafeNativeCommand -FilePath $script:adb -Arguments @("-s", $script:serial, "install", "-r", "-t", $script:macrobenchmarkTestApk) -TimeoutSeconds $timeouts.Install -WorkingDirectory $script:repoRoot
    Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "rm", "-rf", $runRoot) | Out-Null
    $launcherText = @(
        "#!/system/bin/sh",
        "toybox nohup am instrument -w -e androidx.benchmark.suppressErrors NOT-SELF-INSTRUMENTING '$runner' > '$remoteOutput' 2>&1 < /dev/null &"
    ) -join "`n"
    [IO.File]::WriteAllText($localLauncher, $launcherText, [Text.Encoding]::ASCII)
    try {
        Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "rm", "-f", $remoteOutput, $remoteLauncher) | Out-Null
        Invoke-SafeNativeCommand -FilePath $script:adb -Arguments @("-s", $script:serial, "push", $localLauncher, $remoteLauncher) -TimeoutSeconds $timeouts.Install -WorkingDirectory $script:repoRoot
        Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "chmod", "700", $remoteLauncher) | Out-Null
        Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", $remoteLauncher) | Out-Null
        Start-Sleep -Seconds 3
        Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "rm", "-f", $remoteLauncher) | Out-Null
    } finally {
        Remove-Item -LiteralPath $localLauncher -Force -ErrorAction SilentlyContinue
    }
    Write-SafeLog "Detached macrobenchmark started on-device via nohup. ADB may disconnect without stopping instrumentation."
}

function Wait-DetachedMacrobenchmarkCompletion {
    $testPackage = "$($script:macrobenchmarkHostPackage).test"
    if (-not $script:benchmarkRunRoot) {
        $script:benchmarkRunRoot = "$($script:removableStorageRoot)/Android/data/$($script:macrobenchmarkHostPackage)/files/media-grid-run"
    }
    $completion = "$($script:benchmarkRunRoot)/completion.json"
    $running = "$($script:benchmarkRunRoot)/running.json"
    $remoteOutput = "/data/local/tmp/llm-media-grid-benchmark-output.txt"
    $waitStarted = [DateTime]::UtcNow
    $lastRunnerOutputCheck = [DateTime]::MinValue
    $nextDeviceProbe = [DateTime]::UtcNow
    $runningConfirmed = $false
    $deadline = [DateTime]::UtcNow.AddSeconds($timeouts.Macrobenchmark)
    $lastConnectionNotice = [DateTime]::MinValue
    do {
        if ([DateTime]::UtcNow -lt $nextDeviceProbe) {
            Start-Sleep -Seconds 60
            continue
        }
        $connected = $false
        try {
            $state = @(& $script:adb -s $script:serial get-state 2>$null)
            $connected = $LASTEXITCODE -eq 0 -and (($state -join "").Trim() -eq "device")
        } catch { }
        if (-not $connected) {
            try {
                $script:serial = Resolve-AllowedDevice -Adb $script:adb -HardwareSerial $script:hardwareSerial -TimeoutSeconds 15
                $env:ANDROID_SERIAL = $script:serial
                $connected = $true
                Write-SafeLog "ADB reconnected while the device-side benchmark continued."
            } catch {
                if (([DateTime]::UtcNow - $lastConnectionNotice).TotalSeconds -ge 60) {
                    Write-SafeLog "ADB is unavailable; benchmark continues on-device and results remain recoverable."
                    $lastConnectionNotice = [DateTime]::UtcNow
                }
            }
        }
        if ($connected) {
            & $script:adb -s $script:serial shell test -f $completion 2>$null
            if ($LASTEXITCODE -eq 0) {
                $result = @(Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "cat", $completion))
                $text = ($result -join "`n").Trim()
                try { $json = $text | ConvertFrom-Json } catch { $json = $null }
                if ($json -and $json.state -eq "complete") {
                    Write-SafeLog "Detached macrobenchmark completion marker: $text"
                    if ([int]$json.resultCode -ne -1) {
                        throw "Detached macrobenchmark reported failure: $text"
                    }
                    return
                }
            }
            & $script:adb -s $script:serial shell test -f $running 2>$null
            $hasRunningMarker = $LASTEXITCODE -eq 0
            if ($hasRunningMarker) { $runningConfirmed = $true }
            & $script:adb -s $script:serial shell test -f $remoteOutput 2>$null
            $hasRunnerOutput = $LASTEXITCODE -eq 0
            $shouldCheckRunnerOutput = $hasRunnerOutput -and
                ((-not $hasRunningMarker -and ([DateTime]::UtcNow - $waitStarted).TotalSeconds -ge 10) -or
                 ([DateTime]::UtcNow - $lastRunnerOutputCheck).TotalMinutes -ge 5)
            if ($shouldCheckRunnerOutput) {
                $runnerOutput = ((Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "tail", "-n", "80", $remoteOutput)) -join "`n").Trim()
                $lastRunnerOutputCheck = [DateTime]::UtcNow
                if ($runnerOutput -match "Process crashed|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE:\s*-2|shortMsg=") {
                    throw "Device-side macrobenchmark failed before completion: $runnerOutput"
                }
                if (-not $hasRunningMarker -and $runnerOutput -match "INSTRUMENTATION_CODE:") {
                    throw "Device-side macrobenchmark ended without publishing a completion marker: $runnerOutput"
                }
            }
            if (-not $hasRunningMarker -and ([DateTime]::UtcNow - $waitStarted).TotalSeconds -ge 30) {
                throw "Device-side macrobenchmark did not publish its running marker within 30 seconds."
            }
        }
        $nextDeviceProbe = if ($runningConfirmed) { [DateTime]::UtcNow.AddMinutes(5) } else { [DateTime]::UtcNow.AddSeconds(5) }
        Start-Sleep -Seconds $(if ($runningConfirmed) { 60 } else { 5 })
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for the detached macrobenchmark. Device-side metrics and completion files were preserved for later recovery."
}

function Test-DeviceBenchmarkRunStateExists {
    $script:benchmarkRunRoot = "$($script:removableStorageRoot)/Android/data/$($script:macrobenchmarkHostPackage)/files/media-grid-run"
    & $script:adb -s $script:serial shell test -f "$($script:benchmarkRunRoot)/completion.json" 2>$null
    if ($LASTEXITCODE -eq 0) { return $true }
    & $script:adb -s $script:serial shell test -f "$($script:benchmarkRunRoot)/running.json" 2>$null
    if ($LASTEXITCODE -eq 0) {
        $pids = @(& $script:adb -s $script:serial shell pidof $script:macrobenchmarkHostPackage 2>$null)
        if (-not [string]::IsNullOrWhiteSpace(($pids -join "").Trim())) { return $true }
        Write-SafeLog "Ignoring stale running marker because the macrobenchmark host process is not active."
    }
    return $false
}

function Get-DetachedBenchmarkDiagnostics {
    $testPackage = "$($script:macrobenchmarkHostPackage).test"
    $runRoot = "$($script:removableStorageRoot)/Android/data/$($script:macrobenchmarkHostPackage)/files/media-grid-run"
    foreach ($path in @("$runRoot/running.json", "$runRoot/completion.json")) {
        & $script:adb -s $script:serial shell test -f $path 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-SafeLog "Device diagnostic file: $path"
            Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "cat", $path) | Out-Null
        } else {
            Write-SafeLog "Device diagnostic file missing: $path"
        }
    }
    $remoteOutput = "/data/local/tmp/llm-media-grid-benchmark-output.txt"
    & $script:adb -s $script:serial shell test -f $remoteOutput 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-SafeLog "Device diagnostic file tail: $remoteOutput"
        Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "tail", "-n", "80", $remoteOutput) | Out-Null
    } else {
        Write-SafeLog "Device diagnostic file missing: $remoteOutput"
    }
    Write-SafeLog "Active instrumentation diagnostics:"
    Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "dumpsys", "activity", "instrumentation") | Out-Null
    foreach ($package in @($script:macrobenchmarkHostPackage, $testPackage, $script:benchmarkPackage)) {
        $pids = @(& $script:adb -s $script:serial shell pidof $package 2>$null)
        Write-SafeLog "pidof $package = $(($pids -join ' ').Trim())"
    }
    Write-SafeLog "Storage volumes:"
    Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "sm", "list-volumes", "all") | Out-Null
    Write-SafeLog "Storage capacity:"
    Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "df", "-h") | Out-Null
}

function Write-MediaGridBenchmarkResultsCsv {
    $reportDirectory = Join-Path $script:repoRoot "build\reports\media-grid-benchmark"
    $resultCsv = Join-Path $reportDirectory "results.csv"
    $rawRoot = Join-Path $reportDirectory "raw"
    $files = @(Get-ChildItem -LiteralPath $rawRoot -Filter "*.json" -File -Recurse -ErrorAction SilentlyContinue)
    $records = foreach ($file in $files) {
        try { Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json } catch { Write-SafeLog "Ignoring invalid benchmark metric file: $($file.Name)" }
    }
    $rows = foreach ($group in @($records | Where-Object { $_.mode -and $_.scenario } | Group-Object mode, scenario)) {
        $items = @($group.Group)
        $first = $items[0]
        $average = {
            param([string]$Property)
            $values = @($items | ForEach-Object { $value = $_.frame.$Property; if ($null -ne $value) { [double]$value } })
            if ($values.Count -eq 0) { return 0 }
            return [math]::Round((($values | Measure-Object -Average).Average), 2)
        }
        $traceAverage = {
            param([string]$Name)
            $values = @($items | ForEach-Object { $value = $_.traceMs.$Name; if ($null -ne $value) { [double]$value } })
            if ($values.Count -eq 0) { return 0 }
            return [math]::Round((($values | Measure-Object -Average).Average), 2)
        }
        $traceDefinitions = [ordered]@{
            viewportCapture = "MediaGridViewportCapture"
            viewportPublish = "MediaGridViewportPublish"
            priority = "MediaGridPrioritySelect"
            cacheLookup = "MediaGridCacheLookup"
            sourceRead = "MediaGridSourceRead"
            decode = "MediaGridSourceDecode"
            resize = "MediaGridThumbnailResize"
            encode = "MediaGridThumbnailEncode"
            write = "MediaGridThumbnailWrite"
            readyPublish = "MediaGridReadyPublish"
            uiApply = "MediaGridCellImageApply"
            morphPlan = "MediaGridMorphPlan"
            morphDraw = "MediaGridMorphFirstDraw"
            morphHandoff = "MediaGridMorphHandoff"
        }
        $traceSummary = ($traceDefinitions.GetEnumerator() | ForEach-Object { "$($_.Key)=$(& $traceAverage $_.Value)" }) -join ";"
        $counterNames = @("viewportCapture", "viewportPublish", "prioritySelect", "cacheLookup", "sourceRead", "sourceDecode", "resize", "encode", "write", "readyPublish", "cellImageApply", "renderModel", "morphPlan", "morphFirstDraw", "morphHandoff", "network")
        $counterSummary = ($counterNames | ForEach-Object {
                $name = $_
                $total = @($items | ForEach-Object { $value = $_.counters.$name; if ($null -ne $value) { [long]$value } } | Measure-Object -Sum).Sum
                "$name=$total"
            }) -join ";"
        [pscustomobject]@{
            Mode = [string]$first.mode
            Scenario = [string]$first.scenario
            P50 = & $average "p50Ms"
            P90 = & $average "p90Ms"
            P95 = & $average "p95Ms"
            P99 = & $average "p99Ms"
            Jank = [math]::Round(((@($items | ForEach-Object { [double]$_.frame.jank } ) | Measure-Object -Average).Average), 2)
            ViewportMs = & $traceAverage "MediaGridViewportCapture"
            ViewportPublishMs = & $traceAverage "MediaGridViewportPublish"
            PriorityMs = & $traceAverage "MediaGridPrioritySelect"
            CacheMs = & $traceAverage "MediaGridCacheLookup"
            SourceReadMs = & $traceAverage "MediaGridSourceRead"
            DecodeMs = & $traceAverage "MediaGridSourceDecode"
            ResizeMs = & $traceAverage "MediaGridThumbnailResize"
            EncodeMs = & $traceAverage "MediaGridThumbnailEncode"
            WriteMs = & $traceAverage "MediaGridThumbnailWrite"
            ReadyMs = & $traceAverage "MediaGridReadyPublish"
            UiApplyMs = & $traceAverage "MediaGridCellImageApply"
            MorphPlanMs = & $traceAverage "MediaGridMorphPlan"
            MorphDrawMs = & $traceAverage "MediaGridMorphFirstDraw"
            MorphHandoffMs = & $traceAverage "MediaGridMorphHandoff"
            TraceSummary = $traceSummary
            CounterSummary = $counterSummary
        }
    }
    if (@($rows).Count -gt 0) { $rows | Export-Csv -LiteralPath $resultCsv -NoTypeInformation -Encoding UTF8 }
    else { Remove-Item -LiteralPath $resultCsv -Force -ErrorAction SilentlyContinue }
}

function Remove-LocalSnapshotArtifacts {
    foreach ($path in @($script:snapshotRoot, $script:snapshotArchive)) {
        if ($path) { Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue }
    }
}

function Clear-DeviceBenchmarkArtifacts {
    $benchmarkFilesRoot = "/sdcard/Android/data/$($script:benchmarkPackage)/files"
    foreach ($package in @($script:benchmarkPackage, $script:macrobenchmarkHostPackage, "$($script:macrobenchmarkHostPackage).test")) {
        Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "am", "force-stop", $package) | Out-Null
    }
    $clearResult = (Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "pm", "clear", $script:benchmarkPackage)) -join "`n"
    if (-not $clearResult.Contains("Success")) {
        throw "Benchmark target clear did not report success: $clearResult"
    }
    foreach ($package in @($script:macrobenchmarkHostPackage, "$($script:macrobenchmarkHostPackage).test")) {
        $hostClear = (Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "pm", "clear", $package)) -join "`n"
        if (-not $hostClear.Contains("Success")) { throw "Benchmark host clear did not report success for $package`: $hostClear" }
    }
    $storageRoots = @("/sdcard")
    if ($script:removableStorageRoot) { $storageRoots += $script:removableStorageRoot }
    foreach ($root in @(
            "$benchmarkFilesRoot/media-grid-metrics",
            "$benchmarkFilesRoot/media-grid-validation",
            "/sdcard/Android/data/$($script:macrobenchmarkHostPackage)/files/media-grid-run",
            "/sdcard/Android/data/$($script:macrobenchmarkHostPackage).test/files/media-grid-run"
        )) {
        Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "rm", "-rf", $root) | Out-Null
    }
    foreach ($storageRoot in ($storageRoots | Select-Object -Unique)) {
        foreach ($package in @($script:benchmarkPackage, $script:macrobenchmarkHostPackage, "$($script:macrobenchmarkHostPackage).test")) {
            foreach ($root in @("$storageRoot/Android/data/$package", "$storageRoot/Android/media/$package")) {
                Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "rm", "-rf", $root) | Out-Null
            }
        }
    }
    Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "rm", "-f", "/data/local/tmp/llm-media-grid-benchmark-launch.sh", "/data/local/tmp/llm-media-grid-benchmark-output.txt") | Out-Null
}

function Assert-NoDeviceBenchmarkArtifacts {
    foreach ($path in @(
            "$($script:removableStorageRoot)/Android/data/$($script:benchmarkPackage)/files/media-grid-metrics",
            "$($script:removableStorageRoot)/Android/data/$($script:macrobenchmarkHostPackage)/files/media-grid-run"
        )) {
        & $script:adb -s $script:serial shell test -d $path 2>$null
        $testExitCode = $LASTEXITCODE
        if ($testExitCode -eq 1) { continue }
        if ($testExitCode -ne 0) { throw "Could not inspect device benchmark artifact directory: $path" }
        $output = @(Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "ls", "-A", $path))
        if (@($output | Where-Object { -not [string]::IsNullOrWhiteSpace($_.ToString()) }).Count -gt 0) {
            throw "Device benchmark artifacts are still present at $path. Run -RecoverMetricsOnly, then explicitly run -CleanupOnly before starting another benchmark."
        }
    }
}

function Write-MediaGridBenchmarkSummary {
    $reportDirectory = Join-Path $script:repoRoot "build\reports\media-grid-benchmark"
    $reportPath = Join-Path $reportDirectory "latest-summary.md"
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    $rows = @()
    $resultCsv = Join-Path $reportDirectory "results.csv"
    $results = if (Test-Path -LiteralPath $resultCsv -PathType Leaf) { @(Import-Csv -LiteralPath $resultCsv) } else { @() }
    $aggregate = @{}
    foreach ($mode in @("FRAME_ONLY", "PRIORITY_ONLY", "CACHED_UI", "ENCODER_ONLY", "FULL")) {
        $modeRows = @($results | Where-Object { $_.Mode -eq $mode })
        if ($modeRows.Count -gt 0) {
            $aggregate[$mode] = [pscustomobject]@{
                P95 = [math]::Round((($modeRows | ForEach-Object { [double]$_.P95 } | Measure-Object -Average).Average), 2)
                P99 = [math]::Round((($modeRows | ForEach-Object { [double]$_.P99 } | Measure-Object -Average).Average), 2)
                Trace = [math]::Round((($modeRows | ForEach-Object { [double]$_.ViewportMs + [double]$_.ViewportPublishMs + [double]$_.PriorityMs + [double]$_.CacheMs + [double]$_.SourceReadMs + [double]$_.DecodeMs + [double]$_.ResizeMs + [double]$_.EncodeMs + [double]$_.WriteMs + [double]$_.ReadyMs + [double]$_.UiApplyMs + [double]$_.MorphPlanMs + [double]$_.MorphDrawMs + [double]$_.MorphHandoffMs } | Measure-Object -Average).Average), 2)
                Encode = [math]::Round((($modeRows | ForEach-Object { [double]$_.SourceReadMs + [double]$_.DecodeMs + [double]$_.ResizeMs + [double]$_.EncodeMs + [double]$_.WriteMs } | Measure-Object -Average).Average), 2)
                Ui = [math]::Round((($modeRows | ForEach-Object { [double]$_.UiApplyMs } | Measure-Object -Average).Average), 2)
            }
        }
    }
    $differenceRows = foreach ($comparison in @(
            @{ Label = "PRIORITY_ONLY - FRAME_ONLY"; Mode = "PRIORITY_ONLY"; Base = "FRAME_ONLY" },
            @{ Label = "CACHED_UI - PRIORITY_ONLY"; Mode = "CACHED_UI"; Base = "PRIORITY_ONLY" },
            @{ Label = "ENCODER_ONLY - PRIORITY_ONLY"; Mode = "ENCODER_ONLY"; Base = "PRIORITY_ONLY" },
            @{ Label = "FULL - PRIORITY_ONLY"; Mode = "FULL"; Base = "PRIORITY_ONLY" })) {
        $left = $aggregate[$comparison.Mode]
        $right = $aggregate[$comparison.Base]
        if ($left -and $right) {
            $interpretation = if ($comparison.Mode -eq "CACHED_UI") { "UI/cache path delta" } elseif ($comparison.Mode -eq "ENCODER_ONLY") { "decode/resize/encode/write path delta" } elseif ($comparison.Mode -eq "FULL") { "combined contention delta" } else { "priority-selection delta" }
            "| $($comparison.Label) | $([math]::Round($left.P95 - $right.P95, 2)) | $([math]::Round($left.P99 - $right.P99, 2)) | $interpretation |"
        } else {
            "| $($comparison.Label) | N/A | N/A | Requires exported metric rows |"
        }
    }
    $classification = "indeterminate until metric export"
    if ($aggregate["FRAME_ONLY"] -and $aggregate["PRIORITY_ONLY"] -and ($aggregate["PRIORITY_ONLY"].P95 - $aggregate["FRAME_ONLY"].P95) -ge 5) { $classification = "priority selection" }
    elseif ($aggregate["PRIORITY_ONLY"] -and $aggregate["CACHED_UI"] -and (($aggregate["CACHED_UI"].P95 - $aggregate["PRIORITY_ONLY"].P95) -ge 5 -or $aggregate["CACHED_UI"].Ui -ge 5)) { $classification = "UI image reflection/cache" }
    elseif ($aggregate["PRIORITY_ONLY"] -and $aggregate["ENCODER_ONLY"] -and (($aggregate["ENCODER_ONLY"].P95 - $aggregate["PRIORITY_ONLY"].P95) -ge 5 -or $aggregate["ENCODER_ONLY"].Encode -ge 5)) { $classification = "encoding" }
    elseif ($aggregate["PRIORITY_ONLY"] -and $aggregate["FULL"] -and (($aggregate["FULL"].P95 - $aggregate["PRIORITY_ONLY"].P95) -ge 5)) { $classification = "combined contention" }
    foreach ($mode in @("FRAME_ONLY", "PRIORITY_ONLY", "CACHED_UI", "ENCODER_ONLY", "FULL")) {
        foreach ($scenario in @("fast_round_trip", "slow_drag", "settle_after_scroll", "single_fling", "pinch_4_5_4", "pinch_8_9_8", "pinch_return")) {
            $row = $results | Where-Object { $_.Mode -eq $mode -and $_.Scenario -eq $scenario } | Select-Object -First 1
            if ($row) {
                $rows += "| $mode | $scenario | $($row.P50) | $($row.P90) | $($row.P95) | $($row.P99) | $($row.Jank) | $($row.ViewportMs) | $($row.PriorityMs) | $($row.DecodeMs) | $($row.EncodeMs) | $($row.WriteMs) | $($row.UiApplyMs) | $($row.TraceSummary) | $($row.CounterSummary) |"
            } else {
                $rows += "| $mode | $scenario | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | benchmark metric export not available | benchmark metric export not available |"
            }
        }
    }
    $content = @(
        "# Media grid benchmark summary",
        "",
        "Generated by run-safe-macrobenchmark-check.cmd. The benchmark target is the only import destination; production package/data fingerprints are checked before and after the run.",
        "",
        "| Mode | Scenario | P50 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Jank | Viewport (ms) | Priority (ms) | Decode (ms) | Encode (ms) | Write (ms) | UI apply (ms) | Trace details (ms) | Counters |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|"
    ) + $rows + @(
        "",
        "## Differences",
        "",
        "| Comparison | P95 delta (ms) | P99 delta (ms) | Interpretation |",
        "|---|---:|---:|---|",
        $differenceRows,
        "| FULL vs individual processing paths | N/A | N/A | See the mode rows and trace-time columns above |",
        "",
        "## Classification",
        "",
        <#
        "判定不能: 数値が存在する行だけを使い、P95/P99差分とTrace時間の最大寄与を比較する。閾値はP95差分5ms以上または処理時間比20%以上を候補とし、これ未満は判定不能とする。",
        #>
        "Classification: $classification. Candidate threshold: P95/P99 delta >= 5 ms or processing-time share >= 20%.",
        "",
        "Snapshot original selection is capped at 256 files and grouped by extension/name order. Network access is forbidden in every benchmark mode; a request is a failure, not a fallback."
    )
    Set-Content -LiteralPath $reportPath -Value $content -Encoding UTF8
}

Start-SafeScript -Name "run-safe-macrobenchmark-check" -RepoRoot $repoRoot
Set-Location $repoRoot

$serial = ""
$hardwareSerial = ""
$productionPackage = ""
$benchmarkPackage = ""
$macrobenchmarkHostPackage = ""
$adb = ""
$aapt = ""
$benchmarkApk = ""
$benchmarkSetupApk = ""
$macrobenchmarkHostApk = ""
$macrobenchmarkTestApk = ""
$benchmarkSetupDataInode = ""
$benchmarkSetupMetadata = ""
$benchmarkFinalMetadata = ""
$productionBefore = ""
$productionDataBefore = ""
$snapshotRoot = ""
$snapshotArchive = ""
$metricsRemoteRoot = ""
$metricsLocalRoot = ""
$benchmarkRunRoot = ""
$removableStorageRoot = ""
$productionDbRoot = ""
$productionImagesRoot = ""
$productionCacheRoot = ""
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

        $script:hardwareSerial = $properties["testDeviceSerial"]
        if ([string]::IsNullOrWhiteSpace($script:hardwareSerial)) { throw "testDeviceSerial is required in $configPath" }
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
        $script:serial = Resolve-AllowedDevice -Adb $script:adb -HardwareSerial $script:hardwareSerial
        Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
        $env:ANDROID_SERIAL = $script:serial
        $script:removableStorageRoot = Resolve-RemovableStorageRoot -Required:(-not $CleanupOnly)

        if (Test-Path -LiteralPath "C:\Program Files\Android\Android Studio\jbr") {
            $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
            Write-SafeLog "JAVA_HOME=$env:JAVA_HOME"
        }

        $script:productionBefore = Get-PackageMetadata -Adb $script:adb -Serial $script:serial -PackageName $script:productionPackage
    }

    if ($CleanupOnly) {
        Invoke-SafePhase -Name "CleanupOnly" -Action {
            Clear-DeviceBenchmarkArtifacts
            foreach ($path in @(
                    (Join-Path $repoRoot "build\media-grid-benchmark-snapshot"),
                    (Join-Path $repoRoot "build\media-grid-benchmark-snapshot.zip")
                )) {
                Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
        Complete-SafeScript
        Write-Host "Success"
        exit 0
    }

    if ($DiagnosticsOnly) {
        Invoke-SafePhase -Name "DiagnosticsOnly" -Action {
            Get-DetachedBenchmarkDiagnostics
        }
        Complete-SafeScript
        Write-Host "Success"
        exit 0
    }

    if ($RecoverMetricsOnly) {
        try {
            Invoke-SafePhase -Name "RecoverMetrics" -Action {
                if (Test-DeviceBenchmarkRunStateExists) {
                    Wait-DetachedMacrobenchmarkCompletion
                }
                Pull-MediaGridBenchmarkMetrics
                Write-MediaGridBenchmarkResultsCsv
            }
        } catch {
            Capture-BenchmarkFailureLog
            throw
        }
        Write-MediaGridBenchmarkSummary
        Complete-SafeScript
        Write-Host "Success"
        exit 0
    }

    Invoke-SafePhase -Name "ArtifactPrecheck" -Action {
        Assert-NoDeviceBenchmarkArtifacts
    }

    try {
        Invoke-SafePhase -Name "Build" -Action {
            # Remove only local Gradle outputs so the benchmark APK and its
            # source-set merge are rebuilt from the tracked sources.
            Invoke-SafeNativeCommand -FilePath ".\gradlew.bat" -Arguments @(":app:clean", ":macrobenchmark:clean", "--console=plain", "--no-daemon") -TimeoutSeconds $timeouts.Build -WorkingDirectory $repoRoot
            Invoke-SafeNativeCommand -FilePath ".\gradlew.bat" -Arguments @(":app:assembleBenchmark", ":app:assembleBenchmarkSetup", ":app:verifyTestEnvironmentIsolation", ":macrobenchmark:assembleBenchmark", ":macrobenchmark:assembleBenchmarkAndroidTest", "--console=plain", "--no-daemon") -TimeoutSeconds $timeouts.Build -WorkingDirectory $repoRoot
            $script:benchmarkApk = Join-Path $repoRoot "app\build\outputs\apk\benchmark\app-benchmark.apk"
            $script:benchmarkSetupApk = Join-Path $repoRoot "app\build\outputs\apk\benchmarkSetup\app-benchmarkSetup.apk"
            $script:macrobenchmarkHostApk = Join-Path $repoRoot "macrobenchmark\build\outputs\apk\benchmark\macrobenchmark-benchmark.apk"
            $script:macrobenchmarkTestApk = Join-Path $repoRoot "macrobenchmark\build\outputs\apk\androidTest\benchmark\macrobenchmark-benchmark-androidTest.apk"
            Assert-ApkPackage -Aapt $script:aapt -ApkPath $script:benchmarkApk -ExpectedPackage $script:benchmarkPackage -TempName "like-list-manager-benchmark-target.apk"
            Assert-ApkPackage -Aapt $script:aapt -ApkPath $script:benchmarkSetupApk -ExpectedPackage $script:benchmarkPackage -TempName "like-list-manager-benchmark-setup.apk"
            Assert-ApkPackage -Aapt $script:aapt -ApkPath $script:macrobenchmarkHostApk -ExpectedPackage $script:macrobenchmarkHostPackage -TempName "like-list-manager-macrobenchmark-host.apk"
            if (-not (Test-Path -LiteralPath $script:macrobenchmarkTestApk -PathType Leaf)) {
                throw "Macrobenchmark androidTest APK was not found: $script:macrobenchmarkTestApk"
            }
        }

        Invoke-SafePhase -Name "InstallBenchmarkTarget" -Action {
            Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
            $installedPath = @(& $script:adb -s $script:serial shell pm path $script:benchmarkPackage 2>&1)
            if ($LASTEXITCODE -eq 0 -and (($installedPath -join "`n").Contains("package:"))) {
                $preInstallClear = (Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "pm", "clear", $script:benchmarkPackage)) -join "`n"
                if (-not $preInstallClear.Contains("Success")) { throw "Could not clear prior benchmark-only data before setup APK install: $preInstallClear" }
            }
            Invoke-SafeNativeCommand -FilePath $script:adb -Arguments @("-s", $script:serial, "install", "-r", $script:benchmarkSetupApk) -TimeoutSeconds $timeouts.Install -WorkingDirectory $repoRoot
            $clearResult = (Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "pm", "clear", $script:benchmarkPackage)) -join "`n"
            if (-not $clearResult.Contains("Success")) { throw "pm clear did not clear benchmark package only: $clearResult" }
            $validationRoot = "/sdcard/Android/data/$($script:benchmarkPackage)/files/media-grid-validation"
            Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "rm", "-rf", $validationRoot) | Out-Null
            Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "run-as", $script:benchmarkPackage, "mkdir", "-p", "files/benchmark-handoff") | Out-Null
            $script:benchmarkSetupMetadata = Get-PackageMetadata -Adb $script:adb -Serial $script:serial -PackageName $script:benchmarkPackage
            $script:benchmarkSetupDataInode = ((Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "run-as", $script:benchmarkPackage, "stat", "-c", "%i", ".")) -join "").Trim()
            if ($script:benchmarkSetupDataInode -notmatch "^\d+$") { throw "Could not verify benchmark internal data inode: $script:benchmarkSetupDataInode" }
            $targetMetadata = $script:benchmarkSetupMetadata
            $productionUserId = [regex]::Match($script:productionBefore, "uid:(\d+)").Groups[1].Value
            $targetUserId = [regex]::Match($targetMetadata, "uid:(\d+)").Groups[1].Value
            if (-not $productionUserId -or -not $targetUserId -or $productionUserId -eq $targetUserId) {
                throw "Production and benchmark target apps do not have distinct Android UIDs."
            }
        }

        Invoke-SafePhase -Name "ReadOnlySnapshot" -Action {
            New-ProductionSnapshot | Out-Null
            Import-SnapshotToBenchmarkTarget
        }

        Invoke-SafePhase -Name "InstallFinalBenchmarkTarget" -Action {
            Invoke-SafeNativeCommand -FilePath $script:adb -Arguments @("-s", $script:serial, "install", "-r", $script:benchmarkApk) -TimeoutSeconds $timeouts.Install -WorkingDirectory $repoRoot
            $script:benchmarkFinalMetadata = Get-PackageMetadata -Adb $script:adb -Serial $script:serial -PackageName $script:benchmarkPackage
            $targetMetadata = $script:benchmarkFinalMetadata
            $productionUserId = [regex]::Match($script:productionBefore, "uid:(\d+)").Groups[1].Value
            $targetUserId = [regex]::Match($targetMetadata, "uid:(\d+)").Groups[1].Value
            $setupUserId = [regex]::Match($script:benchmarkSetupMetadata, "uid:(\d+)").Groups[1].Value
            if (-not $productionUserId -or -not $targetUserId -or $productionUserId -eq $targetUserId -or $setupUserId -ne $targetUserId) {
                throw "Benchmark UID changed or collides with production: setup=$setupUserId final=$targetUserId production=$productionUserId"
            }
        }

        Invoke-SafePhase -Name "BenchmarkSnapshotSetup" -Action {
            Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
            Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "am", "force-stop", $script:benchmarkPackage) | Out-Null
            $startResult = (Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "am", "start", "-W", "-n", "$($script:benchmarkPackage)/com.lyco256.llm.BenchmarkSnapshotSetupActivity")) -join "`n"
            Write-SafeLog "Benchmark setup Activity launch result (diagnostic only): $startResult"
            Assert-BenchmarkSnapshotReady -PackageMetadata $script:benchmarkFinalMetadata -ExpectedDataInode $script:benchmarkSetupDataInode
        }

        Invoke-SafePhase -Name "Macrobenchmark" -Action {
            Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
            Install-AndStartDetachedMacrobenchmark
            Wait-DetachedMacrobenchmarkCompletion
            Pull-MediaGridBenchmarkMetrics
            Write-MediaGridBenchmarkResultsCsv
        }
    } catch {
        $script:runError = $_.Exception
        Capture-BenchmarkFailureLog
    }

    Write-SafeLog ""
    Write-SafeLog "## PostCheck"
    try {
        Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
        $productionAfter = Get-PackageMetadata -Adb $script:adb -Serial $script:serial -PackageName $script:productionPackage
        if ($script:productionBefore -ne $productionAfter) {
            throw [SafePhaseException]::new("Macrobenchmark", "Production package metadata changed during macrobenchmark.", "")
        }
        if ($script:productionDataBefore) {
            $productionDataAfter = Get-ProductionDataFingerprint
            if ($script:productionDataBefore -ne $productionDataAfter) {
                throw [SafePhaseException]::new("Macrobenchmark", "Production database or media hash changed during macrobenchmark.", "")
            }
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
        $script:serial = Resolve-AllowedDevice -Adb $script:adb -HardwareSerial $script:hardwareSerial
        $env:ANDROID_SERIAL = $script:serial
    } catch {
        if ($_.Exception -is [SafePhaseException]) {
            throw
        }
        throw [SafePhaseException]::new("Macrobenchmark", $_.Exception.Message, "")
    }

    Write-MediaGridBenchmarkSummary

    Complete-SafeScript
    Write-Host "Success"
    exit 0
} catch {
    Write-MediaGridBenchmarkSummary
    Stop-SafeScriptWithFailure -Exception $_.Exception
} finally {
    Remove-LocalSnapshotArtifacts
}
