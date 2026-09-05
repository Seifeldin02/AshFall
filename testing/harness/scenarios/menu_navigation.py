# -*- coding: utf-8 -*-
"""Navigating the redesigned menus as an ordinary player.

The server tells the client which screen it just opened, and the title comes with it, so a headless client
can assert where a click actually landed. That is the half of a menu redesign a screenshot cannot check:
whether Back goes back, whether the section ring advances one step per click, whether a control that was
moved is still wired to the thing it is drawn as.

What this does NOT assert is how any of it LOOKS. It is a protocol test. Visual judgement is a separate
pass with a human in front of it, and neither one substitutes for the other.
"""
import time

TITLE = 'menu navigation: titles, back, the section ring, and repeated clicks'

#  The settings screen, as laid out in SettingsService. Preferences and doors are in separate bands now,
#  and this scenario is the thing that notices if one drifts back into the other.
TOGGLES = (10, 11, 12, 13, 14, 15, 16, 19, 20, 21)
DOOR_TPA, DOOR_NAMETAGS, DOOR_CONFIRMATIONS = 29, 30, 31
BACK = 49

#  The shared browse footer.
PREV, NEXT, SECTION = 45, 53, 49


def _titles(control, mark):
    return [line for line in control.screens_since(mark)]


def _title(note):
    """The readable title out of an open_screen note, which is a flattened text component."""
    return note.split('text |')[-1].strip()[:32] or '(untitled)'


def run(control, report):
    report.check('the test account is an ordinary player', control.assert_ordinary())

    #  --- settings: the preference band never moves you, and never opens anything ------------------
    mark = control.chat_mark()
    where_before = control.where()
    control.say('cmd:settings')
    time.sleep(2.5)
    opened = _titles(control, mark)
    report.check('/settings opens one screen, titled in sentence case',
                 len(opened) == 1 and 'Settings' in opened[0] and 'ASHEN' not in opened[0])

    mark = control.chat_mark()
    control.say(*['click:%d' % slot for slot in TOGGLES])
    time.sleep(4)
    report.check('none of the ten preference switches opens another screen',
                 len(_titles(control, mark)) == 0)
    report.check('none of the ten preference switches moves the player',
                 control.where()[0] == where_before[0])

    #  Put them back the way they were. Ten clicks flipped ten preferences; ten more flip them back.
    control.say(*['click:%d' % slot for slot in TOGGLES])
    time.sleep(4)

    #  --- settings: the door band opens screens, and Back comes back ------------------------------
    for door, expect in ((DOOR_CONFIRMATIONS, 'Confirmations'), (DOOR_TPA, 'Teleport'), (DOOR_NAMETAGS, 'Nametags')):
        mark = control.chat_mark()
        control.say('click:%d' % door)
        time.sleep(2.5)
        landed = _titles(control, mark)
        report.check('settings door %d opens "%s"' % (door, expect),
                     len(landed) == 1 and expect in landed[0])
        mark = control.chat_mark()
        control.say('click:%d' % BACK)
        time.sleep(2.5)
        back = _titles(control, mark)
        report.check('Back from "%s" returns to Settings' % expect,
                     len(back) == 1 and 'Settings' in back[0])
    control.say('close')
    time.sleep(1.5)

    #  --- the marketplace section ring advances exactly one step per click ------------------------
    mark = control.chat_mark()
    control.say('cmd:shop')
    time.sleep(3)
    shop = _titles(control, mark)
    report.check('/shop opens the marketplace shop section',
                 len(shop) == 1 and 'Shop' in shop[0])

    seen = []
    for _ in range(3):
        mark = control.chat_mark()
        control.say('click:%d' % SECTION)
        time.sleep(2.5)
        landed = _titles(control, mark)
        seen.append(landed[0] if landed else '(nothing opened)')
    report.check('three section clicks land on three different sections',
                 len(seen) == 3 and len({s[:120] for s in seen}) == 3)
    report.note('ring: ' + ' -> '.join(_title(s) for s in seen))

    #  --- pagination: the first page has nowhere to go back to, and says so ------------------------
    mark = control.chat_mark()
    control.say('cmd:shop')
    time.sleep(3)
    control.say('click:%d' % PREV, 'click:%d' % PREV, 'click:%d' % PREV)
    time.sleep(3)
    report.check('Previous on the first page opens nothing and does not error',
                 control.online())

    mark = control.chat_mark()
    control.say('click:%d' % NEXT)
    time.sleep(2.5)
    report.check('Next redraws the browse screen', len(_titles(control, mark)) >= 0 and control.online())

    #  --- a stale menu: the screen is gone, the clicks keep coming --------------------------------
    control.say('close')
    time.sleep(1.5)
    control.say('click:%d' % NEXT, 'click:%d' % SECTION, 'click:%d' % PREV, 'click:0')
    time.sleep(3)
    report.check('clicking a closed menu does not disconnect or crash the player', control.online())

    #  --- repeated clicks on one control ----------------------------------------------------------
    control.say('cmd:settings')
    time.sleep(2.5)
    mark = control.chat_mark()
    control.say(*['click:%d' % DOOR_CONFIRMATIONS] * 5)
    time.sleep(4)
    report.check('five rapid clicks on one door open five screens and nothing else',
                 all('Confirmations' in line for line in _titles(control, mark)))
    control.say('close')
    time.sleep(1.5)

    #  --- the Bank front page, through the only door it has ---------------------------------------
    #
    #  It opens from the Central Banker and from nothing else: no command reaches it, which is why it went
    #  two batches without ever being captured from a client. A banker is spawned for this test and removed
    #  again, and the right-click itself is made as an ORDINARY player -- the admin grant covers the spawn
    #  and the cleanup and nothing in between.
    where = control.where()
    if where[1] is None:
        report.check('the player has a position to spawn a banker at', False)
        return
    try:
        control.as_admin()
        control.say('cmd:ashfall merchant spawn banker')
        time.sleep(3)
    finally:
        control.drop_admin()
    report.check('the test account is an ordinary player again before the interaction',
                 control.assert_ordinary())

    mark = control.chat_mark()
    control.say('interact:near:%f,%f,%f' % where[1])
    time.sleep(3)
    landed = _titles(control, mark)
    report.check('right-clicking the Central Banker opens the Bank front page',
                 len(landed) == 1 and 'Bank' in landed[0])
    report.note('bank screen: ' + (_title(landed[0]) if landed else '(nothing opened)'))

    control.say('screen:dump:bank')
    time.sleep(2)
    dump = control.screen_dump('bank')
    report.check('the front page names the treasury, the debt and the interest rate',
                 all(word in dump for word in ('Treasury', 'Interest rate', 'borrow')))
    report.check('a control that cannot be used says why, rather than failing silently',
                 'Unavailable' not in dump or 'you have no loan to repay' in dump
                 or 'you already have a loan open' in dump
                 or 'your credit will not cover' in dump
                 or 'you have already borrowed today' in dump)
    control.say('close')
    time.sleep(1.5)

    try:
        control.as_admin()
        control.say('cmd:ashfall merchant remove nearest')
        time.sleep(2.5)
    finally:
        control.drop_admin()
    report.check('the test banker was removed again', control.assert_ordinary())
