package com.villagerdetails.handler.block.pistonPush;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.section.item.CustomPistonPushBlockConfig;
import net.minecraft.world.level.block.Block;

import static com.villagerdetails.rule.type.RuleType.CUSTOM_PISTON_PUSH_BLOCK;

/**
 * 活塞<b>推动</b>规则（伸出那一段）的过滤器：<b>规则开着</b>并且方块被配置过时，才把原版判定取反。
 *
 * <p>和「强充能 / 可采集 / 挖掘抗性」保持一致：先看规则开关、再看名单 ——
 * 关掉规则就等于对原版零影响，而名单本身可以留着，下次开规则继续用。
 *
 * <p><b>语义是「取反」而不是「强制某个值」</b>：名单只记「与原版不同」的方块，
 * 具体方向由原版自己决定 —— 原版能推的变不能推（可阻止），原版不能推的变能推（可强制）。
 * 这样一份 {@code IdSetConfig} 就够用，不需要再存 true / false。
 *
 * <p>取反发生在注入点（{@code @At("RETURN")}），拿到的就是原版对当前
 * 方块状态 / 坐标 / 方向算出的<b>真实结果</b>，所以判定结果永远跟着原版走，
 * 不存在「表和原版对不上」的问题。
 *
 * <p>只管<b>伸出</b>：缩回（黏回）由 {@link CustomStickyPistonPullBlockHandler} 负责，
 * 两者靠 {@link PistonMoveScope} 分流。
 */
public final class CustomPistonPushBlockHandler {

    private CustomPistonPushBlockHandler() {}

    /**
     * 这个方块要不要把原版判定取反。
     *
     * @return {@code null} = 不覆盖（名单外，原版怎么判就怎么判）；{@code true} = 取反
     */
    public static Boolean isConfigured(Block block) {
        if (!RuleCache.isEnabled(CUSTOM_PISTON_PUSH_BLOCK)) return null;
        return CustomPistonPushBlockConfig.INSTANCE.contains(block) ? Boolean.TRUE : null;
    }
}
