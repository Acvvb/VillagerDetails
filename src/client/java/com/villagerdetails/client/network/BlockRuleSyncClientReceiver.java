package com.villagerdetails.client.network;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.BlockRuleSyncPayload;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * 客户端接收服务端下发的方块规则配置，写入本地 {@link RuleCache}，
 * 供客户端 {@code getDestroySpeed} 使用，从而让破坏进度动画与服务端一致。
 */
public final class BlockRuleSyncClientReceiver {

    private BlockRuleSyncClientReceiver() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BlockRuleSyncPayload.TYPE, (payload, context) -> {
            String resistance = payload.miningResistance();
            String collectable = payload.collectable();
            context.client().execute(() -> {
                RuleCache.setState(RuleType.BLOCK_MINING_RESISTANCE, resistance);
                RuleCache.setState(RuleType.BLOCK_COLLECTABLE, collectable);
            });
        });
    }
}
