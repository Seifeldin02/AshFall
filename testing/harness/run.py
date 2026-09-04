# -*- coding: utf-8 -*-
"""Run the real-game scenarios against a configured staging server.

  python run.py                 every scenario
  python run.py charge          one of them
  python run.py --list

The endpoint comes from harness.ini and is validated before anything connects. See README.md.
"""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from ashfall_harness import control as control_module   # noqa: E402
from ashfall_harness import guard, rcon                 # noqa: E402
import scenarios                                        # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


class Report(object):
    def __init__(self, title):
        self.title = title
        self.passed = 0
        self.failed = []

    def check(self, what, ok):
        self.passed += 1
        if not ok:
            self.failed.append(what)
        print('   %s  %s' % ('ok    ' if ok else 'FAILED', what))

    def note(self, what):
        print('   ..      %s' % what)

    def fail(self, what):
        self.passed += 1
        self.failed.append(what)
        print('   FAILED  %s' % what)


def start_client(endpoint, seconds):
    """The client runs as its own process so a scenario can disconnect it on purpose.

    Any queued instruction from a previous run is discarded first. The last thing cleanup does is queue
    'quit', and if the client had already gone the file survives -- so the next client would join, read it,
    and leave immediately, which reads as "the test client would not join"."""
    for stale in ('bot_%s.cmd' % endpoint.account, 'bot_%s.cmd.taken' % endpoint.account):
        path = os.path.join(HERE, stale)
        if os.path.exists(path):
            os.remove(path)
    return subprocess.Popen([sys.executable, '-m', 'ashfall_harness.client', endpoint.account, str(seconds)],
                            cwd=HERE, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)


def main():
    names = [a for a in sys.argv[1:] if not a.startswith('-')]
    if '--list' in sys.argv:
        for key, module in sorted(scenarios.ALL.items()):
            print('%-18s %s' % (key, getattr(module, 'TITLE', '')))
        return 0

    endpoint = guard.load()
    rcon.use(endpoint)
    print('Ashfall real-game harness')
    print('  endpoint : %s:%d (rcon %d)' % (endpoint.host, endpoint.port, endpoint.rcon_port))
    print('  account  : %s (ordinary player unless a scenario grants otherwise)' % endpoint.account)
    print('')

    chosen = names or sorted(scenarios.ALL)
    unknown = [n for n in chosen if n not in scenarios.ALL]
    if unknown:
        print('unknown scenario(s): %s' % ', '.join(unknown))
        return 2

    #  The harness holds the staging test lease for its whole run, so an in-server verifier started
    #  from the console refuses instead of measuring a subsystem this is halfway through changing.
    #  The TTL means a crashed run frees it on its own rather than blocking the next person.
    rcon.send(['ashfall lease acquire harness scenario-run 3600'], settle=0.4)
    control = control_module.Control(endpoint, workdir=HERE)
    results = []
    for name in chosen:
        module = scenarios.ALL[name]
        print('== %s -- %s' % (name, getattr(module, 'TITLE', '')))
        client = start_client(endpoint, 1800)
        time.sleep(6)
        report = Report(name)
        try:
            if not control.ensure_online():
                report.fail('the test client would not join')
            else:
                #  Every scenario starts from an ordinary, logged-in, alive player.
                control.rc(['deop %s' % control.name, 'gamemode survival %s' % control.name])
                control.rc(['tellraw %s {"text":"BOTCHK"}' % control.name])
                time.sleep(1.5)
                module.run(control, report)
        except Exception as error:                      # a scenario blowing up is a failure, not a crash
            report.fail('scenario raised: %r' % (error,))
        finally:
            try:
                control.cleanup()
            finally:
                control.say('quit')
                time.sleep(2)
                if client.poll() is None:
                    client.terminate()
        results.append(report)
        print('')

    rcon.send(['ashfall lease release harness'], settle=0.4)
    print('---')
    bad = 0
    for report in results:
        bad += len(report.failed)
        print('%-18s %2d checks, %d failed' % (report.title, report.passed, len(report.failed)))
        for line in report.failed:
            print('      FAILED  %s' % line)
    print('')
    print('TOTAL: %d failed' % bad)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
