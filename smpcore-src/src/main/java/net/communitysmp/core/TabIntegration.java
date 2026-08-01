package net.communitysmp.core;

import com.destroystokyo.paper.profile.ProfileProperty;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.event.EventHandler;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;
import me.neznamy.tab.api.placeholder.PlayerPlaceholder;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TabIntegration {
    private static final Pattern TEXTURE_HASH =
            Pattern.compile("textures\\.minecraft\\.net/texture/([A-Za-z0-9]+)");

    private final SMPCore plugin;
    private final Map<UUID, String> skinHashes = new ConcurrentHashMap<>();
    private final Map<UUID, String> factionSuffixes = new ConcurrentHashMap<>();
    private final Map<UUID, String> displayNames = new ConcurrentHashMap<>();
    private final Map<UUID, String> relicSuffixes = new ConcurrentHashMap<>();
    private PlayerPlaceholder skinPlaceholder;
    private PlayerPlaceholder factionPlaceholder;
    private PlayerPlaceholder displayPlaceholder;
    private PlayerPlaceholder relicPlaceholder;
    private EventHandler<TabLoadEvent> reloadHandler;
    private BukkitTask cacheTask;

    TabIntegration(SMPCore plugin, FactionService factions) {
        this.plugin = plugin;
        if (plugin.getServer().getPluginManager().getPlugin("TAB") == null) {
            plugin.getLogger().info("TAB is not installed; SMPCore player-list formatting is disabled.");
            return;
        }
        try {
            registerPlaceholders();
            reloadHandler = event -> plugin.getServer().getScheduler()
                    .runTask(plugin, this::registerPlaceholders);
            TabAPI.getInstance().getEventBus().register(TabLoadEvent.class, reloadHandler);
            cacheTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, 1L,
                    Math.max(20L,plugin.getConfig().getLong("performance.tab-refresh-ticks",100)));
            plugin.getLogger().info("TAB integration active: rendered skin heads and faction suffixes registered.");
        } catch (Throwable error) {
            plugin.getLogger().severe("Could not register TAB integration: " + error.getMessage());
        }
    }

    private void registerPlaceholders() {
        var manager = TabAPI.getInstance().getPlaceholderManager();
        manager.unregisterPlaceholder("%smp_skin_hash%");
        manager.unregisterPlaceholder("%smp_faction_suffix%");
        manager.unregisterPlaceholder("%smp_display_name%");
        manager.unregisterPlaceholder("%smp_relic_suffix%");
        skinPlaceholder = manager.registerPlayerPlaceholder("%smp_skin_hash%", 1000,
                player -> skinHashes.getOrDefault(player.getUniqueId(), defaultTexture()));
        factionPlaceholder = manager.registerPlayerPlaceholder("%smp_faction_suffix%", 500,
                player -> factionSuffixes.getOrDefault(player.getUniqueId(), ""));
        displayPlaceholder = manager.registerPlayerPlaceholder("%smp_display_name%", 500,
                player -> displayNames.getOrDefault(player.getUniqueId(), player.getName()));
        relicPlaceholder = manager.registerPlayerPlaceholder("%smp_relic_suffix%", 500,
                player -> relicSuffixes.getOrDefault(player.getUniqueId(), ""));
    }

    private void refresh() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            skinHashes.put(player.getUniqueId(), skinHash(player));
            Database.FactionRow faction = plugin.db().factionOf(CoreUtil.id(player));
            factionSuffixes.put(player.getUniqueId(),
                    faction == null||plugin.nicknames().isNicked(player) ? "" : " \u00A7b[" + faction.tag() + "]");
            displayNames.put(player.getUniqueId(),plugin.nicknames().displayName(player));
            relicSuffixes.put(player.getUniqueId(),
                    plugin.progress()!=null&&plugin.progress().sovereign(CoreUtil.id(player)) ? " \u00A7d\u25C6" : "");
        }
        skinHashes.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
        factionSuffixes.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
        displayNames.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
        relicSuffixes.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
    }
    void refreshNow(){refresh();}

    private String skinHash(Player player) {
        String nickedTexture=plugin.nicknames().skinTexture(player);
        if(nickedTexture!=null){
            String hash=hashFromTextureValue(nickedTexture);
            if(hash!=null)return hash;
        }
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if (!property.getName().equalsIgnoreCase("textures")) continue;
            String hash=hashFromTextureValue(property.getValue());
            if(hash!=null)return hash;
        }
        return defaultTexture();
    }

    private String hashFromTextureValue(String value){
        try{
            String decoded=new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8);
            Matcher matcher=TEXTURE_HASH.matcher(decoded);
            return matcher.find()?matcher.group(1):null;
        }catch(IllegalArgumentException ignored){return null;}
    }

    private String defaultTexture() {
        return plugin.getConfig().getString("tab-integration.default-head-texture",
                "c10591e6909e6a281b371836e462d67a2c78fa0952e910f32b41a26c48c1757c");
    }

    void shutdown() {
        if (cacheTask != null) cacheTask.cancel();
        if (plugin.getServer().getPluginManager().getPlugin("TAB") != null) {
            try {
                var manager = TabAPI.getInstance().getPlaceholderManager();
                if (reloadHandler != null) TabAPI.getInstance().getEventBus().unregister(reloadHandler);
                if (skinPlaceholder != null) manager.unregisterPlaceholder(skinPlaceholder);
                if (factionPlaceholder != null) manager.unregisterPlaceholder(factionPlaceholder);
                if (displayPlaceholder != null) manager.unregisterPlaceholder(displayPlaceholder);
                if (relicPlaceholder != null) manager.unregisterPlaceholder(relicPlaceholder);
            } catch (Throwable ignored) {
            }
        }
        skinHashes.clear();
        factionSuffixes.clear();
        displayNames.clear();
        relicSuffixes.clear();
    }
}
