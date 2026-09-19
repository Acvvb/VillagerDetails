package com.villagerdetails.client.mixin;

import com.villagerdetails.client.helper.VillagerBedHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.state.BlockState;
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

    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void villagerGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        // 非村民直接放行，走原版逻辑
        if (!(entity instanceof Villager villager)) {
            return;
        }
        Optional<BlockPos> bedPositionOpt = VillagerBedHelper.getBedPosition(villager);

        if (bedPositionOpt.isEmpty()) {
            return;
        }

        BlockPos pos = bedPositionOpt.get();
        BlockState bedState = villager.level().getBlockState(pos);

        // 床方块不存在 → 放行，走原版逻辑
        if (!bedState.is(BlockTags.BEDS)) {
            return;
        }

        // 床还在，计算村民到床中心的距离
        double distance = Vector3d.distance(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                villager.getX(), villager.getY(), villager.getZ()
        );
        if (distance > MAX_DISTANCE_TO_BED) {
            // 距离过远，强制高亮，拦截原版逻辑
            cir.setReturnValue(true);
        }
    }
}