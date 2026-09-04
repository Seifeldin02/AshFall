# -*- coding: utf-8 -*-
"""Belongings across every way a temporary activity can end.

Real items go into a snapshot when a player enters a Colosseum instance or a void world, and come back out
when they leave. The interesting cases are not the happy path: they are dying mid-activity, disconnecting
before restoration, and trying to enter a second activity while already inside one.

Compares full slot/id/count data, not a stack count, because "you got something back" is not the same
claim as "you got exactly what you had".
"""
import re
import time

TITLE = 'inventory is conserved across death, disconnect and overlapping entry'


def _colosseum(control):
    return control.rc(['ashfall colosseum instances'])[0][1]


def run(control, report):
    control.fund(3_000_000)
    before = control.give_marked_items()
    report.note('carrying %d marked stacks' % len(before))

    #  --- death inside a paid encounter ------------------------------------------------------------
    control.say('cmd:colosseum warden_of_cinders')
    time.sleep(3)
    control.say('click:15')
    time.sleep(8)
    report.check('the encounter started', 'Active encounters: 1' in _colosseum(control))
    report.check('the player is inside the arena', 'colo_inst' in control.where()[0])

    control.rc(['effect clear %s' % control.name, 'kill %s' % control.name])
    time.sleep(4)
    control.say('respawn')
    time.sleep(5)
    report.check('dying in the arena resolves the encounter', 'Active encounters: 0' in _colosseum(control))
    report.check('and returns the player to the real world', 'colo_inst' not in control.where()[0])
    report.check('with exactly the items they went in with', control.inventory() == before)
    report.check('and no grave holding a second copy',
                 'No marker-less graves' in control.rc(['ashfall grave repair'])[0][1])

    #  --- entering a second activity while already in one -------------------------------------------
    control.say('cmd:colosseum warden_of_cinders')
    time.sleep(3)
    control.say('click:15')
    time.sleep(8)
    mark = control.chat_mark()
    control.say('cmd:colosseum ashglass_alchemist')
    time.sleep(3)
    control.say('click:15')
    time.sleep(4)
    report.check('a second encounter cannot be started while one is running',
                 'Active encounters: 1' in _colosseum(control))
    said = ' '.join(control.chat_since(mark)).lower()
    report.check('and the refusal says why', 'already' in said or 'encounter' in said)

    #  --- disconnect mid-encounter -------------------------------------------------------------------
    control.say('quit')
    time.sleep(8)
    report.check('a disconnect resolves the encounter rather than leaving it open',
                 'Active encounters: 0' in _colosseum(control))
    report.note('the client must be restarted by the runner before the next scenario')
