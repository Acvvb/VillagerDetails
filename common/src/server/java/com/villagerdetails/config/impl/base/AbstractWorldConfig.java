package com.villagerdetails.config.impl.base;

import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.util.ConfigPaths;
import com.villagerdetails.util.io.AbstractAsyncConfig;
import com.villagerdetails.util.io.AsyncConfigManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>「存档内一份 JSON 文件」型配置的公共父类（观察者模式版）</b>。
 *
 * <p>四个配置（{@code config.json} / {@code trade_index.json} /
 * {@code villager_details_binding.json} / {@code villager_trade_cache.json}）的生命周期和 IO
 * 完全一样，差异只在「文件叫什么、怎么把 JSON 读成内存数据、内存数据怎么清空」。
 *
 * <h2>观察者模式 & Tick 节流</h2>
 * <ul>
 *     <li>只有当实例真正发生数据变更调用了 {@link #markDirty()} 时，它才会主动把自己推入 {@code DIRTY_SET}（脏队列）。</li>
 *     <li>{@link #tickAll()} 绝大多数情况下在检测到集合为空时直接极速返回，完全没有遍历开销。</li>
 *     <li>每一个实例可以通过 {@link #flushIntervalTicks()} 声明自己的落盘节流周期（如村民数据为 1200 tick，普通配置为 1 tick）。</li>
 * </ul>
 *
 * <h2>谁来驱动</h2>
 * <p>tick 驱动<b>就挂在本类的静态块里</b>（见下方 {@code static} 初始化块），
 * 因此新增配置只要 {@code extends AbstractWorldConfig} 就会被自动接管，
 * 不需要再去别处登记 —— <b>也不要再注册第二份 {@code tickAll}</b>：
 * 每多注册一次，{@link #globalTicks} 每 tick 就多加一，节流周期会成比例缩短。
 *
 * <h2>子类的最小义务</h2>
 * <p>实现 {@link #savePath()} / {@link #load()} / {@link #clearData()} /
 * {@link #getDataToSave()} / {@link #configName()} 五个方法即可；
 * 想加行为就覆盖 {@link #onReload} / {@link #onDiskLoad} / {@link #flushIntervalTicks()}。
 */
public abstract class AbstractWorldConfig extends AbstractAsyncConfig implements ReloadableConfig {

    private static final Logger log = LogManager.getLogger(AbstractWorldConfig.class);

    // 主线程每 tick 驱动一次观察者队列。
    // 这是全类唯一的 tick 注册点：别处（例如 ConfigFlushManager）再注册一次会让
    // globalTicks 每 tick +2，把 1200 tick 的节流周期实际变成 600 tick。
    static {
        ServerTickEvents.END_SERVER_TICK.register(_ -> AbstractWorldConfig.tickAll());
    }

    /**
     * 重载前等待异步写落地的上限（毫秒）。等的是本地一次小文件写，正常毫秒级。
     */
    protected static final long RELOAD_AWAIT_MS = 5000L;

    /**
     * 所有实例（仅用于停服时的兜底 flushAll / resetAll）。
     */
    private static final List<AbstractWorldConfig> ALL = new CopyOnWriteArrayList<>();

    /**
     * 观察者队列：只有被标记为 dirty 的配置才会进入此集合。
     * 每 tick 调度器只遍历这个集合，而不是 ALL。
     */
    private static final Set<AbstractWorldConfig> DIRTY_SET = ConcurrentHashMap.newKeySet();

    /**
     * 全局 tick 计数器（只在服务端主线程读写）。
     * 用于计算距离上一次落盘过了多少个 tick，实现精确节流。
     */
    private static long globalTicks = 0;

    // ==============================================================
    // 生命周期状态
    // ==============================================================

    /** 当前挂着的服务器；{@code null} 表示「现在没有可写的目标」（停服后 / 还没开服）。 */
    private volatile MinecraftServer server;

    /** 当前 {@link #server} 的数据是否已经读进内存（换 server 会重置成 {@code false}）。 */
    private volatile boolean loaded;

    /**
     * 上一次落盘的 tick。初始值为 -1，确保第一次进入脏队列时能立刻触发落盘（开服秒建文件）。
     */
    private long lastFlushedTick = -1;

    /**
     * 构造：把实例登记进 {@link #ALL}（停服兜底要用），脏队列则在第一次
     * {@link #markDirty()} 时才加入。
     *
     * <p>{@code this-escape} 在这里是安全的：登记只存一个引用，构造过程中没有任何代码会去读
     * {@link #ALL}（唯一的读取方是停服事件，在全部单例构造完成之后）；
     * 而四个实例都是 {@code static final INSTANCE}，类初始化完成前不会有人调用驱动方法。
     */
    @SuppressWarnings("this-escape")
    protected AbstractWorldConfig() {
        ALL.add(this);
    }

    // ==============================================================
    // 子类回答的问题
    // ==============================================================

    /**
     * 相对存档根目录的路径，例如 {@code villagerdetails/config.json}。
     *
     * <p>每次落盘都会重新问一遍，配合「当前 server」现算，所以换存档不需要清理缓存路径。
     *
     * @return 相对路径（不含存档根前缀）
     */
    protected abstract String savePath();

    /**
     * 磁盘 → 内存：把文件读成各自的内存数据。
     *
     * <p>由生命周期方法在<b>服务端主线程</b>调用；只做读盘 + 内存重建，不碰写盘。
     * 用 {@link #currentFile()} 拿路径，返回 {@code null} 表示「现在没有可读的文件」，
     * 子类应按空数据/默认值处理并直接返回。
     */
    protected abstract void load();

    /**
     * 内存清空（停服复位用）。<b>不要在这里读盘或写盘。</b>
     */
    protected abstract void clearData();

    /**
     * 配置名：注册表去重、{@code /ec c reload <name>} 的点名清单、日志前缀都用它。
     *
     * @return 稳定的配置名（改了会破坏命令的点名清单，不要跟随类名漂移）
     */
    @Override
    public abstract String configName();

    /**
     * {@link #reload} 里、{@code ensureLoaded} 之后、{@code markDirty} 之前执行。
     *
     * <p>典型用法：把读到的内容应用到别的系统（{@code RuleConfig} → 回灌 {@code RuleCache}，
     * {@code UnifiedConfigFile} → 带上所有 section 子配置）。
     *
     * @param server 当前服务器；可能为 {@code null}（停服路径上的重载）
     */
    protected void onReload(MinecraftServer server) {
    }

    /**
     * {@link #reloadFromDisk} 里、真正读完盘之后执行。
     *
     * <p>典型用法：应用 + 打一条带文件名的日志。
     *
     * @param server 当前服务器；可能为 {@code null}（此时没有可读的文件）
     */
    protected void onDiskLoad(MinecraftServer server) {
    }

    /**
     * 落盘周期（tick）。默认 1 = 每 tick 只要脏了就立刻写。
     * 只有「序列化代价高」的配置（整份村民索引）才需要调大。
     *
     * @return 两次落盘之间至少间隔多少 tick
     */
    protected int flushIntervalTicks() {
        return 1;
    }

    // ==============================================================
    // 观察者模式接线
    // ==============================================================

    /**
     * 标记有改动，并主动将自己推入「待写盘观察者队列」。
     *
     * <p>只置标志 + 入集合，<b>不碰磁盘</b>，可以随意在事件回调 / 命令 / 网络包里调；
     * 真正的写盘由 {@link #tickAll()} 按各实例的节流周期触发。
     */
    @Override
    public void markDirty() {
        super.markDirty();
        DIRTY_SET.add(this);
    }

    /**
     * 清除脏标记，并从观察者队列中移出。
     *
     * <p>{@code flush()} 成功时走的是父类的「直接置标志」而不是本方法，所以那份清理
     * 由 {@link #tickAll()} 在确认不脏之后自己做 —— 两条路径都保证不会留在队列里。
     */
    @Override
    public void clearDirty() {
        super.clearDirty();
        DIRTY_SET.remove(this);
    }

    /**
     * tick 末尾：只遍历脏队列，按各自的节流周期决定是否 flush。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>{@link #globalTicks} +1；</li>
     *     <li>脏队列为空 → 立即返回（绝大多数 tick 都走这条快路径）；</li>
     *     <li>对队列里每个实例：距离上次落盘已满 {@link #flushIntervalTicks()} tick
     *         → {@link #flush()}；提交成功（不再脏）后把它移出队列并记下本次 tick。</li>
     * </ol>
     *
     * <p>提交失败（磁盘满 / 文件被占）时脏标记会被保留、实例留在队列里，
     * 于是下一个 tick 继续重试，不会因为一次失败丢掉改动。
     */
    public static void tickAll() {
        globalTicks++;
        if (DIRTY_SET.isEmpty()) {
            return; // 极速空判断，绝大多数 tick 都在这里瞬间结束
        }

        for (AbstractWorldConfig config : DIRTY_SET) {
            if (globalTicks - config.lastFlushedTick >= config.flushIntervalTicks()) {
                config.flush();

                if (!config.isDirty()) {
                    DIRTY_SET.remove(config);
                    config.lastFlushedTick = globalTicks;
                }
            }
        }
    }

    /**
     * 当前是否有未落盘的改动。
     *
     * <p>直接透传父类的原子标志，<b>不额外维护一份状态</b>：脏标记只有
     * {@link #markDirty()} / 父类 {@code flush()} 成功 / {@link #clearDirty()} 三处会改，
     * 多存一份只会有对不齐的风险。
     *
     * @return {@code true} 表示还有改动没提交，下一次 {@link #tickAll()} 会尝试落盘
     */
    @Override
    public boolean isDirty() {
        return super.isDirty();
    }

    // ==============================================================
    // 生命周期
    // ==============================================================

    /**
     * 开服回灌：没加载过就读一次盘，执行 {@link #onReload} 钩子，然后标脏一次。
     *
     * <p>为什么回灌完还要标脏：保证文件存在 —— 全默认值的存档也会拿到一份规范内容，
     * 而不是「这个配置文件从来不存在」。
     *
     * <p>这个方法被 {@code final} 掉：四个配置的先后顺序必须完全一致，
     * 子类要加行为请用 {@link #onReload} 钩子。
     *
     * @param server 当前服务器
     */
    @Override
    public final void reload(MinecraftServer server) {
        ensureLoaded(server);
        onReload(server);
        markDirty();
    }

    /**
     * {@code /ec c reload} 用：<b>真的从磁盘重读</b>。
     *
     * <p>先把内存里的改动 flush 出去并等它落地 —— 否则重新读盘读到的是写之前的旧内容，
     * 等于把玩家刚改的东西回滚掉；读完之后执行 {@link #onDiskLoad} 钩子。
     *
     * <p>{@code final} 原因同 {@link #reload}：顺序统一，扩展点只有钩子。
     *
     * @param server 当前服务器
     */
    @Override
    public final void reloadFromDisk(MinecraftServer server) {
        flushAndAwait(server, RELOAD_AWAIT_MS);
        forceLoad(server);
        onDiskLoad(server);
    }

    /**
     * 挂上 server；没加载过（或换了一台 server）就读一次盘。
     *
     * <p>server 相同且已加载时是纯粹的两次字段比较，随便调 —— {@code RuleConfig.getOrCreate}
     * 这种「每改一次规则都会走一遍」的路径依赖这个特性。
     *
     * @param server 目标服务器；可以为 {@code null}
     */
    public final void ensureLoaded(MinecraftServer server) {
        if (loaded && this.server == server) {
            return;
        }
        forceLoad(server);
    }

    /**
     * 无条件读一次盘并标记为已加载（{@link #ensureLoaded} 的「不做判断」版本）。
     *
     * <p>给「必须重新读磁盘」的场景用：{@code /ec c reload}，以及迁移
     * （迁移会整份重写文件，必须先读到最新内容）。
     *
     * @param server 目标服务器；{@code null} 时子类的 {@link #load()} 会读到「没有文件」并走默认值
     */
    protected final void forceLoad(MinecraftServer server) {
        this.server = server;
        load();
        loaded = true;
    }

    /**
     * 停服收尾：提交未落盘改动、摘掉 server、清脏、清内存。
     *
     * <p>摘 server 有两个原因：静态字段不该攥着已停服的服务器（单人反复换存档即泄漏），
     * 而且 server 为空时 {@link #getFilePath()} 返回 {@code null}，
     * 之后任何迟到的 flush 都不会往已卸载的存档里补写内容。
     *
     * <p>{@link #clearDirty()} 会顺手把它从脏队列里摘掉，所以复位之后不会再被 tick 扫到。
     */
    public final void reset() {
        flush();
        this.server = null;
        this.loaded = false;
        clearDirty(); // 这里会顺手从 DIRTY_SET 移除
        clearData();
    }

    /**
     * 等这份文件真正写到磁盘（读盘前的精确栅栏）。
     *
     * <p>超时只记日志并返回：命令 / 重载不该被一次慢磁盘卡死。
     *
     * @param server    要等的存档；{@code null} 时只 flush 不等待
     * @param timeoutMs 最长等待毫秒数
     */
    public final void flushAndAwait(MinecraftServer server, long timeoutMs) {
        flush();
        if (server == null) return;
        try {
            AsyncConfigManager.awaitFile(ConfigPaths.world(server, savePath()), timeoutMs);
        } catch (Exception e) {
            log.warn("等待 {} 落盘失败（继续按内存内容处理）：", configName(), e);
        }
    }

    // ==============================================================
    // 静态驱动（停服兜底）
    // ==============================================================

    /**
     * 停服前：把所有实例的改动一次性交给异步写线程（不阻塞）。
     *
     * <p>这里遍历 ALL 而不是脏队列，是为了「宁可多看一眼也不漏写」：不脏的实例
     * 在 {@code flush()} 里会被脏标记挡住，等于极速 return，代价可以忽略。
     */
    public static void flushAll() {
        for (AbstractWorldConfig config : ALL) {
            config.flush();
        }
    }

    /**
     * 停服后：把所有实例复位（flush → 摘 server → 清脏 → 清数据）。
     *
     * <p>复位之后静态表里只剩「空壳」实例，下一次开服会由 {@link #reload} 重新挂上 server。
     */
    public static void resetAll() {
        for (AbstractWorldConfig config : ALL) {
            config.reset();
        }
    }

    // ==============================================================
    // 基类接线
    // ==============================================================

    /**
     * {@inheritDoc}
     *
     * <p>交给 {@link #currentFile()}：按当前 server 现算，server 为空时返回 {@code null}，
     * 父类的 {@code flush()} 会因此直接空转（脏标记保留）。
     *
     * @return 目标文件路径；没有 server 时为 {@code null}
     */
    @Override
    protected final Path getFilePath() {
        return currentFile();
    }

    /**
     * 给子类 {@link #load()} 与 {@link #getFilePath()} 用的「当前该读/写的文件」。
     *
     * @return {@code <world>/<savePath()>}；没有挂 server 时为 {@code null}
     */
    protected final Path currentFile() {
        MinecraftServer s = server;
        return s == null ? null : ConfigPaths.world(s, savePath());
    }

    /**
     * 提交落盘。异常不外泄：失败时脏标记保留，下一轮 {@link #tickAll()} 自动重试。
     *
     * <p>{@code final}：四个配置的失败语义必须一致，子类不允许改成「抛异常」或「吞掉并清脏」。
     */
    @Override
    public final void flush() {
        try {
            super.flush();
        } catch (Exception e) {
            log.error("提交 {} 写盘失败，保留待写入标记，下一轮重试：", configName(), e);
        }
    }
}