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
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Optional extra lines around a player's nametag: their faction tag and their balance.
 *
 *  Both are rendered as our own TextDisplays mounted on the player. An earlier version used a scoreboard
 *  team PREFIX for the faction tag, because that is the only way to sit truly to the left of the vanilla
 *  name; it was reverted because a team prefix decorates the player's name EVERYWHERE the server renders
 *  it, so it leaked into death messages ("[BLK] MacoCT was slain by..."), and because TAB manages nametag
 *  teams itself and simply overwrote ours, so the tag never actually appeared above the head. Both are
 *  properties of the mechanism rather than tuning, so exact left-of-name placement is traded away for
 *  something self-contained that cannot leak into chat or be fought over by another plugin.
 *
 *  The vanilla nametag is never touched -- no renames, no team decoration -- so nicknames and TAB keep
 *  working exactly as before.
 *
 *  Ownership: a mounted display is EJECTED when its carrier dies, and the first version never re-mounted
 *  it, so it stayed floating at the death spot -- precisely where the grave then spawned. Every refresh
 *  now re-verifies the display is still riding its owner and re-mounts it otherwise, and death removes it
 *  outright so it rebuilds cleanly on respawn. A display is never positioned relative to anything but its
 *  owning player. */
final class NametagService implements Listener {
    private final SMPCore plugin;
    /** Owner id -> their balance display. One per player, shown/hidden per viewer. */
    private final Map<UUID,UUID> balanceDisplays=new HashMap<>();
    private final Map<UUID,String> lastBalanceText=new HashMap<>();
    private final Map<UUID,UUID> factionDisplays=new HashMap<>();
    private final Map<UUID,String> lastFactionText=new HashMap<>();
    private BukkitTask task;

    private BukkitTask followTask;
    NametagService(SMPCore plugin){
        this.plugin=plugin;
        long period=Math.max(20,plugin.getConfig().getLong("nametags.refresh-ticks",40));
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,period,period);
        /** Positioning runs on its own fast task. The displays are NOT mounted on the player: riding a
         *  player suppresses their whole vanilla nametag block (name and the below-name health line
         *  together), which is why both vanished. Following by teleport keeps the player's own tag
         *  completely untouched, which was the requirement all along. */
        long follow=Math.max(1,plugin.getConfig().getLong("nametags.follow-ticks",2));
        followTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::follow,follow,follow);
        plugin.getServer().getScheduler().runTaskLater(plugin,this::purgeLegacyPrefixTeams,40L);
    }
    private void follow(){
        double balanceOffset=plugin.getConfig().getDouble("nametags.balance-offset",2.35);
        double factionOffset=plugin.getConfig().getDouble("nametags.faction-offset",2.80);
        for(Player target:plugin.getServer().getOnlinePlayers()){
            reposition(balanceDisplays.get(target.getUniqueId()),target,balanceOffset);
            reposition(factionDisplays.get(target.getUniqueId()),target,factionOffset);
        }
    }
    private void reposition(UUID id,Player target,double offset){
        TextDisplay display=resolve(id);
        if(display==null)return;
        Location want=target.getLocation().clone().add(0,offset,0);
        want.setYaw(0);want.setPitch(0);
        if(!display.getWorld().equals(want.getWorld())||display.getLocation().distanceSquared(want)>0.0004)display.teleport(want);
    }
    void shutdown(){
        if(task!=null)task.cancel();if(followTask!=null)followTask.cancel();
        for(UUID id:balanceDisplays.values())removeDisplay(id);
        for(UUID id:factionDisplays.values())removeDisplay(id);
        balanceDisplays.clear();lastBalanceText.clear();factionDisplays.clear();lastFactionText.clear();
    }
    @EventHandler public void quit(PlayerQuitEvent event){clearFor(event.getPlayer().getUniqueId());}
    /** Removes any leftover "aft*" scoreboard teams from the reverted prefix experiment. Those teams live
     *  on a viewer's scoreboard for as long as their session lasts, so anyone who never reconnected after
     *  the revert would still see the prefix decorating names in chat. Cheap, idempotent, and a no-op once
     *  no stale teams remain. */
    void purgeLegacyPrefixTeams(){
        for(Player viewer:plugin.getServer().getOnlinePlayers()){
            try{
                for(org.bukkit.scoreboard.Team team:new java.util.ArrayList<>(viewer.getScoreboard().getTeams()))
                    if(team.getName().startsWith("aft"))team.unregister();
            }catch(Throwable ignored){}
        }
    }
    /** Death ejects passengers, so the display is dropped rather than left stranded; the next refresh
     *  rebuilds it on the respawned player. */
    @EventHandler public void death(PlayerDeathEvent event){
        UUID id=event.getEntity().getUniqueId();
        removeDisplay(balanceDisplays.remove(id));lastBalanceText.remove(id);
        removeDisplay(factionDisplays.remove(id));lastFactionText.remove(id);
    }
    private void clearFor(UUID id){
        removeDisplay(balanceDisplays.remove(id));lastBalanceText.remove(id);
        removeDisplay(factionDisplays.remove(id));lastFactionText.remove(id);
    }

    private void tick(){
        purgeLegacyPrefixTeams();
        boolean anyBalance=false,anyFaction=false;
        for(Player viewer:plugin.getServer().getOnlinePlayers()){
            if(plugin.settings().showBalanceNametags(viewer))anyBalance=true;
            if(plugin.settings().showFactionNametags(viewer))anyFaction=true;
        }
        for(Player target:plugin.getServer().getOnlinePlayers()){
            boolean eligible=!plugin.adminTools().isHiddenFromPublic(target);
            UUID id=target.getUniqueId();
            if(!eligible||!anyBalance){
                removeDisplay(balanceDisplays.remove(id));lastBalanceText.remove(id);
            }else{
                TextDisplay display=resolve(balanceDisplays.get(id));
                /** Re-verify ownership every pass: if it was ejected (death, dismount, teleport oddity) it is
                 *  re-mounted, so it can never drift onto a grave or any other entity. */
                if(display==null){
                    /** Clearing the cache matters: without it a display recreated after a toggle, death or
                     *  chunk reload matches the remembered text, skips the update, and renders blank. */
                    display=spawnBalance(target);balanceDisplays.put(id,display.getUniqueId());lastBalanceText.remove(id);
                }

                String text=compact(plugin.db().player(CoreUtil.id(target)).balance());
                if(!text.equals(lastBalanceText.get(id))){
                    lastBalanceText.put(id,text);
                    display.text(Component.text(text,NamedTextColor.GREEN));
                }
            }
            updateFactionLine(target,eligible&&anyFaction);
        }
        /** Per-viewer visibility for the balance line. */
        for(Player viewer:plugin.getServer().getOnlinePlayers()){
            boolean wants=plugin.settings().showBalanceNametags(viewer);
            boolean wantsFaction=plugin.settings().showFactionNametags(viewer);
            for(UUID displayId:balanceDisplays.values()){
                TextDisplay display=resolve(displayId);
                if(display==null)continue;
                if(wants)viewer.showEntity(plugin,display);else viewer.hideEntity(plugin,display);
            }
            for(UUID displayId:factionDisplays.values()){
                TextDisplay display=resolve(displayId);
                if(display==null)continue;
                if(wantsFaction)viewer.showEntity(plugin,display);else viewer.hideEntity(plugin,display);
            }
        }
    }
    /** Faction tag as its own line, sitting directly above the balance line.
     *
     *  This deliberately does NOT use a scoreboard team prefix any more. A prefix is the only way to place
     *  text truly to the left of the vanilla name, but it decorates the player's name EVERYWHERE the server
     *  renders it -- including death messages, which is why "[BLK] MacoCT was slain by..." started showing
     *  up in chat. It also loses to TAB, which manages nametag teams itself and simply overwrote ours, so
     *  the tag never appeared above the head at all. Both problems are inherent to the mechanism, not
     *  tuning, so the tag is rendered as our own display instead: it costs the exact left-of-name placement
     *  but it is self-contained, never leaks into chat, and cannot be fought over by another plugin. */
    private void updateFactionLine(Player target,boolean eligible){
        UUID id=target.getUniqueId();
        Database.FactionRow faction=eligible?plugin.db().factionOf(CoreUtil.id(target)):null;
        if(faction==null){removeDisplay(factionDisplays.remove(id));lastFactionText.remove(id);return;}
        TextDisplay display=resolve(factionDisplays.get(id));
        if(display==null){display=spawnLine(target,(float)plugin.getConfig().getDouble("nametags.faction-offset",2.35));factionDisplays.put(id,display.getUniqueId());lastFactionText.remove(id);}

        String text="["+faction.tag()+"]";
        if(text.equals(lastFactionText.get(id)))return;
        lastFactionText.put(id,text);
        display.text(Component.text(text,factionColor()));
        sideAlign(display,target.getName(),text);
    }
    /** Mounted on its owner and nothing else. Scale and offset are configurable because how tightly this
     *  sits under the vanilla name depends on whether the below-name health line is also enabled. */
    private TextDisplay spawnBalance(Player target){
        return spawnLine(target,(float)plugin.getConfig().getDouble("nametags.balance-offset",2.35));
    }
    private TextDisplay spawnLine(Player target,float offset){
        Location at=target.getLocation().clone().add(0,offset,0);
        float scale=(float)plugin.getConfig().getDouble("nametags.scale",1.15);
        TextDisplay display=at.getWorld().spawn(at,TextDisplay.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,d->{
            d.setPersistent(false);d.setInvulnerable(true);d.setGravity(false);
            d.setBillboard(Display.Billboard.CENTER);d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);d.setSeeThrough(false);
            d.setViewRange((float)plugin.getConfig().getDouble("nametags.view-range",1.0));
            d.setBackgroundColor(Color.fromARGB(0,0,0,0));
            d.setTransformation(new Transformation(new Vector3f(),new AxisAngle4f(),new Vector3f(scale,scale,scale),new AxisAngle4f()));
            d.setVisibleByDefault(false);
        });
        return display;
    }
    /** Configurable so the tag can be kept in step with whatever accent the rest of the UI settles on. */
    private NamedTextColor factionColor(){
        String name=plugin.getConfig().getString("nametags.faction-color","AQUA");
        NamedTextColor color=NamedTextColor.NAMES.value(name.toLowerCase(java.util.Locale.ROOT));
        return color==null?NamedTextColor.AQUA:color;
    }
    /** Places the faction tag to the RIGHT of the vanilla name on the same line.
     *
     *  The sideways shift is applied as the display's transformation TRANSLATION, not as a world-space
     *  position offset. That distinction is the whole trick: a Display's transformation is applied in its
     *  own local space and is carried along by CENTER billboarding, so +X stays on the viewer's right no
     *  matter which way they orbit the player. A world offset would swing around and end up on the wrong
     *  side. The distance is derived from the name and tag lengths so it clears names of any length rather
     *  than being a fixed gap tuned for one nickname. */
    private void sideAlign(TextDisplay display,String name,String tag){
        float scale=(float)plugin.getConfig().getDouble("nametags.scale",1.15);
        double perChar=plugin.getConfig().getDouble("nametags.char-width",.062)*scale;
        double gap=plugin.getConfig().getDouble("nametags.faction-gap",.14);
        double shift=(name.length()/2.0)*perChar+(tag.length()/2.0)*perChar+gap;
        display.setTransformation(new Transformation(new Vector3f((float)shift,0,0),new AxisAngle4f(),new Vector3f(scale,scale,scale),new AxisAngle4f()));
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
