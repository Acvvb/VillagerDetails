package com.villagerdetails.handler.binding.bbselection;

import com.villagerdetails.cache.BBSectionStateCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

/**
 * 选区物品交互处理器
 * <p>
 * 玩家手持木斧（可配置）时：
 * - 左键点击方块 → 设置 pos1
 * - 右键点击方块 → 设置 pos2
 * </p>
 */
public class SelectionInteractionHandler {

    public static final int POS1_SETTER = 1;
    public static final int POS2_SETTER = 2;

    public static boolean onLeftClickBlock(ServerPlayer player, BlockPos pos) {
        return onBlockClick(player, pos, POS1_SETTER);
    }

    public static boolean onRightClickBlock(ServerPlayer player, BlockPos pos) {
        return onBlockClick(player, pos, POS2_SETTER);
    }

    private static boolean onBlockClick(ServerPlayer player, BlockPos pos, int posSetter) {
        ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (checkSelectionTool(mainHand)) return false;

        if (posSetter == POS1_SETTER) {
            BBSectionStateCache.setPos1(player, pos);
        } else {
            BBSectionStateCache.setPos2(player, pos);
        }
        showPosSuccessMessage(player, pos, posSetter);
        return true;
    }

    public static void showPosSuccessMessage(ServerPlayer player, BlockPos pos, int posSeter) {
        sendOrBroadcast(player, Component.translatable("message.selection.pos_set", posSeter, pos.getX(), pos.getY(), pos.getZ()));
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