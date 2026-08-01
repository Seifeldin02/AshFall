package net.communitysmp.core;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.util.*;

final class SettingsService implements Listener {
    enum ConfirmationKind {
        SHOP("confirm_shop"), AUCTION("confirm_auction"), LUXURY("confirm_luxury"), SHARD("confirm_shard");
        final String key;
        ConfirmationKind(String key){this.key=key;}
    }
    private enum Page { MAIN, CONFIRMATIONS }
    private record Holder(Page page) implements InventoryHolder {@Override public Inventory getInventory(){return null;}}
    private record Toggle(String key,String title,Material icon,boolean fallback) {}

    private static final List<Toggle> MAIN=List.of(
            new Toggle("natural_spawns","Hostile Mobs",Material.ZOMBIE_HEAD,true),
            new Toggle("night_vision","Night Vision",Material.ENDER_EYE,false),
            new Toggle("sidebar","Sidebar",Material.MAP,true),
            new Toggle("event_tracking","Event Tracker",Material.COMPASS,true),
            new Toggle("grave_tracking","Grave Tracker",Material.RECOVERY_COMPASS,true),
            new Toggle("boss_notifications","Boss Alerts",Material.BELL,true),
            new Toggle("private_messages","Private Messages",Material.WRITABLE_BOOK,true),
            new Toggle("tpa_requests","TPA Requests",Material.ENDER_PEARL,true),
            new Toggle("auction_notifications","Auction Alerts",Material.CHEST,true),
            new Toggle("sound_notifications","Sounds",Material.NOTE_BLOCK,true)
    );
    private final SMPCore plugin;
    private final Database db;
    private final Set<UUID> appliedNightVision=new HashSet<>();
    private final Map<UUID,Long> hostileDamageAt=new HashMap<>();
    private BukkitTask visionTask;
    private BukkitTask peacefulTask;

    SettingsService(SMPCore plugin){
        this.plugin=plugin;db=plugin.db();
        visionTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::nightVisionTick,20L,100L);
        peacefulTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::peacefulTick,40L,100L);
    }

    void shutdown(){if(visionTask!=null)visionTask.cancel();if(peacefulTask!=null)peacefulTask.cancel();}

    boolean command(Player player,String[] args){
        if(args.length>0&&args[0].equalsIgnoreCase("native")){
            if(locked(player,true))return true;
            if(!nativeDialogsSupported(player)){openChest(player,Page.MAIN);return true;}
            if(args.length>1&&args[1].equalsIgnoreCase("confirmations"))openNative(player,Page.CONFIRMATIONS);
            else if(args.length>1&&args[1].equalsIgnoreCase("account"))plugin.account().openNative(player,args.length>2?args[2]:"");
            else openNative(player,Page.MAIN);
            return true;
        }
        if(args.length>0&&args[0].equalsIgnoreCase("account")){if(!locked(player,true))plugin.account().open(player);return true;}
        if(locked(player,true))return true;
        if(args.length>0&&args[0].equalsIgnoreCase("chest")){openChest(player,Page.MAIN);return true;}
        if(args.length>0&&args[0].equalsIgnoreCase("confirmations")){open(player,Page.CONFIRMATIONS);return true;}
        if(args.length>2&&args[0].equalsIgnoreCase("set")){
            String key=args[1].toLowerCase(Locale.ROOT),value=args[2].toLowerCase(Locale.ROOT);
            if(!validKey(key)||!Set.of("on","off").contains(value)){CoreUtil.error(player,"Unknown setting selection.");return true;}
            set(player,key,value.equals("on"));
            if(args.length>3&&args[3].equalsIgnoreCase("quick"))return true;
            if(args.length>3&&args[3].equalsIgnoreCase("native")&&nativeDialogsSupported(player)){
                openNative(player,key.startsWith("confirm_")?Page.CONFIRMATIONS:Page.MAIN);
            }else open(player,key.startsWith("confirm_")?Page.CONFIRMATIONS:Page.MAIN);
            return true;
        }
        if(args.length>1&&args[0].equalsIgnoreCase("toggle")){
            String key=args[1].toLowerCase(Locale.ROOT);
            if("particles".equals(key))cycleParticles(player);else if("confirm_all".equals(key))setAllConfirmations(player,!allConfirmations(player));else if(validKey(key))set(player,key,!enabled(player,key,defaultFor(key)));
            if(args.length>2&&args[2].equalsIgnoreCase("quick"))return true;
            if(args.length>2&&args[2].equalsIgnoreCase("native")&&nativeDialogsSupported(player)){
                openNative(player,key.startsWith("confirm_")?Page.CONFIRMATIONS:Page.MAIN);
            }else open(player,key.startsWith("confirm_")?Page.CONFIRMATIONS:Page.MAIN);
            return true;
        }
        open(player,Page.MAIN);return true;
    }

    void open(Player player){open(player,Page.MAIN);}
    void openChestRoot(Player player){if(!locked(player,true))openChest(player,Page.MAIN);}
    void openBedrockRoot(Player player){if(!locked(player,true))openBedrock(player,Page.MAIN);}
    private void open(Player player,Page page){
        if(locked(player,true))return;
        if(plugin.isBedrock(player)&&openBedrock(player,page))return;
        openChest(player,page);
    }

    private boolean nativeDialogsSupported(Player player){
        return plugin.getConfig().getBoolean("settings.native-dialogs",true)
                &&player.getProtocolVersion()==Bukkit.getUnsafe().getProtocolVersion();
    }

    @SuppressWarnings("UnstableApiUsage")
    private void openNative(Player player,Page page){
        try{
            List<ActionButton> buttons=new ArrayList<>();
            if(page==Page.MAIN){
                for(Toggle toggle:MAIN)buttons.add(nativeToggle(player,toggle,Page.MAIN));
                buttons.add(nativeCycle(player));
                buttons.add(nativeButton("Purchase Confirmations","settings native confirmations"));
                buttons.add(nativeButton("Account","settings native account"));
                buttons.add(nativeButton("Random Travel","rtp"));
                buttons.add(nativeButton(plugin.teleports().isQueuedForRtp(player)?"Leave RTP Queue":"Join RTP Queue","rtp queue"));
            }else{
                buttons.add(nativeConfirmationToggle(player,null));
                for(ConfirmationKind kind:ConfirmationKind.values())buttons.add(nativeConfirmationToggle(player,kind));
                buttons.add(nativeButton("Back","settings native"));
            }
            Dialog dialog=Dialog.create(builder->builder.empty()
                    .base(DialogBase.builder(Component.text(page==Page.MAIN?"ASHEN SETTINGS":"PURCHASE CONFIRMATIONS",NamedTextColor.GOLD)).canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.NONE).build())
                    .type(DialogType.multiAction(buttons,null,2)));
            player.showDialog(dialog);
        }catch(Throwable error){
            plugin.getLogger().warning("Native settings dialog was unavailable for "+player.getName()+": "+error.getClass().getSimpleName());
            openChest(player,page);
        }
    }

    private ActionButton nativeButton(String label,String command){
        return ActionButton.create(Component.text(label),Component.empty(),150,DialogAction.staticAction(ClickEvent.runCommand("/"+command)));
    }
    private Component stateLabel(String label,boolean enabled){
        return Component.text(label+": ",NamedTextColor.WHITE).append(Component.text(enabled?"ON":"OFF",enabled?NamedTextColor.GREEN:NamedTextColor.RED));
    }
    private ActionButton nativeToggle(Player player,Toggle toggle,Page page){
        boolean current=enabled(player,toggle.key(),toggle.fallback());
        return ActionButton.create(stateLabel(toggle.title(),current),Component.empty(),150,DialogAction.customClick((response,audience)->
                plugin.getServer().getScheduler().runTask(plugin,()->{
                    Player online=plugin.getServer().getPlayer(player.getUniqueId());if(online==null||locked(online,true))return;
                    set(online,toggle.key(),!enabled(online,toggle.key(),toggle.fallback()));openNative(online,page);
                }),ClickCallback.Options.builder().uses(100).build()));
    }
    private ActionButton nativeConfirmationToggle(Player player,ConfirmationKind kind){
        boolean current=kind==null?allConfirmations(player):confirmationEnabled(player,kind);
        String label=kind==null?"All Confirmations":prettyConfirmation(kind);
        return ActionButton.create(stateLabel(label,current),Component.empty(),150,DialogAction.customClick((response,audience)->
                plugin.getServer().getScheduler().runTask(plugin,()->{
                    Player online=plugin.getServer().getPlayer(player.getUniqueId());if(online==null||locked(online,true))return;
                    if(kind==null)setAllConfirmations(online,!allConfirmations(online));else set(online,kind.key,!confirmationEnabled(online,kind));
                    openNative(online,Page.CONFIRMATIONS);
                }),ClickCallback.Options.builder().uses(100).build()));
    }
    private ActionButton nativeCycle(Player player){
        return ActionButton.create(Component.text("Particles: ",NamedTextColor.WHITE).append(Component.text(CoreUtil.pretty(particles(player)),NamedTextColor.AQUA)),Component.empty(),150,
                DialogAction.customClick((response,audience)->plugin.getServer().getScheduler().runTask(plugin,()->{
                    Player online=plugin.getServer().getPlayer(player.getUniqueId());if(online==null||locked(online,true))return;
                    cycleParticles(online);openNative(online,Page.MAIN);
                }),ClickCallback.Options.builder().uses(100).build()));
    }

    private boolean openBedrock(Player player,Page page){
        try{
            GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());if(connection==null)return false;
            SimpleForm.Builder form=SimpleForm.builder().title(page==Page.MAIN?"ASHEN SETTINGS":"PURCHASE CONFIRMATIONS");
            if(page==Page.MAIN){
                for(Toggle toggle:MAIN)form.button(toggle.title()+"\n"+(enabled(player,toggle.key(),toggle.fallback())?"§aON":"§cOFF"));
                form.button("Particle Intensity\n§e"+CoreUtil.pretty(particles(player)));
                form.button("Purchase Confirmations");
                form.button("Random Travel");
                form.button(plugin.teleports().isQueuedForRtp(player)?"RTP Queue\n§aQUEUED — tap to leave":"RTP Queue\n§7Tap to join");
                form.button("Cosmetics");
                form.button("Account");
            }else{
                form.button("All Routine Confirmations\n"+(allConfirmations(player)?"§aON":"§cOFF"));
                for(ConfirmationKind kind:ConfirmationKind.values())form.button(prettyConfirmation(kind)+"\n"+(confirmationEnabled(player,kind)?"§aON":"§cOFF"));
                form.button("Back");
            }
            form.validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->{
                if(locked(player,true))return;int clicked=response.clickedButtonId();
                if(page==Page.MAIN){
                    if(clicked>=0&&clicked<MAIN.size()){Toggle toggle=MAIN.get(clicked);set(player,toggle.key(),!enabled(player,toggle.key(),toggle.fallback()));openBedrock(player,Page.MAIN);}
                    else if(clicked==MAIN.size()){cycleParticles(player);openBedrock(player,Page.MAIN);}
                    else if(clicked==MAIN.size()+1)openBedrock(player,Page.CONFIRMATIONS);
                    else if(clicked==MAIN.size()+2)plugin.teleports().rtp(player);
                    else if(clicked==MAIN.size()+3){plugin.teleports().toggleRtpQueue(player);openBedrock(player,Page.MAIN);}
                    else if(clicked==MAIN.size()+4)plugin.shards().openCosmetics(player);
                    else if(clicked==MAIN.size()+5)plugin.account().open(player);
                }else{
                    if(clicked==0){setAllConfirmations(player,!allConfirmations(player));openBedrock(player,Page.CONFIRMATIONS);}
                    else if(clicked>0&&clicked<=ConfirmationKind.values().length){ConfirmationKind kind=ConfirmationKind.values()[clicked-1];set(player,kind.key,!confirmationEnabled(player,kind));openBedrock(player,Page.CONFIRMATIONS);}
                    else openBedrock(player,Page.MAIN);
                }
            }));
            return connection.sendForm(form);
        }catch(Throwable error){return false;}
    }

    private void openChest(Player player,Page page){
        Inventory inv=plugin.getServer().createInventory(new Holder(page),54,Component.text(page==Page.MAIN?"ASHEN SETTINGS":"PURCHASE CONFIRMATIONS",NamedTextColor.DARK_GRAY));
        renderChest(inv,player,page);
        player.openInventory(inv);
    }
    private void renderChest(Inventory inv,Player player,Page page){
        inv.clear();
        if(page==Page.MAIN){
            int[] slots={10,11,12,13,14,15,16,19,20,21};for(int i=0;i<MAIN.size();i++){Toggle toggle=MAIN.get(i);inv.setItem(slots[i],toggle(toggle.icon(),toggle.title(),enabled(player,toggle.key(),toggle.fallback())));}
            inv.setItem(23,cycle(Material.FIREWORK_STAR,"Particle Intensity",particles(player)));
            inv.setItem(31,button(Material.REPEATER,"Purchase Confirmations",List.of("Configure each marketplace section.")));
            inv.setItem(39,button(Material.ENDER_PEARL,"Random Travel",List.of("Travel safely in your current dimension.")));
            boolean queued=plugin.teleports().isQueuedForRtp(player);
            inv.setItem(40,button(queued?Material.LIME_DYE:Material.COMPASS,"RTP Queue",List.of(queued?"§aQueued — click to leave.":"Click to join the queue.","Pairs you with another queued player","in the same dimension.")));
            inv.setItem(41,button(Material.PLAYER_HEAD,"Cosmetics",List.of("Choose an unlocked Shard cosmetic.")));
            inv.setItem(49,button(Material.NAME_TAG,"Account",List.of("Password and registration settings.")));
        }else{
            inv.setItem(13,toggle(Material.REPEATER,"All Routine Confirmations",allConfirmations(player)));
            int slot=19;for(ConfirmationKind kind:ConfirmationKind.values()){inv.setItem(slot++,stateBlock(prettyConfirmation(kind),confirmationEnabled(player,kind)));}
            inv.setItem(49,button(Material.ARROW,"Back",List.of()));
        }
    }

    @EventHandler public void click(InventoryClickEvent event){
        if(!(event.getInventory().getHolder(false) instanceof Holder holder))return;event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player))return;
        if(locked(player,true))return;
        int slot=event.getRawSlot();
        if(holder.page==Page.MAIN){
            int[] slots={10,11,12,13,14,15,16,19,20,21};for(int i=0;i<slots.length;i++)if(slot==slots[i]){Toggle toggle=MAIN.get(i);set(player,toggle.key(),!enabled(player,toggle.key(),toggle.fallback()));renderChest(event.getInventory(),player,Page.MAIN);return;}
            if(slot==23){cycleParticles(player);renderChest(event.getInventory(),player,Page.MAIN);}
            else if(slot==31)openChest(player,Page.CONFIRMATIONS);
            else if(slot==39){player.closeInventory();plugin.teleports().rtp(player);}
            else if(slot==40){plugin.teleports().toggleRtpQueue(player);renderChest(event.getInventory(),player,Page.MAIN);}
            else if(slot==41)plugin.shards().openCosmetics(player);
            else if(slot==49)plugin.account().open(player);
        }else{
            if(slot==13){setAllConfirmations(player,!allConfirmations(player));renderChest(event.getInventory(),player,Page.CONFIRMATIONS);}
            else if(slot>=19&&slot<19+ConfirmationKind.values().length){ConfirmationKind kind=ConfirmationKind.values()[slot-19];set(player,kind.key,!confirmationEnabled(player,kind));renderChest(event.getInventory(),player,Page.CONFIRMATIONS);}
            else if(slot==49)openChest(player,Page.MAIN);
        }
    }

    boolean enabled(Player player,String key,boolean fallback){
        String value=db.preference(CoreUtil.id(player),key);return value==null?fallback:Boolean.parseBoolean(value);
    }
    boolean confirmationEnabled(Player player,ConfirmationKind kind){return enabled(player,kind.key,kind!=ConfirmationKind.SHOP);}
    private boolean allConfirmations(Player player){return Arrays.stream(ConfirmationKind.values()).allMatch(kind->confirmationEnabled(player,kind));}
    private void setAllConfirmations(Player player,boolean enabled){for(ConfirmationKind kind:ConfirmationKind.values())db.preference(CoreUtil.id(player),kind.key,Boolean.toString(enabled));CoreUtil.msg(player,"Routine Purchase Confirmations "+(enabled?"enabled":"disabled")+". Mandatory confirmations remain on.");}
    boolean naturalSpawns(Player player){return enabled(player,"natural_spawns",true);}
    boolean graveTracking(Player player){return enabled(player,"grave_tracking",true);}
    boolean bossNotifications(Player player){return enabled(player,"boss_notifications",true);}
    boolean privateMessages(Player player){return enabled(player,"private_messages",true);}
    boolean tpaRequests(Player player){return enabled(player,"tpa_requests",true);}
    boolean auctionNotifications(Player player){return enabled(player,"auction_notifications",true);}
    boolean sounds(Player player){return enabled(player,"sound_notifications",true);}
    void hostileDamage(Player player){hostileDamageAt.put(player.getUniqueId(),System.currentTimeMillis());}
    long settingsLockRemaining(Player player){
        long pve=Math.max(0,10_000-(System.currentTimeMillis()-hostileDamageAt.getOrDefault(player.getUniqueId(),0L))),pvp=plugin.teleports().combatRemaining(player)*1000L;
        return Math.max(pve,pvp)<=0?0:Math.max(1,(Math.max(pve,pvp)+999)/1000);
    }
    private boolean locked(Player player,boolean message){if(plugin.privileged(player))return false;long remaining=settingsLockRemaining(player);if(remaining<=0)return false;if(message)CoreUtil.error(player,"Settings are locked during combat. "+remaining+"s remaining.");return true;}
    void marketSound(Player player,String action){
        if(player==null||!sounds(player))return;String path="marketplace.sounds."+action;String fallback=switch(action){case"purchase"->"ENTITY_EXPERIENCE_ORB_PICKUP";case"sale"->"BLOCK_NOTE_BLOCK_CHIME";case"failed"->"BLOCK_NOTE_BLOCK_BASS";case"confirm"->"UI_BUTTON_CLICK";case"cancel"->"UI_BUTTON_CLICK";case"shard"->"BLOCK_AMETHYST_BLOCK_RESONATE";default->"UI_BUTTON_CLICK";};
        try{Sound sound=Sound.valueOf(plugin.getConfig().getString(path,fallback).toUpperCase(Locale.ROOT));float pitch=action.equals("failed")?.65f:action.equals("cancel")?.85f:action.equals("sale")?1.2f:1.05f;player.playSound(player.getLocation(),sound,.55f,pitch);}catch(IllegalArgumentException ignored){}
    }
    String particles(Player player){String value=db.preference(CoreUtil.id(player),"particle_intensity");return value!=null&&Set.of("FULL","REDUCED","MINIMAL").contains(value)?value:"FULL";}
    double particleScale(Player player){return switch(particles(player)){case"REDUCED"->.45;case"MINIMAL"->.15;default->1;};}

    private void set(Player player,String key,boolean enabled){
        db.preference(CoreUtil.id(player),key,Boolean.toString(enabled));
        if("sidebar".equals(key)){if(enabled)plugin.ui().update(player);else plugin.ui().removeSidebar(player);}
        if("night_vision".equals(key)){if(enabled)applyNightVision(player);else removeNightVision(player);}
        if("natural_spawns".equals(key)&&!enabled)peacefulFor(player);
        CoreUtil.msg(player,displayKey(key)+" "+(enabled?"enabled":"disabled")+".");
    }
    private void cycleParticles(Player player){
        String next=switch(particles(player)){case"FULL"->"REDUCED";case"REDUCED"->"MINIMAL";default->"FULL";};
        db.preference(CoreUtil.id(player),"particle_intensity",next);CoreUtil.msg(player,"Particle Intensity: "+CoreUtil.pretty(next)+".");
    }
    private boolean validKey(String key){return MAIN.stream().anyMatch(toggle->toggle.key().equals(key))||Arrays.stream(ConfirmationKind.values()).anyMatch(kind->kind.key.equals(key));}
    private boolean defaultFor(String key){if(ConfirmationKind.SHOP.key.equals(key))return false;return MAIN.stream().filter(toggle->toggle.key().equals(key)).map(Toggle::fallback).findFirst().orElse(true);}
    private String state(Player player,String key,boolean fallback){return enabled(player,key,fallback)?"ON":"OFF";}
    private String displayKey(String key){return MAIN.stream().filter(toggle->toggle.key().equals(key)).map(Toggle::title).findFirst().orElse(key.startsWith("confirm_")?CoreUtil.pretty(key.substring(8))+" confirmations":CoreUtil.pretty(key));}
    private String prettyConfirmation(ConfirmationKind kind){return switch(kind){case SHOP->"Regular Shop";case AUCTION->"Auction House";case LUXURY->"Luxury Shop";case SHARD->"Shard Shop";};}

    private void nightVisionTick(){
        for(Player player:plugin.getServer().getOnlinePlayers()){
            boolean requested=enabled(player,"night_vision",false),darkness=player.hasPotionEffect(PotionEffectType.DARKNESS)||player.hasPotionEffect(PotionEffectType.BLINDNESS);
            if(requested&&!darkness)applyNightVision(player);
            else if(appliedNightVision.contains(player.getUniqueId()))removeNightVision(player);
        }
        appliedNightVision.removeIf(id->plugin.getServer().getPlayer(id)==null);
    }
    private void applyNightVision(Player player){
        if(player.hasPotionEffect(PotionEffectType.DARKNESS)||player.hasPotionEffect(PotionEffectType.BLINDNESS))return;
        PotionEffect current=player.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if(current==null||current.getDuration()!=PotionEffect.INFINITE_DURATION||!appliedNightVision.contains(player.getUniqueId()))player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,PotionEffect.INFINITE_DURATION,0,false,false,false));
        appliedNightVision.add(player.getUniqueId());
    }
    private void removeNightVision(Player player){if(appliedNightVision.remove(player.getUniqueId()))player.removePotionEffect(PotionEffectType.NIGHT_VISION);}
    private void peacefulTick(){
        List<Player> disabled=new ArrayList<>();List<Location> enabledLocations=new ArrayList<>();
        for(Player player:plugin.getServer().getOnlinePlayers())if(naturalSpawns(player))enabledLocations.add(player.getLocation());else disabled.add(player);
        if(disabled.isEmpty())return;
        double radius=plugin.getConfig().getDouble("settings.natural-spawn-influence-radius",128),radiusSq=radius*radius;
        for(Player player:disabled)peacefulFor(player,enabledLocations,radius,radiusSq);
    }
    private void peacefulFor(Player player){
        double radius=plugin.getConfig().getDouble("settings.natural-spawn-influence-radius",128);
        List<Location> enabledLocations=new ArrayList<>();
        for(Player other:plugin.getServer().getOnlinePlayers())if(!other.getUniqueId().equals(player.getUniqueId())&&naturalSpawns(other))enabledLocations.add(other.getLocation());
        peacefulFor(player,enabledLocations,radius,radius*radius);
    }
    /** Evaluated per hostile mob rather than per toggling-off player: a mob is only removed if no currently
     *  online Hostile-Mobs-ON player is within range of that specific mob's own location. A mob's position can
     *  drift from whichever player "owns" the area it's in (trapped in a pen, a boat, etc.), so checking
     *  proximity to the OFF player's position alone isn't enough to know whether the mob sits in someone
     *  else's active space — this keeps it consistent with the spawn-prevention check, which already works
     *  this way (per spawn location, not per nearby player). */
    private void peacefulFor(Player player,List<Location> enabledLocations,double radius,double radiusSq){
        for(org.bukkit.entity.Entity entity:player.getNearbyEntities(radius,radius,radius)){
            if(!(entity instanceof Enemy enemy)||!removableHostile(enemy))continue;
            Location mobLocation=entity.getLocation();
            boolean protectedByNearbyOnPlayer=enabledLocations.stream().anyMatch(loc->loc.getWorld().equals(mobLocation.getWorld())&&loc.distanceSquared(mobLocation)<=radiusSq);
            if(!protectedByNearbyOnPlayer)enemy.remove();
        }
    }
    private boolean removableHostile(Enemy enemy){
        if(!(enemy instanceof org.bukkit.entity.LivingEntity living)||plugin.bosses().isPeacefulExempt(living))return false;
        if(plugin.bosses().isOrdinaryElite(living))return true;
        if(enemy instanceof Tameable tame&&tame.isTamed())return false;
        if(living.customName()!=null)return false;
        return true;
    }
    boolean selfTest(){return MAIN.size()>=10&&ConfirmationKind.values().length==4&&!defaultFor(ConfirmationKind.SHOP.key)&&particleScaleFor("FULL")==1&&particleScaleFor("MINIMAL")<particleScaleFor("REDUCED");}
    private double particleScaleFor(String value){return switch(value){case"REDUCED"->.45;case"MINIMAL"->.15;default->1;};}

    private ItemStack toggle(Material icon,String title,boolean enabled){ItemStack item=new ItemStack(icon);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(title,NamedTextColor.GOLD));meta.lore(List.of(Component.text(enabled?"ON":"OFF",enabled?NamedTextColor.GREEN:NamedTextColor.RED),Component.text("Click to change.",NamedTextColor.DARK_GRAY)));item.setItemMeta(meta);return item;}
    private ItemStack stateBlock(String title,boolean enabled){return toggle(enabled?Material.LIME_CONCRETE:Material.RED_CONCRETE,title,enabled);}
    private ItemStack cycle(Material icon,String title,String value){return button(icon,title,List.of(value,"Click to change."));}
    private ItemStack button(Material material,String title,List<String> lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(title,NamedTextColor.GOLD));meta.lore(lore.stream().map(line->Component.text(line,NamedTextColor.GRAY)).toList());item.setItemMeta(meta);return item;}
}
