package com.villagerdetails.handler.explosion;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Explosion;

/**
 * TNT 不炸毁掉落物规则：判断某次爆炸是否需要保护给定的实体（掉落物）。
 * <p>
 * 仅当规则开启、实体为掉落物（{@link ItemEntity}）且爆炸来源是 TNT（{@link PrimedTnt}）时返回 true。
 */
public final class TntNoDestroyDropsHandler {

    private TntNoDestroyDropsHandler() {
    }

    /**
     * 该实体是否应在此次爆炸中免于受伤（从而不被炸毁）。
     */
    public static boolean shouldProtectItemDrops(Explosion explosion, Entity entity) {
        if (!RuleCache.isEnabled(RuleType.TNT_NO_DESTROY_DROPS)) return false;
        if (!(entity instanceof ItemEntity)) return false;
        return explosion.getDirectSourceEntity() instanceof PrimedTnt;
    }
}
