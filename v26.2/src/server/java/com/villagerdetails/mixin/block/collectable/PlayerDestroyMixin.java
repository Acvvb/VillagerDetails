package com.villagerdetails.mixin.block.collectable;

import com.villagerdetails.handler.block.BlockRuleContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * 在玩家破坏方块（{@code playerDestroy}）这条调用链里建立玩家上下文，
 * 让 {@code getDrops} 判断当前破坏者是否是「未安装本 mod 的客户端玩家」。
 *
 * <p>26.3 把 {@code Block#playerDestroy} 的入参从 {@code (Level, Player)} 收窄成
 * {@code (ServerLevel, ServerPlayer)}。{@code @Inject} 回调要求逐参数精确相等（{@code @Coerce}
 * 只支持基本类型），所以这里按版本写两套签名，逻辑统一丢给 {@link #villagerdetails$begin(Player)}。
 */
@Mixin(Block.class)
public abstract class PlayerDestroyMixin {

    @Inject(method = "playerDestroy", at = @At("HEAD"))
    private void villagerdetails$beginDestroyContext(



            Level level, Player player,

            BlockPos pos, BlockState state, BlockEntity blockEntity, ItemStack destroyedWith, CallbackInfo ci) {
        villagerdetails$begin(player);
    }

    @Inject(method = "playerDestroy", at = @At("RETURN"))
    private void villagerdetails$endDestroyContext(



            Level level, Player player,

            BlockPos pos, BlockState state, BlockEntity blockEntity, ItemStack destroyedWith, CallbackInfo ci) {
        BlockRuleContext.end();
    }

    /** 只有服务端玩家才建立破坏者上下文，客户端玩家/非玩家实体传 null */
    @Unique
    private static void villagerdetails$begin(Player player) {
        BlockRuleContext.begin(player instanceof ServerPlayer sp ? sp : null);
    }
}
