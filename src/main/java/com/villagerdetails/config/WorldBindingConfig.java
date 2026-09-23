package com.villagerdetails.config;

import com.mojang.serialization.Codec;
import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class WorldBindingConfig extends SavedData {

    private static final String DATA_ID = "villager_details:binding_config";

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

    public static WorldBindingConfig getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public void setBindingState(String key, boolean enabled) {
        bindingStates.put(key, enabled ? 1 : 0);
        setDirty();
    }

    public boolean getBindingState(String key) {
        return bindingStates.getOrDefault(key, 0) == 1;
    }

    public void toggleBindingState(String key) {
        boolean current = getBindingState(key);
        setBindingState(key, !current);
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
     * 核心：将存档配置同步到 RuleCache
     * - 存档中有记录 → 用存档的值
     * - 存档中无记录 → 用枚举默认值 type.isState()
     */
    public void syncToSwitch() {
        Map<RuleType, Boolean> syncMap = new HashMap<>();
        for (RuleType type : RuleType.values()) {
            String key = type.getRegisterName();
            boolean enabled;
            if (bindingStates.containsKey(key)) {
                // 配置中有，按配置
                enabled = bindingStates.get(key) == 1;
            } else {
                // 配置中没有，按枚举默认值
                enabled = type.isState();
            }
            syncMap.put(type, enabled);
        }
        RuleCache.syncRules(syncMap);
    }

    public boolean isBindingEnabled(int id) {
        RuleType type = RuleType.getRuleTypeById(id);
        if (type != null) {
            String key = type.getRegisterName();
            if (bindingStates.containsKey(key)) {
                return bindingStates.get(key) == 1;
            }
            return type.isState();
        }
        return false;
    }
}