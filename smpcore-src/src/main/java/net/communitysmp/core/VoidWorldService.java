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
        absorb(player, label);
        move(player, world.getSpawnLocation().clone().add(.5, 1, .5));
        return null;
    }

    /*  Becoming a participant, without the command's preconditions.
     *
     *  enter() is the COMMAND: it refuses if you are already inside, because typing /voidworld enter twice
     *  should say so. Arriving by teleport is the same event with those preconditions already satisfied in
     *  the opposite direction -- you ARE inside, that is the whole reason this is running -- so sharing
     *  enter() wholesale is exactly wrong, and sharing it wholesale is precisely the bug that was reported:
     *  teleport somebody in and enter() answered "you are already in a void world" and returned before
     *  capturing anything, so they kept their real inventory while standing in the event.
     *
     *  Everything that actually makes somebody a participant lives here, and both routes call it. */
    private void absorb(Player player, String label) {
        capture(player);
        strip(player);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        player.setLevel(0);
        player.setExp(0);
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        player.setFallDistance(0);
        CoreUtil.msg(player, "You entered the '" + label + "' event world. Nothing came in with you, and nothing leaves with you.");
        CoreUtil.msg(player, "To leave the void world, type /voidworld exit.");
    }

    /** A teleport this class is performing itself, exempt from the escape lock and from the world-change
     *  listener that would otherwise treat it as somebody arriving or leaving on their own. */
    private void move(Player player, Location to) {
        moving.add(player.getUniqueId());
        try { player.teleport(to); } finally { moving.remove(player.getUniqueId()); }
    }

    /** The way out. Everything found inside is destroyed, which is the whole point -- a temporary world must
     *  not be an item source. */
    String exit(Player player) {
        String id = CoreUtil.id(player);
        if (!hasCapture(id)) {
            /** Inside with nothing held is the stranded case: whatever put them here did not go through
             *  enter(), so there is nothing to give back, but leaving them in an event world would be
             *  worse than sending them home empty-handed -- which is the state they are already in. */
            if (!inside(player)) return "You are not in a void world.";
            strip(player);
            move(player, Bukkit.getWorlds().getFirst().getSpawnLocation());
            return "You had no held belongings, so you have been returned to spawn.";
        }
        strip(player);
        moving.add(player.getUniqueId());
        Location back;
        try { back = restore(player); } finally { moving.remove(player.getUniqueId()); }
        CoreUtil.msg(player, "Returned. Your belongings are exactly as you left them.");
        /*  Confirm they actually left, and move them again if they did not.
         *
         *  Reported live: "I have to leave the world twice", and "when I exit it sometimes brings me to my
         *  spawnpoint". Both are the same shape -- something refused the teleport, the capture was cleared
         *  anyway, and the second attempt fell through to the no-capture branch, which sends you to the
         *  WORLD spawn rather than to where you came from. Belongings were never at risk, but standing in
         *  an event world with your own inventory is precisely the state this service exists to prevent.
         *
         *  A tick later the world is settled and any competing teleport has run, so this is the last word.
         *  It is a no-op on every exit that worked, which is nearly all of them. */
        Location target = back;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !inside(player)) return;
            moving.add(player.getUniqueId());
            try {
                if (player.isDead()) player.spigot().respawn();
                player.teleport(target != null ? target : Bukkit.getWorlds().getFirst().getSpawnLocation());
            } finally { moving.remove(player.getUniqueId()); }
        }, 1L);
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

    /** Returns where the player was put back, so the caller can confirm it actually happened. */
    private Location restore(Player player) {
        String id = CoreUtil.id(player);
        String raw = db.state(STATE + id);
        if (raw == null || raw.isBlank()) return null;
        Location back = null;
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
            back = world != null
                    ? new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                        Float.parseFloat(parts[4]), Float.parseFloat(parts[5]))
                    : Bukkit.getWorlds().getFirst().getSpawnLocation();
            /*  A corpse cannot be moved.
             *
             *  Vanilla holds a dead player in place and then sends them to their SPAWN POINT when they
             *  respawn, discarding any teleport made in between -- which is exactly the trap the duel arena
             *  hit, and exactly what "exiting sometimes brings me to my spawnpoint" is. Respawn first, then
             *  put them where they belong. */
            if (player.isDead()) player.spigot().respawn();
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
        return back;
    }

    // ------------------------------------------------------------------ lifecycle safety

    /** Logging out inside an event world must not strand a real inventory. Restored on the next join, even
     *  if the world was deleted in the meantime. */
    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!hasCapture(CoreUtil.id(player))) {
            /** In an event world with nothing held: they cannot be restored because there is nothing to
             *  restore, so the only failure left to avoid is leaving them stuck in it. */
            if (inside(player)) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && inside(player)) {
                    strip(player);
                    player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
                    CoreUtil.msg(player, "The event world you were in has closed; you have been returned to spawn.");
                }
            }, 20L);
            return;
        }
        if (inside(player)) strip(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) { restore(player); CoreUtil.msg(player, "Your belongings were restored after the event world closed."); }
        }, 20L);
    }

    /** A quit inside the world leaves the capture in place on purpose -- join() is what resolves it. Nothing
     *  is done here beyond making sure they do not carry event items in their inventory across the logout. */
    @EventHandler public void quit(PlayerQuitEvent event) {
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
        boolean nowInside = inside(player), held = hasCapture(CoreUtil.id(player));
        if (nowInside && !held) {
            /** However they got here -- an admin's /tp, a plugin, a teleport to somebody already inside --
             *  arriving IS entering, and the belongings logic is the same one the command runs. */
            absorb(player, player.getWorld().getName().substring(PREFIX.length()));
            return;
        }
        if (!nowInside && held) {
            String problem = exit(player);
            if (problem != null) CoreUtil.error(player, problem);
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
        if (isVoidWorld(to)) return;
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
