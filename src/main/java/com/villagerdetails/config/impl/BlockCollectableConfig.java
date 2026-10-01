package com.villagerdetails.config.impl;

import com.google.gson.*;
import com.villagerdetails.config.ReloadableConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块可采集配置：方块注册 id 集合，以 JSON 形式保存在存档目录下
 * {@code villagerdetails/block_collectable.json}。
 */
public final class BlockCollectableConfig implements ReloadableConfig {

    private BlockCollectableConfig() {
    }

    public static final BlockCollectableConfig INSTANCE = new BlockCollectableConfig();

    private static final Logger LOGGER = LogManager.getLogger(BlockCollectableConfig.class);
    private static final String SAVE_PATH = "villagerdetails/block_collectable.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static volatile MinecraftServer server;
    private static final Set<Identifier> COLLECTABLES = ConcurrentHashMap.newKeySet();

    @Override
    public String configName() {
        return "BlockCollectableConfig";
    }

    @Override
    public void reload(MinecraftServer server) {
        BlockCollectableConfig.server = server;
        loadFromWorld(server);
    }

    // ==============================================================
    // 业务方法
    // ==============================================================

    public static boolean contains(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return COLLECTABLES.contains(id);
    }

    /** 当前配置快照（仅含调整过的方块，按 id 排序）。 */
    public static java.util.List<Identifier> snapshot() {
        return COLLECTABLES.stream()
                .sorted(java.util.Comparator.comparing(Identifier::toString))
                .toList();
    }

    public static void add(Identifier id) {
        COLLECTABLES.add(id);
        save();
    }

    public static void remove(Identifier id) {
        COLLECTABLES.remove(id);
        save();
    }

    public static void reset() {
        COLLECTABLES.clear();
        save();
    }

    /**
     * 序列化为同步字符串：{@code id,id,...}
     */
    public static String toSyncString() {
        if (COLLECTABLES.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Identifier id : COLLECTABLES) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    /**
     * 客户端：用同步字符串覆盖内存（不落盘）。
     */
    public static void applyFromSyncString(String data) {
        COLLECTABLES.clear();
        if (data == null || data.isEmpty()) return;
        for (String part : data.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            Identifier id = Identifier.tryParse(p);
            if (id != null) COLLECTABLES.add(id);
        }
    }

    // ==============================================================
    // 文件读写
    // ==============================================================

    private static void loadFromWorld(MinecraftServer server) {
        loadFrom(getPath(server));
    }

    private static Path getPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(SAVE_PATH);
    }

    private static void loadFrom(Path file) {
        COLLECTABLES.clear();
        if (!Files.exists(file)) {
            save();
            LOGGER.info("已生成默认配置：{}", file);
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                Identifier id = Identifier.tryParse(e.getKey());
                if (id != null) COLLECTABLES.add(id);
            }
            LOGGER.info("已加载 {} 项方块可采集", COLLECTABLES.size());
        } catch (Exception e) {
            LOGGER.error("加载方块可采集配置失败：{}", file, e);
        }
    }

    private static void save() {
        MinecraftServer s = server;
        if (s == null) return;
        Path file = getPath(s);
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            COLLECTABLES.forEach(id -> root.addProperty(id.toString(), true));
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (Exception e) {
            LOGGER.error("保存方块可采集配置失败：{}", file, e);
        }
    }
}
