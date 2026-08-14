# Ashfall Concord — Development Log

Newest first. Updating this is part of finishing a change, not an afterthought — same as updating
`deploy/manifest.yml` when a non-Git-tracked config or jar starts mattering to a deploy.

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

---

## Standing lessons

1. **Restart staging by stopping it first.** Relaunching without stopping leaves the old JVM holding the
   port; the "restart" silently does nothing and every later test runs against the previous jar. This cost a
   whole round of results once.
2. **Console measurement lies in four specific ways**: `/data get` truncates long NBT over RCON; reading
   three containers with three commands is not an atomic snapshot; spilled items fall off one-block platforms
   out of range; and entity flags like `Invisible` are synced state, not saved NBT.
3. **Trust the plugin's own log over reconstructed console arithmetic.**
