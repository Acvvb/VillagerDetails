package com.villagerdetails.mixin.block.TntNoDestroyDrops;

import com.villagerdetails.handler.explosion.TntNoDestroyDropsHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TNT 不炸毁掉落物：在爆炸伤害计算判断「是否伤害该实体」时，
 * 对 TNT 爆炸中的掉落物（{@link net.minecraft.world.entity.item.ItemEntity}）直接返回 false，
 * 使它们在 TNT 爆炸中既不受伤害也不会被摧毁。
 */
@Mixin(ExplosionDamageCalculator.class)
public abstract class ExplosionMixin {

    @Inject(method = "shouldDamageEntity", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$protectItemDropsFromTnt(Explosion explosion, Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (TntNoDestroyDropsHandler.shouldProtectItemDrops(explosion, entity)) {
            cir.setReturnValue(false);
        }
    }
}
