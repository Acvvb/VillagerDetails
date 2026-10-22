package com.villagerdetails.client.feature.furnace;

import com.villagerdetails.client.cache.impl.FurnaceDirtyClientState;
import com.villagerdetails.client.feature.ClientFeature;
import com.villagerdetails.client.feature.selection.HighlightBoxes;
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
 * <p>开关有两层，任一层关掉都看不到框：
 * <ul>
 *     <li>服务端规则 → 关了就不发，客户端收到空列表自然清空（{@link FurnaceDirtyClientState}）；</li>
 *     <li>客户端配置项 {@link ConfigEntryType#RENDER_FURNACE_DIRTY} → 关掉还会让服务端别再发包。</li>
 * </ul>
 *
 * <p>渲染前按 {@link #RENDER_DISTANCE} 剔除：高亮是给「自己家这一片」看的，
 * 没必要把远处看不见的熔炉也画出来。
 */
public final class FurnaceDirtyHighlightRenderer implements ClientFeature {

    /**
     * 渲染距离（格）：超过这个距离的脏熔炉不画
     */
    private static final double RENDER_DISTANCE = 48.0D;

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
        FurnaceDirtyClientState.Snapshot snapshot = FurnaceDirtyClientState.INSTANCE.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        // 传送到别的维度后，上一维度的列表还没被服务端的新包覆盖：维度对不上就不画，避免同坐标的「幽灵框」
        if (!level.dimension().identifier().toString().equals(snapshot.dimension())) {
            return;
        }

        BlockPos eye = player.blockPosition();
        float lineWidth = (float) ConfigEntryType.FURNACE_DIRTY_LINE_WIDTH.getNumber();
        int argb = ColorUtil.toArgb(ConfigEntryType.FURNACE_DIRTY_COLOR.getColor());
        for (BlockPos pos : snapshot.positions()) {
            if (!pos.closerThan(eye, RENDER_DISTANCE)) {
                continue;
            }
            HighlightBoxes.drawBlock(pos, argb, lineWidth);
        }
    }
}
