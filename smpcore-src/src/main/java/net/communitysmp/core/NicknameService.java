package net.communitysmp.core;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.server.TabCompleteEvent;
import net.kyori.adventure.text.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Small optional bridge to Nicked's supported API. SMPCore never changes the
 * player's real UUID or persistent identity.
 */
final class NicknameService implements Listener {
    private final SMPCore plugin;
    private final List<String> randomNames;
    private Object api;
    private Method displayName,isNicked,getNickInfo,nickPlayer,unnickPlayer;
    private Object pluginCause;
    private Field refresherField;
    private Method refreshProfile;

    NicknameService(SMPCore plugin){
        this.plugin=plugin;
        randomNames=plugin.getConfig().getStringList("nickname.random-names").stream()
                .filter(name->name.matches("[A-Za-z0-9_]{3,16}")).toList();
        connect();
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private void connect(){
        try{
            Class<?> provider=Class.forName("com.nicked.api.NickedAPIProvider");
            api=provider.getMethod("getAPIOrNull").invoke(null);
            if(api==null)return;
            Class<?> apiType=Class.forName("com.nicked.api.NickedAPI");
            Class<?> causeType=Class.forName("com.nicked.nick.NickCause");
            displayName=apiType.getMethod("getDisplayName",UUID.class);
            isNicked=apiType.getMethod("isNicked",UUID.class);
            getNickInfo=apiType.getMethod("getNickInfo",UUID.class);
            nickPlayer=apiType.getMethod("nickPlayer",UUID.class,String.class,causeType);
            unnickPlayer=apiType.getMethod("unnickPlayer",UUID.class,causeType);
            pluginCause=Enum.valueOf((Class<? extends Enum>)causeType.asSubclass(Enum.class),"PLUGIN");
            Class<?> managerType=Class.forName("com.nicked.nick.NickManager");
            refresherField=managerType.getDeclaredField("refresher");
            refresherField.setAccessible(true);
            Class<?> refresherType=Class.forName("com.nicked.packet.PlayerRefresher");
            Class<?> skinType=Class.forName("com.nicked.nick.SkinData");
            refreshProfile=refresherType.getMethod("refresh",Player.class,skinType,String.class);
            plugin.getLogger().info("Nicked integration active; disguised players retain their real SMPCore identity.");
        }catch(Throwable error){
            api=null;
            plugin.getLogger().warning("Nicked integration is unavailable: "+error.getMessage());
        }
    }

    boolean available(){return api!=null;}
    boolean isNicked(Player player){
        if(api==null)return false;
        try{return (boolean)isNicked.invoke(api,player.getUniqueId());}catch(Throwable ignored){return false;}
    }
    String displayName(Player player){
        if(api==null)return player.getName();
        try{
            String value=(String)displayName.invoke(api,player.getUniqueId());
            return value==null||value.isBlank()?player.getName():value;
        }catch(Throwable ignored){return player.getName();}
    }
    String displayName(String realName){
        if(api==null||realName==null||realName.isBlank())return realName;
        try{
            UUID id=plugin.getServer().getOfflinePlayer(realName).getUniqueId();
            String value=(String)displayName.invoke(api,id);
            return value==null||value.isBlank()?realName:value;
        }catch(Throwable ignored){return realName;}
    }
    /** The shared "resolve a typed name to a player" choke point used by /msg, /tpa, /bounty, /f, /stats and
     *  more — a vanished or silently-spectating admin is excluded here unconditionally so every one of those
     *  commands treats them as nonexistent without needing its own separate check. Admin-only tooling (like
     *  /smp spectate itself) intentionally resolves targets via a direct getPlayerExact lookup instead of this
     *  method, so admins retain the ability to act on a vanished/spectating colleague when genuinely needed. */
    Player findVisiblePlayer(String name){
        if(name==null)return null;
        for(Player online:plugin.getServer().getOnlinePlayers()){
            if(!displayName(online).equalsIgnoreCase(name))continue;
            if(hiddenFromPublic(online))return null;
            return online;
        }
        Player real=plugin.getServer().getPlayerExact(name);
        if(real==null||isNicked(real)||hiddenFromPublic(real))return null;
        return real;
    }
    private boolean hiddenFromPublic(Player player){return plugin.adminTools().isHiddenFromPublic(player);}
    String auditName(Player player){String shown=displayName(player);return shown.equals(player.getName())?shown:shown+" ("+player.getName()+")";}
    String skinTexture(Player player){
        Object skin=nickedSkin(player);
        if(skin==null)return null;
        try{
            Object value=skin.getClass().getMethod("value").invoke(skin);
            return value instanceof String text&&!text.isBlank()?text:null;
        }catch(Throwable ignored){return null;}
    }
    String skinSignature(Player player){
        Object skin=nickedSkin(player);
        if(skin==null)return null;
        try{
            Object value=skin.getClass().getMethod("signature").invoke(skin);
            return value instanceof String text&&!text.isBlank()?text:null;
        }catch(Throwable ignored){return null;}
    }
    private Object nickedSkin(Player player){
        if(api==null||!isNicked(player))return null;
        try{
            Object optional=getNickInfo.invoke(api,player.getUniqueId());
            Object info=((java.util.Optional<?>)optional).orElse(null);
            return info==null?null:info.getClass().getMethod("nickedSkin").invoke(info);
        }catch(Throwable ignored){return null;}
    }
    boolean command(Player player,String[] args){
        if(!plugin.isAdmin(player)){CoreUtil.error(player,"Only ADMIN can use disguises.");return true;}
        if(api==null){CoreUtil.error(player,"The disguise service is unavailable.");return true;}
        if(args.length==0){CoreUtil.msg(player,isNicked(player)?"Disguised as "+displayName(player)+". Use /nickname off.":"Usage: /nickname <Minecraft name|random|off>");return true;}
        String requested=args[0];
        try{
            if(SetLike.off(requested)){unnickPlayer.invoke(api,player.getUniqueId(),pluginCause);CoreUtil.msg(player,"Your disguise was removed.");}
            else{
                if(requested.equalsIgnoreCase("random")){
                    if(randomNames.isEmpty()){CoreUtil.error(player,"No random disguise names are configured.");return true;}
                    requested=randomNames.get(ThreadLocalRandom.current().nextInt(randomNames.size()));
                }
                if(!requested.matches("[A-Za-z0-9_]{3,16}")){CoreUtil.error(player,"Use a valid 3-16 character Minecraft name.");return true;}
                for(Player online:plugin.getServer().getOnlinePlayers()){
                    if(online.getUniqueId().equals(player.getUniqueId()))continue;
                    if(online.getName().equalsIgnoreCase(requested)||displayName(online).equalsIgnoreCase(requested)){
                        CoreUtil.error(player,"That disguise name is already in use.");return true;
                    }
                }
                if(player.getName().equalsIgnoreCase(requested)){CoreUtil.error(player,"That is already your real account name.");return true;}
                nickPlayer.invoke(api,player.getUniqueId(),requested,pluginCause);
                CoreUtil.msg(player,"Disguise requested as "+requested+". Skin resolution may take a moment.");
            }
            for(long delay:new long[]{10L,40L,100L})plugin.getServer().getScheduler().runTaskLater(plugin,()->{
                if(!player.isOnline())return;
                refreshPublicProfile(player);
                plugin.tab().refreshNow();
                for(Player online:plugin.getServer().getOnlinePlayers())online.updateCommands();
            },delay);
        }catch(Throwable error){CoreUtil.error(player,"The disguise could not be applied.");plugin.getLogger().warning("Nickname API error: "+error.getMessage());}
        return true;
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void join(PlayerJoinEvent event){
        event.joinMessage(publicText(event.joinMessage()));
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{
            if(!event.getPlayer().isOnline())return;
            for(Player online:plugin.getServer().getOnlinePlayers())if(isNicked(online))refreshPublicProfile(online);
            plugin.tab().refreshNow();
            for(Player online:plugin.getServer().getOnlinePlayers())online.updateCommands();
        },30L);
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event){event.quitMessage(publicText(event.quitMessage()));}

    @EventHandler(priority=EventPriority.MONITOR)
    public void death(PlayerDeathEvent event){event.deathMessage(publicText(event.deathMessage()));}

    @EventHandler(priority=EventPriority.MONITOR)
    public void changedWorld(PlayerChangedWorldEvent event){
        if(isNicked(event.getPlayer()))plugin.getServer().getScheduler().runTaskLater(plugin,()->refreshPublicProfile(event.getPlayer()),5L);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void tabComplete(TabCompleteEvent event){
        if(!(event.getSender() instanceof Player viewer)||plugin.isAdmin(viewer))return;
        event.setCompletions(cleanCompletions(event.getCompletions()));
    }

    /** Paper routes real client-typed command-argument completion (e.g. "/msg <TAB>") through this async
     *  event rather than the legacy synchronous TabCompleteEvent above, which mainly covers older/compat
     *  paths — without this handler a hidden/spectating admin's name leaked back into command completion
     *  even though the legacy handler correctly filtered chat-side suggestions and other plugins' events. */
    @EventHandler(priority=EventPriority.HIGH)
    public void asyncTabComplete(com.destroystokyo.paper.event.server.AsyncTabCompleteEvent event){
        if(!(event.getSender() instanceof Player viewer)||plugin.isAdmin(viewer))return;
        event.setCompletions(cleanCompletions(event.getCompletions()));
    }

    private List<String> cleanCompletions(List<String> completions){
        LinkedHashSet<String> clean=new LinkedHashSet<>();
        for(String suggestion:completions){
            Player matched=plugin.getServer().getPlayerExact(suggestion);
            if(matched!=null&&hiddenFromPublic(matched))continue;
            String visible=suggestion;
            for(Player online:plugin.getServer().getOnlinePlayers()){
                if(!isNicked(online))continue;
                String real=online.getName(),nick=displayName(online);
                if(visible.equalsIgnoreCase(real))visible=nick;
                else visible=visible.replaceAll("(?i)\\b"+java.util.regex.Pattern.quote(real)+"\\b",java.util.regex.Matcher.quoteReplacement(nick));
            }
            clean.add(visible);
        }
        return List.copyOf(clean);
    }

    private void refreshPublicProfile(Player player){
        if(api==null||refreshProfile==null||refresherField==null||!isNicked(player))return;
        try{
            Object optional=getNickInfo.invoke(api,player.getUniqueId());
            Object info=((java.util.Optional<?>)optional).orElse(null);if(info==null)return;
            Object skin=info.getClass().getMethod("nickedSkin").invoke(info);
            String name=(String)info.getClass().getMethod("nickedName").invoke(info);
            Object refresher=refresherField.get(api);
            refreshProfile.invoke(refresher,player,skin,name);
        }catch(Throwable error){
            plugin.getLogger().warning("Could not refresh the public disguise profile for "+player.getName()+": "+error.getClass().getSimpleName());
        }
    }

    Component publicText(Component message){
        if(message==null)return null;
        Component result=message;
        for(Player online:plugin.getServer().getOnlinePlayers())if(isNicked(online)){
            String real=online.getName(),shown=displayName(online);
            result=result.replaceText(builder->builder.matchLiteral(real).replacement(shown));
        }
        return result;
    }

    private static final class SetLike {
        static boolean off(String value){String lower=value.toLowerCase(Locale.ROOT);return lower.equals("off")||lower.equals("clear")||lower.equals("reset");}
    }
}
