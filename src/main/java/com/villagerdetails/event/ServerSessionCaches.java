package com.villagerdetails.event;

import com.villagerdetails.cache.BBSectionStateCache;
import com.villagerdetails.cache.EBSelectionStateCache;
import com.villagerdetails.cache.PortalSelectionCache;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 服务端「只活在一次联机会话里」的玩家缓存：断线时按 UUID 摘掉。
 *
 * <p>这几个缓存都是进程级静态 Map（key = 玩家 UUID）。玩家退出不摘的话，
 * 长时间运行会一直堆积；集成服反复换存档时，同一个 UUID 还会读到上一局留下的选择。
 *
 * <p>下面这张清单就是「断线要清哪些东西」的全部答案：新增一份这样的玩家缓存，
 * 在这里加一行（方法引用）即可。客户端侧的对应物是 {@code client.cache.ClientSessionStates}。
 */
public final class ServerSessionCaches {

    private static final Logger LOGGER = LogManager.getLogger(ServerSessionCaches.class);

    /**
     * 断线时清空的玩家缓存
     */
    private static final List<Consumer<UUID>> CLEANERS = List.of(
            EBSelectionStateCache::clear,
            BBSectionStateCache::clearSelection,
            PortalSelectionCache::clear);

    private ServerSessionCaches() {
    }

    /**
     * 注册断线清理；模组初始化时调一次
     */
    public static void init() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, _) -> clear(handler.getPlayer().getUUID()));
    }

    /**
     * 摘掉这个玩家所有会话内的缓存；单个清不掉只记日志，不连累其它几个
     */
    public static void clear(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        for (Consumer<UUID> cleaner : CLEANERS) {
            try {
                cleaner.accept(playerUuid);
            } catch (Throwable t) {
                LOGGER.error("清理玩家 {} 的会话缓存失败：{}", playerUuid, cleaner, t);
            }
        }
    }
}
