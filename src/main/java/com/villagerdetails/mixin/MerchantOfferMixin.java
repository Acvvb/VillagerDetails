package com.villagerdetails.mixin;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MerchantOffer.class)
public abstract class MerchantOfferMixin {

    /**
     * 规则开启时，交易后 uses 不自增 —— 交易永不售罄。
     */
    @Inject(method = "increaseUses", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$infiniteTrade(CallbackInfo ci) {
        if (RuleCache.isEnabled(RuleType.VILLAGER_INFINITE_TRADE)) {
            ci.cancel();
        }
    }
}