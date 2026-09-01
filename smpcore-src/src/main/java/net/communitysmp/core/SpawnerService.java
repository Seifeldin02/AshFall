package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

final class SpawnerService {
    private final SMPCore plugin;
    private final Database db;
    private final FactionService factions;
    private final NamespacedKey typeKey,placedKey,historiesKey,stackKey,identityKey,identitiesKey,spawnerMobKey,raidFactionKey,virtualKey,sourceIdsKey,bulkKey;
    private final Map<UUID,Long> warned=new HashMap<>();
    private final Map<String,Long> miningStarted=new HashMap<>();
    private final Map<String,Long> tntOwners=new HashMap<>();
    private final Map<UUID,String> hoverLabels=new HashMap<>();
    private BukkitTask hoverTask;

    SpawnerService(SMPCore plugin,FactionService factions){
        this.plugin=plugin;this.db=plugin.db();this.factions=factions;
        sourceIdsKey=new NamespacedKey(plugin,"spawner_source_ids");bulkKey=new NamespacedKey(plugin,"stack_bulk_resolved");virtualKey=new NamespacedKey(plugin,"virtual_stack");typeKey=new NamespacedKey(plugin,"spawner_type");placedKey=new NamespacedKey(plugin,"placed_spawner");historiesKey=new NamespacedKey(plugin,"spawner_histories");stackKey=new NamespacedKey(plugin,"spawner_stack");identityKey=new NamespacedKey(plugin,"spawner_identity");identitiesKey=new NamespacedKey(plugin,"spawner_identities");spawnerMobKey=new NamespacedKey(plugin,"spawner_mob");raidFactionKey=new NamespacedKey(plugin,"raid_faction");
        plugin.getServer().getScheduler().runTaskLater(plugin,this::migrateLoadedFactionSpawners,100L);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::logGolemDayRollover,1200L,6000L);
        hoverTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::hoverTick,10L,
                Math.max(5L,plugin.getConfig().getLong("performance.spawner-hover-ticks",10)));
    }
    void shutdown(){if(hoverTask!=null)hoverTask.cancel();hoverLabels.clear();}
    boolean isHovering(Player player){return hoverLabels.containsKey(player.getUniqueId());}

    private void migrateLoadedFactionSpawners(){
        long now=System.currentTimeMillis();for(World world:plugin.getServer().getWorlds())for(Chunk chunk:world.getLoadedChunks())for(org.bukkit.block.BlockState state:chunk.getTileEntities())if(state instanceof CreatureSpawner spawner&&spawner.getPersistentDataContainer().has(placedKey,PersistentDataType.BYTE)){
            int[] recoveries=histories(spawner);String[] ids=identities(spawner,recoveries.length);setIdentities(spawner,ids);spawner.update(true);FactionService.Claim claim=factions.claimAt(spawner.getLocation());if(claim!=null)for(String id:ids)plugin.progress().factionSpawnerPlaced(claim.faction(),id,spawner.getSpawnedType(),now,spawner.getLocation());
        }
    }

    void damage(BlockDamageEvent event){
        if(event.getBlock().getType()!=Material.SPAWNER)return;if(!(event.getBlock().getState() instanceof CreatureSpawner spawner))return;
        if(!validTool(event.getItemInHand())){warn(event.getPlayer());return;}int recovery=selectedHistory(spawner);if(recovery<1)return;
        /** No hold-to-break timer any more (it stacked awkwardly with the durability cost) -- just a one-off
         *  heads-up that recovering the spawner costs tool durability. */
        long now=System.currentTimeMillis();if(now-warned.getOrDefault(event.getPlayer().getUniqueId(),0L)>1500){warned.put(event.getPlayer().getUniqueId(),now);CoreUtil.error(event.getPlayer(),"Recovering this spawner costs "+plugin.getConfig().getInt("spawner-breaking.relocated-durability-cost",512)+" tool durability.");}
    }

    boolean breaking(BlockBreakEvent event){
        Block block=event.getBlock();if(block.getType()!=Material.SPAWNER)return false;Player player=event.getPlayer();if(!(block.getState() instanceof CreatureSpawner spawner)){event.setCancelled(true);return true;}
        int[] histories=histories(spawner);String[] identities=identities(spawner,histories.length);int selected=histories[histories.length-1];String selectedIdentity=identities[identities.length-1];EntityType mobType=spawner.getSpawnedType();ItemStack tool=player.getInventory().getItemInMainHand();boolean pickup=validTool(tool);
        event.setCancelled(true);event.setDropItems(false);event.setExpToDrop(0);
        if(histories.length>1){setHistories(spawner,Arrays.copyOf(histories,histories.length-1));setIdentities(spawner,Arrays.copyOf(identities,identities.length-1));spawner.update(true);plugin.getServer().getScheduler().runTask(plugin,()->plugin.netWorth().blockChanged(block));}
        else{plugin.netWorth().removed(block);block.setType(Material.AIR,false);}
        miningStarted.remove(miningKey(player,block));
        if(!pickup){
            /** Destroyed, not recovered. The spawner is gone from the world either way, so it goes to the
             *  server vault with its type intact rather than simply ceasing to exist. Exactly one entry per
             *  destroyed physical spawner: a stack that loses one member records one, because this branch
             *  runs once per break and the rest of the stack is still standing. */
            if(plugin.vault()!=null)plugin.vault().deliverSpawner(mobType,1,block.getLocation(),"DESTROYED");
            /** ...and to the Spawner Shop, so it can be bought back. This is the path a VANILLA spawner takes
             *  too: mined without a netherite silk-touch pickaxe it is destroyed for XP and nobody gets the
             *  block, which is exactly "left the world without reaching an inventory". Treated identically to
             *  a placed spawner for that reason. */
            if(plugin.spawnerShop()!=null)plugin.spawnerShop().recover(mobType,1,block.getLocation(),"MINED_WITHOUT_RECOVERY");
            warn(player);int min=Math.max(0,plugin.getConfig().getInt("spawner-breaking.exp-min",15)),max=Math.max(min,plugin.getConfig().getInt("spawner-breaking.exp-max",43));if(player.getGameMode()!=GameMode.CREATIVE)player.giveExp(ThreadLocalRandom.current().nextInt(min,max+1));
            if(player.getGameMode()!=GameMode.CREATIVE){double reward=Math.max(0,plugin.getConfig().getDouble("spawner-breaking.money-reward",25));if(reward>0){plugin.creditEarned(CoreUtil.id(player),reward,"SPAWNER_BREAK");db.recordEconomy(CoreUtil.id(player),"EXPLORATION",reward,"SPAWNER_BREAK");player.sendActionBar(Component.text("+"+CoreUtil.money(reward)+" for destroying a spawner",NamedTextColor.GREEN));}}
            return true;
        }
        ItemStack recovered=createItem(mobType,selected+1,selectedIdentity);CoreUtil.give(player,recovered);damageTool(player,selected>=1?plugin.getConfig().getInt("spawner-breaking.relocated-durability-cost",512):1);player.removePotionEffect(PotionEffectType.MINING_FATIGUE);player.playSound(player.getLocation(),Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,.7f,1.3f);CoreUtil.msg(player,"Recovered one "+CoreUtil.pretty(mobType.name())+" spawner"+(histories.length>1?" • "+(histories.length-1)+" remain":"")+".");return true;
    }

    void placed(BlockPlaceEvent event){
        if(event.getBlockPlaced().getType()!=Material.SPAWNER||!(event.getBlockPlaced().getState() instanceof CreatureSpawner spawner))return;ItemStack item=event.getItemInHand();String type=item.hasItemMeta()?item.getItemMeta().getPersistentDataContainer().get(typeKey,PersistentDataType.STRING):null;
        if(type==null){event.setCancelled(true);CoreUtil.error(event.getPlayer(),"Only naturally recovered SMPCore spawners may be placed.");return;}
        try{EntityType entityType=EntityType.valueOf(type);String identity=item.getItemMeta().getPersistentDataContainer().getOrDefault(identityKey,PersistentDataType.STRING,UUID.randomUUID().toString());spawner.setSpawnedType(entityType);spawner.getPersistentDataContainer().set(placedKey,PersistentDataType.BYTE,(byte)1);setHistories(spawner,new int[]{item.getItemMeta().getPersistentDataContainer().getOrDefault(historiesKey,PersistentDataType.INTEGER,1)});setIdentities(spawner,new String[]{identity});tunePlacedSpawner(spawner);spawner.update(true);FactionService.Claim claim=factions.claimAt(event.getBlockPlaced().getLocation());if(claim!=null)plugin.progress().factionSpawnerPlaced(claim.faction(),identity,entityType,System.currentTimeMillis(),event.getBlockPlaced().getLocation());CoreUtil.msg(event.getPlayer(),"Placed "+CoreUtil.pretty(type)+" Spawner ×1.");}catch(IllegalArgumentException ex){event.setCancelled(true);CoreUtil.error(event.getPlayer(),"That spawner type is invalid.");}
    }

    /** NOTE for anyone chasing "my Iron Golem spawner produces nothing": the cause is not in this class.
     *  IronGolem#checkSpawnObstruction demands a block it can stand on directly beneath, and BaseSpawner
     *  calls it unconditionally, so a golem spawner hung over an open drop -- the shape every farm here is
     *  built to -- spawns absolutely nothing and reports nothing. Paper's own switch for that is
     *  `entities.spawning.iron-golems-can-spawn-in-air` in config/paper-world-defaults.yml, which this
     *  server sets to true. Every other mob is unaffected either way: Mob.checkMobSpawnRules already
     *  waives the ground test for spawner spawns.
     *
     *  Placed spawners are the server's farming investment, so they get a genuinely upgraded cycle that
     *  natural world spawners never receive: a short 5-10s delay, vanilla's nearby-mob suppression lifted
     *  so a full farm does not throttle itself, and a generous activation range. Applied only to blocks
     *  carrying placedKey, so untouched world spawners stay exactly vanilla.
     *  Note the spawn COUNT is deliberately left at 1 -- stack output is produced in spawned() by the
     *  stack multiplier, and raising both would multiply production twice. */
    private void tunePlacedSpawner(CreatureSpawner spawner){
        if(!spawner.getPersistentDataContainer().has(placedKey,PersistentDataType.BYTE))return;
        int min=Math.max(20,plugin.getConfig().getInt("spawners.min-delay-ticks",100));
        int max=Math.max(min,plugin.getConfig().getInt("spawners.max-delay-ticks",200));
        spawner.setMinSpawnDelay(min);spawner.setMaxSpawnDelay(max);spawner.setDelay(min);
        spawner.setSpawnCount(1);
        /** Vanilla throttles a spawner once a few mobs of its type are nearby. With virtual stacking the
         *  representative count stays tiny anyway, so this ceiling is raised right out of the way: a placed
         *  spawner's rate is governed by its cycle alone and never slows because entities are present. */
        spawner.setMaxNearbyEntities(Math.max(64,plugin.getConfig().getInt("spawners.max-nearby-entities",2048)));
        spawner.setRequiredPlayerRange(Math.max(16,plugin.getConfig().getInt("spawners.required-player-range",32)));
        spawner.setSpawnRange(Math.max(2,plugin.getConfig().getInt("spawners.spawn-range",4)));
    }
    boolean stack(PlayerInteractEvent event){
        if(!event.getAction().isRightClick()||event.getClickedBlock()==null||event.getClickedBlock().getType()!=Material.SPAWNER||event.getItem()==null||!event.getItem().hasItemMeta())return false;
        String itemType=event.getItem().getItemMeta().getPersistentDataContainer().get(typeKey,PersistentDataType.STRING);if(itemType==null)return false;if(!(event.getClickedBlock().getState() instanceof CreatureSpawner spawner))return false;event.setCancelled(true);
        if(!spawner.getSpawnedType().name().equals(itemType)){CoreUtil.error(event.getPlayer(),"Only identical spawner types can stack.");return true;}int[] old=histories(spawner);int max=Math.max(1,plugin.getConfig().getInt("spawners.max-stack",10));if(old.length>=max){CoreUtil.error(event.getPlayer(),"This spawner stack is full ("+max+").");return true;}
        int[] next=Arrays.copyOf(old,old.length+1);next[next.length-1]=event.getItem().getItemMeta().getPersistentDataContainer().getOrDefault(historiesKey,PersistentDataType.INTEGER,1);String[] ids=identities(spawner,old.length),nextIds=Arrays.copyOf(ids,ids.length+1);nextIds[nextIds.length-1]=event.getItem().getItemMeta().getPersistentDataContainer().getOrDefault(identityKey,PersistentDataType.STRING,UUID.randomUUID().toString());setHistories(spawner,next);setIdentities(spawner,nextIds);tunePlacedSpawner(spawner);spawner.update(true);FactionService.Claim claim=factions.claimAt(event.getClickedBlock().getLocation());if(claim!=null)plugin.progress().factionSpawnerPlaced(claim.faction(),nextIds[nextIds.length-1],spawner.getSpawnedType(),System.currentTimeMillis(),event.getClickedBlock().getLocation());consume(event.getPlayer(),event.getHand());plugin.netWorth().blockChanged(event.getClickedBlock());CoreUtil.msg(event.getPlayer(),CoreUtil.pretty(itemType)+" Spawner ×"+next.length);return true;
    }

    /** Virtual mob stacking for PLAYER-PLACED spawners.
     *
     *  A farm keeps only a handful of real entities. Every further spawn folds into one of them, raising
     *  its internal count (rendered as e.g. "Blaze x50") instead of adding another physical mob, and a new
     *  representative is only created once the current one hits the per-entity cap. Killing a representative
     *  resolves the whole stack at once -- loot, XP, money and kill accounting all multiplied a single time,
     *  never by re-running vanilla's own reward path.
     *
     *  This is what makes a placed spawner competitive with a natural farm without being a lag machine:
     *  throughput is limited by the spawn CYCLE, not by an entity budget, so it does not slow down or stop
     *  because mobs are already present. Natural world spawners are untouched and behave exactly as vanilla.
     */
    private org.bukkit.scheduler.BukkitTask consolidateTask;
    void startConsolidation(){
        long period=Math.max(20,plugin.getConfig().getLong("spawners.consolidate-interval-ticks",40));
        consolidateTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::consolidateStacks,period,period);
    }
    void stopConsolidation(){if(consolidateTask!=null)consolidateTask.cancel();}
    void spawned(SpawnerSpawnEvent event){
        if(event.getEntity() instanceof Enemy&&pauseHostileSpawner(event.getSpawner())){event.setCancelled(true);return;}
        CreatureSpawner spawner=event.getSpawner();
        if(!spawner.getPersistentDataContainer().has(placedKey,PersistentDataType.BYTE))return;
        /** Re-tune on every cycle, not just at placement. Tuning used to happen ONLY when a spawner was
         *  placed or stacked, so every spawner that already existed kept vanilla's 200-800 tick delay --
         *  an average of 500 ticks, i.e. the 25s that was measured, instead of the intended 5-10s. Doing it
         *  here is cheap and makes every existing placed spawner heal itself on its next cycle. */
        if(spawner.getMinSpawnDelay()!=plugin.getConfig().getInt("spawners.min-delay-ticks",100)
                ||spawner.getMaxSpawnDelay()!=plugin.getConfig().getInt("spawners.max-delay-ticks",200)){
            tunePlacedSpawner(spawner);spawner.update(true);
        }
        if(!(event.getEntity() instanceof LivingEntity spawned))return;
        /** One spawner cycle contributes its whole stack size at once, so a x10 spawner adds 10 to the
         *  virtual count per cycle -- the stack multiplier lives here and nowhere else, which is what keeps
         *  production from being multiplied twice. */
        int add=Math.max(1,stackSize(spawner));
        int cap=Math.max(2,plugin.getConfig().getInt("spawners.virtual-stack-cap",100));
        /** Stamp the mob with the identities of the spawner that produced it. The payout allowance is
         *  charged against those identities, not against the killer, so it scales with the represented
         *  spawner count and cannot be reset by rearranging blocks. */
        int units=Math.max(1,stackSize(spawner));
        String[] unitIds=identities(spawner,units);
        String source=String.join(",",unitIds);
        /** identities() mints a fresh UUID for any slot it finds empty and does NOT store it, so without
         *  persisting here a spawner that never went through placed() would hand out new identities every
         *  cycle and its daily allowance would reset continuously. Written only when it actually differs. */
        if(!source.equals(spawner.getPersistentDataContainer().get(identitiesKey,PersistentDataType.STRING))){setIdentities(spawner,unitIds);spawner.update(true);}
        LivingEntity host=findStackHost(spawned,cap);
        if(host!=null){
            setVirtualStack(host,Math.min(cap,virtualStack(host)+add));
            /** UNION, not overwrite. A representative can carry mobs from more than one spawner -- two golem
             *  spawners in the same room merge into one entity -- and the daily allowance is granted PER
             *  represented spawner. Overwriting the stamp with whichever spawner fired last silently halved
             *  a two-spawner farm's allowance and charged the whole stack to one of them. Nobody would ever
             *  notice except as "my second 50m spawner earned me nothing extra". */
            host.getPersistentDataContainer().set(sourceIdsKey,PersistentDataType.STRING,mergedSources(host,source));
            event.setCancelled(true);
            return;
        }
        setVirtualStack(spawned,Math.min(cap,add));
        spawned.getPersistentDataContainer().set(sourceIdsKey,PersistentDataType.STRING,source);
    }
    /** Nearest living representative of the same type that still has room. Deliberately a small radius:
     *  representatives should cluster at the farm, not merge across a whole chunk. */
    /** Consolidation sweep. Merging previously happened only at the instant of spawning, so two
     *  representatives that were created apart and later drifted together (Blazes especially, since they
     *  fly) stayed separate forever -- the reported "Blaze x30 and Blaze x40 side by side". This runs on a
     *  timer and folds any compatible neighbouring stack into the fullest one, so a farm naturally settles
     *  into as few entities as the cap allows. */
    void consolidateStacks(){
        int cap=Math.max(2,plugin.getConfig().getInt("spawners.virtual-stack-cap",100));
        double radius=Math.max(2,plugin.getConfig().getDouble("spawners.virtual-merge-radius",12));
        for(World world:plugin.getServer().getWorlds()){
            java.util.List<LivingEntity> stacked=new java.util.ArrayList<>();
            for(Entity entity:world.getEntities())
                if(entity instanceof LivingEntity living&&!living.isDead()&&virtualStack(living)>0)stacked.add(living);
            if(stacked.size()<2)continue;
            /** Fullest first, and ties broken by id so the pass is deterministic rather than dependent on
             *  whatever order the world happened to return entities in. */
            stacked.sort(java.util.Comparator.<LivingEntity>comparingInt(e->-virtualStack(e))
                    .thenComparing(e->e.getUniqueId()));
            java.util.Set<UUID> consumed=new java.util.HashSet<>();
            for(LivingEntity host:stacked){
                if(consumed.contains(host.getUniqueId()))continue;
                int hostStack=virtualStack(host);
                if(hostStack>=cap)continue;
                for(LivingEntity other:stacked){
                    if(other==host||consumed.contains(other.getUniqueId())||other.isDead())continue;
                    if(other.getType()!=host.getType()||!other.getWorld().equals(host.getWorld()))continue;
                    if(other.getLocation().distanceSquared(host.getLocation())>radius*radius)continue;
                    int room=cap-hostStack;
                    if(room<=0)break;
                    int donor=virtualStack(other);
                    /** A representative at the cap is never a donor. It used to be: the cap check above only
                     *  stopped a full stack acting as HOST, so an x40 host would take 60 from an x100
                     *  neighbour and the two would trade places -- then trade back on the next sweep, which
                     *  is the counts visibly swapping back and forth. */
                    if(donor<=0||donor>=cap)continue;
                    /** Only absorb a donor that fits whole. Partial transfers are the only way a surviving
                     *  representative can lose count, so refusing them means every merge either removes the
                     *  donor outright or does nothing -- counts can rise, never shuffle. */
                    if(donor>room)continue;
                    hostStack+=donor;
                    /** Carry the donor's spawner stamp across before it is removed, for the same reason the
                     *  spawn-time merge unions rather than overwrites: the donor's spawners are still
                     *  represented by the surviving entity and must keep counting toward the allowance. */
                    String donorSource=other.getPersistentDataContainer().get(sourceIdsKey,PersistentDataType.STRING);
                    if(donorSource!=null&&!donorSource.isBlank())
                        host.getPersistentDataContainer().set(sourceIdsKey,PersistentDataType.STRING,mergedSources(host,donorSource));
                    consumed.add(other.getUniqueId());other.remove();
                }
                if(hostStack!=virtualStack(host))setVirtualStack(host,hostStack);
            }
        }
    }
    /** Existing stamp plus the incoming one, de-duplicated and order-stable. Capped so a farm that merges
     *  endlessly cannot grow an unbounded string in entity NBT; the cap is far above any real stack. */
    private String mergedSources(LivingEntity host,String incoming){
        java.util.LinkedHashSet<String> ids=new java.util.LinkedHashSet<>();
        String existing=host.getPersistentDataContainer().get(sourceIdsKey,PersistentDataType.STRING);
        for(String part:(existing==null?"":existing).split(","))if(!part.isBlank())ids.add(part);
        for(String part:(incoming==null?"":incoming).split(","))if(!part.isBlank())ids.add(part);
        int max=Math.max(1,plugin.getConfig().getInt("spawners.max-source-identities",32));
        if(ids.size()>max)return String.join(",",new java.util.ArrayList<>(ids).subList(0,max));
        return String.join(",",ids);
    }

    private LivingEntity findStackHost(LivingEntity spawned,int cap){
        double radius=Math.max(2,plugin.getConfig().getDouble("spawners.virtual-merge-radius",6));
        LivingEntity best=null;int bestStack=-1;
        for(Entity nearby:spawned.getWorld().getNearbyEntities(spawned.getLocation(),radius,radius,radius)){
            if(!(nearby instanceof LivingEntity other)||other.isDead()||other==spawned)continue;
            if(other.getType()!=spawned.getType())continue;
            int stack=virtualStack(other);
            if(stack<=0||stack>=cap)continue;
            /** Prefer the fullest one under the cap so stacks fill up rather than spreading thin. */
            if(stack>bestStack){bestStack=stack;best=other;}
        }
        return best;
    }
    /** The spawner units that produced this mob. Empty when the mob did not come from a placed spawner. */
    java.util.List<String> sourceIdentities(Entity entity){
        String raw=entity==null?null:entity.getPersistentDataContainer().get(sourceIdsKey,PersistentDataType.STRING);
        if(raw==null||raw.isBlank())return java.util.List.of();
        java.util.List<String> out=new java.util.ArrayList<>();
        for(String part:raw.split(","))if(!part.isBlank())out.add(part);
        return out;}

    /** Logs the previous reward day's golem-spawner totals when the 12:00 Asia/Riyadh day rolls over, so
     *  the model can be retuned later from measured server data rather than from estimates. */
    private String lastLoggedDay=CoreUtil.riyadhDay();
    void logGolemDayRollover(){
        String today=CoreUtil.riyadhDay();
        if(today.equals(lastLoggedDay))return;
        double[] totals=db.golemDaily(lastLoggedDay);
        plugin.getLogger().info("[golem-spawner] "+lastLoggedDay+": represented kills="+(long)totals[0]+", payout="+CoreUtil.money(totals[1]));
        lastLoggedDay=today;}

    int virtualStack(Entity entity){
        if(entity==null)return 0;
        return entity.getPersistentDataContainer().getOrDefault(virtualKey,PersistentDataType.INTEGER,0);
    }
    private void setVirtualStack(LivingEntity entity,int amount){
        entity.getPersistentDataContainer().set(virtualKey,PersistentDataType.INTEGER,amount);
        entity.getPersistentDataContainer().set(spawnerMobKey,PersistentDataType.BYTE,(byte)1);
        if(amount>1){
            entity.customName(net.kyori.adventure.text.Component.text(CoreUtil.pretty(entity.getType().name())+" ",net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .append(net.kyori.adventure.text.Component.text("x"+amount,net.kyori.adventure.text.format.NamedTextColor.AQUA)));
            /** Deliberately NOT setCustomNameVisible(true). That flag renders the label permanently, through
             *  terrain, out to the full 64-block name distance -- which turned every stacked farm into a
             *  marker visible from across the map and gave away bases. Left false, the label behaves like any
             *  other named mob: it appears when a player actually looks at it from close range. The label
             *  itself is unchanged. */
            entity.setCustomNameVisible(false);
        }else{
            /** Back down to a single mob: drop the stack label rather than leaving a stale "x2" on it. */
            entity.customName(null);
            entity.setCustomNameVisible(false);
        }
        /** A representative carrying many mobs' worth of value must not quietly despawn and take the whole
         *  stack with it; the cap plus the small merge radius keeps the entity count tiny regardless. */
        entity.setRemoveWhenFarAway(amount<=1);
    }
    /** Resolves a stacked representative on death: everything it represents pays out once, together.
     *  Runs at HIGH so it sees the final vanilla drop list (including Looting) and multiplies THAT, rather
     *  than re-running any reward logic and risking a double payout. Drops are merged into full stacks so
     *  a x100 kill produces a handful of item entities instead of hundreds. */
    /*  What a stacked death owes, computed once, without ever building the items.
     *
     *  A x1000 zombie stack produces two thousand rotten flesh. Cloning an ItemStack per represented mob to
     *  find that out is the expensive half of the problem, so the plan is arithmetic on the drop list the
     *  event already carries: each plain drop's amount times the stack size, each drop carrying custom data
     *  kept aside to be handed over as real items, and the experience multiplied the same way.
     *
     *  Pure, so the conservation properties can be asserted without a mob, a player or a world. */
    record BulkPlan(java.util.Map<Material,Integer> plain,java.util.List<ItemStack> keep,int experience){
        int plainTotal(){int sum=0;for(int amount:plain.values())sum+=amount;return sum;}
    }

    static BulkPlan planStack(java.util.List<ItemStack> drops,int stack,int experienceEach){
        java.util.Map<Material,Integer> plain=new java.util.LinkedHashMap<>();
        java.util.List<ItemStack> keep=new java.util.ArrayList<>();
        for(ItemStack drop:drops){
            if(drop==null||drop.getType().isAir())continue;
            if(drop.hasItemMeta()&&(drop.getItemMeta().hasDisplayName()||drop.getItemMeta().hasEnchants())){
                for(int i=0;i<stack;i++)keep.add(drop.clone());
            }else plain.merge(drop.getType(),drop.getAmount()*stack,Integer::sum);
        }
        return new BulkPlan(plain,keep,experienceEach*stack);
    }

    /** Splits a plan's plain materials by whether the shop currently buys them. Unsellables are never
     *  discarded -- they are handed to the killer through the ordinary give-or-drop path. */
    private java.util.Map<Material,Integer> sellablePart(java.util.Map<Material,Integer> plain,boolean wanted){
        java.util.Map<Material,Integer> out=new java.util.LinkedHashMap<>();
        ShopService shop=plugin.shop();
        for(var entry:plain.entrySet())
            if(shop!=null&&shop.buysBack(entry.getKey())==wanted)out.put(entry.getKey(),entry.getValue());
        return out;
    }

    /*  Resolves a stack of a thousand or more into money and experience instead of item entities.
     *
     *  At a x1000 stack the ordinary path is not merely slow, it is a different kind of event: two thousand
     *  rotten flesh is thirty-two item entities that immediately begin merging, plus a thousand experience
     *  orbs, all in one tick, on a server where somebody built the farm precisely because they intend to do
     *  it repeatedly.
     *
     *  So the sellable half never becomes items at all. It goes through the shop's own bulk sale, which is
     *  the same quote, the same daily threshold and reduced rate, the same treasury check and loan garnish,
     *  and the same stock and economy accounting a manual sale would have produced -- the ONLY difference is
     *  that there is nothing to take out of an inventory. Anything the shop does not buy is aggregated into
     *  whole stacks and handed over as real items, because deleting it would be a loss the player never
     *  agreed to. Experience is granted as a single number rather than as a thousand orbs.
     *
     *  Anything that stops the sale -- no killer, an offline killer, a treasury that cannot cover it --
     *  falls straight back to the ordinary drop path. A stack is never resolved twice: the entity is stamped
     *  before anything is paid, and a stamped entity returns immediately. */
    private boolean bulkResolve(org.bukkit.event.entity.EntityDeathEvent event,int stack){
        int threshold=Math.max(1,plugin.getConfig().getInt("spawners.bulk-resolve-threshold",1000));
        if(stack<threshold)return false;
        LivingEntity mob=event.getEntity();
        if(mob.getPersistentDataContainer().has(bulkKey,PersistentDataType.BYTE))return true;
        Player killer=mob.getKiller();
        if(killer==null||!killer.isOnline())return false;
        ShopService shop=plugin.shop();
        if(shop==null)return false;

        BulkPlan plan=planStack(event.getDrops(),stack,event.getDroppedExp());
        java.util.Map<Material,Integer> sellable=sellablePart(plan.plain(),true);
        java.util.Map<Material,Integer> unsold=sellablePart(plan.plain(),false);
        java.util.Map<Material,Double> earnings=shop.bulkQuote(killer,sellable);
        double money=0;for(double value:earnings.values())money+=value;
        money=Math.round(money*100)/100.0;
        if(!sellable.isEmpty()&&!shop.sellBulk(killer,sellable,"STACKED_MOB_"+mob.getType().name()))return false;

        mob.getPersistentDataContainer().set(bulkKey,PersistentDataType.BYTE,(byte)1);
        event.getDrops().clear();
        /** Unsellables and anything carrying custom data become real items, merged into whole stacks so a
         *  thousand-mob kill hands over a handful of stacks rather than a thousand singles. */
        int returned=0;
        for(var entry:unsold.entrySet()){
            int remaining=entry.getValue(),max=Math.max(1,entry.getKey().getMaxStackSize());
            returned+=remaining;
            while(remaining>0){int take=Math.min(max,remaining);CoreUtil.give(killer,new ItemStack(entry.getKey(),take));remaining-=take;}
        }
        for(ItemStack item:plan.keep()){CoreUtil.give(killer,item);returned+=item.getAmount();}
        /** Granted directly, at exactly the total the orbs would have carried. setDroppedExp(0) is what
         *  stops the orbs existing at all; giveExp is what keeps the number honest. */
        event.setDroppedExp(0);
        if(plan.experience()>0)killer.giveExp(plan.experience());

        StringBuilder line=new StringBuilder();
        int shown=0;
        for(var entry:sellable.entrySet()){
            if(shown++==3){line.append(", +").append(sellable.size()-3).append(" more");break;}
            if(shown>1)line.append(", ");
            line.append(entry.getValue()).append("x ").append(CoreUtil.pretty(entry.getKey().name()));
        }
        CoreUtil.msg(killer,"Stack of "+stack+" resolved: sold "+(shown==0?"nothing":line.toString())
                +" for "+CoreUtil.money(money)+(returned>0?", "+returned+" unsold item"+(returned==1?"":"s")+" delivered":"")
                +", "+plan.experience()+" XP granted.");
        return true;
    }

    void stackedDeath(org.bukkit.event.entity.EntityDeathEvent event){
        /** Custom golem-spawner golems drop XP (vanilla Iron Golems drop none) -- 2x a Blaze (20). Exclusive to
         *  spawner-origin golems; the virtual-stack multiply below then scales it like every other spawner drop. */
        if(event.getEntity().getType()==EntityType.IRON_GOLEM&&event.getEntity().getPersistentDataContainer().has(spawnerMobKey))event.setDroppedExp(Math.max(event.getDroppedExp(),plugin.getConfig().getInt("spawner-golem-exp",20)));
        int stack=virtualStack(event.getEntity());
        if(stack<=1)return;
        /** A thousand or more resolves to money and experience instead of entities. Anything that stops it
         *  -- no killer, no shop, a treasury that cannot pay -- returns false and falls through to the
         *  ordinary path below, so a failure costs throughput and never items. */
        if(bulkResolve(event,stack))return;
        java.util.Map<org.bukkit.Material,Integer> totals=new java.util.LinkedHashMap<>();
        java.util.List<ItemStack> complex=new java.util.ArrayList<>();
        for(ItemStack drop:event.getDrops()){
            if(drop==null||drop.getType().isAir())continue;
            /** Anything with custom data is duplicated as-is rather than merged, so enchanted or named
             *  drops are never collapsed into a plain stack. */
            if(drop.hasItemMeta()&&(drop.getItemMeta().hasDisplayName()||drop.getItemMeta().hasEnchants()))complex.add(drop);
            else totals.merge(drop.getType(),drop.getAmount(),Integer::sum);
        }
        event.getDrops().clear();
        for(var entry:totals.entrySet()){
            int remaining=entry.getValue()*stack,max=entry.getKey().getMaxStackSize();
            while(remaining>0){int take=Math.min(max,remaining);event.getDrops().add(new ItemStack(entry.getKey(),take));remaining-=take;}
        }
        for(ItemStack item:complex)for(int i=0;i<stack;i++)event.getDrops().add(item.clone());
        event.setDroppedExp(event.getDroppedExp()*stack);
    }
    private boolean pauseHostileSpawner(CreatureSpawner spawner){
        double range=Math.max(8,spawner.getRequiredPlayerRange()),rangeSq=range*range;
        List<Player> eligible=spawner.getWorld().getPlayers().stream()
                .filter(player->!player.isDead()&&player.getGameMode()!=GameMode.SPECTATOR)
                .filter(player->player.getLocation().distanceSquared(spawner.getLocation())<=rangeSq).toList();
        return eligible.isEmpty()||eligible.stream().noneMatch(plugin.settings()::naturalSpawns);
    }
    private void hoverTick(){
        for(Player player:plugin.getServer().getOnlinePlayers()){
            if(plugin.teleports().combatRemaining(player)>0||plugin.isTeleporting(player)){hoverLabels.remove(player.getUniqueId());continue;}
            Block target=player.getTargetBlockExact(7,FluidCollisionMode.NEVER);
            String label=null;
            if(target!=null&&target.getType()==Material.SPAWNER&&target.getState() instanceof CreatureSpawner spawner){
                label=CoreUtil.pretty(spawner.getSpawnedType().name())+" Spawner ×"+stackSize(spawner);
            }
            String previous=hoverLabels.get(player.getUniqueId());
            if(Objects.equals(previous,label))continue;
            if(label==null){hoverLabels.remove(player.getUniqueId());player.sendActionBar(Component.empty());}
            else{hoverLabels.put(player.getUniqueId(),label);player.sendActionBar(Component.text(label,NamedTextColor.GOLD));}
        }
        hoverLabels.keySet().removeIf(id->plugin.getServer().getPlayer(id)==null);
    }

    void tntPlaced(BlockPlaceEvent event){if(event.getBlockPlaced().getType()!=Material.TNT)return;Database.FactionRow faction=db.factionOf(CoreUtil.id(event.getPlayer()));if(faction!=null)tntOwners.put(blockKey(event.getBlockPlaced()),faction.id());}
    void blockRemoved(Block block){if(block.getType()==Material.TNT)tntOwners.remove(blockKey(block));}
    void tntPrime(TNTPrimeEvent event){
        long faction=factionOf(event.getPrimingEntity());if(faction<=0)faction=tntOwners.getOrDefault(blockKey(event.getBlock()),0L);tntOwners.remove(blockKey(event.getBlock()));if(faction<=0)return;long source=faction;
        plugin.getServer().getScheduler().runTask(plugin,()->event.getBlock().getWorld().getNearbyEntities(event.getBlock().getLocation().add(.5,.5,.5),1.5,1.5,1.5,entity->entity instanceof TNTPrimed).stream().map(TNTPrimed.class::cast).filter(tnt->!tnt.getPersistentDataContainer().has(raidFactionKey)).min(Comparator.comparingDouble(tnt->tnt.getLocation().distanceSquared(event.getBlock().getLocation()))).ifPresent(tnt->tnt.getPersistentDataContainer().set(raidFactionKey,PersistentDataType.LONG,source)));
    }

    void explosion(EntityExplodeEvent event){
        long raider=event.getEntity() instanceof TNTPrimed tnt?tnt.getPersistentDataContainer().getOrDefault(raidFactionKey,PersistentDataType.LONG,factionOf(tnt.getSource())):0;if(raider<=0)return;
        for(Block block:List.copyOf(event.blockList())){if(block.getType()!=Material.SPAWNER||!(block.getState() instanceof CreatureSpawner spawner))continue;FactionService.Claim claim=factions.claimAt(block.getLocation());if(claim==null||claim.faction().id()==raider)continue;event.blockList().remove(block);raidRecover(block,spawner);}
    }

    int stackSize(CreatureSpawner spawner){return histories(spawner).length;}
    /*  Item and experience conservation for a stacked resolution.
     *
     *  Two things have to be true and neither is obvious from reading the code. Everything that would have
     *  dropped must still exist somewhere in the plan -- multiplied, sorted into sellable and not, but never
     *  quietly rounded away; and the experience must be the exact number the orbs would have carried, since
     *  granting it directly is the one place a stack could silently pay less than an unstacked kill. */
    static boolean bulkPlanSelfTest(){
        ItemStack named=new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta=named.getItemMeta();
        meta.displayName(Component.text("Keepsake"));
        named.setItemMeta(meta);
        java.util.List<ItemStack> drops=java.util.List.of(
                new ItemStack(Material.ROTTEN_FLESH,2),new ItemStack(Material.IRON_INGOT,1),named);
        BulkPlan plan=planStack(drops,1000,5);
        if(plan.experience()!=5000)return false;
        if(plan.plain().getOrDefault(Material.ROTTEN_FLESH,0)!=2000)return false;
        if(plan.plain().getOrDefault(Material.IRON_INGOT,0)!=1000)return false;
        /** Custom-data drops are never merged into a plain stack: a thousand named swords are a thousand
         *  named swords, not one stack of a thousand. */
        if(plan.keep().size()!=1000)return false;
        int before=0;for(ItemStack drop:drops)before+=drop.getAmount();
        int after=plan.plainTotal();for(ItemStack kept:plan.keep())after+=kept.getAmount();
        if(after!=before*1000)return false;
        /** A single mob must come out of the same function completely unchanged. */
        BulkPlan one=planStack(drops,1,5);
        return one.experience()==5&&one.plainTotal()==3&&one.keep().size()==1;
    }

    /** The split by shop interest has to be a partition: every material lands on exactly one side, and the
     *  two sides add back up to the whole. Run against the live price list rather than a stub, because the
     *  failure this guards against is a material the shop stops buying going missing rather than being
     *  handed over. */
    boolean bulkSplitSelfTest(){
        java.util.Map<Material,Integer> plain=new java.util.LinkedHashMap<>();
        plain.put(Material.ROTTEN_FLESH,2000);plain.put(Material.BONE,1000);
        plain.put(Material.IRON_INGOT,1000);plain.put(Material.DIRT,50);
        java.util.Map<Material,Integer> sellable=sellablePart(plain,true),unsold=sellablePart(plain,false);
        if(sellable.size()+unsold.size()!=plain.size())return false;
        int total=0;for(int amount:sellable.values())total+=amount;for(int amount:unsold.values())total+=amount;
        int expected=0;for(int amount:plain.values())expected+=amount;
        return total==expected&&java.util.Collections.disjoint(sellable.keySet(),unsold.keySet());
    }

    boolean selfTest(){ItemStack item=createItem(EntityType.BLAZE,2,"selftest-spawner");ItemMeta meta=item.getItemMeta();return item.getMaxStackSize()==1&&"BLAZE".equals(meta.getPersistentDataContainer().get(typeKey,PersistentDataType.STRING))&&meta.getPersistentDataContainer().getOrDefault(historiesKey,PersistentDataType.INTEGER,0)==2&&meta.getPersistentDataContainer().getOrDefault(stackKey,PersistentDataType.INTEGER,0)==1&&"selftest-spawner".equals(meta.getPersistentDataContainer().get(identityKey,PersistentDataType.STRING))&&value(EntityType.BLAZE)>value(EntityType.ZOMBIE)&&(!(meta instanceof BlockStateMeta blockMeta)||!blockMeta.hasBlockState())&&plugin.getConfig().getInt("spawners.max-stack",0)==10&&plugin.getConfig().getInt("spawner-breaking.relocated-durability-cost",0)>=500;}

    private void raidRecover(Block block,CreatureSpawner spawner){int[] histories=histories(spawner);String[] identities=identities(spawner,histories.length);int selected=histories[histories.length-1];block.getWorld().dropItemNaturally(block.getLocation().add(.5,.5,.5),createItem(spawner.getSpawnedType(),selected+1,identities[identities.length-1]));if(histories.length>1){setHistories(spawner,Arrays.copyOf(histories,histories.length-1));setIdentities(spawner,Arrays.copyOf(identities,identities.length-1));spawner.update(true);plugin.netWorth().blockChanged(block);}else{plugin.netWorth().removed(block);block.setType(Material.AIR,false);}}
    /** Public factory so shop/shard purchases hand over a genuine SMPCore spawner (tagged, placeable,
     *  stackable) rather than a raw spawn egg or an untagged vanilla spawner block. */
    ItemStack purchasedSpawner(EntityType type){return createItem(type,1,java.util.UUID.randomUUID().toString());}
    /** Every spawner type this server actually registers, from the configured value table. Orders read
     *  this, so a type added to the config later becomes orderable without a code change. */
    java.util.List<EntityType> orderableTypes(){
        java.util.List<EntityType> types=new java.util.ArrayList<>();
        org.bukkit.configuration.ConfigurationSection section=plugin.getConfig().getConfigurationSection("net-worth.spawners");
        if(section!=null)for(String key:section.getKeys(false)){
            if(key.equalsIgnoreCase("default"))continue;
            try{types.add(EntityType.valueOf(key.toUpperCase(java.util.Locale.ROOT)));}catch(IllegalArgumentException ignored){}
        }
        types.sort(java.util.Comparator.comparing(EntityType::name));
        return types;
    }
    /** A canonical, freshly identified spawner item of this type, for order icons and stash delivery. It
     *  carries the same persistent data a recovered spawner does, so a delivered one places normally. */
    ItemStack orderItem(EntityType type){return createItem(type,0,UUID.randomUUID().toString());}
    /** The spawner type an item really is, read from persistent data rather than its display name. */
    EntityType typeOf(ItemStack item){
        if(item==null||item.getType()!=Material.SPAWNER||!item.hasItemMeta())return null;
        String raw=item.getItemMeta().getPersistentDataContainer().get(typeKey,PersistentDataType.STRING);
        if(raw==null)return null;
        try{return EntityType.valueOf(raw);}catch(IllegalArgumentException ignored){return null;}
    }
    private ItemStack createItem(EntityType type,int recoveries){return createItem(type,recoveries,UUID.randomUUID().toString());}
    /** Package-visible so the Spawner Shop can hand a recovered spawner back as a REAL spawner item --
     *  same identity stamping, same recovery count, same everything a broken-and-picked-up one would have. */
    ItemStack createItem(EntityType type,int recoveries,String identity){ItemStack item=new ItemStack(Material.SPAWNER);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(CoreUtil.pretty(type.name())+" Spawner ×1",NamedTextColor.GOLD));meta.lore(List.of(Component.text("Recovery: "+recoveries,NamedTextColor.GRAY)));meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);meta.setMaxStackSize(1);meta.getPersistentDataContainer().set(typeKey,PersistentDataType.STRING,type.name());meta.getPersistentDataContainer().set(historiesKey,PersistentDataType.INTEGER,recoveries);meta.getPersistentDataContainer().set(stackKey,PersistentDataType.INTEGER,1);meta.getPersistentDataContainer().set(identityKey,PersistentDataType.STRING,identity);item.setItemMeta(meta);return item;}
    void migrateInventory(Player player){ItemStack[] contents=player.getInventory().getContents();boolean changed=false;for(int i=0;i<contents.length;i++){ItemStack migrated=migrateItem(contents[i]);if(migrated!=contents[i]){contents[i]=migrated;changed=true;}}if(changed){player.getInventory().setContents(contents);CoreUtil.msg(player,"Recovered spawner items were updated to the safe SMPCore format.");}}
    ItemStack migrateItem(ItemStack source){if(source==null||source.getType()!=Material.SPAWNER||!source.hasItemMeta())return source;ItemMeta meta=source.getItemMeta();String type=meta.getPersistentDataContainer().get(typeKey,PersistentDataType.STRING),identity=meta.getPersistentDataContainer().get(identityKey,PersistentDataType.STRING);int recovery=meta.getPersistentDataContainer().getOrDefault(historiesKey,PersistentDataType.INTEGER,1);boolean embedded=meta instanceof BlockStateMeta blockMeta&&blockMeta.hasBlockState();if(type==null&&embedded&&((BlockStateMeta)meta).getBlockState() instanceof CreatureSpawner state)type=state.getSpawnedType().name();if(type==null||!embedded&&meta.getPersistentDataContainer().has(stackKey)&&identity!=null)return source;try{ItemStack clean=createItem(EntityType.valueOf(type),recovery,identity==null?UUID.randomUUID().toString():identity);clean.setAmount(source.getAmount());return clean;}catch(IllegalArgumentException ignored){return source;}}
    private int[] histories(CreatureSpawner spawner){PersistentDataContainer pdc=spawner.getPersistentDataContainer();int[] values=pdc.get(historiesKey,PersistentDataType.INTEGER_ARRAY);if(values!=null&&values.length>0)return Arrays.copyOf(values,Math.min(10,values.length));return new int[]{pdc.has(placedKey)?1:0};}
    private void setHistories(CreatureSpawner spawner,int[] values){spawner.getPersistentDataContainer().set(historiesKey,PersistentDataType.INTEGER_ARRAY,values);if(Arrays.stream(values).anyMatch(value->value>0))spawner.getPersistentDataContainer().set(placedKey,PersistentDataType.BYTE,(byte)1);}
    private String[] identities(CreatureSpawner spawner,int count){String raw=spawner.getPersistentDataContainer().get(identitiesKey,PersistentDataType.STRING);String[] stored=raw==null||raw.isBlank()?new String[0]:raw.split(",");String[] out=Arrays.copyOf(stored,count);for(int i=0;i<count;i++)if(out[i]==null||out[i].isBlank())out[i]=UUID.randomUUID().toString();return out;}
    private void setIdentities(CreatureSpawner spawner,String[] values){spawner.getPersistentDataContainer().set(identitiesKey,PersistentDataType.STRING,String.join(",",values));}
    double value(EntityType type){return plugin.getConfig().getDouble("net-worth.spawners."+type.name(),plugin.getConfig().getDouble("net-worth.spawners.default",25000));}
    private int selectedHistory(CreatureSpawner spawner){int[] values=histories(spawner);return values[values.length-1];}
    private boolean validTool(ItemStack item){return item!=null&&item.getType()==Material.NETHERITE_PICKAXE&&item.containsEnchantment(Enchantment.SILK_TOUCH);}
    private void damageTool(Player player,int amount){if(player.getGameMode()==GameMode.CREATIVE||amount<=0)return;ItemStack item=player.getInventory().getItemInMainHand();if(!(item.getItemMeta() instanceof Damageable meta))return;int next=meta.getDamage()+amount,max=item.getType().getMaxDurability();if(next>=max){player.getInventory().setItemInMainHand(null);player.playSound(player.getLocation(),Sound.ENTITY_ITEM_BREAK,1,1);}else{meta.setDamage(next);item.setItemMeta(meta);}}
    private void consume(Player player,EquipmentSlot hand){ItemStack item=player.getInventory().getItem(hand);if(item.getAmount()<=1)player.getInventory().setItem(hand,null);else item.setAmount(item.getAmount()-1);}
    private long factionOf(Entity source){if(source instanceof Player player){Database.FactionRow faction=db.factionOf(CoreUtil.id(player));return faction==null?0:faction.id();}if(source instanceof Projectile projectile&&projectile.getShooter() instanceof Player player)return factionOf(player);if(source instanceof TNTPrimed tnt)return tnt.getPersistentDataContainer().getOrDefault(raidFactionKey,PersistentDataType.LONG,0L);return 0;}
    private String miningKey(Player player,Block block){return player.getUniqueId()+":"+blockKey(block);}
    private String blockKey(Block block){return block.getWorld().getUID()+":"+block.getX()+":"+block.getY()+":"+block.getZ();}
    private void warn(Player player){long now=System.currentTimeMillis();if(now-warned.getOrDefault(player.getUniqueId(),0L)<1500)return;warned.put(player.getUniqueId(),now);player.sendMessage(Component.text("⚠ Spawners only drop with a Netherite Pickaxe and Silk Touch.",NamedTextColor.RED));player.playSound(player.getLocation(),Sound.BLOCK_NOTE_BLOCK_BASS,1,.5f);}
}
