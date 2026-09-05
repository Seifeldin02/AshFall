# -*- coding: utf-8 -*-
"""A headless Minecraft client, so tests can be run by something that is actually a player.

Synthetic tests could not see the bugs that mattered. A charge that never moved passed its own test suite
because the test drove it inside a single tick, where nothing can move; /colosseum leave was impossible to
confirm for everybody except administrators, who skip the guard that broke it. Both needed a real client
connected to a real server.

It joins, answers keep-alives and the anticheat's transaction pings, and then does nothing on its own.
Everything else is driven at it -- commands through the real command path, real GUI clicks, real deaths --
and it records what it is shown: chat as the player sees it, and entity positions at tick resolution.

Protocol 767 (Minecraft 1.21.1), offline mode. The endpoint comes from harness.ini via guard.py, which
refuses production before a socket is opened.

  python -m ashfall_harness.client <name> [seconds]
"""
import io
import os
import socket
import struct
import sys
import time
import zlib

from . import guard
from . import nbt as nbtchat

PROTOCOL = 767


def varint(value):
    out = b''
    while True:
        byte = value & 0x7F
        value >>= 7
        out += struct.pack('B', byte | (0x80 if value else 0))
        if not value:
            return out


def readable(payload):
    """Printable runs out of an NBT text component -- enough to see what the player was told."""
    out, run = [], []
    for byte in payload:
        if 32 <= byte < 127:
            run.append(chr(byte))
        else:
            if len(run) >= 3:
                out.append(''.join(run))
            run = []
    if len(run) >= 3:
        out.append(''.join(run))
    return ' | '.join(out)


def string(text):
    raw = text.encode('utf-8')
    return varint(len(raw)) + raw


class Bot(object):
    def __init__(self, name, seconds, endpoint=None):
        self.endpoint = endpoint or guard.load()
        self.host, self.port = self.endpoint.host, self.endpoint.port
        self.name = name
        self.deadline = time.time() + seconds
        self.sock = socket.create_connection((self.host, self.port), timeout=20)
        self.sock.settimeout(1.0)
        self.threshold = -1
        self.buffer = b''
        self.state = 'login'
        self.log = io.open('bot_%s.log' % name, 'w', encoding='utf-8')
        self.seen = {}
        self.sizes = {}
        self.chat_ids = set()
        self.captured = []
        self.chat_decode_warned = False
        self.window = 0
        self.last_screen = b''
        self.sidebar = {}
        self.sidebar_events = []
        self.state_id = 0
        self.entities = {}
        self.trace = []
        self.tracing = False
        self.entity_id = None
        self.status('connecting')

    # ---------------------------------------------------------------- plumbing
    def status(self, text):
        with io.open('bot_%s.status' % self.name, 'w', encoding='utf-8') as f:
            f.write('%s %s\n' % (time.strftime('%H:%M:%S'), text))

    def note(self, text):
        self.log.write('%s %s\n' % (time.strftime('%H:%M:%S'), text))
        self.log.flush()

    def send(self, packet_id, payload=b''):
        body = varint(packet_id) + payload
        if self.threshold < 0:
            frame = varint(len(body)) + body
        elif len(body) >= self.threshold:
            squeezed = zlib.compress(body)
            inner = varint(len(body)) + squeezed
            frame = varint(len(inner)) + inner
        else:
            inner = varint(0) + body
            frame = varint(len(inner)) + inner
        self.sock.sendall(frame)

    def _pull(self):
        try:
            chunk = self.sock.recv(65536)
        except socket.timeout:
            return True
        if not chunk:
            return False
        self.buffer += chunk
        return True

    @staticmethod
    def _read_varint(data, offset=0):
        result = shift = 0
        while True:
            if offset >= len(data):
                return None, offset
            byte = data[offset]
            offset += 1
            result |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return result, offset
            shift += 7
            if shift > 35:
                raise ValueError('varint too long')

    def packets(self):
        """Yields (packet_id, payload) for every complete frame currently buffered."""
        while True:
            length, head = self._read_varint(self.buffer)
            if length is None or len(self.buffer) - head < length:
                return
            frame = self.buffer[head:head + length]
            self.buffer = self.buffer[head + length:]
            if self.threshold >= 0:
                size, at = self._read_varint(frame)
                frame = zlib.decompress(frame[at:]) if size else frame[at:]
            packet_id, at = self._read_varint(frame)
            yield packet_id, frame[at:]

    # ---------------------------------------------------------------- the join
    def join(self):
        self.send(0x00, varint(PROTOCOL) + string(self.host) + struct.pack('>H', self.port) + varint(2))
        self.send(0x00, string(self.name) + b'\x00' * 16)
        self.status('login sent')

    def handle_login(self, packet_id, payload):
        if packet_id == 0x03:                                   # set compression
            self.threshold, _ = self._read_varint(payload)
            self.note('compression threshold %d' % self.threshold)
        elif packet_id == 0x02:                                 # login success
            self.note('login success')
            self.send(0x03)                                     # login acknowledged
            self.state = 'configuration'
            self.send(0x00, string('en_us') + struct.pack('b', 8) + varint(0)
                      + b'\x01' + struct.pack('B', 0x7F) + varint(1) + b'\x00' + b'\x01')
            self.status('configuring')
        elif packet_id == 0x00:                                 # disconnect
            self.note('LOGIN DISCONNECT ' + repr(payload[:400]))
            self.status('disconnected in login')
            raise SystemExit(1)
        elif packet_id == 0x01:
            self.note('encryption requested -- server is not in offline mode')
            raise SystemExit(1)

    def handle_configuration(self, packet_id, payload):
        if packet_id == 0x0E:                                   # clientbound known packs
            self.send(0x07, varint(0))
        elif packet_id == 0x03:                                 # finish configuration
            self.send(0x03)
            self.state = 'play'
            self.note('entered play')
            self.status('playing')
        elif packet_id == 0x04:                                 # keep alive
            self.send(0x04, payload[:8])
        elif packet_id == 0x05:                                 # ping
            self.send(0x05, payload[:4])
        elif packet_id == 0x02:                                 # disconnect
            self.note('CONFIG DISCONNECT ' + repr(payload[:400]))
            self.status('disconnected in configuration')
            raise SystemExit(1)
        elif packet_id == 0x09:                                 # add resource pack
            uuid, at = payload[:16], 16
            self.send(0x06, uuid + varint(3))                   # "declined"

    def handle_play(self, packet_id, payload):
        self.seen[packet_id] = self.seen.get(packet_id, 0) + 1
        self.sizes.setdefault(packet_id, set()).add(len(payload))
        if packet_id == 0x26 and len(payload) == 8:             # keep alive
            self.send(0x18, payload)
        elif packet_id == 0x35 and len(payload) == 4:           # ping -- the anticheat's transaction
            self.send(0x27, payload)
        elif packet_id == 0x40:                                 # synchronize player position
            teleport, _ = self._read_varint(payload, 33)
            self.send(0x00, varint(teleport))
            self.note('teleport confirmed %s' % teleport)
        elif packet_id == 0x3C:                                 # combat death -- respawn, or nothing works
            self.send(0x09, varint(0))
            self.note('died; respawn requested')
        elif packet_id == 0x1D:                                 # disconnect
            self.note('PLAY DISCONNECT ' + repr(payload[:400]))
            self.status('kicked')
            raise SystemExit(1)
        elif packet_id == 0x01 and len(payload) >= 46:          # add entity
            eid, at = self._read_varint(payload)
            kind, at = self._read_varint(payload, at + 16)
            x, y, z = struct.unpack('>ddd', payload[at:at + 24])
            self.entities[eid] = [kind, x, y, z]
            self.record(eid)
        elif packet_id == 0x2E and len(payload) == 9:           # move entity pos (relative)
            eid, at = self._read_varint(payload)
            dx, dy, dz = struct.unpack('>hhh', payload[at:at + 6])
            row = self.entities.get(eid)
            if row:
                row[1] += dx / 4096.0
                row[2] += dy / 4096.0
                row[3] += dz / 4096.0
                self.record(eid)
        elif packet_id == 0x70 and len(payload) >= 27:          # teleport entity (absolute)
            eid, at = self._read_varint(payload)
            x, y, z = struct.unpack('>ddd', payload[at:at + 24])
            row = self.entities.setdefault(eid, [-1, x, y, z])
            row[1], row[2], row[3] = x, y, z
            self.record(eid)
        elif packet_id == 0x33:                                 # open screen
            window, at = self._read_varint(payload)
            self.window = window
            self.note('screen opened, window %d: %s' % (window, readable(payload[at:])[:140]))
        elif packet_id == 0x13:                                 # container set content
            window, at = self._read_varint(payload)
            state, _ = self._read_varint(payload, at)
            self.window, self.state_id = window, state
            #  Kept so a run can write down what the server actually put on the screen. The icon names and
            #  their colours arrive as NBT text components, so the printable runs out of the raw payload
            #  are the real thing the client was handed -- not our own idea of what we sent.
            self.last_screen = payload
        elif packet_id == 0x15:                                 # container set slot
            window, at = self._read_varint(payload)
            state, _ = self._read_varint(payload, at)
            if window == self.window:
                self.state_id = state
        elif packet_id == 0x12:                                 # container close
            self.window = 0
            self.note('screen closed by the server')
        elif self._sidebar(payload):
            pass
        elif packet_id in self.chat_ids:
            try:
                line = nbtchat.render(payload)
            except Exception as error:
                if not self.chat_decode_warned:
                    self.chat_decode_warned = True
                    self.note('chat decode failed (%s); first payload hex: %s' % (error, payload.hex()))
                line = readable(payload)
            self.note('CHAT ' + line)
            self.captured.append(line)
        elif b'BOTCHK' in payload:
            self.chat_ids.add(packet_id)
            self.note('chat packet identified as 0x%02x' % packet_id)

    #  ---------------------------------------------------------------- the sidebar
    #
    #  Read the way the client reads it, not the way the server believes it sent it.
    #
    #  ClientboundSetScorePacket is  String owner, String objective, VarInt value, ...  and
    #  ClientboundResetScorePacket is  String owner, Optional<String> objective.  Both begin with the row's
    #  own text and name the objective immediately after it, so matching on the objective NAME identifies
    #  them without a packet id -- which is the same reason the chat id is discovered rather than written
    #  down: one protocol bump and a hard-coded id silently stops seeing anything, and a test that sees
    #  nothing passes.
    OBJECTIVE = 'smpui'

    def _string_at(self, payload, at):
        #  _read_varint returns (None, offset) when it runs off the end, so the guard has to be an identity
        #  check before it is a range check -- `None < 0` is a TypeError, and this runs on every packet.
        length, at = self._read_varint(payload, at)
        if length is None or length < 0 or length > 512 or at + length > len(payload):
            return None, at
        try:
            return payload[at:at + length].decode('utf-8'), at + length
        except UnicodeDecodeError:
            return None, at

    def _sidebar(self, payload):
        """True when this payload was a sidebar row arriving or leaving. Records it either way."""
        try:
            return self._sidebar_unguarded(payload)
        except (ValueError, IndexError, struct.error):
            #  This speculatively parses EVERY packet, chunk data included. A malformed read here means
            #  "not a score packet", never a dead client.
            return False

    def _sidebar_unguarded(self, payload):
        owner, at = self._string_at(payload, 0)
        if owner is None:
            return False
        objective, after = self._string_at(payload, at)
        if objective == self.OBJECTIVE:
            value, _ = self._read_varint(payload, after)
            self.sidebar[owner] = value
            self.sidebar_events.append(('set', owner, value))
            return True
        #  Reset carries the objective behind an Optional, so it sits one byte further along.
        if at < len(payload) and payload[at] == 1:
            objective, _ = self._string_at(payload, at + 1)
            if objective == self.OBJECTIVE:
                self.sidebar.pop(owner, None)
                self.sidebar_events.append(('reset', owner, 0))
                return True
        return False

    def sidebar_rows(self):
        """The panel as it currently stands, top row first -- highest score is drawn at the top."""
        return [row for row, _ in sorted(self.sidebar.items(), key=lambda kv: -kv[1])]

    def record(self, eid):
        """Ground truth at the resolution a real client sees it: every position packet, as it arrives.

        This is the only honest way to ask "did that thing actually move" -- polling the server over RCON
        samples at whatever rate the round trip allows, which is far too coarse to see a two-second charge.
        Bounded so a long session cannot grow without limit."""
        if not self.tracing or len(self.trace) >= 60000:
            return
        kind, x, y, z = self.entities[eid]
        self.trace.append((round(time.time(), 3), eid, kind, round(x, 3), round(y, 3), round(z, 3)))

    # ---------------------------------------------------------------- driving it
    def drive(self):
        """One line per instruction, dropped into bot_<name>.cmd by whatever is running the test.

             cmd:<command without the slash>     run it as this player, through the real command path
             pos:<x> <y> <z>                     claim a position, so movement is a thing that happened
             quit                                leave cleanly
        """
        path = 'bot_%s.cmd' % self.name
        taken = path + '.taken'
        if not os.path.exists(path):
            return
        try:
            #  Claim the file before reading it. Reading and then deleting loses anything appended in
            #  between, which shows up as a command that was never sent and an hour spent blaming the
            #  server for it.
            os.replace(path, taken)
            lines = io.open(taken, encoding='utf-8').read().splitlines()
            os.remove(taken)
        except OSError:
            return
        for line in lines:
            line = line.strip()
            if not line:
                continue
            if line == 'quit':
                raise SystemExit(0)
            if line.startswith('cmd:'):
                self.send(0x04, string(line[4:]))
                self.note('sent command ' + line[4:])
            elif line == 'respawn':
                self.send(0x09, varint(0))
                self.note('respawn requested')
            elif line == 'chat:start':
                self.captured = []
                self.note('capturing chat')
            elif line == 'chat:dump':
                with io.open('chat_%s.txt' % self.name, 'w', encoding='utf-8') as f:
                    for row in self.captured:
                        f.write(row)
                        f.write(u'\n')
                self.note('wrote %d chat lines' % len(self.captured))
            elif line == 'trace:start':
                self.trace = []
                self.entities = {}
                self.tracing = True
                self.note('tracing entity positions')
            elif line == 'trace:dump':
                self.tracing = False
                with io.open('trace_%s.csv' % self.name, 'w', encoding='utf-8') as f:
                    for row in self.trace:
                        f.write(','.join(str(v) for v in row) + '\n')
                self.note('wrote %d position records' % len(self.trace))
            elif line.startswith('click:'):
                slot = int(line[6:])
                self.send(0x0E, varint(self.window) + varint(self.state_id) + struct.pack('>hb', slot, 0)
                          + varint(0) + varint(0) + varint(0))
                self.note('clicked slot %d in window %d (state %d)' % (slot, self.window, self.state_id))
            elif line.startswith('screen:dump'):
                name = line.split(':', 2)[2] if line.count(':') > 1 else 'screen'
                with io.open('ui_%s.txt' % name, 'w', encoding='utf-8') as f:
                    f.write(readable(self.last_screen))
                    f.write(chr(10))
                self.note('wrote %d bytes of screen content to ui_%s.txt' % (len(self.last_screen), name))
            elif line.startswith('sidebar:dump'):
                name = line.split(':', 2)[2] if line.count(':') > 1 else 'sidebar'
                with io.open('ui_%s.txt' % name, 'w', encoding='utf-8') as f:
                    for row in self.sidebar_rows():
                        f.write(row)
                        f.write(chr(10))
                self.note('wrote %d sidebar rows to ui_%s.txt' % (len(self.sidebar), name))
            elif line == 'sidebar:reset':
                self.sidebar_events = []
                self.note('sidebar packet counter reset')
            elif line.startswith('sidebar:count'):
                name = line.split(':', 2)[2] if line.count(':') > 1 else 'sidebarcount'
                with io.open('ui_%s.txt' % name, 'w', encoding='utf-8') as f:
                    f.write(str(len(self.sidebar_events)) + chr(10))
                self.note('sidebar row packets since reset: %d' % len(self.sidebar_events))
            elif line.startswith('interact:near:'):
                #  RIGHT-CLICKING AN NPC, WHICH IS THE ONLY WAY INTO SOME SCREENS.
                #
                #  The Bank front page opens from the Central Banker and from nothing else -- no command
                #  reaches it -- so without this packet a whole screen stays uncapturable and the coverage
                #  note saying so never goes away. ServerboundInteractPacket is entity id, type (0 =
                #  interact), hand, then the secondary-action flag.
                #
                #  The target is chosen by position rather than by "the one that just appeared": mobs spawn
                #  while a test runs, and interacting with whatever arrived most recently is how a test
                #  ends up right-clicking a cow and reporting the bank as broken.
                target = [float(v) for v in line.split(':', 2)[2].split(',')]
                best, best_gap = None, 9e9
                for eid, (kind, x, y, z) in self.entities.items():
                    gap = (x - target[0]) ** 2 + (y - target[1]) ** 2 + (z - target[2]) ** 2
                    if gap < best_gap:
                        best, best_gap = eid, gap
                if best is None:
                    self.note('no tracked entity to interact with')
                else:
                    self.send(0x16, varint(best) + varint(0) + varint(0) + bytes([0]))
                    self.note('interacted with entity %d, %.1f blocks away' % (best, best_gap ** 0.5))
            elif line == 'close':
                self.send(0x0F, varint(self.window))
                self.note('closed window %d' % self.window)
            elif line.startswith('pos:'):
                x, y, z = (float(v) for v in line[4:].split())
                self.send(0x1A, struct.pack('>ddd', x, y, z) + bytes([1]))
                self.note('moved to %s %s %s' % (x, y, z))

    def run(self):
        self.join()
        last = time.time()
        while time.time() < self.deadline:
            if not self._pull():
                self.note('connection closed by server')
                self.status('closed')
                break
            for packet_id, payload in self.packets():
                if self.state == 'login':
                    self.handle_login(packet_id, payload)
                elif self.state == 'configuration':
                    self.handle_configuration(packet_id, payload)
                else:
                    self.handle_play(packet_id, payload)
            if self.state == 'play':
                self.drive()
                if time.time() - last > 5:
                    last = time.time()
                    self.status('playing (%d packet kinds seen)' % len(self.seen))
        self.note('ids seen in play:')
        for k in sorted(self.seen):
            self.note('   0x%02x  x%-6d lengths=%s' % (k, self.seen[k], sorted(self.sizes.get(k, ()))[:6]))
        self.status('finished')
        try:
            self.sock.close()
        except OSError:
            pass


if __name__ == '__main__':
    Bot(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 120).run()
