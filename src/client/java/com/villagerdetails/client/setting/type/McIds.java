package com.villagerdetails.client.setting.type;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import java.util.*;

/**
 * MC 里所有「注册表 id」和「标签 id」的索引，给列表项的输入框做补全和校验用。
 *
 * <p>覆盖面：进入世界之后用当前世界的 {@code RegistryAccess}，也就是<b>全部注册表</b>
 * （方块 / 物品 / 实体类型 / 生物群系 / 附魔 / 音效 / 粒子 …）以及挂在它们上面的<b>全部标签</b>
 * （例如 {@code #minecraft:wool}）。还没进世界（在主界面就打开了设置）时退回
 * 方块 / 物品 / 实体类型这三个内置注册表，这时读不到标签。
 *
 * <p>索引建一次就缓存（按 {@code RegistryAccess} 实例判断要不要重建）。{@link #search} 会遍历全表，
 * 所以<b>只在输入框内容变化时调</b>，别每帧调。
 */
public final class McIds {

    /**
     * 一条候选。
     *
     * @param id       完整 id：普通条目是 {@code minecraft:red_bed}，标签是 {@code #minecraft:wool}，
     *                 注册表（分类）是 {@code minecraft:item} / {@code minecraft:block} 这种
     * @param source   它来自哪些注册表（同一个 id 可能同时在多个注册表里），例如 {@code block / item}
     * @param category 这条是不是「一整个注册表」（分类 id），界面上会额外标注一下
     */
    public record Entry(String id, String source, boolean category) {
    }

    /**
     * 建好的索引
     */
    private record Index(List<Entry> entries, Set<String> known, Set<String> bareTags) {
    }

    /**
     * 没进世界时的缓存 key
     */
    private static final Object NO_LEVEL = new Object();

    private static Index cache;
    private static Object cacheKey;

    private McIds() {
    }

    // ==============================================================
    // 查询
    // ==============================================================

    /**
     * 这个字符串是不是 MC 里真实存在的 id。
     *
     * <p>两种写法都算数：带 {@code #} 的标签（{@code #minecraft:wool}），以及省略 {@code #} 的标签
     * （{@code minecraft:wool}）；注册表条目（{@code minecraft:red_bed}）和注册表本身
     * （{@code minecraft:item}、{@code minecraft:block} 这种「分类 id」）也在索引里。
     *
     * <p>空串不算 id —— 调用方自己会把「什么都没填」当成取消。
     */
    public static boolean isKnown(String raw) {
        String value = normalize(raw);
        if (value.isEmpty()) {
            return false;
        }
        Index index = index();
        return index.known().contains(value) || index.bareTags().contains(value);
    }

    /**
     * 按关键字搜候选，返回最匹配的前 {@code limit} 条。
     *
     * <p>匹配优先级：id 完全相同 → 路径完全相同（{@code wool} 能搜到 {@code #minecraft:wool}）→
     * 路径以关键字开头 → id 里包含关键字。同一档里按 id 排序，所以结果是稳定的。
     */
    public static List<Entry> search(String rawQuery, int limit) {
        String query = normalize(rawQuery);
        if (query.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Entry> entries = index().entries();
        List<Entry> hits = new ArrayList<>(limit);
        for (int rank = 0; rank <= LAST_RANK && hits.size() < limit; rank++) {
            for (Entry entry : entries) {
                if (hits.size() >= limit) {
                    break;
                }
                if (rankOf(entry.id(), query) == rank) {
                    hits.add(entry);
                }
            }
        }
        return hits;
    }

    /**
     * 最大的匹配档位（见 {@link #rankOf}）
     */
    private static final int LAST_RANK = 3;

    /**
     * 只在某个注册表里的条目中搜（标签、注册表分类都不算）。
     *
     * <p>给「服务器配置项」的列表页用：方块规则只该补全方块 id，无挤压只该补全实体 id。
     */
    public static List<Entry> searchIn(String rawQuery, int limit, Registry<?> registry) {
        String query = normalize(rawQuery);
        if (query.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Entry> entries = index().entries();
        List<Entry> hits = new ArrayList<>(limit);
        for (int rank = 0; rank <= LAST_RANK && hits.size() < limit; rank++) {
            for (Entry entry : entries) {
                if (hits.size() >= limit) {
                    break;
                }
                if (entry.category() || entry.id().startsWith("#")) {
                    continue;
                }
                if (rankOf(entry.id(), query) == rank && inRegistry(entry.id(), registry)) {
                    hits.add(entry);
                }
            }
        }
        return hits;
    }

    private static boolean inRegistry(String id, Registry<?> registry) {
        if (registry == null) {
            return true;
        }
        Identifier identifier = Identifier.tryParse(id);
        return identifier != null && registry.containsKey(identifier);
    }

    /**
     * -1 = 不匹配；越小越匹配
     */
    private static int rankOf(String id, String query) {
        if (id.equals(query)) {
            return 0;
        }
        String bareId = stripHash(id);
        String bareQuery = stripHash(query);
        String path = pathOf(bareId);
        if (path.equals(bareQuery)) {
            return 1;
        }
        if (path.startsWith(bareQuery)) {
            return 2;
        }
        if (bareId.contains(bareQuery)) {
            return 3;
        }
        return -1;
    }

    // ==============================================================
    // 索引
    // ==============================================================

    private static Index index() {
        Object key = cacheKey();
        if (cache != null && cacheKey == key) {
            return cache;
        }
        cache = build();
        cacheKey = key;
        return cache;
    }

    private static Object cacheKey() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? NO_LEVEL : minecraft.level.registryAccess();
    }

    private static Index build() {
        // id → 它出现在哪些注册表里（minecraft:red_bed 同时在 block 和 item 里）
        Map<String, Set<String>> sources = new LinkedHashMap<>();
        // 标签 id（不带 #）→ 哪些注册表上有同名标签，用来兼容玩家不写 # 的写法
        Set<String> bareTags = new LinkedHashSet<>();
        // 哪些 key 是「注册表本身」（分类 id）
        Set<String> categories = new LinkedHashSet<>();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            minecraft.level.registryAccess().registries().forEach(registry ->
                    collect(sources, bareTags, categories, registry.key().identifier(), registry.value()));
        } else {
            // 还没进世界：退回几个最常用的内置注册表（这时没有标签）
            collect(sources, bareTags, categories, Identifier.withDefaultNamespace("block"), BuiltInRegistries.BLOCK);
            collect(sources, bareTags, categories, Identifier.withDefaultNamespace("item"), BuiltInRegistries.ITEM);
            collect(sources, bareTags, categories, Identifier.withDefaultNamespace("entity_type"),
                    BuiltInRegistries.ENTITY_TYPE);
        }

        List<Entry> entries = new ArrayList<>(sources.size());
        for (Map.Entry<String, Set<String>> entry : sources.entrySet()) {
            entries.add(new Entry(entry.getKey(), String.join(" / ", entry.getValue()),
                    categories.contains(entry.getKey())));
        }
        entries.sort(Comparator.comparing(Entry::id));
        return new Index(List.copyOf(entries), Set.copyOf(sources.keySet()), Set.copyOf(bareTags));
    }

    /**
     * 把一个注册表里的所有 id、标签，以及<b>注册表自己</b>都收进索引。
     *
     * <p>注册表自己也要收：{@code minecraft:item} / {@code minecraft:block} 这种「分类 id」是能填的值
     * （对应「一级分类 id」那种用法），不是条目但它确实存在于 MC 里。
     */
    private static void collect(Map<String, Set<String>> sources, Set<String> bareTags,
                                Set<String> categories, Identifier registryId, Registry<?> registry) {
        if (registry == null) {
            return;
        }
        String registryName = shortName(registryId.toString());
        String registryKey = registryId.toString();
        categories.add(registryKey);
        sources.computeIfAbsent(registryKey, key -> new LinkedHashSet<>()).add(registryName);

        for (Identifier id : registry.keySet()) {
            sources.computeIfAbsent(id.toString(), key -> new LinkedHashSet<>()).add(registryName);
        }
        registry.getTags().forEach(named -> {
            Identifier location = named.key().location();
            sources.computeIfAbsent("#" + location, key -> new LinkedHashSet<>())
                    .add(registryName + "_tag");
            bareTags.add(location.toString());
        });
    }

    /**
     * {@code minecraft:block} → {@code block}；别的命名空间保留全名（模组注册表）
     */
    private static String shortName(String registryId) {
        int colon = registryId.indexOf(':');
        if (colon < 0) {
            return registryId;
        }
        String namespace = registryId.substring(0, colon);
        return "minecraft".equals(namespace) ? registryId.substring(colon + 1) : registryId;
    }

    // ==============================================================
    // 小工具
    // ==============================================================

    /**
     * 去掉首尾空白、转小写（MC 的 id 全是小写，玩家随便敲大写也能对上）
     */
    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String stripHash(String value) {
        return value.startsWith("#") ? value.substring(1) : value;
    }

    private static String pathOf(String bareId) {
        int colon = bareId.indexOf(':');
        return colon < 0 ? bareId : bareId.substring(colon + 1);
    }
}
