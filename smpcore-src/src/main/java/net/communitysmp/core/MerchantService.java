package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

final class MerchantService {
    enum Type { BOSS, EVENT, SHOP, AUCTION, BANKER }
    private static final class Holder implements InventoryHolder {final Type type;final Map<Integer,String> actions=new HashMap<>();Holder(Type type){this.type=type;}@Override public Inventory getInventory(){return null;}}
    private final SMPCore plugin;private final Database db;private final ShopService shop;private final AuctionService auctions;private final BossEventService bosses;private final BankService bank;
    private final NamespacedKey merchantIdKey,merchantTypeKey,scrollKey,eventKey,sigilKey,legendarySigilKey;

    MerchantService(SMPCore plugin,ShopService shop,AuctionService auctions,BossEventService bosses,BankService bank){this.plugin=plugin;this.db=plugin.db();this.shop=shop;this.auctions=auctions;this.bosses=bosses;this.bank=bank;merchantIdKey=new NamespacedKey(plugin,"merchant_id");merchantTypeKey=new NamespacedKey(plugin,"merchant_type");scrollKey=new NamespacedKey(plugin,"sealed_omen");eventKey=new NamespacedKey(plugin,"event_token");sigilKey=new NamespacedKey(plugin,"elite_sigil");legendarySigilKey=new NamespacedKey(plugin,"legendary_sigil");plugin.getServer().getScheduler().runTaskLater(plugin,this::restore,20L);}

    boolean command(Player admin,String[] args){
        if(args.length<3){CoreUtil.error(admin,"Usage: /ashfall merchant <spawn|remove> <boss|event|shop|auction|banker|nearest|id>");return true;}
        if(args[1].equalsIgnoreCase("spawn")){Type type=parse(args[2]);if(type==null){CoreUtil.error(admin,"Merchant type: boss, event, shop, auction, or banker.");return true;}Location location=targetLocation(admin);String id=type.name().toLowerCase(Locale.ROOT)+"-"+UUID.randomUUID().toString().substring(0,8);Villager villager=spawn(id,type,location);if(villager==null){CoreUtil.error(admin,"The merchant could not be spawned here.");return true;}CoreUtil.msg(admin,display(type)+" spawned permanently as "+id+".");return true;}
        if(args[1].equalsIgnoreCase("remove")){Database.MerchantRow row=args[2].equalsIgnoreCase("nearest")?nearest(admin.getLocation(),12):db.merchant(args[2]);if(row==null){CoreUtil.error(admin,"No matching merchant was found nearby.");return true;}remove(row);CoreUtil.msg(admin,"Merchant "+row.id()+" removed.");return true;}
        CoreUtil.error(admin,"Usage: /ashfall merchant <spawn|remove> ...");return true;
    }

    boolean interact(Player player,Entity entity){String type=entity.getPersistentDataContainer().get(merchantTypeKey,PersistentDataType.STRING);if(type==null)return false;Type parsed=parse(type);if(parsed==null)return false;if(parsed==Type.SHOP)shop.openPremium(player);else if(parsed==Type.AUCTION)auctions.openFromMerchant(player);else if(parsed==Type.BANKER)bank.open(player);else openOffers(player,parsed);return true;}
    boolean isMerchant(Entity entity){return entity.getPersistentDataContainer().has(merchantIdKey);}
    void click(InventoryClickEvent event){bank.click(event);if(!(event.getInventory().getHolder(false) instanceof Holder holder))return;event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player))return;String action=holder.actions.get(event.getRawSlot());if(action==null)return;if(!bank.allowNonessential(player,holder.type==Type.BOSS?"boss summons":"event purchases"))return;
        if(holder.type==Type.BOSS){
            if(action.equals("boss_sigil"))confirmSigilPurchase(player);
            else if(action.equals("boss_legendary_sigil"))confirmLegendarySigilPurchase(player);
            else if(action.equals("boss_pick_money"))openKindPicker(player,"MONEY");
            else if(action.equals("boss_pick_sigil"))openKindPicker(player,"SIGIL");
            else if(action.equals("boss_pick_legendary"))openKindPicker(player,"LEGENDARY");
            else if(action.equals("boss_back"))openOffers(player,Type.BOSS);
            else if(action.startsWith("boss_confirm_")){
                String rest=action.substring("boss_confirm_".length());int sep=rest.indexOf('_');
                String method=rest.substring(0,sep);BossEventService.WorldBossKind kind=BossEventService.WorldBossKind.valueOf(rest.substring(sep+1));
                switch(method){case"MONEY"->confirmChosenMoneyPurchase(player,kind);case"SIGIL"->confirmChosenSigilPurchase(player,kind);case"LEGENDARY"->confirmChosenLegendaryPurchase(player,kind);}
            }
            else{ItemStack item=bosses.createSummonScroll();double price=plugin.getConfig().getDouble("merchants.boss-scroll-price",1000000);confirmPurchase(player,item,price);}
        }else{BossEventService.EventTier tier=parseTier(action);BossEventService.EventType type=parseEvent(actionType(action));if(type!=null&&tier!=null){ItemStack item=eventToken(type,tier);confirmPurchase(player,item,eventPrice(tier,type));}}}
    void use(PlayerInteractEvent event){if(event.getItem()==null||!event.getAction().isRightClick())return;ItemMeta meta=event.getItem().getItemMeta();if(meta.getPersistentDataContainer().has(scrollKey)){event.setCancelled(true);if(bosses.useSummonScroll(event.getPlayer(),bosses.summonScrollKind(event.getItem())))consume(event.getPlayer(),event.getHand());return;}String token=meta.getPersistentDataContainer().get(eventKey,PersistentDataType.STRING);if(token==null)return;event.setCancelled(true);BossEventService.EventTier tier=parseTier(token);BossEventService.EventType type=parseEvent(actionType(token));if(type==null||tier==null){CoreUtil.error(event.getPlayer(),"This event seal has faded.");return;}String key="merchant_event_last_"+tier.name().toLowerCase(Locale.ROOT);long cooldown=plugin.getConfig().getLong("merchants.event-cooldowns-minutes."+tier.name().toLowerCase(Locale.ROOT),tier==BossEventService.EventTier.MICRO?30:tier==BossEventService.EventTier.MAJOR?180:1440)*60000L,last=parseLong(db.state(key));if(System.currentTimeMillis()-last<cooldown){CoreUtil.error(event.getPlayer(),"This event seal needs "+Math.max(1,(cooldown-(System.currentTimeMillis()-last)+59999)/60000)+" more minute(s) to settle.");return;}if(!bosses.startEvent(type,tier,null,BossEventService.Origin.PLAYER_SUMMONED)){CoreUtil.error(event.getPlayer(),"That event cannot begin while another event conflicts with it.");return;}db.state(key,Long.toString(System.currentTimeMillis()));consume(event.getPlayer(),event.getHand());}

    private void openOffers(Player player,Type type){Holder holder=new Holder(type);Inventory inv=plugin.getServer().createInventory(holder,type==Type.BOSS?27:54,Component.text(type==Type.BOSS?"Keeper of Omens":"Event Keeper",NamedTextColor.DARK_PURPLE));if(type==Type.BOSS){
            double price=plugin.getConfig().getDouble("merchants.boss-scroll-price",1000000),sigilPrice=plugin.getConfig().getDouble("merchants.boss-sigil-price",250000);int sigils=plugin.getConfig().getInt("merchants.boss-sigil-count",8);int legendarySigils=plugin.getConfig().getInt("merchants.boss-legendary-sigil-count",2);
            double chosenPrice=plugin.getConfig().getDouble("merchants.boss-chosen-price",price*1.5);int chosenSigils=sigils+sigils/2;double chosenSigilPrice=sigilPrice*1.5;int chosenLegendarySigils=legendarySigils+legendarySigils/2;
            inv.setItem(10,offerIcon(bosses.createSummonScroll(),price));holder.actions.put(10,"boss");
            ItemStack chosenMoney=offerIcon(bosses.createSummonScroll(),chosenPrice);addLore(chosenMoney,"Choose your encounter",NamedTextColor.GOLD);inv.setItem(11,chosenMoney);holder.actions.put(11,"boss_pick_money");
            ItemStack sigilOffer=offerIcon(bosses.createSummonScroll(),sigilPrice);addLore(sigilOffer,"+ "+sigils+" Elite Sigils",NamedTextColor.LIGHT_PURPLE);inv.setItem(13,sigilOffer);holder.actions.put(13,"boss_sigil");
            ItemStack chosenSigil=offerIcon(bosses.createSummonScroll(),chosenSigilPrice);addLore(chosenSigil,"Choose your encounter",NamedTextColor.GOLD);addLore(chosenSigil,"+ "+chosenSigils+" Elite Sigils",NamedTextColor.LIGHT_PURPLE);inv.setItem(14,chosenSigil);holder.actions.put(14,"boss_pick_sigil");
            ItemStack legendaryOffer=offerIcon(bosses.createSummonScroll(),0);addLore(legendaryOffer,legendarySigils+" Legendary Sigils • no coin",NamedTextColor.LIGHT_PURPLE);inv.setItem(16,legendaryOffer);holder.actions.put(16,"boss_legendary_sigil");
            ItemStack chosenLegendary=offerIcon(bosses.createSummonScroll(),0);addLore(chosenLegendary,"Choose your encounter",NamedTextColor.GOLD);addLore(chosenLegendary,chosenLegendarySigils+" Legendary Sigils • no coin",NamedTextColor.LIGHT_PURPLE);inv.setItem(17,chosenLegendary);holder.actions.put(17,"boss_pick_legendary");
        }else{addTierHeader(inv,9,"Quick Events","Frequent, short encounters");addOffer(inv,11,holder,BossEventService.EventTier.MICRO,BossEventService.EventType.RESOURCE_RUSH,Material.RAW_IRON);addOffer(inv,12,holder,BossEventService.EventTier.MICRO,BossEventService.EventType.TREASURE,Material.BARREL);addOffer(inv,13,holder,BossEventService.EventTier.MICRO,BossEventService.EventType.ELITE_HUNT,Material.IRON_SWORD);addTierHeader(inv,18,"Faction Events","Faction-scale competition");addOffer(inv,20,holder,BossEventService.EventTier.MAJOR,BossEventService.EventType.RESOURCE_RUSH,Material.RAW_GOLD);addOffer(inv,21,holder,BossEventService.EventTier.MAJOR,BossEventService.EventType.ELITE_HUNT,Material.WITHER_SKELETON_SKULL);addOffer(inv,22,holder,BossEventService.EventTier.MAJOR,BossEventService.EventType.KOTH,Material.GOLDEN_HELMET);addTierHeader(inv,27,"Premium Events","Long-cooldown server occasions");addOffer(inv,29,holder,BossEventService.EventTier.RARE,BossEventService.EventType.TREASURE,Material.ENDER_CHEST);addOffer(inv,30,holder,BossEventService.EventTier.RARE,BossEventService.EventType.KOTH,Material.DIAMOND_HELMET);addOffer(inv,31,holder,BossEventService.EventTier.RARE,BossEventService.EventType.WORLD_BOSS,Material.NETHER_STAR);}player.openInventory(inv);}
    private void openKindPicker(Player player,String method){
        Holder holder=new Holder(Type.BOSS);
        Inventory inv=plugin.getServer().createInventory(holder,27,Component.text("Choose Your Encounter",NamedTextColor.DARK_PURPLE));
        double price=plugin.getConfig().getDouble("merchants.boss-scroll-price",1000000);
        double chosenPrice=plugin.getConfig().getDouble("merchants.boss-chosen-price",price*1.5);
        double chosenSigilPrice=plugin.getConfig().getDouble("merchants.boss-sigil-price",250000)*1.5;
        int baseSigils=Math.max(1,plugin.getConfig().getInt("merchants.boss-sigil-count",8));int chosenSigils=baseSigils+baseSigils/2;
        int baseLegendarySigils=Math.max(1,plugin.getConfig().getInt("merchants.boss-legendary-sigil-count",2));int chosenLegendarySigils=baseLegendarySigils+baseLegendarySigils/2;
        int slot=11;
        for(BossEventService.WorldBossKind kind:BossEventService.WorldBossKind.values()){
            ItemStack icon;
            switch(method){
                case"SIGIL"->{icon=offerIcon(bosses.createSummonScroll(kind),chosenSigilPrice);addLore(icon,"+ "+chosenSigils+" Elite Sigils",NamedTextColor.LIGHT_PURPLE);}
                case"LEGENDARY"->{icon=offerIcon(bosses.createSummonScroll(kind),0);addLore(icon,chosenLegendarySigils+" Legendary Sigils • no coin",NamedTextColor.LIGHT_PURPLE);}
                default->icon=offerIcon(bosses.createSummonScroll(kind),chosenPrice);
            }
            inv.setItem(slot,icon);holder.actions.put(slot,"boss_confirm_"+method+"_"+kind.name());
            slot+=2;
        }
        inv.setItem(22,CoreUtil.named(Material.ARROW,"Back",List.of("Return to the Keeper's offerings.")));holder.actions.put(22,"boss_back");
        player.openInventory(inv);
    }
    private void addTierHeader(Inventory inventory,int slot,String title,String description){inventory.setItem(slot,CoreUtil.named(Material.PAPER,title,List.of(description)));}
    private void addOffer(Inventory inventory,int slot,Holder holder,BossEventService.EventTier tier,BossEventService.EventType type,Material icon){inventory.setItem(slot,offerIcon(eventToken(type,tier,icon),eventPrice(tier,type)));holder.actions.put(slot,tier.name()+":"+type.name());}
    private ItemStack offerIcon(ItemStack source,double price){ItemStack item=source.clone();ItemMeta meta=item.getItemMeta();List<Component> lore=meta.hasLore()?new ArrayList<>(meta.lore()):new ArrayList<>();lore.add(Component.empty());lore.add(Component.text(price>0?"Price: "+CoreUtil.money(price):"Price: free",NamedTextColor.GOLD));meta.lore(lore);item.setItemMeta(meta);return item;}
    private void addLore(ItemStack item,String text,NamedTextColor color){ItemMeta meta=item.getItemMeta();List<Component> lore=meta.hasLore()?new ArrayList<>(meta.lore()):new ArrayList<>();lore.add(Component.text(text,color));meta.lore(lore);item.setItemMeta(meta);}
    private ItemStack eventToken(BossEventService.EventType type,BossEventService.EventTier tier){return eventToken(type,tier,Material.PAPER);}
    private ItemStack eventToken(BossEventService.EventType type,BossEventService.EventTier tier,Material material){String display=switch(type){case RESOURCE_RUSH->"Prospector's Seal";case ELITE_HUNT->"Hunter's Seal";case TREASURE->"Wayfarer's Seal";case KOTH->"Conqueror's Seal";case WORLD_BOSS,HUNT->"Ashen Challenge";};String description=switch(type){case RESOURCE_RUSH->"Bonus money for ore mined server-wide.";case ELITE_HUNT->"Marks a powerful foe to hunt down.";case TREASURE->"Hides a cache; the finder keeps it.";case KOTH->"Factions fight to hold the center.";case WORLD_BOSS,HUNT->"Summons a major world boss encounter.";};ItemStack item=CoreUtil.named(material,display,List.of(description,"Right-click to begin."));ItemMeta meta=item.getItemMeta();meta.getPersistentDataContainer().set(eventKey,PersistentDataType.STRING,tier.name()+":"+type.name());item.setItemMeta(meta);return item;}
    private void confirmPurchase(Player player,ItemStack item,double price){String name=item.hasItemMeta()&&item.getItemMeta().hasDisplayName()?net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()):CoreUtil.pretty(item.getType().name());plugin.confirmations().request(player,SettingsService.ConfirmationKind.LUXURY,true,"Purchase "+name,List.of("Cost: "+CoreUtil.money(price)),()->purchase(player,item,price));}
    private void confirmSigilPurchase(Player player){int required=Math.max(1,plugin.getConfig().getInt("merchants.boss-sigil-count",8));double price=plugin.getConfig().getDouble("merchants.boss-sigil-price",250000);plugin.confirmations().request(player,SettingsService.ConfirmationKind.LUXURY,true,"Purchase Sealed Omen",List.of("Cost: "+CoreUtil.money(price)+" + "+required+" Elite Sigils"),()->purchaseWithSigils(player,bosses.createSummonScroll(),required,price));}
    private void confirmChosenMoneyPurchase(Player player,BossEventService.WorldBossKind kind){double price=plugin.getConfig().getDouble("merchants.boss-scroll-price",1000000);double chosenPrice=plugin.getConfig().getDouble("merchants.boss-chosen-price",price*1.5);plugin.confirmations().request(player,SettingsService.ConfirmationKind.LUXURY,true,"Purchase Chosen Sealed Omen",List.of("Cost: "+CoreUtil.money(chosenPrice)),()->purchase(player,bosses.createSummonScroll(kind),chosenPrice));}
    private void confirmChosenSigilPurchase(Player player,BossEventService.WorldBossKind kind){int base=Math.max(1,plugin.getConfig().getInt("merchants.boss-sigil-count",8));int required=base+base/2;double price=plugin.getConfig().getDouble("merchants.boss-sigil-price",250000)*1.5;plugin.confirmations().request(player,SettingsService.ConfirmationKind.LUXURY,true,"Purchase Chosen Sealed Omen",List.of("Cost: "+CoreUtil.money(price)+" + "+required+" Elite Sigils"),()->purchaseWithSigils(player,bosses.createSummonScroll(kind),required,price));}

    /** Sealed Omens are the one purchase that is itself a server-wide event -- somebody just bought the
     *  right to drop a world boss on the map, and everyone benefits from (or has to survive) it. Announced
     *  only for scrolls, deliberately NOT for purchases generally, and only once per scroll from the single
     *  place every scroll purchase path ends up. */
    private void announceScrollPurchase(Player player,BossEventService.WorldBossKind kind,String how){
        net.kyori.adventure.text.Component name=net.kyori.adventure.text.Component.text(plugin.nicknames().displayName(player),net.kyori.adventure.text.format.NamedTextColor.GOLD,net.kyori.adventure.text.format.TextDecoration.BOLD);
        net.kyori.adventure.text.Component what=kind==null
                ?net.kyori.adventure.text.Component.text("a Sealed Omen",net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE,net.kyori.adventure.text.format.TextDecoration.ITALIC)
                :net.kyori.adventure.text.Component.text("the omen of "+plugin.bosses().displayName(kind),net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE,net.kyori.adventure.text.format.TextDecoration.ITALIC);
        net.kyori.adventure.text.Component line=net.kyori.adventure.text.Component.text("☠ ",net.kyori.adventure.text.format.NamedTextColor.DARK_RED)
                .append(name)
                .append(net.kyori.adventure.text.Component.text(" has claimed ",net.kyori.adventure.text.format.NamedTextColor.GRAY))
                .append(what)
                .append(net.kyori.adventure.text.Component.text(" "+how+".",net.kyori.adventure.text.format.NamedTextColor.GRAY));
        net.kyori.adventure.text.Component sub=net.kyori.adventure.text.Component.text("   The seal is theirs to break — when it does, everyone will know.",net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY,net.kyori.adventure.text.format.TextDecoration.ITALIC);
        for(Player online:plugin.getServer().getOnlinePlayers()){
            if(!plugin.settings().bossNotifications(online))continue;
            online.sendMessage(line);online.sendMessage(sub);
            online.playSound(online.getLocation(),Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,.7f,.6f);
        }
    }
    private void purchase(Player player,ItemStack item,double price){if(!ShopService.canFit(player,item)){CoreUtil.error(player,"Make enough inventory space first.");return;}if(!bank.payServer(player,price,"MERCHANT_PURCHASE","MERCHANT_"+item.getType())){CoreUtil.error(player,"You need "+CoreUtil.money(price)+".");return;}CoreUtil.give(player,item);player.playSound(player.getLocation(),Sound.BLOCK_AMETHYST_BLOCK_CHIME,.8f,.75f);CoreUtil.msg(player,"Purchased for "+CoreUtil.money(price)+".");if(isSummonScroll(item))announceScrollPurchase(player,plugin.bosses().summonScrollKind(item),"from the wandering merchant");}
    private void purchaseWithSigils(Player player,ItemStack scroll,int required,double price){if(!ShopService.canFit(player,scroll)){CoreUtil.error(player,"Make enough inventory space first.");return;}if(countSigils(player)<required){CoreUtil.error(player,"You need "+required+" Elite Sigils.");return;}if(!bank.payServer(player,price,"MERCHANT_PURCHASE","BOSS_SCROLL_SIGILS")){CoreUtil.error(player,"You need "+CoreUtil.money(price)+".");return;}removeSigils(player,required);CoreUtil.give(player,scroll);announceScrollPurchase(player,plugin.bosses().summonScrollKind(scroll),"with Elite Sigils");player.playSound(player.getLocation(),Sound.BLOCK_AMETHYST_BLOCK_CHIME,.8f,.65f);CoreUtil.msg(player,"The Keeper accepted "+required+" sigils and "+CoreUtil.money(price)+".");}
    private boolean isSummonScroll(ItemStack item){
        return item!=null&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin,"sealed_omen"),org.bukkit.persistence.PersistentDataType.BYTE);
    }
    private int countSigils(Player player){int count=0;for(ItemStack item:player.getInventory().getStorageContents())if(isSigil(item))count+=item.getAmount();return count;}
    private void removeSigils(Player player,int amount){for(ItemStack item:player.getInventory().getStorageContents()){if(amount<=0)break;if(!isSigil(item))continue;int take=Math.min(amount,item.getAmount());item.setAmount(item.getAmount()-take);amount-=take;}}
    private boolean isSigil(ItemStack item){if(item==null||item.getType().isAir()||!item.hasItemMeta())return false;ItemMeta meta=item.getItemMeta();if(meta.getPersistentDataContainer().has(sigilKey))return true;return meta.hasDisplayName()&&net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName()).equalsIgnoreCase("Elite Sigil");}
    private void confirmLegendarySigilPurchase(Player player){int required=Math.max(1,plugin.getConfig().getInt("merchants.boss-legendary-sigil-count",2));plugin.confirmations().request(player,SettingsService.ConfirmationKind.LUXURY,true,"Purchase Sealed Omen",List.of("Cost: "+required+" Legendary Sigils"),()->purchaseWithLegendarySigils(player,bosses.createSummonScroll(),required));}
    private void confirmChosenLegendaryPurchase(Player player,BossEventService.WorldBossKind kind){int base=Math.max(1,plugin.getConfig().getInt("merchants.boss-legendary-sigil-count",2));int required=base+base/2;plugin.confirmations().request(player,SettingsService.ConfirmationKind.LUXURY,true,"Purchase Chosen Sealed Omen",List.of("Cost: "+required+" Legendary Sigils"),()->purchaseWithLegendarySigils(player,bosses.createSummonScroll(kind),required));}
    private void purchaseWithLegendarySigils(Player player,ItemStack scroll,int required){if(!ShopService.canFit(player,scroll)){CoreUtil.error(player,"Make enough inventory space first.");return;}if(countLegendarySigils(player)<required){CoreUtil.error(player,"You need "+required+" Legendary Sigils.");return;}removeLegendarySigils(player,required);CoreUtil.give(player,scroll);announceScrollPurchase(player,plugin.bosses().summonScrollKind(scroll),"with Legendary Sigils");player.playSound(player.getLocation(),Sound.BLOCK_AMETHYST_BLOCK_CHIME,.8f,.55f);CoreUtil.msg(player,"The Keeper accepted "+required+" legendary sigils.");}
    private int countLegendarySigils(Player player){int count=0;for(ItemStack item:player.getInventory().getStorageContents())if(isLegendarySigil(item))count+=item.getAmount();return count;}
    private void removeLegendarySigils(Player player,int amount){for(ItemStack item:player.getInventory().getStorageContents()){if(amount<=0)break;if(!isLegendarySigil(item))continue;int take=Math.min(amount,item.getAmount());item.setAmount(item.getAmount()-take);amount-=take;}}
    private boolean isLegendarySigil(ItemStack item){return item!=null&&!item.getType().isAir()&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().has(legendarySigilKey);}
    private void consume(Player player,org.bukkit.inventory.EquipmentSlot hand){ItemStack held=player.getInventory().getItem(hand);held.setAmount(held.getAmount()-1);}

    private Villager spawn(String id,Type type,Location location){if(location.getWorld()==null)return null;Villager villager=location.getWorld().spawn(location,Villager.class,CreatureSpawnEvent.SpawnReason.COMMAND,v->{v.customName(Component.text(display(type),NamedTextColor.GOLD));v.setCustomNameVisible(true);v.setAI(false);v.setInvulnerable(true);v.setCollidable(false);v.setPersistent(true);v.setSilent(true);v.setProfession(profession(type));v.getPersistentDataContainer().set(merchantIdKey,PersistentDataType.STRING,id);v.getPersistentDataContainer().set(merchantTypeKey,PersistentDataType.STRING,type.name());});db.saveMerchant(new Database.MerchantRow(id,type.name(),location.getWorld().getName(),location.getX(),location.getY(),location.getZ(),location.getYaw(),location.getPitch(),villager.getUniqueId().toString()));return villager;}
    private void restore(){for(Database.MerchantRow row:db.merchants()){World world=plugin.getServer().getWorld(row.world());if(world==null)continue;world.getChunkAt(((int)row.x())>>4,((int)row.z())>>4).load();Entity existing=null;try{existing=row.entityId()==null?null:plugin.getServer().getEntity(UUID.fromString(row.entityId()));}catch(Exception ignored){}if(existing instanceof Villager villager&&villager.isValid()){protect(villager,row.id(),parse(row.type()));continue;}spawn(row.id(),parse(row.type()),new Location(world,row.x(),row.y(),row.z(),row.yaw(),row.pitch()));}}
    private void protect(Villager villager,String id,Type type){if(type==null)return;villager.setAI(false);villager.setInvulnerable(true);villager.setCollidable(false);villager.setPersistent(true);villager.setSilent(true);villager.getPersistentDataContainer().set(merchantIdKey,PersistentDataType.STRING,id);villager.getPersistentDataContainer().set(merchantTypeKey,PersistentDataType.STRING,type.name());}
    private void remove(Database.MerchantRow row){if(row.entityId()!=null)try{Entity entity=plugin.getServer().getEntity(UUID.fromString(row.entityId()));if(entity!=null)entity.remove();}catch(Exception ignored){}db.deleteMerchant(row.id());}
    private Database.MerchantRow nearest(Location location,double radius){return db.merchants().stream().filter(row->row.world().equals(location.getWorld().getName())).min(Comparator.comparingDouble(row->distanceSquared(location,row))).filter(row->distanceSquared(location,row)<=radius*radius).orElse(null);}
    private double distanceSquared(Location location,Database.MerchantRow row){double dx=location.getX()-row.x(),dy=location.getY()-row.y(),dz=location.getZ()-row.z();return dx*dx+dy*dy+dz*dz;}
    private Location targetLocation(Player player){org.bukkit.block.Block target=player.getTargetBlockExact(6);return target==null?player.getLocation():target.getLocation().add(.5,1,.5);}
    private Type parse(String value){try{return Type.valueOf(value.toUpperCase(Locale.ROOT));}catch(Exception e){return null;}}
    private BossEventService.EventTier parseTier(String value){try{String token=value.contains(":")?value.substring(0,value.indexOf(':')):"MAJOR";return BossEventService.EventTier.valueOf(token.toUpperCase(Locale.ROOT));}catch(Exception e){return null;}}
    private String actionType(String value){return value.contains(":")?value.substring(value.indexOf(':')+1):value;}
    private BossEventService.EventType parseEvent(String value){return switch(value.toLowerCase(Locale.ROOT)){case"resource","resource_rush"->BossEventService.EventType.RESOURCE_RUSH;case"elitehunt","elite_hunt"->BossEventService.EventType.ELITE_HUNT;case"treasure"->BossEventService.EventType.TREASURE;case"koth"->BossEventService.EventType.KOTH;case"worldboss","world_boss","hunt"->BossEventService.EventType.WORLD_BOSS;default->null;};}
    private Villager.Profession profession(Type type){return switch(type){case BOSS->Villager.Profession.CLERIC;case EVENT->Villager.Profession.CARTOGRAPHER;case SHOP->Villager.Profession.TOOLSMITH;case AUCTION->Villager.Profession.LIBRARIAN;case BANKER->Villager.Profession.CLERIC;};}
    private String display(Type type){return switch(type){case BOSS->"Keeper of Omens";case EVENT->"Event Keeper";case SHOP->"Concord Merchant";case AUCTION->"Ashfall Auctioneer";case BANKER->"Central Banker";};}
    private double eventPrice(BossEventService.EventTier tier,BossEventService.EventType type){String name=switch(type){case RESOURCE_RUSH->"resource";case ELITE_HUNT->"elitehunt";case TREASURE->"treasure";case KOTH->"koth";case WORLD_BOSS,HUNT->"worldboss";};double fallback=switch(tier){case MICRO->switch(type){case RESOURCE_RUSH->10_000;case TREASURE->8_000;case ELITE_HUNT->15_000;default->25_000;};case MAJOR->switch(type){case RESOURCE_RUSH->100_000;case TREASURE->90_000;case ELITE_HUNT->120_000;case KOTH->150_000;default->250_000;};case RARE->switch(type){case TREASURE->750_000;case KOTH->1_000_000;case WORLD_BOSS,HUNT->1_000_000;default->600_000;};};return plugin.getConfig().getDouble("merchants.events."+tier.name().toLowerCase(Locale.ROOT)+"."+name+".price",fallback);}
    private double fallbackEventPrice(BossEventService.EventType type){return switch(type){case RESOURCE_RUSH->5000;case ELITE_HUNT->4000;case TREASURE->2000;case KOTH->2500;case WORLD_BOSS,HUNT->18000;};}
    private long parseLong(String value){try{return value==null?0:Long.parseLong(value);}catch(NumberFormatException e){return 0;}}
}
