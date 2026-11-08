package com.villagerdetails.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;

/**
 * 服务端异步快照线程池（全服唯一）。
 *
 * <p>专门处理「区块卸载时抓到的村民交易快照」等重活。
 * 容量较大（1024），因为丢快照会导致数据丢失，必须尽量让洪峰被消化。
 */
public final class SnapshotThreadPool {

    private static final Logger log = LoggerFactory.getLogger("VillagerDetails-SnapshotPool");

    private static final String POOL_NAME = "VillagerDetails-SnapshotThread";
    private static final int QUEUE_CAPACITY = 1024;
    private static final int CORE_SIZE = 2;
    private static final int MAX_SIZE = 4;

    private static volatile ModThreadPool pool;

    private SnapshotThreadPool() {
    }

    /**
     * 初始化快照池。
     *
     * @return 当前（新建或已存在的）快照池，供 {@code ServerThreadManager} 注册进 {@code ModContext}
     */
    public static synchronized ModThreadPool init() {
        if (pool == null || pool.isShutdown()) {
            pool = new ModThreadPool(POOL_NAME, CORE_SIZE, MAX_SIZE, QUEUE_CAPACITY, rejectWithWarn());
        }
        return pool;
    }

    /**
     * 关闭快照池：最多等 1 秒让队列里的快照解析完；超时后剩余快照任务会被
     * {@link ModThreadPool#shutdown()} 拉回调用线程（停服时 = 主线程）继续执行，
     * 保证交易索引不会因为关池而缺数据。
     */
    public static void shutdown() {
        ModThreadPool p;
        synchronized (SnapshotThreadPool.class) {
            p = pool;
            pool = null;
        }
        if (p != null) {
            p.shutdown();
        }
    }

    /**
     * 提交一个快照任务。
     *
     * @return true 表示成功交给线程池，false 表示被拒绝（业务方可根据此处理）
     */
    public static boolean execute(Runnable task) {
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

    /**
     * 拒绝策略：满了直接打 WARN 并抛异常。
     * 因为丢快照会丢数据，调用方必须知道被拒了（返回 false）。
     */
    private static RejectedExecutionHandler rejectWithWarn() {
        return (_, executor) -> {
            log.warn("快照队列已满（容量 {}，排队 {}），任务被拒绝（交由调用方兜底）",
                    QUEUE_CAPACITY, executor.getQueue().size());
            throw new RejectedExecutionException("快照队列已满（容量 " + QUEUE_CAPACITY + "）");
        };
    }
}