# -*- coding: utf-8 -*-
"""A one-line method that calls itself with its own arguments, unchanged.

This exists because of exactly one line, found in production's log eight minutes after the 2026-09-05
promotion:

    private static void tell(Player player, String message) {
        if (player != null && player.isOnline()) tell(player, message);
    }

Nine narration sites in the Colosseum, every one a StackOverflowError the moment a real player was on the
other end. Every test suite passed, because the verifier drives those mechanics with `player == null` on
purpose -- so the guard short-circuited and the recursion was never reached. The only path that could find
it was a person fighting a boss.

The shape is worth checking for mechanically because it is invisible to review: a wrapper named after the
thing it wraps looks correct at a glance, and the compiler is perfectly happy with it.

Overloads are the common, correct version of this shape -- `open(player)` calling `open(player, MAIN)` --
and there are forty of them in this codebase. The difference is the argument list: a real overload changes
it. This only reports a call whose arguments are the parameter names, in order, unchanged.

    python testing/selfcall_check.py
"""
from __future__ import print_function

import io
import os
import re
import sys

SOURCE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      '..', 'smpcore-src', 'src', 'main', 'java', 'net', 'communitysmp', 'core')

#  A whole method on one line: signature, then a body in braces.
METHOD = re.compile(r'\b(\w+)\s*\(([^()]*)\)\s*(?:throws [\w., ]+)?\{(.*)\}\s*$')
KEYWORDS = {'if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'synchronized', 'do', 'else'}


def parameter_names(params):
    """The declared names, in order. `Player player, String message` -> ['player', 'message']."""
    names = []
    depth = 0
    current = ''
    for char in params:
        if char == '<':
            depth += 1
        elif char == '>':
            depth -= 1
        if char == ',' and depth == 0:
            names.append(current)
            current = ''
        else:
            current += char
    names.append(current)
    out = []
    for chunk in names:
        words = re.findall(r'\w+', chunk)
        if words:
            out.append(words[-1])
    return out


def offences(path):
    found = []
    for number, line in enumerate(io.open(path, encoding='utf-8'), 1):
        stripped = line.strip()
        match = METHOD.search(stripped)
        if not match:
            continue
        name, params, body = match.group(1), match.group(2), match.group(3)
        if name in KEYWORDS:
            continue
        #  String literals are not code. `state(key,value)` appears verbatim inside the SQL that
        #  Database.state() runs -- "INSERT INTO state(key,value) VALUES(?,?)" -- and matching inside it
        #  reports a method that is doing nothing of the kind. One false positive is all it takes for a
        #  check like this to be ignored.
        body = re.sub(r'"(?:[^"\\]|\\.)*"', '""', body)
        names = parameter_names(params)
        #  The same name, called with exactly its own parameters in order. A no-argument method calling
        #  itself counts too, and is if anything worse.
        call = re.escape(name) + r'\s*\(\s*' + r'\s*,\s*'.join(re.escape(n) for n in names) + r'\s*\)'
        if re.search(r'(?<![\w.])' + call, body):
            found.append((number, stripped))
    return found


def main():
    total = 0
    for name in sorted(os.listdir(SOURCE)):
        if not name.endswith('.java'):
            continue
        for number, text in offences(os.path.join(SOURCE, name)):
            total += 1
            print('%s:%d' % (name, number))
            print('   ' + text[:160])
    if total:
        print('')
        print('%d method(s) call themselves with their own arguments unchanged.' % total)
        print('That is unconditional recursion, not an overload. It will overflow the stack the first time')
        print('the guard in front of it lets anything through.')
        return 1
    print('no method calls itself with its own arguments unchanged')
    return 0


if __name__ == '__main__':
    sys.exit(main())
