package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class MessagingService {
    private final SMPCore plugin;
    private final Map<UUID,UUID> replies=new HashMap<>();

    MessagingService(SMPCore plugin){this.plugin=plugin;}

    boolean message(Player sender,String[] args){
        if(args.length>=1&&(args[0].equalsIgnoreCase("block")||args[0].equalsIgnoreCase("unblock")))return blockCommand(sender,args);
        if(args.length<2){CoreUtil.error(sender,"Usage: /msg <player> <message>");return true;}
        Player target=plugin.nicknames().findVisiblePlayer(args[0]);if(target==null||target.equals(sender)){CoreUtil.error(sender,"That player is not available.");return true;}
        String text=join(args,1);deliver(sender,target,text);return true;
    }
    private boolean blockCommand(Player sender,String[] args){
        boolean blocking=args[0].equalsIgnoreCase("block");
        if(args.length<2){CoreUtil.error(sender,"Usage: /msg "+(blocking?"block":"unblock")+" <player>");return true;}
        Player target=plugin.nicknames().findVisiblePlayer(args[1]);
        if(target==null||target.equals(sender)){CoreUtil.error(sender,"That player is not available.");return true;}
        String senderId=CoreUtil.id(sender),targetId=CoreUtil.id(target);
        if(blocking){
            if(!plugin.db().blockPlayer(senderId,targetId)){CoreUtil.error(sender,"You have already blocked "+plugin.nicknames().displayName(target)+".");return true;}
            CoreUtil.msg(sender,"Blocked "+plugin.nicknames().displayName(target)+". They can no longer message you.");
        }else{
            if(!plugin.db().unblockPlayer(senderId,targetId)){CoreUtil.error(sender,"You haven't blocked "+plugin.nicknames().displayName(target)+".");return true;}
            CoreUtil.msg(sender,"Unblocked "+plugin.nicknames().displayName(target)+".");
        }
        return true;
    }

    boolean reply(Player sender,String[] args){
        if(args.length==0){CoreUtil.error(sender,"Usage: /r <message>");return true;}UUID targetId=replies.get(sender.getUniqueId());Player target=targetId==null?null:plugin.getServer().getPlayer(targetId);
        if(target==null||target.equals(sender)){CoreUtil.error(sender,"There is nobody online to reply to.");return true;}deliver(sender,target,join(args,0));return true;
    }

    void quit(Player player){replies.remove(player.getUniqueId());}

    private void deliver(Player sender,Player target,String message){
        if(!plugin.settings().privateMessages(sender)){CoreUtil.error(sender,"Private Messages are disabled in /settings.");return;}
        if(!plugin.settings().privateMessages(target)||plugin.db().hasBlocked(CoreUtil.id(target),CoreUtil.id(sender))){CoreUtil.error(sender,plugin.nicknames().displayName(target)+" is not accepting private messages.");return;}
        String clean=message.trim();if(clean.isBlank()){CoreUtil.error(sender,"Message cannot be empty.");return;}if(clean.length()>256)clean=clean.substring(0,256);
        plugin.afk().notifyIfAfk(sender,target);
        sender.sendMessage(Component.text("You → "+plugin.nicknames().displayName(target)+": ",NamedTextColor.GRAY).append(Component.text(clean,NamedTextColor.WHITE)));
        target.sendMessage(Component.text(plugin.nicknames().displayName(sender)+" → You: ",NamedTextColor.GRAY).append(Component.text(clean,NamedTextColor.WHITE)));
        plugin.db().logChat("DM",CoreUtil.id(sender),sender.getName(),CoreUtil.id(target),target.getName(),clean);
        replies.put(sender.getUniqueId(),target.getUniqueId());replies.put(target.getUniqueId(),sender.getUniqueId());
    }

    private String join(String[] args,int start){StringBuilder value=new StringBuilder();for(int i=start;i<args.length;i++){if(value.length()>0)value.append(' ');value.append(args[i]);}return value.toString();}
}
