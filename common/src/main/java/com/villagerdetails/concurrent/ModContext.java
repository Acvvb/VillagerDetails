package com.villagerdetails.concurrent;

public class ModContext {
    private static ModThreadPool serverPool;
    private static ModThreadPool clientPool;

    // 服务端模块调用此方法注册
    public static void setServerPool(ModThreadPool pool) {
        serverPool = pool;
    }

    // 客户端模块调用此方法注册
    public static void setClientPool(ModThreadPool pool) {
        clientPool = pool;
    }

    public static ModThreadPool getServerPool() {
        return serverPool;
    }

    public static ModThreadPool getClientPool() {
        return clientPool;
    }
}