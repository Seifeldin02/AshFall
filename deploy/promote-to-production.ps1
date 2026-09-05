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
    [string]$ProductionRoot = 'C:\MinecraftServer',
    # Where the manifest's `sync:` artifacts are promoted FROM. Staging is the reference for those, and
    # check_deploy.py compares production against it.
    [string]$StagingRoot = 'C:\MinecraftServer-Staging',
    # Skips the typed confirmation. For a promotion the owner has already authorised in writing, and for
    # any caller with no console to type into -- Read-Host reads EOF there and the script aborts.
    [switch]$Yes
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
    if (-not $Yes) {
        $confirm = Read-Host "Type YES to continue"
        if ($confirm -ne 'YES') {
            Write-Host "Aborted. Nothing was changed." -ForegroundColor Yellow
            return
        }
    }

    # --- Back up current production jar + plugin configs ------------------
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backupDir = Join-Path $ProductionRoot "backups\$stamp-pre-promote-$resolvedShort"
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null

    $prodPlugins = Join-Path $ProductionRoot 'plugins'
    # 'SMPCore*.jar', NOT 'SMPCore-*.jar'. The jar production actually runs is called SMPCore.jar, with no
    # version in the name, so the old filter matched nothing: it backed nothing up, removed nothing, and
    # then copied SMPCore-1.7.0.jar in ALONGSIDE the one already there. Two SMPCore jars in one plugins
    # folder is a coin toss over which Paper loads, and the losing side is invisible -- the plugin still
    # enables, and still reports itself as enabled.
    $existingJars = @(Get-ChildItem $prodPlugins -Filter 'SMPCore*.jar' -ErrorAction SilentlyContinue)
    $existingJar = $existingJars | Select-Object -First 1
    if ($existingJars.Count -gt 1) {
        throw "Production already has $($existingJars.Count) SMPCore jars: $($existingJars.Name -join ', '). Which one is running is a guess. Resolve that by hand before promoting."
    }
    if ($existingJar) {
        Copy-Item $existingJar.FullName (Join-Path $backupDir $existingJar.Name) -Force
        Write-Host "Backed up current production jar: $($existingJar.Name) (sha256 $((Get-FileHash $existingJar.FullName -Algorithm SHA256).Hash))" -ForegroundColor Green
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

    # --- Clear plugins/update/ ------------------------------------------
    # Paper applies whatever is in here on the next boot, overwriting what was just deployed, and the
    # server reports the plugin as enabled either way. A stale jar sitting here is how a promotion
    # silently does not happen.
    $updateDir = Join-Path $prodPlugins 'update'
    if (Test-Path $updateDir) {
        foreach ($file in @(Get-ChildItem $updateDir -File -ErrorAction SilentlyContinue)) {
            Copy-Item $file.FullName (Join-Path $backupDir "update-$($file.Name)") -Force
            Remove-Item $file.FullName -Force
            Write-Host "Cleared stale plugins/update/$($file.Name) (backed up first)" -ForegroundColor Yellow
        }
    }

    # --- Deploy the jar, under the name production already uses ---------
    # Keeping the existing filename means nothing else on that server has to learn a new one, and
    # check_deploy.py's "exactly one SMPCore*.jar" stays true by construction rather than by luck.
    $destName = if ($existingJar) { $existingJar.Name } else { Split-Path -Leaf $builtJarPath }
    $existingJars | Remove-Item -Force -ErrorAction SilentlyContinue
    $destJar = Join-Path $prodPlugins $destName
    Copy-Item -Path $builtJarPath -Destination $destJar -Force
    $destHash = (Get-FileHash $destJar -Algorithm SHA256).Hash
    Write-Host "Deployed jar sha256 $destHash" -ForegroundColor Green

    # --- Deploy the manifest artifacts a jar swap does not carry --------
    # colosseum.yml is only written by the plugin when it does not already exist; the arena snapshots are
    # what an encounter clones its instance from; the two supervisor scripts are only re-read when they
    # are relaunched. None of them ride along with the jar. Every one is backed up before it is replaced.
    #
    # They come from STAGING, not from the repo, because staging is what deploy/manifest.yml calls the
    # reference for a `sync:` path and therefore what check_deploy.py compares production against. Taking
    # them from anywhere else can leave the checker red after a promotion that looked like it worked.
    $artifacts = @(
        'plugins\SMPCore\colosseum.yml'
        'plugins\SMPCore\colosseum-templates'
        'console-guard.ps1'
        'storage-guard.ps1'
    )
    foreach ($artifact in $artifacts) {
        $src = Join-Path $StagingRoot $artifact
        if (-not (Test-Path $src)) { throw "Manifest artifact missing from staging: $src" }
        $dst = Join-Path $ProductionRoot $artifact
        if (Test-Path $dst) {
            Copy-Item $dst (Join-Path $backupDir ($artifact -replace '\\', '-')) -Recurse -Force
        }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
        # A directory copied onto an EXISTING directory of the same name goes INSIDE it. The second
        # promotion of the day produced colosseum-templates\colosseum-templates -- a complete, unused
        # duplicate of both arenas, which check_deploy caught and nothing else would have. Clearing the
        # destination first makes a re-run idempotent, which a promotion script has to be.
        if ((Test-Path $src -PathType Container) -and (Test-Path $dst)) {
            Remove-Item $dst -Recurse -Force
        }
        Copy-Item -Path $src -Destination $dst -Recurse -Force
        Write-Host "  promoted $artifact" -ForegroundColor Green
    }

    $marker = Join-Path $ProductionRoot 'DEPLOYED_COMMIT.txt'
    @"
commit=$resolved
jar=$destName
sha256=$destHash
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
