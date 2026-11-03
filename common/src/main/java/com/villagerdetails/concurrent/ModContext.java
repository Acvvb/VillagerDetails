package com.villagerdetails.concurrent;

public class ModContext {

    private static ModThreadPool serverPool;

    // 服务端模块调用此方法注册
    public static void setServerPool(ModThreadPool pool) {
        serverPool = pool;
    }

    public static ModThreadPool getServerPool() {
        return serverPool;
    }
}
