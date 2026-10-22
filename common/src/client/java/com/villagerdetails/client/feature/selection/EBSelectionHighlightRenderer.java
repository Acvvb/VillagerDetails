package com.villagerdetails.client.feature.selection;

import com.villagerdetails.client.cache.impl.EBSelectionClientState;
import com.villagerdetails.client.feature.ClientFeature;
import com.villagerdetails.client.rander.EntityOutlineRenderer;
import com.villagerdetails.client.rander.HighlightBoxes;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.util.ColorUtil;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * 「选中高亮」的客户端渲染：把服务端同步过来的实体 / 方块用一个线框盒罩住。
 *
 * <p>开关和颜色都由 {@link ConfigEntryType} 决定；画法在 {@code rander} 包里 ——
 * 方块框是 {@link HighlightBoxes}，实体描边是 {@link EntityOutlineRenderer}
 * （两者都和 BB 高亮、连线标记框共用同一份几何，这里只管「画哪个、用什么颜色」）。
 */
public final class EBSelectionHighlightRenderer implements ClientFeature {

    private EBSelectionHighlightRenderer() {
    }

    public void register() {
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
        EBSelectionClientState.Snapshot selection = EBSelectionClientState.INSTANCE.get();
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

        int argb = ColorUtil.toArgb(ConfigEntryType.SELECTED_ENTITY_COLOR.getColor());
        float lineWidth = (float) ConfigEntryType.EB_HIGHLIGHT_LINE_WIDTH.getNumber();
        // 插值进度整帧只取一次：下面可能要找一遍实体列表，别每个实体都问一遍
        float partialTick = EntityOutlineRenderer.currentPartialTick();

        // 状态里存的是 UUID（实体可能已经卸载），得在客户端已加载实体里找回来
        for (Entity entity : level.entitiesForRendering()) {
            if (!entity.getUUID().equals(uuid)) {
                continue;
            }
            // 描边盒（插值 + 外扩）的画法在通用渲染器里，任意实体都能用
            EntityOutlineRenderer.draw(entity, partialTick, argb, lineWidth);
            return;
        }
    }
}
