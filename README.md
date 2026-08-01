# AshFall — Ashfall Concord SMPCore

Source and deployment tooling for **SMPCore**, the custom plugin behind
Ashfall Concord (Paper 26.2, `net.communitysmp.core`).

This repo holds source code, build files, safe config templates, and
deployment scripts. It never holds worlds, databases, player data, logs,
backups, built jars, or credentials — see `.gitignore`.

## Layout

```
smpcore-src/         Maven project (paper-api 26.2, Java 21)
config-templates/    Redacted templates for server.properties, ReplayCore
                      config, etc. Copy + fill in real values, never commit
                      the filled-in copy.
deploy/               build.ps1 / deploy-to-staging.ps1 /
                      promote-to-production.ps1 — see deploy/README.md
```

## Branch model

- **`main`** — exact code currently approved and running in production.
- **`staging`** — code currently being tested on the staging server.
- **`fix/*` / `feature/*`** — short-lived branches, merged into `staging`.

Nothing reaches `main` or production without being tested and explicitly
approved on staging first.

## Servers

| | Production | Staging |
|---|---|---|
| Path | `C:\MinecraftServer` | `C:\MinecraftServer-Staging` |
| Java port | 25565 | 25566 |
| Geyser | UDP 19133 | UDP 19133 (staging-only, not port-forwarded) |
| Voice Chat | UDP 24455 | UDP 24455 (staging-only, not port-forwarded) |
| Whitelist | as configured | always on |
| RCON | enabled | disabled by default |
| ReplayCore | live | disabled/isolated |
| Discord reminders | live | disabled |

Staging is never port-forwarded and never shares worlds, databases, plugin
data, configs, or logs with production.

## Workflow

1. Branch off `staging` (`fix/orders-partial-claim`, etc.) and develop.
2. Merge into `staging`.
3. `.\deploy\deploy-to-staging.ps1` — builds and deploys that exact commit
   to `C:\MinecraftServer-Staging`.
4. Test on staging.
5. Once approved, merge that exact tested commit into `main`.
6. `.\deploy\promote-to-production.ps1 -Commit <hash>` — backs up
   production, builds, deploys only the jar.
7. Apply any deliberate config changes or tested DB migrations by hand.
8. Restart production once, manually, from its visible console.

Full details in [`deploy/README.md`](deploy/README.md).

## Building locally

Third-party plugin jars (VaultUnlocked, AuthMe, GrimAC, Geyser, ReplayCore,
TAB) are `system`-scoped dependencies in `smpcore-src/pom.xml` and aren't
committed here. `deploy/build.ps1` stages fresh copies from a running
server (production by default) before every build — see
[`deploy/README.md`](deploy/README.md) for details.
