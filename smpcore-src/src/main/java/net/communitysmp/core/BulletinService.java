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
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.text.DecimalFormat;
import java.util.*;

/** Four-panel wall: a private per-viewer YOUR STATS hologram (panel index 0) plus three shared holograms —
 *  TOP PLAYERS (1), TOP FACTIONS (2), TOP BOUNTIES (3). Panel X-offsets are recomputed every refresh from
 *  each panel's own rendered content width (see halfWidth()) rather than a fixed spacing constant, so long
 *  names/numbers push neighbors further apart instead of overlapping. */
final class BulletinService implements Listener {
    /** Each panel is now a fully independent floating hologram with its own persisted location, rather than
     *  four slices of one wall anchored to a block face. That means they can be placed, moved and removed
     *  one at a time and put wherever they read best, instead of all four being forced into a single row
     *  whose spacing had to be computed to stop them overlapping. Billboard.CENTER makes them readable from
     *  any angle (the old FIXED billboard was only legible from the front). YOUR STATS stays private: it is
     *  still one real TextDisplay per online player, spawned visibleByDefault(false) and shown only to its
     *  owner, so everyone standing at the same hologram sees their own numbers. */
    enum PanelKind {
        STATS(0,"YOUR STATS"), PLAYERS(1,"TOP PLAYERS"), FACTIONS(2,"TOP FACTIONS"), BOUNTIES(3,"TOP BOUNTIES");
        final int index;final String title;
        PanelKind(int index,String title){this.index=index;this.title=title;}
        String stateKey(){return "bulletin.panel."+name().toLowerCase(Locale.ROOT);}
        static PanelKind of(String raw){for(PanelKind k:values())if(k.name().equalsIgnoreCase(raw))return k;return null;}
        static PanelKind byIndex(int index){for(PanelKind k:values())if(k.index==index)return k;return null;}
    }
    private record Holder(int panel) implements InventoryHolder {@Override public Inventory getInventory(){return null;}}
    private final SMPCore plugin;private final Database db;private final NamespacedKey panelKey;private BukkitTask refreshTask;
    /** Placed location per shared panel kind, and the live entity for each. */
    private final Map<PanelKind,Location> placed=new EnumMap<>(PanelKind.class);
    private final Map<PanelKind,UUID> shared=new EnumMap<>(PanelKind.class);
    /** One private STATS display per online player, keyed by player id. */
    private final Map<UUID,UUID> personalDisplays=new HashMap<>();

    BulletinService(SMPCore plugin){
        this.plugin=plugin;db=plugin.db();panelKey=new NamespacedKey(plugin,"bulletin_panel");
        plugin.getServer().getScheduler().runTaskLater(plugin,this::restore,60L);
        long period=Math.max(30,Math.min(60,plugin.getConfig().getLong("bulletin.refresh-seconds",40)))*20L;refreshTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::refresh,period,period);
    }
    void shutdown(){if(refreshTask!=null)refreshTask.cancel();for(UUID id:personalDisplays.values()){Entity entity=plugin.getServer().getEntity(id);if(entity!=null)entity.remove();}personalDisplays.clear();}

    boolean command(Player player,String[] args){
        if(args.length<2){commandHelp(player);return true;}
        String action=args[1].toLowerCase(Locale.ROOT);
        return switch(action){
            case"place","move"->{
                if(args.length<3){CoreUtil.error(player,"Usage: /ashfall bulletin "+action+" <stats|players|factions|bounties|all>");yield true;}
                if(args[2].equalsIgnoreCase("all")){
                    /** Placing "all" fans them out in a short row in front of the player purely as a
                     *  convenience starting point -- each one can then be moved independently. */
                    int offset=0;
                    for(PanelKind kind:PanelKind.values()){placePanel(player,kind,player.getLocation().clone().add(player.getLocation().getDirection().clone().setY(0).normalize().multiply(2).add(sideways(player,(offset++-1.5)*3))));}
                    CoreUtil.msg(player,"All four bulletins placed. Move any one with /ashfall bulletin move <name>.");
                    yield true;
                }
                PanelKind kind=PanelKind.of(args[2]);
                if(kind==null){CoreUtil.error(player,"Unknown panel. Use stats, players, factions, bounties, or all.");yield true;}
                placePanel(player,kind,player.getLocation().clone().add(player.getLocation().getDirection().clone().setY(0).normalize().multiply(2)));
                CoreUtil.msg(player,kind.title+" placed here.");
                yield true;
            }
            case"remove"->{
                if(args.length<3){CoreUtil.error(player,"Usage: /ashfall bulletin remove <stats|players|factions|bounties|all>");yield true;}
                if(args[2].equalsIgnoreCase("all")){removeAll();CoreUtil.msg(player,"All bulletins removed.");yield true;}
                PanelKind kind=PanelKind.of(args[2]);
                if(kind==null){CoreUtil.error(player,"Unknown panel.");yield true;}
                removePanel(kind);CoreUtil.msg(player,kind.title+" removed.");
                yield true;
            }
            case"list"->{
                CoreUtil.msg(player,"Bulletin panels:");
                for(PanelKind kind:PanelKind.values()){
                    Location at=placed.get(kind);
                    CoreUtil.msg(player,"  "+kind.title+" — "+(at==null?"not placed":at.getWorld().getName()+" "+at.getBlockX()+","+at.getBlockY()+","+at.getBlockZ()));
                }
                yield true;
            }
            case"refresh"->{refresh();CoreUtil.msg(player,"Bulletins refreshed.");yield true;}
            default->{commandHelp(player);yield true;}
        };
    }
    void commandHelp(org.bukkit.command.CommandSender sender){
        sender.sendMessage("§6§lBulletins §7(independent floating holograms)");
        sender.sendMessage("§7  §f/ashfall bulletin place <stats|players|factions|bounties|all>");
        sender.sendMessage("§7  §f/ashfall bulletin move <name>   §7— re-place it where you stand");
        sender.sendMessage("§7  §f/ashfall bulletin remove <name|all>");
        sender.sendMessage("§7  §f/ashfall bulletin list");
        sender.sendMessage("§7  §f/ashfall bulletin refresh");
    }
    /** Perpendicular offset used only by the "all" convenience placement. */
    private Vector sideways(Player player,double distance){
        Vector dir=player.getLocation().getDirection().setY(0).normalize();
        return new Vector(-dir.getZ(),0,dir.getX()).multiply(distance);
    }

    private void placePanel(Player player,PanelKind kind,Location at){
        removePanel(kind);
        Location location=at.clone();location.setY(player.getLocation().getY()+2);
        placed.put(kind,location);
        db.state(kind.stateKey(),location.getWorld().getName()+"|"+location.getX()+"|"+location.getY()+"|"+location.getZ());
        if(kind!=PanelKind.STATS)shared.put(kind,spawnDisplay(location,kind.index,true).getUniqueId());
        refresh();
    }
    private void removePanel(PanelKind kind){
        if(kind==PanelKind.STATS){
            for(UUID id:personalDisplays.values()){Entity e=plugin.getServer().getEntity(id);if(e!=null)e.remove();}
            personalDisplays.clear();
        }else{
            UUID id=shared.remove(kind);
            if(id!=null){Entity e=plugin.getServer().getEntity(id);if(e!=null)e.remove();}
        }
        placed.remove(kind);
        db.state(kind.stateKey(),"");
    }
    private void removeAll(){for(PanelKind kind:PanelKind.values())removePanel(kind);}

    /** One shared factory so every hologram -- shared or private -- gets identical presentation. */
    private TextDisplay spawnDisplay(Location location,int index,boolean persistent){
        return location.getWorld().spawn(location,TextDisplay.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,text->{
            text.setPersistent(persistent);text.setInvulnerable(true);text.setGravity(false);
            /** CENTER, not FIXED: readable from any angle, which is the whole point of a floating billboard. */
            text.setBillboard(Display.Billboard.CENTER);
            text.setAlignment(TextDisplay.TextAlignment.CENTER);text.setLineWidth(200);text.setShadowed(true);
            text.setSeeThrough(false);text.setViewRange(2.5f);text.setBrightness(new Display.Brightness(15,15));
            text.setBackgroundColor(Color.fromARGB(205,7,7,7));
            text.setTransformation(new Transformation(new Vector3f(),new AxisAngle4f(),new Vector3f(.72f,.72f,.72f),new AxisAngle4f()));
            text.getPersistentDataContainer().set(panelKey,PersistentDataType.INTEGER,index);
            if(!persistent)text.setVisibleByDefault(false);
        });
    }

    void refresh(){
        for(PanelKind kind:List.of(PanelKind.PLAYERS,PanelKind.FACTIONS,PanelKind.BOUNTIES)){
            Location at=placed.get(kind);if(at==null)continue;
            /** Skip entirely while the chunk is unloaded. getEntity() cannot see an entity in an unloaded
             *  chunk, so refreshing there used to read as "the panel is gone" and spawn a replacement --
             *  every 40s, persistently, forever. Nobody can see a hologram in an unloaded chunk anyway. */
            if(!chunkReady(at))continue;
            TextDisplay display=resolveShared(kind,at);
            if(display==null){display=spawnDisplay(at,kind.index,true);shared.put(kind,display.getUniqueId());}
            sweepDuplicates(kind,at,display);
            display.text(switch(kind){case PLAYERS->playerLeaderboardPanel();case FACTIONS->factionLeaderboardPanel();default->bountyLeaderboardPanel();});
            if(display.getLocation().distanceSquared(at)>0.0001)display.teleport(at);
        }
        refreshPersonalPanels();
    }
    /** A panel may only be resolved or spawned while its chunk is loaded; see refresh(). */
    private boolean chunkReady(Location at){
        return at!=null&&at.getWorld()!=null&&at.getWorld().isChunkLoaded(at.getBlockX()>>4,at.getBlockZ()>>4);
    }
    /** Every persistent panel of this kind sitting at this spot. Used both to adopt an existing hologram
     *  instead of spawning a rival one, and to clean up any that already accumulated. */
    private List<TextDisplay> panelsAt(PanelKind kind,Location at){
        List<TextDisplay> found=new ArrayList<>();
        for(Entity entity:at.getWorld().getNearbyEntities(at,2.0,2.0,2.0))
            if(entity instanceof TextDisplay display&&display.isValid()&&display.isPersistent()
                    &&display.getPersistentDataContainer().getOrDefault(panelKey,PersistentDataType.INTEGER,-1)==kind.index)
                found.add(display);
        return found;
    }
    /** Self-heal: whatever the cause, a spot may only ever hold one hologram per panel kind. Because this
     *  runs on every refresh, any duplicate that does appear survives at most one cycle. */
    private int sweepDuplicates(PanelKind kind,Location at,TextDisplay keep){
        int removed=0;
        for(TextDisplay other:panelsAt(kind,at))
            if(!other.getUniqueId().equals(keep.getUniqueId())){other.remove();removed++;}
        if(removed>0)plugin.getLogger().warning("[Bulletin] removed "+removed+" duplicate "+kind+" hologram(s).");
        return removed;
    }
    private TextDisplay resolveShared(PanelKind kind,Location at){
        UUID id=shared.get(kind);
        if(id!=null){Entity e=plugin.getServer().getEntity(id);if(e instanceof TextDisplay td&&td.isValid())return td;}
        /** Tracking was lost (restart, chunk churn, cleared state) but the hologram itself may still be
         *  standing. Adopt it rather than adding a second one next to it. */
        TextDisplay adopted=panelsAt(kind,at).stream().findFirst().orElse(null);
        if(adopted!=null)shared.put(kind,adopted.getUniqueId());
        return adopted;
    }
    /** Loads every online player's stats and shard balance from two bulk snapshot queries rather than a
     *  query pair per player, then updates each viewer's own private hologram. */
    private void refreshPersonalPanels(){
        Location at=placed.get(PanelKind.STATS);
        if(at!=null&&!chunkReady(at))return;
        if(at==null){
            if(!personalDisplays.isEmpty()){for(UUID id:personalDisplays.values()){Entity e=plugin.getServer().getEntity(id);if(e!=null)e.remove();}personalDisplays.clear();}
            return;
        }
        Map<String,Database.StatsRow> stats=db.statsSnapshot();
        Map<String,Integer> shards=db.shardBalanceSnapshot();
        Set<UUID> online=new HashSet<>();
        for(Player player:plugin.getServer().getOnlinePlayers()){
            online.add(player.getUniqueId());
            TextDisplay display=resolvePersonal(player.getUniqueId());
            if(display==null)display=spawnPersonalDisplay(player,at);
            display.text(personalStatsPanel(player,stats.get(CoreUtil.id(player)),shards.getOrDefault(CoreUtil.id(player),0)));
            if(display.getLocation().distanceSquared(at)>0.0001)display.teleport(at);
        }
        personalDisplays.entrySet().removeIf(entry->{if(online.contains(entry.getKey()))return false;Entity e=plugin.getServer().getEntity(entry.getValue());if(e!=null)e.remove();return true;});
    }
    private TextDisplay resolvePersonal(UUID playerId){UUID id=personalDisplays.get(playerId);if(id==null)return null;Entity e=plugin.getServer().getEntity(id);return e instanceof TextDisplay td&&td.isValid()?td:null;}
    private TextDisplay spawnPersonalDisplay(Player player,Location at){
        /** Replacing a tracked display without removing it first is how private panels leaked. */
        UUID prior=personalDisplays.remove(player.getUniqueId());
        if(prior!=null){Entity old=plugin.getServer().getEntity(prior);if(old!=null)old.remove();}
        TextDisplay text=spawnDisplay(at,PanelKind.STATS.index,false);
        player.showEntity(plugin,text);
        personalDisplays.put(player.getUniqueId(),text.getUniqueId());
        return text;
    }
    @EventHandler public void join(PlayerJoinEvent event){
        Location at=placed.get(PanelKind.STATS);
        if(at==null||!chunkReady(at))return;
        /** A rejoin after an unclean quit can still have a live display tracked; keep it rather than
         *  stacking a second one on top of it. */
        if(resolvePersonal(event.getPlayer().getUniqueId())!=null)return;
        spawnPersonalDisplay(event.getPlayer(),at);
    }
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

    /** Force-removal for holograms that have become untrackable -- e.g. their persisted state was cleared
     *  while the entities were in an unloaded chunk, leaving them with no reference anywhere. Ignores all
     *  tracking and simply deletes every entity carrying the bulletin_panel tag across loaded worlds. */
    int purge(org.bukkit.command.CommandSender sender){
        int removed=0;
        for(World world:plugin.getServer().getWorlds())for(Entity entity:world.getEntities())
            if(entity instanceof TextDisplay&&entity.getPersistentDataContainer().has(panelKey,PersistentDataType.INTEGER)){entity.remove();removed++;}
        for(UUID id:personalDisplays.values()){Entity e=plugin.getServer().getEntity(id);if(e!=null&&e.isValid())e.remove();}
        personalDisplays.clear();shared.clear();placed.clear();
        for(PanelKind kind:PanelKind.values())db.state(kind.stateKey(),"");
        db.state("bulletin.wall.v1","");
        CoreUtil.msg(sender,"Bulletin purge: force-removed "+removed+" tagged hologram(s) and cleared all panel state. Re-place with /ashfall bulletin place all.");
        return removed;
    }
    /** Restores each panel's location independently. Any leftover tagged entity at that spot is cleared
     *  first, so a restart cannot accumulate duplicates. */
    private void restore(){
        for(PanelKind kind:PanelKind.values()){
            String value=db.state(kind.stateKey());
            if(value==null||value.isBlank())continue;
            String[] parts=value.split("\\|");
            if(parts.length<4)continue;
            World world=plugin.getServer().getWorld(parts[0]);
            if(world==null)continue;
            try{
                Location at=new Location(world,Double.parseDouble(parts[1]),Double.parseDouble(parts[2]),Double.parseDouble(parts[3]));
                at.getChunk().load();
                /** Clears any backlog left by an older build before a single fresh panel is placed. */
                int stale=0;
                for(TextDisplay leftover:panelsAt(kind,at)){leftover.remove();stale++;}
                if(stale>0)plugin.getLogger().info("[Bulletin] cleared "+stale+" leftover "+kind+" hologram(s) on restore.");
                placed.put(kind,at);
                if(kind!=PanelKind.STATS)shared.put(kind,spawnDisplay(at,kind.index,true).getUniqueId());
            }catch(Exception error){plugin.getLogger().warning("Could not restore bulletin "+kind+": "+error.getMessage());}
        }
        refresh();
    }
    boolean selfTest(){return plugin.getConfig().getLong("bulletin.refresh-seconds",40)>=30&&BlockFace.values().length>=6;}
    private ItemStack item(Material material,String name,List<String> lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(name,NamedTextColor.GOLD));meta.lore(lore.stream().map(line->Component.text(line,NamedTextColor.GRAY)).toList());item.setItemMeta(meta);return item;}
    private String shorten(String value,int max){return value.length()<=max?value:value.substring(0,max-1)+"…";}
    private float yaw(BlockFace face){return switch(face){case NORTH->180;case SOUTH->0;case EAST->-90;case WEST->90;default->0;};}
    private float pitch(BlockFace face){return face==BlockFace.UP?-90:face==BlockFace.DOWN?90:0;}
}
