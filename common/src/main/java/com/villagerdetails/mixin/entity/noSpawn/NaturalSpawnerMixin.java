package com.villagerdetails.mixin.entity.noSpawn;

import com.villagerdetails.handler.entity.noSpawn.NoSpawnHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 禁止生成规则的「消音」补丁：把自然生成路径上的刷屏警告掐掉。
 *
 * <p>原版 {@code NaturalSpawner.getMobForSpawn} 长这样：
 * <pre>{@code
 * Mob mob = EntityType.create(level, EntitySpawnReason.NATURAL);   // 我们在这里返回 null
 * if (mob instanceof Mob) return mob;
 * LOGGER.warn("Can't spawn entity of type: {}", id);              // ← 于是每 tick 刷一条
 * return null;
 * }</pre>
 * 也就是说 —— {@link com.villagerdetails.mixin.entity.noSpawn.EntityTypeMixin}
 * 把 {@code create} 弄成 null 之后，原版会自己认为「创建失败了」并打一条 WARN。
 * 名单里放一个会自然生成的生物（僵尸 / 骷髅 / 苦力怕…），警告就会跟着刷怪频率一直刷，
 * 服务端日志被淹掉。注意这不是「僵尸专属」的个例：<b>所有自然生成的生物</b>都走这一个日志点，
 * 所以任何生物丢进名单都会出现同样的刷屏。
 *
 * <p>修法是在 {@code getMobForSpawn} 的 HEAD 就先判一次名单：被拦的直接返回 null，
 * 原版那段「create → instanceof → WARN」根本不会执行，日志自然没了。
 * 返回 null 和原版创建失败的返回值完全一致（调用方 {@code spawnCategoryForPosition}
 * 拿到 null 就是直接 return），行为不变，只是不再打日志。
 *
 * <p>这里复用 {@link NoSpawnHandler#isBlocked}，规则开关 / 名单判定和 {@code EntityTypeMixin}
 * 是同一份逻辑，不会出现两处结论不一致。
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {

    /**
     * 自然生成的实体被名单拦住时，直接返回 null，跳过原版那条 WARN
     */
    @Inject(method = "getMobForSpawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/EntityType;)Lnet/minecraft/world/entity/Mob;",
            at = @At("HEAD"),
            cancellable = true)
    private static void villagerdetails$skipBlockedSpawn(ServerLevel level, EntityType<?> type,
                                                         CallbackInfoReturnable<Mob> cir) {
        if (NoSpawnHandler.isBlocked(type, EntitySpawnReason.NATURAL)) {
            cir.setReturnValue(null);
        }
    }
}
