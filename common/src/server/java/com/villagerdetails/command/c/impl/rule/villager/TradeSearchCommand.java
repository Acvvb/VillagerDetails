package com.villagerdetails.command.c.impl.rule.villager;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.config.impl.IdTranslationConfig;
import com.villagerdetails.handler.entity.villager.trade.VillagerTradeSearchHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

/**
 * {@code /ec c TradeSearch <关键词>} —— 查询全存档「已锁定交易」的村民能卖什么。
 *
 * <p>规则开关本身照旧走 {@code /ec TradeSearch <state>}；本类只负责
 * {@code /ec c} 下面的子命令树：
 * <pre>
 *   /ec c TradeSearch             显示用法
 *   /ec c TradeSearch &lt;关键词&gt;  搜索内存缓存（物品名 / id / 附魔名 / 坐标 / 维度 / UUID）
 * </pre>
 *
 * <p><b>关键词的 Tab 补全</b>：数据源是交易索引（{@code trade_index.json}）里的
 * <b>英文 id</b>（{@code mending}）与<b>中文叫法</b>（{@code 经验修补}），见
 * {@link IdTranslationConfig#getAllSearchableNames()} —— 每次按 Tab 都重新取一遍，
 * 重载索引后补全内容立刻跟着变。
 *
 * <p>关键词用 {@link StringArgumentType#greedyString()}，<b>不能用 {@code word()}</b>：
 * Brigadier 的 {@code word()} 走 {@code StringReader#readUnquotedString}，只认
 * {@code 0-9 A-Z a-z _ - . +}，中文（以及空格、逗号以外的一切非 ASCII 字符）会被当成
 * 「尾随数据」直接报 {@code Expected whitespace to end one argument, but found trailing data}。
 * 这个命令的关键词本来就以中文为主（{@code 经验修补}），所以必须用 greedy。
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

    private TradeSearchCommand() {
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("TradeSearch")
                .executes(context -> VillagerTradeSearchHandler.showUsage(context.getSource().getPlayer()))
                .then(Commands.argument("keyword", StringArgumentType.greedyString())
                        .suggests((_, builder) -> SharedSuggestionProvider.suggest(
                                IdTranslationConfig.getAllSearchableNames(), builder))
                        .executes(context -> VillagerTradeSearchHandler.search(
                                context.getSource().getPlayer(),
                                StringArgumentType.getString(context, "keyword"))));
    }
}
