package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.time.LocalDate;
import java.util.*;

final class ShopService {
    record Price(double buy,double sell,int dailyFull,int dailyLimit,double reduced,String display,boolean luxury,String category){}
    static final class SellHolder implements InventoryHolder {
        final double multiplier; boolean finalized;
        SellHolder(double multiplier){this.multiplier=multiplier;} @Override public Inventory getInventory(){return null;}
    }
    private record SaleQuote(double earned,int sellable,int unsupported){}

    private static final int INPUT_END=45;
    private final SMPCore plugin; private final Database db; private final NamespacedKey luxuryKey,capsuleKey;
    private final LinkedHashMap<Material,Price> prices=new LinkedHashMap<>();

    ShopService(SMPCore plugin){this.plugin=plugin;db=plugin.db();luxuryKey=new NamespacedKey(plugin,"shop_luxury");capsuleKey=new NamespacedKey(plugin,"villager_capsule");reload();}

    /** How much more the shop charges than it pays. Fixed at 5x. */
    double buyMultiple(){return Math.max(1,plugin.getConfig().getDouble("shop.buy-multiple",5.0));}
    void reload(){prices.clear();YamlConfiguration yaml=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"shop.yml"));loadSection(yaml.getConfigurationSection("items"),false);loadSection(yaml.getConfigurationSection("luxuries"),true);}
    private void loadSection(ConfigurationSection section,boolean luxury){
        if(section==null)return;
        for(String key:section.getKeys(false)){
            Material material=Material.matchMaterial(key);if(material==null)continue;
            double sell=luxury?-1:section.getDouble(key+".sell",-1);
            /** Commodity buy price is DERIVED, never read from the file: exactly the configured multiple of
             *  what the shop pays for that same item. Deriving it means the two can never drift apart, and
             *  an edit to sell automatically carries to buy. Luxuries have no sell price, so they keep their
             *  own configured buy. */
            double buy=luxury?section.getDouble(key+".buy",-1):Math.round(sell*buyMultiple()*100)/100.0;
            if(buy<=0||(!luxury&&sell<=0)||(luxury&&sell>=buy))continue;
            int full=section.getInt(key+".daily-full",plugin.getConfig().getInt("shop.sell-control.default-full-quantity",256));
            int limit=Integer.MAX_VALUE;
            double reduced=plugin.getConfig().getDouble("shop.sell-control.reduced-multiplier",.5);
            String display=section.getString(key+".display",CoreUtil.pretty(key));
            /** Authored per item rather than guessed from the material name. The old heuristic put slime
             *  balls under redstone and dumped anything it did not recognise into a catch-all, which is no
             *  basis for the shop's default grouping. */
            String category=section.getString(key+".category",luxury?"LUXURY":"UTILITY");
            prices.put(material,new Price(buy,sell,Math.max(0,full),Math.max(full,limit),Math.max(0,Math.min(1,reduced)),display,luxury,category));
        }
    }

    /** What the shop currently owns, and can therefore sell. Luxuries are minted by the server rather than
     *  bought from players, so they are not stock-limited. */
    boolean stockLimited(Material material){Price price=prices.get(material);return price!=null&&!price.luxury();}
    int stock(Material material){return stockLimited(material)?db.shopStock(material.name()):Integer.MAX_VALUE;}
    boolean inStock(Material material,int amount){return !stockLimited(material)||db.shopStock(material.name())>=amount;}
    java.util.Map<String,Integer> allStock(){return db.shopStockAll();}
    /** The authored category for a material, or null when it is not a shop item (auction listings). */
    String categoryOf(Material material){Price price=prices.get(material);return price==null?null:price.category();}
    /** Deliberate display order, not alphabetical: it runs from what a new player gathers first to what
     *  needs a mob farm, so the shop reads as a progression rather than an index. */
    private static final java.util.List<String> CATEGORY_ORDER=java.util.List.of("WOOD","MINING","FARMING","ANIMALS","MOB_DROPS");
    static int categoryRank(String category){int index=CATEGORY_ORDER.indexOf(category);return index<0?CATEGORY_ORDER.size():index;}
    java.util.List<String> categories(boolean luxury){
        java.util.List<String> out=new java.util.ArrayList<>();
        for(Price price:prices.values())if(price.luxury()==luxury&&!out.contains(price.category()))out.add(price.category());
        out.sort(java.util.Comparator.comparingInt(ShopService::categoryRank).thenComparing(java.util.Comparator.naturalOrder()));
        return out;
    }

    void open(Player p){plugin.marketplace().open(p,MarketplaceService.Section.SHOP);}
    void open(Player p,boolean luxury){plugin.marketplace().open(p,luxury?MarketplaceService.Section.LUXURY:MarketplaceService.Section.SHOP);}
    void openPremium(Player p){plugin.marketplace().openPremium(p);}

    private ItemStack luxuryItem(Material material,Price price){
        ItemStack item=new ItemStack(material);if(material==Material.WIND_CHARGE)return item;ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(price.display(),NamedTextColor.LIGHT_PURPLE));meta.getPersistentDataContainer().set(luxuryKey,PersistentDataType.BYTE,(byte)1);
        if(material==Material.TRIAL_KEY){meta.getPersistentDataContainer().set(capsuleKey,PersistentDataType.STRING,"DISPOSABLE");meta.setMaxStackSize(1);meta.lore(List.of(Component.text("Captures one villager.",NamedTextColor.GRAY)));}
        else if(material==Material.OMINOUS_TRIAL_KEY){meta.getPersistentDataContainer().set(capsuleKey,PersistentDataType.STRING,"REUSABLE");meta.setMaxStackSize(1);meta.lore(List.of(Component.text("Reusable villager transport.",NamedTextColor.GRAY)));}
        item.setItemMeta(meta);return item;
    }
    /** IRON_GOLEM_SPAWN_EGG is only a shop-facing ICON for the Iron Golem Spawner -- what the player
     *  actually receives is a real, tagged SMPCore spawner, so it places, stacks and tracks like any other.
     *  Handled here because purchaseItem is the single delivery point every shop purchase passes through. */
    private ItemStack purchaseItem(Material material,Price price,int amount){
        if(material==Material.IRON_GOLEM_SPAWN_EGG){ItemStack spawner=plugin.spawners().purchasedSpawner(org.bukkit.entity.EntityType.IRON_GOLEM);spawner.setAmount(Math.max(1,amount));return spawner;}
        ItemStack item=price.luxury()?luxuryItem(material,price):new ItemStack(material);item.setAmount(amount);return item;}

    void click(InventoryClickEvent event){if(event.getInventory().getHolder(false) instanceof SellHolder holder)sellClick(event,holder);}

    void openSellBasket(Player p,boolean premium){
        double multiplier=premium?plugin.getConfig().getDouble("merchants.shop.sell-multiplier",1.075):1;
        SellHolder holder=new SellHolder(multiplier);Inventory inv=plugin.getServer().createInventory(holder,54,Component.text("Sell Basket",NamedTextColor.DARK_GREEN));
        inv.setItem(47,CoreUtil.named(Material.BARRIER,"Cancel",List.of("Return every item.")));
        inv.setItem(49,totalIcon(new SaleQuote(0,0,0)));
        inv.setItem(51,CoreUtil.named(Material.LIME_CONCRETE,"Confirm Sale",List.of("Only supported ordinary items will be sold.")));
        p.openInventory(inv);
    }

    void requestQuickSell(Player p,boolean premium,Runnable returnToShop){
        double multiplier=premium?plugin.getConfig().getDouble("merchants.shop.sell-multiplier",1.075):1;
        SaleQuote quote=inventoryQuote(p,multiplier);
        if(quote.sellable()<=0){CoreUtil.error(p,"No supported ordinary items were found in your inventory.");plugin.settings().marketSound(p,"failed");return;}
        plugin.confirmations().request(p,SettingsService.ConfirmationKind.SHOP,false,"Quick Sell Inventory",
                List.of(quote.sellable()+" item"+(quote.sellable()==1?"":"s"),"Total: "+CoreUtil.money(quote.earned())),
                ()->{quickSell(p,multiplier);returnToShop.run();},returnToShop);
    }

    private void sellClick(InventoryClickEvent e,SellHolder holder){
        if(!(e.getWhoClicked() instanceof Player p))return;int raw=e.getRawSlot();
        if(raw>=0&&raw<INPUT_END){plugin.getServer().getScheduler().runTask(plugin,()->refreshBasket(e.getInventory(),p));return;}
        if(raw>=INPUT_END&&raw<e.getInventory().getSize()){
            e.setCancelled(true);
            if(raw==47){holder.finalized=true;returnItems(p,e.getInventory());p.closeInventory();}
            else if(raw==51)confirmBasket(p,e.getInventory(),holder);
            return;
        }
        if(e.isShiftClick()&&e.getCurrentItem()!=null&&!e.getCurrentItem().getType().isAir()){
            e.setCancelled(true);ItemStack moving=e.getCurrentItem().clone();int remaining=moveIntoInputs(e.getInventory(),moving);int moved=moving.getAmount()-remaining;e.getCurrentItem().setAmount(e.getCurrentItem().getAmount()-moved);plugin.getServer().getScheduler().runTask(plugin,()->refreshBasket(e.getInventory(),p));
        }
    }

    void drag(InventoryDragEvent e){if(e.getInventory().getHolder(false) instanceof SellHolder&&e.getRawSlots().stream().anyMatch(slot->slot>=INPUT_END&&slot<54))e.setCancelled(true);if(e.getInventory().getHolder(false) instanceof SellHolder&&e.getWhoClicked() instanceof Player p)plugin.getServer().getScheduler().runTask(plugin,()->refreshBasket(e.getInventory(),p));}
    void close(InventoryCloseEvent e){if(e.getInventory().getHolder(false) instanceof SellHolder holder&&!holder.finalized&&e.getPlayer() instanceof Player p){holder.finalized=true;returnItems(p,e.getInventory());}}

    private void refreshBasket(Inventory inv,Player p){if(!(inv.getHolder(false) instanceof SellHolder holder)||holder.finalized)return;inv.setItem(49,totalIcon(quote(inv,p,holder.multiplier)));}
    private ItemStack totalIcon(SaleQuote quote){List<String> lore=new ArrayList<>();lore.add("Total: "+CoreUtil.money(quote.earned()));lore.add(quote.sellable()+" item"+(quote.sellable()==1?"":"s")+" accepted");if(quote.unsupported()>0)lore.add(quote.unsupported()+" item"+(quote.unsupported()==1?"":"s")+" will be returned");return CoreUtil.named(Material.GOLD_INGOT,"Sale Total",lore);}
    private SaleQuote quote(Inventory inv,Player p,double multiplier){
        Map<Material,Integer> counts=new LinkedHashMap<>();int unsupported=0;
        for(int slot=0;slot<INPUT_END;slot++){ItemStack item=inv.getItem(slot);if(item==null||item.getType().isAir())continue;Price price=prices.get(item.getType());if(price==null||price.luxury()||price.sell()<0||!item.isSimilar(new ItemStack(item.getType()))){unsupported+=item.getAmount();continue;}counts.merge(item.getType(),item.getAmount(),Integer::sum);}
        String day=LocalDate.now().toString();double earned=0;int sellable=0;
        for(var entry:counts.entrySet()){Price price=prices.get(entry.getKey());int sold=db.dailySold(CoreUtil.id(p),entry.getKey().name(),day),amount=entry.getValue();int full=Math.min(amount,Math.max(0,price.dailyFull()-sold)),reduced=amount-full;earned+=(full*price.sell()+reduced*price.sell()*price.reduced())*multiplier;sellable+=amount;}
        return new SaleQuote(Math.round(earned*100)/100.0,sellable,unsupported);
    }

    private SaleQuote inventoryQuote(Player p,double multiplier){
        Map<Material,Integer> counts=inventorySellable(p);String day=LocalDate.now().toString();double earned=0;int sellable=0;
        for(var entry:counts.entrySet()){Price price=prices.get(entry.getKey());int amount=entry.getValue(),sold=db.dailySold(CoreUtil.id(p),entry.getKey().name(),day);int full=Math.min(amount,Math.max(0,price.dailyFull()-sold)),reduced=amount-full;earned+=(full*price.sell()+reduced*price.sell()*price.reduced())*multiplier;sellable+=amount;}
        return new SaleQuote(Math.round(earned*100)/100.0,sellable,0);
    }

    private Map<Material,Integer> inventorySellable(Player p){return sellableFrom(p.getInventory().getStorageContents());}
    private Map<Material,Integer> sellableFrom(ItemStack[] contents){
        Map<Material,Integer> counts=new LinkedHashMap<>();
        for(ItemStack item:contents){
            if(item==null||item.getType().isAir()||!item.isSimilar(new ItemStack(item.getType())))continue;
            Price price=prices.get(item.getType());if(price!=null&&!price.luxury()&&price.sell()>=0)counts.merge(item.getType(),item.getAmount(),Integer::sum);
        }
        return counts;
    }
    private SaleQuote containerQuote(Inventory inv,Player p){
        Map<Material,Integer> counts=sellableFrom(inv.getContents());String day=LocalDate.now().toString();double earned=0;int sellable=0;
        for(var entry:counts.entrySet()){Price price=prices.get(entry.getKey());int amount=entry.getValue(),sold=db.dailySold(CoreUtil.id(p),entry.getKey().name(),day);int full=Math.min(amount,Math.max(0,price.dailyFull()-sold)),reduced=amount-full;earned+=full*price.sell()+reduced*price.sell()*price.reduced();sellable+=amount;}
        return new SaleQuote(Math.round(earned*100)/100.0,sellable,0);
    }
    boolean sellAllChest(Player player){
        org.bukkit.util.RayTraceResult ray=player.rayTraceBlocks(6);
        if(ray==null||ray.getHitBlock()==null||!(ray.getHitBlock().getState() instanceof org.bukkit.block.Container container)){CoreUtil.error(player,"Look at a chest, barrel, or shulker box within 6 blocks.");return true;}
        Location location=container.getBlock().getLocation();
        if(plugin.spawnClaims().contains(location)&&!plugin.isAdmin(player)){CoreUtil.error(player,"This storage is protected by spawn.");return true;}
        FactionService.Claim claim=plugin.factions().claimAt(location);
        if(claim!=null&&!plugin.factions().isMember(player,claim.faction())&&!plugin.privileged(player)){CoreUtil.error(player,"This storage is protected by "+claim.faction().name()+".");return true;}
        SaleQuote preview=containerQuote(container.getInventory(),player);
        if(preview.sellable()<=0){CoreUtil.error(player,"No supported ordinary items were found in that container.");plugin.settings().marketSound(player,"failed");return true;}
        plugin.confirmations().request(player,SettingsService.ConfirmationKind.SHOP,false,"Sell Container Contents",
                List.of(preview.sellable()+" item"+(preview.sellable()==1?"":"s"),"Estimated total: "+CoreUtil.money(preview.earned())),
                ()->sellContainerNow(player,location),()->{});
        return true;
    }
    private void sellContainerNow(Player player,Location location){
        if(!(location.getBlock().getState() instanceof org.bukkit.block.Container container)){CoreUtil.error(player,"That container is no longer there.");plugin.settings().marketSound(player,"failed");return;}
        if(plugin.spawnClaims().contains(location)&&!plugin.isAdmin(player)){CoreUtil.error(player,"This storage is protected by spawn.");return;}
        FactionService.Claim claim=plugin.factions().claimAt(location);
        if(claim!=null&&!plugin.factions().isMember(player,claim.faction())&&!plugin.privileged(player)){CoreUtil.error(player,"This storage is protected by "+claim.faction().name()+".");return;}
        Inventory inv=container.getInventory();
        Map<Material,Integer> counts=sellableFrom(inv.getContents());
        SaleQuote quote=containerQuote(inv,player);
        if(quote.sellable()<=0){CoreUtil.error(player,"Nothing left to sell in that container.");plugin.settings().marketSound(player,"failed");return;}
        if(!plugin.bank().payShopSeller(player,quote.earned(),"CONTAINER_SELLALL")){CoreUtil.error(player,"The Central Bank treasury cannot cover this sale yet.");plugin.settings().marketSound(player,"failed");return;}
        String day=LocalDate.now().toString();
        for(var entry:counts.entrySet()){
            Material material=entry.getKey();Price price=prices.get(material);int amount=entry.getValue(),sold=db.dailySold(CoreUtil.id(player),material.name(),day);int full=Math.min(amount,Math.max(0,price.dailyFull()-sold)),reduced=amount-full;double earned=Math.round((full*price.sell()+reduced*price.sell()*price.reduced())*100)/100.0;
            int remaining=amount;for(int slot=0;slot<inv.getSize()&&remaining>0;slot++){ItemStack item=inv.getItem(slot);if(item==null||item.getType()!=material||!item.isSimilar(new ItemStack(material)))continue;int take=Math.min(remaining,item.getAmount());if(take>=item.getAmount())inv.setItem(slot,null);else{item.setAmount(item.getAmount()-take);inv.setItem(slot,item);}remaining-=take;}
            creditStock(player,material,amount-remaining,amount);
            db.recordSale(CoreUtil.id(player),material.name(),day,amount,earned);db.recordEconomy(CoreUtil.id(player),"SHOP_SELL",earned,material.name());
        }
        CoreUtil.msg(player,"Sold "+quote.sellable()+" item"+(quote.sellable()==1?"":"s")+" from the container for "+CoreUtil.money(quote.earned())+".");plugin.settings().marketSound(player,"sale");
    }

    /** Credits the shop with what a sale actually removed from the player, not what was quoted. Every sell
     *  path funnels through here: anything the shop pays for it must also come to own, or items would leave
     *  the world with nothing to resell and the shop could never restock itself. */
    private void creditStock(Player p,Material material,int removed,int quoted){
        if(removed>0)db.shopStockAdd(material.name(),removed);
        if(removed!=quoted)plugin.getLogger().warning("[Shop] "+p.getName()+" sale of "+material.name()+" removed "+removed+" of "+quoted+"; stock credited for the removed amount only.");
    }
    private void quickSell(Player p,double multiplier){
        Map<Material,Integer> counts=inventorySellable(p);SaleQuote quote=inventoryQuote(p,multiplier);
        if(quote.sellable()<=0){CoreUtil.error(p,"No supported ordinary items were found in your inventory.");plugin.settings().marketSound(p,"failed");return;}
        if(!plugin.bank().payShopSeller(p,quote.earned(),"QUICK_SELL")){CoreUtil.error(p,"The Central Bank treasury cannot cover this sale yet.");plugin.settings().marketSound(p,"failed");return;}
        String day=LocalDate.now().toString();
        for(var entry:counts.entrySet()){
            Material material=entry.getKey();Price price=prices.get(material);int amount=entry.getValue(),sold=db.dailySold(CoreUtil.id(p),material.name(),day);int full=Math.min(amount,Math.max(0,price.dailyFull()-sold)),reduced=amount-full;double earned=Math.round((full*price.sell()+reduced*price.sell()*price.reduced())*multiplier*100)/100.0;
            int remaining=amount;for(ItemStack item:p.getInventory().getStorageContents())if(item!=null&&item.getType()==material&&item.isSimilar(new ItemStack(material))){int take=Math.min(remaining,item.getAmount());item.setAmount(item.getAmount()-take);remaining-=take;if(remaining==0)break;}
            creditStock(p,material,amount-remaining,amount);
            db.recordSale(CoreUtil.id(p),material.name(),day,amount,earned);db.recordEconomy(CoreUtil.id(p),"SHOP_SELL",earned,material.name());
        }
        CoreUtil.msg(p,"Quick-sold "+quote.sellable()+" item"+(quote.sellable()==1?"":"s")+" for "+CoreUtil.money(quote.earned())+".");plugin.settings().marketSound(p,"sale");
    }

    private void confirmBasket(Player p,Inventory inv,SellHolder holder){
        SaleQuote quote=quote(inv,p,holder.multiplier);if(quote.sellable()<=0){CoreUtil.error(p,"No supported items are currently sellable.");plugin.settings().marketSound(p,"failed");refreshBasket(inv,p);return;}
        String day=LocalDate.now().toString();Map<Material,Integer> remaining=new HashMap<>(),soldAmounts=new HashMap<>();Map<Material,Double> earnings=new HashMap<>();
        for(int slot=0;slot<INPUT_END;slot++){ItemStack item=inv.getItem(slot);if(item==null||item.getType().isAir()||!item.isSimilar(new ItemStack(item.getType())))continue;Price price=prices.get(item.getType());if(price!=null&&!price.luxury()&&price.sell()>=0)remaining.merge(item.getType(),item.getAmount(),Integer::sum);}
        for(var entry:remaining.entrySet()){Price price=prices.get(entry.getKey());int already=db.dailySold(CoreUtil.id(p),entry.getKey().name(),day),amount=entry.getValue();int full=Math.min(amount,Math.max(0,price.dailyFull()-already)),reduced=amount-full;double earned=Math.round((full*price.sell()+reduced*price.sell()*price.reduced())*holder.multiplier*100)/100.0;soldAmounts.put(entry.getKey(),amount);earnings.put(entry.getKey(),earned);}
        if(!plugin.bank().payShopSeller(p,quote.earned(),"BASKET")){CoreUtil.error(p,"The Central Bank treasury cannot cover this sale yet.");plugin.settings().marketSound(p,"failed");refreshBasket(inv,p);return;}
        for(var entry:soldAmounts.entrySet()){int remove=entry.getValue();for(int slot=0;slot<INPUT_END&&remove>0;slot++){ItemStack item=inv.getItem(slot);if(item==null||item.getType()!=entry.getKey()||!item.isSimilar(new ItemStack(entry.getKey())))continue;int take=Math.min(remove,item.getAmount());item.setAmount(item.getAmount()-take);remove-=take;}creditStock(p,entry.getKey(),entry.getValue()-remove,entry.getValue());double earned=earnings.get(entry.getKey());db.recordSale(CoreUtil.id(p),entry.getKey().name(),day,entry.getValue(),earned);db.recordEconomy(CoreUtil.id(p),"SHOP_SELL",earned,entry.getKey().name());}
        holder.finalized=true;returnItems(p,inv);p.closeInventory();CoreUtil.msg(p,"Sold "+quote.sellable()+" item"+(quote.sellable()==1?"":"s")+" for "+CoreUtil.money(quote.earned())+".");plugin.settings().marketSound(p,"sale");
    }

    private void returnItems(Player p,Inventory inv){for(int slot=0;slot<INPUT_END;slot++){ItemStack item=inv.getItem(slot);if(item==null||item.getType().isAir())continue;inv.setItem(slot,null);CoreUtil.give(p,item);}}
    private int moveIntoInputs(Inventory inv,ItemStack moving){int remaining=moving.getAmount();for(int slot=0;slot<INPUT_END&&remaining>0;slot++){ItemStack current=inv.getItem(slot);if(current==null||!current.isSimilar(moving))continue;int add=Math.min(remaining,current.getMaxStackSize()-current.getAmount());current.setAmount(current.getAmount()+add);remaining-=add;}for(int slot=0;slot<INPUT_END&&remaining>0;slot++){ItemStack current=inv.getItem(slot);if(current!=null&&!current.getType().isAir())continue;int add=Math.min(remaining,moving.getMaxStackSize());ItemStack placed=moving.clone();placed.setAmount(add);inv.setItem(slot,placed);remaining-=add;}return remaining;}

    boolean command(Player p,String[] args){
        if(args.length==0){open(p);return true;}if(args[0].equalsIgnoreCase("luxury")||args[0].equalsIgnoreCase("luxuries")){open(p,true);return true;}
        if(args[0].equalsIgnoreCase("sell")&&args.length==1){openSellBasket(p,false);return true;}
        if(args[0].equalsIgnoreCase("sellall")&&args.length>1&&args[1].equalsIgnoreCase("chest")){sellAllChest(p);return true;}
        if(args[0].equalsIgnoreCase("sellall")||args[0].equalsIgnoreCase("quicksell")){requestQuickSell(p,false,()->{});return true;}
        if(args.length<2||!(args[0].equalsIgnoreCase("buy")||args[0].equalsIgnoreCase("sell"))){CoreUtil.error(p,"Usage: /shop [luxury|sellall|buy|sell] <item> [amount]");return true;}
        Material material=Material.matchMaterial(args[1]);Price price=material==null?null:prices.get(material);if(price==null){CoreUtil.error(p,"That item is not in the server shop.");return true;}
        int amount=1;if(args.length>2)try{amount=Math.min(2304,Math.max(1,Integer.parseInt(args[2])));}catch(NumberFormatException ignored){}
        if(args[0].equalsIgnoreCase("buy"))buy(p,material,amount,1);else sell(p,material,amount,1);return true;
    }

    List<String> itemNames(boolean selling,String prefix){String lower=prefix.toLowerCase(Locale.ROOT);return prices.entrySet().stream().filter(e->!selling||(!e.getValue().luxury()&&e.getValue().sell()>=0)).map(e->e.getKey().name().toLowerCase(Locale.ROOT)).filter(n->n.startsWith(lower)).toList();}
    void buy(Player p,Material material,int amount,double multiplier){Price price=prices.get(material);if(price==null)return;if(price.luxury()){amount=1;if(!plugin.bank().allowNonessential(p,"luxury purchases")){plugin.settings().marketSound(p,"failed");return;}}ItemStack wanted=purchaseItem(material,price,amount);double cost=Math.round(price.buy()*amount*multiplier*100)/100.0;if(!canFit(p,wanted)){CoreUtil.error(p,"Make enough inventory space first.");plugin.settings().marketSound(p,"failed");return;}
        /** Stock is taken FIRST, as a single conditional statement, so it doubles as the reservation: if it
         *  succeeds nobody else can spend those items, and if it fails there was nothing to sell. Everything
         *  after this point must return the stock on any failure, or the shop would lose items it owns. */
        boolean limited=stockLimited(material);
        if(limited&&!db.shopStockTake(material.name(),amount)){
            int have=db.shopStock(material.name());
            CoreUtil.error(p,have<=0?"The shop has no "+price.display()+" in stock. Players must sell some first."
                    :"The shop only has "+CoreUtil.compact(have)+" "+price.display()+" in stock.");
            plugin.settings().marketSound(p,"failed");return;
        }
        if(!plugin.bank().payServer(p,cost,"SHOP_PURCHASE",material.name())){
            if(limited)db.shopStockAdd(material.name(),amount);
            CoreUtil.error(p,"You need "+CoreUtil.money(cost)+" to buy "+amount+" "+price.display()+".");plugin.settings().marketSound(p,"failed");return;
        }
        db.recordEconomy(CoreUtil.id(p),"SHOP_BUY",-cost,material.name());
        /** canFit() was checked on this same tick so leftovers should be impossible, but if the inventory
         *  somehow cannot take them they are dropped rather than deleted -- the money and the stock have
         *  already moved, so silently swallowing them would destroy paid-for items. */
        for(ItemStack leftover:p.getInventory().addItem(wanted).values())p.getWorld().dropItemNaturally(p.getLocation(),leftover);CoreUtil.msg(p,"Bought "+amount+" "+price.display()+" for "+CoreUtil.money(cost)+".");boolean specialSound=material==Material.ELYTRA||material==Material.DRAGON_EGG||material==Material.WIND_CHARGE;if(!price.luxury()||!specialSound)plugin.settings().marketSound(p,"purchase");if(price.luxury())celebrateLuxury(p,material,price);}
    void sell(Player p,Material material,int wanted,double multiplier){Price price=prices.get(material);if(price==null||price.luxury()||price.sell()<0){CoreUtil.error(p,"That item cannot be sold to the server.");plugin.settings().marketSound(p,"failed");return;}ItemStack vanilla=new ItemStack(material);int available=0;for(ItemStack item:p.getInventory().getStorageContents())if(item!=null&&item.isSimilar(vanilla))available+=item.getAmount();int amount=Math.min(wanted,available);if(amount<=0){CoreUtil.error(p,"Only ordinary, unmodified "+CoreUtil.pretty(material.name())+" can be sold.");plugin.settings().marketSound(p,"failed");return;}String day=LocalDate.now().toString();int sold=db.dailySold(CoreUtil.id(p),material.name(),day),full=Math.min(amount,Math.max(0,price.dailyFull()-sold)),reduced=amount-full;double earned=Math.round((full*price.sell()+reduced*price.sell()*price.reduced())*multiplier*100)/100.0;if(!plugin.bank().payShopSeller(p,earned,material.name())){CoreUtil.error(p,"The Central Bank treasury cannot cover this sale yet.");plugin.settings().marketSound(p,"failed");return;}int remaining=amount;for(ItemStack item:p.getInventory().getStorageContents())if(item!=null&&item.isSimilar(vanilla)){int take=Math.min(remaining,item.getAmount());item.setAmount(item.getAmount()-take);remaining-=take;if(remaining==0)break;}creditStock(p,material,amount-remaining,amount);db.recordSale(CoreUtil.id(p),material.name(),day,amount,earned);db.recordEconomy(CoreUtil.id(p),"SHOP_SELL",earned,material.name());CoreUtil.msg(p,"Sold "+amount+" "+CoreUtil.pretty(material.name())+" for "+CoreUtil.money(earned)+(reduced>0?" (50% rate applied after today's threshold)":"")+".");plugin.settings().marketSound(p,"sale");}

    List<Map.Entry<Material,Price>> entries(boolean luxury){return prices.entrySet().stream().filter(entry->entry.getValue().luxury()==luxury).map(entry->Map.entry(entry.getKey(),entry.getValue())).toList();}
    Price price(Material material){return prices.get(material);}
    /** The shop ICON must match what is actually delivered. IRON_GOLEM_SPAWN_EGG is only the catalogue key
     *  for the Iron Golem Spawner -- showing the egg made the listing look like it sold a spawn egg,
     *  so the icon is swapped for the real spawner here exactly as purchaseItem swaps the delivery. */
    ItemStack displayItem(Material material){
        Price price=prices.get(material);
        if(material==Material.IRON_GOLEM_SPAWN_EGG)return plugin.spawners().purchasedSpawner(org.bukkit.entity.EntityType.IRON_GOLEM);
        return price!=null&&price.luxury()?luxuryItem(material,price):new ItemStack(material);
    }

    double configuredSell(Material material){Price price=prices.get(material);return price==null||price.luxury()?0:Math.max(0,price.sell());}
    boolean selfTest(){Price shell=prices.get(Material.SHULKER_SHELL),stone=prices.get(Material.COBBLESTONE),wind=prices.get(Material.WIND_CHARGE),dirt=prices.get(Material.DIRT),cane=prices.get(Material.SUGAR_CANE),tag=prices.get(Material.NAME_TAG),egg=prices.get(Material.DRAGON_EGG),single=prices.get(Material.TRIAL_KEY),reusable=prices.get(Material.OMINOUS_TRIAL_KEY);if(shell==null||Math.abs(shell.buy()-40000)>.001||stone==null||wind==null||Math.abs(wind.buy()-5000)>.001||dirt==null||cane==null||tag==null||tag.buy()!=10000||!tag.luxury()||egg==null||egg.buy()!=50000000||single==null||single.buy()!=100000||reusable==null||reusable.buy()!=5000000||dirt.reduced()!=.5||dirt.dailyLimit()!=Integer.MAX_VALUE)return false;
        /** Every commodity must price at exactly the fixed multiple of its sell value, with no exceptions:
         *  the point of deriving buy is that no single item can drift. Luxuries have no sell price so are
         *  excluded. This also pins the prices the owner fixed, so an edit that quietly moves them fails
         *  the self-test instead of shipping. */
        for(var entry:prices.entrySet()){
            Price value=entry.getValue();
            if(value.luxury()||value.sell()<=0)continue;
            if(Math.abs(value.buy()-Math.round(value.sell()*buyMultiple()*100)/100.0)>.001)return false;
        }
        /** Model invariants rather than individual prices. Commodity prices are generated by the economy
         *  model (reports/shop_balance_report.csv), so pinning them here would fail the self-test every time
         *  the model is retuned. What must never drift are the relationships the owner set: Emerald above
         *  Blaze Rod, and Gunpowder at least double Blaze Rod, because there is no Creeper spawner. */
        Price blazeRod=prices.get(Material.BLAZE_ROD),emerald=prices.get(Material.EMERALD),powder=prices.get(Material.GUNPOWDER);
        if(blazeRod==null||emerald==null||powder==null)return false;
        if(emerald.sell()<=blazeRod.sell()||powder.sell()<blazeRod.sell()*2)return false;
        Price heavy=prices.get(Material.HEAVY_CORE),silence=prices.get(Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE),
                star=prices.get(Material.NETHER_STAR),beacon=prices.get(Material.BEACON);
        if(heavy==null||heavy.buy()!=10000000||silence==null||silence.buy()!=1000000
                ||star==null||star.buy()!=500000||beacon==null||beacon.buy()!=750000)return false;
        /** Luxuries are minted by the server and must never be stock-gated; commodities always must be. */
        if(stockLimited(Material.DRAGON_EGG)||!stockLimited(Material.COBBLESTONE))return false;
        ItemStack purchase=purchaseItem(Material.COBBLESTONE,stone,32),charge=purchaseItem(Material.WIND_CHARGE,wind,1),capsule=purchaseItem(Material.TRIAL_KEY,single,1);
        return purchase.getAmount()==32&&!purchase.hasItemMeta()&&!charge.hasItemMeta()&&capsule.getItemMeta().getPersistentDataContainer().has(capsuleKey)&&itemNames(true,"cobble").contains("cobblestone");}
    private void celebrateLuxury(Player buyer,Material material,Price price){String shown=plugin.nicknames().displayName(buyer);if(material==Material.WIND_CHARGE){if(plugin.settings().sounds(buyer))buyer.playSound(buyer.getLocation(),Sound.ENTITY_BREEZE_SHOOT,.7f,1.15f);return;}if(material==Material.ELYTRA){plugin.getServer().broadcast(Component.text("✦ THE SKY OPENS",NamedTextColor.AQUA));plugin.getServer().broadcast(Component.text(shown+" has earned the Veteran Explorer's Wings.",NamedTextColor.GOLD));celebrationSounds(Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,Sound.ENTITY_FIREWORK_ROCKET_BLAST,Sound.UI_TOAST_CHALLENGE_COMPLETE);buyer.getWorld().spawnParticle(Particle.END_ROD,buyer.getLocation().add(0,1,0),80,1.5,1.2,1.5,.08);}else if(material==Material.DRAGON_EGG){plugin.getServer().broadcast(Component.text("AN END-BORN TREASURE CHANGES HANDS",NamedTextColor.DARK_PURPLE));plugin.getServer().broadcast(Component.text(shown+" acquired a Dragon Egg.",NamedTextColor.GOLD));celebrationSounds(Sound.ENTITY_ENDER_DRAGON_GROWL,Sound.BLOCK_END_PORTAL_SPAWN,Sound.UI_TOAST_CHALLENGE_COMPLETE);buyer.getWorld().spawnParticle(Particle.DRAGON_BREATH,buyer.getLocation().add(0,1,0),100,1.5,1.3,1.5,.05);}else plugin.getServer().broadcast(Component.text("✦ "+shown+" acquired "+price.display()+" from the Ashfall luxury market.",NamedTextColor.LIGHT_PURPLE));}
    private void celebrationSounds(Sound first,Sound second,Sound third){Sound[] sounds={first,second,third};for(int i=0;i<sounds.length;i++){int index=i;plugin.getServer().getScheduler().runTaskLater(plugin,()->{for(Player online:plugin.getServer().getOnlinePlayers())if(plugin.settings().sounds(online))online.playSound(online.getLocation(),sounds[index],.8f,1f);},i*10L);}}
    static boolean canFit(Player p,ItemStack wanted){int remaining=wanted.getAmount(),max=wanted.getMaxStackSize();for(ItemStack slot:p.getInventory().getStorageContents()){if(slot==null||slot.getType().isAir())remaining-=max;else if(slot.isSimilar(wanted))remaining-=Math.max(0,max-slot.getAmount());if(remaining<=0)return true;}return false;}
}
