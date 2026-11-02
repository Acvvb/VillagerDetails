package com.villagerdetails.concurrent;

import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * 服务端异步线程池的生命周期管理（<b>全服唯一</b>的那个池子）。
 *
 * <p>{@link #init()} 由服务端入口调用；同时自己挂两个生命周期钩子，解决「单人反复换存档」：
 * <ul>
 *     <li>{@code SERVER_STARTED} → {@link #init()}：上一个世界停下时池子已经被关掉，
 *         新世界启动时重新建一个（重复调用是空操作）；</li>
 *     <li>{@code SERVER_STOPPING} → 安全关闭（等待队列最多 3 秒，然后 {@code shutdownNow}），
 *         并把 {@link ModContext} 里的引用一起清掉，避免静态字段攥着已停服的线程。</li>
 * </ul>
 *
 * <p>钩子只用 {@code lifecycleHooked} 挂一次：以前每次 {@code init()} 都注册一遍
 * {@code SERVER_STOPPING}，换几次存档就会堆几个重复的停服回调。
 */
@Environment(EnvType.SERVER)
public final class ServerThreadManager {

    private static ModThreadPool serverPool;

    /**
     * 生命周期钩子是否已经挂过（只在第一次 init 时挂）。
     */
    private static boolean lifecycleHooked;

    private ServerThreadManager() {
    }

    /**
     * 建立（或在池子已关闭时重建）服务端线程池。
     */
    public static synchronized void init() {
        // 池子还活着：什么都不用做
        if (serverPool != null && !serverPool.isShutdown()) {
            return;
        }

        // 服务端池：核心 2，最大 4，队列 1000
        serverPool = new ModThreadPool("VillagerDetails-Server", 2, 4, 1000);
        ModContext.setServerPool(serverPool);
        hookLifecycle();
    }

    private static synchronized void hookLifecycle() {
        if (lifecycleHooked) {
            return;
        }
        lifecycleHooked = true;

        // 单人换存档：SERVER_STOPPING 里池子被关掉，下一个世界启动时重新建
        ServerLifecycleEvents.SERVER_STARTED.register(_ -> init());
        ServerLifecycleEvents.SERVER_STOPPING.register(_ -> shutdown());
    }

    /**
     * 停服：先把引用摘掉再关池子，避免关池期间还有代码拿到一个正在关闭的池。
     *
     * <p>{@code pool.shutdown()} 最多会等 3 秒，<b>不在类锁里等</b>：
     * 否则这 3 秒内任何 {@link #init()} 调用都会被卡住，等锁释放后又把一个新池建起来。
     */
    private static void shutdown() {
        ModThreadPool pool;
        synchronized (ServerThreadManager.class) {
            pool = serverPool;
            serverPool = null;
            ModContext.setServerPool(null);
        }
        if (pool != null) {
            pool.shutdown();
        }
    }
}
