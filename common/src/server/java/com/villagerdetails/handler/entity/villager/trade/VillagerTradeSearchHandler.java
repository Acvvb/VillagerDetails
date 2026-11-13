package com.villagerdetails.handler.entity.villager.trade;

import com.villagerdetails.cache.impl.VillagerTradeCache;
import com.villagerdetails.config.impl.TradeIndexConfig;
import com.villagerdetails.lang.ServerTranslations;
import com.villagerdetails.pojo.dto.TradeItemInfo;
import com.villagerdetails.pojo.dto.VillagerTradeInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * {@code /ec c TradeSearch <item|book>} 的业务实现：<b>只查内存缓存</b>，不遍历世界。
 *
 * <h2>三段式（线程边界写死在方法名里）</h2>
 * <ol>
 *     <li><b>命令层（主线程）</b>：{@code /ec c TradeSearch ...} 只负责防抖 + 提交任务；</li>
 *     <li><b>查询线程（{@code VillagerDetails-Query}）</b>：{@link #searchItemAsync} /
 *         {@link #searchBookAsync} 遍历 CHM、匹配、排序、截断，产出<b>纯数据</b>
 *         {@link SearchResult}。这里<b>绝不</b>碰 {@link ServerPlayer}、绝不构造
 *         {@link Component}、绝不发包 —— 那些都是主线程的东西；</li>
 *     <li><b>主线程（下一 tick）</b>：{@link #sendResult} 只消费纯数据拼聊天栏消息。</li>
 * </ol>
 *
 * <p>这样拆开之后，一次查询的「重活」（遍历几万条村民 × 每条十几笔交易、拼别名匹配）
 * 完全不占 tick，玩家只多等 1 tick（50ms）就看结果。
 *
 * <p><b>为什么纯数据里允许出现 {@link BlockPos} / {@link ResourceKey}</b>：它们是原版不可变值
 * 类型，{@link VillagerTradeInfo} 的构造器已经保证 {@code pos.immutable()} 且整个 DTO 跨线程
 * 只读（见该 record 的注释）。真正不能带过线程边界的是 {@code Villager}、{@code MerchantOffer}、
 * {@code ItemStack}、{@code Component} 这些「活的」对象，这里一个都没有。
 *
 * <h2>控制台 / 命令方块路径（{@code player == null}）</h2>
 * 三条公开入口（{@link #showUsage}、{@link #sendResult}，以及转手给它们的
 * {@link #searchItems} / {@link #searchBooks}）都接受 {@code player == null}。
 * 它们<b>不直接调</b> {@code player.sendSystemMessage(...)}，而是统一走
 * {@link com.villagerdetails.util.SendMessengerUtils#sendOrBroadcast} ——
 * 那个工具在 {@code player == null} 时会打一条 {@code INFO} 到服务端日志
 * （见它的 null 分支）。所以控制台执行不会 NPE，结果输出在服务端日志里，
 * 而不是任何玩家客户端。物品名在控制台侧走服务端别名表（{@link TradeIndexConfig}），
 * 因为控制台没有客户端语言表。
 */
public final class VillagerTradeSearchHandler {

    private VillagerTradeSearchHandler() {
    }

    /**
     * 一次最多显示多少条村民（防刷屏）。
     */
    public static final int MAX_RESULTS = 10;

    /**
     * 一条结果里最多列几笔<b>命中的</b>交易，多出来的折叠成「另有 N 件」。
     */
    public static final int MAX_OFFERS_PER_ENTRY = 6;

    private static final String K_USAGE = "command.villager_trade_search.usage";
    private static final String K_EMPTY = "command.villager_trade_search.empty";
    private static final String K_NO_MATCH = "command.villager_trade_search.no_match";
    private static final String K_HEADER_ITEM = "command.villager_trade_search.header.item";
    private static final String K_HEADER_BOOK = "command.villager_trade_search.header.book";
    private static final String K_TRUNCATED = "command.villager_trade_search.truncated";
    private static final String K_ENTRY_USES = "command.villager_trade_search.entry.uses";
    private static final String K_MORE_OFFERS = "command.villager_trade_search.entry.more";
    private static final String K_HOVER_COPY_COORDS = "command.villager_trade_search.hover.copy_coords";
    private static final String K_HOVER_COPY_UUID = "command.villager_trade_search.hover.copy_uuid";

    // ============================================================
    // 纯数据载体（唯一允许跨线程传递的东西）
    // ============================================================

    /**
     * 一个附魔：只有 id 与等级，展示名由客户端本地化。
     */
    public record EnchantmentInfo(String id, int level) {
        public EnchantmentInfo {
            id = id == null ? "" : id;
            if (level < 1) {
                level = 1;
            }
        }
    }

    /**
     * 一笔命中的交易（纯数据）。
     *
     * <p>比提示词里的字段多了两组价格：原实现的结果行是
     * {@code 9 绿宝石 -> 1 书架}，没有价格就只剩「卖什么」，玩家没法判断划算不划算。
     *
     * @param resultItemId 卖出物 id
     * @param resultCount  卖出数量
     * @param price1Id     主价格物品 id
     * @param price1Count  主价格数量
     * @param price2Id     第二样价格物品 id；没有时为空串
     * @param price2Count  第二样价格数量；没有时为 0
     * @param usesLeft     这笔交易还能换几次（快照时的 {@code maxUses}）
     * @param enchantments 卖出物携带的附魔
     */
    public record TradeHit(String resultItemId, int resultCount,
                           String price1Id, int price1Count,
                           String price2Id, int price2Count,
                           int usesLeft,
                           List<EnchantmentInfo> enchantments) {
        public TradeHit {
            resultItemId = resultItemId == null ? "" : resultItemId;
            price1Id = price1Id == null ? "" : price1Id;
            price2Id = price2Id == null ? "" : price2Id;
            enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
        }

        /**
         * 有没有第二样价格（只有真的有两段价格时才写 {@code " + ..."}）。
         */
        public boolean hasSecondPrice() {
            return !price2Id.isBlank() && price2Count > 0;
        }
    }

    /**
     * 一条村民结果：命中的村民 + 命中的那几笔交易（不是它的全部交易）。
     */
    public record Entry(UUID uuid, BlockPos pos, ResourceKey<Level> dimension, List<TradeHit> hits) {
        public Entry {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    /**
     * 一次查询的完整结果（纯数据）。
     *
     * @param entries      已截断到 {@link #MAX_RESULTS} 条的展示条目
     * @param totalMatches 命中（且过滤掉死者之后）的村民总数
     * @param shownMatches 实际放进 {@code entries} 的条数
     * @param keyword      已 trim 的原始关键词（渲染时再消 §）
     * @param isBook       true = 附魔书子命令
     */
    public record SearchResult(List<Entry> entries, int totalMatches, int shownMatches,
                               String keyword, boolean isBook) {
        public SearchResult {
            entries = entries == null ? List.of() : List.copyOf(entries);
            keyword = keyword == null ? "" : keyword;
        }
    }

    // ============================================================
    // 异步查询（查询线程调用）
    // ============================================================

    /**
     * {@code /ec c TradeSearch item <关键词>}：普通物品交易（附魔书交易已排除）。
     */
    public static SearchResult searchItemAsync(String keyword) {
        return searchAsync(keyword, false);
    }

    /**
     * {@code /ec c TradeSearch book <关键词>}：附魔书交易，关键词是附魔 id / 中文名。
     */
    public static SearchResult searchBookAsync(String keyword) {
        return searchAsync(keyword, true);
    }

    /**
     * 两个子命令共用的异步搜索：遍历 → 过滤死者 → 转纯数据 → 截断。
     *
     * <p><b>匹配、排序复用缓存层</b>（{@code VillagerTradeCache.searchItem/searchBook}）：
     * 那套逻辑里已经有 id 直配 + 别名表中文名兜底 + 固定排序，搬到这里只会复制一份出来。
     * 缓存层的遍历是 CHM {@code values()} 的弱一致遍历，本身就是并发安全的。
     *
     * <p><b>死者必须二次过滤</b>：弱一致遍历可能返回「几毫秒前刚被
     * {@link VillagerTradeCache#remove} 物理删掉」的那一条（游标走过了 bucket，值还是旧的）。
     * 墓碑表就是为此存在的 —— 少了这一步会查出刚被打死的村民。
     */
    private static SearchResult searchAsync(String keyword, boolean book) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) {
            return new SearchResult(List.of(), 0, 0, "", book);
        }

        List<VillagerTradeCache.SearchResult> raw = book
                ? VillagerTradeCache.INSTANCE.searchBook(trimmed)
                : VillagerTradeCache.INSTANCE.searchItem(trimmed);

        Map<UUID, Long> removedAt = VillagerTradeCache.removedAtView();
        List<Entry> alive = new ArrayList<>(raw.size());
        for (VillagerTradeCache.SearchResult match : raw) {
            VillagerTradeInfo info = match.info();
            // 正确性保证（不是可选优化）：墓碑里有 = 这只村民已经死了，跳过
            if (removedAt.containsKey(info.uuid())) {
                continue;
            }
            List<TradeHit> hits = new ArrayList<>(match.matchedOffers().size());
            for (TradeItemInfo offer : match.matchedOffers()) {
                hits.add(toHit(offer));
            }
            alive.add(new Entry(info.uuid(), info.pos(), info.dimension(), hits));
        }

        int total = alive.size();
        int shown = Math.min(MAX_RESULTS, total);
        List<Entry> capped = shown == total ? alive : new ArrayList<>(alive.subList(0, shown));
        return new SearchResult(capped, total, shown, trimmed, book);
    }

    /**
     * {@link TradeItemInfo} → {@link TradeHit}：只做字段搬运，不解析名字、不碰注册表。
     */
    private static TradeHit toHit(TradeItemInfo offer) {
        List<EnchantmentInfo> enchantments = new ArrayList<>(offer.enchantments().size());
        for (TradeItemInfo.EnchantmentEntry enchantment : offer.enchantments()) {
            enchantments.add(new EnchantmentInfo(enchantment.id(), enchantment.level()));
        }
        return new TradeHit(offer.resultItemId(), offer.resultCount(),
                offer.price1Id(), offer.price1Count(),
                offer.price2Id(), offer.price2Count(),
                offer.maxUses(), enchantments);
    }

    // ============================================================
    // 同步入口（控制台 / 命令方块：没有 ServerPlayer，也不在 tick 线程上）
    // ============================================================

    /**
     * 不带关键词：发用法提示，并顺带告诉玩家缓存里现在有多少条。
     *
     * <p>{@code player == null}（控制台 / 命令方块）时不会 NPE —— 消息统一交给
     * {@link com.villagerdetails.util.SendMessengerUtils#sendOrBroadcast}，
     * 它的 null 分支会把内容打一条 {@code INFO} 到服务端日志。
     *
     * @param player 目标玩家；{@code null} = 控制台 / 命令方块
     */
    public static int showUsage(@Nullable ServerPlayer player) {
        sendOrBroadcast(player, Component.translatable(
                K_USAGE, String.valueOf(VillagerTradeCache.INSTANCE.size())));
        return 1;
    }

    /**
     * 同步版本（控制台 / 命令方块专用）：本身就是低频调用，直接查完再发。
     *
     * <p>形参可为 {@code null}：{@code null} 就是控制台 / 命令方块，输出走
     * {@code sendOrBroadcast} 的日志分支（见 {@link #sendResult}）。
     *
     * @return 实际显示的结果条数
     */
    public static int searchItems(@Nullable ServerPlayer player, String keyword) {
        return renderSync(player, keyword, false);
    }

    /**
     * 同步版本（控制台 / 命令方块专用）。
     *
     * <p>形参可为 {@code null}，语义同 {@link #searchItems}。
     */
    public static int searchBooks(@Nullable ServerPlayer player, String keyword) {
        return renderSync(player, keyword, true);
    }

    /**
     * 两个同步子命令的共用实现。
     *
     * <p>关键词为空时退回 {@link #showUsage}（同样接受 {@code null}）；否则直接复用异步侧的
     * 纯查询（{@code searchXxxAsync} 不依赖线程）再交给 {@link #sendResult} 输出。
     * 控制台路径从头到尾没有一处直调 {@code player.xxx}，所以 {@code player == null} 是安全的。
     *
     * @param player 目标玩家；{@code null} = 控制台 / 命令方块（输出到服务端日志）
     */
    private static int renderSync(@Nullable ServerPlayer player, String keyword, boolean book) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) {
            return showUsage(player);
        }
        // 注意：searchXxxAsync 是纯查询，不依赖线程，所以这里直接复用同一套逻辑
        return sendResult(player, book ? searchBookAsync(trimmed) : searchItemAsync(trimmed));
    }

    // ============================================================
    // 主线程渲染（只消费纯数据）
    // ============================================================

    /**
     * 把异步查询结果发出去。<b>必须在主线程调用</b>（要 construct {@link Component}、要发包）。
     *
     * <p><b>控制台 / 命令方块的输出通道</b>：{@code player == null} 时不是「发给某个玩家」，
     * 而是由 {@link com.villagerdetails.util.SendMessengerUtils#sendOrBroadcast} 把整条消息
     * 打一条 {@code INFO} 到服务端日志。这里所有消息都走那个工具，<b>没有任何
     * {@code player.sendSystemMessage(...)} 直调</b>，所以控制台执行不会 NPE；
     * 物品名也会切到服务端别名表（{@link TradeIndexConfig}），因为控制台没有客户端语言表。
     *
     * @param player 目标玩家；{@code null} = 控制台 / 命令方块（输出到服务端日志）
     * @param result {@link #searchItemAsync} / {@link #searchBookAsync} 的产物
     * @return 实际显示的结果条数；0 = 没命中 / 缓存为空
     */
    public static int sendResult(@Nullable ServerPlayer player, SearchResult result) {
        // 关键词会原样拼进聊天栏标题：去掉 § 颜色码，免得玩家用关键词给整行染色
        String displayKeyword = result.keyword().replace('§', ' ');

        if (result.entries().isEmpty()) {
            if (VillagerTradeCache.INSTANCE.size() == 0) {
                // 缓存空 = 还没积累到任何快照（区块卸载 / 开界面时才会写缓存），单独给一句提示
                sendOrBroadcast(player, Component.translatable(K_EMPTY));
            } else {
                sendOrBroadcast(player, Component.translatable(K_NO_MATCH, displayKeyword));
            }
            return 0;
        }

        // 玩家：物品名发 translatable，客户端自己本地化；控制台 / 命令方块：没有客户端语言表，
        // 用服务端别名表（trade_index.json + 内置）兜底，免得日志里全是 item.minecraft.xxx
        boolean console = player == null;
        MutableComponent out = Component.translatable(
                result.isBook() ? K_HEADER_BOOK : K_HEADER_ITEM,
                displayKeyword,
                String.valueOf(result.totalMatches()),
                String.valueOf(result.shownMatches()));

        List<Entry> entries = result.entries();
        for (int i = 0; i < entries.size(); i++) {
            out.append("\n").append(entry(entries.get(i), i + 1, console));
        }
        if (result.totalMatches() > result.shownMatches()) {
            out.append("\n").append(Component.translatable(
                    K_TRUNCATED, String.valueOf(result.totalMatches() - result.shownMatches())));
        }

        sendOrBroadcast(player, out);
        return result.shownMatches();
    }

    /**
     * 一条结果：
     * <pre>
     * #1 主世界 [305, 64, 227] UUID: c49fcd79
     *   9 绿宝石 -> 1 书架（可交易 12 次）
     *   18 绿宝石 + 1 书 -> 1 附魔书[经验修补]（可交易 12 次）
     * </pre>
     *
     * <p>只打<b>命中的那几笔交易</b>，最多 {@value #MAX_OFFERS_PER_ENTRY} 笔，剩下的折叠成
     * 「另有 N 件」—— 这个截断放在渲染侧（异步侧保留完整命中列表），因为「另有 N 件」
     * 需要知道被折叠掉多少，而纯数据里不值得为此多存一个字段。
     */
    private static MutableComponent entry(Entry result, int index, boolean console) {
        MutableComponent line = Component.literal("§7#" + index + " ")
                .append(dimensionName(result.dimension()))
                .append(Component.literal(" §7"))
                .append(coordinateText(result.pos()))
                .append(Component.literal(" §7"))
                .append(uuidText(result.uuid()));

        List<TradeHit> hits = result.hits();
        int limit = Math.min(MAX_OFFERS_PER_ENTRY, hits.size());
        for (int i = 0; i < limit; i++) {
            // 同一村民的多笔命中交易逐行打印（搜「绿宝石」时「收绿宝石」和「卖绿宝石」各一行）
            line.append("\n  ").append(offerLine(hits.get(i), console));
        }
        if (hits.size() > limit) {
            line.append("\n  ").append(Component.translatable(K_MORE_OFFERS, String.valueOf(hits.size() - limit)));
        }
        return line;
    }

    /**
     * 村民 UUID：显示前 8 位，<b>点击复制完整 UUID</b>（悬停提示）。
     *
     * <p>缩短只是为了让第一行别被 36 位 UUID 撑得太长；真正要用的时候点一下就拿到全的。
     */
    private static MutableComponent uuidText(UUID uuid) {
        String full = uuid.toString();
        return Component.literal("UUID: " + uuid)
                .withStyle(style -> style.withColor(TextColor.fromRgb(0x9CC2E5)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.CopyToClipboard(full)))
                .withStyle(style -> style.withHoverEvent(
                        new HoverEvent.ShowText(
                                ServerTranslations.translateComponent(
                                        Component.translatable(K_HOVER_COPY_UUID))
                        )
                ));
    }

    /**
     * 一笔交易 → 组件：{@code 1 绿宝石 + 1 书 -> 1 附魔书[经验修补]（可交易 12 次）}。
     *
     * <p><b>玩家看到的名字一律发 {@code Component.translatable}</b>，由<b>客户端</b>按自己的语言表
     * 解析：中文客户端显示「书架」，英文客户端显示 {@code Bookshelf} —— 服务端一行语言表都不需要，
     * 而且旧索引记录也会立刻跟着客户端语言走（服务端只存 id，不存名字）。
     *
     * @param console 控制台 / 命令方块等没有客户端语言表的调用方；此时改用服务端别名表兜底
     */
    private static MutableComponent offerLine(TradeHit hit, boolean console) {
        MutableComponent line = Component.literal("§f")
                .append(Component.literal(hit.price1Count() + " "))
                .append(itemName(hit.price1Id(), console));
        if (hit.hasSecondPrice()) {
            line.append(Component.literal(" + " + hit.price2Count() + " "))
                    .append(itemName(hit.price2Id(), console));
        }
        line.append(Component.literal(" -> " + hit.resultCount() + " "))
                .append(itemName(hit.resultItemId(), console));

        if (!hit.enchantments().isEmpty()) {
            line.append("[");
            for (int i = 0; i < hit.enchantments().size(); i++) {
                if (i > 0) {
                    line.append(", ");
                }
                line.append(enchantmentName(hit.enchantments().get(i), console));
            }
            line.append("]");
        }

        if (hit.usesLeft() > 0) {
            line.append(" ").append(Component.translatable(K_ENTRY_USES, String.valueOf(hit.usesLeft())));
        }
        return line;
    }

    /**
     * 物品 id → 展示名。
     *
     * <ul>
     *     <li>玩家：拿注册表里的 {@link Item} 实例，用原版 {@link Item#getDescriptionId()} 取
     *         <b>正确的翻译键</b> —— 方块类物品（{@code BlockItem}，例如书架、带釉陶瓦）用的是
     *         {@code block.minecraft.*}，普通物品才是 {@code item.minecraft.*}。手拼 {@code item.}
     *         前缀会让方块类物品在客户端显示成原始键，这正是「白色带釉陶瓦显示
     *         {@code item.minecraft.white_glazed_terracotta}」的原因；</li>
     *     <li>控制台：走服务端别名表（{@code trade_index.json} + 内置兜底），保证日志里是
     *         「绿宝石」而不是 {@code item.minecraft.emerald}。</li>
     * </ul>
     *
     * <p>id 写坏 / 注册表里没有（模组物品被卸载）时退回手拼 {@code item.<ns>.<path>}，
     * 不让一行结果炸掉整条消息。
     */
    private static MutableComponent itemName(String itemId, boolean console) {
        Identifier id = itemId == null ? null : Identifier.tryParse(itemId);
        if (id == null) {
            return Component.literal(String.valueOf(itemId));
        }
        if (!console) {
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item != Items.AIR) {
                // 原版自己知道该用 item. 还是 block. 前缀（BlockItem 走 block.minecraft.*）
                return Component.translatable(item.getDescriptionId());
            }
            // 注册表里找不到：退回手拼
            return Component.translatable("item." + id.getNamespace() + "." + id.getPath());
        }
        String alias = TradeIndexConfig.displayNameOf(id);
        return Component.literal(alias != null && !alias.isBlank() ? alias : itemId);
    }

    /**
     * 附魔 → 展示名：名称用 {@code enchantment.<ns>.<path>}，等级 &gt; 1 时追加
     * {@code enchantment.level.N}（原版自带 1~10，客户端渲染成 I / II / III）。
     *
     * <p><b>为什么这里手拼键、不走注册表</b>：1.21.9+ 附魔已经是数据驱动的动态注册表，
     * {@code BuiltInRegistries} 里<b>没有</b> {@code ENCHANTMENT} 入口（只有 {@code ITEM}），
     * 要拿实例得走 {@code server.registryAccess()}。而附魔的翻译键恒为
     * {@code enchantment.<ns>.<path>}（原版与模组都遵守这个约定），手拼是安全的 ——
     * 真正有「两套前缀」歧义的只有物品（item / block）。
     */
    private static MutableComponent enchantmentName(EnchantmentInfo enchantment, boolean console) {
        Identifier id = Identifier.tryParse(enchantment.id());
        MutableComponent name;
        if (!console) {
            name = id == null
                    ? Component.literal(enchantment.id())
                    : Component.translatable("enchantment." + id.getNamespace() + "." + id.getPath());
        } else {
            // 控制台：别名表里附魔的中文叫法（trade_index.json 的默认表就是一张附魔表）
            String alias = id == null ? null : TradeIndexConfig.displayNameOf(id);
            name = Component.literal(alias != null && !alias.isBlank() ? alias : enchantment.id());
        }
        if (enchantment.level() > 1) {
            name.append(" ").append(console
                    ? Component.literal(String.valueOf(enchantment.level()))
                    : Component.translatable("enchantment.level." + enchantment.level()));
        }
        return name;
    }

    /**
     * 坐标：按普通文本显示成 {@code [x, y, z]}，悬停提示、点击把 {@code x, y, z} 复制到剪贴板。
     *
     * <p>悬停提示也发 {@code Component.translatable}（而不是服务端解析好的字符串），
     * 这样它同样跟随客户端语言。
     *
     * <p>只是复制，不执行任何命令 —— 结果行里不会再挂 {@code /ec c TradeSearch ...} 这种
     * 「点了就跑」的命令（传送功能已移除）。
     */
    private static MutableComponent coordinateText(BlockPos pos) {
        String coords = formatPos(pos);

        return Component.literal("[" + coords + "]")
                .withStyle(style -> style.withColor(TextColor.fromRgb(0x9CC2E5)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.CopyToClipboard(coords)))
                .withStyle(style -> style.withHoverEvent(
                        new HoverEvent.ShowText(
                                ServerTranslations.translateComponent(
                                        Component.translatable(K_HOVER_COPY_COORDS)
                                )
                        )
                ));
    }

    /**
     * 维度显示名：三个原版维度用语言键，其它维度（模组维度）退回注册 id。
     */
    private static MutableComponent dimensionName(ResourceKey<Level> dimension) {
        if (Level.OVERWORLD.equals(dimension)) {
            return Component.translatable("dimension.minecraft.overworld");
        }
        if (Level.NETHER.equals(dimension)) {
            return Component.translatable("dimension.minecraft.the_nether");
        }
        if (Level.END.equals(dimension)) {
            return Component.translatable("dimension.minecraft.the_end");
        }
        return Component.literal(dimension.identifier().toString());
    }

    private static String formatPos(BlockPos pos) {
        return new StringJoiner(" ")
                .add(String.valueOf(pos.getX()))
                .add(String.valueOf(pos.getY()))
                .add(String.valueOf(pos.getZ()))
                .toString();
    }
}
