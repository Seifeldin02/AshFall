package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Ashfall's own buy-order market. Replaces the DonutOrders bridge outright.
 *
 *  WHY IT IS NATIVE. DonutOrders keyed orders by Material. Every spawner is Material.SPAWNER, so a "Blaze
 *  Spawner" order could be filled with a Cave Spider one and there was no way to express the difference.
 *  Its storage was actually fine (a serialised ItemStack per order) -- the limitation was the creation flow
 *  and the API it exposed. Rather than keep working around that, orders now carry a CANONICAL IDENTITY.
 *
 *  IDENTITY. A key is a stable string that names exactly what will satisfy the order:
 *      vanilla:DIAMOND                     an ordinary, unmodified diamond
 *      vanilla:ENCHANTED_BOOK/SHARPNESS/5  a book storing exactly that one enchantment at that level
 *      smpcore:spawner/BLAZE               an SMPCore Blaze spawner, by its persistent type, not its name
 *  Matching is by identity and never by display name, so renaming an item cannot make it pass, and a
 *  spawner of the wrong type can never fill another type's order. Spawner types come from SpawnerService's
 *  own value registry, so a type added there later becomes orderable with no change here.
 *
 *  ESCROW AND CONSERVATION, which is the part that actually matters. Money is taken from the buyer once, at
 *  creation, and lives in the order row. Every later movement is a CONDITIONAL UPDATE that both checks and
 *  mutates in one statement -- the same reservation pattern the finite shop stock uses:
 *
 *    fulfilment  UPDATE ... SET filled=filled+q, escrow=escrow-cost
 *                WHERE id=? AND status='ACTIVE' AND filled+q<=amount AND escrow>=cost
 *    cancel      UPDATE ... SET status='CANCELLED', escrow=0 WHERE id=? AND status='ACTIVE' AND escrow=?
 *
 *  A statement that changes zero rows means somebody else got there first, and the caller does nothing. The
 *  cancel/expire compare-and-swap on the escrow value is what makes a double refund impossible: the second
 *  attempt cannot match the amount it read. Items are removed from the seller only AFTER the reservation
 *  succeeds, and a short removal reverses the reservation and hands back exactly what was taken.
 *
 *  Nothing is held in memory that matters. Orders, escrow and undelivered goods all live in the database,
 *  so a restart, a logout or a crash resumes from the same state. */
final class OrdersService implements Listener {

    private static final String VANILLA = "vanilla:", SPAWNER = "smpcore:spawner/", BOOK = "vanilla:ENCHANTED_BOOK/", ENCHANTED = "enchanted:";

    /** Which screen an inventory belongs to, so one click handler can serve them all. */
    private enum Screen { HUB, PUBLIC, MINE, HISTORY, CATEGORY, PICK, CONFIRM, STASH, DELIVER, ENCHANT }
    /** Insertable area of the delivery screen: the top three rows, and nothing else. */
    private static final int DELIVER_SLOTS = 27;

    private final class Holder implements InventoryHolder {
        private final Screen screen;
        private final int page;
        private final String search;
        private final long orderId;
        private Inventory inv;
        /** Consequential actions arm on the first click and commit on the second, so nothing money-moving
         *  or item-moving can happen by accident. Cleared whenever the screen is repainted. */
        private long armedAt;
        private int armedSlot = -1;
        private String category;
        private Holder(Screen screen, int page, String search, long orderId) {
            this.screen = screen; this.page = page; this.search = search; this.orderId = orderId;
        }
        @Override public Inventory getInventory() { return inv; }
    }

    /** A half-built order, while the player is being asked for numbers. */
    private static final class Draft {
        private String key;
        private int amount;
        private double unit;
    }

    /** A base item plus the custom enchantments being chosen for it, before the amount/price prompts. */
    private static final class EnchantDraft {
        private Material base;
        private final Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
    }

    private final SMPCore plugin;
    private final Database db;
    /** Chat prompts in flight. Cleared on use, on cancel and on quit; nothing else depends on them. */
    private final Map<UUID, Consumer<String>> prompts = new LinkedHashMap<>();
    private final Map<UUID, Draft> drafts = new LinkedHashMap<>();
    private final Map<UUID, EnchantDraft> enchantDrafts = new LinkedHashMap<>();
    private List<String> catalogue = List.of();
    private BukkitTask expiryTask;

    OrdersService(SMPCore plugin) {
        this.plugin = plugin;
        this.db = plugin.db();
        Bukkit.getScheduler().runTask(plugin, this::buildCatalogue);
        long period = Math.max(20, plugin.getConfig().getLong("orders.expiry-check-seconds", 60)) * 20L;
        expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::expireDue, period, period);
    }

    void shutdown() { if (expiryTask != null) expiryTask.cancel(); }

    // ------------------------------------------------------------------ catalogue and identity
    /** Every orderable key. Built once: the vanilla item registry, one entry per enchantment level for
     *  books, and every spawner type SpawnerService knows about. */
    private void buildCatalogue() {
        List<String> keys = new ArrayList<>();
        for (Material material : Material.values())
            if (material.isItem() && !material.isAir() && !material.isLegacy() && material != Material.ENCHANTED_BOOK)
                keys.add(VANILLA + material.name());
        for (Enchantment enchantment : org.bukkit.Registry.ENCHANTMENT)
            for (int level = 1; level <= enchantment.getMaxLevel(); level++)
                keys.add(BOOK + enchantment.getKey().getKey().toUpperCase(Locale.ROOT) + "/" + level);
        for (EntityType type : plugin.spawners().orderableTypes()) keys.add(SPAWNER + type.name());
        keys.sort(String::compareTo);
        catalogue = List.copyOf(keys);
        plugin.getLogger().info("[Orders] catalogue: " + catalogue.size() + " orderable items, including "
                + plugin.spawners().orderableTypes().size() + " spawner types.");
    }

    /** The item one unit of this order IS. Also the icon, and the thing a delivery is compared against. */
    ItemStack canonical(String key) {
        if (key == null) return null;
        if (key.startsWith(SPAWNER)) {
            EntityType type = entityType(key.substring(SPAWNER.length()));
            return type == null ? null : plugin.spawners().orderItem(type);
        }
        if (key.startsWith(BOOK)) {
            String[] parts = key.substring(BOOK.length()).split("/");
            if (parts.length != 2) return null;
            Enchantment enchantment = enchantment(parts[0]);
            int level;
            try { level = Integer.parseInt(parts[1]); } catch (NumberFormatException error) { return null; }
            if (enchantment == null) return null;
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
            meta.addStoredEnchant(enchantment, level, true);
            book.setItemMeta(meta);
            return book;
        }
        if (key.startsWith(ENCHANTED)) {
            Material material = enchantedMaterial(key);
            if (material == null || !material.isItem()) return null;
            ItemStack item = new ItemStack(material);
            for (Map.Entry<Enchantment, Integer> entry : enchantedEnchants(key).entrySet()) item.addUnsafeEnchantment(entry.getKey(), entry.getValue());
            return item;
        }
        if (key.startsWith(VANILLA)) {
            Material material = Material.matchMaterial(key.substring(VANILLA.length()));
            return material == null || !material.isItem() ? null : new ItemStack(material);
        }
        return null;
    }

    private Enchantment enchantment(String name) {
        return org.bukkit.Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
    }

    private EntityType entityType(String name) {
        try { return EntityType.valueOf(name); } catch (IllegalArgumentException error) { return null; }
    }

    // ------------------------------------------------------------------ enchanted-item orders
    private Material enchantedMaterial(String key) {
        String body = key.substring(ENCHANTED.length());
        int slash = body.indexOf('/');
        return Material.matchMaterial(slash < 0 ? body : body.substring(0, slash));
    }

    private Map<Enchantment, Integer> enchantedEnchants(String key) {
        Map<Enchantment, Integer> map = new LinkedHashMap<>();
        String[] parts = key.substring(ENCHANTED.length()).split("/");
        for (int i = 1; i + 1 < parts.length; i += 2) {
            Enchantment enchantment = enchantment(parts[i]);
            int level;
            try { level = Integer.parseInt(parts[i + 1]); } catch (NumberFormatException error) { continue; }
            if (enchantment != null) map.put(enchantment, level);
        }
        return map;
    }

    /** Exactly the ordered TYPE carrying EXACTLY the ordered enchantments -- display name and lore are ignored
     *  (a renamed item still counts), but any custom persistent data (relics, bound items, custom spawners) is
     *  rejected, and a damaged item is refused so the buyer gets what they paid for. */
    private boolean matchesEnchanted(String key, ItemStack stack) {
        Material material = enchantedMaterial(key);
        if (material == null || stack.getType() != material) return false;
        if (!stack.getEnchantments().equals(enchantedEnchants(key))) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().getKeys().isEmpty()) return false;
        return !(meta instanceof org.bukkit.inventory.meta.Damageable damage && damage.hasDamage());
    }

    /** A base item is enchantable if the vanilla rules allow at least one enchantment on it -- the same realism
     *  filter the picker uses, so unrealistic enchants (Efficiency on a sword, Sharpness on a pickaxe) can never
     *  be chosen. Books are excluded; enchanted books have their own order type. */
    private boolean isEnchantableBase(Material material) {
        if (material == null || material == Material.ENCHANTED_BOOK || material == Material.BOOK) return false;
        ItemStack probe = new ItemStack(material);
        for (Enchantment enchantment : org.bukkit.Registry.ENCHANTMENT) if (enchantment.canEnchantItem(probe)) return true;
        return false;
    }

    private List<Enchantment> applicableEnchants(Material material) {
        ItemStack probe = new ItemStack(material);
        List<Enchantment> list = new ArrayList<>();
        for (Enchantment enchantment : org.bukkit.Registry.ENCHANTMENT) if (enchantment.canEnchantItem(probe)) list.add(enchantment);
        list.sort(java.util.Comparator.comparing(enchantment -> enchantment.getKey().getKey()));
        return list;
    }

    private boolean conflictsWithSelected(Enchantment candidate, Map<Enchantment, Integer> selected) {
        for (Enchantment other : selected.keySet())
            if (!other.equals(candidate) && (candidate.conflictsWith(other) || other.conflictsWith(candidate))) return true;
        return false;
    }

    /** Canonical, sorted key so the same enchant set always produces the same identity string. */
    private String composeEnchantedKey(Material material, Map<Enchantment, Integer> enchants) {
        List<Enchantment> ordered = new ArrayList<>(enchants.keySet());
        ordered.sort(java.util.Comparator.comparing(enchantment -> enchantment.getKey().getKey()));
        StringBuilder sb = new StringBuilder(ENCHANTED).append(material.name());
        for (Enchantment enchantment : ordered)
            sb.append('/').append(enchantment.getKey().getKey().toUpperCase(Locale.ROOT)).append('/').append(enchants.get(enchantment));
        return sb.toString();
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V";
            case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII"; case 9 -> "IX"; case 10 -> "X";
            default -> String.valueOf(n);
        };
    }

    /** From the item picker: a plain enchantable base opens the enchant chooser first; everything else goes
     *  straight to the amount/price prompts. */
    private void startOrderFor(Player player, String key) {
        if (key.startsWith(VANILLA)) {
            Material material = Material.matchMaterial(key.substring(VANILLA.length()));
            if (isEnchantableBase(material)) {
                EnchantDraft draft = new EnchantDraft();
                draft.base = material;
                enchantDrafts.put(player.getUniqueId(), draft);
                openEnchantPicker(player);
                return;
            }
        }
        beginDraft(player, key);
    }

    /** The enchant chooser: one book per enchantment the item can legally take. Left-click raises the level,
     *  right-click lowers/removes it, and an enchant that conflicts with a chosen one locks out. Confirming with
     *  none selected simply orders the item plain. */
    private void openEnchantPicker(Player player) {
        EnchantDraft draft = enchantDrafts.get(player.getUniqueId());
        if (draft == null || draft.base == null) { openPick(player, 1, null); return; }
        Holder holder = new Holder(Screen.ENCHANT, 1, null, 0);
        holder.inv = plugin.getServer().createInventory(holder, 54, Component.text("Enchant " + CoreUtil.pretty(draft.base.name()), NamedTextColor.DARK_AQUA));
        List<Enchantment> applicable = applicableEnchants(draft.base);
        for (int i = 0; i < applicable.size() && i < 45; i++) {
            Enchantment enchantment = applicable.get(i);
            int level = draft.enchants.getOrDefault(enchantment, 0);
            boolean blocked = level == 0 && conflictsWithSelected(enchantment, draft.enchants);
            String name = CoreUtil.pretty(enchantment.getKey().getKey());
            Material icon = level > 0 ? Material.ENCHANTED_BOOK : (blocked ? Material.GRAY_DYE : Material.BOOK);
            List<String> lore = new ArrayList<>();
            if (blocked) lore.add("Conflicts with a chosen enchant");
            else if (level > 0) { lore.add("Selected: " + roman(level) + "  (max " + roman(enchantment.getMaxLevel()) + ")"); lore.add("Left-click: raise   Right-click: lower"); }
            else { lore.add("Not selected  (max " + roman(enchantment.getMaxLevel()) + ")"); lore.add("Left-click to add"); }
            holder.inv.setItem(i, CoreUtil.named(icon, name + (level > 0 ? " " + roman(level) : ""), lore));
        }
        for (int slot = 45; slot < 54; slot++) holder.inv.setItem(slot, filler());
        holder.inv.setItem(45, CoreUtil.named(Material.ARROW, "Back", List.of("Item picker")));
        holder.inv.setItem(48, CoreUtil.named(Material.CHEST, "Order it plain", List.of("No enchantments")));
        String composed = draft.enchants.isEmpty() ? VANILLA + draft.base.name() : composeEnchantedKey(draft.base, draft.enchants);
        ItemStack preview = canonical(composed);
        if (preview == null) preview = new ItemStack(draft.base);
        ItemMeta pm = preview.getItemMeta();
        if (pm != null) {
            pm.displayName(Component.text(display(composed), NamedTextColor.GOLD));
            pm.lore(List.of(Component.text(draft.enchants.size() + " enchantment(s) chosen", NamedTextColor.GRAY)));
            preview.setItemMeta(pm);
        }
        holder.inv.setItem(49, preview);
        holder.inv.setItem(50, CoreUtil.named(Material.LIME_CONCRETE, "Confirm & set amount",
                List.of(draft.enchants.isEmpty() ? "No enchants — orders it plain" : draft.enchants.size() + " enchant(s) chosen", "Then choose amount and price")));
        player.openInventory(holder.inv);
    }

    private void handleEnchantClick(Player player, InventoryClickEvent event, int slot) {
        EnchantDraft draft = enchantDrafts.get(player.getUniqueId());
        if (draft == null || draft.base == null) { openPick(player, 1, null); return; }
        if (slot == 45) { enchantDrafts.remove(player.getUniqueId()); openPick(player, 1, null); return; }
        if (slot == 48) { enchantDrafts.remove(player.getUniqueId()); beginDraft(player, VANILLA + draft.base.name()); return; }
        if (slot == 50) {
            String key = draft.enchants.isEmpty() ? VANILLA + draft.base.name() : composeEnchantedKey(draft.base, draft.enchants);
            enchantDrafts.remove(player.getUniqueId());
            beginDraft(player, key);
            return;
        }
        if (slot >= 45) return;
        List<Enchantment> applicable = applicableEnchants(draft.base);
        if (slot >= applicable.size()) return;
        Enchantment enchantment = applicable.get(slot);
        int level = draft.enchants.getOrDefault(enchantment, 0);
        if (event.isRightClick()) {
            if (level <= 1) draft.enchants.remove(enchantment); else draft.enchants.put(enchantment, level - 1);
        } else if (level == 0) {
            if (conflictsWithSelected(enchantment, draft.enchants)) { CoreUtil.error(player, CoreUtil.pretty(enchantment.getKey().getKey()) + " conflicts with an enchant you already chose."); return; }
            draft.enchants.put(enchantment, 1);
        } else if (level < enchantment.getMaxLevel()) draft.enchants.put(enchantment, level + 1);
        else { CoreUtil.error(player, CoreUtil.pretty(enchantment.getKey().getKey()) + " is already at its maximum (" + roman(enchantment.getMaxLevel()) + ")."); return; }
        openEnchantPicker(player);
    }

    /** Does this stack satisfy that order?
     *
     *  Spawners are judged by their persistent type, so a renamed Blaze spawner still counts and a Cave
     *  Spider spawner never does. Everything else must be the plain canonical item -- isSimilar against a
     *  freshly built one rejects renamed, enchanted, damaged and PDC-tagged stacks, which is what stops a
     *  relic or a bound item being handed over to fill an order for its base material. */
    boolean matches(String key, ItemStack stack) {
        if (key == null || stack == null || stack.getType().isAir()) return false;
        if (key.startsWith(SPAWNER)) {
            EntityType wanted = entityType(key.substring(SPAWNER.length()));
            return wanted != null && wanted == plugin.spawners().typeOf(stack);
        }
        /** A spawner must never satisfy a plain vanilla:SPAWNER order either. */
        if (stack.getType() == Material.SPAWNER && plugin.spawners().typeOf(stack) != null) return false;
        if (key.startsWith(ENCHANTED)) return matchesEnchanted(key, stack);
        ItemStack want = canonical(key);
        return want != null && stack.isSimilar(want);
    }

    /** Marketplace categories, so the create picker is organised instead of dumping the whole registry. */
    enum Cat {
        SPAWNERS("Spawners", Material.SPAWNER), BOOKS("Enchanted Books", Material.ENCHANTED_BOOK),
        ORES("Ores & Minerals", Material.DIAMOND), COMBAT("Combat & Tools", Material.DIAMOND_SWORD),
        FOOD("Food & Farming", Material.BREAD), REDSTONE("Redstone & Mechanisms", Material.REDSTONE),
        BLOCKS("Blocks", Material.BRICKS), MISC("Everything Else", Material.CHEST);
        final String label; final Material icon;
        Cat(String label, Material icon) { this.label = label; this.icon = icon; }
    }

    Cat categoryOf(String key) {
        if (key.startsWith(ENCHANTED)) { Material m = enchantedMaterial(key); return m == null ? Cat.MISC : categoryOf(VANILLA + m.name()); }
        if (key.startsWith(SPAWNER)) return Cat.SPAWNERS;
        if (key.startsWith(BOOK)) return Cat.BOOKS;
        Material m = Material.matchMaterial(key.substring(VANILLA.length()));
        if (m == null) return Cat.MISC;
        String n = m.name();
        if (n.contains("ORE") || n.contains("INGOT") || n.contains("NUGGET") || n.startsWith("RAW_")
                || n.equals("DIAMOND") || n.equals("EMERALD") || n.equals("COAL") || n.equals("REDSTONE")
                || n.equals("LAPIS_LAZULI") || n.equals("QUARTZ") || n.contains("AMETHYST") || n.contains("ANCIENT_DEBRIS")
                || n.contains("NETHERITE_SCRAP")) return Cat.ORES;
        if (n.endsWith("SWORD") || n.endsWith("_AXE") || n.endsWith("PICKAXE") || n.endsWith("SHOVEL") || n.endsWith("_HOE")
                || n.endsWith("HELMET") || n.endsWith("CHESTPLATE") || n.endsWith("LEGGINGS") || n.endsWith("BOOTS")
                || n.contains("BOW") || n.equals("ARROW") || n.contains("SHIELD") || n.contains("TRIDENT")
                || n.equals("MACE") || n.contains("TOTEM") || n.contains("SHELL")) return Cat.COMBAT;
        if (m.isEdible() || n.contains("SEED") || n.contains("WHEAT") || n.contains("CARROT") || n.contains("POTATO")
                || n.contains("BEETROOT") || n.contains("MELON") || n.contains("PUMPKIN") || n.contains("SUGAR")
                || n.contains("CANE") || n.contains("KELP") || n.contains("COCOA") || n.contains("BERR")
                || n.contains("APPLE") || n.contains("BREAD") || n.contains("EGG") || n.contains("SAPLING")
                || n.contains("MUSHROOM")) return Cat.FOOD;
        if (n.contains("REDSTONE") || n.contains("PISTON") || n.contains("REPEATER") || n.contains("COMPARATOR")
                || n.contains("OBSERVER") || n.contains("HOPPER") || n.contains("DISPENSER") || n.contains("DROPPER")
                || n.contains("LEVER") || n.contains("BUTTON") || n.contains("PRESSURE_PLATE") || n.contains("RAIL")
                || n.equals("TARGET") || n.contains("TNT") || n.contains("DAYLIGHT")) return Cat.REDSTONE;
        if (m.isBlock()) return Cat.BLOCKS;
        return Cat.MISC;
    }

    private List<String> inCategory(Cat cat, String search) {
        List<String> out = new ArrayList<>();
        String needle = search == null ? null : search.toLowerCase(Locale.ROOT);
        for (String key : catalogue) {
            if (categoryOf(key) != cat) continue;
            if (needle != null && !display(key).toLowerCase(Locale.ROOT).contains(needle)) continue;
            out.add(key);
        }
        return out;
    }

    // ------------------------------------------------------------------ marketplace hub + sections
    void openHub(Player player) {
        Holder holder = new Holder(Screen.HUB, 1, null, 0);
        holder.inv = plugin.getServer().createInventory(holder, 27, Component.text("Orders • Marketplace", NamedTextColor.DARK_AQUA));
        for (int i = 0; i < 27; i++) holder.inv.setItem(i, filler());
        int active = (int) db.ordersOf(CoreUtil.id(player)).stream().filter(r -> r.status().equals("ACTIVE")).count();
        holder.inv.setItem(10, CoreUtil.named(Material.COMPASS, "Browse & fulfil orders", List.of("See every open buy order", "Deliver items and get paid")));
        holder.inv.setItem(11, CoreUtil.named(Material.WRITABLE_BOOK, "Create an order", List.of("Place a new buy order", "Browse by category")));
        holder.inv.setItem(13, CoreUtil.named(Material.CHEST, "My active orders", List.of(active + " active", "Cancel and refund")));
        int waiting = db.stashCount(CoreUtil.id(player));
        holder.inv.setItem(15, waiting > 0
                ? CoreUtil.Menu.action(Material.ENDER_CHEST, "Claim deliveries", List.of(
                        CoreUtil.C_TEXT + waiting + CoreUtil.C_BODY + " stack" + (waiting == 1 ? "" : "s") + " waiting for you",
                        "Auction purchases, expired listings and rewards that",
                        "would not fit at the time.",
                        CoreUtil.C_MUTE + "Nothing here expires."))
                : CoreUtil.Menu.info(Material.ENDER_CHEST, "Claim deliveries", List.of(
                        CoreUtil.C_MUTE + "Nothing is waiting for you.")));
        holder.inv.setItem(16, CoreUtil.named(Material.BOOK, "Order history", List.of("Completed, cancelled, expired", "Hide entries you are done with")));
        player.openInventory(holder.inv);
    }

    void openCategories(Player player) {
        Holder holder = new Holder(Screen.CATEGORY, 1, null, 0);
        holder.inv = plugin.getServer().createInventory(holder, 45, Component.text("Create • Pick a category", NamedTextColor.DARK_AQUA));
        for (int i = 36; i < 45; i++) holder.inv.setItem(i, filler());
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19};
        Cat[] cats = Cat.values();
        for (int i = 0; i < cats.length && i < slots.length; i++) {
            int count = inCategory(cats[i], null).size();
            holder.inv.setItem(slots[i], CoreUtil.named(cats[i].icon, cats[i].label, List.of(count + " item(s)", "Click to browse")));
        }
        holder.inv.setItem(40, CoreUtil.named(Material.ARROW, "Back", List.of("Marketplace")));
        player.openInventory(holder.inv);
    }

    String display(String key) {
        if (key == null) return "?";
        if (key.startsWith(SPAWNER)) return CoreUtil.pretty(key.substring(SPAWNER.length())) + " Spawner";
        if (key.startsWith(BOOK)) {
            String[] parts = key.substring(BOOK.length()).split("/");
            return CoreUtil.pretty(parts[0]) + " " + (parts.length > 1 ? parts[1] : "") + " Book";
        }
        if (key.startsWith(ENCHANTED)) {
            Material material = enchantedMaterial(key);
            StringBuilder sb = new StringBuilder(material == null ? "?" : CoreUtil.pretty(material.name()));
            boolean first = true;
            for (Map.Entry<Enchantment, Integer> entry : enchantedEnchants(key).entrySet()) {
                sb.append(first ? " (" : ", "); first = false;
                sb.append(CoreUtil.pretty(entry.getKey().getKey().getKey())).append(' ').append(roman(entry.getValue()));
            }
            if (!first) sb.append(')');
            return sb.toString();
        }
        return CoreUtil.pretty(key.substring(key.indexOf(':') + 1));
    }

    // ------------------------------------------------------------------ screens
    private Inventory open(Player player, Screen screen, int page, String search, long orderId, String title, int size) {
        Holder holder = new Holder(screen, page, search, orderId);
        holder.inv = plugin.getServer().createInventory(holder, size, Component.text(title, NamedTextColor.DARK_AQUA));
        return holder.inv;
    }

    void openPublic(Player player) { openPublic(player, 1, null); }

    void openPublic(Player player, int page, String search) {
        List<Database.OrderRow> rows = db.ordersActive(search);
        Inventory inv = open(player, Screen.PUBLIC, page, search, 0, "Orders • Buying", 54);
        paint(inv, rows, page, (row, slot) -> inv.setItem(slot, orderIcon(row, true, player)));
        for (int slot = 45; slot < 54; slot++) inv.setItem(slot, filler());
        inv.setItem(45, CoreUtil.named(Material.COMPASS, "Search", List.of(search == null ? "Showing everything" : "Showing: " + search, "Click to search")));
        inv.setItem(47, CoreUtil.named(Material.WRITABLE_BOOK, "Create an order", List.of("Place a new buy order")));
        inv.setItem(49, CoreUtil.named(Material.CHEST, "Your orders", List.of("Active orders and history")));
        /*  The one a player actually reaches -- /orders opens this board, not the hub. It said
         *  "0 stack(s) waiting" and looked as clickable as everything else beside it. */
        int waiting = db.stashCount(CoreUtil.id(player));
        inv.setItem(51, waiting > 0
                ? CoreUtil.Menu.action(Material.ENDER_CHEST, "Claim deliveries", List.of(
                        CoreUtil.C_TEXT + waiting + CoreUtil.C_BODY + " stack" + (waiting == 1 ? "" : "s") + " waiting for you",
                        CoreUtil.C_MUTE + "Nothing in here expires."))
                : CoreUtil.Menu.info(Material.ENDER_CHEST, "Claim deliveries", List.of(
                        CoreUtil.C_MUTE + "Nothing is waiting for you.")));
        inv.setItem(53, CoreUtil.named(Material.PAPER, "Page " + page, List.of(rows.size() + " open order(s)")));
        pageNav(inv, page, rows.size());
        player.openInventory(inv);
    }

    void openMine(Player player, int page) {
        List<Database.OrderRow> rows = db.ordersOf(CoreUtil.id(player));
        Inventory inv = open(player, Screen.MINE, page, null, 0, "Orders • Yours", 54);
        paint(inv, rows, page, (row, slot) -> inv.setItem(slot, orderIcon(row, false, player)));
        for (int slot = 45; slot < 54; slot++) if (inv.getItem(slot) == null) inv.setItem(slot, filler());
        for (int slot = 45; slot < 54; slot++) if (inv.getItem(slot) == null) inv.setItem(slot, filler());
        inv.setItem(45, CoreUtil.named(Material.ARROW, "Back", List.of("Orders")));
        inv.setItem(53, CoreUtil.named(Material.PAPER, "Your orders", List.of(rows.size() + " order(s)", "Active: cancel & refund", "Finished: click to hide")));
        pageNav(inv, page, rows.size());
        player.openInventory(inv);
    }

    void openHistory(Player player, int page) {
        List<Database.OrderRow> rows = db.ordersOf(CoreUtil.id(player)).stream().filter(r -> !r.status().equals("ACTIVE")).toList();
        Inventory inv = open(player, Screen.HISTORY, page, null, 0, "Orders • History", 54);
        paint(inv, rows, page, (row, slot) -> inv.setItem(slot, orderIcon(row, false, player)));
        for (int slot = 45; slot < 54; slot++) if (inv.getItem(slot) == null) inv.setItem(slot, filler());
        inv.setItem(45, CoreUtil.named(Material.ARROW, "Back", List.of("Marketplace")));
        inv.setItem(49, CoreUtil.named(Material.PAPER, "Order history", List.of(rows.size() + " past order(s)", "Click one to hide it")));
        navigation(inv, page, rows.size());
        player.openInventory(inv);
    }

    void openCategoryItems(Player player, Cat cat, int page, String search) {
        List<String> keys = inCategory(cat, search);
        Holder holder = new Holder(Screen.PICK, page, search, 0);
        holder.category = cat.name();
        holder.inv = plugin.getServer().createInventory(holder, 54, Component.text(cat.label + (search == null ? "" : " • " + search), NamedTextColor.DARK_AQUA));
        int from = (page - 1) * 45;
        for (int i = 0; i < 45 && from + i < keys.size(); i++) {
            String key = keys.get(from + i);
            ItemStack ico = canonical(key);
            if (ico == null) continue;
            ItemMeta meta = ico.getItemMeta();
            meta.displayName(Component.text(display(key), NamedTextColor.GOLD));
            meta.lore(List.of(Component.text("Click to order this", NamedTextColor.GRAY)));
            ico.setItemMeta(meta);
            holder.inv.setItem(i, ico);
        }
        for (int slot = 45; slot < 54; slot++) holder.inv.setItem(slot, filler());
        holder.inv.setItem(45, CoreUtil.named(Material.COMPASS, "Search", List.of(search == null ? "Search this category" : "Showing: " + search)));
        holder.inv.setItem(49, CoreUtil.named(Material.ARROW, "Back", List.of("Categories")));
        holder.inv.setItem(53, CoreUtil.named(Material.PAPER, "Page " + page, List.of(keys.size() + " item(s)")));
        navigation(holder.inv, page, keys.size());
        player.openInventory(holder.inv);
    }

    void openPick(Player player, int page, String search) { openPick(player, page, search, null); }

    /** The create picker: EVERY orderable item by default. Category is an optional FILTER (cycled with the
     *  filter button), not a separate screen -- clearing it (All) shows the whole catalogue again. */
    void openPick(Player player, int page, String search, String catName) {
        List<String> keys = catName == null ? filtered(search) : inCategory(Cat.valueOf(catName), search);
        Holder holder = new Holder(Screen.PICK, page, search, 0);
        holder.category = catName;
        holder.inv = plugin.getServer().createInventory(holder, 54,
                Component.text("Order \u2022 Choose an item", NamedTextColor.DARK_AQUA));
        int from = (page - 1) * 45;
        for (int i = 0; i < 45 && from + i < keys.size(); i++) {
            String key = keys.get(from + i);
            ItemStack icon = canonical(key);
            if (icon == null) continue;
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text(display(key), NamedTextColor.GOLD));
            meta.lore(List.of(Component.text("Click to order this", NamedTextColor.GRAY)));
            icon.setItemMeta(meta);
            holder.inv.setItem(i, icon);
        }
        for (int slot = 45; slot < 54; slot++) holder.inv.setItem(slot, filler());
        holder.inv.setItem(45, CoreUtil.named(Material.COMPASS, "Search", List.of(search == null ? "Click to search" : "Showing: " + search)));
        holder.inv.setItem(47, CoreUtil.named(Material.HOPPER, "Filter: " + (catName == null ? "All items" : Cat.valueOf(catName).label), List.of("Click to cycle category filter")));
        holder.inv.setItem(49, CoreUtil.named(Material.ARROW, "Back", List.of("Orders")));
        holder.inv.setItem(53, CoreUtil.named(Material.PAPER, "Page " + page, List.of(keys.size() + " item(s)")));
        pageNav(holder.inv, page, keys.size());
        player.openInventory(holder.inv);
    }

    private String nextCategory(String current) {
        Cat[] cats = Cat.values();
        if (current == null) return cats[0].name();
        for (int i = 0; i < cats.length; i++) if (cats[i].name().equals(current)) return i + 1 < cats.length ? cats[i + 1].name() : null;
        return null;
    }

    /** Pagination arrows at 46 (prev) / 52 (next), keeping them clear of the spaced action buttons. */
    private void pageNav(Inventory inv, int page, int total) {
        if (page > 1) inv.setItem(46, CoreUtil.named(Material.SPECTRAL_ARROW, "Previous page", List.of()));
        if (page * 45 < total) inv.setItem(52, CoreUtil.named(Material.SPECTRAL_ARROW, "Next page", List.of()));
    }

    private List<String> filtered(String search) {
        if (search == null || search.isBlank()) return catalogue;
        String needle = search.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String key : catalogue) if (display(key).toLowerCase(Locale.ROOT).contains(needle)) out.add(key);
        return out;
    }

    private interface Painter { void paint(Database.OrderRow row, int slot); }

    private void paint(Inventory inv, List<Database.OrderRow> rows, int page, Painter painter) {
        int from = (page - 1) * 45;
        for (int i = 0; i < 45 && from + i < rows.size(); i++) painter.paint(rows.get(from + i), i);
    }

    private void navigation(Inventory inv, int page, int total) {
        if (page > 1) inv.setItem(50, CoreUtil.named(Material.SPECTRAL_ARROW, "Previous page", List.of()));
        if (page * 45 < total) inv.setItem(51, CoreUtil.named(Material.SPECTRAL_ARROW, "Next page", List.of()));
    }

    private ItemStack orderIcon(Database.OrderRow row, boolean forSeller, Player viewer) {
        ItemStack icon = canonical(row.itemKey());
        if (icon == null) icon = new ItemStack(Material.BARRIER);
        icon = icon.clone();
        ItemMeta meta = icon.getItemMeta();
        boolean active = row.status().equals("ACTIVE");
        int remaining = row.amount() - row.filled();
        meta.displayName(Component.text(remaining + "x " + display(row.itemKey()), active ? NamedTextColor.GOLD : NamedTextColor.GRAY));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Buyer: " + row.buyerName(), NamedTextColor.GRAY));
        lore.add(Component.text("Filled " + row.filled() + " / " + row.amount(), NamedTextColor.GRAY));
        lore.add(Component.text("Unit price: " + CoreUtil.money(row.unit()), NamedTextColor.YELLOW));
        lore.add(Component.text("Value left: " + CoreUtil.money(remaining * row.unit()), NamedTextColor.YELLOW));
        NamedTextColor statusColour = active ? NamedTextColor.GREEN
                : row.status().equals("COMPLETED") ? NamedTextColor.AQUA : NamedTextColor.RED;
        lore.add(Component.text("Status: " + row.status()
                + (active ? "  (expires " + ago(row.expiresAt()) + ")" : ""), statusColour));
        lore.add(Component.empty());
        if (forSeller && active) {
            int carried = viewer == null ? 0 : count(viewer, row.itemKey());
            int fill = Math.min(carried, remaining);
            lore.add(Component.text(carried > 0 ? "You are carrying " + carried + " — can fill " + fill : "You are carrying none",
                    carried > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
            lore.add(Component.text("Click to open the delivery basket", NamedTextColor.GREEN));
        } else if (!forSeller && active) {
            lore.add(Component.text("Escrow held: " + CoreUtil.money(row.escrow()), NamedTextColor.DARK_GRAY));
            lore.add(Component.text("Click to cancel and refund", NamedTextColor.RED));
        } else if (!forSeller) {
            lore.add(Component.text("Click to remove from your history", NamedTextColor.DARK_GRAY));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "order_id"),
                org.bukkit.persistence.PersistentDataType.LONG, row.id());
        icon.setItemMeta(meta);
        return icon;
    }

    /** Relative time, for order expiry. Future = "in 3d", past = "expired". */
    private String ago(long at) {
        long delta = at - System.currentTimeMillis();
        if (delta <= 0) return "any moment";
        long hours = delta / 3600000L;
        if (hours >= 48) return "in " + (hours / 24) + "d";
        if (hours >= 1) return "in " + hours + "h";
        return "in " + Math.max(1, delta / 60000L) + "m";
    }

    /** The delivery screen. The player PUTS items in; nothing is ever pulled out of their inventory for
     *  them. Closing without confirming returns everything, and confirming is a second, deliberate click. */
    void openDeliver(Player player, long orderId) {
        Database.OrderRow row = db.order(orderId);
        if (row == null || !row.status().equals("ACTIVE")) { CoreUtil.error(player, "That order is no longer active."); return; }
        if (row.buyer().equals(CoreUtil.id(player))) { CoreUtil.error(player, "You cannot fill your own order."); return; }
        Holder holder = new Holder(Screen.DELIVER, 1, null, orderId);
        holder.inv = plugin.getServer().createInventory(holder, 54,
                Component.text("Deliver \u2022 " + display(row.itemKey()), NamedTextColor.DARK_AQUA));
        paintDeliver(holder, row, player);
        player.openInventory(holder.inv);
    }

    /** Repaints everything below the insertable rows. Never touches slots 0-26, which belong to the player. */
    private void paintDeliver(Holder holder, Database.OrderRow row, Player player) {
        Inventory inv = holder.inv;
        int inserted = insertedCount(inv, row.itemKey());
        int needed = row.amount() - row.filled();
        int deliverable = Math.min(inserted, needed);
        double gross = Math.round(deliverable * row.unit() * 100) / 100.0;
        double tax = Math.max(0, plugin.getConfig().getDouble("orders.tax-percent", 2.5)) * plugin.bank().feeFactor() / 100.0;
        double net = Math.round(gross * (1 - tax) * 100) / 100.0;
        for (int slot = 27; slot < 54; slot++) inv.setItem(slot, filler());
        inv.setItem(31, CoreUtil.named(Material.PAPER, "Order #" + row.id(),
                List.of("Buyer: " + row.buyerName(),
                        "Wants: " + display(row.itemKey()),
                        "Still needed: " + needed,
                        "Pays: " + CoreUtil.money(row.unit()) + " each")));
        inv.setItem(33, CoreUtil.named(inserted > 0 ? Material.CHEST : Material.BARRIER, "You have inserted " + inserted,
                List.of(deliverable + " of them will be delivered",
                        inserted > needed ? "The extra " + (inserted - needed) + " will be returned" : "Within what the order needs",
                        "Payout: " + CoreUtil.money(net) + (tax > 0 ? " after " + CoreUtil.money(gross - net) + " tax" : ""))));
        boolean armed = holder.armedSlot == 49 && System.currentTimeMillis() - holder.armedAt < 6000;
        inv.setItem(49, deliverable <= 0
                ? CoreUtil.named(Material.GRAY_CONCRETE, "Put items in the top three rows", List.of("Only matching items count"))
                : CoreUtil.named(armed ? Material.YELLOW_CONCRETE : Material.LIME_CONCRETE,
                    armed ? "Click again to confirm" : "Deliver " + deliverable,
                    List.of(armed ? "Delivering " + deliverable + " for " + CoreUtil.money(net) : "You will be asked to confirm")));
        inv.setItem(45, CoreUtil.named(Material.ARROW, "Back", List.of("Returns everything you inserted")));
    }

    private ItemStack filler() { return CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()); }

    private int insertedCount(Inventory inv, String key) {
        int total = 0;
        for (int slot = 0; slot < DELIVER_SLOTS; slot++) {
            ItemStack item = inv.getItem(slot);
            if (matches(key, item)) total += item.getAmount();
        }
        return total;
    }

    /** Hands back everything sitting in the insertable rows. Called on close, on back, and after a partial
     *  delivery for whatever was surplus -- so an item put in here can only ever come back out. */
    private void returnInserted(Player player, Inventory inv) {
        for (int slot = 0; slot < DELIVER_SLOTS; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            inv.setItem(slot, null);
            CoreUtil.give(player, item);
        }
    }

    @EventHandler public void closed(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder holder)) return;
        if (holder.screen != Screen.DELIVER || !(event.getPlayer() instanceof Player player)) return;
        returnInserted(player, event.getInventory());
    }

    /** Commits exactly what was inserted, through the same reservation the rest of the system uses. */
    private void commitDelivery(Player seller, Holder holder) {
        Database.OrderRow row = db.order(holder.orderId);
        if (row == null || !row.status().equals("ACTIVE")) { CoreUtil.error(seller, "That order is no longer active."); returnInserted(seller, holder.inv); seller.closeInventory(); return; }
        int needed = row.amount() - row.filled();
        int qty = Math.min(insertedCount(holder.inv, row.itemKey()), needed);
        if (qty <= 0) { CoreUtil.error(seller, "Nothing matching is inserted."); return; }
        double cost = Math.round(qty * row.unit() * 100) / 100.0;
        if (!db.orderReserve(holder.orderId, qty, cost)) {
            CoreUtil.error(seller, "Somebody just filled that order; nothing was taken.");
            returnInserted(seller, holder.inv); seller.closeInventory(); return;
        }
        /** Take from the SCREEN, not the player's inventory, and only after the reservation succeeded. */
        int remaining = qty;
        for (int slot = 0; slot < DELIVER_SLOTS && remaining > 0; slot++) {
            ItemStack item = holder.inv.getItem(slot);
            if (!matches(row.itemKey(), item)) continue;
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            holder.inv.setItem(slot, item.getAmount() <= 0 ? null : item);
            remaining -= take;
        }
        if (remaining > 0) { db.orderUnreserve(holder.orderId, qty, cost); CoreUtil.error(seller, "Delivery came up short; nothing was charged."); return; }
        double tax = Math.max(0, plugin.getConfig().getDouble("orders.tax-percent", 2.5)) * plugin.bank().feeFactor() / 100.0;
        double fee = Math.round(cost * tax * 100) / 100.0, net = cost - fee;
        db.changeBalance(CoreUtil.id(seller), net);
        if (fee > 0) plugin.bank().creditFee(fee, CoreUtil.id(seller), "ORDER_TAX");
        db.recordEconomy(CoreUtil.id(seller), "ORDER_SALE", net, row.itemKey());
        db.stashAdd(row.buyer(), row.itemKey(), qty, canonical(row.itemKey()));
        db.orderCompleteIfFull(holder.orderId);
        CoreUtil.msg(seller, "Delivered " + qty + "x " + display(row.itemKey()) + " for " + CoreUtil.money(net) + ".");
        Player buyer = plugin.getServer().getPlayer(row.buyer());
        if (buyer != null) CoreUtil.msg(buyer, seller.getName() + " delivered " + qty + "x " + display(row.itemKey()) + " \u2014 /orders to collect.");
        Database.OrderRow after = db.order(holder.orderId);
        if (after == null || !after.status().equals("ACTIVE")) { returnInserted(seller, holder.inv); seller.closeInventory(); }
        else { holder.armedSlot = -1; paintDeliver(holder, after, seller); }
    }

    void openStash(Player player) {
        List<ItemStack> stash = db.stashOf(CoreUtil.id(player));
        Inventory inv = open(player, Screen.STASH, 1, null, 0, "Claims waiting", 54);
        for (int i = 0; i < Math.min(45, stash.size()); i++) inv.setItem(i, stash.get(i));
        for (int slot = 45; slot < 54; slot++) if (inv.getItem(slot) == null) inv.setItem(slot, filler());
        inv.setItem(45, CoreUtil.Menu.back("Back to the order board."));
        /*  Collection can legitimately deliver only part of what is owed now -- a full inventory keeps the
         *  rest of the claim rather than dropping it on the floor -- so the button says what will happen
         *  and the screen has something to say when there is nothing left. */
        if (stash.isEmpty()) {
            inv.setItem(22, CoreUtil.Menu.nothing("Nothing is waiting for you", List.of(
                    "Auction purchases, expired listings and rewards that",
                    "would not fit at the time all end up here.",
                    CoreUtil.C_MUTE + "Nothing in here expires or is lost on a restart.")));
            inv.setItem(49, CoreUtil.Menu.blocked(Material.HOPPER, "Collect", "there is nothing to collect.", List.of()));
        } else {
            inv.setItem(49, CoreUtil.Menu.action(Material.HOPPER, "Collect everything", List.of(
                    CoreUtil.C_TEXT + stash.size() + CoreUtil.C_BODY + " stack" + (stash.size() == 1 ? "" : "s"),
                    CoreUtil.C_MUTE + "Whatever will not fit stays here.")));
        }
        player.openInventory(inv);
    }

    // ------------------------------------------------------------------ clicks
    /** A slot worth making a noise for: something is there, and it is not the grey filler furniture. */
    private static boolean isButton(org.bukkit.inventory.ItemStack clicked) {
        return clicked != null && !clicked.getType().isAir()
                && clicked.getType() != Material.GRAY_STAINED_GLASS_PANE
                && clicked.getType() != Material.BLACK_STAINED_GLASS_PANE;
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        /** Button feedback, on the same shared vocabulary the duel screens use. Only real buttons make a
         *  noise: filler glass and empty slots are furniture, and the deliver screen's own item area is a
         *  container the player is meant to move items in and out of freely. */
        if (holder.screen != Screen.DELIVER && isButton(event.getCurrentItem()) && event.getRawSlot() < event.getInventory().getSize())
            plugin.settings().uiSound(player, "select");
        if (holder.screen == Screen.DELIVER) {
            int raw = event.getRawSlot();
            /** The top three rows and the player's own inventory stay fully interactive -- that is the whole
             *  point of the screen. Everything else is furniture and is refused. */
            if (raw >= DELIVER_SLOTS && raw < 54) {
                event.setCancelled(true);
                if (raw == 45) { player.closeInventory(); Bukkit.getScheduler().runTask(plugin, () -> openPublic(player)); return; }
                if (raw == 49) {
                    Database.OrderRow row = db.order(holder.orderId);
                    if (row == null) { player.closeInventory(); return; }
                    if (holder.armedSlot == 49 && System.currentTimeMillis() - holder.armedAt < 6000) commitDelivery(player, holder);
                    else { holder.armedSlot = 49; holder.armedAt = System.currentTimeMillis(); paintDeliver(holder, row, player); }
                }
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Database.OrderRow row = db.order(holder.orderId);
                if (row != null) { holder.armedSlot = -1; paintDeliver(holder, row, player); }
            });
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;
        ItemStack clicked = event.getCurrentItem();

        switch (holder.screen) {
            case PUBLIC -> {
                switch (slot) {
                    case 45 -> { askSearch(player, holder); return; }
                    case 47 -> { openPick(player, 1, null); return; }
                    case 49 -> { openMine(player, 1); return; }
                    case 51 -> { openStash(player); return; }
                    case 46 -> { openPublic(player, Math.max(1, holder.page - 1), holder.search); return; }
                    case 52 -> { openPublic(player, holder.page + 1, holder.search); return; }
                    default -> { }
                }
            }
            case PICK -> {
                switch (slot) {
                    case 45 -> { askSearch(player, holder); return; }
                    case 47 -> { openPick(player, 1, holder.search, nextCategory(holder.category)); return; }
                    case 49 -> { openPublic(player); return; }
                    case 46 -> { openPick(player, Math.max(1, holder.page - 1), holder.search, holder.category); return; }
                    case 52 -> { openPick(player, holder.page + 1, holder.search, holder.category); return; }
                    default -> { }
                }
                if (slot < 45) {
                    if (clicked == null || clicked.getType().isAir()) return;
                    List<String> keys = holder.category == null ? filtered(holder.search) : inCategory(Cat.valueOf(holder.category), holder.search);
                    int index = (holder.page - 1) * 45 + slot;
                    if (index < keys.size()) startOrderFor(player, keys.get(index));
                }
                return;
            }
            case ENCHANT -> { handleEnchantClick(player, event, slot); return; }
            case STASH -> {
                if (slot == 45) { openPublic(player); return; }
                if (slot == 49) { collect(player); return; }
            }
            case MINE, HISTORY -> {
                if (slot == 45) { openPublic(player); return; }
                if (slot == 46) { reopen(player, holder, Math.max(1, holder.page - 1)); return; }
                if (slot == 52) { reopen(player, holder, holder.page + 1); return; }
            }
            default -> { }
        }
        if (slot >= 45) return;
        if (clicked == null || clicked.getType().isAir()) return;
        Long id = clicked.hasItemMeta() ? clicked.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "order_id"), org.bukkit.persistence.PersistentDataType.LONG) : null;
        if (id == null) return;
        if (holder.screen == Screen.PUBLIC) { openDeliver(player, id); return; }
        if (holder.screen != Screen.MINE && holder.screen != Screen.HISTORY) return;
        Database.OrderRow row = db.order(id);
        if (row == null) return;
        if (!row.status().equals("ACTIVE")) {
            /** Finished orders offer removal from the list instead of cancellation. History only -- the row,
             *  its escrow trail and the ledger entries all stay exactly where they are. */
            if (holder.armedSlot == slot && System.currentTimeMillis() - holder.armedAt < 6000) {
                db.orderHide(id); CoreUtil.msg(player, "Order #" + id + " removed from your list.");
                holder.armedSlot = -1; openMine(player, holder.page);
            } else { holder.armedSlot = slot; holder.armedAt = System.currentTimeMillis();
                CoreUtil.msg(player, "Click again to remove order #" + id + " from your list."); }
            return;
        }
        if (holder.armedSlot == slot && System.currentTimeMillis() - holder.armedAt < 6000) { holder.armedSlot = -1; cancel(player, id); }
        else { holder.armedSlot = slot; holder.armedAt = System.currentTimeMillis();
            CoreUtil.msg(player, "Click again to cancel order #" + id + " and refund " + CoreUtil.money(row.escrow()) + "."); }
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof Holder) event.setCancelled(true);
    }

    private void reopen(Player player, Holder holder, int page) {
        switch (holder.screen) {
            case PUBLIC -> openPublic(player, page, holder.search);
            case MINE -> openMine(player, page);
            case HISTORY -> openHistory(player, page);
            case PICK -> openPick(player, page, holder.search, holder.category);
            default -> { }
        }
    }

    private String plainName(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return null;
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
    }

    // ------------------------------------------------------------------ chat prompts
    private void askSearch(Player player, Holder holder) {
        player.closeInventory();
        CoreUtil.msg(player, "Type what you are looking for in chat, or 'all' to clear.");
        Screen screen = holder.screen;
        String category = holder.category;
        prompts.put(player.getUniqueId(), text -> {
            String search = text.equalsIgnoreCase("all") ? null : text;
            if (screen == Screen.PICK && category != null) openCategoryItems(player, Cat.valueOf(category), 1, search);
            else if (screen == Screen.PICK) openPick(player, 1, search);
            else openPublic(player, 1, search);
        });
    }

    private void beginDraft(Player player, String key) {
        Draft draft = new Draft();
        draft.key = key;
        drafts.put(player.getUniqueId(), draft);
        player.closeInventory();
        CoreUtil.msg(player, "How many " + display(key) + " do you want to buy? Type a number, or 'cancel'.");
        prompts.put(player.getUniqueId(), amountText -> {
            int amount = parsePositive(amountText, 0);
            int max = plugin.getConfig().getInt("orders.max-amount", 3456);
            if (amount <= 0 || amount > max) { CoreUtil.error(player, "Enter a whole number between 1 and " + max + "."); return; }
            draft.amount = amount;
            CoreUtil.msg(player, "What will you pay per item? Type an amount, or 'cancel'.");
            prompts.put(player.getUniqueId(), priceText -> {
                double unit = CoreUtil.parseMoney(priceText);
                if (!(unit > 0)) { CoreUtil.error(player, "Enter a price greater than zero."); return; }
                draft.unit = unit;
                confirm(player, draft);
            });
        });
    }

    private static Component orderLine(String text) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .deserialize(text).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    private int parsePositive(String text, int fallback) {
        try { return Integer.parseInt(text.trim().replace(",", "")); } catch (NumberFormatException error) { return fallback; }
    }

    private void confirm(Player player, Draft draft) {
        double total = draft.amount * draft.unit;
        Inventory inv = open(player, Screen.CONFIRM, 1, null, 0, "Orders • Confirm", 27);
        ItemStack icon = canonical(draft.key);
        if (icon != null) {
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text(draft.amount + "x " + display(draft.key), CoreUtil.EMBER)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(java.util.List.of(
                    orderLine(CoreUtil.C_BODY + "Paying " + CoreUtil.C_TEXT + CoreUtil.money(draft.unit) + CoreUtil.C_BODY + " each"),
                    orderLine(CoreUtil.C_BODY + "Escrow " + CoreUtil.C_EMBER + CoreUtil.money(total)),
                    orderLine(CoreUtil.C_MUTE + "Held until somebody fills it, or you cancel the order.")));
            icon.setItemMeta(meta);
            inv.setItem(CoreUtil.Menu.SUBJECT, icon);
        }
        inv.setItem(CoreUtil.Menu.CANCEL, CoreUtil.Menu.cancel("Nothing is charged and no order is created."));
        inv.setItem(CoreUtil.Menu.CONFIRM, CoreUtil.Menu.confirm(CoreUtil.money(total), List.of(
                CoreUtil.C_BODY + "Leaves your balance now and waits in escrow.",
                CoreUtil.C_MUTE + "Cancelling the order later refunds whatever is left.")));
        player.openInventory(inv);
        prompts.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Consumer<String> prompt = prompts.get(event.getPlayer().getUniqueId());
        if (prompt == null) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        prompts.remove(event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("cancel")) { drafts.remove(event.getPlayer().getUniqueId()); CoreUtil.msg(event.getPlayer(), "Cancelled."); return; }
            prompt.accept(text);
        });
    }

    // ------------------------------------------------------------------ confirm click
    @EventHandler
    public void confirmClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder holder) || holder.screen != Screen.CONFIRM) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) { player.closeInventory(); return; }
        if (event.getRawSlot() == CoreUtil.Menu.CONFIRM) { create(player, draft); player.closeInventory(); }
        else if (event.getRawSlot() == CoreUtil.Menu.CANCEL) { drafts.remove(player.getUniqueId()); player.closeInventory(); CoreUtil.msg(player, "Cancelled."); }
    }

    // ------------------------------------------------------------------ lifecycle
    void create(Player player, Draft draft) {
        String id = CoreUtil.id(player);
        int limit = plugin.getConfig().getInt("orders.max-per-player", 10);
        if (db.ordersOf(id).stream().filter(row -> row.status().equals("ACTIVE")).count() >= limit) {
            CoreUtil.error(player, "You already have " + limit + " active orders."); return;
        }
        double total = Math.round(draft.amount * draft.unit * 100) / 100.0;
        /** The money leaves the buyer BEFORE the row exists. A failed debit means no order, rather than an
         *  order nobody funded. */
        if (!db.changeBalance(id, -total)) { CoreUtil.error(player, "You cannot cover " + CoreUtil.money(total) + "."); return; }
        long days = plugin.getConfig().getLong("orders.expiry-days", 7);
        long orderId = db.orderCreate(id, player.getName(), draft.key, draft.amount, draft.unit, total,
                System.currentTimeMillis() + days * 86400000L);
        db.recordEconomy(id, "ORDER_ESCROW", -total, draft.key);
        drafts.remove(player.getUniqueId());
        CoreUtil.msg(player, "Order #" + orderId + " placed: " + draft.amount + "x " + display(draft.key)
                + " at " + CoreUtil.money(draft.unit) + " each. " + CoreUtil.money(total) + " is held in escrow.");
    }

    /** Deliver as much as the seller is carrying, up to what the order still needs. */
    void deliver(Player seller, long orderId) {
        Database.OrderRow row = db.order(orderId);
        if (row == null || !row.status().equals("ACTIVE")) { CoreUtil.error(seller, "That order is no longer active."); return; }
        if (row.buyer().equals(CoreUtil.id(seller))) { CoreUtil.error(seller, "You cannot fill your own order."); return; }
        int wanted = row.amount() - row.filled();
        int carrying = count(seller, row.itemKey());
        int qty = Math.min(wanted, carrying);
        if (qty <= 0) { CoreUtil.error(seller, "You are not carrying any " + display(row.itemKey()) + "."); return; }
        double cost = Math.round(qty * row.unit() * 100) / 100.0;

        /** Reserve first: one statement that both checks and takes. If it changes no rows, somebody else
         *  filled this order in the meantime and we must not touch the seller's inventory at all. */
        if (!db.orderReserve(orderId, qty, cost)) { CoreUtil.error(seller, "Somebody just filled that order."); openPublic(seller); return; }

        int removed = take(seller, row.itemKey(), qty);
        if (removed < qty) {
            /** Undo the reservation and hand back exactly what was taken. The order is left as it was. */
            db.orderUnreserve(orderId, qty, cost);
            if (removed > 0) giveUnits(seller, row.itemKey(), removed);
            CoreUtil.error(seller, "Delivery came up short and nothing was charged.");
            return;
        }
        double tax = Math.max(0, plugin.getConfig().getDouble("orders.tax-percent", 2.5)) * plugin.bank().feeFactor() / 100.0;
        double fee = Math.round(cost * tax * 100) / 100.0, net = cost - fee;
        db.changeBalance(CoreUtil.id(seller), net);
        if (fee > 0) plugin.bank().creditFee(fee, CoreUtil.id(seller), "ORDER_TAX");
        db.recordEconomy(CoreUtil.id(seller), "ORDER_SALE", net, row.itemKey());
        db.stashAdd(row.buyer(), row.itemKey(), qty, canonical(row.itemKey()));
        db.orderCompleteIfFull(orderId);

        CoreUtil.msg(seller, "Delivered " + qty + "x " + display(row.itemKey()) + " for " + CoreUtil.money(net)
                + (fee > 0 ? " (after " + CoreUtil.money(fee) + " tax)" : "") + ".");
        Player buyer = plugin.getServer().getPlayer(row.buyer());
        if (buyer != null) CoreUtil.msg(buyer, seller.getName() + " delivered " + qty + "x " + display(row.itemKey())
                + " to your order. Collect it with /orders.");
        openPublic(seller);
    }

    void cancel(Player player, long orderId) {
        Database.OrderRow row = db.order(orderId);
        if (row == null || !row.buyer().equals(CoreUtil.id(player))) { CoreUtil.error(player, "That is not your order."); return; }
        if (!row.status().equals("ACTIVE")) { CoreUtil.error(player, "That order is already " + row.status().toLowerCase(Locale.ROOT) + "."); return; }
        /** Compare-and-swap on the escrow figure we just read: a second cancel cannot match it, so the
         *  refund can only ever happen once. */
        if (!db.orderClose(orderId, "CANCELLED", row.escrow())) { CoreUtil.error(player, "That order just changed; try again."); return; }
        if (row.escrow() > 0) {
            db.changeBalance(row.buyer(), row.escrow());
            db.recordEconomy(row.buyer(), "ORDER_REFUND", row.escrow(), row.itemKey());
        }
        CoreUtil.msg(player, "Order #" + orderId + " cancelled. " + CoreUtil.money(row.escrow()) + " refunded.");
        openMine(player, 1);
    }

    private void expireDue() {
        for (Database.OrderRow row : db.ordersExpired(System.currentTimeMillis())) {
            if (!db.orderClose(row.id(), "EXPIRED", row.escrow())) continue;
            if (row.escrow() > 0) {
                db.changeBalance(row.buyer(), row.escrow());
                db.recordEconomy(row.buyer(), "ORDER_REFUND", row.escrow(), row.itemKey());
            }
            Player buyer = plugin.getServer().getPlayer(row.buyer());
            if (buyer != null) CoreUtil.msg(buyer, "Order #" + row.id() + " expired. "
                    + CoreUtil.money(row.escrow()) + " refunded.");
        }
    }

    // ------------------------------------------------------------------ inventory helpers
    private int count(Player player, String key) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents())
            if (matches(key, item)) total += item.getAmount();
        return total;
    }

    /** Removes up to `amount` matching items and reports how many actually went. */
    private int take(Player player, String key, int amount) {
        int remaining = amount;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (remaining <= 0) break;
            if (!matches(key, item)) continue;
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
        }
        return amount - remaining;
    }

    private void giveUnits(Player player, String key, int amount) {
        ItemStack unit = canonical(key);
        if (unit == null) return;
        int remaining = amount, max = Math.max(1, unit.getMaxStackSize());
        while (remaining > 0) {
            ItemStack give = unit.clone();
            give.setAmount(Math.min(max, remaining));
            remaining -= give.getAmount();
            CoreUtil.give(player, give);
        }
    }

    /*  This used to take the whole stash and then CoreUtil.give() it, which drops what will not fit on
     *  the ground -- five minutes from despawning, or gone entirely if the player happened to be standing
     *  in a Colosseum arena or a void world when they collected. Nothing leaves the stash now until it is
     *  somewhere that survives a restart. */
    private void collect(Player player) {
        int before = db.stashCount(CoreUtil.id(player));
        if (before == 0) { CoreUtil.msg(player, "Nothing is waiting for you."); return; }
        int left = plugin.deliverStash(player);
        int taken = before - left;
        if (taken > 0) CoreUtil.ok(player, "Collected " + taken + " item stack" + (taken == 1 ? "" : "s") + ".");
        if (left > 0) {
            CoreUtil.warn(player, left + " stack" + (left == 1 ? "" : "s") + " would not fit. Make room and collect again — nothing is lost.");
            openStash(player);
        } else player.closeInventory();
    }

    /** No join handler here on purpose. GameplayListener already calls loginSummary once per join, and
     *  having a second one meant every notice arrived twice.
     *
     *  Only things that HAPPENED while they were away are reported: deliveries that landed, and orders that
     *  expired or completed. An order merely still being active is not news and is not mentioned. Each event
     *  is reported once, tracked by the filled count already reported rather than by the order existing, so
     *  logging in again says nothing new. */
    void loginSummary(Player player) {
        String id = CoreUtil.id(player);
        List<String> news = new ArrayList<>();
        for (Database.OrderRow row : db.ordersOf(id)) {
            boolean delivered = row.filled() > row.notified();
            boolean ended = !row.status().equals("ACTIVE") && row.notifiedEnd() == 0;
            if (delivered) news.add((row.filled() - row.notified()) + "x " + display(row.itemKey())
                    + " delivered to order #" + row.id());
            if (ended) news.add("Order #" + row.id() + " " + row.status().toLowerCase(Locale.ROOT)
                    + (row.status().equals("EXPIRED") ? " and the escrow was refunded" : ""));
            if (delivered || ended) db.orderMarkNotified(row.id(), row.filled());
        }
        if (news.isEmpty()) return;
        CoreUtil.msg(player, "While you were away:");
        for (String line : news) CoreUtil.msg(player, "  " + line);
        int stash = db.stashCount(id);
        if (stash > 0) CoreUtil.msg(player, "  Collect " + stash + " stack(s) with /orders.");
    }

    // ------------------------------------------------------------------ admin / test hooks
    /** Resolve a friendly name to a canonical key: a full key passes through, "spawner/BLAZE" becomes the
     *  typed spawner, anything else is treated as a vanilla material. */
    String resolveKey(String raw) {
        if (raw == null) return null;
        if (raw.contains(":")) return raw;
        if (raw.toLowerCase(Locale.ROOT).startsWith("spawner/")) return SPAWNER + raw.substring(8).toUpperCase(Locale.ROOT);
        Material material = Material.matchMaterial(raw);
        return material == null ? null : VANILLA + material.name();
    }

    void debugCreate(org.bukkit.command.CommandSender admin, String playerName, String itemName, int qty, double price) {
        Player buyer = plugin.getServer().getPlayer(playerName);
        String id = buyer != null ? CoreUtil.id(buyer) : playerName.toLowerCase(Locale.ROOT);
        String key = resolveKey(itemName);
        if (key == null || canonical(key) == null) { CoreUtil.error(admin, "Unknown item: " + itemName); return; }
        if (qty <= 0 || price <= 0) { CoreUtil.error(admin, "Quantity and price must be positive."); return; }
        double total = Math.round(qty * price * 100) / 100.0;
        if (!db.changeBalance(id, -total)) { CoreUtil.error(admin, playerName + " cannot cover " + CoreUtil.money(total) + "."); return; }
        long orderId = db.orderCreate(id, buyer != null ? buyer.getName() : playerName, key, qty, price, total,
                System.currentTimeMillis() + plugin.getConfig().getLong("orders.expiry-days", 7) * 86400000L);
        db.recordEconomy(id, "ORDER_ESCROW", -total, key);
        CoreUtil.msg(admin, "Order #" + orderId + " created for " + playerName + ": " + qty + "x " + display(key)
                + " at " + CoreUtil.money(price) + " (escrow " + CoreUtil.money(total) + ").");
    }

    /** Makes the named player deliver everything they are carrying into that order, through exactly the
     *  same path a click uses -- so a console test exercises the real reservation, not a shortcut. */
    void debugClaim(org.bukkit.command.CommandSender admin, String playerName, String orderIdRaw) {
        Player seller = plugin.getServer().getPlayer(playerName);
        if (seller == null) { CoreUtil.error(admin, playerName + " is not online."); return; }
        long orderId;
        try { orderId = Long.parseLong(orderIdRaw.replace("#", "").trim()); }
        catch (NumberFormatException error) { CoreUtil.error(admin, "Usage: <player> <order id>"); return; }
        deliver(seller, orderId);
        CoreUtil.msg(admin, "Delivery attempted for " + playerName + " on order #" + orderId + ".");
    }

    /** Console-readable state, for conservation checks. */
    String debugStatus(long orderId) {
        Database.OrderRow row = db.order(orderId);
        if (row == null) return "ORDER " + orderId + " missing";
        return "ORDER " + row.id() + " key=" + row.itemKey() + " amount=" + row.amount() + " filled=" + row.filled()
                + " unit=" + row.unit() + " ESCROW=" + row.escrow() + " status=" + row.status()
                + " buyerBalance=" + db.player(row.buyer()).balance() + " stash=" + db.stashCount(row.buyer());
    }

    // ------------------------------------------------------------------ self test
    boolean selfTest() {
        if (catalogue.isEmpty()) buildCatalogue();
        if (catalogue.size() < 500) return false;
        /** Identity resolves both ways for all three kinds. */
        if (canonical("vanilla:DIAMOND") == null || canonical(BOOK + "SHARPNESS/5") == null) return false;
        /** A plain diamond fills a diamond order; a renamed one does not. */
        ItemStack plain = new ItemStack(Material.DIAMOND, 5);
        if (!matches("vanilla:DIAMOND", plain)) return false;
        ItemStack renamed = new ItemStack(Material.DIAMOND, 5);
        ItemMeta meta = renamed.getItemMeta();
        meta.displayName(Component.text("Not ordinary"));
        renamed.setItemMeta(meta);
        if (matches("vanilla:DIAMOND", renamed)) return false;
        /** THE POINT OF THE REWRITE: spawner types cannot fill each other. */
        List<EntityType> types = plugin.spawners().orderableTypes();
        if (types.size() < 2) return false;
        EntityType first = types.get(0), second = types.get(1);
        ItemStack firstItem = plugin.spawners().orderItem(first);
        if (!matches(SPAWNER + first.name(), firstItem)) return false;
        if (matches(SPAWNER + second.name(), firstItem)) return false;
        /** And a typed spawner must not pass as the plain vanilla material either. */
        if (matches("vanilla:SPAWNER", firstItem)) return false;
        return true;
    }
}
