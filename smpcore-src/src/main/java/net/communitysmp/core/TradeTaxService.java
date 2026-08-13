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
    private Method getTrade,getPlayer1,getPlayer2,getPlayerOf,getCurrencies,hookName;

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
            /** Read the whole currency map and identify each entry by its hook's own name, rather than
             *  asking for a currency by a name we guessed.
             *
             *  The previous version called getCurrency("money"), and AxTrade resolves that through
             *  HookManager.getCurrencyHook(name) which returns null for an unknown name -- and the hooks are
             *  actually called "Vault" and "Experience". A null hook makes getCurrency return 0.0, so every
             *  money trade was taxed on zero and the fee silently never applied. Enumerating the map means
             *  the currency name can be anything the server has configured and this still works. */
            getCurrencies=tradePlayerClass.getMethod("getCurrencies");
            hookName=Class.forName("com.artillexstudios.axtrade.hooks.currency.CurrencyHook").getMethod("getName");
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
            Charge chargeA=chargeFor(first),chargeB=chargeFor(second);
            if(chargeA.total()<=0&&chargeB.total()<=0)return;
            if(!canPay(a,chargeA.total())||!canPay(b,chargeB.total())){
                setCancelled(event,true);
                if(!canPay(a,chargeA.total()))CoreUtil.error(a,"Trade cancelled — you cannot cover the "+CoreUtil.money(chargeA.total())+" transaction fee.");
                if(!canPay(b,chargeB.total()))CoreUtil.error(b,"Trade cancelled — you cannot cover the "+CoreUtil.money(chargeB.total())+" transaction fee.");
                return;
            }
            /** Guard against being taxed twice for one trade. AxTrade fires AxTradeCompleteEvent from a
             *  single place per trade, so this should never trigger -- it exists so that a change on their
             *  side, or any replay of the event, cannot silently double-charge real money. */
            if(!taxedOnce(trade))return;
            collect(a,chargeA);
            collect(b,chargeB);
        }catch(Throwable error){
            /** Never let a tax failure break somebody's trade. */
            plugin.getLogger().warning("[TradeTax] Skipped taxing a trade after an unexpected error: "+error);
        }
    }
    /** What one side is handing over, and what each part of it costs them, kept together so the player can
     *  be told exactly how the fee was arrived at instead of just a total. */
    private record Charge(double money,double xp,double moneyTax,double xpFee){
        double total(){return moneyTax+xpFee;}
    }
    /** Money tax plus the XP charge, for one side of the trade. */
    private Charge chargeFor(Object tradePlayer){
        double money=0,xp=0;
        java.util.List<String> xpHooks=plugin.getConfig().getStringList("trade-tax.experience-hooks");
        if(xpHooks.isEmpty())xpHooks=java.util.List.of("Experience");
        for(java.util.Map.Entry<?,?> entry:currencies(tradePlayer).entrySet()){
            double amount=entry.getValue() instanceof Number number?number.doubleValue():0;
            if(amount<=0)continue;
            String name=hookNameOf(entry.getKey());
            /** Anything that is not an XP hook is money of some kind, so a server that swaps Vault for
             *  another economy keeps being taxed instead of quietly stopping. */
            if(xpHooks.stream().anyMatch(hook->hook.equalsIgnoreCase(name)))xp+=amount;else money+=amount;
        }
        /** 1% of the money one side hands over, and nothing else on the money leg -- this REPLACES the old
         *  rate rather than sitting alongside it. The default no longer falls back to the auction sale tax:
         *  that made removing the key silently reinstate a 5% charge. */
        double taxPercent=plugin.getConfig().getDouble("trade-tax.percent",1.0);
        double perXp=plugin.getConfig().getDouble("trade-tax.money-per-100-xp",10)/100.0;
        return new Charge(money,xp,Math.max(0,money*taxPercent/100.0),Math.max(0,xp*perXp));
    }
    /** Remembers the trades already settled, so one trade can only ever be charged once. Bounded: a trade
     *  is recorded at commit and the set is trimmed, so this cannot grow over a long uptime. */
    private final java.util.Set<Integer> taxed=java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<>(){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Integer,Boolean> eldest){return size()>256;}
    });
    private boolean taxedOnce(Object trade){return taxed.add(System.identityHashCode(trade));}
    private java.util.Map<?,?> currencies(Object tradePlayer){
        try{Object value=getCurrencies.invoke(tradePlayer);return value instanceof java.util.Map<?,?> map?map:java.util.Map.of();}
        catch(Throwable ignored){return java.util.Map.of();}
    }
    private String hookNameOf(Object hook){
        try{return String.valueOf(hookName.invoke(hook));}catch(Throwable ignored){return "";}
    }
    private boolean canPay(Player player,double amount){return amount<=0||db().player(CoreUtil.id(player)).balance()>=amount;}
    private void collect(Player player,Charge charge){
        double amount=charge.total();
        if(amount<=0)return;
        if(!db().changeBalance(CoreUtil.id(player),-amount))return;
        plugin.bank().creditFee(amount,CoreUtil.id(player),"DIRECT_TRADE");
        db().recordEconomy(CoreUtil.id(player),"TRADE_TAX",-amount,"AXTRADE");
        /** Spell out what was charged and on what. A bare total left players unsure whether the fee applied
         *  to the money, the XP, or the items, and unsure what they actually ended up with. */
        StringBuilder detail=new StringBuilder();
        double percent=plugin.getConfig().getDouble("trade-tax.percent",1.0);
        if(charge.moneyTax()>0)detail.append(CoreUtil.money(charge.moneyTax())).append(" (").append(trim(percent)).append("% of ").append(CoreUtil.money(charge.money())).append(")");
        if(charge.xpFee()>0){if(detail.length()>0)detail.append(" + ");detail.append(CoreUtil.money(charge.xpFee())).append(" (").append((long)charge.xp()).append(" XP)");}
        CoreUtil.msg(player,"Trade tax: "+CoreUtil.money(amount)+" — "+detail+".");
        if(charge.money()>0)CoreUtil.msg(player,"You handed over "+CoreUtil.money(charge.money())+", so this trade cost you "+CoreUtil.money(charge.money()+amount)+" in total. Balance: "+CoreUtil.money(db().player(CoreUtil.id(player)).balance())+".");
        else CoreUtil.msg(player,"Balance: "+CoreUtil.money(db().player(CoreUtil.id(player)).balance())+".");
    }
    private String trim(double value){return value==Math.rint(value)?String.valueOf((long)value):String.valueOf(value);}
    private void setCancelled(Object event,boolean cancelled){
        try{event.getClass().getMethod("setCancelled",boolean.class).invoke(event,cancelled);}catch(Throwable ignored){}
    }
    private Database db(){return plugin.db();}
    void shutdown(){if(active)HandlerList.unregisterAll(this);}
}
