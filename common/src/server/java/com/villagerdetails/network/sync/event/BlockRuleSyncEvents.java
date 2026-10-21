package com.villagerdetails.network.sync.event;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.channel.SyncEvents;
import com.villagerdetails.network.sync.clientbound.BlockRuleSyncChannel;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

/**
 * 逐条配置同步（{@link BlockRuleSyncChannel}）的事件：玩家加入下发、规则开关变化广播、命令树刷新。
 *
 * <p>放在 {@code network.sync.event} 包下 = 自动注册，不用手动登记。
 *
 * <p>逐条配置的<b>当前值</b>（玩家配了什么）跟着描述符同步 / {@link BlockRuleSyncChannel}
 * 原有的增量逻辑走，这里只管接线。
 *
 * <p>所有发送都经过 {@code SyncChannel#sendTo} → {@code canSendTo}（{@code ServerPlayNetworking.canSend}），
 * 客户端没装本 mod 时一个包都不会发出去。
 */
public final class BlockRuleSyncEvents implements SyncEvents {

    public static final BlockRuleSyncEvents INSTANCE = new BlockRuleSyncEvents();

    private BlockRuleSyncEvents() {
    }

    @Override
    public void registerEvents() {
        // 玩家加入时同步当前配置
        ServerPlayConnectionEvents.JOIN.register((handler, _, _) ->
                BlockRuleSyncChannel.INSTANCE.syncTo(handler.getPlayer()));

        // 逐条配置类规则开/关变化时广播。
        // 哪些规则算「逐条配置类」由 RuleType 自己声明（RuleConfigKind.LIST），
        // 这里只问 RuleCache 要那份不可变列表 —— 不在这里手写规则名单。
        RuleCache.addListener((type, _, _) -> {
            if (RuleCache.configRuleTypes().contains(type)) {
                BlockRuleSyncChannel.INSTANCE.broadcastAll();
            }
        });

        // 规则开关变化时刷新在线玩家的命令树，让 /ec c 子命令的补全即时更新（不用重进服务器）
        RuleCache.addListener((type, _, _) -> {
            if (type.getCommandObject() == null) {
                return;
            }
            // 静态引用只读一次并判空：停机放掉 server 之后，lambda 里再读一次就可能拿到 null
            MinecraftServer server = RuleCache.getServer();
            if (server == null) {
                return;
            }
            BlockRuleSyncChannel.INSTANCE.forEachPlayer(player ->
                    server.getCommands().sendCommands(player));
        });
    }
}
