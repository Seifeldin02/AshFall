package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class BankService implements Listener {
    private enum Page { MAIN, BORROW, REPAY, REPAY_CONFIRM }
    private enum InputKind { BORROW, REPAY }
    private enum InputStage { AMOUNT, CONFIRM }
    private record Pending(InputKind kind,InputStage stage,double amount,long expiresAt){}
    private static final class Holder implements InventoryHolder {
        final Page page;
        final Map<Integer,Double> amounts=new HashMap<>();
        Holder(Page page){this.page=page;}
        @Override public Inventory getInventory(){return null;}
    }
    private final SMPCore plugin;
    private final Database db;
    private final Map<UUID,Long> garnishNotices=new HashMap<>();
    private final Map<UUID,Pending> pending=new ConcurrentHashMap<>();
    private final Set<UUID> processingPayments=ConcurrentHashMap.newKeySet();

    BankService(SMPCore plugin){this.plugin=plugin;this.db=plugin.db();}
    boolean awaitingChatInput(Player player){return pending.containsKey(player.getUniqueId());}

    void open(Player player){openMain(player);}

    private void openMain(Player player){
        Database.LoanRow loan=accrue(player);
        Database.BankRow bank=db.bank();
        double available=available(player,loan,bank);
        Holder holder=new Holder(Page.MAIN);
        Inventory inv=plugin.getServer().createInventory(holder,27,Component.text("Ashfall Central Bank",NamedTextColor.DARK_GREEN));
        inv.setItem(4,icon(Material.GOLD_BLOCK,"Central Treasury",CoreUtil.money(bank.balance()),"Server payments in • shop payouts out"));
        inv.setItem(10,icon(Material.WRITABLE_BOOK,"Your Debt",loan==null?"None":CoreUtil.money(loan.debt()),loan==null?"No active loan.":"Principal: "+CoreUtil.money(loan.principal())));
        inv.setItem(12,icon(Material.CLOCK,"Interest",loan==null?"—":CoreUtil.money(loan.interest()),formatPercent(plugin.getConfig().getDouble("bank.loans.daily-interest-percent",1))+" per day"));
        inv.setItem(14,icon(loan!=null&&loan.overdue()?Material.REDSTONE_TORCH:Material.LANTERN,"Due Status",dueLine(loan),loan!=null&&loan.overdue()?"Future income is partially garnished.":""));
        inv.setItem(16,icon(Material.EMERALD,"Available Loan",CoreUtil.money(available),"Based on progress, history, and treasury funds."));
        inv.setItem(21,icon(Material.EMERALD_BLOCK,"Borrow",available>0?"View available amounts":"Unavailable",loan!=null?"Repay the current loan first.":""));
        inv.setItem(23,icon(Material.GOLD_INGOT,"Repay",loan==null?"No active loan":"Choose a repayment amount","Payments return funds to the treasury."));
        player.openInventory(inv);
    }

    private void openBorrow(Player player){
        Database.LoanRow loan=accrue(player);Database.BankRow bank=db.bank();double available=available(player,loan,bank);
        if(available<minimumLoan()){CoreUtil.error(player,loan==null?"The treasury cannot fund a loan for you yet.":"Repay your current loan before borrowing again.");openMain(player);return;}
        Holder holder=new Holder(Page.BORROW);Inventory inv=plugin.getServer().createInventory(holder,27,Component.text("Choose Loan",NamedTextColor.DARK_GREEN));
        List<Double> amounts=distinctAmounts(available);
        int[] slots={10,12,14};
        for(int i=0;i<amounts.size();i++){double amount=amounts.get(i);inv.setItem(slots[i],icon(Material.EMERALD,i==amounts.size()-1?"Borrow Maximum":"Borrow "+CoreUtil.money(amount),CoreUtil.money(amount),formatPercent(plugin.getConfig().getDouble("bank.loans.daily-interest-percent",1))+" daily interest","Due in "+plugin.getConfig().getInt("bank.loans.due-days",7)+" days"));holder.amounts.put(slots[i],amount);}
        inv.setItem(16,icon(Material.NAME_TAG,"Custom Borrow Amount","Type an exact amount in chat"));
        inv.setItem(22,icon(Material.ARROW,"Back","Return to account summary"));
        player.openInventory(inv);
    }

    private void openRepay(Player player){
        Database.LoanRow loan=accrue(player);if(loan==null){CoreUtil.msg(player,"You have no active loan.");openMain(player);return;}
        double affordable=Math.min(loan.debt(),db.player(CoreUtil.id(player)).balance());
        Holder holder=new Holder(Page.REPAY);Inventory inv=plugin.getServer().createInventory(holder,27,Component.text("Repay Loan",NamedTextColor.GOLD));
        List<Double> amounts=distinctRepayments(affordable,loan.debt());int[] slots={11,13,15};
        for(int i=0;i<amounts.size();i++){double amount=amounts.get(i);inv.setItem(slots[i],icon(Material.GOLD_INGOT,i==amounts.size()-1?"Repay Maximum":"Repay "+CoreUtil.money(amount),CoreUtil.money(amount)));holder.amounts.put(slots[i],amount);}
        inv.setItem(16,icon(Material.NAME_TAG,"Custom Amount","Type an exact repayment in chat"));
        inv.setItem(4,icon(Material.WRITABLE_BOOK,"Outstanding Debt",CoreUtil.money(loan.debt()),loan.overdue()?"OVERDUE":dueLine(loan)));
        inv.setItem(22,icon(Material.ARROW,"Back","Return to account summary"));
        player.openInventory(inv);
    }

    private void openRepayConfirmation(Player player,double amount){
        Database.LoanRow loan=accrue(player);if(loan==null){openMain(player);return;}
        Holder holder=new Holder(Page.REPAY_CONFIRM);holder.amounts.put(11,amount);
        Inventory inv=plugin.getServer().createInventory(holder,27,Component.text("Confirm Repayment",NamedTextColor.GOLD));
        inv.setItem(4,icon(Material.WRITABLE_BOOK,"Repayment",CoreUtil.money(amount),"Debt after payment: "+CoreUtil.money(Math.max(0,loan.debt()-amount))));
        inv.setItem(11,icon(Material.LIME_CONCRETE,"Confirm",CoreUtil.money(amount)));
        inv.setItem(15,icon(Material.RED_CONCRETE,"Cancel","Return to repayment options"));
        player.openInventory(inv);
    }

    void click(InventoryClickEvent event){
        if(!(event.getInventory().getHolder(false) instanceof Holder holder))return;
        event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player))return;int slot=event.getRawSlot();
        if(holder.page==Page.MAIN){if(slot==21)openBorrow(player);else if(slot==23)openRepay(player);return;}
        if(holder.page==Page.REPAY_CONFIRM){if(slot==11){Double amount=holder.amounts.get(11);if(amount!=null)repay(player,amount);}else if(slot==15)openRepay(player);return;}
        if(slot==22){openMain(player);return;}
        if(holder.page==Page.BORROW&&slot==16){startCustom(player,InputKind.BORROW);return;}
        if(holder.page==Page.REPAY&&slot==16){startCustom(player,InputKind.REPAY);return;}
        Double amount=holder.amounts.get(slot);if(amount==null)return;
        if(holder.page==Page.BORROW)borrow(player,amount);else repay(player,amount);
    }

    private void borrow(Player player,double amount){
        Database.LoanRow loan=accrue(player);Database.BankRow bank=db.bank();double allowed=available(player,loan,bank);
        if(loan!=null||amount<minimumLoan()||amount>allowed+.001){CoreUtil.error(player,"That loan is no longer available.");openMain(player);return;}
        /** One successful issuance per real day. Deliberately counts loans ISSUED, not loans outstanding,
         *  so repaying early does not buy another go -- otherwise the limit could be cycled indefinitely
         *  within a day. Repayments themselves are entirely unaffected and never consume allowance. */
        int perDay=Math.max(1,plugin.getConfig().getInt("bank.loans.max-per-day",1));
        if(db.loansIssuedSince(CoreUtil.id(player),loanDayStart())>=perDay){
            CoreUtil.error(player,"You have already taken a loan today. The allowance resets at "+plugin.getConfig().getInt("bank.loans.reset-hour",12)+":00 server time.");
            openMain(player);return;
        }
        double rate=plugin.getConfig().getDouble("bank.loans.daily-interest-percent",1)/100.0;long due=System.currentTimeMillis()+plugin.getConfig().getLong("bank.loans.due-days",7)*86400000L;
        if(!db.issueLoan(CoreUtil.id(player),amount,rate,due)){CoreUtil.error(player,"The treasury could not issue that loan.");openMain(player);return;}
        player.playSound(player.getLocation(),Sound.BLOCK_VAULT_OPEN_SHUTTER,.8f,1.1f);CoreUtil.msg(player,"Borrowed "+CoreUtil.money(amount)+". Due in "+plugin.getConfig().getInt("bank.loans.due-days",7)+" days.");openMain(player);
    }

    /** Same noon boundary the shard allowance uses, so "today" means one thing across the server. */
    private long loanDayStart(){
        java.time.ZoneId zone=java.time.ZoneId.of(plugin.getConfig().getString("weekly-dragon.timezone","Asia/Riyadh"));
        java.time.ZonedDateTime now=java.time.ZonedDateTime.now(zone);
        java.time.ZonedDateTime start=now.with(java.time.LocalTime.of(plugin.getConfig().getInt("bank.loans.reset-hour",12),0));
        if(now.isBefore(start))start=start.minusDays(1);
        return start.toInstant().toEpochMilli();
    }
    private void startCustom(Player player,InputKind kind){
        if(kind==InputKind.BORROW&&available(player,accrue(player),db.bank())<minimumLoan()){CoreUtil.error(player,"No custom loan is available right now.");openMain(player);return;}
        if(kind==InputKind.REPAY&&accrue(player)==null){CoreUtil.error(player,"You have no active loan.");openMain(player);return;}
        player.closeInventory();long expires=System.currentTimeMillis()+plugin.getConfig().getLong("bank.loans.input-timeout-seconds",30)*1000L;
        pending.put(player.getUniqueId(),new Pending(kind,InputStage.AMOUNT,0,expires));
        CoreUtil.msg(player,"Type the amount to "+(kind==InputKind.BORROW?"borrow":"repay")+" in chat, or type cancel.");
        scheduleTimeout(player.getUniqueId(),expires);
    }

    @SuppressWarnings("deprecation")
    @EventHandler public void chat(AsyncPlayerChatEvent event){
        Pending input=pending.get(event.getPlayer().getUniqueId());if(input==null)return;event.setCancelled(true);String message=event.getMessage().trim();
        plugin.getServer().getScheduler().runTask(plugin,()->handleInput(event.getPlayer(),message));
    }

    private void handleInput(Player player,String message){
        Pending input=pending.get(player.getUniqueId());if(input==null)return;if(System.currentTimeMillis()>input.expiresAt()){pending.remove(player.getUniqueId());CoreUtil.error(player,"Custom loan input timed out.");return;}
        if(message.equalsIgnoreCase("cancel")){pending.remove(player.getUniqueId());CoreUtil.msg(player,"Cancelled.");if(input.kind()==InputKind.REPAY)openRepay(player);else openBorrow(player);return;}
        if(input.stage()==InputStage.AMOUNT){
            if(input.kind()==InputKind.REPAY){
                Database.LoanRow loan=accrue(player);Database.PlayerRow account=db.player(CoreUtil.id(player));double amount=CoreUtil.parseMoney(message),balance=account==null?0:account.balance(),debt=loan==null?0:loan.debt();
                if(!Double.isFinite(amount)||amount<=0||amount>balance+.001||amount>debt+.001){CoreUtil.error(player,"Enter an amount up to "+CoreUtil.money(Math.min(balance,debt))+", or cancel.");return;}
                pending.remove(player.getUniqueId());openRepayConfirmation(player,round(amount));return;
            }
            double amount=CoreUtil.parseMoney(message),available=available(player,accrue(player),db.bank());if(amount<minimumLoan()||amount>available+.001){CoreUtil.error(player,"Enter "+CoreUtil.money(minimumLoan())+" to "+CoreUtil.money(available)+", or cancel.");return;}
            amount=round(amount);int days=plugin.getConfig().getInt("bank.loans.due-days",7);double interest=round(amount*plugin.getConfig().getDouble("bank.loans.daily-interest-percent",1)/100.0*days);
            pending.put(player.getUniqueId(),new Pending(InputKind.BORROW,InputStage.CONFIRM,amount,input.expiresAt()));
            CoreUtil.msg(player,"Borrow "+CoreUtil.money(amount)+" • estimated "+CoreUtil.money(interest)+" interest by day "+days+" • total "+CoreUtil.money(amount+interest)+".");
            CoreUtil.msg(player,"Type confirm or cancel.");
            return;
        }
        if(!message.equalsIgnoreCase("confirm")){CoreUtil.error(player,"Type confirm or cancel.");return;}pending.remove(player.getUniqueId());borrow(player,input.amount());
    }

    private void scheduleTimeout(UUID player,long expires){
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{Pending input=pending.get(player);if(input==null||input.expiresAt()!=expires||System.currentTimeMillis()<expires)return;pending.remove(player);Player online=plugin.getServer().getPlayer(player);if(online!=null)CoreUtil.error(online,"Custom loan input timed out.");},plugin.getConfig().getLong("bank.loans.input-timeout-seconds",30)*20L+2);
    }

    private void repay(Player player,double requested){
        if(!Double.isFinite(requested)||requested<=0||!processingPayments.add(player.getUniqueId()))return;
        try{
            Database.LoanRow loan=accrue(player);Database.PlayerRow account=db.player(CoreUtil.id(player));
            if(loan==null||account==null||requested>loan.debt()+.001||requested>account.balance()+.001){CoreUtil.error(player,"That repayment is no longer available.");openRepay(player);return;}
            double paid=db.repayLoan(CoreUtil.id(player),requested);if(paid<=0){CoreUtil.error(player,"You do not have enough balance for that payment.");openMain(player);return;}
            player.playSound(player.getLocation(),Sound.BLOCK_VAULT_CLOSE_SHUTTER,.7f,1.2f);CoreUtil.msg(player,"Repaid "+CoreUtil.money(paid)+".");openMain(player);
        }finally{processingPayments.remove(player.getUniqueId());}
    }

    double creditEarned(String player,double amount,String detail){
        if(amount<=0)return 0;Database.LoanRow loan=accrue(player);double garnish=loan!=null&&loan.overdue()?Math.min(loan.debt(),amount*plugin.getConfig().getDouble("bank.loans.overdue-garnish-percent",25)/100.0):0;
        double paid=db.creditEarnedWithGarnishment(player,amount,garnish);
        if(paid>.009){Player online=find(player);long now=System.currentTimeMillis();if(online!=null&&now-garnishNotices.getOrDefault(online.getUniqueId(),0L)>10000){garnishNotices.put(online.getUniqueId(),now);online.sendActionBar(Component.text(CoreUtil.money(paid)+" applied to overdue bank debt",NamedTextColor.GOLD));}}
        return amount-paid;
    }

    void creditFee(double amount,String player,String detail){db.creditBankRevenue(amount,"FEE",player,detail);}
    void creditSink(double amount,String player,String detail){db.creditBankRevenue(amount,"SINK",player,detail);}
    boolean payServer(Player player,double amount,String category,String detail){return db.serverPayment(CoreUtil.id(player),amount,category,detail);}
    boolean refundServerPayment(Player player,double amount,String category,String detail){return db.refundServerPayment(CoreUtil.id(player),amount,category,detail);}
    boolean payFaction(long faction,double amount,String player,String detail){return db.factionServerPayment(faction,amount,player,detail);}
    boolean payShopSeller(Player player,double amount,String detail){
        Database.LoanRow loan=accrue(player);double garnish=loan!=null&&loan.overdue()?Math.min(loan.debt(),amount*plugin.getConfig().getDouble("bank.loans.overdue-garnish-percent",25)/100.0):0;
        boolean paid=db.payShopSeller(CoreUtil.id(player),amount,garnish,detail);if(paid&&garnish>.009)garnishNotice(player,garnish);return paid;
    }
    boolean canPay(double amount){Database.BankRow row=db.bank();return row!=null&&row.balance()+.0001>=amount;}

    boolean allowNonessential(Player player,String purchase){
        Database.LoanRow loan=accrue(player);if(loan==null||!loan.overdue())return true;
        CoreUtil.error(player,"Overdue bank debt blocks "+purchase+". Repay it at the Banker first.");return false;
    }

    boolean overdue(String player){Database.LoanRow loan=accrue(player);return loan!=null&&loan.overdue();}
    Database.BankRow treasury(){return db.bank();}
    /** Admin-only Central Bank balance control (add/remove/set). Delta may push the balance negative, which
     *  is intentional -- a negative treasury is what turns on the deficit surcharge. */
    void adminAdjust(double delta){db.adjustBank(delta);}
    /** The Central Bank deficit surcharge. While the treasury sits at or below zero, every player->bank payment
     *  is charged at 2x and every shop payout is paid at 0.5x, until buys/fees/sinks pull the treasury back
     *  above zero. This is the SINGLE source of truth so no price is ever hand-doubled at a call site.
     *
     *  The state is CACHED for 1s: buyFactor()/sellFactor() are called once per shop-GUI item (dozens per open),
     *  and hitting the synchronized bank() SELECT each time was a needless query storm. A 1s staleness is
     *  irrelevant for a treasury that moves on human timescales, and it keeps the hot shop paths query-free. */
    private volatile boolean cachedDeficit; private volatile long deficitCheckedAt;
    boolean deficit(){
        long now = System.currentTimeMillis();
        if (now - deficitCheckedAt > 1000L) { Database.BankRow row = db.bank(); cachedDeficit = row != null && row.balance() <= 0; deficitCheckedAt = now; }
        return cachedDeficit;
    }
    double buyFactor(){ return deficit()?2.0:1.0; }
    double sellFactor(){ return deficit()?0.5:1.0; }

    private Database.LoanRow accrue(Player player){return accrue(CoreUtil.id(player));}
    private Database.LoanRow accrue(String player){return db.accrueLoan(player,plugin.getConfig().getDouble("bank.loans.maximum-interest-percent",25));}
    private double available(Player player,Database.LoanRow loan,Database.BankRow bank){if(loan!=null||bank==null)return 0;return round(Math.min(limit(player),bank.balance()));}
    private double limit(Player player){
        Database.StatsRow stats=db.stats(CoreUtil.id(player));double balance=stats==null?0:stats.balance(),hours=stats==null?0:stats.playSeconds()/3600.0;
        double limit=plugin.getConfig().getDouble("bank.loans.base-limit",500)+db.milestoneCount(CoreUtil.id(player))*plugin.getConfig().getDouble("bank.loans.per-milestone-limit",750)+Math.min(5000,balance*.25)+Math.min(5000,hours*25)+db.repaidLoanCount(CoreUtil.id(player))*1000;
        double maximum=plugin.getConfig().getDouble("bank.loans.maximum-limit",0),dynamic=Math.max(minimumLoan(),limit);
        return round(maximum>0?Math.min(maximum,dynamic):dynamic);
    }
    private double minimumLoan(){return plugin.getConfig().getDouble("bank.loans.minimum-amount",500);}
    private List<Double> distinctAmounts(double maximum){LinkedHashSet<Double> values=new LinkedHashSet<>();for(double factor:new double[]{.25,.5,1}){double amount=roundDown(Math.max(minimumLoan(),maximum*factor));if(amount<=maximum+.001)values.add(amount);}if(values.isEmpty()&&maximum>=minimumLoan())values.add(round(maximum));return new ArrayList<>(values);}
    private List<Double> distinctRepayments(double affordable,double debt){LinkedHashSet<Double> values=new LinkedHashSet<>();for(double value:new double[]{500,2500,Math.min(affordable,debt)})if(value>0&&value<=affordable+.001&&value<=debt+.001)values.add(round(value));return new ArrayList<>(values);}
    private double roundDown(double value){return Math.floor(value/100.0)*100.0;}
    private double round(double value){return Math.round(value*100.0)/100.0;}
    private void garnishNotice(Player player,double paid){long now=System.currentTimeMillis();if(now-garnishNotices.getOrDefault(player.getUniqueId(),0L)>10000){garnishNotices.put(player.getUniqueId(),now);player.sendActionBar(Component.text(CoreUtil.money(paid)+" applied to overdue bank debt",NamedTextColor.GOLD));}}
    private String dueLine(Database.LoanRow loan){if(loan==null)return "No payment due";if(loan.overdue())return "OVERDUE";long millis=Math.max(0,loan.dueAt()-System.currentTimeMillis());long days=Math.max(1,(millis+86399999L)/86400000L);return "Due in "+days+" day"+(days==1?"":"s");}
    private String formatPercent(double value){return (Math.rint(value)==value?Integer.toString((int)value):String.format(Locale.US,"%.1f",value))+"%";}
    private ItemStack icon(Material material,String name,String... lines){return CoreUtil.named(material,name,Arrays.stream(lines).filter(line->line!=null&&!line.isBlank()).toList());}
    private Player find(String id){for(Player player:plugin.getServer().getOnlinePlayers())if(CoreUtil.id(player).equals(id))return player;return null;}
}
