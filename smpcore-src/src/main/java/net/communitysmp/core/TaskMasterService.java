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
    private record Task(String id, Material material, int amount,
                        int steps, int travel, int risk, int grind, String flavour) {
        int effort() { return steps * 2 + travel * 3 + risk * 3 + grind * 3; }
        /** Rounded to the nearest hundred so the board reads like a person quoting a price. */
        double reward() { return Math.max(1500, Math.round((effort() * 1250 - 2500) / 100.0) * 100); }
        String tier() {
            int e = effort();
            return e < 9 ? "Easy" : e < 14 ? "Testing" : e < 22 ? "Hard" : "Brutal";
        }
        /** The one or two things that actually make this job hard, for the contract card. */
        List<String> why() {
            List<String> tags = new ArrayList<>();
            if (travel >= 3) tags.add("The End or the deep ocean");
            else if (travel == 2) tags.add("Requires the Nether");
            else if (travel == 1) tags.add("Requires travel");
            if (risk >= 3) tags.add("Genuinely dangerous");
            else if (risk == 2) tags.add("Hostile ground");
            if (grind >= 3) tags.add("Rare drop");
            if (steps >= 3) tags.add("Multi-stage craft");
            return tags.size() > 2 ? tags.subList(0, 2) : tags;
        }
    }

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
        new Task("cookie",    Material.COOKIE,                64, 2,0,0,0, "Sixty-four cookies. They are for me. Do not make it strange."),
        new Task("ladder",    Material.LADDER,                64, 1,0,0,1, "Sixty-four ladders. The last crew sank a shaft and then forgot how to leave it."),
        new Task("charcoal",  Material.CHARCOAL,              48, 1,0,0,1, "Forty-eight charcoal. Wood in, fire out, and no, I will not take coal instead."),
        new Task("bread",     Material.BREAD,                 32, 1,0,0,1, "The road crews eat before they dig. Thirty-two loaves, still warm if you can manage it."),
        new Task("pie",       Material.PUMPKIN_PIE,           24, 2,0,0,1, "Twenty-four pumpkin pies. The harvest festival will not feed itself."),
        new Task("hay",       Material.HAY_BLOCK,             16, 1,0,0,2, "Sixteen bales. The horses are unmoved by promises."),
        // --------------------------------------------------------------------------------- Testing
        new Task("cake",      Material.CAKE,                   3, 3,0,0,1, "Somebody's daughter turns nine. Three cakes, and do not ask me to explain the milk."),
        new Task("lantern",   Material.LANTERN,               16, 2,0,1,1, "Sixteen lanterns for the tunnel. I have lost two crews to the dark already."),
        new Task("target",    Material.TARGET,                16, 2,0,0,2, "Sixteen targets for the range. The recruits keep missing the wall entirely."),
        new Task("smooth",    Material.SMOOTH_STONE,         128, 2,0,0,2, "A hundred and twenty-eight smooth stone. Twice through the furnace. I will know."),
        new Task("shelf",     Material.BOOKSHELF,             12, 3,0,0,2, "The scribes want shelves. Twelve of them. They will not say what for."),
        new Task("spyglass",  Material.SPYGLASS,               2, 3,1,0,1, "Two spyglasses. One for the lookout, one for when the lookout drops the first."),
        new Task("amethyst",  Material.AMETHYST_SHARD,        24, 0,1,1,2, "Twenty-four amethyst shards. Listen for the chiming, and mind the drop."),
        new Task("candle",    Material.CANDLE,                32, 2,1,1,1, "Thirty-two candles for a vigil. Do not ask whose."),
        new Task("brick",     Material.BRICKS,                64, 2,1,0,2, "Sixty-four bricks. Clay, fire and patience. Mostly patience."),
        new Task("carrot",    Material.GOLDEN_CARROT,         32, 2,0,1,2, "Thirty-two golden carrots. The night watch swears by them and I am not paying for excuses."),
        // ------------------------------------------------------------------------------------ Hard
        new Task("ice",       Material.PACKED_ICE,            64, 1,2,0,2, "Sixty-four packed ice, and it had better not arrive as water."),
        new Task("glowstone", Material.GLOWSTONE,             24, 0,2,2,1, "Glowstone. Twenty-four. Yes, from over there. No, I will not come with you."),
        new Task("honey",     Material.HONEY_BOTTLE,          16, 2,1,1,2, "Sixteen bottles of honey. Bring a campfire, and bring your nerve."),
        new Task("blaze",     Material.BLAZE_ROD,             16, 0,2,3,2, "Sixteen rods that keep burning. I have a client who insists."),
        new Task("echo",      Material.ECHO_SHARD,             6, 0,2,3,2, "Six echo shards. Quietly. I mean that literally."),
        new Task("totem",     Material.TOTEM_OF_UNDYING,       1, 0,1,3,3, "A totem. Walk into a raid, walk back out, and bring me the thing that let you."),
        // ---------------------------------------------------------------------------------- Brutal
        new Task("skull",     Material.WITHER_SKELETON_SKULL,  1, 0,2,3,3, "One skull. Black bone, hollow eyes. I will not tell you what it is for, and you will not want to know."),
        new Task("anchor",    Material.RESPAWN_ANCHOR,         2, 3,2,2,2, "Two anchors. If you have to ask why I want them charged over there, do not take the job."),
        new Task("shell",     Material.SHULKER_SHELL,          4, 0,3,3,2, "Four shulker shells. Boxes do not build themselves and neither, apparently, does my patience."),
        new Task("sealantern",Material.SEA_LANTERN,            8, 1,3,3,2, "Eight sea lanterns. The guardians will object. Object back."),
        new Task("netherite", Material.NETHERITE_INGOT,        1, 3,2,2,3, "One netherite ingot. I know exactly what I am asking. That is why the purse is what it is."),
        new Task("conduit",   Material.CONDUIT,                1, 3,3,2,3, "One conduit. A heart and eight shells. Come back damp.")
    );

    private final SMPCore plugin;
    private UUID traderId;
    /** Where the courier was placed. Kept so the announcement quotes the spot he is actually standing on,
     *  and so he can be put back if something removes him while the event is still running. */
    private Location home;
    private long endsAt;
    /** player -> their drawn contracts, and which they have already turned in. */
    private final Map<UUID, List<Task>> assigned = new HashMap<>();
    private final Map<UUID, List<String>> completed = new HashMap<>();

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
        assigned.clear();
        completed.clear();
    }

    boolean active() { return traderId != null; }

    /** Right-clicking the courier. Returns true when handled, so the caller can cancel the vanilla trade. */
    boolean interact(Player player) {
        if (!active()) return false;
        assigned.computeIfAbsent(player.getUniqueId(), id -> draw());
        open(player);
        return true;
    }

    /** A fresh hand of 3-4 contracts: one Easy, one Testing, one Hard, and a Brutal three times in five.
     *
     *  Drawn a band at a time rather than at random across the whole table, so a hand always holds
     *  something you could turn in today and something worth setting out for. Shuffling blind can deal
     *  somebody four jobs in the End, which is not a board, it is a wall. */
    private List<Task> draw() {
        List<Task> hand = new ArrayList<>();
        for (String band : List.of("Easy", "Testing", "Hard")) pickFrom(band, hand);
        if (ThreadLocalRandom.current().nextInt(5) < 3) pickFrom("Brutal", hand);
        Collections.shuffle(hand);
        return List.copyOf(hand);
    }

    private void pickFrom(String band, List<Task> hand) {
        List<Task> options = TABLE.stream().filter(task -> task.tier().equals(band)).toList();
        if (!options.isEmpty()) hand.add(options.get(ThreadLocalRandom.current().nextInt(options.size())));
    }

    private void open(Player player) {
        List<Task> tasks = assigned.getOrDefault(player.getUniqueId(), List.of());
        List<String> done = completed.computeIfAbsent(player.getUniqueId(), id -> new ArrayList<>());
        Inventory inv = plugin.getServer().createInventory(new Holder(traderId), 27,
                Component.text("Task Master • Contracts", NamedTextColor.DARK_PURPLE));
        int slot = 11;
        for (Task task : tasks) {
            int held = count(player, task.material());
            boolean claimed = done.contains(task.id());
            ItemStack icon = new ItemStack(task.material());
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text((claimed ? "✔ " : "") + task.amount() + "x " + CoreUtil.pretty(task.material().name()),
                    claimed ? NamedTextColor.DARK_GRAY : NamedTextColor.GOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(task.flavour(), NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(Component.text("Difficulty: " + task.tier(), NamedTextColor.DARK_GRAY));
            /** Say WHY it pays what it pays, so the board never reads as arbitrary. */
            for (String reason : task.why()) lore.add(Component.text("- " + reason, NamedTextColor.DARK_GRAY));
            lore.add(Component.text("Reward: " + CoreUtil.money(task.reward()), NamedTextColor.YELLOW));
            if (claimed) lore.add(Component.text("Already delivered.", NamedTextColor.DARK_GRAY));
            else if (held >= task.amount()) lore.add(Component.text("Click to deliver.", NamedTextColor.GREEN));
            else lore.add(Component.text("Carrying " + held + " / " + task.amount(), NamedTextColor.RED));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
            slot++;
        }
        player.openInventory(inv);
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
        List<Task> tasks = assigned.getOrDefault(player.getUniqueId(), List.of());
        int index = event.getRawSlot() - 11;
        if (index < 0 || index >= tasks.size()) return;
        Task task = tasks.get(index);
        List<String> done = completed.computeIfAbsent(player.getUniqueId(), id -> new ArrayList<>());
        if (done.contains(task.id())) return;
        if (!active()) { CoreUtil.error(player, "The Task Master has moved on."); player.closeInventory(); return; }
        if (count(player, task.material()) < task.amount()) {
            CoreUtil.error(player, "You are not carrying " + task.amount() + " " + CoreUtil.pretty(task.material().name()) + ".");
            plugin.settings().marketSound(player, "failed");
            return;
        }
        /** Take the goods FIRST, and only pay for what was actually removed, so a race cannot pay twice. */
        int removed = take(player, task.material(), task.amount());
        if (removed < task.amount()) {
            /** Put back whatever was taken rather than paying for a partial delivery. */
            if (removed > 0) CoreUtil.give(player, new ItemStack(task.material(), removed));
            CoreUtil.error(player, "The delivery came up short.");
            return;
        }
        done.add(task.id());
        plugin.creditEarned(CoreUtil.id(player), task.reward(), "TASK_MASTER_" + task.id().toUpperCase(Locale.ROOT));
        plugin.db().recordEconomy(CoreUtil.id(player), "TASK_MASTER", task.reward(), task.id());
        CoreUtil.msg(player, "Contract complete: " + task.amount() + "x " + CoreUtil.pretty(task.material().name())
                + " for " + CoreUtil.money(task.reward()) + ".");
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
        /** Contracts are per-event and not persisted: a player who leaves mid-event simply draws a fresh
         *  hand if they return while it is still running. Nothing owed is lost, because payment happens at
         *  the moment of delivery rather than at the end. */
        assigned.remove(event.getPlayer().getUniqueId());
        completed.remove(event.getPlayer().getUniqueId());
    }

    boolean selfTest() {
        if (TABLE.size() < 20) return false;
        if (TABLE.stream().map(Task::id).distinct().count() != TABLE.size()) return false;
        if (!TABLE.stream().allMatch(t -> t.amount() > 0 && t.reward() >= 1500)) return false;

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
        for (String band : List.of("Easy", "Testing", "Hard", "Brutal"))
            if (TABLE.stream().noneMatch(t -> t.tier().equals(band))) return false;

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
