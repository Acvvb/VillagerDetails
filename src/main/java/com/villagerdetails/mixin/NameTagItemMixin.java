package com.villagerdetails.mixin;

import com.villagerdetails.handler.villager.trader.AutoVillagerTrader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.NameTagItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.villagerdetails.util.SendMessengerUtils.sendOverlayOrBroadcast;

@Mixin(NameTagItem.class)
public class NameTagItemMixin {

    @Inject(method = "interactLivingEntity", at = @At("RETURN"))
    private void villagerdetails$onNameTag(
            ItemStack itemStack,
            Player player,
            LivingEntity target,
            InteractionHand type,
            CallbackInfoReturnable<InteractionResult> cir) {

        // 1) 只在原版命名成功时触发
        InteractionResult result = cir.getReturnValue();
        if (result == null || !result.consumesAction()) return;

        // 2) 只在服务端执行
        if (player.level().isClientSide()) return;

        // 3) 目标必须是村民
        if (!(target instanceof Villager villager)) return;

        // 4) 拿命名牌上的自定义名字
        Component customName = itemStack.get(DataComponents.CUSTOM_NAME);
        if (customName == null) return;

        String name = customName.getString();

        // ★ 只处理支持的三类名字，其余直接放行（不弹消息）
        if (!AutoVillagerTrader.isSupportedName(villager, name)) return;

        // 5) 支持的名字 → 调顶层入口
        int attempts = AutoVillagerTrader.tickByName(villager, name, 500);
        villager.setCustomName(null);

        // 6) 反馈
        ServerPlayer sp = (ServerPlayer) player;
        if (attempts > 0) {
            sendOverlayOrBroadcast(sp, Component.literal(
                    "§a命中！第 §e" + attempts + " §a次刷新就出了 " + name));
        } else if (attempts == 0) {
            sendOverlayOrBroadcast(sp, Component.literal(
                    "§e刷了 §c500 §e次都没命中，再命名一次试试"));
        } else if (attempts == -1) {
            sendOverlayOrBroadcast(sp, Component.literal(
                    "§c该村民无法刷新（已锁定 / 小孩 / 无职业 / 无工作站）"));
        } else if (attempts == -2) {
            sendOverlayOrBroadcast(sp, Component.literal(
                    "§c该村民刷不出 " + name));
        } else if (attempts == -3) {
            sendOverlayOrBroadcast(sp, Component.literal(
                    "§c对应规则未开启，请先启用"));
        }
    }
}