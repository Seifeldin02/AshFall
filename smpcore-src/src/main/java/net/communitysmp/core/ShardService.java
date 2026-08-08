package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

final class ShardService implements Listener {
    enum Category { UTILITY, TOOLS, ARMOR, WEAPONS, COSMETIC }
    record Stock(String key,Material icon,String name,int price,int limit,String period,Category category,boolean mandatory){}
    private record CosmeticHolder() implements InventoryHolder {@Override public Inventory getInventory(){return null;}}

    private final SMPCore plugin;
    private final Database db;
    private final NamespacedKey boundKey,stockKey,toolKey,cosmeticTokenKey;
    private final Map<UUID,Long> lastActivity=new ConcurrentHashMap<>();
    private final Set<UUID> toolGuard=ConcurrentHashMap.newKeySet();
    private org.bukkit.configuration.file.YamlConfiguration config;
    private BukkitTask playTask,effectTask;

    ShardService(SMPCore plugin){
        this.plugin=plugin;db=plugin.db();boundKey=new NamespacedKey(plugin,"shard_bound_owner");stockKey=new NamespacedKey(plugin,"shard_stock");toolKey=new NamespacedKey(plugin,"shard_tool");cosmeticTokenKey=new NamespacedKey(plugin,"cosmetic_unlock");
        reload();
        playTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::playtimeTick,1200L,1200L);
        effectTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::effectTick,20L,10L);
    }
    void reload(){config=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"shards.yml"));}
    void shutdown(){if(playTask!=null)playTask.cancel();if(effectTask!=null)effectTask.cancel();}
    void join(Player player){lastActivity.put(player.getUniqueId(),System.currentTimeMillis());db.shardAccount(CoreUtil.id(player));}
    void quit(Player player){lastActivity.remove(player.getUniqueId());}
    void activity(Player player){lastActivity.put(player.getUniqueId(),System.currentTimeMillis());}
    int balance(Player player){return db.shardBalance(CoreUtil.id(player));}
    int adminAdjust(String playerId,int delta){return db.adjustShardsAdmin(playerId,delta);}
    int adminSet(String playerId,int amount){return db.setShardsAdmin(playerId,amount);}

    private void playtimeTick(){
        long now=System.currentTimeMillis(),activeWindow=config.getLong("earning.active-window-seconds",300)*1000L;
        int activeThreshold=config.getInt("earning.active-seconds-per-shard",2700),afkThreshold=config.getInt("earning.afk-seconds-per-shard",3600);
        for(Player player:plugin.getServer().getOnlinePlayers()){
            boolean active=now-lastActivity.getOrDefault(player.getUniqueId(),now)<=activeWindow;
            int earned=db.accrueShardTime(CoreUtil.id(player),active,60,activeThreshold,afkThreshold);
            if(earned>0){shardMessage(player,"+"+earned+" Shard"+(earned==1?"":"s")+" • playtime");if(plugin.settings().sounds(player))player.playSound(player.getLocation(),Sound.BLOCK_AMETHYST_BLOCK_CHIME,.7f,1.4f);}
        }
    }

    List<Stock> stock(){
        List<Stock> result=new ArrayList<>();
        var section=config.getConfigurationSection("stock");if(section!=null)for(String key:section.getKeys(false)){
            Material material=Material.matchMaterial(section.getString(key+".icon","PAPER"));if(material==null)material=Material.PAPER;
            String categoryRaw=section.getString(key+".category","UTILITY").toUpperCase(Locale.ROOT);Category category;try{category=Category.valueOf(categoryRaw);}catch(Exception ignored){category=Category.UTILITY;}
            result.add(new Stock(key,material,section.getString(key+".name",CoreUtil.pretty(key)),section.getInt(key+".price",1),section.getInt(key+".limit",0),section.getString(key+".period","WEEK"),category,section.getBoolean(key+".mandatory",false)));
        }
        Stock rotating=rotatingCosmetic();if(rotating!=null)result.add(rotating);
        return List.copyOf(result);
    }
    Stock stock(String key){return stock().stream().filter(row->row.key().equalsIgnoreCase(key)).findFirst().orElse(null);}
    String period(Stock stock){return switch(stock.period().toUpperCase(Locale.ROOT)){case"LIFETIME"->"LIFETIME";case"MONTH"->YearMonth.now().toString();default->weekKey();};}
    int remaining(Player player,Stock stock){if(stock.limit()<=0)return Integer.MAX_VALUE;return Math.max(0,stock.limit()-db.shardPurchaseCount(CoreUtil.id(player),stock.key(),period(stock)));}
    void purchase(Player player,Stock stock){
        if(stock==null){CoreUtil.error(player,"That Shard Shop item is unavailable.");return;}
        int remaining=remaining(player,stock);if(remaining<=0){CoreUtil.error(player,"You have reached this stock limit.");return;}
        ItemStack item=create(player,stock);if(item==null){CoreUtil.error(player,"That reward could not be prepared safely.");return;}
        if(!ShopService.canFit(player,item)){CoreUtil.error(player,"Make enough inventory space first.");return;}
        if(!db.spendShards(CoreUtil.id(player),stock.price(),stock.key())){CoreUtil.error(player,"You need "+stock.price()+" Shards.");return;}
        db.recordShardPurchase(CoreUtil.id(player),stock.key(),period(stock),1);CoreUtil.give(player,item);
        shardMessage(player,"Purchased "+stock.name()+" for "+stock.price()+" Shards.");
        plugin.settings().marketSound(player,"shard");
    }

    ItemStack displayItem(Stock stock){return createReward(stock);}
    ItemStack excavatorPickaxe(boolean fortune){return tool(Material.NETHERITE_PICKAXE,"EXCAVATOR",fortune?Map.of(Enchantment.EFFICIENCY,5,Enchantment.FORTUNE,3,Enchantment.UNBREAKING,3,Enchantment.MENDING,1):Map.of(Enchantment.EFFICIENCY,5,Enchantment.SILK_TOUCH,1,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));}
    private ItemStack create(Player player,Stock stock){ItemStack item=createReward(stock);if(item!=null)bind(item,player,stock.key());return item;}
    private ItemStack createReward(Stock stock){
        ItemStack item=switch(stock.key().toLowerCase(Locale.ROOT)){
            case"disposable_capsule"->plugin.capsules().empty(false);
            case"reusable_capsule"->plugin.capsules().empty(true);
            case"sealed_omen"->plugin.bosses().createSummonScroll();
            case"enchanted_golden_apple"->new ItemStack(Material.ENCHANTED_GOLDEN_APPLE);
            case"premium_supplies"->supplies();
            case"fortune_excavator"->tool(Material.NETHERITE_PICKAXE,"EXCAVATOR",Map.of(Enchantment.EFFICIENCY,5,Enchantment.FORTUNE,3,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"silk_excavator"->tool(Material.NETHERITE_PICKAXE,"EXCAVATOR",Map.of(Enchantment.EFFICIENCY,5,Enchantment.SILK_TOUCH,1,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"fortune_shovel"->tool(Material.NETHERITE_SHOVEL,"EXCAVATOR",Map.of(Enchantment.EFFICIENCY,5,Enchantment.FORTUNE,3,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"silk_shovel"->tool(Material.NETHERITE_SHOVEL,"EXCAVATOR",Map.of(Enchantment.EFFICIENCY,5,Enchantment.SILK_TOUCH,1,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"feller_axe"->tool(Material.NETHERITE_AXE,"FELLER",Map.of(Enchantment.EFFICIENCY,5,Enchantment.SHARPNESS,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"market_axe"->tool(Material.NETHERITE_AXE,"MARKET",Map.of(Enchantment.EFFICIENCY,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"haste_24h"->tool(Material.HONEY_BOTTLE,"HASTE_24H",Map.of());
            case"netherite_pickaxe"->enchanted(Material.NETHERITE_PICKAXE,Map.of(Enchantment.EFFICIENCY,5,Enchantment.FORTUNE,3,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"netherite_shovel"->enchanted(Material.NETHERITE_SHOVEL,Map.of(Enchantment.EFFICIENCY,5,Enchantment.FORTUNE,3,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"netherite_axe"->enchanted(Material.NETHERITE_AXE,Map.of(Enchantment.EFFICIENCY,5,Enchantment.SHARPNESS,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"netherite_hoe"->enchanted(Material.NETHERITE_HOE,Map.of(Enchantment.EFFICIENCY,5,Enchantment.FORTUNE,3,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"netherite_sword"->enchanted(Material.NETHERITE_SWORD,Map.of(Enchantment.SHARPNESS,5,Enchantment.LOOTING,3,Enchantment.SWEEPING_EDGE,3,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"netherite_helmet"->enchanted(Material.NETHERITE_HELMET,Map.of(Enchantment.PROTECTION,4,Enchantment.RESPIRATION,3,Enchantment.AQUA_AFFINITY,1,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"netherite_chestplate"->enchanted(Material.NETHERITE_CHESTPLATE,Map.of(Enchantment.PROTECTION,4,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"netherite_leggings"->enchanted(Material.NETHERITE_LEGGINGS,Map.of(Enchantment.PROTECTION,4,Enchantment.SWIFT_SNEAK,3,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"netherite_boots"->enchanted(Material.NETHERITE_BOOTS,Map.of(Enchantment.PROTECTION,4,Enchantment.FEATHER_FALLING,4,Enchantment.DEPTH_STRIDER,3,Enchantment.SOUL_SPEED,3,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"netherite_spear"->enchanted(Material.NETHERITE_SPEAR,Map.of(Enchantment.SHARPNESS,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"mace"->enchanted(Material.MACE,Map.of(Enchantment.DENSITY,5,Enchantment.WIND_BURST,2,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            case"bow"->enchanted(Material.BOW,Map.of(Enchantment.POWER,5,Enchantment.PUNCH,2,Enchantment.FLAME,1,Enchantment.INFINITY,1,Enchantment.UNBREAKING,3));
            case"crossbow"->enchanted(Material.CROSSBOW,Map.of(Enchantment.QUICK_CHARGE,3,Enchantment.MULTISHOT,1,Enchantment.UNBREAKING,3,Enchantment.MENDING,1));
            default->stock.key().startsWith("cosmetic_")?cosmeticToken(stock):new ItemStack(stock.icon());
        };
        return item;
    }
    /** Admin/staging loadout: everything the Shard Shop's gear tier sells, fully maxed, plus the consumables
     *  needed to actually stress-test combat (boss fights, PvP, relic abilities) without grinding for them.
     *  Deliberately built from the SAME enchanted() catalogue the shop uses, so what testers hold is exactly
     *  what players can buy rather than a parallel definition that could drift. Admin-gated at the command. */
    void giveTestKit(Player player){
        List<ItemStack> kit=new ArrayList<>(List.of(
                enchanted(Material.NETHERITE_HELMET,Map.of(Enchantment.PROTECTION,4,Enchantment.UNBREAKING,3,Enchantment.MENDING,1,Enchantment.RESPIRATION,3,Enchantment.AQUA_AFFINITY,1)),
                enchanted(Material.NETHERITE_CHESTPLATE,Map.of(Enchantment.PROTECTION,4,Enchantment.UNBREAKING,3,Enchantment.MENDING,1)),
                enchanted(Material.NETHERITE_LEGGINGS,Map.of(Enchantment.PROTECTION,4,Enchantment.UNBREAKING,3,Enchantment.MENDING,1)),
                enchanted(Material.NETHERITE_BOOTS,Map.of(Enchantment.PROTECTION,4,Enchantment.UNBREAKING,3,Enchantment.MENDING,1,Enchantment.FEATHER_FALLING,4,Enchantment.DEPTH_STRIDER,3)),
                enchanted(Material.NETHERITE_SWORD,Map.of(Enchantment.SHARPNESS,5,Enchantment.LOOTING,3,Enchantment.SWEEPING_EDGE,3,Enchantment.UNBREAKING,3,Enchantment.MENDING,1)),
                enchanted(Material.NETHERITE_AXE,Map.of(Enchantment.SHARPNESS,5,Enchantment.EFFICIENCY,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1)),
                enchanted(Material.MACE,Map.of(Enchantment.DENSITY,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1)),
                enchanted(Material.BOW,Map.of(Enchantment.POWER,5,Enchantment.INFINITY,1,Enchantment.FLAME,1,Enchantment.UNBREAKING,3)),
                enchanted(Material.CROSSBOW,Map.of(Enchantment.QUICK_CHARGE,3,Enchantment.MULTISHOT,1,Enchantment.UNBREAKING,3,Enchantment.MENDING,1)),
                enchanted(Material.SHIELD,Map.of(Enchantment.UNBREAKING,3,Enchantment.MENDING,1)),
                enchanted(Material.ELYTRA,Map.of(Enchantment.UNBREAKING,3,Enchantment.MENDING,1)),
                new ItemStack(Material.ENCHANTED_GOLDEN_APPLE,30),
                new ItemStack(Material.COOKED_BEEF,64),
                new ItemStack(Material.ENDER_PEARL,32),
                new ItemStack(Material.ARROW,64)));
        /** Spear is version-gated: NETHERITE_SPEAR only exists on builds that ship the combat spear, and a
         *  hard reference would fail to load the class entirely on ones that don't. */
        Material spear=Material.matchMaterial("NETHERITE_SPEAR");
        if(spear!=null)kit.add(enchanted(spear,Map.of(Enchantment.SHARPNESS,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1)));
        for(int i=0;i<2;i++)kit.add(new ItemStack(Material.WIND_CHARGE,64));
        for(int i=0;i<2;i++)kit.add(firework(64,1));
        for(int i=0;i<5;i++)kit.add(strengthPotion());
        for(ItemStack item:kit)CoreUtil.give(player,item);
        CoreUtil.msg(player,"Test kit issued: full maxed Shard Shop loadout, 30 e-apples, 5x Strength II, 64 steak, 32 pearls, 2x64 wind charges, elytra + 2x64 tier-1 rockets.");
    }
    private ItemStack strengthPotion(){
        ItemStack potion=new ItemStack(Material.POTION);
        org.bukkit.inventory.meta.PotionMeta meta=(org.bukkit.inventory.meta.PotionMeta)potion.getItemMeta();
        meta.addCustomEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.STRENGTH,3600,1),true);
        meta.displayName(Component.text("Potion of Strength II",NamedTextColor.LIGHT_PURPLE));
        potion.setItemMeta(meta);potion.setAmount(1);return potion;
    }
    private ItemStack firework(int amount,int power){
        ItemStack rocket=new ItemStack(Material.FIREWORK_ROCKET,amount);
        org.bukkit.inventory.meta.FireworkMeta meta=(org.bukkit.inventory.meta.FireworkMeta)rocket.getItemMeta();
        meta.setPower(power);rocket.setItemMeta(meta);return rocket;
    }
    private ItemStack enchanted(Material material,Map<Enchantment,Integer> enchants){ItemStack item=new ItemStack(material);enchants.forEach((enchant,level)->item.addUnsafeEnchantment(enchant,level));return item;}
    private ItemStack supplies(){
        ItemStack item=new ItemStack(Material.BUNDLE);BundleMeta meta=(BundleMeta)item.getItemMeta();
        meta.setItems(List.of(new ItemStack(Material.GOLDEN_CARROT,16),new ItemStack(Material.FIREWORK_ROCKET,16),new ItemStack(Material.ENDER_PEARL,4),new ItemStack(Material.TORCH,16)));
        item.setItemMeta(meta);return item;
    }
    private ItemStack tool(Material material,String function,Map<Enchantment,Integer> enchants){
        ItemStack item=enchanted(material,enchants);
        ItemMeta meta=item.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey,PersistentDataType.STRING,function);
        String description=switch(function){
            case"EXCAVATOR"->"Breaks a careful 3 × 3 area.";
            case"FELLER"->"Fells connected logs.";
            case"MARKET"->"Right-click to open the Sell Basket.";
            default->"Drink for Haste II (24 hours).";
        };
        meta.lore(List.of(Component.text(description,NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }
    private ItemStack cosmeticToken(Stock stock){ItemStack item=CoreUtil.named(stock.icon(),stock.name(),List.of("Right-click to unlock and equip."));ItemMeta meta=item.getItemMeta();meta.getPersistentDataContainer().set(cosmeticTokenKey,PersistentDataType.STRING,stock.key().substring("cosmetic_".length()));item.setItemMeta(meta);return item;}
    private void bind(ItemStack item,Player player,String stock){ItemMeta meta=item.getItemMeta();meta.getPersistentDataContainer().set(boundKey,PersistentDataType.STRING,CoreUtil.id(player));meta.getPersistentDataContainer().set(stockKey,PersistentDataType.STRING,stock);item.setItemMeta(meta);}
    boolean bound(ItemStack item){return item!=null&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().has(boundKey,PersistentDataType.STRING);}
    boolean belongsTo(Player player,ItemStack item){return !bound(item)||CoreUtil.id(player).equals(item.getItemMeta().getPersistentDataContainer().get(boundKey,PersistentDataType.STRING));}
    /** Re-assigns a Shard-bound item's ownership tag to a new player, keeping its stock/source tag as-is.
     *  Used only by the one sanctioned bound-item transfer path — looting it from a defeated player's PvP
     *  grave (see GraveService.isGraveContents()/isGraveLoot()). Everywhere else (drop, trade, regular
     *  storage) stays exactly as protected as before; this doesn't loosen those checks, it just moves who
     *  they protect once a legitimate transfer has actually happened, so the new owner isn't immediately
     *  locked out of an item they just rightfully looted. */
    void transferBinding(ItemStack item,Player newOwner){
        if(!bound(item))return;
        ItemMeta meta=item.getItemMeta();
        meta.getPersistentDataContainer().set(boundKey,PersistentDataType.STRING,CoreUtil.id(newOwner));
        item.setItemMeta(meta);
    }

    void rewardElite(Player player,String tier){
        /** No boss reward exceeds 10 Shards — world bosses were previously the outlier at 50, well above
         *  every other tier (legendary tops out at 10, dragon/wither also cap at 10). Cooldowns and anti-farm
         *  gating are untouched by this — they live on the boss spawn/kill side (BossEventService), not here. */
        int amount=switch(tier.toLowerCase(Locale.ROOT)){case"epic"->2;case"legendary"->10;case"worldboss","worldboss_ashen","worldboss_iron","worldboss_piglin"->10;default->0;};
        if("rare".equalsIgnoreCase(tier)){if(Math.random()>=config.getDouble("earning.rare-chance",.12))return;amount=1;}
        if(amount>0)award(player,amount,"ELITE_"+tier.toUpperCase(Locale.ROOT),0);
    }
    /** weekly is only meaningful for ENDER_DRAGON — a manually crystal-revived dragon keeps its reduced
     *  vanilla respawn rewards and gives no Shards at all, only the weekly-designated encounter does. */
    void rewardBoss(Player player,org.bukkit.entity.EntityType type,boolean weekly){
        if(type==org.bukkit.entity.EntityType.ENDER_DRAGON){if(weekly)award(player,10,"DRAGON",config.getLong("earning.dragon-cooldown-hours",168)*3600000L);}
        else if(type==org.bukkit.entity.EntityType.WITHER)award(player,10,"WITHER",config.getLong("earning.wither-cooldown-hours",24)*3600000L);
        else if(type==org.bukkit.entity.EntityType.WARDEN)award(player,ThreadLocalRandom.current().nextInt(3,6),"WARDEN",config.getLong("earning.warden-cooldown-hours",24)*3600000L);
    }
    private void award(Player player,int amount,String source,long cooldown){
        String id=CoreUtil.id(player);if(cooldown>0&&!db.claimShardCooldown(id,source,cooldown))return;db.addShards(id,amount,source,null);shardMessage(player,"+"+amount+" Shards • "+CoreUtil.pretty(source));if(plugin.settings().sounds(player))player.playSound(player.getLocation(),Sound.BLOCK_AMETHYST_CLUSTER_BREAK,.8f,1.25f);
    }

    @EventHandler public void interact(PlayerInteractEvent event){
        Player player=event.getPlayer();activity(player);ItemStack item=event.getItem();if(item==null||!belongsTo(player,item))return;
        ItemMeta meta=item.getItemMeta();String function=meta.getPersistentDataContainer().get(toolKey,PersistentDataType.STRING);
        if(event.getAction().isRightClick()&&"MARKET".equals(function)){event.setCancelled(true);plugin.marketplace().openSellBasket(player,false);return;}
        if(event.getAction().isRightClick()&&"HASTE_24H".equals(function)){event.setCancelled(true);consumeOne(player,event.getHand(),item);long expiry=Math.max(System.currentTimeMillis(),parseLong(db.state("shard_haste:"+CoreUtil.id(player))))+86400000L;db.state("shard_haste:"+CoreUtil.id(player),Long.toString(expiry));CoreUtil.msg(player,"Haste II active for 24 hours.");return;}
        String cosmetic=meta.getPersistentDataContainer().get(cosmeticTokenKey,PersistentDataType.STRING);
        if(event.getAction().isRightClick()&&cosmetic!=null){event.setCancelled(true);if(db.unlockCosmetic(CoreUtil.id(player),cosmetic)){consumeOne(player,event.getHand(),item);db.activateCosmetic(CoreUtil.id(player),cosmetic);CoreUtil.msg(player,CoreUtil.pretty(cosmetic)+" unlocked and equipped.");}else CoreUtil.msg(player,"You already own this cosmetic.");}
    }

    @EventHandler(ignoreCancelled=true) public void breakBlock(BlockBreakEvent event){
        Player player=event.getPlayer();activity(player);ItemStack item=player.getInventory().getItemInMainHand();if(!belongsTo(player,item)||!item.hasItemMeta()||toolGuard.contains(player.getUniqueId()))return;
        String function=item.getItemMeta().getPersistentDataContainer().get(toolKey,PersistentDataType.STRING);if(function==null)return;
        if("EXCAVATOR".equals(function))breakArea(player,event.getBlock());else if("FELLER".equals(function)&&event.getBlock().getType().name().endsWith("_LOG"))fellTree(player,event.getBlock());
    }
    private void breakArea(Player player,Block origin){
        toolGuard.add(player.getUniqueId());try{
            Vector direction=player.getEyeLocation().getDirection();int axis=Math.abs(direction.getY())>.65?0:Math.abs(direction.getX())>Math.abs(direction.getZ())?1:2;
            for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++){Block block=switch(axis){case 0->origin.getRelative(a,0,b);case 1->origin.getRelative(0,a,b);default->origin.getRelative(a,b,0);};if(block.equals(origin)||!safeToolBlock(player,block))continue;player.breakBlock(block);}
        }finally{toolGuard.remove(player.getUniqueId());}
    }
    private void fellTree(Player player,Block origin){
        toolGuard.add(player.getUniqueId());try{Deque<Block> queue=new ArrayDeque<>();Set<String> seen=new HashSet<>();queue.add(origin);int broken=0;while(!queue.isEmpty()&&broken<64){Block block=queue.removeFirst();String key=block.getX()+":"+block.getY()+":"+block.getZ();if(!seen.add(key)||!block.getType().name().endsWith("_LOG"))continue;if(!block.equals(origin)&&safeToolBlock(player,block)){player.breakBlock(block);broken++;}for(int x=-1;x<=1;x++)for(int y=0;y<=1;y++)for(int z=-1;z<=1;z++)queue.add(block.getRelative(x,y,z));}}finally{toolGuard.remove(player.getUniqueId());}
    }
    private boolean safeToolBlock(Player player,Block block){if(block.getType().isAir()||Set.of(Material.BEDROCK,Material.BARRIER,Material.SPAWNER).contains(block.getType())||block.getState() instanceof org.bukkit.block.Container)return false;if(plugin.spawnClaims().contains(block.getLocation())&&!plugin.isAdmin(player))return false;FactionService.Claim claim=plugin.factions().claimAt(block.getLocation());return claim==null||plugin.factions().isMember(player,claim.faction());}

    @EventHandler public void drop(PlayerDropItemEvent event){if(bound(event.getItemDrop().getItemStack())){event.setCancelled(true);CoreUtil.error(event.getPlayer(),"Shard rewards are account-bound.");}}
    @EventHandler public void pickup(EntityPickupItemEvent event){
        if(!(event.getEntity() instanceof Player player))return;
        ItemStack stack=event.getItem().getItemStack();
        if(belongsTo(player,stack))return;
        if(plugin.graves()!=null&&plugin.graves().isGraveLoot(event.getItem())){transferBinding(stack,player);event.getItem().setItemStack(stack);return;}
        event.setCancelled(true);
    }
    @EventHandler public void inventory(InventoryClickEvent event){
        if(event.getInventory().getHolder(false) instanceof CosmeticHolder){event.setCancelled(true);if(event.getWhoClicked() instanceof Player player&&event.getRawSlot()>=10&&event.getRawSlot()<10+db.cosmetics(CoreUtil.id(player)).size()){Database.CosmeticRow row=db.cosmetics(CoreUtil.id(player)).get(event.getRawSlot()-10);db.activateCosmetic(CoreUtil.id(player),row.cosmetic());openCosmetics(player);}else if(event.getWhoClicked() instanceof Player player&&event.getRawSlot()==31){db.activateCosmetic(CoreUtil.id(player),null);openCosmetics(player);}return;}
        if(!(event.getWhoClicked() instanceof Player player))return;
        /** GraveService's own click() handler already blocks every action that would PLACE an item into a
         *  grave, so anything reaching here in a grave-contents view is inherently being taken OUT — the one
         *  sanctioned bound-item transfer path (loot a defeated player's grave). Rebind on the way out so the
         *  new owner isn't immediately locked out of what they just legitimately looted, and skip straight
         *  past every other check below (they don't apply to a grave GUI anyway). */
        if(plugin.graves()!=null&&plugin.graves().isGraveContents(event.getView().getTopInventory())){
            ItemStack current=event.getCurrentItem(),cursor=event.getCursor();
            if(bound(current)&&!belongsTo(player,current)){transferBinding(current,player);event.setCurrentItem(current);}
            if(bound(cursor)&&!belongsTo(player,cursor)){transferBinding(cursor,player);event.setCursor(cursor);}
            return;
        }
        Inventory topInv=event.getView().getTopInventory();
        org.bukkit.event.inventory.InventoryType topType=topInv.getType();
        if(topType==org.bukkit.event.inventory.InventoryType.ANVIL||plugin.enderChests().isPersonalStorage(topInv,player))return;
        if(topType==org.bukkit.event.inventory.InventoryType.PLAYER){
            if(topInv.getHolder(false) instanceof Player target&&!target.equals(player))inspectionTransfer(player,CoreUtil.id(target),target.getName(),"INV",event);
            return;
        }
        if(plugin.enderChests().isAdminInspecting(topInv,player)){
            String targetId=plugin.enderChests().targetOf(topInv);
            Database.PlayerRow row=targetId==null?null:db.player(targetId);
            if(targetId!=null&&row!=null)inspectionTransfer(player,targetId,row.name(),"ENDERCHEST",event);
            return;
        }
        ItemStack current=event.getCurrentItem(),cursor=event.getCursor();
        if(bound(current)&&!belongsTo(player,current)||bound(cursor)&&!belongsTo(player,cursor)){event.setCancelled(true);CoreUtil.error(player,"That Shard reward belongs to another player.");return;}
        boolean movingBound=bound(cursor)&&event.getRawSlot()<event.getView().getTopInventory().getSize()||event.isShiftClick()&&event.getRawSlot()>=event.getView().getTopInventory().getSize()&&bound(current);
        if(movingBound){event.setCancelled(true);CoreUtil.error(player,"Shard rewards cannot be transferred into shared storage.");}
    }
    /** Admin-inspection ownership transfer for /inv (OpenInv) and /enderchest inspect: a bound item taken OUT
     *  of someone else's storage during an authorized inspection immediately rebinds to the admin who took
     *  it; one placed IN rebinds to whoever's storage it landed in — so it behaves like its new holder's own
     *  item everywhere afterward (equip, drop, future inspection) instead of staying locked to whoever
     *  originally earned it. Only ever called from the two branches in inventory() already gated on either
     *  OpenInv's own permission system (topType==PLAYER) or isAdminInspecting()'s isAdmin() check — never a
     *  general bypass. topInventory-vs-bottomInventory is exactly what distinguishes "this click actually
     *  touches the target's storage" from "the admin is just rearranging their own inventory while the
     *  window happens to be open" — shift-click never touches the cursor, so only `current` matters there. */
    private void inspectionTransfer(Player admin,String targetId,String targetName,String surface,InventoryClickEvent event){
        ItemStack current=event.getCurrentItem(),cursor=event.getCursor();
        int topSize=event.getView().getTopInventory().getSize(),slot=event.getRawSlot();
        boolean clickedTop=slot>=0&&slot<topSize,adminChanged=false;
        String adminId=CoreUtil.id(admin);
        if(event.isShiftClick()){
            if(bound(current)&&rebind(current,clickedTop?adminId:targetId,admin,surface,targetName))adminChanged=true;
        }else if(clickedTop){
            if(bound(current)&&rebind(current,adminId,admin,surface,targetName))adminChanged=true;
            if(bound(cursor)&&rebind(cursor,targetId,admin,surface,targetName))event.setCursor(cursor);
        }
        if(adminChanged)event.setCurrentItem(current);
    }
    private boolean rebind(ItemStack item,String newOwnerId,Player admin,String surface,String targetName){
        String oldOwnerId=item.getItemMeta().getPersistentDataContainer().get(boundKey,PersistentDataType.STRING);
        if(newOwnerId.equals(oldOwnerId))return false;
        ItemMeta meta=item.getItemMeta();meta.getPersistentDataContainer().set(boundKey,PersistentDataType.STRING,newOwnerId);item.setItemMeta(meta);
        db.logAudit(admin.getName(),"BOUND_ITEM_TRANSFER","from="+oldOwnerId+" to="+newOwnerId+" item="+item.getType()+" via="+surface+" target="+targetName);
        return true;
    }

    void openCosmetics(Player player){
        List<Database.CosmeticRow> rows=db.cosmetics(CoreUtil.id(player));Inventory inv=plugin.getServer().createInventory(new CosmeticHolder(),45,Component.text("Ashen Cosmetics",NamedTextColor.DARK_PURPLE));String active=db.activeCosmetic(CoreUtil.id(player));
        int slot=10;for(Database.CosmeticRow row:rows){Material icon=cosmeticIcon(row.cosmetic());inv.setItem(slot++,CoreUtil.named(icon,CoreUtil.pretty(row.cosmetic()),List.of(row.cosmetic().equals(active)?"Equipped":"Click to equip.")));}
        inv.setItem(31,CoreUtil.named(Material.BARRIER,"Disable Cosmetic",List.of()));player.openInventory(inv);
    }
    private void effectTick(){
        long now=System.currentTimeMillis();
        for(Player player:plugin.getServer().getOnlinePlayers()){
            long haste=parseLong(db.state("shard_haste:"+CoreUtil.id(player)));if(haste>now&&!player.hasPotionEffect(PotionEffectType.HASTE))player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,240,1,false,false,false));
            String cosmetic=db.activeCosmetic(CoreUtil.id(player));if(cosmetic==null||plugin.adminTools().isHiddenFromPublic(player))continue;Location loc=player.getLocation().add(0,.1,0);
            switch(cosmetic){
                case"ashen_halo"->cosmeticParticle(player,Particle.SOUL_FIRE_FLAME,player.getEyeLocation().add(0,.45,0),4,.35,.08,.35,.01);
                case"emberstep"->cosmeticParticle(player,Particle.FLAME,loc,4,.25,.05,.25,.01);
                case"void_crown"->cosmeticParticle(player,Particle.PORTAL,player.getEyeLocation().add(0,.35,0),4,.3,.12,.3,.02);
                case"concord_wings"->cosmeticParticle(player,Particle.END_ROD,player.getLocation().add(0,1,0),4,.55,.45,.2,.01);
                default->{}
            }
        }
    }

    private void cosmeticParticle(Player source,Particle particle,Location location,int base,double offsetX,double offsetY,double offsetZ,double extra){
        for(Player viewer:source.getWorld().getPlayers()){
            if(viewer.getLocation().distanceSquared(location)>128*128)continue;
            double scale=plugin.settings().particleScale(viewer);int count=Math.max(1,(int)Math.round(base*scale));
            if(scale<=.2&&plugin.getServer().getCurrentTick()%20!=0)continue;
            viewer.spawnParticle(particle,location,count,offsetX,offsetY,offsetZ,extra);
        }
    }

    private Stock rotatingCosmetic(){List<String> rotation=config.getStringList("cosmetic-rotation");if(rotation.isEmpty())return null;String key=rotation.get(Math.floorMod(weekIndex(),rotation.size()));var section=config.getConfigurationSection("cosmetics."+key);if(section==null)return null;Material icon=Material.matchMaterial(section.getString("icon","AMETHYST_SHARD"));if(icon==null)icon=Material.AMETHYST_SHARD;return new Stock("cosmetic_"+key,icon,section.getString("name",CoreUtil.pretty(key)),section.getInt("price",120),1,"LIFETIME",Category.COSMETIC,true);}
    private Material cosmeticIcon(String cosmetic){var section=config.getConfigurationSection("cosmetics."+cosmetic);Material material=section==null?null:Material.matchMaterial(section.getString("icon"));return material==null?Material.AMETHYST_SHARD:material;}
    private String weekKey(){LocalDate date=LocalDate.now();WeekFields fields=WeekFields.ISO;return date.get(fields.weekBasedYear())+"-W"+String.format(Locale.ROOT,"%02d",date.get(fields.weekOfWeekBasedYear()));}
    private int weekIndex(){LocalDate date=LocalDate.now();return date.get(WeekFields.ISO.weekOfWeekBasedYear())+date.getYear()*53;}
    boolean selfTest(){Stock excavator=stock("fortune_excavator");ItemStack preview=excavator==null?null:displayItem(excavator);return config.getInt("earning.active-seconds-per-shard",0)==2700&&config.getInt("earning.afk-seconds-per-shard",0)==3600&&stock("sealed_omen")!=null&&preview!=null&&!preview.getItemMeta().hasDisplayName()&&stock().stream().noneMatch(row->row.icon()==Material.ELYTRA||row.icon()==Material.DRAGON_EGG);}
    private void shardMessage(Player player,String message){player.sendMessage(Component.text(message,NamedTextColor.RED));}
    private void consumeOne(Player player,EquipmentSlot slot,ItemStack item){ItemStack next=item.clone();next.setAmount(next.getAmount()-1);player.getInventory().setItem(slot,next.getAmount()<=0?null:next);}
    private long parseLong(String value){try{return value==null?0:Long.parseLong(value);}catch(NumberFormatException ignored){return 0;}}
}
