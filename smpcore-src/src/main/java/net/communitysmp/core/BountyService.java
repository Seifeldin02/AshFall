package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

final class BountyService implements Listener {
    private record GuiHolder() implements InventoryHolder { @Override public Inventory getInventory(){return null;} }
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());
    private final SMPCore plugin; private final Database db; private final FactionService factions;
    private final NamespacedKey claimIdKey;
    BountyService(SMPCore plugin,FactionService factions){this.plugin=plugin;this.db=plugin.db();this.factions=factions;this.claimIdKey=new NamespacedKey(plugin,"bounty_claim_id");}

    /** placement-enabled defaults to true — a per-server config flag, not a code path removal, so it can be
     *  flipped back the moment a real anti-cheating/verification approach is ready without touching this
     *  class again. Deliberately only gates placing NEW bounties: viewing, claiming, and the existing bounty
     *  GUI are all untouched, since the concern is new placements specifically, not the feature as a whole. */
    boolean place(Player placer,String targetName,double amount){
        if(!plugin.getConfig().getBoolean("bounties.placement-enabled",true)){CoreUtil.error(placer,"Placing new bounties is temporarily disabled.");return true;}
        Player visibleTarget=plugin.nicknames().findVisiblePlayer(targetName);String resolved=visibleTarget==null?targetName:visibleTarget.getName();
        double min=plugin.getConfig().getDouble("bounties.minimum",100);String target=CoreUtil.id(resolved),source=CoreUtil.id(placer);
        if(amount<min){CoreUtil.error(placer,"Minimum bounty is "+CoreUtil.money(min)+".");return true;}
        if(target.equals(source)){CoreUtil.error(placer,"You cannot place a bounty on yourself.");return true;}
        Database.PlayerRow targetRow=db.player(target);if(targetRow==null){CoreUtil.error(placer,"That player has not joined this server.");return true;}
        double fee=Math.round(amount*plugin.getConfig().getDouble("bounties.placement-fee-percent",2.0))/100.0,totalCharge=Math.round((amount+fee)*100)/100.0;
        if(!db.changeBalance(source,-totalCharge)){CoreUtil.error(placer,"You need "+CoreUtil.money(totalCharge)+" including the "+CoreUtil.money(fee)+" placement fee.");return true;}
        if(fee>0){plugin.bank().creditFee(fee,source,"BOUNTY_PLACEMENT");db.recordEconomy(source,"BOUNTY_FEE",-fee,"PLACEMENT");}
        db.addBounty(target,targetRow.name(),amount);db.addBountyContribution(target,source,placer.getName(),"PLAYER",amount);
        Database.BountyRow total=db.bounty(target);String shown=plugin.nicknames().displayName(targetRow.name());CoreUtil.msg(placer,"Added "+CoreUtil.money(amount)+" to "+shown+"'s bounty. Fee: "+CoreUtil.money(fee)+". Total bounty: "+CoreUtil.money(total.amount())+".");
        Database.FactionRow placerFaction=db.factionOf(source),targetFaction=db.factionOf(target);if(placerFaction!=null&&targetFaction!=null&&placerFaction.id()==targetFaction.id())CoreUtil.msg(placer,"Note: faction-mates cannot claim this bounty.");
        plugin.progress().bountyPlaced(placer,targetRow.name(),amount);if(total.amount()>=plugin.getConfig().getDouble("bounties.large-announcement",10000))plugin.getServer().broadcastMessage("§4☠ A "+CoreUtil.money(total.amount())+" bounty now hangs over "+shown+".");return true;
    }
    void list(Player p){List<Database.BountyRow> rows=db.bounties();if(rows.isEmpty()){CoreUtil.msg(p,"There are no active bounties.");return;}CoreUtil.msg(p,"Active bounties:");for(Database.BountyRow row:rows)CoreUtil.msg(p,"☠ "+plugin.nicknames().displayName(row.targetName())+" — "+CoreUtil.money(row.amount()));}

    boolean onPlayerKill(Player victim,Player killer,double deathLoss){
        if(killer==null||killer.equals(victim))return false;String target=CoreUtil.id(victim),hunter=CoreUtil.id(killer);db.incrementStat(hunter,"player_kills");Database.PlayerRow kr=db.player(hunter),vr=db.player(target);boolean sameFaction=factions.sameFaction(victim,killer),sameNetwork=kr!=null&&vr!=null&&kr.ipHash()!=null&&kr.ipHash().equals(vr.ipHash()),deathAwarded=false;
        if(!sameNetwork)db.logKill(hunter,target,factions.friendly(killer,victim));
        if(!sameFaction&&!sameNetwork&&deathLoss>0){plugin.creditEarned(hunter,deathLoss,"PVP_DEATH_TRANSFER");CoreUtil.msg(killer,"You claimed "+CoreUtil.money(deathLoss)+" from "+plugin.nicknames().displayName(victim)+"'s defeat.");deathAwarded=true;}
        if(!sameFaction&&!sameNetwork)considerAutoBounty(hunter);
        Database.BountyRow bounty=db.bounty(target);if(bounty==null)return deathAwarded;
        if(sameFaction){CoreUtil.error(killer,"Same-faction kills cannot receive death money or claim bounties.");return false;}
        /** Anti-abuse rejections must not tip off a player that their alt/shared-network account was detected —
         *  the message is identical to a generic "not eligible" outcome; the real reason only ever reaches
         *  admins, via admin_audit (visible through /ashfall audit), never the killer. */
        if(sameNetwork){db.recordBountyClaim(hunter,target,true);CoreUtil.error(killer,"This kill is not eligible for a bounty claim.");db.logAudit("SYSTEM","BOUNTY_REJECT_SILENT","claim blocked: killer="+killer.getName()+" ("+hunter+") and target="+victim.getName()+" ("+target+") share a recent network identity");return false;}
        long cooldown=plugin.getConfig().getLong("bounties.repeat-pair-days",7)*86400000L;if(System.currentTimeMillis()-db.lastBountyClaim(hunter,target)<cooldown){CoreUtil.error(killer,"This killer/target pair cannot claim another bounty yet.");return deathAwarded;}
        double tax=Math.round(bounty.amount()*plugin.getConfig().getDouble("bounties.claim-tax-percent",3.0))/100.0;
        db.removeBounty(target);db.recordBountyClaim(hunter,target,false);
        double threshold=plugin.getConfig().getDouble("bounties.admin-approval-threshold",10000);
        boolean needsAdminReview=bounty.amount()>=threshold;
        long id=db.createPendingClaim(target,victim.getName(),hunter,killer.getName(),bounty.amount(),tax);
        if(needsAdminReview){
            CoreUtil.msg(killer,"Your bounty claim on "+plugin.nicknames().displayName(victim)+" ("+CoreUtil.money(bounty.amount())+") is large enough to require admin review. You'll be paid once it's approved.");
            notifyAdminsPendingClaim(id,killer.getName(),victim.getName(),bounty.amount());
            db.history("SERVER",null,"BOUNTY","Pending claim #"+id+": "+killer.getName()+" vs "+victim.getName()+" for "+CoreUtil.money(bounty.amount()));
        }else{
            CoreUtil.msg(killer,"Your bounty claim on "+plugin.nicknames().displayName(victim)+" ("+CoreUtil.money(bounty.amount())+") is being verified and will pay out shortly.");
        }
        attachReplayThenResolve(id,victim.getUniqueId(),needsAdminReview);
        return deathAwarded;
    }
    /** Every bounty claim briefly holds for a ReplayCore kill-replay attach attempt before an
     *  under-threshold claim actually pays out — per requirement, a claim that can't get evidence stays
     *  pending for manual review rather than auto-paying blind. Claims already routed to admin review get
     *  the replay attached the same way, purely so the "Review Replay" button has something to show by the
     *  time an admin opens the GUI. If ReplayCore isn't installed/enabled at all (not merely "this one clip
     *  failed"), this resolves immediately instead of waiting on a dependency that isn't part of this
     *  deployment. */
    /*  TAKES THE VICTIM'S ACTUAL UUID, because it never had one to parse.
     *
     *  This was handed `target` -- CoreUtil.id(victim), a lower-cased NAME -- and called UUID.fromString
     *  on it. "macoct" is not a UUID, so any bounty claim reaching here on a server WITH ReplayCore threw
     *  straight out of PlayerDeathEvent. The UUID is on the victim at the one call site; deriving it from
     *  an id string was never going to work. Same mistake, same day, as finalizeAutoApprove below. */
    private void attachReplayThenResolve(long claimId,java.util.UUID victim,boolean needsAdminReview){
        if(!plugin.replay().available()){if(!needsAdminReview)finalizeAutoApprove(claimId);return;}
        pollForReplay(claimId,victim,needsAdminReview,0);
    }
    private void pollForReplay(long claimId,java.util.UUID victim,boolean needsAdminReview,int attempt){
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{
            java.util.Optional<uk.co.forgevector.replaycore.api.plugin.KillReplay> found=plugin.replay().latestKillReplay(victim);
            if(found.isPresent()){
                db.setReplayResult(claimId,found.get().webUrl());
                if(!needsAdminReview)finalizeAutoApprove(claimId);
                return;
            }
            if(attempt>=6){
                db.setReplayResult(claimId,null);
                if(!needsAdminReview){
                    Database.PendingBountyClaimRow row=db.pendingClaim(claimId);
                    if(row!=null){notifyAdminsPendingClaim(claimId,row.killerName(),row.targetName(),row.amount());db.history("SERVER",null,"BOUNTY","Pending claim #"+claimId+" held for admin review — no replay evidence available.");}
                }
                return;
            }
            pollForReplay(claimId,victim,needsAdminReview,attempt+1);
        },100L);
    }
    private void finalizeAutoApprove(long claimId){
        Database.PendingBountyClaimRow row=db.pendingClaim(claimId);if(row==null||!"PENDING".equals(row.status()))return;
        double payout=Math.max(0,Math.round((row.amount()-row.tax())*100)/100.0);
        if(row.tax()>0)plugin.bank().creditFee(row.tax(),row.killer(),"BOUNTY_CLAIM_TAX");
        if(payout>0)plugin.creditEarned(row.killer(),payout,"BOUNTY_CLAIM");
        db.resolvePendingClaim(claimId,"APPROVED","SYSTEM");db.clearBountyContributions(row.target());
        /*  THE LINE THAT COST SOMEBODY THEIR INVENTORY.
         *
         *  row.killer() is CoreUtil.id(killer) -- a lower-cased NAME -- and this called UUID.fromString on
         *  it. Every auto-approved bounty claim therefore threw IllegalArgumentException, and because the
         *  claim is finalised from inside PlayerDeathEvent the exception unwound the whole handler: the very
         *  last thing that handler does is create the grave, so the victim's items were left on the ground
         *  with nothing recording them and despawned five minutes later.
         *
         *  It survived because the money moves BEFORE this line. The payout, the tax and the APPROVED row
         *  all committed, so from the economy's side the claim looked like it had worked perfectly; only the
         *  grave was missing, and nothing connected the two. Reported live on 2026-09-06.
         *
         *  Looked up by name, which is what the row actually holds. */
        Player killerOnline=plugin.getServer().getPlayerExact(row.killerName());
        if(killerOnline==null)killerOnline=plugin.getServer().getPlayerExact(row.killer());
        if(killerOnline!=null)plugin.progress().bountyClaimed(killerOnline,row.targetName(),row.amount());
        plugin.getServer().broadcastMessage("§4☠ "+row.killerName()+" claimed "+CoreUtil.money(payout)+" from the bounty on "+row.targetName()+".");
    }
    private void notifyAdminsPendingClaim(long id,String killerName,String victimName,double amount){
        for(Player online:plugin.getServer().getOnlinePlayers())if(plugin.isAdmin(online))CoreUtil.msg(online,"§c[Bounty] Claim #"+id+" needs review: "+killerName+" killed "+victimName+" for "+CoreUtil.money(amount)+". Use /ashfall bounty.");
    }

    /** The Central Bank auto-places a bounty on players building up a real PvP-threat pattern — repeated
     *  distinct victims, or betraying faction-mates/allies specifically — funded from the treasury and
     *  capped as a small percentage of it per bounty, with its own cooldown, so it can neither be farmed
     *  nor risk draining the bank. Never stacks on top of an existing bounty. */
    private void considerAutoBounty(String offenderId){
        long windowMs=plugin.getConfig().getLong("bounties.auto.window-hours",72)*3600000L;
        int distinctVictims=db.recentDistinctVictims(offenderId,System.currentTimeMillis()-windowMs);
        int betrayals=db.recentBetrayals(offenderId,System.currentTimeMillis()-windowMs);
        int victimThreshold=plugin.getConfig().getInt("bounties.auto.distinct-victims-threshold",4);
        if(distinctVictims<victimThreshold&&betrayals<2)return;
        long cooldownMs=plugin.getConfig().getLong("bounties.auto.cooldown-hours",24)*3600000L;String cooldownKey="auto_bounty_cd:"+offenderId;
        long last=parseLong(db.state(cooldownKey));if(System.currentTimeMillis()-last<cooldownMs)return;
        Database.BountyRow existing=db.bounty(offenderId);if(existing!=null&&existing.amount()>0)return;
        Database.BankRow treasury=db.bank();if(treasury==null)return;
        Database.PlayerRow offenderRow=db.player(offenderId);if(offenderRow==null)return;
        double base=plugin.getConfig().getDouble("bounties.auto.base-amount",5000);
        double scaled=base*(1+Math.max(0,distinctVictims-victimThreshold)*.4+betrayals*.6);
        double cap=treasury.balance()*plugin.getConfig().getDouble("bounties.auto.treasury-percent-cap",.02);
        double amount=Math.round(Math.min(scaled,Math.max(0,cap))*100)/100.0;
        if(amount<plugin.getConfig().getDouble("bounties.minimum",100))return;
        if(!db.debitBank(amount,"Auto-bounty on "+offenderRow.name()))return;
        db.state(cooldownKey,Long.toString(System.currentTimeMillis()));
        db.addBounty(offenderId,offenderRow.name(),amount);db.addBountyContribution(offenderId,"BANK","Central Bank","BANK_AUTO",amount);
        String shown=plugin.nicknames().displayName(offenderRow.name());
        plugin.getServer().broadcastMessage("§4☠ The Central Bank has placed a "+CoreUtil.money(amount)+" bounty on "+shown+" for a pattern of PvP aggression.");
        db.history("SERVER",null,"BOUNTY","Central Bank auto-bounty on "+offenderRow.name()+" for "+CoreUtil.money(amount)+" ("+distinctVictims+" recent victims, "+betrayals+" betrayals).");
    }
    private long parseLong(String value){try{return value==null||value.isBlank()?0:Long.parseLong(value);}catch(NumberFormatException ignored){return 0;}}

    // ===== Admin tooling =====
    /** Every claim (not just large ones) now passes through bounty_claims_pending briefly while a replay
     *  attach attempt runs, so under-threshold claims that are about to auto-approve within seconds must not
     *  clutter this admin-facing GUI. Only show a claim once it's genuinely awaiting a human: it's large
     *  enough to always require review, or the replay attempt has already finished one way or the other
     *  (a below-threshold claim only stays PENDING past that point if no replay evidence turned up). */
    void openAdminGui(Player admin){
        double threshold=plugin.getConfig().getDouble("bounties.admin-approval-threshold",10000);
        List<Database.PendingBountyClaimRow> pending=db.pendingClaims("PENDING").stream().filter(row->row.amount()>=threshold||row.replayChecked()).toList();
        Inventory inv=plugin.getServer().createInventory(new GuiHolder(),54,Component.text("Bounty Claims — Pending Review",NamedTextColor.DARK_RED));
        int slot=0;
        for(Database.PendingBountyClaimRow row:pending){
            if(slot>=45)break;
            ItemStack icon=new ItemStack(Material.PLAYER_HEAD);SkullMeta meta=(SkullMeta)icon.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(row.killerName()));
            meta.displayName(Component.text("Claim #"+row.id()+" — "+row.killerName(),NamedTextColor.GOLD));
            String replayStatus=row.replayUrl()!=null?"available":row.replayChecked()?"unavailable":"pending";
            meta.lore(List.of(
                    Component.text("Victim: "+row.targetName(),NamedTextColor.GRAY),
                    Component.text("Amount: "+CoreUtil.money(row.amount())+"  (tax "+CoreUtil.money(row.tax())+")",NamedTextColor.GRAY),
                    Component.text("Filed: "+STAMP.format(Instant.ofEpochMilli(row.createdAt())),NamedTextColor.DARK_GRAY),
                    Component.text("Replay: "+replayStatus,NamedTextColor.GRAY),
                    Component.empty(),
                    Component.text("Left-click: approve payout",NamedTextColor.GREEN),
                    Component.text("Right-click: reject and refund",NamedTextColor.RED),
                    Component.text("Shift-click: inspect network/history in console",NamedTextColor.YELLOW),
                    Component.text("Drop (Q): review replay evidence",NamedTextColor.AQUA)
            ));
            meta.getPersistentDataContainer().set(claimIdKey,PersistentDataType.LONG,row.id());
            icon.setItemMeta(meta);inv.setItem(slot++,icon);
        }
        if(pending.isEmpty())inv.setItem(22,CoreUtil.named(Material.BARRIER,"No pending claims",List.of()));
        admin.openInventory(inv);
    }
    @EventHandler public void click(InventoryClickEvent event){
        if(!(event.getInventory().getHolder(false) instanceof GuiHolder))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player admin)||!plugin.isAdmin(admin))return;
        ItemStack clicked=event.getCurrentItem();if(clicked==null||!clicked.hasItemMeta())return;
        Long id=clicked.getItemMeta().getPersistentDataContainer().get(claimIdKey,PersistentDataType.LONG);if(id==null)return;
        if(event.getClick()==ClickType.DROP){reviewReplay(admin,id);return;}
        if(event.isShiftClick()){inspectToConsole(admin,id);return;}
        if(event.isRightClick())rejectClaim(admin,id,true);else approveClaim(admin,id);
        openAdminGui(admin);
    }
    private void reviewReplay(Player admin,long id){
        Database.PendingBountyClaimRow row=db.pendingClaim(id);if(row==null)return;
        if(row.replayUrl()==null){CoreUtil.error(admin,row.replayChecked()?"No replay evidence is available for this claim.":"Replay evidence is still being prepared — try again shortly.");return;}
        admin.sendMessage(Component.text("Claim #"+id+" replay: ",NamedTextColor.GRAY).append(Component.text("[Open Replay]",NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(row.replayUrl()))));
    }
    boolean approveClaim(CommandSender admin,long id){
        Database.PendingBountyClaimRow row=db.pendingClaim(id);if(row==null||!"PENDING".equals(row.status())){CoreUtil.error(admin,"That claim is not pending.");return false;}
        double payout=Math.max(0,Math.round((row.amount()-row.tax())*100)/100.0);
        if(row.tax()>0)plugin.bank().creditFee(row.tax(),row.killer(),"BOUNTY_CLAIM_TAX");
        if(payout>0)plugin.creditEarned(row.killer(),payout,"BOUNTY_CLAIM");
        db.resolvePendingClaim(id,"APPROVED",adminName(admin));db.clearBountyContributions(row.target());
        db.logAudit(adminName(admin),"BOUNTY_APPROVE","claim="+id+" killer="+row.killerName()+" amount="+CoreUtil.money(row.amount()));
        plugin.getServer().broadcastMessage("§4☠ "+row.killerName()+" claimed "+CoreUtil.money(payout)+" from the reviewed bounty on "+row.targetName()+".");
        CoreUtil.msg(admin,"Claim #"+id+" approved and paid.");
        return true;
    }
    /** A rejected+refunded claim ends the bounty entirely (onPlayerKill already deletes the bounties row the
     *  moment a claim is submitted, before this ever runs, so there's nothing left for anyone else to claim
     *  afterward) — functionally the same as an admin cancelling it outright, not a completed transaction, so
     *  the 2% placement fee taken at contribution time should come back too, not just the principal. The fee
     *  itself is never persisted per-contribution, so it's recomputed from the stored principal using the
     *  current placement-fee-percent — a deliberate, acceptable approximation rather than a schema change,
     *  same tradeoff the codebase already makes elsewhere for cheap self-correcting recomputation. */
    boolean rejectClaim(CommandSender admin,long id,boolean refund){
        Database.PendingBountyClaimRow row=db.pendingClaim(id);if(row==null||!"PENDING".equals(row.status())){CoreUtil.error(admin,"That claim is not pending.");return false;}
        db.resolvePendingClaim(id,"REJECTED",adminName(admin));
        if(refund){for(Database.BountyContributionRow contribution:db.bountyContributions(row.target())){
            if("BANK_AUTO".equals(contribution.source()))db.creditBankRevenue(contribution.amount(),"REFUND",null,"Rejected bounty claim #"+id);
            else{double fee=Math.round(contribution.amount()*plugin.getConfig().getDouble("bounties.placement-fee-percent",2.0))/100.0;plugin.creditEarned(contribution.contributor(),contribution.amount()+fee,"BOUNTY_REFUND");}
        }db.clearBountyContributions(row.target());}
        db.logAudit(adminName(admin),"BOUNTY_REJECT","claim="+id+" killer="+row.killerName()+" refunded="+refund);
        CoreUtil.msg(admin,"Claim #"+id+" rejected"+(refund?" and refunded.":"."));
        return true;
    }
    boolean adminRemove(CommandSender admin,String targetName,boolean refund){
        Player visible=plugin.nicknames().findVisiblePlayer(targetName);String target=CoreUtil.id(visible!=null?visible.getName():targetName);
        Database.BountyRow row=db.bounty(target);if(row==null){CoreUtil.error(admin,"That player has no active bounty.");return false;}
        if(refund)for(Database.BountyContributionRow contribution:db.bountyContributions(target)){
            if("BANK_AUTO".equals(contribution.source()))db.creditBankRevenue(contribution.amount(),"REFUND",null,"Bounty removed by admin");
            else{double fee=Math.round(contribution.amount()*plugin.getConfig().getDouble("bounties.placement-fee-percent",2.0))/100.0;plugin.creditEarned(contribution.contributor(),contribution.amount()+fee,"BOUNTY_REFUND");}
        }
        db.removeBounty(target);db.clearBountyContributions(target);
        db.logAudit(adminName(admin),"BOUNTY_REMOVE","target="+targetName+" refunded="+refund);
        CoreUtil.msg(admin,"Removed the bounty on "+targetName+(refund?" and refunded contributors.":"."));
        return true;
    }
    void inspect(CommandSender admin,String targetName){
        Player visible=plugin.nicknames().findVisiblePlayer(targetName);String target=CoreUtil.id(visible!=null?visible.getName():targetName);
        Database.PlayerRow row=db.player(target);if(row==null){CoreUtil.error(admin,"That player has not joined this server.");return;}
        CoreUtil.msg(admin,"Bounty/PvP history for "+targetName+" (network id: "+(row.ipHash()==null?"unknown":row.ipHash())+"):");
        Database.BountyRow active=db.bounty(target);CoreUtil.msg(admin,"Active bounty: "+(active==null?"none":CoreUtil.money(active.amount())));
        int recentVictims=db.recentDistinctVictims(target,System.currentTimeMillis()-259200000L),recentBetrayals=db.recentBetrayals(target,System.currentTimeMillis()-259200000L);
        CoreUtil.msg(admin,"Last 72h: "+recentVictims+" distinct victims, "+recentBetrayals+" betrayals of faction/allies.");
        List<Database.BountyContributionRow> contributions=db.bountyContributions(target);
        if(!contributions.isEmpty()){CoreUtil.msg(admin,"Current bounty funding:");for(Database.BountyContributionRow c:contributions)CoreUtil.msg(admin,"  "+c.contributorName()+" ("+c.source()+"): "+CoreUtil.money(c.amount()));}
    }
    private void inspectToConsole(Player admin,long id){
        Database.PendingBountyClaimRow row=db.pendingClaim(id);if(row==null)return;
        Database.PlayerRow killerRow=db.player(row.killer()),victimRow=db.player(row.target());
        plugin.getLogger().info("[Bounty inspect] claim #"+id+" killer="+row.killerName()+" (net="+(killerRow==null?"?":killerRow.ipHash())+") victim="+row.targetName()+" (net="+(victimRow==null?"?":victimRow.ipHash())+") amount="+row.amount());
        CoreUtil.msg(admin,"Logged claim #"+id+"'s network details to console.");
    }
    private String adminName(CommandSender s){return s instanceof Player p?p.getName():"CONSOLE";}
}
