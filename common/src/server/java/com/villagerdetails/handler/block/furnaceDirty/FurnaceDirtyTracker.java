package com.villagerdetails.handler.block.furnaceDirty;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.section.item.FurnaceDirtyHighlightConfig;
import com.villagerdetails.network.sync.clientbound.FurnaceDirtySyncChannel;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脏熔炉的<b>全局脏表 + 按区块增量同步</b>（类似实体追踪那套思路，只是粒度换成区块）。
 *
 * <h2>为什么不再「全服广播 + 客户端过滤」</h2>
 * 老方案每个周期给每个玩家发一份「玩家附近」的完整列表，客户端整份替换。它有两个问题：
 * <ul>
 *     <li>客户端只增不减时（例如服务端发的列表里没有某个坐标了），只能靠「每次全量覆盖」兜住，
 *         于是每个玩家每个周期都必须收一份完整列表，数据量随脏熔炉数量增长；</li>
 *     <li>要让列表包含玩家「可能看到」的范围，服务端只能按固定半径扫描，
 *         既扫不到视距外、也管不住客户端缓存的上限。</li>
 * </ul>
 * 现在改成：
 * <ol>
 *     <li>服务端维护<b>全局脏表</b> {@link #GLOBAL_DIRTY}：维度 → 脏熔炉坐标；</li>
 *     <li>每个玩家记录<b>当前已同步的区块集合</b> {@link #VIEWS}（视距按
 *         {@code viewChunkRadius} 折算成正方形区块范围，≤ 0 时跟随服务端视距）；</li>
 *     <li>玩家跨区块 / 换维度时：新进入视距的区块先扫一遍再 <b>ADD</b>，离开视距的区块
 *         <b>REMOVE</b>，客户端只留视距内的坐标；</li>
 *     <li>熔炉状态变化（有东西放进去了 / 烧完了）由周期重扫发现，<b>只推给视距覆盖该坐标的玩家</b>。</li>
 * </ol>
 *
 * <p>于是客户端的表上限 ≈ 视距区块数 × 每区块脏熔炉数，玩家飞多远都不会涨。
 *
 * <h2>线程</h2>
 * 全部在服务端主线程（tick / 网络回调）里读写；用并发容器只是为了「万一别处读一眼」时不出
 * {@code ConcurrentModificationException}。
 */
public final class FurnaceDirtyTracker {

    /**
     * 全局脏表：维度 → 脏熔炉坐标。
     *
     * <p>只保留「至少还有一个玩家视距覆盖」的区块 —— 区块没人看时会被 {@link #pruneUntracked}
     * 摘掉，所以这张表的大小上限同样是「所有在线玩家的视距并集」，不会随世界探索无限增长。
     */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> GLOBAL_DIRTY = new ConcurrentHashMap<>();

    /**
     * 每个玩家「已经同步过」的视距：维度 + 区块集合 + 上次所在区块（用于跳过没移动的玩家）。
     */
    private static final Map<UUID, PlayerView> VIEWS = new ConcurrentHashMap<>();

    private FurnaceDirtyTracker() {
    }

    /**
     * 一个玩家的视距状态。
     *
     * @param dimension   这些区块属于哪个维度
     * @param chunks      已同步过的区块（{@link #chunkKey(int, int)} 打包后的 long）
     * @param centerChunk 上次处理时玩家所在的区块；没变就不用重算
     * @param chunkRadius 上次计算时用的半径（区块数）；配置 / 服务端视距变化时也要重算
     */
    private record PlayerView(ResourceKey<Level> dimension, Set<Long> chunks, long centerChunk,
                              int chunkRadius) {
    }

    // ==============================================================
    // 对外入口（由 FurnaceDirtySyncEvents 接线）
    // ==============================================================

    /**
     * 每 tick 调用一次：更新玩家视距 + 按配置间隔重扫已跟踪的区块。
     */
    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (!RuleCache.isEnabled(RuleType.FURNACE_DIRTY_HIGHLIGHT)) {
            // 规则关着：什么都不扫。刚被关掉的那一下顺手清表 + 让客户端清空
            if (!VIEWS.isEmpty() || !GLOBAL_DIRTY.isEmpty()) {
                resetAll(server);
            }
            return;
        }

        int chunkRadius = resolveViewChunkRadius(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            updateView(player, chunkRadius);
        }

        int interval = FurnaceDirtyHighlightConfig.scanIntervalTicks();
        if (server.getTickCount() % interval == 0) {
            rescanTracked(server);
        }
    }

    /**
     * 本次 tick 使用的「视距半径（区块）」。
     *
     * <p>配置 {@code viewChunkRadius} ≤ 0 时跟随服务端视距
     * （{@code server.properties} 的 {@code view-distance}）：每次 tick 动态读，
     * 改了服务端配置重启后自动跟上，不需要改模组配置。
     *
     * <p>解析逻辑和配置日志共用 {@link FurnaceDirtyHighlightConfig#effectiveViewChunkRadius}，
     * 保证「日志里写的」和「实际扫描的」永远是同一个值。
     *
     * @return 半径（区块）；至少为 1，保证「只覆盖自己所在区块」时也是个合法范围
     */
    private static int resolveViewChunkRadius(MinecraftServer server) {
        return FurnaceDirtyHighlightConfig.effectiveViewChunkRadius(server);
    }

    /**
     * 规则开关变化：关 → 清表并让所有客户端清空；开 → 丢掉视距记录，下个 tick 重新扫一遍再 ADD。
     */
    public static void onRuleChanged(MinecraftServer server, boolean enabled) {
        if (server == null) {
            return;
        }
        if (enabled) {
            // 脏表在关的时候已经清空；视距记录也丢掉，下个 tick 会按新状态重新补发
            VIEWS.clear();
        } else {
            resetAll(server);
        }
    }

    /**
     * 玩家下线：丢掉他的视距记录，并把他独自覆盖的区块从脏表里摘掉（防泄漏）。
     */
    public static void onPlayerLeave(ServerPlayer player) {
        if (player == null) {
            return;
        }
        PlayerView view = VIEWS.remove(player.getUUID());
        if (view != null) {
            pruneUntracked(view.dimension(), view.chunks());
        }
    }

    /**
     * 停服：静态表全部清掉（下一局重新建立）。
     */
    public static void reset() {
        VIEWS.clear();
        GLOBAL_DIRTY.clear();
    }

    // ==============================================================
    // 玩家视距
    // ==============================================================

    /**
     * 更新一个玩家的视距：把「新进入视距的区块」ADD 过去、「离开视距的区块」REMOVE 掉。
     *
     * <p>只在「跨区块 / 换维度 / 半径变化 / 首次处理」时真正干活，其余 tick 只做几次整数比较。
     *
     * @param chunkRadius 视距半径（区块数），已由 {@link #resolveViewChunkRadius} 解析过
     */
    private static void updateView(ServerPlayer player, int chunkRadius) {
        UUID id = player.getUUID();
        if (!FurnaceDirtySyncChannel.INSTANCE.canSendTo(player)) {
            // 客户端没装 mod / 自己关了显示：丢掉视距记录。恢复可用后会被当成「新玩家」整批补发
            PlayerView dropped = VIEWS.remove(id);
            if (dropped != null) {
                pruneUntracked(dropped.dimension(), dropped.chunks());
            }
            return;
        }

        ServerLevel level = player.level();
        ResourceKey<Level> dimension = level.dimension();
        BlockPos eye = player.blockPosition();
        long centerChunk = chunkKey(eye.getX() >> 4, eye.getZ() >> 4);

        PlayerView view = VIEWS.get(id);
        if (view != null && view.chunkRadius() == chunkRadius
                && view.centerChunk() == centerChunk && view.dimension().equals(dimension)) {
            return; // 没跨区块、半径也没变：这一 tick 什么都不用做
        }

        Set<Long> desired = chunksAround(eye, chunkRadius);
        boolean dimensionChanged = view != null && !view.dimension().equals(dimension);
        Set<Long> previous = view == null || dimensionChanged ? Set.of() : view.chunks();

        // 换维度：先让客户端把上一维度的表整体清掉，旧坐标不会残留在本地
        if (dimensionChanged) {
            FurnaceDirtySyncChannel.INSTANCE.sendClear(player, view.dimension().identifier().toString());
        }

        // 新进入视距的区块：脏表里可能还没有它们，先扫一遍再把这批区块里的脏点 ADD 过去。
        // scanChunk 会顺带把差异推给「本来就看着这些区块」的其他玩家。
        Set<Long> addedChunks = new HashSet<>(desired);
        addedChunks.removeAll(previous);
        for (long chunk : addedChunks) {
            scanChunk(level, chunk);
        }
        List<BlockPos> added = dirtyInChunks(dimension, addedChunks);
        FurnaceDirtySyncChannel.INSTANCE.send(player, level, true, added);

        // 离开视距的区块：把这些坐标 REMOVE 掉，并把没人再看的区块从脏表里摘掉
        Set<Long> removedChunks = new HashSet<>(previous);
        removedChunks.removeAll(desired);
        List<BlockPos> removed = removedChunks.isEmpty()
                ? List.of()
                : dirtyInChunks(dimension, removedChunks);
        if (!removed.isEmpty()) {
            FurnaceDirtySyncChannel.INSTANCE.send(player, level, false, removed);
        }

        VIEWS.put(id, new PlayerView(dimension, desired, centerChunk, chunkRadius));

        // 摘除必须放在 VIEWS 更新之后：否则会把自己刚放弃的区块误判成「还有人看」
        if (!removedChunks.isEmpty()) {
            pruneUntracked(dimension, removedChunks);
        }
        if (dimensionChanged) {
            // 换维度时旧维度不再由这个玩家覆盖：若他是最后一个，旧维度的脏数据也要摘掉
            pruneUntracked(view.dimension(), view.chunks());
        }
    }

    // ==============================================================
    // 全局扫描
    // ==============================================================

    /**
     * 周期重扫「当前被任意玩家视距覆盖」的区块，把状态变化（变脏 / 变干净）推给看着它的玩家。
     */
    private static void rescanTracked(MinecraftServer server) {
        Map<ResourceKey<Level>, Set<Long>> tracked = trackedChunksByDimension();
        if (tracked.isEmpty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Set<Long> chunks = tracked.get(level.dimension());
            if (chunks == null || chunks.isEmpty()) {
                continue;
            }
            for (long chunk : chunks) {
                scanChunk(level, chunk);
            }
        }
    }

    /**
     * 扫一个区块并把差异灌进全局脏表；差异会推给所有视距覆盖该区块的玩家。
     *
     * <p>区块还没加载（{@code getChunkNow} 返回 null）时直接跳过 —— 不因为扫描去加载 / 生成区块。
     */
    private static void scanChunk(ServerLevel level, long chunk) {
        ResourceKey<Level> dimension = level.dimension();
        LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunkX(chunk), chunkZ(chunk));
        if (levelChunk == null) {
            return;
        }

        List<BlockPos> fresh = FurnaceDirtyScanner.scanChunk(level, levelChunk);
        Set<BlockPos> freshSet = new HashSet<>(fresh);
        Set<BlockPos> old = new HashSet<>();
        Set<BlockPos> bucket = GLOBAL_DIRTY.get(dimension);
        if (bucket != null) {
            for (BlockPos pos : bucket) {
                if (chunkKey(pos.getX() >> 4, pos.getZ() >> 4) == chunk) {
                    old.add(pos);
                }
            }
        }

        List<BlockPos> added = new ArrayList<>();
        for (BlockPos pos : freshSet) {
            if (!old.contains(pos)) {
                added.add(pos);
            }
        }
        List<BlockPos> removed = new ArrayList<>();
        for (BlockPos pos : old) {
            if (!freshSet.contains(pos)) {
                removed.add(pos);
            }
        }
        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }
        sortByPos(added);
        sortByPos(removed);

        Set<BlockPos> dirty = dirtyBucket(dimension);
        if (!added.isEmpty()) {
            dirty.addAll(added);
        }
        if (!removed.isEmpty()) {
            removed.forEach(dirty::remove);
        }

        notifyWatchers(level, dimension, added, removed);
    }

    /**
     * 把一批变化推给「视距覆盖这些坐标」的玩家；每个玩家最多各一包 ADD / REMOVE。
     */
    private static void notifyWatchers(ServerLevel level, ResourceKey<Level> dimension,
                                       List<BlockPos> added, List<BlockPos> removed) {
        MinecraftServer server = level.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerView view = VIEWS.get(player.getUUID());
            if (view == null || !view.dimension().equals(dimension)) {
                continue;
            }
            if (!FurnaceDirtySyncChannel.INSTANCE.canSendTo(player)) {
                PlayerView dropped = VIEWS.remove(player.getUUID());
                if (dropped != null) {
                    pruneUntracked(dropped.dimension(), dropped.chunks());
                }
                continue;
            }
            List<BlockPos> playerAdded = filterByChunks(added, view.chunks());
            List<BlockPos> playerRemoved = filterByChunks(removed, view.chunks());
            if (!playerAdded.isEmpty()) {
                FurnaceDirtySyncChannel.INSTANCE.send(player, level, true, playerAdded);
            }
            if (!playerRemoved.isEmpty()) {
                FurnaceDirtySyncChannel.INSTANCE.send(player, level, false, playerRemoved);
            }
        }
    }

    // ==============================================================
    // 脏表 / 视距维护
    // ==============================================================

    /**
     * 把「已经没有任何玩家视距覆盖」的区块从脏表里摘掉。
     */
    private static void pruneUntracked(ResourceKey<Level> dimension, Set<Long> candidates) {
        Set<BlockPos> bucket = GLOBAL_DIRTY.get(dimension);
        if (bucket == null || bucket.isEmpty()) {
            return;
        }
        Set<Long> tracked = trackedChunks(dimension);
        Set<Long> gone = new HashSet<>(candidates);
        gone.removeAll(tracked);
        if (gone.isEmpty()) {
            return;
        }
        bucket.removeIf(pos -> gone.contains(chunkKey(pos.getX() >> 4, pos.getZ() >> 4)));
    }

    /**
     * 让所有客户端清空，并丢掉全部状态（规则关闭 / 停服前）。
     */
    private static void resetAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerView view = VIEWS.get(player.getUUID());
            if (view == null) {
                continue;
            }
            if (FurnaceDirtySyncChannel.INSTANCE.canSendTo(player)) {
                FurnaceDirtySyncChannel.INSTANCE.sendClear(player, view.dimension().identifier().toString());
            }
        }
        VIEWS.clear();
        GLOBAL_DIRTY.clear();
    }

    /**
     * 所有玩家视距并集（按维度分组）
     */
    private static Map<ResourceKey<Level>, Set<Long>> trackedChunksByDimension() {
        Map<ResourceKey<Level>, Set<Long>> tracked = new HashMap<>();
        for (PlayerView view : VIEWS.values()) {
            tracked.computeIfAbsent(view.dimension(), _ -> new HashSet<>()).addAll(view.chunks());
        }
        return tracked;
    }

    /**
     * 某个维度里当前被覆盖的区块
     */
    private static Set<Long> trackedChunks(ResourceKey<Level> dimension) {
        Set<Long> tracked = new HashSet<>();
        for (PlayerView view : VIEWS.values()) {
            if (view.dimension().equals(dimension)) {
                tracked.addAll(view.chunks());
            }
        }
        return tracked;
    }

    private static Set<BlockPos> dirtyBucket(ResourceKey<Level> dimension) {
        return GLOBAL_DIRTY.computeIfAbsent(dimension, _ -> ConcurrentHashMap.newKeySet());
    }

    /**
     * 脏表里落在给定区块集合内的坐标（排好序，保证同样的内容每次生成的包字节稳定）
     */
    private static List<BlockPos> dirtyInChunks(ResourceKey<Level> dimension, Set<Long> chunks) {
        Set<BlockPos> bucket = GLOBAL_DIRTY.get(dimension);
        if (bucket == null || bucket.isEmpty() || chunks.isEmpty()) {
            return List.of();
        }
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : bucket) {
            if (chunks.contains(chunkKey(pos.getX() >> 4, pos.getZ() >> 4))) {
                result.add(pos);
            }
        }
        sortByPos(result);
        return result;
    }

    /**
     * 只留下落在给定区块集合内的坐标（用于「推给某个玩家」）。
     */
    private static List<BlockPos> filterByChunks(List<BlockPos> positions, Set<Long> chunks) {
        if (positions.isEmpty()) {
            return List.of();
        }
        List<BlockPos> result = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            if (chunks.contains(chunkKey(pos.getX() >> 4, pos.getZ() >> 4))) {
                result.add(pos);
            }
        }
        return result;
    }

    // ==============================================================
    // 区块坐标工具
    // ==============================================================

    /**
     * 以玩家所在区块为中心、半径 N 个区块的正方形区块集合（(2N+1)×(2N+1)）。
     *
     * <p>注意这里的分隔线是<b>区块</b>：{@code chunkRadius = 2} 就是 5×5 个区块，
     * 和 {@code view-distance} 的语义一致（它本身也是区块数）。
     */
    private static Set<Long> chunksAround(BlockPos pos, int chunkRadius) {
        int centerX = pos.getX() >> 4;
        int centerZ = pos.getZ() >> 4;
        Set<Long> chunks = new HashSet<>();
        for (int chunkX = centerX - chunkRadius; chunkX <= centerX + chunkRadius; chunkX++) {
            for (int chunkZ = centerZ - chunkRadius; chunkZ <= centerZ + chunkRadius; chunkZ++) {
                chunks.add(chunkKey(chunkX, chunkZ));
            }
        }
        return chunks;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int chunkX(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    private static int chunkZ(long chunkKey) {
        return (int) chunkKey;
    }

    private static void sortByPos(List<BlockPos> positions) {
        positions.sort(Comparator.comparingLong(BlockPos::asLong));
    }
}
