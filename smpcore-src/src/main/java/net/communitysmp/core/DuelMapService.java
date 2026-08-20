package net.communitysmp.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.GameRule;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Duel maps: one private template world per map, and a disposable clone of that template for every match.
 *
 *  A template is never entered by matchmaking -- it exists so an admin can build, and so an instance has
 *  something authoritative to copy. Every duel gets its own instance world cloned from the template as it
 *  stands at that moment, which is what gives concurrent duels on the same map complete isolation: terrain,
 *  chests and mobs in one match cannot be observed from another, and nothing a duellist breaks survives the
 *  match. Editing a template therefore affects future matches only; instances already running keep the copy
 *  they started with.
 *
 *  Instances are deleted when the duel ends. Any that survive a crash are recognisable by their name prefix
 *  and removed at startup, before matchmaking can hand one out. */
class DuelMapService implements org.bukkit.event.Listener {

    /** How the arena reacts to a duellist breaking a block. */
    enum BreakRule {
        /** Only blocks placed during this match may be broken. Original terrain, and any explosion damage to
         *  it, is refused. Used by the two built flat arenas. */
        PLACED_ONLY,
        /** Terrain inside the playable bounds is fully breakable, explosions included. Boundary barriers and
         *  control infrastructure are still protected. Used by the imported maps. */
        FULL
    }

    record DuelMap(String key, String name, BreakRule rule,
                   double p1x, double p1y, double p1z,
                   double p2x, double p2y, double p2z,
                   Double specx, Double specy, Double specz) {

        String templateWorld() { return "duel_tpl_" + key; }

        /** The two duellists always face each other: the yaw is derived from the spawn pair rather than
         *  stored, so moving a spawn point can never leave somebody staring at a wall. */
        float yawP1() { return facing(p1x, p1z, p2x, p2z); }
        float yawP2() { return facing(p2x, p2z, p1x, p1z); }

        private static float facing(double fromX, double fromZ, double toX, double toZ) {
            double dx = toX - fromX, dz = toZ - fromZ;
            return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        }

        /** Spectators sit above the midpoint of the two spawns unless an admin pinned a spot. */
        double[] spectator(int fallbackHeight) {
            if (specx != null && specy != null && specz != null) return new double[]{specx, specy, specz};
            return new double[]{(p1x + p2x) / 2.0, Math.max(p1y, p2y) + fallbackHeight, (p1z + p2z) / 2.0};
        }
    }

    private final SMPCore plugin;
    private final Map<String, DuelMap> maps = new LinkedHashMap<>();
    /** instance world name -> map key, for the worlds this process created. */
    private final Map<String, String> liveInstances = new LinkedHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    static final String INSTANCE_PREFIX = "duel_inst_";

    DuelMapService(SMPCore plugin) {
        this.plugin = plugin;
        reload();
        Bukkit.getScheduler().runTask(plugin, this::cleanupOrphans);
        /** Windows keeps the region-file handles of an unloaded world mapped until the JVM releases them,
         *  so a folder is often still undeletable minutes after its match ended. Rather than leak instances
         *  for the rest of the session, retry every minute: any instance folder that is neither a loaded
         *  world nor a live match is fair game, and one of the attempts eventually succeeds. */
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweepDetached, 1200L, 1200L);
    }

    /** Deletes instance folders that no longer belong to a loaded world or a live match. */
    int sweepDetached() {
        File container = Bukkit.getWorldContainer();
        File[] children = container.listFiles();
        if (children == null) return 0;
        int removed = 0;
        for (File child : children) {
            if (!child.isDirectory() || !child.getName().startsWith(INSTANCE_PREFIX)) continue;
            if (liveInstances.containsKey(child.getName())) continue;
            if (Bukkit.getWorld(child.getName()) != null) continue;
            deleteQuietly(child);
            if (!child.exists()) removed++;
        }
        if (removed > 0) plugin.getLogger().info("[duel-maps] swept " + removed + " detached instance folder(s)");
        return removed;
    }

    // ------------------------------------------------------------------ registry

    void reload() {
        maps.clear();
        org.bukkit.configuration.ConfigurationSection root = plugin.getConfig().getConfigurationSection("duel-maps");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            org.bukkit.configuration.ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            List<Double> p1 = s.getDoubleList("p1"), p2 = s.getDoubleList("p2"), spec = s.getDoubleList("spectator");
            if (p1.size() < 3 || p2.size() < 3) {
                plugin.getLogger().warning("[duel-maps] " + key + " is missing p1/p2 spawns; skipped.");
                continue;
            }
            BreakRule rule = "placed-only".equalsIgnoreCase(s.getString("break-rule", "full"))
                    ? BreakRule.PLACED_ONLY : BreakRule.FULL;
            maps.put(key, new DuelMap(key, s.getString("name", key), rule,
                    p1.get(0), p1.get(1), p1.get(2), p2.get(0), p2.get(1), p2.get(2),
                    spec.size() >= 3 ? spec.get(0) : null,
                    spec.size() >= 3 ? spec.get(1) : null,
                    spec.size() >= 3 ? spec.get(2) : null));
        }
    }

    Collection<DuelMap> maps() { return maps.values(); }
    DuelMap map(String key) { return key == null ? null : maps.get(key.toLowerCase(Locale.ROOT)); }

    // ------------------------------------------------------------------ template worlds

    /** Loads (creating if absent) the private template world for a map. Templates are void worlds with mob
     *  spawning off; they are kept loaded only while an admin is editing or an instance is being cloned. */
    World template(DuelMap map) {
        World world = Bukkit.getWorld(map.templateWorld());
        if (world != null) { loadPlayArea(world, map); return world; }
        world = new WorldCreator(map.templateWorld())
                .generator(new ArenaService.VoidGenerator())
                .type(WorldType.FLAT)
                .environment(World.Environment.NORMAL)
                .createWorld();
        if (world != null) { applyWorldRules(world, true); loadPlayArea(world, map); }
        return world;
    }


    /** The chunks that make up the arena, centred on the spawn pair. A void template has nothing loaded by
     *  default, so without this an admin's /setblock silently fails and a clone copies empty region files. */
    void loadPlayArea(World world, DuelMap map) {
        int radius = Math.max(1, plugin.getConfig().getInt("duel-loot.load-radius-chunks", 12));
        int cx = (int) Math.floor((map.p1x() + map.p2x()) / 2.0) >> 4, cz = (int) Math.floor((map.p1z() + map.p2z()) / 2.0) >> 4;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) world.getChunkAt(cx + dx, cz + dz).load(true);
    }

    /** Writes a template's chunks to disk so a clone copies the current state rather than a stale one. */
    boolean saveTemplate(DuelMap map) {
        World world = Bukkit.getWorld(map.templateWorld());
        if (world == null) return false;
        flushTemplate(world, map);
        return true;
    }

    // ------------------------------------------------------------------ instances

    /** Clones the map's template into a fresh, uniquely named world and loads it. Returns null on failure. */
    World createInstance(DuelMap map) {
        if (template(map) == null) { plugin.getLogger().warning("[duel-maps] template missing for " + map.key()); return null; }
        String name = INSTANCE_PREFIX + map.key() + "_" + System.currentTimeMillis() + "_" + counter.incrementAndGet();
        File target = new File(Bukkit.getWorldContainer(), name);
        if (!snapshotTemplate(map, target.toPath())) { deleteQuietly(target); return null; }
        /** uid.dat identifies a world; a copy that keeps it collides with its source. */
        new File(target, "uid.dat").delete();
        new File(target, "session.lock").delete();
        World instance = new WorldCreator(name).generator(new ArenaService.VoidGenerator())
                .type(WorldType.FLAT).environment(World.Environment.NORMAL).createWorld();
        if (instance == null) { deleteQuietly(target); return null; }
        applyWorldRules(instance, false);
        purgeMobs(instance);
        liveInstances.put(name, map.key());
        fillChests(instance, map);
        return instance;
    }


    /** World.save() queues region writes rather than completing them, so a clone taken straight afterwards
     *  can copy a folder that does not yet contain the builder's latest chunks -- which is exactly how a
     *  freshly placed chest goes missing in the instance. Unloading each loaded chunk with save=true forces
     *  the write to finish before the copy starts; the template reloads them on demand. */
    private void flushTemplate(World tpl, DuelMap map) {
        tpl.save();
        for (org.bukkit.Chunk chunk : tpl.getLoadedChunks()) chunk.unload(true);
        tpl.save();
    }

    /** Takes a committed, quiescent copy of a template.
     *
     *  Saving alone is not enough: Paper queues region writes, so a folder copied straight after save() can
     *  be missing whole chunks -- which is how chest, sign and spawner block entities went absent from
     *  clones. Fully UNLOADING the world is the deterministic barrier: Bukkit flushes every dirty chunk and
     *  closes every region file before returning, so what is on disk afterwards is exactly the template.
     *  The world is reopened immediately afterwards, so an admin editing it sees only a brief pause. */
    private synchronized boolean snapshotTemplate(DuelMap map, java.nio.file.Path target) {
        World tpl = Bukkit.getWorld(map.templateWorld());
        java.io.File folder;
        if (tpl != null) {
            for (Player p : new ArrayList<>(tpl.getPlayers()))
                p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            folder = tpl.getWorldFolder();
            tpl.save();
            if (!Bukkit.unloadWorld(tpl, true)) {
                plugin.getLogger().warning("[duel-maps] template " + map.key() + " would not unload; snapshot may be stale.");
                return false;
            }
        } else {
            folder = new java.io.File(Bukkit.getWorldContainer(), map.templateWorld());
            if (!folder.isDirectory()) return false;
        }
        boolean ok;
        try { copyWorldFolder(folder.toPath(), target); ok = true; }
        catch (IOException e) { plugin.getLogger().warning("[duel-maps] snapshot failed for " + map.key() + ": " + e.getMessage()); ok = false; }
        template(map);
        return ok;
    }

    /** Unloads and deletes an instance world entirely. Any players still inside are moved out first. */
    void destroyInstance(World world, Location fallback) {
        if (world == null || !world.getName().startsWith(INSTANCE_PREFIX)) return;
        for (Player player : new ArrayList<>(world.getPlayers())) {
            Location to = fallback != null ? fallback : Bukkit.getWorlds().get(0).getSpawnLocation();
            player.teleport(to);
        }
        String name = world.getName();
        File folder = world.getWorldFolder();
        boolean unloaded = Bukkit.unloadWorld(world, false);
        liveInstances.remove(name);
        placed.remove(name);
        if (!unloaded) plugin.getLogger().warning("[duel-maps] " + name + " refused to unload; it will be swept at next startup.");
        /** Windows does not release the region-file handles the instant a world unloads, so a delete
         *  attempted in the same tick silently leaves the folder behind. Retry on a short delay until it
         *  is actually gone; anything that still survives is swept by cleanupOrphans() at next startup. */
        deleteWithRetry(folder, 30);
    }

    /** Startup recovery: every instance world is disposable by definition, so anything on disk at boot is
     *  the residue of a crash and is removed before matchmaking can hand it out. */
    int cleanupOrphans() {
        int removed = 0;
        for (World world : new ArrayList<>(Bukkit.getWorlds()))
            if (world.getName().startsWith(INSTANCE_PREFIX)) {
                File folder = world.getWorldFolder();
                Bukkit.unloadWorld(world, false);
                deleteQuietly(folder);
                removed++;
            }
        File container = Bukkit.getWorldContainer();
        File[] children = container.listFiles();
        if (children != null) for (File child : children)
            if (child.isDirectory() && child.getName().startsWith(INSTANCE_PREFIX)) { deleteQuietly(child); removed++; }
        liveInstances.clear();
        if (removed > 0) plugin.getLogger().info("[duel-maps] removed " + removed + " orphaned duel instance(s)");
        return removed;
    }

    List<String> instanceNames() { return new ArrayList<>(liveInstances.keySet()); }


    // ------------------------------------------------------------------ arena block rules

    /** instance world -> blocks a duellist placed during THIS match. Only these may be broken again on a
     *  PLACED_ONLY map, which is what keeps the two built arenas pristine while still letting players
     *  block-clutch. Cleared with the instance. */
    private final Map<String, Set<Long>> placed = new java.util.concurrent.ConcurrentHashMap<>();

    private static long key(org.bukkit.block.Block b) {
        return ((long) b.getX() & 0x3FFFFFF) << 38 | ((long) b.getZ() & 0x3FFFFFF) << 12 | ((long) (b.getY() + 2048) & 0xFFF);
    }

    /** Infrastructure the builder placed to make the map work at all. Protected under BOTH rules, because a
     *  duellist mining the boundary out of a fully-breakable map would simply leave it. */
    private boolean infrastructure(org.bukkit.Material m) {
        return switch (m) {
            case BARRIER, BEDROCK, COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK,
                 STRUCTURE_BLOCK, STRUCTURE_VOID, JIGSAW, LIGHT, END_PORTAL_FRAME -> true;
            default -> false;
        };
    }

    /** The map a loaded instance world belongs to, or null when the world is not a duel instance. */
    DuelMap mapOfWorld(World world) {
        if (world == null) return null;
        String k = liveInstances.get(world.getName());
        return k == null ? null : maps.get(k);
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void place(org.bukkit.event.block.BlockPlaceEvent e) {
        DuelMap m = mapOfWorld(e.getBlock().getWorld());
        if (m == null) return;
        placed.computeIfAbsent(e.getBlock().getWorld().getName(), n -> java.util.concurrent.ConcurrentHashMap.newKeySet())
              .add(key(e.getBlock()));
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void breakBlock(org.bukkit.event.block.BlockBreakEvent e) {
        DuelMap m = mapOfWorld(e.getBlock().getWorld());
        if (m == null) return;
        if (infrastructure(e.getBlock().getType())) { e.setCancelled(true); return; }
        if (m.rule() == BreakRule.FULL) return;
        Set<Long> mine = placed.get(e.getBlock().getWorld().getName());
        if (mine == null || !mine.remove(key(e.getBlock()))) e.setCancelled(true);
    }

    /** Explosions follow the same rule as a pickaxe: on a PLACED_ONLY map they may only consume blocks the
     *  duellists themselves placed, and on either map they never touch boundary infrastructure. */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void entityExplode(org.bukkit.event.entity.EntityExplodeEvent e) { filterBlast(e.getLocation().getWorld(), e.blockList()); }

    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void blockExplode(org.bukkit.event.block.BlockExplodeEvent e) { filterBlast(e.getBlock().getWorld(), e.blockList()); }

    private void filterBlast(World world, List<org.bukkit.block.Block> blocks) {
        DuelMap m = mapOfWorld(world);
        if (m == null) return;
        Set<Long> mine = placed.get(world.getName());
        blocks.removeIf(b -> infrastructure(b.getType())
                || (m.rule() == BreakRule.PLACED_ONLY && (mine == null || !mine.remove(key(b)))));
    }


    // ------------------------------------------------------------------ chest loot

    /** Fills every chest in a fresh instance. Templates are never touched, so each match rolls its own loot
     *  and nothing can carry between instances. A connected double chest is ONE container as far as Bukkit
     *  is concerned, so iterating block states would roll it twice -- the halves are de-duplicated by their
     *  shared inventory before any rolling happens. */
    void fillChests(World world, DuelMap map) {
        if (world == null || map == null) return;
        boolean keys = plugin.getConfig().getStringList("duel-loot.trial-key-maps").contains(map.key());
        Set<org.bukkit.inventory.Inventory> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int filled = 0;
        /** A freshly cloned world has nothing loaded, so its chests are invisible until the playable area is
         *  pulled in. Load a radius around the spawn pair -- that is the arena by definition -- before
         *  looking for containers, otherwise loot silently never generates. */
        int radius = Math.max(1, plugin.getConfig().getInt("duel-loot.load-radius-chunks", 12));
        int cx = (int) Math.floor((map.p1x() + map.p2x()) / 2.0) >> 4, cz = (int) Math.floor((map.p1z() + map.p2z()) / 2.0) >> 4;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) world.getChunkAt(cx + dx, cz + dz).load(true);
        for (org.bukkit.Chunk chunk : world.getLoadedChunks())
            for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
                if (!(state instanceof org.bukkit.block.Chest chest)) continue;
                org.bukkit.inventory.Inventory inv = chest.getInventory();
                if (!seen.add(inv)) continue;
                boolean isDouble = inv.getHolder() instanceof org.bukkit.block.DoubleChest;
                inv.clear();
                roll(inv, isDouble, keys);
                filled++;
            }
        if (filled > 0) plugin.getLogger().info("[duel-maps] " + world.getName() + ": populated " + filled + " chest(s)");
    }

    /** Single chests carry useful-but-moderate support; double chests roll a stronger table with a real but
     *  uncommon chance at a match-swinging item. Nothing high-impact is ever guaranteed. */
    private void roll(org.bukkit.inventory.Inventory inv, boolean big, boolean trialKeys) {
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        String path = big ? "duel-loot.double" : "duel-loot.single";
        int rolls = plugin.getConfig().getInt(path + ".rolls", big ? 6 : 3);
        List<String> table = plugin.getConfig().getStringList(path + ".entries");
        if (table.isEmpty()) table = big
                ? List.of("GOLDEN_APPLE:1-3:0.9","ENDER_PEARL:1-4:0.7","COOKED_BEEF:8-16:0.9","ARROW:16-32:0.6",
                          "OBSIDIAN:2-6:0.5","WIND_CHARGE:4-12:0.5","DIAMOND:1-3:0.4","IRON_BLOCK:1-2:0.35",
                          "ENCHANTED_GOLDEN_APPLE:1-1:0.10","TOTEM_OF_UNDYING:1-1:0.07")
                : List.of("GOLDEN_APPLE:1-2:0.8","COOKED_BEEF:6-12:0.9","ARROW:8-16:0.6","ENDER_PEARL:1-2:0.45",
                          "OBSIDIAN:1-4:0.4","WIND_CHARGE:2-6:0.4","IRON_INGOT:2-5:0.5","COBWEB:1-3:0.3");
        for (int i = 0; i < rolls; i++) {
            String entry = table.get(rng.nextInt(table.size()));
            String[] parts = entry.split(":");
            if (parts.length < 3) continue;
            try {
                if (rng.nextDouble() >= Double.parseDouble(parts[2])) continue;
                org.bukkit.Material mat = org.bukkit.Material.valueOf(parts[0].toUpperCase(Locale.ROOT));
                String[] range = parts[1].split("-");
                int lo = Integer.parseInt(range[0]), hi = range.length > 1 ? Integer.parseInt(range[1]) : lo;
                put(inv, new org.bukkit.inventory.ItemStack(mat, Math.max(1, rng.nextInt(lo, hi + 1))), rng);
            } catch (Exception ignored) {}
        }
        /** Trial keys exist on two maps only, and the ominous variant is the rarer of the pair. */
        if (trialKeys) {
            double normal = plugin.getConfig().getDouble("duel-loot.trial-key-chance", big ? .12 : .06);
            double ominous = plugin.getConfig().getDouble("duel-loot.ominous-key-chance", big ? .04 : .015);
            if (rng.nextDouble() < ominous) put(inv, new org.bukkit.inventory.ItemStack(org.bukkit.Material.OMINOUS_TRIAL_KEY), rng);
            else if (rng.nextDouble() < normal) put(inv, new org.bukkit.inventory.ItemStack(org.bukkit.Material.TRIAL_KEY), rng);
        }
    }

    private void put(org.bukkit.inventory.Inventory inv, org.bukkit.inventory.ItemStack item, java.util.Random rng) {
        for (int attempt = 0; attempt < 12; attempt++) {
            int slot = rng.nextInt(inv.getSize());
            if (inv.getItem(slot) == null) { inv.setItem(slot, item); return; }
        }
        inv.addItem(item);
    }

    // ------------------------------------------------------------------ world hygiene

    /** Mob spawning is off permanently in both templates and instances: a duel arena should contain exactly
     *  what the builder placed, and nothing that wanders in while a match is running. */
    private void applyWorldRules(World world, boolean template) {
        world.setAutoSave(template);
        if (!template) world.setKeepSpawnInMemory(false);
        world.setDifficulty(org.bukkit.Difficulty.NORMAL);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setTime(6000);
        world.setStorm(false);
    }

    /** Clears anything that drifted in or was cloned along with the template. Item frames, armour stands and
     *  other build furniture are deliberately kept -- only living mobs and loose items go. */
    void purgeMobs(World world) {
        if (world == null) return;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Player) continue;
            if (entity instanceof LivingEntity || entity.getType() == org.bukkit.entity.EntityType.ITEM)
                entity.remove();
        }
    }

    // ------------------------------------------------------------------ filesystem

    private void copyWorldFolder(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (name.equals("session.lock") || name.equals("uid.dat")) return FileVisitResult.CONTINUE;
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteWithRetry(File folder, int attemptsLeft) {
        deleteQuietly(folder);
        if (!folder.exists() || attemptsLeft <= 0) {
            if (folder.exists()) plugin.getLogger().warning("[duel-maps] could not delete " + folder.getName() + "; it will be swept at next startup.");
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> deleteWithRetry(folder, attemptsLeft - 1), 40L);
    }

    private void deleteQuietly(File folder) {
        if (folder == null || !folder.exists()) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(folder.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
