package com.villagerdetails.mixin.block.endPortalGravityBlock;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.section.item.EndPortalGravityBlockConfig;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockBehaviour.class)
public abstract class EndPortalGravityMixin {

    // 1. 方块放置时，像原版沙子一样，向游戏注册一个“计划刻”
    @Inject(method = "onPlace", at = @At("TAIL"))
    private void onBlockPlaced(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston, CallbackInfo ci) {
        scheduleFallCheck(level, pos, state);
    }

    // 2. 邻居更新时，同样注册计划刻
    @Inject(method = "neighborChanged", at = @At("HEAD"))
    private void onNeighborChanged(BlockState state, Level level, BlockPos pos, Block block, Orientation orientation, boolean movedByPiston, CallbackInfo ci) {
        scheduleFallCheck(level, pos, state);
    }

    // 3. 计划刻到期，原版游戏会自动调用这个 tick 方法
    @Inject(method = "tick", at = @At("HEAD"))
    private void onScheduledTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        // 再次确认条件，如果满足就掉落
        if (shouldFall(level, pos, state)) {
            FallingBlockEntity.fall(level, pos, state);
        }
    }

    // 注册计划刻的辅助方法
    @Unique
    private void scheduleFallCheck(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) return;

        // 规则及白名单检测
        if (!RuleCache.isEnabled(RuleType.ENDER_PORTAL_GRAVITY_BLOCK)) return;
        if (state.getBlock() instanceof FallingBlock) return; // 原版沙子自己会处理
        if (!EndPortalGravityBlockConfig.INSTANCE.contains(state.getBlock())) return;

        // 如果 5x5x5 范围内有末地门，向服务端注册一个 2 tick 后的计划刻（和沙子默认延迟一致）
        if (isNearPortal(level, pos)) {
            serverLevel.scheduleTick(pos, state.getBlock(), 2);
        }
    }

    // 实际判断是否掉落的逻辑
    @Unique
    private boolean shouldFall(ServerLevel level, BlockPos pos, BlockState state) {
        if (!RuleCache.isEnabled(RuleType.ENDER_PORTAL_GRAVITY_BLOCK)) return false;
        if (state.getBlock() instanceof FallingBlock) return false;
        if (!EndPortalGravityBlockConfig.INSTANCE.contains(state.getBlock())) return false;

        // 检查下方是否是空气
        if (!level.getBlockState(pos.below()).isAir()) return false;

        return isNearPortal(level, pos);
    }

    // 5x5x5 范围检测
    @Unique
    private boolean isNearPortal(Level level, BlockPos pos) {
        for (BlockPos checkPos : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
            if (level.getBlockState(checkPos).is(Blocks.END_PORTAL)) {
                return true;
            }
        }
        return false;
    }
}