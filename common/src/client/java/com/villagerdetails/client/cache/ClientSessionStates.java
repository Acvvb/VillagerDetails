package com.villagerdetails.client.cache;

import com.villagerdetails.client.feature.villagerbed.VillagerBedHelper;
import com.villagerdetails.client.setting.network.RuleAuxCache;
import com.villagerdetails.client.setting.network.ServerRuleClientState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * 客户端会话状态的<b>唯一断线清理入口</b>。
 *
 * <p>下面这张清单就是「断线要清哪些东西」的全部答案：新增一份只在本次联机里有意义的状态，
 * 在这里加一行（方法引用）即可，不用再去某个通道里补一个 DISCONNECT 回调。
 *
 * <p>单个清理抛异常只记 ERROR 并继续清下一份：一个状态清不掉不该连累其它状态。
 */
public final class ClientSessionStates {

    private static final Logger LOGGER = LogManager.getLogger(ClientSessionStates.class);

    /**
     * 断线时按顺序清空的状态清单
     */
    private static final List<ClientSessionState> STATES = List.of(
            VillagerBedHelper::clear,
            BBSelectionClientState::clear,
            EBSelectionClientState::clear,
            PortalViewClientState::clearAll,
            ServerRuleClientState::clearSession,
            RuleAuxCache::clear);

    private ClientSessionStates() {
    }

    /**
     * 注册断线清理；客户端初始化时调一次
     */
    public static void registerEvents() {
        ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> clearAll());
    }

    /**
     * 清空所有会话状态（断线时自动调，也可以手动调）
     */
    public static void clearAll() {
        for (ClientSessionState state : STATES) {
            try {
                state.clearOnSessionEnd();
            } catch (Throwable t) {
                LOGGER.error("清理客户端会话状态失败：{}", state, t);
            }
        }
    }
}
