package com.villagerdetails.config.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.base.AbstractWorldConfig;
import com.villagerdetails.config.impl.util.ConfigPaths;
import com.villagerdetails.rule.type.RuleType;
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
 * <p>生命周期与 IO 全部由 {@link AbstractWorldConfig} 提供，本类只回答「写哪个文件、读成什么、
 * 清空成什么」，外加一个统一配置特有的动作：<b>重载时要把所有子配置（各个 section）也带一遍</b>
 * （见 {@link #onReload}）。
 *
 * <p>文件内容是一棵 {@code section → JsonObject} 的树，各 section 自己通过
 * {@link #getSection(String)} / {@link #setSection(String, JsonElement)} 读写这棵树、
 * 然后 {@code UnifiedConfigFile.INSTANCE.markDirty()}，由 tick 末尾统一落盘。
 */
public final class UnifiedConfigFile extends AbstractWorldConfig {

    // 全局唯一实例
    public static final UnifiedConfigFile INSTANCE = new UnifiedConfigFile();

    private static final Logger log = LogManager.getLogger(UnifiedConfigFile.class);

    /** 相对存档根目录的文件位置：{@code <world>/villagerdetails/config.json}。 */
    private static final String SAVE_PATH = "villagerdetails/config.json";

    /** 记录「这份文件是哪个配置版本写出来的」的保留键，迁移靠它判断要不要重写。 */
    public static final String CONFIG_VERSION_KEY = "__configVersion__";

    /**
     * 内存里的整份配置树：{@code section 名 → 该 section 的对象}。
     *
     * <p>既是读写的唯一数据源，也是<b>落盘内容</b>。只在主线程访问
     * （{@code flush()} 就是串行化点，Gson 会在那一刻遍历它）。
     */
    private JsonObject root = new JsonObject();

    /**
     * 私有构造：全局只允许 {@link #INSTANCE} 存在一个实例。
     *
     * <p>构造时父类会把它登记进静态表，{@link AbstractWorldConfig#tickAll()} 因此自动覆盖到它。
     */
    private UnifiedConfigFile() {
    }

    // ============================================================
    // 父类要求的三件事 + 数据
    // ============================================================

    /**
     * {@inheritDoc}
     *
     * <p>本配置固定写在存档的 {@code villagerdetails/config.json}。
     */
    @Override
    protected String savePath() {
        return SAVE_PATH;
    }

    /**
     * {@inheritDoc}
     *
     * <p>直接交 {@link #root} 本身：顶层就是一个 {@code section → 对象} 的 JSON 对象，
     * 不需要再包一层。
     */
    @Override
    protected Object getDataToSave() {
        return root;
    }

    /**
     * {@inheritDoc}
     *
     * <p>注册名 / {@code /ec c reload <name>} 里用的名字。
     */
    @Override
    public String configName() {
        return "UnifiedConfigFile";
    }

    /**
     * 磁盘 → 内存：整份重读 {@code config.json}。
     *
     * <p>先重置成空树再读：读失败时内存里不会有「半份旧配置 + 半份新配置」这种混合状态。
     * 读盘异常只记 ERROR，让游戏按空配置继续跑（缺省的 section 各自回落默认值）。
     */
    @Override
    protected void load() {
        root = new JsonObject();
        Path file = currentFile();
        if (file == null) return;
        try {
            root = JsonIO.readObject(file);
            log.info("已加载统一配置：{}", file);
        } catch (Exception e) {
            log.error("加载统一配置失败：{}", file, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>换成空树：停服后这棵树没有意义，留着只会被下一次落盘写成别的存档的内容。
     */
    @Override
    protected void clearData() {
        root = new JsonObject();
    }

    // ============================================================
    // 钩子：统一配置重载必须把子配置也带一遍
    // ============================================================

    /**
     * {@inheritDoc}
     *
     * <p>读盘之后立刻驱动所有 section 配置重载：它们都只是这棵树上的一个节点，
     * 必须在本类读完盘之后才读，否则拿到的是旧 root。
     */
    @Override
    protected void onReload(MinecraftServer server) {
        reloadChildren(server);
    }

    /**
     * {@inheritDoc}
     *
     * <p>和 {@link #onReload} 一样要把子配置带一遍，只是多打一条「从磁盘重读了几个 section」的日志；
     * {@code server == null}（比如重载时服务器刚停）只复位了内存树，没有可读的文件。
     */
    @Override
    protected void onDiskLoad(MinecraftServer server) {
        if (server == null) {
            log.warn("reloadFromDisk 收到 null server：只重置了内存 root，没有读盘");
            return;
        }
        log.info("已从磁盘重读统一配置：{} 个 section", root.keySet().size());
        reloadChildren(server);
    }

    /**
     * 把规则侧的 section 配置全部重载一遍（{@link RuleType#getReloadableConfigs()}）。
     *
     * <p>单个子配置抛异常只记日志、不打断其余配置：一份坏掉的 section 不该让整次重载失败。
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

    // ============================================================
    // 数据读写
    // ============================================================

    /**
     * 取某个 section 的对象；不存在就当场建一个空对象挂进树里。
     *
     * <p>返回的是<b>树上的活引用</b>：调用方直接往里读写，然后调
     * {@code UnifiedConfigFile.INSTANCE.markDirty()} 由 tick 末尾统一落盘。
     *
     * @param name section 名（通常就是规则的注册名）
     * @return 该 section 的 {@link JsonObject}，永不为 {@code null}
     */
    public static JsonObject getSection(String name) {
        if (INSTANCE.root.has(name) && INSTANCE.root.get(name).isJsonObject()) {
            return INSTANCE.root.getAsJsonObject(name);
        }
        JsonObject section = new JsonObject();
        INSTANCE.root.add(name, section);
        return section;
    }

    /**
     * 整份替换某个 section（迁移 / 规范化重写时用）。
     *
     * <p>只改内存，不标脏 —— 调用方自己负责 {@code markDirty()}。
     *
     * @param name  section 名
     * @param value 新的 section 内容
     */
    public static void setSection(String name, JsonElement value) {
        INSTANCE.root.add(name, value);
    }

    // ============================================================
    // 迁移辅助
    // ============================================================

    /**
     * 存档里是否存在 {@code config.json}。
     *
     * <p>迁移用它区分「全新存档」和「有旧配置要迁移」；只查文件是否存在，不读内容。
     *
     * @param server 目标服务器；{@code null} 时返回 {@code false}
     */
    public static boolean fileExists(MinecraftServer server) {
        return server != null && Files.exists(ConfigPaths.world(server, SAVE_PATH));
    }

    /**
     * 迁移用：<b>无条件</b>把文件重读进内存（迁移会整份重写，必须先拿到最新内容）。
     *
     * @param server 目标服务器
     */
    public static void loadForMigration(MinecraftServer server) {
        INSTANCE.forceLoad(server);
    }

    /**
     * 读内存树里记录的配置版本号（{@link #CONFIG_VERSION_KEY}）。
     *
     * <p>字段缺失、不是字符串、或去空白后为空都算「没有版本」，返回 {@code null}
     * —— 迁移据此把老存档当未知版本处理。
     *
     * @return 形如 {@code "1.1.0"} 的版本号；没有则 {@code null}
     */
    public static @Nullable String storedConfigVersion() {
        JsonElement element = INSTANCE.root.get(CONFIG_VERSION_KEY);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 把配置版本号写进内存树（不落盘，由调用方标记待写）。
     *
     * @param version 本次写入的版本号
     */
    public static void setConfigVersion(String version) {
        INSTANCE.root.addProperty(CONFIG_VERSION_KEY, version);
    }

    /**
     * 丢掉内存里的整份配置树（迁移的「整份重置」路径用）。
     *
     * <p>只清内存：调用方随后会重新填 section 并 {@code markDirty()}，
     * 由 tick 末尾把新内容覆盖回文件。
     */
    public static void clearAllSections() {
        INSTANCE.root = new JsonObject();
    }

    /**
     * 除了版本号之外，内存树里还有没有任何 section。
     *
     * <p>迁移用它判断「这份配置是不是空的」：只有 {@link #CONFIG_VERSION_KEY} 一个键也算空。
     *
     * @return 至少有一个业务 section 时 {@code true}
     */
    public static boolean hasContent() {
        for (String key : INSTANCE.root.keySet()) {
            if (!CONFIG_VERSION_KEY.equals(key)) {
                return true;
            }
        }
        return false;
    }
}
