package com.villagerdetails.handler.villagerTrader;

import com.villagerdetails.mixin.AbstractVillagerMixin;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.function.Predicate;

public class AutoVillagerTrader {

    /**
     * 自动刷新村民交易，直到命中 want 或达到 maxAttempts。
     *
     * @return 结果编码：
     *         > 0  第 N 次刷新命中（N 从 1 开始）
     *         = 0  跑满 maxAttempts 未命中
     *         = -1 状态不对，未执行（已锁定/小孩/无职业/无工作站/客户端）
     */
    public static int tick(Villager villager, int maxAttempts, Predicate<ItemStack> want) {
        if (villager.level().isClientSide()) return -1;
        if (villager.isBaby()) return -1;
        if (villager.getVillagerXp() > 0) return -1;
        if (villager.getVillagerData().profession().is(VillagerProfession.NONE)) return -1;
        if (villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).isEmpty()) return -1;

        AbstractVillagerMixin acc = (AbstractVillagerMixin) villager;

        for (int i = 0; i < maxAttempts; i++) {
            acc.setOffersField(null);
            MerchantOffers offers = villager.getOffers();
            if (hasWanted(offers, want)) {
                lock(villager, offers);
                return i + 1;
            }
        }
        return 0;
    }

    private static boolean hasWanted(MerchantOffers offers, Predicate<ItemStack> want) {
        if (offers == null) return false;
        for (MerchantOffer o : offers) {
            if (want.test(o.getResult())) return true;
        }
        return false;
    }

    private static void lock(Villager villager, MerchantOffers offers) {
        if (offers != null && !offers.isEmpty()) {
            offers.getFirst().increaseUses();
        }
        villager.setVillagerXp(1);
    }
}