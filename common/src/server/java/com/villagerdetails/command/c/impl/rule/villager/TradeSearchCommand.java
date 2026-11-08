package com.villagerdetails.command.c.impl.rule.villager;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.villagerdetails.cache.impl.TradeItemCompletionCache;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.concurrent.ServerThreadManager;
import com.villagerdetails.config.impl.TradeIndexConfig;
import com.villagerdetails.handler.entity.villager.trade.VillagerTradeSearchHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * {@code /ec c TradeSearch <item|book> <关键词>} —— 查询全存档「已锁定交易」的村民能卖什么。
 *
 * <p>规则开关本身照旧走 {@code /ec TradeSearch <state>}；本类只负责 {@code /ec c} 下面的子命令树：
 * <pre>
 *   /ec c TradeSearch              显示用法
 *   /ec c TradeSearch item [关键词] 普通物品交易（<b>不含</b>附魔书）
 *   /ec c TradeSearch book [关键词] 附魔书交易，关键词是附魔 id / 中文名（{@code mending} / 经验修补）
 * </pre>
 *
 * <h2>线程模型（玩家分支）</h2>
 * <ol>
 *     <li>命令在主线程执行：只做「1 秒防抖 + 抓 playerId + 提交任务」，<b>不在 tick 里查索引</b>；</li>
 *     <li>{@code VillagerDetails-Query} 查询线程跑
 *         {@link VillagerTradeSearchHandler#searchItemAsync} / {@code searchBookAsync}，
 *         产出纯数据 {@link VillagerTradeSearchHandler.SearchResult}；</li>
 *     <li>{@code server.execute(...)} 切回主线程 —— 它天然就是「下一 tick 执行」，
 *         不需要额外的延迟机制。玩家感知延迟 1 tick（50ms），主线程 tick 完全不受影响。</li>
 * </ol>
 *
 * <p><b>为什么闭包里不捕获 {@link ServerPlayer}</b>：查询可能要跑几十毫秒，这段时间里玩家
 * 完全可能下线 / 换维度 / 被踢。闭包攥着一个 {@code ServerPlayer} 引用既拖住对象，又会在发包时
 * 用到已失效的连接。所以只带 {@code UUID}，切回主线程时用
 * {@code server.getPlayerList().getPlayer(playerId)} 重新取，取到 {@code null} 就静默丢弃。
 *
 * <p><b>为什么不用同一个池子</b>：查询与「区块卸载抓快照」共用队列时，玩家刷几次查询就能把
 * 快照任务挤掉 —— 那是要丢数据的。查询池独立（见 {@link ServerThreadManager#executeQuery}）。
 *
 * <p><b>为什么拆成两个子命令</b>：附魔书交易的关键词是「附魔」，和物品完全不是一套词汇；
 * 混在一个命令里时，搜「绿宝石」会把一堆「57 绿宝石 + 1 书 -> 1 附魔书」倒出来，而搜
 * 「经验修补」又和物品名挤在同一份补全里。拆开以后两个补全列表各自干净：
 * <ul>
 *     <li>{@code item} → 交易里出现过的物品 id + 中文叫法（{@code minecraft:bookshelf} / 书架）；</li>
 *     <li>{@code book} → 交易里可能产出的附魔（{@code mending} / {@code minecraft:mending} / 经验修补）。</li>
 * </ul>
 * 数据源都是 {@link TradeItemCompletionCache}：服务端启动 / 数据包重载时遍历
 * {@code VILLAGER_TRADE} 注册表动态扫出来的，所以数据包或模组新增的交易也能补全。
 *
 * <p>缓存还没刷过（服务端启动早期）时退回静态别名表
 * {@link TradeIndexConfig#getAllSearchableNames()}，保证补全列表不会是空的。
 *
 * <p>关键词用 {@link StringArgumentType#greedyString()}，<b>不能用 {@code word()}</b>：
 * Brigadier 的 {@code word()} 走 {@code StringReader#readUnquotedString}，只认
 * {@code 0-9 A-Z a-z _ - . +}，中文（以及空格、逗号以外的一切非 ASCII 字符）会被当成
 * 「尾随数据」直接报 {@code Expected whitespace to end one argument, but found trailing data}。
 * 这两个命令的关键词都以中文为主（{@code 经验修补}、{@code 书架}），所以必须用 greedy。
 *
 * <p>greedy 不影响补全：{@code SuggestionsBuilder#getRemaining()} 给的是这一整个参数已敲的内容，
 * {@link SharedSuggestionProvider#suggest} 按它当前缀过滤，所以敲 {@code 经验} 按 Tab 照样出
 * {@code 经验修补}；顺带地把 {@code x y z} 这种带空格的坐标写法也救回来了。
 *
 * <p><b>字面量必须与 {@code RuleType.VILLAGER_TRADE_SEARCH} 的注册名一致</b>
 * （都是 {@code TradeSearch}）：{@code CCommand} 的启动期自检会检查这一点，
 * 不一致会打 WARN；整条子命令的准入条件（规则开没开、权限档位够不够）也由
 * {@code CCommand} 统一挂上，这里不重复写 {@code requires}。
 */
public final class TradeSearchCommand implements RegisterServer {

    public static final TradeSearchCommand INSTANCE = new TradeSearchCommand();

    /**
     * 子命令字面量：普通物品。
     */
    public static final String ITEM = "item";

    /**
     * 子命令字面量：附魔书。
     */
    public static final String BOOK = "book";

    /**
     * 关键词参数名。
     */
    private static final String KEYWORD = "keyword";

    private static final Logger log = LogManager.getLogger(TradeSearchCommand.class);

    /**
     * 提示键：查询线程抛异常 / 池子拒绝。
     */
    private static final String K_QUERY_FAILED = "command.villager_trade_search.query_failed";
    private static final String K_TOO_BUSY = "command.villager_trade_search.too_busy";

    /**
     * 同一玩家的防抖表：{@code 玩家 UUID → 上次查询时的 gameTime}。
     *
     * <p><b>为什么需要它</b>：{@code greedyString} 的命令很容易被连按（尤其是没命中时玩家会
     * 反复回车重试）。不加防抖的话，每次回车都是一个查询任务，十几下就能把
     * <p>条目数上限 = 本服跑过这条命令的玩家数，量级极小，不需要额外清理；换存档后
     * {@code gameTime} 归零会命中下面的 {@code now >= last} 判断，自然放行。
     */
    private static final Map<UUID, Long> LAST_QUERY_TICK = new ConcurrentHashMap<>();

    /**
     * 防抖窗口：20 tick = 1 秒。
     */
    private static final long QUERY_COOLDOWN_TICKS = 20L;

    private TradeSearchCommand() {
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("TradeSearch")
                .executes(context -> VillagerTradeSearchHandler.showUsage(context.getSource().getPlayer()))
                .then(Commands.literal(ITEM)
                        .executes(context -> VillagerTradeSearchHandler.showUsage(context.getSource().getPlayer()))
                        .then(Commands.argument(KEYWORD, StringArgumentType.greedyString())
                                .suggests((_, builder) -> SharedSuggestionProvider.suggest(itemSuggestions(), builder))
                                .executes(context -> runSearch(context, false))))
                .then(Commands.literal(BOOK)
                        .executes(context -> VillagerTradeSearchHandler.showUsage(context.getSource().getPlayer()))
                        .then(Commands.argument(KEYWORD, StringArgumentType.greedyString())
                                .suggests((_, builder) -> SharedSuggestionProvider.suggest(bookSuggestions(), builder))
                                .executes(context -> runSearch(context, true))));
    }

    // ============================================================
    // 执行：玩家走异步，控制台 / 命令方块走同步
    // ============================================================

    /**
     * 一条查询命令的实际执行入口。
     *
     * <p>两个分支的差别只在「谁在哪个线程上跑」：
     * <ul>
     *     <li><b>玩家</b>：主线程提交 → 查询线程搜 → 下一 tick 主线程发包（不卡 tick）；</li>
     *     <li><b>控制台 / 命令方块</b>：没有 {@link ServerPlayer}、也不在 tick 线程上，
     *         直接同步查完发日志即可（本来就是低频操作）。</li>
     * </ul>
     */
    private static int runSearch(CommandContext<CommandSourceStack> context, boolean book) {
        ServerPlayer player = context.getSource().getPlayer();
        String keyword = StringArgumentType.getString(context, KEYWORD);

        if (player == null) {
            // 控制台 / 命令方块：同步版本，物品名走服务端别名表
            return book
                    ? VillagerTradeSearchHandler.searchBooks(null, keyword)
                    : VillagerTradeSearchHandler.searchItems(null, keyword);
        }

        MinecraftServer server = context.getSource().getServer();
        UUID playerId = player.getUUID();

        if (!passQueryCooldown(server, playerId)) {
            sendOrBroadcast(player, Component.translatable(K_TOO_BUSY));
            return 0;
        }

        boolean submitted = ServerThreadManager.executeQuery(() -> {
            VillagerTradeSearchHandler.SearchResult result;
            try {
                result = book
                        ? VillagerTradeSearchHandler.searchBookAsync(keyword)
                        : VillagerTradeSearchHandler.searchItemAsync(keyword);
            } catch (Throwable t) {
                log.error("异步查询村民交易失败：book={}, keyword={}", book, keyword, t);
                server.execute(() -> {
                    ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                    if (online != null) {
                        sendOrBroadcast(online, Component.translatable(K_QUERY_FAILED));
                    }
                });
                return;
            }
            // server.execute(...) 天然就是「下一 tick 执行」：不需要任何延迟机制。
            // 这里重新按 UUID 取玩家 —— 闭包绝不捕获 ServerPlayer 引用。
            server.execute(() -> {
                ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                if (online == null) {
                    // 玩家已离线 / 换服：静默丢弃，不发包也不报错
                    return;
                }
                VillagerTradeSearchHandler.sendResult(online, result);
            });
        });

        if (!submitted) {
            // 池子未初始化（停服中）或队列被打满：告诉玩家稍后再试，别让他以为命令没反应
            sendOrBroadcast(player, Component.translatable(K_TOO_BUSY));
            return 0;
        }
        return 1;
    }

    /**
     * 同一玩家 1 秒内只允许一次查询。
     *
     * @return true = 放行（并刷新时间戳）；false = 太快了，本次丢弃
     */
    private static boolean passQueryCooldown(MinecraftServer server, UUID playerId) {
        ServerLevel overworld = server.overworld();
        long now = overworld.getGameTime();
        Long last = LAST_QUERY_TICK.get(playerId);
        // now < last：换存档 / 重开服后 gameTime 归零，视为过期记录，直接放行并刷新
        if (last != null && now >= last && now - last < QUERY_COOLDOWN_TICKS) {
            return false;
        }
        LAST_QUERY_TICK.put(playerId, now);
        return true;
    }

    // ============================================================
    // 补全
    // ============================================================

    /**
     * {@code item} 的补全：交易里出现过的物品 id + 中文叫法（<b>不含附魔书</b>）。
     *
     * <p>缓存本身已经排除了 {@code minecraft:enchanted_book}，这里再挡一道 ——
     * 补全范围只允许是「普通物品交易」的主题，附魔书归 {@code book} 子命令。
     * 缓存没就绪（服务端启动早期）时退回静态别名表。
     */
    private static Collection<String> itemSuggestions() {
        Set<String> dynamic = TradeItemCompletionCache.getItemSuggestions();
        if (dynamic.isEmpty()) {
            return TradeIndexConfig.getAllSearchableNames();
        }
        List<String> filtered = new ArrayList<>(dynamic.size());
        for (String suggestion : dynamic) {
            if (!isEnchantedBookSuggestion(suggestion)) {
                filtered.add(suggestion);
            }
        }
        return filtered;
    }

    /**
     * 这条补全项是不是指「附魔书」（{@code minecraft:enchanted_book} 或裸 {@code enchanted_book}）。
     */
    private static boolean isEnchantedBookSuggestion(String suggestion) {
        return suggestion != null && suggestion.endsWith("enchanted_book");
    }

    /**
     * {@code book} 的补全：交易里可能产出的附魔（短 id / 完整 id / 中文叫法）。
     * 缓存没就绪时退回静态别名表（里面本来就有一份附魔中文名，够用）。
     */
    private static Collection<String> bookSuggestions() {
        Set<String> dynamic = TradeItemCompletionCache.getEnchantmentSuggestions();
        return dynamic.isEmpty() ? TradeIndexConfig.getAllSearchableNames() : dynamic;
    }
}
