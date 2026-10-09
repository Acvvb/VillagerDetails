package com.villagerdetails.client.feature.villagerbed;

import com.villagerdetails.client.setting.type.ConfigEntryType;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

import static com.villagerdetails.client.setting.util.ColorUtil.toArgb;

/**
 * 把「村民 ↔ 它绑定的床」用一根线连起来。
 *
 * <p>连线走原版 gizmo 系统绘制，并调用 {@code setAlwaysOnTop()}，
 * 因此即使中间隔着方块也会绘制在整个世界的最上层（和原版 F3+G 区块边界线一样的画法）。
 *
 * <p>是否渲染、线宽、颜色、告警距离都由 {@link ConfigEntryType}（游戏内 UI 可改）决定。
 */
public final class VillagerBedLineRenderer {

    private static final Logger log = LogManager.getLogger(VillagerBedLineRenderer.class);

    /**
     * 床方块高度（9/16 格），让线连到床面上而不是床方块的几何中心
     */
    private static final double BED_SURFACE_Y = 0.5625;

    /**
     * 收集器缺失的告警只提示一次，避免刷屏
     */
    private static boolean collectorWarningLogged;

    private VillagerBedLineRenderer() {
    }

    public static void register() {
        // BEFORE_GIZMOS 在原版 LevelRenderer.finalizeGizmoCollection() 之前触发，
        // 此时 Gizmos 的线程本地收集器正处于本次渲染的作用域内：
        // 这里塞进去的线会被立刻收集，并随原版调试线一起提交渲染。
        LevelRenderEvents.BEFORE_GIZMOS.register(_ -> renderBedLines());
    }

    /**
     * 遍历客户端已加载的村民，给每一只连着它的床画一条线
     */
    private static void renderBedLines() {
        if (!ConfigEntryType.RENDER_BED_LINE.getToggle()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }

        // 阈值：超过这个距离就换成告警色
        double farDistance = ConfigEntryType.FAR_DISTANCE.getNumber();
        double farDistanceSqr = farDistance * farDistance;
        float lineWidth = (float) ConfigEntryType.LINE_WIDTH.getNumber();

        try {
            for (Entity entity : level.entitiesForRendering()) {
                if (!(entity instanceof Villager villager)) {
                    continue;
                }

                // 服务端同步过来的床坐标；联机时来自缓存，缓存没有时回退到客户端大脑记忆
                Optional<BlockPos> bedPosOpt = VillagerBedHelper.getBedPosition(villager);
                if (bedPosOpt.isEmpty()) {
                    continue;
                }
                BlockPos bedPos = bedPosOpt.get();

                // 床被拆掉、或床所在区块未加载（getBlockState 返回空气）时不画
                if (!level.getBlockState(bedPos).is(BlockTags.BEDS)) {
                    continue;
                }

                Vec3 bedSurface = Vec3.atBottomCenterOf(bedPos).add(0.0, BED_SURFACE_Y, 0.0);
                Vec3 villagerCenter = villager.position().add(0.0, villager.getBbHeight() * 0.5, 0.0);

                int rgb = villagerCenter.distanceToSqr(bedSurface) > farDistanceSqr
                        ? ConfigEntryType.FAR_COLOR.getColor()
                        : ConfigEntryType.NEAR_COLOR.getColor();

                // setAlwaysOnTop()：保证有方块遮挡时也渲染在最顶层
                Gizmos.line(villagerCenter, bedSurface, toArgb(rgb), lineWidth).setAlwaysOnTop();
            }
        } catch (IllegalStateException e) {
            // 正常情况 BEFORE_GIZMOS 一定在原版 gizmo 收集作用域内；
            // 万一以后渲染路径变了导致没有收集器，这里只告警一次，而不是让游戏崩掉
            if (!collectorWarningLogged) {
                collectorWarningLogged = true;
                log.warn("当前没有激活的 gizmo 收集器，村民-床连线这次没有渲染", e);
            }
        }
    }
}