package net.communitysmp.core;

import org.bukkit.Location;
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

    /** Snapshots are stored as WorldEdit .schem files under plugins/SMPCore/monument-snapshots/ — WorldEdit
     *  (installed alongside this) already solves "reliably capture and restore a block region" far better
     *  than anything worth hand-rolling here; this just drives its commands (via console, with explicit
     *  coordinates so nothing depends on where the admin is standing) and wraps them with the safety flow
     *  the spec asks for: preview, explicit confirm, a backup of current state before any overwrite, and
     *  audit logging. See restore()'s own comment for why this can only ever help monuments captured from
     *  here forward, never ones already damaged before this existed. */
    private static final int RADIUS=24;
    private java.io.File snapshotDir(){java.io.File dir=new java.io.File(plugin.getDataFolder(),"monument-snapshots");dir.mkdirs();return dir;}
    private boolean worldEditReady(CommandSender sender){
        if(plugin.getServer().getPluginManager().getPlugin("WorldEdit")==null){CoreUtil.error(sender,"WorldEdit is not installed — monument snapshot/restore is unavailable.");return false;}
        return true;
    }
    private void dispatch(String command){plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),command);}

    boolean command(CommandSender sender,String[] args){
        if(args.length<2){help(sender);return true;}
        if(!(sender instanceof Player player)&&!List.of("list").contains(args[1].toLowerCase(Locale.ROOT))){CoreUtil.error(sender,"Run this in game.");return true;}
        return switch(args[1].toLowerCase(Locale.ROOT)){
            case"list"->{list(sender);yield true;}
            case"locate"->locate((Player)sender,args);
            case"tp"->tp((Player)sender,args);
            case"register"->register((Player)sender,args);
            case"remove"->remove((Player)sender,args);
            case"rename"->rename((Player)sender,args);
            case"snapshot"->snapshot((Player)sender,args);
            case"restore"->restore((Player)sender,args);
            default->{help(sender);yield true;}
        };
    }
    private void help(CommandSender sender){
        CoreUtil.msg(sender,"Monuments: /ashfall monument list");
        CoreUtil.msg(sender,"           /ashfall monument locate <"+String.join("|",STRUCTURE_KEYS.keySet())+">");
        CoreUtil.msg(sender,"           /ashfall monument tp <id>");
        CoreUtil.msg(sender,"           /ashfall monument register <name>");
        CoreUtil.msg(sender,"           /ashfall monument remove <id>");
        CoreUtil.msg(sender,"           /ashfall monument rename <id> <name>");
        CoreUtil.msg(sender,"           /ashfall monument snapshot <id>  (requires WorldEdit)");
        CoreUtil.msg(sender,"           /ashfall monument restore <id> preview|confirm  (requires WorldEdit)");
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
        if(args.length<3){CoreUtil.error(player,"Usage: /ashfall monument register <name>");return true;}
        String name=args[2];
        if(db.savedLocationByName(name)!=null){CoreUtil.error(player,"A location named \""+name+"\" already exists.");return true;}
        Location loc=player.getLocation();
        long id=db.saveLocation(name,loc.getWorld().getName(),loc.getX(),loc.getY(),loc.getZ(),null,true,CoreUtil.id(player));
        CoreUtil.msg(player,"Registered \""+name+"\" (#"+id+") at your current location.");
        return true;
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

    private String schemPath(long id,String suffix){return new java.io.File(snapshotDir(),"loc_"+id+suffix+".schem").getAbsolutePath();}
    private boolean snapshot(Player player,String[] args){
        if(!worldEditReady(player))return true;
        if(args.length<3){CoreUtil.error(player,"Usage: /ashfall monument snapshot <id>");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null){CoreUtil.error(player,"No saved location with that id/name.");return true;}
        int x=(int)row.x(),y=(int)row.y(),z=(int)row.z();
        dispatch("worldedit:pos1 "+(x-RADIUS)+","+(y-RADIUS)+","+(z-RADIUS));
        dispatch("worldedit:pos2 "+(x+RADIUS)+","+(y+RADIUS)+","+(z+RADIUS));
        dispatch("worldedit:copy");
        dispatch("worldedit:schematic save -f "+schemPath(row.id(),""));
        db.setSavedLocationStatus(row.id(),"SNAPSHOTTED");
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
    private boolean restore(Player player,String[] args){
        if(!worldEditReady(player))return true;
        if(args.length<4){CoreUtil.error(player,"Usage: /ashfall monument restore <id> preview|confirm");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null){CoreUtil.error(player,"No saved location with that id/name.");return true;}
        java.io.File schem=new java.io.File(schemPath(row.id(),""));
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
        dispatch("worldedit:pos1 "+(x-RADIUS)+","+(y-RADIUS)+","+(z-RADIUS));
        dispatch("worldedit:pos2 "+(x+RADIUS)+","+(y+RADIUS)+","+(z+RADIUS));
        dispatch("worldedit:copy");
        dispatch("worldedit:schematic save -f "+schemPath(row.id(),"_pre_restore_"+System.currentTimeMillis()));
        dispatch("worldedit:schematic load "+schemPath(row.id(),""));
        dispatch("worldedit:paste -a");
        db.logAudit(player.getName(),"MONUMENT_RESTORE","location=#"+row.id()+" \""+row.name()+"\" claim_warning="+(!warning.isEmpty()));
        CoreUtil.msg(player,"Restored \""+row.name()+"\" from its snapshot. The pre-restore state was itself saved in case this needs to be undone.");
        return true;
    }
}
