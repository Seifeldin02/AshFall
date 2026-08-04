package net.communitysmp.core;

import org.bukkit.command.CommandSender;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ashfall's moderation-management layer on top of PunisherX, which remains the single authoritative
 *  warning/mute/ban system (see plugin.yml's note on /warn — SMPCore's own local warning system was retired
 *  entirely, not duplicated here). PunisherX already has solid commands for issuing punishments (/warn,
 *  /mute, /ban, /banip, /kick, /jail) and clearing them (/unwarn, /unmute, /unban, /unjail, /clearall), plus
 *  /change-reason, /check, /history, /banlist — none of that is reimplemented here. This only adds what
 *  PunisherX genuinely doesn't have: changing an existing punishment's DURATION by ID (no such command
 *  exists at all), revoking one SPECIFIC punishment by ID rather than only "the last warning" (/unwarn's one
 *  mode), a single unified view spanning PunisherX's own data + Ashfall's separate IP-ban system (a table
 *  PunisherX has no awareness of) + staff notes (a feature that doesn't exist anywhere else), and staff
 *  notes themselves.
 *
 *  There is no PunisherX API for "read every punishment a player has" — this talks to its SQLite file
 *  directly (same technique already used this session for the alialiomer duration fix and warning
 *  migration), which keeps it reading the exact same data /check and /history already show rather than
 *  re-deriving a second, possibly-inconsistent notion of "this player's punishments". Every write here
 *  updates PunisherX's own tables directly too (there's no separate Ashfall copy of punishment state to
 *  drift out of sync), followed by a live "punisherx reload" so the change takes effect immediately instead
 *  of needing a restart — confirmed this session that PunisherX's own commands (unlike WorldEdit/Prism's)
 *  work fine when dispatched from console. */
final class ModerationService {
    private final SMPCore plugin;
    private final Database db;
    ModerationService(SMPCore plugin){this.plugin=plugin;this.db=plugin.db();}

    private static final Pattern DURATION=Pattern.compile("(\\d+)([smhdw])",Pattern.CASE_INSENSITIVE);
    /** PunisherX's own convention, confirmed against real records this session: endTime=-1 means permanent.
     *  (Ashfall's separate ip_bans table uses 0 for permanent instead — different table, different author,
     *  not something to unify here; this parser matches whichever table it's actually writing to.) */
    private static final long INVALID_DURATION=Long.MIN_VALUE;
    private long parseDuration(String token){
        if(token==null)return INVALID_DURATION;
        if(token.equalsIgnoreCase("perm")||token.equalsIgnoreCase("permanent"))return -1;
        Matcher m=DURATION.matcher(token);
        if(!m.matches())return INVALID_DURATION;
        long amount=Long.parseLong(m.group(1));
        long unitMillis=switch(m.group(2).toLowerCase(Locale.ROOT)){case"s"->1000L;case"m"->60000L;case"h"->3600000L;case"d"->86400000L;case"w"->604800000L;default->-1L;};
        return unitMillis<0?INVALID_DURATION:System.currentTimeMillis()+amount*unitMillis;
    }
    private String describeDuration(long endTime){
        if(endTime==-1)return"permanent";
        long remaining=endTime-System.currentTimeMillis();
        if(remaining<=0)return"expired";
        long days=remaining/86400000L,hours=(remaining%86400000L)/3600000L,minutes=(remaining%3600000L)/60000L;
        if(days>0)return days+"d "+hours+"h";
        if(hours>0)return hours+"h "+minutes+"m";
        return Math.max(1,minutes)+"m";
    }

    private File punisherXDatabaseFile(){
        org.bukkit.plugin.Plugin px=plugin.getServer().getPluginManager().getPlugin("PunisherX");
        if(px==null)return null;
        org.bukkit.configuration.file.YamlConfiguration config=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new File(px.getDataFolder(),"config.yml"));
        if(!"sqlite".equalsIgnoreCase(config.getString("database.type","sqlite")))return null;
        File file=new File(px.getDataFolder(),config.getString("database.sql.dbname","my_database"));
        return file.isFile()?file:null;
    }
    private Connection punisherXConnection() throws SQLException{
        File file=punisherXDatabaseFile();
        if(file==null)throw new SQLException("PunisherX's SQLite database could not be found (not installed, or configured for a non-SQLite backend this can't read).");
        return DriverManager.getConnection("jdbc:sqlite:"+file.getAbsolutePath());
    }
    private void reloadPunisherX(){plugin.getServer().getScheduler().runTask(plugin,()->plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),"punisherx reload"));}

    /** name/reason/operator come straight from PunisherX's own columns, denormalized at write time —
     *  exactly what /check and /history already display, not a separate Ashfall interpretation of them. */
    record PunishmentRow(long id,String name,String uuid,String reason,String operator,String type,long start,long endTime){
        boolean active(){return endTime==-1||endTime>System.currentTimeMillis();}
    }
    private List<PunishmentRow> queryPunishments(String table,String uuid,int limit) throws SQLException{
        List<PunishmentRow> rows=new ArrayList<>();
        try(Connection c=punisherXConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM "+table+" WHERE uuid=? ORDER BY start DESC LIMIT ?")){
            ps.setString(1,uuid);ps.setInt(2,limit);
            try(ResultSet rs=ps.executeQuery()){
                while(rs.next())rows.add(new PunishmentRow(rs.getLong("id"),rs.getString("name"),rs.getString("uuid"),rs.getString("reason"),rs.getString("operator"),rs.getString("punishmentType"),rs.getLong("start"),rs.getLong("endTime")));
            }
        }
        return rows;
    }
    private PunishmentRow findActiveById(long id) throws SQLException{
        try(Connection c=punisherXConnection();PreparedStatement ps=c.prepareStatement("SELECT * FROM punishments WHERE id=?")){
            ps.setLong(1,id);
            try(ResultSet rs=ps.executeQuery()){
                if(!rs.next())return null;
                return new PunishmentRow(rs.getLong("id"),rs.getString("name"),rs.getString("uuid"),rs.getString("reason"),rs.getString("operator"),rs.getString("punishmentType"),rs.getLong("start"),rs.getLong("endTime"));
            }
        }
    }
    /** Best-effort match to the corresponding punishmenthistory row — punishments and punishmenthistory use
     *  independent auto-increment sequences (confirmed this session: the same ban was id=29 in one table,
     *  id=32 in the other), so there's no foreign key to follow directly. name+uuid+punishmentType+start is
     *  specific enough in practice to identify the same event without touching an unrelated record — this is
     *  exactly the match that had to be done by hand for the alialiomer fix earlier; this automates it
     *  correctly instead of risking the same mistake (an earlier manual attempt this session hit the wrong
     *  history row on the first try). */
    private void syncHistoryEndTime(PunishmentRow row,long newEndTime) throws SQLException{
        try(Connection c=punisherXConnection();PreparedStatement ps=c.prepareStatement("UPDATE punishmenthistory SET endTime=? WHERE name=? AND uuid=? AND punishmentType=? AND start=?")){
            ps.setLong(1,newEndTime);ps.setString(2,row.name());ps.setString(3,row.uuid());ps.setString(4,row.type());ps.setLong(5,row.start());
            ps.executeUpdate();
        }
    }

    private UUID resolveUuid(String name){
        org.bukkit.entity.Player online=plugin.getServer().getPlayerExact(name);
        if(online!=null)return online.getUniqueId();
        return UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8));
    }

    boolean command(CommandSender sender,String[] args){
        if(args.length<2){help(sender);return true;}
        return switch(args[1].toLowerCase(Locale.ROOT)){
            case"view"->view(sender,args);
            case"duration"->duration(sender,args);
            case"revoke"->revoke(sender,args);
            case"note"->note(sender,args);
            case"notes"->notes(sender,args);
            default->{help(sender);yield true;}
        };
    }
    private void help(CommandSender sender){
        CoreUtil.msg(sender,"Moderation (Ashfall helpers — PunisherX itself owns /warn /mute /ban /banip /kick /jail /unwarn /unmute /unban /unjail /clearall /change-reason /check /history /banlist):");
        CoreUtil.msg(sender,"  /ashfall moderation view <player>  — unified view: PunisherX active+history, Ashfall IP-bans, staff notes");
        CoreUtil.msg(sender,"  /ashfall moderation duration <id> <duration|perm>  — change an EXISTING active punishment's expiry (PunisherX has no command for this)");
        CoreUtil.msg(sender,"  /ashfall moderation revoke <id>  — remove one specific active punishment by ID (unlike /unwarn, which only removes the last one)");
        CoreUtil.msg(sender,"  /ashfall moderation note <player> <text>  — add a private staff note (visible only via 'view'/'notes', never to the player)");
        CoreUtil.msg(sender,"  /ashfall moderation notes <player>  — list staff notes");
        CoreUtil.msg(sender,"  IP-bans: /ashfall ipban ban|unban|duration|list (already existed, folded into 'view')");
    }
    private boolean view(CommandSender sender,String[] args){
        if(args.length<3){CoreUtil.error(sender,"Usage: /ashfall moderation view <player>");return true;}
        String name=args[2];UUID uuid=resolveUuid(name);
        CoreUtil.msg(sender,"Moderation record for "+name+" ("+uuid+")");
        try{
            List<PunishmentRow> active=queryPunishments("punishments",uuid.toString(),50);
            if(active.isEmpty())CoreUtil.msg(sender,"  Active (PunisherX): none");
            else{CoreUtil.msg(sender,"  Active (PunisherX):");for(PunishmentRow r:active)CoreUtil.msg(sender,"    #"+r.id()+" "+r.type()+" — \""+r.reason()+"\" — "+describeDuration(r.endTime())+" — by "+r.operator());}
            List<PunishmentRow> history=queryPunishments("punishmenthistory",uuid.toString(),10);
            if(history.isEmpty())CoreUtil.msg(sender,"  History (PunisherX, most recent 10 incl. expired/legacy): none");
            else{CoreUtil.msg(sender,"  History (PunisherX, most recent 10 incl. expired/legacy — run /history "+name+" for the full log):");for(PunishmentRow r:history)CoreUtil.msg(sender,"    #"+r.id()+" "+r.type()+" — \""+r.reason()+"\" — "+java.time.Instant.ofEpochMilli(r.start())+" — by "+r.operator());}
        }catch(SQLException e){CoreUtil.error(sender,"PunisherX lookup failed: "+e.getMessage());plugin.getLogger().warning("[moderation view] "+e);}
        String lastIp=plugin.ipBans().lastKnownIp(name);
        List<Database.IpBanRow> ipBans=lastIp==null?List.of():db.ipBans().stream().filter(b->b.ip().equals(lastIp)).toList();
        if(ipBans.isEmpty())CoreUtil.msg(sender,"  IP-bans (Ashfall): none on record for this name's last known IP");
        else{CoreUtil.msg(sender,"  IP-bans (Ashfall):");for(Database.IpBanRow b:ipBans)CoreUtil.msg(sender,"    "+b.ip()+" — \""+b.reason()+"\" — "+(b.expiresAt()==0?"permanent":describeDuration(b.expiresAt()))+" — by "+b.bannedBy());}
        List<Database.StaffNoteRow> notes=db.staffNotes(uuid.toString());
        if(notes.isEmpty())CoreUtil.msg(sender,"  Staff notes: none");
        else{CoreUtil.msg(sender,"  Staff notes ("+notes.size()+"):");for(Database.StaffNoteRow n:notes)CoreUtil.msg(sender,"    ["+java.time.Instant.ofEpochMilli(n.createdAt())+"] "+n.staffName()+": "+n.note());}
        return true;
    }
    private boolean duration(CommandSender sender,String[] args){
        if(args.length<4){CoreUtil.error(sender,"Usage: /ashfall moderation duration <id> <duration|perm> — id is from /check or /ashfall moderation view");return true;}
        long id;try{id=Long.parseLong(args[2]);}catch(NumberFormatException e){CoreUtil.error(sender,"<id> must be numeric — see /check <player> or /ashfall moderation view <player> for valid IDs.");return true;}
        long newEnd=parseDuration(args[3]);
        if(newEnd==INVALID_DURATION){CoreUtil.error(sender,"Invalid duration. Use e.g. 7d, 12h, 30m, or 'perm'.");return true;}
        try{
            PunishmentRow row=findActiveById(id);
            if(row==null){CoreUtil.error(sender,"No active punishment with ID "+id+".");return true;}
            String before=describeDuration(row.endTime());
            try(Connection c=punisherXConnection();PreparedStatement ps=c.prepareStatement("UPDATE punishments SET endTime=? WHERE id=?")){ps.setLong(1,newEnd);ps.setLong(2,id);ps.executeUpdate();}
            syncHistoryEndTime(row,newEnd);
            reloadPunisherX();
            db.logAudit(sender.getName(),"MODERATION_DURATION","id="+id+" player="+row.name()+" type="+row.type()+" reason=\""+row.reason()+"\" "+before+" -> "+describeDuration(newEnd));
            CoreUtil.msg(sender,"#"+id+" ("+row.type()+" on "+row.name()+", \""+row.reason()+"\"): "+before+" -> "+describeDuration(newEnd)+".");
        }catch(SQLException e){CoreUtil.error(sender,"Duration change failed: "+e.getMessage());plugin.getLogger().warning("[moderation duration] "+e);}
        return true;
    }
    private boolean revoke(CommandSender sender,String[] args){
        if(args.length<3){CoreUtil.error(sender,"Usage: /ashfall moderation revoke <id> — id is from /check or /ashfall moderation view. Removes ONLY that specific punishment; history is untouched.");return true;}
        long id;try{id=Long.parseLong(args[2]);}catch(NumberFormatException e){CoreUtil.error(sender,"<id> must be numeric — see /check <player> or /ashfall moderation view <player> for valid IDs.");return true;}
        try{
            PunishmentRow row=findActiveById(id);
            if(row==null){CoreUtil.error(sender,"No active punishment with ID "+id+".");return true;}
            try(Connection c=punisherXConnection();PreparedStatement ps=c.prepareStatement("DELETE FROM punishments WHERE id=?")){ps.setLong(1,id);ps.executeUpdate();}
            reloadPunisherX();
            db.logAudit(sender.getName(),"MODERATION_REVOKE","id="+id+" player="+row.name()+" type="+row.type()+" reason=\""+row.reason()+"\"");
            CoreUtil.msg(sender,"Revoked #"+id+" ("+row.type()+" on "+row.name()+", \""+row.reason()+"\"). It remains in /history as a record; only the active effect was removed.");
        }catch(SQLException e){CoreUtil.error(sender,"Revoke failed: "+e.getMessage());plugin.getLogger().warning("[moderation revoke] "+e);}
        return true;
    }
    private boolean note(CommandSender sender,String[] args){
        if(args.length<4){CoreUtil.error(sender,"Usage: /ashfall moderation note <player> <text>");return true;}
        String name=args[2];UUID uuid=resolveUuid(name);
        String text=String.join(" ",java.util.Arrays.copyOfRange(args,3,args.length));
        long id=db.addStaffNote(uuid.toString(),name,text,sender.getName());
        db.logAudit(sender.getName(),"MODERATION_NOTE","player="+name+" note_id="+id);
        CoreUtil.msg(sender,"Note #"+id+" added for "+name+". Staff-only — never shown to the player.");
        return true;
    }
    private boolean notes(CommandSender sender,String[] args){
        if(args.length<3){CoreUtil.error(sender,"Usage: /ashfall moderation notes <player>");return true;}
        String name=args[2];UUID uuid=resolveUuid(name);
        List<Database.StaffNoteRow> notes=db.staffNotes(uuid.toString());
        if(notes.isEmpty()){CoreUtil.msg(sender,"No staff notes for "+name+".");return true;}
        CoreUtil.msg(sender,"Staff notes for "+name+" ("+notes.size()+"):");
        for(Database.StaffNoteRow n:notes)CoreUtil.msg(sender,"  #"+n.id()+" ["+java.time.Instant.ofEpochMilli(n.createdAt())+"] "+n.staffName()+": "+n.note());
        return true;
    }
}
