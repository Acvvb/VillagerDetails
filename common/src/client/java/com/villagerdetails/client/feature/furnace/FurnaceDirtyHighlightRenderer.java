package com.villagerdetails.client.feature.furnace;

import com.villagerdetails.client.cache.impl.FurnaceDirtyClientState;
import com.villagerdetails.client.feature.ClientFeature;
import com.villagerdetails.client.rander.HighlightBoxes;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.util.ColorUtil;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * 脏熔炉高亮的客户端渲染。
 *
 * <p>画法直接复用选区高亮那套（{@link HighlightBoxes#drawBlock}）：
 * 线框、{@code setAlwaysOnTop()}、外扩 1/16 防止与方块表面共面闪烁，都是现成的。
 * 这里只负责「画哪些、用什么颜色」。
 *
 * <p><b>渲染时不做距离判断，也不需要比较维度</b>：{@link FurnaceDirtyClientState} 按维度分桶，
 * 每个维度里只装服务端认为「在视距内」的坐标（进入视距 ADD、离开视距 REMOVE），
 * 直接取当前维度那一桶即可。
 *
 * <p>只留一个兜底判断：「区块还没加载」—— 区块刚卸载、REMOVE 还在路上时，
 * 画一个没有方块的位置没有意义。
 *
 * <p>开关有两层，任一层关掉都看不到框：
 * <ul>
 *     <li>服务端规则 → 关了就不发，并主动让客户端清空（{@code FurnaceDirtyTracker}）；</li>
 *     <li>客户端配置项 {@link ConfigEntryType#RENDER_FURNACE_DIRTY} → 关掉还会让服务端别再发包。</li>
 * </ul>
 */
public final class FurnaceDirtyHighlightRenderer implements ClientFeature {

    private FurnaceDirtyHighlightRenderer() {
    }

    /**
     * 由客户端初始化入口调用一次
     */
    public void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(_ -> render());
    }

    private static void render() {
        // 本地显示开关优先：关掉之后即使还有缓存（例如刚切开关的那一帧）也不画
        if (!ConfigEntryType.RENDER_FURNACE_DIRTY.getToggle()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return;
        }

        // 按维度分桶：只取当前维度那一份，不用再比对快照维度
        var positions = FurnaceDirtyClientState.INSTANCE.positions(level.dimension().identifier().toString());
        if (positions.isEmpty()) {
            return;
        }

        float lineWidth = (float) ConfigEntryType.FURNACE_DIRTY_LINE_WIDTH.getNumber();
        int argb = ColorUtil.toArgb(ConfigEntryType.FURNACE_DIRTY_COLOR.getColor());
        for (BlockPos pos : positions) {
            // 兜底：区块刚卸载、REMOVE 还在路上的那一帧
            if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            HighlightBoxes.drawBlock(pos, argb, lineWidth);
        }
    }
}
