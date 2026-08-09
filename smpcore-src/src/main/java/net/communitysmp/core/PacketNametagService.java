package net.communitysmp.core;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Packet-only nametag overlay: one mounted TextDisplay per player that REPLACES the vanilla nametag.
 *
 *  Mounting a passenger on a player suppresses that player's vanilla nametag client-side -- confirmed by
 *  elimination across a Bukkit-mounted version and two packet-mounted heights, so it is a property of the
 *  player being a vehicle rather than of occlusion or offset. Rather than fight it, this leans into it: if
 *  a viewer has either option enabled, the mounted display renders the COMPLETE nametag for them (name,
 *  health, plus whatever extras they asked for). A viewer with neither option enabled is sent nothing at
 *  all, so their client draws the ordinary vanilla nametag untouched.
 *
 *  Layout is composed from the three viewer toggles. Balance is on by default; faction and hearts are
 *  off. With EVERY option off nothing is sent at all, so that player keeps the untouched vanilla nametag
 *  (which already carries the below-name health line) -- adding the hearts option does not change that
 *  default. Examples:
 *      money only          ->  Name              / $Balance
 *      money + hearts      ->  Name              / 20 <3 / $Balance
 *      faction + hearts    ->  Name [TAG]        / 20 <3
 *      all three           ->  Name [TAG]        / 20 <3 / $Balance
 *      none                ->  no packet sent; vanilla nametag
 *
 *  Positioning is entirely the client's job: the display is a passenger of the real player entity, so
 *  vanilla moves it through walking, sprinting, jumping, falling, riding and teleporting. There is no
 *  movement mirroring and no position packet anywhere in this class.
 *
 *  The same fake entity id is reused for every viewer, which is safe because clients track entities
 *  independently -- so one id can legitimately carry different TEXT for different viewers, which is
 *  exactly how per-viewer content is achieved without per-viewer entities.
 *
 *  Nothing here touches scoreboard teams, chat, death messages, TAB or the real player name. */
final class PacketNametagService implements Listener {
    /** Display metadata indices, stable since 1.19.4. */
    private static final int IDX_TRANSLATION=11, IDX_SCALE=12, IDX_BILLBOARD=15, IDX_VIEW_RANGE=17,
            IDX_TEXT=23, IDX_LINE_WIDTH=24, IDX_BACKGROUND=25, IDX_STYLE=27;
    /** Text display style bitmask. Only the see-through bit is used; shadow and background are left alone
     *  so this changes occlusion only, not how the tag looks. */
    private static final byte STYLE_SEE_THROUGH=0x02, STYLE_OCCLUDED=0x00;
    private static final byte BILLBOARD_CENTER=3;
    /** Counted DOWN from a high value; real entity ids count up, so these cannot collide. */
    /** Appended to the change-detection key only; stripped before the text is drawn. */
    private static final String SNEAK_MARK="\u0000sneak";
    private static final AtomicInteger NEXT_ID=new AtomicInteger(Integer.MAX_VALUE-1_000_000);

    /** Everything the overlay renders. Compared as a whole so a packet is only sent when something the
     *  viewer can actually see has changed -- name/nickname, health, faction or balance. */
    private record Snapshot(String name,int health,String faction,String balance) {}

    private final SMPCore plugin;
    private final Map<UUID,Integer> entityIds=new HashMap<>();
    private final Map<UUID,Snapshot> lastSnapshot=new HashMap<>();
    /** viewer id -> (target id -> last text variant sent), so unchanged viewers get nothing. */
    private final Map<UUID,Map<UUID,String>> sent=new HashMap<>();
    private BukkitTask task;
    private final boolean active;

    PacketNametagService(SMPCore plugin){
        this.plugin=plugin;
        active=plugin.getServer().getPluginManager().getPlugin("packetevents")!=null;
        if(!active){plugin.getLogger().info("[Nametags] PacketEvents is not installed; overlays disabled.");return;}
        long period=Math.max(10,plugin.getConfig().getLong("nametags.refresh-ticks",20));
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::refresh,period,period);
    }
    void shutdown(){
        if(task!=null)task.cancel();
        if(!active)return;
        for(Player viewer:plugin.getServer().getOnlinePlayers())
            for(int id:entityIds.values())send(viewer,new WrapperPlayServerDestroyEntities(id));
        entityIds.clear();lastSnapshot.clear();sent.clear();
    }

    // ------------------------------------------------------------------ lifecycle
    @EventHandler public void join(PlayerJoinEvent event){if(active)later(20L);}
    @EventHandler public void quit(PlayerQuitEvent event){
        if(!active)return;
        UUID id=event.getPlayer().getUniqueId();
        Integer entity=entityIds.remove(id);
        if(entity!=null)for(Player viewer:plugin.getServer().getOnlinePlayers())send(viewer,new WrapperPlayServerDestroyEntities(entity));
        lastSnapshot.remove(id);sent.remove(id);
        for(Map<UUID,String> seen:sent.values())seen.remove(id);
    }
    /** Death, respawn, dimension change and long teleports all make the client drop entities it was
     *  tracking, so the overlay has to be re-spawned and re-mounted rather than assumed still attached. */
    @EventHandler public void death(PlayerDeathEvent event){forget(event.getEntity());}
    @EventHandler public void respawn(PlayerRespawnEvent event){forget(event.getPlayer());}
    @EventHandler public void changedWorld(PlayerChangedWorldEvent event){forget(event.getPlayer());}
    @EventHandler public void teleport(PlayerTeleportEvent event){
        if(event.getTo()==null||event.getFrom().getWorld()==null)return;
        if(!event.getFrom().getWorld().equals(event.getTo().getWorld())||event.getFrom().distanceSquared(event.getTo())>4096)forget(event.getPlayer());
    }
    /** Drops our record of this player's tag AND destroys it on every client first. Clearing tracking alone
     *  was not enough: the fake entity id is reused per player, so the next refresh re-spawned that same id
     *  while clients still held the old one, stranding a tag at the previous position. */
    private void forget(Player target){
        if(!active)return;
        UUID id=target.getUniqueId();
        Integer entity=entityIds.get(id);
        for(Map.Entry<UUID,Map<UUID,String>> entry:sent.entrySet()){
            if(entry.getValue().remove(id)==null||entity==null)continue;
            Player viewer=plugin.getServer().getPlayer(entry.getKey());
            if(viewer!=null){send(viewer,new WrapperPlayServerDestroyEntities(entity));
                send(viewer,new WrapperPlayServerSetPassengers(target.getEntityId(),new int[0]));}
        }
        lastSnapshot.remove(id);
        later(10L);
    }
    /** Crouching changes occlusion, so it re-sends instead of waiting for the next periodic tick. */
    @EventHandler public void sneak(org.bukkit.event.player.PlayerToggleSneakEvent event){if(active)later(1L);}
    void viewerSettingChanged(Player viewer){if(active)later(1L);}
    private void later(long ticks){plugin.getServer().getScheduler().runTaskLater(plugin,this::refresh,ticks);}

    // ------------------------------------------------------------------ core
    private void refresh(){
        if(!active)return;
        for(Player target:plugin.getServer().getOnlinePlayers()){
            UUID id=target.getUniqueId();
            /** Vanished/hidden players get no overlay at all, so they simply keep whatever visibility rules
             *  already apply to them -- this must never become a way to spot someone who is hidden. */
            boolean eligible=!plugin.adminTools().isHiddenFromPublic(target);
            Snapshot snapshot=eligible?snapshot(target):null;
            if(snapshot!=null)lastSnapshot.put(id,snapshot);else lastSnapshot.remove(id);
            for(Player viewer:plugin.getServer().getOnlinePlayers()){
                Map<UUID,String> seen=sent.computeIfAbsent(viewer.getUniqueId(),k->new HashMap<>());
                /** A viewer must never be sent their own tag. The display is a passenger of the player, so
                 *  in first person it renders directly in front of the camera. Vanilla never shows you your
                 *  own nametag either. */
                boolean self=viewer.getUniqueId().equals(id);
                boolean money=plugin.settings().showBalanceNametags(viewer);
                boolean faction=plugin.settings().showFactionNametags(viewer);
                boolean hearts=plugin.settings().showHeartNametags(viewer);
                /** Beyond entity-tracking range the client holds no player entity to mount onto, so a tag
                 *  sent now ends up stranded wherever that client last saw them -- the displaced tags. Tear
                 *  down past the outer bound and re-spawn inside the inner one; the gap between the two
                 *  keeps someone walking the boundary from flapping spawn/destroy every cycle. */
                boolean tracked=viewer.getWorld().equals(target.getWorld())&&viewer.canSee(target)
                        &&viewer.getLocation().distanceSquared(target.getLocation())
                          <(seen.containsKey(id)?outerRangeSq():innerRangeSq());
                if(self||snapshot==null||!tracked||(!money&&!faction&&!hearts)){
                    if(seen.remove(id)!=null){
                        Integer entity=entityIds.get(id);
                        if(entity!=null){send(viewer,new WrapperPlayServerDestroyEntities(entity));
                            send(viewer,new WrapperPlayServerSetPassengers(target.getEntityId(),new int[0]));}
                    }
                    continue;
                }
                /** Sneaking is part of the change key so crouching re-sends at once, rather than waiting for
                 *  the balance or health to happen to change. */
                boolean sneaking=target.isSneaking();
                String variant=render(snapshot,money,faction,hearts)+(sneaking?SNEAK_MARK:"");
                String previous=seen.get(id);
                if(variant.equals(previous))continue;
                int entity=entityIds.computeIfAbsent(id,k->NEXT_ID.getAndDecrement());
                if(previous==null)spawn(viewer,target,entity,variant,sneaking);
                else send(viewer,new WrapperPlayServerEntityMetadata(entity,List.of(text(variant),style(sneaking),viewRange(sneaking))));
                seen.put(id,variant);
            }
        }
    }
    private Snapshot snapshot(Player target){
        Database.FactionRow faction=plugin.db().factionOf(CoreUtil.id(target));
        return new Snapshot(plugin.nicknames().displayName(target),
                (int)Math.max(0,Math.round(target.getHealth())),
                faction==null?"":faction.tag(),
                compact(plugin.db().player(CoreUtil.id(target)).balance()));
    }
    /** The rendered nametag as a single string, using \n for line breaks. Doubling as the change-detection
     *  key means a packet is sent exactly when the visible result differs, with no separate bookkeeping. */
    private String render(Snapshot s,boolean money,boolean faction,boolean hearts){
        StringBuilder out=new StringBuilder(s.name());
        if(faction&&!s.faction().isEmpty())out.append(" [").append(s.faction()).append(']');
        /** Hearts are drawn only when that viewer asked for them. With every option off no packet is sent
         *  at all, so the player keeps the ordinary vanilla nametag, which already carries the below-name
         *  health line -- that default is unchanged by this option existing. */
        if(hearts)out.append('\n').append(s.health()).append(" ❤");
        if(money)out.append('\n').append(s.balance());
        return out.toString();
    }
    /** Rebuilds the coloured component from the rendered string: name white, faction tag in the configured
     *  colour, health white with a red heart, and the balance as a dark green sign plus a white value --
     *  matching the vanilla nametag and below-name health line as closely as possible. */
    private EntityData<?> text(String variant){
        String[] lines=variant.replace(SNEAK_MARK,"").split("\n",-1);
        Component result=Component.empty();
        for(int i=0;i<lines.length;i++){
            if(i>0)result=result.append(Component.newline());
            String line=lines[i];
            if(i==0){
                int bracket=line.indexOf(" [");
                if(bracket>=0)result=result.append(Component.text(line.substring(0,bracket),NamedTextColor.WHITE))
                        .append(Component.text(" "+line.substring(bracket+1),factionColor()));
                else result=result.append(Component.text(line,NamedTextColor.WHITE));
            }else if(line.endsWith("❤")){
                result=result.append(Component.text(line.substring(0,line.length()-1),NamedTextColor.WHITE))
                        .append(Component.text("❤",NamedTextColor.RED));
            }else if(line.startsWith("$")){
                result=result.append(Component.text("$",NamedTextColor.DARK_GREEN))
                        .append(Component.text(line.substring(1),NamedTextColor.WHITE));
            }else result=result.append(Component.text(line,NamedTextColor.WHITE));
        }
        return new EntityData<>(IDX_TEXT,EntityDataTypes.ADV_COMPONENT,result);
    }
    /** Vanilla draws a standing player's name through walls and stops once they crouch, which is what makes
     *  crouching read as hidden and standing as exposed. Mirroring that is the point: the replacement tag
     *  previously ignored occlusion entirely and stayed visible through terrain. */
    private EntityData<?> style(boolean sneaking){
        return new EntityData<>(IDX_STYLE,EntityDataTypes.BYTE,sneaking?STYLE_OCCLUDED:STYLE_SEE_THROUGH);
    }
    /** Crouching also shortens how far the name carries, again as vanilla does. */
    private EntityData<?> viewRange(boolean sneaking){
        float range=(float)plugin.getConfig().getDouble("nametags.view-range",1.0);
        return new EntityData<>(IDX_VIEW_RANGE,EntityDataTypes.FLOAT,
                sneaking?range*(float)plugin.getConfig().getDouble("nametags.sneak-view-range-factor",0.5):range);
    }
    private double innerRangeSq(){double d=plugin.getConfig().getDouble("nametags.max-distance",48);return d*d;}
    private double outerRangeSq(){double d=plugin.getConfig().getDouble("nametags.max-distance",48)+16;return d*d;}
    private void spawn(Player viewer,Player target,int entityId,String variant,boolean sneaking){
        Vector3d at=new Vector3d(target.getLocation().getX(),target.getLocation().getY(),target.getLocation().getZ());
        send(viewer,new WrapperPlayServerSpawnEntity(entityId,Optional.of(UUID.randomUUID()),EntityTypes.TEXT_DISPLAY,
                at,0f,0f,0f,0,Optional.empty()));
        float scale=(float)plugin.getConfig().getDouble("nametags.scale",1.0);
        float lift=(float)plugin.getConfig().getDouble("nametags.lift",.30);
        List<EntityData<?>> data=new ArrayList<>();
        data.add(new EntityData<>(IDX_TRANSLATION,EntityDataTypes.VECTOR3F,new Vector3f(0,lift,0)));
        data.add(new EntityData<>(IDX_SCALE,EntityDataTypes.VECTOR3F,new Vector3f(scale,scale,scale)));
        data.add(new EntityData<>(IDX_BILLBOARD,EntityDataTypes.BYTE,BILLBOARD_CENTER));
        data.add(viewRange(sneaking));
        data.add(style(sneaking));
        data.add(new EntityData<>(IDX_LINE_WIDTH,EntityDataTypes.INT,200));
        data.add(new EntityData<>(IDX_BACKGROUND,EntityDataTypes.INT,plugin.getConfig().getInt("nametags.background-argb",1073741824)));
        data.add(text(variant));
        send(viewer,new WrapperPlayServerEntityMetadata(entityId,data));
        /** Attachment: the client is told this is a passenger of the real player, so IT owns positioning. */
        send(viewer,new WrapperPlayServerSetPassengers(target.getEntityId(),new int[]{entityId}));
    }
    private void send(Player viewer,com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet){
        try{PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,packet);}
        catch(Throwable error){plugin.getLogger().warning("[Nametags] packet send failed: "+error);}
    }
    private NamedTextColor factionColor(){
        NamedTextColor color=NamedTextColor.NAMES.value(plugin.getConfig().getString("nametags.faction-color","AQUA").toLowerCase(java.util.Locale.ROOT));
        return color==null?NamedTextColor.AQUA:color;
    }
    private String compact(double amount){
        double abs=Math.abs(amount);
        if(abs<1000)return "$"+new DecimalFormat("0").format(amount);
        double scaled;String suffix;
        if(abs>=1_000_000_000){scaled=amount/1_000_000_000;suffix="b";}
        else if(abs>=1_000_000){scaled=amount/1_000_000;suffix="m";}
        else{scaled=amount/1000;suffix="k";}
        return "$"+new DecimalFormat(Math.abs(scaled)>=100?"0":"0.#").format(scaled)+suffix;
    }
}
