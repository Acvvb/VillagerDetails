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
 *       <b>只接受区域内的门</b>，区域外有门也不选；<b>确认整片区域确实没门</b>时
 *       才会在区域内建新门（扫描没覆盖全就返回 null，交给调用方回退原版）。</li>
 *   <li>{@link #scanRegion(ServerLevel, BlockPos, BlockPos, BlockPos)} —— 区域扫描本体：
 *       按瓦片扫满整片区域，并告诉调用方「这次有没有扫全」。</li>
 *   <li>{@link #forceSpawnAt(ServerLevel, BlockPos, BlockPos, BlockPos)} —— 增强模式专用，
 *       不要求落脚点是门，只要求站得住；结果是 {@link CompletableFuture}。</li>
 * </ul>
 *
 * <p>找门统一走 {@link #scanByPoi}（原版 POI 引擎，与 {@code PortalForcer} 找门的实现是同一套），
 * 不再逐格遍历方块；传送一次后结果由 {@link PortalBindingService#cachePortal} 缓存，
 * 后续传送走 O(1) 缓存命中。
 *
 * <p><b>扫描覆盖</b>：单次 POI 调用有半径上限（{@link #POI_SCAN_RADIUS_LIMIT}），
 * 所以区域扫描一律按 {@link #TILE} 分片铺满，不靠「一次半径」硬撑；
 * 分片总量与冷 chunk 数都受预算（{@link #MAX_TILES} / {@link #MAX_COLD_CHUNKS}）约束，
 * 超预算只扫中心一片并把 {@code complete} 标成 false —— 宁可说「没扫全」，也不猜。
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
     * 区域 POI 搜索的<b>单次</b>水平半径上限（取原版 {@code PortalForcer} 主世界找门的 128）。
     *
     * <p>{@link PoiManager#ensureLoadedAndValid} 是<b>同步</b>加载 chunk，一次给的半径越大，
     * 主线程一次要加载的 chunk 越多；所以单次调用永远不超过这个值。
     * 区域本身比这大时不再「夹紧半径了事」，而是按 {@link #TILE} 分片扫（见 {@link #scanRegion}）。
     */
    private static final int POI_SCAN_RADIUS_LIMIT = 128;

    /**
     * 区域扫描的瓦片边长（格）。取单次扫描的安全半径，保证一片之内一定看得全。
     */
    private static final int TILE = POI_SCAN_RADIUS_LIMIT;

    /**
     * 相邻瓦片中心的步进（格），故意小于瓦片边长，让相邻瓦片的覆盖区重叠，不留缝隙。
     *
     * <p>单次 {@link #scanByPoi} 的实际覆盖是「半径向上取整到 chunk」，约 ±(半径 + 16) 格，
     * 步进 {@code TILE * 3 / 4 = 96} 远小于两片覆盖之和，边界上的门不会漏。
     */
    private static final int TILE_STEP = TILE * 3 / 4;

    /**
     * 一次区域扫描最多铺多少片瓦片；超了只扫中心一片并标记「没扫全」。
     *
     * <p>64 片 ≈ 1000×1000 格的区域（实际上绑定的区域上限是 128×64×128，
     * 见 {@code PortalBinding#MAX_SIZE_X}，所以正常绑定只用到 2×2 片）。
     * 这个预算只是给手改配置塞进来的超大区域兜底。
     */
    private static final int MAX_TILES = 64;

    /**
     * 一次区域扫描最多允许同步加载多少「未加载的 chunk 列」；超了同样只扫中心一片。
     *
     * <p>冷 chunk 的代价是<b>生成 + 落盘</b>，几百 ms 到数秒；256 列（16×16）是一次
     * 主动刷新可以接受的卡顿上限，再大就该让玩家先把那一片跑热或缩小选区。
     */
    private static final int MAX_COLD_CHUNKS = 256;

    /**
     * 增强模式的落点结果。
     *
     * @param spawnPos     最终传送落点
     * @param portalBottom 命中区域内的门时为门底坐标（供调用方回写缓存）；
     *                     异步 {@code findSpawn} 找到的普通地面为 {@code null}，<b>不可写缓存</b>
     */
    public record ForceSpawnResult(Vec3 spawnPos, @Nullable BlockPos portalBottom) {
    }

    /**
     * 一次区域扫描的结果（{@link #scanRegion}）。
     *
     * @param portal     离区域中心最近的<b>门底</b>坐标；区域内没扫到是 {@code null}
     * @param complete   这次扫描有没有覆盖<b>整个</b>区域。{@code false} 表示区域超出预算、
     *                   只扫了中心一片 —— 此时 {@code portal == null} <b>不能</b>当成「区域内没门」
     * @param tiles      区域铺满需要多少片瓦片（诊断 / 日志用）
     * @param coldChunks 区域里当前未加载（不到 FULL）的 chunk 列数（诊断 / 日志用）
     */
    public record RegionScan(@Nullable BlockPos portal, boolean complete, int tiles, int coldChunks) {
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
     * 区域外有门不会选；区域内确实没门时才在区域内建新门，绝不跑到区域外。
     *
     * <p>策略：
     * <ol>
     *   <li>{@link #scanRegion}：按瓦片扫完整个区域，取离区域中心最近的门；</li>
     *   <li>扫到了 → 直接用；</li>
     *   <li>扫完、确实没有 → 在区域内建新门；</li>
     *   <li><b>没扫完</b>（区域过大超预算）→ 返回 {@code null}：
     *       既不建门也不猜。「没扫到」和「没有门」是两回事，在这里建门会直接
     *       多出一扇门、破坏玩家的建筑，所以宁可让调用方回退原版传送。</li>
     * </ol>
     *
     * <p>返回 {@code null} 时调用方走原版兜底（{@code EntityPortalMixin} 就是这么处理的）。
     * 本方法签名保持不变，行为只随 {@link RegionScan#complete()} 变化。
     */
    public static @Nullable BlockPos locateInRegion(ServerLevel level, BlockPos center,
                                                    BlockPos min, BlockPos max) {
        RegionScan scan = scanRegion(level, center, min, max);
        if (scan.portal() != null) {
            LOG.debug("[PortalLocator] found portal in region at {}", scan.portal());
            return scan.portal();
        }
        if (!scan.complete()) {
            LOG.warn("[PortalLocator] region scan incomplete ({} tiles, {} cold chunks), "
                            + "refuse to build a new portal, center={}",
                    scan.tiles(), scan.coldChunks(), center);
            return null;
        }

        // 区域内确实没门 → 建新门
        LOG.debug("[PortalLocator] no portal in region, building inside near {}", center);
        return buildInRegion(level, center, min, max);
    }

    /**
     * 按瓦片扫一遍整个区域：返回「离 {@code center} 最近的门底坐标」以及
     * <b>这次扫描有没有覆盖整个区域</b>。
     *
     * <p>为什么分瓦片：{@link #scanByPoi} 单次半径被 {@link #POI_SCAN_RADIUS_LIMIT} 卡着，
     * 若只拿「区域中心到角点的距离」当半径调一次，跨度超过约 300 格的区域外圈<b>永远扫不到</b> ——
     * 于是「区域里明明有门」会被误判成「没门」，进而建出第二扇门（破坏建筑）、
     * 或者把刷新时本来可用的缓存刷没。现在铺满整个区域，每片都带同一个区域裁剪，取全局最近。
     *
     * <p>预算（决定 {@link RegionScan#complete()}）：
     * <ul>
     *   <li>瓦片数 &gt; {@link #MAX_TILES}，或</li>
     *   <li>区域里未加载（不到 FULL）的 chunk 列数 &gt; {@link #MAX_COLD_CHUNKS}</li>
     * </ul>
     * 任一超了就<b>只扫中心一片</b>并标记 {@code complete = false}：只告诉玩家没扫全，
     * 不猜结果、不清缓存、不建门。预算存在的理由：{@link PoiManager#ensureLoadedAndValid}
     * 是主线程同步加载，冷区域一次扫几千个 chunk 会把服务器卡死。
     *
     * <p>绑定的选区本身有尺寸上限（{@code PortalBinding#MAX_SIZE_X} 等），
     * 所以正常绑定最多 2×2 片、一定扫全；本预算只是给手改配置的超大区域兜底。
     */
    public static RegionScan scanRegion(ServerLevel level, BlockPos center, BlockPos min, BlockPos max) {
        // 中心自身就是区域内的门：不用扫，直接命中
        if (inRegion(center, min, max)
                && level.getBlockState(center).getBlock() instanceof NetherPortalBlock) {
            return new RegionScan(bottomOfPortal(level, center, min.getY()), true, 0, 0);
        }

        int tilesX = tileCount(min.getX(), max.getX());
        int tilesZ = tileCount(min.getZ(), max.getZ());
        int tiles = tilesX * tilesZ;
        int coldChunks = countColdChunks(level, min, max);

        if (tiles > MAX_TILES || coldChunks > MAX_COLD_CHUNKS) {
            LOG.warn("[PortalLocator] region too large ({}x{} = {} tiles, {} cold chunks; "
                            + "budgets {} tiles / {} cold): scanning the centre tile only, "
                            + "complete=false, center={}",
                    tilesX, tilesZ, tiles, coldChunks, MAX_TILES, MAX_COLD_CHUNKS, center);
            BlockPos single = scanByPoi(level, center, TILE / 2, min, max);
            return new RegionScan(bottomOfPortalOrNull(level, single, min.getY()), false, tiles, coldChunks);
        }

        BlockPos best = null;
        for (int x = min.getX(); x <= max.getX(); x += TILE_STEP) {
            for (int z = min.getZ(); z <= max.getZ(); z += TILE_STEP) {
                BlockPos hit = scanByPoi(level, new BlockPos(x, center.getY(), z), TILE / 2, min, max);
                if (hit != null && (best == null || closer(hit, best, center))) {
                    best = hit;
                }
            }
        }
        return new RegionScan(bottomOfPortalOrNull(level, best, min.getY()), true, tiles, coldChunks);
    }

    /**
     * 区域内要找多少片瓦片（与 {@link #scanRegion} 的循环边界一致）。
     */
    private static int tileCount(int min, int max) {
        return Math.max(1, max - min) / TILE_STEP + 1;
    }

    /**
     * {@code a} 是否比 {@code b} 更「近」：先比到 {@code origin} 的距离，
     * 等距按 x / y / z 字典序 —— 和 {@link #scanByPoi} 内部的 tie-break 一致，结果可复现。
     */
    private static boolean closer(BlockPos a, BlockPos b, BlockPos origin) {
        int byDistance = Double.compare(a.distSqr(origin), b.distSqr(origin));
        if (byDistance != 0) {
            return byDistance < 0;
        }
        if (a.getX() != b.getX()) return a.getX() < b.getX();
        if (a.getY() != b.getY()) return a.getY() < b.getY();
        return a.getZ() < b.getZ();
    }

    /**
     * 把扫到的门方块归一到门底；{@code hit} 为 null 时直接返回 null。
     */
    private static @Nullable BlockPos bottomOfPortalOrNull(ServerLevel level,
                                                           @Nullable BlockPos hit, int floorY) {
        return hit == null ? null : bottomOfPortal(level, hit, floorY);
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
     * <p>内部就是 {@link #scanRegion} 的「只要结果」版本，分片 / 预算 / 裁剪语义完全一致。
     * 需要区分「没门」和「没扫全」的调用方（刷新、传送）请直接用 {@link #scanRegion}。
     */
    public static @Nullable BlockPos scanRegionOnly(ServerLevel level, BlockPos center,
                                                    BlockPos min, BlockPos max) {
        return scanRegion(level, center, min, max).portal();
    }

    /**
     * 区域里还有多少 chunk 列没到 {@link ChunkStatus#FULL}（只查 (x,z)）。
     *
     * <p>用来做「一次扫描要同步加载多少冷 chunk」的预算判断，{@link #isRegionLoaded} 也走它。
     */
    public static int countColdChunks(ServerLevel level, BlockPos min, BlockPos max) {
        int minChunkX = SectionPos.blockToSectionCoord(min.getX());
        int maxChunkX = SectionPos.blockToSectionCoord(max.getX());
        int minChunkZ = SectionPos.blockToSectionCoord(min.getZ());
        int maxChunkZ = SectionPos.blockToSectionCoord(max.getZ());
        int cold = 0;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (level.getChunk(cx, cz, ChunkStatus.FULL, false) == null) {
                    cold++;
                }
            }
        }
        return cold;
    }

    /** 区域的所有 chunk 列是否都已加载（只查 (x,z)）。 */
    public static boolean isRegionLoaded(ServerLevel level, BlockPos min, BlockPos max) {
        return countColdChunks(level, min, max) == 0;
    }
}