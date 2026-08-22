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
    private Database db;private DuelMapService duelMaps;private CoreEconomy economy;private BankService bank;private TeleportService teleports;private FactionService factions;private ShopService shop;private AuctionService auctions;private ProgressService progress;private RelicService relics;private BossEventService bosses;private BountyService bounties;private SpawnerService spawners;private SpawnClaimService spawnClaims;private EnderChestService enderChests;private NetWorthService netWorth;private MerchantService merchants;private VillagerCapsuleService capsules;private GraveService graves;private BulletinService bulletin;private UIService ui;private TabIntegration tabIntegration;private GuideService guides;private MessagingService messaging;private SettingsService settings;private AccountService account;private RegistrationService registration;private ConfirmationService confirmations;private ShardService shards;private MarketplaceService marketplace;private WeeklyDragonService weeklyDragon;private VillagerDiscountService villagerDiscounts;private WorldBorderService worldBorders;private GrimCompatibility grimCompatibility;private NicknameService nicknames;private TrustedAdminService trustedAdmins;private AdminToolsService adminTools;private DiscordReminderService discordReminders;private OrdersService ordersService;private AfkService afk;private PunishmentService punishments;private ReplayIntegration replay;private ObsidianDurabilityService obsidian;private IpBanService ipBans;private MonumentService monuments;private ModerationService moderation;private TradeTaxService tradeTax;private TaskMasterService taskMaster;private IndustrialHopperService industrialHoppers;private DiscardedVaultService vault;private ArenaService arena;private SpawnerShopService spawnerShop;private SpectacleService spectacle;private PacketNametagService packetNametags;
    private final Map<UUID,Long> feedbackCooldowns=new HashMap<>();
    Database db(){return db;} UIService ui(){return ui;} ProgressService progress(){return progress;} TeleportService teleports(){return teleports;} SpawnClaimService spawnClaims(){return spawnClaims;}EnderChestService enderChests(){return enderChests;}NetWorthService netWorth(){return netWorth;}BankService bank(){return bank;}VillagerCapsuleService capsules(){return capsules;}FactionService factions(){return factions;}BossEventService bosses(){return bosses;}MessagingService messaging(){return messaging;}GraveService graves(){return graves;}BulletinService bulletin(){return bulletin;}SettingsService settings(){return settings;}AccountService account(){return account;}RegistrationService registration(){return registration;}ConfirmationService confirmations(){return confirmations;}ShardService shards(){return shards;}MarketplaceService marketplace(){return marketplace;}WeeklyDragonService weeklyDragon(){return weeklyDragon;}VillagerDiscountService villagerDiscounts(){return villagerDiscounts;}SpawnerService spawners(){return spawners;}NicknameService nicknames(){return nicknames;}TabIntegration tab(){return tabIntegration;}AfkService afk(){return afk;}RelicService relics(){return relics;}AdminToolsService adminTools(){return adminTools;}PunishmentService punishments(){return punishments;}ReplayIntegration replay(){return replay;}TrustedAdminService trustedAdmins(){return trustedAdmins;}IpBanService ipBans(){return ipBans;}ModerationService moderation(){return moderation;}PacketNametagService packetNametags(){return packetNametags;}
    OrdersService orders(){return ordersService;}
    TaskMasterService taskMaster(){return taskMaster;}
    IndustrialHopperService industrialHoppers(){return industrialHoppers;}
    DiscardedVaultService vault(){return vault;}
    ArenaService arena(){return arena;}
    DuelMapService duelMaps(){return duelMaps;}
    ShopService shop(){return shop;} SpawnerShopService spawnerShop(){return spawnerShop;} SpectacleService spectacle(){return spectacle;}
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
        spectacle=new SpectacleService(this);tradeTax=new TradeTaxService(this);taskMaster=new TaskMasterService(this);getServer().getPluginManager().registerEvents(taskMaster,this);industrialHoppers=new IndustrialHopperService(this);getServer().getPluginManager().registerEvents(industrialHoppers,this);vault=new DiscardedVaultService(this);getServer().getPluginManager().registerEvents(vault,this);spawnerShop=new SpawnerShopService(this);getServer().getPluginManager().registerEvents(spawnerShop,this);duelMaps=new DuelMapService(this);getServer().getPluginManager().registerEvents(duelMaps,this);arena=new ArenaService(this);getServer().getPluginManager().registerEvents(arena,this);packetNametags=new PacketNametagService(this);spawners.startConsolidation();weeklyDragon=new WeeklyDragonService(this);graves=new GraveService(this);bulletin=new BulletinService(this);guides=new GuideService(this);messaging=new MessagingService(this);obsidian=new ObsidianDurabilityService(this,factions);
        villagerDiscounts=new VillagerDiscountService(this);
        worldBorders=new WorldBorderService(this);
        for(World world:getServer().getWorlds())try{world.setGameRule(GameRule.LOCATOR_BAR,false);}catch(Throwable ignored){}
        ui=new UIService(this,bosses,netWorth);nicknames=new NicknameService(this);tabIntegration=new TabIntegration(this,factions);
        adminTools=new AdminToolsService(this);discordReminders=new DiscordReminderService(this);ordersService=new OrdersService(this);afk=new AfkService(this);punishments=new PunishmentService(this);ipBans=new IpBanService(this);monuments=new MonumentService(this);moderation=new ModerationService(this);
        getServer().getPluginManager().registerEvents(new GameplayListener(this,factions,teleports,shop,auctions,bosses,bounties,relics,progress,spawners,spawnClaims,netWorth,merchants,capsules,graves,obsidian),this);
        getServer().getPluginManager().registerEvents(trustedAdmins,this);
        for(Listener listener:List.of(packetNametags,enderChests,bank,settings,account,confirmations,shards,marketplace,graves,bulletin,guides,progress,netWorth,villagerDiscounts,nicknames,adminTools,ordersService,relics,spawnClaims,afk,bounties,ipBans))
            getServer().getPluginManager().registerEvents(listener,this);
        if(getServer().getPluginManager().isPluginEnabled("GrimAC"))grimCompatibility=new GrimCompatibility(this);
        /** PunisherX's ban broadcast is gated behind punisherx.see.ban, which defaults to op-only — there's
         *  no permissions plugin installed to grant it broadly, and patching PunisherX's own jar isn't
         *  something this codebase should do. Overriding a permission's registered default at runtime is the
         *  standard, safe way to do this cross-plugin: Bukkit registers every plugin's plugin.yml permissions
         *  before any plugin's onEnable runs, so PunisherX's node already exists in the registry by the time
         *  this runs, regardless of load order between the two plugins. Only the BAN broadcast is made public
         *  this way — warn/mute/kick notifications intentionally stay staff-only, matching the spec (only
         *  bans are meant to be publicly announced). */
        org.bukkit.permissions.Permission banBroadcast=getServer().getPluginManager().getPermission("punisherx.see.ban");
        if(banBroadcast!=null)banBroadcast.setDefault(org.bukkit.permissions.PermissionDefault.TRUE);
        replay=new ReplayIntegration(this);
        for(String name:List.of("f","balance","pay","home","sethome","delhome","renamehome","homes","buyhome","tpa","tpahere","tpaccept","tpdeny","spawn","rtp","back","msg","reply","shop","luxuryshop","shardshop","settings","ah","bounty","bounties","events","relics","leaderboards","guide","smphelp","role","sidebar","feedback","stats","progress","history","graves","enderchest","ashfall","nickname","discord","admin","shout","afk","myorders")){
            PluginCommand command=getCommand(name);if(command!=null){command.setExecutor(this);command.setTabCompleter(this);}
        }
        getLogger().info("SMPCore 1.7.0 enabled: marketplace, accessibility settings, shards, faction relations and weekly Dragon are ready.");
    }
    @Override public void onDisable(){if(bosses!=null)bosses.reconcileForcedChunks();if(enderChests!=null)enderChests.shutdown();if(spawnClaims!=null)spawnClaims.shutdown();if(discordReminders!=null)discordReminders.shutdown();if(adminTools!=null)adminTools.shutdown();if(trustedAdmins!=null)trustedAdmins.shutdown();if(grimCompatibility!=null)grimCompatibility.shutdown();if(tabIntegration!=null)tabIntegration.shutdown();if(teleports!=null)teleports.shutdown();if(ui!=null)ui.shutdown();if(bulletin!=null)bulletin.shutdown();if(graves!=null)graves.shutdown();if(obsidian!=null)obsidian.shutdown();if(weeklyDragon!=null)weeklyDragon.shutdown();if(spawners!=null){spawners.stopConsolidation();spawners.shutdown();}if(shards!=null)shards.shutdown();if(settings!=null)settings.shutdown();if(afk!=null)afk.shutdown();if(factions!=null)factions.shutdown();if(netWorth!=null)netWorth.shutdown();if(bosses!=null)bosses.shutdown();if(relics!=null)relics.shutdown();if(progress!=null)progress.shutdown();if(tradeTax!=null)tradeTax.shutdown();if(ordersService!=null)ordersService.shutdown();if(taskMaster!=null)taskMaster.end();if(industrialHoppers!=null)industrialHoppers.shutdown();if(vault!=null)vault.shutdown();if(arena!=null)arena.shutdown();if(spectacle!=null)spectacle.shutdown();if(packetNametags!=null)packetNametags.shutdown();if(db!=null)db.close();}

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){String name=command.getName().toLowerCase(Locale.ROOT);if(name.equals("admin"))return consoleAdmin(sender,args);if(name.equals("fly"))return flyCommand(sender,args);if(name.equals("flyspeed"))return flySpeedCommand(sender,args);if(name.equals("ashfall"))return admin(sender,args);if(name.equals("shout"))return shout(sender,args);if(!(sender instanceof Player p)){CoreUtil.error(sender,"This command requires a player.");return true;}db.ensurePlayer(CoreUtil.id(p),p.getName(),getConfig().getDouble("starting-balance",250));shards.activity(p);return switch(name){case"f"->factions.command(p,args);case"balance"->{CoreUtil.msg(p,"Balance: "+CoreUtil.money(db.player(CoreUtil.id(p)).balance()));yield true;}case"pay"->pay(p,args);case"home"->teleports.homeCommand(p,args);case"sethome"->teleports.setPersonalHome(p,args.length>0?args[0]:"home");case"delhome"->teleports.deletePersonalHome(p,args.length>0?args[0]:"home");case"renamehome"->{if(args.length<2){CoreUtil.error(p,"Usage: /renamehome <old> <new>");yield true;}yield teleports.renamePersonalHome(p,args[0],args[1]);}case"buyhome"->teleports.buyPersonalHome(p,args.length>0&&args[0].equalsIgnoreCase("confirm"));case"tpa"->{if(args.length<1)CoreUtil.error(p,"Usage: /tpa <player>");else teleports.tpa(p,args[0]);yield true;}case"tpahere"->{if(args.length<1)CoreUtil.error(p,"Usage: /tpahere <player>");else teleports.tpahere(p,args[0]);yield true;}case"tpaccept"->teleports.accept(p);case"tpdeny"->teleports.deny(p);case"spawn"->{teleports.warmup(p,teleports.spawn(),"server spawn");yield true;}case"rtp"->{if(args.length>0&&args[0].equalsIgnoreCase("queue")){yield teleports.toggleRtpQueue(p);}yield teleports.rtp(p);}case"msg"->messaging.message(p,args);case"reply"->messaging.reply(p,args);case"duel"->duel(p,args);case"orders"->{ordersService.openPublic(p);yield true;}case"order"->{ordersService.openPick(p,1,null);yield true;}case"shop"->shop.command(p,args);case"spawnershop"->{spawnerShop.open(p);yield true;}case"luxuryshop"->{marketplace.open(p,MarketplaceService.Section.LUXURY);yield true;}case"shardshop"->{marketplace.open(p,MarketplaceService.Section.SHARDS);yield true;}case"settings"->settings.command(p,args);case"ah"->auctions.command(p,args);case"bounty"->{if(args.length<2)CoreUtil.error(p,"Usage: /bounty <player> <amount>");else bounties.place(p,args[0],CoreUtil.parseMoney(args[1]));yield true;}case"bounties"->{bounties.list(p);yield true;}case"events"->{eventCommand(p,args);yield true;}case"relics"->{if(args.length>0&&args[0].equalsIgnoreCase("trace")){if(args.length<2)CoreUtil.error(p,"Usage: /relics trace <relic key>");else relics.trace(p,args[1]);}else relics.list(p);yield true;}case"leaderboards"->{leaderboards(p,args);yield true;}case"guide"->guides.command(p,args);case"rules"->guides.rulesCommand(p,args);case"smphelp"->{playerHelp(p);yield true;}case"role"->{CoreUtil.msg(p,"Your Ashfall role is "+roleName(p)+".");yield true;}case"sidebar"->progress.toggleSidebar(p);case"feedback"->{feedback(p,args);yield true;}case"stats"->progress.stats(p,args.length>0?args[0]:null);case"progress"->progress.show(p);case"history"->{int page=parsePage(args);progress.history(p,false,page);yield true;}case"homes"->teleports.listPersonalHomes(p,args.length>0&&args[0].equalsIgnoreCase("locate"));case"graves"->graves.command(p);case"enderchest"->enderChests.command(p,args);case"nickname"->nicknames.command(p,args);case"discord"->{p.sendMessage("§9Discord: §b§nhttps://discord.gg/G2FfuXjz8");yield true;}case"afk"->{boolean now=afk.toggle(p);CoreUtil.msg(p,now?"You are now AFK.":"Welcome back — no longer AFK.");yield true;}case"back"->{if(!isAdmin(p)){CoreUtil.error(p,"Only staff may use /back.");yield true;}yield teleports.back(p);}case"kit"->{
                if(!isAdmin(p)){CoreUtil.error(p,"Only staff may use /kit.");yield true;}
                if(args.length<1||!args[0].equalsIgnoreCase("test")){CoreUtil.error(p,"Usage: /kit test [player]");yield true;}
                /** Admin-only in both forms; the optional target lets staff outfit someone else for a
                 *  co-op test without handing them the permission. */
                Player recipient=p;
                if(args.length>1){
                    recipient=nicknames.findVisiblePlayer(args[1]);
                    if(recipient==null){CoreUtil.error(p,"Player not found or not online: "+args[1]);yield true;}
                }
                shards.giveTestKit(recipient);
                if(!recipient.equals(p)){
                    CoreUtil.msg(p,"Test kit given to "+recipient.getName()+".");
                    CoreUtil.msg(recipient,plugin_kitNotice(p));
                }
                db.logAudit(p.getName(),"KIT_TEST","target="+recipient.getName());
                yield true;
            }case"myorders"->{CoreUtil.error(p,"The orders marketplace is temporarily unavailable.");yield true;}default->false;};}
    private boolean pay(Player p,String[] args){if(args.length<2){CoreUtil.error(p,"Usage: /pay <player> <amount>");return true;}Player target=nicknames.findVisiblePlayer(args[0]);double amount=CoreUtil.parseMoney(args[1]);if(target==null||target.equals(p)){CoreUtil.error(p,"That player is not available.");return true;}if(amount<=0||!db.changeBalance(CoreUtil.id(p),-amount)){CoreUtil.error(p,"Invalid amount or insufficient balance.");return true;}
        /** The tax comes OUT OF the transfer rather than being added on top: the sender is debited exactly
         *  what they typed, the recipient receives the remainder and the difference goes to the Central
         *  Bank. Taking the tax from the amount is what makes it impossible for the sum to drift -- net and
         *  tax are defined so that they add back to the amount already withdrawn, so no rounding remainder
         *  can be created or lost. Same Math.round(amount*percent)/100 shape the bounty fee already uses. */
        /** Tripled while the Central Bank is in deficit -- see BankService.feeFactor(). */
        double taxPercent=Math.max(0,getConfig().getDouble("pay.tax-percent",1.0))*bank.feeFactor();
        double tax=Math.round(amount*taxPercent)/100.0,net=Math.round((amount-tax)*100)/100.0;
        tax=Math.round((amount-net)*100)/100.0;
        db.ensurePlayer(CoreUtil.id(target),target.getName(),getConfig().getDouble("starting-balance",250));
        creditEarned(CoreUtil.id(target),net,"PLAYER_PAYMENT");
        if(tax>0){bank.creditFee(tax,CoreUtil.id(p),"PLAYER_PAYMENT_TAX");db.recordEconomy(CoreUtil.id(p),"PAY_TAX",-tax,"PLAYER_PAYMENT");}
        CoreUtil.msg(p,"Paid "+nicknames.displayName(target)+" "+CoreUtil.money(net)+(tax>0?" ("+CoreUtil.money(amount)+" less a "+CoreUtil.money(tax)+" transfer tax)":"")+".");
        CoreUtil.msg(target,nicknames.displayName(p)+" paid you "+CoreUtil.money(net)+(tax>0?" after a "+CoreUtil.money(tax)+" transfer tax":"")+".");
        return true;}
    private boolean consoleAdmin(CommandSender sender,String[] args){
        if(!(sender instanceof ConsoleCommandSender)&&!(sender instanceof RemoteConsoleCommandSender)){CoreUtil.error(sender,"This command is console-only.");return true;}
        if(args.length==0||args[0].equalsIgnoreCase("list")){CoreUtil.msg(sender,"Admin accounts: "+trustedAdmins.accountList()+". AuthMe login is required.");return true;}
        if(args[0].equalsIgnoreCase("spawntrialtest")){
            if(args.length<2){CoreUtil.error(sender,"Usage: /admin spawntrialtest <player> — spawns one zombie tagged trial_spawner_mob and one untagged control zombie next to the player, then reports whether each survives the very next Hostile-Mobs-OFF sweep. Diagnostic only, deterministic (doesn't depend on waiting for a real trial spawner's cooldown).");return true;}
            Player target=getServer().getPlayerExact(args[1]);
            if(target==null){CoreUtil.error(sender,"Player not online: "+args[1]);return true;}
            org.bukkit.Location base=target.getLocation();
            org.bukkit.entity.Zombie tagged=target.getWorld().spawn(base.clone().add(2,0,0),org.bukkit.entity.Zombie.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
            tagged.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(this,"trial_spawner_mob"),org.bukkit.persistence.PersistentDataType.BYTE,(byte)1);
            org.bukkit.entity.Zombie control=target.getWorld().spawn(base.clone().add(-2,0,0),org.bukkit.entity.Zombie.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
            java.util.UUID taggedId=tagged.getUniqueId(),controlId=control.getUniqueId();
            CoreUtil.msg(sender,"[spawntrialtest] spawned tagged="+taggedId+" control="+controlId+" near "+target.getName()+" (hostile mobs "+(settings.naturalSpawns(target)?"ON":"OFF")+"). Waiting one sweep cycle (~6s)...");
            getServer().getScheduler().runTaskLater(this,()->{
                org.bukkit.entity.Entity taggedNow=getServer().getEntity(taggedId),controlNow=getServer().getEntity(controlId);
                boolean taggedAlive=taggedNow!=null&&taggedNow.isValid(),controlAlive=controlNow!=null&&controlNow.isValid();
                /** CoreUtil.msg(sender,...) here would silently go nowhere for an RCON sender — the connection
                 *  that issued this command has almost always already closed by the time this delayed task runs
                 *  6s later (confirmed live: this exact loss is why simlogin logs via getLogger() instead of
                 *  msg() for its own delayed result). getLogger().info() always lands in the console/log
                 *  regardless of whether the invoking connection is still open. */
                getLogger().info("[spawntrialtest] result: tagged(trial_spawner_mob)="+(taggedAlive?"SURVIVED":"removed")+" | control(untagged)="+(controlAlive?"SURVIVED":"removed")+(!controlAlive&&taggedAlive?" — exception working correctly (control removed, tagged exempt)":controlAlive?" — hostile mobs may be ON for this player, or sweep hasn't run yet":" — PROBLEM: tagged mob was removed too"));
            },120L);
            return true;
        }
        if(args[0].equalsIgnoreCase("bulletinpurge")){
            bulletin.purge(sender);
            return true;
        }
        if(args[0].equalsIgnoreCase("bossscan")){
            bosses.scanOrphanBosses(sender,args.length>1&&args[1].equalsIgnoreCase("clean"));
            return true;
        }
        /** Forensic-only: opens its OWN separate, read-only JDBC connection to an arbitrary sqlite file
         *  path (never the live plugin database connection, and never anything but SELECT) and dumps every
         *  ender_chest_items row for one player, across every page number that exists in that file (not
         *  just pages 1-2) — deserializing each item to its real Material/amount/display name rather than
         *  just a byte length, so a genuine item-content comparison across snapshots is possible. Built
         *  specifically to investigate a reported Ender Chest data-loss incident without touching any live
         *  server's actual database or requiring a restart. */
        if(args[0].equalsIgnoreCase("ecforensics")){
            if(args.length<4){CoreUtil.error(sender,"Usage: /admin ecforensics <label> <absoluteDbPath> <player>");return true;}
            String label=args[1],dbPath=args[2],targetName=args[3],targetId=CoreUtil.id(targetName);
            java.io.File dbFile=new java.io.File(dbPath);
            if(!dbFile.isFile()){CoreUtil.error(sender,"No such file: "+dbPath);return true;}
            try(java.sql.Connection conn=java.sql.DriverManager.getConnection("jdbc:sqlite:file:"+dbFile.getAbsolutePath().replace("\\","/")+"?mode=ro");
                java.sql.PreparedStatement stmt=conn.prepareStatement("SELECT page,slot,item FROM ender_chest_items WHERE player=? ORDER BY page,slot")){
                stmt.setString(1,targetId);
                int count=0;
                getLogger().info("[ecforensics:"+label+"] "+targetName+" ("+targetId+") from "+dbPath+":");
                try(java.sql.ResultSet rs=stmt.executeQuery()){
                    while(rs.next()){
                        count++;
                        int page=rs.getInt("page"),slot=rs.getInt("slot");
                        byte[] bytes=rs.getBytes("item");
                        String summary;
                        try{
                            org.bukkit.inventory.ItemStack stack=org.bukkit.inventory.ItemStack.deserializeBytes(bytes);
                            String name=stack.hasItemMeta()&&stack.getItemMeta().hasDisplayName()?net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(stack.getItemMeta().displayName())+" ":"";
                            summary=name+stack.getType()+" x"+stack.getAmount()+" (bytes="+bytes.length+")";
                        }catch(Throwable error){
                            summary="UNDESERIALIZABLE ("+bytes.length+" bytes) -- "+error.getClass().getSimpleName()+": "+error.getMessage();
                        }
                        getLogger().info("  page="+page+" slot="+slot+" -> "+summary);
                    }
                }
                getLogger().info("[ecforensics:"+label+"] "+targetName+": "+count+" row(s) total.");
                CoreUtil.msg(sender,"[ecforensics:"+label+"] "+targetName+": "+count+" row(s) -- see console/log for full item detail.");
            }catch(Exception error){
                CoreUtil.error(sender,"[ecforensics] failed: "+error);
                getLogger().warning("[ecforensics] failed for "+dbPath+": "+error);
            }
            return true;
        }
        if(args[0].equalsIgnoreCase("nearbymobs")){
            if(args.length<2){CoreUtil.error(sender,"Usage: /admin nearbymobs <player> [radius] — lists nearby hostile mobs and whether each is tagged trial_spawner_mob. Diagnostic only.");return true;}
            Player target=getServer().getPlayerExact(args[1]);
            if(target==null){CoreUtil.error(sender,"Player not online: "+args[1]);return true;}
            double radius=args.length>2?Double.parseDouble(args[2]):40;
            org.bukkit.NamespacedKey trialKey=new org.bukkit.NamespacedKey(this,"trial_spawner_mob");
            int total=0,tagged=0;
            for(org.bukkit.entity.Entity e:target.getNearbyEntities(radius,radius,radius)){
                if(!(e instanceof org.bukkit.entity.Enemy living))continue;
                total++;
                boolean isTrial=e.getPersistentDataContainer().has(trialKey,org.bukkit.persistence.PersistentDataType.BYTE);
                if(isTrial)tagged++;
                CoreUtil.msg(sender,"  "+e.getType()+" at "+e.getLocation().getBlockX()+","+e.getLocation().getBlockY()+","+e.getLocation().getBlockZ()+" — trial_spawner_mob="+isTrial+" valid="+e.isValid());
            }
            CoreUtil.msg(sender,"[nearbymobs] "+target.getName()+" (hostile mobs "+(settings.naturalSpawns(target)?"ON":"OFF")+"): "+total+" hostile mob(s) within "+radius+" blocks, "+tagged+" tagged as trial-spawner-spawned.");
            return true;
        }
        if(args[0].equalsIgnoreCase("localdispatch")){
            if(args.length<2){CoreUtil.error(sender,"Usage: /admin localdispatch <raw command, e.g. \"worldedit:pos1 1,2,3\"> — dispatches via the exact same getServer().dispatchCommand(getConsoleSender(),...) path MonumentService's dispatch() uses internally. Diagnostic only.");return true;}
            String raw=String.join(" ",Arrays.copyOfRange(args,1,args.length));
            boolean result=getServer().dispatchCommand(getServer().getConsoleSender(),raw);
            CoreUtil.msg(sender,"[localdispatch] \""+raw+"\" -> dispatchCommand returned "+result+" (check console output above/around this line for the command's own response)");
            return true;
        }
        if(args[0].equalsIgnoreCase("tabtest")){
            if(args.length<2){CoreUtil.error(sender,"Usage: /admin tabtest <comma-separated: cmd,arg1,arg2,...> — a trailing comma means \"cursor on a new empty argument\", e.g. \"ashfall,monument,\" simulates \"/ashfall monument <TAB>\". Diagnostic only, Bukkit's own space tokenizer eats real trailing spaces before this code ever sees them.");return true;}
            String joined=String.join(" ",Arrays.copyOfRange(args,1,args.length));
            String[] parts=joined.split(",",-1);
            String cmdName=parts[0];
            String[] tabArgs=Arrays.copyOfRange(parts,1,parts.length);
            Command cmd=getCommand(cmdName);
            if(cmd==null){CoreUtil.error(sender,"[tabtest] unknown command: "+cmdName);return true;}
            List<String> result=onTabComplete(sender,cmd,cmdName,tabArgs);
            CoreUtil.msg(sender,"[tabtest] \""+joined+"\" -> "+(result==null?"null":result));
            return true;
        }
        if(args[0].equalsIgnoreCase("simlogin")){
            if(args.length<2){CoreUtil.error(sender,"Usage: /admin simlogin <name> — fires a fake AsyncPlayerPreLoginEvent to test ban-enforcement listeners without a real client connection. Diagnostic only.");return true;}
            String simName=args[1];
            if(simName.length()>16){CoreUtil.error(sender,"Name cannot be longer than 16 characters.");return true;}
            UUID simUuid=UUID.nameUUIDFromBytes(("OfflinePlayer:"+simName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            getServer().getScheduler().runTaskAsynchronously(this,()->{
                try{
                    java.net.InetAddress addr=java.net.InetAddress.getByName("127.0.0.1");
                    org.bukkit.event.player.AsyncPlayerPreLoginEvent event=new org.bukkit.event.player.AsyncPlayerPreLoginEvent(simName,addr,simUuid);
                    getServer().getPluginManager().callEvent(event);
                    org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result result=event.getLoginResult();
                    getLogger().info("[simlogin] "+simName+" ("+simUuid+") -> result="+result);
                    if(result!=org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED){
                        String plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.kickMessage());
                        getLogger().info("[simlogin] kick message (plain text): "+plain.replace("\n"," | "));
                    }
                }catch(Exception e){getLogger().warning("[simlogin] exception: "+e);}
            });
            return true;
        }
        if(args[0].equalsIgnoreCase("relictest")){
            if(args.length<3){CoreUtil.error(sender,"Usage: /admin relictest <player> <relicKey> — reproduces the auction-escrow duplication bug end to end: creates a real relic item, marks its lifecycle row LOST (simulating the bug), places it in a genuine ACTIVE auction listing for <player>, forces an immediate lifecycle pass, then reports whether it self-healed back to ACTIVE instead of staying LOST. Diagnostic only.");return true;}
            String targetName=args[1],relicKey=args[2];
            if(!relics.keys().contains(relicKey)){CoreUtil.error(sender,"Unknown relic key. Use /relics for the list.");return true;}
            String targetId=UUID.nameUUIDFromBytes(("OfflinePlayer:"+targetName).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            Player online=getServer().getPlayerExact(targetName);if(online!=null)targetId=CoreUtil.id(online);
            org.bukkit.inventory.ItemStack item=relics.create(relicKey);
            db.registerRelic(relicKey,targetId,targetName);
            long farFuture=System.currentTimeMillis()+999_999_999L;
            db.markRelicLost(relicKey,farFuture);
            long auctionId=db.createAuction(targetId,targetName,item,1000,System.currentTimeMillis()+3600000L,0);
            Database.RelicLifecycleRow before=db.relicLifecycle(relicKey);
            getLogger().info("[relictest] set up: "+relicKey+" owner="+targetName+" status="+before.status()+" eligible_at="+before.eligibleAt()+" ; created ACTIVE auction #"+auctionId+" containing the exact same item.");
            relics.debugForceLifecycleTick();
            Database.RelicLifecycleRow after=db.relicLifecycle(relicKey);
            CoreUtil.msg(sender,"[relictest] before: status="+before.status()+" | after one forced lifecycle tick: status="+after.status()+" owner="+after.ownerName()+(after.status().equals("ACTIVE")?" — SELF-HEALED correctly, did not resurface a duplicate." :" — did NOT self-heal, check logs."));
            return true;
        }
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

    void giveGuide(Player p){guides.giveBoth(p);}
    private void playerHelp(Player p){p.sendMessage("§6§lASHFALL");p.sendMessage("§eFactions: §f/f create, /f claim, /f relations, /f expand, /f <player>");p.sendMessage("§eEconomy: §f/balance, /pay, /bounty, /bounties");p.sendMessage("§eMarketplace: §f/shop, /ah, /luxuryshop, /shardshop, /orders, /myorders");p.sendMessage("§eTravel: §f/home, /tpa, /spawn, /rtp, /rtp queue");p.sendMessage("§eSocial: §f/msg, /r, /trade, /feedback");p.sendMessage("§eMore: §f/settings, /events, /progress, /stats, /graves, /enderchest, /guide, /afk");}

    private boolean admin(CommandSender sender,String[] args){
        if(sender instanceof Player p&&!isAdmin(p)){CoreUtil.error(sender,"Only the configured ADMIN account can use SMPCore administration.");return true;}
        if(args.length==0){adminHelp(sender);return true;}
        if(args[0].equalsIgnoreCase("help")){if(args.length==1)adminHelp(sender);else adminSectionHelp(sender,args[1]);return true;}
        try{
            switch(args[0].toLowerCase(Locale.ROOT)){
                case"hopper"->{if(args.length>=2&&args[1].equalsIgnoreCase("verify")){CoreUtil.msg(sender,"Verifying Industrial Hopper parity against a live rig:");for(String line:new IndustrialHopperVerify(this,industrialHoppers).run())CoreUtil.msg(sender,"  "+line);return true;}if(args.length>=5&&args[1].equalsIgnoreCase("rig")){org.bukkit.World rw=sender instanceof Player rp?rp.getWorld():getServer().getWorlds().get(0);CoreUtil.msg(sender,industrialHoppers.rig(rw,Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4])));return true;}if(args.length>=5&&args[1].equalsIgnoreCase("count")){org.bukkit.World cw=sender instanceof Player cp?cp.getWorld():getServer().getWorlds().get(0);CoreUtil.msg(sender,industrialHoppers.count(cw,Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4])));return true;}if(args.length>=5&&args[1].equalsIgnoreCase("create")){org.bukkit.World w=sender instanceof Player hp?hp.getWorld():getServer().getWorlds().get(0);boolean made=industrialHoppers.install(w.getBlockAt(Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4])));CoreUtil.msg(sender,made?"Industrial Hopper installed.":"That block is not a hopper.");return true;}if(args.length>=4){org.bukkit.World world=args.length>4?getServer().getWorld(args[4]):(sender instanceof Player hp?hp.getWorld():getServer().getWorlds().get(0));if(world==null){CoreUtil.error(sender,"Unknown world.");return true;}try{CoreUtil.msg(sender,industrialHoppers.describe(world,Integer.parseInt(args[1]),Integer.parseInt(args[2]),Integer.parseInt(args[3])));}catch(NumberFormatException e){CoreUtil.error(sender,"Usage: /ashfall hopper <x> <y> <z> [world]");}}else{java.util.List<String> all=industrialHoppers.describeAll();CoreUtil.msg(sender,"Industrial hoppers in memory: "+all.size());for(String line:all)CoreUtil.msg(sender,"  "+line);}}
                case"vault"->{int page=1;if(args.length>1)try{page=Math.max(1,Integer.parseInt(args[1]));}catch(NumberFormatException ignored){}CoreUtil.msg(sender,vault.summaryLine());vault.show(sender,page);}
                case"balance"->adminBalance(sender,args);
                case"boss"->adminBoss(sender,args);
                case"duelmap"->adminDuelMap(sender,args);case"spawnershop"->CoreUtil.msg(sender,spawnerShop.describeStock());
                case"elite"->adminElite(sender,args);
                case"event"->adminEvent(sender,args);
                case"merchant"->{if(!(sender instanceof Player p)){CoreUtil.error(sender,"Run merchant commands in game.");return true;}merchants.command(p,args);}
                case"bulletin"->{if(!(sender instanceof Player p)){CoreUtil.error(sender,"Run bulletin commands in game.");return true;}bulletin.command(p,args);}
                case"feedback"->adminFeedback(sender,args);
                case"economy"->economyReport(sender,args);
                case"bank"->adminBank(sender,args);
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
                case"ipban"->ipBans.command(sender,args);
                case"monument"->monuments.command(sender,args);
                case"moderation"->moderation.command(sender,args);
                case"enderchest"->{
                    if(args.length<2||!args[1].equalsIgnoreCase("migratetiers")){CoreUtil.error(sender,"Usage: /ashfall enderchest migratetiers confirm — one-time, idempotent migration to the 27/45/72/90 tier system.");break;}
                    if(args.length<3||!args[2].equalsIgnoreCase("confirm")){CoreUtil.error(sender,"This changes ender_tier for every known player. Run '/ashfall enderchest migratetiers confirm' to proceed. Safe to run more than once.");break;}
                    enderChests.migrateTiers(sender);
                }
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
        adminCategory(s,"Economy","/ashfall balance, /ashfall bank, /ashfall economy report, /ashfall shard");
        adminCategory(s,"Events & Bosses","/ashfall event, /ashfall boss, /ashfall elite");
        adminCategory(s,"Factions & Spawn","/ashfall faction, /ashfall spawnclaim");
        adminCategory(s,"Merchants","/ashfall merchant");
        adminCategory(s,"Bulletin","/ashfall bulletin");
        adminCategory(s,"Feedback","/ashfall feedback");
        adminCategory(s,"Relics","/ashfall relic");
        adminCategory(s,"Bounties","/ashfall bounty");
        adminCategory(s,"Moderation","/ashfall vanish, /ashfall spectate, /ashfall unspectate, /ashfall audit, /ashfall ipban, /ashfall monument, /warn /ban /mute /kick /history /check (PunisherX), /openinv, /openender (OpenInv)");
        adminCategory(s,"Replays","/ashfall replay <killer|victim> <player> [count]");
        adminCategory(s,"Player Repair","/ashfall progressrepair, /ashfall cooldowns");
        adminCategory(s,"Debug / Maintenance","/ashfall help maintenance");
        s.sendMessage(ChatColor.DARK_GRAY+"Enter a parent command to see its options.");
    }
    private void adminCategory(CommandSender s,String category,String commands){s.sendMessage(ChatColor.YELLOW+category+ChatColor.DARK_GRAY+" — "+ChatColor.WHITE+commands);}
    private void adminCommands(CommandSender s,String title,String... commands){s.sendMessage(ChatColor.GOLD+""+ChatColor.BOLD+title+":");for(String command:commands)s.sendMessage(ChatColor.GRAY+"  "+ChatColor.WHITE+command);}
    private void adminSectionHelp(CommandSender s,String section){
        switch(section.toLowerCase(Locale.ROOT)){
            case"economy","balance","bank"->adminCommands(s,"Economy","/ashfall economy report","/ashfall balance set <player> <amount>","/ashfall balance add <player> <amount>","/ashfall balance take <player> <amount>","/ashfall bank <add|remove|set> <amount>","/ashfall shard <give|remove|set> <player> <amount>");
            case"moderation"->adminCommands(s,"Moderation","/ashfall vanish","/ashfall spectate <player>","/ashfall unspectate","/ashfall audit [count]","/ashfall ipban ban|unban|duration|list","/ashfall monument ...","/warn <player> (time) <reason> (PunisherX)","/ban, /mute, /kick, /history, /check (PunisherX)","/openinv <player> (OpenInv, live)","/openender <player> (OpenInv, live)");
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
    private void eventHelp(CommandSender s){adminCommands(s,"Events","/ashfall event resource","/ashfall event elitehunt","/ashfall event taskmaster","/ashfall event worldboss","/ashfall event stop");}
    private void bossHelp(CommandSender s){adminCommands(s,"World Boss","/ashfall boss spawn [ashen|iron|piglin]","/ashfall boss here [ashen|iron|piglin]","/ashfall boss despawn");}
    private void eliteHelp(CommandSender s){adminCommands(s,"Powered Mobs","/ashfall elite <uncommon|rare|epic|legendary|miniboss> [mob] [here | <x> <y> <z>]","  mob optional (e.g. wither_skeleton, blaze); location optional -> random if omitted","/ashfall elite legendary blaze here","/ashfall elite epic 100 64 -200","/ashfall elite stats");}
    private void feedbackHelp(CommandSender s){adminCommands(s,"Feedback","/ashfall feedback notify <on|off>","/ashfall feedback list [open|done|all]","/ashfall feedback view <id>","/ashfall feedback done <id>","/ashfall feedback reopen <id>","/ashfall feedback delete <id>");}
    private void factionHelp(CommandSender s){adminCommands(s,"Factions","/ashfall faction inspect <name>","/ashfall faction resize <name> <tier>","/ashfall faction resetclaim <name>","/ashfall faction recalc");}
    private void merchantHelp(CommandSender s){adminCommands(s,"Spawn Merchants","/ashfall merchant spawn <boss|event|shop|auction|banker>","/ashfall merchant remove <nearest|id>");}
    private void spawnClaimHelp(CommandSender s){adminCommands(s,"Spawn Protection","/ashfall spawnclaim select","/ashfall spawnclaim info","/ashfall spawnclaim clear");}
    private void relicHelp(CommandSender s){adminCommands(s,"Relics","/ashfall relic give <player> <key>","/ashfall relic remove <key>","/ashfall relic forcerespawn <key>","/ashfall relic reconcile <key> — audited check for duplicate physical copies among online players");}
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
    private void adminBank(CommandSender s,String[] args){
        double balance=bank.treasury().balance();
        if(args.length<2){CoreUtil.msg(s,"Central Bank balance: "+CoreUtil.money(balance));CoreUtil.msg(s,ChatColor.GRAY+"Manage it with /ashfall bank <add|remove|set> <amount>.");return;}
        String action=args[1].toLowerCase(Locale.ROOT);
        if(!Set.of("add","remove","take","set").contains(action)){CoreUtil.error(s,"Unknown bank action. Use add, remove, or set.");return;}
        if(args.length<3){CoreUtil.error(s,"Usage: /ashfall bank "+action+" <amount>");return;}
        double amount=CoreUtil.parseMoney(args[2]);
        if(amount<=0){CoreUtil.error(s,"Amount must be a positive number (e.g. 500000 or 1m).");return;}
        switch(action){
            case"add"->bank.adminAdjust(amount);
            case"remove","take"->bank.adminAdjust(-amount);
            default->bank.adminAdjust(amount-balance);
        }
        double now=bank.treasury().balance();
        db.logAudit(adminName(s),"BANK_"+action.toUpperCase(Locale.ROOT),"amount="+amount+" balance="+now);
        CoreUtil.msg(s,"Central Bank "+(action.equals("set")?"set to "+CoreUtil.money(now):(action.equals("add")?"increased by "+CoreUtil.money(amount):"decreased by "+CoreUtil.money(amount)))+". New balance: "+CoreUtil.money(now)+".");
    }
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
    private static final List<String> ELITE_MOBS=List.of("here","zombie","husk","skeleton","stray","bogged","wither_skeleton","spider","cave_spider","creeper","enderman","witch","piglin","piglin_brute","zombified_piglin","blaze","drowned","vindicator","pillager","evoker","ravager","hoglin","zoglin","slime","phantom","vex","breeze","warden");
    private static org.bukkit.entity.EntityType parseMobType(String value){try{org.bukkit.entity.EntityType type=org.bukkit.entity.EntityType.valueOf(value.toUpperCase(Locale.ROOT));Class<?> cls=type.getEntityClass();if(cls!=null&&org.bukkit.entity.Mob.class.isAssignableFrom(cls)&&type.isSpawnable())return type;}catch(IllegalArgumentException ignored){}return null;}
    private static Double parseCoord(String value){try{return Double.parseDouble(value);}catch(NumberFormatException e){return null;}}

    // ------------------------------------------------------------------ duel map templates
    private final Map<String,org.bukkit.Location> duelMapReturn=new java.util.concurrent.ConcurrentHashMap<>();
    /** Every /ashfall duelmap subcommand, in the order the help prints them. Single source of truth for the
     *  help text, the tab completion and the "unknown subcommand" reply, so they cannot drift apart. */
    private static final List<String> DUELMAP_SUBS=List.of("list","snapshots","create","enter","exit","build","import",
        "save","setspawn","test","dryrun","drop","orphans","reload","loot","canary","verify");

    /** Registered map ids, straight from the live registry. */
    private List<String> duelMapKeys(){return duelMaps==null?List.of():duelMaps.maps().stream().map(DuelMapService.DuelMap::key).toList();}

    /** Concise usage plus the values that would actually have worked -- an admin should never have to guess
     *  a map id or go and read the source to find out what a subcommand wanted. */
    private void duelMapUsage(CommandSender sender,String sub,String what){
        CoreUtil.error(sender,"Usage: /ashfall duelmap "+sub+" <"+what+">");
        if(what.equals("map"))CoreUtil.msg(sender,"Maps: "+String.join(", ",duelMapKeys()));
        else if(what.equals("instance-world")){
            List<String> live=duelMaps==null?List.of():duelMaps.instanceNames();
            CoreUtil.msg(sender,"Live instances: "+(live.isEmpty()?"none":String.join(", ",live)));
        }
    }

    private static double round2(double v){return Math.round(v*100)/100.0;}
    private static String fmt(org.bukkit.Location at){return round2(at.getX())+", "+round2(at.getY())+", "+round2(at.getZ());}
    /** Which duel maps a match could actually be sent to right now. A map with no committed snapshot is not
     *  a failure -- it just has not been built yet -- but it is the single most useful thing to see in a
     *  selftest, because it is exactly what stops the duel flow reaching a player. */
    private String duelMapSnapshotStatus(){
        if(duelMaps==null)return "unavailable";
        List<String> missing=new ArrayList<>();
        int total=0;
        for(DuelMapService.DuelMap m:duelMaps.maps()){total++;if(!duelMaps.hasSnapshot(m))missing.add(m.key());}
        return (total-missing.size())+"/"+total+(missing.isEmpty()?" (all playable)":" - not yet built: "+String.join(", ",missing));
    }
    /** /ashfall duelmap ... -- template maintenance without touching the filesystem. Templates are private
     *  build worlds; instances are the disposable clones matches actually run in. */
    private void adminDuelMap(CommandSender sender,String[] args){
        DuelMapService svc=duelMaps;
        if(svc==null){CoreUtil.error(sender,"Duel maps are unavailable.");return;}
        String sub=args.length>1?args[1].toLowerCase(Locale.ROOT):"list";
        switch(sub){
            case"list"->{
                CoreUtil.msg(sender,"Duel templates:");
                for(DuelMapService.DuelMap m:svc.maps())
                    CoreUtil.msg(sender,"  "+m.key()+" - "+m.name()+" ["+m.rule()+"] world="+m.templateWorld()
                        +(getServer().getWorld(m.templateWorld())!=null?" (loaded)":" (not loaded)")
                        +(svc.hasSnapshot(m)?" [committed]":" [NOT COMMITTED]"));
                List<String> live=svc.instanceNames();
                CoreUtil.msg(sender,"Live instances: "+(live.isEmpty()?"none":String.join(", ",live)));
            }
            case"enter"->{
                if(!(sender instanceof Player p)){CoreUtil.error(sender,"Players only.");return;}
                DuelMapService.DuelMap m=args.length>2?svc.map(args[2]):null;
                if(m==null){duelMapUsage(sender,"enter","map");return;}
                CoreUtil.msg(p,"Opening the "+m.name()+" workspace - this can take a few seconds on a big map...");
                org.bukkit.World w=svc.workspace(m,line->CoreUtil.msg(p,"  "+line));
                if(w==null){CoreUtil.error(sender,"Could not open that workspace. Check /ashfall duelmap snapshots.");return;}
                /** Never drop an admin into an empty void and call it a template: if the arena is not there
                 *  after the workspace has been opened (and, if needed, rebuilt from the snapshot), say so
                 *  instead of teleporting them somewhere that looks broken. */
                if(!svc.holdsArena(w,m)){
                    CoreUtil.error(sender,m.name()+" has no arena in its workspace and no committed snapshot to rebuild it from.");
                    CoreUtil.msg(sender,"Build it (/ashfall duelmap build "+m.key()+") or import it, then /ashfall duelmap save "+m.key()+".");
                    return;
                }
                duelMapReturn.put(p.getUniqueId().toString(),p.getLocation());
                p.teleport(svc.workspaceSpawn(w,m));
                p.setGameMode(org.bukkit.GameMode.CREATIVE);
                CoreUtil.msg(p,"Editing "+m.name()+" ("+svc.arenaRegionName(m)+" present). /ashfall duelmap save "+m.key()+" commits it; /ashfall duelmap exit returns you.");
            }
            case"exit"->{
                if(!(sender instanceof Player p)){CoreUtil.error(sender,"Players only.");return;}
                org.bukkit.Location back=duelMapReturn.remove(p.getUniqueId().toString());
                p.teleport(back!=null?back:getServer().getWorlds().get(0).getSpawnLocation());
                CoreUtil.msg(p,"Left the template.");
            }
            case"save"->{
                DuelMapService.DuelMap m=args.length>2?svc.map(args[2]):null;
                if(m==null){duelMapUsage(sender,"save","map");return;}
                CoreUtil.msg(sender,svc.commitTemplate(m));
            }
            case"import"->{
                DuelMapService.DuelMap m=args.length>2?svc.map(args[2]):null;
                if(m==null){duelMapUsage(sender,"import","map");return;}
                if(!DuelMapImporter.available()){CoreUtil.error(sender,"WorldEdit is not installed on this server, so an import cannot run here.");return;}
                new DuelMapImporter(this,svc).run(sender,m);
            }
            case"build"->{
                DuelMapService.DuelMap m=args.length>2?svc.map(args[2]):null;
                if(m==null){duelMapUsage(sender,"build","arena50|arena100");return;}
                int size=m.key().equals("arena100")?100:m.key().equals("arena50")?50:0;
                if(size==0){CoreUtil.error(sender,"Only the two flat arenas are built from code; the rest are imported.");return;}
                CoreUtil.msg(sender,"Building "+m.name()+" ("+size+"x"+size+"). This takes a few seconds.");
                svc.buildFlatArena(m,size,()->CoreUtil.msg(sender,svc.commitTemplate(m)));
            }
            case"verify"->{
                CoreUtil.msg(sender,"Verifying the duel arena pipeline end to end:");
                for(String line:new DuelMapVerify(this,svc).run())CoreUtil.msg(sender,"  "+line);
            }
            case"dryrun"->{
                DuelMapService.DuelMap m=args.length>2?svc.map(args[2]):null;
                if(m==null){duelMapUsage(sender,"dryrun","map");return;}
                long began=System.currentTimeMillis();
                CoreUtil.msg(sender,"Preparing "+m.name()+" exactly as a match would (async clone + sliced chunk load)...");
                /** The result arrives a few ticks later, by which time an RCON caller has already been
                 *  disconnected -- so every line goes to the log as well as to whoever asked. */
                java.util.function.Consumer<String> say=line->{CoreUtil.msg(sender,line);getLogger().info("[duel-maps] dryrun "+m.key()+": "+line);};
                svc.prepareInstance(m,world->{
                    if(world==null){say.accept("Match preparation FAILED for "+m.key()+".");return;}
                    org.bukkit.Location p1=m.p1(world),p2=m.p2(world);
                    int[] census=svc.chestCensus(world);
                    say.accept("ready in "+(System.currentTimeMillis()-began)+" ms: "+world.getName());
                    say.accept("p1 "+fmt(p1)+" yaw "+Math.round(p1.getYaw())+" on "+m.footing(world,p1));
                    say.accept("p2 "+fmt(p2)+" yaw "+Math.round(p2.getYaw())+" on "+m.footing(world,p2));
                    say.accept("facing each other: "+DuelMapService.facesEachOther(m)
                        +" | chests filled: "+(census[0]+census[1])+" | living mobs: "+world.getLivingEntities().size()
                        +" | mob spawning: "+world.getGameRuleValue(org.bukkit.GameRule.DO_MOB_SPAWNING));
                    svc.destroyInstance(world,null);
                    say.accept("instance dropped; world unloaded: "+(getServer().getWorld(world.getName())==null));
                });
            }
            case"canary"->{
                CoreUtil.msg(sender,"Duel template persistence canary - building, saving, unloading, reloading and cloning:");
                for(String line:new DuelMapCanary(this,svc).run())CoreUtil.msg(sender,"  "+line);
            }
            case"loot"->{
                DuelMapService.DuelMap m=args.length>2?svc.map(args[2]):null;
                if(m==null){duelMapUsage(sender,"loot","map");return;}
                org.bukkit.World probe=svc.createInstance(m);
                if(probe==null){CoreUtil.error(sender,"Could not clone that map to count its chests.");return;}
                CoreUtil.msg(sender,svc.lootReport(m,probe));
                svc.destroyInstance(probe,null);
            }
            case"snapshots"->{
                CoreUtil.msg(sender,"Committed snapshots in "+svc.snapshotRoot().getAbsolutePath()+":");
                for(DuelMapService.DuelMap m:svc.maps())
                    CoreUtil.msg(sender,"  "+m.key()+": "+(svc.hasSnapshot(m)?"committed":"NOT COMMITTED - matches cannot use this map"));
                CoreUtil.msg(sender,"Custom worlds live in "+svc.customWorldDir().getAbsolutePath());
            }
            case"create"->{
                DuelMapService.DuelMap m=args.length>2?svc.map(args[2]):null;
                if(m==null){duelMapUsage(sender,"create","map");return;}
                org.bukkit.World w=svc.template(m);
                CoreUtil.msg(sender,w==null?"Could not create that template world.":"Template world "+w.getName()+" is ready.");
            }
            case"test"->{
                DuelMapService.DuelMap m=args.length>2?svc.map(args[2]):null;
                if(m==null){duelMapUsage(sender,"test","map");return;}
                org.bukkit.World inst=svc.createInstance(m);
                if(inst==null){CoreUtil.error(sender,"Could not create an instance.");return;}
                if(sender instanceof Player p){
                    duelMapReturn.putIfAbsent(p.getUniqueId().toString(),p.getLocation());
                    p.teleport(new org.bukkit.Location(inst,m.p1x(),m.p1y(),m.p1z(),m.yawP1(),0));
                }
                CoreUtil.msg(sender,"Test instance "+inst.getName()+" created. /ashfall duelmap drop "+inst.getName()+" removes it.");
            }
            case"drop"->{
                if(args.length<3){duelMapUsage(sender,"drop","instance-world");return;}
                org.bukkit.World w=getServer().getWorld(args[2]);
                if(w==null||!w.getName().startsWith(DuelMapService.INSTANCE_PREFIX)){duelMapUsage(sender,"drop","instance-world");return;}
                svc.destroyInstance(w,sender instanceof Player p?duelMapReturn.remove(p.getUniqueId().toString()):null);
                CoreUtil.msg(sender,"Instance removed.");
            }
            case"orphans"->CoreUtil.msg(sender,"Removed "+svc.cleanupOrphans()+" orphaned instance world(s).");
            case"setspawn"->{
                if(!(sender instanceof Player p)){CoreUtil.error(sender,"Players only.");return;}
                if(args.length<4){duelMapUsage(sender,"setspawn","map> <p1|p2|spectator");return;}
                DuelMapService.DuelMap m=svc.map(args[2]);
                if(m==null){duelMapUsage(sender,"setspawn","map");return;}
                String which=args[3].toLowerCase(Locale.ROOT);
                if(!which.equals("p1")&&!which.equals("p2")&&!which.equals("spectator")){duelMapUsage(sender,"setspawn","map> <p1|p2|spectator");return;}
                org.bukkit.Location at=p.getLocation();
                getConfig().set("duel-maps."+m.key()+"."+which,List.of(round2(at.getX()),round2(at.getY()),round2(at.getZ())));
                saveConfig(); svc.reload();
                CoreUtil.msg(p,"Set "+which+" for "+m.name()+" to "+round2(at.getX())+", "+round2(at.getY())+", "+round2(at.getZ())+".");
            }
            case"reload"->{svc.reload();CoreUtil.msg(sender,"Duel map registry reloaded.");}
            default->{
                if(args.length>1)CoreUtil.error(sender,"Unknown subcommand '"+args[1]+"'.");
                CoreUtil.msg(sender,"DUEL MAPS - /ashfall duelmap <"+String.join("|",DUELMAP_SUBS)+">");
                CoreUtil.msg(sender,"  build/import/save commit a template; test/dryrun/loot clone one; drop removes an instance.");
                CoreUtil.msg(sender,"  canary = template persistence proof, verify = full pipeline. Maps: "+String.join(", ",duelMapKeys()));
            }
        }
    }

    private void adminElite(CommandSender s,String[] args){
        if(args.length<2){eliteHelp(s);return;}
        String tier=args[1].toLowerCase(Locale.ROOT);
        if(tier.equals("stats")||tier.equals("report")){bosses.rarityReport(s);return;}
        if(!List.of("uncommon","rare","epic","legendary","miniboss").contains(tier)){CoreUtil.error(s,"Unknown rarity. Use uncommon, rare, epic, legendary, or miniboss.");return;}
        int idx=2;org.bukkit.entity.EntityType mobType=null;
        if(args.length>idx){org.bukkit.entity.EntityType parsed=parseMobType(args[idx]);if(parsed!=null){mobType=parsed;idx++;}}
        org.bukkit.Location loc=null;
        if(args.length>idx){
            if(args[idx].equalsIgnoreCase("here")){if(!(s instanceof Player p)){CoreUtil.error(s,"The 'here' option must be used in game.");return;}loc=p.getLocation();}
            else if(args.length>=idx+3){Double x=parseCoord(args[idx]),y=parseCoord(args[idx+1]),z=parseCoord(args[idx+2]);if(x==null||y==null||z==null){CoreUtil.error(s,"Coordinates must be numbers. Usage: /ashfall elite <rarity> [mob] [here | <x> <y> <z>]");return;}org.bukkit.World w=s instanceof Player p?p.getWorld():getServer().getWorlds().get(0);loc=new org.bukkit.Location(w,x,y,z);}
            else{CoreUtil.error(s,"Unrecognized '"+args[idx]+"'. Usage: /ashfall elite <rarity> [mob] [here | <x> <y> <z>]");return;}
        }
        LivingEntity elite=bosses.spawnElite(tier,loc,mobType);
        if(elite==null){CoreUtil.error(s,"Could not spawn -- invalid mob for this world, or no safe location found.");return;}
        db.logAudit(adminName(s),"ELITE_SPAWN",tier+" "+elite.getType().name()+(loc!=null?" @"+loc.getBlockX()+","+loc.getBlockY()+","+loc.getBlockZ():" random"));
        CoreUtil.msg(s,CoreUtil.pretty(tier)+" "+CoreUtil.pretty(elite.getType().name())+" spawned"+(loc!=null?" at "+loc.getBlockX()+", "+loc.getBlockY()+", "+loc.getBlockZ():" at a random location")+".");
    }
    private void adminEvent(CommandSender s,String[] args){if(args.length<2){eventHelp(s);return;}if(args[1].equalsIgnoreCase("stop")){boolean stopped=bosses.forceStopEvent();db.logAudit(adminName(s),"EVENT_STOP",stopped?"stopped active event":"nothing was active");CoreUtil.msg(s,stopped?"Active event stopped and cleaned up.":"No event was active.");return;}BossEventService.EventType type=switch(args[1].toLowerCase(Locale.ROOT)){case"resource"->BossEventService.EventType.RESOURCE_RUSH;case"elitehunt"->BossEventService.EventType.ELITE_HUNT;case"taskmaster","task"->BossEventService.EventType.TASK_MASTER;case"worldboss","hunt"->BossEventService.EventType.WORLD_BOSS;default->{CoreUtil.error(s,"Unknown event. Use /smp event for options.");yield null;}};if(type==null)return;if(bosses.startEvent(type,null,BossEventService.Origin.ADMIN_SUMMONED))CoreUtil.msg(s,"Event started.");else CoreUtil.error(s,"A conflicting event is already active.");}
    private void adminFeedback(CommandSender s,String[] args){if(args.length<2){feedbackHelp(s);return;}String action=args[1].toLowerCase(Locale.ROOT);if(action.equals("notify")){if(!(s instanceof Player p)||args.length<3||!List.of("on","off").contains(args[2].toLowerCase(Locale.ROOT))){CoreUtil.error(s,"Usage: /smp feedback notify <on|off>");return;}boolean enabled=args[2].equalsIgnoreCase("on");db.preference(CoreUtil.id(p),"feedback_notify",Boolean.toString(enabled));CoreUtil.msg(s,"Feedback notifications "+(enabled?"enabled":"disabled")+".");return;}if(action.equals("list")){String status=args.length>2&&!args[2].equalsIgnoreCase("all")?args[2].toUpperCase(Locale.ROOT):"";for(Database.FeedbackRow row:db.feedback(status,20))CoreUtil.msg(s,"#"+row.id()+" ["+row.status()+"] "+row.playerName()+" • "+trim(row.message(),70));return;}if(!List.of("view","done","reopen","delete").contains(action)){CoreUtil.error(s,"Unknown feedback action. Use /smp feedback for options.");return;}if(args.length<3){CoreUtil.error(s,"Feedback ID required for '"+action+"'.");return;}long id=Long.parseLong(args[2]);Database.FeedbackRow row=db.feedback(id);if(row==null){CoreUtil.error(s,"Feedback not found.");return;}switch(action){case"view"->{CoreUtil.msg(s,"#"+row.id()+" ["+row.status()+"] "+row.playerName()+" • "+Instant.ofEpochMilli(row.createdAt()));s.sendMessage(row.message());}case"done"->{db.feedbackStatus(id,"DONE");CoreUtil.msg(s,"Feedback #"+id+" marked DONE.");}case"reopen"->{db.feedbackStatus(id,"OPEN");CoreUtil.msg(s,"Feedback #"+id+" reopened.");}case"delete"->{db.deleteFeedback(id);CoreUtil.msg(s,"Feedback #"+id+" permanently deleted.");}}}

    private void economyReport(CommandSender s,String[] args){if(args.length<2||!args[1].equalsIgnoreCase("report")){adminCommands(s,"Economy","/smp economy report");return;}long now=System.currentTimeMillis();economyWindow(s,"24 hours",db.economyTotals(now-86400000L));economyWindow(s,"7 days",db.economyTotals(now-604800000L));Database.BankRow treasury=bank.treasury();CoreUtil.msg(s,"Central Bank: "+CoreUtil.money(treasury.balance())+" | fees "+CoreUtil.money(treasury.feeRevenue())+" | server payments "+CoreUtil.money(treasury.sinkRevenue())+" | shop payouts "+CoreUtil.money(treasury.shopPayouts())+" | interest "+CoreUtil.money(treasury.interestRevenue()));List<Database.StatsRow> rich=db.topStats("balance");if(!rich.isEmpty())CoreUtil.msg(s,"Richest player: "+rich.getFirst().name()+" "+CoreUtil.money(rich.getFirst().balance()));List<NetWorthService.Row> factions=netWorth.rankings();if(!factions.isEmpty())CoreUtil.msg(s,"Top faction net worth: "+factions.getFirst().name()+" "+CoreUtil.money(factions.getFirst().value()));db.pruneEconomy(now-7776000000L);}
    private void economyWindow(CommandSender s,String label,List<Database.EconomyTotal> totals){Map<String,Double> m=new HashMap<>();for(Database.EconomyTotal total:totals)m.put(total.category(),total.amount());double normal=m.getOrDefault("MOB_NORMAL",0.0),elite=m.getOrDefault("ELITE",0.0)+m.getOrDefault("BOSS",0.0),milestones=m.getOrDefault("MILESTONE",0.0)+m.getOrDefault("EVENT",0.0),selling=m.getOrDefault("SHOP_SELL",0.0),removed=Math.max(0,-m.entrySet().stream().filter(e->e.getValue()<0).mapToDouble(Map.Entry::getValue).sum()),net=m.values().stream().mapToDouble(Double::doubleValue).sum();if(Math.abs(net)<.0001)net=0;s.sendMessage("§6§lECONOMY • "+label);s.sendMessage("§7Normal mobs: §a"+CoreUtil.money(normal)+"  §7Elites/bosses: §a"+CoreUtil.money(elite));s.sendMessage("§7Milestones/events: §a"+CoreUtil.money(milestones)+"  §7Shop selling: §a"+CoreUtil.money(selling));s.sendMessage("§7Removed: §c"+CoreUtil.money(removed)+"  §7Net creation: "+(net>=0?"§a":"§c")+CoreUtil.money(net));}
    private void adminRelic(CommandSender s,String[] args){
        if(args.length<2){relicHelp(s);return;}
        if(args[1].equalsIgnoreCase("remove")){if(args.length<3){CoreUtil.error(s,"Usage: /smp relic remove <key>");return;}if(relics.remove(args[2]))CoreUtil.msg(s,"Relic removed from circulation — it will resurface naturally in "+relics.lostReentryDays()+" days, same as any other lost relic.");else CoreUtil.error(s,"Relic not found.");return;}
        if(args[1].equalsIgnoreCase("forcerespawn")){if(args.length<3){CoreUtil.error(s,"Usage: /smp relic forcerespawn <key>");return;}if(relics.forceEligible(args[2]))CoreUtil.msg(s,"Forced "+args[2]+" to become eligible to resurface immediately.");else CoreUtil.error(s,"That relic isn't currently LOST/recycling.");return;}
        if(args[1].equalsIgnoreCase("reconcile")){if(args.length<3){CoreUtil.error(s,"Usage: /ashfall relic reconcile <key>");return;}relics.reconcile(s,args[2]);return;}
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
    private void selfTest(CommandSender s){CoreUtil.msg(s,"Running non-destructive migration and persistence tests...");for(String result:db.selfTest())CoreUtil.msg(s,result);List<Integer> sizes=getConfig().getIntegerList("claims.sizes"),costs=getConfig().getIntegerList("claims.expansion-costs");boolean ok=sizes.size()==6&&costs.size()==5&&CoreUtil.compact(2590).length()<=5&&getConfig().getDouble("merchants.shop.buy-multiplier",1)<1&&getConfig().getDouble("merchants.shop.sell-multiplier",1)>1&&getConfig().getDouble("mob-money.minimum-multiplier",0)>.0&&getConfig().getDouble("spawner-breaking.money-reward",0)==25&&getConfig().getInt("spawner-breaking.exp-max",0)>=getConfig().getInt("spawner-breaking.exp-min",1)&&getConfig().getInt("auctions.max-active-per-player",0)==30&&getConfig().getDouble("bank.loans.daily-interest-percent",0)>0&&getConfig().getDouble("bank.loans.overdue-garnish-percent",0)>0&&getConfig().getDouble("bank.loans.maximum-limit",-1)==0&&getConfig().getInt("homes.personal.upgrades.10",0)==50000000&&getConfig().getLong("graves.lifetime-hours",0)==48&&getConfig().getDouble("performance.world-borders.sizes.overworld",0)==225000&&getConfig().getDouble("performance.world-borders.sizes.nether",0)==57000&&getConfig().getDouble("performance.world-borders.sizes.end",0)==175000&&getConfig().getDouble("progression.vanguard-economic-target",0)==250000&&getConfig().getDouble("pay.tax-percent",-1)>=0&&getConfig().getDouble("progression.rank-rewards.VANGUARD",0)==250000;for(int i=1;i<sizes.size();i++)ok&=sizes.get(i)>sizes.get(i-1);for(int i=1;i<costs.size();i++)ok&=costs.get(i)>costs.get(i-1);CoreUtil.msg(s,"Claim/economy/bank/auction/home/border configuration: "+(ok?"ok":"FAILED"));CoreUtil.msg(s,"Money parser, smart combat links and guide selection: "+(CoreUtil.moneyParserSelfTest()&&teleports.combatSelfTest()&&guides.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Chat combining-mark (zalgo) sanitization: "+(CoreUtil.combiningMarkSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Seven-rank requirement progression: "+(progress.rankSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Shop, Dragon Egg and Villager Capsule checks: "+(shop.selfTest()&&capsules.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Stacked/recovery spawner checks: "+(spawners.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Shared boss participant scaling/health-percent math: "+(bosses.scalingSelfTest()?"ok":"FAILED")); CoreUtil.msg(s,"Boss reward split (single participant takes the whole pool): "+(bosses.rewardSplitSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Boss/elite health-safety clamp: "+(bosses.bossHealthSafetySelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"World-boss rebalance/soft-enrage configuration: "+(bosses.worldBossRebalanceSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Epic/Legendary rarity, scaling and phase configuration: "+(bosses.eliteTierSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Active-play event tiers/protected buffer/effect sanitation: "+(bosses.eventTimingSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Marketplace, settings, shards and weekly Dragon: "+(marketplace.selfTest()&&settings.selfTest()&&shards.selfTest()&&weeklyDragon.selfTest()&&relics.upgradeSelfTest()&&taskMaster.selfTest()&&industrialHoppers.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Discarded-item vault eligibility guards: "+(vault.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Orders identity, catalogue and spawner typing: "+(ordersService.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Arena kit parity, three-stage setup and pari-mutuel arithmetic: "+(arena.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Duel map registry, break rules, spawn facing and trial-key restriction: "+(duelMaps.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Spawner Shop pricing order, rounding and deficit surcharge: "+(spawnerShop.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Duel template snapshots committed: "+duelMapSnapshotStatus());CoreUtil.msg(s,"Live bulletin configuration: "+(bulletin.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Punishment tier configuration: "+(punishments.selfTest()?"ok":"FAILED"));String old=db.state("selftest_1_7_0_restart");db.state("selftest_1_7_0_restart",Long.toString(System.currentTimeMillis()));CoreUtil.msg(s,"1.7.0 restart marker: "+(old==null?"created; run after restart":"read previous value successfully"));}

    /** /duel <player|accept|decline|kit|series|stake|confirm|bet|watch|status|cancel> */
    private boolean duel(Player p,String[] args){
        if(args.length==0){arena.openHub(p);return true;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        return switch(sub){
            case"accept"->arena.accept(p);
            case"decline"->arena.decline(p);
            case"confirm"->arena.confirm(p);
            case"back"->arena.back(p);
            case"map"->{if(args.length<2){CoreUtil.error(p,"Usage: /duel map <map>");yield true;}yield arena.setMap(p,args[1]);}
            case"forfeit","cancel"->arena.forfeit(p);
            case"status"->{CoreUtil.msg(p,arena.status(p));yield true;}
            case"kit"->{if(args.length<2){CoreUtil.error(p,"Kits: mace, sword, axe, spear.");yield true;}yield arena.setKit(p,args[1]);}
            case"series"->{if(args.length<2){CoreUtil.error(p,"Best of 1 or 3.");yield true;}try{yield arena.setSeries(p,Integer.parseInt(args[1]));}catch(NumberFormatException e){CoreUtil.error(p,"Best of 1 or 3.");yield true;}}
            case"stake"->{if(args.length<2){CoreUtil.error(p,"Usage: /duel stake <amount>");yield true;}yield arena.setStake(p,CoreUtil.parseMoney(args[1]));}
            case"watch"->{if(args.length<2){CoreUtil.error(p,"Usage: /duel watch <match id>");yield true;}try{yield arena.watch(p,Integer.parseInt(args[1]));}catch(NumberFormatException e){CoreUtil.error(p,"Match id must be a number.");yield true;}}
            case"bet"->{if(args.length<4){CoreUtil.error(p,"Usage: /duel bet <match id> <player> <amount>");yield true;}try{yield arena.bet(p,Integer.parseInt(args[1]),args[2],CoreUtil.parseMoney(args[3]));}catch(NumberFormatException e){CoreUtil.error(p,"Match id must be a number.");yield true;}}
            default->arena.challenge(p,args[0]);
        };
    }
    private String plugin_kitNotice(Player from){return from.getName()+" gave you the staff test kit.";}

    // ------------------------------------------------------------------ admin flight
    private static final String FLY_PERM="smpcore.fly";
    private boolean mayFly(CommandSender sender){return !(sender instanceof Player p)||p.hasPermission(FLY_PERM)||isAdmin(p);}
    /** Resolves the target: an explicit name, otherwise the sender when they are a player. Console MUST name
     *  somebody, since there is nobody to default to. */
    private Player flyTarget(CommandSender sender,String named){
        if(named!=null){Player found=getServer().getPlayerExact(named);if(found==null)CoreUtil.error(sender,"No player named "+named+" is online.");return found;}
        if(sender instanceof Player self)return self;
        CoreUtil.error(sender,"From console you must name a player.");return null;}

    /** /fly [player] [on|off] -- omitting on/off toggles. */
    private boolean flyCommand(CommandSender sender,String[] args){
        if(!mayFly(sender)){CoreUtil.error(sender,"You may not use /fly.");return true;}
        String named=null,state=null;
        for(String arg:args){
            String low=arg.toLowerCase(Locale.ROOT);
            if(low.equals("on")||low.equals("off")||low.equals("true")||low.equals("false")||low.equals("toggle"))state=low;else named=arg;}
        Player target=flyTarget(sender,named);
        if(target==null)return true;
        boolean on=state==null||state.equals("toggle")?!target.getAllowFlight():state.equals("on")||state.equals("true");
        target.setAllowFlight(on);
        if(!on)target.setFlying(false);
        CoreUtil.msg(sender,"Flight "+(on?"enabled":"disabled")+" for "+target.getName()+".");
        if(!target.equals(sender))CoreUtil.msg(target,"Flight "+(on?"enabled":"disabled")+" by staff.");
        return true;}

    /** /flyspeed [player] <1-10> -- 1 is vanilla flight speed, mapped onto Bukkit's 0-1 scale. */
    private boolean flySpeedCommand(CommandSender sender,String[] args){
        if(!mayFly(sender)){CoreUtil.error(sender,"You may not use /flyspeed.");return true;}
        if(args.length==0){CoreUtil.error(sender,"Usage: /flyspeed [player] <1-10>");return true;}
        String named=args.length>1?args[0]:null,raw=args[args.length-1];
        double scale;
        try{scale=Double.parseDouble(raw);}catch(NumberFormatException e){CoreUtil.error(sender,"'"+raw+"' is not a number. Usage: /flyspeed [player] <1-10>");return true;}
        if(scale<0.1||scale>10){CoreUtil.error(sender,"Speed must be between 0.1 and 10 (1 = normal).");return true;}
        Player target=flyTarget(sender,named);
        if(target==null)return true;
        float speed=(float)Math.max(0.01,Math.min(1.0,scale/10.0));
        target.setFlySpeed(speed);
        CoreUtil.msg(sender,"Fly speed for "+target.getName()+" set to "+scale+" ("+String.format(Locale.ROOT,"%.2f",speed)+").");
        if(!target.equals(sender))CoreUtil.msg(target,"Your fly speed was set to "+scale+" by staff.");
        return true;}

    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        String name=command.getName().toLowerCase(Locale.ROOT);
        if(name.equals("fly")||name.equals("flyspeed")){
            if(!mayFly(sender))return List.of();
            List<String> names=getServer().getOnlinePlayers().stream().map(Player::getName).collect(java.util.stream.Collectors.toList());
            if(args.length==1){List<String> out=new ArrayList<>(names);out.addAll(name.equals("fly")?List.of("on","off"):List.of("1","2","5","10"));
                return out.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).collect(java.util.stream.Collectors.toList());}
            if(args.length==2)return (name.equals("fly")?List.of("on","off"):List.of("1","2","5","10")).stream().filter(s->s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(java.util.stream.Collectors.toList());
            return List.of();}
        if(name.equals("ashfall")&&sender instanceof Player player&&!isAdmin(player))return List.of();
        if(name.equals("admin")&&!(sender instanceof ConsoleCommandSender)&&!(sender instanceof RemoteConsoleCommandSender))return List.of();
        /** /kit is staff-only, so it offers no suggestions at all to normal players -- the command's
         *  existence should not be advertised through tab completion. */
        if(name.equals("kit")){
            if(!(sender instanceof Player staff)||!isAdmin(staff))return List.of();
            if(args.length==1)return filter(args[0],List.of("test"));
            if(args.length==2&&args[0].equalsIgnoreCase("test"))return publicOnlineNames(sender,args[1]);
            return List.of();
        }
        if(args.length==1&&(name.equals("tpa")||name.equals("tpahere")||name.equals("pay")||name.equals("bounty")||name.equals("stats")||name.equals("admin")))return publicOnlineNames(sender,args[0]);
        if(args.length==1&&name.equals("msg")){List<String> options=new ArrayList<>(publicOnlineNames(sender,""));options.addAll(List.of("block","unblock"));return filter(args[0],options);}
        if(args.length==2&&name.equals("msg")&&(args[0].equalsIgnoreCase("block")||args[0].equalsIgnoreCase("unblock")))return publicOnlineNames(sender,args[1]);
        if(args.length==1&&name.equals("rtp"))return filter(args[0],List.of("queue"));
        if(name.equals("duel")&&sender instanceof Player duelPlayer){
            List<String> subs=List.of("accept","decline","kit","series","stake","confirm","bet","watch","status","forfeit","cancel");
            if(args.length==1){List<String> options=new ArrayList<>(arena.onlineChallengeable(duelPlayer));options.addAll(subs);return filter(args[0],options);}
            String s0=args[0].toLowerCase(Locale.ROOT);
            if(args.length==2&&s0.equals("kit"))return filter(args[1],List.of("mace","sword","axe","spear"));
            if(args.length==2&&s0.equals("series"))return filter(args[1],List.of("1","3"));
            if(args.length==2&&s0.equals("stake"))return filter(args[1],List.of("0","1000","10000","100000"));
            if(args.length==2&&(s0.equals("watch")||s0.equals("bet")))return filter(args[1],arena.liveMatchIds());
            if(args.length==3&&s0.equals("bet")){try{return filter(args[2],arena.duellistNames(Integer.parseInt(args[1])));}catch(NumberFormatException e){return List.of();}}
            return List.of();
        }
        /** /ashfall duelmap ... completes from the LIVE registry and world list, so an admin can maintain
         *  maps without having to remember keys or copy instance world names out of a log. The whole
         *  /ashfall tree is already gated above (players who are not admins get nothing at all), so simply
         *  being here means the sender is allowed to run these. */
        if(name.equals("ashfall")&&args.length>=2&&args[0].equalsIgnoreCase("duelmap")){
            if(duelMaps==null)return List.of();
            if(args.length==2)return filter(args[1],DUELMAP_SUBS);
            String sub=args[1].toLowerCase(Locale.ROOT);
            if(args.length==3)return switch(sub){
                case"create","enter","save","test","import","loot","dryrun","setspawn"->filter(args[2],duelMapKeys());
                case"build"->filter(args[2],duelMaps.maps().stream().map(DuelMapService.DuelMap::key).filter(k->k.startsWith("arena")).toList());
                case"drop"->filter(args[2],duelMaps.instanceNames());
                default->List.of();
            };
            if(args.length==4&&sub.equals("setspawn"))return filter(args[3],List.of("p1","p2","spectator"));
            return List.of();
        }
        if(name.equals("ashfall")&&args.length==2&&args[0].equalsIgnoreCase("hopper"))
            return filter(args[1],List.of("verify","rig","count","create"));
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
        if(args.length==1)return switch(name){case"f"->{List<String> options=new ArrayList<>(List.of("create","claim","unclaim","borders","networth","leaderboard","relations","ally","truce","storage","invite","accept","kick","leader","coleader","leave","disband","info","tag","deposit","withdraw","expand","sethome","home","homes","delhome","buyhome","history","locate"));options.addAll(publicOnlineNames(sender,""));yield filter(args[0],options);}case"shop"->filter(args[0],List.of("luxury","buy","sell","sellall"));case"settings"->filter(args[0],List.of("account","confirmations"));case"ah"->filter(args[0],List.of("sell","collect","cancel"));case"enderchest"->filter(args[0],sender instanceof Player viewer&&isAdmin(viewer)?List.of("upgrade","page","inspect"):List.of("upgrade","page"));case"events"->filter(args[0],List.of("track"));case"guide","rules"->filter(args[0],List.of("English","العربية"));case"leaderboards"->filter(args[0],List.of("money","networth","factions","bosses","kills","deaths","mobs","bounties","events","playtime"));case"relics"->filter(args[0],List.of("trace"));case"ashfall"->filter(args[0],List.of("help","balance","economy","bank","boss","elite","event","merchant","bulletin","feedback","faction","spawnclaim","relic","grave","border","setspawn","reload","debug","selftest","vanish","spectate","unspectate","audit","shard","progressrepair","cooldowns","bounty","dragon","replay","chatlog","dmlog","factionchatlog","lastloc","homes","factioninfo","ipban","monument","moderation","vault","hopper","duelmap","spawnershop"));case"nickname"->filter(args[0],List.of("random","off"));default->List.of();};
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
                case"bank"->filter(args[1],List.of("add","remove","set"));
                case"boss"->filter(args[1],List.of("spawn","here","despawn"));
                case"elite"->filter(args[1],List.of("stats","uncommon","rare","epic","legendary","miniboss"));
                case"event"->filter(args[1],List.of("resource","elitehunt","taskmaster","worldboss","stop"));
                case"economy"->filter(args[1],List.of("report"));
                case"feedback"->filter(args[1],List.of("notify","list","view","done","reopen","delete"));
                case"faction"->filter(args[1],List.of("inspect","resize","resetclaim","recalc"));
                case"merchant"->filter(args[1],List.of("spawn","remove"));
                case"bulletin"->filter(args[1],List.of("place","move","remove","list","refresh"));
                case"spawnclaim"->filter(args[1],List.of("select","info","clear"));
                case"relic"->filter(args[1],List.of("give","remove","forcerespawn"));
                case"grave"->filter(args[1],List.of("repair"));
                case"border"->filter(args[1],List.of("status","apply","restore"));
                case"shard"->filter(args[1],List.of("give","remove","set"));
                case"bounty"->filter(args[1],List.of("approve","reject","remove","inspect"));
                case"dragon"->filter(args[1],List.of("status","start"));
                case"replay"->filter(args[1],List.of("killer","victim"));
                case"ipban"->filter(args[1],List.of("ban","unban","duration","list"));
                case"monument"->filter(args[1],List.of("list","locate","tp","register","inspect","remove","rename","snapshot","restore","refill","history","reconstruct","revamp","prism","help"));
                case"moderation"->filter(args[1],List.of("view","duration","revoke","note","notes"));
                default->List.of();
            };
        }
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("ipban")&&args[1].equalsIgnoreCase("ban"))return onlineNames(sender,args[2]);
        if(name.equals("ashfall")&&args.length==4&&args[0].equalsIgnoreCase("ipban")&&args[1].equalsIgnoreCase("ban"))return filter(args[3],List.of("7d","3d","1d","12h","1h","30m","perm"));
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("ipban")&&Set.of("unban","duration").contains(args[1].toLowerCase(Locale.ROOT)))return onlineNames(sender,args[2]);
        if(name.equals("ashfall")&&args.length==4&&args[0].equalsIgnoreCase("ipban")&&args[1].equalsIgnoreCase("duration"))return filter(args[3],List.of("7d","3d","1d","12h","1h","30m","perm"));
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("bulletin")&&Set.of("place","move","remove").contains(args[1].toLowerCase(Locale.ROOT)))return filter(args[2],List.of("stats","players","factions","bounties","all"));
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("monument")&&args[1].equalsIgnoreCase("locate"))return filter(args[2],monuments.structureTypeKeys());
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("monument")&&Set.of("tp","remove","rename","snapshot","restore","inspect","history","refill","reconstruct","revamp","prism").contains(args[1].toLowerCase(Locale.ROOT)))return filter(args[2],monuments.cachedNames());
        if(name.equals("ashfall")&&args.length==4&&args[0].equalsIgnoreCase("monument")&&args[1].equalsIgnoreCase("restore"))return filter(args[3],List.of("preview","confirm"));
        if(name.equals("ashfall")&&args.length==4&&args[0].equalsIgnoreCase("monument")&&args[1].equalsIgnoreCase("refill"))return filter(args[3],List.of("scan","preview","confirm"));
        if(name.equals("ashfall")&&args.length==5&&args[0].equalsIgnoreCase("monument")&&args[1].equalsIgnoreCase("refill")&&Set.of("preview","confirm").contains(args[3].toLowerCase(Locale.ROOT)))return filter(args[4],List.of("force"));
        if(name.equals("ashfall")&&args.length==4&&args[0].equalsIgnoreCase("monument")&&args[1].equalsIgnoreCase("register"))return filter(args[3],monuments.structureTypeKeys());
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("moderation")&&Set.of("view","note","notes").contains(args[1].toLowerCase(Locale.ROOT)))return knownPlayerNames(sender,args[2]);
        if(name.equals("ashfall")&&args.length==4&&args[0].equalsIgnoreCase("moderation")&&args[1].equalsIgnoreCase("duration"))return filter(args[3],List.of("7d","3d","1d","12h","1h","30m","perm"));
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("replay"))return onlineNames(sender,args[2]);
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("merchant")){if(args[1].equalsIgnoreCase("spawn"))return filter(args[2],List.of("boss","event","shop","auction","banker"));if(args[1].equalsIgnoreCase("remove"))return filter(args[2],List.of("nearest"));}
        if(name.equals("ashfall")&&args.length==3&&args[0].equalsIgnoreCase("boss")&&Set.of("spawn","here").contains(args[1].toLowerCase(Locale.ROOT)))return filter(args[2],List.of("ashen","iron","piglin"));
        if(name.equals("ashfall")&&args[0].equalsIgnoreCase("elite")&&List.of("uncommon","rare","epic","legendary","miniboss").contains(args[1].toLowerCase(Locale.ROOT))){if(args.length==3)return filter(args[2],ELITE_MOBS);if(args.length==4&&parseMobType(args[2])!=null)return filter(args[3],List.of("here"));}
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
    /** Moderation targets can be offline, unlike every other admin-tool completion above — combines currently
     *  online real names with every name that's ever joined (Database.allPlayerNames()) so /ashfall moderation
     *  view/note/notes can suggest a name whether or not that player is on right now. */
    private List<String> knownPlayerNames(CommandSender sender,String prefix){
        java.util.LinkedHashSet<String> names=new java.util.LinkedHashSet<>();
        getServer().getOnlinePlayers().stream().filter(p->!p.equals(sender)).map(Player::getName).forEach(names::add);
        names.addAll(db.allPlayerNames());
        return filter(prefix,names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
    }
    private int parsePage(String[] args){if(args.length==0)return 1;try{return Math.max(1,Integer.parseInt(args[0]));}catch(NumberFormatException e){return 1;}}
    private Integer parsePageArg(String raw){try{return Math.max(1,Integer.parseInt(raw));}catch(NumberFormatException e){return null;}}
    private String trim(String value,int max){return value.length()<=max?value:value.substring(0,max-1)+"…";}
}
