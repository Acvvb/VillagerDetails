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

    private static final String POOL_NAME = "VillagerDetails-ServerThread";

    private static final Logger log = LoggerFactory.getLogger(POOL_NAME);

    /**
     * 队列容量。
     *
     * <p>现在放到 <b>256</b>：在「密集型刷怪塔 / 村民牧场一次性卸载」的并发洪峰下，
     * 256 足够让洪峰被 worker 消化掉，不会轻易打满。
     * <b>不是</b>放宽「不在调用线程执行」那条底线 ——
     * 真被打满时依旧走 {@link #rejectWithWarn()}：抛给调用方兜底。
     */
    private static final int QUEUE_CAPACITY = 1024;

    private static final int CORE_SIZE = 2;
    private static final int MAX_SIZE = 4;

    /**
     * 查询线程池的名字（{@code /ec c TradeSearch} 的异步查询专用）。
     *
     * <p>独立于快照池，见 {@link #executeQuery(Runnable)} 的注释。
     */
    private static final String QUERY_POOL_NAME = "VillagerDetails-Query";

    /**
     * 查询池容量：玩家点一次命令就一个任务，{@value #QUERY_CORE_SIZE}~{@value #QUERY_MAX_SIZE}
     * 条线程、队列 {@value #QUERY_QUEUE_CAPACITY} 已经够用；打满就把「稍后再试」交还调用方。
     */
    private static final int QUERY_CORE_SIZE = 1;
    private static final int QUERY_MAX_SIZE = 2;
    private static final int QUERY_QUEUE_CAPACITY = 16;

    /**
     * 当前服务端线程池。
     *
     * <p>{@code volatile}：写方（{@link #init()} / {@link #shutdown()}）在 {@code ServerThreadManager.class}
     * 锁内，读方 {@link #getPool()} 在锁外读，换存档重建池时需要保证读到的是新引用而不是缓存值。
     * 它只补可见性，<b>不承担互斥</b>（互斥仍由 init / shutdown 的类锁负责）。
     */
    private static volatile ModThreadPool serverPool;

    /**
     * 异步查询池（只跑「只读搜索」，绝不写缓存）。
     *
     * <p>和 {@link #serverPool} 相同的可见性约定：类锁内写、锁外读。
     */
    private static volatile ModThreadPool queryPool;

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
     * <p>典型用法：{@code ServerThreadManager.addPreShutdownHook(RuleConfig::flush)}。
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
     * 建立（或在池子已关闭时重建）服务端线程池 + 查询池。
     *
     * <p>两个池各自独立判断「还活着吗」：换存档时 {@link #shutdown()} 会把引用一起清空，
     * 下一次 {@code SERVER_STARTED} 重新建；只重建其中一个也不会把另一个弄丢。
     */
    public static synchronized void init() {
        if (serverPool == null || serverPool.isShutdown()) {
            serverPool = new ModThreadPool(POOL_NAME, CORE_SIZE, MAX_SIZE, QUEUE_CAPACITY, rejectWithWarn());
            ModContext.setServerPool(serverPool);
        }
        if (queryPool == null || queryPool.isShutdown()) {
            queryPool = new ModThreadPool(QUERY_POOL_NAME, QUERY_CORE_SIZE, QUERY_MAX_SIZE,
                    QUERY_QUEUE_CAPACITY, rejectWithWarn());
        }
        hookLifecycle();
    }

    /**
     * 队列满 / 池已关闭时的拒绝策略：<b>先 WARN，再抛出去</b>。
     *
     * <p><b>为什么不再「退化为调用线程同步执行」</b>：本池跑的是「区块卸载时抓到的村民交易快照」
     * 这类解析活，提交方正是服务端主线程的 tick / 区块卸载回调。以前队列一满，任务就当场在
     * tick 线程里做完，直接掉 tick —— 恰好违背了「主线程只抓快照、重活交给池子」的设计目标。
     * 所以这里保持标准的拒绝语义，把「怎么办」交给调用方：
     * <ul>
     *     <li>{@link #execute(Runnable)} 返回 {@code false} —— 调用方据此跳过本次任务
     *         （{@code VillagerTradePipeline} 丢掉这份可重建的快照，并且只提示一次）；</li>
     *     <li>{@link #execute(Runnable, Runnable)} 走拒绝回调，由调用方自己兜底。</li>
     * </ul>
     *
     * <p>「卡一下主线程」听起来比「丢任务」安全，但在这里不成立：解析一份快照是毫秒级，
     * 而丢掉的快照是可重建的缓存数据（下次区块卸载 / 兜底扫描会重新抓）。
     *
     * <p>注意：<b>配置落盘不进这个池</b>（那是 {@code AsyncConfigWriter} 的专职单写者线程），
     * 所以这里的拒绝不会影响任何配置的持久化。
     */
    private static RejectedExecutionHandler rejectWithWarn() {
        return (_, executor) -> {
            log.warn("服务端异步队列已满（容量 {}，排队 {}），任务被拒绝（交由调用方兜底）",
                    QUEUE_CAPACITY, executor.getQueue().size());
            throw new RejectedExecutionException("服务端异步队列已满（容量 " + QUEUE_CAPACITY + "）");
        };
    }

    /**
     * 挂生命周期钩子（只在第一次 {@code init} 时挂一次）。
     *
     * <p><b>不加锁</b>：仅由 {@link #init()} 在类锁内调用，故无需额外同步 ——
     * 这里再写 {@code synchronized} 只是对同一把类锁的重入获取。
     */
    private static void hookLifecycle() {
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

        ModThreadPool main;
        ModThreadPool query;
        synchronized (ServerThreadManager.class) {
            main = serverPool;
            query = queryPool;
            serverPool = null;
            queryPool = null;
            ModContext.setServerPool(null);
        }
        // 查询池<b>先</b>关：查询是一次性的（玩家重新敲一遍就好），丢了不心疼，
        // 也绝不该为它多等一秒；快照池后关，它要等队列里的快照解析完（最多 1 秒）。
        if (query != null) {
            query.shutdown();
        }
        if (main != null) {
            main.shutdown();
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
     * 提交一个「只读查询」任务（{@code /ec c TradeSearch} 用）。
     *
     * <p><b>为什么必须和快照池分开</b>：查询是玩家随时能触发的（还可能被狂刷），而快照任务是
     * 区块卸载时的数据来源。共用一个队列时，玩家刷几次查询就能吃掉容量，把「村民交易快照」
     * 挤成 {@code false} —— 那是会丢数据的；而查询被拒只是让玩家重敲一遍。
     * 所以独立线程池、独立队列（{@value #QUERY_QUEUE_CAPACITY}），线程数也很小，
     * 打满时按标准拒绝语义把「稍后再试」交还调用方。
     *
     * @return true = 已入队；false = 池未初始化 / 已关闭（调用方负责给玩家提示）
     */
    public static boolean executeQuery(Runnable task) {
        ModThreadPool pool = getQueryPool();
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

    private static ModThreadPool getQueryPool() {
        ModThreadPool pool = queryPool;
        if (pool == null || pool.isShutdown()) {
            return null;
        }
        return pool;
    }

}