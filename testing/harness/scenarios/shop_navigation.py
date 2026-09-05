# -*- coding: utf-8 -*-
"""Every route between the five shops, in both directions, as an ordinary player.

The complaint this exists for: leaving the Spawner Shop did not look or behave like arriving at it. Four of
the five shops are sections of one screen and the fifth is its own class, and that seam was visible --
different button lore, a different sound, a different footer, and a different number of item slots above it.

So the assertion is not "the button works". It is that A -> B and B -> A are the same act: the ring advances
one step per click from every starting point, the footer is in the same place on every screen, and no route
between two shops moves a single coin or a single item. Navigation that charges money is the one bug in
this area that cannot be allowed to reach production, so it is measured rather than reasoned about.
"""
import time

TITLE = 'shop navigation: the whole matrix, in both directions, changing nothing'

#  The shared browse footer. Same slots on all five screens -- which is itself one of the assertions.
PREV, SEARCH, SECTION, SORT, NEXT = 45, 47, 49, 51, 53

#  Declaration order in MarketplaceService.Section IS the ring order.
RING = ['Normal Shop', 'Luxury Shop', 'Shard Shop', 'Auction House', 'Spawner Shop']

#  How each shop is opened directly, for the half of the matrix that does not go through the ring.
DOORS = {
    'Normal Shop': 'shop',
    'Luxury Shop': 'luxuryshop',
    'Shard Shop': 'shardshop',
    'Auction House': 'ah',
    'Spawner Shop': 'spawnershop',
}


def _titles(control, mark):
    return control.screens_since(mark)


def _which(note):
    """Which of the five shops a screen-open note is, or None for anything else."""
    for name in RING:
        if name in note:
            return name
    return None


def _open(control, command, settle=3.0):
    mark = control.chat_mark()
    control.say('cmd:' + command)
    time.sleep(settle)
    return [_which(line) for line in _titles(control, mark)]


def _click(control, slot, settle=2.5):
    mark = control.chat_mark()
    control.say('click:%d' % slot)
    time.sleep(settle)
    return [_which(line) for line in _titles(control, mark)]


def run(control, report):
    report.check('the test account is an ordinary player', control.assert_ordinary())
    control.fund(50000)
    start_items = control.give_marked_items()
    start_balance = control.rc(['ashfall balance %s' % control.name])[0][1]

    #  --- every shop opens directly, and opens the shop it was asked for -------------------------
    for name, command in DOORS.items():
        landed = _open(control, command)
        report.check('/%s opens %s' % (command, name), landed[-1:] == [name])
        control.say('close')
        time.sleep(1.2)

    #  --- the ring advances one step per click, from every starting point ------------------------
    #  Five clicks from anywhere must visit all five and come home. Doing it from each of the five is what
    #  catches an asymmetric hand-off: the old Spawner Shop always went to the Normal Shop regardless of
    #  where the ring said it should go next, which looks correct from exactly one starting point.
    for start in RING:
        opened = _open(control, DOORS[start])
        if opened[-1:] != [start]:
            report.check('ring walk could start at %s' % start, False)
            continue
        walk = []
        for _ in range(len(RING)):
            landed = _click(control, SECTION)
            walk.append(landed[-1] if landed else None)
        expected = RING[RING.index(start) + 1:] + RING[:RING.index(start) + 1]
        report.check('from %s the ring visits all five in order and comes back' % start, walk == expected)
        report.note('%-13s -> %s' % (start, ' -> '.join(str(w) for w in walk)))
        control.say('close')
        time.sleep(1.2)

    #  --- both directions across the seam ---------------------------------------------------------
    #  A -> B and B -> A for the pair the whole complaint was about, one click each way.
    _open(control, DOORS['Auction House'])
    into = _click(control, SECTION)
    report.check('Auction House -> Spawner Shop in one click', into[-1:] == ['Spawner Shop'])
    out = _click(control, SECTION)
    report.check('Spawner Shop -> Normal Shop in one click', out[-1:] == ['Normal Shop'])

    #  --- the footer is in the same place on every screen ------------------------------------------
    #  Clicking the paging slots must page, not open something else and not silently do nothing on a screen
    #  where that slot means something different.
    for name in RING:
        _open(control, DOORS[name])
        landed = _click(control, NEXT)
        report.check('Next on %s stays on %s' % (name, name), landed[-1:] in ([name], []))
        landed = _click(control, PREV)
        report.check('Previous on %s stays on %s' % (name, name), landed[-1:] in ([name], []))
        landed = _click(control, SORT)
        report.check('Sort on %s stays on %s' % (name, name), landed[-1:] in ([name], []))
        control.say('close')
        time.sleep(1.2)

    #  --- navigating after filtering, paging and selecting -----------------------------------------
    _open(control, 'shop')
    control.say('click:%d' % SORT, 'click:%d' % NEXT, 'click:%d' % SORT)
    time.sleep(3)
    landed = _click(control, SECTION)
    report.check('switching section after sorting and paging still lands on the next shop',
                 landed[-1:] == ['Luxury Shop'])
    #  Coming back round, the shop must not still be holding the page it was on.
    for _ in range(len(RING) - 1):
        _click(control, SECTION, settle=2.0)
    report.check('the player is back at the Normal Shop after a full lap', control.online())

    #  --- repeated and stale clicks -----------------------------------------------------------------
    mark = control.chat_mark()
    control.say(*['click:%d' % SECTION] * 5)
    time.sleep(6)
    hops = [_which(line) for line in _titles(control, mark)]
    report.check('five rapid switch clicks open five shops and nothing else',
                 len(hops) == 5 and all(h is not None for h in hops))
    report.note('rapid: ' + ' -> '.join(str(h) for h in hops))

    control.say('close')
    time.sleep(1.5)
    control.say('click:%d' % SECTION, 'click:%d' % SEARCH, 'click:%d' % NEXT, 'click:0')
    time.sleep(3)
    report.check('clicking a closed shop does not disconnect or open anything', control.online())

    #  --- and none of it cost anything --------------------------------------------------------------
    end_balance = control.rc(['ashfall balance %s' % control.name])[0][1]
    end_items = control.inventory()
    report.check('navigation moved no items', end_items == start_items)
    report.check('navigation moved no money', end_balance == start_balance)
    report.note('balance before: %s' % start_balance.strip()[:60])
    report.note('balance after : %s' % end_balance.strip()[:60])
