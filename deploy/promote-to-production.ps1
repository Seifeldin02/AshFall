#requires -Version 5.1
<#
  Promotes an exact, already-tested staging commit to production.

  This script deliberately does the LEAST it can get away with:
    1. Requires an explicit -Commit (no "just use HEAD" default) so you
       always know precisely what you're shipping.
    2. Requires that commit to already be merged into `main` (the user
       approves by merging into main themselves; this script never merges).
    3. Backs up current production (jar + plugin configs) before touching
       anything, so the previous build is always available for rollback.
    4. Builds that exact commit and copies ONLY the jar into production's
       plugins folder. It does NOT touch worlds, databases, player data,
       or config files beyond the jar.
    5. Does NOT restart production. Restart is a separate, deliberate,
       single action you take yourself from a visible console — never
       scripted, never automatic.

  Usage:
    .\deploy\promote-to-production.ps1 -Commit abc1234
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Commit,
    [string]$ProductionRoot = 'C:\MinecraftServer'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

Push-Location $repoRoot
try {
    # Resolve and validate the commit exists.
    $resolved = git rev-parse --verify "$Commit^{commit}" 2>$null
    if (-not $resolved) {
        throw "Commit '$Commit' does not exist in this repo."
    }
    $resolvedShort = git rev-parse --short $resolved

    # Confirm it's reachable from main (i.e. actually merged/approved).
    $onMain = git branch --contains $resolved --list main
    if (-not $onMain) {
        throw "Commit $resolvedShort is not on 'main'. Merge the approved staging commit into main first -- this script refuses to promote anything main doesn't already contain."
    }

    Write-Host "About to promote commit $resolvedShort (confirmed on main) to PRODUCTION at $ProductionRoot" -ForegroundColor Yellow
    $confirm = Read-Host "Type YES to continue"
    if ($confirm -ne 'YES') {
        Write-Host "Aborted. Nothing was changed." -ForegroundColor Yellow
        return
    }

    # --- Back up current production jar + plugin configs ------------------
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backupDir = Join-Path $ProductionRoot "backups\$stamp-pre-promote-$resolvedShort"
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null

    $prodPlugins = Join-Path $ProductionRoot 'plugins'
    $existingJar = Get-ChildItem $prodPlugins -Filter 'SMPCore-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($existingJar) {
        Copy-Item $existingJar.FullName (Join-Path $backupDir $existingJar.Name) -Force
        Write-Host "Backed up current production jar: $($existingJar.Name)" -ForegroundColor Green
    }
    else {
        Write-Host "No existing SMPCore jar found in production plugins (first deploy?)." -ForegroundColor Yellow
    }

    $smpcoreConfigDir = Join-Path $prodPlugins 'SMPCore'
    if (Test-Path $smpcoreConfigDir) {
        Copy-Item $smpcoreConfigDir (Join-Path $backupDir 'SMPCore-config') -Recurse -Force
        Write-Host "Backed up plugins\SMPCore config folder." -ForegroundColor Green
    }

    # --- Build the exact approved commit -----------------------------------
    $originalHead = git rev-parse --abbrev-ref HEAD
    Write-Host "Checking out $resolvedShort to build ..." -ForegroundColor Cyan
    git checkout --quiet $resolved

    $builtJarPath = $null
    try {
        $builtJarPath = & (Join-Path $PSScriptRoot 'build.ps1')
    }
    finally {
        git checkout --quiet $originalHead
    }

    if (-not $builtJarPath -or -not (Test-Path $builtJarPath)) {
        throw "Build did not produce a jar. Production was NOT modified."
    }

    # --- Deploy ONLY the jar --------------------------------------------
    Get-ChildItem $prodPlugins -Filter 'SMPCore-*.jar' -ErrorAction SilentlyContinue |
        Remove-Item -Force
    $destJar = Join-Path $prodPlugins (Split-Path -Leaf $builtJarPath)
    Copy-Item -Path $builtJarPath -Destination $destJar -Force

    $marker = Join-Path $ProductionRoot 'DEPLOYED_COMMIT.txt'
    @"
commit=$resolved
jar=$(Split-Path -Leaf $builtJarPath)
promoted_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
backup=$backupDir
"@ | Set-Content -Path $marker -Encoding utf8

    Write-Host ""
    Write-Host "Deployed $(Split-Path -Leaf $destJar) to production plugins." -ForegroundColor Green
    Write-Host "Previous jar + config backed up to: $backupDir" -ForegroundColor Green
    Write-Host ""
    Write-Host "Production NOT restarted. Any config changes or DB migrations" -ForegroundColor Yellow
    Write-Host "for this release must be applied by hand before restarting." -ForegroundColor Yellow
    Write-Host "Restart production once, deliberately, from its visible console." -ForegroundColor Yellow
}
finally {
    Pop-Location
}
