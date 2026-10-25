package com.villagerdetails.client.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import com.villagerdetails.client.cache.base.ClientMapState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 客户端记录的「正在视图高亮的传送门绑定区域」，<b>按维度</b>存。
 *
 * <p>由服务端 {@code PortalViewSyncPayload} 推送，或由「清除高亮」触发按钮本地清空
 * （{@link #clear()}，一次清掉所有维度）。这是<b>纯本机视图状态</b>，不是权威数据 ——
 * 清除它不需要走服务端。
 *
 * <p>一条传送门绑定横跨两个维度，一次「高亮」服务端会把两侧<b>各推一个包</b>下来。
 * 所以这里不能只留一个区域：玩家此刻多半只站在其中一侧，但另一侧的记录必须留着，
 * 等他进入那个维度时渲染器才能把框画出来。同一维度再来新区域就整条替换，不会越攒越多。
 *
 * <p>整份区域表用<b>一个不可变 Map</b> 承载、整体替换：渲染器读到的永远是某个完整版本，
 * 不会读到「一半是新的、一半是旧的」。
 */
public final class PortalViewClientState implements SessionState {

    /**
     * 单例：扫包框架靠它拿实例，调用方统一写 {@code PortalViewClientState.INSTANCE.xxx()}
     */
    public static final PortalViewClientState INSTANCE = new PortalViewClientState();

    /**
     * 一侧的高亮区域：维度 + 两个角点
     */
    public record Region(ResourceKey<Level> dimension, BlockPos min, BlockPos max) {
    }

    // ClientMapState 接管了底层的 volatile、synchronized 和不可变 Map 的复制
    private final ClientMapState<ResourceKey<Level>, Region> state = new ClientMapState<>();

    private PortalViewClientState() {
    }

    /**
     * 记下某个维度要高亮的区域；该维度原有的记录会被替换
     */
    public void set(ResourceKey<Level> dim, BlockPos a, BlockPos b) {
        if (dim == null || a == null || b == null) {
            return;
        }
        // 一行搞定：底层自动 copy-on-write
        state.put(dim, new Region(dim, a, b));
    }

    /**
     * 取某个维度当前要画的区域；没有就返回 {@code null}
     */
    public Region regionOf(ResourceKey<Level> dim) {
        return dim == null ? null : state.get(dim);
    }

    /**
     * 清空<b>所有维度</b>的高亮：主世界和地狱一起清掉，不留任何一侧。
     *
     * <p>这就是「清除高亮」触发按钮 / 服务端空包的<b>业务入口</b> ——
     * 刻意不提供「只清某个维度」的重载：玩家只看得见自己所在的那一侧，
     * 按维度清必然会把另一侧的残留留下来，等他一传送过去就又冒出来了。
     */
    public void clear() {
        state.clear();
    }

    /**
     * 断线 / 切世界时清空（由 {@link com.villagerdetails.client.cache.ClientSessionStates} 统一调用）。
     *
     * <p>和 {@link #clear()} 是两件事：那个是「用户/服务端要求清高亮」，这个是会话结束。
     * 现在两者效果一样，但分开以后谁都不会被对方的改动误伤。
     */
    @Override
    public void clearSession() {
        clear();
    }
}
