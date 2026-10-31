package com.villagerdetails.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送门绑定的临时选区缓存：每个玩家分别记录主世界选区与地狱选区，直到 bind 成功后清空。
 *
 * <p>生命周期（玩家退出 / 停服）由 {@code ServerSessionStates} 扫包接管。
 *
 * <p><b>「没有数据」一律是 {@code null}，绝不造占位对象</b>：
 * <ul>
 *     <li>没 set 过 / 已被 {@link #clear(UUID)} 清掉 → {@code getOverworld} / {@code getNether}
 *         返回 {@code null}，调用方据此提示「不完整」；</li>
 *     <li>存进来的坐标本身是 {@code null}（上游脏数据）→ 直接丢弃这次写入，
 *         读出来依然是 {@code null}，不会变成「半个选区」；</li>
 *     <li>万一 map 里混进了半残记录，读取时也会被归一成 {@code null}。</li>
 * </ul>
 *
 * <p>于是「拿到非 null」就等价于「玩家真实设置过一个完整选区」——
 * 调用方<b>不需要</b>再按坐标值猜「这是不是占位数据」，也不该这么做：
 * {@code (0,0,0)} 在超平坦 / 自定义维度里是合法位置。
 */
public final class PortalSelectionCache implements SessionState {

    public static final PortalSelectionCache INSTANCE = new PortalSelectionCache();

    private static final Map<UUID, Region> OVERWORLD = new ConcurrentHashMap<>();
    private static final Map<UUID, Region> NETHER = new ConcurrentHashMap<>();

    private PortalSelectionCache() {
    }

    public static void setOverworld(UUID uuid, BlockPos pos1, BlockPos pos2) {
        put(OVERWORLD, uuid, pos1, pos2);
    }

    public static void setNether(UUID uuid, BlockPos pos1, BlockPos pos2) {
        put(NETHER, uuid, pos1, pos2);
    }

    public static Region getOverworld(UUID uuid) {
        return usable(OVERWORLD.get(uuid));
    }

    public static Region getNether(UUID uuid) {
        return usable(NETHER.get(uuid));
    }

    public static void clear(UUID uuid) {
        OVERWORLD.remove(uuid);
        NETHER.remove(uuid);
    }

    /**
     * 写入一侧选区；坐标不全（任何一个是 {@code null}）就当作「没设置」，不落缓存。
     *
     * <p>顺手 {@code immutable()}：{@link BlockPos} 的 {@code mutable()} 实例会被复用/改写，
     * 存引用等于存了个会被别人改掉的坐标。
     */
    private static void put(Map<UUID, Region> map, UUID uuid, BlockPos pos1, BlockPos pos2) {
        if (uuid == null || pos1 == null || pos2 == null) {
            return;
        }
        map.put(uuid, new Region(pos1.immutable(), pos2.immutable()));
    }

    /**
     * 读出来只给「两个角点都在」的记录，其余（不存在 / 半残）一律 {@code null}。
     */
    private static Region usable(Region region) {
        return region == null || region.pos1() == null || region.pos2() == null ? null : region;
    }

    @Override
    public void clearPlayer(UUID playerUuid) {
        clear(playerUuid);
    }

    @Override
    public void clearSession() {
        OVERWORLD.clear();
        NETHER.clear();
    }

    /**
     * 一块由两个对角坐标定义的区域
     *
     * <p>不做任何「坐标值像不像占位」的判断：{@code (0,0,0)} 在超平坦 / 自定义维度里
     * 是合法位置，真正选在那儿的玩家不该被误伤。
     */
    public record Region(BlockPos pos1, BlockPos pos2) {
    }
}
