package com.villagerdetails.client.feature.villagerbed;

import com.villagerdetails.network.VillagerBedPayload;
import com.villagerdetails.network.VillagerPacket;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.villager.Villager;

public class VillagerBedClientReceiver {

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(VillagerBedPayload.TYPE, (payload, context) -> {
            VillagerPacket data = payload.data();
            int villagerId = data.villagerId();
            // 这里就把 Optional 拆开（别把它当形参传出去）；空 = 这个村民没有床
            BlockPos bedPos = data.bedPos().orElse(null);

            // 切换到客户端主线程执行（网络包回调在工作线程，和实体/渲染相关的状态统一在主线程改）
            context.client().execute(() -> VillagerBedHelper.updateBedPosition(villagerId, bedPos));
        });

        // 实体卸载（走出视距 / 死亡 / 换维度）时清掉对应缓存，
        // 避免实体ID被复用后，给错误的实体连出一条线
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, _) -> {
            if (entity instanceof Villager villager) {
                VillagerBedHelper.removeBedPosition(villager.getId());
            }
        });

        // 断开连接时整体清空
        ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> VillagerBedHelper.clear());
    }
}
