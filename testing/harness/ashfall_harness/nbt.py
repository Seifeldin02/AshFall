# -*- coding: utf-8 -*-
"""Decode a Minecraft network-NBT text component into what the player actually sees.

Judging a redesign by reading colour codes in the source is not judging the redesign. This turns the bytes
the server sent into the rendered line, with the colours it will be drawn in.
"""
import struct

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG = 0, 1, 2, 3, 4
TAG_FLOAT, TAG_DOUBLE, TAG_BYTE_ARRAY, TAG_STRING = 5, 6, 7, 8
TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY, TAG_LONG_ARRAY = 9, 10, 11, 12

LEGACY = {
    'black': '0', 'dark_blue': '1', 'dark_green': '2', 'dark_aqua': '3', 'dark_red': '4',
    'dark_purple': '5', 'gold': '6', 'gray': '7', 'grey': '7', 'dark_gray': '8', 'dark_grey': '8',
    'blue': '9', 'green': 'a', 'aqua': 'b', 'red': 'c', 'light_purple': 'd', 'yellow': 'e', 'white': 'f',
}
NAMES = {v: k for k, v in LEGACY.items() if k not in ('grey', 'dark_grey')}


class Reader(object):
    def __init__(self, data, at=0):
        self.d, self.i = data, at

    def u1(self):
        #  Running out mid-compound reads as TAG_END. This is a renderer, not a validator: a component
        #  whose trailing bytes belong to the rest of the packet should still draw, and refusing to draw it
        #  is how a capture ends up showing raw field names instead of the message.
        if self.i >= len(self.d):
            return TAG_END
        v = self.d[self.i]
        self.i += 1
        return v

    def take(self, n):
        v = self.d[self.i:self.i + n]
        self.i += n
        return v

    def string(self):
        n = struct.unpack('>H', self.take(2))[0]
        return self.take(n).decode('utf-8', 'replace')

    def value(self, kind):
        if kind == TAG_BYTE:
            return struct.unpack('>b', self.take(1))[0]
        if kind == TAG_SHORT:
            return struct.unpack('>h', self.take(2))[0]
        if kind == TAG_INT:
            return struct.unpack('>i', self.take(4))[0]
        if kind == TAG_LONG:
            return struct.unpack('>q', self.take(8))[0]
        if kind == TAG_FLOAT:
            return struct.unpack('>f', self.take(4))[0]
        if kind == TAG_DOUBLE:
            return struct.unpack('>d', self.take(8))[0]
        if kind == TAG_BYTE_ARRAY:
            return self.take(struct.unpack('>i', self.take(4))[0])
        if kind == TAG_STRING:
            return self.string()
        if kind == TAG_LIST:
            inner = self.u1()
            count = struct.unpack('>i', self.take(4))[0]
            return [self.value(inner) for _ in range(count)] if inner else []
        if kind == TAG_COMPOUND:
            out = {}
            while True:
                child = self.u1()
                if child == TAG_END:
                    return out
                #  The name MUST be read before the value. `out[self.string()] = self.value(child)` reads
                #  them in the opposite order -- Python evaluates the right-hand side of a subscript
                #  assignment first -- which parses the value from where the name starts and produces a
                #  tree that looks plausible and is entirely wrong.
                name = self.string()
                out[name] = self.value(child)
        if kind == TAG_INT_ARRAY:
            return [struct.unpack('>i', self.take(4))[0] for _ in range(struct.unpack('>i', self.take(4))[0])]
        if kind == TAG_LONG_ARRAY:
            return [struct.unpack('>q', self.take(8))[0] for _ in range(struct.unpack('>i', self.take(4))[0])]
        raise ValueError('unknown tag %d' % kind)


def parse(payload, at=0):
    """Network NBT: a bare value with no root name (1.20.5+)."""
    r = Reader(payload, at)
    kind = r.u1()
    if kind == TAG_STRING:
        return r.string(), r.i
    if kind != TAG_COMPOUND:
        return None, r.i
    return r.value(TAG_COMPOUND), r.i


def _colour_of(node, inherited):
    if not isinstance(node, dict):
        return inherited
    colour = node.get('color')
    if not colour:
        return inherited
    if colour.startswith('#'):
        return colour
    return LEGACY.get(colour, inherited)


def flatten(node, inherited='f', out=None):
    """Component tree -> legacy §-coded string, the way it will be drawn."""
    if out is None:
        out = []
    if isinstance(node, str):
        out.append('§' + inherited + node)
        return out
    if isinstance(node, list):
        for child in node:
            flatten(child, inherited, out)
        return out
    if not isinstance(node, dict):
        return out
    colour = _colour_of(node, inherited)
    style = ''
    for key, code in (('bold', 'l'), ('italic', 'o'), ('underlined', 'n'), ('strikethrough', 'm'), ('obfuscated', 'k')):
        value = node.get(key)
        if value in (1, True, 'true'):
            style += '§' + code
    text = node.get('text')
    if text is None and 'translate' in node:
        text = '{%s}' % node['translate']
    if text:
        out.append('§' + (colour if colour.startswith('#') else colour) + style + text)
    for child in node.get('extra', []) or []:
        flatten(child, colour, out)
    return out


def render(payload, at=0):
    node, _ = parse(payload, at)
    if node is None:
        return ''
    if isinstance(node, str):
        return node
    return ''.join(flatten(node))


def plain(legacy):
    """The same line with the colours removed, for width and wording checks."""
    out, i = [], 0
    while i < len(legacy):
        if legacy[i] == '§':
            if i + 1 < len(legacy) and legacy[i + 1] == '#':
                i += 8
            else:
                i += 2
            continue
        out.append(legacy[i])
        i += 1
    return ''.join(out)


def ansi(legacy):
    """Approximate the rendered colours in a terminal, so a capture can be eyeballed."""
    codes = {'0': '30', '1': '34', '2': '32', '3': '36', '4': '31', '5': '35', '6': '33', '7': '37',
             '8': '90', '9': '94', 'a': '92', 'b': '96', 'c': '91', 'd': '95', 'e': '93', 'f': '97'}
    out, i = [], 0
    while i < len(legacy):
        if legacy[i] == '§' and i + 1 < len(legacy):
            code = legacy[i + 1]
            if code in codes:
                out.append('\x1b[0;%sm' % codes[code])
            elif code == 'l':
                out.append('\x1b[1m')
            i += 2
            continue
        out.append(legacy[i])
        i += 1
    return ''.join(out) + '\x1b[0m'
