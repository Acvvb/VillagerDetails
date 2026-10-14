package com.villagerdetails.client.network.sync;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.sync.clientbound.BlockRuleSyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.BlockRuleSyncPayload;
import com.villagerdetails.rule.registry.RuleDataRegistry;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.client.Minecraft;

/**
 * 逐条配置内容的客户端侧：只负责把内容写进本地镜像，供客户端 {@code getDestroyProgress}
 * 这类游戏逻辑使用（走 {@link RuleDataRegistry}，两边同一张表）。
 *
 * <p><b>配置界面不读这里</b> —— 「服务器 / 服务端配置」两个分类的内容来自
 * {@code rule_descriptor_sync} 的全量描述符（{@code ServerRuleClientState}）。
 * 这份包留着是因为本地预测对谁都一样要做。
 */
public final class BlockRuleClientChannel extends BlockRuleSyncChannel
        implements ClientReceiver<BlockRuleSyncPayload> {

    public static final BlockRuleClientChannel INSTANCE = new BlockRuleClientChannel();

    private BlockRuleClientChannel() {
    }

    @Override
    public void handleClient(Minecraft client, BlockRuleSyncPayload payload) {
        for (BlockRuleSyncPayload.RuleData entry : payload.ruleData()) {
            RuleType type = RuleType.getRuleTypeByRegisterName(entry.registerName());
            if (type == null) {
                continue;
            }
            // 写进本地镜像（落盘由服务端负责，客户端这边只覆盖内存）
            RuleDataRegistry.applySynced(entry.registerName(), entry.data());
            // 开关状态也照服务端给的写：客户端的本地预测要用
            // （比如「无挤压」关掉之后客户端也不该再拦推动）。
            // 只在「服务端说关了」或「本地还以为是关的」时动，避免把 ops 这类权限等级覆盖成 true。
            if (!entry.enabled() || !RuleCache.isEnabled(type)) {
                RuleCache.applyClientMirror(type, Boolean.toString(entry.enabled()));
            }
        }
    }
}
