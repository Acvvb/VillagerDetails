package com.villagerdetails.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家选区管理器（方块选区 pos1 / pos2）。
 *
 * <p>每个玩家同一时刻只保存一个选区状态（pos1 + pos2），
 * 采用与 {@link EBSelectionStateCache} 一致的单状态 + 并发 Map 机制。
 *
 * <p>选区会记下<b>设置它时玩家所在的维度</b>：换维度后旧选区立刻作废 ——
 * 服务器每 tick 巡检一次（{@link #clearIfDimensionChanged}），设置选点时也会顺手校验一次，
 * 免得拿主世界选出来的坐标去地狱里绑区域。
 *
 * <p>生命周期（玩家退出 / 停服）由 {@code ServerSessionStates} 扫包接管。
 */
public final class BBSectionStateCache implements SessionState {

    public static final BBSectionStateCache INSTANCE = new BBSectionStateCache();

    private static final Map<UUID, BlockSection> SECTIONS = new ConcurrentHashMap<>();

    /**
     * 选区属于哪个维度（和 {@link #SECTIONS} 同生共死）
     */
    private static final Map<UUID, ResourceKey<Level>> DIMENSIONS = new ConcurrentHashMap<>();

    private BBSectionStateCache() {
    }

    /**
     * 设置玩家的 pos1（保留已设置的 pos2；换过维度的话先把旧选区清掉）
     */
    public static void setPos1(ServerPlayer player, BlockPos pos) {
        clearIfDimensionChanged(player);
        SECTIONS.compute(player.getUUID(), (_, s) ->
                s == null ? new BlockSection(pos, null) : s.withPos1(pos));
        rememberDimension(player);
    }

    /**
     * 设置玩家的 pos2（保留已设置的 pos1；换过维度的话先把旧选区清掉）
     */
    public static void setPos2(ServerPlayer player, BlockPos pos) {
        clearIfDimensionChanged(player);
        SECTIONS.compute(player.getUUID(), (_, s) ->
                s == null ? new BlockSection(null, pos) : s.withPos2(pos));
        rememberDimension(player);
    }

    /**
     * 记下「这个维度」——选区永远是某个维度里的坐标，两角共用
     */
    private static void rememberDimension(ServerPlayer player) {
        DIMENSIONS.put(player.getUUID(), player.level().dimension());
    }

    /**
     * 玩家换过维度、选区却还是上一个维度的话，把它清掉。
     *
     * <p>由服务器 tick 巡检调用；清掉之后调用方负责把空选区推给客户端，
     * 否则客户端的高亮框会一直留着。
     *
     * @return 真的清掉了一个跨维度的旧选区返回 {@code true}
     */
    public static boolean clearIfDimensionChanged(ServerPlayer player) {
        UUID uuid = player.getUUID();
        ResourceKey<Level> recorded = DIMENSIONS.get(uuid);
        if (recorded == null || recorded.equals(player.level().dimension())) {
            return false;
        }
        DIMENSIONS.remove(uuid);
        SECTIONS.remove(uuid);
        return true;
    }

    /**
     * 获取玩家的 pos1
     */
    public static BlockPos getPos1(ServerPlayer player) {
        BlockSection section = SECTIONS.get(player.getUUID());
        return section == null ? null : section.pos1();
    }

    /**
     * 获取玩家的 pos2
     */
    public static BlockPos getPos2(ServerPlayer player) {
        BlockSection section = SECTIONS.get(player.getUUID());
        return section == null ? null : section.pos2();
    }

    /**
     * 玩家是否已有完整选区（pos1 和 pos2 都已设置）。
     *
     * <p>顺手做一次维度校验：命令都是在「拿选区去绑定」之前问这一句，
     * 万一玩家刚换维度、tick 巡检还没来得及清，这里也能挡住旧维度的坐标。
     */
    public static boolean hasSelection(ServerPlayer player) {
        clearIfDimensionChanged(player);
        BlockSection section = SECTIONS.get(player.getUUID());
        return section != null && section.isComplete();
    }

    /**
     * 清除玩家的选区
     */
    public static void clearSelection(ServerPlayer player) {
        clearSelection(player.getUUID());
    }

    /**
     * 清除玩家的选区（只拿得到 UUID 时走这个重载：断线清理就属于这种）
     */
    public static void clearSelection(UUID playerUuid) {
        SECTIONS.remove(playerUuid);
        DIMENSIONS.remove(playerUuid);
    }

    @Override
    public void clearPlayer(UUID playerUuid) {
        clearSelection(playerUuid);
    }

    @Override
    public void clearSession() {
        SECTIONS.clear();
        DIMENSIONS.clear();
    }

    /**
     * 一次选区：pos1 + pos2，两者齐全即视为选区完成。
     */
    public record BlockSection(BlockPos pos1, BlockPos pos2) {

        public BlockSection withPos1(BlockPos pos) {
            return new BlockSection(pos, pos2);
        }

        public BlockSection withPos2(BlockPos pos) {
            return new BlockSection(pos1, pos);
        }

        public boolean isComplete() {
            return pos1 != null && pos2 != null;
        }
    }
}
