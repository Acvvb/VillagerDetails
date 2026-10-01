package com.villagerdetails.command.c.impl.rule;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.villagerdetails.cache.BBSectionStateCache;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.handler.binding.entity.villager.VillagerBindServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

public class VillagerBatchBedBindingServerImpl implements RegisterServer {

    public static final VillagerBatchBedBindingServerImpl INSTANCE = new VillagerBatchBedBindingServerImpl();

    private VillagerBatchBedBindingServerImpl() {
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("batchBedBinding")
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