package net.communitysmp.core;

import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;

import java.util.Locale;

/** Applies reversible safety borders only; it never pregenerates or deletes chunks. */
final class WorldBorderService {
    private final SMPCore plugin;
    private final Database db;

    WorldBorderService(SMPCore plugin){this.plugin=plugin;this.db=plugin.db();if(plugin.getConfig().getBoolean("performance.world-borders.enabled",true))apply();}

    void apply(){
        for(World world:plugin.getServer().getWorlds()){
            String key=dimension(world),path="performance.world-borders.sizes."+key;
            if(!plugin.getConfig().isSet(path))continue;
            double size=Math.max(1000,plugin.getConfig().getDouble(path));
            WorldBorder border=world.getWorldBorder();
            String state="world_border_previous:"+world.getUID();
            if(db.state(state)==null)db.state(state,border.getSize()+","+border.getCenter().getX()+","+border.getCenter().getZ());
            border.setSize(size);
            border.setWarningDistance(Math.max(5,plugin.getConfig().getInt("performance.world-borders.warning-distance",32)));
            border.setDamageBuffer(5);
            border.setDamageAmount(0.2);
        }
    }
    void restore(){
        for(World world:plugin.getServer().getWorlds()){
            String state="world_border_previous:"+world.getUID(),raw=db.state(state);if(raw==null)continue;
            try{String[] parts=raw.split(",");WorldBorder border=world.getWorldBorder();border.setCenter(Double.parseDouble(parts[1]),Double.parseDouble(parts[2]));border.setSize(Double.parseDouble(parts[0]));}catch(Exception error){plugin.getLogger().warning("Could not restore previous border for "+world.getName()+": "+error.getMessage());}
        }
    }
    void status(CommandSender sender){for(World world:plugin.getServer().getWorlds())CoreUtil.msg(sender,world.getName()+": "+Math.round(world.getWorldBorder().getSize())+" × "+Math.round(world.getWorldBorder().getSize()));}
    private String dimension(World world){return switch(world.getEnvironment()){case NORMAL->"overworld";case NETHER->"nether";case THE_END->"end";default->world.getEnvironment().name().toLowerCase(Locale.ROOT);};}
}
