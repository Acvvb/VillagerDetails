package com.villagerdetails.handler.binding.bbselection;

import com.villagerdetails.cache.BBSectionStateCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 选区物品交互处理器
 * <p>
 * 玩家手持木斧（可配置）时：
 * - 左键点击方块 → 设置 pos1
 * - 右键点击方块 → 设置 pos2
 * </p>
 */
public class SelectionInteractionHandler {

    /**
     * 处理玩家左键点击方块（设置 pos1）
     *
     * @param player 玩家
     * @param pos    被点击的方块位置
     * @return 是否处理了该事件
     */
    public static boolean onLeftClickBlock(ServerPlayer player, BlockPos pos) {
        ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (checkSelectionTool(mainHand)) return false;
        BBSectionStateCache.setPos1(player, pos);
        player.sendSystemMessage(Component.literal(
                String.format("§a[选区] pos1: [%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ())
        ));
        return true;
    }

    /**
     * 处理玩家右键点击方块（设置 pos2）
     *
     * @param player 玩家
     * @param pos    被点击的方块位置
     * @return 是否处理了该事件
     */
    public static boolean onRightClickBlock(ServerPlayer player, BlockPos pos) {
        ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (checkSelectionTool(mainHand)) return false;
        BBSectionStateCache.setPos2(player, pos);
        player.sendSystemMessage(Component.literal(
                String.format("§a[选区] pos2: [%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ())
        ));

        return true;
    }


    /**
     * 检查玩家手持的物品是否为选区工具（命名为"tool"的拴绳）
     *
     * @param itemStack 物品
     * @return true 表示是选区工具，false 表示不是
     */
    private static boolean checkSelectionTool(ItemStack itemStack) {
        // 检查是否是拴绳
        if (!itemStack.is(Items.LEAD)) {
            return true;
        }

        // 检查是否有自定义名称
        Component customName = itemStack.getCustomName();
        if (customName == null) {
            return true;
        }

        // 检查自定义名称是否为"tool"
        return !"tool".equals(customName.getString());
    }
}