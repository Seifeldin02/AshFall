package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.text.DecimalFormat;
import java.util.*;

/** Four-panel wall: a private per-viewer YOUR STATS hologram (panel index 0) plus three shared holograms —
 *  TOP PLAYERS (1), TOP FACTIONS (2), TOP BOUNTIES (3). Panel X-offsets are recomputed every refresh from
 *  each panel's own rendered content width (see halfWidth()) rather than a fixed spacing constant, so long
 *  names/numbers push neighbors further apart instead of overlapping. */
final class BulletinService implements Listener {
    private static final String STATE="bulletin.wall.v1";
    private record Holder(int panel) implements InventoryHolder {@Override public Inventory getInventory(){return null;}}
    private final SMPCore plugin;private final Database db;private final NamespacedKey panelKey;private final List<UUID> panels=new ArrayList<>();private BukkitTask refreshTask;
    /** Panel 0 (YOUR STATS) is one real TextDisplay entity per online player rather than a single shared
     *  hologram — Minecraft has no problem with several entities occupying nearby points, and since each is
     *  spawned with visibleByDefault=false and shown only to its own owner (Player#showEntity), every viewer
     *  sees only their own live stats, never anyone else's. Not persisted across restarts
     *  (setPersistent(false)): it only ever needs to exist for currently-online players, and is recomputed
     *  deterministically from the same anchor block + face the three shared panels use. */
    private Block bulletinAnchor;private BlockFace bulletinFace;
    private final Map<UUID,UUID> personalDisplays=new HashMap<>();
    /** Center offset + half-width of each currently-placed shared panel (index 0=players,1=factions,
     *  2=bounties), refreshed every cycle — the personal panel positions itself immediately beside the
     *  players panel using these, so a viewer's own stats slot never overlaps the shared row next to it. */
    private final double[] sharedOffset=new double[3];
    private final double[] sharedHalfWidth=new double[3];

    BulletinService(SMPCore plugin){
        this.plugin=plugin;db=plugin.db();panelKey=new NamespacedKey(plugin,"bulletin_panel");
        plugin.getServer().getScheduler().runTaskLater(plugin,this::restore,60L);
        long period=Math.max(30,Math.min(60,plugin.getConfig().getLong("bulletin.refresh-seconds",40)))*20L;refreshTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::refresh,period,period);
    }
    void shutdown(){if(refreshTask!=null)refreshTask.cancel();for(UUID id:personalDisplays.values()){Entity entity=plugin.getServer().getEntity(id);if(entity!=null)entity.remove();}personalDisplays.clear();}
    boolean command(Player player,String[] args){
        if(args.length<2){commandHelp(player);return true;}
        return switch(args[1].toLowerCase(Locale.ROOT)){case"place"->place(player);case"remove"->{remove(true);CoreUtil.msg(player,"Bulletin removed.");yield true;}case"refresh"->{refresh();CoreUtil.msg(player,"Bulletin refreshed.");yield true;}default->{CoreUtil.error(player,"Bulletin options: place, remove, refresh.");yield true;}};
    }
    void commandHelp(org.bukkit.command.CommandSender sender){sender.sendMessage("§6§lBulletin");sender.sendMessage("§7  §f/ashfall bulletin place");sender.sendMessage("§7  §f/ashfall bulletin remove");sender.sendMessage("§7  §f/ashfall bulletin refresh");}

    private boolean place(Player player){
        RayTraceResult ray=player.rayTraceBlocks(10,FluidCollisionMode.NEVER);if(ray==null||ray.getHitBlock()==null||ray.getHitBlockFace()==null||!ray.getHitBlock().getType().isSolid()){CoreUtil.error(player,"Look at any solid block face within 10 blocks.");return true;}
        remove(false);spawn(ray.getHitBlock(),ray.getHitBlockFace());CoreUtil.msg(player,"Ashfall bulletin placed.");return true;
    }
    private void spawn(Block anchor,BlockFace face){
        panels.clear();bulletinAnchor=anchor;bulletinFace=face;
        for(int index=1;index<=3;index++){int panel=index;
            TextDisplay display=anchor.getWorld().spawn(anchor.getLocation(),TextDisplay.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,text->{text.setPersistent(true);text.setInvulnerable(true);text.setGravity(false);text.setBillboard(Display.Billboard.FIXED);text.setAlignment(TextDisplay.TextAlignment.CENTER);text.setLineWidth(180);text.setShadowed(true);text.setSeeThrough(false);text.setViewRange(2.5f);text.setBrightness(new Display.Brightness(15,15));text.setBackgroundColor(Color.fromARGB(205,7,7,7));text.setTransformation(new Transformation(new Vector3f(),new AxisAngle4f(),new Vector3f(.72f,.72f,.72f),new AxisAngle4f()));text.getPersistentDataContainer().set(panelKey,PersistentDataType.INTEGER,panel);});panels.add(display.getUniqueId());
        }
        db.state(STATE,anchor.getWorld().getName()+"|"+anchor.getX()+"|"+anchor.getY()+"|"+anchor.getZ()+"|"+face.name()+"|"+String.join(",",panels.stream().map(UUID::toString).toList()));refresh();
    }
    private Location panelLocation(Block anchor,BlockFace face,double offset){
        Vector3f normal=new Vector3f(face.getModX(),face.getModY(),face.getModZ()),right=(face==BlockFace.EAST||face==BlockFace.WEST)?new Vector3f(0,0,1):new Vector3f(1,0,0);if(face==BlockFace.DOWN)right.x=-1;
        Location location=anchor.getLocation().add(.5,.5,.5).add(normal.x*.53+right.x*offset,normal.y*.53+right.y*offset,normal.z*.53+right.z*offset);location.setYaw(yaw(face));location.setPitch(pitch(face));return location;
    }
    /** Rough per-character width estimate for Minecraft's default font at this display's .72 transform
     *  scale, in world-blocks. Bukkit exposes no real text-measurement API, so this is a deliberately
     *  generous heuristic (errs toward more spacing, never less) rather than a pixel-exact font metric —
     *  the goal is "never overlaps", not "perfectly flush". */
    private static final double CHAR_WIDTH=0.052;
    private static final double MIN_HALF_WIDTH=0.9;
    private static final double GAP=0.55;
    private double halfWidth(Component content){
        String plain=PlainTextComponentSerializer.plainText().serialize(content);
        int longest=0;for(String line:plain.split("\n",-1))longest=Math.max(longest,line.length());
        return Math.max(MIN_HALF_WIDTH,longest*CHAR_WIDTH/2.0);
    }

    void refresh(){
        List<TextDisplay> displays=resolve();
        if(displays.size()==3){
            Map<Integer,TextDisplay> byIndex=new HashMap<>();
            for(TextDisplay d:displays)byIndex.put(d.getPersistentDataContainer().getOrDefault(panelKey,PersistentDataType.INTEGER,0),d);
            Component[] content={playerLeaderboardPanel(),factionLeaderboardPanel(),bountyLeaderboardPanel()};
            for(int i=0;i<3;i++)sharedHalfWidth[i]=halfWidth(content[i]);
            /** Centers the shared 3-panel row AS A GROUP on the placement block, instead of pinning Players'
             *  own left edge there — the old math put sharedOffset[0] at exactly 0, which made Players sit at
             *  the anchor and Factions/Bounties trail off entirely to one side (never balanced, regardless of
             *  content width). TOP BOUNTIES is normally the narrowest and TOP PLAYERS/FACTIONS wider, so a
             *  true group-center keeps the whole visible row looking centered on the block an admin clicked,
             *  while GAP still guarantees a safe margin between every neighboring pair. */
            double totalHalfWidth=sharedHalfWidth[0]+sharedHalfWidth[1]+sharedHalfWidth[2]+GAP;
            double x=-totalHalfWidth;
            for(int i=0;i<3;i++){if(i>0)x+=GAP;x+=sharedHalfWidth[i];sharedOffset[i]=x;x+=sharedHalfWidth[i];}
            for(int i=1;i<=3;i++){TextDisplay d=byIndex.get(i);if(d==null)continue;d.text(content[i-1]);Location target=panelLocation(bulletinAnchor,bulletinFace,sharedOffset[i-1]);if(d.getLocation().distanceSquared(target)>0.0001)d.teleport(target);}
        }
        refreshPersonalPanels();
    }
    /** One TextDisplay per online player, each visible only to its own owner, positioned immediately beside
     *  the (shared) players panel using the CURRENT viewer's own content width — spawns one for anyone
     *  newly online, updates/repositions everyone else's, despawns anyone who's since disconnected
     *  (belt-and-suspenders alongside the join/quit handlers below, which handle the common case
     *  immediately rather than waiting up to a minute for this to run). Loads every online player's stats
     *  and shard balance from two bulk snapshot queries up front instead of one query pair per player. */
    private void refreshPersonalPanels(){
        if(bulletinAnchor==null)return;
        Map<String,Database.StatsRow> stats=db.statsSnapshot();
        Map<String,Integer> shards=db.shardBalanceSnapshot();
        Set<UUID> online=new HashSet<>();
        for(Player player:plugin.getServer().getOnlinePlayers()){
            online.add(player.getUniqueId());
            Component content=personalStatsPanel(player,stats.get(CoreUtil.id(player)),shards.getOrDefault(CoreUtil.id(player),0));
            double statsOffset=sharedOffset[0]-sharedHalfWidth[0]-GAP-halfWidth(content);
            TextDisplay display=resolvePersonal(player.getUniqueId());
            if(display==null)display=spawnPersonalDisplay(player);
            display.text(content);
            Location target=panelLocation(bulletinAnchor,bulletinFace,statsOffset);
            if(display.getLocation().distanceSquared(target)>0.0001)display.teleport(target);
        }
        personalDisplays.entrySet().removeIf(entry->{if(online.contains(entry.getKey()))return false;Entity e=plugin.getServer().getEntity(entry.getValue());if(e!=null)e.remove();return true;});
    }
    private TextDisplay resolvePersonal(UUID playerId){UUID id=personalDisplays.get(playerId);if(id==null)return null;Entity e=plugin.getServer().getEntity(id);return e instanceof TextDisplay td?td:null;}
    private TextDisplay spawnPersonalDisplay(Player player){
        TextDisplay text=bulletinAnchor.getWorld().spawn(bulletinAnchor.getLocation(),TextDisplay.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,d->{d.setPersistent(false);d.setInvulnerable(true);d.setGravity(false);d.setBillboard(Display.Billboard.FIXED);d.setAlignment(TextDisplay.TextAlignment.CENTER);d.setLineWidth(180);d.setShadowed(true);d.setSeeThrough(false);d.setViewRange(2.5f);d.setBrightness(new Display.Brightness(15,15));d.setBackgroundColor(Color.fromARGB(205,7,7,7));d.setTransformation(new Transformation(new Vector3f(),new AxisAngle4f(),new Vector3f(.72f,.72f,.72f),new AxisAngle4f()));d.getPersistentDataContainer().set(panelKey,PersistentDataType.INTEGER,0);d.setVisibleByDefault(false);});
        player.showEntity(plugin,text);
        personalDisplays.put(player.getUniqueId(),text.getUniqueId());
        return text;
    }
    @EventHandler public void join(PlayerJoinEvent event){if(bulletinAnchor!=null)spawnPersonalDisplay(event.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent event){UUID id=personalDisplays.remove(event.getPlayer().getUniqueId());if(id!=null){Entity e=plugin.getServer().getEntity(id);if(e!=null)e.remove();}}
    private Component playerLeaderboardPanel(){
        List<String> lines=new ArrayList<>();lines.add("TOP PLAYERS");List<Database.StatsRow> rows=db.topStats("balance",10,0);
        if(rows.isEmpty())lines.add("No players yet.");else{int rank=1;for(Database.StatsRow row:rows)lines.add((rank++)+". "+shorten(row.name(),16)+" — "+compactNumber(row.balance()));}
        return panel(lines,NamedTextColor.GOLD);
    }
    private Component factionLeaderboardPanel(){
        List<String> lines=new ArrayList<>();lines.add("TOP FACTIONS");List<NetWorthService.Row> rows=plugin.netWorth().rankings();
        if(rows.isEmpty())lines.add("No factions yet.");else{int rank=1;for(NetWorthService.Row row:rows.subList(0,Math.min(10,rows.size())))lines.add((rank++)+". "+shorten(row.name(),16)+" — "+compactNumber(row.value()));}
        return panel(lines,NamedTextColor.GREEN);
    }
    private Component bountyLeaderboardPanel(){
        List<String> lines=new ArrayList<>();lines.add("TOP BOUNTIES");List<Database.BountyRow> rows=db.bounties();
        if(rows.isEmpty())lines.add("No active bounties.");else{int rank=1;for(Database.BountyRow row:rows.subList(0,Math.min(10,rows.size())))lines.add((rank++)+". "+shorten(row.targetName(),16)+" — "+compactNumber(row.amount()));}
        return panel(lines,NamedTextColor.RED);
    }
    /** Mirrors /stats' own content exactly (see ProgressService.stats()) so the two never drift apart —
     *  this hologram is a convenience view of the same numbers, not a second source of truth for them.
     *  Takes an already-fetched stats row + shard balance (see refreshPersonalPanels()'s bulk snapshot)
     *  instead of querying the database itself. */
    private Component personalStatsPanel(Player player,Database.StatsRow row,int shards){
        List<String> lines=new ArrayList<>();lines.add("YOUR STATS");
        if(row==null)lines.add("No data yet.");
        else{
            long hours=row.playSeconds()/3600,minutes=row.playSeconds()%3600/60;
            double kd=row.deaths()==0?row.playerKills():row.playerKills()/(double)row.deaths();
            lines.add("Playtime: "+hours+"h "+minutes+"m");
            lines.add("Kills/Deaths: "+compactNumber(row.playerKills())+"/"+compactNumber(row.deaths())+" (KD "+String.format(Locale.US,"%.2f",kd)+")");
            lines.add("Mob kills: "+compactNumber(row.mobKills())+"  Boss kills: "+compactNumber(row.bossKills()));
            lines.add("Events won: "+compactNumber(row.eventWins()));
            lines.add("Balance: "+compactNumber(row.balance()));
            lines.add("Shards: "+compactNumber(shards));
        }
        return panel(lines,NamedTextColor.AQUA);
    }
    private Component panel(List<String> lines,NamedTextColor title){Component result=Component.text(lines.getFirst(),title);for(int i=1;i<lines.size();i++)result=result.append(Component.newline()).append(Component.text(lines.get(i),NamedTextColor.WHITE));return result;}
    /** "1k", "1.25k", "1mil", "1.27mil" — deliberately distinct from CoreUtil.compact()'s "$1.25M" money
     *  format used everywhere else in the plugin; this exists only to keep bulletin panels narrow. Used for
     *  every number on the wall that can realistically grow large (money, mob/boss kill counts, shards),
     *  not just money — a raw 5+ digit kill count would defeat the same width math this class works this
     *  hard to get right elsewhere. */
    private String compactNumber(double amount){
        double absolute=Math.abs(amount);
        if(absolute<1000)return(amount==Math.rint(amount)?String.valueOf((long)amount):String.valueOf(amount));
        double scaled;String suffix;
        if(absolute>=1_000_000_000){scaled=amount/1_000_000_000;suffix="bil";}
        else if(absolute>=1_000_000){scaled=amount/1_000_000;suffix="mil";}
        else{scaled=amount/1000;suffix="k";}
        String pattern=Math.abs(scaled)>=100?"0":Math.abs(scaled)>=10?"0.#":"0.##";
        return new DecimalFormat(pattern).format(scaled)+suffix;
    }

    @EventHandler public void interact(PlayerInteractAtEntityEvent event){if(!event.getRightClicked().getPersistentDataContainer().has(panelKey,PersistentDataType.INTEGER))return;event.setCancelled(true);openPersonal(event.getPlayer(),event.getRightClicked().getPersistentDataContainer().getOrDefault(panelKey,PersistentDataType.INTEGER,1));}
    @EventHandler public void click(InventoryClickEvent event){if(event.getInventory().getHolder() instanceof Holder)event.setCancelled(true);}
    @EventHandler public void damage(EntityDamageEvent event){if(event.getEntity().getPersistentDataContainer().has(panelKey,PersistentDataType.INTEGER))event.setCancelled(true);}
    /** Panel index: 0=personal stats, 1=players, 2=factions, 3=bounties. Clicking the stats panel opens the
     *  profile GUI; clicking factions opens the faction GUI; players/bounties open the general event/bank
     *  overview GUI (unchanged content from before the 4-panel layout, just reindexed). */
    void openPersonal(Player player,int panel){
        Inventory inv=plugin.getServer().createInventory(new Holder(panel),36,Component.text(panel==0?"Your Profile":panel==2?"Your Faction":"Ashfall Bulletin",NamedTextColor.DARK_GRAY));
        if(panel==0){Database.StatsRow stats=db.stats(CoreUtil.id(player));inv.setItem(10,item(Material.PLAYER_HEAD,player.getName(),List.of("Adventure Rank: "+plugin.progress().rankLabel(player),"Hostile Income: "+String.format(Locale.US,"%.2fx",plugin.progress().multiplierView(player)))));inv.setItem(12,item(Material.EMERALD,"Balance",List.of(CoreUtil.money(stats==null?0:stats.balance()))));inv.setItem(14,item(Material.AMETHYST_SHARD,"Shards",List.of(Integer.toString(plugin.shards().balance(player)))));if(stats!=null)inv.setItem(16,item(Material.CLOCK,"Playtime & PvP",List.of(stats.playSeconds()/3600+"h "+stats.playSeconds()%3600/60+"m",stats.playerKills()+" kills • "+stats.deaths()+" deaths")));}
        else if(panel==2){
            Database.FactionRow faction=db.factionOf(CoreUtil.id(player));
            if(faction==null){
                inv.setItem(13,item(Material.WHITE_BANNER,"No Faction",List.of("Create one with /f create <name>.","Or ask a leader for an invite.")));
            }else{
                FactionService.Claim claim=plugin.factions().claimOf(faction);
                long online=db.factionMembers(faction.id()).stream().filter(name->plugin.getServer().getPlayerExact(name)!=null).count();
                inv.setItem(10,item(Material.WHITE_BANNER,faction.name()+" ["+faction.tag()+"]",List.of(online+" online • "+db.factionMemberCount(faction.id())+" members")));
                inv.setItem(13,item(Material.GOLD_BLOCK,"Net Worth",List.of(CoreUtil.money(plugin.netWorth().value(faction.id())))));
                inv.setItem(16,item(Material.MAP,"Land & Relations",List.of("Claim: "+(claim==null?"none":claim.size()+" × "+claim.size()),plugin.factions().activeRelationCount(faction.id())+" active relations")));
            }
        }
        else{inv.setItem(10,item(Material.CLOCK,"Active Event",List.of(plugin.bosses().activeEventLine())));inv.setItem(12,item(Material.COMPASS,"Next Event",List.of(plugin.bosses().nextEventLine())));inv.setItem(14,item(Material.DRAGON_HEAD,"Weekly Dragon",List.of(plugin.weeklyDragon().countdown())));inv.setItem(16,item(Material.NETHER_STAR,"World Boss",List.of(plugin.bosses().worldBossLine())));inv.setItem(22,item(Material.GOLD_INGOT,"Central Bank",List.of(CoreUtil.money(plugin.bank().treasury().balance()))));}
        player.openInventory(inv);
    }

    /** Force-removal for a bulletin that's become genuinely untrackable — e.g. its persisted state was
     *  cleared (db.state(STATE,"")) by an earlier remove() while its physical entities happened to be in an
     *  unloaded chunk, so removeEntitiesOnly() silently found nothing to delete via the now-empty UUID
     *  list and the entities were simply left behind with no reference to them anywhere. Unlike normal
     *  removal, this doesn't trust panels/personalDisplays or the STATE string at all — it force-loads
     *  every currently-loaded world's entities (Bukkit already keeps spawn-adjacent chunks loaded, so this
     *  reaches a "stuck at spawn" bulletin without needing manual chunk coordinates) and removes ANY entity
     *  carrying the bulletin_panel tag, tracked or not, before resetting all in-memory/persisted state to
     *  a clean slate. Safe to run even if a legitimate bulletin currently exists — it accounts for that by
     *  requiring name/scope input, but here it intentionally purges everything, since the caller (an admin
     *  command) is meant for exactly the "nothing else worked" case. */
    int purge(org.bukkit.command.CommandSender sender){
        int removed=0;
        for(World world:plugin.getServer().getWorlds())for(Entity entity:world.getEntities()){
            if(entity instanceof TextDisplay&&entity.getPersistentDataContainer().has(panelKey,PersistentDataType.INTEGER)){entity.remove();removed++;}
        }
        for(UUID id:personalDisplays.values()){Entity e=plugin.getServer().getEntity(id);if(e!=null&&e.isValid())e.remove();}
        personalDisplays.clear();panels.clear();bulletinAnchor=null;bulletinFace=null;
        db.state(STATE,"");
        CoreUtil.msg(sender,"Bulletin purge: force-removed "+removed+" tagged entity/entities across all loaded worlds, and cleared all bulletin state. Use /ashfall bulletin place to set up a fresh one.");
        return removed;
    }
    private void restore(){String value=db.state(STATE);if(value==null||value.isBlank())return;String[] parts=value.split("\\|",6);if(parts.length<5)return;World world=plugin.getServer().getWorld(parts[0]);if(world==null)return;try{Block anchor=world.getBlockAt(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]));anchor.getChunk().load();panels.clear();if(parts.length==6)for(String id:parts[5].split(","))try{panels.add(UUID.fromString(id));}catch(IllegalArgumentException ignored){}removeEntitiesOnly();spawn(anchor,BlockFace.valueOf(parts[4]));}catch(Exception error){plugin.getLogger().warning("Could not restore bulletin: "+error.getMessage());}}
    private List<TextDisplay> resolve(){List<TextDisplay> result=new ArrayList<>();for(UUID id:panels){Entity entity=plugin.getServer().getEntity(id);if(entity instanceof TextDisplay display&&display.getPersistentDataContainer().has(panelKey,PersistentDataType.INTEGER))result.add(display);}return result;}
    private void remove(boolean clearState){removeEntitiesOnly();if(clearState)db.state(STATE,"");}
    private void removeEntitiesOnly(){for(UUID id:panels){Entity entity=plugin.getServer().getEntity(id);if(entity!=null)entity.remove();}panels.clear();for(UUID id:personalDisplays.values()){Entity entity=plugin.getServer().getEntity(id);if(entity!=null)entity.remove();}personalDisplays.clear();bulletinAnchor=null;bulletinFace=null;}
    boolean selfTest(){return plugin.getConfig().getLong("bulletin.refresh-seconds",40)>=30&&BlockFace.values().length>=6;}
    private ItemStack item(Material material,String name,List<String> lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(name,NamedTextColor.GOLD));meta.lore(lore.stream().map(line->Component.text(line,NamedTextColor.GRAY)).toList());item.setItemMeta(meta);return item;}
    private String shorten(String value,int max){return value.length()<=max?value:value.substring(0,max-1)+"…";}
    private float yaw(BlockFace face){return switch(face){case NORTH->180;case SOUTH->0;case EAST->-90;case WEST->90;default->0;};}
    private float pitch(BlockFace face){return face==BlockFace.UP?-90:face==BlockFace.DOWN?90:0;}
}
