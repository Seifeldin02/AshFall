package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CoreUtil {
    static final Component PREFIX = Component.text("Ashfall ", NamedTextColor.GOLD).append(Component.text("› ",NamedTextColor.DARK_GRAY));
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");
    private CoreUtil() {}
    static String id(String name){return name.toLowerCase(Locale.ROOT);}
    static String id(Player p){return id(p.getName());}
    static String money(double amount){return "$"+MONEY.format(amount);}
    static String compactMoney(double amount){return "$"+compact(amount);}
    /** The one compact-number formatter. Everything that abbreviates a number goes through this, so
     *  billions read as b and millions as m everywhere at once rather than per screen -- the leaderboards
     *  used to say "bil" and the scoreboard "B" for the same value. */
    static String compact(double value){
        double absolute=Math.abs(value);String[] suffixes={"","k","m","b","t","q"};int suffix=0;
        while(absolute>=1000&&suffix<suffixes.length-1){absolute/=1000.0;value/=1000.0;suffix++;}
        String pattern=absolute>=100?"0":absolute>=10?"0.#":"0.##";
        return new DecimalFormat(pattern).format(value)+suffixes[suffix];
    }
    static void msg(CommandSender sender,String text){sender.sendMessage(ChatColor.GOLD+"Ashfall "+ChatColor.DARK_GRAY+"› "+ChatColor.RESET+text);}
    static void error(CommandSender sender,String text){sender.sendMessage(ChatColor.RED+"Ashfall › "+text);}
    static boolean finitePositive(double d){return Double.isFinite(d)&&d>0;}
    private static final Pattern MONEY_INPUT=Pattern.compile("^\\$?([0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(k|m|mil|b)?$",Pattern.CASE_INSENSITIVE);
    private static final BigDecimal MAX_MONEY_INPUT=new BigDecimal("1000000000000000");
    static double parseMoney(String input){
        if(input==null)return-1;Matcher matcher=MONEY_INPUT.matcher(input.trim().replace(",",""));if(!matcher.matches())return-1;
        try{
            BigDecimal amount=new BigDecimal(matcher.group(1));String suffix=matcher.group(2);
            if(suffix!=null)amount=amount.multiply(switch(suffix.toLowerCase(Locale.ROOT)){case"k"->BigDecimal.valueOf(1_000);case"m","mil"->BigDecimal.valueOf(1_000_000);case"b"->BigDecimal.valueOf(1_000_000_000);default->BigDecimal.ONE;});
            amount=amount.setScale(2,RoundingMode.HALF_UP);if(amount.signum()<=0||amount.compareTo(MAX_MONEY_INPUT)>0)return-1;double value=amount.doubleValue();return finitePositive(value)?value:-1;
        }catch(ArithmeticException|NumberFormatException ignored){return-1;}
    }
    static boolean moneyParserSelfTest(){return parseMoney("1k")==1000&&parseMoney("2.5k")==2500&&parseMoney("1m")==1000000&&parseMoney("1mil")==1000000&&parseMoney("2.5m")==2500000&&parseMoney("1b")==1000000000&&parseMoney("-1")<0&&parseMoney("1e9")<0&&parseMoney("9999999999999999")<0;}
    /** Uses raw codepoint values (not literal combining-mark characters in source) so the test string can't
     *  be silently mangled by editor/encoding normalization — a real risk for this specific category of
     *  character. */
    static boolean combiningMarkSelfTest(){
        StringBuilder zalgo=new StringBuilder().appendCodePoint('e');
        for(int i=0;i<10;i++)zalgo.appendCodePoint(0x0301);
        String cleaned=stripExcessiveCombiningMarks(zalgo.toString(),2);
        int marks=0;for(int i=0;i<cleaned.length();i++)if(cleaned.charAt(i)==0x0301)marks++;
        boolean plainUnaffected="hello world".equals(stripExcessiveCombiningMarks("hello world",2));
        String cafe=new StringBuilder("cafe").appendCodePoint(0x0301).toString();
        boolean accentPreserved=cafe.equals(stripExcessiveCombiningMarks(cafe,2));
        return cleaned.charAt(0)=='e'&&marks==2&&cleaned.length()==3&&plainUnaffected&&accentPreserved;
    }
    static String pretty(String key){String[] parts=key.toLowerCase(Locale.ROOT).split("_");StringBuilder b=new StringBuilder();for(String p:parts){if(!p.isEmpty())b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');}return b.toString().trim();}
    static String timeAgo(long epochMillis){if(epochMillis<=0)return"never";long seconds=Math.max(0,(System.currentTimeMillis()-epochMillis)/1000);if(seconds<60)return seconds+"s ago";long minutes=seconds/60;if(minutes<60)return minutes+"m ago";long hours=minutes/60;if(hours<24)return hours+"h ago";long days=hours/24;return days+"d ago";}
    /** Caps consecutive Unicode combining marks ("zalgo text") per base character. Minecraft's chat protocol
     *  happily transmits arbitrary combining-mark stacks (they're valid Unicode, nothing to reject), but
     *  client-side font shaping has to lay out every mark stacked on the same glyph, and that cost scales
     *  badly enough per-character that a short, otherwise-unremarkable message can make every recipient's
     *  client stall rendering it — a real, longstanding chat-griefing technique, not a server-side bug. The
     *  server can't fix client font shaping, but it can refuse to relay glyph stacks deep enough to trigger
     *  it, which is the only lever actually available here. */
    static String stripExcessiveCombiningMarks(String input,int maxPerChar){
        StringBuilder result=new StringBuilder(input.length());
        int combiningRun=0;
        for(int i=0;i<input.length();){
            int cp=input.codePointAt(i);
            int type=Character.getType(cp);
            boolean combining=type==Character.NON_SPACING_MARK||type==Character.ENCLOSING_MARK||type==Character.COMBINING_SPACING_MARK;
            if(combining){combiningRun++;if(combiningRun<=maxPerChar)result.appendCodePoint(cp);}
            else{combiningRun=0;result.appendCodePoint(cp);}
            i+=Character.charCount(cp);
        }
        return result.toString();
    }
    static ItemStack named(Material material,String name,List<String> lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(name,NamedTextColor.GOLD));if(lore!=null)meta.lore(lore.stream().map(s->Component.text(s,NamedTextColor.GRAY)).toList());item.setItemMeta(meta);return item;}
    static boolean give(Player p,ItemStack item){Map<Integer,ItemStack> left=p.getInventory().addItem(item);left.values().forEach(i->p.getWorld().dropItemNaturally(p.getLocation(),i));return left.isEmpty();}
    static String ipHash(Player p){try{String ip=p.getAddress()==null?"unknown":p.getAddress().getAddress().getHostAddress();byte[] h=MessageDigest.getInstance("SHA-256").digest(ip.getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(h,0,12);}catch(Exception e){return "unknown";}}
    static boolean unsafeSurface(Block b){Material m=b.getType();return !m.isSolid()||m==Material.MAGMA_BLOCK||m==Material.CACTUS||m==Material.FIRE||m==Material.SOUL_FIRE||m.name().contains("LEAVES");}
    static Location findSafe(World world,int x,int z){Block top=world.getHighestBlockAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES);if(unsafeSurface(top)||top.isLiquid())return null;Biome biome=top.getBiome();String bn=biome.getKey().getKey();if(bn.contains("ocean")||bn.contains("river"))return null;Location l=top.getLocation().add(0.5,1,0.5);if(!l.getBlock().isPassable()||!l.clone().add(0,1,0).getBlock().isPassable())return null;return l;}
    /** The Overworld "highest block" heightmap scan used by {@link #findSafe} is meaningless in the Nether —
     *  it almost always hits the cave-roof/ceiling structure instead of real ground, so it either fails outright
     *  or lands in ceiling-layer terrain that's frequently adjacent to lava. Mirrors the downward floor-scan
     *  TeleportService already uses for RTP into the Nether, which has been safe in production. */
    static Location findSafeNether(World world,int x,int z){
        int max=Math.min(world.getMaxHeight()-10,120),min=world.getMinHeight()+6;
        for(int y=max;y>=min;y--){
            Block floor=world.getBlockAt(x,y-1,z),feet=world.getBlockAt(x,y,z),head=world.getBlockAt(x,y+1,z);
            if(!safeNetherFloor(floor)||!feet.isPassable()||!head.isPassable()||feet.isLiquid()||head.isLiquid())continue;
            /** A single safe column isn't enough for something that wanders on its own — Nether terrain is full
             *  of thin ledges/bridges over lava seas and open caverns, which a teleported player can react to
             *  but a boss's pathfinding will happily walk straight off. Require solid footing all around before
             *  accepting the spot. */
            boolean surrounded=true;
            for(int dx=-2;dx<=2&&surrounded;dx++)for(int dz=-2;dz<=2;dz++)if(!world.getBlockAt(x+dx,y-1,z+dz).getType().isSolid()){surrounded=false;break;}
            if(!surrounded)continue;
            return new Location(world,x+.5,y,z+.5);
        }
        return null;
    }
    private static boolean safeNetherFloor(Block block){Material material=block.getType();return material.isSolid()&&!Set.of(Material.LAVA,Material.MAGMA_BLOCK,Material.CACTUS,Material.FIRE,Material.SOUL_FIRE,Material.POWDER_SNOW,Material.BEDROCK).contains(material);}
    static Location findSafeAny(World world,int x,int z){return world.getEnvironment()==World.Environment.NETHER?findSafeNether(world,x,z):findSafe(world,x,z);}
}
