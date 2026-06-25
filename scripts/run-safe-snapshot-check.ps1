param(
    [Parameter(Mandatory = $true)][string]$SnapshotRoot,
    [Parameter(Mandatory = $true)][string]$ImageRoot
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$snapshot = (Resolve-Path -LiteralPath $SnapshotRoot).Path
$images = (Resolve-Path -LiteralPath $ImageRoot).Path
if (-not (Test-Path -LiteralPath (Join-Path $snapshot "like_list_manager.db") -PathType Leaf)) {
    throw "like_list_manager.db was not found under $snapshot"
}
if (-not (Test-Path -LiteralPath $images -PathType Container)) { throw "ImageRoot is not a directory: $images" }

Push-Location $repoRoot
try {
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    & .\gradlew.bat testDebugUnitTest --tests com.lyco256.llm.data.SnapshotCompatibilityTest "-PsnapshotRoot=$snapshot" "-PsnapshotImages=$images" --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Snapshot compatibility checks failed." }
} finally {
    Pop-Location
}
