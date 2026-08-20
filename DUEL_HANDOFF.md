# Duel Rebuild — Handoff

## Branch / commit

- Branch: `staging`
- Parent commit at handoff: `487d397`
- WIP checkpoint commit: see final commit on `staging` labelled `WIP: duel map/instance engine + pending batch (INCOMPLETE)`
- Nothing pushed. Nothing deployed to production. Production untouched.

### Changed files

New:
- `smpcore-src/src/main/java/net/communitysmp/core/DuelMapService.java`

Modified (this batch's relevant subset):
- `ArenaService.java` — duel kits only (mace enchants, Elytra, rockets, slow falling, steak)
- `BossEventService.java` — force-load ownership, Cinder Warlord roof spawn, special-tool split
- `WeeklyDragonService.java` — force-load routed through ownership registry
- `Database.java` — `plugin_forced_chunks`, `spawner_allowance`, `golem_spawner_daily` tables + accessors
- `DiscardedVaultService.java` — burned/exploded commodity recycling
- `NetWorthService.java` — Elite/Legendary Sigil valuation
- `ShardService.java` — `randomSpecialTool()`
- `SpawnerService.java` — golem XP, source-identity stamping, rollover logging
- `SMPCore.java` — `DuelMapService` wiring, `/ashfall duelmap`, `/fly`, `/flyspeed`
- `GameplayListener.java` — legacy sigil migration hook
- `MerchantService.java` — legacy sigil migration
- `resources/config.yml` — `duel-maps`, `duel-loot`, sigil values, golem money, discarded-vault, migrations
- `resources/shop.yml` — TNT + crafted utility items, DIAMOND intent marker
- `resources/bosses.yml` — sigil drop matrix, golem mob-rewards
- `resources/plugin.yml` — `fly` / `flyspeed` commands + `smpcore.fly` permission

Other modified files in the tree predate this batch.

---

## Services / responsibilities

**`DuelMapService`** (new) — the map engine.
- Map registry loaded from `duel-maps` in config.
- `template(map)` — loads/creates the private `duel_tpl_<key>` void world, loads the play area.
- `snapshotTemplate(map, target)` — unloads the template (save=true) as a write barrier, copies the folder, reopens the template. **This is the broken part.**
- `createInstance(map)` — snapshot → load as `duel_inst_<key>_<millis>_<n>` → apply world rules → purge mobs → fill chests.
- `destroyInstance(world, fallback)` — evacuate, unload, retried delete.
- `cleanupOrphans()` — boot-time sweep of all `duel_inst_*`.
- `sweepDetached()` — 60s timer; deletes instance folders that are neither loaded worlds nor live matches (works around Windows holding region-file handles after unload).
- Break-rule listener (`BlockPlace`/`BlockBreak`/`EntityExplode`/`BlockExplode`).
- `fillChests(world, map)` — per-instance loot rolling.

**`ArenaService`** (1600 lines, unchanged flow) — still owns matchmaking, kits, staking, rounds, and player state restore. It has **not** been wired to `DuelMapService`; duels still run in the single shared `ashfall_arena` world using numbered slots.

**`SMPCore`** — constructs `DuelMapService` before `ArenaService`, registers it as a listener, exposes `duelMaps()`, and hosts `/ashfall duelmap`.

---

## Implemented and verified on staging

- Six maps registered with correct break rules (`/ashfall duelmap list`).
- Template world creation; play area loaded on open so `/setblock` works.
- Concurrent instance creation from the same map (two simultaneous, independent).
- Instance drop → unload → folder deleted by sweeper (~35s observed).
- Boot orphan recovery (`removed 2 orphaned duel instance(s)` observed).
- `setspawn` writes config and hot-reloads the registry.
- Admin commands: `list | create | enter | exit | save | test | drop | orphans | setspawn | reload`.
- Duel kits: mace with Sharpness V + Density V + Breach IV simultaneously, Elytra, 64 flight-duration-1 rockets, Slow Falling splash, 16 steak per kit (Axe already had 16).

### Non-duel items completed this batch
- Production forced chunks removed (0 in all dimensions).
- Plugin-vs-admin force-load ownership registry with startup reconciliation (verified: plugin ticket released, admin rectangle untouched).
- Cinder Warlord nether-roof spawning, no cross-ceiling relocation.
- Special-tool drop: 0.5% total, split 0.0833% across six `/shardshop` tools.
- TNT 726.81 = (5×130 + 4×10.55)×1.05, plus Glass Bottle, Torch, Ladder, Paper, Book, Stick, Bucket, Rail.
- Sigil faction values: Elite 75,000 / Legendary 400,000.
- `/fly` and `/flyspeed` with `smpcore.fly`, tab completion, console targeting.
- Boat farm diagnosis: zero movement across active → deactivated → unloaded → reloaded. No config changed.

---

## Incomplete

1. **Template persistence — blocking everything below.**
2. Four region imports into templates.
3. Three-stage GUI (kit → map → final options).
4. `startMatch` wiring to instances.
5. Spawn/facing verification in a real match.
6. Live break-rule testing (code written and registered, never exercised).
7. Loot verification (blocked by #1).
8. Concurrency / restoration / template-update testing in real matches.
9. **Industrial Hopper parity — not started.**
10. Test coverage for all of the above. The existing 46-check selftest predates this work and proves none of it.

---

## Template-persistence failure — exact reproduction

```
/ashfall duelmap create cinder_crucible
execute in duel_tpl_cinder_crucible run setblock 9153 231 11208 stone
execute in duel_tpl_cinder_crucible run setblock 9153 232 11208 chest
execute in duel_tpl_cinder_crucible run setblock 9154 232 11208 chest
execute in duel_tpl_cinder_crucible run setblock 9153 233 11208 oak_sign{front_text:{messages:['{"text":"SNAPTEST"}','""','""','""']}}
execute in duel_tpl_cinder_crucible run setblock 9158 232 11208 trial_spawner
execute in duel_tpl_cinder_crucible run setblock 9160 232 11208 vault
```

Reads back correctly in the template immediately after placement.

```
/ashfall duelmap save cinder_crucible
/ashfall duelmap test cinder_crucible
execute in <instance> run forceload add 9153 11208
execute in <instance> run data get block 9153 232 11208 Items
```

Observed: `The target block is not a block entity` for chest, sign, trial_spawner and vault. Same for a standalone single chest at `9156 232 11208`.

Root cause evidence:
- `duel_tpl_cinder_crucible/region/` contains only origin-area files (`r.-1.-1.mca`, `r.-1.0.mca`). The map coordinates 9153/11208 fall in chunk (572, 700) → region **`r.17.21.mca`, which is never written**.
- Cloned instances therefore copy an empty template.
- After a clone cycle the template itself reports `That position is not loaded` at those coordinates.

Not the cause (already ruled out): chest-specific logic; `fillChests` load radius; the 60s sweeper; instance deletion.

Suspected: `WorldCreator` + void generator + `setKeepSpawnInMemory(false)` leaves far-from-origin chunks unsaved, so `unloadWorld(save=true)` has nothing to flush for them. Suggested direction: build templates by **copying region files directly** from the source world rather than relying on live chunk saves — this also delivers the four imports, which are region copies by nature.

---

## Six maps

Break rule `placed-only` = only blocks placed during that match may be broken; original terrain protected from players and explosions. `full` = terrain fully breakable inside bounds including explosions; barriers/bedrock/command/structure blocks always protected.

| Key | Name | Rule | P1 | P2 |
|---|---|---|---|---|
| `arena100` | Flat 100x100 | placed-only | 0.5, 65, -45.5 | 0.5, 65, 45.5 |
| `arena50` | Flat 50x50 | placed-only | 0.5, 65, -20.5 | 0.5, 65, 20.5 |
| `temple_of_tides` | Temple of Tides | full | 8930.5, 232, 11153.5 | 8930.5, 232, 11276.5 |
| `cinder_crucible` | Cinder Crucible | full | 9153.5, 232, 11260.5 | 9153.5, 232, 11157.5 |
| `deepstone_mines` | Deepstone Mines | full | 8924.5, 248, 11018.5 | 8924.5, 248, 11007.5 |
| `skyroot_village` | Skyroot Village | full | 9186.5, 232, 11022.5 | 9186.5, 232, 10980.5 |

Source bounds to import (staging overworld):

- Temple of Tides: `8852,226,11125` → `9026,320,11297`
- Cinder Crucible: `9063,227,11120` → `9238,320,11297`
- Deepstone Mines: `8845,210,10909` → `9022,320,11083`
- Skyroot Village: `9040,-64,10903` → `9243,320,11085`

Spawns are stored in **source coordinates**; templates keep the same coordinates so nothing shifts. Yaw is derived at runtime from the P1/P2 pair so duellists always face each other. Spectator spawn is optional per map; fallback is the midpoint of P1/P2 raised by `duel-spectator-fallback-height` (10).

Both Flat maps are independently selectable. Map choice is independent of kit — every kit can use every map. No kit is bound to a map.

---

## Three-stage GUI flow (to build)

1. Kit GUI — unchanged from today. Both players select and confirm.
2. Map GUI — both players confirm the chosen map using the existing confirmation model.
3. Final Options GUI — both players confirm. Must include **Visibility Effects**, default **ON**: while enabled, continuously apply Glowing and Night Vision to both duellists for the whole duel regardless of `/settings`, and remove those duel-applied effects during restoration. If OFF, neither effect is forced.

Only after stage 3 is the instance created and the match started. Cancellation, timeout and disconnect handling must be preserved at every stage.

---

## Industrial Hopper requirements (not started)

Must behave exactly like a vanilla hopper except: **27 slots** and **9 items per transfer operation**. Verify and fix as needed:

- Comparator output across 27 slots (suspected deviation: the 27 slots live in a plugin-side inventory while a comparator reads the block's own 5-slot vanilla inventory). Do **not** fix by writing filler items into the block inventory — other hoppers would pull them out.
- Redstone locking (currently reads the block's own vanilla `Hopper.isEnabled()` — correct approach).
- Facing/direction, pushing and pulling.
- Dropped-item collection.
- Hopper-minecart and container interaction.
- Breaking and drops.
- Chunk unload/reload and restart persistence.
- Duplication/loss safety.

Note: an RCON-only test rig is unreliable here — `setblock`-placed redstone did not propagate updates, and a vanilla hopper produced no comparator signal in that rig. Verify visually in-game or with a player-placed rig.

---

## Staging commands and test procedure

Staging: port 25566, RCON 25576. Production must not be restarted or deployed to.

```
/ashfall duelmap list
/ashfall duelmap create <map>
/ashfall duelmap enter <map>          # player only, creative, remembers return location
/ashfall duelmap exit
/ashfall duelmap save <map>
/ashfall duelmap test <map>           # console-capable
/ashfall duelmap drop <instance-world>
/ashfall duelmap orphans
/ashfall duelmap setspawn <map> <p1|p2|spectator>
/ashfall duelmap reload
```

Procedure once template persistence is fixed:
1. `create` a map, build/place block entities, `save`.
2. `test` twice — confirm both instances carry all block entities and roll independent loot.
3. Restart staging — confirm orphan sweep and that the template still holds its blocks.
4. Verify double chest = one roll; trial keys only on `cinder_crucible` / `deepstone_mines`, ominous rarer.
5. Break-rule checks on a Flat map (placed-only, incl. explosions) and an imported map (full).
6. Concurrent matches, cleanup, player-state restoration, and template edit → new instance updated while a running instance is unchanged.

Add automated coverage for map registry integrity, snapshot fidelity (block entities survive clone), loot constraints, and hopper parity. The existing 46-check selftest covers none of this.
