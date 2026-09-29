package com.villagerdetails.mixin;

import com.villagerdetails.handler.villager.trader.refresh.AutoVillagerTrader;
import com.villagerdetails.util.ParseUtils;
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

import static com.villagerdetails.cache.RuleCache.getState;
import static com.villagerdetails.rule.type.RuleType.VILLAGER_ONE_TICK_TRADER_COUNT;
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
        String defaultValue = getState(VILLAGER_ONE_TICK_TRADER_COUNT);
        int count = ParseUtils.toInt(defaultValue,ParseUtils.toInt(defaultValue));
        int attempts = AutoVillagerTrader.tickByName(villager, name, count);
        villager.setCustomName(null);

        // 6) 反馈
        ServerPlayer sp = (ServerPlayer) player;
        switch (attempts) {
            case -1 -> sendOverlayOrBroadcast(sp,
                    Component.translatable("msg.auto_trader.locked"));

            case -2 -> sendOverlayOrBroadcast(sp,
                    Component.translatable("msg.auto_trader.no_match", name));

            case -3 -> sendOverlayOrBroadcast(sp,
                    Component.translatable("msg.auto_trader.rule_disabled"));

            case 0 -> sendOverlayOrBroadcast(sp,
                    Component.translatable("msg.auto_trader.no_hit", count));

            default -> {
                // attempts > 0（唯一剩下的可能）
                sendOverlayOrBroadcast(sp,
                        Component.translatable("msg.auto_trader.success", attempts, name));
            }
        }
    }
}