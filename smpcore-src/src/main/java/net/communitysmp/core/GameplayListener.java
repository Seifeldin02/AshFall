package net.communitysmp.core;

import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.LoginEvent;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class GameplayListener implements Listener {
    private static final class ChatState {final Deque<Long> times=new ArrayDeque<>();String last="";long lastAt,cooldownUntil;int offenses;}
    private final SMPCore plugin;private final Database db;private final FactionService factions;private final TeleportService teleports;private final ShopService shop;private final AuctionService auctions;private final BossEventService bosses;private final BountyService bounties;private final RelicService relics;private final ProgressService progress;private final SpawnerService spawners;private final SpawnClaimService spawnClaims;private final NetWorthService netWorth;private final MerchantService merchants;private final VillagerCapsuleService capsules;private final GraveService graves;private final ObsidianDurabilityService obsidian;
    private final Set<UUID> firstJoin=Collections.synchronizedSet(new HashSet<>());private final SecureRandom secureRandom=new SecureRandom();
    private final Map<UUID,String> lastCommand=new ConcurrentHashMap<>();
    private final Map<UUID,Long> lastCommandAt=new ConcurrentHashMap<>();
    private final Map<UUID,ChatState> chatStates=new ConcurrentHashMap<>();private final Map<UUID,Long> lastCommands=new ConcurrentHashMap<>();private final Map<UUID,Deque<Long>> commandViolations=new ConcurrentHashMap<>();
    private final Set<UUID> exemptDisconnects=ConcurrentHashMap.newKeySet();
    private final Map<UUID,UUID> combatLogKillers=new ConcurrentHashMap<>();
    GameplayListener(SMPCore plugin,FactionService factions,TeleportService teleports,ShopService shop,AuctionService auctions,BossEventService bosses,BountyService bounties,RelicService relics,ProgressService progress,SpawnerService spawners,SpawnClaimService spawnClaims,NetWorthService netWorth,MerchantService merchants,VillagerCapsuleService capsules,GraveService graves,ObsidianDurabilityService obsidian){this.plugin=plugin;this.db=plugin.db();this.factions=factions;this.teleports=teleports;this.shop=shop;this.auctions=auctions;this.bosses=bosses;this.bounties=bounties;this.relics=relics;this.progress=progress;this.spawners=spawners;this.spawnClaims=spawnClaims;this.netWorth=netWorth;this.merchants=merchants;this.capsules=capsules;this.graves=graves;this.obsidian=obsidian;}

    /** Was gamemode==SPECTATOR only — missed a vanished admin who isn't in spectator mode (/smp vanish uses
     *  hidePlayer/showPlayer, not a gamemode change). isHiddenFromPublic() is the one canonical "should this
     *  player be treated as if they don't exist" check (isVanished() OR spectator), already used everywhere
     *  else this kind of leak matters (see its own doc comment in AdminToolsService). Only the hover-sample
     *  list is touched here — the numeric online count is untouched, preserving whatever it already was. */
    @EventHandler public void serverList(ServerListPingEvent e){Iterator<Player> shown=e.iterator();while(shown.hasNext())if(plugin.adminTools().isHiddenFromPublic(shown.next()))shown.remove();if(plugin.getConfig().getBoolean("maintenance.enabled",false)){e.motd(net.kyori.adventure.text.Component.text("Server under maintenance",net.kyori.adventure.text.format.NamedTextColor.GOLD));return;}String name=plugin.getConfig().getString("server-name","Ashfall Concord");String line=bosses.serverListEventLine();e.motd(net.kyori.adventure.text.Component.text(name,net.kyori.adventure.text.format.NamedTextColor.GOLD).append(net.kyori.adventure.text.Component.newline()).append(net.kyori.adventure.text.Component.text(line,net.kyori.adventure.text.format.NamedTextColor.GRAY)));}
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){Player p=e.getPlayer();syncSpectatorVisibility(p);if(!p.hasPlayedBefore())firstJoin.add(p.getUniqueId());db.ensurePlayer(CoreUtil.id(p),p.getName(),plugin.getConfig().getDouble("starting-balance",250));db.setIpHash(CoreUtil.id(p),CoreUtil.ipHash(p));if(plugin.getConfig().getBoolean("authentication.auto-authenticate-floodgate",true)&&isFloodgate(p)){plugin.getServer().getScheduler().runTaskLater(plugin,()->{try{AuthMeApi api=AuthMeApi.getInstance();if(!api.isRegistered(p.getName())){plugin.registration().openBedrock(p);return;}api.forceLogin(p);onAuthenticated(p);}catch(Exception ex){plugin.getLogger().severe("Could not authenticate Floodgate player "+p.getName()+": "+ex.getMessage());}},5L);}else if(plugin.getServer().getPluginManager().getPlugin("AuthMe")==null)plugin.getServer().getScheduler().runTask(plugin,()->onAuthenticated(p));}
    @EventHandler(priority=EventPriority.MONITOR) public void login(LoginEvent e){plugin.getServer().getScheduler().runTask(plugin,()->onAuthenticated(e.getPlayer()));}
    private void onAuthenticated(Player p){if(!p.isOnline())return;Database.PlayerRow row=db.player(CoreUtil.id(p));boolean newPlayer=firstJoin.remove(p.getUniqueId());if(!row.firstSpawn()){if(newPlayer&&plugin.getConfig().getBoolean("random-spawn.enabled",true))randomSpawn(p,0);else db.setPlayerFlag(CoreUtil.id(p),"first_spawn",true);}progress.join(p);plugin.settings().applyConfirmationDefaults(p);plugin.shards().join(p);spawners.migrateInventory(p);bosses.sanitizePlayerEffects(p);relics.confirmInventory(p);factions.join(p);if(!row.guide()){plugin.giveGuide(p);db.setPlayerFlag(CoreUtil.id(p),"guide",true);p.sendMessage("§6Welcome to "+plugin.getConfig().getString("server-name","Ashfall Concord"));p.sendMessage("§7Create or join a faction. Leaders claim land with §f/f claim§7.");}p.sendMessage("§8/settings • /progress • /feedback • /rules");/** Delayed so the summary lands after the welcome block rather than being scrolled away by it. */
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{if(p.isOnline()){auctions.loginSummary(p);plugin.orders().loginSummary(p);}},40L);
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{plugin.ui().update(p);for(Player online:plugin.getServer().getOnlinePlayers())online.updateCommands();},10L);}
    /** Reuses /rtp's own safety search (world border, spawn-claim/faction-claim/active-event avoidance —
     *  see TeleportService.findSafeSpawnLocation()) instead of the old bare CoreUtil.findSafe() call, so a
     *  brand new player's very first spawn gets the same real safety guarantees a manual /rtp already has.
     *  The resulting location is persisted (players.persisted_spawn_*) and reused as this player's death
     *  respawn point until they set a real bed/anchor — see respawn() below. Existing players never go
     *  through this path again (first_spawn is already true for them), so nothing changes for them. */
    private void randomSpawn(Player p,int attempt){
        if(!p.isOnline())return;
        World world=plugin.getServer().getWorld(plugin.getConfig().getString("claims.world","world"));
        teleports.findSafeSpawnLocation(world,attempt,
            safe->p.teleportAsync(safe).thenAccept(ok->{
                if(ok){db.setPlayerFlag(CoreUtil.id(p),"first_spawn",true);teleports.persistNewPlayerSpawn(CoreUtil.id(p),safe);CoreUtil.msg(p,"Your adventure begins in the wilderness. Central spawn is always /spawn.");}
                else randomSpawn(p,attempt+1);
            }),
            ()->{db.setPlayerFlag(CoreUtil.id(p),"first_spawn",true);CoreUtil.error(p,"A safe wilderness start could not be found; use /spawn if needed.");}
        );
    }
    /** A death respawn only ever falls back to something OTHER than the player's own bed/anchor when
     *  neither is currently set/valid (isBedSpawn()/isAnchorSpawn() both false) — that's precisely the
     *  moment vanilla would otherwise send them to world spawn. If this player has a persisted first-spawn
     *  location (only ever set for a genuinely new player, see randomSpawn() above), send them back there
     *  instead, every time, until they set a real bed or anchor of their own. Never rerandomized. */
    @EventHandler public void respawn(PlayerRespawnEvent event){
        if(event.isBedSpawn()||event.isAnchorSpawn())return;
        Location persisted=teleports.persistedSpawn(CoreUtil.id(event.getPlayer()));
        if(persisted!=null)event.setRespawnLocation(persisted);
    }
    private boolean isFloodgate(Player p){try{Class<?> apiClass=Class.forName("org.geysermc.floodgate.api.FloodgateApi");Object api=apiClass.getMethod("getInstance").invoke(null);Method method=apiClass.getMethod("isFloodgatePlayer",UUID.class);return (boolean)method.invoke(api,p.getUniqueId());}catch(Exception e){return false;}}
    private String randomPassword(){byte[] data=new byte[32];secureRandom.nextBytes(data);return Base64.getUrlEncoder().withoutPadding().encodeToString(data);}

    private boolean outsider(Player p,Block block){if(plugin.privileged(p))return false;if(spawnClaims.contains(block.getLocation())){if(plugin.isAdmin(p))return false;CoreUtil.error(p,"This block is protected by spawn.");return true;}FactionService.Claim claim=factions.claimAt(block.getLocation());if(claim==null)return false;if(factions.isMember(p,claim.faction()))return false;CoreUtil.error(p,"This block is protected by "+claim.faction().name()+".");return true;}
    private boolean spawnOutsider(Player p,Block block){if(plugin.privileged(p)||!spawnClaims.contains(block.getLocation())||plugin.isAdmin(p))return false;CoreUtil.error(p,"This block is protected by spawn.");return true;}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void blockDamage(BlockDamageEvent e){spawners.damage(e);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void blockBreak(BlockBreakEvent e){Block block=e.getBlock();if(outsider(e.getPlayer(),block)){e.setCancelled(true);return;}if(spawners.breaking(e))return;spawners.blockRemoved(block);netWorth.removed(block);bosses.onResourceBreak(e.getPlayer(),block);progress.blockMined(e.getPlayer(),block.getType());}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void blockPlace(BlockPlaceEvent e){if(outsider(e.getPlayer(),e.getBlock())){e.setCancelled(true);return;}spawners.tntPlaced(e);spawners.placed(e);if(!e.isCancelled())plugin.getServer().getScheduler().runTask(plugin,()->netWorth.blockChanged(e.getBlockPlaced()));}
    /** The Task Master is a real WanderingTrader so it walks about naturally; its vanilla trade screen is
     *  suppressed and replaced with the contract board. */
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void taskMasterClick(org.bukkit.event.player.PlayerInteractEntityEvent e){
        if(!plugin.taskMaster().isTaskMaster(e.getRightClicked()))return;
        e.setCancelled(true);
        plugin.taskMaster().interact(e.getPlayer());
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void interact(PlayerInteractEvent e){merchants.use(e);if(e.isCancelled())return;if(e.getAction()==Action.LEFT_CLICK_BLOCK&&e.getClickedBlock()!=null&&spawnClaims.selectClick(e.getPlayer(),e.getClickedBlock())){e.setCancelled(true);return;}Block block=e.getClickedBlock();if(block==null)return;if(!plugin.privileged(e.getPlayer())&&block.getType()==Material.ENDER_CHEST&&teleports.combatRemaining(e.getPlayer())>0){e.setCancelled(true);combatEnderMessage(e.getPlayer());return;}if(spawnOutsider(e.getPlayer(),block)){e.setCancelled(true);return;}FactionService.Claim claim=factions.claimAt(block.getLocation());if(!plugin.privileged(e.getPlayer())&&claim!=null&&!factions.isMember(e.getPlayer(),claim.faction())){boolean storage=block.getState() instanceof Container||block.getType()==Material.ENDER_CHEST||block.getType()==Material.DECORATED_POT,door=block.getBlockData() instanceof Openable||controlInteraction(block);if(storage&&!factions.storageAccess(e.getPlayer(),claim.faction())||door&&!factions.doorAccess(e.getPlayer(),claim.faction())||block.getType()==Material.SPAWNER){e.setCancelled(true);CoreUtil.error(e.getPlayer(),storage?"This storage is protected by "+claim.faction().name()+".":"Your faction relation does not allow that interaction.");return;}}if(block.getType()==Material.ENDER_CHEST&&e.getAction().isRightClick()&&e.getHand()==org.bukkit.inventory.EquipmentSlot.HAND){e.setCancelled(true);plugin.enderChests().openFromBlock(e.getPlayer());return;}if(spawners.stack(e))return;if(e.getAction().isRightClick()&&capsules.place(e.getPlayer(),e.getItem(),e.getHand(),block.getRelative(e.getBlockFace()).getLocation().add(.5,0,.5)))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void bucketFill(PlayerBucketFillEvent e){if(outsider(e.getPlayer(),e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void bucketEmpty(PlayerBucketEmptyEvent e){if(outsider(e.getPlayer(),e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void armorStand(PlayerArmorStandManipulateEvent e){if(outsider(e.getPlayer(),e.getRightClicked().getLocation().getBlock()))e.setCancelled(true);}
    /** capsules.capture() runs before the generic outsider() claim gate deliberately — normal villagers are
     *  meant to be stealable even from a protected faction claim, capture() enforces its own (narrower)
     *  spawn-only protection, and it's a safe no-op false for every non-villager-capsule interaction, so every
     *  other entity interaction still falls through to outsider() exactly as before. */
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void entityInteract(PlayerInteractEntityEvent e){if(merchants.interact(e.getPlayer(),e.getRightClicked())){e.setCancelled(true);return;}if(spawnClaims.contains(e.getRightClicked().getLocation())&&spawnClaims.allowed(e.getRightClicked()))return;if(capsules.capture(e.getPlayer(),e.getRightClicked(),e.getHand())){e.setCancelled(true);return;}if(outsider(e.getPlayer(),e.getRightClicked().getLocation().getBlock())){e.setCancelled(true);return;}if(e.getRightClicked() instanceof ZombieVillager zombie&&e.getPlayer().getInventory().getItem(e.getHand()).getType()==Material.GOLDEN_APPLE&&zombie.hasPotionEffect(PotionEffectType.WEAKNESS))bosses.markCurer(e.getPlayer(),zombie);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void explode(EntityExplodeEvent e){boolean protectFactions=plugin.getConfig().getBoolean("claims.prevent-faction-explosions",false);e.blockList().removeIf(block->spawnClaims.contains(block.getLocation())||(protectFactions&&factions.claimAt(block.getLocation())!=null));if(!protectFactions)obsidian.registerExplosion(e.getLocation(),e.getEntity());spawners.explosion(e);for(Block block:e.blockList())netWorth.removed(block);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void tntPrime(TNTPrimeEvent e){spawners.tntPrime(e);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void spawnerSpawn(SpawnerSpawnEvent e){spawners.spawned(e);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void blockExplode(BlockExplodeEvent e){boolean protectFactions=plugin.getConfig().getBoolean("claims.prevent-faction-explosions",false);e.blockList().removeIf(block->spawnClaims.contains(block.getLocation())||(protectFactions&&factions.claimAt(block.getLocation())!=null));if(!protectFactions)obsidian.registerExplosion(e.getBlock().getLocation(),null);for(Block block:e.blockList())netWorth.removed(block);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void pistonExtend(BlockPistonExtendEvent e){for(Block block:e.getBlocks())if(boundaryChanged(block,block.getRelative(e.getDirection()))){e.setCancelled(true);return;}}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void pistonRetract(BlockPistonRetractEvent e){for(Block block:e.getBlocks())if(boundaryChanged(block,block.getRelative(e.getDirection()))){e.setCancelled(true);return;}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void pistonMoved(BlockPistonExtendEvent e){List<Location> from=e.getBlocks().stream().map(Block::getLocation).toList();List<Location> to=e.getBlocks().stream().map(block->block.getRelative(e.getDirection()).getLocation()).toList();plugin.getServer().getScheduler().runTask(plugin,()->{from.forEach(location->netWorth.removed(location.getBlock()));to.forEach(location->netWorth.blockChanged(location.getBlock()));});}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void pistonMoved(BlockPistonRetractEvent e){List<Location> from=e.getBlocks().stream().map(Block::getLocation).toList();List<Location> to=e.getBlocks().stream().map(block->block.getRelative(e.getDirection().getOppositeFace()).getLocation()).toList();plugin.getServer().getScheduler().runTask(plugin,()->{from.forEach(location->netWorth.removed(location.getBlock()));to.forEach(location->netWorth.blockChanged(location.getBlock()));});}
    private boolean boundaryChanged(Block a,Block b){boolean spawnA=spawnClaims.contains(a.getLocation()),spawnB=spawnClaims.contains(b.getLocation());if(spawnA!=spawnB)return true;FactionService.Claim first=factions.claimAt(a.getLocation()),second=factions.claimAt(b.getLocation());return(first==null)!=(second==null)||(first!=null&&second!=null&&first.faction().id()!=second.faction().id());}
    private boolean protectedInteraction(Block block){return block.getState() instanceof Container||block.getBlockData() instanceof Openable||block.getType()==Material.ENDER_CHEST||block.getType()==Material.DECORATED_POT;}
    private boolean controlInteraction(Block block){String name=block.getType().name();return name.endsWith("_BUTTON")||name.endsWith("_GATE")||name.endsWith("_TRAPDOOR")||name.equals("LEVER");}
    private void combatEnderMessage(Player player){long remaining=teleports.combatRemaining(player);CoreUtil.error(player,"You cannot use your Ender Chest while in combat. "+remaining+"s remaining.");}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void inventoryOpen(InventoryOpenEvent e){if(!(e.getPlayer() instanceof Player player)||plugin.privileged(player))return;if(e.getInventory().getType()==InventoryType.ENDER_CHEST&&teleports.combatRemaining(player)>0){e.setCancelled(true);combatEnderMessage(player);return;}Location location;try{location=e.getInventory().getLocation();}catch(Exception ignored){return;}if(location==null)return;if(spawnClaims.contains(location)&&!plugin.isAdmin(player)){e.setCancelled(true);CoreUtil.error(player,"This storage is protected by spawn.");return;}FactionService.Claim claim=factions.claimAt(location);if(claim!=null&&!plugin.isAdmin(player)&&!factions.storageAccess(player,claim.faction())){e.setCancelled(true);CoreUtil.error(player,"This is protected storage in "+claim.faction().name()+" territory.");}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void inventoryMove(InventoryMoveItemEvent e){Location source=e.getSource().getLocation(),destination=e.getDestination().getLocation();FactionService.Claim from=source==null?null:factions.claimAt(source),to=destination==null?null:factions.claimAt(destination);if((from==null)!=(to==null)||(from!=null&&to!=null&&from.faction().id()!=to.faction().id()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void inventoryMoveUpdate(InventoryMoveItemEvent e){Inventory source=e.getSource(),destination=e.getDestination();plugin.getServer().getScheduler().runTask(plugin,()->{netWorth.containerChanged(source);if(destination!=source)netWorth.containerChanged(destination);});}
    @EventHandler(priority=EventPriority.MONITOR) public void inventoryClose(InventoryCloseEvent e){shop.close(e);netWorth.containerChanged(e.getInventory());}
    @EventHandler(priority=EventPriority.HIGHEST) public void inventoryDrag(InventoryDragEvent e){shop.drag(e);}

    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void combat(EntityDamageByEntityEvent e){if(merchants.isMerchant(e.getEntity())){e.setCancelled(true);return;}
        /** World-boss basic melee is rate-limited; abilities are untouched. */
        if(e.getDamager() instanceof org.bukkit.entity.LivingEntity swinger&&bosses.meleeOnCooldown(swinger,e.getCause())){e.setCancelled(true);return;}if(e.getEntity() instanceof Player victim){Player attacker=playerDamager(e.getDamager());boolean selfPearl=isSelfEnderPearl(e,victim),privileged=attacker!=null&&plugin.privileged(attacker);if(attacker!=null&&plugin.arena()!=null&&plugin.arena().areDuelOpponents(attacker,victim))return;if(attacker!=null&&!privileged&&!selfPearl&&(spawnClaims.contains(victim.getLocation())||spawnClaims.contains(attacker.getLocation()))){e.setCancelled(true);CoreUtil.error(attacker,"PvP is disabled at spawn.");return;}if(attacker!=null&&!privileged&&!selfPearl&&factions.friendly(attacker,victim)&&!plugin.getConfig().getBoolean("factions.friendly-fire",false)){e.setCancelled(true);CoreUtil.error(attacker,"Friendly PvP is disabled.");return;}}else if(e.getEntity() instanceof Hanging||e.getEntity() instanceof ArmorStand){Player attacker=playerDamager(e.getDamager());if(attacker!=null&&outsider(attacker,e.getEntity().getLocation().getBlock())){e.setCancelled(true);return;}}else if(spawnClaims.contains(e.getEntity().getLocation())&&spawnClaims.allowed(e.getEntity())){Player attacker=playerDamager(e.getDamager());if(attacker!=null&&!plugin.isAdmin(attacker)){e.setCancelled(true);CoreUtil.error(attacker,"Command-spawned entities are protected at spawn.");return;}}bosses.onDamage(e);}
    /** ignoreCancelled already keeps a genuinely cancelled hit (friendly fire, spawn PvP — see combat() above,
     *  which runs at HIGH, before this MONITOR handler) from ever reaching here. But a hit can also land
     *  fully absorbed — a shield block, or armor/enchantments/Resistance reducing it to nothing — without the
     *  event being cancelled at all; getFinalDamage() is 0 either way. Neither case is a real fight, so
     *  neither should start the PvP teleport lock or the Ender Chest combat lock. */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void pvpTag(EntityDamageByEntityEvent e){if(e.getFinalDamage()<=0)return;if(e.getEntity() instanceof Player victim&&!isSelfEnderPearl(e,victim)){Player attacker=playerDamager(e.getDamager());if(attacker!=null&&!plugin.privileged(attacker)&&!plugin.privileged(victim)&&!(plugin.arena()!=null&&plugin.arena().areDuelOpponents(attacker,victim))){teleports.onPvpHit(attacker,victim);plugin.enderChests().combatStarted(attacker);plugin.enderChests().combatStarted(victim);}}}
    /** Teleport warmup is cancelled here, at MONITOR, rather than in damaged() at HIGH.
     *
     *  combat() cancels friendly fire at HIGH, and damaged() also ran at HIGH. EntityDamageByEntityEvent
     *  shares EntityDamageEvent's handler list, so both were in the same priority bucket and their relative
     *  order was just registration order -- effectively arbitrary. Whether an ally's cancelled hit killed
     *  your teleport therefore depended on which method the JVM happened to register first, which is exactly
     *  why this worked and then silently stopped. At MONITOR every cancellation has already been applied, so
     *  ignoreCancelled genuinely means "this hit landed".
     *
     *  The finalDamage guard matches pvpTag() directly above: a shield block or armour absorbing a hit to
     *  nothing is not damage either, and should not interrupt a teleport. */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void teleportDamage(EntityDamageEvent e){
        if(e.getFinalDamage()<=0)return;
        if(e.getEntity() instanceof Player p)teleports.onDamage(p);
    }
    private Player playerDamager(Entity entity){if(entity instanceof Player p)return p;if(entity instanceof Projectile projectile&&projectile.getShooter() instanceof Player p)return p;return null;}
    private boolean isSelfEnderPearl(EntityDamageByEntityEvent event,Player victim){return event.getDamager() instanceof EnderPearl pearl&&pearl.getShooter() instanceof Player thrower&&thrower.getUniqueId().equals(victim.getUniqueId());}
    /** Blanket damage protection for players standing inside spawn — covers every DamageCause (fall, fire,
     *  drowning, explosions, environmental harm set up by another player) in one place instead of trying to
     *  special-case each one; the narrower spawn-PvP check in combat() above is left in place too since it
     *  runs on a different event type (EntityDamageByEntityEvent) and there's no harm in the redundancy. */
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void damaged(EntityDamageEvent e){bosses.onAnyDamage(e);if(e.isCancelled())return;if(e.getEntity() instanceof Player p){if(spawnClaims.contains(p.getLocation())&&!plugin.privileged(p)){e.setCancelled(true);return;}if(hostileDamage(e))plugin.settings().hostileDamage(p);if((e.getCause()==EntityDamageEvent.DamageCause.FIRE||e.getCause()==EntityDamageEvent.DamageCause.FIRE_TICK||e.getCause()==EntityDamageEvent.DamageCause.LAVA)&&relics.activeItem(p.getInventory().getHelmet(),"crown_of_ash"))e.setDamage(e.getDamage()*.25);}else if(e.getEntity() instanceof Item item&&(e.getCause()==EntityDamageEvent.DamageCause.FIRE||e.getCause()==EntityDamageEvent.DamageCause.LAVA||e.getCause()==EntityDamageEvent.DamageCause.VOID||e.getCause()==EntityDamageEvent.DamageCause.BLOCK_EXPLOSION||e.getCause()==EntityDamageEvent.DamageCause.ENTITY_EXPLOSION))relics.itemLost(item);}
    private boolean hostileDamage(EntityDamageEvent event){if(!(event instanceof EntityDamageByEntityEvent byEntity))return false;Entity source=byEntity.getDamager();if(source instanceof Projectile projectile&&projectile.getShooter() instanceof Entity shooter)source=shooter;return source instanceof Enemy;}
    @EventHandler(ignoreCancelled=true) public void worldBossTarget(EntityTargetLivingEntityEvent e){bosses.onWorldBossTarget(e);}
    @EventHandler(ignoreCancelled=true) public void eliteBlockChange(EntityChangeBlockEvent e){bosses.onEliteBlockChange(e);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void stackedMobDeath(EntityDeathEvent e){spawners.stackedDeath(e);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void move(PlayerMoveEvent e){teleports.onMove(e.getPlayer(),e.getTo());if(e.hasChangedPosition()||e.hasChangedOrientation())plugin.shards().activity(e.getPlayer());}
    @EventHandler public void changedWorld(PlayerChangedWorldEvent e){progress.worldChanged(e.getPlayer(),e.getPlayer().getWorld().getEnvironment());teleports.leaveRtpQueueOnWorldChange(e.getPlayer());}
    @EventHandler public void held(PlayerItemHeldEvent e){plugin.getServer().getScheduler().runTask(plugin,()->scanImportant(e.getPlayer()));}
    @EventHandler(priority=EventPriority.LOWEST) public void kick(PlayerKickEvent event){String cause=event.getCause().name();if(cause.contains("KICK_COMMAND")||cause.contains("BANNED")||cause.contains("WHITELIST")||cause.contains("DUPLICATE"))exemptDisconnects.add(event.getPlayer().getUniqueId());}
    @EventHandler public void quit(PlayerQuitEvent e){Player player=e.getPlayer();db.updateLastLocation(CoreUtil.id(player),player.getLocation());if(!Bukkit.isStopping()&&!player.isDead()&&!exemptDisconnects.remove(player.getUniqueId())&&teleports.combatRemaining(player)>0){Player opponent=teleports.latestLivingOpponent(player);if(opponent!=null){combatLogKillers.put(player.getUniqueId(),opponent.getUniqueId());player.setKiller(opponent);player.setHealth(0);}}teleports.quit(player);plugin.messaging().quit(player);plugin.shards().quit(player);factions.clearChatMode(player);firstJoin.remove(player.getUniqueId());relics.confirmInventory(player);progress.quit(player);plugin.ui().remove(player);db.forgetPreferences(CoreUtil.id(player));db.forgetPreferences("uuid:"+player.getUniqueId());bosses.playerQuit(player.getUniqueId());plugin.registration().quit(player);plugin.getServer().getScheduler().runTaskLater(plugin,()->{for(Player online:plugin.getServer().getOnlinePlayers())online.updateCommands();},1L);}
    @EventHandler public void death(PlayerDeathEvent e){Player victim=e.getEntity(),killer=victim.getKiller();UUID logged=combatLogKillers.remove(victim.getUniqueId());if(logged!=null){Player opponent=plugin.getServer().getPlayer(logged);if(opponent!=null)killer=opponent;e.deathMessage(net.kyori.adventure.text.Component.text(plugin.nicknames().displayName(victim)+" tried to escape the fight"+(killer==null?"":(" with "+plugin.nicknames().displayName(killer)))+" and paid the price.",net.kyori.adventure.text.format.NamedTextColor.RED));}teleports.onDeath(victim);db.incrementStat(CoreUtil.id(victim),"deaths");double percent=plugin.getConfig().getDouble("death.balance-loss-percent",10),cap=plugin.getConfig().getDouble("death.balance-loss-cap",10000),lost=db.takeFraction(CoreUtil.id(victim),percent/100.0,cap);if(lost>0)CoreUtil.error(victim,"Death cost you "+CoreUtil.money(lost)+".");e.getDrops().removeIf(item->item.getType()==Material.PLAYER_HEAD);if(killer!=null&&plugin.getConfig().getBoolean("pvp.drop-player-head",true)){ItemStack head=new ItemStack(Material.PLAYER_HEAD);SkullMeta meta=(SkullMeta)head.getItemMeta();meta.setOwningPlayer(victim);meta.displayName(net.kyori.adventure.text.Component.text(plugin.nicknames().displayName(victim)+"'s Head",net.kyori.adventure.text.format.NamedTextColor.RED));meta.lore(List.of(net.kyori.adventure.text.Component.text("Claimed by "+plugin.nicknames().displayName(killer),net.kyori.adventure.text.format.NamedTextColor.GRAY)));head.setItemMeta(meta);e.getDrops().add(head);}boolean awarded=bounties.onPlayerKill(victim,killer,lost);if(lost>0&&!awarded){plugin.bank().creditSink(lost,CoreUtil.id(victim),"DEATH_PENALTY");db.recordEconomy(CoreUtil.id(victim),"DEATH_SINK",-lost,"NON_PVP");}graves.create(victim,e.getDrops(),victim.getLocation());}
    /** Trial Chamber spawner mobs are tagged here (SpawnReason.TRIAL_SPAWNER is unambiguous — it's set only
     *  for mobs the vanilla trial spawner mechanic itself spawns, never for an ordinary hostile that wanders
     *  into the chamber afterward) so SettingsService's Hostile-Mobs-OFF removal sweep can exempt them by
     *  checking the tag later, regardless of how much time has passed since spawn. Deliberately NOT added to
     *  the NATURAL/SPAWNER spawn-prevention branch below — trial spawner activation is a player-triggered
     *  encounter mechanic, not ambient mob pressure, so it was never blocked from spawning in the first place;
     *  this only had to fix the separate removal sweep. */
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void creatureSpawn(CreatureSpawnEvent e){if(graves.isMarker(e.getEntity()))return;if(e.getSpawnReason()==CreatureSpawnEvent.SpawnReason.COMMAND&&spawnClaims.contains(e.getLocation())){spawnClaims.allow(e.getEntity());e.getEntity().setAI(false);e.getEntity().setPersistent(true);e.getEntity().setCollidable(false);e.getEntity().setInvulnerable(true);}if(spawnClaims.contains(e.getLocation())&&!spawnClaims.allowed(e.getEntity())){e.setCancelled(true);return;}if(e.getSpawnReason()==CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER)e.getEntity().getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin,"trial_spawner_mob"),org.bukkit.persistence.PersistentDataType.BYTE,(byte)1);/** Warden is deliberately excluded here, not just from the later removal sweep (SettingsService.
 *  removableHostile() already exempts it via BossEventService.isPeacefulExempt()) — a Sculk Shrieker's
 *  summon must be allowed to actually happen in the first place, or the removal-sweep exemption never
 *  gets a chance to matter. Ordinary hostiles are completely unaffected: this only widens the "instanceof
 *  Enemy" check by one specific type, the same cancellation logic (radius, "does anyone nearby have it
 *  ON") still applies to everything else exactly as before. */
if((e.getSpawnReason()==CreatureSpawnEvent.SpawnReason.NATURAL||e.getSpawnReason()==CreatureSpawnEvent.SpawnReason.SPAWNER)&&e.getEntity() instanceof Enemy&&!(e.getEntity() instanceof Warden)
        /** Bastion garrison mobs are exempt on the SPAWN side as well, not just from the removal sweep.
         *  Exempting only removal still let Hostile Mobs Off empty a bastion, because the mobs simply never
         *  spawned in the first place -- the same shape of bug the Warden exemption above was fixed for. */
        &&!(e.getEntity() instanceof org.bukkit.entity.LivingEntity bastionCandidate&&plugin.settings().isBastionThreat(bastionCandidate))
&&!(e.getEntity() instanceof org.bukkit.entity.LivingEntity monumentCandidate&&plugin.settings().isMonumentThreat(monumentCandidate))){Collection<Player> nearby=e.getLocation().getNearbyPlayers(plugin.getConfig().getDouble("settings.natural-spawn-influence-radius",128));if(!nearby.isEmpty()&&nearby.stream().noneMatch(plugin.settings()::naturalSpawns)){e.setCancelled(true);return;}}bosses.onSpawn(e);}
    @EventHandler(priority=EventPriority.LOWEST) public void advancement(PlayerAdvancementDoneEvent e){
        net.kyori.adventure.text.Component original=e.message();if(original==null)return;e.message(null);
        io.papermc.paper.advancement.AdvancementDisplay display=e.getAdvancement().getDisplay();
        if(display==null||!display.doesAnnounceToChat())return;
        String verb=switch(display.frame()){case CHALLENGE->"has completed the challenge";case GOAL->"has reached the goal";default->"has made the advancement";};
        net.kyori.adventure.text.Component message=net.kyori.adventure.text.Component.text()
                .append(net.kyori.adventure.text.Component.text(plugin.nicknames().displayName(e.getPlayer()),net.kyori.adventure.text.format.NamedTextColor.WHITE))
                .append(net.kyori.adventure.text.Component.text(" "+verb+" ",display.frame().color()))
                .append(display.title())
                .build();
        plugin.getServer().broadcast(message);
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void entityMove(EntityMoveEvent e){if(e.getEntity() instanceof Villager villager)netWorth.villagerMoved(villager,e.getFrom(),e.getTo());if(e.getEntity() instanceof Player||spawnClaims.allowed(e.getEntity()))return;if(!spawnClaims.contains(e.getFrom())&&spawnClaims.contains(e.getTo()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void entityTeleport(EntityTeleportEvent e){if(e.getEntity() instanceof Player||spawnClaims.allowed(e.getEntity())||e.getTo()==null)return;if(!spawnClaims.contains(e.getFrom())&&spawnClaims.contains(e.getTo()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST) public void commandRate(PlayerCommandPreprocessEvent e){
        /** Duplicate-command guard: re-sending the exact same command within duplicate-window-ms is almost
         *  always a double-tap or a macro, and for GUI-opening commands it stacks inventories. Exempt
         *  commands (login/confirm/etc, see commandExempt) and admins are skipped by the checks below. */
        if(e.getPlayer()!=null&&!plugin.isAdmin(e.getPlayer())&&!commandExempt(e.getMessage())){
            long dupWindow=plugin.getConfig().getLong("rate-limits.commands.duplicate-window-ms",3000);
            String key=e.getMessage().trim().toLowerCase(Locale.ROOT);
            long now=System.currentTimeMillis();
            String prev=lastCommand.get(e.getPlayer().getUniqueId());
            Long prevAt=lastCommandAt.get(e.getPlayer().getUniqueId());
            if(prev!=null&&prevAt!=null&&prev.equals(key)&&now-prevAt<dupWindow){
                e.setCancelled(true);
                CoreUtil.error(e.getPlayer(),"You just ran that — wait a moment.");
                return;
            }
            lastCommand.put(e.getPlayer().getUniqueId(),key);lastCommandAt.put(e.getPlayer().getUniqueId(),now);
        }

        Player player=e.getPlayer();if(plugin.isAdmin(player)||commandExempt(e.getMessage()))return;long now=System.currentTimeMillis(),minimum=Math.max(250,plugin.getConfig().getLong("rate-limits.commands.minimum-interval-ms",500)),last=lastCommands.getOrDefault(player.getUniqueId(),0L);if(now-last>=minimum){lastCommands.put(player.getUniqueId(),now);return;}e.setCancelled(true);
        Deque<Long> violations=commandViolations.computeIfAbsent(player.getUniqueId(),key->new ArrayDeque<>());synchronized(violations){while(!violations.isEmpty()&&now-violations.peekFirst()>5000)violations.removeFirst();violations.addLast(now);if(violations.size()==3)CoreUtil.error(player,"Commands are being sent too quickly.");}
    }
    private boolean commandExempt(String raw){String lower=raw.toLowerCase(Locale.ROOT).trim();String root=lower.split("\\s+")[0];return Set.of("/login","/l","/register","/reg","/email","/captcha","/2fa","/authme","/tpaccept","/tpdeny","/settings","/shop").contains(root)||lower.matches(".*\\s(confirm|cancel)$");}
    @EventHandler(priority=EventPriority.HIGHEST) public void restrictedCommand(PlayerCommandPreprocessEvent e){
        String root=e.getMessage().substring(1).split("\\s+")[0].toLowerCase(Locale.ROOT);
        if(Set.of("gamemode","minecraft:gamemode").contains(root)){if(!plugin.isAdmin(e.getPlayer())){e.setCancelled(true);CoreUtil.error(e.getPlayer(),"That command is console-only.");}return;}
        if(Set.of("op","minecraft:op","deop","minecraft:deop").contains(root)){e.setCancelled(true);CoreUtil.error(e.getPlayer(),"That command is console-only.");return;}
        Set<String> sensitive=Set.of("plugins","pl","bukkit:plugins","version","ver","about","bukkit:version","bukkit:ver","paper","nick","unnick","nickother","realname","grim","grimac","spark");
        if(sensitive.contains(root)&&!plugin.isAdmin(e.getPlayer())){e.setCancelled(true);CoreUtil.error(e.getPlayer(),"That command is admin-only.");return;}
        if(plugin.isAdmin(e.getPlayer())&&Set.of("plugins","pl","bukkit:plugins").contains(root)){e.setCancelled(true);CoreUtil.msg(e.getPlayer(),"Plugins: "+String.join(", ",plugin.getServer().getPluginManager().getPlugins().length==0?List.of():Arrays.stream(plugin.getServer().getPluginManager().getPlugins()).map(org.bukkit.plugin.Plugin::getName).sorted().toList()));}
        else if(plugin.isAdmin(e.getPlayer())&&Set.of("version","ver","about","bukkit:version","bukkit:ver","paper").contains(root)){e.setCancelled(true);CoreUtil.msg(e.getPlayer(),"Paper "+plugin.getServer().getMinecraftVersion()+" • Ashfall "+plugin.getPluginMeta().getVersion());}
    }
    @EventHandler public void commandList(PlayerCommandSendEvent e){Set<String> hidden=new HashSet<>(Set.of("op","minecraft:op","deop","minecraft:deop","admin"));if(!plugin.isAdmin(e.getPlayer()))hidden.addAll(Set.of("gamemode","minecraft:gamemode","plugins","pl","bukkit:plugins","version","ver","about","bukkit:version","bukkit:ver","paper","nick","unnick","nickother","realname","ashfall","smp","smpcore","nickname","disguise","shout","openinv","oi","inv","open","clearinv","openender","oe","clearender","searchinv","si","searchender","se","silentcontainer","sc","silent","silentchest","anycontainer","ac","anychest","searchenchant","searchenchants","searchcontainer","searchchest"));e.getCommands().removeIf(command->hidden.contains(command.toLowerCase(Locale.ROOT)));}
    @EventHandler(priority=EventPriority.HIGHEST) public void gameModeSwitcher(PlayerGameModeChangeEvent e){if(e.getCause()==PlayerGameModeChangeEvent.Cause.GAMEMODE_SWITCHER){e.setCancelled(true);CoreUtil.error(e.getPlayer(),"Gamemode can only be changed from the server console.");}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void gameModeChanged(PlayerGameModeChangeEvent e){plugin.getServer().getScheduler().runTask(plugin,()->syncSpectatorVisibility(e.getPlayer()));}

    private void syncSpectatorVisibility(Player changed){
        boolean hidden=changed.getGameMode()==GameMode.SPECTATOR;
        for(Player viewer:plugin.getServer().getOnlinePlayers()){
            if(viewer.equals(changed))continue;
            if(hidden)viewer.hidePlayer(plugin,changed);else viewer.showPlayer(plugin,changed);
            if(viewer.getGameMode()==GameMode.SPECTATOR)changed.hidePlayer(plugin,viewer);else changed.showPlayer(plugin,viewer);
        }
    }
    @EventHandler public void mobDeath(EntityDeathEvent e){netWorth.entityRemoved(e.getEntity());if(!(e.getEntity() instanceof Player)&&e.getEntity().getKiller()!=null)db.incrementStat(CoreUtil.id(e.getEntity().getKiller()),"mob_kills");bosses.onDeath(e);}
    @EventHandler(ignoreCancelled=true) public void transform(EntityTransformEvent e){netWorth.entityRemoved(e.getEntity());bosses.onTransform(e);plugin.getServer().getScheduler().runTask(plugin,()->{for(Entity entity:e.getTransformedEntities())if(entity instanceof Villager villager)netWorth.villagerChanged(villager);});}
    @EventHandler public void entitiesLoad(org.bukkit.event.world.EntitiesLoadEvent e){bosses.onEntitiesLoaded(e.getEntities());netWorth.entitiesLoaded(e.getEntities());}
    @EventHandler(ignoreCancelled=true) public void villagerCareer(VillagerCareerChangeEvent e){plugin.getServer().getScheduler().runTask(plugin,()->netWorth.villagerChanged(e.getEntity()));}
    @EventHandler(ignoreCancelled=true) public void loot(LootGenerateEvent e){relics.hideInLoot(e.getLoot());}
    @EventHandler(ignoreCancelled=true) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p){ItemStack item=e.getItem().getItemStack(),migrated=spawners.migrateItem(item);if(migrated!=item)e.getItem().setItemStack(migrated);relics.discover(p,migrated);progress.acquired(p,migrated.getType());}}
    @EventHandler public void despawn(ItemDespawnEvent e){relics.itemLost(e.getEntity());}
    /** Runs before chatSpam and unconditionally for every sender (including admins) — chatSpam exempts admins
     *  entirely via an early return, and this needs to catch a hostile/compromised admin account too, not just
     *  regular players. See CoreUtil.stripExcessiveCombiningMarks() for why this exists: stacked Unicode
     *  combining marks ("zalgo text") in a chat message can hang the *client* rendering it, on every recipient's
     *  device, even though the server itself never does anything expensive with the text. */
    @SuppressWarnings("deprecation")
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void chatSanitize(AsyncPlayerChatEvent event){
        String original=event.getMessage(),cleaned=CoreUtil.stripExcessiveCombiningMarks(original,2);
        if(!cleaned.equals(original))event.setMessage(cleaned);
    }
    @SuppressWarnings("deprecation")
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void chatSpam(AsyncPlayerChatEvent event){
        Player player=event.getPlayer();if(plugin.isAdmin(player)||plugin.bank().awaitingChatInput(player)||plugin.marketplace().awaitingInput(player)||plugin.account().awaitingInput(player))return;String normalized=event.getMessage().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","").trim();if(normalized.isEmpty())return;long now=System.currentTimeMillis();ChatState state=chatStates.computeIfAbsent(player.getUniqueId(),key->new ChatState());
        synchronized(state){
            /** Flat floor rules, deliberately simple and separate from the escalating spam logic below:
             *  a 1s gap between any two messages and a 3s block on repeating the same one. Admins returned
             *  early above, so legitimate rapid staff use is unaffected. */
            long minGap=plugin.getConfig().getLong("rate-limits.chat.minimum-interval-ms",1000);
            long dupWindow=plugin.getConfig().getLong("rate-limits.chat.duplicate-window-ms",3000);
            if(state.lastAt>0&&now-state.lastAt<minGap){event.setCancelled(true);CoreUtil.error(player,"Slow down — one message per second.");return;}
            if(state.last!=null&&state.last.equals(normalized)&&now-state.lastAt<dupWindow){event.setCancelled(true);CoreUtil.error(player,"You just said that.");return;}
            if(now<state.cooldownUntil){event.setCancelled(true);if(state.cooldownUntil-now>1000)CoreUtil.error(player,"Chat cooldown: "+Math.max(1,(state.cooldownUntil-now+999)/1000)+"s.");return;}if(now-state.lastAt>30000)state.offenses=0;while(!state.times.isEmpty()&&now-state.times.peekFirst()>4000)state.times.removeFirst();state.times.addLast(now);boolean repeated=now-state.lastAt<12000&&nearDuplicate(state.last,normalized),rapid=state.times.size()>5;if(!repeated&&!rapid){state.last=normalized;state.lastAt=now;return;}event.setCancelled(true);state.offenses++;if(state.offenses>=3){state.cooldownUntil=now+Math.max(5,plugin.getConfig().getLong("rate-limits.chat.cooldown-seconds",10))*1000L;state.offenses=0;CoreUtil.error(player,"Chat paused briefly for repeated spam.");}else CoreUtil.error(player,repeated?"Please do not repeat the same message.":"Please slow down in chat.");}
    }
    /** Runs after chatSpam (LOWEST) has had its chance to cancel a spam/cooldown violation — ignoreCancelled
     *  means a message already blocked for spam never reaches faction chat either. Cancels the public chat
     *  event and re-sends via FactionService instead, so /f chat toggle mode never touches global chat. */
    @EventHandler(priority=EventPriority.LOW,ignoreCancelled=true) public void factionChat(AsyncPlayerChatEvent event){
        Player player=event.getPlayer();if(!factions.isChatMode(player))return;
        event.setCancelled(true);String message=event.getMessage();
        plugin.getServer().getScheduler().runTask(plugin,()->factions.sendChatModeMessage(player,message));
    }
    /** MONITOR so it only ever logs a message that's actually going out — spam-blocked (chatSpam, LOWEST) and
     *  faction-chat-mode (factionChat, LOW, logged separately by FactionService itself) messages are already
     *  cancelled by the time this runs, per ignoreCancelled. Admin-only /ashfall audit-style investigation
     *  tool, never shown to normal players. */
    /** The chat log write is handed to a separate async task instead of running inline.
     *
     *  Every Database method is synchronized on one connection, and the main thread holds that lock
     *  constantly. Writing the log inline meant the chat thread blocked on that lock before the message
     *  finished being delivered -- and while the chat thread held it, the main thread waited in turn, which
     *  is a tick stall and shows up to everyone as a stutter the moment somebody talks. Logging is not
     *  something chat delivery should ever wait for, so it is queued and the event returns immediately.
     *
     *  The values are captured up front: the event object must not be touched once this returns. */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void logPublicChat(AsyncPlayerChatEvent event){
        Player player=event.getPlayer();
        String id=CoreUtil.id(player),name=player.getName(),message=event.getMessage();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,()->{
            try{db.logChat("PUBLIC",id,name,null,null,message);}
            catch(Throwable error){plugin.getLogger().warning("[Chat] could not log a public message: "+error);}
        });
    }
    private boolean nearDuplicate(String first,String second){if(first.isEmpty())return false;if(first.equals(second))return true;if(Math.min(first.length(),second.length())<8)return false;int difference=Math.abs(first.length()-second.length());if(difference>3)return false;int mismatches=difference,limit=2;for(int i=0;i<Math.min(first.length(),second.length())&&mismatches<=limit;i++)if(first.charAt(i)!=second.charAt(i))mismatches++;return mismatches<=limit;}
    @EventHandler(priority=EventPriority.HIGHEST) public void inventory(InventoryClickEvent e){shop.click(e);merchants.click(e);factions.click(e);if(e.getWhoClicked() instanceof Player p)plugin.getServer().getScheduler().runTask(plugin,()->{scanImportant(p);spawners.migrateInventory(p);});}
    private void scanImportant(Player p){for(ItemStack item:p.getInventory().getContents())if(item!=null)progress.acquired(p,item.getType());progress.inspectLoadout(p,true);}
}
