package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Lightweight, manual-toggle AFK tracking. No idle timer — the player says when they're away, and normal
 *  activity (moving or talking) clears it automatically so nobody has to remember to toggle it back off. */
final class AfkService implements Listener {
    private final SMPCore plugin;
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();

    AfkService(SMPCore plugin){this.plugin=plugin;}

    boolean isAfk(Player player){return afk.contains(player.getUniqueId());}

    boolean toggle(Player player){
        boolean now=afk.add(player.getUniqueId());
        if(!now)afk.remove(player.getUniqueId());
        plugin.getServer().broadcast(Component.text(plugin.nicknames().displayName(player)+(now?" is now AFK.":" is no longer AFK."),NamedTextColor.GRAY));
        return now;
    }
    private void clearIfAfk(Player player){
        if(afk.remove(player.getUniqueId()))plugin.getServer().broadcast(Component.text(plugin.nicknames().displayName(player)+" is no longer AFK.",NamedTextColor.GRAY));
    }

    @EventHandler public void move(PlayerMoveEvent event){
        if(!afk.contains(event.getPlayer().getUniqueId()))return;
        if(event.getFrom().getBlockX()!=event.getTo().getBlockX()||event.getFrom().getBlockY()!=event.getTo().getBlockY()||event.getFrom().getBlockZ()!=event.getTo().getBlockZ())clearIfAfk(event.getPlayer());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void chat(AsyncPlayerChatEvent event){
        clearIfAfk(event.getPlayer());
        for(Player online:plugin.getServer().getOnlinePlayers()){
            if(online.equals(event.getPlayer())||!afk.contains(online.getUniqueId()))continue;
            if(mentions(event.getMessage(),online.getName()))event.getPlayer().sendMessage(Component.text("(Note: "+plugin.nicknames().displayName(online)+" is currently AFK.)",NamedTextColor.GRAY));
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event){afk.remove(event.getPlayer().getUniqueId());}

    private boolean mentions(String message,String name){
        return Pattern.compile("(?<![A-Za-z0-9_])"+Pattern.quote(name)+"(?![A-Za-z0-9_])",Pattern.CASE_INSENSITIVE).matcher(message).find();
    }
}
