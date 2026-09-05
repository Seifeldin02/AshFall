# -*- coding: utf-8 -*-
"""The safety rail. Everything else in the harness goes through here first.

This package drives a REAL server with a REAL player: it spends money, starts paid encounters, kills the
account it controls, and deletes worlds. Pointed at the wrong host that is not a test, it is an incident.

So the endpoint is never defaulted, never guessed, and never inherited from a previous run. It has to be
configured explicitly, and the production endpoint is refused outright even if somebody configures it.
"""
import configparser
import os

#  Ashfall production. Hard-refused, by port and by the ports its RCON listens on. This list is not
#  configurable on purpose -- a config file that can turn the safety off is not a safety.
FORBIDDEN_PORTS = {25565, 25575}
FORBIDDEN_HOSTS = {'ashfall.gg', 'play.ashfall.gg'}

CONFIG_ENV = 'ASHFALL_HARNESS_CONFIG'
DEFAULT_CONFIG = 'harness.ini'


class RefusedError(RuntimeError):
    """Raised when the harness is pointed somewhere it must not go."""


def _refuse(reason):
    raise RefusedError(
        'Refusing to run: ' + reason + '\n'
        'The harness spends money, starts paid encounters and deletes worlds. It only ever runs against a\n'
        'staging server you have configured explicitly. See testing/harness/README.md.')


class Endpoint(object):
    def __init__(self, host, port, rcon_port, rcon_password, account, password):
        self.host = host
        self.port = int(port)
        self.rcon_port = int(rcon_port)
        self.rcon_password = rcon_password
        self.account = account
        self.password = password
        self.check()

    def check(self):
        if self.port in FORBIDDEN_PORTS or self.rcon_port in FORBIDDEN_PORTS:
            _refuse('port %d/%d is the production endpoint.' % (self.port, self.rcon_port))
        if self.host.lower() in FORBIDDEN_HOSTS:
            _refuse('host %r is production.' % self.host)
        if not self.rcon_password:
            _refuse('no RCON password configured.')
        if not self.account:
            _refuse('no test account name configured.')
        if self.account.lower() in ('macoct', 'asserto', 'tpkiid'):
            _refuse('%r is a real administrator account, not a test account.' % self.account)

    def __repr__(self):
        return 'Endpoint(%s:%d, rcon %d, as %s)' % (self.host, self.port, self.rcon_port, self.account)


def load(path=None):
    """Reads the endpoint from harness.ini. There is deliberately no default host or port.

    The file holds an RCON password and a test-account password, so it is git-ignored and only ever
    contains STAGING credentials. Never commit a filled-in copy; harness.ini.example is the template."""
    path = path or os.environ.get(CONFIG_ENV) or os.path.join(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))), DEFAULT_CONFIG)
    if not os.path.isfile(path):
        _refuse('no configuration at %s. Copy harness.ini.example and fill in your staging endpoint.' % path)
    parser = configparser.ConfigParser()
    parser.read(path, encoding='utf-8')
    if not parser.has_section('staging'):
        _refuse('%s has no [staging] section.' % path)
    section = parser['staging']
    for key in ('host', 'port', 'rcon_port', 'rcon_password', 'account', 'account_password'):
        if not section.get(key, '').strip():
            _refuse('%s is missing %s.' % (path, key))
    return Endpoint(section['host'].strip(), section['port'], section['rcon_port'],
                    section['rcon_password'].strip(), section['account'].strip(),
                    section['account_password'].strip())
