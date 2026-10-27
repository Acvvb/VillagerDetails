package com.villagerdetails.mixin.block.portalPiglinNoSpawn;

import com.villagerdetails.handler.entity.piglinNoSpawn.PortalPiglinNoSpawnHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「禁用地狱门刷僵尸猪灵」的拦截点：绑定区域里的地狱门方块不再执行这次随机刻。
 *
 * <p>原版 {@code NetherPortalBlock.randomTick} 只做一件事 ——
 * 在维度属性 {@code nether_portal_spawns_piglin} 为真、难度不为和平、玩家离得够近时，
 * 以 1/2000 × 难度 的概率从门底刷一只僵尸猪灵。所以整段取消掉不会碰到别的逻辑：
 * 传送、门形状、动画都在别的方法里。
 *
 * <p>判定（规则开关 + 区域是否覆盖这个方块）全部交给
 * {@link PortalPiglinNoSpawnHandler#isBlocked}，和指令那边的绑定数据是同一份来源。
 *
 * <p>目标方法签名写全了：{@code randomTick} 在 {@code Block} 上也有一个空实现，
 * 明确写出参数类型，编译期就不会糊里糊涂挂到父类上去（两个支持版本里
 * {@link NetherPortalBlock} 都重写了它，构建时的 refmap 会再校验一次）。
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalSpawnMixin {

    @Inject(
            method = "randomTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void villagerdetails$blockPortalPiglinSpawn(BlockState state, ServerLevel level, BlockPos pos,
                                                        RandomSource random, CallbackInfo ci) {
        if (PortalPiglinNoSpawnHandler.isBlocked(level, pos)) {
            ci.cancel();
        }
    }
}
