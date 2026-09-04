package net.communitysmp.core;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Colosseum arenas: one private template world per arena, a committed snapshot of it on disk, and a
 *  disposable clone of that snapshot for every encounter.
 *
 *  This is the duel engine's architecture, deliberately, because it is the one that has been proven on this
 *  server: build in a template, commit it atomically, and never let a fight touch anything but a throwaway
 *  copy. The filesystem primitives are not re-implemented here -- {@link DuelMapService#copyWorldFolder},
 *  {@link DuelMapService#deleteQuietly}, {@link DuelMapService#deleteWithRetry},
 *  {@link DuelMapService#stripIdentity} and {@link DuelMapService#customWorldDir()} are called directly, so
 *  everything that was learned the hard way about Paper's world layout, duplicate-UUID refusals and Windows
 *  file handles has exactly one implementation.
 *
 *  What is deliberately NOT shared is identity. Colosseum templates are their own worlds under their own
 *  prefix, in their own snapshot directory, in their own registry. Editing a Colosseum arena cannot alter a
 *  duel map and vice versa, no Colosseum world is ever offered to duel matchmaking, and the duel chest-loot
 *  roller never sees a Colosseum instance -- which matters, because it would be a free item printer inside
 *  a paid fight.
 *
 *  An arena is created the first time from a duel map's COMMITTED SNAPSHOT (`base-template` in
 *  colosseum.yml). That is a read of the duel snapshot and a write into the Colosseum's own directory --
 *  the duel map is not modified, moved or locked, and after that first copy it is never read again. */
final class ColosseumArenas {

    static final String TEMPLATE_PREFIX = "colo_tpl_";
    static final String INSTANCE_PREFIX = "colo_inst_";

    record Arena(String key, String name, String baseTemplate,
                 double px, double py, double pz,
                 double bx, double by, double bz,
                 Double sx, Double sy, Double sz,
                 int[] bounds) {

        String templateWorld() { return TEMPLATE_PREFIX + key; }

        /** The player always spawns facing the boss, derived rather than stored, so moving one spawn point
         *  can never leave somebody staring at a wall while something walks up behind them. */
        float playerYaw() { return yawTowards(px, pz, bx, bz); }
        float bossYaw() { return yawTowards(bx, bz, px, pz); }

        static float yawTowards(double fromX, double fromZ, double toX, double toZ) {
            return (float) Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
        }

        boolean inBounds(double x, double y, double z) {
            if (bounds == null) return true;
            return x >= bounds[0] && x <= bounds[3] + 1 && y >= bounds[1] && y <= bounds[4] && z >= bounds[2] && z <= bounds[5] + 1;
        }

        Location playerSpawn(World world) { return standable(world, px, py, pz, playerYaw()); }
        Location bossSpawn(World world) { return standable(world, bx, by, bz, bossYaw()); }
        Location spectatorSpawn(World world) {
            if (sx != null && sy != null && sz != null) return new Location(world, sx, sy, sz, playerYaw(), 20f);
            return new Location(world, (px + bx) / 2, Math.max(py, by) + 12, (pz + bz) / 2, playerYaw(), 40f);
        }

        /** The configured point, lifted clear of the floor if it happens to be inside one. Spawn points get
         *  recorded both ways in practice -- the block you stand IN and the block you stand ON -- and this
         *  lands in the same place under either reading rather than burying somebody in the arena floor. */
        static Location standable(World world, double x, double y, double z, float yaw) {
            if (world == null) return new Location(null, x, y, z, yaw, 0f);
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y);
            for (int lift = 0; lift <= 6; lift++) {
                int at = by + lift;
                if (at + 1 >= world.getMaxHeight()) break;
                if (!world.getBlockAt(bx, at, bz).getType().isSolid() && !world.getBlockAt(bx, at + 1, bz).getType().isSolid())
                    return new Location(world, x, at + (lift == 0 ? y - by : 0), z, yaw, 0f);
            }
            return new Location(world, x, y, z, yaw, 0f);
        }
    }

    private final SMPCore plugin;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    /** instance world name -> arena key, for the worlds this process created. */
    private final Map<String, String> liveInstances = new LinkedHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();
    /** Last measured preparation cost, for /ashfall colosseum instances. */
    private final Map<String, long[]> prepStats = new LinkedHashMap<>();

    ColosseumArenas(SMPCore plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ registry

    void reload(YamlConfiguration config) {
        arenas.clear();
        org.bukkit.configuration.ConfigurationSection root = config.getConfigurationSection("arenas");
        if (root == null) return;
        for (String raw : root.getKeys(false)) {
            org.bukkit.configuration.ConfigurationSection s = root.getConfigurationSection(raw);
            if (s == null) continue;
            String key = raw.toLowerCase(Locale.ROOT);
            List<Double> p = s.getDoubleList("player-spawn"), b = s.getDoubleList("boss-spawn"), spec = s.getDoubleList("spectator");
            if (p.size() < 3 || b.size() < 3) {
                plugin.getLogger().warning("[colosseum] arena " + key + " is missing player-spawn/boss-spawn; skipped.");
                continue;
            }
            List<Integer> box = s.getIntegerList("bounds");
            int[] bounds = box.size() < 6 ? null : new int[]{
                    Math.min(box.get(0), box.get(3)), Math.min(box.get(1), box.get(4)), Math.min(box.get(2), box.get(5)),
                    Math.max(box.get(0), box.get(3)), Math.max(box.get(1), box.get(4)), Math.max(box.get(2), box.get(5))};
            arenas.put(key, new Arena(key, s.getString("name", key), s.getString("base-template", "arena100"),
                    p.get(0), p.get(1), p.get(2), b.get(0), b.get(1), b.get(2),
                    spec.size() >= 3 ? spec.get(0) : null, spec.size() >= 3 ? spec.get(1) : null, spec.size() >= 3 ? spec.get(2) : null,
                    bounds));
        }
    }

    Collection<Arena> arenas() { return arenas.values(); }
    Arena arena(String key) { return key == null ? null : arenas.get(key.toLowerCase(Locale.ROOT)); }
    List<String> arenaKeys() { return new ArrayList<>(arenas.keySet()); }

    // ------------------------------------------------------------------ filesystem

    private DuelMapService fs() { return plugin.duelMaps(); }
    private File worldDir() { return fs().customWorldDir(); }

    /** Snapshots live in the plugin's own data folder, next to (but not inside) the duel ones, so Paper
     *  never sees a Colosseum snapshot as a world and nothing can accidentally load one. */
    File snapshotRoot() { return new File(plugin.getDataFolder(), "colosseum-templates"); }
    File snapshotOf(Arena arena) { return new File(snapshotRoot(), arena.key()); }
    boolean hasSnapshot(Arena arena) { return arena != null && regionFiles(snapshotOf(arena)).length > 0; }

    /*  ---------------------------------------------------------------------------------------------------
     *  ONE SNAPSHOT PER ENVIRONMENT, because a chunk is not shaped the same in both.
     *
     *  The committed snapshot is built in a NORMAL world, which is 24 block sections tall. A Nether world is
     *  16. Cloning the Overworld snapshot straight into a Nether instance therefore hands Paper chunks whose
     *  light arrays are longer than the world can hold, and it says so -- "Failed to parse light data",
     *  ArrayIndexOutOfBounds, once per chunk, 84 chunks per instance, roughly 1,300 log lines and a full
     *  re-light every time somebody fought the one boss that uses a Nether arena. Measured on staging: 615
     *  ms to prepare that instance against ~360 ms for an Overworld one.
     *
     *  So a Nether arena gets its own snapshot, converted ONCE from the committed one by opening it as a
     *  Nether world, letting the engine re-light it that single time, and saving the result. Every instance
     *  after that clones chunks that already fit. The committed snapshot is never modified, and committing a
     *  new one drops the derived copies so they cannot go stale. */
    File snapshotOf(Arena arena, World.Environment environment) {
        if (arena == null) return null;
        if (environment == null || environment == World.Environment.NORMAL) return snapshotOf(arena);
        return new File(snapshotRoot(), arena.key() + "__" + environment.name().toLowerCase(Locale.ROOT));
    }

    private boolean hasSnapshot(Arena arena, World.Environment environment) {
        File folder = snapshotOf(arena, environment);
        return folder != null && regionFiles(folder).length > 0;
    }

    /** Builds the environment-specific snapshot if it is not there yet. Main thread, once per arena per
     *  environment, and never again unless the arena is re-committed. Returns the folder to clone from --
     *  the committed one if conversion was not needed or could not be done, which is always safe: the worst
     *  case is the behaviour this exists to improve on. */
    synchronized File snapshotForCloning(Arena arena, World.Environment environment) {
        File direct = snapshotOf(arena, environment);
        if (environment == null || environment == World.Environment.NORMAL || direct == null) return snapshotOf(arena);
        if (hasSnapshot(arena, environment)) return direct;
        String working = "colo_conv_" + arena.key() + "_" + environment.name().toLowerCase(Locale.ROOT);
        File workingFolder = new File(worldDir(), working);
        plugin.getLogger().info("[colosseum] converting the " + arena.key() + " snapshot for " + environment
                + " (once; the light-data warnings below are the conversion and will not repeat).");
        try {
            fs().deleteQuietly(workingFolder);
            fs().copyWorldFolder(snapshotOf(arena).toPath(), workingFolder.toPath());
            DuelMapService.stripIdentity(workingFolder);
            World world = open(working, environment);
            if (world == null) { fs().deleteQuietly(workingFolder); return snapshotOf(arena); }
            applyWorldRules(world, false);
            /*  Wider than the play area on purpose.
             *
             *  Converting only the arena's own chunks left seven still in the old shape -- the world spawn,
             *  and the ring the engine pulls in around the edges to light them -- and those seven then threw
             *  on every instance load afterwards. The margin covers them, and converting a few extra chunks
             *  once costs nothing next to re-lighting them forever. */
            int[] range = chunkRange(arena);
            for (int cx = range[0] - 2; cx <= range[2] + 2; cx++)
                for (int cz = range[1] - 2; cz <= range[3] + 2; cz++)
                    world.getChunkAt(cx, cz).load(true);
            world.getChunkAt(0, 0).load(true);
            world.save();
            if (!Bukkit.unloadWorld(world, true)) {
                plugin.getLogger().warning("[colosseum] conversion world " + working + " would not unload; using the committed snapshot.");
                return snapshotOf(arena);
            }
            File tmp = new File(snapshotRoot(), direct.getName() + ".tmp");
            fs().deleteQuietly(tmp);
            fs().copyWorldFolder(workingFolder.toPath(), tmp.toPath());
            String bad = validateSnapshot(tmp, arena);
            if (bad != null) throw new IOException(bad);
            fs().deleteQuietly(direct);
            if (!tmp.renameTo(direct)) throw new IOException("the converted snapshot could not be published");
            plugin.getLogger().info("[colosseum] " + environment + " snapshot for " + arena.key() + " is ready; "
                    + "instances of it will no longer re-light on load.");
            return direct;
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().warning("[colosseum] " + environment + " conversion failed for " + arena.key()
                    + " (" + error + "); falling back to the committed snapshot.");
            fs().deleteQuietly(direct);
            return snapshotOf(arena);
        } finally {
            fs().deleteQuietly(workingFolder);
        }
    }

    /** Derived snapshots are caches of a particular commit, so a new commit invalidates them. */
    private void dropDerivedSnapshots(Arena arena) {
        for (World.Environment environment : World.Environment.values()) {
            if (environment == World.Environment.NORMAL) continue;
            File derived = snapshotOf(arena, environment);
            if (derived != null && derived.exists()) {
                fs().deleteQuietly(derived);
                plugin.getLogger().info("[colosseum] dropped the stale " + environment + " snapshot for " + arena.key() + ".");
            }
        }
    }

    private static File[] regionFiles(File worldFolder) {
        File[] files = new File(worldFolder, "region").listFiles((d, n) -> n.endsWith(".mca") && new File(d, n).length() > 0);
        return files == null ? new File[0] : files;
    }

    /** Every directory a Colosseum world could be sitting in, so orphan recovery cannot miss one because
     *  Paper's layout changed underneath it. */
    private List<File> worldSearchPath() {
        LinkedHashSet<File> out = new LinkedHashSet<>();
        out.add(worldDir());
        out.add(Bukkit.getWorldContainer());
        List<World> worlds = Bukkit.getWorlds();
        if (!worlds.isEmpty() && worlds.get(0).getWorldFolder() != null) {
            File[] namespaces = new File(worlds.get(0).getWorldFolder(), "dimensions").listFiles(File::isDirectory);
            if (namespaces != null) out.addAll(Arrays.asList(namespaces));
        }
        return new ArrayList<>(out);
    }

    String describeSnapshot(Arena arena) {
        File[] files = regionFiles(snapshotOf(arena));
        long bytes = 0;
        for (File f : files) bytes += f.length();
        return files.length + " region file(s), " + (bytes / 1024 / 1024) + " MB";
    }

    // ------------------------------------------------------------------ template workspace

    /** Opens the persistent EDITABLE workspace for an arena -- the world an admin builds in.
     *
     *  A workspace that is missing or empty is materialised from the committed snapshot, and a first-ever
     *  create is seeded from the duel map named by `base-template`. A workspace that already holds an arena
     *  is never overwritten: an admin's unsaved building is not something to destroy on their behalf. */
    World workspace(Arena arena, java.util.function.Consumer<String> feedback) {
        if (arena == null) return null;
        World live = Bukkit.getWorld(arena.templateWorld());
        if (live != null) {
            if (holdsArena(live, arena) || !hasSnapshot(arena)) { loadPlayArea(live, arena); return live; }
            feedback.accept("The open workspace for " + arena.name() + " is empty; rebuilding it from the committed snapshot.");
            for (Player inside : new ArrayList<>(live.getPlayers())) inside.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            if (!Bukkit.unloadWorld(live, false)) return live;
            fs().deleteQuietly(live.getWorldFolder());
        }
        File folder = new File(worldDir(), arena.templateWorld());
        if (!hasArenaRegion(folder, arena)) {
            if (hasSnapshot(arena)) {
                feedback.accept("Materialising the " + arena.name() + " workspace from its committed snapshot (" + describeSnapshot(arena) + ")...");
                if (!copyInto(snapshotOf(arena), folder)) return null;
            } else if (!seedFromDuelTemplate(arena, folder, feedback)) {
                feedback.accept("Starting " + arena.name() + " as an empty void workspace.");
            }
        }
        World world = open(arena.templateWorld(), World.Environment.NORMAL);
        if (world == null) return null;
        File opened = world.getWorldFolder();
        if (opened != null && opened.getParentFile() != null && !opened.getParentFile().equals(worldDir())) {
            /** Paper opened a different directory from the one written to, so the layout assumption was
             *  wrong and the workspace would be empty. Correct and rebuild, exactly as the duel engine does. */
            plugin.getLogger().warning("[colosseum] expected workspace at " + worldDir().getAbsolutePath()
                    + " but Paper opened " + opened.getAbsolutePath() + "; correcting and rebuilding.");
            Bukkit.unloadWorld(world, false);
            fs().deleteQuietly(opened);
            fs().deleteQuietly(folder);
            File corrected = new File(worldDir(), arena.templateWorld());
            if (hasSnapshot(arena) && !copyInto(snapshotOf(arena), corrected)) return null;
            world = open(arena.templateWorld(), World.Environment.NORMAL);
            if (world == null) return null;
        }
        applyWorldRules(world, true);
        loadPlayArea(world, arena);
        return world;
    }

    /** The one and only read of a duel map, and it happens once in an arena's life: the first
     *  `/ashfall colosseum create` copies the named duel map's COMMITTED SNAPSHOT as a starting point. The
     *  duel snapshot is opened read-only and copied out; nothing is written back to it. */
    private boolean seedFromDuelTemplate(Arena arena, File target, java.util.function.Consumer<String> feedback) {
        DuelMapService.DuelMap base = fs().map(arena.baseTemplate());
        if (base == null || !fs().hasSnapshot(base)) {
            plugin.getLogger().info("[colosseum] no committed duel snapshot '" + arena.baseTemplate() + "' to seed " + arena.key() + " from.");
            return false;
        }
        feedback.accept("Seeding " + arena.name() + " from the committed " + base.name() + " duel snapshot (a copy - the duel map is not modified).");
        if (!copyInto(fs().snapshotOf(base), target)) return false;
        plugin.getLogger().info("[colosseum] seeded arena " + arena.key() + " from duel snapshot " + base.key());
        return true;
    }

    private boolean copyInto(File source, File target) {
        fs().deleteQuietly(target);
        try { fs().copyWorldFolder(source.toPath(), target.toPath()); }
        catch (IOException e) {
            plugin.getLogger().warning("[colosseum] world copy failed (" + source.getName() + " -> " + target.getName() + "): " + e.getMessage());
            fs().deleteQuietly(target);
            return false;
        }
        DuelMapService.stripIdentity(target);
        return true;
    }

    /*  The environment is chosen HERE, when the world is created, and never afterwards.
     *
     *  A boss whose entity is Nether-native needs a Nether world or the engine works against it -- a Wither
     *  Skeleton burns in Overworld daylight, a Piglin Brute zombifies in fifteen seconds. Those are engine
     *  rules, not plugin rules, and no amount of gamerule pinning makes them go away; it only hides them
     *  until somebody edits the arena's time of day.
     *
     *  What does NOT change is the arena. The same committed snapshot, the same blocks, the same bounds,
     *  barriers and spawns -- because terrain comes from the void generator and the region files, not from
     *  the environment. Nothing Nether-shaped is generated around it. And the environment is passed to the
     *  WorldCreator rather than mutated afterwards, because there is no safe way to change a loaded world's
     *  dimension underneath the entities standing in it. */
    private World open(String name, World.Environment environment) {
        return new WorldCreator(name).generator(new ArenaService.VoidGenerator())
                .type(WorldType.FLAT).environment(environment == null ? World.Environment.NORMAL : environment).createWorld();
    }

    boolean hasArenaRegion(File folder, Arena arena) {
        if (folder == null || !folder.isDirectory()) return false;
        File region = new File(new File(folder, "region"), arenaRegionName(arena));
        return region.isFile() && region.length() > 0;
    }

    String arenaRegionName(Arena arena) {
        int cx = (int) Math.floor((arena.px() + arena.bx()) / 2.0) >> 4, cz = (int) Math.floor((arena.pz() + arena.bz()) / 2.0) >> 4;
        return "r." + (cx >> 5) + "." + (cz >> 5) + ".mca";
    }

    /** True when a LOADED world really has the arena -- on disk, or standing in memory because an admin has
     *  been building and has not saved yet. */
    boolean holdsArena(World world, Arena arena) {
        if (world == null) return false;
        if (hasArenaRegion(world.getWorldFolder(), arena)) return true;
        int x = (int) Math.floor(arena.px()), y = (int) Math.floor(arena.py()), z = (int) Math.floor(arena.pz());
        world.getChunkAt(x >> 4, z >> 4).load(true);
        int floor = Math.max(world.getMinHeight(), y - 12), ceiling = Math.min(world.getMaxHeight() - 1, y + 12);
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int at = floor; at <= ceiling; at++)
                    if (!world.getBlockAt(x + dx, at, z + dz).getType().isAir()) return true;
        return false;
    }

    int[] chunkRange(Arena arena) {
        if (arena.bounds() != null) {
            int[] b = arena.bounds();
            return new int[]{b[0] >> 4, b[2] >> 4, b[3] >> 4, b[5] >> 4};
        }
        int cx = (int) Math.floor((arena.px() + arena.bx()) / 2.0) >> 4, cz = (int) Math.floor((arena.pz() + arena.bz()) / 2.0) >> 4;
        return new int[]{cx - 8, cz - 8, cx + 8, cz + 8};
    }

    int chunkCount(Arena arena) {
        int[] c = chunkRange(arena);
        return (c[2] - c[0] + 1) * (c[3] - c[1] + 1);
    }

    void loadPlayArea(World world, Arena arena) {
        int[] c = chunkRange(arena);
        for (int cx = c[0]; cx <= c[2]; cx++) for (int cz = c[1]; cz <= c[3]; cz++) world.getChunkAt(cx, cz).load(true);
    }

    // ------------------------------------------------------------------ committing

    /** Commits the live template as the snapshot future encounters clone.
     *
     *  The write barrier is a full world unload with save=true, because World.save() only QUEUES the writes;
     *  publication is a validate-then-rename, so a save that dies at any point leaves the previous good
     *  snapshot exactly where it was and encounters keep using it. */
    synchronized String commitTemplate(Arena arena) {
        World tpl = Bukkit.getWorld(arena.templateWorld());
        File source;
        if (tpl != null) {
            Location out = Bukkit.getWorlds().get(0).getSpawnLocation();
            for (Player p : new ArrayList<>(tpl.getPlayers())) p.teleport(out);
            source = tpl.getWorldFolder();
            tpl.save();
            if (!Bukkit.unloadWorld(tpl, true)) {
                plugin.getLogger().warning("[colosseum] template " + arena.key() + " would not unload; snapshot NOT updated.");
                return "The template world would not unload, so nothing was committed. The previous snapshot is unchanged.";
            }
        } else {
            source = new File(worldDir(), arena.templateWorld());
            if (!source.isDirectory())
                return "There is no template world for " + arena.key() + " yet. Run /ashfall colosseum create " + arena.key() + " first.";
        }
        File root = snapshotRoot();
        root.mkdirs();
        File live = snapshotOf(arena), tmp = new File(root, arena.key() + ".tmp"), parked = new File(root, arena.key() + ".old");
        String result;
        try {
            fs().deleteQuietly(tmp); fs().deleteQuietly(parked);
            fs().copyWorldFolder(source.toPath(), tmp.toPath());
            String bad = validateSnapshot(tmp, arena);
            if (bad != null) throw new IOException(bad);
            if (live.exists() && !live.renameTo(parked)) throw new IOException("the previous snapshot could not be parked");
            if (!tmp.renameTo(live)) {
                if (parked.exists()) parked.renameTo(live);
                throw new IOException("the new snapshot could not be published");
            }
            fs().deleteQuietly(parked);
            dropDerivedSnapshots(arena);
            result = "Committed " + arena.name() + " (" + describeSnapshot(arena) + "). Future encounters use it; running encounters keep the copy they started with.";
            plugin.getLogger().info("[colosseum] committed snapshot for " + arena.key() + " -> " + live.getAbsolutePath());
        } catch (IOException e) {
            fs().deleteQuietly(tmp);
            plugin.getLogger().warning("[colosseum] commit failed for " + arena.key() + ": " + e.getMessage());
            result = "Save FAILED (" + e.getMessage() + "). The previous snapshot is untouched and still in use.";
        } finally {
            workspace(arena, line -> { });
        }
        return result;
    }

    /** A snapshot is only publishable if it is a world AND it actually contains the region the arena lives
     *  in. The second half is the point: a perfectly valid but arena-less world folder is exactly the
     *  failure that puts a paying player in an empty void. */
    private String validateSnapshot(File folder, Arena arena) {
        if (!new File(folder, "region").isDirectory()) return "the snapshot has no region directory";
        File[] present = regionFiles(folder);
        if (present.length == 0) return "the snapshot contains no region files at all";
        String needed = arenaRegionName(arena);
        for (File f : present) if (f.getName().equals(needed)) return null;
        return "the arena's own region file " + needed + " was never written - the template's chunks never reached disk";
    }

    boolean saveTemplate(Arena arena) {
        String result = commitTemplate(arena);
        return result != null && result.startsWith("Committed");
    }

    // ------------------------------------------------------------------ instances

    /** Prepares an encounter's own world: an async copy of the immutable committed snapshot, then the
     *  arena's chunks pulled in a slice at a time. Neither half runs long on the main thread -- a fight
     *  starting is not worth a visible freeze for everybody else on the server.
     *
     *  {@code done} is called on the main thread with the ready world, or null if anything failed. */
    void prepareInstance(Arena arena, java.util.function.BiConsumer<World, long[]> done) {
        prepareInstance(arena, World.Environment.NORMAL, done);
    }

    void prepareInstance(Arena arena, World.Environment environment, java.util.function.BiConsumer<World, long[]> done) {
        if (arena == null) { done.accept(null, null); return; }
        if (!hasSnapshot(arena)) { done.accept(null, null); return; }
        long began = System.currentTimeMillis();
        String name = INSTANCE_PREFIX + arena.key() + "_" + System.currentTimeMillis() + "_" + counter.incrementAndGet();
        File dir = worldDir();
        File target = new File(dir, name);
        /** Resolved on the main thread, before the copy, because the first Nether instance of an arena has a
         *  world to convert and that is not something to be doing from a worker. */
        File source = snapshotForCloning(arena, environment);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean copied;
            try { fs().deleteQuietly(target); fs().copyWorldFolder(source.toPath(), target.toPath()); copied = true; }
            catch (IOException e) { plugin.getLogger().warning("[colosseum] clone failed for " + arena.key() + ": " + e.getMessage()); copied = false; }
            long copiedAt = System.currentTimeMillis();
            boolean ok = copied;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!ok) { fs().deleteQuietly(target); done.accept(null, null); return; }
                DuelMapService.stripIdentity(target);
                World instance = open(name, environment);
                if (instance == null) { fs().deleteQuietly(target); done.accept(null, null); return; }
                File opened = instance.getWorldFolder();
                if (opened != null && opened.getParentFile() != null && !opened.getParentFile().equals(dir)) {
                    plugin.getLogger().warning("[colosseum] expected " + dir.getAbsolutePath() + " but Paper opened "
                            + opened.getAbsolutePath() + "; dropping this clone and correcting the layout.");
                    Bukkit.unloadWorld(instance, false);
                    fs().deleteQuietly(opened); fs().deleteQuietly(target);
                    done.accept(null, null);
                    return;
                }
                liveInstances.put(name, arena.key());
                applyWorldRules(instance, false);
                if (instance.getEnvironment() != (environment == null ? World.Environment.NORMAL : environment))
                    plugin.getLogger().warning("[colosseum] " + name + " opened as " + instance.getEnvironment()
                            + " but " + environment + " was asked for.");
                loadPlayAreaSliced(instance, arena, () -> {
                    purge(instance);
                    long[] stats = {copiedAt - began, System.currentTimeMillis() - began, chunkCount(arena)};
                    prepStats.put(name, stats);
                    plugin.getLogger().info("[colosseum] instance " + name + " ready in " + stats[1] + " ms (copy "
                            + stats[0] + " ms, " + stats[2] + " chunks)");
                    done.accept(instance, stats);
                });
            });
        });
    }

    private void loadPlayAreaSliced(World world, Arena arena, Runnable done) {
        int[] c = chunkRange(arena);
        List<int[]> chunks = new ArrayList<>();
        for (int cx = c[0]; cx <= c[2]; cx++) for (int cz = c[1]; cz <= c[3]; cz++) chunks.add(new int[]{cx, cz});
        int perTick = Math.max(1, plugin.colosseum() == null ? 24 : plugin.colosseum().chunksPerTick());
        java.util.Iterator<int[]> it = chunks.iterator();
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                for (int i = 0; i < perTick && it.hasNext(); i++) { int[] at = it.next(); world.getChunkAt(at[0], at[1]).load(true); }
                if (!it.hasNext()) { cancel(); done.run(); }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Unloads and deletes an instance world entirely. Anybody still inside is moved out first -- an
     *  unloading world with a player in it is how somebody ends up at 0,0 in a world that no longer exists. */
    void destroyInstance(World world, Location fallback) {
        if (world == null || !world.getName().startsWith(INSTANCE_PREFIX)) return;
        long began = System.currentTimeMillis();
        for (Player player : new ArrayList<>(world.getPlayers()))
            player.teleport(fallback != null ? fallback : Bukkit.getWorlds().get(0).getSpawnLocation());
        /** Everything the fight created goes with the world, but remove it explicitly first so a boss or a
         *  projectile cannot be carried into another world by any surviving reference. */
        purge(world);
        String name = world.getName();
        File folder = world.getWorldFolder();
        boolean unloaded = Bukkit.unloadWorld(world, false);
        liveInstances.remove(name);
        prepStats.remove(name);
        if (!unloaded) plugin.getLogger().warning("[colosseum] " + name + " refused to unload; it will be swept shortly.");
        fs().deleteWithRetry(folder, 30);
        plugin.getLogger().info("[colosseum] instance " + name + " dropped in " + (System.currentTimeMillis() - began) + " ms");
    }

    /** Startup recovery. Every instance world is disposable by definition, so anything on disk at boot is
     *  the residue of a crash. Half-written snapshots go too, and a parked snapshot with no live sibling
     *  means the atomic swap was interrupted between its two renames -- that one is put back. */
    int cleanupOrphans() {
        int removed = 0;
        for (World world : new ArrayList<>(Bukkit.getWorlds()))
            if (world.getName().startsWith(INSTANCE_PREFIX)) {
                File folder = world.getWorldFolder();
                for (Player p : new ArrayList<>(world.getPlayers())) p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
                Bukkit.unloadWorld(world, false);
                fs().deleteQuietly(folder);
                removed++;
            }
        for (File container : worldSearchPath()) {
            File[] children = container.listFiles();
            if (children == null) continue;
            for (File child : children)
                if (child.isDirectory() && child.getName().startsWith(INSTANCE_PREFIX)) { fs().deleteQuietly(child); removed++; }
        }
        File[] staging = snapshotRoot().listFiles((d, n) -> n.endsWith(".tmp"));
        if (staging != null) for (File f : staging) fs().deleteQuietly(f);
        File[] parked = snapshotRoot().listFiles((d, n) -> n.endsWith(".old"));
        if (parked != null) for (File f : parked) {
            File live = new File(snapshotRoot(), f.getName().substring(0, f.getName().length() - 4));
            if (!live.exists() && f.renameTo(live)) plugin.getLogger().warning("[colosseum] restored parked snapshot " + live.getName());
            else fs().deleteQuietly(f);
        }
        liveInstances.clear();
        prepStats.clear();
        if (removed > 0) plugin.getLogger().info("[colosseum] removed " + removed + " orphaned instance(s)");
        return removed;
    }

    /** Windows keeps region-file handles mapped after an unload, so a folder is often still undeletable
     *  minutes after its encounter ended. Retried on a timer rather than leaked for the session. */
    int sweepDetached() {
        int removed = 0;
        for (File container : worldSearchPath()) {
            File[] children = container.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (!child.isDirectory() || !child.getName().startsWith(INSTANCE_PREFIX)) continue;
                if (liveInstances.containsKey(child.getName())) continue;
                if (Bukkit.getWorld(child.getName()) != null) continue;
                fs().deleteQuietly(child);
                if (!child.exists()) removed++;
            }
        }
        if (removed > 0) plugin.getLogger().info("[colosseum] swept " + removed + " detached instance folder(s)");
        return removed;
    }

    List<String> instanceNames() { return new ArrayList<>(liveInstances.keySet()); }
    int liveInstanceCount() { return liveInstances.size(); }
    boolean isInstance(World world) { return world != null && liveInstances.containsKey(world.getName()); }
    boolean isColosseumWorld(World world) {
        return world != null && (world.getName().startsWith(INSTANCE_PREFIX) || world.getName().startsWith(TEMPLATE_PREFIX));
    }
    long[] prepStats(String instance) { return prepStats.get(instance); }

    Arena arenaOfWorld(World world) {
        if (world == null) return null;
        String key = liveInstances.get(world.getName());
        return key == null ? null : arenas.get(key);
    }

    // ------------------------------------------------------------------ world hygiene

    /** Mob spawning is off permanently in both templates and instances: a Colosseum arena contains exactly
     *  the boss that was paid for and nothing else. KEEP_INVENTORY is on because a Colosseum death must
     *  never drop the player's real belongings -- the death is intercepted, but the gamerule means even an
     *  interception that somehow failed cannot scatter somebody's netherite across a world about to be
     *  deleted. */
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
        world.setGameRule(GameRule.DO_INSOMNIA, false);
        world.setGameRule(GameRule.NATURAL_REGENERATION, true);
        /*  Night, and no weather, in an Overworld arena.
         *
         *  The Nether has neither a sky nor weather, so both calls are meaningless there and setStorm in
         *  particular is a no-op that some versions log about. More to the point, the pinned night is what
         *  used to stop a Wither Skeleton igniting in an Overworld instance -- an incidental protection that
         *  a single edit to this line would have removed. Nether-native bosses now run in a Nether
         *  environment instead, so the protection is structural and this is only about lighting. */
        if (world.getEnvironment() == World.Environment.NORMAL) {
            world.setTime(18000);
            world.setStorm(false);
        }
    }

    /** Clears living entities and loose items -- anything that drifted in, was cloned along with the
     *  template, or was left by a previous encounter. Build furniture (item frames, armour stands) is
     *  deliberately kept so a decorated arena survives. */
    void purge(World world) {
        if (world == null) return;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Player) continue;
            if (entity instanceof LivingEntity || entity.getType() == org.bukkit.entity.EntityType.ITEM
                    || entity instanceof org.bukkit.entity.Projectile || entity instanceof org.bukkit.entity.ExperienceOrb)
                entity.remove();
        }
    }

    /** Infrastructure that may never be removed by anybody, under any circumstance -- mining the boundary
     *  out of an arena is simply leaving it. */
    static boolean infrastructure(Material m) {
        return switch (m) {
            case BARRIER, BEDROCK, COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK,
                 STRUCTURE_BLOCK, STRUCTURE_VOID, JIGSAW, LIGHT, END_PORTAL_FRAME, END_PORTAL, NETHER_PORTAL -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ self test

    boolean selfTest() {
        if (arenas.isEmpty()) return false;
        for (Arena a : arenas.values()) {
            if (a.bounds() == null) return false;
            if (!a.inBounds(a.px(), a.py(), a.pz()) || !a.inBounds(a.bx(), a.by(), a.bz())) return false;
            /** The two spawns must be apart, and the player must be looking at where the boss appears. */
            if (Math.abs(a.px() - a.bx()) + Math.abs(a.pz() - a.bz()) < 4) return false;
            if (!looksAt(a.px(), a.pz(), a.playerYaw(), a.bx(), a.bz())) return false;
            if (!looksAt(a.bx(), a.bz(), a.bossYaw(), a.px(), a.pz())) return false;
            if (chunkCount(a) > 400) return false;
        }
        return true;
    }

    static boolean looksAt(double fromX, double fromZ, float yaw, double toX, double toZ) {
        double rad = Math.toRadians(yaw), dirX = -Math.sin(rad), dirZ = Math.cos(rad);
        double dx = toX - fromX, dz = toZ - fromZ, len = Math.sqrt(dx * dx + dz * dz);
        return len >= 1.0e-6 && (dirX * dx + dirZ * dz) / len > 0.999;
    }
}
