package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

final class WeeklyDragonService {
    private static final DateTimeFormatter ID=DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm");
    private final SMPCore plugin;private final Database db;private final NamespacedKey weeklyKey;
    private BukkitTask task,bossBarTask;
    /** loadPillarArea() is async (chunk loading), and the existing activeDragon() check only catches a
     *  dragon that has already fully spawned — it says nothing about a respawn sequence that's already been
     *  kicked off but hasn't produced an entity yet. Without this flag, a slow chunk load could let a second
     *  tick() (every 20s) or an admin /ashfall dragon start race in underneath and kick off a second
     *  initiateRespawn() before the first one's dragon exists to be detected. Set the instant a spawn
     *  sequence begins, cleared on every terminal branch below (success or failure) so it can never get
     *  stuck true. */
    private volatile boolean spawning=false;

    WeeklyDragonService(SMPCore plugin){
        this.plugin=plugin;db=plugin.db();weeklyKey=new NamespacedKey(plugin,"weekly_dragon");
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,100L,400L);
        bossBarTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::syncBossBar,40L,40L);
    }
    void shutdown(){if(task!=null)task.cancel();if(bossBarTask!=null)bossBarTask.cancel();}

    private void tick(){
        if(!plugin.getConfig().getBoolean("weekly-dragon.enabled",true))return;ZonedDateTime now=ZonedDateTime.now(zone()),target=currentTarget(now);String occurrence=ID.format(target);
        long seconds=Duration.between(now,target).getSeconds();
        if(seconds>0&&seconds<=3600)announceOnce("weekly_dragon:announce60:"+occurrence,"✦ The weekly Ender Dragon awakens in about one hour.");
        if(seconds>0&&seconds<=600)announceOnce("weekly_dragon:announce10:"+occurrence,"⚔ The weekly Ender Dragon awakens in about ten minutes.");
        if(!now.isBefore(target)&&now.isBefore(target.plusHours(6))&&!occurrence.equals(db.state("weekly_dragon:last_started")))start(occurrence);
    }
    /** Vanilla's own EnderDragonFight only adds players to the boss bar as a side effect of its normal tick
     *  progression, which — per the note on attemptRespawn() below — does nothing at all while the boss bar
     *  has no players tracked yet. That's a real chicken-and-egg for a fight started programmatically rather
     *  than by a player walking in fresh: anyone already standing in the End when this service (re)starts
     *  the fight never gets a PlayerChangedWorldEvent to trigger vanilla's own add-to-bar step, so the bar
     *  never appears and the fight never progresses. Managing membership directly here sidesteps needing
     *  vanilla's own population path to work at all — every player in the dragon's world is guaranteed onto
     *  the bar, and everyone else guaranteed off it, twice a second, independent of how they got there. */
    /** The in-memory "spawning" flag above only guards races within a single plugin uptime — it resets to
     *  false on every restart, so a crystal-beam sequence still in flight when the server restarts (its
     *  state lives in the world's own saved DragonBattle data, not in this Java field) has no way to warn a
     *  post-restart respawn attempt that one is already underway. Rather than try to make every possible
     *  race impossible to create, this detects and corrects the result directly and continuously: at most
     *  one live EnderDragon is allowed to exist in the End at any time, checked here on the same twice-a-
     *  second cadence as the boss bar sync (and once at startup, for whatever the previous session left
     *  behind). Prefers keeping whichever entity matches weekly_dragon:active in the database, since that's
     *  the one everything else (status, /ashfall dragon commands) already thinks is "the" dragon; falls
     *  back to the first found if that UUID isn't among the survivors. Extras are removed silently
     *  (Entity.remove(), not damage) — these are accidental duplicates, not a real kill, so no death event,
     *  no "the dragon has been defeated" broadcast, and no kill rewards should fire for them. */
    private void deduplicateDragons(World world){
        List<EnderDragon> dragons=new ArrayList<>();
        for(EnderDragon dragon:world.getEntitiesByClass(EnderDragon.class))if(!dragon.isDead())dragons.add(dragon);
        if(dragons.size()<=1)return;
        String activeId=db.state("weekly_dragon:active");
        EnderDragon keep=dragons.stream().filter(d->d.getUniqueId().toString().equals(activeId)).findFirst().orElse(dragons.get(0));
        int removed=0;
        for(EnderDragon dragon:dragons){
            if(dragon.equals(keep))continue;
            dragon.remove();
            removed++;
        }
        plugin.getLogger().warning("Weekly Ender Dragon: removed "+removed+" duplicate dragon entity/entities, kept "+keep.getUniqueId()+".");
        db.state("weekly_dragon:active",keep.getUniqueId().toString());
        db.history("SERVER",null,"DRAGON","Removed "+removed+" duplicate Ender Dragon entity/entities.");
    }
    private void syncBossBar(){
        World world=endWorld();if(world==null)return;
        deduplicateDragons(world);
        EnderDragon dragon=activeDragon(world);if(dragon==null)return;
        org.bukkit.boss.BossBar bar=dragon.getBossBar();if(bar==null)return;
        List<org.bukkit.entity.Player> present=world.getPlayers();
        for(org.bukkit.entity.Player player:present)if(!bar.getPlayers().contains(player))bar.addPlayer(player);
        for(org.bukkit.entity.Player tracked:new ArrayList<>(bar.getPlayers()))if(!present.contains(tracked))bar.removePlayer(tracked);
    }
    private void start(String occurrence){
        World world=endWorld();if(world==null)return;
        if(spawning)return;
        EnderDragon existing=activeDragon(world);if(existing!=null){db.state("weekly_dragon:last_started",occurrence);broadcast("⚔ The End already has an active Dragon fight.",NamedTextColor.DARK_PURPLE);return;}
        spawning=true;
        loadPillarArea(world).thenAccept(v->plugin.getServer().getScheduler().runTask(plugin,()->{
            if(activeDragon(world)!=null){spawning=false;db.state("weekly_dragon:last_started",occurrence);releasePillarArea(world);return;}
            spawnDragon(world,occurrence);
        }));
    }
    /** Force-starts a fight right now, bypassing the weekly schedule — for admin recovery/testing
     *  (/ashfall dragon start). Kills any existing dragon first (even a non-weekly one) via damage rather
     *  than remove() — a raw remove() bypasses the normal death event DragonBattle listens for and can leave
     *  its internal state thinking a dragon is still alive, silently refusing to respawn one. A real kill
     *  needs a tick to fully process before initiateRespawn() is safe to call.
     *  Sets spawning unconditionally (not gated behind !spawning like start() — this is an explicit admin
     *  override, meant to work even to recover from a stuck state) so a concurrent scheduled tick() can't
     *  race a second respawn sequence in underneath this one. */
    String forceStart(){
        World world=endWorld();if(world==null)return"No End world is loaded.";
        String occurrence=ID.format(ZonedDateTime.now(zone()));
        spawning=true;
        EnderDragon existing=activeDragon(world);
        if(existing!=null){
            existing.setHealth(0);
            plugin.getServer().getScheduler().runTaskLater(plugin,()->beginSpawn(world,occurrence),20L);
        }else beginSpawn(world,occurrence);
        return"Forcing a fresh dragon fight now (occurrence "+occurrence+").";
    }
    /** Diagnostic only — isolates resetCrystals() from initiateRespawn() to narrow down which one is
     *  actually failing. Not part of the normal flow. */
    String debugResetCrystals(){
        World world=endWorld();if(world==null)return"No End world is loaded.";
        DragonBattle battle=world.getEnderDragonBattle();if(battle==null)return"No DragonBattle.";
        int before=battle.getRespawnCrystals().size();
        loadPillarArea(world).thenAccept(v->plugin.getServer().getScheduler().runTask(plugin,()->{
            battle.resetCrystals();
            plugin.getLogger().info("[dragon-debug] resetCrystals() called, respawnCrystals now="+battle.getRespawnCrystals().size()+" healingCrystals="+battle.getHealingCrystals().size());
        }));
        releasePillarAreaLater(world);
        return"resetCrystals() debug triggered (had "+before+" crystals before). Check /ashfall dragon status and console log shortly.";
    }
    private void beginSpawn(World world,String occurrence){
        loadPillarArea(world).thenAccept(v->plugin.getServer().getScheduler().runTask(plugin,()->spawnDragon(world,occurrence)));
    }
    /** Diagnostic for admins — position and vanilla AI phase directly from the live entity, so a stuck fight
     *  (phase never advancing, position never changing) is visible without having to fly out and look. */
    String status(){
        World world=endWorld();if(world==null)return"No End world is loaded.";
        DragonBattle battle=world.getEnderDragonBattle();
        String battleInfo=battle==null?"no DragonBattle":"respawnPhase="+battle.getRespawnPhase()+" previouslyKilled="+battle.hasBeenPreviouslyKilled()+" respawnCrystals="+battle.getRespawnCrystals().size()+" healingCrystals="+battle.getHealingCrystals().size()+" portal="+battle.getEndPortalLocation();
        EnderDragon dragon=activeDragon(world);
        if(dragon==null)return"No dragon currently active. weekly_dragon:active="+db.state("weekly_dragon:active")+" | "+battleInfo;
        Location loc=dragon.getLocation();
        return String.format(Locale.ROOT,"Dragon at %.1f, %.1f, %.1f | phase=%s | weekly=%s | health=%.1f/%.1f | %s",
                loc.getX(),loc.getY(),loc.getZ(),dragon.getPhase(),isWeekly(dragon),dragon.getHealth(),dragon.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(),battleInfo);
    }
    /** A raw world.spawn(EnderDragon.class,...) (the original implementation) created a dragon with no
     *  DragonBattle behind it, so it never entered the vanilla phase state machine and no crystal-beam
     *  entrance ever ran — exactly the "stuck in place, no pillars active" bug reported live. Confirmed via
     *  /data get entity: the dragon that spawned 2026-07-31-16-00 was still sitting at its exact spawn point
     *  [0.5, 82.0, 0.5] 24 hours later, with zero end_crystal entities anywhere in the End.
     *  The obvious next fix — DragonBattle.resetCrystals()+initiateRespawn(), which is Paper's real entry
     *  point for the same sequence vanilla runs on a genuine first fight or a player's 4-crystal manual
     *  respawn — turned out to still fail here, and not for a chunk-loading reason: live diagnosis
     *  (/ashfall dragon debugcrystals, plus direct block checks at every standard ~43-block pillar radius
     *  candidate with chunks forcibly kept loaded) confirmed this End's outer pillar ring simply isn't
     *  intact — resetCrystals() only places crystals on pillars that physically exist, so with none standing
     *  it placed zero every time, initiateRespawn() had nothing to animate, and respawnPhase stayed NONE.
     *  hasBeenPreviouslyKilled()==true, so this world's dragon was legitimately fought at some point before
     *  the weekly system existed — the pillars were very likely mined down afterward, which is completely
     *  normal player behavior, just not something the automatic weekly respawn accounted for.
     *  Rather than depend on physical pillars existing at all (or regenerating terrain to restore them,
     *  which risks touching anything built nearby), this uses DragonBattle's other overload —
     *  initiateRespawn(Collection<EnderCrystal>) — which lets a plugin supply the crystals directly, the
     *  same mechanism a fully custom arena would use. summonRespawnCrystals() places 4 fresh crystals a
     *  short distance above the portal itself, which is unaffected by whatever happened to the outer ring. */
    private void spawnDragon(World world,String occurrence){
        DragonBattle battle=world.getEnderDragonBattle();
        if(battle==null){spawning=false;plugin.getLogger().warning("Could not start the weekly Ender Dragon: no DragonBattle available for "+world.getName()+".");return;}
        /** A prior attempt (floating, unanchored crystals) got as far as respawnPhase=START before stalling,
         *  and this state is persisted in the End's own saved dragon-fight data — it survives server
         *  restarts, which is why it kept reappearing on every subsequent attempt in this session.
         *  initiateRespawn() correctly refuses to start a second respawn while it still believes one is in
         *  progress, returning false forever afterward regardless of how good the crystal placement is.
         *  NONE isn't a settable target (CraftDragonBattle.setRespawnPhase() throws IllegalArgumentException
         *  for it — confirmed live, it's a read-only sentinel for "nothing in progress"), but END is a real
         *  phase; forcing it there closes out the stuck sequence the same way a real completed respawn would. */
        if(battle.getRespawnPhase()!=DragonBattle.RespawnPhase.NONE){
            battle.setRespawnPhase(DragonBattle.RespawnPhase.END);
            plugin.getServer().getScheduler().runTaskLater(plugin,()->attemptRespawn(world,battle,occurrence),20L);
        }else attemptRespawn(world,battle,occurrence);
    }
    private void attemptRespawn(World world,DragonBattle battle,String occurrence){
        List<org.bukkit.entity.EnderCrystal> crystals=summonRespawnCrystals(world,battle.getEndPortalLocation());
        if(crystals.size()<4){
            spawning=false;
            plugin.getLogger().warning("Weekly Ender Dragon: only found "+crystals.size()+" valid crystal mount points near the portal (need 4) — aborting this attempt.");
            for(org.bukkit.entity.EnderCrystal crystal:crystals)crystal.remove();
            releasePillarArea(world);
            return;
        }
        ensureDragonKilledFlag(battle);
        boolean started=battle.initiateRespawn(crystals);
        if(!started){
            spawning=false;
            plugin.getLogger().warning("Weekly Ender Dragon: initiateRespawn(crystals) returned false — respawnPhase was "+battle.getRespawnPhase()+" at call time.");
            for(org.bukkit.entity.EnderCrystal crystal:crystals)crystal.remove();
            releasePillarArea(world);
            return;
        }
        db.state("weekly_dragon:last_started",occurrence);
        broadcast("⚔ WEEKLY DRAGON • The End is under siege.",NamedTextColor.DARK_PURPLE);
        /** EnderDragonFight.tick() — confirmed by disassembling Paper's own patched class — does nothing at all
         *  (no crystal-beam progression, no dragon spawn) while dragonEvent.getPlayers() is empty; it's gated on
         *  a real player being tracked by the boss bar, not just on chunks being loaded. So the respawn state set
         *  above will simply sit there, harmlessly, until a player actually reaches the End — completely normal
         *  for a "go fight it" weekly boss. Keeping the arena force-loaded in the meantime just means the
         *  animation isn't stuck waiting on chunk-load lag the moment someone does arrive. releasePillarAreaLater
         *  is a bounded safety net, not the primary release path — the primary paths are defeated() (real
         *  success) and the two failure branches above (immediate, nothing left to protect). */
        releasePillarAreaLater(world);
        pollForSpawnedDragon(battle,occurrence,0);
    }
    private static java.lang.reflect.Field dragonFightHandleField;
    private static java.lang.reflect.Field dragonKilledField;
    /** Root cause found by disassembling Paper's own patched EnderDragonFight.respawnDragon(List): it silently
     *  returns false unless its internal "dragonKilled" field is true — a transient in-progress flag, distinct
     *  from Bukkit's hasBeenPreviouslyKilled()/getRespawnPhase() (which map to a *different* persisted field and
     *  both already read back correctly as "previously killed" / NONE). This world's dragon fight state was left
     *  with dragonKilled=false — almost certainly a side effect of the original bug where the dragon was raw
     *  world.spawn()'d and never went through the vanilla death pipeline that sets this flag. Bukkit's API has no
     *  getter or setter for it, so this reflects into CraftDragonBattle's private "handle" and the NMS field
     *  directly, mirroring exactly what a legitimate vanilla kill already set once (hasPreviouslyKilledDragon is
     *  true) rather than fabricating new history. */
    private void ensureDragonKilledFlag(DragonBattle battle){
        try{
            if(dragonFightHandleField==null){
                Class<?> craftClass=battle.getClass();
                java.lang.reflect.Field f=craftClass.getDeclaredField("handle");
                f.setAccessible(true);
                dragonFightHandleField=f;
            }
            Object handle=dragonFightHandleField.get(battle);
            if(dragonKilledField==null){
                java.lang.reflect.Field f=handle.getClass().getDeclaredField("dragonKilled");
                f.setAccessible(true);
                dragonKilledField=f;
            }
            dragonKilledField.setBoolean(handle,true);
        }catch(ReflectiveOperationException e){
            plugin.getLogger().warning("Weekly Ender Dragon: could not set internal dragonKilled flag via reflection: "+e);
        }
    }
    /** First attempt floated 4 crystals in mid-air 8 blocks out — initiateRespawn(crystals) accepted them
     *  (respawnPhase moved to START, respawnCrystals showed 4) but then stalled: the crystals got consumed
     *  and phase never advanced past START, no dragon ever appeared. Every real vanilla crystal — whether
     *  from an intact pillar or a player's own manual placement — always rests on a solid block; a floating
     *  crystal apparently isn't a valid beam anchor as far as the animation logic is concerned. This scans
     *  outward from the portal for actual solid-block-with-air-above spots (the general shape of any valid
     *  mount, pillar or bedrock nub alike) and only uses those, rather than assuming a fixed offset. */
    private List<org.bukkit.entity.EnderCrystal> summonRespawnCrystals(World world,Location portal){
        List<Location> mounts=findMountPoints(world,portal,4);
        List<org.bukkit.entity.EnderCrystal> crystals=new ArrayList<>();
        for(Location loc:mounts)crystals.add(world.spawn(loc,org.bukkit.entity.EnderCrystal.class,crystal->crystal.setShowingBottom(true)));
        return crystals;
    }
    private List<Location> findMountPoints(World world,Location portal,int count){
        List<Location> found=new ArrayList<>();
        int cx=portal.getBlockX(),cz=portal.getBlockZ();
        for(int radius=2;radius<=48&&found.size()<count;radius++){
            for(int angleStep=0;angleStep<8&&found.size()<count;angleStep++){
                double angle=Math.toRadians(angleStep*45.0);
                int x=cx+(int)Math.round(radius*Math.cos(angle)),z=cz+(int)Math.round(radius*Math.sin(angle));
                boolean tooClose=false;
                for(Location existing:found)if(existing.getBlockX()==x&&existing.getBlockZ()==z)tooClose=true;
                if(tooClose)continue;
                for(int y=Math.max(world.getMinHeight(),(int)portal.getY()-10);y<=portal.getY()+40;y++){
                    Material below=world.getBlockAt(x,y,z).getType();
                    if(below.isAir()||!below.isSolid())continue;
                    Material above1=world.getBlockAt(x,y+1,z).getType(),above2=world.getBlockAt(x,y+2,z).getType();
                    if(above1.isAir()&&above2.isAir()){found.add(new Location(world,x+0.5,y+1,z+0.5));break;}
                }
            }
        }
        return found;
    }
    /** initiateRespawn() doesn't create the dragon entity synchronously — vanilla's real respawn plays out
     *  over several seconds (pillar beams converge before the dragon actually appears), so checking
     *  battle.getEnderDragon() in the same tick was simply too early and always found null, logging a false
     *  "did not produce a dragon entity" warning even on a fully successful respawn. Poll instead, twice a
     *  second.
     *  Originally gave up after 20s ("normally done well within that") — live-confirmed 2026-08-02 that this
     *  was actively wrong, not just a rare edge case: the animation twice took ~40-50s on this server, so
     *  polling gave up right before the dragon actually appeared. The dragon still spawned moments later, but
     *  with nothing left listening for it, it silently never got the weeklyKey tag at all — the actual root
     *  cause of "weekly dragon gives no egg/no full XP" this whole time, separate from (and in addition to)
     *  the reward-gating bugs fixed earlier. Extended to 3 minutes; the cost of polling a little longer for a
     *  once-a-week (or admin-triggered) event is negligible.
     *  battle.getEnderDragon() resolves the fight's dragonUUID with no liveness check of its own — require
     *  !isDead() too, the same bar activeDragon() already holds every other dragon lookup in this file to. */
    private void pollForSpawnedDragon(DragonBattle battle,String occurrence,int attempt){
        EnderDragon dragon=battle.getEnderDragon();
        if(dragon!=null&&!dragon.isDead()){
            spawning=false;
            dragon.setPersistent(true);dragon.getPersistentDataContainer().set(weeklyKey,PersistentDataType.STRING,occurrence);dragon.customName(Component.text("Ender Dragon",NamedTextColor.DARK_PURPLE));dragon.setCustomNameVisible(true);
            db.state("weekly_dragon:active",dragon.getUniqueId().toString());db.history("SERVER",null,"DRAGON","The weekly Ender Dragon awakened.");
            plugin.getLogger().info("[WeeklyDragon] tagged "+dragon.getUniqueId()+" as weekly after "+(attempt*0.5)+"s of polling.");
            return;
        }
        if(attempt>=360){spawning=false;plugin.getLogger().warning("Weekly Ender Dragon respawn did not produce a dragon entity after 3 minutes of polling.");return;}
        plugin.getServer().getScheduler().runTaskLater(plugin,()->pollForSpawnedDragon(battle,occurrence,attempt+1),10L);
    }
    /** Radius 8 (17x17 chunks) matches — not just "covers" — the exact grid Paper's EnderDragonFight.isArenaLoaded()
     *  itself checks around the arena origin (confirmed by disassembly: it scans chunk offsets -8..+8 on both
     *  axes and requires every one at FULL/BLOCK_TICKING). The previous radius-4 (9x9) force-load was smaller
     *  than what vanilla actually requires, so isArenaLoaded() could still fail even with our own chunks pinned.
     *  getChunkAtAsync(x,z,true) only loads a chunk *once* — the "true" there is "generate if missing", not
     *  "keep loaded". Confirmed live: without setChunkForceLoaded(true), the chunk unloads again within moments
     *  of the future completing (nothing else references it with 0 players online), so a raw entity query even a
     *  few seconds after a "successful" respawn found nothing — the dragon didn't vanish, its chunk did. This is
     *  very likely the real reason the originally-reported dragon looked "stuck": entities in an unloaded chunk
     *  simply don't tick (no movement, no AI), which looks identical to "frozen in place" from a player's view. */
    private CompletableFuture<Void> loadPillarArea(World world){
        List<CompletableFuture<Chunk>> loads=new ArrayList<>();
        for(int cx=-8;cx<=8;cx++)for(int cz=-8;cz<=8;cz++){world.setChunkForceLoaded(cx,cz,true);loads.add(world.getChunkAtAsync(cx,cz,true));}
        return CompletableFuture.allOf(loads.toArray(new CompletableFuture[0]));
    }
    private void releasePillarArea(World world){for(int cx=-8;cx<=8;cx++)for(int cz=-8;cz<=8;cz++)world.setChunkForceLoaded(cx,cz,false);}
    /** Bounded safety net, not the normal release path (see attemptRespawn) — releases in 30 minutes unless a
     *  live weekly dragon is present, so a respawn nobody ever showed up for doesn't pin the arena forever. If a
     *  player arrives after this fires, tick() force-adds its own DRAGON chunk ticket the moment it sees them,
     *  so nothing is lost — the persisted respawn/dragon-killed state survives chunk unload just fine. */
    private void releasePillarAreaLater(World world){plugin.getServer().getScheduler().runTaskLater(plugin,()->{if(!active())releasePillarArea(world);},36000L);}
    void defeated(EnderDragon dragon){
        if(!dragon.getPersistentDataContainer().has(weeklyKey))return;String occurrence=dragon.getPersistentDataContainer().get(weeklyKey,PersistentDataType.STRING);db.state("weekly_dragon:last_defeated",occurrence==null?"unknown":occurrence);db.state("weekly_dragon:active","");db.history("SERVER",null,"DRAGON","The weekly Ender Dragon was defeated.");
        releasePillarArea(dragon.getWorld());
    }
    boolean isWeekly(Entity entity){return entity!=null&&entity.getPersistentDataContainer().has(weeklyKey);}
    boolean active(){World world=endWorld();return world!=null&&activeDragon(world)!=null&&isWeekly(activeDragon(world));}
    String countdown(){
        if(active())return"ACTIVE";ZonedDateTime now=ZonedDateTime.now(zone()),next=nextTarget(now);Duration duration=Duration.between(now,next);long days=duration.toDays(),hours=duration.toHours()%24,minutes=duration.toMinutes()%60;return(days>0?days+"d ":"")+hours+"h "+minutes+"m";
    }
    long nextEpochMillis(){return nextTarget(ZonedDateTime.now(zone())).toInstant().toEpochMilli();}
    String scheduleLabel(){return CoreUtil.pretty(day().name())+" "+time()+" "+zone().getId();}
    boolean selfTest(){return day()!=null&&time()!=null&&zone()!=null&&plugin.getConfig().getString("weekly-dragon.time","16:00").matches("\\d{2}:\\d{2}");}
    private EnderDragon activeDragon(World world){for(EnderDragon dragon:world.getEntitiesByClass(EnderDragon.class))if(!dragon.isDead())return dragon;return null;}
    private World endWorld(){return plugin.getServer().getWorlds().stream().filter(world->world.getEnvironment()==World.Environment.THE_END).findFirst().orElse(null);}
    private DayOfWeek day(){try{return DayOfWeek.valueOf(plugin.getConfig().getString("weekly-dragon.day","FRIDAY").toUpperCase(Locale.ROOT));}catch(Exception ignored){return DayOfWeek.FRIDAY;}}
    private LocalTime time(){try{return LocalTime.parse(plugin.getConfig().getString("weekly-dragon.time","16:00"));}catch(Exception ignored){return LocalTime.of(16,0);}}
    private ZoneId zone(){try{return ZoneId.of(plugin.getConfig().getString("weekly-dragon.timezone",ZoneId.systemDefault().getId()));}catch(Exception ignored){return ZoneId.systemDefault();}}
    private ZonedDateTime currentTarget(ZonedDateTime now){LocalDate date=now.toLocalDate().with(TemporalAdjusters.previousOrSame(day()));ZonedDateTime target=ZonedDateTime.of(date,time(),zone());if(now.isAfter(target.plusHours(6)))target=target.plusWeeks(1);return target;}
    private ZonedDateTime nextTarget(ZonedDateTime now){LocalDate date=now.toLocalDate().with(TemporalAdjusters.nextOrSame(day()));ZonedDateTime target=ZonedDateTime.of(date,time(),zone());if(!target.isAfter(now))target=target.plusWeeks(1);return target;}
    private void announceOnce(String key,String message){if(db.state(key)!=null)return;db.state(key,"1");broadcast(message,NamedTextColor.GOLD);}
    private void broadcast(String message,NamedTextColor color){for(org.bukkit.entity.Player player:plugin.getServer().getOnlinePlayers())if(plugin.settings().bossNotifications(player))player.sendMessage(Component.text(message,color));}
}
