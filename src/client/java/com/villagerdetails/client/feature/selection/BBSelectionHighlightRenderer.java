package com.villagerdetails.client.feature.selection;

import com.villagerdetails.client.cache.BBSelectionClientState;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.util.ColorUtil;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/**
 * 「BB 选择器高亮」的客户端渲染。
 *
 * <ul>
 *     <li>只选中一个角点 → 用该角点的颜色高亮那个方块（和 EB 高亮同款）</li>
 *     <li>两个角点都选中 → 各自高亮，再用「总颜色」把两个方块为顶点的长方体框出来</li>
 * </ul>
 *
 * <p>开关和三种颜色都由 {@link ConfigEntryType} 决定。走 gizmo 绘制并
 * {@code setAlwaysOnTop()}，隔着方块也可见。
 */
public final class BBSelectionHighlightRenderer {

    /**
     * 高亮线宽（固定值）
     */
    private static final float LINE_WIDTH = 2.0F;

    /**
     * 方块盒往外扩 1/16 格，避免与方块表面重合产生闪烁
     */
    private static final double OUTSET = 0.0625D;

    private BBSelectionHighlightRenderer() {
    }

    /**
     * 由客户端初始化入口调用一次
     */
    public static void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(_ -> render());
    }

    private static void render() {
        if (!ConfigEntryType.RENDER_BB_HIGHLIGHT.getToggle()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        BlockPos p1 = BBSelectionClientState.pos1();
        BlockPos p2 = BBSelectionClientState.pos2();
        if (p1 == null && p2 == null) {
            return;
        }

        if (p1 != null) {
            int argb = ColorUtil.toArgb(ConfigEntryType.BB_POS1_COLOR.getColor());
            drawBlockBox(p1, argb);
        }
        if (p2 != null) {
            int argb = ColorUtil.toArgb(ConfigEntryType.BB_POS2_COLOR.getColor());
            drawBlockBox(p2, argb);
        }
        // 两个都选中 → 用总颜色把整个区域框出来
        if (p1 != null && p2 != null) {
            int argb = ColorUtil.toArgb(ConfigEntryType.BB_BOX_COLOR.getColor());
            drawRegionBox(p1, p2, argb);
        }
    }

    /**
     * 单个方块的高亮盒
     */
    private static void drawBlockBox(BlockPos pos, int argb) {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        drawBox(x - OUTSET, y - OUTSET, z - OUTSET,
                x + 1 + OUTSET, y + 1 + OUTSET, z + 1 + OUTSET, argb);
    }

    /**
     * 两个方块为顶点的长方体高亮盒
     */
    private static void drawRegionBox(BlockPos a, BlockPos b, int argb) {
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX()) + 1;
        int maxY = Math.max(a.getY(), b.getY()) + 1;
        int maxZ = Math.max(a.getZ(), b.getZ()) + 1;
        drawBox(minX - OUTSET, minY - OUTSET, minZ - OUTSET,
                maxX + OUTSET, maxY + OUTSET, maxZ + OUTSET, argb);
    }

    private static void drawBox(double x0, double y0, double z0,
                                double x1, double y1, double z1, int argb) {
        // 底面
        line(x0, y0, z0, x1, y0, z0, argb);
        line(x1, y0, z0, x1, y0, z1, argb);
        line(x1, y0, z1, x0, y0, z1, argb);
        line(x0, y0, z1, x0, y0, z0, argb);
        // 顶面
        line(x0, y1, z0, x1, y1, z0, argb);
        line(x1, y1, z0, x1, y1, z1, argb);
        line(x1, y1, z1, x0, y1, z1, argb);
        line(x0, y1, z1, x0, y1, z0, argb);
        // 竖棱
        line(x0, y0, z0, x0, y1, z0, argb);
        line(x1, y0, z0, x1, y1, z0, argb);
        line(x1, y0, z1, x1, y1, z1, argb);
        line(x0, y0, z1, x0, y1, z1, argb);
    }

    private static void line(double x0, double y0, double z0,
                             double x1, double y1, double z1, int argb) {
        Gizmos.line(new Vec3(x0, y0, z0), new Vec3(x1, y1, z1), argb, LINE_WIDTH).setAlwaysOnTop();
    }
}