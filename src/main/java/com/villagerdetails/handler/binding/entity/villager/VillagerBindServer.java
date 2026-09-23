
package com.villagerdetails.handler.binding.entity.villager;

import com.villagerdetails.handler.binding.type.BindingType;
import com.villagerdetails.math.gridhashing.BindableMob;
import com.villagerdetails.math.gridhashing.BindableTarget;
import com.villagerdetails.math.gridhashing.GenericMobTargetMatcher;
import com.villagerdetails.math.gridhashing.villager.VillagerBedStrategy;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.List;
import java.util.Map;

import static com.villagerdetails.handler.binding.BindHandler.checkSameDimension;

/**
 * 村民绑定服务类
 * <p>
 * 对外统一入口，只负责流程编排，底层逻辑全部委托给 VillagerBindHandler 和 VillagerAreaScanner。
 * </p>
 */
public class VillagerBindServer {

    // ==================== 单个村民绑定 ====================

    /**
     * 根据 BindingType 执行村民绑定（床 / 工作方块）
     */
    public static boolean bindVillager(ServerLevel level, ServerPlayer operator,
                                       Villager villager, BlockPos targetPos, BindingType type) {
        if (villager == null) return false;

        if (checkSameDimension(villager, level, operator,
                Component.translatable("msg.villager.cross_dimension", type.getI18nPrefix())
        )) return false;

        return event(level, operator, type, villager, targetPos);
    }

    /**
     * 根据绑定类型分发到具体的绑定逻辑（仅限同包内调用）
     */
    static boolean event(ServerLevel level, ServerPlayer operator,
                         BindingType type, Villager villager, BlockPos targetPos) {
        return switch (type) {
            case BED -> VillagerBindHandler.bindBed(level, operator, villager, targetPos);
            case WORK_BLOCK -> VillagerBindHandler.bindWorkBlock(level, operator, villager, targetPos);
        };
    }

    // ==================== 区域批量绑定 ====================

    /**
     * 区域批量绑床（长方体区域）
     *
     * @param level    服务端世界
     * @param operator 操作玩家（可为 null）
     * @param pos1     长方体对角点1
     * @param pos2     长方体对角点2
     * @return 成功绑定的数量
     */
    public static int bindAreaBeds(ServerLevel level, ServerPlayer operator,
                                   BlockPos pos1, BlockPos pos2) {
        // 1. 扫描（委托给 VillagerAreaScanner）
        List<BindableMob> villagers = VillagerAreaScanner.scanVillagers(level, pos1, pos2);
        List<BindableTarget> beds = VillagerAreaScanner.scanBeds(level, pos1, pos2);

        if (villagers.isEmpty() || beds.isEmpty()) {
            return 0;
        }

        // 2. 匹配
        Map<BindableMob, BindableTarget> matched = GenericMobTargetMatcher.match(villagers, beds);

        // 3. 绑定（sendMsg=false，不逐个弹出提示，避免刷屏）
        VillagerBedStrategy strategy = new VillagerBedStrategy(level, operator, false);
        int successCount = 0;
        for (Map.Entry<BindableMob, BindableTarget> entry : matched.entrySet()) {
            if (strategy.bind(entry.getKey(), entry.getValue())) {
                successCount++;
            }

        }
        int villagerCount = villagers.size();
        int bedCount = (beds.size() + 1) / 2;
        if (successCount == 0) {
            operator.sendSystemMessage(Component.translatable(
                    "msg.villager.bind_area.no_match", villagerCount, bedCount
            ));
        } else {
            operator.sendSystemMessage(Component.translatable(
                    "msg.villager.bind_area.success", successCount, villagerCount, bedCount,
                    pos1.toShortString(), pos2.toShortString()
            ));
        }

        return successCount;
    }
}