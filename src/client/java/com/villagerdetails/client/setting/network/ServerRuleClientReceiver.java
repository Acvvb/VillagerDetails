package com.villagerdetails.client.setting.network;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.ServerRuleRequestPayload;
import com.villagerdetails.network.ServerRuleSetPayload;
import com.villagerdetails.network.ServerRuleSyncPayload;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端侧的服务器规则同步：
 * 接收服务端下发的全部规则状态写进本地 {@link RuleCache}，并提供「请求同步 / 修改规则」的发送入口。
 */
public final class ServerRuleClientReceiver {

    private static final Logger LOGGER = LogManager.getLogger(ServerRuleClientReceiver.class);

    private ServerRuleClientReceiver() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ServerRuleSyncPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    // 整份记下这次同步过来的规则：没在里面的 = 服务端判定我们没权限看
                    List<String> visibleNames = new ArrayList<>(payload.rules().size());
                    for (ServerRuleSyncPayload.Entry entry : payload.rules()) {
                        visibleNames.add(entry.registerName());
                        RuleType type = RuleType.getRuleTypeByRegisterName(entry.registerName());
                        if (type == null) {
                            continue;
                        }
                        // 只写本地镜像：回调是服务端的事，客户端不需要触发
                        RuleCache.applyClientMirror(type, entry.state());
                    }
                    // 先记清单再 bumpVersion：界面重建时会用这份清单过滤
                    ServerRuleClientState.setSyncedRules(visibleNames);
                    ServerRuleClientState.bumpVersion();
                }));

        // 进服务器时确认对方装没装本 mod，装了就让服务端推一份状态过来
        ClientPlayConnectionEvents.JOIN.register((_, _, _) -> {
            if (ServerRuleClientState.isAvailable()) {
                ClientPlayNetworking.send(ServerRuleRequestPayload.INSTANCE);
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> {
            ServerRuleClientState.setAvailable(false);
            ServerRuleClientState.clearSyncedRules();
        });
    }

    /**
     * 打开界面时刷新一次（服务器没装就什么也不做）
     */
    public static void requestSync() {
        if (!ServerRuleClientState.isAvailable()) {
            return;
        }
        ClientPlayNetworking.send(ServerRuleRequestPayload.INSTANCE);
    }

    /**
     * 把某条规则设成指定状态（等价于 /ec <规则> <状态>）
     */
    public static void sendState(RuleType rule, String state) {
        if (!ServerRuleClientState.isAvailable()) {
            LOGGER.warn("服务器未安装 villagerdetails，忽略规则修改请求：{}", rule.getRegisterName());
            return;
        }
        ClientPlayNetworking.send(new ServerRuleSetPayload(rule.getRegisterName(), state, false));
    }

    /**
     * 恢复默认值
     */
    public static void sendReset(RuleType rule) {
        if (!ServerRuleClientState.isAvailable()) {
            LOGGER.warn("服务器未安装 villagerdetails，忽略规则重置请求：{}", rule.getRegisterName());
            return;
        }
        ClientPlayNetworking.send(new ServerRuleSetPayload(rule.getRegisterName(), "", true));
    }
}
