package com.villagerdetails.config.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.section.item.*;
import com.villagerdetails.config.impl.util.ConfigPaths;
import com.villagerdetails.config.impl.util.JsonIO;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;

public final class UnifiedConfigFile implements ReloadableConfig {

    private UnifiedConfigFile() {
    }

    public static final UnifiedConfigFile INSTANCE = new UnifiedConfigFile();

    private static final Logger LOGGER = LogManager.getLogger(UnifiedConfigFile.class);
    private static final String SAVE_PATH = "villagerdetails/config.json";

    private static volatile MinecraftServer server;
    private static volatile boolean loaded;
    private static JsonObject root = new JsonObject();

    /**
     * 由统一文件驱动的所有子配置。
     *
     * <p>新增一个子配置，只需在这里加一行；不用改 {@code ConfigRegistry}。
     * 它们的 {@code reload(server)} 会按顺序被调用，读的都是同一个内存 root。
     */
    private static final List<ReloadableConfig> CHILDREN = List.of(
            ServerLangConfig.INSTANCE,
            NoSqueezeConfig.INSTANCE,
            BlockMiningResistanceConfig.INSTANCE,
            BlockExplosionResistanceConfig.INSTANCE,
            BlockCollectableConfig.INSTANCE,
            PortalBindingConfig.INSTANCE
    );

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
     */
    @Override
    public void reload(MinecraftServer server) {
        ensureLoaded(server);
        for (ReloadableConfig child : CHILDREN) {
            try {
                child.reload(server);
            } catch (Exception e) {
                LOGGER.error("子配置 {} 重载失败", child.configName(), e);
            }
        }
        save();
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
            LOGGER.info("已加载统一配置：{}", file);
        } catch (Exception e) {
            LOGGER.error("加载统一配置失败：{}", file, e);
        }
    }
}