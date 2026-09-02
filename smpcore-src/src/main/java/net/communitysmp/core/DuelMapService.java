package net.communitysmp.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Duel maps: one private template world per map, a COMMITTED SNAPSHOT of that template on disk, and a
 *  disposable clone of that snapshot for every match.
 *
 *  A template is never entered by matchmaking -- it exists so an admin can build. {@code /ashfall duelmap
 *  save} turns the live template into a committed snapshot; matches only ever clone the committed snapshot,
 *  never the live world. That separation is what makes editing safe: an admin can be standing in a
 *  half-built template while matches run, and a save that fails at any point leaves the previous good
 *  snapshot in place rather than publishing a torn one.
 *
 *  Every duel gets its own instance world cloned from the snapshot, which is what gives concurrent duels on
 *  the same map complete isolation: terrain, chests and mobs in one match cannot be observed from another,
 *  and nothing a duellist breaks survives the match. Instances are deleted when the duel ends; any that
 *  survive a crash are recognisable by their name prefix and removed at startup.
 *
 *  WHERE WORLDS LIVE. Paper does not put a Bukkit-created world in the world container -- it puts it in
 *  {@code <level-name>/dimensions/<namespace>/<world>}. Writing a cloned folder to the container instead
 *  produced a world Paper never read: it generated a fresh empty one at the real path, so every instance
 *  came up void and every chest, sign, trial spawner and vault was "missing". The template itself was always
 *  fine -- its region files were on disk the whole time, one directory away from where the clone looked.
 *  {@link #customWorldDir()} resolves the real directory from a world Bukkit has already opened, and
 *  {@link #createInstance} re-verifies that against the world Bukkit actually returns. */
class DuelMapService implements org.bukkit.event.Listener {

    /** How the arena reacts to a duellist breaking a block. */
    enum BreakRule {
        /** Only blocks placed during this match may be broken. Original terrain, and any explosion damage to
         *  it, is refused. Used by the two built flat arenas. */
        PLACED_ONLY,
        /** Terrain inside the playable bounds is fully breakable, explosions included. Boundary barriers and
         *  control infrastructure are still protected, as is anything outside the bounds. */
        FULL
    }

    record DuelMap(String key, String name, BreakRule rule,
                   double p1x, double p1y, double p1z,
                   double p2x, double p2y, double p2z,
                   Double specx, Double specy, Double specz,
                   int[] bounds, String sourceWorld) {

        String templateWorld() { return TEMPLATE_PREFIX + key; }

        /** The two duellists always face each other: the yaw is derived from the spawn pair rather than
         *  stored, so moving a spawn point can never leave somebody staring at a wall. */
        float yawP1() { return facing(p1x, p1z, p2x, p2z); }
        float yawP2() { return facing(p2x, p2z, p1x, p1z); }

        static float facing(double fromX, double fromZ, double toX, double toZ) {
            double dx = toX - fromX, dz = toZ - fromZ;
            return (float) Math.toDegrees(Math.atan2(-dx, dz));
        }

        /** Spectators sit above the midpoint of the two spawns unless an admin pinned a spot. */
        double[] spectator(int fallbackHeight) {
            if (specx != null && specy != null && specz != null) return new double[]{specx, specy, specz};
            return new double[]{(p1x + p2x) / 2.0, Math.max(p1y, p2y) + fallbackHeight, (p1z + p2z) / 2.0};
        }

        boolean inBounds(int x, int y, int z) {
            if (bounds == null) return true;
            return x >= bounds[0] && x <= bounds[3] && y >= bounds[1] && y <= bounds[4] && z >= bounds[2] && z <= bounds[5];
        }

        Location p1(World world) { return standable(world, p1x, p1y, p1z, yawP1()); }
        Location p2(World world) { return standable(world, p2x, p2y, p2z, yawP2()); }

        /** The configured point, lifted out of the floor if it is inside one.
         *
         *  Spawn points get recorded two ways in practice: as the block a player stands IN, and as the block
         *  they stand ON. Skyroot Village's pair were recorded the second way, so taking them literally spawns
         *  a duellist inside a dirt path. Rather than quietly rewriting somebody's coordinates, the point is
         *  taken as given and the player is stood on top of whatever is actually there -- which is the same
         *  place under either reading. Capped at a few blocks so this can never turn a genuinely wrong spawn
         *  into a silently different one. */
        static Location standable(World world, double x, double y, double z, float yaw) {
            if (world == null) return new Location(world, x, y, z, yaw, 0f);
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y);
            for (int lift = 0; lift <= 6; lift++) {
                int at = by + lift;
                if (at + 1 >= world.getMaxHeight()) break;
                if (!world.getBlockAt(bx, at, bz).getType().isSolid()
                        && !world.getBlockAt(bx, at + 1, bz).getType().isSolid())
                    return new Location(world, x, at + (lift == 0 ? y - by : 0), z, yaw, 0f);
            }
            return new Location(world, x, y, z, yaw, 0f);
        }

        /** What a duellist would be standing on. Reported by the verifier so a spawn hanging over lava is
         *  called out rather than discovered by the first player to use the map. */
        Material footing(World world, Location at) {
            return world.getBlockAt(at.getBlockX(), at.getBlockY() - 1, at.getBlockZ()).getType();
        }
    }

    private final SMPCore plugin;
    private final Map<String, DuelMap> maps = new LinkedHashMap<>();
    /** instance world name -> map key, for the worlds this process created. */
    private final Map<String, String> liveInstances = new LinkedHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();
    /** Resolved once: the directory Paper actually keeps Bukkit-created worlds in. */
    private File customWorldDir;

    static final String INSTANCE_PREFIX = "duel_inst_";
    static final String TEMPLATE_PREFIX = "duel_tpl_";

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

    // ------------------------------------------------------------------ filesystem layout

    /** The directory Paper actually creates Bukkit worlds in.
     *
     *  Resolved from a world Bukkit has already opened rather than assumed, because the answer changed:
     *  modern Paper nests custom worlds under {@code <level-name>/dimensions/<namespace>/}, while older
     *  builds put them straight in the world container. Getting this wrong is silent -- the clone lands
     *  somewhere Paper never looks and the instance comes up empty -- so it is resolved, cached, and then
     *  re-verified against reality every time an instance world is opened. */
    File customWorldDir() {
        if (customWorldDir != null) return customWorldDir;
        List<World> worlds = Bukkit.getWorlds();
        World main = worlds.isEmpty() ? null : worlds.get(0);
        for (World world : worlds) {
            if (world == main) continue;
            File folder = world.getWorldFolder();
            if (folder != null && folder.getParentFile() != null) return customWorldDir = folder.getParentFile();
        }
        if (main != null && main.getWorldFolder() != null) {
            File dimensions = new File(main.getWorldFolder(), "dimensions");
            File vanilla = new File(dimensions, "minecraft");
            if (vanilla.isDirectory()) return customWorldDir = vanilla;
            File[] namespaces = dimensions.listFiles(File::isDirectory);
            if (namespaces != null && namespaces.length > 0) return customWorldDir = namespaces[0];
        }
        return customWorldDir = Bukkit.getWorldContainer();
    }

    /** Every directory a duel world could plausibly be sitting in, including the container older builds
     *  used, so orphan recovery cannot miss a folder just because the layout changed under it. */
    private List<File> worldSearchPath() {
        LinkedHashSet<File> out = new LinkedHashSet<>();
        out.add(customWorldDir());
        out.add(Bukkit.getWorldContainer());
        List<World> worlds = Bukkit.getWorlds();
        if (!worlds.isEmpty() && worlds.get(0).getWorldFolder() != null) {
            File dimensions = new File(worlds.get(0).getWorldFolder(), "dimensions");
            File[] namespaces = dimensions.listFiles(File::isDirectory);
            if (namespaces != null) out.addAll(Arrays.asList(namespaces));
        }
        return new ArrayList<>(out);
    }

    /** Where committed snapshots live: inside the plugin's own data folder, deliberately NOT in the world
     *  container, so Paper never sees a snapshot as a world and nothing can accidentally load one. */
    File snapshotRoot() { return new File(plugin.getDataFolder(), "duel-templates"); }
    File snapshotOf(DuelMap map) { return new File(snapshotRoot(), map.key()); }

    /** A snapshot is identified by its region files, NOT by a level.dat.
     *
     *  In Paper's dimension layout a non-main world folder has no level.dat at all -- the level data lives in
     *  the parent world and the dimension folder holds only region/entities/poi/data. Requiring level.dat here
     *  would reject every snapshot ever taken. */
    boolean hasSnapshot(DuelMap map) { return regionFiles(snapshotOf(map)).length > 0; }

    private static File[] regionFiles(File worldFolder) {
        File[] files = new File(worldFolder, "region").listFiles((d, n) -> n.endsWith(".mca") && new File(d, n).length() > 0);
        return files == null ? new File[0] : files;
    }

    /** Deletes instance folders that no longer belong to a loaded world or a live match. */
    int sweepDetached() {
        int removed = 0;
        for (File container : worldSearchPath()) {
            File[] children = container.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (!child.isDirectory() || !child.getName().startsWith(INSTANCE_PREFIX)) continue;
                if (liveInstances.containsKey(child.getName())) continue;
                if (Bukkit.getWorld(child.getName()) != null) continue;
                deleteQuietly(child);
                if (!child.exists()) removed++;
            }
        }
        if (removed > 0) plugin.getLogger().info("[duel-maps] swept " + removed + " detached instance folder(s)");
        return removed;
    }

    // ------------------------------------------------------------------ registry

    void reload() {
        maps.clear();
        org.bukkit.configuration.ConfigurationSection root = plugin.getConfig().getConfigurationSection("duel-maps");
        if (root == null) return;
        for (String raw : root.getKeys(false)) {
            org.bukkit.configuration.ConfigurationSection s = root.getConfigurationSection(raw);
            if (s == null) continue;
            String key = raw.toLowerCase(Locale.ROOT);
            List<Double> p1 = s.getDoubleList("p1"), p2 = s.getDoubleList("p2"), spec = s.getDoubleList("spectator");
            if (p1.size() < 3 || p2.size() < 3) {
                plugin.getLogger().warning("[duel-maps] " + key + " is missing p1/p2 spawns; skipped.");
                continue;
            }
            BreakRule rule = "placed-only".equalsIgnoreCase(s.getString("break-rule", "full"))
                    ? BreakRule.PLACED_ONLY : BreakRule.FULL;
            List<Integer> box = s.getIntegerList("bounds");
            int[] bounds = box.size() < 6 ? null : new int[]{
                    Math.min(box.get(0), box.get(3)), Math.min(box.get(1), box.get(4)), Math.min(box.get(2), box.get(5)),
                    Math.max(box.get(0), box.get(3)), Math.max(box.get(1), box.get(4)), Math.max(box.get(2), box.get(5))};
            maps.put(key, new DuelMap(key, s.getString("name", key), rule,
                    p1.get(0), p1.get(1), p1.get(2), p2.get(0), p2.get(1), p2.get(2),
                    spec.size() >= 3 ? spec.get(0) : null,
                    spec.size() >= 3 ? spec.get(1) : null,
                    spec.size() >= 3 ? spec.get(2) : null,
                    bounds, s.getString("source-world")));
        }
    }

    Collection<DuelMap> maps() { return maps.values(); }
    DuelMap map(String key) { return key == null ? null : maps.get(key.toLowerCase(Locale.ROOT)); }

    /** Maps a player may actually be sent to: registered, and with a committed snapshot to clone. A map an
     *  admin has not finished building is simply not offered, rather than failing at teleport time. */
    List<DuelMap> playableMaps() {
        List<DuelMap> out = new ArrayList<>();
        for (DuelMap m : maps.values()) if (hasSnapshot(m)) out.add(m);
        return out;
    }

    // ------------------------------------------------------------------ template worlds

    /** Loads (creating if absent) the private template world for a map. Templates are void worlds with mob
     *  spawning off; they are kept loaded only while an admin is editing or a snapshot is being committed. */
    World template(DuelMap map) { return workspace(map, line -> { }); }

    /** Opens the persistent EDITABLE workspace for a map -- the world an admin builds in -- and guarantees it
     *  actually contains the arena.
     *
     *  The workspace and the committed snapshot are two different things, and only the snapshot is a deploy
     *  artefact. Promotion therefore carries the snapshot and not the workspace, which is how production
     *  ended up with five `duel_tpl_*` folders that were empty void: the old code asked Paper for the world,
     *  Paper did not have one, and a brand-new empty world was silently generated and handed back as if it
     *  were the template. An admin then teleported into the right coordinates of the right world and found
     *  nothing there.
     *
     *  So a missing or arena-less workspace is now MATERIALISED from the committed snapshot, using the same
     *  copy and identity-metadata handling instances use. What is never touched is a workspace that already
     *  holds the arena, on disk or in memory -- an admin's unsaved building is not something to overwrite.
     *  {@code feedback} receives short progress lines, because materialising a large map takes a few seconds
     *  and silence there is what made the original failure confusing. */
    World workspace(DuelMap map, java.util.function.Consumer<String> feedback) {
        World live = Bukkit.getWorld(map.templateWorld());
        if (live != null) {
            if (holdsArena(live, map)) { loadPlayArea(live, map); return live; }
            if (!hasSnapshot(map)) { loadPlayArea(live, map); return live; }
            /** An empty shell that was silently generated earlier. It holds nothing anybody can lose, so it
             *  is closed and rebuilt from the snapshot. */
            feedback.accept("The open workspace for " + map.name() + " is empty; rebuilding it from the committed snapshot.");
            plugin.getLogger().warning("[duel-maps] workspace " + map.templateWorld() + " had no arena; rebuilding from snapshot");
            for (Player inside : new ArrayList<>(live.getPlayers())) inside.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            if (!Bukkit.unloadWorld(live, false)) {
                plugin.getLogger().warning("[duel-maps] could not unload the empty workspace " + map.templateWorld());
                return live;
            }
            deleteQuietly(live.getWorldFolder());
        }
        File folder = new File(customWorldDir(), map.templateWorld());
        if (!hasArenaRegion(folder, map)) {
            if (!hasSnapshot(map)) {
                feedback.accept("There is no committed snapshot for " + map.name() + " yet, so a new empty workspace is being created.");
            } else {
                feedback.accept("Materialising the " + map.name() + " workspace from its committed snapshot (" + describeSnapshot(snapshotOf(map)) + ")...");
                if (!materialise(map, folder)) {
                    plugin.getLogger().warning("[duel-maps] could not materialise the workspace for " + map.key());
                    return null;
                }
            }
        }
        World world = new WorldCreator(map.templateWorld())
                .generator(new ArenaService.VoidGenerator())
                .type(WorldType.FLAT)
                .environment(World.Environment.NORMAL)
                .createWorld();
        if (world == null) return null;
        File opened = world.getWorldFolder();
        if (opened != null && opened.getParentFile() != null && !opened.getParentFile().equals(customWorldDir())) {
            /** Same self-heal as instances: if Paper opened a different directory from the one written to,
             *  the layout assumption was wrong and the workspace would be empty again. */
            plugin.getLogger().warning("[duel-maps] expected workspace at " + customWorldDir().getAbsolutePath()
                    + " but Paper opened " + opened.getAbsolutePath() + "; correcting and rebuilding.");
            customWorldDir = opened.getParentFile();
            Bukkit.unloadWorld(world, false);
            deleteQuietly(opened);
            deleteQuietly(folder);
            if (hasSnapshot(map) && !materialise(map, new File(customWorldDir(), map.templateWorld()))) return null;
            world = new WorldCreator(map.templateWorld()).generator(new ArenaService.VoidGenerator())
                    .type(WorldType.FLAT).environment(World.Environment.NORMAL).createWorld();
            if (world == null) return null;
        }
        applyWorldRules(world, true);
        loadPlayArea(world, map);
        return world;
    }

    /** Where /ashfall duelmap enter puts an admin: the P1 spawn, lifted clear of the floor like a duellist's,
     *  so entering a map never drops somebody inside a block. */
    Location workspaceSpawn(World world, DuelMap map) { return map.p1(world); }

    /** Copies a committed snapshot into a workspace folder. Identity files are excluded by the copier, and
     *  removed again here for a snapshot written by an older build -- Paper refuses a world whose stored
     *  UUID it already knows. */
    private boolean materialise(DuelMap map, File target) {
        deleteQuietly(target);
        try { copyWorldFolder(snapshotOf(map).toPath(), target.toPath()); }
        catch (IOException e) {
            plugin.getLogger().warning("[duel-maps] workspace copy failed for " + map.key() + ": " + e.getMessage());
            deleteQuietly(target);
            return false;
        }
        stripIdentity(target);
        plugin.getLogger().info("[duel-maps] materialised workspace " + map.templateWorld() + " from its committed snapshot");
        return true;
    }

    /** True when a world FOLDER contains the region file the arena lives in. This is the same test the
     *  snapshot validator applies, and it is what "validate the arena's expected region" means here. */
    boolean hasArenaRegion(File folder, DuelMap map) {
        if (folder == null || !folder.isDirectory()) return false;
        File region = new File(new File(folder, "region"), arenaRegionName(map));
        return region.isFile() && region.length() > 0;
    }

    String arenaRegionName(DuelMap map) {
        int cx = (int) Math.floor((map.p1x() + map.p2x()) / 2.0) >> 4, cz = (int) Math.floor((map.p1z() + map.p2z()) / 2.0) >> 4;
        return "r." + (cx >> 5) + "." + (cz >> 5) + ".mca";
    }

    /** True when a LOADED world really has the arena: on disk, or standing in memory because an admin has
     *  been building and has not saved yet. The in-memory half is what stops a rebuild from destroying
     *  unsaved work in a workspace that has never been committed. */
    boolean holdsArena(World world, DuelMap map) {
        if (world == null) return false;
        if (hasArenaRegion(world.getWorldFolder(), map)) return true;
        int x = (int) Math.floor(map.p1x()), y = (int) Math.floor(map.p1y()), z = (int) Math.floor(map.p1z());
        world.getChunkAt(x >> 4, z >> 4).load(true);
        int floor = Math.max(world.getMinHeight(), y - 12), ceiling = Math.min(world.getMaxHeight() - 1, y + 12);
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int at = floor; at <= ceiling; at++)
                    if (!world.getBlockAt(x + dx, at, z + dz).getType().isAir()) return true;
        return false;
    }

    /** The chunks that make up the arena. A void template has nothing loaded by default, so without this an
     *  admin's /setblock lands in a chunk that is dropped again before anything reads it. Driven by the
     *  map's declared bounds where it has them, and by a radius around the spawn pair where it does not. */
    void loadPlayArea(World world, DuelMap map) {
        int[] c = chunkRange(map);
        for (int cx = c[0]; cx <= c[2]; cx++)
            for (int cz = c[1]; cz <= c[3]; cz++) world.getChunkAt(cx, cz).load(true);
    }

    /** minChunkX, minChunkZ, maxChunkX, maxChunkZ for a map's playable area. */
    int[] chunkRange(DuelMap map) {
        if (map.bounds() != null) {
            int[] b = map.bounds();
            return new int[]{b[0] >> 4, b[2] >> 4, b[3] >> 4, b[5] >> 4};
        }
        int radius = Math.max(1, plugin.getConfig().getInt("duel-loot.load-radius-chunks", 12));
        int cx = (int) Math.floor((map.p1x() + map.p2x()) / 2.0) >> 4;
        int cz = (int) Math.floor((map.p1z() + map.p2z()) / 2.0) >> 4;
        return new int[]{cx - radius, cz - radius, cx + radius, cz + radius};
    }

    // ------------------------------------------------------------------ committing a template

    /** Commits the live template to disk as the snapshot future matches clone.
     *
     *  The write barrier is a FULL WORLD UNLOAD with save=true: Bukkit flushes every dirty chunk and closes
     *  every region file before returning, so what is on disk afterwards is exactly the template. Nothing
     *  weaker is sufficient -- World.save() only queues the writes.
     *
     *  Publication is atomic: the copy is built beside the live snapshot under a .tmp name, validated, and
     *  only then swapped in by rename. A save that fails at any point leaves the previous snapshot exactly
     *  as it was, so a torn template can never reach a match. */
    synchronized String commitTemplate(DuelMap map) {
        World tpl = Bukkit.getWorld(map.templateWorld());
        File source;
        if (tpl != null) {
            Location out = Bukkit.getWorlds().get(0).getSpawnLocation();
            for (Player p : new ArrayList<>(tpl.getPlayers())) p.teleport(out);
            source = tpl.getWorldFolder();
            tpl.save();
            if (!Bukkit.unloadWorld(tpl, true)) {
                plugin.getLogger().warning("[duel-maps] template " + map.key() + " would not unload; snapshot NOT updated.");
                return "The template world would not unload, so nothing was committed. The previous snapshot is unchanged.";
            }
        } else {
            source = new File(customWorldDir(), map.templateWorld());
            if (!source.isDirectory())
                return "There is no template world for " + map.key() + " yet. Run /ashfall duelmap create " + map.key() + " first.";
        }
        File root = snapshotRoot();
        root.mkdirs();
        File live = snapshotOf(map);
        File tmp = new File(root, map.key() + ".tmp"), parked = new File(root, map.key() + ".old");
        String result;
        try {
            deleteQuietly(tmp); deleteQuietly(parked);
            copyWorldFolder(source.toPath(), tmp.toPath());
            String bad = validateSnapshot(tmp, map);
            if (bad != null) throw new IOException(bad);
            if (live.exists() && !live.renameTo(parked)) throw new IOException("the previous snapshot could not be parked");
            if (!tmp.renameTo(live)) {
                if (parked.exists()) parked.renameTo(live);
                throw new IOException("the new snapshot could not be published");
            }
            deleteQuietly(parked);
            result = "Committed " + map.name() + " (" + describeSnapshot(live) + "). Future matches use it; running matches keep the copy they started with.";
            plugin.getLogger().info("[duel-maps] committed snapshot for " + map.key() + " -> " + live.getAbsolutePath());
        } catch (IOException e) {
            deleteQuietly(tmp);
            plugin.getLogger().warning("[duel-maps] commit failed for " + map.key() + ": " + e.getMessage());
            result = "Save FAILED (" + e.getMessage() + "). The previous snapshot is untouched and still in use.";
        } finally {
            /** Reopen for the admin who is presumably still building. */
            template(map);
        }
        return result;
    }

    /** A snapshot is only publishable if it is a world AND it actually contains the region file covering the
     *  arena. That second half is the whole point: the defect this guards against produced a perfectly valid
     *  world folder that simply had no arena in it, and nothing downstream noticed until a duellist stood in
     *  an empty void. */
    private String validateSnapshot(File folder, DuelMap map) {
        if (!new File(folder, "region").isDirectory()) return "the snapshot has no region directory";
        Set<String> present = new HashSet<>();
        for (File f : regionFiles(folder)) present.add(f.getName());
        if (present.isEmpty()) return "the snapshot contains no region files at all";
        int sx = (int) Math.floor((map.p1x() + map.p2x()) / 2.0) >> 4, sz = (int) Math.floor((map.p1z() + map.p2z()) / 2.0) >> 4;
        String spawnRegion = "r." + (sx >> 5) + "." + (sz >> 5) + ".mca";
        if (!present.contains(spawnRegion))
            return "the arena's own region file " + spawnRegion + " was never written - the template's chunks never reached disk";
        int[] c = chunkRange(map);
        List<String> missing = new ArrayList<>();
        for (int rx = c[0] >> 5; rx <= (c[2] >> 5); rx++)
            for (int rz = c[1] >> 5; rz <= (c[3] >> 5); rz++) {
                String name = "r." + rx + "." + rz + ".mca";
                if (!present.contains(name)) missing.add(name);
            }
        /** A bounds box can legitimately overhang into a region that was never generated; that is worth
         *  saying out loud but is not a reason to refuse the save. */
        if (!missing.isEmpty())
            plugin.getLogger().info("[duel-maps] " + map.key() + ": " + missing.size()
                    + " region(s) inside the declared bounds were never generated (" + String.join(", ", missing) + ")");
        return null;
    }

    private String describeSnapshot(File folder) {
        File[] files = regionFiles(folder);
        long bytes = 0;
        for (File f : files) bytes += f.length();
        return files.length + " region file(s), " + (bytes / 1024 / 1024) + " MB";
    }

    /** Commit and report success as a boolean, for callers that only need to know whether to continue. */
    boolean saveTemplate(DuelMap map) {
        String result = commitTemplate(map);
        return result != null && result.startsWith("Committed");
    }

    // ------------------------------------------------------------------ instances

    /** Clones the map's COMMITTED SNAPSHOT into a fresh, uniquely named world and loads it. Never touches
     *  the live template, so an admin can be editing while matches start. Returns null on failure. */
    World createInstance(DuelMap map) {
        if (map == null) return null;
        if (!hasSnapshot(map)) {
            plugin.getLogger().info("[duel-maps] " + map.key() + " has no committed snapshot; committing one now.");
            if (!saveTemplate(map)) {
                plugin.getLogger().warning("[duel-maps] cannot create an instance of " + map.key() + ": no snapshot.");
                return null;
            }
        }
        World instance = openClone(map, customWorldDir());
        if (instance == null) return null;
        /** Trust nothing about where the world landed: if Bukkit opened a folder other than the one we wrote,
         *  the layout assumption is wrong and the instance is empty. Correct the cached directory from the
         *  world Bukkit just handed back and clone again into the right place. This is the guard that turns
         *  the original silent-empty-instance failure into something that self-heals and says so. */
        File opened = instance.getWorldFolder();
        if (opened != null && opened.getParentFile() != null && !opened.getParentFile().equals(customWorldDir())) {
            File corrected = opened.getParentFile();
            plugin.getLogger().warning("[duel-maps] expected world directory " + customWorldDir().getAbsolutePath()
                    + " but Paper opened " + opened.getAbsolutePath() + "; correcting and re-cloning.");
            String stale = instance.getName();
            File staleCopy = new File(customWorldDir(), stale);
            Bukkit.unloadWorld(instance, false);
            liveInstances.remove(stale);
            deleteQuietly(opened);
            deleteQuietly(staleCopy);
            customWorldDir = corrected;
            instance = openClone(map, corrected);
            if (instance == null) return null;
        }
        applyWorldRules(instance, false);
        purgeMobs(instance);
        fillChests(instance, map);
        return instance;
    }

    private World openClone(DuelMap map, File dir) {
        String name = nextInstanceName(map);
        File target = new File(dir, name);
        deleteQuietly(target);
        try { copyWorldFolder(snapshotOf(map).toPath(), target.toPath()); }
        catch (IOException e) {
            plugin.getLogger().warning("[duel-maps] clone failed for " + map.key() + ": " + e.getMessage());
            deleteQuietly(target);
            return null;
        }
        World instance = openCopied(map, name, target);
        if (instance == null) deleteQuietly(target);
        return instance;
    }

    private String nextInstanceName(DuelMap map) {
        return INSTANCE_PREFIX + map.key() + "_" + System.currentTimeMillis() + "_" + counter.incrementAndGet();
    }

    private World openCopied(DuelMap map, String name, File target) {
        /** Belt and braces over copyWorldFolder's own exclusion: a snapshot taken by an older build could
         *  still have an identity file in it, and Paper would refuse the clone as a duplicate world. */
        stripIdentity(target);
        World instance = new WorldCreator(name).generator(new ArenaService.VoidGenerator())
                .type(WorldType.FLAT).environment(World.Environment.NORMAL).createWorld();
        if (instance == null) return null;
        liveInstances.put(name, map.key());
        return instance;
    }

    /** The variant matchmaking uses. Same result as {@link #createInstance}, but the two expensive parts are
     *  kept off the main thread's critical path: the folder copy runs asynchronously (the committed snapshot
     *  is immutable, so nothing can be racing it), and the arena's chunks are pulled in a slice at a time
     *  rather than all at once. An imported map is a couple of hundred chunks; loading those in one tick is a
     *  visible freeze for everyone on the server, and a duel starting is not worth that.
     *
     *  {@code done} is called on the main thread with the ready instance, or with null if anything failed. */
    void prepareInstance(DuelMap map, java.util.function.Consumer<World> done) {
        if (map == null) { done.accept(null); return; }
        if (!hasSnapshot(map) && !saveTemplate(map)) { done.accept(null); return; }
        String name = nextInstanceName(map);
        File dir = customWorldDir();
        File target = new File(dir, name);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean copied;
            try { deleteQuietly(target); copyWorldFolder(snapshotOf(map).toPath(), target.toPath()); copied = true; }
            catch (IOException e) { plugin.getLogger().warning("[duel-maps] clone failed for " + map.key() + ": " + e.getMessage()); copied = false; }
            boolean ok = copied;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!ok) { deleteQuietly(target); done.accept(null); return; }
                World instance = openCopied(map, name, target);
                if (instance == null) { deleteQuietly(target); done.accept(null); return; }
                File opened = instance.getWorldFolder();
                if (opened != null && opened.getParentFile() != null && !opened.getParentFile().equals(dir)) {
                    /** Layout assumption was wrong: fall back to the synchronous path, which re-resolves the
                     *  directory from what Paper actually returned and re-clones into it. */
                    plugin.getLogger().warning("[duel-maps] expected " + dir.getAbsolutePath() + " but Paper opened "
                            + opened.getAbsolutePath() + "; falling back to a verified clone.");
                    Bukkit.unloadWorld(instance, false);
                    liveInstances.remove(name);
                    deleteQuietly(opened); deleteQuietly(target);
                    customWorldDir = opened.getParentFile();
                    done.accept(createInstance(map));
                    return;
                }
                applyWorldRules(instance, false);
                loadPlayAreaSliced(instance, map, () -> {
                    purgeMobs(instance);
                    fillChests(instance, map, false);
                    done.accept(instance);
                });
            });
        });
    }

    /** Pulls the arena's chunks in over several ticks, then runs {@code done}. */
    private void loadPlayAreaSliced(World world, DuelMap map, Runnable done) {
        int[] c = chunkRange(map);
        List<int[]> chunks = new ArrayList<>();
        for (int cx = c[0]; cx <= c[2]; cx++) for (int cz = c[1]; cz <= c[3]; cz++) chunks.add(new int[]{cx, cz});
        int perTick = Math.max(1, plugin.getConfig().getInt("duel-loot.chunks-per-tick", 24));
        java.util.Iterator<int[]> it = chunks.iterator();
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                for (int i = 0; i < perTick && it.hasNext(); i++) {
                    int[] at = it.next();
                    world.getChunkAt(at[0], at[1]).load(true);
                }
                if (!it.hasNext()) { cancel(); done.run(); }
            }
        }.runTaskTimer(plugin, 0L, 1L);
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
        looted.remove(name);
        lastFill.remove(name);
        if (!unloaded) plugin.getLogger().warning("[duel-maps] " + name + " refused to unload; it will be swept at next startup.");
        /** Windows does not release the region-file handles the instant a world unloads, so a delete
         *  attempted in the same tick silently leaves the folder behind. Retry on a short delay until it
         *  is actually gone; anything that still survives is swept by cleanupOrphans() at next startup. */
        deleteWithRetry(folder, 30);
    }

    /** Startup recovery: every instance world is disposable by definition, so anything on disk at boot is
     *  the residue of a crash and is removed before matchmaking can hand it out. Half-written snapshots from
     *  a save that died mid-flight are residue too, and a parked snapshot with no live sibling means the
     *  atomic swap was interrupted between its two renames -- that one is put back rather than deleted. */
    int cleanupOrphans() {
        int removed = 0;
        for (World world : new ArrayList<>(Bukkit.getWorlds()))
            if (world.getName().startsWith(INSTANCE_PREFIX)) {
                File folder = world.getWorldFolder();
                Bukkit.unloadWorld(world, false);
                deleteQuietly(folder);
                removed++;
            }
        for (File container : worldSearchPath()) {
            File[] children = container.listFiles();
            if (children == null) continue;
            for (File child : children)
                if (child.isDirectory() && child.getName().startsWith(INSTANCE_PREFIX)) { deleteQuietly(child); removed++; }
        }
        File[] staging = snapshotRoot().listFiles((d, n) -> n.endsWith(".tmp"));
        if (staging != null) for (File f : staging) deleteQuietly(f);
        File[] parked = snapshotRoot().listFiles((d, n) -> n.endsWith(".old"));
        if (parked != null) for (File f : parked) {
            File live = new File(snapshotRoot(), f.getName().substring(0, f.getName().length() - 4));
            if (!live.exists() && f.renameTo(live)) plugin.getLogger().warning("[duel-maps] restored parked snapshot " + live.getName());
            else deleteQuietly(f);
        }
        liveInstances.clear();
        if (removed > 0) plugin.getLogger().info("[duel-maps] removed " + removed + " orphaned duel instance(s)");
        return removed;
    }

    List<String> instanceNames() { return new ArrayList<>(liveInstances.keySet()); }
    boolean isInstance(World world) { return world != null && liveInstances.containsKey(world.getName()); }

    // ------------------------------------------------------------------ arena block rules

    /** instance world -> blocks a duellist placed during THIS match. Only these may be broken again on a
     *  PLACED_ONLY map, which is what keeps the two built arenas pristine while still letting players
     *  block-clutch. Cleared between rounds and with the instance. */
    private final Map<String, Set<Long>> placed = new java.util.concurrent.ConcurrentHashMap<>();
    /** Instance worlds whose loot has already been rolled. Loot happens exactly once per instance, at its
     *  creation, and this is what makes that literal rather than incidental. */
    private final Set<String> looted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** instance world -> {chests filled, chests left stocked}, for the admin readouts. */
    private final Map<String, int[]> lastFill = new java.util.concurrent.ConcurrentHashMap<>();

    private static long key(org.bukkit.block.Block b) { return key(b.getX(), b.getY(), b.getZ()); }
    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) (y + 2048) & 0xFFFL);
    }
    private static int unpackX(long k) { int x = (int) ((k >> 38) & 0x3FFFFFFL); return (x & 0x2000000) != 0 ? x | 0xFC000000 : x; }
    private static int unpackZ(long k) { int z = (int) ((k >> 12) & 0x3FFFFFFL); return (z & 0x2000000) != 0 ? z | 0xFC000000 : z; }
    private static int unpackY(long k) { return (int) (k & 0xFFFL) - 2048; }

    /** Wipes every block the duellists placed in an instance, so each round starts on the map as built.
     *  Returns how many were cleared. */
    int clearPlaced(World world) {
        if (world == null) return 0;
        Set<Long> mine = placed.remove(world.getName());
        if (mine == null) return 0;
        for (long k : mine) world.getBlockAt(unpackX(k), unpackY(k), unpackZ(k)).setType(Material.AIR, false);
        return mine.size();
    }

    /** Infrastructure the builder placed to make the map work at all. Protected under BOTH rules, because a
     *  duellist mining the boundary out of a fully-breakable map would simply leave it. */
    private boolean infrastructure(Material m) {
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

    /** True when this block may not be removed by anybody, under either rule. */
    private boolean protectedBlock(DuelMap m, org.bukkit.block.Block b) {
        return infrastructure(b.getType()) || !m.inBounds(b.getX(), b.getY(), b.getZ());
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void place(org.bukkit.event.block.BlockPlaceEvent e) {
        org.bukkit.block.Block block = e.getBlock();
        DuelMap m = mapOfWorld(block.getWorld());
        if (m == null) return;
        /** Placing outside the playable bounds would let a duellist simply bridge out of the map. */
        if (!m.inBounds(block.getX(), block.getY(), block.getZ())) { e.setCancelled(true); return; }
        placed.computeIfAbsent(block.getWorld().getName(), n -> java.util.concurrent.ConcurrentHashMap.newKeySet())
              .add(key(block));
    }

    /** THE break rule, as a pure decision, so it can be asserted directly instead of only observed by
     *  swinging a pickaxe in a live match. Both the block-break listener and the explosion filter go through
     *  this, which is what guarantees a duellist and a stick of TNT are held to the same rule. */
    boolean breakAllowed(DuelMap m, org.bukkit.block.Block block, boolean placedThisMatch) {
        if (m == null) return true;
        if (protectedBlock(m, block)) return false;
        return m.rule() == BreakRule.FULL || placedThisMatch;
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void breakBlock(org.bukkit.event.block.BlockBreakEvent e) {
        org.bukkit.block.Block block = e.getBlock();
        DuelMap m = mapOfWorld(block.getWorld());
        if (m == null) return;
        Set<Long> mine = placed.get(block.getWorld().getName());
        boolean own = mine != null && mine.remove(key(block));
        if (!breakAllowed(m, block, own)) e.setCancelled(true);
    }

    /** Explosions follow the same rule as a pickaxe: on a PLACED_ONLY map they may only consume blocks the
     *  duellists themselves placed, and on either map they never touch boundary infrastructure or anything
     *  outside the playable bounds. */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void entityExplode(org.bukkit.event.entity.EntityExplodeEvent e) { filterBlast(e.getLocation().getWorld(), e.blockList()); }

    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void blockExplode(org.bukkit.event.block.BlockExplodeEvent e) { filterBlast(e.getBlock().getWorld(), e.blockList()); }

    private void filterBlast(World world, List<org.bukkit.block.Block> blocks) {
        DuelMap m = mapOfWorld(world);
        if (m == null) return;
        Set<Long> mine = placed.get(world.getName());
        blocks.removeIf(b -> !breakAllowed(m, b, mine != null && mine.remove(key(b))));
    }

    /** Nothing leaves a duel instance under its own steam: an ender pearl or chorus fruit landing outside the
     *  bounds would put a duellist in unreachable void. Plugin and command teleports (the match itself, an
     *  admin) are exempt. */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void teleport(org.bukkit.event.player.PlayerTeleportEvent e) {
        if (e.getTo() == null) return;
        DuelMap m = mapOfWorld(e.getTo().getWorld());
        if (m == null || m.bounds() == null) return;
        if (e.getCause() == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN
                || e.getCause() == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND) return;
        if (!m.inBounds(e.getTo().getBlockX(), e.getTo().getBlockY(), e.getTo().getBlockZ())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ chest loot

    /** Fills every chest in a fresh instance. Templates and snapshots are never touched, so each match rolls
     *  its own loot and nothing can carry between instances. A connected double chest is ONE container as far
     *  as Bukkit is concerned, so iterating block states would roll it twice -- the halves are de-duplicated
     *  by the position of their left half before any rolling happens. */
    int fillChests(World world, DuelMap map) { return fillChests(world, map, true)[0]; }

    /** Rolls this match's loot into every EMPTY chest the committed template contained.
     *
     *  "Empty in the template" is the entire eligibility rule, and it needs no registry, no coordinate list
     *  and no config: an admin places a chest in the template, saves, and every future match finds it and
     *  fills it. A chest the builder deliberately STOCKED is left exactly as they left it -- rolling over it
     *  would destroy the thing they put there on purpose.
     *
     *  A chest a duellist places during the match can never qualify, because this runs once, at instance
     *  creation, before either player is teleported in. {@link #looted} makes that "once" literal: a chunk
     *  that unloads and comes back, or any second call for any reason, cannot re-roll a world's loot -- which
     *  would otherwise be a free item printer in the middle of a match.
     *
     *  Returns {containers filled, containers left alone because the builder had stocked them}. */
    int[] fillChests(World world, DuelMap map, boolean loadArea) {
        if (world == null || map == null) return new int[]{0, 0};
        if (!looted.add(world.getName())) return new int[]{0, 0};
        boolean keys = plugin.getConfig().getStringList("duel-loot.trial-key-maps").contains(map.key());
        Set<String> seen = new HashSet<>();
        int filled = 0, stocked = 0;
        /** A freshly cloned world has nothing loaded, so its chests are invisible until the playable area is
         *  pulled in. The sliced loader does that itself, and passes false so it is not repeated. */
        if (loadArea) loadPlayArea(world, map);
        for (org.bukkit.Chunk chunk : world.getLoadedChunks())
            for (org.bukkit.block.BlockState snapshot : chunk.getTileEntities()) {
                if (!(snapshot instanceof org.bukkit.block.Chest)) continue;
                /** getTileEntities hands back SNAPSHOTS; writing into a snapshot's inventory changes nothing
                 *  in the world. Re-read the live block state before touching anything. */
                if (!(snapshot.getBlock().getState(false) instanceof org.bukkit.block.Chest chest)) continue;
                org.bukkit.inventory.Inventory inv = chest.getInventory();
                /** Both halves of a double chest resolve to one identity, so it is considered -- and rolled --
                 *  exactly once however many block entities it presents. */
                if (!seen.add(containerId(chest, inv))) continue;
                if (!isEmpty(inv)) { stocked++; continue; }
                roll(inv, inv.getHolder(false) instanceof org.bukkit.block.DoubleChest, keys);
                filled++;
            }
        if (filled > 0 || stocked > 0)
            plugin.getLogger().info("[duel-maps] " + world.getName() + ": rolled loot into " + filled
                    + " empty template chest(s); left " + stocked + " pre-stocked chest(s) untouched");
        lastFill.put(world.getName(), new int[]{filled, stocked});
        return new int[]{filled, stocked};
    }

    private static boolean isEmpty(org.bukkit.inventory.Inventory inv) {
        for (org.bukkit.inventory.ItemStack item : inv.getContents())
            if (item != null && !item.getType().isAir()) return false;
        return true;
    }

    /** What the one loot roll did in a live instance, for the admin readouts. */
    int[] lastFill(World world) { return world == null ? null : lastFill.get(world.getName()); }

    /** One identity per physical container: both halves of a double chest resolve to the same string, which
     *  is what stops a double chest being rolled twice and getting double loot. */
    private String containerId(org.bukkit.block.Chest chest, org.bukkit.inventory.Inventory inv) {
        if (inv.getHolder(false) instanceof org.bukkit.block.DoubleChest dc) {
            org.bukkit.inventory.InventoryHolder left = dc.getLeftSide();
            Location at = left instanceof org.bukkit.block.Chest c ? c.getLocation() : chest.getLocation();
            return "d" + at.getBlockX() + ":" + at.getBlockY() + ":" + at.getBlockZ();
        }
        return "s" + chest.getX() + ":" + chest.getY() + ":" + chest.getZ();
    }

    /** Counts the containers a loaded instance presents, de-duplicated exactly as the loot roller counts
     *  them. Used by the loot report so configured key chances can be judged against the real chest count
     *  instead of in the abstract. Returns {singles, doubles}. */
    int[] chestCensus(World world) {
        int single = 0, dbl = 0, barrels = 0, vaults = 0, spawners = 0, empty = 0;
        Set<String> seen = new HashSet<>();
        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
            for (org.bukkit.block.BlockState snapshot : chunk.getTileEntities()) {
                if (snapshot instanceof org.bukkit.block.Barrel) { barrels++; continue; }
                if (!(snapshot instanceof org.bukkit.block.Chest)) continue;
                if (!(snapshot.getBlock().getState(false) instanceof org.bukkit.block.Chest chest)) continue;
                org.bukkit.inventory.Inventory inv = chest.getInventory();
                if (!seen.add(containerId(chest, inv))) continue;
                if (inv.getHolder(false) instanceof org.bukkit.block.DoubleChest) dbl++; else single++;
                if (isEmpty(inv)) empty++;
            }
            /** Vaults and trial spawners are block entities too, so they come out of the same cheap list --
             *  no block scan needed. They matter because a map whose loot is behind vaults is exactly the map
             *  that needs trial keys, which is the whole reason two maps may roll them. */
            for (org.bukkit.block.BlockState snapshot : chunk.getTileEntities()) {
                Material type = snapshot.getType();
                if (type == Material.VAULT) vaults++;
                else if (type == Material.TRIAL_SPAWNER || type == Material.SPAWNER) spawners++;
            }
        }
        return new int[]{single, dbl, barrels, vaults, spawners, empty};
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
                Material mat = Material.valueOf(parts[0].toUpperCase(Locale.ROOT));
                String[] range = parts[1].split("-");
                int lo = Integer.parseInt(range[0]), hi = range.length > 1 ? Integer.parseInt(range[1]) : lo;
                put(inv, new org.bukkit.inventory.ItemStack(mat, Math.max(1, rng.nextInt(lo, hi + 1))), rng);
            } catch (Exception ignored) { }
        }
        /** Trial keys exist on two maps only, and the ominous variant is the rarer of the pair. Rolled
         *  exclusively, so a chest yields at most one key and never both kinds. */
        if (trialKeys) {
            double normal = plugin.getConfig().getDouble("duel-loot.trial-key-chance", .10);
            double ominous = plugin.getConfig().getDouble("duel-loot.ominous-key-chance", .03);
            if (rng.nextDouble() < ominous) put(inv, new org.bukkit.inventory.ItemStack(Material.OMINOUS_TRIAL_KEY), rng);
            else if (rng.nextDouble() < normal) put(inv, new org.bukkit.inventory.ItemStack(Material.TRIAL_KEY), rng);
        }
    }

    /** Rolls a map's loot table `trials` times into a scratch inventory and reports what came out, so the
     *  trial-key restriction and the configured rates can be checked as numbers rather than by opening
     *  chests until something interesting happens. Returns {normal keys, ominous keys, chests that rolled}. */
    int[] lootProbe(DuelMap map, boolean big, int trials) {
        boolean keys = plugin.getConfig().getStringList("duel-loot.trial-key-maps").contains(map.key());
        int normal = 0, ominous = 0;
        for (int i = 0; i < trials; i++) {
            org.bukkit.inventory.Inventory scratch = Bukkit.createInventory(null, big ? 54 : 27);
            roll(scratch, big, keys);
            for (org.bukkit.inventory.ItemStack item : scratch.getContents()) {
                if (item == null) continue;
                if (item.getType() == Material.TRIAL_KEY) normal += item.getAmount();
                if (item.getType() == Material.OMINOUS_TRIAL_KEY) ominous += item.getAmount();
            }
        }
        return new int[]{normal, ominous, trials};
    }

    private void put(org.bukkit.inventory.Inventory inv, org.bukkit.inventory.ItemStack item, java.util.Random rng) {
        for (int attempt = 0; attempt < 12; attempt++) {
            int slot = rng.nextInt(inv.getSize());
            if (inv.getItem(slot) == null) { inv.setItem(slot, item); return; }
        }
        inv.addItem(item);
    }

    /** Expected trial-key yield for a map, given how many chests it actually has. Reported by
     *  /ashfall duelmap loot, so the configured rates can be judged against the real chest count. */
    String lootReport(DuelMap map, World instance) {
        int[] census = chestCensus(instance), fill = lastFill(instance);
        int singles = census[0], doubles = census[1];
        /** ELIGIBLE means "was empty in the template". In an instance the roll has already happened, so the
         *  honest source for that number is what the roll actually did, not what is in the chests now. */
        int eligible = fill != null ? fill[0] : census[5], stocked = fill != null ? fill[1] : (singles + doubles) - census[5];
        String head = map.name() + ": " + (singles + doubles) + " chest(s) (" + singles + " single, " + doubles
                + " double) | " + eligible + " empty in the template and rolled, " + stocked + " pre-stocked and left alone";
        if (!plugin.getConfig().getStringList("duel-loot.trial-key-maps").contains(map.key()))
            return head + "; trial keys are NOT enabled on this map.";
        double normal = plugin.getConfig().getDouble("duel-loot.trial-key-chance", .10);
        double ominous = plugin.getConfig().getDouble("duel-loot.ominous-key-chance", .03);
        /** Ominous is rolled first and wins outright, so the normal key's real chance is conditional. */
        double perOminous = ominous, perNormal = (1 - ominous) * normal;
        return head
                + String.format(" | per eligible chest: ominous %.1f%%, normal %.1f%%", perOminous * 100, perNormal * 100)
                + String.format(" | expected per match: %.2f ominous, %.2f normal", perOminous * eligible, perNormal * eligible);
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
        world.setGameRule(GameRule.FALL_DAMAGE, true);
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.DISABLE_RAIDS, true);
        world.setTime(6000);
        world.setStorm(false);
    }

    /** Belt-and-braces over DO_MOB_SPAWNING: trial spawners, imported spawner blocks and spawn eggs do not
     *  all honour the gamerule, and an imported dungeon map is full of them. Only explicitly plugin-spawned
     *  entities (CUSTOM) are let through, so nothing natural ever appears in a duel. */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void spawn(org.bukkit.event.entity.CreatureSpawnEvent e) {
        World world = e.getLocation().getWorld();
        if (world == null) return;
        String name = world.getName();
        if (!name.startsWith(INSTANCE_PREFIX) && !name.startsWith(TEMPLATE_PREFIX)) return;
        if (e.getSpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        e.setCancelled(true);
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

    // ------------------------------------------------------------------ flat arena builder

    /** Lays the designed black-and-red flat arena into a template world: a size x size floor of red
     *  terracotta speckled with glowstone, full-height obsidian-and-glowstone perimeter walls, and a bedrock
     *  cap on the top row. Spread over ticks -- the walls are 50k-100k blocks and doing that in one tick
     *  freezes the server. */
    void buildFlatArena(DuelMap map, int size, Runnable done) {
        World world = template(map);
        if (world == null) { if (done != null) done.run(); return; }
        int half = size / 2, minX = -half, maxX = half - 1, minZ = -half, maxZ = half - 1;
        int floorY = (int) Math.floor(Math.min(map.p1y(), map.p2y())) - 1, topY = world.getMaxHeight() - 1;
        List<Runnable> steps = new ArrayList<>();
        /** Clear anything already standing, so a rebuild at a different size leaves nothing behind. */
        for (int y = floorY; y <= Math.min(topY, floorY + 16); y++) {
            final int yy = y;
            steps.add(() -> { for (int x = minX - 1; x <= maxX + 1; x++) for (int z = minZ - 1; z <= maxZ + 1; z++)
                world.getBlockAt(x, yy, z).setType(Material.AIR, false); });
        }
        steps.add(() -> { for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            world.getBlockAt(x, floorY, z).setType(floorMat(x, z), false); });
        for (int base = floorY + 1; base <= topY; base += 24) {
            final int y0 = base, y1 = Math.min(topY, base + 23);
            steps.add(() -> {
                for (int y = y0; y <= y1; y++) {
                    boolean cap = y == topY;
                    for (int x = minX; x <= maxX; x++) { shell(world, x, y, minZ, cap); shell(world, x, y, maxZ, cap); }
                    for (int z = minZ + 1; z < maxZ; z++) { shell(world, minX, y, z, cap); shell(world, maxX, y, z, cap); }
                }
            });
        }
        java.util.Iterator<Runnable> it = steps.iterator();
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                for (int i = 0; i < 2 && it.hasNext(); i++) it.next().run();
                if (!it.hasNext()) { cancel(); if (done != null) done.run(); }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void shell(World world, int x, int y, int z, boolean cap) {
        world.getBlockAt(x, y, z).setType(cap ? Material.BEDROCK : wallMat(x, y, z), false);
    }
    private static Material floorMat(int x, int z) { return Math.floorMod(x * 3 + z * 7, 5) == 0 ? Material.GLOWSTONE : Material.RED_TERRACOTTA; }
    private static Material wallMat(int x, int y, int z) { return Math.floorMod(x * 5 + z * 3 + y * 2, 6) == 0 ? Material.GLOWSTONE : Material.OBSIDIAN; }

    // ------------------------------------------------------------------ filesystem

    /*  Package-private, not private, because the Colosseum clones worlds too and must do it with THIS
     *  implementation rather than a lookalike. The subtleties here were all learned the hard way -- which
     *  files carry a world's identity, that Paper refuses a clone whose UUID it already knows, that Windows
     *  holds region-file handles after an unload -- and a second copy of that knowledge would rot. The
     *  Colosseum keeps its own registry, prefixes and rules; it borrows only the filesystem. */
    void copyWorldFolder(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("source " + source + " is not a directory");
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (identityFile(file)) return FileVisitResult.CONTINUE;
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Files that carry a world's IDENTITY rather than its contents, and so must never travel with a copy.
     *
     *  Paper refuses to load a world whose stored UUID matches one it already has -- "is a duplicate of
     *  another world and has been prevented from loading" -- and in the current layout that UUID lives in
     *  data/paper/metadata.dat, not in the uid.dat older versions used. Copying it made every clone silently
     *  refuse to open, which surfaced only as a null world several layers up. Both names are excluded, so the
     *  copy works on either layout. */
    /** Strips the three files that make a copied folder look like a world Paper already has open.
     *  {@link #copyWorldFolder} skips them, but a snapshot written by an older build can still contain one,
     *  and Paper refuses such a clone outright ("is a duplicate of another world"). */
    static void stripIdentity(File folder) {
        new File(folder, "uid.dat").delete();
        new File(folder, "session.lock").delete();
        new File(new File(folder, "data" + File.separator + "paper"), "metadata.dat").delete();
    }

    private static boolean identityFile(Path file) {
        String name = file.getFileName().toString();
        if (name.equals("session.lock") || name.equals("uid.dat")) return true;
        Path parent = file.getParent();
        return name.equals("metadata.dat") && parent != null && parent.getFileName() != null
                && parent.getFileName().toString().equals("paper");
    }

    void deleteWithRetry(File folder, int attemptsLeft) {
        deleteQuietly(folder);
        if (!folder.exists() || attemptsLeft <= 0) {
            if (folder.exists()) plugin.getLogger().warning("[duel-maps] could not delete " + folder.getName() + "; it will be swept at next startup.");
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> deleteWithRetry(folder, attemptsLeft - 1), 40L);
    }

    void deleteQuietly(File folder) {
        if (folder == null || !folder.exists()) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(folder.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ self test

    /** Registry integrity and the arithmetic around it. The end-to-end persistence proof needs a live server
     *  and so lives in {@link DuelMapCanary}, run from /ashfall duelmap canary. */
    boolean selfTest() {
        if (maps.size() < 6) return false;
        for (String required : List.of("arena50", "arena100", "temple_of_tides", "cinder_crucible", "deepstone_mines", "skyroot_village"))
            if (!maps.containsKey(required)) return false;
        if (maps.get("arena50").rule() != BreakRule.PLACED_ONLY || maps.get("arena100").rule() != BreakRule.PLACED_ONLY) return false;
        for (String full : List.of("temple_of_tides", "cinder_crucible", "deepstone_mines", "skyroot_village"))
            if (maps.get(full).rule() != BreakRule.FULL) return false;
        /** Trial keys are restricted to exactly the two maps that are allowed them, and ominous is rarer. */
        List<String> keyMaps = plugin.getConfig().getStringList("duel-loot.trial-key-maps");
        if (keyMaps.size() != 2 || !keyMaps.contains("cinder_crucible") || !keyMaps.contains("deepstone_mines")) return false;
        double normal = plugin.getConfig().getDouble("duel-loot.trial-key-chance", -1);
        double ominous = plugin.getConfig().getDouble("duel-loot.ominous-key-chance", -1);
        if (!(normal > 0 && ominous > 0 && ominous < normal)) return false;
        for (DuelMap m : maps.values()) {
            /** Facing: each duellist's yaw must point at the other, for every registered map. */
            if (!facesEachOther(m)) return false;
            /** A spawn outside its own map's bounds would drop a duellist into the void. */
            if (m.bounds() != null
                    && (!m.inBounds((int) Math.floor(m.p1x()), (int) Math.floor(m.p1y()), (int) Math.floor(m.p1z()))
                     || !m.inBounds((int) Math.floor(m.p2x()), (int) Math.floor(m.p2y()), (int) Math.floor(m.p2z())))) return false;
        }
        /** The placed-block key packs and unpacks negative coordinates without loss -- the flat arenas
         *  straddle 0, so a sign-extension bug here would silently disable block clutching on half the map. */
        for (int[] p : new int[][]{{0, 64, 0}, {-25, 64, -25}, {24, 319, 24}, {9153, 232, 11208}, {-9153, -60, -11208}}) {
            long k = key(p[0], p[1], p[2]);
            if (unpackX(k) != p[0] || unpackY(k) != p[1] || unpackZ(k) != p[2]) return false;
        }
        return true;
    }

    /** True when standing at p1 with yawP1 puts p2 directly in front, and vice versa. */
    static boolean facesEachOther(DuelMap m) {
        return looksAt(m.p1x(), m.p1z(), m.yawP1(), m.p2x(), m.p2z())
            && looksAt(m.p2x(), m.p2z(), m.yawP2(), m.p1x(), m.p1z());
    }

    private static boolean looksAt(double fromX, double fromZ, float yaw, double toX, double toZ) {
        double rad = Math.toRadians(yaw);
        double dirX = -Math.sin(rad), dirZ = Math.cos(rad);
        double dx = toX - fromX, dz = toZ - fromZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-6) return false;
        return (dirX * dx + dirZ * dz) / len > 0.999;
    }
}
