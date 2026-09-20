package com.villagerdetails.util;

import com.villagerdetails.cache.SelectionState;
import com.villagerdetails.event.type.BindingType;
import com.villagerdetails.util.entity.VillagerBindingUtils;
import net.minecraft.core.BlockPos;
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
    public static boolean checkSameDimension(Villager villager, ServerLevel targetLevel, ServerPlayer operator, Component message) {
        ResourceKey<Level> villagerDimension = villager.level().dimension();
        ResourceKey<Level> targetDimension = targetLevel.dimension();
        if (villagerDimension.equals(targetDimension)) return false;
        SendMessengerUtils.sendOrBroadcastActionBar(operator, message);
        return true;
    }

    public static boolean chooseUtil(Level level, Player player, InteractionHand hand) {
        UUID playerUuid = player.getUUID();
        UUID entityUuid = SelectionState.getSelectedEntity(playerUuid);
        BlockPos clickedPos = SelectionState.getSelectedBlock(playerUuid);

        if (entityUuid == null || clickedPos == null) return false;

        BindingType type = BindingType.isHoldingAnyTool(player, hand, level.getEntity(entityUuid));
        if (type == null) return false;

        player.getItemInHand(hand).shrink(1);

        ServerLevel serverLevel = (ServerLevel) level;
        ServerPlayer operator = (ServerPlayer) player;
        Entity entity = level.getEntity(entityUuid);

        // --- 新增：获取实体类型的本地化名称组件 ---
        // entity.getType().getDescription() 会自动返回如 "entity.minecraft.villager" 对应的翻译组件（如“村民”）
        Component entityName = entity.getType().getDescription();

        SelectionState.removeAll(playerUuid);
        boolean isSuccess = false;
        if (entity instanceof Villager) {
            isSuccess = VillagerBindingUtils.bindVillager(serverLevel, operator, entityUuid, clickedPos, type);
        }

        if (isSuccess) {
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable("msg.system.bind.success",
                            entityName,           // 新增：实体类型名（如“村民”）
                            entityUuid.toString(),
                            Component.translatable(type.getI18nPrefix()),
                            clickedPos.toShortString()
                    )
            );
        }
        return isSuccess;
    }
}












