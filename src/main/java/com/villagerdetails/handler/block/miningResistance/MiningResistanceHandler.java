package com.villagerdetails.handler.block.miningResistance;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.BlockMiningResistanceConfig;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.world.level.block.Block;

/**
 * 挖掘抗性规则的过滤器：读取 {@link BlockMiningResistanceConfig} 里的 {@code 方块id → 抗性}，
 * 返回某个方块被覆盖后的破坏时间（挖掘抗性）。规则关闭时不生效。
 */
public final class MiningResistanceHandler {

    private MiningResistanceHandler() {
    }

    /**
     * 返回该方块的抗性覆盖值；未配置 / 规则未开启时返回 {@code null}，调用方走原版逻辑。
     */
    public static Float getOverride(Block block) {
        if (!RuleCache.isEnabled(RuleType.BLOCK_MINING_RESISTANCE)) return null;
        return BlockMiningResistanceConfig.get(block);
    }
}
