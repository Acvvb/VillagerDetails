package com.villagerdetails.handler.block.collectable;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 可采集规则的过滤器：解析 state 里的方块 id 列表，判断某方块是否被配置为「可采集」。
 */
public final class CollectableHandler {

    /** 被「可采集」规则点名、但原版不可破坏的方块，统一给这个默认破坏时间，使其可被挖掘。 */
    public static final float DEFAULT_BREAKABLE_SPEED = 1.5f;

    private CollectableHandler() {
    }

    /** 缓存：state 字符串 → 解析后的 id 集合 */
    private static volatile String cachedState = "";
    private static volatile Set<Identifier> cachedIds = Set.of();

    /**
     * 方块是否在「可采集」名单里。
     */
    public static boolean isConfigured(Block block) {
        Set<Identifier> ids = getIds();
        if (ids.isEmpty()) return false;

        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return id != null && ids.contains(id);
    }

    /**
     * 若方块在「可采集」名单里且原版不可破坏（破坏时间 &lt; 0，如基岩），
     * 返回默认可破坏速度，使其能够被挖掘；否则返回 {@code null}。
     */
    public static Float getBreakableOverride(Block block) {
        Set<Identifier> ids = getIds();
        if (ids.isEmpty()) return null;

        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null || !ids.contains(id)) return null;

        if (block.defaultDestroyTime() >= 0f) return null;
        return DEFAULT_BREAKABLE_SPEED;
    }

    private static Set<Identifier> getIds() {
        String state = RuleCache.getState(RuleType.BLOCK_COLLECTABLE);
        if (state == null) state = "";
        state = state.trim();

        if (state.equals(cachedState)) return cachedIds;

        Set<Identifier> ids = new HashSet<>();
        if (!state.isEmpty()) {
            for (String part : state.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                Identifier id = Identifier.tryParse(trimmed);
                if (id != null) {
                    ids.add(id);
                }
            }
        }

        cachedState = state;
        cachedIds = Collections.unmodifiableSet(ids);
        return cachedIds;
    }
}
