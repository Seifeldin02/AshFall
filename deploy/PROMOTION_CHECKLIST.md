# Promoting to production

Written from the repository and `deploy/manifest.yml`. `deploy/promote-to-production.ps1` now performs
steps 3 to 6 itself; this file is what to check around it, and what to do when it goes wrong.

---

## 1. What a jar swap does not carry

`python deploy/check_deploy.py` names the differences. Four of them stood open before the 2026-09-05
promotion, and each for a different reason:

| item | state | why |
|---|---|---|
| `plugins/SMPCore/colosseum.yml` | absent on production | The Colosseum had never been promoted. A jar swap does not carry it: the shipped resource is only written when the file does not already exist. |
| `plugins/SMPCore/colosseum-templates/` | absent on production | The committed arena snapshots an encounter clones its instance from. Without them the Colosseum has nothing to build an arena out of. |
| `console-guard.ps1` | differed | Fixed on staging 2026-09-04. Production's copy cannot tell the two servers apart and cannot exit while its console is attached to it. |
| `storage-guard.ps1` | differed | Fixed on staging 2026-09-04. Production's copy has no owner check and sleeps fifteen minutes between looks. |

The supervisor scripts are not urgent in themselves — one instance of each is running and one is harmless.
They matter at the *next* restart, which is when the accumulation starts.

**These are promoted from staging, not from the repo.** `manifest.yml` calls staging the reference for a
`sync:` path and `check_deploy.py` compares production against it, so taking them from anywhere else can
leave the checker red after a promotion that looked like it worked.

## 2. Two things about production that have bitten before

* **The jar is called `SMPCore.jar`.** No version in the name. Until 2026-09-05 the promotion script
  filtered for `SMPCore-*.jar`, which matched nothing on production: it backed nothing up, removed nothing,
  and copied `SMPCore-1.7.0.jar` in *alongside* the jar already there. Two SMPCore jars in one folder is a
  coin toss over which one Paper loads, and the losing side is invisible — the plugin still enables. The
  script now keeps whatever filename is already there and refuses outright if it finds more than one.
* **`plugins/update/` is applied on boot.** A stale jar there overwrites what was just deployed and the
  server reports itself as enabled either way. The script clears it (into the backup) before copying.

## 3. What must never be overwritten

These differ between the two servers on purpose. A promotion that copies them breaks production.

| file | production keeps |
|---|---|
| `server.properties` | its own port (25565), RCON port (25575) and password, `max-players`, `motd`, `online-mode`, `level-name` |
| `plugins/SMPCore/config.yml` | its RCON password, and the absence of the two staging-only `trusted-admin` keys |
| `start.bat` vs `start-staging.bat` | production's heap (`-Xmx6G`), its own title and paths |
| `plugins/AuthMe/` | production's account database. Never copied in either direction. |
| `plugins/SMPCore/smpcore.db` | production's players, balances, factions, claims, auctions and claim stash |
| `world/` and `world/dimensions/` | production's world |
| `testing/harness/harness.ini` | must not exist on production at all — `guard.py` refuses production's ports before a socket opens, and the file is git-ignored so a clone cannot carry it |
| `plugins/ReplayCore-1.5.0.jar` | **must stay absent.** It was physically removed on 2026-09-02 and only the config directory remains. `build.ps1` compiles against a cached copy in the repo rather than re-staging it from production. |

## 4. Database migrations

**None that need a step.** SMPCore migrates on boot. Nothing in the 2026-09-04 or 2026-09-05 batches adds
or alters a table: claim receipts live in each player's own PersistentDataContainer, the test lease is a row
in the existing `state` table, and the sidebar's two new reads are queries against `players` and
`shard_accounts` as they already are.

## 5. Promotion, in order

1. **Merge into `main` first.** The script refuses to promote a commit `main` does not already contain.
2. **Choose a window.** The restart disconnects everybody, and a Colosseum reload interrupts and refunds
   any encounter running at that moment.
3. **Run it:** `.\deploy\promote-to-production.ps1 -Commit <sha>`. It backs up the current jar (with its
   SHA-256), the whole `plugins/SMPCore` config folder, and every artifact it is about to replace, into
   `backups\<timestamp>-pre-promote-<sha>\`; clears `plugins/update/`; builds that exact commit; and copies
   the jar and the four manifest artifacts.
4. **Announce and restart.** If only Asserto and/or MacoCT are online, restart silently; otherwise announce
   30 seconds ahead with reminders at 10, 5 and 1. Save and confirm the old JVM has exited before launching
   the new one. Not `/reload`: the supervisor scripts are only re-read when they are relaunched.
5. **Verify the running build**, not merely that the plugin enabled — `/ashfall debug`, and the jar's
   SHA-256 on disk against `DEPLOYED_COMMIT.txt`.

## 6. Verify after promotion

```
/ashfall lease status              nothing held, and it lists what is live
/ashfall selftest                  0 failed
/ashfall colosseum verify          run with nobody else online; it refuses if anybody is
/ashfall duelmap verify
/ashfall duelmap canary
/ashfall hopper verify
/ashfall voidworld verify
python deploy/check_deploy.py      47/47
```

Then, without charging or rewarding anybody: `/shop` opens and its footer cycles all five shops; the
sidebar draws and updates; Geyser's port answers; Simple Voice Chat is listening; exactly one console
guard, freeze watchdog and storage guard are running; the log advances with no new SMPCore errors.

*Verify at promotion time:* production's Colosseum arena worlds are created on first use, so the first
encounter after promotion pays the world-creation cost (~370 ms on staging hardware) and will be the
slowest one.

## 7. Rollback

Rollback is a jar swap and a restart. Nothing in these batches writes a schema or a config an older jar
cannot read.

1. Clear `plugins/update/`.
2. Restore the jar from the backup directory and confirm its SHA-256 against `DEPLOYED_COMMIT.txt`.
3. Restart through the launcher.
4. Leave `colosseum.yml` and `colosseum-templates/` in place — an older jar ignores them.
5. Restore the supervisor scripts only if they are the thing being rolled back. They are independent of the
   jar and can be rolled back on their own.

**What an older jar does with data these batches created:**

* **Claim receipts** in a player's PDC are ignored by an older jar, which does not read that key. A claim
  whose receipt was written but whose row was not yet cleared would be delivered a second time on the old
  jar — the exact bug the 2026-09-04 batch fixed. Before rolling back, run
  `/ashfall stash <player> reconcile` for anyone carrying one, or let every affected player log in once on
  the new jar first, which settles them automatically. On a healthy server there are none: a receipt only
  survives a crash between the player save and the database update.
* **The test lease** row is inert to an older jar.
* **Stash rows** are unchanged in shape.

Stability beats the one-restart target. If a boot or data-integrity failure needs a rollback, do it and
report the extra restart rather than leaving production broken to preserve the count.

## 8. What none of this covers

* **Bedrock.** Nothing in these batches has been through Geyser. The menus are plain chest inventories and
  the palette avoids hex-only colours, but a human on a Bedrock client still has to look at the sidebar,
  the shop footers and the duel setup screens.
* **The Bank front page.** It opens from the Central Banker and from nothing else, and the harness client's
  entity interaction does not survive the ViaVersion path between its protocol and the server's. Its code
  is covered by the suites; its appearance is not. See `testing/harness/README.md`.
