package net.communitysmp.core;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.event.events.FlagEvent;

import java.util.Locale;

final class GrimCompatibility {
    private final SMPCore plugin;
    private GrimAbstractAPI api;

    GrimCompatibility(SMPCore plugin) {
        this.plugin = plugin;
        GrimAPIProvider.getAsync()
                .thenAccept(loaded -> plugin.getServer().getScheduler().runTask(plugin, () -> register(loaded)))
                .exceptionally(error -> {
                    plugin.getLogger().warning("Grim variable-hitbox compatibility could not start: " + error.getClass().getSimpleName());
                    return null;
                });
    }

    private void register(GrimAbstractAPI loaded) {
        if (!plugin.isEnabled()) return;
        api = loaded;
        FlagEvent.Channel channel = loaded.getEventBus().get(FlagEvent.class);
        if(channel==null){plugin.getLogger().warning("Grim flag channel is unavailable; Slime/Magma Cube compatibility was not registered.");return;}
        channel.onFlagSupplier(plugin, (user, check, verboseSupplier, cancelled) -> {
            if (cancelled || check == null) return cancelled;
            String name = check.getCheckName();
            if (!name.equalsIgnoreCase("Reach") && !name.equalsIgnoreCase("Hitboxes") && !name.equalsIgnoreCase("Hitbox")) return false;
            /** The API gives no structured way to read the target entity off a flag — only this free-text
             *  debug string, whose exact key/format isn't documented and can vary (a large/scaled slime's
             *  bigger hitbox appears to change it enough that the old "type=slime"/"entity=slime" prefix
             *  match missed it). Matching the bare word anywhere is far more resilient to that, at
             *  essentially zero false-cancel risk — "slime"/"magma_cube" isn't going to appear in a reach
             *  check's verbose output for an unrelated entity. */
            String verbose = verboseSupplier == null ? "" : verboseSupplier.get().toLowerCase(Locale.ROOT);
            return verbose.contains("slime") || verbose.contains("magma_cube") || verbose.contains("magma cube");
        });
    }

    void shutdown() {
        if (api != null) api.getEventBus().unregisterAllListeners(plugin);
    }
}
