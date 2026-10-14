package com.villagerdetails.mixin.block.power;

import com.villagerdetails.handler.block.power.StrongPowerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让配置的方块变成红石导体，表现得像泥土/石头一样：
 * 可以被红石火把、中继器、比较器充能，
 * 并把充能状态传递给相邻的红石线。
 *
 * <p>「算不算导体」的判定在 {@link StrongPowerHandler}：<b>规则开着</b>且方块在名单里才算，
 * 关掉规则就等于回到原版行为。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseConductorMixin {

    @Inject(method = "isRedstoneConductor", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$isRedstoneConductor(
            BlockGetter level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockBehaviour.BlockStateBase self = (BlockBehaviour.BlockStateBase) (Object) this;
        if (StrongPowerHandler.isConductor(self.getBlock())) {
            cir.setReturnValue(true);
        }
    }
}
