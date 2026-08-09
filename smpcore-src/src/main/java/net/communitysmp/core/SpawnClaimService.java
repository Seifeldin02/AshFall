package net.communitysmp.core;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class SpawnClaimService implements Listener {
    record Region(String world,int minX,int maxX,int minZ,int maxZ){
        boolean contains(Location location){return location.getWorld()!=null&&location.getWorld().getName().equals(world)&&location.getBlockX()>=minX&&location.getBlockX()<=maxX&&location.getBlockZ()>=minZ&&location.getBlockZ()<=maxZ;}
        boolean overlaps(String otherWorld,int otherMinX,int otherMaxX,int otherMinZ,int otherMaxZ){return world.equals(otherWorld)&&minX<=otherMaxX&&maxX>=otherMinX&&minZ<=otherMaxZ&&maxZ>=otherMinZ;}
        int width(){return maxX-minX+1;}
        int depth(){return maxZ-minZ+1;}
    }

    private final SMPCore plugin;
    private final Database db;
    private final FactionService factions;
    private final NamespacedKey allowedEntityKey;
    private final Map<UUID,Location> firstCorners=new HashMap<>();
    private Region region;
    private BukkitTask sweepTask;

    SpawnClaimService(SMPCore plugin,FactionService factions){this.plugin=plugin;this.db=plugin.db();this.factions=factions;this.allowedEntityKey=new NamespacedKey(plugin,"spawn_allowed_entity");load();
        /** A single boot-time sweep isn't enough — anything that slips past the movement/spawn blockers
         *  (edge cases in mob conversion, vehicle physics, etc.) would otherwise sit inside indefinitely
         *  until the next admin redefinition. Re-running this regularly makes the zone self-healing. */
        sweepTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::ejectUnmarked,20L,100L);
    }
    /** Clearing the team on the way out is the other half of the persistence fix: Minecraft saves scoreboard
     *  teams and their members to the world's scoreboard.dat, so anything still listed here at shutdown comes
     *  back on the next boot -- with collision still disabled -- whether or not spawn protection still
     *  applies to that player, or even still exists. */
    void shutdown(){
        if(sweepTask!=null)sweepTask.cancel();
        clearCollisionTeam();
    }
    private void clearCollisionTeam(){
        for(Scoreboard board:boards()){
            Team team=board.getTeam(COLLISION_TEAM);
            if(team==null)continue;
            for(String entry:new java.util.HashSet<>(team.getEntries()))team.removeEntry(entry);
        }
    }
    /** Every scoreboard a player could currently be displaying, plus the main one. UIService hands
     *  sidebar-enabled players their own board, so an entry written to one board is invisible to the other --
     *  which is how somebody ended up permanently non-collidable after a board switch. */
    private java.util.List<Scoreboard> boards(){
        java.util.List<Scoreboard> boards=new java.util.ArrayList<>();
        Scoreboard main=mainBoard();
        if(main!=null)boards.add(main);
        for(Player player:plugin.getServer().getOnlinePlayers()){
            Scoreboard board=player.getScoreboard();
            if(board!=null&&boards.stream().noneMatch(existing->existing.equals(board)))boards.add(board);
        }
        return boards;
    }
    private Scoreboard mainBoard(){
        org.bukkit.scoreboard.ScoreboardManager manager=plugin.getServer().getScoreboardManager();
        return manager==null?null:manager.getMainScoreboard();
    }
    @EventHandler public void vehicleMove(VehicleMoveEvent event){
        if(region==null||event.getVehicle().getPassengers().stream().allMatch(passenger->passenger instanceof Player||allowed(passenger)))return;
        if(!region.contains(event.getFrom())&&region.contains(event.getTo()))event.getVehicle().teleport(event.getFrom());
    }
    /** An empty (or hostile-occupied) vehicle rammed into a player is a known displacement trick — ramming
     *  doesn't move the player via EntityMoveEvent (that only fires for the vehicle's own movement), it
     *  shoves them directly, so it needs its own check rather than relying on the movement/knockback guards. */
    @EventHandler(ignoreCancelled=true) public void vehicleCollide(VehicleEntityCollisionEvent event){
        if(region==null||!(event.getEntity() instanceof Player player)||plugin.privileged(player)||!region.contains(player.getLocation()))return;
        event.setCancelled(true);event.setCollisionCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void knockback(EntityKnockbackEvent event){
        if(region==null||!(event.getEntity() instanceof Player player)||plugin.privileged(player)||!region.contains(player.getLocation()))return;
        event.setCancelled(true);
    }
    /** Fishing rods can hook and reel in players, not just fish — dragging someone across the boundary that
     *  way needs its own check since the "victim" here isn't the one being damaged or knocked back, they're
     *  being pulled by another player's fishing mechanic. Checked from both ends, same as the spawn-PvP rule. */
    @EventHandler(ignoreCancelled=true) public void fish(PlayerFishEvent event){
        if(region==null||event.getState()!=PlayerFishEvent.State.CAUGHT_ENTITY||!(event.getCaught() instanceof Player caught))return;
        if(plugin.privileged(event.getPlayer())||plugin.privileged(caught))return;
        if(region.contains(caught.getLocation())||region.contains(event.getPlayer().getLocation()))event.setCancelled(true);
    }

    boolean contains(Location location){return region!=null&&region.contains(location);}
    boolean near(Location location,int radius){if(region==null||location.getWorld()==null||!location.getWorld().getName().equals(region.world()))return false;int x=location.getBlockX(),z=location.getBlockZ();return x>=region.minX()-radius&&x<=region.maxX()+radius&&z>=region.minZ()-radius&&z<=region.maxZ()+radius;}
    boolean overlaps(String world,int minX,int maxX,int minZ,int maxZ){return region!=null&&region.overlaps(world,minX,maxX,minZ,maxZ);}
    boolean selecting(Player player){return firstCorners.containsKey(player.getUniqueId());}
    void allow(Entity entity){entity.getPersistentDataContainer().set(allowedEntityKey,PersistentDataType.BYTE,(byte)1);}
    boolean allowed(Entity entity){return entity.getPersistentDataContainer().has(allowedEntityKey,PersistentDataType.BYTE);}

    boolean selectClick(Player player,Block block){
        if(!selecting(player))return false;
        Location clicked=block.getLocation();
        Location first=firstCorners.get(player.getUniqueId());
        if(first==null){firstCorners.put(player.getUniqueId(),clicked);CoreUtil.msg(player,"Spawn corner 1 set at X "+clicked.getBlockX()+", Z "+clicked.getBlockZ()+". Left-click the opposite corner.");return true;}
        if(!first.getWorld().equals(clicked.getWorld())){firstCorners.put(player.getUniqueId(),clicked);CoreUtil.error(player,"Both corners must be in one world. This click is now corner 1.");return true;}
        int minX=Math.min(first.getBlockX(),clicked.getBlockX()),maxX=Math.max(first.getBlockX(),clicked.getBlockX()),minZ=Math.min(first.getBlockZ(),clicked.getBlockZ()),maxZ=Math.max(first.getBlockZ(),clicked.getBlockZ());
        String world=clicked.getWorld().getName();
        if(factions.overlaps(world,minX,maxX,minZ,maxZ)){CoreUtil.error(player,"That spawn area overlaps faction land. Choose another opposite corner.");return true;}
        region=new Region(world,minX,maxX,minZ,maxZ);
        db.state("spawn_claim",String.join(",",world,Integer.toString(minX),Integer.toString(maxX),Integer.toString(minZ),Integer.toString(maxZ)));
        firstCorners.remove(player.getUniqueId());
        ejectUnmarked();
        CoreUtil.msg(player,"Spawn protected: "+region.width()+" x "+region.depth()+" blocks across the full world height.");
        if(!region.contains(plugin.teleports().spawn()))CoreUtil.error(player,"The current /spawn point is outside this area. Use /ashfall setspawn inside it if needed.");
        return true;
    }

    void command(CommandSender sender,String[] args){
        if(args.length<2){help(sender);return;}
        switch(args[1].toLowerCase()){
            case"select"->{if(!(sender instanceof Player player)){CoreUtil.error(sender,"Run selection in game.");return;}firstCorners.put(player.getUniqueId(),null);CoreUtil.msg(player,"Spawn selection started. Left-click two opposite block corners.");}
            case"info"->{if(region==null)CoreUtil.msg(sender,"No spawn protection area is set.");else CoreUtil.msg(sender,"Spawn: "+region.world()+" X "+region.minX()+".."+region.maxX()+", Z "+region.minZ()+".."+region.maxZ()+" ("+region.width()+" x "+region.depth()+", full height).");}
            case"clear"->{region=null;firstCorners.clear();db.state("spawn_claim","");CoreUtil.msg(sender,"Spawn protection area cleared.");}
            default->help(sender);
        }
    }

    private void help(CommandSender sender){CoreUtil.msg(sender,"Spawn protection: /ashfall spawnclaim select, /ashfall spawnclaim info, /ashfall spawnclaim clear");}
    private void load(){String raw=db.state("spawn_claim");if(raw==null||raw.isBlank())return;try{String[] values=raw.split(",",5);region=new Region(values[0],Integer.parseInt(values[1]),Integer.parseInt(values[2]),Integer.parseInt(values[3]),Integer.parseInt(values[4]));}catch(Exception e){plugin.getLogger().warning("Ignored invalid spawn claim state.");}}
    /** Hostile mobs that wander (or get displaced) into spawn are REMOVED outright rather than bounced back
     *  outside — bouncing only relocates them a couple of blocks past the border, and a mob still pathing or
     *  aggroed toward someone just inside can simply walk straight back in before the next sweep, which reads
     *  as "stuck"/oscillating rather than actually being cleaned up. Non-hostile entities (villagers, tamed
     *  pets, named mobs) keep the original bounce-out behavior — removing those would be destructive. */
    private static final String COLLISION_TEAM="ashfallnocollide";
    /** Entity#setCollidable(false) does NOT stop player-vs-player pushing — the client predicts that
     *  collision locally regardless of the server-side flag, a well-documented Bukkit/Paper limitation
     *  (confirmed against PaperMC/Paper#376 and the Bukkit forums). The only mechanism that actually works
     *  for players is a Scoreboard Team with COLLISION_RULE=NEVER. Teams live on a specific Scoreboard
     *  object, and UIService hands sidebar-enabled players their own per-player Scoreboard (not the shared
     *  main one) — so the team has to be looked up/created on whichever board the player is CURRENTLY
     *  displaying, every sweep, not just on the main scoreboard once. */
    private void applyCollision(Player player,boolean inside){
        /** Written to the player's own board AND the main board. Mirroring both means a scoreboard swap can
         *  never strand a membership on the board that is no longer being displayed. */
        setMembership(player.getScoreboard(),player,inside);
        Scoreboard main=mainBoard();
        if(main!=null&&!main.equals(player.getScoreboard()))setMembership(main,player,inside);
    }
    private void setMembership(Scoreboard board,Player player,boolean inside){
        if(board==null)return;
        Team team=board.getTeam(COLLISION_TEAM);
        if(team==null)team=board.registerNewTeam(COLLISION_TEAM);
        /** Re-asserted every time: a team restored from scoreboard.dat can come back with a different rule. */
        team.setOption(Team.Option.COLLISION_RULE,Team.OptionStatus.NEVER);
        boolean has=team.hasEntry(player.getName());
        if(inside&&!has)team.addEntry(player.getName());
        else if(!inside&&has)team.removeEntry(player.getName());
    }
    /** Drops memberships belonging to players who are not online. The sweep only ever visited online players,
     *  so an offline entry was never revisited -- it simply persisted, and the player came back with player
     *  collision still switched off for them wherever they went. */
    private void pruneOfflineMemberships(){
        for(Scoreboard board:boards()){
            Team team=board.getTeam(COLLISION_TEAM);
            if(team==null)continue;
            for(String entry:new java.util.HashSet<>(team.getEntries())){
                Player online=plugin.getServer().getPlayerExact(entry);
                if(online==null||region==null||!region.contains(online.getLocation()))team.removeEntry(entry);
            }
        }
    }
    /** Anything that moves a player in or out of spawn reconciles at once instead of waiting up to 5s for the
     *  sweep, which is what made the behaviour feel session-dependent. */
    @EventHandler public void collisionJoin(org.bukkit.event.player.PlayerJoinEvent event){reconcile(event.getPlayer());}
    @EventHandler public void collisionRespawn(org.bukkit.event.player.PlayerRespawnEvent event){reconcile(event.getPlayer());}
    @EventHandler public void collisionWorld(org.bukkit.event.player.PlayerChangedWorldEvent event){reconcile(event.getPlayer());}
    @EventHandler public void collisionTeleport(org.bukkit.event.player.PlayerTeleportEvent event){reconcile(event.getPlayer());}
    private void reconcile(Player player){
        plugin.getServer().getScheduler().runTask(plugin,()->{
            if(!player.isOnline())return;
            applyCollision(player,region!=null&&region.contains(player.getLocation()));
        });
    }
    private void ejectUnmarked(){
        /** Runs on this same 5s sweep across ALL online players (not just those currently in the spawn world,
         *  and even with no region set at all) so anyone who leaves the region — or the world, or has
         *  protection cleared entirely — reliably gets collision back rather than being stuck non-collidable. */
        for(Player player:plugin.getServer().getOnlinePlayers())applyCollision(player,region!=null&&region.contains(player.getLocation()));
        pruneOfflineMemberships();
        if(region==null)return;org.bukkit.World world=plugin.getServer().getWorld(region.world());if(world==null)return;
        for(LivingEntity entity:world.getLivingEntities()){
            if(entity instanceof Player||!region.contains(entity.getLocation())||allowed(entity))continue;
            if(entity instanceof Enemy&&!(entity instanceof Tameable tame&&tame.isTamed())&&entity.customName()==null)entity.remove();
            else entity.teleport(outside(entity.getLocation()));
        }
    }
    private Location outside(Location from){int x=from.getBlockX(),z=from.getBlockZ();int left=Math.abs(x-region.minX()),right=Math.abs(region.maxX()-x),north=Math.abs(z-region.minZ()),south=Math.abs(region.maxZ()-z),nearest=Math.min(Math.min(left,right),Math.min(north,south));if(nearest==left)x=region.minX()-2;else if(nearest==right)x=region.maxX()+2;else if(nearest==north)z=region.minZ()-2;else z=region.maxZ()+2;Location safe=CoreUtil.findSafe(from.getWorld(),x,z);if(safe!=null)return safe;return from.getWorld().getHighestBlockAt(x,z).getLocation().add(.5,1,.5);}
}
