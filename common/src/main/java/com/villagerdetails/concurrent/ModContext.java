package com.villagerdetails.concurrent;

public class ModContext {

    /**
     * 服务端线程池引用。
     *
     * <p>{@code volatile}：写方是 {@code ServerThreadManager} 的初始化 / 停服（在类锁内），
     * 读方（{@code VillagerTradePipeline#submit}）在锁外读。字段本身不能为 {@code final}，
     * 又要在「换存档重建池」时整体替换，所以用可见性而不是锁来保证读到的不是旧引用。
     */
    private static volatile ModThreadPool serverPool;

    // 服务端模块调用此方法注册
    public static void setServerPool(ModThreadPool pool) {
        serverPool = pool;
    }

    public static ModThreadPool getServerPool() {
        return serverPool;
    }
}
