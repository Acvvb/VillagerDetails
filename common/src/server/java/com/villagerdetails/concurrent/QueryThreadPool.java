package com.villagerdetails.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;

/**
 * 服务端异步查询线程池（全服唯一）。
 *
 * <p>专门处理 /ec c TradeSearch 的只读搜索。
 * 容量极小（16），因为玩家刷屏把查询池打满只是报错，不影响快照数据安全。
 */
public final class QueryThreadPool {

    private static final Logger log = LoggerFactory.getLogger("VillagerDetails-QueryPool");

    private static final String POOL_NAME = "VillagerDetails-QueryThread";
    private static final int QUEUE_CAPACITY = 16;
    private static final int CORE_SIZE = 1;
    private static final int MAX_SIZE = 2;

    private static volatile ModThreadPool pool;

    private QueryThreadPool() {
    }

    /**
     * 初始化查询池。
     */
    public static synchronized void init() {
        if (pool == null || pool.isShutdown()) {
            pool = new ModThreadPool(POOL_NAME, CORE_SIZE, MAX_SIZE, QUEUE_CAPACITY, rejectWithWarn());
        }
    }

    /**
     * 关闭查询池：走 {@link ModThreadPool#shutdown()}，最多等 1 秒把已入队的查询跑完；
     * 超时后剩余任务会被拉回调用线程（停服时 = 主线程）继续执行完。
     *
     * <p>查询是一次性的（玩家重敲一次命令就有），所以这里<b>不需要</b>额外的兜底逻辑；
     * 剩余任务被拉回执行只是让停服多花一点时间，不会阻塞正常游戏。
     */
    public static void shutdown() {
        ModThreadPool p;
        synchronized (QueryThreadPool.class) {
            p = pool;
            pool = null;
        }
        if (p != null) {
            p.shutdown();
        }
    }

    /**
     * 提交一个查询任务。
     *
     * @return true = 已入队；false = 池未初始化 / 已关闭（调用方负责给玩家提示）
     */
    public static boolean executeQuery(Runnable task) {
        ModThreadPool p = getPool();
        if (p == null) {
            return false;
        }
        return p.execute(task);
    }

    private static ModThreadPool getPool() {
        ModThreadPool p = pool;
        if (p == null || p.isShutdown()) {
            return null;
        }
        return p;
    }

    private static RejectedExecutionHandler rejectWithWarn() {
        return (_, executor) -> {
            log.warn("查询队列已满（容量 {}，排队 {}），任务被拒绝（交由调用方兜底）",
                    QUEUE_CAPACITY, executor.getQueue().size());
            throw new RejectedExecutionException("查询队列已满（容量 " + QUEUE_CAPACITY + "）");
        };
    }
}