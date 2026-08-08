package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Optional extras around a player's nametag: their faction tag beside the name, and their balance under it.
 *
 *  The two are rendered by deliberately different mechanisms, because they need different things:
 *
 *  FACTION TAG uses a scoreboard team PREFIX on the viewer's own scoreboard. That is the only way to place
 *  text immediately to the left of the vanilla name on the same line and have it stay there as the camera
 *  moves around the player -- a floating entity at a fixed horizontal offset would swing out of place as
 *  you orbit them. It is also exactly how TAB renders its prefixes, so it matches the tab list visually.
 *  Every player already has their own scoreboard (see UIService), so this stays per-viewer for free.
 *
 *  BALANCE uses a TextDisplay mounted on the player, since a second line under the name cannot be done with
 *  a team prefix and the below-name scoreboard slot only renders integers (no "$12.5k").
 *
 *  Neither ever renders the player's NAME -- the vanilla nametag is left completely alone.
 *
 *  Ownership note, which is what the earlier version got wrong: a mounted display is EJECTED when its
 *  carrier dies, and nothing re-mounted it, so it stayed floating at the death spot -- which is precisely
 *  where the grave then appeared. Every refresh now re-verifies that the display is still riding its owner
 *  and re-mounts it otherwise, and death removes it outright so it is rebuilt cleanly on respawn. The
 *  display is never positioned relative to anything except its owning player. */
final class NametagService implements Listener {
    private final SMPCore plugin;
    /** Owner id -> their balance display. One per player, shown/hidden per viewer. */
    private final Map<UUID,UUID> balanceDisplays=new HashMap<>();
    private final Map<UUID,String> lastBalanceText=new HashMap<>();
    /** viewer id -> (target id -> prefix currently applied), so teams are only touched when they change. */
    private final Map<UUID,Map<UUID,String>> appliedPrefix=new HashMap<>();
    private BukkitTask task;

    NametagService(SMPCore plugin){
        this.plugin=plugin;
        long period=Math.max(20,plugin.getConfig().getLong("nametags.refresh-ticks",40));
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,period,period);
    }
    void shutdown(){
        if(task!=null)task.cancel();
        for(UUID id:balanceDisplays.values())removeDisplay(id);
        balanceDisplays.clear();lastBalanceText.clear();appliedPrefix.clear();
    }
    @EventHandler public void quit(PlayerQuitEvent event){clearFor(event.getPlayer().getUniqueId());}
    /** Death ejects passengers, so the display is dropped rather than left stranded; the next refresh
     *  rebuilds it on the respawned player. */
    @EventHandler public void death(PlayerDeathEvent event){
        UUID id=event.getEntity().getUniqueId();
        removeDisplay(balanceDisplays.remove(id));
        lastBalanceText.remove(id);
    }
    private void clearFor(UUID id){
        removeDisplay(balanceDisplays.remove(id));
        lastBalanceText.remove(id);
        appliedPrefix.remove(id);
        for(Map<UUID,String> perViewer:appliedPrefix.values())perViewer.remove(id);
    }

    private void tick(){
        boolean anyBalance=false;
        for(Player viewer:plugin.getServer().getOnlinePlayers())if(plugin.settings().showBalanceNametags(viewer)){anyBalance=true;break;}
        for(Player target:plugin.getServer().getOnlinePlayers()){
            boolean eligible=!plugin.adminTools().isHiddenFromPublic(target);
            UUID id=target.getUniqueId();
            if(!eligible||!anyBalance){
                removeDisplay(balanceDisplays.remove(id));lastBalanceText.remove(id);
            }else{
                TextDisplay display=resolve(balanceDisplays.get(id));
                /** Re-verify ownership every pass: if it was ejected (death, dismount, teleport oddity) it is
                 *  re-mounted, so it can never drift onto a grave or any other entity. */
                if(display==null){display=spawnBalance(target);balanceDisplays.put(id,display.getUniqueId());}
                else if(!target.equals(display.getVehicle()))target.addPassenger(display);
                String text=compact(plugin.db().player(CoreUtil.id(target)).balance());
                if(!text.equals(lastBalanceText.get(id))){
                    lastBalanceText.put(id,text);
                    display.text(Component.text(text,NamedTextColor.GREEN));
                }
            }
            applyFactionPrefixes(target,eligible);
        }
        /** Per-viewer visibility for the balance line. */
        for(Player viewer:plugin.getServer().getOnlinePlayers()){
            boolean wants=plugin.settings().showBalanceNametags(viewer);
            for(Map.Entry<UUID,UUID> entry:balanceDisplays.entrySet()){
                TextDisplay display=resolve(entry.getValue());
                if(display==null)continue;
                if(wants)viewer.showEntity(plugin,display);else viewer.hideEntity(plugin,display);
            }
        }
    }
    /** Blue faction tag immediately left of the vanilla name, on each viewer's own scoreboard. */
    private void applyFactionPrefixes(Player target,boolean eligible){
        Database.FactionRow faction=eligible?plugin.db().factionOf(CoreUtil.id(target)):null;
        String desired=faction==null?"":"["+faction.tag()+"] ";
        for(Player viewer:plugin.getServer().getOnlinePlayers()){
            boolean wants=eligible&&plugin.settings().showFactionNametags(viewer)&&!desired.isEmpty();
            Map<UUID,String> applied=appliedPrefix.computeIfAbsent(viewer.getUniqueId(),k->new HashMap<>());
            String current=applied.get(target.getUniqueId());
            String want=wants?desired:"";
            if(want.equals(current==null?"":current))continue;
            applied.put(target.getUniqueId(),want);
            try{
                Scoreboard board=viewer.getScoreboard();
                String teamName="aft"+target.getUniqueId().toString().replace("-","").substring(0,12);
                Team team=board.getTeam(teamName);
                if(want.isEmpty()){
                    if(team!=null)team.removeEntry(target.getName());
                    continue;
                }
                if(team==null)team=board.registerNewTeam(teamName);
                team.prefix(Component.text(want,NamedTextColor.BLUE));
                if(!team.hasEntry(target.getName()))team.addEntry(target.getName());
            }catch(Throwable ignored){}
        }
    }
    /** Mounted on its owner and nothing else. Scale and offset are configurable because how tightly this
     *  sits under the vanilla name depends on whether the below-name health line is also enabled. */
    private TextDisplay spawnBalance(Player target){
        Location at=target.getLocation();
        float scale=(float)plugin.getConfig().getDouble("nametags.scale",1.15);
        float offset=(float)plugin.getConfig().getDouble("nametags.balance-offset",.32);
        TextDisplay display=at.getWorld().spawn(at,TextDisplay.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,d->{
            d.setPersistent(false);d.setInvulnerable(true);d.setGravity(false);
            d.setBillboard(Display.Billboard.CENTER);d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);d.setSeeThrough(false);
            d.setViewRange((float)plugin.getConfig().getDouble("nametags.view-range",1.0));
            d.setBackgroundColor(Color.fromARGB(0,0,0,0));
            d.setTransformation(new Transformation(new Vector3f(0,offset,0),new AxisAngle4f(),new Vector3f(scale,scale,scale),new AxisAngle4f()));
            d.setVisibleByDefault(false);
        });
        target.addPassenger(display);
        return display;
    }
    private TextDisplay resolve(UUID id){
        if(id==null)return null;
        Entity entity=plugin.getServer().getEntity(id);
        return entity instanceof TextDisplay display&&display.isValid()?display:null;
    }
    private void removeDisplay(UUID id){TextDisplay display=resolve(id);if(display!=null)display.remove();}
    /** $950 / $12.5k / $1.2mil -- short enough never to crowd the name. */
    private String compact(double amount){
        double abs=Math.abs(amount);
        if(abs<1000)return "$"+new DecimalFormat("0").format(amount);
        double scaled;String suffix;
        if(abs>=1_000_000_000){scaled=amount/1_000_000_000;suffix="bil";}
        else if(abs>=1_000_000){scaled=amount/1_000_000;suffix="mil";}
        else{scaled=amount/1000;suffix="k";}
        return "$"+new DecimalFormat(Math.abs(scaled)>=100?"0":"0.#").format(scaled)+suffix;
    }
}
