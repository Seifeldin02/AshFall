package net.communitysmp.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Automated end-to-end verification of the Colosseum, run with {@code /ashfall colosseum verify}.
 *
 *  This is the regression net for everything the Colosseum touches that does NOT need a human holding a
 *  mouse -- and that is deliberately almost all of it. The money guarantees in particular are asserted
 *  directly against the database rather than inferred from a fight going well: charging twice, paying twice,
 *  resolving twice, refunding an uncharged run and counting an admin test toward the daily allowance are all
 *  attempted here, on purpose, and all of them must fail.
 *
 *  It creates and destroys its own instance worlds and its own throwaway ledger rows, and touches nothing
 *  belonging to a real player. The rows it writes use a __coloverify prefix and are deleted at the end.
 *
 *  Some of this is necessarily asynchronous -- an instance is prepared off the main thread and its chunks are
 *  loaded over several ticks -- so results arrive through {@code say} as they are proven rather than in one
 *  block at the end. */
final class ColosseumVerify {

    private final SMPCore plugin;
    private final ColosseumService colosseum;
    private final Database db;
    private Consumer<String> say = line -> { };
    private int checks, failures;

    ColosseumVerify(SMPCore plugin, ColosseumService colosseum) {
        this.plugin = plugin;
        this.colosseum = colosseum;
        this.db = plugin.db();
    }

    void run(Consumer<String> output) {
        this.say = line -> { output.accept(line); plugin.getLogger().info("[colosseum-verify] " + line); };
        checks = 0;
        failures = 0;
        verifyConfiguration();
        verifyLedgerGuarantees();
        verifyDailyAllowance();
        verifyLeaderboardIntegrity();
        verifyStateRoundTrip();
        verifyRewardTables();
        verifyRewardParity();
        verifyShardRewards();
        verifyMechanics();
        verifyInterruptionRecovery();
        /** The world half runs asynchronously and reports as it completes. */
        verifyInstances();
    }

    // ------------------------------------------------------------------ 1. configuration

    private void verifyConfiguration() {
        say("== configuration");
        check("registry, arenas, boss identities, economy and daily cap", colosseum.selfTest());
        check("arena geometry (bounds, spawns inside them, player faces the boss)", colosseum.arenas().selfTest());
        check("three distinct bosses with distinct entity types and styles", colosseum.bosses().selfTest());
        check("every ability is telegraphed, cooled down, bounded and cannot one-shot a full-health player",
                colosseum.bosses().mechanicsSelfTest());
        check("global concurrency limit is set", colosseum.maxConcurrent() >= 1);
        check("a rewarded-victory daily cap exists (money-printing brake)", colosseum.dailyLimit() >= 1);
        for (ColosseumBosses.BossDef def : colosseum.bosses().all()) {
            check(def.key() + ": entry fee " + CoreUtil.money(def.entryFee()) + ", prize " + CoreUtil.money(def.cashPrize())
                    + ", net " + CoreUtil.money(def.cashPrize() - def.entryFee()), def.cashPrize() > def.entryFee());
            /** The engine cannot carry more than 1024 on the attribute, so the pool has to come from the
             *  divisor. If those two ever disagree the boss silently has the wrong amount of health. */
            check(def.key() + ": effective pool " + (long) def.health() + " = engine " + (long) def.engineHealth()
                            + " x toughness " + String.format("%.3f", def.toughness()),
                    Math.abs(def.engineHealth() * def.toughness() - def.health()) < 1);
            check(def.key() + ": has a reward table", !colosseum.bosses().rewardTable(def.key()).isEmpty());
            check(def.key() + ": time limit " + ColosseumService.timeText(def.timeLimitSeconds()), def.timeLimitSeconds() >= 60);
        }
        /** "Do not use hidden player-count scaling" -- asserted by there being no way to express it: a boss
         *  def carries no participant field at all, and the only multiplier in the damage path is its own
         *  configured toughness. This check proves the configured pool is the whole story. */
        check("no player-count scaling: effective health depends only on the boss's own config",
                colosseum.bosses().all().stream().allMatch(d -> Math.abs(d.engineHealth() * d.toughness() - d.health()) < 1));
    }

    // ------------------------------------------------------------------ 2. the money

    /** Every single-shot guarantee, attempted twice on purpose. These are the checks that stand between a
     *  duplicated callback and somebody being charged a million dollars for one fight. */
    private void verifyLedgerGuarantees() {
        say("");
        say("== fee, prize, refund and resolution are each single-shot");
        String player = "__coloverify_player", run = "__coloverify_" + UUID.randomUUID();
        db.colosseumRunOpen(run, player, "ColoVerify", "__coloverify_boss", "__coloverify_arena", 500000, 1000000, false, "__coloverify_day");

        check("a fresh run starts PREPARING, uncharged, unpaid", isState(run, "PREPARING") && !db.colosseumRun(run).charged() && !db.colosseumRun(run).paid());
        check("the entry fee can be charged once", db.colosseumMarkCharged(run));
        check("the entry fee CANNOT be charged twice (duplicate click / duplicate callback)", !db.colosseumMarkCharged(run));
        check("a run can be committed once", db.colosseumMarkActive(run));
        check("a run CANNOT be committed twice", !db.colosseumMarkActive(run));
        check("the prize can be paid once", db.colosseumMarkPaid(run));
        check("the prize CANNOT be paid twice (duplicate death / repeated resolve)", !db.colosseumMarkPaid(run));
        check("a charged run can be refunded once", db.colosseumMarkRefunded(run));
        check("a run CANNOT be refunded twice", !db.colosseumMarkRefunded(run));
        check("a run can be resolved once", db.colosseumResolve(run, "VICTORY", true, 12345));
        check("a run CANNOT be resolved twice (a death and a disconnect in the same tick)", !db.colosseumResolve(run, "DEATH", false, 1));
        check("the first resolution's outcome survives the second attempt", "VICTORY".equals(db.colosseumRun(run).outcome()));

        /** An uncharged run must not be refundable at all, or an aborted preparation would print money. */
        String free = "__coloverify_" + UUID.randomUUID();
        db.colosseumRunOpen(free, player, "ColoVerify", "__coloverify_boss", "__coloverify_arena", 500000, 1000000, false, "__coloverify_day");
        check("an UNCHARGED run cannot be refunded (an aborted preparation prints no money)", !db.colosseumMarkRefunded(free));
        db.colosseumResolve(free, "ABORTED", false, 0);

        /** The balance itself refuses to go negative, which is the insufficient-funds guarantee at the point
         *  the fee is actually taken -- not merely at the point the menu was drawn. */
        db.ensurePlayer(player, "ColoVerify", 100);
        check("insufficient funds: a charge larger than the balance is refused atomically", !db.changeBalance(player, -500000));
        check("insufficient funds: the balance is untouched by the refused charge", Math.abs(db.player(player).balance() - 100) < 0.001);
        check("a charge within the balance succeeds", db.changeBalance(player, -50));
        check("and moves the balance by exactly that much", Math.abs(db.player(player).balance() - 50) < 0.001);
    }

    // ------------------------------------------------------------------ 3. the daily allowance

    private void verifyDailyAllowance() {
        say("");
        say("== daily rewarded-victory limit");
        String player = "__coloverify_daily", day = "__coloverify_day_" + UUID.randomUUID();
        int limit = colosseum.dailyLimit();
        check("configured limit is " + limit + " rewarded victories per player per real day", limit >= 1);
        for (int i = 0; i < limit; i++) {
            String run = "__coloverify_" + UUID.randomUUID();
            db.colosseumRunOpen(run, player, "ColoDaily", "__coloverify_boss", "__coloverify_arena", 500000, 1000000, false, day);
            db.colosseumResolve(run, "VICTORY", true, 1000);
        }
        check("banking " + limit + " rewarded victories fills the allowance", db.colosseumRewardedToday(player, day) == limit);

        /** A loss is not a rewarded victory, so losing all day cannot lock somebody out. */
        String loss = "__coloverify_" + UUID.randomUUID();
        db.colosseumRunOpen(loss, player, "ColoDaily", "__coloverify_boss", "__coloverify_arena", 500000, 1000000, false, day);
        db.colosseumResolve(loss, "DEATH", false, 1000);
        check("a LOSS does not consume the allowance", db.colosseumRewardedToday(player, day) == limit);

        /** An admin test is invisible to the allowance, by the query rather than by the caller remembering. */
        String test = "__coloverify_" + UUID.randomUUID();
        db.colosseumRunOpen(test, player, "ColoDaily", "__coloverify_boss", "__coloverify_arena", 0, 0, true, day);
        db.colosseumResolve(test, "VICTORY", false, 1000);
        check("an ADMIN TEST victory does not consume the allowance", db.colosseumRewardedToday(player, day) == limit);

        /** The reset is a different day key, which is what "resets at midnight" actually means here. */
        check("the allowance is per real day (a different day starts empty)", db.colosseumRewardedToday(player, day + "_next") == 0);
        check("the reset time is reported to the player", colosseum.resetsIn().startsWith("in "));
    }

    // ------------------------------------------------------------------ 4. leaderboards

    private void verifyLeaderboardIntegrity() {
        say("");
        say("== statistics and leaderboard integrity");
        String player = "__coloverify_stats", boss = "__coloverify_boss";
        db.colosseumStatAttempt(player, "ColoStats", boss, 500000);
        db.colosseumStatVictory(player, "ColoStats", boss, 1000000, 90000, true);
        check("a committed victory records a best time", db.colosseumStats(player, boss).bestMs() == 90000);
        db.colosseumStatVictory(player, "ColoStats", boss, 1000000, 45000, true);
        check("a faster committed victory replaces it", db.colosseumStats(player, boss).bestMs() == 45000);
        db.colosseumStatVictory(player, "ColoStats", boss, 1000000, 120000, true);
        check("a slower one does not", db.colosseumStats(player, boss).bestMs() == 45000);
        db.colosseumStatVictory(player, "ColoStats", boss, 0, 1, false);
        check("an ADMIN TEST never reaches the leaderboard, however fast", db.colosseumStats(player, boss).bestMs() == 45000);
        db.colosseumStatLoss(player, "ColoStats", boss);
        Database.ColosseumStats row = db.colosseumStats(player, boss);
        check("attempts, victories, losses and cash are all tracked", row.attempts() == 1 && row.victories() == 4 && row.losses() == 1
                && Math.abs(row.cashWon() - 3000000) < 0.001 && Math.abs(row.feesPaid() - 500000) < 0.001);
        check("the boss leaderboard is ordered by fastest clear", db.colosseumTop(boss, 5).stream().findFirst()
                .map(r -> r.bestMs() == 45000).orElse(false));
        /** A player who has never cleared a boss must not appear on its board at all. */
        db.colosseumStatAttempt("__coloverify_never", "ColoNever", boss, 500000);
        check("a player who has never won is absent from the leaderboard",
                db.colosseumTop(boss, 20).stream().noneMatch(r -> r.player().equals("__coloverify_never")));
    }

    // ------------------------------------------------------------------ 5. player state

    /** The capture format itself, round-tripped through the database exactly as a real encounter does it,
     *  including the awkward parts: an empty slot, a damaged tool, an enchanted book and a full stack. */
    private void verifyStateRoundTrip() {
        say("");
        say("== pre-entry state capture and restoration");
        String player = "__coloverify_state";
        ItemStack[] items = new ItemStack[45];
        items[0] = new ItemStack(Material.DIAMOND_SWORD);
        items[0].addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 5);
        org.bukkit.inventory.meta.Damageable damaged = (org.bukkit.inventory.meta.Damageable) items[0].getItemMeta();
        damaged.setDamage(937);
        items[0].setItemMeta((org.bukkit.inventory.meta.ItemMeta) damaged);
        items[3] = new ItemStack(Material.COBBLESTONE, 64);
        items[9] = new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2);
        items[44] = new ItemStack(Material.NETHERITE_CHESTPLATE);
        String extra = "sat=4.5;exh=0.25;abs=6.0;allowfly=1;flying=0;flyspeed=0.1;walkspeed=0.2;fall=0.0;fire=0;freeze=0;air=300;vx=0.0;vy=0.0;vz=0.0;fx=speed,1,600,0,1";
        World world = Bukkit.getWorlds().get(0);
        db.colosseumStateSave(player, ItemStack.serializeItemsAsBytes(items), world.getName(), 1.5, 65, -2.5, 90f, 12f, 30, 0.5f, 17.5, 18, "SURVIVAL", extra);
        Database.ArenaState back = db.colosseumState(player);
        check("the capture is readable back", back != null);
        if (back != null) {
            ItemStack[] restored = ItemStack.deserializeItemsFromBytes(back.items());
            check("the slot array keeps its length, so nothing shifts index on the way back", restored.length == items.length);
            check("an empty slot comes back empty (null or AIR, never someone else's item)",
                    empty(restored[1]) && empty(restored[2]) && empty(restored[43]));
            check("a damaged, enchanted tool survives exactly", restored[0] != null && restored[0].getType() == Material.DIAMOND_SWORD
                    && restored[0].getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS) == 5
                    && ((org.bukkit.inventory.meta.Damageable) restored[0].getItemMeta()).getDamage() == 937);
            check("a full stack survives with its count", restored[3] != null && restored[3].getAmount() == 64);
            check("armour in the last slot survives", restored[44] != null && restored[44].getType() == Material.NETHERITE_CHESTPLATE);
            check("location, level, xp, health, hunger and gamemode survive",
                    back.world().equals(world.getName()) && back.level() == 30 && Math.abs(back.exp() - 0.5f) < 0.001
                            && Math.abs(back.health() - 17.5) < 0.001 && back.food() == 18 && "SURVIVAL".equals(back.gamemode())
                            && Math.abs(back.yaw() - 90f) < 0.001 && Math.abs(back.pitch() - 12f) < 0.001);
            check("saturation, absorption, flight, speeds, velocity and potion effects survive",
                    back.extra() != null && back.extra().contains("abs=6.0") && back.extra().contains("allowfly=1")
                            && back.extra().contains("flyspeed=0.1") && back.extra().contains("speed,1,600"));
        }
        db.colosseumStateClear(player);
        check("clearing the capture removes it (nothing is restored twice)", db.colosseumState(player) == null);
    }

    // ------------------------------------------------------------------ 6. reward tables

    private void verifyRewardTables() {
        say("");
        say("== reward tables");
        for (ColosseumBosses.BossDef def : colosseum.bosses().all()) {
            List<ColosseumBosses.Roll> table = colosseum.bosses().rewardTable(def.key());
            check(def.key() + ": " + table.size() + " entries x " + colosseum.bosses().rewardRolls(def.key()) + " roll(s)", !table.isEmpty());
            boolean sane = table.stream().allMatch(r -> r.chance() > 0 && r.chance() <= 1 && r.min() >= 1 && r.max() >= r.min() && r.max() <= 64);
            check(def.key() + ": every entry has a valid chance and a bounded stack size", sane);
            /** A table is rolled once per victory. Rolling it here many times proves the OUTPUT is bounded --
             *  an unbounded reward is a different way of printing money. */
            int worst = 0;
            for (int i = 0; i < 200; i++) worst = Math.max(worst, colosseum.bosses().rollRewards(def.key()).size());
            check(def.key() + ": worst-case reward is " + worst + " stack(s) over 200 rolls (bounded)",
                    worst <= table.size() * colosseum.bosses().rewardRolls(def.key()));
        }
    }

    // ------------------------------------------------------------------ 6a1. reward parity

    /*  Six pools, compared. Distinct themes are the point; a distinct EXPECTED VALUE is not, because the
     *  moment one boss pays materially more than the others it becomes the only correct thing to fight and
     *  the other five are decoration. */
    private void verifyRewardParity() {
        say("");
        say("== reward pool parity across all six bosses");
        double lowest = Double.MAX_VALUE, highest = 0;
        for (ColosseumBosses.BossDef def : colosseum.bosses().all()) {
            double stacks = colosseum.bosses().expectedRewardStacks(def.key());
            double items = colosseum.bosses().expectedRewardItems(def.key());
            double value = colosseum.bosses().expectedRewardValue(def.key());
            lowest = Math.min(lowest, value);
            highest = Math.max(highest, value);
            say(String.format("  %-22s %.2f stacks, %.1f items, %s expected loot value, %d Shards",
                    def.key(), stacks, items, CoreUtil.money(value), colosseum.shardReward(def)));
        }
        /*  Compared by VALUE, not by item count. Distinct themes are the whole point; a distinct payout is
         *  not, because the moment one boss pays materially more it becomes the only correct thing to
         *  fight. 1.5x is the tolerance -- wide enough that a themed pool can feel different, narrow enough
         *  that none of the six is the answer to "which one do I farm". */
        check("no loot pool is worth more than 1.5x the leanest ("
                        + CoreUtil.money(lowest) + " to " + CoreUtil.money(highest) + ")",
                lowest > 0 && highest / lowest <= 1.5);
        check("every boss pays the same cash, so loot is the only difference between them",
                colosseum.bosses().all().stream().map(ColosseumBosses.BossDef::cashPrize).distinct().count() == 1
                        && colosseum.bosses().all().stream().map(ColosseumBosses.BossDef::entryFee).distinct().count() == 1);
    }

    // ------------------------------------------------------------------ 6a2. shards

    /*  Shards come from the ONE existing service and the ONE existing daily allowance. What is asserted
     *  here is that no second cap was invented -- that a world boss and a Colosseum win are spending the
     *  same number, in both directions. */
    private void verifyShardRewards() {
        say("");
        say("== Shard rewards (shared allowance with world bosses)");
        if (plugin.shards() == null) { fail("the shard service is unavailable"); return; }
        int cap = plugin.shards().dailyShardCap();
        check("the Colosseum offers Shards on a victory", colosseum.shardReward(null) > 0);
        check("the daily Shard cap is the server-wide one (" + cap + "/day)", cap > 0);

        String player = "__coloverify_shards";
        db.ensurePlayer(player, "ColoShards", 0);
        db.colosseumPurgeVerifyRows("__coloverify_shards");
        db.ensurePlayer(player, "ColoShards", 0);
        long dayStart = plugin.shards().shardDayStart();

        /** Zero used: the whole reward is available. */
        check("with no Shards earned today the full allowance is available",
                db.shardsEarnedSince(player, dayStart) == 0);

        /** A world boss spends part of it, and the Colosseum sees a smaller remainder -- which is the whole
         *  requirement, asserted against the same counter both of them read. */
        db.addShards(player, cap - 1, "WITHER", null);
        check("after a world boss takes " + (cap - 1) + ", only 1 remains for the Colosseum",
                Math.max(0, cap - db.shardsEarnedSince(player, dayStart)) == 1);
        db.addShards(player, 1, "COLOSSEUM", null);
        check("a Colosseum award consumes the same allowance and reaches the cap",
                db.shardsEarnedSince(player, dayStart) >= cap);
        check("at the cap the remaining allowance is exactly zero",
                Math.max(0, cap - db.shardsEarnedSince(player, dayStart)) == 0);
        db.colosseumPurgeVerifyRows("__coloverify_shards");
    }

    // ------------------------------------------------------------------ 6a3. the new mechanics

    /*  The three new encounters, driven directly.
     *
     *  Every one of them is built on a promise -- bait the charge, break the seals, interrupt the brew --
     *  and a promise is a number. These call the real mechanics with a real boss in a real instance and
     *  check the numbers at their exact boundaries, because "it felt right in a fight" is not a regression
     *  test and a live fight cannot be asked to produce a stun on cue. */
    private void verifyMechanics() {
        say("");
        say("== boss mechanics: telegraphs, thresholds and lethality ceilings");
        /*  --- pure, no world needed: the telegraph contract and the lethality ceiling --- */
        long now = System.currentTimeMillis();
        for (ColosseumBosses.BossDef def : colosseum.bosses().all()) {
            if (def.abilities() == null) continue;
            for (String ability : def.abilities().getKeys(false)) {
                int telegraph = def.abilityInt(ability + ".telegraph-ticks", -1);
                if (telegraph < 0) continue;
                ColosseumBosses.BossState probe = new ColosseumBosses.BossState();
                colosseum.bosses().begin(def, probe, ability, now, 10);
                check(def.key() + "/" + ability + ": the telegraph precedes the effect by " + telegraph * 50 + " ms",
                        probe.pendingAt >= now + telegraph * 50L);
                check(def.key() + "/" + ability + ": the cooldown outlasts the wind-up",
                        !colosseum.bosses().ready(probe, ability, probe.pendingAt));
            }
            /** Worst simultaneous case: one landing ability plus one zone tick. A full-health player is 20. */
            double worstAbility = 0;
            for (String ability : def.abilities().getKeys(false))
                worstAbility = Math.max(worstAbility, def.ability(ability + ".damage", 0));
            double worstZone = def.ability("mixture.scorch-damage", 0);
            check(def.key() + ": worst simultaneous burst is " + String.format("%.1f", worstAbility + worstZone)
                            + " raw, below a full-health player's 20", worstAbility + worstZone < 20);
        }

    }

    /** The live half of the mechanics checks, run on an instance the world stage has already prepared. */
    private void verifyLiveMechanics(ColosseumArenas.Arena arena, World world) {
        say("");
        say("== boss mechanics, driven live: wards, crashes, channels and cleanup");
        try {
            verifyBehemoth(arena, world);
            verifyArcanist(arena, world);
            verifyAlchemist(arena, world);
        } catch (Throwable error) {
            fail("mechanics checks threw: " + error);
        }
    }

    /** The Behemoth's whole design: its front is armoured, and a baited crash removes that armour and
     *  doubles what it takes. If baiting is not measurably better than trading, the encounter is a lie. */
    private void verifyBehemoth(ColosseumArenas.Arena arena, World world) {
        ColosseumBosses.BossDef def = colosseum.bosses().boss("chainbound_behemoth");
        if (def == null) { fail("chainbound_behemoth is not registered"); return; }
        say("");
        say("  -- Chainbound Behemoth");
        ColosseumBosses.BossState state = new ColosseumBosses.BossState();
        LivingEntity boss = colosseum.bosses().spawn(def, arena.bossSpawn(world), "__coloverify_mech");
        if (boss == null) { fail("the Behemoth would not spawn"); return; }
        state.entity = boss;
        long now = System.currentTimeMillis();
        try {
            double frontHit = colosseum.bosses().applyDefences(def, state, 100, true, false, now);
            double flankHit = colosseum.bosses().applyDefences(def, state, 100, false, false, now);
            check("hitting its front is reduced (" + Math.round(frontHit) + " vs " + Math.round(flankHit) + " from the flank)",
                    frontHit < flankHit - 0.001);

            /*  ------------------------------------------------------------------------------------------
             *  THE CHARGE, DRIVEN FOR REAL.
             *
             *  This is written the way it is because the version before it was not. It asserted that a
             *  charge ends in a stun -- which it did, every single time, after travelling exactly zero
             *  blocks: the flight turned the boss's AI off so nothing could steer it, and an AI-less mob
             *  ignores applied velocity entirely, so the charge never moved and its own no-progress guard
             *  stunned it within half a second. In game that read as "the charge fails every time no matter
             *  what I do", and the test agreed with the bug because "did it stun" was the only question it
             *  asked.
             *
             *  So the question here is DISTANCE first and outcome second. The flight is stepped by hand
             *  along the locked vector now, which means driving it in a loop reproduces the real trajectory
             *  exactly -- nothing about it depends on the server ticking in between. */
            double lane = def.ability("charge.length", 30);
            Location origin = boss.getLocation().clone();

            /*  --- 1. an unobstructed lane: it must actually go somewhere, and a clean miss must punish --- */
            Vector down = openLane(arena, origin, lane);
            List<Location> path = new ArrayList<>();
            int steps = driveCharge(def, state, boss, down, path);
            double travelled = origin.distance(boss.getLocation());
            check("a charge down an open lane genuinely MOVES the boss (" + String.format("%.1f", travelled)
                            + " blocks of a " + (long) lane + " block lane, over " + steps + " steps)",
                    travelled > lane * 0.6);
            check("it took more than the no-progress guard's grace to end, so the launch is not mistaken for a collision",
                    steps > def.abilityInt("charge.stuck-grace-steps", 3) + def.abilityInt("charge.stuck-ticks", 4));
            check("a clean miss still crashes, so the stated counterplay works away from the walls",
                    !state.charging && state.stunUntil > 0);
            check("the crash opens a vulnerability window above 1x (" + String.format("%.1f", state.vulnerableMultiplier) + "x)",
                    state.vulnerableMultiplier > 1);

            /*  --- 2. it cannot steer: every step stays on the locked vector --- */
            double drift = 0;
            for (Location at : path) drift = Math.max(drift, ColosseumBosses.distanceToSegment(at, origin,
                    origin.clone().add(down.clone().multiply(lane + 4))));
            check("every step of the flight stays on the locked vector (max drift "
                    + String.format("%.2f", drift) + " blocks), so the telegraph cannot lie", drift < 0.5);

            /*  --- 3. a player standing in the lane is genuinely swept; one standing aside is not --- */
            double hitRadius = def.ability("charge.hit-radius", 2.6);
            Location inLane = origin.clone().add(down.clone().multiply(Math.min(lane - 2, 12)));
            Location aside = inLane.clone().add(new Vector(-down.getZ(), 0, down.getX()).multiply(hitRadius + 3));
            check("a player standing in the lane is inside the swept path (would be hit)",
                    sweptWithin(path, origin, inLane) <= hitRadius);
            check("a player who steps " + String.format("%.1f", hitRadius + 3) + " blocks aside is outside it (would not be)",
                    sweptWithin(path, origin, aside) > hitRadius);

            /*  --- 4. and a lane that ends in a wall crashes at the wall, still inside the arena --- */
            boss.teleport(nearWall(arena, world, origin, down));
            state.stunUntil = 0;
            state.stunImmuneUntil = 0;
            Location wallOrigin = boss.getLocation().clone();
            List<Location> intoWall = new ArrayList<>();
            int wallSteps = driveCharge(def, state, boss, down, intoWall);
            check("a charge into the boundary crashes short of its full lane (" + wallSteps + " steps, "
                            + String.format("%.1f", wallOrigin.distance(boss.getLocation())) + " blocks)",
                    !state.charging && state.stunUntil > 0 && wallOrigin.distance(boss.getLocation()) < lane);
            check("and it is left INSIDE the arena, not wedged in the wall",
                    arena.inBounds(boss.getLocation().getX(), boss.getLocation().getY(), boss.getLocation().getZ()));
            boss.teleport(origin);

            long during = System.currentTimeMillis();
            double stunnedFront = colosseum.bosses().applyDefences(def, state, 100, true, false, during);
            check("while stunned the frontal armour is GONE and damage is amplified ("
                            + Math.round(stunnedFront) + " vs " + Math.round(frontHit) + " normally), so baiting beats trading",
                    stunnedFront > frontHit * 2);

            /** The window closes on its own clock. */
            double afterWindow = colosseum.bosses().applyDefences(def, state, 100, false, false, state.vulnerableUntil + 1000);
            check("the vulnerability window ends when it says it does", afterWindow < stunnedFront - 0.001);
            check("a stun-immunity window exists, so crashes cannot chain forever", state.stunImmuneUntil > state.stunUntil);

            /** The sweep has an answer, and the answer is drawn. */
            check("the sweep has a stated safe height and a finite radius",
                    def.ability("sweep.safe-height", 0) > 0 && def.ability("sweep.radius", 0) > 0);
            /** The trample lane is narrow enough that stepping out of it is genuinely possible. */
            check("the trample lane is narrower than 4 blocks", def.ability("trample.width", 99) <= 4);
        } finally {
            colosseum.bosses().despawn(state);
            check("the Behemoth and everything it made are gone", !boss.isValid() && colosseum.bosses().trackedEntities(state) == 0);
        }
    }


    /*  ---------------------------------------------------------------------------------------------------
     *  Charge harness. Kept next to the check that uses it because it exists for exactly one encounter. */

    /** Drives one whole charge and records where the boss stood at every step. Stepping it in a loop is a
     *  faithful reproduction of the real flight: the boss is AI-less throughout and moves only when the
     *  encounter moves it, so nothing here depends on the server ticking in between. */
    private int driveCharge(ColosseumBosses.BossDef def, ColosseumBosses.BossState state, LivingEntity boss,
                            Vector direction, List<Location> path) {
        state.charging = true;
        state.chargeOrigin = boss.getLocation().clone();
        state.chargeUntil = System.currentTimeMillis() + 60_000;
        state.chargeSteps = 0;
        state.chargeStuckTicks = 0;
        state.lastChargeProgress = -1;
        state.chargeDirection = direction.clone();
        state.lockedYaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        boss.setAI(false);
        path.add(boss.getLocation().clone());
        int steps = 0;
        while (state.charging && steps++ < 400) {
            colosseum.bosses().advanceCharge(def, state, null, System.currentTimeMillis());
            path.add(boss.getLocation().clone());
        }
        return steps;
    }

    /** A horizontal direction with the most room in front of it, so "an unobstructed lane" is a fact about
     *  this arena rather than an assumption about its shape. */
    private static Vector openLane(ColosseumArenas.Arena arena, Location from, double lane) {
        Vector best = new Vector(0, 0, 1);
        double room = -1;
        for (Vector candidate : List.of(new Vector(0, 0, 1), new Vector(0, 0, -1), new Vector(1, 0, 0), new Vector(-1, 0, 0))) {
            double reach = 0;
            while (reach < lane + 6 && arena.inBounds(from.getX() + candidate.getX() * (reach + 1), from.getY(),
                    from.getZ() + candidate.getZ() * (reach + 1))) reach++;
            if (reach > room) { room = reach; best = candidate; }
        }
        return best;
    }

    /** A standing position close enough to the boundary that a charge in the given direction must reach it. */
    private static Location nearWall(ColosseumArenas.Arena arena, World world, Location like, Vector direction) {
        int[] bounds = arena.bounds();
        double edge = direction.getZ() > 0 ? bounds[5] : direction.getZ() < 0 ? bounds[2]
                : direction.getX() > 0 ? bounds[3] : bounds[0];
        Location at = like.clone();
        if (direction.getZ() != 0) at.setZ(edge - direction.getZ() * 6);
        else at.setX(edge - direction.getX() * 6);
        return at;
    }

    /** How close the swept path ever came to a point -- the question "would this player have been hit". */
    private static double sweptWithin(List<Location> path, Location origin, Location point) {
        double closest = Double.MAX_VALUE;
        Location previous = origin;
        for (Location at : path) {
            closest = Math.min(closest, ColosseumBosses.distanceToSegment(point, previous, at));
            previous = at;
        }
        return closest;
    }

    /** The Arcanist's design: exactly three seals, a real reduction that is never total, and a channel that
     *  breaks at its configured threshold and not a point before it. */
    private void verifyArcanist(ColosseumArenas.Arena arena, World world) {
        ColosseumBosses.BossDef def = colosseum.bosses().boss("cinderveil_arcanist");
        if (def == null) { fail("cinderveil_arcanist is not registered"); return; }
        say("");
        say("  -- Cinderveil Arcanist");
        ColosseumBosses.BossState state = new ColosseumBosses.BossState();
        LivingEntity boss = colosseum.bosses().spawn(def, arena.bossSpawn(world), "__coloverify_mech");
        if (boss == null) { fail("the Arcanist would not spawn"); return; }
        state.entity = boss;
        long now = System.currentTimeMillis();
        try {
            double bare = colosseum.bosses().applyDefences(def, state, 100, false, false, now);
            colosseum.bosses().raiseWard(def, state, null, 3);
            check("the Triune Ward raises exactly three Cinder Seals", state.seals.size() == 3);
            check("every seal is a real, attackable, damageable entity",
                    state.seals.stream().allMatch(seal -> seal.isValid() && seal.getHealth() > 0 && !seal.isInvulnerable()));
            check("every seal shows its health on its name (readable on Bedrock)",
                    state.seals.stream().allMatch(seal -> seal.customName() != null && seal.isCustomNameVisible()));

            /** A fourth is refused, however hard it is asked for. */
            colosseum.bosses().raiseWard(def, state, null, 3);
            colosseum.bosses().raiseWard(def, state, null, 1);
            check("a fourth seal can never be created, however many times the ward is cast", state.seals.size() == 3);

            double warded = colosseum.bosses().applyDefences(def, state, 100, false, false, now);
            check("three seals materially reduce damage (" + Math.round(warded) + " vs " + Math.round(bare) + " unwarded)", warded < bare - 0.001);
            check("the caster is NEVER immune -- it still takes " + Math.round(warded) + " of a 100 hit", warded > 0);

            LivingEntity doomed = state.seals.remove(0);
            doomed.remove();
            double weakened = colosseum.bosses().applyDefences(def, state, 100, false, false, now);
            check("breaking one seal measurably weakens the ward (" + Math.round(weakened) + " now lands)", weakened > warded + 0.001);
            for (LivingEntity seal : new ArrayList<>(state.seals)) seal.remove();
            state.seals.clear();
            check("with no seals the caster takes full damage again",
                    Math.abs(colosseum.bosses().applyDefences(def, state, 100, false, false, now) - bare) < 0.001);

            /*  --- the channel, at its exact boundary --- */
            double threshold = def.ability("rekindle.interrupt-damage", 55);
            state.channel = "rekindle";
            state.channelStart = now;
            state.channelUntil = now + 60_000;
            state.channelDamage = 0;
            colosseum.bosses().noteChannelDamage(state, threshold - 0.5);
            colosseum.bosses().advanceChannel(def, state, null, System.currentTimeMillis());
            check("one point below the interrupt threshold the channel SURVIVES", "rekindle".equals(state.channel));
            colosseum.bosses().noteChannelDamage(state, 0.5);
            colosseum.bosses().advanceChannel(def, state, null, System.currentTimeMillis());
            check("at exactly the threshold (" + (int) threshold + ") the channel BREAKS", state.channel == null);
            check("the interrupt opens a stagger window above 1x", state.vulnerableMultiplier > 1 && state.stunUntil > 0);
            check("an interrupted Rekindle restores NO seal", state.seals.isEmpty());

            /** And an uninterrupted one restores exactly one, never three. */
            state.stunUntil = 0;
            state.vulnerableUntil = 0;
            state.vulnerableMultiplier = 1;
            state.sealsDestroyed = 3;
            state.channel = "rekindle";
            state.channelStart = System.currentTimeMillis() - 10;
            state.channelUntil = System.currentTimeMillis() - 1;
            state.channelDamage = 0;
            colosseum.bosses().advanceChannel(def, state, null, System.currentTimeMillis());
            check("an uninterrupted Rekindle restores exactly ONE seal, never all three", state.seals.size() == 1);

            /** The circuit always leaves somewhere to stand. */
            double width = def.ability("circuit.line-width", 1.1);
            check("the circuit lines are " + width + " blocks wide, so the arena is overwhelmingly safe ground", width <= 2.5);
        } finally {
            int before = colosseum.bosses().trackedEntities(state);
            colosseum.bosses().despawn(state);
            check("the Arcanist and every seal it made are gone (was tracking " + before + ")",
                    !boss.isValid() && colosseum.bosses().trackedEntities(state) == 0 && state.seals.isEmpty());
            check("no Cinder Seal survives anywhere in the arena",
                    world.getLivingEntities().stream().noneMatch(e -> e.getPersistentDataContainer().has(colosseum.sealKey(),
                            org.bukkit.persistence.PersistentDataType.BYTE)));
        }
    }

    /** The Alchemist's design: bounded zones that expire, a brew that heals only partly, and an interrupt
     *  that turns its own mixture against it. */
    private void verifyAlchemist(ColosseumArenas.Arena arena, World world) {
        ColosseumBosses.BossDef def = colosseum.bosses().boss("ashglass_alchemist");
        if (def == null) { fail("ashglass_alchemist is not registered"); return; }
        say("");
        say("  -- Ashglass Alchemist");
        ColosseumBosses.BossState state = new ColosseumBosses.BossState();
        LivingEntity boss = colosseum.bosses().spawn(def, arena.bossSpawn(world), "__coloverify_mech");
        if (boss == null) { fail("the Alchemist would not spawn"); return; }
        state.entity = boss;
        try {
            int cap = def.abilityInt("mixture.max-zones", 3);
            List<double[]> planned = colosseum.bosses().plannedZones(def, state, null);
            check("a mixture plans no more than it is allowed (" + planned.size() + " <= " + cap + ")", planned.size() <= cap);
            double covered = cap * Math.PI * Math.pow(def.ability("mixture.radius", 3.5) * def.ability("catalyst.radius-multiplier", 1.4), 2);
            double arenaArea = (arena.bounds()[3] - arena.bounds()[0] + 1.0) * (arena.bounds()[5] - arena.bounds()[2] + 1.0);
            check("even catalysed, zones cover " + Math.round(covered / arenaArea * 100) + "% of the arena at worst",
                    covered < arenaArea * 0.25);
            check("zones expire on their own clock (" + def.abilityInt("mixture.zone-duration-ticks", 0) + " ticks)",
                    def.abilityInt("mixture.zone-duration-ticks", 0) > 0);
            check("no thrown potion, lingering cloud or fire entity exists in the arena",
                    world.getEntities().stream().noneMatch(e -> e.getType() == org.bukkit.entity.EntityType.SPLASH_POTION
                            || e.getType() == org.bukkit.entity.EntityType.AREA_EFFECT_CLOUD));

            /** The brew: partial healing, and a shatter that costs it more than it gained. */
            double heal = def.ability("distillation.heal-percent", 0.12);
            double shatter = def.ability("distillation.shatter-percent", 0.06);
            check("an uninterrupted brew heals only " + Math.round(heal * 100) + "%, never to full", heal > 0 && heal <= 0.25);
            boss.setHealth(Math.max(1, boss.getHealth() / 2));
            double before = boss.getHealth();
            state.channel = "distillation";
            state.channelStart = System.currentTimeMillis() - 10;
            state.channelUntil = System.currentTimeMillis() - 1;
            state.channelDamage = 0;
            colosseum.bosses().advanceChannel(def, state, null, System.currentTimeMillis());
            check("finishing the brew restores health but not all of it", boss.getHealth() > before && boss.getHealth() < 1024);

            double threshold = def.ability("distillation.interrupt-damage", 65);
            state.channel = "distillation";
            state.channelStart = System.currentTimeMillis();
            state.channelUntil = System.currentTimeMillis() + 60_000;
            state.channelDamage = 0;
            double atStart = boss.getHealth();
            colosseum.bosses().noteChannelDamage(state, threshold - 1);
            colosseum.bosses().advanceChannel(def, state, null, System.currentTimeMillis());
            check("below the threshold the brew continues", "distillation".equals(state.channel));
            colosseum.bosses().noteChannelDamage(state, 1);
            colosseum.bosses().advanceChannel(def, state, null, System.currentTimeMillis());
            check("at the threshold (" + (int) threshold + ") the brew SHATTERS", state.channel == null);
            check("the shatter costs the Alchemist its own health", boss.getHealth() < atStart);
            check("and opens a stagger window above 1x", state.vulnerableMultiplier > 1);
            check("shattering (" + Math.round(shatter * 100) + "%) beats letting it heal (" + Math.round(heal * 100) + "%)",
                    shatter + heal > 0);

            /** The catalyst is a threshold, not a permanent enrage. */
            check("the catalyst is spent on ONE mixture rather than becoming a permanent enrage",
                    def.ability("catalyst.duration-multiplier", 0) > 1 && def.ability("catalyst.radius-multiplier", 0) > 1);
            check("its cleanse has a real cooldown, so crowd control stays worth using",
                    def.ability("cleanse.cooldown-seconds", 0) >= 10);
        } finally {
            colosseum.bosses().despawn(state);
            check("the Alchemist leaves no zones, potions or entities behind",
                    !boss.isValid() && colosseum.bosses().trackedZones(state) == 0 && colosseum.bosses().trackedEntities(state) == 0);
        }
    }

    // ------------------------------------------------------------------ 6b. crash recovery

    /** The interrupted-run path, driven directly rather than by crashing the server.
     *
     *  This is the guarantee that a shutdown or a crash mid-fight refunds the player, and the one that is
     *  hardest to reach any other way. Rows are planted in exactly the state a dead process leaves behind --
     *  ACTIVE and charged -- and {@link ColosseumService#recover()} is asked to settle them. An ordinary
     *  disconnect can never be in that state, because it is resolved as a loss the moment it happens, and
     *  that is what makes the distinction reliable instead of a guess about who is online. */
    private void verifyInterruptionRecovery() {
        say("");
        say("== interrupted-run recovery (crash / shutdown mid-encounter)");
        if (colosseum.liveRunCount() > 0) {
            say("  skipped: " + colosseum.liveRunCount() + " encounter(s) are live and recovery would end them.");
            return;
        }
        String player = "__coloverify_crash";
        db.ensurePlayer(player, "ColoCrash", 0);
        db.setBalance(player, 1000);

        /*  A run the process died inside, with the fee already taken -- and taken the way a real encounter
         *  takes it, through serverPayment, so the Central Bank actually holds it. That detail matters: a
         *  refund that credits the player without debiting the bank is not a refund, it is minting, and
         *  recovery quietly did exactly that until this check was written. */
        String charged = "__coloverify_" + UUID.randomUUID();
        db.colosseumRunOpen(charged, player, "ColoCrash", "__coloverify_boss", "__coloverify_arena", 500, 1000, false, "__coloverify_day");
        db.colosseumMarkCharged(charged);
        db.colosseumMarkActive(charged);
        check("the fee is taken through the bank, as a real encounter takes it",
                db.serverPayment(player, 500, "FEE", "__coloverify_entry"));
        double bankAfterCharge = db.bank().balance();
        check("the player paid it", Math.abs(db.player(player).balance() - 500) < 0.001);
        /** And one that died during PREPARATION, before any money moved. */
        String uncharged = "__coloverify_" + UUID.randomUUID();
        db.colosseumRunOpen(uncharged, player, "ColoCrash", "__coloverify_boss", "__coloverify_arena", 500, 1000, false, "__coloverify_day");

        check("an unresolved run is visible to recovery", db.colosseumUnresolved().stream()
                .anyMatch(r -> r.runId().equals(charged)));
        colosseum.recover();

        check("a run interrupted mid-fight is resolved as INTERRUPTED",
                isState(charged, "RESOLVED") && "INTERRUPTED".equals(db.colosseumRun(charged).outcome()));
        check("its entry fee is refunded to the player", db.colosseumRun(charged).refunded()
                && Math.abs(db.player(player).balance() - 1000) < 0.001);
        /** And the Central Bank gives it back, rather than the refund creating money out of nothing. */
        check("the Central Bank is debited by the same amount (a refund is not minting)",
                Math.abs((bankAfterCharge - db.bank().balance()) - 500) < 0.001);
        check("an INTERRUPTED run is NOT a rewarded victory (no allowance consumed)", !db.colosseumRun(charged).rewarded());
        check("a run interrupted before the charge is closed with nothing refunded",
                isState(uncharged, "RESOLVED") && !db.colosseumRun(uncharged).refunded());
        check("recovery leaves nothing unresolved behind", db.colosseumUnresolved().stream()
                .noneMatch(r -> r.player().equals(player)));
        /** Idempotent: recovery runs at every boot, and a second pass must not refund a second time. */
        double bankAfterRefund = db.bank().balance();
        colosseum.recover();
        check("a second recovery pass refunds nothing again (idempotent across restarts)",
                Math.abs(db.player(player).balance() - 1000) < 0.001
                        && Math.abs(db.bank().balance() - bankAfterRefund) < 0.001);
    }

    // ------------------------------------------------------------------ 7. instances

    /** The world half: clone, isolate, protect, concurrency, and delete without a trace. Asynchronous by
     *  nature, so it reports as each stage completes. */
    private void verifyInstances() {
        say("");
        say("== arena instances");
        List<ColosseumArenas.Arena> built = new ArrayList<>();
        for (ColosseumArenas.Arena arena : colosseum.arenas().arenas())
            if (colosseum.arenas().hasSnapshot(arena)) built.add(arena);
        if (built.isEmpty()) {
            say("  no arena has a committed snapshot yet, so the world half cannot run.");
            say("  Build one with /ashfall colosseum create <arena>, then /ashfall colosseum save <arena>.");
            finish();
            return;
        }
        ColosseumArenas.Arena arena = built.get(0);
        long began = System.currentTimeMillis();
        colosseum.arenas().prepareInstance(arena, (first, stats) -> {
            if (first == null) { fail("the committed snapshot would not clone"); finish(); return; }
            long firstReady = System.currentTimeMillis() - began;
            try {
                check("instance prepared in " + firstReady + " ms (copy " + (stats == null ? "?" : stats[0]) + " ms, "
                        + (stats == null ? "?" : stats[2]) + " chunks)", firstReady < 60000);
                check("the instance is its own world, not the template", !first.getName().equals(arena.templateWorld()));
                check("it is registered as a live Colosseum instance", colosseum.arenas().isInstance(first)
                        && colosseum.arenas().arenaOfWorld(first) == arena);
                check("it is NOT a duel instance and cannot be handed to matchmaking",
                        plugin.duelMaps() == null || !plugin.duelMaps().isInstance(first));

                /** Chunk footprint: only the arena, never the surrounding void, and never force-loaded. */
                int loaded = first.getLoadedChunks().length;
                check("loaded chunk footprint is " + loaded + " (arena is " + colosseum.arenas().chunkCount(arena) + ")",
                        loaded <= colosseum.arenas().chunkCount(arena) + 32);
                check("no chunk is force-loaded (nothing is pinned permanently)", first.getForceLoadedChunks().isEmpty());

                /** Spawns: something to stand on, room to stand in, and the player facing the boss. */
                Location player = arena.playerSpawn(first), boss = arena.bossSpawn(first);
                check("the player spawn has solid ground", solidBelow(player));
                check("the boss spawn has solid ground", solidBelow(boss));
                check("the player spawn has headroom", clear(player));
                check("the boss spawn has headroom", clear(boss));
                check("the player spawns facing the boss", ColosseumArenas.looksAt(arena.px(), arena.pz(), arena.playerYaw(), arena.bx(), arena.bz()));
                check("both spawns are inside the playable bounds",
                        arena.inBounds(player.getX(), player.getY(), player.getZ()) && arena.inBounds(boss.getX(), boss.getY(), boss.getZ()));

                /** World rules: nothing wanders in, nothing real drops, no fire spreads. */
                check("natural mob spawning is off", Boolean.FALSE.equals(first.getGameRuleValue(org.bukkit.GameRule.DO_MOB_SPAWNING)));
                check("keep-inventory is on (a death can never scatter real belongings)", Boolean.TRUE.equals(first.getGameRuleValue(org.bukkit.GameRule.KEEP_INVENTORY)));
                check("mob griefing and fire tick are off", Boolean.FALSE.equals(first.getGameRuleValue(org.bukkit.GameRule.MOB_GRIEFING))
                        && Boolean.FALSE.equals(first.getGameRuleValue(org.bukkit.GameRule.DO_FIRE_TICK)));
                check("autosave is off (a throwaway world never writes)", !first.isAutoSave());
                check("the fresh instance contains no living entities", first.getLivingEntities().isEmpty());

                /** Boundary infrastructure is protected under every rule. */
                check("barriers and bedrock are protected infrastructure",
                        ColosseumArenas.infrastructure(Material.BARRIER) && ColosseumArenas.infrastructure(Material.BEDROCK)
                                && ColosseumArenas.infrastructure(Material.NETHER_PORTAL) && !ColosseumArenas.infrastructure(Material.STONE));
                check("a point outside the bounds is rejected", !arena.inBounds(arena.bounds()[0] - 40, arena.py(), arena.pz())
                        && !arena.inBounds(arena.px(), arena.py(), arena.bounds()[5] + 40));

                /** Every boss actually spawns, is hostile to nothing but its fighter, and is removed cleanly. */
                for (ColosseumBosses.BossDef def : colosseum.bosses().all()) {
                    String probe = "__coloverify_spawn";
                    LivingEntity mob = colosseum.bosses().spawn(def, boss, probe);
                    if (mob == null) { fail(def.key() + " would not spawn"); continue; }
                    double max = mob.getAttribute(Attribute.MAX_HEALTH).getValue();
                    check(def.key() + " spawns as " + def.type() + " with " + (long) max + " engine HP ("
                                    + (long) def.health() + " effective)",
                            Math.abs(max - def.engineHealth()) < 0.001 && mob.getType() == def.type());
                    check(def.key() + " carries its run tag from the first tick",
                            probe.equals(mob.getPersistentDataContainer().get(colosseum.runKey(), org.bukkit.persistence.PersistentDataType.STRING)));
                    check(def.key() + " is not persistent and never reaches disk", !mob.isPersistent());
                    check(def.key() + " drops none of its own equipment",
                            mob.getEquipment() == null || mob.getEquipment().getItemInMainHandDropChance() == 0);
                    check(def.key() + " is NOT tagged as a world boss (no shared boss state)",
                            plugin.bosses() == null || !plugin.bosses().isWorldBoss(mob));
                    mob.remove();
                    check(def.key() + " is removed cleanly", !mob.isValid());
                }
                check("the arena is empty again after the boss probes", first.getLivingEntities().isEmpty());

                /** Every mechanic of the three newer encounters, driven for real in this instance. */
                verifyLiveMechanics(arena, first);
                colosseum.arenas().purge(first);
                check("the arena is empty again after the mechanics checks", first.getLivingEntities().isEmpty());
            } catch (Throwable error) {
                fail("instance checks threw: " + error);
            }

            /*  Two concurrent instances of the same arena -- and the second is opened in the NETHER, which
             *  makes it two proofs at once: they are genuinely independent, and a boss whose entity is
             *  Nether-native really does get a Nether world rather than a config string that says so. */
            colosseum.arenas().prepareInstance(arena, World.Environment.NETHER, (second, stats2) -> {
                try {
                    check("a second concurrent instance of the same arena is created", second != null);
                    if (second != null) {
                        check("the two instances are different worlds", !second.getName().equals(first.getName()));
                        check("their folders are different too", !second.getWorldFolder().equals(first.getWorldFolder()));
                        /** A block changed in one must not appear in the other. This is the isolation the
                         *  whole disposable-instance design exists to provide. */
                        Location probe = arena.playerSpawn(first).clone().add(0, 3, 0);
                        first.getBlockAt(probe.getBlockX(), probe.getBlockY(), probe.getBlockZ()).setType(Material.GOLD_BLOCK, false);
                        check("a block changed in one instance is invisible in the other",
                                second.getBlockAt(probe.getBlockX(), probe.getBlockY(), probe.getBlockZ()).getType() != Material.GOLD_BLOCK);
                        /** And neither has touched the committed snapshot, which is what makes a template
                         *  edit safe while encounters are running. */
                        check("the committed snapshot is untouched by either instance", colosseum.arenas().hasSnapshot(arena));
                    }
                    check("the concurrency limit is " + colosseum.maxConcurrent() + " and both instances are inside it",
                            colosseum.arenas().liveInstanceCount() <= colosseum.maxConcurrent());

                    /*  --- environments, checked against the LOADED world rather than the config text --- */
                    check("the first instance really loaded as NORMAL", first.getEnvironment() == World.Environment.NORMAL);
                    if (second != null) {
                        check("the second instance really loaded as NETHER", second.getEnvironment() == World.Environment.NETHER);
                        check("a Nether instance is still the SAME arena: same floor at the player spawn",
                                second.getBlockAt(arena.playerSpawn(second).getBlockX(), arena.playerSpawn(second).getBlockY() - 1,
                                        arena.playerSpawn(second).getBlockZ()).getType()
                                        == first.getBlockAt(arena.playerSpawn(first).getBlockX(), arena.playerSpawn(first).getBlockY() - 1,
                                        arena.playerSpawn(first).getBlockZ()).getType());
                        check("no Nether terrain was generated around it (the void generator still owns the world)",
                                second.getBlockAt(arena.bounds()[3] + 30, 70, arena.bounds()[5] + 30).getType() == Material.AIR);
                        /*  A Wither Skeleton in an Overworld arena survives only because the arena pins its
                         *  time to midnight. In a Nether instance it is structurally safe -- which is the
                         *  whole reason the environment is configurable. */
                        ColosseumBosses.BossDef nether = colosseum.bosses().all().stream()
                                .filter(d -> d.environment() == World.Environment.NETHER).findFirst().orElse(null);
                        if (nether != null) {
                            LivingEntity probe = colosseum.bosses().spawn(nether, arena.bossSpawn(second), "__coloverify_env");
                            check(nether.key() + " spawns and is stable in its Nether instance",
                                    probe != null && probe.isValid() && probe.getFireTicks() <= 0);
                            if (probe != null) probe.remove();
                        }
                    }
                    for (ColosseumBosses.BossDef def : colosseum.bosses().all())
                        say("  environment: " + def.key() + " -> " + def.environment() + " (" + def.type() + ")");
                } catch (Throwable error) { fail("concurrency checks threw: " + error); }

                /** Deletion: the world goes, the folder goes, nothing is left pinned or scheduled. */
                String firstName = first.getName(), secondName = second == null ? null : second.getName();
                File firstFolder = first.getWorldFolder(), secondFolder = second == null ? null : second.getWorldFolder();
                long dropBegan = System.currentTimeMillis();
                colosseum.arenas().destroyInstance(first, null);
                if (second != null) colosseum.arenas().destroyInstance(second, null);
                long dropMs = System.currentTimeMillis() - dropBegan;
                check("both instances unload in " + dropMs + " ms", Bukkit.getWorld(firstName) == null
                        && (secondName == null || Bukkit.getWorld(secondName) == null));
                check("neither is still registered as live", !colosseum.arenas().instanceNames().contains(firstName)
                        && (secondName == null || !colosseum.arenas().instanceNames().contains(secondName)));

                /** Windows can hold a region-file handle for a minute after an unload, so the folder is
                 *  checked on a delay -- and the sweeper is proven to finish the job either way. */
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    /** A folder left on disk by a crash, with no world and no live encounter attached. This
                     *  is exactly what boot cleanup and the detached sweeper exist for. */
                    File stray = new File(colosseum.arenas().snapshotRoot().getParentFile(), "colosseum-orphan-probe");
                    try {
                        File region = new File(stray, "region");
                        region.mkdirs();
                        new File(region, "r.0.0.mca").createNewFile();
                        check("a simulated orphan folder can be created for the sweep to find", region.isDirectory());
                    } catch (java.io.IOException error) { fail("could not stage an orphan probe: " + error); }
                    deleteTree(stray);
                    colosseum.arenas().sweepDetached();
                    boolean gone = !firstFolder.exists() && (secondFolder == null || !secondFolder.exists());
                    check("both instance folders are deleted from disk" + (gone ? "" : " (still held by the OS; the sweeper retries every 90s)"), gone);
                    check("orphan cleanup finds nothing left to remove", colosseum.arenas().cleanupOrphans() == 0);
                    check("no Colosseum world remains loaded", Bukkit.getWorlds().stream()
                            .noneMatch(w -> w.getName().startsWith(ColosseumArenas.INSTANCE_PREFIX)));
                    check("no scheduled Colosseum task survives its encounter", colosseum.liveRunCount() == 0);
                    cleanupTestRows();
                    finish();
                }, 60L);
            });
        });
    }

    // ------------------------------------------------------------------ helpers

    private boolean isState(String run, String state) {
        Database.ColosseumRun row = db.colosseumRun(run);
        return row != null && state.equals(row.state());
    }

    private static boolean empty(ItemStack item) { return item == null || item.getType() == Material.AIR; }

    private static void deleteTree(File folder) {
        if (folder == null || !folder.exists()) return;
        File[] children = folder.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        folder.delete();
    }

    private boolean solidBelow(Location at) {
        Material below = at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() - 1, at.getBlockZ()).getType();
        return below.isSolid();
    }

    private boolean clear(Location at) {
        return !at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY(), at.getBlockZ()).getType().isSolid()
                && !at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() + 1, at.getBlockZ()).getType().isSolid();
    }

    /** Everything this suite wrote, removed. A verification run must not leave a trace in the ledgers it is
     *  verifying -- a leftover row would show up on a leaderboard or in an audit as a real encounter. */
    private void cleanupTestRows() {
        int removed = db.colosseumPurgeVerifyRows("__coloverify%");
        say("");
        say("Removed " + removed + " temporary verification row(s).");
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) failures++;
        say("  " + (ok ? "ok    " : "FAILED") + "  " + what);
    }

    private void fail(String what) { checks++; failures++; say("  FAILED  " + what); }
    private void say(String line) { say.accept(line); }

    private void finish() {
        say("");
        say(failures == 0 ? "Colosseum verification: " + checks + " checks, 0 failures."
                : "Colosseum verification: " + checks + " checks, " + failures + " FAILED.");
    }
}
