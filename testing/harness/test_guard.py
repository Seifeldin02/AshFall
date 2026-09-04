# -*- coding: utf-8 -*-
"""The safety rail, asserted rather than trusted.

Runs without a server and without harness.ini. If any of these stops refusing, the harness can be pointed
at production, which is the one thing it must never be able to do.

  python test_guard.py
"""
import configparser
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from ashfall_harness import guard   # noqa: E402

BASE = {
    'host': '127.0.0.1', 'port': '25566', 'rcon_port': '25576',
    'rcon_password': 'staging-only', 'account': 'AshfallProbe', 'account_password': 'throwaway',
}


def write(overrides):
    values = dict(BASE)
    values.update(overrides)
    parser = configparser.ConfigParser()
    parser['staging'] = values
    handle, path = tempfile.mkstemp(suffix='.ini')
    with os.fdopen(handle, 'w', encoding='utf-8') as f:
        parser.write(f)
    return path


def refuses(label, overrides):
    path = write(overrides)
    try:
        guard.load(path)
        print('  FAILED  %s was ACCEPTED' % label)
        return 1
    except guard.RefusedError as error:
        print('  ok      %s refused (%s)' % (label, str(error).splitlines()[0].split(': ', 1)[-1]))
        return 0
    finally:
        os.remove(path)


def accepts(label, overrides):
    path = write(overrides)
    try:
        guard.load(path)
        print('  ok      %s accepted' % label)
        return 0
    except guard.RefusedError as error:
        print('  FAILED  %s was refused: %s' % (label, error))
        return 1
    finally:
        os.remove(path)


def main():
    print('harness safety rail')
    bad = 0
    bad += refuses('the production play port', {'port': '25565'})
    bad += refuses('the production RCON port', {'rcon_port': '25575'})
    bad += refuses('both production ports', {'port': '25565', 'rcon_port': '25575'})
    bad += refuses('the production hostname', {'host': 'play.ashfall.gg'})
    bad += refuses('a real administrator account', {'account': 'MacoCT'})
    bad += refuses('a real administrator account, any case', {'account': 'tpkiid'})
    bad += refuses('a blank RCON password', {'rcon_password': ''})
    bad += refuses('a blank account', {'account': ''})
    bad += accepts('an ordinary staging endpoint', {})

    try:
        guard.load(os.path.join(tempfile.gettempdir(), 'definitely-not-here-%d.ini' % os.getpid()))
        print('  FAILED  a missing configuration was ACCEPTED')
        bad += 1
    except guard.RefusedError:
        print('  ok      a missing configuration refuses rather than defaulting')

    print('')
    print('%d failed' % bad)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
