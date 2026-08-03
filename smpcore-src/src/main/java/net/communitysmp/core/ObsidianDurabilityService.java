package net.communitysmp.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Inside a protected faction claim, obsidian survives repeated explosion hits instead of breaking on the
 *  first one — hidden durability, no visible block-state change, purely a raid-pacing mechanic. Outside a
 *  claim, obsidian is untouched vanilla. State is deliberately in-memory only (no DB writes per hit — a
 *  restart just resets in-progress damage, which is an acceptable tradeoff for avoiding write pressure during
 *  an active raid) and there is no per-tick scan: damage is applied only from the explosion events that
 *  already fire, and regeneration is checked on a slow periodic sweep over the (small) set of currently
 *  damaged blocks, never over every obsidian block on the server. */
final class ObsidianDurabilityService {
    private record Pos(String world,int x,int y,int z){}
    private static final int MAX_HITS=5;
    private final SMPCore plugin;
    private final Map<Pos,Integer> hits=new ConcurrentHashMap<>();
    private final Map<Pos,Integer> lastHitTick=new ConcurrentHashMap<>();
    private final Map<Pos,Long> lastHitAt=new ConcurrentHashMap<>();
    private BukkitTask regenTask;

    ObsidianDurabilityService(SMPCore plugin){
        this.plugin=plugin;
        regenTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::regenTick,1200L,1200L);
    }
    void shutdown(){if(regenTask!=null)regenTask.cancel();}

    /** Filters `candidates` (already confirmed OBSIDIAN inside a protected faction claim by the caller) down
     *  to the blocks that should be removed from this explosion's blockList — i.e. the ones that survive.
     *  `source` is whoever caused the explosion, if identifiable (used only for player feedback). */
    List<Block> absorb(List<Block> candidates,Entity source){
        if(candidates.isEmpty())return List.of();
        int tick=Bukkit.getServer().getCurrentTick();
        Player attacker=attackerOf(source);
        List<Block> survivors=new ArrayList<>();
        Set<Pos> seenThisExplosion=new HashSet<>();
        for(Block block:candidates){
            if(block.getType()!=Material.OBSIDIAN)continue;
            Pos pos=new Pos(block.getWorld().getName(),block.getX(),block.getY(),block.getZ());
            if(!seenThisExplosion.add(pos))continue;
            Integer lastTick=lastHitTick.get(pos);
            if(lastTick!=null&&lastTick==tick)continue;
            lastHitTick.put(pos,tick);lastHitAt.put(pos,System.currentTimeMillis());
            int count=hits.merge(pos,1,Integer::sum);
            if(count<MAX_HITS){
                survivors.add(block);
                if(attacker!=null)CoreUtil.msg(attacker,"The obsidian held under the blast.");
            }else{
                hits.remove(pos);lastHitTick.remove(pos);lastHitAt.remove(pos);
                if(attacker!=null)CoreUtil.msg(attacker,"The obsidian finally gave way.");
            }
        }
        return survivors;
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
    private long regenMillis(){return Math.max(60,plugin.getConfig().getLong("claims.obsidian-regen-minutes",15))*60000L;}
}
