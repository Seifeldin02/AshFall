package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** End-to-end proof that a duel template's blocks and BLOCK ENTITIES survive the whole pipeline.
 *
 *  This exists because the pipeline failed silently for a long time and every intermediate step looked
 *  healthy while it did: the template read back correctly in memory, the save reported success, the clone
 *  produced a valid world, and only a player standing in the instance could tell that the arena was not
 *  there. Every stage is therefore asserted against real state on disk rather than against a return value.
 *
 *  The canary deliberately builds FAR from the origin -- the original defect only showed up thousands of
 *  blocks out, because the origin-area region files happened to be written by spawn generation and masked
 *  it. It plants a chest with a uniquely named item, a sign with unique text, a trial spawner and a vault:
 *  four different block-entity types, so a failure that only affects containers is distinguishable from one
 *  that affects all block entities.
 *
 *  Run it with /ashfall duelmap canary. It leaves a token behind in the database so that running it again
 *  after a restart also proves the committed snapshot survived the JVM. */
final class DuelMapCanary {

    /** Deliberately the coordinates the original failure was reported at, so a regression reproduces in
     *  exactly the place it was first seen: chunk (572, 700), region r.17.21.mca. */
    private static final int X = 9153, Y = 232, Z = 11208;
    private static final String KEY = "__canary";
    private static final String TOKEN_STATE = "duel_canary_token";

    private final SMPCore plugin;
    private final DuelMapService maps;

    DuelMapCanary(SMPCore plugin, DuelMapService maps) { this.plugin = plugin; this.maps = maps; }

    /** A map that exists only for this test. Not in config, so it can never be offered to a player, and its
     *  bounds are a single chunk so nothing about it is slow. */
    private DuelMapService.DuelMap map() {
        return new DuelMapService.DuelMap(KEY, "Canary", DuelMapService.BreakRule.FULL,
                X + 0.5, Y, Z - 2.5, X + 0.5, Y, Z + 2.5, null, null, null,
                new int[]{X - 16, Y - 8, Z - 16, X + 16, Y + 8, Z + 16}, null);
    }

    List<String> run() {
        List<String> out = new ArrayList<>();
        DuelMapService.DuelMap map = map();
        String token = "CANARY-" + System.currentTimeMillis();
        long boot = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
        List<World> instances = new ArrayList<>();
        /** The restart proof has to be taken BEFORE anything is wiped: the run below replaces the snapshot
         *  with a fresh one, so asking afterwards whether the old token survived would only ever find the new
         *  one. Read it first, judge it, and report the verdict at the end where it belongs. */
        String stored = plugin.db().state(TOKEN_STATE);
        String priorToken = null, priorBoot = null;
        if (stored != null) {
            int at = stored.lastIndexOf('@');
            priorToken = at < 0 ? stored : stored.substring(0, at);
            priorBoot = at < 0 ? null : stored.substring(at + 1);
        }
        boolean acrossRestart = priorToken != null && priorBoot != null && !priorBoot.equals(Long.toString(boot));
        String restartVerdict = acrossRestart ? verifySnapshotToken(map, priorToken) : null;
        try {
            // ---- 1. a clean void template ------------------------------------------------------
            wipe(map);
            World tpl = maps.template(map);
            if (tpl == null) return fail(out, "1. create template", "template world could not be created");
            out.add("ok  1. void template created: " + tpl.getName() + " at " + tpl.getWorldFolder().getAbsolutePath());

            // ---- 2. a distant chunk, loaded -----------------------------------------------------
            if (!tpl.getChunkAt(X >> 4, Z >> 4).isLoaded()) tpl.getChunkAt(X >> 4, Z >> 4).load(true);
            if (!tpl.isChunkLoaded(X >> 4, Z >> 4)) return fail(out, "2. load distant chunk", "chunk " + (X >> 4) + "," + (Z >> 4) + " would not load");
            out.add("ok  2. distant chunk " + (X >> 4) + "," + (Z >> 4) + " loaded (region r." + (X >> 9) + "." + (Z >> 9) + ".mca)");

            // ---- 3. block entities --------------------------------------------------------------
            plant(tpl, token);
            String missing = verify(tpl, token, "template (in memory)");
            if (missing != null) return fail(out, "3. place block entities", missing);
            out.add("ok  3. chest+item, sign, trial spawner and vault placed and read back in memory");

            // ---- 4/5. commit: full unload, then the region file must exist on disk ---------------
            String commit = maps.commitTemplate(map);
            if (!commit.startsWith("Committed")) return fail(out, "4. commit snapshot", commit);
            File region = new File(new File(maps.snapshotOf(map), "region"), "r." + (X >> 9) + "." + (Z >> 9) + ".mca");
            if (!region.isFile() || region.length() == 0)
                return fail(out, "5. region file on disk", "expected " + region.getAbsolutePath() + " to exist and be non-empty");
            out.add("ok  4. committed atomically: " + commit.substring(commit.indexOf('(')));
            out.add("ok  5. " + region.getName() + " written to the snapshot (" + (region.length() / 1024) + " KB)");

            // ---- 6. reload the template from disk ----------------------------------------------
            /** commitTemplate fully unloaded the world before copying, so what is loaded now was read back
             *  off the disk, not left over in memory. */
            World reloaded = Bukkit.getWorld(map.templateWorld());
            if (reloaded == null) return fail(out, "6. reload template", "the template did not reopen after the commit");
            reloaded.getChunkAt(X >> 4, Z >> 4).load(true);
            missing = verify(reloaded, token, "template (reloaded from disk)");
            if (missing != null) return fail(out, "6. reload template", missing);
            out.add("ok  6. template reloaded from disk still holds all four block entities");

            // ---- 7. clone, twice, independently -------------------------------------------------
            World first = maps.createInstance(map);
            if (first == null) return fail(out, "7. clone", "createInstance returned null");
            instances.add(first);
            missing = verify(first, token, "instance " + first.getName());
            if (missing != null) return fail(out, "7. clone", missing);
            String loot = checkLootEligibility(first, token);
            if (loot != null) return fail(out, "7c. loot eligibility", loot);
            int[] census = maps.chestCensus(first);
            if (census[0] != 2 || census[1] != 1)
                return fail(out, "7. loot census", "expected 2 single + 1 double chest, counted " + census[0]
                        + " + " + census[1] + " - a double chest counted twice would roll twice the loot");
            World second = maps.createInstance(map);
            if (second == null) return fail(out, "7. clone twice", "the second createInstance returned null");
            instances.add(second);
            missing = verify(second, token, "instance " + second.getName());
            if (missing != null) return fail(out, "7. clone twice", missing);
            if (first.getName().equals(second.getName())) return fail(out, "7. clone twice", "both clones got the same world name");
            /** Isolation: breaking the chest in one instance must not be visible in the other. */
            first.getBlockAt(X, Y, Z).setType(Material.AIR, false);
            if (second.getBlockAt(X, Y, Z).getType() != Material.BARREL)
                return fail(out, "7. isolation", "removing a block in one instance changed the other");
            out.add("ok  7. two independent clones, both complete, neither affected by the other");
            out.add("ok  7b. loot census sees 1 single + 1 double chest, so a double chest rolls once");
            out.add("ok  7c. the 2 empty template chests were rolled, the pre-stocked one untouched, no reroll");

            // ---- 8. survive a restart ------------------------------------------------------------
            plugin.db().state(TOKEN_STATE, token + "@" + boot);
            if (!acrossRestart)
                out.add("--  8. restart proof: token stored. Restart the server and run this again to complete it.");
            else if (restartVerdict == null)
                out.add("ok  8. the snapshot committed before this restart survived the JVM intact (" + priorToken + ")");
            else {
                out.add("FAIL 8. restart proof: " + restartVerdict);
                out.add("CANARY FAILED");
                return out;
            }
            out.add("CANARY PASSED");
        } catch (Throwable error) {
            out.add("FAIL canary threw: " + error);
            plugin.getLogger().warning("[duel-maps] canary threw: " + error);
        } finally {
            for (World world : instances) maps.destroyInstance(world, null);
        }
        return out;
    }

    /** Reads the committed snapshot the way a match would -- by cloning it -- and looks for the token that
     *  was written before the last restart. Proves the snapshot survived the JVM, not just the session. */
    private String verifySnapshotToken(DuelMapService.DuelMap map, String token) {
        if (!maps.hasSnapshot(map)) return "there is no committed snapshot on disk any more";
        World probe = maps.createInstance(map);
        if (probe == null) return "the committed snapshot would not clone";
        try { return verifyToken(probe, token, "post-restart clone"); }
        finally { maps.destroyInstance(probe, null); }
    }

    /** Removes every trace of a previous canary run so a pass cannot be inherited from an old one. */
    private void wipe(DuelMapService.DuelMap map) {
        World existing = Bukkit.getWorld(map.templateWorld());
        if (existing != null) {
            for (org.bukkit.entity.Player p : new ArrayList<>(existing.getPlayers()))
                p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            File folder = existing.getWorldFolder();
            Bukkit.unloadWorld(existing, false);
            delete(folder);
        } else delete(new File(maps.customWorldDir(), map.templateWorld()));
        delete(maps.snapshotOf(map));
    }

    private void delete(File folder) {
        if (folder == null || !folder.exists()) return;
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(folder.toPath())) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) { } });
        } catch (Exception ignored) { }
    }

    /** Several different block-entity kinds, so a partial failure is diagnosable rather than just "empty".
     *
     *  The container whose CONTENTS are asserted is a BARREL, not a chest, and that is deliberate: an
     *  instance re-rolls every chest on the map by design, so a chest's contents legitimately differ from the
     *  template's and cannot prove that container NBT survived a clone. A barrel is never touched by the loot
     *  roller, so it is the honest probe. A lone chest and a connected pair are planted alongside it, to prove
     *  the loot roller sees exactly two containers and counts the double chest once rather than twice.
     *
     *  (This is not a hypothetical distinction: the first run of this canary "failed" on a chest that had in
     *  fact survived the clone perfectly and then been correctly re-rolled.) */
    private void plant(World world, String token) {
        world.getBlockAt(X, Y - 1, Z).setType(Material.STONE, false);

        world.getBlockAt(X, Y, Z).setType(Material.BARREL, false);
        if (world.getBlockAt(X, Y, Z).getState(false) instanceof org.bukkit.block.Barrel barrel)
            barrel.getInventory().setItem(0, marked(Material.PAPER, token));

        world.getBlockAt(X, Y + 1, Z).setType(Material.OAK_SIGN, false);
        if (world.getBlockAt(X, Y + 1, Z).getState(false) instanceof Sign sign) {
            sign.getSide(Side.FRONT).line(0, Component.text(token));
            sign.update(true, false);
        }

        world.getBlockAt(X + 2, Y, Z).setType(Material.TRIAL_SPAWNER, false);
        world.getBlockAt(X + 4, Y, Z).setType(Material.VAULT, false);
        world.getBlockAt(X + 6, Y, Z).setType(Material.CHEST, false);
        /** A chest the BUILDER stocked. It must come through the clone with exactly these contents and must
         *  never be rolled over -- an admin who put something in a chest on purpose meant it. */
        world.getBlockAt(X + 11, Y, Z).setType(Material.CHEST, false);
        if (world.getBlockAt(X + 11, Y, Z).getState(false) instanceof Chest stocked)
            stocked.getInventory().setItem(0, marked(Material.BRICK, token + "-STOCKED"));
        /** A double chest is not two chests next to each other: the halves have to declare each other. A
         *  chest facing north with type LEFT connects to the block east of it, so this pair really is one
         *  container -- which is the whole point of planting it. */
        world.getBlockAt(X + 8, Y, Z).setBlockData(chestHalf(org.bukkit.block.data.type.Chest.Type.LEFT), false);
        world.getBlockAt(X + 9, Y, Z).setBlockData(chestHalf(org.bukkit.block.data.type.Chest.Type.RIGHT), false);
    }

    private ItemStack marked(Material type, String name) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        item.setItemMeta(meta);
        return item;
    }

    private String nameOf(ItemStack item) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta().displayName() == null) return null;
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(item.getItemMeta().displayName());
    }

    private org.bukkit.block.data.BlockData chestHalf(org.bukkit.block.data.type.Chest.Type half) {
        org.bukkit.block.data.type.Chest data = (org.bukkit.block.data.type.Chest) Material.CHEST.createBlockData();
        data.setFacing(org.bukkit.block.BlockFace.NORTH);
        data.setType(half);
        return data;
    }

    /** The restart proof, and only that: did the token written before the last restart survive the JVM inside
     *  the committed snapshot? Deliberately checks the two identity probes rather than the full fixture list,
     *  because the snapshot being read was committed by whatever version of this canary ran last -- asserting
     *  today's fixtures against yesterday's snapshot would report a fixture change as a persistence failure. */
    private String verifyToken(World world, String token, String where) {
        world.getChunkAt(X >> 4, Z >> 4).load(true);
        if (!(world.getBlockAt(X, Y, Z).getState(false) instanceof org.bukkit.block.Barrel barrel))
            return where + ": the barrel is not a block entity (block is " + world.getBlockAt(X, Y, Z).getType() + ")";
        String held = nameOf(barrel.getInventory().getItem(0));
        if (!token.equals(held)) return where + ": the barrel holds '" + held + "' instead of '" + token + "'";
        if (!(world.getBlockAt(X, Y + 1, Z).getState(false) instanceof Sign sign))
            return where + ": the sign is not a block entity";
        String line = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(sign.getSide(Side.FRONT).line(0));
        return token.equals(line) ? null : where + ": the sign reads '" + line + "' instead of '" + token + "'";
    }

    /** null when everything is present; otherwise the first thing that is missing, named. */
    private String verify(World world, String token, String where) {
        for (int dx = 0; dx <= 11; dx++) world.getChunkAt((X + dx) >> 4, Z >> 4).load(true);
        if (!(world.getBlockAt(X, Y, Z).getState(false) instanceof org.bukkit.block.Barrel barrel))
            return where + ": the barrel is not a block entity (block is " + world.getBlockAt(X, Y, Z).getType() + ")";
        ItemStack held = barrel.getInventory().getItem(0);
        if (held == null || !held.hasItemMeta() || held.getItemMeta().displayName() == null)
            return where + ": the barrel is empty - its inventory did not survive";
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(held.getItemMeta().displayName());
        if (!token.equals(name)) return where + ": the barrel holds '" + name + "' instead of '" + token + "'";
        if (!(world.getBlockAt(X, Y + 1, Z).getState(false) instanceof Sign sign))
            return where + ": the sign is not a block entity (block is " + world.getBlockAt(X, Y + 1, Z).getType() + ")";
        String line = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(sign.getSide(Side.FRONT).line(0));
        if (!token.equals(line)) return where + ": the sign reads '" + line + "' instead of '" + token + "'";
        if (world.getBlockAt(X + 2, Y, Z).getType() != Material.TRIAL_SPAWNER)
            return where + ": the trial spawner is gone (block is " + world.getBlockAt(X + 2, Y, Z).getType() + ")";
        if (world.getBlockAt(X + 4, Y, Z).getType() != Material.VAULT)
            return where + ": the vault is gone (block is " + world.getBlockAt(X + 4, Y, Z).getType() + ")";
        if (!(world.getBlockAt(X + 6, Y, Z).getState(false) instanceof Chest))
            return where + ": the single chest is not a block entity";
        if (!(world.getBlockAt(X + 8, Y, Z).getState(false) instanceof Chest pair)
                || !(pair.getInventory().getHolder(false) instanceof org.bukkit.block.DoubleChest))
            return where + ": the double chest did not survive as a connected pair";
        if (!(world.getBlockAt(X + 11, Y, Z).getState(false) instanceof Chest))
            return where + ": the pre-stocked chest is not a block entity";
        return null;
    }

    /** The loot eligibility rule, asserted against a real clone: every chest that was EMPTY in the template
     *  gets rolled, every chest the builder STOCKED is left exactly as they left it, a double chest counts
     *  once, and nothing can roll a second time. */
    private String checkLootEligibility(World instance, String token) {
        int[] fill = maps.lastFill(instance);
        if (fill == null) return "the instance recorded no loot roll at all";
        /** One lone chest and one connected pair were empty; the fourth was stocked. */
        if (fill[0] != 2) return "expected 2 empty template chests to be rolled, got " + fill[0];
        if (fill[1] != 1) return "expected 1 pre-stocked chest to be left alone, got " + fill[1];
        if (!(instance.getBlockAt(X + 11, Y, Z).getState(false) instanceof Chest stocked))
            return "the pre-stocked chest is missing from the instance";
        String held = nameOf(stocked.getInventory().getItem(0));
        if (!(token + "-STOCKED").equals(held))
            return "the pre-stocked chest was rolled over (slot 0 now holds " + held + ")";
        /** And it must not be possible to roll the same instance twice -- a chunk reload must not reprint
         *  loot into a chest a duellist has already emptied. */
        int[] again = maps.fillChests(instance, mapOf(), false);
        if (again[0] != 0 || again[1] != 0) return "a second loot roll was allowed (" + again[0] + " chest(s) refilled)";
        return null;
    }

    private DuelMapService.DuelMap mapOf() { return map(); }

    private List<String> fail(List<String> out, String stage, String why) {
        out.add("FAIL " + stage + ": " + why);
        out.add("CANARY FAILED");
        return out;
    }
}
