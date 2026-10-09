package com.villagerdetails.client.feature.selection;

import com.villagerdetails.client.cache.EBSelectionClientState;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.util.ColorUtil;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 「选中高亮」的客户端渲染：把服务端同步过来的实体 / 方块用一个线框盒罩住。
 *
 * <p>开关和颜色都由 {@link ConfigEntryType} 决定。走 gizmo 系统绘制并
 * {@code setAlwaysOnTop()}，隔着方块也可见。
 */
public final class SelectionHighlightRenderer {

    /**
     * 高亮线宽（固定值，不单独开配置项）
     */
    private static final float LINE_WIDTH = 2.0F;

    private SelectionHighlightRenderer() {
    }

    public static void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(_ -> render());
    }

    private static void render() {
        if (!ConfigEntryType.RENDER_SELECTION_HIGHLIGHT.getToggle()) {
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        renderSelectedBlock();
        renderSelectedEntity(level);
    }

    private static void renderSelectedBlock() {
        BlockPos pos = EBSelectionClientState.block();
        if (pos == null) {
            return;
        }
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        // 往外扩 1/16 格，避免与方块表面重合产生闪烁
        double e = 0.0625D;
        int argb = ColorUtil.toArgb(ConfigEntryType.SELECTED_BLOCK_COLOR.getColor());
        drawBox(x - e, y - e, z - e, x + 1 + e, y + 1 + e, z + 1 + e, argb);
    }

    private static void renderSelectedEntity(ClientLevel level) {
        UUID uuid = EBSelectionClientState.entity();
        if (uuid == null) {
            return;
        }
        int argb = ColorUtil.toArgb(ConfigEntryType.SELECTED_ENTITY_COLOR.getColor());
        for (Entity entity : level.entitiesForRendering()) {
            if (!entity.getUUID().equals(uuid)) {
                continue;
            }
            AABB box = entity.getBoundingBox().inflate(0.05D);
            drawBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, argb);
            return;
        }
    }

    /**
     * 用 12 条线画一个盒子
     */
    private static void drawBox(double x0, double y0, double z0,
                                double x1, double y1, double z1, int argb) {
        // 底面 4 条
        line(x0, y0, z0, x1, y0, z0, argb);
        line(x1, y0, z0, x1, y0, z1, argb);
        line(x1, y0, z1, x0, y0, z1, argb);
        line(x0, y0, z1, x0, y0, z0, argb);
        // 顶面 4 条
        line(x0, y1, z0, x1, y1, z0, argb);
        line(x1, y1, z0, x1, y1, z1, argb);
        line(x1, y1, z1, x0, y1, z1, argb);
        line(x0, y1, z1, x0, y1, z0, argb);
        // 4 条竖棱
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