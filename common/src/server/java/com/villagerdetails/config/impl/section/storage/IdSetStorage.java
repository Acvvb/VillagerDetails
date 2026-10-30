package com.villagerdetails.config.impl.section.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.impl.UnifiedConfigFile;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「{@code Identifier} 集合」型子配置的通用存储。
 *
 * <p>负责三件事，各子配置直接复用：
 * <ul>
 *   <li>内存里维护一个 {@code Set<Identifier>}；</li>
 *   <li>同步字符串 {@code id,id,...} 的序列化 / 反序列化；</li>
 *   <li>从统一 JSON 的某个顶层节读 / 写。</li>
 * </ul>
 *
 * <p>各子配置的静态 API 只是对本类的薄包装。
 */
public final class IdSetStorage {

    private static final Logger log = LogManager.getLogger(IdSetStorage.class);

    private final String section;
    private final Set<Identifier> ids = ConcurrentHashMap.newKeySet();

    public IdSetStorage(String section) {
        this.section = section;
    }

    // ==============================================================
    // 查询 / 修改
    // ==============================================================

    public boolean contains(Identifier id) {
        return ids.contains(id);
    }

    public List<Identifier> snapshot() {
        return ids.stream()
                .sorted(Comparator.comparing(Identifier::toString))
                .toList();
    }

    public void add(Identifier id) {
        ids.add(id);
        save();
    }

    public void addAll(Set<Identifier> more) {
        if (more.isEmpty()) return;
        ids.addAll(more);
        save();
    }

    public void remove(Identifier id) {
        if (ids.remove(id)) save();
    }

    public void reset() {
        ids.clear();
        save();
    }

    // ==============================================================
    // 同步字符串
    // ==============================================================

    public String toSyncString() {
        if (ids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Identifier id : ids) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    /**
     * 客户端：用同步字符串覆盖内存（不落盘）。
     */
    public void applyFromSyncString(String data) {
        ids.clear();
        ids.addAll(parseIds(data));
    }

    /**
     * 解析 {@code id,id,...}，空 / 非法项跳过。
     */
    public static Set<Identifier> parseIds(String data) {
        if (data == null || data.isEmpty()) return Set.of();
        Set<Identifier> out = new LinkedHashSet<>();
        for (String part : data.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            Identifier id = Identifier.tryParse(p);
            if (id != null) out.add(id);
        }
        return out;
    }

    // ==============================================================
    // 文件读写
    // ==============================================================

    public void load() {
        ids.clear();
        JsonObject sec = UnifiedConfigFile.getSection(section);
        for (Map.Entry<String, JsonElement> e : sec.entrySet()) {
            Identifier id = Identifier.tryParse(e.getKey());
            if (id != null) ids.add(id);
        }
        log.info("[{}] 已加载 {} 项", section, ids.size());
    }

    public void save() {
        JsonObject sec = new JsonObject();
        ids.forEach(id -> sec.addProperty(id.toString(), true));
        UnifiedConfigFile.setSection(section, sec);
        UnifiedConfigFile.save();
    }
}