package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** The Task Master event: a contract board carried by a hidden courier.
 *
 *  Every task is "bring me N of X". That is deliberate rather than a limitation: delivery tasks are checked
 *  by reading the player's inventory at turn-in, so the whole event needs no kill/mine/break listeners, no
 *  per-player progress counters ticking in the background, and no progress state to persist or reconcile
 *  across a restart. It also matches how the event reads in play -- you take a contract, go and get the
 *  goods, and come back.
 *
 *  Each player draws their own hand of contracts from the table, so two people standing at the same trader
 *  are not racing for the same objective. Rewards scale with the tier of the individual task.
 *
 *  The courier is a real WanderingTrader so it walks around naturally, but it is made invisible, silent and
 *  invulnerable and its vanilla trading GUI is suppressed -- right-clicking opens the contract board
 *  instead. It is tagged in persistent data so it can always be found and cleaned up, including after a
 *  restart that happened mid-event. */
final class TaskMasterService implements Listener {

    /** A single contract.
     *
     *  There is deliberately no reward field. Pay is DERIVED from how much work the job is, never from what
     *  the item is worth -- a stack of bricks is cheap and tedious and pays for the tedium; a wither skull
     *  is worth a fortune to a collector and pays for the fortress, not the price tag. Four honest factors,
     *  each scored 0-3:
     *
     *    steps  how much processing stands between raw materials and the thing being asked for
     *    travel 0 anywhere, 1 a particular biome or structure, 2 the Nether, 3 the End or a monument
     *    risk   0 safe, 1 hostile mobs, 2 somewhere that kills the careless, 3 somewhere that kills anyone
     *    grind  how much repetition or luck, from one-and-done to farming a rare drop
     *
     *  Because pay is a pure function of those four, two jobs that are equally hard pay the same no matter
     *  what they are made of, which is the entire point. selfTest pins that. */
    private record Task(String id, String display, Material icon, List<Material> items, int each,
                        int perItem, int steps, int travel, int risk, int grind, String flavour) {
        /** A collection contract adds the cost of every extra supply chain it drags in. perItem is what one
         *  more entry on the list actually costs you: cheap for wool, where the sixteenth is one more dye;
         *  dear for beds, where it is three more wool plus planks plus a craft. Single-item jobs carry
         *  perItem 0, so the term vanishes and nothing about the ordinary board changes. */
        int effort() { return steps * 2 + travel * 3 + risk * 3 + grind * 3 + (items.size() - 1) * perItem; }
        /** Rounded to the nearest hundred so the board reads like a person quoting a price. */
        double reward() { return Math.max(1500, Math.round((effort() * 1250 - 2500) / 100.0) * 100); }
        String tier() {
            int e = effort();
            return e < 9 ? "Easy" : e < 14 ? "Testing" : e < 22 ? "Hard" : e < 60 ? "Brutal" : "Legendary";
        }
        /** What the contract card is titled. Single jobs describe themselves; sets get a written name. */
        String label() {
            return display.isEmpty() ? each + "x " + CoreUtil.pretty(items.get(0).name()) : display;
        }
        boolean isSet() { return items.size() > 1; }
        /** The one or two things that actually make this job hard, for the contract card. */
        List<String> why() {
            List<String> tags = new ArrayList<>();
            if (travel >= 3) tags.add("The End or the deep ocean");
            else if (travel == 2) tags.add("Requires the Nether");
            else if (travel == 1) tags.add("Requires travel");
            if (risk >= 3) tags.add("Genuinely dangerous");
            else if (risk == 2) tags.add("Hostile ground");
            if (isSet()) tags.add(0, items.size() + " separate things to source");
            if (grind >= 3) tags.add("Rare drop");
            if (steps >= 3) tags.add("Multi-stage craft");
            return tags.size() > 2 ? tags.subList(0, 2) : tags;
        }
    }

    /** An ordinary one-item job. */
    private static Task one(String id, Material material, int amount,
                            int steps, int travel, int risk, int grind, String flavour) {
        return new Task(id, "", material, List.of(material), amount, 0, steps, travel, risk, grind, flavour);
    }

    /** A collection: `each` of EVERY item on the list, or it does not count. */
    private static Task set(String id, String display, Material icon, List<Material> items, int each,
                            int perItem, int steps, int travel, int risk, int grind, String flavour) {
        return new Task(id, display, icon, items, each, perItem, steps, travel, risk, grind, flavour);
    }

    private static final List<Material> WOOL = List.of(
            Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL, Material.LIGHT_BLUE_WOOL,
            Material.YELLOW_WOOL, Material.LIME_WOOL, Material.PINK_WOOL, Material.GRAY_WOOL,
            Material.LIGHT_GRAY_WOOL, Material.CYAN_WOOL, Material.PURPLE_WOOL, Material.BLUE_WOOL,
            Material.BROWN_WOOL, Material.GREEN_WOOL, Material.RED_WOOL, Material.BLACK_WOOL);
    private static final List<Material> BEDS = List.of(
            Material.WHITE_BED, Material.ORANGE_BED, Material.MAGENTA_BED, Material.LIGHT_BLUE_BED,
            Material.YELLOW_BED, Material.LIME_BED, Material.PINK_BED, Material.GRAY_BED,
            Material.LIGHT_GRAY_BED, Material.CYAN_BED, Material.PURPLE_BED, Material.BLUE_BED,
            Material.BROWN_BED, Material.GREEN_BED, Material.RED_BED, Material.BLACK_BED);

    private record Holder(UUID trader) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    /** The contract board.
     *
     *  Every job is still a delivery, which is what keeps this event free of kill/mine listeners, background
     *  counters and progress that would have to survive a restart -- turn-in simply reads your inventory.
     *  What changed is what gets asked for. These are jobs with a reason behind them rather than "bring me
     *  64 of a thing", and most of them are a production chain, a trip or a fight wearing a delivery as a
     *  receipt. A cake is four farms in a trench coat.
     *
     *  Columns after the amount are steps / travel / risk / grind -- see Task. Pay falls out of those, so
     *  the tedious cheap jobs and the terrifying valuable ones are both priced for what they cost you. */
    private static final List<Task> TABLE = List.of(
        // ------------------------------------------------------------------------------------ Easy
        one("cookie", Material.COOKIE,                64, 2,0,0,0, "Sixty-four cookies. They are for me. Do not make it strange."),
        one("ladder", Material.LADDER,                64, 1,0,0,1, "Sixty-four ladders. The last crew sank a shaft and then forgot how to leave it."),
        one("charcoal", Material.CHARCOAL,              48, 1,0,0,1, "Forty-eight charcoal. Wood in, fire out, and no, I will not take coal instead."),
        one("bread", Material.BREAD,                 32, 1,0,0,1, "The road crews eat before they dig. Thirty-two loaves, still warm if you can manage it."),
        one("pie", Material.PUMPKIN_PIE,           24, 2,0,0,1, "Twenty-four pumpkin pies. The harvest festival will not feed itself."),
        one("hay", Material.HAY_BLOCK,             16, 1,0,0,2, "Sixteen bales. The horses are unmoved by promises."),
        // --------------------------------------------------------------------------------- Testing
        one("cake", Material.CAKE,                   3, 3,0,0,1, "Somebody's daughter turns nine. Three cakes, and do not ask me to explain the milk."),
        one("lantern", Material.LANTERN,               16, 2,0,1,1, "Sixteen lanterns for the tunnel. I have lost two crews to the dark already."),
        one("target", Material.TARGET,                16, 2,0,0,2, "Sixteen targets for the range. The recruits keep missing the wall entirely."),
        one("smooth", Material.SMOOTH_STONE,         128, 2,0,0,2, "A hundred and twenty-eight smooth stone. Twice through the furnace. I will know."),
        one("shelf", Material.BOOKSHELF,             12, 3,0,0,2, "The scribes want shelves. Twelve of them. They will not say what for."),
        one("spyglass", Material.SPYGLASS,               2, 3,1,0,1, "Two spyglasses. One for the lookout, one for when the lookout drops the first."),
        one("amethyst", Material.AMETHYST_SHARD,        24, 0,1,1,2, "Twenty-four amethyst shards. Listen for the chiming, and mind the drop."),
        one("candle", Material.CANDLE,                32, 2,1,1,1, "Thirty-two candles for a vigil. Do not ask whose."),
        one("brick", Material.BRICKS,                64, 2,1,0,2, "Sixty-four bricks. Clay, fire and patience. Mostly patience."),
        one("carrot", Material.GOLDEN_CARROT,         32, 2,0,1,2, "Thirty-two golden carrots. The night watch swears by them and I am not paying for excuses."),
        // ------------------------------------------------------------------------------------ Hard
        one("ice", Material.PACKED_ICE,            64, 1,2,0,2, "Sixty-four packed ice, and it had better not arrive as water."),
        one("glowstone", Material.GLOWSTONE,             24, 0,2,2,1, "Glowstone. Twenty-four. Yes, from over there. No, I will not come with you."),
        one("honey", Material.HONEY_BOTTLE,          16, 2,1,1,2, "Sixteen bottles of honey. Bring a campfire, and bring your nerve."),
        one("blaze", Material.BLAZE_ROD,             16, 0,2,3,2, "Sixteen rods that keep burning. I have a client who insists."),
        one("echo", Material.ECHO_SHARD,             6, 0,2,3,2, "Six echo shards. Quietly. I mean that literally."),
        one("totem", Material.TOTEM_OF_UNDYING,       1, 0,1,3,3, "A totem. Walk into a raid, walk back out, and bring me the thing that let you."),
        // ---------------------------------------------------------------------------------- Brutal
        one("skull", Material.WITHER_SKELETON_SKULL,  1, 0,2,3,3, "One skull. Black bone, hollow eyes. I will not tell you what it is for, and you will not want to know."),
        one("anchor", Material.RESPAWN_ANCHOR,         2, 3,2,2,2, "Two anchors. If you have to ask why I want them charged over there, do not take the job."),
        one("shell", Material.SHULKER_SHELL,          4, 0,3,3,2, "Four shulker shells. Boxes do not build themselves and neither, apparently, does my patience."),
        one("sealantern", Material.SEA_LANTERN,            8, 1,3,3,2, "Eight sea lanterns. The guardians will object. Object back."),
        one("netherite", Material.NETHERITE_INGOT,        1, 3,2,2,3, "One netherite ingot. I know exactly what I am asking. That is why the purse is what it is."),
        one("conduit", Material.CONDUIT,                1, 3,3,2,3, "One conduit. A heart and eight shells. Come back damp."),
        one("heart", Material.HEART_OF_THE_SEA,       2, 0,3,1,3, "Two hearts of the sea. Find the wreck, read the map, dig where it says. Simple, he said."),
        one("nautilus", Material.NAUTILUS_SHELL,        16, 0,2,2,3, "Sixteen nautilus shells. The drowned have them. The drowned would rather keep them."),
        one("gapple", Material.ENCHANTED_GOLDEN_APPLE, 2, 0,2,3,3, "Two notched apples. They are not crafted, only found, and only where nobody sensible goes."),
        one("rose", Material.WITHER_ROSE,            8, 0,2,3,3, "Eight wither roses. You know what has to happen for one of these to grow. Eight times."),
        one("breath", Material.DRAGON_BREATH,          8, 1,3,3,3, "Eight bottles of dragon's breath. Stand in it, hold out a bottle, try to stay standing."),
        // -------------------------------------------------------------------------------- Collections
        set("coral", "Every coral, 2 of each", Material.BRAIN_CORAL_BLOCK,
            List.of(Material.TUBE_CORAL_BLOCK, Material.BRAIN_CORAL_BLOCK, Material.BUBBLE_CORAL_BLOCK,
                    Material.FIRE_CORAL_BLOCK, Material.HORN_CORAL_BLOCK), 2, 5, 1,2,1,2,
            "Two of every coral, still coloured. Bring the touch that keeps them alive or do not bother."),
        set("froglight", "Every froglight, 4 of each", Material.PEARLESCENT_FROGLIGHT,
            List.of(Material.OCHRE_FROGLIGHT, Material.VERDANT_FROGLIGHT, Material.PEARLESCENT_FROGLIGHT), 4, 8, 1,2,2,3,
            "All three froglights. Yes, that means the right frogs eating the wrong cubes in the wrong dimension."),
        set("discs", "Five music discs", Material.JUKEBOX,
            List.of(Material.MUSIC_DISC_13, Material.MUSIC_DISC_CAT, Material.MUSIC_DISC_BLOCKS,
                    Material.MUSIC_DISC_CHIRP, Material.MUSIC_DISC_FAR), 1, 6, 0,1,2,3,
            "Five discs, these five, no substitutions. Let the skeletons argue with the creepers about it."),
        set("woolset", "Wool, all 16 colours", Material.WHITE_WOOL, WOOL, 1, 4, 2,2,1,3,
            "One of every colour of wool. Sixteen dyes, and there is no shortcut for the last three."),
        set("bedset", "Beds, all 16 colours", Material.RED_BED, BEDS, 1, 9, 3,2,1,3,
            "One bed in every colour. Forty-eight wool, the planks, and the patience of a saint. Name your price -- I already did.")
    );

    /** What the courier says when a contract lands. The generic line was the same sentence 36 times,
     *  which made every delivery feel like the same delivery. */
    private static final Map<String, String> DELIVERED = Map.ofEntries(
        Map.entry("cookie",     "He eats one immediately and does not look sorry."),
        Map.entry("ladder",     "The crew in the shaft will be delighted. Eventually."),
        Map.entry("charcoal",   "He sniffs it, nods once, and writes something down."),
        Map.entry("bread",      "Still warm. He notices, and says nothing, which is his way of saying thank you."),
        Map.entry("pie",        "The festival is saved. He does not say by whom."),
        Map.entry("hay",        "Somewhere, a horse stops complaining."),
        Map.entry("cake",       "He checks all three for fingerprints in the icing."),
        Map.entry("lantern",    "The tunnel gets its light. The dark gets nothing."),
        Map.entry("target",     "The recruits may now miss something purpose-built."),
        Map.entry("smooth",     "He runs a thumb across one. Twice-fired. He can tell."),
        Map.entry("shelf",      "The scribes take them without explaining. They never do."),
        Map.entry("spyglass",   "He pockets the second one before the lookout can see it."),
        Map.entry("amethyst",   "They chime faintly in the crate. He listens for a moment."),
        Map.entry("candle",     "He counts them twice, and does not say whose vigil it is."),
        Map.entry("brick",      "Clay, fire, and patience. He appreciates all three."),
        Map.entry("carrot",     "The night watch will sleep better. Or rather, less."),
        Map.entry("ice",        "Not a drop. He is visibly impressed and hides it badly."),
        Map.entry("glowstone",  "He does not ask how it went. Your eyebrows answer for you."),
        Map.entry("honey",      "No stings that he can see. He checks again."),
        Map.entry("blaze",      "The client will be pleased. He still will not say who."),
        Map.entry("echo",       "He handles them quietly, as though they might hear him."),
        Map.entry("totem",      "You walked out of a raid holding this. He knows what that means."),
        Map.entry("heart",      "Two wrecks, two maps, two holes in a beach. Worth it."),
        Map.entry("nautilus",   "Prised from the drowned, one at a time. He does not envy you."),
        Map.entry("gapple",     "He wraps both immediately, as if they might be noticed."),
        Map.entry("rose",       "He takes the crate at arm's length and thanks you very briefly."),
        Map.entry("breath",     "Bottled mid-roar. He turns one to the light and whistles."),
        Map.entry("skull",      "He does not look at it. He signs, and slides the purse across."),
        Map.entry("anchor",     "Charged, both of them. He asks nothing further, as promised."),
        Map.entry("shell",      "Four shells and no explanation of the trip. Sensible."),
        Map.entry("sealantern", "You objected back, then. He raises a glass to the guardians."),
        Map.entry("netherite",  "He weighs it in one hand and pays without haggling."),
        Map.entry("conduit",    "Still damp. He grins for the first time all week."),
        Map.entry("coral",      "Every colour, alive. He keeps it in water while he counts."),
        Map.entry("froglight",  "Three lights, three frogs, one very confused ecosystem."),
        Map.entry("discs",      "He reads the labels, all five, and finally stops arguing."),
        Map.entry("woolset",    "Sixteen colours laid out in order. He steps back to look at them."),
        Map.entry("bedset",     "Sixteen beds. He said name your price, and he has paid it."));

    /** One player's current contracts and what they have already turned in.
     *
     *  A batch is fixed once dealt: it survives relogs and restarts, and it does not change one contract at
     *  a time. The next batch is only dealt when every contract in this one is done. */
    private record Batch(List<Task> tasks, List<String> done) {}

    private final SMPCore plugin;
    private UUID traderId;
    /** Where the courier was placed. Kept so the announcement quotes the spot he is actually standing on,
     *  and so he can be put back if something removes him while the event is still running. */
    private Location home;
    private long endsAt;
    private final Map<UUID, Batch> batches = new HashMap<>();

    TaskMasterService(SMPCore plugin) { this.plugin = plugin; }

    private org.bukkit.NamespacedKey markerKey() { return new org.bukkit.NamespacedKey(plugin, "task_master"); }

    boolean isTaskMaster(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(markerKey(), PersistentDataType.BYTE);
    }

    /** Spawns the courier and opens the event. Any leftover courier is cleared first, so a restart that
     *  happened mid-event cannot leave a second one standing.
     *
     *  Returns where he actually ended up, which is not necessarily the location handed in -- the caller
     *  announces THAT, so the coordinates players are given are the coordinates he is standing on. */
    Location begin(Location center, long endsAtMillis) {
        end();
        endsAt = endsAtMillis;
        if (center == null || center.getWorld() == null) return center;
        home = surface(center);
        spawnCourier();
        return home;
    }

    /** Puts the courier on top of the world rather than wherever the event centre happened to land.
     *
     *  getHighestBlockYAt is the terrain surface by definition, so this cannot leave him in a cave or
     *  buried in a hillside -- the old spawn used the raw event centre and could do both. Water and lava
     *  columns are stepped around by sampling a short ring of nearby spots; the search is a couple of dozen
     *  fixed samples, not a scan. */
    private Location surface(Location center) {
        World world = center.getWorld();
        int[] offsets = {0, 4, -4, 8, -8, 12, -12, 16, -16, 20, -20, 24, -24};
        for (int dx : offsets) for (int dz : offsets) {
            int x = center.getBlockX() + dx, z = center.getBlockZ() + dz;
            int y = world.getHighestBlockYAt(x, z);
            Material ground = world.getBlockAt(x, y, z).getType();
            if (ground == Material.WATER || ground == Material.LAVA || ground == Material.POWDER_SNOW) continue;
            if (!world.getBlockAt(x, y + 1, z).isPassable() || !world.getBlockAt(x, y + 2, z).isPassable()) continue;
            return new Location(world, x + .5, y + 1, z + .5);
        }
        return new Location(world, center.getBlockX() + .5,
                world.getHighestBlockYAt(center.getBlockX(), center.getBlockZ()) + 1, center.getBlockZ() + .5);
    }

    /** Invisible, and reading like a wandering trader who has drunk an invisibility potion: the swirl of
     *  effect particles is emitted directly rather than left to a potion effect.
     *
     *  addPotionEffect was tried first and does not stick on this entity -- active_effects came back empty
     *  in staging both from inside the spawn consumer and immediately after the spawn, while /effect give
     *  on the same trader populated it fine. Rather than ship something depending on a call that is being
     *  dropped somewhere below the API, the invisibility flag is set directly and the swirl is drawn by us.
     *  Same look, and nothing between us and the result.
     *
     *  The swirl matters: it is the only thing marking him. An invisible courier with a hidden name and no
     *  particles is nothing at all to find, which is exactly what went wrong before.
     *
     *  He keeps his AI and wanders normally; tick() tethers him to LEASH blocks of where he was placed so
     *  he stays findable in the announced area instead of walking off across the world. */
    private void spawnCourier() {
        if (home == null || home.getWorld() == null) return;
        WanderingTrader trader = home.getWorld().spawn(home, WanderingTrader.class,
                org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, t -> {
            t.setSilent(true);
            t.setInvulnerable(true);
            t.setPersistent(true);
            t.setRemoveWhenFarAway(false);
            t.setDespawnDelay(Integer.MAX_VALUE);
            t.customName(Component.text("The Task Master", NamedTextColor.LIGHT_PURPLE));
            t.setCustomNameVisible(false);
            t.setInvisible(true);
            t.getPersistentDataContainer().set(markerKey(), PersistentDataType.BYTE, (byte) 1);
        });
        traderId = trader.getUniqueId();
        startSwirl();
        plugin.getLogger().info("[TaskMaster] courier placed at " + home.getWorld().getName() + " "
                + home.getBlockX() + " " + home.getBlockY() + " " + home.getBlockZ()
                + " (surface " + home.getWorld().getHighestBlockYAt(home.getBlockX(), home.getBlockZ()) + ")");
    }

    /** How far the courier may wander from where he was placed. */
    private static final double LEASH = 50;
    /** The colour a vanilla invisibility potion tints its particles. */
    private static final org.bukkit.Color SWIRL = org.bukkit.Color.fromRGB(0x7F, 0x83, 0x92);
    private org.bukkit.scheduler.BukkitTask swirl;

    /** Draws the effect swirl around the courier twice a second while he exists. Costs nothing while his
     *  chunk is unloaded, and stops the moment the event ends. */
    private void startSwirl() {
        stopSwirl();
        swirl = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (traderId == null || home == null || home.getWorld() == null) return;
            if (!home.getWorld().isChunkLoaded(home.getBlockX() >> 4, home.getBlockZ() >> 4)) return;
            Entity courier = plugin.getServer().getEntity(traderId);
            if (courier == null || !courier.isValid()) return;
            /** The invisibility flag is synced entity state, NOT saved NBT -- verified in staging, where a
             *  reloaded courier came back visible. Re-asserting it here is what makes it survive a chunk
             *  unload, a reload, and a restart. */
            if (!courier.isInvisible()) courier.setInvisible(true);
            courier.getWorld().spawnParticle(org.bukkit.Particle.ENTITY_EFFECT,
                    courier.getLocation().add(0, 1.1, 0), 6, .28, .55, .28, 1, SWIRL);
        }, 10L, 10L);
    }

    private void stopSwirl() { if (swirl != null) { swirl.cancel(); swirl = null; } }

    /** Called on the event tick. Keeps the courier present without any scanning: it only ever looks up the
     *  one entity it spawned, by id. */
    void tick() {
        /** Expiry is checked BEFORE the courier is looked up. It used to be the other way round, which meant
         *  an event whose courier sat in an unloaded chunk could never reach its own end time. */
        if (System.currentTimeMillis() > endsAt) { end(); return; }
        if (traderId == null || home == null) return;
        if (!home.getWorld().isChunkLoaded(home.getBlockX() >> 4, home.getBlockZ() >> 4)) return;
        Entity trader = plugin.getServer().getEntity(traderId);
        /** Loaded chunk and no courier means something removed him. Put him back rather than leaving the
         *  event running with nothing to walk up to. */
        if (trader == null || !trader.isValid()) { spawnCourier(); return; }
        tether(trader);
    }

    /** Keeps the courier inside his announced area without taking his AI away.
     *
     *  Nudged home once he drifts past four fifths of the leash, and pulled back outright past the leash
     *  itself. The event ticker runs every five seconds and a wandering trader covers only a couple of
     *  blocks in that time, so he can never get meaningfully beyond 50 before being corrected. */
    private void tether(Entity trader) {
        if (!trader.getWorld().equals(home.getWorld())) { trader.teleport(home); return; }
        double away = trader.getLocation().distanceSquared(home);
        if (away > LEASH * LEASH) { trader.teleport(surface(home)); return; }
        if (away > (LEASH * .8) * (LEASH * .8) && trader instanceof org.bukkit.entity.Mob mob)
            mob.getPathfinder().moveTo(home, 1.0);
    }

    /** Where the courier is standing, for the event's own status lines. */
    Location location() { return home; }

    void end() {
        if (traderId != null) {
            Entity trader = plugin.getServer().getEntity(traderId);
            if (trader != null) trader.remove();
            /** Belt and braces for a courier orphaned by a restart: only ever checks LOADED worlds and only
             *  entities already in memory, so this is not a world scan. */
            for (org.bukkit.World world : plugin.getServer().getWorlds())
                for (Entity entity : world.getEntitiesByClass(WanderingTrader.class))
                    if (isTaskMaster(entity)) entity.remove();
        }
        stopSwirl();
        traderId = null;
        home = null;
        batches.clear();
    }

    boolean active() { return traderId != null; }

    /** Right-clicking the courier. Returns true when handled, so the caller can cancel the vanilla trade. */
    boolean interact(Player player) {
        if (!active()) return false;
        batchFor(player);
        open(player);
        return true;
    }

    private String stateKey(Player player) { return "taskmaster:" + CoreUtil.id(player); }

    /** The player's batch: from memory, else from the database, else freshly dealt.
     *
     *  Stored against the event's end time, so a batch belongs to the event it was dealt in and a new event
     *  starts everybody clean without needing to hunt down old rows. */
    private Batch batchFor(Player player) {
        Batch batch = batches.get(player.getUniqueId());
        if (batch != null) return batch;
        batch = load(player);
        if (batch == null) batch = new Batch(new ArrayList<>(draw()), new ArrayList<>());
        batches.put(player.getUniqueId(), batch);
        save(player, batch);
        return batch;
    }

    private void save(Player player, Batch batch) {
        StringBuilder ids = new StringBuilder();
        for (Task task : batch.tasks()) ids.append(ids.length() == 0 ? "" : ",").append(task.id());
        plugin.db().state(stateKey(player), endsAt + ";" + ids + ";" + String.join(",", batch.done()));
    }

    private Batch load(Player player) {
        String raw = plugin.db().state(stateKey(player));
        if (raw == null) return null;
        String[] parts = raw.split(";", -1);
        if (parts.length < 3) return null;
        try {
            if (Long.parseLong(parts[0]) != endsAt) return null;
        } catch (NumberFormatException ignored) { return null; }
        List<Task> tasks = new ArrayList<>();
        for (String id : parts[1].split(",")) {
            String wanted = id.trim();
            for (Task task : TABLE) if (task.id().equals(wanted)) { tasks.add(task); break; }
        }
        if (tasks.isEmpty()) return null;
        List<String> done = new ArrayList<>();
        for (String id : parts[2].split(",")) if (!id.isBlank()) done.add(id.trim());
        return new Batch(tasks, done);
    }

    /** A fresh hand of 3-4 contracts: one Easy, one Testing, one Hard, and a Brutal three times in five.
     *
     *  Drawn a band at a time rather than at random across the whole table, so a hand always holds
     *  something you could turn in today and something worth setting out for. Shuffling blind can deal
     *  somebody four jobs in the End, which is not a board, it is a wall. */
    private List<Task> draw() {
        List<Task> hand = new ArrayList<>();
        for (String band : List.of("Easy", "Testing", "Hard")) pickFrom(band, hand);
        /** The fourth slot: a Legendary collection one time in five, a Brutal three, nothing the last.
         *  Legendary is deliberately scarce -- an all-sixteen-colours contract should feel like an event in
         *  its own right, not the thing on every board. */
        int roll = ThreadLocalRandom.current().nextInt(5);
        if (roll == 0) pickFrom("Legendary", hand);
        else if (roll < 4) pickFrom("Brutal", hand);
        Collections.shuffle(hand);
        return List.copyOf(hand);
    }

    private void pickFrom(String band, List<Task> hand) {
        List<Task> options = TABLE.stream().filter(task -> task.tier().equals(band)).toList();
        if (!options.isEmpty()) hand.add(options.get(ThreadLocalRandom.current().nextInt(options.size())));
    }

    private void open(Player player) {
        Batch batch = batchFor(player);
        List<Task> tasks = batch.tasks();
        List<String> done = batch.done();
        Inventory inv = plugin.getServer().createInventory(new Holder(traderId), 27,
                Component.text("Task Master • Contracts", NamedTextColor.DARK_PURPLE));
        int slot = 11;
        for (Task task : tasks) {
            boolean claimed = done.contains(task.id());
            ItemStack icon = new ItemStack(task.icon());
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text((claimed ? "✔ " : "") + task.label(),
                    claimed ? NamedTextColor.DARK_GRAY : NamedTextColor.GOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(task.flavour(), NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(Component.text("Difficulty: " + task.tier(), NamedTextColor.DARK_GRAY));
            /** Say WHY it pays what it pays, so the board never reads as arbitrary. */
            for (String reason : task.why()) lore.add(Component.text("- " + reason, NamedTextColor.DARK_GRAY));
            lore.add(Component.text("Reward: " + CoreUtil.money(task.reward()), NamedTextColor.YELLOW));
            if (claimed) lore.add(Component.text("Already delivered.", NamedTextColor.DARK_GRAY));
            else if (carrying(player, task)) lore.add(Component.text("Click to deliver.", NamedTextColor.GREEN));
            else if (task.isSet()) {
                long ready = task.items().stream().filter(m -> count(player, m) >= task.each()).count();
                lore.add(Component.text("Carrying " + ready + " / " + task.items().size() + " of the set",
                        NamedTextColor.RED));
            } else lore.add(Component.text("Carrying " + count(player, task.items().get(0)) + " / " + task.each(),
                    NamedTextColor.RED));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
            slot++;
        }
        player.openInventory(inv);
    }

    /** Every line of the contract has to be satisfied, not just the first. */
    private boolean carrying(Player player, Task task) {
        for (Material material : task.items()) if (count(player, material) < task.each()) return false;
        return true;
    }

    private Material missing(Player player, Task task) {
        for (Material material : task.items()) if (count(player, material) < task.each()) return material;
        return null;
    }

    /** Takes the WHOLE contract or none of it. Anything already removed when a later line comes up short is
     *  handed straight back, so a delivery that cannot be completed costs the player nothing. */
    private boolean takeAll(Player player, Task task) {
        Map<Material, Integer> taken = new java.util.LinkedHashMap<>();
        for (Material material : task.items()) {
            int got = take(player, material, task.each());
            if (got > 0) taken.merge(material, got, Integer::sum);
            if (got < task.each()) {
                taken.forEach((m, n) -> CoreUtil.give(player, new ItemStack(m, n)));
                return false;
            }
        }
        return true;
    }

    private int count(Player player, Material material) {
        int total = 0;
        ItemStack plain = new ItemStack(material);
        for (ItemStack item : player.getInventory().getStorageContents())
            /** isSimilar keeps custom/NBT items out: a renamed or relic item is never eaten by a contract. */
            if (item != null && item.isSimilar(plain)) total += item.getAmount();
        return total;
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Batch batch = batchFor(player);
        List<Task> tasks = batch.tasks();
        int index = event.getRawSlot() - 11;
        if (index < 0 || index >= tasks.size()) return;
        Task task = tasks.get(index);
        List<String> done = batch.done();
        if (done.contains(task.id())) return;
        if (!active()) { CoreUtil.error(player, "The Task Master has moved on."); player.closeInventory(); return; }
        if (!carrying(player, task)) {
            Material absent = missing(player, task);
            CoreUtil.error(player, "You are not carrying " + task.each() + " "
                    + CoreUtil.pretty((absent == null ? task.items().get(0) : absent).name()) + ".");
            plugin.settings().marketSound(player, "failed");
            return;
        }
        /** Take the goods FIRST, all of them, so a race cannot pay twice and a shortfall cannot pay once. */
        if (!takeAll(player, task)) {
            CoreUtil.error(player, "The delivery came up short.");
            return;
        }
        done.add(task.id());
        /** Delivered goods go to the server vault instead of evaporating: the handover is recorded in the
         *  audit with what, how much and where, and ordinary shop commodities return to shop stock exactly
         *  as a destroyed one would. Nothing is duplicated -- the items are out of the player's inventory
         *  before this runs, and the vault holds a record, never a withdrawable copy. */
        for (Material material : task.items())
            plugin.vault().deliver(new ItemStack(material, task.each()), player.getLocation(), "DELIVERED");
        plugin.creditEarned(CoreUtil.id(player), task.reward(), "TASK_MASTER_" + task.id().toUpperCase(Locale.ROOT));
        plugin.db().recordEconomy(CoreUtil.id(player), "TASK_MASTER", task.reward(), task.id());
        CoreUtil.msg(player, DELIVERED.getOrDefault(task.id(), "He takes the lot without comment.")
                + " " + CoreUtil.money(task.reward()) + ".");
        save(player, batch);
        /** The next batch is dealt only when the whole set is done -- never one contract at a time -- and
         *  then it keeps going for as long as the event runs. */
        if (done.size() >= tasks.size()) {
            Batch next = new Batch(new ArrayList<>(draw()), new ArrayList<>());
            batches.put(player.getUniqueId(), next);
            save(player, next);
            CoreUtil.msg(player, "That is the whole board cleared. He is already writing the next one.");
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1.1f);
        open(player);
    }

    private int take(Player player, Material material, int amount) {
        int remaining = amount;
        ItemStack plain = new ItemStack(material);
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || !item.isSimilar(plain)) continue;
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            if (remaining == 0) break;
        }
        return amount - remaining;
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        /** Dropped from memory only. The batch itself lives in the database, so a player who logs out
         *  mid-contract comes back to exactly the same board rather than a freshly shuffled one. */
        batches.remove(event.getPlayer().getUniqueId());
    }

    boolean selfTest() {
        if (TABLE.size() < 20) return false;
        if (TABLE.stream().map(Task::id).distinct().count() != TABLE.size()) return false;
        /** Every contract needs its own completion line, or the generic fallback creeps back in. */
        if (!TABLE.stream().allMatch(t -> DELIVERED.containsKey(t.id()))) return false;
        if (!TABLE.stream().allMatch(t -> t.each() > 0 && !t.items().isEmpty() && t.reward() >= 1500)) return false;
        /** A set must not repeat an item, or one line of it could satisfy two. */
        if (!TABLE.stream().allMatch(t -> t.items().stream().distinct().count() == t.items().size())) return false;

        /** Pay is a function of effort and NOTHING else. Equal effort must pay equally regardless of what
         *  is being fetched, and more effort must never pay less. If a future edit starts pricing by what
         *  an item sells for, one of these two fails. */
        for (Task a : TABLE) for (Task b : TABLE) {
            if (a.effort() == b.effort() && a.reward() != b.reward()) return false;
            if (a.effort() > b.effort() && a.reward() < b.reward()) return false;
        }
        /** Concretely: shulker shells out-earn a stack of bricks not because shells are worth more, but
         *  because an End city is worse than a clay pit -- and the cheap tedious job still clears 1500. */
        Task bricks = TABLE.stream().filter(t -> t.id().equals("brick")).findFirst().orElse(null);
        Task shells = TABLE.stream().filter(t -> t.id().equals("shell")).findFirst().orElse(null);
        if (bricks == null || shells == null || shells.reward() <= bricks.reward()) return false;

        /** Every band has to be stocked, or a hand comes up short. */
        for (String band : List.of("Easy", "Testing", "Hard", "Brutal", "Legendary"))
            if (TABLE.stream().noneMatch(t -> t.tier().equals(band))) return false;

        /** The two colour collections sit at the top of the board and the harder one pays more. Asserted as
         *  an ordering rather than as exact figures, so retuning the factors cannot silently invert them. */
        Task wool = TABLE.stream().filter(t -> t.id().equals("woolset")).findFirst().orElse(null);
        Task beds = TABLE.stream().filter(t -> t.id().equals("bedset")).findFirst().orElse(null);
        if (wool == null || beds == null) return false;
        if (wool.reward() < 90000 || beds.reward() <= wool.reward()) return false;
        if (TABLE.stream().anyMatch(t -> !t.id().equals("bedset") && t.reward() > beds.reward())) return false;

        /** A hand is always 3-4 distinct contracts and always holds something doable today. */
        for (int attempt = 0; attempt < 200; attempt++) {
            List<Task> hand = draw();
            if (hand.size() < 3 || hand.size() > 4) return false;
            if (hand.stream().map(Task::id).distinct().count() != hand.size()) return false;
            if (hand.stream().noneMatch(t -> t.tier().equals("Easy"))) return false;
        }
        return true;
    }
}
