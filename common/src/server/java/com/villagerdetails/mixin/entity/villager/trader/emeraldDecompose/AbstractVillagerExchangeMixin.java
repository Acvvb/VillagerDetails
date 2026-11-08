package com.villagerdetails.mixin.entity.villager.trader.emeraldDecompose;

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

/**
 * 「村民可兑换钻石」：把工具匠 / 武器匠高等级的「钻石 → 绿宝石」原地改写成「绿宝石 → 钻石」。
 *
 * <p><b>已知脆弱点</b>：定位用的是 {@code offers} 的<b>下标 ≥ 5</b>（"第 6 笔起"），而不是村民等级
 * 或交易内容特征。交易表结构一旦变化（别的模组插入条目、版本调整交易数目/顺序），
 * 就可能改到不该改的条目上 —— 改这里之前先确认 {@code offers} 的顺序假设仍然成立。
 */
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