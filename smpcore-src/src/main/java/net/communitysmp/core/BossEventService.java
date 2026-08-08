package net.communitysmp.core;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

final class BossEventService {
    enum EventType { RESOURCE_RUSH, ELITE_HUNT, TREASURE, KOTH, WORLD_BOSS, HUNT }
    enum EventTier { MICRO, MAJOR, RARE }
    enum Origin { NATURAL, PLAYER_SUMMONED, ADMIN_SUMMONED }
    enum WorldBossKind { ASHEN_KNIGHT, IRON_GOLEM, PIGLIN_BRUTE }
    private final SMPCore plugin; private final Database db; private final FactionService factions; private final RelicService relics;
    private final NamespacedKey tierKey, spawnerKey, phaseKey, treasureKey, abilityKey, curerKey, eventEliteKey, burstKey, originKey, summonKey, eliteSpawnedAtKey, sharedBaseHealthKey, sharedActiveCountKey, legendaryLootKey, movementScaledKey, summonKindKey;
    private YamlConfiguration bosses, events;
    private final Map<UUID, Map<String, Double>> damage = new HashMap<>();
    private final Map<UUID, Map<String, Long>> lastContribution = new HashMap<>();
    private final Map<String, Deque<Long>> farmKills = new HashMap<>();
    private final Map<UUID, Long> abilityCooldown = new HashMap<>();
    private final Map<UUID, Long> catchupCooldown = new HashMap<>(), lastEngaged = new HashMap<>(), healCooldown = new HashMap<>(), bossMechanicAt = new HashMap<>(), blockedSince = new HashMap<>(), lastMobHit = new HashMap<>(), breakoutCooldown = new HashMap<>();
    private final Map<UUID, Long> lastNearbyAt = new HashMap<>(), specialAbilityAt = new HashMap<>(), exposedUntil = new HashMap<>();
    private final Set<UUID> enraged = new HashSet<>();
    private final Map<UUID, Integer> enrageStageApplied = new HashMap<>();
    /** When a player first actually damaged this boss — the basis for soft-enrage timing (see
     *  worldBossSoftEnrage), replacing spawn time so enrage tracks fight duration rather than boss age. */
    private final Map<UUID, Long> bossFirstEngagedAt = new HashMap<>();
    /** Last moment an eligible player was within despawn range of this elite. Elites expire on continuous
     *  abandonment (see expireElite), not on absolute age, so this resets whenever someone is around. */
    private final Map<UUID, Long> eliteLastPlayerNear = new HashMap<>();
    /** Target-commitment window and out-of-range grace, per boss (see onWorldBossTarget/worldBossTargetTick). */
    private final Map<UUID, Long> bossTargetSince = new HashMap<>();
    private final Map<UUID, Long> bossTargetOutOfRangeSince = new HashMap<>();
    /** First tick at which an engaged target became genuinely unpathable, and the last breakout/leap time. */
    private final Map<UUID, Long> bossUnreachableSince = new HashMap<>();
    private final Map<UUID, Long> bossLeapCooldown = new HashMap<>();
    /** Throttles land re-pathing: calling moveTo() every tick restarts the route mid-execution and is
     *  itself what makes a boss circle instead of committing to a direction. */
    private final Map<UUID, Long> bossRepathAt = new HashMap<>();
    /** End time for a player-summoned boss running ALONGSIDE a natural event (see startEvent's standalone
     *  branch). Zero when no standalone encounter is active. Kept separate from eventEnds so the concurrent
     *  natural event's own duration is never affected. */
    private long standaloneBossEnds;
    private final Map<UUID, String> lastTarget = new HashMap<>();
    private final Map<UUID, Integer> rangedHits = new HashMap<>();
    private final Map<UUID, BossBar> healthBars = new HashMap<>();
    private final Map<UUID, Set<UUID>> barViewers = new HashMap<>();
    private final Set<UUID> eliteIds = new HashSet<>(), sharedBossIds = new HashSet<>(); private final Set<String> eventParticipants = new HashSet<>();
    private volatile EventType eventType; private EventTier eventTier=EventTier.MICRO;private Location eventCenter; private volatile long eventEnds; private Origin eventOrigin=Origin.NATURAL;private long activeTierNextDelay;
    private final EnumMap<EventTier,Long> eventRemaining=new EnumMap<>(EventTier.class);
    private final EnumMap<EventTier,EventType> scheduledEvents=new EnumMap<>(EventTier.class);
    private final Deque<EventType> recentNaturalEvents=new ArrayDeque<>();
    private long lastEventTimerTick,lastTimerPersist;
    private final Map<String, Integer> eventScores = new HashMap<>(); private final Map<String, String> scoreNames = new HashMap<>();
    /** Money earned directly from the CURRENT event's own reward mechanic, per player — deliberately separate
     *  from every other income source (mob kills, shop sales, auctions, loans, etc.) which never touch this
     *  map. Cleared on every new event start, reported to each participant once the event ends. */
    private final Map<String, Double> eventEarnings = new HashMap<>();
    private UUID worldBossId; private long bossSpawnedAt, nextHintAt; private int hintStage,worldBossActiveCount=1;private double worldBossBaseHealth;private Origin worldBossOrigin=Origin.NATURAL;private WorldBossKind worldBossKind=WorldBossKind.ASHEN_KNIGHT;
    private World forcedBossWorld;private int forcedBossChunkX,forcedBossChunkZ;private boolean forcedBossChunkSet;
    private BukkitTask ticker, visuals;

    BossEventService(SMPCore plugin, FactionService factions, RelicService relics) {
        this.plugin = plugin; this.db = plugin.db(); this.factions = factions; this.relics = relics;
        tierKey = new NamespacedKey(plugin, "elite_tier"); spawnerKey = new NamespacedKey(plugin, "spawner_mob"); phaseKey = new NamespacedKey(plugin, "boss_phase"); treasureKey = new NamespacedKey(plugin, "event_treasure"); abilityKey = new NamespacedKey(plugin, "elite_ability"); curerKey = new NamespacedKey(plugin, "zombie_curer"); eventEliteKey = new NamespacedKey(plugin, "event_elite"); burstKey = new NamespacedKey(plugin, "elite_burst");originKey=new NamespacedKey(plugin,"boss_origin");summonKey=new NamespacedKey(plugin,"sealed_omen");eliteSpawnedAtKey=new NamespacedKey(plugin,"elite_spawned_at");sharedBaseHealthKey=new NamespacedKey(plugin,"shared_boss_base_health");sharedActiveCountKey=new NamespacedKey(plugin,"shared_boss_active_count");legendaryLootKey=new NamespacedKey(plugin,"legendary_loot");movementScaledKey=new NamespacedKey(plugin,"elite_movement_scaled");summonKindKey=new NamespacedKey(plugin,"summon_kind");
        reload(); loadEvent(); restoreBoss(); startTasks();
    }
    void reload() { bosses = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "bosses.yml")); events = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "events.yml")); }
    boolean isPeacefulExempt(LivingEntity entity){String tier=entity.getPersistentDataContainer().get(tierKey,PersistentDataType.STRING);return entity instanceof Boss||entity instanceof Warden||isWorldBossTier(tier)||"miniboss".equals(tier);}
    /** Vanilla's IronGolemAttackHostilesGoal lets the Warded Colossus break off to fight nearby
     *  zombies/hostile mobs entirely on its own initiative — as a world boss that's an exploitable
     *  distraction (kite hostile mobs at it to stall the fight, or let it grind "free" kills instead of ever
     *  engaging the player). Mob#setTarget() (used elsewhere, e.g. retargeting onto whoever last hit it)
     *  never fires this event, so cancelling non-player targets here only ever blocks the AI's own automatic
     *  mob-targeting — it never interferes with that player-combat retargeting. */
    /** Elite Endermen spawn holding a block (amethyst, or a shulker box at legendary) purely as a visual
     *  signature — but vanilla Enderman AI will happily PLACE that block, and they were doing it on top of
     *  player enderman farms, sealing the spawning platform. Cancelling EntityChangeBlockEvent for tagged
     *  elites stops both placing and picking up, so they keep the block they were given, can never alter
     *  the world with it, and simply drop it on death (see thematicLoot). Ordinary wild Endermen are
     *  untouched and still behave exactly like vanilla. */
    void onEliteBlockChange(org.bukkit.event.entity.EntityChangeBlockEvent e){
        if(!(e.getEntity() instanceof Enderman enderman))return;
        if(enderman.getPersistentDataContainer().get(tierKey,PersistentDataType.STRING)==null)return;
        e.setCancelled(true);
    }
    void onWorldBossTarget(EntityTargetLivingEntityEvent e){
        if(!(e.getEntity() instanceof LivingEntity living))return;
        String tier=living.getPersistentDataContainer().get(tierKey,PersistentDataType.STRING);
        if(!isWorldBossTier(tier))return;
        /** No world boss ever picks its own non-player target: hostile mobs are a free distraction that can
         *  be kited in to stall a fight (the Colossus was the worst offender via vanilla's
         *  IronGolemAttackHostilesGoal, but the rule is sound for all three). */
        if(!(e.getTarget() instanceof Player player)){e.setCancelled(true);return;}
        /** Out-of-range acquisition is refused outright, so a boss can't latch onto someone who merely
         *  wandered near the arena, and — combined with the stability window in worldBossTargetTick — it
         *  can't be yanked between distant players either. */
        double range=bosses.getDouble("world-boss-targeting.range",50);
        if(!player.getWorld().equals(living.getWorld())||player.getLocation().distanceSquared(living.getLocation())>range*range||!validBossTarget(player)){e.setCancelled(true);return;}
        UUID id=living.getUniqueId();
        Long locked=bossTargetSince.get(id);
        Player current=living instanceof Mob mob&&mob.getTarget() instanceof Player p?p:null;
        long stability=(long)(bosses.getDouble("world-boss-targeting.target-stability-seconds",4)*1000);
        /** Commit to the current target for a short window rather than flipping on every incoming hit —
         *  the old behavior (retarget on any damage, with no range limit) made the boss visibly indecisive
         *  in group fights while still never truly losing anyone. */
        if(current!=null&&!current.equals(player)&&locked!=null&&System.currentTimeMillis()-locked<stability&&validBossTarget(current)
                &&current.getLocation().distanceSquared(living.getLocation())<=range*range){e.setCancelled(true);return;}
        bossTargetSince.put(id,System.currentTimeMillis());
    }
    /** Spectators, dead players and staff in creative/privileged mode are never valid boss targets. */
    private boolean validBossTarget(Player player){
        return player!=null&&player.isOnline()&&!player.isDead()&&player.getGameMode()!=GameMode.SPECTATOR
                &&player.getGameMode()!=GameMode.CREATIVE&&!plugin.privileged(player);
    }
    /** Keeps targeting honest every visual tick: drops a target that has been out of range (or invalid)
     *  for lose-target-seconds and re-acquires the nearest valid player, so a boss neither holds a
     *  permanent lock on someone who ran away nor stands idle while a valid fighter is right next to it. */
    private void worldBossTargetTick(LivingEntity boss){
        if(!(boss instanceof Mob mob))return;
        double range=bosses.getDouble("world-boss-targeting.range",50),rangeSq=range*range;
        UUID id=boss.getUniqueId();long now=System.currentTimeMillis();
        Player current=mob.getTarget() instanceof Player p?p:null;
        if(current!=null&&validBossTarget(current)&&current.getWorld().equals(boss.getWorld())&&current.getLocation().distanceSquared(boss.getLocation())<=rangeSq){
            bossTargetOutOfRangeSince.remove(id);return;
        }
        if(current!=null){
            long since=bossTargetOutOfRangeSince.computeIfAbsent(id,key->now);
            if(now-since<(long)(bosses.getDouble("world-boss-targeting.lose-target-seconds",3)*1000))return;
        }
        bossTargetOutOfRangeSince.remove(id);
        Player nearest=null;double best=rangeSq;
        for(Player candidate:boss.getWorld().getPlayers()){
            if(!validBossTarget(candidate))continue;
            double d=candidate.getLocation().distanceSquared(boss.getLocation());
            if(d<=best){best=d;nearest=candidate;}
        }
        if(nearest!=null){mob.setTarget(nearest);bossTargetSince.put(id,now);}
        else if(current!=null){mob.setTarget(null);bossTargetSince.remove(id);}
    }
    void shutdown() { persistWorldBoss(); persistEvent();persistEventTimers(); if (ticker != null) ticker.cancel(); if (visuals != null) visuals.cancel(); for(var entry:barViewers.entrySet())for(UUID viewer:entry.getValue()){Player player=plugin.getServer().getPlayer(viewer);BossBar bar=healthBars.get(entry.getKey());if(player!=null&&bar!=null)player.hideBossBar(bar);}healthBars.clear();barViewers.clear(); }

    void onSpawn(CreatureSpawnEvent e) {
        LivingEntity mob = e.getEntity(); String reason = e.getSpawnReason().name();
        if (reason.contains("SPAWNER")) { mob.getPersistentDataContainer().set(spawnerKey, PersistentDataType.BYTE, (byte) 1); return; }
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || !(mob instanceof Monster) || mob instanceof Warden) return;
        double dimension=mob.getWorld().getEnvironment()==World.Environment.NETHER?bosses.getDouble("dimension-content.nether.elite-chance-multiplier",1.45):mob.getWorld().getEnvironment()==World.Environment.THE_END?bosses.getDouble("dimension-content.end.elite-chance-multiplier",1.8):1;double roll = Math.random(); String tier = null;
        double uncommon=bosses.getDouble("natural-elites.uncommon-chance",.0025)*dimension;
        double rare=bosses.getDouble("natural-elites.rare-chance",.0004)*dimension;
        double epic=bosses.getDouble("natural-elites.epic-chance",.00006)*dimension;
        double legendary=bosses.getDouble("natural-elites.legendary-chance",.0000015)*dimension;
        if(roll<legendary)tier="legendary";else if(roll<legendary+epic)tier="epic";else if(roll<legendary+epic+rare)tier="rare";else if(roll<legendary+epic+rare+uncommon)tier="uncommon";
        if (tier != null) {makeElite(mob, tier);db.recordEliteSpawn(tier,true);}
    }

    private void makeElite(LivingEntity mob, String tier) {
        String ability = chooseAbility(mob, tier); PersistentDataContainer pdc = mob.getPersistentDataContainer(); pdc.set(tierKey, PersistentDataType.STRING, tier); pdc.set(abilityKey, PersistentDataType.STRING, ability); pdc.set(burstKey, PersistentDataType.INTEGER, 0);pdc.set(phaseKey,PersistentDataType.INTEGER,0);pdc.set(eliteSpawnedAtKey,PersistentDataType.LONG,System.currentTimeMillis());
        double health = clampHealth(Math.min(bosses.getDouble("elite-health-cap", 1000), mob.getAttribute(Attribute.MAX_HEALTH).getBaseValue() * bosses.getDouble("tiers." + tier + ".health-multiplier"))); mob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health); mob.setHealth(health);
        if (mob.getAttribute(Attribute.ATTACK_DAMAGE) != null) mob.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(mob.getAttribute(Attribute.ATTACK_DAMAGE).getBaseValue() * bosses.getDouble("tiers." + tier + ".damage-multiplier"));
        scaleEliteMovement(mob,tier);
        mob.setRemoveWhenFarAway(false);
        NamedTextColor color=switch(tier){case"legendary"->NamedTextColor.DARK_PURPLE;case"epic"->NamedTextColor.GOLD;case"miniboss"->NamedTextColor.DARK_RED;case"rare"->NamedTextColor.LIGHT_PURPLE;default->NamedTextColor.AQUA;};
        mob.customName(Component.text((tier.equals("miniboss") ? "MINIBOSS" : tier.toUpperCase(Locale.ROOT)) + " • " + CoreUtil.pretty(ability) + " " + CoreUtil.pretty(mob.getType().name()), color)); mob.setCustomNameVisible(true); mob.setGlowing(true); eliteIds.add(mob.getUniqueId());
        registerHealthBar(mob,tier);
        if(tier.equals("miniboss")){pdc.set(sharedBaseHealthKey,PersistentDataType.DOUBLE,health);pdc.set(sharedActiveCountKey,PersistentDataType.INTEGER,1);sharedBossIds.add(mob.getUniqueId());}
        equipElite(mob, tier); if (mob instanceof Creeper creeper && !tier.equals("uncommon")) { creeper.setPowered(true); creeper.setExplosionRadius(tier.equals("legendary")||tier.equals("miniboss") ? 4 : 3); } if (mob instanceof Enderman enderman) {Material carried=tier.equals("legendary")?randomShulkerMaterial():Material.AMETHYST_BLOCK;enderman.setCarriedBlock(carried.createBlockData());if(tier.equals("legendary"))pdc.set(legendaryLootKey,PersistentDataType.STRING,carried.name());}
        if (tier.equals("miniboss")) broadcastNotice(Component.text("⚔ A " + CoreUtil.pretty(ability) + " miniboss has emerged in a " + CoreUtil.pretty(mob.getLocation().getBlock().getBiome().getKey().getKey()) + " biome.", NamedTextColor.RED));
        if (tier.equals("legendary")) broadcastNotice(Component.text("☠ Something legendary has awakened in the " + CoreUtil.pretty(mob.getLocation().getBlock().getBiome().getKey().getKey()) + " wilderness...", NamedTextColor.DARK_PURPLE));
    }
    private void scaleEliteMovement(LivingEntity mob,String tier){PersistentDataContainer pdc=mob.getPersistentDataContainer();if(pdc.has(movementScaledKey))return;var attribute=mob.getAttribute(Attribute.MOVEMENT_SPEED);if(attribute!=null){double fallback=switch(tier){case"rare"->1.08;case"epic"->1.18;case"legendary","miniboss"->1.28;default->1.03;};attribute.setBaseValue(attribute.getBaseValue()*bosses.getDouble("tiers."+tier+".movement-multiplier",fallback));}pdc.set(movementScaledKey,PersistentDataType.BYTE,(byte)1);}
    private String chooseAbility(LivingEntity mob, String tier) { if(mob.getWorld().getEnvironment()==World.Environment.NETHER&&Math.random()<.55)return "INFERNAL_RIFT";if(mob.getWorld().getEnvironment()==World.Environment.THE_END&&Math.random()<.65)return "VOID_TETHER";if (mob instanceof Creeper) return "VOLATILE"; if (mob instanceof Spider) return "VENOMOUS"; if (mob instanceof Enderman) return "PHASEWALKER"; if (mob instanceof Skeleton) return Math.random() < .5 ? "FROSTBITE" : "STORMCALLER"; String[] basic = tier.equals("uncommon") ? new String[]{"FLAMEBOUND", "BULWARK"} : new String[]{"FLAMEBOUND", "BULWARK", "VAMPIRIC", "SUMMONER"}; return basic[ThreadLocalRandom.current().nextInt(basic.length)]; }
    private void equipElite(LivingEntity mob, String tier) {
        EntityEquipment eq = mob.getEquipment(); if (eq == null) return;
        float chance=(float)bosses.getDouble("tiers."+tier+".equipment-drop-chance",tier.equals("legendary")?.72:tier.equals("epic")?.82:tier.equals("miniboss")?.85:tier.equals("rare")?.72:.58);
        int level=tier.equals("legendary")?4:tier.equals("epic")||tier.equals("miniboss")?3:tier.equals("rare")?2:1;
        if (mob instanceof AbstractSkeleton) { ItemStack bow = special(Material.BOW, tier + " Stormbow", Enchantment.POWER, level); eq.setItemInMainHand(bow); eq.setItemInMainHandDropChance(chance); }
        else if (mob instanceof Zombie || mob instanceof Piglin || mob instanceof Pillager) { Material weapon=tier.equals("legendary")?Material.NETHERITE_SWORD:tier.equals("uncommon")?Material.IRON_SWORD:Material.DIAMOND_SWORD; ItemStack sword = special(weapon, tier + " Ashen Fang", Enchantment.SHARPNESS, Math.min(4,level)); eq.setItemInMainHand(sword); eq.setItemInMainHandDropChance(chance); }
        if (mob instanceof Zombie || mob instanceof AbstractSkeleton || mob instanceof Piglin) {
            Material helmet=tier.equals("legendary")?Material.NETHERITE_HELMET:tier.equals("epic")||tier.equals("miniboss")?Material.DIAMOND_HELMET:tier.equals("rare")?Material.IRON_HELMET:Material.CHAINMAIL_HELMET;
            ItemStack armor=special(helmet,tier+" Hunter's Helm",Enchantment.PROTECTION,Math.min(3,level));eq.setHelmet(armor);eq.setHelmetDropChance(chance);
            if(tier.equals("epic")||tier.equals("legendary")){Material chest=tier.equals("legendary")?Material.NETHERITE_CHESTPLATE:Material.DIAMOND_CHESTPLATE;eq.setChestplate(special(chest,tier+" Warden's Plate",Enchantment.PROTECTION,Math.min(3,level)));eq.setChestplateDropChance(chance*.75f);}
        }
    }
    private ItemStack special(Material material, String name, Enchantment enchantment, int level) { ItemStack item = new ItemStack(material); item.addUnsafeEnchantment(enchantment, level); item.addUnsafeEnchantment(Enchantment.UNBREAKING, Math.min(3, level)); return item; }

    LivingEntity spawnElite(String tier, Location preferred) { World world = preferred!=null&&preferred.getWorld()!=null?preferred.getWorld():overworld(); if (world == null) return null; Location loc = preferred == null ? randomSafe(world, 400, 2500) : preferred; if (loc == null) loc = world.getSpawnLocation(); Class<? extends LivingEntity> type=eliteType(tier,world.getEnvironment());LivingEntity mob = world.spawn(loc, type, CreatureSpawnEvent.SpawnReason.CUSTOM); makeElite(mob, tier);db.recordEliteSpawn(tier,false); return mob; }
    private Class<? extends LivingEntity> eliteType(String tier,World.Environment environment){if(environment==World.Environment.THE_END)return Enderman.class;if(environment==World.Environment.NETHER)return tier.equals("legendary")||tier.equals("miniboss")?WitherSkeleton.class:Piglin.class;return switch(tier){case"legendary"->WitherSkeleton.class;case"epic","rare"->Skeleton.class;default->Zombie.class;};}
    private LivingEntity bossVictim(Entity entity){if(entity instanceof EnderDragonPart part)return part.getParent();return entity instanceof LivingEntity living?living:null;}
    private boolean isVanillaBoss(LivingEntity entity){List<String> types=bosses.getStringList("boss-participation.vanilla-types");if(types.isEmpty())types=List.of("ENDER_DRAGON","WITHER");return types.stream().anyMatch(type->type.equalsIgnoreCase(entity.getType().name()));}
    void rarityReport(CommandSender sender){CoreUtil.msg(sender,"Elite spawn telemetry (since this update):");Map<String,Database.EliteSpawnRow> counts=new HashMap<>();for(Database.EliteSpawnRow row:db.eliteSpawnCounts())counts.put(row.tier(),row);for(String tier:List.of("uncommon","rare","epic","legendary")){double chance=bosses.getDouble("natural-elites."+tier+"-chance",0);Database.EliteSpawnRow row=counts.get(tier);String odds=chance<=0?"disabled":"1 in "+Math.round(1/chance);CoreUtil.msg(sender,CoreUtil.pretty(tier)+": "+odds+" base | natural "+(row==null?0:row.natural())+" | custom "+(row==null?0:row.custom()));}CoreUtil.msg(sender,"Nether and End apply the configured dimension multiplier equally to every tier.");}
    void onEntitiesLoaded(Collection<Entity> entities) {
        for(Entity entity:entities){
            LivingEntity living=bossVictim(entity);if(living==null)continue;
            if(isVanillaBoss(living)){sharedBossIds.add(living.getUniqueId());ensureSharedBase(living);}
            if(!living.getPersistentDataContainer().has(tierKey))continue;
            eliteIds.add(living.getUniqueId());String tier=living.getPersistentDataContainer().get(tierKey,PersistentDataType.STRING);living.setGlowing(true);PotionEffect legacy=living.getPotionEffect(PotionEffectType.SPEED);if(legacy!=null&&legacy.getDuration()>20*60*20)living.removePotionEffect(PotionEffectType.SPEED);scaleEliteMovement(living,tier);
            if(!isWorldBossTier(tier)){living.setRemoveWhenFarAway(false);if(!living.getPersistentDataContainer().has(eliteSpawnedAtKey,PersistentDataType.LONG))living.getPersistentDataContainer().set(eliteSpawnedAtKey,PersistentDataType.LONG,System.currentTimeMillis());}
            if("miniboss".equals(tier)){sharedBossIds.add(living.getUniqueId());ensureSharedBase(living);}
            registerHealthBar(living,tier);
            if(living.getUniqueId().equals(worldBossId)){Map<String,Database.BossContribution> saved=db.bossContributions(worldBossId.toString());Map<String,Double> amounts=new HashMap<>();Map<String,Long> hits=new HashMap<>();saved.forEach((id,value)->{amounts.put(id,value.damage());hits.put(id,value.lastHit());});damage.putIfAbsent(worldBossId,amounts);lastContribution.putIfAbsent(worldBossId,hits);}
        }
    }
    void sanitizePlayerEffects(Player player){boolean cleared=false;for(PotionEffect effect:new ArrayList<>(player.getActivePotionEffects()))if(effect.getDuration()>20*60*20&&Set.of(PotionEffectType.SPEED,PotionEffectType.SLOWNESS,PotionEffectType.POISON,PotionEffectType.LEVITATION,PotionEffectType.DARKNESS,PotionEffectType.STRENGTH,PotionEffectType.RESISTANCE).contains(effect.getType())){player.removePotionEffect(effect.getType());cleared=true;}if(cleared)CoreUtil.msg(player,"An invalid lingering elite effect was safely cleared.");}

    LivingEntity spawnWorldBoss(Location preferred) { return spawnWorldBoss(preferred,eventType==EventType.WORLD_BOSS?eventOrigin:Origin.ADMIN_SUMMONED); }
    LivingEntity spawnWorldBoss(Location preferred,Origin origin) { return spawnWorldBoss(preferred,origin,randomWorldBossKind()); }
    LivingEntity spawnWorldBoss(Location preferred,Origin origin,WorldBossKind kind) {
        LivingEntity existing = worldBoss(); if (existing != null) return null; World world = worldFor(kind); if (world == null) return null; Location loc = preferred;
        String prefix = configPrefix(kind);
        if (loc == null) { int min = bosses.getInt(prefix+".spawn-radius-min", 1200), max = bosses.getInt(prefix+".spawn-radius-max", 4000); loc = randomSafe(world, min, max); }
        if(loc==null||!loc.getWorld().equals(world)||protectedEventLocation(loc))return null;
        LivingEntity boss = null;
        try {
            boss = spawnBossEntity(world, loc, kind); double rawHp = bosses.getDouble(prefix+".health", 18000); double hp = clampHealth(rawHp); boss.getAttribute(Attribute.MAX_HEALTH).setBaseValue(hp); boss.setHealth(hp); if(boss.getAttribute(Attribute.ATTACK_DAMAGE)!=null)boss.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(bosses.getDouble(prefix+".damage", 18)); if(kind==WorldBossKind.ASHEN_KNIGHT||kind==WorldBossKind.PIGLIN_BRUTE)boss.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
            if(boss.getAttribute(Attribute.FOLLOW_RANGE)!=null)boss.getAttribute(Attribute.FOLLOW_RANGE).setBaseValue(bosses.getDouble("world-boss-targeting.range",50));
            if((kind==WorldBossKind.ASHEN_KNIGHT||kind==WorldBossKind.PIGLIN_BRUTE)&&boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE)!=null)boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE).setBaseValue(boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getBaseValue()+0.25);
            boss.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, tierFor(kind)); boss.getPersistentDataContainer().set(abilityKey, PersistentDataType.STRING, signatureAbility(kind)); boss.getPersistentDataContainer().set(phaseKey, PersistentDataType.INTEGER, 0); boss.setRemoveWhenFarAway(false); boss.customName(Component.text("⚔ " + displayName(kind), colorFor(kind))); boss.setCustomNameVisible(true); boss.setGlowing(true);
            boss.getPersistentDataContainer().set(originKey,PersistentDataType.STRING,origin.name());
            registerHealthBar(boss,tierFor(kind));
            equipWorldBoss(boss,kind);
            playSummoningSequence(boss,kind);
            worldBossId = boss.getUniqueId();bossSpawnedAt = System.currentTimeMillis();hintStage = 0;nextHintAt = bossSpawnedAt + bosses.getLong(prefix+".first-hint-minutes", 10) * 60000L;worldBossOrigin=origin;worldBossBaseHealth=rawHp;worldBossActiveCount=1;worldBossKind=kind;eliteIds.add(worldBossId);damage.put(worldBossId, new HashMap<>());lastContribution.put(worldBossId,new HashMap<>());
            /** trackBossChunk() normally re-pins every visualTick (~1s), but that first tick is up to a full
             *  second away — long enough for a spawn chunk with zero nearby players (any Nether spawn, or an
             *  Overworld one far from everyone) to unload before it ever gets pinned once. Pin it immediately
             *  so there's no bootstrapping gap where the boss can fall out of tracking before it's even seen. */
            trackBossChunk(loc);
            persistWorldBoss();
        } catch (Throwable error) {
            plugin.getLogger().severe("World boss spawn failed safely and was cleaned up: "+error);
            if (boss != null) try { eliteIds.remove(boss.getUniqueId()); damage.remove(boss.getUniqueId()); lastContribution.remove(boss.getUniqueId()); db.deleteBossState(boss.getUniqueId().toString()); boss.remove(); } catch (Throwable ignored) {}
            worldBossId = null;
            return null;
        }
        broadcastWorldEvent("⚔ WORLD EVENT", awakenLine(kind), "Track the event for distance and direction. Last seen in a " + CoreUtil.pretty(loc.getBlock().getBiome().getKey().getKey()) + " biome.", "Full enchanted Diamond gear + potions recommended."); return boss;
    }
    /** Finds world-boss entities that are no longer the tracked encounter — leftovers from a spawn whose
     *  reveal failed, a crash mid-fight, or a chunk that came back after the event had already been cleared.
     *  They are easy to miss precisely because a failed reveal leaves them invisible and invulnerable, yet
     *  they persist forever (setRemoveWhenFarAway(false)) and can keep driving mechanics. Reports by default
     *  and only deletes when explicitly asked, so an in-progress fight is never destroyed by a diagnostic. */
    int scanOrphanBosses(CommandSender sender,boolean clean){
        int found=0,removed=0;
        for(World world:plugin.getServer().getWorlds())for(Entity entity:world.getEntities()){
            if(!(entity instanceof LivingEntity living))continue;
            String tier=living.getPersistentDataContainer().get(tierKey,PersistentDataType.STRING);
            if(!isWorldBossTier(tier))continue;
            boolean tracked=living.getUniqueId().equals(worldBossId);
            if(tracked)continue;
            found++;
            String detail="#"+living.getUniqueId()+" "+living.getType()+" tier="+tier+" at "+world.getName()+" "+living.getLocation().getBlockX()+","+living.getLocation().getBlockY()+","+living.getLocation().getBlockZ()
                    +(living.isInvisible()?" [invisible]":"")+(living.isInvulnerable()?" [invulnerable]":"");
            if(clean){
                UUID id=living.getUniqueId();
                living.remove();
                eliteIds.remove(id);damage.remove(id);lastContribution.remove(id);enrageStageApplied.remove(id);bossFirstEngagedAt.remove(id);eliteLastPlayerNear.remove(id);bossTargetSince.remove(id);bossTargetOutOfRangeSince.remove(id);bossUnreachableSince.remove(id);bossLeapCooldown.remove(id);bossRepathAt.remove(id);lastEngaged.remove(id);removeHealthBar(id);
                try{db.deleteBossState(id.toString());}catch(Throwable ignored){}
                removed++;
                CoreUtil.msg(sender,"  removed "+detail);
            }else CoreUtil.msg(sender,"  orphan "+detail);
        }
        CoreUtil.msg(sender,"Orphan world-boss scan: "+found+" untracked boss entity/entities across "+plugin.getServer().getWorlds().size()+" loaded world(s)"
                +(clean?", "+removed+" removed.":". Re-run with 'clean' to remove them.")
                +(worldBossId==null?" No world boss is currently tracked.":" Currently tracked boss "+worldBossId+" was left untouched."));
        return clean?removed:found;
    }
    private WorldBossKind randomWorldBossKind(){WorldBossKind[] all=WorldBossKind.values();return all[ThreadLocalRandom.current().nextInt(all.length)];}
    private LivingEntity spawnBossEntity(World world,Location loc,WorldBossKind kind){
        return switch(kind){
            case ASHEN_KNIGHT -> world.spawn(loc, WitherSkeleton.class, CreatureSpawnEvent.SpawnReason.CUSTOM, knight -> { var scale = knight.getAttribute(Attribute.SCALE); if(scale!=null) scale.setBaseValue(Math.max(0.5,Math.min(4.0,bosses.getDouble("world-boss.scale",1.35)))); });
            case IRON_GOLEM -> world.spawn(loc, IronGolem.class, CreatureSpawnEvent.SpawnReason.CUSTOM, golem -> { golem.setPlayerCreated(false); var scale = golem.getAttribute(Attribute.SCALE); if(scale!=null) scale.setBaseValue(Math.max(0.5,Math.min(4.0,bosses.getDouble("iron-golem-boss.scale",1.5)))); });
            /** Piglin Brutes zombify into a plain Zombified Piglin after 15s in the Overworld — and world bosses
             *  always spawn in the Overworld — so without this the boss silently turns into an untracked mob
             *  with none of its tags/stats/equipment shortly after every summon, which is exactly what looked
             *  like it "disappearing". Same immunity already used for the Cinder Raider reinforcements below. */
            case PIGLIN_BRUTE -> world.spawn(loc, PiglinBrute.class, CreatureSpawnEvent.SpawnReason.CUSTOM, brute -> { brute.setImmuneToZombification(true); var scale = brute.getAttribute(Attribute.SCALE); if(scale!=null) scale.setBaseValue(Math.max(0.5,Math.min(4.0,bosses.getDouble("piglin-brute-boss.scale",1.8)))); });
        };
    }
    private String signatureAbility(WorldBossKind kind){return switch(kind){case ASHEN_KNIGHT->"FLAMEBOUND";case IRON_GOLEM->"COLOSSAL";case PIGLIN_BRUTE->"CINDERLORD";};}
    private void equipWorldBoss(LivingEntity boss,WorldBossKind kind){
        var waterAttribute=boss.getAttribute(Attribute.WATER_MOVEMENT_EFFICIENCY);if(waterAttribute!=null)waterAttribute.setBaseValue(1.0);
        EntityEquipment eq = boss.getEquipment(); if(eq==null)return;
        switch(kind){
            case ASHEN_KNIGHT -> { eq.setItemInMainHand(special(Material.NETHERITE_SWORD, "Ashen Knight's Brand", Enchantment.SHARPNESS, 4)); eq.setHelmet(special(Material.NETHERITE_HELMET, "Ashen Warhelm", Enchantment.PROTECTION, 3)); eq.setChestplate(special(Material.NETHERITE_CHESTPLATE, "Ashen Cuirass", Enchantment.PROTECTION, 3)); eq.setBoots(special(Material.NETHERITE_BOOTS,"Ashen Warboots",Enchantment.DEPTH_STRIDER,3)); eq.setItemInMainHandDropChance(0); eq.setHelmetDropChance(0); eq.setChestplateDropChance(0); eq.setBootsDropChance(0); }
            case PIGLIN_BRUTE -> { eq.setItemInMainHand(special(Material.NETHERITE_AXE, "Cinderlord's Cleaver", Enchantment.SHARPNESS, 5)); eq.setHelmet(special(Material.GOLDEN_HELMET, "Warlord's Crown", Enchantment.PROTECTION, 3)); eq.setItemInMainHandDropChance(0); eq.setHelmetDropChance(0); }
            case IRON_GOLEM -> {}
        }
    }
    private String awakenLine(WorldBossKind kind){return switch(kind){case ASHEN_KNIGHT->"The Ashen Knight has awakened.";case IRON_GOLEM->"The Warded Colossus has risen.";case PIGLIN_BRUTE->"The Cinder Warlord has stormed in, deep within the Nether.";};}

    ItemStack createSummonScroll(){ItemStack item=CoreUtil.named(Material.PAPER,"Sealed Omen",summonLore(null));ItemMeta meta=item.getItemMeta();meta.getPersistentDataContainer().set(summonKey,PersistentDataType.BYTE,(byte)1);item.setItemMeta(meta);return item;}
    ItemStack createSummonScroll(WorldBossKind kind){ItemStack item=CoreUtil.named(Material.PAPER,"Sealed Omen: "+displayName(kind),summonLore(kind));ItemMeta meta=item.getItemMeta();meta.getPersistentDataContainer().set(summonKey,PersistentDataType.BYTE,(byte)1);meta.getPersistentDataContainer().set(summonKindKey,PersistentDataType.STRING,kind.name());item.setItemMeta(meta);return item;}
    WorldBossKind summonScrollKind(ItemStack item){if(item==null||!item.hasItemMeta())return null;String raw=item.getItemMeta().getPersistentDataContainer().get(summonKindKey,PersistentDataType.STRING);if(raw==null)return null;try{return WorldBossKind.valueOf(raw);}catch(IllegalArgumentException ignored){return null;}}
    private List<String> summonLore(WorldBossKind kind){
        List<String> lore=new ArrayList<>();
        lore.add("Something ancient stirs beneath the seal.");
        lore.add(kind==null?"Use it in unclaimed wilderness — the realm you're standing in decides who answers.":"Use it in unclaimed "+(kind==WorldBossKind.PIGLIN_BRUTE?"Nether":"Overworld")+" wilderness.");
        if(kind==null)lore.add("The Cinder Warlord only answers if used in the Nether.");
        lore.add("");
        lore.add(summonReadyLine());
        return lore;
    }
    /** "Cooldown" here is simply whether an encounter is currently active — a Summoning Paper, like a
     *  natural random world-boss event, can never start a second encounter while one is already running
     *  (see the worldBoss()!=null||eventType!=null guard below). This is unrelated to the natural event
     *  system's own RARE-tier interval timer (activeTierNextDelay/eventRemaining in startEvent()), which
     *  is set ONLY when origin==Origin.NATURAL — a player-summoned encounter never starts, resets, or
     *  blocks that timer, and vice versa; they are genuinely separate cooldowns. */
    private String summonReadyLine(){
        if(worldBoss()==null&&eventType==null)return"Ready to summon.";
        long remaining=Math.max(0,eventEnds-System.currentTimeMillis());
        long minutes=remaining/60000,seconds=(remaining%60000)/1000;
        return"On cooldown — an encounter is already active (~"+minutes+"m "+seconds+"s remaining).";
    }
    /** Keeps any Sealed Omen already sitting in an online player's inventory showing live state, without
     *  needing to reopen the shop or attempt a summon to find out. getContents() returns live references
     *  into the actual inventory array, so mutating the ItemStack here is enough — no setItem() needed. */
    private void refreshSummonScrollLore(){
        for(Player player:plugin.getServer().getOnlinePlayers())for(ItemStack item:player.getInventory().getContents()){
            if(item==null||!item.hasItemMeta())continue;
            ItemMeta meta=item.getItemMeta();
            if(!meta.getPersistentDataContainer().has(summonKey,PersistentDataType.BYTE))continue;
            meta.lore(summonLore(summonScrollKind(item)).stream().map(line->Component.text(line,NamedTextColor.GRAY)).toList());
            item.setItemMeta(meta);
        }
    }
    private WorldBossKind randomOverworldBossKind(){WorldBossKind[] options={WorldBossKind.ASHEN_KNIGHT,WorldBossKind.IRON_GOLEM};return options[ThreadLocalRandom.current().nextInt(options.length)];}
    /** Overworld can only ever roll an Overworld-capable boss (Ashen Knight or Warded Colossus, 50/50) —
     *  the Cinder Warlord is never even a candidate there, since it cannot spawn in the Overworld at all.
     *  The Nether instead rolls all three at roughly equal odds: a Nether-found seal can just as easily
     *  wake one of the Overworld bosses as the Cinder Warlord. The End has no eligible boss. */
    private WorldBossKind randomGenericBossKind(World.Environment current){
        if(current==World.Environment.NETHER){WorldBossKind[] all=WorldBossKind.values();return all[ThreadLocalRandom.current().nextInt(all.length)];}
        if(current==World.Environment.NORMAL)return randomOverworldBossKind();
        return null;
    }
    boolean useSummonScroll(Player player){return useSummonScroll(player,null);}
    /** A specific ("Chosen") scroll (kind != null) requires the dimension its boss actually lives in — no
     *  randomization, the player paid extra to pick exactly one. A generic scroll (kind == null) rolls
     *  dimension-aware via randomGenericBossKind(). Whichever kind is resolved spawns using ITS OWN normal
     *  placement rules — worldFor(kind) and startEvent()'s own randomSafe()/protectedEventLocation() search
     *  in that boss's actual home world — rather than being forced to the player's current position, exactly
     *  like a natural random world-boss event already works. This is what keeps a Nether-rolled Ashen
     *  Knight/Warded Colossus correctly appearing in the Overworld instead of being spawned nearby in the
     *  wrong dimension (or the reverse: a Cinder Warlord silently redirected to some unrelated Nether spot
     *  when rolled from the Overworld — which can't happen at all now, since the Overworld roll never
     *  includes it in the first place). Nothing is consumed and no cooldown starts unless this returns true
     *  (see MerchantService.use(), which only calls consume() on success). */
    boolean useSummonScroll(Player player,WorldBossKind kind){
        World.Environment current=player.getWorld().getEnvironment();
        WorldBossKind resolved=kind!=null?kind:randomGenericBossKind(current);
        if(resolved==null){CoreUtil.error(player,"This seal only stirs in the Overworld or the Nether.");return false;}
        World.Environment required=resolved==WorldBossKind.PIGLIN_BRUTE?World.Environment.NETHER:World.Environment.NORMAL;
        boolean sameWorld=current==required;
        if(kind!=null&&!sameWorld){CoreUtil.error(player,resolved==WorldBossKind.PIGLIN_BRUTE?"The Cinder Warlord's seal only stirs within the Nether.":"This seal needs Overworld wilderness.");return false;}
        Location loc=null;
        if(sameWorld){
            loc=CoreUtil.findSafeAny(player.getWorld(),player.getLocation().getBlockX(),player.getLocation().getBlockZ());
            if(loc==null||protectedEventLocation(loc)){CoreUtil.error(player,"The seal needs wilderness at least "+eventProtectionRadius()+" blocks from protected land.");return false;}
        }
        if(worldBoss()!=null||eventType!=null){CoreUtil.error(player,"A major encounter is already active.");return false;}
        if(!startEvent(EventType.WORLD_BOSS,defaultTier(EventType.WORLD_BOSS),loc,Origin.PLAYER_SUMMONED,resolved)){CoreUtil.error(player,"The omen resists this location.");return false;}
        return true;
    }

    void onDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof LivingEntity attacker) {
            applyAttackAbility(attacker, e);
            if(e.getEntity() instanceof Player&&attacker.getPersistentDataContainer().has(tierKey))lastMobHit.put(attacker.getUniqueId(),System.currentTimeMillis());
        }
        LivingEntity victim=bossVictim(e.getEntity());if(victim==null)return;String tier = victim.getPersistentDataContainer().get(tierKey, PersistentDataType.STRING);boolean shardBoss=victim instanceof Warden,shared=isVanillaBoss(victim)||"miniboss".equals(tier);if(tier==null&&!shared&&!shardBoss)return;String ability = victim.getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
        Player damager = playerDamager(e.getDamager());
        if (damager != null) {
            double toughness=toughnessFor(victim,tier);if(exposedUntil.getOrDefault(victim.getUniqueId(),0L)>System.currentTimeMillis())toughness/=1.5;if(toughness>1)e.setDamage(e.getDamage()/toughness);
            if(tier!=null){double relicMultiplier=relics.eliteOutgoingMultiplier(damager);if(relicMultiplier!=1)e.setDamage(e.getDamage()*relicMultiplier);}
            if (tier!=null&&"BULWARK".equals(ability)) e.setDamage(e.getDamage() * bosses.getDouble("abilities.bulwark-damage-multiplier", .65));
            String damagerId = CoreUtil.id(damager);
            double dealt=Math.min(victim.getHealth(),Math.max(0,e.getFinalDamage()));damage.computeIfAbsent(victim.getUniqueId(), x -> new HashMap<>()).merge(damagerId, dealt, Double::sum);lastContribution.computeIfAbsent(victim.getUniqueId(),x->new HashMap<>()).put(damagerId,System.currentTimeMillis());
            lastTarget.put(victim.getUniqueId(),damagerId);lastEngaged.put(victim.getUniqueId(),System.currentTimeMillis());
            /** Vanilla AI doesn't reliably switch target to whoever just landed a ranged hit (arrows in
             *  particular can go unanswered if the boss is mid-animation or already chasing someone else) —
             *  playerDamager() already resolves the shooter correctly, so just force the target explicitly
             *  for world bosses rather than leaving it to chance. */
            if(isWorldBossTier(tier)&&victim instanceof Mob mob)mob.setTarget(damager);
            if(e.getDamager() instanceof Projectile||damager.getLocation().distanceSquared(victim.getLocation())>Math.pow(bosses.getDouble("anti-cheese.ranged-distance",18),2))rangedHits.merge(victim.getUniqueId(),1,Integer::sum);else rangedHits.put(victim.getUniqueId(),0);
            if(tier!=null){double lifesteal=relics.oathbladeLifesteal(damager);if(lifesteal>0&&dealt>0)damager.setHealth(Math.min(damager.getAttribute(Attribute.MAX_HEALTH).getValue(),damager.getHealth()+dealt*lifesteal));}
            boolean eventTarget = eventType != null && (isWorldBossTier(tier) || victim.getPersistentDataContainer().has(eventEliteKey));
            if (eventTarget && eventParticipants.add(damagerId)){db.incrementStat(damagerId, "event_participations");plugin.progress().eventParticipated(damager);}
            if(shared){sharedBossIds.add(victim.getUniqueId());ensureSharedBase(victim);scaleSharedBoss(victim);}
        } else if (tier!=null&&"BULWARK".equals(ability)) e.setDamage(e.getDamage() * bosses.getDouble("abilities.bulwark-damage-multiplier", .65));
        if(tier==null)return;
        double after = victim.getHealth() - e.getFinalDamage(), max = victim.getAttribute(Attribute.MAX_HEALTH).getValue(); int previous = victim.getPersistentDataContainer().getOrDefault(burstKey, PersistentDataType.INTEGER, 0); boolean worldBossTier=isWorldBossTier(tier); int stage = worldBossTier?worldBossPhaseStage(after/Math.max(1,max)):phaseStage(tier,after/Math.max(1,max));
        if ((worldBossTier||Set.of("epic","legendary","miniboss").contains(tier)) && stage > previous) { victim.getPersistentDataContainer().set(burstKey, PersistentDataType.INTEGER, stage); eliteBurst(victim,tier,stage); }
        if (worldBossTier) { int phase = victim.getPersistentDataContainer().getOrDefault(phaseKey, PersistentDataType.INTEGER, 0); if (stage > phase) { victim.getPersistentDataContainer().set(phaseKey, PersistentDataType.INTEGER, stage); worldBossBurst(victim, kindFromTier(tier), stage); } }
    }
    private int phaseStage(String tier,double healthRatio){if(tier.equals("legendary"))return healthRatio<.25?3:healthRatio<.50?2:healthRatio<.75?1:0;if(tier.equals("epic"))return healthRatio<.30?2:healthRatio<.60?1:0;return healthRatio<.33?2:healthRatio<.66?1:0;}
    /** World bosses get a dedicated 4-stage curve (vs. elites' 3-stage) so the final "enrage" stretch at very
     *  low health reads as a distinct climax rather than a repeat of the mid-fight phase. */
    private int worldBossPhaseStage(double healthRatio){return healthRatio<.15?3:healthRatio<.40?2:healthRatio<.70?1:0;}
    private void applyAttackAbility(LivingEntity attacker, EntityDamageByEntityEvent e) { String ability = attacker.getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING); if (ability == null || !(e.getEntity() instanceof Player player)) return;lastMobHit.put(attacker.getUniqueId(),System.currentTimeMillis());e.setDamage(e.getDamage()*relics.eliteIncomingMultiplier(player)); long now = System.currentTimeMillis(), last = abilityCooldown.getOrDefault(attacker.getUniqueId(), 0L); if ("FLAMEBOUND".equals(ability)) player.setFireTicks(Math.max(player.getFireTicks(), 80)); else if ("VAMPIRIC".equals(ability)) attacker.setHealth(Math.min(attacker.getAttribute(Attribute.MAX_HEALTH).getValue(), attacker.getHealth() + e.getFinalDamage() * .35)); else if ("VENOMOUS".equals(ability)) player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 0)); else if ("FROSTBITE".equals(ability)) player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1)); else if(now-last>6500&&"INFERNAL_RIFT".equals(ability)){abilityCooldown.put(attacker.getUniqueId(),now);player.setFireTicks(Math.max(player.getFireTicks(),120));player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,player.getLocation().add(0,1,0),35,.8,1,.8,.05);Vector away=player.getLocation().toVector().subtract(attacker.getLocation().toVector());if(away.lengthSquared()>0)player.setVelocity(away.normalize().multiply(.8).setY(.35));}else if(now-last>7000&&"VOID_TETHER".equals(ability)){abilityCooldown.put(attacker.getUniqueId(),now);player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION,35,0));Location behind=player.getLocation().clone().subtract(player.getLocation().getDirection().multiply(2));attacker.teleport(behind);player.getWorld().playSound(player.getLocation(),Sound.ENTITY_ENDERMAN_TELEPORT,1,.55f);}else if (now - last > 6000 && "STORMCALLER".equals(ability)) { abilityCooldown.put(attacker.getUniqueId(), now); player.getWorld().strikeLightningEffect(player.getLocation()); player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 0)); } else if (now - last > 7000 && "PHASEWALKER".equals(ability)) { abilityCooldown.put(attacker.getUniqueId(), now); Location behind = player.getLocation().clone().subtract(player.getLocation().getDirection().multiply(2)); behind.setY(player.getLocation().getY()); attacker.teleport(behind); attacker.getWorld().playSound(behind, Sound.ENTITY_ENDERMAN_TELEPORT, 1, .8f); } }
    void onAnyDamage(EntityDamageEvent e){if(!(e.getEntity() instanceof LivingEntity living)||!living.getPersistentDataContainer().has(tierKey))return;if(Set.of("SUFFOCATION","DROWNING","FALL","CRAMMING","VOID").contains(e.getCause().name())){e.setCancelled(true);if(e.getCause()==EntityDamageEvent.DamageCause.VOID){Location safe=CoreUtil.findSafe(living.getWorld(),living.getLocation().getBlockX(),living.getLocation().getBlockZ());living.teleport(safe==null?living.getWorld().getSpawnLocation():safe);}}}
    private void eliteBurst(LivingEntity mob,String tier,int stage) {
        mob.getWorld().playSound(mob.getLocation(),tier.equals("legendary")?Sound.ENTITY_WITHER_SPAWN:Sound.ENTITY_RAVAGER_ROAR,tier.equals("legendary")?1.1f:.8f,stage==1?1.2f:.8f);
        mob.getWorld().spawnParticle(tier.equals("legendary")?Particle.SOUL_FIRE_FLAME:Particle.ENCHANT,mob.getLocation().add(0,1,0),tier.equals("legendary")?90:50,2.5,1.2,2.5,.08);
        for(Entity entity:mob.getNearbyEntities(6,4,6))if(entity instanceof Player p){Vector away=p.getLocation().toVector().subtract(mob.getLocation().toVector());if(away.lengthSquared()>0)p.setVelocity(away.normalize().multiply(tier.equals("legendary")?.95:.7).setY(.4));}
        if(tier.equals("epic")){mob.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,100,stage==1?0:1));mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,stage==1?160:500,stage==1?1:2,false,false));if(stage>=2){mob.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,600,0,false,false));if(mob instanceof PigZombie||mob instanceof Piglin||mob instanceof Hoglin)spawnReinforcements(mob,2);}}
        if(tier.equals("legendary"))legendaryPhase(mob,stage);
        else if("SUMMONER".equals(mob.getPersistentDataContainer().get(abilityKey,PersistentDataType.STRING)))spawnReinforcements(mob,stage+1);
    }
    private void legendaryPhase(LivingEntity mob,int stage){
        if(stage==1){mob.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,120,2));mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,300,2,false,false));}
        else if(stage==2)spawnReinforcements(mob,mob instanceof Creeper?1:3);
        else{mob.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,1200,1,false,false));mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,1200,2,false,false));for(Entity entity:mob.getNearbyEntities(7,4,7))if(entity instanceof Player player)player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,60,0));}
    }
    private void spawnReinforcements(LivingEntity leader,int count){Class<? extends Mob> type;if(leader instanceof Spider)type=CaveSpider.class;else if(leader instanceof Enderman)type=Endermite.class;else if(leader instanceof AbstractSkeleton)type=Skeleton.class;else if(leader instanceof PigZombie)type=PigZombie.class;else if(leader instanceof Piglin)type=Piglin.class;else type=Zombie.class;for(int i=0;i<count;i++)leader.getWorld().spawn(leader.getLocation(),type,CreatureSpawnEvent.SpawnReason.CUSTOM,minion->{minion.getPersistentDataContainer().set(spawnerKey,PersistentDataType.BYTE,(byte)1);minion.setRemoveWhenFarAway(true);});}
    private void worldBossBurst(LivingEntity boss, WorldBossKind kind, int phase) {
        switch(kind){
            case ASHEN_KNIGHT -> { boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.2f, phase == 1 ? 1.3f : .8f); boss.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, boss.getLocation().add(0, 1, 0), 90, 3, 1, 3, .08); for (Entity entity : boss.getNearbyEntities(7, 4, 7)) if (entity instanceof Player p) p.setFireTicks(80); for (int i = 0; i < phase + 1; i++) boss.getWorld().spawn(boss.getLocation(), WitherSkeleton.class, CreatureSpawnEvent.SpawnReason.CUSTOM, s -> { s.customName(Component.text("Ashen Squire", NamedTextColor.GRAY)); s.getPersistentDataContainer().set(spawnerKey, PersistentDataType.BYTE, (byte) 1); }); }
            case IRON_GOLEM -> { boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1.3f, phase == 1 ? 1.1f : .7f); boss.getWorld().spawnParticle(Particle.CRIT, boss.getLocation().add(0, 1, 0), 100, 3.5, 1.2, 3.5, .1); boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, phase, false, false)); for (Entity entity : boss.getNearbyEntities(8, 5, 8)) if (entity instanceof Player p) { Vector away = p.getLocation().toVector().subtract(boss.getLocation().toVector()); if (away.lengthSquared() > 0) p.setVelocity(away.normalize().multiply(1.1 + phase * .3).setY(.6 + phase * .2)); } }
            case PIGLIN_BRUTE -> { boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_PIGLIN_BRUTE_ANGRY, 1.3f, phase == 1 ? 1.2f : .8f); boss.getWorld().spawnParticle(Particle.FLAME, boss.getLocation().add(0, 1, 0), 100, 3, 1.3, 3, .1); boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 140, phase, false, false)); for (Entity entity : boss.getNearbyEntities(6, 4, 6)) if (entity instanceof Player p) p.setFireTicks(Math.max(p.getFireTicks(), 60)); for (int i = 0; i < Math.min(phase,2); i++) boss.getWorld().spawn(boss.getLocation(), Piglin.class, CreatureSpawnEvent.SpawnReason.CUSTOM, s -> { s.customName(Component.text("Cinder Raider", NamedTextColor.GOLD)); s.getPersistentDataContainer().set(spawnerKey, PersistentDataType.BYTE, (byte) 1); s.setImmuneToZombification(true); });
                if(phase>=3&&enraged.add(boss.getUniqueId())){boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,Integer.MAX_VALUE,1,false,false));boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,Integer.MAX_VALUE,1,false,false));boss.getWorld().playSound(boss.getLocation(),Sound.ENTITY_WITHER_AMBIENT,1.4f,.6f);broadcastNotice(Component.text("☠ The Cinder Warlord enters a berserk fury!",NamedTextColor.RED));} }
        }
    }
    private Player playerDamager(Entity damager) { if (damager instanceof Player p) return p; if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) return p; if (damager instanceof Tameable tame && tame.getOwner() instanceof Player p) return p; return null; }

    void onDeath(EntityDeathEvent e) {
        LivingEntity mob = e.getEntity(); Player killer = mob.getKiller(); String tier = mob.getPersistentDataContainer().get(tierKey, PersistentDataType.STRING); eliteIds.remove(mob.getUniqueId()); abilityCooldown.remove(mob.getUniqueId());catchupCooldown.remove(mob.getUniqueId());lastEngaged.remove(mob.getUniqueId());lastTarget.remove(mob.getUniqueId());rangedHits.remove(mob.getUniqueId());bossMechanicAt.remove(mob.getUniqueId());blockedSince.remove(mob.getUniqueId());lastMobHit.remove(mob.getUniqueId());lastNearbyAt.remove(mob.getUniqueId());specialAbilityAt.remove(mob.getUniqueId());exposedUntil.remove(mob.getUniqueId());enraged.remove(mob.getUniqueId());enrageStageApplied.remove(mob.getUniqueId());bossFirstEngagedAt.remove(mob.getUniqueId());eliteLastPlayerNear.remove(mob.getUniqueId());bossTargetSince.remove(mob.getUniqueId());bossTargetOutOfRangeSince.remove(mob.getUniqueId());bossUnreachableSince.remove(mob.getUniqueId());bossLeapCooldown.remove(mob.getUniqueId());bossRepathAt.remove(mob.getUniqueId());removeHealthBar(mob.getUniqueId());boolean spawner = mob.getPersistentDataContainer().has(spawnerKey);
        if (tier != null) { rewardElite(e, killer, tier); return; }
        if(isVanillaBoss(mob)){rewardVanillaBoss(e,mob,killer);return;}if(mob instanceof Warden)rewardWardenShards(mob,killer);if (killer == null) return;
        double penalty = friendlyPenalty(mob); if (penalty > 0) { double charged = db.takeUpTo(CoreUtil.id(killer), penalty); if(charged>0){plugin.bank().creditSink(charged,CoreUtil.id(killer),"FRIENDLY_"+mob.getType().name());db.recordEconomy(CoreUtil.id(killer),"FRIENDLY_PENALTY",-charged,mob.getType().name());killer.sendActionBar(Component.text("-" + CoreUtil.money(charged) + " " + CoreUtil.pretty(mob.getType().name()), NamedTextColor.RED));} return; } if (spawner) return;
        String dimension="dimension-mob-rewards."+mob.getWorld().getEnvironment().name()+"."+mob.getType().name();List<Double> range = bosses.getDoubleList(dimension);if(range.size()<2)range = bosses.getDoubleList("mob-rewards." + mob.getType().name()); if (range.size() < 2) range = bosses.getDoubleList("default-mob-reward"); if (range.size() < 2) range = List.of(.05, .35); double factor = farmFactor(killer, mob.getType()); if (factor <= 0) return;
        double amount = random(range.get(0), range.get(1)) * factor;if(mob instanceof Enemy){plugin.progress().hostileKill(killer,mob.getType(),mob.getWorld().getEnvironment());amount*=plugin.progress().mobIncomeMultiplier(killer);}
        if (amount >= .01) { plugin.creditEarned(CoreUtil.id(killer),amount,"MOB_"+mob.getType().name());db.recordEconomy(CoreUtil.id(killer),"MOB_NORMAL",amount,mob.getType().name()); killer.sendActionBar(Component.text("+" + CoreUtil.money(amount) + " mob reward", NamedTextColor.GREEN)); }
    }
    private void rewardVanillaBoss(EntityDeathEvent event,LivingEntity boss,Player killer){
        UUID id=boss.getUniqueId();Map<String,Double> raw=damage.remove(id);Map<String,Long> hits=lastContribution.remove(id);sharedBossIds.remove(id);Map<String,Double> participants=meaningfulParticipants(boss,raw,hits);
        if(participants.isEmpty()&&killer!=null)participants=Map.of(CoreUtil.id(killer),Math.max(1,boss.getAttribute(Attribute.MAX_HEALTH).getValue()));
        boolean weeklyKill=boss instanceof EnderDragon&&plugin.weeklyDragon().isWeekly(boss);
        /** Vanilla only ever grants the real first-kill reward (dragon egg + 12000 XP instead of the
         *  reduced 500) once per world, on the true first-ever kill — and this world's dragon was already
         *  killed before the weekly system existed, so hasBeenPreviouslyKilled() is permanently true and
         *  vanilla's own logic will never grant either again, weekly or manual. Restoring both, but only for
         *  a weekly-tagged kill (isWeekly(boss), the same flag shards.rewardBoss() below already gates on) —
         *  a manually /ashfall dragon start'd fight keeps vanilla's own reduced default untouched.
         *  This block used to sit after the participant-reward loop, gated behind two early returns
         *  (empty participants, missing mob-rewards config) meant only for the money split below — so a
         *  dragon killed with no tracked player damage (e.g. the final blow being void/fall damage after a
         *  real fight, or damage tracked under a stale UUID) silently skipped the egg, the XP, and — worse —
         *  weeklyDragon().defeated() itself, leaving the service thinking the fight was still unresolved.
         *  None of that should ever depend on whether a money split can be computed, so it now runs
         *  unconditionally for every real dragon death. Also: EntityDeathEvent#setDroppedExp() is a known
         *  no-op for EnderDragon specifically — vanilla spawns its death XP via its own hardcoded orb-spawn
         *  path, not through the generic droppedExp field every other mob honors — so the XP is granted
         *  directly instead of trusting the event field.
         *  Live-confirmed 2026-08-02 (real kill, egg + XP both landed): the egg should be placed as a block
         *  on the bedrock at the center of the main End island (0,65,0, matching vanilla's own first-kill
         *  placement) rather than dropped as an item — and the XP needs to be many small orbs, not one
         *  12000-value orb, since a single giant orb only ever gets picked up by whichever participant
         *  happens to be nearest, handing them the entire encounter's XP alone (confirmed: one player jumped
         *  50 levels from it) instead of letting every nearby participant collect a fair share. */
        if(boss instanceof EnderDragon dragon){
            plugin.getLogger().info("[WeeklyDragon] death: weeklyKill="+weeklyKill+" participants="+participants.size()+" killer="+(killer==null?"null":killer.getName()));
            if(weeklyKill){
                World endWorld=dragon.getWorld();
                /** Scattering orbs around the dragon's own death LOCATION (as opposed to a fixed safe spot)
                 *  live-confirmed a real loss: the dragon usually dies mid-air, sometimes near the island's
                 *  edge, so a wide ±3-block scatter routinely dropped a chunk of the 60 orbs straight into
                 *  the void before anyone could reach them (one full test only reached level 19 instead of
                 *  the expected ~69). Anchored on the same fixed, solid platform the egg appears on instead,
                 *  with a tight scatter that can't roll off it. */
                Location safeSpot=new Location(endWorld,0.5,66,0.5);
                int totalXp=12000,orbCount=60,perOrb=totalXp/orbCount;
                for(int i=0;i<orbCount;i++){
                    Location orbLoc=safeSpot.clone().add(ThreadLocalRandom.current().nextDouble(-2,2),ThreadLocalRandom.current().nextDouble(0,1.5),ThreadLocalRandom.current().nextDouble(-2,2));
                    endWorld.spawn(orbLoc,ExperienceOrb.class,orb->orb.setExperience(perOrb));
                }
                /** The egg used to appear the instant the death event fired — essentially the moment health
                 *  hit zero, well before the dragon's ~10s death animation/explosion actually finishes
                 *  playing out. Delayed to land after it. */
                plugin.getServer().getScheduler().runTaskLater(plugin,()->endWorld.getBlockAt(0,65,0).setType(Material.DRAGON_EGG),200L);
            }
            plugin.weeklyDragon().defeated(dragon);
        }
        if(participants.isEmpty())return;List<Double> range=bosses.getDoubleList("mob-rewards."+boss.getType().name());if(range.size()<2)return;
        double pool=random(range.get(0),range.get(1))*participantRewardMultiplier(participants.size());double total=participants.values().stream().mapToDouble(Double::doubleValue).sum();
        for(var entry:participants.entrySet()){Player player=find(entry.getKey());if(player==null)continue;double share=pool*entry.getValue()/Math.max(1,total);boolean firstDragonKiller=boss.getType()==EntityType.ENDER_DRAGON&&killer!=null&&killer.getUniqueId().equals(player.getUniqueId())&&weeklyKill&&!db.hasMilestone(entry.getKey(),"DEFEAT_DRAGON");boolean full=majorRewardAvailable(entry.getKey(),boss.getType());if(!full)share*=bosses.getDouble("major-rewards."+boss.getType().name()+".repeat-multiplier",.1);if(firstDragonKiller)share=Math.max(share,bosses.getDouble("major-rewards.ENDER_DRAGON.first-killer-reward",100000));share*=plugin.progress().mobIncomeMultiplier(player);share=Math.round(share*100)/100.0;
            if(share>0){plugin.creditEarned(entry.getKey(),share,"BOSS_"+boss.getType().name());db.recordEconomy(entry.getKey(),"BOSS",share,boss.getType().name());CoreUtil.msg(player,"Your boss participation earned "+CoreUtil.money(share)+".");}
            db.incrementStat(entry.getKey(),"boss_kills");plugin.progress().majorKill(player,boss.getType());plugin.shards().rewardBoss(player,boss.getType(),weeklyKill);if(full)giveParticipationLoot(player,boss.getType().name());
        }
    }
    private void rewardWardenShards(LivingEntity warden,Player killer){Map<String,Double> participants=meaningfulParticipants(warden,damage.remove(warden.getUniqueId()),lastContribution.remove(warden.getUniqueId()));if(participants.isEmpty()&&killer!=null)participants=Map.of(CoreUtil.id(killer),1.0);for(String id:participants.keySet()){Player player=find(id);if(player!=null)plugin.shards().rewardBoss(player,EntityType.WARDEN,false);}}
    private boolean majorRewardAvailable(String player,EntityType type){String path="major-rewards."+type.name(),key="major:"+player+":"+type.name();long last=parseLong(db.state(key),0),cooldown=bosses.getLong(path+".cooldown-hours",type==EntityType.WITHER?24:168)*3600000L;if(System.currentTimeMillis()-last<cooldown)return false;db.state(key,Long.toString(System.currentTimeMillis()));return true;}
    private double participantRewardMultiplier(int count){return 1+bosses.getDouble("boss-participation.reward-extra-player-factor",.25)*Math.sqrt(Math.max(0,count-1));}
    private Map<String,Double> meaningfulParticipants(LivingEntity boss,Map<String,Double> raw,Map<String,Long> hits){
        if(raw==null||raw.isEmpty())return Map.of();long now=System.currentTimeMillis(),window=bosses.getLong("boss-participation.active-seconds",120)*1000L;double radiusSq=Math.pow(bosses.getDouble("boss-participation.reward-radius",100),2),total=raw.values().stream().mapToDouble(Double::doubleValue).sum(),minimum=bosses.getDouble("reward-splitting.minimum-damage-percent",.02);Map<String,Double> eligible=new LinkedHashMap<>();
        for(var entry:raw.entrySet()){
            Player player=find(entry.getKey());long last=hits==null?now:hits.getOrDefault(entry.getKey(),0L);
            if(player==null||now-last>window||entry.getValue()/Math.max(1,total)<minimum)continue;
            boolean nearby=player.getWorld().equals(boss.getWorld())&&player.getLocation().distanceSquared(boss.getLocation())<=radiusSq;
            /** A substantial contributor who dies near the end (e.g. caught by the boss's final burst)
             *  shouldn't lose their reward just because dying moved/respawned them out of range — the
             *  proximity gate is meant to exclude players who wandered off and stopped participating, not
             *  ones killed by the very fight they were actively part of. Still requires the same recency
             *  window and minimum-damage-share as every other participant. */
            if(nearby||player.isDead())eligible.put(entry.getKey(),entry.getValue());
        }
        if(eligible.isEmpty())raw.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(entry->{Player player=find(entry.getKey());if(player!=null&&player.getWorld().equals(boss.getWorld())&&player.getLocation().distanceSquared(boss.getLocation())<=radiusSq)eligible.put(entry.getKey(),entry.getValue());});return eligible;
    }
    private void giveParticipationLoot(Player player,String encounter){List<String> configured=bosses.getStringList("boss-participation.loot."+encounter);for(String value:configured){String[] parts=value.split(":",2);Material material=Material.matchMaterial(parts[0]);if(material==null)continue;int amount=1;if(parts.length>1)try{amount=Math.max(1,Math.min(64,Integer.parseInt(parts[1])));}catch(NumberFormatException ignored){}CoreUtil.give(player,new ItemStack(material,amount));}}
    private double friendlyPenalty(LivingEntity mob){if((mob instanceof Wolf||mob instanceof Cat||mob instanceof Parrot)&&(!(mob instanceof Tameable tame)||!tame.isTamed()))return 0;return bosses.getDouble("mob-penalties."+mob.getType().name(),0);}
    private double farmFactor(Player p, EntityType type) { String key = CoreUtil.id(p) + ":" + type.name(); Deque<Long> queue = farmKills.computeIfAbsent(key, x -> new ArrayDeque<>()); long cutoff = System.currentTimeMillis() - plugin.getConfig().getLong("mob-money.anti-farm-window-minutes", 10) * 60000L; while (!queue.isEmpty() && queue.peekFirst() < cutoff) queue.removeFirst(); queue.addLast(System.currentTimeMillis()); double n=queue.size()*plugin.getConfig().getDouble("mob-money.farm-weights."+type.name(),1);int full=plugin.getConfig().getInt("mob-money.full-reward-kills",20),soft=plugin.getConfig().getInt("mob-money.soft-reward-kills",50),hard=plugin.getConfig().getInt("mob-money.hard-reward-kills",100);double softFloor=plugin.getConfig().getDouble("mob-money.soft-multiplier",.35),floor=plugin.getConfig().getDouble("mob-money.minimum-multiplier",.05);if(n<=full)return 1;if(n<=soft)return 1-(1-softFloor)*(n-full)/Math.max(1,soft-full);if(n<=hard)return softFloor-(softFloor-floor)*(n-soft)/Math.max(1,hard-soft);return floor; }
    private void rewardElite(EntityDeathEvent e, Player killer, String tier) {
        LivingEntity mob = e.getEntity(); boolean worldBoss = isWorldBossTier(tier); WorldBossKind bossKind = worldBoss ? kindFromTier(tier) : null;
        Map<String,Double> raw=damage.remove(mob.getUniqueId());Map<String,Long> hits=lastContribution.remove(mob.getUniqueId());Map<String,Double> participants=meaningfulParticipants(mob,raw,hits);sharedBossIds.remove(mob.getUniqueId());if(participants.isEmpty()&&killer!=null)participants=Map.of(CoreUtil.id(killer),1.0);
        if (participants.isEmpty()) {
            if (worldBoss && worldBossId != null && worldBossId.equals(mob.getUniqueId())) {
                db.deleteBossState(worldBossId.toString());
                worldBossId = null;
                broadcastNotice(Component.text(displayName(bossKind)+" has faded without a victor.", NamedTextColor.DARK_GRAY));
            }
            return;
        }
        if (worldBoss) { boolean persisted = worldBossId != null && worldBossId.equals(mob.getUniqueId()); if (persisted && !db.claimBossReward(worldBossId.toString())) return; }
        String path = worldBoss ? configPrefix(bossKind) : "tiers." + tier; double amount = random(bosses.getDouble(path + ".reward-min"), bosses.getDouble(path + ".reward-max"));if(worldBoss)amount*=1+bosses.getDouble(path+".reward-extra-player-factor",.35)*Math.sqrt(Math.max(0,participants.size()-1));else if(tier.equals("miniboss"))amount*=participantRewardMultiplier(participants.size());else if(mob.getWorld().getEnvironment()==World.Environment.NETHER)amount*=bosses.getDouble("dimension-content.nether.reward-multiplier",1.2);else if(mob.getWorld().getEnvironment()==World.Environment.THE_END)amount*=bosses.getDouble("dimension-content.end.reward-multiplier",1.4);splitReward(participants,amount,worldBoss?"world boss":"elite");
        Player credited=killer;if(credited==null){String top=participants.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();credited=find(top);}
        for(String participant:participants.keySet()){Player player=find(participant);if(player==null)continue;if(tier.equals("epic")||tier.equals("legendary"))plugin.progress().eliteParticipation(player,tier);plugin.shards().rewardElite(player,tier);if(worldBoss||tier.equals("miniboss")){db.incrementStat(participant,"boss_kills");plugin.progress().bossKill(player,worldBoss?displayName(bossKind):CoreUtil.pretty(mob.getType().name())+" Miniboss",worldBoss,!worldBoss);giveParticipationLoot(player,worldBoss?tier:"miniboss");}}
        if (worldBoss) rewardWorldBoss(e, credited, participants, bossKind); else { thematicLoot(e, tier);capsuleDrop(e,tier);if(tier.equals("legendary")){String victor=credited==null?"unknown hunters":plugin.nicknames().displayName(credited);broadcastNotice(Component.text("✦ The legendary "+CoreUtil.pretty(mob.getType().name())+" was defeated by "+victor+".",NamedTextColor.GOLD));for(Player player:plugin.getServer().getOnlinePlayers())if(plugin.settings().sounds(player))player.playSound(player.getLocation(),Sound.UI_TOAST_CHALLENGE_COMPLETE,.7f,.8f);} if (credited!=null&&eventType == EventType.ELITE_HUNT && mob.getPersistentDataContainer().has(eventEliteKey)) { plugin.progress().eventWon(credited, "Elite Hunt"); finishEvent(true); } }
    }
    private void splitReward(Map<String,Double> participants,double pool,String label){double eligibleDamage=participants.values().stream().mapToDouble(Double::doubleValue).sum();for(var entry:participants.entrySet()){Player player=find(entry.getKey());if(player==null)continue;double base=pool*entry.getValue()/Math.max(1,eligibleDamage),share=Math.round(base*plugin.progress().mobIncomeMultiplier(player)*100)/100.0;plugin.creditEarned(entry.getKey(),share,label.toUpperCase(Locale.ROOT).replace(' ','_'));db.recordEconomy(entry.getKey(),label.equals("world boss")?"BOSS":"ELITE",share,label);CoreUtil.msg(player,"Your "+label+" damage earned "+CoreUtil.money(share)+".");}}
    private void thematicLoot(EntityDeathEvent e, String tier) { LivingEntity mob = e.getEntity();if(tier.equals("legendary")){legendaryLoot(e);return;} int bonus=switch(tier){case"epic"->3;case"miniboss"->2;case"rare"->1;default->0;}; if (mob instanceof Creeper) { e.getDrops().add(new ItemStack(Material.TNT, 5 + bonus * 2)); e.getDrops().add(new ItemStack(Material.GUNPOWDER, 4 + bonus * 3)); } else if (mob instanceof Spider) { e.getDrops().add(spiderPotion()); e.getDrops().add(new ItemStack(Material.FERMENTED_SPIDER_EYE, 1 + bonus)); if (Math.random() < .35 + bonus * .1) e.getDrops().add(new ItemStack(Material.COBWEB, 1 + bonus)); } else if (mob instanceof Enderman) { if (Math.random() < .72 + bonus * .05) e.getDrops().add(new ItemStack(Material.ENDER_EYE)); e.getDrops().add(new ItemStack(Material.ENDER_PEARL, 2 + bonus * 2)); } else if (mob instanceof AbstractSkeleton) { e.getDrops().add(new ItemStack(Material.SPECTRAL_ARROW, 8 + bonus * 8)); } else if (mob instanceof Zombie) { e.getDrops().add(new ItemStack(Material.IRON_INGOT, 2 + bonus * 2)); if (Math.random() < .25 + bonus * .1) e.getDrops().add(new ItemStack(Material.GOLDEN_APPLE)); }
        if(mob.getWorld().getEnvironment()==World.Environment.NETHER){e.getDrops().add(new ItemStack(Material.MAGMA_CREAM,1+bonus));if(Math.random()<.08+bonus*.06)e.getDrops().add(new ItemStack(Material.ANCIENT_DEBRIS));}else if(mob.getWorld().getEnvironment()==World.Environment.THE_END){e.getDrops().add(new ItemStack(Material.ENDER_PEARL,3+bonus*2));if(Math.random()<.06+bonus*.08)e.getDrops().add(new ItemStack(Material.SHULKER_SHELL));}
        double sigilChance=tier.equals("epic")?.5:tier.equals("miniboss")?.35:tier.equals("rare")?.15:.05;if(Math.random()<sigilChance)e.getDrops().add(sigil());
        if(tier.equals("legendary")&&Math.random()<bosses.getDouble("legendary-sigil-drop-chance",.08))e.getDrops().add(legendarySigil());
    }
    private ItemStack legendarySigil(){ItemStack item=CoreUtil.named(Material.NETHER_STAR,"Legendary Sigil",List.of("A rarer offering to the Keeper of Omens."));ItemMeta meta=item.getItemMeta();meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"legendary_sigil"),PersistentDataType.BYTE,(byte)1);item.setItemMeta(meta);return item;}
    private void legendaryLoot(EntityDeathEvent event){
        LivingEntity mob=event.getEntity();List<ItemStack> drops=event.getDrops();
        if(mob instanceof Enderman enderman){drops.add(legendaryShulkerBox(mob));drops.add(new ItemStack(Material.ENDER_PEARL,24));/** The block it was visibly carrying drops here instead of being placeable in the world — it can be
         *  looted, just never used to grief a farm (see endermanBlockChange). */
        if(enderman.getCarriedBlock()!=null&&!enderman.getCarriedBlock().getMaterial().isAir())drops.add(new ItemStack(enderman.getCarriedBlock().getMaterial()));return;}
        if(mob instanceof Creeper){drops.add(new ItemStack(Material.TNT,32));drops.add(new ItemStack(Material.GUNPOWDER,32));drops.add(new ItemStack(Material.END_CRYSTAL,4));drops.add(strongBook());if(Math.random()<.20)drops.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE));return;}
        if(mob instanceof Spider){drops.add(legendarySpiderPotion());drops.add(legendarySpiderPotion());drops.add(new ItemStack(Material.COBWEB,16));drops.add(new ItemStack(Material.FERMENTED_SPIDER_EYE,8));drops.add(special(Material.DIAMOND_BOOTS,"Silkstrider Boots",Enchantment.FEATHER_FALLING,4));if(Math.random()<.35)drops.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE));return;}
        if(mob instanceof AbstractSkeleton){drops.add(new ItemStack(Material.SPECTRAL_ARROW,64));drops.add(strongBook());drops.add(new ItemStack(Material.DIAMOND,ThreadLocalRandom.current().nextInt(5,10)));if(Math.random()<.45)drops.add(new ItemStack(Material.NETHERITE_SCRAP,2));return;}
        if(mob instanceof Piglin||mob instanceof Hoglin){drops.add(new ItemStack(Material.GOLD_BLOCK,8));drops.add(new ItemStack(Material.NETHERITE_SCRAP,ThreadLocalRandom.current().nextInt(2,5)));drops.add(new ItemStack(Material.GOLDEN_APPLE,4));drops.add(strongBook());return;}
        drops.add(new ItemStack(Material.DIAMOND,ThreadLocalRandom.current().nextInt(5,10)));drops.add(new ItemStack(Material.NETHERITE_SCRAP,ThreadLocalRandom.current().nextInt(1,4)));drops.add(new ItemStack(Material.GOLDEN_APPLE,3));drops.add(strongBook());if(Math.random()<.12)drops.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE));
    }
    private Material randomShulkerMaterial(){List<Material> boxes=List.of(Material.WHITE_SHULKER_BOX,Material.ORANGE_SHULKER_BOX,Material.MAGENTA_SHULKER_BOX,Material.LIGHT_BLUE_SHULKER_BOX,Material.YELLOW_SHULKER_BOX,Material.LIME_SHULKER_BOX,Material.PINK_SHULKER_BOX,Material.GRAY_SHULKER_BOX,Material.LIGHT_GRAY_SHULKER_BOX,Material.CYAN_SHULKER_BOX,Material.PURPLE_SHULKER_BOX,Material.BLUE_SHULKER_BOX,Material.BROWN_SHULKER_BOX,Material.GREEN_SHULKER_BOX,Material.RED_SHULKER_BOX,Material.BLACK_SHULKER_BOX);return boxes.get(ThreadLocalRandom.current().nextInt(boxes.size()));}
    private ItemStack legendaryShulkerBox(LivingEntity mob){
        String stored=mob.getPersistentDataContainer().get(legendaryLootKey,PersistentDataType.STRING);Material material=stored==null?randomShulkerMaterial():Material.matchMaterial(stored);if(material==null||!material.name().endsWith("SHULKER_BOX"))material=randomShulkerMaterial();
        ItemStack item=new ItemStack(material);BlockStateMeta meta=(BlockStateMeta)item.getItemMeta();if(meta.getBlockState() instanceof ShulkerBox box){box.getInventory().addItem(new ItemStack(Material.DIAMOND,ThreadLocalRandom.current().nextInt(5,10)),strongBook(),new ItemStack(Material.GOLDEN_APPLE,ThreadLocalRandom.current().nextInt(2,5)),new ItemStack(Material.SHULKER_SHELL,ThreadLocalRandom.current().nextInt(2,5)),new ItemStack(Material.END_CRYSTAL,ThreadLocalRandom.current().nextInt(2,5)),new ItemStack(Material.CHORUS_FRUIT,16),new ItemStack(Material.EXPERIENCE_BOTTLE,ThreadLocalRandom.current().nextInt(16,33)));if(Math.random()<.50)box.getInventory().addItem(highQualityGear());if(Math.random()<.12)box.getInventory().addItem(new ItemStack(Material.TOTEM_OF_UNDYING));if(Math.random()<.05)box.getInventory().addItem(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE));meta.setBlockState(box);}item.setItemMeta(meta);return item;
    }
    private ItemStack strongBook(){ItemStack book=new ItemStack(Material.ENCHANTED_BOOK);EnchantmentStorageMeta meta=(EnchantmentStorageMeta)book.getItemMeta();List<Map.Entry<Enchantment,Integer>> options=List.of(Map.entry(Enchantment.MENDING,1),Map.entry(Enchantment.PROTECTION,4),Map.entry(Enchantment.SHARPNESS,5),Map.entry(Enchantment.POWER,5),Map.entry(Enchantment.UNBREAKING,3));Map.Entry<Enchantment,Integer> selected=options.get(ThreadLocalRandom.current().nextInt(options.size()));meta.addStoredEnchant(selected.getKey(),selected.getValue(),true);book.setItemMeta(meta);return book;}
    private ItemStack highQualityGear(){Material material=List.of(Material.DIAMOND_SWORD,Material.DIAMOND_PICKAXE,Material.DIAMOND_CHESTPLATE,Material.DIAMOND_BOOTS).get(ThreadLocalRandom.current().nextInt(4));Enchantment enchant=material==Material.DIAMOND_SWORD?Enchantment.SHARPNESS:material==Material.DIAMOND_PICKAXE?Enchantment.EFFICIENCY:material==Material.DIAMOND_BOOTS?Enchantment.FEATHER_FALLING:Enchantment.PROTECTION;return special(material,"",enchant,material==Material.DIAMOND_SWORD?5:4);}
    private ItemStack sigil(){ItemStack item=CoreUtil.named(Material.ECHO_SHARD,"Elite Sigil",List.of("Accepted by the Keeper of Omens."));ItemMeta meta=item.getItemMeta();meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"elite_sigil"),PersistentDataType.BYTE,(byte)1);item.setItemMeta(meta);return item;}
    private void capsuleDrop(EntityDeathEvent event,String tier){VillagerCapsuleService service=plugin.capsules();if(service==null)return;double disposable=bosses.getDouble("capsule-drops."+tier+".disposable",isWorldBossTier(tier)?.15:tier.equals("legendary")?.08:tier.equals("miniboss")?.04:0),reusable=bosses.getDouble("capsule-drops."+tier+".reusable",isWorldBossTier(tier)?.01:tier.equals("legendary")?.006:tier.equals("miniboss")?.002:0);double roll=Math.random();if(roll<reusable)event.getDrops().add(service.empty(true));else if(roll<reusable+disposable)event.getDrops().add(service.empty(false));}
    private ItemStack legendarySpiderPotion(){ItemStack potion=spiderPotion();PotionMeta meta=(PotionMeta)potion.getItemMeta();meta.addCustomEffect(new PotionEffect(PotionEffectType.RESISTANCE,2400,0),true);potion.setItemMeta(meta);return potion;}
    private ItemStack spiderPotion() { ItemStack potion = new ItemStack(Material.POTION); PotionMeta meta = (PotionMeta) potion.getItemMeta(); meta.displayName(Component.text("Silkstep Draught", NamedTextColor.LIGHT_PURPLE)); meta.addCustomEffect(new PotionEffect(PotionEffectType.SPEED, 3600, 1), true); meta.addCustomEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 3600, 0), true); potion.setItemMeta(meta); return potion; }
    private void rewardWorldBoss(EntityDeathEvent e, Player killer, Map<String,Double> participants, WorldBossKind kind) {
        String tier = tierFor(kind);
        switch(kind){
            case ASHEN_KNIGHT -> {
                e.getDrops().add(new ItemStack(Material.NETHERITE_SCRAP, ThreadLocalRandom.current().nextInt(2, 5))); e.getDrops().add(ashenTrophy());e.getDrops().add(cinderforgedBlade());capsuleDrop(e,tier);
                mintSignatureRelic(killer,participants,"crown_of_ash");
                mintSignatureRelic(killer,participants,"ashen_reprisal");
            }
            case IRON_GOLEM -> {
                e.getDrops().add(new ItemStack(Material.IRON_BLOCK, bosses.getInt("iron-golem-boss.reward-iron-blocks", 12))); e.getDrops().add(CoreUtil.named(Material.IRON_INGOT, "Colossus Core Fragment", List.of("Proof of victory over the Warded Colossus.")));e.getDrops().add(colossusBoots());capsuleDrop(e,tier);
                if(db.state("iron_golem_first_clear")==null){db.state("iron_golem_first_clear",Long.toString(System.currentTimeMillis()));Player recipient=fairRecipient(killer,participants);if(recipient!=null){for(int i=0;i<5;i++)CoreUtil.give(recipient,plugin.capsules().empty(false));CoreUtil.msg(recipient,"First-clear bonus: 5 Disposable Villager Capsules.");broadcastNotice(Component.text("✦ "+plugin.nicknames().displayName(recipient)+" claimed the first Warded Colossus victory.",NamedTextColor.GRAY));}}
                mintSignatureRelic(killer,participants,"colossus_core");
            }
            case PIGLIN_BRUTE -> {
                e.getDrops().add(new ItemStack(Material.GOLD_BLOCK, bosses.getInt("piglin-brute-boss.reward-gold-blocks", 10))); e.getDrops().add(CoreUtil.named(Material.GOLDEN_HELMET, "Warlord's Trophy Helm", List.of("Proof of victory over the Cinder Warlord.")));capsuleDrop(e,tier);
                double axeChance=bosses.getDouble("piglin-brute-boss.excavator-drop-chance",.18);
                for(String participant:participants.keySet()){Player player=find(participant);if(player==null)continue;if(Math.random()<axeChance)CoreUtil.give(player,plugin.shards().excavatorPickaxe(Math.random()<.5));}
                mintSignatureRelic(killer,participants,"warlords_ember");
            }
        }
        celebrateWorldBoss(killer,e.getEntity().getLocation(),kind);if (worldBossId != null) db.deleteBossState(worldBossId.toString()); worldBossId = null; hintStage = 0; nextHintAt = 0; if (eventType == EventType.WORLD_BOSS || eventType == EventType.HUNT) finishEvent(true);
    }
    /** Guaranteed, visually distinct per-boss trophies/gear — not just money — so a kill always feels like
     *  it produced something worth having beyond the ledger. The old Ashen Knight "trophy" was a plain
     *  renamed Wither Skeleton Skull, indistinguishable from an ordinary mob drop; this gives it real glint
     *  and lore plus a genuinely usable weapon. */
    private ItemStack ashenTrophy(){ItemStack item=new ItemStack(Material.WITHER_SKELETON_SKULL);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text("Ashen Knight's Crown",NamedTextColor.GOLD));meta.lore(List.of(Component.text("Proof of victory over the Ashen Knight.",NamedTextColor.GRAY),Component.text("Radiates a faint soulfire glow.",NamedTextColor.DARK_GRAY)));meta.addEnchant(Enchantment.UNBREAKING,1,true);meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);item.setItemMeta(meta);return item;}
    private ItemStack cinderforgedBlade(){ItemStack item=new ItemStack(Material.NETHERITE_SWORD);item.addUnsafeEnchantment(Enchantment.SHARPNESS,5);item.addUnsafeEnchantment(Enchantment.FIRE_ASPECT,2);item.addUnsafeEnchantment(Enchantment.LOOTING,3);item.addUnsafeEnchantment(Enchantment.UNBREAKING,3);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text("Cinderforged Blade",NamedTextColor.RED));meta.lore(List.of(Component.text("Forged in the Ashen Knight's dying flame.",NamedTextColor.GRAY)));item.setItemMeta(meta);return item;}
    private ItemStack colossusBoots(){ItemStack item=new ItemStack(Material.NETHERITE_BOOTS);item.addUnsafeEnchantment(Enchantment.FEATHER_FALLING,4);item.addUnsafeEnchantment(Enchantment.PROTECTION,3);item.addUnsafeEnchantment(Enchantment.UNBREAKING,3);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text("Colossus-Forged Boots",NamedTextColor.GRAY));meta.lore(List.of(Component.text("Cast from the Warded Colossus's iron heart.",NamedTextColor.DARK_GRAY),Component.text("Falls like nothing can stop you.",NamedTextColor.DARK_GRAY)));item.setItemMeta(meta);return item;}
    /** Boss-exclusive relics (see RelicService) — a single server-wide copy that mints to a participant
     *  whenever eligible. These are deliberately unique mechanics rather than raw stat upgrades, so they
     *  stay below ShardShop's max-enchant gear in value while being something no amount of vanilla survival
     *  play could ever produce. Recipient selection is randomized by damage share across everyone who
     *  meaningfully fought (not just the finishing blow) so a shared kill feels fair, and is always
     *  broadcast so the whole server sees who won it and why. */
    private Player fairRecipient(Player killer,Map<String,Double> participants){
        Map<String,Double> online=new LinkedHashMap<>();
        for(var entry:participants.entrySet()){Player p=find(entry.getKey());if(p!=null)online.put(entry.getKey(),Math.max(0,entry.getValue()));}
        double total=online.values().stream().mapToDouble(Double::doubleValue).sum();
        if(online.isEmpty()||total<=0)return killer;
        double roll=ThreadLocalRandom.current().nextDouble()*total,cursor=0;
        for(var entry:online.entrySet()){cursor+=entry.getValue();if(roll<=cursor)return find(entry.getKey());}
        return killer;
    }
    private void mintSignatureRelic(Player killer,Map<String,Double> participants,String relicKey){
        Player recipient=fairRecipient(killer,participants);
        if(recipient==null||!relics.mint(relicKey,CoreUtil.id(recipient),recipient.getName()))return;
        CoreUtil.give(recipient,relics.create(relicKey));
        broadcastNotice(Component.text("✦ "+plugin.nicknames().displayName(recipient)+" claimed the "+relics.displayName(relicKey)+" from the shared victory.",NamedTextColor.LIGHT_PURPLE));
        plugin.progress().relicFound(recipient,relics.displayName(relicKey));
    }
    private double random(double min, double max) { if (max <= min) return min; return Math.round(ThreadLocalRandom.current().nextDouble(min, max) * 100) / 100.0; }

    void markCurer(Player player, ZombieVillager zombie) { zombie.getPersistentDataContainer().set(curerKey, PersistentDataType.STRING, CoreUtil.id(player) + "|" + player.getName()); }
    void onTransform(EntityTransformEvent e) {
        /** setImmuneToZombification(true) alone turned out not to reliably stop a Piglin Brute world boss from
         *  converting to a plain Zombified Piglin on this Paper build (confirmed via a second live reproduction
         *  after that fix was already deployed) — the entity would silently get replaced by an untracked mob
         *  with none of its tags, which is exactly what read as the boss "vanishing" again. This is the hard
         *  guarantee: any entity we're actively tracking (world boss or any elite tier) must never be allowed
         *  to transform into something else, for any reason, full stop. */
        if (e.getEntity().getPersistentDataContainer().has(tierKey)) { e.setCancelled(true); return; }
        if (e.getTransformReason() != EntityTransformEvent.TransformReason.CURED) return; String value = e.getEntity().getPersistentDataContainer().get(curerKey, PersistentDataType.STRING); if (value == null) return; String[] parts = value.split("\\|", 2); String id = parts[0]; String stateKey = "cure:" + id + ":" + LocalDate.now(); int count = (int) parseLong(db.state(stateKey), 0), cap = bosses.getInt("cure-zombie-villager.max-per-day", 2); if (count >= cap) return; double reward = bosses.getDouble("cure-zombie-villager.reward", 1000); db.state(stateKey, Integer.toString(count + 1)); plugin.creditEarned(id,reward,"ZOMBIE_VILLAGER_CURE");db.recordEconomy(id,"MILESTONE",reward,"ZOMBIE_VILLAGER_CURE"); Player player = find(id); if (player != null) { CoreUtil.msg(player, "You restored a villager and earned " + CoreUtil.money(reward) + "."); player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.2f); } }

    boolean startEvent(EventType requested, Location location) {return startEvent(requested,defaultTier(requested),location,Origin.ADMIN_SUMMONED,null);}
    boolean startEvent(EventType requested, Location location,Origin origin) {return startEvent(requested,defaultTier(requested),location,origin,null);}
    boolean startEvent(EventType requested, Location location,Origin origin,WorldBossKind kind) {return startEvent(requested,defaultTier(requested),location,origin,kind);}
    boolean startEvent(EventType requested,EventTier selectedTier,Location location,Origin origin) { return startEvent(requested,selectedTier,location,origin,null); }
    boolean startEvent(EventType requested,EventTier selectedTier,Location location,Origin origin,WorldBossKind requestedKind) { EventType type = requested == EventType.HUNT ? EventType.WORLD_BOSS : requested; WorldBossKind kind = type==EventType.WORLD_BOSS ? (requestedKind!=null?requestedKind:randomWorldBossKind()) : null;if(type==EventType.KOTH&&origin!=Origin.ADMIN_SUMMONED&&onlineFactionCount()<(selectedTier==EventTier.RARE?3:2))return false; if(type==EventType.WORLD_BOSS&&worldBoss()!=null){if(origin==Origin.NATURAL)postponeNaturalWorldBoss(selectedTier,"another world boss is still active");return false;}
        /** One player-summoned world boss may run ALONGSIDE one natural non-boss event -- a paid summon
         *  should not be silently eaten just because a Treasure Drop happens to be running. It runs as a
         *  standalone encounter (see standaloneBossEnds) and deliberately does NOT take the event slot, so
         *  the natural event's own state, timers and rewards are left completely untouched. Two world
         *  bosses at once remain impossible via the check above. */
        boolean standaloneBoss=false;
        if(eventType!=null){
            if(type==EventType.WORLD_BOSS&&origin!=Origin.NATURAL&&eventType!=EventType.WORLD_BOSS)standaloneBoss=true;
            else{if(type==EventType.WORLD_BOSS&&origin==Origin.NATURAL)postponeNaturalWorldBoss(selectedTier,"a "+eventType+" event is still running");return false;}
        } World world = type==EventType.WORLD_BOSS?worldFor(kind):overworld(); if (world == null) return false;Location selected=location!=null?location:type==EventType.WORLD_BOSS?randomSafeBossSpawn(world,bosses.getInt(configPrefix(kind)+".spawn-radius-min",1200),bosses.getInt(configPrefix(kind)+".spawn-radius-max",4000)):selectedTier==EventTier.MICRO?randomSafe(world,200,650):randomSafe(world,type==EventType.KOTH?500:800,type==EventType.KOTH?2000:3500);
        if(selected==null&&type==EventType.WORLD_BOSS){if(origin==Origin.NATURAL)postponeNaturalWorldBoss(selectedTier,"no open terrain far enough from protected land");return false;}if(selected==null||!world.equals(selected.getWorld())||protectedEventLocation(selected))return false; if(standaloneBoss){
            LivingEntity solo=spawnWorldBoss(selected,origin,kind);
            if(solo==null)return false;
            standaloneBossEnds=System.currentTimeMillis()+bosses.getLong(configPrefix(kind)+".event-duration-minutes",120)*60000L;
            broadcastWorldEvent("⚔ WORLD BOSS",awakenLine(kind),"Summoned while another event is running — both are live.","Last seen in a "+CoreUtil.pretty(selected.getBlock().getBiome().getKey().getKey())+" biome.");
            persistWorldBoss();
            return true;
        }
        eventType = type;eventTier=selectedTier;eventOrigin=origin;long fallback=selectedTier==EventTier.MICRO?30:selectedTier==EventTier.MAJOR?45:120;long duration=type==EventType.WORLD_BOSS?bosses.getLong(configPrefix(kind)+".event-duration-minutes",120):events.getLong("tiers."+selectedTier.name().toLowerCase(Locale.ROOT)+".duration-minutes",fallback);long fullInterval=origin==Origin.NATURAL?randomRemaining(selectedTier):0;activeTierNextDelay=origin==Origin.NATURAL?Math.max(300000L,fullInterval-duration*60000L):0;eventEnds = System.currentTimeMillis() + duration * 60000L; eventScores.clear(); scoreNames.clear(); eventParticipants.clear(); eventEarnings.clear(); eventCenter = selected;
        switch (type) { case WORLD_BOSS -> { LivingEntity boss = spawnWorldBoss(eventCenter,origin,kind); if (boss == null){clearFailedEvent();return false;}eventCenter = boss.getLocation(); } case ELITE_HUNT -> { String eliteTier=selectedTier==EventTier.MICRO?"rare":selectedTier==EventTier.MAJOR?"miniboss":"legendary";LivingEntity elite = spawnElite(eliteTier, eventCenter); if (elite != null) { elite.getPersistentDataContainer().set(eventEliteKey, PersistentDataType.BYTE, (byte) 1); eventCenter = elite.getLocation(); } broadcastWorldEvent("⚔ WORLD EVENT • ELITE HUNT", "Track down and defeat the marked "+CoreUtil.pretty(eliteTier)+".", locationLine()); } case RESOURCE_RUSH -> broadcastWorldEvent("⛏ WORLD EVENT • RESOURCE RUSH", "Mine ores to earn money during the event!", "Qualifying natural ores count anywhere."); case KOTH -> broadcastWorldEvent("♜ WORLD EVENT • KING OF THE HILL", "Hold the center for your faction until time expires.", locationLine() + " • claims remain protected"); case TREASURE -> { placeTreasure(); broadcastWorldEvent("✦ WORLD EVENT • TREASURE DROP", "Follow the tracker and open the hidden cache.", "Biome: " + CoreUtil.pretty(eventCenter.getBlock().getBiome().getKey().getKey()) + " • rough X " + rough(eventCenter.getBlockX(), 250) + ", Z " + rough(eventCenter.getBlockZ(), 250)); } default -> { } }
        if(origin==Origin.NATURAL){rememberNatural(type);scheduledEvents.put(selectedTier,chooseNatural(selectedTier));}
        for (Player p : plugin.getServer().getOnlinePlayers()) if(plugin.settings().bossNotifications(p))CoreUtil.msg(p, "Use /events for instructions or /events track off to disable navigation."); persistEvent();persistEventTimers(); return true;
    }
    private EventTier defaultTier(EventType type){return type==EventType.WORLD_BOSS||type==EventType.KOTH?EventTier.RARE:EventTier.MAJOR;}
    private int eventInt(String path,int fallback){return events.getInt("tiers."+eventTier.name().toLowerCase(Locale.ROOT)+"."+path,events.getInt(path,fallback));}
    private double eventDouble(String path,double fallback){return events.getDouble("tiers."+eventTier.name().toLowerCase(Locale.ROOT)+"."+path,events.getDouble(path,fallback));}
    private void clearFailedEvent(){eventType=null;eventCenter=null;eventEnds=0;activeTierNextDelay=0;eventScores.clear();eventParticipants.clear();eventOrigin=Origin.NATURAL;db.state("current_event","");}
    private String locationLine() { return "Destination: X " + eventCenter.getBlockX() + ", Z " + eventCenter.getBlockZ(); }
    /** Every event's coordinate origin — deliberately never a player's location. Anchoring on a random
     *  online player (the old randomSafeNearPlayer behavior for MICRO events) let repeated events
     *  statistically triangulate someone's base, which is exactly the leak this anchors away from. */
    private Location eventAnchor(World world){Location configured=plugin.teleports().spawn();if(configured!=null&&configured.getWorld()!=null&&configured.getWorld().equals(world))return configured;return world.getSpawnLocation();}
    private Location randomSafe(World world, int min, int max) { Location anchor=eventAnchor(world); for (int i = 0; i < 48; i++) { double angle = Math.random() * Math.PI * 2; int distance = ThreadLocalRandom.current().nextInt(min, max + 1); int x=anchor.getBlockX()+(int)Math.round(Math.cos(angle)*distance),z=anchor.getBlockZ()+(int)Math.round(Math.sin(angle)*distance); Location loc = CoreUtil.findSafeAny(world, x, z); if (loc != null && !protectedEventLocation(loc)) return loc; } return null; }
    /** World-boss placement, deliberately stricter than generic event placement. A wild boss must never
     *  land near anyone's protected land, so this enforces a large explicit standoff
     *  (world-boss-spawn.min-distance-from-protected, default 2000) measured against every spawn region and
     *  faction claim, and additionally requires genuinely open terrain -- a solid floor with real headroom --
     *  so a boss never materialises inside a cave, a ravine wall, or a one-block pocket it then has to
     *  smash its way out of. The search is hard-bounded by max-attempts; if nothing qualifies it returns
     *  null and the caller POSTPONES the event rather than forcing a bad spawn. */
    private Location randomSafeBossSpawn(World world,int min,int max){
        Location anchor=eventAnchor(world);
        int attempts=Math.max(8,bosses.getInt("world-boss-spawn.max-attempts",64));
        double standoff=Math.max(0,bosses.getDouble("world-boss-spawn.min-distance-from-protected",2000));
        for(int i=0;i<attempts;i++){
            double angle=Math.random()*Math.PI*2;
            int distance=ThreadLocalRandom.current().nextInt(min,max+1);
            int x=anchor.getBlockX()+(int)Math.round(Math.cos(angle)*distance),z=anchor.getBlockZ()+(int)Math.round(Math.sin(angle)*distance);
            Location loc=CoreUtil.findSafeAny(world,x,z);
            if(loc==null||protectedEventLocation(loc))continue;
            if(standoff>0&&(plugin.spawnClaims().near(loc,(int)standoff)||factions.nearClaim(loc,(int)standoff)))continue;
            if(!openBossTerrain(loc))continue;
            return loc;
        }
        return null;
    }
    /** Solid ground underfoot plus continuous clear air above -- rejects caves and cramped pockets. */
    private boolean openBossTerrain(Location loc){
        int headroom=Math.max(2,bosses.getInt("world-boss-spawn.required-headroom",6));
        if(!loc.clone().subtract(0,1,0).getBlock().getType().isSolid())return false;
        for(int dy=0;dy<headroom;dy++){
            Block b=loc.clone().add(0,dy,0).getBlock();
            if(!b.getType().isAir()&&!b.isLiquid())return false;
        }
        return true;
    }
    /** Keeps a natural world boss SCHEDULED (rather than swapping it for another event type, which is what
     *  the generic failure path does) and simply tries again later -- "postpone", per spec. */
    private void postponeNaturalWorldBoss(EventTier tier,String why){
        long delay=Math.max(1,bosses.getLong("world-boss-spawn.postpone-minutes",10))*60000L;
        scheduledEvents.put(tier,EventType.WORLD_BOSS);
        eventRemaining.put(tier,delay);
        persistEventTimers();
        plugin.getLogger().info("[WorldBoss] Natural world boss postponed "+(delay/60000L)+"m: "+why);
    }
    private int eventProtectionRadius(){return Math.max(100,plugin.getConfig().getInt("events.protected-area-radius",100));}
    private boolean protectedEventLocation(Location location){int radius=eventProtectionRadius();return plugin.spawnClaims().near(location,radius)||factions.nearClaim(location,radius);}
    /** Every tier now gets the Ashfall Cache Trophy (previously MICRO-only excluded) plus a tier-appropriate
     *  guaranteed item beyond the scaled gems, so the barrel itself — not just whoever happens to find it
     *  fastest — carries real value against the purchase price: MICRO gets gold blocks, MAJOR gets a Totem of
     *  Undying, RARE gets a Totem of Undying too (its prior sole differentiator) plus the biggest gem scale.
     *  Audited: MICRO was $8,000 for a $2,500-or-nothing gamble with no guaranteed floor worth mentioning;
     *  RARE was ~13% of price back even in the best case. Both now have a real guaranteed reward, not just a
     *  lucky-finder cash chance, while staying a net loss overall (still a money sink by design). */
    private void placeTreasure() { Block block = eventCenter.getBlock(); block.setType(Material.BARREL, false); if (block.getState() instanceof Container container) { container.getPersistentDataContainer().set(treasureKey, PersistentDataType.BYTE, (byte) 1); int scale=eventTier==EventTier.MICRO?1:eventTier==EventTier.MAJOR?2:4;container.getInventory().addItem(new ItemStack(Material.DIAMOND, scale), new ItemStack(Material.EMERALD, 6*scale), new ItemStack(Material.GOLDEN_APPLE, scale));container.getInventory().addItem(CoreUtil.named(Material.NAUTILUS_SHELL, "Ashfall Cache Trophy", List.of("Found during an Ashfall Treasure Drop.")));if(eventTier==EventTier.MICRO)container.getInventory().addItem(new ItemStack(Material.GOLD_BLOCK,4));else container.getInventory().addItem(new ItemStack(Material.TOTEM_OF_UNDYING)); container.update(true); } }
    void onTreasureInteract(PlayerInteractEvent e) { if (eventType != EventType.TREASURE || e.getClickedBlock() == null) return; if (!(e.getClickedBlock().getState() instanceof Container container) || !container.getPersistentDataContainer().has(treasureKey)) return; double reward = eventDouble("treasure.finder-money",1500); plugin.creditEarned(CoreUtil.id(e.getPlayer()),reward,"TREASURE");db.recordEconomy(CoreUtil.id(e.getPlayer()),"EVENT",reward,"TREASURE"); db.incrementStat(CoreUtil.id(e.getPlayer()), "event_wins"); plugin.progress().eventWon(e.getPlayer(), "Treasure Drop"); broadcastNotice(Component.text("✦ " + plugin.nicknames().displayName(e.getPlayer()) + " found the Ashfall cache and earned " + CoreUtil.money(reward) + "!", NamedTextColor.GOLD)); finishEvent(true); }
    void onResourceBreak(Player p, Block block) { if (eventType != EventType.RESOURCE_RUSH) return; String name = block.getType().name(); if (!(name.endsWith("_ORE") || name.equals("ANCIENT_DEBRIS"))) return; double base=eventDouble("resource-rush.ore-money",8),reward=Math.round(base*plugin.progress().mobIncomeMultiplier(p)*100)/100.0; plugin.creditEarned(CoreUtil.id(p),reward,"RESOURCE_RUSH");db.recordEconomy(CoreUtil.id(p),"EVENT",reward,"RESOURCE_RUSH");eventEarnings.merge(CoreUtil.id(p),reward,Double::sum);eventScores.merge(CoreUtil.id(p),1,Integer::sum);scoreNames.put(CoreUtil.id(p),p.getName()); p.sendActionBar(Component.text("+" + CoreUtil.money(reward) + " Resource Rush", NamedTextColor.AQUA)); if (eventParticipants.add(CoreUtil.id(p))){db.incrementStat(CoreUtil.id(p), "event_participations");plugin.progress().eventParticipated(p);} if (Math.random() < eventDouble("resource-rush.double-drop-chance",.12)) block.getDrops(p.getInventory().getItemInMainHand(), p).forEach(item -> block.getWorld().dropItemNaturally(block.getLocation(), item)); }
    private boolean sameArea(Location loc, double radius) { return eventCenter != null && loc.getWorld().equals(eventCenter.getWorld()) && Math.pow(loc.getX() - eventCenter.getX(), 2) + Math.pow(loc.getZ() - eventCenter.getZ(), 2) <= radius * radius; }

    private void startTasks() {
        long now=System.currentTimeMillis();
        String history=db.state("event_recent_history");
        if(history!=null)for(String raw:history.split(","))try{recentNaturalEvents.add(EventType.valueOf(raw));}catch(Exception ignored){}
        while(recentNaturalEvents.size()>2)recentNaturalEvents.removeFirst();
        for(EventTier tier:EventTier.values()){
            String suffix=tier.name().toLowerCase(Locale.ROOT);
            long stored=parseLong(db.state("event_remaining_"+suffix),0);
            eventRemaining.put(tier,stored>0?stored:randomRemaining(tier));
            try{scheduledEvents.put(tier,EventType.valueOf(db.state("event_scheduled_"+suffix)));}catch(Exception ignored){scheduledEvents.put(tier,chooseNatural(tier));}
        }
        lastEventTimerTick=now;lastTimerPersist=now;
        ticker=plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,100L,100L);
        visuals=plugin.getServer().getScheduler().runTaskTimer(plugin,this::visualTick,20L,20L);
    }
    private void tick() {
        long now=System.currentTimeMillis();persistWorldBoss();tickHints(now);tickEventTimers(now);refreshSummonScrollLore();
        /** A standalone (player-summoned) boss has no event slot to expire it, so it is timed out here. */
        if(standaloneBossEnds>0){
            if(worldBoss()==null)standaloneBossEnds=0;
            else if(now>=standaloneBossEnds){standaloneBossEnds=0;despawnWorldBoss();}
        }
        if(worldBossId==null&&forcedBossChunkSet)releaseBossChunk();
        if(eventType==EventType.WORLD_BOSS&&worldBoss()==null&&worldBossChunkObservedEmpty()){plugin.getLogger().warning("World boss event had no boss entity; recovering event state.");finishEvent(false);return;}
        if(eventType!=null){if(now>=eventEnds)finishEvent(false);else if(eventType==EventType.KOTH)tickKoth();return;}
        if(!plugin.getConfig().getBoolean("events.automatic",true))return;
        for(EventTier tier:List.of(EventTier.RARE,EventTier.MAJOR,EventTier.MICRO)){
            if(eventRemaining.getOrDefault(tier,Long.MAX_VALUE)>0||!enoughPlayers(tier))continue;
            EventType type=scheduledEvents.computeIfAbsent(tier,this::chooseNatural);
            if(startEvent(type,tier,null,Origin.NATURAL))break;
            scheduledEvents.put(tier,chooseNatural(tier));eventRemaining.put(tier,300_000L);
        }
    }
    private void tickEventTimers(long now){long elapsed=Math.max(0,Math.min(30_000,now-lastEventTimerTick));lastEventTimerTick=now;if(!plugin.getServer().getOnlinePlayers().isEmpty())for(EventTier tier:EventTier.values())eventRemaining.compute(tier,(key,value)->Math.max(0,(value==null?randomRemaining(tier):value)-elapsed));if(now-lastTimerPersist>=60_000){persistEventTimers();lastTimerPersist=now;}}
    private boolean enoughPlayers(EventTier tier){long active=plugin.getServer().getOnlinePlayers().stream().filter(player->player.getGameMode()!=GameMode.SPECTATOR).count();return active>=events.getInt("tiers."+tier.name().toLowerCase(Locale.ROOT)+".minimum-online-players",1);}
    private EventType chooseNatural(EventTier tier){
        List<EventType> choices=new ArrayList<>(switch(tier){case MICRO->List.of(EventType.TREASURE,EventType.RESOURCE_RUSH,EventType.ELITE_HUNT);case MAJOR->onlineFactionCount()>=2?List.of(EventType.RESOURCE_RUSH,EventType.ELITE_HUNT,EventType.KOTH):List.of(EventType.RESOURCE_RUSH,EventType.ELITE_HUNT,EventType.TREASURE);case RARE->onlineFactionCount()>=3?List.of(EventType.WORLD_BOSS,EventType.KOTH,EventType.TREASURE):List.of(EventType.WORLD_BOSS,EventType.TREASURE);});
        List<EventType> preferred=choices.stream().filter(type->!recentNaturalEvents.contains(type)).toList();
        if(!preferred.isEmpty())choices=new ArrayList<>(preferred);
        else if(recentNaturalEvents.peekLast()!=null&&choices.size()>1)choices.remove(recentNaturalEvents.peekLast());
        return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }
    private void rememberNatural(EventType type){recentNaturalEvents.addLast(type);while(recentNaturalEvents.size()>2)recentNaturalEvents.removeFirst();db.state("event_recent_history",recentNaturalEvents.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(",")));}
    private int onlineFactionCount(){Set<Long> ids=new HashSet<>();for(Player player:plugin.getServer().getOnlinePlayers()){if(player.getGameMode()==GameMode.SPECTATOR)continue;Database.FactionRow faction=db.factionOf(CoreUtil.id(player));if(faction!=null)ids.add(faction.id());}return ids.size();}
    private long randomRemaining(EventTier tier){String path="tiers."+tier.name().toLowerCase(Locale.ROOT);long fallbackMin=tier==EventTier.MICRO?1:tier==EventTier.MAJOR?12:72,fallbackMax=tier==EventTier.MICRO?4:tier==EventTier.MAJOR?36:120;long min=Math.max(1,events.getLong(path+".interval-active-hours-min",fallbackMin)),max=Math.max(min,events.getLong(path+".interval-active-hours-max",fallbackMax));return ThreadLocalRandom.current().nextLong(min*3600000L,max*3600000L+1);}
    private void persistEventTimers(){for(EventTier tier:EventTier.values()){String suffix=tier.name().toLowerCase(Locale.ROOT);db.state("event_remaining_"+suffix,Long.toString(eventRemaining.getOrDefault(tier,randomRemaining(tier))));EventType type=scheduledEvents.get(tier);if(type!=null)db.state("event_scheduled_"+suffix,type.name());}}
    private void visualTick() {eliteIds.removeIf(id->{Entity entity=plugin.getServer().getEntity(id);if(!(entity instanceof LivingEntity mob)||!mob.isValid()){removeHealthBar(id);return true;}String tier=mob.getPersistentDataContainer().get(tierKey,PersistentDataType.STRING);if(expireElite(mob,tier))return true;mob.setGlowing(true);String ability=mob.getPersistentDataContainer().get(abilityKey,PersistentDataType.STRING);Particle particle="FLAMEBOUND".equals(ability)||"VOLATILE".equals(ability)||"INFERNAL_RIFT".equals(ability)||"CINDERLORD".equals(ability)?Particle.FLAME:"STORMCALLER".equals(ability)||"FROSTBITE".equals(ability)?Particle.ELECTRIC_SPARK:"VAMPIRIC".equals(ability)||"VENOMOUS".equals(ability)?Particle.DAMAGE_INDICATOR:"PHASEWALKER".equals(ability)||"VOID_TETHER".equals(ability)?Particle.PORTAL:"COLOSSAL".equals(ability)?Particle.CRIT:Particle.ENCHANT;if(isWorldBossTier(tier)){WorldBossKind kind=kindFromTier(tier);scaleWorldBoss(mob);worldBossMechanic(mob,kind);worldBossRegen(mob,kind);worldBossSoftEnrage(mob,kind);worldBossElementTick(mob,kind);worldBossTargetTick(mob);worldBossSwimTick(mob,kind);worldBossUnreachableTick(mob,tier);trackBossChunk(mob.getLocation());}else if("epic".equals(tier)||"legendary".equals(tier))eliteMechanic(mob,tier);int count=isWorldBossTier(tier)?14:"legendary".equals(tier)?12:"epic".equals(tier)||"miniboss".equals(tier)?8:"rare".equals(tier)?5:3;settingsParticle(mob.getLocation().add(0,1,0),particle,count,.5,.7,.5,.01);updateHealthBar(mob,tier);antiCheeseTick(mob,tier);return false;});sharedBossIds.removeIf(id->{Entity entity=plugin.getServer().getEntity(id);if(!(entity instanceof LivingEntity boss)||!boss.isValid()){damage.remove(id);lastContribution.remove(id);return true;}if(!isWorldBossTier(boss.getPersistentDataContainer().get(tierKey,PersistentDataType.STRING)))scaleSharedBoss(boss);return false;});}
    private void settingsParticle(Location location,Particle particle,int baseCount,double offsetX,double offsetY,double offsetZ,double extra){
        if(location.getWorld()==null)return;
        for(Player viewer:location.getWorld().getPlayers()){
            if(viewer.getLocation().distanceSquared(location)>128*128)continue;
            double scale=plugin.settings().particleScale(viewer);
            if(scale<=.2&&plugin.getServer().getCurrentTick()%20!=0)continue;
            viewer.spawnParticle(particle,location,Math.max(1,(int)Math.round(baseCount*scale)),offsetX,offsetY,offsetZ,extra);
        }
    }
    private boolean expireElite(LivingEntity mob,String tier){if(tier==null||isWorldBossTier(tier))return false;PersistentDataContainer pdc=mob.getPersistentDataContainer();long now=System.currentTimeMillis(),spawned=pdc.getOrDefault(eliteSpawnedAtKey,PersistentDataType.LONG,now);if(!pdc.has(eliteSpawnedAtKey,PersistentDataType.LONG))pdc.set(eliteSpawnedAtKey,PersistentDataType.LONG,spawned);long fallback=switch(tier){case"legendary"->120;case"epic"->75;case"miniboss"->60;case"rare"->45;default->30;};long lifetime=Math.max(1,bosses.getLong("tiers."+tier+".despawn-minutes",fallback))*60000L;long grace=Math.max(30,bosses.getLong("elite-despawn.combat-grace-seconds",120))*1000L;if(now-lastEngaged.getOrDefault(mob.getUniqueId(),0L)<grace)return false;double radius=Math.max(16,bosses.getDouble("elite-despawn.nearby-player-radius",32)),radiusSq=radius*radius;
        /** Expiry is driven by CONTINUOUS ABANDONMENT, not absolute age. The old order — "older than the tier
         *  lifetime?" first, then "is anyone within 32 blocks right now?" — meant a long-lived Epic/Legendary
         *  vanished the instant the last player stepped out of range, which is why they appeared to disappear
         *  just for briefly leaving the area. Now any eligible player nearby continuously refreshes the timer,
         *  and the elite only expires after elite-despawn.abandoned-minutes of nobody being around at all.
         *  Hostile-Mobs-Off removal is deliberately untouched by this and still runs through its own separate
         *  sweep (SettingsService.removableHostile), so an unwanted elite still disappears normally there. */
        UUID id=mob.getUniqueId();
        boolean anyoneNear=false;
        for(Player player:plugin.getServer().getOnlinePlayers())
            if(player.getGameMode()!=GameMode.SPECTATOR&&player.getWorld().equals(mob.getWorld())&&player.getLocation().distanceSquared(mob.getLocation())<=radiusSq){anyoneNear=true;break;}
        if(anyoneNear){eliteLastPlayerNear.put(id,now);return false;}
        long abandonedSince=eliteLastPlayerNear.computeIfAbsent(id,key->Math.max(spawned,now-lifetime));
        long abandonWindow=Math.max(1,bosses.getLong("elite-despawn.abandoned-minutes",30))*60000L;
        if(now-abandonedSince<abandonWindow)return false;
        eliteLastPlayerNear.remove(id);mob.getWorld().spawnParticle(Particle.SMOKE,mob.getLocation().add(0,1,0),20,.5,.7,.5,.02);mob.remove();damage.remove(id);lastContribution.remove(id);abilityCooldown.remove(id);catchupCooldown.remove(id);lastEngaged.remove(id);healCooldown.remove(id);bossMechanicAt.remove(id);lastTarget.remove(id);rangedHits.remove(id);removeHealthBar(id);return true;}
    private void scaleWorldBoss(LivingEntity boss){long now=System.currentTimeMillis(),window=bosses.getLong("world-boss.active-seconds",60)*1000L;double radiusSq=Math.pow(bosses.getDouble("world-boss.active-radius",50),2);Map<String,Long> hits=lastContribution.getOrDefault(boss.getUniqueId(),Map.of());int active=0;for(var entry:hits.entrySet()){Player player=find(entry.getKey());if(player!=null&&player.getWorld().equals(boss.getWorld())&&now-entry.getValue()<=window&&player.getLocation().distanceSquared(boss.getLocation())<=radiusSq)active++;}active=Math.max(1,active);if(active==worldBossActiveCount)return;double oldMax=Math.max(1,boss.getAttribute(Attribute.MAX_HEALTH).getValue()),percent=Math.max(0.0001,boss.getHealth()/oldMax),base=worldBossBaseHealth>0?worldBossBaseHealth:bosses.getDouble("world-boss.health",18000),newMax=clampHealth(base*healthMultiplier(active));boss.getAttribute(Attribute.MAX_HEALTH).setBaseValue(newMax);boss.setHealth(Math.max(1,Math.min(newMax,newMax*percent)));worldBossActiveCount=active;persistWorldBoss();}
    /** No player nearby for 60+ continuous seconds → slow trickle of health back, instead of the fight
     *  resetting to full the moment someone disengages. Deliberately gentle (a full recovery from empty takes
     *  minutes of total abandonment) so retreating briefly to heal up doesn't erase real progress on the boss,
     *  but a genuinely abandoned fight doesn't stay wounded forever either. */
    private void worldBossRegen(LivingEntity boss,WorldBossKind kind){
        UUID id=boss.getUniqueId();long now=System.currentTimeMillis();
        double radius=bosses.getDouble(configPrefix(kind)+".active-radius",50);
        boolean nearby=false;for(Entity entity:boss.getNearbyEntities(radius,radius,radius))if(entity instanceof Player p&&p.getGameMode()!=GameMode.SPECTATOR){nearby=true;break;}
        if(nearby){lastNearbyAt.put(id,now);return;}
        long since=lastNearbyAt.getOrDefault(id,now);
        if(now-since<60_000L)return;
        double max=boss.getAttribute(Attribute.MAX_HEALTH).getValue();if(boss.getHealth()>=max)return;
        if(now-healCooldown.getOrDefault(id,0L)<5000)return;healCooldown.put(id,now);
        double regen=max*bosses.getDouble(configPrefix(kind)+".disengage-regen-percent",.01);
        boss.setHealth(Math.min(max,boss.getHealth()+regen));
        boss.getWorld().spawnParticle(Particle.SOUL,boss.getLocation().add(0,1,0),16,.6,.8,.6,.02);
    }
    /** Luring a world boss into water/lava must never make the fight easier — each boss instead gets
     *  measurably STRONGER in its "wrong" element, so pulling it there is a straight disadvantage for
     *  players rather than a way to stall a slow/awkward mover in a corner. Deliberately independent of
     *  antiCheeseTick's "engaged" tracking (unlike the Piglin Brute lava-speed hack below) — the buff applies
     *  purely off the boss's own physical state, refreshed every visualTick (~1s) pass with a duration a
     *  little longer than that interval so a moment of tick jitter never lets it lapse early. */
    /** Anti-trap / anti-height-cheese, replacing the removed catch-up teleport. A boss that has an engaged
     *  target it genuinely cannot path to for world-boss-unreachable.seconds first tries to free ITSELF —
     *  clearing obstructing blocks beside and above its own body only, never beneath it (digging down would
     *  bury it and could drop it into the void) and never through protected, permanent or special blocks.
     *  If it is still stuck and the target is above it, it makes a single controlled leap whose vertical
     *  impulse is derived from the real height gap, so a pillar of any height is reachable without ever
     *  teleporting. Both paths are cooldown-limited so this can't become a continuous terrain shredder. */
    private void worldBossUnreachableTick(LivingEntity boss,String tier){
        if(!(boss instanceof Mob mob))return;
        UUID id=boss.getUniqueId();long now=System.currentTimeMillis();
        Player target=mob.getTarget() instanceof Player p?p:null;
        if(target==null||!validBossTarget(target)||!target.getWorld().equals(boss.getWorld())){bossUnreachableSince.remove(id);return;}
        /** "Engaged" specifically — this must never fire on a boss that simply hasn't been found yet. */
        if(!lastEngaged.containsKey(id)){bossUnreachableSince.remove(id);return;}
        boolean reachable=mob.getPathfinder().findPath(target.getLocation())!=null&&mob.hasLineOfSight(target);
        if(reachable){bossUnreachableSince.remove(id);return;}
        long stuckFor=now-bossUnreachableSince.computeIfAbsent(id,key->now);
        if(stuckFor<(long)(bosses.getDouble("world-boss-unreachable.seconds",3)*1000))return;
        if(now-bossLeapCooldown.getOrDefault(id,0L)<(long)(bosses.getDouble("world-boss-unreachable.leap-cooldown-seconds",4)*1000))return;
        bossLeapCooldown.put(id,now);
        int cleared=clearAroundAndAbove(boss);
        double dy=target.getLocation().getY()-boss.getLocation().getY();
        if(dy>1.5)launchBossAt(boss,target,dy);
        else if(cleared>0)boss.getWorld().playSound(boss.getLocation(),Sound.ENTITY_IRON_GOLEM_ATTACK,1.4f,.6f);
        if(cleared>0||dy>1.5)mob.getPathfinder().moveTo(target,1.2);
    }
    /** The leap, done properly. Two things made the first attempt useless in practice:
     *  1) the impulse was far too small — Minecraft's ~0.08 b/tick^2 gravity with ~0.98 drag needs roughly
     *     v = 0.098*sqrt(h) + 0.36 to clear h blocks, so a 20-block tower needs ~1.3 and a 40-block one
     *     ~1.6, but the old formula also capped everything at 3.2 while producing ~2.2 at 20 blocks; and
     *  2) far more importantly, a Mob's own movement/pathfinding overwrites setVelocity on the very next
     *     tick, so most of the impulse was simply erased before it did anything.
     *  This solves the impulse from the real gap with no artificial ceiling on how high it may go, then
     *  re-applies the upward component for a few ticks while the boss is still rising, which is what
     *  actually lets it clear a tall pillar instead of hopping. */
    private void launchBossAt(LivingEntity boss,Player target,double dy){
        double vertical=Math.max(bosses.getDouble("world-boss-unreachable.leap-min-vertical",.8),.098*Math.sqrt(Math.max(0,dy))+.36);
        double cap=bosses.getDouble("world-boss-unreachable.leap-max-vertical",0);
        if(cap>0)vertical=Math.min(cap,vertical);
        Vector toward=target.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        Vector horizontal=toward.lengthSquared()>0.0001?toward.normalize().multiply(bosses.getDouble("world-boss-unreachable.leap-horizontal",.9)):new Vector();
        final double up=vertical;
        boss.setVelocity(horizontal.clone().setY(up));
        boss.getWorld().playSound(boss.getLocation(),Sound.ENTITY_IRON_GOLEM_ATTACK,1.4f,.5f);
        boss.getWorld().spawnParticle(Particle.CLOUD,boss.getLocation(),30,.5,.1,.5,.03);
        target.sendActionBar(Component.text("It launches itself at you — height won't save you.",NamedTextColor.RED));
        UUID id=boss.getUniqueId();
        int sustain=Math.max(0,bosses.getInt("world-boss-unreachable.leap-sustain-ticks",6));
        for(int t=1;t<=sustain;t++){
            final int tick=t;
            plugin.getServer().getScheduler().runTaskLater(plugin,()->{
                Entity live=plugin.getServer().getEntity(id);
                if(!(live instanceof LivingEntity flying)||!flying.isValid())return;
                /** Only while still ascending — never turns into hovering or flight. */
                if(flying.getVelocity().getY()<=0.01)return;
                double decay=1.0-(tick/(double)(sustain+1));
                flying.setVelocity(horizontal.clone().setY(Math.max(flying.getVelocity().getY(),up*decay)));
            },t);
        }
    }
    /** Clears obstructing blocks in a small box centred on the boss, from its own feet upward only.
     *  Never touches anything at or below foot level, and reuses breakableShelterBlock() so bedrock,
     *  portals/gateways, other permanent or special blocks, and all protected land stay untouchable. */
    private int clearAroundAndAbove(LivingEntity boss){
        int radius=Math.max(1,bosses.getInt("world-boss-unreachable.radius",2));
        int height=Math.max(1,bosses.getInt("world-boss-unreachable.height",3));
        int max=Math.max(1,bosses.getInt("world-boss-unreachable.max-blocks",10));
        Block feet=boss.getLocation().getBlock();int cleared=0;
        for(int dy=0;dy<=height&&cleared<max;dy++)
            for(int dx=-radius;dx<=radius&&cleared<max;dx++)
                for(int dz=-radius;dz<=radius&&cleared<max;dz++){
                    if(dx==0&&dz==0&&dy==0)continue;
                    Block block=feet.getRelative(dx,dy,dz);
                    if(!breakableShelterBlock(block))continue;
                    block.breakNaturally();cleared++;
                }
        if(cleared>0)boss.getWorld().spawnParticle(Particle.BLOCK,boss.getLocation().add(0,1,0),18,.6,.6,.6,.02,Material.STONE.createBlockData());
        return cleared;
    }
    /** Water turns the Colossus (and to a lesser degree the Knight) into a drifting, circling mess: vanilla
     *  land pathfinding keeps steering toward a floor that isn't there, so it orbits the target instead of
     *  closing. Giving it real swim capability plus a direct nudge toward the target while submerged makes
     *  it actually pursue underwater; the element buffs from the previous pass then make water a genuinely
     *  bad place to fight it rather than a safe one. */
    private void worldBossSwimTick(LivingEntity boss,WorldBossKind kind){
        if(!(boss instanceof Mob mob))return;
        Player target=mob.getTarget() instanceof Player p?p:null;
        if(target==null||!target.getWorld().equals(boss.getWorld()))return;
        boolean submerged=boss.isInWater();
        double distSq=boss.getLocation().distanceSquared(target.getLocation());
        /** The circling had one root cause: land pathfinding. In water there is no walkable floor to path
         *  along, so the navigator keeps recomputing a route it can never follow and the boss drifts in
         *  circles; on land the same thing happens whenever the target is somewhere unreachable. Issuing
         *  ANOTHER moveTo() every tick (what the previous attempt did) actively made it worse — each call
         *  resets the path mid-execution, so the mob restarts its route every tick and never commits to a
         *  direction. So: on land, re-path only occasionally and let vanilla execute it; in water, abandon
         *  pathfinding entirely and steer directly, which is the only thing that reliably closes distance. */
        if(!submerged){
            UUID id=boss.getUniqueId();long now=System.currentTimeMillis();
            if(now-bossRepathAt.getOrDefault(id,0L)>=900){
                bossRepathAt.put(id,now);
                if(distSq>4)mob.getPathfinder().moveTo(target,1.1);
            }
            return;
        }
        Vector toward=target.getEyeLocation().toVector().subtract(boss.getEyeLocation().toVector());
        if(toward.lengthSquared()<0.04)return;
        Vector swim=toward.normalize().multiply(bosses.getDouble("world-boss-swim.speed",.32));
        /** Direct steering, blended with current motion so it reads as swimming rather than teleport-drift,
         *  and buoyancy that cancels the sink-to-the-bottom case without granting flight. */
        Vector velocity=boss.getVelocity().multiply(.55).add(swim);
        if(velocity.getY()<0)velocity.setY(Math.max(velocity.getY(),-.03));
        boss.setVelocity(velocity);
        boss.setRotation((float)Math.toDegrees(Math.atan2(-toward.getX(),toward.getZ())),boss.getLocation().getPitch());
    }
    private void worldBossElementTick(LivingEntity boss,WorldBossKind kind){
        switch(kind){
            case IRON_GOLEM -> {if(boss.isInWater()){boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,30,2,false,false));boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,30,1,false,false));boss.getWorld().spawnParticle(Particle.BUBBLE,boss.getLocation().add(0,1,0),12,.5,.5,.5,.02);}}
            case ASHEN_KNIGHT -> {if(boss.isInWater()){boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,30,2,false,false));boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,30,1,false,false));boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,30,1,false,false));boss.getWorld().spawnParticle(Particle.BUBBLE,boss.getLocation().add(0,1,0),12,.5,.5,.5,.02);}}
            case PIGLIN_BRUTE -> {if(boss.isInLava()){boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,30,2,false,false));boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,30,1,false,false));boss.getWorld().spawnParticle(Particle.LAVA,boss.getLocation().add(0,1,0),8,.5,.5,.5,0);}}
        }
    }
    /** Anti-stall safety net, not a core mechanic: a group that's well past the intended 8-12 minute fight
     *  (world-boss-enrage.after-minutes) makes the boss hit gradually and modestly harder in fixed, capped
     *  steps rather than all at once — announced once so it's never a silent, unfair spike, and capped
     *  (max-stages * damage-bonus-per-stage) so an under-geared attempt still can't become an unavoidable
     *  one-shot machine. A fight finishing on time never triggers this at all. */
    private void worldBossSoftEnrage(LivingEntity boss,WorldBossKind kind){
        if(boss.getAttribute(Attribute.ATTACK_DAMAGE)==null)return;
        /** Enrage is an anti-stall measure for a fight that is DRAGGING ON, so it has to be measured from
         *  when the fight actually started, not from when the boss spawned. Keyed off spawn time it would
         *  (and did) fire on a boss no one had even found yet — including one that failed to reveal at all —
         *  broadcasting "grows restless" to the whole server with no fight in progress. A boss that has never
         *  been damaged now never enrages, and the ramp freezes if nobody has hit it for a while, so walking
         *  away and coming back later doesn't hand the boss free permanent damage stages. */
        UUID bossId=boss.getUniqueId();
        Long lastHit=lastEngaged.get(bossId);
        if(lastHit==null){
            if(enrageStageApplied.getOrDefault(bossId,0)!=0){enrageStageApplied.put(bossId,0);boss.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(bosses.getDouble(configPrefix(kind)+".damage",18));}
            return;
        }
        bossFirstEngagedAt.putIfAbsent(bossId,lastHit);
        long stallMillis=Math.max(15,bosses.getLong("world-boss-enrage.combat-idle-freeze-seconds",120))*1000L;
        if(System.currentTimeMillis()-lastHit>stallMillis)return;
        double elapsedMinutes=(System.currentTimeMillis()-bossFirstEngagedAt.get(bossId))/60000.0,afterMinutes=bosses.getDouble("world-boss-enrage.after-minutes",14);
        int stage=0;
        if(elapsedMinutes>=afterMinutes){
            double rampMinutes=Math.max(.5,bosses.getDouble("world-boss-enrage.ramp-interval-minutes",2));
            int maxStages=Math.max(0,bosses.getInt("world-boss-enrage.max-stages",5));
            stage=Math.min(maxStages,1+(int)((elapsedMinutes-afterMinutes)/rampMinutes));
        }
        int previous=enrageStageApplied.getOrDefault(boss.getUniqueId(),0);
        if(stage==previous)return;
        enrageStageApplied.put(boss.getUniqueId(),stage);
        double baseDamage=bosses.getDouble(configPrefix(kind)+".damage",18),bonus=bosses.getDouble("world-boss-enrage.damage-bonus-per-stage",.08);
        boss.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(baseDamage*(1+stage*bonus));
        if(previous==0&&stage>0){
            boss.getWorld().playSound(boss.getLocation(),Sound.ENTITY_WITHER_AMBIENT,1f,.5f);
            broadcastNotice(Component.text("☠ "+displayName(kind)+" grows restless — the fight has dragged on. Finish it quickly!",NamedTextColor.RED));
        }
    }
    private void ensureSharedBase(LivingEntity boss){PersistentDataContainer pdc=boss.getPersistentDataContainer();if(!pdc.has(sharedBaseHealthKey,PersistentDataType.DOUBLE))pdc.set(sharedBaseHealthKey,PersistentDataType.DOUBLE,Math.max(1,boss.getAttribute(Attribute.MAX_HEALTH).getValue()));if(!pdc.has(sharedActiveCountKey,PersistentDataType.INTEGER))pdc.set(sharedActiveCountKey,PersistentDataType.INTEGER,1);}
    private void scaleSharedBoss(LivingEntity boss){ensureSharedBase(boss);int active=activeParticipantCount(boss),previous=boss.getPersistentDataContainer().getOrDefault(sharedActiveCountKey,PersistentDataType.INTEGER,1);if(active==previous)return;double oldMax=Math.max(1,boss.getAttribute(Attribute.MAX_HEALTH).getValue()),percent=Math.max(.0001,boss.getHealth()/oldMax),base=boss.getPersistentDataContainer().getOrDefault(sharedBaseHealthKey,PersistentDataType.DOUBLE,oldMax),newMax=clampHealth(base*healthMultiplier(active));boss.getAttribute(Attribute.MAX_HEALTH).setBaseValue(newMax);boss.setHealth(Math.max(1,Math.min(newMax,newMax*percent)));boss.getPersistentDataContainer().set(sharedActiveCountKey,PersistentDataType.INTEGER,active);}
    private int activeParticipantCount(LivingEntity boss){long now=System.currentTimeMillis(),window=bosses.getLong("boss-participation.active-seconds",120)*1000L;double radiusSq=Math.pow(bosses.getDouble("boss-participation.active-radius",50),2);int active=0;for(var entry:lastContribution.getOrDefault(boss.getUniqueId(),Map.of()).entrySet()){Player player=find(entry.getKey());if(player!=null&&player.getWorld().equals(boss.getWorld())&&now-entry.getValue()<=window&&player.getLocation().distanceSquared(boss.getLocation())<=radiusSq)active++;}return Math.max(1,active);}
    /** Softened from an earlier 1/1.6/2.1/2.5/+.3-per-extra curve: a real messy group fight (players dying
     *  and re-engaging rather than sustaining clean parallel DPS) empirically ran over an hour even after
     *  cutting base HP, because the multiplier alone assumed perfect uptime scaling that doesn't hold up in
     *  practice. Group fights still scale up, just not steeply enough to turn into a multi-hour slog. */
    private double healthMultiplier(int players){if(players<=1)return 1;if(players==2)return 1.35;if(players==3)return 1.6;if(players==4)return 1.8;return 1.8+(players-4)*.15;}
    private static final double ENGINE_MAX_HEALTH=1024.0;
    private double clampHealth(double intended){return Math.max(1,Math.min(ENGINE_MAX_HEALTH,intended));}
    /** Minecraft's MAX_HEALTH attribute cannot exceed ENGINE_MAX_HEALTH. Big configured pools are represented
     *  by displaying a clamped bar while dividing incoming damage by this factor, so the fight lasts as long
     *  as the configured number implies without ever setting an out-of-range attribute value. */
    private double toughnessFor(LivingEntity mob,String tier){
        double intendedMax;
        if(isWorldBossTier(tier)) intendedMax=Math.max(1,worldBossBaseHealth)*healthMultiplier(worldBossActiveCount);
        else if(mob!=null&&sharedBossIds.contains(mob.getUniqueId())){double base=mob.getPersistentDataContainer().getOrDefault(sharedBaseHealthKey,PersistentDataType.DOUBLE,mob.getAttribute(Attribute.MAX_HEALTH).getValue());int active=mob.getPersistentDataContainer().getOrDefault(sharedActiveCountKey,PersistentDataType.INTEGER,1);intendedMax=base*healthMultiplier(active);}
        else return 1;
        return Math.max(1,intendedMax/ENGINE_MAX_HEALTH);
    }
    boolean bossHealthSafetySelfTest(){return clampHealth(999999)==ENGINE_MAX_HEALTH&&clampHealth(10)==10&&bosses.getDouble("elite-health-cap",1000)<=ENGINE_MAX_HEALTH&&toughnessFor(null,"worldboss")>=1;}
    /** Guards the world-boss-enrage safety net against a fat-fingered config edit turning it into either a
     *  no-op (after-minutes/ramp too low, or zero stages) or a runaway one-shot machine (bonus*maxStages too
     *  high). 60% total damage bonus at full enrage is the intended ceiling. */
    boolean worldBossRebalanceSelfTest(){
        double afterMinutes=bosses.getDouble("world-boss-enrage.after-minutes",14),rampMinutes=bosses.getDouble("world-boss-enrage.ramp-interval-minutes",2),perStage=bosses.getDouble("world-boss-enrage.damage-bonus-per-stage",.08);
        int maxStages=bosses.getInt("world-boss-enrage.max-stages",5);
        boolean enrageSane=afterMinutes>=10&&rampMinutes>=.5&&perStage>0&&perStage*maxStages<=.6&&maxStages>=1;
        boolean healthSane=bosses.getDouble("world-boss.health",0)>3000&&bosses.getDouble("iron-golem-boss.health",0)>4000&&bosses.getDouble("piglin-brute-boss.health",0)>2500;
        return enrageSane&&healthSane;
    }
    /** World-boss identity helpers. Legacy "worldboss" (no suffix, pre-Batch-2 saves) is treated as Ashen Knight. */
    private boolean isWorldBossTier(String tier){return tier!=null&&(tier.equals("worldboss")||tier.startsWith("worldboss_"));}
    private String tierFor(WorldBossKind kind){return switch(kind){case ASHEN_KNIGHT->"worldboss_ashen";case IRON_GOLEM->"worldboss_iron";case PIGLIN_BRUTE->"worldboss_piglin";};}
    private WorldBossKind kindOf(String raw){try{return raw==null?WorldBossKind.ASHEN_KNIGHT:WorldBossKind.valueOf(raw);}catch(IllegalArgumentException ignored){return WorldBossKind.ASHEN_KNIGHT;}}
    private WorldBossKind kindFromTier(String tier){if(tier==null)return WorldBossKind.ASHEN_KNIGHT;return switch(tier){case"worldboss_iron"->WorldBossKind.IRON_GOLEM;case"worldboss_piglin"->WorldBossKind.PIGLIN_BRUTE;default->WorldBossKind.ASHEN_KNIGHT;};}
    private String configPrefix(WorldBossKind kind){return switch(kind){case ASHEN_KNIGHT->"world-boss";case IRON_GOLEM->"iron-golem-boss";case PIGLIN_BRUTE->"piglin-brute-boss";};}
    String displayName(WorldBossKind kind){return switch(kind){case ASHEN_KNIGHT->bosses.getString("world-boss.name","The Ashen Knight");case IRON_GOLEM->bosses.getString("iron-golem-boss.name","The Warded Colossus");case PIGLIN_BRUTE->bosses.getString("piglin-brute-boss.name","The Cinder Warlord");};}
    private NamedTextColor colorFor(WorldBossKind kind){return switch(kind){case ASHEN_KNIGHT->NamedTextColor.DARK_RED;case IRON_GOLEM->NamedTextColor.GRAY;case PIGLIN_BRUTE->NamedTextColor.GOLD;};}
    boolean scalingSelfTest(){double percent=.37,base=18000;return healthMultiplier(1)==1&&healthMultiplier(2)==1.35&&healthMultiplier(3)==1.6&&healthMultiplier(4)==1.8&&Math.abs(healthMultiplier(7)-2.25)<.0001&&Math.abs(base*healthMultiplier(4)*percent/(base*healthMultiplier(4))-percent)<.000001;}
    boolean eliteTierSelfTest(){double uncommon=bosses.getDouble("natural-elites.uncommon-chance",.0025),rare=bosses.getDouble("natural-elites.rare-chance",.0004),epic=bosses.getDouble("natural-elites.epic-chance",.00006),legendary=bosses.getDouble("natural-elites.legendary-chance",.0000015);return uncommon>rare&&rare>epic&&epic>legendary&&rare/epic>=5&&epic/legendary>=25&&bosses.getDouble("tiers.epic.health-multiplier",0)>bosses.getDouble("tiers.rare.health-multiplier",0)&&bosses.getDouble("tiers.legendary.health-multiplier",0)>bosses.getDouble("tiers.epic.health-multiplier",0)&&bosses.getDouble("tiers.legendary.reward-min",0)>bosses.getDouble("tiers.epic.reward-max",0)&&phaseStage("epic",.29)==2&&phaseStage("legendary",.24)==3;}
    boolean eventTimingSelfTest(){List<Double> silverfish=bosses.getDoubleList("mob-rewards.SILVERFISH");return eventProtectionRadius()>=100&&eventRemaining.size()==3&&eventRemaining.values().stream().allMatch(value->value>=0)&&events.getLong("tiers.micro.interval-active-hours-min",0)>=1&&events.getLong("tiers.major.interval-active-hours-min",0)>=12&&events.getLong("tiers.rare.interval-active-hours-min",0)>=72&&silverfish.size()>=2&&silverfish.get(0)==1&&silverfish.get(1)==1&&mobRewardCoverageSelfTest()&&bosses.getLong("tiers.uncommon.despawn-minutes",0)>=30&&bosses.getLong("tiers.legendary.despawn-minutes",0)>=120;}
    private boolean mobRewardCoverageSelfTest(){Set<EntityType> excluded=Set.of(EntityType.PLAYER,EntityType.ARMOR_STAND,EntityType.MANNEQUIN);return Arrays.stream(EntityType.values()).filter(EntityType::isAlive).filter(EntityType::isSpawnable).filter(type->!excluded.contains(type)).allMatch(type->bosses.getDoubleList("mob-rewards."+type.name()).size()>=2);}
    private void worldBossMechanic(LivingEntity boss,WorldBossKind kind){long now=System.currentTimeMillis(),interval=bosses.getLong(configPrefix(kind)+".mechanic-interval-seconds",15)*1000L;if(now-bossMechanicAt.getOrDefault(boss.getUniqueId(),0L)<interval)return;List<Player> nearby=boss.getNearbyEntities(50,30,50).stream().filter(Player.class::isInstance).map(Player.class::cast).filter(player->lastContribution.getOrDefault(boss.getUniqueId(),Map.of()).containsKey(CoreUtil.id(player))).toList();if(nearby.isEmpty())return;bossMechanicAt.put(boss.getUniqueId(),now);
        switch(kind){
            case ASHEN_KNIGHT -> ashenKnightMechanic(boss,nearby);
            case IRON_GOLEM -> ironGolemMechanic(boss,nearby);
            case PIGLIN_BRUTE -> piglinBruteMechanic(boss,nearby);
        }
    }
    private void ashenKnightMechanic(LivingEntity boss,List<Player> nearby){
        int phase=boss.getPersistentDataContainer().getOrDefault(phaseKey,PersistentDataType.INTEGER,0);
        int move=ThreadLocalRandom.current().nextInt(phase>=1?4:3);
        if(move==0){boss.getWorld().playSound(boss.getLocation(),Sound.ENTITY_BLAZE_SHOOT,1,.6f);boss.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,boss.getLocation().add(0,1,0),120,4,1.5,4,.08);for(Player player:nearby)if(player.getLocation().distanceSquared(boss.getLocation())<=100){player.setFireTicks(Math.max(player.getFireTicks(),100));Vector away=player.getLocation().toVector().subtract(boss.getLocation().toVector());if(away.lengthSquared()>0)player.setVelocity(away.normalize().multiply(1.05).setY(.4));}}
        else if(move==1){Player target=nearby.get(ThreadLocalRandom.current().nextInt(nearby.size()));target.setCooldown(Material.SHIELD,100);target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,80,1));target.sendActionBar(Component.text("The Ashen Knight sunders your guard!",NamedTextColor.RED));boss.getWorld().playSound(target.getLocation(),Sound.ITEM_SHIELD_BREAK,1,.7f);}
        else if(move==2){for(Player player:nearby){Vector pull=boss.getLocation().toVector().subtract(player.getLocation().toVector());if(pull.lengthSquared()>4)player.setVelocity(pull.normalize().multiply(.65).setY(.2));player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,50,0));}boss.getWorld().playSound(boss.getLocation(),Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,1,.6f);}
        else ashenJudgment(boss,nearby);
    }
    /** Telegraphed line strike: ~1.5s wind-up (visible+audible, boss briefly near-immune so it can't be
     *  interrupted out of the telegraph), then a straight blade-line down its target's bearing. Standing in
     *  the line hurts; stepping off it costs nothing. Rewards the dodge with a short bonus-damage window. */
    private void ashenJudgment(LivingEntity boss,List<Player> nearby){
        Player target=nearby.get(ThreadLocalRandom.current().nextInt(nearby.size()));
        Vector direction=target.getLocation().toVector().subtract(boss.getLocation().toVector());if(direction.lengthSquared()==0)direction=new Vector(1,0,0);
        Vector aim=direction.normalize().setY(0).normalize();Location origin=boss.getLocation();UUID id=boss.getUniqueId();
        boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,35,4,false,false));
        boss.getWorld().playSound(boss.getLocation(),Sound.ITEM_TRIDENT_RIPTIDE_3,1.3f,.5f);
        for(Player p:nearby)p.sendActionBar(Component.text("The Ashen Knight raises its blade — get off the line!",NamedTextColor.RED));
        for(double d=1;d<=14;d+=1)boss.getWorld().spawnParticle(Particle.SMOKE,origin.clone().add(aim.clone().multiply(d)).add(0,1,0),3,.15,.15,.15,0);
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{
            Entity live=plugin.getServer().getEntity(id);if(!(live instanceof LivingEntity boss2)||!boss2.isValid())return;
            boss2.removePotionEffect(PotionEffectType.RESISTANCE);
            boss2.getWorld().playSound(origin,Sound.ENTITY_WITHER_BREAK_BLOCK,1.4f,.6f);
            double damage=bosses.getDouble("world-boss.judgment-damage",7);
            for(double d=1;d<=14;d+=1){Location point=origin.clone().add(aim.clone().multiply(d));boss2.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,point.clone().add(0,1,0),8,.35,.35,.35,.02);
                for(Entity entity:boss2.getWorld().getNearbyEntities(point,1.6,2,1.6))if(entity instanceof Player victim){victim.damage(damage,boss2);victim.setFireTicks(Math.max(victim.getFireTicks(),60));}}
            exposedUntil.put(id,System.currentTimeMillis()+3000);
        },30L);
    }
    /** Anti-pillar/anti-box: a wide upward-and-outward slam that dislodges players from towers or boxed-in
     *  positions without touching any blocks, so the fight can never grief a base. */
    private void ironGolemMechanic(LivingEntity boss,List<Player> nearby){
        long now=System.currentTimeMillis();
        if(now-specialAbilityAt.getOrDefault(boss.getUniqueId(),0L)>=bosses.getLong("iron-golem-boss.overload-interval-seconds",35)*1000L){specialAbilityAt.put(boss.getUniqueId(),now);overloadSlam(boss,nearby);return;}
        boss.getWorld().playSound(boss.getLocation(),Sound.ENTITY_IRON_GOLEM_STEP,1.4f,.6f);boss.getWorld().spawnParticle(Particle.EXPLOSION,boss.getLocation().add(0,.2,0),1);boss.getWorld().spawnParticle(Particle.CRIT,boss.getLocation().add(0,.5,0),80,4,.4,4,.05);for(Player player:nearby){double distanceSq=player.getLocation().distanceSquared(boss.getLocation());if(distanceSq>324)continue;boolean pillared=Math.abs(player.getLocation().getX()-boss.getLocation().getX())<2&&Math.abs(player.getLocation().getZ()-boss.getLocation().getZ())<2&&player.getLocation().getY()>boss.getLocation().getY()+3;Vector away=player.getLocation().toVector().subtract(boss.getLocation().toVector());if(away.lengthSquared()==0)away=new Vector(1,0,0);player.setVelocity(away.normalize().multiply(pillared?.2:1.3).setY(pillared?1.4:.85));player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,1));}
    }
    /** The Colossus's core-exposure loop: a long, very obvious wind-up (near-immune, so it can't be
     *  interrupted or skipped) followed by a burst that knocks everyone back and briefly opens a bonus-damage
     *  window. This is the fight's real DPS check — the "tank" HP pool is offset by rewarding players who
     *  survive the slam and immediately punish the opening, rather than a flat unbroken grind. */
    private void overloadSlam(LivingEntity boss,List<Player> nearby){
        UUID id=boss.getUniqueId();
        boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,40,9,false,false));
        boss.getWorld().playSound(boss.getLocation(),Sound.ENTITY_IRON_GOLEM_DAMAGE,1.5f,.4f);
        boss.getWorld().spawnParticle(Particle.CRIT,boss.getLocation().add(0,1,0),150,3,1,3,.15);
        for(Player p:nearby)p.sendActionBar(Component.text("The Colossus is overloading — brace or retreat!",NamedTextColor.GRAY));
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{
            Entity live=plugin.getServer().getEntity(id);if(!(live instanceof LivingEntity boss2)||!boss2.isValid())return;
            boss2.removePotionEffect(PotionEffectType.RESISTANCE);
            boss2.getWorld().playSound(boss2.getLocation(),Sound.ENTITY_GENERIC_EXPLODE,1.5f,.6f);
            boss2.getWorld().spawnParticle(Particle.EXPLOSION,boss2.getLocation(),3);
            double damage=bosses.getDouble("iron-golem-boss.overload-damage",10);
            for(Entity entity:boss2.getNearbyEntities(8,5,8))if(entity instanceof Player p){
                if(p.getLocation().distanceSquared(boss2.getLocation())<=25)p.damage(damage,boss2);
                Vector away=p.getLocation().toVector().subtract(boss2.getLocation().toVector());if(away.lengthSquared()>0)p.setVelocity(away.normalize().multiply(1.4).setY(.7));
            }
            exposedUntil.put(id,System.currentTimeMillis()+5000);
        },40L);
    }
    /** Anti-roof/anti-range: a flaming lunge toward whichever tracked participant is currently hardest to
     *  reach, reusing the same catch-up teleport the shared anti-cheese system already provides. */
    private void piglinBruteMechanic(LivingEntity boss,List<Player> nearby){
        long now=System.currentTimeMillis();
        if(now-specialAbilityAt.getOrDefault(boss.getUniqueId(),0L)>=bosses.getLong("piglin-brute-boss.storm-interval-seconds",25)*1000L){specialAbilityAt.put(boss.getUniqueId(),now);cinderStorm(boss,nearby);return;}
        Player target=nearby.get(ThreadLocalRandom.current().nextInt(nearby.size()));boss.getWorld().playSound(boss.getLocation(),Sound.ENTITY_PIGLIN_BRUTE_AMBIENT,1.2f,.7f);boss.getWorld().spawnParticle(Particle.FLAME,boss.getLocation().add(0,1,0),90,3.5,1.4,3.5,.09);Vector lunge=target.getLocation().toVector().subtract(boss.getLocation().toVector());if(lunge.lengthSquared()>0&&lunge.lengthSquared()<2500)boss.setVelocity(lunge.normalize().multiply(1.2).setY(.5));target.setFireTicks(Math.max(target.getFireTicks(),100));target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,0));target.sendActionBar(Component.text("The Cinder Warlord marks you!",NamedTextColor.GOLD));
    }
    /** Zone-denial: embers telegraph around each nearby player for ~1.75s, then ignite. Punishes standing
     *  still without being an unavoidable "just take the damage" mechanic — stepping out of the marked spots
     *  before ignition avoids it entirely. */
    private void cinderStorm(LivingEntity boss,List<Player> nearby){
        for(Player p:nearby)p.sendActionBar(Component.text("The Cinder Warlord scatters embers — keep moving!",NamedTextColor.GOLD));
        boss.getWorld().playSound(boss.getLocation(),Sound.ENTITY_BLAZE_SHOOT,1.3f,.8f);
        List<Location> zones=new ArrayList<>();
        for(Player p:nearby)for(int i=0;i<2;i++){double angle=Math.random()*Math.PI*2,dist=1+Math.random()*4;zones.add(p.getLocation().clone().add(Math.cos(angle)*dist,0,Math.sin(angle)*dist));}
        for(Location zone:zones)boss.getWorld().spawnParticle(Particle.LAVA,zone,6,.4,.1,.4,0);
        double damage=bosses.getDouble("piglin-brute-boss.storm-damage",4);
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{
            if(!boss.isValid())return;
            for(Location zone:zones){
                if(zone.getWorld()==null)continue;
                zone.getWorld().spawnParticle(Particle.FLAME,zone,25,.6,.3,.6,.03);zone.getWorld().playSound(zone,Sound.BLOCK_FIRE_AMBIENT,.8f,1f);
                for(Entity entity:zone.getWorld().getNearbyEntities(zone,1.3,1.5,1.3))if(entity instanceof Player victim){victim.setFireTicks(Math.max(victim.getFireTicks(),100));victim.damage(damage,boss);}
            }
        },35L);
    }
    private void eliteMechanic(LivingEntity mob,String tier){
        Player target=find(lastTarget.get(mob.getUniqueId()));if(target==null||!target.getWorld().equals(mob.getWorld())||target.getLocation().distanceSquared(mob.getLocation())>2500)return;
        long now=System.currentTimeMillis(),interval=(long)(bosses.getDouble("tiers."+tier+".mechanic-interval-seconds",tier.equals("legendary")?8:11)*1000);if(now-bossMechanicAt.getOrDefault(mob.getUniqueId(),0L)<interval)return;bossMechanicAt.put(mob.getUniqueId(),now);boolean legendary=tier.equals("legendary");
        if(mob instanceof AbstractSkeleton){Vector base=target.getEyeLocation().toVector().subtract(mob.getEyeLocation().toVector()).normalize();for(double spread:new double[]{-.12,0,.12}){Arrow arrow=mob.launchProjectile(Arrow.class,base.clone().rotateAroundY(spread).multiply(legendary?1.7:1.45));arrow.setDamage(legendary?5:3);arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);}target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,legendary?60:35,0));mob.getWorld().playSound(mob.getLocation(),Sound.ENTITY_SKELETON_SHOOT,1,.65f);}
        else if(mob instanceof Spider){Vector leap=target.getLocation().toVector().subtract(mob.getLocation().toVector());if(leap.lengthSquared()>0)mob.setVelocity(leap.normalize().multiply(legendary?1.15:.9).setY(.55));for(Entity nearby:mob.getNearbyEntities(4,3,4))if(nearby instanceof Player player){player.addPotionEffect(new PotionEffect(PotionEffectType.POISON,legendary?100:60,0));player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,50,1));}mob.getWorld().spawnParticle(Particle.SPORE_BLOSSOM_AIR,mob.getLocation().add(0,1,0),35,2,.6,2,.02);}
        else if(mob instanceof Enderman){Location destination=target.getLocation().clone().subtract(target.getLocation().getDirection().multiply(2));if(destination.getBlock().isPassable()&&destination.clone().add(0,1,0).getBlock().isPassable())mob.teleport(destination);target.addPotionEffect(new PotionEffect(legendary?PotionEffectType.LEVITATION:PotionEffectType.DARKNESS,legendary?30:45,0));mob.getWorld().playSound(destination,Sound.ENTITY_ENDERMAN_TELEPORT,1,.55f);}
        else if(mob instanceof Creeper){for(Entity nearby:mob.getNearbyEntities(6,4,6))if(nearby instanceof Player player){Vector away=player.getLocation().toVector().subtract(mob.getLocation().toVector());if(away.lengthSquared()>0)player.setVelocity(away.normalize().multiply(legendary?.9:.65).setY(.35));player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,0));}mob.getWorld().spawnParticle(Particle.SMOKE,mob.getLocation().add(0,1,0),60,2,1,2,.04);mob.getWorld().playSound(mob.getLocation(),Sound.ENTITY_CREEPER_PRIMED,1,.6f);}
        else if(mob instanceof PigZombie||mob instanceof Piglin||mob instanceof Hoglin){Vector charge=target.getLocation().toVector().subtract(mob.getLocation().toVector());if(charge.lengthSquared()>0)mob.setVelocity(charge.normalize().multiply(legendary?1.35:1.05).setY(.42));for(Entity entity:mob.getNearbyEntities(4.5,3,4.5))if(entity instanceof Player player){Vector away=player.getLocation().toVector().subtract(mob.getLocation().toVector());if(away.lengthSquared()>0)player.setVelocity(away.normalize().multiply(legendary?.95:.65).setY(.35));player.setFireTicks(Math.max(player.getFireTicks(),legendary?100:60));}mob.getWorld().spawnParticle(Particle.FLAME,mob.getLocation().add(0,1,0),legendary?70:45,2,.8,2,.04);mob.getWorld().playSound(mob.getLocation(),Sound.ENTITY_HOGLIN_ANGRY,1,legendary?.6f:.8f);}
        else{Vector leap=target.getLocation().toVector().subtract(mob.getLocation().toVector());if(leap.lengthSquared()>0)mob.setVelocity(leap.normalize().multiply(legendary?1.05:.8).setY(.5));target.setCooldown(Material.SHIELD,legendary?70:40);target.sendActionBar(Component.text(legendary?"The legendary foe breaks your guard!":"The epic foe closes the gap!",NamedTextColor.RED));}
    }
    private void registerHealthBar(LivingEntity mob,String tier){if(tier==null||healthBars.containsKey(mob.getUniqueId()))return;BossBar.Color color=isWorldBossTier(tier)||"legendary".equals(tier)?BossBar.Color.RED:"epic".equals(tier)||"miniboss".equals(tier)?BossBar.Color.PURPLE:"rare".equals(tier)?BossBar.Color.BLUE:BossBar.Color.WHITE;healthBars.put(mob.getUniqueId(),BossBar.bossBar(mob.customName()==null?Component.text(CoreUtil.pretty(tier)):mob.customName(),1f,color,BossBar.Overlay.PROGRESS));barViewers.put(mob.getUniqueId(),new HashSet<>());}
    private void updateHealthBar(LivingEntity mob,String tier){registerHealthBar(mob,tier);BossBar bar=healthBars.get(mob.getUniqueId());if(bar==null)return;double max=Math.max(1,mob.getAttribute(Attribute.MAX_HEALTH).getValue());bar.progress((float)Math.max(0,Math.min(1,mob.getHealth()/max)));Component name=mob.customName()==null?Component.text(CoreUtil.pretty(tier)):mob.customName();bar.name(name.append(Component.text("  "+Math.ceil(mob.getHealth())+"/"+Math.ceil(max)+" ❤",NamedTextColor.WHITE)));double radius=bosses.getDouble("health-bars.view-distance",isWorldBossTier(tier)?64:32),radiusSq=radius*radius;Set<UUID> viewers=barViewers.computeIfAbsent(mob.getUniqueId(),x->new HashSet<>());for(Player player:plugin.getServer().getOnlinePlayers()){boolean show=player.getWorld().equals(mob.getWorld())&&player.getLocation().distanceSquared(mob.getLocation())<=radiusSq;if(show&&viewers.add(player.getUniqueId()))player.showBossBar(bar);else if(!show&&viewers.remove(player.getUniqueId()))player.hideBossBar(bar);}}
    private void removeHealthBar(UUID id){BossBar bar=healthBars.remove(id);Set<UUID> viewers=barViewers.remove(id);if(bar!=null&&viewers!=null)for(UUID viewer:viewers){Player player=plugin.getServer().getPlayer(viewer);if(player!=null)player.hideBossBar(bar);}}
    private void antiCheeseTick(LivingEntity mob,String tier){
        Long engaged=lastEngaged.get(mob.getUniqueId());if(engaged==null)return;long now=System.currentTimeMillis();
        /** Fire Resistance (set at spawn) already stops lava damage, but a Piglin Brute still carries vanilla's
         *  hard-coded lava movement penalty regardless of any potion effect or attribute — there's no public
         *  API to remove the actual pathing-through-lava slowdown itself. A strong, continuously-refreshed
         *  Speed effect while genuinely touching lava is the closest achievable "moves quickly through lava"
         *  without NMS goal surgery, which this codebase deliberately doesn't do anywhere else. */
        if(isWorldBossTier(tier)&&mob instanceof PiglinBrute&&mob.isInLava())mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,40,3,false,false));
        if((tier.equals("epic")||tier.equals("legendary"))&&mob.isInsideVehicle()){mob.leaveVehicle();mob.setVelocity(mob.getVelocity().setY(.45));}
        long idle=bosses.getLong("anti-cheese.idle-heal-seconds",12)*1000L;if(!isWorldBossTier(tier)&&now-engaged>=idle&&now-healCooldown.getOrDefault(mob.getUniqueId(),0L)>=5000){healCooldown.put(mob.getUniqueId(),now);double max=mob.getAttribute(Attribute.MAX_HEALTH).getValue(),heal=max*bosses.getDouble("tiers."+tier+".idle-heal-percent",bosses.getDouble("anti-cheese.idle-heal-percent",.05));mob.setHealth(Math.min(max,mob.getHealth()+heal));mob.getWorld().spawnParticle(Particle.SOUL,mob.getLocation().add(0,1,0),20,.6,.8,.6,.02);}
        Player target=find(lastTarget.get(mob.getUniqueId()));if(target==null||!target.getWorld().equals(mob.getWorld()))return;
        double distance=target.getLocation().distance(mob.getLocation()),catchDistance=bosses.getDouble("anti-cheese.catch-up-distance",16),vertical=bosses.getDouble("anti-cheese.vertical-distance",5);
        /** Shelter cheese (e.g. a thin box built around the boss) often keeps `hasLineOfSight` and `distance`
         *  both passing — the player can poke through a gap and get hit back — so line-of-sight alone can't
         *  catch it. A "close but hasn't actually landed a hit in a while" stalemate is the real signature. */
        boolean stalemate=distance<=catchDistance&&now-lastMobHit.getOrDefault(mob.getUniqueId(),0L)>=bosses.getLong("anti-cheese.shelter-stalemate-seconds",8)*1000L;
        boolean blocked=!mob.hasLineOfSight(target)||distance>catchDistance||Math.abs(target.getLocation().getY()-mob.getLocation().getY())>vertical||rangedHits.getOrDefault(mob.getUniqueId(),0)>=bosses.getInt("anti-cheese.ranged-hits-before-catch-up",3);
        if(!blocked&&!stalemate){blockedSince.remove(mob.getUniqueId());if(distance>8)mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,40,isWorldBossTier(tier)||"legendary".equals(tier)?2:1,false,false));return;}
        /** Real vanilla pathfinding toward the target, not a teleport — this is what world bosses were missing
         *  entirely when blocked/out of range: the older catch-up teleport was deliberately restricted to
         *  Enderman only (see below), which left world bosses with nothing but a speed buff when NOT blocked
         *  and the close-range breakout lunge when stalemated, and no help at all for "far away or line of
         *  sight blocked by terrain". Cheap to reissue every second at this cadence. */
        if(isWorldBossTier(tier)&&mob instanceof Mob navigator)navigator.getPathfinder().moveTo(target,1.15);
        /** Shelter-breaking is reserved for actual world bosses ("main bosses") — an uncommon/rare/epic/legendary
         *  elite reaching a stalemate must never start removing player blocks. It still gets the teleport/lunge
         *  catch-up below (no block damage), which is a separate, older kiting-prevention mechanic. */
        if(isWorldBossTier(tier)&&stalemate&&now-breakoutCooldown.getOrDefault(mob.getUniqueId(),0L)>=bosses.getLong("anti-cheese.breakout-cooldown-seconds",12)*1000L){breakoutCooldown.put(mob.getUniqueId(),now);breakout(mob,target);}
        if(!blocked)return;
        long trigger=bosses.getLong("anti-cheese.blocked-trigger-seconds",5)*1000L,since=blockedSince.computeIfAbsent(mob.getUniqueId(),key->now);if(now-since<trigger||now-lastMobHit.getOrDefault(mob.getUniqueId(),0L)<trigger)return;
        long cooldown=bosses.getLong("anti-cheese.catch-up-cooldown-seconds",8)*1000L;if(now-catchupCooldown.getOrDefault(mob.getUniqueId(),0L)<cooldown)return;catchupCooldown.put(mob.getUniqueId(),now);blockedSince.put(mob.getUniqueId(),now);rangedHits.put(mob.getUniqueId(),0);
        /** Catch-up teleporting is an Enderman-only trait now — every other elite type used to get free-range
         *  reposition here with no distance cap at all, which is how a Zombie/Skeleton/Piglin elite that
         *  aggro'd a player once could suddenly appear next to them hundreds of blocks later. */
        if(tier.equals("legendary"))legendaryCounter(mob,target);else if(mob instanceof Enderman)repositionNearTarget(mob,target);
    }
    /** Anti-shelter: a strong lunge toward the target plus controlled clearing of a handful of ordinary
     *  blocks in the boss's path — never claims, spawn protection, or anything bedrock/obsidian-tier — so a
     *  hastily-built box no longer trivializes a fight, without touching legitimate builds or raw boss stats. */
    private void breakout(LivingEntity mob,Player target){
        Vector toward=target.getLocation().toVector().subtract(mob.getLocation().toVector());if(toward.lengthSquared()<=0)return;
        Vector direction=toward.normalize();mob.setVelocity(direction.clone().multiply(1.6).setY(.6));
        int broken=0;
        for(int step=1;step<=3&&broken<6;step++){
            Location probe=mob.getLocation().add(direction.clone().multiply(step));
            for(int dy=0;dy<3&&broken<6;dy++){Block block=probe.clone().add(0,dy,0).getBlock();if(breakableShelterBlock(block)){block.breakNaturally();broken++;}}
        }
        if(broken>0){mob.getWorld().playSound(mob.getLocation(),Sound.ENTITY_IRON_GOLEM_ATTACK,1.5f,.6f);mob.getWorld().spawnParticle(Particle.EXPLOSION,mob.getLocation().add(0,1,0),Math.min(broken,4));target.sendActionBar(Component.text("The boss smashes through your shelter!",NamedTextColor.RED));}
    }
    private boolean breakableShelterBlock(Block block){
        if(block.getType().isAir()||!block.getType().isSolid())return false;
        if(Set.of(Material.BEDROCK,Material.BARRIER,Material.OBSIDIAN,Material.CRYING_OBSIDIAN,Material.REINFORCED_DEEPSLATE,Material.RESPAWN_ANCHOR,Material.SPAWNER,Material.END_PORTAL_FRAME,Material.END_GATEWAY,Material.COMMAND_BLOCK).contains(block.getType()))return false;
        if(plugin.spawnClaims().contains(block.getLocation())||factions.claimAt(block.getLocation())!=null)return false;
        return true;
    }
    private void legendaryCounter(LivingEntity mob,Player target){
        mob.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,60,1,false,false));mob.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,mob.getLocation().add(0,1,0),45,.8,1,.8,.05);
        if(mob instanceof Enderman){repositionNearTarget(mob,target);target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,50,0));}
        else if(mob instanceof AbstractSkeleton){Vector aim=target.getEyeLocation().toVector().subtract(mob.getEyeLocation().toVector()).normalize();for(double spread:new double[]{-.16,-.08,0,.08,.16}){Arrow arrow=mob.launchProjectile(Arrow.class,aim.clone().rotateAroundY(spread).multiply(1.6));arrow.setDamage(4);arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);}}
        else if(mob instanceof Spider){Vector leap=target.getLocation().toVector().subtract(mob.getLocation().toVector());if(leap.lengthSquared()>0)mob.setVelocity(leap.normalize().multiply(1.35).setY(.7));target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,70,1));}
        else if(mob instanceof Creeper){target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,0));mob.getWorld().playSound(mob.getLocation(),Sound.ENTITY_CREEPER_PRIMED,1,.65f);}
        else{Vector pull=mob.getLocation().toVector().subtract(target.getLocation().toVector());if(pull.lengthSquared()>0)target.setVelocity(pull.normalize().multiply(.65).setY(.25));Vector leap=target.getLocation().toVector().subtract(mob.getLocation().toVector());if(leap.lengthSquared()>0)mob.setVelocity(leap.normalize().multiply(1.1).setY(.55));}
        target.sendActionBar(Component.text("The legendary foe counters the unreachable position!",NamedTextColor.RED));
    }
    /** The only remaining catch-up teleport in the elite system — deliberately restricted to Endermen (both by
     *  the instanceof guard here and by every call site above) and capped to a 500-block range so an Enderman
     *  that aggro'd a player long ago can't suddenly appear next to them after they've traveled far away. */
    private void repositionNearTarget(LivingEntity mob,Player target){
        if(!(mob instanceof Enderman))return;
        if(!mob.getWorld().equals(target.getWorld())||mob.getLocation().distance(target.getLocation())>bosses.getDouble("anti-cheese.enderman-teleport-range",500))return;
        Vector direction=target.getLocation().getDirection().setY(0);if(direction.lengthSquared()==0)direction=new Vector(1,0,0);Location destination=target.getLocation().clone().subtract(direction.normalize().multiply(3));destination.setY(target.getLocation().getY());if(!destination.getBlock().isPassable()||!destination.clone().add(0,1,0).getBlock().isPassable())destination=target.getLocation().clone().add(2,0,0);if(destination.getBlock().isPassable()&&destination.clone().add(0,1,0).getBlock().isPassable()){mob.getWorld().spawnParticle(Particle.PORTAL,mob.getLocation().add(0,1,0),40,.7,1,.7,.1);mob.teleport(destination);mob.getWorld().playSound(destination,Sound.ENTITY_ENDERMAN_TELEPORT,.8f,.65f);}}
    /** A ~5s summoning circle: the boss exists and is fully set up immediately (health bar, hints, DB state
     *  all persist as normal), but sits invisible/invulnerable/still while a rotating double-ring of particles
     *  and rising-pitch pulses build up around it, ending in a flash-and-roar "revival" that makes it visible,
     *  vulnerable, and mobile. Gives players a moment to arrive and see the reveal instead of walking into an
     *  already-active boss mid-tick.
     *  World bosses spawn 1200-4000 blocks from anyone (see spawn-radius-min/max), so their chunk almost always
     *  unloads within this window with zero players nearby. getEntity(id) can't resolve an entity in an unloaded
     *  chunk, so without forcing the chunk to stay loaded, the one-shot "reveal" step below would silently never
     *  run — leaving the boss permanently stuck invisible+invulnerable+AI-less+silent (persisted that way in the
     *  chunk's NBT) until a player eventually wanders in and reloads it, exactly matching the "boss never spawned,
     *  just stands there invisible, can't be hit" report. Forcing the chunk load for the buildup, and retrying the
     *  reveal instead of giving up after a single failed lookup, closes both the direct cause and any edge case. */
    private void playSummoningSequence(LivingEntity boss,WorldBossKind kind){
        boss.setInvisible(true);boss.setInvulnerable(true);boss.setAI(false);boss.setSilent(true);
        Location center=boss.getLocation().clone();UUID id=boss.getUniqueId();int totalTicks=140;Particle particle=summoningParticle(kind);
        World world=center.getWorld();int chunkX=center.getBlockX()>>4,chunkZ=center.getBlockZ()>>4;
        world.setChunkForceLoaded(chunkX,chunkZ,true);
        for(int tick=0;tick<=totalTicks;tick+=5){
            int t=tick;double progress=t/(double)totalTicks;
            plugin.getServer().getScheduler().runTaskLater(plugin,()->{
                Entity live=plugin.getServer().getEntity(id);if(!(live instanceof LivingEntity)||!live.isValid())return;
                double angle=Math.toRadians(t*14);
                for(int ring=0;ring<2;ring++){
                    double radius=2.6-ring*.9;
                    for(int k=0;k<8;k++){double a=angle+k*(Math.PI*2/8)*(ring==0?1:-1);Location point=center.clone().add(Math.cos(a)*radius,.15+ring*.35,Math.sin(a)*radius);center.getWorld().spawnParticle(particle,point,1,0,0,0,0);}
                }
                // A third, wider ring slowly rises and closes in as the buildup nears completion — power visibly gathering.
                double outerRadius=Math.max(.5,4.5-progress*4);
                for(int k=0;k<10;k++){double a=Math.toRadians(t*-9)+k*(Math.PI*2/10);center.getWorld().spawnParticle(Particle.CLOUD,center.clone().add(Math.cos(a)*outerRadius,.1+progress*2.4,Math.sin(a)*outerRadius),1,0,.02,0,0);}
                if(t%20==0)center.getWorld().playSound(center,Sound.BLOCK_BEACON_AMBIENT,1.2f,.5f+(float)progress*.6f);
                if(t%20==0)center.getWorld().playSound(center,Sound.BLOCK_ANVIL_LAND,.5f+(float)progress*.5f,.4f);
                if(progress>=.72&&t%15==0){center.getWorld().spawnParticle(particle,center.clone().add(0,1,0),40,1.2,1,1.2,.05);center.getWorld().playSound(center,Sound.ENTITY_WARDEN_HEARTBEAT,1.3f,.7f+(float)progress*.4f);}
            },t);
        }
        revealWorldBoss(id,center,kind,particle,world,chunkX,chunkZ,totalTicks+5L,0);
    }
    private void revealWorldBoss(UUID id,Location center,WorldBossKind kind,Particle particle,World world,int chunkX,int chunkZ,long delay,int attempt){
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{
            Entity live=plugin.getServer().getEntity(id);
            if(!(live instanceof LivingEntity boss2)||!live.isValid()){
                if(attempt<15){revealWorldBoss(id,center,kind,particle,world,chunkX,chunkZ,20L,attempt+1);return;}
                /** Previously this only warned and released the chunk, which left the worst possible state
                 *  behind: the entity keeps the invisible/invulnerable/no-AI/silent flags it was spawned with
                 *  (those are only cleared on a SUCCESSFUL reveal), while worldBossId and the WORLD_BOSS event
                 *  both stay set. The result is an invisible, unkillable boss nobody can fight, blocking every
                 *  new event for the full event duration, and — because soft-enrage used to run purely off
                 *  spawn time — still broadcasting "grows restless" warnings server-wide for a fight that was
                 *  never happening. That is exactly the phantom Cinder Warlord enrage seen with no boss active.
                 *  Tear the whole thing down instead of leaving it half-born. */
                plugin.getLogger().warning("World boss "+id+" could not be revealed after summoning — its chunk never became resolvable; cleaning it up rather than leaving a hidden, unfightable boss and a stuck event.");
                world.setChunkForceLoaded(chunkX,chunkZ,false);
                Entity stuck=plugin.getServer().getEntity(id);
                if(stuck!=null)try{stuck.remove();}catch(Throwable ignored){}
                eliteIds.remove(id);damage.remove(id);lastContribution.remove(id);enrageStageApplied.remove(id);bossFirstEngagedAt.remove(id);eliteLastPlayerNear.remove(id);bossTargetSince.remove(id);bossTargetOutOfRangeSince.remove(id);bossUnreachableSince.remove(id);bossLeapCooldown.remove(id);bossRepathAt.remove(id);lastEngaged.remove(id);removeHealthBar(id);
                try{db.deleteBossState(id.toString());}catch(Throwable ignored){}
                if(id.equals(worldBossId)){
                    worldBossId=null;
                    if(eventType==EventType.WORLD_BOSS)finishEvent(false);
                }
                return;
            }
            boss2.setInvisible(false);boss2.setInvulnerable(false);boss2.setAI(true);boss2.setSilent(false);
            /** Cosmetic-only from here down. Wrapped defensively: this Paper build's Particle.FLASH turned out
             *  to require org.bukkit.Color data it wasn't documented to need, and that thrown exception used to
             *  abort setChunkForceLoaded(...,false) below along with the rest of the reveal flourish — leaking
             *  the forced chunk and, worse, giving no indication anything had gone wrong. A pure decoration
             *  failing must never again threaten the boss's actual state or cleanup. */
            try{
                center.getWorld().spawnParticle(particle,center.clone().add(0,1,0),260,1.8,1.8,1.8,.18);
                center.getWorld().spawnParticle(Particle.EXPLOSION,center.clone().add(0,1,0),3);
                center.getWorld().playSound(center,Sound.ENTITY_WITHER_SPAWN,1.5f,kind==WorldBossKind.IRON_GOLEM?.7f:kind==WorldBossKind.PIGLIN_BRUTE?.95f:1f);
                center.getWorld().playSound(center,Sound.ENTITY_ENDER_DRAGON_GROWL,1f,.8f);
                center.getWorld().playSound(center,Sound.ENTITY_ENDER_DRAGON_AMBIENT,1f,.5f);
                for(Entity entity:boss2.getNearbyEntities(6,4,6))if(entity instanceof Player p){Vector away=p.getLocation().toVector().subtract(center.toVector());if(away.lengthSquared()>0)p.setVelocity(away.normalize().multiply(.8).setY(.3));}
            }catch(Throwable error){plugin.getLogger().warning("World boss reveal flourish failed safely (boss is still live and revealed): "+error);}
            /** Must hand off to trackBossChunk's own bookkeeping, not just drop the pin — a raw
             *  setChunkForceLoaded(...,false) here ungates the chunk the exact instant AI/vulnerability turn on,
             *  with nothing guaranteed to re-pin it until the next visualTick (~1s away). With no player nearby
             *  (any Nether spawn, or an isolated Overworld one) that gap is long enough for the chunk to unload
             *  and the just-revealed boss to fall out of tracking before anyone ever saw it. */
            trackBossChunk(boss2.getLocation());
        },delay);
    }
    private Particle summoningParticle(WorldBossKind kind){return switch(kind){case ASHEN_KNIGHT->Particle.SOUL_FIRE_FLAME;case IRON_GOLEM->Particle.CRIT;case PIGLIN_BRUTE->Particle.FLAME;};}
    private void celebrateWorldBoss(Player killer,Location location,WorldBossKind kind){broadcastWorldEvent("⚔ WORLD BOSS DEFEATED",displayName(kind)+" has fallen.",killer==null?"Ash settles over the battlefield.":plugin.nicknames().displayName(killer)+" struck the final blow; rewards were divided by damage.");location.getWorld().spawnParticle(kind==WorldBossKind.IRON_GOLEM?Particle.CRIT:kind==WorldBossKind.PIGLIN_BRUTE?Particle.FLAME:Particle.SOUL_FIRE_FLAME,location.clone().add(0,1,0),160,3,2,3,.08);Sound[] sequence={kind==WorldBossKind.IRON_GOLEM?Sound.ENTITY_IRON_GOLEM_DEATH:kind==WorldBossKind.PIGLIN_BRUTE?Sound.ENTITY_PIGLIN_BRUTE_DEATH:Sound.ENTITY_WITHER_DEATH,Sound.BLOCK_BELL_RESONATE,Sound.UI_TOAST_CHALLENGE_COMPLETE};for(int i=0;i<sequence.length;i++){int index=i;plugin.getServer().getScheduler().runTaskLater(plugin,()->{for(Player player:plugin.getServer().getOnlinePlayers())if(plugin.settings().sounds(player))player.playSound(player.getLocation(),sequence[index],1f,index==1?.75f:1f);},i*12L);}}
    private void tickKoth() { int radius = eventInt("koth.radius",12); Map<String, List<Player>> present = new HashMap<>(); for (Player p : plugin.getServer().getOnlinePlayers()) if (sameArea(p.getLocation(), radius)) { Database.FactionRow f = db.factionOf(CoreUtil.id(p));if(f==null)continue;String key="f:"+f.id();present.computeIfAbsent(key, x -> new ArrayList<>()).add(p); scoreNames.put(key,f.name()); if (eventParticipants.add(CoreUtil.id(p))){db.incrementStat(CoreUtil.id(p), "event_participations");plugin.progress().eventParticipated(p);} } if (present.size() == 1) { String key = present.keySet().iterator().next(); eventScores.merge(key, 1, Integer::sum); } }
    /** Admin-only: removes a live world-boss entity with no rewards/drops, without touching any unrelated
     *  active event (KOTH/Treasure/etc.) the way forceStopEvent()'s blanket clear would. Used by
     *  /ashfall boss despawn. */
    boolean despawnWorldBoss(){
        LivingEntity boss=worldBoss();if(boss==null)return false;
        eliteIds.remove(worldBossId);damage.remove(worldBossId);lastContribution.remove(worldBossId);removeHealthBar(worldBossId);lastNearbyAt.remove(worldBossId);specialAbilityAt.remove(worldBossId);exposedUntil.remove(worldBossId);enraged.remove(worldBossId);enrageStageApplied.remove(worldBossId);bossFirstEngagedAt.remove(worldBossId);eliteLastPlayerNear.remove(worldBossId);bossTargetSince.remove(worldBossId);bossTargetOutOfRangeSince.remove(worldBossId);bossUnreachableSince.remove(worldBossId);bossLeapCooldown.remove(worldBossId);bossRepathAt.remove(worldBossId);bossMechanicAt.remove(worldBossId);db.deleteBossState(worldBossId.toString());boss.remove();worldBossId=null;hintStage=0;nextHintAt=0;
        if(eventType==EventType.WORLD_BOSS||eventType==EventType.HUNT)finishEvent(false);
        broadcastNotice(Component.text("⚔ The world boss was despawned by an administrator.",NamedTextColor.DARK_GRAY));
        return true;
    }
    /** Admin emergency stop: removes any live world-boss entity (no rewards, no drops) and clears all
     *  event state. Used by /ashfall event stop and by recovery paths that need a clean slate. */
    boolean forceStopEvent(){
        if(eventType==null&&worldBoss()==null)return false;
        LivingEntity boss=worldBoss();
        if(boss!=null){eliteIds.remove(worldBossId);damage.remove(worldBossId);lastContribution.remove(worldBossId);removeHealthBar(worldBossId);lastNearbyAt.remove(worldBossId);specialAbilityAt.remove(worldBossId);exposedUntil.remove(worldBossId);enraged.remove(worldBossId);enrageStageApplied.remove(worldBossId);bossFirstEngagedAt.remove(worldBossId);eliteLastPlayerNear.remove(worldBossId);bossTargetSince.remove(worldBossId);bossTargetOutOfRangeSince.remove(worldBossId);bossUnreachableSince.remove(worldBossId);bossLeapCooldown.remove(worldBossId);bossRepathAt.remove(worldBossId);bossMechanicAt.remove(worldBossId);db.deleteBossState(worldBossId.toString());boss.remove();worldBossId=null;hintStage=0;nextHintAt=0;}
        if(eventType!=null)finishEvent(false);
        return true;
    }
    void finishEvent(boolean success) {
        if (eventType == null) return;
        EventType finished = eventType;
        if (finished == EventType.WORLD_BOSS || finished == EventType.HUNT) {
            LivingEntity boss = worldBoss();
            /** deleteBossState() used to live inside the boss!=null branch only, so a boss that vanished without
             *  a proper death (e.g. the Piglin Brute zombification bug) left its DB row orphaned forever —
             *  confirmed live via a dozen-plus stale rows accumulated in boss_state. Cleanup must run whenever
             *  worldBossId is being cleared, whether or not the entity itself is still resolvable. */
            if (worldBossId != null) {
                eliteIds.remove(worldBossId); damage.remove(worldBossId); lastContribution.remove(worldBossId); removeHealthBar(worldBossId);
                lastNearbyAt.remove(worldBossId); specialAbilityAt.remove(worldBossId); exposedUntil.remove(worldBossId); enraged.remove(worldBossId); enrageStageApplied.remove(worldBossId);bossFirstEngagedAt.remove(worldBossId);eliteLastPlayerNear.remove(worldBossId);bossTargetSince.remove(worldBossId);bossTargetOutOfRangeSince.remove(worldBossId);bossUnreachableSince.remove(worldBossId);bossLeapCooldown.remove(worldBossId);bossRepathAt.remove(worldBossId); bossMechanicAt.remove(worldBossId);
                db.deleteBossState(worldBossId.toString());
            }
            if (boss != null) {
                boss.remove();
                broadcastNotice(Component.text(displayName(worldBossKind) + " has faded, its window having closed.", NamedTextColor.DARK_GRAY));
            }
            worldBossId = null; hintStage = 0; nextHintAt = 0;
        }
        if (finished == EventType.KOTH && !eventScores.isEmpty()) {
            String winner = eventScores.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey(), display = scoreNames.getOrDefault(winner, winner);
            if (winner.startsWith("f:")) {
                long id = Long.parseLong(winner.substring(2));double reward=eventDouble("koth.winner-faction",3000);db.changeFactionBalance(id,reward);db.recordEconomy(null,"EVENT",reward,"KOTH_FACTION");db.incrementFactionStat(id, "event_wins");
                for(String name:db.factionMembers(id)){Database.StatsRow member=db.statsByName(name);if(member!=null)db.incrementStat(member.id(),"event_wins");}
                db.history("FACTION", id, "EVENT", display + " won King of the Hill.");db.history("SERVER",null,"EVENT",display+" won King of the Hill.");
            } else {
                String id = winner.substring(2);double reward=events.getDouble("koth.winner-personal", 2000);plugin.creditEarned(id,reward,"KOTH_PERSONAL");db.recordEconomy(id,"EVENT",reward,"KOTH_PERSONAL");db.incrementStat(id, "event_wins");Player player = find(id);if (player != null) plugin.progress().eventWon(player, "King of the Hill");
            }
            broadcastNotice(Component.text("♜ " + display + " won King of the Hill!", NamedTextColor.GOLD));
        } else if (!success && finished != EventType.WORLD_BOSS && finished != EventType.HUNT) broadcastNotice(Component.text("The world event has ended.", NamedTextColor.GRAY));
        if(finished==EventType.RESOURCE_RUSH)for(var entry:eventEarnings.entrySet()){Player earner=find(entry.getKey());if(earner!=null&&entry.getValue()>=.01)CoreUtil.msg(earner,"You made "+CoreUtil.money(entry.getValue())+" during Resource Rush!");}
        EventTier finishedTier=eventTier;Origin finishedOrigin=eventOrigin;long nextDelay=activeTierNextDelay;eventType = null;eventCenter = null;eventEnds = 0;activeTierNextDelay=0;eventScores.clear();eventParticipants.clear();eventEarnings.clear();db.state("current_event", "");
        if(finishedOrigin==Origin.NATURAL){eventRemaining.put(finishedTier,nextDelay>0?nextDelay:randomRemaining(finishedTier));scheduledEvents.computeIfAbsent(finishedTier,this::chooseNatural);persistEventTimers();}
        // A player/admin-summoned event borrows this tier's "only one event at a time" slot without
        // resetting its own independent natural timer. If that timer happened to run out while the summoned
        // event was occupying the slot (e.g. a long boss fight), re-arm it here instead of letting the very
        // next tick fire a natural event immediately the moment the slot frees up.
        else if(eventRemaining.getOrDefault(finishedTier,Long.MAX_VALUE)<=0){eventRemaining.put(finishedTier,randomRemaining(finishedTier));persistEventTimers();}
    }

    void status(Player p) { if (eventType == null) { CoreUtil.msg(p, "No active event. " + nextEventLine()); CoreUtil.msg(p, "Use /events track on|off to control navigation."); return; } CoreUtil.msg(p, "Active Event: " + CoreUtil.pretty(eventType.name()) + " • " + Math.max(0, (eventEnds - System.currentTimeMillis()) / 60000) + "m"); CoreUtil.msg(p, instruction());CoreUtil.msg(p,rewardLine()); CoreUtil.msg(p, trackingLine(p)); }
    String uiEventLine() { return nextScheduledEventLine(); }
    String activeEventLine(){return eventType==null?"None":activeEventName()+" • "+activeEventCountdown();}
    String activeEventName(){return eventType==null?"None":shortEventName(eventType);}
    String activeEventCountdown(){return eventType==null?"":duration(Math.max(0,eventEnds-System.currentTimeMillis()));}
    String nextEventLine(){return nextScheduledEventLine();}
    boolean worldBossActive(){return (eventType==EventType.WORLD_BOSS||eventType==EventType.HUNT)&&worldBoss()!=null;}
    String worldBossLine(){LivingEntity boss=worldBoss();return boss==null?"None":(boss.customName()==null?"Active":net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(boss.customName()));}
    String serverListEventLine(){if(eventType!=null)return"Event: "+CoreUtil.pretty(eventType.name())+" • "+Math.max(0,(eventEnds-System.currentTimeMillis())/60000)+"m";return"Next Event: "+nextEventLine();}
    String nextScheduledEventLine(){TimedEvent event=nextScheduledEvent();return event.countdown().isBlank()?event.name():event.name()+": "+event.countdown();}
    String nextScheduledEventName(){return nextScheduledEvent().name();}
    String nextScheduledEventCountdown(){return nextScheduledEvent().countdown();}
    private record TimedEvent(String name,String countdown){}
    private TimedEvent nextScheduledEvent(){
        long now=System.currentTimeMillis(),best=Long.MAX_VALUE,activeDelay=eventType==null?0:Math.max(0,eventEnds-now);String name="None scheduled";
        for(EventTier tier:EventTier.values()){
            if(eventType!=null&&eventOrigin==Origin.NATURAL&&tier==eventTier){
                long due=eventEnds+Math.max(0,activeTierNextDelay);
                EventType type=scheduledEvents.get(tier);
                if(type!=null&&due<best){best=due;name=shortEventName(type);}
                continue;
            }
            long remaining=eventRemaining.getOrDefault(tier,Long.MAX_VALUE);
            long due=remaining==Long.MAX_VALUE?Long.MAX_VALUE:now+Math.max(Math.max(0,remaining),activeDelay);
            EventType type=scheduledEvents.get(tier);
            if(type!=null&&due<best){best=due;name=shortEventName(type);}
        }
        if(plugin.weeklyDragon()!=null){
            long dragon=plugin.weeklyDragon().nextEpochMillis();
            if(dragon>now&&dragon<best){best=dragon;name="Dragon";}
        }
        return new TimedEvent(name,best==Long.MAX_VALUE?"":duration(best-now));
    }
    private String shortEventName(EventType type){return switch(type){case RESOURCE_RUSH->"Resource Rush";case ELITE_HUNT->"Elite Hunt";case TREASURE->"Treasure";case KOTH->"KOTH";case WORLD_BOSS,HUNT->"World Boss";};}
    private EventTier nextTier(){return Arrays.stream(EventTier.values()).min(Comparator.comparingLong(t->eventRemaining.getOrDefault(t,Long.MAX_VALUE))).orElse(EventTier.MICRO);}
    private String duration(long millis){long minutes=Math.max(0,millis)/60000;if(minutes<1)return"under 1m";long days=minutes/1440,hours=minutes%1440/60,mins=minutes%60;if(days>0)return days+"d "+hours+"h";if(hours>0)return hours+"h "+mins+"m";return mins+"m";}
    boolean active(){return eventType!=null&&eventCenter!=null;}
    boolean nearActiveEvent(Location location,double radius){return eventCenter!=null&&location.getWorld()!=null&&location.getWorld().equals(eventCenter.getWorld())&&location.distanceSquared(eventCenter)<=radius*radius;}
    String trackingLine(Player p) { if (eventType == null || eventCenter == null) return "No active event";if(eventType==EventType.RESOURCE_RUSH)return"⛏ Mine natural ores anywhere";
        Location target = (eventType==EventType.WORLD_BOSS||eventType==EventType.HUNT) && worldBoss()!=null ? worldBoss().getLocation() : eventCenter;
        if (!p.getWorld().equals(target.getWorld())) return "Event: enter " + CoreUtil.pretty(target.getWorld().getEnvironment().name()); double dx = target.getX() - p.getLocation().getX(), dz = target.getZ() - p.getLocation().getZ(), distance = Math.sqrt(dx * dx + dz * dz); double angle = Math.toDegrees(Math.atan2(-dx, dz)); if (angle < 0) angle += 360; String[] directions = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"}; String direction = directions[(int) Math.round(angle / 45.0) % 8]; return "◆ " + CoreUtil.pretty(eventType.name()) + " • " + direction + " • " + Math.round(distance) + "m"; }
    private String instruction() { return switch (eventType) { case RESOURCE_RUSH -> "Mine natural ores anywhere to earn bonus money."; case ELITE_HUNT -> "Follow the tracker and defeat the marked elite."; case TREASURE -> "Follow the tracker and open the marked barrel."; case KOTH -> "Your faction must hold the center within " + eventInt("koth.radius",12) + " blocks."; case WORLD_BOSS, HUNT -> "Damage " + worldBossLine() + ", stay active, and survive its phases."; }; }
    private String rewardLine(){return switch(eventType){case RESOURCE_RUSH->"Reward: "+CoreUtil.money(eventDouble("resource-rush.ore-money",8))+" per qualifying ore plus a drop chance.";case TREASURE->"Reward: cache contents and "+CoreUtil.money(eventDouble("treasure.finder-money",1500))+".";case KOTH->"Reward: faction treasury and event-win credit.";case ELITE_HUNT->"Reward: shared elite money, themed loot and participation.";case WORLD_BOSS,HUNT->"Reward: shared boss pool, participation loot and rare drops.";};}
    private void persistEvent() { if (eventType == null || eventCenter == null) { db.state("current_event", ""); return; } db.state("current_event", String.join(",", eventType.name(), eventCenter.getWorld().getName(), Double.toString(eventCenter.getX()), Double.toString(eventCenter.getY()), Double.toString(eventCenter.getZ()), Long.toString(eventEnds),eventOrigin.name(),eventTier.name(),Long.toString(activeTierNextDelay))); }
    private void loadEvent() { String raw = db.state("current_event"); if (raw == null || raw.isBlank()) return; try { String[] parts = raw.split(","); if (Long.parseLong(parts[5]) <= System.currentTimeMillis()) return; World world = plugin.getServer().getWorld(parts[1]); if (world == null) return; EventType loaded = EventType.valueOf(parts[0]); eventType = loaded == EventType.HUNT ? EventType.WORLD_BOSS : loaded; eventCenter = new Location(world, Double.parseDouble(parts[2]), Double.parseDouble(parts[3]), Double.parseDouble(parts[4])); eventEnds = Long.parseLong(parts[5]);eventOrigin=parts.length>6?Origin.valueOf(parts[6]):Origin.NATURAL;eventTier=parts.length>7?EventTier.valueOf(parts[7]):defaultTier(eventType);activeTierNextDelay=parts.length>8?parseLong(parts[8],0):eventOrigin==Origin.NATURAL?randomRemaining(eventTier):0; } catch (Exception e) { plugin.getLogger().warning("Ignored invalid saved event state."); } }

    /** Walks every persisted boss row (not just the first) on boot, purging every invalid/orphaned one it finds
     *  along the way and restoring the first genuinely live one. Rows could accumulate as garbage before the
     *  finishEvent() cleanup-leak fix above — this sweeps up any that are already stuck rather than only ever
     *  reconciling one row per restart. */
    private void restoreBoss() {
        List<Database.BossStateRow> states = db.bossStates(); if (states.isEmpty()) return;
        boolean adopted=false;
        for (Database.BossStateRow state : states) {
            try {
                UUID candidateId = UUID.fromString(state.entityId());
                World world = plugin.getServer().getWorld(state.world());
                if (world == null) { db.deleteBossState(state.entityId()); continue; }
                world.getChunkAt(((int) state.x()) >> 4, ((int) state.z()) >> 4).load();
                Entity entity = plugin.getServer().getEntity(candidateId);
                if (!(entity instanceof LivingEntity living) || !isWorldBossTier(living.getPersistentDataContainer().get(tierKey, PersistentDataType.STRING))) { db.deleteBossState(state.entityId()); continue; }
                /** The data model only ever tracks ONE active world boss (worldBossId is a single field, and
                 *  startEvent() refuses to start a second one while it's set) — but the old chunk-tracking bug
                 *  could orphan several bosses across different sessions before that was fixed. A live, valid
                 *  entity found here AFTER we've already adopted one is exactly that: real leftover debris this
                 *  restart can finally reach, not something to silently leave behind for despawn to never find. */
                if(adopted){
                    plugin.getLogger().warning("Removed orphaned world boss "+state.entityId()+" ("+state.kind()+") at "+state.world()+" "+Math.round(state.x())+" "+Math.round(state.y())+" "+Math.round(state.z())+" — only one world boss can be tracked at a time.");
                    living.remove(); db.deleteBossState(state.entityId()); continue;
                }
                worldBossId = candidateId; eventCenter = living.getLocation(); eliteIds.add(worldBossId);
                Map<String,Database.BossContribution> saved=db.bossContributions(state.entityId());Map<String,Double> amounts=new HashMap<>();Map<String,Long> hits=new HashMap<>();saved.forEach((id,value)->{amounts.put(id,value.damage());hits.put(id,value.lastHit());});damage.put(worldBossId,amounts);lastContribution.put(worldBossId,hits);
                bossSpawnedAt = state.spawnedAt(); hintStage = state.hintStage(); nextHintAt = state.nextHintAt();worldBossOrigin=Origin.valueOf(state.origin());worldBossBaseHealth=state.baseHealth();worldBossActiveCount=Math.max(1,state.activeCount());worldBossKind=kindOf(state.kind());living.getPersistentDataContainer().set(originKey,PersistentDataType.STRING,worldBossOrigin.name());
                adopted=true;
            } catch (Exception e) {
                plugin.getLogger().warning("Discarded invalid persisted world-boss state: " + e.getMessage());
                db.deleteBossState(state.entityId());
            }
        }
    }
    private LivingEntity worldBoss() { if (worldBossId == null) return null; Entity entity = plugin.getServer().getEntity(worldBossId); return entity instanceof LivingEntity living && living.isValid() ? living : null; }
    /** A world boss spawns far from any player and, once its AI turns on after the summon reveal, can freely
     *  wander out of the one chunk that was force-loaded for the reveal itself. With nobody nearby to generate a
     *  normal chunk ticket, the very next chunk it steps into unloads and it becomes untraceable via getEntity()
     *  — silently "disappearing" with no death, no broadcast, nothing in the log but a housekeeping warning 5s
     *  later. Re-pinning its current chunk every visual tick (~1s, tight enough that no boss can outrun it)
     *  keeps it trackable for its entire active lifetime, not just the initial reveal window. */
    private void trackBossChunk(Location location){
        World world=location.getWorld();if(world==null)return;
        int cx=location.getBlockX()>>4,cz=location.getBlockZ()>>4;
        if(forcedBossChunkSet&&forcedBossWorld==world&&cx==forcedBossChunkX&&cz==forcedBossChunkZ)return;
        if(forcedBossChunkSet&&forcedBossWorld!=null)forcedBossWorld.setChunkForceLoaded(forcedBossChunkX,forcedBossChunkZ,false);
        world.setChunkForceLoaded(cx,cz,true);forcedBossWorld=world;forcedBossChunkX=cx;forcedBossChunkZ=cz;forcedBossChunkSet=true;
    }
    private void releaseBossChunk(){if(forcedBossChunkSet&&forcedBossWorld!=null)forcedBossWorld.setChunkForceLoaded(forcedBossChunkX,forcedBossChunkZ,false);forcedBossChunkSet=false;forcedBossWorld=null;}
    /** An unloaded chunk makes the boss entity briefly unresolvable even though it still exists (e.g. no
     *  player has reached it yet). Only treat the event as genuinely stale when its last known chunk is
     *  loaded and still shows no boss there, so a distant, un-visited boss is never cancelled by mistake. */
    private boolean worldBossChunkObservedEmpty(){
        if(worldBossId==null)return true;
        List<Database.BossStateRow> states=db.bossStates();
        Database.BossStateRow state=states.stream().filter(row->row.entityId().equals(worldBossId.toString())).findFirst().orElse(null);
        if(state==null)return true;
        World world=plugin.getServer().getWorld(state.world());
        if(world==null)return true;
        return world.isChunkLoaded(((int)state.x())>>4,((int)state.z())>>4);
    }
    private void persistWorldBoss() { LivingEntity boss = worldBoss(); if (boss == null || worldBossId == null) return; Location loc = boss.getLocation(); db.saveBossState(new Database.BossStateRow(worldBossId.toString(), loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), bossSpawnedAt, hintStage, nextHintAt, "ACTIVE",worldBossOrigin.name(),worldBossBaseHealth,worldBossActiveCount,worldBossKind.name())); Map<String, Double> participants = damage.get(worldBossId);Map<String,Long> hits=lastContribution.getOrDefault(worldBossId,Map.of()); if (participants != null) for (var entry : participants.entrySet()) db.saveBossDamage(worldBossId.toString(), entry.getKey(), entry.getValue(),hits.getOrDefault(entry.getKey(),0L)); }
    private void tickHints(long now) { LivingEntity boss = worldBoss(); if (boss == null || hintStage >= 2 || now < nextHintAt) return; String prefix=configPrefix(worldBossKind); if (hintStage == 0) { broadcastNotice(Component.text("⚔ Event clue: scouts report activity near X " + rough(boss.getLocation().getBlockX(), 500) + ", Z " + rough(boss.getLocation().getBlockZ(), 500) + ".", NamedTextColor.GOLD)); hintStage = 1; nextHintAt = now + Math.max(1, bosses.getLong(prefix+".second-hint-minutes", 20) - bosses.getLong(prefix+".first-hint-minutes", 10)) * 60000L; } else { broadcastNotice(Component.text("⚔ Strong clue: " + displayName(worldBossKind) + " is near X " + rough(boss.getLocation().getBlockX(), 100) + ", Z " + rough(boss.getLocation().getBlockZ(), 100) + ".", NamedTextColor.RED)); hintStage = 2; nextHintAt = Long.MAX_VALUE; } persistWorldBoss(); }

    private World overworld() { return plugin.getServer().getWorld(plugin.getConfig().getString("claims.world", "world")); }
    private World nether() { for(World world:plugin.getServer().getWorlds()) if(world.getEnvironment()==World.Environment.NETHER) return world; return null; }
    /** The Cinder Warlord (Piglin Brute) is Nether-only — both because that's its natural habitat and because
     *  it sidesteps the whole Piglin-zombification class of bug entirely: it can never accumulate the 15s of
     *  Overworld time that triggers conversion if it's never actually in the Overworld. */
    private World worldFor(WorldBossKind kind){return kind==WorldBossKind.PIGLIN_BRUTE?nether():overworld();}
    private int rough(int value, int interval) { return Math.round(value / (float) interval) * interval; }
    private long parseLong(String value, long fallback) { try { return value == null ? fallback : Long.parseLong(value); } catch (NumberFormatException e) { return fallback; } }
    private Player find(String id) { for (Player p : plugin.getServer().getOnlinePlayers()) if (CoreUtil.id(p).equals(id)) return p; return null; }
    private void broadcastWorldEvent(String title, String line1, String line2) { broadcastWorldEvent(title,line1,line2,null); }
    private void broadcastWorldEvent(String title, String line1, String line2, String hint) { for(Player player:plugin.getServer().getOnlinePlayers()){if(!plugin.settings().bossNotifications(player))continue;player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));player.sendMessage(Component.text(title, NamedTextColor.GOLD));player.sendMessage(Component.text(line1, NamedTextColor.RED));player.sendMessage(Component.text(line2, NamedTextColor.GRAY));if(hint!=null)player.sendMessage(Component.text(hint, NamedTextColor.YELLOW));player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));} }
    boolean isOrdinaryElite(LivingEntity entity) {
        String tier = entity.getPersistentDataContainer().get(tierKey, PersistentDataType.STRING);
        return tier != null && !tier.equals("miniboss") && !entity.getUniqueId().equals(worldBossId);
    }
    private void broadcastNotice(Component message){for(Player player:plugin.getServer().getOnlinePlayers())if(plugin.settings().bossNotifications(player))player.sendMessage(message);}
    /** A quitting client forgets every boss bar it was shown; the server-side "already sent" bookkeeping
     *  doesn't, so without this a reconnecting player's Set.add() on rejoin silently no-ops and the bar never
     *  reappears. Clearing them from every tracked bar's viewer set lets the next update tick resend it. */
    void playerQuit(UUID id){for(Set<UUID> viewers:barViewers.values())viewers.remove(id);}
}
