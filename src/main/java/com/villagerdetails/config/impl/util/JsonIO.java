package com.villagerdetails.config.impl.util;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON 文件读写工具。三个配置类都用同一套 Gson 配置和同样的读写模式。
 *
 * <p>只负责「读成 JsonObject / 把 JsonElement 写成文件」，不管 dirty、不管合并、
 * 不管缓存 —— 那些是各配置自己的策略。
 */
public final class JsonIO {

    private JsonIO() {
    }

    private static final Logger LOGGER = LogManager.getLogger(JsonIO.class);

    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * 读 JSON 对象。
     *
     * <p>文件不存在 / 根不是对象 → 返回空 {@code JsonObject}（不抛）；
     * IO / 解析错误 → 抛给调用方，让各配置决定兜底策略。
     */
    public static JsonObject readObject(Path file) throws Exception {
        if (file == null || !Files.exists(file)) {
            return new JsonObject();
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement e = JsonParser.parseReader(r);
            return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : new JsonObject();
        }
    }

    /**
     * 写 JSON；自动建父目录。
     */
    public static void writeObject(Path file, JsonElement root) throws Exception {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        }
    }

    /**
     * 内部用：写失败时统一打日志，调用方不用重复 try/catch。
     */
    public static void safeWrite(Path file, JsonElement root) {
        try {
            writeObject(file, root);
        } catch (Exception e) {
            LOGGER.error("写入 JSON 失败：{}", file, e);
        }
    }
}