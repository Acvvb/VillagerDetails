package com.villagerdetails.command.c.impl.rule.villager;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.villagerdetails.cache.impl.TradeItemCompletionCache;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.config.impl.IdTranslationConfig;
import com.villagerdetails.handler.entity.villager.trade.VillagerTradeSearchHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

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
 * {@link IdTranslationConfig#getAllSearchableNames()}，保证补全列表不会是空的。
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
                                .executes(context -> VillagerTradeSearchHandler.searchItems(
                                        context.getSource().getPlayer(),
                                        StringArgumentType.getString(context, KEYWORD)))))
                .then(Commands.literal(BOOK)
                        .executes(context -> VillagerTradeSearchHandler.showUsage(context.getSource().getPlayer()))
                        .then(Commands.argument(KEYWORD, StringArgumentType.greedyString())
                                .suggests((_, builder) -> SharedSuggestionProvider.suggest(bookSuggestions(), builder))
                                .executes(context -> VillagerTradeSearchHandler.searchBooks(
                                        context.getSource().getPlayer(),
                                        StringArgumentType.getString(context, KEYWORD)))));
    }

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
            return IdTranslationConfig.getAllSearchableNames();
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
        return dynamic.isEmpty() ? IdTranslationConfig.getAllSearchableNames() : dynamic;
    }
}
