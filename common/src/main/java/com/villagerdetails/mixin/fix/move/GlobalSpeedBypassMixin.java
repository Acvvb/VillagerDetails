package com.villagerdetails.mixin.fix.move;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class GlobalSpeedBypassMixin {

    @Shadow
    public abstract ServerPlayer getPlayer();

    /**
     * 核心拦截：当规则开启时，直接让 shouldCheckPlayerMovement 返回 false。
     * 这意味着服务端完全跳过对玩家和载具的移动速度校验。
     */
    @Inject(
            method = "shouldCheckPlayerMovement",
            at = @At("HEAD"),
            cancellable = true
    )
    private void villagerdetails$skipMovementCheck(boolean isFallFlying, CallbackInfoReturnable<Boolean> cir) {
        if (RuleCache.isEnabled(RuleType.GLOBAL_SPEED_BYPASS)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 兜底方案：如果仍有少量警告漏过，在此静默移动相关日志。
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
        if (RuleCache.isEnabled(RuleType.GLOBAL_SPEED_BYPASS)) {
            if (message != null && (message.contains("moved too quickly") || message.contains("moved wrongly"))) {
                return;
            }
        }
        logger.warn(message, args);
    }
}