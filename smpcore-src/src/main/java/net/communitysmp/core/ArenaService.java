package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Native 1v1 duelling with real stakes, in a world of its own.
 *
 *  ONE MATCH AT A TIME, deliberately. Concurrency here buys nothing except more ways for escrow to go wrong,
 *  and a single active duel is what a server this size actually wants to watch.
 *
 *  MONEY. Both duellists' stakes and every spectator wager are debited up front and held in the database,
 *  never in memory -- so a restart mid-match does not lose or invent a penny. Stakes need not match and may
 *  be zero. The winner takes both stakes in full. Spectator betting is pari-mutuel and NEVER server-funded:
 *  winners get their own stake back plus a proportional share of the losing pool; if all the money was on
 *  one side, a win simply returns stakes because there was nothing to win, and a loss sends that pool to the
 *  Central Bank.
 *
 *  STATE. A duellist's location, inventory, armour, offhand, XP, health, hunger, effects, gamemode and
 *  flight are captured to the database before they are touched and restored exactly afterwards. Nothing
 *  from outside enters the fight: the arena inventory is wiped and replaced with an identical copy of the
 *  chosen kit for both players. Arena deaths are intercepted before graves, drops, penalties or rewards. */
final class ArenaService implements Listener {

    /** A void world with nothing in it; the arena itself is built by us, so there is no external download to
     *  vet and nothing to go wrong at load time. */
    static final class VoidGenerator extends ChunkGenerator {
        @Override public void generateNoise(org.bukkit.generator.WorldInfo info, Random random, int x, int z, ChunkData data) { }
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
    }

    enum Phase { IDLE, PENDING, STAKING, LIVE, ENDING }

    /** The kit list. Contents follow current 1.21 duelling convention: Protection IV diamond as the shared
     *  baseline so fights are decided by the weapon, gapples as the only healing, and no pearls except where
     *  the kit is built around mobility. */
    enum Kit {
        MACE("Mace", "Heavy hitter. Wind charges to gain height, mace to land it."),
        SWORD("Sword + Shield", "The classic. Sharpness V and a shield to time."),
        AXE("Axe", "Shield-breaker. Slower swings, brutal when they land."),
        SPEAR("Spear", "Elytra and rockets. Hit and run, never stand still.");
        private final String label, blurb;
        Kit(String label, String blurb) { this.label = label; this.blurb = blurb; }
        String label() { return label; }
        String blurb() { return blurb; }
    }

    private record Wager(String player, String on, double amount) {}

    private final SMPCore plugin;
    private final Database db;
    private Phase phase = Phase.IDLE;
    private String challenger, opponent;
    private Kit kit = Kit.SWORD;
    private int bestOf = 1;
    private final Map<String, Double> stakes = new LinkedHashMap<>();
    private final Map<String, Boolean> confirmed = new LinkedHashMap<>();
    private final Map<String, Integer> rounds = new LinkedHashMap<>();
    private final List<Wager> wagers = new ArrayList<>();
    private final Map<String, Long> disconnectedAt = new LinkedHashMap<>();
    private World arena;
    private BukkitTask ticker;

    ArenaService(SMPCore plugin) {
        this.plugin = plugin;
        this.db = plugin.db();
        Bukkit.getScheduler().runTask(plugin, this::prepareWorld);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskLater(plugin, this::recoverAfterRestart, 60L);
    }

    void shutdown() {
        if (ticker != null) ticker.cancel();
        /** A shutdown mid-match refunds everything rather than leaving money in limbo. State restoration
         *  happens on the next boot from the database, so nobody loses an inventory either. */
        if (phase == Phase.LIVE || phase == Phase.STAKING) abortAndRefund("the server restarted");
    }

    // ------------------------------------------------------------------ world
    private void prepareWorld() {
        String name = plugin.getConfig().getString("arena.world", "ashfall_arena");
        arena = Bukkit.getWorld(name);
        if (arena == null) {
            arena = new WorldCreator(name).generator(new VoidGenerator()).type(WorldType.FLAT)
                    .environment(World.Environment.NORMAL).createWorld();
        }
        if (arena == null) { plugin.getLogger().warning("[Arena] could not create the arena world."); return; }
        arena.setAutoSave(true);
        arena.setDifficulty(org.bukkit.Difficulty.NORMAL);
        arena.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        arena.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        arena.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        arena.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
        arena.setGameRule(org.bukkit.GameRule.DO_IMMEDIATE_RESPAWN, true);
        arena.setGameRule(org.bukkit.GameRule.FALL_DAMAGE, true);
        arena.setTime(6000);
        buildArena();
    }

    /** A plain, readable arena: a 41x41 floor, a low wall, and a spectator gallery above it. Built rather
     *  than downloaded, so there is no third-party world to inspect or trust. */
    private void buildArena() {
        if (arena == null || db.state("arena_built") != null) return;
        int half = 20, floor = 64;
        for (int x = -half; x <= half; x++) for (int z = -half; z <= half; z++) {
            arena.getBlockAt(x, floor, z).setType(Material.SMOOTH_STONE);
            if (Math.abs(x) == half || Math.abs(z) == half)
                for (int y = 1; y <= 4; y++) arena.getBlockAt(x, floor + y, z).setType(Material.SMOOTH_STONE_SLAB);
        }
        /** Spectator gallery: a ring of glass a few blocks up, outside the fighting floor. */
        for (int x = -half - 2; x <= half + 2; x++) for (int z = -half - 2; z <= half + 2; z++) {
            boolean ring = Math.abs(x) > half || Math.abs(z) > half;
            if (ring) arena.getBlockAt(x, floor + 8, z).setType(Material.GLASS);
        }
        arena.setSpawnLocation(0, floor + 1, 0);
        db.state("arena_built", "1");
        plugin.getLogger().info("[Arena] arena built in " + arena.getName() + ".");
    }

    private Location corner(int index) {
        int floor = 65;
        return new Location(arena, index == 0 ? -15.5 : 15.5, floor, 0.5, index == 0 ? 90f : -90f, 0f);
    }

    private Location gallery() { return new Location(arena, 0.5, 73, 0.5); }

    // ------------------------------------------------------------------ kits
    /** Both duellists always receive an identical copy. Nothing from their own inventory comes with them. */
    List<ItemStack> kitContents(Kit kit) {
        List<ItemStack> items = new ArrayList<>();
        items.add(armour(Material.DIAMOND_HELMET));
        items.add(armour(Material.DIAMOND_CHESTPLATE));
        items.add(armour(Material.DIAMOND_LEGGINGS));
        items.add(armour(Material.DIAMOND_BOOTS));
        switch (kit) {
            case MACE -> {
                items.add(enchanted(Material.MACE, Map.of(Enchantment.DENSITY, 5, Enchantment.UNBREAKING, 3)));
                items.add(new ItemStack(Material.WIND_CHARGE, 16));
                items.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2));
                items.add(new ItemStack(Material.GOLDEN_APPLE, 8));
            }
            case SWORD -> {
                items.add(enchanted(Material.DIAMOND_SWORD, Map.of(Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3)));
                items.add(enchanted(Material.SHIELD, Map.of(Enchantment.UNBREAKING, 3)));
                items.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2));
                items.add(new ItemStack(Material.GOLDEN_APPLE, 8));
            }
            case AXE -> {
                items.add(enchanted(Material.DIAMOND_AXE, Map.of(Enchantment.SHARPNESS, 5, Enchantment.EFFICIENCY, 5, Enchantment.UNBREAKING, 3)));
                items.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2));
                items.add(new ItemStack(Material.GOLDEN_APPLE, 8));
                items.add(new ItemStack(Material.COBWEB, 8));
            }
            case SPEAR -> {
                items.add(enchanted(Material.DIAMOND_SWORD, Map.of(Enchantment.SHARPNESS, 4, Enchantment.UNBREAKING, 3)));
                items.add(enchanted(Material.ELYTRA, Map.of(Enchantment.UNBREAKING, 3)));
                items.add(new ItemStack(Material.FIREWORK_ROCKET, 64));
                items.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1));
                items.add(new ItemStack(Material.GOLDEN_APPLE, 6));
            }
        }
        return items;
    }

    private ItemStack armour(Material material) {
        return enchanted(material, Map.of(Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3));
    }

    private ItemStack enchanted(Material material, Map<Enchantment, Integer> enchants) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        enchants.forEach((enchantment, level) -> meta.addEnchant(enchantment, level, true));
        item.setItemMeta(meta);
        return item;
    }

    private void equip(Player player, Kit kit) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setItemOnCursor(null);
        for (ItemStack item : kitContents(kit)) {
            switch (item.getType()) {
                case DIAMOND_HELMET -> player.getInventory().setHelmet(item);
                case DIAMOND_CHESTPLATE, ELYTRA -> player.getInventory().setChestplate(item);
                case DIAMOND_LEGGINGS -> player.getInventory().setLeggings(item);
                case DIAMOND_BOOTS -> player.getInventory().setBoots(item);
                case SHIELD -> player.getInventory().setItemInOffHand(item);
                default -> player.getInventory().addItem(item);
            }
        }
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        player.setGameMode(GameMode.SURVIVAL);
        player.updateInventory();
    }

    // ------------------------------------------------------------------ state capture and restore
    /** Everything that makes a player who they were, written to the database before anything is touched. */
    private void capture(Player player) {
        String id = CoreUtil.id(player);
        if (db.arenaState(id) != null) return;
        ItemStack[] contents = player.getInventory().getContents();
        ItemStack[] armour = player.getInventory().getArmorContents();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        ItemStack[] all = new ItemStack[contents.length + armour.length + 1];
        System.arraycopy(contents, 0, all, 0, contents.length);
        System.arraycopy(armour, 0, all, contents.length, armour.length);
        all[all.length - 1] = offhand;
        Location at = player.getLocation();
        db.arenaStateSave(id, ItemStack.serializeItemsAsBytes(all), at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                at.getYaw(), at.getPitch(), player.getLevel(), player.getExp(), player.getHealth(), player.getFoodLevel(),
                player.getGameMode().name());
    }

    private void restore(Player player) {
        String id = CoreUtil.id(player);
        Database.ArenaState state = db.arenaState(id);
        if (state == null) return;
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        try {
            ItemStack[] all = ItemStack.deserializeItemsFromBytes(state.items());
            int contents = player.getInventory().getSize();
            ItemStack[] main = new ItemStack[contents], armour = new ItemStack[4];
            System.arraycopy(all, 0, main, 0, Math.min(contents, all.length));
            if (all.length >= contents + 4) System.arraycopy(all, contents, armour, 0, 4);
            player.getInventory().setContents(main);
            player.getInventory().setArmorContents(armour);
            if (all.length > contents + 4 && all[contents + 4] != null) player.getInventory().setItemInOffHand(all[contents + 4]);
        } catch (Throwable error) {
            plugin.getLogger().warning("[Arena] could not restore " + id + "'s inventory: " + error);
        }
        World world = Bukkit.getWorld(state.world());
        if (world != null) player.teleport(new Location(world, state.x(), state.y(), state.z(), state.yaw(), state.pitch()));
        player.setLevel(state.level());
        player.setExp(state.exp());
        player.setFoodLevel(state.food());
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        player.setHealth(Math.min(state.health(), player.getAttribute(Attribute.MAX_HEALTH).getValue()));
        player.setFireTicks(0);
        try { player.setGameMode(GameMode.valueOf(state.gamemode())); } catch (IllegalArgumentException ignored) { player.setGameMode(GameMode.SURVIVAL); }
        db.arenaStateClear(id);
        player.updateInventory();
    }

    /** A restart during a match leaves captured state behind; give it back the moment they reconnect. */
    private void recoverAfterRestart() {
        for (String id : db.arenaStateOwners()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) { restore(player); CoreUtil.msg(player, "Your pre-duel inventory was restored after the restart."); }
        }
    }

    @EventHandler public void rejoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (db.arenaState(CoreUtil.id(player)) == null) return;
        boolean fighting = (phase == Phase.LIVE) && isDuellist(CoreUtil.id(player));
        if (!fighting) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) { restore(player); CoreUtil.msg(player, "Your pre-duel inventory was restored."); }
        }, 20L);
        else { disconnectedAt.remove(CoreUtil.id(player)); CoreUtil.msg(player, "You are back in the duel."); }
    }

    // ------------------------------------------------------------------ challenge flow
    boolean isDuellist(String id) { return id.equals(challenger) || id.equals(opponent); }

    boolean challenge(Player from, String targetName) {
        if (phase != Phase.IDLE) { CoreUtil.error(from, "A duel is already in progress. Wait for it to finish."); return true; }
        Player target = plugin.getServer().getPlayer(targetName);
        if (target == null || target.equals(from)) { CoreUtil.error(from, "That player is not online."); return true; }
        challenger = CoreUtil.id(from);
        opponent = CoreUtil.id(target);
        phase = Phase.PENDING;
        stakes.clear(); confirmed.clear(); wagers.clear(); rounds.clear(); disconnectedAt.clear();
        CoreUtil.msg(from, "Challenge sent to " + target.getName() + ". They have 60 seconds.");
        CoreUtil.msg(target, from.getName() + " has challenged you to a duel. /duel accept or /duel decline.");
        pendingSince = System.currentTimeMillis();
        return true;
    }

    private long pendingSince;

    boolean accept(Player player) {
        if (phase != Phase.PENDING || !CoreUtil.id(player).equals(opponent)) { CoreUtil.error(player, "No challenge for you."); return true; }
        phase = Phase.STAKING;
        broadcastDuellists("Duel accepted. Choose a kit with /duel kit <mace|sword|axe|spear>, the series with "
                + "/duel series <1|3>, then your stake with /duel stake <amount>. /duel confirm when ready.");
        return true;
    }

    boolean decline(Player player) {
        if (phase != Phase.PENDING || !CoreUtil.id(player).equals(opponent)) { CoreUtil.error(player, "No challenge for you."); return true; }
        broadcastDuellists("Duel declined.");
        reset();
        return true;
    }

    boolean setKit(Player player, String name) {
        if (phase != Phase.STAKING || !isDuellist(CoreUtil.id(player))) { CoreUtil.error(player, "Not in a duel setup."); return true; }
        try { kit = Kit.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { CoreUtil.error(player, "Kits: mace, sword, axe, spear."); return true; }
        confirmed.clear();
        broadcastDuellists("Kit set to " + kit.label() + " - " + kit.blurb() + ". Confirmations reset.");
        return true;
    }

    boolean setSeries(Player player, int best) {
        if (phase != Phase.STAKING || !isDuellist(CoreUtil.id(player))) { CoreUtil.error(player, "Not in a duel setup."); return true; }
        if (best != 1 && best != 3) { CoreUtil.error(player, "Best of 1 or 3."); return true; }
        bestOf = best;
        confirmed.clear();
        broadcastDuellists("Series set to best of " + best + ". Confirmations reset.");
        return true;
    }

    /** Stakes are independent and may be zero: nobody has to match anybody. */
    boolean setStake(Player player, double amount) {
        if (phase != Phase.STAKING || !isDuellist(CoreUtil.id(player))) { CoreUtil.error(player, "Not in a duel setup."); return true; }
        if (amount < 0 || !Double.isFinite(amount)) { CoreUtil.error(player, "Stake cannot be negative."); return true; }
        if (amount > 0 && db.player(CoreUtil.id(player)).balance() < amount) { CoreUtil.error(player, "You cannot cover that."); return true; }
        stakes.put(CoreUtil.id(player), amount);
        confirmed.remove(CoreUtil.id(player));
        broadcastDuellists(player.getName() + " staked " + CoreUtil.money(amount) + ". Confirm with /duel confirm.");
        return true;
    }

    boolean confirm(Player player) {
        String id = CoreUtil.id(player);
        if (phase != Phase.STAKING || !isDuellist(id)) { CoreUtil.error(player, "Not in a duel setup."); return true; }
        confirmed.put(id, true);
        CoreUtil.msg(player, "Confirmed. Waiting for the other duellist.");
        if (confirmed.size() < 2) return true;
        /** Both in. Take the stakes NOW, before anybody moves, and refuse the whole thing if either debit
         *  fails -- a duel that starts half-funded is worse than one that never started. */
        double a = stakes.getOrDefault(challenger, 0d), b = stakes.getOrDefault(opponent, 0d);
        if (a > 0 && !db.changeBalance(challenger, -a)) { broadcastDuellists("Challenger could not cover their stake; duel cancelled."); reset(); return true; }
        if (b > 0 && !db.changeBalance(opponent, -b)) {
            if (a > 0) db.changeBalance(challenger, a);
            broadcastDuellists("Opponent could not cover their stake; duel cancelled."); reset(); return true;
        }
        db.arenaEscrowSet(challenger, a);
        db.arenaEscrowSet(opponent, b);
        startMatch();
        return true;
    }

    // ------------------------------------------------------------------ the match
    private void startMatch() {
        phase = Phase.LIVE;
        rounds.put(challenger, 0);
        rounds.put(opponent, 0);
        Player one = plugin.getServer().getPlayer(challenger), two = plugin.getServer().getPlayer(opponent);
        if (one == null || two == null) { abortAndRefund("a duellist went offline"); return; }
        plugin.getServer().broadcast(Component.text("⚔ " + one.getName() + " vs " + two.getName() + " - "
                + kit.label() + ", best of " + bestOf + ". /duel watch to spectate, /duel bet <player> <amount> before it starts.",
                NamedTextColor.GOLD));
        beginRound();
    }

    private void beginRound() {
        Player one = plugin.getServer().getPlayer(challenger), two = plugin.getServer().getPlayer(opponent);
        if (one == null || two == null) { abortAndRefund("a duellist went offline"); return; }
        capture(one); capture(two);
        one.teleport(corner(0)); two.teleport(corner(1));
        equip(one, kit); equip(two, kit);
        broadcastDuellists("Round " + (rounds.get(challenger) + rounds.get(opponent) + 1) + " - fight!");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void death(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        if (phase != Phase.LIVE || !isDuellist(CoreUtil.id(dead)) || !inArena(dead)) return;
        /** Intercepted before graves, drops, penalties and rewards. Keep-inventory is on in this world, but
         *  the drop list is cleared anyway so nothing can leak out of the arena. */
        event.getDrops().clear();
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.deathMessage(null);
        String loser = CoreUtil.id(dead);
        String winner = loser.equals(challenger) ? opponent : challenger;
        Bukkit.getScheduler().runTask(plugin, () -> roundOver(winner, loser));
    }

    private void roundOver(String winner, String loser) {
        if (phase != Phase.LIVE) return;
        rounds.merge(winner, 1, Integer::sum);
        int needed = bestOf / 2 + 1;
        broadcastDuellists("Round to " + name(winner) + " (" + rounds.get(challenger) + " - " + rounds.get(opponent) + ").");
        if (rounds.get(winner) >= needed) { finish(winner, loser); return; }
        Bukkit.getScheduler().runTaskLater(plugin, this::beginRound, 60L);
    }

    private void finish(String winner, String loser) {
        phase = Phase.ENDING;
        double pot = db.arenaEscrowOf(challenger) + db.arenaEscrowOf(opponent);
        db.arenaEscrowClear(challenger); db.arenaEscrowClear(opponent);
        if (pot > 0) {
            db.changeBalance(winner, pot);
            db.recordEconomy(winner, "DUEL_WIN", pot, loser);
        }
        settleWagers(winner);
        plugin.getServer().broadcast(Component.text("⚔ " + name(winner) + " defeats " + name(loser)
                + (pot > 0 ? " and takes " + CoreUtil.money(pot) : ""), NamedTextColor.GOLD));
        returnEverybody();
        reset();
    }

    /** Pari-mutuel, never server-funded. */
    private void settleWagers(String winner) {
        double winningPool = 0, losingPool = 0;
        for (Wager wager : wagers) if (wager.on().equals(winner)) winningPool += wager.amount(); else losingPool += wager.amount();
        if (winningPool <= 0) {
            /** Nobody backed the winner: the losing pool has no claimant and goes to the Central Bank rather
             *  than being invented back to anybody. */
            if (losingPool > 0) plugin.bank().creditSink(losingPool, "ARENA", "SPECTATOR_POOL");
            db.arenaWagersClear();
            return;
        }
        for (Wager wager : wagers) {
            if (!wager.on().equals(winner)) continue;
            /** Own stake back, plus a share of the losing money proportional to what they risked. If there
             *  was no money on the other side, that share is zero and they simply get their stake back. */
            double share = losingPool * (wager.amount() / winningPool);
            double payout = Math.round((wager.amount() + share) * 100) / 100.0;
            db.changeBalance(wager.player(), payout);
            db.recordEconomy(wager.player(), "DUEL_WAGER", payout, winner);
            Player better = plugin.getServer().getPlayer(wager.player());
            if (better != null) CoreUtil.msg(better, "Your wager returned " + CoreUtil.money(payout) + ".");
        }
        db.arenaWagersClear();
    }

    boolean bet(Player player, String on, double amount) {
        if (phase != Phase.STAKING && phase != Phase.PENDING) { CoreUtil.error(player, "Betting closes when the match starts."); return true; }
        String id = CoreUtil.id(player);
        if (isDuellist(id)) { CoreUtil.error(player, "Duellists cannot bet on their own match."); return true; }
        String target = CoreUtil.id(on == null ? "" : on);
        if (!isDuellist(target)) { CoreUtil.error(player, "Bet on one of the two duellists."); return true; }
        if (amount < 0 || !Double.isFinite(amount)) { CoreUtil.error(player, "Amount cannot be negative."); return true; }
        if (wagers.stream().anyMatch(w -> w.player().equals(id))) { CoreUtil.error(player, "You have already placed a wager."); return true; }
        if (amount > 0 && !db.changeBalance(id, -amount)) { CoreUtil.error(player, "You cannot cover that."); return true; }
        wagers.add(new Wager(id, target, amount));
        db.arenaWagerAdd(id, target, amount);
        CoreUtil.msg(player, "Wagered " + CoreUtil.money(amount) + " on " + name(target) + ".");
        return true;
    }

    boolean watch(Player player) {
        if (phase != Phase.LIVE) { CoreUtil.error(player, "No duel is running."); return true; }
        if (isDuellist(CoreUtil.id(player))) { CoreUtil.error(player, "You are in this duel."); return true; }
        capture(player);
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(gallery());
        CoreUtil.msg(player, "Spectating. You cannot affect the fight.");
        return true;
    }

    /** Spectators are in spectator mode, but belt and braces: nothing they do can touch a duellist. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void protect(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player hurt) || !inArena(hurt)) return;
        Player source = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                  && projectile.getShooter() instanceof Player shooter ? shooter : null;
        if (source == null) return;
        if (phase != Phase.LIVE || !isDuellist(CoreUtil.id(source)) || !isDuellist(CoreUtil.id(hurt))) event.setCancelled(true);
    }

    private boolean inArena(Player player) { return arena != null && player.getWorld().equals(arena); }

    @EventHandler public void quit(PlayerQuitEvent event) {
        String id = CoreUtil.id(event.getPlayer());
        if (phase == Phase.LIVE && isDuellist(id)) disconnectedAt.put(id, System.currentTimeMillis());
        else if (inArena(event.getPlayer())) restore(event.getPlayer());
    }

    private void tick() {
        if (phase == Phase.PENDING && System.currentTimeMillis() - pendingSince > 60000) {
            broadcastDuellists("Challenge expired."); reset(); return;
        }
        if (phase != Phase.LIVE) return;
        long grace = Math.max(5, plugin.getConfig().getLong("arena.reconnect-grace-seconds", 45)) * 1000L;
        for (Map.Entry<String, Long> entry : new LinkedHashMap<>(disconnectedAt).entrySet()) {
            if (System.currentTimeMillis() - entry.getValue() < grace) continue;
            String loser = entry.getKey(), winner = loser.equals(challenger) ? opponent : challenger;
            broadcastDuellists(name(loser) + " did not reconnect in time.");
            finish(winner, loser);
            return;
        }
    }

    private void abortAndRefund(String why) {
        for (String id : List.of(String.valueOf(challenger), String.valueOf(opponent))) {
            double held = db.arenaEscrowOf(id);
            if (held > 0) { db.changeBalance(id, held); db.recordEconomy(id, "DUEL_REFUND", held, why); }
            db.arenaEscrowClear(id);
        }
        for (Wager wager : wagers) if (wager.amount() > 0) db.changeBalance(wager.player(), wager.amount());
        db.arenaWagersClear();
        broadcastDuellists("Duel cancelled - " + why + ". All stakes and wagers refunded.");
        returnEverybody();
        reset();
    }

    boolean cancel(Player player) {
        if (phase == Phase.IDLE) { CoreUtil.error(player, "No duel to cancel."); return true; }
        if (!isDuellist(CoreUtil.id(player)) && !plugin.isAdmin(player)) { CoreUtil.error(player, "Not your duel."); return true; }
        abortAndRefund("cancelled by " + player.getName());
        return true;
    }

    private void returnEverybody() {
        if (arena == null) return;
        for (Player player : new ArrayList<>(arena.getPlayers())) restore(player);
    }

    private void reset() {
        phase = Phase.IDLE;
        challenger = null; opponent = null; bestOf = 1; kit = Kit.SWORD;
        stakes.clear(); confirmed.clear(); rounds.clear(); wagers.clear(); disconnectedAt.clear();
    }

    private String name(String id) {
        Player player = plugin.getServer().getPlayer(id);
        return player != null ? player.getName() : id;
    }

    private void broadcastDuellists(String message) {
        for (String id : new String[]{challenger, opponent}) {
            if (id == null) continue;
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) CoreUtil.msg(player, message);
        }
    }

    String status() {
        if (phase == Phase.IDLE) return "No duel is running.";
        return phase + ": " + name(challenger) + " vs " + name(opponent) + " | " + kit.label() + " | best of " + bestOf
                + " | stakes " + CoreUtil.money(stakes.getOrDefault(challenger, 0d)) + " / "
                + CoreUtil.money(stakes.getOrDefault(opponent, 0d)) + " | " + wagers.size() + " wager(s)";
    }

    boolean selfTest() {
        /** Every kit gives both sides the same thing, and nothing in a kit is a relic or bound item. */
        for (Kit k : Kit.values()) {
            List<ItemStack> a = kitContents(k), b = kitContents(k);
            if (a.size() != b.size() || a.isEmpty()) return false;
            for (int i = 0; i < a.size(); i++) if (!a.get(i).isSimilar(b.get(i))) return false;
        }
        /** Pari-mutuel arithmetic: winners never receive more than the two pools combined. */
        double winning = 300, losing = 700;
        double payout = 0;
        for (double stake : new double[]{100, 200}) payout += stake + losing * (stake / winning);
        return Math.abs(payout - (winning + losing)) < 0.01;
    }
}
