package com.villagerdetails.handler;

import com.villagerdetails.cache.SelectionState;
import com.villagerdetails.util.SendMessengerUtils;
import com.villagerdetails.util.VillagerBedUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.UUID;

/**
 * 村民床绑定交互处理器
 * 处理"蹲着 + 手持改名拴绳"右键村民和右键床的逻辑
 */
public class BedBindingHandler {

    private static final String REQUIRED_TOOL_NAME = "bed";

    // ==================== 右键村民：选中村民 ====================

    /**
     * 处理玩家右键实体事件
     * @return InteractionResult 交互结果
     */
    public static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity) {
        // 只处理服务器端
        if (level.isClientSide()) {
            return InteractionResult.PASS;
        }

        // 必须是村民
        if (!(entity instanceof Villager villager)) {
            return InteractionResult.PASS;
        }

        // 验证前置条件：必须蹲着且手持改名为"bed"的拴绳
        if (isHoldingBedTool(player, hand)) {
            return InteractionResult.PASS;
        }

        // 满足所有条件：选中该村民
        UUID playerUuid = player.getUUID();
        UUID villagerUuid = villager.getUUID();
        SelectionState.setSelectedVillager(playerUuid, villagerUuid);

        // 发送提示消息
        SendMessengerUtils.sendOrBroadcastActionBar((ServerPlayer) player, Component.translatable("msg.villager_bed.select.success", villagerUuid.toString().substring(0, 8)));

        // 返回 SUCCESS 阻止原版拴绳交互（防止把村民拴住）
        return InteractionResult.SUCCESS;
    }

    // ==================== 右键方块：绑定床 ====================

    /**
     * 处理玩家右键方块事件
     * @return InteractionResult 交互结果
     */
    public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        // 只处理服务器端
        if (level.isClientSide()) {
            return InteractionResult.PASS;
        }

        // 验证前置条件：必须蹲着且手持改名为"bed"的拴绳
        if (isHoldingBedTool(player, hand)) {
            return InteractionResult.PASS;
        }

        // 检查之前是否选中了村民
        UUID playerUuid = player.getUUID();
        UUID villagerUuid = SelectionState.getSelectedVillager(playerUuid);
        if (villagerUuid == null) {
            return InteractionResult.PASS;
        }

        // 获取点击的方块位置
        BlockPos clickedPos = hitResult.getBlockPos();

        // 消耗一个物品
        player.getItemInHand(hand).shrink(1);

        // 执行绑定
        ServerLevel serverLevel = (ServerLevel) level;
        ServerPlayer operator = (ServerPlayer) player;
        VillagerBedUtils.changeVillagerBed(serverLevel, operator, villagerUuid, clickedPos);

        // 无论成功失败，都清除选中状态
        SelectionState.clearSelectedVillager(playerUuid);

        // 阻止原版交互（防止打开床的GUI或其他行为）
        return InteractionResult.SUCCESS;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 验证玩家是否满足手持工具的前置条件：
     * 1. 必须处于潜行（蹲下）状态
     * 2. 主手或副手必须持有物品
     * 3. 手持物品必须是拴绳
     * 4. 拴绳必须被重命名为指定的名称（忽略大小写）
     *
     * @param player 玩家对象
     * @param hand 交互的手
     * @return 是否满足手持工具条件
     */
    private static boolean isHoldingBedTool(Player player, InteractionHand hand) {
        // 必须蹲着
        if (!player.isShiftKeyDown()) {
            return true;
        }

        // 必须手持物品
        ItemStack itemInHand = player.getItemInHand(hand);
        if (itemInHand.isEmpty()) {
            return true;
        }

        // 必须是拴绳
        if (!itemInHand.is(Items.LEAD)) {
            return true;
        }

        // 必须改名为指定名称（不区分大小写）
        String customName = itemInHand.getHoverName().getString();
        return !REQUIRED_TOOL_NAME.equalsIgnoreCase(customName);
    }
}