package net.communitysmp.core;

import org.bukkit.entity.Player;
import uk.co.forgevector.replaycore.api.plugin.AssetRelationship;
import uk.co.forgevector.replaycore.api.plugin.KillReplay;
import uk.co.forgevector.replaycore.api.plugin.KillReplayApi;
import uk.co.forgevector.replaycore.api.plugin.ReplayCatalogApi;
import uk.co.forgevector.replaycore.api.plugin.ReplayCatalogEntry;
import uk.co.forgevector.replaycore.api.plugin.ReplayCatalogQuery;
import uk.co.forgevector.replaycore.api.plugin.ReplayCoreApi;
import uk.co.forgevector.replaycore.api.plugin.ReplayCoreProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Optional hook into the ReplayCore plugin (uk.co.forgevector.replaycore), if installed and enabled.
 *  ReplayCore records its own kill-replay clips automatically (its built-in death-cam feature, configured
 *  server-side at replaycore.com) — this class only ever looks up the resulting clip afterwards, it never
 *  triggers recording itself. Every entry point is null-safe and exception-safe so the rest of SMPCore
 *  behaves identically whether or not ReplayCore is present. */
final class ReplayIntegration {
    private final SMPCore plugin;
    private final boolean present;

    ReplayIntegration(SMPCore plugin){
        this.plugin=plugin;
        this.present=plugin.getServer().getPluginManager().isPluginEnabled("ReplayCore");
    }

    boolean available(){return present&&api().flatMap(ReplayCoreApi::killReplay).isPresent();}

    private Optional<ReplayCoreApi> api(){return present?ReplayCoreProvider.get():Optional.empty();}

    /** Most recent kill-replay for a victim, with a resolved web URL if one is ready and not yet expired.
     *  Empty if ReplayCore is absent, has no clip yet, or the lookup itself fails for any reason. */
    Optional<KillReplay> latestKillReplay(UUID victim){
        try{
            Optional<KillReplayApi> killApi=api().flatMap(ReplayCoreApi::killReplay);
            if(killApi.isEmpty())return Optional.empty();
            return killApi.get().latestKillReplayWithWebUrl(victim).filter(r->r.valid(System.currentTimeMillis()));
        }catch(Exception e){
            plugin.getLogger().warning("ReplayCore kill-replay lookup failed: "+e);
            return Optional.empty();
        }
    }

    /** Admin lookup by killer or victim — queries ReplayCore's own catalog directly rather than mirroring
     *  it in SMPCore's database, so results are always current. ReplayCatalogApi is its own Bukkit service
     *  (registered independently of ReplayCoreApi), so it's fetched via the ServicesManager. Runs the
     *  (async) catalog call off-thread and always hands the result back on the main thread. */
    void searchReplays(UUID player,boolean asKiller,int limit,Consumer<List<ReplayCatalogEntry>> onResult,Consumer<Throwable> onError){
        if(!present){onError.accept(new IllegalStateException("ReplayCore is not installed or not enabled."));return;}
        var registration=plugin.getServer().getServicesManager().getRegistration(ReplayCatalogApi.class);
        if(registration==null){onError.accept(new IllegalStateException("This ReplayCore version does not expose a replay catalog."));return;}
        ReplayCatalogQuery query=ReplayCatalogQuery.builder().viewerRelation(asKiller?AssetRelationship.KILL:AssetRelationship.DEATH).limit(limit).build();
        registration.getProvider().listForPlayer(player,query).whenComplete((page,error)->plugin.getServer().getScheduler().runTask(plugin,()->{
            if(error!=null)onError.accept(error);else onResult.accept(page.entries());
        }));
    }

    Player onlinePlayer(UUID id){return plugin.getServer().getPlayer(id);}
}
