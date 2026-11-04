package com.villagerdetails.util.io;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class AbstractAsyncConfig {

    /**
     * 有没有「还没提交给写线程」的改动。
     *
     * <p>用 {@link AtomicBoolean} 而不是 {@code boolean}：{@link #markDirty()} 会被
     * 事件回调 / 命令 / 网络包在多处调用，不希望调用方为了置标志先去抢一把锁。
     */
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    /**
     * 当前嵌套的 {@link #batch} 深度。<b>只在主线程读写</b>（见类注释的线程约定）。
     */
    private final AtomicInteger batchDepth = new AtomicInteger(0);

    protected AbstractAsyncConfig() {
    }

    // ==============================================================
    // 子类回答的两个问题
    // ==============================================================

    /**
     * 目标文件；返回 {@code null} 表示「现在没有可写的目标」（{@link #flush()} 会保留脏标记直接返回）。
     *
     * <p>每次 flush 都会重新问一遍，所以子类可以跟着「当前服务器 / 存档目录」动态变化。
     * 允许抛异常：调用方（tick 事件 / 停服收尾）自己决定记日志还是中断。
     */
    protected abstract Path getFilePath();

    /**
     * 要落盘的数据：直接交内存里的树 / Map 即可，Gson 会当场把它序列化成 String。
     *
     * <p><b>必须在主线程、且在没有任何线程改它的时候读</b>（本类的 flush 就是串行化点）。
     */
    protected abstract Object getDataToSave();

    // ==============================================================
    // 脏标记
    // ==============================================================

    /**
     * 标记「有改动，需要落盘」。<b>不碰磁盘</b>，同一 tick 内调多少次都只会在下一次
     * {@link #flush()} 里提交一次。
     */
    public void markDirty() {
        dirty.set(true);
    }

    /**
     * 是否有未提交的改动。
     */
    public boolean isDirty() {
        return dirty.get();
    }

    /**
     * 强行清掉脏标记。
     *
     * <p>只在「内存内容被整体丢弃」时用（例如停服时 {@code reset()} 先 flush 再清空 root）：
     * 不清的话，作废的旧内容会在下次开服时被写出去。
     */
    public void clearDirty() {
        dirty.set(false);
    }

    // ==============================================================
    // 提交
    // ==============================================================

    /**
     * 把累积的改动提交给异步写线程；没有改动 / 没有目标文件时是空操作。
     *
     * <p>主线程只做序列化（纯 CPU），磁盘 I/O 由 {@link AsyncConfigWriter} 的单写者线程完成，
     * 所以本方法<b>不会阻塞在磁盘上</b>。同一个文件连续提交多次只会真正写一次（后写覆盖先写）。
     *
     * <p>处于 {@link #batch} 块内时直接返回：块退出时会统一提交一次。
     */
    public void flush() {
        if (batchDepth.get() > 0) return;
        if (!dirty.get()) return;

        Path filePath = getFilePath();
        if (filePath == null) return;

        if (AsyncConfigManager.save(filePath, getDataToSave())) {
            dirty.set(false);
        }
    }

    /**
     * 把一批修改合并成<b>一次</b>落盘。
     *
     * <p>块内任何 {@link #flush()} 都会被压住（否则每条规则 {@code setState} 都会整份重写一次），
     * 块退出时（含异常退出）自动提交一次 —— 所以「改了但没写」不会因为块内抛异常而丢。
     * 可以嵌套：只有最外层退出时才提交。
     *
     * @param action 要执行的批量修改；{@code null} 视为空操作
     */
    public void batch(Runnable action) {
        if (action == null) {
            return;
        }
        batchDepth.incrementAndGet();
        try {
            action.run();
        } finally {
            boolean outMost = batchDepth.decrementAndGet() == 0;
            if (outMost) {
                flush();
            }
        }
    }
}
