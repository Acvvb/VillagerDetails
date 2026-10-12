package com.villagerdetails.client.feature.selection;

import com.villagerdetails.client.cache.EBSelectionClientState;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.util.ColorUtil;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * 「选中高亮」的客户端渲染：把服务端同步过来的实体 / 方块用一个线框盒罩住。
 *
 * <p>开关和颜色都由 {@link ConfigEntryType} 决定；盒子和线的画法在 {@link HighlightBoxes}
 * （和 BB 高亮共用一份），这里只管「画哪个、用什么颜色」。
 */
public final class EBSelectionHighlightRenderer {

    private EBSelectionHighlightRenderer() {
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

        // 一次读全：实体和方块取自同一份快照，两个框永远属于同一次选中
        EBSelectionClientState.Snapshot selection = EBSelectionClientState.get();
        if (selection.isEmpty()) {
            return;
        }
        renderSelectedBlock(selection.block());
        renderSelectedEntity(level, selection.entity());
    }

    private static void renderSelectedBlock(BlockPos pos) {
        if (pos == null) {
            return;
        }
        float lineWidth = (float) ConfigEntryType.EB_HIGHLIGHT_LINE_WIDTH.getNumber();
        HighlightBoxes.drawBlock(pos, ColorUtil.toArgb(ConfigEntryType.SELECTED_BLOCK_COLOR.getColor()), lineWidth);
    }

    private static void renderSelectedEntity(ClientLevel level, UUID uuid) {
        if (uuid == null) {
            return;
        }

        // 实体模型是按「上一 tick 位置 → 当前 tick 位置」插值画出来的，而碰撞箱每 tick 才更新一次：
        // 直接画 getBoundingBox() 就是 20 TPS 一跳，模型却顺滑，看着就像只有这个框在卡。
        // 所以和原版 F3+B 的实体碰撞箱一样 —— 取本帧插值位置，把盒子整体挪过去。
        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        int argb = ColorUtil.toArgb(ConfigEntryType.SELECTED_ENTITY_COLOR.getColor());
        float lineWidth = (float) ConfigEntryType.EB_HIGHLIGHT_LINE_WIDTH.getNumber();

        for (Entity entity : level.entitiesForRendering()) {
            if (!entity.getUUID().equals(uuid)) {
                continue;
            }
            AABB box = entity.getBoundingBox()
                    .move(entity.getPosition(partialTick).subtract(entity.position()))
                    .inflate(0.05D);
            HighlightBoxes.draw(box, argb, lineWidth);
            return;
        }
    }
}
