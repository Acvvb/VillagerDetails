package com.villagerdetails.command.c.impl.rule;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.impl.suggestion.BlockSuggestionData;
import com.villagerdetails.command.c.server.RegisterServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.Comparator;
import java.util.Map;
import java.util.function.Predicate;

import static com.villagerdetails.command.CommandConstants.PERM_BASE;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * 「方块id → 浮点抗性」类规则命令的公共实现：
 * <p>
 *   /ec c &lt;规则注册id&gt; &lt;方块id&gt; &lt;抗性&gt; —— 设置单个方块抗性（value 支持 {@code 1.5} 或 {@code 方块id=1.5}）
 *   /ec c &lt;规则注册id&gt; reset        —— 清空所有配置，恢复原版
 *   /ec c &lt;规则注册id&gt; show         —— 显示当前配置
 * <p>
 * 挖掘抗性（{@link BlockMiningResistanceServerImpl}）与爆炸抗性（{@link BlockExplosionResistanceServerImpl}）共用，
 * 仅通过抽象方法提供各自不同的注册名、语言键、配置存取与补全。
 */
public abstract class AbstractBlockResistanceServerImpl implements RegisterServer {

    /** 强制最高权限（OWNERS）才能调用。 */
    private static final Predicate<CommandSourceStack> OWNER_REQUIREMENT =
            src -> src.checkPermission(PERM_BASE, PermissionLevel.OWNERS);

    // ==============================================================
    // 子类差异点
    // ==============================================================

    /** 规则注册 id（同时是 /ec c 后的命令字面量）。 */
    protected abstract String commandName();

    protected abstract String setKey();

    protected abstract String resetKey();

    protected abstract String showHeaderKey();

    protected abstract String showEmptyKey();

    protected abstract String invalidValueKey();

    /** value 位置的补全（补全原版对应的值）。 */
    protected abstract SuggestionProvider<CommandSourceStack> valueSuggester();

    protected abstract void put(Identifier id, float value);

    protected abstract void reset();

    protected abstract Map<Identifier, Float> snapshot();

    /** 配置变化后的额外动作（如客户端同步）；默认空实现。 */
    protected void afterChange() {
    }

    // ==============================================================
    // 命令树
    // ==============================================================

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal(commandName())
                .then(Commands.literal("reset")
                        .requires(OWNER_REQUIREMENT)
                        .executes(this::resetCmd))
                .then(Commands.literal("show")
                        .requires(OWNER_REQUIREMENT)
                        .executes(this::showCmd))
                .then(Commands.argument("block", IdentifierArgument.id())
                        .requires(OWNER_REQUIREMENT)
                        .suggests(BlockSuggestionData.ALL_BLOCK_ID_SUGGESTER)
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .suggests(valueSuggester())
                                .executes(this::setCmd)));
    }

    private int setCmd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        Identifier block = IdentifierArgument.getId(ctx, "block");
        String raw = StringArgumentType.getString(ctx, "value");

        // 解析数值：支持 "1.5" 与 "方块id=1.5" 两种写法
        float resistance;
        try {
            resistance = parseResistance(raw);
        } catch (NumberFormatException e) {
            sendOrBroadcast(player, Component.translatable(invalidValueKey(), raw.trim()));
            return 0;
        }

        // "方块id=抗性" 形式里的方块 id 覆盖前面的 block 参数
        Identifier explicit = parseExplicitBlock(raw);
        if (explicit != null) {
            block = explicit;
        }

        put(block, resistance);
        afterChange();

        sendOrBroadcast(player, Component.translatable(setKey(), block.toString(), resistance));
        return 1;
    }

    private int resetCmd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        reset();
        afterChange();

        sendOrBroadcast(player, Component.translatable(resetKey()));
        return 1;
    }

    private int showCmd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        Map<Identifier, Float> entries = snapshot();

        MutableComponent msg = Component.translatable(showHeaderKey());
        if (entries.isEmpty()) {
            msg.append("\n").append(Component.translatable(showEmptyKey()));
        } else {
            entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)))
                    .forEach(e -> msg.append("\n")
                            .append(Component.literal("  " + e.getKey() + " = " + e.getValue())));
        }

        sendOrBroadcast(player, msg);
        return 1;
    }

    // ==============================================================
    // 解析工具
    // ==============================================================

    /** 解析抗性数值（去掉可选的 {@code 方块id=} 前缀），非法 / 负数 / NaN / 无穷大抛 {@link NumberFormatException}。 */
    private static float parseResistance(String raw) {
        String s = raw == null ? "" : raw.trim();
        int eq = s.indexOf('=');
        String number = eq >= 0 ? s.substring(eq + 1).trim() : s;
        float value = Float.parseFloat(number);
        if (!Float.isFinite(value) || value < 0f) {
            throw new NumberFormatException("invalid resistance: " + number);
        }
        return value;
    }

    /** 解析 {@code 方块id=抗性} 形式里的方块 id；无等号或非法 id 返回 null。 */
    private static Identifier parseExplicitBlock(String raw) {
        String s = raw == null ? "" : raw.trim();
        int eq = s.indexOf('=');
        if (eq < 0) return null;
        return Identifier.tryParse(s.substring(0, eq).trim());
    }
}
