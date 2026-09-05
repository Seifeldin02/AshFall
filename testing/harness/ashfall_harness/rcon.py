# -*- coding: utf-8 -*-
"""RCON, but only ever to the endpoint the guard approved.

There is deliberately no default host, port or password here. The endpoint comes from harness.ini, which is
git-ignored, and guard.py refuses the production ports before a socket is ever opened.
"""
import socket
import struct
import time

from . import guard

_endpoint = None


def endpoint():
    global _endpoint
    if _endpoint is None:
        _endpoint = guard.load()
    return _endpoint


def use(configured):
    """Point the harness at an already-validated endpoint. The runner does this once at startup."""
    global _endpoint
    configured.check()
    _endpoint = configured


def send(commands, settle=0.35, timeout=25):
    """Runs commands as console and returns [(command, reply), ...]."""
    target = endpoint()
    s = socket.create_connection((target.host, target.rcon_port), timeout=timeout)
    s.settimeout(timeout)

    def pkt(rid, typ, body):
        data = struct.pack('<ii', rid, typ) + body.encode('utf-8') + b'\x00\x00'
        s.sendall(struct.pack('<i', len(data)) + data)

    def read():
        raw = b''
        while len(raw) < 4:
            chunk = s.recv(4 - len(raw))
            if not chunk:
                return None, None, ''
            raw += chunk
        size = struct.unpack('<i', raw)[0]
        body = b''
        while len(body) < size:
            chunk = s.recv(size - len(body))
            if not chunk:
                break
            body += chunk
        rid, typ = struct.unpack('<ii', body[:8])
        return rid, typ, body[8:-2].decode('utf-8', 'replace')

    pkt(1, 3, target.rcon_password)
    rid, _, _ = read()
    if rid == -1:
        s.close()
        raise RuntimeError('RCON authentication failed for %r' % (target,))

    out = []
    try:
        for index, command in enumerate(commands):
            pkt(100 + index, 2, command)
            time.sleep(settle)
            try:
                _, _, text = read()
            except socket.timeout:
                text = '(timeout)'
            out.append((command, text))
    finally:
        try:
            s.close()
        except OSError:
            pass
    return out
