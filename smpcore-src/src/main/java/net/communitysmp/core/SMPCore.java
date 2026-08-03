package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

public final class SMPCore extends JavaPlugin implements CommandExecutor,TabCompleter {
    private Database db;private CoreEconomy economy;private BankService bank;private TeleportService teleports;private FactionService factions;private ShopService shop;private AuctionService auctions;private ProgressService progress;private RelicService relics;private BossEventService bosses;private BountyService bounties;private SpawnerService spawners;private SpawnClaimService spawnClaims;private EnderChestService enderChests;private NetWorthService netWorth;private MerchantService merchants;private VillagerCapsuleService capsules;private GraveService graves;private BulletinService bulletin;private UIService ui;private TabIntegration tabIntegration;private GuideService guides;private MessagingService messaging;private SettingsService settings;private AccountService account;private RegistrationService registration;private ConfirmationService confirmations;private ShardService shards;private MarketplaceService marketplace;private WeeklyDragonService weeklyDragon;private VillagerDiscountService villagerDiscounts;private WorldBorderService worldBorders;private GrimCompatibility grimCompatibility;private NicknameService nicknames;private TrustedAdminService trustedAdmins;private AdminToolsService adminTools;private DiscordReminderService discordReminders;private OrdersService ordersService;private AfkService afk;private PunishmentService punishments;private ReplayIntegration replay;private ObsidianDurabilityService obsidian;
    private final Map<UUID,Long> feedbackCooldowns=new HashMap<>();
    Database db(){return db;} UIService ui(){return ui;} ProgressService progress(){return progress;} TeleportService teleports(){return teleports;} SpawnClaimService spawnClaims(){return spawnClaims;}EnderChestService enderChests(){return enderChests;}NetWorthService netWorth(){return netWorth;}BankService bank(){return bank;}VillagerCapsuleService capsules(){return capsules;}FactionService factions(){return factions;}BossEventService bosses(){return bosses;}MessagingService messaging(){return messaging;}GraveService graves(){return graves;}BulletinService bulletin(){return bulletin;}SettingsService settings(){return settings;}AccountService account(){return account;}RegistrationService registration(){return registration;}ConfirmationService confirmations(){return confirmations;}ShardService shards(){return shards;}MarketplaceService marketplace(){return marketplace;}WeeklyDragonService weeklyDragon(){return weeklyDragon;}VillagerDiscountService villagerDiscounts(){return villagerDiscounts;}SpawnerService spawners(){return spawners;}NicknameService nicknames(){return nicknames;}TabIntegration tab(){return tabIntegration;}AfkService afk(){return afk;}RelicService relics(){return relics;}AdminToolsService adminTools(){return adminTools;}PunishmentService punishments(){return punishments;}ReplayIntegration replay(){return replay;}TrustedAdminService trustedAdmins(){return trustedAdmins;}
    double creditEarned(String player,double amount,String detail){if(amount<=0)return 0;if(bank==null){db.changeBalance(player,amount);return amount;}return bank.creditEarned(player,amount,detail);}
    boolean isAdmin(Player p){return trustedAdmins!=null&&trustedAdmins.isAdmin(p);}
    boolean privileged(Player p){return isAdmin(p)&&(p.getGameMode()==GameMode.CREATIVE||p.getGameMode()==GameMode.SPECTATOR);}
    String roleName(Player p){return isAdmin(p)?"ADMIN":"MEMBER";}
    boolean isTeleporting(Player p){return teleports!=null&&teleports.isPending(p);}
    boolean isBedrock(Player p){try{Class<?> apiClass=Class.forName("org.geysermc.floodgate.api.FloodgateApi");Object api=apiClass.getMethod("getInstance").invoke(null);Method method=apiClass.getMethod("isFloodgatePlayer",UUID.class);return(boolean)method.invoke(api,p.getUniqueId());}catch(Exception e){return false;}}

    @Override public void onEnable(){
        saveDefaultConfig();
        /** Config-driven, not a jar removal — stays revertible with a one-line config edit instead of a
         *  server file change, and travels with the codebase through the normal merge/deploy process rather
         *  than needing a manual step on every server it should apply to. Empty by default in the shipped
         *  config.yml resource, so this is inert unless a specific server's own live config.yml opts a
         *  plugin in. Runs this early so it fires before this plugin does anything that might depend on the
         *  target being present; the target itself may already be mid/post its own onEnable() by the time
         *  this runs, since Bukkit's load order isn't otherwise controlled here — this stops it from staying
         *  active afterward, not from ever initializing at all. */
        for(String name:getConfig().getStringList("integrations.disabled-plugins")){
            org.bukkit.plugin.Plugin target=getServer().getPluginManager().getPlugin(name);
            if(target!=null&&target.isEnabled()){getServer().getPluginManager().disablePlugin(target);getLogger().info("Disabled "+name+" per config (integrations.disabled-plugins).");}
        }
        for(String resource:List.of("shop.yml","bosses.yml","events.yml","relics.yml","shards.yml"))
            if(!new File(getDataFolder(),resource).exists())saveResource(resource,false);
        db=new Database(this);
        try{db.open();}catch(SQLException e){getLogger().severe("SMPCore cannot open SQLite: "+e.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        trustedAdmins=new TrustedAdminService(this);
        bank=new BankService(this);economy=new CoreEconomy(this);getServer().getServicesManager().register(Economy.class,economy,this,ServicePriority.Highest);
        teleports=new TeleportService(this);factions=new FactionService(this,teleports);spawnClaims=new SpawnClaimService(this,factions);
        shop=new ShopService(this);auctions=new AuctionService(this);enderChests=new EnderChestService(this);
        progress=new ProgressService(this);progress.repairDragonParticipant("Asserto");relics=new RelicService(this);bosses=new BossEventService(this,factions,relics);
        factions.backfillFactionMissions();
        bounties=new BountyService(this,factions);spawners=new SpawnerService(this,factions);netWorth=new NetWorthService(this,factions,shop,spawners);
        merchants=new MerchantService(this,shop,auctions,bosses,bank);capsules=new VillagerCapsuleService(this,factions,spawnClaims,merchants);
        settings=new SettingsService(this);account=new AccountService(this);registration=new RegistrationService(this);confirmations=new ConfirmationService(this);shards=new ShardService(this);marketplace=new MarketplaceService(this,shop,auctions,shards);
        weeklyDragon=new WeeklyDragonService(this);graves=new GraveService(this);bulletin=new BulletinService(this);guides=new GuideService(this);messaging=new MessagingService(this);obsidian=new ObsidianDurabilityService(this,factions);
        villagerDiscounts=new VillagerDiscountService(this);
        worldBorders=new WorldBorderService(this);
        for(World world:getServer().getWorlds())try{world.setGameRule(GameRule.LOCATOR_BAR,false);}catch(Throwable ignored){}
        ui=new UIService(this,bosses,netWorth);nicknames=new NicknameService(this);tabIntegration=new TabIntegration(this,factions);
        adminTools=new AdminToolsService(this);discordReminders=new DiscordReminderService(this);ordersService=new OrdersService(this);afk=new AfkService(this);punishments=new PunishmentService(this);
        getServer().getPluginManager().registerEvents(new GameplayListener(this,factions,teleports,shop,auctions,bosses,bounties,relics,progress,spawners,spawnClaims,netWorth,merchants,capsules,graves,obsidian),this);
        getServer().getPluginManager().registerEvents(trustedAdmins,this);
        for(Listener listener:List.of(enderChests,bank,settings,account,confirmations,shards,marketplace,graves,bulletin,guides,progress,netWorth,villagerDiscounts,nicknames,adminTools,ordersService,relics,spawnClaims,afk,bounties))
            getServer().getPluginManager().registerEvents(listener,this);
        if(getServer().getPluginManager().isPluginEnabled("GrimAC"))grimCompatibility=new GrimCompatibility(this);
        replay=new ReplayIntegration(this);
        for(String name:List.of("f","balance","pay","home","sethome","delhome","renamehome","homes","buyhome","tpa","tpaccept","tpdeny","spawn","rtp","back","msg","reply","shop","luxuryshop","shardshop","settings","ah","bounty","bounties","events","relics","leaderboards","guide","smphelp","role","sidebar","feedback","stats","progress","history","graves","enderchest","ashfall","nickname","discord","admin","shout","afk","warn","warnings","myorders")){
            PluginCommand command=getCommand(name);if(command!=null){command.setExecutor(this);command.setTabCompleter(this);}
        }
        getLogger().info("SMPCore 1.7.0 enabled: marketplace, accessibility settings, shards, faction relations and weekly Dragon are ready.");
    }
    @Override public void onDisable(){if(enderChests!=null)enderChests.shutdown();if(spawnClaims!=null)spawnClaims.shutdown();if(discordReminders!=null)discordReminders.shutdown();if(adminTools!=null)adminTools.shutdown();if(trustedAdmins!=null)trustedAdmins.shutdown();if(grimCompatibility!=null)grimCompatibility.shutdown();if(tabIntegration!=null)tabIntegration.shutdown();if(teleports!=null)teleports.shutdown();if(ui!=null)ui.shutdown();if(bulletin!=null)bulletin.shutdown();if(graves!=null)graves.shutdown();if(obsidian!=null)obsidian.shutdown();if(weeklyDragon!=null)weeklyDragon.shutdown();if(spawners!=null)spawners.shutdown();if(shards!=null)shards.shutdown();if(settings!=null)settings.shutdown();if(factions!=null)factions.shutdown();if(netWorth!=null)netWorth.shutdown();if(bosses!=null)bosses.shutdown();if(relics!=null)relics.shutdown();if(progress!=null)progress.shutdown();if(db!=null)db.close();}

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){String name=command.getName().toLowerCase(Locale.ROOT);if(name.equals("admin"))return consoleAdmin(sender,args);if(name.equals("ashfall"))return admin(sender,args);if(name.equals("shout"))return shout(sender,args);if(!(sender instanceof Player p)){CoreUtil.error(sender,"This command requires a player.");return true;}db.ensurePlayer(CoreUtil.id(p),p.getName(),getConfig().getDouble("starting-balance",250));shards.activity(p);return switch(name){case"f"->factions.command(p,args);case"balance"->{CoreUtil.msg(p,"Balance: "+CoreUtil.money(db.player(CoreUtil.id(p)).balance()));yield true;}case"pay"->pay(p,args);case"home"->teleports.homeCommand(p,args);case"sethome"->teleports.setPersonalHome(p,args.length>0?args[0]:"home");case"delhome"->teleports.deletePersonalHome(p,args.length>0?args[0]:"home");case"renamehome"->{if(args.length<2){CoreUtil.error(p,"Usage: /renamehome <old> <new>");yield true;}yield teleports.renamePersonalHome(p,args[0],args[1]);}case"buyhome"->teleports.buyPersonalHome(p,args.length>0&&args[0].equalsIgnoreCase("confirm"));case"tpa"->{if(args.length<1)CoreUtil.error(p,"Usage: /tpa <player>");else teleports.tpa(p,args[0]);yield true;}case"tpaccept"->teleports.accept(p);case"tpdeny"->teleports.deny(p);case"spawn"->{teleports.warmup(p,teleports.spawn(),"server spawn");yield true;}case"rtp"->{if(args.length>0&&args[0].equalsIgnoreCase("queue")){yield teleports.toggleRtpQueue(p);}yield teleports.rtp(p);}case"msg"->messaging.message(p,args);case"reply"->messaging.reply(p,args);case"shop"->shop.command(p,args);case"luxuryshop"->{marketplace.open(p,MarketplaceService.Section.LUXURY);yield true;}case"shardshop"->{marketplace.open(p,MarketplaceService.Section.SHARDS);yield true;}case"settings"->settings.command(p,args);case"ah"->auctions.command(p,args);case"bounty"->{if(args.length<2)CoreUtil.error(p,"Usage: /bounty <player> <amount>");else bounties.place(p,args[0],CoreUtil.parseMoney(args[1]));yield true;}case"bounties"->{bounties.list(p);yield true;}case"events"->{eventCommand(p,args);yield true;}case"relics"->{if(args.length>0&&args[0].equalsIgnoreCase("trace")){if(args.length<2)CoreUtil.error(p,"Usage: /relics trace <relic key>");else relics.trace(p,args[1]);}else relics.list(p);yield true;}case"leaderboards"->{leaderboards(p,args);yield true;}case"guide"->guides.command(p,args);case"rules"->guides.rulesCommand(p,args);case"smphelp"->{playerHelp(p);yield true;}case"role"->{CoreUtil.msg(p,"Your Ashfall role is "+roleName(p)+".");yield true;}case"sidebar"->progress.toggleSidebar(p);case"feedback"->{feedback(p,args);yield true;}case"stats"->progress.stats(p,args.length>0?args[0]:null);case"progress"->progress.show(p);case"history"->{int page=parsePage(args);progress.history(p,false,page);yield true;}case"homes"->teleports.listPersonalHomes(p,args.length>0&&args[0].equalsIgnoreCase("locate"));case"graves"->graves.command(p);case"enderchest"->enderChests.command(p,args);case"warn"->{if(!isAdmin(p)){CoreUtil.error(p,"Only staff may use /warn.");yield true;}if(args.length<2){CoreUtil.error(p,"Usage: /warn <player> <reason>");yield true;}yield punishments.warn(p,args[0],String.join(" ",java.util.Arrays.copyOfRange(args,1,args.length)));}case"warnings"->{if(!isAdmin(p)){CoreUtil.error(p,"Only staff may use /warnings.");yield true;}if(args.length<1){CoreUtil.error(p,"Usage: /warnings <player> [page]");yield true;}Integer page=args.length>1?parsePageArg(args[1]):null;punishments.history(p,args[0],page==null?1:page);yield true;}case"nickname"->nicknames.command(p,args);case"discord"->{p.sendMessage("§9Discord: §b§nhttps://discord.gg/G2FfuXjz8");yield true;}case"afk"->{boolean now=afk.toggle(p);CoreUtil.msg(p,now?"You are now AFK.":"Welcome back — no longer AFK.");yield true;}case"back"->{if(!isAdmin(p)){CoreUtil.error(p,"Only staff may use /back.");yield true;}yield teleports.back(p);}case"myorders"->{CoreUtil.error(p,"The orders marketplace is temporarily unavailable.");yield true;}default->false;};}
    private boolean pay(Player p,String[] args){if(args.length<2){CoreUtil.error(p,"Usage: /pay <player> <amount>");return true;}Player target=nicknames.findVisiblePlayer(args[0]);double amount=CoreUtil.parseMoney(args[1]);if(target==null||target.equals(p)){CoreUtil.error(p,"That player is not available.");return true;}if(amount<=0||!db.changeBalance(CoreUtil.id(p),-amount)){CoreUtil.error(p,"Invalid amount or insufficient balance.");return true;}db.ensurePlayer(CoreUtil.id(target),target.getName(),getConfig().getDouble("starting-balance",250));creditEarned(CoreUtil.id(target),amount,"PLAYER_PAYMENT");CoreUtil.msg(p,"Paid "+nicknames.displayName(target)+" "+CoreUtil.money(amount)+".");CoreUtil.msg(target,nicknames.displayName(p)+" paid you "+CoreUtil.money(amount)+".");return true;}
    private boolean consoleAdmin(CommandSender sender,String[] args){
        if(!(sender instanceof ConsoleCommandSender)&&!(sender instanceof RemoteConsoleCommandSender)){CoreUtil.error(sender,"This command is console-only.");return true;}
        if(args.length==0||args[0].equalsIgnoreCase("list")){CoreUtil.msg(sender,"Admin accounts: "+trustedAdmins.accountList()+". AuthMe login is required.");return true;}
        String action=args[0].toLowerCase(Locale.ROOT);
        if(!action.equals("add")&&!action.equals("remove")){CoreUtil.error(sender,"Usage: /admin [list|add <player>|remove <player>]");return true;}
        if(args.length<2){CoreUtil.error(sender,"Usage: /admin "+action+" <player>");return true;}
        String targetName=args[1];
        List<String> accounts=new ArrayList<>(getConfig().getStringList("trusted-admin.accounts"));
        Player online=getServer().getPlayerExact(targetName);
        if(action.equals("add")){
            if(accounts.stream().anyMatch(n->n.equalsIgnoreCase(targetName))){CoreUtil.error(sender,targetName+" is already a trusted admin account.");return true;}
            accounts.add(targetName);
            getConfig().set("trusted-admin.accounts",accounts);saveConfig();
            if(online!=null)trustedAdmins.forceGrant(online);
            db.logAudit("CONSOLE","ADMIN_ADD","player="+targetName);
            getLogger().info("[Admin] "+targetName+" added as a trusted admin account.");
            CoreUtil.msg(sender,targetName+" added to trusted-admin.accounts."+(online!=null?" Granted live (online now).":" Will apply on next AuthMe login."));
        }else{
            if(!accounts.removeIf(n->n.equalsIgnoreCase(targetName))){CoreUtil.error(sender,targetName+" is not a trusted admin account.");return true;}
            getConfig().set("trusted-admin.accounts",accounts);saveConfig();
            if(online!=null)trustedAdmins.forceRevoke(online);
            db.logAudit("CONSOLE","ADMIN_REMOVE","player="+targetName);
            getLogger().info("[Admin] "+targetName+" removed as a trusted admin account.");
            CoreUtil.msg(sender,targetName+" removed from trusted-admin.accounts."+(online!=null?" Revoked live (was online).":""));
        }
        return true;
    }
    private boolean shout(CommandSender sender,String[] args){
        if(sender instanceof Player p&&!isAdmin(p)){CoreUtil.error(sender,"Only trusted admins can use /shout.");return true;}
        if(args.length==0){CoreUtil.error(sender,"Usage: /shout <message>");return true;}
        String message=String.join(" ",args);
        getServer().broadcast(net.kyori.adventure.text.Component.text("Server: ",net.kyori.adventure.text.format.NamedTextColor.GOLD).append(net.kyori.adventure.text.Component.text(message,net.kyori.adventure.text.format.NamedTextColor.WHITE)));
        return true;
    }
    private void eventCommand(Player p,String[] args){if(args.length>=2&&args[0].equalsIgnoreCase("track")){if(args[1].equalsIgnoreCase("on"))progress.setTracking(p,true);else if(args[1].equalsIgnoreCase("off"))progress.setTracking(p,false);else CoreUtil.error(p,"Usage: /events track <on|off>");return;}bosses.status(p);}
    private void feedback(Player p,String[] args){String message=String.join(" ",args).trim();if(message.length()<5||message.length()>400){CoreUtil.error(p,"Usage: /feedback <5-400 character message>");return;}long now=System.currentTimeMillis(),wait=60000-(now-feedbackCooldowns.getOrDefault(p.getUniqueId(),0L));if(wait>0){CoreUtil.error(p,"Please wait "+Math.max(1,wait/1000)+" seconds before another submission.");return;}feedbackCooldowns.put(p.getUniqueId(),now);long id=db.addFeedback(CoreUtil.id(p),p.getName(),message);CoreUtil.msg(p,"Feedback #"+id+" saved. Thank you.");for(Player admin:getServer().getOnlinePlayers())if(isAdmin(admin)&&!"false".equalsIgnoreCase(db.preference(CoreUtil.id(admin),"feedback_notify")))admin.sendMessage("§6[Feedback #"+id+"] §f"+p.getName()+": §7"+message);}

    private void leaderboards(Player p,String[] args){
        /** /leaderboard 2 must mean "page 2 of the default leaderboard", not "type=2" (which silently fell
         *  through to the default "balance" case while page stayed 1 no matter what number was typed — the
         *  bug was that a bare numeric first argument was never recognized as a page at all). A leading numeric
         *  argument is always a page number; a non-numeric one is always a type, with an optional page after it. */
        String type="money";int page=1;
        if(args.length>0){
            Integer firstPage=parsePageArg(args[0]);
            if(firstPage!=null)page=firstPage;
            else{type=args[0].toLowerCase(Locale.ROOT);if(args.length>1){Integer secondPage=parsePageArg(args[1]);if(secondPage!=null)page=secondPage;}}
        }
        int size=10,safe=Math.max(1,page),from=(safe-1)*size;
        CoreUtil.msg(p,"Leaderboard: "+type+" • page "+safe);
        if(type.equals("factions")||type.equals("networth")){
            List<NetWorthService.Row> rows=netWorth.rankings();if(from>=rows.size()){CoreUtil.msg(p,"No entries on this page.");return;}
            int i=from+1;for(NetWorthService.Row row:rows.subList(from,Math.min(rows.size(),from+size)))CoreUtil.msg(p,(i++)+". "+row.name()+" — Net Worth: "+CoreUtil.money(row.value()));return;
        }
        if(type.equals("bounties")){
            List<Database.BountyRow> rows=db.bounties();if(from>=rows.size()){CoreUtil.msg(p,"No entries on this page.");return;}
            int i=from+1;for(Database.BountyRow row:rows.subList(from,Math.min(rows.size(),from+size)))CoreUtil.msg(p,(i++)+". "+nicknames.displayName(row.targetName())+" — "+CoreUtil.money(row.amount()));return;
        }
        String column=switch(type){case"bosses"->"boss_kills";case"kills"->"player_kills";case"deaths"->"deaths";case"mobs"->"mob_kills";case"events"->"event_wins";case"playtime"->"play_seconds";default->"balance";};
        List<Database.StatsRow> rows=db.topStats(column,size,from);
        if(rows.isEmpty()){CoreUtil.msg(p,"No entries on this page.");return;}
        int i=from+1;for(Database.StatsRow row:rows){String value=switch(column){case"balance"->CoreUtil.money(row.balance());case"play_seconds"->row.playSeconds()/3600+"h "+row.playSeconds()%3600/60+"m";case"player_kills"->row.playerKills()+" kills";case"deaths"->row.deaths()+" deaths";case"mob_kills"->row.mobKills()+" mobs";case"boss_kills"->row.bossKills()+" bosses";default->row.eventWins()+" wins";};CoreUtil.msg(p,(i++)+". "+nicknames.displayName(row.name())+" — "+value);}
    }

    void giveGuide(Player p){guides.givePreferred(p);}
    private void playerHelp(Player p){p.sendMessage("§6§lASHFALL");p.sendMessage("§eFactions: §f/f create, /f claim, /f relations, /f expand, /f <player>");p.sendMessage("§eEconomy: §f/balance, /pay, /bounty, /bounties");p.sendMessage("§eMarketplace: §f/shop, /ah, /luxuryshop, /shardshop, /orders, /myorders");p.sendMessage("§eTravel: §f/home, /tpa, /spawn, /rtp, /rtp queue");p.sendMessage("§eSocial: §f/msg, /r, /trade, /feedback");p.sendMessage("§eMore: §f/settings, /events, /progress, /stats, /graves, /enderchest, /guide, /afk");}

    private boolean admin(CommandSender sender,String[] args){
        if(sender instanceof Player p&&!isAdmin(p)){CoreUtil.error(sender,"Only the configured ADMIN account can use SMPCore administration.");return true;}
        if(args.length==0){adminHelp(sender);return true;}
        if(args[0].equalsIgnoreCase("help")){if(args.length==1)adminHelp(sender);else adminSectionHelp(sender,args[1]);return true;}
        try{
            switch(args[0].toLowerCase(Locale.ROOT)){
                case"balance"->adminBalance(sender,args);
                case"boss"->adminBoss(sender,args);
                case"elite"->adminElite(sender,args);
                case"event"->adminEvent(sender,args);
                case"merchant"->{if(!(sender instanceof Player p)){CoreUtil.error(sender,"Run merchant commands in game.");return true;}merchants.command(p,args);}
                case"bulletin"->{if(!(sender instanceof Player p)){CoreUtil.error(sender,"Run bulletin commands in game.");return true;}bulletin.command(p,args);}
                case"feedback"->adminFeedback(sender,args);
                case"economy"->economyReport(sender,args);
                case"spawnclaim"->spawnClaims.command(sender,args);
                case"setspawn"->{if(!(sender instanceof Player p)){CoreUtil.error(sender,"Run this in game.");return true;}teleports.setSpawn(p.getLocation());CoreUtil.msg(sender,"Central spawn set.");}
                case"relic"->adminRelic(sender,args);
                case"grave"->{if(args.length<2||!args[1].equalsIgnoreCase("repair")){CoreUtil.error(sender,"Usage: /smp grave repair");return true;}int fixed=graves.repairBrokenMarkers();CoreUtil.msg(sender,fixed==0?"No marker-less graves needed repair.":"Repaired "+fixed+" grave(s) that had no physical marker — they're normally lootable now.");}
                case"bounty"->adminBounty(sender,args);
                case"faction"->adminFaction(sender,args);
                case"chatlog"->adminChatLog(sender,args);
                case"dmlog"->adminDmLog(sender,args);
                case"factionchatlog"->adminFactionChatLog(sender,args);
                case"lastloc"->adminLastLoc(sender,args);
                case"homes"->adminHomes(sender,args);
                case"factioninfo"->adminFactionInfo(sender,args);
                case"border"->adminBorder(sender,args);
                case"reload"->{reloadConfig();shop.reload();relics.reload();bosses.reload();shards.reload();factions.refreshClaims();worldBorders.apply();CoreUtil.msg(sender,"Safe SMPCore YAML reloaded.");}
                case"debug"->{CoreUtil.msg(sender,"Paper "+getServer().getMinecraftVersion()+" | Players "+db.topStats("balance").size()+" ranking rows | Factions "+db.factions().size());CoreUtil.msg(sender,"Vault "+economy.getName()+" | Event "+bosses.uiEventLine()+" | DB migration 1.7.0 active");}
                case"selftest"->selfTest(sender);
                case"vanish"->{if(!(sender instanceof Player p)){CoreUtil.error(sender,"Run this in game.");return true;}boolean now=adminTools.toggleVanish(p);CoreUtil.msg(sender,"Vanish "+(now?"enabled":"disabled")+".");}
                case"spectate"->{if(!(sender instanceof Player p)){CoreUtil.error(sender,"Run this in game.");return true;}if(args.length<2){CoreUtil.error(sender,"Usage: /ashfall spectate <player>");return true;}Player target=getServer().getPlayerExact(args[1]);if(target==null){CoreUtil.error(sender,"That player is not online.");return true;}adminTools.spectate(p,target);CoreUtil.msg(sender,"Spectating "+target.getName()+". Use /ashfall unspectate to return.");}
                case"unspectate"->{if(!(sender instanceof Player p)){CoreUtil.error(sender,"Run this in game.");return true;}if(adminTools.unspectate(p))CoreUtil.msg(sender,"Returned to normal play.");else CoreUtil.error(sender,"You are not spectating anyone.");}
                case"audit"->{int limit=args.length>1?Math.max(1,Math.min(50,Integer.parseInt(args[1]))):20;for(Database.AuditRow row:db.recentAudit(limit))CoreUtil.msg(sender,"["+row.adminName()+"] "+row.action()+(row.detail()==null||row.detail().isBlank()?"":" • "+row.detail()));}
                case"shard"->adminShard(sender,args);
                case"progressrepair"->adminProgressRepair(sender,args);
                case"cooldowns"->adminCooldowns(sender,args);
                case"dragon"->adminDragon(sender,args);
                case"replay"->adminReplay(sender,args);
                case"ordersdebug"->{if(args.length<3){CoreUtil.error(sender,"Usage: /ashfall ordersdebug <player> <orderId>");return true;}ordersService.debugClaim(sender,args[1],args[2]);}
                case"ordersdebugcreate"->{if(args.length<5){CoreUtil.error(sender,"Usage: /ashfall ordersdebugcreate <player> <material> <qty> <price>");return true;}try{ordersService.debugCreate(sender,args[1],args[2],Integer.parseInt(args[3]),Double.parseDouble(args[4]));}catch(NumberFormatException e){CoreUtil.error(sender,"Bad qty/price.");}}
                default->CoreUtil.error(sender,"Unknown admin subcommand. Use /ashfall help.");
            }
        }catch(Exception e){CoreUtil.error(sender,"Command failed safely: "+e.getMessage());getLogger().warning("Admin command error: "+e);}
        return true;
    }

    private void adminHelp(CommandSender s){
        s.sendMessage(ChatColor.GOLD+""+ChatColor.BOLD+"ASHFALL ADMIN");
        adminCategory(s,"Economy","/ashfall balance, /ashfall economy report, /ashfall shard");
        adminCategory(s,"Events & Bosses","/ashfall event, /ashfall boss, /ashfall elite");
        adminCategory(s,"Factions & Spawn","/ashfall faction, /ashfall spawnclaim");
        adminCategory(s,"Merchants","/ashfall merchant");
        adminCategory(s,"Bulletin","/ashfall bulletin");
        adminCategory(s,"Feedback","/ashfall feedback");
        adminCategory(s,"Relics","/ashfall relic");
        adminCategory(s,"Bounties","/ashfall bounty");
        adminCategory(s,"Moderation","/ashfall vanish, /ashfall spectate, /ashfall unspectate, /ashfall audit, /warn, /warnings, /openinv, /openender (OpenInv)");
        adminCategory(s,"Replays","/ashfall replay <killer|victim> <player> [count]");
        adminCategory(s,"Player Repair","/ashfall progressrepair, /ashfall cooldowns");
        adminCategory(s,"Debug / Maintenance","/ashfall help maintenance");
        s.sendMessage(ChatColor.DARK_GRAY+"Enter a parent command to see its options.");
    }
    private void adminCategory(CommandSender s,String category,String commands){s.sendMessage(ChatColor.YELLOW+category+ChatColor.DARK_GRAY+" — "+ChatColor.WHITE+commands);}
    private void adminCommands(CommandSender s,String title,String... commands){s.sendMessage(ChatColor.GOLD+""+ChatColor.BOLD+title+":");for(String command:commands)s.sendMessage(ChatColor.GRAY+"  "+ChatColor.WHITE+command);}
    private void adminSectionHelp(CommandSender s,String section){
        switch(section.toLowerCase(Locale.ROOT)){
            case"economy","balance"->adminCommands(s,"Economy","/ashfall economy report","/ashfall balance set <player> <amount>","/ashfall balance add <player> <amount>","/ashfall balance take <player> <amount>","/ashfall shard <give|remove|set> <player> <amount>");
            case"moderation"->adminCommands(s,"Moderation","/ashfall vanish","/ashfall spectate <player>","/ashfall unspectate","/ashfall audit [count]","/warn <player> <reason>","/warnings <player> [page]","/openinv <player> (OpenInv, live)","/openender <player> (OpenInv, live)");
            case"repair"->adminCommands(s,"Player Repair","/ashfall progressrepair <player>","/ashfall cooldowns <player> <rtp|teleport>");
            case"events"->{adminCommands(s,"Events & Bosses","/ashfall event","/ashfall boss","/ashfall elite");}
            case"event"->eventHelp(s);
            case"boss"->bossHelp(s);
            case"elite"->eliteHelp(s);
            case"factions","faction"->factionHelp(s);
            case"merchants","merchant"->merchantHelp(s);
            case"bulletin"->bulletin.commandHelp(s);
            case"spawn","spawnclaim"->spawnClaimHelp(s);
            case"feedback"->feedbackHelp(s);
            case"relics","relic"->relicHelp(s);
            case"bounty","bounties"->bountyHelp(s);
            case"maintenance","debug"->adminCommands(s,"Debug / Maintenance","/ashfall border status","/ashfall border apply","/ashfall border restore","/ashfall setspawn","/ashfall reload","/ashfall debug","/ashfall selftest","/ashfall grave repair");
            default->adminHelp(s);
        }
    }
    private void eventHelp(CommandSender s){adminCommands(s,"Events","/ashfall event resource","/ashfall event elitehunt","/ashfall event treasure","/ashfall event koth","/ashfall event worldboss","/ashfall event stop");}
    private void bossHelp(CommandSender s){adminCommands(s,"World Boss","/ashfall boss spawn [ashen|iron|piglin]","/ashfall boss here [ashen|iron|piglin]","/ashfall boss despawn");}
    private void eliteHelp(CommandSender s){adminCommands(s,"Powered Mobs","/ashfall elite stats","/ashfall elite uncommon","/ashfall elite rare","/ashfall elite epic","/ashfall elite legendary","/ashfall elite miniboss", "Add 'here' to spawn at your location.");}
    private void feedbackHelp(CommandSender s){adminCommands(s,"Feedback","/ashfall feedback notify <on|off>","/ashfall feedback list [open|done|all]","/ashfall feedback view <id>","/ashfall feedback done <id>","/ashfall feedback reopen <id>","/ashfall feedback delete <id>");}
    private void factionHelp(CommandSender s){adminCommands(s,"Factions","/ashfall faction inspect <name>","/ashfall faction resize <name> <tier>","/ashfall faction resetclaim <name>","/ashfall faction recalc");}
    private void merchantHelp(CommandSender s){adminCommands(s,"Spawn Merchants","/ashfall merchant spawn <boss|event|shop|auction|banker>","/ashfall merchant remove <nearest|id>");}
    private void spawnClaimHelp(CommandSender s){adminCommands(s,"Spawn Protection","/ashfall spawnclaim select","/ashfall spawnclaim info","/ashfall spawnclaim clear");}
    private void relicHelp(CommandSender s){adminCommands(s,"Relics","/ashfall relic give <player> <key>","/ashfall relic remove <key>","/ashfall relic forcerespawn <key>");}
    private void bountyHelp(CommandSender s){adminCommands(s,"Bounties","/ashfall bounty — open the pending-claims review GUI","/ashfall bounty approve <id>","/ashfall bounty reject <id>","/ashfall bounty remove <player> [refund]","/ashfall bounty inspect <player>");}
    private void adminBounty(CommandSender s,String[] args){
        if(args.length<2){if(s instanceof Player p)bounties.openAdminGui(p);else bountyHelp(s);return;}
        String action=args[1].toLowerCase(Locale.ROOT);
        switch(action){
            case"approve"->{if(args.length<3){CoreUtil.error(s,"Usage: /ashfall bounty approve <id>");return;}try{bounties.approveClaim(s,Long.parseLong(args[2]));}catch(NumberFormatException e){CoreUtil.error(s,"Claim id must be a number.");}}
            case"reject"->{if(args.length<3){CoreUtil.error(s,"Usage: /ashfall bounty reject <id>");return;}try{bounties.rejectClaim(s,Long.parseLong(args[2]),true);}catch(NumberFormatException e){CoreUtil.error(s,"Claim id must be a number.");}}
            case"remove"->{if(args.length<3){CoreUtil.error(s,"Usage: /ashfall bounty remove <player> [refund]");return;}bounties.adminRemove(s,args[2],args.length>3&&args[3].equalsIgnoreCase("refund"));}
            case"inspect"->{if(args.length<3){CoreUtil.error(s,"Usage: /ashfall bounty inspect <player>");return;}bounties.inspect(s,args[2]);}
            default->bountyHelp(s);
        }
    }
    private void adminBorder(CommandSender sender,String[] args){
        if(args.length<2||args[1].equalsIgnoreCase("status")){worldBorders.status(sender);return;}
        if(args[1].equalsIgnoreCase("apply")){worldBorders.apply();CoreUtil.msg(sender,"Configured safety borders applied.");return;}
        if(args[1].equalsIgnoreCase("restore")){worldBorders.restore();CoreUtil.msg(sender,"Previous world-border sizes restored for this runtime. Disable performance.world-borders.enabled before restarting if the restoration should remain active.");return;}
        CoreUtil.error(sender,"Usage: /smp border <status|apply|restore>");
    }
    private void adminBalance(CommandSender s,String[] args){if(args.length<2){adminSectionHelp(s,"balance");return;}if(args.length<4){CoreUtil.error(s,"Usage: /smp balance "+args[1]+" <player> <amount>");return;}String id=CoreUtil.id(args[2]);db.ensurePlayer(id,args[2],getConfig().getDouble("starting-balance",250));double amount=CoreUtil.parseMoney(args[3]);if(amount<0){CoreUtil.error(s,"Invalid amount.");return;}if(args[1].equalsIgnoreCase("set"))db.setBalance(id,amount);else if(args[1].equalsIgnoreCase("add"))db.changeBalance(id,amount);else if(args[1].equalsIgnoreCase("take"))db.takeUpTo(id,amount);else{CoreUtil.error(s,"Unknown balance action. Use /smp balance for options.");return;}db.logAudit(adminName(s),"BALANCE_"+args[1].toUpperCase(Locale.ROOT),"player="+args[2]+" amount="+CoreUtil.money(amount));CoreUtil.msg(s,args[2]+" balance: "+CoreUtil.money(db.player(id).balance()));}
    private String adminName(CommandSender s){return s instanceof Player p?p.getName():"CONSOLE";}
    private void adminShard(CommandSender s,String[] args){
        if(args.length<3){CoreUtil.error(s,"Usage: /ashfall shard <give|remove|set> <player> <amount>");return;}
        String action=args[1].toLowerCase(Locale.ROOT);if(!Set.of("give","remove","set").contains(action)){CoreUtil.error(s,"Unknown shard action. Use give, remove, or set.");return;}
        if(args.length<4){CoreUtil.error(s,"Usage: /ashfall shard "+action+" <player> <amount>");return;}
        String id=CoreUtil.id(args[2]);int amount;try{amount=Integer.parseInt(args[3]);}catch(NumberFormatException e){CoreUtil.error(s,"Amount must be a whole number.");return;}
        if(amount<0){CoreUtil.error(s,"Amount must not be negative.");return;}
        db.ensurePlayer(id,args[2],getConfig().getDouble("starting-balance",250));
        int result=switch(action){case"give"->shards.adminAdjust(id,amount);case"remove"->shards.adminAdjust(id,-amount);default->shards.adminSet(id,amount);};
        db.logAudit(adminName(s),"SHARD_"+action.toUpperCase(Locale.ROOT),"player="+args[2]+" amount="+amount);
        CoreUtil.msg(s,args[2]+" shards: "+result);
    }
    private void adminProgressRepair(CommandSender s,String[] args){
        if(args.length<2){CoreUtil.error(s,"Usage: /ashfall progressrepair <player>");return;}
        Player target=getServer().getPlayerExact(args[1]);
        if(target==null){CoreUtil.error(s,"That player must be online to recheck their progress.");return;}
        progress.refreshRanks(target,true);db.logAudit(adminName(s),"PROGRESS_REPAIR","player="+args[1]);
        CoreUtil.msg(s,"Re-evaluated adventure progression for "+args[1]+".");
    }
    private void adminCooldowns(CommandSender s,String[] args){
        if(args.length<3){CoreUtil.error(s,"Usage: /ashfall cooldowns <player> <rtp|teleport>");return;}
        UUID id=getServer().getOfflinePlayer(args[1]).getUniqueId();String kind=args[2].toLowerCase(Locale.ROOT);
        boolean cleared=teleports.clearCooldown(id,kind);
        if(!cleared){CoreUtil.error(s,"Unknown cooldown type. Use rtp or teleport.");return;}
        db.logAudit(adminName(s),"COOLDOWN_RESET","player="+args[1]+" kind="+kind);
        CoreUtil.msg(s,"Cleared "+kind+" cooldown for "+args[1]+".");
    }
    private void adminBoss(CommandSender s,String[] args){if(args.length<2){bossHelp(s);return;}String action=args[1].toLowerCase(Locale.ROOT);if(action.equals("despawn")){if(bosses.despawnWorldBoss())CoreUtil.msg(s,"World boss despawned.");else CoreUtil.error(s,"No world boss is currently active.");return;}if(!action.equals("spawn")&&!action.equals("here")){CoreUtil.error(s,"Usage: /smp boss <spawn|here|despawn> [ashen|iron|piglin]");return;}if(action.equals("here")&&!(s instanceof Player)){CoreUtil.error(s,"The 'here' option must be used in game.");return;}Location loc=action.equals("here")?((Player)s).getLocation():null;BossEventService.WorldBossKind kind=null;if(args.length>2){kind=switch(args[2].toLowerCase(Locale.ROOT)){case"ashen"->BossEventService.WorldBossKind.ASHEN_KNIGHT;case"iron"->BossEventService.WorldBossKind.IRON_GOLEM;case"piglin"->BossEventService.WorldBossKind.PIGLIN_BRUTE;default->null;};if(kind==null){CoreUtil.error(s,"Unknown boss identity. Use ashen, iron, or piglin.");return;}}if(action.equals("here")&&kind==BossEventService.WorldBossKind.PIGLIN_BRUTE&&((Player)s).getWorld().getEnvironment()!=World.Environment.NETHER){CoreUtil.error(s,"The Cinder Warlord (piglin) can only be summoned in the Nether.");return;}if(bosses.startEvent(BossEventService.EventType.WORLD_BOSS,loc,BossEventService.Origin.ADMIN_SUMMONED,kind))CoreUtil.msg(s,"World boss active.");else CoreUtil.error(s,"A conflicting event or boss is already active, or this location is protected.");}
    private void adminDragon(CommandSender s,String[] args){if(args.length<2){CoreUtil.error(s,"Usage: /ashfall dragon <status|start>");return;}String action=args[1].toLowerCase(Locale.ROOT);if(action.equals("status")){CoreUtil.msg(s,weeklyDragon.status());}else if(action.equals("start")){CoreUtil.msg(s,weeklyDragon.forceStart());}else if(action.equals("debugcrystals")){CoreUtil.msg(s,weeklyDragon.debugResetCrystals());}else CoreUtil.error(s,"Usage: /ashfall dragon <status|start>");}
    /** Queries ReplayCore's own live catalog directly (by killer or victim role) rather than mirroring it in
     *  SMPCore's database — for cheating investigations and disputes that aren't tied to a bounty claim. */
    private void adminReplay(CommandSender s,String[] args){
        if(args.length<3||!List.of("killer","victim").contains(args[1].toLowerCase(Locale.ROOT))){CoreUtil.error(s,"Usage: /ashfall replay <killer|victim> <player> [count]");return;}
        boolean asKiller=args[1].equalsIgnoreCase("killer");String playerName=args[2];
        UUID uuid=getServer().getOfflinePlayer(playerName).getUniqueId();
        int count=5;if(args.length>3){try{count=Math.max(1,Math.min(20,Integer.parseInt(args[3])));}catch(NumberFormatException ignored){}}
        if(!replay.available()){CoreUtil.error(s,"ReplayCore is not installed, not enabled, or has no kill-replay feature active.");return;}
        int finalCount=count;
        CoreUtil.msg(s,"Searching ReplayCore for "+playerName+" as "+(asKiller?"killer":"victim")+"...");
        replay.searchReplays(uuid,asKiller,count,
            entries->{
                getLogger().info("[ReplayDebug] search player="+playerName+" role="+(asKiller?"killer":"victim")+" results="+entries.size());
                if(entries.isEmpty()){CoreUtil.msg(s,"No replays found for "+playerName+" as "+(asKiller?"killer":"victim")+" (checked up to "+finalCount+").");return;}
                for(var entry:entries){
                    String when=entry.createdAt()==null?"?":entry.createdAt().toString();
                    String url=entry.watchUrl().orElse("not ready yet ("+entry.processingState()+")");
                    getLogger().info("[ReplayDebug] ["+when+"] "+entry.assetId()+" — "+url);
                    CoreUtil.msg(s,"["+when+"] "+entry.assetId()+" — "+url);
                }
            },
            error->{getLogger().warning("[ReplayDebug] search failed: "+error);CoreUtil.error(s,"Replay search failed: "+error.getMessage());}
        );
    }
    private void adminElite(CommandSender s,String[] args){if(args.length<2){eliteHelp(s);return;}String tier=args[1].toLowerCase(Locale.ROOT);if(tier.equals("stats")||tier.equals("report")){bosses.rarityReport(s);return;}if(!List.of("uncommon","rare","epic","legendary","miniboss").contains(tier)){CoreUtil.error(s,"Unknown tier. Use /smp elite for options.");return;}boolean here=args.length>2&&args[2].equalsIgnoreCase("here");if(here&&!(s instanceof Player)){CoreUtil.error(s,"The 'here' option must be used in game.");return;}Location loc=here?((Player)s).getLocation():null;LivingEntity elite=bosses.spawnElite(tier,loc);if(elite!=null)CoreUtil.msg(s,CoreUtil.pretty(tier)+" mob spawned.");else CoreUtil.error(s,"No safe elite spawn was found.");}
    private void adminEvent(CommandSender s,String[] args){if(args.length<2){eventHelp(s);return;}if(args[1].equalsIgnoreCase("stop")){boolean stopped=bosses.forceStopEvent();db.logAudit(adminName(s),"EVENT_STOP",stopped?"stopped active event":"nothing was active");CoreUtil.msg(s,stopped?"Active event stopped and cleaned up.":"No event was active.");return;}BossEventService.EventType type=switch(args[1].toLowerCase(Locale.ROOT)){case"resource"->BossEventService.EventType.RESOURCE_RUSH;case"elitehunt"->BossEventService.EventType.ELITE_HUNT;case"treasure"->BossEventService.EventType.TREASURE;case"koth"->BossEventService.EventType.KOTH;case"worldboss","hunt"->BossEventService.EventType.WORLD_BOSS;default->{CoreUtil.error(s,"Unknown event. Use /smp event for options.");yield null;}};if(type==null)return;if(bosses.startEvent(type,null,BossEventService.Origin.ADMIN_SUMMONED))CoreUtil.msg(s,"Event started.");else CoreUtil.error(s,"A conflicting event is already active.");}
    private void adminFeedback(CommandSender s,String[] args){if(args.length<2){feedbackHelp(s);return;}String action=args[1].toLowerCase(Locale.ROOT);if(action.equals("notify")){if(!(s instanceof Player p)||args.length<3||!List.of("on","off").contains(args[2].toLowerCase(Locale.ROOT))){CoreUtil.error(s,"Usage: /smp feedback notify <on|off>");return;}boolean enabled=args[2].equalsIgnoreCase("on");db.preference(CoreUtil.id(p),"feedback_notify",Boolean.toString(enabled));CoreUtil.msg(s,"Feedback notifications "+(enabled?"enabled":"disabled")+".");return;}if(action.equals("list")){String status=args.length>2&&!args[2].equalsIgnoreCase("all")?args[2].toUpperCase(Locale.ROOT):"";for(Database.FeedbackRow row:db.feedback(status,20))CoreUtil.msg(s,"#"+row.id()+" ["+row.status()+"] "+row.playerName()+" • "+trim(row.message(),70));return;}if(!List.of("view","done","reopen","delete").contains(action)){CoreUtil.error(s,"Unknown feedback action. Use /smp feedback for options.");return;}if(args.length<3){CoreUtil.error(s,"Feedback ID required for '"+action+"'.");return;}long id=Long.parseLong(args[2]);Database.FeedbackRow row=db.feedback(id);if(row==null){CoreUtil.error(s,"Feedback not found.");return;}switch(action){case"view"->{CoreUtil.msg(s,"#"+row.id()+" ["+row.status()+"] "+row.playerName()+" • "+Instant.ofEpochMilli(row.createdAt()));s.sendMessage(row.message());}case"done"->{db.feedbackStatus(id,"DONE");CoreUtil.msg(s,"Feedback #"+id+" marked DONE.");}case"reopen"->{db.feedbackStatus(id,"OPEN");CoreUtil.msg(s,"Feedback #"+id+" reopened.");}case"delete"->{db.deleteFeedback(id);CoreUtil.msg(s,"Feedback #"+id+" permanently deleted.");}}}

    private void economyReport(CommandSender s,String[] args){if(args.length<2||!args[1].equalsIgnoreCase("report")){adminCommands(s,"Economy","/smp economy report");return;}long now=System.currentTimeMillis();economyWindow(s,"24 hours",db.economyTotals(now-86400000L));economyWindow(s,"7 days",db.economyTotals(now-604800000L));Database.BankRow treasury=bank.treasury();CoreUtil.msg(s,"Central Bank: "+CoreUtil.money(treasury.balance())+" | fees "+CoreUtil.money(treasury.feeRevenue())+" | server payments "+CoreUtil.money(treasury.sinkRevenue())+" | shop payouts "+CoreUtil.money(treasury.shopPayouts())+" | interest "+CoreUtil.money(treasury.interestRevenue()));List<Database.StatsRow> rich=db.topStats("balance");if(!rich.isEmpty())CoreUtil.msg(s,"Richest player: "+rich.getFirst().name()+" "+CoreUtil.money(rich.getFirst().balance()));List<NetWorthService.Row> factions=netWorth.rankings();if(!factions.isEmpty())CoreUtil.msg(s,"Top faction net worth: "+factions.getFirst().name()+" "+CoreUtil.money(factions.getFirst().value()));db.pruneEconomy(now-7776000000L);}
    private void economyWindow(CommandSender s,String label,List<Database.EconomyTotal> totals){Map<String,Double> m=new HashMap<>();for(Database.EconomyTotal total:totals)m.put(total.category(),total.amount());double normal=m.getOrDefault("MOB_NORMAL",0.0),elite=m.getOrDefault("ELITE",0.0)+m.getOrDefault("BOSS",0.0),milestones=m.getOrDefault("MILESTONE",0.0)+m.getOrDefault("EVENT",0.0),selling=m.getOrDefault("SHOP_SELL",0.0),removed=Math.max(0,-m.entrySet().stream().filter(e->e.getValue()<0).mapToDouble(Map.Entry::getValue).sum()),net=m.values().stream().mapToDouble(Double::doubleValue).sum();if(Math.abs(net)<.0001)net=0;s.sendMessage("§6§lECONOMY • "+label);s.sendMessage("§7Normal mobs: §a"+CoreUtil.money(normal)+"  §7Elites/bosses: §a"+CoreUtil.money(elite));s.sendMessage("§7Milestones/events: §a"+CoreUtil.money(milestones)+"  §7Shop selling: §a"+CoreUtil.money(selling));s.sendMessage("§7Removed: §c"+CoreUtil.money(removed)+"  §7Net creation: "+(net>=0?"§a":"§c")+CoreUtil.money(net));}
    private void adminRelic(CommandSender s,String[] args){
        if(args.length<2){relicHelp(s);return;}
        if(args[1].equalsIgnoreCase("remove")){if(args.length<3){CoreUtil.error(s,"Usage: /smp relic remove <key>");return;}if(relics.remove(args[2]))CoreUtil.msg(s,"Relic removed from circulation — it will resurface naturally in "+relics.lostReentryDays()+" days, same as any other lost relic.");else CoreUtil.error(s,"Relic not found.");return;}
        if(args[1].equalsIgnoreCase("forcerespawn")){if(args.length<3){CoreUtil.error(s,"Usage: /smp relic forcerespawn <key>");return;}if(relics.forceEligible(args[2]))CoreUtil.msg(s,"Forced "+args[2]+" to become eligible to resurface immediately.");else CoreUtil.error(s,"That relic isn't currently LOST/recycling.");return;}
        if(!args[1].equalsIgnoreCase("give")){CoreUtil.error(s,"Unknown relic action. Use /smp relic for options.");return;}
        if(args.length<4){CoreUtil.error(s,"Usage: /smp relic give <player> <key>");return;}
        Player target=getServer().getPlayerExact(args[2]);if(target==null||!relics.give(target,args[3]))CoreUtil.error(s,"Player unavailable, key invalid, or already ACTIVE (owned by someone).");else CoreUtil.msg(s,"Relic given.");
    }
    private void adminFaction(CommandSender s,String[] args){if(args.length<2){factionHelp(s);return;}if(!List.of("inspect","resize","resetclaim","recalc").contains(args[1].toLowerCase(Locale.ROOT))){CoreUtil.error(s,"Unknown faction action. Use /smp faction for options.");return;}if(args[1].equalsIgnoreCase("recalc")){netWorth.recalculateLoaded();CoreUtil.msg(s,"Loaded faction assets and auction market estimates recalculated.");return;}if(args.length<3){CoreUtil.error(s,"Faction name required for '"+args[1]+"'.");return;}Database.FactionRow f=db.factionByName(args[2]);if(f==null){CoreUtil.error(s,"Faction not found.");return;}if(args[1].equalsIgnoreCase("inspect")){FactionService.Claim claim=factions.claimOf(f);String claimInfo=claim==null?"none":claim.size()+"x"+claim.size()+" | center "+((claim.minX()+claim.maxX())/2)+", "+((claim.minZ()+claim.maxZ())/2)+" | bounds X "+claim.minX()+".."+claim.maxX()+", Z "+claim.minZ()+".."+claim.maxZ();CoreUtil.msg(s,f.name()+" members="+String.join(",",db.factionMembers(f.id()))+" bank="+CoreUtil.money(f.balance())+" claim="+claimInfo+" net-worth="+CoreUtil.money(netWorth.value(f.id())));}else if(args[1].equalsIgnoreCase("resize")&&args.length>3){if(f.tier()<0){CoreUtil.error(s,"Faction has no claim; use resetclaim first.");return;}int tier=Math.max(0,Math.min(5,Integer.parseInt(args[3])));db.setFactionTier(f.id(),tier);factions.refreshClaims();CoreUtil.msg(s,"Faction claim set to tier "+tier+".");}else if(args[1].equalsIgnoreCase("resetclaim")&&s instanceof Player p){db.setFactionCoreTier(f.id(),p.getLocation().getBlockX(),p.getLocation().getBlockZ(),0);factions.refreshClaims();CoreUtil.msg(s,"Claim reset to 50 × 50 at your location.");}else CoreUtil.error(s,"Usage: /smp faction "+args[1]+" <name>"+(args[1].equalsIgnoreCase("resize")?" <tier>":""));}

    /** Investigation tools: admin-only (gated by admin() above), offline-capable (everything here reads from
     *  SQLite by stored player name, never requires the target online), and every lookup is itself
     *  audit-logged (see db.logAudit calls below) — separate, single-purpose commands rather than one
     *  overloaded screen, per the moderation-tooling requirement these were built for. Never reachable from
     *  a player-facing command; nothing here is exposed outside /ashfall (admin-gated). */
    private Database.PlayerRow requirePlayer(CommandSender s,String name){Database.PlayerRow row=db.player(CoreUtil.id(name));if(row==null)CoreUtil.error(s,"That player has not joined this server.");return row;}
    private int pageArg(String[] args,int index){if(args.length<=index)return 1;try{return Math.max(1,Integer.parseInt(args[index]));}catch(NumberFormatException e){return 1;}}
    private void printChatLog(CommandSender s,List<Database.ChatLogRow> rows,boolean showRecipient){
        if(rows.isEmpty()){CoreUtil.msg(s,"No entries on this page.");return;}
        for(Database.ChatLogRow row:rows)CoreUtil.msg(s,"§8"+java.time.Instant.ofEpochMilli(row.createdAt())+" §7"+row.senderName()+(showRecipient&&row.recipientName()!=null?" → "+row.recipientName():"")+"§7: §f"+row.message());
    }
    private void adminChatLog(CommandSender s,String[] args){
        if(args.length<2){CoreUtil.error(s,"Usage: /ashfall chatlog <player> [page]");return;}
        Database.PlayerRow target=requirePlayer(s,args[1]);if(target==null)return;
        int page=pageArg(args,2),size=10;
        db.logAudit(adminName(s),"INVESTIGATE_CHATLOG","target="+target.name()+" page="+page);
        CoreUtil.msg(s,"Public chat — "+target.name()+" (page "+page+"):");
        printChatLog(s,db.chatBySender(target.id(),size,(page-1)*size),false);
    }
    private void adminDmLog(CommandSender s,String[] args){
        if(args.length<3){CoreUtil.error(s,"Usage: /ashfall dmlog <player1> <player2> [page]");return;}
        Database.PlayerRow a=requirePlayer(s,args[1]);if(a==null)return;
        Database.PlayerRow b=requirePlayer(s,args[2]);if(b==null)return;
        int page=pageArg(args,3),size=10;
        db.logAudit(adminName(s),"INVESTIGATE_DMLOG","between="+a.name()+","+b.name()+" page="+page);
        CoreUtil.msg(s,"Direct messages — "+a.name()+" ↔ "+b.name()+" (page "+page+"):");
        printChatLog(s,db.chatBetween(a.id(),b.id(),size,(page-1)*size),true);
    }
    private void adminFactionChatLog(CommandSender s,String[] args){
        if(args.length<2){CoreUtil.error(s,"Usage: /ashfall factionchatlog <faction> [page]");return;}
        Database.FactionRow f=db.factionByName(args[1]);if(f==null){CoreUtil.error(s,"Faction not found.");return;}
        int page=pageArg(args,2),size=10;
        db.logAudit(adminName(s),"INVESTIGATE_FACTIONCHATLOG","faction="+f.name()+" page="+page);
        CoreUtil.msg(s,"Faction chat — "+f.name()+" (page "+page+"):");
        printChatLog(s,db.factionChatLog(f.id(),size,(page-1)*size),false);
    }
    private void adminLastLoc(CommandSender s,String[] args){
        if(args.length<2){CoreUtil.error(s,"Usage: /ashfall lastloc <player>");return;}
        Database.PlayerRow target=requirePlayer(s,args[1]);if(target==null)return;
        db.logAudit(adminName(s),"INVESTIGATE_LASTLOC","target="+target.name());
        Player online=getServer().getPlayerExact(target.name());
        Location loc=online!=null?online.getLocation():db.lastLocation(target.id());
        if(loc==null){CoreUtil.msg(s,target.name()+": no location on record.");return;}
        CoreUtil.msg(s,target.name()+" ("+(online!=null?"online now":"last seen "+java.time.Instant.ofEpochMilli(db.lastSeen(target.id())))+"): "+loc.getWorld().getName()+" "+loc.getBlockX()+", "+loc.getBlockY()+", "+loc.getBlockZ());
    }
    private void adminHomes(CommandSender s,String[] args){
        if(args.length<2){CoreUtil.error(s,"Usage: /ashfall homes <player>");return;}
        Database.PlayerRow target=requirePlayer(s,args[1]);if(target==null)return;
        db.logAudit(adminName(s),"INVESTIGATE_HOMES","target="+target.name());
        List<Database.HomeRow> homes=db.homes(target.id(),"PERSONAL");
        if(homes.isEmpty()){CoreUtil.msg(s,target.name()+" has no personal homes set.");return;}
        CoreUtil.msg(s,target.name()+"'s homes:");
        for(Database.HomeRow home:homes){Location l=home.location();String where=l.getWorld()==null?"unknown world":l.getWorld().getName()+" "+l.getBlockX()+", "+l.getBlockY()+", "+l.getBlockZ();CoreUtil.msg(s,"• "+home.name()+" — "+where);}
    }
    private void adminFactionInfo(CommandSender s,String[] args){
        if(args.length<2){CoreUtil.error(s,"Usage: /ashfall factioninfo <player>");return;}
        Database.PlayerRow target=requirePlayer(s,args[1]);if(target==null)return;
        db.logAudit(adminName(s),"INVESTIGATE_FACTIONINFO","target="+target.name());
        Database.FactionRow f=db.factionOf(target.id());
        if(f==null){CoreUtil.msg(s,target.name()+" is not in a faction.");return;}
        FactionService.Claim claim=factions.claimOf(f);
        String claimInfo=claim==null?"none":claim.size()+"x"+claim.size()+" | center "+((claim.minX()+claim.maxX())/2)+", "+((claim.minZ()+claim.maxZ())/2)+" | bounds X "+claim.minX()+".."+claim.maxX()+", Z "+claim.minZ()+".."+claim.maxZ();
        CoreUtil.msg(s,target.name()+" — "+f.name()+" ["+f.tag()+"] | members="+String.join(",",db.factionMembers(f.id()))+" | bank="+CoreUtil.money(f.balance())+" | claim="+claimInfo+" | net-worth="+CoreUtil.money(netWorth.value(f.id())));
    }
    private void selfTest(CommandSender s){CoreUtil.msg(s,"Running non-destructive migration and persistence tests...");for(String result:db.selfTest())CoreUtil.msg(s,result);List<Integer> sizes=getConfig().getIntegerList("claims.sizes"),costs=getConfig().getIntegerList("claims.expansion-costs");boolean ok=sizes.size()==6&&costs.size()==5&&CoreUtil.compact(2590).length()<=5&&getConfig().getDouble("merchants.shop.buy-multiplier",1)<1&&getConfig().getDouble("merchants.shop.sell-multiplier",1)>1&&getConfig().getDouble("mob-money.minimum-multiplier",0)>.0&&getConfig().getDouble("spawner-breaking.money-reward",0)==25&&getConfig().getInt("spawner-breaking.exp-max",0)>=getConfig().getInt("spawner-breaking.exp-min",1)&&getConfig().getInt("auctions.max-active-per-player",0)==30&&getConfig().getDouble("bank.loans.daily-interest-percent",0)>0&&getConfig().getDouble("bank.loans.overdue-garnish-percent",0)>0&&getConfig().getDouble("bank.loans.maximum-limit",-1)==0&&getConfig().getInt("homes.personal.upgrades.10",0)==5000000&&getConfig().getLong("graves.lifetime-hours",0)==48&&getConfig().getDouble("performance.world-borders.sizes.overworld",0)==225000&&getConfig().getDouble("performance.world-borders.sizes.nether",0)==57000&&getConfig().getDouble("performance.world-borders.sizes.end",0)==175000&&getConfig().getDouble("progression.vanguard-economic-target",0)==250000;for(int i=1;i<sizes.size();i++)ok&=sizes.get(i)>sizes.get(i-1);for(int i=1;i<costs.size();i++)ok&=costs.get(i)>costs.get(i-1);CoreUtil.msg(s,"Claim/economy/bank/auction/home/border configuration: "+(ok?"ok":"FAILED"));CoreUtil.msg(s,"Money parser, smart combat links and guide selection: "+(CoreUtil.moneyParserSelfTest()&&teleports.combatSelfTest()&&guides.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Seven-rank requirement progression: "+(progress.rankSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Shop, Dragon Egg and Villager Capsule checks: "+(shop.selfTest()&&capsules.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Stacked/recovery spawner checks: "+(spawners.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Shared boss participant scaling/health-percent math: "+(bosses.scalingSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Boss/elite health-safety clamp: "+(bosses.bossHealthSafetySelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Epic/Legendary rarity, scaling and phase configuration: "+(bosses.eliteTierSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Active-play event tiers/protected buffer/effect sanitation: "+(bosses.eventTimingSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Marketplace, settings, shards and weekly Dragon: "+(marketplace.selfTest()&&settings.selfTest()&&shards.selfTest()&&weeklyDragon.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Live bulletin configuration: "+(bulletin.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Punishment tier configuration: "+(punishments.selfTest()?"ok":"FAILED"));String old=db.state("selftest_1_7_0_restart");db.state("selftest_1_7_0_restart",Long.toString(System.currentTimeMillis()));CoreUtil.msg(s,"1.7.0 restart marker: "+(old==null?"created; run after restart":"read previous value successfully"));}

    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        String name=command.getName().toLowerCase(Locale.ROOT);
        if(name.equals("ashfall")&&sender instanceof Player player&&!isAdmin(player))return List.of();
        if(name.equals("admin")&&!(sender instanceof ConsoleCommandSender)&&!(sender instanceof RemoteConsoleCommandSender))return List.of();
        if(args.length==1&&(name.equals("tpa")||name.equals("pay")||name.equals("bounty")||name.equals("stats")||name.equals("admin")))return publicOnlineNames(sender,args[0]);
        if(args.length==1&&name.equals("msg")){List<String> options=new ArrayList<>(publicOnlineNames(sender,""));options.addAll(List.of("block","unblock"));return filter(args[0],options);}
        if(args.length==2&&name.equals("msg")&&(args[0].equalsIgnoreCase("block")||args[0].equalsIgnoreCase("unblock")))return publicOnlineNames(sender,args[1]);
        if(args.length==1&&(name.equals("warn")||name.equals("warnings"))&&sender instanceof Player p&&isAdmin(p))return onlineNames(sender,args[0]);
        if(args.length==1&&name.equals("rtp"))return filter(args[0],List.of("queue"));
        if(name.equals("f")&&args.length==2&&args[0].equalsIgnoreCase("invite"))return publicOnlineNames(sender,args[1]);
        if(name.equals("f")&&args.length==2&&args[0].equalsIgnoreCase("locate")&&sender instanceof Player p)return filter(args[1],getServer().getOnlinePlayers().stream().filter(target->!target.equals(p)&&factions.friendly(p,target)).map(nicknames::displayName).toList());
        if(name.equals("f")&&args.length==2&&Set.of("ally","truce","storage","info").contains(args[0].toLowerCase(Locale.ROOT))){
            Database.FactionRow own=sender instanceof Player p?db.factionOf(CoreUtil.id(p)):null;
            return filter(args[1],db.factions().stream().filter(faction->own==null||faction.id()!=own.id()).map(Database.FactionRow::name).toList());
        }
        if(name.equals("f")&&args.length==2&&Set.of("kick","leader","coleader","co-leader").contains(args[0].toLowerCase(Locale.ROOT))&&sender instanceof Player p){
            Database.FactionRow own=db.factionOf(CoreUtil.id(p));if(own==null)return List.of();List<String> members=new ArrayList<>(db.factionMembers(own.id()).stream().filter(member->!member.equalsIgnoreCase(p.getName())).map(nicknames::displayName).toList());if(args[0].toLowerCase(Locale.ROOT).contains("co"))members.add("remove");return filter(args[1],members);
        }
        if(name.equals("home")&&args.length==1&&sender instanceof Player p){List<String> choices=new ArrayList<>(List.of("list","set","delete","rename","buy","go","help"));choices.addAll(db.homes(CoreUtil.id(p),"PERSONAL").stream().map(Database.HomeRow::name).toList());return filter(args[0],choices);}
        if((name.equals("delhome")||name.equals("renamehome"))&&args.length==1&&sender instanceof Player p)return filter(args[0],db.homes(CoreUtil.id(p),"PERSONAL").stream().map(Database.HomeRow::name).toList());
        if(name.equals("home")&&args.length==2&&sender instanceof Player p&&Set.of("delete","del","remove","rename","go").contains(args[0].toLowerCase(Locale.ROOT)))return filter(args[1],db.homes(CoreUtil.id(p),"PERSONAL").stream().map(Database.HomeRow::name).toList());
        if(name.equals("home")&&args.length==2&&args[0].equalsIgnoreCase("buy"))return filter(args[1],List.of("confirm"));
        if(name.equals("buyhome")&&args.length==1)return filter(args[0],List.of("confirm"));
        if(name.equals("homes")&&args.length==1)return filter(args[0],List.of("locate"));
        if(name.equals("relics")&&args.length==2&&args[0].equalsIgnoreCase("trace")&&sender instanceof Player p)return filter(args[1],db.relicLifecycles().stream().filter(row->"ACTIVE".equals(row.status())&&row.owner().equals(CoreUtil.id(p))).map(Database.RelicLifecycleRow::key).toList());
        if(args.length==1)return switch(name){case"f"->{List<String> options=new ArrayList<>(List.of("create","claim","unclaim","borders","networth","leaderboard","relations","ally","truce","storage","invite","accept","kick","leader","coleader","leave","disband","info","tag","deposit","withdraw","expand","sethome","home","homes","delhome","buyhome","history","locate"));options.addAll(publicOnlineNames(sender,""));yield filter(args[0],options);}case"shop"->filter(args[0],List.of("luxury","buy","sell","sellall"));case"settings"->filter(args[0],List.of("account","confirmations"));case"ah"->filter(args[0],List.of("sell","collect","cancel"));case"enderchest"->filter(args[0],sender instanceof Player viewer&&isAdmin(viewer)?List.of("upgrade","page","inspect"):List.of("upgrade","page"));case"events"->filter(args[0],List.of("track"));case"guide","rules"->filter(args[0],List.of("English","العربية"));case"leaderboards"->filter(args[0],List.of("money","networth","factions","bosses","kills","deaths","mobs","bounties","events","playtime"));case"relics"->filter(args[0],List.of("trace"));case"ashfall"->filter(args[0],List.of("help","balance","economy","boss","elite","event","merchant","bulletin","feedback","faction","spawnclaim","relic","grave","border","setspawn","reload","debug","selftest","vanish","spectate","unspectate","audit","shard","progressrepair","cooldowns","bounty","dragon","replay","chatlog","dmlog","factionchatlog","lastloc","homes","factioninfo"));case"nickname"->filter(args[0],List.of("random","off"));default->List.of();};
        if(name.equals("shop")&&args.length==2&&(args[0].equalsIgnoreCase("buy")||args[0].equalsIgnoreCase("sell")))return shop.itemNames(args[0].equalsIgnoreCase("sell"),args[1]);
        if(name.equals("shop")&&args.length==2&&args[0].equalsIgnoreCase("sellall"))return filter(args[1],List.of("chest"));
        if(name.equals("f")&&args.length==2&&(args[0].equalsIgnoreCase("home")||args[0].equalsIgnoreCase("delhome"))&&sender instanceof Player p){Database.FactionRow faction=db.factionOf(CoreUtil.id(p));return faction==null?List.of():filter(args[1],db.homes(Long.toString(faction.id()),"FACTION").stream().map(Database.HomeRow::name).toList());}
        if(name.equals("enderchest")&&args.length==2&&args[0].equalsIgnoreCase("upgrade"))return filter(args[1],List.of("confirm"));
        if(name.equals("enderchest")&&args.length==2&&args[0].equalsIgnoreCase("inspect")&&sender instanceof Player viewer&&isAdmin(viewer))return onlineNames(sender,args[1]);
        if(name.equals("enderchest")&&args.length==2&&args[0].equalsIgnoreCase("page")&&sender instanceof Player p)return filter(args[1],java.util.stream.IntStream.rangeClosed(1,enderChests.displayPages(p)).mapToObj(Integer::toString).toList());
        if(name.equals("f")&&args.length==2&&args[0].equalsIgnoreCase("borders"))return filter(args[1],List.of("on","off"));
        if(name.equals("f")&&args.length==2&&Set.of("expand","buyhome","unclaim").contains(args[0].toLowerCase(Locale.ROOT)))return filter(args[1],List.of("confirm"));
        if(name.equals("events")&&args.length==2&&args[0].equalsIgnoreCase("track"))return filter(args[1],List.of("on","off"));
        if(name.equals("ashfall")&&args.length==2){
            return switch(args[0].toLowerCase(Locale.ROOT)){
                case"help"->filter(args[1],List.of("economy","events","factions","merchants","feedback","relics","maintenance"));
                case"balance"->filter(args[1],List.of("set","add","take"));
                case"boss"->filter(args[1],List.of("spawn","here","despawn"));
                case"elite"->filter(args[1],List.of("stats","uncommon","rare","epic","legendary","miniboss"));
                case"event"->filter(args[1],List.of("resource","elitehunt","treasure","koth","worldboss","stop"));
                case"economy"->filter(args[1],List.of("report"));
                case"feedback"->filter(args[1],List.of("notify","list","view","done","reopen","delete"));
                case"faction"->filter(args[1],List.of("inspect","resize","resetclaim","recalc"));
                case"merchant"->filter(args[1],List.of("spawn","remove"));
                case"bulletin"->filter(args[1],List.of("place","remove","refresh"));
                case"spawnclaim"->filter(args[1],List.of("select","info","clear"));
                case"relic"->filter(args[1],List.of("give","remove","forcerespawn"));
                case"grave"->filter(args[1],List.of("repair"));
                case"border"->filter(args[1],List.of("status","apply","restore"));
                case"shard"->filter(args[1],List.of("give","remove","set"));
                case"bounty"->filter(args[1],List.of("approve","reject","remove","inspect"));
                case"dragon"->filter(args[1],List.of("status","start"));
                case"replay"->filter(args[1],List.of("killer","victim"));
                default->List.of();
            };
        }
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("replay"))return onlineNames(sender,args[2]);
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("merchant")){if(args[1].equalsIgnoreCase("spawn"))return filter(args[2],List.of("boss","event","shop","auction","banker"));if(args[1].equalsIgnoreCase("remove"))return filter(args[2],List.of("nearest"));}
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("boss")&&Set.of("spawn","here").contains(args[1].toLowerCase(Locale.ROOT)))return filter(args[2],List.of("ashen","iron","piglin"));
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("elite")&&List.of("uncommon","rare","epic","legendary","miniboss").contains(args[1].toLowerCase(Locale.ROOT)))return filter(args[2],List.of("here"));
        if(name.equals("ashfall")&&args.length==4&&args[0].equalsIgnoreCase("relic")&&args[1].equalsIgnoreCase("give"))return filter(args[3],new ArrayList<>(relics.keys()));
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("relic")&&args[1].equalsIgnoreCase("remove"))return filter(args[2],new ArrayList<>(relics.keys()));
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("relic")&&args[1].equalsIgnoreCase("forcerespawn"))return filter(args[2],new ArrayList<>(relics.keys()));
        if(name.equals("ashfall")&&args.length==4&&args[0].equalsIgnoreCase("faction")&&args[1].equalsIgnoreCase("resize"))return filter(args[3],List.of("0","1","2","3","4","5"));
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("feedback")&&args[1].equalsIgnoreCase("notify"))return filter(args[2],List.of("on","off"));
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("balance")&&Set.of("set","add","take").contains(args[1].toLowerCase(Locale.ROOT)))return onlineNames(sender,args[2]);
        if(name.equals("ashfall")&&args.length==2&&Set.of("spectate","progressrepair","cooldowns").contains(args[0].toLowerCase(Locale.ROOT)))return onlineNames(sender,args[1]);
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("shard")&&Set.of("give","remove","set").contains(args[1].toLowerCase(Locale.ROOT)))return onlineNames(sender,args[2]);
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("cooldowns"))return filter(args[2],List.of("rtp","teleport"));
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("relic")&&args[1].equalsIgnoreCase("give"))return onlineNames(sender,args[2]);
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("faction")&&Set.of("inspect","resize","resetclaim").contains(args[1].toLowerCase(Locale.ROOT)))return filter(args[2],db.factions().stream().map(Database.FactionRow::name).toList());
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("bounty")&&Set.of("remove","inspect").contains(args[1].toLowerCase(Locale.ROOT)))return onlineNames(sender,args[2]);
        if(name.equals("ashfall")&&args.length==4&&args[0].equalsIgnoreCase("bounty")&&args[1].equalsIgnoreCase("remove"))return filter(args[3],List.of("refund"));
        return List.of();
    }
    /** Vanished/spectating admins are excluded for everyone except another admin — "behave publicly as if they
     *  do not exist" per the moderation spec, while still letting admins target a spectating colleague if needed. */
    private List<String> onlineNames(CommandSender sender,String prefix){boolean senderIsAdmin=sender instanceof Player viewer&&isAdmin(viewer);return getServer().getOnlinePlayers().stream().filter(p->!p.equals(sender)).filter(p->senderIsAdmin||!adminTools.isHiddenFromPublic(p)).map(p->senderIsAdmin?p.getName():nicknames.displayName(p)).filter(n->n.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).sorted(String.CASE_INSENSITIVE_ORDER).toList();}
    /** Same as onlineNames() but with no admin exemption — for general, non-admin-tool commands (tpa, msg, pay,
     *  bounty, stats, f invite) where even an admin sender should be treated exactly like everyone else, matching
     *  what NicknameService.findVisiblePlayer() (the actual target-resolution check these same commands use)
     *  already does unconditionally. Without this an admin could see a hidden colleague suggested in autocomplete
     *  and then get "that player is not available" upon actually sending it — the exact inconsistency reported
     *  live. Genuine admin tools (enderchest inspect, warn, /ashfall subcommands) keep using onlineNames() above,
     *  since admins legitimately need to target a hidden colleague there. */
    private List<String> publicOnlineNames(CommandSender sender,String prefix){return getServer().getOnlinePlayers().stream().filter(p->!p.equals(sender)).filter(p->!adminTools.isHiddenFromPublic(p)).map(nicknames::displayName).filter(n->n.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).sorted(String.CASE_INSENSITIVE_ORDER).toList();}
    private List<String> filter(String prefix,List<String> values){String lower=prefix.toLowerCase(Locale.ROOT);return values.stream().filter(value->value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();}
    private int parsePage(String[] args){if(args.length==0)return 1;try{return Math.max(1,Integer.parseInt(args[0]));}catch(NumberFormatException e){return 1;}}
    private Integer parsePageArg(String raw){try{return Math.max(1,Integer.parseInt(raw));}catch(NumberFormatException e){return null;}}
    private String trim(String value,int max){return value.length()<=max?value:value.substring(0,max-1)+"…";}
}
