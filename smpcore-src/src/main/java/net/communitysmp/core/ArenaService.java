package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Native 1v1 duelling with real stakes, in a world of its own — now with MANY duels at once.
 *
 *  CONCURRENCY. Each match runs in its own arena SLOT: a fixed coordinate cell in the arena world, spaced
 *  far enough apart (2,048 blocks) that no two fights can see, hit, or interfere with each other. A duel
 *  owns its slot for its whole life and hands it back on finish, so a slot is only ever occupied by one
 *  match. There is no global duel state left — everything a match needs lives on its own {@link Duel}.
 *
 *  MONEY never sits in memory. Both duellists' stakes and every spectator wager are debited up front into
 *  the database ({@code arena_escrow}, {@code arena_wagers}), so a restart mid-match neither loses nor
 *  invents a penny. Stakes need not match and may be zero; the winner takes both in full. Spectator betting
 *  is pari-mutuel and NEVER server-funded — winners get their own stake back plus a share of the losing pool
 *  proportional to what they risked; a one-sided pool returns stakes on a win and goes to the Central Bank
 *  on a loss.
 *
 *  STATE. Location, inventory, armour, offhand, XP, health, hunger, effects and gamemode are written to the
 *  database before a player is touched and restored exactly afterwards, including on reconnect after a
 *  restart. Arena deaths are intercepted before graves, drops, penalties or rewards, so nothing leaks out
 *  and no survival system (bounty, faction, death tax) ever sees them.
 *
 *  BUILDING. A kit may hand out blocks; those can be placed and broken freely, but the arena's own map can
 *  never be broken. Every block a duellist places is tracked and cleared when the round ends, so no match
 *  leaves a mark on the map. */
final class ArenaService implements Listener {

    private static final int SLOT_SPACING = 2048;
    private static final int FLOOR_Y = 64;

    static final class VoidGenerator extends ChunkGenerator {
        @Override public void generateNoise(org.bukkit.generator.WorldInfo info, Random random, int x, int z, ChunkData data) { }
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
    }

    enum Phase { PENDING, STAKING, LIVE, ENDING }

    enum Kit {
        MACE("Mace", Material.MACE, "Wind charges for height, mace to land it."),
        SWORD("Sword + Shield", Material.DIAMOND_SWORD, "Sharpness V and a shield to time."),
        AXE("Axe", Material.DIAMOND_AXE, "Shield-breaker. Slow, brutal, and cobwebs to trap."),
        SPEAR("Spear", Material.ELYTRA, "Elytra and rockets. Hit and run.");
        private final String label, blurb;
        private final Material icon;
        Kit(String label, Material icon, String blurb) { this.label = label; this.icon = icon; this.blurb = blurb; }
        String label() { return label; }
        String blurb() { return blurb; }
        Material icon() { return icon; }
    }

    private record Wager(String player, String on, double amount) {}

    /** One live match and everything it owns. Nothing here is shared with another duel. */
    private final class Duel {
        final int id;
        final int slot;
        final String a, b;
        String aName, bName;
        Kit kit = Kit.SWORD;
        int bestOf = 1;
        Phase phase = Phase.PENDING;
        long pendingSince = System.currentTimeMillis();
        final Map<String, Double> stakes = new LinkedHashMap<>();
        final Set<String> confirmed = new HashSet<>();
        final Map<String, Integer> rounds = new LinkedHashMap<>();
        final List<Wager> wagers = new ArrayList<>();
        final Map<String, Long> disconnectedAt = new LinkedHashMap<>();
        /** Blocks placed by duellists this round, cleared on round end so the map stays pristine. */
        final Set<Long> placed = new HashSet<>();
        Duel(int id, int slot, String a, String b) { this.id = id; this.slot = slot; this.a = a; this.b = b; }
        boolean has(String id) { return id.equals(a) || id.equals(b); }
        String other(String id) { return id.equals(a) ? b : a; }
    }

    private final SMPCore plugin;
    private final Database db;
    /** player id -> the duel they are in (as duellist). One duel per player at a time. */
    private final Map<String, Duel> byPlayer = new ConcurrentHashMap<>();
    private final List<Duel> duels = new ArrayList<>();
    private int nextId = 1;
    /** Last time each player sent a challenge, for the anti-spam cooldown, exactly like a trade request. */
    private final Map<String, Long> lastChallenge = new ConcurrentHashMap<>();
    private World arena;
    private BukkitTask ticker;

    ArenaService(SMPCore plugin) {
        this.plugin = plugin;
        this.db = plugin.db();
        Bukkit.getScheduler().runTask(plugin, this::prepareWorld);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskLater(plugin, this::recoverAfterRestart, 60L);
    }

    void shutdown() {
        if (ticker != null) ticker.cancel();
        /** Refund every live match; inventories come back from the database on the next boot. */
        for (Duel duel : new ArrayList<>(duels)) if (duel.phase == Phase.LIVE || duel.phase == Phase.STAKING) abortAndRefund(duel, "the server restarted");
    }

    // ------------------------------------------------------------------ world
    private void prepareWorld() {
        String name = plugin.getConfig().getString("arena.world", "ashfall_arena");
        arena = Bukkit.getWorld(name);
        if (arena == null) arena = new WorldCreator(name).generator(new VoidGenerator()).type(WorldType.FLAT)
                .environment(World.Environment.NORMAL).createWorld();
        if (arena == null) { plugin.getLogger().warning("[Arena] could not create the arena world."); return; }
        arena.setAutoSave(false);
        arena.setDifficulty(org.bukkit.Difficulty.NORMAL);
        arena.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        arena.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        arena.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        arena.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
        arena.setGameRule(org.bukkit.GameRule.DO_IMMEDIATE_RESPAWN, true);
        arena.setGameRule(org.bukkit.GameRule.FALL_DAMAGE, true);
        arena.setTime(6000);
        plugin.getLogger().info("[Arena] arena world ready: " + arena.getName());
    }

    private int slotBaseX(int slot) { return slot * SLOT_SPACING; }

    /** Builds the arena structure for a slot the first time it is used. Idempotent — re-run before a match
     *  to guarantee a clean floor even if a previous match left something behind. */
    private void buildSlot(int slot) {
        if (arena == null) return;
        int cx = slotBaseX(slot), half = 20;
        for (int x = -half; x <= half; x++) for (int z = -half; z <= half; z++) {
            arena.getBlockAt(cx + x, FLOOR_Y, z).setType(Material.SMOOTH_STONE, false);
            for (int y = 1; y <= 6; y++) {
                Material want = (Math.abs(x) == half || Math.abs(z) == half) && y <= 4 ? Material.SMOOTH_STONE_SLAB : Material.AIR;
                arena.getBlockAt(cx + x, FLOOR_Y + y, z).setType(want, false);
            }
        }
        /** Spectator gallery: a glass ring above the fighting floor, outside it. */
        for (int x = -half - 2; x <= half + 2; x++) for (int z = -half - 2; z <= half + 2; z++)
            if (Math.abs(x) > half || Math.abs(z) > half) arena.getBlockAt(cx + x, FLOOR_Y + 8, z).setType(Material.GLASS, false);
    }

    private Location corner(Duel duel, int index) {
        int cx = slotBaseX(duel.slot);
        return new Location(arena, cx + (index == 0 ? -15.5 : 15.5), FLOOR_Y + 1, 0.5, index == 0 ? 90f : -90f, 0f);
    }

    private Location gallery(Duel duel) { return new Location(arena, slotBaseX(duel.slot) + 0.5, FLOOR_Y + 9, 0.5); }

    private boolean inArena(Player player) { return arena != null && player.getWorld().equals(arena); }

    // ------------------------------------------------------------------ kits
    List<ItemStack> kitContents(Kit kit) {
        List<ItemStack> items = new ArrayList<>();
        items.add(armour(Material.DIAMOND_HELMET));
        items.add(armour(Material.DIAMOND_CHESTPLATE));
        items.add(armour(Material.DIAMOND_LEGGINGS));
        items.add(armour(Material.DIAMOND_BOOTS));
        switch (kit) {
            case MACE -> {
                items.add(enchanted(Material.MACE, Map.of(Enchantment.DENSITY, 5, Enchantment.UNBREAKING, 3)));
                items.add(new ItemStack(Material.WIND_CHARGE, 16));
                items.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2));
                items.add(new ItemStack(Material.GOLDEN_APPLE, 8));
            }
            case SWORD -> {
                items.add(enchanted(Material.DIAMOND_SWORD, Map.of(Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3)));
                items.add(enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3)));
                items.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2));
                items.add(new ItemStack(Material.GOLDEN_APPLE, 8));
            }
            case AXE -> {
                items.add(enchanted(Material.DIAMOND_AXE, Map.of(Enchantment.SHARPNESS, 5, Enchantment.EFFICIENCY, 5, Enchantment.UNBREAKING, 3)));
                items.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2));
                items.add(new ItemStack(Material.GOLDEN_APPLE, 8));
                items.add(new ItemStack(Material.COBWEB, 8));
            }
            case SPEAR -> {
                items.add(enchanted(Material.DIAMOND_SWORD, Map.of(Enchantment.SHARPNESS, 4, Enchantment.UNBREAKING, 3)));
                items.add(enchanted(Material.ELYTRA, Map.of(Enchantment.UNBREAKING, 3)));
                items.add(new ItemStack(Material.FIREWORK_ROCKET, 64));
                items.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1));
                items.add(new ItemStack(Material.GOLDEN_APPLE, 6));
            }
        }
        return items;
    }

    private ItemStack armour(Material material) { return enchanted(material, Map.of(Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3)); }

    private ItemStack enchanted(Material material, Map<Enchantment, Integer> enchants) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        enchants.forEach((enchantment, level) -> meta.addEnchant(enchantment, level, true));
        item.setItemMeta(meta);
        return item;
    }

    private void equip(Player player, Kit kit) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setItemOnCursor(null);
        for (ItemStack item : kitContents(kit)) {
            switch (item.getType()) {
                case DIAMOND_HELMET -> player.getInventory().setHelmet(item);
                case DIAMOND_CHESTPLATE, ELYTRA -> player.getInventory().setChestplate(item);
                case DIAMOND_LEGGINGS -> player.getInventory().setLeggings(item);
                case DIAMOND_BOOTS -> player.getInventory().setBoots(item);
                case SHIELD -> player.getInventory().setItemInOffHand(item);
                default -> player.getInventory().addItem(item);
            }
        }
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        player.setGameMode(GameMode.SURVIVAL);
        player.updateInventory();
    }

    // ------------------------------------------------------------------ state capture / restore
    private void capture(Player player) {
        String id = CoreUtil.id(player);
        if (db.arenaState(id) != null) return;
        ItemStack[] contents = player.getInventory().getContents(), armour = player.getInventory().getArmorContents();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        ItemStack[] all = new ItemStack[contents.length + armour.length + 1];
        System.arraycopy(contents, 0, all, 0, contents.length);
        System.arraycopy(armour, 0, all, contents.length, armour.length);
        all[all.length - 1] = offhand;
        Location at = player.getLocation();
        db.arenaStateSave(id, ItemStack.serializeItemsAsBytes(all), at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                at.getYaw(), at.getPitch(), player.getLevel(), player.getExp(), player.getHealth(), player.getFoodLevel(), player.getGameMode().name());
    }

    private void restore(Player player) {
        String id = CoreUtil.id(player);
        Database.ArenaState state = db.arenaState(id);
        if (state == null) return;
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        try {
            ItemStack[] all = ItemStack.deserializeItemsFromBytes(state.items());
            int size = player.getInventory().getSize();
            ItemStack[] main = new ItemStack[size], armour = new ItemStack[4];
            System.arraycopy(all, 0, main, 0, Math.min(size, all.length));
            if (all.length >= size + 4) System.arraycopy(all, size, armour, 0, 4);
            player.getInventory().setContents(main);
            player.getInventory().setArmorContents(armour);
            if (all.length > size + 4 && all[size + 4] != null) player.getInventory().setItemInOffHand(all[size + 4]);
        } catch (Throwable error) { plugin.getLogger().warning("[Arena] could not restore " + id + ": " + error); }
        World world = Bukkit.getWorld(state.world());
        if (world != null) player.teleport(new Location(world, state.x(), state.y(), state.z(), state.yaw(), state.pitch()));
        player.setLevel(state.level());
        player.setExp(state.exp());
        player.setFoodLevel(state.food());
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        player.setHealth(Math.min(state.health(), player.getAttribute(Attribute.MAX_HEALTH).getValue()));
        player.setFireTicks(0);
        try { player.setGameMode(GameMode.valueOf(state.gamemode())); } catch (IllegalArgumentException ignored) { player.setGameMode(GameMode.SURVIVAL); }
        /** Clear any PvP combat tag the fight left behind so it does not follow them out. */
        if (plugin.teleports() != null) plugin.teleports().clearCombat(player);
        db.arenaStateClear(id);
        player.updateInventory();
    }

    private void recoverAfterRestart() {
        for (String id : db.arenaStateOwners()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) { restore(player); actionbar(player, "Your pre-duel state was restored after the restart."); }
        }
        /** Any escrow/wagers left by a match the restart killed are refunded and cleared. */
        db.arenaEscrowRefundAll((player, amount) -> {
            db.changeBalance(player, amount);
            db.recordEconomy(player, "DUEL_REFUND", amount, "server-restart");
        });
        db.arenaWagersRefundAll((player, amount) -> db.changeBalance(player, amount));
    }

    @EventHandler public void rejoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String id = CoreUtil.id(player);
        Duel duel = byPlayer.get(id);
        if (duel != null && duel.phase == Phase.LIVE) { duel.disconnectedAt.remove(id); actionbar(player, "You are back in the duel."); return; }
        if (db.arenaState(id) != null) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) { restore(player); actionbar(player, "Your pre-duel state was restored."); }
        }, 20L);
    }

    // ------------------------------------------------------------------ challenge / setup
    boolean inLiveDuel(String id) { Duel d = byPlayer.get(id); return d != null && d.phase == Phase.LIVE; }
    private Duel duelOf(Player player) { return byPlayer.get(CoreUtil.id(player)); }

    boolean challenge(Player from, String targetName) {
        Player target = plugin.getServer().getPlayer(targetName);
        if (target == null || target.equals(from)) { CoreUtil.error(from, "That player is not online."); return true; }
        if (byPlayer.containsKey(CoreUtil.id(from))) { CoreUtil.error(from, "You are already in a duel."); return true; }
        if (byPlayer.containsKey(CoreUtil.id(target))) { CoreUtil.error(from, target.getName() + " is already in a duel."); return true; }
        /** Cooldown between challenges, like a trade request, so nobody can spam duel invites. */
        long cooldown = Math.max(0, plugin.getConfig().getLong("arena.challenge-cooldown-seconds", 15)) * 1000L;
        long wait = cooldown - (System.currentTimeMillis() - lastChallenge.getOrDefault(CoreUtil.id(from), 0L));
        if (wait > 0) { CoreUtil.error(from, "Wait " + Math.max(1, wait / 1000) + "s before challenging again."); return true; }
        int slot = freeSlot();
        if (slot < 0) { CoreUtil.error(from, "Every arena is busy right now. Try again shortly."); return true; }
        lastChallenge.put(CoreUtil.id(from), System.currentTimeMillis());
        Duel duel = new Duel(nextId++, slot, CoreUtil.id(from), CoreUtil.id(target));
        duel.aName = from.getName(); duel.bName = target.getName();
        duels.add(duel);
        byPlayer.put(duel.a, duel); byPlayer.put(duel.b, duel);
        CoreUtil.msg(from, "Challenge sent to " + target.getName() + ". It expires in 60 seconds.");
        /** A chat prompt like a trade request -- clickable, no sudden GUI. The setup GUI only opens once the
         *  target actually accepts. */
        target.sendMessage(Component.text(from.getName() + " has challenged you to a duel.", NamedTextColor.GOLD));
        Component accept = Component.text("[Accept]", NamedTextColor.GREEN)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/duel accept"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Accept the duel")));
        Component decline = Component.text("[Decline]", NamedTextColor.RED)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/duel decline"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Decline the duel")));
        target.sendMessage(accept.append(Component.text("  ")).append(decline)
                .append(Component.text("  or /duel accept", NamedTextColor.GRAY)));
        return true;
    }

    private int freeSlot() {
        int max = Math.max(1, plugin.getConfig().getInt("arena.max-concurrent-duels", 6));
        Set<Integer> used = new HashSet<>();
        for (Duel d : duels) used.add(d.slot);
        for (int i = 0; i < max; i++) if (!used.contains(i)) return i;
        return -1;
    }

    boolean accept(Player player) {
        Duel duel = duelOf(player);
        if (duel == null || duel.phase != Phase.PENDING || !CoreUtil.id(player).equals(duel.b)) { CoreUtil.error(player, "No challenge for you."); return true; }
        duel.phase = Phase.STAKING;
        both(duel, "Duel accepted. Set up the match.");
        openSetup(a(duel)); openSetup(b(duel));
        return true;
    }

    boolean decline(Player player) {
        Duel duel = duelOf(player);
        if (duel == null || duel.phase != Phase.PENDING || !CoreUtil.id(player).equals(duel.b)) { CoreUtil.error(player, "No challenge for you."); return true; }
        both(duel, "Duel declined.");
        dispose(duel);
        return true;
    }

    boolean setKit(Player player, String name) {
        Duel duel = duelOf(player);
        if (duel == null || duel.phase != Phase.STAKING) { CoreUtil.error(player, "Not in a duel setup."); return true; }
        try { duel.kit = Kit.valueOf(name.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { CoreUtil.error(player, "Kits: mace, sword, axe, spear."); return true; }
        duel.confirmed.clear();
        refreshSetup(duel);
        return true;
    }

    boolean setSeries(Player player, int best) {
        Duel duel = duelOf(player);
        if (duel == null || duel.phase != Phase.STAKING) { CoreUtil.error(player, "Not in a duel setup."); return true; }
        if (best != 1 && best != 3) { CoreUtil.error(player, "Best of 1 or 3."); return true; }
        duel.bestOf = best;
        duel.confirmed.clear();
        refreshSetup(duel);
        return true;
    }

    boolean setStake(Player player, double amount) {
        Duel duel = duelOf(player);
        if (duel == null || duel.phase != Phase.STAKING) { CoreUtil.error(player, "Not in a duel setup."); return true; }
        if (amount < 0 || !Double.isFinite(amount)) { CoreUtil.error(player, "Stake cannot be negative."); return true; }
        if (amount > 0 && db.player(CoreUtil.id(player)).balance() < amount) { CoreUtil.error(player, "You cannot cover that."); return true; }
        duel.stakes.put(CoreUtil.id(player), amount);
        duel.confirmed.remove(CoreUtil.id(player));
        refreshSetup(duel);
        return true;
    }

    boolean confirm(Player player) {
        Duel duel = duelOf(player);
        String id = CoreUtil.id(player);
        if (duel == null || duel.phase != Phase.STAKING) { CoreUtil.error(player, "Not in a duel setup."); return true; }
        duel.confirmed.add(id);
        actionbar(player, "Confirmed. Waiting for the other duellist.");
        refreshSetup(duel);
        if (!duel.confirmed.contains(duel.a) || !duel.confirmed.contains(duel.b)) return true;
        double sa = duel.stakes.getOrDefault(duel.a, 0d), sb = duel.stakes.getOrDefault(duel.b, 0d);
        if (sa > 0 && !db.changeBalance(duel.a, -sa)) { both(duel, "Challenger could not cover their stake; duel cancelled."); dispose(duel); return true; }
        if (sb > 0 && !db.changeBalance(duel.b, -sb)) { if (sa > 0) db.changeBalance(duel.a, sa); both(duel, "Opponent could not cover their stake; duel cancelled."); dispose(duel); return true; }
        db.arenaEscrowSet(duel.a, sa); db.arenaEscrowSet(duel.b, sb);
        startMatch(duel);
        return true;
    }

    // ------------------------------------------------------------------ the match
    private Player a(Duel d) { return plugin.getServer().getPlayer(d.a); }
    private Player b(Duel d) { return plugin.getServer().getPlayer(d.b); }

    private void startMatch(Duel duel) {
        Player one = a(duel), two = b(duel);
        if (one == null || two == null) { abortAndRefund(duel, "a duellist went offline"); return; }
        duel.phase = Phase.LIVE;
        duel.rounds.put(duel.a, 0); duel.rounds.put(duel.b, 0);
        one.closeInventory(); two.closeInventory();
        buildSlot(duel.slot);
        plugin.getServer().broadcast(Component.text("⚔ " + one.getName() + " vs " + two.getName() + " — "
                + duel.kit.label() + ", best of " + duel.bestOf + ". /duel watch " + duel.id + " to spectate; bet before it starts.", NamedTextColor.GOLD));
        beginRound(duel);
    }

    private void beginRound(Duel duel) {
        Player one = a(duel), two = b(duel);
        if (one == null || two == null) { abortAndRefund(duel, "a duellist went offline"); return; }
        resetArena(duel);
        capture(one); capture(two);
        one.teleport(corner(duel, 0)); two.teleport(corner(duel, 1));
        equip(one, duel.kit); equip(two, duel.kit);
        int round = duel.rounds.get(duel.a) + duel.rounds.get(duel.b) + 1;
        actionbar(one, "Round " + round + " — fight!"); actionbar(two, "Round " + round + " — fight!");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void death(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Duel duel = duelOf(dead);
        if (duel == null || duel.phase != Phase.LIVE || !inArena(dead)) return;
        /** No grave, no drop, no XP loss, no death message, no penalty. Keep-inventory is on in this world
         *  anyway, but the drop list is cleared so nothing can leak even if that changes. */
        event.getDrops().clear();
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.deathMessage(null);
        String loser = CoreUtil.id(dead), winner = duel.other(loser);
        Bukkit.getScheduler().runTask(plugin, () -> roundOver(duel, winner, loser));
    }

    private void roundOver(Duel duel, String winner, String loser) {
        if (duel.phase != Phase.LIVE) return;
        duel.rounds.merge(winner, 1, Integer::sum);
        int needed = duel.bestOf / 2 + 1;
        both(duel, "Round to " + name(winner) + " (" + duel.rounds.get(duel.a) + " - " + duel.rounds.get(duel.b) + ").");
        if (duel.rounds.get(winner) >= needed) { finish(duel, winner, loser); return; }
        Bukkit.getScheduler().runTaskLater(plugin, () -> beginRound(duel), 60L);
    }

    private void finish(Duel duel, String winner, String loser) {
        duel.phase = Phase.ENDING;
        double pot = db.arenaEscrowOf(duel.a) + db.arenaEscrowOf(duel.b);
        db.arenaEscrowClear(duel.a); db.arenaEscrowClear(duel.b);
        if (pot > 0) { db.changeBalance(winner, pot); db.recordEconomy(winner, "DUEL_WIN", pot, loser); }
        settleWagers(duel, winner);
        plugin.getServer().broadcast(Component.text("⚔ " + name(winner) + " defeats " + name(loser)
                + (pot > 0 ? " and takes " + CoreUtil.money(pot) : ""), NamedTextColor.GOLD));
        resetArena(duel);
        returnPlayers(duel);
        dispose(duel);
    }

    private void settleWagers(Duel duel, String winner) {
        double winningPool = 0, losingPool = 0;
        for (Wager w : duel.wagers) if (w.on().equals(winner)) winningPool += w.amount(); else losingPool += w.amount();
        if (winningPool <= 0) {
            if (losingPool > 0) plugin.bank().creditSink(losingPool, "ARENA", "SPECTATOR_POOL");
            db.arenaWagersClearFor(duel.a, duel.b);
            return;
        }
        for (Wager w : duel.wagers) {
            if (!w.on().equals(winner)) continue;
            double payout = Math.round((w.amount() + losingPool * (w.amount() / winningPool)) * 100) / 100.0;
            db.changeBalance(w.player(), payout);
            db.recordEconomy(w.player(), "DUEL_WAGER", payout, winner);
            Player better = plugin.getServer().getPlayer(w.player());
            if (better != null) CoreUtil.msg(better, "Your wager on " + name(winner) + " returned " + CoreUtil.money(payout) + ".");
        }
        db.arenaWagersClearFor(duel.a, duel.b);
    }

    boolean bet(Player player, int duelId, String on, double amount) {
        Duel duel = duels.stream().filter(d -> d.id == duelId).findFirst().orElse(null);
        if (duel == null) { CoreUtil.error(player, "No such match."); return true; }
        if (duel.phase == Phase.LIVE || duel.phase == Phase.ENDING) { CoreUtil.error(player, "Betting closed when the match started."); return true; }
        String id = CoreUtil.id(player);
        if (duel.has(id)) { CoreUtil.error(player, "You cannot bet on your own match."); return true; }
        String target = duel.aName.equalsIgnoreCase(on) ? duel.a : duel.bName.equalsIgnoreCase(on) ? duel.b : null;
        if (target == null) { CoreUtil.error(player, "Bet on " + duel.aName + " or " + duel.bName + "."); return true; }
        if (amount < 0 || !Double.isFinite(amount)) { CoreUtil.error(player, "Amount cannot be negative."); return true; }
        if (duel.wagers.stream().anyMatch(w -> w.player().equals(id))) { CoreUtil.error(player, "You already wagered on this match."); return true; }
        if (amount > 0 && !db.changeBalance(id, -amount)) { CoreUtil.error(player, "You cannot cover that."); return true; }
        duel.wagers.add(new Wager(id, target, amount));
        db.arenaWagerAdd(id, target, amount);
        CoreUtil.msg(player, "Wagered " + CoreUtil.money(amount) + " on " + name(target) + ".");
        return true;
    }

    boolean watch(Player player, int duelId) {
        Duel duel = duels.stream().filter(d -> d.id == duelId).findFirst().orElse(null);
        if (duel == null || duel.phase != Phase.LIVE) { CoreUtil.error(player, "That match is not running."); return true; }
        if (duel.has(CoreUtil.id(player))) { CoreUtil.error(player, "You are in this duel."); return true; }
        capture(player);
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(gallery(duel));
        actionbar(player, "Spectating. You cannot affect the fight.");
        return true;
    }

    boolean forfeit(Player player) {
        Duel duel = duelOf(player);
        if (duel == null) { CoreUtil.error(player, "You are not in a duel."); return true; }
        if (duel.phase == Phase.LIVE) {
            String loser = CoreUtil.id(player);
            both(duel, name(loser) + " forfeits.");
            finish(duel, duel.other(loser), loser);
        } else abortAndRefund(duel, "cancelled by " + player.getName());
        return true;
    }

    /** Belt-and-braces protection: only the two duellists in a LIVE match can damage each other; everyone
     *  else in the arena is untouchable. Runs at HIGHEST so a faction/PvP cancel at HIGH has already fired,
     *  and un-cancels it for a legitimate duel hit. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void combatOverride(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player hurt) || !inArena(hurt)) return;
        Player source = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player sh ? sh : null;
        Duel duel = duelOf(hurt);
        if (source != null && duel != null && duel.phase == Phase.LIVE && duel.has(CoreUtil.id(source)) && duel.has(CoreUtil.id(hurt))
                && !source.equals(hurt)) {
            /** A real duel hit: override any faction/friendly-fire/PvP-lock cancellation. */
            event.setCancelled(false);
            return;
        }
        /** Anyone else involved with an arena player — a spectator, an outsider — cannot deal or take damage. */
        if (source != null) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ building / map protection
    private long key(int x, int y, int z) { return org.bukkit.block.Block.getBlockKey(x, y, z); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (!inArena(event.getPlayer())) return;
        Duel duel = duelOf(event.getPlayer());
        if (duel == null || duel.phase != Phase.LIVE) { event.setCancelled(true); return; }
        org.bukkit.block.Block block = event.getBlockPlaced();
        duel.placed.add(key(block.getX(), block.getY(), block.getZ()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (!inArena(event.getPlayer())) return;
        Duel duel = duelOf(event.getPlayer());
        org.bukkit.block.Block block = event.getBlock();
        /** Only a block placed by a duellist this round may be broken. The arena map itself never can. */
        if (duel != null && duel.phase == Phase.LIVE && duel.placed.remove(key(block.getX(), block.getY(), block.getZ()))) {
            event.setDropItems(false);
            return;
        }
        event.setCancelled(true);
    }

    /** Clears every block the duellists placed and rebuilds the slot's floor, so no match leaves a mark. */
    private void resetArena(Duel duel) {
        if (arena == null) return;
        for (long k : duel.placed) {
            org.bukkit.block.Block block = arena.getBlockAtKey(k);
            block.setType(Material.AIR, false);
        }
        duel.placed.clear();
        buildSlot(duel.slot);
    }

    // ------------------------------------------------------------------ command blocking
    /** While actively duelling, every command is blocked except the ones that end the fight. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void command(PlayerCommandPreprocessEvent event) {
        if (!inLiveDuel(CoreUtil.id(event.getPlayer()))) return;
        String message = event.getMessage().toLowerCase(Locale.ROOT);
        if (message.equals("/duel forfeit") || message.equals("/duel status") || message.startsWith("/duel forfeit ")) return;
        event.setCancelled(true);
        actionbar(event.getPlayer(), "You are in a duel. Use /duel forfeit to give up.");
    }

    // ------------------------------------------------------------------ disconnect / lifecycle
    @EventHandler public void quit(PlayerQuitEvent event) {
        String id = CoreUtil.id(event.getPlayer());
        Duel duel = byPlayer.get(id);
        if (duel != null && duel.phase == Phase.LIVE && duel.has(id)) duel.disconnectedAt.put(id, System.currentTimeMillis());
        else if (inArena(event.getPlayer())) restore(event.getPlayer());
    }

    private void tick() {
        long grace = Math.max(5, plugin.getConfig().getLong("arena.reconnect-grace-seconds", 45)) * 1000L;
        for (Duel duel : new ArrayList<>(duels)) {
            if (duel.phase == Phase.PENDING && System.currentTimeMillis() - duel.pendingSince > 60000) { both(duel, "Challenge expired."); dispose(duel); continue; }
            if (duel.phase != Phase.LIVE) continue;
            for (Map.Entry<String, Long> entry : new LinkedHashMap<>(duel.disconnectedAt).entrySet()) {
                if (System.currentTimeMillis() - entry.getValue() < grace) continue;
                String loser = entry.getKey(), winner = duel.other(loser);
                both(duel, name(loser) + " did not reconnect in time.");
                finish(duel, winner, loser);
                break;
            }
        }
    }

    private void abortAndRefund(Duel duel, String why) {
        for (String id : List.of(duel.a, duel.b)) {
            double held = db.arenaEscrowOf(id);
            if (held > 0) { db.changeBalance(id, held); db.recordEconomy(id, "DUEL_REFUND", held, why); }
            db.arenaEscrowClear(id);
        }
        for (Wager w : duel.wagers) if (w.amount() > 0) db.changeBalance(w.player(), w.amount());
        db.arenaWagersClearFor(duel.a, duel.b);
        both(duel, "Duel cancelled — " + why + ". All stakes and wagers refunded.");
        resetArena(duel);
        returnPlayers(duel);
        dispose(duel);
    }

    private void returnPlayers(Duel duel) {
        for (String id : List.of(duel.a, duel.b)) { Player player = plugin.getServer().getPlayer(id); if (player != null) restore(player); }
        /** Any spectators sitting in this slot's gallery go home too. */
        if (arena != null) for (Player player : new ArrayList<>(arena.getPlayers()))
            if (!duel.has(CoreUtil.id(player)) && Math.abs(player.getLocation().getBlockX() - slotBaseX(duel.slot)) < 40 && db.arenaState(CoreUtil.id(player)) != null) restore(player);
    }

    private void dispose(Duel duel) {
        byPlayer.remove(duel.a); byPlayer.remove(duel.b);
        duels.remove(duel);
    }

    private String name(String id) { Player p = plugin.getServer().getPlayer(id); return p != null ? p.getName() : (byPlayer.containsKey(id) ? id : id); }

    private void both(Duel duel, String message) {
        Player one = a(duel), two = b(duel);
        if (one != null) CoreUtil.msg(one, message);
        if (two != null) CoreUtil.msg(two, message);
    }

    private void actionbar(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.AQUA));
    }

    List<int[]> liveMatches() {
        List<int[]> out = new ArrayList<>();
        for (Duel d : duels) if (d.phase == Phase.LIVE) out.add(new int[]{d.id});
        return out;
    }

    String status(Player viewer) {
        if (duels.isEmpty()) return "No duels are running.";
        StringBuilder sb = new StringBuilder("Matches:");
        for (Duel d : duels) sb.append("\n  #").append(d.id).append(" ").append(d.aName).append(" vs ").append(d.bName)
                .append(" | ").append(d.kit.label()).append(" | Bo").append(d.bestOf).append(" | ").append(d.phase);
        return sb.toString();
    }

    // ------------------------------------------------------------------ GUIs
    private final class Menu implements InventoryHolder {
        final String kind; final int duelId; Inventory inv;
        Menu(String kind, int duelId) { this.kind = kind; this.duelId = duelId; }
        @Override public Inventory getInventory() { return inv; }
    }

    void openHub(Player player) {
        Menu menu = new Menu("hub", 0);
        menu.inv = plugin.getServer().createInventory(menu, 27, Component.text("Duels", NamedTextColor.DARK_AQUA));
        menu.inv.setItem(11, icon(Material.DIAMOND_SWORD, "Challenge a player", List.of("Type /duel <player>", "or click a name below")));
        int slot = 0;
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.equals(player) || byPlayer.containsKey(CoreUtil.id(online)) || slot >= 7) continue;
            menu.inv.setItem(18 + slot++, icon(Material.PLAYER_HEAD, online.getName(), List.of("Click to challenge")));
        }
        menu.inv.setItem(15, icon(Material.ENDER_EYE, "Live matches", List.of(liveMatches().size() + " running", "Click to view / spectate / bet")));
        player.openInventory(menu.inv);
    }

    private void openSetup(Player player) {
        if (player == null) return;
        Duel duel = duelOf(player);
        if (duel == null) return;
        Menu menu = new Menu("setup", duel.id);
        menu.inv = plugin.getServer().createInventory(menu, 45, Component.text("Duel setup", NamedTextColor.DARK_AQUA));
        int[] kitSlots = {10, 12, 14, 16};
        Kit[] kits = Kit.values();
        for (int i = 0; i < kits.length; i++) {
            boolean sel = duel.kit == kits[i];
            menu.inv.setItem(kitSlots[i], icon(kits[i].icon(), (sel ? "✔ " : "") + kits[i].label(), List.of(kits[i].blurb(), sel ? "Selected" : "Click to pick")));
        }
        menu.inv.setItem(20, icon(duel.bestOf == 1 ? Material.LIME_DYE : Material.GRAY_DYE, "Best of 1", List.of(duel.bestOf == 1 ? "Selected" : "Click")));
        menu.inv.setItem(24, icon(duel.bestOf == 3 ? Material.LIME_DYE : Material.GRAY_DYE, "Best of 3", List.of(duel.bestOf == 3 ? "Selected" : "Click")));
        double mine = duel.stakes.getOrDefault(CoreUtil.id(player), 0d);
        /** Stakes are set right here in the GUI, not only via chat: a row of - / + buttons around the
         *  current figure. Independent per player and clamped to what they can afford. */
        menu.inv.setItem(29, icon(Material.RED_STAINED_GLASS_PANE, "- 10,000", List.of("Lower your stake")));
        menu.inv.setItem(30, icon(Material.PINK_STAINED_GLASS_PANE, "- 1,000", List.of("Lower your stake")));
        menu.inv.setItem(31, icon(Material.GOLD_INGOT, "Your stake: " + CoreUtil.money(mine),
                List.of("Buttons on the left lower, right raise", "Stakes need not match; $0 is allowed", "Shift-click to clear")));
        menu.inv.setItem(32, icon(Material.LIME_STAINED_GLASS_PANE, "+ 1,000", List.of("Raise your stake")));
        menu.inv.setItem(33, icon(Material.GREEN_STAINED_GLASS_PANE, "+ 10,000", List.of("Raise your stake")));
        menu.inv.setItem(34, icon(Material.EMERALD_BLOCK, "+ 100,000", List.of("Raise your stake")));
        boolean ready = duel.confirmed.contains(CoreUtil.id(player));
        menu.inv.setItem(40, icon(ready ? Material.YELLOW_CONCRETE : Material.LIME_CONCRETE, ready ? "Waiting for opponent…" : "Confirm", List.of(
                "You: " + duel.aName + " " + CoreUtil.money(duel.stakes.getOrDefault(duel.a, 0d)),
                "Them: " + duel.bName + " " + CoreUtil.money(duel.stakes.getOrDefault(duel.b, 0d)))));
        player.openInventory(menu.inv);
    }

    private void refreshSetup(Duel duel) { openSetup(a(duel)); openSetup(b(duel)); }

    /** GUI stake adjustment: nudge the stake up or down, clamped to what the player can actually cover. */
    private void adjustStake(Player player, double delta) {
        Duel duel = duelOf(player);
        if (duel == null || duel.phase != Phase.STAKING) return;
        double current = duel.stakes.getOrDefault(CoreUtil.id(player), 0d);
        double balance = db.player(CoreUtil.id(player)).balance();
        setStakeSilent(player, Math.max(0, Math.min(current + delta, balance)));
    }

    private void setStakeSilent(Player player, double amount) {
        Duel duel = duelOf(player);
        if (duel == null) return;
        duel.stakes.put(CoreUtil.id(player), amount);
        duel.confirmed.remove(CoreUtil.id(player));
        refreshSetup(duel);
    }

    private ItemStack icon(Material material, String name, List<String> lore) { return CoreUtil.named(material, name, lore); }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        ItemStack clicked = event.getCurrentItem();
        switch (menu.kind) {
            case "hub" -> {
                if (clicked != null && clicked.getType() == Material.PLAYER_HEAD) {
                    String target = PlainName(clicked);
                    if (target != null) { player.closeInventory(); challenge(player, target); }
                } else if (slot == 15) { player.closeInventory(); CoreUtil.msg(player, status(player)); }
            }
            case "setup" -> {
                Kit[] kits = Kit.values();
                int[] kitSlots = {10, 12, 14, 16};
                for (int i = 0; i < kits.length; i++) if (slot == kitSlots[i]) { setKit(player, kits[i].name()); return; }
                switch (slot) {
                    case 20 -> setSeries(player, 1);
                    case 24 -> setSeries(player, 3);
                    case 29 -> adjustStake(player, -10000);
                    case 30 -> adjustStake(player, -1000);
                    case 31 -> { if (event.isShiftClick()) setStakeSilent(player, 0); }
                    case 32 -> adjustStake(player, 1000);
                    case 33 -> adjustStake(player, 10000);
                    case 34 -> adjustStake(player, 100000);
                    case 40 -> { player.closeInventory(); confirm(player); }
                    default -> { }
                }
            }
            default -> { }
        }
    }

    @EventHandler public void drag(InventoryDragEvent event) { if (event.getInventory().getHolder(false) instanceof Menu) event.setCancelled(true); }

    private String PlainName(ItemStack head) {
        if (head == null || !head.hasItemMeta() || !head.getItemMeta().hasDisplayName()) return null;
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(head.getItemMeta().displayName());
    }

    // ------------------------------------------------------------------ tab completion source
    List<String> onlineChallengeable(Player from) {
        List<String> out = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers())
            if (!p.equals(from) && !byPlayer.containsKey(CoreUtil.id(p))) out.add(p.getName());
        return out;
    }

    List<String> liveMatchIds() {
        List<String> out = new ArrayList<>();
        for (Duel d : duels) if (d.phase == Phase.LIVE || d.phase == Phase.STAKING || d.phase == Phase.PENDING) out.add(String.valueOf(d.id));
        return out;
    }

    List<String> duellistNames(int duelId) {
        Duel d = duels.stream().filter(x -> x.id == duelId).findFirst().orElse(null);
        return d == null ? List.of() : List.of(d.aName, d.bName);
    }

    boolean selfTest() {
        for (Kit k : Kit.values()) {
            List<ItemStack> a = kitContents(k), b = kitContents(k);
            if (a.size() != b.size() || a.isEmpty()) return false;
            for (int i = 0; i < a.size(); i++) if (!a.get(i).isSimilar(b.get(i))) return false;
        }
        double winning = 300, losing = 700, payout = 0;
        for (double stake : new double[]{100, 200}) payout += stake + losing * (stake / winning);
        return Math.abs(payout - (winning + losing)) < 0.01;
    }
}
