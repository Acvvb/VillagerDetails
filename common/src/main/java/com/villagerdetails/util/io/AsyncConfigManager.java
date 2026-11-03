package com.villagerdetails.util.io;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * 配置落盘的<b>门面</b>：把数据丢进来，序列化 / 合并 / 异步写盘全部由底层完成。
 *
 * <p>位于 {@code main} 公共区，服务端与客户端<b>共用同一套</b>：
 * 调用方不需要认识 {@link JsonIO} 和 {@link AsyncConfigWriter}，
 * 也不需要自己决定"什么时候写、写几次"。
 *
 * <h2>怎么用</h2>
 * <pre>{@code
 * // 改完内存里的数据，直接丢进来就行：
 * AsyncConfigManager.save(worldPath.resolve("villagerdetails/config.json"), root);
 * AsyncConfigManager.save(configDir.resolve("client.json"), this);
 * AsyncConfigManager.save(file, bindingStates);      // Map 直接给，Gson 会序列化成 JSON 对象
 * }</pre>
 *
 * <h2>它替你做了什么</h2>
 * <ol>
 *     <li><b>当场序列化</b>：{@code data} 在<b>调用线程</b>（服务端 = 主线程）立刻变成 String，
 *         所以之后那棵树 / 那个 Map 再被改也不会污染这次写入；</li>
 *     <li><b>自动合并</b>：同一个 {@link Path} 连续 {@code save} 多次只落盘一次（后写的覆盖先写的）；</li>
 *     <li><b>异步写盘</b>：真正的 I/O 在一条专职单写者线程上完成，
 *         <b>不会阻塞调用线程</b>（服务端不会因此掉 tick）；</li>
 *     <li><b>原子替换</b>：唯一临时文件名 + {@code ATOMIC_MOVE}，写到一半崩了也不会留下半截 JSON；</li>
 *     <li><b>自动兜底排干</b>：JVM 退出时（客户端直接关窗口也算）
 *         {@link AsyncConfigWriter} 自带关机钩子会排干，<b>客户端不用写任何关闭代码</b>。</li>
 * </ol>
 *
 * <h2>什么时候不该用它</h2>
 * 需要「函数返回时文件已经落地」的强一致场景（例如写完后立刻要读回同一路径），
 * 请显式先调 {@link #awaitFile}，或者直接用 {@link JsonIO#writeObject} 同步写。
 *
 * @see AsyncConfigWriter 单写者 + 合并的内部引擎
 * @see JsonIO 序列化与原子写
 */
public final class AsyncConfigManager {

    private static final Logger log = LogManager.getLogger(AsyncConfigManager.class);

    private AsyncConfigManager() {
    }

    /**
     * 把任意可被 Gson 序列化的数据异步写入指定文件。
     *
     * <p><b>必须在调用方线程（服务端 = 主线程）调用</b>：本方法会立刻序列化 {@code data}，
     * 遍历期间那棵树 / 那个集合必须没有别的线程在改。
     *
     * <p>本方法<b>不阻塞、不抛异常</b>：
     * <ul>
     *     <li>参数为空 → 记 WARN 并返回 {@code false}；</li>
     *     <li>序列化失败 → 记 ERROR 并返回 {@code false}，不提交写盘；</li>
     *     <li>正常入队 → 返回 {@code true}。</li>
     * </ul>
     * 返回值只给「需要据此保留脏标记」的调用方用（例如
     * {@code UnifiedConfigFile.flush}），日常调用可以完全忽略。
     *
     * @param filePath 目标文件（父目录不存在会自动创建）
     * @param data     任意可被 Gson 序列化的对象：{@code JsonObject} / {@code Map} / 普通 POJO
     * @return 是否已成功入队
     */
    public static boolean save(Path filePath, Object data) {
        if (filePath == null || data == null) {
            log.warn("AsyncConfigManager.save 收到空参数，已忽略（path={}）", filePath);
            return false;
        }
        try {
            // 1) 必须在调用线程序列化：防止 Gson 树 / Map 被跨线程并发修改
            String jsonString = JsonIO.serialize(data);
            // 2) 扔给后台单写者线程
            AsyncConfigWriter.submit(filePath, jsonString);
            return true;
        } catch (Throwable t) {
            // 序列化炸了（循环引用 / 不支持的字段类型）：不提交，让调用方保留脏标记下轮重试
            log.error("序列化配置失败，未提交写盘：{}", filePath, t);
            return false;
        }
    }

    /**
     * 等到「这个文件」没有待写内容、也没有在途写为止。
     *
     * <p>给<b>读盘前的精确栅栏</b>用：先 {@code save} 入队、紧接着要重新读磁盘时，
     * 不等一下就会读到写之前的旧内容（{@code /ec c reload} 就属于这种情况）。
     *
     * <p>只阻塞调用线程；超时只记 ERROR 并返回，不抛。
     *
     * @param filePath  要等的文件；{@code null} 直接返回
     * @param timeoutMs 最长等待毫秒数
     */
    public static void awaitFile(Path filePath, long timeoutMs) {
        AsyncConfigWriter.awaitFile(filePath, timeoutMs);
    }

    /**
     * 排干所有待写内容并停掉写线程。
     *
     * <p>服务端由 {@code ServerThreadManager} 的 pre-shutdown 钩子调用（<b>必须排在所有 save 之后</b>）；
     * 客户端不需要手动调用 —— JVM 关机钩子已经兜好了。
     *
     * @param timeoutMs 最长等待毫秒数
     */
    public static void flushAndShutdown(long timeoutMs) {
        AsyncConfigWriter.flushAndShutdown(timeoutMs);
    }

}
