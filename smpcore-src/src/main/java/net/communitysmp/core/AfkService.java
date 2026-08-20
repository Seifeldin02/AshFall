package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Lightweight AFK tracking. A player can toggle it manually, and standing still (no block movement and no
 *  chat) for the configured idle window (afk.idle-minutes, default 30) marks them AFK automatically. Any
 *  normal activity — moving a block or talking — clears it again, so nobody has to toggle it back off. */
final class AfkService implements Listener {
    private final SMPCore plugin;
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();
    /** Last time each online player did something that counts as activity (moved a block, or talked). Seeded
     *  on join so a fresh login is never instantly AFK, and cleared on quit. Drives the idle auto-AFK. */
    private final Map<UUID,Long> lastActive = new ConcurrentHashMap<>();
    private BukkitTask idleTask;

    AfkService(SMPCore plugin){
        this.plugin=plugin;
        /** Once a minute is fine-grained enough for a 30-minute threshold and essentially free. */
        idleTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::idleCheck,1200L,1200L);
    }
    void shutdown(){if(idleTask!=null)idleTask.cancel();}
    private void idleCheck(){
        long idleMs=Math.max(1,plugin.getConfig().getLong("afk.idle-minutes",30))*60000L;long now=System.currentTimeMillis();
        for(Player player:plugin.getServer().getOnlinePlayers()){
            if(afk.contains(player.getUniqueId()))continue;
            if(now-lastActive.getOrDefault(player.getUniqueId(),now)>=idleMs)markAfk(player);
        }
    }
    private void markAfk(Player player){
        if(afk.add(player.getUniqueId()))plugin.getServer().broadcast(Component.text(plugin.nicknames().displayName(player)+" is now AFK.",NamedTextColor.GRAY));
    }

    boolean isAfk(Player player){return afk.contains(player.getUniqueId());}

    /** The one place any "you just pinged someone who's away" notice is produced. Every command that notifies
     *  another player (/tpa, /tpahere, /duel, /msg, and any future one of the same shape) routes through here
     *  instead of writing its own AFK line, so the wording is identical everywhere and adding a new such command
     *  is a one-liner. Sends the initiator ONE grey note and returns whether the target was AFK. No-ops (and
     *  returns false) for a null target or self, and never changes the command's own behaviour beyond the note. */
    boolean notifyIfAfk(Player initiator,Player target){
        if(initiator==null||target==null||initiator.equals(target)||!afk.contains(target.getUniqueId()))return false;
        initiator.sendMessage(Component.text("(Note: "+plugin.nicknames().displayName(target)+" is currently AFK and may not respond right away.)",NamedTextColor.GRAY));
        return true;
    }

    boolean toggle(Player player){
        boolean now=afk.add(player.getUniqueId());
        if(!now)afk.remove(player.getUniqueId());
        plugin.getServer().broadcast(Component.text(plugin.nicknames().displayName(player)+(now?" is now AFK.":" is no longer AFK."),NamedTextColor.GRAY));
        return now;
    }
    private void clearIfAfk(Player player){
        if(afk.remove(player.getUniqueId()))plugin.getServer().broadcast(Component.text(plugin.nicknames().displayName(player)+" is no longer AFK.",NamedTextColor.GRAY));
    }

    @EventHandler public void join(PlayerJoinEvent event){lastActive.put(event.getPlayer().getUniqueId(),System.currentTimeMillis());}
    @EventHandler public void move(PlayerMoveEvent event){
        if(event.getFrom().getBlockX()==event.getTo().getBlockX()&&event.getFrom().getBlockY()==event.getTo().getBlockY()&&event.getFrom().getBlockZ()==event.getTo().getBlockZ())return;
        lastActive.put(event.getPlayer().getUniqueId(),System.currentTimeMillis());
        clearIfAfk(event.getPlayer());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void chat(AsyncPlayerChatEvent event){
        lastActive.put(event.getPlayer().getUniqueId(),System.currentTimeMillis());
        clearIfAfk(event.getPlayer());
        for(Player online:plugin.getServer().getOnlinePlayers()){
            if(online.equals(event.getPlayer())||!afk.contains(online.getUniqueId()))continue;
            if(mentions(event.getMessage(),online.getName()))event.getPlayer().sendMessage(Component.text("(Note: "+plugin.nicknames().displayName(online)+" is currently AFK.)",NamedTextColor.GRAY));
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event){afk.remove(event.getPlayer().getUniqueId());lastActive.remove(event.getPlayer().getUniqueId());}

    private boolean mentions(String message,String name){
        return Pattern.compile("(?<![A-Za-z0-9_])"+Pattern.quote(name)+"(?![A-Za-z0-9_])",Pattern.CASE_INSENSITIVE).matcher(message).find();
    }
}
