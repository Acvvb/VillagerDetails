package com.villagerdetails.client.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import com.villagerdetails.client.cache.base.ClientRuleBlockSet;
import net.minecraft.world.level.block.Block;

/**
 * 「粘性活塞黏回控制」规则在本地的镜像：<b>与原版判定不同的方块集合</b>（只管缩回那一段）。
 *
 * <p>和 {@link PistonPushRuleCache} 完全对称，数据同样从描述符全量同步里取
 * （见 {@link ClientRuleBlockSet}）。
 *
 * <p>断线清理由 {@code ClientSessionStates} 扫包自动接管（本类实现 {@link SessionState}）。
 */
public final class StickyPistonPullRuleCache implements SessionState {

    /**
     * 服务端那条规则的注册名（{@code RuleType.CUSTOM_STICKY_PISTON_PULL_BLOCK} 的第一个构造参数）。
     *
     * <p>这里只能写死字符串：{@code RuleType} 在 <b>server</b> 源集里，client 编译期看不见它。
     * 改注册名时必须和 {@code RuleType} 里那一行一起改（那边是唯一事实来源）。
     */
    public static final String RULE_NAME = "CustomStickyPistonPullBlock";

    /**
     * 单例：扫包框架靠它拿实例，调用方统一写 {@code StickyPistonPullRuleCache.INSTANCE.xxx()}
     */
    public static final StickyPistonPullRuleCache INSTANCE = new StickyPistonPullRuleCache();

    private final ClientRuleBlockSet blocks = new ClientRuleBlockSet(RULE_NAME);

    private StickyPistonPullRuleCache() {
    }

    /**
     * 这个方块要不要把原版「黏回」判定取反
     */
    public boolean flips(Block block) {
        return blocks.contains(block);
    }

    @Override
    public void clearSession() {
        blocks.clear();
    }
}
