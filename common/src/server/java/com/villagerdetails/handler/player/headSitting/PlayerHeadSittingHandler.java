package com.villagerdetails.handler.player.headSitting;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.cache.impl.PlayerHeadSittingAllow;
import com.villagerdetails.config.impl.section.item.PlayerHeadSittingConfig;
import com.villagerdetails.event.impl.register.PlayerHeadSittingSectionManager;import com.villagerdetails.rule.playerHeadSitting.PlayerHeadSittingSeat;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 「玩家头顶乘坐」的业务处理器。
 *
 * <p>事件注册已经挪到 {@link PlayerHeadSittingSectionManager}；这里只处理
 * <b>能不能坐、怎么摆、什么时候踢下来</b>。
 *
 * <p>三条线（与之前一致）：
 * <ol>
 *     <li>{@link #trySit}：由入口类在「主手空手右键玩家」时调用；</li>
 *     <li>{@link #onEndServerTick}：巡检玩家载具 + 玩家乘客；</li>
 *     <li>{@link #onPlayerChangeLevel}：换维度记「待下坐」，下一个 tick 处理。</li>
 * </ol>
 *
 * <p>只在服务端主线程跑，所以两个 {@link UUID} 集合用普通 {@link HashSet} 就够。
 */
public final class PlayerHeadSittingHandler {

    private PlayerHeadSittingHandler() {
    }

    private static final Logger LOGGER = LogManager.getLogger(PlayerHeadSittingHandler.class);

    private static final RuleType RULE = RuleType.PLAYER_HEAD_SITTING;

    /** 遍历叠罗汉链的最长步数 */
    private static final int MAX_CHAIN = 16;

    /** 换维度时记下的「待下坐」乘客，下一个 tick 统一处理 */
    private static final Set<UUID> PENDING_DISMOUNT = new HashSet<>();

    // ==============================================================
    // 业务入口：尝试坐下（由 SectionManager 委托）
    // ==============================================================

    /**
     * 尝试坐到 {@code target} 头顶。入口处（SectionManager）已经确认：
     * 服务端、主手、玩家 × 玩家、空手。这里做业务判定和执行骑乘。
     */
    public static InteractionResult trySit(ServerPlayer rider, ServerPlayer target, InteractionHand hand) {
        // 规则状态自带权限等级（true / ops / master / ...）
        if (!RuleCache.isEnabled(rider, RULE)) {
            LOGGER.debug("[PlayerHeadSitting] {} -> {}：规则没开（或该玩家没有对应权限等级）",
                    nameOf(rider), nameOf(target));
            return InteractionResult.PASS;
        }

        String refuse = refuseReason(rider, target);
        if (refuse != null) {
            LOGGER.debug("[PlayerHeadSitting] {} 不能坐上 {} 头顶：{}", nameOf(rider), nameOf(target), refuse);
            return InteractionResult.PASS;
        }

        // force = true：跳过 canRide / canAddPassenger（上面已经自己判过了）
        if (!rider.startRiding(target, true, true)) {
            LOGGER.debug("[PlayerHeadSitting] {} 坐上 {} 头顶失败：startRiding 返回 false"
                            + "（vehicle.canSerialize={}, rider.isPassenger={}, target.getPassengers={}, ruleEnabled={}）",
                    nameOf(rider), nameOf(target),
                    target.getType().canSerialize(), rider.isPassenger(),
                    target.getPassengers().size(), RuleCache.isEnabled(RULE));
            return InteractionResult.PASS;
        }

        LOGGER.debug("[PlayerHeadSitting] {} 坐上 {} 头顶", nameOf(rider), nameOf(target));

        // 立刻按碰撞箱摆一次，免得客户端第一帧看到穿模
        PlayerHeadSittingSeat.snapToSeat(rider, target);
        PlayerHeadSittingSwing.swing(rider, hand);
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * 这次右键该不该让他坐上去。
     *
     * @return {@code null} = 可以坐；否则是拒绝原因
     */
    private static String refuseReason(ServerPlayer rider, ServerPlayer target) {
        if (rider == target) return "self";
        if (rider.isSpectator() || target.isSpectator()) return "spectator";
        if (!PlayerHeadSittingConfig.allowCreative() && rider.isCreative()) return "creative";
        if (rider.isPassenger()) return "rider-already-riding";
        if (!target.getPassengers().isEmpty()) return "target-has-passenger";
        if (target.isRemoved() || target.isDeadOrDying() || target.isSleeping()) return "target-unavailable";
        if (!PlayerHeadSittingAllow.INSTANCE.allows(target)) return "target-not-allowing";
        if (vehicleDepth(target) + 2 > PlayerHeadSittingConfig.maxStackDepth()) return "stack-depth";
        return null;
    }

    private static int vehicleDepth(Entity entity) {
        int depth = 0;
        Entity current = entity;
        while (current.isPassenger() && depth < MAX_CHAIN) {
            Entity vehicle = current.getVehicle();
            if (vehicle == null) break;
            current = vehicle;
            depth++;
        }
        return depth;
    }

    // ==============================================================
    // tick 巡检（由 SectionManager 注册到 END_SERVER_TICK）
    // ==============================================================

    public static void onEndServerTick(MinecraftServer server) {
        boolean enabled = RuleCache.isEnabled(RULE);

        for (ServerPlayer vehicle : server.getPlayerList().getPlayers()) {
            if (vehicle.getPassengers().isEmpty()) continue;
            for (Entity passenger : List.copyOf(vehicle.getPassengers())) {
                if (!(passenger instanceof ServerPlayer rider)) continue;
                String reason = enabled ? kickReason(vehicle, rider) : "rule-disabled";
                if (reason != null) {
                    LOGGER.debug("[PlayerHeadSitting] {} 从 {} 头顶下来：{}", nameOf(rider), nameOf(vehicle), reason);
                    rider.stopRiding();
                    continue;
                }
                PlayerHeadSittingSeat.snapToSeat(rider, vehicle);
                if (vehicle.level() instanceof ServerLevel level) {
                    level.getChunkSource().move(rider);
                }
            }
        }

        if (!enabled) {
            PENDING_DISMOUNT.clear();
            return;
        }

        PENDING_DISMOUNT.removeIf(uuid -> !isRidingPlayer(server, uuid));
    }

    private static String kickReason(ServerPlayer vehicle, ServerPlayer rider) {
        if (PENDING_DISMOUNT.remove(rider.getUUID())) return "target-changed-dimension";
        if (vehicle.isRemoved() || vehicle.isDeadOrDying()) return "target-gone";
        if (!PlayerHeadSittingAllow.INSTANCE.allows(vehicle)) return "target-not-allowing";
        if (vehicle.isSleeping()) return "target-sleep";
        if (PlayerHeadSittingConfig.dismountOnTargetRide() && vehicle.isPassenger()) return "target-riding";
        if (vehicle.isShiftKeyDown()) return "target-sneak";
        return null;
    }

    private static boolean isRidingPlayer(MinecraftServer server, UUID uuid) {
        ServerPlayer rider = server.getPlayerList().getPlayer(uuid);
        return rider != null && rider.getVehicle() instanceof ServerPlayer;
    }

    // ==============================================================
    // 换维度（由 SectionManager 注册到 AFTER_PLAYER_CHANGE_LEVEL）
    // ==============================================================

    public static void onPlayerChangeLevel(ServerPlayer player) {
        if (!RuleCache.isEnabled(RULE)) return;

        for (Entity passenger : List.copyOf(player.getPassengers())) {
            if (passenger instanceof ServerPlayer) {
                PENDING_DISMOUNT.add(passenger.getUUID());
            }
        }
        if (player.getVehicle() instanceof ServerPlayer) {
            PENDING_DISMOUNT.add(player.getUUID());
        }
    }

    private static String nameOf(ServerPlayer player) {
        return player.getName().getString();
    }
}