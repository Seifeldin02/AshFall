package net.communitysmp.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Inside a protected faction claim, obsidian survives repeated explosion hits instead of breaking on the
 *  first one — hidden durability, no visible block-state change, purely a raid-pacing mechanic. Outside a
 *  claim, obsidian is untouched vanilla.
 *
 *  IMPORTANT: this does NOT work by filtering EntityExplodeEvent.blockList() — obsidian's vanilla blast
 *  resistance (1200) is so far above what a single TNT's power (4) can overcome (~71x TNT needed on one
 *  block, and stacking TNT only widens the blast radius, never concentrates more force on one block — this
 *  is well-documented vanilla behavior, confirmed against the Minecraft Wiki) that obsidian NEVER appears in
 *  blockList() for any realistic explosion. An earlier version of this class filtered blockList() and never
 *  actually landed a hit, because there was never a candidate to process. Instead, every explosion is scanned
 *  for nearby claimed obsidian directly (by location/radius, independent of what vanilla decided would
 *  break), and a block that reaches its 5th hit is broken here explicitly (block.setType(AIR)) rather than
 *  relying on vanilla destruction.
 *
 *  State is deliberately in-memory only (no DB writes per hit — a restart resets in-progress damage, an
 *  acceptable tradeoff for avoiding write pressure during an active raid) and there is no per-tick scan:
 *  damage is applied only from the explosion events that already fire, regeneration is checked on a slow
 *  periodic sweep over the (small) set of currently damaged blocks, never over every obsidian block on the
 *  server. */
final class ObsidianDurabilityService {
    private record Pos(String world,int x,int y,int z){
        Location toLocation(){World w=Bukkit.getWorld(world);return w==null?null:new Location(w,x+.5,y+.5,z+.5);}
    }
    private static final int MAX_HITS=5;
    /** A single TNT's blast wave reaches roughly this far before losing enough intensity to affect blocks —
     *  not an attempt to replicate vanilla's exact ray-traced falloff (explicitly out of scope), just a
     *  reasonable, fixed "was this obsidian within the blast" radius for game-balance purposes. */
    private static final double BLAST_RADIUS=4.5;
    private final SMPCore plugin;
    private final FactionService factions;
    private final Map<Pos,Integer> hits=new ConcurrentHashMap<>();
    private final Map<Pos,Integer> lastHitTick=new ConcurrentHashMap<>();
    private final Map<Pos,Long> lastHitAt=new ConcurrentHashMap<>();
    private BukkitTask regenTask;

    ObsidianDurabilityService(SMPCore plugin,FactionService factions){
        this.plugin=plugin;this.factions=factions;
        regenTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::regenTick,1200L,1200L);
    }
    void shutdown(){if(regenTask!=null)regenTask.cancel();}

    /** Called from GameplayListener's explode()/blockExplode() with the explosion's own center location.
     *  `source` is whoever caused it, if identifiable (used only for player feedback) — null for
     *  BlockExplodeEvent (beds, respawn anchors), which has no source entity. */
    void registerExplosion(Location center,Entity source){
        if(center==null||center.getWorld()==null)return;
        World world=center.getWorld();
        int tick=Bukkit.getServer().getCurrentTick();
        Player attacker=attackerOf(source);
        Set<Pos> seenThisExplosion=new HashSet<>();
        int r=(int)Math.ceil(BLAST_RADIUS),cx=center.getBlockX(),cy=center.getBlockY(),cz=center.getBlockZ();
        double radiusSq=BLAST_RADIUS*BLAST_RADIUS;
        for(int dx=-r;dx<=r;dx++)for(int dy=-r;dy<=r;dy++)for(int dz=-r;dz<=r;dz++){
            if(dx*dx+dy*dy+dz*dz>radiusSq)continue;
            Block block=world.getBlockAt(cx+dx,cy+dy,cz+dz);
            if(block.getType()!=Material.OBSIDIAN)continue;
            if(factions.claimAt(block.getLocation())==null)continue;
            Pos pos=new Pos(world.getName(),block.getX(),block.getY(),block.getZ());
            if(!seenThisExplosion.add(pos))continue;
            Integer lastTick=lastHitTick.get(pos);
            if(lastTick!=null&&lastTick==tick)continue;
            lastHitTick.put(pos,tick);lastHitAt.put(pos,System.currentTimeMillis());
            int count=hits.merge(pos,1,Integer::sum);
            if(count<MAX_HITS){
                if(attacker!=null)CoreUtil.msg(attacker,"The obsidian held under the blast — "+(MAX_HITS-count)+" more hit(s) should do it.");
            }else{
                hits.remove(pos);lastHitTick.remove(pos);lastHitAt.remove(pos);
                block.setType(Material.AIR);
                world.playSound(block.getLocation(),Sound.ENTITY_GENERIC_EXPLODE,1f,.6f);
                if(attacker!=null)CoreUtil.msg(attacker,"The obsidian finally gave way.");
            }
        }
    }

    private Player attackerOf(Entity source){
        if(source instanceof Player player)return player;
        if(source instanceof TNTPrimed tnt&&tnt.getSource() instanceof Player player)return player;
        return null;
    }

    private void regenTick(){
        long cutoff=System.currentTimeMillis()-regenMillis();
        lastHitAt.entrySet().removeIf(entry->{
            if(entry.getValue()>cutoff)return false;
            Pos pos=entry.getKey();hits.remove(pos);lastHitTick.remove(pos);return true;
        });
    }
    /** Was Math.max(60, minutes) — since this whole value is already in MINUTES, that silently forced a
     *  60-MINUTE floor regardless of config (the 15-minute default, and any staging test override, never
     *  actually took effect; live-tested and confirmed: hits kept accumulating instead of resetting). The
     *  guard should only stop a config of 0/negative from meaning "instant regen", not force an hour minimum. */
    private long regenMillis(){return Math.max(1,plugin.getConfig().getLong("claims.obsidian-regen-minutes",15))*60000L;}
}
