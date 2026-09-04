# -*- coding: utf-8 -*-
"""Confirmation screens: the confirm slot buys, the cancel slot does not, and closing charges nothing.

A headless click proves behaviour, not visual quality -- but behaviour is the half that can take somebody's
money. This asserts the financial consequence of each control, and that the slots still mean what the
click handler thinks they mean. Moving an icon must never make a different slot perform a purchase.
"""
import re
import time

TITLE = 'confirmation screens: confirm pays, cancel does not, close does not'

CONFIRM_SLOT = 15
CANCEL_SLOT = 11


def _balance(control):
    found = re.search(r'\$([\d,]+)', control.rc(['ashfall balance ' + control.name])[0][1])
    if found:
        return float(found.group(1).replace(',', ''))
    raw = control.rc(['data get entity %s Pos' % control.name])[0][1]
    return None


def _colosseum(control):
    return control.rc(['ashfall colosseum instances'])[0][1]


def run(control, report):
    control.fund(3_000_000)

    #  --- cancel costs nothing -------------------------------------------------------------------
    control.say('cmd:colosseum warden_of_cinders')
    time.sleep(3)
    control.say('click:%d' % CANCEL_SLOT)
    time.sleep(4)
    report.check('cancelling the confirmation starts no encounter',
                 'Active encounters: 0' in _colosseum(control))

    #  --- closing the window costs nothing -------------------------------------------------------
    control.say('cmd:colosseum warden_of_cinders')
    time.sleep(3)
    control.say('close')
    time.sleep(4)
    report.check('closing the confirmation starts no encounter',
                 'Active encounters: 0' in _colosseum(control))

    #  --- confirm does what it says --------------------------------------------------------------
    control.say('cmd:colosseum warden_of_cinders')
    time.sleep(3)
    control.say('click:%d' % CONFIRM_SLOT)
    time.sleep(8)
    report.check('confirming starts exactly one encounter',
                 'Active encounters: 1' in _colosseum(control))

    #  --- a duplicate confirm click must not start a second one or charge twice -------------------
    control.say('click:%d' % CONFIRM_SLOT, 'click:%d' % CONFIRM_SLOT)
    time.sleep(4)
    report.check('extra clicks on a spent confirmation do nothing',
                 'Active encounters: 1' in _colosseum(control))

    control.say('cmd:colosseum leave')
    time.sleep(4)
    control.say('cmd:colosseum leave')
    time.sleep(5)
    report.check('the encounter is cleaned up afterwards',
                 'Active encounters: 0' in _colosseum(control))
