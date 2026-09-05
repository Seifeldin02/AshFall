# -*- coding: utf-8 -*-
"""The sidebar, measured the way the client draws it.

The panel is exactly as wide as its widest row, so "the spacing is off" is a measurable claim and not a
matter of taste: one long row and every other row sits in the empty half it left behind. The rows arrive
here as real scoreboard packets, so this reads what the client was actually handed rather than what the
server believes it composed.

The width table below is written out again ON PURPOSE. CoreUtil has one too, and if this file simply asked
the server how wide it thought a line was, the two would agree on whatever was wrong. Two independent
tables disagreeing is the only way a mistake in either one shows up.

What this does NOT assert is how it LOOKS -- the shade of grey, whether the composition is pleasant, or
anything at all about Bedrock. Those need eyes and, for Bedrock, a Bedrock client.
"""
import time

TITLE = 'sidebar: rendered width, blank rows, and what happens when the state changes'

#  Advances from the vanilla ASCII page, and the budget CoreUtil.Menu composes against.
NARROW = {2: "!,.:;i|'", 3: 'l`', 4: ' I[]t', 5: '"()*<>fk{}', 7: '@~'}
BUDGET = 104


def width(text):
    """Rendered pixels, colour codes excluded, bold counted."""
    total = 0
    bold = False
    index = 0
    while index < len(text):
        char = text[index]
        if char == u'§' and index + 1 < len(text):
            code = text[index + 1].lower()
            if code == 'l':
                bold = True
            elif code == 'r' or code.isdigit() or code in 'abcdef':
                bold = False
            index += 2
            continue
        advance = 6
        for pixels, chars in NARROW.items():
            if char in chars:
                advance = pixels
                break
        total += advance + (1 if bold else 0)
        index += 1
    return total


def visible(row):
    """The row with its colour codes taken out -- what is actually drawn."""
    out = []
    index = 0
    while index < len(row):
        if row[index] == u'§' and index + 1 < len(row):
            index += 2
            continue
        out.append(row[index])
        index += 1
    return ''.join(out)


def rows(control, label='sidebar'):
    control.say('sidebar:dump:%s' % label)
    time.sleep(1.5)
    return [line for line in control.screen_dump(label).splitlines()]


def run(control, report):
    report.check('the test account is an ordinary player', control.assert_ordinary())

    #  --- the panel as it stands -----------------------------------------------------------------
    drawn = rows(control)
    report.check('the client is holding a sidebar at all', len(drawn) >= 4)
    report.note('%d rows: %s' % (len(drawn), ' | '.join(visible(r).strip() for r in drawn)))

    widest = max((width(r), r) for r in drawn) if drawn else (0, '')
    report.check('no row is wider than the %d-pixel budget (widest is %d)' % (BUDGET, widest[0]),
                 widest[0] <= BUDGET)
    report.note('widest row: %r at %d px' % (visible(widest[1]), widest[0]))

    #  The complaint that started this: one long row setting the width while the rest sit half empty.
    #  Separators are excluded -- they are deliberately empty and carry no width at all.
    content = [r for r in drawn if visible(r).strip()]
    if content:
        narrowest = min(width(r) for r in content)
        report.check('the panel is not one long row and a lot of air (narrowest content row is %d px, '
                     'widest %d)' % (narrowest, widest[0]), widest[0] - narrowest <= 56)

    #  --- blank rows are separators, never accidents ---------------------------------------------
    blanks = [index for index, row in enumerate(drawn) if not visible(row).strip()]
    report.check('no blank row at the top or the bottom',
                 0 not in blanks and (len(drawn) - 1) not in blanks)
    report.check('no two blank rows next to each other',
                 all(b + 1 not in blanks for b in blanks))
    report.check('every blank row really is blank (no stray padding)',
                 all(width(drawn[b]) == 0 for b in blanks))
    report.note('blank rows at: %s' % (blanks or 'none'))

    #  --- values that must never be cut into ambiguity -------------------------------------------
    joined = ' '.join(visible(r) for r in drawn)
    report.check('the balance row is present and its number is not truncated',
                 'Balance' in joined and u'…' not in joined.split('Balance')[1][:16])
    report.check('a label is never repeated with two different meanings',
                 joined.count('Bounty') <= 1 or 'Wanted' in joined)

    #  --- the toggle: off means gone, on means back, and nothing is left behind ------------------
    before = list(drawn)
    control.say('cmd:sidebar')
    time.sleep(3)
    off = rows(control, 'sidebar_off')
    report.check('turning the sidebar off removes every row rather than hiding a stale one',
                 len(off) == 0)

    control.say('cmd:sidebar')
    time.sleep(4)
    back = rows(control, 'sidebar_on')
    report.check('turning it back on restores the same rows', len(back) == len(before))
    report.check('and none of them came back wider than the budget',
                 all(width(r) <= BUDGET for r in back))

    #  --- the emphasis block appears and, more importantly, disappears ---------------------------
    #  The rows that only exist during an event are the ones that can be left stranded, because they are
    #  removed by name rather than by rewriting the whole panel.
    #  Driven from the console, so the test account stays an ordinary player throughout -- the sidebar is
    #  a player-facing surface and an opped observer is not the one that matters.
    try:
        control.rc(['ashfall event resource'])
        time.sleep(5)
        during = rows(control, 'sidebar_event')
        report.check('an active event adds an emphasised block',
                     len(during) > len(back) and any('Event' in visible(r) for r in during))
        report.check('and every row of it still fits the budget',
                     all(width(r) <= BUDGET for r in during))

        control.rc(['ashfall event stop'])
        time.sleep(5)
        after = rows(control, 'sidebar_after')
        report.check('ending the event removes its rows, leaving none stranded',
                     not any('Event' in visible(r) and 'Next event' not in visible(r) for r in after))
        report.check('and removes its blank separator with them', len(after) == len(back))
        report.note('rows: %d idle -> %d during -> %d after' % (len(back), len(during), len(after)))
    finally:
        control.rc(['ashfall event stop'])

    #  --- the refresh does not resend rows nobody changed -----------------------------------------
    #  Every row used to be reset and re-added whenever any single one of them differed. Sitting still for
    #  four refresh cycles should now cost close to nothing.
    control.say('sidebar:reset')
    time.sleep(9)
    control.say('sidebar:count:sidebar_packets')
    time.sleep(1.5)
    count = control.screen_dump('sidebar_packets').strip()
    settled = int(count) if count.isdigit() else -1
    report.check('an idle sidebar sends almost nothing over four refresh cycles (%s row packets)' % settled,
                 0 <= settled <= len(back))
