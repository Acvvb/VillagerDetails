package com.villagerdetails.util.piston;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.PushReaction;

/**
 * 跨版本取「能推也能拉」的那个 {@link PushReaction} 常量（<b>两端共用</b>）。
 *
 * <p>为什么需要它：原版「粘性活塞能不能把方块黏回来」除了 {@code PistonBaseBlock#isPushable}
 * 之外，<b>还有第二道闸</b> —— {@code PistonBaseBlock#triggerEvent} 的缩回分支里要求
 * {@code state.getPistonPushReaction()} 等于「能推能拉」那个常量（26.2 叫 {@code NORMAL}，
 * 26.3 改名成 {@code PUSH_PULL}）。两个常量<b>互相不存在</b>：common 里直接引用任何一个，
 * 另一个版本就编译不过，所以这里按 {@link PushReaction#name()} 找（和
 * {@code VanillaPistonRules} 里判 {@code BLOCK / IMMOVEABLE} 是同一套理由）。
 *
 * <p>只给「把方块强制成能被黏回」这条路径用：名单里的方块过了第一道闸之后，
 * 第二道闸也要按「正常可搬动」放行，否则规则只在 {@code isPushable} 上生效、
 * 到 {@code triggerEvent} 又被原版挡回去（<b>改之前就是不生效的那个原因</b>）。
 *
 * <p>取不到常量时（理论上不会）退回「石头」的反应值：石头就是原版里最标准的
 * 「能推能拉」方块，它的反应必然等于原版判定用的那个常量。做成懒加载是为了不在
 * 类初始化链上碰 {@link Blocks}。
 */
public final class PistonReactionCompat {

    /**
     * 懒加载缓存：第一次判定时算一次，之后就是一次 volatile 读
     */
    private static volatile PushReaction movable;

    private PistonReactionCompat() {
    }

    /**
     * 「能推也能拉」的 {@link PushReaction} 常量（26.2 = {@code NORMAL}，26.3 = {@code PUSH_PULL}）。
     */
    public static PushReaction movable() {
        PushReaction cached = movable;
        if (cached == null) {
            cached = lookupMovable();
            movable = cached;
        }
        return cached;
    }

    private static PushReaction lookupMovable() {
        for (PushReaction reaction : PushReaction.values()) {
            String name = reaction.name();
            if ("PUSH_PULL".equals(name) || "NORMAL".equals(name)) {
                return reaction;
            }
        }
        return Blocks.STONE.defaultBlockState().getPistonPushReaction();
    }
}
