package com.villagerdetails.client.mixin.block.pistonPush;

import com.villagerdetails.client.handler.block.pistonPush.PistonMoveScope;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 客户端侧的「这次活塞结构扫描是伸出还是缩回」，和服务端
 * {@code com.villagerdetails.mixin.block.pistonPush.PistonStructureResolverMixin} 对称。
 *
 * <p>客户端平时不跑活塞结构扫描（那在服务端），这个 mixin 只在「同进程真的有结构扫描」时生效
 * （单人 / 集成服），作用是让客户端那份 {@code isPushable} 注入能用和主端一样的分流方式。
 */
@Mixin(PistonStructureResolver.class)
public abstract class ClientPistonStructureResolverMixin {

    @Shadow
    @Final
    private boolean extending;

    @Inject(method = "resolve", at = @At("HEAD"))
    private void villagerdetails$markMoveDirection(CallbackInfoReturnable<Boolean> cir) {
        PistonMoveScope.set(this.extending);
    }
}
