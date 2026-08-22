package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The Spawner Shop: every spawner that leaves the world WITHOUT reaching a player's inventory ends up here,
 *  and can be bought back.
 *
 *  Same idea as the Discarded Vault, and deliberately the same shape: a spawner blown up by a creeper, burnt
 *  in lava, or left on the ground until it despawned is not destroyed value -- it is value that fell out of
 *  the economy. Rather than vanishing, it becomes stock somebody can buy. A spawner a player picks up never
 *  comes near this: the capture paths are despawn, item destruction and explosion, all of which mean nobody
 *  got it.
 *
 *  Stock is per mob type, because that is what a spawner IS -- every spawner is Material.SPAWNER and the
 *  EntityType is the whole product. That is also why this is its own screen rather than a section of
 *  /shop: MarketplaceService is built around one price per Material, and a shop where every row is the same
 *  Material cannot be expressed in it without rewriting the row model underneath three live shops. The GUI
 *  here follows the same grammar -- same page size, same sort cycle, same filler and controls -- so it reads
 *  as part of the shop family, and /shop links straight to it. */
final class SpawnerShopService implements Listener {

    private static final int PAGE_SIZE = 45;

    /** Sort options, mirroring the marketplace's own cycle. */
    enum Sort {
        STOCK("Most in stock"), PRICE_LOW("Cheapest first"), PRICE_HIGH("Dearest first"), NAME("A to Z");
        private final String label;
        Sort(String label) { this.label = label; }
        String label() { return label; }
        Sort next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private record Holder(int page, Sort sort) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private final SMPCore plugin;
    private final Database db;
    /** Viewer -> the page/sort they are on, so a refresh after a purchase keeps their place. */
    private final Map<String, Holder> views = new java.util.concurrent.ConcurrentHashMap<>();

    SpawnerShopService(SMPCore plugin) {
        this.plugin = plugin;
        this.db = plugin.db();
    }

    // ------------------------------------------------------------------ capture

    /** A spawner ITEM that ticked out on the ground. Nobody picked it up, so it comes to the shop. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void despawn(ItemDespawnEvent event) {
        capture(event.getEntity().getItemStack(), event.getLocation(), "DESPAWNED");
    }

    /** Lava, fire, explosions, cactus, the void. Checked on the FOLLOWING tick because damage does not always
     *  destroy an item entity, and recording one that survived would put stock in the shop that never
     *  actually left the world. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) return;
        ItemStack stack = item.getItemStack().clone();
        if (stack.getType() != Material.SPAWNER) return;
        Location at = item.getLocation().clone();
        String reason = switch (event.getCause()) {
            case LAVA, FIRE, FIRE_TICK, HOT_FLOOR -> "BURNED";
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> "EXPLODED";
            case VOID -> "VOID";
            default -> "DESTROYED";
        };
        Bukkit.getScheduler().runTask(plugin, () -> { if (!item.isValid()) capture(stack, at, reason); });
    }

    /** A PLACED spawner caught in a blast. Vanilla drops nothing for an exploded spawner, so without this the
     *  block is simply gone -- which is exactly the loss this shop exists to recover. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityExplode(EntityExplodeEvent event) { explodedBlocks(event.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockExplode(BlockExplodeEvent event) { explodedBlocks(event.blockList()); }

    private void explodedBlocks(List<Block> blocks) {
        for (Block block : blocks) {
            if (block.getType() != Material.SPAWNER) continue;
            if (!(block.getState(false) instanceof CreatureSpawner spawner)) continue;
            EntityType type = spawner.getSpawnedType();
            if (type == null) continue;
            /** A stacked spawner is several spawners in one block, and all of them are lost to the blast. */
            int stack = plugin.spawners() == null ? 1 : Math.max(1, plugin.spawners().stackSize(spawner));
            add(type, stack, "EXPLODED", block.getLocation());
        }
    }

    private void capture(ItemStack stack, Location at, String reason) {
        if (stack == null || stack.getType() != Material.SPAWNER) return;
        EntityType type = plugin.spawners() == null ? null : plugin.spawners().typeOf(stack);
        if (type == null) return;
        add(type, Math.max(1, stack.getAmount()), reason, at);
    }

    /** Capture entry point for other services -- notably a spawner MINED without a recovery tool, which is
     *  destroyed for XP and never reaches an inventory, vanilla and plugin spawners alike. */
    void recover(EntityType type, int amount, Location at, String reason) { add(type, amount, reason, at); }

    private void add(EntityType type, int amount, String reason, Location at) {
        if (!plugin.getConfig().getBoolean("spawner-shop.enabled", true)) return;
        db.spawnerShopAdd(type.name(), amount);
        db.history("SERVER", null, "SPAWNER_SHOP", amount + "x " + CoreUtil.pretty(type.name())
                + " spawner recovered (" + reason + ") at " + describe(at));
        plugin.getLogger().info("[spawner-shop] +" + amount + " " + type.name() + " (" + reason + ")");
    }

    private String describe(Location at) {
        return at == null || at.getWorld() == null ? "unknown"
                : at.getWorld().getName() + " " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ();
    }

    // ------------------------------------------------------------------ pricing

    /** The audited price for one spawner of this type, before the Central Bank's deficit surcharge.
     *
     *  Derived from what the spawner actually EARNS, not from vibes: money per kill is the mob's own
     *  `mob-rewards` midpoint times `mob-money.spawner-share`, plus the shop-sell value of its average drops.
     *  Every figure is a multiple of 50,000 so the list stays readable. See the 2026-08-22 dev-log entry for
     *  the full table and the arithmetic behind each one. */
    double price(EntityType type) {
        double configured = plugin.getConfig().getDouble("spawner-shop.prices." + type.name(), -1);
        if (configured >= 0) return configured;
        return plugin.getConfig().getDouble("spawner-shop.default-price", 250_000);
    }

    double buyPrice(EntityType type) {
        return Math.round(price(type) * plugin.bank().buyFactor() * 100) / 100.0;
    }

    /** A fifth of the buy price, which is exactly the buy:sell ratio every entry in the normal shop uses --
     *  so a spawner's value is stated the same way as everything else on the server rather than being its own
     *  special case. Takes the deficit sell factor for the same reason buying takes the buy factor. */
    double sellPrice(EntityType type) {
        return Math.round(price(type) / 5.0 * plugin.bank().sellFactor() * 100) / 100.0;
    }

    // ------------------------------------------------------------------ the shop

    void open(Player player) { open(player, 0, Sort.STOCK); }

    void open(Player player, int page, Sort sort) {
        List<Map.Entry<EntityType, Integer>> rows = rows(sort);
        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int shown = Math.max(0, Math.min(page, pages - 1));
        Holder holder = new Holder(shown, sort);
        views.put(CoreUtil.id(player), holder);
        Inventory inv = plugin.getServer().createInventory(holder, 54,
                Component.text("Spawner Shop", NamedTextColor.DARK_PURPLE));
        for (int slot = PAGE_SIZE; slot < 54; slot++) inv.setItem(slot, filler());
        for (int index = shown * PAGE_SIZE, slot = 0; index < rows.size() && slot < PAGE_SIZE; index++, slot++)
            inv.setItem(slot, icon(rows.get(index).getKey(), rows.get(index).getValue()));
        if (rows.isEmpty()) inv.setItem(22, CoreUtil.named(Material.BARRIER, "Nothing in stock",
                List.of("Spawners blown up, burnt or left to despawn",
                        "end up here instead of being lost.",
                        "Nothing has been recovered yet.")));
        inv.setItem(45, CoreUtil.named(Material.ARROW, shown > 0 ? "Previous page" : " ",
                shown > 0 ? List.of("Page " + shown + " of " + pages) : List.of()));
        inv.setItem(49, CoreUtil.named(Material.HOPPER, "Sort: " + sort.label(), List.of("Click to change the order.")));
        inv.setItem(43, CoreUtil.named(Material.HOPPER, "Sell held spawner", List.of(
                "Hold a spawner and click to sell it.",
                "Pays a fifth of the buy price, the same",
                "ratio the normal shop uses.")));
        Sort ignored = sort;
        inv.setItem(48, CoreUtil.named(Material.EMERALD, "Switch to Normal Shop", List.of("Cycle on through the shops.")));
        inv.setItem(53, CoreUtil.named(Material.ARROW, shown < pages - 1 ? "Next page" : " ",
                shown < pages - 1 ? List.of("Page " + (shown + 2) + " of " + pages) : List.of()));
        player.openInventory(inv);
    }

    private List<Map.Entry<EntityType, Integer>> rows(Sort sort) {
        LinkedHashMap<EntityType, Integer> stock = stock();
        List<Map.Entry<EntityType, Integer>> rows = new ArrayList<>(stock.entrySet());
        Comparator<Map.Entry<EntityType, Integer>> comparator = switch (sort) {
            case STOCK -> Comparator.<Map.Entry<EntityType, Integer>>comparingInt(row -> -row.getValue())
                    .thenComparing(row -> CoreUtil.pretty(row.getKey().name()), String.CASE_INSENSITIVE_ORDER);
            case PRICE_LOW -> Comparator.comparingDouble(row -> price(row.getKey()));
            case PRICE_HIGH -> Comparator.comparingDouble(row -> -price(row.getKey()));
            case NAME -> Comparator.comparing(row -> CoreUtil.pretty(row.getKey().name()), String.CASE_INSENSITIVE_ORDER);
        };
        rows.sort(comparator);
        return rows;
    }

    /** Everything currently recoverable, mob type -> count. */
    LinkedHashMap<EntityType, Integer> stock() {
        LinkedHashMap<EntityType, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> row : db.spawnerShopStock().entrySet()) {
            if (row.getValue() <= 0) continue;
            try { out.put(EntityType.valueOf(row.getKey()), row.getValue()); }
            catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    private ItemStack icon(EntityType type, int stock) {
        ItemStack item = new ItemStack(Material.SPAWNER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(CoreUtil.pretty(type.name()) + " Spawner", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("In stock: " + stock, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Price: " + CoreUtil.money(buyPrice(type)), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        if (plugin.bank().deficit())
            lore.add(Component.text("Central Bank deficit: 2x until the treasury recovers.", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Recovered from spawners lost to", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("explosions, lava or despawning.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Click to buy one.", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack filler() { return CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()); }

    // ------------------------------------------------------------------ buying

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot == 45) { if (holder.page() > 0) { plugin.settings().uiSound(player, "page"); open(player, holder.page() - 1, holder.sort()); } return; }
        if (slot == 53) { plugin.settings().uiSound(player, "page"); open(player, holder.page() + 1, holder.sort()); return; }
        if (slot == 49) { plugin.settings().uiSound(player, "select"); open(player, 0, holder.sort().next()); return; }
        if (slot == 48) { plugin.settings().uiSound(player, "back"); plugin.shop().open(player); return; }
        if (slot == 43) { sell(player, holder); return; }
        if (slot < 0 || slot >= PAGE_SIZE) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.SPAWNER) return;
        List<Map.Entry<EntityType, Integer>> rows = rows(holder.sort());
        int index = holder.page() * PAGE_SIZE + slot;
        if (index >= rows.size()) return;
        buy(player, rows.get(index).getKey(), holder);
    }

    /** Buys one spawner. Stock is decremented with a compare-and-swap BEFORE any money moves and before the
     *  item exists, so two players clicking the last one at the same instant cannot both get it -- and if the
     *  payment then fails the stock goes straight back. */
    private void buy(Player player, EntityType type, Holder holder) {
        double cost = buyPrice(type);
        if (!ShopService.canFit(player, new ItemStack(Material.SPAWNER))) {
            CoreUtil.error(player, "Make room in your inventory first.");
            plugin.settings().uiSound(player, "error");
            return;
        }
        if (!db.spawnerShopTake(type.name())) {
            CoreUtil.error(player, "That spawner just sold out.");
            plugin.settings().uiSound(player, "error");
            open(player, holder.page(), holder.sort());
            return;
        }
        if (!plugin.bank().payServer(player, cost, "SPAWNER_SHOP", type.name())) {
            db.spawnerShopAdd(type.name(), 1);
            CoreUtil.error(player, "You need " + CoreUtil.money(cost) + ".");
            plugin.settings().uiSound(player, "error");
            return;
        }
        ItemStack spawner = plugin.spawners().createItem(type, 0, java.util.UUID.randomUUID().toString());
        CoreUtil.give(player, spawner);
        db.recordEconomy(CoreUtil.id(player), "SPAWNER_SHOP", -cost, type.name());
        db.history(CoreUtil.id(player), null, "SPAWNER_SHOP",
                player.getName() + " bought a " + CoreUtil.pretty(type.name()) + " Spawner for " + CoreUtil.money(cost) + ".");
        CoreUtil.msg(player, "Bought a " + CoreUtil.pretty(type.name()) + " Spawner for " + CoreUtil.money(cost) + ".");
        plugin.settings().uiSound(player, "success");
        open(player, holder.page(), holder.sort());
    }

    /** Sells the spawner the player is holding. The spawner goes back into stock, so selling and buying are
     *  the two halves of one pool rather than a money faucet. */
    private void sell(Player player, Holder holder) {
        ItemStack held = player.getInventory().getItemInMainHand();
        EntityType type = plugin.spawners() == null ? null : plugin.spawners().typeOf(held);
        if (held == null || held.getType() != Material.SPAWNER || type == null) {
            CoreUtil.error(player, "Hold the spawner you want to sell.");
            plugin.settings().uiSound(player, "error");
            return;
        }
        double paid = sellPrice(type);
        if (!plugin.bank().payShopSeller(player, paid, "SPAWNER_SHOP")) {
            CoreUtil.error(player, "The Central Bank treasury cannot cover this sale yet.");
            plugin.settings().uiSound(player, "error");
            return;
        }
        /** Remove the item only once the money is actually paid, so a failed payout cannot eat the spawner. */
        if (held.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else held.setAmount(held.getAmount() - 1);
        db.spawnerShopAdd(type.name(), 1);
        db.recordEconomy(CoreUtil.id(player), "SPAWNER_SHOP_SALE", paid, type.name());
        db.history(CoreUtil.id(player), null, "SPAWNER_SHOP",
                player.getName() + " sold a " + CoreUtil.pretty(type.name()) + " Spawner for " + CoreUtil.money(paid) + ".");
        CoreUtil.msg(player, "Sold a " + CoreUtil.pretty(type.name()) + " Spawner for " + CoreUtil.money(paid) + ".");
        plugin.settings().uiSound(player, "success");
        open(player, holder.page(), holder.sort());
    }

    // ------------------------------------------------------------------ admin / diagnostics

    String describeStock() {
        LinkedHashMap<EntityType, Integer> stock = stock();
        if (stock.isEmpty()) return "The Spawner Shop has nothing in stock.";
        StringBuilder sb = new StringBuilder("Spawner Shop stock:");
        for (Map.Entry<EntityType, Integer> row : stock.entrySet())
            sb.append("\n  ").append(CoreUtil.pretty(row.getKey().name())).append(" x").append(row.getValue())
              .append("  ").append(CoreUtil.money(buyPrice(row.getKey())));
        return sb.toString();
    }

    /** Prices must be real, ordered by what the spawner actually earns, and round. */
    boolean selfTest() {
        double blaze = price(EntityType.BLAZE), spider = price(EntityType.SPIDER),
               skeleton = price(EntityType.SKELETON), zombie = price(EntityType.ZOMBIE),
               cave = price(EntityType.CAVE_SPIDER);
        if (!(blaze > cave && cave > spider && spider > skeleton && skeleton > zombie)) return false;
        for (double value : new double[]{blaze, spider, skeleton, zombie, cave,
                plugin.getConfig().getDouble("spawner-shop.default-price", 250_000)})
            if (value <= 0 || value % 50_000 != 0) return false;
        /** The deficit surcharge must reach this shop exactly as it reaches every other one. */
        if (buyPrice(EntityType.ZOMBIE) != Math.round(zombie * plugin.bank().buyFactor() * 100) / 100.0) return false;
        /** Selling pays exactly a fifth of the base price, the same ratio the normal shop uses. */
        return sellPrice(EntityType.ZOMBIE) == Math.round(zombie / 5.0 * plugin.bank().sellFactor() * 100) / 100.0;
    }
}
