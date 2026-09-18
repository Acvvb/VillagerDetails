package com.villagerdetails.client.network;

import com.villagerdetails.client.helper.VillagerBedHelper;
import com.villagerdetails.network.VillagerBedPayload;
import com.villagerdetails.network.VillagerPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Optional;

public class VillagerBedClientReceiver {

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(VillagerBedPayload.TYPE, (payload, context) -> {
            VillagerPacket data = payload.data();
            int villagerId = data.villagerId();
            Optional<BlockPos> bedPos = data.bedPos();

            // 切换到客户端主线程执行（网络包回调在工作线程，操作实体必须在主线程）
            context.client().execute(() -> {
                VillagerBedHelper.updateBedPosition(villagerId, bedPos);

                // 可选：如果村民实体已在客户端加载，强制刷新渲染
                Entity entity = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getEntity(villagerId) : null;
                if (entity instanceof Villager) {
                    entity.refreshDimensions();  // 触发重新渲染
                }
            });
        });
    }
}