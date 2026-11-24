package com.villagerdetails.handler.block.pistonPush;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 原版活塞判定的<b>方块级</b>复刻，两条活塞规则共用。
 *
 * <p>只回答一个问题：{@code /ec c} 里设置的值和这个方块的原版行为一致吗？一致就不用落库
 * （直接删条目，和「强充能 / 可采集」同一套理念）。
 *
 * <p><b>真正的判定不靠它</b>：注入点都是 {@code @At("RETURN")}，取反的是原版对当前
 * 方块状态 / 坐标 / 方向算出的真实结果，所以这张表偏一点也不会让游戏行为跑偏，只影响
 * 「这条配置要不要存」。
 *
 * <p><b>「推动」和「黏回」在方块级是同一个判定</b>：原版缩回（黏回）那一段走的也是
 * {@code PistonBaseBlock#isPushable}（{@code PistonStructureResolver} 里 4 个调用点全都在这个类里），
 * 没有单独的「能不能被黏」判定。所以两条规则共用本类，靠注入时的「伸出 / 缩回」分流。
 *
 * <p>逐条对着原版字节码写，方块级能确定的部分照抄：
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
public final class VanillaPistonRules {

    private VanillaPistonRules() {
    }

    /**
     * 这个方块在原版里能不能被活塞挪动（推动和黏回同源）。
     */
    public static boolean isPushable(Block block) {
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
