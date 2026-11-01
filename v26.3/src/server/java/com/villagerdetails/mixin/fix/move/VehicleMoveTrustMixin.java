package com.villagerdetails.mixin.fix.move;

import com.villagerdetails.handler.move.VehicleMoveTrust;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 载具位移包（{@code handleMoveVehicle}）的「完全信任客户端」注入 —— <b>26.3 专用副本</b>。
 *
 * <p>与 26.2 那份的唯一差别：26.3 把位置和朝向收进了 {@code movingTo(): PositionAndRotation}，
 * 不再有 {@code position()/yRot()/xRot()} 三个访问器。
 *
 * <p>为什么这个类不在 {@code common} 里、以及它与 {@link GlobalSpeedBypassMixin} 的分工，
 * 见 26.2 那份的类注释（两边一致）。共用逻辑全在 {@link VehicleMoveTrust}。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class VehicleMoveTrustMixin {

    @Unique
    private static final Logger VILLAGERDETAILS$LOG = LogManager.getLogger("VillagerDetails/VehicleMoveTrust");

    @Shadow
    public abstract ServerPlayer getPlayer();

    @Shadow
    public abstract boolean hasClientLoaded();

    /**
     * 原版在应用载具位移前的必过看门狗（传送等待超时 / 客户端还没加载完）。
     * 取消整个方法后它不会再被原版调用，这里显式调一次，保持同样的门禁语义。
     */
    @Shadow
    private boolean updateAwaitingTeleport() {
        throw new AssertionError("mixin shadow");
    }

    /**
     * 原版 {@code handlePlayerKnownMovement} 会置位这个私有 flag，供 {@code handleClientTickEnd}
     * 判断「本 tick 客户端确实报了位移」。我们绕过了那个方法，这里手动对齐。
     */
    @Shadow
    private boolean receivedMovementThisTick;

    @Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$trustVehicleMove(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        ServerPlayer player = this.getPlayer();
        if (player == null || packet == null) {
            return;
        }

        // 26.3：位置 + 朝向都在 PositionAndRotation 里
        PositionAndRotation movingTo = packet.movingTo();
        Vec3 pos = movingTo.position();
        float yRot = movingTo.yRot();
        float xRot = movingTo.xRot();
        // NaN / ±Infinity：不取消，交回原版（它会按原逻辑 disconnect 掉这种客户端）
        if (!VehicleMoveTrust.isSafe(pos, yRot, xRot)) {
            return;
        }

        // 我们的注入排在原版 ensureRunningOnSameThread 之前，这里自己兜一道线程门：
        // 万一（理论上不会）从非主线程进来，就不碰实体，交回原版让它按原逻辑报错
        player.level();
        if (!player.level().getServer().isSameThread()) {
            return;
        }

        Entity root = player.getRootVehicle();
        if (!VehicleMoveTrust.isTrusted(player, root)) {
            return;
        }
        // 传送中 / 客户端还没加载完：原版会直接忽略这个包，我们也别抢着应用旧坐标
        if (this.updateAwaitingTeleport() || !this.hasClientLoaded()) {
            return;
        }

        // 先取消再落地：宁可这一 tick 不动，也不能让原版校验抢跑（那样又会回弹）
        ci.cancel();
        try {
            VehicleMoveTrust.apply(player, root, pos, yRot, xRot, packet.onGround());
            this.receivedMovementThisTick = true;
        } catch (Throwable t) {
            // 兜底：任何意外都不许把整条网络线程带崩；这一 tick 不移动，客户端下个 tick 会重发
            VILLAGERDETAILS$LOG.warn("[VehicleMoveTrust] 应用载具位置失败，本 tick 不移动：{}", String.valueOf(t));
        }
    }
}
