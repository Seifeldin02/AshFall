package net.communitysmp.core;

import com.destroystokyo.paper.entity.villager.Reputation;
import com.destroystokyo.paper.entity.villager.ReputationType;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumMap;
import java.util.Map;

/** Makes cure benefits universal without copying player-specific negative gossip. */
final class VillagerDiscountService implements Listener {
    private final SMPCore plugin;
    private final NamespacedKey cures;

    VillagerDiscountService(SMPCore plugin) {
        this.plugin = plugin;
        this.cures = new NamespacedKey(plugin, "universal_cures");
    }

    int cureCount(Villager villager) {
        return Math.min(5, Math.max(0, villager.getPersistentDataContainer()
                .getOrDefault(cures, PersistentDataType.INTEGER, 0)));
    }

    @EventHandler
    public void cured(EntityTransformEvent event) {
        if (event.getTransformReason() != EntityTransformEvent.TransformReason.CURED
                || event.getTransformedEntity().getType() != EntityType.VILLAGER
                || !(event.getTransformedEntity() instanceof Villager villager)) return;
        int count = Math.min(5, cureCount(villager) + 1);
        villager.getPersistentDataContainer().set(cures, PersistentDataType.INTEGER, count);
        for (Player player : plugin.getServer().getOnlinePlayers()) apply(villager, player);
        if (plugin.netWorth() != null) plugin.netWorth().entityChanged(villager);
    }

    @EventHandler
    public void trade(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Villager villager && cureCount(villager) > 0) {
            apply(villager, event.getPlayer());
        }
    }

    private void apply(Villager villager, Player player) {
        int count = cureCount(villager);
        if (count <= 0) return;
        Reputation current = villager.getReputation(player.getUniqueId());
        Map<ReputationType, Integer> values = new EnumMap<>(ReputationType.class);
        for (ReputationType type : ReputationType.values()) values.put(type, current.getReputation(type));
        values.put(ReputationType.MAJOR_POSITIVE, Math.max(values.getOrDefault(ReputationType.MAJOR_POSITIVE, 0), Math.min(100, count * 20)));
        values.put(ReputationType.MINOR_POSITIVE, Math.max(values.getOrDefault(ReputationType.MINOR_POSITIVE, 0), Math.min(25, count * 5)));
        villager.setReputation(player.getUniqueId(), new Reputation(values));
    }
}
