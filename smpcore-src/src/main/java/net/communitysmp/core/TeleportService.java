package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class TeleportService {
    private record Pending(BukkitTask task, Location start, UUID observer) {}
    private record Request(String requester, long expires) {}
    private final SMPCore plugin; private final Database db;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> rtpCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID,Long>> pvpLinks = new ConcurrentHashMap<>();
    private final Set<UUID> rtpSearches = ConcurrentHashMap.newKeySet();
    private final Map<String, Request> requests = new ConcurrentHashMap<>();
    private final Map<UUID, Location> backLocations = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> rtpQueuePartner = new ConcurrentHashMap<>();
    private final Map<World.Environment,List<UUID>> rtpQueue = new EnumMap<>(World.Environment.class);
    private final BukkitTask combatBarTask;

    TeleportService(SMPCore plugin) {
        this.plugin = plugin; this.db = plugin.db();
        combatBarTask=plugin.getServer().getScheduler().runTaskTimer(plugin,()->{
            for(Player player:plugin.getServer().getOnlinePlayers()){
                long remaining=combatRemaining(player);
                if(remaining>0&&!plugin.privileged(player)&&!isPending(player))
                    player.sendActionBar(Component.text("PvP: "+remaining+"s",NamedTextColor.RED));
            }
        },20L,20L);
    }
    void shutdown(){combatBarTask.cancel();pending.values().forEach(active->active.task.cancel());pending.clear();}
    void warmup(Player player, Location destination, String label) { warmup(player, destination, label, null); }

    void warmup(Player player, Location destination, String label, Player observer) {
        warmup(player,destination,label,observer,plugin.getConfig().getInt("teleport.warmup-seconds",3),cooldowns,plugin.getConfig().getLong("teleport.cooldown-seconds",10),false);
    }

    private void warmup(Player player, Location destination, String label, Player observer,int seconds,Map<UUID,Long> cooldownMap,long cooldownSeconds,boolean recordGeneralCooldown) {
        if (destination == null || destination.getWorld() == null) { CoreUtil.error(player, "That destination is unavailable."); return; }
        if(plugin.privileged(player)){instant(player,destination,label,observer);return;}
        long combatWait=combatRemaining(player);
        if(combatWait>0){CoreUtil.error(player,"You cannot teleport for "+combatWait+" more second"+(combatWait==1?"":"s")+" after PvP.");return;}
        long now = System.currentTimeMillis(), cooldown = cooldownSeconds * 1000L;
        long last = cooldownMap.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < cooldown) { CoreUtil.error(player, "Teleport is cooling down for " + Math.max(1, (cooldown - (now - last) + 999) / 1000) + " seconds."); return; }
        if(cooldownMap!=cooldowns){long general=remainingCooldown(cooldowns,plugin.getConfig().getLong("teleport.cooldown-seconds",10),player.getUniqueId());if(general>0){CoreUtil.error(player,"Teleport is cooling down for "+general+" seconds.");return;}}
        cancel(player, null);
        CoreUtil.msg(player, "Teleporting to " + label + " in " + seconds + " seconds. Move or take damage to cancel.");
        if (observer != null) CoreUtil.msg(observer, plugin.nicknames().displayName(player) + " will arrive in " + seconds + " seconds.");
        Location start = player.getLocation().clone(); UUID observerId = observer == null ? null : observer.getUniqueId();
        BukkitRunnable countdown = new BukkitRunnable() {
            int remaining = seconds;
            @Override public void run() {
                if (!player.isOnline() || !pending.containsKey(player.getUniqueId())) { cancel(); return; }
                Player watching = observerId == null ? null : plugin.getServer().getPlayer(observerId);
                if (remaining > 0) {
                    player.sendActionBar(Component.text("Teleporting in " + remaining + "…", NamedTextColor.AQUA));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, .8f, 1.15f + (seconds - remaining) * .18f);
                    if (watching != null) {
                        watching.sendActionBar(Component.text(plugin.nicknames().displayName(player) + " arrives in " + remaining + "…", NamedTextColor.GOLD));
                        watching.playSound(watching.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, .8f, .7f + (seconds - remaining) * .08f);
                    }
                    remaining--; return;
                }
                cancel(); Pending current = pending.remove(player.getUniqueId()); if (current == null) return;
                player.teleportAsync(destination).thenAccept(ok -> {
                    if (ok) { long completed=System.currentTimeMillis();cooldownMap.put(player.getUniqueId(),completed);if(recordGeneralCooldown)cooldowns.put(player.getUniqueId(),completed);recordBackOrigin(player,start);rtpQueuePartner.remove(player.getUniqueId());player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, .7f, 1.25f); if (watching != null) watching.playSound(watching.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, .7f, .75f); CoreUtil.msg(player, "Teleported to " + label + "."); }
                    else CoreUtil.error(player, "Teleport failed; try again.");
                });
            }
        };
        BukkitTask task = countdown.runTaskTimer(plugin, 0L, 20L); pending.put(player.getUniqueId(), new Pending(task, start, observerId));
    }

    private void instant(Player player,Location destination,String label,Player observer){cancel(player,null);Location origin=player.getLocation().clone();player.teleportAsync(destination).thenAccept(ok->{if(!ok){CoreUtil.error(player,"Teleport failed; try again.");return;}recordBackOrigin(player,origin);player.playSound(player.getLocation(),Sound.ENTITY_ENDERMAN_TELEPORT,.7f,1.25f);CoreUtil.msg(player,"Teleported instantly to "+label+".");if(observer!=null&&observer.isOnline()){observer.playSound(observer.getLocation(),Sound.ENTITY_ENDERMAN_TELEPORT,.7f,.75f);CoreUtil.msg(observer,plugin.nicknames().displayName(player)+" arrived.");}});}
    /** Overwritten by whichever happens most recently — a successful teleport or a death — so /back always
     *  returns to "wherever you were right before you ended up here", matching either meaning at once. */
    private void recordBackOrigin(Player player,Location origin){if(origin!=null&&origin.getWorld()!=null)backLocations.put(player.getUniqueId(),origin.clone());}
    /** Admin-only recall command. Reversible: teleporting back also records where you just were, so a
     *  second /back returns to the spot you left. */
    boolean back(Player player){
        Location origin=backLocations.get(player.getUniqueId());
        if(origin==null||origin.getWorld()==null){CoreUtil.error(player,"No previous location recorded.");return true;}
        instant(player,origin,"your previous location",null);
        return true;
    }

    void onMove(Player p, Location to) { Pending active = pending.get(p.getUniqueId()); if (active == null || to == null) return; double tolerance = plugin.getConfig().getDouble("teleport.move-tolerance", .35); if (!to.getWorld().equals(active.start.getWorld()) || to.distanceSquared(active.start) > tolerance * tolerance) cancel(p, "Teleport cancelled because you moved."); }
    void onDamage(Player p) { cancel(p, "Teleport cancelled because you took damage."); }
    void onPvpHit(Player attacker,Player victim){if(attacker.equals(victim))return;long seconds=plugin.getConfig().getLong("teleport.pvp-lock-seconds",60),until=System.currentTimeMillis()+seconds*1000L;link(attacker.getUniqueId(),victim.getUniqueId(),until);cancel(attacker,"Teleport cancelled by PvP combat.");cancel(victim,"Teleport cancelled by PvP combat.");attacker.sendActionBar(Component.text("PvP teleport lock: "+seconds+"s",NamedTextColor.RED));victim.sendActionBar(Component.text("PvP teleport lock: "+seconds+"s",NamedTextColor.RED));}
    long combatRemaining(Player player){return combatRemaining(player.getUniqueId(),System.currentTimeMillis());}
    Player latestLivingOpponent(Player player){Map<UUID,Long> links=pvpLinks.get(player.getUniqueId());if(links==null)return null;long now=System.currentTimeMillis();return links.entrySet().stream().filter(entry->entry.getValue()>now).sorted(Map.Entry.<UUID,Long>comparingByValue().reversed()).map(entry->plugin.getServer().getPlayer(entry.getKey())).filter(Objects::nonNull).filter(opponent->opponent.isOnline()&&!opponent.isDead()).findFirst().orElse(null);}
    void onDeath(Player player){clearCombat(player.getUniqueId());cancel(player,null);recordBackOrigin(player,player.getLocation());}
    boolean isPending(Player p){return pending.containsKey(p.getUniqueId());}
    boolean clearCooldown(java.util.UUID id,String kind){switch(kind){case"rtp":rtpCooldowns.remove(id);return true;case"teleport":cooldowns.remove(id);return true;default:return false;}}
    /** Once both halves of an /rtp queue match are independently warming up (see searchRtpQueueMatch), they
     *  have no coupling at all by default — if one player moves, takes damage, or disconnects, the other's
     *  countdown would otherwise finish on its own and teleport them alone to what was meant to be a shared
     *  destination, with no explanation. rtpQueuePartner links the pair for the duration of that countdown
     *  so cancelling either side here cancels both, without naming who bailed — a generic reason only. */
    void cancel(Player p, String reason) {
        UUID partnerId=rtpQueuePartner.remove(p.getUniqueId());
        if(partnerId!=null){
            rtpQueuePartner.remove(partnerId);
            Player partner=plugin.getServer().getPlayer(partnerId);
            if(partner!=null){
                Pending partnerPending=pending.remove(partnerId);
                if(partnerPending!=null){partnerPending.task.cancel();CoreUtil.error(partner,"Your RTP queue match was cancelled because the other player left or cancelled. Use /rtp queue to try again.");}
            }
        }
        Pending active = pending.remove(p.getUniqueId()); if (active == null) return; active.task.cancel(); if (reason != null) CoreUtil.error(p, reason); if (active.observer != null) { Player observer = plugin.getServer().getPlayer(active.observer); if (observer != null) observer.sendActionBar(Component.text(plugin.nicknames().displayName(p) + "'s teleport was cancelled.", NamedTextColor.RED)); } }
    void quit(Player p) { cancel(p, null);rtpSearches.remove(p.getUniqueId()); requests.remove(CoreUtil.id(p)); leaveAllRtpQueues(p.getUniqueId()); }

    /** Dimension-matched RTP queue: /rtp queue toggles membership in the CALLER's current dimension's queue,
     *  and pairs the first two players queued in the same dimension to a single shared safe location — reusing
     *  the exact same async safe-location search as solo /rtp, just fired once for two destinations instead of
     *  one. Left untouched: solo /rtp itself, which is a completely separate code path. */
    boolean toggleRtpQueue(Player player){
        long combat=plugin.privileged(player)?0:combatRemaining(player);if(combat>0){CoreUtil.error(player,"You cannot queue for RTP while in combat. "+combat+"s remaining.");return true;}
        World.Environment env=player.getWorld().getEnvironment();
        List<UUID> queue=rtpQueue.computeIfAbsent(env,e->new ArrayList<>());
        if(queue.remove(player.getUniqueId())){CoreUtil.msg(player,"Left the "+CoreUtil.pretty(env.name())+" RTP queue.");return true;}
        leaveAllRtpQueues(player.getUniqueId());
        queue.add(player.getUniqueId());
        CoreUtil.msg(player,"Joined the "+CoreUtil.pretty(env.name())+" RTP queue. You'll be teleported once another player queues in the same dimension.");
        tryMatchRtpQueue(env);
        return true;
    }
    boolean isQueuedForRtp(Player player){return rtpQueue.getOrDefault(player.getWorld().getEnvironment(),List.of()).contains(player.getUniqueId());}
    void leaveAllRtpQueues(UUID id){for(List<UUID> queue:rtpQueue.values())queue.remove(id);}
    /** A dimension change invalidates the queue entry (matching is per-dimension) rather than silently moving
     *  it — the player is told plainly rather than being teleported somewhere they didn't expect to re-queue for. */
    void leaveRtpQueueOnWorldChange(Player player){
        boolean wasQueued=false;
        for(List<UUID> queue:rtpQueue.values())wasQueued|=queue.remove(player.getUniqueId());
        if(wasQueued)CoreUtil.msg(player,"You changed dimensions, so you were removed from the RTP queue. Use /rtp queue again if you still want it.");
    }
    private void tryMatchRtpQueue(World.Environment env){
        List<UUID> queue=rtpQueue.get(env);if(queue==null||queue.size()<2)return;
        UUID idA=queue.removeFirst(),idB=queue.removeFirst();
        Player a=plugin.getServer().getPlayer(idA),b=plugin.getServer().getPlayer(idB);
        if(a==null||!a.isOnline()){if(b!=null&&b.isOnline())queue.addFirst(idB);return;}
        if(b==null||!b.isOnline()){queue.addFirst(idA);return;}
        CoreUtil.msg(a,"Matched with "+b.getName()+"! Finding a safe shared location...");
        CoreUtil.msg(b,"Matched with "+a.getName()+"! Finding a safe shared location...");
        searchRtpQueueMatch(a,b,a.getWorld(),0);
    }
    private void searchRtpQueueMatch(Player a,Player b,World world,int attempt){
        if(!a.isOnline()||!b.isOnline()){if(a.isOnline())CoreUtil.error(a,"Your RTP queue match disconnected; use /rtp queue to try again.");if(b.isOnline())CoreUtil.error(b,"Your RTP queue match disconnected; use /rtp queue to try again.");return;}
        int max=Math.max(8,plugin.getConfig().getInt("rtp.attempts",32));
        if(attempt>=max){CoreUtil.error(a,"No safe shared RTP destination was found. Please try /rtp queue again.");CoreUtil.error(b,"No safe shared RTP destination was found. Please try /rtp queue again.");return;}
        String dimension=switch(world.getEnvironment()){case NORMAL->"overworld";case NETHER->"nether";case THE_END->"end";default->"overworld";};
        WorldBorder border=world.getWorldBorder();double configured=Math.max(1000,plugin.getConfig().getDouble("rtp.radii."+dimension,plugin.getConfig().getDouble("rtp.radius",5000))),half=Math.max(32,border.getSize()/2-32),radius=Math.min(configured,half);Location center=border.getCenter();
        int x=(int)Math.round(center.getX()+java.util.concurrent.ThreadLocalRandom.current().nextDouble(-radius,radius)),z=(int)Math.round(center.getZ()+java.util.concurrent.ThreadLocalRandom.current().nextDouble(-radius,radius));
        world.getChunkAtAsync(x>>4,z>>4,true).whenComplete((chunk,error)->plugin.getServer().getScheduler().runTask(plugin,()->{
            /** Unlike the check at the top of this method (which runs again on every retry), a disconnect
             *  that happens specifically while THIS async chunk load was in flight would otherwise return
             *  silently here and never reach that check again — leaving the survivor waiting forever with no
             *  explanation. Notify here too, generically, the same way. */
            if(!a.isOnline()||!b.isOnline()){if(a.isOnline())CoreUtil.error(a,"Your RTP queue match disconnected; use /rtp queue to try again.");if(b.isOnline())CoreUtil.error(b,"Your RTP queue match disconnected; use /rtp queue to try again.");return;}
            if(error!=null){searchRtpQueueMatch(a,b,world,attempt+1);return;}
            if(!a.getWorld().equals(world)||!b.getWorld().equals(world)){CoreUtil.error(a,"RTP queue match cancelled — a player changed dimensions.");CoreUtil.error(b,"RTP queue match cancelled — a player changed dimensions.");return;}
            Location safe=safeRtp(world,x,z);int distance=Math.max(500,plugin.getConfig().getInt("rtp.protected-distance",500));
            if(safe==null||!insideBorder(border,safe)||plugin.spawnClaims().near(safe,distance)||plugin.factions().nearClaim(safe,distance)||plugin.bosses().nearActiveEvent(safe,distance)){searchRtpQueueMatch(a,b,world,attempt+1);return;}
            String label="random "+CoreUtil.pretty(world.getEnvironment().name())+" wilderness (RTP queue match with "+"%s"+")";
            /** warmup() unconditionally calls cancel() on itself first (to clear out any unrelated pending
             *  teleport the player already had). If rtpQueuePartner were linked BEFORE calling warmup() here,
             *  that very first self-cancel would immediately tear the fresh link back down again (cancel()
             *  removes both directions of whatever partner link it finds) before the second warmup() even
             *  ran — silently disabling the cross-cancel safety net for every single queue match. Linking
             *  only after both warmups are already running avoids that self-destruction entirely. */
            warmup(a,safe.clone(),String.format(label,b.getName()),null,plugin.getConfig().getInt("rtp.warmup-seconds",3),cooldowns,plugin.getConfig().getLong("teleport.cooldown-seconds",10),false);
            warmup(b,safe.clone(),String.format(label,a.getName()),null,plugin.getConfig().getInt("rtp.warmup-seconds",3),cooldowns,plugin.getConfig().getLong("teleport.cooldown-seconds",10),false);
            /** warmup() can silently decline to start (combat lock, cooldown) and already messages the
             *  player why. Only link the pair if BOTH genuinely started counting down — linking a one-sided
             *  "pair" would let the side that failed later falsely cancel the side that succeeded the next
             *  time they touch any unrelated teleport. If only one side started, cancel that one too rather
             *  than leaving them warming up toward a partner who was never coming. */
            if(isPending(a)&&isPending(b)){
                rtpQueuePartner.put(a.getUniqueId(),b.getUniqueId());rtpQueuePartner.put(b.getUniqueId(),a.getUniqueId());
            }else{
                if(isPending(a))cancel(a,"Your RTP queue match could not proceed because your partner wasn't ready. Use /rtp queue again.");
                if(isPending(b))cancel(b,"Your RTP queue match could not proceed because your partner wasn't ready. Use /rtp queue again.");
            }
        }));
    }

    boolean rtp(Player player){
        long combat=plugin.privileged(player)?0:combatRemaining(player);if(combat>0){CoreUtil.error(player,"You cannot use RTP while in combat. "+combat+"s remaining.");return true;}
        long throttle=Math.max(3,Math.min(5,plugin.getConfig().getLong("rtp.request-throttle-seconds",4))),wait=remainingCooldown(rtpCooldowns,throttle,player.getUniqueId());if(wait>0&&!plugin.privileged(player))return true;
        if(!rtpSearches.add(player.getUniqueId())){CoreUtil.msg(player,"A safe wilderness location is already being found.");return true;}
        rtpCooldowns.put(player.getUniqueId(),System.currentTimeMillis());CoreUtil.msg(player,"Searching for a safe "+CoreUtil.pretty(player.getWorld().getEnvironment().name())+" destination.");
        searchRtp(player,player.getWorld(),0);return true;
    }

    private void searchRtp(Player player,World world,int attempt){
        if(!player.isOnline()){rtpSearches.remove(player.getUniqueId());return;}int max=Math.max(8,plugin.getConfig().getInt("rtp.attempts",32));
        if(attempt>=max){rtpSearches.remove(player.getUniqueId());CoreUtil.error(player,"No safe RTP destination was found. Please try again.");return;}
        if(world==null){rtpSearches.remove(player.getUniqueId());CoreUtil.error(player,"The RTP world is unavailable.");return;}
        String dimension=switch(world.getEnvironment()){case NORMAL->"overworld";case NETHER->"nether";case THE_END->"end";default->"overworld";};WorldBorder border=world.getWorldBorder();double configured=Math.max(1000,plugin.getConfig().getDouble("rtp.radii."+dimension,plugin.getConfig().getDouble("rtp.radius",5000))),half=Math.max(32,border.getSize()/2-32),radius=Math.min(configured,half);Location center=border.getCenter();
        int x=(int)Math.round(center.getX()+java.util.concurrent.ThreadLocalRandom.current().nextDouble(-radius,radius)),z=(int)Math.round(center.getZ()+java.util.concurrent.ThreadLocalRandom.current().nextDouble(-radius,radius));
        world.getChunkAtAsync(x>>4,z>>4,true).whenComplete((chunk,error)->plugin.getServer().getScheduler().runTask(plugin,()->{
            if(!rtpSearches.contains(player.getUniqueId())||!player.isOnline())return;if(error!=null){searchRtp(player,world,attempt+1);return;}if(!player.getWorld().equals(world)){rtpSearches.remove(player.getUniqueId());CoreUtil.error(player,"RTP cancelled because you changed dimensions.");return;}Location safe=safeRtp(world,x,z);int distance=Math.max(500,plugin.getConfig().getInt("rtp.protected-distance",500));
            if(safe==null||!insideBorder(border,safe)||plugin.spawnClaims().near(safe,distance)||plugin.factions().nearClaim(safe,distance)||plugin.bosses().nearActiveEvent(safe,distance)){searchRtp(player,world,attempt+1);return;}
            rtpSearches.remove(player.getUniqueId());
            warmup(
                    player,
                    safe,
                    "random " + CoreUtil.pretty(world.getEnvironment().name()) + " wilderness",
                    null,
                    plugin.getConfig().getInt("rtp.warmup-seconds", 3),
                    cooldowns,
                    plugin.getConfig().getLong("teleport.cooldown-seconds", 10),
                    false
            );
        }));
    }
    private Location safeRtp(World world,int x,int z){
        if(world.getEnvironment()==World.Environment.NORMAL)return CoreUtil.findSafe(world,x,z);
        if(world.getEnvironment()==World.Environment.NETHER){
            int max=Math.min(world.getMaxHeight()-10,120),min=world.getMinHeight()+6;for(int y=max;y>=min;y--){Block floor=world.getBlockAt(x,y-1,z),feet=world.getBlockAt(x,y,z),head=world.getBlockAt(x,y+1,z);if(safeFloor(floor)&&feet.isPassable()&&head.isPassable()&&!feet.isLiquid()&&!head.isLiquid())return new Location(world,x+.5,y,z+.5);}
            return null;
        }
        Block floor=world.getHighestBlockAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES);int y=floor.getY()+1;if(y<=world.getMinHeight()+15)return null;Block feet=world.getBlockAt(x,y,z),head=world.getBlockAt(x,y+1,z);if(!safeFloor(floor)||!feet.isPassable()||!head.isPassable())return null;int support=0;for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)if(world.getBlockAt(x+dx,floor.getY(),z+dz).getType().isSolid())support++;return support>=14?new Location(world,x+.5,y,z+.5):null;
    }
    private boolean safeFloor(Block block){Material material=block.getType();return material.isSolid()&&!Set.of(Material.LAVA,Material.MAGMA_BLOCK,Material.CACTUS,Material.FIRE,Material.SOUL_FIRE,Material.POWDER_SNOW,Material.BEDROCK).contains(material);}

    private boolean insideBorder(WorldBorder border,Location location){double half=border.getSize()/2-2,dx=Math.abs(location.getX()-border.getCenter().getX()),dz=Math.abs(location.getZ()-border.getCenter().getZ());return dx<=half&&dz<=half;}
    private long remainingCooldown(Map<UUID,Long> source,long seconds,UUID player){long remaining=seconds*1000L-(System.currentTimeMillis()-source.getOrDefault(player,0L));return remaining<=0?0:Math.max(1,(remaining+999)/1000);}
    private void link(UUID first,UUID second,long until){pvpLinks.computeIfAbsent(first,id->new ConcurrentHashMap<>()).put(second,until);pvpLinks.computeIfAbsent(second,id->new ConcurrentHashMap<>()).put(first,until);}
    private long combatRemaining(UUID player,long now){Map<UUID,Long> opponents=pvpLinks.get(player);if(opponents==null)return 0;opponents.entrySet().removeIf(entry->entry.getValue()<=now);if(opponents.isEmpty()){pvpLinks.remove(player);return 0;}long latest=opponents.values().stream().mapToLong(Long::longValue).max().orElse(now);return Math.max(1,(latest-now+999)/1000);}
    private void clearCombat(UUID player){pvpLinks.remove(player);for(var entry:pvpLinks.entrySet())entry.getValue().remove(player);pvpLinks.entrySet().removeIf(entry->entry.getValue().isEmpty());}
    boolean combatSelfTest(){long now=System.currentTimeMillis();UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();link(a,b,now+60000);link(a,c,now+60000);clearCombat(b);boolean partial=combatRemaining(a,now)>0&&combatRemaining(b,now)==0;clearCombat(c);boolean cleared=combatRemaining(a,now)==0;clearCombat(a);return partial&&cleared;}

    boolean tpa(Player from, String targetName) {
        long combatWait=plugin.privileged(from)?0:combatRemaining(from);
        if(combatWait>0){CoreUtil.error(from,"You cannot request a teleport for "+combatWait+" more second"+(combatWait==1?"":"s")+" after PvP.");return true;}
        Player target=plugin.nicknames().findVisiblePlayer(targetName);
        if(target==null||target.equals(from)){CoreUtil.error(from,"That player is not available.");return true;}
        if(!plugin.settings().tpaRequests(target)){CoreUtil.error(from,plugin.nicknames().displayName(target)+" is not accepting teleport requests.");return true;}
        if(plugin.afk().isAfk(target))CoreUtil.msg(from,plugin.nicknames().displayName(target)+" is currently AFK; the request may sit unanswered for a while.");
        long seconds=Math.max(1,plugin.getConfig().getLong("teleport.request-seconds",60)),expiry=System.currentTimeMillis()+seconds*1000L;
        String targetId=CoreUtil.id(target);Request request=new Request(CoreUtil.id(from),expiry);requests.put(targetId,request);
        CoreUtil.msg(from,"Teleport request sent to "+plugin.nicknames().displayName(target)+". It expires in "+seconds+" seconds.");
        CoreUtil.msg(target,plugin.nicknames().displayName(from)+" wants to teleport to you. Use /tpaccept or /tpdeny.");
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{
            if(!requests.remove(targetId,request))return;
            Player requester=findById(request.requester),receiver=findById(targetId);
            if(requester!=null)CoreUtil.error(requester,"Your teleport request expired.");
            if(receiver!=null)CoreUtil.error(receiver,"The teleport request expired.");
        },seconds*20L);
        return true;
    }
    boolean accept(Player target) { Request request = requests.remove(CoreUtil.id(target)); if (request == null || request.expires < System.currentTimeMillis()) { CoreUtil.error(target, "You have no active teleport request."); return true; } Player requester = findById(request.requester); if (requester == null) { CoreUtil.error(target, "That player is no longer online."); return true; } CoreUtil.msg(target, "Accepted " + plugin.nicknames().displayName(requester) + "'s request."); warmup(requester, target.getLocation(), plugin.nicknames().displayName(target), target); return true; }
    boolean deny(Player target) { Request request = requests.remove(CoreUtil.id(target)); if (request == null) { CoreUtil.error(target, "You have no active teleport request."); return true; } Player requester = findById(request.requester); if (requester != null) CoreUtil.error(requester, plugin.nicknames().displayName(target) + " denied your request."); CoreUtil.msg(target, "Teleport request denied."); return true; }
    private Player findById(String id) { for (Player p : plugin.getServer().getOnlinePlayers()) if (CoreUtil.id(p).equals(id)) return p; return null; }

    boolean homeCommand(Player p,String[] args){if(args.length==0)return personalHome(p,"home");String sub=args[0].toLowerCase(Locale.ROOT);return switch(sub){case"list","homes"->listPersonalHomes(p);case"set"->{if(args.length<2){CoreUtil.error(p,"Usage: /home set <name>");yield true;}yield setPersonalHome(p,args[1]);}case"delete","del","remove"->{if(args.length<2){CoreUtil.error(p,"Usage: /home delete <name>");yield true;}yield deletePersonalHome(p,args[1]);}case"rename"->{if(args.length<3){CoreUtil.error(p,"Usage: /home rename <old> <new>");yield true;}yield renamePersonalHome(p,args[1],args[2]);}case"buy"->buyPersonalHome(p,args.length>1&&args[1].equalsIgnoreCase("confirm"));case"go"->{if(args.length<2){CoreUtil.error(p,"Usage: /home go <name>");yield true;}yield personalHome(p,args[1]);}case"help"->homeHelp(p);default->personalHome(p,args[0]);};}
    private boolean homeHelp(Player p){CoreUtil.msg(p,"Homes: /home <name>, /home list, /home set <name>, /home delete <name>, /home rename <old> <new>, /home buy");return true;}
    boolean personalHome(Player p, String name) { Database.HomeRow home = db.home(CoreUtil.id(p), "PERSONAL", name == null ? "home" : name); if (home == null) { CoreUtil.error(p, "Home not found. Use /home list."); return true; } warmup(p, home.location(), "home '" + home.name() + "'"); return true; }
    boolean setPersonalHome(Player p, String name) { String n = name == null ? "home" : name; if (!n.matches("[A-Za-z0-9_]{1,16}")) { CoreUtil.error(p, "Home names use letters, numbers, or underscores."); return true; } Database.PlayerRow row = db.player(CoreUtil.id(p)); List<Database.HomeRow> homes = db.homes(CoreUtil.id(p), "PERSONAL"); if (db.home(CoreUtil.id(p), "PERSONAL", n) == null && homes.size() >= row.personalSlots()) { CoreUtil.error(p, "No free home slot. Use /buyhome."); return true; } db.putHome(CoreUtil.id(p), "PERSONAL", n, p.getLocation()); CoreUtil.msg(p, "Personal home '" + n + "' set."); return true; }
    boolean deletePersonalHome(Player p, String name) { String n = name == null ? "home" : name;if(db.home(CoreUtil.id(p),"PERSONAL",n)==null){CoreUtil.error(p,"Home '"+n+"' does not exist.");return true;}db.deleteHome(CoreUtil.id(p), "PERSONAL", n); CoreUtil.msg(p, "Personal home '" + n + "' deleted."); return true; }
    boolean renamePersonalHome(Player p,String oldName,String newName){if(!newName.matches("[A-Za-z0-9_]{1,16}")){CoreUtil.error(p,"Home names use 1-16 letters, numbers, or underscores.");return true;}String owner=CoreUtil.id(p);Database.HomeRow old=db.home(owner,"PERSONAL",oldName);if(old==null){CoreUtil.error(p,"Home '"+oldName+"' does not exist.");return true;}if(!oldName.equalsIgnoreCase(newName)&&db.home(owner,"PERSONAL",newName)!=null){CoreUtil.error(p,"Home '"+newName+"' already exists.");return true;}if(!db.renameHome(owner,"PERSONAL",oldName,newName)){CoreUtil.error(p,"That home could not be renamed.");return true;}CoreUtil.msg(p,"Renamed home '"+old.name()+"' to '"+newName+"'.");return true;}
    boolean listPersonalHomes(Player p) { return listPersonalHomes(p,false); }
    boolean listPersonalHomes(Player p,boolean coords) { Database.PlayerRow row = db.player(CoreUtil.id(p)); List<Database.HomeRow> homes=db.homes(CoreUtil.id(p),"PERSONAL");CoreUtil.msg(p,"Personal homes ("+homes.size()+"/"+row.personalSlots()+")");if(homes.isEmpty()){CoreUtil.msg(p,"None set. Use /home set <name>.");return true;}for(Database.HomeRow home:homes){Location loc=home.location();String where=loc.getWorld()==null?"unknown world":CoreUtil.pretty(loc.getWorld().getEnvironment().name());if(coords&&loc.getWorld()!=null)where+=" — X "+loc.getBlockX()+", Y "+loc.getBlockY()+", Z "+loc.getBlockZ();CoreUtil.msg(p,"• "+home.name()+" — "+where);}CoreUtil.msg(p,"Teleport with /home <name>.");return true; }
    boolean buyPersonalHome(Player p, boolean confirm) { Database.PlayerRow row = db.player(CoreUtil.id(p)); if (row.personalSlots() >= 10) { CoreUtil.msg(p, "You already have all 10 personal home slots."); return true; } int next = row.personalSlots() + 1, cost = plugin.getConfig().getInt("homes.personal.upgrades." + next); CoreUtil.msg(p, "Personal home slot " + next + " costs " + CoreUtil.money(cost) + ". Balance: " + CoreUtil.money(row.balance())); if (!confirm) { CoreUtil.msg(p, "Purchase with /home buy confirm."); return true; } if(!plugin.bank().allowNonessential(p,"extra home slots"))return true;if (!plugin.bank().payServer(p,cost,"SINK","PERSONAL_HOME_"+next)) { CoreUtil.error(p, "You cannot afford this slot."); return true; }db.recordEconomy(CoreUtil.id(p),"UPGRADE_SINK",-cost,"PERSONAL_HOME_"+next); db.setPersonalSlots(CoreUtil.id(p), next); CoreUtil.msg(p, "Personal home slot " + next + " unlocked."); return true; }

    Location spawn() { String raw = db.state("server_spawn"); if (raw != null) { String[] a = raw.split(",", 6); World w = plugin.getServer().getWorld(a[0]); if (w != null) try { return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]), Float.parseFloat(a[4]), Float.parseFloat(a[5])); } catch (Exception ignored) {} } World world = plugin.getServer().getWorld(plugin.getConfig().getString("claims.world", "world")); return world == null ? plugin.getServer().getWorlds().getFirst().getSpawnLocation() : world.getSpawnLocation(); }
    void setSpawn(Location location) { db.state("server_spawn", String.join(",", location.getWorld().getName(), Double.toString(location.getX()), Double.toString(location.getY()), Double.toString(location.getZ()), Float.toString(location.getYaw()), Float.toString(location.getPitch()))); }
}
