package com.villagerdetails.config.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.data.VillagerTradeItemRegistry;
import com.villagerdetails.config.impl.util.ConfigPaths;
import com.villagerdetails.util.io.AbstractAsyncConfig;
import com.villagerdetails.util.json.JsonIO;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 名字 ↔ 原始 id 的映射表。
 *
 * <p>直接继承 {@link AbstractAsyncConfig}，写盘逻辑（脏标记、防抖、异步单写者）
 * 全部由底层接管。本类只维护内存数据和解析逻辑。
 *
 * <p>所有默认物品、方块、附魔的映射，统一来源于 {@link VillagerTradeItemRegistry}。
 */
public final class IdTranslationConfig extends AbstractAsyncConfig implements ReloadableConfig {

    public static final IdTranslationConfig INSTANCE = new IdTranslationConfig();

    private static final Logger log = LogManager.getLogger(IdTranslationConfig.class);

    private static final String SAVE_RELATIVE_PATH = "villagerdetails/trade_index.json";

    private volatile Path file;

    private volatile Map<String, List<String>> rawIndex = DEFAULT_INDEX;

    private IdTranslationConfig() {
    }

    @Override
    public String configName() {
        return "TradeIndexConfig";
    }

    @Override
    public void reload(MinecraftServer server) {
        loadFromWorld(server);
    }

    @Override
    protected Path getFilePath() {
        return file;
    }

    @Override
    protected Object getDataToSave() {
        return rawIndex;
    }

    /**
     * 记录要写的数据并标脏。
     */
    private void scheduleWrite(Path file, Map<String, List<String>> index) {
        this.file = file;
        this.rawIndex = index;
        markDirty();
    }

    // ==============================================================
    // 数据源（由 VillagerTradeItemRegistry 提供）
    // ==============================================================

    private static final Map<String, List<String>> DEFAULT_INDEX = VillagerTradeItemRegistry.getDefaultIndex();

    private static volatile Map<String, String> nameToId = reverse(DEFAULT_INDEX);
    private static volatile Map<String, String> idToName = firstNames(DEFAULT_INDEX);
    private static volatile Set<String> searchableNames = collectSearchableNames(idToName, nameToId);

    // ==============================================================
    // 加载
    // ==============================================================

    public static void loadFromWorld(MinecraftServer server) {
        loadFrom(ConfigPaths.world(server, SAVE_RELATIVE_PATH));
    }

    public static void loadFrom(Path file) {
        if (!Files.exists(file)) {
            INSTANCE.scheduleWrite(file, DEFAULT_INDEX);
            applyIndex(DEFAULT_INDEX);
            log.info("已生成默认交易索引（异步落盘中）：{}", file);
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
            log.warn("交易索引里有 {} 个名字同时挂在多个 id 上，已整体作废并从文件里抹除：{}",
                    cleaned.dropped().size(), cleaned.dropped());
            INSTANCE.scheduleWrite(file, cleaned.index());
        }

        applyIndex(cleaned.index());
        log.info("已加载交易索引：{} 个交易条目", nameToId.size());
    }

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
            if (!kept.isEmpty()) {
                index.put(entry.getKey(), List.copyOf(kept));
            }
        }
        return new Cleaned(index, dropped);
    }

    private static Map<String, String> reverse(Map<String, List<String>> index) {
        Map<String, String> reversed = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : index.entrySet()) {
            for (String name : entry.getValue()) {
                reversed.putIfAbsent(name, entry.getKey());
            }
        }
        return Collections.unmodifiableMap(reversed);
    }

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

    private static void applyIndex(Map<String, List<String>> index) {
        Map<String, String> ids = reverse(index);
        Map<String, String> names = firstNames(index);
        nameToId = ids;
        idToName = names;
        searchableNames = collectSearchableNames(names, ids);
    }

    private static Set<String> collectSearchableNames(Map<String, String> idToName, Map<String, String> nameToId) {
        Set<String> names = new LinkedHashSet<>(idToName.size() + nameToId.size());
        names.addAll(idToName.keySet());
        names.addAll(nameToId.keySet());
        return Collections.unmodifiableSet(names);
    }

    public static Set<String> getAllSearchableNames() {
        return searchableNames;
    }

    private record Cleaned(Map<String, List<String>> index, Set<String> dropped) {
    }

    // ==============================================================
    // 解析入口
    // ==============================================================

    public static Identifier resolveId(String raw) {
        if (raw == null) return null;

        String trimmed = raw.replace('\u3000', ' ').trim();
        if (trimmed.isEmpty()) return null;

        // 直接从 nameToId 查（中文名或纯路径都能查到）
        String mapped = nameToId.get(trimmed);
        if (mapped != null) {
            Identifier id = parseId(mapped);
            if (id != null) return id;
        }

        // 直接当 id 用
        return parseId(trimmed);
    }

    public static String displayNameOf(Identifier id) {
        if (id == null) {
            return null;
        }

        // 1. 尝试直接查完整 ID（如 minecraft:book）
        String direct = idToName.get(id.toString());
        if (direct != null) {
            return direct;
        }

        // 2. 如果不是原版命名空间，且上面没查到，直接返回 null
        if (!"minecraft".equals(id.getNamespace())) {
            return null;
        }

        // 3. 尝试查纯路径（如附魔、附魔书的 mending / enchanted_book）
        return idToName.get(id.getPath());
    }

    public static String displayNameOf(String idText) {
        return displayNameOf(parseId(idText));
    }

    private static Identifier parseId(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return null;
        Identifier id = Identifier.tryParse(text);
        return id != null ? id : Identifier.tryParse("minecraft:" + text);
    }
}