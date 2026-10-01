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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块挖掘抗性配置：{@code 方块注册id → 破坏时间}，以 JSON 形式保存在存档目录下
 * {@code villagerdetails/block_mining_resistance.json}。
 */
public final class BlockMiningResistanceConfig implements ReloadableConfig {

    private BlockMiningResistanceConfig() {
    }

    public static final BlockMiningResistanceConfig INSTANCE = new BlockMiningResistanceConfig();

    private static final Logger LOGGER = LogManager.getLogger(BlockMiningResistanceConfig.class);
    private static final String SAVE_PATH = "villagerdetails/block_mining_resistance.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static volatile MinecraftServer server;
    private static final Map<Identifier, Float> RESISTANCES = new ConcurrentHashMap<>();

    @Override
    public String configName() {
        return "BlockMiningResistanceConfig";
    }

    @Override
    public void reload(MinecraftServer server) {
        BlockMiningResistanceConfig.server = server;
        loadFromWorld(server);
    }

    // ==============================================================
    // 业务方法
    // ==============================================================

    /**
     * 返回该方块的抗性覆盖值；未配置返回 null。
     */
    public static Float get(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return RESISTANCES.get(id);
    }

    /** 当前配置快照（仅含调整过的方块）。 */
    public static Map<Identifier, Float> snapshot() {
        return Map.copyOf(RESISTANCES);
    }

    public static void put(Identifier id, float value) {
        RESISTANCES.put(id, value);
        save();
    }

    public static void reset() {
        RESISTANCES.clear();
        save();
    }

    /**
     * 序列化为同步字符串：{@code id=value,id=value,...}
     */
    public static String toSyncString() {
        if (RESISTANCES.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Identifier, Float> e : RESISTANCES.entrySet()) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 客户端：用同步字符串覆盖内存（不落盘）。
     */
    public static void applyFromSyncString(String data) {
        RESISTANCES.clear();
        if (data == null || data.isEmpty()) return;
        for (String part : data.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq <= 0 || eq == p.length() - 1) continue;
            Identifier id = Identifier.tryParse(p.substring(0, eq).trim());
            if (id == null) continue;
            try {
                RESISTANCES.put(id, Float.parseFloat(p.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
                // 非法数值跳过
            }
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
        RESISTANCES.clear();
        if (!Files.exists(file)) {
            save();
            LOGGER.info("已生成默认配置：{}", file);
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                Identifier id = Identifier.tryParse(e.getKey());
                if (id == null || !e.getValue().isJsonPrimitive()) continue;
                try {
                    RESISTANCES.put(id, e.getValue().getAsFloat());
                } catch (Exception ignored) {
                    // 非法数值跳过
                }
            }
            LOGGER.info("已加载 {} 项方块挖掘抗性", RESISTANCES.size());
        } catch (Exception e) {
            LOGGER.error("加载方块挖掘抗性配置失败：{}", file, e);
        }
    }

    private static void save() {
        MinecraftServer s = server;
        if (s == null) return;
        Path file = getPath(s);
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            RESISTANCES.forEach((id, v) -> root.addProperty(id.toString(), v));
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (Exception e) {
            LOGGER.error("保存方块挖掘抗性配置失败：{}", file, e);
        }
    }
}
