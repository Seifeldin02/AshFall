package net.communitysmp.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Hopper;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/*  Live conservation suite for the Industrial Hopper, driven by real ticking blocks.
 *
 *  The synthetic rig in IndustrialHopperVerify calls sweepOnce() by hand. That is a useful arithmetic test
 *  and it is exactly why it never caught the deletion: the bug lived in InventoryMoveItemEvent, which only
 *  fires when Paper's own hopper tick runs, and a hand-driven sweep never makes Paper tick anything. Every
 *  case here builds genuine blocks in a loaded chunk, puts the payload in through the ordinary inventory
 *  path, and then does nothing whatsoever -- the plugin's scheduled sweep and vanilla's hopper cooldown
 *  move the items and the real events fire.
 *
 *  Conservation is sampled EVERY tick over every place an item can be (both chest inventories, both bays,
 *  the native five slots, and item entities in the area), not merely before and after, because a transfer
 *  that loses items and refunds them would pass an endpoint check while still being wrong. A case fails on
 *  the first tick the total moves, and reports that tick's full breakdown rather than a bare mismatch. */
final class IndustrialHopperLive {

    private final SMPCore plugin;
    private final IndustrialHopperService hoppers;
    private final World world;
    private final int baseX, baseY, baseZ;
    private final Consumer<String> report;
    private final Deque<Runnable> queue = new ArrayDeque<>();
    /** Chunks this suite pinned, released together at the end. A rig whose chunk drifts out of range stops
     *  ticking and reports a timeout that says nothing about the code under test. */
    private final Set<Long> pinned = new java.util.HashSet<>();
    private int column, passed, failed;

    IndustrialHopperLive(SMPCore plugin, IndustrialHopperService hoppers, World world,
                         int baseX, int baseY, int baseZ, Consumer<String> report) {
        this.plugin = plugin;
        this.hoppers = hoppers;
        this.world = world;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        this.report = report;
    }

    void run() {
        report.accept("live suite at " + baseX + "," + baseY + "," + baseZ + " in " + world.getName()
                + " -- real ticking blocks, real events, per-tick conservation");
        /** Both orientations of the double chest, because which half getLocation() reports is a property of
         *  the pair rather than of the hopper, and only one of the two used to trigger the loss. The point
         *  of the case is to cover whichever one it is without having to know. */
        chain("64 chests under a double chest, hopper below the LEFT half", Pairing.LEFT,
                List.of(new ItemStack(Material.CHEST, 64)), 0, false);
        chain("64 chests under a double chest, hopper below the RIGHT half", Pairing.RIGHT,
                List.of(new ItemStack(Material.CHEST, 64)), 0, false);
        chain("full 64-stack (stone)", Pairing.SINGLE, List.of(new ItemStack(Material.STONE, 64)), 0, false);
        chain("16-stack item (egg)", Pairing.SINGLE, List.of(new ItemStack(Material.EGG, 16)), 0, false);
        chain("unstackables (5 diamond swords)", Pairing.SINGLE, swords(), 0, false);
        chain("mixed load", Pairing.SINGLE, List.of(new ItemStack(Material.STONE, 40),
                new ItemStack(Material.EGG, 12), new ItemStack(Material.DIAMOND_SWORD, 1),
                new ItemStack(Material.TORCH, 7)), 0, false);
        chain("partial destination (room for 20 of 64)", Pairing.SINGLE,
                List.of(new ItemStack(Material.STONE, 64)), 27 * 64 - 20, false);
        chain("full destination (the payload must be held, never eaten)", Pairing.SINGLE,
                List.of(new ItemStack(Material.STONE, 64)), 27 * 64, false);
        chain("double chest with a chunk unload and reload mid-flight", Pairing.LEFT,
                List.of(new ItemStack(Material.CHEST, 64)), 0, true);
        sidePusher();
        queue.add(this::finish);
        next();
    }

    private List<ItemStack> swords() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) out.add(new ItemStack(Material.DIAMOND_SWORD, 1));
        return out;
    }

    private enum Pairing { SINGLE, LEFT, RIGHT }

    private void next() {
        Runnable step = queue.poll();
        if (step != null) step.run();
    }

    private void finish() {
        for (long chunk : pinned) world.setChunkForceLoaded((int) (chunk >> 32), (int) chunk, false);
        pinned.clear();
        world.setChunkForceLoaded(baseX >> 4, baseZ >> 4, false);
        report.accept(failed == 0
                ? "ALL " + passed + " live cases conserved every item on every tick"
                : passed + " passed, " + failed + " FAILED");
    }

    private void pass(String label, String detail) {
        passed++;
        report.accept("  ok   " + label + " -- " + detail);
        next();
    }

    private void fail(String label, String detail) {
        failed++;
        report.accept("  FAIL " + label + " -- " + detail);
        next();
    }

    // ------------------------------------------------------------------ rig construction

    /** chest -> Industrial Hopper -> chest -> Industrial Hopper -> chest, in a fresh column each time. */
    private void chain(String label, Pairing doubled, List<ItemStack> payload, int prefill, boolean cycleChunk) {
        queue.add(() -> {
            int x = baseX + (column++ * 4), y = baseY, z = baseZ;
            clear(x, z);
            Block top = world.getBlockAt(x, y + 2, z);
            Block upper = world.getBlockAt(x, y + 1, z);
            Block middle = world.getBlockAt(x, y, z);
            Block lower = world.getBlockAt(x, y - 1, z);
            Block bottom = world.getBlockAt(x, y - 2, z);
            for (Block chest : new Block[]{top, middle, bottom}) chest.setType(Material.CHEST, false);
            if (doubled != Pairing.SINGLE) pair(top, doubled);
            industrial(upper);
            industrial(lower);

            Inventory source = ((Container) top.getState(false)).getInventory();
            Inventory target = ((Container) bottom.getState(false)).getInventory();
            int filler = 0;
            for (int slot = 0; slot < target.getSize() && filler < prefill; slot++) {
                int here = Math.min(64, prefill - filler);
                target.setItem(slot, new ItemStack(Material.STONE, here));
                filler += here;
            }
            int carried = 0;
            for (ItemStack item : payload) {
                source.addItem(item.clone());
                carried += item.getAmount();
            }
            int total = carried + filler;
            String shape = doubled == Pairing.SINGLE ? "single chest" : "double chest (size " + source.getSize() + ")";
            sample(label + " [" + shape + "]", x, y, z, total, carried, filler, cycleChunk);
        });
    }

    private void pair(Block block, Pairing side) {
        /** For a north-facing chest the LEFT half's partner sits to its east, so putting the hopper under
         *  the LEFT half and under the RIGHT half are genuinely different geometries, and only one of them
         *  is the one Inventory#getLocation() names. */
        Block partner = world.getBlockAt(block.getX() + (side == Pairing.LEFT ? 1 : -1), block.getY(), block.getZ());
        partner.setType(Material.CHEST, false);
        write(block, side == Pairing.LEFT ? Chest.Type.LEFT : Chest.Type.RIGHT);
        write(partner, side == Pairing.LEFT ? Chest.Type.RIGHT : Chest.Type.LEFT);
    }

    private void write(Block block, Chest.Type type) {
        Chest data = (Chest) block.getBlockData();
        data.setFacing(BlockFace.NORTH);
        data.setType(type);
        block.setBlockData(data, false);
    }

    private void industrial(Block block) {
        block.setType(Material.HOPPER, false);
        Hopper data = (Hopper) block.getBlockData();
        data.setFacing(BlockFace.DOWN);
        data.setEnabled(true);
        block.setBlockData(data, false);
        hoppers.install(block);
    }

    private void pin(int x, int z) {
        world.setChunkForceLoaded(x >> 4, z >> 4, true);
        pinned.add(((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL));
    }

    private void clear(int x, int z) {
        pin(x, z);
        pin(x + 2, z);
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -2; dx <= 2; dx++)
                world.getBlockAt(x + dx, baseY + dy, z).setType(Material.AIR, false);
        for (org.bukkit.entity.Entity entity : world.getNearbyEntities(
                new Location(world, x + .5, baseY + .5, z + .5), 6, 8, 6))
            if (entity instanceof Item) entity.remove();
    }

    // ------------------------------------------------------------------ sampling

    private int census(int x, int y, int z) {
        int sum = 0;
        /** A double chest is a single 54-slot inventory reachable through either of its two blocks, so
         *  visiting both halves counts every item in it twice. Keyed by the inventory's own reported
         *  position, which is the same for both halves and is the block itself for everything else. */
        java.util.Set<String> counted = new java.util.HashSet<>();
        for (int dy = -3; dy <= 3; dy++)
            for (int dx = -2; dx <= 2; dx++) {
                Block block = world.getBlockAt(x + dx, y + dy, z);
                if (block.getState(false) instanceof Container container) {
                    Location where = container.getInventory().getLocation();
                    String id = where == null ? block.getX() + "," + block.getY() + "," + block.getZ()
                            : where.getBlockX() + "," + where.getBlockY() + "," + where.getBlockZ();
                    if (!counted.add(id)) continue;
                }
                sum += hoppers.countAt(block);
                sum += stored(block);
            }
        for (org.bukkit.entity.Entity entity : world.getNearbyEntities(
                new Location(world, x + .5, y + .5, z + .5), 7, 9, 7))
            if (entity instanceof Item dropped) sum += dropped.getItemStack().getAmount();
        return sum;
    }

    private String breakdown(int x, int y, int z) {
        StringBuilder out = new StringBuilder();
        for (int dy = 3; dy >= -3; dy--) {
            Block block = world.getBlockAt(x, y + dy, z);
            if (block.getType() == Material.AIR) continue;
            out.append(' ').append(y + dy).append(':').append(block.getType() == Material.HOPPER ? "IH" : "chest")
               .append('=').append(hoppers.countAt(block) + stored(block));
        }
        return out.toString();
    }

    /** Watches one rig until it settles, failing on the first tick the world stops adding up. */
    private void sample(String label, int x, int y, int z, int total, int carried, int filler, boolean cycleChunk) {
        int[] ticks = {0};
        boolean[] cycled = {!cycleChunk};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            ticks[0]++;
            int seen = census(x, y, z);
            if (seen != total) {
                task.cancel();
                fail(label, "conservation broke on tick " + ticks[0] + ": counted " + seen + ", expected " + total
                        + " --" + breakdown(x, y, z));
                return;
            }
            /** Halfway through, drop the chunk and bring it straight back. A bay that is forgotten on unload
             *  and re-read on load has to come back with the same contents, and mid-flight is the only
             *  moment where that is actually load-bearing. */
            if (!cycled[0] && ticks[0] == 40) {
                cycled[0] = true;
                world.setChunkForceLoaded(x >> 4, z >> 4, false);
                world.unloadChunk(x >> 4, z >> 4, true);
                world.loadChunk(x >> 4, z >> 4);
                world.setChunkForceLoaded(x >> 4, z >> 4, true);
                int after = census(x, y, z);
                if (after != total) {
                    task.cancel();
                    fail(label, "the chunk round trip lost items: " + after + " of " + total
                            + " --" + breakdown(x, y, z));
                    return;
                }
            }
            Block top = world.getBlockAt(x, y + 2, z), upper = world.getBlockAt(x, y + 1, z),
                  middle = world.getBlockAt(x, y, z), lower = world.getBlockAt(x, y - 1, z),
                  bottom = world.getBlockAt(x, y - 2, z);
            int inFlight = hoppers.countAt(top) + stored(upper) + hoppers.countAt(middle) + stored(lower);
            boolean settled = inFlight == 0;
            if (!settled && ticks[0] < 400) return;
            task.cancel();
            int delivered = hoppers.countAt(bottom) - filler;
            int room = Math.max(0, 27 * 64 - filler);
            int expected = Math.min(room, carried);
            if (delivered != expected) {
                fail(label, "delivered " + delivered + " of an expected " + expected + " after " + ticks[0]
                        + " ticks --" + breakdown(x, y, z));
                return;
            }
            if (inFlight != carried - expected) {
                fail(label, "the undeliverable remainder is " + inFlight + " rather than "
                        + (carried - expected) + " --" + breakdown(x, y, z));
                return;
            }
            pass(label, "delivered " + delivered + "/" + carried + ", held " + inFlight
                    + ", total " + total + " intact on all " + ticks[0] + " ticks");
        }, 1L, 1L);
    }

    /** storedCount answers -1 for a block that is not one of ours, which must not be added to a census. */
    private int stored(Block block) { return Math.max(0, hoppers.storedCount(block)); }

    // ------------------------------------------------------------------ the deferred inbound path

    /*  A plain vanilla hopper aimed horizontally at an Industrial Hopper.
     *
     *  This is the case the fix actually changes behaviour for: the push is cancelled and re-performed a
     *  tick later against the settled source, because the source cannot be written to during the event.
     *  It has its own rig because it is the only geometry that reaches that code at all -- a chain from
     *  above never does. */
    private void sidePusher() {
        queue.add(() -> {
            int x = baseX + (column++ * 4), y = baseY, z = baseZ;
            clear(x, z);
            Block feeder = world.getBlockAt(x + 1, y + 1, z);
            Block pusher = world.getBlockAt(x + 1, y, z);
            Block target = world.getBlockAt(x, y, z);
            Block below = world.getBlockAt(x, y - 1, z);
            feeder.setType(Material.CHEST, false);
            below.setType(Material.CHEST, false);
            pusher.setType(Material.HOPPER, false);
            Hopper data = (Hopper) pusher.getBlockData();
            data.setFacing(BlockFace.WEST);
            data.setEnabled(true);
            pusher.setBlockData(data, false);
            industrial(target);
            ((Container) feeder.getState(false)).getInventory().addItem(new ItemStack(Material.STONE, 64));
            sampleSide("vanilla hopper pushing in from the side", x, y, z, 64);
        });
    }

    private void sampleSide(String label, int x, int y, int z, int total) {
        int[] ticks = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            ticks[0]++;
            int seen = census(x, y, z);
            if (seen != total) {
                task.cancel();
                fail(label, "conservation broke on tick " + ticks[0] + ": counted " + seen + ", expected " + total);
                return;
            }
            int arrived = hoppers.countAt(world.getBlockAt(x, y - 1, z));
            /** One item per push attempt at vanilla's eight-tick cooldown, plus the tick the deferred
             *  pull waits: a full stack legitimately takes upwards of a thousand ticks to cross. */
            if (arrived < total && ticks[0] < 2000) return;
            task.cancel();
            if (arrived == total) pass(label, "all " + total + " arrived through the deferred inbound pull in "
                    + ticks[0] + " ticks");
            else fail(label, "only " + arrived + " of " + total + " arrived in " + ticks[0] + " ticks");
        }, 1L, 1L);
    }
}
