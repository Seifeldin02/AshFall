package net.communitysmp.core;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Front end for DonutOrders' /orders buy-order marketplace — fully replaces DonutOrders' own /orders
 *  command on both platforms. DonutOrders' bundled OrderManager.collectStash() only ever accepts
 *  PENDING/COMPLETED/CANCELLED/EXPIRED orders (bytecode-confirmed on the deployed jar, no source available
 *  to patch), so its native "Collect All" permanently reports "nothing to collect" for a partially
 *  fulfilled ACTIVE order — there is no way to fix that from outside a sealed, unmodifiable jar. Every
 *  screen here (browse/create/fulfill/cancel for Java via Paper's native Dialog API, the equivalent via
 *  Geyser forms for Bedrock, plus /myorders' claim-safe stash view for both) drives DonutOrders' own
 *  OrderManager/StorageManager directly via reflection, since DonutOrders exposes no public API — so
 *  behavior and economy stay identical to whatever DonutOrders itself would have done. This is the only
 *  /orders entry point; DonutOrders' own command is unconditionally cancelled in command() below so there
 *  are never two competing systems. */
final class OrdersService implements Listener {
    private record YourOrdersHolder(int page) implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private record OrderDetailHolder(String orderId,int returnPage) implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private record StashHolder(String orderId,int returnPage) implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private static final int PER_PAGE=45;

    private final SMPCore plugin;
    private Boolean available;
    private Object orderManager,storageManager,allowedItemsManager;
    private Method createOrder,fulfillOrder,cancelOrder;
    private Method getAllActiveOrders,getPlayerOrders,getOrderById,getAllowedMaterials;
    private Method orderId,buyerUUID,buyerName,itemTemplate,amountRequested,amountFulfilled,amountRemaining,pricePerItem,orderStatus,formattedExpiry,claimedAt;
    private Method loadStash,clearStash;
    private final Set<UUID> claimInFlight=ConcurrentHashMap.newKeySet();

    OrdersService(SMPCore plugin){this.plugin=plugin;}

    @EventHandler(priority=EventPriority.LOW,ignoreCancelled=true)
    public void command(PlayerCommandPreprocessEvent e){
        Player player=e.getPlayer();
        String[] parts=e.getMessage().substring(1).split(" ",2);
        String cmd=parts[0].toLowerCase(Locale.ROOT);
        if(cmd.equals("myorders")){
            if(!ensureReady())return;
            e.setCancelled(true);
            if(plugin.isBedrock(player))openYourOrdersForm(player,0);else openYourOrdersChest(player,0);
            return;
        }
        if(!Set.of("orders","order","market","donutorders").contains(cmd))return;
        if(!ensureReady())return;
        e.setCancelled(true);
        if(plugin.isBedrock(player))openMain(player);else openMainNative(player);
    }

    private boolean ensureReady(){
        if(available!=null)return available;
        try{
            Plugin donut=Bukkit.getPluginManager().getPlugin("DonutOrders");
            if(donut==null||!donut.isEnabled())return available=false;
            Class<?> donutClass=Class.forName("com.donutorders.DonutOrders");
            Object instance=donutClass.getMethod("getInstance").invoke(null);
            Field orderManagerField=donutClass.getDeclaredField("orderManager");orderManagerField.setAccessible(true);orderManager=orderManagerField.get(instance);
            Field storageManagerField=donutClass.getDeclaredField("storageManager");storageManagerField.setAccessible(true);storageManager=storageManagerField.get(instance);
            allowedItemsManager=donutClass.getMethod("getAllowedItemsManager").invoke(instance);

            Class<?> orderManagerClass=Class.forName("com.donutorders.manager.OrderManager");
            Class<?> storageManagerClass=Class.forName("com.donutorders.storage.StorageManager");
            Class<?> allowedItemsClass=Class.forName("com.donutorders.manager.AllowedItemsManager");
            Class<?> orderClass=Class.forName("com.donutorders.model.Order");

            createOrder=orderManagerClass.getMethod("createOrder",Player.class,ItemStack.class,int.class,double.class,BiConsumer.class);
            fulfillOrder=orderManagerClass.getMethod("fulfillOrder",Player.class,UUID.class,ItemStack[].class,BiConsumer.class);
            cancelOrder=orderManagerClass.getMethod("cancelOrder",Player.class,UUID.class,Consumer.class);
            getAllActiveOrders=storageManagerClass.getMethod("getAllActiveOrders");
            getPlayerOrders=storageManagerClass.getMethod("getPlayerOrders",UUID.class);
            getOrderById=storageManagerClass.getMethod("getOrder",UUID.class);
            getAllowedMaterials=allowedItemsClass.getMethod("getAllowedMaterials");
            loadStash=storageManagerClass.getMethod("loadStash",UUID.class,Consumer.class);
            clearStash=storageManagerClass.getMethod("clearStash",UUID.class,Runnable.class);

            orderId=orderClass.getMethod("getOrderId");buyerUUID=orderClass.getMethod("getBuyerUUID");buyerName=orderClass.getMethod("getBuyerName");
            itemTemplate=orderClass.getMethod("getItemTemplate");amountRequested=orderClass.getMethod("getAmountRequested");amountFulfilled=orderClass.getMethod("getAmountFulfilled");
            amountRemaining=orderClass.getMethod("getAmountRemaining");pricePerItem=orderClass.getMethod("getPricePerItem");orderStatus=orderClass.getMethod("getStatus");formattedExpiry=orderClass.getMethod("getFormattedExpiry");claimedAt=orderClass.getMethod("getClaimedAt");
            return available=true;
        }catch(Throwable error){
            plugin.getLogger().warning("Orders bridge unavailable: "+error.getClass().getSimpleName()+(error.getMessage()!=null?": "+error.getMessage():""));
            return available=false;
        }
    }

    // ───────────────────────── shared item/description helpers ─────────────────────────

    /** Both stored enchants (an actual ENCHANTED_BOOK order) and direct enchants (an enchanted-gear order)
     *  need to be visible — DonutOrders' own display didn't surface either reliably. */
    private List<String> enchantLines(ItemStack item){
        if(item==null||!item.hasItemMeta())return List.of();
        ItemMeta meta=item.getItemMeta();
        Map<Enchantment,Integer> enchants;
        if(meta instanceof EnchantmentStorageMeta esm&&esm.hasStoredEnchants())enchants=esm.getStoredEnchants();
        else if(meta.hasEnchants())enchants=meta.getEnchants();
        else return List.of();
        List<String> lines=new ArrayList<>();
        for(var entry:enchants.entrySet())lines.add(CoreUtil.pretty(entry.getKey().getKey().getKey())+" "+roman(entry.getValue()));
        return lines;
    }
    private String roman(int value){return switch(value){case 1->"I";case 2->"II";case 3->"III";case 4->"IV";case 5->"V";default->Integer.toString(value);};}
    private Object safeInvoke(Method method,Object target){try{return method.invoke(target);}catch(Exception e){return "?";}}
    private String itemLabel(ItemStack template){
        List<String> ench=enchantLines(template);
        return CoreUtil.pretty(template.getType().name())+(ench.isEmpty()?"":" ("+String.join(", ",ench)+")");
    }

    // ───────────────────────── Bedrock: /orders (public browse/create/fulfill — unchanged) ─────────────────────────

    private boolean openMain(Player player){
        try{
            GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());if(connection==null)return false;
            SimpleForm.Builder form=SimpleForm.builder().title("Ashfall Orders").content("Player-driven buy-order marketplace.");
            form.button("Browse Public Orders");form.button("Create New Order");form.button("Your Orders");
            form.validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->{
                int clicked=response.clickedButtonId();
                if(clicked==0)openPublicOrders(player,0);else if(clicked==1)openNewOrderPicker(player);else if(clicked==2)openYourOrdersForm(player,0);
            }));
            return connection.sendForm(form);
        }catch(Throwable error){return false;}
    }

    private void openPublicOrders(Player player,int page){
        try{
            List<Object> all=new ArrayList<>((Collection<?>)getAllActiveOrders.invoke(storageManager));
            GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());if(connection==null)return;
            int perPage=20,from=page*perPage,to=Math.min(all.size(),from+perPage);
            List<Object> slice=from<all.size()?all.subList(from,to):List.of();
            SimpleForm.Builder form=SimpleForm.builder().title("Public Orders").content(all.isEmpty()?"No active buy orders right now.":"Page "+(page+1)+" • tap an order for details.");
            List<UUID> ids=new ArrayList<>();
            for(Object order:slice){form.button(describeOrder(order));ids.add((UUID)orderId.invoke(order));}
            boolean hasPrev=page>0,hasNext=to<all.size();
            if(hasPrev)form.button("« Previous Page");
            if(hasNext)form.button("Next Page »");
            form.button("Back");
            form.validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->{
                int clicked=response.clickedButtonId();
                if(clicked<ids.size()){openOrderDetailForm(player,ids.get(clicked),()->openPublicOrders(player,page));return;}
                int extra=clicked-ids.size();
                if(hasPrev){if(extra==0){openPublicOrders(player,page-1);return;}extra--;}
                if(hasNext&&extra==0){openPublicOrders(player,page+1);return;}
                openMain(player);
            }));
            connection.sendForm(form);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
    }

    private String describeOrder(Object order) throws Exception{
        ItemStack template=(ItemStack)itemTemplate.invoke(order);
        return itemLabel(template)+" x"+amountRemaining.invoke(order)+"\n"+CoreUtil.money((double)pricePerItem.invoke(order))+"/ea • "+buyerName.invoke(order);
    }

    private void openNewOrderPicker(Player player){
        try{
            @SuppressWarnings("unchecked")
            List<Material> materials=(List<Material>)getAllowedMaterials.invoke(allowedItemsManager);
            if(materials.isEmpty()){CoreUtil.error(player,"No items are currently orderable.");return;}
            List<String> names=materials.stream().map(m->CoreUtil.pretty(m.name())).toList();
            GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());if(connection==null)return;
            CustomForm form=CustomForm.builder().title("Create New Order")
                    .dropdown("Item",names)
                    .input("Quantity","e.g. 64","64")
                    .input("Price per item","e.g. 5.0","1.0")
                    .validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->{
                        Material material=materials.get(response.asDropdown(0));
                        int quantity;double price;
                        try{quantity=Integer.parseInt(response.asInput(1).trim());}catch(NumberFormatException ex){CoreUtil.error(player,"Quantity must be a whole number.");return;}
                        try{price=Double.parseDouble(response.asInput(2).trim());}catch(NumberFormatException ex){CoreUtil.error(player,"Price must be a number.");return;}
                        if(quantity<=0||price<=0){CoreUtil.error(player,"Quantity and price must be greater than zero.");return;}
                        confirmNewOrder(player,material,quantity,price);
                    })).build();
            connection.sendForm(form);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
    }
    private void confirmNewOrder(Player player,Material material,int quantity,double price){
        try{
            BiConsumer<Boolean,String> callback=(ok,message)->plugin.getServer().getScheduler().runTask(plugin,()->{
                Player online=plugin.getServer().getPlayer(player.getUniqueId());
                if(online!=null&&message!=null)CoreUtil.msg(online,message);
            });
            createOrder.invoke(orderManager,player,new ItemStack(material),quantity,price,callback);
        }catch(Exception error){CoreUtil.error(player,"Could not create that order.");}
    }

    private void openOrderDetailForm(Player player,UUID id,Runnable back){
        try{
            Object order=getOrderById.invoke(storageManager,id);
            if(order==null){CoreUtil.error(player,"That order is no longer available.");back.run();return;}
            GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());if(connection==null)return;
            ItemStack template=(ItemStack)itemTemplate.invoke(order);
            String statusName=orderStatus.invoke(order).toString();
            boolean own=player.getUniqueId().equals(buyerUUID.invoke(order));
            int fulfilled=(int)amountFulfilled.invoke(order);
            String content="Item: "+itemLabel(template)+"\nRequested: "+amountRequested.invoke(order)+" • Fulfilled: "+fulfilled
                    +"\nPrice: "+CoreUtil.money((double)pricePerItem.invoke(order))+" each\nBuyer: "+buyerName.invoke(order)
                    +"\nStatus: "+CoreUtil.pretty(statusName)+"\nExpires: "+formattedExpiry.invoke(order);
            SimpleForm.Builder form=SimpleForm.builder().title("Order Detail").content(content);
            List<Runnable> actions=new ArrayList<>();
            if(own&&statusName.equals("ACTIVE")){form.button("Cancel Order");actions.add(()->confirmCancel(player,id,back));}
            else if(!own&&statusName.equals("ACTIVE")){form.button("Fulfill Order");actions.add(()->confirmFulfill(player,id,order,back));}
            if(own&&fulfilled>0){form.button("Claim Delivered Items");actions.add(()->confirmClaim(player,id,back));}
            form.button("Back");actions.add(back);
            form.validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->{
                int clicked=response.clickedButtonId();
                if(clicked>=0&&clicked<actions.size())actions.get(clicked).run();
            }));
            connection.sendForm(form);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");back.run();}
    }

    private void confirmFulfill(Player player,UUID id,Object order,Runnable back){
        try{
            ItemStack template=(ItemStack)itemTemplate.invoke(order);
            Material material=template.getType();
            int remaining=(int)amountRemaining.invoke(order);
            int have=0;for(ItemStack stack:player.getInventory().getStorageContents())if(stack!=null&&stack.getType()==material)have+=stack.getAmount();
            int deliver=Math.min(have,remaining);
            if(deliver<=0){CoreUtil.error(player,"You have no "+CoreUtil.pretty(material.name())+" to deliver.");back.run();return;}
            GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());if(connection==null)return;
            SimpleForm.Builder form=SimpleForm.builder().title("Confirm Delivery")
                    .content("Deliver "+deliver+" "+CoreUtil.pretty(material.name())+" toward this order?\nPrice: "+CoreUtil.money((double)pricePerItem.invoke(order))+" each.");
            form.button("Deliver "+deliver);form.button("Cancel");
            form.validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->{
                if(response.clickedButtonId()!=0){back.run();return;}
                ItemStack[] items=takeForDelivery(player,material,deliver);
                try{
                    BiConsumer<Boolean,String> callback=(ok,message)->plugin.getServer().getScheduler().runTask(plugin,()->{
                        if(!Boolean.TRUE.equals(ok))refund(player,items);
                        Player online=plugin.getServer().getPlayer(player.getUniqueId());
                        if(online!=null){if(message!=null)CoreUtil.msg(online,message);back.run();}
                    });
                    fulfillOrder.invoke(orderManager,player,id,items,callback);
                }catch(Exception error){refund(player,items);CoreUtil.error(player,"Delivery failed; your items were returned.");}
            }));
            connection.sendForm(form);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
    }

    private ItemStack[] takeForDelivery(Player player,Material material,int amount){
        List<ItemStack> taken=new ArrayList<>();
        ItemStack[] contents=player.getInventory().getStorageContents();
        for(int i=0;i<contents.length&&amount>0;i++){
            ItemStack stack=contents[i];
            if(stack==null||stack.getType()!=material)continue;
            int take=Math.min(amount,stack.getAmount());
            ItemStack piece=stack.clone();piece.setAmount(take);taken.add(piece);
            int remain=stack.getAmount()-take;
            player.getInventory().setItem(i,remain>0?withAmount(stack,remain):null);
            amount-=take;
        }
        return taken.toArray(new ItemStack[0]);
    }
    private ItemStack withAmount(ItemStack source,int amount){ItemStack clone=source.clone();clone.setAmount(amount);return clone;}
    private void refund(Player player,ItemStack[] items){for(ItemStack item:items)if(item!=null)CoreUtil.give(player,item);}

    private void confirmCancel(Player player,UUID id,Runnable back){
        try{
            GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());if(connection==null)return;
            SimpleForm.Builder form=SimpleForm.builder().title("Cancel Order").content("Cancel this order and refund remaining funds?\nAnything already delivered stays claimable via /myorders.");
            form.button("Yes, Cancel");form.button("No");
            form.validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->{
                if(response.clickedButtonId()!=0){back.run();return;}
                try{
                    Consumer<Boolean> callback=ok->plugin.getServer().getScheduler().runTask(plugin,()->{
                        Player online=plugin.getServer().getPlayer(player.getUniqueId());
                        if(online!=null){CoreUtil.msg(online,Boolean.TRUE.equals(ok)?"Order cancelled.":"That order could not be cancelled.");openYourOrdersForm(online,0);}
                    });
                    cancelOrder.invoke(orderManager,player,id,callback);
                }catch(Exception error){CoreUtil.error(player,"Could not cancel that order.");}
            }));
            connection.sendForm(form);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
    }

    private void confirmClaim(Player player,UUID id,Runnable back){
        try{
            GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());if(connection==null)return;
            SimpleForm.Builder form=SimpleForm.builder().title("Claim Delivered Items").content("Claim everything delivered so far?\nThe order stays active for any remaining quantity.");
            form.button("Claim");form.button("Cancel");
            form.validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->{
                if(response.clickedButtonId()!=0){back.run();return;}
                claimPartial(player,id,back);
            }));
            connection.sendForm(form);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
    }

    // ───────────────────────── Java: /orders native dialog flow ─────────────────────────

    /** Mirrors AccountService's own nativeSupported() check — native Dialogs are an UnstableApiUsage,
     *  client-version-dependent feature, and this server does not run a multi-version proxy, so a mismatch
     *  should only ever happen against a stale client. No chest-based fallback is built for the full
     *  browse/create/fulfill flow (unlike /myorders, which already has one) since that would duplicate this
     *  entire section a second time for a case that shouldn't occur in practice. */
    private boolean nativeOrdersSupported(Player player){return plugin.getConfig().getBoolean("settings.native-dialogs",true)&&player.getProtocolVersion()==Bukkit.getUnsafe().getProtocolVersion();}

    private ActionButton nativeRun(String label,NamedTextColor color,Runnable action){
        return ActionButton.create(Component.text(label,color),Component.empty(),260,DialogAction.customClick(
                (response,audience)->plugin.getServer().getScheduler().runTask(plugin,action),
                ClickCallback.Options.builder().uses(1).build()));
    }
    private List<io.papermc.paper.registry.data.dialog.body.DialogBody> bodyLines(List<String> lines){
        List<io.papermc.paper.registry.data.dialog.body.DialogBody> out=new ArrayList<>();
        for(String line:lines)out.add(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(line,NamedTextColor.GRAY),300));
        return out;
    }

    @SuppressWarnings("UnstableApiUsage")
    private void openMainNative(Player player){
        if(!nativeOrdersSupported(player)){CoreUtil.error(player,"Please update your Minecraft client to use /orders.");return;}
        try{
            List<ActionButton> buttons=List.of(
                    nativeRun("Browse Public Orders",NamedTextColor.AQUA,()->openPublicOrdersNative(player,0)),
                    nativeRun("Create New Order",NamedTextColor.GREEN,()->openNewOrderSearchNative(player)),
                    nativeRun("Your Orders",NamedTextColor.GOLD,()->openYourOrdersChest(player,0)));
            Dialog dialog=Dialog.create(builder->builder.empty()
                    .base(DialogBase.builder(Component.text("Ashfall Orders",NamedTextColor.GOLD)).canCloseWithEscape(true).pause(false).build())
                    .type(DialogType.multiAction(buttons,null,1)));
            player.showDialog(dialog);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
    }

    @SuppressWarnings("UnstableApiUsage")
    private void openNewOrderSearchNative(Player player){
        try{
            ActionButton apply=ActionButton.create(Component.text("Search"),Component.empty(),180,DialogAction.customClick((response,audience)->{
                String query=response.getText("query");
                plugin.getServer().getScheduler().runTask(plugin,()->openNewOrderResultsNative(player,query==null?"":query.trim(),0));
            },ClickCallback.Options.builder().uses(1).build()));
            Dialog dialog=Dialog.create(builder->builder.empty().base(DialogBase.builder(Component.text("Create New Order",NamedTextColor.GOLD))
                    .body(bodyLines(List.of("Search for an item, or leave blank to browse the full catalog.")))
                    .inputs(List.of(io.papermc.paper.registry.data.dialog.input.DialogInput.text("query",Component.text("Item search")).maxLength(40).width(280).build()))
                    .canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.NONE).build())
                    .type(DialogType.confirmation(apply,nativeRun("Back",NamedTextColor.GRAY,()->openMainNative(player)))));
            player.showDialog(dialog);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
    }

    @SuppressWarnings("UnstableApiUsage")
    private void openNewOrderResultsNative(Player player,String query,int page){
        try{
            @SuppressWarnings("unchecked")
            List<Material> all=(List<Material>)getAllowedMaterials.invoke(allowedItemsManager);
            String needle=query.toLowerCase(Locale.ROOT);
            List<Material> matches=needle.isBlank()?all:all.stream()
                    .filter(m->m.name().toLowerCase(Locale.ROOT).contains(needle.replace(' ','_'))||CoreUtil.pretty(m.name()).toLowerCase(Locale.ROOT).contains(needle))
                    .toList();
            if(matches.isEmpty()){CoreUtil.error(player,"No items match \""+query+"\".");openNewOrderSearchNative(player);return;}
            int perPage=15,from=page*perPage,to=Math.min(matches.size(),from+perPage);
            List<Material> slice=matches.subList(from,to);
            List<ActionButton> buttons=new ArrayList<>();
            for(Material material:slice)buttons.add(nativeRun(CoreUtil.pretty(material.name()),NamedTextColor.WHITE,()->openNewOrderAmountNative(player,material,query,page)));
            boolean hasPrev=page>0,hasNext=to<matches.size();
            if(hasPrev)buttons.add(nativeRun("« Previous Page",NamedTextColor.GRAY,()->openNewOrderResultsNative(player,query,page-1)));
            if(hasNext)buttons.add(nativeRun("Next Page »",NamedTextColor.GRAY,()->openNewOrderResultsNative(player,query,page+1)));
            buttons.add(nativeRun("New Search",NamedTextColor.YELLOW,()->openNewOrderSearchNative(player)));
            buttons.add(nativeRun("Back",NamedTextColor.GRAY,()->openMainNative(player)));
            Dialog dialog=Dialog.create(builder->builder.empty()
                    .base(DialogBase.builder(Component.text("Results"+(query.isBlank()?"":" • \""+query+"\""),NamedTextColor.GOLD)).canCloseWithEscape(true).pause(false).build())
                    .type(DialogType.multiAction(buttons,null,1)));
            player.showDialog(dialog);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
    }

    @SuppressWarnings("UnstableApiUsage")
    private void openNewOrderAmountNative(Player player,Material material,String query,int page){
        try{
            ActionButton apply=ActionButton.create(Component.text("Create Order",NamedTextColor.GREEN),Component.empty(),200,DialogAction.customClick((response,audience)->{
                String qtyText=response.getText("quantity"),priceText=response.getText("price");
                plugin.getServer().getScheduler().runTask(plugin,()->{
                    int quantity;double price;
                    try{quantity=Integer.parseInt(qtyText.trim());}catch(Exception ex){CoreUtil.error(player,"Quantity must be a whole number.");return;}
                    try{price=Double.parseDouble(priceText.trim());}catch(Exception ex){CoreUtil.error(player,"Price must be a number.");return;}
                    if(quantity<=0||price<=0){CoreUtil.error(player,"Quantity and price must be greater than zero.");return;}
                    confirmNewOrder(player,material,quantity,price);
                });
            },ClickCallback.Options.builder().uses(1).build()));
            Dialog dialog=Dialog.create(builder->builder.empty().base(DialogBase.builder(Component.text(CoreUtil.pretty(material.name()),NamedTextColor.GOLD))
                    .inputs(List.of(
                            io.papermc.paper.registry.data.dialog.input.DialogInput.text("quantity",Component.text("Quantity")).initial("64").maxLength(6).width(260).build(),
                            io.papermc.paper.registry.data.dialog.input.DialogInput.text("price",Component.text("Price per item")).initial("1.0").maxLength(10).width(260).build()))
                    .canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.NONE).build())
                    .type(DialogType.confirmation(apply,nativeRun("Back",NamedTextColor.GRAY,()->openNewOrderResultsNative(player,query,page)))));
            player.showDialog(dialog);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
    }

    @SuppressWarnings("UnstableApiUsage")
    private void openPublicOrdersNative(Player player,int page){
        try{
            List<Object> all=new ArrayList<>((Collection<?>)getAllActiveOrders.invoke(storageManager));
            int perPage=12,from=page*perPage,to=Math.min(all.size(),from+perPage);
            List<Object> slice=from<all.size()?all.subList(from,to):List.of();
            List<ActionButton> buttons=new ArrayList<>();
            for(Object order:slice){
                UUID oid=(UUID)orderId.invoke(order);
                String label=describeOrder(order).replace('\n',' ');
                buttons.add(nativeRun(label,NamedTextColor.WHITE,()->openOrderDetailNative(player,oid,()->openPublicOrdersNative(player,page))));
            }
            boolean hasPrev=page>0,hasNext=to<all.size();
            if(hasPrev)buttons.add(nativeRun("« Previous Page",NamedTextColor.GRAY,()->openPublicOrdersNative(player,page-1)));
            if(hasNext)buttons.add(nativeRun("Next Page »",NamedTextColor.GRAY,()->openPublicOrdersNative(player,page+1)));
            buttons.add(nativeRun("Back",NamedTextColor.GRAY,()->openMainNative(player)));
            Dialog dialog=Dialog.create(builder->builder.empty()
                    .base(DialogBase.builder(Component.text(all.isEmpty()?"No active orders":"Public Orders • "+(page+1),NamedTextColor.GOLD)).canCloseWithEscape(true).pause(false).build())
                    .type(DialogType.multiAction(buttons,null,1)));
            player.showDialog(dialog);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
    }

    @SuppressWarnings("UnstableApiUsage")
    private void openOrderDetailNative(Player player,UUID id,Runnable back){
        try{
            Object order=getOrderById.invoke(storageManager,id);
            if(order==null){CoreUtil.error(player,"That order is no longer available.");back.run();return;}
            ItemStack template=(ItemStack)itemTemplate.invoke(order);
            String statusName=orderStatus.invoke(order).toString();
            boolean own=player.getUniqueId().equals(buyerUUID.invoke(order));
            int fulfilled=(int)amountFulfilled.invoke(order),requested=(int)amountRequested.invoke(order);
            List<String> lines=new ArrayList<>(List.of(
                    "Item: "+itemLabel(template),
                    "Requested: "+requested+" • Fulfilled: "+fulfilled,
                    "Price: "+CoreUtil.money((double)pricePerItem.invoke(order))+" each",
                    "Buyer: "+buyerName.invoke(order),
                    "Status: "+CoreUtil.pretty(statusName),
                    "Expires: "+formattedExpiry.invoke(order)));
            if(own&&fulfilled>0)lines.add("Use /myorders to view or claim delivered items.");
            List<ActionButton> buttons=new ArrayList<>();
            if(own&&statusName.equals("ACTIVE"))buttons.add(nativeRun("Cancel Order",NamedTextColor.RED,()->confirmCancelNative(player,id,back)));
            else if(!own&&statusName.equals("ACTIVE"))buttons.add(nativeRun("Fulfill Order",NamedTextColor.GREEN,()->confirmFulfillNative(player,id,order,back)));
            buttons.add(nativeRun("Back",NamedTextColor.GRAY,back));
            Dialog dialog=Dialog.create(builder->builder.empty().base(DialogBase.builder(Component.text("Order Detail",NamedTextColor.GOLD))
                    .body(bodyLines(lines))
                    .canCloseWithEscape(true).pause(false).build())
                    .type(DialogType.multiAction(buttons,null,1)));
            player.showDialog(dialog);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");back.run();}
    }

    @SuppressWarnings("UnstableApiUsage")
    private void confirmFulfillNative(Player player,UUID id,Object order,Runnable back){
        try{
            ItemStack template=(ItemStack)itemTemplate.invoke(order);
            Material material=template.getType();
            int remaining=(int)amountRemaining.invoke(order);
            int have=0;for(ItemStack stack:player.getInventory().getStorageContents())if(stack!=null&&stack.getType()==material)have+=stack.getAmount();
            int deliver=Math.min(have,remaining);
            if(deliver<=0){CoreUtil.error(player,"You have no "+CoreUtil.pretty(material.name())+" to deliver.");back.run();return;}
            double pricePer=(double)pricePerItem.invoke(order);
            ActionButton apply=ActionButton.create(Component.text("Deliver "+deliver,NamedTextColor.GREEN),Component.empty(),200,DialogAction.customClick((response,audience)->plugin.getServer().getScheduler().runTask(plugin,()->{
                ItemStack[] items=takeForDelivery(player,material,deliver);
                try{
                    BiConsumer<Boolean,String> callback=(ok,message)->plugin.getServer().getScheduler().runTask(plugin,()->{
                        if(!Boolean.TRUE.equals(ok))refund(player,items);
                        Player online=plugin.getServer().getPlayer(player.getUniqueId());
                        if(online!=null){if(message!=null)CoreUtil.msg(online,message);back.run();}
                    });
                    fulfillOrder.invoke(orderManager,player,id,items,callback);
                }catch(Exception error){refund(player,items);CoreUtil.error(player,"Delivery failed; your items were returned.");}
            }),ClickCallback.Options.builder().uses(1).build()));
            Dialog dialog=Dialog.create(builder->builder.empty().base(DialogBase.builder(Component.text("Confirm Delivery",NamedTextColor.GOLD))
                    .body(bodyLines(List.of("Deliver "+deliver+" "+CoreUtil.pretty(material.name())+" toward this order?","Price: "+CoreUtil.money(pricePer)+" each.")))
                    .canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.NONE).build())
                    .type(DialogType.confirmation(apply,nativeRun("Cancel",NamedTextColor.GRAY,back))));
            player.showDialog(dialog);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
    }

    @SuppressWarnings("UnstableApiUsage")
    private void confirmCancelNative(Player player,UUID id,Runnable back){
        try{
            ActionButton apply=ActionButton.create(Component.text("Yes, Cancel",NamedTextColor.RED),Component.empty(),200,DialogAction.customClick((response,audience)->plugin.getServer().getScheduler().runTask(plugin,()->{
                try{
                    Consumer<Boolean> callback=ok->plugin.getServer().getScheduler().runTask(plugin,()->{
                        Player online=plugin.getServer().getPlayer(player.getUniqueId());
                        if(online!=null){CoreUtil.msg(online,Boolean.TRUE.equals(ok)?"Order cancelled.":"That order could not be cancelled.");openMainNative(online);}
                    });
                    cancelOrder.invoke(orderManager,player,id,callback);
                }catch(Exception error){CoreUtil.error(player,"Could not cancel that order.");}
            }),ClickCallback.Options.builder().uses(1).build()));
            Dialog dialog=Dialog.create(builder->builder.empty().base(DialogBase.builder(Component.text("Cancel Order",NamedTextColor.GOLD))
                    .body(bodyLines(List.of("Cancel this order and refund remaining funds?","Anything already delivered stays claimable via /myorders.")))
                    .canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.NONE).build())
                    .type(DialogType.confirmation(apply,nativeRun("No",NamedTextColor.GRAY,back))));
            player.showDialog(dialog);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
    }

    // ───────────────────────── Bedrock: /myorders form (list) ─────────────────────────

    private void openYourOrdersForm(Player player,int page){
        try{
            List<Object> all=new ArrayList<>((Collection<?>)getPlayerOrders.invoke(storageManager,player.getUniqueId()));
            all.removeIf(this::isHidden);
            GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());if(connection==null)return;
            int perPage=20,from=page*perPage,to=Math.min(all.size(),from+perPage);
            List<Object> slice=from<all.size()?all.subList(from,to):List.of();
            SimpleForm.Builder form=SimpleForm.builder().title("Your Orders").content(all.isEmpty()?"You have no orders yet.":"Page "+(page+1));
            List<UUID> ids=new ArrayList<>();
            for(Object order:slice){
                ItemStack template=(ItemStack)itemTemplate.invoke(order);
                int fulfilled=(int)amountFulfilled.invoke(order);
                String claimTag=fulfilled>0?" • tap for claim options":"";
                form.button(itemLabel(template)+" x"+amountRequested.invoke(order)+"\n"+CoreUtil.pretty(orderStatus.invoke(order).toString())+" • "+fulfilled+"/"+amountRequested.invoke(order)+" filled"+claimTag);
                ids.add((UUID)orderId.invoke(order));
            }
            boolean hasPrev=page>0,hasNext=to<all.size();
            if(hasPrev)form.button("« Previous Page");
            if(hasNext)form.button("Next Page »");
            form.button("Close");
            form.validResultHandler(response->plugin.getServer().getScheduler().runTask(plugin,()->{
                int clicked=response.clickedButtonId();
                if(clicked<ids.size()){openOrderDetailForm(player,ids.get(clicked),()->openYourOrdersForm(player,page));return;}
                int extra=clicked-ids.size();
                if(hasPrev){if(extra==0){openYourOrdersForm(player,page-1);return;}extra--;}
                if(hasNext&&extra==0){openYourOrdersForm(player,page+1);}
            }));
            connection.sendForm(form);
        }catch(Throwable error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
    }


    // ───────────────────────── Java: /myorders chest GUI ─────────────────────────

    /** DonutOrders exposes no delete/archive of its own, so "removed" orders are never actually deleted —
     *  just hidden from these listings via a plain key in SMPCore's own state table (same generic store
     *  already used for relic cooldowns/last-used tracking, no schema migration needed). archiveOrder()
     *  below is the only writer; both listings filter reads. */
    private boolean isHidden(Object order){try{return "true".equals(plugin.db().state("order_hidden:"+orderId.invoke(order)));}catch(Exception ignored){return false;}}
    private void openYourOrdersChest(Player player,int page){
        List<Object> all;
        try{all=new ArrayList<>((Collection<?>)getPlayerOrders.invoke(storageManager,player.getUniqueId()));}
        catch(Exception error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");return;}
        all.removeIf(this::isHidden);
        int pages=Math.max(1,(all.size()+PER_PAGE-1)/PER_PAGE),safePage=Math.max(0,Math.min(page,pages-1));
        int from=safePage*PER_PAGE,to=Math.min(all.size(),from+PER_PAGE);
        Inventory inv=plugin.getServer().createInventory(new YourOrdersHolder(safePage),54,Component.text("Your Orders"+(pages>1?" • "+(safePage+1)+"/"+pages:""),NamedTextColor.DARK_GREEN));
        int slot=0;
        for(int i=from;i<to;i++){
            Object order=all.get(i);
            try{
                ItemStack template=((ItemStack)itemTemplate.invoke(order)).clone();
                int fulfilled=(int)amountFulfilled.invoke(order),requested=(int)amountRequested.invoke(order);
                boolean canClaim=fulfilled>0;
                ItemMeta meta=template.getItemMeta();
                meta.displayName(Component.text(itemLabel(template)+" x"+requested,NamedTextColor.GOLD));
                List<Component> lore=new ArrayList<>();
                lore.add(Component.text(CoreUtil.pretty(orderStatus.invoke(order).toString())+" • "+fulfilled+"/"+requested+" filled",NamedTextColor.GRAY));
                lore.add(Component.text(CoreUtil.money((double)pricePerItem.invoke(order))+" each",NamedTextColor.GRAY));
                if(canClaim)lore.add(Component.text("Click for claim options",NamedTextColor.GREEN));
                else lore.add(Component.text("Click for details",NamedTextColor.DARK_GRAY));
                meta.lore(lore);
                template.setItemMeta(meta);
                inv.setItem(slot,template);
            }catch(Exception ignored){}
            slot++;
        }
        for(int s=slot;s<45;s++)inv.setItem(s,CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,all.isEmpty()?"You have no orders yet":"",List.of()));
        if(safePage>0)inv.setItem(45,CoreUtil.named(Material.ARROW,"Previous Page",List.of()));
        for(int s=46;s<49;s++)inv.setItem(s,CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,"",List.of()));
        inv.setItem(49,CoreUtil.named(Material.BARRIER,"Close",List.of()));
        for(int s=50;s<53;s++)inv.setItem(s,CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,"",List.of()));
        if(safePage+1<pages)inv.setItem(53,CoreUtil.named(Material.ARROW,"Next Page",List.of()));
        this.currentListIds.put(player.getUniqueId(),ids(all,from,to));
        player.openInventory(inv);
    }
    private final Map<UUID,List<UUID>> currentListIds=new HashMap<>();
    private List<UUID> ids(List<Object> orders,int from,int to){
        List<UUID> out=new ArrayList<>();
        for(int i=from;i<to;i++)try{out.add((UUID)orderId.invoke(orders.get(i)));}catch(Exception ignored){out.add(null);}
        return out;
    }

    private void openOrderDetailChest(Player player,UUID id,int returnPage){
        Object order;
        try{order=getOrderById.invoke(storageManager,id);}catch(Exception error){order=null;}
        if(order==null){CoreUtil.error(player,"That order is no longer available.");openYourOrdersChest(player,returnPage);return;}
        try{
            ItemStack template=((ItemStack)itemTemplate.invoke(order)).clone();
            String statusName=orderStatus.invoke(order).toString();
            int fulfilled=(int)amountFulfilled.invoke(order),requested=(int)amountRequested.invoke(order);
            boolean canClaim=fulfilled>0;
            Inventory inv=plugin.getServer().createInventory(new OrderDetailHolder(id.toString(),returnPage),27,Component.text("Order Detail",NamedTextColor.DARK_GREEN));
            ItemMeta meta=template.getItemMeta();
            meta.displayName(Component.text(itemLabel(template),NamedTextColor.GOLD));
            List<Component> lore=new ArrayList<>();
            lore.add(Component.text("Requested: "+requested+" • Fulfilled: "+fulfilled,NamedTextColor.GRAY));
            lore.add(Component.text("Price: "+CoreUtil.money((double)pricePerItem.invoke(order))+" each",NamedTextColor.GRAY));
            lore.add(Component.text("Status: "+CoreUtil.pretty(statusName),NamedTextColor.GRAY));
            lore.add(Component.text("Expires: "+formattedExpiry.invoke(order),NamedTextColor.GRAY));
            meta.lore(lore);template.setItemMeta(meta);
            inv.setItem(13,template);
            for(int s=0;s<27;s++)if(s!=13&&s!=11&&s!=15&&s!=22)inv.setItem(s,CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,"",List.of()));
            if(canClaim)inv.setItem(11,CoreUtil.named(Material.LIME_DYE,"View/Claim Stash",List.of("Opens the delivered items waiting","in escrow. The order stays active","for the rest.")));
            else inv.setItem(11,CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,"",List.of()));
            if(statusName.equals("ACTIVE"))inv.setItem(15,CoreUtil.named(Material.RED_DYE,"Cancel Order",List.of("Refunds unspent escrow.","Anything delivered stays claimable.")));
            else if(statusName.equals("COMPLETED")||statusName.equals("CANCELLED"))inv.setItem(15,CoreUtil.named(Material.HOPPER,"Remove from My Orders",List.of("Archives this order permanently.","Only allowed once its stash is empty.")));
            else inv.setItem(15,CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,"",List.of()));
            inv.setItem(22,CoreUtil.named(Material.ARROW,"Back",List.of()));
            player.openInventory(inv);
        }catch(Exception error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");openYourOrdersChest(player,returnPage);}
    }
    /** The stash-viewing screen for both claiming and simply checking what's waiting. Items shown here are
     *  read directly from the same loadStash() claimPartial() itself drains — "Collect All" cannot say
     *  "nothing to collect" while items are visibly sitting in the grid above it, because both read the
     *  exact same call. All display slots are inert (click() cancels unconditionally and only acts on the
     *  two named button slots), so nothing can be dragged out except through Collect All. */
    private void openStashChest(Player player,UUID id,int returnPage){
        Object order;
        try{order=getOrderById.invoke(storageManager,id);}catch(Exception error){order=null;}
        if(order==null){CoreUtil.error(player,"That order is no longer available.");openYourOrdersChest(player,returnPage);return;}
        try{
            @SuppressWarnings("unchecked")
            Consumer<ItemStack[]> stashConsumer=stash->plugin.getServer().getScheduler().runTask(plugin,()->{
                Player online=plugin.getServer().getPlayer(player.getUniqueId());
                if(online==null)return;
                Inventory inv=plugin.getServer().createInventory(new StashHolder(id.toString(),returnPage),27,Component.text("Stash Contents",NamedTextColor.DARK_GREEN));
                for(int s=0;s<27;s++)inv.setItem(s,CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,"",List.of()));
                int slot=0,total=0;
                if(stash!=null)for(ItemStack item:stash){
                    if(item==null||item.getAmount()<=0)continue;
                    total+=item.getAmount();
                    if(slot<18)inv.setItem(slot++,item.clone());
                }
                if(total>0)inv.setItem(22,CoreUtil.named(Material.LIME_DYE,"Collect All ("+total+")",List.of("Give all of this to your inventory.","The order stays active for the rest.")));
                else inv.setItem(22,CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,"Nothing to collect right now",List.of()));
                inv.setItem(26,CoreUtil.named(Material.ARROW,"Back",List.of()));
                online.openInventory(inv);
            });
            loadStash.invoke(storageManager,id,stashConsumer);
        }catch(Exception error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");openOrderDetailChest(player,id,returnPage);}
    }
    /** "Remove from My Orders" for a terminal (completed/cancelled) order — DonutOrders exposes no delete,
     *  so this just hides it going forward (see isHidden()/openYourOrdersChest()). Re-checks the stash live
     *  rather than trusting the detail screen's earlier canClaim snapshot, since that could be stale by the
     *  time this click lands. */
    private void archiveOrder(Player player,UUID id,int returnPage){
        try{
            @SuppressWarnings("unchecked")
            Consumer<ItemStack[]> stashConsumer=stash->plugin.getServer().getScheduler().runTask(plugin,()->{
                Player online=plugin.getServer().getPlayer(player.getUniqueId());
                if(online==null)return;
                int total=0;if(stash!=null)for(ItemStack item:stash)if(item!=null)total+=item.getAmount();
                if(total>0){CoreUtil.error(online,"Claim the "+total+" item(s) still in its stash before removing this order.");openOrderDetailChest(online,id,returnPage);return;}
                plugin.db().state("order_hidden:"+id,"true");
                CoreUtil.msg(online,"Order removed from My Orders.");
                openYourOrdersChest(online,returnPage);
            });
            loadStash.invoke(storageManager,id,stashConsumer);
        }catch(Exception error){CoreUtil.error(player,"Could not remove that order right now.");}
    }

    @EventHandler public void click(InventoryClickEvent event){
        InventoryHolder raw=event.getInventory().getHolder(false);
        if(!(raw instanceof YourOrdersHolder)&&!(raw instanceof OrderDetailHolder)&&!(raw instanceof StashHolder))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player))return;
        if(raw instanceof YourOrdersHolder holder){
            int slot=event.getRawSlot();
            if(slot<0||slot>=54)return;
            List<UUID> ids=currentListIds.getOrDefault(player.getUniqueId(),List.of());
            if(slot<ids.size()&&ids.get(slot)!=null){openOrderDetailChest(player,ids.get(slot),holder.page());return;}
            if(slot==45&&holder.page()>0){openYourOrdersChest(player,holder.page()-1);return;}
            if(slot==53){openYourOrdersChest(player,holder.page()+1);return;}
            if(slot==49)player.closeInventory();
            return;
        }
        if(raw instanceof StashHolder stashHolder){
            int slot=event.getRawSlot();
            UUID id;try{id=UUID.fromString(stashHolder.orderId());}catch(Exception ignored){return;}
            if(slot==22)claimPartial(player,id,()->openStashChest(player,id,stashHolder.returnPage()));
            else if(slot==26)openOrderDetailChest(player,id,stashHolder.returnPage());
            return;
        }
        OrderDetailHolder holder=(OrderDetailHolder)raw;
        int slot=event.getRawSlot();
        UUID id;try{id=UUID.fromString(holder.orderId());}catch(Exception ignored){return;}
        if(slot==11){
            openStashChest(player,id,holder.returnPage());
        }else if(slot==15){
            Object order;try{order=getOrderById.invoke(storageManager,id);}catch(Exception ex){order=null;}
            String statusName;try{statusName=order==null?"":orderStatus.invoke(order).toString();}catch(Exception ex){statusName="";}
            if(statusName.equals("ACTIVE")){
                player.closeInventory();
                try{
                    Consumer<Boolean> callback=ok->plugin.getServer().getScheduler().runTask(plugin,()->{
                        Player online=plugin.getServer().getPlayer(player.getUniqueId());
                        if(online!=null){CoreUtil.msg(online,Boolean.TRUE.equals(ok)?"Order cancelled.":"That order could not be cancelled.");openYourOrdersChest(online,holder.returnPage());}
                    });
                    cancelOrder.invoke(orderManager,player,id,callback);
                }catch(Exception error){CoreUtil.error(player,"Could not cancel that order.");}
            }else if(statusName.equals("COMPLETED")||statusName.equals("CANCELLED")){
                archiveOrder(player,id,holder.returnPage());
            }
        }else if(slot==22){
            openYourOrdersChest(player,holder.returnPage());
        }
    }

    // ───────────────────────── safe partial claim (shared by both platforms) ─────────────────────────

    /** DonutOrders' own collectStash() hard-refuses while an order is ACTIVE (bytecode-confirmed), and the
     *  order model has no separate "amount already claimed" counter of its own — only a single terminal
     *  claimedAt/claimedBy pair meant for one final claim. Neither fits "claim what's delivered so far,
     *  keep the order open for more." So this claims directly from DonutOrders' own escrow stash instead
     *  (StorageManager.loadStash/clearStash, both public, both already how DonutOrders itself stores
     *  delivered-but-uncollected items) and never touches the order's status, amountFulfilled, or
     *  claimedAt/claimedBy at all. The order stays exactly as DonutOrders' own fulfillOrder() left it —
     *  ACTIVE until fully fulfilled — so cancelling afterward still refunds only the true unfulfilled
     *  remainder via DonutOrders' own unmodified cancelOrder(). claimInFlight guards against a double-claim
     *  race delivering the same stash twice if a client double-fires the click before the async load/clear
     *  round-trip finishes. */
    /** Temporary diagnostic — isolates just the createOrder() reflection call, to see its exact accept/reject
     *  reason without needing a real order. */
    void debugCreate(org.bukkit.command.CommandSender admin,String playerName,String materialName,int qty,double price){
        if(!ensureReady()){CoreUtil.error(admin,"Orders bridge unavailable.");return;}
        Player target=plugin.getServer().getPlayerExact(playerName);
        if(target==null){CoreUtil.error(admin,"That player must be online.");return;}
        Material material=Material.matchMaterial(materialName);
        if(material==null){CoreUtil.error(admin,"Unknown material.");return;}
        BiConsumer<Boolean,String> callback=(created,message)->plugin.getServer().getScheduler().runTask(plugin,()->{
            plugin.getLogger().info("[OrdersDebug] debugCreate callback: player="+playerName+" material="+materialName+" qty="+qty+" price="+price+" created="+created+" message="+message);
            CoreUtil.msg(admin,"debugCreate result: created="+created+" message="+message);
        });
        try{createOrder.invoke(orderManager,target,new ItemStack(material),qty,price,callback);CoreUtil.msg(admin,"Invoked createOrder — watch console/chat for the callback result.");}
        catch(Exception ex){plugin.getLogger().log(java.util.logging.Level.WARNING,"[OrdersDebug] debugCreate invoke threw",ex);CoreUtil.error(admin,"createOrder invoke threw: "+ex);}
    }
    void debugClaim(org.bukkit.command.CommandSender admin,String playerName,String orderIdRaw){
        if(!ensureReady()){CoreUtil.error(admin,"Orders bridge unavailable.");return;}
        Player target=plugin.getServer().getPlayerExact(playerName);
        if(target==null){CoreUtil.error(admin,"That player must be online.");return;}
        UUID id;try{id=UUID.fromString(orderIdRaw);}catch(Exception e){CoreUtil.error(admin,"Bad order id.");return;}
        CoreUtil.msg(admin,"Triggering claimPartial for "+playerName+" / "+id+" — watch console.");
        claimPartial(target,id,()->CoreUtil.msg(admin,"claimPartial finished — check console log."));
    }
    private void claimPartial(Player player,UUID id,Runnable onDone){
        try{
            Object order=getOrderById.invoke(storageManager,id);
            if(order==null){CoreUtil.error(player,"That order is no longer available.");onDone.run();return;}
            if(!player.getUniqueId().equals(buyerUUID.invoke(order))){CoreUtil.error(player,"That is not your order.");onDone.run();return;}
            if(!claimInFlight.add(id)){CoreUtil.error(player,"A claim for this order is already in progress.");onDone.run();return;}
            ItemStack template=((ItemStack)itemTemplate.invoke(order)).clone();
            @SuppressWarnings("unchecked")
            Consumer<ItemStack[]> stashConsumer=stash->plugin.getServer().getScheduler().runTask(plugin,()->{
                try{
                    int count=0;
                    if(stash!=null)for(ItemStack s:stash)if(s!=null)count+=s.getAmount();
                    Player online=plugin.getServer().getPlayer(player.getUniqueId());
                    if(count<=0){
                        if(online!=null)CoreUtil.error(online,"Nothing is currently available to collect.");
                        claimInFlight.remove(id);onDone.run();return;
                    }
                    if(online!=null)for(ItemStack s:stash)if(s!=null)CoreUtil.give(online,s);
                    int claimedCount=count;
                    Runnable afterClear=()->plugin.getServer().getScheduler().runTask(plugin,()->{
                        claimInFlight.remove(id);
                        Player p2=plugin.getServer().getPlayer(player.getUniqueId());
                        if(p2!=null)CoreUtil.msg(p2,"Claimed "+claimedCount+" "+itemLabel(template)+". The order stays active for the rest.");
                        onDone.run();
                    });
                    try{clearStash.invoke(storageManager,id,afterClear);}
                    catch(Exception ex){
                        plugin.getLogger().log(java.util.logging.Level.WARNING,"[Orders] clearStash invoke threw for order "+id,ex);
                        claimInFlight.remove(id);
                        if(online!=null)CoreUtil.error(online,"Items were given, but the stash could not be cleared — contact an admin if this repeats.");
                        onDone.run();
                    }
                }catch(Exception ex){
                    plugin.getLogger().log(java.util.logging.Level.WARNING,"[Orders] claimPartial stash handling threw for order "+id,ex);
                    claimInFlight.remove(id);
                    Player p2=plugin.getServer().getPlayer(player.getUniqueId());
                    if(p2!=null)CoreUtil.error(p2,"Could not finish claiming that order.");
                    onDone.run();
                }
            });
            loadStash.invoke(storageManager,id,stashConsumer);
        }catch(Exception error){
            claimInFlight.remove(id);
            plugin.getLogger().log(java.util.logging.Level.WARNING,"[Orders] claimPartial threw for order "+id,error);
            CoreUtil.error(player,"Could not claim that order.");
            onDone.run();
        }
    }
}
