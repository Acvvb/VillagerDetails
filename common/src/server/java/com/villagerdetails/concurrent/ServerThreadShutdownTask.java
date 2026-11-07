package com.villagerdetails.concurrent;

import com.villagerdetails.config.impl.base.AbstractWorldConfig;
import com.villagerdetails.util.io.AsyncConfigManager;

public class ServerThreadShutdownTask {

    public static void register() {
        ServerThreadManager.init();
        task();
    }

    private static void task() {
        ServerThreadManager.addPreShutdownHook(() -> {
            //写配置
            AbstractWorldConfig.flushAll();
            //清空队列并关闭服务器
            AsyncConfigManager.flushAndShutdown(3000L);
        });
    }


}
