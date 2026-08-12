package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
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

/** Front end for DonutOrders' /orders buy-order marketplace. This build bundles TWO separate GUI systems
 *  under com.donutorders — the older chest-based com.donutorders.gui.* classes (PublicOrdersGUI,
 *  NewOrderGUI, OrderDetailGUI, YourOrdersGUI, ...) and the newer native-Dialog-rendered
 *  com.donutorders.ui.PlatformOrdersUI (full item catalog, search, enchantment/level selection) — and
 *  GUIManager is the one stable facade in front of both, chosen per-call, not per-class:
 *  openPublicOrders(Player,int) always opens the chest PublicOrdersGUI (bytecode-confirmed) — that IS the
 *  "clean chest GUI" /orders is meant to show. openOrderPlacement(Player) delegates to
 *  PlatformOrdersUI.openPlacement() when GUIManager's platformUI field is set (it is, on this branded
 *  build) — that IS the native catalog/search/enchantment creation flow /order is meant to show.
 *  openNewOrderPicker(Player), despite the similar name, always opens the plain chest NewOrderGUI with no
 *  catalog search or enchantment step — it looks like the right method and is not; do not use it for
 *  /order. None of this is rebuilt here, only invoked via GUIManager reflection, since DonutOrders exposes
 *  no public API. The one part DonutOrders itself cannot do correctly: its bundled
 *  OrderManager.collectStash() only ever accepts PENDING/COMPLETED/CANCELLED/EXPIRED orders
 *  (bytecode-confirmed, no source available to patch it), so its native "Collect All" permanently reports
 *  "nothing to collect" for a partially fulfilled ACTIVE order. /myorders is SMPCore's own dedicated
 *  claim-safe screen for both platforms, reading directly from DonutOrders' own escrow stash
 *  (loadStash/clearStash) without touching order status at all. */
final class OrdersService implements Listener {
    private record YourOrdersHolder(int page) implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private record OrderDetailHolder(String orderId,int returnPage) implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private record StashHolder(String orderId,int returnPage) implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private static final int PER_PAGE=45;

    private final SMPCore plugin;
    private Boolean available;
    private Object orderManager,storageManager,allowedItemsManager,guiManager;
    private Method createOrder,fulfillOrder,cancelOrder;
    private Method getAllActiveOrders,getPlayerOrders,getOrderById,getAllowedMaterials;
    private Method orderId,buyerUUID,buyerName,itemTemplate,amountRequested,amountFulfilled,amountRemaining,pricePerItem,orderStatus,formattedExpiry,claimedAt;
    private Method loadStash,clearStash;
    private Method openPublicOrdersNative,openOrderPlacement,openDonutOrderDetail,openNewOrderPickerFallback;
    private Method getGuiState;
    private Field contextOrderIdField;
    private final Set<UUID> claimInFlight=ConcurrentHashMap.newKeySet();
    private final Map<UUID,UUID> blockNextStashOpen=new ConcurrentHashMap<>();

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
        /** /order (create) is deliberately split from /orders (browse) — two direct entry points into
         *  DonutOrders' own native screens instead of a combined command that shows a menu first. Both
         *  still just call straight into GUIManager; Bedrock keeps going through the existing combined
         *  Geyser form since nothing there was reported broken. market/donutorders alias to browse, matching
         *  DonutOrders' own most common usage for those names.
         *  openOrderPlacement(Player), not openNewOrderPicker(Player), is the real entry point for the rich
         *  native creation screen — bytecode-confirmed (javap -c against the deployed 1.3.0 jar):
         *  openNewOrderPicker() unconditionally opens the old chest-based NewOrderGUI, no catalog search or
         *  enchantment step at all. openOrderPlacement() checks GUIManager's own platformUI field and, when
         *  set (it is, on this branded build), delegates to PlatformOrdersUI.openPlacement() — the actual
         *  native root→categories→search→results→configure→enchantments→levels→confirm flow — only falling
         *  back to the plain chest picker if platformUI were ever null. */
        if(cmd.equals("order")){
            if(!ensureReady())return;
            e.setCancelled(true);
            if(plugin.isBedrock(player)){openMain(player);return;}
            try{
                if(openOrderPlacement!=null)openOrderPlacement.invoke(guiManager,player);
                else openNewOrderPickerFallback.invoke(guiManager,player);
            }
            catch(Exception error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
            return;
        }
        if(!Set.of("orders","market","donutorders").contains(cmd))return;
        if(!ensureReady())return;
        e.setCancelled(true);
        if(plugin.isBedrock(player)){openMain(player);return;}
        try{openPublicOrdersNative.invoke(guiManager,player,0);}
        catch(Exception error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");}
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
            Field guiManagerField=donutClass.getDeclaredField("guiManager");guiManagerField.setAccessible(true);guiManager=guiManagerField.get(instance);
            allowedItemsManager=donutClass.getMethod("getAllowedItemsManager").invoke(instance);

            Class<?> orderManagerClass=Class.forName("com.donutorders.manager.OrderManager");
            Class<?> storageManagerClass=Class.forName("com.donutorders.storage.StorageManager");
            Class<?> allowedItemsClass=Class.forName("com.donutorders.manager.AllowedItemsManager");
            Class<?> orderClass=Class.forName("com.donutorders.model.Order");
            Class<?> guiManagerClass=Class.forName("com.donutorders.manager.GUIManager");

            createOrder=orderManagerClass.getMethod("createOrder",Player.class,ItemStack.class,int.class,double.class,BiConsumer.class);
            fulfillOrder=orderManagerClass.getMethod("fulfillOrder",Player.class,UUID.class,ItemStack[].class,BiConsumer.class);
            cancelOrder=orderManagerClass.getMethod("cancelOrder",Player.class,UUID.class,Consumer.class);
            getAllActiveOrders=storageManagerClass.getMethod("getAllActiveOrders");
            getPlayerOrders=storageManagerClass.getMethod("getPlayerOrders",UUID.class);
            getOrderById=storageManagerClass.getMethod("getOrder",UUID.class);
            getAllowedMaterials=allowedItemsClass.getMethod("getAllowedMaterials");
            loadStash=storageManagerClass.getMethod("loadStash",UUID.class,Consumer.class);
            clearStash=storageManagerClass.getMethod("clearStash",UUID.class,Runnable.class);
            openPublicOrdersNative=guiManagerClass.getMethod("openPublicOrders",Player.class,int.class);
            openNewOrderPickerFallback=guiManagerClass.getMethod("openNewOrderPicker",Player.class);
            /** openOrderPlacement(Player) — the PlatformOrdersUI-backed rich native creation flow — only
             *  exists on the branded 1.3.0 build manually kept on this server; a generic upstream 1.5.0 jar
             *  (bytecode-confirmed, e.g. what production currently runs) has no com.donutorders.ui package
             *  at all and never had this method. Version-specific reflection stays in its own try/catch so a
             *  missing method here degrades /order to the plain chest picker instead of aborting the entire
             *  bridge — /orders and /myorders (the actual partial-claim fix) do not depend on this at all and
             *  must keep working regardless of which DonutOrders build is present. Same reasoning for
             *  getState/openOrderDetail/contextOrderId below: present on both versions as far as verified,
             *  but the active/claimed-order stash-click protection is a nice-to-have, not core functionality,
             *  so it degrades to a no-op (see the null checks in watchDonutOrderDetailClick) rather than
             *  ever blocking ensureReady() from succeeding. */
            try{openOrderPlacement=guiManagerClass.getMethod("openOrderPlacement",Player.class);}catch(Exception ignored){openOrderPlacement=null;}
            try{
                getGuiState=guiManagerClass.getMethod("getState",UUID.class);
                openDonutOrderDetail=guiManagerClass.getMethod("openOrderDetail",Player.class,UUID.class);
                Class<?> playerGuiStateClass=Class.forName("com.donutorders.manager.GUIManager$PlayerGUIState");
                contextOrderIdField=playerGuiStateClass.getField("contextOrderId");
            }catch(Exception ignored){getGuiState=null;openDonutOrderDetail=null;contextOrderIdField=null;}

            orderId=orderClass.getMethod("getOrderId");buyerUUID=orderClass.getMethod("getBuyerUUID");buyerName=orderClass.getMethod("getBuyerName");
            itemTemplate=orderClass.getMethod("getItemTemplate");amountRequested=orderClass.getMethod("getAmountRequested");amountFulfilled=orderClass.getMethod("getAmountFulfilled");
            amountRemaining=orderClass.getMethod("getAmountRemaining");pricePerItem=orderClass.getMethod("getPricePerItem");orderStatus=orderClass.getMethod("getStatus");formattedExpiry=orderClass.getMethod("getFormattedExpiry");claimedAt=orderClass.getMethod("getClaimedAt");
            return available=true;
        }catch(Throwable error){
            plugin.getLogger().warning("Orders bridge unavailable: "+error.getClass().getSimpleName()+(error.getMessage()!=null?": "+error.getMessage():""));
            return available=false;
        }
    }

    // ───────────────────────── DonutOrders' own native GUI: block a real bug in it ─────────────────────────

    /** DonutOrders' own OrderDetailGUI.build() (bytecode-confirmed via javap) only puts a real "Collect"
     *  chest icon at slot 11 for terminal orders (PENDING/COMPLETED/CANCELLED/EXPIRED); for an ACTIVE order
     *  it leaves slot 11 as an ordinary filler pane, visually identical to the rest of the gray glass. But
     *  its handleClick() checks the slot number ONLY — bipush 11 → GUIManager.openCollectStash(...)
     *  unconditionally, with no order-status check at all — so that "ordinary" pane is still fully
     *  clickable and opens the (bytecode-confirmed-broken-for-ACTIVE) Collect Stash screen anyway. That
     *  screen can show real items and still be unable to actually collect them, since
     *  OrderManager.collectStash() itself only ever accepts terminal statuses.
     *  This is entirely inside a sealed DonutOrders class with no source available — cannot be patched
     *  directly. DonutOrders' own click listener runs at EventPriority.HIGH with ignoreCancelled=false
     *  (bytecode-confirmed), so cancelling this click doesn't stop it from processing the click and opening
     *  the Collect Stash screen regardless — cancelling here is still worth doing anyway (prevents vanilla's
     *  own default click behavior, e.g. picking up whatever's actually sitting in that slot), it just isn't
     *  sufficient on its own. GUIManager.getState(player).contextOrderId identifies exactly which order is
     *  open here, no title parsing needed for that part.
     *  First attempt cancelled the resulting InventoryOpenEvent outright — live-confirmed that's actively
     *  dangerous: cancelling the OPEN stops the client from ever seeing the new inventory, but DonutOrders'
     *  own PlayerGUIState still gets updated to "viewing Collect Stash" inside the same async callback,
     *  desyncing what the client is actually looking at (still OrderDetailGUI) from what DonutOrders thinks
     *  is open. The next click then gets routed through the wrong GUI's handleClick(), corrupting the whole
     *  screen — reported live as "double-click breaks the chest GUI and makes it unusable."
     *  Second attempt let the Collect Stash screen open for real, then closed it back out — no more
     *  corruption, but that closes the *entire* GUI (kicks the player out to no inventory at all), reported
     *  live as worse than doing nothing: the requirement is that this slot behaves exactly like the ordinary
     *  filler glass around it, i.e. truly nothing happens.
     *  Landed on: let it open for real (still keeps DonutOrders' state consistent, avoiding the original
     *  corruption bug), then immediately reopen the *same* Order Detail screen via DonutOrders' own
     *  GUIManager.openOrderDetail(Player,UUID) instead of closing. This both restores DonutOrders' own state
     *  to what it actually was (OrderDetailGUI, matching contextOrderId, not left pointing at Collect Stash)
     *  and returns the player to exactly where they were — a brief screen swap rather than a silent no-op
     *  (DonutOrders unconditionally opens something new on every slot-11 click, bytecode-confirmed no early
     *  exit exists to intercept before that happens — no code here can prevent that first open from
     *  occurring at all, only react once it has), but the net effect the player experiences is "the glass
     *  didn't do anything," not "the whole screen closed." blockNextStashOpen entries auto-expire after 2s
     *  (not consumed on first match) so a rapid double/triple-click, which can fire openCollectStash more
     *  than once, catches every resulting open, not just the first. */
    @EventHandler(priority=EventPriority.LOW)
    public void watchDonutOrderDetailClick(InventoryClickEvent event){
        if(event.getRawSlot()!=11||!(event.getWhoClicked() instanceof Player player))return;
        if(guiManager==null||getGuiState==null)return;
        try{
            Object state=getGuiState.invoke(guiManager,player.getUniqueId());
            if(state==null)return;
            UUID contextOrderId=(UUID)contextOrderIdField.get(state);
            if(contextOrderId==null)return;
            Object order=getOrderById.invoke(storageManager,contextOrderId);
            if(order==null)return;
            /** CLAIMED (a real 6th OrderStatus value alongside ACTIVE/PENDING/COMPLETED/CANCELLED/EXPIRED,
             *  confirmed via the enum — easy to miss since nothing else in this class checked for it) is
             *  DonutOrders' own one-shot terminal marker set the moment its native collectStash() actually
             *  succeeds once. A second slot-11 click on an already-CLAIMED order live-confirmed DonutOrders'
             *  own "[Security] ... already claimed ... Replay attack blocked" rejection — functioning
             *  correctly, but with a client-side message that can still read as success despite nothing
             *  being given, which is confusing and was never something ACTIVE-only blocking here protected
             *  against. Same redirect-back applies: there's never anything useful to do by re-entering
             *  Collect Stash on an order DonutOrders itself already considers fully resolved. */
            String orderStatusName=orderStatus.invoke(order).toString();
            if("ACTIVE".equals(orderStatusName)||"CLAIMED".equals(orderStatusName)){
                event.setCancelled(true);
                UUID playerId=player.getUniqueId();
                blockNextStashOpen.put(playerId,contextOrderId);
                plugin.getServer().getScheduler().runTaskLater(plugin,()->blockNextStashOpen.remove(playerId),40L);
            }
        }catch(Exception ignored){}
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void closeDonutStashOpenForActiveOrder(InventoryOpenEvent event){
        if(!(event.getPlayer() instanceof Player player))return;
        UUID orderId=blockNextStashOpen.get(player.getUniqueId());
        if(orderId==null)return;
        String title=event.getView().getTitle();
        if(title==null||!title.contains("ᴄᴏʟʟᴇᴄᴛ"))return;
        plugin.getServer().getScheduler().runTask(plugin,()->{
            Player online=plugin.getServer().getPlayer(player.getUniqueId());
            if(online==null)return;
            try{openDonutOrderDetail.invoke(guiManager,online,orderId);}
            catch(Exception error){online.closeInventory();}
        });
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
    /** amountFulfilled on the order itself is DonutOrders' own cumulative-forever counter — it never
     *  decreases once items are claimed via claimPartial() (which deliberately never touches it, see that
     *  method's own comment), so "fulfilled>0" alone stays true forever after a single delivery, long after
     *  everything has actually been collected. That showed up as two live bugs: /myorders listing an order
     *  as having claimable items when its stash was genuinely empty, and the "View/Claim Stash" button in
     *  the detail screen staying lit (and openable, to an empty stash) for an ACTIVE order indefinitely.
     *  order_claimed_total tracks what claimPartial() has actually paid out so far, the same lightweight
     *  per-order state-table pattern already used for order_hidden — "fulfilled minus claimed so far" is
     *  the true pending amount, and is what every "can this be claimed right now" check should use instead
     *  of the raw cumulative counter. */
    private int claimedTotal(UUID id){try{return Integer.parseInt(plugin.db().state("order_claimed_total:"+id));}catch(Exception ignored){return 0;}}
    private boolean hasPendingStash(Object order) throws Exception{
        int fulfilled=(int)amountFulfilled.invoke(order);
        UUID id=(UUID)orderId.invoke(order);
        return fulfilled-claimedTotal(id)>0;
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
            if(own&&hasPendingStash(order)){form.button("Claim Delivered Items");actions.add(()->confirmClaim(player,id,back));}
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
                String claimTag=hasPendingStash(order)?" • tap for claim options":"";
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
    /** One-line summary on login of orders that expired while the player was away.
     *
     *  Deliberately the same shape as the auction summary: read the CURRENT state rather than caching
     *  expiry events. A cache would have to catch every expiry, survive restarts and be cleared exactly
     *  once, whereas counting what is presently sitting in EXPIRED cannot miss anything, cannot deliver
     *  twice, and collapses to one line however many expired.
     *
     *  Runs off the main thread because getPlayerOrders() reaches DonutOrders' storage layer, and a join
     *  handler must not block on that. Silent when DonutOrders is absent or its API does not match. */
    void loginSummary(Player player){
        if(storageManager==null||getPlayerOrders==null||orderStatus==null)return;
        java.util.UUID id=player.getUniqueId();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,()->{
            int expired=0;
            try{
                for(Object order:(Collection<?>)getPlayerOrders.invoke(storageManager,id)){
                    if(isHidden(order))continue;
                    Object status=orderStatus.invoke(order);
                    if(status!=null&&"EXPIRED".equalsIgnoreCase(String.valueOf(status)))expired++;
                }
            }catch(Throwable error){
                plugin.getLogger().fine("[Orders] login summary skipped: "+error);
                return;
            }
            if(expired<=0)return;
            int count=expired;
            plugin.getServer().getScheduler().runTask(plugin,()->{
                if(!player.isOnline())return;
                CoreUtil.msg(player,count+" order"+(count==1?"":"s")+" expired while you were away. Use /orders to collect what is waiting.");
            });
        });
    }
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
                boolean canClaim=hasPendingStash(order);
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

    /** Was a synchronous render using hasPendingStash() (fulfilled minus a locally-tracked claimed-so-far
     *  counter) — live-confirmed that leaves every order claimed before that counter existed permanently
     *  showing as pending, since nothing ever updates the counter for a claim it never saw happen. This is
     *  the actual per-order decision point ("View/Claim Stash" button vs plain filler), so it needs to be
     *  right every time, not just after one extra click into the stash screen specifically. Now loads the
     *  real live stash first (same loadStash() call the stash screen and claimPartial() both already trust)
     *  and recomputes order_claimed_total directly from ground truth (fulfilled minus what's still actually
     *  sitting in the stash) on every view, rather than incrementally maintaining a counter that can drift
     *  or start out stale. */
    private void openOrderDetailChest(Player player,UUID id,int returnPage){
        Object orderLookup;
        try{orderLookup=getOrderById.invoke(storageManager,id);}catch(Exception error){orderLookup=null;}
        if(orderLookup==null){CoreUtil.error(player,"That order is no longer available.");openYourOrdersChest(player,returnPage);return;}
        final Object order=orderLookup;
        try{
            @SuppressWarnings("unchecked")
            Consumer<ItemStack[]> stashConsumer=stash->plugin.getServer().getScheduler().runTask(plugin,()->{
                Player online=plugin.getServer().getPlayer(player.getUniqueId());
                if(online==null)return;
                int stashTotal=0;
                if(stash!=null)for(ItemStack item:stash)if(item!=null)stashTotal+=item.getAmount();
                try{
                    int fulfilled=(int)amountFulfilled.invoke(order);
                    int correctClaimed=Math.max(0,fulfilled-stashTotal);
                    if(correctClaimed!=claimedTotal(id))plugin.db().state("order_claimed_total:"+id,Integer.toString(correctClaimed));
                }catch(Exception ignored){}
                try{renderOrderDetailChest(online,order,id,returnPage,stashTotal>0);}
                catch(Exception error){CoreUtil.error(online,"The orders marketplace is temporarily unavailable.");openYourOrdersChest(online,returnPage);}
            });
            loadStash.invoke(storageManager,id,stashConsumer);
        }catch(Exception error){CoreUtil.error(player,"The orders marketplace is temporarily unavailable.");openYourOrdersChest(player,returnPage);}
    }
    private void renderOrderDetailChest(Player player,Object order,UUID id,int returnPage,boolean canClaim) throws Exception{
        ItemStack template=((ItemStack)itemTemplate.invoke(order)).clone();
        String statusName=orderStatus.invoke(order).toString();
        int fulfilled=(int)amountFulfilled.invoke(order),requested=(int)amountRequested.invoke(order);
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
        else if(statusName.equals("COMPLETED")||statusName.equals("CANCELLED")||statusName.equals("CLAIMED"))inv.setItem(15,CoreUtil.named(Material.HOPPER,"Remove from My Orders",List.of("Archives this order permanently.","Only allowed once its stash is empty.")));
        else inv.setItem(15,CoreUtil.named(Material.GRAY_STAINED_GLASS_PANE,"",List.of()));
        inv.setItem(22,CoreUtil.named(Material.ARROW,"Back",List.of()));
        player.openInventory(inv);
    }
    /** The stash-viewing screen for both claiming and simply checking what's waiting. Items shown here are
     *  read directly from the same loadStash() claimPartial() itself drains — "Collect All" cannot say
     *  "nothing to collect" while items are visibly sitting in the grid above it, because both read the
     *  exact same call. All display slots are inert (click() cancels unconditionally and only acts on the
     *  two named button slots), so nothing can be dragged out except through Collect All. */
    private void openStashChest(Player player,UUID id,int returnPage){
        Object orderLookup;
        try{orderLookup=getOrderById.invoke(storageManager,id);}catch(Exception error){orderLookup=null;}
        if(orderLookup==null){CoreUtil.error(player,"That order is no longer available.");openYourOrdersChest(player,returnPage);return;}
        final Object order=orderLookup;
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
                /** Same ground-truth reconciliation as openOrderDetailChest — recompute order_claimed_total
                 *  from fulfilled minus what's actually still in the stash, every time this screen loads,
                 *  rather than only correcting the empty case. */
                try{int fulfilled=(int)amountFulfilled.invoke(order);int correctClaimed=Math.max(0,fulfilled-total);if(correctClaimed!=claimedTotal(id))plugin.db().state("order_claimed_total:"+id,Integer.toString(correctClaimed));}catch(Exception ignored){}
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
            }else if(statusName.equals("COMPLETED")||statusName.equals("CANCELLED")||statusName.equals("CLAIMED")){
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
                    plugin.db().state("order_claimed_total:"+id,Integer.toString(claimedTotal(id)+claimedCount));
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
