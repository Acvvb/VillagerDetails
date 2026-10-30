package com.villagerdetails.client.rander;

import com.villagerdetails.client.setting.type.ConfigEntryType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;

import static com.villagerdetails.client.setting.util.ColorUtil.toArgb;

/**
 * 「任意实体 → 某个方块」连线的公共画法。
 *
 * <p>现在用在两处：村民-床（{@code VillagerBedLineRenderer}）、村民-工作站点
 * （{@code WorkstationLineRenderer}），但这里<b>不认识任何具体实体类型</b> ——
 * 实体交给 {@link LinkSpec#target()} 自己筛（返回 {@code null} 就是不画），
 * 所以以后要连「船 → 码头」「盔甲架 → 铁砧」之类，写一个新的 spec 就行。
 *
 * <p>一个 spec 只有四处差异，其余遍历 / 插值 / 去重 / 画框全在这里：
 * 怎么找到那块方块、那块什么情况下不该画、连线落在方块的哪个高度、线怎么取色。
 *
 * <p>标记框的颜色 / 线宽取 {@link ConfigEntryType#BOUND_BLOCK_BOX_COLOR} /
 * {@link ConfigEntryType#BOUND_BLOCK_BOX_LINE_WIDTH}，也就是所有「实体 → 方块」连线
 * <b>共用</b>同一套标记框样式。
 *
 * <p>走原版 gizmo 系统绘制并 {@code setAlwaysOnTop()}，隔着方块也可见。
 */
public final class EntityBlockLinkRenderer {

    private static final Logger log = LogManager.getLogger(EntityBlockLinkRenderer.class);

    /**
     * 收集器缺失的告警只提示一次，避免刷屏
     */
    private static boolean collectorWarningLogged;

    private EntityBlockLinkRenderer() {
    }

    /**
     * 这个实体要连到哪块方块。
     *
     * <p><b>种类过滤在这里做</b>：不是想要的实体类型、没有绑定、数据还没同步到 ——
     * 一律返回 {@code null}，渲染循环会跳过它。
     */
    @FunctionalInterface
    public interface TargetFinder {
        BlockPos find(Entity entity);
    }

    /**
     * 那个方块现在还能不能画。
     *
     * <p>用「读方块状态」而不是「查区块加载」：{@code level.getBlockState(pos)} 对未加载的区块
     * 直接返回空气，<b>不会</b>为了画一条线把区块加载下来；方块被拆掉时也自然返回空气。
     * 所以一次 {@code isAir()} / {@code is(标签)} 就同时挡掉了「区块没加载」和「方块没了」两种情况。
     */
    @FunctionalInterface
    public interface TargetCheck {
        boolean test(ClientLevel level, BlockPos pos);
    }

    /**
     * 连线颜色。
     */
    @FunctionalInterface
    public interface LineColor {
        int argb(double distanceSqr);
    }

    /**
     * 一条连线形态的全部差异。
     *
     * @param target         这个实体连到哪块方块（返回 {@code null} 表示跳过这个实体）
     * @param present        方块是否还该画（区块未加载 / 被拆 → false）
     * @param targetY        连线终点落在方块上的 Y 偏移（床面 0.5625，一般方块中心 0.5）
     * @param renderDistance 超过这个距离整条跳过（{@link Double#MAX_VALUE} = 不按距离跳，只是换颜色）
     * @param color          线颜色
     * @param lineWidth      线宽
     */
    public record LinkSpec(TargetFinder target,
                           TargetCheck present,
                           double targetY,
                           double renderDistance,
                           LineColor color,
                           float lineWidth) {
    }

    /**
     * 遍历本帧可见实体，给每个「找得到目标」的画一条线；每个目标方块再套一个标记框
     * （同一个方块只画一次）。
     *
     * <p>调用方先自己判开关（关着就不用进来了），这里只管画。
     */
    public static void render(LinkSpec spec) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }

        // MAX_VALUE 平方是 Infinity，所以「不限距离」不用特判
        double maxDistanceSqr = spec.renderDistance() * spec.renderDistance();
        // 标记框：颜色 / 线宽是所有连线共用的配置项，这里读一次
        int boxArgb = toArgb(ConfigEntryType.BOUND_BLOCK_BOX_COLOR.getColor());
        float boxLineWidth = (float) ConfigEntryType.BOUND_BLOCK_BOX_LINE_WIDTH.getNumber();
        // 多个实体可以共用同一个方块，同一方块的框只画一次（叠着画会显得更粗更亮）
        Set<BlockPos> drawnBoxes = new HashSet<>();
        // 实体那一端也要按本帧插值取位置，不然连线会跟着 tick 一跳一跳
        // （和实体高亮盒是同一个坑：position() 是 tick 位置，模型画的是插值位置）
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        try {
            for (Entity entity : level.entitiesForRendering()) {
                // 种类过滤由 spec 负责：不是它要的实体就返回 null
                BlockPos target = spec.target().find(entity);
                if (target == null) {
                    continue;
                }
                if (!spec.present().test(level, target)) {
                    continue;
                }

                Vec3 targetCenter = Vec3.atBottomCenterOf(target).add(0.0, spec.targetY(), 0.0);
                Vec3 entityCenter = entity.getPosition(partialTick)
                        .add(0.0, entity.getBbHeight() * 0.5, 0.0);

                double distanceSqr = entityCenter.distanceToSqr(targetCenter);
                if (distanceSqr > maxDistanceSqr) {
                    continue;
                }

                // setAlwaysOnTop()：保证有方块遮挡时也渲染在最顶层
                Gizmos.line(entityCenter, targetCenter, spec.color().argb(distanceSqr), spec.lineWidth())
                        .setAlwaysOnTop();

                // 目标方块本身也标出来（和 BB 选区选中单个方块时同一个画法）
                if (drawnBoxes.add(target)) {
                    HighlightBoxes.drawBlock(target, boxArgb, boxLineWidth);
                }
            }
        } catch (IllegalStateException e) {
            // 正常情况 BEFORE_GIZMOS 一定在原版 gizmo 收集作用域内；
            // 万一以后渲染路径变了导致没有收集器，这里只告警一次，而不是让游戏崩掉
            if (!collectorWarningLogged) {
                collectorWarningLogged = true;
                log.warn("当前没有激活的 gizmo 收集器，实体与方块的连线这次没有渲染", e);
            }
        }
    }
}
