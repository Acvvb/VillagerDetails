package com.villagerdetails.mixin.craft;

import com.villagerdetails.handler.craft.BoxCraftPlan;
import com.villagerdetails.handler.craft.BoxCraftingHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 盒子合成：接管 ResultSlot.onTake（普通点击路径）。
 * <p>
 * 关键点：Slot.safeTake 中 onTake 的参数与返回给调用者的 ItemStack 是同一引用，
 * 所以 taken.setCount(0) 就能让调用者拿到空栈，原版不会再发 preview。
 * Shift 点击路径由 BoxCraftingHelper / quickMoveStack 拦截，不走这里。
 */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {

    @Inject(method = "onTake", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$boxCraftTake(Player takingPlayer, ItemStack taken, CallbackInfo ci) {
        if (!BoxCraftingHandler.isEnabled()) return;
        if (!(takingPlayer instanceof ServerPlayer sp)) return;

        // 从当前菜单拿 craftSlots
        if (!(sp.containerMenu instanceof AbstractCraftingMenu menu)) return;
        CraftingContainer craftSlots =
                ((AbstractCraftingMenuAccessor) menu).villagerdetails$getCraftSlots();
        if (craftSlots == null) return;

        BoxCraftPlan plan = BoxCraftingHandler.computePlan(sp.level(), craftSlots);
        if (plan == null) return;

        // 清空玩家即将拿到的 preview
        if (!taken.isEmpty()) {
            taken.setCount(0);
        }
        // 防御性：清光标残留
        if (!sp.containerMenu.getCarried().isEmpty()) {
            sp.containerMenu.setCarried(ItemStack.EMPTY);
        }

        plan.execute(sp, craftSlots);
        craftSlots.setChanged();
        ci.cancel();
    }
}