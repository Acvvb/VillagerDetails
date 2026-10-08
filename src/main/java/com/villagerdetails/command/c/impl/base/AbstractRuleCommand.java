package com.villagerdetails.command.c.impl.base;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.network.sync.BlockRuleSyncChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static com.villagerdetails.command.CommandConstants.PERM_BASE;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * 「逐条规则」命令的抽象基类：
 * &lt;id&gt; true|false —— 加进 / 移出名单，false 等于删除
 * reset —— 清空配置
 * show —— 显示名单
 *
 * <p>子类只需描述自己的差异：命令名、参数名、suggestion、配置增删/重置/快照、翻译 key。
 */
public abstract class AbstractRuleCommand implements RegisterServer {

    /**
     * 命令层要求最高权限（OWNERS）。
     */
    protected static final Predicate<CommandSourceStack> OWNER_REQUIREMENT =
            src -> src.checkPermission(PERM_BASE, PermissionLevel.OWNERS);

    /**
     * value 参数的参数名，所有规则命令统一。
     */
    protected static final String VALUE_ARG = "value";

    // ================= 子类必须提供 =================

    /**
     * 命令字面量，例如 "NoSqueeze"、"BlockCollectable"。
     */
    protected abstract String literalName();

    /**
     * id 参数的参数名，例如 "entity"、"block"。
     */
    protected abstract String argName();

    /**
     * id 参数的补全器。
     */
    protected abstract SuggestionProvider<CommandSourceStack> idSuggester();

    /**
     * value 参数的补全器；返回 null 表示不补全。
     */
    protected SuggestionProvider<CommandSourceStack> valueSuggester() {
        return null;
    }

    protected abstract void add(Identifier id);

    protected abstract void remove(Identifier id);

    protected abstract void resetConfig();

    protected abstract List<Identifier> snapshot();

    protected abstract String kSet();

    protected abstract String kRemove();

    protected abstract String kReset();

    protected abstract String kShowHeader();

    protected abstract String kShowEmpty();

    protected abstract String kInvalidValue();

    // ================= 通用实现 =================

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        RequiredArgumentBuilder<CommandSourceStack, String> valueArg =
                Commands.argument(VALUE_ARG, StringArgumentType.word());
        SuggestionProvider<CommandSourceStack> valueSuggester = valueSuggester();
        if (valueSuggester != null) {
            valueArg.suggests(valueSuggester);
        }
        valueArg.executes(this::set);

        return Commands.literal(literalName())
                .then(Commands.literal("reset")
                        .requires(OWNER_REQUIREMENT)
                        .executes(this::reset))
                .then(Commands.literal("show")
                        .requires(OWNER_REQUIREMENT)
                        .executes(this::show))
                .then(Commands.argument(argName(), IdentifierArgument.id())
                        .requires(OWNER_REQUIREMENT)
                        .suggests(idSuggester())
                        .then(valueArg));
    }

    protected int set(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        Identifier id = IdentifierArgument.getId(ctx, argName());
        String value = StringArgumentType.getString(ctx, VALUE_ARG).trim().toLowerCase(Locale.ROOT);

        boolean enable;
        if ("true".equals(value)) {
            enable = true;
        } else if ("false".equals(value)) {
            enable = false;
        } else {
            sendOrBroadcast(player, Component.translatable(kInvalidValue(), value));
            return 0;
        }

        if (enable) {
            add(id);
        } else {
            remove(id);
        }
        BlockRuleSyncChannel.INSTANCE.broadcastAll();

        sendOrBroadcast(player, Component.translatable(enable ? kSet() : kRemove(), id.toString()));
        return 1;
    }

    protected int reset(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        resetConfig();
        BlockRuleSyncChannel.INSTANCE.broadcastAll();

        sendOrBroadcast(player, Component.translatable(kReset()));
        return 1;
    }

    protected int show(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        List<Identifier> ids = snapshot();

        MutableComponent msg = Component.translatable(kShowHeader());
        if (ids.isEmpty()) {
            msg.append("\n").append(Component.translatable(kShowEmpty()));
        } else {
            for (Identifier id : ids) {
                msg.append("\n").append(Component.literal("  " + id));
            }
        }

        sendOrBroadcast(player, msg);
        return 1;
    }
}