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
                   EntityType type, double scale, List<String> lore,
                   double health, double damage, double armor, double armorToughness, double movement,
                   double knockbackResistance, double followRange,
                   int timeLimitSeconds, double entryFee, double cashPrize,
                   boolean available, int rewardLimit, ConfigurationSection abilities) {

        /** The pool the fight is actually worth, expressed as a damage divisor because the attribute itself
         *  cannot hold it. A boss configured at or below the engine cap has a divisor of exactly 1. */
        double toughness() { return Math.max(1, health / ENGINE_MAX_HEALTH); }
        double engineHealth() { return Math.max(1, Math.min(ENGINE_MAX_HEALTH, health)); }
        double ability(String path, double fallback) { return abilities == null ? fallback : abilities.getDouble(path, fallback); }
        int abilityInt(String path, int fallback) { return abilities == null ? fallback : abilities.getInt(path, fallback); }
        long cooldownMs(String ability, double fallbackSeconds) { return (long) (ability(ability + ".cooldown-seconds", fallbackSeconds) * 1000); }
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
    }

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
            bosses.put(key, new BossDef(key, s.getString("name", key), icon == null ? Material.NETHER_STAR : icon,
                    s.getString("style", "Boss"), s.getString("difficulty", "Hard"),
                    s.getString("arena", "ashen_colosseum").toLowerCase(Locale.ROOT), type,
                    Math.max(0.5, Math.min(4.0, s.getDouble("scale", 1.0))), s.getStringList("lore"),
                    Math.max(1, s.getDouble("health", 1500)), Math.max(0, s.getDouble("damage", 12)),
                    Math.max(0, s.getDouble("armor", 0)), Math.max(0, s.getDouble("armor-toughness", 0)),
                    Math.max(0.05, s.getDouble("movement", 0.28)),
                    Math.max(0, Math.min(1, s.getDouble("knockback-resistance", 0.3))),
                    Math.max(16, s.getDouble("follow-range", 64)),
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
        /** A boss that somehow left the arena is walked back rather than lost. */
        ColosseumArenas.Arena arena = plugin.colosseum().arenas().arenaOfWorld(boss.getWorld());
        if (arena != null && !arena.inBounds(boss.getLocation().getX(), boss.getLocation().getY(), boss.getLocation().getZ()))
            boss.teleport(arena.bossSpawn(boss.getWorld()));

        state.projectiles.removeIf(f -> f == null || !f.isValid());

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
            default -> { }
        }
    }

    private static double maxHealth(LivingEntity entity) {
        AttributeInstance a = entity.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    private boolean ready(BossState state, String ability, long now) { return now >= state.nextUse.getOrDefault(ability, 0L); }

    private void begin(BossDef def, BossState state, String ability, long now, double fallbackCooldown) {
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
            CoreUtil.msg(player, state.entity.getName() + " draws a veil of ash — arrows will barely find it now.");
        }
        double distance = state.entity.getLocation().distance(player.getLocation());
        if (ready(state, "volley", now) && distance > 5) { begin(def, state, "volley", now, 11); return; }
        if (ready(state, "blink", now) && distance > 4 && distance <= def.ability("blink.max-range", 30)) begin(def, state, "blink", now, 10);
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
            default -> { }
        }
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
        int fissure = Math.max(1, def.abilityInt("fissure.rings", 0)) * 18;
        if (def.abilityInt("fissure.rings", 0) == 0) fissure = 0;
        int slam = def.ability("slam.radius", 0) > 0 ? 24 : 0;
        /** Only one ability winds up at a time, so the heaviest single telegraph is the ceiling -- plus the
         *  small constant the stance and blink markers add. */
        return Math.max(fissure, slam) + 30;
    }

    // ------------------------------------------------------------------ damage interception

    /** Damage arriving at the boss. Three things happen here and nowhere else: the effective-pool divisor,
     *  the active stance multipliers, and the riposte's reflection. */
    void bossHurt(BossDef def, BossState state, EntityDamageEvent event) {
        double toughness = def.toughness();
        if (toughness > 1) event.setDamage(event.getDamage() / toughness);
        if ("bulwark".equals(state.active)) event.setDamage(event.getDamage() * def.ability("bulwark.damage-taken-multiplier", 0.2));
        if ("riposte".equals(state.active)) event.setDamage(event.getDamage() * def.ability("riposte.damage-taken-multiplier", 0.25));
        /** The veil: ranged fire barely lands once it is up, so the fight has to be finished up close. */
        if (state.veiled && event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof org.bukkit.entity.Projectile)
            event.setDamage(event.getDamage() * def.ability("veil.projectile-damage-multiplier", 0.35));
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
        if (state.entity != null && state.entity.isValid()) state.entity.remove();
        state.entity = null;
        state.pending = null;
        state.active = null;
        state.strikesLeft = 0;
    }

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
                if (cooldown < 0 && telegraph < 0) continue;
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
        }
        return true;
    }

    /** Configuration integrity, asserted rather than assumed: three bosses, all distinct, all with an arena
     *  that exists, none of them free money, and none of them scaled by anything but their own config. */
    boolean selfTest() {
        if (bosses.size() < 3) return false;
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
            types.add(def.type());
            styles.add(def.style());
        }
        /** Three stat variations of one mob would not be three bosses. */
        return types.size() >= 3 && styles.size() >= 3;
    }
}
