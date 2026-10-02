package com.villagerdetails.handler.block.explosionResistance;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.BlockExplosionResistanceConfig;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.world.level.block.Block;

/**
 * 爆炸抗性规则的过滤器：读取 {@link BlockExplosionResistanceConfig} 里的 {@code 方块id → 爆炸抗性}，
 * 返回某个方块被覆盖后的爆炸抗性。规则关闭时不生效。
 */
public final class ExplosionResistanceHandler {

    private ExplosionResistanceHandler() {
    }

    /**
     * 返回该方块的爆炸抗性覆盖值；未配置 / 规则未开启时返回 {@code null}，调用方走原版逻辑。
     */
    public static Float getOverride(Block block) {
        if (!RuleCache.isEnabled(RuleType.BLOCK_EXPLOSION_RESISTANCE)) return null;
        return BlockExplosionResistanceConfig.get(block);
    }
}
