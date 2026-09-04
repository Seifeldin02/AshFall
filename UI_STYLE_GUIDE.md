# Ashfall — interface style guide

Ashfall is ash and ember: dark, warm, restrained. The interface should feel like that without ever getting
in the way of reading a number. This document is the rule set; `CoreUtil` is where it is enforced.

The single most important rule: **a player should be able to tell what happened from the shape and colour of
a line before reading a word of it.**

---

## 1. Colour

Seven colours. Each means exactly one thing, everywhere.

| Role | Colour | Used for |
|---|---|---|
| **Ember** | `§6` / `#E0A24B` | Ashfall itself: headings, the sidebar title, money worth noticing |
| **Text** | `§f` | The value the eye is looking for — an amount, a name, a count |
| **Body** | `§7` | Ordinary sentences |
| **Mute** | `§8` | Labels, units, hints, separators, "what to do next" |
| **Good** | `§a` | It happened, and something moved because of it |
| **Warn** | `§e` | Worth noticing before it becomes a problem |
| **Bad** | `§c` | It did not happen |

**Never** use `§0` (black), `§1` (dark blue) or `§4` (dark red) for text. They are unreadable against
Minecraft's chat background and near-invisible on Bedrock, which dims further.

Do not introduce a colour to make something feel special. Aqua, pink and purple are not in this palette; if
something needs to stand out, it is ember, and if two things are both ember then neither is.

## 2. Message shapes

Every player-facing line goes through `CoreUtil`. Pick the shape by what the message *is*:

```java
CoreUtil.msg(p,   "Balance: $60,710,750");            // › neutral information
CoreUtil.ok(p,    "Sold 64 Iron Ingot for $1,280.");  // › it worked, money moved
CoreUtil.warn(p,  "This will cost $500,000.");        // › notice this before continuing
CoreUtil.error(p, "You cannot afford that.");         // › it did not happen
CoreUtil.error(p, "Home not found.", "/home list shows the ones you have.");
```

The marker is one character (`›`) in the colour of the outcome. It replaced the words "Ashfall › " that used
to open all 1,350 messages — repeated that often, a brand stops being identity and becomes margin noise. The
relic list printed it five times down the left-hand side.

For a block of related lines, use a heading and items rather than repeating anything:

```java
CoreUtil.heading(p, "Relic chronicle", "5 relics");
CoreUtil.item(p, "Crown of Ash  held by MacoCT  Eligible");
CoreUtil.field(p, "Balance", "$60,710,750");
CoreUtil.hint(p, "/guide walks through any of these in detail.");
```

## 3. Wording

* **Routine success is one line.** "Sold 64 Iron Ingot for $1,280." Not a paragraph, not an exclamation mark.
* **A failure says what was wrong and what to do.** "You cannot afford that." is half a message; the other
  half is "It costs $500,000 and you have $312,000."
* **Never "Something went wrong."** The detail belongs in the log *and* a human explanation belongs in chat.
* **No ALL CAPS**, ever, including headings. `ASHFALL` became `Ashfall`; `WORLD BOSS` became `World boss`.
  Caps are shouting, and everything shouting means nothing is.
* **No decorative separators**, no `=====` rules, no lore paragraphs on routine actions.
* Sentence case. One space after a full stop. Terminology stays fixed: it is a *faction*, not a team or a
  guild; *Shards*, not shards or SHARDS; *entry fee*, not cost or price.

## 4. Numbers and money

* `CoreUtil.money()` for anything a player can spend, always exact, always with the sign in front of the
  symbol: `-$8,000,000`, never `$-8,000,000`.
* `CoreUtil.compactMoney()` for summaries and the sidebar only — `$60.7m`. **Never** for a transaction: a
  fee, a rounding or a net figure abbreviated is a fee hidden.
* A transaction line separates what moved from what it cost:
  `Sold 64 Iron Ingot · $1,280 · 5% fee $64 · net $1,216`.
* Counts are plain (`5 relics`), durations are short (`2h 5m`, `8h`), times are relative (`resets in 7h 9m`).

## 5. Untrusted text

Anything a player chose — a nickname, a faction name or tag, a listing title, a home name — is **data, not
formatting**. Pass it through `CoreUtil.safe()` before it enters a message. A name carrying a section sign
would otherwise recolour or hide the rest of the line it appears in, including the part that says what
something costs.

The same applies to click actions: never build a `run_command` from player-supplied text.

## 6. Sounds

Two vocabularies already exist and should be used rather than replaced:

* `SettingsService.marketSound(player, action)` — `purchase`, `sale`, `failed`, `confirm`, `cancel`,
  `shard`. Config-overridable under `marketplace.sounds.*`, fixed volume 0.55, pitch carrying the meaning
  (failed drops to 0.65, cancel to 0.85, a sale lifts to 1.2). Every economic screen goes through it.
* `ColosseumService.sound(player, key)` — `start`, `victory`, `defeat`, `cancel`, `click`, from
  `colosseum.yml`.

Rules for both:

* **Respect the player's setting.** Both check `settings().sounds(player)` first. Anything new must too.
* **One sound per outcome.** If two handlers react to the same event, exactly one of them plays.
* **Never on a repeating HUD update.** Action bars, boss bars and sidebar refreshes are silent.
* **Every message must be fully understandable with sound off.** Sound is emphasis, never information.

## 7. Menus

Chest menus are the one GUI grammar Geyser renders faithfully, so they are the only one this server uses.
`CoreUtil.Menu` is to icons what `CoreUtil.msg`/`error` are to chat: the six roles live there, and a screen
picks one per icon rather than inventing its own.

| role | name colour | for |
|---|---|---|
| `heading` | ember | what this screen is, or the thing being confirmed. Never clickable. |
| `action` | white | something you can do right now. |
| `info` | grey | information. Clicking does nothing, and it does not pretend otherwise. |
| `state` | white / grey + green `On` / red `Off` | a switch, showing the state it is in **now**. |
| `blocked` | dark grey + a red reason | present so the option is discoverable, greyed so it is obviously not usable. |
| `danger` | red | spends money, deletes something, or cannot be undone. |

* **Italics off, always.** Minecraft renders custom item names and lore in italics unless you turn it off.
  Every icon on this server was italic for that reason; `Menu.of` disables it once, for everything.
* Lore is at most four short lines: what it is, what it costs, what happens, why it is unavailable.
* Unavailable states say *why*, not just that: "Unavailable — you already have a loan open."
* **Every list needs an empty state.** `Menu.nothing(what, how)` — what would be here, and what to do about
  it. A screen that is simply blank is the most common way a menu fails.
* **Page arrows say whether they will move.** `Menu.page(page, pages, forward)` greys out and explains
  itself on the first and last page instead of being two identical arrows either side of a screen that
  will not move.

### Slots are shared, not repeated

`CoreUtil.Menu` holds the numbers, and both the builder and the click handler read them from there.

| constant | slot | screen |
|---|---:|---|
| `SUBJECT` / `CANCEL` / `CONFIRM` | 13 / 11 / 15 | the 27-slot confirmation dialog |
| `BACK_SMALL` | 22 | the bottom of a 27-slot screen |
| `PREV` / `SEARCH` / `BACK` / `SORT` / `NEXT` | 45 / 47 / 49 / 51 / 53 | the 54-slot browse footer |
| `SELL_CANCEL` / `SELL_TOTAL` / `SELL_CONFIRM` | 47 / 49 / 51 | the 54-slot sell basket |

**Cancel is always on the left; confirm is always on the right.** This is the rule the constants exist to
enforce. The universal confirmation dialog had Cancel at 11 and Confirm at 15; the Bank's repayment dialog,
the Ender Chest upgrade and the Orders escrow dialog had them mirrored. Nobody moved an icon to cause that
— four screens were written at four different times and drifted apart — and the consequence was that a
player who learned where Cancel lives from the dialog they see most would press Confirm on a loan
repayment while meaning to back out.

**Moving an icon is a code change, not a cosmetic one.** If a slot number is not in `Menu`, it appears in
the builder, in the click handler and in the verifier; change all three together, or a different slot will
perform a purchase. `testing/harness/run.py menu-navigation` exercises the screens through a real client
and asserts where each click actually lands.

### Known divergence

`OrdersService` uses its own footer grammar (Back at 45, paging at 46/52) across all six of its screens. It
is internally consistent and its stash screen needs 49 for Collect, so it is left alone deliberately rather
than half-migrated. If it is ever unified, the whole file moves at once, with its click handler and the
harness scenario.

## 8. Action bars, boss bars and titles

* The action bar is for *transient state*: a countdown, a warning, a target's health. Never for something
  the player needs to be able to scroll back to.
* Only one system writes the action bar at a time. Colosseum encounters, event tracking and teleport
  warm-ups already coordinate; anything new must too.
* Titles are for moments that stop play — an encounter starting or ending. Not for confirmations.

---

## Applying this to existing code

Do not perform blind global replacements. The vocabulary is centralised precisely so that most of the
server improved the moment `CoreUtil` changed; everything after that is a considered edit to a specific
screen, and every one of those must keep its placeholders, permissions, click actions and amounts intact.

---

## Coverage checklist

What has been through the vocabulary, and what has not. "Audited" means it was read and judged; not every
audited screen needed changing, and rearranging one that already worked would have been churn.

| surface | state |
|---|---|
| chat, announcements, errors, command replies | migrated (`CoreUtil.msg`/`ok`/`warn`/`error`/`heading`/`item`/`field`/`hint`) |
| sidebar, help, `/stats`, `/relics`, `/homes`, leaderboards | migrated |
| confirmation dialog (all callers) | migrated, fixed sides |
| bank front page, borrow, repay, repay confirmation | migrated |
| settings: main, confirmations, TPA, nametags, Auto-TPA | migrated, regrouped |
| factions: main, members, relations list, relation detail | migrated |
| marketplace footer (shop, auction, luxury, shards) | migrated |
| sell baskets (ordinary and spawner) | migrated |
| spawner shop footer and empty state | migrated |
| orders: claim button, claim screen, escrow confirmation | migrated |
| ender chest upgrade dialog | migrated, fixed sides |
| duels: empty states, closed betting, opponent wager panel | migrated |
| Colosseum encounter menu | audited, unchanged — already the strongest screen on the server |
| duel setup wizard, wager box, kit layout editor | audited, unchanged — named slots, self-tested, thorough comments |
| marketplace item grid, spawner grid, shop item lore | audited, unchanged — prices already legible and correctly coloured |
| sounds (`marketSound`, Colosseum, `uiSound`) | audited, unchanged — two consistent vocabularies, both respect the player's setting |
| graves, bounties, discarded vault, task master, guide, net worth, progress | inherit the vocabulary through their builders; not individually redesigned |
| Bedrock rendering through Geyser | **not verified** — see below |

### Not covered, and why

* **Bedrock/Geyser.** Geyser is installed on staging, but the harness client speaks the Java protocol
  directly; nothing in any of this has been through Geyser. The palette avoids hex-only colours, the marker
  glyph is one the server already used, and every icon is a plain chest menu — but Bedrock dims colours
  further and lays out sidebars differently, so a human on a Bedrock client still has to look.
* **The bank front page, visually.** It opens only from the Banker merchant, so the capture pass could not
  reach it without an NPC. Its code is covered by the suites; its appearance is not captured.
* **Whether any of it looks good.** The harness asserts where a click lands and what it costs. That is the
  half that can take somebody's money. It is not a judgement of visual quality and does not pretend to be.
