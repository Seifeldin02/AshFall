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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
        SPEAR("Spear", Material.DIAMOND_SPEAR, "Reach and Lunge. Charge in, jab, and keep your distance.");
        private final String label, blurb;
        private final Material icon;
        Kit(String label, Material icon, String blurb) { this.label = label; this.icon = icon; this.blurb = blurb; }
        String label() { return label; }
        String blurb() { return blurb; }
        Material icon() { return icon; }
    }

    private record Wager(String player, String on, double amount, int round) {}

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
        /** True while a round's loss is being processed, so two near-simultaneous lethal hits or a
         *  disconnect racing a death cannot resolve the same round twice. Reset at each round start. */
        boolean resolving = false;
        /** True while a round sits in its ready-gate: both duellists are at their corners, frozen and fully
         *  re-equipped, and neither can be hurt until each has clicked Ready. The fight begins only when both
         *  are ready. Spectators may place their bets during the gate. */
        boolean gating = false;
        /** Ids that have clicked Ready for the current round; cleared when each round's gate opens. */
        final Set<String> roundReady = new HashSet<>();
        Duel(int id, int slot, String a, String b) { this.id = id; this.slot = slot; this.a = a; this.b = b; }
        boolean has(String id) { return id.equals(a) || id.equals(b); }
        String other(String id) { return id.equals(a) ? b : a; }
    }

    private final SMPCore plugin;
    private final Database db;
    /** player id -> the duel they are in (as duellist). One duel per player at a time. */
    private final Map<String, Duel> byPlayer = new ConcurrentHashMap<>();
    /** player id -> duel id they are spectating in the dedicated duel-spectator state. */
    private final Map<String, Integer> spectators = new ConcurrentHashMap<>();
    /** player id -> {side: 0=A 1=B -1=none, amount} staged in the betting GUI before it is confirmed. */
    private final Map<String, double[]> stagedBet = new ConcurrentHashMap<>();
    private final List<Duel> duels = new ArrayList<>();
    private int nextId = 1;
    /** Last time each player sent a challenge, for the anti-spam cooldown, exactly like a trade request. */
    private final Map<String, Long> lastChallenge = new ConcurrentHashMap<>();
    private World arena;
    /** Slots whose structure has already been built, so it is only laid once, not every round. */
    private final Map<Integer,Integer> builtSize = new java.util.HashMap<>();
    private final Set<Integer> building = new HashSet<>();
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

    /** Arena size by kit: the Spear gets a bigger floor for its mobility; every other kit shares the default. */
    private int sizeFor(Kit kit) { return kit == Kit.SPEAR ? 100 : 50; }

    /** Black-and-red aesthetic. Floor is mostly red terracotta with sparse glowstone for light; walls are mostly
     *  obsidian with sparse glowstone. Deterministic per-coordinate so a rebuild reproduces the identical map. */
    private Material floorMat(int x, int z) { return Math.floorMod(x * 3 + z * 7, 5) == 0 ? Material.GLOWSTONE : Material.RED_TERRACOTTA; }
    private Material wallMat(int x, int y, int z) { return Math.floorMod(x * 5 + z * 3 + y * 2, 6) == 0 ? Material.GLOWSTONE : Material.OBSIDIAN; }

    /** Ensures the slot's arena exists at the requested size, THEN runs onReady. The full-height perimeter walls
     *  are tens of thousands of blocks, so the build is spread one vertical band per tick rather than frozen
     *  into a single tick -- the match only begins once the structure is finished. Cached per (slot,size); a
     *  slot reused at a different size has its previous shell cleared first, so spear (100) and the default (50)
     *  can share slots. The structure is never rebuilt between rounds (it is unbreakable and simply persists). */
    private void ensureArena(int slot, int size, Runnable onReady) {
        if (arena == null) { onReady.run(); return; }
        Integer cur = builtSize.get(slot);
        if (cur != null && cur == size) { onReady.run(); return; }
        if (!building.add(slot)) { Bukkit.getScheduler().runTaskLater(plugin, () -> ensureArena(slot, size, onReady), 10L); return; }
        int old = cur == null ? 0 : cur, cx = slotBaseX(slot), topY = arena.getMaxHeight() - 1;
        List<Runnable> steps = new ArrayList<>();
        if (old > 0 && old != size) addShellSteps(steps, cx, old, topY, true);
        /** Wipe any leftover low structure at this slot (e.g. the old procedural arena, or a previous larger
     *  floor/interior) before laying the new map, so the arena is always clean. One Y-layer per tick over a
     *  footprint that covers the old 61x61 arena plus this size, up to a modest height. */
        int clearHalf = Math.max(size / 2 + 1, 32);
        for (int cy = FLOOR_Y; cy <= FLOOR_Y + 12; cy++) {
            final int y = cy, x0 = cx - clearHalf, x1 = cx + clearHalf;
            steps.add(() -> { for (int x = x0; x <= x1; x++) for (int z = -clearHalf; z <= clearHalf; z++) arena.getBlockAt(x, y, z).setType(Material.AIR, false); });
        }
        int h = size / 2, minX = cx - h, maxX = cx + h - 1, minZ = -h, maxZ = h - 1;
        steps.add(() -> { for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) arena.getBlockAt(x, FLOOR_Y, z).setType(floorMat(x, z), false); });
        addShellSteps(steps, cx, size, topY, false);
        runSteps(steps, () -> { builtSize.put(slot, size); building.remove(slot); plugin.getLogger().info("[Arena] built slot " + slot + " (" + size + "x" + size + ")"); onReady.run(); });
    }

    /** Perimeter-wall steps for a `size` arena, one 24-block vertical band per step so no tick sets too many
     *  blocks. clear=true air's the shell (to wipe a previous size); clear=false lays glowstone/obsidian with a
     *  bedrock cap on the very top row. Walls run floor+1 up to world height, fully enclosing the arena. */
    private void addShellSteps(List<Runnable> steps, int cx, int size, int topY, boolean clear) {
        int h = size / 2, minX = cx - h, maxX = cx + h - 1, minZ = -h, maxZ = h - 1;
        for (int base = FLOOR_Y + 1; base <= topY; base += 24) {
            final int y0 = base, y1 = Math.min(topY, base + 23);
            steps.add(() -> {
                for (int y = y0; y <= y1; y++) {
                    for (int x = minX; x <= maxX; x++) { setShell(x, y, minZ, y == topY, clear); setShell(x, y, maxZ, y == topY, clear); }
                    for (int z = minZ + 1; z < maxZ; z++) { setShell(minX, y, z, y == topY, clear); setShell(maxX, y, z, y == topY, clear); }
                }
            });
        }
    }
    private void setShell(int x, int y, int z, boolean top, boolean clear) {
        arena.getBlockAt(x, y, z).setType(clear ? Material.AIR : (top ? Material.BEDROCK : wallMat(x, y, z)), false);
    }
    private void runSteps(List<Runnable> steps, Runnable done) {
        java.util.Iterator<Runnable> it = steps.iterator();
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                if (it.hasNext()) it.next().run();
                if (!it.hasNext()) { cancel(); done.run(); }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private Location corner(Duel duel, int index) {
        int cx = slotBaseX(duel.slot), h = sizeFor(duel.kit) / 2;
        return new Location(arena, cx + (index == 0 ? -h + 3.5 : h - 4.5), FLOOR_Y + 1, 0.5, index == 0 ? 90f : -90f, 0f);
    }

    /** Spectators watch from INSIDE the arena (they are invisible, flying and non-colliding), since the
     *  full-height walls make an outside gallery useless. Placed above the centre, looking down. */
    private Location gallery(Duel duel) { return new Location(arena, slotBaseX(duel.slot) + 0.5, FLOOR_Y + 6, 0.5, 0f, 25f); }

    private boolean inArena(Player player) { return arena != null && player.getWorld().equals(arena); }
    boolean isArenaWorld(org.bukkit.World world) { return arena != null && arena.equals(world); }
    /** True for the fillable duel item-wager box, so the relic storage guard can allow relics to be staked into
     *  it. Relics staked here are escrowed and awarded to the winner (whose next inventory scan transfers
     *  ownership) -- they are never actually "stored", and only one physical copy ever exists. */
    boolean isWagerBox(org.bukkit.inventory.Inventory inv) { return inv != null && inv.getHolder(false) instanceof Menu m && m.fillable && "wagerbox".equals(m.kind); }
    /** True when a and b are the two duellists of the SAME live match -- used to let their hits through the
     *  faction/spawn PvP guards without the spurious "friendly PvP disabled" message, and to suppress the
     *  PvP-lock actionbar for them. */
    boolean areDuelOpponents(Player a, Player b) {
        Duel d = byPlayer.get(CoreUtil.id(a));
        return d != null && d.phase == Phase.LIVE && d.has(CoreUtil.id(a)) && d.has(CoreUtil.id(b));
    }

    // ------------------------------------------------------------------ kits
    /** The full standardized kit as one list (armour, then weapon, then offhand if any, then consumables).
     *  Used for the self-test's parity check and for reporting exactly what each side receives. */
    /** The full kit (armour, offhand, hotbar, then inventory extras) -- for the parity self-test and for
     *  reporting exactly what each side receives. Both duellists always get identical copies. */
    List<ItemStack> kitContents(Kit kit) {
        List<ItemStack> all = new ArrayList<>(kitArmour(kit));
        ItemStack offhand = kitOffhand(kit);
        if (offhand != null) all.add(offhand);
        all.addAll(kitHotbar(kit));
        all.addAll(kitExtra(kit));
        return all;
    }

    /** Helmet, chest, legs, boots. Netherite tier. The Spear wears an ELYTRA as its chest (it also carries a
     *  spare netherite chestplate in the hotbar to swap into if grounded). */
    private List<ItemStack> kitArmour(Kit kit) {
        List<ItemStack> a = new ArrayList<>();
        a.add(armour(Material.NETHERITE_HELMET));
        a.add(kit == Kit.SPEAR ? enchanted(Material.ELYTRA, Map.of(Enchantment.UNBREAKING, 3)) : armour(Material.NETHERITE_CHESTPLATE));
        a.add(armour(Material.NETHERITE_LEGGINGS));
        a.add(armour(Material.NETHERITE_BOOTS));
        return a;
    }

    private ItemStack kitOffhand(Kit kit) {
        return switch (kit) {
            case MACE, SWORD, AXE -> enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3));
            case SPEAR -> new ItemStack(Material.TOTEM_OF_UNDYING);
        };
    }

    /** Hotbar, slots 0-8, laid out as the researched modern kits. */
    private List<ItemStack> kitHotbar(Kit kit) {
        return switch (kit) {
            case MACE -> List.of(
                enchanted(Material.MACE, Map.of(Enchantment.DENSITY, 5, Enchantment.WIND_BURST, 3, Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3)),
                new ItemStack(Material.WIND_CHARGE, 64), new ItemStack(Material.GOLDEN_APPLE, 16), new ItemStack(Material.ENDER_PEARL, 16),
                splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1),
                splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1),
                new ItemStack(Material.WATER_BUCKET));
            case SWORD -> List.of(
                enchanted(Material.NETHERITE_SWORD, Map.of(Enchantment.SHARPNESS, 5, Enchantment.FIRE_ASPECT, 2, Enchantment.UNBREAKING, 3)),
                enchanted(Material.BOW, Map.of(Enchantment.POWER, 5)), new ItemStack(Material.GOLDEN_APPLE, 16), new ItemStack(Material.CROSSBOW),
                splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1),
                splash(org.bukkit.potion.PotionType.STRONG_SWIFTNESS, 1), splash(org.bukkit.potion.PotionType.FIRE_RESISTANCE, 1),
                new ItemStack(Material.WATER_BUCKET));
            case AXE -> List.of(
                enchanted(Material.NETHERITE_AXE, Map.of(Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3)),
                enchanted(Material.NETHERITE_SWORD, Map.of(Enchantment.SHARPNESS, 5)), new ItemStack(Material.GOLDEN_APPLE, 16), new ItemStack(Material.COOKED_BEEF, 16),
                new ItemStack(Material.CROSSBOW), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1),
                splash(org.bukkit.potion.PotionType.STRONG_SWIFTNESS, 1), new ItemStack(Material.WATER_BUCKET));
            /** Elytra Spear: Netherite Spear (Lunge for the speed bursts) with rockets to stay airborne,
             *  pearls to dodge dives, gapples for the hunger drain, a spare chestplate to hotswap if grounded,
             *  splash heals and a water bucket. */
            case SPEAR -> List.of(
                enchanted(Material.NETHERITE_SPEAR, Map.of(Enchantment.LUNGE, Math.max(1, Enchantment.LUNGE.getMaxLevel()), Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3)),
                new ItemStack(Material.FIREWORK_ROCKET, 64), new ItemStack(Material.ENDER_PEARL, 16), new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2),
                armour(Material.NETHERITE_CHESTPLATE), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1),
                new ItemStack(Material.WATER_BUCKET), new ItemStack(Material.GOLDEN_APPLE, 16));
        };
    }

    /** Inventory rows (slots 9+): backups. */
    private List<ItemStack> kitExtra(Kit kit) {
        return switch (kit) {
            case MACE -> List.of(new ItemStack(Material.WIND_CHARGE, 64), new ItemStack(Material.GOLDEN_APPLE, 16), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 8));
            case SWORD -> List.of(new ItemStack(Material.ARROW, 64), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 4),
                enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3)), enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3)));
            case AXE -> List.of(enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3)), enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3)),
                enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3)), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 4), new ItemStack(Material.ARROW, 32));
            case SPEAR -> List.of(new ItemStack(Material.FIREWORK_ROCKET, 64), new ItemStack(Material.TOTEM_OF_UNDYING, 2), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 4));
        };
    }

    private ItemStack splash(org.bukkit.potion.PotionType type, int count) {
        ItemStack item = new ItemStack(Material.SPLASH_POTION, count);
        org.bukkit.inventory.meta.PotionMeta meta = (org.bukkit.inventory.meta.PotionMeta) item.getItemMeta();
        meta.setBasePotionType(type);
        item.setItemMeta(meta);
        return item;
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
        var inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(null);
        inv.setItemInOffHand(null);
        player.setItemOnCursor(null);
        List<ItemStack> armour = kitArmour(kit);
        inv.setHelmet(armour.get(0));
        inv.setChestplate(armour.get(1));
        inv.setLeggings(armour.get(2));
        inv.setBoots(armour.get(3));
        ItemStack offhand = kitOffhand(kit);
        if (offhand != null) inv.setItemInOffHand(offhand);
        List<ItemStack> hotbar = kitHotbar(kit);
        for (int i = 0; i < hotbar.size() && i < 9; i++) inv.setItem(i, hotbar.get(i));
        for (ItemStack extra : kitExtra(kit)) inv.addItem(extra);
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setExhaustion(0);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.setRemainingAir(player.getMaximumAir());
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
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
                at.getYaw(), at.getPitch(), player.getLevel(), player.getExp(), player.getHealth(), player.getFoodLevel(),
                player.getGameMode().name(), encodeExtra(player));
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
        player.setHealth(Math.min(state.health(), player.getAttribute(Attribute.MAX_HEALTH).getValue()));
        try { player.setGameMode(GameMode.valueOf(state.gamemode())); } catch (IllegalArgumentException ignored) { player.setGameMode(GameMode.SURVIVAL); }
        /** Clear the spectator-only flags so nobody ever leaves invisible, non-colliding or invulnerable. */
        player.setInvisible(false);
        player.setCollidable(true);
        player.setInvulnerable(false);
        /** Saturation, potion effects, flight, fall distance, fire and air -- everything the duel touched
         *  that is not already a column -- so the player is returned to EXACTLY their pre-duel state. */
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        applyExtra(player, state.extra());
        /** Clear any PvP combat tag the fight left behind so it does not follow them out. */
        if (plugin.teleports() != null) plugin.teleports().clearCombat(player);
        db.arenaStateClear(id);
        player.updateInventory();
    }

    /** Packs everything not already a column -- saturation, flight, fall/fire/air, and the full potion
     *  effect list -- into one string. */
    private String encodeExtra(Player p) {
        StringBuilder sb = new StringBuilder();
        sb.append("sat=").append(p.getSaturation());
        sb.append(";exh=").append(p.getExhaustion());
        sb.append(";allowfly=").append(p.getAllowFlight() ? 1 : 0);
        sb.append(";flying=").append(p.isFlying() ? 1 : 0);
        sb.append(";fall=").append(p.getFallDistance());
        sb.append(";fire=").append(p.getFireTicks());
        sb.append(";air=").append(p.getRemainingAir());
        sb.append(";fx=");
        boolean first = true;
        for (PotionEffect e : p.getActivePotionEffects()) {
            if (!first) sb.append("|"); first = false;
            sb.append(e.getType().getKey().getKey()).append(",").append(e.getAmplifier()).append(",")
              .append(e.getDuration()).append(",").append(e.isAmbient() ? 1 : 0).append(",").append(e.hasParticles() ? 1 : 0);
        }
        return sb.toString();
    }

    private void applyExtra(Player p, String extra) {
        if (extra == null || extra.isBlank()) { p.setFireTicks(0); p.setFallDistance(0); return; }
        for (String part : extra.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq), v = part.substring(eq + 1);
            try {
                switch (k) {
                    case "sat" -> p.setSaturation(Float.parseFloat(v));
                    case "exh" -> p.setExhaustion(Float.parseFloat(v));
                    case "allowfly" -> p.setAllowFlight(v.equals("1"));
                    case "flying" -> { if (v.equals("1") && p.getAllowFlight()) p.setFlying(true); }
                    case "fall" -> p.setFallDistance(Float.parseFloat(v));
                    case "fire" -> p.setFireTicks(Integer.parseInt(v));
                    case "air" -> p.setRemainingAir(Integer.parseInt(v));
                    case "fx" -> { if (!v.isEmpty()) for (String fx : v.split("\\|")) {
                        String[] f = fx.split(",");
                        PotionEffectType type = org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(f[0]));
                        if (type != null) p.addPotionEffect(new PotionEffect(type, Integer.parseInt(f[2]), Integer.parseInt(f[1]), f[3].equals("1"), f[4].equals("1")));
                    } }
                }
            } catch (Exception ignored) { }
        }
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
        db.arenaItemWagerRefundAll((player, items) -> giveOrStash(player, items, "Your wagered duel items were returned after the restart."));
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
        plugin.afk().notifyIfAfk(from, target);
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
        plugin.getServer().broadcast(Component.text("⚔ " + one.getName() + " vs " + two.getName() + " — "
                + duel.kit.label() + ", best of " + duel.bestOf + ". /duel watch " + duel.id + " to spectate; bet before it starts.", NamedTextColor.GOLD));
        ensureArena(duel.slot, sizeFor(duel.kit), () -> beginRound(duel));
    }

    /** Opens the round: clean the arena, teleport both duellists to their corners, fully re-equip and heal
     *  them (equip() already tops health/food and clears effects), then FREEZE them behind a ready-gate. The
     *  round does not begin until each has clicked Ready. Between rounds this runs again, so every round starts
     *  from an identical clean, healed, ready-gated state. */
    private void beginRound(Duel duel) {
        Player one = a(duel), two = b(duel);
        if (one == null || two == null) { abortAndRefund(duel, "a duellist went offline"); return; }
        resetArena(duel);
        duel.resolving = false;
        duel.gating = true;
        duel.roundReady.clear();
        capture(one); capture(two);
        one.teleport(corner(duel, 0)); two.teleport(corner(duel, 1));
        equip(one, duel.kit); equip(two, duel.kit);
        int round = duel.rounds.get(duel.a) + duel.rounds.get(duel.b) + 1;
        both(duel, "Round " + round + " — ready up when you are set. Spectators may bet now.");
        openReady(one, duel); openReady(two, duel);
        refreshSpectate(duel);
    }

    /** The ready-gate GUI: a Ready button plus a live view of both sides' ready state. */
    private void openReady(Player player, Duel duel) {
        if (player == null) return;
        Menu menu = new Menu("ready", duel.id);
        menu.inv = plugin.getServer().createInventory(menu, 27, Component.text("Ready up", NamedTextColor.DARK_AQUA));
        for (int i = 0; i < 27; i++) menu.inv.setItem(i, filler());
        String meId = CoreUtil.id(player), themId = duel.other(meId);
        boolean meReady = duel.roundReady.contains(meId), themReady = duel.roundReady.contains(themId);
        int round = duel.rounds.get(duel.a) + duel.rounds.get(duel.b) + 1;
        String themName = meId.equals(duel.a) ? duel.bName : duel.aName;
        menu.inv.setItem(4, icon(duel.kit.icon(), "Round " + round + " of best-of-" + duel.bestOf, List.of(
                "Score " + duel.rounds.getOrDefault(duel.a, 0) + " - " + duel.rounds.getOrDefault(duel.b, 0),
                "Kit " + duel.kit.label())));
        menu.inv.setItem(13, icon(meReady ? Material.YELLOW_CONCRETE : Material.LIME_CONCRETE,
                meReady ? "Ready — waiting for " + themName + "…" : "Click when you are ready", List.of(
                "You: " + (meReady ? "READY" : "not ready"),
                themName + ": " + (themReady ? "READY" : "not ready"),
                "The round starts the instant you are both ready.")));
        player.openInventory(menu.inv);
    }

    private void refreshReady(Duel duel) { if (duel.gating) { openReady(a(duel), duel); openReady(b(duel), duel); } }

    /** Both sides are ready: drop the gate and start the fight. */
    private void startFight(Duel duel) {
        if (!duel.gating) return;
        Player one = a(duel), two = b(duel);
        if (one == null || two == null) { abortAndRefund(duel, "a duellist went offline"); return; }
        duel.gating = false;
        one.closeInventory(); two.closeInventory();
        int round = duel.rounds.get(duel.a) + duel.rounds.get(duel.b) + 1;
        actionbar(one, "Round " + round + " — fight!"); actionbar(two, "Round " + round + " — fight!");
        refreshSpectate(duel);
    }

    /** While gated, a duellist is held on their block (they may still look around) and cannot be hurt. */
    @EventHandler(ignoreCancelled = true) public void freeze(PlayerMoveEvent event) {
        Duel duel = duelOf(event.getPlayer());
        if (duel == null || !duel.gating) return;
        Location from = event.getFrom(), to = event.getTo();
        if (to == null) return;
        if (from.getBlockX() != to.getBlockX() || from.getBlockZ() != to.getBlockZ() || to.getY() > from.getY() + 0.02) {
            Location held = from.clone(); held.setYaw(to.getYaw()); held.setPitch(to.getPitch());
            event.setTo(held);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST) public void gateShield(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player hurt)) return;
        Duel duel = duelOf(hurt);
        if (duel != null && duel.gating && inArena(hurt)) event.setCancelled(true);
    }

    /** A gated duellist cannot escape the ready-gate by closing it -- it reopens until they ready up (or the
     *  fight starts / match ends). The one-tick delay and the "already showing ready" check keep this from
     *  fighting the refresh openInventory calls. */
    @EventHandler public void closeGate(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Menu menu) || !menu.kind.equals("ready")) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        String id = CoreUtil.id(player);
        Duel duel = find(menu.duelId);
        if (duel == null || !duel.gating || !duel.has(id) || duel.roundReady.contains(id)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            Duel cur = byPlayer.get(id);
            if (cur != null && cur.gating && !cur.roundReady.contains(id)
                    && !(player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu m && m.kind.equals("ready")))
                openReady(player, cur);
        }, 2L);
    }

    /** The round is resolved by INTERCEPTING the killing blow, not by a real death. A duellist whose hit
     *  would drop them to zero has the damage cancelled, their health topped up, and the round awarded --
     *  so PlayerDeathEvent never fires for a duellist, which means no grave, no drops, no respawn yank, no
     *  economy or faction side effect can ever occur. Void, fire and fall damage all arrive here too. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void lethal(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player hurt)) return;
        Duel duel = duelOf(hurt);
        if (duel == null || duel.phase != Phase.LIVE || duel.gating || !inArena(hurt)) return;
        if (duel.resolving) { event.setCancelled(true); return; }
        if (hurt.getHealth() - event.getFinalDamage() > 0.0001) return;
        /** Let a Totem of Undying do its job: if the duellist is holding one, DON'T intercept -- vanilla pops
         *  it, revives them, and the round continues. Only a genuinely fatal blow with no totem ends the round.
         *  (The spear kit carries a totem; the old unconditional intercept made it useless in duels.) */
        if (hurt.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || hurt.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) return;
        event.setCancelled(true);
        duel.resolving = true;
        hurt.setHealth(hurt.getAttribute(Attribute.MAX_HEALTH).getValue());
        hurt.setFireTicks(0);
        String loser = CoreUtil.id(hurt), winner = duel.other(loser);
        both(duel, name(loser) + " is down.");
        Bukkit.getScheduler().runTask(plugin, () -> roundOver(duel, winner, loser));
    }

    /** Safety net only: a duellist should never actually die (see lethal() above), but if something bypasses
     *  damage entirely -- /kill, a plugin -- strip every death side effect and resolve the round. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void death(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Duel duel = duelOf(dead);
        if (duel == null || !inArena(dead)) return;
        event.getDrops().clear();
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.deathMessage(null);
        if (duel.phase != Phase.LIVE || duel.resolving) return;
        duel.resolving = true;
        String loser = CoreUtil.id(dead), winner = duel.other(loser);
        Bukkit.getScheduler().runTask(plugin, () -> roundOver(duel, winner, loser));
    }

    /** If a duellist ever does die, respawn them at their captured spot and restore fully, rather than
     *  letting vanilla fling them to the overworld spawn. */
    @EventHandler
    public void respawn(PlayerRespawnEvent event) {
        Database.ArenaState state = db.arenaState(CoreUtil.id(event.getPlayer()));
        if (state == null) return;
        World world = Bukkit.getWorld(state.world());
        if (world != null) event.setRespawnLocation(new Location(world, state.x(), state.y(), state.z(), state.yaw(), state.pitch()));
        Bukkit.getScheduler().runTask(plugin, () -> { if (event.getPlayer().isOnline()) restore(event.getPlayer()); });
    }

    private void roundOver(Duel duel, String winner, String loser) {
        if (duel.phase != Phase.LIVE) return;
        int roundNo = duel.rounds.get(duel.a) + duel.rounds.get(duel.b) + 1;
        duel.rounds.merge(winner, 1, Integer::sum);
        int needed = duel.bestOf / 2 + 1;
        settleWagerScope(duel, roundNo, winner, "round " + roundNo);
        refreshSpectate(duel);
        both(duel, "Round to " + name(winner) + " (" + duel.rounds.get(duel.a) + " - " + duel.rounds.get(duel.b) + ").");
        if (duel.rounds.get(winner) >= needed) { finish(duel, winner, loser); return; }
        Bukkit.getScheduler().runTaskLater(plugin, () -> beginRound(duel), 60L);
    }

    private void finish(Duel duel, String winner, String loser) {
        /** Idempotent: a death and a disconnect-forfeit can both resolve the same match in one tick, and the
         *  escrow is only paid out once. */
        if (duel.phase != Phase.LIVE) return;
        duel.phase = Phase.ENDING;
        double pot = db.arenaEscrowOf(duel.a) + db.arenaEscrowOf(duel.b);
        db.arenaEscrowClear(duel.a); db.arenaEscrowClear(duel.b);
        if (pot > 0) {
            double potTax = Math.round(pot * wagerTax() * 100) / 100.0, potNet = pot - potTax;
            db.changeBalance(winner, potNet); db.recordEconomy(winner, "DUEL_WIN", potNet, loser);
            if (potTax > 0) plugin.bank().creditFee(potTax, winner, "DUEL_POT_TAX");
        }
        settleWagerScope(duel, 0, winner, "match");
        plugin.getServer().broadcast(Component.text("⚔ " + name(winner) + " defeats " + name(loser)
                + (pot > 0 ? " and takes " + CoreUtil.money(pot) : ""), NamedTextColor.GOLD));
        resetArena(duel);
        returnPlayers(duel);
        awardItemWagers(duel, winner, loser);
        dispose(duel);
    }

    /** Settle every wager in ONE scope: round 0 is the whole-match pool (paid at finish); round N is that
     *  round's own pool (paid the moment round N ends). Winners split the losing side of the SAME scope and get
     *  their own stake back; if nobody backed the winner the losing pool is sunk. Settled wagers are removed and
     *  the DB mirror is resynced so the remaining (still-open) wagers stay crash-safe. */
    /** Slight Central Bank cut taken from duel winnings (the money pot and the spectator betting pool). */
    private double wagerTax() { return Math.max(0, Math.min(50, plugin.getConfig().getDouble("arena.wager-tax-percent", 5))) / 100.0; }
    private void settleWagerScope(Duel duel, int round, String winner, String label) {
        List<Wager> scope = new ArrayList<>();
        for (Wager w : duel.wagers) if (w.round() == round) scope.add(w);
        if (scope.isEmpty()) return;
        double winningPool = 0, losingPool = 0;
        for (Wager w : scope) if (w.on().equals(winner)) winningPool += w.amount(); else losingPool += w.amount();
        if (winningPool <= 0) {
            if (losingPool > 0) plugin.bank().creditSink(losingPool, "ARENA", "SPECTATOR_POOL");
        } else {
            double betTax = Math.round(losingPool * wagerTax() * 100) / 100.0;
            if (betTax > 0) { plugin.bank().creditFee(betTax, winner, "DUEL_BET_TAX"); losingPool -= betTax; }
            for (Wager w : scope) {
                if (!w.on().equals(winner)) continue;
                double payout = Math.round((w.amount() + losingPool * (w.amount() / winningPool)) * 100) / 100.0;
                db.changeBalance(w.player(), payout);
                db.recordEconomy(w.player(), "DUEL_WAGER", payout, winner);
                Player better = plugin.getServer().getPlayer(w.player());
                if (better != null) CoreUtil.msg(better, "Your " + label + " wager on " + name(winner) + " returned " + CoreUtil.money(payout) + ".");
            }
        }
        duel.wagers.removeAll(scope);
        syncWagerDb(duel);
    }

    /** Rewrite the arena_wagers DB mirror from the wagers still open in memory (used after a scope settles). */
    private void syncWagerDb(Duel duel) {
        db.arenaWagersClearFor(duel.a, duel.b);
        for (Wager w : duel.wagers) db.arenaWagerAdd(w.player(), w.on(), w.amount(), w.round());
    }

    boolean bet(Player player, int duelId, String on, double amount) {
        Duel duel = duels.stream().filter(d -> d.id == duelId).findFirst().orElse(null);
        if (duel == null) { CoreUtil.error(player, "No such match."); return true; }
        String target = duel == null ? null : (duel.aName.equalsIgnoreCase(on) ? duel.a : duel.bName.equalsIgnoreCase(on) ? duel.b : null);
        return placeWager(player, duel, target, amount, 0);
    }

    /** True while spectators may place or change bets: before the match (pending/staking) and during every
     *  round's ready-gate. It is closed only while a round is actively being fought. */
    private boolean bettingOpen(Duel duel) {
        return duel.phase == Phase.PENDING || duel.phase == Phase.STAKING || (duel.phase == Phase.LIVE && duel.gating);
    }

    /** The round a "this round" bet applies to: the one about to be fought (or round 1 before the match). */
    private int currentRound(Duel duel) { return duel.rounds.getOrDefault(duel.a, 0) + duel.rounds.getOrDefault(duel.b, 0) + 1; }

    /** Places or CHANGES a wager for a given scope (round 0 = whole match, N = a specific round), while betting
     *  is open. A spectator may hold one wager per scope. Changing a scope refunds its old stake first, so the
     *  money is always conserved. */
    private boolean placeWager(Player player, Duel duel, String target, double amount, int round) {
        if (duel == null) { CoreUtil.error(player, "No such match."); return true; }
        if (!bettingOpen(duel)) { CoreUtil.error(player, "Betting is only open before the match and between rounds."); return true; }
        String id = CoreUtil.id(player);
        if (duel.has(id)) { CoreUtil.error(player, "You cannot bet on your own match."); return true; }
        if (target == null) { CoreUtil.error(player, "Choose " + duel.aName + " or " + duel.bName + "."); return true; }
        if (amount < 0 || !Double.isFinite(amount)) { CoreUtil.error(player, "Amount cannot be negative."); return true; }
        if (round != 0 && round < currentRound(duel)) { CoreUtil.error(player, "That round is already over."); return true; }
        Wager existing = duel.wagers.stream().filter(w -> w.player().equals(id) && w.round() == round).findFirst().orElse(null);
        if (existing != null) { db.changeBalance(id, existing.amount()); duel.wagers.remove(existing); syncWagerDb(duel); }
        if (amount > 0 && !db.changeBalance(id, -amount)) { CoreUtil.error(player, "You cannot cover that."); if (existing != null) { duel.wagers.add(existing); syncWagerDb(duel); } return true; }
        if (amount > 0) { duel.wagers.add(new Wager(id, target, amount, round)); syncWagerDb(duel); }
        actionbar(player, "Wager set: " + CoreUtil.money(amount) + " on " + name(target) + (round == 0 ? " (whole match)." : " (round " + round + ")."));
        refreshSpectate(duel);
        return true;
    }

    private Wager wagerOf(Duel duel, String id) { return duel.wagers.stream().filter(w -> w.player().equals(id)).findFirst().orElse(null); }

    boolean watch(Player player, int duelId) {
        Duel duel = duels.stream().filter(d -> d.id == duelId).findFirst().orElse(null);
        if (duel == null) { CoreUtil.error(player, "No such match."); return true; }
        if (duel.has(CoreUtil.id(player))) { CoreUtil.error(player, "You are in this duel."); return true; }
        openSpectate(player, duelId);
        return true;
    }

    /** Puts a player into the dedicated duel-spectator state: survival with an empty inventory, flying,
     *  permanently invisible, non-colliding and untouchable, unable to interfere in any way. Their real
     *  state and location are captured first and restored on leave. This does NOT touch the admin /spectator
     *  vanish system -- it is a separate, self-contained state. */
    private void enterSpectator(Player player, Duel duel) {
        String id = CoreUtil.id(player);
        if (spectators.containsKey(id) || byPlayer.containsKey(id)) return;
        capture(player);
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvisible(true);
        player.setCollidable(false);
        player.setInvulnerable(true);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        spectators.put(id, duel.id);
        player.teleport(gallery(duel));
        /** Hide the spectator ENTIRELY (body AND nametag) from the two duellists, so no name floats over the
         *  fight -- all while staying in survival, so the admin-only Spectator gamemode is never touched. Other
         *  viewers still just see an ordinary invisible player (whose nametag the packet service also hides). */
        Player fa = a(duel), fb = b(duel);
        if (fa != null) fa.hidePlayer(plugin, player);
        if (fb != null) fb.hidePlayer(plugin, player);
        actionbar(player, "Spectating — invisible, and you cannot affect the fight.");
    }

    private void leaveSpectator(Player player) {
        spectators.remove(CoreUtil.id(player));
        stagedBet.remove(CoreUtil.id(player));
        /** Make the ex-spectator visible again to everyone (idempotent -- showPlayer is a no-op where they were
         *  never hidden). */
        for (Player viewer : plugin.getServer().getOnlinePlayers()) viewer.showPlayer(plugin, player);
        restore(player);
    }

    boolean isDuelSpectator(String id) { return spectators.containsKey(id); }

    // ---- spectator cannot interfere -------------------------------------------------------------
    @EventHandler(ignoreCancelled = true) public void specPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && spectators.containsKey(CoreUtil.id(p))) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void specDrop(PlayerDropItemEvent e) {
        if (spectators.containsKey(CoreUtil.id(e.getPlayer()))) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void specInteract(PlayerInteractEvent e) {
        if (spectators.containsKey(CoreUtil.id(e.getPlayer()))) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void specInteractEntity(PlayerInteractEntityEvent e) {
        if (spectators.containsKey(CoreUtil.id(e.getPlayer()))) e.setCancelled(true);
    }
    /** A spectator can neither deal damage nor make the attack/hit sounds that come with swinging at a fighter.
     *  Cancelled as early as possible (LOWEST) so the melee never reaches the sound logic. */
    @EventHandler(priority = EventPriority.LOWEST) public void specAttack(EntityDamageByEntityEvent e) {
        Player src = e.getDamager() instanceof Player p ? p
                : e.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player sh ? sh : null;
        if (src != null && spectators.containsKey(CoreUtil.id(src))) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void specSwing(org.bukkit.event.player.PlayerAnimationEvent e) {
        if (spectators.containsKey(CoreUtil.id(e.getPlayer()))) e.setCancelled(true);
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
        if (source != null && duel != null && duel.phase == Phase.LIVE && !duel.gating && duel.has(CoreUtil.id(source)) && duel.has(CoreUtil.id(hurt))
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

    @EventHandler(ignoreCancelled = true) public void bucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        Duel duel = duelOf(event.getPlayer());
        if (duel == null || duel.phase != Phase.LIVE || !inArena(event.getPlayer())) return;
        org.bukkit.block.Block b = event.getBlock();
        duel.placed.add(key(b.getX(), b.getY(), b.getZ()));
    }
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
        /** Structure is built once and left standing; a reset only removes what the duellists placed, so the
         *  map is never modified by a match. Cheap -- a handful of blocks, not the whole arena. */
        for (long k : duel.placed) arena.getBlockAtKey(k).setType(Material.AIR, false);
        duel.placed.clear();
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
        long grace = Math.max(3, plugin.getConfig().getLong("arena.reconnect-grace-seconds", 10)) * 1000L;
        long now = System.currentTimeMillis();
        for (Duel duel : new ArrayList<>(duels)) {
            if (duel.phase == Phase.PENDING && now - duel.pendingSince > 60000) { both(duel, "Challenge expired."); dispose(duel); continue; }
            if (duel.phase != Phase.LIVE) continue;
            for (Map.Entry<String, Long> entry : new LinkedHashMap<>(duel.disconnectedAt).entrySet()) {
                long elapsed = now - entry.getValue();
                String loser = entry.getKey(), winner = duel.other(loser);
                if (elapsed >= grace) { both(duel, name(loser) + " did not reconnect in time."); finish(duel, winner, loser); break; }
                /** Visible, non-chat countdown for the remaining duellist and every spectator of this match. */
                int secs = (int) Math.ceil((grace - elapsed) / 1000.0);
                String msg = name(loser) + " disconnected — forfeits in " + secs + "s";
                Player remaining = plugin.getServer().getPlayer(winner);
                if (remaining != null) actionbar(remaining, msg);
                for (Map.Entry<String, Integer> sp : spectators.entrySet()) {
                    if (sp.getValue() != duel.id) continue;
                    Player watcher = plugin.getServer().getPlayer(sp.getKey());
                    if (watcher != null) actionbar(watcher, msg);
                }
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
        both(duel, "Duel cancelled — " + why + ". All stakes, money bets and wagered items refunded.");
        resetArena(duel);
        returnPlayers(duel);
        refundItemWagers(duel);
        dispose(duel);
    }

    private void returnPlayers(Duel duel) {
        for (String id : List.of(duel.a, duel.b)) { Player player = plugin.getServer().getPlayer(id); if (player != null) restore(player); }
        for (Map.Entry<String, Integer> entry : new ArrayList<>(spectators.entrySet())) {
            if (entry.getValue() != duel.id) continue;
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) leaveSpectator(player); else spectators.remove(entry.getKey());
        }
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
        /** When true this inventory is a real container the player fills (the item-wager box), so clicks and
         *  drags are NOT cancelled. Every other Menu is a button panel and stays fully click-locked. */
        boolean fillable = false;
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
        /** A clear banner up top showing the currently selected kit, so it is obvious at a glance. */
        menu.inv.setItem(4, glow(icon(duel.kit.icon(), "Kit: " + duel.kit.label(), List.of(duel.kit.blurb(), "Best of " + duel.bestOf))));
        int[] kitSlots = {10, 12, 14, 16};
        Kit[] kits = Kit.values();
        for (int i = 0; i < kits.length; i++) {
            boolean sel = duel.kit == kits[i];
            ItemStack ico = icon(kits[i].icon(), (sel ? "\u2714 SELECTED \u2014 " : "") + kits[i].label(), List.of(kits[i].blurb(), sel ? "This kit is selected" : "Click to pick this kit"));
            if (sel) glow(ico);
            menu.inv.setItem(kitSlots[i], ico);
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
        String meId = CoreUtil.id(player), themId = duel.other(meId);
        String meName = meId.equals(duel.a) ? duel.aName : duel.bName, themName = meId.equals(duel.a) ? duel.bName : duel.aName;
        boolean ready = duel.confirmed.contains(meId);
        /** Rendered from THIS player's perspective: "You" is always the viewer, whichever side they are. */
        int wagered = loadWager(duel, meId).size(), theirWager = loadWager(duel, themId).size();
        String themWagerName = meId.equals(duel.a) ? duel.bName : duel.aName;
        menu.inv.setItem(42, icon(Material.CHEST, "Wager items" + (wagered > 0 ? " (" + wagered + ")" : ""), List.of(
                "Put items in to wager them", "Winner takes BOTH sides' wagered items",
                "You staged: " + wagered + "   " + themWagerName + ": " + theirWager,
                "Separate from the money stake above")));
        menu.inv.setItem(43, icon(Material.SPYGLASS, "View " + themWagerName + "'s wager", List.of(
                theirWager > 0 ? theirWager + " stack(s) staged" : "Nothing staged yet", "Click to inspect what they staked")));
        menu.inv.setItem(40, icon(ready ? Material.YELLOW_CONCRETE : Material.LIME_CONCRETE, ready ? "Confirmed — waiting for opponent…" : "Confirm", List.of(
                "You: " + meName + "  " + CoreUtil.money(duel.stakes.getOrDefault(meId, 0d)) + (ready ? "  (ready)" : ""),
                "Them: " + themName + "  " + CoreUtil.money(duel.stakes.getOrDefault(themId, 0d)) + (duel.confirmed.contains(themId) ? "  (ready)" : ""),
                "Kit " + duel.kit.label() + " · Best of " + duel.bestOf)));
        player.openInventory(menu.inv);
    }

    private void refreshSetup(Duel duel) { openSetup(a(duel)); openSetup(b(duel)); }
    /** Closing the setup GUI with ESC cancels the duel, exactly like /duel cancel -- unless the player has
     *  confirmed (and is just waiting for their opponent) or merely stepped into another duel screen (the wager
     *  box/viewer, or a live setup refresh), which must NOT count as a cancel. */
    @EventHandler public void closeSetup(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Menu menu) || !menu.kind.equals("setup")) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        String id = CoreUtil.id(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            Duel d = byPlayer.get(id);
            if (d == null || (d.phase != Phase.STAKING && d.phase != Phase.PENDING) || d.confirmed.contains(id)) return;
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu m
                    && (m.kind.equals("setup") || m.kind.equals("wagerbox") || m.kind.equals("wagerview"))) return;
            forfeit(player);
        }, 2L);
    }
    /** Re-render the setup GUI live for whichever duellist currently has it open, so wager counts update on
     *  both sides the moment either confirms/clears -- without yanking anyone who is inside the wager box. */
    private void refreshSetupOpen(Duel duel) {
        for (Player p : new Player[]{a(duel), b(duel)})
            if (p != null && p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu m && "setup".equals(m.kind)) openSetup(p);
    }

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
    /** Adds an enchantment glint to an icon (no real enchantment) so the selected choice clearly stands out. */
    private ItemStack glow(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setEnchantmentGlintOverride(true); item.setItemMeta(meta); }
        return item;
    }
    private ItemStack filler() { return CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()); }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Menu menu)) return;
        if (menu.fillable) {
            /** The wager box: the top 45 slots and the player's own inventory are freely usable; the bottom row
             *  is a control bar (Back / Clear / Confirm) and stays click-locked. */
            if (!(event.getWhoClicked() instanceof Player boxPlayer)) return;
            int raw = event.getRawSlot();
            if (raw >= 45 && raw < 54) {
                event.setCancelled(true);
                if (raw == 45) openSetup(boxPlayer);
                else if (raw == 48) clearWager(boxPlayer, event.getInventory());
                else if (raw == 49) confirmWager(boxPlayer, event.getInventory());
            }
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        ItemStack clicked = event.getCurrentItem();
        switch (menu.kind) {
            case "hub" -> {
                if (clicked != null && clicked.getType() == Material.PLAYER_HEAD) {
                    String target = PlainName(clicked);
                    if (target != null) { player.closeInventory(); challenge(player, target); }
                } else if (slot == 15) { openMatchList(player); }
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
                    case 42 -> openWagerBox(player);
                    case 43 -> openOpponentWager(player);
                    default -> { }
                }
            }
            case "matchlist" -> {
                if (clicked != null && clicked.hasItemMeta()) {
                    Integer mid = clicked.getItemMeta().getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, "duel_id"), org.bukkit.persistence.PersistentDataType.INTEGER);
                    if (mid != null) openSpectate(player, mid);
                }
            }
            case "spectate" -> {
                Duel duel = find(menu.duelId);
                if (duel == null) { player.closeInventory(); return; }
                String id = CoreUtil.id(player);
                double[] st = stagedBet.computeIfAbsent(id, k -> new double[]{-1, 0, 0});
                if (st.length < 3) { st = new double[]{st[0], st[1], 0}; stagedBet.put(id, st); }
                double balance = db.player(id).balance();
                switch (slot) {
                    case 20 -> { st[0] = 0; openSpectate(player, menu.duelId); }
                    case 24 -> { st[0] = 1; openSpectate(player, menu.duelId); }
                    case 22 -> { if (duel.bestOf > 1) st[2] = st[2] == 0 ? 1 : 0; openSpectate(player, menu.duelId); }
                    case 29 -> { st[1] = Math.max(0, st[1] - 1000); openSpectate(player, menu.duelId); }
                    case 30 -> { st[1] = Math.max(0, st[1] - 100); openSpectate(player, menu.duelId); }
                    case 32 -> { st[1] = Math.min(balance, st[1] + 100); openSpectate(player, menu.duelId); }
                    case 33 -> { st[1] = Math.min(balance, st[1] + 1000); openSpectate(player, menu.duelId); }
                    case 38 -> { String target = st[0] == 0 ? duel.a : st[0] == 1 ? duel.b : null;
                                 int scope = (duel.bestOf > 1 && st[2] != 0) ? currentRound(duel) : 0;
                                 if (target == null) actionbar(player, "Pick a fighter to back first."); else placeWager(player, duel, target, st[1], scope);
                                 openSpectate(player, menu.duelId); }
                    case 42 -> { if (spectators.containsKey(id)) leaveSpectator(player); else player.closeInventory(); }
                    case 44 -> { if (duel.phase == Phase.LIVE && !duel.gating) enterSpectator(player, duel); openSpectate(player, menu.duelId); }
                    default -> { }
                }
            }
            case "wagerview" -> { if (slot == 49) openSetup(player); return; }
            case "ready" -> {
                Duel duel = find(menu.duelId);
                if (duel == null || !duel.gating) { player.closeInventory(); return; }
                if (slot == 13 && duel.has(CoreUtil.id(player))) {
                    duel.roundReady.add(CoreUtil.id(player));
                    if (duel.roundReady.contains(duel.a) && duel.roundReady.contains(duel.b)) startFight(duel);
                    else { refreshReady(duel); actionbar(player, "Ready — waiting for your opponent."); }
                }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ item wagering
    /** Items wagered by a duellist, read from the DB escrow (empty list if none). */
    private List<ItemStack> loadWager(Duel duel, String id) {
        byte[] raw = db.arenaItemWagerGet(duel.id, id);
        List<ItemStack> out = new ArrayList<>();
        if (raw != null) try { for (ItemStack it : ItemStack.deserializeItemsFromBytes(raw)) if (it != null && !it.getType().isAir()) out.add(it); } catch (Throwable ignored) { }
        return out;
    }

    /** A real 54-slot container the duellist fills with what they want to wager. It is pre-loaded from any
     *  items already staged, so it can be edited. Only allowed before the match starts (STAKING). */
    /** Read-only view of what the OPPONENT has wagered, so both sides can see the stakes before committing. */
    private void openOpponentWager(Player player) {
        Duel duel = duelOf(player);
        if (duel == null) return;
        String themId = duel.other(CoreUtil.id(player));
        String themName = CoreUtil.id(player).equals(duel.a) ? duel.bName : duel.aName;
        Menu menu = new Menu("wagerview", duel.id);
        menu.inv = plugin.getServer().createInventory(menu, 54, Component.text(themName + "'s wager", NamedTextColor.DARK_AQUA));
        List<ItemStack> items = loadWager(duel, themId);
        for (int i = 0; i < items.size() && i < 45; i++) menu.inv.setItem(i, items.get(i));
        for (int slot = 45; slot < 54; slot++) menu.inv.setItem(slot, filler());
        menu.inv.setItem(49, icon(Material.ARROW, "Back", List.of("Duel setup")));
        if (items.isEmpty()) menu.inv.setItem(22, icon(Material.BARRIER, themName + " has not wagered anything", List.of()));
        player.openInventory(menu.inv);
    }

    private void openWagerBox(Player player) {
        Duel duel = duelOf(player);
        if (duel == null || duel.phase != Phase.STAKING) { actionbar(player, "Items can only be wagered before the match starts."); return; }
        Menu menu = new Menu("wagerbox", duel.id);
        menu.fillable = true;
        menu.inv = plugin.getServer().createInventory(menu, 54, Component.text("Wager items \u2014 winner takes all", NamedTextColor.DARK_AQUA));
        for (int slot = 45; slot < 54; slot++) menu.inv.setItem(slot, filler());
        int already = loadWager(duel, CoreUtil.id(player)).size();
        menu.inv.setItem(45, icon(Material.ARROW, "Back", List.of("Return to duel setup", "Unconfirmed items above are returned")));
        menu.inv.setItem(48, icon(Material.CAULDRON, "Clear wager", List.of(already > 0 ? "Return all " + already + " staged stack(s)" : "Nothing staged yet")));
        menu.inv.setItem(49, icon(Material.LIME_CONCRETE, "Confirm wager", List.of("Add the items above to your wager", "Winner takes both sides' wagered items")));
        menu.inv.setItem(53, icon(Material.BOOK, "Currently wagered", List.of(already + " stack(s) staged", "Add more above, then Confirm")));
        player.openInventory(menu.inv);
        CoreUtil.msg(player, "Put items in the top area, then click Confirm to stake them. Closing without confirming returns them.");
    }

    /** Appends the items staged in the top of the box to the player's DB item-wager escrow, then clears the box
     *  and returns to setup. Escrow only ever changes here and in clearWager, so nothing is wagered by accident
     *  and an already-escrowed item can never be pulled back out of the box for free. */
    private void confirmWager(Player player, Inventory box) {
        String id = CoreUtil.id(player);
        Duel duel = duelOf(player);
        List<ItemStack> staged = new ArrayList<>();
        for (int i = 0; i < 45; i++) { ItemStack it = box.getItem(i); if (it != null && !it.getType().isAir()) staged.add(it); }
        if (duel == null || duel.phase != Phase.STAKING) { giveOrStash(id, staged, "Your items were returned \u2014 the match was no longer accepting wagers."); player.closeInventory(); return; }
        if (staged.isEmpty()) { actionbar(player, "Put items in the box first, then Confirm."); return; }
        List<ItemStack> full = new ArrayList<>(loadWager(duel, id));
        full.addAll(staged);
        db.arenaItemWagerSave(duel.id, id, ItemStack.serializeItemsAsBytes(full.toArray(new ItemStack[0])));
        for (int i = 0; i < 45; i++) box.setItem(i, null);
        actionbar(player, "Wager confirmed \u2014 " + full.size() + " stack(s) staked.");
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline() && duelOf(player) == duel && duel.phase == Phase.STAKING) openSetup(player); refreshSetupOpen(duel); });
    }

    /** Returns every item the player has wagered (confirmed escrow plus anything unconfirmed still in the box)
     *  and empties their escrow. */
    private void clearWager(Player player, Inventory box) {
        String id = CoreUtil.id(player);
        Duel duel = duelOf(player);
        List<ItemStack> back = new ArrayList<>();
        for (int i = 0; i < 45; i++) { ItemStack it = box.getItem(i); if (it != null && !it.getType().isAir()) { back.add(it); box.setItem(i, null); } }
        if (duel != null) { back.addAll(loadWager(duel, id)); db.arenaItemWagerClear(duel.id, id); }
        giveOrStash(id, back, "Wager cleared \u2014 items returned.");
        player.closeInventory();
        if (duel != null) Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline() && duelOf(player) == duel && duel.phase == Phase.STAKING) openSetup(player); refreshSetupOpen(duel); });
    }

    /** On closing the wager box, escrow whatever is inside to the DB (crash-safe) and hand any surplus back if
     *  the match already ended/started while it was open. Then return the duellist to the setup GUI. */
    @EventHandler public void closeWager(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Menu menu) || !menu.kind.equals("wagerbox")) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        String id = CoreUtil.id(player);
        /** Anything still in the TOP of the box was never confirmed, so closing hands it straight back -- closing
         *  is a cancel. Confirmed items already live in the DB escrow and are untouched here. */
        List<ItemStack> unconfirmed = new ArrayList<>();
        for (int i = 0; i < 45; i++) { ItemStack it = event.getInventory().getItem(i); if (it != null && !it.getType().isAir()) unconfirmed.add(it); }
        if (!unconfirmed.isEmpty()) giveOrStash(id, unconfirmed, "Unconfirmed wager items were returned.");
    }

    /** Winner receives both sides' wagered items (their own back + the loser's). Overflow drops at their feet;
     *  if they are offline it goes to their claim-later stash. */
    private void awardItemWagers(Duel duel, String winner, String loser) {
        List<ItemStack> pot = new ArrayList<>(loadWager(duel, winner));
        pot.addAll(loadWager(duel, loser));
        db.arenaItemWagerClear(duel.id, winner); db.arenaItemWagerClear(duel.id, loser);
        if (pot.isEmpty()) return;
        giveOrStash(winner, pot, "You won " + pot.size() + " wagered item stack(s).");
        both(duel, name(winner) + " takes the wagered items.");
    }

    private void refundItemWagers(Duel duel) {
        for (String id : List.of(duel.a, duel.b)) {
            List<ItemStack> items = loadWager(duel, id);
            db.arenaItemWagerClear(duel.id, id);
            if (!items.isEmpty()) giveOrStash(id, items, "Your wagered items were returned.");
        }
    }

    /** Give items to a player: to their inventory if online (surplus dropped at their feet), else into their
     *  persistent claim-later stash so nothing is ever lost. */
    private void giveOrStash(String id, List<ItemStack> items, String note) {
        if (items == null || items.isEmpty()) return;
        Player player = plugin.getServer().getPlayer(id);
        if (player != null && player.isOnline()) {
            for (ItemStack it : items) {
                if (it == null || it.getType().isAir()) continue;
                for (ItemStack leftover : player.getInventory().addItem(it).values()) player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            CoreUtil.msg(player, note);
        } else {
            for (ItemStack it : items) db.stashAddItem(id, it);
        }
    }

    private Duel find(int id) { return duels.stream().filter(d -> d.id == id).findFirst().orElse(null); }

    /** Every current match, clickable to open its spectator/betting window. */
    void openMatchList(Player player) {
        Menu menu = new Menu("matchlist", 0);
        menu.inv = plugin.getServer().createInventory(menu, 54, Component.text("Duels • Matches", NamedTextColor.DARK_AQUA));
        int slot = 0;
        for (Duel d : duels) {
            if (slot >= 45) break;
            ItemStack card = new ItemStack(d.phase == Phase.LIVE ? Material.DIAMOND_SWORD : Material.CLOCK);
            ItemMeta meta = card.getItemMeta();
            meta.displayName(Component.text("#" + d.id + "  " + d.aName + " vs " + d.bName, NamedTextColor.GOLD));
            meta.lore(List.of(Component.text("Kit " + d.kit.label() + " · Best of " + d.bestOf, NamedTextColor.GRAY),
                    Component.text("Score " + d.rounds.getOrDefault(d.a, 0) + " - " + d.rounds.getOrDefault(d.b, 0), NamedTextColor.GRAY),
                    Component.text("Status: " + d.phase, NamedTextColor.DARK_GRAY),
                    Component.text(d.phase == Phase.LIVE ? "Click to watch" : "Click to open betting", NamedTextColor.GREEN)));
            meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "duel_id"), org.bukkit.persistence.PersistentDataType.INTEGER, d.id);
            card.setItemMeta(meta);
            menu.inv.setItem(slot++, card);
        }
        if (duels.isEmpty()) menu.inv.setItem(22, icon(Material.BARRIER, "No matches right now", List.of("Challenge someone with /duel <player>")));
        player.openInventory(menu.inv);
    }

    /** The spectator / betting window. Shows both fighters, kit, series score and stakes; lets a spectator
     *  stage and confirm (or change) a wager before the match locks; and, once the match is live, enter the
     *  arena to watch in the dedicated invisible spectator state. */
    void openSpectate(Player player, int duelId) {
        Duel duel = find(duelId);
        if (duel == null) { CoreUtil.error(player, "That match has ended."); player.closeInventory(); return; }
        String id = CoreUtil.id(player);
        boolean bettingOpen = bettingOpen(duel);
        double[] st = stagedBet.computeIfAbsent(id, k -> new double[]{-1, 0, 0});
        if (st.length < 3) { double[] grown = new double[]{st[0], st[1], 0}; stagedBet.put(id, grown); st = grown; }
        Menu menu = new Menu("spectate", duelId);
        menu.inv = plugin.getServer().createInventory(menu, 54, Component.text("Duel #" + duelId + " • Spectate", NamedTextColor.DARK_AQUA));
        for (int f = 45; f < 54; f++) menu.inv.setItem(f, filler());
        /** Scope of the bet being staged: 0 = whole match, else the current round. Best-of-1 has only a match. */
        int cur = currentRound(duel);
        boolean perRound = duel.bestOf > 1 && st[2] != 0;
        int scope = perRound ? cur : 0;
        String scopeLabel = perRound ? "round " + cur : "whole match";
        double poolA = 0, poolB = 0;
        for (Wager w : duel.wagers) { if (w.round() != scope) continue; if (w.on().equals(duel.a)) poolA += w.amount(); else poolB += w.amount(); }
        menu.inv.setItem(4, icon(Material.PAPER, "#" + duelId + "  " + duel.aName + " vs " + duel.bName, List.of(
                "Kit: " + duel.kit.label(), "Series: Best of " + duel.bestOf,
                "Score: " + duel.rounds.getOrDefault(duel.a, 0) + " - " + duel.rounds.getOrDefault(duel.b, 0),
                "Stakes: " + CoreUtil.money(duel.stakes.getOrDefault(duel.a, 0d)) + " / " + CoreUtil.money(duel.stakes.getOrDefault(duel.b, 0d)),
                "Status: " + (duel.gating ? "READY-GATE (betting open)" : duel.phase.toString()))));
        boolean backA = st[0] == 0, backB = st[0] == 1;
        menu.inv.setItem(20, icon(backA ? Material.LIME_CONCRETE : Material.WHITE_CONCRETE, (backA ? "✔ " : "") + "Back " + duel.aName,
                List.of(scopeLabel + " pool on " + duel.aName + ": " + CoreUtil.money(poolA), bettingOpen ? "Click to back " + duel.aName : "Betting closed")));
        menu.inv.setItem(24, icon(backB ? Material.RED_CONCRETE : Material.WHITE_CONCRETE, (backB ? "✔ " : "") + "Back " + duel.bName,
                List.of(scopeLabel + " pool on " + duel.bName + ": " + CoreUtil.money(poolB), bettingOpen ? "Click to back " + duel.bName : "Betting closed")));
        if (bettingOpen) {
            if (duel.bestOf > 1)
                menu.inv.setItem(22, icon(perRound ? Material.CLOCK : Material.NETHER_STAR, "Betting on: " + scopeLabel, List.of(
                        "Click to switch between", "this round and the whole match",
                        "Match bets pay when the match ends;", "round bets pay when that round ends")));
            menu.inv.setItem(29, icon(Material.RED_STAINED_GLASS_PANE, "- 1,000", List.of()));
            menu.inv.setItem(30, icon(Material.PINK_STAINED_GLASS_PANE, "- 100", List.of()));
            menu.inv.setItem(31, icon(Material.GOLD_INGOT, "Wager amount: " + CoreUtil.money(st[1]), List.of("Pick a fighter, set an amount, confirm")));
            menu.inv.setItem(32, icon(Material.LIME_STAINED_GLASS_PANE, "+ 100", List.of()));
            menu.inv.setItem(33, icon(Material.GREEN_STAINED_GLASS_PANE, "+ 1,000", List.of()));
            menu.inv.setItem(38, icon(Material.EMERALD, "Confirm " + scopeLabel + " wager", List.of("Backing " + (st[0] == 0 ? duel.aName : st[0] == 1 ? duel.bName : "nobody yet"),
                    "Amount " + CoreUtil.money(st[1]), "On: " + scopeLabel, "Changing this scope refunds the old wager")));
        } else {
            menu.inv.setItem(31, icon(Material.BARRIER, "Betting is closed", List.of("Opens again at the next round's ready-gate")));
        }
        List<Wager> mineAll = new ArrayList<>();
        for (Wager w : duel.wagers) if (w.player().equals(id)) mineAll.add(w);
        List<String> mineLore = new ArrayList<>();
        if (mineAll.isEmpty()) mineLore.add("None placed");
        else for (Wager w : mineAll) mineLore.add(CoreUtil.money(w.amount()) + " on " + (w.on().equals(duel.a) ? duel.aName : duel.bName) + (w.round() == 0 ? " (match)" : " (round " + w.round() + ")"));
        menu.inv.setItem(40, icon(Material.BOOK, "Your wagers", mineLore));
        if (duel.phase == Phase.LIVE && !spectators.containsKey(id))
            menu.inv.setItem(44, icon(Material.ENDER_EYE, "Enter arena to watch", List.of("Invisible, cannot interfere")));
        menu.inv.setItem(42, icon(Material.ARROW, spectators.containsKey(id) ? "Leave spectating" : "Close", List.of()));
        player.openInventory(menu.inv);
    }

    /** Re-render the spectate window for anyone who currently has it open on this match. */
    private void refreshSpectate(Duel duel) {
        for (Player p : plugin.getServer().getOnlinePlayers())
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu m && m.kind.equals("spectate") && m.duelId == duel.id)
                openSpectate(p, duel.id);
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Menu m)) return;
        if (!m.fillable) { event.setCancelled(true); return; }
        for (int raw : event.getRawSlots()) if (raw >= 45 && raw < 54) { event.setCancelled(true); return; }
    }

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
