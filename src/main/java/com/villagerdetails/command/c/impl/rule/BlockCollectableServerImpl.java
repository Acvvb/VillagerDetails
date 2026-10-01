package com.villagerdetails.command.c.impl.rule;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.villagerdetails.command.c.impl.suggestion.BlockSuggestionData;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.config.impl.BlockCollectableConfig;
import com.villagerdetails.network.BlockRuleSyncHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.List;
import java.util.function.Predicate;

import static com.villagerdetails.command.CommandConstants.PERM_BASE;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * /ec c BlockCollectable &lt;方块id&gt; true|false —— 设置/取消某个方块的「可采集」。
 * /ec c BlockCollectable reset —— 清空所有配置，恢复原版。
 */
public final class BlockCollectableServerImpl implements RegisterServer {

    public static final BlockCollectableServerImpl INSTANCE = new BlockCollectableServerImpl();

    private BlockCollectableServerImpl() {
    }

    private static final String K_SET = "command.block.collectable.set";
    private static final String K_REMOVE = "command.block.collectable.remove";
    private static final String K_RESET = "command.block.collectable.reset";
    private static final String K_SHOW_HEADER = "command.block.collectable.show.header";
    private static final String K_SHOW_EMPTY = "command.block.collectable.show.empty";

    /** 强制最高权限（OWNERS）才能调用。 */
    private static final Predicate<CommandSourceStack> OWNER_REQUIREMENT =
            src -> src.checkPermission(PERM_BASE, PermissionLevel.OWNERS);

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("BlockCollectable")
                .then(Commands.literal("reset")
                        .requires(OWNER_REQUIREMENT)
                        .executes(BlockCollectableServerImpl::reset))
                .then(Commands.literal("show")
                        .requires(OWNER_REQUIREMENT)
                        .executes(BlockCollectableServerImpl::show))
                .then(Commands.argument("block", IdentifierArgument.id())
                        .requires(OWNER_REQUIREMENT)
                        .suggests(BlockSuggestionData.ALL_BLOCK_ID_SUGGESTER)
                        .then(Commands.literal("true")
                                .executes(ctx -> set(ctx, true)))
                        .then(Commands.literal("false")
                                .executes(ctx -> set(ctx, false))));
    }

    private static int set(CommandContext<CommandSourceStack> ctx, boolean value) {
        ServerPlayer player = ctx.getSource().getPlayer();
        Identifier block = IdentifierArgument.getId(ctx, "block");

        if (value) {
            BlockCollectableConfig.add(block);
        } else {
            BlockCollectableConfig.remove(block);
        }
        BlockRuleSyncHandler.broadcast();

        sendOrBroadcast(player, Component.translatable(value ? K_SET : K_REMOVE, block.toString()));
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        BlockCollectableConfig.reset();
        BlockRuleSyncHandler.broadcast();

        sendOrBroadcast(player, Component.translatable(K_RESET));
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        List<Identifier> ids = BlockCollectableConfig.snapshot();

        MutableComponent msg = Component.translatable(K_SHOW_HEADER);
        if (ids.isEmpty()) {
            msg.append("\n").append(Component.translatable(K_SHOW_EMPTY));
        } else {
            for (Identifier id : ids) {
                msg.append("\n").append(Component.literal("  " + id));
            }
        }

        sendOrBroadcast(player, msg);
        return 1;
    }
}
