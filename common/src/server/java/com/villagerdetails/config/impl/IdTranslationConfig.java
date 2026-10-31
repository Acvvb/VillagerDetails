package com.villagerdetails.config.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.util.ConfigPaths;
import com.villagerdetails.config.impl.util.JsonIO;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 名字 ↔ 原始 id 的映射表（命名牌上写什么名字，等价于哪个 id）。
 *
 * <p>文件是一整个平铺对象：<b>key = 原始 id，value = 这个名字的全部叫法（列表）</b>，
 * 一个 id 可以有多个叫法（别名）：
 * <pre>
 * {
 *   "sharpness": ["锋利", "锐利"],
 *   "white_terracotta": ["白色陶瓦"]
 * }
 * </pre>
 *
 * <p>和早期版本的两点区别：以前是「中文名当 key、英文 id 当 value」，而且分成 enchant / item 两组。
 * 现在 key 换成原始 id（不带命名空间时按 {@code minecraft:} 处理，也支持 {@code 其他命名空间:id}），
 * 分组也不需要了 —— 解析出来的就是 id，是附魔还是物品由游戏注册表判断
 * （见 {@code AutoVillagerTrader}）。
 *
 * <p><b>一个名字只能指向一个 id</b>：加载时如果发现同一个名字挂在多个 id 下面，
 * 这个名字<b>整体作废</b>（从所有列表里抹掉）并把文件重写一遍，同时打日志点名 ——
 * 免得手写时写重了，出现"同一个名字解析到哪个 id 看运气"。
 */
public final class IdTranslationConfig implements ReloadableConfig {

    private IdTranslationConfig() {
    }

    private static final Logger log = LogManager.getLogger(IdTranslationConfig.class);

    public static final IdTranslationConfig INSTANCE = new IdTranslationConfig();

    @Override
    public String configName() {
        return "TradeIndexConfig";
    }

    @Override
    public void reload(MinecraftServer server) {
        loadFromWorld(server);
    }

    /**
     * 存档内相对路径
     */
    private static final String SAVE_RELATIVE_PATH = "villagerdetails/trade_index.json";

    // ==============================================================
    // 默认表（id → 叫法）
    // ==============================================================

    private static final Map<String, List<String>> DEFAULT_INDEX = defaultIndex();

    /**
     * 反查表：叫法 → id。加载 / 重置后整体替换，读的时候拿到的永远是完整的一份。
     */
    private static volatile Map<String, String> nameToId = reverse(DEFAULT_INDEX);

    private static Map<String, List<String>> defaultIndex() {
        Map<String, List<String>> map = new LinkedHashMap<>();

        // 保护类
        put(map, "protection", "保护");
        put(map, "fire_protection", "火焰保护");
        put(map, "feather_falling", "摔落保护");
        put(map, "blast_protection", "爆炸保护");
        put(map, "projectile_protection", "弹射物保护");
        put(map, "respiration", "水下呼吸");
        put(map, "aqua_affinity", "水下速掘");
        put(map, "thorns", "荆棘");
        put(map, "depth_strider", "深海探索者");
        put(map, "frost_walker", "冰霜行者");
        put(map, "binding_curse", "绑定诅咒");
        put(map, "soul_speed", "灵魂疾行");
        put(map, "swift_sneak", "迅捷潜行");

        // 武器类
        put(map, "sharpness", "锋利");
        put(map, "smite", "亡灵杀手");
        put(map, "bane_of_arthropods", "节肢杀手");
        put(map, "knockback", "击退");
        put(map, "fire_aspect", "火焰附加");
        put(map, "looting", "抢夺");
        put(map, "sweeping_edge", "横扫之刃");

        // 工具类
        put(map, "efficiency", "效率");
        put(map, "silk_touch", "精准采集");
        put(map, "unbreaking", "耐久");
        put(map, "fortune", "时运");

        // 弓类
        put(map, "power", "力量");
        put(map, "punch", "冲击");
        put(map, "flame", "火矢");
        put(map, "infinity", "无限");

        // 钓鱼竿
        put(map, "luck_of_the_sea", "海之眷顾");
        put(map, "lure", "饵钓");

        // 三叉戟
        put(map, "loyalty", "忠诚");
        put(map, "impaling", "穿刺");
        put(map, "riptide", "激流");
        put(map, "channeling", "引雷");

        // 弩
        put(map, "multishot", "多重射击");
        put(map, "quick_charge", "快速装填");
        put(map, "piercing", "穿透");

        // 重锤专属
        put(map, "density", "致密");
        put(map, "breach", "破甲");
        put(map, "wind_burst", "风爆");

        // 长矛专属
        put(map, "lunge", "突进");

        // 通用
        put(map, "mending", "经验修补");
        put(map, "vanishing_curse", "消失诅咒");

        // 陶瓦
        put(map, "white_terracotta", "白色陶瓦");
        put(map, "orange_terracotta", "橙色陶瓦");
        put(map, "magenta_terracotta", "品红色陶瓦");
        put(map, "light_blue_terracotta", "淡蓝色陶瓦");
        put(map, "yellow_terracotta", "黄色陶瓦");
        put(map, "lime_terracotta", "黄绿色陶瓦");
        put(map, "pink_terracotta", "粉红色陶瓦");
        put(map, "gray_terracotta", "灰色陶瓦");
        put(map, "light_gray_terracotta", "淡灰色陶瓦");
        put(map, "cyan_terracotta", "青色陶瓦");
        put(map, "purple_terracotta", "紫色陶瓦");
        put(map, "blue_terracotta", "蓝色陶瓦");
        put(map, "brown_terracotta", "棕色陶瓦");
        put(map, "green_terracotta", "绿色陶瓦");
        put(map, "red_terracotta", "红色陶瓦");
        put(map, "black_terracotta", "黑色陶瓦");

        return Collections.unmodifiableMap(map);
    }

    private static void put(Map<String, List<String>> map, String id, String... names) {
        map.put(id, List.of(names));
    }

    // ==============================================================
    // 加载 / 写盘
    // ==============================================================

    /**
     * 服务器启动 / 重载时调用：从存档加载；文件不存在就按默认表生成一份。
     */
    public static void loadFromWorld(MinecraftServer server) {
        loadFrom(getConfigPath(server));
    }

    /**
     * 从指定文件加载。
     *
     * <p>文件存在时<b>整份替换</b>默认表（不做逐项合并）；读失败 / 结构不对则回落到默认表。
     */
    public static void loadFrom(Path file) {
        if (!Files.exists(file)) {
            write(file, DEFAULT_INDEX);
            nameToId = reverse(DEFAULT_INDEX);
            log.info("已生成默认交易索引：{}", file);
            return;
        }

        Map<String, List<String>> parsed;
        try {
            parsed = parse(JsonIO.readObject(file));
        } catch (Exception e) {
            log.error("读取交易索引失败，回落默认表：{}", file, e);
            nameToId = reverse(DEFAULT_INDEX);
            return;
        }

        Cleaned cleaned = dropAmbiguousNames(parsed);
        if (!cleaned.index().equals(parsed)) {
            // 重名项（或写得不规范的地方）已经抹掉了：立刻把清理后的结果写回去
            log.warn("交易索引里有 {} 个名字同时挂在多个 id 上，已整体作废并从文件里抹除：{}",
                    cleaned.dropped().size(), cleaned.dropped());
            write(file, cleaned.index());
        }

        nameToId = reverse(cleaned.index());
        log.info("已加载交易索引：{} 个交易条目", nameToId.size());
    }

    private static Path getConfigPath(MinecraftServer server) {
        return ConfigPaths.world(server, SAVE_RELATIVE_PATH);
    }

    /**
     * 把文件内容读成「id → 叫法」。
     *
     * <p>value 必须是字符串数组；写单个字符串也收（等价于只有一个叫法的列表）。
     * 空叫法、非字符串元素会被跳过 —— 跳过了就说明和文件不一致，外层会重写一遍。
     */
    private static Map<String, List<String>> parse(JsonObject root) {
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String id = entry.getKey() == null ? "" : entry.getKey().trim();
            if (id.isEmpty()) {
                continue;
            }

            List<String> names = new ArrayList<>();
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonArray()) {
                for (JsonElement element : value.getAsJsonArray()) {
                    addName(names, element);
                }
            } else {
                addName(names, value);
            }
            parsed.put(id, names);
        }
        return parsed;
    }

    private static void addName(List<String> names, JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return;
        }
        String name = element.getAsString().trim();
        if (!name.isEmpty() && !names.contains(name)) {
            names.add(name);
        }
    }

    /**
     * 抹掉「一个名字挂在多个 id 上」的名字（比较时忽略大小写），顺带丢掉被抹空的 id。
     */
    private static Cleaned dropAmbiguousNames(Map<String, List<String>> parsed) {
        Map<String, Integer> counts = new HashMap<>();
        for (List<String> names : parsed.values()) {
            for (String name : names) {
                counts.merge(name.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }

        Map<String, List<String>> index = new LinkedHashMap<>();
        Set<String> dropped = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : parsed.entrySet()) {
            List<String> kept = new ArrayList<>(entry.getValue().size());
            for (String name : entry.getValue()) {
                if (counts.get(name.toLowerCase(Locale.ROOT)) > 1) {
                    dropped.add(name);
                } else {
                    kept.add(name);
                }
            }
            // 叫法被抹光的 id 没必要留在文件里
            if (!kept.isEmpty()) {
                index.put(entry.getKey(), List.copyOf(kept));
            }
        }
        return new Cleaned(index, dropped);
    }

    /**
     * 展开成反查表：叫法 → id
     */
    private static Map<String, String> reverse(Map<String, List<String>> index) {
        Map<String, String> reversed = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : index.entrySet()) {
            for (String name : entry.getValue()) {
                // 加载时已经去过重，这里是默认表 / 兜底路径的保护
                reversed.putIfAbsent(name, entry.getKey());
            }
        }
        return Collections.unmodifiableMap(reversed);
    }

    /**
     * 按「id → 叫法列表」写文件
     */
    private static void write(Path file, Map<String, List<String>> index) {
        JsonObject root = new JsonObject();
        index.forEach((id, names) -> {
            JsonArray array = new JsonArray();
            names.forEach(array::add);
            root.add(id, array);
        });
        JsonIO.safeWrite(file, root);
    }

    /**
     * 清理结果
     *
     * @param index   清理后的表
     * @param dropped 被整体作废的重名叫法
     */
    private record Cleaned(Map<String, List<String>> index, Set<String> dropped) {
    }

    // ==============================================================
    // 解析入口
    // ==============================================================

    /**
     * 把玩家输入的名字转成 Identifier：先查名字表（中文 / 别名），查不到就当 id 直接解析。
     *
     * @return 解析不出来返回 {@code null}
     */
    public static Identifier resolveId(String raw) {
        if (raw == null) return null;

        String trimmed = raw.replace('\u3000', ' ').trim();
        if (trimmed.isEmpty()) return null;

        // 1) 名字表
        String mapped = nameToId.get(trimmed);
        if (mapped != null) {
            Identifier id = parseId(mapped);
            if (id != null) return id;
        }

        // 2) 直接当 id 用（英文 / 带命名空间的 id）
        return parseId(trimmed);
    }

    /**
     * 解析 id 文本：带命名空间按原样，不带就补 {@code minecraft:}
     */
    private static Identifier parseId(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return null;
        Identifier id = Identifier.tryParse(text);
        return id != null ? id : Identifier.tryParse("minecraft:" + text);
    }
}
