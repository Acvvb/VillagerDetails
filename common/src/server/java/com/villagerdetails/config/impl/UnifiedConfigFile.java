package com.villagerdetails.config.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.util.ConfigPaths;
import com.villagerdetails.config.impl.util.JsonIO;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

public final class UnifiedConfigFile implements ReloadableConfig {

    private UnifiedConfigFile() {
    }

    public static final UnifiedConfigFile INSTANCE = new UnifiedConfigFile();

    private static final Logger log = LogManager.getLogger(UnifiedConfigFile.class);
    private static final String SAVE_PATH = "villagerdetails/config.json";

    /**
     * 配置版本标记的键名：写在 root 顶层，和各个子配置的 section 平级。
     *
     * <p>双下划线包起来是为了不和任何 section 名撞车；真实版本由
     * {@code com.villagerdetails.config.migration.MigrationManager} 负责比对与推进。
     */
    public static final String CONFIG_VERSION_KEY = "__configVersion__";

    private static volatile MinecraftServer server;
    private static volatile boolean loaded;
    private static JsonObject root = new JsonObject();

    @Override
    public String configName() {
        return "UnifiedConfigFile";
    }

    /**
     * 统一注册的入口：
     * <ol>
     *   <li>先把整份文件读进内存（幂等）；</li>
     *   <li>依次驱动每个子配置从内存 root 加载；</li>
     *   <li>最后写一次盘，保证文件存在、骨架完整。</li>
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
        save();
    }

    /**
     * 强制从磁盘重读整份配置（{@code /ec c reload} 用）。
     *
     * <p>与 {@link #reload(MinecraftServer)} 的三点区别：
     * <ul>
     *     <li>跳过 {@link #ensureLoaded} 的「同一 server 直接 return」短路，<b>真的去读文件</b>；</li>
     *     <li>读完把新的 root 灌进所有子配置，玩家手改的内容才会生效；</li>
     *     <li><b>不在末尾 save()</b> —— 否则刚读进来的内容会立刻被整份写回去，
     *         手改等于白改；子配置自己补默认值时各按各的脏标记 save（写的也是刚读到的内容，安全）。</li>
     * </ul>
     */
    public static synchronized void reloadFromDisk(MinecraftServer server) {
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
     * 让所有「规则侧子配置」重新从内存 root 应用一遍；单份失败不影响其它份。
     *
     * <p>刻意不在这里 save：正常重载路径由 {@link #reload(MinecraftServer)} 收尾落盘，
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

    public static synchronized void reset() {
        server = null;
        loaded = false;
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
     * 写入配置版本（只改内存；是否落盘由调用方决定）。
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

    public static synchronized void save() {
        MinecraftServer s = server;
        if (s == null) return;
        JsonIO.safeWrite(ConfigPaths.world(s, SAVE_PATH), root);
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