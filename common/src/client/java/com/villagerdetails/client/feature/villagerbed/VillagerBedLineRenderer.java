package com.villagerdetails.client.feature.villagerbed;

import com.villagerdetails.client.cache.impl.VillagerBedClientState;
import com.villagerdetails.client.feature.ClientFeature;
import com.villagerdetails.client.rander.EntityBlockLinkRenderer;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * 把「村民 ↔ 它绑定的床」用一根线连起来，并给床本身套一个单方块高亮框。
 *
 * <p>遍历 / 插值 / 去重 / 画框这些和「村民-工作站点连线」完全一样，都在
 * {@link EntityBlockLinkRenderer} 里；这里只声明床这一侧的差异：
 * 谁来连（哪些实体、绑的是哪块）、床什么情况下不该画（方块不是床）、
 * 连线落在床面的哪个高度、以及按距离切近 / 远两档颜色。
 *
 * <p>是否渲染、线宽、颜色、告警距离都由 {@link ConfigEntryType}（游戏内 UI 可改）决定；
 * 床上的小方框另用 {@code BOUND_BLOCK_BOX_*} 两个配置项。
 */
public final class VillagerBedLineRenderer implements ClientFeature {

    /**
     * 床方块高度（9/16 格），让线连到床面上而不是床方块的几何中心
     */
    private static final double BED_SURFACE_Y = 0.5625;

    private VillagerBedLineRenderer() {
    }

    public void register() {
        // BEFORE_GIZMOS 在原版 LevelRenderer.finalizeGizmoCollection() 之前触发，
        // 此时 Gizmos 的线程本地收集器正处于本次渲染的作用域内：
        // 塞进去的线会被立刻收集，并随原版调试线一起提交渲染。
        LevelRenderEvents.BEFORE_GIZMOS.register(_ -> renderBedLines());
    }

    private static void renderBedLines() {
        if (!ConfigEntryType.RENDER_BED_LINE.getToggle()) {
            return;
        }

        // 阈值：超过这个距离就换成告警色
        double farDistance = ConfigEntryType.FAR_DISTANCE.getNumber();
        double farDistanceSqr = farDistance * farDistance;
        int nearColor = ConfigEntryType.NEAR_COLOR.getColor();
        int farColor = ConfigEntryType.FAR_COLOR.getColor();

        EntityBlockLinkRenderer.render(new EntityBlockLinkRenderer.LinkSpec(
                VillagerBedLineRenderer::bedOf,
                // 床被拆掉、或床所在区块未加载（getBlockState 返回空气）时不画
                (level, pos) -> level.getBlockState(pos).is(BlockTags.BEDS),
                BED_SURFACE_Y,
                // 床连线不按距离跳过，远处的只是换个颜色提醒
                Double.MAX_VALUE,
                distanceSqr -> distanceSqr > farDistanceSqr ? farColor : nearColor,
                (float) ConfigEntryType.LINE_WIDTH.getNumber()));
    }

    /**
     * 这个实体绑的床；<b>不是村民、或者查不到床都返回 {@code null}</b>（= 这个实体不参与）。
     *
     * <p>种类过滤就放在这里：通用渲染器不认识实体类型，所有「连哪些实体」的判断都在各自的
     * {@code xxxOf(Entity)} 里。
     */
    private static BlockPos bedOf(Entity entity) {
        if (!(entity instanceof Villager villager)) {
            return null;
        }
        // 服务端同步过来的床坐标；联机时来自缓存，缓存没有时回退到客户端大脑记忆
        return VillagerBedClientState.INSTANCE.getBedPosition(villager).orElse(null);
    }
}
