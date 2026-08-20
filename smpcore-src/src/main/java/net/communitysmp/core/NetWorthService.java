package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

final class NetWorthService implements Listener {
    record Row(long factionId,String name,double value){}
    record Breakdown(double bank,double containers,double spawners,double dragonEggs,double villagerRaw,double villagerEffective,double infrastructure,double total){}
    private record Holder(long factionId,String page,int pageIndex) implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private record VillagerEntry(boolean counted,String name,String detail,Location location,double value,List<String> trades){}
    private record ContainerRef(String key,Location location,Inventory inventory){}
    private final SMPCore plugin;private final Database db;private final FactionService factions;private final ShopService shop;private final SpawnerService spawners;private final NamespacedKey relicKey;
    private final NamespacedKey eliteSigilValueKey,legendarySigilValueKey;private BukkitTask task,scanTask;private final Deque<Chunk> scanQueue=new ArrayDeque<>();private volatile List<Row> rankingCache=List.of();private volatile long rankingCachedAt;

    NetWorthService(SMPCore plugin,FactionService factions,ShopService shop,SpawnerService spawners){this.plugin=plugin;this.db=plugin.db();this.factions=factions;this.shop=shop;this.spawners=spawners;this.relicKey=new NamespacedKey(plugin,"relic");eliteSigilValueKey=new NamespacedKey(plugin,"elite_sigil");legendarySigilValueKey=new NamespacedKey(plugin,"legendary_sigil");long period=Math.max(1,plugin.getConfig().getLong("net-worth.recalculate-minutes",2))*1200L;task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::recalculateLoaded,200L,period);}
    void shutdown(){if(task!=null)task.cancel();if(scanTask!=null)scanTask.cancel();}

    List<Row> rankings(){long now=System.currentTimeMillis();if(now-rankingCachedAt<10_000&&!rankingCache.isEmpty())return rankingCache;List<Row> rows=new ArrayList<>();for(Database.FactionRow faction:db.factions())rows.add(new Row(faction.id(),faction.name(),value(faction.id())));rows.sort(java.util.Comparator.comparingDouble(Row::value).reversed().thenComparing(Row::name,String.CASE_INSENSITIVE_ORDER));rankingCache=List.copyOf(rows);rankingCachedAt=now;return rankingCache;}
    double value(long faction){Database.FactionRow row=db.faction(faction);if(row==null)return 0;double ordinary=db.assetTotalExcept(faction,"VILLAGER"),villagers=db.assetTotalOf(faction,"VILLAGER"),full=plugin.getConfig().getDouble("net-worth.villagers.full-value-up-to",200000),reduced=plugin.getConfig().getDouble("net-worth.villagers.excess-multiplier",.20),cap=plugin.getConfig().getDouble("net-worth.villagers.maximum-contribution",500000);double effective=Math.min(cap,Math.min(villagers,full)+Math.max(0,villagers-full)*reduced);return Math.max(0,row.balance())+ordinary+effective+infrastructure(row);}
    Breakdown breakdown(long faction){Database.FactionRow row=db.faction(faction);if(row==null)return new Breakdown(0,0,0,0,0,0,0,0);double containers=db.assetTotalOf(faction,"CONTAINER")+db.assetTotalOf(faction,"INDUSTRIAL_HOPPER"),spawnerValue=db.assetTotalOf(faction,"SPAWNER"),dragonEggs=db.assetTotalOf(faction,"DRAGON_EGG"),villagers=db.assetTotalOf(faction,"VILLAGER"),full=plugin.getConfig().getDouble("net-worth.villagers.full-value-up-to",200000),reduced=plugin.getConfig().getDouble("net-worth.villagers.excess-multiplier",.20),cap=plugin.getConfig().getDouble("net-worth.villagers.maximum-contribution",500000),effective=Math.min(cap,Math.min(villagers,full)+Math.max(0,villagers-full)*reduced),bank=Math.max(0,row.balance()),infrastructure=infrastructure(row);return new Breakdown(bank,containers,spawnerValue,dragonEggs,villagers,effective,infrastructure,bank+containers+spawnerValue+dragonEggs+effective+infrastructure);}
    private double infrastructure(Database.FactionRow faction){if(faction.tier()<=0)return 0;List<Integer> costs=plugin.getConfig().getIntegerList("claims.expansion-costs");double value=0;for(int tier=0;tier<Math.min(faction.tier(),costs.size());tier++)value+=Math.max(0,costs.get(tier));return value;}
    void claimsChanged(){invalidate();}
    private void invalidate(){rankingCachedAt=0;}

    void open(Player player,Database.FactionRow faction){
        refreshFactionVillagers(faction);
        Breakdown b=breakdown(faction.id());Holder holder=new Holder(faction.id(),"SUMMARY",0);
        Inventory inv=plugin.getServer().createInventory(holder,27,Component.text(faction.name()+" Net Worth",NamedTextColor.DARK_GREEN));
        inv.setItem(4,icon(Material.NETHER_STAR,"Total Net Worth",List.of(CoreUtil.money(b.total()))));
        inv.setItem(10,icon(Material.GOLD_BLOCK,"Faction Bank",List.of(CoreUtil.money(b.bank()))));
        inv.setItem(12,icon(Material.CHEST,"Claim Containers",List.of(CoreUtil.money(b.containers()),"Valued contents inside the claim","Includes "+CoreUtil.money(plugin.getConfig().getDouble("net-worth.industrial-hopper-value",5000))+" per Industrial Hopper")));
        inv.setItem(14,icon(Material.IRON_BARS,"Placed Spawners",spawnerLore(faction.id(),b.spawners())));
        inv.setItem(16,icon(Material.EMERALD,"Villagers",List.of(CoreUtil.money(b.villagerEffective())+" counted","Raw value: "+CoreUtil.money(b.villagerRaw()),"Click for villager details")));
        inv.setItem(18,icon(Material.DRAGON_EGG,"Dragon Eggs",List.of(CoreUtil.money(b.dragonEggs()),"Counted in chests, shulkers, and placed in your claim")));
        inv.setItem(22,icon(Material.FILLED_MAP,"Claim Infrastructure",List.of(CoreUtil.money(b.infrastructure()),"Cumulative expansion replacement value")));
        player.openInventory(inv);
    }
    private void openVillagers(Player player,long factionId,int requestedPage){
        Database.FactionRow faction=db.faction(factionId);if(faction==null)return;List<VillagerEntry> entries=villagerEntries(faction);int pages=Math.max(1,(entries.size()+44)/45),page=Math.max(0,Math.min(requestedPage,pages-1));Holder holder=new Holder(factionId,"VILLAGERS",page);
        Inventory inv=plugin.getServer().createInventory(holder,54,Component.text("Villagers • "+(page+1)+"/"+pages,NamedTextColor.DARK_GREEN));
        int from=page*45,to=Math.min(entries.size(),from+45),slot=0;for(int i=from;i<to;i++){VillagerEntry entry=entries.get(i);List<String> lore=new ArrayList<>();lore.add(entry.detail());lore.add("X "+entry.location().getBlockX()+" Y "+entry.location().getBlockY()+" Z "+entry.location().getBlockZ());lore.add(entry.location().getWorld()==null?"Unknown world":entry.location().getWorld().getName());lore.add(entry.counted()?"Value: "+CoreUtil.money(entry.value()):"Excluded: "+entry.detail());if(entry.counted())lore.addAll(entry.trades());inv.setItem(slot++,icon(entry.counted()?Material.VILLAGER_SPAWN_EGG:Material.RED_STAINED_GLASS_PANE,(entry.counted()?"✓ ":"✕ ")+entry.name(),lore));}
        inv.setItem(49,icon(Material.ARROW,"Back",List.of("Return to net worth summary")));if(page>0)inv.setItem(45,icon(Material.SPECTRAL_ARROW,"Previous Page",List.of()));if(page+1<pages)inv.setItem(53,icon(Material.SPECTRAL_ARROW,"Next Page",List.of()));
        player.openInventory(inv);
    }
    @EventHandler public void click(InventoryClickEvent event){
        if(!(event.getInventory().getHolder(false) instanceof Holder holder))return;event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player))return;
        if(holder.page().equals("SUMMARY")&&event.getRawSlot()==16)openVillagers(player,holder.factionId(),0);
        else if(holder.page().equals("VILLAGERS")){if(event.getRawSlot()==49){Database.FactionRow faction=db.faction(holder.factionId());if(faction!=null)open(player,faction);}else if(event.getRawSlot()==45)openVillagers(player,holder.factionId(),holder.pageIndex()-1);else if(event.getRawSlot()==53)openVillagers(player,holder.factionId(),holder.pageIndex()+1);}
    }

    private final Map<String,Long> lastContainerUpdateAt=new HashMap<>();
    void containerChanged(Inventory inventory){ContainerRef ref=containerRef(inventory);if(ref==null)return;
        long now=System.currentTimeMillis();Long last=lastContainerUpdateAt.get(ref.key());if(last!=null&&now-last<3000)return;lastContainerUpdateAt.put(ref.key(),now);String villagerKey=ref.key()+":villagers";if(!(ref.location().getBlock().getState() instanceof Container)){db.deleteAsset(ref.key());db.deleteAsset(villagerKey);invalidate();return;}FactionService.Claim claim=factions.claimAt(ref.location());if(claim==null){db.deleteAsset(ref.key());db.deleteAsset(villagerKey);invalidate();return;}industrialHopperAsset(ref.location(),claim);
        double value=0,villagers=0;
        /** An Industrial Hopper keeps its contents in its own inventory, not in the block's native slots,
         *  so read the real thing. Valued through the ordinary container path and counted exactly once --
         *  the flat block value above is a separate asset row keyed by position. */
        ItemStack[] holdings=plugin.industrialHoppers()==null?null:plugin.industrialHoppers().storedContents(ref.location().getBlock());
        if(holdings==null)holdings=ref.inventory().getStorageContents();
        for(ItemStack item:holdings)if(item!=null&&!item.getType().isAir()&&(plugin.shards()==null||!plugin.shards().bound(item))){double captured=plugin.capsules()==null?0:plugin.capsules().capturedValue(item);villagers+=captured*item.getAmount();value+=captured>0?0:itemValue(item);}if(value<=0)db.deleteAsset(ref.key());else db.saveAsset(new Database.AssetRow(claim.faction().id(),ref.key(),"CONTAINER","MIXED",Math.round(value*100)/100.0,System.currentTimeMillis()));if(villagers<=0)db.deleteAsset(villagerKey);else db.saveAsset(new Database.AssetRow(claim.faction().id(),villagerKey,"VILLAGER","CAPSULE",Math.round(villagers*100)/100.0,System.currentTimeMillis()));invalidate();}
    void blockChanged(Block block){
        BlockState state=block.getState();
        /** Fast path: the vast majority of block changes (piston moves of slime/redstone, ordinary placements)
         *  are not trackable assets. Skipping them here avoids per-block DB writes -- the flood a TNT duper or
         *  flying-machine quarry was generating thousands of times a second on the main thread. */
        if(!(state instanceof Container)&&!(state instanceof CreatureSpawner)&&block.getType()!=Material.DRAGON_EGG)return;
        String key=blockKey(block,state instanceof CreatureSpawner?"spawner":"container");db.deleteAsset(key);
        if(state instanceof Container container){
            if(container instanceof Chest chest&&chest.getInventory().getHolder() instanceof DoubleChest doubleChest)for(InventoryHolder side:List.of(doubleChest.getLeftSide(),doubleChest.getRightSide()))if(side instanceof Chest half){db.deleteAsset(blockKey(half.getBlock(),"container"));db.deleteAsset(blockKey(half.getBlock(),"container")+":villagers");}
            containerChanged(container.getInventory());
        }else if(state instanceof CreatureSpawner spawner)saveSpawner(block,spawner.getSpawnedType());
        else if(block.getType()==Material.DRAGON_EGG)saveDragonEgg(block);
        else db.deleteAsset(blockKey(block,"dragonegg"));
        invalidate();
    }
    void removed(Block block){Material t=block.getType();if(t!=Material.SPAWNER&&t!=Material.DRAGON_EGG&&!(block.getState() instanceof Container))return;db.deleteAsset(blockKey(block,"ihopper"));db.deleteAsset(blockKey(block,"container"));db.deleteAsset(blockKey(block,"container")+":villagers");db.deleteAsset(blockKey(block,"spawner"));db.deleteAsset(blockKey(block,"dragonegg"));if(block.getState() instanceof Chest chest&&chest.getInventory().getHolder() instanceof DoubleChest doubleChest){for(InventoryHolder side:List.of(doubleChest.getLeftSide(),doubleChest.getRightSide()))if(side instanceof Chest half){db.deleteAsset(blockKey(half.getBlock(),"container"));db.deleteAsset(blockKey(half.getBlock(),"container")+":villagers");}}invalidate();}
    /** A placed Industrial Hopper is itself worth something to the faction, on top of whatever is inside
     *  it. One row per block position, so ten hoppers are ten rows, the same hopper can never be counted
     *  twice, and the row disappears with the block exactly like every other asset. */
    private void industrialHopperAsset(Location at,FactionService.Claim claim){
        String key=blockKey(at.getBlock(),"ihopper");
        if(claim==null||plugin.industrialHoppers()==null||!plugin.industrialHoppers().isIndustrial(at.getBlock())){db.deleteAsset(key);return;}
        db.saveAsset(new Database.AssetRow(claim.faction().id(),key,"INDUSTRIAL_HOPPER","HOPPER",
                plugin.getConfig().getDouble("net-worth.industrial-hopper-value",5000),System.currentTimeMillis()));
    }
    void saveSpawner(Block block,EntityType type){FactionService.Claim claim=factions.claimAt(block.getLocation());if(claim==null){db.deleteAsset(blockKey(block,"spawner"));invalidate();return;}double unit=spawners.value(type);int count=block.getState() instanceof CreatureSpawner spawner?spawners.stackSize(spawner):1;db.saveAsset(new Database.AssetRow(claim.faction().id(),blockKey(block,"spawner"),"SPAWNER",type.name(),unit*count,System.currentTimeMillis()));invalidate();}
    /** A Dragon Egg is a unique, extremely rare trophy — worth a flat, large sum to whichever faction has one
     *  sitting in their territory, on the same footing as one stored in a chest (see itemValue()) or nested in
     *  a shulker box (see shulkerDragonEggBonus()), so where it happens to be resting doesn't change its value. */
    void saveDragonEgg(Block block){FactionService.Claim claim=factions.claimAt(block.getLocation());if(claim==null){db.deleteAsset(blockKey(block,"dragonegg"));invalidate();return;}db.saveAsset(new Database.AssetRow(claim.faction().id(),blockKey(block,"dragonegg"),"DRAGON_EGG","DRAGON_EGG",dragonEggValue(),System.currentTimeMillis()));invalidate();}
    private double dragonEggValue(){return plugin.getConfig().getDouble("net-worth.base-values.DRAGON_EGG",1000000);}

    void recalculateLoaded(){refreshMarketValues();validateCachedClaims();scanQueue.clear();for(World world:plugin.getServer().getWorlds())Collections.addAll(scanQueue,world.getLoadedChunks());if(scanTask!=null)scanTask.cancel();scanTask=plugin.getServer().getScheduler().runTaskTimer(plugin,()->{int budget=Math.max(1,plugin.getConfig().getInt("net-worth.reconcile-chunks-per-tick",4));while(budget-->0&&!scanQueue.isEmpty()){Chunk chunk=scanQueue.removeFirst();if(!chunk.isLoaded())continue;for(BlockState state:chunk.getTileEntities()){if(state instanceof Container container)containerChanged(container.getInventory());else if(state instanceof CreatureSpawner spawner)saveSpawner(state.getBlock(),spawner.getSpawnedType());}for(Entity entity:chunk.getEntities())if(entity instanceof Villager villager)villagerChanged(villager);}if(scanQueue.isEmpty()&&scanTask!=null){scanTask.cancel();scanTask=null;invalidate();}},1L,1L);}
    private void validateCachedClaims(){for(Database.FactionRow faction:db.factions())for(Database.AssetRow asset:db.assets(faction.id())){if(asset.key().startsWith("villager:"))continue;String[] parts=asset.key().split(":");if(parts.length<5)continue;World world=plugin.getServer().getWorld(parts[1]);try{int x=Integer.parseInt(parts[2]),y=Integer.parseInt(parts[3]),z=Integer.parseInt(parts[4]);Location location=world==null?null:new Location(world,x,y,z);FactionService.Claim claim=location==null?null:factions.claimAt(location);boolean invalid=claim==null||claim.faction().id()!=faction.id();if(!invalid&&world.isChunkLoaded(x>>4,z>>4)){BlockState state=world.getBlockAt(x,y,z).getState();invalid="INDUSTRIAL_HOPPER".equals(asset.type())&&(plugin.industrialHoppers()==null||!plugin.industrialHoppers().isIndustrial(world.getBlockAt(x,y,z)))||"CONTAINER".equals(asset.type())&&!(state instanceof Container)||"SPAWNER".equals(asset.type())&&!(state instanceof CreatureSpawner)||"DRAGON_EGG".equals(asset.type())&&state.getType()!=Material.DRAGON_EGG;}if(invalid)db.deleteAsset(asset.key());}catch(NumberFormatException ignored){db.deleteAsset(asset.key());}}invalidate();}

    void villagerChanged(Villager villager){String key="villager:"+villager.getUniqueId();db.deleteAsset(key);FactionService.Claim claim=factions.claimAt(villager.getLocation());double value=villagerValue(villager);if(claim!=null&&value>0)db.saveAsset(new Database.AssetRow(claim.faction().id(),key,"VILLAGER",villager.getProfession().getKey().getKey(),Math.round(value*100)/100.0,System.currentTimeMillis()));invalidate();}
    void entityChanged(Villager villager){villagerChanged(villager);}
    void entityRemoved(Entity entity){if(entity instanceof Villager villager){db.deleteAsset("villager:"+villager.getUniqueId());invalidate();}}
    void villagerMoved(Villager villager,Location from,Location to){FactionService.Claim a=factions.claimAt(from),b=factions.claimAt(to);if((a==null)!=(b==null)||a!=null&&b!=null&&a.faction().id()!=b.faction().id())plugin.getServer().getScheduler().runTask(plugin,()->{if(villager.isValid())villagerChanged(villager);});}
    void entitiesLoaded(Collection<Entity> entities){for(Entity entity:entities)if(entity instanceof Villager villager)villagerChanged(villager);}
    double villagerValue(Villager villager){if(villager.isAdult()&&villager.getProfession()!=Villager.Profession.NONE&&villager.getProfession()!=Villager.Profession.NITWIT){double value=switch(Math.max(1,villager.getVillagerLevel())){case 1->500;case 2->1000;case 3->2000;case 4->4000;default->8000;};int cures=plugin.villagerDiscounts()==null?0:plugin.villagerDiscounts().cureCount(villager);for(MerchantRecipe recipe:villager.getRecipes())value+=tradeValue(recipe,cures);return Math.min(plugin.getConfig().getDouble("net-worth.villagers.per-villager-cap",125000),value);}return 0;}
    private double tradeValue(MerchantRecipe recipe){return tradeValue(recipe,0);}
    private double tradeValue(MerchantRecipe recipe,int universalCures){ItemStack result=recipe.getResult(),adjusted=recipe.getAdjustedIngredient1();int emeralds=adjusted!=null&&adjusted.getType()==Material.EMERALD?adjusted.getAmount():recipe.getIngredients().stream().filter(item->item.getType()==Material.EMERALD).mapToInt(ItemStack::getAmount).sum();if(universalCures>0&&emeralds>0)emeralds=Math.max(1,emeralds-universalCures*5);double value=0;if(result.getType()==Material.ENCHANTED_BOOK&&result.getItemMeta() instanceof EnchantmentStorageMeta meta){for(var enchant:meta.getStoredEnchants().entrySet()){if(enchant.getKey().equals(Enchantment.MENDING))value=Math.max(value,115_000);else if(enchant.getKey().equals(Enchantment.UNBREAKING)&&enchant.getValue()>=3)value=Math.max(value,70_000);else if(enchant.getKey().equals(Enchantment.FORTUNE)&&enchant.getValue()>=3)value=Math.max(value,60_000);else if(enchant.getKey().equals(Enchantment.SILK_TOUCH))value=Math.max(value,45_000);else if(Set.of(Enchantment.EFFICIENCY,Enchantment.PROTECTION,Enchantment.SHARPNESS).contains(enchant.getKey()))value=Math.max(value,30_000);}}else if(result.getType().name().startsWith("DIAMOND_"))value=12_000;else if(Set.of(Material.NAME_TAG,Material.GLASS,Material.QUARTZ,Material.REDSTONE).contains(result.getType()))value=1500;double priceFactor=emeralds<=1?1.35:emeralds>=48?.35:emeralds>=32?.6:1;double remaining=recipe.getMaxUses()<=0?1:Math.max(.2,(recipe.getMaxUses()-recipe.getUses())/(double)recipe.getMaxUses());return value*priceFactor*(.75+.25*remaining);}

    private double itemValue(ItemStack item){
        if(plugin.graves()!=null&&plugin.graves().isCompass(item)||plugin.shards()!=null&&plugin.shards().bound(item))return 0;
        ItemMeta meta=item.getItemMeta();if(meta.getPersistentDataContainer().has(relicKey))return plugin.getConfig().getDouble("net-worth.relic-value",500000)*item.getAmount();
        /** Sigils are the currency of boss summons, so they are valued off what a summon costs rather than
         *  off their material. Two Legendary Sigils buy a summon outright; eight Elite Sigils buy one only
         *  alongside a 250,000 coin payment, which is what puts a Legendary well above an Elite. */
        if(meta.getPersistentDataContainer().has(legendarySigilValueKey))return plugin.getConfig().getDouble("net-worth.legendary-sigil-value",400000)*item.getAmount();
        if(meta.getPersistentDataContainer().has(eliteSigilValueKey))return plugin.getConfig().getDouble("net-worth.elite-sigil-value",75000)*item.getAmount();
        double shulkerBonus=shulkerDragonEggBonus(item,meta);
        double base=marketOrBase(item.getType());
        if(base<=0)return shulkerBonus;
        double enchant=enchantBonus(meta,base);
        return base*item.getAmount()+enchant*item.getAmount()+shulkerBonus;
    }
    /** Shulker boxes are priced flat (see net-worth.base-values.SHULKER_BOX) regardless of contents — fine for
     *  ordinary junk, but a Dragon Egg tucked inside one would otherwise vanish from a faction's net worth
     *  entirely instead of counting the same as one sitting loose in the chest around it. */
    private double shulkerDragonEggBonus(ItemStack item,ItemMeta meta){
        if(!item.getType().name().endsWith("SHULKER_BOX")||!(meta instanceof BlockStateMeta bsm)||!(bsm.getBlockState() instanceof ShulkerBox shulker))return 0;
        double bonus=0;for(ItemStack inner:shulker.getInventory().getContents())if(inner!=null&&inner.getType()==Material.DRAGON_EGG)bonus+=dragonEggValue()*inner.getAmount();
        return bonus;
    }
    /** Enchantments add real but bounded value — a modest per-enchant amount reflecting how much they'd
     *  actually move a private sale, not a multiplier that lets a single item snowball. Overall capped at the
     *  item's own base value, so even a maxed-out piece can at most double, never dominate the total. */
    private double enchantBonus(ItemMeta meta,double base){
        if(!meta.hasEnchants())return 0;
        double bonus=0;
        for(var entry:meta.getEnchants().entrySet()){
            Enchantment ench=entry.getKey();int level=Math.max(1,entry.getValue());
            if(ench.equals(Enchantment.VANISHING_CURSE)||ench.equals(Enchantment.BINDING_CURSE))continue;
            double per=ench.equals(Enchantment.MENDING)?3000
                    :ench.equals(Enchantment.SILK_TOUCH)?2000
                    :ench.equals(Enchantment.INFINITY)?1500
                    :ench.equals(Enchantment.FORTUNE)||ench.equals(Enchantment.LOOTING)?800
                    :ench.equals(Enchantment.UNBREAKING)?500
                    :ench.equals(Enchantment.SHARPNESS)||ench.equals(Enchantment.PROTECTION)||ench.equals(Enchantment.EFFICIENCY)||ench.equals(Enchantment.POWER)?400
                    :150;
            bonus+=per*level;
        }
        return Math.min(bonus,base);
    }
    private double marketOrBase(Material material){int minimum=plugin.getConfig().getInt("net-worth.market.minimum-sales",5);Database.MarketValueRow market=db.marketValue(material.name());if(market!=null&&market.samples()>=minimum)return market.value();double configured=plugin.getConfig().getDouble("net-worth.base-values."+material.name(),-1);if(configured<0&&material.name().endsWith("SHULKER_BOX"))configured=plugin.getConfig().getDouble("net-worth.base-values.SHULKER_BOX",-1);if(configured>=0)return configured;return shop.configuredSell(material);}
    private void refreshMarketValues(){
        long since=System.currentTimeMillis()-plugin.getConfig().getLong("net-worth.market.window-days",30)*86400000L;Map<Material,List<Double>> samples=new HashMap<>();Set<String> repeated=new HashSet<>();
        for(Database.AuctionSaleRow sale:db.auctionSales(since)){if(sale.seller().equals(sale.buyer())||sale.item()==null||sale.item().getAmount()<1)continue;Database.FactionRow seller=db.factionOf(sale.seller()),buyer=db.factionOf(sale.buyer());if(seller!=null&&buyer!=null&&seller.id()==buyer.id())continue;String pair=sale.seller().compareTo(sale.buyer())<0?sale.seller()+":"+sale.buyer():sale.buyer()+":"+sale.seller();String repeatKey=pair+":"+sale.item().getType()+":"+(sale.soldAt()/86400000L);if(!repeated.add(repeatKey))continue;double unit=sale.price()/sale.item().getAmount();if(Double.isFinite(unit)&&unit>.01)samples.computeIfAbsent(sale.item().getType(),x->new ArrayList<>()).add(unit);}
        int minimum=plugin.getConfig().getInt("net-worth.market.minimum-sales",5);double change=Math.max(.01,plugin.getConfig().getDouble("net-worth.market.max-change-per-refresh",.20));
        for(var entry:samples.entrySet()){List<Double> values=entry.getValue();if(values.size()<minimum)continue;Collections.sort(values);double firstMedian=median(values);List<Double> trimmed=values.stream().filter(v->v>=firstMedian*.25&&v<=firstMedian*4).sorted().collect(Collectors.toList());if(trimmed.size()<minimum)continue;double estimate=median(trimmed);Database.MarketValueRow previous=db.marketValue(entry.getKey().name());if(previous!=null){double low=previous.value()*(1-change),high=previous.value()*(1+change);estimate=Math.max(low,Math.min(high,estimate));}db.saveMarketValue(new Database.MarketValueRow(entry.getKey().name(),Math.round(estimate*100)/100.0,trimmed.size(),System.currentTimeMillis()));}
    }
    private double median(List<Double> values){int n=values.size();return n%2==1?values.get(n/2):(values.get(n/2-1)+values.get(n/2))/2;}
    private void refreshFactionVillagers(Database.FactionRow faction){FactionService.Claim claim=factions.claimOf(faction);World world=plugin.getServer().getWorld(faction.world());if(claim==null||world==null)return;for(int cx=claim.minX()>>4;cx<=claim.maxX()>>4;cx++)for(int cz=claim.minZ()>>4;cz<=claim.maxZ()>>4;cz++){if(!world.isChunkLoaded(cx,cz))continue;for(Entity entity:world.getChunkAt(cx,cz).getEntities())if(entity instanceof Villager villager)villagerChanged(villager);}}
    private List<VillagerEntry> villagerEntries(Database.FactionRow faction){
        FactionService.Claim claim=factions.claimOf(faction);if(claim==null)return List.of();World world=plugin.getServer().getWorld(faction.world());if(world==null)return List.of();List<VillagerEntry> entries=new ArrayList<>();int buffer=64,minChunk=(claim.minX()-buffer)>>4,maxChunk=(claim.maxX()+buffer)>>4,minZ=(claim.minZ()-buffer)>>4,maxZ=(claim.maxZ()+buffer)>>4;
        for(int cx=minChunk;cx<=maxChunk;cx++)for(int cz=minZ;cz<=maxZ;cz++){if(!world.isChunkLoaded(cx,cz))continue;for(Entity entity:world.getChunkAt(cx,cz).getEntities())if(entity instanceof Villager villager){Location location=villager.getLocation();boolean inside=claim.contains(location),valid=villager.isAdult()&&villager.getProfession()!=Villager.Profession.NONE&&villager.getProfession()!=Villager.Profession.NITWIT;double amount=inside&&valid?villagerValue(villager):0;String detail=inside?(valid?"Level "+villager.getVillagerLevel()+" • entity "+villager.getUniqueId():"Not an adult employed villager"):"Outside faction claim";entries.add(new VillagerEntry(inside&&valid,CoreUtil.pretty(villager.getProfession().getKey().getKey())+" Villager",detail,location,amount,importantTrades(villager)));}}
        for(Database.AssetRow asset:db.assets(faction.id()))if(asset.type().equals("VILLAGER")&&asset.material().equals("CAPSULE")){Location location=assetLocation(asset.key());if(location!=null)entries.add(new VillagerEntry(true,"Captured Villager Capsule","Stored in a claim container",location,asset.value(),List.of("Capture-time trade value")));}entries.sort(java.util.Comparator.comparing(VillagerEntry::counted).reversed().thenComparing(java.util.Comparator.comparingDouble(VillagerEntry::value).reversed()));return entries;
    }
    private List<String> importantTrades(Villager villager){List<String> rows=new ArrayList<>();for(MerchantRecipe recipe:villager.getRecipes()){double value=tradeValue(recipe);if(value<1500)continue;rows.add(CoreUtil.pretty(recipe.getResult().getType().name())+" • "+CoreUtil.money(value));if(rows.size()>=4)break;}if(rows.isEmpty())rows.add("No premium trades");return rows;}
    private List<String> spawnerLore(long factionId,double total){Map<String,Integer> counts=new TreeMap<>();Map<String,Double> values=new TreeMap<>();for(Database.AssetRow asset:db.assets(factionId))if(asset.type().equals("SPAWNER")){EntityType type;try{type=EntityType.valueOf(asset.material());}catch(IllegalArgumentException e){continue;}double unit=Math.max(.01,spawners.value(type));int count=Math.max(1,(int)Math.round(asset.value()/unit));counts.merge(asset.material(),count,Integer::sum);values.merge(asset.material(),asset.value(),Double::sum);}List<String> lore=new ArrayList<>();lore.add("Total: "+CoreUtil.money(total));for(String type:counts.keySet())lore.add(CoreUtil.pretty(type)+" ×"+counts.get(type)+" • "+CoreUtil.money(values.get(type)));if(counts.isEmpty())lore.add("No configured spawners found");return lore;}
    private Location assetLocation(String key){String[] parts=key.split(":");if(parts.length<5)return null;try{World world=plugin.getServer().getWorld(parts[1]);return world==null?null:new Location(world,Integer.parseInt(parts[2]),Integer.parseInt(parts[3]),Integer.parseInt(parts[4]));}catch(NumberFormatException e){return null;}}
    private ItemStack icon(Material material,String name,List<String> lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(name,NamedTextColor.GOLD));meta.lore(lore.stream().map(line->Component.text(line,NamedTextColor.GRAY)).toList());item.setItemMeta(meta);return item;}
    private ContainerRef containerRef(Inventory inventory){
        InventoryHolder holder=inventory.getHolder(false);List<Block> blocks=new ArrayList<>();
        if(holder instanceof DoubleChest doubleChest){if(doubleChest.getLeftSide() instanceof Chest left)blocks.add(left.getBlock());if(doubleChest.getRightSide() instanceof Chest right)blocks.add(right.getBlock());}
        else if(holder instanceof Container container)blocks.add(container.getBlock());
        Location fallback;try{fallback=blocks.isEmpty()?inventory.getLocation():null;}catch(Exception ignored){fallback=null;}
        if(fallback!=null)blocks.add(fallback.getBlock());if(blocks.isEmpty())return null;
        blocks.sort(java.util.Comparator.comparing((Block b)->b.getWorld().getName()).thenComparingInt(Block::getX).thenComparingInt(Block::getY).thenComparingInt(Block::getZ));Block first=blocks.getFirst();return new ContainerRef(blockKey(first,"container"),first.getLocation(),inventory);
    }
    private String blockKey(Block block,String type){return type+":"+block.getWorld().getName()+":"+block.getX()+":"+block.getY()+":"+block.getZ();}
}
