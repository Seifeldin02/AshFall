package net.communitysmp.core;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Throwaway void worlds for limited events.
 *
 *  The rule the owner asked for is absolute in BOTH directions: nobody takes their own belongings in, and
 *  nobody takes anything they found inside back out. That is enforced by capture-and-clear on the way in and
 *  clear-and-restore on the way out, which is the same shape the duel arena already uses and for the same
 *  reason -- it is the only model where a crash cannot merge the two inventories.
 *
 *  The capture lives in the database, not in memory, so a restart mid-event does not strand anybody's real
 *  inventory. A player who logs out inside a void world and comes back is restored on join, even if the
 *  world was deleted while they were away.
 *
 *  Deliberately NOT a game mode, a mini-game framework or a scoreboard. It creates an empty world, moves
 *  people in and out safely, and gets out of the way. */
final class VoidWorldService implements Listener {

    /** Prefix on every world this service owns, so a stray /ashfall voidworld delete can never be pointed at
     *  the overworld, a duel instance, or anything else that matters. */
    private static final String PREFIX = "ashfall_void_";
    private static final String STATE = "voidworld:";

    private final SMPCore plugin;
    private final Database db;

    VoidWorldService(SMPCore plugin) { this.plugin = plugin; this.db = plugin.db(); }

    // ------------------------------------------------------------------ the world itself

    /** A generator that produces nothing at all. No terrain, no biome features, no structures, no ores --
     *  an event world should start as a blank sheet and stay one until somebody builds on it. */
    private static final class VoidGenerator extends ChunkGenerator {
        @Override public void generateNoise(WorldInfo info, Random random, int x, int z, ChunkData data) { }
        @Override public void generateSurface(WorldInfo info, Random random, int x, int z, ChunkData data) { }
        @Override public void generateBedrock(WorldInfo info, Random random, int x, int z, ChunkData data) { }
        @Override public void generateCaves(WorldInfo info, Random random, int x, int z, ChunkData data) { }
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
        @Override public List<BlockPopulator> getDefaultPopulators(World world) { return List.of(); }
    }

    String worldName(String label) { return PREFIX + label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", ""); }

    boolean isVoidWorld(World world) { return world != null && world.getName().startsWith(PREFIX); }

    List<String> labels() {
        List<String> out = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) if (isVoidWorld(world)) out.add(world.getName().substring(PREFIX.length()));
        return out;
    }

    World find(String label) {
        World world = Bukkit.getWorld(worldName(label));
        return isVoidWorld(world) ? world : null;
    }

    /** Creates the world and lays a small stone platform at the spawn, because a void world with no floor
     *  drops the first person who enters it straight out of the bottom. */
    World create(String label) {
        String name = worldName(label);
        if (name.equals(PREFIX)) return null;
        World existing = Bukkit.getWorld(name);
        if (existing != null) return existing;
        World world = new WorldCreator(name)
                .generator(new VoidGenerator())
                .type(WorldType.FLAT)
                .generateStructures(false)
                .createWorld();
        if (world == null) return null;
        world.setSpawnLocation(0, 65, 0);
        world.setKeepSpawnInMemory(true);
        world.setAutoSave(true);
        world.setTime(6000);
        world.setStorm(false);
        world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++)
                world.getBlockAt(x, 64, z).setType(Material.SMOOTH_STONE, false);
        return world;
    }

    // ------------------------------------------------------------------ in and out

    boolean inside(Player player) { return isVoidWorld(player.getWorld()); }

    /** Captured state exists only while somebody is inside. Its presence IS the "this player is in an event"
     *  flag, which is what makes the restore idempotent and crash-safe. */
    private boolean hasCapture(String id) { String value = db.state(STATE + id); return value != null && !value.isBlank(); }

    String enter(Player player, String label) {
        World world = find(label);
        if (world == null) return "No void world called '" + label + "'. Create it first.";
        if (inside(player)) return "You are already in a void world.";
        if (hasCapture(CoreUtil.id(player))) return "You already have belongings held by a void world; use exit first.";
        capture(player);
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        player.setLevel(0);
        player.setExp(0);
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.teleport(world.getSpawnLocation().clone().add(.5, 1, .5));
        CoreUtil.msg(player, "You entered the '" + label + "' event world. Nothing came in with you, and nothing leaves with you.");
        return null;
    }

    /** The way out. Everything found inside is destroyed, which is the whole point -- a temporary world must
     *  not be an item source. */
    String exit(Player player) {
        String id = CoreUtil.id(player);
        if (!hasCapture(id)) return inside(player) ? "You have no held belongings; nothing to restore." : "You are not in a void world.";
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
        restore(player);
        CoreUtil.msg(player, "Returned. Your belongings are exactly as you left them.");
        return null;
    }

    /** Everyone currently inside, moved out. Used before a delete and at shutdown. */
    void evacuate(World world, String reason) {
        for (Player player : new ArrayList<>(world.getPlayers())) {
            exit(player);
            if (reason != null) CoreUtil.msg(player, reason);
        }
    }

    String delete(String label) {
        World world = find(label);
        if (world == null) return "No void world called '" + label + "'.";
        evacuate(world, "The event world was closed.");
        String name = world.getName();
        File folder = world.getWorldFolder();
        if (!Bukkit.unloadWorld(world, false)) return "Could not unload " + name + "; try again once it is empty.";
        deleteTree(folder);
        return null;
    }

    private void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    // ------------------------------------------------------------------ capture / restore

    private void capture(Player player) {
        String id = CoreUtil.id(player);
        ItemStack[] contents = player.getInventory().getContents(), armour = player.getInventory().getArmorContents();
        ItemStack[] all = new ItemStack[contents.length + armour.length + 1];
        System.arraycopy(contents, 0, all, 0, contents.length);
        System.arraycopy(armour, 0, all, contents.length, armour.length);
        all[all.length - 1] = player.getInventory().getItemInOffHand();
        Location at = player.getLocation();
        StringBuilder header = new StringBuilder();
        header.append(at.getWorld().getName()).append('|').append(at.getX()).append('|').append(at.getY()).append('|')
                .append(at.getZ()).append('|').append(at.getYaw()).append('|').append(at.getPitch()).append('|')
                .append(player.getLevel()).append('|').append(player.getExp()).append('|')
                .append(player.getHealth()).append('|').append(player.getFoodLevel()).append('|')
                .append(player.getGameMode().name()).append('|')
                .append(java.util.Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(all)));
        db.state(STATE + id, header.toString());
    }

    private void restore(Player player) {
        String id = CoreUtil.id(player);
        String raw = db.state(STATE + id);
        if (raw == null || raw.isBlank()) return;
        String[] parts = raw.split("\\|", 12);
        try {
            if (parts.length >= 12) {
                ItemStack[] all = ItemStack.deserializeItemsFromBytes(java.util.Base64.getDecoder().decode(parts[11]));
                int size = player.getInventory().getSize();
                ItemStack[] main = new ItemStack[size], armour = new ItemStack[4];
                System.arraycopy(all, 0, main, 0, Math.min(size, all.length));
                if (all.length >= size + 4) System.arraycopy(all, size, armour, 0, 4);
                player.getInventory().setContents(main);
                player.getInventory().setArmorContents(armour);
                if (all.length > size + 4 && all[size + 4] != null) player.getInventory().setItemInOffHand(all[size + 4]);
            }
            World world = Bukkit.getWorld(parts[0]);
            /** If the world they came from is gone, spawn is the only honest fallback -- but their items are
             *  restored either way, which is the half that actually matters. */
            Location back = world != null
                    ? new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                        Float.parseFloat(parts[4]), Float.parseFloat(parts[5]))
                    : Bukkit.getWorlds().getFirst().getSpawnLocation();
            player.teleport(back);
            player.setLevel(Integer.parseInt(parts[6]));
            player.setExp(Float.parseFloat(parts[7]));
            player.setHealth(Math.min(Double.parseDouble(parts[8]), player.getAttribute(Attribute.MAX_HEALTH).getValue()));
            player.setFoodLevel(Integer.parseInt(parts[9]));
            try { player.setGameMode(GameMode.valueOf(parts[10])); } catch (IllegalArgumentException ignored) { player.setGameMode(GameMode.SURVIVAL); }
        } catch (RuntimeException error) {
            plugin.getLogger().warning("[VoidWorld] could not fully restore " + id + ": " + error);
        } finally {
            /** Cleared LAST and unconditionally: a capture left behind would block every future entry and,
             *  worse, could be restored a second time. */
            db.state(STATE + id, "");
            player.updateInventory();
        }
    }

    // ------------------------------------------------------------------ lifecycle safety

    /** Logging out inside an event world must not strand a real inventory. Restored on the next join, even
     *  if the world was deleted in the meantime. */
    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!hasCapture(CoreUtil.id(player))) return;
        if (inside(player)) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) { restore(player); CoreUtil.msg(player, "Your belongings were restored after the event world closed."); }
        }, 20L);
    }

    /** A quit inside the world leaves the capture in place on purpose -- join() is what resolves it. Nothing
     *  is done here beyond making sure they do not carry event items in their inventory across the logout. */
    @EventHandler public void quit(PlayerQuitEvent event) {
        if (inside(event.getPlayer())) {
            event.getPlayer().getInventory().clear();
            event.getPlayer().getInventory().setArmorContents(null);
        }
    }

    void shutdown() {
        for (World world : Bukkit.getWorlds()) if (isVoidWorld(world)) evacuate(world, "The server is restarting.");
    }

    // ------------------------------------------------------------------ self test

    boolean selfTest() {
        if (!worldName("Test Event 1").equals(PREFIX + "testevent1")) return false;
        if (!worldName("../../etc").equals(PREFIX + "etc")) return false;
        if (worldName("!!!").equals(PREFIX) && create("!!!") != null) return false;
        return isVoidWorld(null) == false;
    }
}
