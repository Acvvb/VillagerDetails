package com.villagerdetails.handler.villager.trader.refresh;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class IdTranslation {

    private IdTranslation() {}

    private static final Logger log = LogManager.getLogger(IdTranslation.class);

    /** 存档内相对路径 */
    private static final String SAVE_RELATIVE_PATH = "villagerdetails/id_mappings.json";

    private static final Map<String, String> DEFAULT_CN_TO_ENCHANT = Map.ofEntries(
            // 保护类
            Map.entry("保护", "protection"),
            Map.entry("火焰保护", "fire_protection"),
            Map.entry("摔落保护", "feather_falling"),
            Map.entry("爆炸保护", "blast_protection"),
            Map.entry("弹射物保护", "projectile_protection"),
            Map.entry("水下呼吸", "respiration"),
            Map.entry("水下速掘", "aqua_affinity"),
            Map.entry("荆棘", "thorns"),
            Map.entry("深海探索者", "depth_strider"),
            Map.entry("冰霜行者", "frost_walker"),
            Map.entry("绑定诅咒", "binding_curse"),
            Map.entry("灵魂疾行", "soul_speed"),
            Map.entry("迅捷潜行", "swift_sneak"),

            // 武器类
            Map.entry("锋利", "sharpness"),
            Map.entry("亡灵杀手", "smite"),
            Map.entry("节肢杀手", "bane_of_arthropods"),
            Map.entry("击退", "knockback"),
            Map.entry("火焰附加", "fire_aspect"),
            Map.entry("抢夺", "looting"),
            Map.entry("横扫之刃", "sweeping_edge"),

            // 工具类
            Map.entry("效率", "efficiency"),
            Map.entry("精准采集", "silk_touch"),
            Map.entry("耐久", "unbreaking"),
            Map.entry("时运", "fortune"),

            // 弓类
            Map.entry("力量", "power"),
            Map.entry("冲击", "punch"),
            Map.entry("火矢", "flame"),
            Map.entry("无限", "infinity"),

            // 钓鱼竿
            Map.entry("海之眷顾", "luck_of_the_sea"),
            Map.entry("饵钓", "lure"),

            // 三叉戟
            Map.entry("忠诚", "loyalty"),
            Map.entry("穿刺", "impaling"),
            Map.entry("激流", "riptide"),
            Map.entry("引雷", "channeling"),

            // 弩
            Map.entry("多重射击", "multishot"),
            Map.entry("快速装填", "quick_charge"),
            Map.entry("穿透", "piercing"),

            // 重锤专属
            Map.entry("致密", "density"),
            Map.entry("破甲", "breach"),
            Map.entry("风爆", "wind_burst"),

            // 长矛专属
            Map.entry("突进", "lunge"),

            // 通用
            Map.entry("经验修补", "mending"),
            Map.entry("消失诅咒", "vanishing_curse")
    );

    private static final Map<String, String> DEFAULT_CN_TO_ITEM = Map.ofEntries(
            Map.entry("白色陶瓦", "white_terracotta"),
            Map.entry("橙色陶瓦", "orange_terracotta"),
            Map.entry("品红色陶瓦", "magenta_terracotta"),
            Map.entry("淡蓝色陶瓦", "light_blue_terracotta"),
            Map.entry("黄色陶瓦", "yellow_terracotta"),
            Map.entry("黄绿色陶瓦", "lime_terracotta"),
            Map.entry("粉红色陶瓦", "pink_terracotta"),
            Map.entry("灰色陶瓦", "gray_terracotta"),
            Map.entry("淡灰色陶瓦", "light_gray_terracotta"),
            Map.entry("青色陶瓦", "cyan_terracotta"),
            Map.entry("紫色陶瓦", "purple_terracotta"),
            Map.entry("蓝色陶瓦", "blue_terracotta"),
            Map.entry("棕色陶瓦", "brown_terracotta"),
            Map.entry("绿色陶瓦", "green_terracotta"),
            Map.entry("红色陶瓦", "red_terracotta"),
            Map.entry("黑色陶瓦", "black_terracotta")
    );

    private static volatile Map<String, String> cnToEnchant = DEFAULT_CN_TO_ENCHANT;
    private static volatile Map<String, String> cnToItem = DEFAULT_CN_TO_ITEM;

    /**
     * 服务器启动时调用。从存档加载配置；文件不存在则写入默认映射。
     */
    public static void loadFromWorld(MinecraftServer server) {
        Path file = getConfigPath(server);
        loadFrom(file);
    }

    /**
     * 从指定文件加载。
     * 已存在的项 → 覆盖默认；
     * 不存在的项 → 保留默认（合并语义）。
     */
    public static void loadFrom(Path file) {
        if (Files.exists(file)) {
            Map<String, String> enchant = new LinkedHashMap<>();
            Map<String, String> item = new LinkedHashMap<>();
            boolean loaded = false;

            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement rootElement = JsonParser.parseReader(reader);
                if (rootElement != null && rootElement.isJsonObject()) {
                    JsonObject root = rootElement.getAsJsonObject();
                    mergeInto(root.getAsJsonObject("enchant"), enchant);
                    mergeInto(root.getAsJsonObject("item"), item);
                    loaded = true;
                    log.info("已加载映射配置：{} 条附魔 + {} 条物品",
                            enchant.size(), item.size());
                }
            } catch (Exception e) {
                log.error("加载映射配置失败，回落默认表", e);
            }

            if (loaded) {
                cnToEnchant = Collections.unmodifiableMap(enchant);
                cnToItem = Collections.unmodifiableMap(item);
                return;
            }
        } else {
            // 首次：写默认到存档
            writeDefaults(file);
            log.info("已生成默认映射配置：{}", file);
        }

        // json 不存在 / 解析失败 → 用默认表兜底
        cnToEnchant = DEFAULT_CN_TO_ENCHANT;
        cnToItem = DEFAULT_CN_TO_ITEM;
    }

    /** 重置为默认（可通过命令调用） */
    public static void resetToDefault() {
        cnToEnchant = DEFAULT_CN_TO_ENCHANT;
        cnToItem = DEFAULT_CN_TO_ITEM;
    }

    private static Path getConfigPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(SAVE_RELATIVE_PATH);
    }

    /** 把 JSON 里的键值对合并到目标 map（覆盖默认值） */
    private static void mergeInto(JsonObject obj, Map<String, String> target) {
        if (obj == null) return;
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement v = entry.getValue();
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                String key = entry.getKey();
                if (!key.isBlank()) {
                    target.put(key, v.getAsString());
                }
            }
        }
    }

    /** 把默认映射写入文件 */
    private static void writeDefaults(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }

            JsonObject root = new JsonObject();

            JsonObject enchantObj = new JsonObject();
            IdTranslation.DEFAULT_CN_TO_ENCHANT.forEach(enchantObj::addProperty);
            root.add("enchant", enchantObj);

            JsonObject itemObj = new JsonObject();
            IdTranslation.DEFAULT_CN_TO_ITEM.forEach(itemObj::addProperty);
            root.add("item", itemObj);

            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                        .create().toJson(root, writer);
            }
        } catch (Exception e) {
            log.error("写入默认映射配置失败", e);
        }
    }

    // ==============================================================
    // 解析入口
    // ==============================================================

    /**
     * 把玩家输入的名字转成 Identifier。
     * 顺序：附魔中文 → 物品中文 → 英文 ID。
     */
    public static Identifier resolveEnchantId(String raw) {
        if (raw == null) return null;

        String trimmed = raw.replace('\u3000', ' ').trim();
        if (trimmed.isEmpty()) return null;

        // 1) 附魔中文名（从当前运行时映射）
        String enchantMapped = cnToEnchant.get(trimmed);
        if (enchantMapped != null) {
            return Identifier.tryParse("minecraft:" + enchantMapped);
        }

        // 2) 物品中文名
        String itemMapped = cnToItem.get(trimmed);
        if (itemMapped != null) {
            return Identifier.tryParse("minecraft:" + itemMapped);
        }

        // 3) 英文 ID 兜底
        String lower = trimmed.toLowerCase();
        Identifier id = Identifier.tryParse(lower);
        if (id == null) id = Identifier.tryParse("minecraft:" + lower);
        return id;
    }
}