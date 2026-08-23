package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;

final class RelicService implements Listener {
    private final SMPCore plugin;private final Database db;private final NamespacedKey key;private YamlConfiguration config;private BukkitTask lifecycleTask,buffTask,anchorTask;

    /** One armed Skyward Anchor. `peak` is OUR OWN fall tracking rather than Player#getFallDistance,
     *  because vanilla zeroes that constantly while gliding and we need the drop to survive an elytra
     *  descent (and to be deliberately reset when the dive flattens out). */
    private static final class Anchor {
        double peak;
        final long armedAt;
        /** Has the holder actually LEFT the ground since arming?
         *
         *  Without this the relic killed itself on the tick after the right-click: Player#isOnGround reflects
         *  the last movement packet the client sent, so it is still true for a tick or two after a launch,
         *  the ground-contact branch fired immediately, and the anchor discharged into thin air with a fall
         *  distance of zero. Damage was therefore 0.9 x 7 x 1.5 = about 9 -- which is exactly the "it does no
         *  base damage" and "the buff doesn't seem active at all" that was reported, and it is also why the
         *  mace combo never triggered: by the time you swung, there was nothing armed left to combo with. */
        boolean airborne;
        Anchor(double peak){this.peak=peak;this.armedAt=System.currentTimeMillis();}
    }
    private final Map<UUID,Anchor> anchors=new java.util.concurrent.ConcurrentHashMap<>();
    /** Player -> tick deadline for the post-slam invulnerability window. */
    private final Map<UUID,Long> slamGuard=new java.util.concurrent.ConcurrentHashMap<>();
    /** Player -> deadline for "the fall you are in right now was caused by a wind burst".
     *
     *  A six-block-per-target launch happily reaches twenty-plus blocks, and landing from that unaided is
     *  most of a health bar -- the relic would routinely kill its own user. This is also what was asked for
     *  in so many words: a wind burst should not hand you the full fall back. */
    private final Map<UUID,Long> burstGrace=new java.util.concurrent.ConcurrentHashMap<>();
    RelicService(SMPCore plugin){this.plugin=plugin;this.db=plugin.db();this.key=new NamespacedKey(plugin,"relic");reload();lifecycleTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::lifecycleTick,1200L,12000L);buffTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::buffTick,20L,40L);anchorTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::anchorTick,1L,1L);}
    /** Test-only hook for /admin relictest — runs the real periodic lifecycle pass immediately instead of
     *  waiting up to 10 minutes for the next scheduled one. Not used by any normal game logic. */
    void debugForceLifecycleTick(){lifecycleTick();}
    void shutdown(){if(lifecycleTask!=null)lifecycleTask.cancel();if(buffTask!=null)buffTask.cancel();if(anchorTask!=null)anchorTask.cancel();anchors.clear();slamGuard.clear();burstGrace.clear();}
    void reload(){config=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"relics.yml"));}
    Set<String> keys(){ConfigurationSection section=config.getConfigurationSection("relics");return section==null?Set.of():section.getKeys(false);}
    String displayName(String relicKey){return config.getString("relics."+relicKey+".name",CoreUtil.pretty(relicKey));}
    ItemStack create(String relicKey){String path="relics."+relicKey;Material material=Material.matchMaterial(config.getString(path+".material","PAPER"));if(material==null)material=Material.PAPER;ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(displayName(relicKey),NamedTextColor.GOLD));List<Component> lore=new ArrayList<>();for(String line:config.getStringList(path+".lore"))lore.add(Component.text(line,NamedTextColor.GRAY));lore.add(Component.empty());lore.add(Component.text("Unique Relic • "+relicKey,NamedTextColor.DARK_PURPLE));meta.lore(lore);meta.getPersistentDataContainer().set(key,PersistentDataType.STRING,relicKey);item.setItemMeta(meta);if(relicKey.equals("crown_of_ash")){item.addUnsafeEnchantment(Enchantment.PROTECTION,4);item.addUnsafeEnchantment(Enchantment.FIRE_PROTECTION,4);item.addUnsafeEnchantment(Enchantment.UNBREAKING,3);}if(relicKey.equals("wayfinder"))item.addUnsafeEnchantment(Enchantment.UNBREAKING,1);if(relicKey.equals("oathblade")){item.addUnsafeEnchantment(Enchantment.SHARPNESS,5);item.addUnsafeEnchantment(Enchantment.LOOTING,2);item.addUnsafeEnchantment(Enchantment.UNBREAKING,3);}return item;}
    String keyOf(ItemStack item){if(item==null||!item.hasItemMeta())return null;return item.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING);}
    boolean isActive(String relicKey){Database.RelicLifecycleRow row=db.relicLifecycle(relicKey);return row!=null&&row.active()&&"ACTIVE".equals(row.status());}
    /** Historical ownership must never satisfy the Sovereign rank or the chat/TAB symbol — only a relic whose
     *  lifecycle row is currently ACTIVE and currently owned by this exact player counts. Lost, destroyed,
     *  resurfacing, or previously-owned relics all fall through to false here. */
    boolean ownsActiveRelic(String playerId){
        if(playerId==null)return false;
        for(String relicKey:keys()){Database.RelicLifecycleRow row=db.relicLifecycle(relicKey);if(row!=null&&"ACTIVE".equals(row.status())&&playerId.equals(row.owner()))return true;}
        return false;
    }
    private boolean canMint(String relicKey){Database.RelicLifecycleRow row=db.relicLifecycle(relicKey);return row==null||"ELIGIBLE".equals(row.status());}
    boolean mint(String relicKey,String owner,String ownerName){if(!keys().contains(relicKey)||!canMint(relicKey))return false;db.registerRelic(relicKey,owner,ownerName);return true;}
    /** Admin-only entry point (the only caller is /ashfall relic give / /smp relic give) - deliberately does
     *  NOT go through mint()/canMint(), which only allows ELIGIBLE and exists to gate organic discovery
     *  (hideInLoot()). An admin handing out a relic should work regardless of whether it's currently LOST,
     *  RETIRED, or has never existed - only a genuinely ACTIVE copy (someone already holding it) should
     *  refuse, since that's the one case that would create a real duplicate. registerRelic() is a single
     *  atomic UPSERT that already does exactly "cancel any pending lost/recycling state, set ACTIVE, mark
     *  this owner" in one statement - the read-then-write here has no real race window since admin commands
     *  only ever run synchronously on the main thread. */
    boolean give(Player player,String relicKey){
        if(!keys().contains(relicKey))return false;
        Database.RelicLifecycleRow row=db.relicLifecycle(relicKey);
        if(row!=null&&"ACTIVE".equals(row.status()))return false;
        db.registerRelic(relicKey,CoreUtil.id(player),player.getName());
        CoreUtil.give(player,create(relicKey));
        discovery(player,relicKey,"found");
        return true;
    }
    /** Admin override for the resurface wait - ends a LOST relic's timer immediately, reaching the exact
     *  same ELIGIBLE end state lifecycleTick() would arrive at on its own once eligible_at passes. */
    boolean forceEligible(String relicKey){
        Database.RelicLifecycleRow row=db.relicLifecycle(relicKey);
        if(row==null||!"LOST".equals(row.status()))return false;
        db.makeRelicEligible(relicKey);
        plugin.getServer().broadcast(Component.text("Rumors speak of "+displayName(relicKey)+" resurfacing somewhere in Ashfall...",NamedTextColor.LIGHT_PURPLE));
        db.history("SERVER",null,"RELIC",displayName(relicKey)+" was forced to become eligible to resurface by an admin.");
        return true;
    }
    void discover(Player player,ItemStack item){
        String relicKey=keyOf(item);if(relicKey==null)return;Database.RelicLifecycleRow row=db.relicLifecycle(relicKey);if(row==null)return;
        if("hidden".equals(row.owner())||"ELIGIBLE".equals(row.status())||!row.active()){db.confirmRelic(relicKey,CoreUtil.id(player),player.getName());discovery(player,relicKey,"uncovered");}
        else if(row.owner().equals(CoreUtil.id(player)))db.confirmRelic(relicKey,CoreUtil.id(player),player.getName());
        else{db.confirmRelic(relicKey,CoreUtil.id(player),player.getName());discovery(player,relicKey,"claimed");}
    }
    /** Called by AuctionService.buy() when a sold listing happens to contain a relic — a sale is exactly
     *  as legitimate an ownership transfer as picking one up via discover(), so it goes through the same
     *  confirmRelic() ownership+status+timer reset, keeping the single tracked copy pointed at whoever
     *  now actually holds it (and resetting the reclaim clock, since the buyer just logged an activity). */
    void transferOnSale(ItemStack item,Player buyer){
        String relicKey=keyOf(item);if(relicKey==null)return;
        if(db.relicLifecycle(relicKey)==null)return;
        db.confirmRelic(relicKey,CoreUtil.id(buyer),buyer.getName());
        discovery(buyer,relicKey,"purchased");
    }
    /** Admin diagnostic and on-demand trigger for the same duplicate guard confirmInventory() already
     *  runs automatically (every 10-minute lifecycle tick, and on every join/quit) — reports exactly
     *  which online players currently hold a physical copy and whether each matches the tracked owner,
     *  then runs the same check immediately instead of waiting. Prepared specifically for a known,
     *  already-diagnosed duplicate (e.g. an extra Colossus Core) so the cleanup at deploy time is a
     *  deliberate, visible, audited action rather than a silent background side effect. Only inspects
     *  ONLINE players — an offline player's saved inventory can't be edited directly; their stale copy
     *  is caught automatically the moment they next log in. */
    void reconcile(CommandSender sender,String relicKey){
        if(!keys().contains(relicKey)){CoreUtil.error(sender,"Unknown relic key. Use /relics for the list.");return;}
        Database.RelicLifecycleRow row=db.relicLifecycle(relicKey);
        if(row==null){CoreUtil.error(sender,"No lifecycle record for "+relicKey+" yet.");return;}
        CoreUtil.msg(sender,"Reconciling "+displayName(relicKey)+" — tracked canonical owner: "+row.ownerName()+" ["+row.status()+"]");
        int found=0;
        for(Player player:plugin.getServer().getOnlinePlayers()){
            boolean holds=hasRelic(player.getInventory().getContents(),relicKey)||hasRelic(plugin.enderChests().allContents(player),relicKey);
            if(!holds)continue;
            found++;
            boolean canonical="ACTIVE".equals(row.status())&&CoreUtil.id(player).equals(row.owner());
            CoreUtil.msg(sender,"  "+player.getName()+" holds a physical copy — "+(canonical?"matches the tracked owner, kept.":"does NOT match the tracked owner ("+row.ownerName()+", status "+row.status()+") — will be removed below."));
        }
        if(found==0){CoreUtil.msg(sender,"No online player currently holds a physical copy in inventory or Ender Storage.");return;}
        CoreUtil.msg(sender,"Running an immediate check now (same logic as the periodic sweep and every join/quit)...");
        for(Player player:plugin.getServer().getOnlinePlayers())confirmInventory(player);
        db.logAudit(sender instanceof Player p?p.getName():"CONSOLE","RELIC_RECONCILE","relic="+relicKey+" tracked_owner="+row.ownerName()+" online_holders="+found);
        CoreUtil.msg(sender,"Done — re-run this command to confirm the result. Every removal was logged to console and /ashfall audit.");
    }
    @EventHandler public void despawn(org.bukkit.event.entity.ItemDespawnEvent event){itemLost(event.getEntity());}

    /** Relics must live only in an active player inventory — dropping, looting from a PvP grave, direct
     *  trading, and auctioning are all still fine (none of those route through a vanilla-typed container
     *  GUI or SMPCore's own Ender Storage pages, so none of these three handlers touch them at all). Only
     *  Ender Storage, chests/barrels/shulkers/furnaces/hoppers/etc, and faction storage (which is just those
     *  same vanilla container types placed in claimed territory) are blocked. */
    private boolean isPersistentStorage(Inventory inventory){
        if(inventory==null)return false;
        /** The duel item-wager box is not storage -- relics staked there are escrowed and go to the winner. */
        if(plugin.arena()!=null&&plugin.arena().isWagerBox(inventory))return false;
        InventoryType type=inventory.getType();
        if(type==InventoryType.CHEST||type==InventoryType.ENDER_CHEST||type==InventoryType.SHULKER_BOX||type==InventoryType.BARREL
                ||type==InventoryType.DISPENSER||type==InventoryType.DROPPER||type==InventoryType.HOPPER
                ||type==InventoryType.FURNACE||type==InventoryType.BLAST_FURNACE||type==InventoryType.SMOKER||type==InventoryType.BREWING)
            return true;
        return plugin.enderChests().isEnderChestStorage(inventory);
    }
    @EventHandler(priority=EventPriority.HIGH) public void guardStorageClick(InventoryClickEvent event){
        if(!(event.getWhoClicked() instanceof Player player))return;
        Inventory top=event.getView().getTopInventory();
        if(!isPersistentStorage(top))return;
        int topSize=top.getSize();
        boolean intoStorage=event.getRawSlot()<topSize&&keyOf(event.getCursor())!=null;
        boolean shiftedIntoStorage=event.getClick().isShiftClick()&&event.getRawSlot()>=topSize&&keyOf(event.getCurrentItem())!=null;
        if(intoStorage||shiftedIntoStorage){event.setCancelled(true);CoreUtil.error(player,"Relics cannot be stored — carry, drop, trade, or auction them instead.");}
    }
    @EventHandler(priority=EventPriority.HIGH) public void guardStorageDrag(InventoryDragEvent event){
        if(!(event.getWhoClicked() instanceof Player player))return;
        if(keyOf(event.getOldCursor())==null)return;
        Inventory top=event.getView().getTopInventory();
        if(!isPersistentStorage(top))return;
        int topSize=top.getSize();
        for(int slot:event.getRawSlots())if(slot<topSize){event.setCancelled(true);CoreUtil.error(player,"Relics cannot be stored — carry, drop, trade, or auction them instead.");return;}
    }
    @EventHandler public void guardHopperTransfer(InventoryMoveItemEvent event){if(keyOf(event.getItem())!=null)event.setCancelled(true);}
    /** InventoryMoveItemEvent above only covers container-to-container movement — a hopper (or hopper
     *  minecart) vacuuming a dropped item entity off the ground fires InventoryPickupItemEvent instead, which
     *  nothing was listening for. That's how a relic could still physically end up inside a hopper despite
     *  the storage ban: drop it on top of one. Cancelling here means the relic never enters the hopper at
     *  all rather than being pulled in and stripped afterwards. */
    @EventHandler(ignoreCancelled=true) public void guardHopperPickup(org.bukkit.event.inventory.InventoryPickupItemEvent event){
        if(keyOf(event.getItem().getItemStack())!=null)event.setCancelled(true);
    }
    /** AxTrade (and any other GUI-mediated hand-off) moves a relic between two online players without ever
     *  firing a pickup event, so ownership stayed pointed at the giver until something else happened to
     *  re-scan — the reason a traded Warlord's Ember only registered after being dropped and re-claimed.
     *  Re-scanning both sides one tick after ANY inventory closes catches every GUI transfer generically,
     *  with no compile-time dependency on a specific trade plugin. */
    @EventHandler public void reconcileOnInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event){
        if(!(event.getPlayer() instanceof Player player))return;
        plugin.getServer().getScheduler().runTask(plugin,()->{if(player.isOnline())confirmInventory(player);});
    }
    /** Tasteful, live-only chat marker — gated on the actual Sovereign of Ashfall rank (which itself requires
     *  every prior rank plus current relic ownership), not on relic ownership alone. Owning a relic without
     *  having earned the rank must never show the symbol. Prepends rather than replaces the format string so it
     *  composes safely with whatever the server's base chat format already is. */
    @EventHandler(ignoreCancelled=true) public void relicChatSymbol(AsyncPlayerChatEvent event){
        if(!plugin.progress().sovereign(CoreUtil.id(event.getPlayer())))return;
        event.setFormat("§d◆ §r"+event.getFormat());
    }
    private void discovery(Player player,String relicKey,String verb){plugin.getServer().broadcast(Component.text("✦ RELIC DISCOVERED ",NamedTextColor.LIGHT_PURPLE).append(Component.text(plugin.nicknames().displayName(player)+" "+verb+" "+displayName(relicKey)+".",NamedTextColor.GOLD)));plugin.progress().relicFound(player,displayName(relicKey));}
    /** Refreshes the tracked owner's bookkeeping when they hold their own relic. Anything else — a
     *  different online player holding a copy of a relic recorded ACTIVE to someone else, or ANY online
     *  player holding a copy of a relic that isn't currently ACTIVE at all (a stale leftover from a
     *  reclaim that happened while they were offline — an offline player's saved inventory can't be
     *  edited directly, so the strip is deferred to whenever that copy is next seen) — means a second
     *  physical copy exists. Removing it here, the moment it's ever seen again, is what makes "only one
     *  tracked copy" an actual invariant instead of a hope; silently reassigning ownership to whoever's
     *  scan happened to run last (the old behavior) is exactly how the Colossus Core duplication went
     *  unnoticed for days. */
    void confirmInventory(Player player){
        String playerId=CoreUtil.id(player);
        for(ItemStack item:player.getInventory().getContents()){
            String relicKey=keyOf(item);if(relicKey==null)continue;
            Database.RelicLifecycleRow row=db.relicLifecycle(relicKey);if(row==null)continue;
            if("ACTIVE".equals(row.status())&&playerId.equals(row.owner())){db.confirmRelic(relicKey,playerId,player.getName());continue;}
            if("ACTIVE".equals(row.status())){
                /** Someone other than the tracked owner is physically holding an ACTIVE relic. That's either a
                 *  legitimate hand-off (trade/gift/kill-loot — the item genuinely moved and the old owner no
                 *  longer has one) or a real duplicate (both copies exist at once). Blindly stripping, as this
                 *  did before, deleted legitimately traded relics; blindly reassigning — the behavior before
                 *  that — is what let the Colossus Core duplication go unnoticed. Deciding on whether the
                 *  tracked owner STILL has a copy distinguishes the two cases exactly. */
                switch(ownerCopyState(row)){
                    case PRESENT -> {
                        item.setAmount(0);
                        plugin.getLogger().warning("[RelicDuplicateGuard] Removed a duplicate "+relicKey+" from "+player.getName()+" — the tracked owner ("+row.ownerName()+") still holds the real one.");
                        db.history("SERVER",null,"RELIC",displayName(relicKey)+": a duplicate physical copy was removed from "+player.getName()+" (tracked owner "+row.ownerName()+" still holds the genuine one).");
                    }
                    case ABSENT -> {
                        db.confirmRelic(relicKey,playerId,player.getName());
                        plugin.getLogger().info("[RelicLifecycle] "+relicKey+" changed hands: "+row.ownerName()+" no longer holds it and "+player.getName()+" does — ownership transferred.");
                        db.history("SERVER",null,"RELIC",displayName(relicKey)+" changed hands from "+row.ownerName()+" to "+plugin.nicknames().displayName(player)+".");
                    }
                    /** Owner offline: their inventory genuinely can't be inspected, so "duplicate" and
                     *  "traded away just before logging off" are indistinguishable right now. Deleting a
                     *  possibly-real item is far worse than a temporarily stale owner field, so this defers
                     *  rather than guessing — it resolves itself the moment the tracked owner logs back in. */
                    case UNKNOWN -> plugin.getLogger().info("[RelicLifecycle] "+player.getName()+" holds "+relicKey+" tracked to the currently-offline "+row.ownerName()+"; deferring until that owner is online and it can be told apart from a duplicate.");
                }
                continue;
            }
            item.setAmount(0);
            plugin.getLogger().warning("[RelicDuplicateGuard] Removed a stray "+relicKey+" from "+player.getName()+" (tracked status="+row.status()+", tracked owner="+row.ownerName()+") — did not match the single tracked copy.");
            db.history("SERVER",null,"RELIC",displayName(relicKey)+": a duplicate/stale physical copy was removed from "+player.getName()+" during a routine check (tracked owner: "+row.ownerName()+", status: "+row.status()+").");
        }
    }
    private enum CopyState { PRESENT, ABSENT, UNKNOWN }
    /** Whether the tracked owner still demonstrably has their copy. Auction escrow counts as present (the
     *  item really is there); an offline owner is UNKNOWN rather than ABSENT, since their saved inventory
     *  isn't inspectable. */
    private CopyState ownerCopyState(Database.RelicLifecycleRow row){
        String owner=row.owner(),relicKey=row.key();
        if("hidden".equals(owner))return CopyState.ABSENT;
        if(activeAuctionFor(owner,relicKey)!=null||expiredAuctionFor(owner,relicKey)!=null)return CopyState.PRESENT;
        Player online=onlineById(owner);
        if(online==null)return CopyState.UNKNOWN;
        if(hasRelic(online.getInventory().getContents(),relicKey)||hasRelic(plugin.enderChests().allContents(online),relicKey))return CopyState.PRESENT;
        return CopyState.ABSENT;
    }
    private Player onlineById(String playerId){for(Player p:plugin.getServer().getOnlinePlayers())if(CoreUtil.id(p).equals(playerId))return p;return null;}
    boolean hideInLoot(List<ItemStack> loot){if(Math.random()>config.getDouble("lifecycle.exploration-chance",.0002))return false;for(String relicKey:List.of("wayfinder","oathblade")){if(mint(relicKey,"hidden","Undiscovered")){loot.add(create(relicKey));return true;}}return false;}
    void itemLost(Item item){itemLostByKey(keyOf(item.getItemStack()));}
    void itemLostByKey(String relicKey){
        if(relicKey==null||!isActive(relicKey))return;
        long delay=config.getLong("lifecycle.resurface-after-real-days",2)*86400000L;
        db.markRelicLost(relicKey,System.currentTimeMillis()+delay);
        plugin.getServer().broadcast(Component.text("The "+displayName(relicKey)+" has been lost to history...",NamedTextColor.DARK_PURPLE));
        plugin.progress().relicLost(displayName(relicKey));
    }
    /** Escrow-aware location check backing the reclaim timer and trace(): an active auction listing is a
     *  fully valid, tracked location — the item physically exists there, just not in Bukkit inventory —
     *  and an expired-but-uncollected one still counts as "in the seller's expired-items storage" until
     *  they either collect it or go the full reclaim window without logging in after it expired.
     *  Seller-scoped (not the global activeAuctions() list, which caps at 200 rows) so a relic can never
     *  be missed just because enough unrelated listings exist. */
    private Database.AuctionRow activeAuctionFor(String owner,String relicKey){for(Database.AuctionRow row:db.activeAuctionsBySeller(owner))if(relicKey.equals(keyOf(row.item())))return row;return null;}
    private Database.AuctionRow expiredAuctionFor(String owner,String relicKey){for(Database.AuctionRow row:db.collectibleAuctions(owner))if(relicKey.equals(keyOf(row.item())))return row;return null;}
    private void lifecycleTick(){
        for(Player player:plugin.getServer().getOnlinePlayers())confirmInventory(player);
        long now=System.currentTimeMillis();
        for(Database.RelicLifecycleRow row:db.relicLifecycles()){
            if("LOST".equals(row.status()))lostTick(row,now);
            else if("ACTIVE".equals(row.status())&&!"hidden".equals(row.owner())){checkReclaim(row,now);checkStillExists(row);}
            else if("ELIGIBLE".equals(row.status()))eligibleTick(row);
        }
    }
    /** "Never resurface while any valid copy remains", enforced at the one place it actually matters: a
     *  relic that got marked LOST while a genuine copy was sitting in the owner's auction escrow (exactly
     *  the bug this whole rework fixes — self-healing for any relic already mislabeled this way when the
     *  fix deploys, and a permanent guard against it happening again through some other path) is restored
     *  to ACTIVE instead of ever being allowed to reach ELIGIBLE and resurface a second copy. Only once
     *  no such copy is found does the normal resurface countdown apply. */
    private void lostTick(Database.RelicLifecycleRow row,long now){
        if(!"hidden".equals(row.owner())&&(activeAuctionFor(row.owner(),row.key())!=null||expiredAuctionFor(row.owner(),row.key())!=null)){
            db.confirmRelic(row.key(),row.owner(),row.ownerName());
            plugin.getLogger().info("[RelicLifecycle] "+row.key()+" was marked LOST while a copy was still present in "+row.ownerName()+"'s auction escrow — restored to ACTIVE.");
            db.history("SERVER",null,"RELIC",displayName(row.key())+" was found safe in an auction listing and restored to "+row.ownerName()+" instead of resurfacing a duplicate.");
            return;
        }
        if(row.eligibleAt()>0&&now>=row.eligibleAt()){
            db.makeRelicEligible(row.key());
            plugin.getServer().broadcast(Component.text("Rumors speak of "+displayName(row.key())+" resurfacing somewhere in Ashfall...",NamedTextColor.LIGHT_PURPLE));
            db.history("SERVER",null,"RELIC",displayName(row.key())+" became eligible to resurface.");
        }
    }
    /** Backstop for destruction the event handlers can miss. itemLost() fires from ItemDespawnEvent and from
     *  EntityDamageEvent(VOID/FIRE/LAVA/explosion) on a dropped Item, but an item entity can also leave the
     *  world without either — most notably falling out of the bottom of the world, where it may simply be
     *  removed rather than damaged (confirmed live: a Colossus Core thrown into the void stayed ACTIVE
     *  indefinitely). This sweeps ACTIVE relics whose owner is online yet has no copy anywhere checkable —
     *  inventory, Ender Storage, auction escrow, any other online player's hands, their graves, or a loaded
     *  dropped-item entity — and only after two consecutive misses (~20 min, since this rides the 10-minute
     *  lifecycle pass) marks it lost. Deliberately unhurried: relic state is not performance-critical, and
     *  requiring two passes keeps a momentarily-unloaded chunk or an in-flight transfer from being mistaken
     *  for destruction. An offline owner is skipped entirely rather than assumed empty-handed. */
    private final Map<String,Integer> missingStrikes=new HashMap<>();
    private void checkStillExists(Database.RelicLifecycleRow row){
        String relicKey=row.key();
        if(ownerCopyState(row)!=CopyState.ABSENT){missingStrikes.remove(relicKey);return;}
        for(Player other:plugin.getServer().getOnlinePlayers())
            if(hasRelic(other.getInventory().getContents(),relicKey)||hasRelic(plugin.enderChests().allContents(other),relicKey)){missingStrikes.remove(relicKey);return;}
        Player owner=onlineById(row.owner());
        if(owner!=null)for(Database.GraveRow grave:db.graves(owner.getUniqueId()))
            if(hasRelic(db.graveItems(grave.id()).toArray(new ItemStack[0]),relicKey)){missingStrikes.remove(relicKey);return;}
        for(World world:plugin.getServer().getWorlds())for(Entity entity:world.getEntities())
            if(entity instanceof Item dropped&&relicKey.equals(keyOf(dropped.getItemStack()))){missingStrikes.remove(relicKey);return;}
        if(missingStrikes.merge(relicKey,1,Integer::sum)<2)return;
        missingStrikes.remove(relicKey);
        plugin.getLogger().info("[RelicLifecycle] "+relicKey+" could not be found in any checkable location across two consecutive passes while its owner ("+row.ownerName()+") was online — treating it as destroyed.");
        itemLostByKey(relicKey);
    }
    /** Mirrors lostTick()'s escrow self-heal, extended to the ELIGIBLE state — lostTick() only ever runs
     *  while status is still LOST, so a relic that was incorrectly marked lost by a since-fixed bug and
     *  had already crossed into ELIGIBLE before that fix ever deployed would otherwise sit there forever,
     *  or worse, let someone mint a genuine duplicate while the true owner's copy is still safely sitting
     *  in their auction escrow — exactly the state a real production relic was found stuck in (marked LOST
     *  by the OLD pre-rework code, timer expired, never revisited once ELIGIBLE). makeRelicEligible()
     *  never clears owner/owner_name, so the last recorded owner is still known here. */
    private void eligibleTick(Database.RelicLifecycleRow row){
        if("hidden".equals(row.owner()))return;
        if(activeAuctionFor(row.owner(),row.key())==null&&expiredAuctionFor(row.owner(),row.key())==null)return;
        db.confirmRelic(row.key(),row.owner(),row.ownerName());
        plugin.getLogger().info("[RelicLifecycle] "+row.key()+" was ELIGIBLE to resurface but a copy was still present in "+row.ownerName()+"'s auction escrow — restored to ACTIVE.");
        db.history("SERVER",null,"RELIC",displayName(row.key())+" was found safe in an auction listing and restored to "+row.ownerName()+" instead of resurfacing a duplicate.");
    }
    /** The single reclaim rule, replacing the old ability-usage/Minecraft-tick timers and the old
     *  "search every location or give up after N hours" auto-verify: a normally-held relic is reclaimed
     *  only once its owner has gone lifecycle.reclaim-after-real-days real days without logging in. An
     *  active auction listing pauses this clock entirely; an expired-but-uncollected listing starts the
     *  clock no earlier than the expiry, and any later login still resets it, exactly like a
     *  normally-held relic. This is the "never resurface while any valid copy remains" guarantee: it
     *  simply never fires while a tracked copy (inventory, Ender Storage, or auction escrow) is known to
     *  still exist and its owner is still coming back. */
    private void checkReclaim(Database.RelicLifecycleRow row,long now){
        String relicKey=row.key(),owner=row.owner();
        if(activeAuctionFor(owner,relicKey)!=null)return;
        long reclaimAfter=config.getLong("lifecycle.reclaim-after-real-days",7)*86400000L;
        Database.AuctionRow expired=expiredAuctionFor(owner,relicKey);
        long anchor=expired!=null?Math.max(expired.expires(),db.lastSeen(owner)):db.lastSeen(owner);
        if(anchor<=0||now-anchor<reclaimAfter)return;
        if(expired!=null){
            if(!db.reclaimExpiredAuction(expired.id()))return;
            plugin.getLogger().info("[RelicLifecycle] "+relicKey+" reclaimed from "+row.ownerName()+"'s expired, uncollected auction listing #"+expired.id()+" after "+config.getLong("lifecycle.reclaim-after-real-days",7)+" real days without a login since expiry.");
            db.history("SERVER",null,"RELIC",displayName(relicKey)+" was reclaimed from an expired, uncollected auction listing (last owner: "+row.ownerName()+").");
        }else{
            for(Player player:plugin.getServer().getOnlinePlayers())for(ItemStack item:player.getInventory().getContents())if(relicKey.equals(keyOf(item)))item.setAmount(0);
            plugin.getLogger().info("[RelicLifecycle] "+relicKey+" reclaimed — "+row.ownerName()+" has not logged in for "+config.getLong("lifecycle.reclaim-after-real-days",7)+" real days.");
            db.history("SERVER",null,"RELIC",displayName(relicKey)+" was reclaimed after "+config.getLong("lifecycle.reclaim-after-real-days",7)+" real days without a login (last owner: "+row.ownerName()+").");
        }
        itemLostByKey(relicKey);
    }
    /** Admin removal of the physical item(s) — not a permanent retirement. deactivateRelic() (still present
     *  in Database.java but deliberately never called from anywhere) sets a terminal 'RETIRED' status
     *  lifecycleTick() never revisits, which silently deleted the relic from the chronicle forever.
     *  markRelicLost() (the same call organic loss uses) puts it through the normal LOST -> eligible ->
     *  resurfacing cycle instead, using the same real-millis eligible_at basis lifecycleTick() checks. */
    boolean remove(String relicKey){
        if(db.relic(relicKey)==null)return false;
        db.markRelicLost(relicKey,System.currentTimeMillis()+config.getLong("lifecycle.resurface-after-real-days",2)*86400000L);
        for(Player player:plugin.getServer().getOnlinePlayers())for(ItemStack item:player.getInventory().getContents())if(relicKey.equals(keyOf(item)))item.setAmount(0);
        return true;
    }
    long lostReentryDays(){return config.getLong("lifecycle.resurface-after-real-days",2);}
    void list(Player p){
        List<Database.RelicLifecycleRow> rows=db.relicLifecycles();
        if(rows.isEmpty()){CoreUtil.msg(p,"No relics have entered the chronicle yet.");return;}
        CoreUtil.msg(p,"Relic chronicle:");
        long now=System.currentTimeMillis();
        for(Database.RelicLifecycleRow row:rows){
            String suffix="";
            if("LOST".equals(row.status())&&row.eligibleAt()>0)suffix=" — resurfaces in "+formatRemaining(row.eligibleAt()-now);
            CoreUtil.msg(p,"• "+displayName(row.key())+" — "+plugin.nicknames().displayName(row.ownerName())+" ["+CoreUtil.pretty(row.status())+"]"+suffix);
        }
    }
    private String formatRemaining(long millis){
        if(millis<=0)return "any moment now";
        long totalHours=millis/3600000L,days=totalHours/24,hours=totalHours%24;
        if(days>0)return days+"d "+hours+"h";
        if(hours>0)return hours+"h";
        return "under an hour";
    }
    /** A relic is identified ONLY by its persistent key, never by material or enchantments.
     *
     *  That is deliberate: these are one-of-one artifacts players are meant to keep and improve. Upgrading
     *  the Oathblade from diamond to netherite, adding Knockback or Looting, renaming it, or repairing it
     *  all preserve the item's custom data, so the relic keeps working. This test pins that contract --
     *  it rebuilds a relic on a DIFFERENT material with extra enchantments and asserts the key still
     *  resolves, so any future change that starts matching on material or enchantments fails here rather
     *  than silently disabling somebody's upgraded artifact. */
    boolean upgradeSelfTest(){
        ItemStack original=create("oathblade");
        if(!"oathblade".equals(keyOf(original)))return false;
        ItemStack upgraded=new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta from=original.getItemMeta(),to=upgraded.getItemMeta();
        to.getPersistentDataContainer().set(key,PersistentDataType.STRING,
                from.getPersistentDataContainer().get(key,PersistentDataType.STRING));
        to.displayName(from.displayName());
        upgraded.setItemMeta(to);
        upgraded.addUnsafeEnchantment(Enchantment.KNOCKBACK,2);
        upgraded.addUnsafeEnchantment(Enchantment.LOOTING,3);
        upgraded.addUnsafeEnchantment(Enchantment.SHARPNESS,5);
        /** Different material, three extra enchantments, still the same relic. */
        return "oathblade".equals(keyOf(upgraded));
    }
    boolean activeItem(ItemStack item,String relicKey){return relicKey.equals(keyOf(item))&&isActive(relicKey);}

    /** Self-service trace for a relic's CURRENT recorded owner: checks their own inventory/Ender Storage,
     *  their faction's claimed territory (loaded chunks), every grave in the database, and loaded-world
     *  dropped items — the full set of locations this server can actually, safely inspect. Only concludes
     *  "genuinely gone" and transitions to the lost/resurfacing lifecycle when every one of those comes up
     *  empty AND nobody else has since become the recorded owner; otherwise reports exactly where it is
     *  (or that ownership already moved on) instead of guessing. */
    void trace(Player player,String relicKey){
        if(relicKey==null||!keys().contains(relicKey)){CoreUtil.error(player,"Unknown relic key. Use /relics for the list.");return;}
        Database.RelicLifecycleRow row=db.relicLifecycle(relicKey);
        if(row==null||!"ACTIVE".equals(row.status())){CoreUtil.msg(player,displayName(relicKey)+" is not currently active (status: "+(row==null?"ELIGIBLE":CoreUtil.pretty(row.status()))+"); nothing to trace.");return;}
        if(!row.owner().equals(CoreUtil.id(player))){CoreUtil.error(player,"Only "+row.ownerName()+" can trace "+displayName(relicKey)+".");return;}
        CoreUtil.msg(player,"Tracing "+displayName(relicKey)+"...");
        if(hasRelic(player.getInventory().getContents(),relicKey)){conclude(player,relicKey,"found in their own inventory",true);CoreUtil.msg(player,"Found: it's in your own inventory right now.");return;}
        if(hasRelic(plugin.enderChests().allContents(player),relicKey)){conclude(player,relicKey,"found in their Ender Storage",true);CoreUtil.msg(player,"Found: it's sitting in your Ender Storage.");return;}
        Database.AuctionRow activeAuction=activeAuctionFor(CoreUtil.id(player),relicKey);
        if(activeAuction!=null){conclude(player,relicKey,"found in an active auction listing #"+activeAuction.id(),true);CoreUtil.msg(player,"Found: it's in your active Auction House listing #"+activeAuction.id()+".");return;}
        Database.AuctionRow expiredAuction=expiredAuctionFor(CoreUtil.id(player),relicKey);
        if(expiredAuction!=null){conclude(player,relicKey,"found in an expired, uncollected auction listing #"+expiredAuction.id(),true);CoreUtil.msg(player,"Found: it's sitting in your expired Auction House listing #"+expiredAuction.id()+" — run /ah collect to retrieve it.");return;}
        Database.FactionRow faction=db.factionOf(CoreUtil.id(player));
        if(faction!=null){
            FactionService.Claim claim=plugin.factions().claimOf(faction);
            if(claim!=null&&claimContains(claim,relicKey)){conclude(player,relicKey,"found in a container within their faction claim",true);CoreUtil.msg(player,"Found: it's in a container somewhere within your faction's claimed territory.");return;}
        }
        for(Database.GraveRow grave:db.graves(player.getUniqueId())){
            if(hasRelic(db.graveItems(grave.id()).toArray(new ItemStack[0]),relicKey)){conclude(player,relicKey,"found uncollected in grave #"+grave.id(),true);CoreUtil.msg(player,"Found: it's sitting uncollected in your grave #"+grave.id()+". Visit /graves before it expires!");return;}
        }
        for(World world:plugin.getServer().getWorlds())for(Entity entity:world.getEntities())if(entity instanceof Item item&&relicKey.equals(keyOf(item.getItemStack()))){conclude(player,relicKey,"found as a loaded dropped item in "+world.getName(),true);CoreUtil.msg(player,"Found: it's lying on the ground, currently loaded in "+CoreUtil.pretty(world.getEnvironment().name())+".");return;}
        Database.RelicLifecycleRow fresh=db.relicLifecycle(relicKey);
        if(!fresh.owner().equals(CoreUtil.id(player))){plugin.getLogger().info("[RelicTrace] "+player.getName()+" traced "+relicKey+": ownership already transferred to "+fresh.ownerName()+".");CoreUtil.msg(player,displayName(relicKey)+" is currently held by "+plugin.nicknames().displayName(fresh.ownerName())+" — ownership already transferred.");return;}
        itemLostByKey(relicKey);
        CoreUtil.msg(player,"No trace of "+displayName(relicKey)+" in your inventory, Ender Storage, active/expired auction listings, faction territory, graves, or loaded world entities. It has been marked lost and will resurface in "+config.getLong("lifecycle.resurface-after-real-days",2)+" real days.");
        plugin.getLogger().info("[RelicTrace] "+player.getName()+" traced "+relicKey+": no remaining copy found anywhere checkable; transitioned to LOST.");
        db.history("SERVER",null,"RELIC",plugin.nicknames().displayName(player)+" traced "+displayName(relicKey)+" and found no remaining copy; transitioned to lost.");
    }
    /** Found-somewhere outcomes still refresh last_confirmed for display purposes; the reclaim decision
     *  itself no longer depends on it (see checkReclaim(), keyed off login recency instead). */
    private void conclude(Player player,String relicKey,String logNote,boolean refresh){
        plugin.getLogger().info("[RelicTrace] "+player.getName()+" traced "+relicKey+": "+logNote+".");
        if(refresh)db.confirmRelic(relicKey,CoreUtil.id(player),player.getName());
    }
    private boolean hasRelic(ItemStack[] items,String relicKey){if(items==null)return false;for(ItemStack item:items)if(relicKey.equals(keyOf(item)))return true;return false;}
    private boolean claimContains(FactionService.Claim claim,String relicKey){
        World world=plugin.getServer().getWorld(claim.faction().world());if(world==null)return false;
        int minCx=claim.minX()>>4,maxCx=claim.maxX()>>4,minCz=claim.minZ()>>4,maxCz=claim.maxZ()>>4;
        for(int cx=minCx;cx<=maxCx;cx++)for(int cz=minCz;cz<=maxCz;cz++){
            if(!world.isChunkLoaded(cx,cz))continue;
            for(org.bukkit.block.BlockState state:world.getChunkAt(cx,cz).getTileEntities())
                if(state instanceof org.bukkit.block.Container container&&hasRelic(container.getInventory().getContents(),relicKey))return true;
        }
        return false;
    }

    /** A relic ability may never be used from inside protected spawn, and may never reach into it from
     *  outside. Spawn is a no-combat sanctuary, but the Colossus Core's shockwave is pure knockback rather
     *  than damage, so none of the normal damage/knockback guards caught it — a player standing safely in
     *  spawn could be physically shoved out of it by someone standing beyond the border. Both the activation
     *  site and every individual entity a relic effect touches are now checked (see relicEffectTarget). */
    private boolean relicBlockedInSpawn(Player player,String relicKey){
        if(!plugin.spawnClaims().contains(player.getLocation()))return false;
        CoreUtil.error(player,displayName(relicKey)+" lies dormant inside spawn.");
        return true;
    }
    /** Shared target filter for every active relic ability. Previously each one used
     *  `instanceof Enemy || instanceof Player`, which both missed legitimate combat mobs that aren't tagged
     *  Enemy (e.g. a provoked neutral) and offered no faction/friendly protection at all. This accepts any
     *  genuine combat LivingEntity while refusing: the caster, anything inside protected spawn, NPC and
     *  armor-stand style non-combat entities, invulnerable entities, tamed pets, and players the caster is
     *  factionally friendly with. */
    private boolean relicEffectTarget(Player caster,Entity entity){
        if(entity==null||entity.equals(caster)||!(entity instanceof LivingEntity living))return false;
        if(living.isDead()||!living.isValid()||living.isInvulnerable())return false;
        if(entity instanceof org.bukkit.entity.ArmorStand||entity.hasMetadata("NPC"))return false;
        if(plugin.spawnClaims().contains(entity.getLocation()))return false;
        if(living instanceof Player other){
            if(other.getGameMode()==GameMode.SPECTATOR||other.getGameMode()==GameMode.CREATIVE)return false;
            if(plugin.factions().friendly(caster,other))return false;
            return true;
        }
        if(living instanceof org.bukkit.entity.Tameable tame&&tame.isTamed())return false;
        return living instanceof org.bukkit.entity.Mob;
    }
    @EventHandler public void interact(PlayerInteractEvent event){
        if(!event.getAction().isRightClick()||event.getHand()!=EquipmentSlot.HAND)return;
        Player player=event.getPlayer();ItemStack item=player.getInventory().getItemInMainHand();String relicKey=keyOf(item);if(relicKey==null||!isActive(relicKey))return;
        if(relicBlockedInSpawn(player,relicKey)){event.setCancelled(true);return;}
        switch(relicKey){
            case"ashen_reprisal"->ashenReprisal(player,event);
            case"colossus_core"->colossusWard(player,event);
            case"warlords_ember"->warlordsDash(player,event);
            case"skyward_anchor"->skywardLaunch(player,event);
            default->{}
        }
    }
    // ================================================================== Skyward Anchor
    /*  A slam, not a jump.
     *
     *  Right-click LAUNCHES you and ARMS the relic; it is not a hold-to-use effect, so you cannot simply
     *  fall while holding it and expect a hit. Once armed, the drop is being measured, and the relic
     *  discharges on the first of three things:
     *
     *    1. you physically touch an entity on the way down,
     *    2. you touch the ground,
     *    3. you land a MACE hit before either of the above -- the combo.
     *
     *  Damage is 0.9x what a maximum-Density mace would do for the same drop, then scaled by how centred
     *  the target was under you: 1.5x dead centre, falling off to 0.5x at the edge of the 3x3x3. Only
     *  HORIZONTAL offset matters, because the box is a cube and vertical position within it is not a skill
     *  expression. Slightly worse than a mace on purpose -- it is area damage that is very hard to miss.
     *
     *  Every hit adds a wind burst, and the bursts MULTIPLY: two targets is twice the launch. Like a vanilla
     *  wind burst the launch itself costs no fall damage, and a strike that connects with anybody cancels
     *  the landing damage entirely even if nobody was directly beneath you. Miss everything and you take the
     *  fall exactly as a mace user would.
     *
     *  Deliberately no boss-specific bonus. It is already among the strongest things to bring to a boss;
     *  the one thing it does not do is get better at them.
     */

    /** What a maximum-Density mace would deal for this drop. Vanilla's own fall bonus curve (4/block for the
     *  first three, 2/block to eight, 0.5/block after) plus Density V at 0.5/block/level, on the mace's base
     *  attack damage. Kept as one function so the relic tracks any mace retuning by construction. */
    private double maceEquivalent(double fall){
        double drop=Math.max(0,fall);
        double bonus=drop<=3?4*drop:drop<=8?12+2*(drop-3):22+.5*(drop-8);
        return 7+bonus+2.5*drop;
    }

    /*  Launch heights, done against Minecraft's ACTUAL physics rather than a textbook parabola.
     *
     *  Every launch here derived its velocity from v = sqrt(2*g*h). That is the vacuum answer, and the game
     *  is not a vacuum: a player's vertical motion is `y += vy; vy = (vy - 0.08) * 0.98` -- there is 2% drag
     *  every tick. The error is small for a hop and enormous for a throw, because the drag compounds over
     *  every tick of the ascent:
     *
     *      configured   actually reached
     *          6              5.7
     *         20             16.3
     *         22             17.9      <- the mace combo, asking for 22 and delivering 18
     *         70             47.6
     *
     *  So "mace-combo-burst-height: 22" was really "about eighteen blocks", which is exactly the reported
     *  "still doesn't launch me insanely high".
     *
     *  apexHeight() is the closed form of that recurrence (terminal velocity 0.08*0.98/(1-0.98) = 3.92),
     *  verified against a tick-by-tick simulation to within 0.01 blocks. launchVelocity() inverts it, so a
     *  height in config is now the height genuinely reached. */
    private static double apexHeight(double velocity){
        if(velocity<=0)return 0;
        double terminal=3.92;
        double ticks=Math.log(terminal/(velocity+terminal))/Math.log(.98);
        return 50*velocity-terminal*ticks;
    }

    /** The upward velocity that actually reaches this many blocks. */
    private static double launchVelocity(double height){
        double low=0,high=12;
        for(int i=0;i<48;i++){double mid=(low+high)/2;if(apexHeight(mid)<height)low=mid;else high=mid;}
        return (low+high)/2;
    }

    private double anchorRadius(){return Math.max(.5,config.getDouble("buffs.skyward-anchor.aoe-radius",1.5));}

    /** 1.5x directly underneath, easing to 0.5x at the edge of the box. Horizontal distance only. */
    private double centringMultiplier(Location impact,Location target){
        double radius=anchorRadius();
        double dx=target.getX()-impact.getX(),dz=target.getZ()-impact.getZ();
        double horizontal=Math.sqrt(dx*dx+dz*dz);
        double centre=config.getDouble("buffs.skyward-anchor.centre-multiplier",1.5);
        double edge=config.getDouble("buffs.skyward-anchor.edge-multiplier",.5);
        double t=Math.max(0,Math.min(1,horizontal/radius));
        return centre+(edge-centre)*t;
    }

    private void skywardLaunch(Player player,PlayerInteractEvent event){
        event.setCancelled(true);
        if(onCooldown(player,"skyward_anchor",config.getLong("buffs.skyward-anchor.cooldown-seconds",20)*1000L))return;
        /** Anything already inside the box eats the strike right now -- the relic does not politely wait for
         *  you to come back down before noticing somebody standing on top of you. */
        int hits=strike(player,player.getLocation(),0,false);
        if(hits>0)windBurst(player,hits);
        else{
            double height=Math.max(1,config.getDouble("buffs.skyward-anchor.height",20));
            player.setVelocity(player.getVelocity().setY(launchVelocity(height)));
            player.setFallDistance(0);
        }
        anchors.put(player.getUniqueId(),new Anchor(player.getLocation().getY()));
        player.getWorld().playSound(player.getLocation(),Sound.ENTITY_BREEZE_JUMP,1f,.7f);
        player.getWorld().playSound(player.getLocation(),Sound.ITEM_MACE_SMASH_AIR,.8f,1.4f);
        player.getWorld().spawnParticle(Particle.GUST,player.getLocation(),1,0,0,0,0);
        player.getWorld().spawnParticle(Particle.CLOUD,player.getLocation(),25,.4,.1,.4,.02);
        CoreUtil.msg(player,hits>0?"The Skyward Anchor discharges as it lifts you.":"The Skyward Anchor hurls you skyward \u2014 come down hard.");
    }

    /** Launch upward, scaled by how many things were hit. Two targets is twice the HEIGHT, not twice the
     *  velocity, which keeps the multiplication readable instead of exponential. Fall distance is cleared
     *  the way a vanilla wind burst does, so the ride up is never what kills you. */
    /** The launch. Tuned to read as a real Wind Burst rather than as a mace's little hop.
     *
     *  The first pass used 2.5 blocks per target, which is roughly what an unenchanted mace smash gives you
     *  -- reported, correctly, as "the push is similar to a base mace push". The default is now a genuine
     *  Wind Burst II-sized launch, and it still MULTIPLIES per target: two targets is twice the HEIGHT.
     *  Height rather than velocity, because velocity would square the effect and put three targets in orbit.
     *
     *  Sound and particles are vanilla's own wind-burst pair, so it looks and sounds like the thing it is
     *  imitating, and fall distance is cleared so the ride up is never what kills you. */
    private void windBurst(Player player,int hits){
        windBurst(player,hits,config.getDouble("buffs.skyward-anchor.burst-height-per-hit",6));
    }

    private void windBurst(Player player,int hits,double perHit){
        int counted=Math.max(1,Math.min(hits,config.getInt("buffs.skyward-anchor.max-burst-hits",8)));
        double height=Math.min(Math.max(.5,perHit)*counted,
                Math.max(1,config.getDouble("buffs.skyward-anchor.max-burst-height",70)));
        /** Horizontal motion is damped so the launch reads as vertical lift rather than as being swatted. */
        Vector velocity=player.getVelocity();
        player.setVelocity(new Vector(velocity.getX()*.4,launchVelocity(height),velocity.getZ()*.4));
        player.setFallDistance(0);
        /** The ride down from a launch this size is not the player's fault, so it is not charged to them.
         *  Cleared the moment they land, so it only ever covers the one descent. */
        burstGrace.put(player.getUniqueId(),System.currentTimeMillis()
                +Math.max(1000,config.getLong("buffs.skyward-anchor.burst-fall-grace-seconds",12)*1000L));
        Location at=player.getLocation();
        player.getWorld().playSound(at,Sound.ENTITY_WIND_CHARGE_WIND_BURST,1.2f,counted>=2?.8f:1f);
        player.getWorld().playSound(at,Sound.ITEM_MACE_SMASH_GROUND_HEAVY,1f,.9f);
        player.getWorld().spawnParticle(Particle.GUST_EMITTER_LARGE,at,1,0,0,0,0);
        player.getWorld().spawnParticle(Particle.GUST,at,counted,.6,.2,.6,0);
    }

    /** Applies the area damage. Returns how many valid targets were actually hit. */
    private int strike(Player player,Location impact,double fall,boolean announce){
        double radius=anchorRadius();
        double scale=config.getDouble("buffs.skyward-anchor.mace-scale",.9);
        int hits=0;
        for(Entity entity:impact.getWorld().getNearbyEntities(impact,radius,radius,radius)){
            if(!relicEffectTarget(player,entity)||!(entity instanceof LivingEntity target))continue;
            double damage=scale*maceEquivalent(fall)*centringMultiplier(impact,target.getLocation());
            if(damage<=0)continue;
            /** Attributed to the player, so kill credit, boss damage tracking, PvP logging and every
             *  downstream reward path see it as their hit rather than as anonymous damage. */
            target.damage(damage,player);
            hits++;
        }
        if(hits>0){
            impact.getWorld().spawnParticle(Particle.EXPLOSION,impact,1,0,0,0,0);
            impact.getWorld().spawnParticle(Particle.GUST,impact,1,0,0,0,0);
            impact.getWorld().playSound(impact,Sound.ITEM_MACE_SMASH_GROUND_HEAVY,1f,1f);
            /** Reports the actual numbers, so the damage can be checked against what it is supposed to be
             *  instead of guessed at from a health bar. */
            if(announce)player.sendActionBar(Component.text("\u2726 SLAM \u2014 ",NamedTextColor.GOLD)
                    .append(Component.text(hits+(hits==1?" target":" targets"),NamedTextColor.WHITE))
                    .append(Component.text(String.format(java.util.Locale.US," \u2022 %.0f block drop \u2022 up to %.1f dmg",
                            fall,scale*maceEquivalent(fall)*config.getDouble("buffs.skyward-anchor.centre-multiplier",1.5)),NamedTextColor.GRAY)));
        }
        return hits;
    }

    /** Discharges the armed relic against whatever is underneath. Returns hits so callers can decide about
     *  fall damage. */
    private int discharge(Player player){
        Anchor anchor=anchors.remove(player.getUniqueId());
        if(anchor==null)return 0;
        double fall=Math.max(0,anchor.peak-player.getLocation().getY());
        int hits=strike(player,player.getLocation(),fall,true);
        if(hits>0){
            windBurst(player,hits);
            grantLandingGuard(player);
        }
        return hits;
    }

    /** A short window of immunity the instant a slam connects.
     *
     *  Committing to a twenty-block dive and then standing in the open for the recovery is how a big
     *  telegraphed move becomes a liability rather than a threat, especially in PvP. Deliberately granted
     *  ONLY on a slam that actually connected: missing still costs the full fall and gives nothing back, so
     *  the risk of committing is real. */
    private void grantLandingGuard(Player player){
        long ticks=Math.max(0,config.getLong("buffs.skyward-anchor.landing-invulnerability-ticks",10));
        if(ticks<=0)return;
        slamGuard.put(player.getUniqueId(),System.currentTimeMillis()+ticks*50L);
        player.getWorld().spawnParticle(Particle.ENCHANT,player.getLocation().add(0,1,0),18,.5,.8,.5,.4);
    }

    /** The landing window, and the wind burst's own descent. Void is never survivable by design. */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void anchorGuard(org.bukkit.event.entity.EntityDamageEvent event){
        if(!(event.getEntity() instanceof Player player))return;
        if(event.getCause()==org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID)return;
        long now=System.currentTimeMillis();
        Long guard=slamGuard.get(player.getUniqueId());
        if(guard!=null&&now<guard){event.setCancelled(true);return;}
        if(guard!=null)slamGuard.remove(player.getUniqueId());
        if(event.getCause()!=org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL)return;
        Long grace=burstGrace.remove(player.getUniqueId());
        if(grace!=null&&now<grace)event.setCancelled(true);
    }

    /** Per-tick bookkeeping for everyone with the relic armed. */
    private void anchorTick(){
        if(anchors.isEmpty())return;
        double maxAngle=config.getDouble("buffs.skyward-anchor.elytra-max-dive-angle",40);
        long life=Math.max(5,config.getLong("buffs.skyward-anchor.arm-seconds",30))*1000L;
        for(Map.Entry<UUID,Anchor> entry:new ArrayList<>(anchors.entrySet())){
            Player player=plugin.getServer().getPlayer(entry.getKey());
            Anchor anchor=entry.getValue();
            if(player==null||!player.isOnline()||player.isDead()){anchors.remove(entry.getKey());continue;}
            if(System.currentTimeMillis()-anchor.armedAt>life){anchors.remove(entry.getKey());continue;}
            Location at=player.getLocation();
            anchor.peak=Math.max(anchor.peak,at.getY());

            /** Elytra: a steep dive is a legitimate way to build the drop, a glide is not. Past the
             *  configured angle from straight down the accumulated height is surrendered -- but the relic
             *  stays armed, because the player did right-click for it and should not lose the charge for
             *  levelling out. Angle is measured from vertical: 0 is straight down, 90 is level flight. */
            if(player.isGliding()){
                Vector velocity=player.getVelocity();
                double speed=velocity.length();
                double angle=speed<=1e-4||velocity.getY()>=0?90:Math.toDegrees(Math.acos(Math.min(1,-velocity.getY()/speed)));
                if(angle>maxAngle)anchor.peak=at.getY();
            }

            /** Armed feedback, deliberately restrained.
             *
             *  The first version put a GUST puff and a cloud burst at the feet every tick, which filled the
             *  whole screen the moment you looked down -- i.e. exactly when you are lining up a slam. Now a
             *  couple of small motes in a slow ring around the ankles every quarter second, plus an action
             *  bar that also reports the drop being carried. The readout is the real feedback: it is
             *  unmissable, costs no screen space, and lets the holder time the hit. */
            if(player.getTicksLived()%5==0){
                double spin=player.getTicksLived()*.35;
                for(int i=0;i<2;i++){
                    double angle=spin+i*Math.PI;
                    player.getWorld().spawnParticle(Particle.CLOUD,
                            at.clone().add(Math.cos(angle)*.32,.12,Math.sin(angle)*.32),1,0,0,0,0);
                }
            }
            if(player.getTicksLived()%4==0){
                double carried=Math.max(0,anchor.peak-at.getY());
                player.sendActionBar(Component.text("\u2726 Skyward Anchor armed \u2014 ",NamedTextColor.AQUA)
                        .append(Component.text(String.format(java.util.Locale.US,"%.0f block drop",carried),NamedTextColor.WHITE)));
            }

            if(!player.isOnGround())anchor.airborne=true;
            else if(anchor.airborne){
                /** Landing with no fall damage event of its own (a short drop) still discharges, so a
                 *  ground touch always resolves the relic exactly as promised -- but only once the holder
                 *  has genuinely been off the ground, or the launch tick itself would end it. */
                discharge(player);
                continue;
            }
            /** There is deliberately NO mid-air "you touched a mob" trigger any more.
             *
             *  It used to fire the instant you brushed a target on the way down, which left no window at all
             *  to swing the mace first -- the combo was effectively unreachable. Landing is now the only
             *  thing that resolves the relic, which makes the whole dive predictable: fall, swing whenever
             *  you like, and the slam happens when you arrive. The cost is that a purely airborne target
             *  (a phantom, somebody on an elytra) can no longer be slammed in mid-air; it has to be caught
             *  where it meets the ground, or with the mace.
             */
        }
    }

    /** Landing damage. A strike that connected cancels it outright -- even if nobody was directly beneath,
     *  which is the whole point of an area slam. Miss, and the fall lands on you exactly as it would on a
     *  mace user who whiffed. */
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void anchorFall(org.bukkit.event.entity.EntityDamageEvent event){
        if(event.getCause()!=org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL)return;
        if(!(event.getEntity() instanceof Player player)||!anchors.containsKey(player.getUniqueId()))return;
        if(discharge(player)>0)event.setCancelled(true);
    }

    /** The combo: mace first, ground second.
     *
     *  Land a mace hit while the relic is armed and before it has discharged and the strike rides along with
     *  it -- double weapon damage on the target you actually hit, the relic's own area damage to everything
     *  else in the box, and a doubled wind burst. One relic, two ways to use it. */
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void anchorMaceCombo(org.bukkit.event.entity.EntityDamageByEntityEvent event){
        if(!(event.getDamager() instanceof Player player))return;
        Anchor anchor=anchors.get(player.getUniqueId());
        if(anchor==null)return;
        if(player.getInventory().getItemInMainHand().getType()!=Material.MACE)return;
        if(!relicEffectTarget(player,event.getEntity()))return;
        anchors.remove(player.getUniqueId());
        event.setDamage(event.getDamage()*Math.max(1,config.getDouble("buffs.skyward-anchor.mace-combo-multiplier",2)));
        double fall=Math.max(0,anchor.peak-player.getLocation().getY());
        /** The struck entity already took the (doubled) mace hit; the area damage is for everyone else. */
        Location impact=event.getEntity().getLocation();
        double radius=anchorRadius(),scale=config.getDouble("buffs.skyward-anchor.mace-scale",.9);
        int extra=0;
        for(Entity entity:impact.getWorld().getNearbyEntities(impact,radius,radius,radius)){
            if(entity.equals(event.getEntity())||!relicEffectTarget(player,entity)||!(entity instanceof LivingEntity target))continue;
            target.damage(scale*maceEquivalent(fall)*centringMultiplier(impact,target.getLocation()),player);
            extra++;
        }
        /** Unmistakable on purpose -- a combo you cannot tell fired is a combo nobody will use. */
        impact.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER,impact,1,0,0,0,0);
        impact.getWorld().spawnParticle(Particle.GUST_EMITTER_LARGE,impact,1,0,0,0,0);
        impact.getWorld().spawnParticle(Particle.FLASH,impact,1,0,0,0,0,Color.fromRGB(255,235,190));
        impact.getWorld().playSound(impact,Sound.ITEM_MACE_SMASH_GROUND_HEAVY,1.3f,.7f);
        impact.getWorld().playSound(impact,Sound.ENTITY_WIND_CHARGE_WIND_BURST,1.3f,.75f);
        impact.getWorld().playSound(impact,Sound.ITEM_TOTEM_USE,.7f,1.5f);
        /** The combo gets its OWN launch height rather than a doubled slam. Doubling six blocks was still
         *  read as "not as strong as I wanted"; this is a deliberately absurd, Wind-Burst-VI-sized throw,
         *  which is the point of landing a combo in the first place. Still multiplies per target struck. */
        windBurst(player,extra+1,config.getDouble("buffs.skyward-anchor.mace-combo-burst-height",22));
        grantLandingGuard(player);
        player.showTitle(net.kyori.adventure.title.Title.title(
                Component.text("\u2726 ANCHOR SLAM \u2726",NamedTextColor.LIGHT_PURPLE,net.kyori.adventure.text.format.TextDecoration.BOLD),
                Component.text((extra+1)+" struck \u2022 mace damage doubled \u2022 burst x2",NamedTextColor.WHITE),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(80),java.time.Duration.ofMillis(1200),java.time.Duration.ofMillis(400))));
    }
    /** Cooldowns are bound to the relic itself (persisted in the state table), not the player holding
     *  it — so dropping, relogging, dying, trading, or a server restart can never reset or bypass one;
     *  the single unique artifact simply isn't ready again until real time has actually passed. */
    private boolean onCooldown(Player player,String relicKey,long cooldownMs){
        /** Creative is for testing. Waiting out a twenty-second cooldown between attempts makes tuning a
         *  relic miserable, and nothing in creative is a balance concern by definition. Deliberately does
         *  not WRITE a cooldown either, so a creative test cannot lock the relic out for a survival player
         *  afterwards -- these cooldowns are bound to the relic itself, not to whoever is holding it. */
        if(player.getGameMode()==GameMode.CREATIVE)return false;
        long now=System.currentTimeMillis(),ready=parseLong(db.state("relic_cooldown:"+relicKey));
        if(now<ready){CoreUtil.error(player,displayName(relicKey)+" is not ready yet ("+((ready-now)/1000+1)+"s).");return true;}
        db.state("relic_cooldown:"+relicKey,Long.toString(now+cooldownMs));return false;
    }
    private long parseLong(String value){try{return value==null||value.isBlank()?0:Long.parseLong(value);}catch(NumberFormatException ignored){return 0;}}
    private void ashenReprisal(Player player,PlayerInteractEvent event){
        event.setCancelled(true);
        if(onCooldown(player,"ashen_reprisal",config.getLong("buffs.ashen-reprisal.cooldown-seconds",25)*1000L))return;
        double damage=config.getDouble("buffs.ashen-reprisal.damage",6),range=config.getDouble("buffs.ashen-reprisal.range",4.5);
        Location eye=player.getEyeLocation();Vector direction=eye.getDirection().normalize();int hits=0;
        for(Entity entity:player.getNearbyEntities(range,range,range)){
            if(!relicEffectTarget(player,entity)||!(entity instanceof LivingEntity target))continue;
            Vector to=target.getLocation().toVector().subtract(eye.toVector());if(to.length()>range)continue;
            if(direction.dot(to.normalize())<0.55)continue;
            target.damage(damage,player);target.setFireTicks(Math.max(target.getFireTicks(),60));target.setVelocity(target.getVelocity().add(new Vector(0,0.42,0)));hits++;
        }
        player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,eye.clone().add(direction.clone().multiply(1.4)),36,1.1,.5,1.1,.02);
        player.getWorld().playSound(player.getLocation(),Sound.ENTITY_BLAZE_SHOOT,1.3f,.7f);
        player.getWorld().playSound(player.getLocation(),Sound.ENTITY_WITHER_HURT,1f,1.7f);
        if(hits>0)CoreUtil.msg(player,"Ashen Reprisal struck "+hits+" foe"+(hits==1?"":"s")+".");
    }
    private void colossusWard(Player player,PlayerInteractEvent event){
        event.setCancelled(true);
        if(onCooldown(player,"colossus_core",config.getLong("buffs.colossus-core.cooldown-seconds",45)*1000L))return;
        int duration=(int)Math.round(config.getDouble("buffs.colossus-core.brace-seconds",3)*20);
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,duration,3,false,true,true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,duration,1,false,true,true));
        double radius=config.getDouble("buffs.colossus-core.radius",8),push=config.getDouble("buffs.colossus-core.knockback",3.4);
        double bossPush=config.getDouble("buffs.colossus-core.boss-knockback",2.6);
        int stagger=(int)Math.round(config.getDouble("buffs.colossus-core.stagger-seconds",5)*20);
        Location center=player.getLocation();
        for(Entity entity:player.getNearbyEntities(radius,radius,radius)){
            if(!relicEffectTarget(player,entity))continue;
            Vector away=entity.getLocation().toVector().subtract(center.toVector());if(away.lengthSquared()<0.01)away=new Vector(1,0,0);
            /** World bosses carry heavy knockback resistance, which is what made this relic look like it did
             *  nothing to the only targets worth using it on. The velocity is applied directly rather than
             *  added, and divided back out by their resistance, so a boss is visibly thrown too -- just less
             *  far than an ordinary mob. */
            boolean boss=entity instanceof LivingEntity living&&plugin.bosses().isWorldBoss(living);
            double force=boss?bossPush:push;
            if(boss&&entity instanceof LivingEntity living){
                var resist=living.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE);
                if(resist!=null&&resist.getValue()<1)force/=Math.max(0.15,1-resist.getValue());
            }
            away.normalize().multiply(force);away.setY(Math.max(away.getY(),boss?0.55:0.45));
            entity.setVelocity(boss?away:entity.getVelocity().add(away));
            /** Stagger, not crowd control: they keep moving and fighting, just slowed and unable to mine
             *  their way out. Strictly time-limited so nothing is ever permanently disabled. */
            if(entity instanceof LivingEntity hit){
                hit.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,stagger,2,false,true,true));
                hit.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE,stagger,1,false,true,true));
            }
        }
        player.getWorld().playSound(center,Sound.ENTITY_IRON_GOLEM_ATTACK,1.3f,.75f);
        player.getWorld().playSound(center,Sound.BLOCK_ANVIL_LAND,1f,.6f);
        player.getWorld().spawnParticle(Particle.EXPLOSION,center.clone().add(0,1,0),1);
        player.getWorld().spawnParticle(Particle.CLOUD,center.clone().add(0,.2,0),60,radius*.5,.2,radius*.5,.02);
        CoreUtil.msg(player,"The Colossus Core braces and answers in force.");
    }
    private void warlordsDash(Player player,PlayerInteractEvent event){
        event.setCancelled(true);
        if(onCooldown(player,"warlords_ember",config.getLong("buffs.warlords-ember.cooldown-seconds",20)*1000L))return;
        Vector direction=player.getLocation().getDirection().normalize();double power=config.getDouble("buffs.warlords-ember.power",2.1);
        Location destination=player.getLocation().add(direction.clone().multiply(power*2));
        if(plugin.spawnClaims().contains(destination)&&!plugin.isAdmin(player)){CoreUtil.error(player,"You cannot dash into protected spawn territory.");return;}
        /** The launch is the combo opener: velocity is SET, never re-zeroed afterwards, so the wielder keeps
         *  the momentum and can swap to a heavier weapon mid-flight and land the follow-up while the target
         *  is still burning from the charge. */
        player.setVelocity(direction.clone().multiply(power).setY(Math.max(0.28,direction.getY()*.5+0.28)));
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,140,0,false,true,true));
        double radius=config.getDouble("buffs.warlords-ember.radius",4.5);
        double impact=config.getDouble("buffs.warlords-ember.impact-damage",24);
        double push=config.getDouble("buffs.warlords-ember.knockback",1.6);
        double bossPush=config.getDouble("buffs.warlords-ember.boss-knockback",1.1);
        int burn=(int)Math.round(config.getDouble("buffs.warlords-ember.burn-seconds",6)*20);
        Location center=player.getLocation().add(direction.clone().multiply(2));
        for(Entity entity:player.getWorld().getNearbyEntities(center,radius,radius,radius)){
            /** relicEffectTarget keeps friendly faction members, NPCs, tamed pets and protected entities
             *  out; the spawn check above already refused the dash itself inside protected territory. */
            if(!relicEffectTarget(player,entity)||!(entity instanceof LivingEntity target))continue;
            boolean boss=plugin.bosses().isWorldBoss(target);
            /** Real damage, credited to the wielder so it counts as their contribution on a boss. */
            target.damage(impact,player);
            target.setFireTicks(Math.max(target.getFireTicks(),burn));
            double force=boss?bossPush:push;
            if(boss){
                var resist=target.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE);
                if(resist!=null&&resist.getValue()<1)force/=Math.max(0.15,1-resist.getValue());
                target.setVelocity(direction.clone().multiply(force).setY(.35));
            }else target.setVelocity(target.getVelocity().add(direction.clone().multiply(force).setY(.35)));
        }
        player.getWorld().playSound(player.getLocation(),Sound.ITEM_FIRECHARGE_USE,1.2f,1.1f);
        player.getWorld().playSound(player.getLocation(),Sound.ENTITY_PIGLIN_BRUTE_ANGRY,.8f,1.3f);
        player.getWorld().spawnParticle(Particle.FLAME,player.getLocation(),30,.4,.2,.4,.03);
    }
    double eliteOutgoingMultiplier(Player player){return activeItem(player.getInventory().getItemInMainHand(),"oathblade")?config.getDouble("buffs.oathblade.elite-damage-multiplier",1.25):1;}
    double eliteIncomingMultiplier(Player player){return activeItem(player.getInventory().getHelmet(),"crown_of_ash")?config.getDouble("buffs.crown-of-ash.elite-damage-multiplier",.8):1;}
    double oathbladeLifesteal(Player player){return activeItem(player.getInventory().getItemInMainHand(),"oathblade")?config.getDouble("buffs.oathblade.lifesteal-percent",10)/100.0:0;}
    private void buffTick(){for(Player player:plugin.getServer().getOnlinePlayers()){if(activeItem(player.getInventory().getHelmet(),"crown_of_ash")){player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE,100,0,true,false,true));player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE,100,0,true,false,true));}boolean wayfinder=activeItem(player.getInventory().getItemInMainHand(),"wayfinder")||activeItem(player.getInventory().getItemInOffHand(),"wayfinder");if(wayfinder){player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED,100,0,true,false,true));player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION,260,0,true,false,true));}}}
}
