package com.villagerdetails.concurrent;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ModThreadPool {
    private final Logger logger;
    private final ThreadPoolExecutor executor;

    /**
     * 默认拒绝策略：<b>直接丢弃</b>（WARN 由下面的包装日志统一打出）。
     *
     * <p>只适合「丢一条不影响正确性」的任务（村民交易快照这类缓存重建型任务）。
     * 需要「调用方自己决定怎么兜」的池子（服务端那个）必须在构造时传入更强的策略 ——
     * 见 {@code ServerThreadManager} 里用的「先 WARN 再抛出」，<b>不会</b>把任务退回调用线程执行。
     */
    private static final RejectedExecutionHandler DISCARD_WITH_WARN = (_, _) -> {
    };

    // 通过构造器传入不同的参数，让两端各自定制容量
    public ModThreadPool(String poolName, int coreSize, int maxSize, int queueCapacity) {
        this(poolName, coreSize, maxSize, queueCapacity, DISCARD_WITH_WARN);
    }

    /**
     * @param rejectedHandler 队列满 / 池已关闭时的兜底策略。
     *
     *                        <p><b>可以传会抛 {@link RejectedExecutionException} 的策略</b>：
     *                        {@link #submit} 会把它转成「future 以异常收尾」，{@link #execute}
     *                        则会记一条 WARN 后丢弃 —— 两种入口都不会把异常漏给业务调用方。
     *                        （早期注释写着「不允许传 AbortPolicy」，那是 {@code execute} 还没
     *                        兜住异常时的约定，现在已不成立。）
     */
    public ModThreadPool(String poolName, int coreSize, int maxSize, int queueCapacity,
                         RejectedExecutionHandler rejectedHandler) {
        this.logger = LoggerFactory.getLogger(poolName);

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);
            @Override
            public Thread newThread(@NonNull Runnable r) {
                Thread thread = new Thread(r, poolName + "-Worker-" + counter.getAndIncrement());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }
        };

        RejectedExecutionHandler handler = (task, executor) -> {
            // 兜底日志：真正「保命」的动作由外部传进来的策略负责
            logger.warn("异步任务被拒绝（队列容量 {}，已排队 {}）", queueCapacity, executor.getQueue().size());
            rejectedHandler.rejectedExecution(task, executor);
        };

        this.executor = new ThreadPoolExecutor(
                coreSize, maxSize, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity), threadFactory, handler
        );
    }

    /**
     * 提交一个不关心结果的异步任务。
     *
     * <p>池已关闭 / 任务被拒绝时<b>不会</b>抛给调用方：拒绝策略已经决定怎么兜
     * （本类默认丢弃；服务端池是抛异常，这里会记 WARN 后丢弃）。
     */
    public void execute(Runnable task) {
        if (executor == null || executor.isShutdown()) {
            logger.debug("线程池已关闭，任务未提交");
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    // 子线程里出的 Error（OOM / StackOverflow 等）不能把 worker 带走后静默无日志
                    logger.error("异步任务执行异常", t);
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列满，或池刚好在此刻关闭
            logger.warn("异步任务被拒绝（队列满 / 池已关闭），已丢弃", e);
        }
    }

    /**
     * 提交一个带结果的异步任务。
     *
     * <p><b>返回的 future 永远会被完成</b>：池已关闭、任务被拒绝、任务里抛 Error，
     * 都会以 {@code completeExceptionally} 收尾，调用方 {@code join()} 不会挂死。
     * （以前池已关闭时直接 return 一个永不完成的 future，外层 join 会永久阻塞。）
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (executor == null || executor.isShutdown()) {
            future.completeExceptionally(new RejectedExecutionException("线程池已关闭，任务未执行"));
            return future;
        }
        try {
            executor.submit(() -> {
                try {
                    future.complete(task.call());
                } catch (Throwable t) {
                    logger.error("异步任务执行异常", t);
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.warn("异步任务被拒绝，future 以异常结束", e);
            future.completeExceptionally(e);
        }
        return future;
    }

    public void shutdown() {
        if (executor != null) {
            logger.debug("等待 {} 个异步任务处理完毕...", executor.getQueue().size());
            executor.shutdown();
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }
    }

    public boolean isShutdown() {
        return executor == null || executor.isShutdown();
    }

    /**
     * 已经排队、还没开始执行的任务数（给「停服前刷盘」这类收尾逻辑做观测用）。
     */
    public int queued() {
        return executor == null ? 0 : executor.getQueue().size();
    }
}
