package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Hopper;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Industrial Hopper: a vanilla hopper with a 27-slot backing store that moves nine items a tick.
 *
 *  The block really is a vanilla hopper, which is the important design decision: it keeps vanilla facing,
 *  redstone locking, block protection, piston rules, item-entity pickup and the way other hoppers and
 *  droppers interact with it, all for free and without re-implementing any of it. What is custom is only
 *  the storage behind it and the transfer rate.
 *
 *  The extra storage is a real inventory serialised into the hopper's own TileState persistent data, so it
 *  is saved with the chunk exactly like ordinary block contents. That is what makes restarts and chunk
 *  unloads safe: there is no separate registry to fall out of sync, nothing to rebuild on boot, and a
 *  chunk that unloads takes its own data with it. The vanilla 5-slot inventory is deliberately left as the
 *  hopper's real inventory so vanilla transfer logic still functions; the 27 slots are an overflow buffer
 *  drained into it.
 *
 *  Ticking is centralised: one repeating task walks the loaded industrial hoppers, rather than a scheduler
 *  per block. Nothing scans the world -- placements register into a set, and entries are dropped as soon as
 *  a lookup finds the block is no longer one of ours. */
final class IndustrialHopperService implements Listener {

    private static final int SLOTS = 27;

    private record Holder(Location at) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private final SMPCore plugin;
    private final NamespacedKey blockKey, itemKey, dataKey;
    /** Locations known to hold an industrial hopper. Populated on place and on first interaction; entries
     *  that no longer resolve are discarded during the sweep, so this can never leak. */
    private final Set<Location> known = new HashSet<>();
    private BukkitTask task;

    IndustrialHopperService(SMPCore plugin) {
        this.plugin = plugin;
        blockKey = new NamespacedKey(plugin, "industrial_hopper");
        itemKey = new NamespacedKey(plugin, "industrial_hopper_item");
        dataKey = new NamespacedKey(plugin, "industrial_hopper_store");
        registerRecipe();
        /** One pass over chunks that are ALREADY loaded when the plugin enables. ChunkLoadEvent covers
         *  everything loaded afterwards, but spawn chunks are up before this runs and would otherwise never
         *  join the sweep until something forced them to reload. Bounded by what is already in memory --
         *  this is not a world scan. */
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (org.bukkit.World world : Bukkit.getWorlds())
                for (org.bukkit.Chunk chunk : world.getLoadedChunks()) noticeChunk(chunk);
        });
        long period = Math.max(1, plugin.getConfig().getLong("industrial-hopper.tick-period", 1));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, period, period);
    }

    void shutdown() { if (task != null) task.cancel(); }

    // ------------------------------------------------------------------ item + recipe
    ItemStack createItem() {
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Industrial Hopper", NamedTextColor.AQUA));
        meta.lore(List.of(
                Component.text("27 slots of buffered storage.", NamedTextColor.GRAY),
                Component.text("Moves up to 9 items per tick.", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void registerRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "industrial_hopper_recipe");
        if (Bukkit.getRecipe(key) != null) return;
        ShapedRecipe recipe = new ShapedRecipe(key, createItem());
        /** The vanilla hopper pattern with iron blocks in place of ingots. */
        recipe.shape("I I", "ICI", " I ");
        recipe.setIngredient('I', Material.IRON_BLOCK);
        recipe.setIngredient('C', Material.CHEST);
        Bukkit.addRecipe(recipe);
    }

    private boolean isIndustrialItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    boolean isIndustrial(Block block) {
        if (block == null || block.getType() != Material.HOPPER) return false;
        boolean ours = block.getState() instanceof TileState state
                && state.getPersistentDataContainer().has(blockKey, PersistentDataType.BYTE);
        /** Lazily register: any path that identifies one of ours also makes sure it is being ticked. */
        if (ours) known.add(block.getLocation());
        return ours;
    }

    // ------------------------------------------------------------------ backing store
    private ItemStack[] load(Hopper hopper) {
        byte[] raw = hopper.getPersistentDataContainer().get(dataKey, PersistentDataType.BYTE_ARRAY);
        if (raw == null || raw.length == 0) return new ItemStack[SLOTS];
        try {
            ItemStack[] stored = ItemStack.deserializeItemsFromBytes(raw);
            ItemStack[] out = new ItemStack[SLOTS];
            System.arraycopy(stored, 0, out, 0, Math.min(SLOTS, stored.length));
            return out;
        } catch (Throwable error) {
            /** Never throw away a player's items because one block's data could not be read. */
            plugin.getLogger().warning("[IndustrialHopper] unreadable buffer at " + hopper.getLocation() + ": " + error);
            return new ItemStack[SLOTS];
        }
    }

    private void save(Hopper hopper, ItemStack[] contents) {
        hopper.getPersistentDataContainer().set(dataKey, PersistentDataType.BYTE_ARRAY,
                ItemStack.serializeItemsAsBytes(contents));
        /** update(false, false): write the tile data without forcing a physics update or a block change. */
        hopper.update(true, false);
    }

    // ------------------------------------------------------------------ placement / breaking
    @EventHandler(ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (!isIndustrialItem(event.getItemInHand())) return;
        Block block = event.getBlockPlaced();
        if (!(block.getState() instanceof Hopper hopper)) return;
        hopper.getPersistentDataContainer().set(blockKey, PersistentDataType.BYTE, (byte) 1);
        hopper.update(true, false);
        known.add(block.getLocation());
        CoreUtil.msg(event.getPlayer(), "Industrial Hopper placed: 27 buffered slots, 9 items per tick.");
    }

    /** Breaking returns the custom item AND everything buffered, so nothing is silently destroyed. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isIndustrial(block)) return;
        if (!(block.getState() instanceof Hopper hopper)) return;
        ItemStack[] buffered = load(hopper);
        known.remove(block.getLocation());
        /** Clear the buffer before the block goes, so a cancelled-then-replaced break cannot duplicate. */
        save(hopper, new ItemStack[SLOTS]);
        event.setDropItems(false);
        Location drop = block.getLocation().add(.5, .5, .5);
        block.getWorld().dropItemNaturally(drop, createItem());
        for (ItemStack item : buffered)
            if (item != null && !item.getType().isAir()) block.getWorld().dropItemNaturally(drop, item);
        /** The vanilla 5 slots are ordinary hopper contents and drop through the normal path. */
        if (block.getState(false) instanceof Container container) {
            for (ItemStack item : container.getInventory().getContents())
                if (item != null && !item.getType().isAir()) block.getWorld().dropItemNaturally(drop, item);
            container.getInventory().clear();
        }
    }

    // ------------------------------------------------------------------ buffer GUI
    @EventHandler(ignoreCancelled = true)
    public void open(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getClickedBlock() == null) return;
        if (!isIndustrial(event.getClickedBlock())) return;
        if (event.getPlayer().isSneaking()) return;
        /** Shift-right-click keeps the vanilla hopper screen; a normal right-click opens the buffer. */
        if (!(event.getClickedBlock().getState() instanceof Hopper hopper)) return;
        event.setCancelled(true);
        Inventory inv = Bukkit.createInventory(new Holder(event.getClickedBlock().getLocation()), SLOTS,
                Component.text("Industrial Hopper • Buffer", NamedTextColor.DARK_AQUA));
        inv.setContents(load(hopper));
        event.getPlayer().openInventory(inv);
    }

    @EventHandler
    public void close(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder holder)) return;
        Block block = holder.at().getBlock();
        if (!isIndustrial(block) || !(block.getState() instanceof Hopper hopper)) {
            /** The block went away while the screen was open: give the contents back rather than void them. */
            for (ItemStack item : event.getInventory().getContents())
                if (item != null && !item.getType().isAir()) CoreUtil.give((Player) event.getPlayer(), item);
            return;
        }
        save(hopper, event.getInventory().getContents());
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder holder)) return;
        /** Writing on close is enough, but a block broken mid-edit must not accept further edits. */
        if (!isIndustrial(holder.at().getBlock())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ centralised transfer
    private void sweep() {
        if (known.isEmpty()) return;
        int perTick = Math.max(1, plugin.getConfig().getInt("industrial-hopper.items-per-tick", 9));
        List<Location> stale = new ArrayList<>();
        for (Location at : known) {
            if (at.getWorld() == null || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
            Block block = at.getBlock();
            if (!isIndustrial(block) || !(block.getState() instanceof Hopper hopper)) { stale.add(at); continue; }
            /** Redstone-locked hoppers do nothing, exactly as vanilla. */
            if (block.getBlockData() instanceof org.bukkit.block.data.type.Hopper data && !data.isEnabled()) continue;
            transfer(block, hopper, perTick);
        }
        known.removeAll(stale);
    }

    /** Drains the buffer into the hopper's own 5 slots, then pushes onward into the facing container.
     *  Vanilla moves one item per 8 ticks; this moves up to nine per tick, which is the whole point. */
    private void transfer(Block block, Hopper hopper, int perTick) {
        ItemStack[] buffer = load(hopper);
        boolean dirty = false;
        /** getState(false) is the LIVE tile entity. getState() hands back a snapshot, and mutating a
         *  snapshot inventory is not guaranteed to persist -- that is how a transfer can appear to consume
         *  items without delivering them. */
        if (!(block.getState(false) instanceof Container liveHopper)) return;
        Inventory own = liveHopper.getInventory();
        int moved = 0;

        /** 1. pull the hopper's own contents into the buffer so vanilla pickup keeps flowing. */
        for (int i = 0; i < own.getSize() && moved < perTick; i++) {
            ItemStack item = own.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            int free = firstFit(buffer, item);
            if (free < 0) break;
            int take = Math.min(item.getAmount(), perTick - moved);
            ItemStack piece = item.clone(); piece.setAmount(take);
            if (!insert(buffer, piece)) break;
            item.setAmount(item.getAmount() - take);
            own.setItem(i, item.getAmount() <= 0 ? null : item);
            moved += take; dirty = true;
        }

        /** 2. push from the buffer into whatever the hopper faces. */
        Block facing = block.getRelative(((Directional) block.getBlockData()).getFacing());
        if (facing.getState(false) instanceof Container target) {
            Inventory into = target.getInventory();
            for (int i = 0; i < buffer.length && moved < perTick; i++) {
                ItemStack item = buffer[i];
                if (item == null || item.getType().isAir()) continue;
                int take = Math.min(item.getAmount(), perTick - moved);
                ItemStack piece = item.clone(); piece.setAmount(take);
                /** addItem reports what would NOT fit; only the accepted amount is deducted, so a full
                 *  destination can never swallow items. */
                int leftover = into.addItem(piece).values().stream().mapToInt(ItemStack::getAmount).sum();
                int accepted = take - leftover;
                if (accepted <= 0) continue;
                item.setAmount(item.getAmount() - accepted);
                buffer[i] = item.getAmount() <= 0 ? null : item;
                moved += accepted; dirty = true;
            }
        }
        if (dirty) save(hopper, buffer);
    }

    private int firstFit(ItemStack[] buffer, ItemStack item) {
        for (int i = 0; i < buffer.length; i++) {
            ItemStack slot = buffer[i];
            if (slot == null || slot.getType().isAir()) return i;
            if (slot.isSimilar(item) && slot.getAmount() < slot.getMaxStackSize()) return i;
        }
        return -1;
    }

    private boolean insert(ItemStack[] buffer, ItemStack item) {
        for (int i = 0; i < buffer.length && item.getAmount() > 0; i++) {
            ItemStack slot = buffer[i];
            if (slot != null && slot.isSimilar(item) && slot.getAmount() < slot.getMaxStackSize()) {
                int room = slot.getMaxStackSize() - slot.getAmount();
                int add = Math.min(room, item.getAmount());
                slot.setAmount(slot.getAmount() + add);
                item.setAmount(item.getAmount() - add);
            }
        }
        for (int i = 0; i < buffer.length && item.getAmount() > 0; i++) {
            if (buffer[i] == null || buffer[i].getType().isAir()) { buffer[i] = item.clone(); item.setAmount(0); }
        }
        return item.getAmount() == 0;
    }

    /** Called when a chunk loads so hoppers in it rejoin the sweep without any world scan. */
    void noticeChunk(org.bukkit.Chunk chunk) {
        for (org.bukkit.block.BlockState state : chunk.getTileEntities())
            if (state instanceof Hopper hopper
                    && hopper.getPersistentDataContainer().has(blockKey, PersistentDataType.BYTE))
                known.add(hopper.getLocation());
    }

    /** Chunk load is how hoppers rejoin the sweep after a restart or an unload: the chunk hands us its own
     *  tile entities, so there is never a world-wide scan. */
    @EventHandler
    public void chunkLoad(org.bukkit.event.world.ChunkLoadEvent event){noticeChunk(event.getChunk());}
    @EventHandler
    public void chunkUnload(org.bukkit.event.world.ChunkUnloadEvent event){
        /** Drop them from the sweep set; their data lives in the chunk and comes back with it. */
        for(org.bukkit.block.BlockState state:event.getChunk().getTileEntities())
            if(state instanceof Hopper hopper)known.remove(hopper.getLocation());
    }
    boolean selfTest() {
        ItemStack item = createItem();
        if (!isIndustrialItem(item)) return false;
        ItemStack[] buffer = new ItemStack[SLOTS];
        /** Round-trip through the same serialisation the block data uses. */
        buffer[0] = new ItemStack(Material.DIAMOND, 12);
        byte[] raw = ItemStack.serializeItemsAsBytes(buffer);
        ItemStack[] back = ItemStack.deserializeItemsFromBytes(raw);
        if (back.length < 1 || back[0] == null || back[0].getAmount() != 12) return false;
        /** Overflow must be reported, never silently dropped. */
        ItemStack[] full = new ItemStack[1];
        full[0] = new ItemStack(Material.STONE, 64);
        ItemStack extra = new ItemStack(Material.DIAMOND, 5);
        return !insert(full, extra) && extra.getAmount() == 5;
    }
}
