package com.villagerdetails.mixin.craft;

import com.villagerdetails.handler.craft.BoxCraftPlan;
import com.villagerdetails.handler.craft.BoxCraftingHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 盒子合成：接管物品栏 2x2 的结果槽 Shift 点击。
 * <p>
 * 原版 InventoryMenu.quickMoveStack 在 slotIndex == 0 时同样会先 moveItemStackTo
 * 把 preview 塞进背包，再走 onTake，导致 preview 与 execute 产物叠加。
 * 这里提前拦截，直接发放真实产物并阻止原版快速移动。
 * <p>
 * craftSlots / resultSlots 在 AbstractCraftingMenu 上，通过 AbstractCraftingMenuAccessor 访问。
 */
@Mixin(InventoryMenu.class)
public abstract class InventoryMenuMixin {

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$boxCraftQuickMove(
            Player player, int slotIndex, CallbackInfoReturnable<ItemStack> cir) {

        if (slotIndex != 0) return;
        if (!BoxCraftingHandler.isEnabled()) return;
        if (!(player instanceof ServerPlayer sp)) return;

        InventoryMenu self = (InventoryMenu) (Object) this;
        AbstractCraftingMenuAccessor acc = (AbstractCraftingMenuAccessor) self;
        CraftingContainer craftSlots = acc.villagerdetails$getCraftSlots();
        ResultContainer resultSlots = acc.villagerdetails$getResultSlots();
        if (craftSlots == null || resultSlots == null) return;

        BoxCraftPlan plan = BoxCraftingHandler.computePlan(sp.level(), craftSlots);
        if (plan == null) return;

        // 清空结果槽的 preview，阻止原版 moveItemStackTo
        resultSlots.setItem(0, ItemStack.EMPTY);

        // 发放真实产物
        plan.execute(sp, craftSlots);

        // 触发容器更新，菜单会重算（网格已空，结果槽变 EMPTY）
        craftSlots.setChanged();

        // 返回空栈，表示"没有物品被移动"
        cir.setReturnValue(ItemStack.EMPTY);
    }
}