package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
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
    private final SMPCore plugin;private final Database db;private final NamespacedKey key;private YamlConfiguration config;private BukkitTask lifecycleTask,buffTask;
    RelicService(SMPCore plugin){this.plugin=plugin;this.db=plugin.db();this.key=new NamespacedKey(plugin,"relic");reload();lifecycleTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::lifecycleTick,1200L,12000L);buffTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::buffTick,20L,40L);}
    void shutdown(){if(lifecycleTask!=null)lifecycleTask.cancel();if(buffTask!=null)buffTask.cancel();}
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
    boolean give(Player player,String relicKey){if(!mint(relicKey,CoreUtil.id(player),player.getName()))return false;CoreUtil.give(player,create(relicKey));markUsed(relicKey);discovery(player,relicKey,"found");return true;}
    void discover(Player player,ItemStack item){
        String relicKey=keyOf(item);if(relicKey==null)return;Database.RelicLifecycleRow row=db.relicLifecycle(relicKey);if(row==null)return;
        markUsed(relicKey);
        if("hidden".equals(row.owner())||"ELIGIBLE".equals(row.status())||!row.active()){db.confirmRelic(relicKey,CoreUtil.id(player),player.getName());discovery(player,relicKey,"uncovered");}
        else if(row.owner().equals(CoreUtil.id(player)))db.confirmRelic(relicKey,CoreUtil.id(player),player.getName());
        else{db.confirmRelic(relicKey,CoreUtil.id(player),player.getName());discovery(player,relicKey,"claimed");}
    }
    @EventHandler public void despawn(org.bukkit.event.entity.ItemDespawnEvent event){itemLost(event.getEntity());}

    /** Relics must live only in an active player inventory — dropping, looting from a PvP grave, direct
     *  trading, and auctioning are all still fine (none of those route through a vanilla-typed container
     *  GUI or SMPCore's own Ender Storage pages, so none of these three handlers touch them at all). Only
     *  Ender Storage, chests/barrels/shulkers/furnaces/hoppers/etc, and faction storage (which is just those
     *  same vanilla container types placed in claimed territory) are blocked. */
    private boolean isPersistentStorage(Inventory inventory){
        if(inventory==null)return false;
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
    /** Tasteful, live-only chat marker — gated on the actual Sovereign of Ashfall rank (which itself requires
     *  every prior rank plus current relic ownership), not on relic ownership alone. Owning a relic without
     *  having earned the rank must never show the symbol. Prepends rather than replaces the format string so it
     *  composes safely with whatever the server's base chat format already is. */
    @EventHandler(ignoreCancelled=true) public void relicChatSymbol(AsyncPlayerChatEvent event){
        if(!plugin.progress().sovereign(CoreUtil.id(event.getPlayer())))return;
        event.setFormat("§d◆ §r"+event.getFormat());
    }
    private void discovery(Player player,String relicKey,String verb){plugin.getServer().broadcast(Component.text("✦ RELIC DISCOVERED ",NamedTextColor.LIGHT_PURPLE).append(Component.text(plugin.nicknames().displayName(player)+" "+verb+" "+displayName(relicKey)+".",NamedTextColor.GOLD)));plugin.progress().relicFound(player,displayName(relicKey));}
    /** Passive relics (no right-click ability) count as "used" simply by sitting in the active inventory —
     *  that's how their entire mechanic works, via buffTick()/the elite-multiplier methods running off
     *  whatever's equipped. Interactive relics (ashen_reprisal/colossus_core/warlords_ember) only count as
     *  used when their ability actually fires — see the end of each handler below — since carrying one
     *  unused for weeks shouldn't reset its own clock. */
    private static final Set<String> PASSIVE_RELICS=Set.of("crown_of_ash","wayfinder","oathblade");
    void confirmInventory(Player player){for(ItemStack item:player.getInventory().getContents()){String relicKey=keyOf(item);if(relicKey!=null){Database.RelicLifecycleRow row=db.relicLifecycle(relicKey);if(row!=null){db.confirmRelic(relicKey,CoreUtil.id(player),player.getName());if(PASSIVE_RELICS.contains(relicKey))markUsed(relicKey);}}}}
    boolean hideInLoot(List<ItemStack> loot){if(Math.random()>config.getDouble("lifecycle.exploration-chance",.0002))return false;for(String relicKey:List.of("wayfinder","oathblade")){if(mint(relicKey,"hidden","Undiscovered")){loot.add(create(relicKey));return true;}}return false;}
    /** Ticks, not milliseconds — "Minecraft days" must track the in-game clock (frozen while the server is
     *  down, unaffected by real-world calendar time) rather than wall-clock time. Reads the primary claims
     *  world's full-time; falls back to whatever world loaded first if that one is somehow unavailable. */
    private static final long MC_DAY_TICKS=24000L;
    private long mcTicksNow(){World world=plugin.getServer().getWorld(plugin.getConfig().getString("claims.world","world"));if(world==null){List<World> worlds=plugin.getServer().getWorlds();world=worlds.isEmpty()?null:worlds.get(0);}return world==null?0L:world.getFullTime();}
    /** Separate from last_confirmed (which just means "still exists somewhere legitimate", used for the
     *  60-real-day abandoned-owner check). last_used specifically means "actually engaged with" — see
     *  confirmInventory() and the interactive relics' handlers below for what sets it — and drives the
     *  7-Minecraft-day unused-reclaim rule. Defaults to "just used" for a relic with no recorded value yet
     *  (new feature rollout, or a freshly minted/claimed relic) so it starts with a full grace period
     *  instead of being immediately eligible for reclaim. */
    private void markUsed(String relicKey){db.state("relic_last_used:"+relicKey,Long.toString(mcTicksNow()));}
    private long lastUsedTicks(String relicKey){String raw=db.state("relic_last_used:"+relicKey);if(raw==null||raw.isBlank())return mcTicksNow();try{return Long.parseLong(raw);}catch(NumberFormatException e){return mcTicksNow();}}
    void itemLost(Item item){itemLostByKey(keyOf(item.getItemStack()));}
    void itemLostByKey(String relicKey){if(relicKey==null||!isActive(relicKey))return;long delay=config.getLong("lifecycle.lost-reentry-mc-days",7)*MC_DAY_TICKS;db.markRelicLost(relicKey,mcTicksNow()+delay);plugin.getServer().broadcast(Component.text("The "+displayName(relicKey)+" has been lost to history...",NamedTextColor.DARK_PURPLE));plugin.progress().relicLost(displayName(relicKey));}
    /** Reclaims a relic nobody has actually engaged with for lifecycle.unused-mc-days, even though the
     *  owner still technically has it sitting in their inventory. Pulls it out of wherever it's carried,
     *  then routes through the exact same LOST -> eligible -> resurfacing cycle as any other loss. */
    private void removeUnused(Database.RelicLifecycleRow row){
        String relicKey=row.key();
        for(Player player:plugin.getServer().getOnlinePlayers())for(ItemStack item:player.getInventory().getContents())if(relicKey.equals(keyOf(item)))item.setAmount(0);
        plugin.getLogger().info("[RelicLifecycle] "+relicKey+" reclaimed after going unused for "+config.getLong("lifecycle.unused-mc-days",7)+" Minecraft days (last owner: "+row.ownerName()+").");
        db.history("SERVER",null,"RELIC",displayName(relicKey)+" was reclaimed after going unused for "+config.getLong("lifecycle.unused-mc-days",7)+" Minecraft days (last owner: "+row.ownerName()+").");
        itemLostByKey(relicKey);
    }
    private void lifecycleTick(){
        for(Player player:plugin.getServer().getOnlinePlayers())confirmInventory(player);
        long now=System.currentTimeMillis(),nowTicks=mcTicksNow();
        long inactive=config.getLong("lifecycle.inactive-owner-days",60)*86400000L;
        long lostDelayTicks=config.getLong("lifecycle.lost-reentry-mc-days",7)*MC_DAY_TICKS;
        long unusedTicks=config.getLong("lifecycle.unused-mc-days",7)*MC_DAY_TICKS;
        for(Database.RelicLifecycleRow row:db.relicLifecycles()){
            if("LOST".equals(row.status())&&row.eligibleAt()>0&&nowTicks>=row.eligibleAt()){db.makeRelicEligible(row.key());plugin.getServer().broadcast(Component.text("Rumors speak of "+displayName(row.key())+" resurfacing somewhere in Ashfall...",NamedTextColor.LIGHT_PURPLE));db.history("SERVER",null,"RELIC",displayName(row.key())+" became eligible to resurface.");}
            else if("ACTIVE".equals(row.status())&&!"hidden".equals(row.owner())&&db.lastSeen(row.owner())>0&&now-db.lastSeen(row.owner())>=inactive&&now-row.lastConfirmed()>=inactive){db.markRelicLost(row.key(),nowTicks+lostDelayTicks);plugin.getServer().broadcast(Component.text("The "+displayName(row.key())+" has faded from living memory...",NamedTextColor.DARK_PURPLE));plugin.progress().relicLost(displayName(row.key()));}
            else if("ACTIVE".equals(row.status())&&!"hidden".equals(row.owner())&&nowTicks-lastUsedTicks(row.key())>=unusedTicks)removeUnused(row);
            else if("ACTIVE".equals(row.status())&&!"hidden".equals(row.owner()))autoVerify(row,now);
        }
    }
    /** Nobody should have to remember to run /relics trace for a relic to ever get correctly marked lost —
     *  this runs the same exhaustive search automatically, on every tick, for every active relic that
     *  hasn't been confirmed recently. If the owner is online, their live inventory/Ender Storage is checked
     *  directly and a genuine "not found anywhere" while online is conclusive enough to act on immediately.
     *  If they're offline we can't inspect their personal inventory at all (no server-side API for that
     *  without raw NBT parsing), so graves/claim/loaded-world are checked but a much longer unconfirmed
     *  stretch is required before concluding — avoiding a false "lost" just because someone's been away. */
    private void autoVerify(Database.RelicLifecycleRow row,long now){
        long checkAfter=config.getLong("lifecycle.auto-verify-after-hours",6)*3600000L;
        if(now-row.lastConfirmed()<checkAfter)return;
        String relicKey=row.key();
        Player online=plugin.getServer().getPlayer(row.owner());
        if(online!=null&&(hasRelic(online.getInventory().getContents(),relicKey)||hasRelic(plugin.enderChests().allContents(online),relicKey))){db.confirmRelic(relicKey,row.owner(),row.ownerName());return;}
        Database.FactionRow faction=db.factionOf(row.owner());
        if(faction!=null){FactionService.Claim claim=plugin.factions().claimOf(faction);if(claim!=null&&claimContains(claim,relicKey)){db.confirmRelic(relicKey,row.owner(),row.ownerName());return;}}
        for(Database.GraveRow grave:db.graves(row.owner()))if(hasRelic(db.graveItems(grave.id()).toArray(new ItemStack[0]),relicKey)){db.confirmRelic(relicKey,row.owner(),row.ownerName());return;}
        for(World world:plugin.getServer().getWorlds())for(Entity entity:world.getEntities())if(entity instanceof Item item&&relicKey.equals(keyOf(item.getItemStack()))){db.confirmRelic(relicKey,row.owner(),row.ownerName());return;}
        if(online==null){
            long offlineThreshold=config.getLong("lifecycle.auto-verify-offline-hours",96)*3600000L;
            if(now-row.lastConfirmed()<offlineThreshold)return;
        }
        itemLostByKey(relicKey);
        plugin.getLogger().info("[RelicAutoVerify] "+relicKey+" auto-detected as lost — owner "+row.ownerName()+" ("+(online!=null?"online":"offline")+"), not found in inventory, Ender Storage, faction claim, graves, or loaded world entities.");
        db.history("SERVER",null,"RELIC",displayName(relicKey)+" was automatically detected as lost (last owner: "+row.ownerName()+").");
    }
    /** Admin removal of the physical item(s) — not a permanent retirement. deactivateRelic() (still present
     *  in Database.java but deliberately never called from anywhere) sets a terminal 'RETIRED' status
     *  lifecycleTick() never revisits, which silently deleted the relic from the chronicle forever.
     *  markRelicLost() (the same call organic loss uses) puts it through the normal LOST -> eligible ->
     *  resurfacing cycle instead. Must use the same Minecraft-tick eligible_at basis lifecycleTick() checks
     *  against (mcTicksNow(), not System.currentTimeMillis()) — using wall-clock millis here produced a
     *  number so far beyond any real tick count that the LOST -> eligible transition could never trigger,
     *  leaving anything removed this way stuck in LOST forever regardless of the status name being correct. */
    boolean remove(String relicKey){
        if(db.relic(relicKey)==null)return false;
        db.markRelicLost(relicKey,mcTicksNow()+config.getLong("lifecycle.lost-reentry-mc-days",7)*MC_DAY_TICKS);
        for(Player player:plugin.getServer().getOnlinePlayers())for(ItemStack item:player.getInventory().getContents())if(relicKey.equals(keyOf(item)))item.setAmount(0);
        return true;
    }
    long lostReentryDays(){return config.getLong("lifecycle.lost-reentry-mc-days",7);}
    void list(Player p){List<Database.RelicLifecycleRow> rows=db.relicLifecycles();if(rows.isEmpty()){CoreUtil.msg(p,"No relics have entered the chronicle yet.");return;}CoreUtil.msg(p,"Relic chronicle:");for(Database.RelicLifecycleRow row:rows)CoreUtil.msg(p,"• "+displayName(row.key())+" — "+plugin.nicknames().displayName(row.ownerName())+" ["+CoreUtil.pretty(row.status())+"]");}
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
        CoreUtil.msg(player,"No trace of "+displayName(relicKey)+" in your inventory, Ender Storage, faction territory, graves, or loaded world entities. It has been marked lost and will resurface in "+config.getLong("lifecycle.lost-reentry-days",14)+" days.");
        plugin.getLogger().info("[RelicTrace] "+player.getName()+" traced "+relicKey+": no remaining copy found anywhere checkable; transitioned to LOST.");
        db.history("SERVER",null,"RELIC",plugin.nicknames().displayName(player)+" traced "+displayName(relicKey)+" and found no remaining copy; transitioned to lost.");
    }
    /** Found-somewhere outcomes still refresh last_confirmed — otherwise a relic sitting untouched in a
     *  world container (which the periodic inventory sweep can never see) would keep drifting toward the
     *  60-day inactive-owner fallback even though a trace just proved it's still genuinely accounted for. */
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

    @EventHandler public void interact(PlayerInteractEvent event){
        if(!event.getAction().isRightClick()||event.getHand()!=EquipmentSlot.HAND)return;
        Player player=event.getPlayer();ItemStack item=player.getInventory().getItemInMainHand();String relicKey=keyOf(item);if(relicKey==null||!isActive(relicKey))return;
        switch(relicKey){
            case"ashen_reprisal"->ashenReprisal(player,event);
            case"colossus_core"->colossusWard(player,event);
            case"warlords_ember"->warlordsDash(player,event);
            default->{}
        }
    }
    /** Cooldowns are bound to the relic itself (persisted in the state table), not the player holding
     *  it — so dropping, relogging, dying, trading, or a server restart can never reset or bypass one;
     *  the single unique artifact simply isn't ready again until real time has actually passed. */
    private boolean onCooldown(Player player,String relicKey,long cooldownMs){
        long now=System.currentTimeMillis(),ready=parseLong(db.state("relic_cooldown:"+relicKey));
        if(now<ready){CoreUtil.error(player,displayName(relicKey)+" is not ready yet ("+((ready-now)/1000+1)+"s).");return true;}
        db.state("relic_cooldown:"+relicKey,Long.toString(now+cooldownMs));return false;
    }
    private long parseLong(String value){try{return value==null||value.isBlank()?0:Long.parseLong(value);}catch(NumberFormatException ignored){return 0;}}
    private void ashenReprisal(Player player,PlayerInteractEvent event){
        event.setCancelled(true);
        if(onCooldown(player,"ashen_reprisal",config.getLong("buffs.ashen-reprisal.cooldown-seconds",25)*1000L))return;
        markUsed("ashen_reprisal");
        double damage=config.getDouble("buffs.ashen-reprisal.damage",6),range=config.getDouble("buffs.ashen-reprisal.range",4.5);
        Location eye=player.getEyeLocation();Vector direction=eye.getDirection().normalize();int hits=0;
        for(Entity entity:player.getNearbyEntities(range,range,range)){
            if(entity.equals(player)||!(entity instanceof LivingEntity target)||!(entity instanceof org.bukkit.entity.Enemy||entity instanceof Player))continue;
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
        markUsed("colossus_core");
        int duration=(int)Math.round(config.getDouble("buffs.colossus-core.brace-seconds",3)*20);
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,duration,3,false,true,true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,duration,1,false,true,true));
        double radius=config.getDouble("buffs.colossus-core.radius",5),push=config.getDouble("buffs.colossus-core.knockback",1.4);
        Location center=player.getLocation();
        for(Entity entity:player.getNearbyEntities(radius,radius,radius)){
            if(entity.equals(player)||!(entity instanceof org.bukkit.entity.Enemy||entity instanceof Player))continue;
            Vector away=entity.getLocation().toVector().subtract(center.toVector());if(away.lengthSquared()<0.01)away=new Vector(1,0,0);
            away.normalize().multiply(push);away.setY(Math.max(away.getY(),0.35));
            entity.setVelocity(entity.getVelocity().add(away));
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
        markUsed("warlords_ember");
        Vector direction=player.getLocation().getDirection().normalize();double power=config.getDouble("buffs.warlords-ember.power",2.1);
        Location destination=player.getLocation().add(direction.clone().multiply(power*2));
        if(plugin.spawnClaims().contains(destination)&&!plugin.isAdmin(player)){CoreUtil.error(player,"You cannot dash into protected spawn territory.");return;}
        player.setVelocity(direction.clone().multiply(power).setY(Math.max(0.28,direction.getY()*.5+0.28)));
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,60,0,false,true,true));
        double radius=config.getDouble("buffs.warlords-ember.radius",2.2);
        Location center=player.getLocation().add(direction.clone().multiply(2));
        for(Entity entity:player.getWorld().getNearbyEntities(center,radius,radius,radius)){
            if(entity.equals(player)||!(entity instanceof org.bukkit.entity.Enemy||entity instanceof Player)||!(entity instanceof LivingEntity target))continue;
            target.setFireTicks(Math.max(target.getFireTicks(),40));target.setVelocity(target.getVelocity().add(direction.clone().multiply(.6).setY(.25)));
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
