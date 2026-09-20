package com.villagerdetails.event;

import com.villagerdetails.cache.SelectionState;
import com.villagerdetails.event.type.BindingType;
import com.villagerdetails.util.BindingToolUtils;
import com.villagerdetails.util.SendMessengerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.UUID;

public class UseBlockListener {

    public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.PASS;

        UUID playerUuid = player.getUUID();

        BlockPos clickedPos = hitResult.getBlockPos();

        ServerPlayer operator = (ServerPlayer) player;
        SelectionState.setSelectedBlock(playerUuid,clickedPos);

        BindingType type = BindingType.isHoldingAnyTool(player,hand,null);
        if (type == null) return InteractionResult.PASS;

        if (SelectionState.isEnd(playerUuid)){
            if (BindingToolUtils.chooseUtil(level, player, hand)) return InteractionResult.SUCCESS;
        }else {
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable("msg.block.select.success",
                            level.getBlockState(clickedPos).getBlock().getName(),
                            "%d, %d, %d".formatted(clickedPos.getX(), clickedPos.getY(), clickedPos.getZ())
                    ));
        }
        return InteractionResult.PASS;
    }

}
