package com.villagerdetails.handler.block.collectable;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.BlockCollectableConfig;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.world.level.block.Block;

/**
 * 可采集规则的过滤器：读取 {@link BlockCollectableConfig} 里的方块 id 集合，
 * 判断某方块是否被配置为「可采集」。规则关闭时不生效。
 */
public final class CollectableHandler {

    /** 被「可采集」规则点名、但原版不可破坏的方块，统一给这个默认破坏时间，使其可被挖掘。 */
    public static final float DEFAULT_BREAKABLE_SPEED = 1.5f;

    private CollectableHandler() {
    }

    /**
     * 方块是否在「可采集」名单里。
     */
    public static boolean isConfigured(Block block) {
        if (!RuleCache.isEnabled(RuleType.BLOCK_COLLECTABLE)) return false;
        return BlockCollectableConfig.contains(block);
    }

    /**
     * 若方块在「可采集」名单里且原版不可破坏（破坏时间 &lt; 0，如基岩），
     * 返回默认可破坏速度，使其能够被挖掘；否则返回 {@code null}。
     */
    public static Float getBreakableOverride(Block block) {
        if (!RuleCache.isEnabled(RuleType.BLOCK_COLLECTABLE)) return null;
        if (!BlockCollectableConfig.contains(block)) return null;
        if (block.defaultDestroyTime() >= 0f) return null;
        return DEFAULT_BREAKABLE_SPEED;
    }
}
