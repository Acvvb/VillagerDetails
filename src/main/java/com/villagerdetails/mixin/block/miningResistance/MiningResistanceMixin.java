package com.villagerdetails.mixin.block.miningResistance;

import com.villagerdetails.handler.block.collectable.CollectableHandler;
import com.villagerdetails.handler.block.miningResistance.MiningResistanceHandler;
import com.villagerdetails.network.BlockRuleSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 覆盖方块的挖掘抗性（破坏时间），在带玩家参数的 {@code getDestroyProgress} 上处理，
 * 从而可以针对「未安装本 mod 的客户端玩家」回退原版逻辑。
 * <p>
 * 优先级：挖掘抗性规则的自定义值 &gt; 可采集规则（让原版不可破坏的方块变为可破坏）&gt; 原版。
 */
@Mixin(BlockBehaviour.class)
public abstract class MiningResistanceMixin {

    @Inject(method = "getDestroyProgress", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$overrideDestroyProgress(BlockState state, Player player,
                                                         BlockGetter level, BlockPos pos,
                                                         CallbackInfoReturnable<Float> cir) {
        // 未安装本 mod 的客户端玩家 → 原版逻辑（对该玩家不生效）
        if (player instanceof ServerPlayer sp && !ServerPlayNetworking.canSend(sp, BlockRuleSyncPayload.TYPE)) {
            return;
        }

        Float override = MiningResistanceHandler.getOverride(state.getBlock());
        if (override == null) {
            override = CollectableHandler.getBreakableOverride(state.getBlock());
        }
        if (override != null) {
            int modifier = player.hasCorrectToolForDrops(state) ? 30 : 100;
            cir.setReturnValue(player.getDestroySpeed(state) / override / (float) modifier);
        }
    }
}
