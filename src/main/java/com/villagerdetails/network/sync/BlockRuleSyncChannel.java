package com.villagerdetails.network.sync;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.rule.RuleDataRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐条配置类规则的同步：服务端 → 客户端。
 *
 * <p>玩家加入时下发一次；规则开闭或配置变化时广播给所有在线玩家。
 * 下发内容按 {@link RuleDataRegistry} 生成（注册名 + 列表形态标识 + 内容），
 * 客户端照着标识渲染，不写死哪条规则是哪种。
 *
 * <p>包 id：{@code villagerdetails:block_rule_sync}
 */
public class BlockRuleSyncChannel extends SyncChannel<BlockRuleSyncPayload> {

    public static final BlockRuleSyncChannel INSTANCE = new BlockRuleSyncChannel();

    protected BlockRuleSyncChannel() {
        super(BlockRuleSyncPayload.ID, BlockRuleSyncPayload.CODEC);
    }

    @Override
    public void registerEvents() {
        // 玩家加入时同步当前配置
        ServerPlayConnectionEvents.JOIN.register((handler, _, _) -> syncTo(handler.getPlayer()));

        // 这些规则开/关变化时广播
        RuleCache.addListener((type, _, _) -> {
            switch (type) {
                case BLOCK_MINING_RESISTANCE, BLOCK_COLLECTABLE,
                     BLOCK_EXPLOSION_RESISTANCE, VILLAGER_NO_SQUEEZE -> broadcastAll();
                default -> {
                }
            }
        });

        // 规则开关变化时刷新在线玩家的命令树，让 /ec c 子命令的补全即时更新（不用重进服务器）
        RuleCache.addListener((type, _, _) -> {
            if (type.getCommandObject() == null) {
                return;
            }
            forEachPlayer(player -> RuleCache.getServer().getCommands().sendCommands(player));
        });
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
