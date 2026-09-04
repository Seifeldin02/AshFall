# -*- coding: utf-8 -*-
"""The staging test lease: contention, refusal, stale recovery, and that the suite still runs afterwards.

WHAT THIS EXISTS FOR: on 2026-09-04 `/ashfall colosseum verify` reported three failures. All three were
true at the moment they were measured -- a harness scenario had an encounter running, and the verifier
found a scheduled task belonging to it. Re-run on a quiet server, 278/0.

A suite that fails because something else is legitimately happening is worse than one that does not run,
because it teaches whoever reads the output to discount failures. So the destructive suites take an
exclusive lease and refuse before touching anything.

Console only -- no client, no world changes. Run it against staging with the harness config:

    python testing/harness/test_lease.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from ashfall_harness import guard, rcon                    # noqa: E402

SECTION = u'§'
GATED = ('colosseum verify', 'voidworld verify', 'duelmap canary', 'hopper verify')


def strip(text):
    return re.sub(SECTION + u'.', '', text or '')


class Check(object):
    def __init__(self):
        self.failed = []
        self.passed = 0

    def __call__(self, what, ok):
        self.passed += 1
        print('  %-7s %s' % ('ok' if ok else 'FAILED', what))
        if not ok:
            self.failed.append(what)


def rc(commands, settle=0.4):
    return [(c, strip(t)) for c, t in rcon.send(commands, settle=settle)]


def one(command, settle=0.4):
    return rc([command], settle)[0][1]


def main():
    endpoint = guard.load(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'harness.ini'))
    rcon.use(endpoint)
    check = Check()
    print('Staging test lease -- %s:%d' % (endpoint.host, endpoint.rcon_port))
    print('')

    #  Start from a known state, whatever an earlier crashed run left behind.
    one('ashfall lease break')

    #  --- a lease held by somebody else refuses every destructive suite -----------------------------
    held = one('ashfall lease acquire harness pretending-to-be-a-run 300')
    check('a lease can be taken', 'Lease held by harness' in held)

    for suite in GATED:
        reply = one('ashfall ' + suite, settle=1.0)
        refused = 'lease is held by harness' in reply
        ran = 'checks' in reply or 'CANARY' in reply or 'Verifying' in reply
        check('%-18s refuses while the lease is held' % suite, refused and not ran)

    #  The refusal has to say what is in the way, not merely that something is.
    check('the refusal names the holder and its purpose',
          'harness' in one('ashfall ' + GATED[0], settle=1.0)
          and 'pretending-to-be-a-run' in one('ashfall ' + GATED[0], settle=1.0))

    #  --- and nothing was touched -------------------------------------------------------------------
    check('nothing ran, so no encounter was created',
          'Active encounters: 0' in one('ashfall colosseum instances'))

    #  --- releasing lets them through ---------------------------------------------------------------
    check('the holder can give it back', 'released' in one('ashfall lease release harness'))
    check('and the lease reads free afterwards', 'free' in one('ashfall lease status'))
    reply = one('ashfall duelmap canary', settle=25.0)
    check('a destructive suite runs normally once the lease is free', 'CANARY PASSED' in reply)

    #  --- a crashed holder cannot keep it forever ----------------------------------------------------
    one('ashfall lease acquire ghost crashed-mid-run 30')
    status = one('ashfall lease status')
    check('a short lease is held', 'ghost' in status)
    print('  ..      waiting 32s for the lease to expire on its own')
    time.sleep(32)
    check('an expired lease stops blocking anything', 'free' in one('ashfall lease status'))
    check('and the suite it was blocking runs again',
          'CANARY PASSED' in one('ashfall duelmap canary', settle=25.0))

    #  --- and it can be taken from a holder that is not coming back ----------------------------------
    one('ashfall lease acquire ghost still-crashed 600')
    check('break takes a live lease', 'Took the lease from ghost' in one('ashfall lease break'))
    check('and leaves it free', 'free' in one('ashfall lease status'))

    #  --- read-only checks are deliberately never gated ----------------------------------------------
    one('ashfall lease acquire harness read-only-check 120')
    check('selftest is not gated', 'restart marker' in one('ashfall selftest', settle=1.5))
    check('duelmap verify is not gated', 'lease is held' not in one('ashfall duelmap verify', settle=3.0))
    one('ashfall lease break')

    print('')
    print('%d failed' % len(check.failed))
    for what in check.failed:
        print('  FAILED  %s' % what)
    return 1 if check.failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
