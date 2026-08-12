package net.communitysmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.List;
import java.util.Locale;

final class GuideService implements Listener {
    private record Holder() implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private final SMPCore plugin;
    private final Database db;

    GuideService(SMPCore plugin){this.plugin=plugin;this.db=plugin.db();}

    /** Single canonical source for the rules, in both languages — /rules and the guidebook's rules pages both
     *  render from exactly this, so the two can never say something different. Cheat names are examples only
     *  ("...and similar unfair tools"), never presented as an exhaustive list. */
    private static List<String> prohibited(boolean arabic){return arabic?List.of(
            "أي شكل من أشكال الغش: X-ray، الطيران، تسريع الحركة/المدى، NoFall، Bunny Hop، Kill Aura، المساعدة على التصويب، النقر الآلي، freecam، وأدوات غير عادلة مشابهة",
            "الألفاظ البذيئة أو السباب",
            "العنصرية أو التمييز أو خطاب الكراهية بأي شكل",
            "الخيانة الداخلية (Insiding) — حظر فوري",
            "الغزو عبر الأخطاء البرمجية أو خدعة إندر بيرل (Ender Pearl glitching)",
            "أدوات/إضافات البناء الآلي (Printer)",
            "الحسابات البديلة للتهرب من الحظر أو مضاعفة الإحصائيات/الموارد",
            "جدران التجدد (Regen walls)",
            "رادار اللاعبين أو رادار الكهوف في الخريطة المصغرة"
    ):List.of(
            "Any form of cheating: X-ray, flight, speed/reach hacks, NoFall, Bunny Hop, Kill Aura, Aim Assist, auto-clickers, freecam, and similar unfair tools",
            "Profanity or swearing",
            "Racism, discrimination, or hate speech of any kind",
            "Insiding — instant ban",
            "Glitch raiding or Ender Pearl glitching",
            "Printer / automatic-building mods",
            "Alt accounts used for ban evasion or stat/resource boosting",
            "Regen walls",
            "Minimap player radar or cave radar"
    );}
    private static List<String> allowed(boolean arabic){return arabic?List.of(
            "الخداع والرشاوى",
            "الغزو الطبيعي، الفخاخ، والتخريب",
            "الخيانة خارج نطاق Insiding — نقض التحالفات أو الهدنات مسموح",
            "استعراض المخططات (مثل Litematica) طالما ميزات البناء الآلي معطّلة",
            "إضافات تحسين الأداء",
            "استخدام الخريطة المصغرة مع تعطيل رادار اللاعبين ورادار الكهوف"
    ):List.of(
            "Deception and bribes",
            "Normal raiding, traps, and griefing",
            "Betrayal outside of insiding — breaking alliances or truces is allowed",
            "Schematic/blueprint viewing (e.g. Litematica), provided automatic printer/building features are disabled",
            "Performance mods",
            "Minimap use with player radar and cave radar disabled"
    );}
    private static String distinction(boolean arabic){return arabic?
            "الفرق: الخيانة المسموحة = نقض الاتفاقات أو التحالفات أو خداع الأعداء. الخيانة الداخلية الممنوعة (Insiding) = استغلال عضويتك أو ثقة فصيلك لسرقته أو تدميره من الداخل."
            :"The distinction: allowed betrayal = breaking deals, alliances, or deceiving enemies. Banned insiding = abusing faction membership/trust to steal from or destroy your OWN faction from within.";}

    boolean rulesCommand(Player player,String[] args){
        boolean arabic=args.length>0&&(args[0].equalsIgnoreCase("arabic")||args[0].equalsIgnoreCase("ar")||args[0].equals("العربية"));
        player.sendMessage(Component.text(arabic?"— قوانين أشفال —":"— ASHFALL RULES —",NamedTextColor.GOLD));
        player.sendMessage(Component.text(arabic?"ممنوع:":"PROHIBITED:",NamedTextColor.RED));
        for(String line:prohibited(arabic))player.sendMessage(Component.text("• "+line,NamedTextColor.GRAY));
        player.sendMessage(Component.text(arabic?"مسموح:":"ALLOWED:",NamedTextColor.GREEN));
        for(String line:allowed(arabic))player.sendMessage(Component.text("• "+line,NamedTextColor.GRAY));
        player.sendMessage(Component.text(distinction(arabic),NamedTextColor.DARK_GRAY));
        if(!arabic)player.sendMessage(Component.text("Use /rules arabic to view the rules in Arabic.",NamedTextColor.DARK_GRAY));
        return true;
    }

    boolean command(Player player,String[] args){
        if(args.length==0){open(player);return true;}
        String language=args[0].toLowerCase(Locale.ROOT);
        if(language.equals("english")||language.equals("en"))give(player,"en");
        else if(language.equals("arabic")||language.equals("ar")||language.equals("العربية"))give(player,"ar");
        else CoreUtil.error(player,"Use /guide and choose English or العربية.");
        return true;
    }

    void open(Player player){
        Holder holder=new Holder();Inventory inventory=plugin.getServer().createInventory(holder,27,Component.text("Ashfall Guide",NamedTextColor.GOLD));
        String selected=db.preference(CoreUtil.id(player),"guide_language");
        inventory.setItem(11,CoreUtil.named(Material.BOOK,"English",List.of("Ashfall Field Guide",selected==null||selected.equals("en")?"Selected":"Click to select")));
        inventory.setItem(15,CoreUtil.named(Material.WRITABLE_BOOK,"العربية",List.of("دليل أشــفال",selected!=null&&selected.equals("ar")?"اللغة المختارة":"اضغط للاختيار")));
        player.openInventory(inventory);
    }

    /** First spawn hands over BOTH language editions, because a brand-new player has never had the chance
     *  to express a language preference and givePreferred() would silently default them to English. Only the
     *  first-join path calls this, and that path is already gated by the players.guide flag, so a returning
     *  player cannot accumulate duplicate books. /guide and the language picker still give one book. */
    void giveBoth(Player player){give(player,"en");give(player,"ar");}
    void givePreferred(Player player){give(player,"ar".equals(db.preference(CoreUtil.id(player),"guide_language"))?"ar":"en");}

    @EventHandler public void click(InventoryClickEvent event){
        if(!(event.getInventory().getHolder(false) instanceof Holder)||!(event.getWhoClicked() instanceof Player player))return;
        event.setCancelled(true);if(event.getRawSlot()==11){player.closeInventory();give(player,"en");}else if(event.getRawSlot()==15){player.closeInventory();give(player,"ar");}
    }

    private void give(Player player,String language){
        db.preference(CoreUtil.id(player),"guide_language",language);ItemStack book=book(language);CoreUtil.give(player,book);
        CoreUtil.msg(player,language.equals("ar")?"تمت إضافة دليل أشــفال العربي إلى حقيبتك.":"The Ashfall Field Guide was added to your inventory.");
    }

    private ItemStack book(String language){
        ItemStack book=new ItemStack(Material.WRITTEN_BOOK);BookMeta meta=(BookMeta)book.getItemMeta();boolean arabic=language.equals("ar");
        meta.title(Component.text(arabic?"دليل أشــفال":"Ashfall Field Guide",NamedTextColor.GOLD));meta.author(Component.text("Ashfall"));
        List<Page> pages=arabic?arabicPages():englishPages();meta.pages(pages.stream().map(page->page(page.title(),page.body())).toList());book.setItemMeta(meta);return book;
    }

    private List<Page> englishPages(){return List.of(
            new Page("WELCOME TO ASHFALL","Survival comes first. Factions, exploration, events, relics and World Bosses create shared stories.\n\nStart with /smphelp."),
            new Page("YOUR FIRST DAYS","Gather food and tools. Check /balance.\n\nCreate a faction with /f create <name>. The leader stands at the chosen base center and uses /f claim."),
            new Page("LAND & RELATIONS","The first claim is 50 × 50. Use /f borders and /f expand.\n\n/f relations manages truces and alliances. Alliance storage works only after both leaders approve it."),
            new Page("MARKETPLACE","/shop, /ah, /luxuryshop and /shardshop open one connected Marketplace.\n\nUse Search / Filter, Sort and My Listings. The Shop Sell Basket works without typed item names."),
            new Page("COMMUNITY","The Orders marketplace is temporarily unavailable.\n\n/discord shows the Ashfall community invite."),
            new Page("SHARDS","Shards are account-bound rewards from active play and meaningful boss or elite participation.\n\nSpend them in /shardshop. Strong rewards have per-player limits; cosmetics rotate weekly."),
            new Page("ASHEN SETTINGS","Open Ashen Settings from Java Quick Actions (G), the pause menu, or /settings. Bedrock receives a touch-friendly form.\n\nChoices persist."),
            new Page("SPAWNS & VISION","Natural Hostile Spawns OFF clears ordinary nearby hostiles, including elites, only when no nearby player has it ON. Bosses and Trial Chamber spawner mobs remain.\n\nNight Vision is infinite and yields to boss darkness."),
            new Page("TRAVEL","/home and /f home\n/tpa <player>\n/tpahere <player>\n/spawn\n/rtp\n\nRTP searches your current dimension: Overworld, Nether or End. It avoids claims and protected areas. PvP combat blocks travel."),
            new Page("EVENTS & DRAGON","/events shows the active objective, direction and reward. Tracking can be toggled.\n\nThe weekly Ender Dragon is Friday at 4:00 PM server time. Meaningful participants share progression and rewards."),
            new Page("ELITES & BOSSES","Uncommon, Rare, Epic, Legendary and Miniboss enemies glow and show health bars.\n\nThree named World Bosses — the Ashen Knight, the Warded Colossus and the Cinder Warlord — periodically awaken somewhere in the world, with hints narrowing down their location over time.\n\nBoss and high-tier rewards use meaningful damage participation, not only the final hit."),
            new Page("DEATH & STORAGE","Deaths with items create separate 48-hour graves. Use /graves to select one or stop tracking.\n\n/enderchest is private. Faction storage blocks outsiders unless bilateral alliance storage is active."),
            new Page("WORLD RELICS","Relics are rare, powerful artifacts found out in the world. Whoever picks one up becomes its ACTIVE owner — only one player can hold a given relic at a time.\n\n/relics shows every relic's chronicle and status. /relics trace <key> helps locate one you own.\n\nIf a relic's owner goes missing (no trace in inventory, Ender Storage, faction claim, graves or the loaded world) it's marked LOST. After a cooldown it becomes ELIGIBLE and can resurface somewhere in Ashfall for the next player to find — nothing is ever deleted, it just re-enters circulation."),
            new Page("RAIDING & CAPSULES","Villager Capsules can capture — and steal — any normal villager, even from inside another faction's protected claim. Server merchants and spawn-protected NPCs can't be captured. Hover a capsule to see the villager's trades and base prices.\n\nObsidian inside a protected faction claim quietly resists raiding: it takes several qualifying explosion hits before it actually breaks, and un-hit obsidian slowly recovers over time. Outside claims, obsidian is ordinary vanilla obsidian."),
            new Page("RULES — PROHIBITED","PROHIBITED:\n"+String.join("\n",prohibited(false).stream().map(line->"• "+line).toList())+"\n\nFull rules any time: /rules"),
            new Page("RULES — ALLOWED","ALLOWED:\n"+String.join("\n",allowed(false).stream().map(line->"• "+line).toList())+"\n\n"+distinction(false)),
            new Page("HELP & FEEDBACK","/progress shows Adventure goals.\n/stats [player]\n/history and /f history\n/sidebar toggles the HUD.\n\nUse /feedback <message> to report bugs or suggest additions. /guide changes language.\n\nRead the full server rules any time with /rules.")
    );}

    private List<Page> arabicPages(){return List.of(
            new Page("مرحباً بك في أشفال","البقاء هو الأساس. الفصائل والاستكشاف والفعاليات والآثار وزعماء العالم تصنع قصصاً مشتركة.\n\nابدأ بالأمر /smphelp."),
            new Page("أيامك الأولى","اجمع الطعام والأدوات وتحقق من رصيدك عبر /balance.\n\nأنشئ فصيلاً عبر /f create <name>. يقف القائد في مركز القاعدة المختار ثم يستخدم /f claim."),
            new Page("الأرض والعلاقات","أول حماية 50 × 50. استخدم /f borders و /f expand.\n\nيدير /f relations الهدن والتحالفات. لا يفتح تخزين التحالف إلا بعد موافقة القائدين."),
            new Page("السوق الموحد","تفتح أوامر /shop و /ah و /luxuryshop و /shardshop سوقاً واحداً مترابطاً.\n\nاستخدم البحث والفرز وقائمة عروضك. تتيح سلة البيع وضع الأغراض مباشرة."),
            new Page("المجتمع","سوق الطلبات غير متاح حالياً بشكل مؤقت.\n\nيعرض /discord رابط مجتمع أشفال."),
            new Page("الشظايا","الشظايا مكافآت مرتبطة بحسابك من اللعب الفعّال والمشاركة الحقيقية في قتال النخب والزعماء.\n\nأنفقها في /shardshop. للمكافآت القوية حدود، وتتبدل الزينة أسبوعياً."),
            new Page("إعدادات أشن","افتح إعدادات أشن من الإجراءات السريعة (G) أو قائمة الإيقاف في Java، أو استخدم /settings. تظهر نافذة مناسبة للمس في Bedrock.\n\nتُحفظ اختياراتك."),
            new Page("الوحوش والرؤية","عند إيقاف الوحوش الطبيعية تختفي الوحوش العدائية العادية القريبة، بما فيها النخبة، ما لم يكن لاعب قريب قد فعّلها. الزعماء ووحوش غرف المحاكمة (Trial Chamber) لا تختفي.\n\nالرؤية الليلية دائمة ولا تلغي ظلام الزعماء."),
            new Page("التنقل","/home و /f home\n/tpa <player>\n/tpahere <player>\n/spawn\n/rtp\n\nيبحث RTP في بُعدك الحالي: العالم العادي أو النذر أو الإند، ويتجنب المناطق المحمية. يمنع قتال PvP التنقل."),
            new Page("الفعاليات والتنين","يعرض /events الهدف النشط والاتجاه والجائزة، ويمكن إيقاف التتبع.\n\nيظهر تنين الإند الأسبوعي يوم الجمعة 4:00 مساءً بتوقيت الخادم. يتشارك المشاركون الحقيقيون التقدم والمكافآت."),
            new Page("النخب والزعماء","الأعداء غير المألوفين والنادرين والملحميين والأسطوريين وزعماء النخبة الصغرى (Miniboss) متوهجون ولهم شريط حياة.\n\nيستيقظ من وقت لآخر ثلاثة زعماء عالميين باسمهم: The Ashen Knight وThe Warded Colossus وThe Cinder Warlord، في مكان ما بالعالم، مع تلميحات تضيّق موقعهم تدريجياً.\n\nتعتمد مكافآت الزعماء والنخب القوية على المشاركة والضرر الحقيقيين لا على الضربة الأخيرة فقط."),
            new Page("الموت والتخزين","ينشئ كل موت بالأغراض قبراً مستقلاً لمدة 48 ساعة. استخدم /graves لاختيار قبر أو إيقاف تتبعه.\n\n/enderchest خاص. تخزين الفصيل محمي إلا عند تفعيل التخزين المشترك بموافقة القائدين."),
            new Page("آثار العالم","الآثار قطع نادرة وقوية تُوجد في العالم. من يلتقطها يصبح مالكها النشط (ACTIVE) — لا يملك الأثر الواحد سوى لاعب واحد في كل مرة.\n\nيعرض /relics سجل كل أثر وحالته، ويساعد /relics trace <key> في تحديد موقع أثر تملكه.\n\nإذا فُقد أثر مالكه (لم يُعثر عليه في الحقيبة أو التخزين أو أرض الفصيل أو القبور أو العالم المحمّل) يُعلَّم كـ«مفقود». وبعد فترة يصبح «متاحاً» ويظهر من جديد في مكان ما بأشفال ليجده لاعب آخر — لا يُحذف الأثر أبداً، بل يعود إلى التداول فقط."),
            new Page("الغزو وكبسولات القرويين","يمكن لكبسولة القروي أسر — بل وسرقة — أي قروي عادي، حتى داخل أرض فصيل آخر محمية. لا يمكن أسر تجار الخادم أو الكائنات المحمية في منطقة الولادة. مرّر المؤشر فوق الكبسولة لرؤية مقايضات القروي وأسعارها الحالية.\n\nحجر السحر (obsidian) داخل أرض فصيل محمية يقاوم الغزو بصمت: يحتاج عدة انفجارات مؤهلة قبل أن ينكسر فعلياً، ويستعيد متانته تدريجياً إن لم يُصَب لفترة. خارج أراضي الفصائل يبقى حجر السحر عادياً كما في اللعبة الأصلية."),
            new Page("القوانين — الممنوع","ممنوع:\n"+String.join("\n",prohibited(true).stream().map(line->"• "+line).toList())+"\n\nالقوانين كاملة في أي وقت: /rules"),
            new Page("القوانين — المسموح","مسموح:\n"+String.join("\n",allowed(true).stream().map(line->"• "+line).toList())+"\n\n"+distinction(true)),
            new Page("المساعدة والملاحظات","يعرض /progress أهداف المغامرة.\n/stats [player]\n/history و /f history\n/sidebar لتبديل الواجهة.\n\nاستخدم /feedback <message> للإبلاغ عن خلل أو اقتراح إضافة. يغيّر /guide اللغة.\n\nيمكنك قراءة قوانين الخادم كاملة في أي وقت عبر /rules.")
    );}

    private Component page(String title,String body){return Component.text(title+"\n\n",NamedTextColor.GOLD).append(Component.text(body,NamedTextColor.DARK_GRAY));}
    boolean selfTest(){return englishPages().size()==arabicPages().size()&&englishPages().size()>=10&&book("en").getItemMeta() instanceof BookMeta&&book("ar").getItemMeta() instanceof BookMeta;}
    private record Page(String title,String body){}
}
