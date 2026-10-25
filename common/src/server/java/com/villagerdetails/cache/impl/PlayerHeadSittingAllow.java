package com.villagerdetails.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 「允许别人坐到我头上」的服务端名单（由客户端自己上报，见
 * {@code PlayerHeadSittingAllowPayload}）。
 *
 * <p>语义：
 * <ul>
 *     <li><b>不在名单里 = 不允许</b>。所以没装本 mod 的玩家天然是被保护的，能放心开服务器；</li>
 *     <li>每个玩家只管自己这一格，别人无权替他改；</li>
 *     <li>玩家退出就丢掉记录，下次进服由客户端重新上报一次 —— 不落盘、不跨存档。</li>
 * </ul>
 *
 * <p>只在服务端主线程读写（收包回调已经切回主线程，tick 巡检也在主线程），
 * 所以一张普通 {@link HashSet} 就够；生命周期（退出 / 停服）由
 * {@code ServerSessionStates} 扫包接管，这里不用自己注册事件。
 */
public final class PlayerHeadSittingAllow implements SessionState {

    public static final PlayerHeadSittingAllow INSTANCE = new PlayerHeadSittingAllow();

    private PlayerHeadSittingAllow() {
    }

    /**
     * 允许别人坐到自己头上的玩家
     */
    private final Set<UUID> allowed = new HashSet<>();

    /**
     * 记下这个玩家的选择（收包时调）。
     *
     * <p>{@code false} 会直接把 uuid 从名单里摘掉 —— 正在他头上的乘客由
     * {@code PlayerHeadSittingHandler} 下一个 tick 踢下来。
     */
    public void set(ServerPlayer player, boolean allow) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        if (allow) {
            allowed.add(uuid);
        } else {
            allowed.remove(uuid);
        }
    }

    /**
     * 这个人现在允不允许别人坐到他头上
     */
    public boolean allows(ServerPlayer player) {
        return player != null && allowed.contains(player.getUUID());
    }

    @Override
    public void clearPlayer(UUID playerUuid) {
        allowed.remove(playerUuid);
    }

    @Override
    public void clearSession() {
        allowed.clear();
    }
}
