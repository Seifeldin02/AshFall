package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/** Persistent, staff-facing warning/punishment tier system.
 *  Tier index (0=never punished) escalates permanently and NEVER resets — only the CURRENT 3-strike
 *  warning_count and the 7-day post-unban probation window are transient. A warning issued during probation
 *  skips the 3-strike count entirely and escalates immediately to the next tier. */
final class PunishmentService {
    private static final long[] TIER_DURATION_DAYS={1,7,30,365,-1};
    private static final String[] TIER_LABELS={"1 day","7 days","1 month","1 year","permanent"};
    private static final long PROBATION_DAYS=7;
    private final SMPCore plugin;
    private final Database db;

    PunishmentService(SMPCore plugin){this.plugin=plugin;this.db=plugin.db();}

    boolean warn(Player staff,String targetName,String reason){
        Database.StatsRow target=db.statsByName(targetName);
        if(target==null){CoreUtil.error(staff,"That player has never joined the server.");return true;}
        String playerId=target.id();
        long now=System.currentTimeMillis();
        Database.PunishmentRow state=db.punishment(playerId);
        boolean onProbation=now<state.probationUntil();
        int nextTier;boolean punished;int newCount=state.warningCount()+1;
        if(onProbation){
            nextTier=Math.min(TIER_LABELS.length,state.tier()+1);
            punished=true;
        }else if(newCount>=3){
            nextTier=Math.min(TIER_LABELS.length,state.tier()+1);
            punished=true;
        }else{
            nextTier=state.tier();punished=false;
            db.savePunishmentState(playerId,target.name(),state.tier(),newCount,state.probationUntil(),state.lastPunishedAt());
        }
        db.recordWarning(playerId,target.name(),CoreUtil.id(staff),staff.getName(),reason,punished?nextTier:0);
        if(!punished){
            CoreUtil.msg(staff,target.name()+" warned ("+newCount+"/3). Reason: "+reason);
            notifyTarget(target.name(),"§eYou have been warned by staff: §f"+reason+" §7("+newCount+"/3 — a 3rd warning triggers an automatic punishment).");
            return true;
        }
        long durationDays=TIER_DURATION_DAYS[nextTier-1];
        String label=TIER_LABELS[nextTier-1];
        long banEnd=durationDays<0?Long.MAX_VALUE:now+durationDays*86400000L;
        long probationUntil=durationDays<0?Long.MAX_VALUE:banEnd+PROBATION_DAYS*86400000L;
        db.savePunishmentState(playerId,target.name(),nextTier,0,probationUntil,now);
        applyBan(target.name(),durationDays,reason,label);
        String publicReason=(onProbation?"Warned during probation — escalated to tier "+nextTier+" (":"3 warnings reached — tier "+nextTier+" (")+label+"). Reason: "+reason;
        CoreUtil.msg(staff,target.name()+" has been punished: "+publicReason);
        db.history("SERVER",null,"MODERATION",target.name()+" was punished ("+label+").");
        plugin.db().logAudit(staff.getName(),"WARN_PUNISH","target="+target.name()+" tier="+nextTier+" reason="+reason);
        return true;
    }

    /** Player ids in this codebase are lowercase names, not real Mojang UUIDs (see CoreUtil.id), so Bukkit's
     *  UUID-keyed ban API has to be reached via the deprecated name-based OfflinePlayer lookup — there's no
     *  stored UUID anywhere to resolve it otherwise. */
    @SuppressWarnings("deprecation")
    private void applyBan(String playerName,long durationDays,String reason,String label){
        OfflinePlayer offline=Bukkit.getOfflinePlayer(playerName);
        String banMessage="Banned ("+label+"): "+reason;
        try{
            if(durationDays<0)offline.ban(banMessage,(Duration)null,"SMPCore /warn");
            else offline.ban(banMessage,Duration.ofDays(durationDays),"SMPCore /warn");
        }catch(Throwable error){plugin.getLogger().warning("Could not write ban entry for "+playerName+": "+error);}
        Player online=Bukkit.getPlayerExact(playerName);
        if(online!=null)online.kick(Component.text(banMessage,NamedTextColor.RED));
    }

    private void notifyTarget(String playerName,String message){Player online=Bukkit.getPlayerExact(playerName);if(online!=null)online.sendMessage(message);}

    void history(Player staff,String targetName,int page){
        Database.StatsRow target=db.statsByName(targetName);
        if(target==null){CoreUtil.error(staff,"That player has never joined the server.");return;}
        Database.PunishmentRow state=db.punishment(target.id());
        CoreUtil.msg(staff,target.name()+" — tier "+state.tier()+(state.tier()>0?" ("+TIER_LABELS[state.tier()-1]+")":" (none)")+" • active warnings "+state.warningCount()+"/3"+(System.currentTimeMillis()<state.probationUntil()?" • ON PROBATION":""));
        int size=8,safe=Math.max(1,page);
        List<Database.WarningRow> all=db.warnings(target.id(),200);
        int from=(safe-1)*size;
        if(from>=all.size()){staff.sendMessage("§7No entries on this page.");return;}
        for(Database.WarningRow row:all.subList(from,Math.min(all.size(),from+size)))staff.sendMessage("§8"+java.time.Instant.ofEpochMilli(row.createdAt())+" §7"+row.staffName()+" → §f"+row.reason()+(row.triggeredTier()>0?" §c[triggered tier "+row.triggeredTier()+"]":""));
    }

    boolean selfTest(){return TIER_DURATION_DAYS.length==5&&TIER_LABELS.length==5;}
}
