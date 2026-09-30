package com.villagerdetails.mixin.villager.trader.autoTrader.hardWork;


import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.mixin.villager.trader.autoTrader.MerchantOfferInterfaceMixin;
import com.villagerdetails.util.ParseUtils;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import static com.villagerdetails.rule.type.RuleType.VILLAGER_HARD_WORKING;

@Mixin(Villager.class)
public class MerchantOfferResetMixin {

    @Redirect(
            method = "restock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/trading/MerchantOffer;resetUses()V"
            )
    )
    private void customResetUses(MerchantOffer offer) {
        String state = RuleCache.getState(VILLAGER_HARD_WORKING);
        String defaultValue = VILLAGER_HARD_WORKING.getState();
        float multiple = ParseUtils.toFloat(state,ParseUtils.toFloat(defaultValue)) - 1.0F;
        if (multiple <= 0) {
            offer.resetUses();
            return;
        }
        int maxUses = offer.getMaxUses();
        int newUses = -(int) (multiple * maxUses);
        ((MerchantOfferInterfaceMixin) offer).setUses(newUses);
    }

}
