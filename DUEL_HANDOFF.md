# Duel Rebuild — State

**Staging only. Production untouched.** The full narrative, including the promotion checklist, is in the
`2026-08-20` section of `DEVELOPMENT_LOG.md`; this file is the short operational version.

## Status

| | |
|---|---|
| Template persistence | **Fixed and proven.** `/ashfall duelmap canary` passes, including across a restart. |
| Six maps | **Built, imported, committed, verified.** All six selectable; every kit can use every map. |
| Break rules | **Both models implemented and asserted**, pickaxe and explosion, through one shared predicate. |
| Three-stage setup | **Built.** Kit → Map → Final Options, both-sides confirm at each. Needs two real players to click through. |
| `startMatch` wiring | **Done.** Every match runs in its own cloned world; instances destroyed on every exit path. |
| Loot | **Verified.** Double chests roll once; trial keys restricted to two maps; rates measured over 8,000 rolls. |
| Industrial Hopper | **Parity verified**, three real deviations fixed (comparator, furnace faces, minecarts). |
| Test coverage | Three new console suites, plus two new lines in `/ashfall selftest`. |

## The defect, in one paragraph

Paper puts a Bukkit-created world in `<level-name>/dimensions/<namespace>/<world>`, **not** in the world
container. `createInstance` wrote the clone to the container and then asked Paper to open it, so Paper found
nothing at the real path and generated a fresh empty void world. The template was never broken — its region
files were on disk the whole time, one directory away from where the clone looked. Two adjacent facts also
bite: a non-main world folder has **no `level.dat`** (level data lives in the parent world), and a world's
identity now lives in **`data/paper/metadata.dat`**, not `uid.dat` — copying that makes Paper reject the clone
as a duplicate world.

## Commands

```
/ashfall duelmap list | snapshots            # what exists, and what is committed
/ashfall duelmap create <map>                # open the template world
/ashfall duelmap enter <map> | exit          # build in it (player only, creative)
/ashfall duelmap build <arena50|arena100>    # lay the flat arena from code, then commit
/ashfall duelmap import <map>                # copy the exact cuboid from the source world, then commit
/ashfall duelmap save <map>                  # commit the template atomically
/ashfall duelmap setspawn <map> <p1|p2|spectator>
/ashfall duelmap test <map> | drop <world> | orphans | reload
/ashfall duelmap canary                      # template persistence proof (run twice, across a restart)
/ashfall duelmap verify                      # full pipeline: clone, spawns, rules, loot, concurrency, cleanup
/ashfall duelmap dryrun <map>                # the exact async path a real match takes
/ashfall duelmap loot <map>                  # chest census and expected trial-key yield
/ashfall hopper verify                       # Industrial Hopper vanilla-parity rig
```

Staging: port 25566, RCON 25576.

## Two owner decisions outstanding

1. **Cinder Crucible P2 spawns over lava** (`9153, 232, 11157`; lava at y=231 across at least 3×3). P1 is
   fine. Not auto-relocated on purpose — moving somebody's arena spawn is their call. Fix: stand where you
   want it, then `/ashfall duelmap setspawn cinder_crucible p2`.
2. **Deepstone Mines has 18 vaults but no chests inside its bounds**, so no trial key can be found on it even
   though it is one of the two maps allowed to roll them. Its bounds start at y=210; the chests are presumably
   below that. Widen the bounds and re-import, or add chests to the template.

## Manual acceptance still outstanding

Needs two players on staging. Every mechanism behind these is asserted automatically; the clicking is not.

1. Challenge → accept → kit + both confirm → map + both confirm → Final Options + both confirm → fight.
2. Back from Map and from Final Options; change something after one side has confirmed and check that both
   confirmations clear.
3. Cancel from each stage; ESC from each stage; one player disconnecting at each stage.
4. In-match: place and break your own block on a flat arena, then try the floor (must refuse), then TNT on the
   floor (must refuse). On an imported map, break terrain and TNT it (must work), then a barrier (must refuse).
5. Visibility Effects ON through a whole best-of-3, including after a golden apple and a milk bucket; then a
   duel with it OFF.
6. Two matches on the same map at once; neither sees the other's damage or loot; both worlds gone afterwards.
7. Full inventory/XP/health/location restoration after a win, a forfeit and a disconnect.
