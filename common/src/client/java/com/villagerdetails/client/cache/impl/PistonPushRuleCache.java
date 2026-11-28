package com.villagerdetails.client.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import com.villagerdetails.client.cache.base.ClientRuleBlockSet;
import net.minecraft.world.level.block.Block;

/**
 * 「活塞搬运控制」规则在本地的镜像：<b>与原版判定不同的方块集合</b>
 * （推、拉、被蜂蜜块 / 史莱姆块带走，三件事共用这一份）。
 *
 * <p>数据从描述符全量同步里取（见 {@link ClientRuleBlockSet}），不订阅 {@code block_rule_sync} ——
 * 那条是会被客户端静音的业务通道，拿它当判定依据，玩家一关掉开关本地判定就静默失效。
 *
 * <p>断线清理由 {@code ClientSessionStates} 扫包自动接管（本类实现 {@link SessionState}）。
 */
public final class PistonPushRuleCache implements SessionState {

    /**
     * 服务端那条规则的注册名（{@code RuleType.CUSTOM_PISTON_PUSH_BLOCK} 的第一个构造参数）。
     *
     * <p>这里只能写死字符串：{@code RuleType} 在 <b>server</b> 源集里，client 编译期看不见它。
     * 改注册名时必须和 {@code RuleType} 里那一行一起改（那边是唯一事实来源）。
     */
    public static final String RULE_NAME = "CustomPistonPushBlock";

    /**
     * 单例：扫包框架靠它拿实例，调用方统一写 {@code PistonPushRuleCache.INSTANCE.xxx()}
     */
    public static final PistonPushRuleCache INSTANCE = new PistonPushRuleCache();

    private final ClientRuleBlockSet blocks = new ClientRuleBlockSet(RULE_NAME);

    private PistonPushRuleCache() {
    }

    /**
     * 这个方块要不要把原版「推动」判定取反
     */
    public boolean flips(Block block) {
        return blocks.contains(block);
    }

    @Override
    public void clearSession() {
        blocks.clear();
    }
}
