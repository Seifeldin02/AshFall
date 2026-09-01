# Ashfall Concord — session start prompt

Paste this at the start of a new session. It is deliberately short: the detail lives in the files it points
at, and those stay current because updating them is part of finishing a change.

---

You are working on **SMPCore**, the custom Paper plugin behind the Ashfall Concord Minecraft server.

## Read these first, in this order

1. `DEVELOPMENT_LOG.md` — newest entries first. What shipped, what broke, and why. Read at least the top
   three sessions before touching anything.
2. `deploy/manifest.yml` — every artifact a promotion must carry that Git does **not** track. If a change
   touches a config or a jar, it belongs here.
3. `DUEL_HANDOFF.md` — only if the work touches duels or arenas.

## Layout

| Thing | Where |
|---|---|
| Plugin source | `C:\Ashfall-Development\AshFall\smpcore-src` (Maven, Java 21, `mvn -o package`) |
| Production server | `C:\MinecraftServer` — game 25565, RCON 25575 |
| Staging server | `C:\MinecraftServer-Staging` — game 25566, RCON 25576 |
| Built jar | `smpcore-src/target/SMPCore-1.7.0.jar` → `plugins/SMPCore.jar` |
| Git | branch `staging`; the servers themselves are not in the repo |

## How to work

- **Staging first, always.** Deploy, restart, verify, then ask before promoting. The owner will say when to
  promote; do not assume.
- **A jar swap carries no YAML.** `config.yml`, `shop.yml`, `bosses.yml`, `relics.yml`, `shards.yml`,
  `events.yml` and the Paper configs live on each server and must be edited on each server. This has bitten
  every release that forgot it.
- **Clear `plugins/update/` before swapping a jar**, or Paper applies a stale one on boot and overwrites what
  you just copied. Verify the running build, not just that the plugin enabled.
- **Restarts:** capture the listening java PID *before* sending `stop` and wait for the **process to exit** —
  the ports close early while the world is still saving, and launching into a locked world folder kills the
  boot. Never `taskkill` a console PID; send `stop` over RCON.
- **Relaunch** through `launch-via-conhost.bat` via `schtasks /it` so the console is visible. The owner needs
  a real console; never launch hidden or backgrounded.
- **Verify with evidence**, not assumption: `/ashfall selftest`, `python deploy/check_deploy.py`, and a grep
  of `logs/latest.log` for SMPCore errors. Quote the numbers.

## Standing rules from the owner

- **Restart announcements:** if only Asserto and/or MacoCT are online, restart silently. Anyone else, announce
  30 seconds ahead. One message and a 5-second countdown — no staged warnings, no "update complete" broadcast.
- **Never touch production** unless explicitly told to in that message.
- **Dev log every change.** "So when I push to production we know what will be pushed and what won't."
- **Owner-fixed prices and rates are not yours to tidy.** They are pinned in `manifest.yml` and in self-tests.
- Use the console yourself rather than asking the owner to type commands.

## Traps that have actually cost real time

- A **Bukkit repeating task is not cancelled by an exception** — it logs and runs again next tick. Advance any
  loop counter *before* anything that can throw, or one bad frame becomes an infinite one.
- `Particle#getDataType()` varies by particle and by Paper version (`FLASH` wants a `Color`, `DRAGON_BREATH` a
  `Float`). Spawning one bare throws. Ask the API; don't hardcode.
- `Location.getWorld()` **throws** after its world unloads — it does not return null. Hold world *names*.
- Bukkit worlds live in `world/dimensions/<namespace>/<world>` and have **no `level.dat`**; identity is
  `data/paper/metadata.dat`.
- `execute if block` reports failure for an **unloaded** chunk, not just a non-matching block. `/forceload add`
  before probing or your verification lies to you.
- A spawner needs a player in the world to tick at all, so spawner behaviour **cannot** be tested on an empty
  staging server. `RequiredPlayerRange: -1` makes one tick regardless of distance, but not of an empty server.
- Read the **actual server jar** when a vanilla mechanic is in question (`javap -c` on
  `versions/<v>/paper-<v>.jar`). Two bugs this month were mis-diagnosed from remembered vanilla behaviour.

## Style

Match the surrounding code: dense formatting, and comments that explain *why* — especially the reasoning
behind a non-obvious fix, since that is what stops the next person reintroducing it. Do not add a comment that
merely restates the code.
