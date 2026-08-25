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
import org.bukkit.Location;
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
            IDX_TEXT=23, IDX_LINE_WIDTH=24, IDX_BACKGROUND=25, IDX_OPACITY=26, IDX_STYLE=27;
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
    /** Two fake displays per player, mirroring how vanilla actually draws a nametag.
     *
     *  Vanilla does not draw the name once. For a standing player it draws it TWICE: a dim pass in
     *  see-through mode (alpha 32) that shows through terrain, then a full-brightness pass in normal mode
     *  that only survives the depth test where the player is genuinely visible. That is why a real nametag
     *  is bright in the open and faint through a wall. A single display can be one or the other, never
     *  both -- see-through made ours a bright wall-hack marker, and occluded made it vanish entirely.
     *
     *  So: DIM is see-through and faint, SOLID is occluded and full strength. Both are mounted on the
     *  player. Crouching does not despawn anything, it only re-sends opacity -- DIM drops to zero (nothing
     *  through walls) and SOLID drops to the crouch alpha -- so a crouch can never leave a stale copy. */
    private final Map<UUID,int[]> entityIds=new HashMap<>();
    private int[] idsFor(UUID id){return entityIds.computeIfAbsent(id,k->new int[]{NEXT_ID.getAndDecrement(),NEXT_ID.getAndDecrement()});}
    private final Map<UUID,Snapshot> lastSnapshot=new HashMap<>();
    /** viewer id -> (target id -> last text variant sent), so unchanged viewers get nothing. */
    private final Map<UUID,Map<UUID,String>> sent=new HashMap<>();
    private BukkitTask task,resyncTask;
    private final boolean active;

    PacketNametagService(SMPCore plugin){
        this.plugin=plugin;
        active=plugin.getServer().getPluginManager().getPlugin("packetevents")!=null;
        if(!active){plugin.getLogger().info("[Nametags] PacketEvents is not installed; overlays disabled.");return;}
        long period=Math.max(10,plugin.getConfig().getLong("nametags.refresh-ticks",20));
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::refresh,period,period);
        long resync=Math.max(40,plugin.getConfig().getLong("nametags.resync-ticks",100));
        resyncTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::resyncSweep,resync,resync);
    }
    void shutdown(){
        if(task!=null)task.cancel();
        if(resyncTask!=null)resyncTask.cancel();
        if(!active)return;
        for(Player viewer:plugin.getServer().getOnlinePlayers())
            for(int[] pair:entityIds.values())send(viewer,new WrapperPlayServerDestroyEntities(pair[0],pair[1]));
        entityIds.clear();lastSnapshot.clear();sent.clear();
    }

    // ------------------------------------------------------------------ lifecycle
    @EventHandler public void join(PlayerJoinEvent event){if(active)later(20L);}
    @EventHandler public void quit(PlayerQuitEvent event){
        if(!active)return;
        UUID id=event.getPlayer().getUniqueId();
        int[] pair=entityIds.remove(id);
        if(pair!=null)for(Player viewer:plugin.getServer().getOnlinePlayers())send(viewer,new WrapperPlayServerDestroyEntities(pair[0],pair[1]));
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
        int[] pair=entityIds.get(id);
        for(Map.Entry<UUID,Map<UUID,String>> entry:sent.entrySet()){
            if(entry.getValue().remove(id)==null||pair==null)continue;
            Player viewer=plugin.getServer().getPlayer(entry.getKey());
            if(viewer!=null){send(viewer,new WrapperPlayServerDestroyEntities(pair[0],pair[1]));
                send(viewer,new WrapperPlayServerSetPassengers(target.getEntityId(),new int[0]));}
        }
        lastSnapshot.remove(id);
        later(10L);
    }
    /** The client has just started tracking someone: anything we believed we had sent them is gone, so the
     *  record is cleared and the next refresh re-establishes it from scratch. Without this the tag is only
     *  restored by luck, whenever the rendered text next happens to change. */
    @EventHandler public void track(io.papermc.paper.event.player.PlayerTrackEntityEvent event){
        if(!active||!(event.getEntity() instanceof Player target))return;
        Map<UUID,String> seen=sent.get(event.getPlayer().getUniqueId());
        if(seen!=null)seen.remove(target.getUniqueId());
        later(2L);
    }
    /** Stopped tracking: the client has dropped both displays along with the player, so drop our record to
     *  match. Leaving it would make the next re-track look like "already sent". */
    @EventHandler public void untrack(io.papermc.paper.event.player.PlayerUntrackEntityEvent event){
        if(!active||!(event.getEntity() instanceof Player target))return;
        Map<UUID,String> seen=sent.get(event.getPlayer().getUniqueId());
        if(seen!=null)seen.remove(target.getUniqueId());
    }
    /** Cheap belt-and-braces sweep. The event handlers above should make this redundant, but a single
     *  dropped or mis-ordered packet would otherwise leave one specific pairing broken indefinitely, and
     *  that is precisely the symptom being fixed. Rebuilding one viewer's tags per pass keeps the cost
     *  flat regardless of player count, and a full cycle completes in (players * interval). */
    private int resyncCursor;
    private void resyncSweep(){
        if(!active)return;
        List<Player> online=new ArrayList<>(plugin.getServer().getOnlinePlayers());
        if(online.isEmpty())return;
        Player viewer=online.get(Math.floorMod(resyncCursor++,online.size()));
        Map<UUID,String> seen=sent.get(viewer.getUniqueId());
        if(seen==null||seen.isEmpty())return;
        /** Re-assert the attachment; never tear it down.
         *
         *  This used to destroy both displays, unmount them and re-spawn two ticks later, which is a
         *  deliberate ~100ms hole in which the tag is simply gone -- and because a fresh spawn lands at the
         *  spawn coordinate before the mount is applied, it came back via the ground. That is the brief
         *  drop: the self-heal itself was causing it, once every resync interval per viewer.
         *
         *  SetPassengers is idempotent, so re-sending it costs one packet and is invisible when nothing is
         *  wrong, while still repairing the case this sweep exists for -- a mount the client lost, which is
         *  what strands a tag in place. The other failure it used to cover, the client not having the
         *  entity at all, is handled properly by the track/untrack events, which know when that happens
         *  instead of guessing. */
        for(UUID targetId:new ArrayList<>(seen.keySet())){
            int[] pair=entityIds.get(targetId);
            Player target=plugin.getServer().getPlayer(targetId);
            if(pair!=null&&target!=null&&target.isTrackedBy(viewer))
                send(viewer,new WrapperPlayServerSetPassengers(target.getEntityId(),new int[]{pair[0],pair[1]}));
        }
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
                /** Whether the CLIENT actually holds the player entity, not merely whether it is nearby.
                 *  Distance was a guess at the server's tracking range, and when the two disagreed we kept
                 *  a `seen` entry for a target the client had already dropped -- so nothing was ever
                 *  re-sent and that one player's tag stayed missing until something unrelated changed the
                 *  text. That is exactly the "couldn't see MacoCT until we both /spawn" case: /spawn forced
                 *  a re-track, which is the only thing that fixed it. Paper tells us directly. */
                /** A dead player is never tagged.
                 *
                 *  death() destroys the tag entities, but the rescan it schedules ten ticks later would
                 *  happily rebuild them: the corpse is still tracked by nearby clients through the death
                 *  animation, so `tracked` was still true and the tag was re-sent onto a body. It then sat
                 *  at the death spot until something forced a re-track -- which is why it only cleared when
                 *  the owner walked back into view. Excluding dead targets stops it being recreated at all,
                 *  and costs nothing else: a living player is never isDead(). */
                boolean tracked=!target.isDead()&&viewer.getWorld().equals(target.getWorld())&&viewer.canSee(target)
                        &&target.isTrackedBy(viewer)
                        &&viewer.getLocation().distanceSquared(target.getLocation())
                          <(seen.containsKey(id)?outerRangeSq():innerRangeSq());
                if(self||snapshot==null||!tracked||(!money&&!faction&&!hearts)){
                    if(seen.remove(id)!=null){
                        int[] pair=entityIds.get(id);
                        if(pair!=null){send(viewer,new WrapperPlayServerDestroyEntities(pair[0],pair[1]));
                            send(viewer,new WrapperPlayServerSetPassengers(target.getEntityId(),new int[0]));}
                    }
                    continue;
                }
                /** Sneaking is part of the change key so crouching re-sends at once, rather than waiting for
                 *  the balance or health to happen to change. */
                /** An invisible player (potion or otherwise) is treated like a crouching one: the overlay stays
                 *  mounted -- which suppresses the vanilla nametag -- but renders at zero opacity, so no name
                 *  floats over an invisible player. Standard behaviour, and the same DIM path crouch uses. */
                boolean sneaking=target.isSneaking()||target.isInvisible();
                String variant=render(snapshot,money,faction,hearts)+(sneaking?SNEAK_MARK:"");
                String previous=seen.get(id);
                if(variant.equals(previous))continue;
                int[] pair=idsFor(id);
                if(previous==null)spawn(viewer,target,pair,variant,sneaking);
                else for(int pass=0;pass<2;pass++)
                    send(viewer,new WrapperPlayServerEntityMetadata(pair[pass],
                            List.of(text(variant),style(pass==0),viewRange(sneaking),opacity(pass==0,sneaking))));
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
    /** The DIM pass is the see-through one; SOLID is always depth-tested. Which pass a display is never
     *  changes -- only its opacity does -- so crouching cannot produce a spawn/destroy race. */
    private EntityData<?> style(boolean dimPass){
        return new EntityData<>(IDX_STYLE,EntityDataTypes.BYTE,dimPass?STYLE_SEE_THROUGH:STYLE_OCCLUDED);
    }
    /** Alpha per pass, reproducing vanilla's two-pass result.
     *
     *  Standing: DIM shows faintly through terrain, SOLID reads at full strength wherever the player is
     *  actually visible. Crouching: DIM goes to zero so nothing shows through walls at all, and SOLID drops
     *  to the crouch alpha, so a crouched player's tag is present but visibly dimmed -- which is the part
     *  that was missing before, when crouching only toggled occlusion and left the colour untouched. */
    private EntityData<?> opacity(boolean dimPass,boolean sneaking){
        int alpha=dimPass
                ?(sneaking?0:plugin.getConfig().getInt("nametags.obstructed-alpha",40))
                :(sneaking?plugin.getConfig().getInt("nametags.crouch-alpha",115):255);
        return new EntityData<>(IDX_OPACITY,EntityDataTypes.BYTE,(byte)Math.max(0,Math.min(255,alpha)));
    }
    /** Crouching also shortens how far the name carries, again as vanilla does. */
    private EntityData<?> viewRange(boolean sneaking){
        float range=(float)plugin.getConfig().getDouble("nametags.view-range",1.0);
        return new EntityData<>(IDX_VIEW_RANGE,EntityDataTypes.FLOAT,
                sneaking?range*(float)plugin.getConfig().getDouble("nametags.sneak-view-range-factor",0.5):range);
    }
    private double innerRangeSq(){double d=plugin.getConfig().getDouble("nametags.max-distance",48);return d*d;}
    private double outerRangeSq(){double d=plugin.getConfig().getDouble("nametags.max-distance",48)+16;return d*d;}
    private void spawn(Player viewer,Player target,int[] pair,String variant,boolean sneaking){
        /** Spawned at head height rather than at the feet. The coordinate in the spawn packet only applies
         *  until SetPassengers arrives and the client takes over positioning, but for that moment the
         *  display renders exactly where it was spawned -- and target.getLocation() is the FEET, so any
         *  rebuild flickered through a tag lying on the ground. Head height makes that instant
         *  indistinguishable from the mounted position, so even a dropped or delayed mount packet cannot
         *  produce a visible drop. */
        Location origin=target.getEyeLocation();
        Vector3d at=new Vector3d(origin.getX(),origin.getY(),origin.getZ());
        float scale=(float)plugin.getConfig().getDouble("nametags.scale",1.0);
        float lift=(float)plugin.getConfig().getDouble("nametags.lift",.30);
        for(int pass=0;pass<2;pass++){
            boolean dimPass=pass==0;
            send(viewer,new WrapperPlayServerSpawnEntity(pair[pass],Optional.of(UUID.randomUUID()),EntityTypes.TEXT_DISPLAY,
                    at,0f,0f,0f,0,Optional.empty()));
            List<EntityData<?>> data=new ArrayList<>();
            data.add(new EntityData<>(IDX_TRANSLATION,EntityDataTypes.VECTOR3F,new Vector3f(0,lift,0)));
            data.add(new EntityData<>(IDX_SCALE,EntityDataTypes.VECTOR3F,new Vector3f(scale,scale,scale)));
            data.add(new EntityData<>(IDX_BILLBOARD,EntityDataTypes.BYTE,BILLBOARD_CENTER));
            data.add(viewRange(sneaking));
            data.add(style(dimPass));
            data.add(opacity(dimPass,sneaking));
            data.add(new EntityData<>(IDX_LINE_WIDTH,EntityDataTypes.INT,200));
            /** Background only on the dim pass. Vanilla draws the backdrop once; putting it on both would
             *  stack two translucent panels and read as a darker box than a real nametag. */
            data.add(new EntityData<>(IDX_BACKGROUND,EntityDataTypes.INT,dimPass?plugin.getConfig().getInt("nametags.background-argb",1073741824):0));
            data.add(text(variant));
            send(viewer,new WrapperPlayServerEntityMetadata(pair[pass],data));
        }
        /** Attachment: the client is told both are passengers of the real player, so IT owns positioning.
         *  Both ids go in ONE packet -- a second SetPassengers would replace the first, not add to it. */
        send(viewer,new WrapperPlayServerSetPassengers(target.getEntityId(),new int[]{pair[0],pair[1]}));
    }
    private void send(Player viewer,com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet){
        try{PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,packet);}
        catch(Throwable error){plugin.getLogger().warning("[Nametags] packet send failed: "+error);}
    }
    private NamedTextColor factionColor(){
        NamedTextColor color=NamedTextColor.NAMES.value(plugin.getConfig().getString("nametags.faction-color","AQUA").toLowerCase(java.util.Locale.ROOT));
        return color==null?NamedTextColor.AQUA:color;
    }
    /** Same shared formatter as every other screen; the leading $ is what text() colours separately. */
    private String compact(double amount){return CoreUtil.compactMoney(amount);}
}
