package com.villagerdetails.client.network.sync;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.client.setting.network.ServerRuleClientState;
import com.villagerdetails.network.sync.payload.clientbound.ServerRuleSyncPayload;
import com.villagerdetails.network.sync.clientbound.ServerRuleSyncChannel;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则状态同步的客户端侧：收包写进本地镜像（{@link RuleCache}）+ 记录「有权限看的规则清单」。
 *
 * <p>清单是「服务器分类要不要显示」「服务器配置项里放哪些规则」的依据。
 */
public final class ServerRuleClientChannel extends ServerRuleSyncChannel
        implements ClientReceiver<ServerRuleSyncPayload> {

    public static final ServerRuleClientChannel INSTANCE = new ServerRuleClientChannel();

    private ServerRuleClientChannel() {
    }

    @Override
    public void handleClient(Minecraft client, ServerRuleSyncPayload payload) {
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
    }

    @Override
    public void registerClientEvents() {
        // 断开连接时清掉，免得下次进别的服务器还留着上一份
        ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> {
            ServerRuleClientState.setAvailable(false);
            ServerRuleClientState.clearSyncedRules();
            ServerRuleClientState.clearRuleData();
        });
    }
}
