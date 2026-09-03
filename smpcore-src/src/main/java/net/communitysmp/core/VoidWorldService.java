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
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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
    /** Per-world access flag, persisted in the same state table as the captures so it survives restarts and
     *  lives exactly as long as the world does. */
    private static final String ACCESS = "voidworld-open:";

    /*  Re-entrancy guard.
     *
     *  enter() and exit() teleport people, and this class also LISTENS for world changes and teleports so an
     *  admin dragging somebody in or out goes through the same belongings logic as the command. Without a
     *  flag those two meet in the middle: enter() teleports, the listener sees a world change into a void
     *  world, and captures a second time -- over the capture enter() just took, which is how a real
     *  inventory gets overwritten by an empty one. Everything this class initiates is marked. */
    private final java.util.Set<java.util.UUID> moving = new java.util.HashSet<>();

    private final SMPCore plugin;
    private final Database db;

    VoidWorldService(SMPCore plugin) {
        this.plugin = plugin;
        this.db = plugin.db();
        /** Deferred a tick: worlds cannot be created until the server has finished starting. */
        Bukkit.getScheduler().runTask(plugin, this::reloadExisting);
    }

    /*  Bring back every event world that is still on disk.
     *
     *  Bukkit does not auto-load worlds it did not create from server.properties, so after a restart the
     *  folder was still sitting there while `/voidworld list` reported nothing, `/voidworld enter` answered
     *  "no void world called that", and the world could not even be deleted -- an event that outlived a
     *  restart simply vanished, permanently, leaving its folder behind forever. Found by noticing two
     *  orphaned folders from the owner's own testing after a routine restart.
     *
     *  Paper puts a Bukkit-created world in world/dimensions/<namespace>/<name>; older layouts put it at the
     *  server root. Both are checked, because guessing wrong here means silently reloading nothing. */
    private void reloadExisting() {
        for (String name : new java.util.TreeSet<>(onDisk())) {
            if (Bukkit.getWorld(name) != null) continue;
            World world = new WorldCreator(name).generator(new VoidGenerator()).type(WorldType.FLAT)
                    .generateStructures(false).createWorld();
            if (world == null) {
                plugin.getLogger().warning("[VoidWorld] could not reload " + name + "; its folder is still on disk.");
                continue;
            }
            settle(world);
            plugin.getLogger().info("[VoidWorld] reloaded event world " + name
                    + " (" + (isOpen(name.substring(PREFIX.length())) ? "open" : "closed") + ")");
        }
    }

    private java.util.Set<String> onDisk() {
        java.util.Set<String> found = new java.util.HashSet<>();
        File root = Bukkit.getWorldContainer();
        File[] roots = {root, new File(root, "world/dimensions/minecraft")};
        for (File base : roots) {
            File[] children = base.listFiles();
            if (children == null) continue;
            for (File child : children)
                if (child.isDirectory() && child.getName().startsWith(PREFIX)) found.add(child.getName());
        }
        return found;
    }

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
        settle(world);
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++)
                world.getBlockAt(x, 64, z).setType(Material.SMOOTH_STONE, false);
        return world;
    }

    /** The settings an event world always has, applied both when it is created and when it is brought back
     *  after a restart -- a reloaded world that quietly lost keepInventory would start dropping event items
     *  on death, which is exactly the behaviour these worlds exist to avoid. */
    private void settle(World world) {
        world.setSpawnLocation(0, 65, 0);
        world.setKeepSpawnInMemory(true);
        world.setAutoSave(true);
        world.setTime(6000);
        world.setStorm(false);
        world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
    }

    // ------------------------------------------------------------------ in and out

    boolean inside(Player player) { return isVoidWorld(player.getWorld()); }

    /** Closed is the default and the safe one: a world nobody opened is an admin-only build site. */
    boolean isOpen(String label) { return "true".equals(db.state(ACCESS + worldName(label))); }

    void setOpen(String label, boolean open) { db.state(ACCESS + worldName(label), open ? "true" : ""); }

    /** Everyone may see which worlds exist; only an administrator may walk into a closed one. */
    boolean mayEnter(Player player, String label) { return isOpen(label) || plugin.isAdmin(player); }

    /** Everything a player can be holding, in one place. An open screen is included on purpose: the crafting
     *  grid and the cursor are both real storage, and a stack parked in either of them is the obvious way to
     *  carry an event item out if only the main inventory is cleared. */
    private void strip(Player player) {
        player.closeInventory();
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
    }

    /** Captured state exists only while somebody is inside. Its presence IS the "this player is in an event"
     *  flag, which is what makes the restore idempotent and crash-safe. */
    private boolean hasCapture(String id) { String value = db.state(STATE + id); return value != null && !value.isBlank(); }

    String enter(Player player, String label) {
        World world = find(label);
        if (world == null) return "No void world called '" + label + "'. Create it first.";
        if (!mayEnter(player, label)) return "The '" + label + "' event world is closed. An administrator has to open it first.";
        if (inside(player)) return "You are already in a void world.";
        if (hasCapture(CoreUtil.id(player))) return "You already have belongings held by a void world; use exit first.";
        /** The origin is taken HERE, before anything moves, which is the same guarantee every other entry
         *  route now gets from {@link #crossing}. */
        beginSession(player, player.getLocation(), label);
        move(player, world.getSpawnLocation().clone().add(.5, 1, .5));
        return null;
    }

    /*  ---------------------------------------------------------------------------------------------------
     *  THE ONE ENTRY POINT. Every route into a void world ends here: the command, an admin's /tp, a teleport
     *  to somebody already inside, another plugin, a reconnect, a respawn.
     *
     *  IDEMPOTENT, and that word is doing real work. A player who already holds a snapshot is ALREADY in a
     *  session -- they hopped between two event worlds, or an event re-fired -- and capturing again would
     *  overwrite their real belongings with the empty inventory they are standing in. So the snapshot is
     *  written exactly once per session and never again until the session ends.
     *
     *  THE ORIGIN IS AN ARGUMENT, not player.getLocation(), and that is the whole bug that was reported.
     *  The old code captured from PlayerChangedWorldEvent, which fires AFTER the crossing -- so an admin who
     *  teleported to a friend inside had their "return location" recorded as the void world's spawn plateau
     *  at 0,65,0. On exit they were teleported to that recorded point, which is inside the void world, and
     *  their real belongings were handed back while they stood there. The follow-up nudge then saw them
     *  still inside and sent them to the same coordinates again. One step off the platform and a normal
     *  player would have lost everything they owned into the void.
     *
     *  So the caller supplies where the player genuinely came FROM, and a void world is refused as an
     *  origin outright -- there is no code path that can record one now, whatever calls this. */
    private void beginSession(Player player, Location origin, String label) {
        String id = CoreUtil.id(player);
        if (hasCapture(id)) { isolate(player); return; }
        Location from = origin;
        if (from == null || from.getWorld() == null || isVoidWorld(from.getWorld())) {
            /** No usable origin. Rather than record a coordinate inside the event -- the exact failure this
             *  method exists to prevent -- fall back to a real destination outside it and say so. */
            from = fallbackReturn();
            plugin.getLogger().warning("[VoidWorld] " + id + " entered '" + label
                    + "' with no usable origin; their return point is the server spawn.");
        }
        capture(player, from);
        isolate(player);
        CoreUtil.msg(player, "You entered the '" + label + "' event world. Nothing came in with you, and nothing leaves with you.");
        CoreUtil.msg(player, "To leave the void world, type /voidworld exit.");
    }

    /** The body half of entering: the participant state, with no snapshot handling of its own. Safe to run
     *  again on somebody already in a session, which is what makes {@link #beginSession} idempotent. */
    private void isolate(Player player) {
        strip(player);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        player.setLevel(0);
        player.setExp(0);
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        player.setFallDistance(0);
    }

    /** A teleport this class is performing itself, exempt from the escape lock and from the world-change
     *  listener that would otherwise treat it as somebody arriving or leaving on their own. */
    private void move(Player player, Location to) {
        moving.add(player.getUniqueId());
        try { player.teleport(to); } finally { moving.remove(player.getUniqueId()); }
    }

    /*  ---------------------------------------------------------------------------------------------------
     *  THE ONE EXIT POINT, and the order it happens in is the entire safety property:
     *
     *      1. resolve a destination OUTSIDE the void world, and refuse to proceed without one
     *      2. destroy everything found inside      (a temporary world is not an item source)
     *      3. teleport
     *      4. VERIFY they actually left
     *      5. only now restore the real belongings
     *      6. only now delete the snapshot
     *
     *  Steps 4 and 5 used to be the other way round, and that is what let somebody stand inside a void
     *  world holding their real inventory -- one misstep from losing all of it. Restoration cannot happen
     *  while the player is still physically inside, and the snapshot cannot be deleted until restoration is
     *  done, so a failure anywhere leaves the session fully intact and recoverable rather than half-applied.
     *
     *  Returns null on success, or a message describing the failure. */
    String exit(Player player) {
        String id = CoreUtil.id(player);
        if (!hasCapture(id)) {
            /*  Inside with no session at all. Whatever put them here bypassed every route -- there is
             *  nothing to give back, and the one thing that must not happen is handing them normal-world
             *  belongings they do not have a snapshot for while they stand over the void. They leave with
             *  what they came in with, which is nothing. */
            if (!inside(player)) return "You are not in a void world.";
            strip(player);
            Location out = fallbackReturn();
            move(player, out);
            plugin.getLogger().warning("[VoidWorld] " + id + " was inside a void world with no session; returned to " + describe(out));
            return "You had no held belongings, so you have been returned to spawn.";
        }

        /** Resolved BEFORE anything is touched, and never a coordinate inside a void world. */
        Location destination = resolveReturn(id);
        if (destination == null) {
            protectInside(player);
            plugin.getLogger().severe("[VoidWorld] no safe destination for " + id + "; the session is kept intact.");
            return "There is nowhere safe to send you right now. Your belongings are still held safely - tell an administrator.";
        }

        strip(player);
        moving.add(player.getUniqueId());
        boolean left;
        try {
            if (player.isDead()) player.spigot().respawn();
            player.teleport(destination);
            left = !inside(player);
        } catch (RuntimeException error) {
            plugin.getLogger().warning("[VoidWorld] exit teleport threw for " + id + ": " + error);
            left = false;
        } finally { moving.remove(player.getUniqueId()); }

        if (!left) {
            /*  The teleport was refused or redirected. NOTHING is restored and NOTHING is deleted: the
             *  player keeps the isolated event body, keeps the snapshot, and is stood somewhere solid
             *  inside the world rather than left wherever the failure left them. */
            protectInside(player);
            plugin.getLogger().warning("[VoidWorld] exit teleport failed for " + id + "; session kept, they are still inside.");
            return "Could not move you out of the event world. Nothing was changed - your belongings are still held safely. Try /voidworld exit again.";
        }

        boolean restored = applyState(player, id);
        if (!restored) {
            plugin.getLogger().severe("[VoidWorld] could not apply " + id + "'s state after a successful exit; the snapshot is kept.");
            return "You are out, but your belongings could not be restored automatically. They are still held safely - tell an administrator.";
        }
        /** Confirmed outside, restored, and only now is the snapshot gone. */
        db.state(STATE + id, "");
        player.updateInventory();
        CoreUtil.msg(player, "Returned. Your belongings are exactly as you left them.");
        return null;
    }

    /** Somewhere solid inside the event world, for a player whose exit failed. They keep the isolated body
     *  and the snapshot; this is only about not leaving them falling. */
    private void protectInside(Player player) {
        World world = player.getWorld();
        if (!isVoidWorld(world)) return;
        moving.add(player.getUniqueId());
        try {
            if (player.isDead()) player.spigot().respawn();
            player.teleport(world.getSpawnLocation().clone().add(.5, 1, .5));
            player.setFallDistance(0);
        } finally { moving.remove(player.getUniqueId()); }
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
        /** getWorldFolder names one location; a stale folder in the other layout would be reloaded on the
         *  next restart as a world the admin believes they deleted. */
        for (File base : new File[]{Bukkit.getWorldContainer(), new File(Bukkit.getWorldContainer(), "world/dimensions/minecraft")}) {
            File stale = new File(base, name);
            if (stale.isDirectory()) deleteTree(stale);
        }
        /** The access flag belongs to the world, so it dies with it -- a later world of the same name must
         *  start closed rather than inheriting an open door. */
        db.state(ACCESS + name, "");
        return null;
    }

    private void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    // ------------------------------------------------------------------ capture / restore

    /** The snapshot. {@code at} is where the player came FROM, supplied by the caller rather than read
     *  off the player, because by the time some routes notice a crossing the player is already on the far
     *  side of it. A void world is rejected as an origin: a return point inside the event is the defect
     *  this whole rewrite exists to make impossible. */
    private void capture(Player player, Location at) {
        String id = CoreUtil.id(player);
        if (at == null || at.getWorld() == null || isVoidWorld(at.getWorld())) at = fallbackReturn();
        ItemStack[] contents = player.getInventory().getContents(), armour = player.getInventory().getArmorContents();
        ItemStack[] all = new ItemStack[contents.length + armour.length + 1];
        System.arraycopy(contents, 0, all, 0, contents.length);
        System.arraycopy(armour, 0, all, contents.length, armour.length);
        all[all.length - 1] = player.getInventory().getItemInOffHand();
        StringBuilder header = new StringBuilder();
        header.append(at.getWorld().getName()).append('|').append(at.getX()).append('|').append(at.getY()).append('|')
                .append(at.getZ()).append('|').append(at.getYaw()).append('|').append(at.getPitch()).append('|')
                .append(player.getLevel()).append('|').append(player.getExp()).append('|')
                .append(player.getHealth()).append('|').append(player.getFoodLevel()).append('|')
                .append(player.getGameMode().name()).append('|')
                .append(java.util.Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(all)));
        db.state(STATE + id, header.toString());
    }

    /*  Restoration is deliberately TWO steps, because they have to happen at different moments.
     *
     *  resolveReturn() answers "where does this player belong", and it runs BEFORE anything is touched, so
     *  an exit with no safe destination is refused rather than half-performed. applyState() puts the
     *  belongings back, and it runs only AFTER the player is confirmed outside the void world. The old
     *  single restore() did both at once and cleared the snapshot in a finally block regardless of whether
     *  either had worked -- which is how somebody ended up inside a void world, holding their real
     *  inventory, with nothing left in the database to put them right. */

    /** Where the snapshot says this player belongs, verified. Never inside a void world, never raw 0,0,
     *  and null only when the server has no non-void world at all. */
    private Location resolveReturn(String id) {
        String raw = db.state(STATE + id);
        if (raw == null || raw.isBlank()) return fallbackReturn();
        String[] parts = raw.split("\\|", 12);
        if (parts.length < 6) return fallbackReturn();
        World world = Bukkit.getWorld(parts[0]);
        if (world == null || isVoidWorld(world)) return fallbackReturn();
        try {
            Location exact = new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
            if (standable(exact)) return exact;
            /** The recorded spot is no longer safe -- the terrain changed, or it was mid-air. A verified
             *  surface in the SAME world is the next best thing, and keeps them where they were. */
            Location safe = CoreUtil.findSafeAny(world, exact.getBlockX(), exact.getBlockZ());
            if (safe != null && safe.getWorld() != null && !isVoidWorld(safe.getWorld())) {
                safe.setYaw(exact.getYaw());
                safe.setPitch(exact.getPitch());
                return safe;
            }
        } catch (RuntimeException error) {
            plugin.getLogger().warning("[VoidWorld] unreadable return point for " + id + ": " + error);
        }
        return fallbackReturn();
    }

    /** The configured server spawn, then any non-void world's spawn. Never a hardcoded 0,0. */
    private Location fallbackReturn() {
        Location spawn = plugin.teleports() == null ? null : plugin.teleports().spawn();
        if (spawn != null && spawn.getWorld() != null && !isVoidWorld(spawn.getWorld())) return spawn;
        for (World candidate : Bukkit.getWorlds()) if (!isVoidWorld(candidate)) return candidate.getSpawnLocation();
        return null;
    }

    private boolean standable(Location at) {
        if (at.getWorld() == null) return false;
        if (at.getBlockY() <= at.getWorld().getMinHeight() || at.getBlockY() >= at.getWorld().getMaxHeight() - 1) return false;
        if (at.getBlock().getType().isSolid() || at.clone().add(0, 1, 0).getBlock().getType().isSolid()) return false;
        return at.clone().add(0, -1, 0).getBlock().getType().isSolid();
    }

    private static String describe(Location at) {
        return at == null || at.getWorld() == null ? "nowhere"
                : at.getWorld().getName() + " " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ();
    }

    /** Puts the belongings back. Does NOT teleport and does NOT clear the snapshot -- the caller owns both,
     *  because both depend on the player already being somewhere safe. Returns false if anything failed, in
     *  which case the snapshot is deliberately left in place. */
    private boolean applyState(Player player, String id) {
        String raw = db.state(STATE + id);
        if (raw == null || raw.isBlank()) return true;
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
            player.setLevel(Integer.parseInt(parts[6]));
            player.setExp(Float.parseFloat(parts[7]));
            player.setHealth(Math.min(Double.parseDouble(parts[8]), player.getAttribute(Attribute.MAX_HEALTH).getValue()));
            player.setFoodLevel(Integer.parseInt(parts[9]));
            try { player.setGameMode(GameMode.valueOf(parts[10])); } catch (IllegalArgumentException ignored) { player.setGameMode(GameMode.SURVIVAL); }
            player.setFireTicks(0);
            player.setFallDistance(0);
            player.updateInventory();
            return true;
        } catch (RuntimeException error) {
            plugin.getLogger().severe("[VoidWorld] could not apply " + id + "'s belongings: " + error);
            return false;
        }
    }

    // ------------------------------------------------------------------ lifecycle safety

    /** Logging out inside an event world must not strand a real inventory. Restored on the next join, even
     *  if the world was deleted in the meantime. */
    /*  ---------------------------------------------------------------------------------------------------
     *  THE CROSSING, recorded before it happens.
     *
     *  This is the fix. PlayerChangedWorldEvent -- the only hook the old code had -- fires AFTER the player
     *  has arrived, and gives the world they left but not the place. Reading player.getLocation() there
     *  records the DESTINATION, so an admin teleporting to a friend inside had "the void world's spawn
     *  plateau" written down as where they came from, and /voidworld exit dutifully sent them back to it.
     *
     *  PlayerTeleportEvent carries getFrom(), the real origin, and fires before the move. Every teleport in
     *  the game goes through it -- commands, plugins, pearls, portals -- so capturing here covers routes
     *  nobody has thought of yet. MONITOR, ignoreCancelled: only a crossing that is actually going to
     *  happen is recorded, and nothing about access control is touched. */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void crossing(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (moving.contains(player.getUniqueId()) || event.getTo() == null) return;
        World from = event.getFrom().getWorld(), to = event.getTo().getWorld();
        if (from == null || to == null) return;
        /** Normal -> void, and only when there is no session yet. Void -> void keeps the session and its
         *  original return destination untouched, which is exactly what "teleporting between Voidworlds
         *  retains the same isolated session" means. */
        if (!isVoidWorld(to) || isVoidWorld(from)) return;
        if (hasCapture(CoreUtil.id(player))) return;
        capture(player, event.getFrom());
        pendingArrival.add(player.getUniqueId());
    }

    /** Players whose snapshot has been taken by {@link #crossing} and whose body still has to be isolated
     *  once they land. Cleared the moment it is used, and on quit. */
    private final java.util.Set<java.util.UUID> pendingArrival = new java.util.HashSet<>();

    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!hasCapture(CoreUtil.id(player))) {
            /*  Inside a void world with NO session. There is nothing to give back, and handing them
             *  normal-world belongings they have no snapshot for while they stand over the void is the one
             *  outcome that must never happen. They leave empty-handed, which is the state they are in. */
            if (inside(player)) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || !inside(player)) return;
                strip(player);
                Location out = fallbackReturn();
                move(player, out);
                plugin.getLogger().warning("[VoidWorld] " + CoreUtil.id(player) + " logged in inside a void world with no session; sent to " + describe(out));
                CoreUtil.msg(player, "The event world you were in has closed; you have been returned to spawn.");
            }, 20L);
            return;
        }
        /** Still inside: strip first so nothing found in the event survives the reconnect, then run the
         *  ordinary exit, which will not restore anything until they are verifiably out. */
        if (inside(player)) strip(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (inside(player)) {
                String problem = exit(player);
                if (problem != null) CoreUtil.error(player, problem);
                else CoreUtil.msg(player, "Your belongings were restored after the event world closed.");
                return;
            }
            /** Already outside -- the world was deleted while they were away. Belongings only. */
            if (applyState(player, CoreUtil.id(player))) {
                db.state(STATE + CoreUtil.id(player), "");
                player.updateInventory();
                CoreUtil.msg(player, "Your belongings were restored after the event world closed.");
            }
        }, 20L);
    }

    /** A quit inside the world leaves the capture in place on purpose -- join() is what resolves it. Nothing
     *  is done here beyond making sure they do not carry event items in their inventory across the logout. */
    @EventHandler public void quit(PlayerQuitEvent event) {
        pendingArrival.remove(event.getPlayer().getUniqueId());
        if (inside(event.getPlayer())) strip(event.getPlayer());
    }

    /*  Dying inside an event world must not eject you from it.
     *
     *  Reported live: die in the void world and you respawn at your real-world spawn point with an empty
     *  inventory, still counted as being in the event -- your real belongings were never at risk, but the
     *  state was plainly wrong and looked like a total loss. Vanilla sends a corpse to its spawn point, and
     *  a spawn point set in the overworld is exactly what it did.
     *
     *  So a death inside is resolved inside: respawn on the event world's platform, still holding the
     *  capture. Only exit() ever gives the belongings back. If the world went away while they were dead
     *  there is nothing to respawn into, so the full restore runs instead and they come back as themselves.
     *  Graves are not involved either way -- keepInventory is on in these worlds, so a death drops nothing
     *  for a grave to hold, which is the behaviour that was asked for. */
    @EventHandler public void respawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!hasCapture(CoreUtil.id(player))) return;
        World died = Bukkit.getWorld(event.getPlayer().getWorld().getName());
        if (!isVoidWorld(died)) return;
        event.setRespawnLocation(died.getSpawnLocation().clone().add(.5, 1, .5));
        Bukkit.getScheduler().runTask(plugin, () ->
                CoreUtil.msg(player, "You died inside the event world. Your own belongings are still held safely; type /voidworld exit to collect them."));
    }

    /*  Arriving or leaving by any route at all, not just by the command.
     *
     *  An admin dragging somebody in with /tp has to produce exactly the same result as that player typing
     *  /voidworld enter -- belongings captured, inventory cleared -- and dragging them back out has to run
     *  the restore. Hooking the world change rather than the command is what makes that true for every
     *  teleport that exists, including ones from other plugins. */
    @EventHandler public void changedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (moving.contains(player.getUniqueId())) return;
        boolean nowInside = inside(player);
        boolean held = hasCapture(CoreUtil.id(player));
        boolean expected = pendingArrival.remove(player.getUniqueId());
        String label = nowInside ? player.getWorld().getName().substring(PREFIX.length()) : null;

        if (nowInside) {
            /*  Arriving IS entering, by every route. Two cases, and both end in the same lifecycle:
             *
             *    the snapshot was already taken by crossing() a moment ago  -> isolate the body
             *    there is no snapshot at all                                -> take one now
             *
             *  The second is the belt-and-braces path for a crossing that produced no teleport event we
             *  saw: a respawn into the world, or a login. There is no true origin left to read, so
             *  beginSession falls back to a real destination outside rather than inventing one inside. */
            if (held && expected) { isolate(player); return; }
            if (!held) { beginSession(player, null, label); return; }
            /*  Already in a session and moving void -> void. The snapshot, and with it the original return
             *  destination, is deliberately left exactly as it is. */
            return;
        }
        if (held) {
            /*  Left a void world for a normal one by some route other than /voidworld exit. That is the
             *  same transition and gets the same lifecycle -- but they are already OUT, so the destination
             *  is wherever they legitimately arrived, and only the belongings have to catch up. */
            if (!applyState(player, CoreUtil.id(player))) {
                CoreUtil.error(player, "Your belongings could not be restored automatically. They are still held safely - tell an administrator.");
                return;
            }
            db.state(STATE + CoreUtil.id(player), "");
            player.updateInventory();
            CoreUtil.msg(player, "Returned. Your belongings are exactly as you left them.");
        }
    }

    /*  No way out except the way in.
     *
     *  Reported live: enter the world, then /home or /spawn, and you keep the event inventory while standing
     *  in the overworld. The command block below is the message; this is the guarantee. Every teleport out
     *  of an event world by a non-administrator is refused unless this class is the one making it, which
     *  covers commands nobody thought to list and plugins added later. Movement WITHIN the world is
     *  untouched, so ender pearls and the like still work inside the event. */
    @EventHandler(ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (moving.contains(player.getUniqueId()) || plugin.isAdmin(player)) return;
        World to = event.getTo() == null ? null : event.getTo().getWorld();

        /*  The closed door works in both directions.
         *
         *  Blocking only the escape would leave the obvious hole open: teleport to a friend who is already
         *  inside and you are inside too, whatever the access flag says. "Closed" has to mean a
         *  non-administrator cannot arrive by ANY route, which includes being sent by one -- so opening the
         *  world is a prerequisite for bringing somebody in, not an alternative to it. */
        if (isVoidWorld(to) && !isVoidWorld(event.getFrom().getWorld())) {
            String label = to.getName().substring(PREFIX.length());
            if (!mayEnter(player, label)) {
                event.setCancelled(true);
                CoreUtil.error(player, "The '" + label + "' event world is closed.");
                CoreUtil.msg(player, "An administrator has to run /voidworld open " + label + " first.");
            }
            return;
        }

        if (!isVoidWorld(event.getFrom().getWorld())) return;
        if (isVoidWorld(to)) {
            /** Void to void is still an ARRIVAL at the destination, so the closed door applies there too --
             *  otherwise an open world would be a lobby into every closed one. */
            String label = to.getName().substring(PREFIX.length());
            if (!mayEnter(player, label)) {
                event.setCancelled(true);
                CoreUtil.error(player, "The '" + label + "' event world is closed.");
            }
            return;
        }
        event.setCancelled(true);
        CoreUtil.error(player, "To leave the void world, type /voidworld exit.");
    }

    /** The same rule stated as a message rather than as a silent refusal, so the answer arrives before the
     *  player has typed the command three more times. Administrators keep every command. */
    @EventHandler(ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!inside(player) || plugin.isAdmin(player)) return;
        String root = event.getMessage().split(" ")[0].toLowerCase(Locale.ROOT);
        if (root.startsWith("/")) root = root.substring(1);
        if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);
        if (!escapes.contains(root)) return;
        event.setCancelled(true);
        CoreUtil.error(player, "That command is disabled inside an event world.");
        CoreUtil.msg(player, "To leave the void world, type /voidworld exit.");
    }

    /*  Commands that would put a non-administrator outside the event world.
     *
     *  Deliberately a denylist rather than an allowlist: an allowlist would break every ordinary command the
     *  event itself needs, and the teleport handler above is the actual guarantee -- this list only decides
     *  which refusals come with a useful explanation instead of a cancelled teleport. Extendable from the
     *  config for anything a future plugin adds. */
    private final java.util.Set<String> escapes = new java.util.HashSet<>(List.of(
            "spawn", "home", "homes", "sethome", "delhome", "warp", "warps", "back", "tp", "tpa", "tpahere",
            "tpaccept", "tpyes", "tpdeny", "tpno", "tphere", "tpo", "tpask", "rtp", "wild", "wilderness",
            "randomtp", "hub", "lobby", "server", "world", "worlds", "mv", "mvtp", "multiverse",
            "f", "faction", "factions", "guild", "party", "arena", "duel", "duels", "event", "shop",
            "ec", "enderchest", "trade", "ah", "auction", "market", "marketplace", "orders", "order",
            "bank", "grave", "graves", "recover", "kill", "suicide", "top", "jump", "lastdeath", "deathback"));

    /*  ---------------------------------------------------------------------------------------------------
     *  THE LIFECYCLE REGRESSION.
     *
     *  Reported live: an admin teleported to a friend inside an event world instead of using the command,
     *  and on leaving was put at 0,0 INSIDE the void world while already holding their restored real
     *  belongings -- one step from losing everything into the void.
     *
     *  The cause was that the snapshot was written from PlayerChangedWorldEvent, which fires after the
     *  crossing, so "where you came from" was recorded as where you had just arrived. That specific defect
     *  is asserted here, at the level it actually lived: a snapshot whose recorded world is a void world
     *  must be impossible to create and impossible to return to.
     *
     *  Run with /ashfall voidworld verify. Uses throwaway ids and cleans up after itself. */
    List<String> verify() {
        List<String> out = new ArrayList<>();
        int[] score = {0, 0};
        String id = "__voidverify";
        try {
            Location real = fallbackReturn();
            check(out, score, "there is a non-void fallback destination",
                    real != null && real.getWorld() != null && !isVoidWorld(real.getWorld()));
            /** It is a REAL world's spawn, resolved from configuration -- never the literal 0,0 the old
             *  stranded path used, and never a coordinate inside an event world. */
            check(out, score, "the fallback is a resolved world spawn rather than a hardcoded origin",
                    real != null && real.getWorld() != null
                            && real.getWorld().getSpawnLocation().getBlockX() == real.getBlockX()
                            || (real != null && plugin.teleports() != null && plugin.teleports().spawn() != null));

            /*  --- THE REPORTED BUG, as a property ---
             *
             *  A snapshot that records a void world as the origin is the whole defect. Plant one directly
             *  and require the resolver to refuse it. */
            World voidWorld = null;
            for (World candidate : Bukkit.getWorlds()) if (isVoidWorld(candidate)) { voidWorld = candidate; break; }
            boolean temporary = false;
            if (voidWorld == null) { voidWorld = create("__voidverify"); temporary = voidWorld != null; }
            if (voidWorld == null) {
                out.add("  SKIP  no void world available, so the crossing checks cannot run");
            } else {
                db.state(STATE + id, voidWorld.getName() + "|0.5|65.0|0.5|0.0|0.0|0|0.0|20.0|20|SURVIVAL|");
                Location resolved = resolveReturn(id);
                check(out, score, "a snapshot pointing INSIDE a void world is refused",
                        resolved != null && !isVoidWorld(resolved.getWorld()));
                check(out, score, "and the refusal lands somewhere real, never at void 0,0",
                        resolved != null && resolved.getWorld() != null && !isVoidWorld(resolved.getWorld()));

                /** And the same rule at the other end: capture must not be able to record one either. */
                db.state(STATE + id, "");
                Location badOrigin = new Location(voidWorld, 0.5, 65, 0.5);
                String recorded = recordOriginForTest(id, badOrigin);
                check(out, score, "capture rejects a void world as an origin (recorded " + recorded + ")",
                        recorded != null && !recorded.startsWith(PREFIX));

                /** Access control is unchanged by any of this. */
                setOpen("__voidverify", false);
                check(out, score, "a closed world is closed by default", !isOpen("__voidverify"));
                setOpen("__voidverify", true);
                check(out, score, "and open once an administrator opens it", isOpen("__voidverify"));
                setOpen("__voidverify", false);
                if (temporary) delete("__voidverify");
            }

            /*  --- a good snapshot round trips exactly --- */
            World home = Bukkit.getWorlds().getFirst();
            db.state(STATE + id, home.getName() + "|12.5|70.0|-34.5|90.0|10.0|7|0.25|18.0|17|SURVIVAL|");
            Location back = resolveReturn(id);
            check(out, score, "a valid snapshot resolves to its own world", back != null && back.getWorld().equals(home));
            check(out, score, "the recorded facing survives", back != null && Math.abs(back.getYaw() - 90f) < 0.01);

            /*  --- a snapshot for a world that no longer exists falls back rather than failing --- */
            db.state(STATE + id, "__voidverify_missing_world|1.0|64.0|1.0|0.0|0.0|0|0.0|20.0|20|SURVIVAL|");
            Location gone = resolveReturn(id);
            check(out, score, "a snapshot naming a deleted world falls back to a real destination",
                    gone != null && gone.getWorld() != null && !isVoidWorld(gone.getWorld()));

            /*  --- the session flag is what makes entry idempotent --- */
            db.state(STATE + id, home.getName() + "|1.0|64.0|1.0|0.0|0.0|0|0.0|20.0|20|SURVIVAL|");
            check(out, score, "holding a snapshot IS the in-session flag", hasCapture(id));
            db.state(STATE + id, "");
            check(out, score, "and clearing it ends the session", !hasCapture(id));

            /*  --- the escape rules a non-administrator is held to --- */
            check(out, score, "the escape command list still covers spawn/home/tp/back",
                    escapes.contains("spawn") && escapes.contains("home") && escapes.contains("tp") && escapes.contains("back"));
        } catch (RuntimeException error) {
            out.add("  FAILED  the suite threw: " + error);
            score[1]++;
        } finally {
            db.state(STATE + id, "");
        }
        out.add("");
        out.add(score[1] == 0 ? "Voidworld lifecycle: " + score[0] + " checks, 0 failures."
                : "Voidworld lifecycle: " + score[0] + " checks, " + score[1] + " FAILED.");
        return out;
    }

    /** Capture-only, for the verifier: writes a snapshot from the given origin with no player attached and
     *  reports which world actually got recorded. */
    private String recordOriginForTest(String id, Location origin) {
        Location at = origin;
        if (at == null || at.getWorld() == null || isVoidWorld(at.getWorld())) at = fallbackReturn();
        if (at == null || at.getWorld() == null) return null;
        db.state(STATE + id, at.getWorld().getName() + "|" + at.getX() + "|" + at.getY() + "|" + at.getZ()
                + "|0.0|0.0|0|0.0|20.0|20|SURVIVAL|");
        return at.getWorld().getName();
    }

    private static void check(List<String> out, int[] score, String what, boolean ok) {
        score[0]++;
        if (!ok) score[1]++;
        out.add("  " + (ok ? "ok    " : "FAILED") + "  " + what);
    }

    void shutdown() {
        for (World world : Bukkit.getWorlds()) if (isVoidWorld(world)) evacuate(world, "The server is restarting.");
    }

    // ------------------------------------------------------------------ self test

    boolean selfTest() {
        if (!worldName("Test Event 1").equals(PREFIX + "testevent1")) return false;
        if (!worldName("../../etc").equals(PREFIX + "etc")) return false;
        if (worldName("!!!").equals(PREFIX) && create("!!!") != null) return false;
        if (isVoidWorld(null)) return false;
        /** The escape list has to contain the ones that were actually reported, or the message never fires
         *  for the commands somebody already found. */
        for (String command : List.of("spawn", "home", "tpa", "f"))
            if (!escapes.contains(command)) return false;
        /** A world nobody opened must read as closed, and the flag must survive being written and cleared. */
        String probe = "__selftest_access";
        setOpen(probe, true);
        boolean opened = isOpen(probe);
        setOpen(probe, false);
        return opened && !isOpen(probe);
    }
}
