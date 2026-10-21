package com.villagerdetails.client.network.sync;

import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.sync.payload.clientbound.BlockRuleSyncPayload;
import net.minecraft.client.Minecraft;

/**
 * 逐条配置内容的客户端侧。
 *
 * <p>三端分离之后客户端不再跑任何规则判定 / 本地预测（那些 Mixin 已随服务端逻辑移入
 * {@code server} 源集），所以这份「逐条配置镜像」在客户端已无读取方：收到包只做记录级别的忽略。
 *
 * <p>保留这个收包注册是<b>有意的</b>：Fabric 的 {@code ServerPlayNetworking.canSend(player, type)}
 * 取决于客户端有没有登记同名通道，服务端侧的 {@code canSendTo}/{@code syncTo} 会据此决定要不要下发。
 * 这里不注册会让服务端误以为对面是原版客户端，从而改变服务端行为。
 */
public final class BlockRuleClientChannel implements ClientReceiver<BlockRuleSyncPayload> {

    public static final BlockRuleClientChannel INSTANCE = new BlockRuleClientChannel();

    private BlockRuleClientChannel() {
    }

    @Override
    public PayloadId<BlockRuleSyncPayload> id() {
        return BlockRuleSyncPayload.ID;
    }

    @Override
    public void handleClient(Minecraft client, BlockRuleSyncPayload payload) {
        // 客户端没有规则判定逻辑，不需要镜像；配置界面用的数据来自 rule_descriptor_sync。
    }
}
