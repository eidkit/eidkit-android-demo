# Build a release AAB for the EidKit demo app.
# Reads signing secrets from release.secrets.ps1 (gitignored).
# Usage: .\release.ps1

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$SecretsFile = Join-Path $PSScriptRoot "release.secrets.ps1"
if (-not (Test-Path $SecretsFile)) {
    Write-Error "Missing $SecretsFile — copy release.secrets.ps1 and fill in your values."
    exit 1
}
. $SecretsFile

Write-Host "Building release AAB..."
& "$PSScriptRoot\gradlew.bat" :app:bundleRelease

$aab = "$PSScriptRoot\app\build\outputs\bundle\release\app-release.aab"
if (Test-Path $aab) {
    Write-Host "Done: $aab"
} else {
    Write-Error "Build succeeded but AAB not found at expected path."
}
