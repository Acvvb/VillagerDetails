package com.villagerdetails.util.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON 读写工具：<b>序列化</b> + <b>原子写</b>。
 *
 * <p>位于 {@code main} 公共区，<b>绝不依赖任何服务端 / 客户端专属类型</b>
 * （不出现 {@code MinecraftServer}、{@code ConfigPaths} 之类），只认标准 {@link Path}。
 * 服务端的存档配置、客户端的渲染设置共用这一份实现。
 *
 * <p>本类只管「字节怎么落盘」，不管调度：
 * <ul>
 *     <li>要异步（推荐）：{@link AsyncConfigManager#save} —— 主线程序列化、后台单写者落盘；</li>
 *     <li>要立刻同步写（少见）：{@link #writeObject} / {@link #safeWrite}，会阻塞调用线程。</li>
 * </ul>
 */
public final class JsonIO {

    private JsonIO() {
    }

    private static final Logger log = LogManager.getLogger(JsonIO.class);

    /**
     * 全项目统一的 Gson 配置：缩进格式化 + 不转义 HTML 字符（中文与 {@code <>&} 保持原样）。
     */
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    // ==============================================================
    // 读
    // ==============================================================

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

    // ==============================================================
    // 序列化
    // ==============================================================

    /**
     * 把任意对象序列化成最终要落盘的文本（pretty printing + 数字数组压成一行）。
     *
     * <p>参数是 {@link Object} 而不是 {@code JsonElement}：Gson 能直接吃
     * {@code JsonObject}、{@code Map}、普通 POJO ——调用方不用先手工拼一棵树。
     *
     * <p>这是<b>唯一</b>的序列化入口：同步写和异步写都走它，避免同一个文件
     * 因为「谁序列化的」不同而格式漂移。
     *
     * <p><b>必须在调用方线程（服务端 = 主线程）执行</b>：它会遍历整棵树，
     * 而 Gson 的树和普通集合都不是线程安全的。
     */
    public static String serialize(Object data) {
        return inlineNumberArrays(GSON.toJson(data));
    }

    // ==============================================================
    // 写（同步；异步请用 AsyncConfigManager）
    // ==============================================================

    /**
     * 同步写一个 JSON；自动建父目录。<b>会阻塞调用线程</b>。
     *
     * <p><b>原子写</b>：内容先写进同目录的临时文件，再整体替换目标文件。中途崩服 / 断电时，
     * 目标文件要么还是上一份完整内容、要么已经是新的完整内容，不会留下写到一半的 JSON。
     *
     * <p>日常请用 {@link AsyncConfigManager#save}；这里只在「必须立刻可见」的场景才需要。
     */
    public static void writeObject(Path file, Object data) throws Exception {
        writeString(file, serialize(data));
    }

    /**
     * 原子写一段文本（临时文件 + 整体替换）；JSON 之外的写入方也能直接用。
     *
     * <p><b>临时文件名带随机 UUID</b>：以前是固定的 {@code 文件名.tmp}，只要有两个写者
     * 同时写同一个文件（异步写线程 + 某处同步兜底），就会出现「A 的 finally 删掉 B 正在写的临时文件」。
     * 现在每次写都是独立临时文件，即使将来出现并发写也不会互踩。
     */
    public static void writeString(Path file, String content) throws Exception {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);

        // 临时文件必须和目标同目录：只有同一文件系统内才谈得上原子替换
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + UUID.randomUUID());
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            moveInto(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 把临时文件替换成目标文件：优先原子移动，不行再退回普通覆盖移动。
     *
     * <p>退回的两类原因都会碰到：文件系统本身不支持原子替换（部分网络盘 / FAT），
     * 以及目标文件正被别的进程占着（Windows 上杀毒、索引服务很常见）。
     * 退回后只是"不再原子"，内容仍然是完整的。
     */
    private static void moveInto(Path tmp, Path file) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("原子替换失败，退回普通覆盖写入：{}（{}）", file, e.toString());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 内部用：写失败时统一打日志，调用方不用重复 try/catch。
     *
     * <p>同步兜底路径；异步路径的失败由 {@link AsyncConfigWriter} 统一计数并记录。
     */
    public static void safeWrite(Path file, Object data) {
        try {
            writeObject(file, data);
        } catch (Exception e) {
            log.error("写入 JSON 失败：{}", file, e);
        }
    }

    // ==============================================================
    // 格式化：把纯数字数组压回一行
    // ==============================================================

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
}
