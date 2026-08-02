package net.communitysmp.core;

import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.RestoreSessionEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class TrustedAdminService implements Listener {
    private final SMPCore plugin;
    private final Set<UUID> authenticated=ConcurrentHashMap.newKeySet();
    private final Map<UUID,PermissionAttachment> attachments=new ConcurrentHashMap<>();

    TrustedAdminService(SMPCore plugin){
        this.plugin=plugin;
    }

    boolean isAdmin(Player player){return authenticated.contains(player.getUniqueId())&&realAccount(player);}

    @EventHandler(priority=EventPriority.LOWEST)
    public void join(PlayerJoinEvent event){
        Player player=event.getPlayer();
        if(realAccount(player))player.setOp(false);
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void authenticated(LoginEvent event){
        Player player=event.getPlayer();
        if(!realAccount(player))return;
        authenticated.add(player.getUniqueId());
        player.setOp(true);
        grantGamemode(player);
        plugin.getLogger().info("Administrator authenticated: "+player.getName()+".");
        player.updateCommands();
    }

    private void grantGamemode(Player player){
        PermissionAttachment attachment=player.addAttachment(plugin);
        attachment.setPermission("minecraft.command.gamemode",true);
        attachment.setPermission("minecraft.command.gamemode.other",true);
        attachments.put(player.getUniqueId(),attachment);
    }
    private void revokeGamemode(Player player){
        PermissionAttachment attachment=attachments.remove(player.getUniqueId());
        if(attachment!=null)player.removeAttachment(attachment);
    }

    /** Admin accounts normally never get AuthMe's own same-IP session skip (below, unconditionally, until
     *  this exception) — a stolen or spoofed session shouldn't be enough to hand out admin without a real
     *  password. trusted-admin.same-machine-autologin carves out one narrow exception to that: if the
     *  connection is literally from this same physical machine (the case every time when developing and
     *  testing on the box that also runs the server), let AuthMe's own session restore go through instead of
     *  forcing a fresh password — the exact same mechanism a non-admin account like MacoCT already benefits
     *  from unconditionally, just no longer blocked here for an admin account when the connection can't have
     *  come from anywhere but this machine. Defaults to false and is deliberately never set in the shipped
     *  config.yml resource — only staging's own live config.yml (outside git, never copied by any deploy
     *  script) sets it, so this exact jar stays fully locked down on production regardless of build. */
    @EventHandler(priority=EventPriority.LOWEST)
    public void requirePassword(RestoreSessionEvent event){
        Player player=event.getPlayer();
        if(!realAccount(player))return;
        if(plugin.getConfig().getBoolean("trusted-admin.same-machine-autologin",false)&&isThisMachine(player)){
            authenticated.add(player.getUniqueId());
            player.setOp(true);
            grantGamemode(player);
            plugin.getLogger().info("Administrator auto-logged in via same-machine session: "+player.getName()+".");
            player.updateCommands();
            return;
        }
        event.setCancelled(true);
        authenticated.remove(player.getUniqueId());
        player.setOp(false);
        revokeGamemode(player);
    }
    private boolean isThisMachine(Player player){
        if(!(player.getAddress() instanceof java.net.InetSocketAddress socket))return false;
        java.net.InetAddress address=socket.getAddress();
        if(address==null)return false;
        if(address.isLoopbackAddress())return true;
        try{
            java.util.Enumeration<java.net.NetworkInterface> interfaces=java.net.NetworkInterface.getNetworkInterfaces();
            while(interfaces.hasMoreElements()){
                java.util.Enumeration<java.net.InetAddress> addresses=interfaces.nextElement().getInetAddresses();
                while(addresses.hasMoreElements())if(addresses.nextElement().equals(address))return true;
            }
        }catch(Exception ignored){}
        return false;
    }

    @EventHandler
    public void quit(PlayerQuitEvent event){
        UUID id=event.getPlayer().getUniqueId();
        authenticated.remove(id);
        if(realAccount(event.getPlayer())){event.getPlayer().setOp(false);revokeGamemode(event.getPlayer());}
    }

    /** Immediate live grant/revoke for the /admin console command, mirroring vanilla /op and /deop:
     *  config.yml is always the source of truth, but an already-online target shouldn't have to
     *  relog to see the change take effect. */
    void forceGrant(Player player){
        authenticated.add(player.getUniqueId());
        player.setOp(true);
        grantGamemode(player);
        player.updateCommands();
    }
    void forceRevoke(Player player){
        authenticated.remove(player.getUniqueId());
        player.setOp(false);
        revokeGamemode(player);
        player.updateCommands();
    }

    void shutdown(){
        for(Player player:plugin.getServer().getOnlinePlayers())if(realAccount(player)){player.setOp(false);revokeGamemode(player);}
        authenticated.clear();
    }

    /** isAdmin() requires being in the live `authenticated` set, which is populated by AuthMe's LoginEvent
     *  (fires well after PlayerJoinEvent, once a password is entered) and cleared by quit()/requirePassword()
     *  the instant a session ends. That makes isAdmin() unreliable at exactly the two moments spectator-
     *  visibility messaging needs a stable admin-account check: at join, before login has happened yet, and
     *  at quit, after this class's own quit() has already cleared it. This checks only whether the account
     *  NAME is configured as trusted — no live-session dependency — for callers that need "is this account
     *  an admin" independent of whether they happen to be authenticated at this exact instant. */
    boolean isConfiguredAdminAccount(Player player){return realAccount(player);}
    private boolean realAccount(Player player){return accounts().contains(player.getName().toLowerCase(Locale.ROOT));}
    private Set<String> accounts(){
        List<String> configured=plugin.getConfig().getStringList("trusted-admin.accounts");
        if(configured.isEmpty()){String legacy=plugin.getConfig().getString("trusted-admin.account","");return legacy.isBlank()?Set.of():Set.of(legacy.toLowerCase(Locale.ROOT));}
        Set<String> lower=new java.util.HashSet<>();for(String name:configured)if(!name.isBlank())lower.add(name.toLowerCase(Locale.ROOT));return lower;
    }
    String accountList(){return String.join(", ",plugin.getConfig().getStringList("trusted-admin.accounts"));}
}
