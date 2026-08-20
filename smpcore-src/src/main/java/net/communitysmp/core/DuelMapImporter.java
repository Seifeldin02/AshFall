package net.communitysmp.core;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;

import java.util.ArrayList;
import java.util.List;

/** Imports a real, built area of a live world into a duel map's template.
 *
 *  Deliberately NOT a region-file copy. The four imported arenas were built inside a shared survival world
 *  and their selections overlap the same {@code .mca} files as unrelated terrain, so copying region files
 *  would drag in whatever else happened to share the region -- other builds, other players' bases, the lot.
 *  This copies exactly the declared cuboid, block by block, through WorldEdit, which is also what carries
 *  block-entity NBT across: chests keep their contents, signs their text, spawners and vaults their data.
 *
 *  It is sliced by chunk column and spread over ticks. Skyroot Village alone is 204 x 385 x 183 -- over
 *  fourteen million blocks -- and writing that in one tick is a multi-second server freeze. A handful of
 *  chunk columns per tick keeps each tick's share small enough to be unnoticeable while the whole import
 *  still finishes in well under a minute.
 *
 *  This class is only ever loaded when an admin runs the import, so a server without WorldEdit installed
 *  (production, currently) never resolves these classes at all. */
final class DuelMapImporter {

    private final SMPCore plugin;
    private final DuelMapService maps;

    DuelMapImporter(SMPCore plugin, DuelMapService maps) { this.plugin = plugin; this.maps = maps; }

    static boolean available() { return Bukkit.getPluginManager().getPlugin("WorldEdit") != null; }

    /** Copies the map's declared bounds out of its source world into its template, then commits a snapshot.
     *  Reports progress to the caller as it goes. */
    void run(CommandSender sender, DuelMapService.DuelMap map) {
        int[] b = map.bounds();
        if (b == null) { CoreUtil.error(sender, map.key() + " has no bounds configured; nothing to import."); return; }
        String sourceName = map.sourceWorld() != null ? map.sourceWorld() : Bukkit.getWorlds().get(0).getName();
        World source = Bukkit.getWorld(sourceName);
        if (source == null) { CoreUtil.error(sender, "Source world '" + sourceName + "' is not loaded."); return; }
        World target = maps.template(map);
        if (target == null) { CoreUtil.error(sender, "Could not open the template world for " + map.key() + "."); return; }

        int minY = Math.max(b[1], Math.max(source.getMinHeight(), target.getMinHeight()));
        int maxY = Math.min(b[4], Math.min(source.getMaxHeight(), target.getMaxHeight()) - 1);
        int minCX = b[0] >> 4, maxCX = b[3] >> 4, minCZ = b[2] >> 4, maxCZ = b[5] >> 4;

        List<int[]> columns = new ArrayList<>();
        for (int cx = minCX; cx <= maxCX; cx++) for (int cz = minCZ; cz <= maxCZ; cz++) columns.add(new int[]{cx, cz});
        int perTick = Math.max(1, plugin.getConfig().getInt("duel-maps-import.columns-per-tick", 2));

        CoreUtil.msg(sender, "Importing " + map.name() + ": " + columns.size() + " chunk column(s), y " + minY + ".." + maxY
                + ", from " + sourceName + " into " + target.getName() + ".");
        plugin.getLogger().info("[duel-maps] import " + map.key() + " started: " + columns.size() + " columns from " + sourceName);

        java.util.Iterator<int[]> it = columns.iterator();
        long started = System.currentTimeMillis();
        int total = columns.size();
        int[] doneCount = {0};
        long[] blocks = {0};
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                com.sk89q.worldedit.world.World weSource = BukkitAdapter.adapt(source);
                com.sk89q.worldedit.world.World weTarget = BukkitAdapter.adapt(target);
                try (EditSession from = WorldEdit.getInstance().newEditSession(weSource);
                     EditSession into = WorldEdit.getInstance().newEditSession(weTarget)) {
                    for (int n = 0; n < perTick && it.hasNext(); n++) {
                        int[] c = it.next();
                        int x0 = Math.max(b[0], c[0] << 4), x1 = Math.min(b[3], (c[0] << 4) + 15);
                        int z0 = Math.max(b[2], c[1] << 4), z1 = Math.min(b[5], (c[1] << 4) + 15);
                        /** Both sides must be resident before the copy: the source chunk to read, the target
                         *  chunk so the write lands in a loaded chunk that will actually be saved. */
                        source.getChunkAt(c[0], c[1]).load(true);
                        target.getChunkAt(c[0], c[1]).load(true);
                        for (int x = x0; x <= x1; x++)
                            for (int z = z0; z <= z1; z++)
                                for (int y = minY; y <= maxY; y++) {
                                    BlockVector3 pos = BlockVector3.at(x, y, z);
                                    into.setBlock(pos, from.getFullBlock(pos));
                                    blocks[0]++;
                                }
                        doneCount[0]++;
                    }
                } catch (Throwable error) {
                    cancel();
                    CoreUtil.error(sender, "Import failed: " + error);
                    plugin.getLogger().warning("[duel-maps] import " + map.key() + " failed: " + error);
                    return;
                }
                if (doneCount[0] % 25 == 0 && it.hasNext())
                    CoreUtil.msg(sender, "  " + doneCount[0] + " / " + total + " columns (" + (blocks[0] / 1000) + "k blocks)");
                if (it.hasNext()) return;
                cancel();
                int entities = copyEntities(source, target, b);
                long seconds = (System.currentTimeMillis() - started) / 1000;
                CoreUtil.msg(sender, "Blocks copied: " + blocks[0] + " in " + seconds + "s; " + entities + " build entities carried over.");
                maps.purgeMobs(target);
                CoreUtil.msg(sender, maps.commitTemplate(map));
                plugin.getLogger().info("[duel-maps] import " + map.key() + " finished: " + blocks[0] + " blocks, " + entities + " entities");
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Carries across the entities a built map genuinely depends on -- item frames, paintings and armour
     *  stands are part of the build, not decoration that can be regenerated. Living mobs and dropped items
     *  are deliberately NOT copied: a duel arena spawns nothing and holds nothing loose. */
    private int copyEntities(World source, World target, int[] b) {
        int copied = 0;
        Location centre = new Location(source, (b[0] + b[3]) / 2.0, (b[1] + b[4]) / 2.0, (b[2] + b[5]) / 2.0);
        double rx = (b[3] - b[0]) / 2.0 + 2, ry = (b[4] - b[1]) / 2.0 + 2, rz = (b[5] - b[2]) / 2.0 + 2;
        for (Entity entity : source.getNearbyEntities(centre, rx, ry, rz)) {
            Location at = entity.getLocation();
            if (!inside(b, at)) continue;
            Location to = new Location(target, at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
            try {
                if (entity instanceof ItemFrame frame) {
                    target.spawn(to, frame.getClass(), copy -> {
                        copy.setFacingDirection(frame.getFacing(), true);
                        copy.setItem(frame.getItem());
                        copy.setRotation(frame.getRotation());
                        copy.setVisible(frame.isVisible());
                        copy.setFixed(frame.isFixed());
                        copy.setGlowing(frame.isGlowing());
                    });
                    copied++;
                } else if (entity instanceof Painting painting) {
                    target.spawn(to, Painting.class, copy -> {
                        copy.setFacingDirection(painting.getFacing(), true);
                        copy.setArt(painting.getArt(), true);
                    });
                    copied++;
                } else if (entity instanceof ArmorStand stand) {
                    target.spawn(to, ArmorStand.class, copy -> {
                        copy.setVisible(stand.isVisible());
                        copy.setSmall(stand.isSmall());
                        copy.setArms(stand.hasArms());
                        copy.setBasePlate(stand.hasBasePlate());
                        copy.setMarker(stand.isMarker());
                        copy.setGravity(false);
                        copy.setInvulnerable(true);
                        if (stand.getEquipment() != null && copy.getEquipment() != null) {
                            copy.getEquipment().setArmorContents(stand.getEquipment().getArmorContents());
                            copy.getEquipment().setItemInMainHand(stand.getEquipment().getItemInMainHand());
                            copy.getEquipment().setItemInOffHand(stand.getEquipment().getItemInOffHand());
                        }
                        copy.setHeadPose(stand.getHeadPose());
                        copy.setBodyPose(stand.getBodyPose());
                        copy.setLeftArmPose(stand.getLeftArmPose());
                        copy.setRightArmPose(stand.getRightArmPose());
                        copy.setLeftLegPose(stand.getLeftLegPose());
                        copy.setRightLegPose(stand.getRightLegPose());
                        if (stand.customName() != null) copy.customName(stand.customName());
                        copy.setCustomNameVisible(stand.isCustomNameVisible());
                    });
                    copied++;
                }
            } catch (Throwable error) {
                plugin.getLogger().warning("[duel-maps] could not carry over a " + entity.getType() + ": " + error);
            }
        }
        return copied;
    }

    private boolean inside(int[] b, Location at) {
        return at.getBlockX() >= b[0] && at.getBlockX() <= b[3]
            && at.getBlockY() >= b[1] && at.getBlockY() <= b[4]
            && at.getBlockZ() >= b[2] && at.getBlockZ() <= b[5];
    }
}
