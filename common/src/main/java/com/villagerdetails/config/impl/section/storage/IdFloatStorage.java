package com.villagerdetails.config.impl.section.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.impl.UnifiedConfigFile;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「{@code Identifier → float}」型子配置的通用存储。
 *
 * <p>职责与 {@link IdSetStorage} 对称，只是值类型换成 float，
 * 同步字符串格式为 {@code id=value,id=value,...}。
 */
public final class IdFloatStorage {

    private static final Logger LOGGER = LogManager.getLogger(IdFloatStorage.class);

    private final String section;
    private final Map<Identifier, Float> values = new ConcurrentHashMap<>();

    public IdFloatStorage(String section) {
        this.section = section;
    }

    // ==============================================================
    // 查询 / 修改
    // ==============================================================

    public Float get(Identifier id) {
        return values.get(id);
    }

    public Map<Identifier, Float> snapshot() {
        return Map.copyOf(values);
    }

    public void put(Identifier id, float value) {
        values.put(id, value);
        save();
    }

    public void remove(Identifier id) {
        if (values.remove(id) != null) save();
    }

    public void reset() {
        values.clear();
        save();
    }

    // ==============================================================
    // 同步字符串
    // ==============================================================

    public String toSyncString() {
        if (values.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Identifier, Float> e : values.entrySet()) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 客户端：用同步字符串覆盖内存（不落盘）。
     */
    public void applyFromSyncString(String data) {
        values.clear();
        if (data == null || data.isEmpty()) return;
        for (String part : data.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq <= 0 || eq == p.length() - 1) continue;
            Identifier id = Identifier.tryParse(p.substring(0, eq).trim());
            if (id == null) continue;
            try {
                values.put(id, Float.parseFloat(p.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
                // 非法数值跳过
            }
        }
    }

    // ==============================================================
    // 文件读写
    // ==============================================================

    public void load() {
        values.clear();
        JsonObject sec = UnifiedConfigFile.getSection(section);
        for (Map.Entry<String, JsonElement> e : sec.entrySet()) {
            Identifier id = Identifier.tryParse(e.getKey());
            if (id == null || !e.getValue().isJsonPrimitive()) continue;
            try {
                values.put(id, e.getValue().getAsFloat());
            } catch (Exception ignored) {
                // 非法数值跳过
            }
        }
        LOGGER.info("[{}] 已加载 {} 项", section, values.size());
    }

    public void save() {
        JsonObject sec = new JsonObject();
        values.forEach((id, v) -> sec.addProperty(id.toString(), v));
        UnifiedConfigFile.setSection(section, sec);
        UnifiedConfigFile.save();
    }
}