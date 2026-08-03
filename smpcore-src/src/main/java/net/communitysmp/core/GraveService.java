package net.communitysmp.core;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

final class GraveService implements Listener {
    private enum View { LIST, CONTENTS }
    private record Holder(View view,long graveId) implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private final SMPCore plugin;
    private final Database db;
    private final NamespacedKey graveKey,compassKey,compassOwnerKey,visualKey,graveLootKey;
    private final Map<Long,UUID> viewers=new HashMap<>();
    private final Set<String> notifiedLooters=new HashSet<>();
    private final Map<UUID,UUID> ownerVisuals=new HashMap<>();
    private final Set<UUID> pendingCompasses=new HashSet<>();
    private BukkitTask cleanup,tracking;

    GraveService(SMPCore plugin){
        this.plugin=plugin;this.db=plugin.db();
        graveKey=new NamespacedKey(plugin,"grave_id");compassKey=new NamespacedKey(plugin,"grave_compass");
        compassOwnerKey=new NamespacedKey(plugin,"grave_compass_owner");visualKey=new NamespacedKey(plugin,"grave_visual");graveLootKey=new NamespacedKey(plugin,"grave_loot");
        plugin.getServer().getScheduler().runTaskLater(plugin,this::restoreLoadedMarkers,40L);
        cleanup=plugin.getServer().getScheduler().runTaskTimer(plugin,this::cleanup,1200L,6000L);
        tracking=plugin.getServer().getScheduler().runTaskTimer(plugin,this::trackingTick,40L,40L);
    }

    void shutdown(){
        if(cleanup!=null)cleanup.cancel();if(tracking!=null)tracking.cancel();
        for(var entry:viewers.entrySet()){Player player=plugin.getServer().getPlayer(entry.getValue());if(player!=null&&player.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder holder&&holder.view()==View.CONTENTS&&holder.graveId()==entry.getKey())save(player.getOpenInventory().getTopInventory(),holder.graveId());}
        viewers.clear();for(UUID id:ownerVisuals.values()){Entity entity=plugin.getServer().getEntity(id);if(entity!=null)entity.remove();}ownerVisuals.clear();
    }

    /** A death location can legitimately sit below the world's minimum build height — vanilla void damage
     *  doesn't necessarily kill on the first tick, so a falling player can die well under min-height before
     *  ever landing on anything. Spawning the marker armor stand there throws (out-of-bounds chunk section),
     *  which used to leave the grave permanently marker-less: tracked in /graves, DB row intact, but with no
     *  physical entity to right-click, so the contents GUI could never be opened. Clamping to the world's own
     *  floor (getMinHeight() — confirmed empirically as Y0 in the End, not Y1) keeps the grave at the correct
     *  X/Z and makes it a normal, lootable grave instead of a permanently stuck one. */
    private Location clampToWorld(Location location){
        World world=location.getWorld();
        if(location.getY()>=world.getMinHeight()&&location.getY()<world.getMaxHeight())return location;
        double y=Math.min(Math.max(location.getY(),world.getMinHeight()),world.getMaxHeight()-1);
        return new Location(world,location.getX(),y,location.getZ(),location.getYaw(),location.getPitch());
    }

    boolean create(Player owner,List<ItemStack> drops,Location location){
        List<ItemStack> items=drops.stream().filter(Objects::nonNull).filter(item->!item.getType().isAir()&&item.getAmount()>0&&!isCompass(item)).map(ItemStack::clone).toList();
        drops.removeIf(this::isCompass);if(items.isEmpty())return false;
        location=clampToWorld(location);
        ProfileProperty textures=owner.getPlayerProfile().getProperties().stream().filter(property->property.getName().equals("textures")).findFirst().orElse(null);
        long hours=Math.max(1,plugin.getConfig().getLong("graves.lifetime-hours",48)),id=db.createGrave(
                CoreUtil.id(owner),owner.getUniqueId().toString(),owner.getName(),plugin.nicknames().displayName(owner),
                textures==null?null:textures.getValue(),textures==null?null:textures.getSignature(),
                location,System.currentTimeMillis()+hours*3600000L,items);
        drops.clear();Database.GraveRow grave=db.grave(id);spawnMarker(grave);select(owner,grave,false);return true;
    }

    boolean isMarker(Entity entity){return entity.getPersistentDataContainer().has(graveKey,PersistentDataType.LONG)||entity.getPersistentDataContainer().has(visualKey,PersistentDataType.BYTE);}
    boolean isCompass(ItemStack item){return item!=null&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().has(compassKey,PersistentDataType.LONG);}
    /** Lets other services (ShardService, for account-bound items) recognise "this click is inside a grave's
     *  loot GUI" so their own general-purpose ownership guards can step aside for the one sanctioned transfer
     *  path — looting a defeated player's grave — without loosening those guards anywhere else. Safe to allow
     *  broadly here: GraveService's own click() handler already blocks every action that would PLACE an item
     *  into a grave (PLACE_ALL/PLACE_ONE/PLACE_SOME/SWAP_WITH_CURSOR/HOTBAR_SWAP/COLLECT_TO_CURSOR/CLONE_STACK
     *  — see click()), so anything reaching this far in a CONTENTS view is inherently being taken OUT, never
     *  put in. */
    boolean isGraveContents(Inventory inventory){return inventory!=null&&inventory.getHolder(false) instanceof Holder holder&&holder.view()==View.CONTENTS;}
    /** One-shot marker on a real dropped Item entity, set only by spill() (breaking a grave open rather than
     *  looting its GUI). Same purpose as isGraveContents() for the ground-pickup path. */
    boolean isGraveLoot(Item item){return item!=null&&item.getPersistentDataContainer().has(graveLootKey,PersistentDataType.BYTE);}

    boolean command(Player player){openList(player);return true;}

    @EventHandler public void join(PlayerJoinEvent event){plugin.getServer().getScheduler().runTaskLater(plugin,()->{Player player=event.getPlayer();for(var entry:ownerVisuals.entrySet()){Entity visual=plugin.getServer().getEntity(entry.getValue());if(visual!=null&&entry.getKey().equals(player.getUniqueId()))player.showEntity(plugin,visual);else if(visual!=null)player.hideEntity(plugin,visual);}syncCompass(player,selected(player));},20L);}
    @EventHandler public void respawn(PlayerRespawnEvent event){
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{
            List<Database.GraveRow> graves=db.graves(event.getPlayer().getUniqueId());if(graves.isEmpty())return;
            select(event.getPlayer(),graves.getFirst(),false);CoreUtil.msg(event.getPlayer(),"Your newest grave is now tracked. Use /graves to switch or stop.");
        },10L);
    }

    @EventHandler public void interact(PlayerInteractEntityEvent event){
        Long id=event.getRightClicked().getPersistentDataContainer().get(graveKey,PersistentDataType.LONG);if(id==null)return;event.setCancelled(true);openContents(event.getPlayer(),id);
    }

    @EventHandler public void damage(EntityDamageByEntityEvent event){
        if(event.getEntity().getPersistentDataContainer().has(visualKey,PersistentDataType.BYTE)){event.setCancelled(true);return;}
        Long id=event.getEntity().getPersistentDataContainer().get(graveKey,PersistentDataType.LONG);if(id==null)return;event.setCancelled(true);Player player=damager(event.getDamager());if(player==null)return;
        if(isBeingLooted(id)){CoreUtil.error(player,"This grave is currently being looted.");return;}
        Database.GraveRow grave=db.grave(id);if(grave!=null&&!owns(player,grave))notifyOwner(grave,"Your grave #"+id+" was broken open by "+plugin.nicknames().displayName(player)+".");
        spill(id,event.getEntity().getLocation());CoreUtil.msg(player,"Grave broken; its contents were released.");
    }

    @EventHandler public void click(InventoryClickEvent event){
        if(event.getInventory().getHolder(false) instanceof Holder holder&&event.getWhoClicked() instanceof Player player){
            if(holder.view()==View.LIST){event.setCancelled(true);if(event.getRawSlot()==49){stop(player,true);return;}if(event.getRawSlot()==53){Database.GraveRow grave=selected(player);if(grave!=null)syncCompass(player,grave);openList(player);return;}ItemStack clicked=event.getCurrentItem();if(clicked==null||!clicked.hasItemMeta())return;Long id=clicked.getItemMeta().getPersistentDataContainer().get(graveKey,PersistentDataType.LONG);if(id!=null){Database.GraveRow grave=db.grave(id);if(grave!=null&&owns(player,grave)){select(player,grave,true);openList(player);}}return;}
            int top=event.getInventory().getSize(),raw=event.getRawSlot();if(raw>=top){if(event.isShiftClick())event.setCancelled(true);return;}
            InventoryAction action=event.getAction();if(Set.of(InventoryAction.PLACE_ALL,InventoryAction.PLACE_ONE,InventoryAction.PLACE_SOME,InventoryAction.SWAP_WITH_CURSOR,InventoryAction.HOTBAR_SWAP,InventoryAction.COLLECT_TO_CURSOR,InventoryAction.CLONE_STACK).contains(action)){event.setCancelled(true);return;}
            Database.GraveRow grave=db.grave(holder.graveId());if(grave!=null&&!owns(player,grave)&&event.getCurrentItem()!=null){String notice=grave.id()+":"+player.getUniqueId();if(notifiedLooters.add(notice))notifyOwner(grave,"Your grave #"+grave.id()+" is being looted by "+plugin.nicknames().displayName(player)+".");}
            plugin.getServer().getScheduler().runTask(plugin,()->save(event.getInventory(),holder.graveId()));return;
        }
        if(!(event.getWhoClicked() instanceof Player player))return;
        ItemStack current=event.getCurrentItem(),cursor=event.getCursor();boolean protectedItem=isCompass(current)||isCompass(cursor);
        if(protectedItem&&event.getView().getTopInventory().getType()!=InventoryType.CRAFTING&&event.getView().getTopInventory().getType()!=InventoryType.PLAYER){
            if(event.getRawSlot()<event.getView().getTopInventory().getSize()||event.isShiftClick())event.setCancelled(true);
        }
    }

    @EventHandler public void drag(InventoryDragEvent event){
        if(event.getInventory().getHolder(false) instanceof Holder holder&&holder.view()==View.CONTENTS&&event.getRawSlots().stream().anyMatch(slot->slot<event.getInventory().getSize()))event.setCancelled(true);
        if(isCompass(event.getOldCursor())&&event.getRawSlots().stream().anyMatch(slot->slot<event.getView().getTopInventory().getSize()))event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event){if(event.getInventory().getHolder(false) instanceof Holder holder&&holder.view()==View.CONTENTS){save(event.getInventory(),holder.graveId());viewers.remove(holder.graveId());}}
    @EventHandler public void drop(PlayerDropItemEvent event){if(isCompass(event.getItemDrop().getItemStack())){event.setCancelled(true);CoreUtil.error(event.getPlayer(),"The bound Grave Compass cannot be dropped.");}}
    @EventHandler public void entitiesLoad(EntitiesLoadEvent event){for(Entity entity:event.getEntities()){if(entity.getPersistentDataContainer().has(visualKey,PersistentDataType.BYTE)){entity.remove();continue;}Long id=entity.getPersistentDataContainer().get(graveKey,PersistentDataType.LONG);if(id==null)continue;Database.GraveRow grave=db.grave(id);if(grave==null||grave.expiresAt()<=System.currentTimeMillis())entity.remove();else db.updateGraveMarker(id,entity.getUniqueId().toString());}}
    @EventHandler public void chunkLoad(ChunkLoadEvent event){plugin.getServer().getScheduler().runTask(plugin,()->restoreMarkersIn(event.getChunk().getWorld(),event.getChunk().getX(),event.getChunk().getZ()));}

    private void openList(Player player){
        List<Database.GraveRow> graves=db.graves(player.getUniqueId());Inventory inventory=plugin.getServer().createInventory(new Holder(View.LIST,0),54,Component.text("Your Graves",NamedTextColor.DARK_GRAY));Long selected=selectedId(player);int slot=0;
        for(Database.GraveRow grave:graves){if(slot>=45)break;Location location=grave.location();double distance=location!=null&&location.getWorld().equals(player.getWorld())?player.getLocation().distance(location):-1;long now=System.currentTimeMillis();
            ItemStack icon=new ItemStack(Objects.equals(selected,grave.id())?Material.RECOVERY_COMPASS:Material.PLAYER_HEAD);ItemMeta meta=icon.getItemMeta();meta.displayName(Component.text((Objects.equals(selected,grave.id())?"Tracked • ":"")+"Grave #"+grave.id(),NamedTextColor.GOLD));meta.lore(List.of(Component.text(dimension(grave.world()),NamedTextColor.GRAY),Component.text(distance>=0?"Distance: "+Math.round(distance)+"m":"Distance: another dimension",NamedTextColor.GRAY),Component.text("Age: "+duration(now-grave.createdAt()),NamedTextColor.GRAY),Component.text("Remaining: "+duration(grave.expiresAt()-now),NamedTextColor.GRAY),Component.text("Click to track",NamedTextColor.YELLOW)));meta.getPersistentDataContainer().set(graveKey,PersistentDataType.LONG,grave.id());icon.setItemMeta(meta);inventory.setItem(slot++,icon);
        }
        ItemStack stop=new ItemStack(Material.BARRIER);ItemMeta stopMeta=stop.getItemMeta();stopMeta.displayName(Component.text("Stop Tracking",NamedTextColor.RED));stop.setItemMeta(stopMeta);inventory.setItem(49,stop);
        if(pendingCompasses.contains(player.getUniqueId())){ItemStack receive=new ItemStack(Material.COMPASS);ItemMeta meta=receive.getItemMeta();meta.displayName(Component.text("Receive Grave Compass",NamedTextColor.AQUA));meta.lore(List.of(Component.text("Free one inventory slot, then click.",NamedTextColor.GRAY)));receive.setItemMeta(meta);inventory.setItem(53,receive);}
        player.openInventory(inventory);
    }

    private void openContents(Player player,long id){
        Database.GraveRow grave=db.grave(id);if(grave==null||grave.expiresAt()<=System.currentTimeMillis()){CoreUtil.error(player,"That grave has expired.");if(grave!=null)loseGraveRelics(grave);remove(grave);return;}
        UUID current=viewers.get(id);if(current!=null&&!current.equals(player.getUniqueId())){CoreUtil.error(player,"Someone else is already looting this grave.");return;}
        List<ItemStack> items=db.graveItems(id);if(items.isEmpty()){remove(grave);return;}Holder holder=new Holder(View.CONTENTS,id);Inventory inventory=plugin.getServer().createInventory(holder,54,Component.text(publicName(grave)+"'s Grave",NamedTextColor.DARK_GRAY));for(int slot=0;slot<Math.min(54,items.size());slot++)inventory.setItem(slot,items.get(slot).clone());viewers.put(id,player.getUniqueId());player.openInventory(inventory);
    }

    private void save(Inventory inventory,long id){
        Database.GraveRow grave=db.grave(id);if(grave==null)return;List<ItemStack> items=new ArrayList<>();for(ItemStack item:inventory.getStorageContents())if(item!=null&&!item.getType().isAir())items.add(item.clone());db.saveGraveItems(id,items);if(items.isEmpty()){removeMarker(grave);viewers.remove(id);clearTracking(id);for(HumanEntity viewer:List.copyOf(inventory.getViewers()))viewer.closeInventory();}
    }

    private void spill(long id,Location location){Database.GraveRow grave=db.grave(id);if(grave==null||isBeingLooted(id))return;for(ItemStack item:db.graveItems(id))if(!isCompass(item)){Item dropped=location.getWorld().dropItemNaturally(location,item);dropped.getPersistentDataContainer().set(graveLootKey,PersistentDataType.BYTE,(byte)1);}remove(grave);}
    private void remove(Database.GraveRow grave){if(grave==null)return;removeMarker(grave);db.deleteGrave(grave.id());viewers.remove(grave.id());clearTracking(grave.id());}
    /** grave_items cascade-deletes with its grave (see schema) the instant a grave expires — silent and
     *  total, unlike spill() which relocates everything to a real dropped item first. A relic caught in
     *  that cascade would otherwise stay permanently ACTIVE, pointing at a physical item that no longer
     *  exists anywhere. Must run BEFORE remove()/deleteGrave() while the items are still readable. Not
     *  called from spill() — there the item survives as a real dropped Item, so despawn/pickup already
     *  handle it correctly and marking it lost here too would be a false positive. */
    private void loseGraveRelics(Database.GraveRow grave){
        if(grave==null||plugin.relics()==null)return;
        for(ItemStack item:db.graveItems(grave.id())){
            String relicKey=plugin.relics().keyOf(item);
            if(relicKey!=null)plugin.relics().itemLostByKey(relicKey);
        }
    }
    private void cleanup(){for(Database.GraveRow grave:db.expiredGraves())if(!viewers.containsKey(grave.id())){loseGraveRelics(grave);remove(grave);}restoreLoadedMarkers();}

    private String trackingOwner(Player player){return "uuid:"+player.getUniqueId();}
    private void select(Player player,Database.GraveRow grave,boolean message){db.preference(trackingOwner(player),"tracked_grave",Long.toString(grave.id()));if(plugin.settings().graveTracking(player))syncCompass(player,grave);if(message)CoreUtil.msg(player,"Now tracking grave #"+grave.id()+".");}
    private Long selectedId(Player player){String value=db.preference(trackingOwner(player),"tracked_grave");if(value==null){value=db.preference(CoreUtil.id(player),"tracked_grave");if(value!=null)db.preference(trackingOwner(player),"tracked_grave",value);}if(value==null)return null;try{return Long.parseLong(value);}catch(NumberFormatException ignored){return null;}}
    private Database.GraveRow selected(Player player){Long id=selectedId(player);if(id==null)return null;Database.GraveRow grave=db.grave(id);return grave!=null&&owns(player,grave)&&grave.expiresAt()>System.currentTimeMillis()?grave:null;}
    private void stop(Player player,boolean message){db.preference(trackingOwner(player),"tracked_grave","");removeCompasses(player);removeVisual(player);pendingCompasses.remove(player.getUniqueId());if(message)CoreUtil.msg(player,"Grave tracking stopped.");}
    private void clearTracking(long graveId){for(Player player:plugin.getServer().getOnlinePlayers())if(Objects.equals(selectedId(player),graveId))stop(player,true);}

    private void trackingTick(){
        for(Player player:plugin.getServer().getOnlinePlayers()){
            if(plugin.spawners().isHovering(player)||plugin.isTeleporting(player))continue;
            if(!plugin.settings().graveTracking(player)){removeCompasses(player);removeVisual(player);continue;}
            if(plugin.teleports().combatRemaining(player)>0||plugin.isTeleporting(player))continue;
            Database.GraveRow grave=selected(player);if(grave==null){if(selectedId(player)!=null)stop(player,false);continue;}Location target=grave.location();if(target==null)continue;
            if(player.getWorld().equals(target.getWorld())){double distance=player.getLocation().distance(target);player.sendActionBar(Component.text("Grave "+direction(player.getLocation(),target)+" • "+Math.round(distance)+"m • "+dimension(grave.world()),NamedTextColor.AQUA));if(distance<=128&&target.getWorld().isChunkLoaded(target.getBlockX()>>4,target.getBlockZ()>>4)){double step=plugin.settings().particleScale(player)<.5?1.3:.65;for(double y=.3;y<=5.3;y+=step)player.spawnParticle(Particle.SOUL_FIRE_FLAME,target.clone().add(0,y,0),1,0,0,0,0);ensureVisual(player,grave);}}
            else{player.sendActionBar(Component.text("Grave • "+dimension(grave.world())+" • another dimension",NamedTextColor.AQUA));removeVisual(player);}
            syncCompass(player,grave);
        }
    }

    private void syncCompass(Player player,Database.GraveRow grave){
        int first=-1;for(int i=0;i<player.getInventory().getSize();i++)if(isCompass(player.getInventory().getItem(i))){if(first<0)first=i;else player.getInventory().setItem(i,null);}
        if(grave==null){if(first>=0)player.getInventory().setItem(first,null);pendingCompasses.remove(player.getUniqueId());return;}
        ItemStack existing=first<0?null:player.getInventory().getItem(first);Long existingId=existing==null?null:existing.getItemMeta().getPersistentDataContainer().get(compassKey,PersistentDataType.LONG);Location target=grave.location();boolean correctWorld=target!=null&&target.getWorld().equals(player.getWorld());
        if(Objects.equals(existingId,grave.id())&&existing.getItemMeta() instanceof CompassMeta old&&old.hasLodestone()==correctWorld){pendingCompasses.remove(player.getUniqueId());return;}
        ItemStack item=new ItemStack(Material.COMPASS);if(!(item.getItemMeta() instanceof CompassMeta meta)){plugin.getLogger().warning("CompassMeta is unavailable; grave action-bar tracking remains active.");return;}meta.displayName(Component.text("Bound Grave Compass",NamedTextColor.AQUA));meta.lore(List.of(Component.text("Tracks grave #"+grave.id(),NamedTextColor.GRAY),Component.text("Use /graves to change target",NamedTextColor.DARK_GRAY)));if(correctWorld){meta.setLodestone(target);meta.setLodestoneTracked(false);}meta.getPersistentDataContainer().set(compassKey,PersistentDataType.LONG,grave.id());meta.getPersistentDataContainer().set(compassOwnerKey,PersistentDataType.STRING,player.getUniqueId().toString());item.setItemMeta(meta);
        if(first>=0){player.getInventory().setItem(first,item);pendingCompasses.remove(player.getUniqueId());}
        else{HashMap<Integer,ItemStack> left=player.getInventory().addItem(item);if(left.isEmpty())pendingCompasses.remove(player.getUniqueId());else pendingCompasses.add(player.getUniqueId());}
    }
    private void removeCompasses(Player player){for(int i=0;i<player.getInventory().getSize();i++)if(isCompass(player.getInventory().getItem(i)))player.getInventory().setItem(i,null);}

    private void ensureVisual(Player owner,Database.GraveRow grave){
        UUID old=ownerVisuals.get(owner.getUniqueId());Entity existing=old==null?null:plugin.getServer().getEntity(old);Long existingGrave=existing==null?null:existing.getPersistentDataContainer().get(graveKey,PersistentDataType.LONG);if(existing!=null&&Objects.equals(existingGrave,grave.id()))return;if(existing!=null)existing.remove();
        Location location=grave.location();if(location==null)return;ArmorStand visual=location.getWorld().spawn(location.clone().add(0,.1,0),ArmorStand.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,stand->{stand.setInvisible(true);stand.setSmall(true);stand.setGravity(false);stand.setBasePlate(false);stand.setPersistent(false);stand.setInvulnerable(true);stand.setGlowing(true);stand.getPersistentDataContainer().set(visualKey,PersistentDataType.BYTE,(byte)1);stand.getPersistentDataContainer().set(graveKey,PersistentDataType.LONG,grave.id());stand.getEquipment().setHelmet(head(grave));});
        for(Player online:plugin.getServer().getOnlinePlayers())if(!online.getUniqueId().equals(owner.getUniqueId()))online.hideEntity(plugin,visual);owner.showEntity(plugin,visual);ownerVisuals.put(owner.getUniqueId(),visual.getUniqueId());
    }
    private void removeVisual(Player owner){UUID id=ownerVisuals.remove(owner.getUniqueId());Entity entity=id==null?null:plugin.getServer().getEntity(id);if(entity!=null)entity.remove();}

    /** Admin recovery path for graves stuck with no physical marker (old void-Y deaths, or any other
     *  transient spawn failure) instead of waiting for their chunk to load naturally. Never deletes a row —
     *  items stay in SQLite regardless of outcome; this only ever tries to give a stuck grave back its
     *  normal, lootable marker. */
    int repairBrokenMarkers(){
        int fixed=0;
        for(Database.GraveRow grave:db.allGraves()){
            if(grave.expiresAt()<=System.currentTimeMillis()||grave.markerUuid()!=null)continue;
            Location location=resolveLocation(grave);if(location==null)continue;
            World world=location.getWorld();int cx=location.getBlockX()>>4,cz=location.getBlockZ()>>4;
            boolean wasLoaded=world.isChunkLoaded(cx,cz);if(!wasLoaded)world.getChunkAt(cx,cz).load();
            spawnMarker(grave);
            if(db.grave(grave.id())!=null&&db.grave(grave.id()).markerUuid()!=null)fixed++;
            if(!wasLoaded)world.getChunkAt(cx,cz).unload();
        }
        cachedGravesAt=0;
        return fixed;
    }

    private List<Database.GraveRow> cachedGraves=List.of();
    private long cachedGravesAt=0;
    private List<Database.GraveRow> allGravesCached(){long now=System.currentTimeMillis();if(now-cachedGravesAt>5000){cachedGraves=db.allGraves();cachedGravesAt=now;}return cachedGraves;}
    private void restoreLoadedMarkers(){for(Database.GraveRow grave:allGravesCached()){if(grave.expiresAt()<=System.currentTimeMillis())continue;Location location=grave.location();if(location==null||!location.getWorld().isChunkLoaded(location.getBlockX()>>4,location.getBlockZ()>>4))continue;Entity marker=marker(grave);if(marker==null)spawnMarker(grave);}}
    private void restoreMarkersIn(World world,int chunkX,int chunkZ){for(Database.GraveRow grave:allGravesCached()){if(grave.expiresAt()<=System.currentTimeMillis()||!grave.world().equals(world.getName()))continue;Location location=grave.location();if(location!=null&&(location.getBlockX()>>4)==chunkX&&(location.getBlockZ()>>4)==chunkZ&&marker(grave)==null)spawnMarker(grave);}}
    /** restoreLoadedMarkers()/restoreMarkersIn() already retry this on every 5-minute cleanup pass and on
     *  every relevant chunk load for any grave with no marker — so clamping here, not just in create(),
     *  self-heals graves that got stuck marker-less before this fix existed, as soon as their chunk is next
     *  loaded. repairBrokenMarkers() below force-loads that chunk instead of waiting on it. */
    /** grave.location() returns null only when the grave's stored world no longer exists at all (an admin
     *  deleted/renamed a dimension) — the one case Y-clamping can't reach, since there's no world to build a
     *  Location in. Relocating to the configured spawn's world keeps the grave real and lootable instead of
     *  becoming a stale database record with no recoverable physical grave. */
    private Location resolveLocation(Database.GraveRow grave){
        Location location=grave.location();if(location!=null)return location;
        Location fallback=plugin.teleports().spawn();if(fallback==null||fallback.getWorld()==null)return null;
        db.updateGraveLocation(grave.id(),fallback);return fallback;
    }
    private void spawnMarker(Database.GraveRow grave){
        if(grave==null)return;Location location=resolveLocation(grave);if(location==null)return;
        Location safe=clampToWorld(location);if(safe.getY()!=location.getY()){db.updateGraveLocation(grave.id(),safe);location=safe;}
        try{
            ArmorStand stand=location.getWorld().spawn(location.clone().add(0,.1,0),ArmorStand.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,armor->{armor.setInvisible(true);armor.setSmall(true);armor.setGravity(false);armor.setBasePlate(false);armor.setArms(false);armor.setPersistent(true);armor.setCustomNameVisible(true);armor.customName(Component.text(publicName(grave)+"'s Grave",NamedTextColor.GRAY));armor.getPersistentDataContainer().set(graveKey,PersistentDataType.LONG,grave.id());armor.getEquipment().setHelmet(head(grave));});
            if(stand.isValid())db.updateGraveMarker(grave.id(),stand.getUniqueId().toString());
        }catch(RuntimeException error){plugin.getLogger().warning("Could not create marker for grave #"+grave.id()+" at "+grave.world()+" "+Math.round(grave.x())+" "+Math.round(grave.y())+" "+Math.round(grave.z())+"; its items remain safe in SQLite: "+error.getMessage());}
    }
    private void removeMarker(Database.GraveRow grave){Entity marker=marker(grave);if(marker!=null)marker.remove();}
    private Entity marker(Database.GraveRow grave){if(grave==null||grave.markerUuid()==null)return null;try{return plugin.getServer().getEntity(UUID.fromString(grave.markerUuid()));}catch(Exception ignored){return null;}}
    private void notifyOwner(Database.GraveRow grave,String message){for(Player online:plugin.getServer().getOnlinePlayers())if(owns(online,grave)){CoreUtil.error(online,message);break;}}
    private boolean owns(Player player,Database.GraveRow grave){return grave!=null&&player.getUniqueId().toString().equalsIgnoreCase(grave.ownerUuid());}
    private String publicName(Database.GraveRow grave){return grave.publicName()==null||grave.publicName().isBlank()?grave.ownerName():grave.publicName();}
    private ItemStack head(Database.GraveRow grave){
        ItemStack head=new ItemStack(Material.PLAYER_HEAD);SkullMeta meta=(SkullMeta)head.getItemMeta();
        if(grave.skinValue()!=null&&!grave.skinValue().isBlank()){
            UUID profileId;try{profileId=UUID.fromString(grave.ownerUuid());}catch(Exception ignored){profileId=UUID.randomUUID();}
            PlayerProfile profile=Bukkit.createProfile(profileId,publicName(grave));
            profile.setProperty(grave.skinSignature()==null||grave.skinSignature().isBlank()?new ProfileProperty("textures",grave.skinValue()):new ProfileProperty("textures",grave.skinValue(),grave.skinSignature()));
            meta.setPlayerProfile(profile);
        }else meta.setOwningPlayer(Bukkit.getOfflinePlayer(grave.ownerName()));
        head.setItemMeta(meta);return head;
    }
    private Player damager(Entity entity){if(entity instanceof Player player)return player;if(entity instanceof Projectile projectile&&projectile.getShooter() instanceof Player player)return player;return null;}
    private boolean isBeingLooted(long graveId){
        UUID viewerId=viewers.get(graveId);if(viewerId==null)return false;
        Player viewer=plugin.getServer().getPlayer(viewerId);
        if(viewer!=null&&viewer.isOnline()&&viewer.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder holder&&holder.view()==View.CONTENTS&&holder.graveId()==graveId)return true;
        viewers.remove(graveId,viewerId);return false;
    }
    private String dimension(String world){World loaded=plugin.getServer().getWorld(world);return loaded==null?world:switch(loaded.getEnvironment()){case NORMAL->"Overworld";case NETHER->"Nether";case THE_END->"The End";default->CoreUtil.pretty(loaded.getEnvironment().name());};}
    private String duration(long millis){long minutes=Math.max(0,millis/60000);if(minutes>=1440)return (minutes/1440)+"d "+((minutes%1440)/60)+"h";if(minutes>=60)return (minutes/60)+"h "+(minutes%60)+"m";return minutes+"m";}
    private String direction(Location from,Location to){double angle=Math.toDegrees(Math.atan2(to.getX()-from.getX(),-(to.getZ()-from.getZ())));if(angle<0)angle+=360;String[] names={"N","NE","E","SE","S","SW","W","NW"};return names[((int)Math.round(angle/45.0))%8];}
}
