package com.villagerdetails.config.impl.util;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        Files.writeString(file, inlineNumberArrays(GSON.toJson(root)), StandardCharsets.UTF_8);
    }

    /**
     * 「带换行的纯数字数组」——注意必须包含换行才算，字符串里的换行是转义的，所以碰不到字符串内容。
     */
    private static final Pattern MULTILINE_NUMBER_ARRAY = Pattern.compile(
            "\\[\\s*\\n(?:\\s*-?\\d+(?:\\.\\d+)?\\s*,\\s*\\n)*\\s*-?\\d+(?:\\.\\d+)?\\s*\\n\\s*]");

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /**
     * 把纯数字数组压回一行：{@code [-2278, 77, -2098]}。
     *
     * <p>Gson 的 {@code setPrettyPrinting()} 连数组也一个元素一行，坐标这种三元素数组会摊成三行，
     * 读起来很难受。这里只收拾「每个数字独占一行」的数字数组：
     * <ul>
     *   <li>JSON 字符串里的换行一定是转义过的（{@code \n}），所以这个模式匹配不到字符串内容；</li>
     *   <li>字符串数组、空数组、已经在一行上的数组都不动。</li>
     * </ul>
     */
    private static String inlineNumberArrays(String json) {
        Matcher arrays = MULTILINE_NUMBER_ARRAY.matcher(json);
        StringBuilder out = new StringBuilder(json.length());
        while (arrays.find()) {
            Matcher numbers = NUMBER.matcher(arrays.group());
            StringBuilder inline = new StringBuilder("[");
            while (numbers.find()) {
                if (inline.length() > 1) {
                    inline.append(", ");
                }
                inline.append(numbers.group());
            }
            inline.append(']');
            arrays.appendReplacement(out, Matcher.quoteReplacement(inline.toString()));
        }
        arrays.appendTail(out);
        return out.toString();
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