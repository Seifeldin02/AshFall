package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.scheduler.BukkitTask;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Periodic Discord reminder. Timing persists across restarts (via the state table) so a restart never
 *  causes an extra reminder, and it defers while a world event/boss is active to avoid overlapping chat. */
final class DiscordReminderService {
    private static final String STATE_KEY = "discord_reminder_last";
    private final SMPCore plugin;
    private final Database db;
    private BukkitTask task;

    DiscordReminderService(SMPCore plugin){
        this.plugin = plugin; this.db = plugin.db();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L*60, 20L*60);
    }
    void shutdown(){ if(task!=null) task.cancel(); }

    private void tick(){
        if(!plugin.getConfig().getBoolean("discord-reminder.enabled", true)) return;
        if(plugin.getServer().getOnlinePlayers().isEmpty()) return;
        if(plugin.bosses().active()) return;
        long intervalMinutes = Math.max(5, plugin.getConfig().getLong("discord-reminder.interval-minutes", 60));
        long last = parseLong(db.state(STATE_KEY));
        long now = System.currentTimeMillis();
        if(now - last < intervalMinutes * 60000L) return;
        db.state(STATE_KEY, Long.toString(now));
        String message = plugin.getConfig().getString("discord-reminder.message",
                "Join the Ashfall Discord for announcements, server updates, and feedback: https://discord.gg/G2FfuXjz8");
        Component rendered = linkify(message);
        plugin.getServer().broadcast(rendered);
        plugin.getLogger().info("Discord reminder sent to "+plugin.getServer().getOnlinePlayers().size()+" online player(s).");
    }
    private long parseLong(String value){try{return value==null?0:Long.parseLong(value);}catch(NumberFormatException e){return 0;}}

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static Component linkify(String message){
        Matcher matcher = URL_PATTERN.matcher(message);
        Component result = Component.empty();
        int last = 0;
        while(matcher.find()){
            if(matcher.start() > last) result = result.append(Component.text(message.substring(last, matcher.start()), NamedTextColor.AQUA));
            String url = matcher.group();
            result = result.append(Component.text(url, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(Component.text("Open " + url))));
            last = matcher.end();
        }
        if(last < message.length()) result = result.append(Component.text(message.substring(last), NamedTextColor.AQUA));
        return result;
    }
}
