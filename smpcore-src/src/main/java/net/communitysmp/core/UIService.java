package net.communitysmp.core;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.*;

final class UIService {
    private record View(Scoreboard board,Objective sidebar,List<String> lines){}
    private record Snapshot(Database.BountyRow top,Map<String,Double> bounties,NetWorthService.Row strongest,String activeName,String activeTime,String nextName,String nextTime){}
    private final SMPCore plugin;
    private final Database db;
    private final BossEventService events;
    private final NetWorthService netWorth;
    private final Map<UUID,View> views=new HashMap<>();
    private Snapshot snapshot;
    private long nextSnapshotAt;
    private BukkitTask task;

    UIService(SMPCore plugin,BossEventService events,NetWorthService netWorth){
        this.plugin=plugin;this.db=plugin.db();this.events=events;this.netWorth=netWorth;start();
    }

    private void start(){
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,()->{
            long now=System.currentTimeMillis();
            if(snapshot==null||now>=nextSnapshotAt){
                List<Database.BountyRow> rows=db.bounties();
                Map<String,Double> own=new HashMap<>();
                for(Database.BountyRow row:rows)own.put(row.target(),row.amount());
                List<NetWorthService.Row> factions=netWorth.rankings();
                snapshot=new Snapshot(rows.isEmpty()?null:rows.getFirst(),Map.copyOf(own),factions.isEmpty()?null:factions.getFirst(),events.activeEventName(),events.activeEventCountdown(),events.nextScheduledEventName(),events.nextScheduledEventCountdown());
                nextSnapshotAt=now+Math.max(1_000L,plugin.getConfig().getLong("ui.snapshot-update-ticks",100)*50L);
            }
            for(Player player:plugin.getServer().getOnlinePlayers())update(player);
            views.keySet().removeIf(id->plugin.getServer().getPlayer(id)==null);
        },20L,Math.max(20L,plugin.getConfig().getLong("ui.update-ticks",40)));
    }

    void update(Player player){
        Database.PlayerRow row=db.player(CoreUtil.id(player));
        Snapshot state=snapshot;
        if(row==null||state==null)return;
        boolean enabled=plugin.progress().sidebarEnabled(player,plugin.isBedrock(player));
        View view=views.computeIfAbsent(player.getUniqueId(),id->create(player));
        Database.BountyRow top=state.top();
        NetWorthService.Row strongest=state.strongest();
        List<String> lines=new ArrayList<>();
        lines.add("§6TOP BOUNTY");
        lines.add(top==null?"§8None active":"§f"+trim(top.targetName(),10)+" §c"+CoreUtil.compactMoney(top.amount()));
        if(events.active()){
            lines.add("§5ACTIVE EVENT");
            lines.add("§f"+trim(state.activeName(),16));
            lines.add("§8"+state.activeTime());
        }
        if(events.worldBossActive()){
            lines.add("§4WORLD BOSS");
            lines.add("§f"+trim(events.worldBossLine(),16));
            lines.add("§8Despawns in "+events.activeEventCountdown());
        }
        lines.add("§dNEXT EVENT");
        lines.add("§f"+trim(state.nextName(),16));
        if(!state.nextTime().isBlank())lines.add("§8"+state.nextTime());
        lines.add("§bTOP FACTION");
        lines.add(strongest==null?"§8None yet":"§f"+trim(strongest.name(),10)+" §b"+CoreUtil.compactMoney(strongest.value()));
        lines.add("§cShards §f"+plugin.shards().balance(player));
        lines.add("§7Bounty §c"+CoreUtil.compactMoney(state.bounties().getOrDefault(CoreUtil.id(player),0d)));
        lines.add("§aBalance §f"+CoreUtil.compactMoney(row.balance()));

        if(enabled){
            view.sidebar().setDisplaySlot(DisplaySlot.SIDEBAR);
            if(!view.lines().equals(lines)){
                for(String old:view.lines())view.board().resetScores(old);
                int score=lines.size();
                for(String line:lines)view.sidebar().getScore(line).setScore(score--);
                views.put(player.getUniqueId(),new View(view.board(),view.sidebar(),List.copyOf(lines)));
            }
        }else{
            view.board().clearSlot(DisplaySlot.SIDEBAR);
            if(!view.lines().isEmpty()){
                for(String old:view.lines())view.board().resetScores(old);
                views.put(player.getUniqueId(),new View(view.board(),view.sidebar(),List.of()));
            }
        }
        if(plugin.progress().trackingEnabled(player)&&events.active()&&!plugin.isTeleporting(player)&&!plugin.spawners().isHovering(player)&&plugin.teleports().combatRemaining(player)<=0){
            player.sendActionBar(Component.text(events.trackingLine(player),NamedTextColor.LIGHT_PURPLE));
        }
    }

    private View create(Player player){
        Scoreboard board=plugin.getServer().getScoreboardManager().getNewScoreboard();
        Objective sidebar=board.registerNewObjective("smpui",Criteria.DUMMY,Component.text("ASHFALL",NamedTextColor.GOLD));
        sidebar.numberFormat(NumberFormat.blank());
        if(plugin.getConfig().getBoolean("ui.health-below-name",true))try{
            Objective health=board.registerNewObjective("health",Criteria.HEALTH,Component.text("❤",NamedTextColor.RED));
            health.setDisplaySlot(DisplaySlot.BELOW_NAME);
        }catch(IllegalArgumentException error){
            plugin.getLogger().warning("Could not enable below-name health: "+error.getMessage());
        }
        player.setScoreboard(board);
        return new View(board,sidebar,List.of());
    }

    void removeSidebar(Player player){
        View view=views.get(player.getUniqueId());
        if(view!=null){
            view.board().clearSlot(DisplaySlot.SIDEBAR);
            for(String old:view.lines())view.board().resetScores(old);
            views.put(player.getUniqueId(),new View(view.board(),view.sidebar(),List.of()));
        }
    }
    void remove(Player player){views.remove(player.getUniqueId());player.setScoreboard(plugin.getServer().getScoreboardManager().getMainScoreboard());}
    void shutdown(){if(task!=null)task.cancel();for(Player player:plugin.getServer().getOnlinePlayers())remove(player);views.clear();}
    private String trim(String text,int max){return text.length()<=max?text:text.substring(0,Math.max(1,max-1))+"…";}
}
