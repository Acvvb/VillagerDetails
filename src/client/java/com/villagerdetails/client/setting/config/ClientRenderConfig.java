package com.villagerdetails.client.setting.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.util.ColorUtil;
import com.villagerdetails.config.impl.util.JsonIO;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端配置的「存储 + 持久化」。
 *
 * <p>只影响本机显示，不同步给服务端。所有的配置项、默认值、行样式都在
 * {@link ConfigEntryType} 里声明，本类只负责：
 * <ul>
 *     <li>按 {@code registerName} 存取值（单值 / 列表 / 快捷键）</li>
 *     <li>把值落盘到 {@code config/villagerdetails-client.json}，启动时读回</li>
 *     <li>读回后做一次范围修正（{@link #clamp()}）和一次旧版格式迁移（{@link #migrateLegacy}）</li>
 * </ul>
 *
 * <p>新增一个配置项<b>不需要改本类</b>：只要在 {@link ConfigEntryType} 加一行常量，
 * 存取逻辑自动走通用 map。
 */
public final class ClientRenderConfig {

    /**
     * 单例构造：先套用一遍 {@link ConfigEntryType} 里声明的默认值，
     * 保证「新建实例 = 全默认」。
     */
    private ClientRenderConfig() {
        applyEntryDefaults();
    }

    /**
     * 日志
     */
    private static final Logger LOGGER = LogManager.getLogger(ClientRenderConfig.class);

    /**
     * JSON 读写器（缩进对齐、不转义 HTML 字符）
     */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * 配置文件文件名（放在 Fabric 的 config 目录下）
     */
    private static final String FILE_NAME = "villagerdetails-client.json";

    /**
     * 未绑定快捷键（空数组）
     */
    public static final int[] NO_KEYS = new int[0];

    /**
     * 全局单例，界面和渲染都从这里拿当前配置
     */
    private static ClientRenderConfig instance = new ClientRenderConfig();

    // ==============================================================
    // 配置数据（全部按 registerName 索引，新增配置项不需要改这里）
    // ==============================================================

    /**
     * 单值项：registerName → "true"/"false"、"4.0"、"#54E36B"
     */
    private Map<String, String> values = new LinkedHashMap<>();

    /**
     * 列表项：registerName → 值列表
     */
    private Map<String, List<String>> lists = new LinkedHashMap<>();

    /**
     * 快捷键：registerName → "88+67"
     */
    private Map<String, String> keyBinds = new LinkedHashMap<>();

    // ==============================================================
    // 界面状态（不是配置项，保留为字段）
    // ==============================================================

    /**
     * 上次打开配置界面时选中的一级分类 key（空串表示没有记录）
     */
    private String lastCategory = "";

    /**
     * 上次打开配置界面时选中的二级分类 key（空串表示没有记录）
     */
    private String lastSubCategory = "";

    /**
     * 打开设置界面的默认键从 K 改成 J 之后，是否已经做过一次性迁移
     */
    private boolean openKeyMigrated;

    // ==============================================================
    // 通用读写
    // ==============================================================

    /**
     * 取全局单例（界面、渲染、键位处理都从这里拿配置）
     */
    public static ClientRenderConfig get() {
        return instance;
    }

    /**
     * 取原始值；没有就返回空串
     */
    public String getRaw(String registerName) {
        return values.getOrDefault(registerName, "");
    }

    /**
     * 写原始值；空串等于删除（回到默认）
     */
    public void setRaw(String registerName, String value) {
        if (value == null || value.isEmpty()) {
            values.remove(registerName);
        } else {
            values.put(registerName, value);
        }
    }

    /**
     * 把所有配置项按 {@link ConfigEntryType} 上声明的默认值刷一遍（不落盘）。
     * 构造函数和「全局重置」都会走它。
     */
    private void applyEntryDefaults() {
        for (ConfigEntryType entry : ConfigEntryType.values()) {
            entry.applyDefaultTo(this);
        }
    }

    /**
     * 兜底初始化三个 map（values / lists / keyBinds）。
     * Gson 反序列化不走构造函数，配置文件缺字段时字段会保持 null，这里统一补成空 map。
     */
    private void ensureMaps() {
        if (values == null) values = new LinkedHashMap<>();
        if (lists == null) lists = new LinkedHashMap<>();
        if (keyBinds == null) keyBinds = new LinkedHashMap<>();
    }

    /**
     * 配置文件路径（{@code <config>/villagerdetails-client.json}）
     */
    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /**
     * 首次调用时读取配置文件；不存在就生成默认文件。
     * 读取失败或文件损坏时回退到全默认实例。
     */
    public static void load() {
        Path file = path();
        if (!Files.exists(file)) {
            instance.save();
            LOGGER.info("已生成默认客户端配置：{}", file);
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            ClientRenderConfig loaded = GSON.fromJson(root, ClientRenderConfig.class);
            if (loaded != null) {
                instance = loaded;
            }
            instance.ensureMaps();
            instance.migrateLegacy(root);
            instance.clamp();
            LOGGER.info("已加载客户端渲染配置：{}", file);
        } catch (Exception e) {
            LOGGER.error("加载客户端渲染配置失败，回退到默认值：{}", file, e);
            instance = new ClientRenderConfig();
        }
    }

    /**
     * 旧版「每个配置项一个字段」→ 新 values map 的一次性迁移。
     * 新 map 已有值就不覆盖。
     */
    private void migrateLegacy(JsonObject root) {
        if (root == null) return;
        migrateRaw(root, "renderBedLine", "RenderBedLine", false);
        migrateRaw(root, "lineWidth", "LineWidth", false);
        migrateRaw(root, "farDistance", "FarDistance", false);
        migrateRaw(root, "nearColor", "NearColor", true);
        migrateRaw(root, "farColor", "FarColor", true);
    }

    /**
     * 迁移单个旧字段：{@code root} 里存在 {@code oldField} 且新 map 里还没有
     * 对应的 {@code registerName} 时，把它转成新的字符串形式写进 values。
     *
     * @param isColor 旧字段是否为 int 型 RGB，需要转成 {@code "#RRGGBB"}
     */
    private void migrateRaw(JsonObject root, String oldField, String registerName, boolean isColor) {
        if (!root.has(oldField)) return;
        if (values.containsKey(registerName)) return;
        try {
            if (isColor) {
                values.put(registerName, ColorUtil.formatRgb(root.get(oldField).getAsInt()));
            } else {
                values.put(registerName, root.get(oldField).getAsString());
            }
        } catch (Exception e) {
            LOGGER.warn("迁移旧配置字段失败：{} → {}", oldField, registerName, e);
        }
    }

    /**
     * 落盘（保存前会先做一次范围修正）
     */
    public void save() {
        clamp();
        Path file = path();
        try {
            // 和存档里的配置走同一套原子写：中途崩游戏不会把这份配置截成半截
            JsonIO.writeString(file, GSON.toJson(this));
        } catch (Exception e) {
            LOGGER.error("保存客户端渲染配置失败：{}", file, e);
        }
    }

    // ==============================================================
    // 界面状态
    // ==============================================================

    /**
     * 上次打开的一级分类 key（空串表示没有记录）
     */
    public String getLastCategory() {
        return lastCategory == null ? "" : lastCategory;
    }

    /**
     * 记录一级分类 key；传 null 等于清空
     */
    public void setLastCategory(String value) {
        this.lastCategory = value == null ? "" : value;
    }

    /**
     * 上次打开的二级分类 key（空串表示没有记录）
     */
    public String getLastSubCategory() {
        return lastSubCategory == null ? "" : lastSubCategory;
    }

    /**
     * 记录二级分类 key；传 null 等于清空
     */
    public void setLastSubCategory(String value) {
        this.lastSubCategory = value == null ? "" : value;
    }

    /**
     * 打开设置界面的默认键是否已经迁移过（K → J）
     */
    public boolean isOpenKeyMigrated() {
        return openKeyMigrated;
    }

    /**
     * 标记「K → J 迁移」已经做过，下次不再重复
     */
    public void setOpenKeyMigrated(boolean value) {
        this.openKeyMigrated = value;
    }

    // ==============================================================
    // 快捷键
    // ==============================================================

    /**
     * 取某项绑定的按键组合；未绑定返回空数组
     */
    public int[] getKeyBind(String registerName) {
        return parseKeys(keyBinds.get(registerName));
    }

    /**
     * 绑定一组按键；传空数组等于解绑
     */
    public void setKeyBind(String registerName, int[] keys) {
        String joined = joinKeys(keys);
        if (joined.isEmpty()) keyBinds.remove(registerName);
        else keyBinds.put(registerName, joined);
    }

    /**
     * 解除某项的快捷键绑定
     */
    public void clearKeyBind(String registerName) {
        keyBinds.remove(registerName);
    }

    /**
     * 把 {@code "88+67"} 解析成按键码数组；空串或格式不对都当未绑定
     */
    public static int[] parseKeys(String raw) {
        if (raw == null || raw.isBlank()) return NO_KEYS;
        String[] parts = raw.split("\\+");
        int[] keys = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                keys[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return NO_KEYS;
            }
        }
        return keys;
    }

    /**
     * 把按键码数组拼成 {@code "88+67"}；空数组返回空串
     */
    public static String joinKeys(int[] keys) {
        if (keys == null || keys.length == 0) return "";
        StringBuilder builder = new StringBuilder();
        for (int code : keys) {
            if (!builder.isEmpty()) builder.append('+');
            builder.append(code);
        }
        return builder.toString();
    }

    /**
     * 取某项的值列表（只读快照，没有值就是空列表）
     */
    public List<String> getList(String registerName) {
        List<String> v = lists.get(registerName);
        return v == null ? List.of() : List.copyOf(v);
    }

    /**
     * 整份替换某项的值列表；传空列表等于清空
     */
    public void setList(String registerName, List<String> values) {
        if (values == null || values.isEmpty()) lists.remove(registerName);
        else lists.put(registerName, new ArrayList<>(values));
    }

    // ==============================================================
    // 渲染 / 重置
    // ==============================================================

    /**
     * 把所有配置项夹回合法范围（读文件后 / 保存前调用）
     */
    public void clamp() {
        for (ConfigEntryType entry : ConfigEntryType.values()) {
            entry.clampInto(this);
        }
    }

    /**
     * 「全局重置」：所有配置项（含快捷键）恢复成 {@link ConfigEntryType}
     * 里声明的默认值，然后落盘。
     *
     * <p>先 {@code values.clear()} 是为了顺带清掉已经从枚举里删掉的孤儿键。
     */
    public void resetToDefaults() {
        values.clear();
        applyEntryDefaults();
        save();
    }

    /**
     * 是否还有配置项不是默认值（全都默认值时「全局重置」按钮置灰）
     */
    public boolean hasNonDefaultValues() {
        for (ConfigEntryType entry : ConfigEntryType.values()) {
            if (!entry.isDefault()) return true;
        }
        return false;
    }
}