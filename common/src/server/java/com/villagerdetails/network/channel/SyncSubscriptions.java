package com.villagerdetails.network.channel;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「这个玩家把哪几条 S→C 通道静音了」的<b>唯一记录处</b>。
 *
 * <p>由客户端的通用订阅包 {@code SyncSubscriptionPayload} 维护：客户端把本地显示关掉时
 * 发一个 {@code off}，服务端就在这儿记一笔；重新打开时发 {@code on}，再把这一笔删掉。
 * {@link SyncChannel#canSendTo} 会先查这里，所以「被静音的通道」连扫描/组包都会跳过。
 *
 * <p>玩家退出时整条记录丢弃（见 {@code SyncSubscriptionEvents}）：
 * 下次进服默认是「要发」，客户端如果仍然关着显示，收到第一包后会自己再发一次 off。
 *
 * <p>键用 UUID 而不是 {@code ServerPlayer} 实例：玩家对象会被重建，
 * 而且用 UUID 不会把已经下线的玩家实例攥在内存里。
 */
public final class SyncSubscriptions {

    /**
     * 玩家 UUID → 这个玩家静音的通道 id 集合
     */
    private static final Map<UUID, Set<Identifier>> MUTED = new ConcurrentHashMap<>();

    private SyncSubscriptions() {
    }

    /**
     * 这个玩家是不是把这条通道静音了
     */
    public static boolean isMuted(ServerPlayer player, Identifier channel) {
        if (player == null || channel == null) {
            return false;
        }
        Set<Identifier> set = MUTED.get(player.getUUID());
        return set != null && set.contains(channel);
    }

    /**
     * 设置 / 取消静音；集合空了就顺手把这一条玩家记录删掉，不给空集合留位置
     */
    public static void setMuted(ServerPlayer player, Identifier channel, boolean muted) {
        if (player == null || channel == null) {
            return;
        }
        UUID uuid = player.getUUID();
        if (muted) {
            MUTED.computeIfAbsent(uuid, _ -> ConcurrentHashMap.newKeySet()).add(channel);
            return;
        }
        Set<Identifier> set = MUTED.get(uuid);
        if (set == null) {
            return;
        }
        set.remove(channel);
        if (set.isEmpty()) {
            MUTED.remove(uuid, set);
        }
    }

    /**
     * 玩家退出：丢掉他的全部静音记录
     */
    public static void clear(ServerPlayer player) {
        if (player != null) {
            MUTED.remove(player.getUUID());
        }
    }

    /**
     * 服务器停止：整份清空（懒清理，防止内存里留下上一局的记录）
     */
    public static void clearAll() {
        MUTED.clear();
    }
}
