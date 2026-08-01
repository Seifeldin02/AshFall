# Deployment scripts

Three PowerShell scripts, one job each. Run them from anywhere; they locate
the repo root relative to their own location.

Third-party plugin jars (VaultUnlocked, AuthMe, GrimAC, Geyser, ReplayCore,
TAB) are `system`-scoped dependencies in `smpcore-src/pom.xml` and are never
committed to this repo. `build.ps1` stages fresh copies from a running
server (production by default) before every build, so there's no manual
setup step beyond having a server with those plugins installed.

## build.ps1

Stages the third-party jars and runs `mvn clean package`. Prints and
returns the path to the built `SMPCore-<version>.jar`. Used internally by
the other two scripts; run it directly if you just want to check the code
compiles.

```powershell
.\deploy\build.ps1
```

## deploy-to-staging.ps1

Builds current HEAD and copies the jar into
`C:\MinecraftServer-Staging\plugins\`. Refuses to run with uncommitted
changes, and refuses to run off a branch other than `staging` unless you
pass `-AllowAnyBranch` (useful for quickly testing a `fix/*` branch before
it's merged). Records the exact deployed commit in
`MinecraftServer-Staging\DEPLOYED_COMMIT.txt`. Does not restart the
staging server — do that yourself from `start-staging.bat` in a visible
console.

```powershell
.\deploy\deploy-to-staging.ps1
.\deploy\deploy-to-staging.ps1 -AllowAnyBranch   # deploy a feature branch directly
```

## promote-to-production.ps1

The only script allowed to touch `C:\MinecraftServer`. Takes an explicit
commit hash — never a default, never "whatever HEAD is" — and refuses to
run unless that commit is already merged into `main`. Backs up the current
production jar and `plugins/SMPCore` config folder, asks for a typed `YES`
confirmation, then builds and deploys only the jar. It never restarts
production and never touches worlds, databases, or player data.

```powershell
.\deploy\promote-to-production.ps1 -Commit abc1234
```

## Full workflow

1. Branch off `staging`: `fix/orders-partial-claim`.
2. Develop, commit, open a PR (or merge directly) into `staging`.
3. `.\deploy\deploy-to-staging.ps1`
4. Test on the staging server, whitelisted, port 25566.
5. Once approved, merge that exact tested commit into `main`.
6. `.\deploy\promote-to-production.ps1 -Commit <that commit>` — backs up
   production automatically as part of this step.
7. Apply any deliberate config changes or tested DB migrations by hand.
8. Restart production once, manually, from its visible console.
