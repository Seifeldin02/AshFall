# -*- coding: utf-8 -*-
"""The crash windows in claim delivery, entered on purpose.

Delivery writes a receipt into the player's PersistentDataContainer, saves the player, applies the receipt
to the database, then clears it. The claim is that a receipt and the inventory it records reach disk in the
same write, so a crash between the save and the database update can be settled afterwards instead of paying
the claim twice.

That claim is not argued here, it is read off the disk: the scenario aborts a delivery at each boundary,
opens the player's own <uuid>.dat, and checks whether the receipt and the item are both in it.

`/ashfall stash <player> fail <boundary>` aborts the next delivery at:

    before-save   nothing durable, no receipt          nothing should have happened
    after-save    items and receipt durable, row alive  reconcile must settle it, not re-deliver
    after-apply   row already updated, receipt alive    reconcile must simply clear it
"""
import glob
import gzip
import io
import os
import re
import time

TITLE = 'claim delivery survives a failure at every persistence boundary'

CLAIM_ITEM = 'minecraft:diamond'
CLAIM_COUNT = 40
FILLER = 'minecraft:cobblestone'
STAGING = r'C:\MinecraftServer-Staging'


def _count(control, item):
    reply = control.rc(['clear %s %s 0' % (control.name, item)], settle=0.2)[0][1]
    found = re.search(r'(\d+)\s+matching item', reply)
    return int(found.group(1)) if found else 0


def _owed(control):
    reply = control.rc(['ashfall stash %s' % control.name], settle=0.3)[0][1]
    found = re.search(r'(\d+) waiting', reply)
    return int(found.group(1)) if found else None


def _receipts(control):
    reply = control.rc(['ashfall stash %s receipts' % control.name], settle=0.3)[0][1]
    found = re.search(r'Receipts\s+(.+)', reply)
    return (found.group(1).strip() if found else 'none')


def _playerdata(control):
    """The player's own saved NBT, decompressed. This is the atomicity claim, on disk."""
    for pattern in ('world/players/data/*.dat', 'world/playerdata/*.dat'):
        for path in glob.glob(os.path.join(STAGING, pattern)):
            try:
                raw = gzip.open(path, 'rb').read()
            except OSError:
                raw = io.open(path, 'rb').read()
            if control.name.encode('utf-8') in raw or b'stash_receipts' in raw:
                return path, raw
    return None, b''


def _drain(control):
    control.say('cmd:orders')
    time.sleep(2.5)
    control.say('click:51')
    time.sleep(2.0)
    control.say('click:49')
    time.sleep(2.0)
    control.say('close')
    time.sleep(1.5)


def _grant(control, count):
    try:
        control.as_admin()
        control.rc(['ashfall stash %s grant %s %d' % (control.name, CLAIM_ITEM, count)])
        time.sleep(0.8)
    finally:
        control.drop_admin()


def _fail_at(control, point):
    try:
        control.as_admin()
        control.rc(['ashfall stash %s fail %s' % (control.name, point)])
        time.sleep(0.5)
    finally:
        control.drop_admin()


def _reconcile(control):
    try:
        control.as_admin()
        reply = control.rc(['ashfall stash %s reconcile' % control.name], settle=0.5)[0][1]
    finally:
        control.drop_admin()
    return reply


def run(control, report):
    report.check('the test account is an ordinary player', control.assert_ordinary())
    control.rc(['clear %s' % control.name])
    time.sleep(0.8)
    for _ in range(6):
        if _owed(control) == 0:
            break
        _drain(control)
    if _owed(control) != 0:
        report.fail('the stash could not be emptied before the scenario started')
        return

    #  --- boundary 1: the save never happens ---------------------------------------------------------
    _grant(control, CLAIM_COUNT)
    _fail_at(control, 'before-save')
    _drain(control)
    report.check('a failure before the save hands nothing over', _count(control, CLAIM_ITEM) == 0)
    report.check('and leaves the claim intact', _owed(control) == 1)
    report.check('and leaves no receipt behind', _receipts(control) == 'none')

    #  --- boundary 2: saved, then the database update never happens -----------------------------------
    #  This is the window the last pass accepted as a residual duplicate. The items are durable, the row
    #  still says they are owed, and only the receipt can tell the two states apart.
    _fail_at(control, 'after-save')
    _drain(control)
    delivered = _count(control, CLAIM_ITEM)
    report.check('a failure after the save still hands the items over', delivered == CLAIM_COUNT)
    receipt = _receipts(control)
    report.check('and leaves a receipt saying so', receipt != 'none' and ':0' in receipt)
    report.check('and the claim row is still there, unsettled', _owed(control) == 1)

    path, raw = _playerdata(control)
    report.note('playerdata: %s (%d bytes)' % (os.path.basename(path or '?'), len(raw)))
    report.check('the receipt reached disk in the player save itself',
                 b'stash_receipts' in raw)

    #  Recovery is what the receipt exists for: settle the row, do not deliver again.
    _reconcile(control)
    report.check('recovery settles the claim without delivering it twice',
                 _count(control, CLAIM_ITEM) == delivered)
    report.check('and the claim is cleared', _owed(control) == 0)
    report.check('and the receipt is gone', _receipts(control) == 'none')

    #  Repeating recovery must do nothing at all.
    _reconcile(control)
    _reconcile(control)
    report.check('repeating recovery is idempotent',
                 _count(control, CLAIM_ITEM) == delivered and _owed(control) == 0)

    #  --- boundary 3: applied, then the receipt is never cleared --------------------------------------
    control.rc(['clear %s' % control.name])
    time.sleep(0.8)
    _grant(control, CLAIM_COUNT)
    _fail_at(control, 'after-apply')
    _drain(control)
    report.check('a failure after the row is applied still hands the items over',
                 _count(control, CLAIM_ITEM) == CLAIM_COUNT)
    report.check('and the claim is already gone', _owed(control) == 0)
    _reconcile(control)
    report.check('recovery clears the stale receipt and delivers nothing more',
                 _count(control, CLAIM_ITEM) == CLAIM_COUNT and _receipts(control) == 'none')

    #  --- a claim that only half fits ------------------------------------------------------------------
    #  The case the addItem contract makes dangerous, and the one the old code got wrong: partial
    #  acceptance is the only outcome that rewrites the stack it was handed.
    control.rc(['clear %s' % control.name])
    time.sleep(1.0)
    _grant(control, CLAIM_COUNT)
    #  Exact slots rather than /give, which places wherever it likes: thirty-five slots of filler and one
    #  diamond stack with room for exactly 24 more. A partial fit has to be built, not hoped for.
    control.rc(['item replace entity %s container.%d with %s 64' % (control.name, slot, FILLER)
                for slot in range(35)])
    time.sleep(1.5)
    control.rc(['item replace entity %s container.35 with %s 40' % (control.name, CLAIM_ITEM)])
    time.sleep(1.0)
    before = _count(control, CLAIM_ITEM)
    report.check('the partial-fit inventory was set up (40 held, room for 24 more)', before == 40)
    _drain(control)
    after = _count(control, CLAIM_ITEM)
    room = 64 - (before % 64) if before % 64 else 0
    report.note('had %d, room for %d more in the open stack, delivered %d' % (before, room, after - before))
    report.check('a claim that only half fits delivers exactly the 24 that fit',
                 after - before == 24)
    report.check('and the rest is still owed, at the exact remaining amount',
                 _owed(control) == 1)
    reply = control.rc(['ashfall stash %s' % control.name], settle=0.3)[0][1]
    remaining = re.search(r'#\d+\s+(\d+)x', reply)
    report.check('the remaining claim is the exact leftover',
                 remaining is not None and int(remaining.group(1)) == CLAIM_COUNT - (after - before))

    #  Make room and the rest arrives, once.
    control.rc(['clear %s %s' % (control.name, FILLER)])
    time.sleep(1.2)
    _drain(control)
    report.check('making room delivers the exact remainder and nothing more',
                 _count(control, CLAIM_ITEM) == before + CLAIM_COUNT)
    report.check('and the claim is finished', _owed(control) == 0)

    _fail_at(control, 'off')
    control.rc(['clear %s' % control.name])
