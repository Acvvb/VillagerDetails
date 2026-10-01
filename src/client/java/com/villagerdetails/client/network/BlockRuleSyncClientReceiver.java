package com.villagerdetails.client.network;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.BlockCollectableConfig;
import com.villagerdetails.config.impl.BlockMiningResistanceConfig;
import com.villagerdetails.network.BlockRuleSyncPayload;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * 客户端接收服务端下发的方块规则配置，写入本地缓存，
 * 供客户端 {@code getDestroyProgress} 使用，从而让破坏进度动画与服务端一致。
 */
public final class BlockRuleSyncClientReceiver {

    private BlockRuleSyncClientReceiver() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BlockRuleSyncPayload.TYPE, (payload, context) -> {
            boolean resistanceEnabled = payload.miningResistanceEnabled();
            boolean collectableEnabled = payload.collectableEnabled();
            String resistance = payload.miningResistance();
            String collectable = payload.collectable();

            context.client().execute(() -> {
                RuleCache.setState(RuleType.BLOCK_MINING_RESISTANCE, Boolean.toString(resistanceEnabled));
                RuleCache.setState(RuleType.BLOCK_COLLECTABLE, Boolean.toString(collectableEnabled));
                BlockMiningResistanceConfig.applyFromSyncString(resistance);
                BlockCollectableConfig.applyFromSyncString(collectable);
            });
        });
    }
}
