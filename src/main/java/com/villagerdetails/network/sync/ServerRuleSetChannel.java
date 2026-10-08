package com.villagerdetails.network.sync;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.rule.RuleSwitches;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端改规则状态：客户端 → 服务端（等价于 {@code /ec <规则> <状态>}）。
 *
 * <p>权限只看 {@code /ec} 那道总权限，和界面显示、命令用的是同一套判定。
 *
 * <p>包 id：{@code villagerdetails:server_rule_set}
 */
public final class ServerRuleSetChannel extends SyncChannel<ServerRuleSetPayload> {

    private static final Logger LOGGER = LogManager.getLogger(ServerRuleSetChannel.class);

    public static final ServerRuleSetChannel INSTANCE = new ServerRuleSetChannel();

    private ServerRuleSetChannel() {
        super(ServerRuleSetPayload.ID, ServerRuleSetPayload.CODEC);
    }

    @Override
    protected void handleServer(ServerPlayer player, ServerRuleSetPayload payload) {
        if (player == null) {
            return;
        }

        RuleType type = RuleType.getRuleTypeByRegisterName(payload.registerName());
        if (type == null) {
            LOGGER.warn("收到未知规则的状态修改请求：{}", payload.registerName());
            return;
        }

        // 和 /ec 命令同一套权限判定
        if (RuleCache.lacksRuleCommandPermission(player)) {
            player.sendSystemMessage(Component.translatable("villagerdetails.ui.rule.no_permission"));
            LOGGER.warn("玩家 {} 尝试修改规则 {} 但没有权限", player.getName().getString(), type.getRegisterName());
            return;
        }

        String canonical;
        if (payload.reset()) {
            canonical = type.getState();
        } else {
            // 和命令同一套校验：预定义列表必须匹配，自定义列表放行，none 归一化成空
            canonical = RuleSwitches.canonicalize(type, payload.state());
            if (canonical == null) {
                player.sendSystemMessage(Component.translatable(
                        "command.entity_binder.error.invalid_state", payload.state()));
                LOGGER.warn("玩家 {} 给规则 {} 传了非法状态：{}",
                        player.getName().getString(), type.getRegisterName(), payload.state());
                return;
            }
        }

        RuleCache.setState(type, canonical);

        // setState 会触发监听器广播，这里再补一次，保证发起者立刻看到最新状态
        ServerRuleSyncChannel.INSTANCE.syncTo(player);
    }
}
