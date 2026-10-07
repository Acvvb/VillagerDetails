package com.villagerdetails.client.setting.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.villagerdetails.client.setting.type.ConfigEntry;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端渲染配置，保存在 {@code config/villagerdetails-client.json}。
 *
 * <p>只影响本机显示，不会同步给服务端；配置文件损坏时回退到默认值。
 */
public final class ClientRenderConfig {

    /**
     * 默认值统一在 {@link ConfigEntry} 的枚举常量上声明（写法参考 RuleType 的 state），
     * 这里构造时套用一遍，保证「新建实例 = 全默认」。
     */
    private ClientRenderConfig() {
        applyEntryDefaults();
    }

    private static final Logger LOGGER = LogManager.getLogger(ClientRenderConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "villagerdetails-client.json";

    /**
     * 线宽可调范围
     */
    public static final float MIN_LINE_WIDTH = 1.0F;
    public static final float MAX_LINE_WIDTH = 10.0F;
    /**
     * 「离床过远」判定距离的可调范围（格）
     */
    public static final double MIN_FAR_DISTANCE = 0.0D;
    public static final double MAX_FAR_DISTANCE = 64.0D;

    /**
     * 纯 RGB 掩码（配置里只存 RGB，Alpha 由渲染时补满）
     */
    public static final int RGB_MASK = 0xFFFFFF;
    private static final int OPAQUE = 0xFF000000;

    /**
     * 未绑定（空数组）
     */
    public static final int[] NO_KEYS = new int[0];

    private static ClientRenderConfig instance = new ClientRenderConfig();

    // ==============================================================
    // 配置项（Gson 直接读写这些字段，静态字段不会被序列化）
    // 默认值由 ConfigEntry 声明，构造时统一套用，所以这里不写初始值
    // ==============================================================

    /**
     * 是否渲染「村民 ↔ 床」的连线
     */
    private boolean renderBedLine;

    /**
     * 连线粗细
     */
    private float lineWidth;

    /**
     * 村民离床在阈值内的连线颜色（RGB）
     */
    private int nearColor;

    /**
     * 村民离床超过阈值时的告警连线颜色（RGB）
     */
    private int farColor;

    /**
     * 判定「离床过远」的距离（格）
     */
    private double farDistance;

    // ==============================================================
    // 界面状态（和渲染无关，只是「记住上次打开的位置」）
    // ==============================================================

    /**
     * 上次打开配置界面时选中的一级分类 key
     */
    private String lastCategory = "";

    /**
     * 上次打开配置界面时选中的二级分类 key
     */
    private String lastSubCategory = "";

    /**
     * 功能开关的快捷键绑定：配置项注册名 → {@code "88+67"} 这种按键码组合（缺失/空串 = 未绑定）
     */
    private Map<String, String> keyBinds = new LinkedHashMap<>();

    /**
     * 打开设置界面的默认键从 K 改成 J 之后，是否已经做过一次性迁移
     */
    private boolean openKeyMigrated;

    /**
     * 「列表」类配置项的值：配置项注册名 → 值列表。
     *
     * <p>没有值就不写进这个 map（读出来是空列表），所以「没配过」和「清空了」是一回事。
     */
    private Map<String, List<String>> lists = new LinkedHashMap<>();

    // ==============================================================
    // 读写
    // ==============================================================

    public static ClientRenderConfig get() {
        return instance;
    }

    /**
     * 把 ConfigEntry 上声明的默认值套到这个实例（不落盘）
     */
    private void applyEntryDefaults() {
        for (ConfigEntry entry : ConfigEntry.values()) {
            entry.applyDefaultTo(this);
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /**
     * 首次调用时读取配置文件；不存在就生成默认文件
     */
    public static void load() {
        Path file = path();
        if (!Files.exists(file)) {
            instance.save();
            LOGGER.info("已生成默认客户端配置：{}", file);
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ClientRenderConfig loaded = GSON.fromJson(reader, ClientRenderConfig.class);
            if (loaded != null) {
                instance = loaded;
            }
            instance.clamp();
            LOGGER.info("已加载客户端渲染配置：{}", file);
        } catch (Exception e) {
            LOGGER.error("加载客户端渲染配置失败，回退到默认值：{}", file, e);
            instance = new ClientRenderConfig();
        }
    }

    /**
     * 落盘（保存前会先做范围修正）
     */
    public void save() {
        clamp();
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            LOGGER.error("保存客户端渲染配置失败：{}", file, e);
        }
    }

    // ==============================================================
    // 取值 / 赋值（界面和渲染都走这里，统一做范围限制）
    // ==============================================================

    public boolean isRenderBedLine() {
        return renderBedLine;
    }

    public void setRenderBedLine(boolean value) {
        this.renderBedLine = value;
    }

    public float getLineWidth() {
        return lineWidth;
    }

    public void setLineWidth(float value) {
        this.lineWidth = clampWidth(value);
    }

    public int getNearColor() {
        return nearColor;
    }

    public void setNearColor(int rgb) {
        this.nearColor = rgb & RGB_MASK;
    }

    public int getFarColor() {
        return farColor;
    }

    public void setFarColor(int rgb) {
        this.farColor = rgb & RGB_MASK;
    }

    public double getFarDistance() {
        return farDistance;
    }

    public void setFarDistance(double value) {
        this.farDistance = clampDistance(value);
    }

    /**
     * 上次打开的一级分类 key（空串表示没有记录）
     */
    public String getLastCategory() {
        return lastCategory == null ? "" : lastCategory;
    }

    public void setLastCategory(String value) {
        this.lastCategory = value == null ? "" : value;
    }

    /**
     * 上次打开的二级分类 key（空串表示没有记录）
     */
    public String getLastSubCategory() {
        return lastSubCategory == null ? "" : lastSubCategory;
    }

    public void setLastSubCategory(String value) {
        this.lastSubCategory = value == null ? "" : value;
    }

    // ==============================================================
    // 功能开关的快捷键绑定
    // ==============================================================

    /**
     * 取某项绑定的按键组合，未绑定返回空数组（支持 {@code X+C} 这样的组合键）
     */
    public int[] getKeyBind(String registerName) {
        String raw = keyBinds == null ? null : keyBinds.get(registerName);
        return parseKeys(raw);
    }

    /**
     * 绑定一组按键；传空数组等于解绑
     */
    public void setKeyBind(String registerName, int[] keys) {
        if (keyBinds == null) {
            keyBinds = new LinkedHashMap<>();
        }
        String joined = joinKeys(keys);
        if (joined.isEmpty()) {
            keyBinds.remove(registerName);
        } else {
            keyBinds.put(registerName, joined);
        }
    }

    public void clearKeyBind(String registerName) {
        if (keyBinds != null) {
            keyBinds.remove(registerName);
        }
    }

    /**
     * 把 {@code "88+67"} 解析成按键码数组；空串或格式不对都当未绑定
     */
    public static int[] parseKeys(String raw) {
        if (raw == null || raw.isBlank()) {
            return NO_KEYS;
        }
        String[] parts = raw.split("\\+");
        int[] keys = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                keys[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                // 配置被手改坏了就当未绑定
                return NO_KEYS;
            }
        }
        return keys;
    }

    /**
     * 把按键码数组拼成 {@code "88+67"}；空数组返回空串
     */
    public static String joinKeys(int[] keys) {
        if (keys == null || keys.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int code : keys) {
            if (!builder.isEmpty()) {
                builder.append('+');
            }
            builder.append(code);
        }
        return builder.toString();
    }

    /**
     * 当前所有绑定（只读快照，key → 按键码组合字符串）
     */
    public Map<String, String> getKeyBinds() {
        return keyBinds == null ? Map.of() : Map.copyOf(keyBinds);
    }

    // ==============================================================
    // 「列表」类配置项的值
    // ==============================================================

    /**
     * 取某项的值列表（只读快照，没有值就是空列表）
     */
    public List<String> getList(String registerName) {
        List<String> values = lists == null ? null : lists.get(registerName);
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * 整份设置某项的值列表；传空列表等于清空
     */
    public void setList(String registerName, List<String> values) {
        if (lists == null) {
            lists = new LinkedHashMap<>();
        }
        if (values == null || values.isEmpty()) {
            lists.remove(registerName);
        } else {
            lists.put(registerName, new ArrayList<>(values));
        }
    }

    /**
     * 把配置里的 RGB 补齐 Alpha，给渲染用
     */
    public static int toArgb(int rgb) {
        return OPAQUE | (rgb & RGB_MASK);
    }

    /**
     * 把 RGB 拆成 0~1 的三个分量（界面里做颜色预览/滑条用）
     */
    public static float red(int rgb) {
        return ((rgb >> 16) & 0xFF) / 255.0F;
    }

    public static float green(int rgb) {
        return ((rgb >> 8) & 0xFF) / 255.0F;
    }

    public static float blue(int rgb) {
        return (rgb & 0xFF) / 255.0F;
    }

    /**
     * 把 0~1 的三个分量合成 RGB
     */
    public static int rgb(float red, float green, float blue) {
        return (toByte(red) << 16) | (toByte(green) << 8) | toByte(blue);
    }

    private static int toByte(float component) {
        return Math.round(Math.clamp(component, 0.0F, 1.0F) * 255.0F);
    }

    /**
     * 把配置整体修正回合法范围（读文件后 / 保存前都会调用）
     */
    public void clamp() {
        lineWidth = clampWidth(lineWidth);
        farDistance = clampDistance(farDistance);
        nearColor &= RGB_MASK;
        farColor &= RGB_MASK;
        if (keyBinds == null) {
            keyBinds = new LinkedHashMap<>();
        }
        if (lists == null) {
            lists = new LinkedHashMap<>();
        }
    }

    /**
     * 「全局重置」：所有配置项（含快捷键）恢复成 ConfigEntry 里声明的默认值，然后落盘
     */
    public void resetToDefaults() {
        applyEntryDefaults();
        save();
    }

    /**
     * 是否还有配置项不是默认值（全都默认值时「全局重置」按钮置灰）
     */
    public boolean hasNonDefaultValues() {
        for (ConfigEntry entry : ConfigEntry.values()) {
            if (!entry.isDefault()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 打开设置界面的默认键是否已经迁移过（K → J）
     */
    public boolean isOpenKeyMigrated() {
        return openKeyMigrated;
    }

    public void setOpenKeyMigrated(boolean value) {
        this.openKeyMigrated = value;
    }

    public static float clampWidth(float value) {
        if (Float.isNaN(value)) {
            return 4.0F;
        }
        return Math.clamp(value, MIN_LINE_WIDTH, MAX_LINE_WIDTH);
    }

    public static double clampDistance(double value) {
        if (Double.isNaN(value)) {
            return 5.0D;
        }
        return Math.clamp(value, MIN_FAR_DISTANCE, MAX_FAR_DISTANCE);
    }
}
