package com.villagerdetails.concurrent;

import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;

/**
 * 服务端异步线程池的生命周期管理（<b>全服唯一</b>的那个池子）。
 *
 * <p>{@link #init()} 由服务端入口调用；同时自己挂两个生命周期钩子，解决「单人反复换存档」：
 * <ul>
 *     <li>{@code SERVER_STARTED} → {@link #init()}：上一个世界停下时池子已经被关掉，
 *         新世界启动时重新建一个（重复调用是空操作）；</li>
 *     <li>{@code SERVER_STOPPING} → 安全关闭（<b>先跑 {@link #addPreShutdownHook} 登记过的收尾钩子，
 *         再</b>等待队列最多 1 秒，然后超时强杀），并把 {@link ModContext} 里的引用一起清掉，
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
     * <p>现在放到 <b>256</b>：在「密集型刷怪塔 / 村民牧场一次性卸载」的并发洪峰下，
     * 256 足够让洪峰被 worker 消化掉，不会轻易打满。
     * <b>不是</b>放宽「不在调用线程执行」那条底线 ——
     * 真被打满时依旧走 {@link #rejectWithWarn()}：抛给调用方兜底。
     */
    private static final int QUEUE_CAPACITY = 256;

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
        serverPool = new ModThreadPool(POOL_NAME, CORE_SIZE, MAX_SIZE, QUEUE_CAPACITY, rejectWithWarn());
        ModContext.setServerPool(serverPool);
        hookLifecycle();
    }

    /**
     * 队列满 / 池已关闭时的拒绝策略：<b>先 WARN，再抛出去</b>。
     *
     * <p><b>为什么不再「退化为调用线程同步执行」</b>：本池最重的任务是村民交易索引的落盘
     * （整份 JSON 序列化 + 原子写文件），而它的提交方正是服务端主线程的 tick。以前队列一满，
     * 这个写盘任务就当场在 tick 线程里做完，直接掉 tick —— 恰好违背了它自己「主线程永远不做 I/O」
     * 的设计目标。所以这里改回标准的拒绝语义，把「怎么办」交给调用方：
     * <ul>
     *     <li>{@code ModThreadPool#submit} 把 future 以异常收尾 —— {@code VillagerTradeStorage}
     *         据此<b>保留脏标记</b>，下个 tick 的定时任务再试；</li>
     *     <li>{@code ModThreadPool#execute} 记一条 WARN 后丢弃 —— 交易快照丢了不要紧，
     *         下次区块卸载会重新抓。</li>
     * </ul>
     *
     * <p>「卡一下主线程」听起来比「丢任务」安全，但在这里不成立：整份索引写盘是毫秒~十毫秒级，
     * 而丢掉的快照是可重建的缓存数据。
     */
    private static RejectedExecutionHandler rejectWithWarn() {
        return (_, executor) -> {
            log.warn("服务端异步队列已满（容量 {}，排队 {}），任务被拒绝（交由调用方兜底）",
                    QUEUE_CAPACITY, executor.getQueue().size());
            throw new RejectedExecutionException("服务端异步队列已满（容量 " + QUEUE_CAPACITY + "）");
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
     * <p>{@code pool.shutdown()} 最多会等 1 秒，<b>不在类锁里等</b>：
     * 否则这 1 秒内任何 {@link #init()} 调用都会被卡住，等锁释放后又把一个新池建起来。
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
    /**
     * 提交一个异步任务。像调用普通 void 方法一样。
     *
     * @return true 表示成功交给线程池，false 表示被拒绝（业务方可根据此处理）
     */
    public static boolean execute(Runnable task) {
        ModThreadPool pool = getPool();
        if (pool == null) {
            return false;
        }
        return pool.execute(task);
    }

    /**
     * 提交一个异步任务，并提供拒绝后的回调。
     */
    public static void execute(Runnable task, Runnable onRejected) {
        if (!execute(task)) {
            onRejected.run();
        }
    }

    private static ModThreadPool getPool() {
        ModThreadPool pool = serverPool;
        if (pool == null || pool.isShutdown()) {
            return null;
        }
        return pool;
    }

}