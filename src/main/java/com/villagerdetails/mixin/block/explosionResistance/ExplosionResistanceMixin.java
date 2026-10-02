package com.villagerdetails.mixin.block.explosionResistance;

import com.villagerdetails.handler.block.explosionResistance.ExplosionResistanceHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * 覆盖方块的爆炸抗性：在爆炸伤害计算读取方块抗性时，把配置过的方块替换为自定义抗性。
 * <p>
 * 爆炸对方块的破坏只发生在服务端（{@link net.minecraft.world.level.ServerExplosion}），
 * 客户端无需同步，因此这里不做「未安装本 mod 客户端」的回退处理。
 */
@Mixin(ExplosionDamageCalculator.class)
public abstract class ExplosionResistanceMixin {

    @Inject(method = "getBlockExplosionResistance", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$overrideExplosionResistance(Explosion explosion, BlockGetter level, BlockPos pos,
                                                             BlockState state, FluidState fluidState,
                                                             CallbackInfoReturnable<Optional<Float>> cir) {
        // 空气（无流体）走原版逻辑，避免凭空给空气增加抗性
        if (state.isAir()) return;

        Float override = ExplosionResistanceHandler.getOverride(state.getBlock());
        if (override != null) {
            cir.setReturnValue(Optional.of(override));
        }
    }
}
