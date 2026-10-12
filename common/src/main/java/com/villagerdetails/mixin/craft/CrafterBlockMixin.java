package com.villagerdetails.mixin.craft;

import com.villagerdetails.handler.craft.BoxCraftPlan;
import com.villagerdetails.handler.craft.BoxCraftingHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 盒子合成：合成器（Crafter）。
 * <p>
 * Crafter 没有菜单和结果槽，流程是红石脉冲 → CrafterBlock.dispenseFrom()。
 * 直接在 HEAD 处接管，第一次调用时就执行并取消原版两阶段动画。
 * <p>
 * CrafterBlockEntity 本身实现了 CraftingContainer，可直接传给 computePlan。
 */
@Mixin(CrafterBlock.class)
public abstract class CrafterBlockMixin {

    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$boxCraftInCrafter(
            BlockState state, ServerLevel level, BlockPos pos, CallbackInfo ci) {

        if (!BoxCraftingHandler.isEnabled()) return;

        var be = level.getBlockEntity(pos);
        if (!(be instanceof CrafterBlockEntity crafter)) return;

        BoxCraftPlan plan = BoxCraftingHandler.computePlan(level, crafter);
        if (plan == null) return;

        // 朝向：Crafter 的属性是 ORIENTATION（FrontAndTop），front 即输出方向
        Direction facing = state.hasProperty(BlockStateProperties.ORIENTATION)
                ? state.getValue(BlockStateProperties.ORIENTATION).front()
                : Direction.NORTH;
        BlockPos targetPos = pos.relative(facing);

        // ★ 新签名：去掉了 crafterPos
        plan.executeForCrafter(level, targetPos, crafter);

        crafter.setChanged();
        level.sendBlockUpdated(pos, state, state, 3);

        ci.cancel();
    }
}