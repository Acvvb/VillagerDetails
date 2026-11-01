package com.villagerdetails.handler.move;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 载具位移的「完全信任客户端」实现：规则开启时不再做任何服务端位移校验或修正。
 *
 * <p>为什么需要它：原版 {@code handleMoveVehicle} 里有两条独立的**回退**分支 ——
 * 「moved too quickly」（超出阈值时把服务端旧位置发回去并直接 return，客户端坐标根本不落地）
 * 和「moved wrongly」（残差过大时 snap 回旧位置）。只静默日志是没用的，回弹照旧发生。
 *
 * <p>这里走另一条路：整个方法在 HEAD 处被取消，客户端上报的坐标由本类<b>原样落地</b>，
 * 不跑服务端物理（{@code Entity#move}）、不做碰撞判定、不比较位移阈值 —— 数值合法性已经在上层
 * （{@code RuleStateValidator}）拦过了，这一层不再重复判断。
 *
 * <p><b>本类只吃 {@link Vec3} + 基本类型，不碰任何包类型</b>：{@code ServerboundMoveVehiclePacket}
 * 的取坐标 API 在 26.2（{@code position()/yRot()/xRot()}）和 26.3
 * （{@code movingTo(): PositionAndRotation}）之间不兼容，所以读包那一小段留在各版本的薄 mixin 里，
 * 共用逻辑全部在这里。
 */
public final class VehicleMoveTrust {

    /**
     * {@code handlePlayerKnownMovement} 里「这次算有动作」的阈值（原版同值）
     */
    private static final double MOVED_THRESHOLD_SQR = 1.0E-5;

    private VehicleMoveTrust() {
    }

    /**
     * 包里的坐标 / 朝向是否「可以安全落地」。
     *
     * <p>NaN / ±Infinity 一律返回 {@code false}：原版在 {@code containsInvalidValues} 里遇到这种包会
     * {@code disconnect}，我们不抢这个职责 —— 返回 false = 不取消，让原版照旧把人踢掉。
     */
    public static boolean isSafe(Vec3 pos, float yRot, float xRot) {
        return pos != null
                && Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z)
                && Float.isFinite(yRot) && Float.isFinite(xRot);
    }

    /**
     * 这次载具移动包要不要完全信任。
     *
     * <p>放行条件（缺一不可）：规则开着、玩家确实骑在某个载具上、并且<b>他本人就是这辆载具的控制者</b>。
     * 最后一条不是限速，而是正确性：乘客不该决定载具的位置，原版同样会直接 return。
     */
    public static boolean isTrusted(ServerPlayer rider, Entity root) {
        if (rider == null || root == null) {
            return false;
        }
        // 没骑载具（getRootVehicle 返回自身）→ 这不是载具移动包该管的场景
        if (root == rider) {
            return false;
        }
        if (root.getControllingPassenger() != rider) {
            return false;
        }
        return RuleCache.isEnabled(RuleType.GLOBAL_SPEED_BYPASS);
    }

    /**
     * 把客户端上报的位置 / 朝向<b>原样</b>应用到根载具上，并补上原版取消后会缺失的几项副作用。
     *
     * <p>按原版正常路径的顺序做，但**不做**任何校验与回退：
     * <ol>
     *     <li>{@link Entity#absSnapTo} —— 位置 + 朝向（朝向按原版 {@link Mth#wrapDegrees} 规范化）；</li>
     *     <li>{@code setOnGroundWithMovement} / {@code doCheckFallDamage} —— 落地状态与摔落判定；</li>
     *     <li>{@code checkMovementStatistics} —— 里程统计；</li>
     *     <li>{@code getChunkSource().move} —— <b>区块跟随，漏了这行就会表现为「地形不加载 / 卡在原地」</b>；</li>
     *     <li>known movement / 活跃时间 —— 与原生 {@code handlePlayerKnownMovement} 对齐
     *         （否则高速飞行可能被当成挂机）。</li>
     * </ol>
     */
    public static void apply(ServerPlayer rider, Entity root, Vec3 pos, float yRot, float xRot, boolean onGround) {
        double dx = pos.x - root.getX();
        double dy = pos.y - root.getY();
        double dz = pos.z - root.getZ();
        Vec3 delta = new Vec3(dx, dy, dz);

        // 客户端坐标直接落地：不再经过 Entity#move（服务端物理）与任何阈值比较
        root.absSnapTo(pos.x, pos.y, pos.z, Mth.wrapDegrees(yRot), Mth.wrapDegrees(xRot));
        root.setOnGroundWithMovement(onGround, delta);
        root.doCheckFallDamage(dx, dy, dz, onGround);
        rider.checkMovementStatistics(dx, dy, dz);

        // 区块跟随：玩家/载具移动时让服务端跟着加载、卸载周边区块
        rider.level().getChunkSource().move(rider);

        // 与原生 handlePlayerKnownMovement 对齐（它还会置一个 tick 内的私有 flag，
        // 由各自的 mixin 补上）：有位移就算「有动作」，并刷新 known movement
        if (delta.lengthSqr() > MOVED_THRESHOLD_SQR) {
            rider.resetLastActionTime();
        }
        rider.setKnownMovement(delta);
    }
}
