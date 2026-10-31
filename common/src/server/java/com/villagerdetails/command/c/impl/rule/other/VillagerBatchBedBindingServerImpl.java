package com.villagerdetails.command.c.impl.rule.other;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.villagerdetails.cache.impl.BBSectionStateCache;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.handler.binding.entity.villager.VillagerBindServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * {@code /ec c VillagerBatchBedBinding} —— 把当前选区内的床批量绑定给村民（{@code batchBedBinding}）。
 *
 * <p><b>字面量与规则注册名必须一致</b>：{@code RuleType.VILLAGER_BATCH_BED_RESET} 的
 * {@code registerName} 是 {@code VillagerBatchBedBinding}，所以这里的字面量也用它。
 * 以前这里写的是 {@code batchBedBinding}，导致 {@code /ec VillagerBatchBedBinding ...} 与
 * {@code /ec c batchBedBinding} 成了两个名字（同物异名），启动期自检现在会对这种情况打 WARN。
 */
public class VillagerBatchBedBindingServerImpl implements RegisterServer {

    public static final VillagerBatchBedBindingServerImpl INSTANCE = new VillagerBatchBedBindingServerImpl();

    private VillagerBatchBedBindingServerImpl() {
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("VillagerBatchBedBinding")
                .executes(VillagerBatchBedBindingServerImpl::bindAreaBeds);
    }

    private static int bindAreaBeds(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;

        if (!BBSectionStateCache.hasSelection(player)) {
            sendOrBroadcast(player, Component.translatable("command.entity_binder.batch_bed_binding.no_selection"));
            return 0;
        }

        BlockPos pos1 = BBSectionStateCache.getPos1(player);
        BlockPos pos2 = BBSectionStateCache.getPos2(player);
        int count = VillagerBindServer.bindAreaBeds(player.level(), player, pos1, pos2);

        sendOrBroadcast(player, Component.translatable("command.entity_binder.batch_bed_binding.success", count));
        return 1;
    }
}