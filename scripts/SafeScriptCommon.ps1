$script:SafeScriptName = ""
$script:SafeLogDirectory = ""
$script:SafeLogPath = ""
$script:SafeLatestLogPath = ""
$script:SafeLastErrorSummary = ""
$script:SafeLastImpactSummary = ""
$script:SafeFileHashCache = @{}

class SafePhaseException : System.Exception {
    [string]$Phase
    [string]$Summary
    [string]$ImpactSummary

    SafePhaseException([string]$phase, [string]$summary, [string]$impactSummary) : base($summary) {
        $this.Phase = $phase
        $this.Summary = $summary
        $this.ImpactSummary = $impactSummary
    }
}

function Join-SafeCommandArguments {
    param([string[]]$Arguments)
    $quoted = foreach ($argument in $Arguments) {
        if ($null -eq $argument) {
            continue
        }
        if ($argument -eq "") {
            '""'
        } elseif ($argument -match '[\s"`&|<>^]') {
            '"' + ($argument -replace '"', '\"') + '"'
        } else {
            $argument
        }
    }
    return ($quoted -join " ")
}

function Start-SafeScript {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [int]$KeepLogs = 10
    )

    $script:SafeScriptName = $Name
    $script:SafeLogDirectory = Join-Path $RepoRoot "build\safe-script-logs\$Name"
    New-Item -ItemType Directory -Force -Path $script:SafeLogDirectory | Out-Null

    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $script:SafeLogPath = Join-Path $script:SafeLogDirectory "$timestamp.log"
    $script:SafeLatestLogPath = Join-Path $script:SafeLogDirectory "latest.log"
    "Safe script: $Name" | Set-Content -LiteralPath $script:SafeLogPath -Encoding UTF8
    "Started: $(Get-Date -Format o)" | Add-Content -LiteralPath $script:SafeLogPath -Encoding UTF8

    Get-ChildItem -LiteralPath $script:SafeLogDirectory -Filter "*.log" -File |
        Where-Object { $_.Name -ne "latest.log" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -Skip $KeepLogs |
        Remove-Item -Force
}

function Complete-SafeScript {
    if ($script:SafeLogPath -and (Test-Path -LiteralPath $script:SafeLogPath)) {
        Copy-Item -LiteralPath $script:SafeLogPath -Destination $script:SafeLatestLogPath -Force
    }
}

function Write-SafeLog {
    param([string]$Message = "")
    $Message | Add-Content -LiteralPath $script:SafeLogPath -Encoding UTF8
}

function Get-SafeLogPath {
    return $script:SafeLogPath
}

function Get-SafeCachedFileHash {
    param([Parameter(Mandatory = $true)][System.IO.FileInfo]$File)

    $cacheKey = "$($File.FullName)|$($File.Length)|$($File.LastWriteTimeUtc.Ticks)"
    if (-not $script:SafeFileHashCache.ContainsKey($cacheKey)) {
        $script:SafeFileHashCache[$cacheKey] = (Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    return $script:SafeFileHashCache[$cacheKey]
}

function Get-SafeValidationFingerprint {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string[]]$InputPaths,
        [Parameter(Mandatory = $true)][string]$CacheVersion
    )

    $root = [System.IO.Path]::GetFullPath($RepoRoot).TrimEnd('\', '/')
    $records = New-Object System.Collections.Generic.List[string]
    $records.Add("cacheVersion=$CacheVersion")
    foreach ($inputPath in $InputPaths) {
        $fullPath = if ([System.IO.Path]::IsPathRooted($inputPath)) {
            [System.IO.Path]::GetFullPath($inputPath)
        } else {
            [System.IO.Path]::GetFullPath((Join-Path $root $inputPath))
        }
        if (-not ($fullPath -eq $root -or $fullPath.StartsWith("$root\", [System.StringComparison]::OrdinalIgnoreCase))) {
            throw "Validation input must stay inside the repository: $inputPath"
        }
        if (-not (Test-Path -LiteralPath $fullPath)) {
            $relativeMissing = $fullPath.Substring($root.Length).TrimStart('\', '/').Replace('\', '/')
            $records.Add("missing=$relativeMissing")
            continue
        }

        $item = Get-Item -LiteralPath $fullPath
        $files = if ($item.PSIsContainer) {
            @(Get-ChildItem -LiteralPath $fullPath -File -Recurse | Where-Object {
                    $_.FullName -notmatch '[\\/](build|\.gradle|\.kotlin)[\\/]'
                })
        } else {
            @($item)
        }
        foreach ($file in $files) {
            $relative = $file.FullName.Substring($root.Length).TrimStart('\', '/').Replace('\', '/')
            $hash = Get-SafeCachedFileHash -File $file
            $records.Add("$relative|$($file.Length)|$hash")
        }
    }

    $manifest = ($records | Sort-Object -Unique) -join "`n"
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($manifest)
        return ([System.BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function Test-SafeValidationCache {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Fingerprint
    )

    $statePath = Join-Path $RepoRoot "build\safe-script-state\$Key.json"
    if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) {
        return $false
    }
    try {
        $state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($state.fingerprint -ne $Fingerprint) {
            return $false
        }
        foreach ($output in @($state.outputs)) {
            $outputPath = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $output.path))
            if (-not (Test-Path -LiteralPath $outputPath -PathType Leaf)) {
                return $false
            }
            $hash = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($hash -ne $output.sha256) {
                return $false
            }
        }
        Write-SafeLog "Skipped unchanged successful validation: $Key"
        return $true
    } catch {
        Write-SafeLog "Ignoring unreadable validation state $statePath`: $($_.Exception.Message)"
        return $false
    }
}

function Set-SafeValidationCache {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Fingerprint,
        [string[]]$OutputPaths = @()
    )

    $outputs = foreach ($outputPath in $OutputPaths) {
        $fullPath = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $outputPath))
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            throw "Successful validation did not produce required output: $outputPath"
        }
        [pscustomobject]@{
            path = $outputPath.Replace('\', '/')
            sha256 = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    $stateDirectory = Join-Path $RepoRoot "build\safe-script-state"
    New-Item -ItemType Directory -Force -Path $stateDirectory | Out-Null
    $statePath = Join-Path $stateDirectory "$Key.json"
    [pscustomobject]@{
        fingerprint = $Fingerprint
        outputs = @($outputs)
        completedAt = (Get-Date -Format o)
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $statePath -Encoding UTF8
    Write-SafeLog "Recorded successful validation: $Key"
}

function Get-SafeFileSnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$InputPath,
        [string]$Filter = "*"
    )

    $root = [System.IO.Path]::GetFullPath($RepoRoot).TrimEnd('\', '/')
    $fullPath = [System.IO.Path]::GetFullPath((Join-Path $root $InputPath))
    if (-not ($fullPath -eq $root -or $fullPath.StartsWith("$root\", [System.StringComparison]::OrdinalIgnoreCase))) {
        throw "Snapshot input must stay inside the repository: $InputPath"
    }
    if (-not (Test-Path -LiteralPath $fullPath -PathType Container)) {
        return @()
    }
    return @(Get-ChildItem -LiteralPath $fullPath -File -Recurse -Filter $Filter |
        Sort-Object FullName |
        ForEach-Object {
            [pscustomobject]@{
                path = $_.FullName.Substring($root.Length).TrimStart('\', '/').Replace('\', '/')
                sha256 = Get-SafeCachedFileHash -File $_
            }
        })
}

function Get-SafeChangedSnapshotFiles {
    param(
        [object[]]$PreviousFiles,
        [object[]]$CurrentFiles
    )

    $previousByPath = @{}
    foreach ($file in @($PreviousFiles)) {
        $previousByPath[$file.path] = $file.sha256
    }
    $currentByPath = @{}
    foreach ($file in @($CurrentFiles)) {
        $currentByPath[$file.path] = $file.sha256
    }
    $changed = @($CurrentFiles | Where-Object {
            -not $previousByPath.ContainsKey($_.path) -or $previousByPath[$_.path] -ne $_.sha256
        })
    $removed = @($PreviousFiles | Where-Object { -not $currentByPath.ContainsKey($_.path) })
    return [pscustomobject]@{
        Changed = $changed
        Removed = $removed
    }
}

function Get-SafeUnitTestClasses {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][object[]]$Files
    )

    $classes = @()
    foreach ($file in $Files) {
        $fullPath = Join-Path $RepoRoot $file.path
        $source = Get-Content -LiteralPath $fullPath -Raw -Encoding UTF8
        $packageMatch = [regex]::Match($source, '(?m)^package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$')
        $classMatches = [regex]::Matches($source, '(?m)^(?:internal\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*Test)\b')
        if (-not $packageMatch.Success -or $classMatches.Count -eq 0) {
            return @()
        }
        foreach ($classMatch in $classMatches) {
            $classes += "$($packageMatch.Groups[1].Value).$($classMatch.Groups[1].Value)"
        }
    }
    return @($classes | Sort-Object -Unique)
}

function Invoke-SafeUnitTestValidation {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$Fingerprint,
        [Parameter(Mandatory = $true)][string]$MainFingerprint,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds,
        [switch]$ForceFull
    )

    $cacheKey = "app-unit-test"
    if (-not $ForceFull -and (Test-SafeValidationCache -RepoRoot $RepoRoot -Key $cacheKey -Fingerprint $Fingerprint)) {
        return
    }

    $scopePath = Join-Path $RepoRoot "build\safe-script-state\app-unit-test-scope.json"
    $currentFiles = @(Get-SafeFileSnapshot -RepoRoot $RepoRoot -InputPath "app\src\test" -Filter "*.kt")
    $selectedClasses = @()
    if (-not $ForceFull -and (Test-Path -LiteralPath $scopePath -PathType Leaf)) {
        try {
            $previousScope = Get-Content -LiteralPath $scopePath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($previousScope.version -eq "unit-test-scope-v1" -and $previousScope.mainFingerprint -eq $MainFingerprint) {
                $changes = Get-SafeChangedSnapshotFiles -PreviousFiles @($previousScope.testFiles) -CurrentFiles $currentFiles
                if ($changes.Changed.Count -gt 0 -and $changes.Changed.Count -le 8 -and $changes.Removed.Count -eq 0) {
                    $selectedClasses = @(Get-SafeUnitTestClasses -RepoRoot $RepoRoot -Files $changes.Changed)
                    if ($selectedClasses.Count -ne $changes.Changed.Count) {
                        $selectedClasses = @()
                    }
                }
            }
        } catch {
            Write-SafeLog "Ignoring unreadable unit-test scope state $scopePath`: $($_.Exception.Message)"
            $selectedClasses = @()
        }
    }

    $arguments = @(":app:testDebugUnitTest")
    if ($selectedClasses.Count -gt 0) {
        foreach ($className in $selectedClasses) {
            $arguments += @("--tests", $className)
        }
        Write-SafeLog "Running changed unit test classes only: $($selectedClasses -join ', ')"
    } else {
        Write-SafeLog "Running the complete debug unit test suite."
    }
    $arguments += @("--console=plain", "--no-daemon")
    Invoke-SafeNativeCommand -FilePath ".\gradlew.bat" -Arguments $arguments -TimeoutSeconds $TimeoutSeconds -WorkingDirectory $RepoRoot
    Set-SafeValidationCache -RepoRoot $RepoRoot -Key $cacheKey -Fingerprint $Fingerprint

    $scopeDirectory = Split-Path -Parent $scopePath
    New-Item -ItemType Directory -Force -Path $scopeDirectory | Out-Null
    [pscustomobject]@{
        version = "unit-test-scope-v1"
        mainFingerprint = $MainFingerprint
        testFiles = @($currentFiles)
        lastRunMode = if ($selectedClasses.Count -gt 0) { "partial" } else { "full" }
        selectedClasses = @($selectedClasses)
        completedAt = (Get-Date -Format o)
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $scopePath -Encoding UTF8
}

function Set-SafeImpactSummary {
    param([string]$Message)
    $script:SafeLastImpactSummary = $Message
}

function Clear-SafePhaseState {
    $script:SafeLastErrorSummary = ""
    $script:SafeLastImpactSummary = ""
}

function Get-SafeErrorSummaryFromText {
    param([string[]]$Lines)

    $patterns = @(
        "^\s*e:\s",
        "^\s*error[:\s]",
        "^\s*> Task .* FAILED",
        "^\s*.* > .* FAILED$",
        "^\s*FAILED:",
        "^\s*FAILURES!!!",
        "^\s*Execution failed for task",
        "^\s*Caused by:",
        "^\s*java\.",
        "^\s*org\.",
        "^\s*com\.",
        "^\s*INSTRUMENTATION_CODE:",
        "^\s*lint found",
        "^\s*There were failing tests",
        "^\s*Required package is not installed",
        "^\s*Package .* is not currently installed",
        "^\s*Expected exactly one (?:connected|physical) adb device",
        "^\s*Allowed test device .* did not reconnect",
        "^\s*Refusing installation",
        "^\s*Could not ",
        "^\s*FAILURE:"
    )

    foreach ($pattern in $patterns) {
        $line = $Lines | Where-Object { $_ -match $pattern } | Select-Object -First 1
        if ($line) {
            return $line.Trim()
        }
    }

    $fallback = $Lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1
    if ($fallback) {
        return $fallback.Trim()
    }

    return "See log for details."
}

function Get-SafeErrorSummaryFromLog {
    if (-not (Test-Path -LiteralPath $script:SafeLogPath)) {
        return "See log for details."
    }
    $lines = Get-Content -LiteralPath $script:SafeLogPath -Tail 250 -Encoding UTF8
    return Get-SafeErrorSummaryFromText -Lines $lines
}

function Invoke-SafeNativeCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds,
        [string]$WorkingDirectory = (Get-Location).Path,
        [string]$FailureMessage = ""
    )

    $argumentText = Join-SafeCommandArguments -Arguments $Arguments
    $displayCommand = "$FilePath $argumentText".TrimEnd()
    Write-SafeLog ""
    Write-SafeLog "> $displayCommand"

    $stdoutPath = [System.IO.Path]::GetTempFileName()
    $stderrPath = [System.IO.Path]::GetTempFileName()
    $commandLine = "`"`"$FilePath`" $argumentText > `"$stdoutPath`" 2> `"$stderrPath`"`""

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $env:ComSpec
    $process.StartInfo.Arguments = "/d /c $commandLine"
    $process.StartInfo.WorkingDirectory = $WorkingDirectory
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true

    try {
        if (-not $process.Start()) {
            throw "Could not start command: $displayCommand"
        }

        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try {
                $process.Kill()
            } catch {
                Write-SafeLog "Could not kill timed out process: $($_.Exception.Message)"
            }
            $script:SafeLastErrorSummary = "timeout"
            throw "timeout"
        }
        $process.WaitForExit()

        $lines = @()
        if (Test-Path -LiteralPath $stdoutPath) {
            $lines += @(Get-Content -LiteralPath $stdoutPath -Encoding UTF8)
        }
        if (Test-Path -LiteralPath $stderrPath) {
            $lines += @(Get-Content -LiteralPath $stderrPath -Encoding UTF8)
        }
        foreach ($line in $lines) {
            Write-SafeLog $line
        }

        if ($displayCommand -match "\bam instrument\b") {
            $instrumentationFailure = $lines |
                Where-Object {
                    $_ -match "Error in |FAILURES!!!|There (?:was|were) \d+ failure|shortMsg=Process crashed"
                } |
                Select-Object -First 1
            if ($instrumentationFailure) {
                $script:SafeLastErrorSummary = $instrumentationFailure.Trim()
                throw "instrumentation test failure"
            }
        }

        if ($process.ExitCode -ne 0) {
            if ($FailureMessage) {
                $script:SafeLastErrorSummary = $FailureMessage
            } else {
                $script:SafeLastErrorSummary = Get-SafeErrorSummaryFromText -Lines $lines
            }
            throw "exit code $($process.ExitCode)"
        }
    } finally {
        if ($process) { $process.Dispose() }
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-SafeLoggedScript {
    param([Parameter(Mandatory = $true)][scriptblock]$Script)

    try {
        $result = & $Script *>&1
        foreach ($line in @($result)) {
            Write-SafeLog ($line | Out-String).TrimEnd()
        }
    } catch {
        Write-SafeLog $_.Exception.Message
        $script:SafeLastErrorSummary = $_.Exception.Message
        throw
    }
}

function Invoke-SafePhase {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Action,
        [string]$ImpactSummary = ""
    )

    Clear-SafePhaseState
    Write-Host $Name
    Write-SafeLog ""
    Write-SafeLog "## $Name"
    Write-SafeLog "Started: $(Get-Date -Format o)"

    try {
        & $Action
        Write-SafeLog "Completed: $(Get-Date -Format o)"
    } catch {
        if ($script:SafeLastErrorSummary -eq "timeout" -or $_.Exception.Message -eq "timeout") {
            $summary = "$Name timeout"
        } elseif (-not [string]::IsNullOrWhiteSpace($script:SafeLastErrorSummary)) {
            $summary = $script:SafeLastErrorSummary
        } elseif (-not [string]::IsNullOrWhiteSpace($_.Exception.Message)) {
            $summary = $_.Exception.Message
        } else {
            $summary = Get-SafeErrorSummaryFromLog
        }
        if ([string]::IsNullOrWhiteSpace($summary)) {
            $summary = $_.Exception.Message
        }
        if (-not $ImpactSummary) {
            $ImpactSummary = $script:SafeLastImpactSummary
        }
        throw [SafePhaseException]::new($Name, $summary, $ImpactSummary)
    }
}

function Stop-SafeScriptWithFailure {
    param([Parameter(Mandatory = $true)][System.Exception]$Exception)

    Complete-SafeScript
    if ($Exception -is [SafePhaseException]) {
        Write-Host "Failed: $($Exception.Phase)"
        Write-Host "Error: $($Exception.Summary)"
        if (-not [string]::IsNullOrWhiteSpace($Exception.ImpactSummary)) {
            Write-Host "Impact: $($Exception.ImpactSummary)"
        }
        Write-Host "Log: $(Get-SafeLogPath)"
    } else {
        Write-Host "Failed: Preflight"
        Write-Host "Error: $($Exception.Message)"
        Write-Host "Log: $(Get-SafeLogPath)"
    }
    exit 1
}
