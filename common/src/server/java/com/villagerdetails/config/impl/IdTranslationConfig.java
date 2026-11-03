package com.villagerdetails.config.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.util.ConfigPaths;
import com.villagerdetails.util.io.AsyncConfigManager;
import com.villagerdetails.util.io.JsonIO;
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
     * 常见交易物品的中文叫法兜底：<b>id（不带命名空间）→ 中文</b>。
     *
     * <p>为什么需要它：{@code trade_index.json} 里登记的是附魔 / 陶瓦（这些是「卖什么」的判定依据），
     * 而<b>价格物品</b>（绿宝石、书、纸……）从来没人登记过。服务端又只有原版 {@code en_us}
     * 语言表，光靠它拼出来的展示文本会是 {@code 10 Emerald -> 1 Enchanted Book[经验修补]}
     * —— 中文玩家搜「绿宝石」就成了空结果。
     *
     * <p>所以这里放一份<b>只读兜底</b>：查不到文件里的叫法时才用它。三个承诺：
     * <ul>
     *     <li><b>不写盘</b>：{@code trade_index.json} 的内容永远只由玩家 / 默认表决定，
     *         这份兜底不会把文件撑大，也不会在重载后「凭空多出条目」；</li>
     *     <li><b>文件优先</b>：玩家在文件里给同一个 id 配了自己的叫法，以文件为准；</li>
     *     <li><b>只放常见物</b>：冷门物品（泥土、砂砾……）刻意不列，它们同时也不在
     *         「白名单」里 —— 这正是村民交易索引要丢掉的那批交易。</li>
     * </ul>
     *
     * <p><b>声明顺序有讲究</b>：下面 {@link #nameToId} / {@link #searchableNames} 的静态初始化
     * 会读这两个表，所以它们必须声明在前面（Java 静态字段按声明顺序初始化）。
     */
    private static final Map<String, String> BUILTIN_ID_TO_NAME = builtinItemAliases();

    /**
     * {@link #BUILTIN_ID_TO_NAME} 的反查：中文叫法 → id（{@link #resolveId} 用）。
     */
    private static final Map<String, String> BUILTIN_NAME_TO_ID = reverseBuiltin(BUILTIN_ID_TO_NAME);

    /**
     * 反查表：叫法 → id。加载 / 重置后整体替换，读的时候拿到的永远是完整的一份。
     */
    private static volatile Map<String, String> nameToId = reverse(DEFAULT_INDEX);

    /**
     * 正查表：id → 第一个叫法（用作展示名，例如 {@code mending → 经验修补}）。
     *
     * <p>{@link #nameToId} 是「多对一」（一个 id 可以有多个叫法），没法反向取；
     * 村民交易查询需要按 id 找展示名，所以单独维护一份。和 {@code nameToId} 同源、同时整体替换。
     */
    private static volatile Map<String, String> idToName = firstNames(DEFAULT_INDEX);

    /**
     * Tab 补全用的「可搜索名字」全集：英文 id + 全部中文 / 别名叫法，和上面两张表同源、同时整体替换。
     *
     * <p>为什么单独存一份而不是每次补全都现拼：按 Tab 是高频操作，而这份表只在
     * 加载 / 重载 / 重置交易索引时变一次（{@link #applyIndex}）。
     *
     * <p>顺序固定（先全部 id、再全部叫法，各自按文件里的顺序），所以同一个前缀的补全顺序
     * 每次按 Tab 都一样，不会跳来跳去。
     */
    private static volatile Set<String> searchableNames = collectSearchableNames(idToName, nameToId);

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
    // 内置物品叫法兜底（不写进 trade_index.json）
    // ==============================================================

    private static Map<String, String> builtinItemAliases() {
        Map<String, String> map = new LinkedHashMap<>();

        // 付款物（原版村民交易几乎只用这几样付钱）
        map.put("emerald", "绿宝石");
        map.put("book", "书");
        map.put("paper", "纸");

        // 常见产物（附魔书靠附魔名区分，物品名本身也补一个）
        map.put("enchanted_book", "附魔书");
        map.put("glass", "玻璃");
        map.put("glass_pane", "玻璃板");
        map.put("lantern", "灯笼");
        map.put("candle", "蜡烛");
        map.put("clock", "时钟");
        map.put("compass", "指南针");
        map.put("ink_sac", "墨囊");
        map.put("clay_ball", "黏土球");
        map.put("brick", "红砖");
        map.put("nether_brick", "下界砖");
        map.put("quartz", "下界石英");
        map.put("arrow", "箭");

        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> reverseBuiltin(Map<String, String> idToName) {
        Map<String, String> reversed = new LinkedHashMap<>();
        idToName.forEach((id, name) -> reversed.putIfAbsent(name, id));
        return Collections.unmodifiableMap(reversed);
    }

    /**
     * 内置兜底叫法（{@code id → 中文}）；只读，给补全 / 白名单 / 展示名用。
     */
    public static Map<String, String> builtinItemAliasesView() {
        return BUILTIN_ID_TO_NAME;
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
            applyIndex(DEFAULT_INDEX);
            log.info("已生成默认交易索引：{}", file);
            return;
        }

        Map<String, List<String>> parsed;
        try {
            parsed = parse(JsonIO.readObject(file));
        } catch (Exception e) {
            log.error("读取交易索引失败，回落默认表：{}", file, e);
            applyIndex(DEFAULT_INDEX);
            return;
        }

        Cleaned cleaned = dropAmbiguousNames(parsed);
        if (!cleaned.index().equals(parsed)) {
            // 重名项（或写得不规范的地方）已经抹掉了：立刻把清理后的结果写回去
            log.warn("交易索引里有 {} 个名字同时挂在多个 id 上，已整体作废并从文件里抹除：{}",
                    cleaned.dropped().size(), cleaned.dropped());
            write(file, cleaned.index());
        }

        applyIndex(cleaned.index());
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
     * 正查表：id → 第一个叫法。没有叫法的 id 不进来。
     */
    private static Map<String, String> firstNames(Map<String, List<String>> index) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : index.entrySet()) {
            List<String> aliases = entry.getValue();
            if (aliases != null && !aliases.isEmpty()) {
                names.put(entry.getKey(), aliases.getFirst());
            }
        }
        return Collections.unmodifiableMap(names);
    }

    /**
     * 两张表一起替换，避免出现「反查表是新的、正查表还是旧的」的中间状态。
     */
    private static void applyIndex(Map<String, List<String>> index) {
        Map<String, String> ids = reverse(index);
        Map<String, String> names = firstNames(index);
        nameToId = ids;
        idToName = names;
        searchableNames = collectSearchableNames(names, ids);
    }

    /**
     * 展开成「可搜索名字」全集：先全部英文 id，再全部中文 / 别名叫法，最后补上内置兜底。
     *
     * <p>给 {@code /ec c TradeSearch <关键词>} 的 Tab 补全用 —— 这个命令的关键词
     * 既能写 id（{@code mending}）也能写叫法（{@code 经验修补}），两边都要能补全出来。
     */
    private static Set<String> collectSearchableNames(Map<String, String> idToName,
                                                     Map<String, String> nameToId) {
        Set<String> names = new LinkedHashSet<>(
                idToName.size() + nameToId.size() + BUILTIN_ID_TO_NAME.size() * 2);
        names.addAll(idToName.keySet());
        names.addAll(nameToId.keySet());
        // 内置兜底也进补全（绿宝石 / 附魔书 这类价格物品，文件里通常没有）
        names.addAll(BUILTIN_ID_TO_NAME.keySet());
        names.addAll(BUILTIN_ID_TO_NAME.values());
        return Collections.unmodifiableSet(names);
    }

    /**
     * 交易索引里所有能用来搜索的名字：英文 id（{@code mending}、{@code white_terracotta}）
     * 与中文 / 别名叫法（{@code 经验修补}、{@code 白色陶瓦}），外加内置的物品兜底叫法
     * （{@code emerald} / {@code 绿宝石} 这类价格物品）。
     *
     * <p>返回的是<b>不可变快照</b>，可以放心在补全器里遍历；重载交易索引后会换成新的一份，
     * 调用方每次都重新取，不要缓存。
     */
    public static Set<String> getAllSearchableNames() {
        return searchableNames;
    }

    /**
     * 这个 id 在交易索引里有没有登记（带不带 {@code minecraft:} 都认，含内置兜底叫法）。
     *
     * <p>给「村民交易查询」做<b>白名单降级过滤</b>用：只有索引里登记过的物品 / 附魔才值得收录，
     * 冷门物品（泥土、砂砾这种）直接丢掉，免得缓存和落盘文件被无意义的交易撑爆。
     *
     * @param idText 物品 / 附魔 id，例如 {@code minecraft:emerald} 或 {@code mending}
     */
    public static boolean isMapped(String idText) {
        Identifier id = parseId(idText);
        if (id == null) {
            return false;
        }
        if (idToName.containsKey(id.toString())) {
            return true;
        }
        if (!"minecraft".equals(id.getNamespace())) {
            return false;
        }
        return idToName.containsKey(id.getPath()) || BUILTIN_ID_TO_NAME.containsKey(id.getPath());
    }

    /**
     * 按「id → 叫法列表」写文件。
     *
     * <p><b>改成异步</b>：这些写入只发生在「首次生成默认索引」和「重名清理后重写」两类场景，
     * 写完立刻由内存里的 {@code DEFAULT_INDEX} / 清理后的 {@code index} 接管，
     * 没有任何一处需要「函数返回时文件已落地」的强一致语义。
     * Gson 能直接序列化 {@code Map<String, List<String>>}，不用手拼 JsonObject。
     */
    private static void write(Path file, Map<String, List<String>> index) {
        AsyncConfigManager.save(file, index);
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
     * 把玩家输入的名字转成 Identifier：先查名字表（中文 / 别名），再查内置兜底，最后当 id 直接解析。
     *
     * @return 解析不出来返回 {@code null}
     */
    public static Identifier resolveId(String raw) {
        if (raw == null) return null;

        String trimmed = raw.replace('\u3000', ' ').trim();
        if (trimmed.isEmpty()) return null;

        // 1) 名字表（trade_index.json）
        String mapped = nameToId.get(trimmed);
        if (mapped != null) {
            Identifier id = parseId(mapped);
            if (id != null) return id;
        }

        // 2) 内置兜底叫法（绿宝石 / 附魔书 这类文件里通常没有的物品名）
        String builtin = BUILTIN_NAME_TO_ID.get(trimmed);
        if (builtin != null) {
            Identifier id = parseId(builtin);
            if (id != null) return id;
        }

        // 3) 直接当 id 用（英文 / 带命名空间的 id）
        return parseId(trimmed);
    }

    /**
     * 取某个 id 的展示叫法（第一个别名），没有配置返回 {@code null}。
     *
     * <p>给「村民交易查询」这类<b>只读展示</b>用：玩家在 {@code trade_index.json} 里给
     * {@code mending} 配了「经验修补」，这里就返回「经验修补」；文件里没有时再看内置兜底
     * （{@code emerald → 绿宝石}），都没有就返回 {@code null}，调用方再退回原版语言表 / 注册 id。
     *
     * <p>文件中 key 通常不带命名空间（{@code mending}），而注册表里的 id 带命名空间
     * （{@code minecraft:mending}），所以两者都查一遍。
     */
    public static String displayNameOf(Identifier id) {
        if (id == null) {
            return null;
        }
        String direct = idToName.get(id.toString());
        if (direct != null) {
            return direct;
        }
        if (!"minecraft".equals(id.getNamespace())) {
            return null;
        }
        String path = id.getPath();
        String fromFile = idToName.get(path);
        if (fromFile != null) {
            return fromFile;
        }
        // 文件优先，内置兜底只补文件里没有的（价格物品）
        return BUILTIN_ID_TO_NAME.get(path);
    }

    /**
     * 文本形态的 id 重载（{@code minecraft:mending} 或 {@code mending}）。
     *
     * @return 解析不出来或没有叫法时返回 {@code null}
     */
    public static String displayNameOf(String idText) {
        return displayNameOf(parseId(idText));
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
