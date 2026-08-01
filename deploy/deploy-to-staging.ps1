#requires -Version 5.1
<#
  Builds the current git HEAD and deploys the resulting jar to the staging
  server (C:\MinecraftServer-Staging). Never touches production.

  Refuses to run unless the working tree is clean and HEAD is on `staging`
  (or a fix/feature branch, if -AllowAnyBranch is passed) — this keeps
  "what's deployed to staging" traceable to a real commit at all times.

  Does NOT stop or start the staging server process. Restart it yourself
  from a visible console (start-staging.bat) once the jar is in place —
  server processes are managed manually, never launched hidden/backgrounded.

  Usage:
    .\deploy\deploy-to-staging.ps1
    .\deploy\deploy-to-staging.ps1 -AllowAnyBranch
#>
param(
    [string]$StagingRoot = 'C:\MinecraftServer-Staging',
    [switch]$AllowAnyBranch
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path $StagingRoot)) {
    throw "Staging server not found at $StagingRoot. Set it up first."
}

Push-Location $repoRoot
try {
    $dirty = git status --porcelain
    if ($dirty) {
        throw "Working tree is not clean. Commit or stash changes before deploying to staging.`n$dirty"
    }

    $branch = git rev-parse --abbrev-ref HEAD
    if (-not $AllowAnyBranch -and $branch -ne 'staging') {
        throw "HEAD is on '$branch', not 'staging'. Merge into staging first, or pass -AllowAnyBranch to deploy a feature branch directly for local testing."
    }

    $commit = git rev-parse HEAD
    $commitShort = git rev-parse --short HEAD
}
finally {
    Pop-Location
}

Write-Host "Deploying commit $commitShort (branch: $branch) to staging ..." -ForegroundColor Cyan

# --- Build --------------------------------------------------------------
$builtJarPath = & (Join-Path $PSScriptRoot 'build.ps1')
if (-not $builtJarPath -or -not (Test-Path $builtJarPath)) {
    throw "Build did not produce a jar."
}

# --- Deploy ---------------------------------------------------------------
$stagingPlugins = Join-Path $StagingRoot 'plugins'
New-Item -ItemType Directory -Force -Path $stagingPlugins | Out-Null

Get-ChildItem $stagingPlugins -Filter 'SMPCore-*.jar' -ErrorAction SilentlyContinue |
    Remove-Item -Force

$destJar = Join-Path $stagingPlugins (Split-Path -Leaf $builtJarPath)
Copy-Item -Path $builtJarPath -Destination $destJar -Force

$marker = Join-Path $StagingRoot 'DEPLOYED_COMMIT.txt'
@"
commit=$commit
branch=$branch
jar=$(Split-Path -Leaf $builtJarPath)
deployed_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
"@ | Set-Content -Path $marker -Encoding utf8

Write-Host "Deployed $(Split-Path -Leaf $destJar) to $stagingPlugins" -ForegroundColor Green
Write-Host "Recorded deployed commit in $marker" -ForegroundColor Green
Write-Host ""
Write-Host "Staging server not restarted automatically." -ForegroundColor Yellow
Write-Host "Restart it yourself via a visible console: $StagingRoot\start-staging.bat"
