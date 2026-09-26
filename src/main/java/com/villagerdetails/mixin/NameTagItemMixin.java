package com.villagerdetails.mixin;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.handler.villager.trader.AutoVillagerTrader;
import com.villagerdetails.handler.villager.trader.IdTranslation;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.NameTagItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.function.Predicate;

import static com.villagerdetails.rule.type.RuleType.VILLAGER_AUTO_TRADER;
import static com.villagerdetails.rule.type.RuleType.VILLAGER_SILENT_AUTO_REROLL_TRADER;
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

        //规则状态检测
        if (!RuleCache.isEnabled(VILLAGER_AUTO_TRADER)) return;

        // 只在原版命名成功时触发（PASS 说明没名字或不能命名）
        InteractionResult result = cir.getReturnValue();
        if (result == null || !result.consumesAction()) return;

        // 只在服务端执行
        if (player.level().isClientSide()) return;

        // 目标必须是村民
        if (!(target instanceof Villager villager)) return;

        // 拿命名牌上的自定义名字
        Component customName = itemStack.get(DataComponents.CUSTOM_NAME);
        if (customName == null) return;

        // 解析名字 → 目标物品
        Predicate<ItemStack> want = parseWanted(
                (ServerLevel) player.level(), customName.getString());

        if (want == null) {
            sendOverlayOrBroadcast((ServerPlayer) player,
                    Component.literal("§c无法识别 " + customName.getString()
                            + "。可用中文名（如 经验修补）或英文 ID（mending）"));
            villager.setCustomName(null);
            return;
        }

        // 触发自动刷新
        int attempts = AutoVillagerTrader.tick(villager, 500, want);

        //是否移除村民命名
        if(RuleCache.isEnabled(VILLAGER_SILENT_AUTO_REROLL_TRADER)) villager.setCustomName(null);

        ServerPlayer sp = (ServerPlayer) player;
        if (attempts > 0) {
            sendOverlayOrBroadcast(sp, Component.literal(
                    "§a命中！第 §e" + attempts + " §a次刷新就出了 " + customName.getString()));
        } else if (attempts == 0) {
            sendOverlayOrBroadcast(sp, Component.literal(
                    "§e刷了 §c500 §e次都没命中，再命名一次试试"));
        } else {
            sendOverlayOrBroadcast(sp, Component.literal(
                    "§c该村民无法刷新"));
        }
    }

    /**
     * 名字 → 目标物品判断
     * 仅支持附魔书
     */
    @Unique
    private static Predicate<ItemStack> parseWanted(ServerLevel level, String raw) {
        Identifier id = IdTranslation.resolveEnchantId(raw);
        if (id == null) return null;
        Registry<Enchantment> enchRegistry = level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT);
        Enchantment enchantment = enchRegistry.getValue(id);
        if (enchantment == null) return null;
        return stack -> {
            if (!stack.is(Items.ENCHANTED_BOOK)) return false;
            ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
            if (stored == null) return false;
            for (Holder<Enchantment> h : stored.keySet()) {
                if (h.value() != enchantment) continue;
                return stored.getLevel(h) >= h.value().getMaxLevel();
            }
            return false;
        };
    }
}