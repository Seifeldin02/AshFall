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
        /** Prefer the shop's own catalogue name. IRON_GOLEM_SPAWN_EGG is only the KEY under which the Iron
         *  Golem Spawner is listed -- the item delivered is a spawner and always was -- so prettifying the
         *  material announced "spent 50m on Iron Golem Spawn Egg" for something that is not a spawn egg. */
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(cleaned);
        if (material != null && plugin.shop() != null) return plugin.shop().displayName(material);
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
        final int duration = 20 * Math.max(1, Math.min(120, plugin.getConfig().getInt(
                legendary ? "celebrations.legendary-seconds" : "celebrations.grand-seconds", legendary ? 25 : 8)));
        final double density = Math.max(.2, plugin.settings() == null ? 1 : plugin.settings().particleScale(player));
        BukkitTask task = new BukkitRunnable() {
            int t = 0;
            /** Wall-clock backstop, independent of the frame counter. */
            final long deadline = System.currentTimeMillis() + duration * 50L + 5000L;
            @Override public void run() {
                Player self = plugin.getServer().getPlayer(id);
                /** The counter advances BEFORE anything that can throw.
                 *
                 *  A Bukkit repeating task is NOT cancelled by an exception -- it logs and runs again next
                 *  tick -- so an increment placed after the drawing turns any single bad particle into a
                 *  PERMANENT one. That is exactly what happened: Particle.FLASH requires a Color in this
                 *  Paper build, spawning it without one threw on the very first legendary frame, and the
                 *  show ran forever at t=0. 5,832 exceptions and a player who could not stop sparkling.
                 *
                 *  Three independent ways out now: the frame count, the wall clock, and cancel-on-error. */
                int frame = t++;
                if (self == null || !self.isOnline() || frame > duration || System.currentTimeMillis() > deadline) {
                    running.remove(id); cancel(); return;
                }
                try { frame(self, legendary, frame, duration, density); }
                catch (RuntimeException ex) {
                    running.remove(id); cancel();
                    plugin.getLogger().warning("Celebration stopped after an error: " + ex);
                }
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

    /*  Built as ACTS, not as a loop.
     *
     *  The first version drew the same helix every tick, the same ring every 7, the same burst every 9 and
     *  replayed the same two firework sounds every 12 -- forty-odd times over a 25 second show. The opening
     *  landed; everything after it was the opening again, which is what made it feel cheap. Repetition reads
     *  as low quality however good the individual effect is.
     *
     *  So each act has its OWN visual language and hands over to the next, every sound cue fires on exactly
     *  ONE tick (never on a `t % n` timer, which is what produced the machine-gun effect), and the show ends
     *  on a long silent confetti fall that thins out to nothing rather than stopping dead.
     *
     *    LEGENDARY   IMPACT  0-8%    flash and outward shockwaves
     *                ASCENT  8-32%   a rising violet helix with embers climbing it
     *                BLOOM  32-56%   a dome of colour opening overhead, palette shifting as it goes
     *                DRIFT  56-100%  silent falling confetti, thinning to nothing
     *
     *    GRAND       POP     0-18%   one gold burst and a single fast ring
     *                SPIRAL 18-56%   gold twin helix
     *                DRIFT  56-100%  silent falling gold confetti
     *
     *  Act boundaries are FRACTIONS of the configured duration, so changing celebrations.legendary-seconds
     *  restretches the whole show instead of just making the last act longer.
     */

    private static final Color[] LEGENDARY_PALETTE = {
            Color.fromRGB(214, 96, 255), Color.fromRGB(150, 110, 255), Color.fromRGB(120, 210, 255),
            Color.fromRGB(255, 150, 240), Color.fromRGB(245, 235, 255)};
    private static final Color[] GRAND_PALETTE = {
            Color.fromRGB(255, 205, 60), Color.fromRGB(255, 170, 40), Color.fromRGB(255, 235, 150)};

    private Color palette(boolean legendary, double progress) {
        Color[] colours = legendary ? LEGENDARY_PALETTE : GRAND_PALETTE;
        int index = (int) Math.floor(Math.max(0, Math.min(.9999, progress)) * colours.length);
        return colours[Math.min(colours.length - 1, index)];
    }

    private void frame(Player player, boolean legendary, int t, int duration, double density) {
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null) return;
        int span = Math.max(1, duration);

        if (legendary) {
            int impact = (int) (span * .08), ascent = (int) (span * .32), bloom = (int) (span * .56);
            if (t <= impact) impact(world, base, t / (double) Math.max(1, impact), density);
            else if (t <= ascent) ascent(world, base, t - impact, ascent - impact, density);
            else if (t <= bloom) bloom(world, base, t - ascent, bloom - ascent, density);
            else drift(world, base, (t - bloom) / (double) Math.max(1, span - bloom), true, density);

            /** One tick, one cue, never repeated. */
            if (t == 0) {
                play(base, Sound.ENTITY_ENDER_DRAGON_GROWL, .55f, 1.55f);
                play(base, Sound.BLOCK_END_PORTAL_SPAWN, .7f, 1.4f);
                play(base, Sound.EVENT_RAID_HORN, .35f, 1.9f);
            } else if (t == 6) play(base, Sound.ENTITY_WITHER_SPAWN, .45f, 1.6f);
            else if (t == impact + 1) play(base, Sound.BLOCK_CONDUIT_ACTIVATE, .8f, 1.5f);
            else if (t == ascent + 1) play(base, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1f, .9f);
            else if (t == ascent + 12) play(base, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, .8f, 1.2f);
            else if (t == bloom) play(base, Sound.ITEM_TOTEM_USE, .5f, 1.35f);
            /** DRIFT is deliberately silent -- the confetti outlasts the noise on purpose. */
        } else {
            int pop = (int) (span * .18), spiral = (int) (span * .56);
            if (t <= pop) impactGrand(world, base, t / (double) Math.max(1, pop), density);
            else if (t <= spiral) spiral(world, base, t - pop, spiral - pop, density);
            else drift(world, base, (t - spiral) / (double) Math.max(1, span - spiral), false, density);

            if (t == 0) {
                play(base, Sound.ENTITY_PLAYER_LEVELUP, .9f, 1.5f);
                play(base, Sound.BLOCK_BEACON_ACTIVATE, .8f, 1.7f);
            } else if (t == pop + 1) play(base, Sound.BLOCK_AMETHYST_BLOCK_CHIME, .9f, 1.6f);
            else if (t == spiral) play(base, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, .7f, 1.35f);
        }
    }

    /** ACT 1 (legendary): a single flash, then shockwave rings racing outward along the ground. */
    private void impact(World world, Location base, double p, double density) {
        if (p <= 0) {
            particle(world, Particle.FLASH, base.clone().add(0, 1.2, 0), 1, 0, 0, 0, 0, LEGENDARY_PALETTE[0]);
            particle(world, Particle.FIREWORK, base.clone().add(0, 1.2, 0), scaled(120, density), 1.2, 1.2, 1.2, .22);
        }
        ring(world, base, .8 + p * 9, palette(true, p), density, true);
        /** The column only exists during the impact, so it reads as a strike rather than a permanent beam. */
        for (double y = 0; y < 16; y += .6)
            particle(world, Particle.ELECTRIC_SPARK, base.clone().add(0, y, 0), scaled(1, density), .16, 0, .16, 0);
    }

    /** ACT 1 (grand): one warm burst and a single quick ring. Short and punchy. */
    private void impactGrand(World world, Location base, double p, double density) {
        if (p <= 0) particle(world, Particle.FIREWORK, base.clone().add(0, 1.3, 0), scaled(60, density), .9, .9, .9, .18);
        ring(world, base, .8 + p * 4.5, palette(false, p), density, false);
    }

    /** ACT 2 (legendary): a helix that CLIMBS -- widening and rising once, not looping. Embers rise with it. */
    private void ascent(World world, Location base, int k, int length, double density) {
        double p = k / (double) Math.max(1, length);
        double height = p * 5.5, radius = 1.1 + p * 1.4;
        for (int strand = 0; strand < 3; strand++) {
            double angle = k * .34 + strand * (Math.PI * 2 / 3);
            Location point = base.clone().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
            particle(world, Particle.END_ROD, point, 1, 0, 0, 0, 0);
            particle(world, Particle.DUST, point, scaled(2, density), .07, .07, .07, 0,
                    new Particle.DustOptions(palette(true, p), 1.5f));
        }
        /** count 0 turns the offsets into a VELOCITY, which is the only way to make a particle actually
         *  travel. Embers drift upward through the helix instead of hanging in the air. */
        if (k % 3 == 0)
            particle(world, Particle.SOUL_FIRE_FLAME,
                    base.clone().add(ThreadLocalRandom.current().nextDouble(-2, 2), .3, ThreadLocalRandom.current().nextDouble(-2, 2)),
                    0, 0, .18, 0, 1);
    }

    /** ACT 2 (grand): the gold twin helix, rising once. */
    private void spiral(World world, Location base, int k, int length, double density) {
        double p = k / (double) Math.max(1, length);
        for (int strand = 0; strand < 2; strand++) {
            double angle = k * .38 + strand * Math.PI;
            Location point = base.clone().add(Math.cos(angle) * 1.15, p * 3.2, Math.sin(angle) * 1.15);
            particle(world, Particle.END_ROD, point, 1, 0, 0, 0, 0);
            particle(world, Particle.DUST, point, scaled(2, density), .07, .07, .07, 0,
                    new Particle.DustOptions(palette(false, p), 1.25f));
        }
    }

    /** ACT 3 (legendary): a dome opening overhead like a firework shell, colour shifting as it expands.
     *  Drawn as a growing hemisphere rather than a flat ring, so it reads as a different effect entirely
     *  from the shockwaves in act one. */
    private void bloom(World world, Location base, int k, int length, double density) {
        double p = k / (double) Math.max(1, length);
        double radius = .5 + p * 7;
        Location centre = base.clone().add(0, 3.2, 0);
        int rings = 4;
        for (int r = 0; r < rings; r++) {
            double lat = (Math.PI / 2) * (r / (double) rings);
            double y = Math.sin(lat) * radius, ringRadius = Math.cos(lat) * radius;
            int points = (int) Math.max(6, ringRadius * 6 * Math.min(1, density));
            for (int i = 0; i < points; i++) {
                double angle = Math.PI * 2 * i / points + k * .05;
                Location point = centre.clone().add(Math.cos(angle) * ringRadius, y, Math.sin(angle) * ringRadius);
                particle(world, Particle.DUST, point, 1, 0, 0, 0, 0, new Particle.DustOptions(palette(true, p), 1.4f));
            }
        }
        if (k % 6 == 0) particle(world, Particle.DRAGON_BREATH, centre, scaled(10, density), 1.4, .6, 1.4, .01, 1.0f);
    }

    /** FINAL ACT, both tiers: confetti falling in silence, thinning out until there is nothing left.
     *  The show fades rather than stopping, which is the difference between "it ended" and "it was cut off". */
    private void drift(World world, Location base, double p, boolean legendary, double density) {
        double remaining = Math.max(0, 1 - p);
        int pieces = (int) Math.round((legendary ? 7 : 4) * remaining * Math.min(1, density));
        double spread = legendary ? 4.5 : 3;
        for (int i = 0; i < pieces; i++) {
            Location point = base.clone().add(
                    ThreadLocalRandom.current().nextDouble(-spread, spread),
                    ThreadLocalRandom.current().nextDouble(1.5, legendary ? 7 : 5),
                    ThreadLocalRandom.current().nextDouble(-spread, spread));
            particle(world, Particle.DUST, point, 1, .04, .04, .04, 0,
                    new Particle.DustOptions(palette(legendary, ThreadLocalRandom.current().nextDouble()), 1.35f));
            /** A few pieces given real downward motion so the cloud visibly falls. */
            if (i % 3 == 0) particle(world, Particle.FIREWORK, point, 0, 0, -.08, 0, 1);
        }
        if (legendary && pieces > 0 && ThreadLocalRandom.current().nextInt(14) == 0)
            particle(world, Particle.TOTEM_OF_UNDYING, base.clone().add(0, 2.2, 0), scaled(3, density), 1.2, .8, 1.2, .04);
    }

    /** Spawns a particle with whatever data the API says it needs.
     *
     *  Paper particles differ in whether they REQUIRE a data object and which class it is, and that can
     *  change between versions: FLASH takes a Color here and DRAGON_BREATH takes a Float, and spawning
     *  either bare threw. Rather than hard-coding today's answer at every call site, ask getDataType() and
     *  supply something it accepts. A particle whose data we cannot produce is skipped rather than thrown --
     *  a missing sparkle is not worth an exception on the main thread.
     *
     *  NOTE: a count of 0 is meaningful and must be passed through untouched. Vanilla then treats the
     *  offsets as a VELOCITY vector, which is the only way to make a particle actually travel (used by the
     *  falling confetti and the rising embers). */
    private void particle(World world, Particle particle, Location at, int count, double dx, double dy, double dz, double extra, Object data) {
        Class<?> required = particle.getDataType();
        if (required == null || required == Void.class) { world.spawnParticle(particle, at, count, dx, dy, dz, extra); return; }
        if (data != null && required.isInstance(data)) { world.spawnParticle(particle, at, count, dx, dy, dz, extra, data); return; }
        if (required == Color.class) { world.spawnParticle(particle, at, count, dx, dy, dz, extra, Color.fromRGB(255, 225, 140)); return; }
        if (required == Particle.DustOptions.class) { world.spawnParticle(particle, at, count, dx, dy, dz, extra, new Particle.DustOptions(Color.fromRGB(255, 205, 60), 1.4f)); return; }
        if (required == Float.class) { world.spawnParticle(particle, at, count, dx, dy, dz, extra, 1.0f); return; }
    }

    private void particle(World world, Particle particle, Location at, int count, double dx, double dy, double dz, double extra) {
        particle(world, particle, at, count, dx, dy, dz, extra, null);
    }

    /** Asserts that every particle this service uses is one we can actually supply data for, so a Paper
     *  version that starts REQUIRING data for one of them fails a self-test instead of becoming a permanent
     *  particle storm on somebody's screen. It has already earned this once: it caught DRAGON_BREATH needing
     *  a Float immediately after FLASH was fixed for needing a Color. */
    boolean selfTest() {
        for (Particle particle : new Particle[]{Particle.END_ROD, Particle.DUST, Particle.FIREWORK, Particle.TOTEM_OF_UNDYING,
                Particle.SOUL_FIRE_FLAME, Particle.ELECTRIC_SPARK, Particle.FLASH, Particle.DRAGON_BREATH}) {
            Class<?> required = particle.getDataType();
            if (required == null || required == Void.class || required == Color.class
                    || required == Particle.DustOptions.class || required == Float.class) continue;
            return false;
        }
        /** Act boundaries must stay in order, or a phase would be skipped entirely. */
        for (int seconds : new int[]{plugin.getConfig().getInt("celebrations.grand-seconds", 8),
                                     plugin.getConfig().getInt("celebrations.legendary-seconds", 25)}) {
            int span = seconds * 20;
            if (seconds <= 0) return false;
            if (!((int) (span * .08) <= (int) (span * .18) && (int) (span * .32) < (int) (span * .56) && (int) (span * .56) < span)) return false;
        }
        return true;
    }

    /** A flat ring on the ground at the given radius. */
    private void ring(World world, Location centre, double radius, Color colour, double density, boolean legendary) {
        int points = (int) Math.max(10, radius * 12 * Math.min(1, density));
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2 * i / points;
            Location point = centre.clone().add(Math.cos(angle) * radius, .15, Math.sin(angle) * radius);
            particle(world, Particle.FIREWORK, point, 1, 0, 0, 0, .02);
            particle(world, Particle.DUST, point, 1, 0, 0, 0, 0, new Particle.DustOptions(colour, legendary ? 1.5f : 1.3f));
        }
    }

    private int scaled(int count, double density) { return Math.max(1, (int) Math.round(count * density)); }

    /** Played per listener so each player's own sound toggle is respected -- world.playSound would ignore it. */
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
