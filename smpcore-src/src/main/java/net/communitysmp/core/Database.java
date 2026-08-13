package net.communitysmp.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.*;
import java.util.*;

final class Database implements AutoCloseable {
    record PlayerRow(String id, String name, double balance, boolean firstSpawn, boolean guide, String ipHash,
                     int personalSlots, int bossKills, int playerKills, int eventWins) {}
    record FactionRow(long id, String name, String tag, String leader, double balance, int tier, int homeSlots,
                      String world, int coreX, int coreZ) {}
    record FactionMemberRow(String player,String playerName,long factionId,String role,long joinedAt) {}
    record HomeRow(String name, Location location) {}
    record ChatLogRow(long id,String kind,String sender,String senderName,String recipient,String recipientName,String message,long createdAt) {}
    record AuctionRow(long id, String seller, String sellerName, ItemStack item, double price, long listed,
                      long expires, String status, String buyer,double listingFee) {}
    record BountyRow(String target, String targetName, double amount) {}
    record BountyContributionRow(long id,String target,String contributor,String contributorName,String source,double amount,long createdAt) {}
    record PendingBountyClaimRow(long id,String target,String targetName,String killer,String killerName,double amount,double tax,long createdAt,String status,String resolvedBy,long resolvedAt,String replayUrl,boolean replayChecked) {}
    record RelicRow(String key, String owner, String ownerName, long discoveredAt, boolean active) {}
    record StatsRow(String id,String name,long playSeconds,int playerKills,int deaths,int mobKills,int bossKills,int eventWins,double balance) {}
    record PrestigeRow(long id,String name,double prestige,int tier,double bank,int eventWins,int bossKills,int minibossKills,int relics,int bountiesClaimed,int milestones) {}
    record FeedbackRow(long id,String player,String playerName,long createdAt,String message,String status) {}
    record PunishmentRow(String player,String playerName,int tier,int warningCount,long probationUntil,long lastPunishedAt) {}
    record WarningRow(long id,String player,String playerName,String staff,String staffName,String reason,long createdAt,int triggeredTier) {}
    record HistoryRow(long id,String scope,Long factionId,String kind,String message,long createdAt) {}
    record AuditRow(long id,long occurredAt,String adminName,String action,String detail) {}
    record BossStateRow(String entityId,String world,double x,double y,double z,long spawnedAt,int hintStage,long nextHintAt,String rewardState,String origin,double baseHealth,int activeCount,String kind) {}
    record BossContribution(double damage,long lastHit) {}
    record EconomyTotal(String category,double amount) {}
    record ProgressMetrics(int eventWins,int eventParticipations,double gameplayIncome,double serverContributions) {
        double economicScore(){return Math.max(0,gameplayIncome)+Math.max(0,serverContributions);}
    }
    record MerchantRow(String id,String type,String world,double x,double y,double z,float yaw,float pitch,String entityId) {}
    record AssetRow(long factionId,String key,String type,String material,double value,long updatedAt) {}
    record MarketValueRow(String material,double value,int samples,long updatedAt) {}
    record AuctionSaleRow(long id,String seller,String buyer,ItemStack item,double price,long soldAt) {}
    record RelicLifecycleRow(String key,String owner,String ownerName,long discoveredAt,boolean active,String status,long lastConfirmed,long eligibleAt) {}
    record BankRow(double balance,double feeRevenue,double shopProfit,double interestRevenue,double sinkRevenue,double shopPayouts,long updatedAt) {}
    record LoanRow(long id,String player,double principal,double originalAmount,double interest,double rateDaily,long issuedAt,long dueAt,long lastAccrual,String status) {
        double debt(){return Math.max(0,principal)+Math.max(0,interest);}
        boolean overdue(){return "OVERDUE".equals(status);}
    }
    record EliteSpawnRow(String tier,long natural,long custom,long total,long lastSpawn) {}
    record GraveRow(long id,String owner,String ownerUuid,String ownerName,String publicName,String skinValue,String skinSignature,String world,double x,double y,double z,long createdAt,long expiresAt,String markerUuid) {
        Location location(){org.bukkit.World loaded=org.bukkit.Bukkit.getWorld(world);return loaded==null?null:new Location(loaded,x,y,z);}
    }
    record ShardAccount(String player,int balance,long activeSeconds,long afkSeconds,long updatedAt) {}
    record RelationRow(long lowFaction,long highFaction,String type,String pendingType,long requestedBy,
                       boolean storageLow,boolean storageHigh,long updatedAt,boolean homesLow,boolean homesHigh) {
        long other(long faction){return faction==lowFaction?highFaction:lowFaction;}
        boolean active(){return type!=null&&!type.isBlank();}
        boolean sharedStorage(){return "ALLIANCE".equals(type)&&storageLow&&storageHigh;}
        boolean storageApproval(long faction){return faction==lowFaction?storageLow:storageHigh;}
        boolean sharedHomes(){return "ALLIANCE".equals(type)&&homesLow&&homesHigh;}
        boolean homesApproval(long faction){return faction==lowFaction?homesLow:homesHigh;}
    }
    record CosmeticRow(String cosmetic,String player,long unlockedAt,boolean active) {}

    private final SMPCore plugin;
    private Connection connection;

    Database(SMPCore plugin) { this.plugin = plugin; }

    synchronized void open() throws SQLException {
        File file = new File(plugin.getDataFolder(), plugin.getConfig().getString("database.file", "smpcore.db"));
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS players (id TEXT PRIMARY KEY, name TEXT NOT NULL, balance REAL NOT NULL, first_spawn INTEGER NOT NULL DEFAULT 0, guide INTEGER NOT NULL DEFAULT 0, ip_hash TEXT, personal_slots INTEGER NOT NULL DEFAULT 1, boss_kills INTEGER NOT NULL DEFAULT 0, player_kills INTEGER NOT NULL DEFAULT 0, event_wins INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE INDEX IF NOT EXISTS players_name ON players(name)");
            s.execute("CREATE TABLE IF NOT EXISTS factions (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE, tag TEXT UNIQUE COLLATE NOCASE, leader TEXT NOT NULL, balance REAL NOT NULL DEFAULT 0, tier INTEGER NOT NULL DEFAULT 0, home_slots INTEGER NOT NULL DEFAULT 1, world TEXT NOT NULL, core_x INTEGER NOT NULL, core_z INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS faction_members (player TEXT PRIMARY KEY, player_name TEXT NOT NULL, faction_id INTEGER NOT NULL REFERENCES factions(id) ON DELETE CASCADE, role TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS homes (owner TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL COLLATE NOCASE, world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, PRIMARY KEY(owner,type,name))");
            s.execute("CREATE TABLE IF NOT EXISTS bounties (target TEXT PRIMARY KEY, target_name TEXT NOT NULL, amount REAL NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS bounty_claims (killer TEXT NOT NULL, target TEXT NOT NULL, claimed_at INTEGER NOT NULL, ip_blocked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(killer,target))");
            s.execute("CREATE TABLE IF NOT EXISTS bounty_contributions (id INTEGER PRIMARY KEY AUTOINCREMENT, target TEXT NOT NULL, contributor TEXT NOT NULL, contributor_name TEXT NOT NULL, source TEXT NOT NULL, amount REAL NOT NULL, created_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS bounty_contributions_target ON bounty_contributions(target)");
            s.execute("CREATE TABLE IF NOT EXISTS bounty_claims_pending (id INTEGER PRIMARY KEY AUTOINCREMENT, target TEXT NOT NULL, target_name TEXT NOT NULL, killer TEXT NOT NULL, killer_name TEXT NOT NULL, amount REAL NOT NULL, tax REAL NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING', resolved_by TEXT, resolved_at INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE INDEX IF NOT EXISTS bounty_claims_pending_status ON bounty_claims_pending(status,created_at)");
            s.execute("CREATE TABLE IF NOT EXISTS pvp_kill_log (id INTEGER PRIMARY KEY AUTOINCREMENT, killer TEXT NOT NULL, victim TEXT NOT NULL, betrayal INTEGER NOT NULL DEFAULT 0, occurred_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS pvp_kill_log_killer_time ON pvp_kill_log(killer,occurred_at)");
            s.execute("CREATE TABLE IF NOT EXISTS auctions (id INTEGER PRIMARY KEY AUTOINCREMENT, seller TEXT NOT NULL, seller_name TEXT NOT NULL, item BLOB NOT NULL, price REAL NOT NULL, listed INTEGER NOT NULL, expires INTEGER NOT NULL, status TEXT NOT NULL, buyer TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS auctions_status_expires ON auctions(status,expires)");
            s.execute("CREATE TABLE IF NOT EXISTS relics (relic_key TEXT PRIMARY KEY, owner TEXT NOT NULL, owner_name TEXT NOT NULL, discovered_at INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1)");
            s.execute("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS player_preferences (player TEXT NOT NULL, pref_key TEXT NOT NULL, pref_value TEXT NOT NULL, PRIMARY KEY(player,pref_key))");
            s.execute("CREATE TABLE IF NOT EXISTS milestones (player TEXT NOT NULL, milestone TEXT NOT NULL, achieved_at INTEGER NOT NULL, PRIMARY KEY(player,milestone))");
            s.execute("CREATE TABLE IF NOT EXISTS feedback (id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT NOT NULL, player_name TEXT NOT NULL, created_at INTEGER NOT NULL, message TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'OPEN')");
            s.execute("CREATE INDEX IF NOT EXISTS feedback_status_created ON feedback(status,created_at)");
            s.execute("CREATE TABLE IF NOT EXISTS history (id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, faction_id INTEGER, kind TEXT NOT NULL, message TEXT NOT NULL, created_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS history_scope_created ON history(scope,faction_id,created_at DESC)");
            /** Admin-only investigation log — public/faction/DM chat, moderation-facing only (see
             *  MessagingService/GameplayListener/FactionService for the write sites). Nothing existed here
             *  before this table: history only ever goes as far back as the table has been live. */
            s.execute("CREATE TABLE IF NOT EXISTS chat_log (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, sender TEXT NOT NULL, sender_name TEXT NOT NULL, recipient TEXT, recipient_name TEXT, message TEXT NOT NULL, created_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS chat_log_sender ON chat_log(sender,created_at DESC)");
            s.execute("CREATE INDEX IF NOT EXISTS chat_log_recipient ON chat_log(recipient,created_at DESC)");
            s.execute("CREATE TABLE IF NOT EXISTS faction_stats (faction_id INTEGER PRIMARY KEY REFERENCES factions(id) ON DELETE CASCADE, event_wins INTEGER NOT NULL DEFAULT 0, boss_kills INTEGER NOT NULL DEFAULT 0, miniboss_kills INTEGER NOT NULL DEFAULT 0, relics INTEGER NOT NULL DEFAULT 0, bounties_claimed INTEGER NOT NULL DEFAULT 0, milestones INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS daily_sales (player TEXT NOT NULL, item TEXT NOT NULL, day TEXT NOT NULL, quantity INTEGER NOT NULL DEFAULT 0, earned REAL NOT NULL DEFAULT 0, PRIMARY KEY(player,item,day))");
            s.execute("CREATE TABLE IF NOT EXISTS boss_state (entity_id TEXT PRIMARY KEY, world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, spawned_at INTEGER NOT NULL, hint_stage INTEGER NOT NULL DEFAULT 0, next_hint_at INTEGER NOT NULL, reward_state TEXT NOT NULL DEFAULT 'ACTIVE')");
            s.execute("CREATE TABLE IF NOT EXISTS boss_damage (entity_id TEXT NOT NULL REFERENCES boss_state(entity_id) ON DELETE CASCADE, player TEXT NOT NULL, damage REAL NOT NULL, PRIMARY KEY(entity_id,player))");
            s.execute("CREATE TABLE IF NOT EXISTS server_admins (player TEXT PRIMARY KEY, player_name TEXT NOT NULL, granted_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS ender_chest_items (player TEXT NOT NULL, page INTEGER NOT NULL, slot INTEGER NOT NULL, item BLOB NOT NULL, PRIMARY KEY(player,page,slot))");
            s.execute("CREATE TABLE IF NOT EXISTS merchants (id TEXT PRIMARY KEY, type TEXT NOT NULL, world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, entity_id TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS faction_assets (faction_id INTEGER NOT NULL REFERENCES factions(id) ON DELETE CASCADE, asset_key TEXT NOT NULL, asset_type TEXT NOT NULL, material TEXT NOT NULL, value REAL NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(faction_id,asset_key))");
            s.execute("CREATE INDEX IF NOT EXISTS faction_assets_faction ON faction_assets(faction_id)");
            s.execute("CREATE TABLE IF NOT EXISTS market_values (material TEXT PRIMARY KEY, value REAL NOT NULL, samples INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS economy_ledger (id INTEGER PRIMARY KEY AUTOINCREMENT, occurred_at INTEGER NOT NULL, player TEXT, category TEXT NOT NULL, amount REAL NOT NULL, detail TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS economy_ledger_time_category ON economy_ledger(occurred_at,category)");
            s.execute("CREATE TABLE IF NOT EXISTS central_bank (id INTEGER PRIMARY KEY CHECK(id=1), balance REAL NOT NULL DEFAULT 0, fee_revenue REAL NOT NULL DEFAULT 0, shop_profit REAL NOT NULL DEFAULT 0, interest_revenue REAL NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)");
            s.execute("INSERT OR IGNORE INTO central_bank(id,balance,updated_at) VALUES(1,0,0)");
            s.execute("CREATE TABLE IF NOT EXISTS bank_loans (id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT NOT NULL, principal REAL NOT NULL, original_amount REAL NOT NULL, interest REAL NOT NULL DEFAULT 0, rate_daily REAL NOT NULL, issued_at INTEGER NOT NULL, due_at INTEGER NOT NULL, last_accrual INTEGER NOT NULL, status TEXT NOT NULL)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS bank_loans_one_active ON bank_loans(player) WHERE status IN ('ACTIVE','OVERDUE')");
            s.execute("CREATE INDEX IF NOT EXISTS bank_loans_player_issued ON bank_loans(player,issued_at DESC)");
            s.execute("CREATE TABLE IF NOT EXISTS bank_ledger (id INTEGER PRIMARY KEY AUTOINCREMENT, occurred_at INTEGER NOT NULL, player TEXT, category TEXT NOT NULL, amount REAL NOT NULL, detail TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS bank_ledger_time ON bank_ledger(occurred_at)");
            s.execute("CREATE TABLE IF NOT EXISTS elite_spawn_counts (tier TEXT PRIMARY KEY, natural_count INTEGER NOT NULL DEFAULT 0, custom_count INTEGER NOT NULL DEFAULT 0, total_count INTEGER NOT NULL DEFAULT 0, last_spawn INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS graves (id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, owner_name TEXT NOT NULL, world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, marker_uuid TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS graves_owner_expires ON graves(owner,expires_at)");
            s.execute("CREATE TABLE IF NOT EXISTS grave_items (grave_id INTEGER NOT NULL REFERENCES graves(id) ON DELETE CASCADE, slot INTEGER NOT NULL, item BLOB NOT NULL, PRIMARY KEY(grave_id,slot))");
            s.execute("CREATE TABLE IF NOT EXISTS spawner_placements (spawner_id TEXT PRIMARY KEY, faction_id INTEGER NOT NULL, mob_type TEXT NOT NULL, placed_at INTEGER NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS player_spawner_progress (player TEXT PRIMARY KEY, faction_id INTEGER NOT NULL, spawner_id TEXT NOT NULL, placement_time INTEGER NOT NULL, join_time INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS shard_accounts (player TEXT PRIMARY KEY, balance INTEGER NOT NULL DEFAULT 0, active_seconds INTEGER NOT NULL DEFAULT 0, afk_seconds INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS shard_ledger (id INTEGER PRIMARY KEY AUTOINCREMENT, occurred_at INTEGER NOT NULL, player TEXT NOT NULL, amount INTEGER NOT NULL, source TEXT NOT NULL, detail TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS shard_ledger_player_time ON shard_ledger(player,occurred_at)");
            s.execute("CREATE TABLE IF NOT EXISTS shard_cooldowns (player TEXT NOT NULL, source TEXT NOT NULL, last_awarded INTEGER NOT NULL, PRIMARY KEY(player,source))");
            s.execute("CREATE TABLE IF NOT EXISTS shard_purchases (player TEXT NOT NULL, stock_key TEXT NOT NULL, period TEXT NOT NULL, quantity INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(player,stock_key,period))");
            s.execute("CREATE TABLE IF NOT EXISTS cosmetic_unlocks (player TEXT NOT NULL, cosmetic TEXT NOT NULL, unlocked_at INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(player,cosmetic))");
            s.execute("CREATE TABLE IF NOT EXISTS faction_relations (faction_low INTEGER NOT NULL REFERENCES factions(id) ON DELETE CASCADE, faction_high INTEGER NOT NULL REFERENCES factions(id) ON DELETE CASCADE, relation_type TEXT, pending_type TEXT, requested_by INTEGER NOT NULL DEFAULT 0, storage_low INTEGER NOT NULL DEFAULT 0, storage_high INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, PRIMARY KEY(faction_low,faction_high), CHECK(faction_low<faction_high))");
            s.execute("CREATE INDEX IF NOT EXISTS faction_relations_active ON faction_relations(relation_type,faction_low,faction_high)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS faction_one_co_leader ON faction_members(faction_id) WHERE role='CO_LEADER'");
            s.execute("CREATE TABLE IF NOT EXISTS progression_item_claims (item_id TEXT PRIMARY KEY, player TEXT NOT NULL, claimed_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS admin_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, occurred_at INTEGER NOT NULL, admin_name TEXT NOT NULL, action TEXT NOT NULL, detail TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS admin_audit_time ON admin_audit(occurred_at DESC)");
            s.execute("CREATE INDEX IF NOT EXISTS admin_audit_action ON admin_audit(action)");
            /** tier NEVER resets — it is the player's all-time punishment history. warning_count is the
             *  CURRENT active strike count (0-2) toward the next escalation, and resets to 0 every time a
             *  punishment is applied. probation_until is only meaningful while now < probation_until: a warning
             *  issued inside that window immediately escalates instead of counting toward the normal 3 strikes. */
            s.execute("CREATE TABLE IF NOT EXISTS punishments (player TEXT PRIMARY KEY, player_name TEXT NOT NULL, tier INTEGER NOT NULL DEFAULT 0, warning_count INTEGER NOT NULL DEFAULT 0, probation_until INTEGER NOT NULL DEFAULT 0, last_punished_at INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS warnings (id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT NOT NULL, player_name TEXT NOT NULL, staff TEXT NOT NULL, staff_name TEXT NOT NULL, reason TEXT NOT NULL, created_at INTEGER NOT NULL, triggered_tier INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE INDEX IF NOT EXISTS warnings_player ON warnings(player,created_at DESC)");
            s.execute("CREATE TABLE IF NOT EXISTS blocked_messages (blocker TEXT NOT NULL, blocked TEXT NOT NULL, PRIMARY KEY(blocker,blocked))");
            s.execute("CREATE TABLE IF NOT EXISTS ip_bans (ip TEXT PRIMARY KEY, banned_by TEXT NOT NULL, reason TEXT NOT NULL, banned_at INTEGER NOT NULL, expires_at INTEGER NOT NULL DEFAULT 0, source_player TEXT NOT NULL, source_entry TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS saved_locations (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE, world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, structure_type TEXT, manual INTEGER NOT NULL DEFAULT 0, registered_by TEXT, discovered_at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE')");
            s.execute("CREATE TABLE IF NOT EXISTS monument_refill_containers (id INTEGER PRIMARY KEY AUTOINCREMENT, monument_id INTEGER NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, loot_table TEXT, last_refilled_at INTEGER NOT NULL DEFAULT 0, refilled_by TEXT, UNIQUE(world,x,y,z))");
            s.execute("CREATE INDEX IF NOT EXISTS monument_refill_containers_monument ON monument_refill_containers(monument_id)");
            /** The shop's own inventory, as a quantity ledger rather than stored ItemStacks. The shop may
             *  only sell what players have actually sold it, so a row here is real owned stock. Storing
             *  counts per canonical material -- not serialised items -- means custom names, lore and NBT can
             *  never fragment or inflate stock, and there is no pile of thousands of ItemStacks to keep
             *  consistent. Absent row == 0: stock starts empty and is never seeded with invented supply.
             *  The CHECK is a last-resort guard; every mutation is already conditional. */
            /** Last rendered YOUR STATS line per player, so a returning player's bulletin can be drawn the
             *  instant they join instead of blank until the next 40s refresh. Purely a display cache: it is
             *  never read back as authoritative, only shown until the real refresh overwrites it. */
            s.execute("CREATE TABLE IF NOT EXISTS stats_snapshot (player TEXT PRIMARY KEY, panel TEXT NOT NULL, updated_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS shop_stock (material TEXT PRIMARY KEY, quantity INTEGER NOT NULL DEFAULT 0 CHECK(quantity>=0))");
            /** Audit record of items that genuinely left the world. Append-only by construction: nothing
             *  in the codebase deletes from it or reads an item back out of it. */
            /** Represented spawner mobs killed per player, per mob type, per reward day. Counts represented
             *  mobs rather than kill events, so a stack of 100 counts as 100. */
            /** Native buy orders. escrow is the money still held FOR THIS ROW; every movement of it is a
             *  conditional UPDATE so it can never be spent or refunded twice. */
            /** Arena escrow and captured player state. Both live here rather than in memory so a restart
             *  mid-duel neither loses a stake nor strands somebody in a kit. */
            s.execute("CREATE TABLE IF NOT EXISTS arena_escrow (player TEXT PRIMARY KEY, amount REAL NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS arena_wagers (id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT NOT NULL, backed TEXT NOT NULL, amount REAL NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS arena_state (player TEXT PRIMARY KEY, items BLOB NOT NULL, world TEXT NOT NULL, x REAL, y REAL, z REAL, yaw REAL, pitch REAL, level INTEGER, exp REAL, health REAL, food INTEGER, gamemode TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS smp_orders (id INTEGER PRIMARY KEY AUTOINCREMENT, buyer TEXT NOT NULL, buyer_name TEXT NOT NULL, item_key TEXT NOT NULL, amount INTEGER NOT NULL, filled INTEGER NOT NULL DEFAULT 0, unit_price REAL NOT NULL, escrow REAL NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE')");
            s.execute("CREATE INDEX IF NOT EXISTS idx_orders_status ON smp_orders(status)");
            /** Added after the table shipped, so guarded rather than assumed. */
            for(String column:new String[]{"notified INTEGER NOT NULL DEFAULT 0","notified_end INTEGER NOT NULL DEFAULT 0","hidden INTEGER NOT NULL DEFAULT 0"})
                try{s.execute("ALTER TABLE smp_orders ADD COLUMN "+column);}catch(SQLException ignored){}
            /** Goods delivered to an offline or full buyer. Held until collected; never auto-granted. */
            s.execute("CREATE TABLE IF NOT EXISTS smp_order_stash (id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, item BLOB NOT NULL, created_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_stash_owner ON smp_order_stash(owner)");
            s.execute("CREATE TABLE IF NOT EXISTS spawner_kill_counts (player TEXT NOT NULL, mob_type TEXT NOT NULL, day TEXT NOT NULL, killed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(player,mob_type,day))");
            s.execute("CREATE TABLE IF NOT EXISTS discarded_ledger (id INTEGER PRIMARY KEY AUTOINCREMENT, occurred_at INTEGER NOT NULL, material TEXT NOT NULL, amount INTEGER NOT NULL, reason TEXT NOT NULL, recycled INTEGER NOT NULL DEFAULT 0, world TEXT NOT NULL DEFAULT '', x INTEGER NOT NULL DEFAULT 0, y INTEGER NOT NULL DEFAULT 0, z INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_discarded_material ON discarded_ledger(material)");
            s.execute("CREATE TABLE IF NOT EXISTS staff_notes (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, note TEXT NOT NULL, staff_name TEXT NOT NULL, created_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS staff_notes_player ON staff_notes(player_uuid,created_at DESC)");
            // 1.5 removes private container ownership. This table never held items, so dropping it is lossless.
            s.execute("DROP TABLE IF EXISTS private_chests");
        }
        ensureColumn("players","deaths","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("players","mob_kills","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("players","play_seconds","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("players","last_seen","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("players","event_participations","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("players","ender_pages","INTEGER NOT NULL DEFAULT 1");
        ensureColumn("players","last_world","TEXT");
        ensureColumn("players","last_x","REAL");
        ensureColumn("players","last_y","REAL");
        ensureColumn("players","last_z","REAL");
        // The ONE persisted safe-wilderness location a genuinely new player is placed at on their very
        // first spawn, reused as their death respawn point until they set a real bed/anchor. NULL for
        // every player who already existed before this feature shipped -- they're simply never assigned
        // one, so their respawn behavior is completely unchanged (see TeleportService.persistedSpawn()).
        ensureColumn("players","persisted_spawn_world","TEXT");
        ensureColumn("players","persisted_spawn_x","REAL");
        ensureColumn("players","persisted_spawn_y","REAL");
        ensureColumn("players","persisted_spawn_z","REAL");
        // 0 = free 27-slot Ender Chest tier. Migrated once from the old chunk-count model by
        // /ashfall enderchest migrate (see EnderChestService.migrateTiers()) -- not automatic on startup,
        // since production data needs a deliberate, reported run rather than a silent boot-time change.
        ensureColumn("players","ender_tier","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("factions","tag","TEXT");
        ensureColumn("factions","claimed_land","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("factions","set_home","INTEGER NOT NULL DEFAULT 0");
        /** Best-effort historical backfill for the new "be part of a faction that has ever claimed land / set a
         *  home" progression missions: a faction currently holding a claim or a home obviously satisfies the
         *  condition even though these flags didn't exist before now. Idempotent — safe to run every boot. */
        update("UPDATE factions SET claimed_land=1 WHERE tier>=0 AND claimed_land=0");
        update("UPDATE factions SET claimed_land=1 WHERE claimed_land=0 AND id IN (SELECT DISTINCT faction_id FROM history WHERE scope='FACTION' AND kind IN ('CLAIM','EXPANSION') AND faction_id IS NOT NULL)");
        update("UPDATE factions SET set_home=1 WHERE set_home=0 AND EXISTS (SELECT 1 FROM homes WHERE homes.type='FACTION' AND homes.owner=CAST(factions.id AS TEXT))");
        ensureColumn("relics","status","TEXT NOT NULL DEFAULT 'ACTIVE'");
        ensureColumn("relics","last_confirmed","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("relics","eligible_at","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("boss_state","origin","TEXT NOT NULL DEFAULT 'NATURAL'");
        ensureColumn("boss_state","base_health","REAL NOT NULL DEFAULT 700");
        ensureColumn("boss_state","active_count","INTEGER NOT NULL DEFAULT 1");
        ensureColumn("boss_state","kind","TEXT NOT NULL DEFAULT 'ASHEN_KNIGHT'");
        ensureColumn("boss_damage","last_hit","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("auctions","sold_at","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("auctions","listing_fee","REAL NOT NULL DEFAULT 0");
        ensureColumn("bounty_claims_pending","replay_url","TEXT");
        ensureColumn("bounty_claims_pending","replay_checked","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("central_bank","sink_revenue","REAL NOT NULL DEFAULT 0");
        ensureColumn("central_bank","shop_payouts","REAL NOT NULL DEFAULT 0");
        ensureColumn("faction_members","joined_at","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("faction_relations","homes_low","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("faction_relations","homes_high","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("graves","owner_uuid","TEXT");
        ensureColumn("graves","public_name","TEXT");
        ensureColumn("graves","skin_value","TEXT");
        ensureColumn("graves","skin_signature","TEXT");
        ensureColumn("saved_locations","boundary_radius","INTEGER NOT NULL DEFAULT 24");
        ensureColumn("saved_locations","last_snapshot_at","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("saved_locations","last_restored_at","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("saved_locations","last_refill_at","INTEGER NOT NULL DEFAULT 0");
        // Real structure bounding box, from Paper's GeneratedStructure API (covers every connected piece of
        // e.g. an Ancient City or Trial Chambers, not a guessed cube) -- NULL until locate() captures one, at
        // which point all six are set together. Falls back to boundary_radius around the center when absent
        // (manual/"custom" registrations, or a structure type Paper doesn't expose GeneratedStructure data for).
        ensureColumn("saved_locations","min_x","INTEGER");
        ensureColumn("saved_locations","min_y","INTEGER");
        ensureColumn("saved_locations","min_z","INTEGER");
        ensureColumn("saved_locations","max_x","INTEGER");
        ensureColumn("saved_locations","max_y","INTEGER");
        ensureColumn("saved_locations","max_z","INTEGER");
        for(GraveRow grave:list("SELECT * FROM graves WHERE owner_uuid IS NULL OR owner_uuid=''",Database::mapGrave)){
            UUID uuid=org.bukkit.Bukkit.getOfflinePlayer(grave.ownerName()).getUniqueId();
            update("UPDATE graves SET owner_uuid=?,public_name=COALESCE(NULLIF(public_name,''),owner_name) WHERE id=?",uuid.toString(),grave.id());
        }
        try(Statement s=connection.createStatement()){s.execute("CREATE INDEX IF NOT EXISTS graves_owner_uuid_expires ON graves(owner_uuid,expires_at)");}
        update("UPDATE faction_members SET joined_at=? WHERE joined_at<=0",System.currentTimeMillis());
        ensureFactionTags();
        try(Statement s=connection.createStatement()){s.execute("CREATE UNIQUE INDEX IF NOT EXISTS factions_tag_unique ON factions(tag COLLATE NOCASE) WHERE tag IS NOT NULL");}
        update("UPDATE relics SET status=CASE WHEN active=1 THEN 'ACTIVE' ELSE 'RETIRED' END WHERE status IS NULL OR status='' ");
    }

    private void ensureColumn(String table,String column,String definition)throws SQLException{
        boolean found=false;try(Statement s=connection.createStatement();ResultSet rs=s.executeQuery("PRAGMA table_info("+table+")")){while(rs.next())if(rs.getString("name").equalsIgnoreCase(column)){found=true;break;}}
        if(!found)try(Statement s=connection.createStatement()){s.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+definition);}
    }

    synchronized PlayerRow ensurePlayer(String id, String displayName, double startingBalance) {
        update("INSERT OR IGNORE INTO players(id,name,balance) VALUES(?,?,?)", id, displayName, startingBalance);
        update("UPDATE players SET name=? WHERE id=?", displayName, id);
        return player(id);
    }

    synchronized PlayerRow player(String id) {
        return one("SELECT * FROM players WHERE id=?", rs -> new PlayerRow(rs.getString("id"), rs.getString("name"), rs.getDouble("balance"), rs.getInt("first_spawn") != 0, rs.getInt("guide") != 0, rs.getString("ip_hash"), rs.getInt("personal_slots"), rs.getInt("boss_kills"), rs.getInt("player_kills"), rs.getInt("event_wins")), id);
    }

    synchronized boolean changeBalance(String id, double delta) {
        if (!Double.isFinite(delta)) return false;
        if (delta < 0) return update("UPDATE players SET balance=balance+? WHERE id=? AND balance+?>=-0.00001", delta, id, delta) == 1;
        return update("UPDATE players SET balance=balance+? WHERE id=?", delta, id) == 1;
    }

    synchronized void setBalance(String id, double value) { update("UPDATE players SET balance=? WHERE id=?", Math.max(0, value), id); }
    synchronized double takeUpTo(String id,double requested){PlayerRow row=player(id);if(row==null||requested<=0)return 0;double taken=Math.round(Math.min(row.balance(),requested)*100.0)/100.0;if(taken>0)changeBalance(id,-taken);return taken;}
    synchronized double takeFraction(String id,double fraction,double cap){PlayerRow row=player(id);if(row==null)return 0;return takeUpTo(id,Math.min(Math.max(0,cap),row.balance()*Math.max(0,fraction)));}
    synchronized double transferFraction(String from,String to,double fraction,double cap){
        try{connection.setAutoCommit(false);PlayerRow source=player(from);if(source==null){connection.rollback();return 0;}double amount=Math.round(Math.min(Math.max(0,cap),source.balance()*fraction)*100.0)/100.0;if(amount<=0){connection.rollback();return 0;}if(!changeBalance(from,-amount)){connection.rollback();return 0;}PlayerRow target=player(to);if(target==null)throw new SQLException("Target economy account missing");changeBalance(to,amount);connection.commit();return amount;}catch(SQLException e){rollbackQuietly();throw fail(e);}finally{autoCommitQuietly();}
    }
    synchronized void setPlayerFlag(String id, String column, boolean value) {
        if (!Set.of("first_spawn", "guide").contains(column)) throw new IllegalArgumentException("flag");
        update("UPDATE players SET " + column + "=? WHERE id=?", value ? 1 : 0, id);
    }
    synchronized void setIpHash(String id, String hash) { update("UPDATE players SET ip_hash=? WHERE id=?", hash, id); }
    synchronized void setPersonalSlots(String id, int slots) { update("UPDATE players SET personal_slots=? WHERE id=?", slots, id); }
    synchronized int enderPages(String id){return Math.max(1,integer("SELECT ender_pages FROM players WHERE id=?",id));}
    synchronized void setEnderPages(String id,int pages){update("UPDATE players SET ender_pages=? WHERE id=?",Math.max(1,Math.min(64,pages)),id);}
    /** Replaces the old chunk-count model (ender_pages, still present but no longer read for capacity —
     *  see EnderChestService's tier redesign). 0 = free 27-slot tier, matching every player who never
     *  bought anything; never decreases once migrated/purchased. */
    synchronized int enderTier(String id){return Math.max(0,integer("SELECT ender_tier FROM players WHERE id=?",id));}
    synchronized void setEnderTier(String id,int tier){update("UPDATE players SET ender_tier=? WHERE id=?",Math.max(0,tier),id);}
    synchronized List<String> allPlayerIds(){return list("SELECT id FROM players",rs->rs.getString("id"));}
    /** Everyone who ever paid for the OLD single chunk-2 upgrade (EnderChestService.purchase()'s only
     *  possible non-1 "next" value under the old max-upgrades:2 model) — used by the tier migration to
     *  floor their new tier at 1 even if their expanded space happens to be empty right now. */
    synchronized Set<String> legacyEnderStorageBuyers(){return new HashSet<>(list("SELECT DISTINCT player FROM economy_ledger WHERE category='UPGRADE_SINK' AND detail='ENDER_STORAGE_2' AND player IS NOT NULL",rs->rs.getString("player")));}
    synchronized void migrateEnderStorageAdditive(){
        if("done".equals(state("ender_storage_additive_v1")))return;
        boolean own=false;
        try{
            own=connection.getAutoCommit();
            if(own)connection.setAutoCommit(false);
            update("UPDATE players SET ender_pages=CASE ender_pages WHEN 3 THEN 4 WHEN 4 THEN 8 ELSE MAX(1,ender_pages) END");
            state("ender_storage_additive_v1","done");
            if(own)connection.commit();
        }catch(SQLException error){
            if(own)rollbackQuietly();
            throw fail(error);
        }finally{if(own)autoCommitQuietly();}
    }
    synchronized ItemStack[] enderPage(String id,int page){ItemStack[] items=new ItemStack[45];for(var entry:list("SELECT slot,item FROM ender_chest_items WHERE player=? AND page=? ORDER BY slot",rs->Map.entry(rs.getInt("slot"),ItemStack.deserializeBytes(rs.getBytes("item"))),id,page))if(entry.getKey()>=0&&entry.getKey()<items.length)items[entry.getKey()]=entry.getValue();return items;}
    synchronized void saveEnderPage(String id,int page,ItemStack[] items){boolean own=false;try{own=connection.getAutoCommit();if(own)connection.setAutoCommit(false);update("DELETE FROM ender_chest_items WHERE player=? AND page=?",id,page);for(int slot=0;slot<Math.min(45,items.length);slot++){ItemStack item=items[slot];if(item!=null&&!item.getType().isAir())update("INSERT INTO ender_chest_items(player,page,slot,item) VALUES(?,?,?,?)",id,page,slot,item.serializeAsBytes());}if(own)connection.commit();}catch(SQLException e){if(own)rollbackQuietly();throw fail(e);}finally{if(own)autoCommitQuietly();}}
    synchronized void incrementStat(String id, String column) {
        if (!Set.of("boss_kills", "player_kills", "event_wins", "deaths", "mob_kills", "event_participations").contains(column)) throw new IllegalArgumentException("stat");
        update("UPDATE players SET " + column + "=" + column + "+1 WHERE id=?", id);
    }
    synchronized void addPlaySeconds(String id,long seconds){if(seconds>0)update("UPDATE players SET play_seconds=play_seconds+?,last_seen=? WHERE id=?",seconds,System.currentTimeMillis(),id);}
    synchronized void setPlaySecondsAtLeast(String id,long seconds){if(seconds>=0)update("UPDATE players SET play_seconds=MAX(play_seconds,?) WHERE id=?",seconds,id);}
    synchronized void touchPlayer(String id){update("UPDATE players SET last_seen=? WHERE id=?",System.currentTimeMillis(),id);}
    synchronized long lastSeen(String id){String value=scalar("SELECT CAST(last_seen AS TEXT) FROM players WHERE id=?",id);try{return value==null?0:Long.parseLong(value);}catch(NumberFormatException e){return 0;}}
    synchronized StatsRow stats(String id){return one("SELECT id,name,play_seconds,player_kills,deaths,mob_kills,boss_kills,event_wins,balance FROM players WHERE id=?",Database::mapStats,id);}
    synchronized StatsRow statsByName(String name){return one("SELECT id,name,play_seconds,player_kills,deaths,mob_kills,boss_kills,event_wins,balance FROM players WHERE name=? COLLATE NOCASE",Database::mapStats,name);}
    synchronized List<StatsRow> topStats(String column){return topStats(column,10,0);}
    /** One bulk read backing the bulletin's personal-stats panels — avoids a separate per-player query on
     *  every refresh cycle (see BulletinService.refreshPersonalPanels()). Keyed by player id. */
    synchronized Map<String,StatsRow> statsSnapshot(){Map<String,StatsRow> map=new HashMap<>();for(StatsRow row:list("SELECT id,name,play_seconds,player_kills,deaths,mob_kills,boss_kills,event_wins,balance FROM players",Database::mapStats))map.put(row.id(),row);return map;}
    synchronized Map<String,Integer> shardBalanceSnapshot(){
        Map<String,Integer> map=new HashMap<>();
        try(PreparedStatement ps=connection.prepareStatement("SELECT player,balance FROM shard_accounts");ResultSet rs=ps.executeQuery()){
            while(rs.next())map.put(rs.getString("player"),rs.getInt("balance"));
        }catch(SQLException e){throw fail(e);}
        return map;
    }
    synchronized List<StatsRow> topStats(String column,int limit,int offset){if(!Set.of("play_seconds","player_kills","deaths","mob_kills","boss_kills","event_wins","balance").contains(column))throw new IllegalArgumentException("stat");return list("SELECT id,name,play_seconds,player_kills,deaths,mob_kills,boss_kills,event_wins,balance FROM players ORDER BY "+column+" DESC,name LIMIT "+Math.max(1,limit)+" OFFSET "+Math.max(0,offset),Database::mapStats);}
    private final Map<String,Map<String,String>> preferenceCache = new HashMap<>();
    synchronized String preference(String player,String key){
        Map<String,String> cached = preferenceCache.get(player);
        if(cached == null){
            cached = new HashMap<>();
            for(Map.Entry<String,String> row : list("SELECT pref_key,pref_value FROM player_preferences WHERE player=?", rs -> Map.entry(rs.getString("pref_key"), rs.getString("pref_value")), player)) cached.put(row.getKey(), row.getValue());
            preferenceCache.put(player, cached);
        }
        return cached.get(key);
    }
    synchronized void preference(String player,String key,String value){
        update("INSERT INTO player_preferences(player,pref_key,pref_value) VALUES(?,?,?) ON CONFLICT(player,pref_key) DO UPDATE SET pref_value=excluded.pref_value",player,key,value);
        preferenceCache.computeIfAbsent(player,k->new HashMap<>()).put(key,value);
    }
    synchronized void forgetPreferences(String player){preferenceCache.remove(player);}
    synchronized boolean isServerAdmin(String player){return integer("SELECT COUNT(*) FROM server_admins WHERE player=?",player)>0;}
    synchronized void grantServerAdmin(String player,String name){update("INSERT INTO server_admins(player,player_name,granted_at) VALUES(?,?,?) ON CONFLICT(player) DO UPDATE SET player_name=excluded.player_name",player,name,System.currentTimeMillis());}
    synchronized void revokeServerAdmin(String player){update("DELETE FROM server_admins WHERE player=?",player);}
    synchronized List<String> serverAdmins(){return strings("SELECT player_name FROM server_admins ORDER BY player_name");}
    /** Removes a completed milestone. Used when a mission's requirements change and a player no longer
     *  satisfies them; safe because the affected mission grants no one-time payout. */
    synchronized boolean clearMilestone(String player,String milestone){return update("DELETE FROM milestones WHERE player=? AND milestone=?",player,milestone)>0;}
    synchronized boolean markMilestone(String player,String milestone){return update("INSERT OR IGNORE INTO milestones(player,milestone,achieved_at) VALUES(?,?,?)",player,milestone,System.currentTimeMillis())==1;}
    synchronized int milestoneCount(String player){return integer("SELECT COUNT(*) FROM milestones WHERE player=?",player);}
    synchronized boolean hasMilestone(String player,String milestone){return integer("SELECT COUNT(*) FROM milestones WHERE player=? AND milestone=?",player,milestone)>0;}
    synchronized Set<String> milestones(String player){return new LinkedHashSet<>(strings("SELECT milestone FROM milestones WHERE player=? ORDER BY achieved_at",player));}

    synchronized ShardAccount shardAccount(String player){
        update("INSERT OR IGNORE INTO shard_accounts(player,updated_at) VALUES(?,?)",player,System.currentTimeMillis());
        return one("SELECT * FROM shard_accounts WHERE player=?",rs->new ShardAccount(rs.getString("player"),rs.getInt("balance"),rs.getLong("active_seconds"),rs.getLong("afk_seconds"),rs.getLong("updated_at")),player);
    }
    synchronized int shardBalance(String player){return shardAccount(player).balance();}
    /** Non-playtime shards a player has earned since a timestamp, for the daily allowance. Passive
     *  playtime accrual (ACTIVE_PLAYTIME / AFK_PLAYTIME) is excluded at the query level so it can never
     *  consume the cap, and negative ledger rows (spends, admin deductions) are ignored so spending shards
     *  cannot refund allowance. */
    synchronized int shardsEarnedSince(String player,long since){
        return integer("SELECT COALESCE(SUM(amount),0) FROM shard_ledger WHERE player=? AND occurred_at>=? AND amount>0 AND source NOT IN ('ACTIVE_PLAYTIME','AFK_PLAYTIME')",player,since);
    }
    synchronized int accrueShardTime(String player,boolean active,long seconds,int activeThreshold,int afkThreshold){
        if(seconds<=0)return 0;ShardAccount before=shardAccount(player);long activeSeconds=before.activeSeconds()+(active?seconds:0),afkSeconds=before.afkSeconds()+(active?0:seconds);int awarded=0;
        while(activeSeconds>=activeThreshold){activeSeconds-=activeThreshold;awarded++;}
        while(afkSeconds>=afkThreshold){afkSeconds-=afkThreshold;awarded++;}
        update("UPDATE shard_accounts SET balance=balance+?,active_seconds=?,afk_seconds=?,updated_at=? WHERE player=?",awarded,activeSeconds,afkSeconds,System.currentTimeMillis(),player);
        if(awarded>0)update("INSERT INTO shard_ledger(occurred_at,player,amount,source,detail) VALUES(?,?,?,?,?)",System.currentTimeMillis(),player,awarded,active?"ACTIVE_PLAYTIME":"AFK_PLAYTIME",null);
        return awarded;
    }
    synchronized void addShards(String player,int amount,String source,String detail){
        if(amount<=0)return;shardAccount(player);update("UPDATE shard_accounts SET balance=balance+?,updated_at=? WHERE player=?",amount,System.currentTimeMillis(),player);
        update("INSERT INTO shard_ledger(occurred_at,player,amount,source,detail) VALUES(?,?,?,?,?)",System.currentTimeMillis(),player,amount,source,detail);
    }
    synchronized boolean spendShards(String player,int amount,String source){
        if(amount<=0)return false;shardAccount(player);if(update("UPDATE shard_accounts SET balance=balance-?,updated_at=? WHERE player=? AND balance>=?",amount,System.currentTimeMillis(),player,amount)!=1)return false;
        update("INSERT INTO shard_ledger(occurred_at,player,amount,source,detail) VALUES(?,?,?,?,?)",System.currentTimeMillis(),player,-amount,"SHOP_PURCHASE",source);return true;
    }
    synchronized boolean claimShardCooldown(String player,String source,long cooldownMillis){
        long now=System.currentTimeMillis(),last=0;String raw=scalar("SELECT CAST(last_awarded AS TEXT) FROM shard_cooldowns WHERE player=? AND source=?",player,source);try{if(raw!=null)last=Long.parseLong(raw);}catch(NumberFormatException ignored){}
        if(now-last<Math.max(0,cooldownMillis))return false;update("INSERT INTO shard_cooldowns(player,source,last_awarded) VALUES(?,?,?) ON CONFLICT(player,source) DO UPDATE SET last_awarded=excluded.last_awarded",player,source,now);return true;
    }
    synchronized int adjustShardsAdmin(String player,int delta){
        shardAccount(player);
        if(delta>0)addShards(player,delta,"ADMIN",null);
        else if(delta<0){int take=Math.min(shardBalance(player),-delta);if(take>0){update("UPDATE shard_accounts SET balance=balance-?,updated_at=? WHERE player=?",take,System.currentTimeMillis(),player);update("INSERT INTO shard_ledger(occurred_at,player,amount,source,detail) VALUES(?,?,?,?,?)",System.currentTimeMillis(),player,-take,"ADMIN",null);}}
        return shardBalance(player);
    }
    synchronized int setShardsAdmin(String player,int amount){shardAccount(player);return adjustShardsAdmin(player,Math.max(0,amount)-shardBalance(player));}
    synchronized int shardPurchaseCount(String player,String stock,String period){return integer("SELECT quantity FROM shard_purchases WHERE player=? AND stock_key=? AND period=?",player,stock,period);}
    synchronized void recordShardPurchase(String player,String stock,String period,int amount){update("INSERT INTO shard_purchases(player,stock_key,period,quantity) VALUES(?,?,?,?) ON CONFLICT(player,stock_key,period) DO UPDATE SET quantity=quantity+excluded.quantity",player,stock,period,amount);}
    synchronized boolean unlockCosmetic(String player,String cosmetic){return update("INSERT OR IGNORE INTO cosmetic_unlocks(player,cosmetic,unlocked_at,active) VALUES(?,?,?,0)",player,cosmetic,System.currentTimeMillis())==1;}
    synchronized void activateCosmetic(String player,String cosmetic){update("UPDATE cosmetic_unlocks SET active=0 WHERE player=?",player);if(cosmetic!=null&&!cosmetic.isBlank())update("UPDATE cosmetic_unlocks SET active=1 WHERE player=? AND cosmetic=?",player,cosmetic);}
    synchronized String activeCosmetic(String player){return scalar("SELECT cosmetic FROM cosmetic_unlocks WHERE player=? AND active=1 LIMIT 1",player);}
    synchronized List<CosmeticRow> cosmetics(String player){return list("SELECT cosmetic,player,unlocked_at,active FROM cosmetic_unlocks WHERE player=? ORDER BY unlocked_at",rs->new CosmeticRow(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getInt(4)!=0),player);}

    synchronized FactionRow createFaction(String name, String leader, String leaderName, Location core) {
        try {
            connection.setAutoCommit(false);
            long id;
            String tag=availableFactionTag(name,-1);
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO factions(name,tag,leader,world,core_x,core_z) VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, name, tag, leader, core.getWorld().getName(), core.getBlockX(), core.getBlockZ());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) { if (!keys.next()) throw new SQLException("No faction id"); id = keys.getLong(1); }
            }
            update("UPDATE factions SET tier=-1 WHERE id=?",id);
            update("INSERT INTO faction_members(player,player_name,faction_id,role,joined_at) VALUES(?,?,?,'LEADER',?)", leader, leaderName, id,System.currentTimeMillis());
            connection.commit();
            return faction(id);
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IllegalStateException(e);
        } finally { autoCommitQuietly(); }
    }

    synchronized FactionRow faction(long id) { return one("SELECT * FROM factions WHERE id=?", Database::mapFaction, id); }
    synchronized FactionRow factionByName(String name) { return one("SELECT * FROM factions WHERE name=? COLLATE NOCASE", Database::mapFaction, name); }
    synchronized FactionRow factionByTag(String tag){return one("SELECT * FROM factions WHERE tag=? COLLATE NOCASE",Database::mapFaction,tag);}
    synchronized FactionRow factionOf(String player) { return one("SELECT f.* FROM factions f JOIN faction_members m ON m.faction_id=f.id WHERE m.player=?", Database::mapFaction, player); }
    synchronized List<String> factionMembers(long factionId) { return strings("SELECT player_name FROM faction_members WHERE faction_id=? ORDER BY CASE role WHEN 'LEADER' THEN 0 WHEN 'CO_LEADER' THEN 1 ELSE 2 END, player_name", factionId); }
    synchronized List<FactionMemberRow> factionMemberRows(long factionId){return list("SELECT player,player_name,faction_id,role,joined_at FROM faction_members WHERE faction_id=? ORDER BY CASE role WHEN 'LEADER' THEN 0 WHEN 'CO_LEADER' THEN 1 ELSE 2 END,joined_at",rs->new FactionMemberRow(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getLong(5)),factionId);}
    synchronized long factionJoinTime(String player){String value=scalar("SELECT CAST(joined_at AS TEXT) FROM faction_members WHERE player=?",player);try{return value==null?0:Long.parseLong(value);}catch(NumberFormatException e){return 0;}}
    synchronized String roleOf(String player) { return scalar("SELECT role FROM faction_members WHERE player=?", player); }
    synchronized int factionMemberCount(long id) { return integer("SELECT COUNT(*) FROM faction_members WHERE faction_id=?", id); }
    synchronized void addMember(long faction, String player, String name) { update("INSERT INTO faction_members(player,player_name,faction_id,role,joined_at) VALUES(?,?,?,'MEMBER',?)", player, name, faction,System.currentTimeMillis()); }
    synchronized void removeMember(String player) { update("DELETE FROM faction_members WHERE player=?", player); }
    synchronized void setLeader(long faction, String player) { update("UPDATE faction_members SET role='MEMBER' WHERE faction_id=? AND role='LEADER'", faction); update("UPDATE faction_members SET role='LEADER' WHERE player=? AND faction_id=?", player,faction); update("UPDATE factions SET leader=? WHERE id=?", player, faction); }
    synchronized String coLeader(long faction){return scalar("SELECT player FROM faction_members WHERE faction_id=? AND role='CO_LEADER' LIMIT 1",faction);}
    synchronized void setCoLeader(long faction,String player){
        update("UPDATE faction_members SET role='MEMBER' WHERE faction_id=? AND role='CO_LEADER'",faction);
        if(player!=null&&!player.isBlank())update("UPDATE faction_members SET role='CO_LEADER' WHERE faction_id=? AND player=? AND role<>'LEADER'",faction,player);
    }
    synchronized void deleteFaction(long id) { update("DELETE FROM factions WHERE id=?", id); update("DELETE FROM homes WHERE owner=? AND type='FACTION'", Long.toString(id)); }
    synchronized boolean changeFactionBalance(long id, double delta) {
        if (!Double.isFinite(delta)) return false;
        if (delta < 0) return update("UPDATE factions SET balance=balance+? WHERE id=? AND balance+?>=-0.00001", delta, id, delta) == 1;
        return update("UPDATE factions SET balance=balance+? WHERE id=?", delta, id) == 1;
    }
    synchronized void setFactionTier(long id, int tier) { update("UPDATE factions SET tier=? WHERE id=?", tier, id); }
    synchronized void setFactionCoreTier(long id, int x, int z, int tier) { update("UPDATE factions SET core_x=?,core_z=?,tier=? WHERE id=?", x, z, tier, id); }
    synchronized void setFactionHomeSlots(long id, int slots) { update("UPDATE factions SET home_slots=? WHERE id=?", slots, id); }
    synchronized boolean setFactionTag(long id,String tag){return update("UPDATE factions SET tag=? WHERE id=? AND NOT EXISTS(SELECT 1 FROM factions WHERE tag=? COLLATE NOCASE AND id<>?)",tag,id,tag,id)==1;}
    synchronized List<FactionRow> factions() { return list("SELECT * FROM factions", Database::mapFaction); }
    synchronized RelationRow relation(long first,long second){if(first==second)return null;long low=Math.min(first,second),high=Math.max(first,second);return one("SELECT * FROM faction_relations WHERE faction_low=? AND faction_high=?",Database::mapRelation,low,high);}
    synchronized List<RelationRow> relations(long faction){return list("SELECT * FROM faction_relations WHERE faction_low=? OR faction_high=? ORDER BY updated_at DESC",Database::mapRelation,faction,faction);}
    synchronized int allianceCount(long faction){return integer("SELECT COUNT(*) FROM faction_relations WHERE relation_type='ALLIANCE' AND (faction_low=? OR faction_high=?)",faction,faction);}
    synchronized void requestRelation(long first,long second,String requested,long requester){
        long low=Math.min(first,second),high=Math.max(first,second);update("INSERT INTO faction_relations(faction_low,faction_high,pending_type,requested_by,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(faction_low,faction_high) DO UPDATE SET pending_type=excluded.pending_type,requested_by=excluded.requested_by,updated_at=excluded.updated_at",low,high,requested,requester,System.currentTimeMillis());
    }
    synchronized void acceptRelation(long first,long second,String type){
        long low=Math.min(first,second),high=Math.max(first,second);update("UPDATE faction_relations SET relation_type=?,pending_type=NULL,requested_by=0,storage_low=0,storage_high=0,homes_low=0,homes_high=0,updated_at=? WHERE faction_low=? AND faction_high=?",type,System.currentTimeMillis(),low,high);
    }
    synchronized void clearRelation(long first,long second){long low=Math.min(first,second),high=Math.max(first,second);update("DELETE FROM faction_relations WHERE faction_low=? AND faction_high=?",low,high);}
    synchronized void storageApproval(long first,long second,long faction,boolean enabled){
        long low=Math.min(first,second),high=Math.max(first,second);String column=faction==low?"storage_low":"storage_high";update("UPDATE faction_relations SET "+column+"=?,updated_at=? WHERE faction_low=? AND faction_high=? AND relation_type='ALLIANCE'",enabled?1:0,System.currentTimeMillis(),low,high);
    }
    synchronized void homesApproval(long first,long second,long faction,boolean enabled){
        long low=Math.min(first,second),high=Math.max(first,second);String column=faction==low?"homes_low":"homes_high";update("UPDATE faction_relations SET "+column+"=?,updated_at=? WHERE faction_low=? AND faction_high=? AND relation_type='ALLIANCE'",enabled?1:0,System.currentTimeMillis(),low,high);
    }
    synchronized void incrementFactionStat(long faction,String column){if(!Set.of("event_wins","boss_kills","miniboss_kills","relics","bounties_claimed","milestones").contains(column))throw new IllegalArgumentException("faction stat");update("INSERT OR IGNORE INTO faction_stats(faction_id) VALUES(?)",faction);update("UPDATE faction_stats SET "+column+"="+column+"+1 WHERE faction_id=?",faction);}
    synchronized List<PrestigeRow> factionPrestige(){String sql="SELECT f.id,f.name,f.tier,f.balance,COALESCE(s.event_wins,0) event_wins,COALESCE(s.boss_kills,0) boss_kills,COALESCE(s.miniboss_kills,0) miniboss_kills,COALESCE(s.relics,0) relics,COALESCE(s.bounties_claimed,0) bounties_claimed,COALESCE(s.milestones,0) milestones,(CASE WHEN f.tier<0 THEN 0 ELSE 250+f.tier*1000 END)+MIN(f.balance/100.0,2500)+COALESCE(s.event_wins,0)*500+COALESCE(s.boss_kills,0)*750+COALESCE(s.miniboss_kills,0)*300+COALESCE(s.relics,0)*600+COALESCE(s.bounties_claimed,0)*250+COALESCE(s.milestones,0)*150 prestige FROM factions f LEFT JOIN faction_stats s ON s.faction_id=f.id ORDER BY prestige DESC,f.name LIMIT 10";return list(sql,rs->new PrestigeRow(rs.getLong("id"),rs.getString("name"),rs.getDouble("prestige"),rs.getInt("tier"),rs.getDouble("balance"),rs.getInt("event_wins"),rs.getInt("boss_kills"),rs.getInt("miniboss_kills"),rs.getInt("relics"),rs.getInt("bounties_claimed"),rs.getInt("milestones")));}

    synchronized void putHome(String owner, String type, String name, Location l) {
        update("INSERT INTO homes(owner,type,name,world,x,y,z,yaw,pitch) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(owner,type,name) DO UPDATE SET world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch", owner, type, name, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }
    synchronized void deleteHome(String owner, String type, String name) { update("DELETE FROM homes WHERE owner=? AND type=? AND name=? COLLATE NOCASE", owner, type, name); }
    synchronized boolean renameHome(String owner,String type,String oldName,String newName){
        if(oldName.equalsIgnoreCase(newName))return update("UPDATE homes SET name=? WHERE owner=? AND type=? AND name=? COLLATE NOCASE",newName,owner,type,oldName)==1;
        return update("UPDATE homes SET name=? WHERE owner=? AND type=? AND name=? COLLATE NOCASE AND NOT EXISTS(SELECT 1 FROM homes WHERE owner=? AND type=? AND name=? COLLATE NOCASE)",newName,owner,type,oldName,owner,type,newName)==1;
    }
    synchronized List<HomeRow> homes(String owner, String type) {
        return list("SELECT * FROM homes WHERE owner=? AND type=? ORDER BY name", rs -> new HomeRow(rs.getString("name"), new Location(plugin.getServer().getWorld(rs.getString("world")), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch"))), owner, type);
    }
    synchronized HomeRow home(String owner, String type, String name) {
        return one("SELECT * FROM homes WHERE owner=? AND type=? AND name=? COLLATE NOCASE", rs -> new HomeRow(rs.getString("name"), new Location(plugin.getServer().getWorld(rs.getString("world")), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch"))), owner, type, name);
    }

    synchronized long createAuction(String seller, String sellerName, ItemStack item, double price, long expiry,double listingFee) {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO auctions(seller,seller_name,item,price,listed,expires,status,listing_fee) VALUES(?,?,?,?,?,?,'ACTIVE',?)", Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, seller, sellerName, item.serializeAsBytes(), price, System.currentTimeMillis(), expiry,listingFee);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : -1; }
        } catch (SQLException e) { throw fail(e); }
    }
    synchronized List<AuctionRow> activeAuctions() { expireAuctions(); return list("SELECT * FROM auctions WHERE status='ACTIVE' ORDER BY price ASC, listed ASC LIMIT 200", Database::mapAuction); }
    synchronized AuctionRow auction(long id) { expireAuctions(); return one("SELECT * FROM auctions WHERE id=?", Database::mapAuction, id); }
    synchronized int activeAuctionCount(String seller) { expireAuctions(); return integer("SELECT COUNT(*) FROM auctions WHERE seller=? AND status='ACTIVE'", seller); }
    synchronized List<AuctionRow> collectibleAuctions(String seller) { expireAuctions(); return list("SELECT * FROM auctions WHERE seller=? AND status='EXPIRED' ORDER BY expires", Database::mapAuction, seller); }
    /** Seller-scoped, unlike activeAuctions() (which caps at 200 rows server-wide, ordered by price) — a
     *  relic listing checked against that global list could be missed entirely if 200 unrelated cheaper
     *  listings exist. Used by RelicService to confirm "is this specific relic actively escrowed". */
    synchronized List<AuctionRow> activeAuctionsBySeller(String seller){expireAuctions();return list("SELECT * FROM auctions WHERE seller=? AND status='ACTIVE' ORDER BY listed",Database::mapAuction,seller);}
    synchronized boolean markAuctionSold(long id, String buyer) { return update("UPDATE auctions SET status='SOLD',buyer=?,sold_at=? WHERE id=? AND status='ACTIVE' AND expires>?", buyer,System.currentTimeMillis(),id,System.currentTimeMillis()) == 1; }
    synchronized boolean collectAuction(long id, String seller) { return update("UPDATE auctions SET status='COLLECTED' WHERE id=? AND seller=? AND status='EXPIRED'", id, seller) == 1; }
    synchronized boolean cancelAuction(long id, String seller) { return update("UPDATE auctions SET status='EXPIRED',expires=? WHERE id=? AND seller=? AND status='ACTIVE'", System.currentTimeMillis(), id, seller) == 1; }
    synchronized void expireAuctions() { update("UPDATE auctions SET status='EXPIRED' WHERE status='ACTIVE' AND expires<=?", System.currentTimeMillis()); }
    /** Relic lifecycle reclaim of an expired-but-never-collected listing: voids the escrow row (so /ah
     *  collect can never later also hand out the same physical item — the one thing that would recreate
     *  a duplicate) in the same call that marks the relic itself LOST. Only succeeds if the row is still
     *  genuinely EXPIRED (not already collected/sold/reclaimed by something else in the meantime). */
    synchronized boolean reclaimExpiredAuction(long id){return update("UPDATE auctions SET status='RECLAIMED' WHERE id=? AND status='EXPIRED'",id)==1;}

    synchronized BountyRow bounty(String target) { return one("SELECT * FROM bounties WHERE target=?", rs -> new BountyRow(rs.getString("target"),rs.getString("target_name"),rs.getDouble("amount")), target); }
    synchronized void addBounty(String target, String targetName, double amount) { update("INSERT INTO bounties(target,target_name,amount) VALUES(?,?,?) ON CONFLICT(target) DO UPDATE SET target_name=excluded.target_name,amount=bounties.amount+excluded.amount", target,targetName,amount); }
    synchronized void removeBounty(String target) { update("DELETE FROM bounties WHERE target=?", target); }
    synchronized List<BountyRow> bounties() { return list("SELECT * FROM bounties ORDER BY amount DESC LIMIT 50", rs -> new BountyRow(rs.getString("target"),rs.getString("target_name"),rs.getDouble("amount"))); }
    synchronized long lastBountyClaim(String killer, String target) { String v=scalar("SELECT CAST(claimed_at AS TEXT) FROM bounty_claims WHERE killer=? AND target=?",killer,target); return v==null?0:Long.parseLong(v); }
    synchronized void recordBountyClaim(String killer,String target,boolean ipBlocked) { update("INSERT INTO bounty_claims(killer,target,claimed_at,ip_blocked) VALUES(?,?,?,?) ON CONFLICT(killer,target) DO UPDATE SET claimed_at=excluded.claimed_at,ip_blocked=excluded.ip_blocked",killer,target,System.currentTimeMillis(),ipBlocked?1:0); }
    synchronized void addBountyContribution(String target,String contributor,String contributorName,String source,double amount){update("INSERT INTO bounty_contributions(target,contributor,contributor_name,source,amount,created_at) VALUES(?,?,?,?,?,?)",target,contributor,contributorName,source,amount,System.currentTimeMillis());}
    synchronized List<BountyContributionRow> bountyContributions(String target){return list("SELECT * FROM bounty_contributions WHERE target=? ORDER BY created_at",rs->new BountyContributionRow(rs.getLong("id"),rs.getString("target"),rs.getString("contributor"),rs.getString("contributor_name"),rs.getString("source"),rs.getDouble("amount"),rs.getLong("created_at")),target);}
    synchronized void clearBountyContributions(String target){update("DELETE FROM bounty_contributions WHERE target=?",target);}
    synchronized long createPendingClaim(String target,String targetName,String killer,String killerName,double amount,double tax){update("INSERT INTO bounty_claims_pending(target,target_name,killer,killer_name,amount,tax,created_at,status) VALUES(?,?,?,?,?,?,?, 'PENDING')",target,targetName,killer,killerName,amount,tax,System.currentTimeMillis());Long id=one("SELECT last_insert_rowid()",rs->rs.getLong(1));return id==null?0:id;}
    synchronized List<PendingBountyClaimRow> pendingClaims(String status){if(status==null||status.isBlank())return list("SELECT * FROM bounty_claims_pending ORDER BY created_at DESC LIMIT 50",Database::mapPendingClaim);return list("SELECT * FROM bounty_claims_pending WHERE status=? ORDER BY created_at DESC LIMIT 50",Database::mapPendingClaim,status);}
    synchronized PendingBountyClaimRow pendingClaim(long id){return one("SELECT * FROM bounty_claims_pending WHERE id=?",Database::mapPendingClaim,id);}
    synchronized void resolvePendingClaim(long id,String status,String resolvedBy){update("UPDATE bounty_claims_pending SET status=?,resolved_by=?,resolved_at=? WHERE id=?",status,resolvedBy,System.currentTimeMillis(),id);}
    private static PendingBountyClaimRow mapPendingClaim(ResultSet rs)throws SQLException{return new PendingBountyClaimRow(rs.getLong("id"),rs.getString("target"),rs.getString("target_name"),rs.getString("killer"),rs.getString("killer_name"),rs.getDouble("amount"),rs.getDouble("tax"),rs.getLong("created_at"),rs.getString("status"),rs.getString("resolved_by"),rs.getLong("resolved_at"),rs.getString("replay_url"),rs.getInt("replay_checked")!=0);}
    synchronized void setReplayResult(long id,String replayUrl){update("UPDATE bounty_claims_pending SET replay_url=?,replay_checked=1 WHERE id=?",replayUrl,id);}
    synchronized void logKill(String killer,String victim,boolean betrayal){update("INSERT INTO pvp_kill_log(killer,victim,betrayal,occurred_at) VALUES(?,?,?,?)",killer,victim,betrayal?1:0,System.currentTimeMillis());}
    synchronized int recentDistinctVictims(String killer,long sinceMillis){return integer("SELECT COUNT(DISTINCT victim) FROM pvp_kill_log WHERE killer=? AND occurred_at>=?",killer,sinceMillis);}
    synchronized int recentBetrayals(String killer,long sinceMillis){return integer("SELECT COUNT(*) FROM pvp_kill_log WHERE killer=? AND betrayal=1 AND occurred_at>=?",killer,sinceMillis);}

    synchronized RelicRow relic(String key) { return one("SELECT * FROM relics WHERE relic_key=?", Database::mapRelic, key); }
    synchronized void registerRelic(String key,String owner,String name) { long now=System.currentTimeMillis();update("INSERT INTO relics(relic_key,owner,owner_name,discovered_at,active,status,last_confirmed,eligible_at) VALUES(?,?,?,?,1,'ACTIVE',?,0) ON CONFLICT(relic_key) DO UPDATE SET owner=excluded.owner,owner_name=excluded.owner_name,discovered_at=excluded.discovered_at,active=1,status='ACTIVE',last_confirmed=excluded.last_confirmed,eligible_at=0",key,owner,name,now,now); }
    synchronized void deactivateRelic(String key) { update("UPDATE relics SET active=0,status='RETIRED' WHERE relic_key=?",key); }
    synchronized List<RelicRow> relics() { return list("SELECT * FROM relics ORDER BY discovered_at", Database::mapRelic); }
    synchronized RelicLifecycleRow relicLifecycle(String key){return one("SELECT * FROM relics WHERE relic_key=?",Database::mapRelicLifecycle,key);}
    synchronized void confirmRelic(String key,String owner,String ownerName){update("UPDATE relics SET owner=?,owner_name=?,active=1,status='ACTIVE',last_confirmed=?,eligible_at=0 WHERE relic_key=?",owner,ownerName,System.currentTimeMillis(),key);}
    synchronized void markRelicLost(String key,long eligibleAt){update("UPDATE relics SET active=0,status='LOST',last_confirmed=?,eligible_at=? WHERE relic_key=?",System.currentTimeMillis(),eligibleAt,key);}
    synchronized void makeRelicEligible(String key){update("UPDATE relics SET active=0,status='ELIGIBLE',eligible_at=0 WHERE relic_key=?",key);}
    synchronized List<RelicLifecycleRow> relicLifecycles(){return list("SELECT * FROM relics ORDER BY discovered_at",Database::mapRelicLifecycle);}
    synchronized String state(String key) { return scalar("SELECT value FROM state WHERE key=?",key); }
    synchronized void state(String key,String value) { update("INSERT INTO state(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",key,value); }

    synchronized List<PlayerRow> richestPlayers() { return list("SELECT * FROM players ORDER BY balance DESC LIMIT 10", rs -> new PlayerRow(rs.getString("id"),rs.getString("name"),rs.getDouble("balance"),rs.getInt("first_spawn")!=0,rs.getInt("guide")!=0,rs.getString("ip_hash"),rs.getInt("personal_slots"),rs.getInt("boss_kills"),rs.getInt("player_kills"),rs.getInt("event_wins"))); }
    /** Every player who's ever joined (players table is populated on first join, never pruned) — backs
     *  offline-player autocomplete for moderation commands, where the target is very often not online. */
    synchronized List<String> allPlayerNames(){return list("SELECT name FROM players ORDER BY name",rs->rs.getString("name"));}
    synchronized List<FactionRow> richestFactions() { return list("SELECT * FROM factions ORDER BY balance DESC LIMIT 10",Database::mapFaction); }
    synchronized List<PlayerRow> topStat(String column) { if(!Set.of("boss_kills","player_kills","event_wins").contains(column)) throw new IllegalArgumentException(); return list("SELECT * FROM players ORDER BY "+column+" DESC,name LIMIT 10",rs -> new PlayerRow(rs.getString("id"),rs.getString("name"),rs.getDouble("balance"),rs.getInt("first_spawn")!=0,rs.getInt("guide")!=0,rs.getString("ip_hash"),rs.getInt("personal_slots"),rs.getInt("boss_kills"),rs.getInt("player_kills"),rs.getInt("event_wins"))); }

    synchronized long addFeedback(String player,String name,String message){try(PreparedStatement ps=connection.prepareStatement("INSERT INTO feedback(player,player_name,created_at,message,status) VALUES(?,?,?,?,'OPEN')",Statement.RETURN_GENERATED_KEYS)){bind(ps,player,name,System.currentTimeMillis(),message);ps.executeUpdate();try(ResultSet rs=ps.getGeneratedKeys()){return rs.next()?rs.getLong(1):-1;}}catch(SQLException e){throw fail(e);}}
    synchronized FeedbackRow feedback(long id){return one("SELECT * FROM feedback WHERE id=?",Database::mapFeedback,id);}
    synchronized List<FeedbackRow> feedback(String status,int limit){return list("SELECT * FROM feedback WHERE (?='' OR status=?) ORDER BY created_at DESC LIMIT ?",Database::mapFeedback,status,status,limit);}
    synchronized boolean feedbackStatus(long id,String status){return update("UPDATE feedback SET status=? WHERE id=?",status,id)==1;}
    synchronized boolean deleteFeedback(long id){return update("DELETE FROM feedback WHERE id=?",id)==1;}

    /** Deliberately kept OUT of FactionRow (used far too widely to risk touching its shape for two narrow
     *  progression flags) — small standalone accessors instead. */
    synchronized boolean factionClaimedLandEver(long factionId){Boolean v=one("SELECT claimed_land FROM factions WHERE id=?",rs->rs.getInt(1)!=0,factionId);return v!=null&&v;}
    synchronized void markFactionClaimedLand(long factionId){update("UPDATE factions SET claimed_land=1 WHERE id=? AND claimed_land=0",factionId);}
    synchronized boolean factionSetHomeEver(long factionId){Boolean v=one("SELECT set_home FROM factions WHERE id=?",rs->rs.getInt(1)!=0,factionId);return v!=null&&v;}
    synchronized void markFactionSetHome(long factionId){update("UPDATE factions SET set_home=1 WHERE id=? AND set_home=0",factionId);}

    /** tier/warning_count/probation_until default to 0 for a player with no punishment history at all — a
     *  fresh row is only persisted once the FIRST warning is actually issued, not on lookup. */
    synchronized PunishmentRow punishment(String playerId){PunishmentRow row=one("SELECT * FROM punishments WHERE player=?",Database::mapPunishment,playerId);return row==null?new PunishmentRow(playerId,"",0,0,0,0):row;}
    synchronized void savePunishmentState(String playerId,String playerName,int tier,int warningCount,long probationUntil,long lastPunishedAt){update("INSERT INTO punishments(player,player_name,tier,warning_count,probation_until,last_punished_at) VALUES(?,?,?,?,?,?) ON CONFLICT(player) DO UPDATE SET player_name=excluded.player_name,tier=excluded.tier,warning_count=excluded.warning_count,probation_until=excluded.probation_until,last_punished_at=excluded.last_punished_at",playerId,playerName,tier,warningCount,probationUntil,lastPunishedAt);}
    synchronized long recordWarning(String playerId,String playerName,String staffId,String staffName,String reason,int triggeredTier){try(PreparedStatement ps=connection.prepareStatement("INSERT INTO warnings(player,player_name,staff,staff_name,reason,created_at,triggered_tier) VALUES(?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){bind(ps,playerId,playerName,staffId,staffName,reason,System.currentTimeMillis(),triggeredTier);ps.executeUpdate();try(ResultSet rs=ps.getGeneratedKeys()){return rs.next()?rs.getLong(1):-1;}}catch(SQLException e){throw fail(e);}}
    synchronized List<WarningRow> warnings(String playerId,int limit){return list("SELECT * FROM warnings WHERE player=? ORDER BY created_at DESC LIMIT ?",Database::mapWarning,playerId,limit);}
    private static PunishmentRow mapPunishment(ResultSet rs) throws SQLException{return new PunishmentRow(rs.getString("player"),rs.getString("player_name"),rs.getInt("tier"),rs.getInt("warning_count"),rs.getLong("probation_until"),rs.getLong("last_punished_at"));}
    private static WarningRow mapWarning(ResultSet rs) throws SQLException{return new WarningRow(rs.getLong("id"),rs.getString("player"),rs.getString("player_name"),rs.getString("staff"),rs.getString("staff_name"),rs.getString("reason"),rs.getLong("created_at"),rs.getInt("triggered_tier"));}
    record IpBanRow(String ip,String bannedBy,String reason,long bannedAt,long expiresAt,String sourcePlayer,String sourceEntry){boolean active(){return expiresAt==0||expiresAt>System.currentTimeMillis();}}
    synchronized void addIpBan(String ip,String bannedBy,String reason,long expiresAt,String sourcePlayer,String sourceEntry){update("INSERT INTO ip_bans(ip,banned_by,reason,banned_at,expires_at,source_player,source_entry) VALUES(?,?,?,?,?,?,?) ON CONFLICT(ip) DO UPDATE SET banned_by=excluded.banned_by,reason=excluded.reason,banned_at=excluded.banned_at,expires_at=excluded.expires_at,source_player=excluded.source_player,source_entry=excluded.source_entry",ip,bannedBy,reason,System.currentTimeMillis(),expiresAt,sourcePlayer,sourceEntry);}
    synchronized IpBanRow ipBan(String ip){return one("SELECT * FROM ip_bans WHERE ip=?",Database::mapIpBan,ip);}
    synchronized boolean removeIpBan(String ip){return update("DELETE FROM ip_bans WHERE ip=?",ip)>0;}
    synchronized boolean setIpBanExpiry(String ip,long expiresAt){return update("UPDATE ip_bans SET expires_at=? WHERE ip=?",expiresAt,ip)>0;}
    synchronized List<IpBanRow> ipBans(){return list("SELECT * FROM ip_bans ORDER BY banned_at DESC",Database::mapIpBan);}
    private static IpBanRow mapIpBan(ResultSet rs) throws SQLException{return new IpBanRow(rs.getString("ip"),rs.getString("banned_by"),rs.getString("reason"),rs.getLong("banned_at"),rs.getLong("expires_at"),rs.getString("source_player"),rs.getString("source_entry"));}
    record SavedLocationRow(long id,String name,String world,double x,double y,double z,String structureType,boolean manual,String registeredBy,long discoveredAt,String status,int boundaryRadius,long lastSnapshotAt,long lastRestoredAt,long lastRefillAt,Integer minX,Integer minY,Integer minZ,Integer maxX,Integer maxY,Integer maxZ){
        boolean hasRealBounds(){return minX!=null&&minY!=null&&minZ!=null&&maxX!=null&&maxY!=null&&maxZ!=null;}
    }
    synchronized long saveLocation(String name,String world,double x,double y,double z,String structureType,boolean manual,String registeredBy){
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO saved_locations(name,world,x,y,z,structure_type,manual,registered_by,discovered_at) VALUES(?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
            bind(ps,name,world,x,y,z,structureType,manual?1:0,registeredBy,System.currentTimeMillis());ps.executeUpdate();
            try(ResultSet rs=ps.getGeneratedKeys()){return rs.next()?rs.getLong(1):-1;}
        }catch(SQLException e){throw fail(e);}
    }
    synchronized List<SavedLocationRow> savedLocations(){return list("SELECT * FROM saved_locations ORDER BY manual DESC,name",Database::mapSavedLocation);}
    synchronized SavedLocationRow savedLocation(long id){return one("SELECT * FROM saved_locations WHERE id=?",Database::mapSavedLocation,id);}
    synchronized SavedLocationRow savedLocationByName(String name){return one("SELECT * FROM saved_locations WHERE name=?",Database::mapSavedLocation,name);}
    synchronized boolean deleteSavedLocation(long id){return update("DELETE FROM saved_locations WHERE id=?",id)>0;}
    synchronized boolean renameSavedLocation(long id,String newName){return update("UPDATE saved_locations SET name=? WHERE id=?",newName,id)>0;}
    synchronized void setSavedLocationStatus(long id,String status){update("UPDATE saved_locations SET status=? WHERE id=?",status,id);}
    synchronized void markSnapshot(long id){update("UPDATE saved_locations SET last_snapshot_at=? WHERE id=?",System.currentTimeMillis(),id);}
    synchronized void markRestored(long id){update("UPDATE saved_locations SET last_restored_at=? WHERE id=?",System.currentTimeMillis(),id);}
    synchronized void markRefilled(long id){update("UPDATE saved_locations SET last_refill_at=? WHERE id=?",System.currentTimeMillis(),id);}
    synchronized void setStructureBounds(long id,int minX,int minY,int minZ,int maxX,int maxY,int maxZ){update("UPDATE saved_locations SET min_x=?,min_y=?,min_z=?,max_x=?,max_y=?,max_z=? WHERE id=?",minX,minY,minZ,maxX,maxY,maxZ,id);}

    record RefillContainerRow(long id,long monumentId,String world,int x,int y,int z,String lootTable,long lastRefilledAt,String refilledBy){}
    synchronized void registerRefillContainer(long monumentId,String world,int x,int y,int z,String lootTable){update("INSERT INTO monument_refill_containers(monument_id,world,x,y,z,loot_table) VALUES(?,?,?,?,?,?) ON CONFLICT(world,x,y,z) DO UPDATE SET monument_id=excluded.monument_id,loot_table=excluded.loot_table",monumentId,world,x,y,z,lootTable);}
    synchronized List<RefillContainerRow> refillContainers(long monumentId){return list("SELECT * FROM monument_refill_containers WHERE monument_id=? ORDER BY id",Database::mapRefillContainer,monumentId);}
    synchronized void markContainerRefilled(long containerRowId,String admin){update("UPDATE monument_refill_containers SET last_refilled_at=?,refilled_by=? WHERE id=?",System.currentTimeMillis(),admin,containerRowId);}
    private static RefillContainerRow mapRefillContainer(ResultSet rs) throws SQLException{return new RefillContainerRow(rs.getLong("id"),rs.getLong("monument_id"),rs.getString("world"),rs.getInt("x"),rs.getInt("y"),rs.getInt("z"),rs.getString("loot_table"),rs.getLong("last_refilled_at"),rs.getString("refilled_by"));}
    private static SavedLocationRow mapSavedLocation(ResultSet rs) throws SQLException{return new SavedLocationRow(rs.getLong("id"),rs.getString("name"),rs.getString("world"),rs.getDouble("x"),rs.getDouble("y"),rs.getDouble("z"),rs.getString("structure_type"),rs.getInt("manual")!=0,rs.getString("registered_by"),rs.getLong("discovered_at"),rs.getString("status"),rs.getInt("boundary_radius"),rs.getLong("last_snapshot_at"),rs.getLong("last_restored_at"),rs.getLong("last_refill_at"),nullableInt(rs,"min_x"),nullableInt(rs,"min_y"),nullableInt(rs,"min_z"),nullableInt(rs,"max_x"),nullableInt(rs,"max_y"),nullableInt(rs,"max_z"));}
    private static Integer nullableInt(ResultSet rs,String column) throws SQLException{int value=rs.getInt(column);return rs.wasNull()?null:value;}
    synchronized boolean hasBlocked(String blocker,String blocked){return one("SELECT 1 FROM blocked_messages WHERE blocker=? AND blocked=?",rs->true,blocker,blocked)!=null;}
    synchronized boolean blockPlayer(String blocker,String blocked){if(hasBlocked(blocker,blocked))return false;update("INSERT INTO blocked_messages(blocker,blocked) VALUES(?,?)",blocker,blocked);return true;}
    synchronized boolean unblockPlayer(String blocker,String blocked){return update("DELETE FROM blocked_messages WHERE blocker=? AND blocked=?",blocker,blocked)>0;}
    synchronized void history(String scope,Long factionId,String kind,String message){update("INSERT INTO history(scope,faction_id,kind,message,created_at) VALUES(?,?,?,?,?)",scope,factionId,kind,message,System.currentTimeMillis());}
    synchronized void logChat(String kind,String sender,String senderName,String recipient,String recipientName,String message){update("INSERT INTO chat_log(kind,sender,sender_name,recipient,recipient_name,message,created_at) VALUES(?,?,?,?,?,?,?)",kind,sender,senderName,recipient,recipientName,message,System.currentTimeMillis());}
    synchronized List<ChatLogRow> chatBySender(String sender,int limit,int offset){return list("SELECT * FROM chat_log WHERE sender=? ORDER BY created_at DESC LIMIT ? OFFSET ?",Database::mapChatLog,sender,limit,offset);}
    synchronized List<ChatLogRow> chatBetween(String a,String b,int limit,int offset){return list("SELECT * FROM chat_log WHERE kind='DM' AND ((sender=? AND recipient=?) OR (sender=? AND recipient=?)) ORDER BY created_at DESC LIMIT ? OFFSET ?",Database::mapChatLog,a,b,b,a,limit,offset);}
    synchronized List<ChatLogRow> chatByKind(String kind,int limit,int offset){return list("SELECT * FROM chat_log WHERE kind=? ORDER BY created_at DESC LIMIT ? OFFSET ?",Database::mapChatLog,kind,limit,offset);}
    synchronized List<ChatLogRow> factionChatLog(long factionId,int limit,int offset){return list("SELECT * FROM chat_log WHERE kind='FACTION' AND recipient=? ORDER BY created_at DESC LIMIT ? OFFSET ?",Database::mapChatLog,Long.toString(factionId),limit,offset);}
    private static ChatLogRow mapChatLog(ResultSet rs)throws SQLException{return new ChatLogRow(rs.getLong("id"),rs.getString("kind"),rs.getString("sender"),rs.getString("sender_name"),column(rs,"recipient"),column(rs,"recipient_name"),rs.getString("message"),rs.getLong("created_at"));}
    synchronized void updateLastLocation(String id,Location location){if(location==null||location.getWorld()==null)return;update("UPDATE players SET last_world=?,last_x=?,last_y=?,last_z=? WHERE id=?",location.getWorld().getName(),location.getX(),location.getY(),location.getZ(),id);}
    synchronized Location lastLocation(String id){
        record Raw(String world,Double x,Double y,Double z){}
        Raw raw=one("SELECT last_world,last_x,last_y,last_z FROM players WHERE id=?",rs->new Raw(column(rs,"last_world"),(Double)rs.getObject("last_x"),(Double)rs.getObject("last_y"),(Double)rs.getObject("last_z")),id);
        if(raw==null||raw.world()==null||raw.x()==null)return null;
        World world=Bukkit.getWorld(raw.world());
        return world==null?null:new Location(world,raw.x(),raw.y(),raw.z());
    }
    synchronized void setPersistedSpawn(String id,Location location){if(location==null||location.getWorld()==null)return;update("UPDATE players SET persisted_spawn_world=?,persisted_spawn_x=?,persisted_spawn_y=?,persisted_spawn_z=? WHERE id=?",location.getWorld().getName(),location.getX(),location.getY(),location.getZ(),id);}
    synchronized Location persistedSpawn(String id){
        record Raw(String world,Double x,Double y,Double z){}
        Raw raw=one("SELECT persisted_spawn_world,persisted_spawn_x,persisted_spawn_y,persisted_spawn_z FROM players WHERE id=?",rs->new Raw(column(rs,"persisted_spawn_world"),(Double)rs.getObject("persisted_spawn_x"),(Double)rs.getObject("persisted_spawn_y"),(Double)rs.getObject("persisted_spawn_z")),id);
        if(raw==null||raw.world()==null||raw.x()==null)return null;
        World world=Bukkit.getWorld(raw.world());
        return world==null?null:new Location(world,raw.x(),raw.y(),raw.z());
    }
    synchronized void logAudit(String adminName,String action,String detail){update("INSERT INTO admin_audit(occurred_at,admin_name,action,detail) VALUES(?,?,?,?)",System.currentTimeMillis(),adminName,action,detail);}
    synchronized List<AuditRow> recentAudit(int limit){return list("SELECT * FROM admin_audit ORDER BY occurred_at DESC LIMIT ?",rs->new AuditRow(rs.getLong("id"),rs.getLong("occurred_at"),rs.getString("admin_name"),rs.getString("action"),rs.getString("detail")),limit);}
    synchronized List<AuditRow> monumentAudit(long monumentId,int limit){return list("SELECT * FROM admin_audit WHERE action LIKE 'MONUMENT_%' AND detail LIKE ? ORDER BY occurred_at DESC LIMIT ?",rs->new AuditRow(rs.getLong("id"),rs.getLong("occurred_at"),rs.getString("admin_name"),rs.getString("action"),rs.getString("detail")),"location=#"+monumentId+" %",limit);}

    record StaffNoteRow(long id,String playerUuid,String playerName,String note,String staffName,long createdAt){}
    synchronized long addStaffNote(String playerUuid,String playerName,String note,String staffName){
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO staff_notes(player_uuid,player_name,note,staff_name,created_at) VALUES(?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
            bind(ps,playerUuid,playerName,note,staffName,System.currentTimeMillis());ps.executeUpdate();
            try(ResultSet rs=ps.getGeneratedKeys()){return rs.next()?rs.getLong(1):-1;}
        }catch(SQLException e){throw fail(e);}
    }
    synchronized List<StaffNoteRow> staffNotes(String playerUuid){return list("SELECT * FROM staff_notes WHERE player_uuid=? ORDER BY created_at DESC",Database::mapStaffNote,playerUuid);}
    synchronized boolean deleteStaffNote(long id){return update("DELETE FROM staff_notes WHERE id=?",id)>0;}
    private static StaffNoteRow mapStaffNote(ResultSet rs) throws SQLException{return new StaffNoteRow(rs.getLong("id"),rs.getString("player_uuid"),rs.getString("player_name"),rs.getString("note"),rs.getString("staff_name"),rs.getLong("created_at"));}
    synchronized List<HistoryRow> history(Long factionId,int limit,int offset){if(factionId==null)return list("SELECT * FROM history WHERE scope='SERVER' ORDER BY created_at DESC LIMIT ? OFFSET ?",Database::mapHistory,limit,offset);return list("SELECT * FROM history WHERE scope='FACTION' AND faction_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",Database::mapHistory,factionId,limit,offset);}
    synchronized int dailySold(String player,String item,String day){return integer("SELECT quantity FROM daily_sales WHERE player=? AND item=? AND day=?",player,item,day);}
    synchronized String statsSnapshot(String player){return scalar("SELECT panel FROM stats_snapshot WHERE player=?",player);}
    synchronized void statsSnapshot(String player,String panel){
        update("INSERT INTO stats_snapshot(player,panel,updated_at) VALUES(?,?,?) ON CONFLICT(player) DO UPDATE SET panel=excluded.panel,updated_at=excluded.updated_at",
                player,panel,System.currentTimeMillis());
    }
    /** Current owned stock for a material. No row means zero -- never a default or generated amount. */
    synchronized int shopStock(String material){return integer("SELECT quantity FROM shop_stock WHERE material=?",material);}
    synchronized Map<String,Integer> shopStockAll(){
        Map<String,Integer> out=new HashMap<>();
        for(String[] row:list("SELECT material,quantity FROM shop_stock WHERE quantity>0",rs->new String[]{rs.getString(1),String.valueOf(rs.getInt(2))}))
            out.put(row[0],Integer.parseInt(row[1]));
        return out;
    }
    /** Adds stock the shop has just bought from a player. Upsert so the first sale of a material creates
     *  its row rather than needing every material pre-seeded. */
    synchronized void shopStockAdd(String material,int amount){
        if(material==null||amount<=0)return;
        update("INSERT INTO shop_stock(material,quantity) VALUES(?,?) ON CONFLICT(material) DO UPDATE SET quantity=quantity+excluded.quantity",material,amount);
    }
    /** Removes stock for a sale to a player, atomically. The quantity>=? predicate lives in the UPDATE
     *  itself, so the check and the decrement are one statement: two purchases racing for the last items
     *  cannot both succeed, and stock can never be driven negative. Returns false when there is not enough,
     *  in which case nothing was changed and the caller must abort the purchase. */
    synchronized boolean shopStockTake(String material,int amount){
        if(material==null||amount<=0)return false;
        return update("UPDATE shop_stock SET quantity=quantity-? WHERE material=? AND quantity>=?",amount,material,amount)>0;
    }
    synchronized void recordSale(String player,String item,String day,int quantity,double earned){update("INSERT INTO daily_sales(player,item,day,quantity,earned) VALUES(?,?,?,?,?) ON CONFLICT(player,item,day) DO UPDATE SET quantity=quantity+excluded.quantity,earned=earned+excluded.earned",player,item,day,quantity,earned);}
    record ArenaState(byte[] items,String world,double x,double y,double z,float yaw,float pitch,int level,float exp,double health,int food,String gamemode){}
    synchronized void arenaStateSave(String player,byte[] items,String world,double x,double y,double z,float yaw,float pitch,int level,float exp,double health,int food,String gamemode){
        update("INSERT OR REPLACE INTO arena_state(player,items,world,x,y,z,yaw,pitch,level,exp,health,food,gamemode) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",player,items,world,x,y,z,yaw,pitch,level,exp,health,food,gamemode);
    }
    synchronized ArenaState arenaState(String player){
        return one("SELECT items,world,x,y,z,yaw,pitch,level,exp,health,food,gamemode FROM arena_state WHERE player=?",
                rs->new ArenaState(rs.getBytes(1),rs.getString(2),rs.getDouble(3),rs.getDouble(4),rs.getDouble(5),rs.getFloat(6),rs.getFloat(7),rs.getInt(8),rs.getFloat(9),rs.getDouble(10),rs.getInt(11),rs.getString(12)),player);
    }
    synchronized void arenaStateClear(String player){update("DELETE FROM arena_state WHERE player=?",player);}
    synchronized List<String> arenaStateOwners(){return list("SELECT player FROM arena_state",rs->rs.getString(1));}
    synchronized void arenaEscrowSet(String player,double amount){update("INSERT OR REPLACE INTO arena_escrow(player,amount) VALUES(?,?)",player,amount);}
    synchronized double arenaEscrowOf(String player){Double v=one("SELECT amount FROM arena_escrow WHERE player=?",rs->rs.getDouble(1),player);return v==null?0:v;}
    synchronized void arenaEscrowClear(String player){update("DELETE FROM arena_escrow WHERE player=?",player);}
    synchronized void arenaWagerAdd(String player,String backed,double amount){update("INSERT INTO arena_wagers(player,backed,amount) VALUES(?,?,?)",player,backed,amount);}
    synchronized void arenaWagersClear(){update("DELETE FROM arena_wagers",new Object[0]);}

    record OrderRow(long id,String buyer,String buyerName,String itemKey,int amount,int filled,double unit,double escrow,long createdAt,long expiresAt,String status,int notified,int notifiedEnd,int hidden){}
    private static final String ORDER_COLUMNS="id,buyer,buyer_name,item_key,amount,filled,unit_price,escrow,created_at,expires_at,status,notified,notified_end,hidden";
    private static OrderRow orderRow(ResultSet rs)throws SQLException{
        return new OrderRow(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getInt(6),rs.getDouble(7),rs.getDouble(8),rs.getLong(9),rs.getLong(10),rs.getString(11),rs.getInt(12),rs.getInt(13),rs.getInt(14));
    }
    /** Records what has already been reported to the buyer, so a notice is delivered exactly once. */
    /** History visibility only. The row, its escrow trail and the economy ledger all stay exactly as they
     *  were -- this hides a finished order from the owner's list, it does not delete an audit record. */
    synchronized void orderHide(long id){update("UPDATE smp_orders SET hidden=1 WHERE id=? AND status<>'ACTIVE'",id);}
    synchronized void orderMarkNotified(long id,int filled){update("UPDATE smp_orders SET notified=?, notified_end=CASE WHEN status='ACTIVE' THEN 0 ELSE 1 END WHERE id=?",filled,id);}
    synchronized OrderRow order(long id){return one("SELECT "+ORDER_COLUMNS+" FROM smp_orders WHERE id=?",Database::orderRow,id);}
    synchronized List<OrderRow> ordersActive(String search){
        List<OrderRow> rows=list("SELECT "+ORDER_COLUMNS+" FROM smp_orders WHERE status='ACTIVE' ORDER BY created_at DESC",Database::orderRow);
        if(search==null||search.isBlank())return rows;
        String needle=search.toLowerCase(java.util.Locale.ROOT);
        return rows.stream().filter(row->row.itemKey().toLowerCase(java.util.Locale.ROOT).contains(needle)).toList();
    }
    synchronized List<OrderRow> ordersOf(String buyer){return list("SELECT "+ORDER_COLUMNS+" FROM smp_orders WHERE buyer=? AND hidden=0 ORDER BY status='ACTIVE' DESC, created_at DESC",Database::orderRow,buyer);}
    synchronized List<OrderRow> ordersExpired(long now){return list("SELECT "+ORDER_COLUMNS+" FROM smp_orders WHERE status='ACTIVE' AND expires_at<=?",Database::orderRow,now);}
    synchronized long orderCreate(String buyer,String name,String key,int amount,double unit,double escrow,long expires){
        update("INSERT INTO smp_orders(buyer,buyer_name,item_key,amount,filled,unit_price,escrow,created_at,expires_at,status) VALUES(?,?,?,?,0,?,?,?,?,'ACTIVE')",buyer,name,key,amount,unit,escrow,System.currentTimeMillis(),expires);
        Integer id=one("SELECT last_insert_rowid()",rs->rs.getInt(1));
        return id==null?0:id;
    }
    /** Check and take in ONE statement. Zero rows changed means somebody else got there first. */
    synchronized boolean orderReserve(long id,int qty,double cost){
        return update("UPDATE smp_orders SET filled=filled+?, escrow=escrow-? WHERE id=? AND status='ACTIVE' AND filled+?<=amount AND escrow>=?",qty,cost,id,qty,cost)>0;
    }
    /** Exact reverse, for a delivery that could not be completed after reserving. */
    synchronized void orderUnreserve(long id,int qty,double cost){
        update("UPDATE smp_orders SET filled=filled-?, escrow=escrow+? WHERE id=?",qty,cost,id);
    }
    /** Compare-and-swap on the escrow figure the caller read, so a refund can only ever happen once. */
    synchronized boolean orderClose(long id,String status,double expectedEscrow){
        return update("UPDATE smp_orders SET status=?, escrow=0 WHERE id=? AND status='ACTIVE' AND abs(escrow-?)<0.005",status,id,expectedEscrow)>0;
    }
    synchronized void orderCompleteIfFull(long id){update("UPDATE smp_orders SET status='COMPLETED' WHERE id=? AND filled>=amount",id);}
    synchronized void stashAdd(String owner,String key,int amount,ItemStack unit){
        if(unit==null||amount<=0)return;
        int max=Math.max(1,unit.getMaxStackSize()),remaining=amount;
        while(remaining>0){
            ItemStack stack=unit.clone();stack.setAmount(Math.min(max,remaining));remaining-=stack.getAmount();
            update("INSERT INTO smp_order_stash(owner,item,created_at) VALUES(?,?,?)",owner,ItemStack.serializeItemsAsBytes(new ItemStack[]{stack}),System.currentTimeMillis());
        }
    }
    synchronized int stashCount(String owner){return integer("SELECT COUNT(*) FROM smp_order_stash WHERE owner=?",owner);}
    synchronized List<ItemStack> stashOf(String owner){
        List<ItemStack> out=new ArrayList<>();
        for(byte[] raw:list("SELECT item FROM smp_order_stash WHERE owner=? ORDER BY id",rs->rs.getBytes(1),owner))
            try{for(ItemStack item:ItemStack.deserializeItemsFromBytes(raw))if(item!=null&&!item.getType().isAir())out.add(item);}catch(Throwable ignored){}
        return out;
    }
    /** Read and delete together, so a stash cannot be collected twice. */
    synchronized List<ItemStack> stashTake(String owner){
        List<ItemStack> out=stashOf(owner);
        update("DELETE FROM smp_order_stash WHERE owner=?",owner);
        return out;
    }

    synchronized int spawnerKills(String player,String type,String day){return integer("SELECT killed FROM spawner_kill_counts WHERE player=? AND mob_type=? AND day=?",player,type,day);}
    /** Adds to today's count and returns the total BEFORE the addition, which is what the payout split needs. */
    synchronized int addSpawnerKills(String player,String type,String day,int amount){
        int before=spawnerKills(player,type,day);
        update("INSERT INTO spawner_kill_counts(player,mob_type,day,killed) VALUES(?,?,?,?) ON CONFLICT(player,mob_type,day) DO UPDATE SET killed=killed+excluded.killed",player,type,day,amount);
        return before;
    }
    /** Housekeeping: reward days older than a fortnight are of no further use. */
    synchronized void pruneSpawnerKills(String keepFrom){update("DELETE FROM spawner_kill_counts WHERE day<?",keepFrom);}

    /** One statement for a whole batch of destroyed items rather than one per item. */
    synchronized void recordDiscarded(java.util.List<Object[]> rows){
        if(rows.isEmpty())return;
        StringBuilder sql=new StringBuilder("INSERT INTO discarded_ledger(occurred_at,material,amount,reason,recycled,world,x,y,z) VALUES");
        List<Object> args=new ArrayList<>();
        for(int i=0;i<rows.size();i++){sql.append(i==0?"":",").append("(?,?,?,?,?,?,?,?,?)");args.addAll(Arrays.asList(rows.get(i)));}
        update(sql.toString(),args.toArray());
    }
    /** Batched counterpart of shopStockAdd: a single upsert covering every material in the batch. */
    synchronized void shopStockAddAll(Map<String,Integer> amounts){
        if(amounts.isEmpty())return;
        StringBuilder sql=new StringBuilder("INSERT INTO shop_stock(material,quantity) VALUES");
        List<Object> args=new ArrayList<>();
        int i=0;
        for(Map.Entry<String,Integer> entry:amounts.entrySet()){
            if(entry.getValue()==null||entry.getValue()<=0)continue;
            sql.append(i++==0?"":",").append("(?,?)");
            args.add(entry.getKey());args.add(entry.getValue());
        }
        if(i==0)return;
        sql.append(" ON CONFLICT(material) DO UPDATE SET quantity=quantity+excluded.quantity");
        update(sql.toString(),args.toArray());
    }
    /** Per-material totals with the details of the most recent destruction. SQLite takes the bare columns
     *  from the same row that produced MAX(occurred_at), so "last reason/where" needs no second query. */
    synchronized List<String[]> discardedTotals(int limit){
        return list("SELECT material,SUM(amount),SUM(CASE WHEN recycled=1 THEN amount ELSE 0 END),reason,world,x,y,z,MAX(occurred_at) FROM discarded_ledger GROUP BY material ORDER BY SUM(amount) DESC LIMIT ?",
                rs->new String[]{rs.getString(1),String.valueOf(rs.getLong(2)),String.valueOf(rs.getLong(3)),rs.getString(4),rs.getString(5),String.valueOf(rs.getInt(6)),String.valueOf(rs.getInt(7)),String.valueOf(rs.getInt(8)),String.valueOf(rs.getLong(9))},limit);
    }
    synchronized void recordEconomy(String player,String category,double amount,String detail){if(!Double.isFinite(amount)||Math.abs(amount)<.0001)return;update("INSERT INTO economy_ledger(occurred_at,player,category,amount,detail) VALUES(?,?,?,?,?)",System.currentTimeMillis(),player,category,amount,detail);}
    synchronized List<EconomyTotal> economyTotals(long since){return list("SELECT category,SUM(amount) amount FROM economy_ledger WHERE occurred_at>=? GROUP BY category ORDER BY category",rs->new EconomyTotal(rs.getString("category"),rs.getDouble("amount")),since);}
    synchronized ProgressMetrics progressMetrics(String player){
        int wins=integer("SELECT event_wins FROM players WHERE id=?",player),participations=integer("SELECT event_participations FROM players WHERE id=?",player);
        double gameplay=scalarDouble("SELECT COALESCE(SUM(CASE WHEN amount<=0 THEN 0 WHEN category='SHOP_SELL' THEN amount*0.25 ELSE amount END),0) FROM economy_ledger WHERE player=? AND category IN ('MOB_NORMAL','ELITE','BOSS','EVENT','MILESTONE','SHOP_SELL') AND NOT (category='MILESTONE' AND UPPER(COALESCE(detail,'')) LIKE 'PROGRESS_RANK_%')",player);
        double contributions=scalarDouble("SELECT COALESCE(SUM(amount),0) FROM bank_ledger WHERE player=? AND amount>0 AND category IN ('FEE','SINK','SHOP_PURCHASE','MERCHANT_PURCHASE') AND UPPER(COALESCE(detail,'')) NOT LIKE 'DEATH%' AND UPPER(COALESCE(detail,'')) NOT LIKE 'FRIENDLY%' AND UPPER(COALESCE(detail,'')) NOT LIKE 'AUCTION%'",player);
        return new ProgressMetrics(wins,participations,gameplay,contributions);
    }
    synchronized String progressionItemOwner(String itemId){return scalar("SELECT player FROM progression_item_claims WHERE item_id=?",itemId);}
    synchronized boolean claimProgressionItem(String itemId,String player,boolean allowTransfer){
        if(itemId==null||itemId.isBlank()||player==null||player.isBlank())return false;
        String owner=progressionItemOwner(itemId);
        if(owner==null){update("INSERT INTO progression_item_claims(item_id,player,claimed_at) VALUES(?,?,?)",itemId,player,System.currentTimeMillis());return true;}
        if(owner.equals(player))return true;
        if(!allowTransfer)return false;
        return update("UPDATE progression_item_claims SET player=?,claimed_at=? WHERE item_id=?",player,System.currentTimeMillis(),itemId)==1;
    }
    synchronized void pruneEconomy(long before){update("DELETE FROM economy_ledger WHERE occurred_at<?",before);}
    synchronized BankRow bank(){return one("SELECT balance,fee_revenue,shop_profit,interest_revenue,sink_revenue,shop_payouts,updated_at FROM central_bank WHERE id=1",rs->new BankRow(rs.getDouble(1),rs.getDouble(2),rs.getDouble(3),rs.getDouble(4),rs.getDouble(5),rs.getDouble(6),rs.getLong(7)));}
    /** Debits the treasury directly (no player account involved) for bank-funded spend like auto-bounties.
     *  The WHERE-clause balance guard makes this atomically insolvency-safe: it simply fails if the
     *  treasury can't actually afford it, the same way issueLoan already protects loan issuance. */
    synchronized boolean debitBank(double amount,String detail){
        amount=roundMoney(amount);if(amount<=0)return false;
        if(update("UPDATE central_bank SET balance=balance-?,updated_at=? WHERE id=1 AND balance>=?",amount,System.currentTimeMillis(),amount)!=1)return false;
        update("INSERT INTO bank_ledger(occurred_at,player,category,amount,detail) VALUES(?,?,?,?,?)",System.currentTimeMillis(),null,"AUTO_BOUNTY",-amount,detail);
        return true;
    }
    synchronized LoanRow loan(String player){return one("SELECT * FROM bank_loans WHERE player=? AND status IN ('ACTIVE','OVERDUE') ORDER BY issued_at DESC LIMIT 1",Database::mapLoan,player);}
    synchronized int repaidLoanCount(String player){return integer("SELECT COUNT(*) FROM bank_loans WHERE player=? AND status='PAID'",player);}
    synchronized LoanRow accrueLoan(String player,double maxInterestPercent){
        LoanRow loan=loan(player);if(loan==null)return null;long now=System.currentTimeMillis();double elapsed=Math.max(0,now-loan.lastAccrual())/86400000.0;
        double cap=loan.originalAmount()*Math.max(0,maxInterestPercent)/100.0,interest=Math.min(cap,loan.interest()+loan.principal()*Math.max(0,loan.rateDaily())*elapsed);
        if(loan.principal()+interest<0.5){update("UPDATE bank_loans SET principal=0,interest=0,status='PAID',last_accrual=? WHERE id=?",now,loan.id());return null;}
        String status=now>loan.dueAt()?"OVERDUE":"ACTIVE";update("UPDATE bank_loans SET interest=?,last_accrual=?,status=? WHERE id=?",roundMoney(interest),now,status,loan.id());return loan(player);
    }
    synchronized void creditBankRevenue(double amount,String category,String player,String detail){
        amount=roundMoney(amount);if(amount<=0)return;double fees="FEE".equals(category)?amount:0,profit="SHOP_PROFIT".equals(category)?amount:0,sinks=Set.of("SINK","SHOP_PURCHASE","MERCHANT_PURCHASE").contains(category)?amount:0;
        update("UPDATE central_bank SET balance=balance+?,fee_revenue=fee_revenue+?,shop_profit=shop_profit+?,sink_revenue=sink_revenue+?,updated_at=? WHERE id=1",amount,fees,profit,sinks,System.currentTimeMillis());
        update("INSERT INTO bank_ledger(occurred_at,player,category,amount,detail) VALUES(?,?,?,?,?)",System.currentTimeMillis(),player,category,amount,detail);
    }
    synchronized boolean serverPayment(String player,double amount,String category,String detail){
        amount=roundMoney(amount);if(amount<=0)return false;boolean own=false;
        try{own=connection.getAutoCommit();if(own)connection.setAutoCommit(false);if(!changeBalance(player,-amount)){if(own)connection.rollback();return false;}
            creditBankRevenue(amount,category,player,detail);if(own)connection.commit();return true;
        }catch(Exception e){if(own)rollbackQuietly();if(e instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException(e);}finally{if(own)autoCommitQuietly();}
    }
    synchronized boolean refundServerPayment(String player,double amount,String category,String detail){
        amount=roundMoney(amount);if(amount<=0)return false;boolean own=false;
        try{own=connection.getAutoCommit();if(own)connection.setAutoCommit(false);long now=System.currentTimeMillis();double fees="FEE".equals(category)?amount:0,sinks=Set.of("SINK","SHOP_PURCHASE","MERCHANT_PURCHASE").contains(category)?amount:0;
            if(update("UPDATE central_bank SET balance=balance-?,fee_revenue=MAX(0,fee_revenue-?),sink_revenue=MAX(0,sink_revenue-?),updated_at=? WHERE id=1 AND balance>=?",amount,fees,sinks,now,amount)!=1){if(own)connection.rollback();return false;}
            if(!changeBalance(player,amount))throw new SQLException("refund recipient account missing");
            update("INSERT INTO bank_ledger(occurred_at,player,category,amount,detail) VALUES(?,?,?,?,?)",now,player,"REFUND",-amount,detail);
            if(own)connection.commit();return true;
        }catch(Exception e){if(own)rollbackQuietly();if(e instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException(e);}finally{if(own)autoCommitQuietly();}
    }
    synchronized boolean factionServerPayment(long faction,double amount,String player,String detail){
        amount=roundMoney(amount);if(amount<=0)return false;boolean own=false;
        try{own=connection.getAutoCommit();if(own)connection.setAutoCommit(false);if(!changeFactionBalance(faction,-amount)){if(own)connection.rollback();return false;}
            creditBankRevenue(amount,"SINK",player,detail);if(own)connection.commit();return true;
        }catch(Exception e){if(own)rollbackQuietly();if(e instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException(e);}finally{if(own)autoCommitQuietly();}
    }
    synchronized boolean payShopSeller(String player,double gross,double requestedGarnish,String detail){
        gross=roundMoney(gross);if(gross<=0)return false;LoanRow loan=loan(player);double payment=loan==null?0:roundMoney(Math.min(Math.max(0,requestedGarnish),Math.min(gross,loan.debt())));boolean own=false;
        try{own=connection.getAutoCommit();if(own)connection.setAutoCommit(false);long now=System.currentTimeMillis();
            if(update("UPDATE central_bank SET balance=balance-?,shop_payouts=shop_payouts+?,updated_at=? WHERE id=1 AND balance>=?",gross,gross,now,gross)!=1){if(own)connection.rollback();return false;}
            if(!changeBalance(player,gross-payment))throw new IllegalStateException("shop payout recipient account missing");
            update("INSERT INTO bank_ledger(occurred_at,player,category,amount,detail) VALUES(?,?,?,?,?)",now,player,"SHOP_PAYOUT",-gross,detail);
            if(payment>0)applyLoanPayment(loan,payment);if(own)connection.commit();return true;
        }catch(Exception e){if(own)rollbackQuietly();if(e instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException(e);}finally{if(own)autoCommitQuietly();}
    }
    /** Loans ISSUED since a timestamp, counting every loan regardless of whether it was since repaid --
     *  repaying must not hand back another issuance for the same day, which is the whole point of the
     *  limit. Reads issued_at, which bank_loans already records, so no schema change is needed. */
    synchronized int loansIssuedSince(String player,long since){
        return integer("SELECT COUNT(*) FROM bank_loans WHERE player=? AND issued_at>=?",player,since);
    }
    synchronized boolean issueLoan(String player,double amount,double rateDaily,long dueAt){
        amount=roundMoney(amount);if(amount<=0||loan(player)!=null)return false;boolean own=false;
        try{own=connection.getAutoCommit();if(own)connection.setAutoCommit(false);BankRow bank=bank();if(bank==null||bank.balance()+.0001<amount){if(own)connection.rollback();return false;}
            long now=System.currentTimeMillis();if(update("UPDATE central_bank SET balance=balance-?,updated_at=? WHERE id=1 AND balance>=?",amount,now,amount)!=1){if(own)connection.rollback();return false;}
            update("INSERT INTO bank_loans(player,principal,original_amount,interest,rate_daily,issued_at,due_at,last_accrual,status) VALUES(?,?,?,0,?,?,?,?, 'ACTIVE')",player,amount,amount,Math.max(0,rateDaily),now,dueAt,now);
            if(!changeBalance(player,amount))throw new SQLException("loan recipient account missing");
            update("INSERT INTO bank_ledger(occurred_at,player,category,amount,detail) VALUES(?,?,?,?,?)",now,player,"LOAN_ISSUED",-amount,"Central Bank loan");
            if(own)connection.commit();return true;
        }catch(Exception e){if(own)rollbackQuietly();if(e instanceof SQLException sql)throw fail(sql);if(e instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException(e);}finally{if(own)autoCommitQuietly();}
    }
    synchronized double repayLoan(String player,double requested){
        requested=roundMoney(requested);LoanRow loan=loan(player);PlayerRow account=player(player);if(loan==null||account==null||requested<=0)return 0;double payment=roundMoney(Math.min(requested,Math.min(account.balance(),loan.debt())));if(payment<=0)return 0;boolean own=false;
        try{own=connection.getAutoCommit();if(own)connection.setAutoCommit(false);if(!changeBalance(player,-payment)){if(own)connection.rollback();return 0;}applyLoanPayment(loan,payment);
            if(own)connection.commit();return payment;
        }catch(Exception e){if(own)rollbackQuietly();if(e instanceof RuntimeException runtime)throw runtime;if(e instanceof SQLException sql)throw fail(sql);throw new IllegalStateException(e);}finally{if(own)autoCommitQuietly();}
    }
    synchronized double creditEarnedWithGarnishment(String player,double gross,double requestedGarnish){
        gross=roundMoney(gross);LoanRow loan=loan(player);if(gross<=0)return 0;double payment=loan==null?0:roundMoney(Math.min(Math.max(0,requestedGarnish),Math.min(gross,loan.debt())));boolean own=false;
        try{own=connection.getAutoCommit();if(own)connection.setAutoCommit(false);if(!changeBalance(player,gross-payment))throw new IllegalStateException("income recipient account missing");if(payment>0)applyLoanPayment(loan,payment);if(own)connection.commit();return payment;
        }catch(Exception e){if(own)rollbackQuietly();if(e instanceof RuntimeException runtime)throw runtime;if(e instanceof SQLException sql)throw fail(sql);throw new IllegalStateException(e);}finally{if(own)autoCommitQuietly();}
    }
    private void applyLoanPayment(LoanRow loan,double payment){
        double interestPaid=Math.min(payment,loan.interest()),remaining=payment-interestPaid,principal=Math.max(0,loan.principal()-remaining),interest=Math.max(0,loan.interest()-interestPaid);String status=principal+interest<0.5?"PAID":(System.currentTimeMillis()>loan.dueAt()?"OVERDUE":"ACTIVE");
        update("UPDATE bank_loans SET principal=?,interest=?,status=?,last_accrual=? WHERE id=?",roundMoney(principal),roundMoney(interest),status,System.currentTimeMillis(),loan.id());
        update("UPDATE central_bank SET balance=balance+?,interest_revenue=interest_revenue+?,updated_at=? WHERE id=1",payment,interestPaid,System.currentTimeMillis());
        update("INSERT INTO bank_ledger(occurred_at,player,category,amount,detail) VALUES(?,?,?,?,?)",System.currentTimeMillis(),loan.player(),status.equals("PAID")?"LOAN_REPAID":"LOAN_PAYMENT",payment,"Interest "+roundMoney(interestPaid));
    }
    synchronized void recordEliteSpawn(String tier,boolean natural){update("INSERT INTO elite_spawn_counts(tier,natural_count,custom_count,total_count,last_spawn) VALUES(?,?,?,?,?) ON CONFLICT(tier) DO UPDATE SET natural_count=natural_count+excluded.natural_count,custom_count=custom_count+excluded.custom_count,total_count=total_count+1,last_spawn=excluded.last_spawn",tier,natural?1:0,natural?0:1,1,System.currentTimeMillis());}
    synchronized List<EliteSpawnRow> eliteSpawnCounts(){return list("SELECT tier,natural_count,custom_count,total_count,last_spawn FROM elite_spawn_counts ORDER BY CASE tier WHEN 'uncommon' THEN 1 WHEN 'rare' THEN 2 WHEN 'epic' THEN 3 WHEN 'legendary' THEN 4 WHEN 'miniboss' THEN 5 ELSE 6 END",rs->new EliteSpawnRow(rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5)));}
    synchronized void saveBossState(BossStateRow b){update("INSERT INTO boss_state(entity_id,world,x,y,z,spawned_at,hint_stage,next_hint_at,reward_state,origin,base_health,active_count,kind) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(entity_id) DO UPDATE SET world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,hint_stage=excluded.hint_stage,next_hint_at=excluded.next_hint_at,reward_state=excluded.reward_state,origin=excluded.origin,base_health=excluded.base_health,active_count=excluded.active_count,kind=excluded.kind",b.entityId(),b.world(),b.x(),b.y(),b.z(),b.spawnedAt(),b.hintStage(),b.nextHintAt(),b.rewardState(),b.origin(),b.baseHealth(),b.activeCount(),b.kind());}
    synchronized List<BossStateRow> bossStates(){return list("SELECT * FROM boss_state WHERE reward_state='ACTIVE'",Database::mapBossState);}
    synchronized void saveBossDamage(String entity,String player,double damage,long lastHit){update("INSERT INTO boss_damage(entity_id,player,damage,last_hit) VALUES(?,?,?,?) ON CONFLICT(entity_id,player) DO UPDATE SET damage=excluded.damage,last_hit=excluded.last_hit",entity,player,damage,lastHit);}
    synchronized void saveBossDamage(String entity,String player,double damage){saveBossDamage(entity,player,damage,System.currentTimeMillis());}
    synchronized Map<String,Double> bossDamage(String entity){Map<String,Double> out=new HashMap<>();for(var row:list("SELECT player,damage FROM boss_damage WHERE entity_id=?",rs->Map.entry(rs.getString(1),rs.getDouble(2)),entity))out.put(row.getKey(),row.getValue());return out;}
    synchronized Map<String,BossContribution> bossContributions(String entity){Map<String,BossContribution> out=new HashMap<>();for(var row:list("SELECT player,damage,last_hit FROM boss_damage WHERE entity_id=?",rs->Map.entry(rs.getString(1),new BossContribution(rs.getDouble(2),rs.getLong(3))),entity))out.put(row.getKey(),row.getValue());return out;}
    synchronized boolean claimBossReward(String entity){return update("UPDATE boss_state SET reward_state='CLAIMED' WHERE entity_id=? AND reward_state='ACTIVE'",entity)==1;}
    synchronized void deleteBossState(String entity){update("DELETE FROM boss_state WHERE entity_id=?",entity);}

    synchronized void saveMerchant(MerchantRow row){update("INSERT INTO merchants(id,type,world,x,y,z,yaw,pitch,entity_id) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET type=excluded.type,world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,entity_id=excluded.entity_id",row.id(),row.type(),row.world(),row.x(),row.y(),row.z(),row.yaw(),row.pitch(),row.entityId());}
    synchronized List<MerchantRow> merchants(){return list("SELECT * FROM merchants ORDER BY id",Database::mapMerchant);}
    synchronized MerchantRow merchant(String id){return one("SELECT * FROM merchants WHERE id=?",Database::mapMerchant,id);}
    synchronized void deleteMerchant(String id){update("DELETE FROM merchants WHERE id=?",id);}

    synchronized long createGrave(String owner,String ownerUuid,String ownerName,String publicName,String skinValue,String skinSignature,Location location,long expiresAt,List<ItemStack> items){
        boolean own=false;
        try{own=connection.getAutoCommit();if(own)connection.setAutoCommit(false);long now=System.currentTimeMillis(),id;
            try(PreparedStatement ps=connection.prepareStatement("INSERT INTO graves(owner,owner_uuid,owner_name,public_name,skin_value,skin_signature,world,x,y,z,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                bind(ps,owner,ownerUuid,ownerName,publicName,skinValue,skinSignature,location.getWorld().getName(),location.getX(),location.getY(),location.getZ(),now,expiresAt);ps.executeUpdate();try(ResultSet keys=ps.getGeneratedKeys()){if(!keys.next())throw new SQLException("grave id missing");id=keys.getLong(1);}
            }
            int slot=0;for(ItemStack item:items)if(item!=null&&!item.getType().isAir())update("INSERT INTO grave_items(grave_id,slot,item) VALUES(?,?,?)",id,slot++,item.serializeAsBytes());
            if(slot==0)throw new SQLException("empty grave");if(own)connection.commit();return id;
        }catch(Exception e){if(own)rollbackQuietly();if(e instanceof RuntimeException runtime)throw runtime;if(e instanceof SQLException sql)throw fail(sql);throw new IllegalStateException(e);}finally{if(own)autoCommitQuietly();}
    }
    synchronized GraveRow grave(long id){return one("SELECT * FROM graves WHERE id=?",Database::mapGrave,id);}
    synchronized List<GraveRow> graves(UUID ownerUuid){return list("SELECT * FROM graves WHERE owner_uuid=? AND expires_at>? ORDER BY created_at DESC",Database::mapGrave,ownerUuid.toString(),System.currentTimeMillis());}
    synchronized List<GraveRow> graves(String owner){return list("SELECT * FROM graves WHERE owner=? AND expires_at>? ORDER BY created_at DESC",Database::mapGrave,owner,System.currentTimeMillis());}
    synchronized List<GraveRow> allGraves(){return list("SELECT * FROM graves ORDER BY created_at",Database::mapGrave);}
    synchronized List<GraveRow> expiredGraves(){return list("SELECT * FROM graves WHERE expires_at<=? ORDER BY expires_at",Database::mapGrave,System.currentTimeMillis());}
    synchronized List<ItemStack> graveItems(long id){return list("SELECT item FROM grave_items WHERE grave_id=? ORDER BY slot",rs->ItemStack.deserializeBytes(rs.getBytes(1)),id);}
    synchronized void saveGraveItems(long id,List<ItemStack> items){
        boolean own=false;try{own=connection.getAutoCommit();if(own)connection.setAutoCommit(false);update("DELETE FROM grave_items WHERE grave_id=?",id);int slot=0;for(ItemStack item:items)if(item!=null&&!item.getType().isAir())update("INSERT INTO grave_items(grave_id,slot,item) VALUES(?,?,?)",id,slot++,item.serializeAsBytes());if(slot==0)update("DELETE FROM graves WHERE id=?",id);if(own)connection.commit();}catch(Exception e){if(own)rollbackQuietly();if(e instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException(e);}finally{if(own)autoCommitQuietly();}
    }
    synchronized void updateGraveMarker(long id,String marker){update("UPDATE graves SET marker_uuid=? WHERE id=?",marker,id);}
    synchronized void updateGraveLocation(long id,Location location){update("UPDATE graves SET world=?,x=?,y=?,z=? WHERE id=?",location.getWorld().getName(),location.getX(),location.getY(),location.getZ(),id);}
    synchronized void deleteGrave(long id){update("DELETE FROM graves WHERE id=?",id);}

    synchronized List<String> recordSpawnerPlacement(String spawnerId,long factionId,String mobType,long placedAt,Location location){
        if(spawnerId==null||spawnerId.isBlank()||location==null||location.getWorld()==null)return List.of();
        boolean own=false;List<String> credited=new ArrayList<>();
        try{own=connection.getAutoCommit();if(own)connection.setAutoCommit(false);
            int inserted=update("INSERT OR IGNORE INTO spawner_placements(spawner_id,faction_id,mob_type,placed_at,world,x,y,z) VALUES(?,?,?,?,?,?,?,?)",spawnerId,factionId,mobType,placedAt,location.getWorld().getName(),location.getBlockX(),location.getBlockY(),location.getBlockZ());
            if(inserted==0){if(own)connection.rollback();return List.of();}
            for(FactionMemberRow member:factionMemberRows(factionId))if(member.joinedAt()<=placedAt&&update("INSERT OR IGNORE INTO player_spawner_progress(player,faction_id,spawner_id,placement_time,join_time) VALUES(?,?,?,?,?)",member.player(),factionId,spawnerId,placedAt,member.joinedAt())==1)credited.add(member.player());
            if(own)connection.commit();return List.copyOf(credited);
        }catch(Exception e){if(own)rollbackQuietly();if(e instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException(e);}finally{if(own)autoCommitQuietly();}
    }
    synchronized boolean hasSpawnerProgress(String player){return integer("SELECT COUNT(*) FROM player_spawner_progress WHERE player=?",player)>0;}

    synchronized void saveAsset(AssetRow row){update("INSERT INTO faction_assets(faction_id,asset_key,asset_type,material,value,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(faction_id,asset_key) DO UPDATE SET asset_type=excluded.asset_type,material=excluded.material,value=excluded.value,updated_at=excluded.updated_at",row.factionId(),row.key(),row.type(),row.material(),row.value(),row.updatedAt());}
    synchronized void deleteAsset(String key){update("DELETE FROM faction_assets WHERE asset_key=?",key);}
    synchronized void deleteAssetsNotSeen(long faction,long olderThan){update("DELETE FROM faction_assets WHERE faction_id=? AND updated_at<?",faction,olderThan);}
    synchronized double assetTotal(long faction){String value=scalar("SELECT CAST(COALESCE(SUM(value),0) AS TEXT) FROM faction_assets WHERE faction_id=?",faction);try{return value==null?0:Double.parseDouble(value);}catch(NumberFormatException e){return 0;}}
    synchronized double assetTotalExcept(long faction,String type){return scalarDouble("SELECT COALESCE(SUM(value),0) FROM faction_assets WHERE faction_id=? AND asset_type<>?",faction,type);}
    synchronized double assetTotalOf(long faction,String type){return scalarDouble("SELECT COALESCE(SUM(value),0) FROM faction_assets WHERE faction_id=? AND asset_type=?",faction,type);}
    synchronized List<AssetRow> assets(long faction){return list("SELECT * FROM faction_assets WHERE faction_id=?",Database::mapAsset,faction);}
    synchronized MarketValueRow marketValue(String material){return one("SELECT * FROM market_values WHERE material=?",Database::mapMarketValue,material);}
    synchronized void saveMarketValue(MarketValueRow row){update("INSERT INTO market_values(material,value,samples,updated_at) VALUES(?,?,?,?) ON CONFLICT(material) DO UPDATE SET value=excluded.value,samples=excluded.samples,updated_at=excluded.updated_at",row.material(),row.value(),row.samples(),row.updatedAt());}
    synchronized List<AuctionSaleRow> auctionSales(long since){return list("SELECT id,seller,buyer,item,price,sold_at FROM auctions WHERE status='SOLD' AND buyer IS NOT NULL AND sold_at>=? ORDER BY sold_at DESC LIMIT 2000",rs->new AuctionSaleRow(rs.getLong(1),rs.getString(2),rs.getString(3),ItemStack.deserializeBytes(rs.getBytes(4)),rs.getDouble(5),rs.getLong(6)),since);}

    synchronized List<String> selfTest() {
        List<String> checks=new ArrayList<>();
        try {
            String quick=scalar("PRAGMA quick_check");if(!"ok".equalsIgnoreCase(quick))throw new SQLException("quick_check="+quick);checks.add("SQLite quick_check: ok");
            connection.setAutoCommit(false);
            update("INSERT INTO players(id,name,balance) VALUES('__selftest_a','SelfTestA',1000)");
            if(!changeBalance("__selftest_a",-125)||Math.abs(player("__selftest_a").balance()-875)>0.001)throw new SQLException("economy round trip");
            if(changeBalance("__selftest_a",-1000))throw new SQLException("negative-balance guard");checks.add("Economy atomic withdraw/negative guard: ok");
            putHome("__selftest_a","PERSONAL","home",plugin.getServer().getWorlds().getFirst().getSpawnLocation());if(home("__selftest_a","PERSONAL","home")==null||!renameHome("__selftest_a","PERSONAL","home","base")||home("__selftest_a","PERSONAL","base")==null)throw new SQLException("home round trip/rename");checks.add("Home persistence and rename round trip: ok");
            setEnderPages("__selftest_a",4);ItemStack[] ender=new ItemStack[27];ender[3]=new ItemStack(org.bukkit.Material.ENDER_PEARL,7);saveEnderPage("__selftest_a",2,ender);if(enderPages("__selftest_a")!=4||enderPage("__selftest_a",2)[3]==null||enderPage("__selftest_a",2)[3].getAmount()!=7)throw new SQLException("ender storage round trip");checks.add("Expanded Ender storage round trip: ok");
            long auction=createAuction("__selftest_a","SelfTestA",new ItemStack(org.bukkit.Material.COBBLESTONE,32),500,System.currentTimeMillis()+60000,15);AuctionRow ar=auction(auction);if(ar==null||ar.item().getAmount()!=32||ar.price()!=500||ar.listingFee()!=15)throw new SQLException("auction item round trip");checks.add("Auction ItemStack BLOB round trip: ok");
            addBounty("__selftest_b","SelfTestB",250);if(bounty("__selftest_b")==null||bounty("__selftest_b").amount()!=250)throw new SQLException("bounty round trip");checks.add("Bounty persistence round trip: ok");
            registerRelic("__selftest_relic","__selftest_a","SelfTestA");if(!relic("__selftest_relic").active())throw new SQLException("relic round trip");checks.add("Unique relic registry round trip: ok");
            preference("__selftest_a","sidebar","false");if(!"false".equals(preference("__selftest_a","sidebar")))throw new SQLException("preference round trip");checks.add("Persistent preference round trip: ok");
            if(!markMilestone("__selftest_a","test_milestone")||markMilestone("__selftest_a","test_milestone"))throw new SQLException("milestone uniqueness");checks.add("Milestone uniqueness guard: ok");
            long feedback=addFeedback("__selftest_a","SelfTestA","Test suggestion");if(feedback(feedback)==null)throw new SQLException("feedback round trip");checks.add("Feedback persistence round trip: ok");
            history("SERVER",null,"TEST","Self-test chronicle");if(history(null,1,0).isEmpty())throw new SQLException("history round trip");checks.add("History persistence round trip: ok");
            /** Finite shop stock. Exercises the properties the economy now depends on: absent means zero
             *  (no invented supply), a take of more than is held changes nothing, concurrent takes cannot
             *  oversell, and stock survives as real persisted rows. */
            String stockItem="__SELFTEST_STOCK";
            update("DELETE FROM shop_stock WHERE material=?",stockItem);
            if(shopStock(stockItem)!=0)throw new SQLException("stock of an unknown item must be zero, not generated");
            if(shopStockTake(stockItem,1))throw new SQLException("took stock that was never sold to the shop");
            shopStockAdd(stockItem,64);
            if(shopStock(stockItem)!=64)throw new SQLException("stock did not increase on sale");
            if(shopStockTake(stockItem,65))throw new SQLException("oversold: took more than the shop owned");
            if(shopStock(stockItem)!=64)throw new SQLException("failed take must leave stock untouched");
            if(!shopStockTake(stockItem,64)||shopStock(stockItem)!=0)throw new SQLException("exact-stock purchase failed");
            if(shopStockTake(stockItem,1))throw new SQLException("stock went negative");
            /** Two buyers racing for the last item: the conditional UPDATE means exactly one may win. */
            shopStockAdd(stockItem,1);
            boolean first=shopStockTake(stockItem,1),second=shopStockTake(stockItem,1);
            if(!first||second||shopStock(stockItem)!=0)throw new SQLException("simultaneous purchase duplicated the last item");
            /** A refunded purchase (payment failed after reserving) must restore exactly what it took. */
            shopStockAdd(stockItem,10);shopStockTake(stockItem,4);shopStockAdd(stockItem,4);
            if(shopStock(stockItem)!=10)throw new SQLException("rollback of a failed purchase lost stock");
            update("DELETE FROM shop_stock WHERE material=?",stockItem);
            checks.add("Finite shop stock: zero-start, no oversell, race-safe, rollback-safe: ok");
            recordSale("__selftest_a","IRON_INGOT","2099-01-01",64,100);if(dailySold("__selftest_a","IRON_INGOT","2099-01-01")!=64)throw new SQLException("daily sales round trip");checks.add("Daily full-value threshold persistence: ok");
            BossStateRow boss=new BossStateRow("00000000-0000-0000-0000-000000000001",plugin.getServer().getWorlds().getFirst().getName(),0,64,0,System.currentTimeMillis(),1,System.currentTimeMillis()+60000,"ACTIVE","ADMIN_SUMMONED",5000,2,"ASHEN_KNIGHT");saveBossState(boss);saveBossDamage(boss.entityId(),"__selftest_a",123.5,System.currentTimeMillis());if(bossStates().stream().noneMatch(row->row.entityId().equals(boss.entityId())&&row.origin().equals("ADMIN_SUMMONED"))||Math.abs(bossDamage(boss.entityId()).getOrDefault("__selftest_a",0.0)-123.5)>.001)throw new SQLException("boss persistence round trip");checks.add("Boss state/participation/scaling round trip: ok");
            update("INSERT INTO factions(name,tag,leader,balance,tier,home_slots,world,core_x,core_z) VALUES('__SelfTestFaction','TST','__selftest_a',50000,2,1,?,0,0)",plugin.getServer().getWorlds().getFirst().getName());long factionId=Long.parseLong(scalar("SELECT CAST(id AS TEXT) FROM factions WHERE name='__SelfTestFaction'"));if(!"TST".equals(faction(factionId).tag()))throw new SQLException("tag calculation");saveAsset(new AssetRow(factionId,"selftest","CONTAINER","DIAMOND",500,System.currentTimeMillis()));if(assetTotal(factionId)!=500)throw new SQLException("asset round trip");checks.add("Faction tag and net-worth cache: ok");
            update("INSERT INTO faction_members(player,player_name,faction_id,role,joined_at) VALUES('__selftest_a','SelfTestA',?,'LEADER',?)",factionId,System.currentTimeMillis());update("INSERT INTO faction_members(player,player_name,faction_id,role,joined_at) VALUES('__selftest_co','SelfTestCo',?,'MEMBER',?)",factionId,System.currentTimeMillis());setCoLeader(factionId,"__selftest_co");if(!"__selftest_co".equals(coLeader(factionId))||!"CO_LEADER".equals(roleOf("__selftest_co")))throw new SQLException("co-leader uniqueness");checks.add("Single Co-Leader persistence round trip: ok");
            if(!claimProgressionItem("__selftest_item","__selftest_a",true)||claimProgressionItem("__selftest_item","__selftest_co",false)||!claimProgressionItem("__selftest_item","__selftest_co",true))throw new SQLException("equipment progression provenance");checks.add("Equipment progression provenance/legitimate-transfer guard: ok");
            update("INSERT INTO factions(name,tag,leader,balance,tier,home_slots,world,core_x,core_z) VALUES('__SelfTestAlly','STA','__selftest_b',0,-1,1,?,1000,1000)",plugin.getServer().getWorlds().getFirst().getName());long allyId=Long.parseLong(scalar("SELECT CAST(id AS TEXT) FROM factions WHERE name='__SelfTestAlly'"));requestRelation(factionId,allyId,"ALLIANCE",factionId);acceptRelation(factionId,allyId,"ALLIANCE");storageApproval(factionId,allyId,factionId,true);storageApproval(factionId,allyId,allyId,true);if(relation(factionId,allyId)==null||!relation(factionId,allyId).sharedStorage())throw new SQLException("faction relation round trip");checks.add("Faction alliance/shared-storage round trip: ok");
            addShards("__selftest_a",20,"SELFTEST",null);if(shardBalance("__selftest_a")!=20||!spendShards("__selftest_a",5,"SELFTEST")||shardBalance("__selftest_a")!=15)throw new SQLException("shard account round trip");checks.add("Shard account/ledger atomic spend round trip: ok");
            double telemetryBefore=economyTotals(0).stream().filter(t->t.category().equals("MOB_NORMAL")).mapToDouble(EconomyTotal::amount).sum();recordEconomy("__selftest_a","MOB_NORMAL",12.5,"selftest");double telemetryAfter=economyTotals(0).stream().filter(t->t.category().equals("MOB_NORMAL")).mapToDouble(EconomyTotal::amount).sum();if(Math.abs((telemetryAfter-telemetryBefore)-12.5)>.001)throw new SQLException("economy telemetry round trip");checks.add("Economy telemetry round trip: ok");
            double bankBefore=bank().balance();creditBankRevenue(1000,"FEE","__selftest_a","selftest");if(Math.abs(bank().balance()-bankBefore-1000)>.001||!issueLoan("__selftest_a",400,.01,System.currentTimeMillis()+86400000L))throw new SQLException("central bank issue");
            LoanRow loan=loan("__selftest_a");if(loan==null||Math.abs(loan.principal()-400)>.001||Math.abs(repayLoan("__selftest_a",100)-100)>.001||!serverPayment("__selftest_a",50,"SINK","selftest")||!payShopSeller("__selftest_a",25,0,"selftest"))throw new SQLException("central bank loan/accounting round trip");checks.add("Central Bank treasury/loan/server-payment/shop-payout rollback: ok");
            List<ItemStack> graveItems=List.of(new ItemStack(org.bukkit.Material.DIAMOND,3),new ItemStack(org.bukkit.Material.IRON_PICKAXE));long grave=createGrave("__selftest_a",UUID.nameUUIDFromBytes("OfflinePlayer:SelfTestA".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),"SelfTestA","SelfTestA",null,null,plugin.getServer().getWorlds().getFirst().getSpawnLocation(),System.currentTimeMillis()+60000,graveItems);if(grave(grave)==null||graveItems(grave).size()!=2)throw new SQLException("grave persistence");saveGraveItems(grave,List.of(new ItemStack(org.bukkit.Material.DIAMOND)));if(graveItems(grave).size()!=1)throw new SQLException("grave partial persistence");saveGraveItems(grave,List.of());if(grave(grave)!=null)throw new SQLException("empty grave cleanup");checks.add("Independent grave/partial-loot/empty cleanup round trip: ok");
            recordEliteSpawn("epic",true);if(eliteSpawnCounts().stream().noneMatch(row->row.tier().equals("epic")&&row.total()>0))throw new SQLException("elite telemetry");checks.add("Elite rarity telemetry round trip: ok");
            saveMerchant(new MerchantRow("__selftest_merchant","SHOP",plugin.getServer().getWorlds().getFirst().getName(),0,64,0,0,0,null));if(merchant("__selftest_merchant")==null)throw new SQLException("merchant round trip");checks.add("Persistent spawn merchant round trip: ok");
            markRelicLost("__selftest_relic",System.currentTimeMillis()+1000);if(!"LOST".equals(relicLifecycle("__selftest_relic").status()))throw new SQLException("relic lifecycle");checks.add("Relic lifecycle state round trip: ok");
            grantServerAdmin("__selftest_admin","SelfTestAdmin");if(!isServerAdmin("__selftest_admin"))throw new SQLException("server admin persistence");checks.add("Console-managed admin persistence: ok");
            connection.rollback();checks.add("Test transaction rollback: ok (no test data retained)");
        } catch(Exception e){rollbackQuietly();checks.add("FAILED: "+e.getMessage());}
        finally{autoCommitQuietly();}
        return checks;
    }

    private void ensureFactionTags(){for(FactionRow faction:list("SELECT * FROM factions ORDER BY id",Database::mapFaction))if(faction.tag()==null||faction.tag().isBlank())update("UPDATE factions SET tag=? WHERE id=?",availableFactionTag(faction.name(),faction.id()),faction.id());}
    private String availableFactionTag(String name,long exclude){String clean=name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");if(clean.length()<2)clean=(clean+"XX").substring(0,2);String base=clean.substring(0,Math.min(3,clean.length()));for(int i=0;i<1000;i++){String suffix=i==0?"":Integer.toString(i,36).toUpperCase(Locale.ROOT);int keep=Math.max(2,Math.min(4-suffix.length(),base.length()));String candidate=base.substring(0,keep)+suffix;if(integer("SELECT COUNT(*) FROM factions WHERE tag=? COLLATE NOCASE AND id<>?",candidate,exclude)==0)return candidate;}return "F"+Long.toString(Math.max(0,exclude),36).toUpperCase(Locale.ROOT);}
    private static FactionRow mapFaction(ResultSet rs) throws SQLException { return new FactionRow(rs.getLong("id"),rs.getString("name"),rs.getString("tag"),rs.getString("leader"),rs.getDouble("balance"),rs.getInt("tier"),rs.getInt("home_slots"),rs.getString("world"),rs.getInt("core_x"),rs.getInt("core_z")); }
    private static AuctionRow mapAuction(ResultSet rs) throws SQLException { return new AuctionRow(rs.getLong("id"),rs.getString("seller"),rs.getString("seller_name"),ItemStack.deserializeBytes(rs.getBytes("item")),rs.getDouble("price"),rs.getLong("listed"),rs.getLong("expires"),rs.getString("status"),rs.getString("buyer"),rs.getDouble("listing_fee")); }
    private static RelicRow mapRelic(ResultSet rs) throws SQLException { return new RelicRow(rs.getString("relic_key"),rs.getString("owner"),rs.getString("owner_name"),rs.getLong("discovered_at"),rs.getInt("active")!=0); }
    private static StatsRow mapStats(ResultSet rs)throws SQLException{return new StatsRow(rs.getString("id"),rs.getString("name"),rs.getLong("play_seconds"),rs.getInt("player_kills"),rs.getInt("deaths"),rs.getInt("mob_kills"),rs.getInt("boss_kills"),rs.getInt("event_wins"),rs.getDouble("balance"));}
    private static RelicLifecycleRow mapRelicLifecycle(ResultSet rs)throws SQLException{return new RelicLifecycleRow(rs.getString("relic_key"),rs.getString("owner"),rs.getString("owner_name"),rs.getLong("discovered_at"),rs.getInt("active")!=0,rs.getString("status"),rs.getLong("last_confirmed"),rs.getLong("eligible_at"));}
    private static FeedbackRow mapFeedback(ResultSet rs)throws SQLException{return new FeedbackRow(rs.getLong("id"),rs.getString("player"),rs.getString("player_name"),rs.getLong("created_at"),rs.getString("message"),rs.getString("status"));}
    private static HistoryRow mapHistory(ResultSet rs)throws SQLException{long faction=rs.getLong("faction_id");return new HistoryRow(rs.getLong("id"),rs.getString("scope"),rs.wasNull()?null:faction,rs.getString("kind"),rs.getString("message"),rs.getLong("created_at"));}
    private static BossStateRow mapBossState(ResultSet rs)throws SQLException{return new BossStateRow(rs.getString("entity_id"),rs.getString("world"),rs.getDouble("x"),rs.getDouble("y"),rs.getDouble("z"),rs.getLong("spawned_at"),rs.getInt("hint_stage"),rs.getLong("next_hint_at"),rs.getString("reward_state"),rs.getString("origin"),rs.getDouble("base_health"),rs.getInt("active_count"),rs.getString("kind"));}
    private static MerchantRow mapMerchant(ResultSet rs)throws SQLException{return new MerchantRow(rs.getString("id"),rs.getString("type"),rs.getString("world"),rs.getDouble("x"),rs.getDouble("y"),rs.getDouble("z"),rs.getFloat("yaw"),rs.getFloat("pitch"),rs.getString("entity_id"));}
    private static AssetRow mapAsset(ResultSet rs)throws SQLException{return new AssetRow(rs.getLong("faction_id"),rs.getString("asset_key"),rs.getString("asset_type"),rs.getString("material"),rs.getDouble("value"),rs.getLong("updated_at"));}
    private static MarketValueRow mapMarketValue(ResultSet rs)throws SQLException{return new MarketValueRow(rs.getString("material"),rs.getDouble("value"),rs.getInt("samples"),rs.getLong("updated_at"));}
    private static LoanRow mapLoan(ResultSet rs)throws SQLException{return new LoanRow(rs.getLong("id"),rs.getString("player"),rs.getDouble("principal"),rs.getDouble("original_amount"),rs.getDouble("interest"),rs.getDouble("rate_daily"),rs.getLong("issued_at"),rs.getLong("due_at"),rs.getLong("last_accrual"),rs.getString("status"));}
    private static GraveRow mapGrave(ResultSet rs)throws SQLException{return new GraveRow(rs.getLong("id"),rs.getString("owner"),column(rs,"owner_uuid"),rs.getString("owner_name"),column(rs,"public_name"),column(rs,"skin_value"),column(rs,"skin_signature"),rs.getString("world"),rs.getDouble("x"),rs.getDouble("y"),rs.getDouble("z"),rs.getLong("created_at"),rs.getLong("expires_at"),rs.getString("marker_uuid"));}
    private static String column(ResultSet rs,String name)throws SQLException{try{return rs.getString(name);}catch(SQLException ignored){return null;}}
    private static RelationRow mapRelation(ResultSet rs)throws SQLException{return new RelationRow(rs.getLong("faction_low"),rs.getLong("faction_high"),rs.getString("relation_type"),rs.getString("pending_type"),rs.getLong("requested_by"),rs.getInt("storage_low")!=0,rs.getInt("storage_high")!=0,rs.getLong("updated_at"),rs.getInt("homes_low")!=0,rs.getInt("homes_high")!=0);}
    private static double roundMoney(double value){return Math.round(value*100.0)/100.0;}

    private int update(String sql,Object... args) { try(PreparedStatement ps=connection.prepareStatement(sql)){ bind(ps,args); return ps.executeUpdate(); }catch(SQLException e){throw fail(e);} }
    private int integer(String sql,Object... args){ Integer i=one(sql,rs->rs.getInt(1),args); return i==null?0:i; }
    private double scalarDouble(String sql,Object... args){Double value=one(sql,rs->rs.getDouble(1),args);return value==null?0:value;}
    private String scalar(String sql,Object... args){ return one(sql,rs->rs.getString(1),args); }
    private List<String> strings(String sql,Object... args){ return list(sql,rs->rs.getString(1),args); }
    private <T> T one(String sql,Mapper<T> mapper,Object... args){ List<T> l=list(sql,mapper,args); return l.isEmpty()?null:l.getFirst(); }
    private <T> List<T> list(String sql,Mapper<T> mapper,Object... args){ try(PreparedStatement ps=connection.prepareStatement(sql)){bind(ps,args);try(ResultSet rs=ps.executeQuery()){List<T> out=new ArrayList<>();while(rs.next())out.add(mapper.map(rs));return out;}}catch(SQLException e){throw fail(e);} }
    private static void bind(PreparedStatement ps,Object... args)throws SQLException{for(int i=0;i<args.length;i++){Object v=args[i];if(v instanceof byte[] b)ps.setBytes(i+1,b);else ps.setObject(i+1,v);}}
    private RuntimeException fail(SQLException e){plugin.getLogger().severe("Database error: "+e.getMessage());return new IllegalStateException(e);}
    private void rollbackQuietly(){try{connection.rollback();}catch(SQLException ignored){}}
    private void autoCommitQuietly(){try{connection.setAutoCommit(true);}catch(SQLException ignored){}}
    @FunctionalInterface private interface Mapper<T>{T map(ResultSet rs)throws SQLException;}
    @Override public synchronized void close(){if(connection!=null)try{connection.close();}catch(SQLException ignored){}}
}
