package com.villagerdetails.cache;

import com.villagerdetails.cache.base.SessionState;
import com.villagerdetails.network.channel.ChannelScanner;
import com.villagerdetails.network.channel.SyncSubscriptions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ServerSessionStates {

    private static final Logger LOGGER = LogManager.getLogger(ServerSessionStates.class);

    private static final String STATE_PACKAGE = "com.villagerdetails.cache.impl";

    private static final ServerSessionStates INSTANCE = new ServerSessionStates();

    private final List<SessionState> STATES = new ArrayList<>();
    private boolean registered;

    private ServerSessionStates() {
    }

    /**
     * 统一注册入口（唯一对外入口）。内部委托给 {@link #init()}。
     */
    public static void register() {
        INSTANCE.init();
    }

    /**
     * 实例侧：扫包 + 订阅两个清理事件。
     * 之所以不叫 register，是为了避免与 static register() 同名同签名而无法编译。
     */
    private void init() {
        registerAll();

        ServerPlayConnectionEvents.DISCONNECT.register((handler, _) -> {
            ServerPlayer player = handler.getPlayer();
            clearPlayer(player.getUUID());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(_ -> clearAll());
    }

    /**
     * 扫包接管所有服务端会话状态（重复调用是空操作）。
     */
    private void registerAll() {
        if (registered) {
            return;
        }
        registered = true;

        List<ChannelScanner.Failure> failures = new ArrayList<>();
        List<String> names = new ArrayList<>();

        int count = ChannelScanner.forEach(STATE_PACKAGE, ServerSessionStates.class, SessionState.class,
                state -> {
                    names.add(state.getClass().getSimpleName());
                    STATES.add(state);
                }, failures);

        STATES.add(SyncSubscriptions.INSTANCE);
        names.add("SyncSubscriptions");

        ChannelScanner.checkNotEmpty(count, STATE_PACKAGE, "服务端状态扫包", failures);
        ChannelScanner.reportFailures(LOGGER, "服务端状态扫包", failures);

        LOGGER.info("服务端缓存扫包完成：{} 个状态接入会话清理 {}", STATES.size(), names);
    }

    /**
     * 某个玩家退出：摘掉他那一份数据。
     */
    private void clearPlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        registerAll();
        for (SessionState state : STATES) {
            guard(state, () -> state.clearPlayer(playerUuid));
        }
    }

    /**
     * 整份清空（服务器停止时调）。
     */
    private void clearAll() {
        registerAll();
        for (SessionState state : STATES) {
            guard(state, state::clearSession);
        }
    }

    private void guard(SessionState state, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            LOGGER.error("清理服务端会话状态 [{}] 失败", state.getClass().getSimpleName(), t);
        }
    }
}