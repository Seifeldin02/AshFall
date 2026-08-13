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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Industrial Hopper: a hopper with 27 slots that moves nine items a tick in each direction.
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
 *  Known deviation: a vanilla hopper underneath an Industrial Hopper cannot pull from it, because the five
 *  slots vanilla would pull from are always empty. Point the Industrial Hopper at it instead -- that is the
 *  normal way to chain hoppers, and it runs at nine items a tick rather than one per eight. */
final class IndustrialHopperService implements Listener {

    private static final int SLOTS = 27;

    /** One industrial hopper: its location and the single inventory that IS its contents.
     *
     *  The Bay is also the inventory's holder, so any event carrying the inventory hands the Bay straight
     *  back -- there is no lookup that could resolve to the wrong block or invent a second store. */
    private final class Bay implements InventoryHolder {
        private final Location at;
        private final Inventory inv;
        private boolean dirty;
        private Bay(Location at) {
            this.at = at;
            this.inv = Bukkit.createInventory(this, SLOTS,
                    Component.text("Industrial Hopper", NamedTextColor.DARK_AQUA));
        }
        @Override public Inventory getInventory() { return inv; }
    }

    private final SMPCore plugin;
    private final NamespacedKey blockKey, itemKey, dataKey;
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
        registerRecipe();
        /** One pass over chunks that are ALREADY loaded when the plugin enables. ChunkLoadEvent covers
         *  everything after this point, but spawn chunks are up before we are. Bounded by what is already
         *  in memory -- this is not a world scan. */
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) adopt(chunk);
        });
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 1L, 1L);
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushDirty, 20L, 20L);
    }

    void shutdown() {
        if (sweepTask != null) sweepTask.cancel();
        if (flushTask != null) flushTask.cancel();
        for (Bay bay : new ArrayList<>(bays.values())) { closeViewers(bay); flush(bay); }
        bays.clear();
    }

    // ------------------------------------------------------------------ item + recipe
    ItemStack createItem() {
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Industrial Hopper", NamedTextColor.AQUA));
        meta.lore(List.of(
                Component.text("27 slots of storage.", NamedTextColor.GRAY),
                Component.text("Moves up to 9 items per tick.", NamedTextColor.GRAY)));
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
    private void forget(Bay bay) { closeViewers(bay); bays.remove(key(bay.at)); }

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
            World world = bay.at.getWorld();
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
            if (!chunk.getWorld().equals(bay.at.getWorld())) continue;
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
                if (item != null && !item.getType().isAir()) world.dropItemNaturally(at, item);
            container.getInventory().clear();
        }
        bays.remove(key(bay.at));
    }

    /** Returns the contents to the world when the block went away without an event we could see. Logged,
     *  because it means something outside this plugin removed a populated hopper and an admin should be
     *  able to see how much came back and where. */
    private void spill(Bay bay) {
        World world = bay.at.getWorld();
        if (world == null) {
            plugin.getLogger().warning("[IndustrialHopper] " + key(bay.at)
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
            plugin.getLogger().warning("[IndustrialHopper] " + key(bay.at)
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

    /** Everything vanilla would move into or out of the native five slots is intercepted here.
     *
     *  Out: cancelled outright. The native slots hold nothing, and output is the sweep's job.
     *  In, from the block directly above: cancelled and left to the sweep, which pulls nine a tick.
     *  In, from anywhere else (a hopper or dropper aimed at us): performed here, into the real inventory. */
    @EventHandler(ignoreCancelled = true)
    public void moveItem(InventoryMoveItemEvent event) {
        if (bayOf(event.getSource()) != null) { event.setCancelled(true); return; }
        Bay destination = bayOf(event.getDestination());
        if (destination == null) return;
        event.setCancelled(true);
        Location from = event.getSource().getLocation();
        if (from != null && from.getWorld() != null && from.getWorld().equals(destination.at.getWorld())
                && from.getBlockX() == destination.at.getBlockX()
                && from.getBlockY() == destination.at.getBlockY() + 1
                && from.getBlockZ() == destination.at.getBlockZ()) return;
        ItemStack wanted = event.getItem().clone();
        /** Remove from the source first, then credit exactly what actually came out of it. */
        int notRemoved = total(event.getSource().removeItem(wanted.clone()).values());
        int taken = wanted.getAmount() - notRemoved;
        if (taken <= 0) return;
        ItemStack held = wanted.clone();
        held.setAmount(taken);
        for (ItemStack rejected : destination.inv.addItem(held).values())
            for (ItemStack lost : event.getSource().addItem(rejected).values()) dropAt(destination.at, lost);
        destination.dirty = true;
    }

    /** Item entities floating above the hopper. Capacity is checked before the entity is touched, so the
     *  entity is only ever destroyed once the inventory has room for at least part of it. */
    @EventHandler(ignoreCancelled = true)
    public void pickup(InventoryPickupItemEvent event) {
        Bay bay = bayOf(event.getInventory());
        if (bay == null) return;
        event.setCancelled(true);
        Item entity = event.getItem();
        ItemStack stack = entity.getItemStack().clone();
        if (space(bay.inv, stack) <= 0) return;
        entity.remove();
        for (ItemStack rejected : bay.inv.addItem(stack).values())
            entity.getWorld().dropItem(entity.getLocation(), rejected);
        bay.dirty = true;
    }

    // ------------------------------------------------------------------ transfer
    private void sweep() {
        if (bays.isEmpty()) return;
        int budget = Math.max(1, plugin.getConfig().getInt("industrial-hopper.items-per-tick", 9));
        for (Iterator<Bay> it = bays.values().iterator(); it.hasNext(); ) {
            Bay bay = it.next();
            World world = bay.at.getWorld();
            if (world == null) { spill(bay); it.remove(); continue; }
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
            if (block.getBlockData() instanceof org.bukkit.block.data.type.Hopper data && !data.isEnabled()) continue;
            drainNative(block, bay);
            pullFromAbove(block, bay, budget);
            pushToFacing(block, bay, budget);
        }
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
        if (!(above.getState(false) instanceof Container source)) return;
        if (move(source.getInventory(), bay.inv, budget, above.getLocation()) > 0) bay.dirty = true;
    }

    private void pushToFacing(Block block, Bay bay, int budget) {
        if (!(block.getBlockData() instanceof Directional directional)) return;
        Block target = block.getRelative(directional.getFacing());
        Bay other = bays.get(key(target.getLocation()));
        if (other != null) {
            if (move(bay.inv, other.inv, budget, bay.at) > 0) { bay.dirty = true; other.dirty = true; }
            return;
        }
        if (!(target.getState(false) instanceof Container container)) return;
        Inventory into = container.getInventory();
        if (into instanceof FurnaceInventory) {
            /** Vanilla routes by direction rather than by first free slot: down into the smelting slot,
             *  from the side into fuel. Using addItem here would happily drop coal into the ingredient
             *  slot and quietly break every furnace array on the server. */
            int index = directional.getFacing() == BlockFace.DOWN ? 0 : 1;
            if (moveIntoSlot(bay.inv, into, index, budget) > 0) bay.dirty = true;
            return;
        }
        if (move(bay.inv, into, budget, bay.at) > 0) bay.dirty = true;
    }

    /** Transfer into one specific destination slot, for containers where vanilla cares which slot an item
     *  lands in. Same contract as move: the source is decremented first and only the accepted amount is
     *  ever written, so the count on both sides always adds up. */
    private int moveIntoSlot(Inventory from, Inventory to, int index, int budget) {
        int moved = 0;
        for (int slot = 0; slot < from.getSize() && moved < budget; slot++) {
            ItemStack live = from.getItem(slot);
            if (live == null || live.getType().isAir()) continue;
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
            int take = Math.min(item.getAmount(), budget - moved);
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

    private static void dropAt(Location at, ItemStack item) {
        if (at == null || at.getWorld() == null || item == null || item.getType().isAir()) return;
        at.getWorld().dropItemNaturally(at.clone().add(.5, .5, .5), item);
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
        int nativeHeld = 0;
        if (block.getState(false) instanceof Container container)
            for (ItemStack item : container.getInventory().getContents())
                if (item != null && !item.getType().isAir()) nativeHeld += item.getAmount();
        boolean enabled = !(block.getBlockData() instanceof org.bukkit.block.data.type.Hopper data) || data.isEnabled();
        return "Industrial Hopper " + world.getName() + " " + x + " " + y + " " + z
                + " | facing=" + (block.getBlockData() instanceof Directional d ? d.getFacing() : "?")
                + " enabled=" + enabled + " dirty=" + bay.dirty + " viewers=" + bay.inv.getViewers().size()
                + " STORED=" + stored + " native=" + nativeHeld + " " + tally;
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
                if (item != null && !item.getType().isAir()) held += item.getAmount();
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
                if (item != null && !item.getType().isAir()) sum += item.getAmount();
        return sum;
    }

    /** Every hopper currently in memory, with what it holds. */
    List<String> describeAll() {
        List<String> out = new ArrayList<>();
        for (Bay bay : bays.values()) {
            int stored = 0;
            for (ItemStack item : bay.inv.getContents())
                if (item != null && !item.getType().isAir()) stored += item.getAmount();
            out.add(key(bay.at) + " stored=" + stored + " dirty=" + bay.dirty);
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
        return count(destination) == 0 && count(drain) == 9 * 64;
    }

    private static int count(Inventory inv) {
        int sum = 0;
        for (ItemStack item : inv.getContents()) if (item != null && !item.getType().isAir()) sum += item.getAmount();
        return sum;
    }
}
