#requires -Version 5.1
<#
  Builds SMPCore-<version>.jar from smpcore-src.

  pom.xml pulls in several third-party plugin jars as `system`-scoped
  dependencies via relative paths (../plugins/..., ../staging/...). Those
  jars are third-party binaries and are never committed to this repo, so
  this script stages local copies of them next to smpcore-src before
  invoking Maven. By default it copies them from the live production
  server; pass -SourceServer to point at a different copy (e.g. a synced
  staging install) if production is ever unavailable.

  Usage:
    .\deploy\build.ps1
    .\deploy\build.ps1 -SourceServer 'D:\SomeOtherCopy'
#>
param(
    [string]$SourceServer = 'C:\MinecraftServer'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$srcDir   = Join-Path $repoRoot 'smpcore-src'

if (-not (Test-Path $srcDir)) {
    throw "smpcore-src not found at $srcDir"
}

# --- Stage third-party system-scoped jars referenced by pom.xml -----------
$thirdPartyJars = @(
    @{ Rel = 'plugins\VaultUnlocked-2.20.2.jar';                     Src = 'plugins\VaultUnlocked-2.20.2.jar' }
    @{ Rel = 'plugins\AuthMe-6.0.0-Paper.jar';                       Src = 'plugins\AuthMe-6.0.0-Paper.jar' }
    @{ Rel = 'plugins\grimac-bukkit-2.3.74-85dd3d9.jar';             Src = 'plugins\grimac-bukkit-2.3.74-85dd3d9.jar' }
    @{ Rel = 'plugins\Geyser-Spigot.jar';                            Src = 'plugins\Geyser-Spigot.jar' }
    @{ Rel = 'plugins\ReplayCore-1.5.0.jar';                         Src = 'plugins\ReplayCore-1.5.0.jar' }
    @{ Rel = 'staging\tab-elites-20260723\TAB.v6.1.0.jar';           Src = 'staging\tab-elites-20260723\TAB.v6.1.0.jar' }
)

Write-Host "Staging third-party jars from $SourceServer ..." -ForegroundColor Cyan
foreach ($jar in $thirdPartyJars) {
    $dest = Join-Path $repoRoot $jar.Rel
    $src  = Join-Path $SourceServer $jar.Src
    if (-not (Test-Path $src)) {
        throw "Required third-party jar not found: $src`nThis file is not committed to git (it's a third-party binary) and must exist on the source server."
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
    Copy-Item -Path $src -Destination $dest -Force
    Write-Host "  staged $($jar.Rel)"
}

# --- Build ------------------------------------------------------------------
Write-Host "Building with Maven ..." -ForegroundColor Cyan
Push-Location $srcDir
try {
    & mvn -q clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed (exit code $LASTEXITCODE)"
    }
}
finally {
    Pop-Location
}

$builtJar = Get-ChildItem (Join-Path $srcDir 'target') -Filter 'SMPCore-*.jar' |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } |
            Select-Object -First 1

if (-not $builtJar) {
    throw "Build succeeded but no SMPCore-*.jar found in target\"
}

Write-Host "Built: $($builtJar.FullName)" -ForegroundColor Green
return $builtJar.FullName
