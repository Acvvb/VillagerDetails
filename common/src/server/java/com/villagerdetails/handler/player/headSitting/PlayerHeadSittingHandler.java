package com.villagerdetails.handler.player.headSitting;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.cache.impl.PlayerHeadSittingAllow;
import com.villagerdetails.config.impl.section.item.PlayerHeadSittingConfig;
import com.villagerdetails.event.impl.register.PlayerHeadSittingSectionManager;
import com.villagerdetails.rule.playerHeadSitting.PlayerHeadSittingSeat;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
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
 * <p>五条线：
 * <ol>
 *     <li>{@link #trySit}：由入口类在「主手空手右键玩家」时调用；</li>
 *     <li>{@link #onEndServerTick}：巡检玩家载具 + 玩家乘客；</li>
 *     <li>{@link #onPlayerChangeLevel}：换维度记「待下坐」，下一个 tick 处理；</li>
 *     <li>{@link #onPlayerDisconnect} / {@link #onEntityUnload}：<b>玩家下线 / 实体卸载时的强制清理</b>，
 *         把两个方向的骑乘关系都拆干净，并扫掉「幽灵乘客」；</li>
 *     <li>{@link #onPlayerJoin}：重进时兜底清一次，并在下一个 tick 补发乘客表。</li>
 * </ol>
 *
 * <p><b>为什么下线 / 卸载必须自己清</b>：本功能的载具是<b>玩家</b>，而
 * {@code EntityTypes.PLAYER} 是 {@code noSave()}。载具一旦离线，留下的 {@code passengers} 条目就是一个
 * 已经移除的玩家实例：它仍在载具的乘客列表里，会被
 * {@link #onEndServerTick} 每 tick 拿去 {@code snapToSeat} + {@code ChunkMap#move}，
 * 于是这个「已移除的实体」被反复塞回区块追踪 → 存档时刷
 * {@code Trying to save removed ... skipping}，客户端则因为收到指向不存在实体 id 的乘客表而出现隐身。
 * 所以三条兜底一条都不能少：下线清、卸载清、进服清 + 补发包。
 *
 * <p>所有方法都只在服务端主线程跑（下线 / 进服 / 卸载事件与 tick 一样都在主线程），
 * 所以三个 {@link UUID} 集合用普通 {@link HashSet} 就够。
 */
public final class PlayerHeadSittingHandler {

    private PlayerHeadSittingHandler() {
    }

    private static final Logger log = LogManager.getLogger(PlayerHeadSittingHandler.class);

    private static final RuleType RULE = RuleType.PLAYER_HEAD_SITTING;

    /** 遍历叠罗汉链的最长步数 */
    private static final int MAX_CHAIN = 16;

    /** 换维度时记下的「待下坐」乘客，下一个 tick 统一处理 */
    private static final Set<UUID> PENDING_DISMOUNT = new HashSet<>();

    /**
     * 刚进服的玩家：JOIN 那一刻客户端可能还没收完世界（实体表还是空的），
     * 乘客表补发挪到下一个 tick 更稳。处理完就从这个集合里摘掉。
     */
    private static final Set<UUID> RESYNC_NEXT_TICK = new HashSet<>();

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
            log.debug("[PlayerHeadSitting] {} -> {}：规则没开（或该玩家没有对应权限等级）",
                    nameOf(rider), nameOf(target));
            return InteractionResult.PASS;
        }

        String refuse = refuseReason(rider, target);
        if (refuse != null) {
            log.debug("[PlayerHeadSitting] {} 不能坐上 {} 头顶：{}", nameOf(rider), nameOf(target), refuse);
            return InteractionResult.PASS;
        }

        // force = true：跳过 canRide / canAddPassenger（上面已经自己判过了）
        if (!rider.startRiding(target, true, true)) {
            log.debug("[PlayerHeadSitting] {} 坐上 {} 头顶失败：startRiding 返回 false"
                            + "（vehicle.canSerialize={}, rider.isPassenger={}, target.getPassengers={}, ruleEnabled={}）",
                    nameOf(rider), nameOf(target),
                    target.getType().canSerialize(), rider.isPassenger(),
                    target.getPassengers().size(), RuleCache.isEnabled(RULE));
            return InteractionResult.PASS;
        }

        log.debug("[PlayerHeadSitting] {} 坐上 {} 头顶", nameOf(rider), nameOf(target));

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

        flushResync(server);

        for (ServerPlayer vehicle : server.getPlayerList().getPlayers()) {
            if (vehicle.getPassengers().isEmpty()) continue;
            for (Entity passenger : List.copyOf(vehicle.getPassengers())) {
                if (!(passenger instanceof ServerPlayer rider)) continue;

                // ★ 幽灵乘客：已经下线 / 已被移除的旧实体，绝不能再 snapToSeat + ChunkMap#move。
                //   那样会把这个「已移除的实体」每 tick 重新塞回区块追踪，存档时刷
                //   "Trying to save removed ... skipping"，客户端也会收到指向不存在 id 的乘客表。
                if (!isOnlinePlayer(server, rider)) {
                    log.debug("[PlayerHeadSitting] {} 的乘客 {} 已不在线，强制下坐", nameOf(vehicle), rider.getUUID());
                    rider.stopRiding();
                    forget(rider.getUUID());
                    continue;
                }

                String reason = enabled ? kickReason(vehicle, rider) : "rule-disabled";
                if (reason != null) {
                    log.debug("[PlayerHeadSitting] {} 从 {} 头顶下来：{}", nameOf(rider), nameOf(vehicle), reason);
                    rider.stopRiding();
                    forget(rider.getUUID());
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
    // 下线 / 卸载 / 重进（由 SectionManager 注册到 DISCONNECT / ENTITY_UNLOAD / JOIN）
    // ==============================================================

    /**
     * 玩家退出：把「他骑别人」和「别人骑他」两个方向都拆干净，再扫一遍全服的幽灵乘客。
     *
     * <p>退出这一刻实体还在世界（{@code stopRiding()} 合法），所以这是最主要的清理点；
     * 只走 {@code ENTITY_UNLOAD} 的话，玩家实体被移除的时机靠后，载具身上那条条目
     * 可能已经跟着存过一次了。
     */
    public static void onPlayerDisconnect(MinecraftServer server, ServerPlayer player) {
        if (player == null) return;
        dismountBothWays(player, "disconnect");
        if (server != null) {
            sanitizeGhostRiding(server, "disconnect");
        }
    }

    /**
     * 实体卸载（区块卸载 / 换维度 / 被移除）：玩家实体被卸载时把两个方向的骑乘关系都拆掉。
     *
     * <p><b>只处理「卸载的是玩家」这一种</b>：玩家当载具是本模组独有的关系，乘客也只可能是玩家
     * （{@code EntityStartRidingMixin} 只给玩家放行）；而船 / 矿车 / 生物这类原版载具的卸载
     * 有它自己的机制，这里刻意不插手 —— 否则「玩家坐着船，船所在区块卸载」会被我们
     * 顺手踢下船，属于多余的行为改动。方块座位同理：它在 {@code PlayerBlockSittingHandler}
     * 的巡检里自己会 {@code standUp}。
     */
    public static void onEntityUnload(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        dismountBothWays(player, "unload");

        MinecraftServer server = player.level().getServer();
        if (server != null) {
            sanitizeGhostRiding(server, "unload");
        }
    }

    /**
     * 玩家进服：新实体本身不该带骑乘关系，这里强制清一次；
     * 再把乘客表补发<b>推迟到下一个 tick</b>（JOIN 那一刻客户端可能还没收完世界）。
     */
    public static void onPlayerJoin(ServerPlayer player) {
        if (player == null) return;
        dismountBothWays(player, "join");
        RESYNC_NEXT_TICK.add(player.getUUID());
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            sanitizeGhostRiding(server, "join");
        }
    }

    /**
     * 双向拆骑乘关系：先让自己下车，再把自己头上的乘客全部请下来。
     *
     * <p>对每个乘客都是 {@code passenger.stopRiding()}（而不是
     * {@code vehicle.removePassenger(passenger)}）—— 原版 {@code removePassenger} 的契约要求
     * 先由乘客侧把 {@code vehicle} 置空，直接反向调用会抛
     * {@code IllegalStateException("Use x.stopRiding(y), not y.removePassenger(x)")}。
     *
     * <p>不会递归：{@code stopRiding()} 只会走 {@code removePassenger()}，那条路径在本模组里
     * 只挂了一个「补发乘客包」的 TAIL 注入（{@code EntityPlayerVehiclePassengersMixin}），
     * 不回调任何 startRiding / stopRiding。
     */
    private static void dismountBothWays(Entity entity, String reason) {
        if (entity == null) return;

        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            log.debug("[PlayerHeadSitting] 乘客 {} 从载具 {} 上强制下坐（{}）",
                    describe(entity), describe(vehicle), reason);
            entity.stopRiding();
        }

        // 拷贝一份再遍历：stopRiding() 会改 entity 的 passengers 列表
        for (Entity passenger : List.copyOf(entity.getPassengers())) {
            log.debug("[PlayerHeadSitting] 载具 {} 的乘客 {} 被强制下坐（{}）",
                    describe(entity), describe(passenger), reason);
            passenger.stopRiding();
            forget(passenger.getUUID());
        }

        forget(entity.getUUID());
    }

    /**
     * 扫掉全服的「幽灵乘客 / 幽灵载具」：乘客或载具已经不在线（或已被移除）的骑乘关系一律拆掉。
     *
     * <p>正常玩家乘客会被跳过，所以这不会干扰正在叠罗汉的玩家 —— 只处理残留。
     */
    private static void sanitizeGhostRiding(MinecraftServer server, String reason) {
        for (ServerPlayer vehicle : List.copyOf(server.getPlayerList().getPlayers())) {
            for (Entity passenger : List.copyOf(vehicle.getPassengers())) {
                if (isOnlinePlayer(server, passenger)) continue;
                log.debug("[PlayerHeadSitting] 载具 {} 的幽灵乘客 {} 被清掉（{}）",
                        nameOf(vehicle), describe(passenger), reason);
                passenger.stopRiding();
                forget(passenger.getUUID());
            }

            // 反向：自己还挂在某个「已经不在线」的玩家身上（载具已被移除）
            Entity currentVehicle = vehicle.getVehicle();
            if (currentVehicle instanceof ServerPlayer && !isOnlinePlayer(server, currentVehicle)) {
                log.debug("[PlayerHeadSitting] 玩家 {} 的幽灵载具 {} 被清掉（{}）",
                        nameOf(vehicle), describe(currentVehicle), reason);
                vehicle.stopRiding();
                forget(vehicle.getUUID());
            }
        }
    }

    /**
     * 这个实体是不是「本局里仍然在线」的那个玩家实例。
     *
     * <p>同名 UUID 但换了个实例（退服重进）也算幽灵：服务端现在用的是新实例，
     * 旧实例留在别人乘客列表里只会制造幽灵。
     */
    private static boolean isOnlinePlayer(MinecraftServer server, Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return false;
        if (player.isRemoved()) return false;
        return server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    /**
     * 补发「刚进服那批玩家」的乘客表，保证客户端拿到的一定是最新的一份（空表也要发）。
     */
    private static void flushResync(MinecraftServer server) {
        if (RESYNC_NEXT_TICK.isEmpty()) return;
        List<UUID> done = new ArrayList<>(RESYNC_NEXT_TICK.size());
        for (UUID uuid : RESYNC_NEXT_TICK) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                player.connection.send(new ClientboundSetPassengersPacket(player));
            }
            done.add(uuid);
        }
        RESYNC_NEXT_TICK.removeAll(done);
    }

    /**
     * 把 uuid 从「待下坐」和「待补发」两个缓存里摘掉（退出 / 清理时用）。
     *
     * <p>幂等：传 {@code null} 或没记录的 uuid 都是空操作。
     */
    private static void forget(UUID uuid) {
        if (uuid == null) return;
        PENDING_DISMOUNT.remove(uuid);
        RESYNC_NEXT_TICK.remove(uuid);
    }

    private static String describe(Entity entity) {
        if (entity == null) return "<null>";
        String name = entity.getName().getString();
        return name + "(" + entity.getUUID() + ")";
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