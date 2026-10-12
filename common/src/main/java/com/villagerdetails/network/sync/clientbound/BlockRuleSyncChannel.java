package com.villagerdetails.network.sync.clientbound;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.BlockRuleSyncPayload;
import com.villagerdetails.rule.registry.RuleDataRegistry;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐条配置类规则的同步：服务端 → 客户端。
 *
 * <p>放在 {@code network.sync.clientbound} 包下 = 自动注册成 S→C 包类型，不用手动登记。
 * 「玩家加入时下发一次」「规则开闭 / 配置变化时广播」「规则变化时刷新命令树」这些接线
 * 在 {@code network.sync.event.BlockRuleSyncEvents}。
 *
 * <p>下发内容按 {@link RuleDataRegistry} 生成（注册名 + 列表形态标识 + 内容），
 * 客户端照着标识渲染，不写死哪条规则是哪种。
 *
 * <p>包 id：{@code villagerdetails:block_rule_sync}
 */
public class BlockRuleSyncChannel extends SyncChannel<BlockRuleSyncPayload> {

    public static final BlockRuleSyncChannel INSTANCE = new BlockRuleSyncChannel();

    protected BlockRuleSyncChannel() {
        super(BlockRuleSyncPayload.ID);
    }

    /**
     * 把这个玩家该看到的配置推给他
     */
    public void syncTo(ServerPlayer player) {
        if (!canSendTo(player)) {
            return;
        }
        sendTo(player, build());
    }

    /**
     * 广播当前配置（配置变了 / 规则开关变了都调它）
     */
    public void broadcastAll() {
        broadcast(build());
    }

    /**
     * 按规则表拼一份当前配置。
     *
     * <p>每条规则带上自己的开关状态：规则状态那份同步是按权限过滤的，没权限的玩家收不到，
     * 但客户端要拿它做本地预测（比如「无挤压」关掉之后客户端也不该再拦推动）。
     */
    private BlockRuleSyncPayload build() {
        List<BlockRuleSyncPayload.RuleData> entries = new ArrayList<>(RuleDataRegistry.all().size());
        for (RuleDataRegistry.Entry entry : RuleDataRegistry.all()) {
            entries.add(new BlockRuleSyncPayload.RuleData(
                    entry.rule().getRegisterName(),
                    entry.kind().name(),
                    entry.store().serializer().get(),
                    RuleCache.isEnabled(entry.rule())));
        }
        return new BlockRuleSyncPayload(List.copyOf(entries));
    }
}
