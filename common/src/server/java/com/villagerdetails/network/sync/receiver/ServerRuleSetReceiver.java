package com.villagerdetails.network.sync.receiver;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerReceiver;
import com.villagerdetails.network.sync.clientbound.RuleDescriptorSyncChannel;
import com.villagerdetails.network.sync.payload.serverbound.ServerRuleSetPayload;
import com.villagerdetails.rule.RuleChangeService;
import com.villagerdetails.rule.RuleSwitches;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.villagerdetails.command.CommandConstants.RULE_ERROR_INVALID_OPTION;
import static com.villagerdetails.command.CommandConstants.RULE_ERROR_INVALID_STATE;

/**
 * 客户端改规则状态：等价于 {@code /ec <规则> <状态>}。
 *
 * <p>包的收发定义在 {@link ServerRuleSetPayload}，这里只管收到之后干什么。
 * 放在 {@code network.sync.receiver} 包下 = 自动注册。
 *
 * <p>权限只看 {@code /ec} 那道总权限，和界面显示、命令用的是同一套判定。
 */
public final class ServerRuleSetReceiver implements ServerReceiver<ServerRuleSetPayload> {

    private static final Logger log = LogManager.getLogger(ServerRuleSetReceiver.class);

    public static final ServerRuleSetReceiver INSTANCE = new ServerRuleSetReceiver();

    private ServerRuleSetReceiver() {
    }

    @Override
    public PayloadId<ServerRuleSetPayload> id() {
        return ServerRuleSetPayload.ID;
    }

    @Override
    public void handle(ServerPlayer player, ServerRuleSetPayload payload) {
        if (player == null) {
            return;
        }

        RuleType type = RuleType.getRuleTypeByRegisterName(payload.registerName());
        if (type == null) {
            log.warn("收到未知规则的状态修改请求：{}", payload.registerName());
            return;
        }

        // 和 /ec 命令同一套权限判定
        if (RuleCache.lacksRuleCommandPermission(player)) {
            player.sendSystemMessage(Component.translatable("villagerdetails.ui.rule.no_permission"));
            log.warn("玩家 {} 尝试修改规则 {} 但没有权限", player.getName().getString(), type.getRegisterName());
            return;
        }

        // ★ 核心修改：用发请求的玩家构造一个命令源，传给 RuleChangeService
        CommandSourceStack source = player.createCommandSourceStack();

        // 和 /ec 命令同一套校验与写入（走 RuleChangeService，命令那边也是它）
        RuleSwitches.Canonical result = payload.reset()
                ? RuleChangeService.reset(type, source)
                : RuleChangeService.change(type, payload.state(), source);

        if (result instanceof RuleSwitches.Canonical.BadState(String raw)) {
            player.sendSystemMessage(Component.translatable(RULE_ERROR_INVALID_STATE, raw));
            log.warn("玩家 {} 给规则 {} 传了非法状态：{}",
                    player.getName().getString(), type.getRegisterName(), raw);
            return;
        }
        if (result instanceof RuleSwitches.Canonical.BadOption(String option)) {
            player.sendSystemMessage(Component.translatable(RULE_ERROR_INVALID_OPTION, option));
            log.warn("玩家 {} 给规则 {} 传了非法选项：{}",
                    player.getName().getString(), type.getRegisterName(), option);
            return;
        }

        // setState 会触发监听器广播，这里再补一次，保证发起者立刻看到最新状态
        // （内容没变时被 lastSynced 去重，不发包）
        RuleDescriptorSyncChannel.INSTANCE.syncTo(player);
    }
}