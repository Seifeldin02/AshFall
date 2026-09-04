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
    /*  ---------------------------------------------------------------------------------------------------
     *  THE ASHFALL MESSAGE VOCABULARY
     *
     *  Every player-facing line on this server passes through msg() or error() -- about 1,350 call sites --
     *  so the look of the whole server is decided here rather than in any of them. See UI_STYLE_GUIDE.md
     *  for the rules; the short version:
     *
     *    A marker, not a brand.   Every message used to open with the word "Ashfall". Repeated 1,350 times
     *                             that stops being identity and becomes margin noise -- the relic list
     *                             printed it five times down the left-hand side. One coloured mark says the
     *                             same thing in one character, and its colour is what tells you whether to
     *                             care.
     *
     *    Colour means one thing.  Grey is body. White is a value worth reading. Ember is Ashfall and money.
     *                             Green happened, yellow needs attention, red did not happen. Nothing else.
     *
     *    Errors are not red walls. error() used to paint the whole line red, brand included. Red is for the
     *                             mark; the sentence stays readable, and the way out of the problem goes
     *                             quietly underneath it.
     *
     *  The marker glyph is the one the old prefix already used, so it is known to render on both clients.
     *  No new font, no resource pack, nothing exotic. */
    static final String MARK = "›";
    static final String DOT = "\u00b7";
    /** Ember: the one brand colour. Headings, money, the sidebar title. */
    static final net.kyori.adventure.text.format.TextColor EMBER = net.kyori.adventure.text.format.TextColor.color(0xE0A24B);
    static final String C_EMBER = "§6", C_TEXT = "§f", C_BODY = "§7", C_MUTE = "§8";
    static final String C_GOOD = "§a", C_WARN = "§e", C_BAD = "§c";

    static final Component PREFIX = Component.text("Ashfall ", NamedTextColor.GOLD).append(Component.text("› ",NamedTextColor.DARK_GRAY));
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");
    private CoreUtil() {}
    static String id(String name){return name.toLowerCase(Locale.ROOT);}
    static String id(Player p){return id(p.getName());}
    /** The sign goes in front of the symbol. "$-8,000,000" is not how anybody writes a loss, and the
     *  Colosseum totals line printed exactly that. */
    static String money(double amount){return (amount<0?"-$":"$")+MONEY.format(Math.abs(amount));}
    static String compactMoney(double amount){return (amount<0?"-$":"$")+compact(Math.abs(amount));}
    /** The one compact-number formatter. Everything that abbreviates a number goes through this, so
     *  billions read as b and millions as m everywhere at once rather than per screen -- the leaderboards
     *  used to say "bil" and the scoreboard "B" for the same value. */
    static String compact(double value){
        double absolute=Math.abs(value);String[] suffixes={"","k","m","b","t","q"};int suffix=0;
        while(absolute>=1000&&suffix<suffixes.length-1){absolute/=1000.0;value/=1000.0;suffix++;}
        String pattern=absolute>=100?"0":absolute>=10?"0.#":"0.##";
        return new DecimalFormat(pattern).format(value)+suffixes[suffix];
    }
    /** Neutral information -- the overwhelming majority of what the server says. */
    static void msg(CommandSender sender,String text){sender.sendMessage(C_MUTE+MARK+" "+C_BODY+text);}
    /** It worked, and something moved because of it. */
    static void ok(CommandSender sender,String text){sender.sendMessage(C_GOOD+MARK+" "+C_TEXT+text);}
    /** Worth noticing before it becomes a problem: a cost, a countdown, a limit being approached. */
    static void warn(CommandSender sender,String text){sender.sendMessage(C_WARN+MARK+" "+C_TEXT+text);}
    /** It did not happen, and this is why. */
    static void error(CommandSender sender,String text){sender.sendMessage(C_BAD+MARK+" "+C_TEXT+text);}
    /** The same, with the way out underneath it. */
    static void error(CommandSender sender,String problem,String next){
        sender.sendMessage(C_BAD+MARK+" "+C_TEXT+problem);
        if(next!=null&&!next.isBlank())sender.sendMessage("  "+C_MUTE+next);
    }
    /** Opens a block of related lines -- one heading, then items, instead of a prefix down the margin. */
    static void heading(CommandSender sender,String title){sender.sendMessage(C_EMBER+title);}
    static void heading(CommandSender sender,String title,String detail){
        sender.sendMessage(C_EMBER+title+(detail==null||detail.isBlank()?"":C_MUTE+"  "+detail));
    }
    /** A line inside a heading's block. */
    static void item(CommandSender sender,String text){sender.sendMessage("  "+C_MUTE+DOT+" "+C_BODY+text);}
    /** A labelled value: the label recedes, the value does not. */
    static void field(CommandSender sender,String label,String value){
        sender.sendMessage("  "+C_BODY+label+" "+C_TEXT+value);
    }
    /** A quiet line under something else: how to continue, or what a number means. */
    static void hint(CommandSender sender,String text){sender.sendMessage("  "+C_MUTE+text);}

    /*  Untrusted text on its way into a message.
     *
     *  Nicknames, faction names and tags, listing titles and anything else a player chose are data, not
     *  formatting. A name carrying a section sign would otherwise recolour or hide the rest of the line it
     *  appears in -- including the part that says what something costs. */
    static String safe(String text){
        if(text==null)return "";
        return text.replace('§','?');
    }
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

    /*  ---------------------------------------------------------------------------------------------------
     *  THE ASHFALL MENU VOCABULARY
     *
     *  Chest icons had exactly one style -- a gold name over grey lore -- from named() above and from eight
     *  verbatim private copies of it scattered through the services. Everything looked equally important and
     *  equally clickable, so a screen could not say "this does something", "this is only information",
     *  "this is switched on" or "you cannot do this yet". It could only say all four the same way.
     *
     *  Six roles, and the colour carries the whole signal:
     *
     *      heading   ember       what this screen is, or the thing being confirmed. Never clickable.
     *      action    white       something you can do right now.
     *      info      grey        information; clicking does nothing and it does not pretend otherwise.
     *      state     green/red   a switch, showing the state it is in now.
     *      blocked   dark grey   present so the option stays discoverable, with a red line saying in words
     *                            why it will not work -- instead of failing silently when clicked.
     *      danger    red         spends money, deletes something, or cannot be undone.
     *
     *  CONFIRM AND CANCEL HAVE FIXED SIDES, and that is why this block exists at all. The universal
     *  confirmation dialog put Cancel at 11 and Confirm at 15. The Bank's repayment dialog, the Ender Chest
     *  upgrade and the Orders escrow dialog put Confirm at 11 and Cancel at 15 -- mirrored. A player who
     *  learned where Cancel lives from the dialog they see most would press Confirm on a loan repayment
     *  while meaning to back out. Nobody had to move an icon for that; the layouts drifted apart one screen
     *  at a time. Both sides are named constants now, and every dialog reads the names. */
    static final class Menu {
        private Menu(){}
        /** The 27-slot confirmation dialog. Cancel is always left, confirm is always right, everywhere. */
        static final int SUBJECT=13, CANCEL=11, CONFIRM=15;
        /** The bottom row of a 27-slot screen. */
        static final int BACK_SMALL=22;
        /** The bottom row of a 54-slot browse screen. */
        static final int PREV=45, SEARCH=47, BACK=49, SORT=51, NEXT=53;
        /** The 54-slot sell basket: fill the top, read the total, confirm on the right. The ordinary shop
         *  and the spawner shop had landed on the same three numbers independently; they share them now. */
        static final int SELL_CANCEL=47, SELL_TOTAL=49, SELL_CONFIRM=51;

        private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY =
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

        /** Legacy colour codes render, and italics are forced off, so a line reads the same on Java and
         *  through Geyser on Bedrock. A lore line that brings no colour of its own is body text. */
        static ItemStack of(Material material,String name,List<String> lore){
            ItemStack item=new ItemStack(material);
            ItemMeta meta=item.getItemMeta();
            meta.displayName(line(name));
            if(lore!=null)meta.lore(lore.stream().map(text->line(text.indexOf('§')<0?C_BODY+text:text)).toList());
            item.setItemMeta(meta);
            return item;
        }
        private static Component line(String text){
            return LEGACY.deserialize(text).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false);
        }
        static ItemStack heading(Material m,String name,List<String> lore){return of(m,C_EMBER+name,lore);}
        static ItemStack action(Material m,String name,List<String> lore){return of(m,C_TEXT+name,lore);}
        static ItemStack info(Material m,String name,List<String> lore){return of(m,C_BODY+name,lore);}
        static ItemStack danger(Material m,String name,List<String> lore){return of(m,C_BAD+name,lore);}

        /** A switch that shows the state it is in, not the state it would move to -- the old bare "ON"/"OFF"
         *  lore line was ambiguous about which of the two it meant. */
        static ItemStack state(Material m,String name,boolean on,String detail){
            List<String> lore=new ArrayList<>();
            lore.add(on?C_GOOD+"On":C_BAD+"Off");
            if(detail!=null&&!detail.isBlank())lore.add(detail);
            lore.add(C_MUTE+"Click to turn it "+(on?"off":"on")+".");
            return of(m,(on?C_TEXT:C_BODY)+name,lore);
        }
        /** Kept on screen, greyed, with the reason underneath. An option that silently does nothing when
         *  clicked teaches a player that the menu is broken. */
        static ItemStack blocked(Material m,String name,String reason,List<String> lore){
            List<String> full=new ArrayList<>();
            if(lore!=null)full.addAll(lore);
            full.add(C_BAD+"Unavailable "+C_BODY+reason);
            return of(m,C_MUTE+name,full);
        }
        static ItemStack confirm(String what,List<String> detail){
            List<String> lore=new ArrayList<>();
            if(detail!=null)lore.addAll(detail);
            return of(Material.LIME_CONCRETE,C_GOOD+"Confirm"+(what==null||what.isBlank()?"":C_BODY+"  "+what),lore);
        }
        static ItemStack cancel(String detail){
            return of(Material.RED_CONCRETE,C_BAD+"Cancel",
                    List.of(detail==null||detail.isBlank()?"Nothing is charged.":detail));
        }
        static ItemStack back(String where){
            return of(Material.ARROW,C_BODY+"Back",where==null||where.isBlank()?List.of():List.of(where));
        }
        /** Page arrows that say whether there IS another page, rather than two identical arrows either side
         *  of a screen that will not move when you click them. */
        static ItemStack page(int page,int pages,boolean forward){
            boolean can=forward?page+1<pages:page>0;
            return of(can?Material.ARROW:Material.GRAY_DYE,
                    (can?C_TEXT:C_MUTE)+(forward?"Next":"Previous"),
                    List.of(C_BODY+"Page "+C_TEXT+(page+1)+C_BODY+" of "+C_TEXT+pages,
                            can?C_MUTE+"Click to go "+(forward?"forward.":"back.")
                               :C_MUTE+(forward?"This is the last page.":"This is the first page.")));
        }
        /** Nothing here yet -- and what to do about it. An empty screen with no explanation is the most
         *  common way a menu fails, and every list on this server needs one of these. */
        static ItemStack nothing(String what,List<String> how){
            return of(Material.LIGHT_GRAY_STAINED_GLASS_PANE,C_BODY+what,how);
        }
    }

    /*  HANDS AN ITEM OVER WITHOUT CONSUMING THE CALLER'S COPY.
     *
     *  Inventory#addItem writes the remainder back into the stack it is given: a stack that fits entirely
     *  comes back with amount 0, and a stack that half fits comes back holding only the half that did not.
     *  Callers do not expect that -- the spawner payout counted `returned += item.getAmount()` immediately
     *  after handing the item over and therefore counted zero, and the merchant read the scroll it had
     *  just sold to decide what to announce.
     *
     *  Giving addItem a clone costs one object and makes every one of the ninety-odd call sites safe by
     *  construction, including the ones nobody has written yet. */
    static boolean give(Player p,ItemStack item){
        Map<Integer,ItemStack> left=p.getInventory().addItem(item.clone());
        left.values().forEach(i->p.getWorld().dropItemNaturally(p.getLocation(),i));
        return left.isEmpty();
    }
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
    /** The current reward day, which rolls over at 12:00 Asia/Riyadh rather than at midnight UTC.
     *
     *  Before noon there the day label is still yesterday's date, so an evening session that runs past
     *  midnight counts as ONE day rather than silently handing a farmer a fresh daily allowance halfway
     *  through it. */
    static String riyadhDay(){
        java.time.ZonedDateTime now=java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Riyadh"));
        if(now.getHour()<12)now=now.minusDays(1);
        return now.toLocalDate().toString();
    }
    static Location findSafeAny(World world,int x,int z){return world.getEnvironment()==World.Environment.NETHER?findSafeNether(world,x,z):findSafe(world,x,z);}
}
