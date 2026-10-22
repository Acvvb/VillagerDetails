package com.villagerdetails.client.feature.selection;

import com.villagerdetails.client.cache.impl.BBSelectionClientState;
import com.villagerdetails.client.feature.ClientFeature;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.util.ColorUtil;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * 「BB 选择器高亮」的客户端渲染。
 *
 * <ul>
 *     <li>只选中一个角点 → 用该角点的颜色高亮那个方块（和 EB 高亮同款）</li>
 *     <li>两个角点都选中 → 各自高亮，再用「总颜色」把两个方块为顶点的长方体框出来</li>
 * </ul>
 *
 * <p>开关和三种颜色都由 {@link ConfigEntryType} 决定；盒子和线的画法在 {@link HighlightBoxes}
 * （和 EB 高亮共用一份），这里只管「画哪个、用什么颜色」。
 */
public final class BBSelectionHighlightRenderer implements ClientFeature {

    private BBSelectionHighlightRenderer() {
    }

    /**
     * 由客户端初始化入口调用一次
     */
    @Override
    public void register() {
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

        // 一次读全：pos1 / pos2 取自同一份快照，不会画出「新 pos1 配旧 pos2」的错框
        BBSelectionClientState.Snapshot selection = BBSelectionClientState.INSTANCE.get();
        if (selection.isEmpty()) {
            return;
        }
        BlockPos p1 = selection.pos1();
        BlockPos p2 = selection.pos2();

        float lineWidth = (float) ConfigEntryType.BB_HIGHLIGHT_LINE_WIDTH.getNumber();
        double outset = ConfigEntryType.HIGHLIGHT_REGION_OUTSET.getNumber();

        if (p1 != null) {
            HighlightBoxes.drawBlock(p1, ColorUtil.toArgb(ConfigEntryType.BB_POS1_COLOR.getColor()), lineWidth);
        }
        if (p2 != null) {
            HighlightBoxes.drawBlock(p2, ColorUtil.toArgb(ConfigEntryType.BB_POS2_COLOR.getColor()), lineWidth);
        }
        // 两个都选中 → 用总颜色把整个区域框出来
        if (p1 != null && p2 != null) {
            HighlightBoxes.drawRegion(p1, p2, ColorUtil.toArgb(ConfigEntryType.BB_BOX_COLOR.getColor()), outset, lineWidth);
        }
    }
}
