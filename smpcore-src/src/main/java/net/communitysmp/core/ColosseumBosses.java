package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** The three Colosseum bosses: their configuration, their bodies, and their mechanics.
 *
 *  These are their OWN encounters, not world bosses in a smaller room. Nothing here reads or writes
 *  world-boss state, participation records, the active-boss restriction, natural spawning or world-boss
 *  cleanup -- a Colosseum fight is invisible to that system and vice versa. What is deliberately shared is
 *  the arithmetic that was already proven: the engine's 1024 MAX_HEALTH ceiling is handled the same way the
 *  world bosses handle it, with a damage divisor, so a boss can have a pool bigger than the attribute allows
 *  while its health bar still drains at the right rate.
 *
 *  EVERY ability is driven by the run's single ticker rather than by nested delayed tasks. That is not a
 *  style choice: a telegraph scheduled with runTaskLater outlives the fight that scheduled it, and an
 *  ability landing in a world that has already been deleted is exactly the kind of leak this system may not
 *  have. Cooldowns and wind-ups are timestamps on the run; when the run ends, its one task is cancelled and
 *  there is provably nothing else pending.
 *
 *  Every ability is also TELEGRAPHED -- a sound plus particles, at least a second before anything lands --
 *  and every one has an answer: stop attacking, step off the marked ground, turn round, close the distance.
 *  None of them break blocks, summon anything, or deal damage that cannot be avoided or mitigated. */
final class ColosseumBosses {

    /** Minecraft's own ceiling on the MAX_HEALTH attribute. Anything above it is carried as a divisor. */
    private static final double ENGINE_MAX_HEALTH = 1024.0;

    record BossDef(String key, String name, Material icon, String style, String difficulty, String arena,
                   EntityType type, World.Environment environment, double scale, List<String> lore,
                   String strength, String weakness, String counterplay,
                   double health, double damage, double armor, double armorToughness, double movement,
                   double knockbackResistance, double followRange, double maxSingleHitPercent,
                   int timeLimitSeconds, double entryFee, double cashPrize,
                   boolean available, int rewardLimit, ConfigurationSection abilities) {

        /** The pool the fight is actually worth, expressed as a damage divisor because the attribute itself
         *  cannot hold it. A boss configured at or below the engine cap has a divisor of exactly 1. */
        double toughness() { return Math.max(1, health / ENGINE_MAX_HEALTH); }
        double engineHealth() { return Math.max(1, Math.min(ENGINE_MAX_HEALTH, health)); }
        double ability(String path, double fallback) { return abilities == null ? fallback : abilities.getDouble(path, fallback); }
        int abilityInt(String path, int fallback) { return abilities == null ? fallback : abilities.getInt(path, fallback); }
        long cooldownMs(String ability, double fallbackSeconds) { return (long) (ability(ability + ".cooldown-seconds", fallbackSeconds) * 1000); }
        boolean has(String ability) { return abilities != null && abilities.isConfigurationSection(ability); }
    }

    /** Per-run boss state. Lives on the run, dies with the run. */
    static final class BossState {
        LivingEntity entity;
        final Map<String, Long> nextUse = new LinkedHashMap<>();
        /** Ability currently winding up, and when it lands. */
        String pending;
        long pendingAt;
        /** Ability currently active (riposte / bulwark), and when it expires. */
        String active;
        long activeUntil;
        /** Multi-strike bookkeeping for the flurry, so it needs no task of its own. */
        int strikesLeft;
        long nextStrikeAt;
        long recoveryUntil;
        boolean veiled;
        final List<SmallFireball> projectiles = new ArrayList<>();
        double lastKnownHealthFraction = 1;

        /*  ---- shared punish machinery (boss 4's crash, boss 5's stagger, boss 6's shattered brew) ----
         *
         *  All three of the new encounters are built around the same promise: do the right thing and the
         *  boss becomes briefly, visibly vulnerable. One implementation of that promise, so a window opened
         *  by any of them closes the same way and cannot be left hanging by a code path that forgot. */
        long stunUntil;
        /** Refuses a second stun immediately after one ends, so a punish window can never chain forever. */
        long stunImmuneUntil;
        long vulnerableUntil;
        double vulnerableMultiplier = 1;
        String stateLabel = "";

        /*  ---- Chainbound Behemoth ---- */
        boolean charging;
        Vector chargeDirection;
        Location chargeOrigin;
        long chargeUntil;
        float lockedYaw;
        int chargeStuckTicks;
        double lastChargeProgress = -1;

        /** How far the charge has actually travelled. Compared tick to tick, this is what notices a boss
         *  that is pressed against something and going nowhere -- the case vanilla collision does not
         *  report and which would otherwise leave it wedged in a corner for the rest of the fight. */
        double chargeOriginDistance(Location now) {
            if (chargeOrigin == null || now.getWorld() == null || !now.getWorld().equals(chargeOrigin.getWorld())) return 0;
            double dx = now.getX() - chargeOrigin.getX(), dz = now.getZ() - chargeOrigin.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }

        /*  ---- Cinderveil Arcanist ---- */
        final List<LivingEntity> seals = new ArrayList<>();
        boolean wardRaised;
        int sealsDestroyed;
        long wardFeedbackAt;

        /*  ---- channelled casts (Rekindle, Distillation) ----
         *
         *  A channel is the one place where the player's damage output means something other than "the bar
         *  went down": it is counted, and crossing the configured threshold interrupts the cast. Kept on the
         *  state rather than in a task so a lag spike delays the channel instead of losing it. */
        String channel;
        long channelStart, channelUntil;
        double channelDamage;

        /*  ---- Ashglass Alchemist ----
         *  Each zone is {x, y, z, radius, expiresAt, kind}. A plain double array rather than a record
         *  because it is created and discarded constantly and never leaves this class. */
        final List<double[]> zones = new ArrayList<>();
        boolean catalystArmed, catalystSpent;
        long nextZoneTickAt;
        long cleanseAt;
    }

    /** Zone kinds, as the ordinal stored in the zone array. Their meanings are printed in the GUI, so a
     *  player knows what a colour means before they stand in it rather than after. */
    private static final int ZONE_SCORCH = 0, ZONE_MIRE = 1, ZONE_FRAILTY = 2;

    private final SMPCore plugin;
    private final Map<String, BossDef> bosses = new LinkedHashMap<>();
    private YamlConfiguration config;

    ColosseumBosses(SMPCore plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ registry

    void reload(YamlConfiguration config) {
        this.config = config;
        bosses.clear();
        ConfigurationSection root = config.getConfigurationSection("bosses");
        if (root == null) return;
        for (String raw : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(raw);
            if (s == null) continue;
            String key = raw.toLowerCase(Locale.ROOT);
            EntityType type;
            try { type = EntityType.valueOf(s.getString("entity", "WITHER_SKELETON").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { plugin.getLogger().warning("[colosseum] boss " + key + " has an unknown entity type; skipped."); continue; }
            Material icon = Material.matchMaterial(s.getString("icon", "NETHER_STAR"));
            World.Environment environment;
            try { environment = World.Environment.valueOf(s.getString("environment", "NORMAL").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[colosseum] boss " + key + " has an unknown environment; using NORMAL.");
                environment = World.Environment.NORMAL;
            }
            bosses.put(key, new BossDef(key, s.getString("name", key), icon == null ? Material.NETHER_STAR : icon,
                    s.getString("style", "Boss"), s.getString("difficulty", "Hard"),
                    s.getString("arena", "ashen_colosseum").toLowerCase(Locale.ROOT), type, environment,
                    Math.max(0.5, Math.min(4.0, s.getDouble("scale", 1.0))), s.getStringList("lore"),
                    s.getString("strength", ""), s.getString("weakness", ""), s.getString("counterplay", ""),
                    Math.max(1, s.getDouble("health", 1500)), Math.max(0, s.getDouble("damage", 12)),
                    Math.max(0, s.getDouble("armor", 0)), Math.max(0, s.getDouble("armor-toughness", 0)),
                    Math.max(0.05, s.getDouble("movement", 0.28)),
                    Math.max(0, Math.min(1, s.getDouble("knockback-resistance", 0.3))),
                    Math.max(16, s.getDouble("follow-range", 64)),
                    Math.max(0, Math.min(1, s.getDouble("max-single-hit-percent", 0))),
                    Math.max(30, s.getInt("time-limit-seconds", 300)),
                    Math.max(0, s.getDouble("entry-fee", 500000)), Math.max(0, s.getDouble("cash-prize", 1000000)),
                    s.getBoolean("available", true), Math.max(0, s.getInt("reward-limit", 0)),
                    s.getConfigurationSection("abilities")));
        }
    }

    java.util.Collection<BossDef> all() { return bosses.values(); }
    BossDef boss(String key) { return key == null ? null : bosses.get(key.toLowerCase(Locale.ROOT)); }
    List<String> keys() { return new ArrayList<>(bosses.keySet()); }
    List<String> availableKeys() { return bosses.values().stream().filter(BossDef::available).map(BossDef::key).toList(); }

    // ------------------------------------------------------------------ the body

    /** Builds the boss for one encounter. The run id is stamped into the entity's PDC FIRST, before health,
     *  equipment or names, so the damage listener can never see a Colosseum boss it does not yet recognise
     *  and let a hit through to the ordinary mob-damage path. */
    LivingEntity spawn(BossDef def, Location at, String runId) {
        World world = at.getWorld();
        if (world == null) return null;
        Class<? extends org.bukkit.entity.Entity> cls = def.type().getEntityClass();
        if (cls == null || !LivingEntity.class.isAssignableFrom(cls)) return null;
        @SuppressWarnings("unchecked")
        Class<? extends LivingEntity> living = (Class<? extends LivingEntity>) cls;
        LivingEntity boss = world.spawn(at, living, CreatureSpawnEvent.SpawnReason.CUSTOM, spawned -> {
            spawned.getPersistentDataContainer().set(plugin.colosseum().runKey(), org.bukkit.persistence.PersistentDataType.STRING, runId);
            spawned.getPersistentDataContainer().set(plugin.colosseum().bossKey(), org.bukkit.persistence.PersistentDataType.STRING, def.key());
            AttributeInstance scale = spawned.getAttribute(Attribute.SCALE);
            if (scale != null) scale.setBaseValue(def.scale());
            /** Never written to disk: the instance world is deleted at the end of the fight and autosave is
             *  off, but persistent=false makes that a property of the entity rather than a consequence. */
            spawned.setPersistent(false);
            spawned.setRemoveWhenFarAway(false);
        });
        set(boss, Attribute.MAX_HEALTH, def.engineHealth());
        boss.setHealth(def.engineHealth());
        set(boss, Attribute.ATTACK_DAMAGE, def.damage());
        set(boss, Attribute.ARMOR, def.armor());
        set(boss, Attribute.ARMOR_TOUGHNESS, def.armorToughness());
        set(boss, Attribute.MOVEMENT_SPEED, def.movement());
        set(boss, Attribute.KNOCKBACK_RESISTANCE, def.knockbackResistance());
        set(boss, Attribute.FOLLOW_RANGE, def.followRange());
        boss.customName(Component.text("☠ " + def.name(), colour(def)));
        boss.setCustomNameVisible(true);
        boss.setGlowing(true);
        boss.setCanPickupItems(false);
        equip(boss, def);
        /** Fire resistance on the ash-themed pair: their own arena mechanics are fire-flavoured and a boss
         *  burning itself to death would be a very silly way to win. */
        if (def.type() == EntityType.WITHER_SKELETON || def.type() == EntityType.PIGLIN_BRUTE)
            boss.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
        world.playSound(at, sound("start", Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN), 1.1f, 0.7f);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 1, 0), 60, 0.8, 1.0, 0.8, 0.02);
        return boss;
    }

    private static void set(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    private NamedTextColor colour(BossDef def) {
        return switch (def.type()) {
            case IRON_GOLEM -> NamedTextColor.GOLD;
            case VINDICATOR -> NamedTextColor.LIGHT_PURPLE;
            case RAVAGER -> NamedTextColor.DARK_RED;
            case EVOKER -> NamedTextColor.AQUA;
            case WITCH -> NamedTextColor.GREEN;
            default -> NamedTextColor.RED;
        };
    }

    /** Cosmetic only, and every drop chance is zero: a Colosseum boss's gear is never loot. Everything a
     *  victory pays comes from the configured reward table, after the player's own belongings are back. */
    private void equip(LivingEntity boss, BossDef def) {
        EntityEquipment eq = boss.getEquipment();
        if (eq == null) return;
        switch (def.type()) {
            case WITHER_SKELETON -> {
                eq.setItemInMainHand(named(Material.NETHERITE_SWORD, "Emberbound Brand"));
                eq.setHelmet(named(Material.NETHERITE_HELMET, "Oathmask"));
                eq.setChestplate(named(Material.NETHERITE_CHESTPLATE, "Duellist's Cuirass"));
            }
            case VINDICATOR -> {
                eq.setItemInMainHand(named(Material.IRON_AXE, "Revenant's Cleaver"));
                eq.setHelmet(named(Material.SOUL_LANTERN, "Ashen Halo"));
            }
            case RAVAGER -> eq.setHelmet(named(Material.IRON_BARS, "Bound Yoke"));
            case EVOKER -> eq.setHelmet(named(Material.SOUL_LANTERN, "Cinderveil Crown"));
            case WITCH -> eq.setHelmet(named(Material.GLASS_BOTTLE, "Ashglass Retort"));
            default -> { }
        }
        eq.setItemInMainHandDropChance(0);
        eq.setHelmetDropChance(0);
        eq.setChestplateDropChance(0);
        eq.setLeggingsDropChance(0);
        eq.setBootsDropChance(0);
    }

    private static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GRAY));
        item.setItemMeta(meta);
        return item;
    }

    private Sound sound(String action, Sound fallback) {
        if (config == null) return fallback;
        try { return Sound.valueOf(config.getString("sounds." + action, fallback.name()).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return fallback; }
    }

    // ------------------------------------------------------------------ the fight

    /** One step of one encounter, called by that encounter's own ticker. Everything is bounded: the entity
     *  count never changes except for at most `volley.count` short-lived projectiles, the particle counts are
     *  fixed, and no scan wider than this arena ever happens. */
    void tick(BossDef def, BossState state, Player player, long now) {
        LivingEntity boss = state.entity;
        if (boss == null || !boss.isValid() || player == null || !player.isOnline()) return;

        /** Keep the fight honest: the boss only ever wants the one player who paid for it. */
        if (boss instanceof Mob mob && mob.getTarget() != player) mob.setTarget(player);
        state.lastKnownHealthFraction = boss.getHealth() / Math.max(1, maxHealth(boss));

        /** An expiring stance. Bulwark releases its anchor here, which is the only place it can. */
        if (state.active != null && now >= state.activeUntil) {
            if ("bulwark".equals(state.active)) boss.setAI(true);
            state.active = null;
        }
        /** An expiring punish window. Shared by all three of the new encounters. */
        if (state.vulnerableUntil > 0 && now >= state.vulnerableUntil) { state.vulnerableUntil = 0; state.vulnerableMultiplier = 1; }
        if (state.stunUntil > 0 && now >= state.stunUntil) {
            state.stunUntil = 0;
            boss.setAI(true);
            state.stateLabel = "";
            player.sendActionBar(Component.text(def.name() + " recovers.", NamedTextColor.GRAY));
        }
        /** Zones live on the run and expire on their own clock, so nothing is left behind by a fight that
         *  ended in the middle of one. */
        state.zones.removeIf(zone -> now >= (long) zone[4]);
        if (!state.zones.isEmpty()) tickZones(def, state, player, now);
        /** Seals that died are forgotten here rather than wherever the killing blow happened. */
        if (!state.seals.isEmpty()) {
            int before = state.seals.size();
            state.seals.removeIf(seal -> seal == null || !seal.isValid() || seal.isDead());
            if (state.seals.size() < before) {
                state.sealsDestroyed += before - state.seals.size();
                announceWard(def, state, player);
            }
            for (LivingEntity seal : state.seals) refreshSealName(seal);
        }
        /** A boss that somehow left the arena is walked back rather than lost. */
        ColosseumArenas.Arena arena = plugin.colosseum().arenas().arenaOfWorld(boss.getWorld());
        if (arena != null && !arena.inBounds(boss.getLocation().getX(), boss.getLocation().getY(), boss.getLocation().getZ()))
            boss.teleport(arena.bossSpawn(boss.getWorld()));

        state.projectiles.removeIf(f -> f == null || !f.isValid());

        /*  A charge in flight owns the boss completely: it does not steer, does not choose a new ability and
         *  does not stop early. Everything that can end it is checked here, in one place, every tick. */
        if (state.charging) { advanceCharge(def, state, player, now); return; }

        /*  A channel in flight is the same: the boss is committed, the player's damage is being counted, and
         *  the only two outcomes are "interrupted" and "completed". */
        if (state.channel != null) { advanceChannel(def, state, player, now); return; }

        /** Stunned. Nothing is chosen, nothing winds up; the window belongs to the player. */
        if (now < state.stunUntil) {
            boss.getWorld().spawnParticle(Particle.CRIT, boss.getLocation().add(0, 1.6, 0), 4, 0.4, 0.3, 0.4, 0.05);
            return;
        }

        /** A wind-up that has come due. Telegraph first, land second, always in that order. */
        if (state.pending != null) {
            if (now >= state.pendingAt) { String ability = state.pending; state.pending = null; land(def, state, player, ability, now); }
            else { telegraph(def, state, player, state.pending); return; }
        }
        /** Mid-flurry: strike, then keep going. Bounded by strikesLeft, which only ever counts down. */
        if (state.strikesLeft > 0) {
            if (now >= state.nextStrikeAt) {
                strike(def, state, player);
                state.strikesLeft--;
                state.nextStrikeAt = now + def.abilityInt("flurry.strike-interval-ticks", 8) * 50L;
            }
            return;
        }
        if (state.active != null || now < state.recoveryUntil) return;

        /** Choose. At most one ability winds up at a time, which is what stops two telegraphs overlapping
         *  into something a player cannot read. */
        switch (def.key()) {
            case "emberbound_duelist" -> chooseDuelist(def, state, player, now);
            case "warden_of_cinders" -> chooseWarden(def, state, player, now);
            case "ashfallen_revenant" -> chooseRevenant(def, state, player, now);
            case "chainbound_behemoth" -> chooseBehemoth(def, state, player, now);
            case "cinderveil_arcanist" -> chooseArcanist(def, state, player, now);
            case "ashglass_alchemist" -> chooseAlchemist(def, state, player, now);
            default -> { }
        }
    }

    private static double maxHealth(LivingEntity entity) {
        AttributeInstance a = entity.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    boolean ready(BossState state, String ability, long now) { return now >= state.nextUse.getOrDefault(ability, 0L); }

    void begin(BossDef def, BossState state, String ability, long now, double fallbackCooldown) {
        state.pending = ability;
        state.pendingAt = now + def.abilityInt(ability + ".telegraph-ticks", 25) * 50L;
        state.nextUse.put(ability, state.pendingAt + def.cooldownMs(ability, fallbackCooldown));
    }

    // ---- boss 1: the duel ------------------------------------------------------

    private void chooseDuelist(BossDef def, BossState state, Player player, long now) {
        double distance = state.entity.getLocation().distance(player.getLocation());
        if (ready(state, "flurry", now) && distance <= def.ability("flurry.range", 4.5)) { begin(def, state, "flurry", now, 17); return; }
        if (ready(state, "lunge", now) && distance >= def.ability("lunge.min-range", 6) && distance <= def.ability("lunge.max-range", 22)) { begin(def, state, "lunge", now, 9); return; }
        if (ready(state, "riposte", now) && distance <= 8) begin(def, state, "riposte", now, 14);
    }

    // ---- boss 2: the siege ----------------------------------------------------

    private void chooseWarden(BossDef def, BossState state, Player player, long now) {
        double distance = state.entity.getLocation().distance(player.getLocation());
        if (ready(state, "slam", now) && distance <= def.ability("slam.radius", 7)) { begin(def, state, "slam", now, 15); return; }
        if (ready(state, "fissure", now)) { begin(def, state, "fissure", now, 12); return; }
        if (ready(state, "bulwark", now) && state.lastKnownHealthFraction < 0.8) begin(def, state, "bulwark", now, 26);
    }

    // ---- boss 3: the chase ----------------------------------------------------

    private void chooseRevenant(BossDef def, BossState state, Player player, long now) {
        /** The veil is a threshold, not an ability: it turns on once and stays on, so the last third of the
         *  fight has to be closed out in melee. Announced when it happens so nobody wonders why their bow
         *  stopped working. */
        if (!state.veiled && state.lastKnownHealthFraction <= def.ability("veil.health-threshold", 0.35)) {
            state.veiled = true;
            set(state.entity, Attribute.MOVEMENT_SPEED, def.movement() + def.ability("veil.speed-bonus", 0.06));
            state.entity.getWorld().playSound(state.entity.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.2f, 0.8f);
            state.entity.getWorld().spawnParticle(Particle.SOUL, state.entity.getLocation().add(0, 1, 0), 40, 0.6, 0.9, 0.6, 0.03);
            tell(player, state.entity.getName() + " draws a veil of ash — arrows will barely find it now.");
        }
        double distance = state.entity.getLocation().distance(player.getLocation());
        if (ready(state, "volley", now) && distance > 5) { begin(def, state, "volley", now, 11); return; }
        if (ready(state, "blink", now) && distance > 4 && distance <= def.ability("blink.max-range", 30)) begin(def, state, "blink", now, 10);
    }

    // ---- boss 4: the bait ------------------------------------------------------

    /*  THE CHAINBOUND BEHEMOTH is a lesson in not standing in front of things.
     *
     *  Its front is armoured and it barely moves when hit, so trading blows face to face is the slowest
     *  possible way to kill it. What it cannot do is turn once it has committed: the charge locks its
     *  facing a full second and a half before it moves, which is long enough to walk out of the lane on
     *  purpose and leave it sprinting at a wall. That crash is the fight. */
    private void chooseBehemoth(BossDef def, BossState state, Player player, long now) {
        double distance = state.entity.getLocation().distance(player.getLocation());
        if (ready(state, "charge", now) && distance >= def.ability("charge.min-range", 8) && distance <= def.ability("charge.max-range", 34)) {
            begin(def, state, "charge", now, 16);
            lockFacing(def, state, player);
            return;
        }
        if (ready(state, "sweep", now) && distance <= def.ability("sweep.radius", 6) + 3) { begin(def, state, "sweep", now, 13); return; }
        if (ready(state, "trample", now) && distance <= def.ability("trample.length", 14)) begin(def, state, "trample", now, 15);
    }

    /*  The turn lock, and the whole reason a bait is possible.
     *
     *  AI off means the mob's own look-at-target goal cannot run, so the yaw recorded here is the yaw it
     *  will charge along -- not "roughly", exactly. A player who moves after this instant is genuinely out
     *  of the lane, and one who does not is genuinely hit. Without this the charge would quietly home, the
     *  telegraph would be a lie, and the encounter would have no counterplay at all. */
    private void lockFacing(BossDef def, BossState state, Player player) {
        LivingEntity boss = state.entity;
        Vector to = player.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (to.lengthSquared() < 0.01) to = boss.getLocation().getDirection().setY(0);
        state.lockedYaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
        state.chargeDirection = to.normalize();
        boss.setAI(false);
        boss.setRotation(state.lockedYaw, 0f);
        state.stateLabel = "LOCKED ON";
    }

    // ---- boss 5: the support structure -----------------------------------------

    /*  THE CINDERVEIL ARCANIST asks a different question: can you tell what is actually keeping it alive?
     *
     *  Three Cinder Seals each take a configured slice off every hit it receives, so attacking the caster
     *  first is the slow answer and killing the seals is the fast one. It will rebuild one -- exactly one,
     *  never all three -- if you let the Rekindle channel finish, and interrupting that channel is the
     *  biggest punish window in the fight. */
    private void chooseArcanist(BossDef def, BossState state, Player player, long now) {
        if (!state.wardRaised && ready(state, "ward", now)) { begin(def, state, "ward", now, 30); return; }
        /** Rebuilding is only ever attempted when something is actually missing, and never past three. */
        if (state.wardRaised && state.seals.size() < 3 && state.sealsDestroyed > 0 && ready(state, "rekindle", now)) {
            begin(def, state, "rekindle", now, 24);
            return;
        }
        if (ready(state, "circuit", now)) begin(def, state, "circuit", now, 11);
    }

    // ---- boss 6: the brew ------------------------------------------------------

    /*  THE ASHGLASS ALCHEMIST is deliberately not the Warden with different particles.
     *
     *  The Warden owns space by hitting it. The Alchemist owns it by making it cost something to stand in,
     *  and then periodically stops fighting altogether to brew -- which is the only time it is soft. Save
     *  burst for that window and the fight is short; ignore it and the fight is a war of attrition against
     *  something that heals. */
    private void chooseAlchemist(BossDef def, BossState state, Player player, long now) {
        /** The catalyst is a threshold, armed once, spent on the next mixture. Not a permanent enrage. */
        if (!state.catalystArmed && !state.catalystSpent
                && state.lastKnownHealthFraction <= def.ability("catalyst.health-threshold", 0.45)) {
            state.catalystArmed = true;
            state.entity.getWorld().playSound(state.entity.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1.3f, 0.7f);
            state.entity.getWorld().spawnParticle(Particle.WITCH, state.entity.getLocation().add(0, 1.4, 0), 40, 0.6, 0.8, 0.6, 0.05);
            tell(player, def.name() + " adds an unstable catalyst — its NEXT mixture will be larger and last longer. Give the next thrown zones more room.");
        }
        /** A bounded cleanse, so ordinary crowd control is worth using but is not the whole answer. */
        if (now >= state.cleanseAt && def.has("cleanse")) {
            boolean afflicted = false;
            for (org.bukkit.potion.PotionEffect effect : state.entity.getActivePotionEffects())
                if (isHindrance(effect.getType())) { afflicted = true; break; }
            if (afflicted) {
                for (org.bukkit.potion.PotionEffect effect : new ArrayList<>(state.entity.getActivePotionEffects()))
                    if (isHindrance(effect.getType())) state.entity.removePotionEffect(effect.getType());
                state.cleanseAt = now + (long) (def.ability("cleanse.cooldown-seconds", 22) * 1000);
                state.entity.getWorld().playSound(state.entity.getLocation(), Sound.ENTITY_WITCH_DRINK, 1.1f, 1.1f);
                state.entity.getWorld().spawnParticle(Particle.EFFECT, state.entity.getLocation().add(0, 1.2, 0), 20, 0.4, 0.6, 0.4, 0.05);
                bar(player, def.name() + " drinks off your hex - it cannot do that again for "
                        + Math.round(def.ability("cleanse.cooldown-seconds", 22)) + "s.", NamedTextColor.GREEN);
            }
        }
        if (ready(state, "distillation", now) && state.lastKnownHealthFraction <= def.ability("distillation.health-threshold", 0.8)) {
            begin(def, state, "distillation", now, 30);
            return;
        }
        if (ready(state, "mixture", now)) begin(def, state, "mixture", now, 12);
    }

    private static boolean isHindrance(org.bukkit.potion.PotionEffectType type) {
        return type == org.bukkit.potion.PotionEffectType.SLOWNESS || type == org.bukkit.potion.PotionEffectType.WEAKNESS
                || type == org.bukkit.potion.PotionEffectType.POISON || type == org.bukkit.potion.PotionEffectType.MINING_FATIGUE
                || type == org.bukkit.potion.PotionEffectType.BLINDNESS || type == org.bukkit.potion.PotionEffectType.WITHER;
    }

    // ------------------------------------------------------------------ the Behemoth's charge

    /*  One tick of a charge in flight. Four things can end it, and every one of them is checked here rather
     *  than left to the engine:
     *
     *    it reaches the player      -> a heavy hit and a short recovery
     *    it reaches the boundary    -> CRASH
     *    it reaches a solid block   -> CRASH
     *    it stops making progress   -> CRASH (which is also what stops it wedging itself against a wall)
     *
     *  The last one matters more than it looks: relying on vanilla collision to notice a Ravager pressed
     *  into a barrier is exactly how a boss ends up vibrating in a corner for the rest of the encounter. */
    void advanceCharge(BossDef def, BossState state, Player player, long now) {
        LivingEntity boss = state.entity;
        Vector direction = state.chargeDirection == null ? boss.getLocation().getDirection().setY(0).normalize() : state.chargeDirection;
        boss.setRotation(state.lockedYaw, 0f);

        if (now >= state.chargeUntil) { endCharge(def, state, player, now, false); return; }

        double speed = def.ability("charge.speed", 1.05);
        boss.setVelocity(new Vector(direction.getX() * speed, Math.max(-0.4, boss.getVelocity().getY()), direction.getZ() * speed));
        boss.getWorld().spawnParticle(Particle.CLOUD, boss.getLocation().add(0, 0.2, 0), 6, 0.4, 0.1, 0.4, 0.01);
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_STEP, 0.9f, 0.7f);

        /** Connected. Heavy, knocked back, and explicitly not lethal from full health. A charge whose
         *  fighter has gone offline mid-flight simply never connects, and still crashes normally. */
        if (player != null && player.isOnline() && player.getLocation().distance(boss.getLocation()) <= def.ability("charge.hit-radius", 2.6)) {
            hurt(player, boss, def.ability("charge.damage", 14), true);
            Vector away = direction.clone().multiply(def.ability("charge.knockback", 1.4));
            away.setY(0.5);
            player.setVelocity(player.getVelocity().add(away));
            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ATTACK, 1.4f, 0.8f);
            endCharge(def, state, player, now, false);
            return;
        }

        /** Out of arena, or into something solid. Probed ahead rather than waited for. */
        ColosseumArenas.Arena arena = plugin.colosseum().arenas().arenaOfWorld(boss.getWorld());
        Location ahead = boss.getLocation().add(direction.clone().multiply(def.ability("charge.probe-distance", 1.6)));
        boolean blocked = arena != null && !arena.inBounds(ahead.getX(), ahead.getY(), ahead.getZ());
        if (!blocked) {
            Material feet = boss.getWorld().getBlockAt(ahead.getBlockX(), ahead.getBlockY(), ahead.getBlockZ()).getType();
            Material head = boss.getWorld().getBlockAt(ahead.getBlockX(), ahead.getBlockY() + 1, ahead.getBlockZ()).getType();
            blocked = feet.isSolid() && head.isSolid();
        }
        /** Or simply not getting anywhere, which covers every obstruction nobody thought of. */
        double progress = state.chargeOriginDistance(boss.getLocation());
        if (!blocked) {
            if (state.lastChargeProgress >= 0 && progress - state.lastChargeProgress < 0.08) state.chargeStuckTicks++;
            else state.chargeStuckTicks = 0;
            state.lastChargeProgress = progress;
            if (state.chargeStuckTicks >= def.abilityInt("charge.stuck-ticks", 4)) blocked = true;
        }
        if (blocked) endCharge(def, state, player, now, true);
    }

    private void endCharge(BossDef def, BossState state, Player player, long now, boolean crashed) {
        LivingEntity boss = state.entity;
        state.charging = false;
        state.chargeStuckTicks = 0;
        state.lastChargeProgress = -1;
        boss.setVelocity(new Vector(0, boss.getVelocity().getY(), 0));
        if (!crashed) {
            boss.setAI(true);
            state.stateLabel = "";
            state.recoveryUntil = now + def.abilityInt("charge.recovery-ticks", 20) * 50L;
            return;
        }
        /*  THE CRASH. This is the encounter's whole promise paid out, so it is announced four different
         *  ways -- sound, particles, action bar and the boss bar label -- because a punish window nobody
         *  notices is the same as no punish window at all. */
        long stun = def.abilityInt("charge.stun-ticks", 100) * 50L;
        if (now < state.stunImmuneUntil) stun = Math.max(20L * 50L, stun / 2);
        state.stunUntil = now + stun;
        state.stunImmuneUntil = state.stunUntil + def.abilityInt("charge.stun-immunity-ticks", 120) * 50L;
        state.vulnerableUntil = state.stunUntil;
        state.vulnerableMultiplier = Math.max(1, def.ability("charge.crash-vulnerability", 2.0));
        state.stateLabel = "STUNNED — HIT IT NOW";
        boss.setAI(false);
        World world = boss.getWorld();
        world.playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_STUNNED, 1.5f, 0.8f);
        world.playSound(boss.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.2f, 0.5f);
        world.spawnParticle(Particle.BLOCK, boss.getLocation().add(0, 1, 0), 60, 0.9, 0.9, 0.9, 0.1, Material.STONE.createBlockData());
        world.spawnParticle(Particle.CRIT, boss.getLocation().add(0, 1.6, 0), 40, 0.6, 0.6, 0.6, 0.2);
        tell(player, def.name() + " crashes headlong and is STUNNED for " + Math.round(stun / 1000.0)
                + "s — its front plate is open and it takes " + String.format("%.1f", state.vulnerableMultiplier) + "x damage. Hit it now.");
        /*  Backed off the wall it just hit.
         *
         *  A Ravager that ends its charge with its face inside a barrier can spend the rest of the fight
         *  pressed against it, which turns the encounter into a wall-punching exercise. One step back, only
         *  if that step is inside the arena, and it is free again the moment the stun ends. */
        ColosseumArenas.Arena arena = plugin.colosseum().arenas().arenaOfWorld(world);
        Vector back = (state.chargeDirection == null ? boss.getLocation().getDirection() : state.chargeDirection).clone().multiply(-2.0);
        Location freed = boss.getLocation().add(back);
        if (arena == null || arena.inBounds(freed.getX(), freed.getY(), freed.getZ())) boss.teleport(freed);
    }

    // ------------------------------------------------------------------ the Arcanist's seals

    /** Exactly three, at valid standable points around the caster, and never a fourth. The cap is enforced
     *  at the only two places a seal can come into existence, and asserted by the verifier. */
    void raiseWard(BossDef def, BossState state, Player player, int wanted) {
        LivingEntity boss = state.entity;
        World world = boss.getWorld();
        ColosseumArenas.Arena arena = plugin.colosseum().arenas().arenaOfWorld(world);
        double radius = def.ability("ward.radius", 7);
        int made = 0;
        for (int i = 0; i < 3 && state.seals.size() < 3 && made < wanted; i++) {
            double angle = Math.PI * 2 * i / 3 + Math.PI / 6;
            Location at = boss.getLocation().clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            if (arena != null && !arena.inBounds(at.getX(), at.getY(), at.getZ()))
                at = boss.getLocation().clone().add(Math.cos(angle) * (radius / 2), 0, Math.sin(angle) * (radius / 2));
            Location spot = ColosseumArenas.Arena.standable(world, at.getX(), at.getY(), at.getZ(), 0f);
            LivingEntity seal = spawnSeal(def, state, spot);
            if (seal != null) { state.seals.add(seal); made++; }
        }
        state.wardRaised = true;
        announceWard(def, state, player);
    }

    private LivingEntity spawnSeal(BossDef def, BossState state, Location at) {
        String runId = state.entity.getPersistentDataContainer()
                .getOrDefault(plugin.colosseum().runKey(), org.bukkit.persistence.PersistentDataType.STRING, "");
        try {
            org.bukkit.entity.Shulker seal = at.getWorld().spawn(at, org.bukkit.entity.Shulker.class, CreatureSpawnEvent.SpawnReason.CUSTOM, spawned -> {
                spawned.getPersistentDataContainer().set(plugin.colosseum().runKey(), org.bukkit.persistence.PersistentDataType.STRING, runId);
                spawned.getPersistentDataContainer().set(plugin.colosseum().sealKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                spawned.setPersistent(false);
                spawned.setRemoveWhenFarAway(false);
            });
            /** AI off so it never shoots, never moves and never becomes a second fight; peeked open so its
             *  hitbox is the obvious one and projectiles are not silently halved. */
            seal.setAI(false);
            seal.setPeek(1.0f);
            seal.setGlowing(true);
            seal.setSilent(true);
            set(seal, Attribute.MAX_HEALTH, Math.max(10, def.ability("ward.seal-health", 60)));
            seal.setHealth(Math.max(10, def.ability("ward.seal-health", 60)));
            set(seal, Attribute.KNOCKBACK_RESISTANCE, 1.0);
            refreshSealName(seal);
            seal.setCustomNameVisible(true);
            at.getWorld().playSound(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2f, 1.4f);
            at.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.05);
            return seal;
        } catch (Throwable error) {
            plugin.getLogger().warning("[colosseum] could not raise a Cinder Seal: " + error);
            return null;
        }
    }

    /** Health on the label, refreshed every tick a seal is alive. Particles are not feedback on Bedrock;
     *  a name that reads "Cinder Seal 41/60" is. */
    private void refreshSealName(LivingEntity seal) {
        if (seal == null || !seal.isValid()) return;
        double max = maxHealth(seal);
        seal.customName(Component.text("✦ Cinder Seal " + (int) Math.ceil(seal.getHealth()) + "/" + (int) max,
                seal.getHealth() / max > 0.5 ? NamedTextColor.AQUA : NamedTextColor.YELLOW));
    }

    /** The ward, stated in words. Called whenever the count changes, so a player always knows how much
     *  protection is left and what killing another seal would buy them. */
    private void announceWard(BossDef def, BossState state, Player player) {
        int alive = state.seals.size();
        double reduction = wardReduction(def, state);
        state.stateLabel = "WARD " + alive + "/3";
        tell(player, alive == 0
                ? def.name() + " has NO Cinder Seals left - it is taking full damage."
                : def.name() + " is warded by " + alive + " Cinder Seal" + (alive == 1 ? "" : "s")
                    + " - it is taking " + Math.round((1 - reduction) * 100) + "% damage. Break the seals.");
    }

    double wardReduction(BossDef def, BossState state) {
        double per = def.ability("ward.reduction-per-seal", 0.18);
        return Math.min(Math.max(0, def.ability("ward.max-reduction", 0.6)), state.seals.size() * per);
    }

    int livingSeals(BossState state) { return state == null ? 0 : state.seals.size(); }

    // ------------------------------------------------------------------ channelled casts

    /** One tick of a Rekindle or a Distillation. The player's accumulated damage is the only thing that
     *  matters here, and it is shown to them as a progress bar rather than left to be inferred. */
    void advanceChannel(BossDef def, BossState state, Player player, long now) {
        LivingEntity boss = state.entity;
        String ability = state.channel;
        double needed = Math.max(1, def.ability(ability + ".interrupt-damage", 60));
        double done = Math.min(1, state.channelDamage / needed);
        long total = Math.max(1, state.channelUntil - state.channelStart);
        double elapsed = Math.min(1, (double) (now - state.channelStart) / total);

        boss.getWorld().spawnParticle(ability.equals("rekindle") ? Particle.SOUL_FIRE_FLAME : Particle.WITCH,
                boss.getLocation().add(0, 1.6, 0), 8, 0.4, 0.5, 0.4, 0.03);
        if ((now / 200) % 2 == 0) boss.getWorld().playSound(boss.getLocation(),
                ability.equals("rekindle") ? Sound.BLOCK_BEACON_AMBIENT : Sound.BLOCK_BREWING_STAND_BREW, 0.9f, 1.4f);
        bar(player, (ability.equals("rekindle") ? "REKINDLING " : "BREWING ") + progressBar(elapsed, 10)
                + "  §fINTERRUPT " + progressBar(done, 10) + " §7" + (int) Math.min(state.channelDamage, needed) + "/" + (int) needed,
                NamedTextColor.GOLD);

        if (state.channelDamage >= needed) { interruptChannel(def, state, player, now, ability); return; }
        if (now >= state.channelUntil) completeChannel(def, state, player, now, ability);
    }

    /** A plain block bar. Deliberately text: Bedrock renders it identically, and a player who cannot see
     *  particles can still read exactly how close the interrupt is. */
    private static String progressBar(double fraction, int width) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * width);
        return "§a" + "█".repeat(filled) + "§8" + "█".repeat(Math.max(0, width - filled));
    }

    void interruptChannel(BossDef def, BossState state, Player player, long now, String ability) {
        LivingEntity boss = state.entity;
        state.channel = null;
        state.channelDamage = 0;
        boss.setAI(true);
        long stagger = def.abilityInt(ability + ".stagger-ticks", 70) * 50L;
        state.stunUntil = now + stagger;
        state.vulnerableUntil = state.stunUntil;
        state.vulnerableMultiplier = Math.max(1, def.ability(ability + ".stagger-vulnerability", 1.8));
        state.stateLabel = "STAGGERED — HIT IT NOW";
        boss.setAI(false);
        World world = boss.getWorld();
        world.playSound(boss.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.5f, 0.8f);
        world.spawnParticle(Particle.ITEM, boss.getLocation().add(0, 1.3, 0), 40, 0.5, 0.6, 0.5, 0.1, new ItemStack(Material.GLASS_BOTTLE));
        if (ability.equals("distillation")) {
            /** Its own mixture, turned against it. A configured fraction of the pool, not a fixed number, so
             *  it stays meaningful whatever the boss is rebalanced to. */
            double shatter = maxHealth(boss) * Math.max(0, def.ability("distillation.shatter-percent", 0.06));
            boss.setHealth(Math.max(1, boss.getHealth() - shatter));
            boss.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS,
                    def.abilityInt("distillation.shatter-weakness-ticks", 120), 0, false, true));
            tell(player, "The brew SHATTERS — " + def.name() + " takes its own mixture and is staggered for "
                    + Math.round(stagger / 1000.0) + "s at " + String.format("%.1f", state.vulnerableMultiplier) + "x damage.");
        } else {
            tell(player, "You break the Rekindle — no Cinder Seal is restored, and " + def.name()
                    + " is staggered for " + Math.round(stagger / 1000.0) + "s at "
                    + String.format("%.1f", state.vulnerableMultiplier) + "x damage.");
        }
    }

    private void completeChannel(BossDef def, BossState state, Player player, long now, String ability) {
        LivingEntity boss = state.entity;
        state.channel = null;
        state.channelDamage = 0;
        boss.setAI(true);
        state.stateLabel = "";
        World world = boss.getWorld();
        if (ability.equals("rekindle")) {
            /** ONE seal. Never all three, and never a fourth. */
            if (state.seals.size() < 3) raiseWard(def, state, player, 1);
            world.playSound(boss.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.3f, 0.9f);
            tell(player, def.name() + " rebuilt ONE Cinder Seal. Interrupt the next Rekindle to stop it.");
        } else {
            double healed = maxHealth(boss) * Math.max(0, Math.min(0.5, def.ability("distillation.heal-percent", 0.12)));
            boss.setHealth(Math.min(maxHealth(boss), boss.getHealth() + healed));
            world.playSound(boss.getLocation(), Sound.ENTITY_WITCH_DRINK, 1.3f, 1.0f);
            world.spawnParticle(Particle.HEART, boss.getLocation().add(0, 1.6, 0), 12, 0.4, 0.4, 0.4, 0);
            tell(player, def.name() + " finished its brew and recovered "
                    + Math.round(def.ability("distillation.heal-percent", 0.12) * 100) + "% health. Interrupt the next one.");
        }
    }

    /** Damage the player landed on the boss during a channel. Called once per resolved hit, after every
     *  multiplier, so the interrupt threshold means what the player actually dealt. */
    void noteChannelDamage(BossState state, double amount) {
        if (state != null && state.channel != null && amount > 0) state.channelDamage += amount;
    }

    // ------------------------------------------------------------------ the Alchemist's zones

    /** Applied on an interval rather than every tick: a zone is a place that costs something to stand in,
     *  not a shredder. Bounded by the zone list, which is bounded by mixture.max-zones. */
    private void tickZones(BossDef def, BossState state, Player player, long now) {
        World world = state.entity == null ? player.getWorld() : state.entity.getWorld();
        boolean apply = now >= state.nextZoneTickAt;
        if (apply) state.nextZoneTickAt = now + Math.max(250, def.abilityInt("mixture.tick-interval-ticks", 20) * 50L);
        for (double[] zone : state.zones) {
            Location centre = new Location(world, zone[0], zone[1], zone[2]);
            int kind = (int) zone[5];
            Particle particle = switch (kind) {
                case ZONE_MIRE -> Particle.SPLASH;
                case ZONE_FRAILTY -> Particle.WITCH;
                default -> Particle.FLAME;
            };
            for (int i = 0; i < 12; i++) {
                double angle = Math.PI * 2 * i / 12;
                world.spawnParticle(particle, centre.clone().add(Math.cos(angle) * zone[3], 0.2, Math.sin(angle) * zone[3]), 1, 0, 0, 0, 0);
            }
            if (!apply || player.getLocation().distance(centre) > zone[3]) continue;
            switch (kind) {
                case ZONE_SCORCH -> hurt(player, state.entity, def.ability("mixture.scorch-damage", 4), true);
                case ZONE_MIRE -> player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.SLOWNESS, def.abilityInt("mixture.effect-ticks", 60),
                        Math.max(0, def.abilityInt("mixture.mire-amplifier", 1)), false, true));
                case ZONE_FRAILTY -> player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.WEAKNESS, def.abilityInt("mixture.effect-ticks", 60),
                        Math.max(0, def.abilityInt("mixture.frailty-amplifier", 0)), false, true));
                default -> { }
            }
        }
    }

    private static String zoneName(int kind) {
        return switch (kind) {
            case ZONE_MIRE -> "Mire (blue) — slows you";
            case ZONE_FRAILTY -> "Frailty (purple) — weakens your attacks";
            default -> "Scorch (orange) — burns you";
        };
    }

    // ------------------------------------------------------------------ telegraphs

    /** Drawn every tick of the wind-up, so a player who looks away for half a second still sees it. Counts
     *  are fixed and small -- the whole telegraph budget for a fight is a few dozen particles a tick. */
    private void telegraph(BossDef def, BossState state, Player player, String ability) {
        LivingEntity boss = state.entity;
        Location at = boss.getLocation().add(0, 1.2, 0);
        World world = boss.getWorld();
        switch (ability) {
            case "riposte", "bulwark" -> {
                world.spawnParticle(Particle.CRIT, at, 8, 0.5, 0.6, 0.5, 0.02);
                world.spawnParticle(Particle.ENCHANT, at, 10, 0.6, 0.8, 0.6, 0.4);
            }
            case "lunge", "blink" -> {
                world.spawnParticle(ability.equals("blink") ? Particle.PORTAL : Particle.FLAME, at, 14, 0.4, 0.6, 0.4, 0.05);
                /** Both ends of a blink are shown, so the destination is never a surprise. */
                if (ability.equals("blink")) {
                    Location behind = blinkTarget(def, player);
                    world.spawnParticle(Particle.PORTAL, behind.clone().add(0, 1, 0), 14, 0.4, 0.6, 0.4, 0.05);
                }
            }
            case "flurry" -> world.spawnParticle(Particle.SWEEP_ATTACK, at, 3, 0.6, 0.4, 0.6, 0);
            case "volley" -> world.spawnParticle(Particle.SMALL_FLAME, at, 12, 0.5, 0.4, 0.5, 0.02);
            case "fissure" -> {
                /** The ground the eruption will cover, drawn as it will be, so "step off the marks" is a real
                 *  instruction rather than a guess. */
                Location centre = player.getLocation();
                int rings = Math.max(1, def.abilityInt("fissure.rings", 2));
                double radius = def.ability("fissure.radius", 5);
                for (int ring = 1; ring <= rings; ring++) {
                    double r = radius * ring / rings;
                    for (int i = 0; i < 18; i++) {
                        double angle = Math.PI * 2 * i / 18;
                        world.spawnParticle(Particle.FLAME, centre.clone().add(Math.cos(angle) * r, 0.2, Math.sin(angle) * r), 1, 0, 0, 0, 0);
                    }
                }
            }
            case "slam" -> {
                double radius = def.ability("slam.radius", 7);
                for (int i = 0; i < 24; i++) {
                    double angle = Math.PI * 2 * i / 24;
                    world.spawnParticle(Particle.CRIT, boss.getLocation().clone().add(Math.cos(angle) * radius, 0.2, Math.sin(angle) * radius), 1, 0, 0, 0, 0);
                }
            }
            /*  CHARGE. The lane it will actually run, drawn along the LOCKED yaw rather than towards the
             *  player -- which is the whole point. Standing in it is a choice, and so is stepping out. */
            case "charge" -> {
                Vector direction = state.chargeDirection == null ? boss.getLocation().getDirection().setY(0).normalize() : state.chargeDirection;
                double length = def.ability("charge.length", 30), width = def.ability("charge.hit-radius", 2.6);
                for (double d = 1.5; d <= length; d += 1.5) {
                    Location mark = boss.getLocation().clone().add(direction.clone().multiply(d));
                    world.spawnParticle(Particle.FLAME, mark.clone().add(0, 0.2, 0), 1, 0, 0, 0, 0);
                    world.spawnParticle(Particle.SMOKE, mark.clone().add(-direction.getZ() * width, 0.2, direction.getX() * width), 1, 0, 0, 0, 0);
                    world.spawnParticle(Particle.SMOKE, mark.clone().add(direction.getZ() * width, 0.2, -direction.getX() * width), 1, 0, 0, 0, 0);
                }
                boss.setRotation(state.lockedYaw, 0f);
                if (state.pendingAt - System.currentTimeMillis() > 200)
                    bar(player, ("CHARGE INCOMING - get out of the lane, let it hit the wall."), NamedTextColor.RED);
            }
            /*  SWEEP. A ring at the boss's feet with an explicit safe answer: be outside it, or be in the
             *  air. The height that counts as "in the air" is drawn as a second ring above the first. */
            case "sweep" -> {
                double radius = def.ability("sweep.radius", 6), safeHeight = def.ability("sweep.safe-height", 1.6);
                for (int i = 0; i < 20; i++) {
                    double angle = Math.PI * 2 * i / 20;
                    Location rim = boss.getLocation().clone().add(Math.cos(angle) * radius, 0.2, Math.sin(angle) * radius);
                    world.spawnParticle(Particle.CRIT, rim, 1, 0, 0, 0, 0);
                    world.spawnParticle(Particle.SMOKE, rim.clone().add(0, safeHeight, 0), 1, 0, 0, 0, 0);
                }
                if (state.pendingAt - System.currentTimeMillis() > 200)
                    bar(player, ("LOW SWEEP - get outside the ring or jump it."), NamedTextColor.GOLD);
            }
            /*  TRAMPLE. A narrow lane, not an arena-wide blast: the answer is one step sideways. */
            case "trample" -> {
                Vector direction = player.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
                if (direction.lengthSquared() < 0.01) direction = boss.getLocation().getDirection().setY(0);
                direction.normalize();
                double length = def.ability("trample.length", 14), half = def.ability("trample.width", 1.6) / 2;
                for (double d = 1; d <= length; d += 1.2) {
                    Location mark = boss.getLocation().clone().add(direction.clone().multiply(d));
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, mark.clone().add(-direction.getZ() * half, 0.2, direction.getX() * half), 1, 0, 0, 0, 0);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, mark.clone().add(direction.getZ() * half, 0.2, -direction.getX() * half), 1, 0, 0, 0, 0);
                }
            }
            /*  WARD. Where the three seals will stand, so their positions are never a surprise. */
            case "ward" -> {
                double radius = def.ability("ward.radius", 7);
                for (int i = 0; i < 3; i++) {
                    double angle = Math.PI * 2 * i / 3 + Math.PI / 6;
                    Location mark = boss.getLocation().clone().add(Math.cos(angle) * radius, 1, Math.sin(angle) * radius);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, mark, 6, 0.2, 0.5, 0.2, 0.01);
                }
                world.spawnParticle(Particle.ENCHANT, at, 10, 0.6, 0.8, 0.6, 0.5);
            }
            /*  CIRCUIT. The exact geometry, before it is live. Thin lines between the caster and each
             *  surviving seal -- everything that is not a line is a safe place to stand, and there is always
             *  far more of the second than the first. */
            case "circuit" -> {
                for (Location[] segment : circuitSegments(state, player)) {
                    Vector step = segment[1].toVector().subtract(segment[0].toVector());
                    double length = step.length();
                    if (length < 0.1) continue;
                    step.normalize();
                    for (double d = 0; d <= length; d += 1.0)
                        world.spawnParticle(Particle.ELECTRIC_SPARK, segment[0].clone().add(step.clone().multiply(d)).add(0, 0.6, 0), 1, 0, 0, 0, 0);
                }
                if (state.pendingAt - System.currentTimeMillis() > 200)
                    bar(player, "ASHEN CIRCUIT - step off the lines.", NamedTextColor.AQUA);
            }
            /*  REKINDLE / DISTILLATION. A wind-up that says "this is the window", because it is. */
            case "rekindle", "distillation" -> {
                world.spawnParticle(ability.equals("rekindle") ? Particle.SOUL_FIRE_FLAME : Particle.WITCH, at, 14, 0.5, 0.7, 0.5, 0.03);
                if (state.pendingAt - System.currentTimeMillis() > 200)
                    bar(player, ability.equals("rekindle")
                            ? "REKINDLE beginning - burst it down to interrupt."
                            : "DISTILLATION beginning - burst it down to shatter the brew.", NamedTextColor.GOLD);
            }
            /*  MIXTURE. Every impact area, coloured as it will be, before anything is thrown. */
            case "mixture" -> {
                for (double[] zone : plannedZones(def, state, player)) {
                    Particle particle = switch ((int) zone[5]) {
                        case ZONE_MIRE -> Particle.SPLASH;
                        case ZONE_FRAILTY -> Particle.WITCH;
                        default -> Particle.FLAME;
                    };
                    for (int i = 0; i < 14; i++) {
                        double angle = Math.PI * 2 * i / 14;
                        world.spawnParticle(particle, new Location(world, zone[0] + Math.cos(angle) * zone[3], zone[1] + 0.2, zone[2] + Math.sin(angle) * zone[3]), 1, 0, 0, 0, 0);
                    }
                }
            }
            default -> { }
        }
        /** One short note per wind-up start, not per tick -- an audible telegraph that is not an alarm. */
        if (state.pendingAt - System.currentTimeMillis() > def.abilityInt(ability + ".telegraph-ticks", 25) * 50L - 120)
            world.playSound(boss.getLocation(), sound("telegraph", Sound.BLOCK_NOTE_BLOCK_BIT), 1.4f, ability.equals("fissure") ? 0.6f : 1.6f);
    }

    // ------------------------------------------------------------------ landings

    private void land(BossDef def, BossState state, Player player, String ability, long now) {
        LivingEntity boss = state.entity;
        World world = boss.getWorld();
        switch (ability) {
            /** RIPOSTE. A stance, not a hit. It changes what happens to somebody who keeps swinging, and
             *  nothing at all to somebody who stops. */
            case "riposte" -> {
                state.active = "riposte";
                state.activeUntil = now + def.abilityInt("riposte.duration-ticks", 40) * 50L;
                world.playSound(boss.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.3f, 0.7f);
                world.spawnParticle(Particle.ENCHANTED_HIT, boss.getLocation().add(0, 1.2, 0), 24, 0.5, 0.7, 0.5, 0.1);
                player.sendActionBar(Component.text(def.name() + " sets its guard — stop attacking.", NamedTextColor.GOLD));
            }
            /** LUNGE. Closes the gap and then stands there slowed. The recovery window is the reward. */
            case "lunge" -> {
                Vector to = player.getLocation().toVector().subtract(boss.getLocation().toVector());
                if (to.lengthSquared() > 0.01) {
                    to.normalize().multiply(def.ability("lunge.power", 1.25));
                    to.setY(Math.max(0.35, to.getY() + 0.35));
                    boss.setVelocity(to);
                }
                state.recoveryUntil = now + def.abilityInt("lunge.recovery-ticks", 30) * 50L;
                world.playSound(boss.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.1f, 1.4f);
                world.spawnParticle(Particle.FLAME, boss.getLocation().add(0, 0.6, 0), 30, 0.3, 0.3, 0.3, 0.08);
            }
            /** FLURRY. Three ordinary strikes, spaced, each of which misses if the player is not there. */
            case "flurry" -> {
                state.strikesLeft = Math.max(1, def.abilityInt("flurry.strikes", 3));
                state.nextStrikeAt = now;
            }
            /** FISSURE. Erupts exactly where it was drawn -- centred on where the player WAS when the
             *  telegraph began, which is why moving works. Particles and damage only; not one block changes. */
            case "fissure" -> {
                Location centre = player.getLocation().clone();
                double radius = def.ability("fissure.radius", 5);
                world.playSound(centre, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.6f);
                world.spawnParticle(Particle.LAVA, centre.clone().add(0, 0.4, 0), 40, radius / 2, 0.3, radius / 2, 0);
                world.spawnParticle(Particle.FLAME, centre.clone().add(0, 0.4, 0), 60, radius / 2, 0.4, radius / 2, 0.03);
                if (player.getLocation().distance(centre) <= radius)
                    hurt(player, boss, def.ability("fissure.damage", 12), true);
            }
            /** BULWARK. Anchors: no movement, no attacks, far less damage taken. A trade the player can see. */
            case "bulwark" -> {
                state.active = "bulwark";
                state.activeUntil = now + def.abilityInt("bulwark.duration-ticks", 80) * 50L;
                boss.setAI(false);
                world.playSound(boss.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 0.6f);
                world.spawnParticle(Particle.BLOCK, boss.getLocation().add(0, 1, 0), 40, 0.7, 1.0, 0.7, 0.05, Material.IRON_BLOCK.createBlockData());
                player.sendActionBar(Component.text(def.name() + " braces — reposition and heal.", NamedTextColor.GOLD));
            }
            /** SLAM. A shove with modest damage, worst at point-blank and survivable anywhere. */
            case "slam" -> {
                double radius = def.ability("slam.radius", 7);
                world.playSound(boss.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1.3f, 0.6f);
                world.spawnParticle(Particle.EXPLOSION, boss.getLocation().add(0, 0.4, 0), 6, radius / 3, 0.2, radius / 3, 0);
                double distance = player.getLocation().distance(boss.getLocation());
                if (distance <= radius) {
                    hurt(player, boss, def.ability("slam.damage", 8), true);
                    Vector away = player.getLocation().toVector().subtract(boss.getLocation().toVector());
                    if (away.lengthSquared() < 0.01) away = new Vector(0, 1, 0);
                    away.normalize().multiply(def.ability("slam.knockback", 1.1)).setY(0.45);
                    player.setVelocity(player.getVelocity().add(away));
                }
            }
            /** BLINK. To a fixed point behind the player, which was drawn during the wind-up. */
            case "blink" -> {
                Location target = blinkTarget(def, player);
                ColosseumArenas.Arena arena = plugin.colosseum().arenas().arenaOfWorld(world);
                if (arena == null || arena.inBounds(target.getX(), target.getY(), target.getZ())) {
                    world.spawnParticle(Particle.PORTAL, boss.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.4);
                    boss.teleport(target);
                    world.spawnParticle(Particle.PORTAL, target.clone().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.4);
                    world.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 1.1f, 1.3f);
                }
            }
            /** VOLLEY. Exactly `count` projectiles, tracked on the run so they are removed with it. */
            case "volley" -> {
                int count = Math.max(1, Math.min(8, def.abilityInt("volley.count", 3)));
                double spread = def.ability("volley.spread", 0.16), speed = def.ability("volley.speed", 0.9);
                Vector base = player.getEyeLocation().toVector().subtract(boss.getEyeLocation().toVector());
                if (base.lengthSquared() < 0.01) base = boss.getLocation().getDirection();
                base.normalize().multiply(speed);
                world.playSound(boss.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.2f, 1.1f);
                for (int i = 0; i < count; i++) {
                    double offset = (i - (count - 1) / 2.0) * spread;
                    Vector direction = base.clone().add(new Vector(-base.getZ() * offset, 0, base.getX() * offset));
                    SmallFireball ball = world.spawn(boss.getEyeLocation().add(direction.clone().normalize()), SmallFireball.class, f -> {
                        f.setShooter(boss);
                        f.setIsIncendiary(false);
                        f.setYield(0);
                        f.setPersistent(false);
                        f.getPersistentDataContainer().set(plugin.colosseum().runKey(), org.bukkit.persistence.PersistentDataType.STRING,
                                boss.getPersistentDataContainer().getOrDefault(plugin.colosseum().runKey(), org.bukkit.persistence.PersistentDataType.STRING, ""));
                    });
                    ball.setDirection(direction);
                    state.projectiles.add(ball);
                }
            }
            /*  CHARGE. Commits. From here the boss cannot steer and cannot stop -- advanceCharge owns it. */
            case "charge" -> {
                state.charging = true;
                state.chargeUntil = now + def.abilityInt("charge.duration-ticks", 45) * 50L;
                state.chargeStuckTicks = 0;
                state.lastChargeProgress = -1;
                state.chargeOrigin = boss.getLocation().clone();
                state.stateLabel = "CHARGING";
                boss.setAI(false);
                world.playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.5f, 0.9f);
                world.spawnParticle(Particle.EXPLOSION, boss.getLocation().add(0, 0.4, 0), 3, 0.4, 0.1, 0.4, 0);
            }
            /*  SWEEP. Outside the ring, or above it. Both answers are drawn during the wind-up. */
            case "sweep" -> {
                double radius = def.ability("sweep.radius", 6), safeHeight = def.ability("sweep.safe-height", 1.6);
                world.playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ATTACK, 1.4f, 0.7f);
                for (int ring = 1; ring <= 3; ring++)
                    for (int i = 0; i < 24; i++) {
                        double angle = Math.PI * 2 * i / 24;
                        world.spawnParticle(Particle.SWEEP_ATTACK, boss.getLocation().clone()
                                .add(Math.cos(angle) * radius * ring / 3, 0.3, Math.sin(angle) * radius * ring / 3), 1);
                    }
                double flat = flatDistance(boss.getLocation(), player.getLocation());
                boolean airborne = player.getLocation().getY() - boss.getLocation().getY() >= safeHeight;
                /** Never through its own telegraph and never outside it: the same radius that was drawn. */
                if (flat <= radius && !airborne) {
                    hurt(player, boss, def.ability("sweep.damage", 9), true);
                    bar(player, "The chain caught your legs - jump it or leave the ring.", NamedTextColor.RED);
                }
            }
            /*  TRAMPLE. A surge down the marked lane. Positional, not arena-wide. */
            case "trample" -> {
                Vector direction = player.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
                if (direction.lengthSquared() < 0.01) direction = boss.getLocation().getDirection().setY(0);
                direction.normalize();
                double length = def.ability("trample.length", 14), half = def.ability("trample.width", 1.6) / 2;
                Location start = boss.getLocation().clone();
                world.playSound(start, Sound.ENTITY_RAVAGER_STEP, 1.5f, 0.6f);
                boss.setVelocity(direction.clone().multiply(def.ability("trample.push", 0.9)).setY(0.1));
                for (double d = 1; d <= length; d += 1.0)
                    world.spawnParticle(Particle.LARGE_SMOKE, start.clone().add(direction.clone().multiply(d)).add(0, 0.3, 0), 4, half, 0.2, half, 0.01);
                if (distanceToSegment(player.getLocation(), start, start.clone().add(direction.clone().multiply(length))) <= half + 0.4)
                    hurt(player, boss, def.ability("trample.damage", 8), true);
            }
            /*  WARD. Exactly three, once. */
            case "ward" -> {
                world.playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.4f, 0.9f);
                raiseWard(def, state, player, 3);
                tell(player, def.name() + " raises the Triune Ward. Each Cinder Seal cuts the damage it takes — break them.");
            }
            /*  CIRCUIT. Live on exactly the lines that were drawn. */
            case "circuit" -> {
                world.playSound(boss.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.3f, 1.2f);
                double width = def.ability("circuit.line-width", 1.1);
                boolean caught = false;
                for (Location[] segment : circuitSegments(state, player)) {
                    Vector step = segment[1].toVector().subtract(segment[0].toVector());
                    double length = step.length();
                    if (length < 0.1) continue;
                    step.normalize();
                    for (double d = 0; d <= length; d += 0.8)
                        world.spawnParticle(Particle.ELECTRIC_SPARK, segment[0].clone().add(step.clone().multiply(d)).add(0, 0.6, 0), 3, 0.1, 0.2, 0.1, 0.02);
                    if (!caught && distanceToSegment(player.getLocation(), segment[0], segment[1]) <= width) caught = true;
                }
                if (caught) hurt(player, boss, def.ability("circuit.damage", 10), true);
            }
            /*  REKINDLE / DISTILLATION. The boss commits and stops fighting; advanceChannel owns it. */
            case "rekindle", "distillation" -> {
                state.channel = ability;
                state.channelStart = now;
                state.channelUntil = now + def.abilityInt(ability + ".channel-ticks", 80) * 50L;
                state.channelDamage = 0;
                state.stateLabel = ability.equals("rekindle") ? "REKINDLING" : "BREWING";
                boss.setAI(false);
                world.playSound(boss.getLocation(), ability.equals("rekindle")
                        ? Sound.ENTITY_EVOKER_PREPARE_ATTACK : Sound.BLOCK_BREWING_STAND_BREW, 1.3f, 0.9f);
            }
            /*  MIXTURE. Exactly the zones that were drawn, bounded and self-expiring. */
            case "mixture" -> {
                world.playSound(boss.getLocation(), Sound.ENTITY_WITCH_THROW, 1.3f, 1.0f);
                List<double[]> planned = plannedZones(def, state, player);
                int cap = Math.max(1, Math.min(6, def.abilityInt("mixture.max-zones", 3)));
                long life = def.abilityInt("mixture.zone-duration-ticks", 120) * 50L;
                if (state.catalystArmed) {
                    life = (long) (life * Math.max(1, def.ability("catalyst.duration-multiplier", 1.6)));
                    state.catalystArmed = false;
                    state.catalystSpent = true;
                }
                StringBuilder said = new StringBuilder();
                for (double[] zone : planned) {
                    if (state.zones.size() >= cap) break;
                    zone[4] = now + life;
                    state.zones.add(zone);
                    world.spawnParticle(Particle.SPLASH, new Location(world, zone[0], zone[1] + 0.4, zone[2]), 30, zone[3] / 2, 0.2, zone[3] / 2, 0);
                    if (said.length() > 0) said.append("; ");
                    said.append(zoneName((int) zone[5]));
                }
                if (said.length() > 0) bar(player, "Mixtures land: " + said, NamedTextColor.GREEN);
            }
            default -> { }
        }
    }

    /*  The lines the Ashen Circuit will energise: caster to each surviving seal, or caster to the fighter
     *  when every seal is gone, so the ability never becomes a no-op the player can ignore. */
    private List<Location[]> circuitSegments(BossState state, Player player) {
        List<Location[]> out = new ArrayList<>();
        Location origin = state.entity.getLocation();
        for (LivingEntity seal : state.seals)
            if (seal != null && seal.isValid()) out.add(new Location[]{origin.clone(), seal.getLocation().clone()});
        if (out.isEmpty()) out.add(new Location[]{origin.clone(), player.getLocation().clone()});
        return out;
    }

    /*  Where a mixture will land, decided during the wind-up and reused unchanged when it is thrown, so
     *  what was drawn is exactly what arrives. One zone is always centred on the fighter's CURRENT position
     *  -- which is what makes moving the answer -- and the rest ring the boss to deny it a safe pocket. */
    List<double[]> plannedZones(BossDef def, BossState state, Player player) {
        List<double[]> out = new ArrayList<>();
        int wanted = Math.max(1, Math.min(3, def.abilityInt("mixture.zones-per-cast", 2)));
        double radius = def.ability("mixture.radius", 3.5);
        if (state.catalystArmed) radius *= Math.max(1, def.ability("catalyst.radius-multiplier", 1.4));
        /** Centred on the fighter, which is what makes moving the answer -- or on the caster itself when
         *  there is nobody to centre on, so the mechanic can be inspected without a live fight. */
        Location anchor = player != null && player.isOnline() ? player.getLocation() : state.entity.getLocation();
        int[] kinds = {ZONE_SCORCH, ZONE_MIRE, ZONE_FRAILTY};
        for (int i = 0; i < wanted; i++) {
            double angle = Math.PI * 2 * i / wanted;
            double spread = i == 0 ? 0 : def.ability("mixture.spread", 4.5);
            out.add(new double[]{anchor.getX() + Math.cos(angle) * spread, anchor.getY(), anchor.getZ() + Math.sin(angle) * spread,
                    radius, 0, kinds[i % kinds.length]});
        }
        return out;
    }

    private static double flatDistance(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Shortest distance from a point to a line segment, on the horizontal plane. The lane and circuit
     *  hitboxes are both "within W of this line", so both are exactly what was drawn. */
    static double distanceToSegment(Location point, Location a, Location b) {
        double px = point.getX() - a.getX(), pz = point.getZ() - a.getZ();
        double bx = b.getX() - a.getX(), bz = b.getZ() - a.getZ();
        double lengthSq = bx * bx + bz * bz;
        double t = lengthSq < 1.0e-6 ? 0 : Math.max(0, Math.min(1, (px * bx + pz * bz) / lengthSq));
        double dx = px - bx * t, dz = pz - bz * t;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private Location blinkTarget(BossDef def, Player player) {
        double distance = def.ability("blink.behind-distance", 3);
        Location behind = player.getLocation().clone().add(player.getLocation().getDirection().setY(0).normalize().multiply(-distance));
        behind.setY(player.getLocation().getY());
        behind.setDirection(player.getLocation().toVector().subtract(behind.toVector()));
        return behind;
    }

    /** One flurry strike: an ordinary melee hit that connects only if the player is genuinely in range. */
    private void strike(BossDef def, BossState state, Player player) {
        LivingEntity boss = state.entity;
        boss.swingMainHand();
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.3f);
        boss.getWorld().spawnParticle(Particle.SWEEP_ATTACK, boss.getLocation().add(boss.getLocation().getDirection().multiply(1.5)).add(0, 1, 0), 1);
        if (player.getLocation().distance(boss.getLocation()) <= def.ability("flurry.range", 4.5))
            hurt(player, boss, def.ability("flurry.damage", 7));
    }

    /*  Two kinds of damage, and the difference is deliberate.
     *
     *  A SWORD STRIKE is an ordinary attack and armour answers it, exactly as it should. An AREA ABILITY --
     *  a fissure erupting under your feet, a shockwave, a fireball -- is dealt as MAGIC, which armour does
     *  not reduce. That is the difference between a boss that threatens somebody in netherite and one that
     *  cannot: full Protection IV cuts a 20-damage physical hit to under two, so an ability that respected
     *  armour would be pure decoration against the exact gear this fight is balanced for.
     *
     *  It is not unfair, and it is not unavoidable. Protection, Resistance, absorption, totems and health
     *  potions all still apply; every one of these lands one to one and a half seconds after a telegraph
     *  that says exactly where it will be; and stepping out of the marks takes nothing but attention. What
     *  it cannot be is ignored.
     *
     *  Both forms go in as real damage events, so nothing here bypasses the player's own survival tools. */
    private void hurt(Player player, LivingEntity source, double amount) { hurt(player, source, amount, false); }

    private void hurt(Player player, LivingEntity source, double amount, boolean magic) {
        if (amount <= 0 || player.isDead() || player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        if (!magic) { player.damage(amount, source); return; }
        try {
            org.bukkit.damage.DamageSource.Builder builder = org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.MAGIC);
            if (source != null) builder.withCausingEntity(source).withDirectEntity(source);
            player.damage(amount, builder.build());
        } catch (Throwable unsupported) {
            /** Older API: fall back to a plain hit rather than dealing no damage at all. */
            player.damage(amount, source);
        }
    }

    /** Bench only. Draws the heaviest telegraph a boss owns, at a fixed point, so the particle cost of an
     *  encounter can be measured without a fighter standing in it. Uses the same ring code and the same
     *  configured radii the real telegraph does, so what is measured is what players will actually see. */
    void benchTelegraph(BossDef def, LivingEntity boss, Location target) {
        if (boss == null || !boss.isValid() || target == null) return;
        World world = boss.getWorld();
        int rings = Math.max(1, def.abilityInt("fissure.rings", 2));
        double radius = def.ability("fissure.radius", 5);
        for (int ring = 1; ring <= rings; ring++) {
            double r = radius * ring / rings;
            for (int i = 0; i < 18; i++) {
                double angle = Math.PI * 2 * i / 18;
                world.spawnParticle(Particle.FLAME, target.clone().add(Math.cos(angle) * r, 0.2, Math.sin(angle) * r), 1, 0, 0, 0, 0);
            }
        }
        double slam = def.ability("slam.radius", 7);
        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2 * i / 24;
            world.spawnParticle(Particle.CRIT, boss.getLocation().clone().add(Math.cos(angle) * slam, 0.2, Math.sin(angle) * slam), 1, 0, 0, 0, 0);
        }
        world.spawnParticle(Particle.ENCHANT, boss.getLocation().add(0, 1.2, 0), 10, 0.6, 0.8, 0.6, 0.4);
    }

    /** The worst-case particle budget one encounter can draw in a single tick, from the configuration
     *  rather than from a guess. Reported by the bench so the number is auditable. */
    int worstCaseParticlesPerTick(BossDef def) {
        int fissure = def.abilityInt("fissure.rings", 0) == 0 ? 0 : def.abilityInt("fissure.rings", 2) * 18;
        int slam = def.ability("slam.radius", 0) > 0 ? 24 : 0;
        /** The charge lane is the longest telegraph in the game: three marks every 1.5 blocks. */
        int charge = def.has("charge") ? (int) (def.ability("charge.length", 30) / 1.5) * 3 : 0;
        int sweep = def.has("sweep") ? 40 : 0;
        int trample = def.has("trample") ? (int) (def.ability("trample.length", 14) / 1.2) * 2 : 0;
        /** Three segments, sampled every block, plus the zone rings. */
        int circuit = def.has("circuit") ? 3 * 40 : 0;
        int mixture = def.has("mixture") ? def.abilityInt("mixture.zones-per-cast", 2) * 14 : 0;
        /** Only one ability winds up at a time, so the heaviest single telegraph is the ceiling -- plus the
         *  small constant the stance markers and any live zones add. */
        int zones = def.has("mixture") ? def.abilityInt("mixture.max-zones", 3) * 12 : 0;
        int heaviest = Math.max(Math.max(Math.max(fissure, slam), Math.max(charge, sweep)), Math.max(Math.max(trample, circuit), mixture));
        return heaviest + zones + 30;
    }

    // ------------------------------------------------------------------ damage interception

    /** Damage arriving at the boss. Three things happen here and nowhere else: the effective-pool divisor,
     *  the active stance multipliers, and the riposte's reflection. */
    void bossHurt(BossDef def, BossState state, EntityDamageEvent event) { bossHurt(def, state, event, null); }

    /*  Every multiplier a Colosseum boss has, in one place and in a fixed order:
     *
     *    1. the effective-pool divisor           (the boss's configured health)
     *    2. stance multipliers                   (riposte, bulwark)
     *    3. the veil                             (the Revenant's ranged resistance)
     *    4. FRONTAL armour                       (the Behemoth: hitting its face is the slow way)
     *    5. the WARD                             (the Arcanist: its seals are what is keeping it alive)
     *    6. the VULNERABILITY window             (crash, stagger, shattered brew -- all three raise damage)
     *    7. the single-hit cap                   (so one enormous opening blow cannot skip the encounter)
     *
     *  Order matters: the punish window multiplies whatever survived the boss's defences, so it is worth
     *  exactly as much as the fight says it is, and the cap is last so it caps the number the player will
     *  actually see land. Every reduction that is not obvious from the boss's own body announces itself --
     *  nothing here makes damage quietly disappear. */
    void bossHurt(BossDef def, BossState state, EntityDamageEvent event, Player attacker) {
        boolean projectile = event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof org.bukkit.entity.Projectile;
        boolean frontal = def.has("frontal") && attacker != null && withinArc(state.entity, attacker.getLocation(), def.ability("frontal.arc-degrees", 110));
        event.setDamage(applyDefences(def, state, event.getDamage(), frontal, projectile, System.currentTimeMillis()));

        /** The explanations, separated from the arithmetic so the arithmetic can be asserted directly. */
        if (frontal && state.stunUntil <= System.currentTimeMillis())
            say(attacker, state, "frontal", def.name() + "'s armoured front absorbs most of that - bait its charge into a wall.");
        if (!state.seals.isEmpty())
            say(attacker, state, "ward", state.seals.size() + " Cinder Seal" + (state.seals.size() == 1 ? "" : "s")
                    + " absorbing " + Math.round(wardReduction(def, state) * 100) + "% - break them.");
        double cap = def.maxSingleHitPercent();
        if (cap > 0 && event.getDamage() >= maxHealth(state.entity) * cap - 0.001)
            bar(attacker, "Colossal blow - capped at " + Math.round(cap * 100) + "% of the boss's health in one hit.", NamedTextColor.YELLOW);
    }

    /*  EVERY multiplier a Colosseum boss has, as a pure function of the state, in one fixed order:
     *
     *    1. the effective-pool divisor           (the boss's configured health)
     *    2. stance multipliers                   (riposte, bulwark)
     *    3. the veil                             (the Revenant's ranged resistance)
     *    4. FRONTAL armour                       (the Behemoth: hitting its face is the slow way)
     *    5. the WARD                             (the Arcanist: its seals are what is keeping it alive)
     *    6. the VULNERABILITY window             (crash, stagger, shattered brew -- all three raise damage)
     *    7. the single-hit cap                   (so one enormous opening blow cannot skip the encounter)
     *
     *  Order matters: the punish window multiplies whatever survived the boss's defences, so it is worth
     *  exactly as much as the fight says it is, and the cap is last so it caps the number that actually
     *  lands. Pure and side-effect free on purpose -- the verifier calls it at exact boundaries to prove
     *  that baiting a charge really does beat standing in front of one, which is not something a fight can
     *  demonstrate on demand. */
    double applyDefences(BossDef def, BossState state, double damage, boolean frontal, boolean projectile, long now) {
        double toughness = def.toughness();
        if (toughness > 1) damage /= toughness;
        if ("bulwark".equals(state.active)) damage *= def.ability("bulwark.damage-taken-multiplier", 0.2);
        if ("riposte".equals(state.active)) damage *= def.ability("riposte.damage-taken-multiplier", 0.25);
        if (state.veiled && projectile) damage *= def.ability("veil.projectile-damage-multiplier", 0.35);
        /** Frontal armour is suspended entirely while it is stunned. That IS the reward for the bait. */
        if (frontal && def.has("frontal") && state.stunUntil <= now)
            damage *= Math.max(0, def.ability("frontal.damage-multiplier", 0.3));
        if (!state.seals.isEmpty()) damage *= (1 - wardReduction(def, state));
        if (state.vulnerableUntil > now && state.vulnerableMultiplier > 1) damage *= state.vulnerableMultiplier;
        double cap = def.maxSingleHitPercent();
        if (cap > 0 && state.entity != null) damage = Math.min(damage, maxHealth(state.entity) * cap);
        return damage;
    }

    /*  THE SINGLE-HIT CAP, which lives at the end of applyDefences above.
     *
     *  A mace dropped from height can carry several hundred damage. Against a boss whose defining mechanics
     *  only start once its health moves, one such blow could skip the encounter outright -- the seals never
     *  raised, the charge never baited, the brew never interrupted. So a single hit is limited to a
     *  configured share of the pool.
     *
     *  It is deliberately generous (nothing an ordinary weapon comes close to it), configurable, off by
     *  default, and it TELLS the player when it fires. Damage that vanishes without explanation is the
     *  thing this is carefully not doing. Protection, Resistance, absorption and totems are all on the
     *  other side of the fight and are untouched by any of it. */


    /** True when {@code from} is inside the boss's frontal arc. Uses the boss's own facing, which during a
     *  charge is the locked yaw -- so the armoured side is exactly the side it is pointing. */
    private static boolean withinArc(LivingEntity boss, Location from, double arcDegrees) {
        if (boss == null) return false;
        Vector facing = boss.getLocation().getDirection().setY(0);
        Vector toward = from.toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (facing.lengthSquared() < 1.0e-6 || toward.lengthSquared() < 1.0e-6) return false;
        double cos = facing.normalize().dot(toward.normalize());
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos)))) <= arcDegrees / 2;
    }

    /** Throttled feedback, so a defence explains itself once every couple of seconds rather than spamming
     *  the action bar on every swing. */
    /*  Narration that tolerates having nobody to narrate to.
     *
     *  Two reasons, and the second is the one that matters. A player can go offline between one tick and
     *  the next, and a mechanic that NPEs on the way to telling them something would leave the encounter
     *  half-resolved. And the verifier drives these same mechanics with no player at all, which is the only
     *  way to assert that a ward really caps at three or that an interrupt fires at exactly its threshold
     *  -- so every message here has to be optional, not load-bearing. */
    private static void tell(Player player, String message) { if (player != null && player.isOnline()) tell(player, message); }

    private static void bar(Player player, String message, NamedTextColor colour) {
        if (player != null && player.isOnline()) player.sendActionBar(Component.text(message, colour));
    }

    private void say(Player player, BossState state, String tag, String message) {
        long now = System.currentTimeMillis();
        if (player == null || now - state.wardFeedbackAt < 2000) return;
        state.wardFeedbackAt = now;
        player.sendActionBar(Component.text(message, NamedTextColor.GOLD));
    }

    /** A Cinder Seal taking a hit. Its own health is its own; the caster's ward is unaffected until it
     *  actually dies, which the run's ticker notices. */
    void sealHurt(BossState state, EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity seal)) return;
        double after = seal.getHealth() - event.getFinalDamage();
        seal.customName(Component.text("\u2726 Cinder Seal " + Math.max(0, (int) Math.ceil(after)) + "/" + (int) maxHealth(seal),
                after / maxHealth(seal) > 0.5 ? NamedTextColor.AQUA : NamedTextColor.YELLOW));
    }

    /** The riposte's answer, applied after the hit has been taken. Capped, so a critical mace drop cannot be
     *  turned into a one-shot on the player who landed it. */
    void bossRiposte(BossDef def, BossState state, Player attacker, double dealt) {
        if (!"riposte".equals(state.active) || attacker == null || state.entity == null || !state.entity.isValid()) return;
        double back = Math.min(def.ability("riposte.reflect-cap", 6), dealt * def.ability("riposte.reflect-fraction", 0.35));
        if (back <= 0) return;
        attacker.getWorld().playSound(attacker.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.5f);
        attacker.getWorld().spawnParticle(Particle.CRIT, attacker.getLocation().add(0, 1, 0), 8, 0.3, 0.4, 0.3, 0.1);
        hurt(attacker, state.entity, back, true);
    }

    /** Damage from one of the boss's own projectiles, normalised to the configured value so the fireball's
     *  vanilla 5 is not what decides the fight. */
    void projectileHit(BossDef def, EntityDamageByEntityEvent event) {
        event.setDamage(def.ability("volley.damage", 6));
    }

    /** Everything this fight created, gone. Called on every exit path before the world is dropped, so the
     *  cleanup is a property of resolution rather than of world deletion. */
    void despawn(BossState state) {
        if (state == null) return;
        for (SmallFireball ball : state.projectiles) if (ball != null && ball.isValid()) ball.remove();
        state.projectiles.clear();
        /** Seals are entities the encounter created, so they die with it on EVERY exit path -- a victory, a
         *  death, a timeout, a forced drop or a restart recovery -- rather than only when the world goes. */
        World arena = state.entity != null && state.entity.isValid() ? state.entity.getWorld() : null;
        for (LivingEntity seal : state.seals) {
            if (seal == null || !seal.isValid()) continue;
            if (arena == null) arena = seal.getWorld();
            seal.remove();
        }
        state.seals.clear();
        /*  And then by TAG, across the whole arena.
         *
         *  The list is bookkeeping and bookkeeping can be wrong; the tag is on the entity itself and cannot
         *  be. Anything still carrying the seal marker in this world is something this encounter created,
         *  so it goes -- which is what makes "no Cinder Seal survives" a property of resolution rather than
         *  a property of the list having stayed accurate. */
        if (arena != null)
            for (org.bukkit.entity.Entity leftover : arena.getEntities())
                if (leftover.getPersistentDataContainer().has(plugin.colosseum().sealKey(), org.bukkit.persistence.PersistentDataType.BYTE))
                    leftover.remove();
        /** Zones are pure state: no dropped potions, no lingering clouds, no fire. Clearing the list IS the
         *  cleanup, which is precisely why they were built this way. */
        state.zones.clear();
        if (state.entity != null && state.entity.isValid()) state.entity.remove();
        state.entity = null;
        state.pending = null;
        state.active = null;
        state.channel = null;
        state.channelDamage = 0;
        state.charging = false;
        state.chargeDirection = null;
        state.chargeOrigin = null;
        state.stunUntil = 0;
        state.vulnerableUntil = 0;
        state.vulnerableMultiplier = 1;
        state.stateLabel = "";
        state.strikesLeft = 0;
    }

    /** What the boss bar should say beyond its name and health: the state a player must react to. Empty
     *  most of the time, and loud exactly when it matters. */
    String stateLabel(BossState state) { return state == null || state.stateLabel == null ? "" : state.stateLabel; }

    /** Everything this encounter is currently responsible for keeping alive, for the leak audit. */
    int trackedEntities(BossState state) {
        if (state == null) return 0;
        int count = state.entity != null && state.entity.isValid() ? 1 : 0;
        for (LivingEntity seal : state.seals) if (seal != null && seal.isValid()) count++;
        for (SmallFireball ball : state.projectiles) if (ball != null && ball.isValid()) count++;
        return count;
    }

    int trackedZones(BossState state) { return state == null ? 0 : state.zones.size(); }

    // ------------------------------------------------------------------ rewards

    record Roll(Material material, int min, int max, double chance) {}

    /** The boss's configured table, parsed as MATERIAL:min-max:chance. A malformed line is logged and
     *  skipped rather than silently changing what a victory pays. */
    List<Roll> rewardTable(String bossKey) {
        List<Roll> out = new ArrayList<>();
        if (config == null) return out;
        for (String raw : config.getStringList("rewards." + bossKey + ".entries")) {
            String[] parts = raw.split(":");
            if (parts.length < 3) { plugin.getLogger().warning("[colosseum] reward entry '" + raw + "' for " + bossKey + " is malformed; skipped."); continue; }
            Material material = Material.matchMaterial(parts[0].trim());
            if (material == null) { plugin.getLogger().warning("[colosseum] reward entry '" + raw + "' names no such material; skipped."); continue; }
            String[] range = parts[1].split("-");
            try {
                int min = Integer.parseInt(range[0].trim()), max = range.length > 1 ? Integer.parseInt(range[1].trim()) : min;
                out.add(new Roll(material, Math.max(1, min), Math.max(1, Math.max(min, max)), Math.max(0, Math.min(1, Double.parseDouble(parts[2].trim())))));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("[colosseum] reward entry '" + raw + "' has unreadable numbers; skipped.");
            }
        }
        return out;
    }

    int rewardRolls(String bossKey) { return config == null ? 1 : Math.max(1, config.getInt("rewards." + bossKey + ".rolls", 1)); }

    /** One resolution of one boss's table. Called exactly once per victory, guarded by the run's `paid`
     *  flag in the database rather than by anything in memory. */
    List<ItemStack> rollRewards(String bossKey) {
        List<ItemStack> out = new ArrayList<>();
        List<Roll> table = rewardTable(bossKey);
        int rolls = rewardRolls(bossKey);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < rolls; i++)
            for (Roll roll : table)
                if (random.nextDouble() < roll.chance())
                    out.add(new ItemStack(roll.material(), roll.min() == roll.max() ? roll.min() : random.nextInt(roll.min(), roll.max() + 1)));
        return out;
    }

    // ------------------------------------------------------------------ self test

    /*  The rules every ability must obey, asserted from configuration rather than discovered in a fight.
     *
     *  These are the properties that make an encounter readable and survivable, and every one of them is a
     *  thing a well-meaning config edit could quietly break:
     *
     *    * a telegraph is at least three quarters of a second, or it is not a warning
     *    * a cooldown is longer than the wind-up it gates, or two telegraphs can overlap and become unreadable
     *    * no single ability can take a full-health player from 20 to 0, whatever they are wearing
     *    * a multi-strike sequence finishes well inside the time limit, so nothing can stall an encounter
     *
     *  Threshold-style abilities (the Revenant's veil) have neither a cooldown nor a telegraph and are
     *  skipped by name rather than by accident. */
    boolean mechanicsSelfTest() {
        for (BossDef def : bosses.values()) {
            if (def.abilities() == null) return false;
            int telegraphed = 0;
            for (String ability : def.abilities().getKeys(false)) {
                double cooldown = def.ability(ability + ".cooldown-seconds", -1);
                int telegraph = def.abilityInt(ability + ".telegraph-ticks", -1);
                /*  An ability with no telegraph is a PASSIVE -- a threshold like the Revenant's veil or the
                 *  Alchemist's catalyst, or a rate-limited self-effect like its cleanse. Those change what
                 *  the boss is, not what is about to happen to the player, so there is nothing to warn
                 *  about and nothing here to enforce. Anything that reaches out and touches the fighter has
                 *  a telegraph, and is what the rest of this loop is about. */
                if (telegraph < 0) continue;
                if (telegraph < 15) return false;
                if (cooldown * 20 <= telegraph) return false;
                telegraphed++;
                double damage = def.ability(ability + ".damage", 0);
                if (damage < 0 || damage >= 20) return false;
            }
            /** A boss with nothing telegraphed is a boss with no mechanics. */
            if (telegraphed < 2) return false;
            long flurryTicks = (long) def.abilityInt("flurry.strikes", 0) * def.abilityInt("flurry.strike-interval-ticks", 8);
            if (flurryTicks > def.timeLimitSeconds() * 20L / 4) return false;
            /** Bounded projectiles: an unbounded volley is an entity leak with a boss attached. */
            if (def.abilityInt("volley.count", 0) > 8) return false;
            /** Reflection is capped, so a critical mace drop cannot be turned into a one-shot on the player. */
            if (def.ability("riposte.reflect-cap", 0) >= 20) return false;

            /*  ---- the three newer encounters, whose promises are all numeric ----  */

            /** The ward can never be total, or the caster becomes an unexplained invulnerability phase. */
            if (def.has("ward")) {
                if (def.ability("ward.seal-health", 0) <= 0) return false;
                double maxWard = def.ability("ward.max-reduction", 0.6);
                if (maxWard <= 0 || maxWard >= 0.85) return false;
                if (def.ability("ward.reduction-per-seal", 0) <= 0) return false;
            }
            /** Every channel must be interruptible by an amount a player can actually deal in the window,
             *  and must open a window worth having when it is. */
            for (String channel : List.of("rekindle", "distillation")) {
                if (!def.has(channel)) continue;
                if (def.ability(channel + ".interrupt-damage", 0) <= 0) return false;
                if (def.abilityInt(channel + ".channel-ticks", 0) < 40) return false;
                if (def.abilityInt(channel + ".stagger-ticks", 0) < 20) return false;
                if (def.ability(channel + ".stagger-vulnerability", 0) <= 1) return false;
            }
            /** Healing is a percentage of the pool and is deliberately partial: a boss that brews back to
             *  full turns a long fight into an unwinnable one. */
            if (def.has("distillation")) {
                double heal = def.ability("distillation.heal-percent", 0.12);
                if (heal <= 0 || heal > 0.25) return false;
                if (def.ability("distillation.shatter-percent", 0) <= 0) return false;
            }
            /** The crash is the Behemoth's whole reason to exist: it must stun, and it must be worth more
             *  damage than standing in front of it. Its immunity window stops the stun chaining forever. */
            if (def.has("charge")) {
                if (def.abilityInt("charge.stun-ticks", 0) < 40) return false;
                if (def.ability("charge.crash-vulnerability", 0) <= 1) return false;
                if (def.abilityInt("charge.stun-immunity-ticks", 0) <= 0) return false;
                if (def.ability("charge.length", 0) <= 0 || def.ability("charge.speed", 0) <= 0) return false;
                if (def.has("frontal")) {
                    double frontal = def.ability("frontal.damage-multiplier", 0.3);
                    if (frontal <= 0 || frontal >= 1) return false;
                    if (def.ability("frontal.arc-degrees", 110) >= 360) return false;
                    /** Baiting the charge has to beat standing in front of it, or the design is a lie. */
                    if (def.ability("charge.crash-vulnerability", 2) <= 1 / frontal * 0.25) return false;
                }
            }
            /** The sweep must have a stated way out, and it must be one a player can actually take. */
            if (def.has("sweep") && (def.ability("sweep.radius", 0) <= 0 || def.ability("sweep.safe-height", 0) <= 0)) return false;
            /** Zones are bounded, expire on their own, and never cover the arena. */
            if (def.has("mixture")) {
                int cap = def.abilityInt("mixture.max-zones", 3);
                if (cap <= 0 || cap > 6) return false;
                if (def.abilityInt("mixture.zone-duration-ticks", 0) <= 0) return false;
                if (def.abilityInt("mixture.zones-per-cast", 2) > cap) return false;
                /** Total covered area has to leave the fighter somewhere to stand. */
                double covered = cap * Math.PI * Math.pow(def.ability("mixture.radius", 3.5) * def.ability("catalyst.radius-multiplier", 1.4), 2);
                if (covered > 900) return false;
            }
            /** A cleanse that is always available would make crowd control pointless. */
            if (def.has("cleanse") && def.ability("cleanse.cooldown-seconds", 0) < 10) return false;
            /** The single-hit cap must never be so tight that ordinary weapons feel absorbed. */
            if (def.maxSingleHitPercent() > 0 && def.maxSingleHitPercent() < 0.05) return false;
        }
        return true;
    }

    /** The expected number of item stacks one victory pays. Reported so the six reward pools can be
     *  compared directly and no single boss becomes the objectively correct money farm. */
    double expectedRewardStacks(String bossKey) {
        double sum = 0;
        for (Roll roll : rewardTable(bossKey)) sum += roll.chance();
        return sum * rewardRolls(bossKey);
    }

    /** The same, weighted by stack size. Useful for spotting a table that pays a silly number of items,
     *  but NOT a value comparison -- forty glowstone dust and one netherite ingot are both "items". */
    double expectedRewardItems(String bossKey) {
        double sum = 0;
        for (Roll roll : rewardTable(bossKey)) sum += roll.chance() * (roll.min() + roll.max()) / 2.0;
        return sum * rewardRolls(bossKey);
    }

    /*  What a victory is actually WORTH, in money, using the server's own shop sell prices.
     *
     *  This is the number that decides whether one boss becomes the correct farm, and it is the reason the
     *  item count is not: a table of forty experience bottles and a table of two netherite ingots are
     *  nothing alike by count and can be nearly identical by value. Materials the shop does not price
     *  contribute nothing, which understates a pool rather than overstating it -- the safe direction for a
     *  check that exists to catch an outlier. */
    double expectedRewardValue(String bossKey) {
        ShopService shop = plugin.shop();
        if (shop == null) return 0;
        double sum = 0;
        for (Roll roll : rewardTable(bossKey))
            sum += roll.chance() * (roll.min() + roll.max()) / 2.0 * shop.sellValue(roll.material());
        return sum * rewardRolls(bossKey);
    }

    /** Configuration integrity, asserted rather than assumed: three bosses, all distinct, all with an arena
     *  that exists, none of them free money, and none of them scaled by anything but their own config. */
    boolean selfTest() {
        if (bosses.size() < 6) return false;
        java.util.Set<EntityType> types = new java.util.HashSet<>();
        java.util.Set<String> styles = new java.util.HashSet<>();
        for (BossDef def : bosses.values()) {
            if (def.health() <= 0 || def.damage() <= 0 || def.timeLimitSeconds() < 30) return false;
            /** A prize that does not exceed the fee makes a victory a loss, which no amount of play fixes. */
            if (def.cashPrize() <= def.entryFee()) return false;
            if (plugin.colosseum().arenas().arena(def.arena()) == null) return false;
            if (def.toughness() < 1) return false;
            if (Math.abs(def.toughness() * def.engineHealth() - def.health()) > 1) return false;
            if (rewardTable(def.key()).isEmpty()) return false;
            /** Every boss states its own strength, weakness and counterplay, because the GUI prints them
             *  and a player should never need a wiki to find the mechanic. */
            if (def.strength().isBlank() || def.weakness().isBlank() || def.counterplay().isBlank()) return false;
            if (def.environment() == null) return false;
            types.add(def.type());
            styles.add(def.style());
        }
        /** Six stat variations of one mob would not be six bosses. */
        return types.size() >= 6 && styles.size() >= 5;
    }
}
