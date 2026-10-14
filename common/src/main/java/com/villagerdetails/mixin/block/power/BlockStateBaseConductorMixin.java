package com.villagerdetails.mixin.block.power;

import com.villagerdetails.config.impl.section.item.BlockStrongPowerConfig;
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
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseConductorMixin {

    @Inject(method = "isRedstoneConductor", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$isRedstoneConductor(
            BlockGetter level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockBehaviour.BlockStateBase self = (BlockBehaviour.BlockStateBase) (Object) this;
        if (BlockStrongPowerConfig.INSTANCE.contains(self.getBlock())) {
            cir.setReturnValue(true);
        }
    }
}