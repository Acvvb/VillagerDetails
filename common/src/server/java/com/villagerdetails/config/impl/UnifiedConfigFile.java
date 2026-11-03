package com.villagerdetails.config.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.util.ConfigPaths;
import com.villagerdetails.rule.type.RuleType;
import com.villagerdetails.util.io.AbstractAsyncConfig;
import com.villagerdetails.util.io.AsyncConfigManager;
import com.villagerdetails.util.io.AsyncConfigWriter;
import com.villagerdetails.util.io.JsonIO;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 统一配置文件（存档内 {@code villagerdetails/config.json}）。
 *
 * <h2>写入模型：脏标记 + 定期合并落盘</h2>
 *
 * <p>每个子配置改一项就调一次 {@link #markDirty()}，<b>只置脏标记、不碰磁盘</b>；
 * 真正落盘统一由 {@link #flush()} 完成，它由两处驱动：
 * <ul>
 *     <li>{@code END_SERVER_TICK} —— 同一 tick 里的 N 次修改合并成 1 次写；</li>
 *     <li>停服收尾（{@code ServerThreadManager} 的 pre-shutdown 钩子）—— 把最后一次改动刷出去。</li>
 * </ul>
 *
 * <p>为什么改：以前 {@code save()} 是「改一项 → 整份重写一次」。迁移一次要连写 4 次、
 * 批量入名单是 N 次、传送门回写会在玩家传送的那一帧做整份文件写盘。现在这些全部收敛成「每 tick 最多一次」。
 *
 * <p><b>树仍然只允许在主线程碰</b>：{@link #getSection} 会把内部 {@link JsonObject} 直接交出去
 * （{@code ServerLangConfig} 等会原地改它），而 Gson 的树不是线程安全的。
 * 所以 {@link #markDirty()} / {@link #flush()} /  都是 {@code synchronized}。
 *
 * <p><b>I/O 已经移出主线程</b>：{@link #flush()} 只做主线程内的「序列化成 String」，
 * 真正的写盘交给 {@link AsyncConfigWriter} 的专职单写者线程。因此 {@code flush()} 里
 * <b>不允许</b>再出现任何文件操作 —— 那会重新把 I/O 拉回 tick 里。
 */
public final class UnifiedConfigFile implements ReloadableConfig {

    private UnifiedConfigFile() {
    }

    public static final UnifiedConfigFile INSTANCE = new UnifiedConfigFile();

    private static final Logger log = LogManager.getLogger(UnifiedConfigFile.class);
    private static final String SAVE_PATH = "villagerdetails/config.json";

    /**
     * 重载前等待异步写落地的上限。
     *
     * <p>只作用于玩家主动执行的 {@code /ec c reload}（低频命令），等的是本地几次小文件写的完成，
     * 正常情况下是毫秒级；超时只记 ERROR 并继续，不会把命令卡死。
     */
    private static final long RELOAD_AWAIT_MS = 5000L;

    /**
     * 配置版本标记的键名：写在 root 顶层，和各个子配置的 section 平级。
     *
     * <p>双下划线包起来是为了不和任何 section 名撞车；真实版本由
     * {@code com.villagerdetails.config.migration.MigrationManager} 负责比对与推进。
     */
    public static final String CONFIG_VERSION_KEY = "__configVersion__";

    private static volatile MinecraftServer server;
    private static volatile boolean loaded;

    /**
     * 内存里的配置树（含全部 section）。只在主线程访问，且全程在类锁里。
     */
    private static JsonObject root = new JsonObject();

    /**
     * 保存状态机（脏标记 / 防抖 / 批量 / 提交）的<b>组合</b>实例。
     *
     * <p>本类是全 {@code static} 的工具门面，<b>没法继承</b>
     * {@link com.villagerdetails.util.io.AbstractAsyncConfig}（所有调用点都写
     * {@code UnifiedConfigFile.markDirty()} / {@code flush()}，改成实例方法会波及
     * 十几个子配置、迁移器和事件类）。所以这里<b>组合</b>一个匿名子类实例：
     * 状态机逻辑全部由基类提供，本类只回答「写哪个文件 / 写什么」。
     *
     * <p>并发约定与改造前一致：静态方法 {@link #markDirty()} / {@link #flush()} /
     * {@link #getSection} / {@link #setSection} 都拿类锁，因此序列化 {@code root} 时
     * 不会有别的线程在改它（实际上全在主线程）。
     */
    private static final AbstractAsyncConfig WRITER = new AbstractAsyncConfig() {

        /**
         * 目标路径。{@code server} 还没挂上（启动前 / 停服后）时返回 {@code null}：
         * 基类会原样保留脏标记并直接返回，不写盘。
         */
        @Override
        protected Path getFilePath() {
            MinecraftServer s = server;
            return s == null ? null : ConfigPaths.world(s, SAVE_PATH);
        }

        /**
         * 直接交整棵内存树：Gson 在<b>调用线程</b>（主线程）上立刻把它序列化成 String。
         */
        @Override
        protected Object getDataToSave() {
            return root;
        }
    };

    @Override
    public String configName() {
        return "UnifiedConfigFile";
    }

    /**
     * 统一注册的入口：
     * <ol>
     *   <li>先把整份文件读进内存（幂等）；</li>
     *   <li>依次驱动每个子配置从内存 root 加载；</li>
     *   <li>标脏，交给本 tick 末尾的 {@link #flush()} 落盘（保证文件存在、骨架完整）。</li>
     * </ol>
     *
     * <p>启动期走这里：{@link #ensureLoaded} 会在「同一个 server」时短路，
     * 避免每次生命周期回调都重读磁盘。玩家手动 {@code /ec c reload} 走
     * {@link #reloadFromDisk(MinecraftServer)}。
     */
    @Override
    public void reload(MinecraftServer server) {
        ensureLoaded(server);
        reloadChildren(server);
        // 以前这里直接 save()：每次开服无条件整份写一次。现在只标脏，和迁移 / 子配置补默认值合并成一次
        markDirty();
    }

    /**
     * 强制从磁盘重读整份配置（{@code /ec c reload} 用）。
     *
     * <p>与 {@link #reload(MinecraftServer)} 的三点区别：
     * <ul>
     *     <li>跳过 {@link #ensureLoaded} 的「同一 server 直接 return」短路，<b>真的去读文件</b>；</li>
     *     <li>读完把新的 root 灌进所有子配置，玩家手改的内容才会生效；</li>
     *     <li><b>不在末尾标脏</b> —— 否则刚读进来的内容会立刻被整份写回去，
     *         手改等于白改；子配置自己补默认值时各按各的脏标记，最后合并落盘。</li>
     * </ul>
     *
     * <p>读盘<b>之前</b>必须先过 {@link #flushAndAwait} 那道栅栏：否则「刚改完还没到 tick 末尾」
     * 的那次改动会被这轮读盘直接覆盖掉，等于玩家白改（以前是同步写，改动早就在盘上了）。
     */
    public static synchronized void reloadFromDisk(MinecraftServer server) {
        // 先把内存改动提交<b>并等它真正落地</b>：不等就回读，读到的还是写之前的内容（等于这次重载白做）
        flushAndAwait(server, RELOAD_AWAIT_MS);
        // load() 读的是静态 server 字段，先把目标服务器挂上
        UnifiedConfigFile.server = server;
        load();
        if (server == null) {
            log.warn("reloadFromDisk 收到 null server：只重置了内存 root，没有读盘");
        } else {
            log.info("已从磁盘重读统一配置：{} 个 section", root.keySet().size());
        }
        reloadChildren(server);
        loaded = true;
    }

    /**
     * 把内存里的改动<b>提交给写线程，并等到它真正落到磁盘</b> —— 给「读盘之前」的栅栏用。
     *
     * <p>为什么需要单独一个入口：{@link #flush()} 只是把内容入队，紧接着读盘会读到写之前的
     * 旧内容。{@code /ec c reload} 的读盘发生在 {@code MigrationManager.migrateIfNeeded} 里
     * （它比 {@link #reloadFromDisk} 更早），所以那道栅栏<b>不能只放在 reloadFromDisk 里</b>，
     * 否则「同 tick 内改过、还没到 tick 末尾」的改动会先被迁移读盘覆盖，再被 reloadFromDisk
     * 的 flush 把这份旧内容写回磁盘 —— 内存和磁盘一起丢。
     *
     * <p>路径解析失败（停服收尾阶段存档目录不可用）只记日志、不抛：这只是个栅栏，
     * 拿不到路径就按内存内容继续。
     *
     * @param server    目标服务器；{@code null} 时只 flush（没有路径可等）
     * @param timeoutMs 最长等待毫秒数
     */
    public static synchronized void flushAndAwait(MinecraftServer server, long timeoutMs) {
        flush();
        if (server == null) {
            return;
        }
        try {
            AsyncConfigManager.awaitFile(ConfigPaths.world(server, SAVE_PATH), timeoutMs);
        } catch (Exception e) {
            log.warn("等待统一配置落盘失败（继续按内存内容重载）：", e);
        }
    }

    /**
     * 让所有「规则侧子配置」重新从内存 root 应用一遍；单份失败不影响其它份。
     *
     * <p>刻意不在这里标脏：正常重载路径由 {@link #reload(MinecraftServer)} 标脏，
     * 从磁盘重载路径则希望磁盘内容保持不变（直到下一次真正的修改）。
     */
    private static void reloadChildren(MinecraftServer server) {
        for (ReloadableConfig child : RuleType.getReloadableConfigs()) {
            try {
                child.reload(server);
            } catch (Exception e) {
                log.error("子配置 {} 重载失败", child.configName(), e);
            }
        }
    }

    /**
     * 清空内存态（停服用）。
     *
     * <p><b>先刷盘再清</b>：{@code server} 一旦置空，{@link #flush()} 就找不到存档路径了，
     * 之后任何标脏都只能被丢弃。
     *
     * <p>清完 root 之后要显式把脏标记也清掉，否则「作废的旧内容」会在下次开服时被写出去。
     */
    public static synchronized void reset() {
        flush();
        server = null;
        loaded = false;
        WRITER.clearDirty();
        root = new JsonObject();
    }

    public static synchronized void ensureLoaded(MinecraftServer s) {
        if (loaded && server == s) return;
        server = s;
        load();
        loaded = true;
    }

    public static synchronized JsonObject getSection(String name) {
        if (root.has(name) && root.get(name).isJsonObject()) {
            return root.getAsJsonObject(name);
        }
        JsonObject section = new JsonObject();
        root.add(name, section);
        return section;
    }

    public static synchronized void setSection(String name, JsonElement value) {
        root.add(name, value);
    }

    // ==============================================================
    // 落盘：脏标记 + 合并写
    // ==============================================================

    /**
     * 标记「配置有改动，需要落盘」。状态由 {@link com.villagerdetails.util.io.AbstractAsyncConfig} 维护。
     *
     * <p>这是<b>所有子配置修改后的统一出口</b>。它不碰磁盘，同一 tick 内被调多少次都只会在
     * {@link #flush()} 里提交一次。
     */
    public static synchronized void markDirty() {
        WRITER.markDirty();
    }

    /**
     * 把累积的改动<b>提交给异步写线程</b>；没有改动时是空操作。
     *
     * <p>由 tick 末尾和停服收尾调用，<b>必须在主线程</b>（它会遍历整棵 {@code root}）。
     *
     * <p>主线程只做序列化（纯 CPU），磁盘 I/O 由 {@code AsyncConfigWriter} 的单写者线程完成 ——
     * 本方法<b>不会阻塞在磁盘上</b>。
     *
     * <p>失败处理：序列化 / 提交失败都保留脏标记，下一 tick 重试；
     * 真正写盘失败由 {@code AsyncConfigWriter} 记 ERROR 并计数（不重试）。
     *
     * <p><b>本方法绝不向外抛异常</b>：它在 {@code END_SERVER_TICK} 里被直接调用，
     * 抛出去就会顺着 tick 事件链把服务器掀翻。连「解析存档路径」都算在内。
     */
    public static synchronized void flush() {
        try {
            WRITER.flush();
        } catch (Exception e) {
            // getFilePath() 在停服收尾阶段可能因为存档目录不可用而抛；保留脏标记，下轮重试
            log.error("提交统一配置写盘失败，保留待写入标记，下一 tick 重试：", e);
        }
    }

    // ==============================================================
    // 配置版本（供 config.migration.MigrationManager 使用）
    // ==============================================================

    /**
     * 存档里有没有统一配置文件。
     *
     * <p>迁移要区分「全新存档」（没有文件 / 文件是空的）和「老存档」：
     * 前者不需要重置，盖个版本号就行；后者才走兼容判定。
     */
    public static boolean fileExists(MinecraftServer server) {
        return server != null && Files.exists(ConfigPaths.world(server, SAVE_PATH));
    }

    /**
     * 迁移专用入口：把磁盘内容读进内存 root 并标成「已加载」，<b>不驱动子配置</b>。
     *
     * <p>迁移需要在子配置读到数据之前直接改 root（清空 / 改字段 / 盖版本号），
     * 所以这里只读文件；迁移结束后的正常 {@link #reload(MinecraftServer)}
     * 会因 {@link #ensureLoaded} 短路而复用这份 root，把结果交给各子配置。
     */
    public static synchronized void loadForMigration(MinecraftServer server) {
        UnifiedConfigFile.server = server;
        load();
        loaded = true;
    }

    /**
     * 存档里记录的配置版本；没有该字段 / 不是字符串 / 空白 → {@code null}。
     */
    public static synchronized @Nullable String storedConfigVersion() {
        JsonElement element = root.get(CONFIG_VERSION_KEY);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 写入配置版本（只改内存；是否落盘由调用方 {@link #markDirty()} 决定）。
     */
    public static synchronized void setConfigVersion(String version) {
        root.addProperty(CONFIG_VERSION_KEY, version);
    }

    /**
     * 清空全部子配置 section（版本标记一并清掉，由调用方随后重新盖）。
     */
    public static synchronized void clearAllSections() {
        root = new JsonObject();
    }

    /**
     * root 里除了版本标记之外还有没有内容（判断「全新存档」用）。
     */
    public static synchronized boolean hasContent() {
        for (String key : root.keySet()) {
            if (!CONFIG_VERSION_KEY.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static void load() {
        root = new JsonObject();
        MinecraftServer s = server;
        if (s == null) return;
        Path file = ConfigPaths.world(s, SAVE_PATH);
        try {
            root = JsonIO.readObject(file);
            log.info("已加载统一配置：{}", file);
        } catch (Exception e) {
            log.error("加载统一配置失败：{}", file, e);
        }
    }
}
