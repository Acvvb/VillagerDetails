package com.villagerdetails.pojo.dto;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 一只「已锁定交易」村民的完整交易快照（活在一次服务端会话的内存缓存里）。
 *
 * <p>记录本身完全由不可变数据组成：{@link BlockPos} 在构造时 {@code immutable()}，
 * {@code offers} 走 {@link List#copyOf} 做防御性拷贝，所以它可以安全地在主线程构造、
 * 在服务端线程池里读取。
 *
 * @param uuid      村民 UUID（缓存 key，也是死亡清理的依据）
 * @param pos       村民所在方块坐标（点击传送 / 展示用）
 * @param dimension 村民所在维度
 * @param offers    该村民当前能卖出的全部物品（只包含结果物品，不包含支付物）
 */
public record VillagerTradeInfo(
        UUID uuid,
        BlockPos pos,
        ResourceKey<Level> dimension,
        List<TradeItemInfo> offers
) {

    public VillagerTradeInfo {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(dimension, "dimension");
        // BlockPos 本身不可变，但实体里的 BlockPos 可能是可变的 MutableBlockPos 视图：拷一份再定住
        pos = pos.immutable();
        offers = offers == null ? List.of() : List.copyOf(offers);
    }

    /**
     * 关键词是否命中这只村民。
     *
     * <p>除了每件物品（{@link TradeItemInfo#matches}），还允许按 UUID、
     * 维度 id（{@code minecraft:the_nether} / {@code the_nether}）以及
     * {@code x,y,z} / {@code x y z} 两种坐标写法搜。
     *
     * @param lowerKeyword 已经 {@code trim + toLowerCase} 过的关键词
     */
    public boolean matches(String lowerKeyword) {
        if (lowerKeyword == null || lowerKeyword.isEmpty()) {
            return false;
        }
        if (uuid.toString().contains(lowerKeyword)) {
            return true;
        }

        String dimensionId = dimension.identifier().toString().toLowerCase(Locale.ROOT);
        if (dimensionId.contains(lowerKeyword)) {
            return true;
        }

        String coords = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        String coordsSpaced = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        if (coords.contains(lowerKeyword) || coordsSpaced.contains(lowerKeyword)) {
            return true;
        }

        for (TradeItemInfo offer : offers) {
            if (offer.matches(lowerKeyword)) {
                return true;
            }
        }
        return false;
    }
}
