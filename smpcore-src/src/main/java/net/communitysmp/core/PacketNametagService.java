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

/** Packet-only nametag overlays: an optional faction tag and compact balance line above each player.
 *
 *  Why packets rather than real entities. The requirement is per-VIEWER choice over a per-TARGET visual,
 *  and those pull in opposite directions with server-side entities: a real entity exists for everyone (so
 *  visibility has to be bolted on afterwards), and previous attempts either fought the client for position
 *  or suppressed the vanilla nametag by riding the player. A fake entity sent only down the sockets of
 *  viewers who asked for it is exactly the right shape -- viewers who disabled the toggle never learn it
 *  exists, and there is no server-side entity to leak, drift, or be left behind.
 *
 *  Positioning is the client's job, deliberately. Each overlay is attached with SetPassengers on the real
 *  player entity, so the vanilla client moves it with the player through walking, sprinting, jumping,
 *  falling, riding and teleporting. There is NO teleport-chasing and no per-tick position update anywhere
 *  in this class -- the only periodic work is re-reading balances, and even that only sends a packet when
 *  the displayed text actually changed.
 *
 *  Two overlays on one vehicle would occupy the same mount point, so they are separated by the Display
 *  translation transform (client-side, part of the entity's own metadata) rather than by position.
 *
 *  Nothing here touches the player's real nametag, scoreboard teams, chat, death messages, TAB or
 *  nicknames -- it only adds extra client-side entities. */
final class PacketNametagService implements Listener {
    /** Display metadata indices, stable since 1.19.4. Named rather than inlined so a future protocol change
     *  is a one-line fix instead of a hunt through magic numbers. */
    private static final int IDX_TRANSLATION=11, IDX_SCALE=12, IDX_BILLBOARD=15, IDX_VIEW_RANGE=17,
            IDX_TEXT=23, IDX_LINE_WIDTH=24, IDX_BACKGROUND=25;
    private static final byte BILLBOARD_CENTER=3;
    /** Fake entity ids counted DOWN from a very high value: real server entity ids count up from zero, so
     *  this cannot collide with a genuine entity for the lifetime of a server. */
    private static final AtomicInteger NEXT_ID=new AtomicInteger(Integer.MAX_VALUE-1_000_000);

    private record Overlay(int balanceId,int factionId) {}
    private final SMPCore plugin;
    /** target id -> its two fake entity ids (allocated once, reused for every viewer). */
    private final Map<UUID,Overlay> overlays=new HashMap<>();
    /** target id -> last text actually sent, so unchanged values cost nothing. */
    private final Map<UUID,String> lastBalance=new HashMap<>(), lastFaction=new HashMap<>();
    /** viewer id -> target ids that viewer currently has spawned, so we only send deltas. */
    private final Map<UUID,Map<UUID,Boolean>> shownBalance=new HashMap<>(), shownFaction=new HashMap<>();
    private BukkitTask task;
    private final boolean active;

    PacketNametagService(SMPCore plugin){
        this.plugin=plugin;
        boolean ok=plugin.getServer().getPluginManager().getPlugin("packetevents")!=null;
        this.active=ok;
        if(!ok){plugin.getLogger().info("[Nametags] PacketEvents is not installed; balance/faction overlays are disabled.");return;}
        long period=Math.max(20,plugin.getConfig().getLong("nametags.refresh-ticks",40));
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::refresh,period,period);
    }
    void shutdown(){
        if(task!=null)task.cancel();
        if(!active)return;
        for(Player viewer:plugin.getServer().getOnlinePlayers())
            for(Overlay overlay:overlays.values())destroy(viewer,overlay);
        overlays.clear();lastBalance.clear();lastFaction.clear();shownBalance.clear();shownFaction.clear();
    }

    // ---------------------------------------------------------------- lifecycle
    @EventHandler public void join(PlayerJoinEvent event){if(active)plugin.getServer().getScheduler().runTaskLater(plugin,this::refresh,20L);}
    @EventHandler public void quit(PlayerQuitEvent event){
        if(!active)return;
        UUID id=event.getPlayer().getUniqueId();
        Overlay overlay=overlays.remove(id);
        if(overlay!=null)for(Player viewer:plugin.getServer().getOnlinePlayers())destroy(viewer,overlay);
        lastBalance.remove(id);lastFaction.remove(id);
        shownBalance.remove(id);shownFaction.remove(id);
        for(Map<UUID,Boolean> seen:shownBalance.values())seen.remove(id);
        for(Map<UUID,Boolean> seen:shownFaction.values())seen.remove(id);
    }
    /** Death, respawn, teleport and dimension change all make the client forget entities it was tracking,
     *  so the overlay must be re-sent rather than assumed still attached. Forgetting our own bookkeeping
     *  first is what makes the next refresh treat it as new and re-spawn + re-mount it. */
    @EventHandler public void death(PlayerDeathEvent event){forget(event.getEntity());}
    @EventHandler public void respawn(PlayerRespawnEvent event){forget(event.getPlayer());}
    @EventHandler public void changedWorld(PlayerChangedWorldEvent event){forget(event.getPlayer());}
    @EventHandler public void teleport(PlayerTeleportEvent event){
        /** Only a cross-world or long-range teleport drops client-side tracking; ordinary short hops keep
         *  the passenger attachment, so re-sending on every teleport would be needless packet churn. */
        if(event.getTo()==null||event.getFrom().getWorld()==null)return;
        if(!event.getFrom().getWorld().equals(event.getTo().getWorld())||event.getFrom().distanceSquared(event.getTo())>4096)forget(event.getPlayer());
    }
    private void forget(Player target){
        if(!active)return;
        UUID id=target.getUniqueId();
        for(Map<UUID,Boolean> seen:shownBalance.values())seen.remove(id);
        for(Map<UUID,Boolean> seen:shownFaction.values())seen.remove(id);
        plugin.getServer().getScheduler().runTaskLater(plugin,this::refresh,10L);
    }
    /** Called when a viewer flips a toggle, so the change is immediate rather than waiting for the timer. */
    void viewerSettingChanged(Player viewer){if(active)plugin.getServer().getScheduler().runTask(plugin,this::refresh);}

    // ---------------------------------------------------------------- core
    private void refresh(){
        if(!active)return;
        for(Player target:plugin.getServer().getOnlinePlayers()){
            UUID id=target.getUniqueId();
            boolean eligible=!plugin.adminTools().isHiddenFromPublic(target);
            String balance=eligible?compact(plugin.db().player(CoreUtil.id(target)).balance()):null;
            Database.FactionRow faction=eligible?plugin.db().factionOf(CoreUtil.id(target)):null;
            String factionText=faction==null?null:"["+faction.tag()+"]";
            Overlay overlay=overlays.computeIfAbsent(id,k->new Overlay(NEXT_ID.getAndDecrement(),NEXT_ID.getAndDecrement()));
            boolean balanceChanged=balance!=null&&!balance.equals(lastBalance.get(id));
            boolean factionChanged=factionText!=null&&!factionText.equals(lastFaction.get(id));
            if(balance!=null)lastBalance.put(id,balance);else lastBalance.remove(id);
            if(factionText!=null)lastFaction.put(id,factionText);else lastFaction.remove(id);

            for(Player viewer:plugin.getServer().getOnlinePlayers()){
                boolean wantBalance=balance!=null&&plugin.settings().showBalanceNametags(viewer);
                boolean wantFaction=factionText!=null&&plugin.settings().showFactionNametags(viewer);
                sync(viewer,target,overlay,true,wantBalance,balance,balanceChanged,shownBalance);
                sync(viewer,target,overlay,false,wantFaction,factionText,factionChanged,shownFaction);
            }
        }
    }
    /** Spawns, updates or removes ONE line for ONE viewer, sending only what actually changed. */
    private void sync(Player viewer,Player target,Overlay overlay,boolean isBalance,boolean want,String text,
                      boolean textChanged,Map<UUID,Map<UUID,Boolean>> state){
        Map<UUID,Boolean> seen=state.computeIfAbsent(viewer.getUniqueId(),k->new HashMap<>());
        boolean has=Boolean.TRUE.equals(seen.get(target.getUniqueId()));
        int entityId=isBalance?overlay.balanceId():overlay.factionId();
        if(!want){
            if(has){send(viewer,new WrapperPlayServerDestroyEntities(entityId));seen.remove(target.getUniqueId());
                /** Re-send the passenger list so the client stops reserving a slot for the removed line. */
                remount(viewer,target,overlay,state==shownBalance?false:isShown(shownBalance,viewer,target),
                        state==shownFaction?false:isShown(shownFaction,viewer,target));}
            return;
        }
        if(!has){
            spawn(viewer,target,entityId,isBalance,text);
            seen.put(target.getUniqueId(),true);
            remount(viewer,target,overlay,isShown(shownBalance,viewer,target),isShown(shownFaction,viewer,target));
        }else if(textChanged)send(viewer,new WrapperPlayServerEntityMetadata(entityId,List.of(textData(text,isBalance))));
    }
    private boolean isShown(Map<UUID,Map<UUID,Boolean>> state,Player viewer,Player target){
        Map<UUID,Boolean> seen=state.get(viewer.getUniqueId());
        return seen!=null&&Boolean.TRUE.equals(seen.get(target.getUniqueId()));
    }
    private void spawn(Player viewer,Player target,int entityId,boolean isBalance,String text){
        Vector3d at=new Vector3d(target.getLocation().getX(),target.getLocation().getY(),target.getLocation().getZ());
        send(viewer,new WrapperPlayServerSpawnEntity(entityId,Optional.of(UUID.randomUUID()),EntityTypes.TEXT_DISPLAY,
                at,0f,0f,0f,0,Optional.empty()));
        float scale=(float)plugin.getConfig().getDouble("nametags.scale",1.0);
        /** Vertical separation between the two lines is done with the Display's own translation transform,
         *  because both ride the same mount point and would otherwise render on top of each other. */
        /** Lifted clear of the vanilla nametag rather than sitting on it. Both mounted implementations lost
         *  the name and health line, and the overlays rendering directly over that block is the most likely
         *  cause, so they are pushed above it by default. */
        float lift=(float)plugin.getConfig().getDouble(isBalance?"nametags.balance-lift":"nametags.faction-lift",isBalance?.75:1.05);
        List<EntityData<?>> data=new ArrayList<>();
        data.add(new EntityData<>(IDX_TRANSLATION,EntityDataTypes.VECTOR3F,new Vector3f(0,lift,0)));
        data.add(new EntityData<>(IDX_SCALE,EntityDataTypes.VECTOR3F,new Vector3f(scale,scale,scale)));
        data.add(new EntityData<>(IDX_BILLBOARD,EntityDataTypes.BYTE,BILLBOARD_CENTER));
        data.add(new EntityData<>(IDX_VIEW_RANGE,EntityDataTypes.FLOAT,(float)plugin.getConfig().getDouble("nametags.view-range",1.0)));
        data.add(new EntityData<>(IDX_LINE_WIDTH,EntityDataTypes.INT,200));
        data.add(new EntityData<>(IDX_BACKGROUND,EntityDataTypes.INT,plugin.getConfig().getInt("nametags.background-argb",0)));
        data.add(textData(text,isBalance));
        send(viewer,new WrapperPlayServerEntityMetadata(entityId,data));
    }
    /** Money renders as a dark green currency sign followed by a white value; the faction tag is a single
     *  colour. Built as a component rather than a coloured string so the two halves are genuinely separate
     *  styles rather than legacy colour codes embedded in text. */
    private EntityData<?> textData(String text,boolean isBalance){
        Component component;
        if(isBalance&&text.startsWith("$"))
            component=Component.text("$",NamedTextColor.DARK_GREEN).append(Component.text(text.substring(1),NamedTextColor.WHITE));
        else component=Component.text(text,isBalance?NamedTextColor.WHITE:factionColor());
        return new EntityData<>(IDX_TEXT,EntityDataTypes.ADV_COMPONENT,component);
    }
    /** Attachment: the client is told our fake entities are passengers of the real player, so IT handles
     *  every position update from then on. This is the whole reason there is no positioning code here. */
    private void remount(Player viewer,Player target,Overlay overlay,boolean balanceShown,boolean factionShown){
        List<Integer> riders=new ArrayList<>();
        if(balanceShown)riders.add(overlay.balanceId());
        if(factionShown)riders.add(overlay.factionId());
        int[] ids=new int[riders.size()];
        for(int i=0;i<ids.length;i++)ids[i]=riders.get(i);
        send(viewer,new WrapperPlayServerSetPassengers(target.getEntityId(),ids));
    }
    private void destroy(Player viewer,Overlay overlay){
        send(viewer,new WrapperPlayServerDestroyEntities(overlay.balanceId(),overlay.factionId()));
    }
    private void send(Player viewer,com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet){
        try{PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,packet);}
        catch(Throwable error){plugin.getLogger().warning("[Nametags] packet send failed: "+error);}
    }
    private NamedTextColor factionColor(){
        NamedTextColor color=NamedTextColor.NAMES.value(plugin.getConfig().getString("nametags.faction-color","AQUA").toLowerCase(java.util.Locale.ROOT));
        return color==null?NamedTextColor.AQUA:color;
    }
    /** $950 / $12.5k / $1.2mil. */
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
