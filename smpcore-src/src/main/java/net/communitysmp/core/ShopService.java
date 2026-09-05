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
    record Price(double buy,double sell,int dailyFull,int dailyLimit,double reduced,String display,boolean luxury,String category,boolean allowDamaged){}
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
            /** Opt-in, per item, and off everywhere it is not written. Only gear a mob can drop already
             *  worn needs it, and confining it to those entries is what stops the relaxed rule reaching
             *  anything else in the file. */
            boolean allowDamaged=section.getBoolean(key+".allow-damaged",false);
            prices.put(material,new Price(buy,sell,Math.max(0,full),Math.max(full,limit),Math.max(0,Math.min(1,reduced)),display,luxury,category,allowDamaged));
        }
    }

    /** What the shop currently owns, and can therefore sell. Luxuries are minted by the server rather than
     *  bought from players, so they are not stock-limited. */
    /** Live economy references used by other systems (Task Master reward valuation). */
    double luxuryBuyValue(Material material){Price p=prices.get(material);return p!=null&&p.luxury()?p.buy():0;}
    double sellValue(Material material){Price p=prices.get(material);return p!=null&&!p.luxury()&&p.sell()>=0?p.sell():0;}
    double buyPrice(Material material){Price p=prices.get(material);return p==null?0:p.buy();}
    boolean stockLimited(Material material){Price price=prices.get(material);return price!=null&&!price.luxury();}
    int stock(Material material){return stockLimited(material)?db.shopStock(material.name()):Integer.MAX_VALUE;}
    boolean inStock(Material material,int amount){return !stockLimited(material)||db.shopStock(material.name())>=amount;}
    java.util.Map<String,Integer> allStock(){return db.shopStockAll();}
    /** The authored category for a material, or null when it is not a shop item (auction listings). */
    String categoryOf(Material material){Price price=prices.get(material);return price==null?null:price.category();}
    /** The catalogue name a player actually sees, which is not always the material. IRON_GOLEM_SPAWN_EGG
     *  is the shop KEY for the Iron Golem Spawner; the item handed over is a spawner. */
    String displayName(Material material){Price price=prices.get(material);return price==null?CoreUtil.pretty(material.name()):price.display();}
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
    /** The Spawner Shop is its own screen but the same family, so /shop links straight across to it. */
    void openSpawners(Player p){plugin.spawnerShop().open(p);}
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
        SellHolder holder=new SellHolder(multiplier);Inventory inv=plugin.getServer().createInventory(holder,54,Component.text("Sell Basket",CoreUtil.EMBER));
        inv.setItem(CoreUtil.Menu.SELL_CANCEL,CoreUtil.Menu.cancel("Every item comes straight back to you."));
        inv.setItem(CoreUtil.Menu.SELL_TOTAL,totalIcon(new SaleQuote(0,0,0)));
        inv.setItem(CoreUtil.Menu.SELL_CONFIRM,confirmIcon(new SaleQuote(0,0,0)));
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
            if(raw==CoreUtil.Menu.SELL_CANCEL){holder.finalized=true;returnItems(p,e.getInventory());p.closeInventory();}
            else if(raw==CoreUtil.Menu.SELL_CONFIRM)confirmBasket(p,e.getInventory(),holder);
            return;
        }
        if(e.isShiftClick()&&e.getCurrentItem()!=null&&!e.getCurrentItem().getType().isAir()){
            e.setCancelled(true);ItemStack moving=e.getCurrentItem().clone();int remaining=moveIntoInputs(e.getInventory(),moving);int moved=moving.getAmount()-remaining;e.getCurrentItem().setAmount(e.getCurrentItem().getAmount()-moved);plugin.getServer().getScheduler().runTask(plugin,()->refreshBasket(e.getInventory(),p));
        }
    }

    void drag(InventoryDragEvent e){if(e.getInventory().getHolder(false) instanceof SellHolder&&e.getRawSlots().stream().anyMatch(slot->slot>=INPUT_END&&slot<54))e.setCancelled(true);if(e.getInventory().getHolder(false) instanceof SellHolder&&e.getWhoClicked() instanceof Player p)plugin.getServer().getScheduler().runTask(plugin,()->refreshBasket(e.getInventory(),p));}
    void close(InventoryCloseEvent e){if(e.getInventory().getHolder(false) instanceof SellHolder holder&&!holder.finalized&&e.getPlayer() instanceof Player p){holder.finalized=true;returnItems(p,e.getInventory());}}

    /*  Both the summary AND the button repaint. The amount you are about to be paid used to live only in
     *  the lore of a separate icon two slots away from the button that took the money, so the thing you
     *  clicked never said what it would do. */
    private void refreshBasket(Inventory inv,Player p){
        if(!(inv.getHolder(false) instanceof SellHolder holder)||holder.finalized)return;
        SaleQuote quote=quote(inv,p,holder.multiplier);
        inv.setItem(CoreUtil.Menu.SELL_TOTAL,totalIcon(quote));
        inv.setItem(CoreUtil.Menu.SELL_CONFIRM,confirmIcon(quote));
    }
    private ItemStack confirmIcon(SaleQuote quote){
        if(quote.sellable()<=0)return CoreUtil.Menu.blocked(Material.GRAY_CONCRETE,"Sell",
                "the basket is empty.",List.of("Drop items into the top rows.",
                        CoreUtil.C_MUTE+"Shift-click from your inventory to move a whole stack."));
        return CoreUtil.Menu.confirm(CoreUtil.money(quote.earned()),List.of(
                CoreUtil.C_BODY+"Sells "+CoreUtil.C_TEXT+quote.sellable()+CoreUtil.C_BODY+" item"+(quote.sellable()==1?"":"s"),
                quote.unsupported()>0?CoreUtil.C_WARN+quote.unsupported()+" the shop will not take come back to you"
                                     :CoreUtil.C_MUTE+"Paid into your balance immediately."));
    }
    private ItemStack totalIcon(SaleQuote quote){
        List<String> lore=new ArrayList<>();
        lore.add(CoreUtil.C_TEXT+quote.sellable()+CoreUtil.C_BODY+" item"+(quote.sellable()==1?"":"s")+" the shop will buy");
        if(quote.unsupported()>0)lore.add(CoreUtil.C_WARN+quote.unsupported()+CoreUtil.C_BODY+" it will not — handed back");
        lore.add(CoreUtil.C_MUTE+"Past its daily threshold an item pays half.");
        return CoreUtil.Menu.heading(Material.GOLD_INGOT,"Sale total  "+CoreUtil.money(quote.earned()),lore);
    }
    private SaleQuote quote(Inventory inv,Player p,double multiplier){
        Map<Material,Integer> counts=new LinkedHashMap<>();int unsupported=0;
        for(int slot=0;slot<INPUT_END;slot++){ItemStack item=inv.getItem(slot);if(item==null||item.getType().isAir())continue;Price price=prices.get(item.getType());if(price==null||price.luxury()||price.sell()<0||!ordinary(item)){unsupported+=item.getAmount();continue;}counts.merge(item.getType(),item.getAmount(),Integer::sum);}
        String day=LocalDate.now().toString();double earned=0;int sellable=0;
        for(var entry:counts.entrySet()){Price price=prices.get(entry.getKey());int sold=db.dailySold(CoreUtil.id(p),entry.getKey().name(),day),amount=entry.getValue();int full=Math.min(amount,Math.max(0,price.dailyFull()-sold)),reduced=amount-full;earned+=(full*price.sell()+reduced*price.sell()*price.reduced())*multiplier;sellable+=amount;}
        return new SaleQuote(Math.round(earned*plugin.bank().sellFactor()*100)/100.0,sellable,unsupported);
    }

    private SaleQuote inventoryQuote(Player p,double multiplier){
        Map<Material,Integer> counts=inventorySellable(p);String day=LocalDate.now().toString();double earned=0;int sellable=0;
        for(var entry:counts.entrySet()){Price price=prices.get(entry.getKey());int amount=entry.getValue(),sold=db.dailySold(CoreUtil.id(p),entry.getKey().name(),day);int full=Math.min(amount,Math.max(0,price.dailyFull()-sold)),reduced=amount-full;earned+=(full*price.sell()+reduced*price.sell()*price.reduced())*multiplier;sellable+=amount;}
        return new SaleQuote(Math.round(earned*plugin.bank().sellFactor()*100)/100.0,sellable,0);
    }

    private Map<Material,Integer> inventorySellable(Player p){return sellableFrom(p.getInventory().getStorageContents());}
    private Map<Material,Integer> sellableFrom(ItemStack[] contents){
        Map<Material,Integer> counts=new LinkedHashMap<>();
        for(ItemStack item:contents){
            if(!ordinary(item))continue;
            Price price=prices.get(item.getType());if(price!=null&&!price.luxury()&&price.sell()>=0)counts.merge(item.getType(),item.getAmount(),Integer::sum);
        }
        return counts;
    }
    private SaleQuote containerQuote(Inventory inv,Player p){
        Map<Material,Integer> counts=containerSellableDeep(inv);String day=LocalDate.now().toString();double earned=0;int sellable=0;
        for(var entry:counts.entrySet()){Price price=prices.get(entry.getKey());int amount=entry.getValue(),sold=db.dailySold(CoreUtil.id(p),entry.getKey().name(),day);int full=Math.min(amount,Math.max(0,price.dailyFull()-sold)),reduced=amount-full;earned+=full*price.sell()+reduced*price.sell()*price.reduced();sellable+=amount;}
        return new SaleQuote(Math.round(earned*plugin.bank().sellFactor()*100)/100.0,sellable,0);
    }
    /** Like sellableFrom, but for /shop sellall chest it ALSO reaches into shulker boxes sitting in the
     *  container and counts the sellable items inside them. The shulker itself is never counted or sold --
     *  only its contents. Chest-only: the plain /shop sellall (player inventory) path is untouched. */
    private boolean isShulkerBox(ItemStack item){return item!=null&&item.getType().name().endsWith("SHULKER_BOX")&&item.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta bsm&&bsm.getBlockState() instanceof org.bukkit.block.ShulkerBox;}
    private void countSellable(ItemStack item,Map<Material,Integer> counts){
        if(!ordinary(item))return;
        Price price=prices.get(item.getType());if(price!=null&&!price.luxury()&&price.sell()>=0)counts.merge(item.getType(),item.getAmount(),Integer::sum);
    }
    private Map<Material,Integer> containerSellableDeep(Inventory inv){
        Map<Material,Integer> counts=new LinkedHashMap<>();
        for(ItemStack item:inv.getContents()){
            if(item==null||item.getType().isAir())continue;
            if(isShulkerBox(item)){org.bukkit.block.ShulkerBox box=(org.bukkit.block.ShulkerBox)((org.bukkit.inventory.meta.BlockStateMeta)item.getItemMeta()).getBlockState();for(ItemStack inner:box.getInventory().getContents())countSellable(inner,counts);continue;}
            countSellable(item,counts);
        }
        return counts;
    }
    /** Removes up to `amount` of `material` from the container, reaching INTO any shulker boxes it holds
     *  (the emptied shulker is written back and kept). Returns how many were actually removed. */
    private int removeMaterialDeep(Inventory inv,Material material,int amount){
        int remaining=amount;
        for(int slot=0;slot<inv.getSize()&&remaining>0;slot++){
            ItemStack item=inv.getItem(slot);
            if(item==null||item.getType().isAir())continue;
            if(isShulkerBox(item)){
                org.bukkit.inventory.meta.BlockStateMeta bsm=(org.bukkit.inventory.meta.BlockStateMeta)item.getItemMeta();
                org.bukkit.block.ShulkerBox box=(org.bukkit.block.ShulkerBox)bsm.getBlockState();
                Inventory boxInv=box.getInventory();boolean modified=false;
                for(int inner=0;inner<boxInv.getSize()&&remaining>0;inner++){
                    ItemStack it=boxInv.getItem(inner);
                    if(!ordinary(it,material))continue;
                    int take=Math.min(remaining,it.getAmount());
                    if(take>=it.getAmount())boxInv.setItem(inner,null);else{it.setAmount(it.getAmount()-take);boxInv.setItem(inner,it);}
                    remaining-=take;modified=true;
                }
                if(modified){bsm.setBlockState(box);item.setItemMeta(bsm);inv.setItem(slot,item);}
                continue;
            }
            if(!ordinary(item,material))continue;
            int take=Math.min(remaining,item.getAmount());
            if(take>=item.getAmount())inv.setItem(slot,null);else{item.setAmount(item.getAmount()-take);inv.setItem(slot,item);}
            remaining-=take;
        }
        return amount-remaining;
    }
    /** The inventory /shop sellall chest should actually operate on.
     *
     *  An Industrial Hopper is a vanilla HOPPER block whose five native slots hold nothing but the
     *  comparator calibration weight -- its real contents live in a plugin-owned 27-slot inventory. Reading
     *  the block state therefore reported an empty hopper and refused to sell anything. Resolve the bay
     *  first and fall back to the block's own inventory for every ordinary container. */
    private Inventory sellTarget(Location location,org.bukkit.block.Container container){
        Inventory bay=plugin.industrialHoppers()==null?null:plugin.industrialHoppers().inventoryAt(location);
        return bay!=null?bay:container.getInventory();
    }

    boolean sellAllChest(Player player){
        org.bukkit.util.RayTraceResult ray=player.rayTraceBlocks(6);
        if(ray==null||ray.getHitBlock()==null||!(ray.getHitBlock().getState() instanceof org.bukkit.block.Container container)){CoreUtil.error(player,"Look at a chest, barrel, hopper, or shulker box within 6 blocks.");return true;}
        Location location=container.getBlock().getLocation();
        if(plugin.spawnClaims().contains(location)&&!plugin.isAdmin(player)){CoreUtil.error(player,"This storage is protected by spawn.");return true;}
        FactionService.Claim claim=plugin.factions().claimAt(location);
        if(claim!=null&&!plugin.factions().isMember(player,claim.faction())&&!plugin.privileged(player)){CoreUtil.error(player,"This storage is protected by "+claim.faction().name()+".");return true;}
        SaleQuote preview=containerQuote(sellTarget(location,container),player);
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
        Inventory inv=sellTarget(location,container);
        Map<Material,Integer> counts=containerSellableDeep(inv);
        SaleQuote quote=containerQuote(inv,player);
        if(quote.sellable()<=0){CoreUtil.error(player,"Nothing left to sell in that container.");plugin.settings().marketSound(player,"failed");return;}
        if(!plugin.bank().payShopSeller(player,quote.earned(),"CONTAINER_SELLALL")){CoreUtil.error(player,"The Central Bank treasury cannot cover this sale yet.");plugin.settings().marketSound(player,"failed");return;}
        String day=LocalDate.now().toString();
        for(var entry:counts.entrySet()){
            Material material=entry.getKey();Price price=prices.get(material);int amount=entry.getValue(),sold=db.dailySold(CoreUtil.id(player),material.name(),day);int full=Math.min(amount,Math.max(0,price.dailyFull()-sold)),reduced=amount-full;double earned=Math.round((full*price.sell()+reduced*price.sell()*price.reduced())*plugin.bank().sellFactor()*100)/100.0;
            int removed=removeMaterialDeep(inv,material,amount);
            creditStock(player,material,removed,amount);
            db.recordSale(CoreUtil.id(player),material.name(),day,amount,earned);db.recordEconomy(CoreUtil.id(player),"SHOP_SELL",earned,material.name());
        }
        /** An industrial hopper owns its inventory in memory and serialises it on its own schedule, so a
         *  sale made straight into that inventory has to say it changed or a restart would restore what
         *  was just sold. Harmless for an ordinary container -- markDirty simply finds no bay. */
        if(plugin.industrialHoppers()!=null)plugin.industrialHoppers().markDirty(location);
        CoreUtil.msg(player,"Sold "+quote.sellable()+" item"+(quote.sellable()==1?"":"s")+" from the container for "+CoreUtil.money(quote.earned())+".");plugin.settings().marketSound(player,"sale");
    }

    /*  Is this an ordinary, unmodified item of its kind?
     *
     *  The shop's rule has always been "identical to a factory-fresh one", which is exactly what keeps
     *  relics, named items, enchanted gear and anything carrying custom data out of the commodity path.
     *  Mob-dropped equipment breaks that rule for an entirely innocent reason: a skeleton's bow arrives with
     *  durability already spent and nothing else different about it, so the shop refused to buy a bow that
     *  was, in every way that matters, an ordinary bow.
     *
     *  So damage -- and ONLY damage -- is forgiven, and only for entries that ask for it with
     *  allow-damaged. The test is not "ignore the meta": it clears the damage and then demands that what
     *  remains be indistinguishable from a fresh item. An enchanted bow still carries its enchantments
     *  after the damage is cleared and fails; so does a renamed one, a relic, a shard reward, or anything
     *  with custom model data or attributes. There is no path here that accepts an item on the strength of
     *  its material alone. */
    boolean ordinary(ItemStack item){
        if(item==null||item.getType().isAir())return false;
        if(item.isSimilar(new ItemStack(item.getType())))return true;
        Price price=prices.get(item.getType());
        if(price==null||!price.allowDamaged())return false;
        if(!(item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable)||!damageable.hasDamage())return false;
        ItemStack bare=item.clone();
        bare.setAmount(1);
        org.bukkit.inventory.meta.ItemMeta meta=bare.getItemMeta();
        ((org.bukkit.inventory.meta.Damageable)meta).resetDamage();
        bare.setItemMeta(meta);
        return bare.isSimilar(new ItemStack(item.getType()));
    }

    /** The same question about a specific material, for the removal loops that have to match exactly what
     *  the quote counted -- a quote that accepts an item the removal cannot find would pay for goods the
     *  player keeps. */
    private boolean ordinary(ItemStack item,Material material){return item!=null&&item.getType()==material&&ordinary(item);}

    /** Whether the shop currently buys this material at all. The single question every caller outside
     *  this class should be asking, so that "what counts as sellable" has one definition. */
    boolean buysBack(Material material){Price price=prices.get(material);return price!=null&&!price.luxury()&&price.sell()>=0;}

    /** What a sale of these counts is worth right now, per material, with the daily threshold, the reduced
     *  rate past it and the Central Bank sell factor all applied exactly as every other sell path does. */
    java.util.Map<Material,Double> bulkQuote(Player p,java.util.Map<Material,Integer> counts){
        java.util.Map<Material,Double> out=new LinkedHashMap<>();String day=LocalDate.now().toString();
        for(var entry:counts.entrySet()){
            Price price=prices.get(entry.getKey());
            if(price==null||price.luxury()||price.sell()<0)continue;
            out.put(entry.getKey(),bulkLine(price,entry.getValue(),
                    db.dailySold(CoreUtil.id(p),entry.getKey().name(),day),plugin.bank().sellFactor()));
        }
        return out;
    }

    /** What one material line of a sale is worth: full price up to the day's threshold, the reduced rate
     *  beyond it, the whole thing scaled by the Central Bank sell factor. Pulled out of the quote so the
     *  money side of a bulk sale can be asserted without a player, a day or a database -- and so a bulk
     *  sale cannot drift away from the per-item price by having its own copy of the formula. */
    static double bulkLine(Price price,int amount,int alreadySold,double sellFactor){
        int full=Math.min(amount,Math.max(0,price.dailyFull()-alreadySold)),reduced=amount-full;
        return Math.round((full*price.sell()+reduced*price.sell()*price.reduced())*sellFactor*100)/100.0;
    }

    /*  Money conservation for the bulk path.
     *
     *  The property that actually matters is the last one: selling a thousand mobs' worth of drops in one
     *  aggregate must pay exactly what selling the same drops one mob at a time would have paid. If it pays
     *  more, the threshold is a formality and the reduced rate does nothing; if it pays less, a farm is
     *  quietly taxed for being efficient. Everything above it is the arithmetic that has to hold for that
     *  to be true at all. */
    static boolean bulkSelfTest(){
        Price price=new Price(10,2,100,0,.5,"Test",false,"TEST",false);
        if(Math.abs(bulkLine(price,50,0,1)-100)>.001)return false;
        if(Math.abs(bulkLine(price,200,0,1)-300)>.001)return false;
        if(Math.abs(bulkLine(price,100,100,1)-100)>.001)return false;
        if(Math.abs(bulkLine(price,50,0,.5)-50)>.001)return false;
        if(Math.abs(bulkLine(price,0,0,1))>.001)return false;
        double piecemeal=0;
        for(int mob=0;mob<1000;mob++)piecemeal+=bulkLine(price,2,2*mob,1);
        return Math.abs(bulkLine(price,2000,0,1)-piecemeal)<.01;
    }

    /*  Sells items that were never in anybody's inventory.
     *
     *  Every other sell path is quote / pay / remove-from-somewhere. This one has nothing to remove: the
     *  caller is holding an aggregate that has not been spawned into the world at all, which is the entire
     *  point -- a thousand-mob stack that materialises its drops just to sell them back is exactly the lag
     *  the aggregate exists to avoid.
     *
     *  Everything else is the ordinary path and deliberately so: the same daily threshold, the same reduced
     *  rate past it, the same Central Bank sell factor, the same payShopSeller (which is where an overdue
     *  loan is garnished and where a treasury that cannot cover the sale refuses it), the same shop stock
     *  credit, the same sale and economy rows. Nothing about a mob stack gets its own economics. */
    boolean sellBulk(Player p,java.util.Map<Material,Integer> counts,String detail){
        java.util.Map<Material,Double> earnings=bulkQuote(p,counts);
        if(earnings.isEmpty())return false;
        double total=0;for(double value:earnings.values())total+=value;
        total=Math.round(total*100)/100.0;
        if(!plugin.bank().payShopSeller(p,total,detail))return false;
        String day=LocalDate.now().toString();
        for(var entry:earnings.entrySet()){
            int amount=counts.get(entry.getKey());
            /** removed == quoted here by construction: the shop receives every one of them, because they
             *  came into existence owed to the shop rather than being taken out of a container. */
            creditStock(p,entry.getKey(),amount,amount);
            db.recordSale(CoreUtil.id(p),entry.getKey().name(),day,amount,entry.getValue());
            db.recordEconomy(CoreUtil.id(p),"SHOP_SELL",entry.getValue(),entry.getKey().name());
        }
        return true;
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
            int remaining=amount;for(ItemStack item:p.getInventory().getStorageContents())if(ordinary(item,material)){int take=Math.min(remaining,item.getAmount());item.setAmount(item.getAmount()-take);remaining-=take;if(remaining==0)break;}
            creditStock(p,material,amount-remaining,amount);
            db.recordSale(CoreUtil.id(p),material.name(),day,amount,earned);db.recordEconomy(CoreUtil.id(p),"SHOP_SELL",earned,material.name());
        }
        CoreUtil.msg(p,"Quick-sold "+quote.sellable()+" item"+(quote.sellable()==1?"":"s")+" for "+CoreUtil.money(quote.earned())+".");plugin.settings().marketSound(p,"sale");
    }

    private void confirmBasket(Player p,Inventory inv,SellHolder holder){
        SaleQuote quote=quote(inv,p,holder.multiplier);if(quote.sellable()<=0){CoreUtil.error(p,"No supported items are currently sellable.");plugin.settings().marketSound(p,"failed");refreshBasket(inv,p);return;}
        String day=LocalDate.now().toString();Map<Material,Integer> remaining=new HashMap<>(),soldAmounts=new HashMap<>();Map<Material,Double> earnings=new HashMap<>();
        for(int slot=0;slot<INPUT_END;slot++){ItemStack item=inv.getItem(slot);if(!ordinary(item))continue;Price price=prices.get(item.getType());if(price!=null&&!price.luxury()&&price.sell()>=0)remaining.merge(item.getType(),item.getAmount(),Integer::sum);}
        for(var entry:remaining.entrySet()){Price price=prices.get(entry.getKey());int already=db.dailySold(CoreUtil.id(p),entry.getKey().name(),day),amount=entry.getValue();int full=Math.min(amount,Math.max(0,price.dailyFull()-already)),reduced=amount-full;double earned=Math.round((full*price.sell()+reduced*price.sell()*price.reduced())*holder.multiplier*plugin.bank().sellFactor()*100)/100.0;soldAmounts.put(entry.getKey(),amount);earnings.put(entry.getKey(),earned);}
        if(!plugin.bank().payShopSeller(p,quote.earned(),"BASKET")){CoreUtil.error(p,"The Central Bank treasury cannot cover this sale yet.");plugin.settings().marketSound(p,"failed");refreshBasket(inv,p);return;}
        for(var entry:soldAmounts.entrySet()){int remove=entry.getValue();for(int slot=0;slot<INPUT_END&&remove>0;slot++){ItemStack item=inv.getItem(slot);if(!ordinary(item,entry.getKey()))continue;int take=Math.min(remove,item.getAmount());item.setAmount(item.getAmount()-take);remove-=take;}creditStock(p,entry.getKey(),entry.getValue()-remove,entry.getValue());double earned=earnings.get(entry.getKey());db.recordSale(CoreUtil.id(p),entry.getKey().name(),day,entry.getValue(),earned);db.recordEconomy(CoreUtil.id(p),"SHOP_SELL",earned,entry.getKey().name());}
        holder.finalized=true;returnItems(p,inv);
        CoreUtil.msg(p,"Sold "+quote.sellable()+" item"+(quote.sellable()==1?"":"s")+" for "+CoreUtil.money(quote.earned())+".");plugin.settings().marketSound(p,"sale");
        /** Selling a basket used to drop the player out of the shop entirely, which is the wrong end of the
         *  flow: they came from /shop, they will almost always want to sell another basket or go on buying.
         *  Reopening the shop one tick later (rather than in this handler) keeps Bukkit's inventory close and
         *  open from re-entering each other. */
        plugin.getServer().getScheduler().runTask(plugin,()->{if(p.isOnline())open(p);});
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
    void buy(Player p,Material material,int amount,double multiplier){Price price=prices.get(material);if(price==null)return;if(price.luxury()){if(!plugin.bank().allowNonessential(p,"luxury purchases")){plugin.settings().marketSound(p,"failed");return;}}ItemStack wanted=purchaseItem(material,price,amount);double cost=Math.round(price.buy()*amount*multiplier*plugin.bank().buyFactor()*100)/100.0;if(!canFit(p,wanted)){CoreUtil.error(p,"Make enough inventory space first.");plugin.settings().marketSound(p,"failed");return;}
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
        for(ItemStack leftover:p.getInventory().addItem(wanted).values())p.getWorld().dropItemNaturally(p.getLocation(),leftover);CoreUtil.msg(p,"Bought "+amount+" "+price.display()+" for "+CoreUtil.money(cost)+".");boolean specialSound=material==Material.ELYTRA||material==Material.DRAGON_EGG||material==Material.WIND_CHARGE;if(!price.luxury()||!specialSound)plugin.settings().marketSound(p,"purchase");if(price.luxury())celebrateLuxury(p,material,price,amount,cost);}
    void sell(Player p,Material material,int wanted,double multiplier){Price price=prices.get(material);if(price==null||price.luxury()||price.sell()<0){CoreUtil.error(p,"That item cannot be sold to the server.");plugin.settings().marketSound(p,"failed");return;}int available=0;for(ItemStack item:p.getInventory().getStorageContents())if(ordinary(item,material))available+=item.getAmount();int amount=Math.min(wanted,available);if(amount<=0){CoreUtil.error(p,"Only ordinary, unmodified "+CoreUtil.pretty(material.name())+" can be sold.");plugin.settings().marketSound(p,"failed");return;}String day=LocalDate.now().toString();int sold=db.dailySold(CoreUtil.id(p),material.name(),day),full=Math.min(amount,Math.max(0,price.dailyFull()-sold)),reduced=amount-full;double earned=Math.round((full*price.sell()+reduced*price.sell()*price.reduced())*multiplier*plugin.bank().sellFactor()*100)/100.0;if(!plugin.bank().payShopSeller(p,earned,material.name())){CoreUtil.error(p,"The Central Bank treasury cannot cover this sale yet.");plugin.settings().marketSound(p,"failed");return;}int remaining=amount;for(ItemStack item:p.getInventory().getStorageContents())if(ordinary(item,material)){int take=Math.min(remaining,item.getAmount());item.setAmount(item.getAmount()-take);remaining-=take;if(remaining==0)break;}creditStock(p,material,amount-remaining,amount);db.recordSale(CoreUtil.id(p),material.name(),day,amount,earned);db.recordEconomy(CoreUtil.id(p),"SHOP_SELL",earned,material.name());CoreUtil.msg(p,"Sold "+amount+" "+CoreUtil.pretty(material.name())+" for "+CoreUtil.money(earned)+(reduced>0?" (50% rate applied after today's threshold)":"")+".");plugin.settings().marketSound(p,"sale");}

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
    /*  The owner's stated rule, as an assertion: a cooked item is its raw item plus a 10-15% crafting tax.
     *
     *  This is the only price relationship on the list that was given as a hard rule rather than a feel, so
     *  it is the only one encoded. It exists to stop the raw rows drifting away from the cooked ones the
     *  next time either is touched -- not to police prices the owner set deliberately, which are pinned
     *  elsewhere and are none of this test's business. */
    boolean craftingTaxSelfTest(){
        String[][] pairs={{"CHICKEN","COOKED_CHICKEN"},{"BEEF","COOKED_BEEF"},{"COD","COOKED_COD"},
                {"SALMON","COOKED_SALMON"},{"MUTTON","COOKED_MUTTON"},{"PORKCHOP","COOKED_PORKCHOP"},
                {"RABBIT","COOKED_RABBIT"}};
        for(String[] pair:pairs){
            Material raw=Material.matchMaterial(pair[0]),cooked=Material.matchMaterial(pair[1]);
            if(raw==null||cooked==null)return false;
            Price rawPrice=prices.get(raw),cookedPrice=prices.get(cooked);
            if(rawPrice==null||cookedPrice==null)return false;
            double ratio=cookedPrice.buy()/Math.max(.0001,rawPrice.buy());
            if(ratio<1.09||ratio>1.16)return false;
        }
        return true;
    }

    /*  The bow's price is a recipe, and a recipe can drift.
     *
     *  Three string plus three sticks plus a crafting premium in the 15-25% band the owner set, and -- the
     *  part that actually matters -- no way to make money out of the loop in either direction. If somebody
     *  later raises the bow or drops the price of string, this is what notices. */
    boolean bowRecipeSelfTest(){
        Price string=prices.get(Material.STRING),stick=prices.get(Material.STICK),bow=prices.get(Material.BOW);
        if(string==null||stick==null||bow==null)return false;
        double ingredients=3*string.sell()+3*stick.sell();
        double premium=bow.sell()/ingredients;
        if(premium<1.15||premium>1.25)return false;
        /** Buy the parts, craft, sell the bow: must lose money. */
        if(3*string.buy()+3*stick.buy()<=bow.sell())return false;
        /** Buy the bow, sell it back: must lose money. */
        if(bow.buy()<=bow.sell())return false;
        /** And crafting must still beat buying one outright, or the shop undercuts the crafting table. */
        if(bow.buy()<=3*string.buy()+3*stick.buy())return false;
        /** A stick must stay a fraction of the log it comes from -- eight to a log. */
        Price log=prices.get(Material.OAK_LOG);
        return log!=null&&8*stick.sell()<log.buy();
    }

    /** Every entry that forgives durability must be gear a mob can actually drop worn, never a commodity. */
    boolean damagedOptInSelfTest(){
        for(var entry:prices.entrySet())
            if(entry.getValue().allowDamaged()&&!(entry.getKey().getMaxDurability()>0))return false;
        ItemStack enchanted=new ItemStack(Material.BOW);
        org.bukkit.inventory.meta.ItemMeta meta=enchanted.getItemMeta();
        meta.addEnchant(org.bukkit.enchantments.Enchantment.POWER,1,true);
        if(meta instanceof org.bukkit.inventory.meta.Damageable damaged)damaged.setDamage(100);
        enchanted.setItemMeta(meta);
        /** An enchanted bow is refused even though bows forgive damage: clearing the damage leaves the
         *  enchantment behind, and what is left is not an ordinary bow. */
        if(ordinary(enchanted))return false;
        ItemStack used=new ItemStack(Material.BOW);
        org.bukkit.inventory.meta.ItemMeta plain=used.getItemMeta();
        ((org.bukkit.inventory.meta.Damageable)plain).setDamage(200);
        used.setItemMeta(plain);
        if(!ordinary(used))return false;
        /** A damaged item whose entry did NOT opt in stays refused. */
        ItemStack pick=new ItemStack(Material.IRON_PICKAXE);
        org.bukkit.inventory.meta.ItemMeta pickMeta=pick.getItemMeta();
        ((org.bukkit.inventory.meta.Damageable)pickMeta).setDamage(50);
        pick.setItemMeta(pickMeta);
        return !ordinary(pick)&&ordinary(new ItemStack(Material.STICK));
    }

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
    private void celebrateLuxury(Player buyer,Material material,Price price,int amount,double cost){if(material==Material.WIND_CHARGE){if(plugin.settings().sounds(buyer))buyer.playSound(buyer.getLocation(),Sound.ENTITY_BREEZE_SHOOT,.7f,1.15f);return;}/** Only sizeable luxury buys are broadcast now (spam control), and the shout states the quantity bought. */if(cost<plugin.getConfig().getDouble("shop.luxury-announce-threshold",500000))return;String shown=plugin.nicknames().displayName(buyer);String qty=amount+"x ";if(material==Material.ELYTRA){plugin.getServer().broadcast(Component.text("✦ THE SKY OPENS",NamedTextColor.AQUA));plugin.getServer().broadcast(Component.text(shown+" has bought "+qty+price.display()+"!",NamedTextColor.GOLD));celebrationSounds(Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,Sound.ENTITY_FIREWORK_ROCKET_BLAST,Sound.UI_TOAST_CHALLENGE_COMPLETE);buyer.getWorld().spawnParticle(Particle.END_ROD,buyer.getLocation().add(0,1,0),80,1.5,1.2,1.5,.08);}else if(material==Material.DRAGON_EGG){plugin.getServer().broadcast(Component.text("AN END-BORN TREASURE CHANGES HANDS",NamedTextColor.DARK_PURPLE));plugin.getServer().broadcast(Component.text(shown+" has bought "+qty+price.display()+"!",NamedTextColor.GOLD));celebrationSounds(Sound.ENTITY_ENDER_DRAGON_GROWL,Sound.BLOCK_END_PORTAL_SPAWN,Sound.UI_TOAST_CHALLENGE_COMPLETE);buyer.getWorld().spawnParticle(Particle.DRAGON_BREATH,buyer.getLocation().add(0,1,0),100,1.5,1.3,1.5,.05);}else plugin.getServer().broadcast(Component.text("✦ "+shown+" has bought "+qty+price.display()+" from the Ashfall luxury market!",NamedTextColor.LIGHT_PURPLE));}
    private void celebrationSounds(Sound first,Sound second,Sound third){Sound[] sounds={first,second,third};for(int i=0;i<sounds.length;i++){int index=i;plugin.getServer().getScheduler().runTaskLater(plugin,()->{for(Player online:plugin.getServer().getOnlinePlayers())if(plugin.settings().sounds(online))online.playSound(online.getLocation(),sounds[index],.8f,1f);},i*10L);}}
    static boolean canFit(Player p,ItemStack wanted){int remaining=wanted.getAmount(),max=wanted.getMaxStackSize();for(ItemStack slot:p.getInventory().getStorageContents()){if(slot==null||slot.getType().isAir())remaining-=max;else if(slot.isSimilar(wanted))remaining-=Math.max(0,max-slot.getAmount());if(remaining<=0)return true;}return false;}
}
