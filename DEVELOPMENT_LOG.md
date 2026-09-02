# Ashfall Concord — Development Log

Newest first. Updating this is part of finishing a change, not an afterthought — same as updating
`deploy/manifest.yml` when a non-Git-tracked config or jar starts mattering to a deploy.

---

## Session: 2026-09-02 (part 3) - The Boss Colosseum

A paid, solo boss fight in a disposable arena clone, using the player's own real equipment. Three bosses
with distinct identities and combat styles, their own configuration, reward tables, statistics and
lifecycle. Staging only; production is untouched.

### What it is, and what it deliberately is not

It is NOT the world-boss system in a smaller room. A Colosseum encounter never creates, reads or clears
world-boss state, participation records, the active-boss restriction, natural or event spawning, or
world-boss cleanup — the two systems are invisible to each other, and `/ashfall colosseum verify` asserts
that directly by checking a spawned Colosseum boss is not tagged as a world boss.

What IS shared is arithmetic that was already proven here: Minecraft caps the `MAX_HEALTH` attribute at
1024, so a bigger pool has to be carried as a damage divisor. Same technique as the world bosses, same
1024 clamp, and the verifier asserts `engineHealth x toughness == configured health` for every boss so the
two halves can never silently disagree.

### The three bosses

| Boss | Entity | Style | Pool | Melee | Armour | Limit |
|---|---|---|---:|---:|---:|---|
| **Emberbound Duelist** | Wither Skeleton | Duellist | 1,800 | 17 | 6 | 5m |
| **Warden of Cinders** | Iron Golem | Area control | 2,600 | 20 | 12 | 7m |
| **Ashfallen Revenant** | Vindicator | Mobile skirmisher | 1,500 | 14 | 4 | 5m |

- **Emberbound Duelist** — *Riposte* (telegraphed stance: melee is cut to 25% and 35% of it comes back,
  capped at 8; the counterplay is simply to stop swinging for two seconds), *Lunge* (closes from range,
  then stands slowed — that recovery window is the reward), *Flurry* (three spaced sword strikes).
- **Warden of Cinders** — *Fissure* (marks a ring of ground, erupts 1.5s later; step off the marks),
  *Bulwark* (anchors: 20% damage taken but cannot move or attack — free repositioning in exchange for a
  damage window you cannot win), *Slam* (radial shove, worst at point-blank).
- **Ashfallen Revenant** — *Blink* (telegraphed at BOTH ends, so the destination is visible before it
  arrives), *Volley* (exactly three small fireballs, bounded and tracked), *Veil* (below 35% it gains speed
  and shrugs off projectiles, forcing the last third to be closed out in melee).

Every ability is telegraphed with a sound and particles at least 0.75s ahead, has an explicit answer, edits
no blocks, summons nothing and cannot one-shot a full-health player. `mechanicsSelfTest()` asserts all four
of those properties from configuration, so a well-meaning config edit cannot quietly break any of them.

**Ability damage is dealt as MAGIC on purpose.** Full Protection IV netherite cuts a 20-damage physical hit
to under two, so an area ability that respected armour would be pure decoration against the exact gear
this is balanced for. Protection, Resistance, absorption, totems and healing all still apply, and every one
of these lands a second or more after a telegraph that says exactly where it will be — but none of them can
be ignored. Sword strikes stay physical, as they should.

There is no hidden player-count scaling anywhere. A `BossDef` carries no participant field at all.

### The money, and why it is shaped this way

$500,000 in, $1,000,000 out, fee never refunded on a win, so a victory nets $500,000. Three rewarded
victories per player per real day across the whole Colosseum, resetting at Riyadh midnight like every other
daily boundary here — that caps Colosseum profit at $1.5M/day/player, and it is the entire brake on this
becoming a money printer.

**Nothing is charged until the world exists, the state is captured, and the player is verifiably standing
in the arena.** Not "did `teleport()` return true" — `world.equals(player.getWorld())`, because a teleport
can be cancelled or redirected and charging for a fight somebody is not in is the failure this order of
operations exists to make impossible. The charge is the last step before commitment, so any failure during
preparation costs exactly nothing.

Every money flag is a **compare-and-set in SQL**, not a boolean in memory:

```sql
UPDATE colosseum_runs SET charged=1 WHERE run_id=? AND charged=0
```

A duplicated callback, a double click, a reconnect, a duplicate death event, instance cleanup and boot
recovery can all try to resolve the same run. The first one wins; the rest are no-ops that the caller can
see are no-ops. The verifier attempts every one of them twice on purpose and requires the second to fail.

The fee moves through `serverPayment`/`refundServerPayment` — the atomic debit-and-credit pair the rest of
the economy already uses. A fee that only debits the player and a refund that only credits them are not
inverses: the first destroys money and the second creates it. The prize goes through `creditEarned`, so an
overdue bank loan is garnished from a Colosseum prize exactly as from any other income.

### Belongings: a copy in, the original back

A fighter carries a **copy** of their equipment. Durability, eaten golden apples, spent rockets, arena
drops and boss loot are all discarded; the captured original is restored exactly on every exit path.

Which means every route out of the inventory has to be closed while inside, or a copy becomes a duplicate.
Rather than enumerate Ender Chests, shulkers, auctions, orders, faction vaults and trades and hope nobody
adds a twelfth next month, **the rule is inverted**: the only inventory a player may open inside an
encounter is their own, and commands are an allowlist rather than a blocklist. A system added tomorrow is
covered without anybody remembering to come back here.

Two failure modes that took specific work:

- **A corpse cannot be teleported.** `destroyInstance()` moves players out of the world it is about to
  delete, and a dead player ignores that — so the loser of a fight would be left lying in a world that
  stopped existing a tick later. Resolution now forces the respawn first, which fires `PlayerRespawnEvent`
  while the capture is still intact and puts them back on the exact spot they entered from. (Same shape as
  the duel bug where losers woke up at their bed.)
- **A disconnect writes the arena copy to disk.** Paper saves the player's `.dat` right after they quit,
  and at that moment it holds the copied gear — rejoin and you own two of everything. So a quit overwrites
  the live inventory with the captured original *before* that write, and deliberately KEEPS the capture so
  the join handler can finish the location, health and effects properly. The join restore runs on the next
  tick, not after a second, because every tick holding a copy in the real world is a tick it could be
  dropped.

Deaths leave no grave (guard added to `GraveService`), drop nothing real, pay no death tax (guard added to
`GameplayListener`), and never touch the real respawn point. A Colosseum boss death pays no mob money and
counts toward no kill statistic — without that guard a boss worth a million dollars would *also* pay
ordinary combat income on the way down.

Rewards are granted only after restoration succeeds. If it fails, nothing is paid and the fee is refunded
instead: the failure mode is "the player got their money back", never "the player got a prize and lost
their inventory". Overflow goes to the persistent `/orders` stash rather than onto the floor.

### Crash vs disconnect, without guessing

An ordinary quit is resolved as a **loss the instant it happens**. So a row still `ACTIVE` at boot can only
mean the process died with the run genuinely live — the system interrupted the player, and the fee comes
back. No heuristic about who is online, no heartbeat column, no timeout. `recover()` is idempotent, and the
verifier plants rows in exactly the state a dead process leaves behind and runs it twice.

### Arenas

The duel engine's architecture, and in the places that matter literally the duel engine's code:
`copyWorldFolder`, `deleteQuietly`, `deleteWithRetry`, `stripIdentity` and `customWorldDir()` in
`DuelMapService` were opened up to package scope and are called directly. Everything learned the hard way
about Paper's `<level-name>/dimensions/<ns>/` layout, duplicate-UUID refusals and Windows holding region
handles after an unload now has exactly one implementation instead of two that can drift.

What is **not** shared is identity. `colo_tpl_*` / `colo_inst_*`, their own snapshot directory, their own
registry. Editing a Colosseum arena cannot alter a duel map or vice versa; no Colosseum world is ever
offered to duel matchmaking; and the duel chest-loot roller never sees a Colosseum instance — which matters,
because it would be a free item printer sitting inside a paid fight.

`ashen_colosseum` was seeded once from the committed `arena100` duel snapshot (a read and a copy out; the
duel map is not modified) and is its own world from that point on.

Instance preparation is an async folder copy of the immutable snapshot plus sliced chunk loading, 24 chunks
per tick. Templates are never entered by matchmaking. Orphans are cleaned at boot and swept every 90s.

### Performance, measured

`/ashfall colosseum bench <instances> <seconds>` holds real instances open with a real boss telegraphing in
each, samples the server's own tick times, and tears it all down. Two concurrent Warden of Cinders
encounters on staging:

| | Value |
|---|---|
| Instance preparation | 516 ms cold single; ~1.28 s wall for two at once (folder copy 48 ms) |
| Loaded chunks | 93-94 per instance (arena is 64; the rest is Paper's ticket propagation) |
| Force-loaded chunks | **0** — nothing is ever pinned |
| Entities per instance | 1 (the boss) plus at most 3 short-lived fireballs |
| Repeating tasks per encounter | **1** |
| Worst-case particles/tick per encounter | 66, computed from configuration |
| Teardown | 82 ms for both worlds; folders gone, 0 leftovers after the sweep |
| TPS delta under two concurrent encounters | -0.15 |

Every ability is driven by the run's single ticker rather than nested `runTaskLater` calls. That is not
style: a telegraph scheduled with a delayed task outlives the fight that scheduled it, and an ability
landing in a world that has already been deleted is exactly the leak this may not have. Cooldowns and
wind-ups are timestamps; when the run ends its one task is cancelled and there is provably nothing pending.

### Verification

`/ashfall colosseum verify` — **128 checks, 0 failures** on staging. Configuration and boss identity; every
single-shot money guarantee attempted twice; insufficient funds at the moment of charging; the daily
allowance including losses and admin tests not consuming it; leaderboard integrity including an admin test
never reaching it; the full state capture round trip (damaged enchanted tool, full stack, empty slots,
absorption, flight, velocity, effects); bounded reward tables over 200 rolls; interrupted-run recovery run
twice for idempotency; instance isolation, two concurrent instances of one arena, boundary rejection, world
rules, all three bosses spawning and being removed cleanly, deletion, orphan cleanup and the sweep.

Regression, after: `/ashfall selftest` 55 checks with zero failures; `/ashfall duelmap verify` 197 lines,
no failures; `/ashfall duelmap canary` PASSED including the cross-restart snapshot proof.

**One assertion was wrong and was corrected rather than removed:** Paper hands an unset slot back as
`ItemStack.empty()`, not `null`, so "empties survive as null" failed on a round trip that was in fact
perfect. It now asserts what actually matters — the array keeps its length, so nothing shifts index, and an
empty slot comes back empty rather than holding somebody else's item.

### One bug the automated suite did not catch, and how it was found

The suite passed at 125/125 before this, so the remaining budget went on failure-path work rather than
features: a fake `colo_inst_*` folder with real region files was planted on disk while the server was down,
along with a `colosseum_runs` row in exactly the state a dead process leaves behind — `ACTIVE`, charged,
$500,000. Boot cleaned the orphan folder and refunded the player correctly.

But the `bank_ledger` had no matching row. `refundIfCharged()` (the live path) gave the fee back through
`refundServerPayment`, which debits the Central Bank; `recover()` (the boot path) credited the player with
`changeBalance` directly. **A crash mid-encounter refunded the fighter and left the fee sitting in the
Central Bank as money nobody had paid.** Two implementations of one operation, and the second was wrong.

There is now one `refundFee()` used by both, and a verify check that takes the fee the way a real encounter
does and asserts the bank is debited by the same amount it credits the player — which is the check that
would have caught it. `/ashfall colosseum verify` is now **128 checks, 0 failures**.

Worth stating plainly: this was found by simulating the failure on disk, not by reading the code or by
running the suite. A green suite proves the things it thought to ask about.

### Stability

Fifteen instance create/destroy cycles over five rounds of three concurrent instances: zero leftover
folders, zero live instance worlds, zero errors in the log, TPS flat at 19.6-19.7 throughout.

A clean `stop` issued with three instances live: all three worlds unloaded and all three folders deleted
during `onDisable`, none survived. Nothing has to be swept at the next boot, and the boot sweep confirms it.

### What still needs a human

Everything above is asserted without a player. What cannot be, and is the manual acceptance list:

- The feel of each fight in real gear — whether the telegraphs read clearly and the time limits are right.
- The GUI and confirmation screen as rendered on Bedrock through Geyser.
- A real death, a real `/colosseum leave`, and a real mid-fight Alt-F4, end to end.
- A live restart during a committed encounter, to see the refund arrive on rejoin.

### Files, schema and config

New: `ColosseumService`, `ColosseumArenas`, `ColosseumBosses`, `ColosseumVerify`, `colosseum.yml`.
Changed: `Database` (three tables, accessors, selftest), `DuelMapService` (filesystem primitives opened for
reuse; no behaviour change), `SMPCore` (service, commands, admin family, autocomplete, help, selftest),
`SettingsService` (the `COLOSSEUM` confirmation kind), `GameplayListener` and `GraveService` (guards),
`plugin.yml`, `deploy/manifest.yml`.

Schema, created automatically on boot: `colosseum_runs` (the lifecycle ledger with the compare-and-set
flags), `colosseum_stats` (per player per boss), `colosseum_state` (pre-entry capture, deliberately its own
table so a Colosseum bug can never restore somebody into a duel's capture).

Two new deploy artefacts, both now in `deploy/manifest.yml`: `plugins/SMPCore/colosseum.yml` and
`plugins/SMPCore/colosseum-templates/`. **Neither travels with a jar swap.**

### Commands

Player: `/colosseum` (menu), `/colosseum <boss>`, `/colosseum stats [player]`, `/colosseum top [boss]`,
`/colosseum leave` (warns once, then forfeits). Permission `smpcore.colosseum`, default true.

Admin, under the existing ADMIN gate: `/ashfall colosseum list|create|enter|exit|save|setspawn|setboss|
test|instances|drop|orphans|reload|verify|bench`, with live autocomplete for arena ids, boss ids, instance
world names and spawn roles. `/ashfall help colosseum` prints the whole surface.

---

## Session: 2026-09-02 (part 2) - Prism reset: 36.4 GB to a bounded ~0.2 GB

### What the 36.4 GB actually was

Read-only analysis of the old database before touching it. 92,756,899 rows, 36.39 GB, 14.70 days of data
(2026-08-18 23:06 to 2026-09-02 15:51), averaging ~421 bytes per row including indexes.

| Action | Rows | Share | Est. size |
|---|---:|---:|---:|
| `vehicle-exit` | 30,964,518 | 33.4% | 12.15 GB |
| `vehicle-ride` | 30,962,708 | 33.4% | 12.15 GB |
| `entity-death` | 30,044,704 | 32.4% | 11.79 GB |
| `block-break` | 382,868 | 0.4% | 0.15 GB |
| `item-dispense` | 161,657 | 0.2% | 0.06 GB |
| everything else (21 actions) | 240,444 | 0.26% | 0.09 GB |

Top causes: `(none)` 62,950,758 and `fall` 29,705,553. Top affected entity types: `oak_boat` 61,925,505 and
`creeper` 29,782,010.

**99.1% of the database was one creeper farm** - boats being entered and exited, and creepers hitting the
bottom of the drop shaft at ~6996,147,6819. Hoppers were never involved: `item-insert` was 28,082 rows in
fifteen days, `item-remove` 26,564, `item-pickup` 47,859. Together 0.1%.

Measured growth: 6.3M rows/day (2.48 GB/day) lifetime; over the last seven days 3.13M rows/day (1.23 GB/day)
with a peak day of 8.96M rows (3.51 GB). **A seven-day retention alone would have settled at 8.6 GB, and
24.6 GB at the peak rate** - which is why retention alone was not the answer.

### What changed

**`plugins/prism/prism.conf`** (backed up first - see below):

- `vehicle-exit: true -> false` and `vehicle-ride: true -> false`. 66.8% of all rows, every one an oak boat
  in the farm. `vehicle-break` and `vehicle-place` stay ON: those are how a player loses a boat or minecart,
  which is a real grief report.
- New filter `ignore-environmental-mob-deaths`, IGNORE, on `actions=[entity-death]` with
  `named-causes=[cramming, decay, drowning, dryout, fall, "fire tick", lava, suffocation]`.

  Keyed on the CAUSE, not on the mob, and that distinction is the whole point: a creeper a player kills
  still has a cause player and is still recorded, as are boss kills, named mobs, and anything that matters
  for a grief or rollback report. Only the 29.7M rows of mobs dying to the farm disappear. `entity-death`
  itself stays ON - disabling it outright would have thrown away player kills with the farm noise.
- Retention `before:6w -> before:7d`, nightly cron unchanged.

  Six weeks was never wrong in principle and had simply **never fired**: the oldest data was always younger
  than the window, so the scheduled purge had deleted nothing in the plugin's entire life while the database
  grew to 36.4 GB.

**Deliberately left ON**, because they are the audit trail and cost almost nothing: `block-break`,
`block-place`, `block-form`, `block-harvest`, `block-use`, `item-insert`, `item-remove`, `item-pickup`,
`item-drop`, `item-destroy`, `item-throw`, `item-trade`, `item-use`, `item-dispense`, `player-death`,
`entity-death` (player-caused), `entity-place`, `entity-remove`, `sign-edit`, `bucket-fill`, `bucket-empty`,
`hanging-*`, `vehicle-break`, `vehicle-place`, `raid-trigger`.

### Files deleted

Exactly three paths, all under `plugins/prism/`:

- `prism.db` - 39,078,596,608 bytes (36.39 GB)
- `prism.db-wal` - already absent (SQLite removes it on a clean close, which also confirmed the shutdown was
  clean)
- `prism.db-shm` - already absent, same reason

Nothing else was touched. `prism.conf`, `storage.conf`, `prism.lock`, `libs/`, `locale/`, the plugin jar,
all worlds, all SMPCore data and every other plugin were left exactly as they were.

Free disk went from **55.2 GB to 91.6 GB**.

Production was already cleanly stopped at 16:09:31 when this began, so no restart announcement was owed and
none was made. Before deleting, an exclusive open on `prism.db` was taken to prove the JVM had released it.

### Backups

Configuration only - the 36 GB database was deliberately NOT backed up, on instruction:

- `C:\Ashfall-Development\security-backups\prism-config-20260902-162617\prism.conf` and `storage.conf`
- `config-templates/prism/prism.conf.pre-reset-20260902` and `storage.conf.pre-reset-20260902` (in Git)

### Projection

With the filters applied to the measured historical composition, the surviving traffic is ~1,103,700 rows
over the 14.70 days analysed:

- **~75,000 rows/day, ~31 MB/day**
- **seven-day steady state ~525,000 rows, ~0.22 GB**
- at the *peak* observed day's composition, ~107,000 rows/day -> ~0.31 GB over seven days

That is a ~150x reduction and is predictably bounded: one nightly purge removes roughly a day's worth, far
inside the existing 5,000-per-batch / 2-second-cycle purge budget.

**No database backend change is needed.** SQLite is entirely comfortable at 0.2-0.3 GB; the 36 GB figure was
never a backend problem, it was 99% avoidable rows. If the workload ever changes enough to make this
untrue, that is a separate conversation before anything is installed or migrated.

### Monitoring added

`storage-guard.ps1`, in both server directories, launched by the launchers with `start /b` alongside the
console guard and freeze watchdog. Every 15 minutes it records Prism's size and free disk to
`logs/storage-guard.log`, and on a *change* of state it warns in-game over RCON so the alert is noticed
rather than becoming wallpaper.

| | Warn | Alarm |
|---|---|---|
| `prism.db` | 2 GB | 5 GB |
| free disk | 40 GB | 20 GB |

The 2 GB warning is about ten times the expected steady state - clear of normal variation, and still weeks
of runway. It writes to a file and never to the console, because console output is exactly what a blocked
console stalls on (see the 2026-09-01 entry).

### Also fixed

`restart-server.bat` now ends with `exit`. Windows `start` runs a `.bat` through `cmd /K`, which keeps the
shell open after the script finishes, so every watchdog-driven restart left an idle
`cmd /K restart-server.bat` window behind permanently. One from the 04:10 restart was still sitting there
twelve hours later. `exit` (not `exit /b`) terminates it.

### Verification after restart

- Prism created a clean database and loaded the filter: `Loaded filter ignore-environmental-mob-deaths
  (IGNORE). Total filters: 1`
- No SQLite or WAL errors. The only ERROR lines in the boot are GrimAC's pre-existing SLF4J notices.
- `/ashfall selftest` 53/53, zero failures
- All three supervisors running, exactly one each: console guard, freeze watchdog, storage guard
- `storage-guard.log`: `OK  prism.db 0 GB  free disk 91.6 GB`
- Disk 91.6 GB free, up from 55.2 GB

### Non-Git production changes made here

All are host files, all now in `deploy/manifest.yml`:

- `plugins/prism/prism.conf` - actions, filter, retention (config backed up as above)
- `storage-guard.ps1` - new, both servers
- `start.bat` / `start-staging.bat` - launch the storage guard
- `restart-server.bat` - trailing `exit`

---

## Session: 2026-09-02 - Host-wide lag incident: Prism's 36 GB database (read-only audit)

### Confirmed cause

**`plugins/prism/prism.db` is 36.39 GB across 92,756,837 rows in `prism_activities`, on a 475.7 GB disk with
22.6 GB free.** Production's JVM had written **307.95 GB in the 11 hours** since it started - a sustained
~8 MB/s of SQLite WAL churn against a 36 GB file. That saturates the laptop's disk, which is why the whole
machine was slow, not just the server.

Prism's retention is `prism purge start before:6w`, scheduled daily and enabled. The server's data begins
around 22 July, so **at the time of the incident nothing was yet six weeks old and the purge had never
deleted a single row.** The database grew unbounded from day one; the retention policy was correct in
principle and had simply never fired.

What feeds it: `block-break`, `block-place`, `block-form`, `block-harvest`, `block-use`, `entity-death`,
`entity-remove`, `item-insert`, `item-remove`, `item-pickup`, `item-drop`, `item-destroy`, `item-throw`,
`item-trade`, `item-use`, `player-death` and more, all `true`. On this server that means every Industrial
Hopper transfer (nine items a cycle), every stacked-spawner death and every container touch becomes rows.

Compounding it, from the 2026-09-01 watchdog dump: Prism's `EntityDeathListener` constructs a **complete new
entity, AI `Brain` and all**, for every entity death purely to serialise its NBT
(`NbtService.processEntityNbt` -> `CraftRegionAccessor.createEntity`). At the observed ~9,700 creeper deaths
a day that is significant CPU on top of the write volume.

### Why the server looked fine while the machine did not

`tps` reported **20.0 / 20.0 / 20.0** across 1m, 5m and 15m throughout. Prism commits off the main thread, so
the tick loop never suffered - the damage was entirely in host I/O. That is also why the only genuine lag
symptom in the logs is a network one:

```
[15:30:37] MacoCT was kicked due to keepalive timeout!
[15:30:47] MacoCT lost connection: Timed out
```

A starved machine cannot service keepalives on time even at 20 TPS.

### The mass kick was NOT lag

Two separate events, neither caused by the database:

- **04:10:11 - a deliberate restart.** `[STDOUT] [org.spigotmc.RestartCommand] Attempting to restart with
  C:\MinecraftServer\restart-server.bat`, then Asserto and TPKIID `lost connection: Server is restarting`.
  TPKIID ran `/restart` at 04:10:10 while trying to clear MacoCT's AuthMe IP ban (`/unban macoct` 04:06:04,
  `/unban ip macoct` 04:06:23 - neither clears an IP tempban; `pardon-ip` does). The server was back at
  04:10:29.
- **13:32:44-45 - all three players `lost connection: Disconnected` inside one second.** That reason is a
  client/network-side drop, not a server kick.

### Did the console/host changes contribute? No - and each is accounted for

| Change | Effect on this incident | Evidence |
|---|---|---|
| `console-guard.ps1` | none | running as pid 19884, 92 MB; did not appear anywhere in a live 6-second CPU sample (the only PowerShell above 1% was the tooling host). One WMI query per 5s. |
| `freeze-watchdog.ps1` | none - **it was not running** | exited 04:23:46 when its console closed. Production has been unwatched since. See below. |
| `-Dlog4j2.AsyncQueueFullPolicy=Discard` | strictly reduces work | never blocks a producer; cannot add load. |
| `log-named-deaths: false`, `log-villager-deaths: false` | strictly reduces work | total production `logs/` is 8.1 MB against Prism's 37 GB. |
| ReplayCore jar moved out of `plugins/` | strictly reduces work | 0 occurrences of `ReplayCore` in the current log. |
| `restart-script` -> `restart-server.bat` | **positive** | it is what made `/restart` work at 04:10. Under the previous `./start.sh` the isFile() check fails, so that `/restart` would have taken production down permanently instead of for 19 seconds. |

Console state at audit time: window title `Ashfall Concord [PRODUCTION]` with **no `Select ` prefix**, and
the guard log shows the mode held at `0x89`. No console blockage recurred.

### Other things checked and found clean

- **One** production JVM (34128 wrapper -> 11456, started 04:23:42). No staging server running, so nothing
  was competing with production. No duplicate servers, no restart loop.
- No new `hs_err_*`, no heap dumps, no crash reports; the only one on disk is from 2026-08-22.
- No Paper watchdog events, no `Can't keep up`, no `stopped responding` anywhere today. A thread dump was
  therefore not taken - at 20 TPS with no watchdog trip there was nothing for it to show.
- Disk latency at audit time 0.1 ms write / 0.4 ms read, queue length 0 - with zero players online and Prism
  therefore near-idle. The write volume figure above is what matters, not the instantaneous latency.
- Pagefile 13.9 GB allocated, 2.8 GB used, 5.4 GB peak. RAM 31.9 GB with 7.8 GB free. Not memory-bound.
- Running jar confirmed by hash: `plugins/SMPCore.jar` md5 `8e9d293b4d79cc1d5f3a0df46915a035`, identical to
  the built `SMPCore-1.7.0.jar`.

### Two loose ends found during the audit (not causes, not yet fixed)

1. **Production has no freeze watchdog running.** It exits with its console by design, and the 04:23 restart
   left it stopped. It restarts with the next server start; until then the 5-minute hang recovery is absent.
2. **One idle `cmd /K C:\MinecraftServer\restart-server.bat` (pid 31288)** left over from the 04:10 restart.
   `start` runs a `.bat` under `/K`, which keeps the shell open after the script finishes. Harmless, but it
   accumulates one per watchdog-driven restart.

### Smallest safe fix

Purge Prism's history. Nothing else needs to change and no code is involved:

```
prism purge start before:7d --nodefaults
```

That is the existing, supported mechanism, already scheduled nightly - it has simply never had anything old
enough to remove. Reclaiming ~36 GB removes both the disk pressure and the I/O cost of writing into a file
that size. Follow it with a `VACUUM` to return the space to the filesystem, which SQLite does not do on
delete alone.

If the write rate is still too high afterwards, the next lever is narrowing what Prism records - `item-insert`,
`item-remove` and `item-pickup` are the ones a hopper-heavy server generates most of - and only then
shortening the retention window from six weeks.

### Rollback

Nothing was changed during this audit, so there is nothing to roll back. The fix above is reversible in the
sense that matters: a purge deletes history older than the chosen window and cannot affect live world data,
player data or the SMPCore database.

---

## Session: 2026-09-01 (part 2) - Hopper rate, void-world sealing, /ec case, mob-drop shop audit (staging only)

### A plain hopper must move at a plain hopper's rate

Reported: "Normal Hopper -> Industrial Hopper makes the Normal Hopper magically move nine items at once."
It did. `pullFromAbove` treats anything overhead with an inventory as a source and drains it at the bay's own
budget, so the nine-item buff leaked onto whatever was feeding it.

The rule now: the buff belongs to the Industrial Hopper and to nothing else.

- Inbound pushes are re-performed at `event.getItem().getAmount()` - exactly what vanilla was about to move,
  i.e. `settings.hopper-amount` - instead of the configured nine.
- `pullFromAbove` leaves a plain hopper alone when it faces down into us (it is already pushing, and that
  push is now rate-correct), and otherwise pulls at most `industrial-hopper.vanilla-hopper-amount`.

**This produced a stand-off that only a live test could find.** Both halves stepped aside for each other:
`moveItem` deferred to the sweep for anything directly overhead, and the sweep deferred to `moveItem` for a
plain hopper. Nothing moved at all - 33 items parked in a vanilla hopper with an empty Industrial Hopper
underneath it. Ownership of each inbound link is now decided in one place, `sweepDrains()`, and the live
suite has an eleventh case asserting both that all 64 arrive AND that they arrive at eight ticks per item.

### Void worlds: sealed

- **Dying inside no longer ejects you.** `PlayerRespawnEvent` respawns you on the event world's platform,
  still holding your capture. Vanilla was sending the corpse to its spawn point, which is in the overworld -
  real belongings were never at risk, but the state was plainly wrong. No graves, because `keepInventory` is
  on in these worlds and a death drops nothing for a grave to hold.
- **Arriving by teleport is entering.** `PlayerChangedWorldEvent` runs the same capture the command does.
  The first attempt shared `enter()` wholesale and did not work: `enter()` refuses when you are already
  inside, which by then you always are, so it returned before capturing and the arrival kept a real
  inventory. The mechanism now lives in `absorb()`, which both routes call; `enter()` keeps only the
  command's own preconditions.
- **Leaving by teleport is exiting**, through the same restore, so an admin dragging somebody out cannot
  strand them holding event items.
- **Closed by default, and closed in both directions.** `/voidworld open|close [name]`, persisted per world
  in the state table and cleared when the world is deleted. A non-administrator cannot enter a closed world
  by command OR by being teleported into it - blocking only the escape would leave "teleport to a friend who
  is already inside" wide open.
- **No way out except the way in.** Every teleport out of an event world by a non-administrator is cancelled
  unless the service itself is making it, which covers commands nobody listed and plugins added later. The
  command denylist (`/spawn`, `/home`, `/tpa`, `/f`, `/back`, `/warp`, ... ) exists so the refusal comes with
  "To leave the void world, type /voidworld exit." rather than as a silent cancel. Movement *within* the
  world is untouched.
- `voidWorlds.shutdown()` was written but never called from `onDisable`. It is now.
- Entry and exit strip the cursor and close any open screen as well as the inventory - a crafting grid is
  real storage and was the obvious way to carry an event item out.

### /ec inspect is case-insensitive again

`/ec inspect xfpu` failed where `/ec inspect xFPu` worked. The account row was always found
case-insensitively (ids are lowercased), but the OFFLINE path passed the typed string to
`Bukkit.getOfflinePlayer`, which on an offline-mode server derives a UUID by hashing that exact string - so
the two spellings were two different players and only one had a data file. It now passes the stored
canonical name from the row it just found. Still an exact full-name match; no partial matching was added and
nothing else about the command changed.

### Every vanilla mob drop the shop was missing

Extracted from `data/minecraft/loot_table/entities/*.json` in the running Paper build, following
`loot_table` references (a guardian's rare pool rolls the fishing table; sheep have per-colour tables), so
nothing one level down was missed. 81 distinct droppable items, 48 already priced, **33 missing**. 31 added
as commodities, `WET_SPONGE` added as a luxury at the existing `SPONGE` price, and `TIPPED_ARROW`
deliberately excluded - its identity IS its potion data, so the ordinary-item rule rejects every real one
and a bought one would be a blank arrow.

Plus `STICK`, `BOW` and `COOKED_RABBIT`, none of which are loot-table entries: the first two because the bow
was asked for by name, the third because a rabbit killed by fire drops the cooked form.

**The bow is priced from its recipe:** 3 string (9.60) + 3 sticks (0.72) = 10.32, plus a 20% crafting
premium = **12.38 sell / 61.90 buy**. 20% is the middle of the 15-25% band and is chosen so the bow's buy
price stays above the 51.60 its ingredients cost - below about 15% the two cross and the shop undercuts the
crafting table. No profit loop exists in either direction, asserted in `/ashfall selftest`.

**Damaged gear can be sold; enchanted gear cannot.** A skeleton's bow arrives with durability spent and
nothing else different, which the "identical to a fresh one" rule rejected. `allow-damaged: true` is opt-in
per shop entry and forgives damage AND NOTHING ELSE: the test clears the damage and then demands what
remains be indistinguishable from a fresh item, so an enchanted, named or relic bow still fails. Every quote
site and every removal site uses the same predicate, so a quote can never accept an item the removal cannot
find. `BOW` is currently the only entry that opts in.

Two priced-on-merit decisions undercut existing luxuries and were reported rather than bent: `BREEZE_ROD`
(4 wind charges per rod vs the 5,000 `WIND_CHARGE` luxury) and `WITHER_SKELETON_SKULL` (3 skulls per wither
vs the 500,000 `NETHER_STAR` luxury). Both are one edit away from moving if the owner prefers.

### Verification

`/ashfall selftest` all green, including four new lines (bow recipe and no-profit-loop, damaged-gear opt-in
with enchanted bows refused, stacked-mob conservation, void-world naming). `/ashfall hopper livesuite` - all
11 live cases conserved every item on every tick, including the new plain-hopper-above case at 8 ticks per
item. `/ashfall hopper verify` - no failures.

---

## Session: 2026-09-01 - Production console stall (22h 35m), and the fixes for it

### What happened

Production stopped ticking at **23:08:47 on 2026-08-31** and stayed stopped until the console was cleared by
hand at **21:44:15 on 2026-09-01** - 22 hours 35 minutes. Players tried to join all day and could not.

A text selection was active on the production console window. Windows blocks console writes while a
selection is held; that blocked Log4j's appender, and the server thread blocked on its next log call. Paper's
watchdog fired ten seconds later at 23:08:57 - and a watchdog dump is itself written to the console, so it
blocked partway through and never reached the `timeout-time: 60` halt that would have killed and restarted
the JVM. **The safety net was disabled by the very thing it was trying to report.**

### Evidence

- `logs/2026-08-31-1.log.gz` ends with normal creeper-farm output at 23:08:47, then a single line at
  23:08:57 - the CLOSING banner of a watchdog dump whose body never reached disk, which is what a blocked
  appender looks like.
- `spark` confirmed the stall independently from its own thread: `Timed out waiting for world statistics`
  once a minute, 23:09:05 through 23:14:05, then silence.
- Across the whole outage only ten lines reached the log, all `UUID of player` from login threads.
- It ended within seconds of the selection being cleared, and the server thread was demonstrably ALIVE
  immediately after: two dumps one second apart (21:44:15, 21:44:16) show two DIFFERENT stacks. A deadlock
  shows the same frame; clearing a mouse selection does not release a deadlock.
- No JVM crash artifacts, no OOM.

### Contributing factor

9,694 of the 10,704 log lines that day - **90.6%** - were `Named entity ... died` from one Volatile Creeper
farm at ~6996,147,6819. One 300-character console write every few seconds is what turns a stray click into an
outage within seconds instead of something harmless.

### Why the existing guard did not help

`start.bat` already ran `disable-quickedit.ps1`, and it ran, and the selection happened anyway. Measured on
staging with that script having just run: the console mode at JVM start was **0x1F7** - QuickEdit bit still
set. And fifteen seconds later JLine set its own mode when Paper initialised the terminal (**0x9**, extended
flags cleared), after which nothing re-asserted anything. A one-shot pre-JVM fix cannot win that race.

### Exact changes

All are host files, none Git-tracked; every one is now in `deploy/manifest.yml`.

| File | Change |
|---|---|
| `spigot.yml` (both servers) | `log-named-deaths: true` to `false`; `log-villager-deaths: true` to `false` |
| `spigot.yml` (both servers) | `restart-script: ./start.sh` to each server's ABSOLUTE path to `restart-server.bat` |
| `restart-server.bat` (new, both) | what the watchdog runs after a halt; waits 15s, then relaunches the visible console |
| `console-guard.ps1` (new, both) | runs ALONGSIDE the JVM, re-asserting the console mode every 5s |
| `start.bat` / `start-staging.bat` | launch the guard with `start /b` after the existing pre-JVM script |
| `deploy/manifest.yml` + `check_deploy.py` | track all of the above; new `staging_only` section and `ignore_keys` support |

`./start.sh` was a Linux path on a Windows host. Spigot will not run a restart script it cannot stat
(`RestartCommand` checks `new File(script).isFile()`), so the restart had never been capable of running -
the watchdog's halt, had it been reached, would have left production down anyway.

### Verification (not assumption)

- **Guard, from its own log:** `QuickEdit re-disabled: mode 0x1F7 -> 0x1B7` at startup, then
  `QuickEdit re-disabled: mode 0x9 -> 0x89` fifteen seconds later when JLine changed it. Both re-asserts are
  real; without the guard the console sits in whatever state JLine left it.
- **Guard, from OUTSIDE the process:** a separate process detached from its own console, attached to the
  staging server's console and read the mode directly while Java was running: **0x89 - QUICK_EDIT OFF,
  EXTENDED_FLAGS ON. PASS.** Note that 0x9 also has the QuickEdit bit clear and is still unsafe: without
  EXTENDED_FLAGS the console falls back to the registry default, which is why the guard pins both.
- **Restart script, end to end:** staging was stopped and Spigot's exact invocation issued
  (`cmd /c start <abs path>`, via `ProcessStartInfo` so the command line matches `Runtime.exec` byte for
  byte). The server came back on its own, unattended.

Three real defects were found by running these rather than reading them, and all three are fixed:

1. `0xC0000000` parses as a signed Int32 in Windows PowerShell 5.1, so `CreateFileW` received a negative
   number and the guard died before logging anything. Pinned to `[uint32]`.
2. `"%~dp0"` ends the argument with a backslash before the closing quote and escapes it, so
   `start /D "%~dp0"` silently did nothing. Uses `"%~dp0."` now.
3. `ping -n 16 127.0.0.1` took **79 seconds** for a 15-second wait, because the firewall hardening drops
   loopback ICMP and every echo sat out its full timeout. Replaced with `waitfor /t 15`, which needs neither
   the network nor stdin (`timeout.exe` refuses to run when stdin is redirected, which is exactly the state a
   process launched by a dying JVM can be in).

### Rollback

Each piece is independent and reversible on its own; none requires a rebuild.

1. **Log volume:** set `log-named-deaths` and `log-villager-deaths` back to `true` in `spigot.yml`, restart.
   Reverting this alone re-opens the exposure that made the outage fast, so revert it last if at all.
2. **Restart script:** set `settings.restart-script` back to `./start.sh` (or empty to disable), restart. The
   watchdog then halts without restarting, which is the pre-2026-09-01 behaviour.
3. **Console guard:** delete the `start /b ... console-guard.ps1` line from `start.bat` /
   `start-staging.bat` and restart. `console-guard.ps1` and `restart-server.bat` can be left in place -
   nothing invokes them once the launcher line is gone. A running guard can be stopped immediately by ending
   the PowerShell process whose command line contains `console-guard`; it also exits by itself two minutes
   after the last server process disappears.
4. **Deploy tooling:** the `staging_only` and `ignore_keys` handling in `check_deploy.py` is additive;
   removing the manifest sections disables it without touching anything else.

### Not changed, deliberately

The watchdog dump also surfaced two things that are NOT part of this fix and were left alone on instruction:

- **Prism** constructs a complete new entity, AI `Brain` and all, for EVERY entity death, purely to serialise
  the dead one's NBT (`EntityDeathListener` to `NbtService.processEntityNbt` to
  `CraftRegionAccessor.createEntity`). At ~9,700 creeper deaths a day that is a real per-death cost.
  Third-party; unchanged.
- **SMPCore** trips Paper's excessive-velocity warning in `RelicService.windBurst` (Skyward Anchor burst,
  y = 4.2498 against a threshold of 4.0). Cosmetic log noise, unrelated to the stall. Unchanged.

### Standing lesson

A console the server writes to synchronously is a single point of failure for the whole server, and a
watchdog cannot report a fault whose symptom is that reporting is blocked. Keep console output low, keep the
guard running, and treat `Select ` in a server window title as an outage in progress.

---

## Session: 2026-08-31 (part 2) - Temporary void worlds, live hopper rig (staging only)

### Temporary void worlds

`/ashfall voidworld <create|enter|exit|list|delete> [name]`, with tab completion on every verb and on the
real world names for `enter`/`delete`.

The rule is absolute in BOTH directions: nothing goes in, nothing comes out. Enforced by capture-and-clear
on entry and clear-and-restore on exit -- the same shape the duel arena uses, and for the same reason: it is
the only model where a crash cannot merge the two inventories.

- The capture lives in the DATABASE, not memory, so a restart mid-event cannot strand a real inventory.
- Log out inside and the capture survives; `join` restores you even if the world was deleted meanwhile.
- `delete` evacuates everyone first, then unloads and removes the folder.
- Every world this service touches carries an `ashfall_void_` prefix, and the name is sanitised to
  `[a-z0-9_]`, so a mistyped delete can never be aimed at the overworld or a duel instance. Asserted in
  `/ashfall selftest`, including that `../../etc` sanitises to a harmless name.
- Generator produces nothing at all -- no terrain, decorations, structures or mobs -- with a 9x9 platform at
  spawn, because a void world with no floor drops the first entrant straight out of the bottom.

### The Industrial Hopper chain, tested live

The previous rig drove `sweep()` by hand, which is the one thing a real server never does. `/ashfall hopper
live [amount]` now builds the exact reported column out of **genuinely ticking blocks in a force-loaded
chunk**, inserts the payload through `Inventory#addItem` (the ordinary path a player's click takes), and
then does nothing -- the plugin's scheduled sweep and vanilla's hopper tick move the items, so
`InventoryMoveItemEvent` and the suction event fire for real. It samples every tick, because "right before
and after" is not the same statement as "never wrong".

**Result: 64/64 delivered, conservation held on every one of 65 ticks, nothing on the ground.**

Code audit alongside it, all of which came back clean:

- Every `getState` call in the service is `getState(false)`. There is no snapshot-then-`update()` path, so
  the classic Bukkit hazard of a stale tile-entity write clobbering the PDC marker does not exist here.
- Startup adoption is present and correct: the constructor walks every already-loaded chunk on the first
  tick, so hoppers in spawn chunks -- up before the plugin enables -- are registered. That was the leading
  theory for "some hoppers work and others do not" and it is wrong.
- `chunkUnload` flushes and drops the bay; `chunkLoad` re-adopts by PDC marker. Symmetric.
- The calibration weight is PDC-tagged, so real items and comparator scaffolding are never confused;
  `drainNative` rescues anything real that reaches the native five, and `refreshComparator` only ever writes
  into empty slots.

So the reported loss is not in the transfer arithmetic, not in the event interception, and not in
registration. `industrial-hopper.trace` remains available: enable it, reproduce by hand, and the log names
the exact cycle where the total changes.

---

## Session: 2026-08-31 — Hopper chain rig, raw food prices, stacked-mob audit (staging only)

### The Industrial Hopper chain could not be made to lose an item

Built the exact reported rig — chest → hopper → chest → hopper → chest — as a permanent regression test
(`/ashfall hopper verify`). Five payload scenarios, conservation asserted across **all five blocks plus
ground items after every single cycle**, because counting them one command at a time is not a measurement:
at nine items a cycle the contents move between the reads.

| Scenario | Result |
|---|---|
| Full stack of 64 | all 64 arrive in 9 cycles |
| 16-stack item (egg) | all 16 arrive in 2 cycles |
| Unstackable (diamond sword) | arrives in 2 cycles |
| Destination nearly full (1708/1728) | 20 delivered, 44 correctly held in h2 |
| Destination completely full | payload held in the chain, nothing eaten |
| Chunk/serialise round trip mid-flight | total intact, chain completes afterwards |

**No duplication, no deletion, no single-item stranding in any of them.** The first run did report a
failure, and it was my assertion, not the code: a 27-slot chest tops out at 1728, so "nearly full" at 1708
has room for twenty, not sixty-four.

So the loss is not in the transfer arithmetic the sweep drives. What the rig **cannot** drive is vanilla's
own hopper tick — `InventoryMoveItemEvent` and the suction event only fire on a live ticking server, and a
manual insert goes through those. Rather than guess, `industrial-hopper.trace` (off by default) now logs
every movement with the counts either side, so a live reproduction produces the exact cycle where the total
changes instead of another theory.

### Raw meat and fish

Added `CHICKEN`, `BEEF`, `COD`, `SALMON`, `MUTTON`, `PORKCHOP`, `TROPICAL_FISH`, `PUFFERFISH`. Priced by the
owner's crafting-tax rule read backwards: cooking is a craft, so cooked = raw + tax, and raw = cooked / 1.125
at the midpoint of the stated 10–15% band. Sell stays at the 20% of buy every other food row uses. Tropical
fish and pufferfish have no cooked form to derive from, so they are placed against their nearest neighbours
by use rather than by an invented rule.

`/ashfall selftest` now asserts the raw→cooked ratio stays inside 10–15%, so the two sets cannot drift apart.

### 100k stacked-mob lag — audit and recommendation (NOT implemented)

`SpawnerService.stackedDeath` multiplies drops at kill time. Three cost centres, worst first:

1. **`for(ItemStack item:complex) for(int i=0;i<stack;i++) drops.add(item.clone())`** — O(stack) allocations
   on the main thread. A 100,000 stack with one enchanted drop is 100,000 clones in one tick. This is the
   dominant cost and it is unbounded.
2. **Item entities.** 100k Blazes ≈ 1,563 stacks of rods; 100k Iron Golems ≈ 6,250 stacks of iron. Every one
   becomes a real entity with physics, merge checks and spawn packets, all in the same tick.
3. **XP orbs.** `setDroppedExp(exp * stack)` = 2,000,000 XP for a 100k Blaze stack, which vanilla splits into
   ~800 orb entities that then tick and path toward the player.

**Recommendation: keep the value identical, change only the delivery.** Compute the same totals, then hand
them to the killer directly — `CoreUtil.give` into the inventory with the existing claim-later stash for
overflow — instead of spawning entities, and replace the orb shower with a single `giveExp`. Same items, same
XP, no duplication surface (the totals are computed exactly as now), and the entity count drops from
thousands to zero. The `complex` list should also be merged and capped rather than cloned per unit.

This changes how loot arrives, so it is deliberately left unimplemented pending the owner's call.

### Still not done

Temporary void worlds.

---

## Session: 2026-08-25 (part 2) - PROMOTED: hopper collection stability, /duels layouts, opponent wager panel, advancement toasts

**PROMOTED 2026-08-25.** Silent restart (only MacoCT and Asserto online), booted in 60.7s, selftest 0
failures, hopper parity verified with no failures, 0 SMPCore errors, jar MD5 `e0e14529...` identical across
build output, staging and production. `check_deploy.py` 32/33 -- the one item being the four documented
staging-only `trusted-admin` keys and the extra staging sigil UUID. **No YAML was synced this time, by
design:** this batch changed only Java and the bundled `plugin.yml`, so there was nothing to copy, and last
session's config clobber had nothing to recur from.

### Two deploy traps hit while shipping this

**Two SMPCore jars in `plugins/`.** `deploy-to-staging.ps1` writes the VERSIONED name (`SMPCore-1.7.0.jar`);
production has always been promoted by hand to the unversioned `SMPCore.jar`. Staging ended up holding both,
and Paper logged `Ambiguous plugin name 'SMPCore'` and picked one without saying which. Tests run in that
state prove nothing about the build you think you are testing. Staging now holds exactly one jar; the deploy
check already asserts `exactly one` for production.

**A `schtasks /SC ONCE` launcher is a landmine.** The escape-hatch task used to get a visible console is
created with a run time later the same day, so it fires again at that time and launches a SECOND server into
a locked world folder. The staging task was deleted afterwards; the production one was **disabled**, not
deleted -- deleting a task whose instance is still running can take the running process with it, and that
process is production.

### The Industrial Hopper was destroying and re-spawning the pile on top of it

Reported: a full hopper that is also transferring downstream collects a few items whenever capacity opens,
and the leftover stack "keeps jumping around and items spill everywhere".

Two paths absorb floating items: the sweep's `collectItems`, and `pickup`, which intercepts vanilla's own
suction event. `collectItems` was already correct -- it takes only what fits and shrinks the entity's stack
in place. `pickup` was not:

```java
entity.remove();
for (ItemStack rejected : bay.inv.addItem(stack).values())
    entity.getWorld().dropItem(entity.getLocation(), rejected);
```

It removed the WHOLE entity, added the WHOLE stack, and re-dropped whatever the bay refused as a **brand new
item entity**. A full hopper pushing nine items a tick frees a slot or two every tick, so this fired every
tick: new entity, snapped back to the drop point, fresh pickup delay. That is the jumping. `dropAt`, the
last-resort refund, made it worse -- it dropped at the hopper's own block centre, which is inside the
funnel's collision, with `dropItemNaturally` adding a random throw on top. That is the spilling.

Both paths now share one `absorb(Bay, Item)`: measure capacity first, take only what fits, and **edit** a
partly-eaten stack rather than replace it. `dropAt` drops just above the block with no velocity.

Nothing else about the block changed -- 27 slots, nine per transfer, comparator behaviour, redstone lock and
the vanilla-parity fixes are all untouched.

**Three new tests**, `/ashfall hopper verify`. "partial collection by a full, draining hopper": a full hopper with
a chest beneath it and a 200-stack sitting on top, driven cycle by cycle. It asserts the pile is never
re-spawned as a new entity, never moves, never becomes two entities, is still drained as capacity opens, and
that `floor + hopper + destination` is unchanged on **every** cycle. "a full hopper whose output is
blocked" and "a redstone-locked hopper with items on top of it" cover the other two states the owner asked
about: in both the correct answer is to do nothing at all, and in both the pile must be left untouched
rather than nudged, partially eaten or re-dropped.

A fourth test lives in `/ashfall selftest` (Arena kit parity): the layout bijection, including four ways of
corrupting a saved permutation -- an index used twice, an occupied slot left unplaced, an index past the end
of the arrangement, and one pointing at an empty slot -- each of which must be rejected outright.

### The Market Axe opened the hopper it had just sold

Right-clicking an Industrial Hopper with the Market Axe sold the contents **and** opened the hopper. Two
handlers were both claiming the click: `ShardService` cancels the event and sells, `IndustrialHopperService`
cancels it and opens its own screen, and whichever ran first did not stop the other.

Against a vanilla container the cancel is enough, because vanilla is what would have opened it. This block
opens its own screen, so it now asks first: `ShardService.hasRightClickAction` covers the Market Axe, the
Haste token and cosmetic tokens, and `open()` steps aside for all of them.

### /duels -- kit layouts

Where each piece of a duel kit sits is now the player's choice, per kit, saved for every future duel. Click
an item, click where you want it. The editor is laid out like the inventory it configures: main rows on top,
hotbar underneath, armour and offhand shown greyed out because the kit places those.

Deliberately click-to-move rather than drag-and-drop: everything on that screen is a real netherite kit, and
the moment one is allowed onto the cursor, closing the window hands it over for keeps. Nothing leaves the
menu -- a click swaps two entries in an int array.

**What is stored is a permutation, never items.** For each of the 36 slots the row records which slot of the
kit's *default* arrangement belongs there, and it is only applied if it is a genuine bijection onto the
kit's occupied slots. A corrupt, stale or hand-edited row cannot add an item, remove one, or change what one
is; the worst case is that it fails validation and the default is used. A kit whose contents change
invalidates every saved layout for it automatically.

The default path is byte-identical to what it was: `defaultArrangement` reproduces the old
`setItem`-then-`addItem` sequence exactly, so anyone who never opens `/duels` gets the same kit as before,
including the golden-apple merge on the Mace kit.

### The opponent's wager, in the wager box

The box is now three regions: **0-26** your staging area, **27-35** the control row, **36-53** the
opponent's confirmed wager, read-only, repainted live when they confirm or clear.

The dangerous part is that those bottom slots hold real-looking items that are somebody else's escrow. Every
route from them to an inventory is closed:

- every scan of "what did this player stage" now stops at slot 27 (`confirmWager`, `clearWager`,
  `closeWager`) -- previously all three read 45 slots;
- slots 27-53 are click-locked, which also covers number-key swaps and offhand swaps aimed at them;
- the drag guard starts at 27 instead of 45;
- **double-click-to-gather is refused outright** -- it sweeps matching items out of every slot in the view,
  the opponent's panel included, and lands them on the cursor;
- shift-clicking from the player's own inventory is placed by hand into slots 0-26, because Bukkit's own
  shift-click would spread it across the whole top inventory, opponent panel included, where it would be
  silently destroyed on close.

Refreshes happen **in place**. Re-opening a wager box to refresh it would fire `closeWager` and empty the
other player's staging area under them.

### The advancement toast

The existing handler revokes a completed advancement on the tick it lands. That is too late for the popup,
too late for the advancement's rewards, and it strips **every** criterion on that advancement -- including
ones earned legitimately days earlier outside the arena.

Paper fires `PlayerAdvancementCriterionGrantEvent` one step earlier, before the criterion is written, and it
is cancellable. Verified against `paper-26.2.jar`: cancelling makes `award()` call `revokeProgress` and
return before `progressChanged.add`, so the advancement never completes, no reward is granted, nothing is
queued for the client, and there is no toast to suppress. Prior progress is untouched.

The revoke handler stays as the backstop. Recipe advancements are still exempt in both.

---

## Session: 2026-08-24 (part 5) - PROMOTED: combo damage floor, per-spawner golem allowance, relic hotbar readout

**PROMOTED 2026-08-24.** One silent restart (only Asserto online), booted in 35s, selftest clean, zero
SMPCore errors, jar MD5 identical to the staging-tested artefact. Staging stopped and consoles cleared
afterwards; exactly one Minecraft process chain left on the machine.

### The combo damage report, handled without another theory

Two confident wrong causes were given for "the combo makes my mace do LESS damage". The elytra explanation
was wrong -- the owner swaps to a chestplate before diving and the drop counter reads correctly all the way
down. Rather than produce a third guess, two things shipped:

1. **The handler is arithmetically incapable of lowering the number.** The struck target takes the largest
   of the untouched hit, the multiplied hit, and the slam that drop would have dealt anyway. Whatever the
   mechanism was, the symptom cannot recur.
2. **Telemetry** (`relics.yml` -> `log-combo`, on): one line per combo giving the tracked drop, vanilla's own
   fallDistance, gliding state, mace base damage, what was applied and the final damage after armour and
   boss toughness. Left ON in production deliberately -- the one remaining unreproduced sighting will now
   produce evidence instead of another round of speculation.

**Still open, and honestly unexplained:** one instance on staging where the combo looked interrupted, not
reproducible afterwards. The owner's own guess -- the Warded Colossus's gravity pull yanking them mid-fall,
which resets vanilla's fallDistance and therefore the tracked drop -- is plausible and is exactly what the
telemetry line will show if it happens again.

### Drop tracking follows vanilla's own signal

Requested: the fall must be continuous, "same as the mace". Rather than invent a second rule, the tracked
drop now restarts whenever vanilla clears its own `fallDistance` -- water, cobweb, ladder, being knocked
upward -- which is the same moment the mace loses its smash bonus. Ascending is exempt, since fallDistance
is legitimately zero on the way up while the peak is still rising.

### Golem allowance was a shared pool, and that was a real bug

`allowance = spawners x 2200`, minus everything spent across all of them. Adding a spawner therefore did
nothing for the rest of the day, because the new one inherited the others' overdraft.

Measured live before the fix: a third spawner added after 7,521 kills against a 4,400 pool contributed
**exactly zero**, and the recorded payout reconstructs to a 4,400 cap to within 0.03% (predicted 1,211,482
against a recorded 1,211,893).

Each spawner now carries **its own** allowance, and usage fills the spawners that still have headroom first,
so nothing is wasted topping up an exhausted one. A spawner added mid-day starts paying immediately.

### Smaller

- Combo launch scales with the mace's Wind Burst level: none/I/II/III -> 20/28/36/45 blocks, multiplied per
  target struck.
- The launch is applied one tick after the damage event. It was being set from inside the event, where
  vanilla overwrites the attacker's motion and resets fall distance the instant `hurt()` returns.
- Holding a relic shows its cooldown, or READY, on the hotbar text. While the Anchor is armed the drop
  counter takes over instead.
- The combo title card is now just the name and the blocks fallen; the targets/enchant/launch stat line was
  noise at the moment of impact.

---

## Session: 2026-08-24 (part 4) - PROMOTED TO PRODUCTION, and admin login persistence fixed

**PROMOTED 2026-08-24.** One silent restart (only Asserto online), booted in 43s, selftest clean, **zero
SMPCore errors**, jar MD5 identical to the artefact tested on staging. Staging stopped afterwards.

Carried: the jar, plus `relics.yml` (the whole Skyward Anchor block), `bosses.yml` (`elite-types`) and
`config.yml` (`trusted-admin.require-same-ip`). Config drift against staging is now only the documented
staging-only admin flags and the legacy sigil list.

### Admin login persistence: `require-same-ip` is not the IP check

**I got this wrong first and talked myself into a much bigger fix than the problem needed.** Recording the
mistake because the misreading is easy to repeat.

AuthMe's own session feature is `enabled: true, timeout: 30` -- same IP, thirty minutes -- and it is already
what every ordinary player gets. `RestoreSessionEvent` fires **only after AuthMe has validated that**: the
connection matches the address of the player's own last password login and the timeout has not expired.

`trusted-admin.require-same-ip` is a **second, much stricter constraint layered on top**, and its name is
what caused the confusion: it does not mean "same IP as last time", it means "same machine as the SERVER"
(`isThisMachine`: loopback or one of the server box's own NIC addresses). Admins connect from their own
machines, so it refused every time -- which is precisely why admin logins never persisted while everyone
else's did.

Set to **false** on production. That is not a weakening to "any address may skip the password": AuthMe's
same-IP, 30-minute check still stands in front of it, unchanged. It simply stops SMPCore adding a
requirement that only the server console could ever satisfy.

**What I nearly built instead, and did not:** a session-resume mechanism storing (account, address, time)
and re-validating it. It was a reimplementation of what AuthMe already does, sitting behind AuthMe's own
gate, and would have added a security surface for zero behavioural gain. Deleted unbuilt. The one thing kept
from that work is the **logging** -- a restore now records the address it came from, and a refusal explains
that AuthMe approved the session but `require-same-ip` rejected the machine. The silence was the only reason
this needed diagnosing rather than reading.

**To verify:** reconnect within 30 minutes of a password login and `logs/latest.log` should show
`Administrator session restored for <name> from <address> (AuthMe session).`

---

## Session: 2026-08-24 (part 3) - launch heights were being computed in a vacuum - STAGING ONLY

Reported: the mace combo still does not launch high enough. It does not, and the reason is that every launch
height in this relic has been wrong since the first version.

Velocity was derived with `v = sqrt(2*g*h)`. That is the textbook parabola, which assumes no drag. A player's
real vertical motion is `y += vy; vy = (vy - 0.08) * 0.98` -- **2% drag every tick**, compounding over the
whole ascent. The shortfall grows with the throw:

| Configured | Actually reached | Short by |
|---|---|---|
| 6 | 5.7 | 5% |
| 20 | 16.3 | 19% |
| **22** (the mace combo) | **17.9** | **19%** |
| 70 (the cap) | 47.6 | 32% |

So the combo was asking for 22 blocks and delivering about eighteen. Both halves of the complaint were
correct: the number was too small AND it was not even being honoured.

`apexHeight()` is the closed form of that recurrence (terminal velocity `0.08*0.98/(1-0.98)` = 3.92),
checked against a tick-by-tick simulation and agreeing to within 0.01 blocks across the whole range.
`launchVelocity()` inverts it by bisection. **A height in config is now the height actually reached.**

`mace-combo-burst-height` raised **22 -> 45**, which is the 40-50 asked for and needs v=3.235 -- far below
anything that would trip Paper's moved-too-quickly check. The 45-block descent is already covered by the
burst fall grace added earlier, so it cannot kill its own user. For scale, a normal jump is 1.25 blocks.

---

## Session: 2026-08-24 (part 2) - Anchor resolves on landing - STAGING ONLY

**STAGING ONLY.** Owner's design, adopted: the relic now resolves ONLY when the holder lands.

**Why the combo was unreachable.** The relic discharged the instant you brushed a target on the way down,
which is before any human can swing -- the mace window was effectively zero ticks wide. Landing is now the
single trigger, so the whole dive is predictable: fall, swing whenever you like, and the slam resolves when
you arrive. Connect and you get the launch, no fall damage, and a landing guard; hit nothing and you eat the
fall exactly as a mace user who whiffed.

**Cost, stated plainly:** a purely airborne target (a phantom, somebody on an elytra) can no longer be
slammed in mid-air. It has to be caught where it meets the ground, or with the mace.

Three additions the design needed to be playable:

1. **Landing invulnerability, 10 ticks** (`landing-invulnerability-ticks`), granted only on a slam that
   CONNECTED. Committing to a twenty-block dive and then standing in the open for the recovery is how a big
   telegraphed move becomes a liability instead of a threat. Missing still gives nothing back, so the risk
   of committing stays real. Void damage is never cancelled.
2. **Wind-burst fall grace** (`burst-fall-grace-seconds`). Six blocks per target reaches twenty-plus easily,
   and landing from that unaided is most of a health bar -- the relic would have routinely killed its own
   user. The descent from a burst is not charged to the player, and the grace clears on the first landing.
3. **The combo gets its own launch height** (`mace-combo-burst-height`, 22) rather than a doubled slam.
   Doubling six was still read as "not as strong as I wanted"; this is a deliberately absurd Wind-Burst-VI
   sized throw, which is the entire point of landing a combo. Still multiplies per target struck, under a
   `max-burst-height` ceiling of 70.

---

## Session: 2026-08-24 - Anchor field-test fixes, hopper spill #3, admin login persistence - STAGING ONLY

**STAGING ONLY. Production untouched.**

### TO PROMOTE LATER: admin login persistence is not working on production

Diagnosed, **not** applied. Production has `trusted-admin.session-persistence: true` and
`require-same-ip: true`, and `latest.log` contains **zero** "Administrator session restored" lines, so the
IP constraint is refusing every attempt: `isThisMachine()` only accepts loopback or an address belonging to
one of the server box's own network interfaces.

**The obvious fix is dangerous here and must not be applied blind.** Production runs `online-mode=false`, so
Mojang does not verify identity at all -- that is precisely why AuthMe is in front of it. Setting
`require-same-ip: false` would mean **anyone who connects using the name "MacoCT" is opped with no password**.

Built instead, ready to promote:

1. `trusted-admin.trusted-ips` -- an explicit allowlist of addresses that may restore a session. Keeps the
   property that matters (only a known machine skips the password) while working from somewhere that is not
   the server box.
2. A log line on every REFUSAL naming the address that was seen. The failure was previously silent, which is
   the only reason this needed diagnosing at all rather than being read straight out of the log.

**To finish at promotion:** connect once, read the refused address out of `logs/latest.log`, and add it to
`trusted-admin.trusted-ips` in production's `config.yml`. Do not set `require-same-ip: false`.

### Skyward Anchor: it was disarming one tick after being armed

Field report: no apparent buff, no base damage, and the mace combo never fired. All three were the same bug.

`Player#isOnGround()` reflects the last movement packet the client sent, so it is **still true for a tick or
two after a launch**. The ground-contact branch therefore fired immediately, and the relic discharged into
thin air with a fall distance of zero:

| | Damage at dead centre |
|---|---|
| Intended, 20-block drop | `0.9 x 85 x 1.5` = **114.8** |
| What actually happened (`fall = 0`) | `0.9 x 7 x 1.5` = **9.4** |

Which is exactly "you forgot to give it base damage". And the combo could never fire because by the time you
swung the mace there was nothing armed left to combo with. Fixed with an `airborne` flag: ground contact only
counts once the holder has genuinely left the ground.

**The damage model itself was correct and is unchanged.** Dead centre is `0.9 x 1.5` = **1.35x** a
maximum-Density mace for the same drop, easing to `0.9 x 0.5` = 0.45x at the edge of the box. The strike now
prints its own numbers to the action bar (targets, drop, peak damage) so it can be checked rather than
guessed at.

### Wind burst: it was a mace hop, not a wind burst

`burst-height-per-hit` was 2.5 blocks -- which is roughly what a plain mace smash gives you, reported
correctly as "the push is similar to a base mace push". Raised to **6.0**, a genuine Wind Burst II-sized
launch, still multiplying per target. Horizontal motion is now damped to 40% so it reads as lift rather than
as being swatted sideways, and it uses vanilla's own `ENTITY_WIND_CHARGE_WIND_BURST` sound and
`GUST_EMITTER_LARGE` particle so it looks like the thing it is imitating.

### Feedback that does not blind you

The armed effect put a GUST puff and a cloud burst at the feet **every tick**, which filled the screen the
moment you looked down -- i.e. exactly when lining up a slam. Now two small motes in a slow ring around the
ankles every quarter second, plus an action bar reporting the drop currently being carried. The readout is
the better feedback anyway: unmissable, costs no screen space, and lets the holder time the hit.

The mace combo is now unmistakable: title card, `EXPLOSION_EMITTER`, a flash, the wind-burst sound and a
totem chime.

### Relics have no cooldown in creative

Waiting out a twenty-second cooldown between attempts makes tuning miserable, and nothing in creative is a
balance concern. Deliberately does not WRITE a cooldown either: these cooldowns are bound to the RELIC, not
to whoever holds it, so a creative test could otherwise have locked the relic out for a survival player.

### Industrial hopper: a third copy of the same spill

`moveItem()` -- the path a vanilla hopper or dropper aimed at us uses -- had the same remove-before-checking
pattern as `move()` and `moveFromSlots()`, and its spill point is `dropAt(destination.at, ...)`, i.e. the
hopper's **own centre**. An item dropped inside a block is immediately shoved out again by block collision,
which is very much what "the stack on top jumps around like it is on a slime block and eventually falls out"
looks like from outside. Capacity is now checked before anything is removed, in all three.

`dropAt` is also **instrumented**: every remaining call site is a last-resort refund that should now be
unreachable, so if one ever fires it logs the item, the amount and the coordinates instead of silently
flinging somebody's farm across the floor.

**Honest status:** with the current build, a rig of a full industrial hopper (1728/1728) fed by a chest, with
an item stack resting on top, showed **zero spills and zero displacement** -- the stack was collected
normally. The earlier apparent reproduction was a test artefact: eight item entities summoned at one exact
point, which vanilla pushes apart violently on its own. Three real mechanisms have been removed and a
tripwire left in place; if it recurs, `logs/latest.log` will now name it.

---

## Session: 2026-08-23 (part 3) - elite variety, bound-item exploit, Skyward Anchor rework, hoppers - STAGING ONLY

**STAGING ONLY. Production deliberately untouched** at the owner's instruction, pending their own testing.

Also added `SESSION_PROMPT.md` at the repo root: the start-of-session brief for future sessions, so the
layout, the standing rules and the traps that have cost real time do not have to be rediscovered.

### Every Overworld legendary was a Wither Skeleton

`eliteType()` was a hardcoded switch: `case "legendary" -> WitherSkeleton.class`. Every Elite Hunt, for ever.
The tier is meant to say how DANGEROUS a thing is, not what it is.

Now a per-dimension, per-tier pool in `bosses.yml` (`elite-types`), picked flat-random, with invalid entries
skipped and an empty list falling back to the old behaviour. Measured on staging, 30 spawns per tier:

| Tier | Distinct types | Wither Skeletons |
|---|---|---|
| legendary | **11** | 1 / 30 |
| epic | 11 | - |
| uncommon | 9 | - |

Piglins, Piglin Brutes and Hoglins are deliberately absent from the Overworld lists: they zombify there
within seconds. Zoglins and Zombified Piglins stand in.

### Bound Shard items: a one-keystroke bypass, and a block that should never have been there

The guard only understood the CURSOR and shift-clicks. A **hotbar number-key swap is neither**: hover the
destination slot, press the hotbar number, and a bound item walked straight into shared storage. Offhand
swap (F) had the same hole, and **dragging was not checked at all**.

The same rule also blocked something it never should have: with the player's own inventory open the top
inventory is `CRAFTING`, so every bottom-inventory slot satisfies `rawSlot >= topSize` -- meaning a bound
pickaxe could not be shift-clicked between the hotbar and the main inventory. An item you own, moving inside
your own bag, refused.

Every route into a container is now enumerated explicitly (cursor, `NUMBER_KEY`, `SWAP_OFFHAND`, shift-click,
and a new drag handler), and the player's own inventory screen is exempt outright.

### Skyward Anchor: a slam, not a jump

Reworked to the owner's spec. Right-click LAUNCHES **20 blocks** and ARMS it (20s cooldown); it is not a
hold-to-use effect. It discharges on the first of: touching an entity on the way down, touching the ground,
or landing a mace hit.

- **Damage** = `0.9 x` a maximum-Density mace for the same drop, using vanilla's own fall curve, then scaled
  by how centred the target was: **1.5x** dead centre easing to **0.5x** at the edge of the 3x3x3.
  **Horizontal offset only** -- the box is a cube and vertical position inside it is not a skill expression.
- **Wind burst** per target hit, and the bursts MULTIPLY: two targets is twice the launch HEIGHT (not
  velocity, which would be exponential). Fall distance cleared like a vanilla wind burst.
- **Fall damage** is cancelled outright if the strike connected with anybody -- even if nobody was directly
  beneath. Miss everything and the fall lands on you exactly as it would on a mace user who whiffed.
- **Elytra**: the dive angle is measured from straight down. Past 40 degrees the accumulated drop is
  surrendered but the relic stays ARMED, because the charge was already paid for. Fall distance is tracked
  by the relic itself rather than read from `getFallDistance`, which vanilla zeroes constantly while gliding.
- **Mace combo**: land a mace hit while armed and before it discharges -> double weapon damage on that
  target, the relic's area damage to everything else in the box, and a doubled wind burst.
- Damage is attributed to the player, so kill credit, boss damage tracking and PvP logging all see it.
- Deliberately **no boss-specific bonus**. It is already among the strongest things to bring to a boss; the
  one thing it does not do is get better at them.

### Industrial Hoppers

**`/shop sellall chest` now works on one.** The block is a vanilla HOPPER whose five native slots hold only
the comparator calibration weight -- the real contents are a plugin-owned 27-slot inventory, so reading the
block state reported an empty hopper and refused to sell. `sellTarget()` resolves the bay first and falls
back to the block for every ordinary container, and the sale marks the bay dirty so a restart cannot restore
what was just sold.

**Two remove-before-checking defects fixed.** Both `move()` and `moveFromSlots()` pulled an item OUT of the
source, then refunded whatever the destination rejected -- and the refund could itself fail, at which point
the leftovers were thrown into the world with `dropItemNaturally`. `moveFromSlots` is the worse copy: its
source is a furnace or brewing stand, whose `addItem` does not put a refund back where it came from but
tries the ingredient slot, so a full hopper under a furnace could move the furnace's OUTPUT into its INPUT.
Both now ask how much fits before touching anything, which removes the refund path entirely.

**Verified on a live rig:** industrial hopper filled to 1728/1728, furnace above holding 64 iron ingots in
its result slot, thirty seconds of sweeps -> **0 items dropped, 0 items lost, all 64 ingots still in the
result slot**. `/ashfall hopper verify` passes with no failures.

**Honest limit:** the reported symptom -- items above a full hopper being flung away -- did **not** reproduce
in a controlled rig. Twelve item entities dropped onto a full hopper merged into one stack and sat still for
thirty seconds, zero spread. Two genuine item-integrity defects were found and fixed on the way, but if the
ejection recurs, what is directly ABOVE the hopper is the thing to report.

---

## Session: 2026-08-23 (part 2) - celebration rebuilt as acts - PROMOTED TO PRODUCTION

**PROMOTED 2026-08-23** after the owner reviewed it on staging ("PERFECT"). One silent restart (only Asserto
online, which is the standing rule), booted in 31s, self-test ok, zero SMPCore errors, jar MD5 identical to
the artefact tested on staging. Staging stopped afterwards and its console closed itself - the first clean
proof of the `if errorlevel 1 pause` fix, which left **zero** leftover windows for the first time.

### Why the loop had to go

The owner's read was exactly right: "the beginning feels and looks really strong, its just the cycling of
the same exact sound effects and effects is whats throwing me off". The first version drew the same helix
every tick, the same ring every 7, the same burst every 9, and replayed the same two firework sounds every
12 ticks -- **forty-odd repeats across a 25 second show**. Repetition reads as low quality however good the
individual effect is.

Rebuilt as ACTS. Each has its own visual language and hands over to the next; every sound cue fires on
exactly ONE tick (never a `t % n` timer, which is what produced the machine-gun effect); and both tiers end
on a **silent** confetti fall that thins to nothing rather than stopping dead.

| Tier | Act | Share | What it does |
|---|---|---|---|
| LEGENDARY | IMPACT | 0-8% | flash, shockwave rings racing outward, brief column of light |
| | ASCENT | 8-32% | violet helix climbing and widening once, embers rising through it |
| | BLOOM | 32-56% | a dome opening overhead like a firework shell, palette shifting |
| | DRIFT | 56-100% | silent falling confetti, thinning to nothing |
| GRAND | POP | 0-18% | one gold burst and a single fast ring |
| | SPIRAL | 18-56% | gold twin helix, rising once |
| | DRIFT | 56-100% | silent falling gold confetti |

Sound count went from ~45 plays (mostly two repeated cues) to **7 distinct cues** on legendary and **4** on
grand. Nothing repeats.

Act boundaries are FRACTIONS of the configured duration, so changing `celebrations.legendary-seconds`
restretches the whole show instead of just lengthening the last act. The self-test now also asserts the
boundaries stay in order, so a silly duration cannot skip a phase.

Confetti and embers use a particle **count of 0**, which makes vanilla treat the offsets as a velocity
vector -- the only way to make a particle actually travel rather than hang in the air. `particle()` passes
count through untouched for exactly that reason.

---

## Session: 2026-08-23 - the celebration that would not stop

**Ships as:** `SMPCore-1.7.0.jar` + `plugins/SMPCore/config.yml` (`celebrations.grand-seconds`,
`celebrations.legendary-seconds`).

### Particles never stopped after a 50m purchase

`Particle.FLASH` requires a `Color` in Paper 26.2 and was being spawned bare, throwing
`IllegalArgumentException: missing required data class org.bukkit.Color` on the FIRST legendary frame.

**A Bukkit repeating task is not cancelled by an exception** - it logs and runs again next tick. The frame
counter was incremented AFTER the drawing, so it never advanced past 0 and the show ran for ever: 5,832
exceptions in one log and a player who could not stop sparkling. The 10m tier was unaffected; only the
legendary branch touches FLASH.

Fixed three ways over, because one was clearly not enough:

1. The counter advances BEFORE anything that can throw.
2. `frame()` is wrapped - any exception cancels the task and logs once.
3. An independent wall-clock deadline, so neither of the above can be the only thing standing between a
   player and permanent glitter.

`particle()` now asks `Particle#getDataType()` and supplies data the API will accept rather than hard-coding
today's answer at each call site. A new self-test asserts every particle used is one we can satisfy - **and
it immediately caught a second one**: `DRAGON_BREATH` requires a `Float` in this build and was also bare, so
it would have thrown the moment FLASH was fixed.

Durations are now config: **grand 8s, legendary 25s** (owner: 10s fine, a minute fine, longer excessive).

### "MacoCT spent 50mil on Spawn Egg"

`IRON_GOLEM_SPAWN_EGG` is only the shop KEY for the Iron Golem Spawner - `shop.yml` already carries
`display: Iron Golem Spawner`, and the item handed over is and always was a spawner. The announcement was
prettifying the material name. Added `ShopService.displayName(Material)` and the label now uses it.

### Golem spawner allowance with two spawners - audited, plus one latent bug

The owner's two spawners are STACKED in one block (`spawner_histories: [I; 1, 1]`), which is the case that
works correctly: `stackSize` 2 -> two identities on every golem -> allowance 2 x 2200 = **4,400/day**, with
`addSpawnerAllowance` splitting usage across both ids and `spawnerAllowanceUsed` summing them.

**Latent bug found and fixed anyway.** Two golem spawners as SEPARATE blocks within the 12-block virtual
merge radius would have silently halved it: the spawn-time merge OVERWROTE the host's `sourceIds` with
whichever spawner fired last, and `consolidateStacks` discarded the donor's stamp entirely. Both now UNION
the identities (capped at `spawners.max-source-identities`, 32). Nobody would ever have noticed this except
as "my second 50m spawner earned me nothing extra".

---

## Session: 2026-08-22 (part 3) - the boss payout that was multiplied by a rounding error

**Ships as:** `SMPCore-1.7.0.jar` only. No config changes.

### 45,710 from a Warded Colossus whose floor is 664,453

Reported live. The ledger showed it was not a one-off - two outliers in one evening against ~20 normal kills:

| Time | Payout |
|---|---|
| 21:32 - 22:10 (18 kills) | 684,829 … 1,265,954 |
| 22:01:45 | **204,913** |
| 22:11:41 | **45,710** |

`iron-golem-boss` pays `random(126,562.5 … 253,125)`, and MacoCT's progression multiplier is exactly 5.25
(pinned independently: a 12-golem spawner stack paid 17,010 = 12 x 270 x 5.25). So the solo floor is
**664,453** and 45,710 is not a number this code should be able to produce.

**The divisor was never a divisor.** `splitReward` computed

```java
double base = pool * entry.getValue() / Math.max(1, eligibleDamage);
```

`Math.max(1, ...)` was written as a divide-by-zero guard. It is not one - below 1.0 it stops normalising and
becomes a **multiplier**. A sole participant credited with 0.0688 damage receives `pool * 0.0688 / 1`.
Working backwards from the two outliers gives recorded totals of 0.034-0.069 and 0.15-0.31 damage. Both
under 1. That is the whole bug.

**Why a boss fight ends up with a sub-1.0 damage total,** which is the part that makes this reachable rather
than theoretical:

1. Damage is recorded as `Math.min(remainingHealth, finalDamage)`, so any hit landing on a nearly-dead boss
   is credited as a fraction of a heart.
2. **End Crystal damage was never recorded at all.** `playerDamager()` understands direct hits, projectiles
   and pets; a crystal explosion arrives with the *crystal* as the damager. Bukkit still credits the player
   with the KILL - the death message reads "was blown up by MacoCT using [Bound End Crystal]" - so the fight
   looked completely normal while the damage table stayed empty.

Crystal-only kills therefore fell through the "no participants" fallback (`Map.of(killer, 1.0)`) and paid
correctly, which is why this looked random rather than broken. It only misfired when a stray chip of
attributed damage existed *and* was under 1.0 - then that sliver became the entire reward scale.

**Two fixes:**

- `rewardFraction(own, total, participants)`, extracted from `splitReward` so it can be tested: a true
  proportion when anything was recorded, an even split when nothing was. One participant now takes 100% of
  the pool regardless of the absolute numbers.
- Indirect damage is credited to `DamageSource#getCausingEntity()`, so crystal and TNT damage counts as
  participation. **Accounting only** - toughness scaling, relic multipliers and boss targeting still require
  a direct damager, so this changes who gets paid and never how hard the fight is. Previously a crystal user
  fighting alongside someone meleeing would have received *nothing*, since the table was non-empty and they
  were not in it.

New self-test line, `Boss reward split (single participant takes the whole pool)`, pins the exact expression
that was wrong - including the 0.0688 solo case.

### Correction: the 455,480 dragon is explained, and my earlier claim was wrong

Flagged in an earlier session as "a payout the code cannot produce". It can. The weekly dragon pool is
75,000-100,000 and MacoCT's multiplier is 5.25, giving a solo range of **393,750-525,000**. 455,480 sits in
the middle of it. I asserted the cap was ~100,000 after forgetting to apply the progression multiplier -
an arithmetic error on my part, not an unexplained payout. Closed.

---

## Session: 2026-08-22 (part 2) - golem spawner root cause, big-spend spectacle, scroll cooldown lie

**Ships as:** `SMPCore-1.7.0.jar`, `plugins/SMPCore/config.yml` (new `celebrations` block),
`plugins/SMPCore/shop.yml` (End Crystal 2,350 -> 6,000), and - the important one - **`config/paper-world-defaults.yml`**.

> A jar swap carries NONE of the three config files. The Paper one in particular is invisible to a
> `git diff`, lives outside `plugins/`, and is the entire fix for the golem spawner. `deploy/manifest.yml`
> now pins it with a `yaml_asserts` entry so it cannot be silently lost again.

### The 50m Iron Golem Spawner produced nothing, for a reason no log would ever show

Bought 2026-08-22 16:44 for 50,000,000, placed in the Blacklist claim at `world 6744 -49 -112391`. Between
purchase and audit it produced **one** golem. Not a slow rate - one.

What the live server said before any change:

| Probe | Reading |
|---|---|
| `data get block ... Delay` | **0**, constantly |
| tuning (`MinSpawnDelay`/`SpawnCount`/`SpawnRange`/`MaxNearbyEntities`) | 160 / 1 / 4 / 2048 - all correct |
| geometry: positions in range where a 1.4x2.7 golem physically fits | **77** |
| iron golems in the world | **0** |
| `golem_spawner_daily` | 2026-08-21: 2 kills, 2026-08-22: 1 kill |

`Delay: 0` is the tell. Vanilla only resets a spawner's delay after a spawn **succeeds**, so a spawner that
can never place its mob does not idle - it retries every single tick, for ever, silently.

**Root cause, read out of the running `paper-26.2.jar` rather than guessed at.** Iron Golem is the only
purchasable spawner type that still demands solid ground under a SPAWNER spawn:

- `SpawnPlacements`' predicate for `IRON_GOLEM` is `Mob::checkMobSpawnRules`, which opens with
  `EntitySpawnReason.isSpawner(reason) ||` - so the ground test **there** is already waived for spawners.
  This is why Blaze, Zombie and every other spawner works perfectly over an open drop.
- `IronGolem#checkSpawnObstruction` re-imposes it with no spawner exemption: `entityCanStandOn(below)`,
  plus two clear blocks above, plus unobstructed.
- `BaseSpawner` evaluates that one **unconditionally**:
  `if (customRules.isEmpty() && !mob.checkSpawnRules(..) || !mob.checkSpawnObstruction(..)) continue;`

MacoCT's chamber is open air for five layers with nothing beneath the spawner, which is the correct shape
for every other farm on this server and the one shape a golem spawner cannot use.

**Fix: `entities.spawning.iron-golems-can-spawn-in-air: true`** in `config/paper-world-defaults.yml`. That
flag is Paper's own escape hatch and sits inside the exact branch that was failing
(`!entityCanStandOn(below) && !ironGolemsCanSpawnInAir -> return false`). No per-dimension `paper-world.yml`
overrides it, so one edit covers every world. Everything else still applies - the golem must physically fit
and have three clear blocks - which is precisely the rule every other placed spawner already obeys.

**Verified live, not assumed.** A throwaway rig in empty sky above the base (spawner at y 250 with nothing
under the spawn point, a barrier catch-tray six blocks lower, `RequiredPlayerRange: -1` so it ticks with the
players 112k blocks away) produced **27 golems in 40 seconds**, with `Delay` cycling normally (24 -> 11 -> 0
-> 19 -> 5) instead of pinned at 0. Rig fully removed afterwards: spawner and barriers back to air, 33
golems / 54 items / 66 orbs killed, forceload released, volume re-probed with the chunk loaded to confirm
clean sky. (First probe reported dozens of "leftover blocks" purely because it ran after the forceload was
released - `execute if block` fails on an unloaded chunk. Re-load before verifying, always.)

**A wrong turn worth recording.** The first attempt attached vanilla `custom_spawn_rules` (via
`SpawnerEntry`/`SpawnRule`) to golem spawners to skip the rule, with a chunk-load repair sweep to fix
existing ones. It was written, compiled and **reverted** once the bytecode showed `checkSpawnObstruction` is
called unconditionally - custom spawn rules only skip the left-hand side of that `||`. It would have
rewritten every golem spawner's NBT and fixed nothing. `SpawnerService` keeps a short pointer comment saying
where the real fix lives, because that is where the next person will look.

*Economy note:* this is not an income change. `BossEventService` already restricts golem money to purchased
spawners - village and player-built iron farms pay nothing - so an easier village golem is still worth zero.

### Sealed Omen: the "an event is already in progress" cooldown that was not real

Reported twice. The lore line refused on `eventType != null` - i.e. on **any** event - while the actual
guard in `useSummonScroll` is `worldBoss() != null || eventType == WORLD_BOSS` and nothing else. So a
Resource Rush, Elite Hunt or Task Master ticking away anywhere made every Sealed Omen on the server
advertise a cooldown that did not exist; right-clicking would have worked the whole time, because
`startEvent`'s `standaloneBoss` branch exists precisely so a paid summon runs alongside an ordinary event.
The earlier fix only covered the stale-`eventEnds` case and left the real one.

`summonReadyLine()` now mirrors the guard exactly, counts down from `standaloneBossEnds` when the boss is a
standalone summon (`eventEnds` there belongs to the other event), and says "Unavailable - a world boss is
already active" rather than "on cooldown", which was never the right word.

### Big-spend spectacle (`SpectacleService`)

New. Two configurable tiers, fired from one choke point each:

| Tier | Default | What happens |
|---|---|---|
| GRAND | 10,000,000+ | Title, twin gold helix, expanding `TOTEM_OF_UNDYING` rings, firework bursts, layered sounds |
| LEGENDARY | 50,000,000+ | Triple violet helix, wider `SOUL_FIRE_FLAME` shockwaves, a 14-block column of light, `FLASH` + `DRAGON_BREATH` bursts, dragon-growl/wither/raid-horn stack, and a server-wide announcement |

Hooks: `BankService.payServer` (every shop, the Keeper of Omens, the spawner shop, home and ender-chest
upgrades, auction listing fees), `AuctionService.buy` (player-to-player, which does not go through
`payServer`), duel stakes at escrow time, and spectator wagers - the last on the **increase** only, so
raising 9m to 11m celebrates 2m and re-confirming the same wager fires nothing.

Deliberately drawn with particles and hand-played sounds rather than real `Firework` entities: a detonating
firework deals explosion damage to whoever is standing next to it, which is a poor reward for spending fifty
million. Sounds are played per listener so each player's own sound toggle is honoured (`world.playSound`
would ignore it), and particle density follows the **spender's** particle-intensity setting.

### End Crystal: 2,350 -> 6,000

Owner-set. Materials are 467.80, so this is roughly 13x rather than the earlier 5x - the convenience of
skipping the ghast-tear hunt is worth more than the parts, and at stack-buying volumes 2,350 was a rounding
error rather than a sink. Applied to the repo resource and both servers' `shop.yml`, live on production
immediately via `/ashfall reload` (no restart needed for a shop price).

### Still open

- The 2026-08-21 dragon that paid MacoCT **455,480**, which the code still cannot produce. Unchanged.
- `FAILED: central bank issue` on production remains an assertion about treasury solvency, not a fault.

---

## Session: 2026-08-22 - economy surcharge audit, graves, weekly dragon, Auto-TPA, editable templates - PROMOTED TO PRODUCTION

**PROMOTED TO PRODUCTION 2026-08-22.** One restart, silent (only Asserto and MacoCT were online, which is
the standing rule). Promoted: `SMPCore-1.7.0.jar` built from `5274704`, plus `config.yml` (spawner-shop
prices, `betting-window-seconds`, `setup-timeout-seconds`, duel-map bounds, hopper comparator flag),
`shop.yml`, `bosses.yml`, `shards.yml`, `relics.yml`, `events.yml`. `plugins/update/` cleared first; jar
MD5 verified identical to the artefact tested on staging.

Post-restart: booted in 31s, `/ashfall duelmap snapshots` 6/6 committed, `/spawnershop` and the new
`duelmap` completions live, zero orphan instances, **zero SMPCore errors** (the 4 ERROR lines are GrimAC's
SLF4J and the known ReplayCore cloud-registration rejection).

**One selftest line fails on production and it is not a code fault:** `FAILED: central bank issue`. That
check credits the treasury 1,000 and then requires `issueLoan` to succeed - and `issueLoan` refuses when the
bank cannot cover the amount. The Central Bank is at **-26,589,721** (181.0M in, 209.3M out), so no loan of
any size can be issued and the assertion cannot pass. Nothing in this session touches `issueLoan` or the
balance; the tax changes only ever pay INTO the bank, and the Spawner Shop had made no sales at that point.
The selftest is asserting a solvent treasury, which is an environment state, not an invariant.

**Consequence worth knowing:** the deficit surcharge is therefore LIVE right now. Prices and sinks at 2x was
already the case before this deploy; what is new today is **taxes at 3x** (orders 7.5%, auction listing 9%,
duel pot 15%) and **Keeper of Omens sigil costs at 2x**. That is a real, immediate change in what players
pay, and it stays until the treasury climbs back above zero. If it bites harder than intended, `feeFactor()`
in BankService is the single place to soften it.

**Production outage, 08:02:54.** Production and staging both died within four seconds of each other, mid-
gameplay and mid-boot respectively, with **no shutdown sequence in either log** and no crash report - a
machine-level event (reboot / sleep / power / console closed), not a plugin fault and not a deploy.
Production was relaunched and came back in 48s with all three players reconnecting. Because the kill was
abrupt, anything since the last autosave was lost; nothing has been reported missing.

### Weekly Ender Dragon: why 2026-08-21 paid no egg and no bonus XP

Read straight out of the production log:

```
16:03:19 WARN  respawn did not stabilise on a dragon entity after 3 minutes of polling
16:40:22 INFO  [WeeklyDragon] death: weeklyKill=false participants=1 killer=MacoCT
```

Vanilla's `EnderDragonFight.tick()` does nothing at all while no player is in the End, so the dragon does not
appear until somebody arrives. Nobody arrived within three minutes, the tag poll gave up, and when the dragon
finally spawned **37 minutes later** there was nothing listening to tag it - so the kill was never recognised
as the weekly one. Not a chunk-loading problem and not the old UUID race; purely a timeout that assumed a
player would be present.

Fixed three ways, so the reward cannot be lost to how long somebody takes to reach the End:

- The occurrence is **armed** in the database instead of polled for three minutes, and stays armed until a
  dragon is actually tagged. It survives restarts.
- A watcher checks every five seconds and **adopts any untagged live dragon in the End** - it no longer
  depends on `battle.getEnderDragon()`, which is precisely the thing that was never set. The two-second
  stability confirmation is kept.
- A death backstop: a dragon dying untagged while an occurrence is armed is credited as the weekly kill.

### Warded Colossus and the permanent Villager Mover

Not luck. `capsule-drops.worldboss.reusable` was **1.0%** - one permanent, tradeable, infinitely-reusable
Villager Mover roughly every hundred world-boss kills. **Owner-set to 0.5%** (2x rarer, not the 10x I first
applied - 0.1% was judged too harsh), with legendary 0.006 -> 0.003 and miniboss 0.002 -> 0.001. Single-use (`disposable`) capsules are unchanged; those are meant to be normal
rewards. The code fallbacks were updated to match the config so a missing key cannot restore the old rate.

### Treasury: the deficit now actually closes itself

Audited every player -> bank path. Purchases and sinks were already doubled through `bank.buyFactor()` -
homes, ender-chest upgrades, faction expansions and faction home slots all correctly included. Two real gaps:

- **The Keeper of Omens advertised half what it charged.** `purchase()` applied `buyFactor()` at the till but
  the GUI and every confirmation dialog showed the raw config price, so during a deficit it displayed
  1,000,000 and then took 2,000,000. The display now goes through the same factor and says why.
- **Taxes and fees were not scaled at all.** Added `bank.feeFactor()` = **3x during a deficit**, deliberately
  harsher than the 2x on prices so the treasury recovers faster the more the economy moves, rather than a
  deficit becoming a permanent background state.

  **Correction to an earlier claim in this section:** `/pay` and AxTrade direct trades are **untaxed** on
  production - `pay.tax-percent: 0.0` and `trade-tax.percent: 0.0`. The factor is wired at those sites too,
  but 3 x 0 is still 0, so nothing changes for them unless the owner ever sets a rate. The taxes that are
  actually live, and that this genuinely triples during a deficit, are:

  | Path | Rate | In deficit |
  |---|---|---|
  | Orders marketplace | 2.5% | 7.5% |
  | Auction listing fee | 3% | 9% |
  | Duel pot / spectator pool | 5% (code default) | 15%, clamped at 50% |

- **Checked, not regressed: taxes are never refunded on a cancelled or expired listing.** Auctions say so
  explicitly on cancel ("The listing fee is not refunded"). Orders never charge tax at creation at all - the
  tax comes out of the **seller's payout at delivery**, so a cancel refunds `escrow` only and there is no tax
  to give back. Both paths verified in code this session.

### Graves: a punch always breaks one now

Every grave has **two** armour stands at the same position - the glowing visual marker and the real
interactive stand. The marker had a hitbox and the damage handler returned early when hit, so a punch that
landed on the marker did nothing. That is both the "sometimes punching a grave doesn't break it" report and
the visual glitching. The marker is now a true marker (no hitbox), and a hit on one resolves back to its
grave for markers already in the world from older builds. Added an `EntityDamageEvent` guard as well: lava,
fire, explosions and suffocation can never break a grave - only a player punching it.

### Auto-TPA allowlist

A per-player allowlist in TPA Requests: named players `/tpa` straight to you with no request to accept.
Completely separate from Faction Auto-Accept - different preference keys, checked independently, neither
reads the other. `/tpa` only, never `/tpahere`: it grants the right to come to you, not the right to pull you
somewhere. Names match the **real account name**, never a nickname, since a nickname can be changed and an
impersonatable allowlist would be a teleport-into-your-base exploit. The chest GUI shows a head per player
(click = on/off, shift-click = remove) plus a master switch and an "Add player" chat prompt; the native
dialog offers the same actions. Up to 20 names, stored as one preference string so a name can sit inactive
rather than having to be deleted and retyped.

### Also

- **Editable duel templates.** `/ashfall duelmap enter` opened an empty void world on production, because
  promotion carries the committed *snapshots* and not the *workspaces*. A missing or arena-less workspace is
  now materialised from its committed snapshot using the same path and identity-metadata handling instances
  use, the arena region is validated before the admin is teleported, and a workspace that already holds the
  arena - on disk or as unsaved in-memory building - is never overwritten. Verified by deleting all six
  staging workspaces and watching all six rebuild from their snapshots.
- **GUI sound feedback.** One `uiSound` vocabulary (select / adjust / toggle / page / confirm / ready /
  stage / start / back / cancel / error / success), 19 hooks across the duel flow, plus Orders and the
  Discarded Vault which were entirely silent. Never called from a render path, so a refresh cannot
  double-fire.
- **Ghast tears** are now exactly double gunpowder both ways (buy 260 / sell 52) - ghasts cannot be farmed at
  anything like a creeper farm's scale.
- **`/shop` sell basket** returns to the shop after selling instead of closing the window.
- **Iron ingots and deepslate are both already in the shop, on production**, at identical prices to the
  repo: `IRON_INGOT` buy 55 / sell 11 and `DEEPSLATE` buy 1.75 / sell 0.35, both MINING. Checked the live
  `plugins/SMPCore/shop.yml`, not just the repo copy. Both also land on **page 1** of the MINING category
  (alphabetical index 32 and 12 against a 43-item page), so they are not hidden by paging either. If they
  are not showing in-game the cause is in the GUI rather than the data, and I have not reproduced that -
  worth a look together at what filter/sort is active when they disappear.

### /spawnershop - built

Spawners that leave the world **without reaching a player's inventory** become stock that can be bought back,
exactly the principle the Discarded Vault runs on. Capture paths, all of which mean nobody got it:

- `ItemDespawnEvent` - a spawner item that ticked out on the ground.
- `EntityDamageEvent` on a spawner item, re-checked on the FOLLOWING tick, so an item that survived the lava
  is never recorded as lost.
- `EntityExplodeEvent` / `BlockExplodeEvent` for PLACED spawners caught in a blast. Vanilla drops nothing for
  an exploded spawner, so this is the biggest single source of the loss the shop exists to recover. Stacked
  spawners contribute their whole stack size.

Stock is per mob type, held in a new `spawner_shop` table as a count rather than a ledger, because every
spawner of a type is interchangeable; the audit trail lives in `history`. Buying takes stock with a
compare-and-swap (`stock>0` in the WHERE clause) **before** money moves and before the item exists, so two
players clicking the last one cannot both get it, and a failed payment puts the stock straight back.

**It is a full member of the shop family.** `Section.SPAWNERS` joins the switcher cycle, so the middle button
walks Normal Shop -> Luxury -> Shard -> Auction House -> **Spawner Shop** -> Normal Shop and back round, from
any of them. `switchSection` hands off to the Spawner Shop's own screen instead of rendering a row list, and
that screen carries the same switcher onward, which is what makes it one cycle rather than four shops plus an
outlier. Its controls sit on the same slots as the rest of the family.

**Selling.** A spawner can be sold back for **a fifth of the buy price** -- exactly the buy:sell ratio every
entry in the normal shop uses, so a spawner's value is stated the same way as everything else rather than
being its own special case. The sold spawner goes back into stock, so buying and selling are two halves of
one pool rather than a money faucet, and the item is only removed once the payout has actually succeeded.

**Vanilla spawners count too.** A spawner mined WITHOUT a netherite silk-touch pickaxe is destroyed for XP and
nobody gets the block -- that is "left the world without reaching an inventory" just as much as an explosion
is, so it now goes to the vault *and* the Spawner Shop. Placed and naturally-generated spawners are treated
identically here, which is the point: the shop tracks spawners the world lost, not spawners the plugin owns.

The screen itself is separate rather than a marketplace row list, and that is a deliberate design call: the
marketplace is built end to end around one price per `Material`, and every spawner is `Material.SPAWNER`
with the `EntityType` as the entire product. Expressing that as a marketplace row would mean rewriting the
row model underneath `/shop`, `/luxuryshop` and `/shardshop`, all of which are live. Instead the GUI follows
the same grammar - same page size, same sort cycle, same filler and controls - `/shop` links across to it,
and it takes the Central Bank deficit surcharge like every other shop.

Live-verified on staging: a blaze spawner destroyed by TNT was recovered and listed at $1,150,000
(`[spawner-shop] +1 BLAZE (EXPLODED)`), and `/ashfall spawnershop` reports stock for admins.


### Bug-fix pass (same session)

- **The Keeper of Omens now doubles its SIGIL cost too, not just its coin cost.** Sigils are the Keeper's
  other currency; leaving them at face value while doubling the coin half would have made sigils the cheap
  way round a deficit. Rounded up, since half a sigil is not a thing. Applied at every display, confirmation
  and charge site.
- **Summoning scrolls no longer claim a cooldown that is not there.** The label read "an encounter is already
  active" off a stale `eventType`/`worldBoss` field and counted down a negative number. An encounter whose
  window has expired with no boss alive is now reported as Ready, whatever the leftover state says. The
  summon guard itself was always correct - this was the label disagreeing with reality.
- **Elite Endermen drop the End.** An Eye of Ender on every elite kill scaling with tier, plus an End Crystal
  at ~6% on epic and ~12% on miniboss. Legendary Endermen get 6 eyes and a crystal outright.
- **Spectating no longer reopens the betting window on top of you.** Choosing to enter the arena closed the
  menu and then immediately reopened it, leaving a draggable betting screen over a live fight. Entering now
  closes it; only a REFUSED entry keeps the menu up to say why.
- **Progression really is excluded in duels now.** It was only partly true: `blockMined` and `acquired`
  checked, but `hostileKill`, `majorKill` and `grantAdvancement` did not, so duel kits could still push
  milestones. All three now check.
- **Vanilla advancements are disabled in duels too.** A duel hands out netherite, an elytra and a mace, so
  without this a player could collect gear advancements from equipment they never earned and do not keep.
  `PlayerAdvancementDoneEvent` is not cancellable, so the grant is revoked on the same tick - it was earned a
  moment earlier in the arena, so undoing it takes nothing the player had before. Recipe advancements are
  left alone, since revoking those would strip recipe-book entries.

**Not a bug:** the blaze spawner that showed stock 0 was bought on staging by the owner. Nothing was wrong.


### Betting window rework

Betting used to be open only while a ready-gate was up, so two duellists who both hit Ready at once left
spectators a window measured in tenths of a second. Now:

- **Each round opens a ten-second window** the instant the fight actually starts (`arena.betting-window-
  seconds`, default 10). The gate can stay as short as the duellists want without costing anybody a bet.
- **A spectator who arrives mid-round gets their own ten seconds** from when they arrived, so somebody who
  walks into a fight is not told they are too late for something they have only just started watching.
  Granted on entering the arena *or* on opening the match screen, whichever comes first, and only once per
  round - reopening the screen cannot roll the clock forward.
- **Edge cases.** A personal window can never outlive its round: `bettingOpen` re-checks the phase every
  time, so `ENDING` (pools being paid out) and `resolving` (a round being decided this tick) both shut
  betting immediately regardless of anybody's deadline. Personal windows are cleared at the start of each
  round so nothing stale carries forward, and a spectator who has been watching since round one gets the
  round window like everybody else rather than an expired personal one. The spectate screen shows the live
  countdown.

### Audit: Warded Colossus payout variance (1.6M vs 700k)

Working as configured, and the arithmetic accounts for all of it. The payout is:

```
pool  = random(reward-min, reward-max) x (1 + 0.35 x sqrt(participants - 1))
share = pool x (your damage / total damage) x mobIncomeMultiplier(you)
```

With `iron-golem-boss.reward-min: 126,562.5` and `reward-max: 253,125`, the **base roll alone is a 2.0x
spread** before anything else applies. Observed range across ten kills in the ledger was 734,626 to
1,614,981 - a ratio of **2.20x**, which the base roll plus damage share covers entirely.

The three sources, in order of size:

1. **Base roll: 2.0x.** Configured, per kill, nothing to do with performance.
2. **Damage share.** Solo kills pay the whole pool; the 1,614,981 fight had AccelRip on 112,573 alongside,
   about a 14:1 split, while the 734,626 one was solo against a smaller roll.
3. **Progression multiplier.** `mobIncomeMultiplier` is a per-player product of completed requirements, so it
   moves slowly and is not the session-to-session variance - but it is what makes the absolute numbers large:
   the totals paid out are roughly 4-5x the maximum pool, so the multiplier is doing most of the scaling.

**No bug found, nothing changed.** The one thing worth an owner decision: a 2x random spread on the headline
reward for a fight of identical effort is wide enough to feel arbitrary. Narrowing `reward-min` toward
`reward-max` (e.g. 170,000-225,000, a 1.32x spread) would keep the average while making two identical kills
pay similarly. Left alone pending that call, since these are owner-set economy numbers.


### Follow-up patch (post-promotion, same day)

- **Cinder Warlord's seal now works on the nether roof.** The manual-seal placement test asked whether the
  player was within eight blocks of the world's BUILD LIMIT (`maxHeight - 8`, i.e. y >= 248). The nether roof
  is the top of the bedrock ceiling at y=128, so that was never true for somebody standing on it: the check
  fell through to the ordinary ground search, which found the real floor a hundred blocks below and was
  rejected by the depth guard - surfacing as "No clear footing here for the seal". The roof is now found by
  locating the bedrock ceiling itself rather than assuming a height, so a non-standard world height still
  resolves. **Automatic spawning is deliberately untouched** - it uses its own location picker, and automatic
  Cinder Warlords still never go to the roof.
- **End Crystal added to the Luxury Shop at 2,350** (`Bound End Crystal`). My first attempt priced it at
  150,000 by reasoning about what a crystal can be used for; the owner corrected that, and rightly. It is a
  CONSUMABLE bought repeatedly for crystal PvP and the easiest thing in the shop to craft, so it belongs on
  the Wind Charge's shelf (5,000), not the Nether Star's. Priced at **five times its shop material cost**,
  the multiple chosen because shop stock is finite and ghast tears are slow to come by:

  | Input | Cost |
  |---|---|
  | 7 x glass @ 15.4 | 107.80 |
  | eye of ender (pearl 70 + powder 30, half a 60 blaze rod) | 100.00 |
  | ghast tear | 260.00 |
  | **materials** | **467.80** |
  | **x5, rounded** | **2,350** |

- **A player-respawned dragon no longer pays the weekly Dragon's money.** There was no money distinction at
  all: `weeklyKill` gated the egg and the bonus XP, but both kinds drew from the same
  `mob-rewards.ENDER_DRAGON`. Since a respawn costs four end crystals and can be repeated indefinitely, that
  made the weekly event's headline reward into a farmable loop. Added
  `mob-rewards.ENDER_DRAGON_RESPAWNED: [45000, 60000]` against the weekly's `[75000, 100000]`, selected on
  `weeklyKill`, falling back to the shared range if the key is removed.

  **Unresolved:** the ledger shows a 2026-08-21 dragon paying MacoCT **455,480**, and I cannot reconcile that
  with the code. The pool is `random(75000, 100000) x (1 + 0.25 x sqrt(participants - 1))`, which caps at
  ~100,000 for a solo kill; `creditEarned` only garnishes overdue loans and applies no multiplier, and
  `mobIncomeMultiplier` is not on this path at all. Either the config differed on the build that was running
  then, or there is a credit path I have not found. Flagged rather than guessed at.
- **Spawner Shop controls moved onto the family's own slots** - switch at 49 and sort at 51, matching every
  other shop screen. They were the wrong way round.
- **Selling now goes through a sale basket** rather than selling the item in hand: drop spawners into the top
  rows, watch a running total, Confirm or Cancel. Same flow as the normal shop's basket (its own basket, not
  the shared one, since spawners cannot be priced from shop.yml). Money is paid FIRST and only then are the
  items taken, so a treasury that cannot cover the sale can never eat somebody's spawners; anything that is
  not a spawner is handed straight back, and closing the window returns everything.

### Outstanding

- ~~`/spawnershop`~~ - done, see above. Prices are audited below, but no code was written. The blocker is design,
  not effort: the requirement is that it joins the shop family and its filtering, and `MarketplaceService` is
  built end to end around `Map.Entry<Material, ShopService.Price>` rows - render, sort, filter, click and
  purchase all assume one price per Material. Spawners are all `Material.SPAWNER` distinguished by
  `EntityType`, so they cannot be a row in that model without generifying the whole section pipeline. That
  refactor touches `/shop`, `/luxuryshop` and `/shardshop`, all of which are live and working, so it needs to
  be done deliberately and verified rather than squeezed in at the end of a session. Capture hooks
  (`ItemDespawnEvent`, item destroyed by lava/fire/void, spawner blocks in an explosion blast list) and the
  stock table are straightforward once the row model is settled.
- The Bedrock (Geyser) form has no Auto-TPA entry yet; Bedrock players reach it through the chest GUI.

### Auto-TPA, second pass

- **Native `/settings` now has a real text field.** Paper's `TextDialogInput` replaces the chat prompt there,
  and returns to the native allowlist dialog rather than dumping the player into the chest GUI. Chat capture
  is now exclusive to `/settings`, which is the only surface that cannot offer a text field.
- **Player heads render in the native dialog** as `ItemDialogBody` entries beside each name.
- **Clicking a name only toggles it.** The "(click to turn off)" text is gone, and removal moved to its own
  page in both UIs - native gets a "Remove a player..." dialog, the chest menu gets a Remove submenu where
  clicking a head removes it. Shift-click on the main chest list still removes, noted quietly in the lore.
- **Auto-TPA bypasses the general TPA Requests switch.** Somebody who turns requests off to stop strangers
  asking still wants the three friends they named to come straight through, so the allowlist is checked
  before that gate. Still `/tpa` only, never `/tpahere`.
- "Other Players' TPA Requests" renamed to **"TPA Requests"**.
- **Kelp added** (`buy 3.9 / sell 0.78`, FARMING) - priced just under bamboo since raw kelp still has to be
  smelted before it does anything, which is where `DRIED_KELP` (sell 1.43) already sits. Sea pickles were
  already stocked at 21.3 / 4.26.
- **Cobbled deepslate** (`1.75 / 0.35`) and **iron nuggets** (`6.1 / 1.22`) added - the nugget priced at
  exactly a ninth of the ingot both ways so crafting between them can never be an arbitrage. (My earlier note
  about iron ingots and plain deepslate was answering the wrong question; both of those were already stocked,
  these two were the ones actually missing.)
- The admin login-persistence restriction on production (`require-same-ip`) is not yet removed.

**Audited spawner prices.** Money per kill is the `mob-rewards` midpoint x `spawner-share` (0.5), plus drop
value at shop sell prices and vanilla average drop counts.

| Spawner | $/kill | Drop value/kill | Total | vs Zombie | Price |
|---|---|---|---|---|---|
| Blaze | 5.75 | 6.00 (0.5 rod x 12) | 11.75 | 3.83x | **1,150,000** |
| Cave Spider | 2.00 | 3.35 | 5.35 | 1.74x | **500,000** |
| Spider | 1.50 | 3.35 (1 string x 3.2 + eye) | 4.85 | 1.58x | **450,000** |
| Skeleton | 1.50 | 2.14 (bone 1.61 + arrow 0.53) | 3.64 | 1.19x | **350,000** |
| Zombie | 1.50 | 1.57 (rotten flesh) | 3.07 | 1.00x | **300,000** |

Anchored on the owner's zombie floor of 300k and rounded to 50k. The owner's draft had the right ordering but
compressed spacing - spider and skeleton were overpriced against zombie (2.00x / 1.67x versus a true 1.58x /
1.19x) and blaze was underpriced (3.33x versus 3.83x).

---

## Session: 2026-08-20 — duel arena rebuild (maps, instances, three-stage setup) + Industrial Hopper parity — ✅ PROMOTED TO PRODUCTION

**✅ PROMOTED TO PRODUCTION 2026-08-20 (evening), in one restart.** Only Asserto was online, so per the
standing rule the restart was **silent** — no announcement, no countdown. Promoted: `SMPCore-1.7.0.jar`,
`config.yml` (rebuilt from staging with production's own `session-persistence`/`require-same-ip` block
restored and the staging-only auth keys and MacoCT test-whitelist entry stripped; backup at
`config.yml.bak-preduelmaps`), `bosses.yml`, `shop.yml`, `shards.yml`, `relics.yml`, `events.yml`, and the
five map snapshot folders under `plugins/SMPCore/duel-templates/` (`__canary` deliberately excluded).
`plugins/update/` was cleared first.

Post-restart verification on production: `/ashfall selftest` all green including the two new duel lines and
`snapshots committed: 6/6 (all playable)`; all six maps registered and `[committed]`; a `dryrun` of Skyroot
Village cloned, filled 26 chests, resolved both spawns facing each other and dropped cleanly in 517 ms; zero
orphan instances on disk or loaded; zero SMPCore errors in the boot log (the 17 WARN/ERROR lines are all
pre-existing third-party noise — GrimAC's SLF4J, ReplayCore, AuthMe/Vault, the offline-mode banner).

Everything below was built and tested on staging (`C:\MinecraftServer-Staging`, port 25566 / RCON 25576)
first. The **promotion checklist** at the end of this section records what was carried and why.

### The blocking defect: duel template blocks never reached their clones

Every duel instance came up as an empty void. Chests, signs, trial spawners and vaults were all "not a block
entity" in the clone, and the template's own region file for the arena (`r.17.21.mca`) appeared to be missing.
The previous session's diagnosis was that far-from-origin chunks in a void template never persist.

**That diagnosis was wrong, and the template was never broken.** `r.17.21.mca` existed the whole time — 3.2 MB
of it — at `world/dimensions/minecraft/duel_tpl_cinder_crucible/region/`. The real cause:

> **Paper does not put a Bukkit-created world in the world container.** It puts it in
> `<level-name>/dimensions/<namespace>/<world>`. `createInstance` copied the template folder to
> `<worldContainer>/duel_inst_...`, then called `createWorld(...)`, which looked in
> `world/dimensions/minecraft/duel_inst_...`, found nothing, and **generated a brand-new empty void world**.
> The copied folder sat at the root, unused and unread, and the instance a player would have stood in was
> genuinely empty. Every symptom followed from that one wrong directory.

Two further layout facts fell out of fixing it, both of which broke the first attempts:

- **A non-main world folder has no `level.dat`.** Level data lives in the parent world. The first snapshot
  validator required `level.dat` and rejected every snapshot ever taken.
- **A world's identity now lives in `data/paper/metadata.dat`, not `uid.dat`.** Copying it made Paper refuse
  the clone with *"is a duplicate of another world and has been prevented from loading"* — surfacing several
  layers up as a null world with no explanation.

### What replaced it

`DuelMapService` now separates the **live template** (what an admin builds in) from a **committed snapshot**
(what matches clone). Templates are worlds; snapshots are plain folders under
`plugins/SMPCore/duel-templates/<key>/`, deliberately outside the world container so Paper never sees one as
a world.

- **`/ashfall duelmap save <map>` commits atomically.** Full world unload with `save=true` as the write
  barrier (a `World.save()` only *queues* the writes), copy to `<key>.tmp`, validate, then publish by rename.
  A failure at any point leaves the previous good snapshot exactly as it was and says so. Interrupted swaps
  are repaired at boot: a parked `<key>.old` with no live sibling is put back rather than deleted.
- **Snapshot validation refuses to publish an arena-less snapshot.** The region file covering the spawn pair
  must be present and non-empty. This is the specific check that would have caught the original defect the
  first time somebody saved.
- **`createInstance` verifies the directory it assumed against the one Paper actually opened**, and if they
  differ it re-resolves, re-clones and logs loudly. The layout can change again without this failing silently.
- **`prepareInstance` is the path matches use**: the folder copy runs off the main thread (the snapshot is
  immutable, so nothing can race it) and the arena's chunks are pulled ~24 per tick. A 14-million-block map
  is ready in **0.3–0.7 s** with no server freeze.
- **Orphan recovery scans every plausible directory**, the container included, so leftovers from the old
  layout are cleaned up too. Ten stale instance folders from the previous session were removed on first boot.

### Proof, not assertion: `/ashfall duelmap canary`

The old pipeline failed while every intermediate step looked healthy — the template read back correctly, the
save reported success, the clone produced a valid world, and only a player standing in the arena could tell.
So the fix ships with a canary that asserts against real state on disk at every stage, at the exact
coordinates the failure was first reported (`9153, 232, 11208` — chunk 572,700, region r.17.21.mca):

```
ok  1. void template created: duel_tpl___canary at ...\world\dimensions\minecraft\duel_tpl___canary
ok  2. distant chunk 572,700 loaded (region r.17.21.mca)
ok  3. chest+item, sign, trial spawner and vault placed and read back in memory
ok  4. committed atomically: (8 region file(s), 7 MB)
ok  5. r.17.21.mca written to the snapshot (1164 KB)
ok  6. template reloaded from disk still holds all four block entities
ok  7. two independent clones, both complete, neither affected by the other
ok  7b. loot census sees 1 single + 1 double chest, so a double chest rolls once
ok  8. the snapshot committed before this restart survived the JVM intact
CANARY PASSED
```

It plants four different block-entity kinds so a container-only failure is distinguishable from a total one.
The container whose *contents* are asserted is a **barrel**, not a chest: an instance re-rolls every chest by
design, so a chest's contents legitimately differ from the template's. The first run of the canary "failed" on
a chest that had in fact survived perfectly and then been correctly re-rolled — the probe was wrong, not the
code.

### The six maps

All six are built, committed and playable on staging. Map choice is fully independent of kit: every kit can
use every map, and there is no Classic map and no kit-exclusive arena.

| Key | Name | Rule | Source | Chests | Vaults |
|---|---|---|---|---|---|
| `arena50` | Flat 50×50 | placed-only | built from code | 0 | 0 |
| `arena100` | Flat 100×100 | placed-only | built from code | 0 | 0 |
| `temple_of_tides` | Temple of Tides | full | imported, 2,845,850 blocks | 41 + 1 double | 0 |
| `cinder_crucible` | Cinder Crucible | full | imported, 2,913,504 blocks | 35 + 1 double | 21 |
| `deepstone_mines` | Deepstone Mines | full | imported, 3,426,500 blocks | 0 | 18 |
| `skyroot_village` | Skyroot Village | full | imported, 14,335,488 blocks | 19 + 7 double | 0 |

- **The two flat arenas are built from code**, not imported: `/ashfall duelmap build arena50|arena100` lays
  the owner's black-and-red design (red terracotta + glowstone floor, obsidian + glowstone full-height walls,
  bedrock cap) spread over ticks, then commits.
- **The four real maps are imported by exact cuboid, block by block, through WorldEdit** —
  `/ashfall duelmap import <map>`. Deliberately **not** a region-file copy: the arenas were built in the
  shared survival world and their selections overlap the same `.mca` files as unrelated terrain, so copying
  region files would have dragged in whatever else shared the region. Sliced two chunk columns per tick;
  the largest map takes ~43 s and is invisible to anybody else on the server.
- Spawns are stored in source coordinates so nothing shifts, and **yaw is derived from the spawn pair at
  runtime**, so the two duellists always face each other however the points are moved.
- **Skyroot's spawns were recorded as the block you stand ON, not IN** (the other maps use the opposite
  convention). Rather than quietly rewriting the owner's coordinates, a spawn inside a solid block is now
  lifted to the first standable spot above it, capped at six blocks — the same place under either reading.

### Break rules

Both models are implemented as **one shared predicate**, which is what guarantees a pickaxe and a stick of
TNT are held to the same rule — the explosion half was written but never exercised before.

- `placed-only` (both flat arenas): only blocks placed during **that specific match** may be broken. Original
  terrain resists players *and* explosions.
- `full` (the four imported maps): terrain inside the bounds is fully breakable including by explosions.
- Under **both** rules: barriers, bedrock, command/structure/jigsaw blocks and anything outside the declared
  bounds are protected, and a duellist cannot place outside the bounds either (no bridging out of the map).
- Ender pearls and chorus fruit cannot land outside the bounds.

### The three-stage setup flow

Kit → Map → Final Options, each with its own screen and its own both-sides confirmation. The **initial kit GUI
is unchanged**.

- **Nothing is charged and no arena is built until the last confirmation.** Money now leaves the players'
  balances at the final confirm, not the first, so backing out of stage two or three costs nobody anything
  and needs no refund path at all.
- **Any change at any stage clears BOTH confirmations** — neither player can be walked into a choice they did
  not see.
- **Back** returns to the previous stage (from the kit stage it cancels, as closing the window always has).
  **Cancel** is on every stage. ESC still cancels, and still does not count as a cancel when the player is
  merely stepping into the wager box, the opponent's wager viewer, or the next stage.
- **Per-stage timeout** (`arena.setup-timeout-seconds`, default 180) with the clock reset on every stage
  change, so a slow but active setup is never cut off and an abandoned one never holds a concurrency slot.
  The clock is suspended while an arena is being cloned, so a timeout can never strand an instance.
- **Disconnecting during setup cancels the duel** instead of leaving the other player staring at a screen.
- **Final Options carries Visibility Effects, default ON.** While on, both duellists are kept Glowing and
  given Night Vision for the whole fight regardless of their own `/settings` — re-applied every second and
  again after every round's re-equip, because `equip()` strips effects and a milk bucket would otherwise end
  it. Off forces neither. Restoration clears every effect before putting back what the player had, so nothing
  the duel applied ever follows them out.

### Matches now run in their own world

`startMatch` clones the chosen map and runs the match there. Two matches on one map are two separate worlds:
terrain, chests and damage in one are invisible to the other, and editing a template afterwards changes
neither. Instances are destroyed on **every** exit path (clean finish, forfeit, refund, cancelled setup,
shutdown) from the single `dispose` method, so there is no path that leaks one.

- `isArenaWorld` now covers instance worlds, so the graves and progression exclusions follow duels into them.
- `ArenaService`'s own block-break handling is scoped to the legacy shared arena only; `DuelMapService` owns
  the rules inside instances, which is what lets an imported map be fully breakable while the flat arenas
  stay pristine.
- Blocks placed during round one are cleared before round two.
- Natural spawning is off by gamerule **and** by a `CreatureSpawnEvent` guard, because trial spawners and
  imported spawner blocks do not honour the gamerule — and the imported maps contain 11 spawners between them.

### Industrial Hopper — vanilla parity

A vanilla hopper with exactly two differences: 27 slots, and **nine items per transfer operation, not nine per
slot**. Verified, and three real deviations fixed:

- **Comparators now read the real 27 slots.** A comparator reads the block's own five slots, which are empty
  by design, so every Industrial Hopper on the server read as zero. The five slots now carry a **calibration
  weight**: a marked stack sized so vanilla's own five-slot signal formula reproduces the twenty-seven-slot
  reading exactly, checked for every level from 1 to 15. It is a readout, not storage — invisible to every
  transfer path, never dropped, never counted, and recomputed only when the level actually changes. A full
  readout arises only when the real inventory is genuinely full, so vanilla's own "is this container full"
  checks stay truthful as a side effect. (Bug found by the new test: level 1 computed a weight of *zero*, so a
  barely-filled hopper read as empty — precisely the state a comparator lock usually watches for.)
- **A hopper under a furnace no longer steals the ore.** Vanilla exposes only the result and fuel slots
  through a furnace's down face; this pulled from slot 0 and would have taken the input back out of every
  furnace array on the server. Fuel is now only taken as empty buckets, as vanilla does. Brewing stands route
  by face too (ingredient from above; fuel and bottles from the side), so blaze powder can no longer land in a
  potion slot.
- **Container minecarts work in both directions** — unloading a chest minecart above and loading one it faces.
- **A world unloading no longer breaks every hopper on the server.** A `Location` holds only a *weak*
  reference to its `World`, and once that world is unloaded `Location.getWorld()` **throws** rather than
  returning null — so the sweep's `if (world == null)` guard never fired, and the task threw on every tick
  for the rest of the session, taking every other hopper's turn down with it. Bays now carry their world
  name and map key from construction and resolve by name; nothing dereferences the Location's world any
  more. Duel instances unload constantly now, so this was not a test-only situation. Found by the new rig
  cleaning up after itself, and now asserted by it.
- **Dropped-item collection is done directly** rather than relying on vanilla's pickup into the block's five
  slots, so collection depends on whether there is actually room, not on the calibration weight. A full hopper
  leaves the item on the floor rather than deleting it.

### Test coverage added

The legacy 46-check selftest covered none of this work. Three new suites, all runnable from the console:

- **`/ashfall duelmap canary`** — the template-persistence proof above, including across a restart (it stores
  a token stamped with the JVM start time, so the cross-restart claim is real rather than assumed).
- **`/ashfall duelmap verify`** — the full pipeline per map: clone, spawn standability/headroom/facing, break
  rule for both a pickaxe and an explosion, infrastructure and out-of-bounds protection, gamerules, mob
  purge, autosave off, container census, concurrent-instance independence, instance drop and folder removal,
  and the trial-key restriction measured over 8,000 rolls per map.
- **`/ashfall hopper verify`** — builds a real rig in a private void world and drives one hopper cycle at a
  time: placement/facing, pull, push, the nine-per-operation budget, redstone lock, comparator across all 15
  levels in both directions, floor pickup (including a full hopper leaving items alone), container minecarts
  both ways, furnace face routing both ways, breaking, explosions, and the serialise/deserialise path a chunk
  unload and a restart take. Conservation is re-checked after **every** cycle.
- Plus `/ashfall duelmap dryrun <map>`, which exercises the exact async path a real match uses and reports
  timings, resolved spawns and facing.
- `/ashfall selftest` gained two lines: the duel map registry check, and how many maps have a committed
  snapshot (a map with no snapshot is what silently stops the duel flow reaching a player).

Current staging results: **`/ashfall selftest` all green**, **CANARY PASSED**, **DUEL PIPELINE VERIFIED — no
failures**, **INDUSTRIAL HOPPER PARITY VERIFIED — no failures**.

### Three late fixes (same session, promoted together)

- **Live admin autocomplete.** `/ashfall duelmap` now completes its subcommands and their arguments from the
  live registry and world list: registered map ids, the two flat arenas for `build`, loaded instance world
  names for `drop`, and `p1|p2|spectator` for `setspawn`. `/ashfall hopper` completes too. One `DUELMAP_SUBS`
  list feeds the help text, the completion and the unknown-subcommand reply so they cannot drift, and a wrong
  or missing argument now returns a one-line usage **plus the values that would have worked** rather than a
  bare error. The `/ashfall` tree is already admin-gated, so suggestions are permission-aware by construction.
- **Closing any duel GUI cancels.** Closing Kit, Map or Final Options by hand goes through exactly the same
  path as `/duel cancel`, at every stage, with no "already confirmed" exemption. The subtlety: Bukkit fires
  `InventoryCloseEvent` for the old screen when the plugin opens the next one, identically to ESC — so
  advancing a stage, and `startMatch` closing both screens while the duel is still `STAKING`, would have
  cancelled the duel they were starting. Plugin-driven swaps are now marked by an explicit `screenTransition`
  guard, set and cleared synchronously around every open/close; `selfTest` asserts it suppresses, nests, and
  clears even when the swap throws (a stuck flag would silently stop manual closes cancelling for the rest of
  the session). The wager box and wager viewer are sub-screens, not stages: closing one returns the duellist
  to their stage, which keeps the invariant the cancel rule depends on.
- **Template chest eligibility.** Loot now rolls into every chest that was **empty in the committed template**
  and only those; a chest the builder stocked is left exactly as they left it instead of being cleared and
  re-rolled. No registry, no coordinates, no config — place a chest in the template, save, and it works, which
  is exactly what Deepstone Mines needs once its chests are added. A chest a duellist places mid-match can
  never qualify because the roll happens once at instance creation, before either player is teleported in, and
  a `looted` set makes that literal so a chunk reload cannot reprint loot into a chest somebody has already
  emptied. One roll per physical container and double-chest de-duplication are unchanged; the key chances now
  apply per **eligible** chest and are reported that way. The canary gained a pre-stocked chest and asserts all
  three properties (2 empty rolled / 1 stocked untouched / second roll is a no-op).

### Two things needing the owner's decision (staging warnings, not code faults)

1. **Cinder Crucible P2 spawns over lava.** The specified point `9153, 232, 11157` has lava at y=231 across at
   least a 3×3 area; P1 is fine (soul sand). Deliberately **not** auto-relocated — moving somebody's arena
   spawn is their call. Fix by standing where you want it and running
   `/ashfall duelmap setspawn cinder_crucible p2`.
2. **Deepstone Mines has 18 vaults but no chests inside its bounds**, so no trial key can ever be found on it
   even though it is one of the two maps allowed to roll them. The specified bounds start at y=210; its chests
   are presumably below that. Either widen the bounds and re-import, or add chests to the template.

Trial keys are otherwise correctly restricted to Cinder Crucible and Deepstone Mines. The configured 10%/3%
rates measure as **10.03% normal / 3.00% ominous** on Cinder and **9.91% / 3.08%** on Deepstone; note that the
ominous roll wins outright, so the normal key's real per-chest rate is 10% × (1 − 3%) = **9.7%**. Against
Cinder's 36 chests that is ~3.5 normal and ~1.1 ominous keys per match.

### Deploy notes / gotchas hit this session

- **`plugins/SMPCore/config.yml` needs the new keys** — `duel-maps.*.bounds`, `duel-maps.*.source-world`,
  `duel-maps-import.columns-per-tick`, `arena.setup-timeout-seconds`, `duel-loot.chunks-per-tick`,
  `industrial-hopper.comparator-output`. A jar swap alone does **not** propagate them. Staging's copy was
  patched surgically to preserve its staging-only auth keys; a backup is at `config.yml.bak-preduelmaps`.
- **RCON `stop` was silently swallowed** by the deploy script's use of the empty-type-0-packet multi-packet
  trick — Paper closed the connection part-way through the command sequence, the script believed the server
  had stopped, and the next JVM died on `session.lock` (*"another process has locked a portion of the file"*).
  The scratchpad RCON client no longer uses that trick, and the redeploy script now waits on the **java
  process actually exiting**, not on the listening ports (which close early, while the JVM is still saving).
- **Do not tidy up staging console windows by matching on window title.** One dead `cmd` window accumulates
  per restart cycle, each parked at `pause`. Closing the stale ones by `MainWindowTitle` (excluding the live
  java's *parent* PID) took the running server down anyway: the window-owning `cmd` is not the java process's
  direct parent, so the "live" console was not actually excluded. Staging was down for about ten minutes and
  was relaunched cleanly; production was never involved. If they need clearing, do it from the desktop, or
  `stop` the server first. This is the same class of mistake as the standing "never taskkill a console PID"
  rule, reached from a different direction.

### Promotion checklist — what a push to production carries

**Ready to promote (already merged in previous sessions or complete and verified now):**

- Everything from the 2026-08-17 session, already live.
- Non-duel items from the pending batch: production forced-chunk cleanup, the plugin-vs-admin force-load
  ownership registry, Cinder Warlord nether-roof spawning, the 0.5% special-tool drop split across six
  `/shardshop` tools, TNT at 726.81 plus the crafted utility shop items, sigil faction values (75k/400k),
  `/fly` and `/flyspeed` with `smpcore.fly`, and the duel kit changes.
- Industrial Hopper parity fixes and their test suite.
- The duel map engine, the six maps, the three-stage setup flow and their test suites.

**Must NOT be promoted as-is / needs action first:**

- **The four imported maps must be promoted as their committed snapshot folders**
  (`plugins/SMPCore/duel-templates/<key>/`, ~7–40 MB each), copied across as part of the deploy. They are not
  in Git, and they are what a match actually clones — a jar swap alone leaves production with four maps that
  report `NOT COMMITTED` and cannot be selected. Now recorded in `deploy/manifest.yml`.
  WorldEdit *is* installed on production (`worldedit-bukkit-7.4.4.jar`, and it is a hard `depend` in
  plugin.yml), so `/ashfall duelmap import` would run there — but only if the source arenas exist in the
  production overworld at those coordinates. They were built on staging after the 2026-08-17 world clone, so
  assume they do not; check with `/ashfall hopper <x> <y> <z> world` at a map's spawn before relying on it.
  Copying the snapshots is the safer path either way and does not depend on the answer.
- **The two owner decisions above** (Cinder P2 spawn over lava; Deepstone bounds/chests) should be settled
  before players see those maps.
- **The three-stage GUI has not been exercised by two real players.** Every mechanism behind it is asserted,
  but the clicking has not been done. See the manual acceptance steps below.
- The `duel_tpl___canary` template world and its snapshot are test artefacts. Harmless, never offered to a
  player (the canary map is not in config), but there is no reason to copy them to production.

**Manual acceptance still outstanding (needs two players on staging):**

1. Challenge → accept → kit + confirm both → map + confirm both → Final Options + confirm both → fight.
2. Back from Map and from Final Options; change something after one side confirms and check both
   confirmations clear.
3. Cancel from each stage; ESC from each stage; one player disconnecting at each stage.
4. In-match: place and break your own block on a flat arena, then try to break the floor (must refuse), then
   TNT on the floor (must refuse). On an imported map, break terrain and TNT it (must work), then try a
   barrier (must refuse).
5. Visibility Effects ON — both glow and keep night vision through a whole best-of-3, including after a
   golden apple and a milk bucket. Then a duel with it OFF.
6. Two matches on the same map at once; confirm neither can see the other's damage or loot, and that both
   worlds are gone afterwards.
7. Full inventory/XP/health/location restoration after a win, a forfeit and a disconnect.

---

## Session: 2026-08-17 — Discarded Vault v2 + spawner/market/auction/admin changes — ✅ PROMOTED TO PRODUCTION

**✅ MERGED TO PRODUCTION 2026-08-17 (evening).** Everything in this session is now live on prod: full build
swapped, `enchants`+`details` migrations ran on the prod DB, `/ashfall selftest` all-green, vault v2 confirmed
(details-aware aggregate, 13 pages). Prod config carries the clean admin-persist flags (`session-persistence:
true` + `require-same-ip: true`); shop.yml/other config already matched (the staging-only keys were just
explicit copies of code defaults). **Gotcha hit during promotion:** a leftover `plugins/update/SMPCore-1.7.0.jar`
(old build) silently overwrote the swapped jar on the first boot via Paper's update-folder mechanism — caught it
(vault showed old wording + no new columns), deleted the update folder, re-swapped, and restarted again. So it
took **two** restarts, not one. Lesson: always clear `plugins/update/` before a jar swap.

Staging DB **and** the full 21GB `world/` were cloned from production (online SQLite backup + `robocopy /MIR`;
only `session.lock` failed to copy, which is expected and harmless) so the vault could be built and tested
against real data.

- **Page-3 crash fixed** (also shipped in the prior staging jar): block-only materials with no item form
  (`TWISTING_VINES_PLANT`, `CAVE_VINES`, …) threw `new ItemStack "isn't an item"` at
  `DiscardedVaultService.show` and stalled the GUI on whatever page held the first one (page 4). Guarded with
  `!material.isItem()` → placeholder icon, real name/lore kept.
- **Merged/stacked spawner counts are now accurate.** `explodedBlocks` recorded `1` per spawner block, so a
  raid on 12 blocks that were each 10×-merged logged as 12, not ~120. Now reads the real stack size via
  `SpawnerService.stackSize(spawner)`. **Applies going forward** — the historical xFPu-raid entry can't be
  retroactively reconstructed (the merge counts were never recorded).
- **Enchantments captured.** New `discarded_ledger.enchants` column (+ `idx_discarded_material_ench`), migrated
  additively. `record()` stores a canonical sorted signature (`sharpness:5,unbreaking:3`, incl. book stored
  enchants); enchanted variants aggregate as their own rows and render with an enchant glint + a readable
  "Sharpness V, Unbreaking III" line. Plain items are unaffected (empty signature). Going forward only.
- **Dynamic view, performance-first.** The whole aggregate is built **once per cache window (default 15s) OFF
  THE MAIN THREAD** (`Database.discardedAggregate`, GROUP BY material,enchants), cached, and then **all**
  search / sort / category / paging happen **in memory** on that snapshot — so the dynamic controls add zero
  per-click DB or tick load (the #1 requirement). Controls: **Sort** (Most destroyed / Most recycled / Last
  destroyed / Name), **Category** (All / Spawners / Enchanted / Gear / Blocks / Food / Items), **Search**
  (click → type in chat, consumed not broadcast), **Reset**. Console/RCON path unchanged (text dump per page).
- Config: `discarded-vault.cache-seconds` (default 15).

**Follow-ups same day (staging):**
- **Full item details captured (view-only, no withdrawal — the anti-dup design is kept).** New
  `discarded_ledger.details` column. On destruction, `describeItem()` records a compact readable string for
  *notable* items — **shulker box contents** (aggregated item list, incl. inner enchants), custom name,
  custom-data/relic marker, and durability-used. Plain commodities stay empty → still aggregate. The GUI renders
  it as lore ("Contents:" + indented list); search matches details too (find a shulker by what's inside). Going
  forward only; no historical rows touched (owner instruction).
- **Persistent, server-wide coordinate toggle.** New "Coordinates: ON/OFF" button (slot 52) in the vault GUI;
  state stored in DB `state` key `vault_show_coords` so it persists across restarts and applies for everyone.
  When off, entry lore drops the "at world x,y,z" location.
- **Player-placed spawner break: removed the hold-to-break timer.** The `relocated-break-seconds` (~10s) mining
  gate + `MINING_FATIGUE` are gone (they stacked annoyingly with the durability cost). The
  `relocated-durability-cost` (512) durability hit on recovery is **kept**. `SpawnerService.damage/breaking`.
- **Shardshop MARKET axe (`market_axe`, Netherite Axe): right-click now instantly sells the chest you're looking
  at** (`ShopService.sellAllChest`, ray-traced 6 blocks, protection-checked) instead of opening the Sell Basket.
  Only the `MARKET` branch changed; FELLER axe and the GUI Sell Basket button are untouched.
- **Auction listing fee is no longer refunded on cancel** (expiry already forfeited it). Failed listings still
  refund (the item never listed). `AuctionService.cancel`. Bounty placement fee intentionally still refundable
  (bounty cancellation is mod-only and refunds everything).

### Admin login persistence — refactor (staging) + enabled on PRODUCTION (config-only)

`TrustedAdminService` refactored to a clean, self-documenting pair: `trusted-admin.session-persistence` (master
switch — skip the /login password across restarts via AuthMe session restore) + `trusted-admin.require-same-ip`
(default true — constrain it to connections from the SAME IP as the server: its own machine / LAN IP / loopback,
via `isThisMachine`). Legacy `staging-session-persistence` (no IP constraint) and `same-machine-autologin` (IP
constraint) are still honored, so nothing breaks. **The refactored jar is staging-only** (it also carries the
rest of the unpromoted staging work, so it must NOT go to prod yet).

**On production it was enabled config-only, live, no restart:** set `trusted-admin.same-machine-autologin: true`
+ `/ashfall reload` — the running prod jar already has that mechanism, so admins (MacoCT/Asserto) reconnecting
from the server's own IP get their session restored; a different IP still requires /login. Reversible now via
that flag; when the full staging build is eventually promoted, prod switches to `require-same-ip: false` to drop
the constraint for external hosting.



**Deployed to production this session in a single announced (30s) restart** — and that same restart finally
promotes the entire 2026-08-14 → 2026-08-15 staged batch below (Central Bank deficit surcharge, enchanted-item
orders, duel polish + totems-in-duels + invisible-nametags, and the **BIG net-worth lag fix**), whose jar had
been staged on prod but never restarted. Confirmed every optimization in this log ships in this jar: NetWorth
non-asset fast-path (`removed()`/`blockChanged()` gate — the main lag fix), `BankService.deficit()` 1s cache,
arena wager-tax / ESC-cancel / totem-in-duel / spectator survival+hide, PacketNametag invisible-tag hook.

### xFPu bounty audit — NOT a bug
xFPu's ~200k bounty is legitimate player money, not a glitch. `bounty_contributions` shows **ofxbr** stacked
three manual bounties (100,000 + 95,000 + 100) on top of the single **5,000** auto-bounty (`BANK_AUTO`). The
auto-bounty formula is unchanged and correctly capped (base 5k × betrayal/victim scaling, hard cap 2% of
treasury). No code change.

### A single diamond now costs $1,000
`shop.yml` `DIAMOND.sell` 40 → **200**; buy is derived (`sell × shop.buy-multiple` = ×5) so buy = **1,000**
automatically. Applied to resource + staging + prod on-disk shop.yml. Pre-existing stale `shop.selfTest()` pin
(still expected the old **Dragon Egg 50M**, failing identically on prod) aligned to the live **2M** price so the
deploy self-test is green again — no economy change, the egg stays 2M.

### Idle auto-AFK (30 min)
`AfkService` now tracks `lastActive` per player (seeded on join, updated on block-movement and chat) and a
once-a-minute task flags anyone idle ≥ `afk.idle-minutes` (**default 30**) as AFK. Any movement/chat clears it,
exactly like the manual toggle. `shutdown()` cancels the task (wired in `onDisable`).

### Luxury shop — less spam, quantity, bulk buying
- **Announcements gated to big buys.** `celebrateLuxury` only broadcasts when the purchase total ≥
  `shop.luxury-announce-threshold` (**default 500,000**); smaller luxury buys are silent. Wind-Charge keeps its
  quiet local sound only.
- **Quantity in the shout.** Every luxury broadcast now reads "**… has bought Nx <item> …**" (elytra, dragon
  egg and the generic line all include the count).
- **Shift-click bulk buying now works in the Luxury section** too (was Shop-only): shift-click = **16×**.
  Removed the `amount=1` clamp in `ShopService.buy` for luxuries (affordability + inventory-fit still enforced;
  the mandatory ≥2M confirmation still applies at the higher bulk cost).

### Central Bank admin balance control
New `/ashfall bank <add|remove|set> <amount>` (accepts `k`/`m` suffixes); no-arg prints the balance.
`Database.adjustBank(delta)` does an authoritative `balance += delta` (may go negative — that's what arms the
deficit surcharge), wrapped by `BankService.adminAdjust`. Audited via `logAudit` (`BANK_ADD/REMOVE/SET`). Added
to admin help, section help, and tab-complete. Verified on staging: add/remove/set math exact, both guards fire.

### Elite Mobs settings toggle (subset of Hostile Mobs)
New **Elite Mobs** toggle (`elite_mobs`, default on), mirroring Hostile Mobs but scoped to elites:
- **Spawn block:** natural elite upgrades are gated on `SettingsService.elitesAllowedAt(loc)` in
  `BossEventService.onSpawn` — an elite won't form near a player with elites off unless a nearby player has them
  on (same influence radius + override as hostile spawns). World-event/CUSTOM elites are unaffected, exactly as
  world bosses are unaffected by Hostile Mobs off.
- **Despawn sweep:** the peaceful task now also removes ordinary elites (`isOrdinaryElite`) near elite-off
  players, with the same nearby-on-player override; toggling elites off sweeps immediately.
- **Subset rule:** because elites are a subset of hostile mobs, a player "allows" elites only when BOTH are on,
  and the Elite Mobs row is **hidden entirely** from the settings GUI (native/bedrock/chest all render
  `mainToggles(player)`) whenever Hostile Mobs is off. Elite Mobs is the last MAIN entry, so hiding it never
  shifts any other toggle's slot. `SettingsService.selfTest()` MAIN count 9 → 10.

---

## Session: 2026-08-14 → 2026-08-15

Branch `staging`. **Promoted to production 2026-08-16 (evening)** alongside the 2026-08-16 batch above, in one
announced restart — the deficit surcharge, enchanted orders, duel polish and the BIG net-worth lag fix below are
now live on prod.

### Enchanted-item orders + duel wager confirm + kit glow (staging)

- **Enchanted-item orders (DonutOrders-style, native).** New `enchanted:MATERIAL/ENCHANT/level/...` key type.
  Clicking an enchantable base item in the create picker opens an **enchant chooser** (`openEnchantPicker`): one
  book per enchantment the item can *legally* take (`enchantment.canEnchantItem` = the realism filter, so no
  Efficiency-on-sword etc.), left-click raises / right-click lowers (capped at vanilla max), and an enchant that
  **conflicts** with a chosen one locks out (`conflictsWith`). Confirm with none = a plain order. Matching
  (`matchesEnchanted`) is by item **TYPE + exact enchant set**, ignoring display name/lore, rejecting custom
  PDC (relics/bound) and damaged items. `canonical`/`display`/`categoryOf` all handle the new prefix; the
  buyer's stash receives the clean canonical enchanted item. Keys are composed at order time (sorted, so the
  same set = the same identity); the catalogue itself is unchanged.
- **Item-wager Confirm button.** The wager box now has a control bar (Back / Clear / Confirm). Items are
  escrowed to the DB **only on Confirm** (append), closing the box **returns** unconfirmed items, and Clear
  returns everything staked. No more silent commit-on-close. Dupe-safe: escrow is never pulled into an editable
  state, so an escrowed item can't be dragged back out for free. Control-bar slots are click/drag-locked.
- **Chosen kit glows.** The selected kit icon and the kit banner in the duel setup GUI now carry an enchant
  glint (`setEnchantmentGlintOverride`) in addition to the ✔ SELECTED label, so the chosen kit is obvious.

### Central Bank deficit surcharge (staging)

The Central Bank treasury may now go **below zero** (dropped the `balance>=?` guard on the shop-payout UPDATE).
While the treasury is at or below zero (`BankService.deficit()`), a single shared multiplier is applied
everywhere — no hand-doubled config values:
- **`BankService.buyFactor()` = 2.0, `sellFactor()` = 0.5** while in deficit (both 1.0 otherwise).
- **Every player→bank payment is doubled** by applying `buyFactor()` at each sink's amount computation (so
  display == charge == records): `/shop` + `/luxuryshop` buys (`ShopService.buy` + `MarketplaceService` GUI
  lore/confirm), omen/wandering shop (`MerchantService.purchase`/`purchaseWithSigils`), Ender Storage upgrades,
  personal + faction home slots, faction expansion, auction listing fee, and the **death penalty**
  (`takeFraction` percent+cap ×factor).
- **Every shop sell is halved** by applying `sellFactor()` at each earned computation (command sell, the
  `SaleQuote` builders, container sell-all, quicksell) so previews, confirmations and payout all match.
- The surcharge lifts automatically the moment buys/fees/sinks pull the treasury back above zero — it is a
  self-correcting recovery mechanism, computed live from `bank().balance()`, never stored.

Doesn't break existing bank flows: sinks still route through `serverPayment`/`factionServerPayment`, payouts
through `payShopSeller` (now allowed to go negative); the auction listing fee is factored once at the site so
its failure-refund matches; player↔player transfers, taxes and minted income (e.g. villager-trade income) are
untouched — only genuine player→bank sinks and shop payouts move.

### Duel polish + totems + invis nametags + BIG lag fix (staging live; prod jar staged, NOT restarted)

- **ESC out of the duel setup GUI = /duel cancel** (`closeSetup` — 1-tick check that ignores confirmed players
  and players who just stepped into the wager box/viewer or a refresh). Added `cancel` to /duel autocomplete.
- **Arena liquids now reset:** bucket-placed water/lava is tracked (`bucketEmpty` → `duel.placed`) so it's
  cleared between matches. Old leftover liquids also get wiped by the build-time interior clear.
- **Central Bank tax on duel winnings:** `arena.wager-tax-percent` (default 5%) taken from the money pot at
  finish (`DUEL_POT_TAX`) and from the spectator betting pool (`DUEL_BET_TAX`).
- **Totems work in duels:** the `lethal` round-resolver no longer intercepts a killing blow if the duellist is
  holding a Totem of Undying — vanilla pops it and the round continues. (The spear kit carries a totem; it was
  being bypassed every time.) Boss-totem case audited: `BossEventService.onAnyDamage` only touches boss mobs
  (has `tierKey`), never players, so it doesn't break player totems — that rare case is vanilla (double-hit).
- **Spectators stay in SURVIVAL** (the admin-only Spectator gamemode is deliberately never used for duel
  spectators — it makes them fully server-invisible, which is unintended). Instead: `hidePlayer(plugin, spectator)`
  from BOTH duellists hides the spectator's body AND nametag from the fighters robustly (regardless of nametag
  settings); `showPlayer` for everyone on leave. Hit sounds killed by cancelling a spectator's
  `EntityDamageByEntityEvent` at LOWEST (`specAttack`) and their `PlayerAnimationEvent` (`specSwing`), on top of
  the existing interact/pickup/drop guards. Admin Spectator-vanish logic untouched.
- **Invisible players' nametags hidden generally:** `PacketNametagService` treats `isInvisible()` like crouching
  (mounted overlay at zero opacity — suppresses the vanilla tag, shows nothing). (Edge: a viewer with ALL
  nametag options off would still see vanilla tags; a scoreboard team would be needed but TAB manages those.)
- **BIG lag fix (root cause of duper/machine lag):** `NetWorthService.removed()` / `blockChanged()` were doing
  5+ synchronized DB writes PER BLOCK, and they run per **exploded** block and per **piston-moved** block — so
  a TNT duper or flying-machine quarry was firing thousands of DB writes/second on the main thread. Both now
  fast-path return for non-asset blocks (only spawners/containers/dragon eggs/hoppers are tracked). Combined
  with the earlier bank-factor cache, this is the main code-level lag relief. Applied to the prod jar (staged),
  takes effect on the next production restart. NOTE: the heaviest remaining lag is the stacked-spawner farms
  (constant "Blaze x100" spawning/cramming) — intentional, not touched.

Prod jar staged this session but **production was NOT restarted** (per instruction) — the code fixes go live on
the next prod restart.

### ✅ MERGED TO PRODUCTION 2026-08-16 — everything below this session is now live on prod

Prod jar swapped (`plugins/SMPCore-1.7.0.jar`), prod configs updated (boss-chosen-price 1.5M, rtp radii doubled,
net-worth + shop Dragon Egg 2M; reconnect-grace uses the code default 10; `allow-piston-duplication` was set by
the owner). Booted clean: SMPCore enabled, arena ready, 1678-item catalogue, DB migrations applied with no
errors. Staging-only `trusted-admin.staging-session-persistence` deliberately NOT copied.

### Designed arena + 10s DC forfeit + lag pass (staging → merged to production)

- **Designed duel arena built** (`ensureArena`/`addShellSteps`, replaces the old procedural 61x61). Kit-aware:
  **50x50** for mace/sword/axe, **100x100** for spear. Floor = glowstone+red terracotta mix; walls = glowstone+
  obsidian mix from floor+1 to **world height**, top row **bedrock**. Black/red aesthetic. Structure unbreakable;
  player-placed blocks still break (unchanged). Built **spread over ticks** (one 24-block wall band per tick) so
  the ~50k–110k-block shell never freezes the server; the match only starts once the shell finishes
  (`startMatch` → `ensureArena(..., () -> beginRound)`). Cached per **(slot,size)** — a slot reused at a different
  size has its old shell cleared first, so spear and default share slots. `corner()` scales with size;
  spectators now watch from **inside** (walls are opaque). Multi-session per-slot temporary-snapshot model kept.
- **DC forfeit shortened to 10s** (`arena.reconnect-grace-seconds` 45→10, resource + staging; prod uses the new
  code default 10) with a **visible, non-chat countdown** — the remaining duellist and every spectator get an
  actionbar "X disconnected — forfeits in Ns" each second until reconnect or forfeit.
- **Lag/network:** `BankService.deficit()` is now **cached (1s TTL)**. It was hitting the synchronized `bank()`
  SELECT on every `buyFactor()`/`sellFactor()` call — dozens per shop-GUI open — a needless query storm on a
  hot path. Now at most one bank read per second regardless of call volume.

### Duel/vault fixes + relic wagering (staging) — pre-merge pass

- **CRITICAL item-loss fixed.** `awardItemWagers`/`refundItemWagers` ran BEFORE `returnPlayers()`, and
  `restore()` does `setContents()` — so the restored pre-duel inventory overwrote the just-awarded/refunded
  wager items and they vanished. Now award/refund runs AFTER `returnPlayers` in both `finish` and
  `abortAndRefund`. This was the "wagered items disappear" bug.
- **Wager counts update live.** `refreshSetupOpen(duel)` re-renders the setup GUI for whichever duellist is
  currently viewing it (never yanks anyone out of the box) on confirm/clear, so both sides see counts update.
- **Relic wagering allowed.** `ArenaService.isWagerBox()` + `RelicService.isPersistentStorage()` exemption lets
  relics be staked in the wager box (they were blocked as chest "storage"). Dupe-safe: one escrowed copy, and
  the winner's next inventory scan transfers relic ownership; duels are far shorter than the reclaim clock.
- **Wager Back / opponent view.** Wager-box Back returns to setup instead of closing; setup shows both wager
  counts and a "View <opponent>'s wager" read-only screen.
- **Vault paging fixed** — paginates in memory (fetch-all), so the ◀/▶ page arrows always navigate correctly.
- **Vault spawner display fixed** — destroyed spawners show "<Type> Spawner" with the vanilla "interact with a
  spawn egg" hint hidden (`HIDE_ADDITIONAL_TOOLTIP`), instead of a generic-looking egg tooltip.
- **TNT dupers** — audited: cause is Paper `unsupported-settings.allow-piston-duplication: false` (default), NOT
  SMPCore (its piston guard only blocks pistons crossing a claim edge). Owner set it `true` manually on both
  staging + production. Also enables sand/gravel/carpet/rail dupers (economy note).

**PENDING (next session): the designed duel arena map.** Spec captured (50x50 default black/red for
mace/sword/axe; 100x100 for spear; glowstone+obsidian walls to world height + bedrock cap; glowstone+red
terracotta floor; unbreakable structure, player blocks breakable; keep the per-slot temporary-snapshot model).
Deferred deliberately: full-height walls are ~50–100k blocks/slot, so `buildSlot` needs a spread-over-ticks (or
build-at-STAKING) rewrite + per-(slot,size) build cache + scaled spawn corners, not a one-tick main-thread build
that would freeze the server. Not game-breaking; the current procedural arena still works meanwhile.

### Promotion status — what should go to `main`/production vs stay staging-only

**Safe to promote to production (all of this session's commits — general fixes/features, no staging-only flags
added):** `6395ff1` regression sweep · `bdd8947` duel ready-gate · `bda5664` item wagering + per-round betting ·
`16d96ea` vault paging / dragon-egg 2M / rtp×2 / excavator trade-rebind / monument mob exemption / faction-home
validation / AFK-notify / elite-hunt-end · (this commit) shulker sellall.

**Owner specifically wants these ON production** (they were reported as prod-visible): the boss **damage-recap**
for the 3 custom bosses (already on the world-boss path — prod just hasn't been promoted), the **regression
sweep** (boss price, /shop cooldown, pet penalty), and **Dragon Egg 2M**.

**Config files that must be copied on a production promote (not carried by the jar):** `config.yml` (rtp radii,
`merchants.boss-chosen-price`, net-worth DRAGON_EGG) and `shop.yml` (DRAGON_EGG buy). Use
`deploy/promote-to-production.ps1` / `deploy/check_deploy.py`; the on-disk prod ymls need the same edits made to
staging's.

**Staging-only, do NOT promote:** `trusted-admin.staging-session-persistence` (AuthMe remote-admin persistence),
and anything else tagged staging-only in `deploy/manifest.yml`.

### Regression sweep (owner-reported)

- **Boss chosen-summon price 2,000,000 → 1,500,000** (`merchants.boss-chosen-price`, config.yml + staging
  on-disk config). Was silently bumped; reverted.
- **`/shop` fully exempt from the universal command cooldown.** Added `/shop` to `commandExempt` in
  `GameplayListener` so the duplicate-command guard AND the minimum-interval/violation rate limit both skip
  every `/shop …` (notably `/shop sell`, `/shop sellall`, `/shop sellall chest`). This is the ONLY command
  exempted for spam-rate reasons; all others keep the cooldown.
- **Killing cats/dogs/parrots now always costs money.** `friendlyPenalty()` previously returned 0 for an
  UNTAMED wolf/cat/parrot, so those fell through to the positive `mob-rewards` payout. Removed the tamed
  gate: any wolf/cat/parrot now incurs its `mob-penalties.<TYPE>` charge (negative money) regardless of
  tamed state. Tamed pets were already fined; wild ones were the leak.
- **Randomly-placed world bosses restricted to flat biomes.** `randomSafeBossSpawn()` (reached ONLY when no
  explicit location is supplied — i.e. natural/random placement; admin & player summons pass a location and
  bypass it) now also requires `flatBossBiome(loc)`: overworld spawns must be plains/savanna/desert/
  snowy_plains/meadow/beach/swamp/mushroom_fields etc. Nether/End keep their existing terrain gate only.

### Verified already-correct (audited, no change needed)

- **Effective HP ordering** Colossus (6300) > Ashen (~4980) > Cinder (~3580) — asserted by the existing
  world-boss rebalance self-test.
- **Boss reward split is HP%-share** — `splitReward` divides the pool by each participant's damage over the
  summed participant damage (≈ boss HP), not by absolute damage.
- **Damage-recap on the world-boss path** — `sendDamageRecap` already runs for the 3 custom world bosses in
  `rewardElite`. It "works for vanilla but not custom" on PRODUCTION only because this fix (staging) has not
  been promoted. No staging code bug.

### Deploy

Built green, swapped `plugins/SMPCore.jar` on staging (0 players online, no announce needed), relaunched via
the conhost schtasks hatch — console is a real `cmd` window, SMPCore enabled with no errors.

### Duel overhaul (staging)

**Ready-gate round flow.** Every round now opens behind a ready-gate: both duellists are teleported to their
corners, fully re-equipped/healed (equip() already tops health/food/effects), then FROZEN on their block
(`freeze`/PlayerMoveEvent, look-only) and made unhittable (`gateShield` cancels damage at LOWEST;
`combatOverride` no longer un-cancels while `duel.gating`) until each clicks Ready in a 27-slot GUI.
`startFight` drops the gate the instant both are ready. `Duel.gating` + `Duel.roundReady` track it per round;
`beginRound` re-runs between rounds so every round starts identical. Closing the Ready GUI while gated and
not-ready reopens it (`closeGate`). `lethal` skips while gating; disconnect during the gate forfeits via the
existing reconnect-grace tick (phase stays LIVE throughout).

**Item wagering (DB-escrowed, crash-safe).** New `arena_item_wager(duel,player,items BLOB)` table. A "Wager
items" button in the setup GUI opens a real 54-slot box (`Menu.fillable` — clicks/drags allowed for it only);
on close the contents are serialised to the DB escrow. Winner takes both sides' items (`awardItemWagers`;
overflow drops at their feet, or to the order-stash if offline via `stashAddItem`). `refundItemWagers` on
abort, `arenaItemWagerRefundAll` on boot — items are never lost. Separate from the money stake.

**Per-round spectator betting.** `Wager` gained a `round` field (0 = whole match, N = that round). Betting is
now open through each round's ready-gate (`bettingOpen`), not just before the match. The spectate GUI has a
"this round / whole match" scope toggle (best-of-3+), shows the scoped pool per fighter and lists all of the
viewer's wagers. Round bets settle the instant that round ends (`settleWagerScope` in `roundOver`); match bets
settle at `finish`. `arena_wagers` gained a `round` column; `syncWagerDb` keeps the crash-refund mirror in
sync as scopes settle. One wager per spectator per scope; changing a scope refunds the old stake.

### Second regression/feature batch (staging)

- **Discarded ("Ashfall") Vault — in-GUI paging.** `DiscardedVaultService.show` now renders ◀/▶ page arrows
  (holder carries the page; click navigates), reachable without retyping `/ashfall vault <page>`. Its info book
  spells out the two states it tracks — **Destroyed** (items that permanently left the world) and **Recycled**
  (eligible commodities returned to shop stock). There is NO "delivered" state in the vault; "delivered" is an
  Orders concept (a seller delivering goods to a buyer's stash), unrelated to the vault ledger.
- **Dragon Egg worth 2,000,000.** `shop.yml` buy 50,000,000 → 2,000,000 (was level with the Iron Golem
  spawner, which produces ongoing value, so nobody bought the egg); `config.yml` net-worth base-value
  1,000,000 → 2,000,000 so its valuation matches. Resource + staging on-disk.
- **/rtp range doubled.** `rtp.radius` 5000→10000 and `rtp.radii` overworld 5000→10000, nether 4000→8000,
  end 5000→10000. `random-spawn.radius` (first-join scatter) left alone. Resource + staging on-disk.
- **3×3 excavator re-binds on trade.** Shard-bound tools are soulbound (drop blocked) but AxTrade's GUI
  hand-off bypassed that, leaving the tool bound to the seller so `belongsTo` refused the buyer and the 3×3
  died. Added `ShardService.reconcileOnInventoryClose` (mirrors the relic one): one tick after any inventory
  closes, re-bind any bound tool the closer now physically holds to them. Only ever one physical copy, so no
  duplication.
- **Ocean monuments exempt from Hostile Mobs Off.** Mirrored the bastion logic exactly: `MONUMENT_GARRISON`
  (Guardian, Elder Guardian) + `isMonumentThreat` (type AND inside a `Structure.MONUMENT` box, overworld),
  used on BOTH the removal sweep (`removableHostile`) and the spawn gate (GameplayListener), same as bastions.
- **Stale faction /f home disabled + removed.** `/f home` now validates the home still sits in THIS faction's
  claim (`claimAt(...).faction.id()==f.id()`); if the base was destroyed and the faction moved/unclaimed, the
  stale home is deleted and the teleport refused, instead of dropping into lost/hostile ground.
- **Unified AFK-notify.** New `AfkService.notifyIfAfk(initiator,target)` is the single source for the "you
  pinged someone who's away" line; `/tpa`, `/tpahere`, `/msg` and `/duel` all route through it (consistent
  wording, one-liner to add more). `/trade` is AxTrade's own command (external) and not routed. No behaviour
  change beyond the notice.
- **Elite Hunt ends when its elite is gone.** Track the elite's UUID; a credited kill already ends the event,
  and a tick check now ends it on any other disappearance (no-credit death, void, /kill, removal). A
  chunk-loaded guard on the last-known position prevents a brief chunk unload from ending a live hunt.
- **Chat lag — NOT an SMPCore bug (owner-confirmed).** Owner recalls this was already audited previously and
  traced to **console / server-side settings**, not the plugin. Re-verified the SMPCore chat path is clean
  (every handler async or early-return; `logChat` is an async WAL insert). Do not re-audit SMPCore for this.
- **/shop sellall chest now reaches inside shulker boxes.** `containerSellableDeep` / `removeMaterialDeep` make
  the chest sell path count and sell the sellable items INSIDE shulker boxes sitting in the container (the
  shulker itself is never sold — it's emptied and written back). The plain `/shop sellall` (player inventory)
  path is deliberately unchanged.
- **Destroyed BLOCKS now go to the vault (burnt + exploded), AUDIT-ONLY.** `BlockBurnEvent` records burnt
  trees/wool/carpet etc. (fire never drops the block → a genuine loss); the existing explosion hooks now record
  every non-spawner block in the blast too (`deliverBlock`, reason BURNED/EXPLOSION). Deliberately **never
  recycled into shop stock**: an exploded block may also have dropped as an item, so recycling would mint a
  duplicate — so these are logged as *Destroyed*, not *Recycled*. Aggregated by material+reason (a big blast is
  a few rows). Gated by `discarded-vault.record-destroyed-blocks` (default true). `recordableBlock` skips
  air/fluids/fire/technical blocks.

---

## Session: 2026-08-12 → 2026-08-13

Branch `staging`. Production was updated once mid-session (commit `6cbb3e8`); everything after that is
staging-only and awaiting approval.

### Orders — DonutOrders removed, native system built

**Why.** DonutOrders keyed orders by `Material`. Every spawner is `Material.SPAWNER`, so a Blaze Spawner
order could be filled with a Cave Spider one and there was no way to express the difference. Its storage was
actually fine (a serialised `ItemStack` per order) — the limitation was its creation flow and the
`getAllowedMaterials()` API. Working around it was costing more than replacing it.

**Canonical identity.** Orders now carry a key naming exactly what satisfies them:
`vanilla:DIAMOND`, `vanilla:ENCHANTED_BOOK/SHARPNESS/5`, `smpcore:spawner/BLAZE`. Matching is by identity,
never display name. Spawner types are read from SpawnerService's own value registry, so a type added there
later becomes orderable with no code change. **1,678 orderable items, 15 spawner types.**

**Escrow.** Money leaves the buyer once at creation and lives in the order row. Every later movement is a
conditional UPDATE that checks and mutates in one statement — the same reservation pattern the finite shop
stock uses. Fulfilment reserves before touching the seller's inventory; a short removal reverses the
reservation and returns exactly what was taken. Closing is compare-and-swap on the escrow figure the caller
read, so a second cancel or expiry sweep cannot match it and cannot refund twice.

**Migration.** DonutOrders had 14 orders, **zero active**, and an empty stash — nothing was in flight. Its
14 orders and 21 transactions were archived to `legacy_donut_orders` / `legacy_donut_transactions`.

**Money it owed.** Its transaction log contains only CREATE / CANCEL / FULFILL — *there is no refund action
in the schema at all*, and every EXPIRED order still held the money taken at creation. Seven orders were
stranded: **$400,305** (Asserto 400,300 · MacoCT 4 · TPKIID 1). Refunded on staging with ledger entries.
**Production is owed the same amount and has not been touched** — the production migration must repeat this
with an idempotency check so nobody is paid twice.

### Industrial Hopper

Rewritten around **one authoritative inventory** after the first version could duplicate items without
limit. Root cause was ownership: opening the screen built a *copy*, and closing it wrote that copy back over
whatever the transfer loop had done. Now one live `Inventory` per hopper, opened directly; native five slots
kept permanently empty; all transfers remove-before-add.

Verified: **34/34 conservation checks + 5/5 removal runs**, zero created, zero lost.

Two follow-ups worth remembering: the sweep period was **hardcoded to 1 tick** while the config claimed
otherwise, so the first throttle attempt did nothing; and `Inventory.getItem()` returns a **live mirror**, so
overwriting a slot invalidates a reference still held — that turned a refund into air. Final cadence is
**9 items per 8 ticks**, matching vanilla's hopper cooldown.

### Economy

- **Spawner mob money simplified.** Player-placed spawner mobs pay a flat 50%, dropping to 25% past a daily
  allowance of **10,000 represented mobs** of that type (resets 12:00 Asia/Riyadh). The anti-farm
  window/soft/hard curve no longer touches spawner income; natural mobs keep it. This removed a cliff where
  the halved spawner share was gated on `factor < 1`, so the 17th kill in ten minutes cut pay by half again.
- **Actual-spawner audit.** Earlier comparisons modelled Creeper/Guardian/Enderman spawners *that players
  cannot obtain*. Corrected to the three that exist: Blaze **$25,560/hr** (100%), Cave Spider **$14,846/hr**
  (58%), Magma Cube **$14,400/hr** (56%). Iron Golem is a separate luxury tier at **$84,960/hr**.
- **Prices raised** (arbitrage-checked, nothing lowered): string 2.35 → 3.20, magma cream 5.49 → 14.50
  (still under its 15.06 craft cost), farming category **×2.6** across 36 items — sugar cane 1.57 → **4.08**.
  Bread was pulled back to 7.40 because ×2.6 put it above 3× wheat and made a crafting table print money;
  that loop existed before this pass at a smaller margin and is now underwater.
- **Transfer taxes removed.** `pay.tax-percent` and `trade-tax.percent` both 0. The AxTrade hook stays wired.
- **Trade tax bug found on the way out.** It had been taxing **zero** since it shipped: it asked AxTrade for
  a currency named `money`, and `HookManager.getCurrencyHook` returns null for an unknown name, so
  `getCurrency` returned 0.0. The hooks are called `Vault` and `Experience`. Now identified by hook, not name.
- **Iron Golem Spawner at $50m: justified on output, unreachable in practice.** ~$1.2m/day for a mature
  farmer → ~41 day payback, against a Dragon Egg at the same price yielding nothing. But the richest player
  holds $6.8M, so it is currently an aspiration rather than a purchase.

### Bosses

- **Cinder Warlord root cause.** Three STRENGTH buffs sat outside the calibrated damage attribute *and*
  outside the enrage cap: the phase mechanic used `amplifier = phase` (Strength IV by phase 3, +12), the
  phase-3 berserk added permanent Strength II, and **standing in lava granted Strength III, refreshed
  continuously** — that last one is why lava fights specifically turned absurd. All three removed; enrage now
  owns damage progression outright and lava keeps only its defensive Resistance.
- **Pit recovery, all three bosses.** After 20s unable to reach anybody, the boss is placed on solid ground
  at its target. Clearing blocks above a boss in a hole just drops it back in.
- **Damage recap** to participants on a shared boss kill, from the same contribution map the reward split
  uses.
- Damage reverted to the previously approved values, then +10% on Ashen (18.7) and Colossus (20.35) by
  request. Cinder unchanged at 18.

### Task Master

Contract board rewritten: 36 contracts with a reason behind them, **paid by effort not item value** (steps /
travel / risk / grind, plus a per-extra-item term for collections). Wool all 16 colours 100,000; beds all 16
colours 196,250. Batches persist across relogs and restarts and do not change one contract at a time; the
next batch is dealt only when the whole set is done. Courier is invisible with the trader swirl, tethered to
50 blocks — `addPotionEffect` silently fails on that entity, so the effect is drawn directly.

### Discarded-item vault

Event-driven audit of what genuinely leaves the world; eligible plain commodities return to shop stock.
Destroyed spawners now enter it with their type preserved (`SPAWNER_BLAZE`), never recycled into stock, one
entry per destroyed physical spawner. Verified with TNT.

### Login notifications

`GameplayListener` and `OrdersService` were **both** calling `loginSummary`, so every notice arrived twice.
One caller now. Only things that happened while away are reported — deliveries received, orders expired or
completed — each exactly once, tracked by the filled count already reported. An order merely still being
active is not news and is no longer mentioned.

### Staging conveniences

- AuthMe staging session timeout: admins get the same persisted 30-minute session as normal players, so a
  staging restart does not force `/login`. Production untouched.
- `/ashfall hopper rig|count|create`, `/ashfall vault`, `/ashfall ordersdebugcreate` — console diagnostics
  that made the conservation testing possible.


### Orders UX pass (2026-08-14)

- **Delivery is now manual.** Clicking an order opens a screen where the seller *places* items into the top
  three rows; nothing is ever pulled out of their inventory for them. It shows what is still needed, how
  many matching items are inserted, what will actually be delivered, and the payout after tax.
- **Confirmation on every consequential action.** Delivering, cancelling an order and removing one from
  history all arm on the first click and commit on the second, with the button visibly changing state.
  Order creation already had a confirm screen.
- **Closing returns everything.** Inserted items come back on close, on Back, and any surplus beyond what the
  order needed comes back after a partial delivery. Items taken for a delivery come out of the SCREEN, after
  the reservation succeeded -- never speculatively from the player.
- **History hiding.** Finished orders can be removed from the owner's list. UI only: the row, its escrow
  trail and the ledger entries are untouched, and hiding is refused on ACTIVE orders.
- **Layout.** Consistent nine-slot bottom bar across screens, glass filler so furniture is obviously not
  interactive, page indicators with counts, back buttons everywhere.

### AuthMe staging persistence -- the actual fix

The mechanism already existed: , which lets an admin account use
AuthMe's own same-IP session restore, but only when the connection is from this physical machine. It
defaults to false and is deliberately absent from the shipped config resource, so production stays locked
down no matter which jar is built. **The earlier attempt changed the session timeout instead, which granted
admins nothing** -- reverted. Now set in staging's live config only, and **verified in the log:**
.

### Conservation audit against real usage

Order #5 (MacoCT, 100 Blaze Spawners at 1,000, 35 filled by Asserto):
100,000 escrowed = 35,000 released + 65,000 still held, and the seller received 34,125 = 35,000 x 0.975.
Every ORDER_ESCROW / ORDER_REFUND / ORDER_SALE ledger total ties out across all three players.


## Phase 2 — native 1v1 wager arena (2026-08-14, staging only)

**World.** `ashfall_arena`, a void world generated by an empty ChunkGenerator with the arena built in code:
a 41x41 smooth-stone floor, a slab wall, and a glass spectator gallery above it. Built rather than
downloaded — there is no third-party world to vet, and nothing to break on load. Verified by block probe:
floor, both corners, wall, gallery and surrounding void all correct. Mob spawning, weather and the day cycle
are off; keep-inventory and immediate respawn are on.

**Kits.** Mace, Sword + Shield, Axe, Spear. Protection IV diamond is the shared baseline so the weapon
decides the fight; gapples are the only healing. Mace gets wind charges, Sword a shield, Axe cobwebs, Spear
an elytra and 64 rockets. Both duellists always receive an identical copy — the self-test builds each kit
twice and asserts every stack matches.

**Money.** Stakes are independent and may be zero; the winner takes both in full. Every stake and wager is
debited up front and held in `arena_escrow` / `arena_wagers` in the database, never in memory, so a
restart mid-match cannot lose or invent any. Spectator betting is pari-mutuel and never server-funded:
winners get their stake back plus a share of the losing pool proportional to what they risked; money on one
side only returns stakes on a win, and goes to the Central Bank on a loss. Duellists cannot bet on
themselves. Betting closes at match start.

**State.** Location, inventory, armour, offhand, XP, health, hunger, effects and gamemode are written to
`arena_state` before anything is touched, and restored exactly afterwards — including on reconnect after a
restart. Arena deaths are intercepted at LOWEST priority: drops cleared, XP kept, death message suppressed,
so no graves, penalties or rewards fire. A 45-second reconnect grace applies before a disconnect forfeits.

**Commands.** `/duel <player> | accept | decline | kit | series | stake | confirm | bet | watch | status |
cancel`.

**Not yet done:** the live two-player match. Everything above is code-verified and the arena is built and
probed, but an actual duel needs two humans in it.


## Session: 2026-08-14 (part 2) — staging only

### AuthMe: the real MacoCT cause
Asserto keeps their staging session but MacoCT did not, because same-machine-autologin only helps a
connection FROM the server box -- Asserto's stored IP is one of this machine's own LAN IPs, MacoCT's is
remote. New staging-only flag `trusted-admin.staging-session-persistence` lets an admin keep AuthMe's own
same-IP 30-minute session regardless of machine. Absent from the shipped resource and never copied by a
deploy, so production admins still authenticate every time. The earlier attempt only changed a timeout and
did nothing; reverted.

### Task Master: value floor
Rewards were pure effort and ignored item worth (2 notch apples worth ~$2m for $27.5k). Reward is now
max(effort, valueFloor); valueFloor = the items' real economy value (luxury buy, else shop sell, else a
scarcity table) x1.15, held BELOW purchase cost so it can never create buy-then-deliver arbitrage. Effort
still wins for cheap grind contracts. gapple (now 1 apple) $27.5k -> $850k, heart -> $170k, shell -> $136k,
conduit -> $483k; blaze/cookie/nautilus unchanged. Noted but NOT changed: cheap purchasable bulk items
(e.g. blaze rods buyable at ~$60, effort reward $23.8k/16) are a PRE-EXISTING effort-vs-buy arbitrage,
left alone to respect the owner's effort-based design.

### Central Bank villager income
$100 minted into the bank per emerald a completed player->villager trade actually consumes (base +
specialPrice on the first ingredient, i.e. after reputation discount and demand). PlayerTradeEvent fires
once per trade, so shift-clicking counts exactly. Player gets nothing.

### Duels rebuilt for concurrency
Full rewrite: multiple simultaneous matches, each in its own arena slot 2,048 blocks apart, isolated
escrow/wagers/state per Duel. Friendly-fire/PvP override between the two opponents only; spectators and
outsiders can never deal or take damage. All commands blocked while duelling except /duel forfeit. Kit
blocks can be placed/broken; the arena map never can; placed blocks tracked and cleared each round.
Per-round arena reset, full state restore incl. reconnect. Challenge accept is a chat prompt like a trade
request (clickable, cooldown), NOT a sudden GUI; stakes set in the GUI with -/+ buttons; /duel has full tab
completion. Still needs the live two-player match test.

### Boss (low priority)
- Pit-recovery teleport REMOVED entirely. A boss warping to whoever it chases (potentially to their base)
  was worse than one stuck in a pit; a boss in a pit is now intentional design -- fight it on open ground or
  in an arena you build.
- Damage recap now fires on the WORLD-BOSS path (Ashen/Colossus/Cinder). It previously only ran for vanilla
  dragon/wither deaths, so the fights players actually do never showed a recap -- that was the "not
  implemented correctly" report. Shows name + raw damage + percent to participants, once.

### Orders GUI polish
Richer, viewer-aware cards: remaining quantity in the title, unit price and value-left, status colour
(active green / completed aqua / closed red) with relative expiry, and a live "you are carrying N -- can
fill N" line on the public board. Manual delivery basket, confirmations and history hiding from the prior
pass retained.


## Session: 2026-08-14 (part 3) — duel/orders bug fixes, staging only

### Duel state restoration (was incomplete)
capture/restore now cover EVERYTHING the duel touches: inventory, armour, offhand, world/location/rotation,
level, exp, health, food, gamemode -- PLUS the previously-missing saturation, exhaustion, potion effects
(serialized), allow-flight, flying, fall distance, fire ticks and remaining air (packed into a new
arena_state.extra column, guarded ALTER). Both winner and loser are restored, on BO1/BO3 completion,
forfeit, disconnect, cancellation and shutdown recovery.

### No-death round resolution
Rounds are now resolved by INTERCEPTING the killing blow (EntityDamageEvent at HIGHEST): the lethal hit is
cancelled, health topped up, round awarded -- so PlayerDeathEvent never fires for a duellist. No grave, no
drops, no respawn yank to the overworld, no economy/faction death side effects, ever. A per-round
"resolving" guard stops a double-resolve from two simultaneous lethal hits or a death racing a disconnect.
The old death handler is kept only as a safety net (e.g. /kill), and a respawn handler redirects any
duellist who somehow dies back to their captured spot.

### Kits audited and fixed
Refactored into explicit kitArmour/kitWeapon/kitOffhand/kitConsumables. Fixed the Spear giving BOTH a
diamond chestplate and an elytra (the chestplate was silently overwritten); the Spear now wears only the
elytra. equip() places each piece deterministically, clears offhand, and resets flight/fall/fire/air so no
survival state or item can leak in, and restore() wipes the kit afterward. Final contents reported to the
owner.

### Duel setup GUI now viewer-relative
The confirm panel rendered a fixed "You: <player a> / Them: <player b>" for both sides, so Asserto saw
herself as "Them". It is now rendered from each viewer's own perspective (You = the viewer), and both open
GUIs refresh when either player changes kit, series, stake or ready state.

### Arena map
Rebuilt the procedural arena: a 61x61 quartz/andesite floor with a bordered wall, quartz corner pillars,
sea-lantern lighting, a raised glass spectator ring, and OPEN SKY (no ceiling) so Mace launches and
Spear/elytra flight have room. Built once per slot and only the duellists' placed blocks are cleared each
round, so the map is never modified. External download was NOT used: a third-party world could not be
fetched and its contents safely vetted in this environment, and the multi-slot system needs one identical
stampable layout -- so a polished procedural arena is the safe choice. Can paste a specific vetted
schematic per slot if the owner supplies/approves one.

### Orders navigation fixed
The Back button on "Your orders" did nothing -- it had moved to slot 45 but the handler still only checked
slot 49. Back on Mine and Stash now works (slot 45 -> public), Pick's Back stays at 49 (45 is its Search),
the delivery basket's Back returns to the public board after handing items back, and the stash screen got a
Back button so it is no longer a dead end.


## Session: 2026-08-14 (part 4) — player-facing duel/orders finishing, staging only

### Kits corrected to real PvP loadouts
- Spear now uses a TRIDENT (Loyalty III, Impaling V, Unbreaking III) -- the actual spear weapon -- not a
  diamond sword. Elytra chest + 64 rockets.
- Mace: Mace (Density V, Wind Burst III, Unbreaking III) so the launch-and-slam loop works, 64 Wind Charge,
  128 building blocks (cobblestone) to tower for the slam, 2 notch + 16 gapple.
- Sword+Shield: Diamond Sword (Sharp V, Fire Aspect II, Unbreaking III), Shield (Unb III), 2 notch + 16 gapple.
- Axe: Diamond Axe (Sharp V, Efficiency V, Unbreaking III), 8 cobweb, 2 notch + 16 gapple.
- All armour Prot IV + Unb III. Both duellists always get identical copies (self-test asserts parity).

### Spectator: dedicated state + betting GUI
Duel spectators now use a self-contained state (NOT the admin /spectator vanish system): survival, empty
temporary inventory, flight, permanent invisibility, non-colliding, invulnerable, and blocked from
pickup/interact/drop/build. Full state + location captured on enter and restored on leave. A proper
Spectator GUI (/duel watch <id> or the hub match list) shows both fighters, kit, Bo score, stakes, per-side
pools, wager +/- controls, confirm/change-before-lock, current personal wager, enter-arena, and leave.
Wagers can be changed before lock (old stake refunded). GUI live-refreshes on score/wager changes.

### Duel setup GUI viewer-relative (finished)
Confirm panel is rendered per viewer (You = the viewer), with both sides' stakes and ready state, refreshing
both open GUIs when either changes anything.

### Orders marketplace rework
/orders now opens a proper marketplace HUB with sections: Browse & fulfil, Create an order, My active orders,
Claim deliveries, Order history. Create is category-first (Spawners, Enchanted Books, Ores & Minerals,
Combat & Tools, Food & Farming, Redstone, Blocks, Everything Else) with per-category search and pagination,
instead of dumping the whole registry. My-orders and history are separate views. Claim deliveries is the
persisted stash (offline/restart safe, partial fulfilments accumulate). Manual delivery basket, confirmations
and history-hide retained. Back navigation audited across every screen and returns to the logical parent.

### Arena
Improved procedural arena from last part (61x61 quartz/andesite, wall, corner pillars, sea lanterns, raised
glass spectator ring, open sky for Mace/elytra) stands as the current arena. An external downloaded map was
not integrated: a specific arena schematic/structure could not be safely sourced and content-vetted in this
environment. The slot system is ready to stamp a supplied .nbt/.schem per slot on request.


## Session: 2026-08-14 (part 5) — real Spear kit; map honesty

### Spear kit: the real vanilla Spear
Our Paper 26.2 build genuinely has spear items (WOODEN_SPEAR..NETHERITE_SPEAR) and the spear-exclusive
Enchantment.LUNGE. The Spear kit now uses DIAMOND_SPEAR (Sharpness V, Lunge at max level, Unbreaking III),
NOT a Trident. The elytra + rockets were removed because Lunge does not work while elytra-flying, which would
break the kit's own mechanic -- it is now a ground reach/lunge bruiser with a diamond chestplate and the
same heal loadout as the sword. Lunge level is clamped to >=1 for safety.

### Arena / map -- honest status
A real downloaded designed map was NOT installed. In this environment I could not source a specific,
verified, license-clear arena schematic that fits the per-slot clone model: Modrinth 'maps' are full world
zips (not slot schematics and not content-verifiable), and I have no guaranteed arena-schematic URL. WorldEdit
IS on the server, so pasting a supplied .schem per slot is straightforward -- the missing input is the arena
file itself. Did NOT ship another self-made arena this round per the owner's explicit instruction. Unblock:
owner supplies a .schem (or a direct URL) and it is pasted into each slot with per-round reset.


## Session: 2026-08-14 (part 6) — orders revert, netherite kits, duel fixes

### Orders reverted to the simple two-screen flow
/orders opens the browse/fulfil page directly; /order opens the item picker showing ALL items directly. The
marketplace hub is gone from the flow. Category is now an optional FILTER on the picker (cycle button,
default All), not a mandatory category screen. The only real gripe -- the bottom action buttons bunched to
the left -- is fixed: they are spaced across 45/47/49/51/53 with pagination at 46/52. Delivery basket,
claim/stash, history-hide and Back-to-parent all retained.

### Netherite duel kits (owner-specified layouts)
All four kits rebuilt to netherite tier with explicit hotbar layouts and splash potions:
- Elytra Spear: Netherite Spear (Lunge + Sharp V + Unb III), rockets, ender pearls, gapples, a spare
  netherite chestplate to hotswap, splash Healing II, water bucket; elytra chest, Totem offhand; backups.
  (Reinstated the elytra kit as requested, now with the REAL Netherite Spear.)
- Mace: Mace (Density V, Wind Burst III, Sharp V, Unb III), 64 wind charges, gapples, pearls, 4x splash
  Healing II, water bucket; netherite armour, shield offhand; backups.
- Sword+Shield: Netherite Sword (Sharp V, Fire Aspect II), Bow (Power V), Crossbow, gapples, splash
  Healing/Swiftness/Fire-Res, water bucket; shield offhand; arrows + backup shields.
- Axe: Netherite Axe + backup Netherite Sword, crossbow, cooked beef, splash Healing/Swiftness, water
  bucket; shield offhand; backup shields.
Both duellists get identical copies (self-test parity holds).

### Duel fixes
- Friendly-fire spurious message: two guildmates duelling triggered the faction PvP guard, which printed
  "Friendly PvP is disabled" while the arena override let the hit land. Duel opponents now bypass the
  faction/spawn PvP checks entirely -- no message, damage as intended.
- PvP-lock actionbar suppressed for duel opponents (no "PvP teleport lock" above the hotbar mid-duel).
- Graves hard-disabled in the arena world (guard in GraveService.create), so no grave or grave compass can
  ever appear in a duel.
- Setup GUI shows the chosen kit prominently (banner at the top + SELECTED tag on the picked kit).

### Still to do (the round-flow redesign)
Ready-gate rounds (both frozen on a block until each clicks "I am ready", auto-forfeit on disconnect,
betting window during the gate, reset+heal+ready between BO3 rounds) and per-round spectator betting are the
remaining large piece.

---

## Standing lessons

1. **Restart staging by stopping it first.** Relaunching without stopping leaves the old JVM holding the
   port; the "restart" silently does nothing and every later test runs against the previous jar. This cost a
   whole round of results once.
2. **Console measurement lies in four specific ways**: `/data get` truncates long NBT over RCON; reading
   three containers with three commands is not an atomic snapshot; spilled items fall off one-block platforms
   out of range; and entity flags like `Invisible` are synced state, not saved NBT.
3. **Trust the plugin's own log over reconstructed console arithmetic.**
