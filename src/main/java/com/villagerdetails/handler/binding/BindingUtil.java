package com.villagerdetails.handler.binding;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.handler.binding.type.BindingType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public class BindingUtil {

    /**
     * 检查玩家是否持有任意已启用的绑定工具
     *
     * @param player 玩家
     * @param hand   交互手
     * @param entity 目标实体（可为 null，为 null 时不校验实体类型）
     * @return 匹配到的 BindingType，无匹配则返回 null
     */
    public static BindingType isHoldingAnyTool(Player player, InteractionHand hand, Entity entity) {
        for (BindingType type : BindingType.values()) {
            if (!RuleCache.isEnabled(type.getRuleType())) continue;
            if (BindHandler.isHoldingTool(player, hand, type.getRequiredItem(), type.getRequiredToolName())) {
                if (entity == null || type.getEntityClass().isInstance(entity)) {
                    return type;
                }
            }
        }
        return null;
    }

}