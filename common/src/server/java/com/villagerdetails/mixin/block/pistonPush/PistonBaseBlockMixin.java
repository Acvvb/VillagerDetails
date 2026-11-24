package com.villagerdetails.mixin.block.pistonPush;

import com.villagerdetails.handler.block.pistonPush.CustomPistonPushBlockHandler;
import com.villagerdetails.handler.block.pistonPush.CustomStickyPistonPullBlockHandler;
import com.villagerdetails.handler.block.pistonPush.PistonMoveScope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 活塞推动 / 黏回判断：配置过的方块把原版结果<b>取反</b>。
 *
 * <p>注入点是 {@code @At("RETURN")}（不是 HEAD）：<b>先让原版把这一格算完</b>，
 * 再拿它的真实结果翻转。这样「原版能不能挪这一块」永远由原版自己决定 ——
 * 世界边界、上下限、方向、能不能破坏、有没有方块实体这些上下文条件都不用我们抄一遍。
 *
 * <p><b>原版推动（伸出）和黏回（缩回）共用这一个方法</b>，所以这里按
 * {@link PistonMoveScope}（由 {@link PistonStructureResolverMixin} 在每次结构扫描开头写入）分流：
 * <ul>
 *     <li>伸出 → 「活塞推动控制」（{@link CustomPistonPushBlockHandler}）；</li>
 *     <li>缩回 → 「粘性活塞黏回控制」（{@link CustomStickyPistonPullBlockHandler}）。</li>
 * </ul>
 * 两条规则各自独立：只配了推动的方块只影响推动，缩回仍走原版（反之亦然）。
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
        Block block = state.getBlock();
        // null = 对应的名单里没有这个方块 → 不覆盖，原版算出来是什么就是什么
        Boolean override = PistonMoveScope.isExtending()
                ? CustomPistonPushBlockHandler.isConfigured(block)
                : CustomStickyPistonPullBlockHandler.isConfigured(block);
        if (override != null) {
            cir.setReturnValue(!cir.getReturnValue());
        }
    }
}
