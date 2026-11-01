package com.villagerdetails.mixin.fix.move;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 移动提速防回弹。
 *
 * <p><b>作用范围（收窄后）</b>：只有下面两种情况才跳过服务端的位移校验 ——
 * <ol>
 *     <li><b>玩家自身移动</b>（没骑任何载具）：{@code getRootVehicle() == player}。
 *         覆盖鞘翅、模组飞行器、创造飞行这类"人自己在飞"的场景；</li>
 *     <li><b>玩家控制着的乐魂</b>：根载具是 {@link HappyGhast} 且它的控制者是玩家。
 *         这是本模组「乐魂飞行速度」规则唯一需要的载具路径。</li>
 * </ol>
 *
 * <p><b>为什么要收窄</b>：这条规则以前是"无条件返回 false"，等于把<b>所有玩家、所有载具</b>
 * 的位移校验一起关掉 —— 任何人在任何载具上都被放行，副作用远超本模组需要的范围。
 * 现在船 / 矿车 / 其他模组的载具 / 玩家头顶乘坐都不再受影响，仍然走原版校验。
 *
 * <p><b>注意</b>：{@code shouldCheckPlayerMovement} 只被 {@code handleMovePlayer} 调用，
 * 也就是说它只覆盖"玩家自身的移动校验"；骑乘乐魂的位移走的是 {@code handleMoveVehicle}，
 * 那条路径上的判定是内联的（不走这个方法）。所以这里是<b>减少副作用</b>，而不是给骑乘
 * 加新的放行 —— 骑乘一侧本来就没被这个注入覆盖过。
 *
 * <p>默认为<b>关闭</b>（{@link RuleType#GLOBAL_SPEED_BYPASS} 默认 {@code false}）。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class GlobalSpeedBypassMixin {

    @Shadow
    public abstract ServerPlayer getPlayer();

    /**
     * 这次位移校验是否可以跳过。
     *
     * <p>谓词同时被下面两处使用（真正的放行 + 日志静默），保证"能跳过的才静默"，
     * 不会出现"校验照做、日志却被吞掉"的情况。
     */
    @Unique
    private boolean villagerdetails$isBypassAllowed() {
        ServerPlayer self = this.getPlayer();
        if (self == null) {
            return false;
        }
        Entity rootVehicle = self.getRootVehicle();
        // 1) 没骑载具 → 玩家自身移动，保持原有放行范围
        if (rootVehicle == self) {
            return true;
        }
        // 2) 载具路径：只放行「玩家控制着的乐魂」
        return rootVehicle instanceof HappyGhast ghast
                && ghast.getControllingPassenger() instanceof Player;
    }

    /**
     * 核心拦截：规则开启<b>且</b>本次位移属于上面列出的两种场景时，
     * 让 shouldCheckPlayerMovement 返回 false。
     */
    @Inject(
            method = "shouldCheckPlayerMovement",
            at = @At("HEAD"),
            cancellable = true
    )
    private void villagerdetails$skipMovementCheck(boolean isFallFlying, CallbackInfoReturnable<Boolean> cir) {
        if (RuleCache.isEnabled(RuleType.GLOBAL_SPEED_BYPASS) && this.villagerdetails$isBypassAllowed()) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 兜底方案：如果仍有少量警告漏过，在此静默移动相关日志。
     *
     * <p>静默条件与放行条件完全一致 —— 不在放行范围内的移动，日志照常输出，
     * 免得把"本该被拦下"的告警一起吞掉。
     */
    @Redirect(
            method = {"handleMovePlayer", "handleMoveVehicle"},
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;[Ljava/lang/Object;)V",
                    remap = false
            )
    )
    private void villagerdetails$suppressMoveWarnings(Logger logger, String message, Object[] args) {
        if (RuleCache.isEnabled(RuleType.GLOBAL_SPEED_BYPASS) && this.villagerdetails$isBypassAllowed()) {
            if (message != null && (message.contains("moved too quickly") || message.contains("moved wrongly"))) {
                return;
            }
        }
        logger.warn(message, args);
    }
}
