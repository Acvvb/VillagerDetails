package com.villagerdetails.mixin;

import com.villagerdetails.cache.RuleCache;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.villagerdetails.rule.type.RuleType.VILLAGER_TOOLSMITH_EXCHANGE;

@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerExchangeMixin {

    @Inject(method = "getOffers", at = @At("RETURN"))
    private void villagerdetails$exchangeDiamondEmerald(CallbackInfoReturnable<MerchantOffers> cir) {
        AbstractVillager self = (AbstractVillager) (Object) this;

        if (self.level().isClientSide()) return;
        if (!RuleCache.isEnabled(VILLAGER_TOOLSMITH_EXCHANGE)) return;

        if (!(self instanceof Villager villager)) return;

        var prof = villager.getVillagerData().profession();
        if (!prof.is(VillagerProfession.TOOLSMITH)
                && !prof.is(VillagerProfession.WEAPONSMITH)) {
            return;
        }
        MerchantOffers offers = cir.getReturnValue();
        if (offers == null || offers.isEmpty()) return;

        for (int j = 5; j < offers.size(); j++) {
            MerchantOffer o = offers.get(j);
            ItemStack costA = o.getCostA();
            ItemStack result = o.getResult();
            if (!costA.is(Items.DIAMOND) || !result.is(Items.EMERALD)) continue;
            offers.set(j, getReplaced(costA, result, o));
        }
    }
    @Unique
    private static @NonNull MerchantOffer getReplaced(ItemStack costA, ItemStack result, MerchantOffer o) {
        int diamondCount = costA.getCount();
        int emeraldCount = result.getCount();

        // 换成 "绿宝石 → 钻石"
        ItemCost newCost = new ItemCost(Items.EMERALD, diamondCount);
        ItemStack newResult = new ItemStack(Items.DIAMOND, emeraldCount);

        return new MerchantOffer(
                newCost,
                newResult,
                o.getMaxUses(),
                o.getXp(),
                o.getPriceMultiplier()
        );
    }
}