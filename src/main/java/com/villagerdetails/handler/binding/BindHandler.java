package com.villagerdetails.handler.binding;

import com.villagerdetails.cache.EBSelectionStateCache;
import com.villagerdetails.handler.binding.entity.villager.VillagerBindHandler;
import com.villagerdetails.handler.binding.type.BindingType;
import com.villagerdetails.util.SendMessengerUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class BindHandler {

    /**
     * 通用工具检测：蹲着 + 手持改名为指定名称的拴绳
     * @param player 玩家
     * @param hand 交互手
     * @param requiredItem 要求的工具物品（如拴绳）
     * @param requiredToolName 要求的工具名称（如 "bed" 或 "work"）
     * @return true 表示当前手持的是正确的工具
     */
    public static boolean isHoldingTool(Player player, InteractionHand hand, Item requiredItem, String requiredToolName) {
        if (!player.isShiftKeyDown()) return false;
        ItemStack itemInHand = player.getItemInHand(hand);
        if (itemInHand.isEmpty()) return false;
        if (!itemInHand.is(requiredItem)) return false;
        String customName = itemInHand.getHoverName().getString();
        return requiredToolName.equalsIgnoreCase(customName);
    }

    /**
     * 校验实体和目标方块是否在同一维度
     *
     * @param entity       实体
     * @param targetLevel  目标方块所在维度
     * @param operator     操作玩家
     * @param message      提示消息
     * @return 是否不在同一维度（true=维度不同，发送提示；false=同一维度）
     */
    public static boolean checkSameDimension(Entity entity, ServerLevel targetLevel, ServerPlayer operator, Component message) {
        ResourceKey<Level> entityDimension = entity.level().dimension();
        ResourceKey<Level> targetDimension = targetLevel.dimension();
        if (entityDimension.equals(targetDimension)) return false;
        SendMessengerUtils.sendOverlayOrBroadcast(operator, message);
        return true;
    }

    public static boolean chooseUtil(Level level, Player player, InteractionHand hand) {
        UUID playerUuid = player.getUUID();
        // 原子取出并清空选择状态：绑定无论成败，缓存都不会残留旧状态导致重复触发
        EBSelectionStateCache.SelectionState state = EBSelectionStateCache.take(playerUuid);
        if (state == null || !state.isComplete()) return false;

        Entity entity = level.getEntity(state.entityUuid());
        BindingType type = BindingUtil.isHoldingAnyTool(player, hand, entity);
        if (type == null || !(entity instanceof Villager villager)) return false;

        ServerLevel serverLevel = (ServerLevel) level;
        ServerPlayer operator = (ServerPlayer) player;

        boolean isSuccess = VillagerBindHandler.bindVillager(serverLevel, operator, villager, state.blockPos(), type);
        if (isSuccess) {
            // 只有真正绑定成功才消耗工具，避免"已绑定过 / 失败"时白白消耗拴绳
            player.getItemInHand(hand).shrink(1);
        }
        return isSuccess;
    }
}












