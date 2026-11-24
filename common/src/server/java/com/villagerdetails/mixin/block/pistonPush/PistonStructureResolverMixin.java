package com.villagerdetails.mixin.block.pistonPush;

import com.villagerdetails.handler.block.pistonPush.PistonMoveScope;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在每次活塞结构扫描开头记下「这次是伸出还是缩回」，供：
 * <ul>
 *     <li>{@link com.villagerdetails.mixin.block.pistonPush.PistonBaseBlockMixin} —— 决定用
 *         「活塞推动」还是「粘性活塞黏回」那条规则；</li>
 *     <li>服务端玩家退出等场合不需要恢复：扫描只会在服务端主线程跑，下一次扫描开头就会覆盖。</li>
 * </ul>
 *
 * <p>为什么要绕这一层：原版推动和黏回<b>共用</b> {@code PistonBaseBlock#isPushable}，
 * 函数参数里没有「方向」可以分流，而 {@code PistonStructureResolver} 自己带着
 * {@code extending} 字段 —— 这是唯一可靠的信息源。
 */
@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {

    @Shadow
    @Final
    private boolean extending;

    @Inject(method = "resolve", at = @At("HEAD"))
    private void villagerdetails$markMoveDirection(CallbackInfoReturnable<Boolean> cir) {
        PistonMoveScope.set(this.extending);
    }
}
