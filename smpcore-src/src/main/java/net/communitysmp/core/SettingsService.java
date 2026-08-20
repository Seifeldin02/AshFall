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
        /** Confirmations are on for everything that spends a lot or is hard to undo, and off for the
         *  regular shop, where the amounts are small and a prompt on every purchase is just friction. */
        SHOP("confirm_shop",false), AUCTION("confirm_auction",true), LUXURY("confirm_luxury",true), SHARD("confirm_shard",true);
        final String key;final boolean fallback;
        ConfirmationKind(String key,boolean fallback){this.key=key;this.fallback=fallback;}
    }
    /** OTHER keeps the original "tpa_requests" key so nobody's existing preference silently resets when this
     *  splits into three settings — it now scopes to non-faction requesters only (see TeleportService).
     *  FACTION defaults ON so a player who never touched TPA settings keeps today's behavior (faction-mates
     *  could always request before this split existed). AUTO_ACCEPT defaults OFF (opt-in) and, per spec, can
     *  only ever take effect while FACTION is also ON — enforced in set(), not just in the UI. */
    enum TpaKind {
        OTHER("tpa_requests",true), FACTION("tpa_requests_faction",true), AUTO_ACCEPT("tpa_auto_accept_faction",false);
        final String key;final boolean fallback;
        TpaKind(String key,boolean fallback){this.key=key;this.fallback=fallback;}
    }
    /** Viewer-side nametag extras. Independent on purpose: a player may want balances, faction tags,
     *  both, or neither, and the choice only affects what THEY see above other players. */
    enum NametagKind {
        /** Balance is the only one ON by default. Hearts default OFF deliberately: with every toggle off we
         *  send no packet at all, so the player keeps the ordinary vanilla nametag which already shows the
         *  below-name health line -- the hearts toggle only matters once our replacement tag is in play. */
        BALANCES("nametag_balances",true), FACTIONS("nametag_factions",false), HEARTS("nametag_hearts",false);
        final String key;final boolean fallback;
        NametagKind(String key,boolean fallback){this.key=key;this.fallback=fallback;}
    }
    private enum Page { MAIN, CONFIRMATIONS, TPA, NAMETAGS }
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
            new Toggle("auction_notifications","Auction Alerts",Material.CHEST,true),
            new Toggle("sound_notifications","Sounds",Material.NOTE_BLOCK,true),
            new Toggle("elite_mobs","Elite Mobs",Material.WITHER_SKELETON_SKULL,true)
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
            else if(args.length>1&&args[1].equalsIgnoreCase("tpa"))openNative(player,Page.TPA);
            else if(args.length>1&&args[1].equalsIgnoreCase("nametags"))openNative(player,Page.NAMETAGS);
            else if(args.length>1&&args[1].equalsIgnoreCase("account"))plugin.account().openNative(player,args.length>2?args[2]:"");
            else openNative(player,Page.MAIN);
            return true;
        }
        if(args.length>0&&args[0].equalsIgnoreCase("account")){if(!locked(player,true))plugin.account().open(player);return true;}
        if(locked(player,true))return true;
        if(args.length>0&&args[0].equalsIgnoreCase("chest")){openChest(player,Page.MAIN);return true;}
        if(args.length>0&&args[0].equalsIgnoreCase("confirmations")){open(player,Page.CONFIRMATIONS);return true;}
        if(args.length>0&&args[0].equalsIgnoreCase("tpa")){open(player,Page.TPA);return true;}
        if(args.length>0&&args[0].equalsIgnoreCase("nametags")){open(player,Page.NAMETAGS);return true;}
        if(args.length>2&&args[0].equalsIgnoreCase("set")){
            String key=args[1].toLowerCase(Locale.ROOT),value=args[2].toLowerCase(Locale.ROOT);
            if(!validKey(key)||!Set.of("on","off").contains(value)){CoreUtil.error(player,"Unknown setting selection.");return true;}
            set(player,key,value.equals("on"));
            if(args.length>3&&args[3].equalsIgnoreCase("quick"))return true;
            if(args.length>3&&args[3].equalsIgnoreCase("native")&&nativeDialogsSupported(player)){
                openNative(player,pageFor(key));
            }else open(player,pageFor(key));
            return true;
        }
        if(args.length>1&&args[0].equalsIgnoreCase("toggle")){
            String key=args[1].toLowerCase(Locale.ROOT);
            if("particles".equals(key))cycleParticles(player);else if("confirm_all".equals(key))setAllConfirmations(player,!allConfirmations(player));else if(validKey(key))set(player,key,!enabled(player,key,defaultFor(key)));
            if(args.length>2&&args[2].equalsIgnoreCase("quick"))return true;
            if(args.length>2&&args[2].equalsIgnoreCase("native")&&nativeDialogsSupported(player)){
                openNative(player,pageFor(key));
            }else open(player,pageFor(key));
            return true;
        }
        open(player,Page.MAIN);return true;
    }
    private Page pageFor(String key){if(key.startsWith("confirm_"))return Page.CONFIRMATIONS;if(Arrays.stream(TpaKind.values()).anyMatch(kind->kind.key.equals(key)))return Page.TPA;if(Arrays.stream(NametagKind.values()).anyMatch(kind->kind.key.equals(key)))return Page.NAMETAGS;return Page.MAIN;}

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
                for(Toggle toggle:mainToggles(player))buttons.add(nativeToggle(player,toggle,Page.MAIN));
                buttons.add(nativeCycle(player));
                buttons.add(nativeButton("Purchase Confirmations","settings native confirmations"));
                buttons.add(nativeButton("TPA Requests","settings native tpa"));
                buttons.add(nativeButton("Nametags","settings native nametags"));
                buttons.add(nativeButton("Account","settings native account"));
                buttons.add(nativeButton("Random Travel","rtp"));
                buttons.add(nativeButton(plugin.teleports().isQueuedForRtp(player)?"Leave RTP Queue":"Join RTP Queue","rtp queue"));
            }else if(page==Page.CONFIRMATIONS){
                buttons.add(nativeConfirmationToggle(player,null));
                for(ConfirmationKind kind:ConfirmationKind.values())buttons.add(nativeConfirmationToggle(player,kind));
                buttons.add(nativeButton("Back","settings native"));
            }else if(page==Page.NAMETAGS){
                for(NametagKind kind:NametagKind.values())buttons.add(nativeNametagToggle(player,kind));
                buttons.add(nativeButton("Back","settings native"));
            }else{
                buttons.add(nativeTpaToggle(player,TpaKind.OTHER));
                buttons.add(nativeTpaToggle(player,TpaKind.FACTION));
                /** "Hide Auto-Accept where possible" — a native Dialog's button list is free-form (unlike the
                 *  chest/Bedrock forms below, which key their click handlers off fixed positional indices), so
                 *  this is the one surface where the dependency can be hidden outright rather than shown
                 *  disabled-with-explanation. */
                if(enabled(player,TpaKind.FACTION.key,TpaKind.FACTION.fallback))buttons.add(nativeTpaToggle(player,TpaKind.AUTO_ACCEPT));
                buttons.add(nativeButton("Back","settings native"));
            }
            Dialog dialog=Dialog.create(builder->builder.empty()
                    .base(DialogBase.builder(Component.text(pageTitle(page),NamedTextColor.GOLD)).canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.NONE).build())
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
    private ActionButton nativeTpaToggle(Player player,TpaKind kind){
        boolean current=enabled(player,kind.key,kind.fallback);
        return ActionButton.create(stateLabel(prettyTpa(kind),current),Component.empty(),150,DialogAction.customClick((response,audience)->
                plugin.getServer().getScheduler().runTask(plugin,()->{
                    Player online=plugin.getServer().getPlayer(player.getUniqueId());if(online==null||locked(online,true))return;
                    set(online,kind.key,!enabled(online,kind.key,kind.fallback));openNative(online,Page.TPA);
                }),ClickCallback.Options.builder().uses(100).build()));
    }
    private ActionButton nativeNametagToggle(Player player,NametagKind kind){
        boolean current=enabled(player,kind.key,kind.fallback);
        return ActionButton.create(stateLabel(prettyNametag(kind),current),Component.empty(),150,DialogAction.customClick((response,audience)->
                plugin.getServer().getScheduler().runTask(plugin,()->{
                    Player online=plugin.getServer().getPlayer(player.getUniqueId());if(online==null||locked(online,true))return;
                    set(online,kind.key,!enabled(online,kind.key,kind.fallback));openNative(online,Page.NAMETAGS);
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
            SimpleForm.Builder form=SimpleForm.builder().title(pageTitle(page));
            if(page==Page.MAIN){
                for(Toggle toggle:mainToggles(player))form.button(toggle.title()+"\n"+(enabled(player,toggle.key(),toggle.fallback())?"§aON":"§cOFF"));
                form.button("Particle Intensity\n§e"+CoreUtil.pretty(particles(player)));
                form.button("Purchase Confirmations");
                form.button("TPA Requests");
                form.button("Nametags");
                form.button("Random Travel");
                form.button(plugin.teleports().isQueuedForRtp(player)?"RTP Queue\n§aQUEUED — tap to leave":"RTP Queue\n§7Tap to join");
                form.button("Cosmetics");
                form.button("Account");
            }else if(page==Page.CONFIRMATIONS){
                form.button("All Routine Confirmations\n"+(allConfirmations(player)?"§aON":"§cOFF"));
                for(ConfirmationKind kind:ConfirmationKind.values())form.button(prettyConfirmation(kind)+"\n"+(confirmationEnabled(player,kind)?"§aON":"§cOFF"));
                form.button("Back");
            }else if(page==Page.NAMETAGS){
                for(NametagKind kind:NametagKind.values())form.button(prettyNametag(kind)+"\n"+(enabled(player,kind.key,kind.fallback)?"§aON":"§cOFF"));
                form.button("Back");
            }else{
                boolean factionOn=enabled(player,TpaKind.FACTION.key,TpaKind.FACTION.fallback);
                form.button(prettyTpa(TpaKind.OTHER)+"\n"+(enabled(player,TpaKind.OTHER.key,TpaKind.OTHER.fallback)?"§aON":"§cOFF"));
                form.button(prettyTpa(TpaKind.FACTION)+"\n"+(factionOn?"§aON":"§cOFF"));
                form.button(prettyTpa(TpaKind.AUTO_ACCEPT)+"\n"+(factionOn?(enabled(player,TpaKind.AUTO_ACCEPT.key,TpaKind.AUTO_ACCEPT.fallback)?"§aON":"§cOFF"):"§7Unavailable — enable Faction TPA Requests"));
                form.button("Back");
            }
            form.validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->{
                if(locked(player,true))return;int clicked=response.clickedButtonId();
                if(page==Page.MAIN){
                    List<Toggle> mt=mainToggles(player);int n=mt.size();
                    if(clicked>=0&&clicked<n){Toggle toggle=mt.get(clicked);set(player,toggle.key(),!enabled(player,toggle.key(),toggle.fallback()));openBedrock(player,Page.MAIN);}
                    else if(clicked==n){cycleParticles(player);openBedrock(player,Page.MAIN);}
                    else if(clicked==n+1)openBedrock(player,Page.CONFIRMATIONS);
                    else if(clicked==n+2)openBedrock(player,Page.TPA);
                    else if(clicked==n+3)openBedrock(player,Page.NAMETAGS);
                    else if(clicked==n+4)plugin.teleports().rtp(player);
                    else if(clicked==n+5){plugin.teleports().toggleRtpQueue(player);openBedrock(player,Page.MAIN);}
                    else if(clicked==n+6)plugin.shards().openCosmetics(player);
                    else if(clicked==n+7)plugin.account().open(player);
                }else if(page==Page.CONFIRMATIONS){
                    if(clicked==0){setAllConfirmations(player,!allConfirmations(player));openBedrock(player,Page.CONFIRMATIONS);}
                    else if(clicked>0&&clicked<=ConfirmationKind.values().length){ConfirmationKind kind=ConfirmationKind.values()[clicked-1];set(player,kind.key,!confirmationEnabled(player,kind));openBedrock(player,Page.CONFIRMATIONS);}
                    else openBedrock(player,Page.MAIN);
                }else if(page==Page.NAMETAGS){
                    if(clicked>=0&&clicked<NametagKind.values().length){NametagKind kind=NametagKind.values()[clicked];set(player,kind.key,!enabled(player,kind.key,kind.fallback));openBedrock(player,Page.NAMETAGS);}
                    else openBedrock(player,Page.MAIN);
                }else{
                    if(clicked==0){set(player,TpaKind.OTHER.key,!enabled(player,TpaKind.OTHER.key,TpaKind.OTHER.fallback));openBedrock(player,Page.TPA);}
                    else if(clicked==1){set(player,TpaKind.FACTION.key,!enabled(player,TpaKind.FACTION.key,TpaKind.FACTION.fallback));openBedrock(player,Page.TPA);}
                    else if(clicked==2){set(player,TpaKind.AUTO_ACCEPT.key,!enabled(player,TpaKind.AUTO_ACCEPT.key,TpaKind.AUTO_ACCEPT.fallback));openBedrock(player,Page.TPA);}
                    else openBedrock(player,Page.MAIN);
                }
            }));
            return connection.sendForm(form);
        }catch(Throwable error){return false;}
    }

    private void openChest(Player player,Page page){
        Inventory inv=plugin.getServer().createInventory(new Holder(page),54,Component.text(pageTitle(page),NamedTextColor.DARK_GRAY));
        renderChest(inv,player,page);
        player.openInventory(inv);
    }
    private static final int[] MAIN_SLOTS={10,11,12,13,14,15,16,19,20,24};
    private void renderChest(Inventory inv,Player player,Page page){
        inv.clear();
        if(page==Page.MAIN){
            List<Toggle> mt=mainToggles(player);for(int i=0;i<mt.size();i++){Toggle toggle=mt.get(i);inv.setItem(MAIN_SLOTS[i],toggle(toggle.icon(),toggle.title(),enabled(player,toggle.key(),toggle.fallback())));}
            inv.setItem(21,button(Material.ENDER_PEARL,"TPA Requests",List.of("Configure who can send you teleport requests.")));
            inv.setItem(22,button(Material.NAME_TAG,"Nametags",List.of("Show balances or faction tags under player names.")));
            inv.setItem(23,cycle(Material.FIREWORK_STAR,"Particle Intensity",particles(player)));
            inv.setItem(31,button(Material.REPEATER,"Purchase Confirmations",List.of("Configure each marketplace section.")));
            inv.setItem(39,button(Material.ENDER_PEARL,"Random Travel",List.of("Travel safely in your current dimension.")));
            boolean queued=plugin.teleports().isQueuedForRtp(player);
            inv.setItem(40,button(queued?Material.LIME_DYE:Material.COMPASS,"RTP Queue",List.of(queued?"§aQueued — click to leave.":"Click to join the queue.","Pairs you with another queued player","in the same dimension.")));
            inv.setItem(41,button(Material.PLAYER_HEAD,"Cosmetics",List.of("Choose an unlocked Shard cosmetic.")));
            inv.setItem(49,button(Material.NAME_TAG,"Account",List.of("Password and registration settings.")));
        }else if(page==Page.CONFIRMATIONS){
            inv.setItem(13,toggle(Material.REPEATER,"All Routine Confirmations",allConfirmations(player)));
            int slot=19;for(ConfirmationKind kind:ConfirmationKind.values()){inv.setItem(slot++,stateBlock(prettyConfirmation(kind),confirmationEnabled(player,kind)));}
            inv.setItem(49,button(Material.ARROW,"Back",List.of()));
        }else if(page==Page.NAMETAGS){
            int nameSlot=20;
            for(NametagKind kind:NametagKind.values())inv.setItem(nameSlot++,tpaChestItem(prettyNametag(kind),nametagHint(kind),enabled(player,kind.key,kind.fallback)));
            inv.setItem(49,button(Material.ARROW,"Back",List.of()));
        }else{
            boolean factionOn=enabled(player,TpaKind.FACTION.key,TpaKind.FACTION.fallback);
            inv.setItem(20,tpaChestItem(prettyTpa(TpaKind.OTHER),"Requests from outside your faction.",enabled(player,TpaKind.OTHER.key,TpaKind.OTHER.fallback)));
            inv.setItem(21,tpaChestItem(prettyTpa(TpaKind.FACTION),"/tpa and /tpahere from faction members.",factionOn));
            inv.setItem(22,factionOn?tpaChestItem(prettyTpa(TpaKind.AUTO_ACCEPT),"Auto-accepts only /tpa (never /tpahere)\nfrom faction members.",enabled(player,TpaKind.AUTO_ACCEPT.key,TpaKind.AUTO_ACCEPT.fallback)):tpaDisabledChestItem());
            inv.setItem(49,button(Material.ARROW,"Back",List.of()));
        }
    }

    @EventHandler public void click(InventoryClickEvent event){
        if(!(event.getInventory().getHolder(false) instanceof Holder holder))return;event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player))return;
        if(locked(player,true))return;
        int slot=event.getRawSlot();
        if(holder.page==Page.MAIN){
            List<Toggle> mt=mainToggles(player);for(int i=0;i<mt.size();i++)if(slot==MAIN_SLOTS[i]){Toggle toggle=mt.get(i);set(player,toggle.key(),!enabled(player,toggle.key(),toggle.fallback()));renderChest(event.getInventory(),player,Page.MAIN);return;}
            if(slot==21)openChest(player,Page.TPA);
            else if(slot==22)openChest(player,Page.NAMETAGS);
            else if(slot==23){cycleParticles(player);renderChest(event.getInventory(),player,Page.MAIN);}
            else if(slot==31)openChest(player,Page.CONFIRMATIONS);
            else if(slot==39){player.closeInventory();plugin.teleports().rtp(player);}
            else if(slot==40){plugin.teleports().toggleRtpQueue(player);renderChest(event.getInventory(),player,Page.MAIN);}
            else if(slot==41)plugin.shards().openCosmetics(player);
            else if(slot==49)plugin.account().open(player);
        }else if(holder.page==Page.NAMETAGS){
            if(slot>=20&&slot<20+NametagKind.values().length){NametagKind kind=NametagKind.values()[slot-20];set(player,kind.key,!enabled(player,kind.key,kind.fallback));renderChest(event.getInventory(),player,Page.NAMETAGS);}
            else if(slot==49)openChest(player,Page.MAIN);
        }else if(holder.page==Page.CONFIRMATIONS){
            if(slot==13){setAllConfirmations(player,!allConfirmations(player));renderChest(event.getInventory(),player,Page.CONFIRMATIONS);}
            else if(slot>=19&&slot<19+ConfirmationKind.values().length){ConfirmationKind kind=ConfirmationKind.values()[slot-19];set(player,kind.key,!confirmationEnabled(player,kind));renderChest(event.getInventory(),player,Page.CONFIRMATIONS);}
            else if(slot==49)openChest(player,Page.MAIN);
        }else{
            if(slot==20){set(player,TpaKind.OTHER.key,!enabled(player,TpaKind.OTHER.key,TpaKind.OTHER.fallback));renderChest(event.getInventory(),player,Page.TPA);}
            else if(slot==21){set(player,TpaKind.FACTION.key,!enabled(player,TpaKind.FACTION.key,TpaKind.FACTION.fallback));renderChest(event.getInventory(),player,Page.TPA);}
            else if(slot==22){set(player,TpaKind.AUTO_ACCEPT.key,!enabled(player,TpaKind.AUTO_ACCEPT.key,TpaKind.AUTO_ACCEPT.fallback));renderChest(event.getInventory(),player,Page.TPA);}
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
    boolean eliteMobs(Player player){return enabled(player,"elite_mobs",true);}
    /** Elite Mobs is a SUBSET of Hostile Mobs: a player "allows" elites near them only when both are on. */
    private boolean elitesOn(Player player){return naturalSpawns(player)&&eliteMobs(player);}
    /** The settings screens render this instead of MAIN so the Elite Mobs row is absent when Hostile Mobs is
     *  off. Elite Mobs is the LAST entry in MAIN, so dropping it leaves every other toggle's index/slot put. */
    private List<Toggle> mainToggles(Player player){return naturalSpawns(player)?MAIN:MAIN.stream().filter(t->!t.key().equals("elite_mobs")).toList();}
    /** Spawn-influence test for elites, mirroring the Hostile-Mobs gate: an elite may exist at loc only if no
     *  nearby player has elites off, unless some nearby player has them on (the on-player overrides). */
    boolean elitesAllowedAt(Location loc){
        if(loc==null||loc.getWorld()==null)return true;
        double radius=plugin.getConfig().getDouble("settings.natural-spawn-influence-radius",128),radiusSq=radius*radius;
        boolean anyOff=false;
        for(Player player:plugin.getServer().getOnlinePlayers()){
            if(player.getWorld()!=loc.getWorld()||player.getLocation().distanceSquared(loc)>radiusSq)continue;
            if(elitesOn(player))return true;
            anyOff=true;
        }
        return !anyOff;
    }
    boolean graveTracking(Player player){return enabled(player,"grave_tracking",true);}
    boolean bossNotifications(Player player){return enabled(player,"boss_notifications",true);}
    boolean privateMessages(Player player){return enabled(player,"private_messages",true);}
    boolean tpaRequests(Player player){return enabled(player,TpaKind.OTHER.key,TpaKind.OTHER.fallback);}
    boolean factionTpaRequests(Player player){return enabled(player,TpaKind.FACTION.key,TpaKind.FACTION.fallback);}
    /** Runtime enforcement of "Auto-Accept requires Faction TPA Requests" lives here, not just in the UI —
     *  callers never need to separately check factionTpaRequests() before trusting this. */
    boolean tpaAutoAcceptFaction(Player player){return factionTpaRequests(player)&&enabled(player,TpaKind.AUTO_ACCEPT.key,TpaKind.AUTO_ACCEPT.fallback);}
    boolean showBalanceNametags(Player player){return enabled(player,NametagKind.BALANCES.key,NametagKind.BALANCES.fallback);}
    boolean showFactionNametags(Player player){return enabled(player,NametagKind.FACTIONS.key,NametagKind.FACTIONS.fallback);}
    boolean showHeartNametags(Player player){return enabled(player,NametagKind.HEARTS.key,NametagKind.HEARTS.fallback);}
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
        if(TpaKind.AUTO_ACCEPT.key.equals(key)&&enabled&&!factionTpaRequests(player)){CoreUtil.error(player,"Enable Faction TPA Requests first — Auto-Accept only applies to faction /tpa requests.");return;}
        db.preference(CoreUtil.id(player),key,Boolean.toString(enabled));
        if("sidebar".equals(key)){if(enabled)plugin.ui().update(player);else plugin.ui().removeSidebar(player);}
        if("night_vision".equals(key)){if(enabled)applyNightVision(player);else removeNightVision(player);}
        if("natural_spawns".equals(key)&&!enabled)peacefulFor(player);
        if("elite_mobs".equals(key)&&!enabled)eliteSweepNow(player);
        if(key.startsWith("nametag_"))plugin.packetNametags().viewerSettingChanged(player);
        CoreUtil.msg(player,displayKey(key)+" "+(enabled?"enabled":"disabled")+".");
    }
    private void cycleParticles(Player player){
        String next=switch(particles(player)){case"FULL"->"REDUCED";case"REDUCED"->"MINIMAL";default->"FULL";};
        db.preference(CoreUtil.id(player),"particle_intensity",next);CoreUtil.msg(player,"Particle Intensity: "+CoreUtil.pretty(next)+".");
    }
    private boolean validKey(String key){return MAIN.stream().anyMatch(toggle->toggle.key().equals(key))||Arrays.stream(ConfirmationKind.values()).anyMatch(kind->kind.key.equals(key))||Arrays.stream(TpaKind.values()).anyMatch(kind->kind.key.equals(key))||Arrays.stream(NametagKind.values()).anyMatch(kind->kind.key.equals(key));}
    private boolean defaultFor(String key){for(ConfirmationKind kind:ConfirmationKind.values())if(kind.key.equals(key))return kind.fallback;for(TpaKind kind:TpaKind.values())if(kind.key.equals(key))return kind.fallback;for(NametagKind kind:NametagKind.values())if(kind.key.equals(key))return kind.fallback;return MAIN.stream().filter(toggle->toggle.key().equals(key)).map(Toggle::fallback).findFirst().orElse(true);}
    private String state(Player player,String key,boolean fallback){return enabled(player,key,fallback)?"ON":"OFF";}
    private String displayKey(String key){for(TpaKind kind:TpaKind.values())if(kind.key.equals(key))return prettyTpa(kind);for(NametagKind kind:NametagKind.values())if(kind.key.equals(key))return prettyNametag(kind);return MAIN.stream().filter(toggle->toggle.key().equals(key)).map(Toggle::title).findFirst().orElse(key.startsWith("confirm_")?CoreUtil.pretty(key.substring(8))+" confirmations":CoreUtil.pretty(key));}
    private String prettyConfirmation(ConfirmationKind kind){return switch(kind){case SHOP->"Regular Shop";case AUCTION->"Auction House";case LUXURY->"Luxury Shop";case SHARD->"Shard Shop";};}
    private String prettyTpa(TpaKind kind){return switch(kind){case OTHER->"Other Players' TPA Requests";case FACTION->"Faction TPA Requests";case AUTO_ACCEPT->"Auto-Accept Faction TPA";};}
    private String prettyNametag(NametagKind kind){return switch(kind){case BALANCES->"Show Balances";case FACTIONS->"Show Faction Tags";case HEARTS->"Show Hearts";};}
    private String nametagHint(NametagKind kind){return switch(kind){case BALANCES->"Show each player's balance under their name.";case FACTIONS->"Show each player's faction tag beside their name.";case HEARTS->"Show the health line. Always shown when every option here is off.";};}
    private String pageTitle(Page page){return switch(page){case MAIN->"ASHEN SETTINGS";case CONFIRMATIONS->"PURCHASE CONFIRMATIONS";case TPA->"TPA REQUESTS";case NAMETAGS->"NAMETAGS";};}

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
        double radius=plugin.getConfig().getDouble("settings.natural-spawn-influence-radius",128),radiusSq=radius*radius;
        List<Player> disabled=new ArrayList<>();List<Location> enabledLocations=new ArrayList<>();
        List<Player> eliteDisabled=new ArrayList<>();List<Location> eliteEnabledLocations=new ArrayList<>();
        for(Player player:plugin.getServer().getOnlinePlayers()){
            boolean hostile=naturalSpawns(player);
            if(hostile)enabledLocations.add(player.getLocation());else disabled.add(player);
            /** Fully-on players protect elites; Hostile-on/Elite-off players drive an elite-only sweep.
             *  Hostile-off players are already covered by the hostile sweep, so they skip the elite pass. */
            if(hostile&&eliteMobs(player))eliteEnabledLocations.add(player.getLocation());
            else if(hostile)eliteDisabled.add(player);
        }
        for(Player player:disabled)peacefulFor(player,enabledLocations,radius,radiusSq);
        for(Player player:eliteDisabled)eliteSweepFor(player,eliteEnabledLocations,radius,radiusSq);
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
    /** Elite-only despawn sweep -- identical in shape to peacefulFor above but scoped to ordinary elites, for
     *  players who kept Hostile Mobs on but turned Elite Mobs off. An elite survives only if a nearby
     *  Elite-on player is within range of the elite's own position. */
    private void eliteSweepFor(Player player,List<Location> eliteEnabledLocations,double radius,double radiusSq){
        for(org.bukkit.entity.Entity entity:player.getNearbyEntities(radius,radius,radius)){
            if(!(entity instanceof org.bukkit.entity.LivingEntity living)||!plugin.bosses().isOrdinaryElite(living))continue;
            Location mobLocation=entity.getLocation();
            boolean protectedByNearbyOnPlayer=eliteEnabledLocations.stream().anyMatch(loc->loc.getWorld().equals(mobLocation.getWorld())&&loc.distanceSquared(mobLocation)<=radiusSq);
            if(!protectedByNearbyOnPlayer)living.remove();
        }
    }
    private void eliteSweepNow(Player player){
        double radius=plugin.getConfig().getDouble("settings.natural-spawn-influence-radius",128);
        List<Location> eliteEnabledLocations=new ArrayList<>();
        for(Player other:plugin.getServer().getOnlinePlayers())if(!other.getUniqueId().equals(player.getUniqueId())&&elitesOn(other))eliteEnabledLocations.add(other.getLocation());
        eliteSweepFor(player,eliteEnabledLocations,radius,radius*radius);
    }
    /** Bastion garrison mobs, judged by BOTH what they are and where they stand.
     *
     *  Type alone is wrong -- piglins wander the whole Nether -- and location alone is wrong too, since a
     *  stray skeleton standing in a bastion is not a bastion threat. Requiring both keeps the exemption to
     *  the actual garrison, so Hostile Mobs Off cannot be used to walk an empty bastion, while a mob that
     *  merely drifted inside is still cleared normally. */
    private static final java.util.Set<org.bukkit.entity.EntityType> BASTION_GARRISON=java.util.Set.of(
            org.bukkit.entity.EntityType.PIGLIN,org.bukkit.entity.EntityType.PIGLIN_BRUTE,
            org.bukkit.entity.EntityType.HOGLIN,org.bukkit.entity.EntityType.ZOGLIN,
            org.bukkit.entity.EntityType.MAGMA_CUBE);
    /** Package-private on purpose: the spawn gate in GameplayListener uses this exact same test, so the
     *  exemption cannot end up scoped differently on the two halves of the lifecycle. Blocking the spawn
     *  and then removing the survivors are the same rule applied twice. */
    boolean isBastionThreat(org.bukkit.entity.LivingEntity living){
        if(!BASTION_GARRISON.contains(living.getType()))return false;
        org.bukkit.Location at=living.getLocation();
        if(at.getWorld()==null||at.getWorld().getEnvironment()!=org.bukkit.World.Environment.NETHER)return false;
        try{
            for(org.bukkit.generator.structure.GeneratedStructure structure:
                    at.getWorld().getStructures(at.getBlockX()>>4,at.getBlockZ()>>4,org.bukkit.generator.structure.Structure.BASTION_REMNANT))
                if(structure.getBoundingBox().contains(at.getX(),at.getY(),at.getZ()))return true;
        }catch(Throwable ignored){}
        return false;
    }
    /** Ocean-monument garrison, judged by BOTH type and location, exactly like the bastion rule above so the
     *  two exemptions stay identical in shape. Guardians patrol open ocean freely, so type alone is wrong, and a
     *  guardian that merely drifted out of the structure is cleared normally. Elder Guardians and Guardians
     *  standing inside an Ocean Monument are the monument's garrison and are exempt from Hostile Mobs Off. */
    private static final java.util.Set<org.bukkit.entity.EntityType> MONUMENT_GARRISON=java.util.Set.of(
            org.bukkit.entity.EntityType.GUARDIAN,org.bukkit.entity.EntityType.ELDER_GUARDIAN);
    boolean isMonumentThreat(org.bukkit.entity.LivingEntity living){
        if(!MONUMENT_GARRISON.contains(living.getType()))return false;
        org.bukkit.Location at=living.getLocation();
        if(at.getWorld()==null||at.getWorld().getEnvironment()!=org.bukkit.World.Environment.NORMAL)return false;
        try{
            for(org.bukkit.generator.structure.GeneratedStructure structure:
                    at.getWorld().getStructures(at.getBlockX()>>4,at.getBlockZ()>>4,org.bukkit.generator.structure.Structure.MONUMENT))
                if(structure.getBoundingBox().contains(at.getX(),at.getY(),at.getZ()))return true;
        }catch(Throwable ignored){}
        return false;
    }
    /** One-time migration to the intended confirmation defaults. Applied per player and recorded, so a
     *  later restart cannot re-apply it over a choice they have since made -- the whole point is that these
     *  are DEFAULTS, not enforced values. Anyone who had already set a preference keeps it. */
    void applyConfirmationDefaults(Player player){
        String id=CoreUtil.id(player);
        if("true".equalsIgnoreCase(plugin.db().preference(id,"confirm_defaults_v2")))return;
        plugin.db().preference(id,"confirm_defaults_v2","true");
        for(ConfirmationKind kind:ConfirmationKind.values()){
            /** Only seeds a value where the player has never expressed one. */
            if(plugin.db().preference(id,kind.key)==null)set(player,kind.key,defaultFor(kind.key));
        }
    }
    private boolean removableHostile(Enemy enemy){
        if(!(enemy instanceof org.bukkit.entity.LivingEntity living)||plugin.bosses().isPeacefulExempt(living))return false;
        /** Reinforcements summoned by a world boss are part of that fight and must not be cleared. */
        if(plugin.bosses().isWorldBossAdd(living))return false;
        if(isBastionThreat(living))return false;
        if(isMonumentThreat(living))return false;
        if(plugin.bosses().isOrdinaryElite(living))return true;
        if(enemy instanceof Tameable tame&&tame.isTamed())return false;
        if(living.customName()!=null)return false;
        if(living.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin,"trial_spawner_mob"),org.bukkit.persistence.PersistentDataType.BYTE))return false;
        return true;
    }
    boolean selfTest(){return MAIN.size()==10&&ConfirmationKind.values().length==4&&!defaultFor(ConfirmationKind.SHOP.key)&&defaultFor(ConfirmationKind.LUXURY.key)&&defaultFor(ConfirmationKind.AUCTION.key)&&defaultFor(ConfirmationKind.SHARD.key)&&particleScaleFor("FULL")==1&&particleScaleFor("MINIMAL")<particleScaleFor("REDUCED")&&tpaSelfTest()&&NametagKind.values().length==3&&defaultFor(NametagKind.BALANCES.key)&&!defaultFor(NametagKind.FACTIONS.key)&&!defaultFor(NametagKind.HEARTS.key);}
    private boolean tpaSelfTest(){return TpaKind.values().length==3&&defaultFor(TpaKind.OTHER.key)&&defaultFor(TpaKind.FACTION.key)&&!defaultFor(TpaKind.AUTO_ACCEPT.key)&&TpaKind.OTHER.key.equals("tpa_requests");}
    private double particleScaleFor(String value){return switch(value){case"REDUCED"->.45;case"MINIMAL"->.15;default->1;};}

    private ItemStack toggle(Material icon,String title,boolean enabled){ItemStack item=new ItemStack(icon);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(title,NamedTextColor.GOLD));meta.lore(List.of(Component.text(enabled?"ON":"OFF",enabled?NamedTextColor.GREEN:NamedTextColor.RED),Component.text("Click to change.",NamedTextColor.DARK_GRAY)));item.setItemMeta(meta);return item;}
    private ItemStack stateBlock(String title,boolean enabled){return toggle(enabled?Material.LIME_CONCRETE:Material.RED_CONCRETE,title,enabled);}
    private ItemStack cycle(Material icon,String title,String value){return button(icon,title,List.of(value,"Click to change."));}
    private ItemStack button(Material material,String title,List<String> lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(title,NamedTextColor.GOLD));meta.lore(lore.stream().map(line->Component.text(line,NamedTextColor.GRAY)).toList());item.setItemMeta(meta);return item;}
    private ItemStack tpaChestItem(String title,String description,boolean enabled){
        ItemStack item=new ItemStack(enabled?Material.LIME_DYE:Material.RED_DYE);ItemMeta meta=item.getItemMeta();
        meta.displayName(Component.text(title,NamedTextColor.GOLD));
        List<Component> lore=new ArrayList<>();lore.add(Component.text(enabled?"ON":"OFF",enabled?NamedTextColor.GREEN:NamedTextColor.RED));
        for(String line:description.split("\n"))lore.add(Component.text(line,NamedTextColor.GRAY));
        lore.add(Component.text("Click to change.",NamedTextColor.DARK_GRAY));
        meta.lore(lore);item.setItemMeta(meta);return item;
    }
    /** Chest slots can't be cleanly "hidden" the way a native Dialog's button list can (an empty slot next to
     *  Faction TPA would just look broken, with no clue why), so this is the "otherwise show it disabled and
     *  clearly explain the dependency" branch of the spec for the chest/Bedrock surfaces. */
    private ItemStack tpaDisabledChestItem(){
        ItemStack item=new ItemStack(Material.GRAY_DYE);ItemMeta meta=item.getItemMeta();
        meta.displayName(Component.text("Auto-Accept Faction TPA",NamedTextColor.GRAY));
        meta.lore(List.of(Component.text("UNAVAILABLE",NamedTextColor.DARK_GRAY),Component.text("Requires Faction TPA Requests to be ON.",NamedTextColor.GRAY),Component.text("Auto-accepts only /tpa (never /tpahere)",NamedTextColor.DARK_GRAY),Component.text("from faction members.",NamedTextColor.DARK_GRAY)));
        item.setItemMeta(meta);return item;
    }
}
