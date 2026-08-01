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
            new Page("WELCOME TO ASHFALL","Survival comes first. Factions, exploration, events, relics and the Ashen Knight create shared stories.\n\nStart with /smphelp."),
            new Page("YOUR FIRST DAYS","Gather food and tools. Check /balance.\n\nCreate a faction with /f create <name>. The leader stands at the chosen base center and uses /f claim."),
            new Page("LAND & RELATIONS","The first claim is 50 × 50. Use /f borders and /f expand.\n\n/f relations manages truces and alliances. Alliance storage works only after both leaders approve it."),
            new Page("MARKETPLACE","/shop, /ah, /luxuryshop and /shardshop open one connected Marketplace.\n\nUse Search / Filter, Sort and My Listings. The Shop Sell Basket works without typed item names."),
            new Page("ORDERS & COMMUNITY","Use /order to place a buy order. Use /orders to browse, fulfill, cancel or collect orders.\n\n/discord shows the Ashfall community invite."),
            new Page("SHARDS","Shards are account-bound rewards from active play and meaningful boss or elite participation.\n\nSpend them in /shardshop. Strong rewards have per-player limits; cosmetics rotate weekly."),
            new Page("ASHEN SETTINGS","Open Ashen Settings from Java Quick Actions (G), the pause menu, or /settings. Bedrock receives a touch-friendly form.\n\nChoices persist."),
            new Page("SPAWNS & VISION","Natural Hostile Spawns OFF clears ordinary nearby hostiles, including elites, only when no nearby player has it ON. Bosses remain.\n\nNight Vision is infinite and yields to boss darkness."),
            new Page("TRAVEL","/home and /f home\n/tpa <player>\n/spawn\n/rtp\n\nRTP searches your current dimension: Overworld, Nether or End. It avoids claims and protected areas. PvP combat blocks travel."),
            new Page("EVENTS & DRAGON","/events shows the active objective, direction and reward. Tracking can be toggled.\n\nThe weekly Ender Dragon is Friday at 4:00 PM server time. Meaningful participants share progression and rewards."),
            new Page("ELITES & BOSSES","Uncommon, Rare, Epic and Legendary enemies glow and show health bars.\n\nBoss and high-tier rewards use meaningful damage participation, not only the final hit."),
            new Page("DEATH & STORAGE","Deaths with items create separate 48-hour graves. Use /graves to select one or stop tracking.\n\n/enderchest is private. Faction storage blocks outsiders unless bilateral alliance storage is active."),
            new Page("HELP & FEEDBACK","/progress shows Adventure goals.\n/stats [player]\n/history and /f history\n/sidebar toggles the HUD.\n\nUse /feedback <message> to report bugs or suggest additions. /guide changes language.")
    );}

    private List<Page> arabicPages(){return List.of(
            new Page("مرحباً بك في أشفال","البقاء هو الأساس. الفصائل والاستكشاف والفعاليات والآثار وفارس الرماد تصنع قصصاً مشتركة.\n\nابدأ بالأمر /smphelp."),
            new Page("أيامك الأولى","اجمع الطعام والأدوات وتحقق من رصيدك عبر /balance.\n\nأنشئ فصيلاً عبر /f create <name>. يقف القائد في مركز القاعدة المختار ثم يستخدم /f claim."),
            new Page("الأرض والعلاقات","أول حماية 50 × 50. استخدم /f borders و /f expand.\n\nيدير /f relations الهدن والتحالفات. لا يفتح تخزين التحالف إلا بعد موافقة القائدين."),
            new Page("السوق الموحد","تفتح أوامر /shop و /ah و /luxuryshop و /shardshop سوقاً واحداً مترابطاً.\n\nاستخدم البحث والفرز وقائمة عروضك. تتيح سلة البيع وضع الأغراض مباشرة."),
            new Page("الطلبات والمجتمع","استخدم /order لإنشاء طلب شراء، و /orders لتصفح الطلبات وتنفيذها أو إلغائها واستلامها.\n\nيعرض /discord رابط مجتمع أشفال."),
            new Page("الشظايا","الشظايا مكافآت مرتبطة بحسابك من اللعب الفعّال والمشاركة الحقيقية في قتال النخب والزعماء.\n\nأنفقها في /shardshop. للمكافآت القوية حدود، وتتبدل الزينة أسبوعياً."),
            new Page("إعدادات أشن","افتح إعدادات أشن من الإجراءات السريعة (G) أو قائمة الإيقاف في Java، أو استخدم /settings. تظهر نافذة مناسبة للمس في Bedrock.\n\nتُحفظ اختياراتك."),
            new Page("الوحوش والرؤية","عند إيقاف الوحوش الطبيعية تختفي الوحوش العدائية العادية القريبة، بما فيها النخبة، ما لم يكن لاعب قريب قد فعّلها. الزعماء لا يختفون.\n\nالرؤية الليلية دائمة ولا تلغي ظلام الزعماء."),
            new Page("التنقل","/home و /f home\n/tpa <player>\n/spawn\n/rtp\n\nيبحث RTP في بُعدك الحالي: العالم العادي أو النذر أو الإند، ويتجنب المناطق المحمية. يمنع قتال PvP التنقل."),
            new Page("الفعاليات والتنين","يعرض /events الهدف النشط والاتجاه والجائزة، ويمكن إيقاف التتبع.\n\nيظهر تنين الإند الأسبوعي يوم الجمعة 4:00 مساءً بتوقيت الخادم. يتشارك المشاركون الحقيقيون التقدم والمكافآت."),
            new Page("النخب والزعماء","الأعداء غير المألوفين والنادرين والملحميين والأسطوريين متوهجون ولهم شريط حياة.\n\nتعتمد مكافآت الزعماء والنخب القوية على المشاركة والضرر الحقيقيين لا على الضربة الأخيرة فقط."),
            new Page("الموت والتخزين","ينشئ كل موت بالأغراض قبراً مستقلاً لمدة 48 ساعة. استخدم /graves لاختيار قبر أو إيقاف تتبعه.\n\n/enderchest خاص. تخزين الفصيل محمي إلا عند تفعيل التخزين المشترك بموافقة القائدين."),
            new Page("المساعدة والملاحظات","يعرض /progress أهداف المغامرة.\n/stats [player]\n/history و /f history\n/sidebar لتبديل الواجهة.\n\nاستخدم /feedback <message> للإبلاغ عن خلل أو اقتراح إضافة. يغيّر /guide اللغة.")
    );}

    private Component page(String title,String body){return Component.text(title+"\n\n",NamedTextColor.GOLD).append(Component.text(body,NamedTextColor.DARK_GRAY));}
    boolean selfTest(){return englishPages().size()==arabicPages().size()&&englishPages().size()>=10&&book("en").getItemMeta() instanceof BookMeta&&book("ar").getItemMeta() instanceof BookMeta;}
    private record Page(String title,String body){}
}
