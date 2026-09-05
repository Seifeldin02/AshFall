# Ashfall real-game test harness

A headless Minecraft client that joins a staging server as an ordinary player and exercises the paths that
synthetic tests cannot reach: vanilla AI and movement, damage events, GUI clicks, deaths, disconnects,
world transitions and inventory restoration.

It exists because the synthetic tests were wrong in a way only a real client could show. The Chainbound
Behemoth's charge never moved a single block — it disables the boss's AI so nothing can steer it, and an
AI-less mob ignores applied velocity — yet the suite passed, because it drove 400 charge steps inside one
server tick, where nothing can move by construction. Separately, `/colosseum leave` could not be confirmed
by any ordinary player, and nobody noticed because every test ran as an administrator, and administrators
skip the guard that broke it.

## Safety

**This drives a real server.** It spends money, starts paid encounters, kills the account it controls and
deletes worlds.

* The endpoint is never defaulted or guessed. It comes from `harness.ini`, which you create.
* `guard.py` refuses ports **25565** and **25575** (Ashfall production) and the production hostnames,
  before any socket is opened. That refusal is not configurable — a config file that can switch the safety
  off is not a safety.
* The test account is an **ordinary player** by default. Scenarios that need operator grant it around the
  step that needs it and revoke it in a `finally`.
* Cleanup removes only what the run created. Staging has real records from real people in it.
* `harness.ini` is git-ignored. Never commit a filled-in copy, an auth database, or player data.

## Setup

Python 3.9+ and nothing else — no third-party packages, no build step. Everything (protocol framing, NBT,
RCON) is in `ashfall_harness/`.

```
cd testing/harness
cp harness.ini.example harness.ini
# edit harness.ini: your staging host/ports, staging RCON password, and a dedicated test account
```

The test account needs to exist on the server. On a staging server running AuthMe:

```
authme register AshfallProbe <throwaway-password>
```

The harness force-logs it in each run via console, so the password is only needed for registration.

Requirements on the server side:

* `online-mode=false` (the client does not authenticate with Mojang)
* RCON enabled, and the port reachable from wherever you run this
* the SMPCore build you want to test, deployed

## Running

```
python run.py --list          # what is available
python run.py                 # everything
python run.py charge          # one scenario
python run.py stash menu-navigation   # several
python test_guard.py                  # endpoint refusals, no server needed
python test_lease.py                  # test-lease contention and stale recovery, console only
```

Each scenario starts its own client process, so a scenario can disconnect the player on purpose and the
next one still gets a clean session. Exit status is non-zero if anything failed.

## Scenarios

| name | what it protects |
|---|---|
| `charge` | The Behemoth's charge moves, covers its lane, runs straight, and cannot steer. Measured from entity-position packets at tick resolution — RCON polling is far too coarse to see a two-second charge. |
| `colosseum-leave` | An **ordinary player** can leave a paid encounter promptly. Asserts the account is not an operator first, because that is what hid the bug. |
| `gui-confirm` | On a confirmation screen: confirm pays, cancel does not, closing does not, and a second click on a spent confirmation does nothing. |
| `voidworld-entry` | Entering by a raw `/tp` — not the command — still enters the lifecycle; exit returns to the recorded origin, never 0,0 and never inside; dying inside costs no items and leaves no grave. |
| `inventory` | Full slot/id/count conservation across death mid-encounter, an attempted second encounter, and a disconnect. |
| `menu-navigation` | Where a click actually lands: the settings preference band never opens a screen or moves the player, each door opens the screen it is drawn as, Back returns, the marketplace section ring advances one step per click, the first page has nowhere to go back to, a closed menu can be clicked without disconnecting anybody, and repeated clicks on one control do one thing repeatedly. |
| `stash-crash` | Claim delivery aborted at each persistence boundary on purpose, then recovered. Opens the player's own `<uuid>.dat` and checks the receipt is in the same file as the items it records, which is the whole basis of the design. Includes the partial fit -- built by exact slot, not hoped for. |
| `shop-navigation` | Every route between the five shops, in both directions. The ring advances one step per click from all five starting points, each shop opens directly to itself, the footer sits in the same slots on every screen, and the balance and a marked inventory are read either side of the whole matrix -- navigation that charges money is the one bug here that must never ship. |
| `sidebar` | The panel as the client receives it, from real scoreboard packets: no row over the pixel budget, no blank row at either end, no two together, no stale row left behind when an event ends, and an idle panel sending nothing. Carries its own copy of the font width table so a mistake in `CoreUtil`'s cannot agree with itself. |
| `stash` | The durable claim stash from the collection side: a full inventory holds the claim rather than consuming it, making room delivers exactly what was owed, and collecting again delivers nothing. Drains through the player's own screens first, so it never needs a console command that can delete somebody's unclaimed property. |

## What it is not

It is **not Bedrock coverage.** The client speaks the Java protocol directly. Nothing here has been through
Geyser, and no claim about Bedrock rendering or Bedrock form behaviour can be based on it.

It also does not judge visual quality. `chat:dump` captures what the server actually sent, decoded into the
line the player sees, which is the right input for a human to review — but a passing click test proves
behaviour, not that a screen looks good.

## Client instruction reference

Scenarios drive the client by queueing lines (`control.say(...)`):

| instruction | effect |
|---|---|
| `cmd:<command>` | run it as the player, through the real command path |
| `click:<slot>` | click that slot in the open window |
| `close` | close the open window |
| `pos:<x> <y> <z>` | claim a position |
| `respawn` | respawn after death |
| `trace:start` / `trace:dump` | record entity positions, then write `trace_<name>.csv` |
| `chat:start` / `chat:dump` | record chat as rendered, then write `chat_<name>.txt` |
| `interact:near:<x>,<y>,<z>` | right-click the tracked entity nearest that point (see the caveat below) |
| `sidebar:dump:<name>` / `sidebar:count:<name>` / `sidebar:reset` | write the sidebar rows, or the number of row packets since the reset, to `ui_<name>.txt` |
| `quit` | disconnect cleanly |

### Capturing what a screen actually looked like

`screen:dump:<name>` writes the printable runs out of the last `container_set_content` packet to
`ui_<name>.txt`. Icon names and their colours travel as text components, so this is what the client
was handed rather than what the source says was meant -- which is the difference between inspecting a
rendered result and judging raw colour codes. `Control.screen_dump(name)` reads it back.

It is a capture, not an assertion, and it says nothing about whether the result looks good.

### Reading the sidebar

`sidebar:dump:<name>` writes the rows the client is currently holding, top first, to `ui_<name>.txt`.
They are collected from real `set_score` / `reset_score` packets, identified by the **objective name**
rather than by a packet id -- one protocol bump and a hard-coded id silently stops seeing anything, and a
test that sees nothing passes.

Two forms of the reset packet exist and both matter: Bukkit's `resetScores(entry)` clears the entry from
every objective at once and sends it with **no** objective name. Reading only the form that carries one
makes every removal invisible, which looks exactly like a panel holding stale rows.

`sidebar:count:<name>` writes how many row packets have arrived since `sidebar:reset`. That is the number
that showed the pre-2026-09-05 sidebar sending 150 packets for a printed value that never changed.

### Right-clicking an entity, and where it stops working

`interact:near:x,y,z` sends the two packets a real client sends for a right-click -- `INTERACT_AT` with the
hit point, then `INTERACT` -- to the tracked entity nearest that position. Nearest-to-a-point rather than
"the one that just appeared", because mobs spawn while a test runs.

**It does not currently reach the server.** The packet is sent and acknowledged, no event fires, and it
behaves identically with operator, so it is not the anticheat. This client speaks protocol 767 (1.21.1) to
a Minecraft 26.2 server through ViaVersion, and entity interaction is the one thing on that path that does
not come out the other side. This is why the Bank front page -- which opens from the Central Banker and
from nothing else -- has never been captured from a client. `menu-navigation` asserts the door instead: that
a Central Banker is standing within reach of spawn and still carries the tag that makes it one.

The other two routes were tried and are dead ends worth not repeating: `/ashfall merchant spawn` needs a
Player so the console cannot run it, and SMPCore refuses admin commands from anyone but the configured
admin account -- opping the test account is not enough. Adding a command that opens the screen was refused
on purpose: a public gameplay command that exists only so a test can reach a screen is a worse thing to
ship than an uncaptured screen.

### The staging test lease

`run.py` takes `/ashfall lease acquire harness` for the whole run and refuses to start without it.
The four destructive in-server suites do the same in reverse: they refuse while somebody else holds
it, and say who. `selftest` and `duelmap verify` are never gated because they only read.

An RCON caller is named **Rcon**, not CONSOLE. Take the lease with no holder argument and let the
server name you -- `/ashfall lease status` prints who you are -- or you will lock yourself out of
your own suites.
