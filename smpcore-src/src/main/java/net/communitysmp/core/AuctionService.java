package net.communitysmp.core;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

final class AuctionService {
    private final SMPCore plugin;private final Database db;private final Map<UUID,Long> merchantSessions=new HashMap<>();
    AuctionService(SMPCore plugin){this.plugin=plugin;this.db=plugin.db();}
    void open(Player p){plugin.marketplace().open(p,MarketplaceService.Section.AUCTION);}
    void openFromMerchant(Player p){merchantSessions.put(p.getUniqueId(),System.currentTimeMillis()+300000L);plugin.marketplace().openAuctionMerchant(p);}
    boolean command(Player p,String[] args){if(args.length==0){open(p);return true;}switch(args[0].toLowerCase(Locale.ROOT)){case "sell"->{if(args.length<2){CoreUtil.error(p,"Usage: /ah sell <price>");return true;}list(p,CoreUtil.parseMoney(args[1]));}case "collect"->collect(p);case "cancel"->{if(args.length<2){CoreUtil.error(p,"Usage: /ah cancel <listing-id>");return true;}try{cancel(p,Long.parseLong(args[1]));}catch(NumberFormatException e){CoreUtil.error(p,"Invalid listing id.");}}default->CoreUtil.error(p,"Usage: /ah [sell <price>|collect|cancel <id>]");}return true;}
    /*  The listing fee is charged BEFORE anything is listed, and it is a percentage.
     *
     *  At the default 3%, listing something for 20,000,000 costs 600,000 up front -- taken silently, with no
     *  prompt and no warning, on the first line that touches money. Reported live: a player was charged
     *  600k, his entire balance, for a listing he had not been told the price of.
     *
     *  So the quote is now shown first and nothing at all happens until it is accepted. Cancelling cannot
     *  charge, list, consume the held item or duplicate it, because on the cancel path not one of those
     *  lines has run yet. The fee is deliberately spelled out on its own line in the prompt. */
    void list(Player p,double price){
        double min=plugin.getConfig().getDouble("auctions.minimum-price",10);
        if(price<min){CoreUtil.error(p,"Minimum auction price is "+CoreUtil.money(min)+".");return;}
        ItemStack held=p.getInventory().getItemInMainHand();
        if(held.getType().isAir()){CoreUtil.error(p,"Hold the stack you want to list.");return;}
        double factor=merchantActive(p)?feeMultiplier():1;
        double fee=listingFee(price,factor);
        ItemStack quoted=held.clone();
        /** The toggle is the ONLY authority here -- deliberately never mandatory, whatever the fee.
         *
         *  It defaults on, so the 600k surprise cannot happen to anyone who has not gone looking for the
         *  switch. But once somebody turns it off they have said they know what listing costs, and having
         *  it reappear at some hidden price threshold would make the setting a lie. High-value listings are
         *  exactly the ones a trader repeats most, so a forced prompt there is the most annoying place to
         *  put one. */
        plugin.confirmations().request(p,SettingsService.ConfirmationKind.AUCTION_LIST,false,
                "List "+quoted.getAmount()+"x "+CoreUtil.pretty(quoted.getType().name()),
                java.util.List.of("Asking price: "+CoreUtil.money(price),
                        "Listing fee charged now: "+CoreUtil.money(fee),
                        "The fee is not refunded if it does not sell."),
                ()->listConfirmed(p,price,quoted),
                ()->{});
    }

    private double listingFee(double price,double factor){
        return Math.max(1,price*plugin.getConfig().getDouble("auctions.listing-fee-percent",3)*factor/100.0)*plugin.bank().feeFactor();
    }

    /** Everything is re-validated against the CURRENT held item, because the confirmation screen is a tick
     *  or a minute later and the hand can have changed. If it is no longer the same stack the listing is
     *  refused outright rather than listing whatever happens to be held now. */
    private void listConfirmed(Player p,double price,ItemStack quoted){
        double min=plugin.getConfig().getDouble("auctions.minimum-price",10);if(price<min){CoreUtil.error(p,"Minimum auction price is "+CoreUtil.money(min)+".");return;}int max=plugin.getConfig().getInt("auctions.max-active-per-player",30);if(db.activeAuctionCount(CoreUtil.id(p))>=max){CoreUtil.error(p,"You already have "+max+" active listings.");return;}ItemStack held=p.getInventory().getItemInMainHand();if(held.getType().isAir()){CoreUtil.error(p,"Hold the stack you want to list.");return;}
        if(!held.isSimilar(quoted)||held.getAmount()!=quoted.getAmount()){CoreUtil.error(p,"You are no longer holding what you agreed to list. Nothing was charged.");return;}if(plugin.graves()!=null&&plugin.graves().isCompass(held)){CoreUtil.error(p,"Bound Grave Compasses cannot be auctioned.");return;}if(plugin.shards()!=null&&plugin.shards().bound(held)){CoreUtil.error(p,"Shard rewards are account-bound and cannot be auctioned.");return;}double factor=merchantActive(p)?feeMultiplier():1;double fee=listingFee(price,factor);if(!plugin.bank().payServer(p,fee,"FEE","AUCTION_LISTING")){CoreUtil.error(p,"You need the "+CoreUtil.money(fee)+" listing fee.");return;}ItemStack stored=held.clone();p.getInventory().setItemInMainHand(null);try{long expiry=System.currentTimeMillis()+plugin.getConfig().getLong("auctions.expiry-hours",72)*3600000L;long id=db.createAuction(CoreUtil.id(p),p.getName(),stored,price,expiry,fee);db.recordEconomy(CoreUtil.id(p),"AUCTION_FEE",-fee,"LISTING");CoreUtil.msg(p,"Listed as #"+id+" for "+CoreUtil.money(price)+". Fee paid: "+CoreUtil.money(fee)+".");}catch(RuntimeException ex){plugin.bank().refundServerPayment(p,fee,"FEE","AUCTION_LISTING_FAILED");CoreUtil.give(p,stored);CoreUtil.error(p,"Listing failed safely; your item and fee were returned.");}}
    void buy(Player buyer,long id,boolean merchant){Database.AuctionRow row=db.auction(id);if(row==null||!row.status().equals("ACTIVE")){CoreUtil.error(buyer,"That listing is no longer active.");plugin.settings().marketSound(buyer,"failed");return;}if(row.seller().equals(CoreUtil.id(buyer))){CoreUtil.error(buyer,"Use /ah cancel "+id+" for your own listing.");plugin.settings().marketSound(buyer,"failed");return;}if(row.price()>=plugin.getConfig().getDouble("bank.overdue-high-value-threshold",25000)&&!plugin.bank().allowNonessential(buyer,"high-value auction purchases")){plugin.settings().marketSound(buyer,"failed");return;}if(!ShopService.canFit(buyer,row.item())){CoreUtil.error(buyer,"Make enough inventory space first.");plugin.settings().marketSound(buyer,"failed");return;}double tax=row.price()*plugin.getConfig().getDouble("auctions.sale-tax-percent",5)*(merchant?feeMultiplier():1)/100.0;/* One commit: the listing is consumed, the buyer is debited, the seller and the fee are paid, and the item lands in the buyer OWNED durable claim stash. Handing it to their inventory below is a convenience on top of a record that already exists, so nothing can be lost in between. */if(!db.auctionSettle(id,CoreUtil.id(buyer),row.seller(),row.price(),tax,row.item(),"AUCTION_SALE_TAX")){CoreUtil.error(buyer,"That listing is gone, or you cannot afford it.","Nothing was charged. /ah shows what is still listed.");plugin.settings().marketSound(buyer,"failed");return;}db.recordEconomy(row.seller(),"AUCTION_FEE",-tax,"SALE");int held=deliverStash(buyer);plugin.relics().transferOnSale(row.item(),buyer);CoreUtil.ok(buyer,"Bought listing #"+id+" · "+CoreUtil.money(row.price())+(tax>0?" · seller paid "+CoreUtil.money(row.price()-tax)+" after "+CoreUtil.money(tax)+" tax":"")+".");if(held>0)CoreUtil.hint(buyer,"No room for it right now — it is waiting in /orders.");plugin.settings().marketSound(buyer,"purchase");if(plugin.spectacle()!=null)plugin.spectacle().bigSpend(buyer,row.price(),CoreUtil.pretty(row.item().getType().name()));Player seller=find(row.seller());if(seller!=null&&plugin.settings().auctionNotifications(seller))CoreUtil.msg(seller,"Listing #"+id+" sold. You received "+CoreUtil.money(row.price()-tax)+" after tax.");}
    /** One-line summary on login of listings that expired while the player was away.
     *
     *  Computed from current state rather than from cached events. An event cache has to catch every
     *  expiry, survive restarts and be cleared exactly once; reading what is actually sitting in escrow
     *  cannot miss anything and cannot deliver twice, and it collapses to a single line no matter how many
     *  listings expired. Expiry itself is lazy (expireAuctions runs inside the query), so simply asking is
     *  also what forces the state to be up to date. */
    void loginSummary(Player player){
        List<Database.AuctionRow> rows=db.collectibleAuctions(CoreUtil.id(player));
        if(rows.isEmpty())return;
        double value=rows.stream().mapToDouble(Database.AuctionRow::price).sum();
        CoreUtil.msg(player,rows.size()+" auction listing"+(rows.size()==1?"":"s")+" expired while you were away ("
                +CoreUtil.money(value)+" of goods). Use /ah collect to reclaim them.");
    }
    void collect(Player p){
        List<Database.AuctionRow> rows=db.collectibleAuctions(CoreUtil.id(p));
        if(rows.isEmpty()){CoreUtil.msg(p,"You have no expired listings to collect.");return;}
        /*  Reclaiming moves the item from the listing into the durable stash in ONE commit, and out of the
         *  stash into the inventory afterwards.
         *
         *  It used to flip the row to COLLECTED and then call addItem, discarding the leftovers addItem
         *  returns -- so a crash in between, or an inventory that filled up after the fit check, destroyed
         *  the item with nothing anywhere noticing. Every listing is reclaimed now regardless of space;
         *  whatever does not fit simply stays claimable. */
        int count=0;
        for(Database.AuctionRow row:rows)if(db.auctionReclaim(row.id(),CoreUtil.id(p),row.item()))count++;
        int held=deliverStash(p);
        CoreUtil.ok(p,"Reclaimed "+count+" expired listing"+(count==1?"":"s")+".");
        if(held>0)CoreUtil.hint(p,held+" would not fit and is waiting in /orders.");
    }

    /*  Move whatever is owed to this player out of the durable stash and into their hands.
     *
     *  Best effort by design: anything that does not fit goes straight back into the stash, so a claim
     *  survives a full inventory, a disconnect or a restart. Returns how much is still owed. */
    private int deliverStash(Player player){
        java.util.List<org.bukkit.inventory.ItemStack> owed=db.stashTake(CoreUtil.id(player));
        int left=0;
        for(org.bukkit.inventory.ItemStack item:owed){
            if(item==null||item.getType().isAir())continue;
            for(org.bukkit.inventory.ItemStack over:player.getInventory().addItem(item).values()){
                db.stashAddItem(CoreUtil.id(player),over);
                left++;
            }
        }
        return left;
    }
    void cancel(Player p,long id){Database.AuctionRow row=db.auction(id);if(row!=null&&row.seller().equals(CoreUtil.id(p))&&db.cancelAuction(id,CoreUtil.id(p))){CoreUtil.msg(p,"Listing cancelled. The listing fee is not refunded; use /ah collect for the item.");}else CoreUtil.error(p,"Active listing not found or not yours.");}
    List<Database.AuctionRow> rows(){return db.activeAuctions();}
    private Player find(String id){for(Player p:plugin.getServer().getOnlinePlayers())if(CoreUtil.id(p).equals(id))return p;return null;}
    private boolean merchantActive(Player p){long until=merchantSessions.getOrDefault(p.getUniqueId(),0L);if(until<System.currentTimeMillis()){merchantSessions.remove(p.getUniqueId());return false;}return true;}
    private double feeMultiplier(){return plugin.getConfig().getDouble("merchants.auction-fee-multiplier",.5);}
}
