package net.communitysmp.core;

import fr.xephi.authme.api.v3.AuthMeApi;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class AccountService implements Listener {
    private record Holder() implements InventoryHolder { @Override public Inventory getInventory(){return null;} }
    private enum Flow { CHANGE_CURRENT, CHANGE_NEW, CHANGE_CONFIRM, DELETE_PASSWORD, DELETE_CONFIRM }
    private record Pending(Flow flow,String first,String second,long expires) {}
    private final SMPCore plugin;
    private final Map<UUID,Pending> pending=new ConcurrentHashMap<>();

    AccountService(SMPCore plugin){this.plugin=plugin;}
    boolean awaitingInput(Player player){return pending.containsKey(player.getUniqueId());}

    void open(Player player){
        if(plugin.isBedrock(player)){openBedrock(player);return;}
        Inventory inventory=plugin.getServer().createInventory(new Holder(),27,Component.text("Account",NamedTextColor.DARK_GRAY));
        inventory.setItem(11,CoreUtil.named(Material.NAME_TAG,"Change Password",java.util.List.of("Requires your current password.")));
        inventory.setItem(15,CoreUtil.named(Material.BARRIER,"Delete Registration",java.util.List.of("Keeps worlds, inventory, economy, and faction data.","Requires password and DELETE confirmation.")));
        inventory.setItem(22,CoreUtil.named(Material.ARROW,"Back",java.util.List.of()));
        player.openInventory(inventory);
    }

    @SuppressWarnings("UnstableApiUsage")
    void openNative(Player player,String action){
        if(!nativeSupported(player)){open(player);return;}
        if(!action.isBlank()){
            if(action.equalsIgnoreCase("change")){if(eligible(player))nativeChange(player);return;}
            if(action.equalsIgnoreCase("delete")){if(eligible(player))nativeDelete(player);return;}
        }
        try{
            List<ActionButton> buttons=java.util.List.of(
                    nativeCommand("Change Password","settings native account change",NamedTextColor.GREEN),
                    nativeCommand("Delete Registration","settings native account delete",NamedTextColor.RED),
                    nativeCommand("Back to Settings","settings native",NamedTextColor.GRAY));
            Dialog dialog=Dialog.create(builder->builder.empty()
                    .base(DialogBase.builder(Component.text("Account",CoreUtil.EMBER)).canCloseWithEscape(true).pause(false).build())
                    .type(DialogType.multiAction(buttons,null,1)));
            player.showDialog(dialog);
        }catch(Throwable ignored){open(player);}
    }

    private ActionButton nativeCommand(String label,String command,NamedTextColor color){
        return ActionButton.create(Component.text(label,color),Component.empty(),220,DialogAction.staticAction(ClickEvent.runCommand("/"+command)));
    }

    @EventHandler public void click(InventoryClickEvent event){
        if(!(event.getInventory().getHolder(false) instanceof Holder))return;event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player))return;
        if(event.getRawSlot()==11)change(player);else if(event.getRawSlot()==15)delete(player);else if(event.getRawSlot()==22)plugin.settings().openChestRoot(player);
    }

    private void change(Player player){
        if(!eligible(player))return;
        player.closeInventory();
        if(nativeSupported(player)&&nativeChange(player))return;
        pending.put(player.getUniqueId(),new Pending(Flow.CHANGE_CURRENT,"","",System.currentTimeMillis()+60_000));
        CoreUtil.msg(player,"Type your current password in chat, or cancel. It will not be broadcast.");timeout(player);
    }
    private void delete(Player player){
        if(!eligible(player))return;
        player.closeInventory();
        if(nativeSupported(player)&&nativeDelete(player))return;
        pending.put(player.getUniqueId(),new Pending(Flow.DELETE_PASSWORD,"","",System.currentTimeMillis()+60_000));
        CoreUtil.msg(player,"Type your current password in chat, or cancel. It will not be broadcast.");timeout(player);
    }

    private boolean eligible(Player player){
        try{if(!AuthMeApi.getInstance().isRegistered(player.getName())){CoreUtil.error(player,"This username is not registered yet.");return false;}}catch(Throwable error){CoreUtil.error(player,"Account management is temporarily unavailable.");return false;}
        return true;
    }

    @SuppressWarnings("UnstableApiUsage")
    private boolean nativeChange(Player player){
        try{
            ActionButton apply=ActionButton.create(Component.text("Change Password"),Component.empty(),180,DialogAction.customClick((response,audience)->{
                String current=response.getText("current"),next=response.getText("new"),confirm=response.getText("confirm");
                plugin.getServer().getScheduler().runTask(plugin,()->submitChange(player,current,next,confirm));
            },ClickCallback.Options.builder().uses(1).build()));
            Dialog dialog=Dialog.create(builder->builder.empty().base(DialogBase.builder(Component.text("Change Password",NamedTextColor.GOLD))
                    .inputs(java.util.List.of(
                            DialogInput.text("current",Component.text("Current password")).maxLength(30).width(300).build(),
                            DialogInput.text("new",Component.text("New password")).maxLength(30).width(300).build(),
                            DialogInput.text("confirm",Component.text("Confirm new password")).maxLength(30).width(300).build()))
                    .canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.NONE).build())
                    .type(DialogType.confirmation(apply,nativeCommand("Back","settings native account",NamedTextColor.GRAY))));
            player.showDialog(dialog);return true;
        }catch(Throwable ignored){return false;}
    }

    @SuppressWarnings("UnstableApiUsage")
    private boolean nativeDelete(Player player){
        try{
            ActionButton apply=ActionButton.create(Component.text("Delete Registration",NamedTextColor.RED),Component.empty(),180,DialogAction.customClick((response,audience)->{
                String current=response.getText("current"),confirm=response.getText("delete");
                plugin.getServer().getScheduler().runTask(plugin,()->submitDelete(player,current,confirm));
            },ClickCallback.Options.builder().uses(1).build()));
            Dialog dialog=Dialog.create(builder->builder.empty().base(DialogBase.builder(Component.text("Delete Registration",NamedTextColor.RED))
                    .body(java.util.List.of(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text("This only removes AuthMe registration. Type DELETE to confirm.",NamedTextColor.GRAY),280)))
                    .inputs(java.util.List.of(
                            DialogInput.text("current",Component.text("Current password")).maxLength(30).width(300).build(),
                            DialogInput.text("delete",Component.text("Type DELETE")).maxLength(12).width(300).build()))
                    .canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.NONE).build())
                    .type(DialogType.confirmation(apply,nativeCommand("Back","settings native account",NamedTextColor.GRAY))));
            player.showDialog(dialog);return true;
        }catch(Throwable ignored){return false;}
    }

    private void openBedrock(Player player){
        try{
            GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());if(connection==null){open(player);return;}
            SimpleForm form=SimpleForm.builder().title("Account").button("Change Password").button("Delete Registration").button("Back")
                    .validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->{
                        if(response.clickedButtonId()==0)openBedrockChange(player);
                        else if(response.clickedButtonId()==1)openBedrockDelete(player);
                        else plugin.settings().openBedrockRoot(player);
                    })).build();
            connection.sendForm(form);
        }catch(Throwable error){CoreUtil.msg(player,"Your Bedrock account is authenticated automatically through Floodgate.");}
    }
    private void openBedrockChange(Player player){
        if(!eligible(player))return;
        try{
            GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());if(connection==null)return;
            connection.sendForm(CustomForm.builder().title("Change Password")
                    .input("Current Password").input("New Password").input("Confirm New Password")
                    .validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->
                            submitChange(player,response.getInput(0),response.getInput(1),response.getInput(2))))
                    .closedResultHandler(ignored->plugin.getServer().getScheduler().runTask(plugin,()->openBedrock(player))).build());
        }catch(Throwable error){CoreUtil.error(player,"Account management is temporarily unavailable.");}
    }
    private void openBedrockDelete(Player player){
        if(!eligible(player))return;
        try{
            GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());if(connection==null)return;
            connection.sendForm(CustomForm.builder().title("Delete Registration")
                    .label("Your player data is preserved.")
                    .input("Current Password").input("Type DELETE")
                    .validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->
                            submitDelete(player,response.getInput(1),response.getInput(2))))
                    .closedResultHandler(ignored->plugin.getServer().getScheduler().runTask(plugin,()->openBedrock(player))).build());
        }catch(Throwable error){CoreUtil.error(player,"Account management is temporarily unavailable.");}
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority=EventPriority.HIGHEST) public void chat(AsyncPlayerChatEvent event){
        Pending input=pending.get(event.getPlayer().getUniqueId());if(input==null)return;event.setCancelled(true);String message=event.getMessage();
        plugin.getServer().getScheduler().runTask(plugin,()->handleChat(event.getPlayer(),message));
    }
    private void handleChat(Player player,String message){
        Pending input=pending.get(player.getUniqueId());if(input==null)return;
        if(System.currentTimeMillis()>input.expires()){pending.remove(player.getUniqueId());CoreUtil.error(player,"Account input timed out.");return;}
        if(message.equalsIgnoreCase("cancel")){pending.remove(player.getUniqueId());CoreUtil.msg(player,"Account change cancelled.");open(player);return;}
        switch(input.flow()){
            case CHANGE_CURRENT->{pending.put(player.getUniqueId(),new Pending(Flow.CHANGE_NEW,message,"",input.expires()));CoreUtil.msg(player,"Type the new password.");}
            case CHANGE_NEW->{pending.put(player.getUniqueId(),new Pending(Flow.CHANGE_CONFIRM,input.first(),message,input.expires()));CoreUtil.msg(player,"Type the new password again.");}
            case CHANGE_CONFIRM->{pending.remove(player.getUniqueId());submitChange(player,input.first(),input.second(),message);}
            case DELETE_PASSWORD->{pending.put(player.getUniqueId(),new Pending(Flow.DELETE_CONFIRM,message,"",input.expires()));CoreUtil.error(player,"Type DELETE to remove this AuthMe registration, or cancel.");}
            case DELETE_CONFIRM->{pending.remove(player.getUniqueId());submitDelete(player,input.first(),message);}
        }
    }

    private void submitChange(Player player,String current,String next,String confirmation){
        if(current==null||next==null||!next.equals(confirmation)||next.length()<8||next.length()>30){CoreUtil.error(player,"Passwords must match and use 8–30 characters.");return;}
        runAuth(player,()->{
            AuthMeApi api=AuthMeApi.getInstance();if(!api.checkPassword(player.getName(),current))return "CURRENT";api.changePassword(player.getName(),next);return "OK";
        },"Password changed successfully.");
    }
    private void submitDelete(Player player,String current,String confirmation){
        if(!"DELETE".equals(confirmation)){CoreUtil.error(player,"Registration deletion cancelled: DELETE was not entered exactly.");return;}
        runAuth(player,()->{
            AuthMeApi api=AuthMeApi.getInstance();if(!api.checkPassword(player.getName(),current))return "CURRENT";api.forceUnregister(player);return "OK";
        },"AuthMe registration removed. Your inventory, economy, faction, statistics, and world data were preserved.");
    }
    private interface AuthWork { String run() throws Exception; }
    private void runAuth(Player player,AuthWork work,String success){
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,()->{
            String result;try{result=work.run();}catch(Throwable error){result="ERROR";}
            String finished=result;plugin.getServer().getScheduler().runTask(plugin,()->{
                if(!player.isOnline())return;if("OK".equals(finished))CoreUtil.msg(player,success);else if("CURRENT".equals(finished))CoreUtil.error(player,"The current password was incorrect.");else CoreUtil.error(player,"Account management failed safely; no change was made.");
            });
        });
    }
    private void timeout(Player player){long marker=pending.get(player.getUniqueId()).expires();BukkitTask ignored=plugin.getServer().getScheduler().runTaskLater(plugin,()->{Pending current=pending.get(player.getUniqueId());if(current!=null&&current.expires()==marker&&System.currentTimeMillis()>=marker){pending.remove(player.getUniqueId());if(player.isOnline())CoreUtil.error(player,"Account input timed out.");}},1202L);}
    private boolean nativeSupported(Player player){return plugin.getConfig().getBoolean("settings.native-dialogs",true)&&player.getProtocolVersion()==Bukkit.getUnsafe().getProtocolVersion();}
    @EventHandler public void quit(PlayerQuitEvent event){pending.remove(event.getPlayer().getUniqueId());}
}
