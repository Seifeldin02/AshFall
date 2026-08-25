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
import org.bukkit.event.inventory.InventoryAction;
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

    /** Setup runs in three stages, each with its own screen and its own both-sides confirmation. Nothing is
     *  charged and no arena is built until the last one is agreed: the money leaves the players' balances at
     *  the final confirm, so backing out of stage two or three costs nobody anything and needs no refund at
     *  all. Changing anything at any stage clears BOTH confirmations, so neither player can be walked into a
     *  choice they did not agree to. */
    enum Stage {
        KIT("Kit"), MAP("Map"), OPTIONS("Final Options");
        private final String label;
        Stage(String label) { this.label = label; }
        String label() { return label; }
        Stage next() { return this == KIT ? MAP : OPTIONS; }
        Stage previous() { return this == OPTIONS ? MAP : KIT; }
    }

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
        /** Which setup screen the pair is on, and when they arrived, for the per-stage timeout. */
        Stage stage = Stage.KIT;
        long stageSince = System.currentTimeMillis();
        /** The chosen map, and the throwaway world cloned from it once the match actually starts. Null until
         *  the final confirmation: an instance is never created for a duel that has not been agreed. */
        String mapKey;
        DuelMapService.DuelMap map;
        World instance;
        /** Final Options. Visibility Effects is ON by default: while it is on, both duellists are kept
         *  Glowing and given Night Vision for the whole fight regardless of their own /settings, so neither
         *  can lose the other in a dark corner of an imported map. */
        boolean visibility = true;
        /** True from the moment the arena starts being cloned, so a second confirmation click (or a race
         *  between the two players' clicks) cannot start two instances for one duel. */
        boolean starting = false;
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
        /** When the current ready gate opened, so a gate that never resolves can be broken out of. */
        long gateOpenedAt = 0;
        /** Ids that have clicked Ready for the current round; cleared when each round's gate opens. */
        final Set<String> roundReady = new HashSet<>();
        /** When betting closes for the round that is running. Set the moment a round actually starts, so a
         *  pair who both ready up instantly no longer leave spectators with no window at all -- the old rule
         *  only allowed bets while the ready-gate was open, which could be under a second. */
        long roundBettingUntil = 0L;
        /** Spectator id -> their own personal deadline. Somebody who walks in mid-round gets the same ten
         *  seconds from when they arrived, rather than being told they are too late for a fight they have
         *  only just started watching. */
        final Map<String, Long> spectatorBettingUntil = new java.util.concurrent.ConcurrentHashMap<>();
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

    /** Where each duellist starts. The spawn pair comes from the map, and the yaw is derived from the pair
     *  rather than stored, so the two always begin facing each other however the points are moved. */
    private Location corner(Duel duel, int index) {
        if (duel.instance != null && duel.map != null)
            return index == 0 ? duel.map.p1(duel.instance) : duel.map.p2(duel.instance);
        int cx = slotBaseX(duel.slot), h = sizeFor(duel.kit) / 2;
        return new Location(arena, cx + (index == 0 ? -h + 3.5 : h - 4.5), FLOOR_Y + 1, 0.5, index == 0 ? 90f : -90f, 0f);
    }

    /** Spectators watch from INSIDE the arena (they are invisible, flying and non-colliding), since the
     *  full-height walls make an outside gallery useless. Placed above the centre, looking down. */
    private Location gallery(Duel duel) {
        if (duel.instance != null && duel.map != null) {
            double[] at = duel.map.spectator(plugin.getConfig().getInt("duel-spectator-fallback-height", 10));
            return new Location(duel.instance, at[0], at[1], at[2], 0f, 25f);
        }
        return new Location(arena, slotBaseX(duel.slot) + 0.5, FLOOR_Y + 6, 0.5, 0f, 25f);
    }

    boolean inArena(Player player) { return isArenaWorld(player.getWorld()); }

    /** True for any world a duel runs in: the legacy shared arena, and every per-match instance world. Other
     *  services key their duel exclusions off this (graves, progression), so a per-match instance must be
     *  recognised here or a duel death would start dropping graves again. */
    boolean isArenaWorld(org.bukkit.World world) {
        if (world == null) return false;
        if (arena != null && arena.equals(world)) return true;
        DuelMapService maps = plugin.duelMaps();
        return maps != null && maps.isInstance(world);
    }
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
                enchanted(Material.MACE, Map.of(Enchantment.DENSITY, 5, Enchantment.WIND_BURST, 3, Enchantment.SHARPNESS, 5, Enchantment.BREACH, 4, Enchantment.UNBREAKING, 3)),
                new ItemStack(Material.WIND_CHARGE, 64), new ItemStack(Material.GOLDEN_APPLE, 16), new ItemStack(Material.ENDER_PEARL, 16),
                splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1),
                splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 1),
                new ItemStack(Material.WATER_BUCKET));
            case SWORD -> List.of(
                enchanted(Material.NETHERITE_SWORD, Map.of(Enchantment.SHARPNESS, 5, Enchantment.FIRE_ASPECT, 2, Enchantment.UNBREAKING, 3)),
                enchanted(Material.BOW, Map.of(Enchantment.POWER, 5)), new ItemStack(Material.GOLDEN_APPLE, 16),
                /** Piercing so the crossbow is not simply a worse bow in the one kit that carries both -- a
                 *  pierced bolt goes through a raised shield, which is the whole point of bringing it to a
                 *  Sword + Shield match. */
                enchanted(Material.CROSSBOW, Map.of(Enchantment.PIERCING, 4)),
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
            case MACE -> List.of(new ItemStack(Material.WIND_CHARGE, 64), new ItemStack(Material.GOLDEN_APPLE, 16), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 8),
                new ItemStack(Material.ELYTRA), rockets(64), splash(org.bukkit.potion.PotionType.SLOW_FALLING, 1), new ItemStack(Material.COOKED_BEEF, 16));
            case SWORD -> List.of(new ItemStack(Material.COOKED_BEEF, 16), new ItemStack(Material.ARROW, 64), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 4),
                enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3)), enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3)));
            case AXE -> List.of(enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3)), enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3)),
                enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3)), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 4), new ItemStack(Material.ARROW, 32));
            case SPEAR -> List.of(new ItemStack(Material.COOKED_BEEF, 16), rockets(64), new ItemStack(Material.TOTEM_OF_UNDYING, 2), splash(org.bukkit.potion.PotionType.STRONG_HEALING, 4));
        };
    }

    /** Flight-duration-1 rockets: short bursts that suit duelling far better than the default 1-3 spread. */
    private ItemStack rockets(int count) {
        ItemStack item = new ItemStack(Material.FIREWORK_ROCKET, count);
        org.bukkit.inventory.meta.FireworkMeta meta = (org.bukkit.inventory.meta.FireworkMeta) item.getItemMeta();
        meta.setPower(1);
        item.setItemMeta(meta);
        return item;
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

    // ------------------------------------------------------------------ kit layouts (/duels)
    /** A duel kit always contains the same things; where they sit is the player's business.
     *
     *  What is saved is a PERMUTATION, never items. For each of the 36 inventory slots the layout records
     *  which slot of the kit's default arrangement belongs there, or -1 for empty. Applying it is a
     *  reordering of a list the server built a moment ago from {@link #kitHotbar} and {@link #kitExtra}, so
     *  a corrupt, stale or hand-edited row in the database cannot add an item, remove one, or change what
     *  one is -- the worst it can do is fail validation and fall back to the default. */
    private static final int LAYOUT_SLOTS = 36;

    /** The editor is laid out like the inventory it configures: main rows on top, hotbar underneath. */
    private static int gameSlot(int editorSlot) { return editorSlot < 27 ? editorSlot + 9 : editorSlot - 27; }
    private static int editorSlot(int gameSlot) { return gameSlot < 9 ? gameSlot + 27 : gameSlot - 9; }
    private static boolean real(ItemStack item) { return item != null && !item.getType().isAir(); }
    private String layoutKey(String id, Kit kit) { return "duel_layout:" + id + ":" + kit.name(); }

    /** Exactly where {@link #equip} would put this kit with no layout saved -- hotbar first, then addItem
     *  for the extras, into a container whose slot order matches a player inventory's. Reproducing the
     *  default through the same calls is what lets a saved layout be stored as a permutation of it. */
    private ItemStack[] defaultArrangement(Kit kit) {
        Inventory scratch = plugin.getServer().createInventory(null, LAYOUT_SLOTS);
        List<ItemStack> hotbar = kitHotbar(kit);
        for (int i = 0; i < hotbar.size() && i < 9; i++) scratch.setItem(i, hotbar.get(i));
        for (ItemStack extra : kitExtra(kit)) scratch.addItem(extra);
        ItemStack[] out = new ItemStack[LAYOUT_SLOTS];
        for (int i = 0; i < LAYOUT_SLOTS; i++) out[i] = scratch.getItem(i);
        return out;
    }

    private int[] identityLayout(ItemStack[] arrangement) {
        int[] layout = new int[LAYOUT_SLOTS];
        for (int i = 0; i < LAYOUT_SLOTS; i++) layout[i] = real(arrangement[i]) ? i : -1;
        return layout;
    }

    private int[] savedLayout(String id, Kit kit) {
        String raw = db.state(layoutKey(id, kit));
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(",");
        if (parts.length != LAYOUT_SLOTS) return null;
        int[] layout = new int[LAYOUT_SLOTS];
        try { for (int i = 0; i < LAYOUT_SLOTS; i++) layout[i] = Integer.parseInt(parts[i].trim()); }
        catch (NumberFormatException malformed) { return null; }
        return layout;
    }

    /** A layout is only ever applied if it is a genuine bijection onto the kit's occupied slots: every one
     *  of them placed exactly once, and nothing else referenced at all. Anything less -- a kit whose
     *  contents changed since the layout was saved, a truncated row, a repeated index -- falls back to the
     *  default rather than being partially honoured. */
    private boolean layoutMatches(int[] layout, ItemStack[] arrangement) {
        if (layout == null) return false;
        boolean[] used = new boolean[arrangement.length];
        int placed = 0;
        for (int index : layout) {
            if (index < 0) continue;
            if (index >= arrangement.length || !real(arrangement[index]) || used[index]) return false;
            used[index] = true;
            placed++;
        }
        int occupied = 0;
        for (ItemStack item : arrangement) if (real(item)) occupied++;
        return placed == occupied;
    }

    private void saveLayout(Player player, Kit kit, int[] layout) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < layout.length; i++) joined.append(i == 0 ? "" : ",").append(layout[i]);
        db.state(layoutKey(CoreUtil.id(player), kit), joined.toString());
    }

    /** The kit picker. Reachable any time, in or out of a duel -- the arrangement is a standing preference,
     *  not part of a match. */
    void openKitLayoutPicker(Player player) {
        Menu menu = new Menu("layoutpick", 0);
        menu.inv = plugin.getServer().createInventory(menu, 27, Component.text("Duel kit layouts", NamedTextColor.DARK_AQUA));
        for (int slot = 0; slot < 27; slot++) menu.inv.setItem(slot, filler());
        Kit[] kits = Kit.values();
        int[] kitSlots = {10, 12, 14, 16};
        for (int i = 0; i < kits.length; i++) {
            ItemStack[] arrangement = defaultArrangement(kits[i]);
            boolean custom = layoutMatches(savedLayout(CoreUtil.id(player), kits[i]), arrangement);
            ItemStack card = icon(kits[i].icon(), kits[i].label(),
                    List.of(kits[i].blurb(), custom ? "Your own arrangement is saved" : "Using the default arrangement", "Click to arrange it"));
            menu.inv.setItem(kitSlots[i], custom ? glow(card) : card);
        }
        menu.inv.setItem(4, icon(Material.BOOK, "Kit layouts",
                List.of("Every duel hands out the same kit.", "This is where each piece of it sits", "when the match starts.", "Saved per kit, and used for every duel.")));
        menu.inv.setItem(26, icon(Material.BARRIER, "Close", List.of()));
        transition(player, () -> player.openInventory(menu.inv));
    }

    void openKitLayout(Player player, Kit kit) {
        Menu menu = new Menu("kitlayout", 0);
        menu.layoutKit = kit;
        ItemStack[] arrangement = defaultArrangement(kit);
        int[] saved = savedLayout(CoreUtil.id(player), kit);
        menu.layout = layoutMatches(saved, arrangement) ? saved : identityLayout(arrangement);
        menu.inv = plugin.getServer().createInventory(menu, 54, Component.text(kit.label() + " \u2014 kit layout", NamedTextColor.DARK_AQUA));
        renderKitLayout(player, menu);
        transition(player, () -> player.openInventory(menu.inv));
        CoreUtil.msg(player, "Click an item, then click where you want it. Changes save as you make them.");
    }

    private void renderKitLayout(Player player, Menu menu) {
        ItemStack[] arrangement = defaultArrangement(menu.layoutKit);
        for (int editor = 0; editor < LAYOUT_SLOTS; editor++) {
            int index = menu.layout[gameSlot(editor)];
            if (index < 0 || !real(arrangement[index])) { menu.inv.setItem(editor, null); continue; }
            ItemStack show = arrangement[index].clone();
            if (gameSlot(editor) == menu.selected) show = selectedMarker(show);
            menu.inv.setItem(editor, show);
        }
        /** Row five is context, not configuration: armour and the offhand are placed by the kit and cannot
         *  be moved, so they are shown greyed out rather than silently omitted. */
        for (int slot = 36; slot < 54; slot++) menu.inv.setItem(slot, filler());
        List<ItemStack> armour = kitArmour(menu.layoutKit);
        for (int i = 0; i < armour.size() && i < 4; i++) menu.inv.setItem(36 + i, context(armour.get(i), "Worn \u2014 fixed"));
        ItemStack offhand = kitOffhand(menu.layoutKit);
        if (offhand != null) menu.inv.setItem(41, context(offhand, "Offhand \u2014 fixed"));
        menu.inv.setItem(44, icon(Material.PAPER, "Rows 1-3 are your inventory",
                List.of("Row 4 is your hotbar.", "Click an item, then click a slot", "to move or swap it.")));
        menu.inv.setItem(45, icon(Material.ARROW, "Back", List.of("Return to the kit list")));
        menu.inv.setItem(49, icon(Material.CAULDRON, "Reset to default", List.of("Puts everything back where", "the kit normally places it")));
        menu.inv.setItem(53, menu.selected >= 0
                ? icon(Material.LIME_DYE, "Now click a destination", List.of("Click the slot you want it in", "Click the same slot again to cancel"))
                : icon(Material.GRAY_DYE, "Nothing selected", List.of("Click an item to pick it up")));
    }

    private ItemStack selectedMarker(ItemStack item) {
        ItemStack copy = glow(item.clone());
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.text("\u25b6 Selected \u2014 click a destination", NamedTextColor.YELLOW));
            meta.lore(lore);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    private ItemStack context(ItemStack item, String note) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.text(note, NamedTextColor.DARK_GRAY));
            meta.lore(lore);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    /** Click to select, click to place.
     *
     *  Deliberately NOT drag-and-drop. Everything on this screen is a real netherite duel kit, and the
     *  moment one of those is allowed onto the cursor, closing the window hands it to the player for keeps.
     *  Nothing here ever leaves the menu: a click swaps two entries of an int array and repaints. */
    private void layoutClick(Player player, Menu menu, int slot) {
        if (menu.layout == null || menu.layoutKit == null) return;
        if (slot == 45) { openKitLayoutPicker(player); return; }
        if (slot == 49) {
            db.state(layoutKey(CoreUtil.id(player), menu.layoutKit), "");
            menu.layout = identityLayout(defaultArrangement(menu.layoutKit));
            menu.selected = -1;
            renderKitLayout(player, menu);
            sound(player, "cancel");
            actionbar(player, "Layout reset to the kit default.");
            return;
        }
        if (slot < 0 || slot >= LAYOUT_SLOTS) return;
        int game = gameSlot(slot);
        if (menu.selected < 0) {
            if (menu.layout[game] < 0) { sound(player, "error"); return; }
            menu.selected = game;
            renderKitLayout(player, menu);
            sound(player, "adjust");
            actionbar(player, "Selected \u2014 now click where you want it.");
            return;
        }
        if (menu.selected == game) { menu.selected = -1; renderKitLayout(player, menu); sound(player, "cancel"); return; }
        int moving = menu.layout[menu.selected];
        menu.layout[menu.selected] = menu.layout[game];
        menu.layout[game] = moving;
        menu.selected = -1;
        saveLayout(player, menu.layoutKit, menu.layout);
        renderKitLayout(player, menu);
        sound(player, "confirm");
        actionbar(player, "Saved.");
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
        /** The player's own arrangement if they have saved a valid one, otherwise exactly what this always
         *  did. The default path is left untouched on purpose: everyone who has never opened /duels gets
         *  byte-identical kits to the ones they had before layouts existed. */
        ItemStack[] arrangement = defaultArrangement(kit);
        int[] layout = savedLayout(CoreUtil.id(player), kit);
        if (layoutMatches(layout, arrangement)) {
            for (int slot = 0; slot < LAYOUT_SLOTS; slot++)
                inv.setItem(slot, layout[slot] < 0 ? null : arrangement[layout[slot]]);
        } else {
            List<ItemStack> hotbar = kitHotbar(kit);
            for (int i = 0; i < hotbar.size() && i < 9; i++) inv.setItem(i, hotbar.get(i));
            for (ItemStack extra : kitExtra(kit)) inv.addItem(extra);
        }
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
    /** Everything currently held in the duel stash for this player, or an empty array.
     *
     *  A duellist's real inventory is serialised into arena_state for the length of the match, and until now
     *  nothing outside this class could see it. That mattered: the relic lifecycle sweep looks for a relic in
     *  inventories, Ender Storage, graves, auction escrow and dropped items -- none of which is where a
     *  duellist's belongings actually are -- so a relic carried into a duel simply ceased to exist as far as
     *  that check was concerned. */
    ItemStack[] duelStash(String id) {
        Database.ArenaState state = id == null ? null : db.arenaState(id);
        if (state == null || state.items() == null) return new ItemStack[0];
        try { return ItemStack.deserializeItemsFromBytes(state.items()); }
        catch (RuntimeException ignored) { return new ItemStack[0]; }
    }

    /** Every player who currently has belongings parked in a duel stash. */
    List<String> duelStashOwners() { return db.arenaStateOwners(); }

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
        duel.stage = Stage.KIT;
        duel.stageSince = System.currentTimeMillis();
        both(duel, "Duel accepted. Set up the match.");
        openStage(duel);
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
        if (duel.stage != Stage.KIT || duel.starting) { CoreUtil.error(player, "The kit is already locked in for this duel."); return true; }
        sound(player, "select");
        try { duel.kit = Kit.valueOf(name.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { CoreUtil.error(player, "Kits: mace, sword, axe, spear."); sound(player, "error"); return true; }
        changed(duel);
        return true;
    }

    boolean setSeries(Player player, int best) {
        Duel duel = duelOf(player);
        if (duel == null || duel.phase != Phase.STAKING) { CoreUtil.error(player, "Not in a duel setup."); return true; }
        if (duel.stage != Stage.KIT || duel.starting) { CoreUtil.error(player, "The series length is already locked in."); return true; }
        if (best != 1 && best != 3) { CoreUtil.error(player, "Best of 1 or 3."); return true; } sound(player, "error");
        sound(player, "select");
        duel.bestOf = best;
        changed(duel);
        return true;
    }

    boolean setStake(Player player, double amount) {
        Duel duel = duelOf(player);
        if (duel == null || duel.phase != Phase.STAKING) { CoreUtil.error(player, "Not in a duel setup."); return true; }
        if (amount < 0 || !Double.isFinite(amount)) { CoreUtil.error(player, "Stake cannot be negative."); return true; }
        if (duel.stage != Stage.KIT || duel.starting) { CoreUtil.error(player, "Stakes are already locked in for this duel."); return true; }
        if (amount > 0 && db.player(CoreUtil.id(player)).balance() < amount) { CoreUtil.error(player, "You cannot cover that."); return true; }
        duel.stakes.put(CoreUtil.id(player), amount);
        changed(duel);
        return true;
    }

    boolean confirm(Player player) {
        Duel duel = duelOf(player);
        String id = CoreUtil.id(player);
        if (duel == null || duel.phase != Phase.STAKING) { CoreUtil.error(player, "Not in a duel setup."); return true; }
        if (duel.starting) { actionbar(player, "The arena is already being prepared."); sound(player, "error"); return true; }
        /** A stage cannot be confirmed past unless its own choice is actually valid -- the map stage in
         *  particular, since a map with no committed snapshot is not something a match can be sent to. */
        if (duel.stage == Stage.MAP && selectedMap(duel) == null) {
            CoreUtil.error(player, "Pick a map first."); sound(player, "error");
            return true;
        }
        duel.confirmed.add(id);
        sound(player, "confirm");
        actionbar(player, "Confirmed. Waiting for the other duellist.");
        refreshStage(duel);
        if (!duel.confirmed.contains(duel.a) || !duel.confirmed.contains(duel.b)) return true;
        if (duel.stage != Stage.OPTIONS) {
            advance(duel, duel.stage.next());
            return true;
        }
        /** Final confirmation. Only NOW does anybody's money move, and only now is an arena built. */
        DuelMapService.DuelMap map = selectedMap(duel);
        if (map == null) { both(duel, "That map is no longer available; pick another."); advance(duel, Stage.MAP); return true; }
        double sa = duel.stakes.getOrDefault(duel.a, 0d), sb = duel.stakes.getOrDefault(duel.b, 0d);
        if (sa > 0 && !db.changeBalance(duel.a, -sa)) { both(duel, "Challenger could not cover their stake; duel cancelled."); dispose(duel); return true; }
        if (sb > 0 && !db.changeBalance(duel.b, -sb)) { if (sa > 0) db.changeBalance(duel.a, sa); both(duel, "Opponent could not cover their stake; duel cancelled."); dispose(duel); return true; }
        db.arenaEscrowSet(duel.a, sa); db.arenaEscrowSet(duel.b, sb);
        /** A stake this size is a bet like any other, so it gets the same celebration a spectator wager does. */
        if (plugin.spectacle() != null) {
            Player one = a(duel), two = b(duel);
            if (one != null) plugin.spectacle().bigBet(one, sa, "a duel against " + duel.bName);
            if (two != null) plugin.spectacle().bigBet(two, sb, "a duel against " + duel.aName);
        }
        duel.map = map;
        startMatch(duel);
        return true;
    }

    // ------------------------------------------------------------------ the three-stage setup

    /** Moves the pair to a stage, clearing both confirmations and resetting the stage's own timeout. */
    private void advance(Duel duel, Stage to) {
        /** One sound per stage change, for both duellists, fired here rather than in the screen
         *  openers -- those re-render on every refresh and would chatter. */
        sound(duel, to.ordinal() > duel.stage.ordinal() ? "stage" : "back");
        duel.stage = to;
        duel.stageSince = System.currentTimeMillis();
        duel.confirmed.clear();
        both(duel, "Stage " + (to.ordinal() + 1) + " of 3 — " + to.label() + ".");
        openStage(duel);
    }

    /** Step back one stage. Available from the map and options screens; from the kit screen there is nothing
     *  behind it, so Back there cancels the duel exactly as closing the window does. */
    boolean back(Player player) {
        Duel duel = duelOf(player);
        if (duel == null || duel.phase != Phase.STAKING || duel.starting) return true;
        if (duel.stage == Stage.KIT) { forfeit(player); return true; }
        both(duel, name(CoreUtil.id(player)) + " went back to " + duel.stage.previous().label() + ".");
        advance(duel, duel.stage.previous());
        return true;
    }

    /** Any change at any stage un-confirms BOTH sides. Nobody is ever committed to something they did not
     *  see: a late switch of kit, map or option always costs a fresh agreement from both. */
    private void changed(Duel duel) {
        duel.confirmed.clear();
        refreshStage(duel);
    }

    /** Ids whose duel screen the PLUGIN is currently swapping.
     *
     *  Closing a duel setup screen by hand is a cancel, and Bukkit cannot tell us who closed it: opening the
     *  next stage fires InventoryCloseEvent for the previous one exactly as pressing ESC does. Every
     *  plugin-driven open or close therefore runs inside {@link #transition}, and the close handler ignores
     *  anything that happens while the flag is set. The flag is set and cleared synchronously around the
     *  call, because InventoryCloseEvent fires inside openInventory/closeInventory rather than a tick later. */
    private final Set<String> screenTransition = ConcurrentHashMap.newKeySet();

    private void transition(Player player, Runnable action) {
        if (player != null) transition(CoreUtil.id(player), action);
    }

    private void transition(String id, Runnable action) {
        boolean outermost = screenTransition.add(id);
        try { action.run(); }
        finally { if (outermost) screenTransition.remove(id); }
    }

    /** True when the plugin, not the player, is responsible for the screen closing right now. */
    private boolean isTransitioning(Player player) { return screenTransition.contains(CoreUtil.id(player)); }

    private void openStage(Duel duel) { openStage(a(duel), duel); openStage(b(duel), duel); }

    private void openStage(Player player, Duel duel) {
        if (player == null || duel == null) return;
        switch (duel.stage) {
            case KIT -> openSetup(player);
            case MAP -> openMapSelect(player);
            case OPTIONS -> openOptions(player);
        }
    }

    private void refreshStage(Duel duel) { openStage(duel); }

    /** Re-render whichever setup screen a duellist currently has open, without yanking anybody who has
     *  stepped into a side screen (the wager box or the opponent's wager viewer). */
    private void refreshStageOpen(Duel duel) {
        for (Player p : new Player[]{a(duel), b(duel)})
            if (p != null && p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu m
                    && (m.kind.equals("setup") || m.kind.equals("map") || m.kind.equals("options")))
                openStage(p, duel);
    }

    /** The map the pair have chosen, or null when it is unset or no longer playable. */
    private DuelMapService.DuelMap selectedMap(Duel duel) {
        DuelMapService maps = plugin.duelMaps();
        if (maps == null || duel.mapKey == null) return null;
        DuelMapService.DuelMap map = maps.map(duel.mapKey);
        return map != null && maps.hasSnapshot(map) ? map : null;
    }

    /** Every map is available to every kit -- map choice and kit choice are completely independent. */
    private List<DuelMapService.DuelMap> availableMaps() {
        DuelMapService maps = plugin.duelMaps();
        return maps == null ? List.of() : maps.playableMaps();
    }

    boolean setMap(Player player, String key) {
        Duel duel = duelOf(player);
        if (duel == null || duel.phase != Phase.STAKING || duel.stage != Stage.MAP || duel.starting) return true;
        DuelMapService maps = plugin.duelMaps();
        DuelMapService.DuelMap map = maps == null ? null : maps.map(key);
        if (map == null || !maps.hasSnapshot(map)) { CoreUtil.error(player, "That map is not available."); sound(player, "error"); return true; }
        sound(player, "select");
        duel.mapKey = map.key();
        changed(duel);
        return true;
    }

    private void toggleVisibility(Player player) {
        Duel duel = duelOf(player);
        if (duel == null || duel.phase != Phase.STAKING || duel.stage != Stage.OPTIONS || duel.starting) return;
        sound(player, "toggle");
        duel.visibility = !duel.visibility;
        changed(duel);
    }

    // ------------------------------------------------------------------ the match
    private Player a(Duel d) { return plugin.getServer().getPlayer(d.a); }
    private Player b(Duel d) { return plugin.getServer().getPlayer(d.b); }

    /** Builds this match its OWN arena world -- a clone of the chosen map's committed snapshot -- and starts
     *  the first round in it. Two matches on the same map get two separate worlds, so their terrain, chests
     *  and damage are completely invisible to each other, and editing the template afterwards changes
     *  neither of them. The clone is prepared off the main thread and its chunks are pulled in a slice at a
     *  time, so a duel starting never freezes the server for everybody else. */
    private void startMatch(Duel duel) {
        Player one = a(duel), two = b(duel);
        if (one == null || two == null) { abortAndRefund(duel, "a duellist went offline"); return; }
        DuelMapService maps = plugin.duelMaps();
        if (maps == null || duel.map == null) { abortAndRefund(duel, "the duel map service is unavailable"); return; }
        duel.starting = true;
        duel.rounds.put(duel.a, 0); duel.rounds.put(duel.b, 0);
        transition(one, one::closeInventory); transition(two, two::closeInventory);
        both(duel, "Preparing " + duel.map.name() + "…");
        maps.prepareInstance(duel.map, instance -> {
            duel.starting = false;
            /** Everything can have changed while the clone was being built: either duellist may have
             *  disconnected, and the duel may already have been cancelled out from under us. */
            if (duel.phase != Phase.STAKING || !duels.contains(duel)) {
                if (instance != null) maps.destroyInstance(instance, null);
                return;
            }
            Player p1 = a(duel), p2 = b(duel);
            if (instance == null) {
                if (p1 != null || p2 != null) abortAndRefund(duel, "the arena could not be prepared");
                return;
            }
            if (p1 == null || p2 == null) { maps.destroyInstance(instance, null); abortAndRefund(duel, "a duellist went offline"); return; }
            duel.instance = instance;
            duel.phase = Phase.LIVE;
            sound(duel, "start");
            plugin.getServer().broadcast(Component.text("⚔ " + p1.getName() + " vs " + p2.getName() + " — "
                    + duel.kit.label() + " on " + duel.map.name() + ", best of " + duel.bestOf
                    + ". /duel watch " + duel.id + " to spectate; bet before it starts.", NamedTextColor.GOLD));
            beginRound(duel);
        });
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
        duel.gateOpenedAt = System.currentTimeMillis();
        duel.roundReady.clear();
        capture(one); capture(two);
        one.teleport(corner(duel, 0)); two.teleport(corner(duel, 1));
        equip(one, duel.kit); equip(two, duel.kit);
        /** equip() strips every effect, so the duel's own Visibility Effects are re-applied straight after
         *  rather than waiting for the one-second enforcement tick to notice. */
        applyVisibility(duel);
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
        transition(player, () -> player.openInventory(menu.inv));
    }

    private void refreshReady(Duel duel) { if (duel.gating) { openReady(a(duel), duel); openReady(b(duel), duel); } }

    /** Both sides are ready: drop the gate and start the fight. */
    private void startFight(Duel duel) {
        if (!duel.gating) return;
        Player one = a(duel), two = b(duel);
        if (one == null || two == null) { abortAndRefund(duel, "a duellist went offline"); return; }
        sound(duel, "start");
        duel.gating = false;
        /** The round's own betting window opens here, at the instant the fight actually begins. Personal
         *  windows from the previous round are dropped: a spectator who has been watching since round one
         *  gets the round window like everybody else, and nobody carries a stale deadline forward. */
        duel.roundBettingUntil = System.currentTimeMillis() + bettingWindowMillis();
        duel.spectatorBettingUntil.clear();
        one.closeInventory(); two.closeInventory();
        int round = duel.rounds.get(duel.a) + duel.rounds.get(duel.b) + 1;
        actionbar(one, "Round " + round + " — fight!"); actionbar(two, "Round " + round + " — fight!");
        refreshSpectate(duel);
    }

    /** While gated, a duellist is held on their block (they may still look around) and cannot be hurt.
     *
     *  Only ever inside the arena. A duellist who is somehow OUTSIDE it while a gate is open used to be
     *  pinned to the spot anyway: the server rejected every move while the client kept walking, which is
     *  the classic rubber-band -- "stuck in place server side but can move client side", and untouchable in
     *  both directions because gateShield was cancelling all their damage at the same time. */
    @EventHandler(ignoreCancelled = true) public void freeze(PlayerMoveEvent event) {
        Duel duel = duelOf(event.getPlayer());
        if (duel == null || !duel.gating || !inArena(event.getPlayer())) return;
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

    /** A ready gate that never resolves used to hold both duellists frozen for ever -- there was no timeout
     *  on it at all, so anything that lost a player their Ready screen (a closed GUI, a resource-pack
     *  reload, a client hiccup) left them pinned in place and invulnerable with no way out but a restart.
     *
     *  The gate now breaks itself: past the timeout the round simply starts. Starting is the friendlier
     *  failure -- both duellists are already stood in the arena with their kits, so the fight is what they
     *  were waiting for anyway, and an abort would throw away a match nobody actually left. */
    private void tickReadyGates() {
        long now = System.currentTimeMillis();
        long timeout = Math.max(5, plugin.getConfig().getLong("arena.ready-gate-timeout-seconds", 45)) * 1000L;
        for (Duel duel : new ArrayList<>(duels)) {
            if (!duel.gating || duel.gateOpenedAt <= 0 || now - duel.gateOpenedAt < timeout) continue;
            Player one = a(duel), two = b(duel);
            if (one == null || two == null) { abortAndRefund(duel, "a duellist went offline"); continue; }
            both(duel, "Ready gate timed out — starting the round.");
            startFight(duel);
        }
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
        /** Each side hears its own result. Deliberately here at the top of finish(), before any payout or
         *  teleport work, so the cue lands on the same tick the match actually ends rather than trailing
         *  behind the scoreboard. */
        sound(plugin.getServer().getPlayer(winner), "victory");
        sound(plugin.getServer().getPlayer(loser), "defeat");
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
    /** Tripled while the Central Bank is in deficit, clamped so a deficit can never eat a whole pot. */
    private double wagerTax() { return Math.max(0, Math.min(50, plugin.getConfig().getDouble("arena.wager-tax-percent", 5) * plugin.bank().feeFactor())) / 100.0; }
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
    private long bettingWindowMillis() {
        return Math.max(0, plugin.getConfig().getLong("arena.betting-window-seconds", 10)) * 1000L;
    }

    /** Betting is open before the match, during any ready-gate, and for the first ten seconds of each round.
     *
     *  The gate-only rule was the whole problem: two duellists who both hit Ready at once gave spectators a
     *  window measured in tenths of a second. Rounds now carry their own deadline, which is what lets the
     *  gate stay as short as the duellists want without costing anybody their bet. */
    private boolean bettingOpen(Duel duel) { return bettingOpen(duel, null); }

    private boolean bettingOpen(Duel duel, String spectatorId) {
        if (duel.phase == Phase.PENDING || duel.phase == Phase.STAKING) return true;
        /** ENDING means the match is over and the pools are being paid out -- nothing may be staked against a
         *  result that already exists, whatever anybody's personal window says. Same for the moment a round
         *  is being resolved. */
        if (duel.phase != Phase.LIVE || duel.resolving) return false;
        if (duel.gating) return true;
        long now = System.currentTimeMillis();
        if (now < duel.roundBettingUntil) return true;
        /** A late arrival's own ten seconds, which can never outlive the round it was granted in. */
        Long personal = spectatorId == null ? null : duel.spectatorBettingUntil.get(spectatorId);
        return personal != null && now < personal;
    }

    /** One line telling a spectator exactly how long they have. -1 means an open-ended window (before the
     *  match, or during a ready-gate), which needs no countdown. */
    private String bettingHint(long secondsLeft) {
        if (secondsLeft < 0) return "Betting is open.";
        return secondsLeft == 0 ? "Betting for this round has closed." : "Betting closes in " + secondsLeft + "s.";
    }

    /** Seconds left for this viewer, for the GUI to show. 0 when betting is shut. */
    private long bettingSecondsLeft(Duel duel, String spectatorId) {
        if (duel.phase == Phase.PENDING || duel.phase == Phase.STAKING || (duel.phase == Phase.LIVE && duel.gating)) return -1;
        if (!bettingOpen(duel, spectatorId)) return 0;
        long now = System.currentTimeMillis();
        long deadline = Math.max(duel.roundBettingUntil, duel.spectatorBettingUntil.getOrDefault(spectatorId, 0L));
        return Math.max(0, (deadline - now + 999) / 1000);
    }

    /** The round a "this round" bet applies to: the one about to be fought (or round 1 before the match). */
    private int currentRound(Duel duel) { return duel.rounds.getOrDefault(duel.a, 0) + duel.rounds.getOrDefault(duel.b, 0) + 1; }

    /** Places or CHANGES a wager for a given scope (round 0 = whole match, N = a specific round), while betting
     *  is open. A spectator may hold one wager per scope. Changing a scope refunds its old stake first, so the
     *  money is always conserved. */
    private boolean placeWager(Player player, Duel duel, String target, double amount, int round) {
        if (duel == null) { CoreUtil.error(player, "No such match."); return true; }
        if (!bettingOpen(duel, CoreUtil.id(player))) { CoreUtil.error(player, "Betting for this round has closed."); return true; }
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
        /** Only the increase counts as a big bet: raising 9m to 11m is a 2m move, and re-confirming the
         *  same wager should not fire the show a second time. */
        if (plugin.spectacle() != null && amount > (existing == null ? 0 : existing.amount()))
            plugin.spectacle().bigBet(player, amount - (existing == null ? 0 : existing.amount()), "a wager on " + name(target));
        refreshSpectate(duel);
        return true;
    }

    private Wager wagerOf(Duel duel, String id) { return duel.wagers.stream().filter(w -> w.player().equals(id)).findFirst().orElse(null); }

    boolean watch(Player player, int duelId) {
        Duel duel = duels.stream().filter(d -> d.id == duelId).findFirst().orElse(null);
        if (duel == null) { CoreUtil.error(player, "No such match."); return true; }
        if (duel.has(CoreUtil.id(player))) { CoreUtil.error(player, "You are in this duel."); return true; }
        /** Opening the match screen is arriving, whether or not they go on to enter the arena -- otherwise a
         *  spectator who prefers to watch from the list would never get a window at all. Granted once per
         *  round: reopening the screen cannot roll the clock forward again. */
        String viewer = CoreUtil.id(player);
        if (duel.phase == Phase.LIVE && !duel.gating && !duel.spectatorBettingUntil.containsKey(viewer))
            duel.spectatorBettingUntil.put(viewer, System.currentTimeMillis() + bettingWindowMillis());
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
        /** Their own ten seconds, but never past the end of the round they walked into -- a window granted
         *  during a fight that ends two seconds later dies with it, because bettingOpen re-checks the phase. */
        if (duel.phase == Phase.LIVE && !duel.gating)
            duel.spectatorBettingUntil.put(id, System.currentTimeMillis() + bettingWindowMillis());
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

    /** The legacy shared-slot arena world only. Per-match instance worlds have their own break rules, owned
     *  by DuelMapService, which is what lets an imported map be fully breakable while the built flat arenas
     *  stay pristine -- so this class must not also have an opinion about blocks inside one. */
    private boolean inLegacyArena(Player player) { return arena != null && player.getWorld().equals(arena); }

    @EventHandler(ignoreCancelled = true) public void bucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        Duel duel = duelOf(event.getPlayer());
        if (duel == null || duel.phase != Phase.LIVE || !inLegacyArena(event.getPlayer())) return;
        org.bukkit.block.Block b = event.getBlock();
        duel.placed.add(key(b.getX(), b.getY(), b.getZ()));
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (!inLegacyArena(event.getPlayer())) return;
        Duel duel = duelOf(event.getPlayer());
        if (duel == null || duel.phase != Phase.LIVE) { event.setCancelled(true); return; }
        org.bukkit.block.Block block = event.getBlockPlaced();
        duel.placed.add(key(block.getX(), block.getY(), block.getZ()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (!inLegacyArena(event.getPlayer())) return;
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
        /** In an instance the terrain itself needs no repair -- the world is thrown away when the match ends
         *  and the next match gets a fresh clone -- but blocks placed in round one must not still be standing
         *  in round two, so the placed set is wiped between rounds either way. */
        if (duel.instance != null) {
            DuelMapService maps = plugin.duelMaps();
            if (maps != null) maps.clearPlaced(duel.instance);
            duel.placed.clear();
            return;
        }
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
    /** Vanilla advancements do not count inside a duel either.
     *
     *  A duel hands out a full kit -- netherite, an elytra, a mace -- so without this a player could collect
     *  "Cover Me With Diamonds", "Sky's the Limit" and friends from gear they never earned and do not keep.
     *  PlayerAdvancementDoneEvent is not cancellable, so the grant is revoked on the same tick instead: the
     *  advancement was earned a moment ago in the arena, so undoing it takes nothing the player had before.
     *  Recipe advancements are left alone -- revoking those would strip recipe-book entries. */
    /** The toast, killed at the only point where killing it is free.
     *
     *  Revoking a finished advancement (below) undoes the record, but by then the server has already run
     *  the advancement's rewards and queued the client update, and the player has already watched the
     *  popup. Paper fires PlayerAdvancementCriterionGrantEvent one step earlier -- before the criterion is
     *  written -- and it is cancellable. Cancelling it means the criterion is never granted, the
     *  advancement never completes, no reward is handed out, nothing is queued for the client, and there is
     *  no toast to suppress.
     *
     *  It is also gentler than the revoke: revoking a completed advancement strips EVERY criterion on it,
     *  including ones the player earned legitimately days ago outside the arena. Refusing the grant leaves
     *  all of that alone -- the duel simply contributes nothing towards it.
     *
     *  The revoke handler stays as the backstop for anything that reaches "done" by another route. */
    @EventHandler(ignoreCancelled = true) public void advancementCriterion(com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent event) {
        if (!inArena(event.getPlayer())) return;
        if (event.getAdvancement().getKey().getKey().startsWith("recipes/")) return;
        event.setCancelled(true);
    }

    @EventHandler public void advancement(org.bukkit.event.player.PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        if (!inArena(player)) return;
        org.bukkit.advancement.Advancement advancement = event.getAdvancement();
        if (advancement.getKey().getKey().startsWith("recipes/")) return;
        org.bukkit.advancement.AdvancementProgress progress = player.getAdvancementProgress(advancement);
        for (String criterion : new ArrayList<>(progress.getAwardedCriteria())) progress.revokeCriteria(criterion);
        event.message(null);
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        String id = CoreUtil.id(event.getPlayer());
        Duel duel = byPlayer.get(id);
        if (duel != null && duel.phase == Phase.LIVE && duel.has(id)) { duel.disconnectedAt.put(id, System.currentTimeMillis()); return; }
        /** Dropping out DURING setup cancels the duel rather than leaving the other player staring at a
         *  screen waiting for somebody who is not coming back. Nothing has been charged yet at that point,
         *  so this costs neither side anything -- but any items already staged in the wager box are returned. */
        if (duel != null && duel.has(id) && (duel.phase == Phase.PENDING || duel.phase == Phase.STAKING)) {
            if (duel.starting) return;
            abortAndRefund(duel, event.getPlayer().getName() + " disconnected during setup");
            return;
        }
        if (inArena(event.getPlayer())) restore(event.getPlayer());
    }

    private void tick() {
        tickReadyGates();
        long grace = Math.max(3, plugin.getConfig().getLong("arena.reconnect-grace-seconds", 10)) * 1000L;
        long setupTimeout = Math.max(30, plugin.getConfig().getLong("arena.setup-timeout-seconds", 180)) * 1000L;
        long now = System.currentTimeMillis();
        for (Duel duel : new ArrayList<>(duels)) {
            if (duel.phase == Phase.PENDING && now - duel.pendingSince > 60000) { both(duel, "Challenge expired."); dispose(duel); continue; }
            /** A setup stage nobody finishes must not hold a concurrency slot for ever. Each stage gets its
             *  own clock, reset whenever the pair move between stages, so a slow but active setup is never
             *  cut off -- only an abandoned one. The clone in progress is exempt: it finishes or fails on its
             *  own, and cancelling a duel out from under it would leave the world behind. */
            if (duel.phase == Phase.STAKING && !duel.starting && now - duel.stageSince > setupTimeout) {
                abortAndRefund(duel, "the " + duel.stage.label() + " stage timed out");
                continue;
            }
            if (duel.phase != Phase.LIVE) continue;
            applyVisibility(duel);
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
        sound(duel, "cancel");
        both(duel, "Duel cancelled — " + why + ". All stakes, money bets and wagered items refunded.");
        resetArena(duel);
        returnPlayers(duel);
        refundItemWagers(duel);
        dispose(duel);
    }

    /** Keeps both duellists Glowing and with Night Vision for the whole fight when the option is on.
     *
     *  Applied continuously rather than once: potion effects are cleared at every round start, a golden
     *  apple or a milk bucket would otherwise wipe them mid-fight, and the player's own /settings night
     *  vision is not something the duel should have to negotiate with. The effects are short and constantly
     *  refreshed, so nothing lingers once the match is over -- and restore() clears every effect anyway
     *  before putting back the ones the player had before the duel.
     *
     *  With the option OFF nothing is forced in either direction: the players simply keep whatever they had. */
    private void applyVisibility(Duel duel) {
        if (!duel.visibility || duel.phase != Phase.LIVE) return;
        for (Player player : new Player[]{a(duel), b(duel)}) {
            if (player == null || !inArena(player)) continue;
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0, true, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 400, 0, true, false, false));
        }
    }

    private void returnPlayers(Duel duel) {
        for (String id : List.of(duel.a, duel.b)) { Player player = plugin.getServer().getPlayer(id); if (player != null) restore(player); }
        for (Map.Entry<String, Integer> entry : new ArrayList<>(spectators.entrySet())) {
            if (entry.getValue() != duel.id) continue;
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) leaveSpectator(player); else spectators.remove(entry.getKey());
        }
    }

    /** The single place a duel stops existing, so the arena world it owned is destroyed on EVERY exit path --
     *  a clean finish, a forfeit, a refund, a cancelled setup or a shutdown. An instance world is worthless
     *  the moment its match ends; anything that still survives (a Windows file handle, a crash) is swept by
     *  the detached sweeper or removed at the next startup. */
    private void dispose(Duel duel) {
        byPlayer.remove(duel.a); byPlayer.remove(duel.b);
        duels.remove(duel);
        if (duel.instance != null) {
            DuelMapService maps = plugin.duelMaps();
            World world = duel.instance;
            duel.instance = null;
            if (maps != null) maps.destroyInstance(world, null);
        }
    }

    private String name(String id) { Player p = plugin.getServer().getPlayer(id); return p != null ? p.getName() : (byPlayer.containsKey(id) ? id : id); }

    private void both(Duel duel, String message) {
        Player one = a(duel), two = b(duel);
        if (one != null) CoreUtil.msg(one, message);
        if (two != null) CoreUtil.msg(two, message);
    }

    /** Shorthand for the shared GUI sound vocabulary, so every call site reads as the action it is. */
    private void sound(Player player, String action) {
        if (player != null && plugin.settings() != null) plugin.settings().uiSound(player, action);
    }

    private void sound(Duel duel, String action) { sound(a(duel), action); sound(b(duel), action); }

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
                .append(" | ").append(d.kit.label()).append(" | ").append(d.map != null ? d.map.name() : (d.mapKey == null ? "no map" : d.mapKey))
                .append(" | Bo").append(d.bestOf).append(" | ").append(d.phase)
                .append(d.phase == Phase.STAKING ? " (" + d.stage.label() + ")" : "")
                .append(d.instance != null ? " | " + d.instance.getName() : "");
        return sb.toString();
    }

    // ------------------------------------------------------------------ GUIs
    private final class Menu implements InventoryHolder {
        final String kind; final int duelId; Inventory inv;
        /** When true this inventory is a real container the player fills (the item-wager box), so clicks and
         *  drags are NOT cancelled. Every other Menu is a button panel and stays fully click-locked. */
        boolean fillable = false;
        /** Kit-layout editor state. The arrangement being edited is held here rather than re-read from the
         *  database on every click, so a click is one swap and one save rather than a round trip. */
        Kit layoutKit;
        int[] layout;
        int selected = -1;
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
        transition(player, () -> player.openInventory(menu.inv));
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
        transition(player, () -> player.openInventory(menu.inv));
    }

    // ---- stage 2: the map --------------------------------------------------------------------------
    /** Every map is offered to every kit -- there is no map a kit cannot use and no kit a map belongs to.
     *  Maps with no committed snapshot are not shown at all rather than shown and then failing. */
    private void openMapSelect(Player player) {
        if (player == null) return;
        Duel duel = duelOf(player);
        if (duel == null) return;
        Menu menu = new Menu("map", duel.id);
        menu.inv = plugin.getServer().createInventory(menu, 45, Component.text("Duel setup — Map (2/3)", NamedTextColor.DARK_AQUA));
        List<DuelMapService.DuelMap> maps = availableMaps();
        menu.inv.setItem(4, icon(Material.FILLED_MAP, "Choose the arena",
                List.of("Kit: " + duel.kit.label() + " · Best of " + duel.bestOf, "Every kit can use every map.")));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < maps.size() && i < slots.length; i++) {
            DuelMapService.DuelMap map = maps.get(i);
            boolean picked = map.key().equals(duel.mapKey);
            ItemStack ico = icon(mapIcon(map), (picked ? "✔ SELECTED — " : "") + map.name(), List.of(
                    map.rule() == DuelMapService.BreakRule.PLACED_ONLY
                            ? "Only blocks placed this match can be broken"
                            : "Fully breakable, explosions included",
                    picked ? "This map is selected" : "Click to pick this map"));
            if (picked) glow(ico);
            menu.inv.setItem(slots[i], ico);
        }
        if (maps.isEmpty()) menu.inv.setItem(22, icon(Material.BARRIER, "No maps are ready",
                List.of("An admin must build and save a duel map", "before matches can be played on one.")));
        DuelMapService.DuelMap chosen = selectedMap(duel);
        stageFooter(menu, duel, player, chosen == null ? "Pick a map first" : "Confirm " + chosen.name());
        transition(player, () -> player.openInventory(menu.inv));
    }

    private Material mapIcon(DuelMapService.DuelMap map) {
        return switch (map.key()) {
            case "arena50" -> Material.RED_TERRACOTTA;
            case "arena100" -> Material.OBSIDIAN;
            case "temple_of_tides" -> Material.PRISMARINE;
            case "cinder_crucible" -> Material.MAGMA_BLOCK;
            case "deepstone_mines" -> Material.DEEPSLATE;
            case "skyroot_village" -> Material.OAK_LOG;
            default -> Material.MAP;
        };
    }

    // ---- stage 3: final options --------------------------------------------------------------------
    private void openOptions(Player player) {
        if (player == null) return;
        Duel duel = duelOf(player);
        if (duel == null) return;
        DuelMapService.DuelMap map = selectedMap(duel);
        Menu menu = new Menu("options", duel.id);
        menu.inv = plugin.getServer().createInventory(menu, 45, Component.text("Duel setup — Final Options (3/3)", NamedTextColor.DARK_AQUA));
        menu.inv.setItem(4, icon(duel.kit.icon(), "Everything so far", List.of(
                "Kit: " + duel.kit.label(),
                "Map: " + (map == null ? "none" : map.name()),
                "Best of " + duel.bestOf,
                "Your stake: " + CoreUtil.money(duel.stakes.getOrDefault(CoreUtil.id(player), 0d)))));
        menu.inv.setItem(22, glow(icon(duel.visibility ? Material.GLOW_INK_SAC : Material.INK_SAC,
                "Visibility Effects: " + (duel.visibility ? "ON" : "OFF"), List.of(
                duel.visibility ? "Both duellists glow and keep Night Vision" : "Neither effect is forced on anybody",
                duel.visibility ? "for the whole duel, whatever your /settings say" : "You each keep whatever you already had",
                "Click to turn " + (duel.visibility ? "OFF" : "ON")))));
        stageFooter(menu, duel, player, "Confirm and start the duel");
        transition(player, () -> player.openInventory(menu.inv));
    }

    /** The shared bottom bar of the map and options screens: Back, and a Confirm that shows both sides'
     *  state so neither player has to ask whether the other has agreed yet. */
    private void stageFooter(Menu menu, Duel duel, Player player, String confirmHint) {
        String meId = CoreUtil.id(player), themId = duel.other(meId);
        String meName = meId.equals(duel.a) ? duel.aName : duel.bName, themName = meId.equals(duel.a) ? duel.bName : duel.aName;
        boolean ready = duel.confirmed.contains(meId);
        menu.inv.setItem(36, icon(Material.ARROW, "Back", List.of("Return to " + duel.stage.previous().label(),
                "Both confirmations are cleared")));
        menu.inv.setItem(44, icon(Material.BARRIER, "Cancel duel", List.of("Calls the whole thing off", "Nothing has been charged yet")));
        menu.inv.setItem(40, icon(ready ? Material.YELLOW_CONCRETE : Material.LIME_CONCRETE,
                ready ? "Confirmed — waiting for " + themName + "…" : confirmHint, List.of(
                meName + ": " + (ready ? "READY" : "not ready"),
                themName + ": " + (duel.confirmed.contains(themId) ? "READY" : "not ready"),
                "Stage " + (duel.stage.ordinal() + 1) + " of 3")));
    }

    /** Closing a setup screen by hand IS a cancel, at every stage, and goes through exactly the same path
     *  /duel cancel does -- {@link #forfeit}, which for a duel that has not started yet aborts it, refunds
     *  both sides and returns any staged wager items.
     *
     *  There is no "already confirmed" exemption: a player who has confirmed and then closes the window has
     *  walked away from the duel, and leaving the match half-alive waiting for them is exactly the state this
     *  is meant to avoid. The only closes that do NOT cancel are the ones the plugin itself performs while
     *  swapping screens, and those are recognised by the {@link #screenTransition} guard rather than guessed
     *  at from what happens to be open a couple of ticks later. */
    @EventHandler public void closeSetup(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Menu menu) || !isSetupScreen(menu.kind)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        if (isTransitioning(player)) return;
        Duel duel = byPlayer.get(CoreUtil.id(player));
        if (duel == null || duel.starting) return;
        if (duel.phase != Phase.STAKING && duel.phase != Phase.PENDING) return;
        /** Deferred by a tick purely because cancelling inside the close event would re-enter inventory
         *  handling; the DECISION has already been made here, so nothing can change it in between. */
        Bukkit.getScheduler().runTask(plugin, () -> {
            Duel current = byPlayer.get(CoreUtil.id(player));
            if (current != duel || duel.starting) return;
            if (duel.phase != Phase.STAKING && duel.phase != Phase.PENDING) return;
            cancelSetup(player, duel);
        });
    }

    /** The one place a manually closed setup screen turns into a cancellation, so the close handler, the
     *  Cancel button and /duel cancel cannot drift apart. */
    private void cancelSetup(Player player, Duel duel) {
        Player other = plugin.getServer().getPlayer(duel.other(CoreUtil.id(player)));
        if (other != null) transition(other, other::closeInventory);
        forfeit(player);
    }

    private static boolean isSetupScreen(String kind) {
        return kind.equals("setup") || kind.equals("map") || kind.equals("options");
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
    if (duelOf(player) != null) sound(player, "adjust");
        Duel duel = duelOf(player);
        if (duel == null) return;
        duel.stakes.put(CoreUtil.id(player), amount);
        changed(duel);
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
            /** The wager box: the top three rows and the player's own inventory are freely usable. The
             *  control row and the opponent's read-only wager below it stay click-locked. */
            if (!(event.getWhoClicked() instanceof Player boxPlayer)) return;
            int raw = event.getRawSlot();
            /** Double-click-to-gather sweeps matching items out of EVERY slot in the view, the opponent's
             *  display panel included, and lands them on the cursor. That is a mint, so it is refused. */
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) { event.setCancelled(true); return; }
            if (raw >= WAGER_STAGE && raw < 54) {
                event.setCancelled(true);
                Duel open = duelOf(boxPlayer);
                switch (raw) {
                    case 27 -> { if (open != null) openStage(boxPlayer, open); }
                    case 29 -> clearWager(boxPlayer, event.getInventory());
                    case 31 -> confirmWager(boxPlayer, event.getInventory());
                    case 53 -> { if (open != null && event.getCurrentItem() != null
                            && event.getCurrentItem().getType() == Material.SPYGLASS) openOpponentWager(boxPlayer); }
                    default -> { }
                }
                return;
            }
            if (raw >= 54 && event.isShiftClick()) { event.setCancelled(true); stageIntoBox(boxPlayer, event); }
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
                    case 40 -> { transition(player, player::closeInventory); confirm(player); }
                    case 42 -> openWagerBox(player);
                    case 43 -> openOpponentWager(player);
                    default -> { }
                }
            }
            case "map" -> {
                List<DuelMapService.DuelMap> options = availableMaps();
                int[] mapSlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
                for (int i = 0; i < options.size() && i < mapSlots.length; i++)
                    if (slot == mapSlots[i]) { setMap(player, options.get(i).key()); return; }
                switch (slot) {
                    case 36 -> back(player);
                    case 40 -> confirm(player);
                    case 44 -> { transition(player, player::closeInventory); forfeit(player); }
                    default -> { }
                }
            }
            case "options" -> {
                switch (slot) {
                    case 22 -> toggleVisibility(player);
                    case 36 -> back(player);
                    case 40 -> confirm(player);
                    case 44 -> { transition(player, player::closeInventory); forfeit(player); }
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
                                 if (target == null) { actionbar(player, "Pick a fighter to back first."); sound(player, "error"); }
                                 else sound(player, placeWager(player, duel, target, st[1], scope) ? "confirm" : "error");
                                 openSpectate(player, menu.duelId); }
                    case 42 -> { if (spectators.containsKey(id)) leaveSpectator(player); else player.closeInventory(); }
                    /** Entering the arena CLOSES the menu. Reopening it here put the betting screen back
                     *  on top of a player who had just been teleported in to watch -- a window they could
                     *  drag around mid-fight, which is the "GUI bugs out after I start spectating" report.
                     *  Only a failed entry (wrong phase, still gated) leaves the menu up to explain itself. */
                    case 44 -> {
                        if (duel.phase == Phase.LIVE && !duel.gating) { enterSpectator(player, duel); transition(player, player::closeInventory); }
                        else { sound(player, "error"); openSpectate(player, menu.duelId); }
                    }
                    default -> { }
                }
            }
            case "wagerview" -> { if (slot == 49) { Duel back = duelOf(player); if (back != null) openStage(player, back); } return; }
            case "layoutpick" -> {
                Kit[] kits = Kit.values();
                int[] kitSlots = {10, 12, 14, 16};
                for (int i = 0; i < kits.length; i++) if (slot == kitSlots[i]) { openKitLayout(player, kits[i]); return; }
                if (slot == 26) transition(player, player::closeInventory);
                return;
            }
            case "kitlayout" -> { layoutClick(player, menu, slot); return; }
            case "ready" -> {
                Duel duel = find(menu.duelId);
                if (duel == null || !duel.gating) { player.closeInventory(); return; }
                if (slot == 13 && duel.has(CoreUtil.id(player))) {
                    duel.roundReady.add(CoreUtil.id(player));
                    sound(player, "ready");
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
        transition(player, () -> player.openInventory(menu.inv));
    }

    /** Where the wager box stops being yours.
     *
     *  Slots 0-26 are the only ones a player may put anything into. 27-35 is the control row, and 36-53 is a
     *  read-only view of what the opponent has staked -- you should not have to leave the screen you are
     *  staking on to see what you are staking against. Every scan of "what did this player put in the box"
     *  stops at WAGER_STAGE, which is what keeps the opponent's DISPLAY COPIES from ever being handed out:
     *  they are pictures of somebody else's escrow, and treating one as a staged item would mint it. */
    private static final int WAGER_STAGE = 27, WAGER_VIEW = 36;

    /** Paints the bottom two rows with the opponent's confirmed wager, and labels it.
     *
     *  Display copies only, and every slot from WAGER_STAGE up is click-locked, so there is no path from
     *  this panel to an inventory. The copies carry an extra lore line saying so, because an item that
     *  looks exactly like a real one and cannot be picked up needs to say why. */
    private void renderOpponentWager(Duel duel, Player player, Inventory box) {
        String me = CoreUtil.id(player);
        String themId = duel.other(me), themName = me.equals(duel.a) ? duel.bName : duel.aName;
        List<ItemStack> theirs = loadWager(duel, themId);
        for (int slot = WAGER_VIEW; slot < 54; slot++) box.setItem(slot, filler());
        int room = 54 - WAGER_VIEW;
        boolean overflow = theirs.size() > room;
        int shown = overflow ? room - 1 : theirs.size();
        for (int i = 0; i < shown; i++) box.setItem(WAGER_VIEW + i, viewOnly(theirs.get(i)));
        if (overflow)
            box.setItem(53, icon(Material.SPYGLASS, "+" + (theirs.size() - shown) + " more stack(s)",
                    List.of("Click to see " + themName + "'s full wager", "Unconfirmed items are returned")));
        else if (theirs.isEmpty())
            box.setItem(WAGER_VIEW + 4, icon(Material.BARRIER, themName + " has not wagered anything", List.of("Nothing staked on their side yet")));
        box.setItem(35, icon(Material.SHIELD, themName + "'s wager", List.of("Shown in the bottom two rows", "View only \u2014 " + theirs.size() + " stack(s)")));
    }

    /** A display copy: the real item, plus a line making it obvious it cannot be taken. */
    private ItemStack viewOnly(ItemStack item) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.text("View only \u2014 your opponent's stake", NamedTextColor.DARK_GRAY));
            meta.lore(lore);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    /** Repaints the opponent panel of any wager box that is currently open, IN PLACE.
     *
     *  Never by reopening the screen: closing a wager box hands back everything unconfirmed sitting in it,
     *  so re-opening somebody's box to refresh it would empty their staging area under them. */
    private void refreshWagerBoxes(Duel duel) {
        for (Player p : new Player[]{a(duel), b(duel)}) {
            if (p == null) continue;
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder(false) instanceof Menu m && "wagerbox".equals(m.kind) && m.duelId == duel.id)
                renderOpponentWager(duel, p, top);
        }
    }

    /** Moves a shift-clicked stack from the player's own inventory into the STAGING AREA only.
     *
     *  Bukkit's own shift-click would spread the stack across the whole top inventory, opponent panel
     *  included, where it would be quietly destroyed when the box closed (nothing outside the staging area
     *  is ever collected). Placing it by hand keeps the move inside slots 0-26, and what is added to the box
     *  is exactly what is taken off the source stack. */
    private void stageIntoBox(Player player, InventoryClickEvent event) {
        ItemStack moving = event.getCurrentItem();
        if (moving == null || moving.getType().isAir()) return;
        Inventory box = event.getInventory();
        int remaining = moving.getAmount(), max = Math.max(1, moving.getMaxStackSize());
        for (int slot = 0; slot < WAGER_STAGE && remaining > 0; slot++) {
            ItemStack there = box.getItem(slot);
            if (there == null || !there.isSimilar(moving)) continue;
            int take = Math.min(max - there.getAmount(), remaining);
            if (take <= 0) continue;
            there.setAmount(there.getAmount() + take);
            box.setItem(slot, there);
            remaining -= take;
        }
        for (int slot = 0; slot < WAGER_STAGE && remaining > 0; slot++) {
            ItemStack there = box.getItem(slot);
            if (there != null && !there.getType().isAir()) continue;
            ItemStack piece = moving.clone();
            piece.setAmount(Math.min(max, remaining));
            box.setItem(slot, piece);
            remaining -= piece.getAmount();
        }
        if (remaining == moving.getAmount()) { actionbar(player, "The staging area is full \u2014 Confirm what is there first."); sound(player, "error"); return; }
        if (remaining <= 0) event.setCurrentItem(null);
        else { ItemStack left = moving.clone(); left.setAmount(remaining); event.setCurrentItem(left); }
    }

    private void openWagerBox(Player player) {
        Duel duel = duelOf(player);
        if (duel == null || duel.phase != Phase.STAKING) { actionbar(player, "Items can only be wagered before the match starts."); return; }
        Menu menu = new Menu("wagerbox", duel.id);
        menu.fillable = true;
        menu.inv = plugin.getServer().createInventory(menu, 54, Component.text("Wager items \u2014 winner takes all", NamedTextColor.DARK_AQUA));
        for (int slot = WAGER_STAGE; slot < 54; slot++) menu.inv.setItem(slot, filler());
        int already = loadWager(duel, CoreUtil.id(player)).size();
        menu.inv.setItem(27, icon(Material.ARROW, "Back", List.of("Return to duel setup", "Unconfirmed items above are returned")));
        menu.inv.setItem(29, icon(Material.CAULDRON, "Clear wager", List.of(already > 0 ? "Return all " + already + " staged stack(s)" : "Nothing staged yet")));
        menu.inv.setItem(31, icon(Material.LIME_CONCRETE, "Confirm wager", List.of("Add the items above to your wager", "Winner takes both sides' wagered items")));
        menu.inv.setItem(33, icon(Material.BOOK, "Currently wagered", List.of(already + " stack(s) staged", "Add more above, then Confirm")));
        renderOpponentWager(duel, player, menu.inv);
        /** Through transition(), like every other screen change here.
         *
         *  Opening an inventory fires InventoryCloseEvent for the one being replaced, and closeSetup turns a
         *  closed setup screen into a CANCELLED DUEL. Without the guard, clicking "Wager items" closed the
         *  stage screen, closeSetup saw an ordinary walk-away and forfeited the match -- reported as "when I
         *  go to wager items it cancels the duel". openWagerView already went through transition; this was
         *  the single screen that did not. */
        transition(player, () -> player.openInventory(menu.inv));
        CoreUtil.msg(player, "Put items in the top three rows, then click Confirm to stake them. Closing without confirming returns them.");
    }

    /** Appends the items staged in the top of the box to the player's DB item-wager escrow, then clears the box
     *  and returns to setup. Escrow only ever changes here and in clearWager, so nothing is wagered by accident
     *  and an already-escrowed item can never be pulled back out of the box for free. */
    private void confirmWager(Player player, Inventory box) {
        String id = CoreUtil.id(player);
        Duel duel = duelOf(player);
        List<ItemStack> staged = new ArrayList<>();
        for (int i = 0; i < WAGER_STAGE; i++) { ItemStack it = box.getItem(i); if (it != null && !it.getType().isAir()) staged.add(it); }
        if (duel == null || duel.phase != Phase.STAKING) {
            /** Clear the box BEFORE handing anything back. closeInventory() below fires closeWager, which
             *  returns whatever it finds still sitting in the top of the box -- so refunding the items and
             *  then leaving them there handed out a second copy of every stack. That is the reported
             *  duplication, and it is exactly 2x because there are exactly two return paths. */
            for (int i = 0; i < WAGER_STAGE; i++) box.setItem(i, null);
            giveOrStash(id, staged, "Your items were returned — the match was no longer accepting wagers.");
            player.closeInventory();
            return;
        }
        if (staged.isEmpty()) { actionbar(player, "Put items in the box first, then Confirm."); sound(player, "error"); return; }
        List<ItemStack> full = new ArrayList<>(loadWager(duel, id));
        full.addAll(staged);
        db.arenaItemWagerSave(duel.id, id, ItemStack.serializeItemsAsBytes(full.toArray(new ItemStack[0])));
        for (int i = 0; i < WAGER_STAGE; i++) box.setItem(i, null);
        sound(player, "confirm");
        actionbar(player, "Wager confirmed \u2014 " + full.size() + " stack(s) staked.");
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline() && duelOf(player) == duel && duel.phase == Phase.STAKING) openStage(player, duel); refreshStageOpen(duel); refreshWagerBoxes(duel); });
    }

    /** Returns every item the player has wagered (confirmed escrow plus anything unconfirmed still in the box)
     *  and empties their escrow. */
    private void clearWager(Player player, Inventory box) {
        String id = CoreUtil.id(player);
        Duel duel = duelOf(player);
        List<ItemStack> back = new ArrayList<>();
        for (int i = 0; i < WAGER_STAGE; i++) { ItemStack it = box.getItem(i); if (it != null && !it.getType().isAir()) { back.add(it); box.setItem(i, null); } }
        if (duel != null) { back.addAll(loadWager(duel, id)); db.arenaItemWagerClear(duel.id, id); }
        sound(player, "cancel");
        giveOrStash(id, back, "Wager cleared \u2014 items returned.");
        player.closeInventory();
        if (duel != null) Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline() && duelOf(player) == duel && duel.phase == Phase.STAKING) openStage(player, duel); refreshStageOpen(duel); refreshWagerBoxes(duel); });
    }

    /** On closing the wager box, escrow whatever is inside to the DB (crash-safe) and hand any surplus back if
     *  the match already ended/started while it was open. Then return the duellist to the setup GUI. */
    @EventHandler public void closeWager(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Menu menu)) return;
        if (!menu.kind.equals("wagerbox") && !menu.kind.equals("wagerview")) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        String id = CoreUtil.id(player);
        /** Anything still in the TOP of the box was never confirmed, so closing hands it straight back -- closing
         *  is a cancel. Confirmed items already live in the DB escrow and are untouched here. */
        List<ItemStack> unconfirmed = new ArrayList<>();
        if (menu.kind.equals("wagerbox"))
            /** Emptied as they are collected, so no future path that closes the box after already
             *  refunding can hand out a second copy, whatever order the handlers run in. */
            for (int i = 0; i < WAGER_STAGE; i++) {
                ItemStack it = event.getInventory().getItem(i);
                if (it != null && !it.getType().isAir()) { unconfirmed.add(it); event.getInventory().setItem(i, null); }
            }
        if (!unconfirmed.isEmpty()) giveOrStash(id, unconfirmed, "Unconfirmed wager items were returned.");
        /** The wager box is a sub-screen of the kit stage, not a stage of its own, so closing it puts the
         *  duellist back on their stage rather than cancelling. That also keeps the invariant the cancel rule
         *  depends on: during setup a duellist always has a stage screen open, so closing one really does
         *  mean they walked away. */
        Duel duel = byPlayer.get(id);
        if (duel == null || duel.phase != Phase.STAKING || duel.starting) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || byPlayer.get(id) != duel || duel.phase != Phase.STAKING) return;
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu open
                    && (isSetupScreen(open.kind) || open.kind.equals("wagerbox") || open.kind.equals("wagerview"))) return;
            openStage(player, duel);
        });
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
        transition(player, () -> player.openInventory(menu.inv));
    }

    /** The spectator / betting window. Shows both fighters, kit, series score and stakes; lets a spectator
     *  stage and confirm (or change) a wager before the match locks; and, once the match is live, enter the
     *  arena to watch in the dedicated invisible spectator state. */
    void openSpectate(Player player, int duelId) {
        Duel duel = find(duelId);
        if (duel == null) { CoreUtil.error(player, "That match has ended."); player.closeInventory(); return; }
        String id = CoreUtil.id(player);
        boolean bettingOpen = bettingOpen(duel, CoreUtil.id(player));
        long bettingLeft = bettingSecondsLeft(duel, CoreUtil.id(player));
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
                List.of(scopeLabel + " pool on " + duel.aName + ": " + CoreUtil.money(poolA), bettingOpen ? "Click to back " + duel.aName : "Betting closed", bettingHint(bettingLeft))));
        menu.inv.setItem(24, icon(backB ? Material.RED_CONCRETE : Material.WHITE_CONCRETE, (backB ? "✔ " : "") + "Back " + duel.bName,
                List.of(scopeLabel + " pool on " + duel.bName + ": " + CoreUtil.money(poolB), bettingOpen ? "Click to back " + duel.bName : "Betting closed", bettingHint(bettingLeft))));
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
        transition(player, () -> player.openInventory(menu.inv));
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
        for (int raw : event.getRawSlots()) if (raw >= WAGER_STAGE && raw < 54) { event.setCancelled(true); return; }
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
        /** Kit layouts, as a bijection test -- which is the entire safety argument for /duels.
     *
     *  What a layout stores is a PERMUTATION of the kit's default arrangement, never items, and a
     *  permutation cannot mint or delete anything. That argument only holds if every way of corrupting the
     *  permutation is REJECTED rather than half-applied: an index used twice would duplicate an item, an
     *  occupied slot left unplaced would delete one, and an index pointing at an empty slot or past the end
     *  of the arrangement is meaningless. Each of those is constructed here and required to fail. */
        for (Kit k : Kit.values()) {
            ItemStack[] arrangement = defaultArrangement(k);
            int occupied = 0, total = 0;
            for (ItemStack item : arrangement) if (real(item)) { occupied++; total += item.getAmount(); }
            int expected = 0;
            List<ItemStack> layoutHotbar = kitHotbar(k);
            for (int i = 0; i < layoutHotbar.size() && i < 9; i++) expected += layoutHotbar.get(i).getAmount();
            for (ItemStack extra : kitExtra(k)) expected += extra.getAmount();
            /** The default arrangement has to hold the whole kit -- if addItem ever ran out of room, the
             *  layout would be a permutation of something smaller than the kit it claims to describe. */
            if (occupied == 0 || total != expected) return false;

            int[] identity = identityLayout(arrangement);
            if (!layoutMatches(identity, arrangement)) return false;
            if (layoutMatches(null, arrangement)) return false;

            /** A real rearrangement -- everything pushed to the far end of the inventory -- still balances. */
            int[] shuffled = new int[LAYOUT_SLOTS];
            java.util.Arrays.fill(shuffled, -1);
            int cursor = LAYOUT_SLOTS - 1;
            for (int index = 0; index < LAYOUT_SLOTS; index++) if (real(arrangement[index])) shuffled[cursor--] = index;
            if (!layoutMatches(shuffled, arrangement)) return false;
            int moved = 0;
            for (int slot = 0; slot < LAYOUT_SLOTS; slot++) if (shuffled[slot] >= 0) moved += arrangement[shuffled[slot]].getAmount();
            if (moved != total) return false;

            int first = -1, second = -1, empty = -1;
            for (int slot = 0; slot < LAYOUT_SLOTS; slot++) {
                if (identity[slot] >= 0) { if (first < 0) first = slot; else if (second < 0) second = slot; }
                else if (empty < 0) empty = slot;
            }
            if (first < 0 || second < 0 || empty < 0) return false;
            int[] duplicated = identity.clone(); duplicated[second] = duplicated[first];
            if (layoutMatches(duplicated, arrangement)) return false;
            int[] deleted = identity.clone(); deleted[first] = -1;
            if (layoutMatches(deleted, arrangement)) return false;
            int[] past = identity.clone(); past[first] = LAYOUT_SLOTS + 5;
            if (layoutMatches(past, arrangement)) return false;
            int[] atNothing = identity.clone(); atNothing[first] = empty;
            if (layoutMatches(atNothing, arrangement)) return false;
        }

        /** The wager box's three regions must not overlap, and the boundary the clicks are locked at has to
         *  be the same one every "what did this player stage" scan stops at. If those ever drifted apart,
         *  the opponent's display copies would start being collected as staged items. */
        if (WAGER_STAGE != 27 || WAGER_VIEW != 36 || WAGER_VIEW < WAGER_STAGE || LAYOUT_SLOTS != 36) return false;
        for (int slot = 0; slot < LAYOUT_SLOTS; slot++) if (editorSlot(gameSlot(slot)) != slot) return false;

        double winning = 300, losing = 700, payout = 0;
        for (double stake : new double[]{100, 200}) payout += stake + losing * (stake / winning);
        if (Math.abs(payout - (winning + losing)) >= 0.01) return false;

        /** The three-stage setup walks forward Kit -> Map -> Final Options and back again, and never runs
         *  off either end: Back from the first stage is a cancel (handled separately) and Confirm on the last
         *  is the start, so next()/previous() must saturate rather than wrap. */
        if (Stage.KIT.next() != Stage.MAP || Stage.MAP.next() != Stage.OPTIONS || Stage.OPTIONS.next() != Stage.OPTIONS) return false;
        if (Stage.OPTIONS.previous() != Stage.MAP || Stage.MAP.previous() != Stage.KIT || Stage.KIT.previous() != Stage.KIT) return false;
        if (Stage.values().length != 3 || Stage.KIT.ordinal() != 0 || Stage.OPTIONS.ordinal() != 2) return false;

        /** Visibility Effects default ON, and both of its effects are real. */
        if (PotionEffectType.GLOWING == null || PotionEffectType.NIGHT_VISION == null) return false;
        Duel probe = new Duel(-1, 0, "a", "b");
        if (!probe.visibility || probe.stage != Stage.KIT || probe.mapKey != null || probe.instance != null || probe.starting) return false;

        /** A setup timeout that could fire while an arena is being cloned would strand the instance. */
        if (plugin.getConfig().getLong("arena.setup-timeout-seconds", 180) < 30) return false;

        /** All three stage screens must be recognised as setup screens, or closing one of them would not
         *  cancel -- which is the entire behaviour the close handler exists for. */
        if (!isSetupScreen("setup") || !isSetupScreen("map") || !isSetupScreen("options")) return false;
        if (isSetupScreen("wagerbox") || isSetupScreen("wagerview") || isSetupScreen("ready")) return false;

        /** The screen-transition guard. Bukkit fires InventoryCloseEvent for the old screen when the plugin
         *  opens the new one, exactly as it does when a player presses ESC, so the guard is the only thing
         *  standing between "advance to the map stage" and "cancel the duel". It must suppress while a swap
         *  is in progress, nest correctly, and -- critically -- clear itself even when the swap throws,
         *  because a stuck flag would make manual closes stop cancelling for the rest of the session. */
        String guardId = "__selftest_transition";
        screenTransition.remove(guardId);
        if (screenTransition.contains(guardId)) return false;
        boolean[] sawInner = {false, false};
        transition(guardId, () -> {
            sawInner[0] = screenTransition.contains(guardId);
            transition(guardId, () -> sawInner[1] = screenTransition.contains(guardId));
            /** A nested transition must not release the flag when it returns. */
            sawInner[0] &= screenTransition.contains(guardId);
        });
        if (!sawInner[0] || !sawInner[1] || screenTransition.contains(guardId)) return false;
        try { transition(guardId, () -> { throw new IllegalStateException("boom"); }); }
        catch (IllegalStateException expected) { /* the point is what happens next */ }
        return !screenTransition.contains(guardId);
    }
}
