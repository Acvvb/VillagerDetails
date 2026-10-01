package com.villagerdetails.command.c.impl.rule;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.villagerdetails.command.c.impl.suggestion.BlockSuggestionData;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.config.impl.BlockMiningResistanceConfig;
import com.villagerdetails.network.BlockRuleSyncHandler;
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
 * /ec c BlockMiningResistance &lt;方块id&gt; &lt;抗性&gt; —— 设置某个方块的破坏抗性。
 * /ec c BlockMiningResistance reset —— 清空所有配置，恢复原版。
 */
public final class BlockMiningResistanceServerImpl implements RegisterServer {

    public static final BlockMiningResistanceServerImpl INSTANCE = new BlockMiningResistanceServerImpl();

    private BlockMiningResistanceServerImpl() {
    }

    private static final String K_SET = "command.block.mining_resistance.set";
    private static final String K_RESET = "command.block.mining_resistance.reset";
    private static final String K_SHOW_HEADER = "command.block.mining_resistance.show.header";
    private static final String K_SHOW_EMPTY = "command.block.mining_resistance.show.empty";

    /** 强制最高权限（OWNERS）才能调用。 */
    private static final Predicate<CommandSourceStack> OWNER_REQUIREMENT =
            src -> src.checkPermission(PERM_BASE, PermissionLevel.OWNERS);

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("BlockMiningResistance")
                .then(Commands.literal("reset")
                        .requires(OWNER_REQUIREMENT)
                        .executes(BlockMiningResistanceServerImpl::reset))
                .then(Commands.literal("show")
                        .requires(OWNER_REQUIREMENT)
                        .executes(BlockMiningResistanceServerImpl::show))
                .then(Commands.argument("block", IdentifierArgument.id())
                        .requires(OWNER_REQUIREMENT)
                        .suggests(BlockSuggestionData.ALL_BLOCK_ID_SUGGESTER)
                        .then(Commands.argument("resistance", FloatArgumentType.floatArg(0))
                                .executes(BlockMiningResistanceServerImpl::set)));
    }

    private static int set(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        Identifier block = IdentifierArgument.getId(ctx, "block");
        float resistance = FloatArgumentType.getFloat(ctx, "resistance");

        BlockMiningResistanceConfig.put(block, resistance);
        BlockRuleSyncHandler.broadcast();

        sendOrBroadcast(player, Component.translatable(K_SET, block.toString(), resistance));
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        BlockMiningResistanceConfig.reset();
        BlockRuleSyncHandler.broadcast();

        sendOrBroadcast(player, Component.translatable(K_RESET));
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        Map<Identifier, Float> entries = BlockMiningResistanceConfig.snapshot();

        MutableComponent msg = Component.translatable(K_SHOW_HEADER);
        if (entries.isEmpty()) {
            msg.append("\n").append(Component.translatable(K_SHOW_EMPTY));
        } else {
            entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)))
                    .forEach(e -> msg.append("\n")
                            .append(Component.literal("  " + e.getKey() + " = " + e.getValue())));
        }

        sendOrBroadcast(player, msg);
        return 1;
    }
}
