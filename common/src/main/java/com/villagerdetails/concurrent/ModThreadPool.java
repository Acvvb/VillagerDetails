package com.villagerdetails.concurrent;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ModThreadPool {
    private final Logger log;
    private final ThreadPoolExecutor executor;
    public static final int THRESHOLD = 100; // 积压阈值

    /**
     * 停服时等待队列跑完的时长（秒）；超时后剩余任务会被拉回调用线程执行。
     */
    private static final long AWAIT_TERMINATION_SECONDS = 1L;

    /**
     * 拉回任务数超过这个量时打 INFO（否则只打 DEBUG），便于从日志看出"停服为什么慢了"。
     */
    private static final int REMAINING_LOG_THRESHOLD = 100;

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
        this.log = LoggerFactory.getLogger(poolName);

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
            log.warn("异步任务被拒绝（队列容量 {}，已排队 {}）", queueCapacity, executor.getQueue().size());
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
     * @return true = 成功入队；false = 队列满 / 池已关闭（被拒绝）
     */
    public boolean execute(Runnable task) {
        if (executor.isShutdown()) {
            log.debug("线程池已关闭，任务未提交");
            return false;
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("异步任务被拒绝（队列满 / 池已关闭），已丢弃", e);
            return false;
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
        if (executor.isShutdown()) {
            future.completeExceptionally(new RejectedExecutionException("线程池已关闭，任务未执行"));
            return future;
        }
        try {
            executor.submit(() -> {
                try {
                    future.complete(task.call());
                } catch (Throwable t) {
                    log.error("异步任务执行异常", t);
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("线程池队列已满，请保存日志联系开发者。", e);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 关闭前把线程数临时提到 {@code max}，让积压更快被消化。
     *
     * <p>阈值判断用乘法而不是 {@code pending / currentCore}：整数除法会把
     * {@code pending=99, currentCore=4} 算成 24，看起来"不积压"而跳过扩容。
     */
    private void expandPoolForShutdown() {
        if (executor.isShutdown()) return;

        int pending = executor.getQueue().size();
        if (pending == 0) return;

        int currentCore = executor.getCorePoolSize();
        int targetThreads = executor.getMaximumPoolSize();

        // 防止除以 0 的极端情况
        if (currentCore <= 0 || targetThreads <= 0) return;

        if (pending <= currentCore * THRESHOLD) {
            return;
        }

        // 积压严重时一律扩到 max。
        // 曾经这里写成 pending/currentMax > THRESHOLD ? currentMax >> 1 : currentMax ——
        // 积压越大反而缩容（currentMax>>1 < currentMax），方向是反的。
        log.debug("当前积压任务:{}条，扩容至:{}线程处理", pending, targetThreads);

        // 只提 core（target 恒等于 max，不需要动 max）
        if (targetThreads > currentCore) {
            executor.setCorePoolSize(targetThreads);
        }
    }

    /**
     * 优雅关闭。
     *
     * <p>实际行为（以此为准）：
     * <ol>
     *     <li>队列空且没有活跃任务 → {@code shutdownNow()} 直接关；</li>
     *     <li>否则先 {@link #expandPoolForShutdown()} 把线程数临时提到 max，再 {@code shutdown()}；</li>
     *     <li>{@code awaitTermination} 最多等 {@value #AWAIT_TERMINATION_SECONDS} 秒；</li>
     *     <li>等到了 → 正常返回；<b>超时</b> → 把仍在队列里的任务<b>拉回调用线程</b>逐个执行，
     *         保证不静默丢任务（快照池丢任务 = 交易索引缺数据，要等下一次区块卸载才补得回来）。</li>
     * </ol>
     *
     * <p>拉回路径没有时间上限：加上限就等于重新引入"部分任务丢失"。
     * 它只发生在停服路径上（主线程本来就在等池子关掉），所以"主线程被占用"是可接受的代价。
     *
     * <p><b>未来如果有池想"丢弃剩余任务"</b>，请在调用侧走 {@code shutdownNow()}，
     * <b>不要</b>改这里 —— 本方法的契约是"已经入队的任务一定被执行"，加开关会让它同时承担两种语义。
     */
    public void shutdown() {
        if (executor.isShutdown()) {
            return;
        }
        if (executor.getQueue().isEmpty() && executor.getActiveCount() == 0) {
            executor.shutdownNow();
            log.debug("线程池无任务，直接关闭");
            return;
        }
        expandPoolForShutdown();
        executor.shutdown();
        boolean terminated;
        try {
            terminated = executor.awaitTermination(AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminated = false;
        }
        if (terminated) {
            log.debug("线程池已在 {} 秒内跑完并关闭", AWAIT_TERMINATION_SECONDS);
            return;
        }
        runRemainingOnCallerThread();
    }

    /**
     * 把队列里剩下的任务拉回当前线程（停服时 = 主线程）同步执行。
     *
     * <p>单个任务抛异常只记日志，不能中断整个拉回循环；也不设时间上限。
     */
    private void runRemainingOnCallerThread() {
        List<Runnable> remaining = new ArrayList<>();
        executor.getQueue().drainTo(remaining);
        if (remaining.isEmpty()) {
            // worker 们在我们 drain 之前就把队列取空了：没有可拉回的，直接返回
            log.debug("线程池关闭超时，但队列已被 worker 取空，无需拉回");
            return;
        }
        if (remaining.size() > REMAINING_LOG_THRESHOLD) {
            log.info("线程池关闭超时，正在把 {} 个待执行任务拉回当前线程执行（停服期间可能短暂占用主线程）",
                    remaining.size());
        } else {
            log.debug("线程池关闭超时，拉回 {} 个待执行任务到当前线程", remaining.size());
        }
        for (Runnable task : remaining) {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("拉回执行的任务抛出异常（已跳过，继续处理剩余任务）", t);
            }
        }
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }

    /**
     * 已经排队、还没开始执行的任务数（给「停服前刷盘」这类收尾逻辑做观测用）。
     */
    public int queued() {
        return executor.getQueue().size();
    }
}