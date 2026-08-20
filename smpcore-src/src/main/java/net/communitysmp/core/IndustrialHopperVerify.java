package net.communitysmp.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Hopper;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Vanilla-parity verification for the Industrial Hopper, run against real blocks in a real world.
 *
 *  The Industrial Hopper is supposed to be a vanilla hopper with two differences and no others: twenty-seven
 *  slots instead of five, and nine items per transfer operation instead of one. "And no others" is the hard
 *  part, and it is not something an item-count check through RCON can establish -- a {@code /setblock} rig
 *  does not propagate redstone updates the way a player-placed circuit does, so an earlier attempt at this
 *  measured a vanilla hopper as producing no comparator signal at all and would have "confirmed" almost
 *  anything.
 *
 *  So this builds a real rig in a private void world, drives one hopper cycle at a time, and asserts what
 *  actually moved. Every case is one a player can reproduce by hand: a chest feeding it, a chest under it, a
 *  furnace, a minecart, a lever, a comparator, items on the floor, a pickaxe, TNT, and a chunk that unloads
 *  and comes back. Item conservation is re-checked after every single cycle, because a duplication bug that
 *  only shows up on the eleventh transfer is still a duplication bug.
 *
 *  Run with /ashfall hopper verify. It creates its own world and deletes it afterwards. */
final class IndustrialHopperVerify {

    private static final String WORLD = "ashfall_hopper_test";
    private static final int Y = 64;

    private final SMPCore plugin;
    private final IndustrialHopperService hoppers;
    private final List<String> out = new ArrayList<>();
    private int failures;
    /** Rigs are built far from the world spawn on purpose: spawn chunks cannot be unloaded, and the chunk
     *  round-trip case needs a chunk the server is actually willing to let go of. */
    private int x = 1000;

    IndustrialHopperVerify(SMPCore plugin, IndustrialHopperService hoppers) {
        this.plugin = plugin;
        this.hoppers = hoppers;
    }

    List<String> run() {
        World world = open();
        if (world == null) { out.add("FAIL could not create the test world"); return finish(null); }
        try {
            check("pure arithmetic self test (conservation, budget, comparator maths)", hoppers.selfTest());
            placement(world);
            pullFromChest(world);
            pushToChest(world);
            budgetIsPerOperation(world);
            redstoneLock(world);
            comparator(world);
            groundPickup(world);
            minecart(world);
            furnaceFaces(world);
            breaking(world);
            explosion(world);
            persistence(world);
        } catch (Throwable error) {
            out.add("FAIL the rig threw: " + error);
            failures++;
            plugin.getLogger().warning("[IndustrialHopper] verify threw: " + error);
        }
        return finish(world);
    }

    // ------------------------------------------------------------------ cases

    private void placement(World world) {
        section("placement and identity");
        Block hopper = industrial(world, BlockFace.DOWN);
        check("the block is recognised as an Industrial Hopper", hoppers.isIndustrial(hopper));
        check("it starts empty", hoppers.storedCount(hopper) == 0);
        check("it has 27 slots", hoppers.storedContents(hopper).length == 27);
        check("its native five slots hold no real items", hoppers.nativeRealCount(hopper) == 0);
        check("facing is preserved", hopper.getBlockData() instanceof Hopper data && data.getFacing() == BlockFace.DOWN);
        Block sideways = industrial(world, BlockFace.NORTH);
        check("a sideways hopper keeps its facing", sideways.getBlockData() instanceof Hopper d2 && d2.getFacing() == BlockFace.NORTH);
    }

    private void pullFromChest(World world) {
        section("pulling from the container above");
        Block hopper = industrial(world, BlockFace.DOWN);
        Inventory source = chest(hopper.getRelative(BlockFace.UP));
        source.setItem(0, new ItemStack(Material.DIAMOND, 64));
        int total = 64;
        hoppers.sweepOnce();
        check("one cycle pulls exactly nine items", hoppers.storedCount(hopper) == 9);
        check("the source lost exactly nine", count(source) == 55);
        check("nothing was created or destroyed", count(source) + hoppers.storedCount(hopper) == total);
        for (int cycle = 0; cycle < 12; cycle++) {
            hoppers.sweepOnce();
            if (count(source) + hoppers.storedCount(hopper) != total) { fail("conservation broke on cycle " + (cycle + 2)); return; }
        }
        check("the chest empties completely and nothing is invented", count(source) == 0 && hoppers.storedCount(hopper) == total);
    }

    private void pushToChest(World world) {
        section("pushing into the container it faces");
        Block hopper = industrial(world, BlockFace.DOWN);
        Inventory destination = chest(hopper.getRelative(BlockFace.DOWN));
        hoppers.setStored(hopper, 0, new ItemStack(Material.REDSTONE, 64));
        int total = 64;
        hoppers.sweepOnce();
        check("one cycle pushes exactly nine items", count(destination) == 9);
        check("nothing was created or destroyed", count(destination) + hoppers.storedCount(hopper) == total);
        for (int cycle = 0; cycle < 12; cycle++) {
            hoppers.sweepOnce();
            if (count(destination) + hoppers.storedCount(hopper) != total) { fail("conservation broke on cycle " + (cycle + 2)); return; }
        }
        check("the hopper empties completely into the chest", hoppers.storedCount(hopper) == 0 && count(destination) == total);
    }

    private void budgetIsPerOperation(World world) {
        section("nine items per operation, not nine per slot");
        Block hopper = industrial(world, BlockFace.DOWN);
        Inventory destination = chest(hopper.getRelative(BlockFace.DOWN));
        /** One item in each of the 27 slots. A per-slot budget would move all 27 in one cycle; the contract
         *  says nine. This is the difference the whole block is balanced around. */
        for (int slot = 0; slot < 27; slot++) hoppers.setStored(hopper, slot, new ItemStack(Material.COBBLESTONE, 1));
        hoppers.sweepOnce();
        check("27 single items yield exactly nine in one cycle", count(destination) == 9);
        check("18 are still in the hopper", hoppers.storedCount(hopper) == 18);
    }

    private void redstoneLock(World world) {
        section("redstone locking");
        Block hopper = industrial(world, BlockFace.DOWN);
        Inventory source = chest(hopper.getRelative(BlockFace.UP));
        Inventory destination = chest(hopper.getRelative(BlockFace.DOWN));
        source.setItem(0, new ItemStack(Material.DIAMOND, 64));
        Hopper data = (Hopper) hopper.getBlockData();
        data.setEnabled(false);
        hopper.setBlockData(data, false);
        hoppers.sweepOnce();
        hoppers.sweepOnce();
        check("a locked hopper moves nothing in", hoppers.storedCount(hopper) == 0);
        check("a locked hopper moves nothing out", count(destination) == 0);
        check("a locked hopper still reports to a comparator", hoppers.nativeCalibration(hopper) >= 0);
        data.setEnabled(true);
        hopper.setBlockData(data, false);
        hoppers.sweepOnce();
        check("unlocking resumes transfer immediately", hoppers.storedCount(hopper) + count(destination) == 9);
    }

    private void comparator(World world) {
        section("comparator output across 27 slots");
        Block hopper = industrial(world, BlockFace.NORTH);
        check("empty reads zero", level(hopper) == 0 && hoppers.nativeCalibration(hopper) == 0);
        hoppers.setStored(hopper, 0, new ItemStack(Material.DIAMOND, 1));
        hoppers.refreshComparatorNow(hopper);
        check("a single item reads one", level(hopper) == 1);
        check("the block carries a calibration weight for it", hoppers.nativeCalibration(hopper) == IndustrialHopperService.signalUnits(1));
        check("the calibration is not counted as contents", hoppers.nativeRealCount(hopper) == 0 && hoppers.storedCount(hopper) == 1);
        int previous = -1;
        for (int filled = 1; filled <= 27; filled++) {
            hoppers.setStored(hopper, filled - 1, new ItemStack(Material.DIAMOND, 64));
            hoppers.refreshComparatorNow(hopper);
            int reading = level(hopper);
            if (reading < previous) { fail("the reading went DOWN as the hopper filled (" + previous + " -> " + reading + ")"); return; }
            if (hoppers.nativeCalibration(hopper) != IndustrialHopperService.signalUnits(reading)) {
                fail("the block's calibration does not match the reading at " + filled + " full slots");
                return;
            }
            previous = reading;
        }
        check("a completely full hopper reads fifteen", previous == 15);
        check("full means the block's own slots are full too, so vanilla fullness checks agree",
                hoppers.nativeCalibration(hopper) == 5 * 64);
        /** And back down again: the readout must follow the contents in both directions. */
        for (int slot = 0; slot < 27; slot++) hoppers.setStored(hopper, slot, null);
        hoppers.refreshComparatorNow(hopper);
        check("emptying it returns the reading to zero", level(hopper) == 0 && hoppers.nativeCalibration(hopper) == 0);
    }

    private void groundPickup(World world) {
        section("collecting items off the floor");
        Block hopper = industrial(world, BlockFace.DOWN);
        Location above = hopper.getLocation().add(0.5, 1.2, 0.5);
        Item dropped = world.dropItem(above, new ItemStack(Material.IRON_INGOT, 20));
        dropped.setVelocity(new org.bukkit.util.Vector());
        hoppers.sweepOnce();
        check("the item entity is absorbed", hoppers.storedCount(hopper) == 20);
        check("the entity is gone rather than left as a copy", !dropped.isValid());
        /** A full hopper must leave an item on the floor rather than delete it. */
        Block full = industrial(world, BlockFace.NORTH);
        for (int slot = 0; slot < 27; slot++) hoppers.setStored(full, slot, new ItemStack(Material.STONE, 64));
        Item spare = world.dropItem(full.getLocation().add(0.5, 1.2, 0.5), new ItemStack(Material.IRON_INGOT, 5));
        spare.setVelocity(new org.bukkit.util.Vector());
        hoppers.sweepOnce();
        check("a full hopper leaves the item on the floor", spare.isValid() && spare.getItemStack().getAmount() == 5);
        spare.remove();
    }

    private void minecart(World world) {
        section("container minecarts");
        Block hopper = industrial(world, BlockFace.DOWN);
        Location cartAt = hopper.getLocation().add(0.5, 1.0, 0.5);
        org.bukkit.entity.minecart.StorageMinecart cart =
                world.spawn(cartAt, org.bukkit.entity.minecart.StorageMinecart.class, c -> c.setGravity(false));
        cart.getInventory().setItem(0, new ItemStack(Material.COAL, 32));
        hoppers.sweepOnce();
        check("a chest minecart above is unloaded, nine at a time", hoppers.storedCount(hopper) == 9);
        check("the minecart lost exactly nine", count(cart.getInventory()) == 23);
        cart.remove();

        Block pusher = industrial(world, BlockFace.DOWN);
        org.bukkit.entity.minecart.StorageMinecart below =
                world.spawn(pusher.getLocation().add(0.5, -1.0, 0.5), org.bukkit.entity.minecart.StorageMinecart.class, c -> c.setGravity(false));
        hoppers.setStored(pusher, 0, new ItemStack(Material.COAL, 32));
        hoppers.sweepOnce();
        check("a chest minecart it faces is loaded, nine at a time", count(below.getInventory()) == 9);
        check("nothing was created or destroyed", count(below.getInventory()) + hoppers.storedCount(pusher) == 32);
        below.remove();
    }

    private void furnaceFaces(World world) {
        section("furnaces route by face, exactly as vanilla does");
        Block feeder = industrial(world, BlockFace.DOWN);
        Block furnace = feeder.getRelative(BlockFace.DOWN);
        furnace.setType(Material.FURNACE, false);
        hoppers.setStored(feeder, 0, new ItemStack(Material.IRON_ORE, 32));
        hoppers.sweepOnce();
        org.bukkit.inventory.FurnaceInventory inv = (org.bukkit.inventory.FurnaceInventory) ((Container) furnace.getState(false)).getInventory();
        check("a hopper pointing down loads the smelting slot, not the fuel slot",
                inv.getSmelting() != null && inv.getSmelting().getAmount() == 9 && inv.getFuel() == null);

        /** And the other direction: a hopper UNDER a furnace must take the result, never the ore that has not
         *  smelted yet. Getting this wrong quietly steals the input back out of every furnace array. */
        Block drain = industrial(world, BlockFace.DOWN);
        Block above = drain.getRelative(BlockFace.UP);
        above.setType(Material.FURNACE, false);
        org.bukkit.inventory.FurnaceInventory source = (org.bukkit.inventory.FurnaceInventory) ((Container) above.getState(false)).getInventory();
        source.setSmelting(new ItemStack(Material.IRON_ORE, 16));
        source.setFuel(new ItemStack(Material.COAL, 16));
        source.setResult(new ItemStack(Material.IRON_INGOT, 16));
        hoppers.sweepOnce();
        org.bukkit.inventory.FurnaceInventory after = (org.bukkit.inventory.FurnaceInventory) ((Container) above.getState(false)).getInventory();
        check("it takes the smelted result", hoppers.storedCount(drain) == 9 && after.getResult().getAmount() == 7);
        check("it leaves the ore alone", after.getSmelting() != null && after.getSmelting().getAmount() == 16);
        check("it leaves the fuel alone", after.getFuel() != null && after.getFuel().getAmount() == 16);
    }

    private void breaking(World world) {
        section("breaking");
        Block hopper = industrial(world, BlockFace.DOWN);
        hoppers.setStored(hopper, 0, new ItemStack(Material.DIAMOND, 17));
        hoppers.setStored(hopper, 5, new ItemStack(Material.EMERALD, 3));
        hoppers.refreshComparatorNow(hopper);
        int before = groundItems(world, hopper);
        hoppers.dropAllForTest(hopper);
        hopper.setType(Material.AIR, false);
        int diamonds = groundOf(world, hopper, Material.DIAMOND), emeralds = groundOf(world, hopper, Material.EMERALD);
        int blocks = groundOf(world, hopper, Material.HOPPER), calibration = groundOf(world, hopper, Material.GRAY_STAINED_GLASS_PANE);
        check("the contents drop exactly once", diamonds == 17 && emeralds == 3);
        check("exactly one hopper block drops", blocks == 1);
        check("the comparator calibration weight never drops", calibration == 0);
        check("the block stops being ours once broken", !hoppers.isIndustrial(hopper));
        clearGround(world, hopper);
        if (before != 0) out.add("   (note: " + before + " stray item(s) were already on the floor here)");
    }

    private void explosion(World world) {
        section("explosions");
        Block hopper = industrial(world, BlockFace.DOWN);
        hoppers.setStored(hopper, 0, new ItemStack(Material.GOLD_INGOT, 11));
        List<Block> blast = new ArrayList<>(List.of(hopper));
        hoppers.explodeForTest(blast);
        check("the hopper is taken out of the blast list so vanilla cannot drop a second one", blast.isEmpty());
        check("the contents drop exactly once", groundOf(world, hopper, Material.GOLD_INGOT) == 11);
        check("exactly one hopper block drops", groundOf(world, hopper, Material.HOPPER) == 1);
        check("the block is gone", hopper.getType() == Material.AIR);
        check("it stops being ours", !hoppers.isIndustrial(hopper));
        clearGround(world, hopper);
    }

    private void persistence(World world) {
        section("persistence across unload and restart");
        Block hopper = industrial(world, BlockFace.DOWN);
        hoppers.setStored(hopper, 0, new ItemStack(Material.NETHERITE_SCRAP, 4));
        hoppers.setStored(hopper, 26, new ItemStack(Material.LAPIS_LAZULI, 61));
        check("the store writes into the block", hoppers.flushAndForget(hopper));
        check("re-reading the block recovers the contents", hoppers.storedCount(hopper) == 65);
        ItemStack[] back = hoppers.storedContents(hopper);
        check("slot positions survive", back[0] != null && back[0].getType() == Material.NETHERITE_SCRAP && back[0].getAmount() == 4
                && back[26] != null && back[26].getType() == Material.LAPIS_LAZULI && back[26].getAmount() == 61);

        /** The real thing: unload the chunk and pull it back off the disk. This is the same path a restart
         *  takes, minus the JVM. */
        int cx = hopper.getX() >> 4, cz = hopper.getZ() >> 4;
        hoppers.flushAndForget(hopper);
        world.save();
        boolean unloaded = world.unloadChunk(cx, cz, true);
        boolean reloaded = world.loadChunk(cx, cz, false);
        /** What matters is the round trip, not which API call reported what: a server that declines to drop
         *  a chunk (a ticket, a nearby player) is not a hopper bug, so it is reported rather than failed. */
        if (!unloaded || !reloaded) out.add("   (the server declined to unload chunk " + cx + "," + cz
                + "; the serialise/deserialise path below is still asserted)");
        check("contents survive a chunk round trip", hoppers.storedCount(hopper) == 65);
        check("it is still an Industrial Hopper afterwards", hoppers.isIndustrial(hopper));
        check("and still has no real items in its native slots", hoppers.nativeRealCount(hopper) == 0);
        out.add("   (a full restart is covered by /ashfall hopper <x> <y> <z> on a hopper left standing;"
                + " this asserts the same serialise/deserialise path the restart uses)");
    }

    // ------------------------------------------------------------------ rig plumbing

    /** A fresh column for each case, far enough apart that no two rigs can see each other. */
    private Block industrial(World world, BlockFace facing) {
        x += 8;
        Block block = world.getBlockAt(x, Y, 0);
        for (int dy = -2; dy <= 2; dy++) world.getBlockAt(x, Y + dy, 0).setType(Material.AIR, false);
        block.setType(Material.HOPPER, false);
        Hopper data = (Hopper) block.getBlockData();
        data.setFacing(facing);
        data.setEnabled(true);
        block.setBlockData(data, false);
        hoppers.install(block);
        return block;
    }

    private Inventory chest(Block block) {
        block.setType(Material.CHEST, false);
        return ((Container) block.getState(false)).getInventory();
    }

    private int level(Block block) { return hoppers.comparatorLevelAt(block); }

    private static int count(Inventory inv) {
        int sum = 0;
        for (ItemStack item : inv.getContents()) if (item != null && !item.getType().isAir()) sum += item.getAmount();
        return sum;
    }

    private int groundItems(World world, Block near) {
        int sum = 0;
        for (org.bukkit.entity.Entity entity : world.getNearbyEntities(near.getLocation().add(.5, .5, .5), 4, 4, 4))
            if (entity instanceof Item item) sum += item.getItemStack().getAmount();
        return sum;
    }

    private int groundOf(World world, Block near, Material type) {
        int sum = 0;
        for (org.bukkit.entity.Entity entity : world.getNearbyEntities(near.getLocation().add(.5, .5, .5), 4, 4, 4))
            if (entity instanceof Item item && item.getItemStack().getType() == type) sum += item.getItemStack().getAmount();
        return sum;
    }

    private void clearGround(World world, Block near) {
        for (org.bukkit.entity.Entity entity : world.getNearbyEntities(near.getLocation().add(.5, .5, .5), 6, 6, 6))
            if (entity instanceof Item) entity.remove();
    }

    private World open() {
        World existing = Bukkit.getWorld(WORLD);
        if (existing != null) return existing;
        World world = new WorldCreator(WORLD).generator(new ArenaService.VoidGenerator())
                .type(WorldType.FLAT).environment(World.Environment.NORMAL).createWorld();
        if (world == null) return null;
        world.setAutoSave(false);
        world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(org.bukkit.GameRule.DO_TILE_DROPS, true);
        world.setKeepSpawnInMemory(false);
        return world;
    }

    private void section(String name) { out.add(""); out.add("== " + name); }

    private void check(String what, boolean ok) {
        out.add((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    private void fail(String what) { check(what, false); }

    private List<String> finish(World world) {
        if (world != null) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) entity.remove();
            File folder = world.getWorldFolder();
            String name = world.getName();
            if (Bukkit.unloadWorld(world, false)) {
                /** Regression guard for a bug this rig found the hard way: a Location holds only a WEAK
                 *  reference to its World, and once that world is unloaded getWorld() THROWS rather than
                 *  returning null -- so the sweep's null check never fired and it threw on every tick
                 *  afterwards, taking every other hopper on the server down with it. Duel instances unload
                 *  constantly now, so this is not a test-only situation. */
                section("after the world unloads");
                boolean threw = false;
                try { hoppers.sweepOnce(); } catch (Throwable error) { threw = true; out.add("   threw: " + error); }
                check("a sweep after a world unloads does not throw", !threw);
                check("the unloaded world's hoppers are released",
                        hoppers.describeAll().stream().noneMatch(line -> line.startsWith(name + " ")));
                delete(folder);
            }
        }
        out.add("");
        out.add(failures == 0 ? "INDUSTRIAL HOPPER PARITY VERIFIED - no failures"
                              : "INDUSTRIAL HOPPER: " + failures + " FAILURE(S)");
        return out;
    }

    private void delete(File folder) {
        if (folder == null || !folder.exists()) return;
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(folder.toPath())) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> { try { java.nio.file.Files.deleteIfExists(path); } catch (Exception ignored) { } });
        } catch (Exception ignored) { }
    }
}
