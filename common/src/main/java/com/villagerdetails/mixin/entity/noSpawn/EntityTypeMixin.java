package com.villagerdetails.mixin.entity.noSpawn;

import com.villagerdetails.handler.entity.noSpawn.NoSpawnHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 禁止生成规则的拦截点：名单里的实体类型在这里被「创建成空」。
 *
 * <p>打在 {@code create(Level, EntitySpawnRequest)} 上，因为它是所有生成路径的最内层：
 * {@code create(Level, EntitySpawnReason)} 自己也只是转手调它，
 * 刷怪蛋 / 自然生成 / 刷怪笼 / 繁殖 / {@code /summon} 一律经过这里。
 *
 * <p>哪些原因该放行（加载 / 跨维度 / 转化）由 {@link NoSpawnHandler} 判定，
 * 这里只负责把结论变成 null。
 */
@Mixin(EntityType.class)
public abstract class EntityTypeMixin {

    /**
     * 名单里的实体类型不生成
     */
    @Inject(method = "create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnRequest;)Lnet/minecraft/world/entity/Entity;",
            at = @At("HEAD"),
            cancellable = true)
    private void villagerdetails$blockSpawn(Level level, EntitySpawnRequest request,
                                            CallbackInfoReturnable<Entity> cir) {
        EntityType<?> self = (EntityType<?>) (Object) this;
        if (NoSpawnHandler.isBlocked(self, request.reason())) {
            cir.setReturnValue(null);
        }
    }
}
