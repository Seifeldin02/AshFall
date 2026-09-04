package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** THE ASHFALL COLOSSEUM -- a paid, solo boss fight in a disposable arena, using the player's real gear.
 *
 *  THE MONEY IS THE HARD PART, not the fight. A player hands over half a million before anything happens,
 *  and every guarantee here exists because getting one of them wrong costs somebody real progress:
 *
 *    * Nothing is charged until the world exists, the state is captured, and the player is verifiably
 *      standing in the arena. The charge is the LAST thing before the encounter commits, so a failure
 *      anywhere in preparation costs nothing at all.
 *    * The fee, the prize, the loot and the refund are each guarded by a compare-and-set in the database,
 *      not by a boolean in memory. A duplicated death event, a reconnect, a second cleanup pass and the
 *      boot recovery can all try to resolve the same run; the first one wins and the rest are no-ops.
 *    * Rewards are granted only AFTER the player's own belongings are back. If restoration fails, nothing
 *      is paid and it is refunded instead -- the failure mode is "the player got their money back", never
 *      "the player got a prize and lost their inventory".
 *
 *  THE BELONGINGS ARE THE OTHER HARD PART. A Colosseum fighter takes a COPY of their equipment in. Whatever
 *  happens to that copy -- durability, eaten gold apples, spent rockets, arena drops, boss loot -- is
 *  discarded, and the captured original is restored exactly. That is why every route out of the inventory
 *  is closed while inside: an Ender Chest, a shulker, an auction listing or a sell command would turn a copy
 *  into a duplicate. Deaths never leave a grave, never drop real items, and never touch the real spawn point.
 *
 *  A CRASH AND A DISCONNECT ARE DIFFERENT, and are told apart without guessing. An ordinary quit is resolved
 *  as a loss the instant it happens, so a row still ACTIVE at boot can only mean the process itself died
 *  mid-fight: the system interrupted the player, so the fee comes back.
 *
 *  Colosseum bosses are their own encounters. Nothing here creates, reads or clears world-boss state,
 *  participation records, the active-boss restriction, natural spawning or world-boss cleanup. */
final class ColosseumService implements Listener {

    /*  How often an encounter's own ticker runs, in game ticks.
     *
     *  It is a constant because it is not only a scheduling detail: an ability that advances the boss a
     *  fixed distance per step has to know how much of a second a step is worth, and a literal 2 in two
     *  files is a literal 2 that will eventually disagree with itself. */
    static final long TICK_PERIOD = 2L;

    // ------------------------------------------------------------------ run

    /** One encounter. Everything the fight owns hangs off this and dies with it -- the world, the boss, the
     *  boss bar and the single repeating task. There is deliberately no global tick anywhere in this class. */
    private final class Run {
        final String id = UUID.randomUUID().toString();
        final String player, playerName, bossKey, arenaKey;
        final boolean adminTest;
        final double fee, prize;
        final ColosseumBosses.BossState boss = new ColosseumBosses.BossState();
        World instance;
        BossBar bar;
        BukkitTask ticker;
        long committedAt, deadlineAt;
        boolean committed, resolved;
        /** Set while the run is deliberately teleporting the player, so the boundary guard lets it through. */
        boolean moving;

        Run(String player, String playerName, String bossKey, String arenaKey, boolean adminTest, double fee, double prize) {
            this.player = player; this.playerName = playerName; this.bossKey = bossKey; this.arenaKey = arenaKey;
            this.adminTest = adminTest; this.fee = adminTest ? 0 : fee; this.prize = adminTest ? 0 : prize;
        }
        long remainingSeconds() { return Math.max(0, (deadlineAt - System.currentTimeMillis() + 999) / 1000); }
    }

    private final SMPCore plugin;
    private final Database db;
    private final ColosseumArenas arenas;
    private final ColosseumBosses bosses;
    private final NamespacedKey runKey, bossKey, sealKey;
    private final Map<String, Run> byPlayer = new ConcurrentHashMap<>();
    /** Players whose confirmation screen is open or whose instance is being prepared. Purely a double-click
     *  guard -- the authoritative "one run per player" rule is the unresolved row in the database. */
    private final Map<String, Long> preparing = new ConcurrentHashMap<>();
    private YamlConfiguration config;
    private BukkitTask sweeper;

    ColosseumService(SMPCore plugin) {
        this.plugin = plugin;
        this.db = plugin.db();
        this.runKey = new NamespacedKey(plugin, "colosseum_run");
        this.bossKey = new NamespacedKey(plugin, "colosseum_boss");
        this.sealKey = new NamespacedKey(plugin, "colosseum_seal");
        this.arenas = new ColosseumArenas(plugin);
        this.bosses = new ColosseumBosses(plugin);
        reload();
    }

    /** Deferred to after the server is up: orphan cleanup unloads worlds, and recovery pays refunds, neither
     *  of which belongs in a constructor that runs while plugins are still enabling. */
    void start() {
        Bukkit.getScheduler().runTask(plugin, () -> { arenas.cleanupOrphans(); recover(); });
        /** Windows holds region-file handles after an unload, so a folder is often undeletable for a minute
         *  after its fight ended. Retried rather than leaked, exactly as the duel engine does it. */
        sweeper = Bukkit.getScheduler().runTaskTimer(plugin, arenas::sweepDetached, 1800L, 1800L);
    }

    ColosseumArenas arenas() { return arenas; }
    ColosseumBosses bosses() { return bosses; }
    NamespacedKey runKey() { return runKey; }
    NamespacedKey bossKey() { return bossKey; }
    NamespacedKey sealKey() { return sealKey; }
    YamlConfiguration config() { return config; }
    int chunksPerTick() { return config == null ? 24 : Math.max(1, config.getInt("chunks-per-tick", 24)); }
    int maxConcurrent() { return config == null ? 4 : Math.max(1, config.getInt("max-concurrent-instances", 4)); }
    int dailyLimit() { return config == null ? 3 : Math.max(0, config.getInt("economy.daily-rewarded-victories", 3)); }
    int liveRunCount() { return byPlayer.size(); }

    void reload() {
        File file = new File(plugin.getDataFolder(), "colosseum.yml");
        config = YamlConfiguration.loadConfiguration(file);
        arenas.reload(config);
        bosses.reload(config);
    }

    // ------------------------------------------------------------------ eligibility

    /** Why this player cannot start this boss right now, or null if they can. One method, so the GUI, the
     *  command and the confirmation callback are all held to exactly the same rule -- and so a state that
     *  changed while the confirmation screen was open is caught before any money moves. */
    String refusal(Player player, ColosseumBosses.BossDef def, boolean adminTest) {
        if (def == null) return "That Colosseum boss does not exist.";
        if (!def.available() && !adminTest) return def.name() + " is not open for challenges right now.";
        ColosseumArenas.Arena arena = arenas.arena(def.arena());
        if (arena == null) return "That boss has no arena configured.";
        if (!arenas.hasSnapshot(arena)) return "The " + arena.name() + " has not been built yet. Ask an administrator.";
        String id = CoreUtil.id(player);
        if (byPlayer.containsKey(id)) return "You are already in a Colosseum encounter. Use /colosseum leave to give it up.";
        if (db.colosseumOpenRunOf(id) != null) return "You have an unfinished Colosseum encounter. Rejoin or wait a moment for it to settle.";
        if (preparing.containsKey(id)) return "Your arena is already being prepared.";
        if (player.isDead()) return "You cannot enter the Colosseum while dead.";
        if (plugin.isTeleporting(player)) return "You are already teleporting.";
        if (plugin.arena() != null && plugin.arena().inArena(player)) return "Finish your duel first.";
        if (plugin.arena() != null && plugin.arena().inLiveDuel(id)) return "Finish your duel first.";
        if (plugin.voidWorlds() != null && plugin.voidWorlds().inside(player)) return "Leave the event world first (/voidworld exit).";
        if (arenas.isColosseumWorld(player.getWorld())) return "You are already inside a Colosseum world.";
        if (plugin.teleports() != null && plugin.teleports().combatRemaining(player) > 0)
            return "You are in combat. Wait " + plugin.teleports().combatRemaining(player) + "s.";
        if (!adminTest && player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE)
            return "Colosseum encounters are fought in Survival.";
        if (arenas.liveInstanceCount() >= maxConcurrent() || byPlayer.size() >= maxConcurrent())
            return "Every Colosseum arena is in use (" + maxConcurrent() + " at once). Try again shortly.";
        if (!adminTest) {
            int used = db.colosseumRewardedToday(id, CoreUtil.riyadhDay());
            if (dailyLimit() > 0 && used >= dailyLimit())
                return "You have taken all " + dailyLimit() + " rewarded Colosseum victories for today. Resets " + resetsIn() + ".";
            if (def.rewardLimit() > 0 && db.colosseumRewardedTodayFor(id, CoreUtil.riyadhDay(), def.key()) >= def.rewardLimit())
                return "You have taken all " + def.rewardLimit() + " rewarded victories over " + def.name() + " today.";
            Database.PlayerRow row = db.player(id);
            if (row == null || row.balance() < def.entryFee())
                return "The entry fee is " + CoreUtil.money(def.entryFee()) + " and you have "
                        + CoreUtil.money(row == null ? 0 : row.balance()) + ".";
        }
        return null;
    }

    /** How long until the daily rewarded-victory allowance resets, in the same day boundary the rest of the
     *  server uses (Riyadh midnight), so "resets in 4h" means the same thing everywhere. */
    String resetsIn() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Riyadh"));
        java.time.ZonedDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());
        long minutes = java.time.Duration.between(now, midnight).toMinutes();
        return minutes >= 60 ? "in " + (minutes / 60) + "h " + (minutes % 60) + "m" : "in " + Math.max(1, minutes) + "m";
    }

    int rewardedToday(Player player) { return db.colosseumRewardedToday(CoreUtil.id(player), CoreUtil.riyadhDay()); }

    // ------------------------------------------------------------------ entry

    /** The player-facing entry point: check, then put the fee in front of them before anything is prepared. */
    void challenge(Player player, String bossId, boolean adminTest) {
        ColosseumBosses.BossDef def = bosses.boss(bossId);
        String no = refusal(player, def, adminTest);
        if (no != null) { CoreUtil.error(player, no); sound(player, "cancel"); return; }
        if (adminTest) { prepare(player, def, true); return; }
        List<String> details = new ArrayList<>();
        details.add("Boss: " + def.name() + " (" + def.style() + ", " + def.difficulty() + ")");
        details.add("Arena: " + (def.environment() == World.Environment.NETHER ? "Nether" : "Overworld") + " instance");
        details.add("Strength: " + def.strength());
        details.add("Weakness: " + def.weakness());
        details.add("Counterplay: " + def.counterplay());
        details.add("Entry fee: " + CoreUtil.money(def.entryFee()) + " — charged when the arena is ready");
        details.add("Victory prize: " + CoreUtil.money(def.cashPrize()) + " plus its reward table");
        int shards = shardReward(def);
        if (shards > 0) details.add("Shards on a win: " + shards + " (shared daily limit, "
                + (plugin.shards() == null ? 0 : plugin.shards().remainingDailyShards(player)) + " left today)");
        details.add("Net profit on a win: " + CoreUtil.money(def.cashPrize() - def.entryFee()));
        details.add("Time limit: " + timeText(def.timeLimitSeconds()));
        details.add("Rewarded victories left today: " + Math.max(0, dailyLimit() - rewardedToday(player)) + " of " + dailyLimit());
        details.add("Allowance resets " + resetsIn());
        details.add("A LOSS keeps the fee: dying, running out of time,");
        details.add("leaving with /colosseum leave, or disconnecting.");
        details.add("Your equipment is always returned exactly as it is now.");
        plugin.confirmations().request(player, SettingsService.ConfirmationKind.COLOSSEUM, false,
                "Colosseum Entry — " + def.name(), details,
                () -> {
                    /** Re-checked on the way out of the confirmation screen: the balance, the daily cap, the
                     *  concurrency slot and the player's own state can all have changed while it was open. */
                    String late = refusal(player, def, false);
                    if (late != null) { CoreUtil.error(player, late); sound(player, "cancel"); return; }
                    prepare(player, def, false);
                },
                () -> CoreUtil.msg(player, "Colosseum entry cancelled. Nothing was charged."));
    }

    /** Builds the arena. Nothing has been charged at this point and nothing will be until the player is
     *  verifiably standing in it. */
    private void prepare(Player player, ColosseumBosses.BossDef def, boolean adminTest) {
        String id = CoreUtil.id(player);
        /** The double-click guard. putIfAbsent, so two clicks in the same tick cannot both get through. */
        if (preparing.putIfAbsent(id, System.currentTimeMillis()) != null) { CoreUtil.error(player, "Your arena is already being prepared."); return; }
        ColosseumArenas.Arena arena = arenas.arena(def.arena());
        Run run = new Run(id, player.getName(), def.key(), arena.key(), adminTest, def.entryFee(), def.cashPrize());
        db.colosseumRunOpen(run.id, id, player.getName(), def.key(), arena.key(), run.fee, run.prize, adminTest, CoreUtil.riyadhDay());
        CoreUtil.msg(player, "Preparing the " + arena.name() + " ("
                + (def.environment() == World.Environment.NETHER ? "Nether" : "Overworld") + " instance)...");
        sound(player, "confirm");
        /** The environment is the BOSS's, chosen at world creation. A Nether-native entity gets a Nether
         *  world; everything else stays Overworld. Same arena, same blocks, same bounds either way. */
        arenas.prepareInstance(arena, def.environment(), (world, stats) -> {
            preparing.remove(id);
            if (world == null) {
                /** Preparation failed before any money moved, which is the entire point of doing it in this
                 *  order. The run is closed as an abort and is not an attempt, a loss or a statistic. */
                db.colosseumResolve(run.id, "ABORTED", false, 0);
                CoreUtil.error(player, "The Colosseum could not be prepared. You have not been charged.");
                sound(player, "cancel");
                return;
            }
            db.colosseumRunWorld(run.id, world.getName());
            if (!player.isOnline() || player.isDead()) {
                db.colosseumResolve(run.id, "ABORTED", false, 0);
                arenas.destroyInstance(world, null);
                return;
            }
            run.instance = world;
            commit(run, player, def, arena, world);
        });
    }

    /** The committed start, in the only order that is safe: capture, teleport, VERIFY, charge, commit.
     *
     *  Everything before the charge is reversible for free. Everything after it is guarded by a
     *  compare-and-set. There is no window in which the player is poorer without a fight, or in a fight
     *  without having paid. */
    private void commit(Run run, Player player, ColosseumBosses.BossDef def, ColosseumArenas.Arena arena, World world) {
        if (!capture(player)) {
            db.colosseumResolve(run.id, "ABORTED", false, 0);
            arenas.destroyInstance(world, null);
            CoreUtil.error(player, "Your belongings could not be safely recorded, so nothing was started or charged.");
            return;
        }
        Location spawn = arena.playerSpawn(world);
        run.moving = true;
        boolean teleported;
        try { teleported = player.teleport(spawn); }
        catch (RuntimeException error) { plugin.getLogger().warning("[colosseum] teleport threw for " + run.playerName + ": " + error); teleported = false; }
        run.moving = false;
        /** Not "did teleport() return true" but "is the player actually there". A teleport can be cancelled,
         *  redirected or silently fail, and charging for a fight the player is not standing in is exactly
         *  the failure this check exists to make impossible. */
        if (!teleported || !world.equals(player.getWorld())) {
            db.colosseumResolve(run.id, "ABORTED", false, 0);
            restore(player);
            arenas.destroyInstance(world, null);
            CoreUtil.error(player, "The Colosseum could not seat you, so nothing was charged.");
            sound(player, "cancel");
            return;
        }
        if (!run.adminTest) {
            /** Charged exactly once, and only here. The database refuses a second charge for this run
             *  outright; the balance withdrawal itself is atomic and refuses to go negative. */
            if (!db.colosseumChargeEntry(run.id, run.player, run.fee, "COLOSSEUM_ENTRY:" + def.key())) {
                db.colosseumMarkRefunded(run.id);
                db.colosseumResolve(run.id, "ABORTED", false, 0);
                run.moving = true;
                restore(player);
                run.moving = false;
                arenas.destroyInstance(world, null);
                CoreUtil.error(player, "The entry fee could not be taken, so the encounter was cancelled. You have not been charged.");
                return;
            }
            db.recordEconomy(run.player, "COLOSSEUM_ENTRY", -run.fee, def.key());
            db.colosseumStatAttempt(run.player, run.playerName, def.key(), run.fee);
            CoreUtil.msg(player, "Entry fee of " + CoreUtil.money(run.fee) + " paid.");
        } else {
            db.colosseumStatAttempt(run.player, run.playerName, def.key(), 0);
        }
        if (!db.colosseumMarkActive(run.id)) {
            /** Something else already moved this run on. Refund whatever was taken and get out. */
            refundIfCharged(run);
            arenas.destroyInstance(world, null);
            restore(player);
            return;
        }
        run.committed = true;
        run.committedAt = System.currentTimeMillis();
        run.deadlineAt = run.committedAt + def.timeLimitSeconds() * 1000L;
        byPlayer.put(run.player, run);

        /** A clean slate for the fight itself, so nothing from before it decides the outcome. */
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.setFreezeTicks(0);
        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        player.setAllowFlight(false);
        player.setFlying(false);

        LivingEntity boss = bosses.spawn(def, arena.bossSpawn(world), run.id);
        if (boss == null) {
            plugin.getLogger().warning("[colosseum] " + def.key() + " would not spawn; refunding " + run.playerName + ".");
            resolve(run, "INTERRUPTED");
            return;
        }
        run.boss.entity = boss;
        run.bar = Bukkit.createBossBar(def.name() + " — " + def.style(), BarColor.RED, BarStyle.SEGMENTED_10);
        run.bar.addPlayer(player);
        CoreUtil.msg(player, def.name() + " enters the arena. " + timeText(def.timeLimitSeconds()) + " on the clock.");
        for (String line : def.lore()) player.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
        player.showTitle(net.kyori.adventure.title.Title.title(
                Component.text(def.name(), NamedTextColor.RED),
                Component.text(def.style() + " — " + def.difficulty(), NamedTextColor.GRAY)));
        sound(player, "start");
        plugin.getLogger().info("[colosseum] " + run.playerName + " committed to " + def.key() + " in " + world.getName()
                + (run.adminTest ? " (ADMIN TEST, no charge)" : " for " + CoreUtil.money(run.fee)));
        /** ONE task for the whole encounter, cancelled by resolve(). Every ability, the clock, the health
         *  bar and the boundary check ride on it, so there is nothing else that can outlive the fight. */
        run.ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(run, def, arena), TICK_PERIOD, TICK_PERIOD);
    }

    // ------------------------------------------------------------------ the encounter loop

    private void tick(Run run, ColosseumBosses.BossDef def, ColosseumArenas.Arena arena) {
        if (run.resolved) return;
        Player player = plugin.getServer().getPlayer(run.player);
        if (player == null || !player.isOnline()) { resolve(run, "DISCONNECT"); return; }
        LivingEntity boss = run.boss.entity;
        if (boss == null || !boss.isValid() || boss.isDead()) { resolve(run, "VICTORY"); return; }
        if (run.instance == null) { resolve(run, "INTERRUPTED"); return; }
        if (!run.instance.equals(player.getWorld())) {
            /** Out of the arena while the fight is live. Put them back once; if that fails the encounter is
             *  over rather than left running with nobody in it. */
            run.moving = true;
            boolean back = player.teleport(arena.playerSpawn(run.instance));
            run.moving = false;
            if (!back) { resolve(run, "LEAVE"); return; }
        }
        if (System.currentTimeMillis() >= run.deadlineAt) { resolve(run, "TIMEOUT"); return; }

        /** The player cannot leave the playable area under their own steam either. */
        Location at = player.getLocation();
        if (!arena.inBounds(at.getX(), at.getY(), at.getZ())) {
            run.moving = true;
            player.teleport(arena.playerSpawn(run.instance));
            run.moving = false;
            player.sendActionBar(Component.text("The Colosseum will not let you out.", NamedTextColor.RED));
        }

        bosses.tick(def, run.boss, player, System.currentTimeMillis());

        double max = boss.getAttribute(Attribute.MAX_HEALTH) == null ? 20 : boss.getAttribute(Attribute.MAX_HEALTH).getValue();
        if (run.bar != null) {
            run.bar.setProgress(Math.max(0, Math.min(1, boss.getHealth() / Math.max(1, max))));
            long left = run.remainingSeconds();
            /** The state label is how a punish window announces itself on the one piece of UI a player is
             *  always looking at. Bedrock renders a boss bar title identically, which is why it is text. */
            String label = bosses.stateLabel(run.boss);
            run.bar.setTitle(def.name() + "  §7" + (int) Math.ceil(boss.getHealth() / Math.max(1, max) * 100) + "%  §f" + timeText(left)
                    + (label.isEmpty() ? "" : "  §e" + label));
            run.bar.setColor(label.contains("STUNNED") || label.contains("STAGGERED") ? BarColor.GREEN
                    : left <= 30 ? BarColor.YELLOW : BarColor.RED);
        }
    }

    // ------------------------------------------------------------------ resolution

    /** THE single exit. Victory, death, timeout, leave, disconnect, interruption and boot recovery all come
     *  through here, and the database decides whether this call is the one that counts. */
    private void resolve(Run run, String outcome) {
        if (run.resolved) return;
        run.resolved = true;
        byPlayer.remove(run.player, run);
        preparing.remove(run.player);
        if (run.ticker != null) { run.ticker.cancel(); run.ticker = null; }
        bosses.despawn(run.boss);
        if (run.bar != null) { run.bar.removeAll(); run.bar = null; }

        boolean victory = "VICTORY".equals(outcome);
        boolean interrupted = "INTERRUPTED".equals(outcome);
        long duration = run.committedAt > 0 ? System.currentTimeMillis() - run.committedAt : 0;
        /** Only a normally-completed, non-test victory is a rewarded victory, and only a rewarded victory
         *  counts against the daily allowance. */
        boolean rewarded = victory && !run.adminTest;
        if (!db.colosseumResolve(run.id, outcome, rewarded, duration)) {
            /** Somebody else resolved this run already. Still clean the world up, pay nothing. */
            dropWorld(run);
            return;
        }
        ColosseumBosses.BossDef def = bosses.boss(run.bossKey);
        Player player = plugin.getServer().getPlayer(run.player);

        /*  Restoration first, always. The player's own belongings matter more than any prize, and a prize is
         *  never granted into a state where the belongings did not come back.
         *
         *  A DEAD player is forced through respawn before anything else. destroyInstance() moves players out
         *  of the world it is about to delete, and a corpse cannot be teleported -- so without this the
         *  loser of a fight is left lying in a world that stops existing a tick later. Respawning here fires
         *  PlayerRespawnEvent while the capture is still intact, which is what puts them back on the exact
         *  spot they entered from.
         *
         *  A DISCONNECT is different again: the player is on their way out and neither a teleport nor a
         *  gamemode change will survive, but their inventory is about to be written to disk holding the
         *  ARENA COPY of their gear. That copy is overwritten with the real one here so the saved file is
         *  clean, and the capture is deliberately KEPT so the join handler can finish the job properly. */
        boolean restored;
        if ("DISCONNECT".equals(outcome)) restored = sanitiseOnQuit(player);
        else {
            if (player != null && player.isOnline() && player.isDead()) {
                try { player.spigot().respawn(); }
                catch (Throwable error) { plugin.getLogger().warning("[colosseum] forced respawn failed for " + run.playerName + ": " + error); }
            }
            restored = player != null && player.isOnline() && restore(player);
        }
        dropWorld(run);

        if (interrupted) {
            /** The SYSTEM ended this, not the player, so the fee goes back. */
            double back = refundIfCharged(run);
            if (player != null && player.isOnline())
                CoreUtil.msg(player, back > 0
                        ? "Your Colosseum encounter was interrupted by the server. " + CoreUtil.money(back) + " has been refunded."
                        : "Your Colosseum encounter was interrupted by the server. Nothing was charged.");
            return;
        }
        if (victory) {
            if (!restored && player != null && player.isOnline()) {
                /** Belongings did not come back, so nothing is paid: refund and say so loudly. This is the
                 *  fail-closed branch -- an internal error must never leave somebody richer and lighter. */
                double back = refundIfCharged(run);
                CoreUtil.error(player, "You won, but your belongings could not be restored safely, so the fight was voided and "
                        + CoreUtil.money(back) + " refunded. Please tell an administrator.");
                plugin.getLogger().severe("[colosseum] VICTORY VOIDED: could not restore " + run.playerName + " after " + run.bossKey);
                return;
            }
            payVictory(run, def, player, duration);
            return;
        }
        /** Every remaining outcome is a loss: the fee stays spent, the belongings come back. */
        if (def != null && !run.adminTest) db.colosseumStatLoss(run.player, run.playerName, run.bossKey);
        if (player != null && player.isOnline()) {
            String why = switch (outcome) {
                case "DEATH" -> "You were defeated";
                case "TIMEOUT" -> "You ran out of time";
                case "LEAVE" -> "You left the encounter";
                case "DISCONNECT" -> "You disconnected mid-encounter";
                default -> "The encounter ended";
            };
            CoreUtil.error(player, why + ". " + (run.adminTest ? "This was an admin test — nothing was charged."
                    : "The " + CoreUtil.money(run.fee) + " entry fee is lost. Your equipment has been returned."));
            sound(player, "defeat");
        }
    }

    /** The prize, its loot and its experience -- once, after restoration, guarded by the paid flag. */
    private void payVictory(Run run, ColosseumBosses.BossDef def, Player player, long duration) {
        if (!db.colosseumMarkPaid(run.id)) return;
        if (run.adminTest) {
            if (player != null && player.isOnline()) {
                CoreUtil.msg(player, "Admin test cleared in " + timeText(duration / 1000) + ". No fee, no prize, no reward, no leaderboard entry.");
                sound(player, "victory");
            }
            db.colosseumStatVictory(run.player, run.playerName, run.bossKey, 0, duration, false);
            return;
        }
        /** Routed through the bank like every other earned payout, so an overdue loan is garnished from
         *  a Colosseum prize exactly as it would be from any other income. */
        plugin.creditEarned(run.player, run.prize, "COLOSSEUM_PRIZE:" + run.bossKey);
        db.recordEconomy(run.player, "COLOSSEUM_PRIZE", run.prize, run.bossKey);
        db.colosseumStatVictory(run.player, run.playerName, run.bossKey, run.prize, duration, true);
        List<ItemStack> loot = def == null ? List.of() : bosses.rollRewards(run.bossKey);
        int stashed = giveOrStash(run.player, loot);
        int experience = config == null ? 600 : Math.max(0, config.getInt("victory-experience", 600));
        /*  SHARDS. The same service, the same shared daily cap as a world boss.
         *
         *  Inside the paid flag, so it is granted exactly once however many times a resolution is attempted;
         *  after restoration, alongside the cash and the loot; and only ever for a genuine rewarded victory
         *  -- an admin test returns above this point, and every loss, timeout, disconnect, abort and
         *  recovery refund never reaches payVictory at all. */
        int shards = 0, shardsOffered = shardReward(def);
        if (player != null && player.isOnline() && shardsOffered > 0 && plugin.shards() != null)
            shards = plugin.shards().rewardColosseum(player, shardsOffered);
        if (player != null && player.isOnline()) {
            if (experience > 0) player.giveExp(experience);
            CoreUtil.msg(player, "VICTORY over " + (def == null ? run.bossKey : def.name()) + " in " + timeText(duration / 1000) + ".");
            CoreUtil.msg(player, "  " + CoreUtil.money(run.prize) + " awarded (net " + CoreUtil.money(run.prize - run.fee) + " after the entry fee).");
            if (!loot.isEmpty()) CoreUtil.msg(player, "  " + loot.size() + " reward stack(s)" + (stashed > 0 ? " — " + stashed + " sent to your /orders stash (no room)" : "") + ".");
            if (shardsOffered > 0) {
                int left = plugin.shards() == null ? 0 : plugin.shards().remainingDailyShards(player);
                CoreUtil.msg(player, shards > 0
                        ? "  +" + shards + " Shard" + (shards == 1 ? "" : "s") + (shards < shardsOffered
                            ? " (trimmed to your remaining daily allowance)" : "") + " — " + left + " left today."
                        : "  No Shards: you have already reached today's Shard limit (shared with world bosses).");
            }
            int left = Math.max(0, dailyLimit() - rewardedToday(player));
            CoreUtil.msg(player, "  Rewarded victories left today: " + left + " of " + dailyLimit() + (left == 0 ? " — resets " + resetsIn() : "") + ".");
            sound(player, "victory");
        }
        /*  There used to be an `else` here that stashed the whole loot list again for an offline winner --
         *  and giveOrStash() above already stashes every item when the player is offline, because that is
         *  the entire point of it. A player who disconnected before their victory resolved was paid their
         *  loot TWICE. Nothing needs doing on this branch; the loot is already owed exactly once. */
        db.history("SERVER", null, "COLOSSEUM", run.playerName + " defeated " + (def == null ? run.bossKey : def.name())
                + " in the Colosseum (" + timeText(duration / 1000) + ").");
        if (config != null && config.getBoolean("economy.broadcast-victories", true))
            plugin.getServer().broadcast(Component.text("⚔ " + run.playerName + " has defeated " + (def == null ? run.bossKey : def.name())
                    + " in the Colosseum — " + timeText(duration / 1000) + ".", NamedTextColor.GOLD));
        plugin.getLogger().info("[colosseum] " + run.playerName + " defeated " + run.bossKey + " in " + duration + " ms; paid "
                + CoreUtil.money(run.prize) + " and " + loot.size() + " reward stack(s)");
    }

    /** What a victory over this boss offers in Shards, before the shared daily allowance trims it. A
     *  per-boss override falls back to the Colosseum-wide default, so tuning one encounter does not mean
     *  editing six. */
    int shardReward(ColosseumBosses.BossDef def) {
        if (config == null) return 0;
        int fallback = Math.max(0, config.getInt("economy.victory-shards", 3));
        return def == null ? fallback : Math.max(0, config.getInt("bosses." + def.key() + ".victory-shards", fallback));
    }

    /** Refunds the entry fee if, and only if, it was actually taken and has not been given back. */
    private double refundIfCharged(Run run) {
        Database.ColosseumRun row = db.colosseumRun(run.id);
        return row == null ? 0 : refundFee(row, run.bossKey);
    }

    /*  THE refund. One implementation, used by the live path and by boot recovery alike.
     *
     *  These were briefly two, and the second one was wrong: recovery credited the player directly while the
     *  live path went through the bank, so a crash mid-encounter refunded the fighter and left the fee
     *  sitting in the Central Bank as money nobody had paid. A fee taken with serverPayment has to be given
     *  back with refundServerPayment or the books do not balance.
     *
     *  If the bank genuinely cannot cover it -- it has been drained since -- the player is still owed the
     *  money and is credited directly, loudly. A player is never made to pay for the ledger being short. */
    private double refundFee(Database.ColosseumRun row, String detail) {
        if (!row.charged() || row.refunded() || row.fee() <= 0) return 0;
        if (!db.colosseumMarkRefunded(row.runId())) return 0;
        if (!db.refundServerPayment(row.player(), row.fee(), "FEE", "COLOSSEUM_REFUND:" + detail)) {
            db.changeBalance(row.player(), row.fee());
            plugin.getLogger().warning("[colosseum] the Central Bank could not fund a refund; credited "
                    + row.playerName() + " directly with " + CoreUtil.money(row.fee()));
        }
        db.recordEconomy(row.player(), "COLOSSEUM_REFUND", row.fee(), detail);
        plugin.getLogger().info("[colosseum] refunded " + CoreUtil.money(row.fee()) + " to " + row.playerName() + " (" + detail + ")");
        return row.fee();
    }

    private void dropWorld(Run run) {
        World world = run.instance;
        run.instance = null;
        if (world != null) arenas.destroyInstance(world, null);
    }

    /** Rewards into the inventory, with the overflow going to the persistent claim-later stash rather than
     *  onto the floor. A million-dollar prize's loot is not something to drop at somebody's feet. */
    private int giveOrStash(String id, List<ItemStack> items) {
        Player player = plugin.getServer().getPlayer(id);
        int stashed = 0;
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            if (player != null && player.isOnline()) {
                for (ItemStack leftover : player.getInventory().addItem(item).values()) { db.stashAddItem(id, leftover); stashed++; }
            } else { db.stashAddItem(id, item); stashed++; }
        }
        return stashed;
    }

    // ------------------------------------------------------------------ player state

    /** The complete pre-entry state, written to the database before anything about the player changes.
     *  Returns false if it could not be recorded, which is a hard stop -- an encounter never starts on a
     *  player whose belongings are not safely written down somewhere. */
    private boolean capture(Player player) {
        String id = CoreUtil.id(player);
        try {
            if (db.colosseumState(id) != null) return true;
            ItemStack[] contents = player.getInventory().getContents(), armour = player.getInventory().getArmorContents();
            ItemStack offhand = player.getInventory().getItemInOffHand();
            ItemStack cursor = player.getItemOnCursor();
            ItemStack[] all = new ItemStack[contents.length + armour.length + 2];
            System.arraycopy(contents, 0, all, 0, contents.length);
            System.arraycopy(armour, 0, all, contents.length, armour.length);
            all[contents.length + armour.length] = offhand;
            all[contents.length + armour.length + 1] = cursor;
            Location at = player.getLocation();
            db.colosseumStateSave(id, ItemStack.serializeItemsAsBytes(all), at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                    at.getYaw(), at.getPitch(), player.getLevel(), player.getExp(), player.getHealth(), player.getFoodLevel(),
                    player.getGameMode().name(), encodeExtra(player));
            /** The cursor is cleared here and restored from the capture: leaving a stack on the cursor while
             *  teleporting is how an item ends up dropped at the old location. */
            player.setItemOnCursor(null);
            return db.colosseumState(id) != null;
        } catch (Throwable error) {
            plugin.getLogger().severe("[colosseum] could not capture " + id + ": " + error);
            return false;
        }
    }

    /** Puts the player back exactly as they were. Returns false if anything went wrong, which callers treat
     *  as a reason to refund rather than to pay. */
    boolean restore(Player player) {
        String id = CoreUtil.id(player);
        Database.ArenaState state = db.colosseumState(id);
        if (state == null) return true;
        /** A corpse cannot be teleported or given items, and clearing the capture now would leave them with
         *  nothing when they finally click Respawn. Left alone; the respawn handler owns it. */
        if (player.isDead()) return false;
        try {
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            player.setItemOnCursor(null);
            ItemStack[] all = ItemStack.deserializeItemsFromBytes(state.items());
            int size = player.getInventory().getSize();
            ItemStack[] main = new ItemStack[size], armour = new ItemStack[4];
            System.arraycopy(all, 0, main, 0, Math.min(size, all.length));
            if (all.length >= size + 4) System.arraycopy(all, size, armour, 0, 4);
            player.getInventory().setContents(main);
            player.getInventory().setArmorContents(armour);
            if (all.length > size + 4 && all[size + 4] != null) player.getInventory().setItemInOffHand(all[size + 4]);
            else player.getInventory().setItemInOffHand(null);
            if (all.length > size + 5 && all[size + 5] != null) player.setItemOnCursor(all[size + 5]);
            World world = Bukkit.getWorld(state.world());
            if (world != null) player.teleport(new Location(world, state.x(), state.y(), state.z(), state.yaw(), state.pitch()));
            else player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            player.setLevel(state.level());
            player.setExp(state.exp());
            player.setFoodLevel(state.food());
            try { player.setGameMode(GameMode.valueOf(state.gamemode())); }
            catch (IllegalArgumentException ignored) { player.setGameMode(GameMode.SURVIVAL); }
            for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
            applyExtra(player, state.extra());
            player.setHealth(Math.max(0.5, Math.min(state.health(), player.getAttribute(Attribute.MAX_HEALTH).getValue())));
            player.setInvisible(false);
            player.setCollidable(true);
            player.setInvulnerable(false);
            player.setFireTicks(0);
            player.setFreezeTicks(0);
            /** The boss fight is not PvP and must not follow anybody home as a combat tag. */
            if (plugin.teleports() != null) plugin.teleports().clearCombat(player);
            db.colosseumStateClear(id);
            player.updateInventory();
            return true;
        } catch (Throwable error) {
            plugin.getLogger().severe("[colosseum] could not restore " + id + ": " + error);
            return false;
        }
    }

    /** Overwrites a quitting player's live inventory and experience with the captured originals, WITHOUT
     *  clearing the capture.
     *
     *  A player who disconnects mid-encounter has Paper write their .dat immediately afterwards, and at that
     *  moment their inventory holds the arena COPY of their equipment. Left alone, they would rejoin owning a
     *  second set of everything they walked in with -- the duplication this whole design exists to prevent.
     *  So the copy is replaced here, where the write is still ahead of us, and the location, health, effects
     *  and gamemode are left to {@link #restore} on their next join, where a teleport actually works. */
    private boolean sanitiseOnQuit(Player player) {
        if (player == null) return false;
        Database.ArenaState state = db.colosseumState(CoreUtil.id(player));
        if (state == null) return true;
        try {
            ItemStack[] all = ItemStack.deserializeItemsFromBytes(state.items());
            int size = player.getInventory().getSize();
            ItemStack[] main = new ItemStack[size], armour = new ItemStack[4];
            System.arraycopy(all, 0, main, 0, Math.min(size, all.length));
            if (all.length >= size + 4) System.arraycopy(all, size, armour, 0, 4);
            player.getInventory().clear();
            player.getInventory().setContents(main);
            player.getInventory().setArmorContents(armour);
            player.getInventory().setItemInOffHand(all.length > size + 4 ? all[size + 4] : null);
            player.setItemOnCursor(null);
            player.setLevel(state.level());
            player.setExp(state.exp());
            return true;
        } catch (Throwable error) {
            plugin.getLogger().severe("[colosseum] could not sanitise " + player.getName() + "'s inventory on quit: " + error);
            return false;
        }
    }

    /** Everything not already a column: saturation, absorption, flight, speeds, velocity, fall/fire/freeze/air
     *  and the full potion effect list. */
    private String encodeExtra(Player p) {
        StringBuilder sb = new StringBuilder();
        sb.append("sat=").append(p.getSaturation());
        sb.append(";exh=").append(p.getExhaustion());
        sb.append(";abs=").append(p.getAbsorptionAmount());
        sb.append(";allowfly=").append(p.getAllowFlight() ? 1 : 0);
        sb.append(";flying=").append(p.isFlying() ? 1 : 0);
        sb.append(";flyspeed=").append(p.getFlySpeed());
        sb.append(";walkspeed=").append(p.getWalkSpeed());
        sb.append(";fall=").append(p.getFallDistance());
        sb.append(";fire=").append(p.getFireTicks());
        sb.append(";freeze=").append(p.getFreezeTicks());
        sb.append(";air=").append(p.getRemainingAir());
        sb.append(";vx=").append(p.getVelocity().getX()).append(";vy=").append(p.getVelocity().getY()).append(";vz=").append(p.getVelocity().getZ());
        sb.append(";fx=");
        boolean first = true;
        for (PotionEffect e : p.getActivePotionEffects()) {
            if (!first) sb.append("|");
            first = false;
            sb.append(e.getType().getKey().getKey()).append(",").append(e.getAmplifier()).append(",")
              .append(e.getDuration()).append(",").append(e.isAmbient() ? 1 : 0).append(",").append(e.hasParticles() ? 1 : 0);
        }
        return sb.toString();
    }

    private void applyExtra(Player p, String extra) {
        double vx = 0, vy = 0, vz = 0;
        if (extra == null || extra.isBlank()) { p.setFireTicks(0); p.setFallDistance(0); return; }
        for (String part : extra.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq), v = part.substring(eq + 1);
            try {
                switch (k) {
                    case "sat" -> p.setSaturation(Float.parseFloat(v));
                    case "exh" -> p.setExhaustion(Float.parseFloat(v));
                    case "abs" -> p.setAbsorptionAmount(Double.parseDouble(v));
                    case "allowfly" -> p.setAllowFlight(v.equals("1"));
                    case "flying" -> { if (v.equals("1") && p.getAllowFlight()) p.setFlying(true); }
                    case "flyspeed" -> p.setFlySpeed(clampSpeed(Float.parseFloat(v)));
                    case "walkspeed" -> p.setWalkSpeed(clampSpeed(Float.parseFloat(v)));
                    case "fall" -> p.setFallDistance(Float.parseFloat(v));
                    case "fire" -> p.setFireTicks(Integer.parseInt(v));
                    case "freeze" -> p.setFreezeTicks(Integer.parseInt(v));
                    case "air" -> p.setRemainingAir(Integer.parseInt(v));
                    case "vx" -> vx = Double.parseDouble(v);
                    case "vy" -> vy = Double.parseDouble(v);
                    case "vz" -> vz = Double.parseDouble(v);
                    case "fx" -> { if (!v.isEmpty()) for (String fx : v.split("\\|")) {
                        String[] f = fx.split(",");
                        PotionEffectType type = org.bukkit.Registry.EFFECT.get(NamespacedKey.minecraft(f[0]));
                        if (type != null) p.addPotionEffect(new PotionEffect(type, Integer.parseInt(f[2]), Integer.parseInt(f[1]), f[3].equals("1"), f[4].equals("1")));
                    } }
                    default -> { }
                }
            } catch (Exception ignored) { }
        }
        p.setVelocity(new org.bukkit.util.Vector(vx, vy, vz));
    }

    private static float clampSpeed(float value) { return Math.max(-1f, Math.min(1f, value)); }

    // ------------------------------------------------------------------ recovery

    /** Boot recovery. Anything still unresolved is, by construction, a run the process died in the middle
     *  of: an ordinary disconnect is resolved at the moment it happens, so it can never be sitting here.
     *  Those runs are interrupted by the system, so their fees go back and their players are put right --
     *  immediately if they are online, and on their next join if not. */
    void recover() {
        List<Database.ColosseumRun> open = db.colosseumUnresolved();
        for (Database.ColosseumRun row : open) {
            db.colosseumResolve(row.runId(), "INTERRUPTED", false, 0);
            double back = refundFee(row, "interrupted:" + row.boss());
            plugin.getLogger().warning("[colosseum] recovered interrupted run " + row.runId() + " (" + row.playerName()
                    + " vs " + row.boss() + ")" + (back > 0 ? ", refunded " + CoreUtil.money(back) : ", nothing to refund"));
        }
        /** Anyone with belongings still parked is restored now if they happen to be online; the join handler
         *  covers everybody else. */
        for (String id : db.colosseumStateOwners()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isOnline() && restore(player))
                CoreUtil.msg(player, "Your pre-Colosseum belongings were restored after the restart.");
        }
        if (!open.isEmpty()) plugin.getLogger().info("[colosseum] recovery complete: " + open.size() + " interrupted run(s) settled");
    }

    /** Clean shutdown. Every live encounter is interrupted BY THE SYSTEM, so every one is refunded and
     *  restored -- the same treatment a crash gets, arrived at deliberately rather than by recovery. */
    void shutdown() {
        if (sweeper != null) { sweeper.cancel(); sweeper = null; }
        for (Run run : new ArrayList<>(byPlayer.values())) resolve(run, "INTERRUPTED");
        for (World world : new ArrayList<>(Bukkit.getWorlds()))
            if (world.getName().startsWith(ColosseumArenas.INSTANCE_PREFIX)) arenas.destroyInstance(world, null);
    }

    // ------------------------------------------------------------------ listeners

    private Run runOf(Player player) { return player == null ? null : byPlayer.get(CoreUtil.id(player)); }
    private Run runOf(String id) { return byPlayer.get(id); }
    boolean inColosseum(Player player) { return player != null && byPlayer.containsKey(CoreUtil.id(player)); }
    boolean isColosseumWorld(World world) { return arenas.isColosseumWorld(world); }

    private Run runOfEntity(org.bukkit.entity.Entity entity) {
        if (entity == null) return null;
        String id = entity.getPersistentDataContainer().get(runKey, PersistentDataType.STRING);
        if (id == null) return null;
        for (Run run : byPlayer.values()) if (run.id.equals(id)) return run;
        return null;
    }

    /** Damage to and from a Colosseum boss. Everything the fight's numbers depend on happens here. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (!isColosseumWorld(event.getEntity().getWorld())) return;
        Run run = runOfEntity(event.getEntity());
        if (run == null || run.resolved) return;
        ColosseumBosses.BossDef def = bosses.boss(run.bossKey);
        if (def == null) return;
        /** Nothing but the fighter may hurt the boss, and the world itself certainly may not: a boss that
         *  drowned, burned or fell out of the arena would hand somebody a million dollars for free. */
        Player attacker = null;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            if (byEntity.getDamager() instanceof Player direct) attacker = direct;
            else if (byEntity.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) attacker = shooter;
        }
        if (attacker == null || !CoreUtil.id(attacker).equals(run.player)) { event.setCancelled(true); return; }
        /*  A Cinder Seal is not the caster. It takes its own damage, on its own health bar, with no ward
         *  reduction and no divisor -- which is what makes breaking one a visibly faster job than grinding
         *  the Arcanist through its own protection. */
        if (event.getEntity().getPersistentDataContainer().has(sealKey, PersistentDataType.BYTE)) {
            bosses.sealHurt(run.boss, event);
            return;
        }
        bosses.bossHurt(def, run.boss, event, attacker);
        double dealt = event.getFinalDamage();
        Player who = attacker;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (run.resolved) return;
            bosses.bossRiposte(def, run.boss, who, dealt);
            /** Damage landed during a channel is what interrupts it, counted after every multiplier so the
             *  threshold means what the player actually dealt. */
            bosses.noteChannelDamage(run.boss, dealt);
        });
    }

    /** Damage FROM a Colosseum boss's own projectile, normalised to the configured value. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void projectile(EntityDamageByEntityEvent event) {
        if (!isColosseumWorld(event.getEntity().getWorld())) return;
        if (!(event.getDamager() instanceof Projectile projectile)) return;
        String id = projectile.getPersistentDataContainer().get(runKey, PersistentDataType.STRING);
        if (id == null) return;
        for (Run run : byPlayer.values())
            if (run.id.equals(id)) {
                ColosseumBosses.BossDef def = bosses.boss(run.bossKey);
                if (def != null) bosses.projectileHit(def, event);
                return;
            }
    }

    /** The fighter dies. Intercepted before graves, drops, death tax, bounties or anything else that treats
     *  this as a real death -- because it is not one. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void death(PlayerDeathEvent event) {
        Run run = runOf(event.getEntity());
        if (run == null) return;
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        Bukkit.getScheduler().runTask(plugin, () -> resolve(run, "DEATH"));
    }

    /** Respawn puts them exactly where they entered from, never at a bed or the world spawn. The real
     *  respawn point is never read and never written. */
    @EventHandler
    public void respawn(PlayerRespawnEvent event) {
        Database.ArenaState state = db.colosseumState(CoreUtil.id(event.getPlayer()));
        if (state == null) return;
        World world = Bukkit.getWorld(state.world());
        if (world != null) event.setRespawnLocation(new Location(world, state.x(), state.y(), state.z(), state.yaw(), state.pitch()));
        Bukkit.getScheduler().runTask(plugin, () -> { if (event.getPlayer().isOnline()) restore(event.getPlayer()); });
    }

    /** An ordinary disconnect is a LOSS, and is resolved here and now. That is what lets boot recovery treat
     *  everything still unresolved as a genuine interruption instead of guessing. */
    @EventHandler
    public void quit(PlayerQuitEvent event) {
        preparing.remove(CoreUtil.id(event.getPlayer()));
        Run run = runOf(event.getPlayer());
        if (run != null) resolve(run, "DISCONNECT");
    }

    /** Belongings parked by a disconnect, a crash or a death are handed back on the next join. */
    @EventHandler
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (db.colosseumState(CoreUtil.id(player)) == null) return;
        /*  Next tick, not in a second.
         *
         *  Until this runs the player is holding the ARENA COPY of their equipment in the real world, and
         *  every tick of that is a tick in which an item could be dropped, traded or stored -- which would
         *  duplicate it. One tick is short enough that nothing can act in it; the delayed second attempt is
         *  a safety net for the case where another plugin's own join handling moved them first, and costs
         *  nothing because restore() is a no-op once the capture is cleared. */
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && restore(player)) CoreUtil.msg(player, "Your pre-Colosseum belongings have been restored.");
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && db.colosseumState(CoreUtil.id(player)) != null && restore(player))
                CoreUtil.msg(player, "Your pre-Colosseum belongings have been restored.");
        }, 40L);
    }

    /** Nothing leaves a Colosseum world under its own steam -- an ender pearl, a chorus fruit or a plugin
     *  teleport out of the arena would strand somebody or end a paid fight by accident. The run's own
     *  teleports are exempt, as are administrators. */
    @EventHandler(ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Run run = runOf(player);
        if (run == null || run.moving || event.getTo() == null) return;
        if (plugin.isAdmin(player) && event.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND) return;
        ColosseumArenas.Arena arena = arenas.arena(run.arenaKey);
        if (arena == null) return;
        Location to = event.getTo();
        if (!run.instance.equals(to.getWorld()) || !arena.inBounds(to.getX(), to.getY(), to.getZ())) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("You cannot leave a Colosseum encounter that way — /colosseum leave forfeits it.", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void portal(PlayerPortalEvent event) { if (isColosseumWorld(event.getFrom().getWorld())) event.setCancelled(true); }

    /** The arena is not a build site. Nothing is broken, placed, blown up or flooded inside one -- which
     *  also means no fight can ever damage the template it was cloned from, because it cannot damage
     *  anything at all. */
    @EventHandler(ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (!isColosseumWorld(event.getBlock().getWorld())) return;
        if (isEditor(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (!isColosseumWorld(event.getBlock().getWorld())) return;
        if (isEditor(event.getPlayer())) return;
        event.setCancelled(true);
    }

    /** An admin building in a TEMPLATE may edit it; nobody may edit an instance, admin or not, because an
     *  instance is a throwaway copy and editing it is pure confusion. */
    private boolean isEditor(Player player) {
        return player != null && plugin.isAdmin(player) && player.getGameMode() == GameMode.CREATIVE
                && player.getWorld().getName().startsWith(ColosseumArenas.TEMPLATE_PREFIX);
    }

    @EventHandler(ignoreCancelled = true)
    public void entityExplode(EntityExplodeEvent event) { if (isColosseumWorld(event.getLocation().getWorld())) event.blockList().clear(); }

    @EventHandler(ignoreCancelled = true)
    public void blockExplode(BlockExplodeEvent event) { if (isColosseumWorld(event.getBlock().getWorld())) event.blockList().clear(); }

    @EventHandler(ignoreCancelled = true)
    public void bucket(PlayerBucketEmptyEvent event) {
        if (isColosseumWorld(event.getBlock().getWorld()) && !isEditor(event.getPlayer())) event.setCancelled(true);
    }

    /** Belt and braces over DO_MOB_SPAWNING: spawn eggs, trial spawners and imported spawner blocks do not
     *  all honour the gamerule. Only explicitly plugin-spawned entities are let through, so a Colosseum
     *  arena contains exactly the boss that was paid for. */
    /*  A Colosseum boss uses ITS OWN mechanics, never the vanilla ones its body happens to carry.
     *
     *  An Evoker left to itself summons Vexes -- an unbounded mob source inside a bounded encounter -- and
     *  throws fangs nobody telegraphed. A Witch throws splash potions on its own schedule and drinks
     *  healing ones, which would quietly compete with the Distillation this fight is built around. Both are
     *  suppressed here so every effect a player sees comes from a configured, telegraphed ability. */
    @EventHandler(ignoreCancelled = true)
    public void spellCast(org.bukkit.event.entity.EntitySpellCastEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(bossKey, PersistentDataType.STRING)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void bossProjectile(org.bukkit.event.entity.ProjectileLaunchEvent event) {
        if (!isColosseumWorld(event.getEntity().getWorld())) return;
        if (!(event.getEntity().getShooter() instanceof org.bukkit.entity.Entity shooter)) return;
        if (!shooter.getPersistentDataContainer().has(bossKey, PersistentDataType.STRING)) return;
        /** The Revenant's volley is spawned directly and tagged with the run; anything else a boss body
         *  throws by itself is vanilla behaviour and is refused. */
        if (!event.getEntity().getPersistentDataContainer().has(runKey, PersistentDataType.STRING)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void bossSelfPotion(org.bukkit.event.entity.EntityPotionEffectEvent event) {
        if (event.getCause() != org.bukkit.event.entity.EntityPotionEffectEvent.Cause.POTION_DRINK) return;
        if (event.getEntity().getPersistentDataContainer().has(bossKey, PersistentDataType.STRING)) event.setCancelled(true);
    }

    /*  Dimension-specific escapes, closed in a Nether instance exactly as in an Overworld one. Nothing can
     *  be placed in an arena, so the only routes left are igniting an existing block into a portal or
     *  detonating a bed, and both are refused rather than left to chance. */
    @EventHandler(ignoreCancelled = true)
    public void portalCreate(org.bukkit.event.world.PortalCreateEvent event) {
        if (isColosseumWorld(event.getWorld())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void ignite(org.bukkit.event.block.BlockIgniteEvent event) {
        if (isColosseumWorld(event.getBlock().getWorld()) && !isEditor(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void bedOrAnchor(org.bukkit.event.player.PlayerInteractEvent event) {
        if (!inColosseum(event.getPlayer()) || event.getClickedBlock() == null) return;
        Material type = event.getClickedBlock().getType();
        if (type == Material.RESPAWN_ANCHOR || type.name().endsWith("_BED")) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("Not in the Colosseum.", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void spawn(org.bukkit.event.entity.CreatureSpawnEvent event) {
        if (!isColosseumWorld(event.getLocation().getWorld())) return;
        if (event.getSpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        event.setCancelled(true);
    }

    /*  THE DUPLICATION GUARD.
     *
     *  A fighter carries a COPY of their equipment: the original is in the database and comes back at the
     *  end. So any container that outlives the arena is a duplicator -- put the copy of a netherite set into
     *  an Ender Chest, die, and you now own two. The same is true of an auction listing, a sell command, a
     *  faction vault, a trade or an order.
     *
     *  Rather than enumerate every one of those and hope none is ever added, the rule is inverted: while
     *  inside an encounter, the ONLY inventory a player may open is their own. Every plugin GUI, every
     *  container and every workbench is closed off in one line, and a system added tomorrow is covered
     *  without anybody remembering to come back here. */
    @EventHandler(ignoreCancelled = true)
    public void openInventory(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !inColosseum(player)) return;
        org.bukkit.event.inventory.InventoryType type = event.getInventory().getType();
        if (type == org.bukkit.event.inventory.InventoryType.PLAYER || type == org.bukkit.event.inventory.InventoryType.CRAFTING) return;
        event.setCancelled(true);
        player.sendActionBar(Component.text("Storage is sealed inside the Colosseum.", NamedTextColor.RED));
    }

    /** The command allowlist. Deliberately a list of what IS permitted rather than a list of what is not:
     *  the risk here is a movement or item command nobody thought of, and an allowlist fails closed. */
    private static final java.util.Set<String> ALLOWED_COMMANDS = java.util.Set.of(
            "colosseum", "msg", "reply", "r", "w", "tell", "afk", "settings", "help", "smphelp", "discord", "rules", "guide");

    @EventHandler(ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!inColosseum(player)) return;
        String raw = event.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (raw.contains(":")) raw = raw.substring(raw.indexOf(':') + 1);
        if (ALLOWED_COMMANDS.contains(raw)) return;
        /** Administrators keep their tools -- an admin who cannot run a command inside an arena cannot
         *  diagnose one either. Everybody else is held to the list. */
        if (plugin.isAdmin(player)) return;
        event.setCancelled(true);
        CoreUtil.error(player, "That command is sealed inside the Colosseum. /colosseum leave forfeits the encounter.");
    }

    // ------------------------------------------------------------------ player commands

    boolean command(Player player, String[] args) {
        if (args.length == 0) { openMenu(player); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "leave" -> leave(player);
            case "stats" -> stats(player, args.length > 1 ? args[1] : player.getName());
            case "top" -> top(player, args.length > 1 ? args[1] : null);
            case "list" -> listBosses(player);
            default -> {
                ColosseumBosses.BossDef def = bosses.boss(args[0]);
                if (def != null) challenge(player, def.key(), false);
                else { CoreUtil.error(player, "Usage: /colosseum [boss|stats|top|leave|list]"); listBosses(player); }
            }
        }
        return true;
    }

    private void leave(Player player) {
        Run run = runOf(player);
        if (run == null) {
            if (db.colosseumState(CoreUtil.id(player)) != null && restore(player)) { CoreUtil.msg(player, "Your belongings have been restored."); return; }
            CoreUtil.error(player, "You are not in a Colosseum encounter.");
            return;
        }
        /** Leaving is a loss and says so before it happens, once. A second /colosseum leave inside ten
         *  seconds is taken as the confirmation. */
        long now = System.currentTimeMillis();
        Long warned = leaveWarned.get(run.id);
        if (!run.adminTest && (warned == null || now - warned > 10_000)) {
            leaveWarned.put(run.id, now);
            CoreUtil.error(player, "Leaving forfeits the encounter: the " + CoreUtil.money(run.fee) + " entry fee is LOST and no prize is paid.");
            CoreUtil.msg(player, "Run /colosseum leave again within 10 seconds to give it up.");
            return;
        }
        leaveWarned.remove(run.id);
        resolve(run, "LEAVE");
    }

    private final Map<String, Long> leaveWarned = new ConcurrentHashMap<>();

    private void listBosses(Player player) {
        CoreUtil.heading(player, "Colosseum", bosses.all().size() + " bosses");
        for (ColosseumBosses.BossDef def : bosses.all())
            player.sendMessage(Component.text("  " + def.key() + " — " + def.name() + " (" + def.style() + ", " + def.difficulty() + ") "
                    + CoreUtil.money(def.entryFee()) + " in, " + CoreUtil.money(def.cashPrize()) + " out"
                    + (def.available() ? "" : " [closed]"), NamedTextColor.GRAY));
    }

    void stats(Player viewer, String target) {
        String id = CoreUtil.id(target);
        List<Database.ColosseumStats> rows = db.colosseumStatsOf(id);
        CoreUtil.heading(viewer, "Colosseum record", CoreUtil.safe(target));
        if (rows.isEmpty()) { viewer.sendMessage(Component.text("  No Colosseum encounters yet.", NamedTextColor.GRAY)); return; }
        double fees = 0, won = 0;
        int attempts = 0, victories = 0, losses = 0;
        for (Database.ColosseumStats row : rows) {
            ColosseumBosses.BossDef def = bosses.boss(row.boss());
            viewer.sendMessage(Component.text("  " + (def == null ? row.boss() : def.name()) + ": " + row.victories() + "W / " + row.losses() + "L"
                    + " of " + row.attempts() + " · best " + (row.bestMs() > 0 ? timeText(row.bestMs() / 1000) : "—"), NamedTextColor.GRAY));
            fees += row.feesPaid(); won += row.cashWon();
            attempts += row.attempts(); victories += row.victories(); losses += row.losses();
        }
        viewer.sendMessage(Component.text("  Totals: " + victories + "W / " + losses + "L of " + attempts
                + " · paid " + CoreUtil.money(fees) + " · won " + CoreUtil.money(won)
                + " · net " + CoreUtil.money(won - fees), NamedTextColor.GRAY));
        if (viewer.getName().equalsIgnoreCase(target)) {
            int left = Math.max(0, dailyLimit() - rewardedToday(viewer));
            viewer.sendMessage(Component.text("  Rewarded victories left today: " + left + " of " + dailyLimit() + " (resets " + resetsIn() + ")", NamedTextColor.GRAY));
        }
    }

    void top(Player viewer, String bossId) {
        List<ColosseumBosses.BossDef> show = new ArrayList<>();
        if (bossId == null) show.addAll(bosses.all());
        else {
            ColosseumBosses.BossDef def = bosses.boss(bossId);
            if (def == null) { CoreUtil.error(viewer, "Unknown boss. Try: " + String.join(", ", bosses.keys())); return; }
            show.add(def);
        }
        CoreUtil.msg(viewer, "Colosseum fastest clears:");
        for (ColosseumBosses.BossDef def : show) {
            viewer.sendMessage(Component.text("  " + def.name(), NamedTextColor.GOLD));
            List<Database.ColosseumStats> rows = db.colosseumTop(def.key(), 5);
            if (rows.isEmpty()) { viewer.sendMessage(Component.text("    nobody has cleared it yet", NamedTextColor.DARK_GRAY)); continue; }
            int rank = 1;
            for (Database.ColosseumStats row : rows)
                viewer.sendMessage(Component.text("    " + rank++ + ". " + (row.playerName().isBlank() ? row.player() : row.playerName())
                        + " — " + timeText(row.bestMs() / 1000) + " (" + row.victories() + " win" + (row.victories() == 1 ? "" : "s") + ")", NamedTextColor.GRAY));
        }
    }

    // ------------------------------------------------------------------ menu

    private record MenuHolder() implements org.bukkit.inventory.InventoryHolder {
        @Override public org.bukkit.inventory.Inventory getInventory() { return null; }
    }

    /** A plain chest menu, which is the one GUI grammar Geyser renders faithfully on Bedrock. Everything a
     *  player needs to decide is written in the lore rather than in a hover or a click hint. */
    void openMenu(Player player) {
        List<ColosseumBosses.BossDef> list = new ArrayList<>(bosses.all());
        /*  Six entries in two rows of three, centred, with a full clear row above and below.
         *
         *  Deliberately not "as many as fit across nine": Geyser renders a chest menu faithfully but a
         *  Bedrock player is tapping, not hovering, so widely spaced targets and a fixed layout read far
         *  better than a dense grid that reflows every time a boss is added. */
        int rows = 6;
        org.bukkit.inventory.Inventory inv = plugin.getServer().createInventory(new MenuHolder(), rows * 9,
                Component.text("The Ashfall Colosseum", NamedTextColor.DARK_RED));
        int used = rewardedToday(player);
        int shardsLeft = plugin.shards() == null ? 0 : plugin.shards().remainingDailyShards(player);
        int[] slots = {11, 13, 15, 29, 31, 33};
        for (int i = 0; i < list.size() && i < slots.length; i++) {
            ColosseumBosses.BossDef def = list.get(i);
            Database.ColosseumStats row = db.colosseumStats(CoreUtil.id(player), def.key());
            List<String> lore = new ArrayList<>();
            lore.add("§7" + def.style() + " · Difficulty: §f" + def.difficulty()
                    + (def.environment() == World.Environment.NETHER ? " §c· Nether arena" : ""));
            for (String line : def.lore()) lore.add("§8" + line);
            lore.add("");
            /*  The mechanic, in plain words, BEFORE the price.
             *
             *  A player should never have to read a wiki, or die twice, to find out what a boss is doing to
             *  them. Strength says why the obvious approach is slow; weakness says what is actually true;
             *  counterplay says what to do about it. All three are configured per boss and asserted by the
             *  self test, so a boss cannot ship without them. */
            lore.add("§eStrength: §f" + def.strength());
            lore.add("§aWeakness: §f" + def.weakness());
            lore.add("§bCounterplay: §f" + def.counterplay());
            lore.add("");
            lore.add("§7Entry fee: §c" + CoreUtil.money(def.entryFee()));
            lore.add("§7Victory prize: §a" + CoreUtil.money(def.cashPrize()) + " §7(net §a" + CoreUtil.money(def.cashPrize() - def.entryFee()) + "§7)");
            int shards = shardReward(def);
            if (shards > 0) lore.add("§7Shards on a win: §d" + shards + " §7(shared daily limit — §f" + shardsLeft + "§7 left today)");
            lore.add("§7Reward table (§f" + String.format("%.1f", bosses.expectedRewardStacks(def.key())) + " stacks expected§7):");
            for (ColosseumBosses.Roll roll : bosses.rewardTable(def.key()))
                lore.add("§8  " + CoreUtil.pretty(roll.material().name()) + " ×" + (roll.min() == roll.max() ? roll.min() : roll.min() + "-" + roll.max())
                        + "  " + Math.round(roll.chance() * 100) + "%");
            lore.add("§7Time limit: §f" + timeText(def.timeLimitSeconds()));
            lore.add("");
            lore.add("§7Your record: §f" + (row == null ? 0 : row.victories()) + "W §7/ §f" + (row == null ? 0 : row.losses()) + "L"
                    + " §7of §f" + (row == null ? 0 : row.attempts()));
            lore.add("§7Best time: §f" + (row != null && row.bestMs() > 0 ? timeText(row.bestMs() / 1000) : "—"));
            lore.add("§7Rewarded victories left today: §f" + Math.max(0, dailyLimit() - used) + "§7/§f" + dailyLimit()
                    + " §8(shared by all " + list.size() + " bosses)");
            lore.add("§8Resets " + resetsIn());
            lore.add("");
            String no = refusal(player, def, false);
            lore.add(no == null ? "§aClick to challenge — you will be asked to confirm." : "§cUnavailable: " + no);
            inv.setItem(slots[i], icon(def.icon(), (no == null ? "§c" : "§8") + def.name(), lore));
        }
        inv.setItem(49, icon(Material.BOOK, "§6How the Colosseum works", List.of(
                "§7One player, one boss, one disposable arena.",
                "§7You fight with your own current equipment.",
                "§7Your belongings are copied in and restored exactly",
                "§7on every exit — win, lose, time out, leave or crash.",
                "",
                "§7A LOSS keeps the entry fee: dying, running out of",
                "§7time, /colosseum leave, or disconnecting.",
                "§7If the SERVER interrupts you, the fee is refunded.",
                "",
                "§7Every boss lists its strength, weakness and the",
                "§7counterplay. Read them — none of the six is beaten",
                "§7by standing still and swinging.",
                "",
                "§d" + Math.max(0, dailyLimit() - used) + " §7rewarded victories left today, §d" + shardsLeft + " §7Shards",
                "§8The Shard allowance is shared with world bosses.",
                "",
                "§7/colosseum stats · /colosseum top · /colosseum leave")));
        player.openInventory(inv);
        sound(player, "click");
    }

    /** A menu item whose legacy colour codes actually render, with italics off so it reads the same on
     *  Java and through Geyser on Bedrock. */
    private static ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer legacy =
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
        meta.displayName(legacy.deserialize(name).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> legacy.deserialize(line.isEmpty() ? " " : line)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void menuClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof MenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta() || clicked.getItemMeta().displayName() == null) return;
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(clicked.getItemMeta().displayName());
        for (ColosseumBosses.BossDef def : bosses.all())
            if (name.equals(def.name()) || name.endsWith(def.name())) {
                player.closeInventory();
                challenge(player, def.key(), false);
                return;
            }
    }

    // ------------------------------------------------------------------ admin surface

    /** The live picture: what is running, what it cost to prepare, and what it is doing to the server. */
    List<String> instanceReport() {
        List<String> out = new ArrayList<>();
        out.add("Active encounters: " + byPlayer.size() + " / " + maxConcurrent() + "  ·  instance worlds: " + arenas.liveInstanceCount());
        for (Run run : byPlayer.values()) {
            String world = run.instance == null ? "(none)" : run.instance.getName();
            long[] stats = run.instance == null ? null : arenas.prepStats(world);
            int entities = run.instance == null ? 0 : run.instance.getEntities().size();
            int chunks = run.instance == null ? 0 : run.instance.getLoadedChunks().length;
            out.add("  " + run.playerName + " vs " + run.bossKey + (run.adminTest ? " [TEST]" : "")
                    + " · " + timeText(run.remainingSeconds()) + " left"
                    + " · world " + world
                    + " · " + entities + " entities, " + chunks + " chunks"
                    + (stats == null ? "" : " · prepared in " + stats[1] + " ms (copy " + stats[0] + " ms)"));
        }
        for (String name : arenas.instanceNames()) {
            boolean owned = byPlayer.values().stream().anyMatch(r -> r.instance != null && r.instance.getName().equals(name));
            if (!owned) out.add("  " + name + " · DETACHED (no live encounter) — /ashfall colosseum drop " + name);
        }
        return out;
    }

    /** Drops a named instance world. Any encounter still running in it is resolved as an interruption first,
     *  so this can never take an arena out from under a paying player without refunding them. */
    String dropInstance(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null || !name.startsWith(ColosseumArenas.INSTANCE_PREFIX)) return "No such Colosseum instance world.";
        for (Run run : new ArrayList<>(byPlayer.values()))
            if (run.instance != null && run.instance.getName().equals(name)) { resolve(run, "INTERRUPTED"); return "Encounter interrupted and refunded; instance removed."; }
        arenas.destroyInstance(world, null);
        return "Instance " + name + " removed.";
    }

    /** Ends every live encounter as an interruption. Used by /ashfall colosseum reload, because reloading
     *  the boss definitions under a running fight would change its rules mid-flight. */
    int interruptAll(String why) {
        List<Run> live = new ArrayList<>(byPlayer.values());
        for (Run run : live) {
            Player player = plugin.getServer().getPlayer(run.player);
            if (player != null) CoreUtil.msg(player, "Your Colosseum encounter was ended by an administrator (" + why + "). Your fee is refunded.");
            resolve(run, "INTERRUPTED");
        }
        return live.size();
    }

    /*  What concurrent encounters actually cost this server, measured rather than asserted.
     *
     *  Holds `instances` real arena clones open with a real boss alive and telegraphing in each, samples the
     *  server's own tick times through the run, and tears everything down. The one thing it cannot do is
     *  swing a sword -- so the fighter's own damage is absent -- but everything that scales with the number
     *  of simultaneous encounters is here: the world clones, their chunks, the entities, the per-run tasks
     *  and the particle budget.
     *
     *  Reports through {@code say} as it goes, because the whole point is a measurement over time. */
    void bench(int instances, int seconds, java.util.function.Consumer<String> say) {
        ColosseumBosses.BossDef def = bosses.all().stream()
                .max(Comparator.comparingDouble(ColosseumBosses.BossDef::health)).orElse(null);
        if (def == null) { say.accept("No bosses are configured."); return; }
        ColosseumArenas.Arena arena = arenas.arena(def.arena());
        if (arena == null || !arenas.hasSnapshot(arena)) { say.accept("The arena for " + def.key() + " has no committed snapshot."); return; }
        int wanted = Math.max(1, Math.min(maxConcurrent(), instances));
        int hold = Math.max(5, Math.min(120, seconds));

        double[] before = plugin.getServer().getTPS();
        double idleMspt = plugin.getServer().getAverageTickTime();
        say.accept("Benchmarking " + wanted + " concurrent " + def.name() + " encounter(s) for " + hold + "s.");
        say.accept("  baseline: TPS " + String.format("%.2f", before[0]) + " (1m), MSPT " + String.format("%.2f", idleMspt)
                + ", worlds " + plugin.getServer().getWorlds().size()
                + ", worst-case particles/tick per encounter " + bosses.worstCaseParticlesPerTick(def));

        List<World> worlds = new ArrayList<>();
        List<LivingEntity> mobs = new ArrayList<>();
        long[] prepared = new long[]{0, 0};
        java.util.concurrent.atomic.AtomicInteger ready = new java.util.concurrent.atomic.AtomicInteger();
        long began = System.currentTimeMillis();
        for (int i = 0; i < wanted; i++) {
            arenas.prepareInstance(arena, (world, stats) -> {
                if (world != null) {
                    worlds.add(world);
                    if (stats != null) { prepared[0] += stats[1]; prepared[1] = Math.max(prepared[1], stats[1]); }
                    LivingEntity boss = bosses.spawn(def, arena.bossSpawn(world), "__bench_" + world.getName());
                    if (boss != null) mobs.add(boss);
                }
                if (ready.incrementAndGet() < wanted) return;

                say.accept("  prepared " + worlds.size() + "/" + wanted + " instance(s) in " + (System.currentTimeMillis() - began)
                        + " ms wall (mean " + (worlds.isEmpty() ? 0 : prepared[0] / worlds.size()) + " ms, slowest " + prepared[1] + " ms)");
                int chunks = worlds.stream().mapToInt(w -> w.getLoadedChunks().length).sum();
                int entities = worlds.stream().mapToInt(w -> w.getEntities().size()).sum();
                say.accept("  loaded chunks " + chunks + " across " + worlds.size() + " world(s), entities " + entities
                        + ", forced chunks " + worlds.stream().mapToInt(w -> w.getForceLoadedChunks().size()).sum());

                /** One task, standing in for the per-run tickers, driving the heaviest telegraph each boss
                 *  has at the same 2-tick cadence a real encounter uses. */
                double[] worst = {0};
                int[] samples = {0};
                org.bukkit.scheduler.BukkitTask load = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    for (LivingEntity mob : mobs)
                        if (mob.isValid()) bosses.benchTelegraph(def, mob, mob.getLocation().clone().add(6, 0, 0));
                    double mspt = plugin.getServer().getAverageTickTime();
                    worst[0] = Math.max(worst[0], mspt);
                    samples[0]++;
                }, 2L, 2L);

                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    double[] during = plugin.getServer().getTPS();
                    double loadedMspt = plugin.getServer().getAverageTickTime();
                    load.cancel();
                    say.accept("  under load: TPS " + String.format("%.2f", during[0]) + " (1m), MSPT " + String.format("%.2f", loadedMspt)
                            + " (peak " + String.format("%.2f", worst[0]) + " over " + samples[0] + " samples)");
                    say.accept("  delta vs idle: MSPT " + String.format("%+.2f", loadedMspt - idleMspt)
                            + " ms, TPS " + String.format("%+.2f", during[0] - before[0]));
                    long teardown = System.currentTimeMillis();
                    for (LivingEntity mob : mobs) if (mob.isValid()) mob.remove();
                    for (World instance : new ArrayList<>(worlds)) arenas.destroyInstance(instance, null);
                    say.accept("  torn down in " + (System.currentTimeMillis() - teardown) + " ms; live instances now "
                            + arenas.liveInstanceCount() + ", Colosseum worlds loaded "
                            + Bukkit.getWorlds().stream().filter(w -> w.getName().startsWith(ColosseumArenas.INSTANCE_PREFIX)).count());
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        int swept = arenas.sweepDetached();
                        say.accept("  after the sweep: " + swept + " leftover folder(s) removed, TPS "
                                + String.format("%.2f", plugin.getServer().getTPS()[0]) + ", MSPT "
                                + String.format("%.2f", plugin.getServer().getAverageTickTime()));
                    }, 60L);
                }, hold * 20L);
            });
        }
    }

    // ------------------------------------------------------------------ small helpers

    static String timeText(long seconds) {
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + String.format("%02d", seconds % 60) + "s";
    }

    private void sound(Player player, String action) {
        if (player == null || config == null) return;
        Sound fallback = switch (action) {
            case "confirm" -> Sound.BLOCK_NOTE_BLOCK_PLING;
            case "cancel" -> Sound.BLOCK_NOTE_BLOCK_BASS;
            case "start" -> Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN;
            case "victory" -> Sound.UI_TOAST_CHALLENGE_COMPLETE;
            case "defeat" -> Sound.ENTITY_ELDER_GUARDIAN_CURSE;
            default -> Sound.UI_BUTTON_CLICK;
        };
        try {
            Sound sound = Sound.valueOf(config.getString("sounds." + action, fallback.name()).toUpperCase(Locale.ROOT));
            player.playSound(player.getLocation(), sound, 0.7f, action.equals("cancel") ? 0.7f : 1.0f);
        } catch (IllegalArgumentException ignored) { }
    }

    /** Bosses a given sender may be offered by tab completion right now: registered, available, and with a
     *  built arena. An admin testing gets the closed ones too, because that is what testing is for. */
    List<String> completableBosses(boolean admin) {
        List<String> out = new ArrayList<>();
        for (ColosseumBosses.BossDef def : bosses.all()) {
            if (!admin && !def.available()) continue;
            ColosseumArenas.Arena arena = arenas.arena(def.arena());
            if (!admin && (arena == null || !arenas.hasSnapshot(arena))) continue;
            out.add(def.key());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    // ------------------------------------------------------------------ self test

    /** Configuration and arithmetic that can be asserted without a live fight. The end-to-end proof needs a
     *  running server and lives in {@link ColosseumVerify}, behind /ashfall colosseum verify. */
    boolean selfTest() {
        if (config == null || !arenas.selfTest() || !bosses.selfTest()) return false;
        if (maxConcurrent() < 1 || chunksPerTick() < 1) return false;
        /** The daily cap is the money-printing brake; a zero or negative one would remove it entirely. */
        if (dailyLimit() < 1) return false;
        /** The default economy, asserted exactly as specified so a stray config edit is caught here. */
        for (ColosseumBosses.BossDef def : bosses.all()) {
            if (def.cashPrize() - def.entryFee() <= 0) return false;
            /** Uncapped daily profit is the failure this whole limit exists to prevent. */
            if (dailyLimit() * (def.cashPrize() - def.entryFee()) > 10_000_000) return false;
        }
        /** Time formatting, because it is on every screen the player reads. */
        return timeText(45).equals("45s") && timeText(300).equals("5m 00s") && timeText(61).equals("1m 01s");
    }
}
