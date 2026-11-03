package com.villagerdetails.util.io;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 配置的<b>保存状态机</b>：脏标记 + 批量合并 + 提交落盘。
 *
 * <p>位于 {@code main} 公共区，服务端与客户端共用；<b>不依赖任何游戏类型</b>，
 * 只认标准 {@link Path} 和一个「能被 Gson 序列化的数据对象」。
 *
 * <h2>它到底解决了什么</h2>
 * <p>改一项配置就整份重写一次文件，代价随「一 tick 里改了多少项」线性增长：
 * 批量入名单是 N 次整份写盘、迁移一次要连写 4 次、传送门回写会落在玩家传送的那一帧。
 * 所以把「改」和「写」拆开：
 * <pre>
 *   改：{@link #markDirty()}                 —— 只置一个原子标志，O(1)、不碰磁盘
 *   写：{@link #flush()}                     —— 序列化（调用线程）→ {@link AsyncConfigManager#save}（后台单写者落盘）
 * </pre>
 * 子类只要回答两个问题：{@link #getFilePath()}（写哪个文件）和 {@link #getDataToSave()}（写什么）。
 *
 * <h2>为什么还需要 {@link #batch}</h2>
 * <p>「标脏 + 定时 flush」只解决了「跨 tick 合并」，同一 tick 内的 N 次修改仍需 N 次序列化 ——
 * 而序列化恰恰是整份遍历配置树的那一步。{@code batch} 在块内<b>压住所有 flush</b>，
 * 退出块时只提交一次；块内的 flush 请求不会丢，因为脏标记一直亮着（改过就是脏）。
 *
 * <h2>线程约定</h2>
 * <ul>
 *     <li>{@link #markDirty()} 只置标志，任意线程都安全；</li>
 *     <li>{@link #flush()} / {@link #batch} <b>必须在主线程</b> —— 它们会遍历
 *         {@link #getDataToSave()} 返回的那棵树，而 Gson 的树 / 普通 Map 都不是线程安全的；</li>
 *     <li>真正的字节级 I/O 不在本类里：{@link AsyncConfigManager#save} 当场序列化、
 *         再把字符串交给 {@link AsyncConfigWriter} 的专职写线程。</li>
 * </ul>
 *
 * <h2>失败语义</h2>
 * <p>{@link AsyncConfigManager#save} 返回 {@code false}（参数为空 / 序列化失败）时<b>不清脏标记</b>，
 * 下一轮 flush 会重试；写盘本身的失败由 {@code AsyncConfigWriter} 记 ERROR 并计数，不在这里重试。
 * {@link #getFilePath()} 返回 {@code null}（例如服务器还没挂上 / 已经停服）时同样保留脏标记直接返回，
 * 绝不写盘。
 *
 * @see AsyncConfigManager 落盘门面
 * @see JsonIO 序列化与原子写
 */
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
    private int batchDepth;

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
     * 当前是否还有未提交的改动（观测 / 排障用）。
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
    public synchronized void flush() {
        if (batchDepth > 0) {
            return;
        }
        if (!dirty.get()) {
            return;
        }
        Path filePath = getFilePath();
        if (filePath == null) {
            // 目标还没准备好（启动前 / 停服后）：脏标记留着，等下一次机会
            return;
        }
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
        synchronized (this) {
            batchDepth++;
        }
        try {
            action.run();
        } finally {
            boolean outmost;
            synchronized (this) {
                outmost = --batchDepth == 0;
            }
            if (outmost) {
                flush();
            }
        }
    }
}
