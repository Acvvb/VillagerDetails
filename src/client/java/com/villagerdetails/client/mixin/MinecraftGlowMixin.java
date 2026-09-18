
package com.villagerdetails.client.mixin;

import com.villagerdetails.client.helper.VillagerBedHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(Minecraft.class)
public abstract class MinecraftGlowMixin {

    @Unique
    private static final double MAX_DISTANCE_TO_BED = 5.0;

    @Unique
    private static final Logger log = LogManager.getLogger(MinecraftGlowMixin.class);

    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void villagerGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        // 非村民直接放行，走原版逻辑
        if (!(entity instanceof Villager)) {
            return;
        }
        Villager villager = (Villager) entity;

        log.info("村民高亮检测开始: entity={}, UUID={}", entity.getName().getString(), entity.getUUID());

        // 把 Optional.ifPresent(lambda) 语法糖改成 if-present 判断
        Optional<BlockPos> bedPositionOpt = VillagerBedHelper.getBedPosition(villager);

        if (!bedPositionOpt.isPresent()) {
            log.info("村民 {} 没有绑定床, 放行走原版逻辑", villager.getUUID());
            return;
        }

        BlockPos pos = bedPositionOpt.get();
        BlockState bedState = villager.level().getBlockState(pos);

        // 床方块不存在 → 放行，走原版逻辑
        if (!bedState.is(BlockTags.BEDS)) {
            log.info("村民 {} 绑定的床已不存在: {}, 放行走原版逻辑", villager.getUUID(), pos);
            return;
        }

        // 床还在，计算村民到床中心的距离
        double distance = Vector3d.distance(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                villager.getX(), villager.getY(), villager.getZ()
        );

        log.info("村民 {} 到床的距离: {}", villager.getUUID(), distance);

        if (distance > MAX_DISTANCE_TO_BED) {
            // 距离过远，强制高亮，拦截原版逻辑
            log.info("村民 {} 距离床过远({} > {}), 开启高亮", villager.getUUID(), distance, MAX_DISTANCE_TO_BED);
            cir.setReturnValue(true);
        } else {
            log.info("村民 {} 在床附近({} <= {}), 不高亮, 放行走原版逻辑", villager.getUUID(), distance, MAX_DISTANCE_TO_BED);
            // 距离正常 → 不调用 setReturnValue，放行走原版逻辑
        }
    }
}