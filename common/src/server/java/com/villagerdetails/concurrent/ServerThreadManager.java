package com.villagerdetails.concurrent;

import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionHandler;

/**
 * 服务端异步线程池的生命周期管理（<b>全服唯一</b>的那个池子）。
 *
 * <p>{@link #init()} 由服务端入口调用；同时自己挂两个生命周期钩子，解决「单人反复换存档」：
 * <ul>
 *     <li>{@code SERVER_STARTED} → {@link #init()}：上一个世界停下时池子已经被关掉，
 *         新世界启动时重新建一个（重复调用是空操作）；</li>
 *     <li>{@code SERVER_STOPPING} → 安全关闭（<b>先跑 {@link #addPreShutdownHook} 登记过的收尾钩子，
 *         再</b>等待队列最多 3 秒，然后 {@code shutdownNow}），并把 {@link ModContext} 里的引用一起清掉，
 *         避免静态字段攥着已停服的线程。</li>
 * </ul>
 *
 * <p>钩子只用 {@code lifecycleHooked} 挂一次：以前每次 {@code init()} 都注册一遍
 * {@code SERVER_STOPPING}，换几次存档就会堆几个重复的停服回调。
 *
 * <p><b>为什么需要 pre-shutdown 钩子</b>：停服收尾里有一串必须跑在「关池之前」的动作
 * （配置序列化入队 → 异步写线程排干 → 才允许关池），而本类的 {@code SERVER_STOPPING} 回调
 * 注册得比 {@code ServerStoppingListener} 早。直接按事件顺序执行就会变成「先关池、再收尾」，
 * 队列里没写完的任务会被 {@code shutdownNow} 丢掉。
 * 所以收尾逻辑必须显式挂在关池<b>之前</b>，不能依赖事件注册顺序。
 */
@Environment(EnvType.SERVER)
public final class ServerThreadManager {

    private static final Logger log = LoggerFactory.getLogger("VillagerDetails-Server");

    private static final String POOL_NAME = "VillagerDetails-Server";

    /**
     * 队列容量。
     *
     * <p>原来是 1000：配合 {@code LinkedBlockingQueue}，队列没满就永远不会超过核心线程数，
     * 于是 {@code maxSize=4} 形同虚设、拒绝策略也几乎触发不到（真触发了就是静默丢任务）。
     * 现在压到 64，池子才真的能在积压时从 2 扩到 4；满了也有下面那条保底策略兜着。
     */
    private static final int QUEUE_CAPACITY = 64;

    private static final int CORE_SIZE = 2;
    private static final int MAX_SIZE = 4;

    private static ModThreadPool serverPool;

    /**
     * 生命周期钩子是否已经挂过（只在第一次 init 时挂）。
     */
    private static boolean lifecycleHooked;

    /**
     * 关池<b>之前</b>要跑的收尾动作（配置刷盘等），按登记顺序执行。
     *
     * <p>用 {@code CopyOnWriteArrayList}：登记发生在启动期，读取发生在停服，几乎不会真并发，
     * 但停服回调里遍历期间有任何登记动作也不会炸。
     */
    private static final List<Runnable> PRE_SHUTDOWN_HOOKS = new CopyOnWriteArrayList<>();

    private ServerThreadManager() {
    }

    /**
     * 登记一个「关池之前」的收尾动作。
     *
     * <p>典型用法：{@code ServerThreadManager.addPreShutdownHook(UnifiedConfigFile::flush)}。
     * 重复登记同一个实例会被忽略（避免换存档时越堆越多）。
     *
     * <p><b>执行顺序 = 登记顺序</b>：所有「把内容交给异步写线程」的动作必须排在
     * {@code AsyncConfigManager.flushAndShutdown} 之前，否则排干之后才入队的内容没人等。
     *
     * <p>钩子抛异常只记日志、不中断后面的钩子，也不阻止关池。
     */
    public static void addPreShutdownHook(Runnable hook) {
        if (hook == null || PRE_SHUTDOWN_HOOKS.contains(hook)) {
            return;
        }
        PRE_SHUTDOWN_HOOKS.add(hook);
    }

    /**
     * 建立（或在池子已关闭时重建）服务端线程池。
     */
    public static synchronized void init() {
        // 池子还活着：什么都不用做
        if (serverPool != null && !serverPool.isShutdown()) {
            return;
        }

        // 服务端池：核心 2，最大 4，队列 64，满了退化为「调用线程同步执行」
        serverPool = new ModThreadPool(POOL_NAME, CORE_SIZE, MAX_SIZE, QUEUE_CAPACITY, callerRunsWithWarn());
        ModContext.setServerPool(serverPool);
        hookLifecycle();
    }

    /**
     * 队列满 / 池已关闭时的保底策略：<b>不丢任务</b>，退化成在提交线程里同步执行。
     *
     * <p>为什么不用 {@code AbortPolicy}（抛异常）：业务侧（区块卸载的村民交易快照）不该被迫
     * 处理拒绝异常；为什么不用「丢弃 + WARN」：配置落盘一旦丢事件就是丢数据。
     * 卡一下主线程远比丢一条配置便宜。
     *
     * <p>注意 {@code task.run()} 跑的是包装后的任务，所以异常已经被内层 try/catch 兜住；
     * 这里再包一层是为了不让 {@code Error} 冒到 {@code ThreadPoolExecutor} 的提交路径上。
     */
    private static RejectedExecutionHandler callerRunsWithWarn() {
        return (task, executor) -> {
            log.warn("服务端异步队列已满（容量 {}，排队 {}），任务改为在调用线程同步执行",
                    QUEUE_CAPACITY, executor.getQueue().size());
            try {
                task.run();
            } catch (Throwable t) {
                log.error("同步执行被拒绝的任务时异常", t);
            }
        };
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
     * 停服：<b>先刷盘</b>（此时池子和存档都还在，任何异步写都还来得及跑完），再摘引用、再关池。
     *
     * <p>{@code pool.shutdown()} 最多会等 3 秒，<b>不在类锁里等</b>：
     * 否则这 3 秒内任何 {@link #init()} 调用都会被卡住，等锁释放后又把一个新池建起来。
     */
    private static void shutdown() {
        // 顺序关键：收尾钩子（配置刷盘）必须在池子还活着的时候跑
        runPreShutdownHooks();

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

    /**
     * 依次执行收尾钩子；单个失败只记日志，不连累其它钩子，也不阻止关池。
     */
    private static void runPreShutdownHooks() {
        for (Runnable hook : PRE_SHUTDOWN_HOOKS) {
            try {
                hook.run();
            } catch (Throwable t) {
                log.error("停服收尾钩子执行失败（已跳过，继续关池）", t);
            }
        }
    }
}
