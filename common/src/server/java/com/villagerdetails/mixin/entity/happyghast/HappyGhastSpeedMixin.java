package com.villagerdetails.mixin.entity.happyghast;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import com.villagerdetails.util.ParseUtils;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HappyGhast.class)
public abstract class HappyGhastSpeedMixin {

    @Shadow
    public abstract LivingEntity getControllingPassenger();

    @Unique
    private static final double VANILLA_SPEED = 0.05;

    @Inject(method = "addPassenger", at = @At("TAIL"))
    private void onAddPassenger(CallbackInfo ci) {
        updateSpeed();
    }

    @Inject(method = "removePassenger", at = @At("TAIL"))
    private void onRemovePassenger(CallbackInfo ci) {
        updateSpeed();
    }

    @Unique
    private void updateSpeed() {
        HappyGhast self = (HappyGhast) (Object) this;
        AttributeInstance attribute = self.getAttribute(Attributes.FLYING_SPEED);
        if (attribute == null) return;

        // 规则未开启时，强制恢复原版速度
        if (!RuleCache.isEnabled(RuleType.HAPPY_GHAST_SPEED)) {
            attribute.setBaseValue(VANILLA_SPEED);
            return;
        }

        // 判断是否有玩家在控制
        if (this.getControllingPassenger() instanceof Player) {
            double customSpeed = ParseUtils.toDouble(
                    RuleCache.getState(RuleType.HAPPY_GHAST_SPEED),
                    VANILLA_SPEED
            );
            attribute.setBaseValue(customSpeed);
        } else {
            attribute.setBaseValue(VANILLA_SPEED);
        }
    }
}