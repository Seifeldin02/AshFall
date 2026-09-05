# -*- coding: utf-8 -*-
"""An ordinary player leaving a paid encounter.

WHAT THIS CAUGHT: /colosseum leave is confirm-by-repeat -- it prints "run it again within 10 seconds" --
and the duplicate-command guard cancelled the repeat with "You just ran that, wait a moment". The one
command that asks to be run twice was the one command that could not be, and it is the way out of a fight
somebody has paid to be in.

Nobody saw it because admins skip the guard. THIS SCENARIO MUST RUN AS AN ORDINARY PLAYER; running it with
operator is the same mistake that hid the bug.
"""
import re
import time

TITLE = 'an ordinary player can leave a paid encounter promptly'


def _report(control):
    return control.rc(['ashfall colosseum instances'])[0][1].strip().split('\n')[0]


def run(control, report):
    report.check('the test account is NOT an operator (admins skip the guard that broke this)',
                 control.assert_ordinary())

    control.fund(3_000_000)
    control.say('cmd:colosseum cinderveil_arcanist')
    time.sleep(3)
    control.say('click:15')
    time.sleep(8)
    report.check('the encounter started', 'Active encounters: 1' in _report(control))

    mark = control.chat_mark()
    control.say('cmd:colosseum leave')
    time.sleep(2.5)
    said = ' '.join(control.chat_since(mark))
    report.check('the first call warns rather than leaving', 'forfeits' in said or 'again' in said)

    #  Promptly, the way the message tells you to -- this is the step that used to be refused.
    mark = control.chat_mark()
    control.say('cmd:colosseum leave')
    time.sleep(4)
    said = ' '.join(control.chat_since(mark))
    report.check('the prompt repeat is NOT refused as a duplicate command',
                 'just ran that' not in said.lower())
    report.check('the encounter actually ended', 'Active encounters: 0' in _report(control))
    report.check('and the player is out of the arena', 'colo_inst' not in control.where()[0])
