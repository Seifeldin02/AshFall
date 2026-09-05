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

    /*  43, THE SAME AS EVERY OTHER SHOP -- AND IT WAS NOT.
     *
     *  It was 45, so the grid ran to slot 44 while the footer's Sell button was written into 43. On a
     *  page with more than 43 kinds of spawner in stock, the 44th was drawn under a button and its slot
     *  was claimed by the sell basket before the click ever reached the buy path: a stock row that could
     *  be seen and not bought. Nobody had hit it because the recovery list has never been that long. */
    static final int PAGE_SIZE = 43;
    /** The sell basket shares the footer band with the paging and the switch, so it is named alongside them
     *  rather than written as a bare 43 in two places that have to agree. */
    private static final int SELL = 43;

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
                Component.text(MarketplaceService.Section.SPAWNERS.label, CoreUtil.EMBER));
        CoreUtil.Menu.footer(inv);
        for (int index = shown * PAGE_SIZE, slot = 0; index < rows.size() && slot < PAGE_SIZE; index++, slot++)
            inv.setItem(slot, icon(rows.get(index).getKey(), rows.get(index).getValue()));
        if (rows.isEmpty()) inv.setItem(22, CoreUtil.Menu.nothing("Nothing in stock",
                List.of("Spawners blown up, burnt or left to despawn end up",
                        "here instead of being lost for good.",
                        CoreUtil.C_MUTE + "Nothing has been recovered yet.")));
        /*  A disabled arrow used to be an ARROW named " " with no lore at all -- a blank icon that looked
         *  like a rendering fault. */
        inv.setItem(CoreUtil.Menu.PREV, CoreUtil.Menu.page(shown, pages, false));
        /*  The same button the other four shops draw, from the same builder: the whole ring, the section
         *  you are in marked, and the next one's own icon on the front. This screen used to write its own
         *  two-line version that named the next stop and nothing else. */
        inv.setItem(CoreUtil.Menu.BACK, MarketplaceService.switchButton(MarketplaceService.Section.SPAWNERS));
        /** Same slots the rest of the shop family uses: 43 sell basket, 49 switch, 51 sort, 45/53 paging.
         *  The switch and the sort were the wrong way round against every other shop screen. */
        inv.setItem(SELL, CoreUtil.Menu.action(Material.HOPPER, "Sell spawners", List.of(
                "Opens a sale basket, the same as /shop.",
                "Drop spawners in and confirm.",
                CoreUtil.C_MUTE + "Pays a fifth of the buy price.")));
        /*  HOPPER was also the Sell Spawners button four slots away. */
        inv.setItem(CoreUtil.Menu.SORT, CoreUtil.Menu.action(Material.COMPARATOR, "Sort", List.of(
                CoreUtil.C_TEXT + sort.label(), CoreUtil.C_MUTE + "Click to cycle.")));
        inv.setItem(CoreUtil.Menu.NEXT, CoreUtil.Menu.page(shown, pages, true));
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

    // ------------------------------------------------------------------ buying

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot == CoreUtil.Menu.PREV) { if (holder.page() > 0) { plugin.settings().uiSound(player, "page"); open(player, holder.page() - 1, holder.sort()); } return; }
        if (slot == CoreUtil.Menu.NEXT) { plugin.settings().uiSound(player, "page"); open(player, holder.page() + 1, holder.sort()); return; }
        if (slot == CoreUtil.Menu.SORT) { plugin.settings().uiSound(player, "select"); open(player, 0, holder.sort().next()); return; }
        /*  "select", not "back": this is not a Back button, and the bass note the other cues use for going
         *  backwards said it was. It goes ON round the ring, through the same opener every other route
         *  uses, so arriving at the Normal Shop from here is the same act as arriving from anywhere else. */
        if (slot == CoreUtil.Menu.BACK) { plugin.settings().uiSound(player, "select"); plugin.marketplace().openSection(player, MarketplaceService.Section.SPAWNERS.next()); return; }
        if (slot == SELL) { plugin.settings().uiSound(player, "select"); openSellBasket(player); return; }
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
        /*  Ask before anything moves.
         *
         *  Nothing at all happens on this path until the player confirms: no stock is taken, no money is
         *  charged, no item is created. Cancelling therefore cannot leave a spawner reserved or a balance
         *  short -- it just re-opens the shop. The stock compare-and-swap and the payment stay in exactly
         *  the order they were in, they simply now run inside the accept branch. */
        plugin.confirmations().request(player, SettingsService.ConfirmationKind.SPAWNER,
                cost >= plugin.getConfig().getDouble("confirmations.mandatory-spawner-price", 10_000_000),
                "Buy a " + CoreUtil.pretty(type.name()) + " Spawner",
                List.of("Cost: " + CoreUtil.money(cost)),
                () -> buyConfirmed(player, type, holder, cost),
                () -> open(player, holder.page(), holder.sort()));
    }

    private void buyConfirmed(Player player, EntityType type, Holder holder, double quotedCost) {
        /** Re-quote and re-check on accept. The price or the player's inventory can both change between the
         *  click and the confirmation, and the confirmed purchase must be the one that was agreed to. */
        double cost = buyPrice(type);
        if (cost != quotedCost) {
            CoreUtil.error(player, "The price changed to " + CoreUtil.money(cost) + " -- nothing was bought.");
            plugin.settings().uiSound(player, "error");
            open(player, holder.page(), holder.sort());
            return;
        }
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

    // ------------------------------------------------------------------ the sale basket

    /** The basket is deliberately the same shape as the normal shop's: drop the stacks in the top rows, see a
     *  running total, Confirm or Cancel. Spawners cannot be priced from shop.yml -- they are all
     *  Material.SPAWNER -- so this quotes them from the audited per-type price instead, but the flow a player
     *  sees is identical. */
    private static final int BASKET_INPUT_END = 45;

    private record BasketHolder(boolean dummy) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    void openSellBasket(Player player) {
        Inventory inv = plugin.getServer().createInventory(new BasketHolder(true), 54,
                Component.text("Sell Basket", CoreUtil.EMBER));
        inv.setItem(CoreUtil.Menu.SELL_CANCEL, CoreUtil.Menu.cancel("Every spawner comes straight back to you."));
        inv.setItem(CoreUtil.Menu.SELL_TOTAL, basketTotal(0, 0));
        inv.setItem(CoreUtil.Menu.SELL_CONFIRM, basketConfirm(0, 0));
        player.openInventory(inv);
    }

    private ItemStack basketTotal(int count, double value) {
        List<String> lore = new ArrayList<>();
        lore.add(CoreUtil.C_TEXT + count + CoreUtil.C_BODY + " spawner" + (count == 1 ? "" : "s") + " in the basket");
        lore.add(CoreUtil.C_MUTE + "Each pays a fifth of its buy price.");
        if (plugin.bank().deficit()) lore.add(CoreUtil.C_WARN + "Central Bank deficit — payouts are halved.");
        return CoreUtil.Menu.heading(Material.GOLD_INGOT, "Sale total  " + CoreUtil.money(value), lore);
    }

    /** The button that takes the spawners says what it pays for them. */
    private ItemStack basketConfirm(int count, double value) {
        if (count <= 0) return CoreUtil.Menu.blocked(Material.GRAY_CONCRETE, "Sell",
                "the basket is empty.", List.of("Drop spawners into the top rows.",
                        CoreUtil.C_MUTE + "Anything that is not a spawner is handed back."));
        return CoreUtil.Menu.confirm(CoreUtil.money(value), List.of(
                CoreUtil.C_BODY + "Sells " + CoreUtil.C_TEXT + count + CoreUtil.C_BODY + " spawner" + (count == 1 ? "" : "s"),
                CoreUtil.C_MUTE + "Anything else in the basket is handed back."));
    }

    /** What the basket is currently worth, and how many sellable spawners are in it. */
    private double[] quoteBasket(Inventory inv) {
        int count = 0;
        double value = 0;
        for (int slot = 0; slot < BASKET_INPUT_END; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() != Material.SPAWNER) continue;
            EntityType type = plugin.spawners() == null ? null : plugin.spawners().typeOf(item);
            if (type == null) continue;
            count += item.getAmount();
            value += sellPrice(type) * item.getAmount();
        }
        return new double[]{count, Math.round(value * 100) / 100.0};
    }

    @EventHandler public void basketClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof BasketHolder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int raw = event.getRawSlot();
        /** The top five rows and the player's own inventory stay freely usable -- that is the whole point of
         *  a basket. Only the control bar is locked. */
        if (raw >= BASKET_INPUT_END && raw < 54) {
            event.setCancelled(true);
            if (raw == CoreUtil.Menu.SELL_CANCEL) { plugin.settings().uiSound(player, "cancel"); player.closeInventory(); }
            else if (raw == CoreUtil.Menu.SELL_CONFIRM) confirmBasket(player, event.getInventory());
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof BasketHolder) {
                double[] quote = quoteBasket(player.getOpenInventory().getTopInventory());
                org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
                top.setItem(CoreUtil.Menu.SELL_TOTAL, basketTotal((int) quote[0], quote[1]));
                top.setItem(CoreUtil.Menu.SELL_CONFIRM, basketConfirm((int) quote[0], quote[1]));
            }
        });
    }

    @EventHandler public void basketDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof BasketHolder)) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= BASKET_INPUT_END && slot < 54)) { event.setCancelled(true); return; }
        if (!(event.getWhoClicked() instanceof Player player)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof BasketHolder) {
                double[] quote = quoteBasket(player.getOpenInventory().getTopInventory());
                org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
                top.setItem(CoreUtil.Menu.SELL_TOTAL, basketTotal((int) quote[0], quote[1]));
                top.setItem(CoreUtil.Menu.SELL_CONFIRM, basketConfirm((int) quote[0], quote[1]));
            }
        });
    }

    /** Closing the basket hands everything back. Nothing is ever kept without being paid for. */
    @EventHandler public void basketClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof BasketHolder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        returnBasket(player, event.getInventory());
    }

    private void returnBasket(Player player, Inventory inv) {
        for (int slot = 0; slot < BASKET_INPUT_END; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            inv.setItem(slot, null);
            CoreUtil.give(player, item);
        }
    }

    /** Pays for every spawner in the basket, returns anything that is not one, and puts what was sold back
     *  into stock so buying and selling remain two halves of one pool. The money is paid FIRST; only then are
     *  the items taken, so a treasury that cannot cover the sale can never eat somebody's spawners. */
    private void confirmBasket(Player player, Inventory inv) {
        double[] quote = quoteBasket(inv);
        int count = (int) quote[0];
        double value = quote[1];
        if (count <= 0) {
            CoreUtil.error(player, "Put spawners in the basket first.");
            plugin.settings().uiSound(player, "error");
            return;
        }
        if (!plugin.bank().payShopSeller(player, value, "SPAWNER_SHOP")) {
            CoreUtil.error(player, "The Central Bank treasury cannot cover this sale yet.");
            plugin.settings().uiSound(player, "error");
            return;
        }
        for (int slot = 0; slot < BASKET_INPUT_END; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() != Material.SPAWNER) continue;
            EntityType type = plugin.spawners() == null ? null : plugin.spawners().typeOf(item);
            if (type == null) continue;
            db.spawnerShopAdd(type.name(), item.getAmount());
            inv.setItem(slot, null);
        }
        db.recordEconomy(CoreUtil.id(player), "SPAWNER_SHOP_SALE", value, count + " spawner(s)");
        db.history(CoreUtil.id(player), null, "SPAWNER_SHOP",
                player.getName() + " sold " + count + " spawner(s) for " + CoreUtil.money(value) + ".");
        CoreUtil.msg(player, "Sold " + count + " spawner" + (count == 1 ? "" : "s") + " for " + CoreUtil.money(value) + ".");
        plugin.settings().uiSound(player, "success");
        /** Anything that was not a spawner goes back, then straight to the shop -- the same return-to-shop
         *  flow the normal sell basket uses. */
        returnBasket(player, inv);
        plugin.getServer().getScheduler().runTask(plugin, () -> { if (player.isOnline()) open(player); });
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
