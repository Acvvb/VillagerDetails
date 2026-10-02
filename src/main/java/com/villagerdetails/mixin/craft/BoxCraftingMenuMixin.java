package com.villagerdetails.mixin.craft;

import com.villagerdetails.handler.craft.BoxCraftingHandler;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 盒子合成：在工作台结果计算阶段显示盒子合成预览，并在 Shift 点击时接管结果槽的快速移动。
 * <p>
 * 注意：craftSlots / resultSlots 都在父类 AbstractCraftingMenu 上，
 * 所以这里统一通过 AbstractCraftingMenuAccessor 访问，不再 @Shadow。
 */
@Mixin(CraftingMenu.class)
public abstract class BoxCraftingMenuMixin {

    /**
     * 结果槽预览：若格子里摆出「装满同一物品」的潜影盒且能匹配原版配方，
     * 把结果槽替换为盒子合成的预览，并取消原版逻辑。
     */
    @Inject(method = "slotChangedCraftingGrid", at = @At("HEAD"), cancellable = true)
    private static void villagerdetails$boxCraftResult(
            AbstractContainerMenu menu, ServerLevel level, Player player,
            CraftingContainer container, ResultContainer resultSlots,
            @Nullable RecipeHolder<CraftingRecipe> recipeHint, CallbackInfo ci) {

        if (!BoxCraftingHandler.isEnabled()) return;

        BoxCraftingHandler.BoxCraftPlan plan =
                BoxCraftingHandler.computePlan(level, container);
        if (plan == null) return;

        ItemStack preview = plan.preview();
        resultSlots.setItem(0, preview);
        menu.setRemoteSlot(0, preview);
        if (player instanceof ServerPlayer sp) {
            sp.connection.send(new ClientboundContainerSetSlotPacket(
                    menu.containerId, menu.incrementStateId(), 0, preview));
        }
        ci.cancel();
    }

    /**
     * Shift 点击结果槽：原版会先把 preview moveItemStackTo 进背包，再走 onTake，
     * 导致 preview 和 execute 产物叠加。这里提前拦截，直接发放真实产物。
     */
    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$boxCraftQuickMove(
            Player player, int slotIndex, CallbackInfoReturnable<ItemStack> cir) {

        if (slotIndex != 0) return;
        if (!BoxCraftingHandler.isEnabled()) return;
        if (!(player instanceof ServerPlayer sp)) return;

        CraftingMenu self = (CraftingMenu) (Object) this;
        AbstractCraftingMenuAccessor acc = (AbstractCraftingMenuAccessor) self;
        CraftingContainer craftSlots = acc.villagerdetails$getCraftSlots();
        ResultContainer resultSlots = acc.villagerdetails$getResultSlots();
        if (craftSlots == null || resultSlots == null) return;

        BoxCraftingHandler.BoxCraftPlan plan =
                BoxCraftingHandler.computePlan(sp.level(), craftSlots);
        if (plan == null) return;

        // 先清空结果槽的 preview，阻止原版 moveItemStackTo
        resultSlots.setItem(0, ItemStack.EMPTY);

        // 发放真实产物
        plan.execute(sp, craftSlots);

        // 网格已空，触发容器更新让菜单重算（结果槽会变 EMPTY）
        craftSlots.setChanged();

        // 返回空栈，表示"没有物品被移动"
        cir.setReturnValue(ItemStack.EMPTY);
    }
}