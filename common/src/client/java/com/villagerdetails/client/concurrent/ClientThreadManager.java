package com.villagerdetails.client.concurrent;

import com.villagerdetails.concurrent.ModContext;
import com.villagerdetails.concurrent.ModThreadPool;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

@Environment(EnvType.CLIENT)
public class ClientThreadManager {
    private static ModThreadPool clientPool;
    private static boolean isInitialized = false;

    public static synchronized void init() {
        // 1. 防重判断：如果已经初始化，且池子没死，直接跳过
        if (isInitialized && clientPool != null && !clientPool.isShutdown()) {
            return;
        }

        // 客户端池：核心 1，最大 2，队列 500（减轻对渲染主线程的影响）
        clientPool = new ModThreadPool("VillagerDetails-Client", 1, 2, 500);
        ModContext.setClientPool(clientPool);
        isInitialized = true;

        // 客户端完全退出时，安全关闭线程池
        ClientLifecycleEvents.CLIENT_STOPPING.register(_ -> {
            if (clientPool != null) {
                clientPool.shutdown();
                clientPool = null; // 2. 关键：置空，允许重新初始化
            }
            ModContext.setClientPool(null); // 同时清理上下文引用
            isInitialized = false;          // 3. 重置标志位
        });
    }
}