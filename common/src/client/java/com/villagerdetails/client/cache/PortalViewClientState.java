package com.villagerdetails.client.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端记录的「正在视图高亮的传送门绑定区域」，<b>按维度</b>存。
 *
 * <p>由服务端 {@code PortalViewSyncPayload} 推送，或由「清除高亮」触发按钮本地清空
 * （{@link #clearAll()}，一次清掉所有维度）。这是<b>纯本机视图状态</b>，不是权威数据 ——
 * 清除它不需要走服务端。
 *
 * <p>一条传送门绑定横跨两个维度，一次「高亮」服务端会把两侧<b>各推一个包</b>下来。
 * 所以这里不能只留一个区域：玩家此刻多半只站在其中一侧，但另一侧的记录必须留着，
 * 等他进入那个维度时渲染器才能把框画出来。同一维度再来新区域就整条替换，不会越攒越多。
 *
 * <p>整份区域表用<b>一个不可变 Map</b> 承载、整体替换：渲染器读到的永远是某个完整版本，
 * 不会读到「一半是新的、一半是旧的」。
 */
public final class PortalViewClientState {

    /**
     * 一侧的高亮区域：维度 + 两个角点
     */
    public record Region(ResourceKey<Level> dimension, BlockPos min, BlockPos max) {
    }

    private static final Object LOCK = new Object();

    /**
     * 维度 → 该维度当前要画的区域（不可变，改的时候整体换一份）
     */
    private static volatile Map<ResourceKey<Level>, Region> regions = Map.of();

    private PortalViewClientState() {
    }

    /**
     * 记下某个维度要高亮的区域；该维度原有的记录会被替换
     */
    public static void set(ResourceKey<Level> dim, BlockPos a, BlockPos b) {
        if (dim == null || a == null || b == null) {
            return;
        }
        synchronized (LOCK) {
            Map<ResourceKey<Level>, Region> next = new HashMap<>(regions);
            next.put(dim, new Region(dim, a, b));
            regions = Map.copyOf(next);
        }
    }

    /**
     * 取某个维度当前要画的区域；没有就返回 {@code null}
     */
    public static Region regionOf(ResourceKey<Level> dim) {
        return dim == null ? null : regions.get(dim);
    }

    /**
     * 清空<b>所有维度</b>的高亮：主世界和地狱一起清掉，不留任何一侧。
     *
     * <p>这就是「清除高亮」触发按钮 / 断线清理走的唯一入口 ——
     * 刻意不提供「只清某个维度」的重载：玩家只看得见自己所在的那一侧，
     * 按维度清必然会把另一侧的残留留下来，等他一传送过去就又冒出来了。
     */
    public static void clearAll() {
        synchronized (LOCK) {
            regions = Map.of();
        }
    }
}
