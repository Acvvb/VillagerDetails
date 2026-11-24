package com.villagerdetails.handler.block.pistonPush;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.section.item.CustomStickyPistonPullBlockConfig;
import net.minecraft.world.level.block.Block;

import static com.villagerdetails.rule.type.RuleType.CUSTOM_STICKY_PISTON_PULL_BLOCK;

/**
 * 活塞<b>黏回</b>规则（缩回那一段）的过滤器。
 *
 * <p>和 {@link CustomPistonPushBlockHandler} 完全对称：规则开着 + 方块被配置过 → 把原版
 * 「能不能被粘性活塞黏回来」的结果取反。原版这一段走的也是 {@code PistonBaseBlock#isPushable}，
 * 没有独立的「能不能被黏」判定，所以两条规则靠 {@link PistonMoveScope} 分流：
 * 伸出用推动规则，缩回用黏回规则。
 *
 * <p>关掉规则等于对原版零影响，名单留着下次开规则继续用。
 */
public final class CustomStickyPistonPullBlockHandler {

    private CustomStickyPistonPullBlockHandler() {}

    /**
     * 这个方块要不要把原版「黏回」判定取反。
     *
     * @return {@code null} = 不覆盖（名单外，原版怎么判就怎么判）；{@code true} = 取反
     */
    public static Boolean isConfigured(Block block) {
        if (!RuleCache.isEnabled(CUSTOM_STICKY_PISTON_PULL_BLOCK)) return null;
        return CustomStickyPistonPullBlockConfig.INSTANCE.contains(block) ? Boolean.TRUE : null;
    }
}
