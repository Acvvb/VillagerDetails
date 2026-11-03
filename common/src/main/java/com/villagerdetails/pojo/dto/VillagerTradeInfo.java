package com.villagerdetails.pojo.dto;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 一只「已锁定交易」村民的完整交易快照（内存索引 + 落盘文件都存这一份）。
 *
 * <p>记录本身完全由不可变数据组成：{@link BlockPos} 在构造时 {@code immutable()}，
 * {@code offers} 走 {@link List#copyOf} 做防御性拷贝，所以它可以安全地在主线程构造、
 * 在服务端线程池里读取，也可以直接序列化进
 * {@code <存档>/villagerdetails/villager_trade_cache.json}。
 *
 * @param uuid      村民 UUID（缓存 key，也是死亡清理的依据）
 * @param pos       村民所在方块坐标（点击复制坐标 / 展示用）
 * @param dimension 村民所在维度
 * @param offers    该村民当前能卖出的全部交易（每笔都带卖出物、价格、数量，见 {@link TradeItemInfo}）
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
     * 关键词是否命中这只村民<b>本身</b>：UUID、维度 id、{@code x,y,z} / {@code x y z} 两种坐标写法。
     *
     * <p><b>交易内容不在这里比</b>：搜索要按「哪几笔交易命中」逐条收集（这样聊天栏只打命中的那几条），
     * 所以匹配逻辑在 {@code VillagerTradeCache.search} 里按 offer 粒度做：
     * id 直配（{@link TradeItemInfo#matchesId}）+ 服务端别名表兜底（中文名）。
     * 这个 DTO 是两端共用的，不能反向依赖服务端配置。
     *
     * @param lowerKeyword 已经 {@code trim + toLowerCase} 过的关键词
     */
    public boolean matchesLocation(String lowerKeyword) {
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
        return coords.contains(lowerKeyword) || coordsSpaced.contains(lowerKeyword);
    }
}
