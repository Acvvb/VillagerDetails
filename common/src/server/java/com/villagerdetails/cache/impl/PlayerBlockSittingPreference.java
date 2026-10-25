package com.villagerdetails.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 「我还想不想坐着」的服务端名单（由客户端自己上报，见
 * {@code PlayerBlockSittingEnabledPayload}）。
 *
 * <p>和「玩家头顶乘坐」那张名单的默认值<b>相反</b>：
 * <ul>
 *     <li><b>不在名单里 = 允许坐</b>。所以没装本 mod 的玩家照样能坐楼梯 / 半砖；</li>
 *     <li>只有客户端明确说「不想坐」的玩家才会被记进来；</li>
 *     <li>玩家退出就丢掉记录，下次进服重新上报（客户端一进服就会发一次）。</li>
 * </ul>
 *
 * <p>只在服务端主线程读写（收包回调和 tick 巡检都在主线程），所以普通 {@link HashSet} 就够；
 * 生命周期（退出 / 停服）由 {@code ServerSessionStates} 扫包接管，这里不用自己注册事件。
 */
public final class PlayerBlockSittingPreference implements SessionState {

    public static final PlayerBlockSittingPreference INSTANCE = new PlayerBlockSittingPreference();

    private PlayerBlockSittingPreference() {
    }

    /**
     * 明确关掉这个功能的玩家
     */
    private final Set<UUID> disabled = new HashSet<>();

    /**
     * 记下这个玩家的选择（收包时调）；{@code true} 等于把人从「不想坐」名单里摘掉
     */
    public void set(ServerPlayer player, boolean enabled) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        if (enabled) {
            disabled.remove(uuid);
        } else {
            disabled.add(uuid);
        }
    }

    /**
     * 这个人现在还能不能坐；<b>没上报过 = 能坐</b>
     */
    public boolean enabled(ServerPlayer player) {
        return player != null && !disabled.contains(player.getUUID());
    }

    @Override
    public void clearPlayer(UUID playerUuid) {
        disabled.remove(playerUuid);
    }

    @Override
    public void clearSession() {
        disabled.clear();
    }
}
