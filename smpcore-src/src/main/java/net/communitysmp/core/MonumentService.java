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
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.BoundingBox;
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

    /** A monument's real operating area. Backed by Paper's GeneratedStructure API when available (captured at
     *  /monument locate time — the union of every StructurePiece's bounding box, so a sprawling Ancient City
     *  or Trial Chambers is fully covered instead of truncated to a guessed cube); falls back to a fixed cube
     *  around the center for manual/"custom" registrations or structure types Paper doesn't expose generation
     *  data for. Every command that touches blocks (snapshot/restore/reconstruct/refill scan) goes through
     *  resolveBounds() so there is exactly one source of truth for "how big is this monument". */
    private record Bounds(int minX,int minY,int minZ,int maxX,int maxY,int maxZ,boolean real){
        long blockCount(){return (long)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1);}
        String size(){return (maxX-minX+1)+"x"+(maxY-minY+1)+"x"+(maxZ-minZ+1);}
    }
    private static final int FALLBACK_RADIUS=24;
    /** Union of every GeneratedStructure's bounding box intersecting the chunk at the found location — a
     *  structure spans many chunks, but GeneratedStructure.getBoundingBox() already reports the FULL extent
     *  regardless of which one intersecting chunk you queried it from, so one query is enough. Returns null
     *  (not an error) if Paper has no GeneratedStructure data here — locate() still succeeds either way, it
     *  just falls back to the fixed-radius cube for that monument until/unless this becomes available. */
    private BoundingBox captureStructureBoundingBox(World world,Location loc,Structure structure){
        try{
            var structures=world.getStructures(loc.getBlockX()>>4,loc.getBlockZ()>>4,structure);
            BoundingBox union=null;
            for(GeneratedStructure gs:structures)union=union==null?gs.getBoundingBox().clone():union.union(gs.getBoundingBox());
            return union;
        }catch(Exception e){plugin.getLogger().warning("[monument] structure bounding-box lookup failed: "+e);return null;}
    }
    /** Self-heals monuments registered before real-bounds capture existed (every /monument locate result up
     *  to this point): if a row has a known structure type but no stored bounds yet, this tries capturing
     *  them right now at the row's OWN already-known coordinates — no new search needed, locateNearestStructure
     *  is never called here, only the cheap getStructures(chunk) lookup — and persists them so this only ever
     *  happens once per monument. Falls through to the fixed-radius cube if that still finds nothing (manual
     *  "custom" registrations, or a type Paper has no GeneratedStructure data for). */
    private Bounds resolveBounds(Database.SavedLocationRow row,World world){
        int minY=world.getMinHeight(),maxY=world.getMaxHeight()-1;
        if(!row.hasRealBounds()&&row.structureType()!=null){
            String key=STRUCTURE_KEYS.get(row.structureType());
            Structure structure=key!=null?Registry.STRUCTURE.get(NamespacedKey.minecraft(key)):null;
            if(structure!=null){
                BoundingBox box=captureStructureBoundingBox(world,new Location(world,row.x(),row.y(),row.z()),structure);
                if(box!=null){
                    int bx0=(int)Math.floor(box.getMinX()),by0=(int)Math.floor(box.getMinY()),bz0=(int)Math.floor(box.getMinZ());
                    int bx1=(int)Math.ceil(box.getMaxX())-1,by1=(int)Math.ceil(box.getMaxY())-1,bz1=(int)Math.ceil(box.getMaxZ())-1;
                    db.setStructureBounds(row.id(),bx0,by0,bz0,bx1,by1,bz1);
                    return new Bounds(bx0,Math.max(minY,by0),bz0,bx1,Math.min(maxY,by1),bz1,true);
                }
            }
        }
        if(row.hasRealBounds())return new Bounds(row.minX(),Math.max(minY,row.minY()),row.minZ(),row.maxX(),Math.min(maxY,row.maxY()),row.maxZ(),true);
        int x=(int)row.x(),y=(int)row.y(),z=(int)row.z(),r=row.boundaryRadius();
        return new Bounds(x-r,Math.max(minY,y-r),z-r,x+r,Math.min(maxY,y+r),z+r,false);
    }

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
    private Clipboard copyRegion(World bukkitWorld,Bounds b,int originX,int originY,int originZ) throws Exception{
        com.sk89q.worldedit.world.World weWorld=BukkitAdapter.adapt(bukkitWorld);
        CuboidRegion region=new CuboidRegion(weWorld,BlockVector3.at(b.minX(),b.minY(),b.minZ()),BlockVector3.at(b.maxX(),b.maxY(),b.maxZ()));
        BlockArrayClipboard clipboard=new BlockArrayClipboard(region);
        clipboard.setOrigin(BlockVector3.at(originX,originY,originZ));
        try(EditSession editSession=WorldEdit.getInstance().newEditSession(weWorld)){
            ForwardExtentCopy copy=new ForwardExtentCopy(editSession,region,clipboard,region.getMinimumPoint());
            Operations.complete(copy);
        }
        return clipboard;
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

    /** How many blocks a sync actually found different, and (when apply=true) actually wrote. Previously
     *  confirm pasted the ENTIRE clipboard/cuboid unconditionally — for an Ancient City-sized region that's
     *  ~3.1M blocks written regardless of how much was actually damaged, which is what caused the ~20s
     *  single-tick freeze. Real damage is typically a tiny fraction of the total area (a few dozen to a few
     *  thousand blocks), so writing only the positions that differ turns "always touch millions of blocks"
     *  into "touch roughly as many blocks as were actually broken" — the common case becomes near-instant,
     *  and the worst case (a structure that's mostly different, e.g. never reconstructed before) still scales
     *  with real damage instead of a fixed multi-million-block floor. */
    private record SyncResult(long differing,long applied){}
    /** World-to-world diff+apply (same-seed reconstruction). Comparison uses BlockData (not just Material) via
     *  ChunkSnapshot — cheap in-memory array reads, same technique as the old exactDiffCount() — so a rotated
     *  stair or a different water level counts as "different" too, not just a changed block type. Only when
     *  apply=true does it actually read/write anything through WorldEdit (getFullBlock/setBlock, so container
     *  NBT and other block-entity data comes along with the copy, not just the bare block type). */
    private SyncResult syncWorldToWorld(World source,World target,Bounds b,boolean apply) throws Exception{
        long differing=0,applied=0;
        int minY=Math.max(b.minY(),Math.max(source.getMinHeight(),target.getMinHeight())),maxY=Math.min(b.maxY(),Math.min(source.getMaxHeight(),target.getMaxHeight())-1);
        int minChunkX=b.minX()>>4,maxChunkX=b.maxX()>>4,minChunkZ=b.minZ()>>4,maxChunkZ=b.maxZ()>>4;
        com.sk89q.worldedit.world.World weSource=apply?BukkitAdapter.adapt(source):null;
        com.sk89q.worldedit.world.World weTarget=apply?BukkitAdapter.adapt(target):null;
        try(EditSession sourceSession=apply?WorldEdit.getInstance().newEditSession(weSource):null;
            EditSession targetSession=apply?WorldEdit.getInstance().newEditSession(weTarget):null){
            for(int cx=minChunkX;cx<=maxChunkX;cx++)for(int cz=minChunkZ;cz<=maxChunkZ;cz++){
                org.bukkit.ChunkSnapshot sourceSnap=source.getChunkAt(cx,cz).getChunkSnapshot(false,false,false);
                org.bukkit.ChunkSnapshot targetSnap=target.getChunkAt(cx,cz).getChunkSnapshot(false,false,false);
                int xStart=Math.max(b.minX(),cx*16),xEnd=Math.min(b.maxX(),cx*16+15);
                int zStart=Math.max(b.minZ(),cz*16),zEnd=Math.min(b.maxZ(),cz*16+15);
                for(int x=xStart;x<=xEnd;x++)for(int y=minY;y<=maxY;y++)for(int z=zStart;z<=zEnd;z++){
                    if(sourceSnap.getBlockData(x&15,y,z&15).equals(targetSnap.getBlockData(x&15,y,z&15)))continue;
                    differing++;
                    if(apply){
                        BlockVector3 pos=BlockVector3.at(x,y,z);
                        targetSession.setBlock(pos,sourceSession.getFullBlock(pos));
                        applied++;
                    }
                }
            }
        }
        return new SyncResult(differing,applied);
    }
    /** Clipboard-to-world diff+apply (schematic restore). The clipboard was captured with its origin set to
     *  the monument's own center, so its internal absolute coordinates already line up 1:1 with the live
     *  world's coordinates — no offset math needed. Comparison converts the live block's BlockData to a
     *  WorldEdit BlockState via BukkitAdapter so both sides compare as the same type; a Clipboard has no
     *  chunk-snapshot equivalent (it's already an in-memory structure, not a live chunk system) so this reads
     *  it directly, but that's still plain array access internally, not disk/chunk I/O. */
    private SyncResult syncClipboardToWorld(Clipboard source,World target,Bounds b,boolean apply) throws Exception{
        long differing=0,applied=0;
        int minY=Math.max(b.minY(),target.getMinHeight()),maxY=Math.min(b.maxY(),target.getMaxHeight()-1);
        int minChunkX=b.minX()>>4,maxChunkX=b.maxX()>>4,minChunkZ=b.minZ()>>4,maxChunkZ=b.maxZ()>>4;
        com.sk89q.worldedit.world.World weTarget=apply?BukkitAdapter.adapt(target):null;
        try(EditSession targetSession=apply?WorldEdit.getInstance().newEditSession(weTarget):null){
            for(int cx=minChunkX;cx<=maxChunkX;cx++)for(int cz=minChunkZ;cz<=maxChunkZ;cz++){
                org.bukkit.ChunkSnapshot targetSnap=target.getChunkAt(cx,cz).getChunkSnapshot(false,false,false);
                int xStart=Math.max(b.minX(),cx*16),xEnd=Math.min(b.maxX(),cx*16+15);
                int zStart=Math.max(b.minZ(),cz*16),zEnd=Math.min(b.maxZ(),cz*16+15);
                for(int x=xStart;x<=xEnd;x++)for(int y=minY;y<=maxY;y++)for(int z=zStart;z<=zEnd;z++){
                    BlockVector3 pos=BlockVector3.at(x,y,z);
                    com.sk89q.worldedit.world.block.BlockState sourceBlock=source.getBlock(pos);
                    com.sk89q.worldedit.world.block.BlockState targetBlock=BukkitAdapter.adapt(targetSnap.getBlockData(x&15,y,z&15));
                    if(sourceBlock.equals(targetBlock))continue;
                    differing++;
                    if(apply){targetSession.setBlock(pos,source.getFullBlock(pos));applied++;}
                }
            }
        }
        return new SyncResult(differing,applied);
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
            CoreUtil.msg(sender,"  #"+row.id()+" "+row.name()+(row.manual()?" (registered)":" ["+row.structureType()+"]")+" — "+row.world()+" "+(int)row.x()+","+(int)row.y()+","+(int)row.z()+" — "+row.status()+(row.hasRealBounds()?" [real bounds]":""));
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
        BoundingBox box=captureStructureBoundingBox(world,loc,structure);
        if(box!=null){
            db.setStructureBounds(id,(int)Math.floor(box.getMinX()),(int)Math.floor(box.getMinY()),(int)Math.floor(box.getMinZ()),(int)Math.ceil(box.getMaxX())-1,(int)Math.ceil(box.getMaxY())-1,(int)Math.ceil(box.getMaxZ())-1);
            CoreUtil.msg(player,"Found "+typeArg+" at "+loc.getWorld().getName()+" "+loc.getBlockX()+","+loc.getBlockY()+","+loc.getBlockZ()+" — cached as #"+id+" (\""+name+"\"). Real structure bounds captured: "+((int)Math.ceil(box.getMaxX())-(int)Math.floor(box.getMinX()))+"x"+((int)Math.ceil(box.getMaxY())-(int)Math.floor(box.getMinY()))+"x"+((int)Math.ceil(box.getMaxZ())-(int)Math.floor(box.getMinZ()))+".");
        }else{
            CoreUtil.msg(player,"Found "+typeArg+" at "+loc.getWorld().getName()+" "+loc.getBlockX()+","+loc.getBlockY()+","+loc.getBlockZ()+" — cached as #"+id+" (\""+name+"\"). No real structure bounds available from Paper for this type — falling back to a "+(FALLBACK_RADIUS*2)+"x"+(FALLBACK_RADIUS*2)+"x"+(FALLBACK_RADIUS*2)+" cube; widen with a bigger boundary_radius if needed.");
        }
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
        CoreUtil.msg(player,"Registered \""+name+"\" (#"+id+") at your current location"+(type!=null?" as type \""+type+"\"":"")+". No real structure bounds for a manual registration — uses the fixed-radius cube; run /ashfall monument locate instead if this is meant to track an actual generated structure.");
        return true;
    }
    private boolean inspect(CommandSender sender,String[] args){
        if(args.length<3){CoreUtil.error(sender,"Usage: /ashfall monument inspect <id>");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null){CoreUtil.error(sender,"No saved location with that id/name.");return true;}
        boolean hasSnapshot=schemFile(row.id(),"").isFile();
        String claimInfo="none";
        World world=plugin.getServer().getWorld(row.world());
        Bounds bounds=world!=null?resolveBounds(row,world):null;
        if(world!=null){
            FactionService.Claim claim=plugin.factions().claimAt(new Location(world,(int)row.x(),(int)row.y(),(int)row.z()));
            claimInfo=claim!=null?claim.faction().name():"none at center (see reconstruct/restore preview for a full-area sweep)";
        }
        CoreUtil.msg(sender,"Monument #"+row.id()+" \""+row.name()+"\"");
        CoreUtil.msg(sender,"  Type: "+(row.structureType()==null?"unset":row.structureType())+" | Status: "+row.status()+" | "+(row.manual()?"manually registered":"auto-discovered")+" by "+row.registeredBy());
        CoreUtil.msg(sender,"  Location: "+row.world()+" "+(int)row.x()+","+(int)row.y()+","+(int)row.z());
        if(bounds!=null)CoreUtil.msg(sender,"  Area: "+(bounds.real()?"real structure bounds (Paper GeneratedStructure)":"fallback cube, radius "+row.boundaryRadius()+" — no real structure data captured")+" — "+bounds.size()+" ("+bounds.blockCount()+" blocks) spanning "+bounds.minX()+".."+bounds.maxX()+" X, "+bounds.minY()+".."+bounds.maxY()+" Y, "+bounds.minZ()+".."+bounds.maxZ()+" Z");
        else CoreUtil.msg(sender,"  Area: unknown — world \""+row.world()+"\" isn't loaded right now");
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
     *  out the coordinates by hand: it prints a ready-to-run Prism command with this monument's real area
     *  already filled in (a bounding radius covering the whole captured structure, not just its center). */
    private boolean prism(CommandSender sender,String[] args){
        if(args.length<3){CoreUtil.error(sender,"Usage: /ashfall monument prism <id>");return true;}
        Database.SavedLocationRow row=resolve(args[2]);
        if(row==null){CoreUtil.error(sender,"No saved location with that id/name.");return true;}
        if(plugin.getServer().getPluginManager().getPlugin("Prism")==null){CoreUtil.error(sender,"Prism is not installed.");return true;}
        World world=plugin.getServer().getWorld(row.world());
        int x=(int)row.x(),y=(int)row.y(),z=(int)row.z();
        int radius=row.boundaryRadius();
        if(world!=null){Bounds b=resolveBounds(row,world);radius=Math.max(Math.max(x-b.minX(),b.maxX()-x),Math.max(z-b.minZ(),b.maxZ()-z));}
        CoreUtil.msg(sender,"Prism rollback for \""+row.name()+"\" — run these yourself, standing anywhere (Prism accepts an explicit at:, so you don't need to be at the monument):");
        CoreUtil.msg(sender,"  1. Inspect:  /prism lookup at:"+x+","+y+","+z+" radius:"+radius+" action:block-break,block-place world:"+row.world());
        CoreUtil.msg(sender,"     (add player:<name> to scope to one suspect, since:<time> e.g. since:2h to limit the window)");
        CoreUtil.msg(sender,"  2. Preview:  /prism preview");
        CoreUtil.msg(sender,"  3. Apply:    /prism rollback at:"+x+","+y+","+z+" radius:"+radius+" action:block-break,block-place world:"+row.world()+" player:<suspect>");
        CoreUtil.msg(sender,"  4. Undo:     /prism restore  (reverses the most recent rollback)");
        CoreUtil.msg(sender,"  radius:"+radius+" is a circle covering this monument's full captured area — Prism only takes a radius, not a box, so it may include some area just outside the real structure bounds.");
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
        CoreUtil.msg(sender,"  Area used by B/C/refill: real Paper-captured structure bounds when available (/monument locate captures these), otherwise a fixed-radius cube — check /ashfall monument inspect <id> to see which applies.");
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
        Bounds b=resolveBounds(row,world);
        int x=(int)row.x(),y=(int)row.y(),z=(int)row.z();
        CoreUtil.msg(player,"Snapshotting "+b.size()+" ("+b.blockCount()+" blocks) around \""+row.name()+"\"…"+(b.blockCount()>1_000_000?" this will briefly freeze the whole server (large area).":b.blockCount()>500_000?" this may take a moment.":""));
        try{
            Clipboard clipboard=copyRegion(world,b,x,y,z);
            saveSchematic(clipboard,schemFile(row.id(),""));
        }catch(Exception e){CoreUtil.error(player,"Snapshot failed: "+e);plugin.getLogger().warning("[monument snapshot] "+e);return true;}
        db.setSavedLocationStatus(row.id(),"SNAPSHOTTED");
        db.markSnapshot(row.id());
        db.logAudit(player.getName(),"MONUMENT_SNAPSHOT","location=#"+row.id()+" \""+row.name()+"\" area="+b.size());
        CoreUtil.msg(player,"Snapshotted "+b.size()+" around \""+row.name()+"\". This can now be restored later if it's damaged.");
        return true;
    }
    /** Scans the given bounds for faction claims not belonging to whatever faction (if any) already holds the
     *  center — at chunk grain (claims are chunk-based) rather than every single block, and across the WHOLE
     *  area (interior included, not just the perimeter) so a claim sitting entirely inside a large structure's
     *  bounds is still caught. */
    private String claimWarning(World world,Bounds b,int centerX,int centerY,int centerZ){
        FactionService.Claim center=plugin.factions().claimAt(new Location(world,centerX,centerY,centerZ));
        StringBuilder warning=new StringBuilder();
        Set<Long> warnedFactions=new java.util.HashSet<>();
        int minChunkX=b.minX()>>4,maxChunkX=b.maxX()>>4,minChunkZ=b.minZ()>>4,maxChunkZ=b.maxZ()>>4;
        for(int cx=minChunkX;cx<=maxChunkX;cx++)for(int cz=minChunkZ;cz<=maxChunkZ;cz++){
            FactionService.Claim other=plugin.factions().claimAt(new Location(world,cx*16+8,centerY,cz*16+8));
            if(other!=null&&(center==null||other.faction().id()!=center.faction().id())&&warnedFactions.add(other.faction().id()))
                warning.append("Overlaps ").append(other.faction().name()).append("'s claim. ");
        }
        return warning.toString();
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
    /** The one remaining unbounded cost after switching to diff-only writes: with players online and no
     *  chunked/time-sliced execution built yet, a genuinely massive apply (e.g. a structure that's never been
     *  reconstructed before, so almost everything "differs") would still freeze the server for everyone. Until
     *  that's built, block it outright unless an admin explicitly opts in via maintenance mode (server-list
     *  already shows "under maintenance" for that flag, so this reuses an existing, visible signal rather than
     *  adding a separate one). No online players at all means no one to freeze, so that case is always allowed. */
    private static final long LARGE_APPLY_THRESHOLD=200_000;
    private boolean blockedByLargeApplyGuard(CommandSender sender,long applyCount){
        if(applyCount<=LARGE_APPLY_THRESHOLD)return false;
        boolean anyoneOnline=!plugin.getServer().getOnlinePlayers().isEmpty();
        boolean maintenance=plugin.getConfig().getBoolean("maintenance.enabled",false);
        if(!anyoneOnline||maintenance)return false;
        CoreUtil.error(sender,applyCount+" blocks need writing — that's large enough to freeze the server for everyone online (chunked/time-sliced execution isn't built yet). Either wait for a lower-traffic moment, or explicitly enable maintenance mode first (it already kicks the server list to \"under maintenance\") and run this again.");
        return true;
    }
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
        Bounds b=resolveBounds(row,world);
        String warning=claimWarning(world,b,x,y,z);
        Clipboard toRestore;
        SyncResult scan;
        try{
            toRestore=loadSchematic(schem);
            scan=syncClipboardToWorld(toRestore,world,b,false);
        }catch(Exception e){CoreUtil.error(player,"Scan failed: "+e);plugin.getLogger().warning("[monument restore scan] "+e);return true;}
        CoreUtil.msg(player,"Restore "+(confirm?"— applying":"preview")+" for \""+row.name()+"\": "+b.size()+" area, "+scan.differing()+" block(s) actually differ from the snapshot and would be written (not the full "+b.blockCount()+"-block area).");
        if(!warning.isEmpty())CoreUtil.error(player,warning+"Proceeding with confirm will overwrite blocks there.");
        if(!confirm){CoreUtil.msg(player,"Preview only — nothing changed. Run 'restore "+args[2]+" confirm' to actually apply it.");return true;}
        if(blockedByLargeApplyGuard(player,scan.differing()))return true;
        try{
            Clipboard before=copyRegion(world,b,x,y,z);
            saveSchematic(before,schemFile(row.id(),"_pre_restore_"+System.currentTimeMillis()));
            syncClipboardToWorld(toRestore,world,b,true);
        }catch(Exception e){CoreUtil.error(player,"Restore failed: "+e);plugin.getLogger().warning("[monument restore] "+e);return true;}
        db.markRestored(row.id());
        db.logAudit(player.getName(),"MONUMENT_RESTORE","location=#"+row.id()+" \""+row.name()+"\" area="+b.size()+" applied="+scan.differing()+" claim_warning="+(!warning.isEmpty()));
        CoreUtil.msg(player,"Restored \""+row.name()+"\" from its snapshot — "+scan.differing()+" block(s) written. The pre-restore state was itself saved in case this needs to be undone.");
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
    /** Trial Chamber vaults and trial spawners are deliberately NOT in CONTAINER_TYPES. A vault
     *  (org.bukkit.block.Vault) isn't a Lootable/Container at all — it has no inventory; its reward is
     *  tracked per-player via getRewardedPlayers()/removeRewardedPlayer(UUID), a completely different
     *  mechanic from "regenerate this chest's contents". Paper DOES technically expose a way to let one
     *  specific player re-earn a vault (removeRewardedPlayer), but that's a per-player unlock, not a global
     *  refill, and building that as its own admin flow is out of scope here — this only reports that vaults
     *  and trial spawners were found and explains why they're skipped, rather than silently ignoring or
     *  mishandling them. */
    private static final Set<Material> EXCLUDED_SPECIAL=Set.of(Material.VAULT,Material.TRIAL_SPAWNER);
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
        Bounds b=resolveBounds(row,world);
        return switch(action){
            case"scan"->refillScan(player,row,world,b);
            case"preview"->refillApply(player,row,world,false,args.length>4&&args[4].equalsIgnoreCase("force"));
            case"confirm"->refillApply(player,row,world,true,args.length>4&&args[4].equalsIgnoreCase("force"));
            default->{CoreUtil.error(player,"Usage: /ashfall monument refill <id> scan|preview|confirm [force]");yield true;}
        };
    }
    /** Marker stored in monument_refill_containers.loot_table for a container confirmed genuine (present in
     *  the same-seed pristine copy) but which the pristine copy shows never carries a LootTable NBT tag at
     *  all — e.g. Trial Chambers' reward barrels, which vanilla populates at runtime through the trial/vault
     *  mechanism rather than a static structure loot table. These are real, unambiguously part of the
     *  structure, but there is nothing for Lootable.setLootTable() to re-arm; refillApply() reports them
     *  separately instead of silently skipping or mishandling them. */
    private static final String NO_LOOT_TABLE_MARKER="NO_LOOT_TABLE";
    /** Registers a container position only if the SAME-SEED pristine copy also has a container there — reusing
     *  the exact same disposable temp world reconstruct() uses. Genuineness is "does this exact position
     *  generate as a container at all in an untouched copy", NOT "does it currently carry a loot table" — the
     *  first attempt at this check required a live LootTable NBT tag in the pristine copy, which incorrectly
     *  rejected genuine Trial Chambers reward barrels (confirmed live: /data get block ... LootTable on a
     *  known-genuine barrel in the pristine world returns nothing — those are populated by the trial/vault
     *  reward mechanism at runtime, not a static structure loot table, so requiring one was simply wrong for
     *  that container role). A player's own hopper/dispenser sitting inside a monument's now-much-larger real
     *  bounds will be ordinary terrain in the temp world at that position — not a container of any kind — so
     *  it's still correctly rejected regardless of loot-table presence. Only for structure types with vanilla
     *  worldgen this server can locate (needs WorldEdit for the temp world, same as reconstruct); if that's
     *  unavailable, falls back to the old live-only check with a clear note that it may include false
     *  positives. */
    private boolean refillScan(CommandSender player,Database.SavedLocationRow row,World world,Bounds b){
        World tempWorld=null;
        if(plugin.getServer().getPluginManager().getPlugin("WorldEdit")!=null){
            tempWorld=ensureReconstructionWorld(player,world);
            if(tempWorld!=null){
                int minChunkX=b.minX()>>4,maxChunkX=b.maxX()>>4,minChunkZ=b.minZ()>>4,maxChunkZ=b.maxZ()>>4;
                for(int cx=minChunkX;cx<=maxChunkX;cx++)for(int cz=minChunkZ;cz<=maxChunkZ;cz++)tempWorld.getChunkAt(cx,cz).load(true);
            }
        }
        CoreUtil.msg(player,"Scanning "+b.size()+" ("+b.blockCount()+" blocks) around \""+row.name()+"\" for containers…"+(b.blockCount()>500_000?" this is a large area and may take a moment.":"")+" This runs once, on demand, never automatically.");
        Map<Material,Integer> foundByType=new java.util.EnumMap<>(Material.class);
        Map<Material,Integer> excludedByType=new java.util.EnumMap<>(Material.class);
        int registered=0,rejectedNotGenuine=0,noLootTable=0;
        World finalTempWorld=tempWorld;
        int minChunkX=b.minX()>>4,maxChunkX=b.maxX()>>4,minChunkZ=b.minZ()>>4,maxChunkZ=b.maxZ()>>4;
        for(int cx=minChunkX;cx<=maxChunkX;cx++)for(int cz=minChunkZ;cz<=maxChunkZ;cz++){
            org.bukkit.ChunkSnapshot liveSnap=world.getChunkAt(cx,cz).getChunkSnapshot(false,false,false);
            int xStart=Math.max(b.minX(),cx*16),xEnd=Math.min(b.maxX(),cx*16+15);
            int zStart=Math.max(b.minZ(),cz*16),zEnd=Math.min(b.maxZ(),cz*16+15);
            for(int x=xStart;x<=xEnd;x++)for(int y=b.minY();y<=b.maxY();y++)for(int z=zStart;z<=zEnd;z++){
                Material type=liveSnap.getBlockType(x&15,y,z&15);
                if(EXCLUDED_SPECIAL.contains(type)){excludedByType.merge(type,1,Integer::sum);continue;}
                if(!CONTAINER_TYPES.contains(type))continue;
                String lootMarker=null;
                if(finalTempWorld!=null){
                    org.bukkit.block.BlockState tempState=finalTempWorld.getBlockAt(x,y,z).getState();
                    if(!CONTAINER_TYPES.contains(tempState.getType())){rejectedNotGenuine++;continue;}
                    boolean hasLootTable=tempState instanceof org.bukkit.loot.Lootable lootable&&lootable.getLootTable()!=null;
                    if(!hasLootTable){lootMarker=NO_LOOT_TABLE_MARKER;noLootTable++;}
                }
                db.registerRefillContainer(row.id(),world.getName(),x,y,z,lootMarker);
                foundByType.merge(type,1,Integer::sum);
                registered++;
            }
        }
        if(registered==0&&excludedByType.isEmpty()&&rejectedNotGenuine==0){
            CoreUtil.msg(player,"Scan complete: no supported refillable containers found in \""+row.name()+"\"'s area. Either this structure genuinely has none (e.g. an ocean monument), the area is wrong (check /ashfall monument inspect "+row.id()+" — 'fallback cube' means /monument locate never captured real bounds for this one), or they haven't generated/loaded in yet.");
            return true;
        }
        CoreUtil.msg(player,"Scan complete: "+registered+" container(s) found and registered to \""+row.name()+"\"."+(tempWorld==null?" (WorldEdit unavailable — could not cross-check against a same-seed copy, so this may include player-placed containers that happen to fall inside the area.)":""));
        if(!foundByType.isEmpty()){StringBuilder types=new StringBuilder();foundByType.forEach((m,c)->types.append(m.name()).append('×').append(c).append(' '));CoreUtil.msg(player,"  Found: "+types);}
        if(!excludedByType.isEmpty()){StringBuilder types=new StringBuilder();excludedByType.forEach((m,c)->types.append(m.name()).append('×').append(c).append(' '));CoreUtil.msg(player,"  Skipped (not ordinary refillable containers — see /ashfall monument help): "+types);}
        if(rejectedNotGenuine>0)CoreUtil.msg(player,"  Skipped "+rejectedNotGenuine+" container(s) that the same-seed copy shows are NOT part of the original structure (likely player-placed, just sitting inside this monument's bounds).");
        if(noLootTable>0)CoreUtil.msg(player,"  Note: "+noLootTable+" of the registered containers never carry a loot table even in the pristine copy (e.g. Trial Vault reward barrels, populated at runtime by that mechanism) — they'll show up but refill can't re-arm them via a loot table; see /ashfall monument help.");
        CoreUtil.msg(player,"Run /ashfall monument refill "+row.id()+" preview to see what's eligible.");
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
        List<Database.RefillContainerRow> eligible=new java.util.ArrayList<>(),skippedPlayerItems=new java.util.ArrayList<>(),skippedCooldown=new java.util.ArrayList<>(),skippedPending=new java.util.ArrayList<>(),skippedNoLootTable=new java.util.ArrayList<>();
        for(Database.RefillContainerRow c:containers){
            if(NO_LOOT_TABLE_MARKER.equals(c.lootTable())){skippedNoLootTable.add(c);continue;}
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
        if(!skippedNoLootTable.isEmpty())CoreUtil.msg(player,"  Not applicable: "+skippedNoLootTable.size()+" container(s) confirmed genuine but never loot-table-based (e.g. Trial Vault reward barrels) — refill can't re-arm these via a loot table, nothing to do here.");
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
     *  Drives WorldEdit's Java API directly (same as snapshot/restore) rather than console text commands with
     *  a "-w" world flag — that flag isn't real WorldEdit syntax and silently no-ops; the API path works
     *  cross-world with no such issue since an EditSession is just handed whichever WorldEdit World it needs. */
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
        Bounds b=resolveBounds(row,liveWorld);
        World tempWorld=ensureReconstructionWorld(player,liveWorld);
        if(tempWorld==null){CoreUtil.error(player,"Failed to create the isolated temp world — check console for the underlying error.");return true;}
        int x=(int)row.x(),y=(int)row.y(),z=(int)row.z();
        CoreUtil.msg(player,"Forcing chunk generation in the temp world across "+b.size()+"…"+(b.blockCount()>500_000?" large area, may take a moment.":""));
        int minChunkX=b.minX()>>4,maxChunkX=b.maxX()>>4,minChunkZ=b.minZ()>>4,maxChunkZ=b.maxZ()>>4;
        for(int cx=minChunkX;cx<=maxChunkX;cx++)for(int cz=minChunkZ;cz<=maxChunkZ;cz++)tempWorld.getChunkAt(cx,cz).load(true);
        SyncResult scan;
        try{scan=syncWorldToWorld(tempWorld,liveWorld,b,false);}
        catch(Exception e){CoreUtil.error(player,"Scan failed: "+e);plugin.getLogger().warning("[monument reconstruct scan] "+e);return true;}
        String warning=claimWarning(liveWorld,b,x,y,z);
        CoreUtil.msg(player,"Reconstruction "+(confirm?"— applying":"preview")+" for \""+row.name()+"\": "+b.size()+" area ("+(row.hasRealBounds()?"real structure bounds":"fallback cube")+") — "+scan.differing()+" block(s) actually differ and would be written (not the full "+b.blockCount()+"-block area).");
        if(!warning.isEmpty())CoreUtil.error(player,warning+"Proceeding with confirm will overwrite blocks there.");
        if(!confirm){CoreUtil.msg(player,"Preview only — nothing changed. Run 'reconstruct "+args[2]+" confirm' to actually copy the clean structure over the live one.");return true;}
        if(blockedByLargeApplyGuard(player,scan.differing()))return true;
        try{
            Clipboard liveBackup=copyRegion(liveWorld,b,x,y,z);
            saveSchematic(liveBackup,schemFile(row.id(),"_pre_reconstruct_backup_"+System.currentTimeMillis()));
            scan=syncWorldToWorld(tempWorld,liveWorld,b,true);
        }catch(Exception e){CoreUtil.error(player,"Reconstruction failed: "+e);plugin.getLogger().warning("[monument reconstruct] "+e);return true;}
        db.markRestored(row.id());
        db.logAudit(player.getName(),"MONUMENT_RECONSTRUCT","location=#"+row.id()+" \""+row.name()+"\" area="+b.size()+" applied="+scan.applied()+" claim_warning="+(!warning.isEmpty()));
        CoreUtil.msg(player,"Reconstructed \""+row.name()+"\" from a same-seed temp-world copy ("+scan.applied()+" blocks changed). The pre-reconstruction live state was itself backed up in case this needs to be undone.");
        return true;
    }
}
