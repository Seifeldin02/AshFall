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
        /** STAGING ONLY. staging-session-persistence lets an admin account keep AuthMe's OWN same-IP session
         *  -- the exact 30-minute persistence every non-admin already gets unconditionally -- regardless of
         *  which machine they connect from. This is what an admin like MacoCT, who connects remotely rather
         *  than from the server box, actually needs: same-machine-autologin below only ever helped somebody
         *  physically on this machine (an admin on the server's own LAN IP), so a remote admin was still
         *  forced to /login after every restart. Kept a separate, broader flag rather than widening
         *  same-machine-autologin, so the narrow same-machine behaviour stays available on its own.
         *  Deliberately absent from the shipped config resource and never copied by a deploy script, so
         *  production admins still authenticate with a real password every time. */
        /** Admin login persistence: skip the /login password across restarts/reconnects via AuthMe's session
         *  restore. session-persistence is the master switch; require-same-ip (default true) constrains it to
         *  connections from the SAME IP as the server (its own machine / LAN IP / loopback) -- turn that off only
         *  when the server moves to external hosting and admins connect from a different IP. Legacy flags are
         *  still honored: staging-session-persistence = persistence with no IP constraint, same-machine-autologin
         *  = persistence constrained to this machine. Everything defaults off, so a stock config stays locked. */
        boolean persist=plugin.getConfig().getBoolean("trusted-admin.session-persistence",false);
        boolean requireSameIp=plugin.getConfig().getBoolean("trusted-admin.require-same-ip",true);
        if(!persist){
            if(plugin.getConfig().getBoolean("trusted-admin.staging-session-persistence",false)){persist=true;requireSameIp=false;}
            else if(plugin.getConfig().getBoolean("trusted-admin.same-machine-autologin",false)){persist=true;requireSameIp=true;}
        }
        if(persist&&(!requireSameIp||isThisMachine(player))){
            authenticated.add(player.getUniqueId());
            player.setOp(true);
            grantGamemode(player);
            plugin.getLogger().info("Administrator session restored for "+player.getName()+" from "
                    +describeAddress(player)+" (AuthMe session"+(requireSameIp?" + same machine":"")+").");
            player.updateCommands();
            return;
        }
        /** Silence was the whole problem: persistence simply did not happen and there was no way to tell
         *  whether the switch was off or the same-machine check had refused. Say which, and say what address
         *  was seen.
         *
         *  Worth being clear about what require-same-ip actually is, because the name reads like the IP check
         *  and it is not: AuthMe has ALREADY verified the session against the player's own previous address
         *  and its 30-minute timeout before RestoreSessionEvent is ever fired. This flag is a SECOND,
         *  stricter constraint on top -- "and also, only from the server box itself". Turning it off does not
         *  mean any address may restore a session; it means admins get exactly the same same-IP session that
         *  every other player already gets. */
        if(persist)plugin.getLogger().info("Administrator session NOT restored for "+player.getName()
                +" from "+describeAddress(player)+": AuthMe approved the session, but trusted-admin."
                +"require-same-ip is on and that address is not this machine.");
        event.setCancelled(true);
        authenticated.remove(player.getUniqueId());
        player.setOp(false);
        revokeGamemode(player);
    }

    private String describeAddress(Player player){
        if(!(player.getAddress() instanceof java.net.InetSocketAddress socket))return "an unknown address";
        java.net.InetAddress address=socket.getAddress();
        return address==null?"an unknown address":address.getHostAddress();
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
