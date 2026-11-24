package com.villagerdetails.handler.block.pistonPush;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.section.item.CustomPistonPushBlockConfig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

import static com.villagerdetails.rule.type.RuleType.CUSTOM_PISTON_PUSH_BLOCK;

/**
 * 活塞推动规则的过滤器：<b>规则开着</b>并且方块被配置过时，才把原版判定取反。
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

    /**
     * 这个方块在<b>原版</b>里能不能被活塞推动（方块级判定）。
     *
     * <p>只用来回答一个问题：{@code /ec c} 里设置的值和原版一致吗？一致就不用落库（直接删条目）。
     * <b>真正的判定不靠它</b> —— 注入点是 {@code @At("RETURN")}，取反的是原版对当前上下文的真实结果。
     *
     * <p>逐条对着原版 {@code PistonBaseBlock#isPushable} 的字节码写的，方块级能确定的部分照抄：
     * <ol>
     *     <li>空气 → 可推（原版要活塞能推进空气里）；</li>
     *     <li>黑曜石 / 哭泣的黑曜石 / 重生锚 / 强化深板岩 → 不可推；</li>
     *     <li>活塞 / 粘性活塞 → 看 {@code EXTENDED}（伸出时不可推，默认状态是缩回 = 可推）；</li>
     *     <li>破坏时间 &lt; 0（原版不可破坏）→ 不可推；</li>
     *     <li>其余看 {@code PushReaction}：不可移动 → 不可推；没有方块实体 → 可推。</li>
     * </ol>
     *
     * <p><b>{@code PushReaction} 为什么按名字比</b>：26.2 的常量叫 {@code BLOCK} / {@code NORMAL}，
     * 26.3 改成了 {@code IMMOVEABLE} / {@code PUSH_PULL}，两版的枚举常量<b>互相不存在</b>，
     * common 里的共享代码引用任何一个都会让另一个版本编译不过。这里按 {@code name()} 判，
     * 两版都认（未知名字按「没有方块实体就可推」的默认分支处理）。
     *
     * <p>与原版有出入的只有「位置 / 方向」这类上下文条件（世界边界、上下限、可破坏开关、只推不拉），
     * 它们不影响这条命令的判断，只决定这条配置要不要落库。
     */
    public static boolean vanillaPushable(Block block) {
        if (block == null) {
            return true;
        }
        BlockState state = block.defaultBlockState();
        if (state.isAir()) {
            return true;
        }
        // 26.2 的原版把这些方块写死在 isPushable 里；26.3 改成走 IMMOVEABLE 反应，这里两种都覆盖
        if (block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN
                || block == Blocks.RESPAWN_ANCHOR || block == Blocks.REINFORCED_DEEPSLATE) {
            return false;
        }
        if (block == Blocks.PISTON || block == Blocks.STICKY_PISTON) {
            return !state.getValue(PistonBaseBlock.EXTENDED);
        }
        if (block.defaultDestroyTime() < 0f) {
            return false;
        }
        String reaction = state.getPistonPushReaction().name();
        if ("BLOCK".equals(reaction) || "IMMOVEABLE".equals(reaction)) {
            return false;
        }
        return !state.hasBlockEntity();
    }
}
