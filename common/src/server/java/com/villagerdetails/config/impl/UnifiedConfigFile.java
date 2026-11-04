package com.villagerdetails.config.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.util.ConfigPaths;
import com.villagerdetails.rule.type.RuleType;
import com.villagerdetails.util.io.AbstractAsyncConfig;
import com.villagerdetails.util.io.AsyncConfigManager;
import com.villagerdetails.util.json.JsonIO;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 统一配置文件（存档内 {@code villagerdetails/config.json}）。
 *
 * <p>直接继承 {@link AbstractAsyncConfig}，所有的脏标记防抖、批量合并、异步落盘
 * 全部由底层基类接管，本类只需提供路径与数据。
 */
public final class UnifiedConfigFile extends AbstractAsyncConfig implements ReloadableConfig {

    // 全局唯一实例
    public static final UnifiedConfigFile INSTANCE = new UnifiedConfigFile();

    private static final Logger log = LogManager.getLogger(UnifiedConfigFile.class);
    private static final String SAVE_PATH = "villagerdetails/config.json";
    private static final long RELOAD_AWAIT_MS = 5000L;

    public static final String CONFIG_VERSION_KEY = "__configVersion__";

    private volatile MinecraftServer server;
    private volatile boolean loaded;
    private JsonObject root = new JsonObject();

    private UnifiedConfigFile() {
    }

    @Override
    protected Path getFilePath() {
        MinecraftServer s = server;
        return s == null ? null : ConfigPaths.world(s, SAVE_PATH);
    }

    @Override
    protected Object getDataToSave() {
        return root;
    }

    @Override
    public String configName() {
        return "UnifiedConfigFile";
    }

    @Override
    public void reload(MinecraftServer server) {
        ensureLoaded(server);
        reloadChildren(server);
        markDirty();
    }

    public static void reloadFromDisk(MinecraftServer server) {
        INSTANCE.flushAndAwait(server, RELOAD_AWAIT_MS);
        INSTANCE.server = server;
        INSTANCE.load();
        if (server == null) {
            log.warn("reloadFromDisk 收到 null server：只重置了内存 root，没有读盘");
        } else {
            log.info("已从磁盘重读统一配置：{} 个 section", INSTANCE.root.keySet().size());
        }
        reloadChildren(server);
        INSTANCE.loaded = true;
    }

    public void flushAndAwait(MinecraftServer server, long timeoutMs) {
        flush();
        if (server == null) return;
        try {
            AsyncConfigManager.awaitFile(ConfigPaths.world(server, SAVE_PATH), timeoutMs);
        } catch (Exception e) {
            log.warn("等待统一配置落盘失败（继续按内存内容重载）：", e);
        }
    }

    private static void reloadChildren(MinecraftServer server) {
        for (ReloadableConfig child : RuleType.getReloadableConfigs()) {
            try {
                child.reload(server);
            } catch (Exception e) {
                log.error("子配置 {} 重载失败", child.configName(), e);
            }
        }
    }

    public static void reset() {
        INSTANCE.flush();
        INSTANCE.server = null;
        INSTANCE.loaded = false;
        INSTANCE.clearDirty();
        INSTANCE.root = new JsonObject();
    }

    public static void ensureLoaded(MinecraftServer s) {
        if (INSTANCE.loaded && INSTANCE.server == s) {
            return;
        }
        INSTANCE.server = s;
        INSTANCE.load();
        INSTANCE.loaded = true;
    }

    private void load() {
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

    // ============================================================
    // 数据读写
    // ============================================================

    public static JsonObject getSection(String name) {
        if (INSTANCE.root.has(name) && INSTANCE.root.get(name).isJsonObject()) {
            return INSTANCE.root.getAsJsonObject(name);
        }
        JsonObject section = new JsonObject();
        INSTANCE.root.add(name, section);
        return section;
    }

    public static void setSection(String name, JsonElement value) {
        INSTANCE.root.add(name, value);
    }

    // ============================================================
    // 迁移辅助
    // ============================================================

    public static boolean fileExists(MinecraftServer server) {
        return server != null && Files.exists(ConfigPaths.world(server, SAVE_PATH));
    }

    public static void loadForMigration(MinecraftServer server) {
        INSTANCE.server = server;
        INSTANCE.load();
        INSTANCE.loaded = true;
    }

    public static @Nullable String storedConfigVersion() {
        JsonElement element = INSTANCE.root.get(CONFIG_VERSION_KEY);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static void setConfigVersion(String version) {
        INSTANCE.root.addProperty(CONFIG_VERSION_KEY, version);
    }

    public static void clearAllSections() {
        INSTANCE.root = new JsonObject();
    }

    public static boolean hasContent() {
        for (String key : INSTANCE.root.keySet()) {
            if (!CONFIG_VERSION_KEY.equals(key)) {
                return true;
            }
        }
        return false;
    }

    public void markDirty() {
        super.markDirty();
    }

    public void flush() {
        try {
            super.flush();
        } catch (Exception e) {
            log.error("提交统一配置写盘失败，保留待写入标记，下一 tick 重试：", e);
        }
    }
}