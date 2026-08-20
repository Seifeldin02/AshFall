package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

/** The discarded-item vault: an audit record of what genuinely leaves the world, and a recycling path that
 *  puts ordinary commodities back into shop stock instead of letting them evaporate.
 *
 *  THE VAULT IS NOT STORAGE. It is a ledger. Nothing can ever be withdrawn from it, the admin view is
 *  read-only, and no item is ever reconstructed from a record. That is deliberate: a vault you can take
 *  things out of is a second copy of every item that was destroyed, which is duplication with extra steps.
 *
 *  Recycling is not duplication either, for the same reason. The item entity is already gone by the time
 *  anything is credited -- both hooks confirm the removal before recording it -- and what gets credited is
 *  the SHOP's stock counter, not an item. The shop may now sell one more cobblestone because one more
 *  cobblestone left the world, which is exactly the flow a player selling it would have produced, minus the
 *  payment. Nobody gains an item.
 *
 *  What is eligible is deliberately narrow. A stack qualifies only if it is a stock-limited /shop commodity
 *  the shop actually buys AND it is byte-for-byte an ordinary stack of that material. That single
 *  isSimilar check against a plain stack is what keeps relics, bound and protected items, custom spawners,
 *  Industrial Hoppers, renamed, enchanted and damaged items out of ordinary stock -- every one of them
 *  carries data a plain stack does not, so none of them can ever be flattened into a commodity. Ineligible
 *  destructions are still recorded, so the audit stays complete; they simply credit nothing.
 *
 *  Nothing here scans the world. Both inputs are events fired by the item entity itself, and writes are
 *  accumulated in memory and flushed as one statement per batch rather than one per destroyed item. */
final class DiscardedVaultService implements Listener {

    /** Accumulated destructions that have not been written yet, keyed by material + reason + eligibility. */
    private record Key(String material, String enchants, String details, String reason, boolean recycled) {}
    private static final class Pending {
        private int amount;
        private long at;
        private String world = "";
        private int x, y, z;
    }

    private record VaultHolder(int page) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private final SMPCore plugin;
    private final Map<Key, Pending> pending = new LinkedHashMap<>();
    private BukkitTask flushTask;

    // ---- dynamic view state: all sort/search/filter runs in memory on a cached snapshot ----
    private static final int AGG_LIMIT = 20000;
    private enum Sort {
        MOST_DESTROYED("Most destroyed"), MOST_RECYCLED("Most recycled"), RECENT("Last destroyed"), NAME("Name A-Z");
        final String label; Sort(String l) { label = l; }
    }
    private static final String[] CATEGORIES = {"All", "Spawners", "Enchanted", "Gear", "Blocks", "Food", "Items"};
    private static final class Session { Sort sort = Sort.MOST_DESTROYED; String category = "All"; String search = ""; int page = 1; }
    /** One aggregated row, built once per cache refresh; the controls then sort/filter/page this list in
     *  memory and never touch the database again. */
    private record VaultEntry(String material, String enchants, String enchantsDisplay, String details, String category,
                              String display, String searchKey, long destroyed, long recycled,
                              String reason, String world, int x, int y, int z, long at) {}
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> awaitingSearch = ConcurrentHashMap.newKeySet();
    private volatile List<VaultEntry> cache;
    private volatile long cacheAt;
    private volatile boolean rebuilding;
    /** Server-wide + persistent (DB state key vault_show_coords): whether entry lore shows last-seen coords. */
    private boolean showCoords = true;

    DiscardedVaultService(SMPCore plugin) {
        this.plugin = plugin;
        long seconds = Math.max(5, plugin.getConfig().getLong("discarded-vault.flush-seconds", 15));
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, seconds * 20L, seconds * 20L);
    }

    void shutdown() {
        if (flushTask != null) flushTask.cancel();
        flush();
    }

    // ------------------------------------------------------------------ destruction hooks
    /** An item entity reaching the end of its life. This is the single biggest source of items leaving the
     *  world, and the event is definitive -- nothing else removes an entity through this path. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void despawn(ItemDespawnEvent event) {
        record(event.getEntity().getItemStack(), event.getLocation(), "DESPAWNED");
    }

    /** Fire, lava, explosions, cactus and the void. The item is only recorded if it is actually gone on the
     *  following tick: damage does not always destroy an entity, and guessing would put phantom entries in
     *  an audit whose only value is being accurate. Item merges never reach this path, so a stack that was
     *  absorbed into another one is never mistaken for a destroyed one. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) return;
        ItemStack stack = item.getItemStack().clone();
        Location at = item.getLocation().clone();
        String reason = switch (event.getCause()) {
            case LAVA, FIRE, FIRE_TICK, HOT_FLOOR -> "BURNED";
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> "EXPLOSION";
            case VOID -> "VOID";
            case CONTACT -> "DESTROYED";
            default -> event.getCause().name();
        };
        Bukkit.getScheduler().runTask(plugin, () -> { if (!item.isValid()) record(stack, at, reason); });
    }

    /** Goods handed over deliberately -- a Task Master delivery -- rather than destroyed.
     *
     *  Same ledger, same eligibility rules, same absence of duplication: the items left the player's
     *  inventory before this is called, and what lands here is a record plus, for ordinary shop
     *  commodities, stock the server can resell. Anything carrying custom data is logged and nothing more. */
    void deliver(ItemStack stack, Location at, String reason) { record(stack, at, reason); }

    /** A spawner that was genuinely destroyed rather than recovered.
     *
     *  Recorded with its mob type preserved -- SPAWNER_BLAZE, not a bare "spawner" -- because which type
     *  was lost is the whole point of the record. Never recycled into shop stock under any circumstances: a
     *  spawner is not an ordinary commodity, and turning destroyed ones into sellable stock would quietly
     *  mint them. One call per destroyed physical spawner, so a stack that loses one keeps the rest. */
    void deliverSpawner(org.bukkit.entity.EntityType type, int amount, Location at, String reason) {
        if (amount <= 0) return;
        /** A spawner with unreadable spawn data still LEFT THE WORLD, and the audit exists to record that.
         *  Dropping the entry because the type could not be read would quietly lose exactly the events an
         *  audit is for, so it is filed as UNKNOWN instead. */
        Pending row = pending.computeIfAbsent(new Key("SPAWNER_" + (type == null ? "UNKNOWN" : type.name()), "", "", reason, false), ignored -> new Pending());
        row.amount += amount;
        row.at = System.currentTimeMillis();
        if (at != null && at.getWorld() != null) {
            row.world = at.getWorld().getName();
            row.x = at.getBlockX(); row.y = at.getBlockY(); row.z = at.getBlockZ();
        }
        if (pending.size() >= 400) flush();
    }

    /** Explosions are a real destruction path for spawners. MONITOR and ignoreCancelled, so the block is
     *  genuinely going, and once per block in the list, so one spawner is one entry. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityExplode(org.bukkit.event.entity.EntityExplodeEvent event) { explodedBlocks(event.blockList()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockExplode(org.bukkit.event.block.BlockExplodeEvent event) { explodedBlocks(event.blockList()); }

    private void explodedBlocks(List<org.bukkit.block.Block> blocks) {
        boolean recordBlocks = plugin.getConfig().getBoolean("discarded-vault.record-destroyed-blocks", true);
        for (org.bukkit.block.Block block : blocks) {
            if (block.getType() == Material.SPAWNER && block.getState() instanceof org.bukkit.block.CreatureSpawner spawner)
                /** A merged/stacked spawner is ONE physical block standing in for N spawners -- record the real
                 *  stack size, or a 10x-merged farm shows up in the audit as a single spawner. */
                deliverSpawner(spawner.getSpawnedType(), Math.max(1, plugin.spawners().stackSize(spawner)), block.getLocation(), "EXPLOSION");
            else {
                /** The block row stays audit-only; the stock credit is decided by settleBlast() from what
                 *  actually failed to drop, so a block and its surviving drop can never both credit. */
                if (recordBlocks) deliverBlock(block.getType(), 1, block.getLocation(), "EXPLOSION");
                blastExpect(block);
            }
        }
    }

    /** A block destroyed by fire -- burnt trees, wool, carpet and the like. Fire never drops the block, so
     *  this is always a genuine, un-recoverable loss and a clean audit entry. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockBurn(org.bukkit.event.block.BlockBurnEvent event) {
        if (!plugin.getConfig().getBoolean("discarded-vault.record-destroyed-blocks", true)) return;
        /** Fire destroys a block WITHOUT ever dropping it, so the commodity is unambiguously gone and can
         *  be recycled exactly once with no risk of a duplicate: there is no item for anybody to collect. */
        deliverBlock(event.getBlock().getType(), 1, event.getBlock().getLocation(), "BURNED",
                recycleBlocks() && recyclableMaterial(event.getBlock().getType()));
    }

    /** Whether a destroyed block is worth an audit entry -- skip air, fluids, fire and technical blocks that
     *  have no meaningful item form. */
    private boolean recordableBlock(Material m) {
        if (m == null || m.isAir() || !m.isBlock()) return false;
        return switch (m) {
            case FIRE, SOUL_FIRE, WATER, LAVA, BUBBLE_COLUMN, NETHER_PORTAL, END_PORTAL, END_GATEWAY, MOVING_PISTON, PISTON_HEAD, SNOW -> false;
            default -> true;
        };
    }

    /** A solid block destroyed with no recoverable drop (burnt, or caught in a blast). AUDIT-ONLY -- never
     *  recycled into shop stock: an exploded block may ALSO have dropped as an item, so recycling it would mint
     *  a duplicate, and a burnt block is a destruction record rather than a lost tradeable good. Aggregated by
     *  material+reason, so even a large blast is only a handful of rows. */
    void deliverBlock(Material material, int amount, Location at, String reason) { deliverBlock(material, amount, at, reason, false); }
    void deliverBlock(Material material, int amount, Location at, String reason, boolean recycled) {
        if (amount <= 0 || !recordableBlock(material)) return;
        Pending row = pending.computeIfAbsent(new Key(material.name(), "", "", reason, recycled), ignored -> new Pending());
        row.amount += amount;
        row.at = System.currentTimeMillis();
        if (at != null && at.getWorld() != null) {
            row.world = at.getWorld().getName();
            row.x = at.getBlockX(); row.y = at.getBlockY(); row.z = at.getBlockZ();
        }
        if (pending.size() >= 400) flush();
    }

    private void record(ItemStack stack, Location at, String reason) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return;
        Key key = new Key(stack.getType().name(), enchantSignature(stack), describeItem(stack), reason, recyclable(stack));
        Pending row = pending.computeIfAbsent(key, ignored -> new Pending());
        row.amount += stack.getAmount();
        row.at = System.currentTimeMillis();
        if (at != null && at.getWorld() != null) {
            row.world = at.getWorld().getName();
            row.x = at.getBlockX(); row.y = at.getBlockY(); row.z = at.getBlockZ();
        }
        /** A burst big enough to matter is written immediately rather than waiting for the timer. */
        if (pending.size() >= 400) flush();
    }

    private boolean recycleBlocks() { return plugin.getConfig().getBoolean("discarded-vault.recycle-destroyed-blocks", true); }
    /** The plain item form of a destroyed block, judged by exactly the same rule as a destroyed item. */
    boolean recyclableMaterial(Material material) {
        if (material == null || material.isAir() || !material.isItem()) return false;
        return recyclable(new ItemStack(material));
    }

    /** ---- Explosion netting -------------------------------------------------------------------
     *  An exploded block may ALSO drop as an item, and that item is collectable, so crediting the block
     *  at blast time would mint stock the player still holds. Instead the whole tick is settled at once:
     *  every drop the destroyed blocks COULD have produced is counted as expected, every item that
     *  actually spawned inside the blast area is counted as dropped, and only the difference -- the part
     *  that genuinely vanished -- is recycled. Anything that did drop stays in the world and is recycled
     *  later, exactly once, when it despawns or burns. Aggregating per tick rather than per explosion is
     *  what makes overlapping blasts safe: both sides of the subtraction include every blast in the tick. */
    private final Map<Material,Integer> blastExpected = new HashMap<>();
    private final Map<Material,Integer> blastDropped = new HashMap<>();
    private boolean blastScheduled = false;
    private String blastWorld; private int bx1,by1,bz1,bx2,by2,bz2; private Location blastAt;

    private void blastExpect(org.bukkit.block.Block block) {
        for (ItemStack drop : block.getDrops()) {
            if (drop == null || drop.getType().isAir()) continue;
            if (!recyclableMaterial(drop.getType())) continue;
            blastExpected.merge(drop.getType(), drop.getAmount(), Integer::sum);
        }
        Location at = block.getLocation();
        if (blastWorld == null || !blastWorld.equals(at.getWorld().getName())) {
            blastWorld = at.getWorld().getName(); blastAt = at;
            bx1 = bx2 = at.getBlockX(); by1 = by2 = at.getBlockY(); bz1 = bz2 = at.getBlockZ();
        } else {
            bx1 = Math.min(bx1, at.getBlockX()); bx2 = Math.max(bx2, at.getBlockX());
            by1 = Math.min(by1, at.getBlockY()); by2 = Math.max(by2, at.getBlockY());
            bz1 = Math.min(bz1, at.getBlockZ()); bz2 = Math.max(bz2, at.getBlockZ());
        }
        if (!blastScheduled) { blastScheduled = true; Bukkit.getScheduler().runTask(plugin, this::settleBlast); }
    }

    /** Items produced by the blast. Restricted to the blast bounding box so an unrelated mob drop in the
     *  same tick cannot cancel out a genuine loss; a stray item inside the box only ever makes the credit
     *  smaller, never larger, so the at-most-once guarantee holds either way. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void itemSpawn(org.bukkit.event.entity.ItemSpawnEvent event) {
        if (!blastScheduled || blastWorld == null) return;
        Location at = event.getEntity().getLocation();
        if (at.getWorld() == null || !blastWorld.equals(at.getWorld().getName())) return;
        if (at.getBlockX() < bx1-2 || at.getBlockX() > bx2+2 || at.getBlockY() < by1-2 || at.getBlockY() > by2+2
                || at.getBlockZ() < bz1-2 || at.getBlockZ() > bz2+2) return;
        ItemStack stack = event.getEntity().getItemStack();
        if (stack == null || stack.getType().isAir()) return;
        blastDropped.merge(stack.getType(), stack.getAmount(), Integer::sum);
    }

    private void settleBlast() {
        try {
            if (recycleBlocks()) for (Map.Entry<Material,Integer> entry : blastExpected.entrySet()) {
                int lost = entry.getValue() - blastDropped.getOrDefault(entry.getKey(), 0);
                if (lost > 0) deliverBlock(entry.getKey(), lost, blastAt, "EXPLOSION_LOST", true);
            }
        } finally {
            blastExpected.clear(); blastDropped.clear(); blastScheduled = false; blastWorld = null; blastAt = null;
        }
    }

    /** Only an ordinary, unmodified stack of something the shop actually trades. */
    boolean recyclable(ItemStack stack) {
        if (stack == null) return false;
        Material material = stack.getType();
        if (material.isAir()) return false;
        ShopService shop = plugin.shop();
        if (shop == null || !shop.stockLimited(material) || shop.configuredSell(material) <= 0) return false;
        /** isSimilar against a plain stack rejects everything carrying custom data: display names, lore,
         *  enchantments, damage, and any persistent key. Relics, bound items, custom spawners and the
         *  Industrial Hopper all fail here, which is exactly the intent -- none of them may ever be
         *  flattened into ordinary commodity stock. */
        return stack.isSimilar(new ItemStack(material));
    }

    /** Canonical, sorted enchantment signature for the ledger key ("sharpness:5,unbreaking:3"), covering both
     *  live enchantments and the stored enchantments on an enchanted book. Empty for plain items -- so ordinary
     *  stacks aggregate exactly as before and only enchanted gear splits into its own rows. */
    static String enchantSignature(ItemStack stack) {
        java.util.TreeMap<String, Integer> map = new java.util.TreeMap<>();
        stack.getEnchantments().forEach((e, l) -> map.put(e.getKey().getKey(), l));
        if (stack.getItemMeta() instanceof EnchantmentStorageMeta esm)
            esm.getStoredEnchants().forEach((e, l) -> map.put(e.getKey().getKey(), l));
        if (map.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        map.forEach((k, l) -> b.append(b.length() == 0 ? "" : ",").append(k).append(":").append(l));
        return b.toString();
    }

    /** A compact, human-readable description of an item's notable details for the audit view -- shulker box
     *  CONTENTS, a custom name, relic/custom-data and durability. Empty for a plain item, so ordinary stacks
     *  still aggregate. VIEW-ONLY: this is text for an admin to read, never a serialized item to reconstruct. */
    static String describeItem(ItemStack stack) {
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        if (meta == null) return "";
        List<String> parts = new ArrayList<>();
        if (meta.hasDisplayName()) {
            String nm = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName()).trim();
            if (!nm.isEmpty()) parts.add("“" + nm + "”");
        }
        if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta bsm && bsm.hasBlockState()
                && bsm.getBlockState() instanceof org.bukkit.block.ShulkerBox box) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (ItemStack inside : box.getInventory().getContents()) {
                if (inside == null || inside.getType().isAir()) continue;
                String label = CoreUtil.pretty(inside.getType().name());
                String ench = enchantSignature(inside);
                if (!ench.isEmpty()) label += " [" + enchantsDisplay(ench) + "]";
                counts.merge(label, inside.getAmount(), Integer::sum);
            }
            if (counts.isEmpty()) parts.add("holds nothing");
            else {
                StringBuilder c = new StringBuilder("holds ");
                int shown = 0, total = counts.size();
                for (Map.Entry<String, Integer> en : counts.entrySet()) {
                    if (shown == 12) { c.append(", +").append(total - 12).append(" more"); break; }
                    if (shown > 0) c.append(", ");
                    c.append(en.getValue()).append("x ").append(en.getKey());
                    shown++;
                }
                parts.add(c.toString());
            }
        }
        if (!meta.getPersistentDataContainer().isEmpty()) parts.add("custom-data");
        if (meta instanceof org.bukkit.inventory.meta.Damageable dmg && dmg.hasDamage()) parts.add("used");
        return String.join(" • ", parts);
    }
    /** Renders the stored detail string into lore -- shulker contents become an indented list. */
    private static void appendDetailLore(List<String> lore, String details) {
        if (details == null || details.isEmpty()) return;
        for (String seg : details.split(" • ")) {
            if (seg.startsWith("holds ")) {
                lore.add("Contents:");
                for (String item : seg.substring("holds ".length()).split(", ")) lore.add("  " + item);
            } else lore.add(seg);
        }
    }

    // ------------------------------------------------------------------ batched write
    private void flush() {
        if (pending.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>(pending.size());
        Map<String, Integer> stock = new LinkedHashMap<>();
        for (Map.Entry<Key, Pending> entry : pending.entrySet()) {
            Key key = entry.getKey();
            Pending row = entry.getValue();
            rows.add(new Object[]{row.at, key.material(), row.amount, key.reason(), key.recycled() ? 1 : 0,
                    row.world, row.x, row.y, row.z, key.enchants(), key.details()});
            if (key.recycled()) stock.merge(key.material(), row.amount, Integer::sum);
        }
        pending.clear();
        plugin.db().recordDiscarded(rows);
        /** One statement for the whole batch. The ledger is written first, so a failure here can only ever
         *  leave the audit ahead of the stock, never stock credited with no record of where it came from. */
        plugin.db().shopStockAddAll(stock);
    }

    // ------------------------------------------------------------------ admin view (read only)
    void show(CommandSender sender, int page) {
        if (!(sender instanceof Player viewer)) { showConsole(sender, page); return; }
        sessions.computeIfAbsent(viewer.getUniqueId(), k -> new Session()).page = Math.max(1, page);
        render(viewer);
    }

    /** Console/RCON path: a plain text dump of one page of the most-destroyed aggregate, built synchronously
     *  (console is not the hot path) and without disturbing the shared GUI cache. */
    private void showConsole(CommandSender sender, int page) {
        List<VaultEntry> all = buildEntries(plugin.db().discardedAggregate(AGG_LIMIT));
        int perPage = 45, maxPage = Math.max(1, (all.size() + perPage - 1) / perPage);
        int p = Math.min(Math.max(1, page), maxPage), from = perPage * (p - 1);
        List<VaultEntry> slice = all.subList(Math.min(from, all.size()), Math.min(from + perPage, all.size()));
        CoreUtil.msg(sender, "Discarded vault \u2014 " + slice.size() + " entry(s), page " + p + "/" + maxPage);
        for (VaultEntry e : slice)
            CoreUtil.msg(sender, "  " + e.display() + " destroyed=" + e.destroyed() + " recycled=" + e.recycled()
                    + " last=" + e.reason() + " at " + e.world() + " " + e.x() + "," + e.y() + "," + e.z()
                    + " (" + ago(e.at()) + " ago)");
    }

    /** Opens the GUI from the cached snapshot. If stale, the snapshot is rebuilt OFF THE MAIN THREAD first (one
     *  query per cache window, never per click) so search/sort/paging add no load to the server tick. */
    private void render(Player viewer) {
        List<VaultEntry> c = cache;
        long ttl = Math.max(1, plugin.getConfig().getLong("discarded-vault.cache-seconds", 15)) * 1000L;
        if (c != null && System.currentTimeMillis() - cacheAt <= ttl) { renderNow(viewer, c); return; }
        if (rebuilding) {
            if (c != null) renderNow(viewer, c);
            else Bukkit.getScheduler().runTaskLater(plugin, () -> { if (viewer.isOnline()) render(viewer); }, 8L);
            return;
        }
        rebuilding = true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<VaultEntry> built;
            try { built = buildEntries(plugin.db().discardedAggregate(AGG_LIMIT)); }
            catch (RuntimeException ex) { rebuilding = false; return; }
            cache = built; cacheAt = System.currentTimeMillis(); rebuilding = false;
            Bukkit.getScheduler().runTask(plugin, () -> { if (viewer.isOnline()) renderNow(viewer, built); });
        });
    }

    private void renderNow(Player viewer, List<VaultEntry> all) {
        showCoords = !"false".equals(plugin.db().state("vault_show_coords"));
        Session s = sessions.computeIfAbsent(viewer.getUniqueId(), k -> new Session());
        List<VaultEntry> view = filterSort(all, s);
        int perPage = 45, maxPage = Math.max(1, (view.size() + perPage - 1) / perPage);
        int p = Math.min(Math.max(1, s.page), maxPage); s.page = p;
        int from = perPage * (p - 1);
        List<VaultEntry> slice = view.subList(Math.min(from, view.size()), Math.min(from + perPage, view.size()));
        Inventory inv = Bukkit.createInventory(new VaultHolder(p), 54,
                Component.text("Discarded Vault \u2022 page " + p + "/" + maxPage, NamedTextColor.DARK_RED));
        int slot = 0;
        for (VaultEntry e : slice) { if (slot >= 45) break; inv.setItem(slot++, icon(e)); }
        if (p > 1) inv.setItem(45, CoreUtil.named(Material.ARROW, "\u25c0 Previous page", List.of("Go to page " + (p - 1))));
        inv.setItem(46, CoreUtil.named(Material.COMPARATOR, "Sort: " + s.sort.label, List.of("Click to change how entries are ordered.")));
        inv.setItem(47, CoreUtil.named(Material.HOPPER, "Category: " + s.category, List.of("Click to cycle the category filter.")));
        inv.setItem(48, CoreUtil.named(Material.OAK_SIGN, s.search.isEmpty() ? "Search" : "Search: \"" + s.search + "\"",
                List.of("Click, then type a term in chat.", "Type 'clear' to remove it.")));
        inv.setItem(49, CoreUtil.named(Material.WRITABLE_BOOK, "Audit \u2014 page " + p + "/" + maxPage,
                List.of(view.size() + " of " + all.size() + " entries shown.",
                        "Records what permanently left the world.", "Nothing can be withdrawn.")));
        inv.setItem(51, CoreUtil.named(Material.BARRIER, "Reset filters", List.of("Clear search + category; sort by most destroyed.")));
        inv.setItem(52, CoreUtil.named(showCoords ? Material.FILLED_MAP : Material.MAP, "Coordinates: " + (showCoords ? "ON" : "OFF"), List.of("Show/hide the last-seen location in each entry.", "Server-wide and persistent.")));
        if (p < maxPage) inv.setItem(53, CoreUtil.named(Material.ARROW, "Next page \u25b6", List.of("Go to page " + (p + 1))));
        viewer.openInventory(inv);
    }

    private List<VaultEntry> filterSort(List<VaultEntry> all, Session s) {
        java.util.stream.Stream<VaultEntry> stream = all.stream();
        if (!"All".equals(s.category)) { String cat = s.category; stream = stream.filter(e -> e.category().equals(cat)); }
        if (!s.search.isEmpty()) { String q = s.search.toLowerCase(Locale.ROOT); stream = stream.filter(e -> e.searchKey().contains(q)); }
        Comparator<VaultEntry> cmp = switch (s.sort) {
            case MOST_DESTROYED -> Comparator.comparingLong(VaultEntry::destroyed).reversed();
            case MOST_RECYCLED -> Comparator.comparingLong(VaultEntry::recycled).reversed();
            case RECENT -> Comparator.comparingLong(VaultEntry::at).reversed();
            case NAME -> Comparator.comparing(VaultEntry::display, String.CASE_INSENSITIVE_ORDER);
        };
        return stream.sorted(cmp).toList();
    }

    private List<VaultEntry> buildEntries(List<String[]> agg) {
        List<VaultEntry> out = new ArrayList<>(agg.size());
        for (String[] r : agg) {
            String material = r[0], enchants = r[1] == null ? "" : r[1], details = r[2] == null ? "" : r[2];
            boolean spawner = material.startsWith("SPAWNER_");
            String baseName = spawner ? CoreUtil.pretty(material.substring("SPAWNER_".length())) + " Spawner" : CoreUtil.pretty(material);
            String enchDisplay = enchantsDisplay(enchants);
            String display = enchDisplay.isEmpty() ? baseName : baseName + " (" + enchDisplay + ")";
            String searchKey = (display + " " + details).toLowerCase(Locale.ROOT);
            out.add(new VaultEntry(material, enchants, enchDisplay, details, categoryOf(material, enchants, spawner),
                    display, searchKey,
                    Long.parseLong(r[3]), Long.parseLong(r[4]), r[5], r[6],
                    Integer.parseInt(r[7]), Integer.parseInt(r[8]), Integer.parseInt(r[9]), Long.parseLong(r[10])));
        }
        return out;
    }

    private ItemStack icon(VaultEntry e) {
        boolean spawner = e.material().startsWith("SPAWNER_");
        Material material = spawner ? Material.SPAWNER : Material.matchMaterial(e.material());
        /** Block-only materials (TWISTING_VINES_PLANT, CAVE_VINES, ...) resolve but are not items; new ItemStack
         *  throws for them, so fall back to a placeholder while keeping the real name/lore. */
        Material iconMaterial = material == null || !material.isItem() ? Material.BARRIER : material;
        List<String> lore = new ArrayList<>();
        if (!e.enchantsDisplay().isEmpty()) lore.add(e.enchantsDisplay());
        appendDetailLore(lore, e.details());
        lore.add("Destroyed: " + CoreUtil.compact(e.destroyed()));
        lore.add("Recycled into shop stock: " + CoreUtil.compact(e.recycled()));
        lore.add(showCoords ? "Last: " + e.reason() + " at " + e.world() + " " + e.x() + ", " + e.y() + ", " + e.z() : "Last: " + e.reason());
        lore.add(ago(e.at()) + " ago \u2022 " + e.category());
        ItemStack item = CoreUtil.named(iconMaterial, e.display(), lore);
        if (spawner || !e.enchants().isEmpty()) {
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (spawner) meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
                if (!e.enchants().isEmpty()) meta.setEnchantmentGlintOverride(true);
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    private static String categoryOf(String material, String enchants, boolean spawner) {
        if (spawner) return "Spawners";
        if (enchants != null && !enchants.isEmpty()) return "Enchanted";
        Material m = Material.matchMaterial(material);
        if (m == null) return "Items";
        if (material.endsWith("_SWORD") || material.endsWith("_AXE") || material.endsWith("_PICKAXE") || material.endsWith("_SHOVEL")
                || material.endsWith("_HOE") || material.endsWith("_HELMET") || material.endsWith("_CHESTPLATE")
                || material.endsWith("_LEGGINGS") || material.endsWith("_BOOTS") || material.equals("BOW") || material.equals("CROSSBOW")
                || material.equals("TRIDENT") || material.equals("SHIELD") || material.equals("ELYTRA") || material.equals("MACE")) return "Gear";
        if (m.isEdible()) return "Food";
        if (m.isBlock()) return "Blocks";
        return "Items";
    }

    private static String enchantsDisplay(String enchants) {
        if (enchants == null || enchants.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String part : enchants.split(",")) {
            int c = part.lastIndexOf(':');
            if (c <= 0) continue;
            int level; try { level = Integer.parseInt(part.substring(c + 1)); } catch (NumberFormatException ignored) { continue; }
            if (b.length() > 0) b.append(", ");
            b.append(CoreUtil.pretty(part.substring(0, c))).append(' ').append(roman(level));
        }
        return b.toString();
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V";
            case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII"; case 9 -> "IX"; case 10 -> "X";
            default -> String.valueOf(n);
        };
    }

    private static String ago(long at) {
        Duration since = Duration.between(Instant.ofEpochMilli(at), Instant.now());
        long hours = since.toHours();
        if (hours >= 24) return since.toDays() + "d";
        if (hours >= 1) return hours + "h";
        return Math.max(1, since.toMinutes()) + "m";
    }

    /** Read-only: the grid items are inert; only the control row (page arrows, sort/category/search/reset)
     *  does anything, and every action just re-renders from the in-memory cache -- no database work per click. */
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof VaultHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        Session s = sessions.computeIfAbsent(viewer.getUniqueId(), k -> new Session());
        switch (event.getRawSlot()) {
            case 45 -> { if (s.page > 1) { s.page--; render(viewer); } }
            case 53 -> { s.page++; render(viewer); }
            case 46 -> { s.sort = Sort.values()[(s.sort.ordinal() + 1) % Sort.values().length]; s.page = 1; render(viewer); }
            case 47 -> { s.category = nextCategory(s.category); s.page = 1; render(viewer); }
            case 48 -> { awaitingSearch.add(viewer.getUniqueId()); viewer.closeInventory();
                         CoreUtil.msg(viewer, "Type a search term in chat \u2014 or 'clear' to remove it, 'cancel' to keep the current view."); }
            case 51 -> { s.sort = Sort.MOST_DESTROYED; s.category = "All"; s.search = ""; s.page = 1; render(viewer); }
            case 52 -> { plugin.db().state("vault_show_coords", showCoords ? "false" : "true"); render(viewer); }
            default -> {}
        }
    }
    private static String nextCategory(String current) {
        int i = 0;
        for (int k = 0; k < CATEGORIES.length; k++) if (CATEGORIES[k].equals(current)) { i = k; break; }
        return CATEGORIES[(i + 1) % CATEGORIES.length];
    }
    /** In-GUI search without an anvil: the Search button parks the viewer here and their next chat line becomes
     *  the filter (consumed, never broadcast). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void searchChat(AsyncPlayerChatEvent event) {
        if (!awaitingSearch.remove(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
        String msg = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player viewer = event.getPlayer();
            if (!viewer.isOnline()) return;
            Session s = sessions.computeIfAbsent(viewer.getUniqueId(), k -> new Session());
            if (!msg.equalsIgnoreCase("cancel")) { s.search = msg.equalsIgnoreCase("clear") ? "" : msg; s.page = 1; }
            render(viewer);
        });
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        awaitingSearch.remove(event.getPlayer().getUniqueId());
        sessions.remove(event.getPlayer().getUniqueId());
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof VaultHolder) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ self test
    boolean selfTest() {
        /** An ordinary commodity recycles. */
        if (!recyclable(new ItemStack(Material.COBBLESTONE, 12))) return false;
        /** A luxury the shop mints rather than buys must never enter stock. */
        if (recyclable(new ItemStack(Material.DRAGON_EGG))) return false;
        /** Anything carrying custom data is refused, which is the guard that protects relics, bound items,
         *  custom spawners and Industrial Hoppers. Proven here with the same shape they all have. */
        ItemStack named = new ItemStack(Material.COBBLESTONE, 12);
        var meta = named.getItemMeta();
        meta.displayName(Component.text("Not ordinary"));
        named.setItemMeta(meta);
        if (recyclable(named)) return false;
        ItemStack keyed = new ItemStack(Material.COBBLESTONE, 12);
        var tagged = keyed.getItemMeta();
        tagged.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "vault_probe"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        keyed.setItemMeta(tagged);
        if (recyclable(keyed)) return false;
        ItemStack relic = plugin.relics().create("oathblade");
        if (relic != null && recyclable(relic)) return false;
        /** Enchanted and damaged versions of an otherwise eligible material are refused too. */
        ItemStack enchanted = new ItemStack(Material.COBBLESTONE, 12);
        enchanted.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        return !recyclable(enchanted);
    }

    String summaryLine() {
        List<String[]> totals = plugin.db().discardedTotals(5000);
        long destroyed = 0, recycled = 0;
        for (String[] row : totals) { destroyed += Long.parseLong(row[1]); recycled += Long.parseLong(row[2]); }
        return "Vault: " + CoreUtil.compact(destroyed) + " destroyed, " + CoreUtil.compact(recycled)
                + " returned to shop stock across " + totals.size() + " material"
                + (totals.size() == 1 ? "" : "s") + ".";
    }

    String describe(Material material) { return material == null ? "" : material.name().toLowerCase(Locale.ROOT); }
}
