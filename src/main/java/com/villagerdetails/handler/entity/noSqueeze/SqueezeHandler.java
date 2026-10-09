package com.villagerdetails.handler.entity.noSqueeze;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.section.item.NoSqueezeConfig;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.world.entity.Entity;

/**
 * 无挤压规则的过滤器：查这份「不会被挤压」的实体类型名单。
 *
 * <p>名单存在 {@link NoSqueezeConfig} 里（{@code /ec c NoSqueeze <实体id> true|false} 维护），
 * 客户端那份是服务端同步过来的镜像 —— 以前是直接解析规则状态里的 id 列表，
 * 现在规则状态只管开关，名单独立出来了。
 *
 * <p>和其他三条逐条配置的规则（可采集 / 挖掘抗性 / 爆炸抗性）一致：<b>规则没开就不生效</b>。
 * 老存档的名单由 {@code NoSqueezeConfig} 迁移时会顺带把规则打开。
 */
public final class SqueezeHandler {

    private SqueezeHandler() {
    }

    /**
     * 这个实体是不是受保护（不会被挤压）
     */
    public static boolean isProtected(Entity entity) {
        if (entity == null) return false;
        if (!RuleCache.isEnabled(RuleType.VILLAGER_NO_SQUEEZE)) return false;
        return NoSqueezeConfig.INSTANCE.contains(entity.getType());
    }
}
