# -*- coding: utf-8 -*-
"""The Chainbound Behemoth's charge, measured at the resolution a client actually sees.

WHAT THIS CAUGHT: the charge never moved. It turns the boss's AI off so nothing in the engine can steer it,
then drove the flight with setVelocity -- and an AI-less mob ignores applied velocity entirely. The boss
stood still for the whole charge, its own no-progress guard fired after four steps, and it crash-stunned
itself half a second after every single charge.

The suite that shipped with the bug asserted "a charge ends in a stun", which the broken implementation
satisfied perfectly, because it drove 400 steps inside one server tick where nothing can move.

So this measures DISTANCE first and outcome second, from entity-position packets rather than from RCON
polling -- a two-second charge is invisible at RCON round-trip rates.
"""
import io
import os
import re
import time

TITLE = 'the charge moves, connects, and cannot steer'


def _instances(control):
    found = re.findall(r'(colo_inst_[A-Za-z0-9_]+)', control.rc(['ashfall colosseum instances'])[0][1])
    return found[0] if found else None


def _path(control, boss_only=True):
    rows = []
    path = os.path.join(control.workdir, 'trace_%s.csv' % control.name)
    for line in io.open(path, encoding='utf-8').read().splitlines():
        when, eid, kind, x, y, z = line.split(',')
        rows.append((float(when), int(eid), float(x), float(z)))
    if not rows:
        return []
    walked, last = {}, {}
    for when, eid, x, z in rows:
        if eid in last:
            a = last[eid]
            walked[eid] = walked.get(eid, 0) + ((x - a[0]) ** 2 + (z - a[1]) ** 2) ** 0.5
        last[eid] = (x, z)
    boss = max(walked, key=walked.get)
    return [(w, x, z) for w, eid, x, z in rows if not boss_only or eid == boss]


def _bursts(path, threshold=1.2, minimum=3):
    out, run = [], []
    for i in range(1, len(path)):
        (_, x0, z0), (t1, x1, z1) = path[i - 1], path[i]
        if ((x1 - x0) ** 2 + (z1 - z0) ** 2) ** 0.5 > threshold:
            run.append((t1, x0, z0, x1, z1))
        elif run:
            if len(run) >= minimum:
                out.append(run)
            run = []
    if len(run) >= minimum:
        out.append(run)
    return out


def run(control, report):
    control.fund(3_000_000)
    control.rc(['gamemode survival %s' % control.name, 'effect clear %s' % control.name,
                'effect give %s minecraft:resistance 900 4 true' % control.name])
    control.say('trace:start', 'cmd:colosseum chainbound_behemoth')
    time.sleep(3)
    control.say('click:15')

    world, deadline = None, time.time() + 90
    while time.time() < deadline and not world:
        time.sleep(2)
        world = _instances(control)
    if not world:
        report.fail('no encounter instance appeared')
        return
    report.note('instance %s' % world)

    #  Step sideways periodically. A locked charge must not follow.
    began = time.time()
    while time.time() - began < 80:
        control.rc(['execute in minecraft:%s run tp %s ~6 ~ ~' % (world, control.name)], settle=0.05)
        time.sleep(3.5)
        control.rc(['execute in minecraft:%s run tp %s ~-6 ~ ~' % (world, control.name)], settle=0.05)
        time.sleep(3.5)
    control.say('trace:dump')
    time.sleep(2)

    path = _path(control)
    bursts = _bursts(path)
    report.check('the boss charges at all (%d charges seen)' % len(bursts), len(bursts) >= 2)
    if not bursts:
        return

    lanes, drifts = [], []
    for burst in bursts:
        travelled = sum(((s[3] - s[1]) ** 2 + (s[4] - s[2]) ** 2) ** 0.5 for s in burst)
        lanes.append(travelled)
        straight = ((burst[-1][3] - burst[0][1]) ** 2 + (burst[-1][4] - burst[0][2]) ** 2) ** 0.5
        drifts.append(abs(travelled - straight))
    report.note('lanes travelled: %s' % ['%.1f' % v for v in lanes])
    #  Against the broken build every one of these is zero: the boss never moved.
    report.check('each charge covers most of its 30-block lane (min %.1f)' % min(lanes), min(lanes) > 18)
    report.check('each charge runs straight -- it cannot steer (max deviation %.2f)' % max(drifts),
                 max(drifts) < 3.0)
    report.check('a charge takes several steps, so the launch is not mistaken for a collision',
                 min(len(b) for b in bursts) >= 5)

    control.say('cmd:colosseum leave')
    time.sleep(4)
    control.say('cmd:colosseum leave')
    time.sleep(4)
    left = control.rc(['ashfall colosseum instances'])[0][1]
    report.check('the encounter is resolved and the instance is gone', 'instance worlds: 0' in left)
