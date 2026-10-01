package com.villagerdetails.handler.block.miningResistance;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 挖掘抗性规则的过滤器：解析 state 里的 {@code 方块id=抗性} 键值对，
 * 返回某个方块被覆盖后的破坏时间（挖掘抗性）。
 */
public final class MiningResistanceHandler {

    private MiningResistanceHandler() {
    }

    /** 缓存：state 字符串 → 解析后的 id→抗性 映射 */
    private static volatile String cachedState = "";
    private static volatile Map<Identifier, Float> cachedMap = Map.of();

    /**
     * 返回该方块的抗性覆盖值；未配置时返回 {@code null}，调用方走原版逻辑。
     */
    public static Float getOverride(Block block) {
        Map<Identifier, Float> map = getMap();
        if (map.isEmpty()) return null;

        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) return null;

        return map.get(id);
    }

    private static Map<Identifier, Float> getMap() {
        String state = RuleCache.getState(RuleType.BLOCK_MINING_RESISTANCE);
        if (state == null) state = "";
        state = state.trim();

        // 缓存命中
        if (state.equals(cachedState)) return cachedMap;

        Map<Identifier, Float> map = new HashMap<>();
        if (!state.isEmpty()) {
            for (String part : state.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;

                int eq = trimmed.indexOf('=');
                if (eq <= 0 || eq == trimmed.length() - 1) continue;

                Identifier id = Identifier.tryParse(trimmed.substring(0, eq).trim());
                if (id == null) continue;

                try {
                    float value = Float.parseFloat(trimmed.substring(eq + 1).trim());
                    map.put(id, value);
                } catch (NumberFormatException ignored) {
                    // 非法数值直接跳过
                }
            }
        }

        cachedState = state;
        cachedMap = Collections.unmodifiableMap(map);
        return cachedMap;
    }
}
