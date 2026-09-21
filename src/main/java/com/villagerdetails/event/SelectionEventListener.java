package com.villagerdetails.event;

import com.villagerdetails.selection.SelectionInteractionHandler;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.HitResult;

/**
 * 选区事件注册器
 */
public class SelectionEventListener {


    public static void register() {

        // 左键破坏方块 → 拦截并设置 pos1
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, _, _) -> {
            if (player instanceof ServerPlayer sp) return !SelectionInteractionHandler.onLeftClickBlock(sp, pos);
            return true; // 正常破坏
        });

        // 右键使用方块 → 拦截并设置 pos2
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (hitResult.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;
            if (SelectionInteractionHandler.onRightClickBlock(sp, hitResult.getBlockPos())) {
                return InteractionResult.SUCCESS; // 取消原右键行为
            }
            return InteractionResult.PASS;
        });
    }
}