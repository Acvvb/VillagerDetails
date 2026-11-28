package com.villagerdetails.util.piston;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 原版活塞判定的<b>方块级</b>复刻，<b>两端共用</b>（服务端命令 + 客户端镜像都要用同一套答案）。
 *
 * <p>只回答一个问题：{@code /ec c} 里设置的值和这个方块的原版行为一致吗？一致就不用落库
 * （直接删条目，和「强充能 / 可采集」同一套理念）。
 *
 * <p><b>为什么放在 main 而不是 server</b>：客户端那份镜像也要知道「这个方块原版能不能被活塞搬动」，
 * 两端各写一份的话，一旦只改了一边，客户端会和服务端选到不同的结果 —— 现场表现是
 * 「方块卡一下再跳过去」。这里全是 MC 方块数据、没有配置/规则开关，客户端看得见，所以合成一份。
 *
 * <p><b>真正的判定不靠它</b>：注入点都是 {@code @At("RETURN")}，改的是原版对当前
 * 方块状态 / 坐标 / 方向算出的真实结果，所以这张表偏一点也不会让游戏行为跑偏，只影响
 * 「这条配置要不要存」。
 *
 * <p><b>两个入口</b>（{@link #isPushable} / {@link #isPullable}）：
 * {@code isPushable} 是原版那个判定本身；{@link #isPullable} 在它之外再加一道
 * {@code PushReaction} 闸「能推能拉」—— 原版缩回（黏回）那一段除了 {@code isPushable}
 * 还要求这一块反应可移动，反应是 {@code PUSH_ONLY} / {@code PUSH}（推得动但黏不住、
 * 也带不走）的方块会被拦下。命令侧用后者当「原版值」。
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
 * 共享代码引用任何一个都会让另一个版本编译不过。这里按 {@code name()} 判，
 * 两版都认（未知名字按「没有方块实体就可推」的默认分支处理）。
 *
 * <p>与原版有出入的只有「位置 / 方向」这类上下文条件（世界边界、上下限、可破坏开关、只推不拉），
 * 它们不影响这条命令的判断，只决定这条配置要不要落库。
 */
public final class VanillaPistonRules {

    private VanillaPistonRules() {
    }

    /**
     * 这个方块在原版里能不能被活塞<b>推动</b>（也就等于「能不能被活塞 / 粘性方块带着走」）。
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

    /**
     * 这个方块在原版里能不能被粘性活塞<b>黏回来</b>（缩回那一段）。
     *
     * <p>比 {@link #isPushable(Block)} 多一道闸：原版缩回分支要求反应是「能推能拉」
     * （26.2 {@code NORMAL} / 26.3 {@code PUSH_PULL}），反应是 {@code PUSH_ONLY} / {@code PUSH}
     * 的方块<b>推得动但黏不住</b>。少了这一条，{@code /ec c ... &lt;只推不拉的方块&gt; true}
     * 会被当成「和原版一致」直接删条目 —— 玩家配上去了、运行时却什么都没发生。
     *
     * <p>{@code DESTROY} 这类「一推就碎」的方块同理：{@code isPushable} 在
     * {@code allowDestroyable = false}（黏回那一条）时本来就给 false，这里一并归到不可黏。
     */
    public static boolean isPullable(Block block) {
        if (block == null) {
            // 和 isPushable(null) 保持一致：查不到方块时按「可推可拉」处理
            return true;
        }
        return isPushable(block)
                && block.defaultBlockState().getPistonPushReaction() == PistonReactionCompat.movable();
    }
}
