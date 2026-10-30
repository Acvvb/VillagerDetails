package com.villagerdetails.handler.binding.block.portalbinding;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.section.item.PortalBindingConfig;
import com.villagerdetails.pojo.dto.PortalBinding;
import com.villagerdetails.pojo.dto.PortalDestination;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * 传送门绑定业务入口。
 *
 * <p>对外动作：
 * <ul>
 *   <li>{@link #bind} —— 写入一条绑定，并对两侧目标区域<b>预热</b>（区域已有门才写缓存）；</li>
 *   <li>{@link #resolve(Entity, BlockPos)} / {@link #resolve(ResourceKey, BlockPos)} —— 解析目标（方案 A 多命中仲裁）；</li>
 *   <li>{@link #cachePortal} —— 传送时扫描/建门成功后回写缓存；</li>
 *   <li>{@link #refreshCachedPortals} —— {@code /ec c PortalBinding refresh <序号>} 触发，
 *       两侧重扫后一次性写回（绑定已不存在 / 服务端不可用返回 {@code null}）。</li>
 * </ul>
 *
 * <h2>缓存策略</h2>
 * <ul>
 *   <li><b>bind 时预热</b>：目标区域已加载且有门 → 解析并写缓存，避免首次传送卡顿；<b>不自动建门</b>，区域没门就跳过。</li>
 *   <li><b>首次传送</b>：缓存为空 → 懒解析（扫描/建门）→ 回写缓存。</li>
 *   <li><b>缓存验证</b>：每次传送先 {@code getBlockState(cached)} 判断是不是门，失效则自动重扫。</li>
 *   <li><b>refresh</b>：两侧重扫一遍后一次性写回（不清空了再写，配置只落一次盘），
 *       用于玩家“绑完后又建了新门想换门”的场景。</li>
 * </ul>
 * 刷新/预热都按「离区域中心最近」选门，<b>不保证换成玩家想要的那扇</b>；
 * 想强制某扇门，请拆掉当前缓存的那扇，或把选区框到目标门附近。
 *
 * <h2>解析策略（方案 A）</h2>
 * <ul>
 *   <li>无命中 → null，调用方走原版</li>
 *   <li>单命中 → 直通，不进入距离计算（与 1:1 绑定完全等价）</li>
 *   <li>多命中 → 优先「原版对应维度」的候选；无则用全部；仍跨维度取列表首个维度（确定性）</li>
 *   <li>候选中按「原版理论落点」的欧氏距离平方取最近，<b>无阈值</b></li>
 *   <li>等距按 pos 字典序 tie-break，不依赖 Map/HashSet 遍历顺序</li>
 * </ul>
 *
 * <p>理论落点：主世界 ↔ 下界按 1:8 缩放 XZ、Y 不变（与 {@code PortalForcer} 一致）；
 * 其他维度不缩放。
 */
public final class PortalBindingService {

    private static final Logger log = LogManager.getLogger(PortalBindingService.class);

    private PortalBindingService() {
    }

    // ==================== 绑定（含预热） ====================

    /**
     * 执行绑定。四个坐标均为必填，两侧都是区域。
     *
     * <p>写入后立即预热：目标区域<b>已存在门</b>时解析并写缓存；区域内没门或 chunk 未加载则跳过，
     * 不自动建门 —— 建门留给首次传送（玩家真正要用时）。
     *
     * <p>如果不希望预热（要求“每次传送看到最新区域状态”），把本方法末尾两次 {@code prewarm} 调用删掉即可，
     * 其它逻辑不受影响。
     */
    public static void bind(BlockPos overworldMin, BlockPos overworldMax,
                            BlockPos netherMin, BlockPos netherMax) {
        PortalBindingManager manager = PortalBindingManager.getInstance();
        manager.addBinding(overworldMin, overworldMax, netherMin, netherMax);

        // addBinding 内部已归一化，用 equals 反查（忽略缓存字段）拿到真实 binding
        PortalBinding probe = PortalBinding.of(
                Level.OVERWORLD, overworldMin, overworldMax,
                Level.NETHER, netherMin, netherMax);
        PortalBinding binding = findCurrent(probe);
        if (binding == null) {
            log.warn("[PortalBinding] 绑定后找不到对应记录，跳过预热");
            return;
        }

        prewarm(binding, binding.dimA(), binding.minA(), binding.maxA(), false);
        prewarm(binding, binding.dimB(), binding.minB(), binding.maxB(), true);
    }

    /**
     * 预热：目标区域内已存在门时解析并写缓存；没门 / chunk 未加载 / server 不可用则跳过。
     *
     * <p><b>不建门</b> —— 避免玩家只绑了区域时系统自动建房。
     *
     * @param targetIsB true 表示预热 B 侧（{@code binding.portalB()}），false 表示预热 A 侧
     */
    private static void prewarm(PortalBinding binding, ResourceKey<Level> targetDim,
                                BlockPos targetMin, BlockPos targetMax, boolean targetIsB) {
        MinecraftServer server = RuleCache.getServer();
        if (server == null) {
            return;
        }
        ServerLevel targetLevel = server.getLevel(targetDim);
        if (targetLevel == null) {
            return;
        }
        if (!PortalLocator.isRegionLoaded(targetLevel, targetMin, targetMax)) {
            log.debug("[PortalBinding] 预热跳过（chunk 未加载）：{}", targetDim.identifier());
            return;
        }
        BlockPos center = centerOf(targetMin, targetMax);
        BlockPos portalPos = PortalLocator.scanRegionOnly(targetLevel, center, targetMin, targetMax);
        if (portalPos == null) {
            log.debug("[PortalBinding] 预热跳过（区域内无门）：{}", targetDim.identifier());
            return;
        }
        PortalBindingConfig.INSTANCE.updateCachedPortal(binding, targetIsB, portalPos);
        log.debug("[PortalBinding] 预热写缓存：{} -> {}", targetDim.identifier(), portalPos);
    }

    // ==================== 解析 ====================

    /**
     * 实体进入传送门时查询目标；没有绑定返回 null。
     *
     * <p>委托给 {@link #resolve(ResourceKey, BlockPos)}，维度取自实体当前所在 level。
     */
    public static PortalDestination resolve(Entity entity, BlockPos portalPos) {
        return resolve(entity.level().dimension(), portalPos);
    }

    /**
     * 按维度 + 坐标查询目标，供非实体场景使用。
     *
     * <p>方案 A：多命中时按「原版理论落点」的欧氏距离取最近，无阈值；
     * 单命中直通；等距按 pos 字典序 tie-break（确定性）。
     */
    public static PortalDestination resolve(ResourceKey<Level> dim, BlockPos pos) {
        List<PortalDestination> candidates =
                PortalBindingManager.getInstance().findAllDestinations(dim, pos);
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        return pickClosest(dim, pos, candidates);
    }

    /**
     * 方案 A 仲裁：在命中候选里选离原版理论落点最近的。
     *
     * <p>候选池的构建顺序：
     * <ol>
     *   <li>优先保留目标维度 = 「原版对应维度」的候选（主↔下）</li>
     *   <li>该维度无候选时退回全部候选</li>
     *   <li>仍跨维度则取列表首个出现的维度，保证结果可复现</li>
     * </ol>
     *
     * <p>前提：{@code candidates} 非空（由 {@link #resolve(ResourceKey, BlockPos)} 保证）。
     */
    private static PortalDestination pickClosest(ResourceKey<Level> sourceDim,
                                                 BlockPos sourcePos,
                                                 List<PortalDestination> candidates) {
        ResourceKey<Level> expected = expectedTargetDim(sourceDim);

        List<PortalDestination> pool = candidates.stream()
                .filter(d -> d.dimension().equals(expected))
                .toList();
        if (pool.isEmpty()) {
            pool = candidates;
        }
        if (pool.stream().map(PortalDestination::dimension).distinct().count() > 1) {
            ResourceKey<Level> pick = pool.getFirst().dimension();
            pool = pool.stream().filter(d -> d.dimension().equals(pick)).toList();
        }
        if (pool.size() == 1) {
            return pool.getFirst();
        }

        BlockPos theoretical = scaleLikeVanilla(sourcePos, sourceDim, pool.getFirst().dimension());

        return pool.stream()
                .min(Comparator
                        .comparingDouble((PortalDestination d) -> d.pos().distSqr(theoretical))
                        .thenComparingInt(d -> d.pos().getX())
                        .thenComparingInt(d -> d.pos().getY())
                        .thenComparingInt(d -> d.pos().getZ()))
                .orElse(pool.getFirst());
    }

    // ==================== 缓存回写 ====================

    /**
     * 把「已解析出的门底坐标」回写到对应绑定的对应侧并落盘。
     *
     * <p>由 Mixin 在以下时机调用：扫描到附近门 / 新建门成功之后。
     * 校验（{@code getBlockState(cached) instanceof NetherPortalBlock}）与
     * 扫描都发生在 Mixin 里，本方法只负责落盘，不参与判断。
     *
     * <p>失败情形静默返回：
     * <ul>
     *   <li>{@code portalDestination} 为 null / {@code portalDestination.source()} 为 null（旧构造，无绑定上下文）</li>
     *   <li>{@code portalPos} 为 null</li>
     *   <li>绑定已被删除（{@code updateCachedPortal} 内部 warn 并返回 false）</li>
     * </ul>
     *
     * @param portalDestination      {@link #resolve} 返回的目标，需携带 {@code source()} 与 {@code targetIsB()}
     * @param portalPos 门底坐标（{@code bottomOfPortal} 的结果，不是随便一个门方块）
     */
    public static void cachePortal(@Nullable PortalDestination portalDestination, @Nullable BlockPos portalPos) {
        if (portalDestination == null || portalDestination.source() == null || portalPos == null) {
            return;
        }
        PortalBindingConfig.INSTANCE.updateCachedPortal(
                portalDestination.source(), portalDestination.targetIsB(), portalPos.immutable());
    }

    // ==================== 刷新缓存 ====================

    /**
     * 刷新时某一侧的处置结果（只用于命令反馈，不影响数据）。
     */
    public enum SideOutcome {
        /** 扫到了新门，缓存被换成它。 */
        FOUND,
        /** 没扫到新门，但旧缓存仍是「区域内的真门」/ 或者这次没扫全 → 原样保留。 */
        KEPT,
        /** 扫全了、区域内确实没门（或旧缓存已不再合格）→ 这一侧没有缓存。 */
        CLEARED,
        /** 目标维度当前拿不到 → 这一侧一个字节都没动。 */
        UNTOUCHED
    }

    /**
     * 某一侧的刷新结果。
     *
     * @param portal   这一侧最终的缓存坐标；{@code null} 表示没有缓存
     * @param outcome  这个值是怎么定下来的
     * @param complete 这一侧的扫描有没有覆盖整个区域
     */
    public record SideResult(@Nullable BlockPos portal, SideOutcome outcome, boolean complete) {
    }

    /**
     * 一次刷新的完整结果。
     *
     * @param binding 刷新后的快照（用于展示）
     * @param sideA   A 侧的处置
     * @param sideB   B 侧的处置
     */
    public record RefreshResult(PortalBinding binding, SideResult sideA, SideResult sideB) {

        /** 两侧是否有任何一侧「没扫全」（区域过大），命令反馈要提示 */
        public boolean anyIncomplete() {
            return !sideA.complete() || !sideB.complete();
        }
    }

    /**
     * 刷新一条绑定两侧的缓存：按当前区域状态重新扫描，再一次性覆盖写回。
     *
     * <p>供 {@code /ec c PortalBinding refresh <序号>} 指令（以及 {@code show} 里那个
     * {@code [刷新]} 按钮）调用。语义：
     * <b>重新按「离区域中心最近」选一次</b>，不保证换成玩家想要的那扇 ——
     * 想强制某扇门，请拆掉当前那扇，或把选区框到目标门附近。
     *
     * <p><b>「有证据才丢」</b>：刷新不会因为「这次没扫到」就把可用缓存抹掉。
     * 每侧的判定见 {@link #rescanSide}：扫到新的就用新的；没扫到但旧缓存仍是区域内的真门
     * （或本次没扫全）就保留；只有「确认扫全 + 区域里确实没门 / 旧缓存已不合格」才清空。
     *
     * <p>两侧都算完之后一次写回（{@link PortalBindingConfig#applyCachedPortals}），
     * 中间不会落下「清了缓存但还没写回」的中间状态，配置也只落一次盘。
     *
     * @param binding 目标绑定（equals 匹配，缓存字段不参与）
     * @return 刷新结果；<b>绑定已不存在 / 服务端不可用返回 {@code null}</b>
     *         —— 这两种情况什么都没改，调用方要按失败处理，别报「已刷新」
     */
    public static @Nullable RefreshResult refreshCachedPortals(@Nullable PortalBinding binding) {
        if (binding == null) {
            return null;
        }
        PortalBindingConfig config = PortalBindingConfig.INSTANCE;
        int idx = configIndexOf(binding);
        if (idx < 0) {
            log.warn("[PortalBinding] refresh 找不到目标绑定：{}", binding);
            return null;
        }

        PortalBinding fresh = config.get(idx);
        if (fresh == null) {
            return null;
        }

        MinecraftServer server = RuleCache.getServer();
        if (server == null) {
            // 一个维度都拿不到就没法重扫：直接算失败，别把现有缓存清空了却扫不回来
            log.warn("[PortalBinding] refresh 跳过（server 不可用）：{}", binding);
            return null;
        }

        SideResult a = rescanSide(server, fresh.dimA(), fresh.portalA(), fresh.minA(), fresh.maxA());
        SideResult b = rescanSide(server, fresh.dimB(), fresh.portalB(), fresh.minB(), fresh.maxB());

        config.applyCachedPortals(fresh, a.portal(), b.portal());

        PortalBinding updated = config.get(idx);
        return new RefreshResult(updated != null ? updated : fresh, a, b);
    }

    /**
     * 重扫一侧，并按「有证据才丢」决定这一侧最终缓存什么。
     *
     * <p>判定顺序（和 {@code EntityPortalMixin#resolvePortalPos} 用同一套缓存校验标准）：
     * <ol>
     *   <li>维度拿不到 → {@link SideOutcome#UNTOUCHED}，一个字节不动；</li>
     *   <li>扫到门 → 用它（{@link SideOutcome#FOUND}）；</li>
     *   <li>没扫到、且<b>没扫全</b> → 「没扫到」不等于「没有」，
     *       旧缓存有就保留（{@link SideOutcome#KEPT}）；</li>
     *   <li>扫全了但没扫到 → 旧缓存若「仍在区域内」且「该方块仍是传送门」就保留，
     *       否则清空（{@link SideOutcome#CLEARED}）。</li>
     * </ol>
     *
     * <p>第 4 条里的「仍在区域内」是刻意的：玩家把选区分小之后，旧缓存那扇门可能已经被
     * 排除在区域外，这时必须清掉，否则下次传送会把人送到区域外的那扇门。
     */
    private static SideResult rescanSide(MinecraftServer server, ResourceKey<Level> dim,
                                         @Nullable BlockPos oldPortal, BlockPos min, BlockPos max) {
        ServerLevel level = server.getLevel(dim);
        if (level == null) {
            log.debug("[PortalBinding] refresh 跳过某侧（维度不可用）：{}", dim.identifier());
            return new SideResult(oldPortal, SideOutcome.UNTOUCHED, false);
        }

        PortalLocator.RegionScan scan =
                PortalLocator.scanRegion(level, centerOf(min, max), min, max);

        if (scan.portal() != null) {
            return new SideResult(scan.portal(), SideOutcome.FOUND, scan.complete());
        }
        if (!scan.complete()) {
            // 「没扫全」和「没有门」不能混为一谈：保留旧缓存
            log.debug("[PortalBinding] refresh 未扫全，保留旧缓存：{} -> {}",
                    dim.identifier(), oldPortal);
            return oldPortal != null
                    ? new SideResult(oldPortal, SideOutcome.KEPT, false)
                    : new SideResult(null, SideOutcome.CLEARED, false);
        }
        if (isUsableCachedPortal(level, oldPortal, min, max)) {
            log.debug("[PortalBinding] refresh 区域内没扫到新门，沿用旧缓存：{} -> {}",
                    dim.identifier(), oldPortal);
            return new SideResult(oldPortal, SideOutcome.KEPT, true);
        }
        return new SideResult(null, SideOutcome.CLEARED, true);
    }

    /**
     * 旧缓存还能不能用：必须在区域内，且那个方块现在仍然是传送门。
     */
    private static boolean isUsableCachedPortal(ServerLevel level, @Nullable BlockPos portal,
                                                BlockPos min, BlockPos max) {
        return portal != null
                && PortalBinding.inRegion(portal, min, max)
                && level.getBlockState(portal).getBlock() instanceof NetherPortalBlock;
    }

    // ==================== 辅助 ====================

    /**
     * 按 equals（忽略缓存字段）在当前快照里定位 binding 的最新副本。
     * 找不到返回 null。
     */
    private static @Nullable PortalBinding findCurrent(PortalBinding binding) {
        int idx = configIndexOf(binding);
        return idx < 0 ? null : PortalBindingConfig.INSTANCE.get(idx);
    }

    /**
     * 按 equals 在 config 的 snapshot 里定位下标。
     * 依赖 {@link PortalBinding#equals} 忽略缓存字段。
     */
    private static int configIndexOf(PortalBinding binding) {
        List<PortalBinding> snapshot = PortalBindingConfig.INSTANCE.snapshot();
        for (int i = 0; i < snapshot.size(); i++) {
            if (snapshot.get(i).equals(binding)) {
                return i;
            }
        }
        return -1;
    }

    private static BlockPos centerOf(BlockPos min, BlockPos max) {
        return new BlockPos(
                (min.getX() + max.getX()) / 2,
                (min.getY() + max.getY()) / 2,
                (min.getZ() + max.getZ()) / 2);
    }

    /**
     * 原版坐标缩放：主世界 → 下界 XZ ×0.125，下界 → 主世界 XZ ×8，Y 不变；
     * 其他维度（含末地）不缩放。
     *
     * <p>用 {@link Mth#floor} 而非整数除法，负数（如 -801 / 8 = -101）才正确。
     */
    private static BlockPos scaleLikeVanilla(BlockPos p, ResourceKey<Level> from, ResourceKey<Level> to) {
        int x = p.getX(), y = p.getY(), z = p.getZ();
        if (from.equals(Level.OVERWORLD) && to.equals(Level.NETHER)) {
            return new BlockPos(Mth.floor(x / 8.0), y, Mth.floor(z / 8.0));
        }
        if (from.equals(Level.NETHER) && to.equals(Level.OVERWORLD)) {
            return new BlockPos(x * 8, y, z * 8);
        }
        return p;
    }

    /**
     * 主世界 ↔ 下界互相对应；其他维度返回自身（末地不缩放、不跨维映射）。
     */
    private static ResourceKey<Level> expectedTargetDim(ResourceKey<Level> source) {
        if (source.equals(Level.OVERWORLD)) return Level.NETHER;
        if (source.equals(Level.NETHER)) return Level.OVERWORLD;
        return source;
    }
}