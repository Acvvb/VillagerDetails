package com.villagerdetails.util;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class BindingToolUtils {

    /**
     * 通用工具检测：蹲着 + 手持改名为指定名称的拴绳
     * @param player 玩家
     * @param hand 交互手
     * @param requiredToolName 要求的工具名称（如 "bed" 或 "workblock"）
     * @return true 表示不满足条件（应返回 PASS 放行），false 表示满足条件（可以继续处理）
     */
    public static boolean isNotHoldingTool(Player player, InteractionHand hand, Item requiredItem, String requiredToolName) {
        if (!player.isShiftKeyDown()) return false;
        ItemStack itemInHand = player.getItemInHand(hand);
        if (itemInHand.isEmpty()) return false;
        if (!itemInHand.is(requiredItem)) return false;
        String customName = itemInHand.getHoverName().getString();
        return requiredToolName.equalsIgnoreCase(customName);
    }

    /**
     * 校验村民和目标方块是否在同一维度
     */
    public static boolean checkSameDimension(Villager villager, ServerLevel targetLevel, ServerPlayer operator, String failTranslationKey) {
        ResourceKey<Level> villagerDimension = villager.level().dimension();
        ResourceKey<Level> targetDimension = targetLevel.dimension();
        if (villagerDimension.equals(targetDimension)) return false;
        SendMessengerUtils.sendOrBroadcastActionBar(operator, Component.translatable(failTranslationKey));
        return true;
    }
}