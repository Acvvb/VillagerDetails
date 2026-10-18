package com.villagerdetails.handler.entity.noSpawn;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.section.item.MobNoSpawnConfig;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;

/**
 * 禁止生成规则的过滤器：<b>规则开着</b>并且实体类型在名单里时，{@code EntityType.create} 直接返回 null，
 * 于是所有「生成」路径都拿不到实体，名额也不会进世界。
 *
 * <p>拦截点选在 {@link EntityType#create(net.minecraft.world.level.Level, net.minecraft.world.entity.EntitySpawnRequest)}
 * 上：这是所有生成路径的必经之路 ——
 * <ul>
 *     <li>刷怪蛋 / 水桶 / 发射器 / {@code /summon} → {@code EntityType.spawn(...)} → {@code create}</li>
 *     <li>自然生成 → {@code NaturalSpawner} → {@code create}</li>
 *     <li>刷怪笼 / 试炼刷怪笼 / 袭击 / 巡逻队 / 结构生成 → {@code create}</li>
 *     <li>繁殖 / 生物互相召唤 / 增援 → {@code create}</li>
 * </ul>
 * 并且 {@code create} 的返回值本来就是「可能为 null」（原版自己也会因为 {@code canSpawn} 返回 null），
 * 所以取消掉不需要额外兜底。
 *
 * <p><b>不是所有 {@code create} 都算生成</b>，下面三种必须放行，否则会伤到已经存在的实体：
 * <ul>
 *     <li>{@link EntitySpawnReason#LOAD} —— 区块加载 / 客户端按包重建实体。拦了会把世界里的生物删掉。</li>
 *     <li>{@link EntitySpawnReason#DIMENSION_TRAVEL} —— 走传送门换维度时的实体重建。</li>
 *     <li>{@link EntitySpawnReason#CONVERSION} —— 生物转化（僵尸村民治愈、僵尸变溺尸等），
 *         拦了会让原实体被丢弃却没有新实体，等于凭空删掉一只。</li>
 * </ul>
 * 其余原因都是真正的「生成」，一律拦。
 *
 * <p>判定顺序和另外几条逐条配置的规则一致：先看规则开关、再看名单 —— 关掉规则就等于对原版零影响，
 * 名单本身可以留着，下次开规则继续用。
 */
public final class NoSpawnHandler {

    private NoSpawnHandler() {
    }

    /**
     * 这次创建要不要拦下来
     *
     * @param type   被创建的实体类型
     * @param reason 创建原因（{@code /ec c MobNoSpawn} 之外的原版语义，原样传给这里）
     */
    public static boolean isBlocked(EntityType<?> type, EntitySpawnReason reason) {
        if (type == null || reason == null) {
            return false;
        }
        // 最便宜的一步放最前面：一次枚举引用比较
        if (!isSpawn(reason)) {
            return false;
        }
        if (!RuleCache.isEnabled(RuleType.MOB_NO_SPAWN)) {
            return false;
        }
        return MobNoSpawnConfig.INSTANCE.contains(type);
    }

    /**
     * 这个原因算不算「真正的生成」（加载 / 跨维度 / 转化都不算）
     */
    private static boolean isSpawn(EntitySpawnReason reason) {
        return reason != EntitySpawnReason.LOAD
                && reason != EntitySpawnReason.DIMENSION_TRAVEL
                && reason != EntitySpawnReason.CONVERSION;
    }
}
