package com.villagerdetails.client.network.sync;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.client.setting.network.ServerRuleClientState;
import com.villagerdetails.network.channel.SyncProtocol;
import com.villagerdetails.network.sync.clientbound.RuleDescriptorSyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.RuleDescriptorSyncPayload;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 规则描述符同步的客户端侧：收包整份写进 {@link ServerRuleClientState}，界面跟着重建。
 *
 * <p>客户端在这里<b>不解析规则语义</b> —— 收到什么存什么，行 / 页签 / 详情页都照它渲染。
 */
public final class RuleDescriptorClientChannel extends RuleDescriptorSyncChannel
        implements ClientReceiver<RuleDescriptorSyncPayload> {

    private static final Logger LOGGER = LogManager.getLogger(RuleDescriptorClientChannel.class);

    public static final RuleDescriptorClientChannel INSTANCE = new RuleDescriptorClientChannel();

    /**
     * 上一次看到的客户端界面语言，用来发现「玩家在选项里改了语言」
     */
    private static String lastLanguage = "";

    private RuleDescriptorClientChannel() {
    }

    @Override
    public void handleClient(Minecraft client, RuleDescriptorSyncPayload payload) {
        if (payload.protocol() != SyncProtocol.VERSION) {
            LOGGER.warn("服务端的同步协议版本是 {}，客户端是 {}，忽略这次描述符同步",
                    payload.protocol(), SyncProtocol.VERSION);
            return;
        }
        // 顺带把状态写进客户端镜像：破坏进度 / 挤压判定这类游戏逻辑在客户端也要读它。
        // 只有客户端认识的规则写得进去（镜像按 RuleType 存）；不认识的规则本来也没有客户端逻辑要用。
        for (RuleDescriptor rule : payload.rules()) {
            RuleType type = RuleType.getRuleTypeByRegisterName(rule.registerName());
            if (type != null) {
                RuleCache.applyClientMirror(type, rule.state());
            }
        }
        // 先记内容再 bumpVersion：界面重建时会用这份新内容
        ServerRuleClientState.setDescriptors(payload.categories(), payload.rules());
        ServerRuleClientState.bumpVersion();
    }

    @Override
    public void registerClientEvents() {
        // 进服先打个招呼：服务端据此按我们的界面语言翻译描述符里的文字，并顺手推一份过来
        ClientPlayConnectionEvents.JOIN.register((_, _, _) -> ClientRequests.sendHello());

        // 玩家在选项里改了界面语言 → 重新打个招呼，让服务端按新语言重推一份。
        // （描述符里是已经翻译好的纯文本，不会像普通 Component 那样自己跟着语言变，所以得主动重来一次。）
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            String now = ClientRequests.currentLanguage();
            if (!now.equals(lastLanguage)) {
                lastLanguage = now;
                ClientRequests.sendHello();
            }
        });
    }
}
