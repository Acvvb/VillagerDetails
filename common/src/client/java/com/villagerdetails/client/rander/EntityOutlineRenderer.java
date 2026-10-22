package com.villagerdetails.client.rander;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * 「任意实体 → 描边盒」的公共画法。
 *
 * <p>把实体本帧插值后的碰撞箱算出来，再交给 {@link HighlightBoxes#draw} 画 12 条线。
 * 现在用在 EB 选中高亮（{@code EBSelectionHighlightRenderer}），但它<b>不认识任何实体类型</b> ——
 * 传进来一个 {@link Entity} 就画，以后要描船、描盔甲架、描村民加线都用这一份。
 *
 * <p><b>为什么要插值</b>：实体模型是按「上一 tick 位置 → 当前 tick 位置」插值画出来的，
 * 而碰撞箱每 tick 才更新一次。直接画 {@code getBoundingBox()} 就是 20 TPS 一跳，
 * 模型却顺滑，看着就像只有这个框在卡。所以和原版 F3+B 的实体碰撞箱一样 ——
 * 取本帧插值位置，把盒子整体挪过去。
 */
public final class EntityOutlineRenderer {

    /**
     * 描边盒相对实体表面的外扩量。
     *
     * <p>盒子贴着模型表面时会出现 Z-fighting（描边和方块/实体面共面闪烁），
     * 往外推一点点就稳了，肉眼看不出偏移。
     */
    public static final double OUTLINE_INFLATE = 0.05D;

    private EntityOutlineRenderer() {
    }

    /**
     * 画一个实体的描边盒（自己取本帧插值进度）。
     *
     * <p>同一帧里要画多个实体时用下面的重载，把 {@code partialTick} 取一次传进来更省。
     */
    public static void draw(Entity entity, int argb, float lineWidth) {
        draw(entity, currentPartialTick(), argb, lineWidth);
    }

    /**
     * 画一个实体的描边盒。
     *
     * @param partialTick 本帧插值进度（{@code Minecraft#getDeltaTracker()} 的 game time delta）
     */
    public static void draw(Entity entity, float partialTick, int argb, float lineWidth) {
        if (entity == null) {
            return;
        }
        HighlightBoxes.draw(interpolatedBox(entity, partialTick).inflate(OUTLINE_INFLATE), argb, lineWidth);
    }

    /**
     * 实体本帧插值后的碰撞箱（<b>未</b>外扩）。
     *
     * <p>想自己决定外扩量 / 自己画别的形状时用这个，别重复抄「move 一下」那行。
     */
    public static AABB interpolatedBox(Entity entity, float partialTick) {
        return entity.getBoundingBox()
                .move(entity.getPosition(partialTick).subtract(entity.position()));
    }

    /**
     * 当前帧的插值进度
     */
    public static float currentPartialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }
}
