package com.villagerdetails.network.sync.receiver;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerReceiver;
import com.villagerdetails.network.sync.payload.serverbound.RuleDataPayload;
import com.villagerdetails.network.sync.clientbound.BlockRuleSyncChannel;
import com.villagerdetails.rule.registry.RuleDataRegistry;
import com.villagerdetails.rule.type.RuleDataKind;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端改「服务器配置项」的逐条数据：等价于 {@code /ec c <规则> ...}。
 *
 * <p>包的收发定义在 {@code network.sync.serverbound.RuleDataChannel}，这里只管收到之后干什么。
 * 放在 {@code network.sync.receiver} 包下 = 自动注册。
 *
 * <p>支持哪些规则、值怎么校验，全部查 {@link RuleDataRegistry}（和同步用的是同一张表），
 * 所以客户端只要照着同步下来的标识渲染，服务端这边就不会漏校验。
 * 改完广播一次方块规则同步，所有客户端界面跟着刷新。
 */
public final class RuleDataReceiver implements ServerReceiver<RuleDataPayload> {

    private static final Logger LOGGER = LogManager.getLogger(RuleDataReceiver.class);

    public static final RuleDataReceiver INSTANCE = new RuleDataReceiver();

    private RuleDataReceiver() {
    }

    @Override
    public PayloadId<RuleDataPayload> id() {
        return RuleDataPayload.ID;
    }

    @Override
    public void handle(ServerPlayer player, RuleDataPayload payload) {
        if (player == null) {
            return;
        }

        if (RuleCache.lacksRuleCommandPermission(player)) {
            player.sendSystemMessage(Component.translatable("villagerdetails.ui.rule.no_permission"));
            LOGGER.warn("玩家 {} 尝试修改规则 {} 的数据但没有权限",
                    player.getName().getString(), payload.registerName());
            return;
        }

        RuleType type = RuleType.getRuleTypeByRegisterName(payload.registerName());
        RuleDataRegistry.Entry entry = type == null ? null : RuleDataRegistry.of(type);
        if (entry == null) {
            LOGGER.warn("收到不支持逐条配置的规则的数据修改请求：{}", payload.registerName());
            return;
        }

        if (!apply(entry, payload)) {
            LOGGER.warn("规则 {} 的数据修改请求非法：id={} value={} remove={} reset={}",
                    payload.registerName(), payload.id(), payload.value(), payload.remove(), payload.reset());
            return;
        }

        BlockRuleSyncChannel.INSTANCE.broadcastAll();
    }

    /**
     * 把请求落到对应的配置上。
     *
     * @return 成功返回 true；id / 值非法返回 false
     */
    private static boolean apply(RuleDataRegistry.Entry entry, RuleDataPayload payload) {
        RuleDataKind kind = entry.kind();

        if (payload.reset()) {
            entry.store().register().run();
            return true;
        }

        String rawId = payload.id() == null ? "" : payload.id().trim();
        if (!kind.isValidId(rawId)) {
            return false;
        }
        Identifier id = Identifier.tryParse(rawId);
        if (id == null) {
            return false;
        }

        if (payload.remove()) {
            entry.store().remover().accept(id);
            return true;
        }

        String value = payload.value() == null ? "" : payload.value().trim();
        if (!kind.isValidValue(value)) {
            return false;
        }
        entry.store().setter().accept(id, kind.normalizeValue(value));
        return true;
    }
}
