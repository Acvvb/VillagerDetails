package com.villagerdetails.mixin;

import com.villagerdetails.handler.villager.trader.decompose.ItemProvisioner;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MerchantMenu.class)
public abstract class MerchantMenuProvisionMixin {

    @Inject(method = "tryMoveItems", at = @At("HEAD"))
    private void villagerdetails$provision(int newTradeIndex, CallbackInfo ci) {
        MerchantMenuInterfaceMixin acc = (MerchantMenuInterfaceMixin) this;
        Merchant trader = acc.getTrader();
        MerchantContainer container = acc.getTradeContainer();
        if (trader == null || container == null) return;

        Player player = trader.getTradingPlayer();
        if (player == null) return;

        MerchantOffers offers = trader.getOffers();
        if (newTradeIndex < 0 || newTradeIndex >= offers.size()) return;

        MerchantOffer offer = offers.get(newTradeIndex);
        if (offer.isOutOfStock()) return;

        // 交易前精确制备，不做回收
        handleCost(player, container, offer.getItemCostA().itemStack(),
                offer.getCostA().getCount(), 0);
        offer.getItemCostB().ifPresent(costB ->
                handleCost(player, container, costB.itemStack(), costB.count(), 1));
    }

    /**
     * 精确制备：只补"槽位 + 背包"总缺口。
     * 备多了也没关系——会留在背包里，下次交易还能用。
     */
    @Unique
    private static void handleCost(Player player, MerchantContainer container,
                                   ItemStack costStack, int needCount, int slot) {
        Item target = costStack.getItem();
        if (!ItemProvisioner.hasRecipe(target)) return;

        Inventory inv = player.getInventory();

        // 1. 背包里已有多少目标物品
        int invCount = countInInventory(inv, target);

        // 2. 交易槽位里已有多少
        ItemStack slotStack = container.getItem(slot);
        int slotCount = slotStack.is(target) ? slotStack.getCount() : 0;

        // 3. 够不够
        int total = invCount + slotCount;
        if (total >= needCount) return;

        // 4. 按缺口制备
        int missing = needCount - total;
        ItemProvisioner.provision(player, target, missing);
    }

    @Unique
    private static int countInInventory(Inventory inv, Item target) {
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(target)) count += s.getCount();
        }
        return count;
    }
}