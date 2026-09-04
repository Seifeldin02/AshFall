# Promoting the 2026-09-04 staging batch

Written from the repository and `deploy/manifest.yml`. **Nothing here has been done.** Production was not
read, contacted or changed while preparing it; anything that can only be established with production in
front of you is marked *verify at promotion time*.

Staging build at the time of writing: see `DEPLOYED_COMMIT.txt` on the staging server.

---

## 1. What is actually different, and why

`python deploy/check_deploy.py` reports four items. All four are expected, and each is expected for a
different reason.

| item | state | why |
|---|---|---|
| `plugins/SMPCore/colosseum.yml` | absent on production | The Colosseum has never been promoted. A jar swap does not carry it: the shipped resource is only written when the file does not already exist. |
| `plugins/SMPCore/colosseum-templates/` | absent on production | The committed arena snapshots the encounter instances are cloned from. Without them the Colosseum has nothing to build an arena out of. |
| `console-guard.ps1` | differs from staging | Fixed on staging (2026-09-04). Production's copy cannot tell the two servers apart and cannot exit while its console is attached to it. |
| `storage-guard.ps1` | differs from staging | Fixed on staging (2026-09-04). Production's copy has no owner check at all and sleeps fifteen minutes between looks. |

Everything else — the jar, `config.yml`, `bosses.yml`, `relics.yml`, the Paper world configs, the launcher
scripts, the Geyser/Floodgate builds — matches, or is deliberately environment-specific (below).

The two supervisor differences are **not urgent for production**: one instance of each is running there and
one instance is harmless. They matter at production's *next restart*, which is when the accumulation starts.

## 2. What must not be overwritten

These differ between the two servers on purpose. A promotion that copies them from staging breaks
production.

| file | production keeps |
|---|---|
| `server.properties` | its own port (25565), RCON port (25575) and password, `max-players`, `motd`, `online-mode`, `level-name` |
| `plugins/SMPCore/config.yml` → `rcon` | production's RCON password |
| `start.bat` vs `start-staging.bat` | production's heap (`-Xmx6G`), its own title, its own paths |
| `plugins/AuthMe/` | production's account database. Never copied in either direction. |
| `plugins/SMPCore/smpcore.db` | production's players, balances, factions, claims, auctions and **claim stash**. Never copied. |
| `world/` and `world/dimensions/` | production's world. Only `colosseum-templates` is promoted, and it is a plugin resource, not a world. |
| `testing/harness/harness.ini` | must not exist on production at all — `guard.py` refuses production's ports before a socket opens, and the file is git-ignored so a clone cannot carry it. |

## 3. Database migrations in this batch

**None that need a step.** SMPCore migrates on boot: `smp_order_stash` already exists in production's schema
(it predates this batch), and nothing in this batch adds or alters a table. The new work is:

* `stashRows` / `stashRemove` / `stashShrink` / `stashRow` — queries against the existing table.
* claim receipts — stored in each player's own PersistentDataContainer, created on demand, no schema.
* the test lease — a row in the existing `state` key/value table, created on demand.

A rollback therefore needs no down-migration. See §6.

## 4. Promotion, in order

1. **Choose a window.** The Colosseum reload interrupts and refunds any encounter running at that moment,
   and the restart in step 7 disconnects everybody.
2. **Announce it.** One message and a five-second countdown, per the usual practice.
3. **Take a backup that you have actually tested restoring**: `plugins/SMPCore/smpcore.db`,
   `plugins/SMPCore/config.yml`, `world/`, and the current `plugins/SMPCore-1.7.0.jar`. Record the jar's
   SHA-256 — that is what a rollback needs, not "the previous one".
4. **Clear `plugins/update/`** before copying anything in. A stale jar there is applied on boot and
   overwrites what you just deployed, and the server still reports itself as enabled.
5. **Copy the artifacts:**
   * `smpcore-src/target/SMPCore-1.7.0.jar` → `plugins/`
   * `config-templates/colosseum.yml` → `plugins/SMPCore/colosseum.yml` *(new file; do not merge)*
   * `config-templates/colosseum-templates/` → `plugins/SMPCore/colosseum-templates/`
   * `config-templates/console-guard.ps1` → the server root
   * `config-templates/storage-guard.ps1` → the server root
6. **Do not copy** anything in §2.
7. **Restart** through the established launcher. Not `/reload`: the supervisor scripts are only re-read when
   they are relaunched, and the jar swap needs a clean boot.
8. **Verify the running build**, not merely that the plugin enabled: `/ashfall debug` and the jar's
   SHA-256 on disk against step 3.

## 5. Verify after promotion

Run these in this order. The destructive ones take the staging test lease on staging; on production they
should be run with **nobody else online**, and they will refuse if anybody is.

```
/ashfall lease status              nothing should be held, and it lists what is live
/ashfall selftest                  0 failed, including the claim-stash and inventory-contract checks
/ashfall colosseum verify          the promotion this batch is really about
/ashfall duelmap verify
/ashfall duelmap canary
/ashfall hopper verify
/ashfall voidworld verify
python deploy/check_deploy.py      should now be 47/47
```

Then, with a real account:

* `/orders` → the claim screen opens and says what is waiting.
* `/ashfall stash <player>` on somebody who is owed something, then have them collect it.
* One duel through all three setup stages, cancelling from stage one (which now has a Cancel button).
* One Colosseum encounter, confirmed and completed.

*Verify at promotion time:* production's Colosseum arena worlds are created on first use. The first
encounter after promotion pays the world-creation cost (~370 ms on staging hardware) and will be the
slowest one.

## 6. Rollback

Rollback is a jar swap and a restart. Nothing in this batch writes a schema or a config that an older jar
cannot read.

1. Clear `plugins/update/`.
2. Restore the jar from step 3 and confirm its SHA-256.
3. Restart through the launcher.
4. Leave `colosseum.yml` and `colosseum-templates/` in place — an older jar ignores them.
5. Restore the supervisor scripts only if the new ones are the thing being rolled back. They are
   independent of the jar and can be rolled back on their own.

**What an older jar does with data this batch created:**

* **Claim receipts** in a player's PDC are ignored by an older jar, which does not read that key. A claim
  whose receipt was written but whose row was not yet cleared would be delivered a second time on the old
  jar — the exact bug this batch fixes. Before rolling back, run `/ashfall stash <player> reconcile` for
  anyone with a receipt, or simply let every affected player log in once on the new jar first, which
  settles them automatically.
* **The test lease** row is inert to an older jar.
* **Stash rows** are unchanged in shape.

*Verify at promotion time:* whether production carries any claim receipts at rollback time
(`/ashfall stash <player> receipts`). On a healthy server there are none — a receipt only survives a crash
between the player save and the database update.

## 7. What is not covered by any of this

* **Bedrock.** Nothing in this batch has been through Geyser. The menus are plain chest inventories and the
  palette avoids hex-only colours, but a human on a Bedrock client still has to look at the duel setup
  screens and the Colosseum menu.
* **The bank front page.** It opens only from the Banker merchant, so it has never been captured from a
  client. Its code is covered by the suites; its appearance is not.
* **Production's own supervisor accumulation.** It has one of each running now. Whether the fixed scripts
  behave the same on production's process tree can only be seen at production's next restart.
