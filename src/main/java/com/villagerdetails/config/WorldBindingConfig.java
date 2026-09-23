package com.villagerdetails.config;

import com.mojang.serialization.Codec;
import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

public class WorldBindingConfig extends SavedData {

    private static final String DATA_ID = "villager_details:binding_config";

    /**
     * 只存储“与默认配置不一致”的差异项。
     * key = RuleType.getRegisterName()，value = 1(开启) / 0(关闭)。
     * 与默认值相同的项不会被写入这里，因此持久化文件里只保留差异。
     */
    private final Map<String, Integer> bindingStates = new HashMap<>();

    private static final Codec<Map<String, Integer>> STATE_MAP_CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.INT);

    public static final Codec<WorldBindingConfig> CODEC = STATE_MAP_CODEC.xmap(
            map -> {
                WorldBindingConfig config = new WorldBindingConfig();
                config.bindingStates.putAll(map);
                return config;
            },
            config -> new HashMap<>(config.bindingStates)
    );

    public static final SavedDataType<WorldBindingConfig> TYPE = new SavedDataType<>(
            Objects.requireNonNull(Identifier.tryParse(DATA_ID)),
            WorldBindingConfig::new,
            CODEC,
            DataFixTypes.LEVEL
    );

    public WorldBindingConfig() {
        super();
    }

    /**
     * 服务器级配置（全局唯一），存到 <world>/data/ 而不是某个维度下。
     * 规则开关是全服共享的，必须使用 server 级数据存储，避免多维度各存一份导致互相覆盖。
     */
    public static WorldBindingConfig getOrCreate(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * 设置规则的“生效状态”。只持久化与默认值不同的项：
     * - 与默认一致 → 从差异表移除（持久化文件里不出现）
     * - 与默认不同 → 记录到差异表
     */
    public void setBindingState(String key, boolean enabled) {
        RuleType type = RuleType.getRuleTypeByRegisterName(key);
        if (type != null && type.isState() == enabled) {
            bindingStates.remove(key);
        } else {
            bindingStates.put(key, enabled ? 1 : 0);
        }
        setDirty();
    }

    /**
     * 读取规则的“生效状态”：差异表有记录取记录，否则取默认值。
     */
    public boolean getBindingState(String key) {
        RuleType type = RuleType.getRuleTypeByRegisterName(key);
        if (bindingStates.containsKey(key)) {
            return bindingStates.get(key) == 1;
        }
        return type != null && type.isState();
    }

    public void toggleBindingState(String key) {
        setBindingState(key, !getBindingState(key));
    }

    public void removeBindingState(String key) {
        bindingStates.remove(key);
        setDirty();
    }

    public Map<String, Boolean> getAllBindingStates() {
        Map<String, Boolean> result = new HashMap<>();
        bindingStates.forEach((k, v) -> result.put(k, v == 1));
        return result;
    }

    public void resetAll() {
        bindingStates.clear();
        setDirty();
    }

    /**
     * 将持久化配置同步到内存开关 RuleCache：
     * - 差异表有记录 → 用差异表的值
     * - 差异表无记录 → 用枚举默认值 type.isState()
     * 同时清理历史遗留的未知键（旧版本规则名），保持持久化文件干净。
     */
    public void syncToSwitch() {
        Map<RuleType, Boolean> syncMap = new HashMap<>();
        for (RuleType type : RuleType.values()) {
            syncMap.put(type, getBindingState(type.getRegisterName()));
        }

        // 清理历史遗留键（不再对应任何 RuleType 的旧规则名）
        Iterator<String> it = bindingStates.keySet().iterator();
        boolean removed = false;
        while (it.hasNext()) {
            String key = it.next();
            if (RuleType.getRuleTypeByRegisterName(key) == null) {
                it.remove();
                removed = true;
            }
        }
        if (removed) {
            setDirty();
        }

        RuleCache.syncRules(syncMap);
    }

    public boolean isBindingEnabled(int id) {
        RuleType type = RuleType.getRuleTypeById(id);
        return type != null && getBindingState(type.getRegisterName());
    }
}
