# -*- coding: utf-8 -*-
"""Driving the test account, and cleaning up only what the harness itself created.

Two rules this module exists to enforce:

  * The test account is an ORDINARY PLAYER by default. Administrator-only testing is exactly how
    /colosseum leave stayed broken -- admins skip the duplicate-command guard that broke it, so the one
    command a player could not confirm looked fine from every account anybody tested with. Privileges are
    granted around the specific step that needs them and revoked immediately afterwards.

  * Cleanup removes what this run made, and nothing else. The staging database has real records in it from
    real people playing; a harness that "tidies up" by pattern is a harness that eventually deletes them.
"""
import io
import os
import re
import time

from . import rcon

SECTION = u'§'


def strip(text):
    return re.sub(SECTION + u'.', '', text or '')


class Control(object):
    def __init__(self, endpoint, workdir=None):
        self.endpoint = endpoint
        self.name = endpoint.account
        self.workdir = workdir or os.getcwd()
        self.created_worlds = []
        self.granted_admin = False

    # ---------------------------------------------------------------- console
    def rc(self, commands, settle=0.4):
        return [(c, strip(t)) for c, t in rcon.send(commands, settle=settle)]

    def show(self, commands, settle=0.4, width=300):
        for command, text in self.rc(commands, settle):
            print('>>>', command)
            if text.strip():
                print('   ', text[:width].replace('\n', '\n    '))

    # ---------------------------------------------------------------- the player
    def say(self, *lines):
        """Queue instructions for the client: commands, clicks, movement, capture control."""
        path = os.path.join(self.workdir, 'bot_%s.cmd' % self.name)
        with io.open(path, 'a', encoding='utf-8') as f:
            for line in lines:
                f.write(line + u'\n')

    def online(self):
        return self.name in self.rc(['list'])[0][1]

    def ensure_online(self, retries=3):
        for _ in range(retries):
            if self.online():
                self.rc(['authme forcelogin %s' % self.name])
                time.sleep(0.6)
                #  A dead player cannot run a command, and the server answers one with
                #  "chat.disabled.options" rather than anything that names the real reason.
                if '0.0f' in self.rc(['data get entity %s Health' % self.name])[0][1]:
                    self.say('respawn')
                    time.sleep(1.5)
                return True
            time.sleep(2)
        return False

    def where(self):
        out = self.rc(['data get entity %s Dimension' % self.name,
                       'data get entity %s Pos' % self.name], settle=0.1)
        dimension = re.search(r'"([^"]+)"', out[0][1])
        position = re.search(r'\[([-\d.E]+)d, ([-\d.E]+)d, ([-\d.E]+)d\]', out[1][1])
        return (dimension.group(1) if dimension else '?',
                tuple(round(float(v), 2) for v in position.groups()) if position else None)

    def health(self):
        found = re.search(r'([-\d.]+)f', self.rc(['data get entity %s Health' % self.name], settle=0.1)[0][1])
        return float(found.group(1)) if found else None

    def chat_since(self, mark):
        path = os.path.join(self.workdir, 'bot_%s.log' % self.name)
        text = io.open(path, encoding='utf-8', errors='replace').read()[mark:]
        return [line[9:] for line in text.splitlines() if line[9:].startswith('CHAT ')]

    def chat_mark(self):
        path = os.path.join(self.workdir, 'bot_%s.log' % self.name)
        return len(io.open(path, encoding='utf-8', errors='replace').read())

    # ---------------------------------------------------------------- privileges
    def as_admin(self):
        """Grant operator for one step. Always paired with drop_admin() in a finally."""
        self.rc(['op %s' % self.name])
        self.granted_admin = True

    def drop_admin(self):
        if self.granted_admin:
            self.rc(['deop %s' % self.name])
            self.granted_admin = False

    def assert_ordinary(self):
        """The default state, asserted rather than assumed."""
        listed = self.rc(['deop %s' % self.name])[0][1]
        return 'not an operator' in listed or 'no longer' in listed

    # ---------------------------------------------------------------- test fixtures
    def fund(self, amount):
        """Test money, from the console, into the test account only."""
        self.rc(['ashfall balance add %s %d' % (self.name, amount)])

    #  Distinctive quantities, so a miscount is obvious rather than plausible.
    MARKED = (('minecraft:diamond', 17), ('minecraft:netherite_ingot', 3), ('minecraft:cooked_beef', 41))

    def give_marked_items(self):
        """A recognisable inventory, so "the same items came back" is a comparison and not a vibe."""
        self.rc(['clear %s' % self.name])
        self.rc(['give %s %s %d' % (self.name, item, count) for item, count in self.MARKED])
        time.sleep(1.0)
        return self.inventory()

    def inventory(self):
        """How many of each marked item the player is holding.

        Counted with `clear <player> <item> 0`, which reports matches WITHOUT removing them. Reading
        `data get entity Inventory` instead means parsing nested NBT components -- the server hands out a
        compass whose lore alone is most of a kilobyte -- across an RCON reply that can be split or
        truncated. Both failure modes read as "the items changed", which sends you hunting a duplication
        bug that is really a parser."""
        counts = []
        for item, _ in self.MARKED:
            reply = self.rc(['clear %s %s 0' % (self.name, item)], settle=0.15)[0][1]
            found = re.search(r'(\d+)\s+matching item', reply)
            counts.append((item, int(found.group(1)) if found else 0))
        return counts

    def note_world(self, label):
        self.created_worlds.append(label)

    def cleanup(self):
        """Only what this run created."""
        self.drop_admin()
        for label in self.created_worlds:
            self.rc(['ashfall voidworld close %s' % label])
            self.rc(['ashfall voidworld delete %s' % label])
        self.created_worlds = []
