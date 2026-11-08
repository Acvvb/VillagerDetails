package com.villagerdetails.concurrent;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 服务端线程池的生命周期管理。
 *
 * <p>只负责调度 {@link SnapshotThreadPool} 和 {@link QueryThreadPool} 的初始化与关闭，
 * 并提供 pre-shutdown 钩子机制，保证配置落盘在关池之前完成。
 */
@Environment(EnvType.SERVER)
public final class ServerThreadManager {

    private static final Logger log = LoggerFactory.getLogger("VillagerDetails-ThreadManager");

    private static boolean lifecycleHooked;

    /**
     * 关池<b>之前</b>要跑的收尾动作（配置刷盘等），按登记顺序执行。
     */
    private static final List<Runnable> PRE_SHUTDOWN_HOOKS = new CopyOnWriteArrayList<>();

    private ServerThreadManager() {
    }

    /**
     * 登记一个「关池之前」的收尾动作。
     *
     * <p><b>执行顺序 = 登记顺序</b>：所有「把内容交给异步写线程」的动作必须排在
     * {@code AsyncConfigManager.flushAndShutdown} 之前。
     */
    public static void addPreShutdownHook(Runnable hook) {
        if (hook == null || PRE_SHUTDOWN_HOOKS.contains(hook)) {
            return;
        }
        PRE_SHUTDOWN_HOOKS.add(hook);
    }

    /**
     * 建立（或重建）所有服务端线程池。
     *
     * <p>快照池建好后必须注册进 {@link ModContext}：业务侧（{@code VillagerTradePipeline}）
     * 是通过 {@code ModContext.getServerPool()} 拿池的，漏了这一步所有快照任务都会被静默丢弃。
     */
    public static synchronized void init() {
        ModThreadPool serverPool = SnapshotThreadPool.init();
        QueryThreadPool.init();
        ModContext.setServerPool(serverPool);
        hookLifecycle();
    }

    private static void hookLifecycle() {
        if (lifecycleHooked) {
            return;
        }
        lifecycleHooked = true;

        ServerLifecycleEvents.SERVER_STARTED.register(_ -> init());
        ServerLifecycleEvents.SERVER_STOPPING.register(_ -> shutdown());
    }

    /**
     * 停服：<b>先跑收尾钩子</b>（此时池子还活着，配置落盘入队），再按顺序关池。
     *
     * <p>关池顺序固定为「查询池 → 快照池」，与两者的数据价值一致：
     * <ul>
     *     <li><b>查询池先关</b>：最多等 1 秒把已入队的查询跑完；没跑完的会由
     *         {@link ModThreadPool#shutdown()} 拉回当前线程执行（查询量本身很小，可接受）；</li>
     *     <li><b>快照池后关</b>：同样最多等 1 秒，超时后<b>把剩余快照任务拉回当前线程执行</b>
     *         —— 快照丢掉会导致交易索引缺数据、要等下一次区块卸载才补得回来。
     *         因为这一步可能变慢，所以必须排在所有收尾动作之后。</li>
     * </ul>
     */
    private static void shutdown() {
        // 1. 跑收尾钩子（配置刷盘等）
        runPreShutdownHooks();

        // 2. 查询池先关（最多等 1 秒；剩余任务拉回当前线程，查询是一次性的，慢一点可接受）
        QueryThreadPool.shutdown();

        // 3. 快照池后关（可能把剩余快照拉回当前线程，慢，所以放最后）
        SnapshotThreadPool.shutdown();
    }

    private static void runPreShutdownHooks() {
        for (Runnable hook : PRE_SHUTDOWN_HOOKS) {
            try {
                hook.run();
            } catch (Throwable t) {
                log.error("停服收尾钩子执行失败（已跳过，继续关池）", t);
            }
        }
    }

    /**
     * 提交一个快照任务（兼容旧调用）。
     */
    public static boolean execute(Runnable task) {
        return SnapshotThreadPool.execute(task);
    }

    /**
     * 提交一个查询任务。
     */
    public static boolean executeQuery(Runnable task) {
        return QueryThreadPool.executeQuery(task);
    }

    /**
     * 提交一个异步任务，并提供拒绝后的回调。
     */
    public static void execute(Runnable task, Runnable onRejected) {
        if (!execute(task)) {
            onRejected.run();
        }
    }
}