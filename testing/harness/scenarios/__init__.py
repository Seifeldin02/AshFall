# -*- coding: utf-8 -*-
"""The scenarios worth keeping.

Every one of these exists because it caught something, or because something it covers broke once and must
not break silently again. They run against a live staging server through a real client; none of them can
be satisfied by calling a helper method.
"""
from . import (charge, colosseum_leave, gui_confirm, voidworld_entry, inventory_conservation,
               menu_navigation, shop_navigation, sidebar, stash_recovery, stash_crash)

ALL = {
    'charge': charge,
    'colosseum-leave': colosseum_leave,
    'gui-confirm': gui_confirm,
    'voidworld-entry': voidworld_entry,
    'inventory': inventory_conservation,
    'menu-navigation': menu_navigation,
    'shop-navigation': shop_navigation,
    'sidebar': sidebar,
    'stash': stash_recovery,
    'stash-crash': stash_crash,
}
