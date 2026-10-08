package com.villagerdetails.command.c.impl.rule;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.villagerdetails.cache.BBSectionStateCache;
import com.villagerdetails.cache.PortalSelectionCache;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.handler.binding.block.portalbinding.PortalBindingService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * 传送门绑定指令。
 * <p>
 * 用法：
 * /c PortalBinding set    → 把当前维度的拴绳选区写入缓存
 * /c PortalBinding bind   → 把主世界 + 地狱两个缓存正式绑定
 */
public class PortalBindingServerImpl implements RegisterServer {

    public static final PortalBindingServerImpl INSTANCE = new PortalBindingServerImpl();

    private PortalBindingServerImpl() {
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("PortalBinding")
                .then(Commands.literal("set").executes(this::onSet))
                .then(Commands.literal("bind").executes(this::onBind));
    }

    /* ---------- set：把当前维度选区写进缓存 ---------- */

    private int onSet(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        ServerPlayer player = getPlayerOrFail(source);
        if (player == null) return 0;

        ServerLevel level = player.level();
        ResourceKey<Level> dim = level.dimension();
        boolean inOverworld = dim.equals(Level.OVERWORLD);
        boolean inNether = dim.equals(Level.NETHER);
        if (!inOverworld && !inNether) {
            source.sendFailure(Component.translatable("command.portal_binding.failure.unsupported_dimension"));
            return 0;
        }

        BlockPos pos1 = BBSectionStateCache.getPos1(player);
        BlockPos pos2 = BBSectionStateCache.getPos2(player);
        if (pos1 == null || pos2 == null) {
            source.sendFailure(Component.translatable("command.portal_binding.failure.no_selection"));
            return 0;
        }

        if (inOverworld) {
            PortalSelectionCache.setOverworld(player.getUUID(), pos1, pos2);
        } else {
            PortalSelectionCache.setNether(player.getUUID(), pos1, pos2);
        }

        String dimName = dim.identifier().toString();
        source.sendSuccess(
                () -> Component.translatable("command.portal_binding.set_success",
                        dimName,
                        String.valueOf(pos1.getX()), String.valueOf(pos1.getY()), String.valueOf(pos1.getZ()),
                        String.valueOf(pos2.getX()), String.valueOf(pos2.getY()), String.valueOf(pos2.getZ())),
                false
        );
        return 1;
    }

    /* ---------- bind：从缓存取出两侧并正式绑定 ---------- */

    private int onBind(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        ServerPlayer player = getPlayerOrFail(source);
        if (player == null) return 0;

        PortalSelectionCache.Region ow = PortalSelectionCache.getOverworld(player.getUUID());
        PortalSelectionCache.Region ne = PortalSelectionCache.getNether(player.getUUID());

        if (ow == null || ne == null) {
            source.sendFailure(Component.translatable("command.portal_binding.failure.incomplete"));
            return 0;
        }

        PortalBindingService.bind(
                ow.pos1(), ow.pos2(),
                ne.pos1(), ne.pos2()
        );

        PortalSelectionCache.clear(player.getUUID());

        source.sendSuccess(
                () -> Component.translatable("command.portal_binding.bind_success"),
                true
        );
        return 1;
    }

    /* ---------- 工具方法 ---------- */

    private ServerPlayer getPlayerOrFail(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("command.portal_binding.failure.not_player"));
            return null;
        }
    }
}