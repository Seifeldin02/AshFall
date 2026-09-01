package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Industrial Hopper: a hopper with 27 slots that moves nine items per cycle in each direction.
 *
 *  The cycle is industrial-hopper.tick-period, matched to vanilla's own 8-tick hopper cooldown by default:
 *  nine times the throughput of a vanilla hopper for the same per-block cost, rather than seventy-two times
 *  the throughput at eight times the cost on a block players mass-produce.
 *
 *  ONE AUTHORITATIVE INVENTORY. That is the whole design, and it is worth stating plainly because the
 *  previous implementation got it wrong and could duplicate items without limit. Each industrial hopper
 *  owns exactly one live Inventory object, held by its Bay. Every path in this file -- the player's screen,
 *  item-entity pickup, another hopper pushing in, output to the facing container, chunk unload, restart,
 *  breaking the block, explosions -- reads and writes that same object. There is no second copy anywhere:
 *  not in the block's persistent data (a write-only snapshot of the inventory, never a parallel store), and
 *  not in the block's native five slots.
 *
 *  Why the old design duplicated: opening the screen BUILT A COPY of the stored contents, and closing it
 *  wrote that copy back over whatever the transfer loop had done in the meantime. Open the screen, let the
 *  hopper push a stack into a chest, close the screen, and the stack existed in both places. Repeating that
 *  minted items indefinitely. A copy that is only reconciled on close is also exactly why the screen looked
 *  stale while items were moving.
 *
 *  The native five slots are kept permanently EMPTY and are not storage. Every vanilla path that would put
 *  something in them is intercepted and redirected into the authoritative inventory, and the sweep drains
 *  anything that still slips through. Vanilla can therefore never move an item out of this block on its
 *  own, so there is no second owner to disagree with.
 *
 *  Transfers are transactional and always remove before they add. The source is decremented first; only
 *  then is the destination offered anything; anything the destination rejects goes straight back. There is
 *  no instant at which one item exists in two inventories, which is what makes duplication structurally
 *  impossible rather than merely unlikely. Losing an item would require a refund to fail, and refunds go
 *  back into space this class just freed itself.
 *
 *  Persistence is that inventory serialised into the block's own TileState data, written on a one-second
 *  dirty flush, on world save, on chunk unload and on shutdown. Because it lives in the block it saves and
 *  loads with the chunk: there is no registry to rebuild on boot and no world scan anywhere.
 *
 *  COMPARATORS still have to work, and a comparator reads the block's own five slots, not ours. Those five
 *  slots therefore carry a CALIBRATION WEIGHT: a marked stack whose size is chosen so that vanilla's own
 *  container-signal formula, applied over five slots, produces exactly the strength the twenty-seven slots
 *  deserve. It is a readout, not storage -- invisible to every transfer path, never dropped, never counted,
 *  and recomputed whenever the real contents change. A full readout arises only when the real inventory is
 *  genuinely full, so vanilla's "is this container full" checks stay truthful as a side effect.
 *
 *  Known deviation: a vanilla hopper underneath an Industrial Hopper cannot pull from it, because the five
 *  slots vanilla would pull from hold no real items. Point the Industrial Hopper at it instead -- that is the
 *  normal way to chain hoppers, and it runs at nine items a cycle rather than one. */
final class IndustrialHopperService implements Listener {

    private static final int SLOTS = 27;

    /** One industrial hopper: its location and the single inventory that IS its contents.
     *
     *  The Bay is also the inventory's holder, so any event carrying the inventory hands the Bay straight
     *  back -- there is no lookup that could resolve to the wrong block or invent a second store. */
    private final class Bay implements InventoryHolder {
        private final Location at;
        /** The world's NAME and this bay's map key, both captured at construction.
         *
         *  A Location holds only a WEAK reference to its World, and once that world is unloaded
         *  Location.getWorld() THROWS ("World unloaded") rather than returning null -- so the obvious
         *  null guard never fires, and the sweep threw on every tick for the rest of the session, taking
         *  every other hopper's turn down with it. Nothing here may dereference the Location's world;
         *  everything goes through worldOf() and id instead. */
        private final String worldName;
        private final String id;
        private final Inventory inv;
        private boolean dirty;
        private Bay(Location at) {
            this.at = at;
            this.worldName = at.getWorld() == null ? "?" : at.getWorld().getName();
            this.id = key(at);
            this.inv = Bukkit.createInventory(this, SLOTS,
                    Component.text("Industrial Hopper", NamedTextColor.DARK_AQUA));
        }
        @Override public Inventory getInventory() { return inv; }
    }

    /** The Bay's world, or null once it has been unloaded. Resolved BY NAME, never through the Location. */
    private World worldOf(Bay bay) { return Bukkit.getWorld(bay.worldName); }

    private final SMPCore plugin;
    private final NamespacedKey blockKey, itemKey, dataKey, signalKey;
    /** Location key -> the one Bay for that block. Entries are created in exactly one place (bay) and
     *  removed in exactly one place (forget/dropAll), which is what keeps a location from ever ending up
     *  with two inventories. */
    private final Map<String, Bay> bays = new HashMap<>();
    private BukkitTask sweepTask, flushTask;

    IndustrialHopperService(SMPCore plugin) {
        this.plugin = plugin;
        blockKey = new NamespacedKey(plugin, "industrial_hopper");
        itemKey = new NamespacedKey(plugin, "industrial_hopper_item");
        dataKey = new NamespacedKey(plugin, "industrial_hopper_store");
        signalKey = new NamespacedKey(plugin, "industrial_hopper_signal");
        registerRecipe();
        /** One pass over chunks that are ALREADY loaded when the plugin enables. ChunkLoadEvent covers
         *  everything after this point, but spawn chunks are up before we are. Bounded by what is already
         *  in memory -- this is not a world scan. */
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) adopt(chunk);
        });
        /** The sweep period is CONFIGURED, not hardcoded. It was hardcoded to 1 while the config claimed
         *  otherwise, so setting industrial-hopper.tick-period changed nothing at all and the block was
         *  never actually throttled. */
        long period = Math.max(1, plugin.getConfig().getLong("industrial-hopper.tick-period", 8));
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, period, period);
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushDirty, 20L, 20L);
    }

    void shutdown() {
        if (sweepTask != null) sweepTask.cancel();
        if (flushTask != null) flushTask.cancel();
        for (Bay bay : new ArrayList<>(bays.values())) { closeViewers(bay); flush(bay); }
        bays.clear();
    }

    // ------------------------------------------------------------------ item + recipe
    /** The real 27-slot inventory of the industrial hopper at this location, or null if there is none.
     *
     *  Exposed because the block's own state is a vanilla five-slot HOPPER holding nothing but the
     *  comparator calibration weight -- anything reading `container.getInventory()` (like /shop sellall
     *  chest) sees an empty hopper and reports nothing to sell. */
    Inventory inventoryAt(Location at) {
        Bay bay = at == null ? null : bays.get(key(at));
        return bay == null ? null : bay.inv;
    }

    /** Marks the hopper at this location as needing a save, for callers that changed its contents through
     *  {@link #inventoryAt} rather than through the hopper's own paths. */
    void markDirty(Location at) {
        Bay bay = at == null ? null : bays.get(key(at));
        if (bay != null) bay.dirty = true;
    }

    ItemStack createItem() {
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Industrial Hopper", NamedTextColor.AQUA));
        meta.lore(List.of(
                Component.text("27 slots of storage.", NamedTextColor.GRAY),
                Component.text("Moves 9 items per hopper cycle.", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void registerRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "industrial_hopper_recipe");
        if (Bukkit.getRecipe(key) != null) return;
        ShapedRecipe recipe = new ShapedRecipe(key, createItem());
        /** The vanilla hopper pattern with iron blocks in place of ingots: five blocks and a chest. */
        recipe.shape("I I", "ICI", " I ");
        recipe.setIngredient('I', Material.IRON_BLOCK);
        recipe.setIngredient('C', Material.CHEST);
        Bukkit.addRecipe(recipe);
    }

    private boolean isIndustrialItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ the single ownership map
    private static String key(Location at) {
        return (at.getWorld() == null ? "?" : at.getWorld().getName())
                + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
    }

    /** The ONLY place a Bay is ever created. Returns null when the block is not one of ours, so callers can
     *  use it both as the identity test and as the accessor without a second lookup path existing. */
    private Bay bay(Block block) {
        if (block == null || block.getType() != Material.HOPPER) return null;
        String id = key(block.getLocation());
        Bay existing = bays.get(id);
        if (existing != null) return existing;
        if (!(block.getState(false) instanceof TileState tile)) return null;
        if (!tile.getPersistentDataContainer().has(blockKey, PersistentDataType.BYTE)) return null;
        Bay created = new Bay(block.getLocation());
        byte[] raw = tile.getPersistentDataContainer().get(dataKey, PersistentDataType.BYTE_ARRAY);
        if (raw != null && raw.length > 0) {
            try {
                ItemStack[] stored = ItemStack.deserializeItemsFromBytes(raw);
                for (int slot = 0; slot < Math.min(SLOTS, stored.length); slot++) created.inv.setItem(slot, stored[slot]);
            } catch (Throwable error) {
                /** Never delete somebody's items because one block's data could not be read. */
                plugin.getLogger().warning("[IndustrialHopper] unreadable contents at " + id + ": " + error);
            }
        }
        bays.put(id, created);
        return created;
    }

    boolean isIndustrial(Block block) { return bay(block) != null; }

    /** The authoritative contents, or null when this block is not one of ours.
     *
     *  Net-worth valuation needs this because the native five slots it would otherwise read are always
     *  empty by design -- valuing them would price every Industrial Hopper on the server at nothing. */
    ItemStack[] storedContents(Block block) {
        Bay bay = bay(block);
        return bay == null ? null : bay.inv.getContents();
    }

    /** Viewers are always closed when a Bay stops being owned: an open screen is a live handle on the
     *  inventory, and leaving one open would let a player put items into a store nothing will ever save. */
    private void forget(Bay bay) { closeViewers(bay); bays.remove(bay.id); }

    private void closeViewers(Bay bay) {
        for (HumanEntity viewer : new ArrayList<>(bay.inv.getViewers())) viewer.closeInventory();
    }

    /** Writes the authoritative inventory into the block, where the chunk will save it. */
    private boolean flush(Bay bay) {
        Block block = bay.at.getBlock();
        if (block.getType() != Material.HOPPER || !(block.getState(false) instanceof TileState tile)) return false;
        if (!tile.getPersistentDataContainer().has(blockKey, PersistentDataType.BYTE)) return false;
        tile.getPersistentDataContainer().set(dataKey, PersistentDataType.BYTE_ARRAY,
                ItemStack.serializeItemsAsBytes(bay.inv.getContents()));
        tile.update(true, false);
        bay.dirty = false;
        return true;
    }

    private void flushDirty() {
        if (bays.isEmpty()) return;
        for (Bay bay : bays.values()) {
            if (!bay.dirty) continue;
            World world = worldOf(bay);
            if (world == null || !world.isChunkLoaded(bay.at.getBlockX() >> 4, bay.at.getBlockZ() >> 4)) continue;
            flush(bay);
        }
    }

    /** Chunk load is how hoppers rejoin after a restart or an unload: the chunk hands us its own tile
     *  entities, so nothing has to search the world. */
    private void adopt(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities())
            if (state.getType() == Material.HOPPER && state instanceof TileState tile
                    && tile.getPersistentDataContainer().has(blockKey, PersistentDataType.BYTE))
                bay(state.getBlock());
    }

    @EventHandler public void chunkLoad(ChunkLoadEvent event) { adopt(event.getChunk()); }

    /** Flush into the chunk, then stop owning it. Dropping the entry is the important half: keeping it
     *  would leave a second inventory in memory for a block that is about to be re-read from disk. */
    @EventHandler public void chunkUnload(ChunkUnloadEvent event) {
        if (bays.isEmpty()) return;
        Chunk chunk = event.getChunk();
        for (Iterator<Bay> it = bays.values().iterator(); it.hasNext(); ) {
            Bay bay = it.next();
            if (bay.at.getBlockX() >> 4 != chunk.getX() || bay.at.getBlockZ() >> 4 != chunk.getZ()) continue;
            if (!chunk.getWorld().getName().equals(bay.worldName)) continue;
            flush(bay);
            closeViewers(bay);
            it.remove();
        }
    }

    @EventHandler public void worldSave(WorldSaveEvent event) { flushDirty(); }

    // ------------------------------------------------------------------ placement, breaking, destruction
    @EventHandler(ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (!isIndustrialItem(event.getItemInHand())) return;
        Block block = event.getBlockPlaced();
        if (!(block.getState(false) instanceof TileState tile)) return;
        /** A leftover Bay here would mean a previous hopper at this exact spot vanished without an event.
         *  Return its contents rather than letting a new hopper silently inherit them. */
        if (!install(block)) return;
        CoreUtil.msg(event.getPlayer(), "Industrial Hopper placed: 27 slots, 9 items per tick.");
    }

    /** Turns a hopper block into an industrial one and gives it its inventory. Shared by placement and by
     *  the admin command, so both produce byte-identical state -- there is no second way to create one. */
    boolean install(Block block) {
        if (block == null || block.getType() != Material.HOPPER) return false;
        if (!(block.getState(false) instanceof TileState tile)) return false;
        /** A leftover Bay here would mean a previous hopper at this exact spot vanished without an event.
         *  Return its contents rather than letting a new hopper silently inherit them. */
        Bay stale = bays.get(key(block.getLocation()));
        if (stale != null) { spill(stale); forget(stale); }
        tile.getPersistentDataContainer().set(blockKey, PersistentDataType.BYTE, (byte) 1);
        tile.update(true, false);
        return bay(block) != null;
    }

    /** MONITOR, so a protection plugin that cancels the break at HIGHEST has already had its say. Returning
     *  the contents at an earlier priority would hand the player their items and then leave the block
     *  standing, which is a duplication bug of exactly the kind this class exists to avoid. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        Bay bay = bay(event.getBlock());
        if (bay == null) return;
        event.setDropItems(false);
        dropAll(bay, event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void entityExplode(EntityExplodeEvent event) { explode(event.blockList()); }
    @EventHandler(ignoreCancelled = true)
    public void blockExplode(BlockExplodeEvent event) { explode(event.blockList()); }

    private void explode(List<Block> blocks) {
        if (bays.isEmpty()) return;
        for (Iterator<Block> it = blocks.iterator(); it.hasNext(); ) {
            Block block = it.next();
            Bay bay = bays.get(key(block.getLocation()));
            if (bay == null) continue;
            /** Taken out of the blast list so vanilla cannot also drop a plain hopper on top of ours. */
            it.remove();
            dropAll(bay, block);
            block.setType(Material.AIR);
        }
    }

    /** Vanilla cannot push a block entity, so these should never fire. They exist so that if some other
     *  plugin ever moves one, the block and its inventory cannot be separated. */
    @EventHandler(ignoreCancelled = true)
    public void pistonExtend(BlockPistonExtendEvent event) { if (involves(event.getBlocks())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void pistonRetract(BlockPistonRetractEvent event) { if (involves(event.getBlocks())) event.setCancelled(true); }

    private boolean involves(List<Block> blocks) {
        if (bays.isEmpty()) return false;
        for (Block block : blocks) if (bays.containsKey(key(block.getLocation()))) return true;
        return false;
    }

    private void dropAll(Bay bay, Block block) {
        World world = block.getWorld();
        Location at = block.getLocation().add(.5, .5, .5);
        closeViewers(bay);
        world.dropItemNaturally(at, createItem());
        for (ItemStack item : bay.inv.getContents())
            if (item != null && !item.getType().isAir()) world.dropItemNaturally(at, item);
        bay.inv.clear();
        /** The native five should already be empty; drop anything that somehow is not. */
        if (block.getState(false) instanceof Container container) {
            for (ItemStack item : container.getInventory().getContents())
                if (item != null && !item.getType().isAir() && !isSignalMarker(item)) world.dropItemNaturally(at, item);
            container.getInventory().clear();
        }
        bays.remove(bay.id);
    }

    /** Returns the contents to the world when the block went away without an event we could see. Logged,
     *  because it means something outside this plugin removed a populated hopper and an admin should be
     *  able to see how much came back and where. */
    private void spill(Bay bay) {
        World world = worldOf(bay);
        if (world == null) {
            plugin.getLogger().warning("[IndustrialHopper] " + bay.id
                    + " lost its world reference; contents could not be returned.");
            return;
        }
        Location at = bay.at.clone().add(.5, .5, .5);
        int returned = 0, stacks = 0;
        for (ItemStack item : bay.inv.getContents()) {
            if (item == null || item.getType().isAir()) continue;
            ItemStack copy = item.clone();
            world.dropItemNaturally(at, copy);
            returned += copy.getAmount();
            stacks++;
        }
        bay.inv.clear();
        if (returned > 0)
            plugin.getLogger().warning("[IndustrialHopper] " + bay.id
                    + " disappeared without a break event; returned " + returned + " item(s) in "
                    + stacks + " stack(s) to the world.");
    }

    // ------------------------------------------------------------------ the screen
    /** The player opens the authoritative inventory ITSELF, not a rendering of it. Whatever the transfer
     *  loop does while the screen is open is visible the instant it happens, and whatever the player does
     *  lands in the same object the transfer loop reads on its next tick. Nothing is reconciled on close,
     *  because there is nothing to reconcile. */
    @EventHandler(ignoreCancelled = true)
    public void open(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.HOPPER) return;
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        /** Sneaking with something in hand is a build action, exactly as on a vanilla hopper. */
        if (player.isSneaking() && hand != null && !hand.getType().isAir()) return;
        /** A tool whose entire purpose is a right-click action ON a container must not also open it.
         *
         *  The Market Axe sells a container's contents from where you stand. Against every vanilla
         *  container it cancels the interaction, so nothing opens; this block opens its own screen instead
         *  of letting vanilla do it, so it has to make the same check for itself. Reported live: right
         *  clicking an Industrial Hopper with the axe sold the contents AND opened the hopper. */
        if (plugin.shards() != null && plugin.shards().hasRightClickAction(player, hand)) return;
        Bay bay = bay(block);
        if (bay == null) return;
        event.setCancelled(true);
        player.openInventory(bay.inv);
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (event.getInventory().getHolder(false) instanceof Bay bay) bay.dirty = true;
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof Bay bay) bay.dirty = true;
    }

    // ------------------------------------------------------------------ vanilla input paths
    private Bay bayOf(Inventory inv) {
        if (inv == null) return null;
        if (inv.getHolder(false) instanceof Bay bay) return bay;
        Location at = inv.getLocation();
        return at == null ? null : bays.get(key(at));
    }

    /*  Everything vanilla would move into or out of the native five slots is intercepted here.
     *
     *  NOTHING IN THIS METHOD MAY WRITE TO event.getSource(). That is not a style rule, it is the bug that
     *  deleted MacoCT's chests, and it is worth stating exactly because the broken version looked correct.
     *
     *  Paper does not hand a listener a copy of the moving stack. HopperBlockEntity#hopperPull and
     *  #hopperPush take the LIVE ItemStack out of the source slot, remember its real count, call setCount()
     *  on it to shrink it to the one item being moved, fire the event, and then -- if the event was
     *  cancelled -- call setCount() again with the remembered count to put it back. For the whole duration
     *  of the event the source slot therefore holds a stack of 64 that is temporarily claiming to be a
     *  stack of 1, and the restore is a mutation of that particular object rather than a re-insert.
     *
     *  Bukkit's Inventory#removeItem, asked for one item, finds a slot whose stack says it holds one, sees
     *  that the whole slot is consumed, and calls clear(slot). The object is now detached from the
     *  container. Paper's restore then writes 64 into an ItemStack nobody is holding, and the sixty-four
     *  items are simply gone -- minus the single one this method credited to the bay. Sixty-three lost per
     *  event, which is exactly what was reported and exactly what the live column reproduced.
     *
     *  The old code only reached that path when the source was NOT the block directly above, and it decided
     *  that by comparing Inventory#getLocation() against the block overhead. A double chest reports one of
     *  its two halves there, and which half is arbitrary -- so a hopper under a double chest took the
     *  inbound path and lost items, while the same hopper under a single chest was left to the sweep and
     *  worked perfectly. That is the whole of "some Industrial Hoppers are glitched and others are fine".
     *
     *  So: cancel, never write, and let the settled state be read a tick later.
     *
     *  Out: cancelled outright. The native slots hold nothing, and output is the sweep's job.
     *  In, from the block directly above: cancelled and left to the sweep, which pulls nine a cycle.
     *  In, from anywhere else (a hopper or dropper aimed at us): cancelled and re-performed next tick. */
    @EventHandler(ignoreCancelled = true)
    public void moveItem(InventoryMoveItemEvent event) {
        if (tracing()) plugin.getLogger().info("[IH-move] " + describe(event.getSource()) + " -> "
                + describe(event.getDestination()) + " item=" + describe(event.getItem())
                + " srcBay=" + (bayOf(event.getSource()) != null) + " dstBay=" + (bayOf(event.getDestination()) != null));
        if (bayOf(event.getSource()) != null) { event.setCancelled(true); return; }
        Bay destination = bayOf(event.getDestination());
        if (destination == null) return;
        event.setCancelled(true);
        if (sweepDrains(event.getSource(), destination)) return;
        /** Exactly what vanilla was about to move, which is settings.hopper-amount -- never the bay's own
         *  nine. The buff belongs to the Industrial Hopper, not to whatever is feeding it. */
        pullLater(destination, event.getSource(), Math.max(1, event.getItem().getAmount()));
    }

    /** Every block position that backs an inventory.
     *
     *  A double chest is one inventory over two blocks and reports only one of them from getLocation(),
     *  so a caller asking "is this the container directly above me" has to be given both halves or it will
     *  answer no half the time. */
    private List<Location> anchors(Inventory inv) {
        List<Location> out = new ArrayList<>();
        if (inv instanceof org.bukkit.inventory.DoubleChestInventory doubled) {
            for (Inventory half : new Inventory[]{doubled.getLeftSide(), doubled.getRightSide()})
                if (half != null) anchor(half, out);
            if (!out.isEmpty()) return out;
        }
        anchor(inv, out);
        return out;
    }

    private void anchor(Inventory inv, List<Location> out) {
        try {
            Location at = inv.getLocation();
            if (at != null) out.add(at);
        } catch (Throwable unavailable) {
            /** getLocation goes through the holder, which can be a virtual or already-unloaded one. */
        }
    }

    /*  Which of the two transfer paths owns this link -- and it must be exactly one of them.
     *
     *  Every inbound link is drained either by the sweep's pullFromAbove or by the deferred pull this event
     *  schedules, never by both (that would double the rate) and never by neither. The container directly
     *  overhead is normally the sweep's, which is why the event handler steps aside for it.
     *
     *  A PLAIN hopper overhead is the exception, and getting it wrong is a stand-off rather than a bug you
     *  can see in a stack trace: pullFromAbove deliberately leaves a plain hopper alone so it keeps moving
     *  at its own rate, so if the event handler also steps aside, nobody moves anything at all and the
     *  hopper silently fills to its five slots and stops. Found exactly that way -- 33 items parked in a
     *  vanilla hopper with an empty Industrial Hopper underneath it. An INDUSTRIAL hopper overhead is not
     *  the exception: it pushes into us from its own sweep, which is a path no event is involved in. */
    private boolean sweepDrains(Inventory source, Bay bay) {
        if (!feedsFromAbove(source, bay)) return false;
        Block above = bay.at.getBlock().getRelative(BlockFace.UP);
        if (above.getType() != Material.HOPPER) return true;
        if (bays.containsKey(key(above.getLocation()))) return true;
        return !(above.getBlockData() instanceof Directional facing) || facing.getFacing() != BlockFace.DOWN;
    }

    /** True when this inventory is (or includes) the block sitting directly on top of the bay. */
    private boolean feedsFromAbove(Inventory source, Bay bay) {
        for (Location at : anchors(source)) {
            World world;
            try { world = at.getWorld(); } catch (Throwable unloaded) { continue; }
            if (world == null || !world.getName().equals(bay.worldName)) continue;
            if (at.getBlockX() == bay.at.getBlockX() && at.getBlockY() == bay.at.getBlockY() + 1
                    && at.getBlockZ() == bay.at.getBlockZ()) return true;
        }
        return false;
    }

    /** One deferred inbound pull per bay per source per tick. Without the guard a hopper aimed at a bay
     *  would queue a fresh task on every one of its own attempts and the bay would drain it faster than
     *  vanilla ever would. */
    private final Set<String> pendingPulls = new HashSet<>();

    /*  The inbound transfer a vanilla hopper or dropper aimed at us would have made, performed one tick
     *  later against the settled source rather than against the half-mutated one the event exposes.
     *
     *  Deferring is not a workaround for a race; it is the only point at which the source can be read at
     *  all. Once Paper has restored the slot, this is an ordinary move(): capacity is measured first, the
     *  source is decremented by exactly what the bay accepts, and nothing can be left in both places or in
     *  neither. Cancelling costs the pusher one tick, which is what a vanilla hopper spends anyway when a
     *  destination refuses it, and the dropper case behaves as a momentarily-full container -- it keeps its
     *  item and we take it on the next tick instead of it being pushed. */
    private void pullLater(Bay bay, Inventory source, int amount) {
        List<Location> where = anchors(source);
        Location anchor = where.isEmpty() ? null : where.getFirst();
        String token = bay.id + " <- " + (anchor == null ? "inv" + System.identityHashCode(source)
                : anchor.getBlockX() + "," + anchor.getBlockY() + "," + anchor.getBlockZ());
        if (!pendingPulls.add(token)) return;
        String anchorWorld = null;
        int ax = 0, ay = 0, az = 0;
        if (anchor != null) {
            try { anchorWorld = anchor.getWorld() == null ? null : anchor.getWorld().getName(); }
            catch (Throwable unloaded) { anchorWorld = null; }
            ax = anchor.getBlockX(); ay = anchor.getBlockY(); az = anchor.getBlockZ();
        }
        final String world = anchorWorld;
        final int fx = ax, fy = ay, fz = az;
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingPulls.remove(token);
            Bay live = bays.get(bay.id);
            if (live == null) return;
            Inventory from = source;
            /** Re-resolved from the world rather than trusting the captured handle: the pusher can be
             *  broken, replaced or unloaded in the tick we waited, and a detached container would be a
             *  handle on items that no longer exist anywhere. */
            if (world != null) {
                World in = Bukkit.getWorld(world);
                if (in == null || !in.isChunkLoaded(fx >> 4, fz >> 4)) return;
                if (!(in.getBlockAt(fx, fy, fz).getState(false) instanceof Container container)) return;
                from = container.getInventory();
            }
            int moved = move(from, live.inv, amount, live.at);
            if (moved > 0) live.dirty = true;
            if (tracing()) plugin.getLogger().info("[IH-move] deferred inbound pull " + token + " moved " + moved);
        });
    }

    /** Vanilla's own suction, aimed at the real store instead of at the five native slots.
     *
     *  Cancelled and redone through absorb() rather than allowed to run, because vanilla would move the
     *  item into the native slots, which hold nothing but the comparator calibration weight. Sharing
     *  absorb() with the sweep is also what stops the two paths racing: whichever fires first takes only
     *  what fits, and the other then finds either an emptier bay or a smaller stack.
     *
     *  This handler used to do its own thing -- remove the whole entity, add the whole stack, and re-drop
     *  whatever the bay rejected as a brand new entity. On a full hopper that was draining downstream it
     *  fired every time a slot opened, which is precisely the reported jumping and spilling. */
    @EventHandler(ignoreCancelled = true)
    public void pickup(InventoryPickupItemEvent event) {
        Bay bay = bayOf(event.getInventory());
        if (bay == null) return;
        event.setCancelled(true);
        int took = absorb(bay, event.getItem());
        if (tracing()) plugin.getLogger().info("[IH-pickup] " + bay.id + " absorbed " + took);
    }

    /*  The LIVE chain rig: chest -> hopper -> chest -> hopper -> chest, in a real world.
     *
     *  The synthetic rig in IndustrialHopperVerify drives sweep() by hand, which is the one thing a real
     *  server never does. This builds the same column out of genuinely ticking blocks in a loaded chunk,
     *  inserts the payload through the ORDINARY inventory path (Inventory#addItem, exactly as a player's
     *  click does), and then does nothing at all -- the plugin's own scheduled sweep and vanilla's hopper
     *  tick move the items, and InventoryMoveItemEvent and the suction event fire for real.
     *
     *  It samples every tick rather than at the end, because "the total was right before and after" is not
     *  the same statement as "the total was never wrong". If the count ever changes, the sampler stops on
     *  that tick and reports the full five-block breakdown for it.
     *
     *  Chunks are force-loaded for the duration: a hopper in an unloaded chunk does not tick, and a rig that
     *  quietly stopped ticking would report a clean run having tested nothing. */
    void liveChain(org.bukkit.World world, int x, int y, int z, int payload, Material what, java.util.function.Consumer<String> report) {
        for (int dy = -3; dy <= 3; dy++) world.getBlockAt(x, y + dy, z).setType(Material.AIR, false);
        world.setChunkForceLoaded(x >> 4, z >> 4, true);

        Block topChest = world.getBlockAt(x, y + 2, z);
        Block upper = world.getBlockAt(x, y + 1, z);
        Block midChest = world.getBlockAt(x, y, z);
        Block lower = world.getBlockAt(x, y - 1, z);
        Block bottomChest = world.getBlockAt(x, y - 2, z);
        topChest.setType(Material.CHEST, false);
        midChest.setType(Material.CHEST, false);
        bottomChest.setType(Material.CHEST, false);
        for (Block block : new Block[]{upper, lower}) {
            block.setType(Material.HOPPER, false);
            org.bukkit.block.data.type.Hopper data = (org.bukkit.block.data.type.Hopper) block.getBlockData();
            data.setFacing(BlockFace.DOWN);
            data.setEnabled(true);
            block.setBlockData(data, false);
            install(block);
        }
        report.accept("rig built at " + x + "," + y + "," + z + " in " + world.getName()
                + " (chest/IH/chest/IH/chest), chunk force-loaded, both hoppers installed="
                + (isIndustrial(upper) && isIndustrial(lower)));

        /** The ordinary path a player's inventory click takes. Not setItem, not the test hook. */
        Inventory source = ((Container) topChest.getState(false)).getInventory();
        /** The material matters. A stone run passed cleanly while a real 64-stack of CHESTS lost 62 of
         *  them, so the payload is a parameter now rather than a hardcoded assumption. */
        source.addItem(new ItemStack(what, payload));
        int total = payload;
        report.accept("inserted " + payload + "x " + what + " through Inventory#addItem; sampling every tick");

        int[] worstTick = {-1};
        String[] worstLine = {null};
        int[] ticks = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            ticks[0]++;
            int a = countAt(topChest), b = storedCount(upper), c = countAt(midChest),
                d = storedCount(lower), e = countAt(bottomChest), g = 0;
            for (org.bukkit.entity.Entity entity : world.getNearbyEntities(
                    new Location(world, x + .5, y, z + .5), 5, 6, 5))
                if (entity instanceof Item dropped) g += dropped.getItemStack().getAmount();
            int seen = a + b + c + d + e + g;
            if (seen != total && worstTick[0] < 0) {
                worstTick[0] = ticks[0];
                worstLine[0] = "tick " + ticks[0] + ": top=" + a + " IH1=" + b + " mid=" + c
                        + " IH2=" + d + " bottom=" + e + " ground=" + g + " -> " + seen + ", expected " + total;
            }
            boolean settled = a == 0 && b == 0 && c == 0 && d == 0;
            if (ticks[0] >= 400 || settled) {
                task.cancel();
                world.setChunkForceLoaded(x >> 4, z >> 4, false);
                report.accept("finished after " + ticks[0] + " ticks: top=" + a + " IH1=" + b + " mid=" + c
                        + " IH2=" + d + " bottom=" + e + " ground=" + g);
                if (worstLine[0] != null) report.accept("CONSERVATION BROKE -- " + worstLine[0]);
                else report.accept("conservation held on every one of " + ticks[0] + " ticks; delivered "
                        + e + "/" + total + " with real ticking blocks and real events");
            }
        }, 1L, 1L);
    }

    int countAt(Block block) {
        if (!(block.getState(false) instanceof Container container)) return 0;
        int sum = 0;
        for (ItemStack item : container.getInventory().getContents())
            if (item != null && !item.getType().isAir() && !isSignalMarker(item)) sum += item.getAmount();
        return sum;
    }

    // ------------------------------------------------------------------ test surface
    /** A deliberately small, explicitly-named set of hooks for {@link IndustrialHopperVerify}.
     *
     *  The alternative is a test that pokes at redstone through RCON and infers behaviour from item counts,
     *  which is exactly how this block's comparator and locking bugs went unnoticed: {@code /setblock}-placed
     *  redstone does not propagate updates the way a player-placed circuit does, so the rig lied. These run
     *  the real transfer code against real block inventories in a real world, one cycle at a time, and assert
     *  what actually happened. Nothing here bypasses the ownership model: every one of them goes through the
     *  same single authoritative inventory every other path uses. */
    void sweepOnce() { sweep(); }

    /** Items in the authoritative 27-slot store. */
    int storedCount(Block block) {
        Bay bay = bay(block);
        if (bay == null) return -1;
        return count(bay.inv);
    }

    boolean setStored(Block block, int slot, ItemStack item) {
        Bay bay = bay(block);
        if (bay == null) return false;
        bay.inv.setItem(slot, item);
        bay.dirty = true;
        return true;
    }

    /** Real items sitting in the block's own five slots. Should always be zero: the calibration weight is
     *  not an item as far as anything in this class is concerned. */
    int nativeRealCount(Block block) {
        if (!(block.getState(false) instanceof Container container)) return -1;
        int sum = 0;
        for (ItemStack item : container.getInventory().getContents())
            if (item != null && !item.getType().isAir() && !isSignalMarker(item)) sum += item.getAmount();
        return sum;
    }

    int nativeCalibration(Block block) {
        if (!(block.getState(false) instanceof Container container)) return -1;
        int sum = 0;
        for (ItemStack item : container.getInventory().getContents())
            if (isSignalMarker(item)) sum += item.getAmount();
        return sum;
    }

    int comparatorLevelAt(Block block) {
        Bay bay = bay(block);
        return bay == null ? -1 : comparatorLevel(bay.inv);
    }

    void refreshComparatorNow(Block block) {
        Bay bay = bay(block);
        if (bay != null) refreshComparator(block, bay);
    }

    /** Writes the store into the block and drops the in-memory Bay, so the next read has to come off the
     *  block's own data -- the same path a chunk unload and a restart take. */
    boolean flushAndForget(Block block) {
        Bay bay = bay(block);
        if (bay == null) return false;
        boolean ok = flush(bay);
        forget(bay);
        return ok;
    }

    /** Exactly what the break handler does once a break is final: contents out, block item out, bay gone. */
    boolean dropAllForTest(Block block) {
        Bay bay = bay(block);
        if (bay == null) return false;
        dropAll(bay, block);
        return true;
    }

    void explodeForTest(List<Block> blocks) { explode(blocks); }

    // ------------------------------------------------------------------ transfer
    /*  Per-transfer audit trail, off by default.
     *
     *  The chest -> hopper -> chest -> hopper -> chest chain was built as a regression rig and could not be
     *  made to lose an item: conservation held on every cycle for a 64 stack, a 16 stack, an unstackable,
     *  a nearly-full destination, a completely full one, and across a serialise/re-read round trip. So the
     *  reported loss is not in the transfer arithmetic this rig drives.
     *
     *  What the rig CANNOT drive is vanilla's own hopper tick -- InventoryMoveItemEvent and the suction
     *  event only fire on a live server with real ticking blocks, and those are the paths a manual insert
     *  goes through. Rather than guess at them, this logs every movement with the counts either side, so a
     *  live reproduction produces the exact cycle where the total changes instead of another theory.
     *
     *  Enable with industrial-hopper.trace: true, reproduce, then read logs/latest.log for [IH-trace]. */
    private boolean tracing() { return plugin.getConfig().getBoolean("industrial-hopper.trace", false); }

    private void trace(Bay bay, String stage, int before, int after) {
        if (before == after) return;
        plugin.getLogger().info("[IH-trace] " + bay.id + " " + stage + ": " + before + " -> " + after
                + " (" + (after - before >= 0 ? "+" : "") + (after - before) + ")");
    }

    private String describe(Inventory inv) {
        if (inv == null) return "null";
        Location at = null;
        try { at = inv.getLocation(); } catch (Throwable ignored) { }
        return inv.getType() + (at == null ? "@?" : "@" + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ())
                + "/size" + inv.getSize();
    }

    private String describe(ItemStack item) {
        return item == null ? "null" : item.getAmount() + "x" + item.getType();
    }

    /*  Per-tick census of a vertical column, for chasing a reported loss on blocks somebody actually built.
     *
     *  Counts every place an item in that column can legitimately be -- container contents, the real store
     *  of each Industrial Hopper, the native five slots (minus calibration weight), and item entities in the
     *  neighbourhood -- and reports only the ticks on which the TOTAL changes. A stable total with items
     *  moving between rows is a working chain; a falling total is the loss, timestamped to the tick and
     *  attributed to whichever row lost it. */
    void watch(World world, int x, int fromY, int toY, int z, int seconds, java.util.function.Consumer<String> report) {
        int lowY = Math.min(fromY, toY), highY = Math.max(fromY, toY);
        world.setChunkForceLoaded(x >> 4, z >> 4, true);
        int[] ticks = {0}, last = {-1};
        report.accept("watching " + x + " " + lowY + ".." + highY + " " + z + " in " + world.getName()
                + " for " + seconds + "s");
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            ticks[0]++;
            StringBuilder row = new StringBuilder();
            int seen = 0;
            for (int y = highY; y >= lowY; y--) {
                Block block = world.getBlockAt(x, y, z);
                Bay bay = bays.get(key(block.getLocation()));
                int here = countAt(block);
                if (bay != null) here += count(bay.inv);
                seen += here;
                if (here > 0 || bay != null || block.getState(false) instanceof Container)
                    row.append(' ').append(y).append(':').append(block.getType() == Material.HOPPER
                            ? (bay != null ? "IH" : "hopper") : block.getType().name().toLowerCase(java.util.Locale.ROOT))
                       .append('=').append(here);
            }
            int ground = 0;
            for (org.bukkit.entity.Entity entity : world.getNearbyEntities(
                    new Location(world, x + .5, (lowY + highY) / 2.0, z + .5), 8, (highY - lowY) / 2.0 + 8, 8))
                if (entity instanceof Item dropped) ground += dropped.getItemStack().getAmount();
            seen += ground;
            if (seen != last[0]) {
                String line = "tick " + ticks[0] + " total=" + seen
                        + (last[0] < 0 ? "" : " (" + (seen - last[0] >= 0 ? "+" : "") + (seen - last[0]) + ")")
                        + row + " ground=" + ground;
                last[0] = seen;
                report.accept(line);
            }
            if (ticks[0] >= seconds * 20L) {
                task.cancel();
                world.setChunkForceLoaded(x >> 4, z >> 4, false);
                report.accept("watch finished after " + ticks[0] + " ticks, final total " + seen);
            }
        }, 1L, 1L);
    }

    private void sweep() {
        if (bays.isEmpty()) return;
        int budget = Math.max(1, plugin.getConfig().getInt("industrial-hopper.items-per-tick", 9));
        for (Iterator<Bay> it = bays.values().iterator(); it.hasNext(); ) {
            Bay bay = it.next();
            /** Resolved by NAME: an unloaded world makes the Location's own accessor throw, not return
             *  null, so this guard has to avoid it entirely. There is nowhere to drop the contents of a
             *  hopper whose world is gone, so the bay is simply released. */
            World world = worldOf(bay);
            if (world == null) { closeViewers(bay); it.remove(); continue; }
            if (!world.isChunkLoaded(bay.at.getBlockX() >> 4, bay.at.getBlockZ() >> 4)) continue;
            Block block = bay.at.getBlock();
            if (block.getType() != Material.HOPPER || !(block.getState(false) instanceof TileState tile)
                    || !tile.getPersistentDataContainer().has(blockKey, PersistentDataType.BYTE)) {
                /** The block went away with no event we could see -- world edit, /setblock, another plugin.
                 *  Give the contents back to the world rather than deleting them, and stop owning it. */
                spill(bay);
                closeViewers(bay);
                it.remove();
                continue;
            }
            /** Redstone lock, read from the block's own vanilla `enabled` state rather than re-derived
             *  from power levels, so a locked Industrial Hopper is locked under exactly the conditions a
             *  locked vanilla hopper is. */
            if (block.getBlockData() instanceof org.bukkit.block.data.type.Hopper data && !data.isEnabled()) {
                /** A locked hopper still has to report its contents to a comparator; only the item movement
                 *  stops. This is also how a comparator-and-lock loop is built, so getting it wrong would
                 *  break the most common redstone use of the block. */
                refreshComparator(block, bay);
                continue;
            }
            boolean trace = tracing();
            int mark = trace ? count(bay.inv) : 0;
            drainNative(block, bay);
            if (trace) { trace(bay, "drainNative", mark, count(bay.inv)); mark = count(bay.inv); }
            collectItems(block, bay);
            if (trace) { trace(bay, "collectItems", mark, count(bay.inv)); mark = count(bay.inv); }
            pullFromAbove(block, bay, budget);
            if (trace) { trace(bay, "pullFromAbove", mark, count(bay.inv)); mark = count(bay.inv); }
            pushToFacing(block, bay, budget);
            if (trace) trace(bay, "pushToFacing", mark, count(bay.inv));
            refreshComparator(block, bay);
        }
    }

    /** Picks up item entities floating in the hopper's suction box, exactly as a vanilla hopper does.
     *
     *  Vanilla drives this through the block's own five slots, and those hold no real items here, so relying
     *  on it alone would make collection depend on the calibration weight rather than on whether there is
     *  actually room. Doing it directly means a duel-hopper under a mob farm behaves like a vanilla one:
     *  it collects while it has space and stops when it is genuinely full. */
    private void collectItems(Block block, Bay bay) {
        Location at = bay.at;
        org.bukkit.util.BoundingBox box = new org.bukkit.util.BoundingBox(
                at.getBlockX(), at.getBlockY() + 0.6875, at.getBlockZ(),
                at.getBlockX() + 1, at.getBlockY() + 1.5, at.getBlockZ() + 1);
        for (org.bukkit.entity.Entity entity : block.getWorld().getNearbyEntities(box, e -> e instanceof Item))
            absorb(bay, (Item) entity);
    }

    /** Takes as much of one floating stack as the bay can genuinely hold, and leaves the rest where it lies.
     *
     *  The ONE place an item entity is ever consumed, shared by this sweep and by vanilla's own suction
     *  event, so the two can never disagree about what happened to a stack. Two rules make it safe.
     *
     *  Take only what fits. Capacity is measured before the entity is touched, so nothing is ever removed
     *  that cannot be credited, and there is no remainder to refund or throw on the floor.
     *
     *  A partly-eaten stack is EDITED, never replaced. Removing the entity and re-dropping the leftover as
     *  a new one is what "the pile on top of a full hopper keeps jumping around and spilling everywhere"
     *  actually was: a full hopper that is also pushing items downstream frees a slot or two every tick, so
     *  every tick the resting stack was destroyed and respawned -- a new entity each time, snapped back to
     *  the drop point, with a fresh pickup delay and a randomised throw from dropItemNaturally. Shrinking
     *  the stack in place keeps it the same entity, sitting still, for as long as it takes to drain. */
    private int absorb(Bay bay, Item dropped) {
        if (dropped == null || !dropped.isValid()) return 0;
        ItemStack stack = dropped.getItemStack();
        if (stack == null || stack.getType().isAir()) return 0;
        int take = Math.min(space(bay.inv, stack), stack.getAmount());
        if (take <= 0) return 0;
        /** Decrement the entity FIRST, then credit what was taken -- the same remove-before-add order
         *  every other path here uses, so a rejected remainder can never become a second copy. */
        ItemStack piece = stack.clone();
        piece.setAmount(take);
        if (take >= stack.getAmount()) dropped.remove();
        else { ItemStack left = stack.clone(); left.setAmount(stack.getAmount() - take); dropped.setItemStack(left); }
        for (ItemStack rejected : bay.inv.addItem(piece).values()) dropAt(bay.at, rejected);
        bay.dirty = true;
        return take;
    }

    // ------------------------------------------------------------------ comparator output

    /** Vanilla's container signal, computed over the real twenty-seven slots.
     *
     *  Identical formula to the one a comparator applies to any container: the mean fill fraction scaled to
     *  fourteen, plus one for "not empty". Kept as a pure function so it can be asserted in the self test
     *  rather than only observed with redstone. */
    static int comparatorLevel(Inventory inv) {
        double fill = 0;
        boolean any = false;
        ItemStack[] contents = inv.getStorageContents();
        for (ItemStack item : contents) {
            if (item == null || item.getType().isAir()) continue;
            any = true;
            fill += (double) item.getAmount() / Math.max(1, item.getMaxStackSize());
        }
        return (int) Math.floor(fill / contents.length * 14) + (any ? 1 : 0);
    }

    /** How many calibration items the five native slots must hold for vanilla to read back `level`.
     *
     *  Vanilla over five 64-stack slots reports floor(14n/320) + 1 for n > 0, so the smallest n that lands
     *  on a given level is ceil((level-1) * 320 / 14). Level 15 needs all 320 -- which is only ever asked
     *  for when the real inventory is completely full, so a "full" native inventory always means a full
     *  real inventory and vanilla's own fullness checks stay honest. */
    static int signalUnits(int level) {
        if (level <= 0) return 0;
        if (level >= 15) return 5 * 64;
        /** Level 1 is "not empty", which vanilla awards for ANY item at all -- the scaled term is zero there,
         *  so the answer is one item, not none. Returning zero would have made a barely-filled hopper read as
         *  empty, which is precisely the state a comparator lock is usually watching for. */
        return Math.max(1, (int) Math.ceil((level - 1) * 320.0 / 14.0));
    }

    private boolean isSignalMarker(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(signalKey, PersistentDataType.BYTE);
    }

    private ItemStack signalMarker(int amount) {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Comparator Calibration", NamedTextColor.DARK_GRAY));
        meta.lore(List.of(Component.text("Reports this hopper's 27 slots to comparators.", NamedTextColor.DARK_GRAY)));
        meta.getPersistentDataContainer().set(signalKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Writes the calibration weight into the block's own five slots so an adjacent comparator reads the
     *  strength the twenty-seven slots deserve. Only touched when the level actually changes -- a block
     *  update every sweep for every hopper on the server would be an expensive way to say nothing. */
    private void refreshComparator(Block block, Bay bay) {
        if (!plugin.getConfig().getBoolean("industrial-hopper.comparator-output", true)) return;
        if (!(block.getState(false) instanceof Container container)) return;
        Inventory own = container.getInventory();
        int want = signalUnits(comparatorLevel(bay.inv));
        int have = 0;
        for (ItemStack item : own.getContents()) if (isSignalMarker(item)) have += item.getAmount();
        if (have == want) return;
        for (int slot = 0; slot < own.getSize(); slot++) if (isSignalMarker(own.getItem(slot))) own.setItem(slot, null);
        int left = want;
        for (int slot = 0; slot < own.getSize() && left > 0; slot++) {
            if (own.getItem(slot) != null && !own.getItem(slot).getType().isAir()) continue;
            int here = Math.min(64, left);
            own.setItem(slot, signalMarker(here));
            left -= here;
        }
        /** applyPhysics=true so neighbouring comparators are told to re-read; without it the signal only
         *  updates the next time something else happens to poke the block. */
        container.update(true, true);
    }

    /** Nothing should ever rest in the native five. If something does get in -- a plugin, a command, a
     *  vanilla path added in some future update -- it is moved into the real inventory the same tick, so it
     *  becomes visible on the screen instead of turning into hidden storage. */
    private void drainNative(Block block, Bay bay) {
        if (!(block.getState(false) instanceof Container container)) return;
        Inventory own = container.getInventory();
        for (int slot = 0; slot < own.getSize(); slot++) {
            ItemStack live = own.getItem(slot);
            if (live == null || live.getType().isAir()) continue;
            /** The calibration weight is a readout, not contents -- draining it into the real inventory
             *  would turn the comparator's own scaffolding into items a player could take out. */
            if (isSignalMarker(live)) continue;
            /** Detached before the slot is cleared, for the same reason as in move(). */
            ItemStack item = live.clone();
            own.setItem(slot, null);
            for (ItemStack rejected : bay.inv.addItem(item).values())
                for (ItemStack lost : own.addItem(rejected).values()) dropAt(bay.at, lost);
            bay.dirty = true;
        }
    }

    private void pullFromAbove(Block block, Bay bay, int budget) {
        Block above = block.getRelative(BlockFace.UP);
        Bay upper = bays.get(key(above.getLocation()));
        if (upper != null) {
            /** Another industrial hopper. If it already faces us it is pushing, so pulling as well would
             *  just double the rate on that one link. */
            if (above.getBlockData() instanceof Directional facing && facing.getFacing() == BlockFace.DOWN) return;
            if (move(upper.inv, bay.inv, budget, above.getLocation()) > 0) { upper.dirty = true; bay.dirty = true; }
            return;
        }
        /*  A plain hopper overhead is not a container to be drained nine at a time.
         *
         *  Reported live: "Normal Hopper -> Industrial Hopper makes the Normal Hopper magically move nine
         *  items at once". It did, because pullFromAbove treats anything above with an inventory as a source
         *  and pulls at the bay's own rate -- so the buff leaked onto the block feeding it. A plain hopper
         *  has to keep moving at a plain hopper's amount and cadence whichever side it feeds from.
         *
         *  Facing down into us it is already pushing, and that push is cancelled and re-performed at
         *  settings.hopper-amount by moveItem, so pulling as well would just double the rate on that link --
         *  the same reasoning that already applies to an Industrial Hopper overhead. Facing anywhere else it
         *  never pushes, so we do pull, but only ever a plain hopper's worth per cycle. */
        boolean plainHopper = above.getType() == Material.HOPPER;
        if (plainHopper) {
            if (above.getBlockData() instanceof Directional facing && facing.getFacing() == BlockFace.DOWN) return;
            budget = Math.max(1, plugin.getConfig().getInt("industrial-hopper.vanilla-hopper-amount", 1));
        }
        if (above.getState(false) instanceof Container source) {
            Inventory from = source.getInventory();
            /** Vanilla pulls through the container's DOWN face, and for a furnace that face exposes only the
             *  result and the fuel slot. Taking from slot 0 would have a hopper under a furnace steal the ore
             *  back out before it ever smelted, which is not a subtle difference to anybody with a farm. */
            int moved = from instanceof FurnaceInventory ? moveFromSlots(from, bay.inv, new int[]{2, 1}, budget, bay.at)
                      : from instanceof org.bukkit.inventory.BrewerInventory ? moveFromSlots(from, bay.inv, new int[]{0, 1, 2, 3}, budget, bay.at)
                      : move(from, bay.inv, budget, above.getLocation());
            if (moved > 0) bay.dirty = true;
            return;
        }
        /** A container minecart sitting in the block above is a source too -- that is how a vanilla hopper
         *  unloads a chest minecart, and an industrial hopper under a rail has to do the same. */
        Inventory cart = cartInventory(above);
        if (cart != null && move(cart, bay.inv, budget, above.getLocation()) > 0) bay.dirty = true;
    }

    /** The inventory of a container minecart standing in a block space, or null when there is not one. */
    private Inventory cartInventory(Block block) {
        org.bukkit.util.BoundingBox box = new org.bukkit.util.BoundingBox(
                block.getX(), block.getY(), block.getZ(), block.getX() + 1, block.getY() + 1, block.getZ() + 1);
        for (org.bukkit.entity.Entity entity : block.getWorld().getNearbyEntities(box, e -> e instanceof InventoryHolder))
            if (entity instanceof org.bukkit.entity.Minecart && entity instanceof InventoryHolder holder) return holder.getInventory();
        return null;
    }

    /** Transfer restricted to a specific set of source slots, in order, for containers where vanilla only
     *  exposes part of the inventory to the face a hopper is reading through. */
    private int moveFromSlots(Inventory from, Inventory to, int[] slots, int budget, Location spillAt) {
        int moved = 0;
        for (int slot : slots) {
            if (moved >= budget || slot >= from.getSize()) continue;
            ItemStack live = from.getItem(slot);
            if (live == null || live.getType().isAir()) continue;
            /** A furnace's fuel slot only gives up empty buckets, exactly as vanilla does -- otherwise a
             *  hopper underneath would drain the coal it was just fed. */
            if (from instanceof FurnaceInventory && slot == 1 && live.getType() != Material.BUCKET) continue;
            ItemStack item = live.clone();
            /** Same remove-before-checking flaw move() had, and this is the WORSE copy of it: the source
             *  here is a furnace or brewing stand, whose addItem() does not put a refund back where it came
             *  from -- it tries the ingredient slot. When that slot is full or will not take the item, the
             *  refund fails and the leftovers were flung into the world with dropItemNaturally. A full
             *  industrial hopper under a furnace therefore scattered the furnace's output across the floor,
             *  every sweep, for as long as it stayed full. Ask how much fits first and nothing ever leaves
             *  the source that cannot arrive. */
            int room = space(to, item);
            if (room <= 0) continue;
            int take = Math.min(Math.min(item.getAmount(), budget - moved), room);
            if (take <= 0) continue;
            ItemStack piece = item.clone();
            piece.setAmount(take);
            ItemStack keep = item.clone();
            keep.setAmount(item.getAmount() - take);
            from.setItem(slot, keep.getAmount() <= 0 ? null : keep);
            int rejected = total(to.addItem(piece).values());
            moved += take - rejected;
            if (rejected > 0) {
                /** Unreachable now that capacity is checked up front; kept as the last line of defence so a
                 *  future change to space() degrades to a refund rather than to item loss. */
                ItemStack back = item.clone();
                back.setAmount(rejected);
                for (ItemStack lost : from.addItem(back).values()) dropAt(spillAt, lost);
            }
        }
        return moved;
    }

    private void pushToFacing(Block block, Bay bay, int budget) {
        if (!(block.getBlockData() instanceof Directional directional)) return;
        Block target = block.getRelative(directional.getFacing());
        Bay other = bays.get(key(target.getLocation()));
        if (other != null) {
            if (move(bay.inv, other.inv, budget, bay.at) > 0) { bay.dirty = true; other.dirty = true; }
            return;
        }
        if (!(target.getState(false) instanceof Container container)) {
            /** A container minecart in the facing block space is a destination, just as it is for vanilla. */
            Inventory cart = cartInventory(target);
            if (cart != null && move(bay.inv, cart, budget, bay.at) > 0) bay.dirty = true;
            return;
        }
        Inventory into = container.getInventory();
        if (into instanceof FurnaceInventory) {
            /** Vanilla routes by direction rather than by first free slot: down into the smelting slot,
             *  from the side into fuel. Using addItem here would happily drop coal into the ingredient
             *  slot and quietly break every furnace array on the server. */
            int index = directional.getFacing() == BlockFace.DOWN ? 0 : 1;
            if (moveIntoSlot(bay.inv, into, index, budget) > 0) bay.dirty = true;
            return;
        }
        if (into instanceof org.bukkit.inventory.BrewerInventory) {
            /** Brewing stands route by face too: from above only the ingredient slot is reachable, from the
             *  side only fuel and the three bottle slots. Without this, blaze powder lands in a bottle slot
             *  and the stand stops working. */
            int[] slots = directional.getFacing() == BlockFace.DOWN ? new int[]{3} : new int[]{4, 0, 1, 2};
            int moved = 0;
            for (int index : slots) {
                if (moved >= budget) break;
                moved += moveIntoSlot(bay.inv, into, index, budget - moved, item -> brewingSlotAccepts(index, item));
            }
            if (moved > 0) bay.dirty = true;
            return;
        }
        if (move(bay.inv, into, budget, bay.at) > 0) bay.dirty = true;
    }

    /** Which brewing-stand slot will take which item, mirroring vanilla's own placement rules. */
    private static boolean brewingSlotAccepts(int slot, ItemStack item) {
        boolean blaze = item.getType() == Material.BLAZE_POWDER;
        boolean bottle = switch (item.getType()) {
            case POTION, SPLASH_POTION, LINGERING_POTION, GLASS_BOTTLE -> true;
            default -> false;
        };
        return switch (slot) {
            case 4 -> blaze;
            case 3 -> !blaze && !bottle;
            default -> bottle;
        };
    }

    /** Transfer into one specific destination slot, for containers where vanilla cares which slot an item
     *  lands in. Same contract as move: the source is decremented first and only the accepted amount is
     *  ever written, so the count on both sides always adds up. */
    private int moveIntoSlot(Inventory from, Inventory to, int index, int budget) {
        return moveIntoSlot(from, to, index, budget, item -> true);
    }

    private int moveIntoSlot(Inventory from, Inventory to, int index, int budget, java.util.function.Predicate<ItemStack> accepts) {
        int moved = 0;
        for (int slot = 0; slot < from.getSize() && moved < budget; slot++) {
            ItemStack live = from.getItem(slot);
            if (live == null || live.getType().isAir()) continue;
            if (!accepts.test(live)) continue;
            ItemStack item = live.clone();
            ItemStack existing = to.getItem(index);
            ItemStack current = existing == null ? null : existing.clone();
            boolean empty = current == null || current.getType().isAir();
            if (!empty && !current.isSimilar(item)) continue;
            int room = empty ? item.getMaxStackSize() : Math.max(0, current.getMaxStackSize() - current.getAmount());
            int take = Math.min(Math.min(item.getAmount(), budget - moved), room);
            if (take <= 0) continue;
            ItemStack keep = item.clone();
            keep.setAmount(item.getAmount() - take);
            from.setItem(slot, keep.getAmount() <= 0 ? null : keep);
            ItemStack placed = item.clone();
            placed.setAmount((empty ? 0 : current.getAmount()) + take);
            to.setItem(index, placed);
            moved += take;
        }
        return moved;
    }

    /** Moves up to `budget` individual items between two inventories, conserving every one of them.
     *
     *  The source is decremented BEFORE the destination is offered anything, and whatever the destination
     *  will not take goes straight back into the source -- into space this method just freed itself, so the
     *  refund cannot fail. At no point does a single item exist in both inventories, which is what makes
     *  duplication impossible here rather than merely unlikely. Returns the number of items delivered. */
    private int move(Inventory from, Inventory to, int budget, Location spillAt) {
        int moved = 0, rejections = 0;
        for (int slot = 0; slot < from.getSize() && moved < budget; slot++) {
            ItemStack live = from.getItem(slot);
            if (live == null || live.getType().isAir()) continue;
            /** getItem hands back a LIVE MIRROR of the slot, not a copy: the moment the slot is overwritten
             *  that reference changes underneath us, and reading it afterwards to build a refund would
             *  produce air. Detach from the slot before touching it. */
            ItemStack item = live.clone();
            /** Take only what the destination can actually hold.
             *
             *  This used to pull the item out FIRST and refund whatever the destination rejected -- and the
             *  refund could itself fail, at which point the leftovers were dropped into the world with
             *  dropItemNaturally, i.e. flung outward. That is the reported "items above the hopper explode
             *  when it is still full": a full industrial hopper under a full chest, or under a furnace whose
             *  slots will not take the item back, scattering the source's contents across the floor every
             *  sweep. Asking how much fits before touching anything removes the refund path entirely. */
            int room = space(to, item);
            if (room <= 0) {
                /** Full for this material. Try a couple more slots in case something else fits, then stop
                 *  scanning -- the same give-up rule the rejection path used. */
                if (++rejections >= 3) break;
                continue;
            }
            int take = Math.min(Math.min(item.getAmount(), budget - moved), room);
            ItemStack piece = item.clone();
            piece.setAmount(take);
            ItemStack keep = item.clone();
            keep.setAmount(item.getAmount() - take);
            from.setItem(slot, keep.getAmount() <= 0 ? null : keep);
            int rejected = total(to.addItem(piece).values());
            moved += take - rejected;
            if (rejected > 0) {
                ItemStack back = item.clone();
                back.setAmount(rejected);
                for (ItemStack lost : from.addItem(back).values()) dropAt(spillAt, lost);
                /** The destination is full for this material. Try a couple more slots in case it has room
                 *  for something else, then give up for this tick rather than scanning all 27 for nothing. */
                if (++rejections >= 3) return moved;
            }
        }
        return moved;
    }

    private static int total(Collection<ItemStack> stacks) {
        int sum = 0;
        for (ItemStack stack : stacks) if (stack != null) sum += stack.getAmount();
        return sum;
    }

    /** Free room for `stack` across an inventory, counting partial stacks of the same item. */
    private static int space(Inventory inv, ItemStack stack) {
        int room = 0;
        for (ItemStack slot : inv.getStorageContents()) {
            if (slot == null || slot.getType().isAir()) room += stack.getMaxStackSize();
            else if (slot.isSimilar(stack)) room += Math.max(0, slot.getMaxStackSize() - slot.getAmount());
            if (room >= stack.getAmount()) return room;
        }
        return room;
    }

    /** Spilling is always a symptom, never a plan: every remaining call site is a last-resort refund that
     *  should be unreachable now that capacity is checked before anything is removed. Logged so that if it
     *  ever does fire, it says so instead of silently flinging somebody's farm across the floor. */
    private static void dropAt(Location at, ItemStack item) {
        if (at == null || item == null || item.getType().isAir()) return;
        org.bukkit.Bukkit.getLogger().warning("[SMPCore] Industrial hopper spilled " + item.getAmount() + "x "
                + item.getType() + " at " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ()
                + " - this should be unreachable; please report it.");
        /** Location.getWorld() throws rather than returning null once its world has been unloaded, and this
         *  is reached from refund paths that can run in the same tick a world goes away. There is nowhere to
         *  drop an item in a world that no longer exists, so the only correct answer is to do nothing. */
        World world;
        try { world = at.getWorld(); } catch (IllegalArgumentException unloaded) { return; }
        if (world == null) return;
        /** Above the block, with no random throw. The old landing point was the block's own centre, and
         *  the centre of a hopper is inside its funnel: an item dropped there is shoved straight back out
         *  by collision and ends up somewhere else entirely, with dropItemNaturally adding a random kick on
         *  top. A refund should land where its owner can pick it up again. */
        Item spilled = world.dropItem(at.clone().add(.5, 1.2, .5), item);
        spilled.setVelocity(new org.bukkit.util.Vector());
    }

    // ------------------------------------------------------------------ diagnostics
    /** Reports the authoritative state of one hopper. This is the measurement instrument for conservation
     *  testing as much as a support tool: the contents live in a live inventory and in serialised block
     *  data, neither of which can be read with /data get, so without this there is no way to state what a
     *  hopper is actually holding at a given moment. */
    String describe(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        Bay bay = bay(block);
        if (bay == null) return "No Industrial Hopper at " + world.getName() + " " + x + " " + y + " " + z
                + " (block is " + block.getType() + ").";
        Map<Material, Integer> tally = new java.util.LinkedHashMap<>();
        int stored = 0;
        for (ItemStack item : bay.inv.getContents())
            if (item != null && !item.getType().isAir()) {
                stored += item.getAmount();
                tally.merge(item.getType(), item.getAmount(), Integer::sum);
            }
        int nativeHeld = 0, calibration = 0;
        if (block.getState(false) instanceof Container container)
            for (ItemStack item : container.getInventory().getContents()) {
                if (item == null || item.getType().isAir()) continue;
                if (isSignalMarker(item)) calibration += item.getAmount(); else nativeHeld += item.getAmount();
            }
        boolean enabled = !(block.getBlockData() instanceof org.bukkit.block.data.type.Hopper data) || data.isEnabled();
        return "Industrial Hopper " + world.getName() + " " + x + " " + y + " " + z
                + " | facing=" + (block.getBlockData() instanceof Directional d ? d.getFacing() : "?")
                + " enabled=" + enabled + " dirty=" + bay.dirty + " viewers=" + bay.inv.getViewers().size()
                + " STORED=" + stored + " native=" + nativeHeld
                + " comparator=" + comparatorLevel(bay.inv) + " (calibration " + calibration + "/" + signalUnits(comparatorLevel(bay.inv)) + ")"
                + " " + tally;
    }

    /** Exact item count at a position, computed server-side. Console tooling cannot count a container
     *  reliably from /data get -- long NBT is truncated in the reply -- and it cannot see an industrial
     *  hopper's contents at all, so measurement has to happen here. */
    String count(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        Bay bay = bays.get(key(block.getLocation()));
        int stored = 0;
        if (bay != null)
            for (ItemStack item : bay.inv.getContents())
                if (item != null && !item.getType().isAir()) stored += item.getAmount();
        int held = 0;
        if (block.getState(false) instanceof Container container)
            for (ItemStack item : container.getInventory().getContents())
                if (item != null && !item.getType().isAir() && !isSignalMarker(item)) held += item.getAmount();
        return "COUNT " + x + " " + y + " " + z + " type=" + block.getType() + " container=" + held
                + " industrial=" + (bay == null ? -1 : stored) + " TOTAL=" + (held + stored);
    }

    /** All three positions of a source-above / hopper / destination-below column, counted in ONE tick.
     *
     *  Counting them with three separate commands is not a measurement of conservation: at nine items a
     *  tick the contents move between the reads, and the same items get counted twice or not at all. This
     *  exists so the invariant can be checked against a state that actually existed at one instant. */
    String rig(World world, int x, int y, int z) {
        int above = totalAt(world, x, y + 1, z), self = totalAt(world, x, y, z), below = totalAt(world, x, y - 1, z);
        /** Items lying on the floor count too. A hopper whose block is removed returns its contents to the
         *  world rather than deleting them, and a conservation check that ignored the ground would score
         *  that as a loss. */
        int ground = 0;
        for (org.bukkit.entity.Entity entity : world.getNearbyEntities(new Location(world, x + .5, y + .5, z + .5), 8, 8, 8))
            if (entity instanceof Item dropped) ground += dropped.getItemStack().getAmount();
        return "RIG source=" + above + " hopper=" + self + " dest=" + below + " ground=" + ground
                + " TOTAL=" + (above + self + below + ground);
    }

    private int totalAt(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        int sum = 0;
        Bay bay = bays.get(key(block.getLocation()));
        if (bay != null)
            for (ItemStack item : bay.inv.getContents())
                if (item != null && !item.getType().isAir()) sum += item.getAmount();
        if (block.getState(false) instanceof Container container)
            for (ItemStack item : container.getInventory().getContents())
                if (item != null && !item.getType().isAir() && !isSignalMarker(item)) sum += item.getAmount();
        return sum;
    }

    /** Every hopper currently in memory, with what it holds. */
    List<String> describeAll() {
        List<String> out = new ArrayList<>();
        for (Bay bay : bays.values()) {
            int stored = 0;
            for (ItemStack item : bay.inv.getContents())
                if (item != null && !item.getType().isAir()) stored += item.getAmount();
            out.add(bay.id + " stored=" + stored + " dirty=" + bay.dirty);
        }
        return out;
    }

    // ------------------------------------------------------------------ self test
    boolean selfTest() {
        if (!isIndustrialItem(createItem())) return false;
        /** What the block stores has to come back unchanged. */
        ItemStack[] stored = new ItemStack[SLOTS];
        stored[0] = new ItemStack(Material.DIAMOND, 12);
        stored[26] = new ItemStack(Material.REDSTONE, 7);
        ItemStack[] back = ItemStack.deserializeItemsFromBytes(ItemStack.serializeItemsAsBytes(stored));
        if (back.length < SLOTS || back[0] == null || back[0].getAmount() != 12
                || back[26] == null || back[26].getAmount() != 7) return false;

        /** Item conservation, run for real against live inventories rather than argued about in a comment.
         *  1728 diamonds are pushed nine at a time into a destination that is deliberately too small, so
         *  the run covers ordinary transfer, partial acceptance and a completely full destination. The
         *  invariant is re-checked after EVERY pass: source + destination never changes. */
        Inventory source = Bukkit.createInventory(null, SLOTS), destination = Bukkit.createInventory(null, 9);
        for (int slot = 0; slot < SLOTS; slot++) source.setItem(slot, new ItemStack(Material.DIAMOND, 64));
        int expected = SLOTS * 64;
        for (int pass = 0; pass < 400; pass++) {
            move(source, destination, 9, null);
            if (count(source) + count(destination) != expected) return false;
        }
        if (count(destination) != 9 * 64 || count(source) != expected - 9 * 64) return false;

        /** And back the other way: a full source must empty completely without inventing anything. */
        Inventory drain = Bukkit.createInventory(null, SLOTS);
        for (int pass = 0; pass < 400; pass++) {
            move(destination, drain, 9, null);
            if (count(destination) + count(drain) != 9 * 64) return false;
        }
        if (count(destination) != 0 || count(drain) != 9 * 64) return false;

        /** NINE ITEMS PER OPERATION, not nine per slot. A source holding one item in each of 27 slots must
         *  give up exactly nine per cycle, not 27 -- that distinction is the whole throughput contract of
         *  the block and it is invisible in a test that only uses full stacks. */
        Inventory sparse = Bukkit.createInventory(null, SLOTS), sink = Bukkit.createInventory(null, 54);
        for (int slot = 0; slot < SLOTS; slot++) sparse.setItem(slot, new ItemStack(Material.DIAMOND, 1));
        if (move(sparse, sink, 9, null) != 9 || count(sink) != 9 || count(sparse) != SLOTS - 9) return false;
        Inventory oneStack = Bukkit.createInventory(null, SLOTS), sink2 = Bukkit.createInventory(null, 54);
        oneStack.setItem(0, new ItemStack(Material.DIAMOND, 64));
        if (move(oneStack, sink2, 9, null) != 9 || count(oneStack) != 55) return false;

        /** Comparator parity: the twenty-seven-slot reading, and the calibration weight that makes vanilla's
         *  five-slot formula reproduce it. Every level from empty to full must round-trip exactly. */
        Inventory gauge = Bukkit.createInventory(null, SLOTS);
        if (comparatorLevel(gauge) != 0 || signalUnits(0) != 0) return false;
        gauge.setItem(0, new ItemStack(Material.DIAMOND, 1));
        if (comparatorLevel(gauge) != 1) return false;
        for (int slot = 0; slot < SLOTS; slot++) gauge.setItem(slot, new ItemStack(Material.DIAMOND, 64));
        if (comparatorLevel(gauge) != 15 || signalUnits(15) != 5 * 64) return false;
        for (int level = 1; level <= 15; level++) {
            int units = signalUnits(level);
            if (units <= 0 || units > 5 * 64) return false;
            /** What vanilla will read back off five 64-stack slots holding `units` items. */
            if ((int) Math.floor(units / 64.0 / 5 * 14) + 1 != level) return false;
            if (level > 1 && units <= signalUnits(level - 1)) return false;
        }
        /** A half-full store must not read as full, or a comparator lock would cut a farm off early. */
        Inventory half = Bukkit.createInventory(null, SLOTS);
        for (int slot = 0; slot < SLOTS; slot++) half.setItem(slot, new ItemStack(Material.DIAMOND, 32));
        int mid = comparatorLevel(half);
        return mid > 1 && mid < 15 && signalUnits(mid) < 5 * 64;
    }

    private static int count(Inventory inv) {
        int sum = 0;
        for (ItemStack item : inv.getContents()) if (item != null && !item.getType().isAir()) sum += item.getAmount();
        return sum;
    }
}
