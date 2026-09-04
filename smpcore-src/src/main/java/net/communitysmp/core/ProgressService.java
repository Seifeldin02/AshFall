package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class ProgressService implements Listener {
    private record Holder() implements InventoryHolder { @Override public Inventory getInventory(){return null;} }
    private record DetailHolder(String key) implements InventoryHolder { @Override public Inventory getInventory(){return null;} }
    private record Requirement(String name,String description,boolean complete,String counter,Material icon,double multiplier,int rank,String detailKey) {}
    private record EnchantSpec(Material material,Map<Enchantment,Integer> required,List<Map.Entry<Enchantment,Integer>> anyOf,String label) {}
    private final SMPCore plugin;
    private final Database db;
    private final NamespacedKey itemIdentityKey,itemOwnerKey,directTransferKey,detailKeyTag;
    private BukkitTask playTask;
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneId.systemDefault());
    private static final List<String> RANK_NAMES=List.of("Wayfarer","Trailblazer","Riftwalker","Conqueror","Vanguard","Ashforged Paragon","Sovereign of Ashfall");

    ProgressService(SMPCore plugin){this.plugin=plugin;db=plugin.db();itemIdentityKey=new NamespacedKey(plugin,"progress_item_id");itemOwnerKey=new NamespacedKey(plugin,"progress_item_owner");directTransferKey=new NamespacedKey(plugin,"progress_direct_transfer");detailKeyTag=new NamespacedKey(plugin,"progress_detail_key");playTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::tickPlaytime,1200L,1200L);}
    void shutdown(){tickPlaytime();if(playTask!=null)playTask.cancel();}
    void join(Player player){
        String id=CoreUtil.id(player);db.touchPlayer(id);try{db.setPlaySecondsAtLeast(id,player.getStatistic(Statistic.PLAY_ONE_MINUTE)/20L);}catch(Exception ignored){}
        backfillLegacyProgress(player);revalidateFullGear(player);inspectLoadout(player,false);if(db.hasSpawnerProgress(id))db.markMilestone(id,"VANGUARD_FACTION_SPAWNER");refreshRanks(player,false);
        if("true".equalsIgnoreCase(db.preference(id,"dragon_participant_repair"))){grantAdvancement(player,"end/kill_dragon");CoreUtil.give(player,new ItemStack(Material.DRAGON_BREATH));CoreUtil.msg(player,"Dragon participation restored: achievement, progression, and reward credited.");db.preference(id,"dragon_participant_repair","false");}
    }
    void quit(Player player){db.touchPlayer(CoreUtil.id(player));multiplierCache.remove(CoreUtil.id(player));}
    private void tickPlaytime(){for(Player player:plugin.getServer().getOnlinePlayers())db.addPlaySeconds(CoreUtil.id(player),60);}

    boolean sidebarEnabled(Player player,boolean bedrock){String value=db.preference(CoreUtil.id(player),"sidebar");return value==null||Boolean.parseBoolean(value);}
    boolean toggleSidebar(Player player){boolean next=!sidebarEnabled(player,plugin.isBedrock(player));db.preference(CoreUtil.id(player),"sidebar",Boolean.toString(next));if(!next)plugin.ui().removeSidebar(player);else plugin.ui().update(player);CoreUtil.msg(player,"Sidebar "+(next?"enabled":"disabled")+".");return true;}
    boolean trackingEnabled(Player player){return !"false".equalsIgnoreCase(db.preference(CoreUtil.id(player),"event_tracking"));}
    void setTracking(Player player,boolean enabled){db.preference(CoreUtil.id(player),"event_tracking",Boolean.toString(enabled));CoreUtil.msg(player,"Event tracking "+(enabled?"enabled":"disabled")+".");}

    void worldChanged(Player player,World.Environment environment){if(environment==World.Environment.NETHER)milestone(player,"ENTER_NETHER","entered the Nether for the first time",500,Material.GOLDEN_APPLE,true);else if(environment==World.Environment.THE_END)milestone(player,"ENTER_END","reached the End for the first time",1500,Material.ENDER_EYE,true);}
    /** Duel kits are borrowed gear, not achievements -- nothing done inside the arena world (equipped with a
     *  kit, breaking placed blocks, or spectating) may advance /progression. The spear's elytra was crediting
     *  the Elytra milestone, etc. */
    private boolean inArena(Player player){ return plugin.arena()!=null && plugin.arena().isArenaWorld(player.getWorld()); }
    void blockMined(Player player,Material material){
        if(inArena(player))return;
        if(material==Material.DIAMOND_ORE||material==Material.DEEPSLATE_DIAMOND_ORE)milestone(player,"ADVENTURE_DIAMOND","mined their first diamond ore",250,null,false);
        else if(material==Material.ANCIENT_DEBRIS){milestone(player,"ANCIENT_DEBRIS_MINED","unearthed ancient debris",750,null,false);refreshAdvanced(player);}
    }
    void hostileKill(Player player,EntityType type,World.Environment environment){if(inArena(player))return;if(environment==World.Environment.NETHER&&Set.of(EntityType.BLAZE,EntityType.PIGLIN_BRUTE,EntityType.WITHER_SKELETON).contains(type))milestone(player,"ADVENTURE_NETHER","proved themselves against the Nether",500,Material.GOLDEN_APPLE,true);}
    void acquired(Player player,Material material){if(inArena(player))return;switch(material){case BLAZE_ROD->milestone(player,"BLAZE_ROD","acquired their first Blaze Rod",200,null,false);case ENDER_EYE->milestone(player,"ENDER_EYE","crafted or found their first Eye of Ender",300,null,false);case NETHERITE_INGOT->milestone(player,"NETHERITE_INGOT","forged their first Netherite Ingot",750,null,true);case ELYTRA->milestone(player,"ELYTRA","claimed their first Elytra",2000,Material.FIREWORK_ROCKET,true);case WITHER_SKELETON_SKULL->milestone(player,"WITHER_SKULL","found their first Wither Skeleton Skull",350,null,false);default->{}}}
    void majorKill(Player player,EntityType type){if(inArena(player))return;if(type==EntityType.WITHER){grantAdvancement(player,"nether/summon_wither");milestone(player,"DEFEAT_WITHER","defeated the Wither for the first time",4000,Material.BEACON,true);}else if(type==EntityType.ENDER_DRAGON){grantAdvancement(player,"end/kill_dragon");milestone(player,"DEFEAT_DRAGON","defeated the Ender Dragon for the first time",6000,Material.DRAGON_BREATH,true);}refreshAdvanced(player);}

    void repairDragonParticipant(String name){
        Database.StatsRow row=db.statsByName(name);if(row==null||db.hasMilestone(row.id(),"DEFEAT_DRAGON")||!db.markMilestone(row.id(),"DEFEAT_DRAGON"))return;double reward=plugin.getConfig().getDouble("milestones.rewards.DEFEAT_DRAGON",6000);
        if(reward>0){plugin.creditEarned(row.id(),reward,"DRAGON_PARTICIPATION_REPAIR");db.recordEconomy(row.id(),"MILESTONE",reward,"DEFEAT_DRAGON_REPAIR");}
        db.state("major:"+row.id()+":ENDER_DRAGON",Long.toString(System.currentTimeMillis()));db.preference(row.id(),"dragon_participant_repair","true");db.history("SERVER",null,"MILESTONE",row.name()+" received restored Ender Dragon participation credit.");
    }
    /** Re-checks ASHFORGED_FULL_GEAR against the CURRENT spec once per player.
     *
     *  Adding Soul Speed III to the boots means some players hold a completion they no longer satisfy, and
     *  the rank multiplier is derived live from completed missions -- so leaving it would keep paying an
     *  ongoing bonus for a requirement that is no longer met. Revoked rather than grandfathered because
     *  this mission carries no one-time money reward, so there is nothing to claw back or double-pay.
     *  Guarded by a preference flag so it runs once and does not re-punish someone who simply logs in
     *  without their armour on. */
    private void revalidateFullGear(Player player){
        String id=CoreUtil.id(player);
        if("true".equalsIgnoreCase(db.preference(id,"fullgear_soulspeed_recheck")))return;
        db.preference(id,"fullgear_soulspeed_recheck","true");
        if(!db.hasMilestone(id,"ASHFORGED_FULL_GEAR"))return;
        ItemStack[] armor=player.getInventory().getArmorContents();
        boolean stillQualifies=armor.length==4
                &&meetsSpec(armor[3],FULL_GEAR_HELMET)&&meetsSpec(armor[2],FULL_GEAR_CHEST)
                &&meetsSpec(armor[1],FULL_GEAR_LEGS)&&meetsSpec(armor[0],FULL_GEAR_BOOTS);
        /** Not wearing it right now is not proof they cannot meet it, so the inventory is checked too. */
        if(!stillQualifies)stillQualifies=matching(player,FULL_GEAR_HELMET)!=null&&matching(player,FULL_GEAR_CHEST)!=null
                &&matching(player,FULL_GEAR_LEGS)!=null&&matching(player,FULL_GEAR_BOOTS)!=null;
        if(stillQualifies)return;
        if(db.clearMilestone(id,"ASHFORGED_FULL_GEAR")){
            multiplierCache.remove(id);
            CoreUtil.msg(player,"The Ashforged Armor mission now requires Soul Speed III on the boots. Your completion has been reset until your set meets it again; use /progress to review.");
        }
    }
    private void backfillLegacyProgress(Player player){
        String id=CoreUtil.id(player);if("true".equalsIgnoreCase(db.preference(id,"adventure_backfill_v1")))return;Set<String> existing=db.milestones(id);
        boolean diamond=mined(player,Material.DIAMOND_ORE)||mined(player,Material.DEEPSLATE_DIAMOND_ORE),dragon=killed(player,EntityType.ENDER_DRAGON)||advancementDone(player,"end/kill_dragon")||existing.contains("DEFEAT_DRAGON"),wither=killed(player,EntityType.WITHER)||advancementDone(player,"nether/summon_wither")||existing.contains("DEFEAT_WITHER");
        boolean nether=existing.contains("ENTER_NETHER")||existing.contains("BLAZE_ROD")||existing.contains("WITHER_SKULL")||existing.contains("NETHERITE_INGOT")||killed(player,EntityType.BLAZE)||killed(player,EntityType.PIGLIN_BRUTE)||killed(player,EntityType.WITHER_SKELETON)||wither,end=advancementDone(player,"end/root")||dragon,debris=mined(player,Material.ANCIENT_DEBRIS)||existing.contains("ANCIENT_DEBRIS_MINED");int restored=0;
        if(diamond)restored+=restore(id,"ADVENTURE_DIAMOND");if(nether)restored+=restore(id,"ADVENTURE_NETHER");if(end)restored+=restore(id,"ENTER_END");if(dragon)restored+=restore(id,"DEFEAT_DRAGON");if(wither)restored+=restore(id,"DEFEAT_WITHER");if(debris)restored+=restore(id,"ANCIENT_DEBRIS_MINED");if(dragon&&wither&&debris)restored+=restore(id,"ADVENTURE_MASTER");
        db.preference(id,"adventure_backfill_v1","true");if(restored>0)CoreUtil.msg(player,"Restored "+restored+" previous progression milestone"+(restored==1?"":"s")+". Use /progress.");
    }
    private int restore(String id,String milestone){return db.markMilestone(id,milestone)?1:0;}
    private boolean mined(Player player,Material material){try{return player.getStatistic(Statistic.MINE_BLOCK,material)>0;}catch(Exception ignored){return false;}}
    private boolean killed(Player player,EntityType type){try{return player.getStatistic(Statistic.KILL_ENTITY,type)>0;}catch(Exception ignored){return false;}}
    private boolean advancementDone(Player player,String key){try{var advancement=Bukkit.getAdvancement(NamespacedKey.minecraft(key));return advancement!=null&&player.getAdvancementProgress(advancement).isDone();}catch(Exception ignored){return false;}}
    private void refreshAdvanced(Player player){String id=CoreUtil.id(player);if(db.hasMilestone(id,"DEFEAT_DRAGON")&&db.hasMilestone(id,"DEFEAT_WITHER")&&db.hasMilestone(id,"ANCIENT_DEBRIS_MINED"))milestone(player,"ADVENTURE_MASTER","completed Ashfall's great survival trials",7500,Material.ECHO_SHARD,true);}

    private int rank(String id){Set<String> done=db.milestones(id);for(int level=5;level>=1;level--)if(done.contains("PROGRESS_V2_RANK_"+level))return level;return 0;}
    /** Rank 6 ("Sovereign of Ashfall") is never persisted as a PROGRESS_V2_RANK_6 milestone — the user's spec is
     *  explicit that it must reflect ACTIVE relic ownership only, disappearing the instant the relic is lost.
     *  Every other rank is a permanent, one-time achievement; this one is deliberately live-computed instead.
     *  Package-visible so RelicService's chat symbol and TabIntegration's TAB suffix can gate on the SAME
     *  condition the rank display uses — owning an active relic alone must never be enough on its own. */
    boolean sovereign(String id){return rank(id)>=5&&plugin.relics()!=null&&plugin.relics().ownsActiveRelic(id);}
    private int effectiveRank(String id){return sovereign(id)?6:rank(id);}
    private double increment(String key,double fallback){return plugin.getConfig().getDouble("progression.requirement-multipliers."+key,fallback);}
    private List<Requirement> requirements(String id,int level,Set<String> done,Database.ProgressMetrics metrics){
        List<Requirement> rows=new ArrayList<>();
        switch(level){
            case 1->{rows.add(req("First Shine","Mine your first Diamond Ore","ADVENTURE_DIAMOND",done,Material.DIAMOND_ORE,increment("diamond",.08),1));rows.add(req("Banner Bound","Join or create a faction","WAYFARER_FACTION_MEMBER",done,Material.WHITE_BANNER,increment("faction-member",.04),1));rows.add(req("Staked Claim","Be part of a faction that has claimed land at least once","WAYFARER_FACTION_LAND",done,Material.GOLDEN_SHOVEL,increment("faction-land",.05),1));rows.add(req("Home Fires","Be part of a faction that has set a faction home at least once","WAYFARER_FACTION_HOME",done,Material.RED_BED,increment("faction-home",.03),1));}
            case 2->{rows.add(req("Hellbound","Defeat a Blaze, Piglin Brute, or Wither Skeleton","ADVENTURE_NETHER",done,Material.BLAZE_POWDER,increment("nether-combat",.10),2));rows.add(req("One Small Step","Step through an End Portal","ENTER_END",done,Material.END_PORTAL_FRAME,increment("enter-end",.10),2));rows.add(req("Wings of Ash","Claim your first Elytra","ELYTRA",done,Material.ELYTRA,increment("elytra",.15),2));}
            case 3->{rows.add(req("Deep Cut","Mine Ancient Debris","ANCIENT_DEBRIS_MINED",done,Material.ANCIENT_DEBRIS,increment("ancient-debris",.05),3));rows.add(req("Dragonslayer","Take part in defeating the Ender Dragon","DEFEAT_DRAGON",done,Material.DRAGON_BREATH,increment("dragon",.10),3));rows.add(req("Bone to Pick","Take part in defeating the Wither","DEFEAT_WITHER",done,Material.NETHER_STAR,increment("wither",.10),3));}
            case 4->{double target=plugin.getConfig().getDouble("progression.vanguard-economic-target",250_000);rows.add(req("Big Game Hunter","Take part in defeating an Epic-tier mob","EPIC_PARTICIPANT",done,Material.ENCHANTED_GOLDEN_APPLE,increment("epic",.20),4));rows.add(new Requirement("Regular","Participate in 3 server events",metrics.eventParticipations()>=3,metrics.eventParticipations()+" / 3",Material.CLOCK,increment("three-events",.20),4,null));rows.add(new Requirement("Coin Counter","Generate "+CoreUtil.money(target)+" in eligible economic activity",metrics.economicScore()>=target,CoreUtil.money(metrics.economicScore())+" / "+CoreUtil.money(target),Material.GOLD_INGOT,increment("economic-activity",.25),4,null));rows.add(req("Head to Toe","Equip a full set of Netherite armor at once","VANGUARD_NETHERITE_ARMOR",done,Material.NETHERITE_CHESTPLATE,increment("netherite-armor",.35),4));rows.add(req("Fully Loaded","Own a Netherite Sword, Pickaxe, Axe, and Shovel","VANGUARD_NETHERITE_TOOLS",done,Material.NETHERITE_PICKAXE,increment("netherite-tools",.25),4));rows.add(req("Homegrown","Have a faction spawner placed after you joined","VANGUARD_FACTION_SPAWNER",done,Material.IRON_BARS,increment("faction-spawner",.40),4));}
            case 5->{
                rows.add(reqDetail("Ashforged Armor","Own a fully maxed Netherite armor set","ASHFORGED_FULL_GEAR",done,Material.NETHERITE_CHESTPLATE,increment("netherite-full-gear",.45),5,"FULL_GEAR"));
                rows.add(reqDetail("Blade & Arrow","Own a fully enchanted Netherite Sword and Bow","ASHFORGED_SWORD_BOW",done,Material.NETHERITE_SWORD,increment("netherite-sword-bow",.40),5,"SWORD_BOW"));
                rows.add(reqDetail("Master Craftsman","Own fully enchanted Netherite tools","ASHFORGED_TOOLS",done,Material.NETHERITE_PICKAXE,increment("netherite-tools-maxed",.40),5,"TOOLS"));
            }
            case 6->{boolean owns=plugin.relics()!=null&&plugin.relics().ownsActiveRelic(id);rows.add(new Requirement("Crowned","Actively own an active World Relic",owns,owns?"ACTIVE":"NOT CURRENTLY OWNED",Material.NETHER_STAR,increment("relic-ownership",.50),6,null));}
            default->{}
        }return rows;
    }
    private Requirement req(String name,String description,String key,Set<String> done,Material icon,double reward,int rank){boolean complete=done.contains(key);return new Requirement(name,description,complete,complete?"1 / 1":"0 / 1",icon,reward,rank,null);}
    private Requirement reqDetail(String name,String description,String key,Set<String> done,Material icon,double reward,int rank,String detailKey){boolean complete=done.contains(key);return new Requirement(name,description,complete,complete?"1 / 1":"0 / 1",icon,reward,rank,detailKey);}
    /** rank=0 is a sentinel for "not required by any rank" — used only by the standalone Hoe bonus below, which
     *  is deliberately excluded from requirements(level,...) so it can never gate rank-5 eligibility. */
    private Requirement hoeRequirement(Set<String> done){boolean complete=done.contains("HOE_MAXED_BONUS");return new Requirement("Hoe...?","Own a fully maxed Netherite Hoe",complete,complete?"1 / 1":"0 / 1",Material.NETHERITE_HOE,increment("hoe-bonus",.05),0,"HOE");}
    private List<Requirement> allRequirements(String id,Set<String> done,Database.ProgressMetrics metrics){List<Requirement> all=new ArrayList<>();for(int level=1;level<=6;level++){all.addAll(requirements(id,level,done,metrics));if(level==5)all.add(hoeRequirement(done));}return all;}
    private boolean eligible(String id,int level,Set<String> done,Database.ProgressMetrics metrics){return requirements(id,level,done,metrics).stream().allMatch(Requirement::complete);}
    private final Set<String> sovereignActive=new HashSet<>();
    void refreshRanks(Player player,boolean announce){
        inspectLoadout(player,false);String id=CoreUtil.id(player);if(db.hasSpawnerProgress(id))db.markMilestone(id,"VANGUARD_FACTION_SPAWNER");if(db.factionOf(id)!=null)db.markMilestone(id,"WAYFARER_FACTION_MEMBER");Set<String> done=db.milestones(id);Database.ProgressMetrics metrics=db.progressMetrics(id);int before=rank(id);
        for(int next=before+1;next<=5&&eligible(id,next,done,metrics);next++){if(!db.markMilestone(id,"PROGRESS_V2_RANK_"+next))break;done.add("PROGRESS_V2_RANK_"+next);grantRankReward(player,next);before=next;if(announce){CoreUtil.msg(player,"Progress rank: "+RANK_NAMES.get(next)+" • "+String.format(Locale.US,"%.2fx",multiplier(id,done,metrics))+" hostile-mob income");player.playSound(player.getLocation(),Sound.ENTITY_PLAYER_LEVELUP,.8f,1.35f);}}
        if(sovereign(id)){if(sovereignActive.add(id)&&announce){CoreUtil.msg(player,"Progress rank: "+RANK_NAMES.get(6)+" • "+String.format(Locale.US,"%.2fx",multiplier(id,done,metrics))+" hostile-mob income");player.playSound(player.getLocation(),Sound.ENTITY_PLAYER_LEVELUP,.8f,1.5f);}}
        else sovereignActive.remove(id);
    }
    private void grantRankReward(Player player,int level){String id=CoreUtil.id(player);if(!db.markMilestone(id,"PROGRESS_V2_REWARD_"+level))return;double reward=rankReward(level);if(reward>0){plugin.creditEarned(id,reward,"PROGRESS_RANK_"+RANK_NAMES.get(level));db.recordEconomy(id,"MILESTONE",reward,"PROGRESS_RANK_"+RANK_NAMES.get(level));CoreUtil.msg(player,RANK_NAMES.get(level)+" completion reward: "+CoreUtil.money(reward)+".");}}
    private double rankReward(int level){return plugin.getConfig().getDouble("progression.rank-rewards."+RANK_NAMES.get(level).toUpperCase(Locale.ROOT),switch(level){case 1->5000;case 2->25000;case 3->150000;case 4->250000;default->0;});}
    private double multiplier(String id,Set<String> done,Database.ProgressMetrics metrics){double total=1;for(Requirement requirement:allRequirements(id,done,metrics))if(requirement.complete()||done.contains("PROGRESS_V2_RANK_"+requirement.rank()))total+=requirement.multiplier();return Math.round(total*100.0)/100.0;}
    private final Map<String,double[]> multiplierCache=new HashMap<>();
    double mobIncomeMultiplier(Player player){
        String id=CoreUtil.id(player);long now=System.currentTimeMillis();double[] cached=multiplierCache.get(id);
        if(cached!=null&&now<cached[1])return cached[0];
        refreshRanks(player,true);double value=multiplier(id,db.milestones(id),db.progressMetrics(id));
        multiplierCache.put(id,new double[]{value,now+5000});return value;
    }
    private double maximumMultiplier(){return Math.round((1+increment("diamond",.08)+increment("faction-member",.04)+increment("faction-land",.05)+increment("faction-home",.03)+increment("nether-combat",.10)+increment("enter-end",.10)+increment("elytra",.15)+increment("ancient-debris",.05)+increment("dragon",.10)+increment("wither",.10)+increment("epic",.20)+increment("three-events",.20)+increment("economic-activity",.25)+increment("netherite-armor",.35)+increment("netherite-tools",.25)+increment("faction-spawner",.40)+increment("netherite-full-gear",.45)+increment("netherite-sword-bow",.40)+increment("netherite-tools-maxed",.40)+increment("relic-ownership",.50)+increment("hoe-bonus",.05))*100.0)/100.0;}

    boolean show(Player player){
        inspectLoadout(player,false);refreshRanks(player,false);String id=CoreUtil.id(player);Set<String> done=db.milestones(id);Database.ProgressMetrics metrics=db.progressMetrics(id);int level=effectiveRank(id);double current=multiplier(id,done,metrics);
        Inventory inventory=plugin.getServer().createInventory(new Holder(),54,Component.text("Adventure Progress",NamedTextColor.DARK_GREEN));
        inventory.setItem(4,icon(Material.NETHER_STAR,RANK_NAMES.get(level),List.of("Current Multiplier: "+String.format(Locale.US,"%.2fx",current),"Maximum Multiplier: "+String.format(Locale.US,"%.2fx",maximumMultiplier()),level>=6?"Final rank completed":"Next rank: "+RANK_NAMES.get(level+1))));
        int slot=9;for(Requirement requirement:allRequirements(id,done,metrics))inventory.setItem(slot++,requirementIcon(requirement));
        for(int rank=1;rank<=5;rank++){boolean complete=done.contains("PROGRESS_V2_RANK_"+rank);List<String> rankLore=new ArrayList<>(List.of(complete?"COMPLETE":"INCOMPLETE"));double reward=rankReward(rank);if(reward>0)rankLore.add("Rank reward: "+CoreUtil.money(reward));inventory.setItem(44+rank,icon(complete?Material.LIME_STAINED_GLASS_PANE:Material.RED_STAINED_GLASS_PANE,RANK_NAMES.get(rank)+" Completion",rankLore));}
        boolean sov=sovereign(id);inventory.setItem(50,icon(sov?Material.LIME_STAINED_GLASS_PANE:Material.RED_STAINED_GLASS_PANE,RANK_NAMES.get(6)+" Status",List.of(sov?"ACTIVE — you currently own an active World Relic":"INACTIVE — requires currently owning an active World Relic","Live status: lost the instant relic ownership is lost")));
        player.openInventory(inventory);return true;
    }
    @EventHandler public void click(InventoryClickEvent event){
        InventoryHolder holder=event.getInventory().getHolder(false);
        if(holder instanceof DetailHolder){
            event.setCancelled(true);
            if(event.getSlot()==8&&event.getWhoClicked() instanceof Player player)show(player);
            return;
        }
        if(!(holder instanceof Holder))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player))return;
        ItemStack clicked=event.getCurrentItem();if(clicked==null||!clicked.hasItemMeta())return;
        String key=clicked.getItemMeta().getPersistentDataContainer().get(detailKeyTag,PersistentDataType.STRING);
        if(key!=null)openEquipmentDetail(player,key);
    }
    private ItemStack requirementIcon(Requirement requirement){
        List<String> lore=new ArrayList<>(List.of(requirement.description(),(requirement.complete()?"COMPLETE":"INCOMPLETE")+" • "+requirement.counter(),"Reward: +"+String.format(Locale.US,"%.2fx",requirement.multiplier()),requirement.rank()==0?"Bonus • not required for any rank":"Rank: "+RANK_NAMES.get(requirement.rank())));
        if(requirement.detailKey()!=null)lore.add("Click for exact requirements");
        ItemStack item=icon(requirement.complete()?Material.LIME_DYE:requirement.icon(),(requirement.complete()?"✓ ":"✕ ")+requirement.name(),lore);
        if(requirement.detailKey()!=null){ItemMeta meta=item.getItemMeta();meta.getPersistentDataContainer().set(detailKeyTag,PersistentDataType.STRING,requirement.detailKey());item.setItemMeta(meta);}
        return item;
    }
    String rankLabel(Player player){refreshRanks(player,false);return RANK_NAMES.get(effectiveRank(CoreUtil.id(player)));}
    double multiplierView(Player player){refreshRanks(player,false);String id=CoreUtil.id(player);return multiplier(id,db.milestones(id),db.progressMetrics(id));}
    private ItemStack icon(Material material,String name,List<String> lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(name,NamedTextColor.GOLD));meta.lore(lore.stream().map(line->Component.text(line,NamedTextColor.GRAY)).toList());item.setItemMeta(meta);return item;}
    void eliteParticipation(Player player,String tier){String key=tier.equalsIgnoreCase("legendary")?"LEGENDARY_PARTICIPANT":tier.equalsIgnoreCase("epic")?"EPIC_PARTICIPANT":null;if(key!=null){db.markMilestone(CoreUtil.id(player),key);refreshRanks(player,true);}}
    void eventParticipated(Player player){refreshRanks(player,true);}
    void inspectLoadout(Player player,boolean announce){
        if(inArena(player))return;
        String id=CoreUtil.id(player);ItemStack[] armor=player.getInventory().getArmorContents();List<ItemStack> armorSet=armor.length<4?List.of():Arrays.asList(armor);
        boolean fullArmor=armorSet.size()==4&&armorSet.stream().allMatch(item->item!=null&&Set.of(Material.NETHERITE_HELMET,Material.NETHERITE_CHESTPLATE,Material.NETHERITE_LEGGINGS,Material.NETHERITE_BOOTS).contains(item.getType()));
        Map<Material,ItemStack> owned=new EnumMap<>(Material.class);for(ItemStack item:player.getInventory().getContents())if(item!=null)owned.putIfAbsent(item.getType(),item);
        List<ItemStack> toolSet=Arrays.asList(owned.get(Material.NETHERITE_SWORD),owned.get(Material.NETHERITE_PICKAXE),owned.get(Material.NETHERITE_AXE),owned.get(Material.NETHERITE_SHOVEL));
        boolean tools=toolSet.stream().allMatch(Objects::nonNull),changed=false;
        if(fullArmor&&qualifyEquipment(player,armorSet))changed|=db.markMilestone(id,"VANGUARD_NETHERITE_ARMOR");
        if(tools&&qualifyEquipment(player,toolSet))changed|=db.markMilestone(id,"VANGUARD_NETHERITE_TOOLS");
        if(inspectMasterwork(player,id,armorSet,owned))changed=true;
        if(changed&&announce){CoreUtil.msg(player,"Equipment requirement completed.");refreshRanks(player,true);}
    }
    /** Bukkit's armor-contents order is [boots, leggings, chestplate, helmet]. Each of the three Ashforged Paragon
     *  missions and the standalone Hoe bonus reuses qualifyEquipment() (the existing provenance/anti-reuse system)
     *  exactly as the Vanguard armor/tools checks above do, per explicit instruction to not invent a second system. */
    /** Any item of the required TYPE carrying the required upgrades qualifies, whatever it is called.
     *
     *  Matching was already by Material and enchantment level -- display name and lore were never consulted,
     *  so a renamed drop was never rejected for its name. What did reject it was WHICH copy got inspected:
     *  the caller built one item per Material with putIfAbsent, i.e. the first stack encountered in slot
     *  order, and every spec check ran against that single candidate. Carry an ordinary Netherite Pickaxe in
     *  an earlier slot than the maxed one and the maxed one was never looked at, which reads exactly like
     *  "my upgraded item doesn't count". Scanning all slots removes that ordering dependency.
     *
     *  Provenance is untouched: whichever item matches is still passed through qualifyEquipment() as before,
     *  so the anti-reuse and direct-transfer rules apply exactly as they did. */
    private ItemStack matching(Player player,EnchantSpec spec){
        for(ItemStack item:player.getInventory().getContents())if(meetsSpec(item,spec))return item;
        for(ItemStack item:player.getInventory().getArmorContents())if(meetsSpec(item,spec))return item;
        return null;
    }
    private boolean inspectMasterwork(Player player,String id,List<ItemStack> armorSet,Map<Material,ItemStack> owned){
        boolean changed=false;
        if(armorSet.size()==4){
            ItemStack boots=armorSet.get(0),legs=armorSet.get(1),chest=armorSet.get(2),helmet=armorSet.get(3);
            /** Worn deliberately: this requirement is to EQUIP a full set, so it reads the armour slots
             *  rather than the inventory. The pieces themselves may be any renamed or boss-dropped variant,
             *  since meetsSpec only looks at type and upgrades. */
            if(meetsSpec(helmet,FULL_GEAR_HELMET)&&meetsSpec(chest,FULL_GEAR_CHEST)&&meetsSpec(legs,FULL_GEAR_LEGS)&&meetsSpec(boots,FULL_GEAR_BOOTS)&&qualifyEquipment(player,armorSet))
                changed|=db.markMilestone(id,"ASHFORGED_FULL_GEAR");
        }
        ItemStack sword=matching(player,SWORD_SPEC),bow=matching(player,BOW_SPEC);
        if(sword!=null&&bow!=null&&qualifyEquipment(player,List.of(sword,bow)))
            changed|=db.markMilestone(id,"ASHFORGED_SWORD_BOW");
        ItemStack pick=matching(player,PICKAXE_SPEC),axe=matching(player,AXE_SPEC),shovel=matching(player,SHOVEL_SPEC);
        if(pick!=null&&axe!=null&&shovel!=null&&qualifyEquipment(player,Arrays.asList(pick,axe,shovel)))
            changed|=db.markMilestone(id,"ASHFORGED_TOOLS");
        ItemStack hoe=matching(player,HOE_SPEC);
        if(hoe!=null&&qualifyEquipment(player,List.of(hoe))&&db.markMilestone(id,"HOE_MAXED_BONUS"))
            changed=true;
        return changed;
    }
    private boolean meetsSpec(ItemStack item,EnchantSpec spec){
        if(item==null||item.getType()!=spec.material()||!item.hasItemMeta())return false;
        ItemMeta meta=item.getItemMeta();
        for(var entry:spec.required().entrySet())if(meta.getEnchantLevel(entry.getKey())<entry.getValue())return false;
        if(!spec.anyOf().isEmpty()){
            boolean any=false;for(var alt:spec.anyOf())if(meta.getEnchantLevel(alt.getKey())>=alt.getValue()){any=true;break;}
            if(!any)return false;
        }
        return true;
    }
    private static final EnchantSpec FULL_GEAR_HELMET=new EnchantSpec(Material.NETHERITE_HELMET,Map.of(Enchantment.PROTECTION,4,Enchantment.MENDING,1,Enchantment.UNBREAKING,3,Enchantment.RESPIRATION,3,Enchantment.AQUA_AFFINITY,1),List.of(),"Netherite Helmet");
    private static final EnchantSpec FULL_GEAR_CHEST=new EnchantSpec(Material.NETHERITE_CHESTPLATE,Map.of(Enchantment.PROTECTION,4,Enchantment.MENDING,1,Enchantment.UNBREAKING,3),List.of(),"Netherite Chestplate");
    private static final EnchantSpec FULL_GEAR_LEGS=new EnchantSpec(Material.NETHERITE_LEGGINGS,Map.of(Enchantment.PROTECTION,4,Enchantment.MENDING,1,Enchantment.UNBREAKING,3,Enchantment.SWIFT_SNEAK,3),List.of(),"Netherite Leggings");
    private static final EnchantSpec FULL_GEAR_BOOTS=new EnchantSpec(Material.NETHERITE_BOOTS,Map.of(Enchantment.PROTECTION,4,Enchantment.MENDING,1,Enchantment.UNBREAKING,3,Enchantment.FEATHER_FALLING,4,Enchantment.DEPTH_STRIDER,3,Enchantment.SOUL_SPEED,3),List.of(),"Netherite Boots (with Soul Speed III)");
    private static final EnchantSpec SWORD_SPEC=new EnchantSpec(Material.NETHERITE_SWORD,Map.of(Enchantment.SHARPNESS,5,Enchantment.LOOTING,3,Enchantment.SWEEPING_EDGE,3,Enchantment.UNBREAKING,3,Enchantment.MENDING,1),List.of(),"Netherite Sword");
    private static final EnchantSpec BOW_SPEC=new EnchantSpec(Material.BOW,Map.of(Enchantment.POWER,5,Enchantment.PUNCH,2,Enchantment.FLAME,1,Enchantment.UNBREAKING,3),List.of(Map.entry(Enchantment.INFINITY,1),Map.entry(Enchantment.MENDING,1)),"Bow (Mending OR Infinity)");
    private static final EnchantSpec PICKAXE_SPEC=new EnchantSpec(Material.NETHERITE_PICKAXE,Map.of(Enchantment.EFFICIENCY,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1),List.of(Map.entry(Enchantment.FORTUNE,3),Map.entry(Enchantment.SILK_TOUCH,1)),"Netherite Pickaxe (Fortune III OR Silk Touch)");
    private static final EnchantSpec AXE_SPEC=new EnchantSpec(Material.NETHERITE_AXE,Map.of(Enchantment.EFFICIENCY,5,Enchantment.SHARPNESS,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1),List.of(),"Netherite Axe");
    private static final EnchantSpec SHOVEL_SPEC=new EnchantSpec(Material.NETHERITE_SHOVEL,Map.of(Enchantment.EFFICIENCY,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1),List.of(Map.entry(Enchantment.FORTUNE,3),Map.entry(Enchantment.SILK_TOUCH,1)),"Netherite Shovel (Fortune III OR Silk Touch)");
    private static final EnchantSpec HOE_SPEC=new EnchantSpec(Material.NETHERITE_HOE,Map.of(Enchantment.EFFICIENCY,5,Enchantment.UNBREAKING,3,Enchantment.MENDING,1),List.of(Map.entry(Enchantment.FORTUNE,3),Map.entry(Enchantment.SILK_TOUCH,1)),"Netherite Hoe (Fortune III OR Silk Touch)");
    private static final Map<String,List<EnchantSpec>> DETAIL_GROUPS=Map.of(
        "FULL_GEAR",List.of(FULL_GEAR_HELMET,FULL_GEAR_CHEST,FULL_GEAR_LEGS,FULL_GEAR_BOOTS),
        "SWORD_BOW",List.of(SWORD_SPEC,BOW_SPEC),
        "TOOLS",List.of(PICKAXE_SPEC,AXE_SPEC,SHOVEL_SPEC),
        "HOE",List.of(HOE_SPEC)
    );
    private void openEquipmentDetail(Player player,String key){
        List<EnchantSpec> specs=DETAIL_GROUPS.get(key);if(specs==null)return;
        Inventory inventory=plugin.getServer().createInventory(new DetailHolder(key),9,Component.text("Minimum Requirements",NamedTextColor.DARK_GREEN));
        int slot=0;
        for(EnchantSpec spec:specs){
            boolean has=playerMeetsSpec(player,spec);
            List<String> lore=new ArrayList<>(List.of(has?"FULFILLED":"MISSING"));
            for(var entry:spec.required().entrySet())lore.add(CoreUtil.pretty(entry.getKey().getKey().getKey())+" "+roman(entry.getValue()));
            if(!spec.anyOf().isEmpty()){List<String> alt=new ArrayList<>();for(var entry:spec.anyOf())alt.add(CoreUtil.pretty(entry.getKey().getKey().getKey())+" "+roman(entry.getValue()));lore.add("Either: "+String.join(" OR ",alt));}
            inventory.setItem(slot++,icon(has?Material.LIME_DYE:spec.material(),(has?"✓ ":"✕ ")+spec.label(),lore));
        }
        inventory.setItem(8,icon(Material.ARROW,"Back",List.of("Return to /progress")));
        player.openInventory(inventory);
    }
    /** Mirrors the exact checks inspectMasterwork() uses to grant real credit — armor pieces must be worn in
     *  their slot, everything else just needs to exist anywhere in the main inventory — so this view never shows
     *  a checkmark that doesn't correspond to genuine mission progress. */
    private boolean playerMeetsSpec(Player player,EnchantSpec spec){
        if(spec.material()==Material.NETHERITE_HELMET)return meetsSpec(player.getInventory().getHelmet(),spec);
        if(spec.material()==Material.NETHERITE_CHESTPLATE)return meetsSpec(player.getInventory().getChestplate(),spec);
        if(spec.material()==Material.NETHERITE_LEGGINGS)return meetsSpec(player.getInventory().getLeggings(),spec);
        if(spec.material()==Material.NETHERITE_BOOTS)return meetsSpec(player.getInventory().getBoots(),spec);
        for(ItemStack item:player.getInventory().getContents())if(meetsSpec(item,spec))return true;
        return false;
    }
    private String roman(int value){return switch(value){case 1->"I";case 2->"II";case 3->"III";case 4->"IV";case 5->"V";default->Integer.toString(value);};}
    private boolean qualifyEquipment(Player player,List<ItemStack> items){
        String playerId=CoreUtil.id(player);
        for(ItemStack item:items){
            if(item==null)return false;
            ItemMeta meta=item.getItemMeta();String direct=meta.getPersistentDataContainer().get(directTransferKey,PersistentDataType.STRING);
            if(direct!=null&&!direct.equals(playerId))return false;
        }
        for(ItemStack item:items){
            ItemMeta meta=item.getItemMeta();String itemId=meta.getPersistentDataContainer().get(itemIdentityKey,PersistentDataType.STRING);
            if(itemId==null||itemId.isBlank()){itemId=UUID.randomUUID().toString();meta.getPersistentDataContainer().set(itemIdentityKey,PersistentDataType.STRING,itemId);}
            String direct=meta.getPersistentDataContainer().get(directTransferKey,PersistentDataType.STRING);
            if(!db.claimProgressionItem(itemId,playerId,direct==null||direct.equals(playerId)))return false;
            meta.getPersistentDataContainer().set(itemOwnerKey,PersistentDataType.STRING,playerId);
            if(playerId.equals(direct))meta.getPersistentDataContainer().remove(directTransferKey);
            item.setItemMeta(meta);
        }
        return true;
    }
    @EventHandler public void directDrop(PlayerDropItemEvent event){
        ItemStack item=event.getItemDrop().getItemStack();if(item==null||!item.hasItemMeta())return;ItemMeta meta=item.getItemMeta();String owner=meta.getPersistentDataContainer().get(itemOwnerKey,PersistentDataType.STRING);
        if(owner!=null&&owner.equals(CoreUtil.id(event.getPlayer()))){meta.getPersistentDataContainer().set(directTransferKey,PersistentDataType.STRING,owner);item.setItemMeta(meta);event.getItemDrop().setItemStack(item);}
    }
    @EventHandler public void progressionPickup(EntityPickupItemEvent event){
        if(!(event.getEntity() instanceof Player player))return;ItemStack item=event.getItem().getItemStack();if(item==null||!item.hasItemMeta())return;ItemMeta meta=item.getItemMeta();String direct=meta.getPersistentDataContainer().get(directTransferKey,PersistentDataType.STRING);
        if(direct!=null&&direct.equals(CoreUtil.id(player))){meta.getPersistentDataContainer().remove(directTransferKey);item.setItemMeta(meta);event.getItem().setItemStack(item);}
    }
    @EventHandler public void legitimateInventoryTransfer(InventoryClickEvent event){
        if(!(event.getWhoClicked() instanceof Player))return;Inventory top=event.getView().getTopInventory();if(top.getType()==InventoryType.PLAYER||top.getType()==InventoryType.CRAFTING||top.getHolder(false) instanceof Holder)return;
        int topSize=top.getSize(),raw=event.getRawSlot();
        if(raw<topSize){clearDirectTransfer(event.getCurrentItem());clearDirectTransfer(event.getCursor());}
        else if(event.isShiftClick())clearDirectTransfer(event.getCurrentItem());
    }
    @EventHandler public void legitimateInventoryDrag(InventoryDragEvent event){
        int top=event.getView().getTopInventory().getSize();if(event.getRawSlots().stream().anyMatch(slot->slot<top))clearDirectTransfer(event.getOldCursor());
    }
    void clearDirectTransfer(ItemStack item){if(item==null||!item.hasItemMeta())return;ItemMeta meta=item.getItemMeta();if(!meta.getPersistentDataContainer().has(directTransferKey))return;meta.getPersistentDataContainer().remove(directTransferKey);item.setItemMeta(meta);}
    void factionSpawnerPlaced(Database.FactionRow faction,String identity,EntityType type,long placedAt,Location location){for(String playerId:db.recordSpawnerPlacement(identity,faction.id(),type.name(),placedAt,location)){db.markMilestone(playerId,"VANGUARD_FACTION_SPAWNER");for(Player online:plugin.getServer().getOnlinePlayers())if(CoreUtil.id(online).equals(playerId)){CoreUtil.msg(online,"Vanguard requirement completed: faction "+CoreUtil.pretty(type.name())+" spawner.");refreshRanks(online,true);}}}
    boolean rankSelfTest(){return RANK_NAMES.size()==7&&Math.abs(maximumMultiplier()-5.25)<.001;}

    private void milestone(Player player,String key,String phrase,double fallbackReward,Material gift,boolean announce){
        String id=CoreUtil.id(player);if(!db.markMilestone(id,key))return;double reward=plugin.getConfig().getDouble("milestones.rewards."+key,fallbackReward);if(reward>0){plugin.creditEarned(id,reward,"MILESTONE_"+key);db.recordEconomy(id,"MILESTONE",reward,key);}if(gift!=null)CoreUtil.give(player,new ItemStack(gift));CoreUtil.msg(player,"Milestone: "+CoreUtil.pretty(key)+" • "+CoreUtil.money(reward));player.playSound(player.getLocation(),Sound.ENTITY_PLAYER_LEVELUP,.8f,1.15f);
        String text=player.getName()+" "+phrase+".";if(announce)plugin.getServer().broadcast(Component.text("✦ "+plugin.nicknames().displayName(player)+" "+phrase+".",NamedTextColor.GOLD));db.history("SERVER",null,"MILESTONE",text);Database.FactionRow faction=db.factionOf(id);if(faction!=null){db.incrementFactionStat(faction.id(),"milestones");db.history("FACTION",faction.id(),"MILESTONE",text);}refreshRanks(player,true);
    }

    void factionCreated(Database.FactionRow faction,String player){String text=player+" founded "+faction.name()+".";db.history("SERVER",null,"FACTION",text);db.history("FACTION",faction.id(),"CREATED",text);}
    /** Joining OR creating a faction both qualify for the very-first-rank "be part of any faction" mission. */
    void factionMembership(Player player){String id=CoreUtil.id(player);if(db.markMilestone(id,"WAYFARER_FACTION_MEMBER"))refreshRanks(player,true);}
    /** Historical, faction-scoped credit: once a faction has EVER claimed land / set a home, every CURRENT
     *  member gets the milestone permanently — including members who join later, since the requirement is
     *  "be part of a faction that has done this," not "personally perform the action." Works for offline
     *  members too (only the online ones get an immediate rank refresh/notification). */
    void factionLandMilestone(String playerId){if(!db.markMilestone(playerId,"WAYFARER_FACTION_LAND"))return;for(Player online:plugin.getServer().getOnlinePlayers())if(CoreUtil.id(online).equals(playerId)){refreshRanks(online,true);return;}}
    void factionHomeMilestone(String playerId){if(!db.markMilestone(playerId,"WAYFARER_FACTION_HOME"))return;for(Player online:plugin.getServer().getOnlinePlayers())if(CoreUtil.id(online).equals(playerId)){refreshRanks(online,true);return;}}
    void factionClaim(Database.FactionRow faction,int size){db.incrementFactionStat(faction.id(),"milestones");db.history("FACTION",faction.id(),"CLAIM",faction.name()+" claimed its first "+size+" × "+size+" territory.");}
    void factionExpanded(Database.FactionRow faction,int size){db.incrementFactionStat(faction.id(),"milestones");String text=faction.name()+" expanded its territory to "+size+" × "+size+".";db.history("FACTION",faction.id(),"EXPANSION",text);if(faction.tier()>=3)db.history("SERVER",null,"EXPANSION",text);}
    void factionDisbanded(Database.FactionRow faction){db.history("SERVER",null,"FACTION",faction.name()+" passed into history.");db.history("FACTION",faction.id(),"DISBANDED",faction.name()+" was disbanded.");}
    void bossKill(Player player,String bossName,boolean worldBoss,boolean miniboss){Database.FactionRow faction=db.factionOf(CoreUtil.id(player));String text=player.getName()+" defeated "+bossName+".";db.history("SERVER",null,"BOSS",text);if(faction!=null){db.incrementFactionStat(faction.id(),worldBoss?"boss_kills":"miniboss_kills");db.history("FACTION",faction.id(),"BOSS",text);}}
    void relicFound(Player player,String relic){Database.FactionRow faction=db.factionOf(CoreUtil.id(player));String text=player.getName()+" discovered "+relic+".";db.history("SERVER",null,"RELIC",text);if(faction!=null){db.incrementFactionStat(faction.id(),"relics");db.history("FACTION",faction.id(),"RELIC",text);}}
    void relicLost(String relic){db.history("SERVER",null,"RELIC",relic+" was lost to history.");}
    void bountyPlaced(Player player,String target,double amount){if(amount>=plugin.getConfig().getDouble("bounties.large-announcement",10000))db.history("SERVER",null,"BOUNTY",player.getName()+" placed a "+CoreUtil.money(amount)+" bounty on "+target+".");}
    void bountyClaimed(Player player,String target,double amount){Database.FactionRow faction=db.factionOf(CoreUtil.id(player));String text=player.getName()+" claimed the "+CoreUtil.money(amount)+" bounty on "+target+".";db.history("SERVER",null,"BOUNTY",text);if(faction!=null){db.incrementFactionStat(faction.id(),"bounties_claimed");db.history("FACTION",faction.id(),"BOUNTY",text);}}
    void eventWon(Player player,String event){Database.FactionRow faction=db.factionOf(CoreUtil.id(player));String text=player.getName()+" won "+event+".";db.history("SERVER",null,"EVENT",text);if(faction!=null){db.incrementFactionStat(faction.id(),"event_wins");db.history("FACTION",faction.id(),"EVENT",text);}refreshRanks(player,true);}
    private void grantAdvancement(Player player,String key){if(inArena(player))return;try{var advancement=Bukkit.getAdvancement(NamespacedKey.minecraft(key));if(advancement==null)return;var progress=player.getAdvancementProgress(advancement);for(String criterion:progress.getRemainingCriteria())progress.awardCriteria(criterion);}catch(Exception ignored){}}

    boolean stats(Player viewer,String name){Player visible=name==null?null:plugin.nicknames().findVisiblePlayer(name);Database.StatsRow row=name==null?db.stats(CoreUtil.id(viewer)):db.statsByName(visible==null?name:visible.getName());if(row==null){CoreUtil.error(viewer,"Player statistics were not found.");return true;}long hours=row.playSeconds()/3600,minutes=row.playSeconds()%3600/60;double kd=row.deaths()==0?row.playerKills():row.playerKills()/(double)row.deaths();CoreUtil.heading(viewer,CoreUtil.safe(plugin.nicknames().displayName(row.name())),"record");CoreUtil.field(viewer,"Playtime",hours+"h "+minutes+"m");CoreUtil.field(viewer,"Kills / deaths",row.playerKills()+" / "+row.deaths()+CoreUtil.C_MUTE+"  K/D "+String.format(Locale.US,"%.2f",kd));CoreUtil.field(viewer,"Mobs / bosses",row.mobKills()+" / "+row.bossKills());CoreUtil.field(viewer,"Events won",String.valueOf(row.eventWins()));CoreUtil.field(viewer,"Balance",CoreUtil.compactMoney(row.balance()));CoreUtil.field(viewer,"Shards",String.valueOf(plugin.db().shardBalance(CoreUtil.id(row.name()))));return true;}
    boolean history(Player player,boolean factionHistory,int page){Long factionId=null;if(factionHistory){Database.FactionRow faction=db.factionOf(CoreUtil.id(player));if(faction==null){CoreUtil.error(player,"You are not in a faction.");return true;}factionId=faction.id();}int safe=Math.max(1,page),size=7;List<Database.HistoryRow> rows=db.history(factionId,size,(safe-1)*size);CoreUtil.msg(player,(factionHistory?"Faction":"Server")+" chronicle • page "+safe);if(rows.isEmpty()){CoreUtil.msg(player,"No entries on this page.");return true;}for(Database.HistoryRow row:rows)player.sendMessage("§8"+DATE.format(Instant.ofEpochMilli(row.createdAt()))+" §7• §f"+row.message());return true;}
}
