package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Color;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class FactionService {
    record Claim(Database.FactionRow faction,int size,int minX,int maxX,int minZ,int maxZ){boolean contains(Location l){return l.getWorld()!=null&&l.getWorld().getName().equals(faction.world())&&l.getBlockX()>=minX&&l.getBlockX()<=maxX&&l.getBlockZ()>=minZ&&l.getBlockZ()<=maxZ;}boolean overlaps(Claim other){return faction.world().equals(other.faction.world())&&minX<=other.maxX&&maxX>=other.minX&&minZ<=other.maxZ&&maxZ>=other.minZ;}}
    private record Invite(long factionId,long expires){}
    private record RelationsHolder(long faction) implements InventoryHolder {@Override public Inventory getInventory(){return null;}}
    private record RelationHolder(long faction,long other) implements InventoryHolder {@Override public Inventory getInventory(){return null;}}
    private record MainHolder(UUID player) implements InventoryHolder {@Override public Inventory getInventory(){return null;}}
    private record MembersHolder(long faction) implements InventoryHolder {@Override public Inventory getInventory(){return null;}}
    private final SMPCore plugin;
    private final Database db;
    private final TeleportService teleports;
    private final Map<String,Invite> invites=new ConcurrentHashMap<>();
    private final Set<UUID> chatMode=ConcurrentHashMap.newKeySet();
    private BukkitTask borderTask;
    private List<Claim> claims=List.of();

    FactionService(SMPCore plugin,TeleportService teleports){this.plugin=plugin;this.db=plugin.db();this.teleports=teleports;refreshClaims();borderTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::renderEnabledBorders,20L,10L);}
    void shutdown(){if(borderTask!=null)borderTask.cancel();}
    void join(Player player){}
    void refreshClaims(){List<Integer> sizes=plugin.getConfig().getIntegerList("claims.sizes");List<Claim> next=new ArrayList<>();for(Database.FactionRow f:db.factions()){if(f.tier()<0)continue;int size=sizes.get(Math.min(f.tier(),sizes.size()-1));int minX=f.coreX()-size/2,minZ=f.coreZ()-size/2;next.add(new Claim(f,size,minX,minX+size-1,minZ,minZ+size-1));}claims=List.copyOf(next);if(plugin.netWorth()!=null)plugin.netWorth().claimsChanged();}
    Claim claimAt(Location l){for(Claim c:claims)if(c.contains(l))return c;return null;}
    boolean nearClaim(Location location,int radius){if(location.getWorld()==null)return false;int x=location.getBlockX(),z=location.getBlockZ();String world=location.getWorld().getName();for(Claim claim:claims)if(claim.faction.world().equals(world)&&x>=claim.minX()-radius&&x<=claim.maxX()+radius&&z>=claim.minZ()-radius&&z<=claim.maxZ()+radius)return true;return false;}
    Claim claimOf(Database.FactionRow f){for(Claim c:claims)if(c.faction.id()==f.id())return c;return null;}
    boolean overlaps(String world,int minX,int maxX,int minZ,int maxZ){for(Claim c:claims)if(c.faction.world().equals(world)&&c.minX()<=maxX&&c.maxX()>=minX&&c.minZ()<=maxZ&&c.maxZ()>=minZ)return true;return false;}
    boolean isMember(Player p,Database.FactionRow faction){Database.FactionRow own=db.factionOf(CoreUtil.id(p));return own!=null&&own.id()==faction.id();}
    boolean isLeader(Player p,Database.FactionRow faction){return faction!=null&&faction.leader().equals(CoreUtil.id(p));}
    boolean isCoLeader(Player player,Database.FactionRow faction){return faction!=null&&CoreUtil.id(player).equals(db.coLeader(faction.id()));}
    boolean canManage(Player player,Database.FactionRow faction){return isLeader(player,faction)||isCoLeader(player,faction);}
    boolean sameFaction(Player a,Player b){Database.FactionRow fa=db.factionOf(CoreUtil.id(a)),fb=db.factionOf(CoreUtil.id(b));return fa!=null&&fb!=null&&fa.id()==fb.id();}
    boolean friendly(Player a,Player b){Database.FactionRow first=db.factionOf(CoreUtil.id(a)),second=db.factionOf(CoreUtil.id(b));if(first==null||second==null)return false;if(first.id()==second.id())return true;Database.RelationRow row=db.relation(first.id(),second.id());return row!=null&&row.active()&&Set.of("TRUCE","ALLIANCE").contains(row.type());}
    boolean doorAccess(Player player,Database.FactionRow territory){Database.FactionRow own=db.factionOf(CoreUtil.id(player));if(own==null)return false;if(own.id()==territory.id())return true;Database.RelationRow row=db.relation(own.id(),territory.id());return row!=null&&"ALLIANCE".equals(row.type());}
    boolean storageAccess(Player player,Database.FactionRow territory){Database.FactionRow own=db.factionOf(CoreUtil.id(player));if(own==null)return false;if(own.id()==territory.id())return true;Database.RelationRow row=db.relation(own.id(),territory.id());return row!=null&&row.sharedStorage();}
    /** Personal homes: settable — and, since a claim can change hands after a home was set, teleportable to —
     *  only inside your own faction's claim, unclaimed wilderness, or an allied faction's claim that BOTH
     *  sides have opted into via /f homes (same bilateral-approval pattern as shared storage). No relation, or
     *  a TRUCE, is not enough on its own — a home is a much bigger foothold than shared storage access. */
    boolean homeAccess(Player player,Database.FactionRow territory){Database.FactionRow own=db.factionOf(CoreUtil.id(player));if(own==null)return false;if(own.id()==territory.id())return true;Database.RelationRow row=db.relation(own.id(),territory.id());return row!=null&&row.sharedHomes();}
    int activeRelationCount(long faction){return(int)db.relations(faction).stream().filter(Database.RelationRow::active).count();}

    boolean command(Player p,String[] args){
        if(args.length==0){openMain(p);return true;}
        String sub=args[0].toLowerCase(Locale.ROOT);String pid=CoreUtil.id(p);Database.FactionRow f=db.factionOf(pid);
        switch(sub){
            case "create"->{if(args.length<2){CoreUtil.error(p,"Usage: /f create <name>");return true;}if(f!=null){CoreUtil.error(p,"Leave your current faction first.");return true;}String name=args[1];if(!name.matches("[A-Za-z0-9_]{3,16}")){CoreUtil.error(p,"Faction names use 3-16 letters, numbers, or underscores.");return true;}if(db.factionByName(name)!=null){CoreUtil.error(p,"That faction name is taken.");return true;}Database.FactionRow made=db.createFaction(name,pid,p.getName(),p.getLocation());refreshClaims();plugin.progress().factionCreated(made,p.getName());plugin.progress().factionMembership(p);CoreUtil.msg(p,"Created "+made.name()+" ["+made.tag()+"]. No land was claimed automatically.");CoreUtil.msg(p,"The faction leader should stand at the desired center and use /f claim. Change the TAB tag with /f tag <tag>.");}
            case "claim"->{if(!requireFactionManager(p,f))return true;if(f.tier()>=0){CoreUtil.error(p,"Your faction already has a claim. Expand it with /f expand.");return true;}if(!p.getWorld().getName().equals(plugin.getConfig().getString("claims.world","world"))){CoreUtil.error(p,"Faction land can only be claimed in the Overworld.");return true;}int rememberedTier=rememberedTier(f.id());int size=rememberedTier>0?sizeFor(rememberedTier):plugin.getConfig().getInt("claims.starting-size",50);Claim proposed=claimFor(f,p.getLocation(),size);if(plugin.spawnClaims().overlaps(proposed.faction.world(),proposed.minX(),proposed.maxX(),proposed.minZ(),proposed.maxZ())){CoreUtil.error(p,"This claim would overlap protected spawn.");return true;}for(Claim c:claims)if(proposed.overlaps(c)){CoreUtil.error(p,"This "+size+" x "+size+" claim would overlap "+c.faction.name()+". Move farther away.");return true;}db.setFactionCoreTier(f.id(),p.getLocation().getBlockX(),p.getLocation().getBlockZ(),Math.max(0,rememberedTier));db.state("faction_remembered_tier:"+f.id(),"");refreshClaims();Database.FactionRow claimed=db.faction(f.id());plugin.progress().factionClaim(claimed,size);db.markFactionClaimedLand(claimed.id());grantFactionMissions(claimed);broadcast(claimed,"The leader claimed a protected "+size+" x "+size+" territory centered at X "+p.getLocation().getBlockX()+", Z "+p.getLocation().getBlockZ()+".");}
            case "unclaim"->{if(!requireFactionManager(p,f))return true;if(f.tier()<0){CoreUtil.error(p,"Your faction has no active claim.");return true;}if(args.length<2||!args[1].equalsIgnoreCase("confirm")){CoreUtil.msg(p,"This releases your current territory as wilderness. Your next /f claim keeps the same "+sizeFor(f.tier())+" x "+sizeFor(f.tier())+" size instead of starting over. Confirm with /f unclaim confirm.");return true;}db.state("faction_remembered_tier:"+f.id(),Integer.toString(f.tier()));db.setFactionTier(f.id(),-1);refreshClaims();broadcast(f,"The faction released its territory; it is now wilderness. /f claim elsewhere restores the same size.");}
            case "invite"->{if(!requireFactionManager(p,f))return true;if(args.length<2){CoreUtil.error(p,"Usage: /f invite <player>");return true;}Player target=plugin.nicknames().findVisiblePlayer(args[1]);if(target==null){CoreUtil.error(p,"That player is not online.");return true;}if(db.factionOf(CoreUtil.id(target))!=null){CoreUtil.error(p,"That player is already in a faction.");return true;}if(db.factionMemberCount(f.id())>=plugin.getConfig().getInt("factions.max-members",3)){CoreUtil.error(p,"Faction member limit reached (3).");return true;}long expiry=System.currentTimeMillis()+plugin.getConfig().getLong("factions.invite-seconds",120)*1000;invites.put(CoreUtil.id(target),new Invite(f.id(),expiry));CoreUtil.msg(p,"Invited "+plugin.nicknames().displayName(target)+".");CoreUtil.msg(target,plugin.nicknames().displayName(p)+" invited you to "+f.name()+". Use /f accept.");}
            case "accept"->{if(f!=null){CoreUtil.error(p,"You are already in a faction.");return true;}Invite in=invites.remove(pid);if(in==null||in.expires<System.currentTimeMillis()){CoreUtil.error(p,"You have no active faction invitation.");return true;}Database.FactionRow invited=db.faction(in.factionId);if(invited==null||db.factionMemberCount(invited.id())>=plugin.getConfig().getInt("factions.max-members",3)){CoreUtil.error(p,"That invitation is no longer available.");return true;}db.addMember(invited.id(),pid,p.getName());plugin.progress().factionMembership(p);grantFactionMissions(invited);broadcast(invited,plugin.nicknames().displayName(p)+" joined the faction.");}
            case "kick"->{if(!requireFactionManager(p,f))return true;if(args.length<2){CoreUtil.error(p,"Usage: /f kick <member>");return true;}Player visible=plugin.nicknames().findVisiblePlayer(args[1]);String target=CoreUtil.id(visible==null?args[1]:visible.getName());Database.FactionRow tf=db.factionOf(target);if(tf==null||tf.id()!=f.id()){CoreUtil.error(p,"That player is not in your faction.");return true;}if(target.equals(f.leader())||isCoLeader(p,f)&&target.equals(db.coLeader(f.id()))){CoreUtil.error(p,"Only the leader may remove faction leadership roles.");return true;}db.removeMember(target);broadcast(f,plugin.nicknames().displayName(visible==null?args[1]:visible.getName())+" was removed from the faction.");}
            case "leader"->{if(!requireFactionLeader(p,f))return true;if(args.length<2){CoreUtil.error(p,"Usage: /f leader <member>");return true;}Player visible=plugin.nicknames().findVisiblePlayer(args[1]);String target=CoreUtil.id(visible==null?args[1]:visible.getName());Database.FactionRow tf=db.factionOf(target);if(tf==null||tf.id()!=f.id()){CoreUtil.error(p,"That player is not in your faction.");return true;}db.setLeader(f.id(),target);broadcast(f,plugin.nicknames().displayName(visible==null?args[1]:visible.getName())+" is now the faction leader.");}
            case "coleader","co-leader"->{if(!requireFactionLeader(p,f))return true;if(args.length<2){String current=db.coLeader(f.id());CoreUtil.msg(p,current==null?"No Co-Leader is appointed.":"Current Co-Leader: "+plugin.nicknames().displayName(current));return true;}if(args[1].equalsIgnoreCase("remove")||args[1].equalsIgnoreCase("none")){db.setCoLeader(f.id(),null);broadcast(f,"The Co-Leader role was cleared.");return true;}Player visible=plugin.nicknames().findVisiblePlayer(args[1]);String target=CoreUtil.id(visible==null?args[1]:visible.getName());Database.FactionRow tf=db.factionOf(target);if(tf==null||tf.id()!=f.id()||target.equals(f.leader())){CoreUtil.error(p,"Choose a normal member of your faction.");return true;}db.setCoLeader(f.id(),target);broadcast(f,plugin.nicknames().displayName(visible==null?args[1]:visible.getName())+" is now the faction Co-Leader.");}
            case "leave"->{if(f==null){CoreUtil.error(p,"You are not in a faction.");return true;}if(isLeader(p,f)){CoreUtil.error(p,"Transfer leadership or use /f disband confirm.");return true;}db.removeMember(pid);CoreUtil.msg(p,"You left "+f.name()+".");broadcast(f,plugin.nicknames().displayName(p)+" left the faction.");}
            case "disband"->{if(!requireFactionLeader(p,f))return true;if(args.length<2||!args[1].equalsIgnoreCase("confirm")){CoreUtil.error(p,"This removes the claim and faction bank. Confirm with /f disband confirm.");return true;}plugin.progress().factionDisbanded(f);if(f.balance()>0)plugin.bank().creditSink(f.balance(),pid,"FACTION_DISBAND");db.deleteFaction(f.id());refreshClaims();CoreUtil.msg(p,"Faction disbanded. The land is now wilderness.");}
            case "info"->{Database.FactionRow show=f;if(args.length>1)show=db.factionByName(args[1]);if(show==null){CoreUtil.error(p,"Faction not found. Create one with /f create <name>.");return true;}Claim c=claimOf(show);CoreUtil.msg(p,"Faction: "+show.name()+" ["+show.tag()+"] | Members: "+String.join(", ",db.factionMembers(show.id()).stream().map(plugin.nicknames()::displayName).toList()));CoreUtil.msg(p,show.name()+" — Net Worth: "+CoreUtil.money(plugin.netWorth().value(show.id())));CoreUtil.msg(p,"Bank: "+CoreUtil.money(show.balance())+" | Claim: "+(c==null?"none — leader use /f claim":c.size+" x "+c.size+" (tier "+show.tier()+")")+" | Storage: direct outsider access blocked, TNT raiding allowed");}
            case "borders"->{if(f==null){CoreUtil.error(p,"You are not in a faction.");return true;}boolean current=borderEnabled(p),next=args.length>1?args[1].equalsIgnoreCase("on"):!current;if(args.length>1&&!Set.of("on","off").contains(args[1].toLowerCase(Locale.ROOT))){CoreUtil.error(p,"Usage: /f borders [on|off]");return true;}db.preference(pid,"faction_borders",Boolean.toString(next));CoreUtil.msg(p,"Faction borders "+(next?"enabled.":"disabled."));if(!next)p.sendActionBar(Component.empty());}
            case "networth"->{if(f==null){CoreUtil.error(p,"You are not in a faction.");return true;}plugin.netWorth().open(p,f);}
            case "leaderboard"->{
                int page=1;if(args.length>1)try{page=Integer.parseInt(args[1]);}catch(NumberFormatException ignored){}
                int size=10,safe=Math.max(1,page),from=(safe-1)*size;List<NetWorthService.Row> rows=plugin.netWorth().rankings();
                CoreUtil.msg(p,"Faction leaderboard • page "+safe);
                if(from>=rows.size()){CoreUtil.msg(p,"No entries on this page.");return true;}
                int i=from+1;for(NetWorthService.Row row:rows.subList(from,Math.min(rows.size(),from+size)))CoreUtil.msg(p,(i++)+". "+row.name()+" — Net Worth: "+CoreUtil.money(row.value()));
            }
            case "relations"->{if(f==null){CoreUtil.error(p,"You are not in a faction.");return true;}openRelations(p,f);}
            case "ally","truce"->{if(!requireFactionManager(p,f))return true;if(args.length<2){CoreUtil.error(p,"Usage: /f "+sub+" <faction>");return true;}requestRelation(p,f,args[1],sub.equals("ally")?"ALLIANCE":"TRUCE");}
            case "storage"->{if(!requireFactionManager(p,f))return true;if(args.length<2){CoreUtil.error(p,"Usage: /f storage <allied faction>");return true;}toggleStorage(p,f,args[1]);}
            case "homeaccess"->{if(!requireFactionManager(p,f))return true;if(args.length<2){CoreUtil.error(p,"Usage: /f homeaccess <allied faction>");return true;}toggleHomeAccess(p,f,args[1]);}
            case "locate"->{if(args.length<2){CoreUtil.error(p,"Usage: /f locate <player>");return true;}Player target=plugin.nicknames().findVisiblePlayer(args[1]);if(target==null||!friendly(p,target)){CoreUtil.error(p,"You may only locate an online member of your faction, ally, or truce.");return true;}String shown=plugin.nicknames().displayName(target);if(!p.getWorld().equals(target.getWorld())){CoreUtil.msg(p,shown+" is in "+CoreUtil.pretty(target.getWorld().getEnvironment().name())+".");return true;}double dx=target.getX()-p.getX(),dz=target.getZ()-p.getZ();String direction=cardinal(Math.toDegrees(Math.atan2(-dx,dz)));CoreUtil.msg(p,shown+": "+Math.round(Math.hypot(dx,dz))+" blocks "+direction+".");}
            case "tag"->{if(!requireFactionLeader(p,f))return true;if(args.length<2){CoreUtil.msg(p,"Faction TAB tag: ["+f.tag()+"]. Change it with /f tag <2-4 letters/numbers>.");return true;}String tag=args[1].toUpperCase(Locale.ROOT);if(!tag.matches("[A-Z0-9]{2,4}")){CoreUtil.error(p,"Faction tags use 2-4 letters or numbers.");return true;}if(!db.setFactionTag(f.id(),tag)){CoreUtil.error(p,"That faction tag is already taken.");return true;}broadcast(db.faction(f.id()),"Faction TAB tag changed to ["+tag+"].");}
            case "deposit"->{if(f==null){CoreUtil.error(p,"You are not in a faction.");return true;}if(args.length<2){CoreUtil.error(p,"Usage: /f deposit <amount>");return true;}double amount=CoreUtil.parseMoney(args[1]);if(amount<0||!db.changeBalance(pid,-amount)){CoreUtil.error(p,"Invalid amount or insufficient personal funds.");return true;}db.changeFactionBalance(f.id(),amount);CoreUtil.msg(p,"Deposited "+CoreUtil.money(amount)+". Faction balance: "+CoreUtil.money(db.faction(f.id()).balance()));}
            case "withdraw"->{if(!requireFactionLeader(p,f))return true;if(args.length<2){CoreUtil.error(p,"Usage: /f withdraw <amount>");return true;}double amount=CoreUtil.parseMoney(args[1]);if(amount<0||!db.changeFactionBalance(f.id(),-amount)){CoreUtil.error(p,"Invalid amount or insufficient faction funds.");return true;}plugin.creditEarned(pid,amount,"FACTION_WITHDRAWAL");CoreUtil.msg(p,"Withdrew "+CoreUtil.money(amount)+" from the faction bank.");}
            case "expand"->{if(!requireFactionManager(p,f))return true;if(f.tier()<0){CoreUtil.error(p,"Claim your free starting territory first with /f claim.");return true;}List<Integer> sizes=plugin.getConfig().getIntegerList("claims.sizes");List<Integer> costs=plugin.getConfig().getIntegerList("claims.expansion-costs");if(f.tier()>=sizes.size()-1){CoreUtil.msg(p,"Your claim is already at the maximum size.");return true;}int next=f.tier()+1,cost=costs.get(f.tier());Claim proposed=claimFor(f,null,sizes.get(next));if(plugin.spawnClaims().overlaps(proposed.faction.world(),proposed.minX(),proposed.maxX(),proposed.minZ(),proposed.maxZ())){CoreUtil.error(p,"This expansion would overlap protected spawn.");return true;}for(Claim c:claims)if(c.faction.id()!=f.id()&&proposed.overlaps(c)){CoreUtil.error(p,"Expansion to "+sizes.get(next)+" x "+sizes.get(next)+" would overlap "+c.faction.name()+".");return true;}CoreUtil.msg(p,"Claim expansion: "+sizes.get(f.tier())+" x "+sizes.get(f.tier())+" → "+sizes.get(next)+" x "+sizes.get(next)+" | Cost: "+CoreUtil.money(cost)+" | Faction balance: "+CoreUtil.money(f.balance()));if(args.length<2||!args[1].equalsIgnoreCase("confirm")){CoreUtil.msg(p,"Review the boundary, then use /f expand confirm.");return true;}if(!plugin.bank().payFaction(f.id(),cost,pid,"FACTION_CLAIM_"+next)){CoreUtil.error(p,"The faction bank cannot afford this expansion.");return true;}db.recordEconomy(pid,"UPGRADE_SINK",-cost,"FACTION_CLAIM_"+next);db.setFactionTier(f.id(),next);refreshClaims();Database.FactionRow expanded=db.faction(f.id());plugin.progress().factionExpanded(expanded,sizes.get(next));broadcast(expanded,"Territory expanded to "+sizes.get(next)+" x "+sizes.get(next)+".");}
            case "history"->{int page=1;if(args.length>1)try{page=Integer.parseInt(args[1]);}catch(NumberFormatException ignored){}plugin.progress().history(p,true,page);}
            case "sethome"->{if(f==null){CoreUtil.error(p,"You are not in a faction.");return true;}String name=args.length>1?args[1]:"home";if(!name.matches("[A-Za-z0-9_]{1,16}")){CoreUtil.error(p,"Home names use letters, numbers, or underscores.");return true;}if(!isMember(p,f)||claimAt(p.getLocation())==null||claimAt(p.getLocation()).faction.id()!=f.id()){CoreUtil.error(p,"Faction homes must be set inside your protected claim.");return true;}List<Database.HomeRow> homes=db.homes(Long.toString(f.id()),"FACTION");if(db.home(Long.toString(f.id()),"FACTION",name)==null&&homes.size()>=f.homeSlots()){CoreUtil.error(p,"No free faction home slot. Use /f buyhome.");return true;}db.putHome(Long.toString(f.id()),"FACTION",name,p.getLocation());db.markFactionSetHome(f.id());grantFactionMissions(f);CoreUtil.msg(p,"Faction home '"+name+"' set.");}
            case "home"->{if(f==null){CoreUtil.error(p,"You are not in a faction.");return true;}String name=args.length>1?args[1]:"home";Database.HomeRow home=db.home(Long.toString(f.id()),"FACTION",name);if(home==null){CoreUtil.error(p,"Faction home not found. Use /f homes.");return true;}
                /** A faction home is only valid while it still sits inside THIS faction's own claim. If the base was
                 *  destroyed and the faction moved/unclaimed (so the spot is now wilderness or belongs to someone
                 *  else), the stale home is disabled AND removed here, rather than teleporting into lost/hostile
                 *  ground. This is the fix for a /f home surviving a base change. */
                Claim hc=claimAt(home.location());
                if(hc==null||hc.faction.id()!=f.id()){db.deleteHome(Long.toString(f.id()),"FACTION",name);CoreUtil.error(p,"Faction home '"+name+"' was in territory your faction no longer holds, so it has been removed. Set a new one with /f sethome inside your claim.");return true;}
                teleports.warmup(p,home.location(),"faction home");}
            case "homes"->{if(f==null){CoreUtil.error(p,"You are not in a faction.");return true;}List<String> names=db.homes(Long.toString(f.id()),"FACTION").stream().map(Database.HomeRow::name).toList();CoreUtil.msg(p,"Faction homes ("+names.size()+"/"+f.homeSlots()+"): "+(names.isEmpty()?"none":String.join(", ",names)));}
            case "delhome"->{if(!requireFactionManager(p,f))return true;String name=args.length>1?args[1]:"home";db.deleteHome(Long.toString(f.id()),"FACTION",name);CoreUtil.msg(p,"Faction home '"+name+"' deleted.");}
            case "buyhome"->{if(!requireFactionManager(p,f))return true;if(f.homeSlots()>=3){CoreUtil.msg(p,"Your faction already has all 3 home slots.");return true;}int next=f.homeSlots()+1,cost=plugin.getConfig().getInt("homes.faction.upgrades."+next);CoreUtil.msg(p,"Faction home slot "+next+" costs "+CoreUtil.money(cost)+". Faction balance: "+CoreUtil.money(f.balance()));if(args.length<2||!args[1].equalsIgnoreCase("confirm")){CoreUtil.msg(p,"Purchase with /f buyhome confirm.");return true;}if(!plugin.bank().payFaction(f.id(),cost,pid,"FACTION_HOME_"+next)){CoreUtil.error(p,"The faction bank cannot afford this slot.");return true;}db.recordEconomy(pid,"UPGRADE_SINK",-cost,"FACTION_HOME_"+next);db.setFactionHomeSlots(f.id(),next);CoreUtil.msg(p,"Faction home slot "+next+" unlocked.");}
            case "chat"->{if(f==null){CoreUtil.error(p,"You are not in a faction.");return true;}if(args.length>1){String message=String.join(" ",Arrays.copyOfRange(args,1,args.length));broadcast(f,"§b[F] "+plugin.nicknames().displayName(p)+"§7: §f"+message);}else{boolean now=toggleChatMode(p);CoreUtil.msg(p,now?"§bFaction chat mode ON §7— everything you type now goes to your faction only. /f chat again to turn it off.":"§7Faction chat mode OFF — back to normal chat.");}}
            default->lookup(p,args[0]);
        }
        return true;
    }
    private void openMain(Player player){
        Database.FactionRow faction=db.factionOf(CoreUtil.id(player));
        Inventory inv=plugin.getServer().createInventory(new MainHolder(player.getUniqueId()),45,Component.text("Faction",NamedTextColor.DARK_GREEN));
        if(faction==null){
            inv.setItem(20,button(Material.WHITE_BANNER,"Create a Faction",List.of("Use /f create <name>.")));
            inv.setItem(24,button(Material.WRITABLE_BOOK,"Pending Invitation",List.of("Use /f accept after an invitation.")));
            player.openInventory(inv);return;
        }
        Claim claim=claimOf(faction);
        inv.setItem(10,button(Material.PAPER,faction.name()+" ["+faction.tag()+"]",List.of(roleLabel(player,faction),db.factionMemberCount(faction.id())+" / "+plugin.getConfig().getInt("factions.max-members",3)+" members")));
        inv.setItem(12,button(Material.GOLD_BLOCK,"Net Worth",List.of(CoreUtil.money(plugin.netWorth().value(faction.id())))));
        inv.setItem(14,button(Material.BARRIER,"Faction Borders",List.of(borderEnabled(player)?"ON":"OFF")));
        inv.setItem(16,button(Material.PLAYER_HEAD,"Members & Co-Leader",List.of("Manage faction roles.")));
        inv.setItem(28,button(claim==null?Material.GOLDEN_SHOVEL:Material.RECOVERY_COMPASS,claim==null?"Claim Territory":"Expand Territory",List.of(claim==null?"Centers the free claim at your position.":claim.size()+" × "+claim.size())));
        inv.setItem(30,button(Material.WHITE_BANNER,"Relations",List.of("Alliances and truces.")));
        inv.setItem(32,button(Material.RED_BED,"Faction Homes",List.of(String.join(", ",db.homes(Long.toString(faction.id()),"FACTION").stream().map(Database.HomeRow::name).toList()))));
        inv.setItem(34,button(Material.CHEST,"Faction Bank",List.of(CoreUtil.money(faction.balance()),"Use /f deposit or /f withdraw.")));
        player.openInventory(inv);
    }
    private void openMembers(Player player,Database.FactionRow faction){
        Inventory inv=plugin.getServer().createInventory(new MembersHolder(faction.id()),27,Component.text("Faction Members",NamedTextColor.DARK_GREEN));
        int slot=10;for(Database.FactionMemberRow member:db.factionMemberRows(faction.id())){
            List<String> lore=new ArrayList<>();lore.add(CoreUtil.pretty(member.role()));
            if(isLeader(player,faction)&&!"LEADER".equals(member.role()))lore.add("Click to "+("CO_LEADER".equals(member.role())?"remove Co-Leader":"appoint Co-Leader")+".");
            inv.setItem(slot++,button(Material.PLAYER_HEAD,member.playerName(),lore));
        }
        inv.setItem(22,button(Material.ARROW,"Back",List.of()));player.openInventory(inv);
    }
    private String roleLabel(Player player,Database.FactionRow faction){return isLeader(player,faction)?"Leader":isCoLeader(player,faction)?"Co-Leader":"Member";}
    private void requestRelation(Player player,Database.FactionRow own,String targetName,String type){
        Database.FactionRow other=db.factionByName(targetName);if(other==null||other.id()==own.id()){CoreUtil.error(player,"That faction was not found.");return;}Database.RelationRow row=db.relation(own.id(),other.id());
        if(row!=null&&type.equals(row.pendingType())&&row.requestedBy()==other.id()){
            if("ALLIANCE".equals(type)&&(db.allianceCount(own.id())>=allianceLimit()||db.allianceCount(other.id())>=allianceLimit())){CoreUtil.error(player,"One faction has reached the alliance limit.");return;}
            db.acceptRelation(own.id(),other.id(),type);relationBroadcast(own,other,"The "+CoreUtil.pretty(type)+" between "+own.name()+" and "+other.name()+" is now active.");db.history("SERVER",null,"FACTION_RELATION",own.name()+" and "+other.name()+" formed a "+type.toLowerCase(Locale.ROOT)+".");return;
        }
        if(row!=null&&type.equals(row.type())){CoreUtil.msg(player,"That "+type.toLowerCase(Locale.ROOT)+" is already active.");return;}
        db.requestRelation(own.id(),other.id(),type,own.id());relationBroadcast(own,other,own.name()+" requested a "+type.toLowerCase(Locale.ROOT)+" with "+other.name()+". The other leader must approve it.");
    }
    private void toggleStorage(Player player,Database.FactionRow own,String targetName){
        Database.FactionRow other=db.factionByName(targetName);if(other==null){CoreUtil.error(player,"That faction was not found.");return;}Database.RelationRow row=db.relation(own.id(),other.id());if(row==null||!"ALLIANCE".equals(row.type())){CoreUtil.error(player,"Shared storage requires an active alliance.");return;}
        boolean next=!row.storageApproval(own.id());db.storageApproval(own.id(),other.id(),own.id(),next);Database.RelationRow updated=db.relation(own.id(),other.id());String message=next?(updated.sharedStorage()?"Shared storage is now active between both factions.":"Your leader approved shared storage; the other leader must also approve."):"Shared storage access was disabled.";relationBroadcast(own,other,message);
    }
    private void toggleHomeAccess(Player player,Database.FactionRow own,String targetName){
        Database.FactionRow other=db.factionByName(targetName);if(other==null){CoreUtil.error(player,"That faction was not found.");return;}Database.RelationRow row=db.relation(own.id(),other.id());if(row==null||!"ALLIANCE".equals(row.type())){CoreUtil.error(player,"Home access requires an active alliance.");return;}
        boolean next=!row.homesApproval(own.id());db.homesApproval(own.id(),other.id(),own.id(),next);Database.RelationRow updated=db.relation(own.id(),other.id());String message=next?(updated.sharedHomes()?"Members can now set and use personal homes in both territories.":"Your leader approved home access; the other leader must also approve."):"Home access was disabled.";relationBroadcast(own,other,message);
    }
    private int allianceLimit(){return Math.max(0,plugin.getConfig().getInt("factions.max-alliances",2));}
    private void relationBroadcast(Database.FactionRow first,Database.FactionRow second,String text){broadcast(first,text);broadcast(second,text);}

    private void openRelations(Player player,Database.FactionRow faction){
        Inventory inv=plugin.getServer().createInventory(new RelationsHolder(faction.id()),54,Component.text("Faction Relations",NamedTextColor.DARK_GREEN));int slot=0;
        for(Database.FactionRow other:db.factions()){if(other.id()==faction.id()||slot>=45)continue;Database.RelationRow row=db.relation(faction.id(),other.id());String status=row==null?"No relation":row.active()?CoreUtil.pretty(row.type())+(row.sharedStorage()?" • shared storage":"")+(row.sharedHomes()?" • home access":""):row.pendingType()!=null?"Pending "+CoreUtil.pretty(row.pendingType()):"No relation";inv.setItem(slot++,button(Material.WHITE_BANNER,other.name()+" ["+other.tag()+"]",List.of(status,"Click to manage.")));}
        inv.setItem(49,button(Material.PAPER,"Alliance Limit",List.of(db.allianceCount(faction.id())+" / "+allianceLimit())));player.openInventory(inv);
    }
    private void openRelation(Player player,Database.FactionRow faction,Database.FactionRow other){
        Database.RelationRow row=db.relation(faction.id(),other.id());Inventory inv=plugin.getServer().createInventory(new RelationHolder(faction.id(),other.id()),45,Component.text(other.name()+" Relations",NamedTextColor.DARK_GREEN));
        inv.setItem(11,button(Material.WHITE_WOOL,"Request Truce",List.of("PvP disabled; no access privileges.")));
        inv.setItem(13,button(Material.LIGHT_BLUE_WOOL,"Request Alliance",List.of("PvP disabled; doors and controls allowed.")));
        boolean shared=row!=null&&row.sharedStorage(),approved=row!=null&&row.storageApproval(faction.id());inv.setItem(15,button(shared?Material.LIME_CONCRETE:approved?Material.YELLOW_CONCRETE:Material.RED_CONCRETE,"Shared Storage",List.of(shared?"ACTIVE":approved?"Waiting for other leader":"Both leaders must approve.")));
        boolean sharedHomes=row!=null&&row.sharedHomes(),homesApproved=row!=null&&row.homesApproval(faction.id());inv.setItem(17,button(sharedHomes?Material.LIME_CONCRETE:homesApproved?Material.YELLOW_CONCRETE:Material.RED_CONCRETE,"Home Access",List.of(sharedHomes?"ACTIVE — members can set/use homes in both territories":homesApproved?"Waiting for other leader":"Both leaders must approve.")));
        if(row!=null&&(row.active()||row.pendingType()!=null))inv.setItem(31,button(Material.BARRIER,"End / Withdraw Relation",List.of("Immediately removes the relation or request.")));
        inv.setItem(40,button(Material.ARROW,"Back",List.of()));player.openInventory(inv);
    }
    void click(InventoryClickEvent event){
        if(event.getInventory().getHolder(false) instanceof MainHolder holder){event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player)||!holder.player().equals(player.getUniqueId()))return;Database.FactionRow faction=db.factionOf(CoreUtil.id(player));if(faction==null)return;switch(event.getRawSlot()){case 10->command(player,new String[]{"info"});case 12->plugin.netWorth().open(player,faction);case 14->{command(player,new String[]{"borders"});openMain(player);}case 16->openMembers(player,faction);case 28->command(player,new String[]{faction.tier()<0?"claim":"expand"});case 30->openRelations(player,faction);case 32->command(player,new String[]{"homes"});default->{}}return;}
        if(event.getInventory().getHolder(false) instanceof MembersHolder holder){event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player))return;Database.FactionRow faction=db.faction(holder.faction());if(faction==null)return;if(event.getRawSlot()==22){openMain(player);return;}int index=event.getRawSlot()-10;List<Database.FactionMemberRow> members=db.factionMemberRows(faction.id());if(index<0||index>=members.size()||!isLeader(player,faction))return;Database.FactionMemberRow member=members.get(index);if("LEADER".equals(member.role()))return;if("CO_LEADER".equals(member.role()))db.setCoLeader(faction.id(),null);else db.setCoLeader(faction.id(),member.player());openMembers(player,db.faction(faction.id()));return;}
        if(event.getInventory().getHolder(false) instanceof RelationsHolder holder){event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player))return;Database.FactionRow faction=db.faction(holder.faction());if(faction==null)return;int slot=event.getRawSlot(),index=0;for(Database.FactionRow other:db.factions()){if(other.id()==faction.id())continue;if(index++==slot){openRelation(player,faction,other);return;}}return;}
        if(!(event.getInventory().getHolder(false) instanceof RelationHolder holder))return;event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player))return;Database.FactionRow faction=db.faction(holder.faction()),other=db.faction(holder.other());if(faction==null||other==null||!canManage(player,faction)){CoreUtil.error(player,"Only faction leadership can manage relations.");player.closeInventory();return;}
        switch(event.getRawSlot()){case 11->{requestRelation(player,faction,other.name(),"TRUCE");openRelation(player,faction,other);}case 13->{requestRelation(player,faction,other.name(),"ALLIANCE");openRelation(player,faction,other);}case 15->{toggleStorage(player,faction,other.name());openRelation(player,faction,other);}case 17->{toggleHomeAccess(player,faction,other.name());openRelation(player,faction,other);}case 31->{db.clearRelation(faction.id(),other.id());relationBroadcast(faction,other,"The relation between "+faction.name()+" and "+other.name()+" ended.");openRelation(player,faction,other);}case 40->openRelations(player,faction);default->{}}}
    private ItemStack button(Material material,String name,List<String> lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(name,NamedTextColor.GOLD));meta.lore(lore.stream().map(line->Component.text(line,NamedTextColor.GRAY)).toList());item.setItemMeta(meta);return item;}

    private int rememberedTier(long factionId){String raw=db.state("faction_remembered_tier:"+factionId);if(raw==null||raw.isBlank())return -1;try{return Integer.parseInt(raw);}catch(NumberFormatException e){return -1;}}
    private int sizeFor(int tier){List<Integer> sizes=plugin.getConfig().getIntegerList("claims.sizes");return sizes.get(Math.min(Math.max(0,tier),sizes.size()-1));}
    private Claim claimFor(Database.FactionRow f,Location center,int size){int x=center==null?f.coreX():center.getBlockX(),z=center==null?f.coreZ():center.getBlockZ();String world=center==null?f.world():center.getWorld().getName();Database.FactionRow row=f==null?new Database.FactionRow(-1,"proposal","","",0,0,1,world,x,z):f;int minX=x-size/2,minZ=z-size/2;return new Claim(row,size,minX,minX+size-1,minZ,minZ+size-1);}
    private boolean requireFactionLeader(Player p,Database.FactionRow f){if(f==null){CoreUtil.error(p,"You are not in a faction.");return false;}if(!isLeader(p,f)){CoreUtil.error(p,"Only the faction leader can do that.");return false;}return true;}
    /** Grants the "faction has claimed land"/"faction has set a home" progression missions to every CURRENT
     *  member of the given faction, for whichever of the two the faction has actually achieved. Safe/cheap to
     *  call opportunistically (claim, sethome, and join) — markMilestone is idempotent, so re-granting an
     *  already-held milestone to an existing member is a harmless no-op. */
    private void grantFactionMissions(Database.FactionRow faction){
        boolean land=db.factionClaimedLandEver(faction.id()),home=db.factionSetHomeEver(faction.id());
        if(!land&&!home)return;
        for(String memberName:db.factionMembers(faction.id())){
            String memberId=CoreUtil.id(memberName);
            if(land)plugin.progress().factionLandMilestone(memberId);
            if(home)plugin.progress().factionHomeMilestone(memberId);
        }
    }
    /** grantFactionMissions() was only ever wired into the claim/sethome/join actions themselves — a faction
     *  that had already claimed land or set a home BEFORE those missions existed would never re-trigger any of
     *  those three, so its current members would sit on legitimate historical credit forever without actually
     *  receiving it. One-time startup sweep over every faction closes that gap; markMilestone is idempotent so
     *  running this on every boot is harmless. */
    void backfillFactionMissions(){for(Database.FactionRow faction:db.factions())grantFactionMissions(faction);}
    private boolean requireFactionManager(Player p,Database.FactionRow f){if(f==null){CoreUtil.error(p,"You are not in a faction.");return false;}if(!canManage(p,f)){CoreUtil.error(p,"Only the faction Leader or Co-Leader can do that.");return false;}return true;}
    private void broadcast(Database.FactionRow f,String text){for(String name:db.factionMembers(f.id())){Player online=plugin.getServer().getPlayerExact(name);if(online!=null)CoreUtil.msg(online,text);}}
    /** Transient, session-scoped — mirrors AfkService's Set<UUID> toggle rather than the DB-backed
     *  player_preferences mechanism, since chat mode shouldn't survive a relog any more than AFK does. */
    boolean isChatMode(Player player){return chatMode.contains(player.getUniqueId());}
    void clearChatMode(Player player){chatMode.remove(player.getUniqueId());}
    private boolean toggleChatMode(Player player){boolean now=chatMode.add(player.getUniqueId());if(!now)chatMode.remove(player.getUniqueId());return now;}
    /** Called from GameplayListener once a chat-mode player's message has already been cancelled from public
     *  chat. Re-checks faction membership at send time (not just at toggle time) since /f leave, /f kick, or
     *  /f disband could have happened in between without this player ever touching /f chat again. */
    void sendChatModeMessage(Player sender,String message){
        Database.FactionRow f=db.factionOf(CoreUtil.id(sender));
        if(f==null){chatMode.remove(sender.getUniqueId());CoreUtil.error(sender,"You are no longer in a faction — faction chat mode disabled.");return;}
        broadcast(f,"§b[F] "+plugin.nicknames().displayName(sender)+"§7: §f"+message);
        db.logChat("FACTION",CoreUtil.id(sender),sender.getName(),Long.toString(f.id()),f.name(),message);
    }
    private boolean borderEnabled(Player player){return Boolean.parseBoolean(Objects.requireNonNullElse(db.preference(CoreUtil.id(player),"faction_borders"),"false"));}
    private void renderEnabledBorders(){for(Player player:plugin.getServer().getOnlinePlayers()){if(!borderEnabled(player))continue;Database.FactionRow faction=db.factionOf(CoreUtil.id(player));Claim claim=faction==null?null:claimOf(faction);if(claim!=null)renderBorder(player,claim);}}
    private void renderBorder(Player player,Claim claim){
        World world=player.getWorld();if(!world.getName().equals(claim.faction().world()))return;Location here=player.getLocation();double range=Math.max(24,plugin.getConfig().getDouble("factions.border-view-distance",48)),minX=claim.minX(),maxX=claim.maxX()+1.0,minZ=claim.minZ(),maxZ=claim.maxZ()+1.0,yMin=world.getMinHeight()+1,yMax=world.getMaxHeight()-1;
        if(Math.abs(here.getZ()-minZ)<=range)verticalLine(player,world,true,minZ,Math.max(minX,here.getX()-range),Math.min(maxX,here.getX()+range),yMin,yMax);
        if(Math.abs(here.getZ()-maxZ)<=range)verticalLine(player,world,true,maxZ,Math.max(minX,here.getX()-range),Math.min(maxX,here.getX()+range),yMin,yMax);
        if(Math.abs(here.getX()-minX)<=range)verticalLine(player,world,false,minX,Math.max(minZ,here.getZ()-range),Math.min(maxZ,here.getZ()+range),yMin,yMax);
        if(Math.abs(here.getX()-maxX)<=range)verticalLine(player,world,false,maxX,Math.max(minZ,here.getZ()-range),Math.min(maxZ,here.getZ()+range),yMin,yMax);
    }
    /** Approximates the vanilla World Border's look with particles — a dense, continuous, pale-blue vertical
     *  sheet with a diagonal scrolling stripe, rather than the previous sparse dotted grid — while staying
     *  fully passable and rendered per-viewer, since claims (unlike the real border) must never block movement
     *  and must only ever be visible to players who've opted in. */
    private void verticalLine(Player player,World world,boolean alongX,double fixed,double start,double end,double yMin,double yMax){
        if(end<start||yMax<yMin)return;
        double phase=(System.currentTimeMillis()/200L)%40;
        Particle.DustTransition sheet=new Particle.DustTransition(Color.fromRGB(150,220,255),Color.fromRGB(70,170,255),1.1f);
        Particle.DustTransition sweep=new Particle.DustTransition(Color.fromRGB(235,250,255),Color.fromRGB(90,200,255),1.3f);
        double middle=(start+end)/2.0,half=Math.max(.25,(end-start)/2.0),midY=(yMin+yMax)/2.0,yHalf=Math.max(.25,(yMax-yMin)/2.0);
        for(double axis=Math.ceil(start/3.0)*3.0;axis<=end;axis+=3.0){
            Location column=new Location(world,alongX?axis:fixed,midY,alongX?fixed:axis);
            player.spawnParticle(Particle.DUST,column,Math.max(8,(int)Math.ceil(yHalf/2)),0,yHalf,0,0,sheet);
        }
        for(double y=Math.ceil(yMin/4.0)*4.0;y<=yMax;y+=4.0){
            Location row=new Location(world,alongX?middle:fixed,y,alongX?fixed:middle);
            player.spawnParticle(Particle.DUST,row,Math.max(10,(int)Math.ceil(half*2)),alongX?half:0,.05,alongX?.05:half,0,sheet);
        }
        double height=Math.max(1,yMax-yMin);
        for(double axis=Math.ceil(start/3.0)*3.0;axis<=end;axis+=3.0){
            double y=yMin+Math.floorMod((long)Math.floor(axis*1.5+phase*2.2),(long)Math.max(1,Math.floor(height)));
            Location point=new Location(world,alongX?axis:fixed,y,alongX?fixed:axis);
            player.spawnParticle(Particle.DUST,point,3,.4,.6,.4,0,sweep);
        }
    }
    /** /f <playername> — an unrecognized subcommand is tried as a player name before falling back to help,
     *  showing that player's faction's PUBLIC info only (same fields /f info <faction> already shows to
     *  anyone — this doesn't expose anything new, just adds a player-name entry point to the same data). */
    /** /f <name> resolves BOTH a player named this AND a faction named this, independently — a player who
     *  happens to share a name with an unrelated faction (or vice versa) should see both results rather than
     *  one silently winning. The only time the faction half is skipped is when it's the exact same faction the
     *  matched player is already shown as belonging to — nothing left to disambiguate there. */
    private boolean lookup(Player viewer,String targetName){
        Player visible=plugin.nicknames().findVisiblePlayer(targetName);
        Database.StatsRow statsRow=db.statsByName(visible==null?targetName:visible.getName());
        Database.FactionRow byName=db.factionByName(targetName);
        Database.FactionRow playerFaction=statsRow==null?null:db.factionOf(statsRow.id());
        boolean shown=false;
        if(statsRow!=null){
            if(playerFaction==null)CoreUtil.msg(viewer,plugin.nicknames().displayName(statsRow.name())+" is not part of any faction.");
            else{CoreUtil.msg(viewer,plugin.nicknames().displayName(statsRow.name())+" is a member of "+playerFaction.name()+" ["+playerFaction.tag()+"].");showFactionInfo(viewer,playerFaction);}
            shown=true;
        }
        if(byName!=null&&(playerFaction==null||playerFaction.id()!=byName.id())){
            if(shown)CoreUtil.msg(viewer,"§7— Faction \""+byName.name()+"\" —");
            showFactionInfo(viewer,byName);
            shown=true;
        }
        if(!shown)help(viewer);
        return true;
    }
    /** Public faction info only — name, tag, net-worth rank, members, claim size. Deliberately excludes the
     *  faction bank balance and anything else that isn't meant to be visible to non-members. */
    private void showFactionInfo(Player viewer,Database.FactionRow faction){
        Claim claim=claimOf(faction);
        int rank=1;for(NetWorthService.Row ranked:plugin.netWorth().rankings()){if(ranked.factionId()==faction.id())break;rank++;}
        CoreUtil.msg(viewer,faction.name()+" ["+faction.tag()+"] — rank #"+rank+" by net worth.");
        CoreUtil.msg(viewer,"Members: "+String.join(", ",db.factionMembers(faction.id()).stream().map(plugin.nicknames()::displayName).toList()));
        CoreUtil.msg(viewer,"Net Worth: "+CoreUtil.money(plugin.netWorth().value(faction.id()))+" | Claim: "+(claim==null?"none":claim.size()+" x "+claim.size()+" (tier "+faction.tier()+")"));
    }
    private void help(Player p){CoreUtil.msg(p,"Faction commands: /f, create, invite, accept, info, networth, leaderboard, relations, borders, deposit, withdraw");CoreUtil.msg(p,"Leadership: /f coleader <member|remove>, /f leader <member>");CoreUtil.msg(p,"Leader land flow: stand at the center → /f claim → /f expand | TAB tag: /f tag");CoreUtil.msg(p,"Relations: /f ally, /f truce, /f storage, /f homeaccess | Faction Homes: /f sethome, home, homes, buyhome");CoreUtil.msg(p,"Members: kick, leader, leave, disband");}
    private String cardinal(double angle){String[] names={"N","NE","E","SE","S","SW","W","NW"};int index=(int)Math.floor((angle+22.5+360)%360/45);return names[index];}
}
