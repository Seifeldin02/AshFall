package net.communitysmp.core;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline IP bans. PunisherX (installed for the rest of moderation — ban/mute/warn/kick/history) ships its
 *  own /banip, but it throws an unexpected error on every attempt in this build — reproduced both with an
 *  offline player name AND a literal IP, so it isn't specific to name resolution; most likely tied to its
 *  GeoIP lookup silently failing since no MaxMind license key is configured, though the exception itself
 *  isn't logged to console to confirm that precisely. Rather than depend on a broken third-party command for
 *  something this important, this is a small, self-contained implementation: resolve the IP from AuthMe's
 *  own database (the authoritative source — it stores real login/registration IPs, unlike SMPCore's own
 *  ip_hash column which is a one-way hash and can't be used to actually apply a ban), then enforce it
 *  directly via AsyncPlayerPreLoginEvent. Everything else in the moderation spec (ban/mute/warn/history/etc)
 *  goes through PunisherX unmodified — this exists only for the one piece that's demonstrably broken. */
final class IpBanService implements Listener {
    private static final Pattern DURATION = Pattern.compile("(?i)^(\\d+)([smhdw])$");
    private final SMPCore plugin;
    private final Database db;

    IpBanService(SMPCore plugin){this.plugin=plugin;this.db=plugin.db();}

    /** authme.db is a sibling plugin's own SQLite file, opened read-only and closed immediately after each
     *  lookup — never held open, never written to, so there's no lock contention with AuthMe's own connection
     *  and no risk of this service corrupting data it doesn't own. */
    private File authMeDatabase(){return new File(plugin.getDataFolder().getParentFile(),"AuthMe/authme.db");}

    /** Same online-connection > AuthMe-last-login > AuthMe-registration priority ban() already uses, exposed
     *  for ModerationService's unified "view" command so it can show which of Ashfall's ip_bans actually
     *  apply to a given player without duplicating this resolution logic. Null if nothing on record at all. */
    String lastKnownIp(String username){
        Player online=plugin.getServer().getPlayerExact(username);
        if(online!=null&&online.getAddress()!=null)return online.getAddress().getAddress().getHostAddress();
        AuthMeRecord record=lookupAuthMe(username);
        if(record==null)return null;
        return record.ip()!=null&&!record.ip().isBlank()?record.ip():record.regIp();
    }
    private record AuthMeRecord(String username,String ip,String regIp,long lastLogin){}
    private AuthMeRecord lookupAuthMe(String username){
        File dbFile=authMeDatabase();if(!dbFile.isFile())return null;
        try(Connection connection=DriverManager.getConnection("jdbc:sqlite:file:"+dbFile.getAbsolutePath()+"?mode=ro");
            PreparedStatement ps=connection.prepareStatement("SELECT username,ip,regip,lastlogin FROM authme WHERE LOWER(username)=? ORDER BY lastlogin DESC LIMIT 1")){
            ps.setString(1,username.toLowerCase(Locale.ROOT));
            try(ResultSet rs=ps.executeQuery()){
                if(!rs.next())return null;
                return new AuthMeRecord(rs.getString("username"),rs.getString("ip"),rs.getString("regip"),rs.getLong("lastlogin"));
            }
        }catch(SQLException e){plugin.getLogger().warning("Could not read AuthMe database for IP lookup: "+e.getMessage());return null;}
    }
    /** Every other account whose most recent login IP matches — used to warn the admin privately that an IP
     *  ban may catch more than the one account they targeted, per the moderation spec's explicit requirement. */
    private List<String> otherAccountsSharingIp(String ip,String excludingUsername){
        File dbFile=authMeDatabase();List<String> others=new ArrayList<>();if(!dbFile.isFile())return others;
        try(Connection connection=DriverManager.getConnection("jdbc:sqlite:file:"+dbFile.getAbsolutePath()+"?mode=ro");
            PreparedStatement ps=connection.prepareStatement("SELECT username FROM authme WHERE ip=? AND LOWER(username)!=?")){
            ps.setString(1,ip);ps.setString(2,excludingUsername.toLowerCase(Locale.ROOT));
            try(ResultSet rs=ps.executeQuery()){while(rs.next())others.add(rs.getString("username"));}
        }catch(SQLException ignored){}
        return others;
    }

    private long parseDuration(String token){
        if(token==null)return 0;
        if(token.equalsIgnoreCase("perm")||token.equalsIgnoreCase("permanent"))return 0;
        Matcher matcher=DURATION.matcher(token);if(!matcher.matches())return -1;
        long amount=Long.parseLong(matcher.group(1));
        long unitMillis=switch(matcher.group(2).toLowerCase(Locale.ROOT)){case"s"->1000L;case"m"->60000L;case"h"->3600000L;case"d"->86400000L;case"w"->604800000L;default->-1L;};
        return unitMillis<0?-1:System.currentTimeMillis()+amount*unitMillis;
    }
    private String describeDuration(long expiresAt){
        if(expiresAt==0)return"permanent";
        long remaining=expiresAt-System.currentTimeMillis();if(remaining<=0)return"expired";
        long days=remaining/86400000L,hours=(remaining%86400000L)/3600000L,minutes=(remaining%3600000L)/60000L;
        if(days>0)return days+"d "+hours+"h";if(hours>0)return hours+"h "+minutes+"m";return Math.max(1,minutes)+"m";
    }

    boolean command(CommandSender sender,String[] args){
        if(args.length<2){help(sender);return true;}
        return switch(args[1].toLowerCase(Locale.ROOT)){
            case"ban"->ban(sender,args);
            case"unban"->unban(sender,args);
            case"duration"->duration(sender,args);
            case"list"->{list(sender);yield true;}
            default->{help(sender);yield true;}
        };
    }
    private void help(CommandSender sender){
        CoreUtil.msg(sender,"Offline IP bans: /ashfall ipban ban <player> <duration|perm> <reason>");
        CoreUtil.msg(sender,"                  /ashfall ipban unban <player|ip>");
        CoreUtil.msg(sender,"                  /ashfall ipban duration <player|ip> <duration|perm>");
        CoreUtil.msg(sender,"                  /ashfall ipban list");
    }

    private boolean ban(CommandSender sender,String[] args){
        if(args.length<5){CoreUtil.error(sender,"Usage: /ashfall ipban ban <player> <duration|perm> <reason>");return true;}
        String targetName=args[2];long expiresAt=parseDuration(args[3]);
        if(expiresAt==-1){CoreUtil.error(sender,"Invalid duration. Use e.g. 7d, 12h, 30m, or 'perm'.");return true;}
        String reason=String.join(" ",java.util.Arrays.copyOfRange(args,4,args.length));
        String adminName=sender instanceof Player p?p.getName():"CONSOLE";
        Player online=plugin.getServer().getPlayerExact(targetName);
        String ip=online!=null&&online.getAddress()!=null?online.getAddress().getAddress().getHostAddress():null;
        String sourceEntry="online connection";
        if(ip==null){
            AuthMeRecord record=lookupAuthMe(targetName);
            if(record==null||(record.ip()==null&&record.regIp()==null)){
                CoreUtil.error(sender,targetName+" has no stored IP on record (AuthMe has no login history for that name). Refusing to guess — this name will not be treated as an IP address.");
                return true;
            }
            ip=record.ip()!=null&&!record.ip().isBlank()?record.ip():record.regIp();
            sourceEntry=record.ip()!=null&&!record.ip().isBlank()?"AuthMe last login":"AuthMe registration IP";
            targetName=record.username();
        }
        db.addIpBan(ip,adminName,reason,expiresAt,targetName,sourceEntry);
        db.logAudit(adminName,"IP_BAN","player="+targetName+" duration="+describeDuration(expiresAt)+" reason="+reason);
        CoreUtil.msg(sender,"IP-banned "+targetName+" ("+sourceEntry+") for "+describeDuration(expiresAt)+".");
        if(online!=null)online.kick(net.kyori.adventure.text.Component.text("You have been IP-banned"+(expiresAt==0?"":" for "+describeDuration(expiresAt))+". Reason: "+reason));
        List<String> shared=otherAccountsSharingIp(ip,targetName);
        if(!shared.isEmpty())CoreUtil.msg(sender,"§7Note: this IP is also on record for: "+String.join(", ",shared)+" — this ban will affect them too if they try to connect.");
        return true;
    }
    private boolean unban(CommandSender sender,String[] args){
        if(args.length<3){CoreUtil.error(sender,"Usage: /ashfall ipban unban <player|ip>");return true;}
        String target=args[2],ip=resolveForLookup(target);
        if(ip==null){CoreUtil.error(sender,"No IP ban found for "+target+".");return true;}
        boolean removed=db.removeIpBan(ip);
        if(!removed){CoreUtil.error(sender,"No IP ban found for "+target+".");return true;}
        db.logAudit(sender instanceof Player p?p.getName():"CONSOLE","IP_UNBAN","target="+target);
        CoreUtil.msg(sender,"Removed the IP ban for "+target+".");
        return true;
    }
    private boolean duration(CommandSender sender,String[] args){
        if(args.length<4){CoreUtil.error(sender,"Usage: /ashfall ipban duration <player|ip> <duration|perm>");return true;}
        String target=args[2],ip=resolveForLookup(target);
        if(ip==null){CoreUtil.error(sender,"No IP ban found for "+target+".");return true;}
        long expiresAt=parseDuration(args[3]);
        if(expiresAt==-1){CoreUtil.error(sender,"Invalid duration. Use e.g. 7d, 12h, 30m, or 'perm'.");return true;}
        db.setIpBanExpiry(ip,expiresAt);
        db.logAudit(sender instanceof Player p?p.getName():"CONSOLE","IP_BAN_DURATION","target="+target+" new_duration="+describeDuration(expiresAt));
        CoreUtil.msg(sender,"Updated IP ban duration for "+target+" to "+describeDuration(expiresAt)+".");
        return true;
    }
    private void list(CommandSender sender){
        List<Database.IpBanRow> bans=db.ipBans();
        if(bans.isEmpty()){CoreUtil.msg(sender,"No active IP bans.");return;}
        for(Database.IpBanRow ban:bans)if(ban.active())CoreUtil.msg(sender,ban.sourcePlayer()+" ("+ban.sourceEntry()+") — "+describeDuration(ban.expiresAt())+" — "+ban.reason());
    }
    /** Accepts either a raw IP (direct table hit) or a player name (resolved to whatever IP that name's most
     *  recent AuthMe record used, THEN checked against the ban table) — covers both "I know the IP" and "I
     *  only know the name" admin workflows for unban/duration without needing two separate commands. */
    private String resolveForLookup(String target){
        if(db.ipBan(target)!=null)return target;
        AuthMeRecord record=lookupAuthMe(target);
        if(record!=null){
            if(record.ip()!=null&&db.ipBan(record.ip())!=null)return record.ip();
            if(record.regIp()!=null&&db.ipBan(record.regIp())!=null)return record.regIp();
        }
        return null;
    }

    @EventHandler(priority=org.bukkit.event.EventPriority.LOW)
    public void preLogin(AsyncPlayerPreLoginEvent event){
        String ip=event.getAddress().getHostAddress();
        Database.IpBanRow ban=db.ipBan(ip);
        if(ban==null||!ban.active())return;
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,net.kyori.adventure.text.Component.text(
                "You are IP-banned"+(ban.expiresAt()==0?"":" for "+describeDuration(ban.expiresAt()))+". Reason: "+ban.reason()));
    }
}
