param(
    [string]$DeviceConfig = (Join-Path $PSScriptRoot "..\test-device.local.properties"),
    [string]$OutputRoot = (Join-Path $PSScriptRoot "..\build\ocr8-visual")
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb.exe was not found at $adb."
}
if (-not (Test-Path -LiteralPath $DeviceConfig)) {
    throw "Missing local device allow-list: $DeviceConfig"
}

$config = @{}
foreach ($line in Get-Content -LiteralPath $DeviceConfig) {
    if ($line -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') {
        $config[$Matches[1].Trim()] = $Matches[2].Trim()
    }
}
$hardwareSerial = $config["testDeviceSerial"]
$testPackage = $config["testPackage"]
if ([string]::IsNullOrWhiteSpace($hardwareSerial) -or [string]::IsNullOrWhiteSpace($testPackage)) {
    throw "The visual check requires testDeviceSerial and testPackage in $DeviceConfig."
}

$deviceLines = @(& $adb devices -l 2>&1)
$matchingEndpoints = @()
foreach ($line in @($deviceLines | Where-Object { $_ -match '^\S+\s+device(?:\s|$)' })) {
    $endpoint = [regex]::Match($line, '^(.*?)\s+device(?:\s|$)').Groups[1].Value.Trim()
    $reportedSerial = ((& $adb -s $endpoint shell getprop ro.serialno 2>&1) -join '').Trim()
    if ($reportedSerial -eq $hardwareSerial) {
        $matchingEndpoints += $endpoint
    }
}
if ($matchingEndpoints.Count -eq 0) {
    throw "Allowed test device $hardwareSerial is not connected. Run run-safe-integration-check.cmd first after reconnecting it."
}
$serial = @($matchingEndpoints | Sort-Object @{ Expression = { if ($_ -match ' \(\d+\)\._adb-tls-connect\._tcp$') { 1 } else { 0 } } }, @{ Expression = { $_ } })[0]
foreach ($extra in @($matchingEndpoints | Where-Object { $_ -ne $serial })) {
    & $adb disconnect $extra | Out-Null
}

$source = "/sdcard/Android/data/$testPackage/files/ocr8-visual/."
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$destination = Join-Path (Join-Path $OutputRoot $stamp) ""
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$pullOutput = @(& $adb -s $serial pull $source $destination 2>&1)
$pullExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorActionPreference
if ($pullExitCode -ne 0) {
    throw "Could not retrieve OCR8 visual artifacts from $serial. Output: $($pullOutput -join ' | ')"
}
$files = @(Get-ChildItem -LiteralPath $destination -File -Filter "*.png")
if ($files.Count -lt 3) {
    throw "Expected at least three OCR8 visual PNG artifacts in $destination, found $($files.Count)."
}
Write-Output "VisualArtifacts"
$files | Sort-Object Name | ForEach-Object { Write-Output $_.FullName }
Write-Output "Success"
