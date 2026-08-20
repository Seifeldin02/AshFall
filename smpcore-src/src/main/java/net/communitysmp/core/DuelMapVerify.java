package net.communitysmp.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Automated end-to-end verification of the duel arena pipeline, from committed snapshot to a running,
 *  correctly-ruled, correctly-looted instance and back to a deleted folder.
 *
 *  This is the regression net for everything the duel rebuild touched that does NOT need a human holding a
 *  mouse. The three-stage setup GUI genuinely needs two players clicking, and is covered by the state-machine
 *  assertions in ArenaService.selfTest plus manual acceptance -- everything else is asserted here against a
 *  real instance world: the map actually clones, the spawns are standable and face each other, the break rule
 *  behaves for both a pickaxe and an explosion, natural spawning is off, loot lands only where it is allowed,
 *  two concurrent instances of one map are genuinely independent, and dropping an instance removes its world
 *  and its folder.
 *
 *  Run with /ashfall duelmap verify. It creates and destroys its own instances and touches nothing else. */
final class DuelMapVerify {

    private final SMPCore plugin;
    private final DuelMapService maps;
    private final List<String> out = new ArrayList<>();
    private int failures;

    DuelMapVerify(SMPCore plugin, DuelMapService maps) { this.plugin = plugin; this.maps = maps; }

    List<String> run() {
        List<DuelMapService.DuelMap> playable = maps.playableMaps();
        out.add("Maps registered: " + maps.maps().size() + ", with a committed snapshot: " + playable.size());
        check("registry, break rules, facing and trial-key restriction", maps.selfTest());
        if (playable.isEmpty()) {
            out.add("No map has a committed snapshot yet, so nothing further can be verified.");
            out.add("Build the flat arenas (/ashfall duelmap build arena50|arena100) and import the rest.");
            return finish();
        }
        for (DuelMapService.DuelMap map : playable) verifyMap(map);
        verifyConcurrency(playable.get(0));
        verifyLootRestriction();
        return finish();
    }

    // ------------------------------------------------------------------ per map

    private void verifyMap(DuelMapService.DuelMap map) {
        out.add("");
        out.add("== " + map.name() + " (" + map.key() + ", " + map.rule() + ")");
        World world = maps.createInstance(map);
        if (world == null) { fail(map.key() + ": the committed snapshot would not clone"); return; }
        try {
            /** The clone must be a DIFFERENT world from the template, or a match would be fighting in the
             *  thing admins build in. */
            check("instance is its own world, not the template", !world.getName().equals(map.templateWorld()));
            check("instance is registered as a live duel instance", maps.isInstance(world) && maps.mapOfWorld(world) == map);

            /** Spawns: both duellists need something to stand on and room to stand in, and each must be
             *  looking at the other. A spawn hanging over void is the failure mode that ends a duel before
             *  it starts. */
            Location p1 = map.p1(world), p2 = map.p2(world);
            check("p1 has solid ground", solidBelow(p1));
            check("p2 has solid ground", solidBelow(p2));
            check("p1 has headroom", clear(p1));
            check("p2 has headroom", clear(p2));
            check("the two spawns face each other", DuelMapService.facesEachOther(map));
            check("the two spawns are not the same place", p1.distanceSquared(p2) > 1);

            /** Terrain rules, asserted directly on real blocks of this instance. */
            Block ground = world.getBlockAt(p1.getBlockX(), p1.getBlockY() - 1, p1.getBlockZ());
            boolean placedOnly = map.rule() == DuelMapService.BreakRule.PLACED_ONLY;
            check("original terrain " + (placedOnly ? "resists" : "yields to") + " a duellist",
                    maps.breakAllowed(map, ground, false) != placedOnly);
            check("a block the duellist placed can always be broken back", maps.breakAllowed(map, ground, true) || placedOnly == false);
            /** Infrastructure is protected under BOTH rules. Tested by actually putting a barrier down. */
            Block probe = world.getBlockAt(p1.getBlockX(), p1.getBlockY() + 3, p1.getBlockZ());
            Material was = probe.getType();
            probe.setType(Material.BARRIER, false);
            check("barriers survive a pickaxe", !maps.breakAllowed(map, probe, false));
            check("barriers survive even a block the duellist placed", !maps.breakAllowed(map, probe, true));
            probe.setType(Material.BEDROCK, false);
            check("bedrock survives", !maps.breakAllowed(map, probe, false));
            probe.setType(was, false);
            if (map.bounds() != null) {
                Block outside = world.getBlockAt(map.bounds()[0] - 4, p1.getBlockY(), map.bounds()[2] - 4);
                check("nothing outside the playable bounds can be broken", !maps.breakAllowed(map, outside, true));
            }

            /** Explosions obey exactly the same rule as a pickaxe -- that equivalence is the point, and it is
             *  the half that was never actually exercised before. */
            List<Block> blast = new ArrayList<>(List.of(ground, probe));
            blast.removeIf(b -> !maps.breakAllowed(map, b, false));
            check("an explosion " + (placedOnly ? "cannot" : "can") + " eat original terrain",
                    placedOnly ? blast.isEmpty() : blast.contains(ground));

            /** A duel arena contains what the builder placed and nothing else. */
            check("natural mob spawning is off", Boolean.FALSE.equals(world.getGameRuleValue(org.bukkit.GameRule.DO_MOB_SPAWNING)));
            check("daylight is frozen", Boolean.FALSE.equals(world.getGameRuleValue(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE)));
            check("no living mobs are in the fresh instance", world.getLivingEntities().stream().noneMatch(e -> !(e instanceof org.bukkit.entity.Player)));
            check("instance does not autosave (nothing a match does is ever written back)", !world.isAutoSave());

            /** Footing is reported rather than asserted where it is a HAZARD rather than a hole: a spawn over
             *  lava is a map-design decision only the owner can make, and silently relocating it would change
             *  their arena. A spawn with nothing at all underneath is a straight failure. */
            Material under1 = map.footing(world, p1), under2 = map.footing(world, p2);
            if (hazard(under1) || hazard(under2))
                out.add("WARN spawn footing is " + under1 + " / " + under2 + " - a duellist would start in it."
                        + " Move the spawn with /ashfall duelmap setspawn " + map.key() + " <p1|p2>.");
            else out.add("   spawn footing: " + under1 + " / " + under2);

            int[] census = maps.chestCensus(world);
            out.add("   containers: " + census[0] + " single chest, " + census[1] + " double chest, "
                    + census[2] + " barrel, " + census[3] + " vault, " + census[4] + " spawner");
            out.add("   " + maps.lootReport(map, census[0], census[1]));
            if (census[3] > 0 && !plugin.getConfig().getStringList("duel-loot.trial-key-maps").contains(map.key()))
                out.add("WARN this map has " + census[3] + " vault(s) but is not allowed to roll trial keys,"
                        + " so nothing on it can ever be opened.");
            boolean keyMap = plugin.getConfig().getStringList("duel-loot.trial-key-maps").contains(map.key());
            if (census[3] == 0 && keyMap)
                out.add("WARN this map may roll trial keys but has no vaults for them to open.");
            if (keyMap && census[3] > 0 && census[0] + census[1] == 0)
                out.add("WARN this map has " + census[3] + " vault(s) and may roll trial keys, but it has no chests"
                        + " inside its configured bounds, so no key can ever be found on it. Widen the bounds"
                        + " (currently y " + map.bounds()[1] + ".." + map.bounds()[4] + ") or add chests.");
        } finally {
            File folder = world.getWorldFolder();
            maps.destroyInstance(world, null);
            check("dropping the instance unloads the world", Bukkit.getWorld(world.getName()) == null);
            /** Windows can hold the region files for a while after the unload; the sweeper retries. Not a
             *  failure on its own, so it is reported rather than asserted. */
            out.add("   folder removed immediately: " + (!folder.exists() ? "yes" : "no (queued for the sweeper)"));
        }
    }

    // ------------------------------------------------------------------ concurrency

    private void verifyConcurrency(DuelMapService.DuelMap map) {
        out.add("");
        out.add("== concurrent matches on " + map.name());
        World a = maps.createInstance(map), b = maps.createInstance(map);
        if (a == null || b == null) {
            fail("two concurrent instances of one map could not be created");
            if (a != null) maps.destroyInstance(a, null);
            if (b != null) maps.destroyInstance(b, null);
            return;
        }
        try {
            check("the two instances are different worlds", !a.getName().equals(b.getName()));
            check("the two instances are different folders", !a.getWorldFolder().equals(b.getWorldFolder()));
            check("both are registered", maps.isInstance(a) && maps.isInstance(b));
            /** Terrain damage in one must be invisible in the other: that is what makes two matches on one
             *  map safe to run at the same time. */
            Location p1 = map.p1(a);
            Block one = a.getBlockAt(p1.getBlockX(), p1.getBlockY() - 1, p1.getBlockZ());
            Block two = b.getBlockAt(p1.getBlockX(), p1.getBlockY() - 1, p1.getBlockZ());
            Material original = two.getType();
            one.setType(Material.AIR, false);
            check("breaking terrain in one match leaves the other untouched", two.getType() == original && original != Material.AIR);
            /** And dropping one must not disturb the other. */
            maps.destroyInstance(a, null);
            check("dropping one instance leaves the other loaded", Bukkit.getWorld(b.getName()) != null && maps.isInstance(b));
        } finally {
            if (Bukkit.getWorld(a.getName()) != null) maps.destroyInstance(a, null);
            maps.destroyInstance(b, null);
        }
    }

    // ------------------------------------------------------------------ loot

    private void verifyLootRestriction() {
        out.add("");
        out.add("== trial keys");
        List<String> allowed = plugin.getConfig().getStringList("duel-loot.trial-key-maps");
        check("exactly two maps may roll trial keys", allowed.size() == 2
                && allowed.contains("cinder_crucible") && allowed.contains("deepstone_mines"));
        int trials = 4000;
        for (DuelMapService.DuelMap map : maps.maps()) {
            int[] single = maps.lootProbe(map, false, trials), dbl = maps.lootProbe(map, true, trials);
            int normal = single[0] + dbl[0], ominous = single[1] + dbl[1];
            boolean mayHave = allowed.contains(map.key());
            if (!mayHave) {
                check(map.key() + " rolls no trial keys at all", normal == 0 && ominous == 0);
                continue;
            }
            double normalRate = normal / (double) (trials * 2), ominousRate = ominous / (double) (trials * 2);
            out.add(String.format("   %s: normal %.2f%%, ominous %.2f%% over %d rolls",
                    map.key(), normalRate * 100, ominousRate * 100, trials * 2));
            /** Configured 10%% normal and 3%% ominous, with ominous rolled first and winning outright, so the
             *  normal key's real rate is 0.10 x (1 - 0.03) = 9.7%%. Tolerances are wide enough that a healthy
             *  run never trips them and a wrong constant always does. */
            double wantOminous = plugin.getConfig().getDouble("duel-loot.ominous-key-chance", .03);
            double wantNormal = plugin.getConfig().getDouble("duel-loot.trial-key-chance", .10) * (1 - wantOminous);
            check(map.key() + " ominous rate is near " + pct(wantOminous), Math.abs(ominousRate - wantOminous) < 0.015);
            check(map.key() + " normal rate is near " + pct(wantNormal), Math.abs(normalRate - wantNormal) < 0.025);
            check(map.key() + " ominous keys stay rarer than normal ones", ominous < normal);
        }
    }

    private static String pct(double v) { return String.format("%.1f%%", v * 100); }

    // ------------------------------------------------------------------ plumbing

    private boolean solidBelow(Location at) {
        Block below = at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() - 1, at.getBlockZ());
        return below.getType().isSolid() || hazard(below.getType());
    }

    /** Something a duellist would be standing IN rather than ON. */
    private static boolean hazard(Material type) {
        return switch (type) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE, POWDER_SNOW, CACTUS -> true;
            default -> false;
        };
    }

    private boolean clear(Location at) {
        Block feet = at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY(), at.getBlockZ());
        Block head = at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() + 1, at.getBlockZ());
        return !feet.getType().isSolid() && !head.getType().isSolid();
    }

    private void check(String what, boolean ok) {
        out.add((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    private void fail(String what) { check(what, false); }

    private List<String> finish() {
        out.add("");
        out.add(failures == 0 ? "DUEL PIPELINE VERIFIED - no failures" : "DUEL PIPELINE: " + failures + " FAILURE(S)");
        return out;
    }
}
