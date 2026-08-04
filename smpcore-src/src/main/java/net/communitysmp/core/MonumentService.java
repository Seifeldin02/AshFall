package net.communitysmp.core;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.StructureSearchResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Admin-only structure/location lookup, teleport, and a small named-location registry — /ashfall monument.
 *  Structure searches are genuinely expensive (Bukkit's locateNearestStructure runs the same spiral chunk
 *  search vanilla's own /locate does, synchronously, main-thread-only — it is NOT safe to call off-thread,
 *  Bukkit world-read APIs aren't thread-safe in general) so every result is cached in saved_locations the
 *  moment it's found; a second /monument locate for the same structure type near the same spot returns the
 *  cached entry instead of re-searching, satisfying "don't repeatedly run expensive searches" without
 *  needing to fake asynchrony that the underlying API doesn't actually support. */
final class MonumentService {
    private static final Map<String,String> STRUCTURE_KEYS = new LinkedHashMap<>();
    static {
        STRUCTURE_KEYS.put("monument","monument");
        STRUCTURE_KEYS.put("ancient_city","ancient_city");
        STRUCTURE_KEYS.put("trial_chambers","trial_chambers");
        STRUCTURE_KEYS.put("mansion","mansion");
        STRUCTURE_KEYS.put("stronghold","stronghold");
        STRUCTURE_KEYS.put("fortress","fortress");
        STRUCTURE_KEYS.put("bastion","bastion_remnant");
        STRUCTURE_KEYS.put("end_city","end_city");
    }
    private final SMPCore plugin;
    private final Database db;

    MonumentService(SMPCore plugin){this.plugin=plugin;this.db=plugin.db();}

    List<String> structureTypeKeys(){return List.copyOf(STRUCTURE_KEYS.keySet());}
    /** Backs tab-completion for tp/remove/rename/snapshot/restore — a 2s cache so rapid keystrokes
     *  don't each cost a database round trip, without ever going near the actually-expensive
     *  locateNearestStructure() search path used by /monument locate. */
    private volatile List<String> nameCache=List.of();
    private volatile long nameCacheAt=0;
    List<String> cachedNames(){
        long now=System.currentTimeMillis();
        if(now-nameCacheAt>2000){nameCache=db.savedLocations().stream().map(Database.SavedLocationRow::name).toList();nameCacheAt=now;}
        return nameCache;
    }

    /** Snapshots are stored as WorldEdit .schem files under plugins/SMPCore/monument-snapshots/ — WorldEdit
     *  (installed alongside this) already solves "reliably capture and restore a block region" far better
     *  than anything worth hand-rolling here; this just drives its commands (via console, with explicit
     *  coordinates so nothing depends on where the admin is standing) and wraps them with the safety flow
     *  the spec asks for: preview, explicit confirm, a backup of current state before any overwrite, and
     *  audit logging. See restore()'s own comment for why this can only ever help monuments captured from
     *  here forward, never ones already damaged before this existed. */
    private static final int RADIUS=24;
    private boolean worldEditReady(CommandSender sender){
        if(plugin.getServer().getPluginManager().getPlugin("WorldEdit")==null){CoreUtil.error(sender,"WorldEdit is not installed — monument snapshot/restore is unavailable.");return false;}
        return true;
    }
    /** Everything below drives WorldEdit's own Java API directly (EditSession/Clipboard/Operations) instead
     *  of dispatching "//pos1"/"//copy"-style text commands to console. That used to be how this worked, but
     *  live testing showed WorldEdit's selection/copy commands are effectively player-only here — dispatched
     *  via console (whether local ConsoleCommandSender or RCON's RemoteConsoleCommandSender) they're rejected
     *  by Bukkit's own command routing before WorldEdit ever sees them (dispatchCommand returns false, no
     *  output at all). The API path below has no such requirement — an EditSession only needs a WorldEdit
     *  World, never an actor — and works identically for a monument in any dimension without needing a
     *  player standing anywhere near it. */
    private Clipboard copyRegion(World bukkitWorld,int minX,int minY,int minZ,int maxX,int maxY,int maxZ,int originX,int originY,int originZ) throws Exception{
        com.sk89q.worldedit.world.World weWorld=BukkitAdapter.adapt(bukkitWorld);
        CuboidRegion region=new CuboidRegion(weWorld,BlockVector3.at(minX,minY,minZ),BlockVector3.at(maxX,maxY,maxZ));
        BlockArrayClipboard clipboard=new BlockArrayClipboard(region);
        clipboard.setOrigin(BlockVector3.at(originX,originY,originZ));
        try(EditSession editSession=WorldEdit.getInstance().newEditSession(weWorld)){
            ForwardExtentCopy copy=new ForwardExtentCopy(editSession,region,clipboard,region.getMinimumPoint());
            Operations.complete(copy);
        }
        return clipboard;
    }
    private void pasteClipboard(Clipboard clipboard,World bukkitWorld,int x,int y,int z) throws Exception{
        com.sk89q.worldedit.world.World weWorld=BukkitAdapter.adapt(bukkitWorld);
        try(EditSession editSession=WorldEdit.getInstance().newEditSession(weWorld)){
            Operation paste=new ClipboardHolder(clipboard).createPaste(editSession).to(BlockVector3.at(x,y,z)).ignoreAirBlocks(false).build();
            Operations.complete(paste);
        }
    }
    private void saveSchematic(Clipboard clipboard,java.io.File file) throws Exception{
        file.getParentFile().mkdirs();
        try(ClipboardWriter writer=BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(new java.io.FileOutputStream(file))){
            writer.write(clipboard);
        }
    }
    private Clipboard loadSchematic(java.io.File file) throws Exception{
        try(ClipboardReader reader=BuiltInClipboardFormat.SPONGE_SCHEMATIC.getReader(new java.io.FileInputStream(file))){
            return reader.read();
        }
    }

    boolean command(CommandSender sender,String[] args){
        if(args.length<2){help(sender);return true;}
        if(!(sender instanceof Player player)&&!Set.of("list","inspect","history","refill","reconstruct","snapshot","restore","prism","help").contains(args[1].toLowerCase(Locale.ROOT))){CoreUtil.error(sender,"Run this in game.");return true;}
        return switch(args[1].toLowerCase(Locale.ROOT)){
            case"list"->{list(sender);yield true;}
            case"locate"->locate((Player)sender,args);
            case"tp"->tp((Player)sender,args);
            case"register"->register((Player)sender,args);
            case"remove"->remove((Player)sender,args);
            case"rename"->rename((Player)sender,args);
            case"snapshot"->snapshot(sender,args);
            case"restore"->restore(sender,args);
            case"inspect"->inspect(sender,args);
            case"history"->history(sender,args);
            case"refill"->refill(sender,args);
            case"reconstruct"->reconstruct(sender,args);
            case"prism"->prism(sender,args);
            case"help"->{restorationHelp(sender);yield true;}
            default->{help(sender);yield true;}
        };
    }
    private void help(CommandSender sender){
        CoreUtil.msg(sender,"Monuments: /ashfall monument list");
        CoreUtil.msg(sender,"           /ashfall monument locate <"+String.join("|",STRUCTURE_KEYS.keySet())+">");
        CoreUtil.msg(sender,"           /ashfall monument tp <id>");
        CoreUtil.msg(sender,"           /ashfall monument register <name> [type]");
        CoreUtil.msg(sender,"           /ashfall monument inspect <id>");
        CoreUtil.msg(sender,"           /ashfall monument remove <id>");
        CoreUtil.msg(sender,"           /ashfall monument rename <id> <name>");
        CoreUtil.msg(sender,"           /ashfall monument snapshot <id>  (requires WorldEdit)");
        CoreUtil.msg(sender,"           /ashfall monument restore <id> preview|confirm  (requires WorldEdit)");
        CoreUtil.msg(sender,"           /ashfall monument refill <id> scan|preview|confirm [force]");
        CoreUtil.msg(sender,"           /ashfall monument history <id>");
        CoreUtil.msg(sender,"           /ashfall monument reconstruct <id> preview|confirm  (same-seed proof of concept, requires WorldEdit)");
        CoreUtil.msg(sender,"           /ashfall monument prism <id>  (prints a ready-to-use Prism lookup for that location)");
        CoreUtil.msg(sender,"           /ashfall monument help  (explains which restoration path to use and why)");
    }

    private void list(CommandSender sender){
        List<Database.SavedLocationRow> rows=db.savedLocations();
        if(rows.isEmpty()){CoreUtil.msg(sender,"No monuments or locations saved yet. Use /ashfall monument locate <type> or register <name>.");return;}
        CoreUtil.msg(sender,"Saved locations ("+rows.size()+"):");
        for(Database.SavedLocationRow row:rows)
            CoreUtil.msg(sender,"  #"+row.id()+" "+row.name()+(row.manual()?" (registered)":" ["+row.structureType()+"]")+" — "+row.world()+" "+(int)row.x()+","+(int)row.y()+","+(int)row.z()+" — "+row.status());
    }

    private boolean locate(Player player,String[] args){
        if(args.length<3){CoreUtil.error(player,"Usage: /ashfall monument locate <"+String.join("|",STRUCTURE_KEYS.keySet())+">");return true;}
        String typeArg=args[2].toLowerCase(Locale.ROOT);String key=STRUCTURE_KEYS.get(typeArg);
        if(key==null){CoreUtil.error(player,"Unknown structure type. Options: "+String.join(", ",STRUCTURE_KEYS.keySet()));return true;}
        Structure structure=Registry.STRUCTURE.get(NamespacedKey.minecraft(key));
        if(structure==null){CoreUtil.error(player,"That structure type isn't available in this world/version.");return true;}
        World world=player.getWorld();Location origin=player.getLocation();
        Database.SavedLocationRow nearestCached=nearestExisting(origin,typeArg);
        if(nearestCached!=null){CoreUtil.msg(player,"Already cached as #"+nearestCached.id()+" \""+nearestCached.name()+"\" — use /ashfall monument tp "+nearestCached.id()+". (Searching again would be an expensive re-scan for a structure we already know about.)");return true;}
        CoreUtil.msg(player,"Searching for the nearest "+typeArg+"… this may briefly affect server performance, same as vanilla /locate.");
        StructureSearchResult result;
        try{result=world.locateNearestStructure(origin,structure,100,false);}
        catch(Exception ex){CoreUtil.error(player,"Search failed: "+ex.getMessage());return true;}
        if(result==null||result.getLocation()==null){CoreUtil.error(player,"No "+typeArg+" found within a reasonable search radius.");return true;}
        Location loc=result.getLocation();
        String name=typeArg+"_"+(countOfType(typeArg)+1);
        long id=db.saveLocation(name,loc.getWorld().getName(),loc.getX(),loc.getY(),loc.getZ(),typeArg,false,CoreUtil.id(player));
        CoreUtil.msg(player,"Found "+typeArg+" at "+loc.getWorld().getName()+" "+loc.getBlockX()+","+loc.getBlockY()+","+loc.getBlockZ()+" — cached as #"+id+" (\""+name+"\").");
        return true;
    }
    private long countOfType(String type){return db.savedLocations().stream().filter(r->type.equals(r.structureType())).count();}
    /** Within 200 blocks (structures of the same kind are always much farther apart than that in vanilla
     *  generation) of an already-cached entry of the same type counts as "the same one" — avoids caching
     *  near-duplicate entries if an admin runs /locate again from a slightly different spot. */
    private Database.SavedLocationRow nearestExisting(Location origin,String type){
        if(origin.getWorld()==null)return null;
        for(Database.SavedLocationRow row:db.savedLocations()){
            if(!type.equals(row.structureType())||!row.world().equals(origin.getWorld().getName()))continue;
            double dx=row.x()-origin.getX(),dz=row.z()-origin.getZ();
            if(dx*dx+dz*dz<=200.0*200.0)return row;
        }
        return null;
    }

    private boolean tp(Player player,String[] args){
        if(args.length<3){CoreUtil.error(player,"Usage: /ashfall monument tp <id>");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null){CoreUtil.error(player,"No saved location with that id/name.");return true;}
        World world=plugin.getServer().getWorld(row.world());
        if(world==null){CoreUtil.error(player,"That location's world isn't loaded.");return true;}
        player.teleport(new Location(world,row.x(),row.y(),row.z()));
        CoreUtil.msg(player,"Teleported to \""+row.name()+"\".");
        return true;
    }
    private boolean register(Player player,String[] args){
        if(args.length<3){CoreUtil.error(player,"Usage: /ashfall monument register <name> [type]");return true;}
        String name=args[2];
        if(db.savedLocationByName(name)!=null){CoreUtil.error(player,"A location named \""+name+"\" already exists.");return true;}
        String type=null;
        if(args.length>=4){
            type=args[3].toLowerCase(Locale.ROOT);
            if(!STRUCTURE_KEYS.containsKey(type)&&!type.equals("custom")){CoreUtil.error(player,"Unknown type. Options: "+String.join(", ",STRUCTURE_KEYS.keySet())+", custom");return true;}
        }
        Location loc=player.getLocation();
        long id=db.saveLocation(name,loc.getWorld().getName(),loc.getX(),loc.getY(),loc.getZ(),type,true,CoreUtil.id(player));
        db.logAudit(player.getName(),"MONUMENT_REGISTER","location=#"+id+" \""+name+"\" type="+(type==null?"none":type));
        CoreUtil.msg(player,"Registered \""+name+"\" (#"+id+") at your current location"+(type!=null?" as type \""+type+"\"":"")+".");
        return true;
    }
    private boolean inspect(CommandSender sender,String[] args){
        if(args.length<3){CoreUtil.error(sender,"Usage: /ashfall monument inspect <id>");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null){CoreUtil.error(sender,"No saved location with that id/name.");return true;}
        boolean hasSnapshot=schemFile(row.id(),"").isFile();
        String claimInfo="none";
        World world=plugin.getServer().getWorld(row.world());
        if(world!=null){
            FactionService.Claim claim=plugin.factions().claimAt(new Location(world,(int)row.x(),(int)row.y(),(int)row.z()));
            claimInfo=claim!=null?claim.faction().name():"none directly on center (edges not re-checked here — see restore preview for a full boundary sweep)";
        }
        CoreUtil.msg(sender,"Monument #"+row.id()+" \""+row.name()+"\"");
        CoreUtil.msg(sender,"  Type: "+(row.structureType()==null?"unset":row.structureType())+" | Status: "+row.status()+" | "+(row.manual()?"manually registered":"auto-discovered")+" by "+row.registeredBy());
        CoreUtil.msg(sender,"  Location: "+row.world()+" "+(int)row.x()+","+(int)row.y()+","+(int)row.z()+" | Safe boundary radius: "+row.boundaryRadius());
        CoreUtil.msg(sender,"  Discovered: "+CoreUtil.timeAgo(row.discoveredAt())+" | Faction claim at center: "+claimInfo);
        CoreUtil.msg(sender,"  Snapshot: "+(hasSnapshot?"available (taken "+CoreUtil.timeAgo(row.lastSnapshotAt())+")":"none — WorldEdit restoration unavailable until /ashfall monument snapshot is run"));
        CoreUtil.msg(sender,"  Last restoration: "+(row.lastRestoredAt()==0?"never":CoreUtil.timeAgo(row.lastRestoredAt())));
        CoreUtil.msg(sender,"  Last loot refill: "+(row.lastRefillAt()==0?"never":CoreUtil.timeAgo(row.lastRefillAt())));
        int registeredContainers=db.refillContainers(row.id()).size();
        CoreUtil.msg(sender,"  Registered refill containers: "+registeredContainers+(registeredContainers==0?" — run /ashfall monument refill "+row.id()+" scan first":""));
        return true;
    }
    private boolean history(CommandSender sender,String[] args){
        if(args.length<3){CoreUtil.error(sender,"Usage: /ashfall monument history <id>");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null){CoreUtil.error(sender,"No saved location with that id/name.");return true;}
        List<Database.AuditRow> entries=db.monumentAudit(row.id(),20);
        if(entries.isEmpty()){CoreUtil.msg(sender,"No restoration/refill history for \""+row.name()+"\" yet.");return true;}
        CoreUtil.msg(sender,"History for \""+row.name()+"\" (#"+row.id()+"), most recent first:");
        for(Database.AuditRow entry:entries)CoreUtil.msg(sender,"  "+CoreUtil.timeAgo(entry.occurredAt())+" — "+entry.adminName()+" — "+entry.action()+" — "+entry.detail());
        return true;
    }
    /** Path A: Prism rollback. Prism (installed alongside this) already has its own full lookup/preview/
     *  rollback/restore(undo) workflow — genuinely better than anything worth re-implementing — but its
     *  commands are registered player-only (verified live: "/prism lookup ..." dispatched via console,
     *  either local ConsoleCommandSender or RCON's RemoteConsoleCommandSender, is rejected by Bukkit's own
     *  command routing before Prism ever sees it, the same failure WorldEdit's text commands hit). Prism's
     *  own workflow is inherently interactive anyway — an admin stands at the damage, looks up what happened,
     *  previews a rollback, then confirms — so there's nothing to script around here. This just saves typing
     *  out the coordinates by hand: it prints a ready-to-run Prism command with this monument's location and
     *  boundary radius already filled in. */
    private boolean prism(CommandSender sender,String[] args){
        if(args.length<3){CoreUtil.error(sender,"Usage: /ashfall monument prism <id>");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null){CoreUtil.error(sender,"No saved location with that id/name.");return true;}
        if(plugin.getServer().getPluginManager().getPlugin("Prism")==null){CoreUtil.error(sender,"Prism is not installed.");return true;}
        int x=(int)row.x(),y=(int)row.y(),z=(int)row.z();
        CoreUtil.msg(sender,"Prism rollback for \""+row.name()+"\" — run these yourself, standing anywhere (Prism accepts an explicit at:, so you don't need to be at the monument):");
        CoreUtil.msg(sender,"  1. Inspect:  /prism lookup at:"+x+","+y+","+z+" radius:"+row.boundaryRadius()+" action:block-break,block-place world:"+row.world());
        CoreUtil.msg(sender,"     (add player:<name> to scope to one suspect, since:<time> e.g. since:2h to limit the window)");
        CoreUtil.msg(sender,"  2. Preview:  /prism preview");
        CoreUtil.msg(sender,"  3. Apply:    /prism rollback at:"+x+","+y+","+z+" radius:"+row.boundaryRadius()+" action:block-break,block-place world:"+row.world()+" player:<suspect>");
        CoreUtil.msg(sender,"  4. Undo:     /prism restore  (reverses the most recent rollback)");
        CoreUtil.msg(sender,"  Container transactions (item-insert/item-remove) aren't included above on purpose — if you're separately confiscating items, rolling those back too would give the items back. Add action:item-insert,item-remove yourself only if you want that.");
        db.logAudit(sender.getName(),"MONUMENT_PRISM_LOOKUP","location=#"+row.id()+" \""+row.name()+"\"");
        return true;
    }
    private void restorationHelp(CommandSender sender){
        CoreUtil.msg(sender,"Monument restoration — three paths, pick based on what's available for the damage:");
        CoreUtil.msg(sender,"  A) Prism rollback — damage happened AFTER Prism started logging (has real history). /ashfall monument prism <id>. Repairs only the logged player actions; nothing else is touched.");
        CoreUtil.msg(sender,"  B) WorldEdit snapshot — a clean /ashfall monument snapshot was taken BEFORE the damage. /ashfall monument restore <id> preview|confirm. Repairs future damage only — can't recover something never snapshotted.");
        CoreUtil.msg(sender,"  C) Same-seed reconstruction — neither of the above exists for an already-damaged vanilla structure. /ashfall monument reconstruct <id> preview|confirm. Regenerates the structure from a disposable same-seed temp world; verified live and safe for vanilla structures, but has no way to know what a PLAYER built nearby, so always check the claim-overlap warning first.");
        CoreUtil.msg(sender,"  Structural restoration (any path above) is NOT loot refill — chest contents are handled separately: /ashfall monument refill <id> scan|preview|confirm.");
        CoreUtil.msg(sender,"  All paths require preview before confirm, and B/C both back up the current state first, so a restore mistake is itself undoable.");
    }
    private boolean remove(Player player,String[] args){
        if(args.length<3){CoreUtil.error(player,"Usage: /ashfall monument remove <id>");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null||!db.deleteSavedLocation(row.id())){CoreUtil.error(player,"No saved location with that id/name.");return true;}
        CoreUtil.msg(player,"Removed \""+row.name()+"\".");
        return true;
    }
    private boolean rename(Player player,String[] args){
        if(args.length<4){CoreUtil.error(player,"Usage: /ashfall monument rename <id> <name>");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null){CoreUtil.error(player,"No saved location with that id/name.");return true;}
        if(db.savedLocationByName(args[3])!=null){CoreUtil.error(player,"A location named \""+args[3]+"\" already exists.");return true;}
        db.renameSavedLocation(row.id(),args[3]);
        CoreUtil.msg(player,"Renamed \""+row.name()+"\" to \""+args[3]+"\".");
        return true;
    }
    private Database.SavedLocationRow resolve(String idOrName){
        try{return db.savedLocation(Long.parseLong(idOrName));}catch(NumberFormatException ignored){return db.savedLocationByName(idOrName);}
    }

    /** WorldEdit's "/schematic save -f <name>" takes a name relative to ITS OWN sandboxed schematics root
     *  (plugins/WorldEdit/schematics/, per WorldEdit's own config.yml "saving.dir") and rejects anything
     *  containing drive letters or backslashes as "invalid characters" — an absolute Windows path (what this
     *  used to return) doesn't work as an argument to that command at all. schemPath() is what to pass to
     *  worldedit: commands; schemFile() is the actual java.io.File, for existence checks and copying, at the
     *  location WorldEdit itself will have written it to. */
    private String schemPath(long id,String suffix){return "loc_"+id+suffix+".schem";}
    private java.io.File schemFile(long id,String suffix){
        org.bukkit.plugin.Plugin worldEdit=plugin.getServer().getPluginManager().getPlugin("WorldEdit");
        java.io.File schematicsDir=worldEdit!=null?new java.io.File(worldEdit.getDataFolder(),"schematics"):new java.io.File(plugin.getDataFolder(),"monument-snapshots");
        return new java.io.File(schematicsDir,schemPath(id,suffix));
    }
    private boolean snapshot(CommandSender player,String[] args){
        if(!worldEditReady(player))return true;
        if(args.length<3){CoreUtil.error(player,"Usage: /ashfall monument snapshot <id>");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null){CoreUtil.error(player,"No saved location with that id/name.");return true;}
        World world=plugin.getServer().getWorld(row.world());
        if(world==null){CoreUtil.error(player,"That location's world isn't loaded.");return true;}
        int x=(int)row.x(),y=(int)row.y(),z=(int)row.z();
        try{
            Clipboard clipboard=copyRegion(world,x-RADIUS,y-RADIUS,z-RADIUS,x+RADIUS,y+RADIUS,z+RADIUS,x,y,z);
            saveSchematic(clipboard,schemFile(row.id(),""));
        }catch(Exception e){CoreUtil.error(player,"Snapshot failed: "+e);plugin.getLogger().warning("[monument snapshot] "+e);return true;}
        db.setSavedLocationStatus(row.id(),"SNAPSHOTTED");
        db.markSnapshot(row.id());
        db.logAudit(player.getName(),"MONUMENT_SNAPSHOT","location=#"+row.id()+" \""+row.name()+"\" radius="+RADIUS);
        CoreUtil.msg(player,"Snapshotted "+RADIUS*2+"x"+RADIUS*2+"x"+RADIUS*2+" around \""+row.name()+"\". This can now be restored later if it's damaged.");
        return true;
    }
    /** Can only ever restore a monument snapshotted from HERE FORWARD — there is no historical data for
     *  anything already damaged before this system existed (nothing in this codebase, CoreProtect was never
     *  installed, and WorldEdit obviously can't retroactively know what a region looked like before it was
     *  ever asked to remember it). Blindly regenerating a damaged structure from the world seed isn't done
     *  either: even a small amount of legitimate player building nearby would get silently destroyed, and
     *  there's no reliable way to distinguish "this block is part of the structure" from "a player built
     *  this next to it" after the fact. preview checks the paste region against faction claims (the one
     *  automated safety check that IS reliably possible) and requires confirm as a separate step; confirm
     *  additionally snapshots current state first, so even a restore mistake is itself undoable. */
    private boolean restore(CommandSender player,String[] args){
        if(!worldEditReady(player))return true;
        if(args.length<4){CoreUtil.error(player,"Usage: /ashfall monument restore <id> preview|confirm");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null){CoreUtil.error(player,"No saved location with that id/name.");return true;}
        java.io.File schem=schemFile(row.id(),"");
        if(!schem.isFile()){CoreUtil.error(player,"No snapshot exists for \""+row.name()+"\". Only locations snapshotted with /ashfall monument snapshot can be restored — this cannot recover damage from before a snapshot was taken.");return true;}
        boolean confirm=args[3].equalsIgnoreCase("confirm");
        if(!confirm&&!args[3].equalsIgnoreCase("preview")){CoreUtil.error(player,"Usage: /ashfall monument restore <id> preview|confirm");return true;}
        int x=(int)row.x(),y=(int)row.y(),z=(int)row.z();
        World world=plugin.getServer().getWorld(row.world());
        if(world==null){CoreUtil.error(player,"That location's world isn't loaded.");return true;}
        FactionService.Claim claim=plugin.factions().claimAt(new Location(world,x,y,z));
        StringBuilder warning=new StringBuilder();
        for(int dx=-RADIUS;dx<=RADIUS&&warning.isEmpty();dx+=RADIUS)for(int dz=-RADIUS;dz<=RADIUS&&warning.isEmpty();dz+=RADIUS){
            FactionService.Claim other=plugin.factions().claimAt(new Location(world,x+dx,y,z+dz));
            if(other!=null&&(claim==null||other.faction().id()!=claim.faction().id()))warning.append("The restore region overlaps ").append(other.faction().name()).append("'s claim. ");
        }
        CoreUtil.msg(player,"Restore preview for \""+row.name()+"\": "+RADIUS*2+"x"+RADIUS*2+"x"+RADIUS*2+" region at "+row.world()+" "+x+","+y+","+z+".");
        if(!warning.isEmpty())CoreUtil.error(player,warning.toString()+"Proceeding with confirm will overwrite blocks there.");
        if(!confirm){CoreUtil.msg(player,"Preview only — nothing changed. Run 'restore "+args[2]+" confirm' to actually apply it.");return true;}
        try{
            Clipboard before=copyRegion(world,x-RADIUS,y-RADIUS,z-RADIUS,x+RADIUS,y+RADIUS,z+RADIUS,x,y,z);
            saveSchematic(before,schemFile(row.id(),"_pre_restore_"+System.currentTimeMillis()));
            Clipboard toRestore=loadSchematic(schem);
            pasteClipboard(toRestore,world,x,y,z);
        }catch(Exception e){CoreUtil.error(player,"Restore failed: "+e);plugin.getLogger().warning("[monument restore] "+e);return true;}
        db.markRestored(row.id());
        db.logAudit(player.getName(),"MONUMENT_RESTORE","location=#"+row.id()+" \""+row.name()+"\" claim_warning="+(!warning.isEmpty()));
        CoreUtil.msg(player,"Restored \""+row.name()+"\" from its snapshot. The pre-restore state was itself saved in case this needs to be undone.");
        return true;
    }

    /** Vanilla structure loot table for each supported type's primary/flagship chest. Structures with
     *  several distinct loot pools (trial_chambers: corridor/reward_common/reward_rare/reward_unique;
     *  bastion_remnant: bridge/hoglin_stable/treasure/other; stronghold: corridor/crossing/library) only
     *  get their single most representative table here — refill can't know which specific pool a given
     *  block originally used once its container has already been looted and its loot-table reference
     *  cleared, so it applies one reasonable default rather than guessing wrong. "monument" (ocean
     *  monument) has no chest loot table in vanilla at all — its "loot" is the gold blocks themselves —
     *  so it's intentionally absent and refill reports it as not applicable. */
    private static final Map<String,String> REFILL_LOOT_TABLES = Map.of(
        "ancient_city","chests/ancient_city",
        "trial_chambers","chests/trial_chambers/reward_common",
        "mansion","chests/woodland_mansion",
        "stronghold","chests/stronghold_corridor",
        "fortress","chests/nether_bridge",
        "bastion","chests/bastion_treasure",
        "end_city","chests/end_city_treasure"
    );
    private static final Set<Material> CONTAINER_TYPES=Set.of(Material.CHEST,Material.TRAPPED_CHEST,Material.BARREL,Material.DISPENSER,Material.DROPPER,Material.HOPPER,
        Material.SHULKER_BOX,Material.WHITE_SHULKER_BOX,Material.ORANGE_SHULKER_BOX,Material.MAGENTA_SHULKER_BOX,Material.LIGHT_BLUE_SHULKER_BOX,Material.YELLOW_SHULKER_BOX,
        Material.LIME_SHULKER_BOX,Material.PINK_SHULKER_BOX,Material.GRAY_SHULKER_BOX,Material.LIGHT_GRAY_SHULKER_BOX,Material.CYAN_SHULKER_BOX,Material.PURPLE_SHULKER_BOX,
        Material.BLUE_SHULKER_BOX,Material.BROWN_SHULKER_BOX,Material.GREEN_SHULKER_BOX,Material.RED_SHULKER_BOX,Material.BLACK_SHULKER_BOX);
    private static final long REFILL_COOLDOWN_MS=24L*60*60*1000;

    /** Separate from restore() by design — pasting a saved schematic's chest contents back in would hand
     *  out the exact same items every time (a real dupe vector), so refill never touches schematics. It
     *  instead re-arms each registered container's vanilla LootTable reference (Bukkit's Lootable API);
     *  the actual randomized items only get generated the next time a player opens it, exactly like a
     *  freshly-generated structure chest would, respecting difficulty/luck/RNG for real instead of us
     *  hand-rolling a fake version of that logic. */
    private boolean refill(CommandSender player,String[] args){
        if(args.length<4){CoreUtil.error(player,"Usage: /ashfall monument refill <id> scan|preview|confirm [force]");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null){CoreUtil.error(player,"No saved location with that id/name.");return true;}
        String action=args[3].toLowerCase(Locale.ROOT);
        World world=plugin.getServer().getWorld(row.world());
        if(world==null){CoreUtil.error(player,"That location's world isn't loaded.");return true;}
        int cx=(int)row.x(),cy=(int)row.y(),cz=(int)row.z(),radius=row.boundaryRadius();
        return switch(action){
            case"scan"->refillScan(player,row,world,cx,cy,cz,radius);
            case"preview"->refillApply(player,row,world,false,args.length>4&&args[4].equalsIgnoreCase("force"));
            case"confirm"->refillApply(player,row,world,true,args.length>4&&args[4].equalsIgnoreCase("force"));
            default->{CoreUtil.error(player,"Usage: /ashfall monument refill <id> scan|preview|confirm [force]");yield true;}
        };
    }
    private boolean refillScan(CommandSender player,Database.SavedLocationRow row,World world,int cx,int cy,int cz,int radius){
        CoreUtil.msg(player,"Scanning "+(radius*2)+"x"+(radius*2)+"x"+(radius*2)+" around \""+row.name()+"\" for containers… this runs once, on demand, never automatically.");
        int found=0;
        for(int x=cx-radius;x<=cx+radius;x++)for(int y=Math.max(world.getMinHeight(),cy-radius);y<=Math.min(world.getMaxHeight()-1,cy+radius);y++)for(int z=cz-radius;z<=cz+radius;z++){
            Material type=world.getBlockAt(x,y,z).getType();
            if(!CONTAINER_TYPES.contains(type))continue;
            db.registerRefillContainer(row.id(),world.getName(),x,y,z,null);
            found++;
        }
        CoreUtil.msg(player,"Scan complete: "+found+" container(s) found and registered to \""+row.name()+"\". Run /ashfall monument refill "+row.id()+" preview to see what's eligible.");
        return true;
    }
    private boolean refillApply(CommandSender player,Database.SavedLocationRow row,World world,boolean confirm,boolean force){
        String lootKey=row.structureType()==null?null:REFILL_LOOT_TABLES.get(row.structureType());
        List<Database.RefillContainerRow> containers=db.refillContainers(row.id());
        if(containers.isEmpty()){CoreUtil.error(player,"No registered containers for \""+row.name()+"\" — run /ashfall monument refill "+row.id()+" scan first.");return true;}
        if(lootKey==null){CoreUtil.error(player,"No vanilla loot table is defined for structure type \""+(row.structureType()==null?"unset":row.structureType())+"\" — refill unavailable. (Ocean monuments have no chest loot table in vanilla at all.)");return true;}
        org.bukkit.loot.LootTable table=plugin.getServer().getLootTable(NamespacedKey.minecraft(lootKey));
        if(table==null){CoreUtil.error(player,"Loot table \""+lootKey+"\" isn't available on this server/version.");return true;}
        long now=System.currentTimeMillis();
        List<Database.RefillContainerRow> eligible=new java.util.ArrayList<>(),skippedPlayerItems=new java.util.ArrayList<>(),skippedCooldown=new java.util.ArrayList<>(),skippedPending=new java.util.ArrayList<>();
        for(Database.RefillContainerRow c:containers){
            org.bukkit.block.BlockState state=world.getBlockAt(c.x(),c.y(),c.z()).getState();
            if(!(state instanceof org.bukkit.loot.Lootable lootable))continue;
            if(lootable.getLootTable()!=null&&!force){skippedPending.add(c);continue;}
            org.bukkit.inventory.Inventory inv=state instanceof org.bukkit.block.Container container?container.getInventory():null;
            boolean empty=inv==null||inv.isEmpty();
            boolean onCooldown=!force&&(now-c.lastRefilledAt())<REFILL_COOLDOWN_MS;
            if(!empty&&!force){skippedPlayerItems.add(c);continue;}
            if(onCooldown){skippedCooldown.add(c);continue;}
            eligible.add(c);
        }
        CoreUtil.msg(player,"Refill "+(confirm?"— applying":"preview")+" for \""+row.name()+"\" using loot table \""+lootKey+"\":");
        CoreUtil.msg(player,"  Still has original vanilla loot pending (will refill itself on first open, no action needed): "+skippedPending.size());
        CoreUtil.msg(player,"  Eligible (empty, loot table already spent): "+eligible.size()+" | Skipped (not empty — has items, possibly a player's): "+skippedPlayerItems.size()+" | Skipped (cooldown, refilled <24h ago): "+skippedCooldown.size());
        if(!confirm){
            for(Database.RefillContainerRow c:eligible)CoreUtil.msg(player,"    would refill: "+c.world()+" "+c.x()+","+c.y()+","+c.z());
            CoreUtil.msg(player,"Preview only — nothing changed. Run 'refill "+row.id()+" confirm' to apply, or add 'force' to override skips.");
            return true;
        }
        int applied=0;
        for(Database.RefillContainerRow c:eligible){
            org.bukkit.block.BlockState state=world.getBlockAt(c.x(),c.y(),c.z()).getState();
            if(!(state instanceof org.bukkit.loot.Lootable lootable))continue;
            lootable.setLootTable(table);
            lootable.setSeed(java.util.concurrent.ThreadLocalRandom.current().nextLong());
            state.update(true,false);
            db.markContainerRefilled(c.id(),player.getName());
            applied++;
        }
        db.markRefilled(row.id());
        db.logAudit(player.getName(),"MONUMENT_REFILL","location=#"+row.id()+" \""+row.name()+"\" applied="+applied+" force="+force);
        CoreUtil.msg(player,"Refilled "+applied+" container(s) — fresh randomized loot generates the next time each is opened, same as a newly-generated structure.");
        return true;
    }

    /** Path C: same-seed clean-world reconstruction proof of concept. Minecraft worldgen is a pure function
     *  of (seed, version, worldgen settings, coordinates) — no randomness depends on what's actually been
     *  explored or damaged in the live world. Since staging and production share a seed (confirmed live via
     *  /seed on both: 991369842947417391) and both run generator-settings={} / level-type=normal (vanilla
     *  defaults, confirmed in server.properties), regenerating the same coordinates in an isolated, disposable
     *  temp world reproduces the exact same structure that originally generated in the live world — this is
     *  the same technique real server admins already use by hand (spin up a creative single-player world with
     *  the same seed, locate the structure, copy it with WorldEdit). This wires that up as an in-game command
     *  with the required safety rails: preview, claim-overlap check, explicit confirm, and a pre-change backup.
     *  The one genuine unknown going in was whether WorldEdit's console-driven copy/paste can be pointed at a
     *  DIFFERENT world than the one it last operated on — unlike snapshot()/restore() above, which only ever
     *  touch one single world and so never had to answer that question. */
    private static final String RECONSTRUCTION_WORLD_PREFIX="ashfall_reconstruction_";
    private World ensureReconstructionWorld(CommandSender sender,World reference){
        String tempName=RECONSTRUCTION_WORLD_PREFIX+reference.getEnvironment().name().toLowerCase(Locale.ROOT);
        World existing=plugin.getServer().getWorld(tempName);
        if(existing!=null)return existing;
        CoreUtil.msg(sender,"Creating isolated temp world \""+tempName+"\" with seed "+reference.getSeed()+" (one-time setup for this dimension, may take a moment)...");
        org.bukkit.WorldCreator creator=new org.bukkit.WorldCreator(tempName).seed(reference.getSeed()).environment(reference.getEnvironment()).type(org.bukkit.WorldType.NORMAL).generateStructures(true);
        return creator.createWorld();
    }
    private boolean reconstruct(CommandSender player,String[] args){
        if(!worldEditReady(player))return true;
        if(args.length<4){CoreUtil.error(player,"Usage: /ashfall monument reconstruct <id> preview|confirm");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null){CoreUtil.error(player,"No saved location with that id/name.");return true;}
        boolean confirm=args[3].equalsIgnoreCase("confirm");
        if(!confirm&&!args[3].equalsIgnoreCase("preview")){CoreUtil.error(player,"Usage: /ashfall monument reconstruct <id> preview|confirm");return true;}
        World liveWorld=plugin.getServer().getWorld(row.world());
        if(liveWorld==null){CoreUtil.error(player,"That location's world isn't loaded.");return true;}
        World tempWorld=ensureReconstructionWorld(player,liveWorld);
        if(tempWorld==null){CoreUtil.error(player,"Failed to create the isolated temp world — check console for the underlying error.");return true;}
        int x=(int)row.x(),y=(int)row.y(),z=(int)row.z();
        CoreUtil.msg(player,"Forcing chunk generation in the temp world around "+x+","+y+","+z+"…");
        int minChunkX=(x-RADIUS)>>4,maxChunkX=(x+RADIUS)>>4,minChunkZ=(z-RADIUS)>>4,maxChunkZ=(z+RADIUS)>>4;
        for(int cx=minChunkX;cx<=maxChunkX;cx++)for(int cz=minChunkZ;cz<=maxChunkZ;cz++)tempWorld.getChunkAt(cx,cz).load(true);
        int diffCount=0,sampleStep=2;
        for(int dx=-RADIUS;dx<=RADIUS;dx+=sampleStep)for(int dy=-RADIUS;dy<=RADIUS;dy+=sampleStep)for(int dz=-RADIUS;dz<=RADIUS;dz+=sampleStep){
            int wy=y+dy;if(wy<liveWorld.getMinHeight()||wy>=liveWorld.getMaxHeight()||wy<tempWorld.getMinHeight()||wy>=tempWorld.getMaxHeight())continue;
            if(liveWorld.getBlockAt(x+dx,wy,z+dz).getType()!=tempWorld.getBlockAt(x+dx,wy,z+dz).getType())diffCount++;
        }
        FactionService.Claim claim=plugin.factions().claimAt(new Location(liveWorld,x,y,z));
        StringBuilder warning=new StringBuilder();
        for(int dx=-RADIUS;dx<=RADIUS&&warning.isEmpty();dx+=RADIUS)for(int dz=-RADIUS;dz<=RADIUS&&warning.isEmpty();dz+=RADIUS){
            FactionService.Claim other=plugin.factions().claimAt(new Location(liveWorld,x+dx,y,z+dz));
            if(other!=null&&(claim==null||other.faction().id()!=claim.faction().id()))warning.append("The reconstruction region overlaps ").append(other.faction().name()).append("'s claim. ");
        }
        CoreUtil.msg(player,"Reconstruction "+(confirm?"— applying":"preview")+" for \""+row.name()+"\": sampled ~"+diffCount+" differing blocks (every "+sampleStep+" blocks) between the live structure and the freshly-generated same-seed copy in "+(radiusVolume())+" sampled region.");
        if(!warning.isEmpty())CoreUtil.error(player,warning.toString()+"Proceeding with confirm will overwrite blocks there.");
        if(!confirm){CoreUtil.msg(player,"Preview only — nothing changed. Run 'reconstruct "+args[2]+" confirm' to actually copy the clean structure over the live one.");return true;}
        try{
            Clipboard cleanCopy=copyRegion(tempWorld,x-RADIUS,y-RADIUS,z-RADIUS,x+RADIUS,y+RADIUS,z+RADIUS,x,y,z);
            saveSchematic(cleanCopy,schemFile(row.id(),"_pre_reconstruct_"+System.currentTimeMillis()));
            Clipboard liveBackup=copyRegion(liveWorld,x-RADIUS,y-RADIUS,z-RADIUS,x+RADIUS,y+RADIUS,z+RADIUS,x,y,z);
            saveSchematic(liveBackup,schemFile(row.id(),"_pre_reconstruct_backup_"+System.currentTimeMillis()));
            pasteClipboard(cleanCopy,liveWorld,x,y,z);
        }catch(Exception e){CoreUtil.error(player,"Reconstruction failed: "+e);plugin.getLogger().warning("[monument reconstruct] "+e);return true;}
        db.markRestored(row.id());
        db.logAudit(player.getName(),"MONUMENT_RECONSTRUCT","location=#"+row.id()+" \""+row.name()+"\" sampled_diff="+diffCount+" claim_warning="+(!warning.isEmpty()));
        CoreUtil.msg(player,"Reconstructed \""+row.name()+"\" from a same-seed temp-world copy (via WorldEdit's direct API, cross-world — no console-command world-targeting involved). The pre-reconstruction live state was itself backed up in case this needs to be undone.");
        return true;
    }
    private String radiusVolume(){return (RADIUS*2)+"x"+(RADIUS*2)+"x"+(RADIUS*2);}
}
