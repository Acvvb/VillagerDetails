package com.villagerdetails.client.feature.workstation;

import com.villagerdetails.client.cache.impl.WorkstationClientState;
import com.villagerdetails.client.feature.ClientFeature;
import com.villagerdetails.client.rander.EntityBlockLinkRenderer;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.util.ColorUtil;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * 把「村民 ↔ 它绑定的工作站点」用一根线连起来，并给站点本身套一个单方块高亮框。
 *
 * <p>遍历 / 插值 / 去重 / 画框这些和「村民-床连线」完全一样，都在
 * {@link EntityBlockLinkRenderer} 里；这里只声明工作站点这一侧的差异：
 * 谁来连（哪些实体、绑的是哪块）、站点什么情况下不该画、
 * 连线落在方块的哪个高度、以及距离超出就整条跳过。
 *
 * <p>被 {@code ClientFeatureScanner} 扫包自动注册（{@code client.feature} 含子包），
 * 加这个类不需要改客户端入口。
 */
public final class WorkstationLineRenderer implements ClientFeature {

    /**
     * 工作站点方块中心的 Y 偏移：连到方块中间，而不是底面
     */
    private static final double WORKSTATION_CENTER_Y = 0.5;

    /**
     * 渲染距离（格）：超过这个距离的村民直接跳过（连线和框一起跳过）。
     *
     * <p>和床不同：工作站点遍地都是（每个有职业的村民一个），不设距离上限会把远处那些
     * 根本看不见的站也画出来。
     */
    private static final double RENDER_DISTANCE = 48.0D;

    private WorkstationLineRenderer() {
    }

    /**
     * 由客户端初始化入口（扫包框架）调用一次
     */
    @Override
    public void register() {
        // BEFORE_GIZMOS 在原版 LevelRenderer.finalizeGizmoCollection() 之前触发，
        // 此时 Gizmos 的线程本地收集器正处于本次渲染的作用域内：
        // 塞进去的线会被立刻收集，并随原版调试线一起提交渲染。
        LevelRenderEvents.BEFORE_GIZMOS.register(_ -> renderWorkstationLines());
    }

    private static void renderWorkstationLines() {
        if (!ConfigEntryType.RENDER_WORKSTATION_LINE.getToggle()) {
            return;
        }

        int argb = ColorUtil.toArgb(ConfigEntryType.WORKSTATION_LINE_COLOR.getColor());

        EntityBlockLinkRenderer.render(new EntityBlockLinkRenderer.LinkSpec(
                WorkstationLineRenderer::workstationOf,
                // 站点被拆掉、或站点所在区块未加载（getBlockState 返回空气）时不画。
                // 这里刻意用「读方块状态」而不是已弃用的 hasChunkAt：它不会为了画一条线把区块加载下来，
                // 而且「区块没加载」和「方块没了」两种情况一次就挡掉了（和床连线的判断方式一致）。
                (level, pos) -> !level.getBlockState(pos).isAir(),
                WORKSTATION_CENTER_Y,
                RENDER_DISTANCE,
                // 工作站点只有一种颜色，不按距离切换
                _ -> argb,
                (float) ConfigEntryType.LINE_WIDTH.getNumber()));
    }

    /**
     * 这个实体绑的工作站点；<b>不是村民就返回 {@code null}</b>（= 这个实体不参与）。
     *
     * <p>种类过滤就放在这里：通用渲染器不认识实体类型，所有「连哪些实体」的判断都在各自的
     * {@code xxxOf(Entity)} 里。
     */
    private static BlockPos workstationOf(Entity entity) {
        if (!(entity instanceof Villager villager)) {
            return null;
        }
        // 服务端同步过来的工作站点坐标；没有就不画
        return WorkstationClientState.INSTANCE.getWorkstation(villager.getId());
    }
}
