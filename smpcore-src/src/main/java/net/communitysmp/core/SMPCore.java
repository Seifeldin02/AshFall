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
    private Database db;private DuelMapService duelMaps;private CoreEconomy economy;private BankService bank;private TeleportService teleports;private FactionService factions;private ShopService shop;private AuctionService auctions;private ProgressService progress;private RelicService relics;private BossEventService bosses;private BountyService bounties;private SpawnerService spawners;private SpawnClaimService spawnClaims;private EnderChestService enderChests;private NetWorthService netWorth;private MerchantService merchants;private VillagerCapsuleService capsules;private GraveService graves;private BulletinService bulletin;private UIService ui;private TabIntegration tabIntegration;private GuideService guides;private MessagingService messaging;private SettingsService settings;private AccountService account;private RegistrationService registration;private ConfirmationService confirmations;private ShardService shards;private MarketplaceService marketplace;private WeeklyDragonService weeklyDragon;private VillagerDiscountService villagerDiscounts;private WorldBorderService worldBorders;private GrimCompatibility grimCompatibility;private NicknameService nicknames;private TrustedAdminService trustedAdmins;private AdminToolsService adminTools;private DiscordReminderService discordReminders;private OrdersService ordersService;private AfkService afk;private PunishmentService punishments;private ReplayIntegration replay;private ObsidianDurabilityService obsidian;private IpBanService ipBans;private MonumentService monuments;private ModerationService moderation;private TradeTaxService tradeTax;private TaskMasterService taskMaster;private IndustrialHopperService industrialHoppers;private VoidWorldService voidWorlds;private DiscardedVaultService vault;private ArenaService arena;private ColosseumService colosseum;private SpawnerShopService spawnerShop;private SpectacleService spectacle;private PacketNametagService packetNametags;
    private final Map<UUID,Long> feedbackCooldowns=new HashMap<>();
    Database db(){return db;} UIService ui(){return ui;} ProgressService progress(){return progress;} TeleportService teleports(){return teleports;} SpawnClaimService spawnClaims(){return spawnClaims;}EnderChestService enderChests(){return enderChests;}NetWorthService netWorth(){return netWorth;}BankService bank(){return bank;}VillagerCapsuleService capsules(){return capsules;}FactionService factions(){return factions;}BossEventService bosses(){return bosses;}MessagingService messaging(){return messaging;}GraveService graves(){return graves;}BulletinService bulletin(){return bulletin;}SettingsService settings(){return settings;}AccountService account(){return account;}RegistrationService registration(){return registration;}ConfirmationService confirmations(){return confirmations;}ShardService shards(){return shards;}MarketplaceService marketplace(){return marketplace;}WeeklyDragonService weeklyDragon(){return weeklyDragon;}VillagerDiscountService villagerDiscounts(){return villagerDiscounts;}SpawnerService spawners(){return spawners;}NicknameService nicknames(){return nicknames;}TabIntegration tab(){return tabIntegration;}AfkService afk(){return afk;}RelicService relics(){return relics;}AdminToolsService adminTools(){return adminTools;}PunishmentService punishments(){return punishments;}ReplayIntegration replay(){return replay;}TrustedAdminService trustedAdmins(){return trustedAdmins;}IpBanService ipBans(){return ipBans;}ModerationService moderation(){return moderation;}PacketNametagService packetNametags(){return packetNametags;}
    OrdersService orders(){return ordersService;}
    TaskMasterService taskMaster(){return taskMaster;}
    IndustrialHopperService industrialHoppers(){return industrialHoppers;}
    VoidWorldService voidWorlds(){return voidWorlds;}

    DiscardedVaultService vault(){return vault;}
    ArenaService arena(){return arena;}
    ColosseumService colosseum(){return colosseum;}
    DuelMapService duelMaps(){return duelMaps;}
    ShopService shop(){return shop;} SpawnerShopService spawnerShop(){return spawnerShop;} SpectacleService spectacle(){return spectacle;}
    double creditEarned(String player,double amount,String detail){if(amount<=0)return 0;if(bank==null){db.changeBalance(player,amount);return amount;}return bank.creditEarned(player,amount,detail);}
    boolean isAdmin(Player p){return trustedAdmins!=null&&trustedAdmins.isAdmin(p);}
    /*  A world that exists only for as long as the thing happening inside it.
     *
     *  Colosseum instances, duel instances, event arenas and void worlds are all created, used and deleted.
     *  Nothing outside them may keep a Location that points into one: a Location holds its World, so a
     *  single cached one keeps the whole unloaded world object -- and everything it still references --
     *  alive for as long as the cache does. It is also a destination that no longer exists, and asking a
     *  Location for an unloaded world THROWS rather than returning null. */
    boolean isDisposableWorld(org.bukkit.World world){
        if(world==null)return false;
        if(colosseum!=null&&colosseum.isColosseumWorld(world))return true;
        if(duelMaps!=null&&duelMaps.isInstance(world))return true;
        if(voidWorlds!=null&&voidWorlds.isVoidWorld(world))return true;
        return arena!=null&&arena.isArenaWorld(world);
    }
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
        for(String resource:List.of("shop.yml","bosses.yml","events.yml","relics.yml","shards.yml","colosseum.yml"))
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
        spectacle=new SpectacleService(this);tradeTax=new TradeTaxService(this);taskMaster=new TaskMasterService(this);getServer().getPluginManager().registerEvents(taskMaster,this);industrialHoppers=new IndustrialHopperService(this);getServer().getPluginManager().registerEvents(industrialHoppers,this);vault=new DiscardedVaultService(this);getServer().getPluginManager().registerEvents(vault,this);spawnerShop=new SpawnerShopService(this);getServer().getPluginManager().registerEvents(spawnerShop,this);duelMaps=new DuelMapService(this);getServer().getPluginManager().registerEvents(duelMaps,this);arena=new ArenaService(this);getServer().getPluginManager().registerEvents(arena,this);colosseum=new ColosseumService(this);getServer().getPluginManager().registerEvents(colosseum,this);voidWorlds=new VoidWorldService(this);getServer().getPluginManager().registerEvents(voidWorlds,this);packetNametags=new PacketNametagService(this);spawners.startConsolidation();weeklyDragon=new WeeklyDragonService(this);graves=new GraveService(this);bulletin=new BulletinService(this);guides=new GuideService(this);messaging=new MessagingService(this);obsidian=new ObsidianDurabilityService(this,factions);
        villagerDiscounts=new VillagerDiscountService(this);
        worldBorders=new WorldBorderService(this);
        for(World world:getServer().getWorlds())try{world.setGameRule(GameRule.LOCATOR_BAR,false);}catch(Throwable ignored){}
        ui=new UIService(this,bosses,netWorth);nicknames=new NicknameService(this);tabIntegration=new TabIntegration(this,factions);
        adminTools=new AdminToolsService(this);discordReminders=new DiscordReminderService(this);ordersService=new OrdersService(this);afk=new AfkService(this);punishments=new PunishmentService(this);ipBans=new IpBanService(this);monuments=new MonumentService(this);moderation=new ModerationService(this);
        getServer().getPluginManager().registerEvents(new GameplayListener(this,factions,teleports,shop,auctions,bosses,bounties,relics,progress,spawners,spawnClaims,netWorth,merchants,capsules,graves,obsidian),this);
        getServer().getPluginManager().registerEvents(trustedAdmins,this);
        /** Orphan sweep and interrupted-run recovery: both need a live server, so neither belongs in the
         *  service constructor. Anything left ACTIVE in colosseum_runs at this point is a fight the process
         *  died in the middle of, and its entry fee goes back. */
        colosseum.start();
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
        for(String name:List.of("f","balance","pay","home","sethome","delhome","renamehome","homes","buyhome","tpa","tpahere","tpaccept","tpdeny","spawn","rtp","back","msg","reply","shop","luxuryshop","shardshop","settings","ah","bounty","bounties","events","relics","leaderboards","guide","smphelp","role","sidebar","feedback","stats","progress","history","graves","enderchest","ashfall","nickname","discord","admin","shout","afk","myorders","colosseum")){
            PluginCommand command=getCommand(name);if(command!=null){command.setExecutor(this);command.setTabCompleter(this);}
        }
        applyCommandFeedbackPolicy();
        getLogger().info("SMPCore 1.7.0 enabled: marketplace, accessibility settings, shards, faction relations and weekly Dragon are ready.");
    }
    /*  DELIVERING WHAT THE STASH OWES, WITHOUT LOSING IT ON THE WAY.
     *
     *  Two stores that cannot share a transaction: a SQLite row, and a player inventory that is only
     *  durable once Paper writes it to disk. Something has to happen first, and whichever it is decides
     *  which way a crash in the middle goes.
     *
     *  It used to delete first, which fails towards LOSS: the row is gone, the item is in an inventory
     *  that has not been saved, and a crash takes it with no record that anybody was owed anything.
     *
     *  It now goes the other way round, per item:
     *
     *      1. read the row, leave it alone
     *      2. put the item in the inventory; whatever does not fit stays in the row, resized
     *      3. flush the player to disk, so what we just handed over is actually durable
     *      4. only then delete the rows we delivered
     *
     *  A crash before (3) leaves every row claimable and nothing durable -- correct. A crash between (3)
     *  and (4) leaves an item that IS durable and a row that still says it is owed, so it can be claimed
     *  once more: one DELETE against a local file wide, and only on an abrupt kill. That is the residual,
     *  it is documented, and it fails towards the player rather than away from them.
     *
     *  Collecting twice on purpose still cannot duplicate anything, because step 4 already removed the row
     *  and stashRemove reports whether it was the one that removed it.
     *
     *  Returns how many claims are still outstanding, which is normally a full inventory. */
    /*  ================================================================================================
     *  DELIVERING A CLAIM ACROSS TWO STORES THAT CANNOT SHARE A TRANSACTION.
     *
     *  A claim lives in SQLite. The inventory it is delivered into lives in playerdata, which Paper writes
     *  as a separate file. One of them has to be written first, and whichever it is decides what a crash
     *  in the gap costs.
     *
     *      delete the row first    the row is gone, the item is in an unsaved inventory:  LOSS
     *      save the player first   the item is durable, the row still says it is owed:    DUPLICATE
     *
     *  The previous pass chose the second and documented the duplicate as a residual. It is not a residual
     *  any more, because the choice was a false one: the two stores cannot share a transaction, but the
     *  RECEIPT can share a transaction with the inventory.
     *
     *  A player's PersistentDataContainer is serialised into the same <uuid>.dat that carries their
     *  Inventory, written by one PlayerDataStorage.save() to a temporary file and renamed into place. So a
     *  note saying "row 41 was delivered" written into the PDC before that save either persists WITH the
     *  items or not at all. That is the atomic boundary this needs, and it already exists.
     *
     *  THE PROTOCOL, per collection:
     *
     *      1. reconcile()  -- settle any receipts left behind by an earlier crash
     *      2. read the rows; do not touch them
     *      3. for each row: add to the inventory, and write a receipt recording the row id and exactly
     *         how much of it did NOT fit
     *      4. saveData()   -- inventory and receipts become durable together, or neither does
     *      5. apply the receipts to the database: delete what fully fitted, shrink what partly fitted
     *      6. clear the receipts
     *
     *  WHERE A CRASH LANDS:
     *
     *      before 4    nothing durable, no receipt, rows untouched.        Nothing happened.
     *      between 4   items durable, receipts durable, rows still there.  reconcile() applies the
     *      and 5       receipts on the next join: delete/shrink, no second delivery.
     *      during 5    some rows applied, the rest still carry receipts.   reconcile() finishes them.
     *      between 5   receipts durable, rows already gone.                reconcile() clears them.
     *      and 6
     *
     *  Every one of those is idempotent, so running recovery twice does nothing the second time.
     *
     *  WHY NOTHING CAN SLIP BETWEEN 3 AND 4: steps 2-6 run inside a single server tick. Paper's periodic
     *  player save runs in the tick loop, not concurrently, so it cannot land in the middle and make an
     *  item durable behind a receipt's back. Anything that made this asynchronous would break it.
     *
     *  WHY A RECEIPT CANNOT BE READ AS THE WRONG CLAIM: smp_order_stash uses INTEGER PRIMARY KEY
     *  AUTOINCREMENT, which SQLite guarantees never reuses a rowid. A receipt for id 41 can only ever mean
     *  the claim that was id 41. */
    /*  EVERY INVENTORY INSERTION PATH IN THIS PLUGIN, AUDITED 2026-09-04.
     *
     *  Inventory#addItem rewrites the stack it is handed -- but ONLY when the insertion is partially
     *  accepted. A stack that is placed, merged whole, or refused outright comes back untouched. That is
     *  asserted by inventorySelfTest() below rather than believed, because I had it wrong twice.
     *
     *  A caller is therefore wrong if it reuses, compares, persists or logs that stack afterwards AND can
     *  ever meet a nearly-full inventory. Twenty-three call sites; the ones that could are listed first,
     *  and the rest are recorded so the next reader does not have to work it out again.
     *
     *      WRONG, now fixed
     *        SMPCore.deliverStash       compared the leftover against the stack that HAD BECOME the leftover,
     *                                   so a partial delivery read as "nothing fitted", the row stayed at
     *                                   full size, and the part that arrived would be handed out again
     *        SpawnerService payout      `returned += item.getAmount()` after CoreUtil.give undercounts the
     *                                   "N unsold items delivered" line whenever the inventory is nearly full
     *
     *      FRAGILE RATHER THAN WRONG, hardened anyway by cloning inside CoreUtil.give
     *        MerchantService x2         reads the scroll it has just sold to decide what to announce. Only
     *                                   the amount is rewritten and it reads meta, so it works today -- and
     *                                   it is exactly the shape that stops working
     *
     *      SAFE, and why
     *        CoreUtil.give              clones now, which covers every one of its ninety-odd callers
     *        ColosseumService.giveOrStash / ArenaService.giveOrStash   use the returned leftover, never the input
     *        ShopService.buy            counts from a separate `amount`, never from the stack
     *        OrdersService.deliver      subtracts from `remaining` BEFORE handing the stack over
     *        OrdersService.returnInserted / GraveService.syncCompass / ShardService kit fill  do not reuse it
     *        ArenaService kit fills     kitHotbar/kitExtra build fresh stacks per call, so nothing is shared
     *        IndustrialHopper x7        every one moves a `piece = item.clone()` and counts a saved `take`
     *        DuelMapService.put         loot placement; the caller does not read the stack afterwards
     *
     *  ColosseumService also had a separate duplication: an offline winner's loot was stashed by
     *  giveOrStash AND again by the branch that followed it. */
    static final String STASH_RECEIPT_KEY="stash_receipts";

    private org.bukkit.NamespacedKey receiptKey(){return new org.bukkit.NamespacedKey(this,STASH_RECEIPT_KEY);}

    /** "id:remaining" pairs. remaining 0 means the whole row was taken. */
    private java.util.LinkedHashMap<Long,Integer> readReceipts(Player player){
        java.util.LinkedHashMap<Long,Integer> out=new java.util.LinkedHashMap<>();
        String raw=player.getPersistentDataContainer().get(receiptKey(),org.bukkit.persistence.PersistentDataType.STRING);
        if(raw==null||raw.isBlank())return out;
        for(String part:raw.split(",")){
            int colon=part.indexOf(':');
            if(colon<=0)continue;
            try{out.put(Long.parseLong(part.substring(0,colon).trim()),Integer.parseInt(part.substring(colon+1).trim()));}
            catch(NumberFormatException ignored){}
        }
        return out;
    }
    private void writeReceipts(Player player,java.util.Map<Long,Integer> receipts){
        if(receipts.isEmpty()){player.getPersistentDataContainer().remove(receiptKey());return;}
        StringBuilder text=new StringBuilder();
        for(java.util.Map.Entry<Long,Integer> entry:receipts.entrySet()){
            if(text.length()>0)text.append(',');
            text.append(entry.getKey()).append(':').append(entry.getValue());
        }
        player.getPersistentDataContainer().set(receiptKey(),org.bukkit.persistence.PersistentDataType.STRING,text.toString());
    }

    /*  Settle receipts from a delivery whose database half never happened.
     *
     *  A receipt means the items ARE in a saved inventory. So the row must be applied, never re-delivered.
     *  Runs on join and at the start of every collection; doing it twice is a no-op both times. */
    int reconcileStash(Player player){
        java.util.LinkedHashMap<Long,Integer> receipts=readReceipts(player);
        if(receipts.isEmpty())return 0;
        int settled=0;
        for(java.util.Map.Entry<Long,Integer> entry:receipts.entrySet()){
            long id=entry.getKey();
            int remaining=Math.max(0,entry.getValue());
            try{
                if(remaining<=0){if(db.stashRemove(id))settled++;}
                else{
                    Database.StashRow row=db.stashRow(id);
                    /*  The row may already be the right size if the crash landed after the shrink. Only
                     *  shrink when it is still the pre-delivery size, so this cannot cut it twice. */
                    if(row!=null&&row.item().getAmount()>remaining){
                        org.bukkit.inventory.ItemStack rest=row.item().clone();
                        rest.setAmount(remaining);
                        if(db.stashShrink(id,rest))settled++;
                    }
                }
            }catch(Throwable failure){
                getLogger().warning("Could not settle claim receipt "+id+" for "+player.getName()+": "+failure);
            }
        }
        writeReceipts(player,java.util.Map.of());
        if(settled>0)getLogger().info("[stash] settled "+settled+" claim receipt(s) for "+player.getName()
                +" left behind by an interrupted delivery.");
        return settled;
    }

    /** Set by the failure-injection command only. Aborts a delivery at a named boundary so the recovery
     *  path can be exercised for real instead of argued about. Never set in normal operation. */
    volatile String stashFailPoint=null;

    private void maybeFail(String point){
        if(point.equals(stashFailPoint)){stashFailPoint=null;throw new IllegalStateException("injected stash failure at "+point);}
    }

    /*  Returns how many claims are still outstanding -- normally a full inventory. */
    int deliverStash(Player player){
        reconcileStash(player);
        /*  A disposable world's inventory IS the disposable part: Colosseum arenas and void worlds swap the
         *  real one out on entry and restore it on exit, so anything handed over inside is discarded when
         *  the instance is torn down. The claim stays where it is, and says why. */
        if(isDisposableWorld(player.getWorld())){
            CoreUtil.warn(player,"Claims cannot be collected in here — anything handed over would be left behind when this world closes.");
            CoreUtil.hint(player,"Leave first, then collect. Nothing expires.");
            return db.stashCount(CoreUtil.id(player));
        }
        java.util.List<Database.StashRow> owed=db.stashRows(CoreUtil.id(player));
        if(owed.isEmpty())return 0;

        java.util.LinkedHashMap<Long,Integer> receipts=new java.util.LinkedHashMap<>();
        /*  Exactly what went into the inventory, kept so it can come back out.
         *
         *  Without this, a failed save leaves the items sitting in a live inventory that nobody has
         *  recorded -- and Paper saves that inventory when the player next quits. The claim row is still
         *  there, so they would be paid a second time. Adding to an inventory is not a commit, and the
         *  only way to make it behave like one is to be able to undo it. */
        java.util.List<org.bukkit.inventory.ItemStack> handedOver=new java.util.ArrayList<>();
        int left=0;
        for(Database.StashRow row:owed){
            /*  addItem writes the remainder back into the stack it is handed, so the amount owed is read
             *  first and the stack itself is a clone -- the row's copy stays pristine for the failure path. */
            int wanted=row.item().getAmount();
            org.bukkit.inventory.ItemStack giving=row.item().clone();
            java.util.Collection<org.bukkit.inventory.ItemStack> over;
            try{over=player.getInventory().addItem(giving).values();}
            catch(Throwable failure){
                /*  One unreadable claim must not swallow the rest. Its row is untouched and everything
                 *  after it still gets its turn. */
                getLogger().warning("Stash row "+row.id()+" for "+player.getName()+" could not be delivered: "+failure);
                left++;continue;
            }
            int remaining=over.isEmpty()?0:over.iterator().next().getAmount();
            if(remaining>=wanted){left++;continue;}          // nothing fitted; no receipt, no change
            receipts.put(row.id(),remaining);
            org.bukkit.inventory.ItemStack taken=row.item().clone();
            taken.setAmount(wanted-remaining);
            handedOver.add(taken);
            if(remaining>0)left++;
        }
        if(receipts.isEmpty())return left;

        /*  THE ATOMIC BOUNDARY. The receipts go into the same NBT document as the items they record. */
        writeReceipts(player,receipts);
        try{maybeFail("before-save");player.saveData();}
        catch(Throwable failure){
            /*  The save did not happen, so neither did the receipts -- and the items must not be left in a
             *  live inventory that the next quit would persist behind the claim's back. Everything this
             *  call put in comes straight back out, the receipts go, and every row is untouched. */
            for(org.bukkit.inventory.ItemStack taken:handedOver)player.getInventory().removeItem(taken);
            writeReceipts(player,java.util.Map.of());
            getLogger().severe("Could not persist a claim delivery for "+player.getName()
                    +"; "+handedOver.size()+" item(s) were taken back and nothing was consumed: "+failure);
            return db.stashCount(CoreUtil.id(player));
        }
        maybeFail("after-save");

        for(java.util.Map.Entry<Long,Integer> entry:receipts.entrySet()){
            long id=entry.getKey();
            int remaining=entry.getValue();
            try{
                if(remaining<=0)db.stashRemove(id);
                else{
                    Database.StashRow row=db.stashRow(id);
                    if(row!=null&&row.item().getAmount()>remaining){
                        org.bukkit.inventory.ItemStack rest=row.item().clone();
                        rest.setAmount(remaining);
                        db.stashShrink(id,rest);
                    }
                }
            }catch(Throwable failure){
                /*  Left as a receipt. reconcile() will finish it on the next join. */
                getLogger().warning("Claim "+id+" was delivered but its row could not be updated: "+failure);
            }
        }
        maybeFail("after-apply");
        writeReceipts(player,java.util.Map.of());
        return left;
    }

    @Override public void onDisable(){if(bosses!=null)bosses.reconcileForcedChunks();if(enderChests!=null)enderChests.shutdown();if(spawnClaims!=null)spawnClaims.shutdown();if(discordReminders!=null)discordReminders.shutdown();if(adminTools!=null)adminTools.shutdown();if(trustedAdmins!=null)trustedAdmins.shutdown();if(grimCompatibility!=null)grimCompatibility.shutdown();if(tabIntegration!=null)tabIntegration.shutdown();if(teleports!=null)teleports.shutdown();if(ui!=null)ui.shutdown();if(bulletin!=null)bulletin.shutdown();if(graves!=null)graves.shutdown();if(obsidian!=null)obsidian.shutdown();if(weeklyDragon!=null)weeklyDragon.shutdown();if(spawners!=null){spawners.stopConsolidation();spawners.shutdown();}if(shards!=null)shards.shutdown();if(settings!=null)settings.shutdown();if(afk!=null)afk.shutdown();if(factions!=null)factions.shutdown();if(netWorth!=null)netWorth.shutdown();if(bosses!=null)bosses.shutdown();if(relics!=null)relics.shutdown();if(progress!=null)progress.shutdown();if(tradeTax!=null)tradeTax.shutdown();if(ordersService!=null)ordersService.shutdown();if(taskMaster!=null)taskMaster.end();if(voidWorlds!=null)voidWorlds.shutdown();if(industrialHoppers!=null)industrialHoppers.shutdown();if(vault!=null)vault.shutdown();if(arena!=null)arena.shutdown();if(colosseum!=null)colosseum.shutdown();if(spectacle!=null)spectacle.shutdown();if(packetNametags!=null)packetNametags.shutdown();if(db!=null)db.close();}

    /*  The player-facing half of the temporary event worlds.
     *
     *  /ashfall voidworld is the admin surface and is gated on the ADMIN account alone, which is fine for
     *  creating and deleting them and wrong for everything a participant needs: a player who logs into an
     *  event and wants out again cannot be made to wait for an administrator, and "there is no way out" is
     *  the stranding this system is specifically supposed to prevent.
     *
     *  So the verbs are split by permission rather than by command. exit and list are open to everyone,
     *  enter is a normal permission that defaults to allowed (the gate on a private event is simply not
     *  creating the world until it is wanted), and create and delete stay with the administrators. Tab
     *  completion offers exactly the verbs the sender may actually run. */
    private java.util.List<String> voidVerbs(CommandSender sender){
        java.util.List<String> verbs=new ArrayList<>(List.of("list","exit"));
        if(sender.hasPermission("smpcore.voidworld.enter"))verbs.add("enter");
        if(sender.hasPermission("smpcore.voidworld.manage")||(sender instanceof Player vp&&isAdmin(vp))||!(sender instanceof Player)){
            verbs.add("create");verbs.add("delete");verbs.add("open");verbs.add("close");
        }
        return verbs;
    }

    /** The name may be omitted while exactly one event world exists, which is the normal case for a live
     *  event -- "/voidworld open" should not need an argument to state the obvious. */
    private String soleVoidWorld(){
        java.util.List<String> labels=voidWorlds.labels();
        return labels.size()==1?labels.getFirst():null;
    }

    private boolean voidWorldCommand(CommandSender sender,String[] args){
        java.util.List<String> allowed=voidVerbs(sender);
        if(args.length==0){
            CoreUtil.msg(sender,"Event worlds: /voidworld "+String.join(" | ",allowed));
            return true;
        }
        String verb=args[0].toLowerCase(Locale.ROOT);
        if(!allowed.contains(verb)){CoreUtil.error(sender,"You cannot use /voidworld "+verb+".");return true;}
        switch(verb){
            case"list"->{
                java.util.List<String> labels=voidWorlds.labels();
                if(labels.isEmpty()){CoreUtil.msg(sender,"Event worlds: none");return true;}
                java.util.List<String> shown=new ArrayList<>();
                for(String each:labels)shown.add(each+(voidWorlds.isOpen(each)?" (open)":" (closed)"));
                CoreUtil.msg(sender,"Event worlds: "+String.join(", ",shown));
            }
            case"exit"->{
                if(!(sender instanceof Player ep)){CoreUtil.error(sender,"That is a player command.");return true;}
                String problem=voidWorlds.exit(ep);
                if(problem!=null)CoreUtil.error(ep,problem);
            }
            case"enter"->{
                if(!(sender instanceof Player np)){CoreUtil.error(sender,"That is a player command.");return true;}
                if(args.length<2){CoreUtil.error(sender,"Usage: /voidworld enter <name>");return true;}
                String problem=voidWorlds.enter(np,args[1]);
                if(problem!=null)CoreUtil.error(np,problem);
            }
            case"open",  "close"->{
                boolean open=verb.equals("open");
                String target=args.length>=2?args[1]:soleVoidWorld();
                if(target==null){CoreUtil.error(sender,"Usage: /voidworld "+verb+" <name>");return true;}
                if(voidWorlds.find(target)==null){CoreUtil.error(sender,"No void world called '"+target+"'.");return true;}
                voidWorlds.setOpen(target,open);
                CoreUtil.msg(sender,"Event world '"+target+"' is now "+(open?"OPEN -- anyone may enter.":"CLOSED -- administrators only."));
                db.history("SERVER",null,"VOIDWORLD","The temporary event world '"+target+"' was "+(open?"opened":"closed")+".");
            }
            case"create"->{
                if(args.length<2){CoreUtil.error(sender,"Usage: /voidworld create <name>");return true;}
                org.bukkit.World made=voidWorlds.create(args[1]);
                if(made==null)CoreUtil.error(sender,"Could not create a void world called '"+args[1]+"'.");
                else{CoreUtil.msg(sender,"Void world '"+args[1]+"' is ready ("+made.getName()+").");
                    db.history("SERVER",null,"VOIDWORLD","A temporary event world '"+args[1]+"' was created.");}
            }
            case"delete"->{
                if(args.length<2){CoreUtil.error(sender,"Usage: /voidworld delete <name>");return true;}
                String problem=voidWorlds.delete(args[1]);
                if(problem!=null)CoreUtil.error(sender,problem);
                else{CoreUtil.msg(sender,"Void world '"+args[1]+"' deleted; everyone inside was returned.");
                    db.history("SERVER",null,"VOIDWORLD","The temporary event world '"+args[1]+"' was deleted.");}
            }
            default->CoreUtil.error(sender,"Unknown verb.");
        }
        return true;
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){String name=command.getName().toLowerCase(Locale.ROOT);if(name.equals("admin"))return consoleAdmin(sender,args);if(name.equals("fly"))return flyCommand(sender,args);if(name.equals("flyspeed"))return flySpeedCommand(sender,args);if(name.equals("ashfall"))return admin(sender,args);if(name.equals("shout"))return shout(sender,args);if(name.equals("voidworld"))return voidWorldCommand(sender,args);if(!(sender instanceof Player p)){CoreUtil.error(sender,"This command requires a player.");return true;}db.ensurePlayer(CoreUtil.id(p),p.getName(),getConfig().getDouble("starting-balance",250));shards.activity(p);return switch(name){case"f"->factions.command(p,args);case"balance"->{CoreUtil.msg(p,"Balance: "+CoreUtil.money(db.player(CoreUtil.id(p)).balance()));yield true;}case"pay"->pay(p,args);case"home"->teleports.homeCommand(p,args);case"sethome"->teleports.setPersonalHome(p,args.length>0?args[0]:"home");case"delhome"->teleports.deletePersonalHome(p,args.length>0?args[0]:"home");case"renamehome"->{if(args.length<2){CoreUtil.error(p,"Usage: /renamehome <old> <new>");yield true;}yield teleports.renamePersonalHome(p,args[0],args[1]);}case"buyhome"->teleports.buyPersonalHome(p,args.length>0&&args[0].equalsIgnoreCase("confirm"));case"tpa"->{if(args.length<1)CoreUtil.error(p,"Usage: /tpa <player>");else teleports.tpa(p,args[0]);yield true;}case"tpahere"->{if(args.length<1)CoreUtil.error(p,"Usage: /tpahere <player>");else teleports.tpahere(p,args[0]);yield true;}case"tpaccept"->teleports.accept(p);case"tpdeny"->teleports.deny(p);case"spawn"->{teleports.warmup(p,teleports.spawn(),"server spawn");yield true;}case"rtp"->{if(args.length>0&&args[0].equalsIgnoreCase("queue")){yield teleports.toggleRtpQueue(p);}yield teleports.rtp(p);}case"msg"->messaging.message(p,args);case"reply"->messaging.reply(p,args);case"colosseum"->colosseum.command(p,args);case"duel"->duel(p,args);case"duels"->duels(p,args);case"orders"->{ordersService.openPublic(p);yield true;}case"order"->{ordersService.openPick(p,1,null);yield true;}case"shop"->shop.command(p,args);case"spawnershop"->{spawnerShop.open(p);yield true;}case"luxuryshop"->{marketplace.open(p,MarketplaceService.Section.LUXURY);yield true;}case"shardshop"->{marketplace.open(p,MarketplaceService.Section.SHARDS);yield true;}case"settings"->settings.command(p,args);case"ah"->auctions.command(p,args);case"bounty"->{if(args.length<2)CoreUtil.error(p,"Usage: /bounty <player> <amount>");else bounties.place(p,args[0],CoreUtil.parseMoney(args[1]));yield true;}case"bounties"->{bounties.list(p);yield true;}case"events"->{eventCommand(p,args);yield true;}case"relics"->{if(args.length>0&&args[0].equalsIgnoreCase("trace")){if(args.length<2)CoreUtil.error(p,"Usage: /relics trace <relic key>");else relics.trace(p,args[1]);}else relics.list(p);yield true;}case"leaderboards"->{leaderboards(p,args);yield true;}case"guide"->guides.command(p,args);case"rules"->guides.rulesCommand(p,args);case"smphelp"->{playerHelp(p);yield true;}case"role"->{CoreUtil.msg(p,"Your Ashfall role is "+roleName(p)+".");yield true;}case"sidebar"->progress.toggleSidebar(p);case"feedback"->{feedback(p,args);yield true;}case"stats"->progress.stats(p,args.length>0?args[0]:null);case"progress"->progress.show(p);case"history"->{int page=parsePage(args);progress.history(p,false,page);yield true;}case"homes"->teleports.listPersonalHomes(p,args.length>0&&args[0].equalsIgnoreCase("locate"));case"graves"->graves.command(p);case"enderchest"->enderChests.command(p,args);case"nickname"->nicknames.command(p,args);case"discord"->{p.sendMessage("§9Discord: §b§nhttps://discord.gg/G2FfuXjz8");yield true;}case"afk"->{boolean now=afk.toggle(p);CoreUtil.msg(p,now?"You are now AFK.":"Welcome back — no longer AFK.");yield true;}case"back"->{if(!isAdmin(p)){CoreUtil.error(p,"Only staff may use /back.");yield true;}yield teleports.back(p);}case"kit"->{
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
        int i=from+1;for(Database.StatsRow row:rows){String value=switch(column){case"balance"->CoreUtil.money(row.balance());case"play_seconds"->row.playSeconds()/3600+"h "+row.playSeconds()%3600/60+"m";case"player_kills"->row.playerKills()+" kills";case"deaths"->row.deaths()+" deaths";case"mob_kills"->row.mobKills()+" mobs";case"boss_kills"->row.bossKills()+" bosses";default->row.eventWins()+" wins";};CoreUtil.item(p,(i++)+". "+CoreUtil.safe(nicknames.displayName(row.name()))+"  "+CoreUtil.C_TEXT+value);}
    }

    void giveGuide(Player p){guides.giveBoth(p);}
    /*  The command index.
     *
     *  Was a bold all-caps banner over seven yellow labels. Yellow is the server's "pay attention" colour
     *  and this screen is a reference -- every line shouting is every line being ignored. Ember heading,
     *  receding labels, commands in white where the eye actually needs to land. */
    private void playerHelp(Player p){
        CoreUtil.heading(p,"Ashfall","commands");
        helpRow(p,"Factions","/f create, /f claim, /f relations, /f expand, /f <player>");
        helpRow(p,"Economy","/balance, /pay, /bounty, /bounties");
        helpRow(p,"Marketplace","/shop, /ah, /luxuryshop, /shardshop, /orders");
        helpRow(p,"Travel","/home, /tpa, /spawn, /rtp");
        helpRow(p,"Social","/msg, /r, /trade, /feedback");
        helpRow(p,"Combat","/duel, /colosseum");
        helpRow(p,"More","/settings, /events, /progress, /stats, /graves, /enderchest, /guide, /afk");
        CoreUtil.hint(p,"/guide walks through any of these in detail.");
    }
    private void helpRow(Player p,String label,String commands){
        p.sendMessage("  §8"+label+" §7"+commands);
    }

    private boolean admin(CommandSender sender,String[] args){
        if(sender instanceof Player p&&!isAdmin(p)){CoreUtil.error(sender,"Only the configured ADMIN account can use SMPCore administration.");return true;}
        if(args.length==0){adminHelp(sender);return true;}
        if(args[0].equalsIgnoreCase("help")){if(args.length==1)adminHelp(sender);else adminSectionHelp(sender,args[1]);return true;}
        try{
            switch(args[0].toLowerCase(Locale.ROOT)){
                case"voidworld"->{
                    /*  Temporary event worlds. Nothing goes in, nothing comes out.
                     *
                     *  Follows the conventions the rest of /ashfall uses: a verb as args[1], the subject as
                     *  args[2], every branch answers the sender, and every verb is offered by tab complete.
                     *  `enter` is player-only because it moves somebody; the rest work from console. */
                    if(args.length>=2&&args[1].equalsIgnoreCase("verify")){
                        if(!testGate(sender,"voidworld verify"))return true;
                        CoreUtil.msg(sender,"Verifying the void world entry/exit lifecycle:");
                        for(String line:voidWorlds.verify())CoreUtil.msg(sender,line);
                        return true;
                    }
                    if(args.length<2){CoreUtil.error(sender,"Usage: /ashfall voidworld <create|enter|exit|list|delete|open|close|verify> [name]");return true;}
                    String verb=args[1].toLowerCase(Locale.ROOT);
                    if(verb.equals("list")){
                        java.util.List<String> labels=voidWorlds.labels();
                        CoreUtil.msg(sender,"Void worlds: "+(labels.isEmpty()?"none":String.join(", ",labels)));
                        return true;
                    }
                    if(verb.equals("exit")){
                        if(!(sender instanceof Player vp)){CoreUtil.error(sender,"That is a player command.");return true;}
                        String problem=voidWorlds.exit(vp);
                        if(problem!=null)CoreUtil.error(vp,problem);
                        return true;
                    }
                    if(args.length<3){CoreUtil.error(sender,"Usage: /ashfall voidworld "+verb+" <name>");return true;}
                    String label=args[2];
                    switch(verb){
                        case"create"->{
                            org.bukkit.World made=voidWorlds.create(label);
                            if(made==null)CoreUtil.error(sender,"Could not create a void world called '"+label+"'.");
                            else{CoreUtil.msg(sender,"Void world '"+label+"' is ready ("+made.getName()+").");
                                db.history("SERVER",null,"VOIDWORLD","A temporary event world '"+label+"' was created.");}
                        }
                        case"enter"->{
                            if(!(sender instanceof Player vp)){CoreUtil.error(sender,"That is a player command.");return true;}
                            String problem=voidWorlds.enter(vp,label);
                            if(problem!=null)CoreUtil.error(vp,problem);
                        }
                        case"open","close"->{
                            if(voidWorlds.find(label)==null){CoreUtil.error(sender,"No void world called '"+label+"'.");return true;}
                            voidWorlds.setOpen(label,verb.equals("open"));
                            CoreUtil.msg(sender,"Event world '"+label+"' is now "+(verb.equals("open")?"OPEN -- anyone may enter.":"CLOSED -- administrators only."));
                            db.history("SERVER",null,"VOIDWORLD","The temporary event world '"+label+"' was "+verb+"ed.");
                        }
                        case"delete"->{
                            String problem=voidWorlds.delete(label);
                            if(problem!=null)CoreUtil.error(sender,problem);
                            else{CoreUtil.msg(sender,"Void world '"+label+"' deleted; everyone inside was returned.");
                                db.history("SERVER",null,"VOIDWORLD","The temporary event world '"+label+"' was deleted.");}
                        }
                        default->CoreUtil.error(sender,"Usage: /ashfall voidworld <create|enter|exit|list|delete|open|close> [name]");
                    }
                    return true;
                }
                case"hopper"->{if(args.length>=2&&args[1].equalsIgnoreCase("livesuite")){
                    /** Console-runnable: the suite has to be able to prove itself with nobody standing in
                     *  the world, so it falls back to the main world's spawn. */
                    org.bukkit.World sw=sender instanceof Player sp?sp.getWorld():getServer().getWorlds().getFirst();
                    org.bukkit.Location sat=sender instanceof Player sp2?sp2.getLocation():sw.getSpawnLocation();
                    int sx=args.length>=5?Integer.parseInt(args[2]):sat.getBlockX()+6;
                    int sy=args.length>=5?Integer.parseInt(args[3]):Math.max(sat.getBlockY()+3,72);
                    int sz=args.length>=5?Integer.parseInt(args[4]):sat.getBlockZ();
                    CoreUtil.msg(sender,"Live Industrial Hopper conservation suite (real ticking blocks):");
                    new IndustrialHopperLive(this,industrialHoppers,sw,sx,sy,sz,
                            line->{CoreUtil.msg(sender,"  "+line);getLogger().info("[IH-live] "+line);}).run();
                    return true;
                }
                if(args.length>=6&&args[1].equalsIgnoreCase("watch")){
                    org.bukkit.World ww=args.length>=7?getServer().getWorld(args[6]):(sender instanceof Player wp?wp.getWorld():getServer().getWorlds().getFirst());
                    if(ww==null){CoreUtil.error(sender,"Unknown world.");return true;}
                    industrialHoppers.watch(ww,Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]),
                            Integer.parseInt(args[5]),args.length>=8?Integer.parseInt(args[7]):30,
                            line->{CoreUtil.msg(sender,"  "+line);getLogger().info("[IH-watch] "+line);});
                    return true;
                }
                if(args.length>=2&&args[1].equalsIgnoreCase("live")){
                    /** Console-runnable on purpose: this is the rig that has to prove itself without a
                     *  human standing in the world, so it falls back to the main world spawn. */
                    org.bukkit.World lw=sender instanceof Player lp?lp.getWorld():getServer().getWorlds().getFirst();
                    org.bukkit.Location at=sender instanceof Player lp2?lp2.getLocation():lw.getSpawnLocation();
                    int amount=args.length>=3?Math.max(1,Math.min(64,Integer.parseInt(args[2]))):64;
                    CoreUtil.msg(sender,"Building a live chest/hopper/chest/hopper/chest rig with real ticking blocks:");
                    org.bukkit.Material what=args.length>=4?org.bukkit.Material.matchMaterial(args[3]):Material.STONE;
                    if(what==null||!what.isItem()){CoreUtil.error(sender,"Unknown item: "+args[3]);return true;}
                    industrialHoppers.liveChain(lw,at.getBlockX()+3,Math.max(at.getBlockY()+2,70),at.getBlockZ(),amount,what,
                            line->{CoreUtil.msg(sender,"  "+line);getLogger().info("[IH-live] "+line);});
                    return true;
                }
                if(args.length>=2&&args[1].equalsIgnoreCase("verify")){if(!testGate(sender,"hopper verify"))return true;CoreUtil.msg(sender,"Verifying Industrial Hopper parity against a live rig:");for(String line:new IndustrialHopperVerify(this,industrialHoppers).run())CoreUtil.msg(sender,"  "+line);return true;}if(args.length>=5&&args[1].equalsIgnoreCase("rig")){org.bukkit.World rw=sender instanceof Player rp?rp.getWorld():getServer().getWorlds().get(0);CoreUtil.msg(sender,industrialHoppers.rig(rw,Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4])));return true;}if(args.length>=5&&args[1].equalsIgnoreCase("count")){org.bukkit.World cw=sender instanceof Player cp?cp.getWorld():getServer().getWorlds().get(0);CoreUtil.msg(sender,industrialHoppers.count(cw,Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4])));return true;}if(args.length>=5&&args[1].equalsIgnoreCase("create")){org.bukkit.World w=sender instanceof Player hp?hp.getWorld():getServer().getWorlds().get(0);boolean made=industrialHoppers.install(w.getBlockAt(Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4])));CoreUtil.msg(sender,made?"Industrial Hopper installed.":"That block is not a hopper.");return true;}if(args.length>=4){org.bukkit.World world=args.length>4?getServer().getWorld(args[4]):(sender instanceof Player hp?hp.getWorld():getServer().getWorlds().get(0));if(world==null){CoreUtil.error(sender,"Unknown world.");return true;}try{CoreUtil.msg(sender,industrialHoppers.describe(world,Integer.parseInt(args[1]),Integer.parseInt(args[2]),Integer.parseInt(args[3])));}catch(NumberFormatException e){CoreUtil.error(sender,"Usage: /ashfall hopper <x> <y> <z> [world]");}}else{java.util.List<String> all=industrialHoppers.describeAll();CoreUtil.msg(sender,"Industrial hoppers in memory: "+all.size());for(String line:all)CoreUtil.msg(sender,"  "+line);}}
                case"vault"->{int page=1;if(args.length>1)try{page=Math.max(1,Integer.parseInt(args[1]));}catch(NumberFormatException ignored){}CoreUtil.msg(sender,vault.summaryLine());vault.show(sender,page);}
                case"balance"->adminBalance(sender,args);
                case"boss"->adminBoss(sender,args);
                case"colosseum"->adminColosseum(sender,args);case"duelmap"->adminDuelMap(sender,args);case"spawnershop"->CoreUtil.msg(sender,spawnerShop.describeStock());
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
                case"lease"->adminLease(sender,args);   /* staging test coordination; see testGate */
                case"stash"->adminStash(sender,args);
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
        adminCategory(s,"Arenas","/ashfall duelmap, /ashfall colosseum");
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
            case"arenas","colosseum"->colosseumHelp(s);
            case"voidworld","voidworlds"->voidWorldHelp(s);
            case"duelmap","duelmaps"->adminCommands(s,"Duel Maps","/ashfall duelmap <"+String.join("|",DUELMAP_SUBS)+">",
                "  build/import/save commit a template; test/dryrun/loot clone one; drop removes an instance.",
                "  canary = template persistence proof, verify = full pipeline.");
            case"maintenance","debug"->adminCommands(s,"Debug / Maintenance","/ashfall border status","/ashfall border apply","/ashfall border restore","/ashfall setspawn","/ashfall reload","/ashfall debug","/ashfall selftest","/ashfall grave repair",
                    "/ashfall lease status                            who is running staging tests, and what is live",
                    "/ashfall lease acquire <purpose> [seconds]       take the exclusive test window",
                    "/ashfall lease release                           give it back",
                    "/ashfall lease break                             take it from a crashed holder",
                    "/ashfall stash <player>                          what the claim stash owes somebody",
                    "/ashfall stash <player> receipts                 undelivered claim receipts in their playerdata",
                    "/ashfall stash <player> reconcile                settle those receipts now",
                    "/ashfall stash <player> fail <boundary>          abort the next delivery, to exercise recovery");
            default->adminHelp(s);
        }
    }
    /** The Colosseum admin surface, in the order an arena is actually brought up: make it, build it,
     *  commit it, test it, then keep an eye on what it is running. */
    private void voidWorldHelp(CommandSender s){
        adminCommands(s,"Event (Void) Worlds",
            "/voidworld enter|exit|list                       the player-facing verbs",
            "/ashfall voidworld create|delete <name>          admin only",
            "/ashfall voidworld open|close <name>             a closed world is admin-only to ENTER",
            "/ashfall voidworld verify                        the entry/exit lifecycle suite",
            "",
            "Crossing INTO a void world by any route -- the command, /tp, an admin teleport, another",
            "plugin -- captures the player's real state from where they came FROM, once, and isolates",
            "them. Crossing back out restores only after they are verifiably outside, and the snapshot",
            "is not deleted until that succeeds. A failed exit keeps the session intact.");
    }

    private void colosseumHelp(CommandSender s){
        adminCommands(s,"Boss Colosseum",
            "/ashfall colosseum list                          arenas, bosses, stats and snapshot state",
            "/ashfall colosseum create <arena>                open (or seed) the build workspace",
            "/ashfall colosseum enter <arena>                 teleport in to build it (creative)",
            "/ashfall colosseum setspawn <arena> <player|boss|spectator>",
            "/ashfall colosseum setboss <arena> <boss>        move a boss to another arena",
            "/ashfall colosseum save <arena>                  commit it; future encounters clone this",
            "/ashfall colosseum exit                          back where you came from",
            "/ashfall colosseum test <boss>                   a REAL encounter, free: no charge, no prize,",
            "                                                 no reward table, no allowance, no leaderboard",
            "/ashfall colosseum instances                     live encounters, worlds, chunks, prep times",
            "/ashfall colosseum drop <instance-world>         end one (interrupts and REFUNDS its fighter)",
            "/ashfall colosseum orphans                       remove leftover instance worlds",
            "/ashfall colosseum reload                        reload colosseum.yml (refunds live encounters)",
            "/ashfall colosseum verify                        the full automated suite",
            "/ashfall colosseum bench <instances> <seconds>   measure what concurrency costs this server",
            "",
            "Player side: /colosseum, /colosseum stats [player], /colosseum top [boss], /colosseum leave.",
            "",
            "Six bosses, one shared limit of 3 rewarded victories per player per Riyadh day.",
            "Each declares environment: NORMAL or NETHER -- the instance is CREATED in that",
            "environment (same arena, same blocks; no Nether terrain is generated).",
            "A rewarded win also pays Shards from the SAME daily allowance as world bosses.",
            "Every boss value lives in plugins/SMPCore/colosseum.yml -- nothing is compiled in.");
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
    /** Derived from the SAME predicate the command validates with, rather than hand-listed beside it.
     *
     *  The hand-written list had drifted: `guardian` was spawnable and worked perfectly, it simply was not
     *  offered, and so were a dozen others. Generating it from parseMobType means the suggestions and the
     *  accepted values can never disagree again. */
    /*  Hiding console/RCON command messages from in-game admins.
     *
     *  `broadcast-console-to-ops` and `broadcast-rcon-to-ops` are ALREADY false on both servers, so the
     *  usual answer is not the answer here -- whatever the owner is seeing survives both. The remaining
     *  vanilla mechanism is the sendCommandFeedback gamerule, which is what actually governs whether a
     *  command's success message is echoed to operators.
     *
     *  It is config-gated and OFF by default on purpose: the same gamerule also suppresses the feedback a
     *  PLAYER gets from their own commands, which the owner explicitly asked not to break. Turn it on, see
     *  whether the message disappears, and we will know which mechanism produced it. Commands keep being
     *  written to the server log either way, so the audit trail is untouched. */
    void applyCommandFeedbackPolicy(){
        if(!getConfig().getBoolean("admin.hide-console-command-feedback",false))return;
        for(org.bukkit.World world:getServer().getWorlds())
            world.setGameRule(org.bukkit.GameRule.SEND_COMMAND_FEEDBACK,false);
        getLogger().info("[Admin] sendCommandFeedback disabled on all worlds (admin.hide-console-command-feedback).");
    }

    private static final List<String> ELITE_MOBS=buildEliteMobs();
    private static List<String> buildEliteMobs(){
        List<String> out=new ArrayList<>();out.add("here");
        for(org.bukkit.entity.EntityType type:org.bukkit.entity.EntityType.values()){
            String name=type.name().toLowerCase(Locale.ROOT);
            if(parseMobType(name)!=null)out.add(name);
        }
        return List.copyOf(out);
    }
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
    /** Which Colosseum arenas an encounter could actually be sent to right now. An arena with no committed
     *  snapshot is not a failure -- it just has not been built -- but it is exactly what stops /colosseum
     *  reaching a player, so it belongs in the selftest readout rather than in a support ticket. */
    private String colosseumSnapshotStatus(){
        if(colosseum==null)return "unavailable";
        List<String> missing=new ArrayList<>();
        int total=0;
        for(ColosseumArenas.Arena a:colosseum.arenas().arenas()){total++;if(!colosseum.arenas().hasSnapshot(a))missing.add(a.key());}
        return (total-missing.size())+"/"+total+(missing.isEmpty()?" (all playable)":" - not yet built: "+String.join(", ",missing));
    }
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
                if(!testGate(sender,"duelmap canary"))return;
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

    // ------------------------------------------------------------------ colosseum

    /** Every /ashfall colosseum subcommand, in the order help prints them. Single source of truth for the
     *  help text, the tab completion and the unknown-subcommand reply, so the three cannot drift. */
    private static final List<String> COLOSSEUM_SUBS=List.of("list","create","enter","exit","save","setspawn",
        "setboss","test","instances","drop","orphans","reload","verify","bench");

    private final Map<String,org.bukkit.Location> colosseumReturn=new java.util.concurrent.ConcurrentHashMap<>();

    private void colosseumUsage(CommandSender sender,String sub,String what){
        CoreUtil.error(sender,"Usage: /ashfall colosseum "+sub+" <"+what+">");
        if(what.startsWith("arena"))CoreUtil.msg(sender,"Arenas: "+String.join(", ",colosseum.arenas().arenaKeys()));
        else if(what.startsWith("boss"))CoreUtil.msg(sender,"Bosses: "+String.join(", ",colosseum.bosses().keys()));
        else if(what.startsWith("instance")){
            List<String> live=colosseum.arenas().instanceNames();
            CoreUtil.msg(sender,"Live instances: "+(live.isEmpty()?"none":String.join(", ",live)));
        }
    }

    /** /ashfall colosseum ... -- arena maintenance with the same commit guarantees as duel maps: a write
     *  barrier that actually flushes, a validated temporary snapshot, an atomic publish, and the previous
     *  good snapshot preserved on any failure. */
    private void adminColosseum(CommandSender sender,String[] args){
        if(colosseum==null){CoreUtil.error(sender,"The Colosseum is unavailable.");return;}
        ColosseumArenas svc=colosseum.arenas();
        String sub=args.length>1?args[1].toLowerCase(Locale.ROOT):"list";
        switch(sub){
            case"list"->{
                CoreUtil.msg(sender,"Colosseum arenas:");
                for(ColosseumArenas.Arena a:svc.arenas())
                    CoreUtil.msg(sender,"  "+a.key()+" - "+a.name()+" world="+a.templateWorld()
                        +(getServer().getWorld(a.templateWorld())!=null?" (loaded)":" (not loaded)")
                        +(svc.hasSnapshot(a)?" [committed: "+svc.describeSnapshot(a)+"]":" [NOT COMMITTED - encounters cannot use it]"));
                CoreUtil.msg(sender,"Colosseum bosses:");
                for(ColosseumBosses.BossDef d:colosseum.bosses().all())
                    CoreUtil.msg(sender,"  "+d.key()+" - "+d.name()+" ["+d.style()+"/"+d.difficulty()+"] arena="+d.arena()
                        +" hp="+(long)d.health()+" dmg="+d.damage()+" limit="+ColosseumService.timeText(d.timeLimitSeconds())
                        +" fee="+CoreUtil.money(d.entryFee())+" prize="+CoreUtil.money(d.cashPrize())
                        +(d.available()?"":" [CLOSED]"));
                CoreUtil.msg(sender,"Snapshots live in "+svc.snapshotRoot().getAbsolutePath());
            }
            case"create"->{
                ColosseumArenas.Arena a=args.length>2?svc.arena(args[2]):null;
                if(a==null){colosseumUsage(sender,"create","arena");return;}
                CoreUtil.msg(sender,"Opening the "+a.name()+" workspace - this can take a few seconds...");
                org.bukkit.World w=svc.workspace(a,line->CoreUtil.msg(sender,"  "+line));
                if(w==null){CoreUtil.error(sender,"Could not create that workspace.");return;}
                CoreUtil.msg(sender,"Template world "+w.getName()+" is ready. Build it, then /ashfall colosseum save "+a.key()+".");
                if(!svc.hasSnapshot(a))CoreUtil.msg(sender,"It has no committed snapshot yet, so encounters cannot use it until you save.");
            }
            case"enter"->{
                if(!(sender instanceof Player p)){CoreUtil.error(sender,"Players only.");return;}
                ColosseumArenas.Arena a=args.length>2?svc.arena(args[2]):null;
                if(a==null){colosseumUsage(sender,"enter","arena");return;}
                org.bukkit.World w=svc.workspace(a,line->CoreUtil.msg(p,"  "+line));
                if(w==null){CoreUtil.error(sender,"Could not open that workspace.");return;}
                if(!svc.holdsArena(w,a)){
                    CoreUtil.error(sender,a.name()+" has no arena in its workspace and nothing to rebuild it from.");
                    CoreUtil.msg(sender,"Its base template is '"+a.baseTemplate()+"'; commit that duel map first, or build this one by hand.");
                }
                colosseumReturn.put(p.getUniqueId().toString(),p.getLocation());
                p.teleport(a.playerSpawn(w));
                p.setGameMode(org.bukkit.GameMode.CREATIVE);
                CoreUtil.msg(p,"Editing "+a.name()+". /ashfall colosseum save "+a.key()+" commits it; /ashfall colosseum exit returns you.");
            }
            case"exit"->{
                if(!(sender instanceof Player p)){CoreUtil.error(sender,"Players only.");return;}
                org.bukkit.Location back=colosseumReturn.remove(p.getUniqueId().toString());
                p.teleport(back!=null?back:getServer().getWorlds().get(0).getSpawnLocation());
                CoreUtil.msg(p,"Left the Colosseum template.");
            }
            case"save"->{
                ColosseumArenas.Arena a=args.length>2?svc.arena(args[2]):null;
                if(a==null){colosseumUsage(sender,"save","arena");return;}
                CoreUtil.msg(sender,svc.commitTemplate(a));
            }
            case"setspawn"->{
                if(!(sender instanceof Player p)){CoreUtil.error(sender,"Players only.");return;}
                if(args.length<4){colosseumUsage(sender,"setspawn","arena> <player|boss|spectator");return;}
                ColosseumArenas.Arena a=svc.arena(args[2]);
                if(a==null){colosseumUsage(sender,"setspawn","arena");return;}
                String which=args[3].toLowerCase(Locale.ROOT);
                String field=switch(which){case"player"->"player-spawn";case"boss"->"boss-spawn";case"spectator"->"spectator";default->null;};
                if(field==null){colosseumUsage(sender,"setspawn","arena> <player|boss|spectator");return;}
                org.bukkit.Location at=p.getLocation();
                if(!writeColosseumConfig("arenas."+a.key()+"."+field,List.of(round2(at.getX()),round2(at.getY()),round2(at.getZ())))){
                    CoreUtil.error(sender,"Could not write colosseum.yml.");return;}
                colosseum.reload();
                CoreUtil.msg(p,"Set the "+which+" spawn for "+a.name()+" to "+fmt(at)+".");
            }
            case"setboss"->{
                if(args.length<4){colosseumUsage(sender,"setboss","arena> <boss");return;}
                ColosseumArenas.Arena a=svc.arena(args[2]);
                ColosseumBosses.BossDef d=colosseum.bosses().boss(args[3]);
                if(a==null){colosseumUsage(sender,"setboss","arena");return;}
                if(d==null){colosseumUsage(sender,"setboss","boss");return;}
                if(!writeColosseumConfig("bosses."+d.key()+".arena",a.key())){CoreUtil.error(sender,"Could not write colosseum.yml.");return;}
                colosseum.reload();
                CoreUtil.msg(sender,d.name()+" now fights in "+a.name()+".");
            }
            case"test"->{
                if(!(sender instanceof Player p)){CoreUtil.error(sender,"Players only - an admin test is a real encounter.");return;}
                ColosseumBosses.BossDef d=args.length>2?colosseum.bosses().boss(args[2]):null;
                if(d==null){colosseumUsage(sender,"test","boss");return;}
                CoreUtil.msg(sender,"Admin test: a real instance and a real fight, with NO charge, NO prize, NO reward table, no daily allowance used and no leaderboard entry.");
                colosseum.challenge(p,d.key(),true);
            }
            case"instances"->{for(String line:colosseum.instanceReport())CoreUtil.msg(sender,line);}
            case"bench"->{
                int count=args.length>2?parseIntOr(args[2],2):2,seconds=args.length>3?parseIntOr(args[3],20):20;
                /** Results arrive over the following seconds, by which time an RCON caller has already been
                 *  disconnected -- so every line goes to the log as well as to whoever asked. */
                colosseum.bench(count,seconds,line->{CoreUtil.msg(sender,line);getLogger().info("[colosseum-bench] "+line);});
            }
            case"drop"->{
                if(args.length<3){colosseumUsage(sender,"drop","instance-world");return;}
                CoreUtil.msg(sender,colosseum.dropInstance(args[2]));
            }
            case"orphans"->CoreUtil.msg(sender,"Removed "+svc.cleanupOrphans()+" orphaned Colosseum instance(s); swept "+svc.sweepDetached()+" detached folder(s).");
            case"reload"->{
                int ended=colosseum.interruptAll("configuration reload");
                colosseum.reload();
                CoreUtil.msg(sender,"Colosseum configuration reloaded ("+colosseum.arenas().arenas().size()+" arena(s), "
                    +colosseum.bosses().all().size()+" boss(es))"+(ended>0?"; "+ended+" running encounter(s) were interrupted and refunded.":"."));
            }
            case"verify"->{
                if(!testGate(sender,"colosseum verify"))return;
                CoreUtil.msg(sender,"Verifying the Colosseum end to end - this creates and destroys its own instances:");
                new ColosseumVerify(this,colosseum).run(line->CoreUtil.msg(sender,"  "+line));
            }
            default->{
                if(args.length>1)CoreUtil.error(sender,"Unknown subcommand '"+args[1]+"'.");
                CoreUtil.msg(sender,"COLOSSEUM - /ashfall colosseum <"+String.join("|",COLOSSEUM_SUBS)+">");
                CoreUtil.msg(sender,"  create/enter/save build and commit an arena; test runs a free real encounter.");
                CoreUtil.msg(sender,"  instances/drop/orphans manage live worlds; verify is the full automated suite.");
                CoreUtil.msg(sender,"  bench <instances> <seconds> measures what concurrent encounters cost this server.");
                CoreUtil.msg(sender,"  Arenas: "+String.join(", ",colosseum.arenas().arenaKeys())+" | Bosses: "+String.join(", ",colosseum.bosses().keys()));
            }
        }
    }

    /** colosseum.yml is not the plugin's main config, so it is loaded, edited and written back explicitly.
     *  Comments in the file are lost on a write, which is why only setspawn/setboss do it -- everything else
     *  is edited by hand. */
    private static int parseIntOr(String value,int fallback){try{return Integer.parseInt(value);}catch(NumberFormatException e){return fallback;}}

    private boolean writeColosseumConfig(String path,Object value){
        try{
            java.io.File file=new java.io.File(getDataFolder(),"colosseum.yml");
            org.bukkit.configuration.file.YamlConfiguration yaml=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            yaml.set(path,value);
            yaml.save(file);
            return true;
        }catch(java.io.IOException error){getLogger().warning("Could not write colosseum.yml: "+error.getMessage());return false;}
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
    private void economyWindow(CommandSender s,String label,List<Database.EconomyTotal> totals){Map<String,Double> m=new HashMap<>();for(Database.EconomyTotal total:totals)m.put(total.category(),total.amount());double normal=m.getOrDefault("MOB_NORMAL",0.0),elite=m.getOrDefault("ELITE",0.0)+m.getOrDefault("BOSS",0.0),milestones=m.getOrDefault("MILESTONE",0.0)+m.getOrDefault("EVENT",0.0),selling=m.getOrDefault("SHOP_SELL",0.0),removed=Math.max(0,-m.entrySet().stream().filter(e->e.getValue()<0).mapToDouble(Map.Entry::getValue).sum()),net=m.values().stream().mapToDouble(Double::doubleValue).sum();if(Math.abs(net)<.0001)net=0;CoreUtil.heading(s,"Economy",label);s.sendMessage("§7Normal mobs: §a"+CoreUtil.money(normal)+"  §7Elites/bosses: §a"+CoreUtil.money(elite));s.sendMessage("§7Milestones/events: §a"+CoreUtil.money(milestones)+"  §7Shop selling: §a"+CoreUtil.money(selling));s.sendMessage("§7Removed: §c"+CoreUtil.money(removed)+"  §7Net creation: "+(net>=0?"§a":"§c")+CoreUtil.money(net));}
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
        for(Database.HomeRow home:homes){Location l=home.location();String where=l.getWorld()==null?"unknown world":l.getWorld().getName()+" "+l.getBlockX()+", "+l.getBlockY()+", "+l.getBlockZ();CoreUtil.item(s,CoreUtil.safe(home.name())+"  "+CoreUtil.C_MUTE+where);}
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
    /*  THE CLAIM STASH, FROM THE OUTSIDE.
     *
     *  Auction purchases, expired listings and Colosseum reward overflow all land here when they cannot go
     *  straight into an inventory, and until now nothing could see them: it is a table of serialised item
     *  blobs. "I bought something and never got it" had no answer that was not a database client.
     *
     *  Read-only apart from `grant`, which exists so the delivery path can be exercised against a live
     *  player -- a full inventory, a second collection, a restart in the middle -- rather than only against
     *  the row lifecycle in the selftest. It is operator-gated and written to the audit trail like every
     *  other admin action that creates something. */
    /*  ================================================================================================
     *  THE STAGING TEST LEASE.
     *
     *  /ashfall colosseum verify reported three failures on 2026-09-04, and every one of them was true at
     *  the moment it looked: a harness scenario had an encounter running, and the verifier's "no scheduled
     *  task survives its encounter" check found a scheduled task, belonging to somebody else's encounter.
     *  Re-run on a quiet server it was 278/0.
     *
     *  A verifier that fails because something else is legitimately happening is worse than one that does
     *  not run: it teaches whoever reads it to discount failures. So the destructive suites take an
     *  exclusive lease, and refuse -- BEFORE touching anything -- when they cannot have it, naming what is
     *  in the way.
     *
     *  What is deliberately NOT gated: /ashfall selftest (one transaction, rolled back) and
     *  /ashfall duelmap verify (reads the registry). Read-only work should not queue behind a lease.
     *
     *  A crashed holder cannot keep it: the lease carries an expiry and an expired one is simply taken.
     *  Nothing here ever cancels a real player's encounter to make room for a test. */
    record TestLease(String owner,String purpose,long expiresAt){
        boolean expired(){return System.currentTimeMillis()>expiresAt;}
        long secondsLeft(){return Math.max(0,(expiresAt-System.currentTimeMillis())/1000);}
    }
    private static final String LEASE_STATE="staging_test_lease";

    TestLease testLease(){
        String raw=db.state(LEASE_STATE);
        if(raw==null||raw.isBlank())return null;
        String[] parts=raw.split("\\|",3);
        if(parts.length<3)return null;
        try{
            TestLease lease=new TestLease(parts[0],parts[1],Long.parseLong(parts[2]));
            return lease.expired()?null:lease;
        }catch(NumberFormatException e){return null;}
    }
    boolean testLeaseAcquire(String owner,String purpose,long ttlSeconds){
        TestLease held=testLease();
        if(held!=null&&!held.owner().equalsIgnoreCase(owner))return false;
        long ttl=Math.max(30,Math.min(3600,ttlSeconds));
        db.state(LEASE_STATE,owner+"|"+purpose.replace('|','/')+"|"+(System.currentTimeMillis()+ttl*1000L));
        return true;
    }
    boolean testLeaseRelease(String owner){
        TestLease held=testLease();
        if(held!=null&&!held.owner().equalsIgnoreCase(owner))return false;
        db.state(LEASE_STATE,"");
        return true;
    }

    /*  Everything that would make a destructive suite unsafe or unreliable right now, in words.
     *
     *  Deliberately reports rather than resolves. "Somebody is fighting a boss" is a reason to come back
     *  later, never a reason to end their encounter. */
    java.util.List<String> stagingActivity(String requester){
        java.util.List<String> busy=new java.util.ArrayList<>();
        java.util.List<String> others=new java.util.ArrayList<>();
        for(Player online:getServer().getOnlinePlayers())
            if(requester==null||!online.getName().equalsIgnoreCase(requester))others.add(online.getName());
        if(!others.isEmpty())busy.add(others.size()+" other player"+(others.size()==1?"":"s")+" online: "+String.join(", ",others));
        if(colosseum!=null&&colosseum.liveRunCount()>0)
            busy.add(colosseum.liveRunCount()+" Colosseum encounter(s) running");
        if(arena!=null&&arena.liveDuelCount()>0)
            busy.add(arena.liveDuelCount()+" duel(s) in progress");
        return busy;
    }

    /** Returns true when the suite may proceed. Otherwise it has already told the sender why, and nothing
     *  has been touched. */
    boolean testGate(CommandSender sender,String suite){
        String requester=sender.getName();
        TestLease held=testLease();
        if(held!=null&&!held.owner().equalsIgnoreCase(requester)){
            CoreUtil.error(sender,"The staging test lease is held by "+held.owner()+" for "+held.purpose()+".",
                    "It frees itself in "+held.secondsLeft()+"s, or /ashfall lease break to take it.");
            return false;
        }
        java.util.List<String> busy=stagingActivity(requester);
        if(!busy.isEmpty()&&held==null){
            CoreUtil.error(sender,suite+" would not be reliable right now, so it has not run.","Nothing was changed.");
            for(String line:busy)CoreUtil.item(sender,line);
            CoreUtil.hint(sender,"Take the lease first if this is a deliberate test window: /ashfall lease acquire "+suite);
            return false;
        }
        return true;
    }

    private void adminLease(CommandSender sender,String[] args){
        String verb=args.length>1?args[1].toLowerCase(Locale.ROOT):"status";
        TestLease held=testLease();
        switch(verb){
            case"acquire"->{
                /*  An explicit holder, because every RCON caller is CONSOLE: without it the harness and a
                 *  person running a suite from the console are indistinguishable, which is exactly the
                 *  collision this lease exists to stop. */
                String holder=args.length>2?args[2]:sender.getName();
                String purpose=args.length>3?String.join(" ",java.util.Arrays.copyOfRange(args,3,args.length)):"staging tests";
                long ttl=900;
                if(args.length>4)try{ttl=Long.parseLong(args[args.length-1]);purpose=String.join(" ",java.util.Arrays.copyOfRange(args,3,args.length-1));}catch(NumberFormatException ignored){}
                if(!testLeaseAcquire(holder,purpose,ttl)){
                    TestLease other=testLease();
                    CoreUtil.error(sender,"Held by "+(other==null?"somebody":other.owner())+" for "+(other==null?"?":other.purpose())+".",
                            other==null?null:"Free in "+other.secondsLeft()+"s.");
                    return;
                }
                TestLease now=testLease();
                CoreUtil.ok(sender,"Lease held by "+holder+" for "+(now==null?ttl:now.secondsLeft())+"s \u2014 "+purpose+".");
                java.util.List<String> busy=stagingActivity(sender.getName());
                if(!busy.isEmpty()){
                    CoreUtil.warn(sender,"Live activity the suites will not wait for:");
                    for(String line:busy)CoreUtil.item(sender,line);
                }
            }
            case"release"->{
                String holder=args.length>2?args[2]:sender.getName();
                if(!testLeaseRelease(holder)){
                    CoreUtil.error(sender,"That lease belongs to "+(held==null?"nobody":held.owner())+".");return;
                }
                CoreUtil.ok(sender,"Lease released.");
            }
            case"break"->{
                db.state(LEASE_STATE,"");
                db.logAudit(sender.getName(),"TEST_LEASE_BREAK",held==null?"none":held.owner()+" / "+held.purpose());
                CoreUtil.ok(sender,held==null?"There was no lease to break.":"Took the lease from "+held.owner()+".");
            }
            default->{
                if(held==null)CoreUtil.field(sender,"Test lease","free");
                else CoreUtil.field(sender,"Test lease",held.owner()+" \u00b7 "+held.purpose()+" \u00b7 "+held.secondsLeft()+"s left");
                /*  You are identified by the name the server knows you as, which for an RCON caller is
                 *  "Rcon" and not "CONSOLE". Taking the lease under any other name locks you out of your
                 *  own suites, so the name is on the screen rather than left to be discovered. */
                CoreUtil.field(sender,"You are",sender.getName());
                java.util.List<String> busy=stagingActivity(null);
                if(busy.isEmpty())CoreUtil.hint(sender,"Nothing is running; the destructive suites are safe.");
                else for(String line:busy)CoreUtil.item(sender,line);
            }
        }
    }

    private void adminStash(CommandSender sender,String[] args){
        if(args.length<2){CoreUtil.error(sender,"Usage: /ashfall stash <player> [grant <material> <count>]");return;}
        String who=CoreUtil.id(args[1]);
        if(args.length>=4&&args[2].equalsIgnoreCase("fail")){
            /*  Aborts the next delivery at a named persistence boundary, so the crash windows can be
             *  entered on purpose. Operator-gated, audited, and cleared the moment it fires. */
            String point=args[3].toLowerCase(Locale.ROOT);
            if(!java.util.Set.of("before-save","after-save","after-apply","off").contains(point)){
                CoreUtil.error(sender,"Boundaries: before-save, after-save, after-apply, off.");return;
            }
            stashFailPoint="off".equals(point)?null:point;
            db.logAudit(sender.getName(),"STASH_FAIL_POINT",point);
            CoreUtil.ok(sender,stashFailPoint==null?"Failure injection off.":"The next claim delivery will abort at "+point+".");
            return;
        }
        if(args.length>=3&&args[2].equalsIgnoreCase("reconcile")){
            Player target=getServer().getPlayerExact(args[1]);
            if(target==null){CoreUtil.error(sender,"That player is not online.");return;}
            CoreUtil.ok(sender,"Settled "+reconcileStash(target)+" claim receipt(s) for "+target.getName()+".");
            return;
        }
        if(args.length>=3&&args[2].equalsIgnoreCase("receipts")){
            Player target=getServer().getPlayerExact(args[1]);
            if(target==null){CoreUtil.error(sender,"That player is not online.");return;}
            String raw=target.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(this,STASH_RECEIPT_KEY),
                    org.bukkit.persistence.PersistentDataType.STRING);
            CoreUtil.field(sender,"Receipts",raw==null||raw.isBlank()?"none":raw);
            return;
        }
        if(args.length>=5&&args[2].equalsIgnoreCase("grant")){
            org.bukkit.Material material=org.bukkit.Material.matchMaterial(args[3]);
            if(material==null||material.isAir()){CoreUtil.error(sender,"No such item: "+CoreUtil.safe(args[3]));return;}
            int count;
            try{count=Math.max(1,Math.min(material.getMaxStackSize(),Integer.parseInt(args[4])));}
            catch(NumberFormatException e){CoreUtil.error(sender,"That is not a number.");return;}
            db.stashAddItem(who,new org.bukkit.inventory.ItemStack(material,count));
            db.logAudit(sender.getName(),"STASH_GRANT",args[1]+" "+material+" x"+count);
            CoreUtil.ok(sender,"Owed "+args[1]+" "+count+"x "+CoreUtil.pretty(material.name())+".");
            return;
        }
        java.util.List<Database.StashRow> owed=db.stashRows(who);
        CoreUtil.heading(sender,"Claim stash",args[1]+" · "+owed.size()+" waiting");
        if(owed.isEmpty()){CoreUtil.hint(sender,"Nothing is owed to this player.");return;}
        for(Database.StashRow row:owed)
            CoreUtil.item(sender,"#"+row.id()+"  "+row.item().getAmount()+"x "+CoreUtil.pretty(row.item().getType().name()));
        CoreUtil.hint(sender,"Delivered when they use /orders, with room to receive it.");
    }

    /*  WHAT addItem ACTUALLY DOES, pinned.
     *
     *  Every bug in the audit above came from one undocumented behaviour, so it is asserted here rather
     *  than described: exactly enough room, no room, room for part of a stack, room split across slots, and
     *  a round trip through the stash's own serialisation with every kind of item data attached. */
    String inventorySelfTest(){
        try{
            org.bukkit.inventory.Inventory scratch=getServer().createInventory(null,27);

            /*  WHAT addItem ACTUALLY DOES ON THIS PAPER BUILD, asserted rather than assumed.
             *
             *  I asserted in an earlier pass that addItem always writes the remainder back into the stack it
             *  is handed. It does not, and the truth is worse: on this build the argument survives being
             *  PLACED in a free slot, MERGED entirely into a partial stack, and REFUSED outright -- and is
             *  overwritten with the remainder only when the insertion is PARTIALLY accepted.
             *
             *  So the one case that mutates is the one nobody reaches while testing, because it needs an
             *  inventory that is nearly, but not quite, full. That is how the delivery path came to compare
             *  the leftover against a stack that had by then BECOME the leftover -- a partial delivery read
             *  as "nothing fitted", the row was left at full size, and the part that did arrive would have
             *  been handed out again.
             *
             *  The delivery path is written not to care which is true: it hands addItem a clone and reads
             *  only the map it returns. That is the one report that is correct under both contracts. */
            ItemStack placed=new ItemStack(Material.DIAMOND,64);
            if(!scratch.addItem(placed).isEmpty())return "an empty inventory refused a full stack";
            if(placed.getAmount()!=64)return "placing into a free slot changed the caller's stack";
            if(scratch.getItem(0).getAmount()!=64)return "the placed stack did not arrive whole";

            /** Merged entirely into an existing partial stack. */
            scratch.clear();
            scratch.setItem(0,new ItemStack(Material.DIAMOND,40));
            ItemStack merged=new ItemStack(Material.DIAMOND,20);
            if(!scratch.addItem(merged).isEmpty())return "a stack that fits by merging was refused";
            if(scratch.getItem(0).getAmount()!=60)return "the merge did not land in the partial stack";
            if(merged.getAmount()!=20)return "merging changed the caller's stack";

            /** No room at all: the map reports the whole amount back. */
            scratch.clear();
            for(int slot=0;slot<27;slot++)scratch.setItem(slot,new ItemStack(Material.STONE,64));
            ItemStack refused=new ItemStack(Material.DIAMOND,16);
            java.util.Map<Integer,ItemStack> back=scratch.addItem(refused);
            if(back.size()!=1||back.values().iterator().next().getAmount()!=16)return "a full inventory did not report the whole stack back";
            if(refused.getAmount()!=16)return "a refused stack was modified";

            /** Room for only part of it: the map reports exactly the remainder. */
            scratch.clear();
            for(int slot=0;slot<26;slot++)scratch.setItem(slot,new ItemStack(Material.STONE,64));
            scratch.setItem(26,new ItemStack(Material.DIAMOND,60));
            ItemStack partial=new ItemStack(Material.DIAMOND,10);
            java.util.Map<Integer,ItemStack> rest=scratch.addItem(partial);
            if(rest.size()!=1||rest.values().iterator().next().getAmount()!=6)return "a partial fit did not report the exact remainder";
            if(scratch.getItem(26).getAmount()!=64)return "a partial fit did not fill the slot it topped up";
            /*  THE ONE THAT MUTATES. Asserted in the direction it actually goes, so a future Paper that
             *  stops doing this fails here loudly instead of quietly changing what callers can rely on. */
            if(partial.getAmount()!=6)return "a partial fit no longer rewrites the caller's stack (contract changed)";

            /** Split across several slots: partials topped up first, then a free slot. */
            scratch.clear();
            scratch.setItem(0,new ItemStack(Material.DIAMOND,60));
            scratch.setItem(1,new ItemStack(Material.DIAMOND,60));
            ItemStack wide=new ItemStack(Material.DIAMOND,64);
            if(!scratch.addItem(wide).isEmpty())return "a stack that fits across three slots was refused";
            if(scratch.getItem(0).getAmount()!=64||scratch.getItem(1).getAmount()!=64||scratch.getItem(2).getAmount()!=56)
                return "a split insertion did not distribute correctly";
            /** Two partial merges then a placement, so the argument carries the tail that was placed. */
            if(wide.getAmount()!=56)return "a split insertion left the caller's stack in an unexpected state";

            /** The stack sizes a claim actually carries. */
            for(int size:new int[]{64,16,1}){
                scratch.clear();
                ItemStack one=new ItemStack(Material.COOKED_BEEF,size);
                if(!scratch.addItem(one).isEmpty())return "an empty inventory refused a stack of "+size;
                if(scratch.getItem(0).getAmount()!=size)return "a stack of "+size+" did not arrive whole";
            }

            /*  And a claim keeps every piece of item data across the stash's own serialisation, which is
             *  what a claim row physically is. */
            ItemStack fancy=new ItemStack(Material.DIAMOND_SWORD);
            org.bukkit.inventory.meta.ItemMeta meta=fancy.getItemMeta();
            meta.displayName(net.kyori.adventure.text.Component.text("Test Blade"));
            meta.lore(java.util.List.of(net.kyori.adventure.text.Component.text("a line of lore")));
            meta.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS,4,true);
            ((org.bukkit.inventory.meta.Damageable)meta).setDamage(37);
            meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(this,"selftest_marker"),
                    org.bukkit.persistence.PersistentDataType.STRING,"kept");
            fancy.setItemMeta(meta);
            ItemStack[] round=ItemStack.deserializeItemsFromBytes(ItemStack.serializeItemsAsBytes(new ItemStack[]{fancy}));
            if(round.length!=1||round[0]==null)return "a serialised claim came back as nothing";
            if(!round[0].isSimilar(fancy))return "a serialised claim came back as a different item";
            org.bukkit.inventory.meta.ItemMeta kept=round[0].getItemMeta();
            if(kept.getEnchantLevel(org.bukkit.enchantments.Enchantment.SHARPNESS)!=4)return "an enchantment was lost in the claim stash";
            if(((org.bukkit.inventory.meta.Damageable)kept).getDamage()!=37)return "durability was lost in the claim stash";
            if(!"kept".equals(kept.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(this,"selftest_marker"),
                    org.bukkit.persistence.PersistentDataType.STRING)))return "PDC data was lost in the claim stash";
            return null;
        }catch(Throwable failure){
            getLogger().warning("inventory self test could not run: "+failure);
            return "threw "+failure;
        }
    }

    private void selfTest(CommandSender s){CoreUtil.msg(s,"Running non-destructive migration and persistence tests...");for(String result:db.selfTest())CoreUtil.msg(s,result);List<Integer> sizes=getConfig().getIntegerList("claims.sizes"),costs=getConfig().getIntegerList("claims.expansion-costs");boolean ok=sizes.size()==6&&costs.size()==5&&CoreUtil.compact(2590).length()<=5&&getConfig().getDouble("merchants.shop.buy-multiplier",1)<1&&getConfig().getDouble("merchants.shop.sell-multiplier",1)>1&&getConfig().getDouble("mob-money.minimum-multiplier",0)>.0&&getConfig().getDouble("spawner-breaking.money-reward",0)==25&&getConfig().getInt("spawner-breaking.exp-max",0)>=getConfig().getInt("spawner-breaking.exp-min",1)&&getConfig().getInt("auctions.max-active-per-player",0)==30&&getConfig().getDouble("bank.loans.daily-interest-percent",0)>0&&getConfig().getDouble("bank.loans.overdue-garnish-percent",0)>0&&getConfig().getDouble("bank.loans.maximum-limit",-1)==0&&getConfig().getInt("homes.personal.upgrades.10",0)==50000000&&getConfig().getLong("graves.lifetime-hours",0)==48&&getConfig().getDouble("performance.world-borders.sizes.overworld",0)==225000&&getConfig().getDouble("performance.world-borders.sizes.nether",0)==57000&&getConfig().getDouble("performance.world-borders.sizes.end",0)==175000&&getConfig().getDouble("progression.vanguard-economic-target",0)==250000&&getConfig().getDouble("pay.tax-percent",-1)>=0&&getConfig().getDouble("progression.rank-rewards.VANGUARD",0)==250000;for(int i=1;i<sizes.size();i++)ok&=sizes.get(i)>sizes.get(i-1);for(int i=1;i<costs.size();i++)ok&=costs.get(i)>costs.get(i-1);CoreUtil.msg(s,"Claim/economy/bank/auction/home/border configuration: "+(ok?"ok":"FAILED"));String inventoryResult=inventorySelfTest();CoreUtil.msg(s,"Inventory insertion semantics (exact/none/partial/split/64,16,1) and claim item fidelity: "+(inventoryResult==null?"ok":"FAILED at "+inventoryResult));CoreUtil.msg(s,"Money parser, smart combat links and guide selection: "+(CoreUtil.moneyParserSelfTest()&&teleports.combatSelfTest()&&guides.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Chat combining-mark (zalgo) sanitization: "+(CoreUtil.combiningMarkSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Seven-rank requirement progression: "+(progress.rankSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Shop, Dragon Egg and Villager Capsule checks: "+(shop.selfTest()&&capsules.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Raw/cooked crafting-tax band (10-15%): "+(shop.craftingTaxSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Bow recipe pricing and no-profit-loop: "+(shop.bowRecipeSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Damaged-gear opt-in (enchanted bows refused): "+(shop.damagedOptInSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Stacked-mob conservation (money, items, XP, split): "+(ShopService.bulkSelfTest()&&SpawnerService.bulkPlanSelfTest()&&spawners.bulkSplitSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Void event world naming and sanitisation: "+(voidWorlds.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Stacked/recovery spawner checks: "+(spawners.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Shared boss participant scaling/health-percent math: "+(bosses.scalingSelfTest()?"ok":"FAILED")); CoreUtil.msg(s,"Boss reward split (single participant takes the whole pool): "+(bosses.rewardSplitSelfTest()?"ok":"FAILED")); CoreUtil.msg(s,"Celebration particle data and durations: "+(spectacle.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Boss/elite health-safety clamp: "+(bosses.bossHealthSafetySelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"World-boss rebalance/soft-enrage configuration: "+(bosses.worldBossRebalanceSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Epic/Legendary rarity, scaling and phase configuration: "+(bosses.eliteTierSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Active-play event tiers/protected buffer/effect sanitation: "+(bosses.eventTimingSelfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Marketplace, settings, shards and weekly Dragon: "+(marketplace.selfTest()&&settings.selfTest()&&shards.selfTest()&&weeklyDragon.selfTest()&&relics.upgradeSelfTest()&&taskMaster.selfTest()&&industrialHoppers.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Discarded-item vault eligibility guards: "+(vault.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Orders identity, catalogue and spawner typing: "+(ordersService.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Arena kit parity, three-stage setup and pari-mutuel arithmetic: "+(arena.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Duel map registry, break rules, spawn facing and trial-key restriction: "+(duelMaps.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Spawner Shop pricing order, rounding and deficit surcharge: "+(spawnerShop.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Duel template snapshots committed: "+duelMapSnapshotStatus());CoreUtil.msg(s,"Colosseum arenas, boss identities, economy and daily cap: "+(colosseum.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Colosseum arena snapshots committed: "+colosseumSnapshotStatus());CoreUtil.msg(s,"Live bulletin configuration: "+(bulletin.selfTest()?"ok":"FAILED"));CoreUtil.msg(s,"Punishment tier configuration: "+(punishments.selfTest()?"ok":"FAILED"));String old=db.state("selftest_1_7_0_restart");db.state("selftest_1_7_0_restart",Long.toString(System.currentTimeMillis()));CoreUtil.msg(s,"1.7.0 restart marker: "+(old==null?"created; run after restart":"read previous value successfully"));}

    /** /duel <player|accept|decline|kit|series|stake|confirm|bet|watch|status|cancel> */
    /** /duels -- where each piece of a duel kit sits when the match starts. A standing preference, so it
     *  is deliberately reachable outside a match; /duels <kit> jumps straight to one. */
    private boolean duels(Player p,String[] args){
        if(args.length>0){
            try{arena.openKitLayout(p,ArenaService.Kit.valueOf(args[0].toUpperCase(Locale.ROOT)));return true;}
            catch(IllegalArgumentException unknown){CoreUtil.error(p,"Kits: mace, sword, axe, spear.");return true;}
        }
        arena.openKitLayoutPicker(p);
        return true;
    }

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
        /** Only the verbs this sender may actually run, and only the worlds that actually exist. */
        if(name.equals("voidworld")){
            if(args.length==1)return filter(args[0],voidVerbs(sender));
            if(args.length==2&&List.of("enter","delete","open","close").contains(args[0].toLowerCase(Locale.ROOT)))
                return filter(args[1],voidWorlds.labels());
            return List.of();
        }
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
        if(name.equals("duels")&&args.length==1)return filter(args[0],List.of("mace","sword","axe","spear"));
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
        /** /ashfall colosseum ... completes from the LIVE registry and world list, exactly like duel maps.
         *  The whole /ashfall tree is already gated above, so being here means the sender may run these. */
        if(name.equals("ashfall")&&args.length>=2&&args[0].equalsIgnoreCase("colosseum")){
            if(colosseum==null)return List.of();
            if(args.length==2)return filter(args[1],COLOSSEUM_SUBS);
            String sub=args[1].toLowerCase(Locale.ROOT);
            if(args.length==3)return switch(sub){
                case"create","enter","save","setspawn","setboss"->filter(args[2],colosseum.arenas().arenaKeys());
                case"test"->filter(args[2],colosseum.completableBosses(true));
                case"drop"->filter(args[2],colosseum.arenas().instanceNames());
                case"bench"->filter(args[2],List.of("1","2","3","4"));
                default->List.of();
            };
            if(args.length==4&&sub.equals("setspawn"))return filter(args[3],List.of("player","boss","spectator"));
            if(args.length==4&&sub.equals("setboss"))return filter(args[3],colosseum.completableBosses(true));
            if(args.length==4&&sub.equals("bench"))return filter(args[3],List.of("10","20","30","60"));
            return List.of();
        }
        if(name.equals("ashfall")&&args.length==2&&args[0].equalsIgnoreCase("hopper"))
            return filter(args[1],List.of("verify","rig","count","create"));
        if(name.equals("colosseum")&&args.length==2){
            String verb=args[0].toLowerCase(Locale.ROOT);
            if(verb.equals("stats"))return publicOnlineNames(sender,args[1]);
            if(verb.equals("top"))return filter(args[1],colosseum.completableBosses(true));
            return List.of();
        }
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
        if(args.length==1)return switch(name){case"f"->{List<String> options=new ArrayList<>(List.of("create","claim","unclaim","borders","networth","leaderboard","relations","ally","truce","storage","invite","accept","kick","leader","coleader","leave","disband","info","tag","deposit","withdraw","expand","sethome","home","homes","delhome","buyhome","history","locate"));options.addAll(publicOnlineNames(sender,""));yield filter(args[0],options);}case"shop"->filter(args[0],List.of("luxury","buy","sell","sellall"));case"settings"->filter(args[0],List.of("account","confirmations"));case"ah"->filter(args[0],List.of("sell","collect","cancel"));case"enderchest"->filter(args[0],sender instanceof Player viewer&&isAdmin(viewer)?List.of("upgrade","page","inspect"):List.of("upgrade","page"));case"events"->filter(args[0],List.of("track"));case"guide","rules"->filter(args[0],List.of("English","العربية"));case"leaderboards"->filter(args[0],List.of("money","networth","factions","bosses","kills","deaths","mobs","bounties","events","playtime"));case"relics"->filter(args[0],List.of("trace"));case"colosseum"->{List<String> options=new ArrayList<>(List.of("stats","top","leave","list"));options.addAll(colosseum.completableBosses(sender instanceof Player cp&&isAdmin(cp)));yield filter(args[0],options);}case"ashfall"->filter(args[0],List.of("help","balance","economy","bank","boss","elite","event","merchant","bulletin","feedback","faction","spawnclaim","relic","grave","border","setspawn","reload","debug","selftest","vanish","spectate","unspectate","audit","shard","progressrepair","cooldowns","bounty","dragon","replay","chatlog","dmlog","factionchatlog","lastloc","homes","factioninfo","ipban","monument","moderation","vault","hopper","duelmap","colosseum","spawnershop","voidworld"));case"nickname"->filter(args[0],List.of("random","off"));default->List.of();};
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
                case"help"->filter(args[1],List.of("economy","events","factions","merchants","feedback","relics","arenas","colosseum","duelmap","voidworld","maintenance"));
                case"balance"->filter(args[1],List.of("set","add","take"));
                case"bank"->filter(args[1],List.of("add","remove","set"));
                case"boss"->filter(args[1],List.of("spawn","here","despawn"));
                case"elite"->filter(args[1],List.of("stats","uncommon","rare","epic","legendary","miniboss"));
                case"event"->filter(args[1],List.of("resource","elitehunt","taskmaster","worldboss","stop"));
                case"lease"->filter(args[1],List.of("status","acquire","release","break"));
                case"stash"->filter(args[1],getServer().getOnlinePlayers().stream().map(Player::getName).toList());
                case"voidworld"->filter(args[1],List.of("create","enter","exit","list","delete","open","close","verify"));
                case"hopper"->filter(args[1],List.of("verify","livesuite","live","watch","create","rig","count"));
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
                case"colosseum"->filter(args[1],COLOSSEUM_SUBS);
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
        /** enter and delete operate on worlds that already exist, so they suggest the real ones. */
        if(name.equals("ashfall")&&args[0].equalsIgnoreCase("voidworld")&&args.length==3
                &&List.of("enter","delete","open","close").contains(args[1].toLowerCase(Locale.ROOT)))
            return filter(args[2],voidWorlds.labels());
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
