package com.villagerdetails.command.c.impl.rule.villager;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.handler.entity.villager.trade.VillagerTradeSearchHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /ec c TradeSearch <关键词>} —— 查询全存档「已锁定交易」的村民能卖什么。
 *
 * <p>规则开关本身照旧走 {@code /ec TradeSearch <state>}；本类只负责
 * {@code /ec c} 下面的子命令树：
 * <pre>
 *   /ec c TradeSearch                   显示用法
 *   /ec c TradeSearch &lt;关键词&gt;        搜索内存缓存（物品名 / id / 附魔名 / 坐标 / 维度 / UUID）
 *   /ec c TradeSearch teleport &lt;uuid&gt; 传送到该村民（结果行里的 [传送] 按钮点出来就是它）
 * </pre>
 *
 * <p><b>字面量必须与 {@code RuleType.VILLAGER_TRADE_SEARCH} 的注册名一致</b>
 * （都是 {@code TradeSearch}）：{@code CCommand} 的启动期自检会检查这一点，
 * 不一致会打 WARN；整条子命令的准入条件（规则开没开、权限档位够不够）也由
 * {@code CCommand} 统一挂上，这里不重复写 {@code requires}。
 *
 * <p>为什么不用开头的 {@code keyword} 遮住 {@code teleport}：Brigadier 会先精确匹配字面量，
 * 所以关键词恰好是 {@code teleport} 时会被当成子命令（其余关键词不受影响）。
 */
public final class TradeSearchCommand implements RegisterServer {

    public static final TradeSearchCommand INSTANCE = new TradeSearchCommand();

    private TradeSearchCommand() {
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("TradeSearch")
                .executes(context -> VillagerTradeSearchHandler.showUsage(context.getSource().getPlayer()))
                .then(Commands.literal("teleport")
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(context -> VillagerTradeSearchHandler.teleport(
                                        context.getSource().getPlayer(),
                                        StringArgumentType.getString(context, "uuid")))))
                .then(Commands.argument("keyword", StringArgumentType.greedyString())
                        .executes(context -> VillagerTradeSearchHandler.search(
                                context.getSource().getPlayer(),
                                StringArgumentType.getString(context, "keyword"))));
    }
}
