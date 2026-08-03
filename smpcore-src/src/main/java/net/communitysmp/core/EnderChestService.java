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
        openPage1(admin,target,chunks(target));
    }

    private int chunks(String playerId){return Math.max(1,Math.min(maxChunks(),db.enderPages(playerId)));}
    private int chunks(Player player){return chunks(CoreUtil.id(player));}
    /** Every chunk — including 1, now that it has bonus slots alongside the vanilla 27 — holds UPGRADE_CHUNK
     *  logical slots, so this is uniform. */
    private int capacity(Player player){return chunks(player)*UPGRADE_CHUNK;}
    private int maxChunks(){return Math.max(2,plugin.getConfig().getInt("ender-chest.max-upgrades",2));}
    int displayPages(Player player){return chunks(player);}

    /** Bare /ec always lands on page 1 — the familiar "this is my Ender Chest" view. */
    private void open(Player player){openPage(player,1);}

    private void openPage(Player player,int requested){
        if(blocked(player))return;
        int totalChunks=chunks(player),page=Math.max(1,Math.min(requested,totalChunks));
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
        refreshPage1Nav(view,totalChunks);
        viewer.openInventory(view);
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
     *  reused-page-1-partition scheme as reading (see openPage1()/class doc). */
    private void persistPage1Bonus(String targetId,Inventory view){
        ItemStack[] bonus=new ItemStack[PAGE1_NAV];
        for(int i=CHUNK;i<PAGE1_NAV;i++)bonus[i]=view.getItem(i);
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
        int totalChunks=chunks(playerId);
        Inventory existing=liveChunks.get(key);
        if(existing!=null){refreshNav(existing,chunkNumber,totalChunks);return existing;}
        ItemStack[] data=db.enderPage(playerId,chunkNumber);
        Inventory inv=plugin.getServer().createInventory(new ChunkHolder(playerId,chunkNumber),UPGRADE_PAGE_SIZE,
                Component.text("Ender Storage • page "+chunkNumber+"/"+totalChunks,NamedTextColor.DARK_PURPLE));
        for(int slot=0;slot<UPGRADE_CHUNK&&slot<data.length;slot++)if(data[slot]!=null)inv.setItem(slot,data[slot].clone());
        for(int slot=UPGRADE_CHUNK;slot<UPGRADE_PAGE_SIZE;slot++)inv.setItem(slot,CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,"",List.of()));
        refreshNav(inv,chunkNumber,totalChunks);
        liveChunks.put(key,inv);
        return inv;
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
    private void persistChunk(String playerId,int chunkNumber,Inventory inventory){
        ItemStack[] part=new ItemStack[UPGRADE_CHUNK];
        for(int slot=0;slot<UPGRADE_CHUNK;slot++)part[slot]=inventory.getItem(slot);
        db.saveEnderPage(playerId,chunkNumber,part);
    }

    private void openUpgrade(Player player,int returnPage){
        int current=chunks(player);
        if(current>=maxChunks()){CoreUtil.msg(player,"Ender Storage is fully expanded at "+capacity(player)+" slots.");return;}
        int next=current+1;
        double cost=plugin.getConfig().getDouble("ender-chest.upgrades."+next);
        int nextCapacity=next*UPGRADE_CHUNK;
        Inventory inventory=plugin.getServer().createInventory(new UpgradeHolder(CoreUtil.id(player),returnPage),27,
                Component.text("Ender Storage Upgrade",NamedTextColor.DARK_PURPLE));
        inventory.setItem(11,CoreUtil.named(Material.LIME_CONCRETE,"Confirm",List.of(CoreUtil.money(cost),nextCapacity+" slots total")));
        inventory.setItem(15,CoreUtil.named(Material.RED_CONCRETE,"Cancel",List.of()));
        player.openInventory(inventory);
    }

    private void purchase(Player player,int returnPage){
        int current=chunks(player);
        if(current>=maxChunks()){CoreUtil.msg(player,"Ender Storage is fully expanded at "+capacity(player)+" slots.");openPage(player,returnPage);return;}
        int next=current+1;
        double cost=plugin.getConfig().getDouble("ender-chest.upgrades."+next);
        if(cost<=0){CoreUtil.error(player,"That storage expansion is not configured.");openPage(player,returnPage);return;}
        if(!plugin.bank().allowNonessential(player,"Ender Storage upgrades")){openPage(player,returnPage);return;}
        if(!plugin.bank().payServer(player,cost,"SINK","ENDER_STORAGE_"+next)){CoreUtil.error(player,"You cannot afford this upgrade.");openPage(player,returnPage);return;}
        db.recordEconomy(CoreUtil.id(player),"UPGRADE_SINK",-cost,"ENDER_STORAGE_"+next);
        db.setEnderPages(CoreUtil.id(player),next);
        player.playSound(player.getLocation(),Sound.BLOCK_ENDER_CHEST_OPEN,1f,1.25f);
        CoreUtil.msg(player,"Ender Storage expanded to "+capacity(player)+" slots.");
        openPage(player,returnPage);
    }

    /** Used only by allContents() (net-worth/relic-tracing/other read-only callers) — reads chunk 1 (vanilla
     *  0-26 + bonus 27-44, see page1Contents()) and chunks 2+ from whichever is authoritative: a live shared
     *  Inventory if one is currently open, otherwise the database. */
    private ItemStack[] loadAll(Player player,int capacity){
        ItemStack[] flat=new ItemStack[capacity];
        int totalChunks=chunks(player);
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
                flat[(chunk-1)*UPGRADE_CHUNK+slot]=source[slot]==null?null:source[slot].clone();
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
            if(slot>=PAGE1_NAV&&slot<PAGE1_SIZE){
                event.setCancelled(true);
                if(slot==PAGE1_NAV+8&&chunks(target)>1)viewer.openInventory(sharedChunk(holder.target(),2));
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
            if(slot<UPGRADE_CHUNK)return;
            event.setCancelled(true);
            int realTotal=chunks(holder.player());
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
            if(event.getRawSlots().stream().anyMatch(slot->slot>=PAGE1_NAV&&slot<PAGE1_SIZE)){event.setCancelled(true);return;}
            Player target=resolveTarget(holder.target());
            if(target==null)return;
            Inventory top=event.getView().getTopInventory();
            plugin.getServer().getScheduler().runTask(plugin,()->mirrorPage1(target,top));
            return;
        }
        InventoryHolder raw=event.getInventory().getHolder(false);
        if(raw instanceof ChunkHolder&&event.getRawSlots().stream().anyMatch(slot->slot>=UPGRADE_CHUNK&&slot<UPGRADE_PAGE_SIZE))event.setCancelled(true);
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
