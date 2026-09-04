# -*- coding: utf-8 -*-
"""Entering a void world by the route that is not the command, and getting back out.

WHAT THIS COVERS: the reported failure was an admin teleporting straight to somebody inside an event world
instead of using /voidworld enter. The state logic only half-activated, and on leaving the player was put
at 0,0 INSIDE the void world while already holding their restored real belongings -- one step from losing
everything into the void.

The cause was that the snapshot was written from PlayerChangedWorldEvent, which fires AFTER the crossing,
so "where you came from" was recorded as where you had just arrived.

Also covered here: dying inside must not drop the real inventory or leave a grave.
"""
import time

TITLE = 'void worlds: any route in, the same route out, belongings intact'

LABEL = '__harness_probe'


def run(control, report):
    control.as_admin()
    try:
        made = control.rc(['ashfall voidworld create %s' % LABEL])[0][1]
        report.check('a disposable void world was created', 'ready' in made.lower())
        control.note_world(LABEL)
        control.rc(['ashfall voidworld open %s' % LABEL])
    finally:
        control.drop_admin()

    world = 'ashfall_void_' + LABEL
    before_items = control.give_marked_items()
    home = control.where()
    report.note('starting in %s at %s with %d stacks' % (home[0], home[1], len(before_items)))

    #  --- 1. the ordinary route --------------------------------------------------------------------
    control.say('cmd:voidworld enter %s' % LABEL)
    time.sleep(4)
    inside = control.where()
    report.check('/voidworld enter puts the player inside', world in inside[0])

    #  --- 2. dying inside --------------------------------------------------------------------------
    #  Inside, the live inventory is the ISOLATED one -- the real belongings are held in the session
    #  snapshot, which is the whole design. So what matters after dying in here is that nothing of the real
    #  inventory was dropped, no grave was made from it, and the snapshot survives to be restored on exit.
    report.check('entering isolates the live inventory (real belongings are in the snapshot)',
                 control.inventory() != before_items)
    control.rc(['effect clear %s' % control.name, 'kill %s' % control.name])
    time.sleep(3)
    control.say('respawn')
    time.sleep(3)
    report.check('a death inside leaves the player inside rather than ejecting them', world in control.where()[0])
    report.check('and leaves no grave to loot',
                 'No marker-less graves' in control.rc(['ashfall grave repair'])[0][1])

    #  --- 3. leaving -------------------------------------------------------------------------------
    control.say('cmd:voidworld exit')
    time.sleep(5)
    out = control.where()
    report.check('exit returns to the real world', world not in out[0])
    report.check('exit returns to where the player actually came from, not 0,0', out[1] == home[1])
    report.check('belongings are intact after the round trip', control.inventory() == before_items)

    #  --- 4. the reported route: teleported straight in ---------------------------------------------
    before = control.where()
    control.rc(['execute in minecraft:%s run tp %s 0.5 70 0.5' % (world, control.name)])
    time.sleep(4)
    report.check('a raw teleport in still enters the lifecycle', world in control.where()[0])
    control.say('cmd:voidworld exit')
    time.sleep(5)
    landed = control.where()
    report.check('exit after a raw teleport lands in the real world', world not in landed[0])
    report.check('and never at 0,0 inside it',
                 landed[1] is None or abs(landed[1][0]) > 1 or abs(landed[1][2]) > 1)
    report.check('and returns to the recorded origin', landed[1] == before[1])
    report.check('with belongings intact', control.inventory() == before_items)
