package com.villagerdetails.config.impl.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.impl.dto.Cleaned;
import net.minecraft.resources.Identifier;

import java.util.*;

/**
 * 名字 ↔ 原始 id 映射表（{@code trade_index.json}）的 <b>纯数据工具</b>。
 *
 * <p>和 {@link VillagerTradeIndexJson}（村民交易缓存）是两套东西，<b>结果类型也不同</b>：
 * <ul>
 *     <li>{@link VillagerTradeIndexJson#parse} → {@code dto.ParsedIndex}
 *         （村民 UUID → 交易快照）；</li>
 *     <li>{@link #parse} → {@code dto.Cleaned}（{@code 原始 id → 名字列表} + 被作废的歧义名）。</li>
 * </ul>
 *
 * <p>本类只做「JSON ⇄ 索引」与索引上的查询视图（{@code 名字→id} / {@code id→名字}），
 * 不碰文件、不碰生命周期：那些在 {@code IdTranslationConfig}（继承 {@code AbstractWorldConfig}）。
 */
public final class IdTranslationData {

    /**
     * 私有构造：本类只有静态工具方法，不允许实例化。
     *
     * <p>实例化没有意义，反而会让人以为「可以持有一份索引副本」，和
     * {@code IdTranslationConfig} 里的唯一数据源冲突。
     */
    private IdTranslationData() {
    }

    /**
     * 把 {@code trade_index.json} 的根对象解析成 {@code 原始 id → 名字列表}。
     *
     * <p>宽容解析：id 为空的行直接跳过；值可以是数组也可以是单个字符串；
     * 非字符串 / 空名一律忽略（{@link #addName} 负责去重与过滤）。
     *
     * @param root 从文件读出来的 JSON 对象
     * @return 保持文件内顺序的索引；不会返回 {@code null}
     */
    public static Map<String, List<String>> parse(JsonObject root) {
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

    /**
     * 往名字列表里追加一个别名：只收非空字符串，且同一条 id 下不重复。
     *
     * @param names   目标列表（原地修改）
     * @param element JSON 里的一个值；可能是数组元素，也可能就是字段本身
     */
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
     * 索引反向：{@code 原始 id → 名字列表} 变成 {@code 名字 → 原始 id}。
     *
     * <p>同一个名字如果挂在多个 id 上，<b>先出现的赢</b>（{@code putIfAbsent}）；
     * 出现歧义时应先用 {@link #dropAmbiguousNames(Map)} 把歧义名清掉再调用本方法。
     *
     * @param index 原始索引
     * @return 不可变的反向表（保序）
     */
    public static Map<String, String> reverse(Map<String, List<String>> index) {
        Map<String, String> reversed = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : index.entrySet()) {
            for (String name : entry.getValue()) {
                reversed.putIfAbsent(name, entry.getKey());
            }
        }
        return Collections.unmodifiableMap(reversed);
    }

    /**
     * 取每个 id 的<b>第一个</b>名字（展示用的主名称）。
     *
     * @param index 原始索引
     * @return 不可变的 {@code id → 主名称}；没有名字的 id 不会出现在结果里
     */
    public static Map<String, String> firstNames(Map<String, List<String>> index) {
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
     * Tab 补全用的名字集合：{@code id} 与 {@code 别名} 的并集。
     *
     * @param idToName {@link #firstNames} 的结果
     * @param nameToId {@link #reverse} 的结果
     * @return 不可变的去重集合（保序：先 id、后别名）
     */
    public static Set<String> collectSearchableNames(Map<String, String> idToName, Map<String, String> nameToId) {
        Set<String> names = new LinkedHashSet<>(idToName.size() + nameToId.size());
        names.addAll(idToName.keySet());
        names.addAll(nameToId.keySet());
        return Collections.unmodifiableSet(names);
    }

    /**
     * 字符串 → {@link Identifier}：先去空白、转小写，缺命名空间时补 {@code minecraft:}。
     *
     * @param raw 玩家输入或文件里读到的 id 文本
     * @return 解析出的 id；空串 / 非法格式返回 {@code null}
     */
    public static Identifier parseId(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return null;
        Identifier id = Identifier.tryParse(text);
        return id != null ? id : Identifier.tryParse("minecraft:" + text);
    }

    /**
     * 清掉「同一个名字挂在多个 id 上」的歧义名（大小写不敏感）。
     *
     * <p>为什么是整体作废、而不是随便挑一个 id：歧义名会让查询结果取决于文件里的先后顺序，
     * 等于把「哪个 id 赢」变成不可预测的行为。作废之后这些名字不再能从别名反查，
     * 但 {@code id → 名字} 的展示仍然正常（被删掉的只是别名）。
     *
     * @param parsed {@link #parse} 的结果
     * @return 干净的索引 + 被作废的名字集合（{@code dropped} 为空表示文件本身没歧义）
     */
    public static Cleaned dropAmbiguousNames(Map<String, List<String>> parsed) {
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
            if (!kept.isEmpty()) {
                index.put(entry.getKey(), List.copyOf(kept));
            }
        }
        return new Cleaned(index, dropped);
    }
}
