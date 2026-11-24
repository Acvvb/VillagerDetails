package com.villagerdetails.mixin.block.pistonPush;

import com.villagerdetails.handler.block.pistonPush.CustomPistonPushBlockHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 活塞推动判断：配置过的方块把原版结果<b>取反</b>。
 *
 * <p>注入点是 {@code @At("RETURN")}（不是 HEAD）：<b>先让原版把这一格算完</b>，
 * 再拿它的真实结果翻转。这样「原版能不能推」永远由原版自己决定 ——
 * 世界边界、上下限、方向、能不能破坏、有没有方块实体这些上下文条件都不用我们抄一遍。
 *
 * <p>语义上名单 = 「与原版不同」的方块（见 {@link CustomPistonPushBlockHandler}）：
 * 原版能推的变成不能推，原版不能推的变成能推，名单外一个字节都不动。
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {

    @Inject(
            method = "isPushable(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;ZLnet/minecraft/core/Direction;)Z",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void villagerdetails$invertIsPushable(
            BlockState state,
            Level level,
            BlockPos pos,
            Direction direction,
            boolean allowDestroyable,
            Direction connectionDirection,
            CallbackInfoReturnable<Boolean> cir
    ) {
        // null = 名单外 → 不覆盖，原版算出来是什么就是什么
        if (CustomPistonPushBlockHandler.isConfigured(state.getBlock()) != null) {
            cir.setReturnValue(!cir.getReturnValue());
        }
    }
}
