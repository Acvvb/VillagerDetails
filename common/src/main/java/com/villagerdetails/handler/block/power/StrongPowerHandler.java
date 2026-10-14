package com.villagerdetails.handler.block.power;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.section.item.BlockStrongPowerConfig;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.world.level.block.Block;

/**
 * 强充能规则的过滤器：<b>规则开着</b>并且方块在名单里时，才把它当成红石导体。
 *
 * <p>和「可采集 / 挖掘抗性 / 爆炸抗性」保持一致：先看规则开关、再看名单 ——
 * 关掉规则就等于对原版零影响，而名单本身可以留着，下次开规则继续用。
 *
 * <p>开关判定放在前面：关规则是这个方法最常见的返回路径，先判它就省掉了后面那次注册表反查。
 */
public final class StrongPowerHandler {

    private StrongPowerHandler() {
    }

    /**
     * 这个方块现在算不算红石导体
     */
    public static boolean isConductor(Block block) {
        if (!RuleCache.isEnabled(RuleType.BLOCK_STRONG_POWER)) {
            return false;
        }
        return BlockStrongPowerConfig.INSTANCE.contains(block);
    }
}
