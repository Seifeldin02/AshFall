package net.communitysmp.core;

import com.lishid.openinv.IOpenInv;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Page 1 is a hybrid, and that split matters everywhere below. Slots 0-26 ARE the actual vanilla Ender
 *  Chest object — the same one /openender and any physically placed Ender Chest block read — never copied-
 *  and-forgotten: openPage1() shows a plain custom GUI whose first 27 slots start as a live copy of the real
 *  chest, and every click/drag is mirrored back into the real object within the same tick (see
 *  mirrorPage1()), plus a final mirror on close as a safety net. Slots 27-44 are NOT vanilla data — they are
 *  genuine new plugin-only storage (18 slots) that exists purely so page 1 can match page 2's full-double-
 *  chest size without vanilla's real 27-slot ceiling forcing either wasted glass or a visibly smaller page.
 *  They persist through the exact same DELETE-then-INSERT-only-non-empty path as chunks 2+ (db.enderPage/
 *  saveEnderPage, reusing the "page 1" partition of ender_chest_items, which nothing else ever wrote to
 *  before), saved when the last viewer closes — safe with no snapshot/merge risk because, like chunks 2+,
 *  every viewer shares the exact same live Inventory object (see liveMirrors). /openender and any world
 *  Ender Chest block only ever see slots 0-26; they were never aware of 27-44 and still aren't.
 *  It supports simultaneous owner + admin viewing exactly like chunks 2+ (liveMirrors, keyed by target,
 *  evicted only once the last viewer leaves) — admin inspection of page 1 used to be OpenInv's job alone,
 *  but there is no longer a technical reason for that split now that this mirror exists; /enderchest inspect
 *  covers everything.
 *  An earlier attempt made page 1 literally the same NMS object via net.minecraft.world.CompoundContainer
 *  (the exact mechanism vanilla uses to merge two real chest halves into one double-chest GUI). That was
 *  reverted after it turned out Paper's CraftInventoryDoubleChest.getLocation() throws a NullPointerException
 *  whenever either half lacks a real block location — ours does, since a player's Ender Chest isn't tied to
 *  a block — and it kicked a live player. The mirrored design here never constructs anything Paper doesn't
 *  already fully support for a plain custom inventory.
 *  Chunks 2+ (the paid Ashfall upgrade pages) aren't vanilla-constrained, so each one holds a genuine full
 *  double chest of real storage. Materialized ONCE into a real Inventory object shared by every current
 *  viewer — owner and/or admin — the same way Bukkit already lets two players view one physical chest, so
 *  there is no snapshot, no independent copy, and no merge, because there is only ever one object.
 *  Navigation is bounded by the PURCHASED chunk count, not just by whether a button is drawn — an earlier
 *  version opened sharedChunk(chunk+1) unconditionally on the Next-page click, letting a player materialize
 *  (and get real, persisted) pages far past anything they paid for just by clicking where a button would be
 *  even after the real Next arrow had stopped being drawn. Every navigation click re-checks the actual
 *  purchased chunk count before acting, never just the button that happened to render when the page opened. */
final class EnderChestService implements Listener {
    private static final int CHUNK=27;
    /** Page 1 is now a genuine full double chest (54, matching page 2): slots 0-26 the real vanilla chest,
     *  27-44 the new plugin-only bonus storage described in the class doc, 45-53 the nav row. */
    private static final int PAGE1_SIZE=54;
    private static final int PAGE1_NAV=PAGE1_SIZE-9;
    /** Upgrade pages aren't vanilla-constrained — a genuine full double chest of real storage, nav row
     *  immediately after with no wasted middle rows, same shape principle as page 1. */
    private static final int UPGRADE_CHUNK=45;
    private static final int UPGRADE_PAGE_SIZE=54;
    private record UpgradeHolder(String player,int returnPage) implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private record ChunkHolder(String player,int chunk) implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private record Page1Holder(String target) implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private final SMPCore plugin;
    private final Database db;
    private final Map<UUID,Integer> currentPages=new HashMap<>();
    /** Key: target playerId. Present exactly while at least one viewer — owner and/or an inspecting admin —
     *  has page 1 open; mirrored into the real vanilla Ender Chest on every interaction (see mirrorPage1()),
     *  never only on close, and evicted the instant the last viewer closes it. */
    private final Map<String,Inventory> liveMirrors=new HashMap<>();
    /** Key: playerId+":"+chunkNumber (chunk 2+ only). Present exactly while at least one viewer — owner
     *  and/or an inspecting admin — has that page open; persisted to the database and evicted the instant
     *  the last viewer closes it, never before (see close()). */
    private final Map<String,Inventory> liveChunks=new HashMap<>();
    /** Key: target playerId (lowercase name, same as holder.target()). Holds the fake-but-real Player OpenInv
     *  hands back for a genuinely offline target — its getEnderChest() reads/writes their actual persisted
     *  data, not a snapshot, so this is safe against the target logging in mid-inspection: if they do,
     *  OpenInv's own loadPlayer()/unload() handles that reconciliation, the exact problem that plugin exists
     *  to solve safely rather than SMPCore re-implementing raw playerdata NBT access. */
    private final Map<String,Player> offlineLoaded=new HashMap<>();

    EnderChestService(SMPCore plugin){
        this.plugin=plugin;
        this.db=plugin.db();
        db.migrateEnderStorageAdditive();
        /** Runs the same idempotent, tier-raising-only migration below automatically on every startup, not
         *  only on manual admin trigger — a real production incident showed the manual-only approach is
         *  actively dangerous: a player who logs in and opens /enderchest before an admin remembers to run
         *  the command sees their entire bonus row and page 2 rendered as Locked (their ender_tier column
         *  is still its default, 0, until migrated), which looks exactly like real item loss even though
         *  nothing was actually touched — that's what triggered this comment. Still safe (and still useful)
         *  to trigger manually too — see migrateTiers(CommandSender) — running it twice computes the exact
         *  same result and changes nothing, per its own doc below.
         *  GATED OFF (default false) pending full forensic verification of that same incident — do not flip
         *  this to default-true until that investigation is explicitly closed out. */
        if(plugin.getConfig().getBoolean("ender-chest.auto-migrate-on-startup",false)){
            int changed=runMigrateTiers();
            if(changed>0)plugin.getLogger().info("[EnderChest] Startup tier migration upgraded "+changed+" player(s) to a higher tier.");
        }
    }

    void shutdown(){
        for(var entry:new HashMap<>(liveMirrors).entrySet()){
            Player target=plugin.getServer().getPlayerExact(entry.getKey());
            if(target!=null)mirrorPage1(target,entry.getValue());
            persistPage1Bonus(entry.getKey(),entry.getValue());
        }
        liveMirrors.clear();
        for(var entry:new HashMap<>(liveChunks).entrySet()){
            int split=entry.getKey().lastIndexOf(':');
            persistChunk(entry.getKey().substring(0,split),Integer.parseInt(entry.getKey().substring(split+1)),entry.getValue());
        }
        liveChunks.clear();
    }

    boolean isPersonalStorage(Inventory inventory,Player player){
        if(inventory==null)return false;
        InventoryHolder holder=inventory.getHolder(false);
        if(holder instanceof ChunkHolder ch)return ch.player().equals(CoreUtil.id(player));
        if(holder instanceof Page1Holder ph)return ph.target().equals(CoreUtil.id(player));
        return inventory.getType()==org.bukkit.event.inventory.InventoryType.ENDER_CHEST&&holder instanceof Player owner&&owner.equals(player);
    }
    /** True exactly when `viewer` is an authorized admin looking at SOMEONE ELSE's Ender Storage page via
     *  /enderchest inspect (Page1Holder/ChunkHolder whose target isn't the viewer) — the same "who's allowed
     *  to move bound items here" question ShardService.inventory() already asks for OpenInv's /inv
     *  (topType==PLAYER, a type check) and for the owner's own storage (isPersonalStorage(), an ownership
     *  check). A non-admin can never reach this state at all — command() gates /enderchest inspect on
     *  isAdmin() before openForAdmin() ever runs — so "admin AND target != viewer" is equivalent to "this is
     *  an active, authorized inspection session", not a general bypass. ShardService.inventory() uses this to
     *  decide whether to run its own inspectionTransfer() (rebind + audit-log) instead of the normal
     *  bound-item restriction. */
    boolean isAdminInspecting(Inventory inventory,Player viewer){
        if(inventory==null||!plugin.isAdmin(viewer))return false;
        InventoryHolder holder=inventory.getHolder(false);
        if(holder instanceof ChunkHolder ch)return !ch.player().equals(CoreUtil.id(viewer));
        if(holder instanceof Page1Holder ph)return !ph.target().equals(CoreUtil.id(viewer));
        return false;
    }
    /** The player ID this Ender Storage page belongs to (Page1Holder's target or ChunkHolder's player), or
     *  null if this inventory isn't one — lets ShardService know who a bound-item insert during /ec inspect
     *  should be rebound to, without needing to know about ChunkHolder/Page1Holder itself. */
    String targetOf(Inventory inventory){
        InventoryHolder holder=inventory.getHolder(false);
        if(holder instanceof ChunkHolder ch)return ch.player();
        if(holder instanceof Page1Holder ph)return ph.target();
        return null;
    }
    /** Any Ender Storage page — this player's own or (via admin inspect) someone else's, and either the
     *  paged SMPCore GUI or the raw vanilla fallback. Ownership-agnostic on purpose: used to block a whole
     *  category of item (relics) from Ender Storage entirely, not to check who it belongs to. */
    boolean isEnderChestStorage(Inventory inventory){
        if(inventory==null)return false;
        InventoryHolder holder=inventory.getHolder(false);
        return holder instanceof ChunkHolder||holder instanceof Page1Holder||inventory.getType()==org.bukkit.event.inventory.InventoryType.ENDER_CHEST;
    }

    boolean command(Player player,String[] args){
        if(args.length>=2&&args[0].equalsIgnoreCase("inspect")){
            if(!plugin.isAdmin(player)){CoreUtil.error(player,"Only ADMIN can inspect another player's Ender Storage.");return true;}
            String targetId=CoreUtil.id(args[1]);
            Player target=plugin.getServer().getPlayerExact(args[1]);
            if(target==null){
                if(db.player(targetId)==null){CoreUtil.error(player,"That player has not joined this server.");return true;}
                target=loadOffline(targetId,args[1]);
                if(target==null){CoreUtil.error(player,"That player is offline, and OpenInv (required for offline Ender Storage inspection) is unavailable.");return true;}
            }
            openForAdmin(player,target);return true;
        }
        if(blocked(player))return true;
        if(args.length==0){open(player);return true;}
        if(args[0].equalsIgnoreCase("page")){
            if(args.length<2){CoreUtil.error(player,"Usage: /enderchest page <number>");return true;}
            try{openPage(player,Integer.parseInt(args[1]));}catch(NumberFormatException error){CoreUtil.error(player,"Use a valid page number.");}
            return true;
        }
        if(!args[0].equalsIgnoreCase("upgrade")){CoreUtil.error(player,"Usage: /enderchest [page <number>|upgrade]");return true;}
        if(args.length>1&&args[1].equalsIgnoreCase("confirm"))purchase(player,currentPages.getOrDefault(player.getUniqueId(),1));
        else openUpgrade(player,currentPages.getOrDefault(player.getUniqueId(),1));
        return true;
    }
    void openFromBlock(Player player){open(player);}
    ItemStack[] allContents(Player player){return loadAll(player,capacity(player));}

    private IOpenInv openInvApi(){org.bukkit.plugin.Plugin p=plugin.getServer().getPluginManager().getPlugin("OpenInv");return p instanceof IOpenInv api?api:null;}
    /** Resolves a target for mirroring purposes — a genuinely online Player first, otherwise whatever OpenInv
     *  proxy is currently held for them (re-loading on demand if a prior session's proxy was already released,
     *  e.g. after navigating away and back). Never null while the target has actually joined the server
     *  before, short of OpenInv being missing/failing. */
    private Player resolveTarget(String targetId){
        Player online=plugin.getServer().getPlayerExact(targetId);
        if(online!=null)return online;
        Player loaded=offlineLoaded.get(targetId);
        if(loaded!=null)return loaded;
        Database.PlayerRow row=db.player(targetId);
        return row==null?null:loadOffline(targetId,row.name());
    }
    private Player loadOffline(String targetId,String name){
        IOpenInv api=openInvApi();if(api==null)return null;
        org.bukkit.OfflinePlayer offline=plugin.getServer().getOfflinePlayer(name);
        Player loaded=api.loadPlayer(offline);if(loaded==null)return null;
        offlineLoaded.put(targetId,loaded);return loaded;
    }
    /** Only safe to actually release the OpenInv proxy once nothing of this target's Ender Storage is still
     *  open anywhere (page 1 or any chunk 2+ page) — navigating between those pages closes-then-reopens, so a
     *  single close event alone can't tell "done inspecting" from "just switched pages". */
    private void unloadIfDone(String targetId){
        Player loaded=offlineLoaded.get(targetId);if(loaded==null)return;
        if(liveMirrors.containsKey(targetId)||liveChunks.keySet().stream().anyMatch(key->key.startsWith(targetId+":")))return;
        IOpenInv api=openInvApi();if(api!=null)api.unload(plugin.getServer().getOfflinePlayer(loaded.getName()));
        offlineLoaded.remove(targetId);
    }

    /** Admin inspection now covers everything, page 1 included, through the same live mirror the owner's
     *  own /ec uses — there's no remaining technical reason to send admins to /openender for the base chest. */
    void openForAdmin(Player admin,Player target){
        plugin.db().logAudit(admin.getName(),"INSPECT_ENDERCHEST","target="+target.getName());
        CoreUtil.msg(admin,"Showing "+target.getName()+"'s Ender Storage, live.");
        openPage1(admin,target,totalPages(target));
    }

    /** Tier 0 (free) is always exactly the real vanilla chest, 27 slots — not configurable, since it IS
     *  the vanilla chest. Tiers 1-3 are purchased and each grant a TOTAL slot count (not incremental) from
     *  ender-chest.tiers.<N>.slots. Two physical GUI pages cover the full 0-90 range regardless of tier —
     *  page 1 is slots 0-44 (27 vanilla + up to 18 bonus), page 2 is slots 45-89 — but only however many of
     *  those slots the player's current tier actually unlocks are usable; the rest render locked (see
     *  lockedFrom()/openPage1()/sharedChunk()). This keeps the entire live-mirroring/multi-viewer/OpenInv
     *  architecture above completely untouched: only how much of the already-existing GUI space is
     *  currently interactive changes. */
    private static final int BASE_CAPACITY=27;
    private static final int MAX_TIER=3;
    private int tier(String playerId){return Math.max(0,Math.min(MAX_TIER,db.enderTier(playerId)));}
    private int tier(Player player){return tier(CoreUtil.id(player));}
    private int tierCapacity(int tier){return tier<=0?BASE_CAPACITY:plugin.getConfig().getInt("ender-chest.tiers."+tier+".slots",BASE_CAPACITY);}
    private double tierPrice(int tier){return plugin.getConfig().getDouble("ender-chest.tiers."+tier+".price",0);}
    private int capacity(Player player){return tierCapacity(tier(player));}
    /** Page 2 only ever becomes navigable once the player has unlocked at least one slot of it (tier 2+,
     *  72 slots) — matching the old "don't even show a Next button for something you haven't bought"
     *  behavior, just keyed off tier instead of chunk count. */
    private int totalPages(Player player){return totalPages(CoreUtil.id(player));}
    private int totalPages(String playerId){return tierCapacity(tier(playerId))>UPGRADE_CHUNK?2:1;}
    int displayPages(Player player){return totalPages(player);}
    /** How many of THIS page's UPGRADE_CHUNK logical slots are currently unlocked — page 1 covers logical
     *  0-44, page 2 covers logical 45-89. Clamped into [0,UPGRADE_CHUNK]. */
    private int unlockedOnPage(Player player,int pageNumber){return unlockedOnPage(CoreUtil.id(player),pageNumber);}
    private int unlockedOnPage(String playerId,int pageNumber){
        int cap=tierCapacity(tier(playerId)),pageStart=(pageNumber-1)*UPGRADE_CHUNK;
        return Math.max(0,Math.min(UPGRADE_CHUNK,cap-pageStart));
    }

    /** Bare /ec always lands on page 1 — the familiar "this is my Ender Chest" view. */
    private void open(Player player){openPage(player,1);}

    private void openPage(Player player,int requested){
        if(blocked(player))return;
        int totalChunks=totalPages(player),page=Math.max(1,Math.min(requested,totalChunks));
        currentPages.put(player.getUniqueId(),page);
        if(page==1){openPage1(player,player,totalChunks);return;}
        player.openInventory(sharedChunk(CoreUtil.id(player),page));
    }

    /** Materializes the target's page-1 mirror ONCE and hands the SAME object to every subsequent viewer —
     *  owner and/or admin — until the last of them closes it (see close()), exactly the sharedChunk() pattern
     *  used for chunks 2+. Slots 0-26 come from the real vanilla chest and mirror back to it on every
     *  click/drag (see mirrorPage1()); slots 27-44 are the plugin-only bonus storage described in the class
     *  doc, loaded from db.enderPage(id,1) and persisted the same way chunks 2+ are — on close, safely,
     *  because every viewer already shares this one live object. */
    private void openPage1(Player viewer,Player target,int totalChunks){
        String targetId=CoreUtil.id(target);
        Inventory view=liveMirrors.get(targetId);
        if(view==null){
            view=plugin.getServer().createInventory(new Page1Holder(targetId),PAGE1_SIZE,
                    Component.text("Ender Chest",NamedTextColor.DARK_PURPLE));
            ItemStack[] real=target.getEnderChest().getContents();
            for(int i=0;i<CHUNK&&i<real.length;i++)if(real[i]!=null)view.setItem(i,real[i].clone());
            ItemStack[] bonus=db.enderPage(targetId,1);
            for(int i=CHUNK;i<PAGE1_NAV&&i<bonus.length;i++)if(bonus[i]!=null)view.setItem(i,bonus[i].clone());
            /** Fill the WHOLE nav row with glass first, before refreshPage1Nav() overwrites the 3 real button
             *  slots — matching sharedChunk()'s already-safe pattern for chunks 2+. Without this, the other 6
             *  nav-row slots stayed genuinely empty in the live Inventory object: direct clicks/drags on them
             *  were already cancelled below, but a shift-click FROM THE PLAYER'S OWN INVENTORY isn't caught by
             *  that check (its raw slot is in the bottom inventory, not 45-53), so Bukkit's default shift-click
             *  routing would silently place items into those empty slots once the real bonus slots (27-44)
             *  filled up — and persistPage1Bonus()/saveEnderPage() only ever save slots 0-44, so anything that
             *  landed in 45-53 was discarded the moment the chest closed. Confirmed unrecoverable: nothing in
             *  this codebase ever wrote those slots to disk, at any point. */
            for(int i=PAGE1_NAV;i<PAGE1_SIZE;i++)view.setItem(i,CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,"",List.of()));
            liveMirrors.put(targetId,view);
        }
        refreshPage1Locks(view,target);
        refreshPage1Nav(view,totalChunks);
        viewer.openInventory(view);
    }
    /** Re-applied every time page 1 is (re)handed out, not just at first materialization, so a live
     *  upgrade purchase mid-session immediately unlocks the newly-available slots without needing to
     *  reopen. Only ever overlays the lock indicator onto a slot that's currently EMPTY (or already
     *  showing the lock indicator) — never hides or replaces a real item. That should be unreachable
     *  anyway since tiers only ever increase, but costs nothing to guard regardless. */
    private void refreshPage1Locks(Inventory view,Player target){
        int unlocked=unlockedOnPage(target,1);
        for(int i=CHUNK;i<PAGE1_NAV;i++){
            boolean shouldLock=i>=unlocked;
            ItemStack current=view.getItem(i);
            boolean isLockPlaceholder=current!=null&&current.getType()==Material.BARRIER;
            if(shouldLock&&(current==null||isLockPlaceholder))view.setItem(i,lockedSlotItem());
            else if(!shouldLock&&isLockPlaceholder)view.setItem(i,null);
        }
    }
    private ItemStack lockedSlotItem(){return CoreUtil.named(Material.BARRIER,"Locked",List.of("Upgrade your Ender Chest to unlock this slot.","/enderchest upgrade"));}
    private boolean isLockedSlot(ItemStack item){return item!=null&&item.getType()==Material.BARRIER&&item.hasItemMeta()&&item.getItemMeta().hasDisplayName()&&net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()).equals("Locked");}

    /** One-time, idempotent migration from the old chunk-count model to the new 27/45/72/90 tier system.
     *  Never called automatically (no startup hook) — a production data change like this needs a
     *  deliberate, reported, admin-triggered run, not a silent one buried in a restart. Safe to run more
     *  than once: it only ever raises a player's tier to the minimum required, never lowers it, and
     *  re-running against already-migrated data recomputes the exact same result and changes nothing.
     *
     *  Two independent signals decide the minimum tier, and the higher of the two wins:
     *   1. Occupancy — the highest logical slot, ONLY across the old page 1 (0-44) and page 2 (45-89) —
     *      the exact two pages that still exist, unchanged, in the new system — that actually has an item
     *      in it. The vanilla 27-slot chest itself is deliberately never checked: it physically cannot
     *      hold more than 27 items, so occupancy there alone can never require more than Tier 0. Deliberately
     *      does NOT look at any old page 3+: production's old max-upgrades:2 config made that physically
     *      impossible to ever reach for a real player (confirmed against production's live config this
     *      session), so nothing legitimate can be sitting there — but stray leftover test/debug data doing
     *      so on a non-production database must NOT count either, since nothing in the new 2-page layout
     *      can represent or display an old page 3+ slot at all. Counting it anyway granted a tier with no
     *      visible storage behind it (confirmed live: an old page-7 item drove a Tier 3 grant while the
     *      player's actual page 2 rendered completely empty) — exactly backwards from "grant the minimum
     *      tier the occupied space requires".
     *   2. Purchase history — anyone with a real economy_ledger record of buying the old $200k chunk-2
     *      upgrade is floored at Tier 1 even if that space is currently empty; they already paid for it.
     *
     *  Reads whichever is authoritative per player exactly like loadAll() does: a live shared Inventory if
     *  someone (owner or admin) happens to have that page open right now, otherwise the database — so a
     *  migration run while players are online can't read stale pre-save data out from under them. */
    int migrateTiers(org.bukkit.command.CommandSender sender){
        int[] counts=new int[2];
        int changed=runMigrateTiers(counts);
        CoreUtil.msg(sender,"Ender Chest tier migration: checked "+counts[0]+" known player(s), upgraded "+changed+" to a higher tier. Nobody's tier was ever lowered or left unmigrated if they qualified for more.");
        return changed;
    }
    private int runMigrateTiers(){return runMigrateTiers(new int[2]);}
    private int runMigrateTiers(int[] countsOut){
        Set<String> legacyBuyers=db.legacyEnderStorageBuyers();
        int checked=0,changed=0;
        for(String id:db.allPlayerIds()){
            checked++;
            int currentTier=Math.max(0,db.enderTier(id));
            int required=Math.max(occupancyTier(id),legacyBuyers.contains(id)?1:0);
            if(required>currentTier){db.setEnderTier(id,required);changed++;}
        }
        countsOut[0]=checked;countsOut[1]=changed;
        return changed;
    }
    /** Only ever looks at old page 1 (0-44) and page 2 (45-89) — the exact two pages that still exist,
     *  unchanged, in the new system. Deliberately does NOT check page 3+ (see migrateTiers()'s doc for why
     *  that would be wrong even defensively, not just unnecessary) — production's old max-upgrades:2 config
     *  made anything past page 2 physically impossible for a real player to ever have. */
    private int occupancyTier(String playerId){
        int tier=0;
        for(int page=1;page<=2;page++){
            ItemStack[] data=migrationSource(playerId,page);
            if(data==null)continue;
            int start=page==1?CHUNK:0;
            for(int i=start;i<UPGRADE_CHUNK&&i<data.length;i++){
                if(data[i]==null||isLockedSlot(data[i]))continue;
                if(page==1)tier=Math.max(tier,1);
                else tier=Math.max(tier,i<27?2:3);
            }
        }
        return tier;
    }
    /** Live shared Inventory if currently open (owner or admin), otherwise a pure database read — never
     *  touches OpenInv/the vanilla chest, since (as occupancyTier()'s doc explains) the vanilla chest is
     *  irrelevant to which tier is required. */
    private ItemStack[] migrationSource(String playerId,int page){
        Inventory live=page==1?liveMirrors.get(playerId):liveChunks.get(playerId+":"+page);
        if(live!=null){ItemStack[] result=new ItemStack[UPGRADE_CHUNK];for(int i=0;i<UPGRADE_CHUNK;i++)result[i]=live.getItem(i);return result;}
        return db.enderPage(playerId,page);
    }
    private void refreshPage1Nav(Inventory view,int totalChunks){
        view.setItem(PAGE1_NAV+4,CoreUtil.named(Material.ENDER_EYE,"Page 1/"+totalChunks,List.of()));
        view.setItem(PAGE1_NAV+5,CoreUtil.named(Material.EMERALD,"Upgrade",List.of()));
        view.setItem(PAGE1_NAV+8,totalChunks>1?CoreUtil.named(Material.ARROW,"Next Page",List.of()):CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,"",List.of()));
    }
    private void mirrorPage1(Player target,Inventory view){
        ItemStack[] mirrored=new ItemStack[CHUNK];
        for(int i=0;i<CHUNK;i++)mirrored[i]=view.getItem(i);
        target.getEnderChest().setContents(mirrored);
    }
    /** Persists page 1's bonus slots (27-44) — the non-vanilla portion — using the exact same
     *  reused-page-1-partition scheme as reading (see openPage1()/class doc). Skips the lock-indicator
     *  placeholder (see lockedSlotItem()/refreshPage1Locks()) — it's a GUI-only overlay, never real player
     *  data, and must never be written to ender_chest_items or it would come back as a phantom item next
     *  load and permanently poison the tier migration's occupancy check. */
    private void persistPage1Bonus(String targetId,Inventory view){
        ItemStack[] bonus=new ItemStack[PAGE1_NAV];
        for(int i=CHUNK;i<PAGE1_NAV;i++){ItemStack item=view.getItem(i);bonus[i]=isLockedSlot(item)?null:item;}
        db.saveEnderPage(targetId,1,bonus);
    }

    /** Materializes chunk N (N>=2) into a real Inventory exactly once, then hands the SAME object to every
     *  subsequent viewer — owner or admin — until the last of them closes it. Bukkit already supports
     *  multiple simultaneous viewers of one Inventory (the same mechanism a shared chest uses), so once
     *  everyone is looking at the one object, live edits are inherently visible to everyone instantly;
     *  there is nothing left to snapshot or merge. Only ever called with a chunkNumber already validated
     *  against the player's real, purchased chunk count by the caller (see click()) — this method itself
     *  does not re-check that, so it must never be reachable with an unvalidated page number. */
    private Inventory sharedChunk(String playerId,int chunkNumber){
        String key=playerId+":"+chunkNumber;
        int totalChunks=totalPages(playerId);
        Inventory existing=liveChunks.get(key);
        if(existing!=null){refreshChunkLocks(existing,playerId,chunkNumber);refreshNav(existing,chunkNumber,totalChunks);return existing;}
        ItemStack[] data=db.enderPage(playerId,chunkNumber);
        Inventory inv=plugin.getServer().createInventory(new ChunkHolder(playerId,chunkNumber),UPGRADE_PAGE_SIZE,
                Component.text("Ender Storage • page "+chunkNumber+"/"+totalChunks,NamedTextColor.DARK_PURPLE));
        for(int slot=0;slot<UPGRADE_CHUNK&&slot<data.length;slot++)if(data[slot]!=null)inv.setItem(slot,data[slot].clone());
        for(int slot=UPGRADE_CHUNK;slot<UPGRADE_PAGE_SIZE;slot++)inv.setItem(slot,CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,"",List.of()));
        refreshChunkLocks(inv,playerId,chunkNumber);
        refreshNav(inv,chunkNumber,totalChunks);
        liveChunks.put(key,inv);
        return inv;
    }
    /** Same guarded lock/unlock overlay as refreshPage1Locks(), for page 2's storage slots (logical 45-89). */
    private void refreshChunkLocks(Inventory inv,String playerId,int chunkNumber){
        int unlocked=unlockedOnPage(playerId,chunkNumber);
        for(int slot=0;slot<UPGRADE_CHUNK;slot++){
            boolean shouldLock=slot>=unlocked;
            ItemStack current=inv.getItem(slot);
            boolean isLockPlaceholder=isLockedSlot(current);
            if(shouldLock&&(current==null||isLockPlaceholder))inv.setItem(slot,lockedSlotItem());
            else if(!shouldLock&&isLockPlaceholder)inv.setItem(slot,null);
        }
    }
    /** Re-applied every time a chunk is (re)handed out, not just at first materialization — otherwise a page
     *  cached before a purchase raised the total (e.g. an admin still inspecting it) would keep showing a
     *  Next button baked in against the OLD total. */
    private void refreshNav(Inventory inv,int chunkNumber,int totalChunks){
        inv.setItem(UPGRADE_CHUNK,CoreUtil.named(Material.ARROW,"Previous Page",List.of()));
        inv.setItem(UPGRADE_CHUNK+4,CoreUtil.named(Material.ENDER_EYE,"Page "+chunkNumber+"/"+totalChunks,List.of()));
        inv.setItem(UPGRADE_CHUNK+5,CoreUtil.named(Material.EMERALD,"Upgrade (owner only)",List.of()));
        inv.setItem(UPGRADE_CHUNK+8,chunkNumber<totalChunks?CoreUtil.named(Material.ARROW,"Next Page",List.of()):CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,"",List.of()));
    }
    /** Skips the lock-indicator placeholder the same way persistPage1Bonus() does — see that method's doc
     *  for why it must never be written to ender_chest_items. */
    private void persistChunk(String playerId,int chunkNumber,Inventory inventory){
        ItemStack[] part=new ItemStack[UPGRADE_CHUNK];
        for(int slot=0;slot<UPGRADE_CHUNK;slot++){ItemStack item=inventory.getItem(slot);part[slot]=isLockedSlot(item)?null:item;}
        db.saveEnderPage(playerId,chunkNumber,part);
    }

    private void openUpgrade(Player player,int returnPage){
        int current=tier(player);
        if(current>=MAX_TIER){CoreUtil.msg(player,"Ender Storage is fully expanded at "+capacity(player)+" slots.");return;}
        int next=current+1;
        double cost=tierPrice(next);
        Inventory inventory=plugin.getServer().createInventory(new UpgradeHolder(CoreUtil.id(player),returnPage),27,
                Component.text("Ender Storage Upgrade",NamedTextColor.DARK_PURPLE));
        inventory.setItem(11,CoreUtil.named(Material.LIME_CONCRETE,"Confirm",List.of(CoreUtil.money(cost),tierCapacity(next)+" slots total")));
        inventory.setItem(15,CoreUtil.named(Material.RED_CONCRETE,"Cancel",List.of()));
        player.openInventory(inventory);
    }

    private void purchase(Player player,int returnPage){
        int current=tier(player);
        if(current>=MAX_TIER){CoreUtil.msg(player,"Ender Storage is fully expanded at "+capacity(player)+" slots.");openPage(player,returnPage);return;}
        int next=current+1;
        double cost=tierPrice(next);
        if(cost<=0){CoreUtil.error(player,"That storage expansion is not configured.");openPage(player,returnPage);return;}
        if(!plugin.bank().allowNonessential(player,"Ender Storage upgrades")){openPage(player,returnPage);return;}
        if(!plugin.bank().payServer(player,cost,"SINK","ENDER_STORAGE_TIER_"+next)){CoreUtil.error(player,"You cannot afford this upgrade.");openPage(player,returnPage);return;}
        db.recordEconomy(CoreUtil.id(player),"UPGRADE_SINK",-cost,"ENDER_STORAGE_TIER_"+next);
        db.setEnderTier(CoreUtil.id(player),next);
        player.playSound(player.getLocation(),Sound.BLOCK_ENDER_CHEST_OPEN,1f,1.25f);
        CoreUtil.msg(player,"Ender Storage expanded to "+capacity(player)+" slots.");
        openPage(player,returnPage);
    }

    /** Used only by allContents() (net-worth/relic-tracing/other read-only callers) — reads chunk 1 (vanilla
     *  0-26 + bonus 27-44, see page1Contents()) and chunks 2+ from whichever is authoritative: a live shared
     *  Inventory if one is currently open, otherwise the database. */
    private ItemStack[] loadAll(Player player,int capacity){
        ItemStack[] flat=new ItemStack[capacity];
        int totalChunks=totalPages(player);
        String id=CoreUtil.id(player);
        for(int chunk=1;chunk<=totalChunks;chunk++){
            ItemStack[] source;
            if(chunk==1)source=page1Contents(player);
            else{
                Inventory live=liveChunks.get(id+":"+chunk);
                if(live!=null){source=new ItemStack[UPGRADE_CHUNK];for(int slot=0;slot<UPGRADE_CHUNK;slot++)source[slot]=live.getItem(slot);}
                else source=db.enderPage(id,chunk);
            }
            for(int slot=0;slot<UPGRADE_CHUNK&&slot<source.length&&(chunk-1)*UPGRADE_CHUNK+slot<flat.length;slot++)
                flat[(chunk-1)*UPGRADE_CHUNK+slot]=(source[slot]==null||isLockedSlot(source[slot]))?null:source[slot].clone();
        }
        return flat;
    }
    /** Page 1's full 45 logical slots (0-26 vanilla + 27-44 bonus): the live mirror if anyone currently has
     *  it open, otherwise a fresh read of the real Ender Chest plus the persisted bonus row. */
    private ItemStack[] page1Contents(Player player){
        String id=CoreUtil.id(player);
        Inventory live=liveMirrors.get(id);
        ItemStack[] result=new ItemStack[UPGRADE_CHUNK];
        if(live!=null){for(int i=0;i<UPGRADE_CHUNK;i++)result[i]=live.getItem(i);return result;}
        ItemStack[] real=player.getEnderChest().getContents();
        for(int i=0;i<CHUNK&&i<real.length;i++)result[i]=real[i];
        ItemStack[] bonus=db.enderPage(id,1);
        for(int i=CHUNK;i<PAGE1_NAV&&i<bonus.length;i++)result[i]=bonus[i];
        return result;
    }

    @EventHandler public void click(InventoryClickEvent event){
        if(!(event.getWhoClicked() instanceof Player viewer))return;
        if(event.getView().getTopInventory().getHolder(false) instanceof Page1Holder holder){
            boolean owner=holder.target().equals(CoreUtil.id(viewer));
            if(!owner&&!plugin.isAdmin(viewer))return;
            int slot=event.getRawSlot();
            Player target=resolveTarget(holder.target());
            if(target==null)return;
            /** Locked slots are filled with a real, non-stackable BARRIER placeholder rather than left
             *  empty — the same reason the nav row below is glass-filled rather than left empty (see the
             *  class doc on openPage1()): an occupied, non-stackable slot can never be a shift-click
             *  auto-placement target, so this alone already blocks the bypass the nav-row comment warns
             *  about. This check only has to stop a DIRECT click from picking up/swapping the barrier. */
            if(slot>=CHUNK&&slot<PAGE1_NAV&&isLockedSlot(event.getCurrentItem())){event.setCancelled(true);CoreUtil.error(viewer,"That slot is locked — upgrade your Ender Chest to unlock it.");return;}
            if(slot>=PAGE1_NAV&&slot<PAGE1_SIZE){
                event.setCancelled(true);
                if(slot==PAGE1_NAV+8&&totalPages(target)>1)viewer.openInventory(sharedChunk(holder.target(),2));
                else if(slot==PAGE1_NAV+5){
                    if(!owner){CoreUtil.error(viewer,"Only the owner can purchase upgrades.");return;}
                    openUpgrade(viewer,1);
                }
                return;
            }
            Inventory top=event.getView().getTopInventory();
            plugin.getServer().getScheduler().runTask(plugin,()->mirrorPage1(target,top));
            return;
        }
        InventoryHolder raw=event.getInventory().getHolder(false);
        if(raw instanceof UpgradeHolder holder){
            event.setCancelled(true);
            if(!holder.player().equals(CoreUtil.id(viewer)))return;
            if(event.getRawSlot()==11)purchase(viewer,holder.returnPage());
            else if(event.getRawSlot()==15)openPage(viewer,holder.returnPage());
            return;
        }
        if(raw instanceof ChunkHolder holder){
            int slot=event.getRawSlot();
            if(slot<0||slot>=UPGRADE_PAGE_SIZE){return;}
            boolean owner=holder.player().equals(CoreUtil.id(viewer));
            if(slot<UPGRADE_CHUNK){
                if(isLockedSlot(event.getCurrentItem())){event.setCancelled(true);CoreUtil.error(viewer,"That slot is locked — upgrade your Ender Chest to unlock it.");}
                return;
            }
            event.setCancelled(true);
            int realTotal=totalPages(holder.player());
            if(slot==UPGRADE_CHUNK&&holder.chunk()==2){
                Player target=resolveTarget(holder.player());
                if(target!=null)openPage1(viewer,target,realTotal);
            }
            else if(slot==UPGRADE_CHUNK&&holder.chunk()>2)viewer.openInventory(sharedChunk(holder.player(),holder.chunk()-1));
            else if(slot==UPGRADE_CHUNK+8&&holder.chunk()<realTotal)viewer.openInventory(sharedChunk(holder.player(),holder.chunk()+1));
            else if(slot==UPGRADE_CHUNK+5){
                if(!owner){CoreUtil.error(viewer,"Only the owner can purchase upgrades.");return;}
                openUpgrade(viewer,holder.chunk());
            }
            return;
        }
    }

    @EventHandler public void close(InventoryCloseEvent event){
        InventoryHolder raw=event.getInventory().getHolder(false);
        if(raw instanceof Page1Holder holder){
            if(event.getViewers().isEmpty()){
                Player target=resolveTarget(holder.target());
                if(target!=null)mirrorPage1(target,event.getInventory());
                persistPage1Bonus(holder.target(),event.getInventory());
                liveMirrors.remove(holder.target());
                unloadIfDone(holder.target());
            }
            return;
        }
        if(raw instanceof ChunkHolder holder&&event.getViewers().isEmpty()){
            persistChunk(holder.player(),holder.chunk(),event.getInventory());
            liveChunks.remove(holder.player()+":"+holder.chunk());
            unloadIfDone(holder.player());
        }
    }
    @EventHandler public void drag(InventoryDragEvent event){
        if(event.getWhoClicked() instanceof Player viewer&&event.getView().getTopInventory().getHolder(false) instanceof Page1Holder holder){
            Inventory top=event.getView().getTopInventory();
            if(event.getRawSlots().stream().anyMatch(slot->(slot>=PAGE1_NAV&&slot<PAGE1_SIZE)||(slot>=CHUNK&&slot<PAGE1_NAV&&isLockedSlot(top.getItem(slot))))){event.setCancelled(true);return;}
            Player target=resolveTarget(holder.target());
            if(target==null)return;
            plugin.getServer().getScheduler().runTask(plugin,()->mirrorPage1(target,top));
            return;
        }
        InventoryHolder raw=event.getInventory().getHolder(false);
        if(raw instanceof ChunkHolder&&event.getRawSlots().stream().anyMatch(slot->(slot>=UPGRADE_CHUNK&&slot<UPGRADE_PAGE_SIZE)||(slot<UPGRADE_CHUNK&&isLockedSlot(event.getInventory().getItem(slot)))))event.setCancelled(true);
    }
    @EventHandler public void quit(PlayerQuitEvent event){currentPages.remove(event.getPlayer().getUniqueId());}

    void combatStarted(Player player){
        Inventory top=player.getOpenInventory().getTopInventory();
        InventoryHolder holder=top.getHolder(false);
        if(holder instanceof UpgradeHolder||holder instanceof ChunkHolder||holder instanceof Page1Holder||isPersonalStorage(top,player)){player.closeInventory();blocked(player);}
    }
    private boolean blocked(Player player){
        if(plugin.privileged(player))return false;
        long remaining=plugin.teleports().combatRemaining(player);
        if(remaining<=0)return false;
        CoreUtil.error(player,"You cannot use your Ender Chest while in combat. "+remaining+"s remaining.");
        return true;
    }
}
