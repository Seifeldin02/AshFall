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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Optional extra lines under a player's nametag: their balance and/or their faction tag.
 *
 *  Structure matters here for cost. The obvious design -- one display per (viewer, target) pair -- is
 *  O(n^2) and would mean 2,500 entities at 50 players. Instead each player carries at most TWO displays of
 *  their own (balance, faction) mounted as passengers, and per-viewer choice is expressed purely through
 *  Player#showEntity / #hideEntity. That is O(n) entities, and toggling a setting costs a visibility call
 *  rather than spawning anything.
 *
 *  The vanilla nametag itself is never touched -- no team prefixes, no renames -- so nicknames, TAB and
 *  anything else that owns the name line keep working exactly as before. Text is refreshed on a slow timer
 *  (balances rarely change in a way anyone needs sub-second) rather than per tick, and a display is only
 *  updated when its content actually changed. */
final class NametagService implements Listener {
    private record Tags(UUID balance,UUID faction) {}
    private final SMPCore plugin;
    private final Map<UUID,Tags> tags=new HashMap<>();
    private final Map<UUID,String> lastBalance=new HashMap<>(), lastFaction=new HashMap<>();
    private BukkitTask task;

    NametagService(SMPCore plugin){
        this.plugin=plugin;
        long period=Math.max(20,plugin.getConfig().getLong("nametags.refresh-ticks",40));
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,period,period);
    }
    void shutdown(){if(task!=null)task.cancel();for(Tags t:tags.values())remove(t);tags.clear();}
    @EventHandler public void quit(PlayerQuitEvent event){Tags t=tags.remove(event.getPlayer().getUniqueId());if(t!=null)remove(t);lastBalance.remove(event.getPlayer().getUniqueId());lastFaction.remove(event.getPlayer().getUniqueId());}

    private void tick(){
        boolean anyBalance=false,anyFaction=false;
        for(Player viewer:plugin.getServer().getOnlinePlayers()){
            if(plugin.settings().showBalanceNametags(viewer))anyBalance=true;
            if(plugin.settings().showFactionNametags(viewer))anyFaction=true;
        }
        for(Player target:plugin.getServer().getOnlinePlayers()){
            /** Hidden, vanished and spectating players get no extra lines at all -- these must never be a
             *  way to locate someone who is deliberately not visible. Uses the existing canonical check
             *  rather than re-deriving it, which the codebase already warns against doing by hand. */
            boolean eligible=!plugin.adminTools().isHiddenFromPublic(target);
            Tags current=tags.get(target.getUniqueId());
            if(!eligible||(!anyBalance&&!anyFaction)){
                if(current!=null){remove(current);tags.remove(target.getUniqueId());}
                continue;
            }
            String balanceText=anyBalance?compact(plugin.db().player(CoreUtil.id(target)).balance()):null;
            Database.FactionRow faction=anyFaction?plugin.db().factionOf(CoreUtil.id(target)):null;
            String factionText=faction==null?null:"["+faction.tag()+"]";
            Tags updated=ensure(target,balanceText!=null,factionText!=null,current);
            if(updated==null)continue;
            apply(updated.balance(),balanceText,NamedTextColor.GREEN,lastBalance,target.getUniqueId());
            apply(updated.faction(),factionText,NamedTextColor.AQUA,lastFaction,target.getUniqueId());
            /** Visibility is the per-viewer part: the same display is simply shown or hidden per player. */
            for(Player viewer:plugin.getServer().getOnlinePlayers()){
                show(viewer,updated.balance(),plugin.settings().showBalanceNametags(viewer)&&balanceText!=null);
                show(viewer,updated.faction(),plugin.settings().showFactionNametags(viewer)&&factionText!=null);
            }
        }
    }
    private Tags ensure(Player target,boolean wantBalance,boolean wantFaction,Tags current){
        UUID balance=current==null?null:current.balance(),faction=current==null?null:current.faction();
        if(wantBalance&&resolve(balance)==null)balance=spawn(target,0.05).getUniqueId();
        if(wantFaction&&resolve(faction)==null)faction=spawn(target,-0.20).getUniqueId();
        Tags updated=new Tags(balance,faction);
        tags.put(target.getUniqueId(),updated);
        return updated;
    }
    /** Mounted as a passenger so it tracks the player automatically with no per-tick teleporting. */
    private TextDisplay spawn(Player target,double yOffset){
        Location at=target.getLocation();
        TextDisplay display=at.getWorld().spawn(at,TextDisplay.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,d->{
            d.setPersistent(false);d.setInvulnerable(true);d.setGravity(false);
            d.setBillboard(Display.Billboard.CENTER);d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);d.setSeeThrough(false);d.setViewRange(.6f);
            d.setBackgroundColor(Color.fromARGB(0,0,0,0));
            d.setTransformation(new Transformation(new Vector3f(0,(float)yOffset,0),new AxisAngle4f(),new Vector3f(.62f,.62f,.62f),new AxisAngle4f()));
            d.setVisibleByDefault(false);
        });
        target.addPassenger(display);
        return display;
    }
    private void apply(UUID id,String text,NamedTextColor color,Map<UUID,String> cache,UUID owner){
        TextDisplay display=resolve(id);
        if(display==null)return;
        if(text==null){display.remove();cache.remove(owner);return;}
        String key=id+"|"+text;
        if(key.equals(cache.get(owner)))return;
        cache.put(owner,key);
        display.text(Component.text(text,color));
    }
    private void show(Player viewer,UUID id,boolean visible){
        TextDisplay display=resolve(id);
        if(display==null)return;
        if(visible)viewer.showEntity(plugin,display);else viewer.hideEntity(plugin,display);
    }
    private TextDisplay resolve(UUID id){
        if(id==null)return null;
        Entity entity=plugin.getServer().getEntity(id);
        return entity instanceof TextDisplay display&&display.isValid()?display:null;
    }
    private void remove(Tags t){
        TextDisplay balance=resolve(t.balance()),faction=resolve(t.faction());
        if(balance!=null)balance.remove();
        if(faction!=null)faction.remove();
    }
    /** $950 / $12.5k / $1.2mil -- the same shape the bulletin uses, kept short so it never crowds the name. */
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
