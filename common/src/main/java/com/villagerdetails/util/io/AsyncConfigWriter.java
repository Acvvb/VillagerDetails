package com.villagerdetails.util.io;

import com.villagerdetails.util.json.JsonIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用<b>异步单写者</b>落盘引擎（内部实现）。
 *
 * <p><b>业务代码不要直接用这个类</b>，用门面 {@link AsyncConfigManager#save}：
 * 它负责「当前线程序列化 → 交给这里异步写」这一整套，调用方只管丢数据。
 *
 * <h2>它解决的问题</h2>
 * 序列化必须在调用方线程（服务端 = 主线程），因为那棵树 / 那个 Map 随时可能被原地修改；
 * 但「写字节」是纯 I/O，没必要占着主线程。于是把两者拆开：
 * <pre>
 *   调用线程：data → JsonIO.serialize(data) → String      （纯 CPU，毫秒级）
 *   写线程  ：String → 临时文件（UUID 名） → 原子 move       （纯 I/O，不碰任何游戏对象）
 * </pre>
 *
 * <h2>为什么是单线程</h2>
 * <ol>
 *     <li><b>顺序</b>：同一个文件的后写必须覆盖先写，多线程会乱序；单写者天然 FIFO；</li>
 *     <li><b>无锁</b>：只有一个线程碰磁盘，不需要文件锁，也不会出现「两个线程抢同一个临时文件」；</li>
 *     <li><b>合并</b>：{@link AsyncConfigManager#save} 同一个路径只保留最新内容，一次 drain 写一遍就够。</li>
 * </ol>
 *
 * <h2>合并（去抖）怎么做的</h2>
 * {@link #PENDING} 是 {@code 路径 → 最新内容}，后提交的覆盖先提交的；
 * {@link #NOTIFY} 只负责"叫醒"写线程。写线程醒来后总是从 {@link #PENDING} <b>取走当前最新值</b>，
 * 所以同一个文件在一个 tick 里被改 10 次，磁盘只会被写 1 次。重复的通知会被"取不到就跳过"自然吃掉。
 *
 * <h2>停机（三道保险）</h2>
 * <ol>
 *     <li><b>服务端</b>：{@code VillagerDetailsServer} 把 {@code AsyncConfigManager.flushAndShutdown}
 *         挂在 pre-shutdown 钩子里，排在「关池之前」；</li>
 *     <li><b>客户端</b>：<b>不用写任何代码</b> —— 本类在类加载时自行注册
 *         {@link Runtime#addShutdownHook}，退出游戏时兜底排干；</li>
 *     <li><b>极端情况</b>：超时不死等、不抛异常，只记 ERROR。</li>
 * </ol>
 * 停完之后如果还有提交，写线程会<b>懒重启</b>——单人反复换存档时不需要手动复位。
 */
public final class AsyncConfigWriter {

    private static final Logger log = LogManager.getLogger(AsyncConfigWriter.class);

    private static final String THREAD_NAME = "VillagerDetails-ConfigWriter";

    /**
     * 单个文件的写盘重试上限。
     *
     * <p>Windows 上「文件正被另一个进程占用」是常态（杀毒 / 索引服务 / 编辑器），
     * 一次失败就把内容丢掉等于静默丢配置，所以给 3 次机会，超过才彻底放弃。
     */
    private static final int MAX_WRITE_RETRIES = 3;

    /**
     * 路径 → 连续失败次数（写盘成功或新内容入队时清零）。
     */
    private static final ConcurrentHashMap<Path, Integer> RETRY_COUNT = new ConcurrentHashMap<>();

    /**
     * 关机兜底排干的等待上限（毫秒）。
     */
    private static final long SHUTDOWN_DRAIN_MS = 5000L;

    /**
     * 写线程空转时的唤醒粒度：只影响停机反应速度，不影响写入延迟（有任务会立刻被唤醒）。
     */
    private static final long POLL_MS = 200L;

    /**
     * 排干 / 精确等待时的自旋粒度。10ms 足够，配置写入本身是亚毫秒级。
     */
    private static final long SPIN_MS = 10L;

    /**
     * 待写内容：{@code 路径 → 最新 JSON}。同一个路径后提交的覆盖先提交的（这就是"合并"）。
     */
    private static final ConcurrentHashMap<Path, String> PENDING = new ConcurrentHashMap<>();

    /**
     * 唤醒队列。元素可能重复、也可能对应的 {@link #PENDING} 项已经没了 —— 写线程会跳过，不影响正确性。
     */
    private static final LinkedBlockingQueue<Path> NOTIFY = new LinkedBlockingQueue<>();

    /**
     * 线程生命周期锁：{@link #ensureWorker()} 与 {@link #flushAndShutdown(long)} 互斥。
     */
    private static final Object LIFECYCLE = new Object();

    /**
     * 当前写线程；{@code null} 表示还没起或已停。
     */
    private static Thread worker;

    /**
     * 停机标志。<b>只由 {@link #flushAndShutdown} 置真、由 {@link #ensureWorker} 置假</b>（都在锁内）。
     */
    private static volatile boolean stop;

    /**
     * 正在写哪个文件（{@code null} = 没在写）。{@link #awaitFile} 用它判断"这个文件是否还有在途写"。
     */
    private static volatile Path inFlight;

    private static final AtomicLong WRITTEN = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();

    static {
        registerShutdownHook();
    }

    private AsyncConfigWriter() {
    }

    // ==============================================================
    // 对外 API（门面在 AsyncConfigManager）
    // ==============================================================

    /**
     * 提交一份<b>已经序列化好</b>的 JSON 文本，由写线程异步落盘。
     *
     * <p><b>必须在调用方线程调用</b>（调用方保证 {@code jsonContent} 已经脱离那棵可变的配置树）。
     * 同一个 {@code filePath} 连续提交多次只有最后一次会真正落盘。
     *
     * <p>本方法<b>不阻塞、不抛异常</b>：入队失败（参数为空）只记一条 WARN。
     *
     * @param filePath    目标文件（父目录不存在会自动创建）
     * @param jsonContent 完整的 JSON 文本
     */
    public static void submit(Path filePath, String jsonContent) {
        if (filePath == null || jsonContent == null) {
            log.warn("AsyncConfigWriter.submit 收到空参数，已忽略（path={}）", filePath);
            return;
        }
        // 先放内容再叫醒：写线程醒来时一定能拿到最新的那一份
        PENDING.put(filePath, jsonContent);
        // 新内容重新计数：之前那份的失败次数不该拖累这一次
        RETRY_COUNT.remove(filePath);
        ensureWorker();
        NOTIFY.offer(filePath);
    }

    /**
     * 等到「这个文件」既没有待写内容、也没有在途写为止。
     *
     * <p>给<b>读盘前的精确栅栏</b>用：{@code /ec c reload} 会先 flush（入队）、再重新读磁盘，
     * 不等一下就会读到写之前的旧内容，等于把刚改的东西回滚掉。
     *
     * <p>只阻塞调用线程，不阻塞写线程；超时只记 ERROR 并返回，绝不抛。
     *
     * @param filePath  要等的文件；{@code null} 直接返回
     * @param timeoutMs 最长等待毫秒数
     */
    public static void awaitFile(Path filePath, long timeoutMs) {
        if (filePath == null) {
            return;
        }
        long deadline = deadlineAfter(timeoutMs);
        while (PENDING.containsKey(filePath) || filePath.equals(inFlight)) {
            if (System.nanoTime() >= deadline) {
                log.error("等待配置落盘超时（{} ms），继续按内存内容运行：{}", timeoutMs, filePath);
                return;
            }
            sleepQuietly();
        }
    }

    /**
     * 排干所有待写内容，然后停掉写线程。
     *
     * <p>调用点：服务端 pre-shutdown 钩子（必须排在所有 flush 之后），以及 JVM 关机钩子。
     * 语义：
     * <ol>
     *     <li>自旋等待 {@link #PENDING} / {@link #NOTIFY} / 在途写全部清空；</li>
     *     <li>置停机标志并 {@code interrupt + join} 写线程；</li>
     *     <li><b>关线程后再复查一次</b>：还有残留就唤醒写线程补排干一轮 —— 防止
     *         「判空之后、置停机标志之前」这条窗口里新提交的内容被静默搁置；</li>
     *     <li>超时不死等、不抛异常，只记 ERROR（剩下的文件交给 JVM 关机钩子或下次启动）。</li>
     * </ol>
     *
     * <p>停完之后再 {@link #submit} 会<b>懒重启</b>写线程，所以单人换存档不需要手动复位。
     *
     * @param timeoutMs 最长等待毫秒数；{@code <= 0} 表示不等，直接停
     */
    public static void flushAndShutdown(long timeoutMs) {
        // 第一轮：正常排干 + 停线程
        drain(timeoutMs);
        shutdownWorker(timeoutMs);

        // 第二轮：关线程之后再复查一次。
        // 旧实现在「hasPending() 判空 → 置 stop」之间有个窗口：这段时间里的 submit 会成功
        // 写进 PENDING，而 ensureWorker() 看到写线程还活着就直接返回，随后写线程因 stop 退出
        // —— 那条内容再也没人写，而且全程没有日志。所以这里唤醒一次补排干。
        if (!hasPending()) {
            return;
        }
        log.warn("配置写线程已停，但仍有 {} 个文件待写，唤醒写线程补排干一轮：{}",
                PENDING.size(), PENDING.keySet());
        ensureWorker();
        // 停机期间提交的内容当时没被叫醒（写线程还没起），这里把通知重投一遍；
        // 重复通知由写线程「取不到就跳过」自然吃掉。
        for (Path path : PENDING.keySet()) {
            NOTIFY.offer(path);
        }
        drain(timeoutMs);
        shutdownWorker(timeoutMs);

        if (hasPending()) {
            // 极端情况（磁盘无响应 / 无权限）：不死等，剩下的交给 JVM 关机钩子或下次启动
            log.error("补排干后仍有 {} 个文件未写完，留给 JVM 关机钩子兜底：{}",
                    PENDING.size(), PENDING.keySet());
        }
    }

    /**
     * 自旋等待待写内容清空；超时只记 ERROR 并返回（不死等、不抛异常）。
     */
    private static void drain(long timeoutMs) {
        long deadline = deadlineAfter(timeoutMs);
        while (hasPending()) {
            if (System.nanoTime() >= deadline) {
                log.error("等待配置落盘超时（{} ms），仍有 {} 个文件未写完：{}",
                        timeoutMs, PENDING.size(), PENDING.keySet());
                return;
            }
            sleepQuietly();
        }
    }

    // ==============================================================
    // 关机兜底
    // ==============================================================

    /**
     * JVM 退出时的兜底排干。
     *
     * <p>为什么需要它：服务端有自己的停服钩子，但<b>客户端没有任何"关服"事件</b>
     * （直接关窗口 / 进程被结束）。这里注册一次，客户端就<b>不用写任何停机关闭代码</b>也能防丢。
     *
     * <p>服务端场景下它是完全空操作：'/'+'stop' 时 pre-shutdown 钩子已经排干并停过写线程，
     * 走到这里时 {@code hasPending()} 为 false，立即返回。
     */
    private static void registerShutdownHook() {
        try {
            Thread hook = new Thread(() -> {
                try {
                    flushAndShutdown(SHUTDOWN_DRAIN_MS);
                } catch (Throwable t) {
                    // 关机钩子里绝不能抛：抛了只会被 JVM 记成未捕获异常，还拖慢退出
                    log.error("关机兜底排干配置写入失败", t);
                }
            }, THREAD_NAME + "-Shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (Throwable t) {
            // JVM 已经在关闭 / 安全管理器拒绝注册：不影响正常运行（服务端还有自己的钩子兜着）
            log.debug("注册配置写盘关机钩子失败（不影响正常运行）：{}", t.toString());
        }
    }

    // ==============================================================
    // 写线程
    // ==============================================================

    /**
     * 确保写线程活着；没有就起一个（停机后再次提交会走到这里）。
     *
     * <p>在锁内做：避免「停机 join 到一半又被拉起一条新线程」这种双写者。
     */
    private static void ensureWorker() {
        synchronized (LIFECYCLE) {
            Thread current = worker;
            if (!stop && current != null && current.isAlive()) {
                return;
            }
            if (current != null && current.isAlive()) {
                // 后台正在停机：等它退干净再起新的（正常路径不会走到，纯兜底）
                try {
                    current.join(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            stop = false;
            Thread thread = new Thread(AsyncConfigWriter::runLoop, THREAD_NAME);
            // daemon：真正的数据安全由 flushAndShutdown / 关机钩子保证，
            // 不靠"JVM 被这条线程吊住"
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            worker = thread;
            thread.start();
        }
    }

    private static void shutdownWorker(long timeoutMs) {
        synchronized (LIFECYCLE) {
            Thread current = worker;
            if (current == null) {
                stop = false;
                return;
            }
            stop = true;
            worker = null;
            current.interrupt();
            long remaining = Math.max(1L, timeoutMs);
            try {
                current.join(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (current.isAlive()) {
                // 极端情况（写阻塞在无响应的网络盘上）：等不到就只能放弃这条线程。
                // worker 已经置空，后续提交会再起一条 —— 因为临时文件名带 UUID、替换是原子 move，
                // 两条线程并发写同一个目标文件也不会互相破坏，最坏只是最终内容顺序不确定。
                log.error("配置写线程未能在 {} ms 内退出，已放弃等待（待写 {} 个文件）",
                        remaining, PENDING.size());
            }
        }
    }

    private static void runLoop() {
        log.debug("配置写线程已启动：{}", THREAD_NAME);
        try {
            while (!stop) {
                Path path;
                try {
                    path = NOTIFY.poll(POLL_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (stop) {
                        return;
                    }
                    continue;
                }
                if (path == null) {
                    continue;
                }
                writeOne(path);
            }
        } catch (Throwable t) {
            // 绝不让写线程死于未知异常：它一死，所有后续配置都不会再落盘
            log.error("配置写线程异常退出，后续写入将不再进行", t);
        } finally {
            inFlight = null;
            log.debug("配置写线程已退出：{}", THREAD_NAME);
        }
    }

    /**
     * 写一个文件：从 {@link #PENDING} 取<b>当前最新</b>内容（取不到说明已被更新的通知合并掉）。
     */
    private static void writeOne(Path path) {
        // 先标记在途，再取内容：否则「已取走内容、还没开始写」的那一瞬间
        // 会被排干逻辑误判成"没活了"
        inFlight = path;
        String content = null;
        try {
            content = PENDING.remove(path);
            if (content == null) {
                return;
            }
            // 真正的字节级写入（唯一临时文件名 + 原子替换 + 失败回退）统一在 JsonIO 里
            JsonIO.writeString(path, content);
            WRITTEN.incrementAndGet();
            // 写成功：这只文件的重试计数清零
            RETRY_COUNT.remove(path);
        } catch (Throwable t) {
            FAILED.incrementAndGet();
            if (content == null) {
                // 极少见：刚取到内容就抛了，没有内容可放回
                RETRY_COUNT.remove(path);
                log.error("异步写入配置失败（本次没有可重试的内容）：{}", path, t);
                return;
            }
            int attempts = RETRY_COUNT.merge(path, 1, Integer::sum);
            if (attempts > MAX_WRITE_RETRIES) {
                // 超过上限：彻底放弃，但必须留下能搜到的 WARN（这份内容确实没了）
                RETRY_COUNT.remove(path);
                log.warn("异步写入配置失败，已重试 {} 次仍失败，放弃本次写入（{} 字节）：{}",
                        MAX_WRITE_RETRIES, content.length(), path, t);
                return;
            }
            // 放回待写队列：putIfAbsent 不覆盖「已经入队的更新版本」。
            // 返回非 null 说明队列里已有新内容 —— 本次这份已经过期，丢掉即可（新内容有自己的重试计数）。
            String newer = PENDING.putIfAbsent(path, content);
            if (newer == null) {
                log.warn("异步写入配置失败（第 {}/{} 次），已放回待写队列稍后重试：{}",
                        attempts, MAX_WRITE_RETRIES, path, t);
            } else {
                RETRY_COUNT.remove(path);
                log.debug("异步写入配置失败，但队列里已有更新版本，丢弃这份过期内容：{}", path);
            }
            // 重新叫醒写线程：上面两种情况下队列里都还有内容要写
            NOTIFY.offer(path);
        } finally {
            inFlight = null;
        }
    }

    // ==============================================================
    // 内部工具
    // ==============================================================

    private static boolean hasPending() {
        return !PENDING.isEmpty() || !NOTIFY.isEmpty() || inFlight != null;
    }

    private static long deadlineAfter(long timeoutMs) {
        long nanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        // 防溢出：System.nanoTime() 的绝对值没有意义，只比差值
        return System.nanoTime() + nanos;
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(AsyncConfigWriter.SPIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
