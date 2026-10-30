package com.villagerdetails.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个玩家的<b>模组版本</b>（由 {@code C2SHandshakePayload} 上报，握手收包端写）。
 *
 * <p>放在 {@code cache.impl} 包下 = 被 {@code ServerSessionStates} 自动扫到、自动接入会话清理
 * （玩家退出时 {@link #clearPlayer} 摘掉那一条）。所以业务侧不需要自己记得清理。
 *
 * <p><b>为什么不用 {@code WeakHashMap<UUID, String>}</b>：
 * <ol>
 *     <li>{@code WeakHashMap} 的键是「你传进去的那个 UUID 实例」的弱引用 —— 数据能活多久取决于
 *         GC 什么时候扫，而「这个玩家的版本」应该是「在线期间一定有、下线就立刻没有」的确定语义，
 *         不能交给 GC 时机决定；</li>
 *     <li>{@code WeakHashMap} 不是线程安全的，而收包回调已经切到服务端主线程、
 *         业务查询可能来自别处（命令、tick、集成服切换存档）。</li>
 * </ol>
 * 这里用 {@link ConcurrentHashMap} + 显式的 {@link #clearPlayer}，生命周期跟着玩家上下线走，
 * 和 {@code SyncSubscriptions} 是同一套写法。
 *
 * <p>「没上报过」和「上报了空串」要分得开：前者 {@link #known} 为 false（典型情况是原版客户端，
 * 它压根不会发握手包），后者是装了模组但读不到版本。
 */
public final class PlayerModVersion implements SessionState {

    public static final PlayerModVersion INSTANCE = new PlayerModVersion();

    /**
     * 玩家 UUID → 上报的版本原文
     */
    private final Map<UUID, String> versions = new ConcurrentHashMap<>();

    private PlayerModVersion() {
    }

    /**
     * 记下这个玩家上报的版本（握手收包端调用）
     */
    public void set(ServerPlayer player, String version) {
        if (player == null) {
            return;
        }
        versions.put(player.getUUID(), version == null ? "" : version);
    }

    /**
     * 这个玩家上报的版本原文；没上报过返回空串。
     *
     * <p>原版客户端（没装模组）永远返回空串，用 {@link #known} 区分「没装」和「装了但版本是空」。
     */
    public String of(ServerPlayer player) {
        return player == null ? "" : versions.getOrDefault(player.getUUID(), "");
    }

    /**
     * 这个玩家到底有没有上报过版本（= 是不是装了本模组）
     */
    public boolean known(ServerPlayer player) {
        return player != null && versions.containsKey(player.getUUID());
    }

    /**
     * 停服：整份清空（集成服换存档时不留上一局的记录）
     */
    @Override
    public void clearSession() {
        versions.clear();
    }

    /**
     * 玩家退出：摘掉他那一条
     */
    @Override
    public void clearPlayer(UUID playerUuid) {
        if (playerUuid != null) {
            versions.remove(playerUuid);
        }
    }
}
