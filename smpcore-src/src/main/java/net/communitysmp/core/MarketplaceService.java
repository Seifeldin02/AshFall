package net.communitysmp.core;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class MarketplaceService implements Listener {
    enum Section { SHOP, AUCTION, LUXURY, SHARDS }
    private enum Sort { DEFAULT, CHEAPEST, EXPENSIVE, NAME }
    private enum InputType { ITEM, SELLER, LIST_PRICE }
    private record ItemRef(Material material,Long auction,String shard) {}
    private static final class View {
        Sort sort=Sort.DEFAULT;String category="ALL",query="",seller="";int page;boolean mine,merchant;
    }
    private static final class Session {
        Section section=Section.SHOP;final EnumMap<Section,View> views=new EnumMap<>(Section.class);
        View view(){return views.computeIfAbsent(section,key->new View());}
    }
    private static final class Holder implements InventoryHolder {
        final UUID player;final Section section;final Map<Integer,ItemRef> items=new HashMap<>();
        Holder(UUID player,Section section){this.player=player;this.section=section;}@Override public Inventory getInventory(){return null;}
    }
    private record FilterHolder(UUID player,Section section) implements InventoryHolder {@Override public Inventory getInventory(){return null;}}
    private record SearchHolder(UUID player,InputType type,Section section) implements InventoryHolder {@Override public Inventory getInventory(){return null;}}
    private record Input(InputType type,Section section,long expires) {}

    private static final int PAGE_SIZE=43;
    private final SMPCore plugin;private final ShopService shop;private final AuctionService auctions;private final ShardService shards;
    private final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
    private final Map<UUID,Input> inputs=new ConcurrentHashMap<>();

    MarketplaceService(SMPCore plugin,ShopService shop,AuctionService auctions,ShardService shards){this.plugin=plugin;this.shop=shop;this.auctions=auctions;this.shards=shards;}

    void open(Player player,Section section){Session session=sessions.computeIfAbsent(player.getUniqueId(),id->new Session());session.section=section;resetSearch(session.view());session.view().merchant=false;render(player,session);}
    void openPremium(Player player){Session session=sessions.computeIfAbsent(player.getUniqueId(),id->new Session());session.section=Section.SHOP;resetSearch(session.view());session.view().merchant=true;render(player,session);}
    void openAuctionMerchant(Player player){Session session=sessions.computeIfAbsent(player.getUniqueId(),id->new Session());session.section=Section.AUCTION;resetSearch(session.view());session.view().merchant=true;render(player,session);}
    void openSellBasket(Player player,boolean premium){shop.openSellBasket(player,premium);}
    boolean awaitingInput(Player player){Input input=inputs.get(player.getUniqueId());if(input!=null&&input.expires()<System.currentTimeMillis()){inputs.remove(player.getUniqueId());return false;}return input!=null;}

    private void render(Player player,Session session){
        View view=session.view();Holder holder=new Holder(player.getUniqueId(),session.section);
        String title=switch(session.section){case SHOP->view.merchant?"Merchant Market":"Marketplace • Shop";case AUCTION->view.merchant?"Auctioneer":"Marketplace • Auction";case LUXURY->"Marketplace • Luxury";case SHARDS->"Marketplace • Shards";};
        Inventory inv=plugin.getServer().createInventory(holder,54,Component.text(title,session.section==Section.SHARDS?NamedTextColor.DARK_AQUA:NamedTextColor.DARK_GREEN));
        int total=switch(session.section){case SHOP,LUXURY->renderShop(player,inv,holder,view,session.section==Section.LUXURY);case AUCTION->renderAuctions(player,inv,holder,view);case SHARDS->renderShards(player,inv,holder,view);};
        int pages=Math.max(1,(total+PAGE_SIZE-1)/PAGE_SIZE),requestedPage=view.page;view.page=Math.max(0,Math.min(view.page,pages-1));
        if(requestedPage!=view.page){render(player,session);return;}
        if(session.section==Section.SHOP){inv.setItem(43,button(Material.HOPPER,"Sell Basket",List.of("Place items, review, and confirm.")));inv.setItem(44,button(Material.PAPER,"Selling",List.of("Right-click an item to sell.","Full value becomes 50% after its daily threshold.")));inv.setItem(46,button(Material.GOLD_INGOT,"Quick Sell",List.of("Sell every supported ordinary item","in your inventory.")));}
        else if(session.section==Section.AUCTION){inv.setItem(43,button(Material.ANVIL,"List Held Item",List.of("Hold the stack you want to list.")));inv.setItem(44,button(Material.HOPPER,"Collect Expired",List.of("Retrieve returned listings.")));}
        inv.setItem(45,button(Material.ARROW,"Previous",List.of("Page "+(view.page+1)+" / "+pages)));
        inv.setItem(47,button(Material.SPYGLASS,"Search / Filter",filterLore(view)));
        Section next=nextSection(session.section);
        inv.setItem(49,button(sectionIcon(next),"Switch to "+sectionName(next),List.of()));
        inv.setItem(51,button(Material.HOPPER,"Sort: "+sortName(view.sort),List.of("Click to change.")));
        inv.setItem(53,button(Material.ARROW,"Next",List.of("Page "+(view.page+1)+" / "+pages)));
        player.openInventory(inv);
    }

    private int renderShop(Player player,Inventory inv,Holder holder,View view,boolean luxury){
        List<Map.Entry<Material,ShopService.Price>> rows=new ArrayList<>(shop.entries(luxury));
        rows.removeIf(row->!matches(view,row.getKey(),row.getValue().display()));
        Comparator<Map.Entry<Material,ShopService.Price>> comparator=switch(view.sort){
            case CHEAPEST->Comparator.comparingDouble(row->row.getValue().buy());
            case EXPENSIVE->Comparator.<Map.Entry<Material,ShopService.Price>>comparingDouble(row->row.getValue().buy()).reversed();
            case NAME->Comparator.comparing(row->row.getValue().display(),String.CASE_INSENSITIVE_ORDER);
            case DEFAULT->luxury?Comparator.comparingDouble(row->row.getValue().buy()):null;
        };
        if(comparator!=null)rows.sort(comparator);
        int total=rows.size(),start=Math.max(0,view.page*PAGE_SIZE);double buyMultiplier=view.merchant?plugin.getConfig().getDouble("merchants.shop.buy-multiplier",.925):1,sellMultiplier=view.merchant?plugin.getConfig().getDouble("merchants.shop.sell-multiplier",1.075):1;
        for(int index=start,slot=0;index<rows.size()&&slot<PAGE_SIZE;index++,slot++){
            var row=rows.get(index);ShopService.Price price=row.getValue();ItemStack icon=shop.displayItem(row.getKey());ItemMeta meta=icon.getItemMeta();
            if(!luxury)meta.displayName(null);
            List<Component> lore=new ArrayList<>();lore.add(Component.text("Buy: "+CoreUtil.money(price.buy()*buyMultiplier)+(luxury?"":" each"),NamedTextColor.GREEN));if(!luxury)lore.add(Component.text("Sell: "+CoreUtil.money(price.sell()*sellMultiplier)+" each",NamedTextColor.YELLOW));if(!luxury)lore.add(Component.text("Hold shift to buy/sell in bulk",NamedTextColor.DARK_GRAY));meta.lore(lore);icon.setItemMeta(meta);inv.setItem(slot,icon);holder.items.put(slot,new ItemRef(row.getKey(),null,null));
        }
        return total;
    }

    private int renderAuctions(Player player,Inventory inv,Holder holder,View view){
        List<Database.AuctionRow> rows=new ArrayList<>(auctions.rows());String id=CoreUtil.id(player);
        rows.removeIf(row->(view.mine&&!row.seller().equals(id))||!view.query.isBlank()&&!plainName(row.item()).toLowerCase(Locale.ROOT).contains(view.query.toLowerCase(Locale.ROOT))||!view.seller.isBlank()&&!plugin.nicknames().displayName(row.sellerName()).toLowerCase(Locale.ROOT).contains(view.seller.toLowerCase(Locale.ROOT))||!"ALL".equals(view.category)&&!category(row.item().getType()).equals(view.category));
        Comparator<Database.AuctionRow> comparator=switch(view.sort){case CHEAPEST->Comparator.comparingDouble(Database.AuctionRow::price);case EXPENSIVE->Comparator.comparingDouble(Database.AuctionRow::price).reversed();case NAME->Comparator.comparing(row->plainName(row.item()),String.CASE_INSENSITIVE_ORDER);default->Comparator.comparingLong(Database.AuctionRow::listed).reversed();};rows.sort(comparator);
        int total=rows.size(),start=Math.max(0,view.page*PAGE_SIZE);
        for(int index=start,slot=0;index<rows.size()&&slot<PAGE_SIZE;index++,slot++){
            Database.AuctionRow row=rows.get(index);ItemStack icon=row.item().clone();ItemMeta meta=icon.getItemMeta();List<Component> lore=meta.hasLore()?new ArrayList<>(meta.lore()):new ArrayList<>();lore.add(Component.empty());lore.add(Component.text("Price: "+CoreUtil.money(row.price()),NamedTextColor.GOLD));lore.add(Component.text("Seller: "+plugin.nicknames().displayName(row.sellerName()),NamedTextColor.GRAY));long mins=Math.max(1,(row.expires()-System.currentTimeMillis())/60000);lore.add(Component.text("Expires: "+mins/60+"h "+mins%60+"m",NamedTextColor.DARK_GRAY));lore.add(Component.text(row.seller().equals(id)?"Click to cancel":"Click to purchase",row.seller().equals(id)?NamedTextColor.RED:NamedTextColor.GREEN));meta.lore(lore);icon.setItemMeta(meta);inv.setItem(slot,icon);holder.items.put(slot,new ItemRef(null,row.id(),null));
        }
        return total;
    }

    private int renderShards(Player player,Inventory inv,Holder holder,View view){
        List<ShardService.Stock> rows=new ArrayList<>(shards.stock());rows.removeIf(row->!view.query.isBlank()&&!row.name().toLowerCase(Locale.ROOT).contains(view.query.toLowerCase(Locale.ROOT))||!"ALL".equals(view.category)&&!row.category().name().equals(view.category));
        Comparator<ShardService.Stock> comparator=switch(view.sort){case CHEAPEST->Comparator.comparingInt(ShardService.Stock::price);case EXPENSIVE->Comparator.comparingInt(ShardService.Stock::price).reversed();case NAME->Comparator.comparing(ShardService.Stock::name,String.CASE_INSENSITIVE_ORDER);default->Comparator.comparing(ShardService.Stock::category).thenComparingInt(ShardService.Stock::price);};rows.sort(comparator);
        int total=rows.size(),start=view.page*PAGE_SIZE;
        for(int index=start,slot=0;index<rows.size()&&slot<PAGE_SIZE;index++,slot++){
            ShardService.Stock row=rows.get(index);int remaining=shards.remaining(player,row);ItemStack icon=shards.displayItem(row);ItemMeta meta=icon.getItemMeta();List<Component> lore=meta.hasLore()?new ArrayList<>(meta.lore()):new ArrayList<>();lore.add(Component.text("Price: "+row.price()+" Shards",NamedTextColor.AQUA));if(row.limit()>0)lore.add(Component.text(remaining+" remaining this "+("LIFETIME".equalsIgnoreCase(row.period())?"account":row.period().toLowerCase(Locale.ROOT)),NamedTextColor.GRAY));meta.lore(lore);icon.setItemMeta(meta);inv.setItem(slot,icon);holder.items.put(slot,new ItemRef(null,null,row.key()));
        }
        inv.setItem(44,button(Material.REDSTONE_BLOCK,"Your Shards",List.of(Integer.toString(shards.balance(player)))));
        return total;
    }

    @EventHandler public void click(InventoryClickEvent event){
        if(event.getInventory().getHolder(false) instanceof SearchHolder holder){searchClick(event,holder);return;}
        if(event.getInventory().getHolder(false) instanceof FilterHolder holder){filterClick(event,holder);return;}
        if(!(event.getInventory().getHolder(false) instanceof Holder holder))return;event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player)||!holder.player.equals(player.getUniqueId()))return;
        Session session=sessions.computeIfAbsent(player.getUniqueId(),id->new Session());session.section=holder.section;View view=session.view();int slot=event.getRawSlot();ItemRef item=holder.items.get(slot);
        if(item!=null){handleItem(player,session,item,event.isShiftClick(),event.isRightClick());return;}
        switch(slot){
            case 43->{if(session.section==Section.SHOP)shop.openSellBasket(player,view.merchant);else if(session.section==Section.AUCTION)prompt(player,InputType.LIST_PRICE);}
            case 44->{if(session.section==Section.AUCTION)auctions.collect(player);}
            case 46->{if(session.section==Section.SHOP)shop.requestQuickSell(player,view.merchant,()->render(player,session));}
            case 45->{view.page=Math.max(0,view.page-1);render(player,session);}
            case 47->openFilters(player,session);
            case 49->switchSection(player,session,nextSection(session.section),false);
            case 51->{view.sort=Sort.values()[(view.sort.ordinal()+1)%Sort.values().length];view.page=0;render(player,session);}
            case 53->{view.page++;render(player,session);}
            default->{}
        }
    }

    private void handleItem(Player player,Session session,ItemRef ref,boolean shift,boolean right){
        View view=session.view();
        if(session.section==Section.SHOP||session.section==Section.LUXURY){
            ShopService.Price price=shop.price(ref.material());if(price==null)return;int amount=shift&&session.section==Section.SHOP?16:1;double multiplier=view.merchant?plugin.getConfig().getDouble("merchants.shop.buy-multiplier",.925):1;
            if(right&&session.section==Section.SHOP){shop.sell(player,ref.material(),amount,view.merchant?plugin.getConfig().getDouble("merchants.shop.sell-multiplier",1.075):1);render(player,session);return;}
            double cost=Math.round(price.buy()*amount*multiplier*100)/100.0;boolean luxury=session.section==Section.LUXURY,mandatory=luxury&&cost>=plugin.getConfig().getDouble("confirmations.mandatory-luxury-price",2_000_000);
            plugin.confirmations().request(player,luxury?SettingsService.ConfirmationKind.LUXURY:SettingsService.ConfirmationKind.SHOP,mandatory,"Buy "+amount+" "+price.display(),List.of("Cost: "+CoreUtil.money(cost)),()->{shop.buy(player,ref.material(),amount,multiplier);render(player,session);},()->render(player,session));
        }else if(session.section==Section.AUCTION){
            Database.AuctionRow row=plugin.db().auction(ref.auction());if(row==null)return;if(row.seller().equals(CoreUtil.id(player))){auctions.cancel(player,row.id());render(player,session);return;}
            plugin.confirmations().request(player,SettingsService.ConfirmationKind.AUCTION,false,"Buy "+plainName(row.item()),List.of("Price: "+CoreUtil.money(row.price()),"Seller: "+plugin.nicknames().displayName(row.sellerName())),()->{auctions.buy(player,row.id(),view.merchant);render(player,session);},()->render(player,session));
        }else{
            ShardService.Stock stock=shards.stock(ref.shard());if(stock==null)return;plugin.confirmations().request(player,SettingsService.ConfirmationKind.SHARD,stock.mandatory(),"Buy "+stock.name(),List.of("Cost: "+stock.price()+" Shards"),()->{shards.purchase(player,stock);render(player,session);},()->render(player,session));
        }
    }

    private void switchSection(Player player,Session session,Section section,boolean merchant){session.section=section;resetSearch(session.view());session.view().merchant=merchant;render(player,session);}
    private void openFilters(Player player,Session session){
        Inventory inv=plugin.getServer().createInventory(new FilterHolder(player.getUniqueId(),session.section),45,Component.text("Marketplace Filters",NamedTextColor.DARK_GRAY));
        inv.setItem(10,button(Material.BARRIER,"All Categories",List.of()));
        List<String> categories=session.section==Section.SHARDS?Arrays.stream(ShardService.Category.values()).map(Enum::name).toList():List.of("BUILDING","WOOD","FOOD","ORES","COMBAT","REDSTONE","UTILITY");
        int slot=11;for(String category:categories)inv.setItem(slot++,button(categoryIcon(category),CoreUtil.pretty(category),List.of()));
        inv.setItem(28,button(Material.SPYGLASS,"Item Search",List.of("Search by item name.")));
        if(session.section==Section.AUCTION)inv.setItem(29,button(Material.PLAYER_HEAD,"Seller Search",List.of("Search by seller name.")));
        if(session.section==Section.AUCTION)inv.setItem(31,button(Material.NAME_TAG,session.view().mine?"Showing My Listings":"All Listings",List.of("Click to toggle.")));
        inv.setItem(36,button(Material.MILK_BUCKET,"Clear Search",List.of()));
        inv.setItem(40,button(Material.ARROW,"Back",List.of()));player.openInventory(inv);
    }
    private void filterClick(InventoryClickEvent event,FilterHolder holder){
        event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player)||!holder.player.equals(player.getUniqueId()))return;Session session=sessions.computeIfAbsent(player.getUniqueId(),id->new Session());session.section=holder.section;View view=session.view();int slot=event.getRawSlot();
        List<String> categories=session.section==Section.SHARDS?Arrays.stream(ShardService.Category.values()).map(Enum::name).toList():List.of("BUILDING","WOOD","FOOD","ORES","COMBAT","REDSTONE","UTILITY");
        if(slot==10){view.category="ALL";view.page=0;render(player,session);}
        else if(slot>=11&&slot<11+categories.size()){view.category=categories.get(slot-11);view.page=0;render(player,session);}
        else if(slot==28)prompt(player,InputType.ITEM);
        else if(slot==29&&session.section==Section.AUCTION)prompt(player,InputType.SELLER);
        else if(slot==31&&session.section==Section.AUCTION){view.mine=!view.mine;view.page=0;render(player,session);}
        else if(slot==36){view.query="";view.seller="";view.category="ALL";view.mine=false;view.page=0;render(player,session);}
        else if(slot==40)render(player,session);
    }

    private void prompt(Player player,InputType type){
        Session session=sessions.computeIfAbsent(player.getUniqueId(),id->new Session());
        if(type!=InputType.LIST_PRICE&&nativeDialogsSupported(player)&&nativeSearch(player,type,session.section))return;
        inputs.put(player.getUniqueId(),new Input(type,session.section,System.currentTimeMillis()+30000));
        if(plugin.isBedrock(player)&&bedrockInput(player,type))return;
        if(type!=InputType.LIST_PRICE){openAnvilSearch(player,type,session.section);return;}
        player.closeInventory();CoreUtil.msg(player,"Type the listing price in chat, or 'cancel'.");
    }
    private boolean bedrockInput(Player player,InputType type){
        try{GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());if(connection==null)return false;CustomForm.Builder form=CustomForm.builder().title(type==InputType.LIST_PRICE?"List Held Item":"Marketplace Search").input(type==InputType.LIST_PRICE?"Price":"Search","Type here","");
            form.validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->applyInput(player,response.asInput(0))));form.closedResultHandler(()->inputs.remove(player.getUniqueId()));return connection.sendForm(form);}catch(Throwable error){return false;}
    }
    private boolean nativeDialogsSupported(Player player){return plugin.getConfig().getBoolean("settings.native-dialogs",true)&&player.getProtocolVersion()==Bukkit.getUnsafe().getProtocolVersion();}
    @SuppressWarnings("UnstableApiUsage")
    private boolean nativeSearch(Player player,InputType type,Section section){
        try{
            View view=sessions.computeIfAbsent(player.getUniqueId(),id->new Session()).views.computeIfAbsent(section,key->new View());
            String initial=type==InputType.SELLER?view.seller:view.query;
            ActionButton apply=ActionButton.create(Component.text("Search"),Component.empty(),150,DialogAction.customClick((response,audience)->{String search=response.getText("search");plugin.getServer().getScheduler().runTask(plugin,()->applySearch(player,type,section,search));},ClickCallback.Options.builder().uses(1).build()));
            Dialog dialog=Dialog.create(builder->builder.empty().base(DialogBase.builder(Component.text(type==InputType.SELLER?"Seller Search":"Item Search",NamedTextColor.GOLD)).inputs(List.of(DialogInput.text("search",Component.text("Search")).initial(initial).maxLength(64).width(300).build())).canCloseWithEscape(true).pause(false).build()).type(DialogType.notice(apply)));
            player.showDialog(dialog);return true;
        }catch(Throwable error){return false;}
    }
    private void openAnvilSearch(Player player,InputType type,Section section){
        Inventory inventory=plugin.getServer().createInventory(new SearchHolder(player.getUniqueId(),type,section),InventoryType.ANVIL,Component.text(type==InputType.SELLER?"Seller Search":"Item Search",NamedTextColor.DARK_GRAY));
        View view=sessions.computeIfAbsent(player.getUniqueId(),id->new Session()).views.computeIfAbsent(section,key->new View());String initial=type==InputType.SELLER?view.seller:view.query;
        ItemStack paper=button(Material.PAPER,initial.isBlank()?"Search":initial,List.of("Enter text, then take the result."));inventory.setItem(0,paper);player.openInventory(inventory);
    }
    @EventHandler public void prepareAnvil(PrepareAnvilEvent event){
        if(!(event.getInventory().getHolder(false) instanceof SearchHolder))return;String text=event.getInventory().getRenameText();event.setResult(button(Material.SPYGLASS,text==null||text.isBlank()?"Clear Search":text,List.of("Click to apply.")));
    }
    private void searchClick(InventoryClickEvent event,SearchHolder holder){
        event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player)||!holder.player.equals(player.getUniqueId()))return;
        if(event.getRawSlot()!=2)return;String text=event.getInventory() instanceof AnvilInventory anvil?anvil.getRenameText():"";inputs.remove(player.getUniqueId());event.getInventory().clear();player.closeInventory();applySearch(player,holder.type,holder.section,text);
    }
    @SuppressWarnings("deprecation")
    @EventHandler(priority=EventPriority.HIGHEST) public void chat(AsyncPlayerChatEvent event){
        if(!awaitingInput(event.getPlayer()))return;event.setCancelled(true);String text=event.getMessage();plugin.getServer().getScheduler().runTask(plugin,()->applyInput(event.getPlayer(),text));
    }
    @EventHandler public void quit(PlayerQuitEvent event){sessions.remove(event.getPlayer().getUniqueId());inputs.remove(event.getPlayer().getUniqueId());}
    @EventHandler public void close(InventoryCloseEvent event){
        if(!(event.getPlayer() instanceof Player player))return;
        if(event.getInventory().getHolder(false) instanceof SearchHolder){inputs.remove(player.getUniqueId());event.getInventory().clear();}
        // Explicit Marketplace opens clear search; internal GUI transitions retain it.
    }
    private void applyInput(Player player,String text){
        Input input=inputs.remove(player.getUniqueId());if(input==null)return;Session session=sessions.computeIfAbsent(player.getUniqueId(),id->new Session());session.section=input.section();View view=session.view();String value=text==null?"":text.trim();
        if(value.equalsIgnoreCase("cancel")){render(player,session);return;}
        if(input.type()==InputType.LIST_PRICE){double price=CoreUtil.parseMoney(value);if(price<=0)CoreUtil.error(player,"That is not a valid price.");else auctions.list(player,price);}
        else if(input.type()==InputType.ITEM)view.query=value.equalsIgnoreCase("clear")?"":value.toLowerCase(Locale.ROOT);
        else view.seller=value.equalsIgnoreCase("clear")?"":value.toLowerCase(Locale.ROOT);
        view.page=0;render(player,session);
    }
    private void applySearch(Player player,InputType type,Section section,String text){
        Session session=sessions.computeIfAbsent(player.getUniqueId(),id->new Session());session.section=section;View view=session.view();String value=text==null?"":text.trim().toLowerCase(Locale.ROOT);
        if(type==InputType.SELLER)view.seller=value;else view.query=value;view.page=0;render(player,session);
    }

    private boolean matches(View view,Material material,String display){return(view.query.isBlank()||display.toLowerCase(Locale.ROOT).contains(view.query.toLowerCase(Locale.ROOT))||material.name().toLowerCase(Locale.ROOT).contains(view.query.toLowerCase(Locale.ROOT)))&&("ALL".equals(view.category)||category(material).equals(view.category));}
    private String category(Material material){String name=material.name();if(name.endsWith("_LOG")||name.endsWith("_WOOD")||name.endsWith("_PLANKS"))return"WOOD";if(name.contains("STONE")||Set.of(Material.COBBLESTONE,Material.DIRT,Material.SAND,Material.GRAVEL,Material.GLASS,Material.OBSIDIAN).contains(material))return"BUILDING";if(material.isEdible()||name.contains("SEED")||Set.of(Material.CARROT,Material.POTATO,Material.SUGAR_CANE).contains(material))return"FOOD";if(name.contains("INGOT")||Set.of(Material.COAL,Material.CHARCOAL,Material.QUARTZ,Material.LAPIS_LAZULI).contains(material))return"ORES";if(Set.of(Material.REDSTONE,Material.SLIME_BALL).contains(material))return"REDSTONE";if(name.contains("SWORD")||name.contains("BOW")||name.contains("ARROW")||Set.of(Material.GUNPOWDER,Material.BONE,Material.STRING,Material.ENDER_PEARL,Material.BLAZE_ROD).contains(material))return"COMBAT";return"UTILITY";}
    private Material categoryIcon(String category){return switch(category){case"BUILDING"->Material.BRICKS;case"WOOD"->Material.OAK_LOG;case"FOOD"->Material.APPLE;case"ORES"->Material.IRON_INGOT;case"COMBAT","WEAPONS"->Material.IRON_SWORD;case"REDSTONE"->Material.REDSTONE;case"TOOLS"->Material.DIAMOND_PICKAXE;case"ARMOR"->Material.DIAMOND_CHESTPLATE;case"COSMETIC"->Material.AMETHYST_SHARD;default->Material.BUNDLE;};}
    private String plainName(ItemStack item){
        if(item.hasItemMeta()&&item.getItemMeta().hasDisplayName()){
            Component display=item.getItemMeta().displayName();
            if(display!=null){
                String plain=PlainTextComponentSerializer.plainText().serialize(display).trim();
                if(!plain.isBlank())return plain;
            }
        }
        return CoreUtil.pretty(item.getType().name());
    }
    boolean selfTest(){return PAGE_SIZE==43&&Section.values().length==4&&Sort.values().length==4&&shop.entries(false).stream().noneMatch(entry->entry.getValue().buy()<entry.getValue().sell());}
    private void resetSearch(View view){view.query="";view.seller="";view.page=0;}
    private Section nextSection(Section section){return switch(section){case SHOP->Section.LUXURY;case LUXURY->Section.SHARDS;case SHARDS->Section.AUCTION;case AUCTION->Section.SHOP;};}
    private String sectionName(Section section){return switch(section){case SHOP->"Normal Shop";case LUXURY->"Luxury Shop";case SHARDS->"Shard Shop";case AUCTION->"Auction House";};}
    private Material sectionIcon(Section section){return switch(section){case SHOP->Material.EMERALD;case LUXURY->Material.AMETHYST_SHARD;case SHARDS->Material.ECHO_SHARD;case AUCTION->Material.CHEST;};}
    private String sortName(Sort sort){return switch(sort){case DEFAULT->"Default / Newest";case CHEAPEST->"Cheapest";case EXPENSIVE->"Most Expensive";case NAME->"Name A–Z";};}
    private List<String> filterLore(View view){List<String> lore=new ArrayList<>();lore.add("Category: "+CoreUtil.pretty(view.category));if(!view.query.isBlank())lore.add("Item: "+view.query);if(!view.seller.isBlank())lore.add("Seller: "+view.seller);return lore;}
    private ItemStack nav(Material material,String name,boolean selected){return button(selected?Material.LIME_STAINED_GLASS_PANE:material,name,List.of(selected?"Current section":"Open section"));}
    private ItemStack button(Material material,String name,List<String> lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(name,NamedTextColor.GOLD));meta.lore(lore.stream().map(line->Component.text(line,NamedTextColor.GRAY)).toList());item.setItemMeta(meta);return item;}
}
