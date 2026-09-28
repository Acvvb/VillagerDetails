package com.villagerdetails.mixin;

import com.villagerdetails.handler.noSqueeze.SqueezeHandler;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$noSqueezeDamage(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!SqueezeHandler.isProtected(self)) return;

        ResourceKey<DamageType> type = source.typeHolder().unwrapKey().orElse(null);
        if (type == null) return;

        String id = type.identifier().toString();
        if (isSqueezeDamage(id)) {
            cir.setReturnValue(false);   // 取消伤害
        }
    }

    /** 挤压相关伤害类型 */
    @Unique
    private static boolean isSqueezeDamage(String id) {
        return switch (id) {
            // 核心挤压
            case "minecraft:cramming",              // 同位置实体过多
                 "minecraft:in_wall",               // 卡在方块里窒息
                 // 方块挤压（可选）
                 "minecraft:cactus",                // 仙人掌
                 "minecraft:sweet_berry_bush",      // 甜浆果丛
                 "minecraft:falling_block",         // 掉落的方块砸
                 "minecraft:falling_stalactite",    // 冰锥砸
                 "minecraft:fly_into_wall"          // 撞墙
                    -> true;
            default -> false;
        };
    }
}