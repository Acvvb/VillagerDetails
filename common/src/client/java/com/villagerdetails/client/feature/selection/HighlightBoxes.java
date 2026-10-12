package com.villagerdetails.client.feature.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 高亮线框盒的公共画法（EB 高亮 / BB 高亮 / 传送门高亮共用）。
 *
 * <p>这里只管<b>几何和线</b>：颜色、线宽、区域外扩一律由调用方当参数传进来，
 * 各自从 {@link com.villagerdetails.client.setting.type.ConfigEntryType} 取自己的配置项，
 * 所以两边的样式仍然是各调各的，抽出来的只是重复的画法。
 *
 * <p>走原版 gizmo 系统绘制并 {@code setAlwaysOnTop()}，隔着方块也可见。
 */
public final class HighlightBoxes {

    /**
     * 单个方块盒往外扩 1/16 格，避免与方块表面重合产生闪烁（固定值）。
     *
     * <p>区域框的外扩不在这里，改由 {@link #drawRegion} 的 {@code outset} 参数传入（可配置）。
     */
    public static final double BLOCK_OUTSET = 0.0625D;

    private HighlightBoxes() {
    }

    /**
     * 画一个现成的盒子。
     *
     * <p>实体高亮用这个：盒子里的坐标由调用方按帧插值算好，这里不再动它。
     */
    public static void draw(AABB box, int argb, float lineWidth) {
        draw(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, argb, lineWidth);
    }

    /**
     * 单个方块的高亮盒（自带 {@link #BLOCK_OUTSET} 外扩）
     */
    public static void drawBlock(BlockPos pos, int argb, float lineWidth) {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        draw(x - BLOCK_OUTSET, y - BLOCK_OUTSET, z - BLOCK_OUTSET,
                x + 1 + BLOCK_OUTSET, y + 1 + BLOCK_OUTSET, z + 1 + BLOCK_OUTSET, argb, lineWidth);
    }

    /**
     * 两个方块为顶点的长方体高亮盒。
     *
     * <p>外扩 {@code outset} 由调用方传入（走客户端配置项），调大可减弱它与角点方块框共面时的闪烁。
     */
    public static void drawRegion(BlockPos a, BlockPos b, int argb, double outset, float lineWidth) {
        double minX = Math.min(a.getX(), b.getX());
        double minY = Math.min(a.getY(), b.getY());
        double minZ = Math.min(a.getZ(), b.getZ());
        double maxX = Math.max(a.getX(), b.getX()) + 1;
        double maxY = Math.max(a.getY(), b.getY()) + 1;
        double maxZ = Math.max(a.getZ(), b.getZ()) + 1;
        draw(minX - outset, minY - outset, minZ - outset,
                maxX + outset, maxY + outset, maxZ + outset, argb, lineWidth);
    }

    /**
     * 用 12 条线画一个盒子
     */
    public static void draw(double x0, double y0, double z0,
                            double x1, double y1, double z1, int argb, float lineWidth) {
        // 底面 4 条
        line(x0, y0, z0, x1, y0, z0, argb, lineWidth);
        line(x1, y0, z0, x1, y0, z1, argb, lineWidth);
        line(x1, y0, z1, x0, y0, z1, argb, lineWidth);
        line(x0, y0, z1, x0, y0, z0, argb, lineWidth);
        // 顶面 4 条
        line(x0, y1, z0, x1, y1, z0, argb, lineWidth);
        line(x1, y1, z0, x1, y1, z1, argb, lineWidth);
        line(x1, y1, z1, x0, y1, z1, argb, lineWidth);
        line(x0, y1, z1, x0, y1, z0, argb, lineWidth);
        // 4 条竖棱
        line(x0, y0, z0, x0, y1, z0, argb, lineWidth);
        line(x1, y0, z0, x1, y1, z0, argb, lineWidth);
        line(x1, y0, z1, x1, y1, z1, argb, lineWidth);
        line(x0, y0, z1, x0, y1, z1, argb, lineWidth);
    }

    private static void line(double x0, double y0, double z0,
                             double x1, double y1, double z1, int argb, float lineWidth) {
        Gizmos.line(new Vec3(x0, y0, z0), new Vec3(x1, y1, z1), argb, lineWidth).setAlwaysOnTop();
    }
}
