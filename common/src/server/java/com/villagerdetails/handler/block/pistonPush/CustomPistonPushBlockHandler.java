package com.villagerdetails.handler.block.pistonPush;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.section.item.CustomPistonPushBlockConfig;
import net.minecraft.world.level.block.Block;

import static com.villagerdetails.rule.type.RuleType.CUSTOM_PISTON_PUSH_BLOCK;

/**
 * 「活塞搬运控制」的过滤器：规则开着 + 方块在名单里 → 把原版对它的搬运判定<b>取反</b>。
 *
 * <p>一条规则管三件事，因为它们在原版里<del>本来就是同一个判定</del>是<b>同一个入口</b>
 * （{@code PistonBaseBlock#isPushable}）：
 * <ol>
 *     <li>活塞能不能<b>推动</b>它（伸出）；</li>
 *     <li>粘性活塞能不能把它<b>黏回来</b>（缩回）；</li>
 *     <li>蜂蜜块 / 史莱姆块能不能<b>黏住它、把它带走</b>（两个方向都会问）。</li>
 * </ol>
 * 原版这三件事都先问 {@code isPushable}：搬不动的方块既推不动、也黏不回来、更不会被粘性方块
 * 带着走。所以「取反」只落在这一处就同时覆盖了三件事 —— 不需要按方向分流，也不需要单独
 * 处理黏连判定（搬不动 → 进不了移动组 → 自然黏不住）。
 *
 * <p>另有一处必须一起管：{@code PistonBaseBlock#triggerEvent} 缩回分支里还有一道
 * {@code PushReaction} 闸（要求这一块的反应是「能推能拉」，26.2 {@code NORMAL} /
 * 26.3 {@code PUSH_PULL}），原版用它拦下「推得动但黏不住」的方块。不把这一道也放行，
 * 「让黑曜石能被粘性活塞黏回来」会在第一道闸通过之后又被挡回去 —— 见
 * {@code com.villagerdetails.mixin.block.pistonPush.PistonBaseBlockMixin}。
 *
 * <p>和「强充能 / 可采集」保持一致：先看规则开关、再看名单 —— 关掉规则等于对原版零影响，
 * 名单本身可以留着，下次开规则继续用。
 */
public final class CustomPistonPushBlockHandler {

    private CustomPistonPushBlockHandler() {}

    /**
     * 这个方块要不要把原版搬运判定取反。
     *
     * @return {@code null} = 不覆盖（名单外，原版怎么判就怎么判）；{@code true} = 取反
     */
    public static Boolean isConfigured(Block block) {
        if (!RuleCache.isEnabled(CUSTOM_PISTON_PUSH_BLOCK)) return null;
        return CustomPistonPushBlockConfig.INSTANCE.contains(block) ? Boolean.TRUE : null;
    }
}
