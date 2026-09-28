package com.villagerdetails.config;

import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ServerLangConfig {

    private ServerLangConfig() {}

    private static final Logger LOGGER = LogManager.getLogger("ServerLangConfig");

    private static final String SAVE_PATH = "villagerdetails/server_lang.json";

    /** 默认值——写死 */
    private static final List<String> DEFAULT_PRELOAD = List.of("en_us", "zh_cn");
    private static final String DEFAULT_LANG = "zh_cn";

    private static volatile List<String> preloadLanguage = DEFAULT_PRELOAD;
    private static volatile String defaultLang = DEFAULT_LANG;

    public static List<String> getPreloadLanguages() { return preloadLanguage; }
    public static String getDefaultLang()        { return defaultLang; }

    // ==============================================================
    // 加载 / 保存
    // ==============================================================

    public static void loadFromWorld(MinecraftServer server) {
        loadFrom(getPath(server));
    }

    public static void loadFrom(Path file) {
        if (!Files.exists(file)) {
            writeDefault(file);
            LOGGER.info("[ServerLangConfig] 已生成默认配置：{}", file);
            return;
        }

        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();

            if (root.has("preload") && root.get("preload").isJsonArray()) {
                List<String> list = new ArrayList<>();
                for (JsonElement e : root.getAsJsonArray("preload")) {
                    if (e.isJsonPrimitive()) list.add(e.getAsString());
                }
                if (!list.isEmpty()) preloadLanguage = List.copyOf(list);
            }

            if (root.has("default")) {
                defaultLang = root.get("default").getAsString();
            }

            LOGGER.info("[ServerLangConfig] preload={}, default={}", preloadLanguage, defaultLang);
        } catch (Exception e) {
            LOGGER.error("[ServerLangConfig] 加载失败，使用默认值", e);
        }
    }

    private static Path getPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(SAVE_PATH);
    }

    private static void writeDefault(Path file) {
        try {
            Files.createDirectories(file.getParent());

            JsonObject root = new JsonObject();

            JsonArray preload = new JsonArray();
            DEFAULT_PRELOAD.forEach(preload::add);
            root.add("preload", preload);

            root.addProperty("default", DEFAULT_LANG);

            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                        .create().toJson(root, w);
            }
        } catch (Exception e) {
            LOGGER.error("[ServerLangConfig] 写入默认失败", e);
        }
    }
}