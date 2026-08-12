package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** The Task Master event: a contract board carried by a hidden courier.
 *
 *  Every task is "bring me N of X". That is deliberate rather than a limitation: delivery tasks are checked
 *  by reading the player's inventory at turn-in, so the whole event needs no kill/mine/break listeners, no
 *  per-player progress counters ticking in the background, and no progress state to persist or reconcile
 *  across a restart. It also matches how the event reads in play -- you take a contract, go and get the
 *  goods, and come back.
 *
 *  Each player draws their own hand of contracts from the table, so two people standing at the same trader
 *  are not racing for the same objective. Rewards scale with the tier of the individual task.
 *
 *  The courier is a real WanderingTrader so it walks around naturally, but it is made invisible, silent and
 *  invulnerable and its vanilla trading GUI is suppressed -- right-clicking opens the contract board
 *  instead. It is tagged in persistent data so it can always be found and cleaned up, including after a
 *  restart that happened mid-event. */
final class TaskMasterService implements Listener {

    /** A single contract. reward is paid on turn-in; tier is only used to describe the difficulty. */
    private record Task(String id, Material material, int amount, double reward, String tier, String flavour) {}

    private record Holder(UUID trader) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    /** The contract table. Deliberately spread across gathering, farming, mining, mob drops and a couple of
     *  genuinely awkward asks, so a drawn hand is usually a mix of "already have it" and "go and do it". */
    private static final List<Task> TABLE = List.of(
        new Task("logs",       Material.OAK_LOG,          64,  2500,  "Easy",   "The camp needs timber."),
        new Task("wheat",      Material.WHEAT,            64,  2200,  "Easy",   "Bread does not bake itself."),
        new Task("cobble",     Material.COBBLESTONE,     256,  2000,  "Easy",   "Rubble for the road crews."),
        new Task("coal",       Material.COAL,             48,  4500,  "Easy",   "The forges are going cold."),
        new Task("iron",       Material.IRON_INGOT,       32,  9000,  "Medium", "Tools break faster than we make them."),
        new Task("leather",    Material.LEATHER,          32,  6500,  "Medium", "Bindings and satchels."),
        new Task("string",     Material.STRING,           48,  6000,  "Medium", "Someone has to make the nets."),
        new Task("gunpowder",  Material.GUNPOWDER,        16, 11000,  "Medium", "Ask no questions about the buyer."),
        new Task("gold",       Material.GOLD_INGOT,       24,  8000,  "Medium", "A debt is owed in gold, not promises."),
        new Task("glass",      Material.GLASS,            64,  7000,  "Medium", "Windows for the new hall."),
        new Task("obsidian",   Material.OBSIDIAN,         16, 13000,  "Hard",   "Slow work, and dangerous."),
        new Task("blaze",      Material.BLAZE_ROD,        12, 15000,  "Hard",   "Bring me fire that keeps burning."),
        new Task("ender",      Material.ENDER_PEARL,      12, 14000,  "Hard",   "The road is long. I would rather skip it."),
        new Task("emerald",    Material.EMERALD,          16, 16000,  "Hard",   "Coin that every village recognises."),
        new Task("diamond",    Material.DIAMOND,           8, 20000,  "Hard",   "No explanation. Just bring them.")
    );

    private final SMPCore plugin;
    private UUID traderId;
    private long endsAt;
    /** player -> their drawn contracts, and which they have already turned in. */
    private final Map<UUID, List<Task>> assigned = new HashMap<>();
    private final Map<UUID, List<String>> completed = new HashMap<>();

    TaskMasterService(SMPCore plugin) { this.plugin = plugin; }

    private org.bukkit.NamespacedKey markerKey() { return new org.bukkit.NamespacedKey(plugin, "task_master"); }

    boolean isTaskMaster(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(markerKey(), PersistentDataType.BYTE);
    }

    /** Spawns the courier and opens the event. Any leftover courier is cleared first, so a restart that
     *  happened mid-event cannot leave a second one standing. */
    void begin(Location center, long endsAtMillis) {
        end();
        endsAt = endsAtMillis;
        if (center == null || center.getWorld() == null) return;
        WanderingTrader trader = center.getWorld().spawn(center, WanderingTrader.class,
                org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, t -> {
            t.setInvisible(true);
            t.setSilent(true);
            t.setInvulnerable(true);
            t.setPersistent(true);
            t.setRemoveWhenFarAway(false);
            t.setDespawnDelay(Integer.MAX_VALUE);
            t.customName(Component.text("The Task Master", NamedTextColor.LIGHT_PURPLE));
            t.setCustomNameVisible(false);
            t.getPersistentDataContainer().set(markerKey(), PersistentDataType.BYTE, (byte) 1);
        });
        traderId = trader.getUniqueId();
    }

    /** Called on the event tick. Keeps the courier present without any scanning: it only ever looks up the
     *  one entity it spawned, by id. */
    void tick() {
        if (traderId == null) return;
        Entity trader = plugin.getServer().getEntity(traderId);
        if (trader == null || !trader.isValid()) return;
        if (System.currentTimeMillis() > endsAt) end();
    }

    void end() {
        if (traderId != null) {
            Entity trader = plugin.getServer().getEntity(traderId);
            if (trader != null) trader.remove();
            /** Belt and braces for a courier orphaned by a restart: only ever checks LOADED worlds and only
             *  entities already in memory, so this is not a world scan. */
            for (org.bukkit.World world : plugin.getServer().getWorlds())
                for (Entity entity : world.getEntitiesByClass(WanderingTrader.class))
                    if (isTaskMaster(entity)) entity.remove();
        }
        traderId = null;
        assigned.clear();
        completed.clear();
    }

    boolean active() { return traderId != null; }

    /** Right-clicking the courier. Returns true when handled, so the caller can cancel the vanilla trade. */
    boolean interact(Player player) {
        if (!active()) return false;
        assigned.computeIfAbsent(player.getUniqueId(), id -> draw());
        open(player);
        return true;
    }

    /** A fresh hand of 3-4 contracts, never duplicated within the hand. */
    private List<Task> draw() {
        List<Task> pool = new ArrayList<>(TABLE);
        Collections.shuffle(pool);
        return List.copyOf(pool.subList(0, 3 + ThreadLocalRandom.current().nextInt(2)));
    }

    private void open(Player player) {
        List<Task> tasks = assigned.getOrDefault(player.getUniqueId(), List.of());
        List<String> done = completed.computeIfAbsent(player.getUniqueId(), id -> new ArrayList<>());
        Inventory inv = plugin.getServer().createInventory(new Holder(traderId), 27,
                Component.text("Task Master • Contracts", NamedTextColor.DARK_PURPLE));
        int slot = 11;
        for (Task task : tasks) {
            int held = count(player, task.material());
            boolean claimed = done.contains(task.id());
            ItemStack icon = new ItemStack(task.material());
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text((claimed ? "✔ " : "") + task.amount() + "x " + CoreUtil.pretty(task.material().name()),
                    claimed ? NamedTextColor.DARK_GRAY : NamedTextColor.GOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(task.flavour(), NamedTextColor.GRAY));
            lore.add(Component.text("Difficulty: " + task.tier(), NamedTextColor.DARK_GRAY));
            lore.add(Component.text("Reward: " + CoreUtil.money(task.reward()), NamedTextColor.YELLOW));
            if (claimed) lore.add(Component.text("Already delivered.", NamedTextColor.DARK_GRAY));
            else if (held >= task.amount()) lore.add(Component.text("Click to deliver.", NamedTextColor.GREEN));
            else lore.add(Component.text("Carrying " + held + " / " + task.amount(), NamedTextColor.RED));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
            slot++;
        }
        player.openInventory(inv);
    }

    private int count(Player player, Material material) {
        int total = 0;
        ItemStack plain = new ItemStack(material);
        for (ItemStack item : player.getInventory().getStorageContents())
            /** isSimilar keeps custom/NBT items out: a renamed or relic item is never eaten by a contract. */
            if (item != null && item.isSimilar(plain)) total += item.getAmount();
        return total;
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        List<Task> tasks = assigned.getOrDefault(player.getUniqueId(), List.of());
        int index = event.getRawSlot() - 11;
        if (index < 0 || index >= tasks.size()) return;
        Task task = tasks.get(index);
        List<String> done = completed.computeIfAbsent(player.getUniqueId(), id -> new ArrayList<>());
        if (done.contains(task.id())) return;
        if (!active()) { CoreUtil.error(player, "The Task Master has moved on."); player.closeInventory(); return; }
        if (count(player, task.material()) < task.amount()) {
            CoreUtil.error(player, "You are not carrying " + task.amount() + " " + CoreUtil.pretty(task.material().name()) + ".");
            plugin.settings().marketSound(player, "failed");
            return;
        }
        /** Take the goods FIRST, and only pay for what was actually removed, so a race cannot pay twice. */
        int removed = take(player, task.material(), task.amount());
        if (removed < task.amount()) {
            /** Put back whatever was taken rather than paying for a partial delivery. */
            if (removed > 0) CoreUtil.give(player, new ItemStack(task.material(), removed));
            CoreUtil.error(player, "The delivery came up short.");
            return;
        }
        done.add(task.id());
        plugin.creditEarned(CoreUtil.id(player), task.reward(), "TASK_MASTER_" + task.id().toUpperCase(Locale.ROOT));
        plugin.db().recordEconomy(CoreUtil.id(player), "TASK_MASTER", task.reward(), task.id());
        CoreUtil.msg(player, "Contract complete: " + task.amount() + "x " + CoreUtil.pretty(task.material().name())
                + " for " + CoreUtil.money(task.reward()) + ".");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1.1f);
        open(player);
    }

    private int take(Player player, Material material, int amount) {
        int remaining = amount;
        ItemStack plain = new ItemStack(material);
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || !item.isSimilar(plain)) continue;
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            if (remaining == 0) break;
        }
        return amount - remaining;
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        /** Contracts are per-event and not persisted: a player who leaves mid-event simply draws a fresh
         *  hand if they return while it is still running. Nothing owed is lost, because payment happens at
         *  the moment of delivery rather than at the end. */
        assigned.remove(event.getPlayer().getUniqueId());
        completed.remove(event.getPlayer().getUniqueId());
    }

    boolean selfTest() {
        if (TABLE.size() < 10) return false;
        List<Task> hand = draw();
        if (hand.size() < 3 || hand.size() > 4) return false;
        return hand.stream().map(Task::id).distinct().count() == hand.size()
                && TABLE.stream().allMatch(t -> t.amount() > 0 && t.reward() > 0);
    }
}
