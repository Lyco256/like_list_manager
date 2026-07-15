param(
    [ValidateSet("usb", "wireless")]
    [string]$DebugMethod = "usb",
    [string]$DeviceConfig = (Join-Path $PSScriptRoot "..\test-device.local.properties"),
    [switch]$CleanupOnly
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
            if ($text -match 'com\.lyco256\.llm\.test\.benchmark|AndroidRuntime|FATAL EXCEPTION|Benchmark target|media-grid') {
                Write-SafeLog $text
            }
        }
    } catch {
        Write-SafeLog "Filtered benchmark log capture failed: $($_.Exception.Message)"
    }
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

function Resolve-WirelessAllowedDevice {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$HardwareSerial,
        [int]$TimeoutSeconds = 90
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $services = @(Invoke-LoggedAdb -Adb $Adb -Arguments @("mdns", "services"))
        foreach ($line in $services) {
            if ($line -match "^\s*(adb-[^\s]+)\s+_adb-tls-connect\._tcp\s+([^\s]+)\s*$" -and $line -match [regex]::Escape($HardwareSerial)) {
                $serviceEndpoint = "$($Matches[1])._adb-tls-connect._tcp"
                Invoke-LoggedAdb -Adb $Adb -Arguments @("connect", $serviceEndpoint) | Out-Null
            }
        }
        $devices = @(Invoke-LoggedAdb -Adb $Adb -Arguments @("devices", "-l"))
        $candidates = @($devices | Where-Object { $_ -match "^\S+\s+device\s" })
        $matches = @()
        foreach ($candidate in $candidates) {
            $endpoint = ($candidate -split "\s+")[0]
            $reportedSerial = ((Invoke-LoggedAdb -Adb $Adb -Arguments @("-s", $endpoint, "shell", "getprop", "ro.serialno")) -join "").Trim()
            if ($reportedSerial -eq $HardwareSerial) { $matches += $endpoint }
        }
        if ($matches.Count -eq 1) { return $matches[0] }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Wireless device with hardware serial $HardwareSerial did not resolve to exactly one connected endpoint within $TimeoutSeconds seconds."
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
    $script:productionCacheRoot = "cache/media_grid_thumbnails"
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

function New-ProductionSnapshot {
    $snapshotRoot = Join-Path $script:repoRoot "build\media-grid-benchmark-snapshot"
    $archive = Join-Path $script:repoRoot "build\media-grid-benchmark-snapshot.zip"
    $script:snapshotRoot = $snapshotRoot
    $script:snapshotArchive = $archive
    Remove-Item -LiteralPath $snapshotRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Join-Path $snapshotRoot "db"), (Join-Path $snapshotRoot "cache\media_grid_thumbnails"), (Join-Path $snapshotRoot "originals") | Out-Null
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
    $cachePaths = @(Get-RunAsFiles -FindRoot $script:productionCacheRoot | Where-Object { [IO.Path]::GetExtension($_).ToLowerInvariant() -eq ".jpg" })
    foreach ($path in $cachePaths) {
        $destination = Join-Path $snapshotRoot ("cache/media_grid_thumbnails/" + ([IO.Path]::GetFileName($path)))
        Copy-RunAsBinary -RelativePath $path -Destination $destination
    }
    $originals = Select-RepresentativeOriginals -Paths @(Get-RunAsFiles -FindRoot $script:productionImagesRoot)
    foreach ($path in $originals) {
        Copy-RunAsBinary -RelativePath $path -Destination (Join-Path $snapshotRoot ("originals/" + ([IO.Path]::GetFileName($path))))
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($snapshotRoot, $archive)
    return $archive
}

function Import-SnapshotToBenchmarkTarget {
    $remoteRoot = "/sdcard/Android/data/$($script:benchmarkPackage)/files/media-grid-snapshot"
    Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "mkdir", "-p", $remoteRoot) | Out-Null
    Invoke-SafeNativeCommand -FilePath $script:adb -Arguments @("-s", $script:serial, "push", $script:snapshotArchive, "$remoteRoot/media-grid-snapshot.zip") -TimeoutSeconds $timeouts.Install -WorkingDirectory $script:repoRoot
    $script:snapshotRemoteRoot = $remoteRoot
}

function Pull-MediaGridBenchmarkMetrics {
    $remoteRoot = "/sdcard/Android/data/$($script:benchmarkPackage)/files/media-grid-metrics"
    $localRoot = Join-Path $script:repoRoot "build\reports\media-grid-benchmark\raw"
    $script:metricsRemoteRoot = $remoteRoot
    $script:metricsLocalRoot = $localRoot
    Remove-Item -LiteralPath $localRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $script:repoRoot "build\reports\media-grid-benchmark\results.csv") -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $localRoot | Out-Null
    $pull = Invoke-SafeNativeCommand -FilePath $script:adb -Arguments @("-s", $script:serial, "pull", $remoteRoot, $localRoot) -TimeoutSeconds $timeouts.Install -WorkingDirectory $script:repoRoot
    $files = @(Get-ChildItem -LiteralPath $localRoot -Filter "*.json" -File -Recurse -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) { throw "Benchmark target exported no media-grid metric JSON files." }
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

function Remove-SnapshotArtifacts {
    if ($script:snapshotRemoteRoot) {
        try { Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "rm", "-rf", $script:snapshotRemoteRoot) | Out-Null } catch { Write-SafeLog "Snapshot shared cleanup failed: $script:snapshotRemoteRoot" }
    }
    foreach ($path in @($script:snapshotRoot, $script:snapshotArchive)) {
        if ($path) { Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue }
    }
    if ($script:metricsRemoteRoot) {
        try { Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "rm", "-rf", $script:metricsRemoteRoot) | Out-Null } catch { Write-SafeLog "Benchmark metrics shared cleanup failed: $script:metricsRemoteRoot" }
    }
    if ($script:benchmarkPackage -and $script:serial) {
        try { Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "rm", "-rf", "/sdcard/Android/data/$($script:benchmarkPackage)/files/media-grid-validation") | Out-Null } catch { Write-SafeLog "Benchmark validation cleanup failed" }
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
        foreach ($scenario in @("fast_round_trip", "slow_drag", "settle_after_scroll", "pinch_4_5_4", "pinch_8_9_8", "pinch_return")) {
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
$macrobenchmarkHostApk = ""
$macrobenchmarkTestApk = ""
$productionBefore = ""
$productionDataBefore = ""
$snapshotRoot = ""
$snapshotArchive = ""
$snapshotRemoteRoot = ""
$metricsRemoteRoot = ""
$metricsLocalRoot = ""
$productionDbRoot = ""
$productionImagesRoot = ""
$productionCacheRoot = ""
$runError = $null

$metricReportDirectory = Join-Path $repoRoot "build\reports\media-grid-benchmark"
Remove-Item -LiteralPath (Join-Path $metricReportDirectory "raw") -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $metricReportDirectory "results.csv") -Force -ErrorAction SilentlyContinue

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
        if ($DebugMethod -eq "wireless") {
            $script:serial = Resolve-WirelessAllowedDevice -Adb $script:adb -HardwareSerial $script:hardwareSerial
        } else {
            $script:serial = $script:hardwareSerial
            Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
        }
        Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
        $env:ANDROID_SERIAL = $script:serial

        if (Test-Path -LiteralPath "C:\Program Files\Android\Android Studio\jbr") {
            $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
            Write-SafeLog "JAVA_HOME=$env:JAVA_HOME"
        }

        $script:productionBefore = Get-PackageMetadata -Adb $script:adb -Serial $script:serial -PackageName $script:productionPackage
    }

    if ($CleanupOnly) {
        Invoke-SafePhase -Name "CleanupOnly" -Action {
            $remoteRoot = "/sdcard/Android/data/$($script:benchmarkPackage)/files"
            Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "rm", "-rf", "$remoteRoot/media-grid-snapshot") | Out-Null
            Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "rm", "-rf", "$remoteRoot/media-grid-metrics") | Out-Null
            Invoke-LoggedAdb -Adb $script:adb -Arguments @("-s", $script:serial, "shell", "rm", "-rf", "$remoteRoot/media-grid-validation") | Out-Null
            foreach ($path in @(
                    (Join-Path $repoRoot "build\media-grid-benchmark-snapshot"),
                    (Join-Path $repoRoot "build\media-grid-benchmark-snapshot.zip"),
                    (Join-Path $repoRoot "build\reports\media-grid-benchmark\raw"),
                    (Join-Path $repoRoot "build\reports\media-grid-benchmark\results.csv")
                )) {
                Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
        Complete-SafeScript
        Write-Host "Success"
        exit 0
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

        Invoke-SafePhase -Name "ReadOnlySnapshot" -Action {
            New-ProductionSnapshot | Out-Null
            Import-SnapshotToBenchmarkTarget
        }

        Invoke-SafePhase -Name "Macrobenchmark" -Action {
            Wait-AllowedDevice -Adb $script:adb -Serial $script:serial
            Invoke-SafeNativeCommand -FilePath ".\gradlew.bat" -Arguments @(":macrobenchmark:connectedBenchmarkAndroidTest", "--console=plain", "--no-daemon") -TimeoutSeconds $timeouts.Macrobenchmark -WorkingDirectory $repoRoot
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
    Remove-SnapshotArtifacts
}
