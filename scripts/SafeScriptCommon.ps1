$script:SafeScriptName = ""
$script:SafeLogDirectory = ""
$script:SafeLogPath = ""
$script:SafeLatestLogPath = ""
$script:SafeLastErrorSummary = ""
$script:SafeLastImpactSummary = ""

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
        "^\s*Expected exactly one connected adb device",
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
                    $_ -match "Error in |FAILURES!!!|There (?:was|were) \d+ failure|shortMsg=Process crashed|INSTRUMENTATION_CODE:\s*-1"
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
