param(
    [Parameter(Mandatory = $true)][string]$SnapshotRoot,
    [Parameter(Mandatory = $true)][string]$ImageRoot
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "SafeScriptCommon.ps1")

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$timeouts = @{
    Preflight = 300
    SnapshotTest = 3600
}

function Get-SnapshotFingerprint {
    param(
        [Parameter(Mandatory = $true)][string]$SnapshotPath,
        [Parameter(Mandatory = $true)][string]$ImagesPath
    )
    $dbPath = Join-Path $SnapshotPath "like_list_manager.db"
    $dbHash = (Get-FileHash -LiteralPath $dbPath -Algorithm SHA256).Hash
    $imageFiles = @(Get-ChildItem -LiteralPath $ImagesPath -File -Recurse)
    $imageBytes = ($imageFiles | Measure-Object -Property Length -Sum).Sum
    if ($null -eq $imageBytes) {
        $imageBytes = 0
    }
    [pscustomobject]@{
        DbHash = $dbHash
        ImageCount = $imageFiles.Count
        ImageBytes = [int64]$imageBytes
    }
}

function Get-SnapshotImpactSummary {
    param(
        [Parameter(Mandatory = $true)]$Before,
        [Parameter(Mandatory = $true)][string]$SnapshotPath,
        [Parameter(Mandatory = $true)][string]$ImagesPath
    )
    try {
        $after = Get-SnapshotFingerprint -SnapshotPath $SnapshotPath -ImagesPath $ImagesPath
        if ($Before.DbHash -eq $after.DbHash -and $Before.ImageCount -eq $after.ImageCount -and $Before.ImageBytes -eq $after.ImageBytes) {
            return "Source snapshot unchanged: DB hash, image count, and image bytes match before/after."
        }
        return "Source snapshot changed or needs manual review: DB hash/count/bytes differ before/after."
    } catch {
        return "Could not re-check source snapshot impact: $($_.Exception.Message)"
    }
}

Start-SafeScript -Name "run-safe-snapshot-check" -RepoRoot $repoRoot
Set-Location $repoRoot

$snapshot = ""
$images = ""
$sourceBefore = $null

try {
    Invoke-SafePhase -Name "Preflight" -Action {
        $script:snapshot = (Resolve-Path -LiteralPath $SnapshotRoot).Path
        $script:images = (Resolve-Path -LiteralPath $ImageRoot).Path
        if (-not (Test-Path -LiteralPath (Join-Path $script:snapshot "like_list_manager.db") -PathType Leaf)) {
            throw "like_list_manager.db was not found under $script:snapshot"
        }
        if (-not (Test-Path -LiteralPath $script:images -PathType Container)) {
            throw "ImageRoot is not a directory: $script:images"
        }
        if (Test-Path -LiteralPath "C:\Program Files\Android\Android Studio\jbr") {
            $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
            Write-SafeLog "JAVA_HOME=$env:JAVA_HOME"
        }
        $script:sourceBefore = Get-SnapshotFingerprint -SnapshotPath $script:snapshot -ImagesPath $script:images
        Write-SafeLog "Source DB hash: $($script:sourceBefore.DbHash)"
        Write-SafeLog "Source image count: $($script:sourceBefore.ImageCount)"
        Write-SafeLog "Source image bytes: $($script:sourceBefore.ImageBytes)"
    }

    Invoke-SafePhase -Name "SnapshotTest" -Action {
        try {
            Invoke-SafeNativeCommand -FilePath ".\gradlew.bat" -Arguments @(":app:testDebugUnitTest", "--tests", "com.lyco256.llm.data.SnapshotCompatibilityTest", "-PsnapshotRoot=$script:snapshot", "-PsnapshotImages=$script:images", "--console=plain", "--no-daemon") -TimeoutSeconds $timeouts.SnapshotTest -WorkingDirectory $repoRoot
        } catch {
            Set-SafeImpactSummary -Message (Get-SnapshotImpactSummary -Before $script:sourceBefore -SnapshotPath $script:snapshot -ImagesPath $script:images)
            throw
        }
        $impact = Get-SnapshotImpactSummary -Before $script:sourceBefore -SnapshotPath $script:snapshot -ImagesPath $script:images
        Write-SafeLog $impact
        if ($impact -notmatch "^Source snapshot unchanged") {
            Set-SafeImpactSummary -Message $impact
            throw "Source snapshot changed during snapshot compatibility check."
        }
    }

    Complete-SafeScript
    Write-Host "Success"
    exit 0
} catch {
    Stop-SafeScriptWithFailure -Exception $_.Exception
}
