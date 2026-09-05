package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

final class ConfirmationService implements Listener {
    private record Holder(UUID token) implements InventoryHolder {@Override public Inventory getInventory(){return null;}}
    private record Pending(UUID player,Runnable accept,Runnable cancel) {}
    private final SMPCore plugin;
    private final Map<UUID,Pending> pending=new HashMap<>();

    ConfirmationService(SMPCore plugin){this.plugin=plugin;}

    void request(Player player,SettingsService.ConfirmationKind kind,boolean mandatory,String title,List<String> details,Runnable accepted){
        request(player,kind,mandatory,title,details,accepted,()->{});
    }
    void request(Player player,SettingsService.ConfirmationKind kind,boolean mandatory,String title,List<String> details,Runnable accepted,Runnable cancelled){
        if(!mandatory&&!plugin.settings().confirmationEnabled(player,kind)){accepted.run();return;}
        UUID token=UUID.randomUUID();pending.entrySet().removeIf(entry->entry.getValue().player().equals(player.getUniqueId()));pending.put(token,new Pending(player.getUniqueId(),accepted,cancelled));
        Inventory inventory=plugin.getServer().createInventory(new Holder(token),27,Component.text("Confirm",NamedTextColor.DARK_GRAY));
        inventory.setItem(CoreUtil.Menu.SUBJECT,CoreUtil.Menu.heading(Material.PAPER,title,details));
        inventory.setItem(CoreUtil.Menu.CANCEL,CoreUtil.Menu.cancel("Nothing is charged and nothing changes."));
        inventory.setItem(CoreUtil.Menu.CONFIRM,CoreUtil.Menu.confirm(null,List.of(
                mandatory?CoreUtil.C_WARN+"This one always asks, whatever your settings say."
                         :CoreUtil.C_BODY+"Go ahead with it.",
                CoreUtil.C_MUTE+"Closing this window cancels too.")));
        player.openInventory(inventory);
        plugin.settings().marketSound(player,"confirm");
    }

    @EventHandler public void click(InventoryClickEvent event){
        if(!(event.getInventory().getHolder(false) instanceof Holder holder))return;event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player))return;
        Pending action=pending.get(holder.token());if(action==null||!action.player().equals(player.getUniqueId())){player.closeInventory();return;}
        if(event.getRawSlot()==CoreUtil.Menu.CANCEL){pending.remove(holder.token());player.closeInventory();plugin.settings().marketSound(player,"cancel");plugin.getServer().getScheduler().runTask(plugin,action.cancel());}
        else if(event.getRawSlot()==CoreUtil.Menu.CONFIRM){pending.remove(holder.token());player.closeInventory();plugin.getServer().getScheduler().runTask(plugin,action.accept());}
    }

    @EventHandler public void close(InventoryCloseEvent event){if(event.getInventory().getHolder(false) instanceof Holder holder){Pending action=pending.remove(holder.token());if(action!=null&&event.getPlayer() instanceof Player player)plugin.getServer().getScheduler().runTask(plugin,()->{plugin.settings().marketSound(player,"cancel");action.cancel().run();});}}
    @EventHandler public void quit(PlayerQuitEvent event){pending.entrySet().removeIf(entry->entry.getValue().player().equals(event.getPlayer().getUniqueId()));}
}
