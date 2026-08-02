package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Admin-only vanish and silent-spectate tools, built on the same visibility primitives GameplayListener
 *  already uses for spectator privacy, so behavior stays consistent.
 *  Live inventory/Ender Chest inspection used to live here (/invsee) but was a one-time snapshot with a
 *  blind overwrite-on-close — the confirmed 2026-07-30 duplication bug. Rather than keep patching a
 *  hand-rolled copy of what OpenInv already does correctly (genuinely live, no snapshot, no writeback),
 *  that responsibility now belongs entirely to OpenInv's /openinv and /openender. */
final class AdminToolsService implements Listener {
    private final SMPCore plugin;
    private final Set<UUID> vanished = new HashSet<>();
    private final Map<UUID,GameMode> previousGameMode = new HashMap<>();
    private final Map<UUID,UUID> spectating = new HashMap<>();
    private final Map<UUID,Location> previousLocation = new HashMap<>();

    AdminToolsService(SMPCore plugin){this.plugin=plugin;}

    boolean isVanished(UUID id){return vanished.contains(id);}

    boolean toggleVanish(Player admin){
        boolean now = !vanished.contains(admin.getUniqueId());
        if(now){
            vanished.add(admin.getUniqueId());
            for(Player other:plugin.getServer().getOnlinePlayers())if(!other.equals(admin))other.hidePlayer(plugin,admin);
        }else{
            vanished.remove(admin.getUniqueId());
            for(Player other:plugin.getServer().getOnlinePlayers())if(!other.equals(admin))other.showPlayer(plugin,admin);
        }
        plugin.db().logAudit(admin.getName(),"VANISH",now?"enabled":"disabled");
        return now;
    }

    boolean isSpectating(UUID id){return spectating.containsKey(id);}

    /** The one canonical "should this player be treated as if they don't exist" check — every target-
     *  resolution/autocomplete path (onlineNames(), NicknameService.findVisiblePlayer(), etc.) should use
     *  THIS instead of hand-rolling isVanished()||isSpectating(). isSpectating() alone only tracks admins
     *  who entered via /smp spectate; since admins can now also freely reach spectator mode with a plain
     *  /gamemode spectator, checking actual current gamemode is what makes this correct regardless of how
     *  they got there — /tp and /trade already hid such an admin correctly because their tab-completion
     *  rides on the client's own tab list, which hidePlayer()/showPlayer() updates on every gamemode change
     *  (see gameModeChange() below); SMPCore's own onlineNames() computed its list from isSpectating()
     *  directly and missed that case entirely, which is exactly why /tpa and /msg leaked them. */
    boolean isHiddenFromPublic(Player player){return isVanished(player.getUniqueId())||player.getGameMode()==GameMode.SPECTATOR;}

    boolean spectate(Player admin,Player target){
        if(target.equals(admin))return false;
        previousGameMode.putIfAbsent(admin.getUniqueId(),admin.getGameMode());
        previousLocation.putIfAbsent(admin.getUniqueId(),admin.getLocation());
        admin.setGameMode(GameMode.SPECTATOR);
        UUID targetId=target.getUniqueId();
        spectating.put(admin.getUniqueId(),targetId);
        /** setSpectatorTarget() called in the same tick as setGameMode(SPECTATOR) is a known client-side
         *  race: the client needs to finish applying the gamemode switch before it can render a spectator
         *  lock, and any network jitter — confirmed present on this host — makes the lock silently fail to
         *  take some of the time. This is why it "sometimes just works, sometimes doesn't." Deferring by a
         *  tick lets the gamemode packet land first, which is the standard fix for this exact race. */
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{
            if(!admin.isOnline()||!targetId.equals(spectating.get(admin.getUniqueId())))return;
            Player liveTarget=plugin.getServer().getPlayer(targetId);
            if(liveTarget==null||admin.getGameMode()!=GameMode.SPECTATOR)return;
            admin.setSpectatorTarget(liveTarget);
        },1L);
        plugin.db().logAudit(admin.getName(),"SPECTATE","target="+target.getName());
        return true;
    }

    boolean unspectate(Player admin){
        if(!spectating.containsKey(admin.getUniqueId()))return false;
        admin.setSpectatorTarget(null);
        GameMode restore=previousGameMode.remove(admin.getUniqueId());
        admin.setGameMode(restore==null?GameMode.SURVIVAL:restore);
        Location back=previousLocation.remove(admin.getUniqueId());
        spectating.remove(admin.getUniqueId());
        if(back!=null&&back.getWorld()!=null)admin.teleport(back);
        plugin.db().logAudit(admin.getName(),"UNSPECTATE","");
        return true;
    }

    /** The fake leave/join illusion (see gameModeChange() below) already announces an admin's entry into and
     *  exit from spectator — a real reconnect/disconnect that happens while they're STILL in that state must
     *  not ALSO show the genuine message, or it doubles up: fake "left" on entering spectator, then a second,
     *  real "left" when they actually disconnect (since the old vanished/spectating check alone doesn't cover
     *  an admin who got to spectator via a plain /gamemode spectator rather than /smp spectate).
     *  Deliberately checks isConfiguredAdminAccount() here, not plugin.isAdmin() — isAdmin() requires the
     *  live `authenticated` flag, which AuthMe's LoginEvent only sets well after PlayerJoinEvent (so a
     *  reconnecting admin still shows as "not admin" at the exact moment this runs) and which this class's
     *  own quit() / TrustedAdminService's quit() clear before this had a chance to see it — the account-name
     *  check has no such timing dependency, since it's just a static config lookup. */
    private boolean currentlyHidden(Player player){return vanished.contains(player.getUniqueId())||spectating.containsKey(player.getUniqueId())||(plugin.trustedAdmins().isConfiguredAdminAccount(player)&&player.getGameMode()==GameMode.SPECTATOR);}
    @EventHandler(priority=EventPriority.HIGH) public void join(PlayerJoinEvent event){
        Player joined = event.getPlayer();
        if(currentlyHidden(joined))event.joinMessage(null);
        for(UUID id:vanished){Player admin=plugin.getServer().getPlayer(id);if(admin!=null&&!admin.equals(joined))joined.hidePlayer(plugin,admin);}
    }

    /** Must run before ANY quit-time cleanup — including this class's own quit() below, which unconditionally
     *  un-spectates (resets gamemode) and clears vanished/spectating the instant a disconnect happens, and
     *  TrustedAdminService's quit() (also unprioritised = NORMAL), which clears the authenticated flag
     *  isAdmin() depends on. At HIGH (its previous value) this ran AFTER both of those on every quit,
     *  checking already-torn-down state and always finding "not hidden" — so a disconnecting hidden admin's
     *  real leave message showed every time regardless of vanish/spectate state. LOWEST guarantees this
     *  captures the true pre-disconnect state before anything else can clear it. */
    @EventHandler(priority=EventPriority.LOWEST) public void quitMessage(PlayerQuitEvent event){
        if(currentlyHidden(event.getPlayer()))event.quitMessage(null);
    }

    @EventHandler public void quit(PlayerQuitEvent event){
        Player player=event.getPlayer();
        if(spectating.containsKey(player.getUniqueId())){GameMode restore=previousGameMode.remove(player.getUniqueId());player.setGameMode(restore==null?GameMode.SURVIVAL:restore);spectating.remove(player.getUniqueId());}
        previousLocation.remove(player.getUniqueId());
        vanished.remove(player.getUniqueId());
    }

    /** Covers BOTH /smp spectate (which calls setGameMode internally) and a plain /gamemode spectator, since
     *  both route through the same event. Only fakes the leave/join illusion for admins — a regular player
     *  self-switching to spectator (if ever permitted) shouldn't silently vanish from chat. */
    @EventHandler(ignoreCancelled=true) public void gameModeChange(PlayerGameModeChangeEvent event){
        Player player=event.getPlayer();
        if(!plugin.isAdmin(player))return;
        GameMode from=player.getGameMode(),to=event.getNewGameMode();
        if(to==GameMode.SPECTATOR&&from!=GameMode.SPECTATOR)plugin.getServer().broadcast(Component.text(player.getName()+" left the game",NamedTextColor.YELLOW));
        else if(from==GameMode.SPECTATOR&&to!=GameMode.SPECTATOR)plugin.getServer().broadcast(Component.text(player.getName()+" joined the game",NamedTextColor.YELLOW));
    }

    void shutdown(){
        for(UUID id:Set.copyOf(spectating.keySet())){Player admin=plugin.getServer().getPlayer(id);if(admin!=null)unspectate(admin);}
        for(UUID id:Set.copyOf(vanished)){Player admin=plugin.getServer().getPlayer(id);if(admin!=null)for(Player other:plugin.getServer().getOnlinePlayers())if(!other.equals(admin))other.showPlayer(plugin,admin);}
        vanished.clear();
    }
}
