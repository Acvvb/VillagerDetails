package com.villagerdetails.mixin.entity.villager.trader.autoTrader.trigger;

import com.villagerdetails.handler.entity.villager.trader.refresh.AutoVillagerTrader;
import com.villagerdetails.handler.entity.villager.trader.refresh.TradeRerollScheduler;
import com.villagerdetails.handler.monster.zombieVillager.ZombieVillagerCureHandler;
import com.villagerdetails.util.ParseUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
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

        // 3) 拿命名牌上的自定义名字（下面两种触发都要用）
        Component customName = itemStack.get(DataComponents.CUSTOM_NAME);
        if (customName == null) return;

        String name = customName.getString();

        // 4) 僵尸村民：写 help → 立刻走完原版治愈流程
        //    （ZombieVillager 不是 Villager 的子类，两个分支互不干扰）
        if (target instanceof ZombieVillager zombieVillager) {
            ZombieVillagerCureHandler.tryCure((ServerPlayer) player, zombieVillager, name);
            return;
        }

        // 5) 普通村民：自动刷新交易那套
        if (!(target instanceof Villager villager)) return;

        // ★ 只处理能解析成「点名目标」或固定关键字的名称，其余直接放行（不弹消息）
        if (!AutoVillagerTrader.isSupportedName(villager, name)) return;
        ServerPlayer sp = (ServerPlayer) player;
        String defaultValue = getState(VILLAGER_ONE_TICK_TRADER_COUNT);
        int count = ParseUtils.toInt(defaultValue, 500);
        int outcome = AutoVillagerTrader.tickByName(villager, name, count, sp);
        villager.setCustomName(null);

        // 6) 反馈
        switch (outcome) {
            case -1 -> sendOverlayOrBroadcast(sp, Component.translatable("msg.auto_trader.locked"));
            case -2 -> sendOverlayOrBroadcast(sp, Component.translatable("msg.auto_trader.no_match", name));
            case -3 -> sendOverlayOrBroadcast(sp, Component.translatable("msg.auto_trader.rule_disabled"));
            case -4 -> sendOverlayOrBroadcast(sp, Component.translatable("msg.auto_trader.wrong_profession"));
            case -7 -> sendOverlayOrBroadcast(sp, Component.translatable("msg.auto_trader.not_producible", name));
            case -8 -> sendOverlayOrBroadcast(sp, Component.translatable("msg.auto_trader.too_many_tasks"));
            // 点名路线：任务已排队，结果由分帧调度器稍后提示
            case 1 -> {
                if (AutoVillagerTrader.isSpecialName(name)) {
                    sendOverlayOrBroadcast(sp, Component.translatable("msg.auto_trader.success", 1, name));
                } else {
                    sendOverlayOrBroadcast(sp, Component.translatable("msg.auto_trader.task_started",
                            name, TradeRerollScheduler.MAX_ATTEMPTS));
                }
            }
            case 0 -> sendOverlayOrBroadcast(sp, Component.translatable("msg.auto_trader.no_hit", count));
            // 只剩 331a/331b 的同步路线会走到这里
            default -> sendOverlayOrBroadcast(sp, Component.translatable("msg.auto_trader.success", outcome, name));
        }
    }
}
