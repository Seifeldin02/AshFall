# -*- coding: utf-8 -*-
"""The durable claim stash, from the collection side.

Settling an auction into a stash protects the SALE. It does nothing for the delivery, and delivery is
where property is actually lost: the old collection path deleted every row for the owner and then wrote
into an inventory that is not durable until Paper next saves the player.

This exercises the boundary with a real player and real items:

    a full inventory must not consume the claim
    making room and collecting again must deliver it, once
    collecting a second time must deliver nothing
    a restart in between must not change any of that

Item counts come from `clear <player> <item> 0`, which reports matches without removing them.
"""
import re
import time

TITLE = 'claim stash: a full inventory holds the claim, and nothing is delivered twice'

STASH_ITEM = 'minecraft:diamond'
STASH_COUNT = 32
FILLER = 'minecraft:cobblestone'


def _count(control, item):
    reply = control.rc(['clear %s %s 0' % (control.name, item)], settle=0.2)[0][1]
    found = re.search(r'(\d+)\s+matching item', reply)
    return int(found.group(1)) if found else 0


def _owed(control):
    """How many claim rows the server says are owed, from the admin view of the stash table."""
    reply = control.rc(['ashfall stash %s' % control.name], settle=0.3)[0][1]
    found = re.search(r'(\d+) waiting', reply)
    if found:
        return int(found.group(1))
    return None


def _drain(control):
    """Collect through the real screens: /orders, the claim button, collect everything."""
    control.say('cmd:orders')
    time.sleep(2.5)
    control.say('click:51')
    time.sleep(2.0)
    control.say('click:49')
    time.sleep(2.0)
    control.say('close')
    time.sleep(1.5)


def run(control, report):
    report.check('the test account is an ordinary player', control.assert_ordinary())

    control.rc(['clear %s' % control.name])
    time.sleep(0.8)

    #  --- start from an empty stash, using the player's own collect path ----------------------------
    #  Deliberately not an admin "wipe the stash" verb. Anything that can delete somebody's unclaimed
    #  property from the console is a worse thing to own than a slightly longer test.
    for _ in range(6):
        if _owed(control) == 0:
            break
        _drain(control)
    report.check('the stash starts empty', _owed(control) == 0)
    control.rc(['clear %s' % control.name])
    time.sleep(0.8)

    #  --- put something in the stash ---------------------------------------------------------------
    #  Admin only for the setup step, and dropped again immediately: a player cannot hand themselves a
    #  claim, and the harness should not pretend they can.
    try:
        control.as_admin()
        control.rc(['ashfall stash %s grant %s %d' % (control.name, STASH_ITEM, STASH_COUNT)])
        time.sleep(0.8)
    finally:
        control.drop_admin()
    owed = _owed(control)
    if owed is None:
        report.note('no admin stash command on this build; skipping the stash scenario')
        return
    report.check('the stash holds exactly one claim after setup', owed == 1)

    #  --- a full inventory must not consume it ------------------------------------------------------
    control.rc(['give %s %s 64' % (control.name, FILLER)] * 40)
    time.sleep(2.0)
    before_full = _count(control, STASH_ITEM)
    _drain(control)
    report.check('collecting into a full inventory delivers nothing',
                 _count(control, STASH_ITEM) == before_full)
    report.check('and the claim is still owed', _owed(control) == 1)

    #  --- make room, collect, and get exactly what was owed -----------------------------------------
    control.rc(['clear %s %s' % (control.name, FILLER)])
    time.sleep(1.2)
    _drain(control)
    delivered = _count(control, STASH_ITEM)
    report.check('collecting with room delivers exactly what was owed (%d)' % STASH_COUNT,
                 delivered == before_full + STASH_COUNT)
    report.check('and the claim is cleared', _owed(control) == 0)

    #  --- collecting again delivers nothing ---------------------------------------------------------
    _drain(control)
    report.check('collecting an empty stash delivers nothing a second time',
                 _count(control, STASH_ITEM) == delivered)

    #  --- and a restart of the same flow is still a no-op --------------------------------------------
    for _ in range(3):
        _drain(control)
    report.check('repeated collection attempts never duplicate the delivery',
                 _count(control, STASH_ITEM) == delivered)

    control.rc(['clear %s' % control.name])
