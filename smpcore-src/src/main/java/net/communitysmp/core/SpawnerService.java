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
    private final NamespacedKey typeKey,placedKey,historiesKey,stackKey,identityKey,identitiesKey,spawnerMobKey,raidFactionKey;
    private final Map<UUID,Long> warned=new HashMap<>();
    private final Map<String,Long> miningStarted=new HashMap<>();
    private final Map<String,Long> tntOwners=new HashMap<>();
    private final Map<UUID,String> hoverLabels=new HashMap<>();
    private BukkitTask hoverTask;

    SpawnerService(SMPCore plugin,FactionService factions){
        this.plugin=plugin;this.db=plugin.db();this.factions=factions;
        typeKey=new NamespacedKey(plugin,"spawner_type");placedKey=new NamespacedKey(plugin,"placed_spawner");historiesKey=new NamespacedKey(plugin,"spawner_histories");stackKey=new NamespacedKey(plugin,"spawner_stack");identityKey=new NamespacedKey(plugin,"spawner_identity");identitiesKey=new NamespacedKey(plugin,"spawner_identities");spawnerMobKey=new NamespacedKey(plugin,"spawner_mob");raidFactionKey=new NamespacedKey(plugin,"raid_faction");
        plugin.getServer().getScheduler().runTaskLater(plugin,this::migrateLoadedFactionSpawners,100L);
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
        String key=miningKey(event.getPlayer(),event.getBlock());long now=System.currentTimeMillis();miningStarted.putIfAbsent(key,now);
        event.getPlayer().addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE,300,0,false,false,false));
        long seconds=Math.max(1,plugin.getConfig().getLong("spawner-breaking.relocated-break-seconds",10));if(now-warned.getOrDefault(event.getPlayer().getUniqueId(),0L)>1500){warned.put(event.getPlayer().getUniqueId(),now);CoreUtil.error(event.getPlayer(),"Relocated spawner: mine for about "+seconds+"s; recovery costs "+plugin.getConfig().getInt("spawner-breaking.relocated-durability-cost",512)+" durability.");}
    }

    boolean breaking(BlockBreakEvent event){
        Block block=event.getBlock();if(block.getType()!=Material.SPAWNER)return false;Player player=event.getPlayer();if(!(block.getState() instanceof CreatureSpawner spawner)){event.setCancelled(true);return true;}
        int[] histories=histories(spawner);String[] identities=identities(spawner,histories.length);int selected=histories[histories.length-1];String selectedIdentity=identities[identities.length-1];EntityType mobType=spawner.getSpawnedType();ItemStack tool=player.getInventory().getItemInMainHand();boolean pickup=validTool(tool);
        if(pickup&&selected>=1){long required=Math.max(1,plugin.getConfig().getLong("spawner-breaking.relocated-break-seconds",10))*1000L,started=miningStarted.getOrDefault(miningKey(player,block),0L),remaining=required-(System.currentTimeMillis()-started);if(started==0||remaining>0){event.setCancelled(true);CoreUtil.error(player,"Keep mining this relocated spawner for about "+Math.max(1,(remaining+999)/1000)+"s.");return true;}}
        event.setCancelled(true);event.setDropItems(false);event.setExpToDrop(0);
        if(histories.length>1){setHistories(spawner,Arrays.copyOf(histories,histories.length-1));setIdentities(spawner,Arrays.copyOf(identities,identities.length-1));spawner.update(true);plugin.getServer().getScheduler().runTask(plugin,()->plugin.netWorth().blockChanged(block));}
        else{plugin.netWorth().removed(block);block.setType(Material.AIR,false);}
        miningStarted.remove(miningKey(player,block));
        if(!pickup){
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

    /** Placed spawners are the server's farming investment, so they get a genuinely upgraded cycle that
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
        spawner.setMaxNearbyEntities(Math.max(6,plugin.getConfig().getInt("spawners.max-nearby-entities",200)));
        spawner.setRequiredPlayerRange(Math.max(16,plugin.getConfig().getInt("spawners.required-player-range",32)));
        spawner.setSpawnRange(Math.max(2,plugin.getConfig().getInt("spawners.spawn-range",4)));
    }
    boolean stack(PlayerInteractEvent event){
        if(!event.getAction().isRightClick()||event.getClickedBlock()==null||event.getClickedBlock().getType()!=Material.SPAWNER||event.getItem()==null||!event.getItem().hasItemMeta())return false;
        String itemType=event.getItem().getItemMeta().getPersistentDataContainer().get(typeKey,PersistentDataType.STRING);if(itemType==null)return false;if(!(event.getClickedBlock().getState() instanceof CreatureSpawner spawner))return false;event.setCancelled(true);
        if(!spawner.getSpawnedType().name().equals(itemType)){CoreUtil.error(event.getPlayer(),"Only identical spawner types can stack.");return true;}int[] old=histories(spawner);int max=Math.max(1,plugin.getConfig().getInt("spawners.max-stack",10));if(old.length>=max){CoreUtil.error(event.getPlayer(),"This spawner stack is full ("+max+").");return true;}
        int[] next=Arrays.copyOf(old,old.length+1);next[next.length-1]=event.getItem().getItemMeta().getPersistentDataContainer().getOrDefault(historiesKey,PersistentDataType.INTEGER,1);String[] ids=identities(spawner,old.length),nextIds=Arrays.copyOf(ids,ids.length+1);nextIds[nextIds.length-1]=event.getItem().getItemMeta().getPersistentDataContainer().getOrDefault(identityKey,PersistentDataType.STRING,UUID.randomUUID().toString());setHistories(spawner,next);setIdentities(spawner,nextIds);tunePlacedSpawner(spawner);spawner.update(true);FactionService.Claim claim=factions.claimAt(event.getClickedBlock().getLocation());if(claim!=null)plugin.progress().factionSpawnerPlaced(claim.faction(),nextIds[nextIds.length-1],spawner.getSpawnedType(),System.currentTimeMillis(),event.getClickedBlock().getLocation());consume(event.getPlayer(),event.getHand());plugin.netWorth().blockChanged(event.getClickedBlock());CoreUtil.msg(event.getPlayer(),CoreUtil.pretty(itemType)+" Spawner ×"+next.length);return true;
    }

    void spawned(SpawnerSpawnEvent event){
        if(event.getEntity() instanceof Enemy&&pauseHostileSpawner(event.getSpawner())){event.setCancelled(true);return;}
        CreatureSpawner spawner=event.getSpawner();int count=stackSize(spawner);if(count<=1)return;
        /** Hard ceiling on how many physical entities one stack may add per cycle, plus a local crowding
         *  check. Without these a max stack in a chunk that is already full of mobs is a straightforward
         *  lag machine; with them a big stack still produces far more than a small one, it just cannot
         *  run away unbounded. */
        int perCycleCap=Math.max(1,plugin.getConfig().getInt("spawners.max-spawns-per-cycle",12));
        int crowdCap=Math.max(8,plugin.getConfig().getInt("spawners.nearby-entity-ceiling",80));
        long nearby=event.getEntity().getNearbyEntities(8,6,8).stream().filter(en->en instanceof org.bukkit.entity.LivingEntity&&!(en instanceof Player)).count();
        if(nearby>=crowdCap)return;
        count=Math.min(count,perCycleCap);
        for(int i=1;i<count;i++){Entity extra=event.getEntity().getWorld().spawnEntity(event.getEntity().getLocation(),event.getEntity().getType());extra.getPersistentDataContainer().set(spawnerMobKey,PersistentDataType.BYTE,(byte)1);}
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
    boolean selfTest(){ItemStack item=createItem(EntityType.BLAZE,2,"selftest-spawner");ItemMeta meta=item.getItemMeta();return item.getMaxStackSize()==1&&"BLAZE".equals(meta.getPersistentDataContainer().get(typeKey,PersistentDataType.STRING))&&meta.getPersistentDataContainer().getOrDefault(historiesKey,PersistentDataType.INTEGER,0)==2&&meta.getPersistentDataContainer().getOrDefault(stackKey,PersistentDataType.INTEGER,0)==1&&"selftest-spawner".equals(meta.getPersistentDataContainer().get(identityKey,PersistentDataType.STRING))&&value(EntityType.BLAZE)>value(EntityType.ZOMBIE)&&(!(meta instanceof BlockStateMeta blockMeta)||!blockMeta.hasBlockState())&&plugin.getConfig().getInt("spawners.max-stack",0)==10&&plugin.getConfig().getInt("spawner-breaking.relocated-durability-cost",0)>=500;}

    private void raidRecover(Block block,CreatureSpawner spawner){int[] histories=histories(spawner);String[] identities=identities(spawner,histories.length);int selected=histories[histories.length-1];block.getWorld().dropItemNaturally(block.getLocation().add(.5,.5,.5),createItem(spawner.getSpawnedType(),selected+1,identities[identities.length-1]));if(histories.length>1){setHistories(spawner,Arrays.copyOf(histories,histories.length-1));setIdentities(spawner,Arrays.copyOf(identities,identities.length-1));spawner.update(true);plugin.netWorth().blockChanged(block);}else{plugin.netWorth().removed(block);block.setType(Material.AIR,false);}}
    /** Public factory so shop/shard purchases hand over a genuine SMPCore spawner (tagged, placeable,
     *  stackable) rather than a raw spawn egg or an untagged vanilla spawner block. */
    ItemStack purchasedSpawner(EntityType type){return createItem(type,1,java.util.UUID.randomUUID().toString());}
    private ItemStack createItem(EntityType type,int recoveries){return createItem(type,recoveries,UUID.randomUUID().toString());}
    private ItemStack createItem(EntityType type,int recoveries,String identity){ItemStack item=new ItemStack(Material.SPAWNER);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(CoreUtil.pretty(type.name())+" Spawner ×1",NamedTextColor.GOLD));meta.lore(List.of(Component.text("Recovery: "+recoveries,NamedTextColor.GRAY)));meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);meta.setMaxStackSize(1);meta.getPersistentDataContainer().set(typeKey,PersistentDataType.STRING,type.name());meta.getPersistentDataContainer().set(historiesKey,PersistentDataType.INTEGER,recoveries);meta.getPersistentDataContainer().set(stackKey,PersistentDataType.INTEGER,1);meta.getPersistentDataContainer().set(identityKey,PersistentDataType.STRING,identity);item.setItemMeta(meta);return item;}
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
