package net.communitysmp.core;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.*;

final class UIService {
    private static final class View {
        final Scoreboard board;final Objective sidebar;
        List<String> lines=List.of();boolean shown;
        View(Scoreboard board,Objective sidebar){this.board=board;this.sidebar=sidebar;}
    }
    private record Snapshot(Database.BountyRow top,Map<String,Double> bounties,NetWorthService.Row strongest,String activeName,String activeTime,String nextName,String nextTime){}

    /*  THE SIDEBAR IS AS WIDE AS ITS WIDEST LINE, AND NOT ONE PIXEL NARROWER.
     *
     *  It used to be budgeted in characters -- trim(name,16) -- which is not what the client measures. A
     *  sixteen-character faction name is 96 pixels of "Illicit Ember" or 112 of "MMMMMMMMMMMMMMMM", so one
     *  unlucky line decided the width of the whole panel and every other line sat in the empty half it left
     *  behind. Every line now fits the same pixel budget, and that is what makes the right-hand edge hold
     *  still while balances, timers and names change underneath it.
     *
     *  104 pixels is about seventeen average characters: wide enough for "$12.3M", for "2h 14m", and for a
     *  faction name long enough to recognise. Anything longer is cut with an ellipsis and stays a command
     *  away, rather than making the sidebar permanently wider for everybody.
     *
     *  The label column is a pixel column too. Padding "Balance" and "Shards" to the same CHARACTER count
     *  lines their values up only by luck -- the two words differ by three pixels, most of a space. */
    private static final int WIDTH=104, LABEL=46;

    private final SMPCore plugin;
    private final Database db;
    private final BossEventService events;
    private final NetWorthService netWorth;
    private final Map<UUID,View> views=new HashMap<>();
    private Snapshot snapshot;
    private Map<String,Double> balances=Map.of();
    private Map<String,Integer> shardBalances=Map.of();
    private long nextSnapshotAt;
    private BukkitTask task;

    UIService(SMPCore plugin,BossEventService events,NetWorthService netWorth){
        this.plugin=plugin;this.db=plugin.db();this.events=events;this.netWorth=netWorth;start();
    }

    private void start(){
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,20L,Math.max(20L,plugin.getConfig().getLong("ui.update-ticks",40)));
    }

    private void tick(){
        long now=System.currentTimeMillis();
        if(snapshot==null||now>=nextSnapshotAt){
            List<Database.BountyRow> rows=db.bounties();
            Map<String,Double> own=new HashMap<>();
            for(Database.BountyRow row:rows)own.put(row.target(),row.amount());
            List<NetWorthService.Row> factions=netWorth.rankings();
            snapshot=new Snapshot(rows.isEmpty()?null:rows.getFirst(),Map.copyOf(own),factions.isEmpty()?null:factions.getFirst(),events.activeEventName(),events.activeEventCountdown(),events.nextScheduledEventName(),events.nextScheduledEventCountdown());
            nextSnapshotAt=now+Math.max(1_000L,plugin.getConfig().getLong("ui.snapshot-update-ticks",100)*50L);
        }
        Collection<? extends Player> online=plugin.getServer().getOnlinePlayers();
        /*  Two statements for the whole server, not two per player. The balance and the shard count are the
         *  only per-player values on the sidebar, and reading them one at a time made the refresh cost scale
         *  with the population -- on top of which shardBalance() writes a row before it reads one. */
        if(online.isEmpty()){balances=Map.of();shardBalances=Map.of();}
        else{
            List<String> ids=new ArrayList<>(online.size());
            for(Player player:online)ids.add(CoreUtil.id(player));
            balances=db.balances(ids);
            shardBalances=db.shardBalances(ids);
        }
        for(Player player:online)update(player);
        views.keySet().removeIf(id->plugin.getServer().getPlayer(id)==null);
    }

    void update(Player player){
        Snapshot state=snapshot;
        if(state==null)return;
        String id=CoreUtil.id(player);
        Double balance=balances.get(id);
        if(balance==null){
            /*  Somebody the batch has not seen yet -- their first tick, or an out-of-band update() from the
             *  settings toggle. One read for one player is the right cost here; it was the per-cycle loop
             *  that had to stop doing it. */
            Database.PlayerRow row=db.player(id);
            if(row==null)return;
            balance=row.balance();
        }
        int shards=shardBalances.getOrDefault(id,-1);
        if(shards<0)shards=plugin.shards().balance(player);
        View view=views.computeIfAbsent(player.getUniqueId(),key->create(player));
        boolean enabled=plugin.progress().sidebarEnabled(player,plugin.isBedrock(player));

        if(enabled){
            if(!view.shown){view.sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);view.shown=true;}
            apply(view,compose(player,state,balance,shards));
        }else{
            if(view.shown){view.board.clearSlot(DisplaySlot.SIDEBAR);view.shown=false;}
            apply(view,List.of());
        }
        if(plugin.progress().trackingEnabled(player)&&events.active()&&!plugin.isTeleporting(player)&&!plugin.spawners().isHovering(player)&&plugin.teleports().combatRemaining(player)<=0){
            /*  The action bar is deliberately NOT deduplicated: it fades on its own after about three
             *  seconds, so an unchanged line still has to be re-sent to stay on screen. */
            player.sendActionBar(Component.text(events.trackingLine(player),NamedTextColor.LIGHT_PURPLE));
        }
    }

    /*  ONE COLUMN, NOT A COLOUR CHART.
     *
     *  This used to be five all-caps section headers in five different colours -- gold, purple, dark red,
     *  pink, aqua -- stacked on top of each other, with the three value rows underneath in three more. Nine
     *  colours in fifteen lines means none of them meant anything, and the sidebar is on screen permanently,
     *  so it set the tone for everything else.
     *
     *  Labels recede in grey, values are white, and the ONE thing that is genuinely urgent -- a live event
     *  or a world boss -- is the only thing allowed to be ember.
     *
     *  The three standings at the bottom used to be six rows: a short grey header, then the value. Half of
     *  them were two words in a panel sized for sixteen characters, which is where most of the empty space
     *  came from. The number now sits on the label row, where it fills it, and the name it belongs to has
     *  the row underneath to itself. Same two rows, same information, neither half blank -- and when there
     *  is no top bounty at all it collapses to one row instead of leaving a stranded "none" under a header.
     *
     *  The leading blank row is gone. Nothing chose it; it was the separator before a section that had since
     *  been removed, and it pushed everything down by one for no reason. */
    private List<String> compose(Player player,Snapshot state,double balance,int shards){
        List<String> lines=new ArrayList<>(14);
        lines.add(CoreUtil.C_BODY+CoreUtil.padTo("Balance",LABEL)+CoreUtil.C_TEXT+CoreUtil.compactMoney(balance));
        lines.add(CoreUtil.C_BODY+CoreUtil.padTo("Shards",LABEL)+CoreUtil.C_TEXT+(shards<100_000?Integer.toString(shards):CoreUtil.compact(shards)));
        double ownBounty=state.bounties().getOrDefault(CoreUtil.id(player),0d);
        /*  "Bounty" appeared twice on the same panel meaning two different things -- the price on your own
         *  head, and the largest one on the server. Yours is what you are: wanted. */
        if(ownBounty>0)lines.add(CoreUtil.C_BODY+CoreUtil.padTo("Wanted",LABEL)+CoreUtil.C_BAD+CoreUtil.compactMoney(ownBounty));

        if(events.active()||events.worldBossActive()){
            lines.add(gap(1));
            boolean boss=events.worldBossActive();
            lines.add(CoreUtil.C_EMBER+(boss?"World boss":"Event"));
            String name=CoreUtil.safe(boss?events.worldBossLine():state.activeName());
            String time=state.activeTime().isBlank()?"":state.activeTime()+" left";
            /*  One row when the name and the countdown both fit, two when they do not -- rather than three
             *  fixed rows, one of which was usually a name with a hand's width of nothing after it. Cutting
             *  the name to make room for the timer would be the wrong trade: a boss you cannot name is not
             *  worth a countdown. */
            if(!time.isBlank()&&CoreUtil.width(name)+8+CoreUtil.width(time)<=WIDTH)
                lines.add(CoreUtil.C_TEXT+name+CoreUtil.C_MUTE+"  "+time);
            else{
                lines.add(CoreUtil.C_TEXT+CoreUtil.fit(name,WIDTH));
                if(!time.isBlank())lines.add(CoreUtil.C_MUTE+time);
            }
        }

        lines.add(gap(2));
        standing(lines,"Next event",state.nextTime(),state.nextName());
        Database.BountyRow top=state.top();
        standing(lines,"Top bounty",top==null?"":CoreUtil.compactMoney(top.amount()),top==null?null:CoreUtil.safe(top.targetName()));
        NetWorthService.Row strongest=state.strongest();
        standing(lines,"Top faction",strongest==null?"":CoreUtil.compactMoney(strongest.value()),strongest==null?null:CoreUtil.safe(strongest.name()));
        return lines;
    }

    /** A quiet label carrying its number, and the name it belongs to on the row underneath -- cut to the
     *  budget, so a sixteen-character faction cannot widen the panel for everyone. With nothing to show it
     *  is one row saying so, rather than a header over an empty value. */
    private void standing(List<String> lines,String label,String value,String name){
        if(name==null||name.isBlank()||name.equalsIgnoreCase("none")){
            lines.add(CoreUtil.C_MUTE+label+"  none");
            return;
        }
        lines.add(CoreUtil.C_MUTE+label+(value==null||value.isBlank()?"":CoreUtil.C_BODY+"  "+value));
        lines.add(CoreUtil.C_BODY+CoreUtil.fit(name,WIDTH));
    }

    /*  ONLY WHAT CHANGED MOVES.
     *
     *  A scoreboard row is identified by its own text, so the old code reset all fifteen and re-added all
     *  fifteen whenever any one of them differed -- thirty packets because a balance ticked over by a
     *  dollar, and the client repaints the panel each time it happens. Rows that are still present and still
     *  in the same position are now left alone entirely.
     *
     *  It also keeps the staleness guarantee the teardown was providing: a row that is gone from the new set
     *  is reset by name, so the world-boss block and its blank separator disappear together the moment the
     *  boss dies, and a shorter list can never leave the tail of a longer one behind it. */
    private void apply(View view,List<String> lines){
        if(view.lines.equals(lines))return;
        for(String old:view.lines)if(!lines.contains(old))view.board.resetScores(old);
        int score=lines.size();
        for(String line:lines){
            Score entry=view.sidebar.getScore(line);
            if(!entry.isScoreSet()||entry.getScore()!=score)entry.setScore(score);
            score--;
        }
        view.lines=List.copyOf(lines);
    }

    private View create(Player player){
        Scoreboard board=plugin.getServer().getScoreboardManager().getNewScoreboard();
        Objective sidebar=board.registerNewObjective("smpui",Criteria.DUMMY,Component.text("Ashfall",CoreUtil.EMBER));
        sidebar.numberFormat(NumberFormat.blank());
        if(plugin.getConfig().getBoolean("ui.health-below-name",true))try{
            Objective health=board.registerNewObjective("health",Criteria.HEALTH,Component.text("❤",NamedTextColor.RED));
            health.setDisplaySlot(DisplaySlot.BELOW_NAME);
        }catch(IllegalArgumentException error){
            plugin.getLogger().warning("Could not enable below-name health: "+error.getMessage());
        }
        player.setScoreboard(board);
        return new View(board,sidebar);
    }

    void removeSidebar(Player player){
        View view=views.get(player.getUniqueId());
        if(view!=null){
            view.board.clearSlot(DisplaySlot.SIDEBAR);
            view.shown=false;
            apply(view,List.of());
        }
    }
    void remove(Player player){views.remove(player.getUniqueId());player.setScoreboard(plugin.getServer().getScoreboardManager().getMainScoreboard());}
    void shutdown(){if(task!=null)task.cancel();for(Player player:plugin.getServer().getOnlinePlayers())remove(player);views.clear();}

    /** Scoreboard rows are keyed by their own text, so two blank separators would be the same row. A bare
     *  colour code is unique, invisible AND zero-width -- padding with spaces made a "blank" row that was
     *  really twelve pixels wide, and on a narrow panel that is visible. */
    private String gap(int index){return "§"+index;}

    /** Proves the composition holds at the extremes rather than at whatever width the test account happens
     *  to have: the longest name, the largest balance and the longest countdown together still fit the
     *  budget, and nothing that matters is cut down to nothing. */
    String selfTest(){
        String longName="Ashfall Illicit Emberwatch Consortium";
        if(CoreUtil.width("Illicit")==7*6)return "width() is counting characters, not pixels";
        if(CoreUtil.width("§aabc")!=CoreUtil.width("abc"))return "colour codes are being measured";
        if(CoreUtil.width(CoreUtil.fit(longName,WIDTH))>WIDTH)return "fit() overshot the budget";
        if(!CoreUtil.fit(longName,WIDTH).endsWith("…"))return "fit() did not mark the cut";
        if(CoreUtil.fit("A",WIDTH).contains("…"))return "fit() cut a name that already fitted";
        if(CoreUtil.fit(longName,4).length()<2)return "fit() cut a name away to nothing";
        if(CoreUtil.width(CoreUtil.padTo("Shards",LABEL))<LABEL)return "padTo() stopped short of the column";
        if(CoreUtil.width(CoreUtil.padTo("Shards",LABEL))>LABEL+3)return "padTo() overshot the column";
        List<String> probe=new ArrayList<>();
        probe.add(CoreUtil.C_BODY+CoreUtil.padTo("Balance",LABEL)+CoreUtil.C_TEXT+CoreUtil.compactMoney(999_999_999_999d));
        probe.add(CoreUtil.C_BODY+CoreUtil.padTo("Wanted",LABEL)+CoreUtil.C_BAD+CoreUtil.compactMoney(999_999_999_999d));
        standing(probe,"Top faction",CoreUtil.compactMoney(999_999_999_999d),longName);
        standing(probe,"Next event","2h 14m","Ember Tide");
        int rows=probe.size();
        standing(probe,"Top bounty","",null);
        if(probe.size()!=rows+1)return "an absent standing did not collapse to one row";
        for(String line:probe)if(CoreUtil.width(line)>WIDTH)return "a sidebar line renders "+CoreUtil.width(line)+" wide, over the "+WIDTH+" budget: "+line;
        if(gap(1).equals(gap(2)))return "two separators share a scoreboard row";
        if(CoreUtil.width(gap(1))!=0)return "a blank separator is not blank";
        return null;
    }
}
