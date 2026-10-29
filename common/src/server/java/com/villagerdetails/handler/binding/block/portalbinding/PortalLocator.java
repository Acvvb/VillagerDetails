package com.villagerdetails.handler.binding.block.portalbinding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.portal.PortalForcer;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 传送门方块的定位与创建工具。
 *
 * <p>主要入口：
 * <ul>
 *   <li>{@link #locateOrCreate(ServerLevel, BlockPos)} —— 无区域信息时的通用定位，
 *       以给定点为中心在 {@value #SCAN_RADIUS} 水平半径内找门；</li>
 *   <li>{@link #locateInRegion(ServerLevel, BlockPos, BlockPos, BlockPos)} —— 绑定区域专用，
 *       <b>只接受区域内的门</b>，区域外有门也不选，区域内没门时在区域内建新门。</li>
 *   <li>{@link #forceSpawnAt(ServerLevel, BlockPos, BlockPos, BlockPos)} —— 增强模式专用，
 *       不要求落脚点是门，只要求站得住；结果是 {@link CompletableFuture}。</li>
 * </ul>
 *
 * <p>找门统一走 {@link #scanByPoi}（原版 POI 引擎，与 {@code PortalForcer} 找门的实现是同一套），
 * 不再逐格遍历方块；传送一次后结果由 {@link PortalBindingService#cachePortal} 缓存，
 * 后续传送走 O(1) 缓存命中。
 *
 * <p>所有方法均要求调用方在服务器主线程执行，内部不处理并发。
 */
public final class PortalLocator {

    private static final Logger LOG = LogManager.getLogger(PortalLocator.class);

    /** 附近找门时的水平半径（仅 {@link #locateOrCreate} 用）。 */
    public static final int SCAN_RADIUS = 16;

    /** createPortal 的轴向尝试顺序，X 优先，失败再试 Z，避免强制 X 在窄空间失败。 */
    private static final Direction.Axis[] PORTAL_AXES = { Direction.Axis.X, Direction.Axis.Z };

    /**
     * 区域 POI 搜索的水平半径上限（取原版 {@code PortalForcer} 主世界找门的 128）。
     *
     * <p>{@link PoiManager#ensureLoadedAndValid} 是<b>同步</b>加载 chunk，区域异常大时按真实跨度
     * 搜索会把主线程卡住；超出上限只搜中心附近这一大片，并告警。
     */
    private static final int POI_SCAN_RADIUS_LIMIT = 128;

    /**
     * 增强模式的落点结果。
     *
     * @param spawnPos     最终传送落点
     * @param portalBottom 命中区域内的门时为门底坐标（供调用方回写缓存）；
     *                     异步 {@code findSpawn} 找到的普通地面为 {@code null}，<b>不可写缓存</b>
     */
    public record ForceSpawnResult(Vec3 spawnPos, @Nullable BlockPos portalBottom) {
    }

    private PortalLocator() {
    }

    // ==================== 找门：POI 公共实现 ====================

    /**
     * 用原版 POI 引擎在 {@code center} 周围 {@code radius} 的水平方框内，找离它最近的门方块。
     *
     * <p>所有「找现成的门」都走这里，跟 {@code PortalForcer#findClosestPortalPosition} 同一套：
     * <ol>
     *   <li>{@link PoiManager#ensureLoadedAndValid} 先把搜索范围内的 chunk / POI 段加载起来，
     *       所以冷区域（chunk 未加载）也能查到；</li>
     *   <li>{@link PoiManager#getInSquare} 只按 <b>X/Z</b> 的 |dx|、|dz| 过滤，不对 Y 做任何判断，
     *       因此 {@code radius} 是<b>水平</b>半径，Y 方向不受限；</li>
     *   <li>{@code Occupancy.ANY} 是必需的 —— 传送门 POI 注册时 {@code maxTickets = 0}，
     *       {@code HAS_SPACE} / {@code IS_OCCUPIED} 会把它们全部过滤掉；</li>
     *   <li>结果再复核方块真的是门 —— POI 记录由 {@code Level#onBlockStateChange} 随方块增删同步，
     *       这层只是兜底。</li>
     * </ol>
     *
     * <p>返回的是<b>门方块</b>坐标（可能是门里任意一格）；需要门底请再过 {@link #bottomOfPortal}。
     *
     * @param radius 水平半径（格）；{@code <= 0} 按 1 处理，且被 {@link #POI_SCAN_RADIUS_LIMIT} 夹紧
     *               —— {@code ensureLoadedAndValid} 是同步加载 chunk，半径过大会卡主线程
     * @param min    {@code min} 与 {@code max} 都非 null 时只接受严格落在该区域内的门；
     *               任一为 null 则不做区域裁剪
     * @param max    区域另一角
     */
    public static @Nullable BlockPos scanByPoi(ServerLevel level, BlockPos center, int radius,
                                               @Nullable BlockPos min, @Nullable BlockPos max) {
        int limit = Math.max(radius, 1);
        if (limit > POI_SCAN_RADIUS_LIMIT) {
            LOG.warn("[PortalLocator] POI scan radius {} clamped to {}, center={}",
                    limit, POI_SCAN_RADIUS_LIMIT, center);
            limit = POI_SCAN_RADIUS_LIMIT;
        }

        // 先把范围内的 POI 段加载起来，否则一条记录都查不到
        PoiManager poiManager = level.getPoiManager();
        poiManager.ensureLoadedAndValid(level, center, limit);

        return poiManager.getInSquare(
                        holder -> holder.is(PoiTypes.NETHER_PORTAL),
                        center, limit, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos)
                // 区域裁剪 + 复核方块（POI 与方块状态已同步，这里只是兜底）
                .filter(pos -> (min == null || max == null || inRegion(pos, min, max))
                        && level.getBlockState(pos).getBlock() instanceof NetherPortalBlock)
                // 离 center 最近的，等距按坐标字典序
                .min(Comparator
                        .comparingDouble((BlockPos p) -> p.distSqr(center))
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getZ))
                .orElse(null);
    }

    // ==================== 无区域：通用定位 ====================

    /**
     * 确保 {@code center} 附近存在一个可用传送门，返回门底坐标；全部失败返回 {@code null}。
     *
     * <p>顺序：自身是门 → POI 扫描（{@value #SCAN_RADIUS} 水平半径）→ {@link PortalForcer} 创建
     * → 简易 2×3 门兜底。用于 {@code PortalDestination} 不带区域信息的旧路径。
     */
    public static @Nullable BlockPos locateOrCreate(ServerLevel level, BlockPos center) {
        if (level.getBlockState(center).getBlock() instanceof NetherPortalBlock) {
            LOG.debug("[PortalLocator] center itself is portal at {}", center);
            return bottomOfPortal(level, center);
        }
        BlockPos found = findPortalNear(level, center);
        if (found != null) {
            LOG.debug("[PortalLocator] found nearby portal at {}", found);
            return found;
        }
        BlockPos created = createPortalAt(level, center);
        if (created != null) {
            LOG.debug("[PortalLocator] created portal at {}", created);
            return created;
        }
        LOG.debug("[PortalLocator] no portal near {}, building simple portal", center);
        return placeSimplePortal(level, center);
    }

    // ==================== 有区域：只扫区域内 ====================

    /**
     * 在绑定区域内定位或创建传送门。
     *
     * <p>与 {@link #locateOrCreate} 的区别：<b>只接受区域内的门</b>。
     * 区域外有门不会选；区域内没门时在区域内建新门，绝不跑到区域外。
     *
     * <p>策略：
     * <ol>
     *   <li>{@link #scanRegionOnly}：中心自身是区域内门 → 用中心；否则用原版 POI 引擎
     *       在区域内找离中心最近的门；等距按坐标字典序</li>
     *   <li>区域内没门 → 在区域内建新门</li>
     * </ol>
     *
     * <p>区域内找门不做体积阈值判断——绑定选区通常就是玩家手动框的一小片，
     * 且首次传送后结果会缓存（{@code cachePortal}），后续走 O(1) 命中。
     */
    public static @Nullable BlockPos locateInRegion(ServerLevel level, BlockPos center,
                                                    BlockPos min, BlockPos max) {
        BlockPos found = scanRegionOnly(level, center, min, max);
        if (found != null) {
            LOG.debug("[PortalLocator] found portal in region at {}", found);
            return found;
        }

        // 区域内没门 → 建新门
        LOG.debug("[PortalLocator] no portal in region, building inside near {}", center);
        return buildInRegion(level, center, min, max);
    }

    /**
     * 在区域内找离 {@code center} 最近的门方块；没有返回 null。
     *
     * <p>半径按区域的<b>水平</b>跨度算（{@link #scanByPoi} 不看 Y），Y 范围交给区域裁剪，
     * 所以把 Y 跨度算进半径只会白白多加载 chunk。
     */
    private static @Nullable BlockPos scanRegionClosest(ServerLevel level,
                                                        BlockPos min, BlockPos max, BlockPos center) {
        int radiusX = Math.max(Math.abs(center.getX() - min.getX()), Math.abs(center.getX() - max.getX()));
        int radiusZ = Math.max(Math.abs(center.getZ() - min.getZ()), Math.abs(center.getZ() - max.getZ()));
        return scanByPoi(level, center, Math.max(radiusX, radiusZ), min, max);
    }

    /**
     * 在区域内建门：先试 {@link PortalForcer}（结果必须在区域内），
     * 否则在区域内向下找实心块建简易 2×3 门。
     *
     * <p>PortalForcer 可能把门建到区域外，这种结果直接丢弃，改用简易门保证位置可控。
     */
    private static @Nullable BlockPos buildInRegion(ServerLevel level, BlockPos center,
                                                    BlockPos min, BlockPos max) {
        BlockPos created = createPortalAt(level, center);
        if (created != null && inRegion(created, min, max)) {
            return created;
        }
        return placeSimplePortalInRegion(level, center, min, max);
    }

    /**
     * 在区域内向下找实心块建 2×3 门；从中心向下扫到区域底部，遇到区域底就停。
     */
    private static @Nullable BlockPos placeSimplePortalInRegion(ServerLevel level, BlockPos center,
                                                                BlockPos min, BlockPos max) {
        BlockPos.MutableBlockPos cursor = center.mutable();
        BlockPos floor = null;
        for (int y = center.getY(); y >= min.getY() && y > level.getMinY(); y--) {
            cursor.setY(y);
            if (!isChunkLoaded(level, cursor)) continue;
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
                floor = cursor.above().immutable();
                break;
            }
        }
        if (floor == null) {
            LOG.warn("[PortalLocator] placeSimplePortalInRegion failed, no solid block in region below {}",
                    center);
            return null;
        }
        BlockPos built = tryBuildPortal(level, floor, Direction.Axis.X);
        if (built == null) built = tryBuildPortal(level, floor, Direction.Axis.Z);
        if (built == null || !inRegion(built, min, max)) {
            LOG.warn("[PortalLocator] placeSimplePortalInRegion failed to build inside region near {}",
                    center);
            return null;
        }
        return built;
    }

    // ==================== 增强模式：安全落脚点（异步） ====================

    /**
     * 增强模式专用：给出 {@code pos} 附近一个<b>能站住</b>的落点，结果异步返回。
     *
     * <p>顺序：
     * <ol>
     *   <li>带区域且区域内已有门 → 立即完成，值为门底中心，并带上 {@code portalBottom}
     *       供调用方回写缓存；</li>
     *   <li>其余情况交给原版 {@link PlayerSpawnFinder#findSpawn}：它自己带
     *       {@code SPAWN_SEARCH} ticket 逐块加载 chunk、逐个候选试错，所以是异步的。
     *       这条路找到的是<b>普通地面</b>，{@code portalBottom} 恒为 {@code null}。</li>
     * </ol>
     *
     * <p>因为 POI 扫描自己会加载 chunk（{@code ensureLoadedAndValid}），这里不需要再按
     * 「中心 chunk 是否已加载」提前返回——那会让跨维度绑定在冷存档下永远扫不到门。
     *
     * <p>结果的线程：原版把回调挂在 {@code MinecraftServer}（它就是个 {@code Executor}，
     * 即服务端主线程队列）上，所以 future 在主线程完成；不放心仍旧过
     * {@code server.execute} 包一层再传送。
     *
     * <p>原版找不到落脚点时 future 会<b>异常完成</b>（内部包成 CrashReport，永不返回 null），
     * 这里统一转成 {@code null} —— 调用方只需判空，不必处理异常。
     *
     * @param regionMin 绑定区域一角；{@code null} 表示不带区域（旧构造的目的地）
     * @param regionMax 绑定区域另一角；{@code null} 表示不带区域
     */
    public static CompletableFuture<ForceSpawnResult> forceSpawnAt(ServerLevel level, BlockPos pos,
                                                                   @Nullable BlockPos regionMin,
                                                                   @Nullable BlockPos regionMax) {
        // ① 区域内已有门 → 直接用门，并把门底带出去供调用方回写缓存
        if (regionMin != null && regionMax != null) {
            BlockPos portal = scanRegionOnly(level, pos, regionMin, regionMax);
            if (portal != null) {
                return CompletableFuture.completedFuture(
                        new ForceSpawnResult(Vec3.atBottomCenterOf(portal), portal));
            }
        }

        // ② 区域内没门 → 交给原版异步找地面；这条路给的不是门，portalBottom 必须为 null
        return PlayerSpawnFinder.findSpawn(level, pos)
                .thenApply(
                        spawn -> new ForceSpawnResult(spawn, null))
                .exceptionally(t -> {
                    LOG.warn("[PortalLocator] PlayerSpawnFinder.findSpawn failed, center={}", pos, t);
                    return null;
                });
    }

    // ==================== 门底 ====================

    /**
     * 从门内的任意方块向下走到门最底格。
     *
     * <p>不钳制 Y：调用方自己保证向下不会离开关心范围（世界底部由方块本身兼容）。
     */
    public static BlockPos bottomOfPortal(ServerLevel level, BlockPos p) {
        return bottomOfPortal(level, p, level.getMinY());
    }

    /**
     * 向下走到门最底格，但<b>不低于 {@code floorY}</b>。
     *
     * <p>区域路径传 {@code min.getY()}：门的黑曜石框架可能垫在区域下界之外，
     * 不钳制的话缓存到的门底坐标会落到区域外，下次按区域选门时就对不上。
     */
    public static BlockPos bottomOfPortal(ServerLevel level, BlockPos p, int floorY) {
        BlockPos bottom = p;
        while (bottom.getY() > floorY
                && level.getBlockState(bottom.below()).getBlock() instanceof NetherPortalBlock) {
            bottom = bottom.below();
        }
        return bottom;
    }

    // ==================== 附近找门（locateOrCreate 用） ====================

    /**
     * 在 {@value #SCAN_RADIUS} 水平半径内找最近的门，归一到门底；没有返回 null。
     *
     * <p>以前是手写的壳层扫描（{@code SCAN_RADIUS}³ 次 {@code getBlockState}），
     * 现在跟区域路径共用 {@link #scanByPoi}，不再逐格遍历。
     */
    private static @Nullable BlockPos findPortalNear(ServerLevel level, BlockPos center) {
        if (level.getBlockState(center).getBlock() instanceof NetherPortalBlock) {
            return bottomOfPortal(level, center);
        }
        BlockPos hit = scanByPoi(level, center, SCAN_RADIUS, null, null);
        return hit == null ? null : bottomOfPortal(level, hit);
    }

    // ==================== 创建门 ====================

    private static @Nullable BlockPos createPortalAt(ServerLevel level, BlockPos center) {
        PortalForcer portalForcer = new PortalForcer(level);
        for (Direction.Axis axis : PORTAL_AXES) {
            try {
                Optional<BlockUtil.FoundRectangle> result =
                        portalForcer.createPortal(center, axis);
                if (result.isPresent()) {
                    return result.get().minCorner;
                }
            } catch (Throwable t) {
                LOG.warn("[PortalLocator] PortalForcer.createPortal failed, center={}, axis={}",
                        center, axis, t);
            }
        }
        return null;
    }

    private static @Nullable BlockPos placeSimplePortal(ServerLevel level, BlockPos center) {
        BlockPos.MutableBlockPos cursor = center.mutable();
        BlockPos floor = null;
        for (int y = center.getY(); y > level.getMinY(); y--) {
            cursor.setY(y);
            if (!isChunkLoaded(level, cursor)) continue;
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
                floor = cursor.above().immutable();
                break;
            }
        }
        if (floor == null) {
            LOG.warn("[PortalLocator] placeSimplePortal failed, no solid block below {}", center);
            return null;
        }
        BlockPos built = tryBuildPortal(level, floor, Direction.Axis.X);
        if (built == null) built = tryBuildPortal(level, floor, Direction.Axis.Z);
        if (built == null) {
            LOG.warn("[PortalLocator] placeSimplePortal failed to build full portal near {}", center);
        }
        return built;
    }

    private static @Nullable BlockPos tryBuildPortal(ServerLevel level, BlockPos base, Direction.Axis axis) {
        int dx = axis == Direction.Axis.X ? 1 : 0;
        int dz = axis == Direction.Axis.Z ? 1 : 0;

        for (int w = 0; w < 2; w++) {
            for (int h = 0; h < 3; h++) {
                BlockPos p = base.offset(dx * w, h, dz * w);
                if (!isChunkLoaded(level, p)) return null;
                BlockState s = level.getBlockState(p);
                if (!s.isAir() && !s.canBeReplaced()) return null;
            }
        }

        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState()
                .setValue(NetherPortalBlock.AXIS, axis);

        for (int w = 0; w < 2; w++) {
            level.setBlockAndUpdate(base.offset(dx * w, -1, dz * w), obsidian);
            level.setBlockAndUpdate(base.offset(dx * w, 3, dz * w), obsidian);
        }
        for (int h = 0; h < 3; h++) {
            level.setBlockAndUpdate(base.offset(-dx, h, -dz), obsidian);
            level.setBlockAndUpdate(base.offset(dx * 2, h, dz * 2), obsidian);
        }
        for (int w = 0; w < 2; w++) {
            for (int h = 0; h < 3; h++) {
                level.setBlockAndUpdate(base.offset(dx * w, h, dz * w), portal);
            }
        }
        return base;
    }

    // ==================== 辅助 ====================

    private static boolean inRegion(BlockPos p, BlockPos min, BlockPos max) {
        return p.getX() >= min.getX() && p.getX() <= max.getX()
                && p.getY() >= min.getY() && p.getY() <= max.getY()
                && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
    }

    private static boolean isChunkLoaded(ServerLevel level, BlockPos pos) {
        return isChunkLoaded(level, pos.getX(), pos.getZ());
    }

    private static boolean isChunkLoaded(ServerLevel level, int x, int z) {
        return level.getChunk(
                SectionPos.blockToSectionCoord(x),
                SectionPos.blockToSectionCoord(z),
                ChunkStatus.FULL,
                false
        ) != null;
    }

    /**
     * 只扫描区域内的门，返回离 {@code center} 最近的那扇门底坐标；没门返回 null。
     * <b>不建门</b>——供预热和刷新使用，避免 bind 时意外建房。
     *
     * <p>语义与 {@link #locateInRegion} 的扫描阶段一致：走原版 POI 引擎在区域内找，
     * 离 center 最近，等距按坐标字典序。区别只是不进入建门兜底。
     */
    public static @Nullable BlockPos scanRegionOnly(ServerLevel level, BlockPos center,
                                                    BlockPos min, BlockPos max) {
        if (inRegion(center, min, max)
                && level.getBlockState(center).getBlock() instanceof NetherPortalBlock) {
            return bottomOfPortal(level, center, min.getY());
        }
        BlockPos found = scanRegionClosest(level, min, max, center);
        return found == null ? null : bottomOfPortal(level, found, min.getY());
    }

    /** 区域的所有 chunk 列是否都已加载（只查 (x,z)）。 */
    public static boolean isRegionLoaded(ServerLevel level, BlockPos min, BlockPos max) {
        int minChunkX = SectionPos.blockToSectionCoord(min.getX());
        int maxChunkX = SectionPos.blockToSectionCoord(max.getX());
        int minChunkZ = SectionPos.blockToSectionCoord(min.getZ());
        int maxChunkZ = SectionPos.blockToSectionCoord(max.getZ());
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (level.getChunk(cx, cz, ChunkStatus.FULL, false) == null) return false;
            }
        }
        return true;
    }
}