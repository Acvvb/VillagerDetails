package com.villagerdetails.handler.player.blockSitting;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.cache.impl.PlayerBlockSittingPreference;
import com.villagerdetails.entity.BlockSeatEntity;
import com.villagerdetails.entity.ModEntities;
import com.villagerdetails.event.impl.register.PlayerBlockSittingSectionManager;import com.villagerdetails.rule.blockSitting.BlockSittingSeat;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 「坐在楼梯 / 半砖上」的业务处理器。
 *
 * <p>事件注册已经挪到 {@link PlayerBlockSittingSectionManager}；这里只处理
 * <b>能不能坐、怎么坐、什么时候收掉座位</b>。
 *
 * <p>两条线（与之前一致）：
 * <ol>
 *     <li>{@link #trySit}：由入口类在「主手空手右键」时调用 → 生成看不见的
 *         {@link BlockSeatEntity} 并让玩家 {@code startRiding}；</li>
 *     <li>{@link #onEndServerTick}：巡检每个座位，该收的时候收掉。</li>
 * </ol>
 *
 * <p>只在服务端主线程跑，所以几个 {@link HashMap} 就够。
 */
public final class PlayerBlockSittingHandler {

    private PlayerBlockSittingHandler() {
    }

    private static final Logger LOGGER = LogManager.getLogger(PlayerBlockSittingHandler.class);

    private static final RuleType RULE = RuleType.PLAYER_BLOCK_SITTING;

    /** 正在坐着的人：乘客 UUID → 他骑的那个座位实体 */
    private static final Map<UUID, BlockSeatEntity> SEATS = new HashMap<>();

    /** 补发乘客包的距离：和座位实体 8 个区块的跟踪距离对得上就够了 */
    private static final double TRACK_DISTANCE = 96.0D;

    /** 每隔多少 tick 给附近玩家补发一次乘客包 */
    private static final int RESYNC_INTERVAL_TICKS = 10;

    /** 刚坐下的人还要给本人补发几次乘客包（每 tick 减 1） */
    private static final Map<UUID, Integer> RESEND = new HashMap<>();

    /** 补发次数（10 tick = 0.5 秒） */
    private static final int RESEND_TICKS = 10;

    /** 刚坐下 / 刚起身的人：这之后若干 tick 内原版会误发「建筑高度上限」提示 */
    private static final Map<UUID, Integer> IGNORE_BUILD_LIMIT = new HashMap<>();

    /** 误发窗口的持续时间 */
    private static final int IGNORE_BUILD_LIMIT_TICKS = 10;

    // ==============================================================
    // 由 SectionManager 调用的生命周期清理
    // ==============================================================

    /**
     * 服务端停止时清空所有状态（由 {@code ServerLifecycleEvents.SERVER_STOPPING} 调用）
     */
    public static void clearState() {
        SEATS.clear();
        RESEND.clear();
        IGNORE_BUILD_LIMIT.clear();
    }

    // ==============================================================
    // 业务入口：尝试坐下（由 SectionManager 委托）
    // ==============================================================

    /**
     * 尝试坐下。入口处（SectionManager）已经确认：主手、ServerPlayer、空手。
     * 这里只做业务前置（规则 / 玩家偏好 / 方块条件）和执行坐下。
     */
    public static InteractionResult trySit(ServerPlayer rider, Level level, BlockHitResult hitResult) {
        // 1. 业务前置条件
        if (!RuleCache.isEnabled(rider, RULE)) return InteractionResult.PASS;
        if (!PlayerBlockSittingPreference.INSTANCE.enabled(rider)) return InteractionResult.PASS;

        // 2. 方块 / 朝向 / 空间等业务判定
        String refuseReason = getSitRefuseReason(rider, level, hitResult);
        if (refuseReason != null) {
            LOGGER.debug("[PlayerBlockSitting] {} 坐不上：{}", nameOf(rider), refuseReason);
            return InteractionResult.PASS;
        }

        // 3. 执行坐下
        return performSit(rider, level, hitResult.getBlockPos());
    }

    /**
     * 判定是否满足坐下的条件。
     *
     * @return {@code null} 表示可以坐下；否则返回拒绝的原因
     */
    @Nullable
    private static String getSitRefuseReason(ServerPlayer rider, Level level, BlockHitResult hitResult) {
        if (rider.isSpectator() || rider.isPassenger()) return "旁观者或已在骑乘";
        if (rider.isShiftKeyDown()) return "潜行状态";
        if (hitResult.getDirection() == Direction.DOWN) return "点击底面";

        BlockPos pos = hitResult.getBlockPos();
        BlockState state = level.getBlockState(pos);

        if (!BlockSittingSeat.isSittingBlock(state)) return "方块不是可坐类型";
        if (BlockSittingSeat.seatPosition(level, pos, state) == null) return "无法计算座位坐标";
        if (!BlockSittingSeat.hasHeadroom(level, pos)) return "上方没有空间";

        return null;
    }

    /**
     * 实际执行生成实体并骑乘的逻辑
     */
    private static InteractionResult performSit(ServerPlayer rider, Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Vec3 seatPos = BlockSittingSeat.seatPosition(level, pos, state);

        if (seatPos == null) return InteractionResult.PASS;

        BlockSeatEntity seat = new BlockSeatEntity(ModEntities.BLOCK_SEAT, level);
        seat.setSeatBlock(pos);
        seat.setPos(seatPos.x, seatPos.y, seatPos.z);

        if (!level.addFreshEntity(seat)) {
            LOGGER.debug("[PlayerBlockSitting] {} 坐不上 {}：座位实体没加进世界", nameOf(rider), pos);
            return InteractionResult.PASS;
        }

        if (!rider.startRiding(seat, true, true)) {
            seat.discard();
            LOGGER.debug("[PlayerBlockSitting] {} 坐不上 {}：startRiding 返回 false (canSerialize={})",
                    nameOf(rider), pos, seat.getType().canSerialize());
            return InteractionResult.PASS;
        }

        SEATS.put(rider.getUUID(), seat);
        RESEND.put(rider.getUUID(), RESEND_TICKS);
        IGNORE_BUILD_LIMIT.put(rider.getUUID(), IGNORE_BUILD_LIMIT_TICKS);

        broadcastPassengers(seat, rider, null);

        LOGGER.debug("[PlayerBlockSitting] {} 坐下：方块={}，座面=({}, {}, {})，座位=({}, {}, {})，玩家=({}, {}, {})",
                nameOf(rider), pos, seatPos.x, seatPos.y, seatPos.z,
                seat.getX(), seat.getY(), seat.getZ(), rider.getX(), rider.getY(), rider.getZ());

        return InteractionResult.SUCCESS;
    }

    /**
     * 把某个座位的乘客列表推给客户端（原因见原注释）。
     */
    private static void broadcastPassengers(BlockSeatEntity seat, ServerPlayer forceTo, ServerPlayer skip) {
        ClientboundSetPassengersPacket packet = new ClientboundSetPassengersPacket(seat);
        if (forceTo != null) {
            forceTo.connection.send(packet);
        }
        if (!(seat.level() instanceof ServerLevel level)) {
            return;
        }
        double maxDistanceSqr = TRACK_DISTANCE * TRACK_DISTANCE;
        for (ServerPlayer player : level.players()) {
            if (player == forceTo || player == skip) continue;
            if (player.distanceToSqr(seat) > maxDistanceSqr) continue;
            player.connection.send(packet);
        }
    }

    // ==============================================================
    // tick 巡检（由 SectionManager 注册到 END_SERVER_TICK）
    // ==============================================================

    public static void onEndServerTick(MinecraftServer server) {
        tickIgnoreBuildLimit();
        if (SEATS.isEmpty()) {
            return;
        }

        boolean ruleEnabled = RuleCache.isEnabled(RULE);
        boolean resync = server.getTickCount() % RESYNC_INTERVAL_TICKS == 0;

        Iterator<Map.Entry<UUID, BlockSeatEntity>> iterator = SEATS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BlockSeatEntity> entry = iterator.next();
            BlockSeatEntity seat = entry.getValue();
            ServerPlayer rider = server.getPlayerList().getPlayer(entry.getKey());

            if (rider == null) {
                seat.discard();
                RESEND.remove(entry.getKey());
                iterator.remove();
                continue;
            }

            if (seat.isRemoved() || rider.getVehicle() != seat) {
                standUp(rider, seat);
                iterator.remove();
                continue;
            }

            String reason = seatCloseReason(ruleEnabled, rider, seat);
            if (reason != null) {
                LOGGER.debug("[PlayerBlockSitting] {} 从 {} 上起来：{}", nameOf(rider), seat.seatBlock(), reason);
                standUp(rider, seat);
                iterator.remove();
                continue;
            }

            Integer left = RESEND.get(entry.getKey());
            if (left != null) {
                broadcastPassengers(seat, rider, null);
                if (left <= 1) {
                    RESEND.remove(entry.getKey());
                } else {
                    RESEND.put(entry.getKey(), left - 1);
                }
            } else if (resync) {
                broadcastPassengers(seat, null, rider);
            }
        }
    }

    private static void tickIgnoreBuildLimit() {
        if (IGNORE_BUILD_LIMIT.isEmpty()) {
            return;
        }
        IGNORE_BUILD_LIMIT.replaceAll((_, left) -> left - 1);
        IGNORE_BUILD_LIMIT.entrySet().removeIf(entry -> entry.getValue() <= 0);
    }

    private static String seatCloseReason(boolean ruleEnabled, ServerPlayer rider, BlockSeatEntity seat) {
        if (!ruleEnabled) return "rule-disabled";
        if (!PlayerBlockSittingPreference.INSTANCE.enabled(rider)) return "player-disabled";
        if (rider.isSpectator() || rider.isDeadOrDying() || rider.isRemoved()) return "rider-gone";
        if (!seatBlockStillThere(seat)) return "block-gone";
        return null;
    }

    private static boolean seatBlockStillThere(BlockSeatEntity seat) {
        BlockPos block = seat.seatBlock();
        if (block == null) return true;
        if (!(seat.level() instanceof ServerLevel level)) return true;
        return BlockSittingSeat.isSittingBlock(level.getBlockState(block));
    }

    private static void standUp(ServerPlayer rider, BlockSeatEntity seat) {
        boolean riding = rider.getVehicle() == seat;
        RESEND.remove(rider.getUUID());
        IGNORE_BUILD_LIMIT.put(rider.getUUID(), IGNORE_BUILD_LIMIT_TICKS);
        if (riding) {
            rider.stopRiding();
        }
        Level seatLevel = seat.level();
        double seatX = seat.getX();
        double seatY = seat.getY();
        double seatZ = seat.getZ();
        broadcastPassengers(seat, riding ? rider : null, null);
        seat.discard();

        if (!riding) {
            return;
        }
        if (rider.isRemoved() || rider.isDeadOrDying() || rider.isSpectator()) {
            return;
        }
        if (rider.level() != seatLevel) {
            return;
        }
        rider.connection.teleport(seatX, seatY, seatZ, rider.getYRot(), rider.getXRot());
        rider.setDeltaMovement(Vec3.ZERO);
        rider.resetFallDistance();
    }

    /**
     * 这个玩家此刻收到的「建筑高度上限」提示是不是原版的误发
     * （由 {@code SeatBuildLimitMessageMixin} 调用）。
     */
    public static boolean shouldIgnoreBuildLimitMessage(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return player.getVehicle() instanceof BlockSeatEntity
                || IGNORE_BUILD_LIMIT.containsKey(player.getUUID());
    }

    private static String nameOf(ServerPlayer player) {
        return player.getName().getString();
    }
}