package com.villagerdetails.mixin.block.pistonPush;

import com.villagerdetails.handler.block.pistonPush.CustomPistonPushBlockHandler;
import com.villagerdetails.util.piston.PistonReactionCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「活塞搬运控制」：名单里的方块把原版对它的搬运判定<b>取反</b>。
 *
 * <p>原版一次「伸出」或「缩回」要走<b>两道</b>闸，同一条规则的名单两处都要管：
 * <ol>
 *     <li>{@code PistonBaseBlock#isPushable} —— 原版推、拉、以及「粘性方块能不能带着它走」
 *         全都先问这一个方法（搬不动的方块既推不动、也黏不回来、更不会被蜂蜜块 / 史莱姆块带走）。
 *         注入点是 {@code @At("RETURN")}（不是 HEAD）：<b>先让原版把这一格算完</b>，再拿它的
 *         真实结果翻转。这样「原版能不能搬这一块」永远由原版自己决定 —— 世界边界、上下限、
 *         方向、能不能破坏、有没有方块实体这些上下文条件都不用我们抄一遍。</li>
 *     <li>{@code PistonBaseBlock#triggerEvent} 缩回分支里的 {@code PushReaction} 闸 ——
 *         原版要求「要黏回来的那一块」的反应等于「能推能拉」（26.2 {@code NORMAL} /
 *         26.3 {@code PUSH_PULL}），<b>否则直接跳过黏回、只把活塞头收回来</b>。
 *         这道闸和 {@code isPushable} 各判一半：{@code PUSH_ONLY}（推得动但黏不住）这类方块
 *         就是靠它拦下的，所以<b>光把 {@code isPushable} 取反不够</b>。
 *         名单内的方块在这里按「能推能拉」放行，让第 1 道闸说了算。</li>
 * </ol>
 *
 * <p>不需要再按「伸出 / 缩回」分流：三件事共用一个入口，名单也只有一份，
 * 所以同一个方块在两个方向上取反的结果天然一致。
 *
 * <p>客户端那份镜像见
 * {@code com.villagerdetails.client.mixin.block.pistonPush.ClientBlockStateBasePistonPushMixin}。
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
        // null = 名单里没有这个方块 → 不覆盖，原版算出来是什么就是什么
        Boolean override = CustomPistonPushBlockHandler.isConfigured(state.getBlock());
        if (override != null) {
            cir.setReturnValue(!cir.getReturnValue());
        }
    }

    /**
     * 缩回分支的第二道闸：名单里的方块一律按「能推能拉」放行，让上面那条注入说了算。
     *
     * <p>只对 {@code triggerEvent} 生效（原版在这个方法里就这一处 {@code getPistonPushReaction}，
     * 且只在缩回分支上），注入点用 {@code @Redirect} 才能改掉原版拿去比较的那个值。
     */
    @Redirect(
            method = "triggerEvent",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getPistonPushReaction()Lnet/minecraft/world/level/material/PushReaction;"
            )
    )
    private PushReaction villagerdetails$movableReaction(BlockState state) {
        PushReaction vanilla = state.getPistonPushReaction();
        if (CustomPistonPushBlockHandler.isConfigured(state.getBlock()) == null) {
            return vanilla;
        }
        return PistonReactionCompat.movable();
    }
}
