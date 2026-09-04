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

* Title is a short noun phrase in ember. No caps, no decoration.
* Controls sit in fixed places so muscle memory works across screens: **Back** bottom-left, **Close**
  bottom-centre, **Previous/Next** flanking it.
* Icons mean something — the item is the action, not decoration.
* Lore is at most four short lines: what it is, what it costs, what happens, why it is unavailable.
* Unavailable states say *why*, not just that. "Not enough Shards (need 12, you have 7)."
* **Moving an icon is a code change, not a cosmetic one.** Slot numbers appear in click handlers and in the
  verifiers; change all three together or a different slot will perform a purchase.

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
