package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Celebration for the purchases and wagers that are genuinely rare.
 *
 *  A player who has saved for weeks and then spends fifty million on one item should not get the same
 *  quiet chest-close as somebody buying a stack of cobblestone. Two tiers, both configurable:
 *
 *    GRAND      (default 10m+)  a local show -- twin gold helix, expanding rings, firework bursts.
 *    LEGENDARY  (default 50m+)  the same idea taken much further, plus a server-wide announcement so
 *                               everyone online knows it happened.
 *
 *  Deliberately drawn with particles and hand-played sounds rather than real Firework entities: a
 *  detonating firework deals explosion damage to whoever is standing next to it, which is a poor reward
 *  for spending fifty million. Everything here is purely cosmetic and cannot hurt, move or interrupt
 *  anybody.
 *
 *  Sounds are played per listener and honour each player's own sound toggle, and particle density follows
 *  the SPENDER's particle-intensity setting, so somebody running MINIMAL for performance still gets a show
 *  without being buried in it.
 */
final class SpectacleService {
    enum Tier { NONE, GRAND, LEGENDARY }

    private final SMPCore plugin;
    /** One running celebration per player: buying twice in quick succession restarts the show rather than
     *  stacking two animations (and two sound tracks) on top of each other. */
    private final Map<UUID, BukkitTask> running = new HashMap<>();

    SpectacleService(SMPCore plugin) { this.plugin = plugin; }

    void shutdown() { for (BukkitTask task : running.values()) task.cancel(); running.clear(); }

    private boolean enabled() { return plugin.getConfig().getBoolean("celebrations.enabled", true); }

    Tier tierFor(double amount) {
        if (!enabled() || !Double.isFinite(amount) || amount <= 0) return Tier.NONE;
        if (amount >= plugin.getConfig().getDouble("celebrations.legendary-threshold", 50000000)) return Tier.LEGENDARY;
        if (amount >= plugin.getConfig().getDouble("celebrations.grand-threshold", 10000000)) return Tier.GRAND;
        return Tier.NONE;
    }

    /** Money spent on something -- a shop item, an auction listing, an upgrade. */
    void bigSpend(Player player, double amount, String what) { celebrate(player, amount, label(what), "spent"); }

    /** Money staked on a duel. Same spectacle, different verb, because "spent 20m on Asserto" reads wrong. */
    void bigBet(Player player, double amount, String what) { celebrate(player, amount, label(what), "staked"); }

    /** Ledger details arrive as raw keys ("MERCHANT_NETHER_STAR", "SHOP_PURCHASE"). Strip the routing
     *  prefix so the title reads like the thing that was bought rather than like a database column. */
    private String label(String raw) {
        if (raw == null || raw.isBlank()) return "something extravagant";
        String cleaned = raw;
        for (String prefix : new String[]{"MERCHANT_", "SHOP_", "SPAWNER_SHOP_", "LUXURY_", "SINK_", "UPGRADE_"})
            if (cleaned.toUpperCase(Locale.ROOT).startsWith(prefix)) { cleaned = cleaned.substring(prefix.length()); break; }
        return cleaned.equals(cleaned.toUpperCase(Locale.ROOT)) ? CoreUtil.pretty(cleaned) : cleaned;
    }

    private void celebrate(Player player, double amount, String what, String verb) {
        if (player == null || !player.isOnline()) return;
        Tier tier = tierFor(amount);
        if (tier == Tier.NONE) return;
        boolean legendary = tier == Tier.LEGENDARY;

        BukkitTask previous = running.remove(player.getUniqueId());
        if (previous != null) previous.cancel();

        player.showTitle(Title.title(
                Component.text(legendary ? "✦ LEGENDARY ✦" : "✦ GRAND PURCHASE ✦",
                        legendary ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(CoreUtil.money(amount) + " • " + what, NamedTextColor.WHITE),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(legendary ? 2800 : 1800), Duration.ofMillis(700))));

        if (legendary && plugin.getConfig().getBoolean("celebrations.broadcast-legendary", true)) announce(player, amount, what, verb);
        else if (!legendary && plugin.getConfig().getBoolean("celebrations.broadcast-grand", false)) announce(player, amount, what, verb);

        final UUID id = player.getUniqueId();
        final int duration = legendary ? 110 : 65;
        final double density = Math.max(.2, plugin.settings() == null ? 1 : plugin.settings().particleScale(player));
        BukkitTask task = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                Player self = plugin.getServer().getPlayer(id);
                /** A player who logs out or dies mid-show simply ends it; nothing here survives them. */
                if (self == null || !self.isOnline() || t > duration) { running.remove(id); cancel(); return; }
                frame(self, legendary, t, density);
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        running.put(id, task);
    }

    private void announce(Player player, double amount, String what, String verb) {
        boolean legendary = tierFor(amount) == Tier.LEGENDARY;
        Component line = Component.text("✦ ", legendary ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GOLD)
                .append(Component.text(plugin.nicknames().displayName(player), NamedTextColor.WHITE))
                .append(Component.text(" just " + verb + " ", NamedTextColor.GRAY))
                .append(Component.text(CoreUtil.money(amount), NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" on " + what + ".", NamedTextColor.GRAY));
        plugin.getServer().broadcast(line);
        for (Player online : plugin.getServer().getOnlinePlayers())
            if (plugin.settings() == null || plugin.settings().sounds(online))
                online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, .5f, legendary ? .8f : 1.1f);
    }

    // ------------------------------------------------------------------ the animation

    private void frame(Player player, boolean legendary, int t, double density) {
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null) return;

        /** A helix climbing the player: three strands and a taller sweep for legendary, two for grand. */
        int strands = legendary ? 3 : 2;
        double sweep = legendary ? 55.0 : 40.0;
        double climb = (t % sweep) / sweep * (legendary ? 3.4 : 2.6);
        double radius = legendary ? 1.5 : 1.15;
        for (int strand = 0; strand < strands; strand++) {
            double angle = t * .38 + strand * (Math.PI * 2 / strands);
            Location point = base.clone().add(Math.cos(angle) * radius, climb, Math.sin(angle) * radius);
            world.spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.DUST, point, scaled(2, density), .08, .08, .08, 0,
                    new Particle.DustOptions(legendary ? Color.fromRGB(214, 96, 255) : Color.fromRGB(255, 205, 60), legendary ? 1.6f : 1.25f));
        }

        /** Shockwave rings, one per interval, each wider than the last. */
        int ringEvery = legendary ? 7 : 10;
        if (t % ringEvery == 0) {
            double ring = 1 + (t / (double) ringEvery) * (legendary ? 1.3 : 1.0);
            if (ring <= (legendary ? 9 : 5)) ring(world, base, ring, legendary, density);
        }

        /** A column of light for legendary only -- it is what makes the difference visible from across the
         *  base rather than only to the player standing in it. */
        if (legendary && t % 2 == 0)
            for (double y = 0; y < 14; y += .55)
                world.spawnParticle(Particle.ELECTRIC_SPARK, base.clone().add(0, y, 0), scaled(1, density), .14, 0, .14, 0);

        if (legendary && t % 9 == 0) {
            double angle = t * .7;
            burst(world, base.clone().add(Math.cos(angle) * 2.4, 2.6 + (t % 27) / 9.0, Math.sin(angle) * 2.4), true, density);
        } else if (!legendary && t % 16 == 0) {
            burst(world, base.clone().add(0, 2.3, 0), false, density);
        }

        sounds(player, base, legendary, t);
    }

    private void ring(World world, Location centre, double radius, boolean legendary, double density) {
        int points = (int) Math.max(12, radius * 14 * Math.min(1, density));
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2 * i / points;
            Location point = centre.clone().add(Math.cos(angle) * radius, .15, Math.sin(angle) * radius);
            world.spawnParticle(Particle.FIREWORK, point, 1, 0, 0, 0, .02);
            if (legendary) world.spawnParticle(Particle.SOUL_FIRE_FLAME, point, 1, 0, 0, 0, .01);
            else world.spawnParticle(Particle.TOTEM_OF_UNDYING, point, 1, 0, 0, 0, .05);
        }
    }

    private void burst(World world, Location at, boolean legendary, double density) {
        world.spawnParticle(Particle.FIREWORK, at, scaled(legendary ? 90 : 45, density), legendary ? 1.3 : .8, legendary ? 1.3 : .8, legendary ? 1.3 : .8, .16);
        Color colour = legendary
                ? Color.fromRGB(150 + ThreadLocalRandom.current().nextInt(106), 40 + ThreadLocalRandom.current().nextInt(80), 200 + ThreadLocalRandom.current().nextInt(56))
                : Color.fromRGB(255, 180 + ThreadLocalRandom.current().nextInt(60), 40);
        world.spawnParticle(Particle.DUST, at, scaled(legendary ? 70 : 35, density), 1.1, 1.1, 1.1, 0, new Particle.DustOptions(colour, 1.5f));
        if (legendary) {
            world.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.DRAGON_BREATH, at, scaled(24, density), .9, .9, .9, .02);
        }
    }

    private int scaled(int count, double density) { return Math.max(1, (int) Math.round(count * density)); }

    /** Played per listener so each player's own sound toggle is respected -- world.playSound would ignore it. */
    private void sounds(Player owner, Location at, boolean legendary, int t) {
        if (legendary) {
            if (t == 0) {
                play(at, Sound.ENTITY_ENDER_DRAGON_GROWL, .55f, 1.55f);
                play(at, Sound.BLOCK_END_PORTAL_SPAWN, .7f, 1.4f);
                play(at, Sound.EVENT_RAID_HORN, .35f, 1.9f);
            }
            if (t == 6) play(at, Sound.ENTITY_WITHER_SPAWN, .45f, 1.6f);
            if (t == 24) play(at, Sound.BLOCK_CONDUIT_ACTIVATE, .8f, 1.5f);
            if (t == 44) play(at, Sound.ITEM_TOTEM_USE, .7f, 1.3f);
            if (t % 12 == 0) {
                play(at, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, .9f, .9f + (t % 36) / 40f);
                play(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, .8f, 1.2f);
            }
            if (t == 96) play(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, .9f, .85f);
        } else {
            if (t == 0) {
                play(at, Sound.ENTITY_PLAYER_LEVELUP, .9f, 1.5f);
                play(at, Sound.BLOCK_BEACON_ACTIVATE, .8f, 1.7f);
            }
            if (t == 14) play(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, .9f, 1.6f);
            if (t % 16 == 0) {
                play(at, Sound.ENTITY_FIREWORK_ROCKET_BLAST, .85f, 1.2f);
                play(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, .7f, 1.4f);
            }
            if (t == 56) play(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, .8f, 1.1f);
        }
    }

    private void play(Location at, Sound sound, float volume, float pitch) {
        World world = at.getWorld();
        if (world == null) return;
        for (Player listener : world.getPlayers()) {
            if (listener.getLocation().distanceSquared(at) > 48 * 48) continue;
            if (plugin.settings() != null && !plugin.settings().sounds(listener)) continue;
            listener.playSound(at, sound, volume, pitch);
        }
    }
}
