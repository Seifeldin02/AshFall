package net.communitysmp.core;

import com.destroystokyo.paper.entity.villager.Reputation;
import com.destroystokyo.paper.entity.villager.ReputationType;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
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

    /** Central Bank income from villager trading: $100 minted into the bank for every emerald a completed
     *  player-to-villager trade actually consumes.
     *
     *  PlayerTradeEvent fires ONCE per completed trade -- a shift-click that runs a trade eight times fires
     *  it eight times -- so crediting per event counts emeralds exactly, with no double-count from
     *  multi-trading or from a trade that yields several result items. MONITOR + ignoreCancelled means the
     *  trade genuinely went through. Only emeralds the player SPENDS count: a trade whose result is emeralds
     *  (selling to a villager) consumes none and mints nothing. Wandering traders are excluded on purpose --
     *  the rule is specifically villager trade. The player receives nothing; this is bank income only.
     *
     *  The amount is the emeralds actually paid after reputation discount and demand, not the nominal recipe
     *  price: vanilla folds both into the first ingredient's special price, so the charged first-ingredient
     *  count is base + specialPrice, floored at one. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void tradeIncome(PlayerTradeEvent event) {
        if (!(event.getVillager() instanceof Villager)) return;
        MerchantRecipe recipe = event.getTrade();
        if (recipe == null) return;
        java.util.List<ItemStack> ingredients = recipe.getIngredients();
        int emeralds = 0;
        for (int i = 0; i < ingredients.size(); i++) {
            ItemStack ingredient = ingredients.get(i);
            if (ingredient == null || ingredient.getType() != Material.EMERALD) continue;
            /** Vanilla only special-prices the first ingredient. */
            int charged = i == 0 ? Math.max(1, ingredient.getAmount() + recipe.getSpecialPrice()) : ingredient.getAmount();
            emeralds += charged;
        }
        if (emeralds <= 0) return;
        double perEmerald = plugin.getConfig().getDouble("bank.villager-trade-income-per-emerald", 100);
        double minted = emeralds * perEmerald;
        if (minted > 0 && plugin.bank() != null)
            plugin.bank().creditSink(minted, CoreUtil.id(event.getPlayer()), "VILLAGER_TRADE_" + emeralds + "E");
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
