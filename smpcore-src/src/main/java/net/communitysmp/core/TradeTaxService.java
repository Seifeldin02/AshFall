package net.communitysmp.core;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;

/** Applies Ashfall's normal transaction tax to completed AxTrade direct trades, and charges for XP moved
 *  through a trade.
 *
 *  Deliberately reflective rather than a compile-time dependency: AxTrade is not published to any Maven
 *  repository we build against, so a hard import would mean vendoring its jar into the build and would make
 *  SMPCore fail to load outright on a server without AxTrade installed. Reflection keeps AxTrade genuinely
 *  optional — if the plugin, the event class, or any accessor is missing, this service simply does nothing
 *  and says so once in the log, and every other part of SMPCore is unaffected.
 *
 *  The hook point is AxTradeCompleteEvent, which AxTrade fires at final commit and which is Cancellable —
 *  so this is a real commit hook, not inference from GUI state. If a party cannot pay, the trade is
 *  cancelled outright rather than being allowed through untaxed. */
final class TradeTaxService implements Listener {
    private final SMPCore plugin;
    private boolean active;
    private Method getTrade,getPlayer1,getPlayer2,getPlayerOf,getCurrency;

    TradeTaxService(SMPCore plugin){
        this.plugin=plugin;
        if(!plugin.getConfig().getBoolean("trade-tax.enabled",true))return;
        if(plugin.getServer().getPluginManager().getPlugin("AxTrade")==null)return;
        try{
            Class<?> eventClass=Class.forName("com.artillexstudios.axtrade.api.events.AxTradeCompleteEvent");
            Class<?> tradeClass=Class.forName("com.artillexstudios.axtrade.trade.Trade");
            Class<?> tradePlayerClass=Class.forName("com.artillexstudios.axtrade.trade.TradePlayer");
            getTrade=eventClass.getMethod("getTrade");
            getPlayer1=tradeClass.getMethod("getPlayer1");
            getPlayer2=tradeClass.getMethod("getPlayer2");
            getPlayerOf=tradePlayerClass.getMethod("getPlayer");
            getCurrency=tradePlayerClass.getMethod("getCurrency",String.class);
            @SuppressWarnings("unchecked")
            Class<? extends Event> typed=(Class<? extends Event>)eventClass;
            EventExecutor executor=(listener,event)->handle(event);
            plugin.getServer().getPluginManager().registerEvent(typed,this,EventPriority.HIGH,executor,plugin,true);
            active=true;
            plugin.getLogger().info("[TradeTax] AxTrade detected — direct-trade tax and XP charges are active.");
        }catch(Throwable error){
            plugin.getLogger().warning("[TradeTax] AxTrade is installed but its API did not match what this build expects ("+error.getClass().getSimpleName()+": "+error.getMessage()+"). Trade tax is disabled; trades are unaffected.");
        }
    }
    boolean isActive(){return active;}

    private void handle(Event event){
        if(!active)return;
        try{
            Object trade=getTrade.invoke(event);
            Object first=getPlayer1.invoke(trade),second=getPlayer2.invoke(trade);
            if(first==null||second==null)return;
            Player a=(Player)getPlayerOf.invoke(first),b=(Player)getPlayerOf.invoke(second);
            if(a==null||b==null)return;
            /** Each side pays only on what THEY are handing over, so a one-sided gift taxes the giver and
             *  a two-way trade taxes both proportionally — the same principle as the auction sale tax. */
            double chargeA=chargeFor(first),chargeB=chargeFor(second);
            if(chargeA<=0&&chargeB<=0)return;
            if(!canPay(a,chargeA)||!canPay(b,chargeB)){
                setCancelled(event,true);
                if(!canPay(a,chargeA))CoreUtil.error(a,"Trade cancelled — you cannot cover the "+CoreUtil.money(chargeA)+" transaction fee.");
                if(!canPay(b,chargeB))CoreUtil.error(b,"Trade cancelled — you cannot cover the "+CoreUtil.money(chargeB)+" transaction fee.");
                return;
            }
            collect(a,chargeA);
            collect(b,chargeB);
        }catch(Throwable error){
            /** Never let a tax failure break somebody's trade. */
            plugin.getLogger().warning("[TradeTax] Skipped taxing a trade after an unexpected error: "+error);
        }
    }
    /** Money tax plus the XP charge, for one side of the trade. */
    private double chargeFor(Object tradePlayer){
        double money=currency(tradePlayer,plugin.getConfig().getString("trade-tax.money-currency","money"));
        double xp=currency(tradePlayer,plugin.getConfig().getString("trade-tax.xp-currency","exp"));
        double taxPercent=plugin.getConfig().getDouble("trade-tax.percent",plugin.getConfig().getDouble("auctions.sale-tax-percent",5));
        double perXp=plugin.getConfig().getDouble("trade-tax.money-per-100-xp",10)/100.0;
        return Math.max(0,money*taxPercent/100.0)+Math.max(0,xp*perXp);
    }
    private double currency(Object tradePlayer,String key){
        if(key==null||key.isBlank())return 0;
        try{Object value=getCurrency.invoke(tradePlayer,key);return value instanceof Number number?Math.max(0,number.doubleValue()):0;}
        catch(Throwable ignored){return 0;}
    }
    private boolean canPay(Player player,double amount){return amount<=0||db().player(CoreUtil.id(player)).balance()>=amount;}
    private void collect(Player player,double amount){
        if(amount<=0)return;
        if(!db().changeBalance(CoreUtil.id(player),-amount))return;
        plugin.bank().creditFee(amount,CoreUtil.id(player),"DIRECT_TRADE");
        db().recordEconomy(CoreUtil.id(player),"TRADE_TAX",-amount,"AXTRADE");
        CoreUtil.msg(player,"Trade fee: "+CoreUtil.money(amount)+".");
    }
    private void setCancelled(Object event,boolean cancelled){
        try{event.getClass().getMethod("setCancelled",boolean.class).invoke(event,cancelled);}catch(Throwable ignored){}
    }
    private Database db(){return plugin.db();}
    void shutdown(){if(active)HandlerList.unregisterAll(this);}
}
