package com.villagerdetails.mixin.craft;

import com.villagerdetails.handler.craft.BoxCraftingHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 盒子合成：接管 ResultSlot.onTake。
 * <p>
 * 关键点：Slot.safeTake 中 onTake 的参数与 safeTake 返回给调用者的 ItemStack 是同一个引用，
 * 因此把 taken 的数量清零后，调用者拿到的是空栈，原版不会再给玩家任何 preview 物品。
 * 真正的产物由 BoxCraftingPlan.execute 发放。
 */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {

    @Shadow
    @Final
    private CraftingContainer craftSlots;

    @Shadow
    @Final
    private Player player;

    @Inject(method = "onTake", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$boxCraftTake(Player takingPlayer, ItemStack taken, CallbackInfo ci) {
        if (!BoxCraftingHandler.isEnabled()) return;
        if (!(this.player instanceof ServerPlayer sp)) return;

        BoxCraftingHandler.BoxCraftPlan plan = BoxCraftingHandler.computePlan(sp.level(), this.craftSlots);
        if (plan == null) return;

        // ★ 关键修复：清空玩家即将拿到的那份 preview。
        // 因为 taken 与 safeTake 返回值是同一引用，setCount(0) 后调用者拿到空栈，
        // 原版 doClick 的 setCarried(taken) 就变成了 setCarried(EMPTY)，不会再发放 preview。
        if (!taken.isEmpty()) {
            taken.setCount(0);
        }

        // 防御性：若玩家光标上还有残留（例如 shift 点击路径），一并清掉。
        if (!sp.containerMenu.getCarried().isEmpty()) {
            sp.containerMenu.setCarried(ItemStack.EMPTY);
        }

        // 发放真正的产物：多盒 / 空盒 / 未装箱散货（溢出掉落），并清空输入盒、消耗背包空盒。
        plan.execute(sp, this.craftSlots);

        // 触发一次容器更新，让结果槽重算（此时网格已空，结果会变为 EMPTY）。
        this.craftSlots.setChanged();

        // 阻止原版 ResultSlot.onTake 继续执行（避免再消耗一次合成网格、记录成就等）。
        ci.cancel();
    }
}