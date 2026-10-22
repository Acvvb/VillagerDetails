package com.villagerdetails.client.feature.selection;

import com.villagerdetails.client.cache.impl.PortalViewClientState;
import com.villagerdetails.client.feature.ClientFeature;
import com.villagerdetails.client.rander.HighlightBoxes;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.util.ColorUtil;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;

/**
 * 传送门绑定「视图高亮」的客户端渲染。
 *
 * <p>画法和 EB / BB 高亮共用一套（{@link HighlightBoxes#drawRegion}），
 * 但<b>刻意不判 {@code RENDER_BB_HIGHLIGHT}</b>：这是命令点出来的临时视图，
 * 不该被那个「常驻选区高亮」开关关掉。
 *
 * <p>颜色是<b>两个维度分开配</b>的：主世界看
 * {@link ConfigEntryType#PORTAL_VIEW_OW_COLOR}，地狱看
 * {@link ConfigEntryType#PORTAL_VIEW_NE_COLOR}。
 *
 * <p>一次「高亮」两侧的区域都会留在 {@link PortalViewClientState} 里，
 * 这里每帧只挑「玩家当前所处维度」的那一侧画 ——
 * 所以人在主世界只看到主世界的框，坐传送门过去后地狱那侧的框自然就出现了，
 * 不需要再点一次命令。
 */
public final class PortalViewHighlightRenderer implements ClientFeature {

    private PortalViewHighlightRenderer() {
    }

    public void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(_ -> render());
    }

    private static void render() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        PortalViewClientState.Region region = PortalViewClientState.INSTANCE.regionOf(level.dimension());
        if (region == null) {
            return;
        }
        float lineWidth = (float) ConfigEntryType.PORTAL_HIGHLIGHT_LINE_WIDTH.getNumber();
        double outset = ConfigEntryType.HIGHLIGHT_REGION_OUTSET.getNumber();
        HighlightBoxes.drawRegion(region.min(), region.max(), ColorUtil.toArgb(colorOf(level)), outset, lineWidth);
    }

    /**
     * 当前维度对应的框颜色：地狱用地狱那套，其余（含主世界）用主世界那套。
     *
     * <p>绑定只可能落在主世界 / 地狱两侧（指令那边就只认这两个维度），
     * 所以「其余」实际走不到，兜底成主世界的颜色即可，不必再开第三个配置项。
     */
    private static int colorOf(ClientLevel level) {
        return Level.NETHER.equals(level.dimension())
                ? ConfigEntryType.PORTAL_VIEW_NE_COLOR.getColor()
                : ConfigEntryType.PORTAL_VIEW_OW_COLOR.getColor();
    }
}
