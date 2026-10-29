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
 *   <li>{@link #refreshCachedPortals} —— {@code /ec c PortalBinding refresh <序号>} 触发，清缓存 + 立即重扫回写。</li>
 * </ul>
 *
 * <h2>缓存策略</h2>
 * <ul>
 *   <li><b>bind 时预热</b>：目标区域已加载且有门 → 解析并写缓存，避免首次传送卡顿；<b>不自动建门</b>，区域没门就跳过。</li>
 *   <li><b>首次传送</b>：缓存为空 → 懒解析（扫描/建门）→ 回写缓存。</li>
 *   <li><b>缓存验证</b>：每次传送先 {@code getBlockState(cached)} 判断是不是门，失效则自动重扫。</li>
 *   <li><b>refresh</b>：清空 + 立即重扫回写，用于玩家“绑完后又建了新门想换门”的场景。</li>
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

    private static final Logger LOGGER = LogManager.getLogger(PortalBindingService.class);

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
            LOGGER.warn("[PortalBinding] 绑定后找不到对应记录，跳过预热");
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
            LOGGER.debug("[PortalBinding] 预热跳过（chunk 未加载）：{}", targetDim.identifier());
            return;
        }
        BlockPos center = centerOf(targetMin, targetMax);
        BlockPos portalPos = PortalLocator.scanRegionOnly(targetLevel, center, targetMin, targetMax);
        if (portalPos == null) {
            LOGGER.debug("[PortalBinding] 预热跳过（区域内无门）：{}", targetDim.identifier());
            return;
        }
        PortalBindingConfig.INSTANCE.updateCachedPortal(binding, targetIsB, portalPos);
        LOGGER.debug("[PortalBinding] 预热写缓存：{} -> {}", targetDim.identifier(), portalPos);
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
     * 刷新一条绑定两侧的缓存：清空后立即按当前区域状态重新扫描并回写。
     *
     * <p>供 {@code /ec c PortalBinding refresh <序号>} 指令调用。语义：
     * <b>重新按「离区域中心最近」选一次</b>，不保证换成玩家想要的那扇 ——
     * 想强制某扇门，请拆掉当前那扇，或把选区框到目标门附近。
     *
     * <p>区域内没门的侧保持无缓存，下次传送时懒解析（可能会建门）。
     *
     * <p>所有“找不到 / server 不可用”都静默降级为“该侧无缓存”，不抛异常。
     *
     * @param binding 目标绑定（equals 匹配，缓存字段不参与）
     * @return 刷新后的 binding 快照（用于命令反馈展示）；找不到返回入参本身
     */
    public static PortalBinding refreshCachedPortals(@Nullable PortalBinding binding) {
        if (binding == null) {
            return null;
        }
        PortalBindingConfig config = PortalBindingConfig.INSTANCE;
        int idx = configIndexOf(binding);
        if (idx < 0) {
            LOGGER.warn("[PortalBinding] refresh 找不到目标绑定：{}", binding);
            return binding;
        }

        // 1) 清空两侧缓存
        config.clearCachedPortals(binding);

        // 2) 重新按当前区域状态扫描并回写
        PortalBinding fresh = config.get(idx);
        if (fresh == null) {
            return binding;
        }

        MinecraftServer server = RuleCache.getServer();
        if (server == null) {
            return fresh;
        }

        BlockPos a = tryRescan(server, fresh.dimA(), fresh.minA(), fresh.maxA());
        if (a != null) {
            config.updateCachedPortal(fresh, false, a);
            fresh = config.get(idx);
            if (fresh == null) return binding;
        }

        BlockPos b = tryRescan(server, fresh.dimB(), fresh.minB(), fresh.maxB());
        if (b != null) {
            config.updateCachedPortal(fresh, true, b);
            fresh = config.get(idx);
        }

        return fresh != null ? fresh : binding;
    }

    /**
     * 尝试对某一侧重扫：返回区域内最近的门，没有返回 null（保持无缓存）。
     *
     * <p>不再要求「区域内 chunk 已加载」—— {@link PortalLocator#scanRegionOnly} 走 POI 引擎，
     * 会自己把搜索范围内的 chunk 同步加载起来，所以冷区域也能刷出结果。
     * 这是用户显式触发的 {@code refresh}，愿意付这份加载开销；
     * 绑定时的 {@link #prewarm} 仍然保留加载判断，避开「批量绑定把远处区域全拉起来」。
     */
    private static @Nullable BlockPos tryRescan(MinecraftServer server, ResourceKey<Level> dim,
                                                BlockPos min, BlockPos max) {
        ServerLevel level = server.getLevel(dim);
        if (level == null) return null;
        return PortalLocator.scanRegionOnly(level, centerOf(min, max), min, max);
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