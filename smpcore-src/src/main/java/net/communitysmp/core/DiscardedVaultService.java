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
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private record Key(String material, String reason, boolean recycled) {}
    private static final class Pending {
        private int amount;
        private long at;
        private String world = "";
        private int x, y, z;
    }

    private record VaultHolder() implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private final SMPCore plugin;
    private final Map<Key, Pending> pending = new LinkedHashMap<>();
    private BukkitTask flushTask;

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

    private void record(ItemStack stack, Location at, String reason) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return;
        Key key = new Key(stack.getType().name(), reason, recyclable(stack));
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

    // ------------------------------------------------------------------ batched write
    private void flush() {
        if (pending.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>(pending.size());
        Map<String, Integer> stock = new LinkedHashMap<>();
        for (Map.Entry<Key, Pending> entry : pending.entrySet()) {
            Key key = entry.getKey();
            Pending row = entry.getValue();
            rows.add(new Object[]{row.at, key.material(), row.amount, key.reason(), key.recycled() ? 1 : 0,
                    row.world, row.x, row.y, row.z});
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
        List<String[]> totals = plugin.db().discardedTotals(45 * Math.max(1, page));
        int from = 45 * (Math.max(1, page) - 1);
        if (from >= totals.size() && !totals.isEmpty()) { CoreUtil.error(sender, "No vault entries on page " + page + "."); return; }
        List<String[]> slice = totals.subList(Math.min(from, totals.size()), totals.size());
        if (!(sender instanceof Player viewer)) {
            CoreUtil.msg(sender, "Discarded vault — " + slice.size() + " material(s), page " + Math.max(1, page));
            for (String[] row : slice)
                CoreUtil.msg(sender, "  " + row[0] + " destroyed=" + row[1] + " recycled=" + row[2]
                        + " last=" + row[3] + " at " + row[4] + " " + row[5] + "," + row[6] + "," + row[7]
                        + " (" + ago(Long.parseLong(row[8])) + " ago)");
            return;
        }
        Inventory inv = Bukkit.createInventory(new VaultHolder(), 54,
                Component.text("Discarded Vault • Audit", NamedTextColor.DARK_RED));
        int slot = 0;
        for (String[] row : slice) {
            if (slot >= 45) break;
            Material material = Material.matchMaterial(row[0]);
            List<String> lore = new ArrayList<>();
            lore.add("Destroyed: " + CoreUtil.compact(Long.parseLong(row[1])));
            lore.add("Recycled into shop stock: " + CoreUtil.compact(Long.parseLong(row[2])));
            lore.add("Last reason: " + row[3]);
            lore.add("Last seen: " + row[4] + " " + row[5] + ", " + row[6] + ", " + row[7]);
            lore.add(ago(Long.parseLong(row[8])) + " ago");
            inv.setItem(slot++, CoreUtil.named(material == null ? Material.BARRIER : material,
                    CoreUtil.pretty(row[0]), lore));
        }
        inv.setItem(49, CoreUtil.named(Material.WRITABLE_BOOK, "Audit record only",
                List.of("This is a log of items that left the world.",
                        "Nothing can be withdrawn from it.",
                        "Eligible commodities were returned to shop stock.")));
        viewer.openInventory(inv);
    }

    private static String ago(long at) {
        Duration since = Duration.between(Instant.ofEpochMilli(at), Instant.now());
        long hours = since.toHours();
        if (hours >= 24) return since.toDays() + "d";
        if (hours >= 1) return hours + "h";
        return Math.max(1, since.toMinutes()) + "m";
    }

    /** Read-only in the strongest sense available: every interaction with the audit screen is refused. */
    @EventHandler public void click(InventoryClickEvent event) {
        if (event.getInventory().getHolder(false) instanceof VaultHolder) event.setCancelled(true);
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
