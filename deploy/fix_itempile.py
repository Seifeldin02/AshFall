# -*- coding: utf-8 -*-
"""Trim the item pile out of nether entity chunk (436, 428), keeping everything that is not farm output.

25,078 item entities in one chunk -- 24,216 gunpowder, 649 TNT, 190 magma cream -- from a creeper farm
whose drops never despawned. Items in an unloaded chunk do not tick, so they never aged out; they simply
accumulated every time somebody visited. Once the chunk loads, every one of them runs
ItemEntity.mergeWithNeighbours -> Level.getEntitiesOfClass every tick, which is O(n^2) and hangs the server
thread. Paper's watchdog then kills the server, restart-on-crash relaunches it, and it hangs again.

This cannot be done with /kill on a live server: merely force-loading the chunk stalled it for 61 seconds
and the watchdog killed it mid-command. So the fix is offline, on the chunk file.

It is done with a full NBT round trip rather than a byte hack, because the chunk also contains things worth
keeping -- a nether star, ancient debris, echo shards, enchanted books, and eight items carrying custom
names, lore or SMPCore PDC tags, which are somebody's actual gear. Only bulk farm output is dropped, and
the original file is backed up first.

    python fix_itempile.py            report what is there, change nothing
    python fix_itempile.py --write    write the trimmed chunk
"""
import collections
import io
import os
import struct
import sys
import zlib

CHUNK = r'C:\MinecraftServer\world\dimensions\minecraft\the_nether\entities\c.436.428.mcc'
BACKUP = r'C:\MinecraftServer\backups\20260922-itempile-c.436.428.mcc'

#  Bulk farm output. Everything else in the chunk is kept, whatever it is.
JUNK = {'minecraft:gunpowder', 'minecraft:tnt', 'minecraft:magma_cream'}

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class Reader(object):
    def __init__(self, data):
        self.d = data
        self.p = 0

    def take(self, n):
        out = self.d[self.p:self.p + n]
        self.p += n
        return out

    def u1(self):
        return struct.unpack('>B', self.take(1))[0]

    def i2(self):
        return struct.unpack('>h', self.take(2))[0]

    def u2(self):
        return struct.unpack('>H', self.take(2))[0]

    def i4(self):
        return struct.unpack('>i', self.take(4))[0]

    def string(self):
        return self.take(self.u2())

    def value(self, kind):
        if kind == BYTE:
            return struct.unpack('>b', self.take(1))[0]
        if kind == SHORT:
            return self.i2()
        if kind == INT:
            return self.i4()
        if kind == LONG:
            return struct.unpack('>q', self.take(8))[0]
        if kind == FLOAT:
            return struct.unpack('>f', self.take(4))[0]
        if kind == DOUBLE:
            return struct.unpack('>d', self.take(8))[0]
        if kind == BYTE_ARRAY:
            return self.take(self.i4())
        if kind == STRING:
            return self.string()
        if kind == LIST:
            inner = self.u1()
            count = self.i4()
            return (inner, [self.value(inner) for _ in range(count)])
        if kind == COMPOUND:
            out = []
            while True:
                child = self.u1()
                if child == END:
                    return out
                name = self.string()
                out.append((name, child, self.value(child)))
        if kind == INT_ARRAY:
            n = self.i4()
            return list(struct.unpack('>%di' % n, self.take(4 * n)))
        if kind == LONG_ARRAY:
            n = self.i4()
            return list(struct.unpack('>%dq' % n, self.take(8 * n)))
        raise ValueError('unknown tag %d at %d' % (kind, self.p))


class Writer(object):
    def __init__(self):
        self.b = io.BytesIO()

    def u1(self, v):
        self.b.write(struct.pack('>B', v))

    def u2(self, v):
        self.b.write(struct.pack('>H', v))

    def i4(self, v):
        self.b.write(struct.pack('>i', v))

    def string(self, raw):
        self.u2(len(raw))
        self.b.write(raw)

    def value(self, kind, v):
        if kind == BYTE:
            self.b.write(struct.pack('>b', v))
        elif kind == SHORT:
            self.b.write(struct.pack('>h', v))
        elif kind == INT:
            self.i4(v)
        elif kind == LONG:
            self.b.write(struct.pack('>q', v))
        elif kind == FLOAT:
            self.b.write(struct.pack('>f', v))
        elif kind == DOUBLE:
            self.b.write(struct.pack('>d', v))
        elif kind == BYTE_ARRAY:
            self.i4(len(v))
            self.b.write(v)
        elif kind == STRING:
            self.string(v)
        elif kind == LIST:
            inner, items = v
            self.u1(inner)
            self.i4(len(items))
            for item in items:
                self.value(inner, item)
        elif kind == COMPOUND:
            for name, child, val in v:
                self.u1(child)
                self.string(name)
                self.value(child, val)
            self.u1(END)
        elif kind == INT_ARRAY:
            self.i4(len(v))
            self.b.write(struct.pack('>%di' % len(v), *v))
        elif kind == LONG_ARRAY:
            self.i4(len(v))
            self.b.write(struct.pack('>%dq' % len(v), *v))
        else:
            raise ValueError('unknown tag %d' % kind)


def get(compound, key):
    for name, kind, val in compound:
        if name == key:
            return kind, val
    return None, None


def describe(entity):
    """(entity id, item id or None, whether it carries anything worth keeping)."""
    _, eid = get(entity, b'id')
    eid = eid.decode() if eid else '?'
    _, item = get(entity, b'Item')
    if item is None:
        return eid, None, True
    _, iid = get(item, b'id')
    iid = iid.decode() if iid else '?'
    _, components = get(item, b'components')
    special = False
    if components:
        for name, _, _ in components:
            if name in (b'minecraft:custom_name', b'minecraft:lore', b'minecraft:custom_data',
                        b'minecraft:enchantments', b'minecraft:stored_enchantments'):
                special = True
    return eid, iid, special


def main():
    raw = open(CHUNK, 'rb').read()
    data = zlib.decompress(raw)
    reader = Reader(data)
    root_kind = reader.u1()
    root_name = reader.string()
    root = reader.value(root_kind)
    print('chunk parsed: %d compressed -> %d raw bytes' % (len(raw), len(data)))

    kind, entities = get(root, b'Entities')
    if kind != LIST:
        raise SystemExit('no Entities list (kind=%r)' % kind)
    inner, rows = entities
    print('entities in chunk: %d' % len(rows))

    keep, drop = [], collections.Counter()
    kept = collections.Counter()
    for entity in rows:
        eid, iid, special = describe(entity)
        if eid == 'minecraft:item' and iid in JUNK and not special:
            drop[iid] += 1
        else:
            keep.append(entity)
            kept[iid or eid] += 1

    print('\nDROPPING (bulk farm output, no custom name/lore/enchant/PDC):')
    for k, v in drop.most_common():
        print('   %-32s %d' % (k, v))
    print('   %-32s %d' % ('TOTAL DROPPED', sum(drop.values())))
    print('\nKEEPING:')
    for k, v in kept.most_common(30):
        print('   %-32s %d' % (k, v))
    print('   %-32s %d' % ('TOTAL KEPT', len(keep)))

    if '--write' not in sys.argv:
        print('\nDRY RUN -- pass --write to apply. Original is backed up at:\n   %s' % BACKUP)
        return 0

    if not os.path.exists(BACKUP):
        raise SystemExit('refusing to write: backup %s is missing' % BACKUP)

    #  Replace the Entities list in place, preserving every other tag in the chunk exactly.
    for index, (name, k, v) in enumerate(root):
        if name == b'Entities':
            root[index] = (name, LIST, (inner, keep))
            break

    writer = Writer()
    writer.u1(root_kind)
    writer.string(root_name)
    writer.value(root_kind, root)
    out = zlib.compress(writer.b.getvalue())

    #  Re-parse what we are about to write, and refuse if it does not come back the same shape.
    check = Reader(zlib.decompress(out))
    check.u1(); check.string()
    rechecked = check.value(root_kind)
    _, back = get(rechecked, b'Entities')
    if back is None or len(back[1]) != len(keep):
        raise SystemExit('round trip failed verification; nothing written')

    open(CHUNK, 'wb').write(out)
    print('\nwrote %s: %d -> %d bytes, %d entities -> %d'
          % (CHUNK, len(raw), len(out), len(rows), len(keep)))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
