package com.villagerdetails.concurrent;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ModThreadPool {
    private final Logger logger;
    private final ThreadPoolExecutor executor;

    // 通过构造器传入不同的参数，让两端各自定制容量
    public ModThreadPool(String poolName, int coreSize, int maxSize, int queueCapacity) {
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

        RejectedExecutionHandler rejectedHandler = (_, _) -> logger.warn("异步队列已满 (Max={})，任务被丢弃！", queueCapacity);

        this.executor = new ThreadPoolExecutor(
                coreSize, maxSize, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity), threadFactory, rejectedHandler
        );
    }

    public void execute(Runnable task) {
        if (executor != null && !executor.isShutdown()) {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    logger.error("异步任务执行异常", e);
                }
            });
        }
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (executor != null && !executor.isShutdown()) {
            executor.submit(() -> {
                try {
                    future.complete(task.call());
                } catch (Exception e) {
                    logger.error("异步任务执行异常", e);
                    future.completeExceptionally(e);
                }
            });
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
}