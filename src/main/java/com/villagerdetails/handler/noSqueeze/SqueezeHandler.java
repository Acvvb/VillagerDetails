package com.villagerdetails.handler.noSqueeze;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 无挤压规则的过滤器：解析 state 里的实体 ID 列表，判断某实体是否受保护。
 */
public final class SqueezeHandler {

    private SqueezeHandler() {}

    /** 缓存：state 字符串 → 解析后的 ID 集合 */
    private static volatile String cachedState = "";
    private static volatile Set<Identifier> cachedIds = Set.of();

    /** 实体是否在"无挤压"名单里 */
    public static boolean isProtected(Entity entity) {
        if (entity == null) return false;
        Set<Identifier> ids = getIds();
        if (ids.isEmpty()) return false;

        Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return ids.contains(entityId);
    }

    /** 直接判断字符串 ID（用于列表预检） */
    public static boolean isInList(String entityIdStr) {
        if (entityIdStr == null || entityIdStr.isBlank()) return false;
        Identifier id = Identifier.tryParse(entityIdStr.trim());
        if (id == null) return false;
        return getIds().contains(id);
    }

    private static Set<Identifier> getIds() {
        String state = RuleCache.getState(RuleType.VILLAGER_NO_SQUEEZE);
        if (state == null) state = "";
        state = state.trim();

        // 缓存命中
        if (state.equals(cachedState)) return cachedIds;

        // 重新解析
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

    /** 供 RuleCallbacks 调用——手动清空缓存，下次读取时重新解析 */
    public static void invalidate() {
        cachedState = "\u0000";   // 设为不可能的值，强制下次重新解析
    }
}