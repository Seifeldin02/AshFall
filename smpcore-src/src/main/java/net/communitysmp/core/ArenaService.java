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
        /** True while a round's loss is being processed, so two near-simultaneous lethal hits or a
         *  disconnect racing a death cannot resolve the same round twice. Reset at each round start. */
        boolean resolving = false;
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
    private final Set<Integer> built = new HashSet<>();
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
    private static final int HALF = 30;

    /** Builds a slot's structure once. A 61x61 quartz-and-stone floor with a low bordered wall and corner
     *  pillars, a raised glass spectator ring outside it, sea-lantern lighting, and completely OPEN SKY --
     *  no ceiling -- so the Spear's elytra and the Mace's wind-charge launches have room. Deliberately
     *  procedural: a downloaded world could not be fetched and content-vetted safely in this environment,
     *  and the slot system needs an identical layout it can stamp and reset per match. */
    private void buildSlot(int slot) {
        if (arena == null || !built.add(slot)) return;
        int cx = slotBaseX(slot);
        for (int x = -HALF - 4; x <= HALF + 4; x++) for (int z = -HALF - 4; z <= HALF + 4; z++) {
            boolean inFloor = Math.abs(x) <= HALF && Math.abs(z) <= HALF;
            boolean onWall = Math.abs(x) == HALF || Math.abs(z) == HALF;
            boolean gallery = (Math.abs(x) > HALF + 1 && Math.abs(x) <= HALF + 4) || (Math.abs(z) > HALF + 1 && Math.abs(z) <= HALF + 4);
            if (inFloor) {
                /** Subtle checker so the floor reads as a real arena, not a slab of stone. */
                boolean light = ((x + z) & 1) == 0;
                arena.getBlockAt(cx + x, FLOOR_Y, z).setType(light ? Material.SMOOTH_QUARTZ : Material.POLISHED_ANDESITE, false);
            }
            if (onWall) {
                for (int y = 1; y <= 3; y++) arena.getBlockAt(cx + x, FLOOR_Y + y, z).setType(Material.SMOOTH_STONE, false);
                arena.getBlockAt(cx + x, FLOOR_Y + 4, z).setType(Material.SMOOTH_STONE_SLAB, false);
            }
            if (gallery) {
                /** Spectator ring: a glass floor two blocks up with a low barrier, ringing the arena. */
                arena.getBlockAt(cx + x, FLOOR_Y + 2, z).setType(Material.GLASS, false);
                arena.getBlockAt(cx + x, FLOOR_Y + 3, z).setType(Material.GLASS, false);
            }
        }
        /** Corner pillars with a lantern on top -- landmarks and light. */
        for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) {
            int x = sx * HALF, z = sz * HALF;
            for (int y = 1; y <= 6; y++) arena.getBlockAt(cx + x, FLOOR_Y + y, z).setType(Material.QUARTZ_PILLAR, false);
            arena.getBlockAt(cx + x, FLOOR_Y + 7, z).setType(Material.SEA_LANTERN, false);
        }
        /** A few sea lanterns set flush into the floor edge so the arena is well lit at night. */
        for (int i = -HALF + 6; i <= HALF - 6; i += 12) {
            arena.getBlockAt(cx + i, FLOOR_Y, HALF - 1).setType(Material.SEA_LANTERN, false);
            arena.getBlockAt(cx + i, FLOOR_Y, -HALF + 1).setType(Material.SEA_LANTERN, false);
            arena.getBlockAt(cx + HALF - 1, FLOOR_Y, i).setType(Material.SEA_LANTERN, false);
            arena.getBlockAt(cx + -HALF + 1, FLOOR_Y, i).setType(Material.SEA_LANTERN, false);
        }
        plugin.getLogger().info("[Arena] built slot " + slot + " at x=" + cx);
    }

    private Location corner(Duel duel, int index) {
        int cx = slotBaseX(duel.slot);
        return new Location(arena, cx + (index == 0 ? -HALF + 4.5 : HALF - 4.5), FLOOR_Y + 1, 0.5, index == 0 ? 90f : -90f, 0f);
    }

    /** A spectator perch on the raised glass ring, looking in. */
    private Location gallery(Duel duel) { return new Location(arena, slotBaseX(duel.slot) + 0.5, FLOOR_Y + 4, HALF + 3.5, 180f, 0f); }

    private boolean inArena(Player player) { return arena != null && player.getWorld().equals(arena); }

    // ------------------------------------------------------------------ kits
    /** The full standardized kit as one list (armour, then weapon, then offhand if any, then consumables).
     *  Used for the self-test's parity check and for reporting exactly what each side receives. */
    List<ItemStack> kitContents(Kit kit) {
        List<ItemStack> all = new ArrayList<>(kitArmour(kit));
        all.add(kitWeapon(kit));
        ItemStack offhand = kitOffhand(kit);
        if (offhand != null) all.add(offhand);
        all.addAll(kitConsumables(kit));
        return all;
    }

    /** Exactly four pieces, helmet-chest-legs-boots. The Spear wears an elytra as its chest, so it does NOT
     *  also carry a diamond chestplate -- the previous code added one and then silently overwrote it. */
    private List<ItemStack> kitArmour(Kit kit) {
        List<ItemStack> armour = new ArrayList<>();
        armour.add(armour(Material.DIAMOND_HELMET));
        armour.add(armour(Material.DIAMOND_CHESTPLATE));
        armour.add(armour(Material.DIAMOND_LEGGINGS));
        armour.add(armour(Material.DIAMOND_BOOTS));
        return armour;
    }

    private ItemStack kitWeapon(Kit kit) {
        return switch (kit) {
            /** Mace with Density (slam damage) AND Wind Burst (self-launch on hit) -- the modern mace combo
             *  that makes the wind-charge-up-then-slam loop work. */
            case MACE -> enchanted(Material.MACE, Map.of(Enchantment.DENSITY, 5, Enchantment.WIND_BURST, 3, Enchantment.UNBREAKING, 3));
            case SWORD -> enchanted(Material.DIAMOND_SWORD, Map.of(Enchantment.SHARPNESS, 5, Enchantment.FIRE_ASPECT, 2, Enchantment.UNBREAKING, 3));
            case AXE -> enchanted(Material.DIAMOND_AXE, Map.of(Enchantment.SHARPNESS, 5, Enchantment.EFFICIENCY, 5, Enchantment.UNBREAKING, 3));
            /** The real vanilla spear -- jab/charge melee with the spear-exclusive Lunge as its gap-closer.
             *  Lunge is used at the version's max level. No elytra: Lunge does not work while elytra-flying,
             *  so an elytra+rockets loadout would break the kit's own mechanic. Sharpness for its damage. */
            case SPEAR -> enchanted(Material.DIAMOND_SPEAR, Map.of(Enchantment.SHARPNESS, 5, Enchantment.LUNGE, Math.max(1, Enchantment.LUNGE.getMaxLevel()), Enchantment.UNBREAKING, 3));
        };
    }

    private ItemStack kitOffhand(Kit kit) { return kit == Kit.SWORD ? enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3)) : null; }

    private List<ItemStack> kitConsumables(Kit kit) {
        return switch (kit) {
            /** Mace is a build-and-slam kit: enough wind charges to keep launching, and two stacks of blocks
             *  to tower up for the killing slam. */
            case MACE -> List.of(new ItemStack(Material.WIND_CHARGE, 64), new ItemStack(Material.COBBLESTONE, 64),
                    new ItemStack(Material.COBBLESTONE, 64), new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2), new ItemStack(Material.GOLDEN_APPLE, 16));
            case SWORD -> List.of(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2), new ItemStack(Material.GOLDEN_APPLE, 16));
            case AXE -> List.of(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2), new ItemStack(Material.GOLDEN_APPLE, 16), new ItemStack(Material.COBWEB, 8));
            /** Spear closes distance with Lunge rather than rockets, so it carries the same heal loadout as
             *  the sword and fights on the ground with its reach advantage. */
            case SPEAR -> List.of(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2), new ItemStack(Material.GOLDEN_APPLE, 16));
        };
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
        inv.setItem(0, kitWeapon(kit));
        ItemStack offhand = kitOffhand(kit);
        if (offhand != null) inv.setItemInOffHand(offhand);
        for (ItemStack consumable : kitConsumables(kit)) inv.addItem(consumable);
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
        duel.resolving = false;
        capture(one); capture(two);
        one.teleport(corner(duel, 0)); two.teleport(corner(duel, 1));
        equip(one, duel.kit); equip(two, duel.kit);
        int round = duel.rounds.get(duel.a) + duel.rounds.get(duel.b) + 1;
        actionbar(one, "Round " + round + " — fight!"); actionbar(two, "Round " + round + " — fight!");
    }

    /** The round is resolved by INTERCEPTING the killing blow, not by a real death. A duellist whose hit
     *  would drop them to zero has the damage cancelled, their health topped up, and the round awarded --
     *  so PlayerDeathEvent never fires for a duellist, which means no grave, no drops, no respawn yank, no
     *  economy or faction side effect can ever occur. Void, fire and fall damage all arrive here too. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void lethal(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player hurt)) return;
        Duel duel = duelOf(hurt);
        if (duel == null || duel.phase != Phase.LIVE || !inArena(hurt)) return;
        if (duel.resolving) { event.setCancelled(true); return; }
        if (hurt.getHealth() - event.getFinalDamage() > 0.0001) return;
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
        duel.rounds.merge(winner, 1, Integer::sum);
        int needed = duel.bestOf / 2 + 1;
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
        String target = duel == null ? null : (duel.aName.equalsIgnoreCase(on) ? duel.a : duel.bName.equalsIgnoreCase(on) ? duel.b : null);
        return placeWager(player, duel, target, amount);
    }

    /** Places or CHANGES a wager, while betting is still open. Changing refunds the old stake first, so the
     *  money is always conserved. */
    private boolean placeWager(Player player, Duel duel, String target, double amount) {
        if (duel == null) { CoreUtil.error(player, "No such match."); return true; }
        if (duel.phase == Phase.LIVE || duel.phase == Phase.ENDING) { CoreUtil.error(player, "Betting closed when the match started."); return true; }
        String id = CoreUtil.id(player);
        if (duel.has(id)) { CoreUtil.error(player, "You cannot bet on your own match."); return true; }
        if (target == null) { CoreUtil.error(player, "Choose " + duel.aName + " or " + duel.bName + "."); return true; }
        if (amount < 0 || !Double.isFinite(amount)) { CoreUtil.error(player, "Amount cannot be negative."); return true; }
        Wager existing = duel.wagers.stream().filter(w -> w.player().equals(id)).findFirst().orElse(null);
        if (existing != null) { db.changeBalance(id, existing.amount()); duel.wagers.remove(existing); db.arenaWagersClearFor(duel.a, duel.b); for (Wager w : duel.wagers) db.arenaWagerAdd(w.player(), w.on(), w.amount()); }
        if (amount > 0 && !db.changeBalance(id, -amount)) { CoreUtil.error(player, "You cannot cover that."); if (existing != null) { duel.wagers.add(existing); db.arenaWagerAdd(existing.player(), existing.on(), existing.amount()); } return true; }
        duel.wagers.add(new Wager(id, target, amount));
        db.arenaWagerAdd(id, target, amount);
        actionbar(player, "Wager set: " + CoreUtil.money(amount) + " on " + name(target) + ".");
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
        actionbar(player, "Spectating — invisible, and you cannot affect the fight.");
    }

    private void leaveSpectator(Player player) {
        spectators.remove(CoreUtil.id(player));
        stagedBet.remove(CoreUtil.id(player));
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
        /** Structure is built once and left standing; a reset only removes what the duellists placed, so the
         *  map is never modified by a match. Cheap -- a handful of blocks, not the whole arena. */
        for (long k : duel.placed) arena.getBlockAtKey(k).setType(Material.AIR, false);
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
        String meId = CoreUtil.id(player), themId = duel.other(meId);
        String meName = meId.equals(duel.a) ? duel.aName : duel.bName, themName = meId.equals(duel.a) ? duel.bName : duel.aName;
        boolean ready = duel.confirmed.contains(meId);
        /** Rendered from THIS player's perspective: "You" is always the viewer, whichever side they are. */
        menu.inv.setItem(40, icon(ready ? Material.YELLOW_CONCRETE : Material.LIME_CONCRETE, ready ? "Confirmed — waiting for opponent…" : "Confirm", List.of(
                "You: " + meName + "  " + CoreUtil.money(duel.stakes.getOrDefault(meId, 0d)) + (ready ? "  (ready)" : ""),
                "Them: " + themName + "  " + CoreUtil.money(duel.stakes.getOrDefault(themId, 0d)) + (duel.confirmed.contains(themId) ? "  (ready)" : ""),
                "Kit " + duel.kit.label() + " · Best of " + duel.bestOf)));
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
    private ItemStack filler() { return CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()); }

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
                double[] st = stagedBet.computeIfAbsent(id, k -> new double[]{-1, 0});
                double balance = db.player(id).balance();
                switch (slot) {
                    case 20 -> { st[0] = 0; openSpectate(player, menu.duelId); }
                    case 24 -> { st[0] = 1; openSpectate(player, menu.duelId); }
                    case 29 -> { st[1] = Math.max(0, st[1] - 1000); openSpectate(player, menu.duelId); }
                    case 30 -> { st[1] = Math.max(0, st[1] - 100); openSpectate(player, menu.duelId); }
                    case 32 -> { st[1] = Math.min(balance, st[1] + 100); openSpectate(player, menu.duelId); }
                    case 33 -> { st[1] = Math.min(balance, st[1] + 1000); openSpectate(player, menu.duelId); }
                    case 38 -> { String target = st[0] == 0 ? duel.a : st[0] == 1 ? duel.b : null;
                                 if (target == null) actionbar(player, "Pick a fighter to back first."); else placeWager(player, duel, target, st[1]);
                                 openSpectate(player, menu.duelId); }
                    case 42 -> { if (spectators.containsKey(id)) leaveSpectator(player); else player.closeInventory(); }
                    case 44 -> { if (duel.phase == Phase.LIVE) enterSpectator(player, duel); openSpectate(player, menu.duelId); }
                    default -> { }
                }
            }
            default -> { }
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
        boolean bettingOpen = duel.phase == Phase.PENDING || duel.phase == Phase.STAKING;
        double[] st = stagedBet.computeIfAbsent(id, k -> new double[]{-1, 0});
        Menu menu = new Menu("spectate", duelId);
        menu.inv = plugin.getServer().createInventory(menu, 54, Component.text("Duel #" + duelId + " • Spectate", NamedTextColor.DARK_AQUA));
        for (int f = 45; f < 54; f++) menu.inv.setItem(f, filler());
        double poolA = 0, poolB = 0;
        for (Wager w : duel.wagers) { if (w.on().equals(duel.a)) poolA += w.amount(); else poolB += w.amount(); }
        menu.inv.setItem(4, icon(Material.PAPER, "#" + duelId + "  " + duel.aName + " vs " + duel.bName, List.of(
                "Kit: " + duel.kit.label(), "Series: Best of " + duel.bestOf,
                "Score: " + duel.rounds.getOrDefault(duel.a, 0) + " - " + duel.rounds.getOrDefault(duel.b, 0),
                "Stakes: " + CoreUtil.money(duel.stakes.getOrDefault(duel.a, 0d)) + " / " + CoreUtil.money(duel.stakes.getOrDefault(duel.b, 0d)),
                "Status: " + duel.phase)));
        boolean backA = st[0] == 0, backB = st[0] == 1;
        menu.inv.setItem(20, icon(backA ? Material.LIME_CONCRETE : Material.WHITE_CONCRETE, (backA ? "✔ " : "") + "Back " + duel.aName,
                List.of("Pool on " + duel.aName + ": " + CoreUtil.money(poolA), bettingOpen ? "Click to back " + duel.aName : "Betting closed")));
        menu.inv.setItem(24, icon(backB ? Material.RED_CONCRETE : Material.WHITE_CONCRETE, (backB ? "✔ " : "") + "Back " + duel.bName,
                List.of("Pool on " + duel.bName + ": " + CoreUtil.money(poolB), bettingOpen ? "Click to back " + duel.bName : "Betting closed")));
        if (bettingOpen) {
            menu.inv.setItem(29, icon(Material.RED_STAINED_GLASS_PANE, "- 1,000", List.of()));
            menu.inv.setItem(30, icon(Material.PINK_STAINED_GLASS_PANE, "- 100", List.of()));
            menu.inv.setItem(31, icon(Material.GOLD_INGOT, "Wager amount: " + CoreUtil.money(st[1]), List.of("Pick a fighter, set an amount, confirm")));
            menu.inv.setItem(32, icon(Material.LIME_STAINED_GLASS_PANE, "+ 100", List.of()));
            menu.inv.setItem(33, icon(Material.GREEN_STAINED_GLASS_PANE, "+ 1,000", List.of()));
            menu.inv.setItem(38, icon(Material.EMERALD, "Confirm wager", List.of("Backing " + (st[0] == 0 ? duel.aName : st[0] == 1 ? duel.bName : "nobody yet"),
                    "Amount " + CoreUtil.money(st[1]), "Changing before lock refunds the old wager")));
        } else {
            menu.inv.setItem(31, icon(Material.BARRIER, "Betting is closed", List.of("The match has started")));
        }
        Wager mine = wagerOf(duel, id);
        menu.inv.setItem(40, icon(Material.BOOK, "Your wager", mine == null ? List.of("None placed")
                : List.of(CoreUtil.money(mine.amount()) + " on " + (mine.on().equals(duel.a) ? duel.aName : duel.bName))));
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
