package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
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

import java.util.*;

final class BulletinService implements Listener {
    private static final String STATE="bulletin.wall.v1";
    private record Holder(int panel) implements InventoryHolder {@Override public Inventory getInventory(){return null;}}
    private final SMPCore plugin;private final Database db;private final NamespacedKey panelKey;private final List<UUID> panels=new ArrayList<>();private BukkitTask refreshTask;

    BulletinService(SMPCore plugin){
        this.plugin=plugin;db=plugin.db();panelKey=new NamespacedKey(plugin,"bulletin_panel");
        plugin.getServer().getScheduler().runTaskLater(plugin,this::restore,60L);
        long period=Math.max(30,Math.min(60,plugin.getConfig().getLong("bulletin.refresh-seconds",40)))*20L;refreshTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::refresh,period,period);
    }
    void shutdown(){if(refreshTask!=null)refreshTask.cancel();}
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
        panels.clear();Vector3f normal=new Vector3f(face.getModX(),face.getModY(),face.getModZ()),right=(face==BlockFace.EAST||face==BlockFace.WEST)?new Vector3f(0,0,1):new Vector3f(1,0,0);if(face==BlockFace.DOWN)right.x=-1;
        for(int index=0;index<3;index++){double offset=(index-1)*2.35;Location location=anchor.getLocation().add(.5,.5,.5).add(normal.x*.53+right.x*offset,normal.y*.53+right.y*offset,normal.z*.53+right.z*offset);location.setYaw(yaw(face));location.setPitch(pitch(face));int panel=index;
            TextDisplay display=anchor.getWorld().spawn(location,TextDisplay.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,text->{text.setPersistent(true);text.setInvulnerable(true);text.setGravity(false);text.setBillboard(Display.Billboard.FIXED);text.setAlignment(TextDisplay.TextAlignment.CENTER);text.setLineWidth(180);text.setShadowed(true);text.setSeeThrough(false);text.setViewRange(2.5f);text.setBrightness(new Display.Brightness(15,15));text.setBackgroundColor(Color.fromARGB(205,7,7,7));text.setTransformation(new Transformation(new Vector3f(),new AxisAngle4f(),new Vector3f(.72f,.72f,.72f),new AxisAngle4f()));text.getPersistentDataContainer().set(panelKey,PersistentDataType.INTEGER,panel);});panels.add(display.getUniqueId());
        }
        db.state(STATE,anchor.getWorld().getName()+"|"+anchor.getX()+"|"+anchor.getY()+"|"+anchor.getZ()+"|"+face.name()+"|"+String.join(",",panels.stream().map(UUID::toString).toList()));refresh();
    }
    void refresh(){List<TextDisplay> displays=resolve();if(displays.size()!=3)return;displays.sort(Comparator.comparingInt(display->display.getPersistentDataContainer().getOrDefault(panelKey,PersistentDataType.INTEGER,0)));displays.get(0).text(profilePanel());displays.get(1).text(centerPanel());displays.get(2).text(factionPanel());}
    private Component profilePanel(){return panel(List.of("YOUR PROFILE","","Right-click to view","rank • money • shards","playtime • PvP"),NamedTextColor.AQUA);}
    private Component centerPanel(){
        List<String> lines=new ArrayList<>();lines.add("ASHFALL BULLETIN");lines.add("Active  "+plugin.bosses().activeEventLine());lines.add("Next  "+plugin.bosses().nextEventLine());lines.add("Dragon  "+plugin.weeklyDragon().countdown());lines.add("Boss  "+plugin.bosses().worldBossLine());Database.BankRow bank=plugin.bank().treasury();lines.add("Bank  "+CoreUtil.compactMoney(bank==null?0:bank.balance()));List<Database.HistoryRow> history=db.history(null,1,0);if(!history.isEmpty()){lines.add("");lines.add(shorten(history.getFirst().message(),36));}return panel(lines,NamedTextColor.GOLD);
    }
    private Component factionPanel(){return panel(List.of("YOUR FACTION","","Right-click to view","claim • net worth","members • relations"),NamedTextColor.GREEN);}
    private Component panel(List<String> lines,NamedTextColor title){Component result=Component.text(lines.getFirst(),title);for(int i=1;i<lines.size();i++)result=result.append(Component.newline()).append(Component.text(lines.get(i),NamedTextColor.WHITE));return result;}

    @EventHandler public void interact(PlayerInteractAtEntityEvent event){if(!event.getRightClicked().getPersistentDataContainer().has(panelKey,PersistentDataType.INTEGER))return;event.setCancelled(true);openPersonal(event.getPlayer(),event.getRightClicked().getPersistentDataContainer().getOrDefault(panelKey,PersistentDataType.INTEGER,1));}
    @EventHandler public void click(InventoryClickEvent event){if(event.getInventory().getHolder() instanceof Holder)event.setCancelled(true);}
    @EventHandler public void damage(EntityDamageEvent event){if(event.getEntity().getPersistentDataContainer().has(panelKey,PersistentDataType.INTEGER))event.setCancelled(true);}
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

    private void restore(){String value=db.state(STATE);if(value==null||value.isBlank())return;String[] parts=value.split("\\|",6);if(parts.length<5)return;World world=plugin.getServer().getWorld(parts[0]);if(world==null)return;try{Block anchor=world.getBlockAt(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]));anchor.getChunk().load();panels.clear();if(parts.length==6)for(String id:parts[5].split(","))try{panels.add(UUID.fromString(id));}catch(IllegalArgumentException ignored){}removeEntitiesOnly();spawn(anchor,BlockFace.valueOf(parts[4]));}catch(Exception error){plugin.getLogger().warning("Could not restore bulletin: "+error.getMessage());}}
    private List<TextDisplay> resolve(){List<TextDisplay> result=new ArrayList<>();for(UUID id:panels){Entity entity=plugin.getServer().getEntity(id);if(entity instanceof TextDisplay display&&display.getPersistentDataContainer().has(panelKey,PersistentDataType.INTEGER))result.add(display);}return result;}
    private void remove(boolean clearState){removeEntitiesOnly();if(clearState)db.state(STATE,"");}
    private void removeEntitiesOnly(){for(UUID id:panels){Entity entity=plugin.getServer().getEntity(id);if(entity!=null)entity.remove();}panels.clear();}
    boolean selfTest(){return plugin.getConfig().getLong("bulletin.refresh-seconds",40)>=30&&BlockFace.values().length>=6;}
    private ItemStack item(Material material,String name,List<String> lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(name,NamedTextColor.GOLD));meta.lore(lore.stream().map(line->Component.text(line,NamedTextColor.GRAY)).toList());item.setItemMeta(meta);return item;}
    private String shorten(String value,int max){return value.length()<=max?value:value.substring(0,max-1)+"…";}
    private float yaw(BlockFace face){return switch(face){case NORTH->180;case SOUTH->0;case EAST->-90;case WEST->90;default->0;};}
    private float pitch(BlockFace face){return face==BlockFace.UP?-90:face==BlockFace.DOWN?90:0;}
}
