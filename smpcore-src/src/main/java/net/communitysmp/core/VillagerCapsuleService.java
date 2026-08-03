package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

final class VillagerCapsuleService {
    private final SMPCore plugin;
    private final FactionService factions;
    private final SpawnClaimService spawnClaims;
    private final MerchantService merchants;
    private final NamespacedKey capsuleKey,dataKey,valueKey;

    VillagerCapsuleService(SMPCore plugin,FactionService factions,SpawnClaimService spawnClaims,MerchantService merchants){
        this.plugin=plugin;this.factions=factions;this.spawnClaims=spawnClaims;this.merchants=merchants;
        capsuleKey=new NamespacedKey(plugin,"villager_capsule");dataKey=new NamespacedKey(plugin,"villager_snapshot");valueKey=new NamespacedKey(plugin,"villager_asset_value");
    }

    /** Normal villagers are intentionally raidable/stealable, including inside another faction's protected
     *  claim — matching how they're already unprotected from being killed outright (combat() in
     *  GameplayListener never claim-checks generic LivingEntity damage, only Hanging/ArmorStand and Players).
     *  Only spawn protection and explicitly-protected NPCs (server merchants, spawn-claim "allowed" entities)
     *  stay uncapturable. */
    boolean capture(Player player,Entity target,EquipmentSlot hand){
        if(!(target instanceof Villager villager))return false;ItemStack held=player.getInventory().getItem(hand);String kind=kind(held);if(kind==null)return false;
        if(snapshot(held)!=null){CoreUtil.error(player,"This capsule already contains a villager.");return true;}
        if(merchants.isMerchant(villager)||spawnClaims.allowed(villager)){CoreUtil.error(player,"Permanent server merchants cannot be captured.");return true;}
        if(spawnClaims.contains(villager.getLocation())&&!plugin.isAdmin(player)){CoreUtil.error(player,"This villager is protected by spawn.");return true;}
        UUID targetId=villager.getUniqueId();
        plugin.confirmations().request(player,SettingsService.ConfirmationKind.LUXURY,true,"Capture this villager",List.of("The villager and all trades will enter the capsule."),()->{
            Entity current=plugin.getServer().getEntity(targetId);ItemStack currentItem=player.getInventory().getItem(hand);
            if(!(current instanceof Villager live)||!live.isValid()||kind(currentItem)==null||snapshot(currentItem)!=null||!player.getWorld().equals(live.getWorld())||player.getLocation().distanceSquared(live.getLocation())>36){CoreUtil.error(player,"The villager or capsule is no longer available.");return;}
            if(merchants.isMerchant(live)||spawnClaims.allowed(live)){CoreUtil.error(player,"Permanent server merchants cannot be captured.");return;}
            if(spawnClaims.contains(live.getLocation())&&!plugin.isAdmin(player)){CoreUtil.error(player,"That villager is now protected by spawn.");return;}
            captureNow(player,live,hand,currentItem,kind(currentItem));
        });
        return true;
    }

    /** Real per-hover Shift-key detection is impossible from a server-only plugin: tooltip rendering and
     *  hover/modifier-key state are 100% client-side and never reach the server (no packet exists for it —
     *  confirmed against the Minecraft protocol and corroborated by the Spigot plugin-dev community, e.g. the
     *  SpigotMC thread literally titled "is there any proper way to do 'hold shift for detail' (impossible to
     *  do)"). F3+H "advanced tooltips" is the only vanilla tooltip-verbosity toggle and it's a client-global
     *  debug setting, not per-item server-controllable and not tied to hover+Shift at all. So instead of a
     *  fake two-tier hover, the trade list is always appended below the basic info — the only way "viewable
     *  without holding, no clicks, no GUI" (auctions, trade previews, anywhere a tooltip renders) is actually
     *  achievable, and it keeps the same "basic summary, then full trade detail" structure the user wanted. */
    private List<Component> tradeLoreLines(Villager villager){
        List<Component> lines=new ArrayList<>();lines.add(Component.text("Trades (current prices):",NamedTextColor.GOLD));
        for(MerchantRecipe recipe:villager.getRecipes()){
            List<ItemStack> ingredients=recipe.getIngredients();
            String cost=ingredients.stream().filter(i->i!=null&&!i.getType().isAir()).map(i->i.getAmount()+" "+CoreUtil.pretty(i.getType().name())).collect(Collectors.joining(" + "));
            ItemStack result=recipe.getResult();String sold=result.getAmount()+" "+CoreUtil.pretty(result.getType().name());
            lines.add(Component.text("  "+cost+" → "+sold,NamedTextColor.GRAY));
        }
        return lines;
    }

    private void captureNow(Player player,Villager villager,EquipmentSlot hand,ItemStack held,String kind){
        try{
            EntitySnapshot saved=villager.createSnapshot();if(saved==null||saved.getEntityType()!=EntityType.VILLAGER){CoreUtil.error(player,"That villager could not be captured safely.");return;}
            ItemStack filled=held.clone();filled.setAmount(1);ItemMeta meta=filled.getItemMeta();meta.getPersistentDataContainer().set(dataKey,PersistentDataType.STRING,saved.getAsString());double assetValue=plugin.netWorth().villagerValue(villager);meta.getPersistentDataContainer().set(valueKey,PersistentDataType.DOUBLE,assetValue);
            String profession=CoreUtil.pretty(villager.getProfession().getKey().getKey());meta.displayName(Component.text(profession+" Villager Capsule",NamedTextColor.LIGHT_PURPLE));
            List<Component> lore=new ArrayList<>(List.of(Component.text("Level "+villager.getVillagerLevel()+" • "+("REUSABLE".equals(kind)?"Reusable":"Single use"),NamedTextColor.GRAY),Component.text("Right-click a block to release.",NamedTextColor.DARK_GRAY)));
            lore.addAll(tradeLoreLines(villager));meta.lore(lore);meta.setMaxStackSize(1);filled.setItemMeta(meta);
            player.getInventory().setItem(hand,filled);plugin.netWorth().entityRemoved(villager);villager.remove();player.playSound(player.getLocation(),Sound.BLOCK_VAULT_INSERT_ITEM,.8f,1.25f);CoreUtil.msg(player,"Villager captured.");
        }catch(Exception ex){plugin.getLogger().warning("Villager capture failed safely for "+player.getName()+": "+ex.getMessage());CoreUtil.error(player,"That villager could not be captured safely.");}
    }

    boolean place(Player player,ItemStack held,EquipmentSlot hand,Location location){
        String kind=kind(held),snapshot=snapshot(held);if(kind==null||snapshot==null)return false;
        if(location.getWorld()==null||!location.getBlock().isPassable()||!location.clone().add(0,1,0).getBlock().isPassable()){CoreUtil.error(player,"The villager needs two clear blocks.");return true;}
        if(spawnClaims.contains(location)&&!plugin.isAdmin(player)){CoreUtil.error(player,"Villager capsules cannot be opened in protected spawn.");return true;}
        FactionService.Claim claim=factions.claimAt(location);if(claim!=null&&!factions.isMember(player,claim.faction())){CoreUtil.error(player,"You cannot release a villager in "+claim.faction().name()+" territory.");return true;}
        String expected=snapshot;Location target=location.clone();
        plugin.confirmations().request(player,SettingsService.ConfirmationKind.LUXURY,true,"Release captured villager",List.of("The villager will be placed here."),()->{
            ItemStack current=player.getInventory().getItem(hand);if(current==null||!expected.equals(snapshot(current))){CoreUtil.error(player,"That capsule is no longer in your hand.");return;}
            if(!canReleaseAt(player,target))return;
            placeNow(player,current,hand,target,kind(current),expected);
        });
        return true;
    }

    private boolean canReleaseAt(Player player,Location location){
        if(location.getWorld()==null||!location.getBlock().isPassable()||!location.clone().add(0,1,0).getBlock().isPassable()){CoreUtil.error(player,"The villager needs two clear blocks.");return false;}
        if(spawnClaims.contains(location)&&!plugin.isAdmin(player)){CoreUtil.error(player,"Villager capsules cannot be opened in protected spawn.");return false;}
        FactionService.Claim claim=factions.claimAt(location);if(claim!=null&&!factions.isMember(player,claim.faction())){CoreUtil.error(player,"You cannot release a villager in "+claim.faction().name()+" territory.");return false;}
        return true;
    }

    private void placeNow(Player player,ItemStack held,EquipmentSlot hand,Location location,String kind,String snapshot){
        try{
            EntitySnapshot saved=Bukkit.getEntityFactory().createEntitySnapshot(snapshot);if(saved.getEntityType()!=EntityType.VILLAGER){CoreUtil.error(player,"This capsule contains invalid data.");return;}
            Entity created=saved.createEntity(location);if(!(created instanceof Villager villager)){if(created!=null)created.remove();CoreUtil.error(player,"The villager could not be released here.");return;}plugin.netWorth().villagerChanged(villager);
            if("REUSABLE".equals(kind))player.getInventory().setItem(hand,empty(true));else player.getInventory().setItem(hand,null);
            player.playSound(location,Sound.BLOCK_VAULT_EJECT_ITEM,.8f,1.2f);CoreUtil.msg(player,"Villager released.");
        }catch(Exception ex){plugin.getLogger().warning("Villager release failed without consuming the capsule for "+player.getName()+": "+ex.getMessage());CoreUtil.error(player,"The villager could not be released; the capsule was not consumed.");}
    }

    ItemStack empty(boolean reusable){
        Material material=reusable?Material.OMINOUS_TRIAL_KEY:Material.TRIAL_KEY;ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(reusable?"Reusable Villager Capsule":"Disposable Villager Capsule",NamedTextColor.LIGHT_PURPLE));meta.lore(List.of(Component.text(reusable?"Reusable villager transport.":"Captures one villager.",NamedTextColor.GRAY)));meta.getPersistentDataContainer().set(capsuleKey,PersistentDataType.STRING,reusable?"REUSABLE":"DISPOSABLE");meta.setMaxStackSize(1);item.setItemMeta(meta);return item;
    }

    boolean selfTest(){
        ItemStack single=empty(false),reusable=empty(true);if(!"DISPOSABLE".equals(kind(single))||!"REUSABLE".equals(kind(reusable))||single.getMaxStackSize()!=1||reusable.getMaxStackSize()!=1||snapshot(single)!=null)return false;
        World world=plugin.getServer().getWorlds().getFirst();Location base=world.getSpawnLocation();Location location=null;for(int i=0;i<12&&location==null;i++){Location candidate=CoreUtil.findSafe(world,base.getBlockX()+256+i*19,base.getBlockZ()+256+i*23);if(candidate!=null&&!spawnClaims.contains(candidate)&&factions.claimAt(candidate)==null)location=candidate;}if(location==null)return false;
        Villager original=null,restored=null;try{original=world.spawn(location,Villager.class,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,v->{v.setProfession(Villager.Profession.LIBRARIAN);v.setVillagerLevel(3);v.setVillagerExperience(80);v.customName(Component.text("Capsule Test"));MerchantRecipe recipe=new MerchantRecipe(new ItemStack(Material.BOOK),5);recipe.setIngredients(List.of(new ItemStack(Material.EMERALD,4)));recipe.setUses(2);v.setRecipes(List.of(recipe));});EntitySnapshot saved=original.createSnapshot();original.remove();original=null;Entity entity=saved.createEntity(location);if(!(entity instanceof Villager villager)){if(entity!=null)entity.remove();return false;}restored=villager;return villager.getProfession()==Villager.Profession.LIBRARIAN&&villager.getVillagerLevel()==3&&villager.getVillagerExperience()==80&&villager.getRecipes().size()==1&&villager.getRecipes().getFirst().getUses()==2;}catch(Exception ex){plugin.getLogger().warning("Villager snapshot self-test failed: "+ex.getMessage());return false;}finally{if(original!=null)original.remove();if(restored!=null)restored.remove();}
    }
    private String kind(ItemStack item){if(item==null||item.getType().isAir()||!item.hasItemMeta())return null;String value=item.getItemMeta().getPersistentDataContainer().get(capsuleKey,PersistentDataType.STRING);return value==null?null:value.toUpperCase(Locale.ROOT);}
    private String snapshot(ItemStack item){return item==null||!item.hasItemMeta()?null:item.getItemMeta().getPersistentDataContainer().get(dataKey,PersistentDataType.STRING);}
    double capturedValue(ItemStack item){if(snapshot(item)==null)return 0;Double value=item.getItemMeta().getPersistentDataContainer().get(valueKey,PersistentDataType.DOUBLE);return value==null?plugin.getConfig().getDouble("net-worth.villagers.legacy-capsule-value",2000):Math.max(0,value);}
}
