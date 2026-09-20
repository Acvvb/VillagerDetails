package com.villagerdetails.config;

import com.mojang.serialization.Codec;
import com.villagerdetails.event.type.BindingType;
import com.villagerdetails.permission.BindingTypeSwitch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.util.datafix.DataFixTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class WorldBindingConfig extends SavedData {

    private static final String DATA_ID = "villager_details:binding_config";

    private final Map<String, Integer> bindingStates = new HashMap<>();

    // ==================== Codec 定义 ====================

    private static final Codec<Map<String, Integer>> STATE_MAP_CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.INT);

    public static final Codec<WorldBindingConfig> CODEC = STATE_MAP_CODEC.xmap(
            map -> {
                WorldBindingConfig config = new WorldBindingConfig();
                config.bindingStates.putAll(map);
                return config;
            },
            config -> config.bindingStates
    );

    public static final SavedDataType<WorldBindingConfig> TYPE = new SavedDataType<>(
            Objects.requireNonNull(Identifier.tryParse(DATA_ID)),
            WorldBindingConfig::new,
            CODEC,
            DataFixTypes.LEVEL
    );

    // ==================== 构造函数 ====================

    public WorldBindingConfig() {
        super();
    }

    // ==================== 获取实例 ====================

    /**
     * 从指定维度获取配置，不存在则自动创建
     */
    public static WorldBindingConfig getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ==================== 业务方法 ====================

    /**
     * 设置某个绑定类型的状态
     */
    public void setBindingState(String key, boolean enabled) {
        bindingStates.put(key, enabled ? 1 : 0);
        setDirty(); // 标记数据已修改，触发存档保存
    }

    /**
     * 获取某个绑定类型的状态，默认返回 false
     */
    public boolean getBindingState(String key) {
        return bindingStates.getOrDefault(key, 0) == 1;
    }

    /**
     * 切换某个绑定类型的状态
     */
    public void toggleBindingState(String key) {
        boolean current = getBindingState(key);
        setBindingState(key, !current);
    }

    /**
     * 移除某个绑定类型的配置
     */
    public void removeBindingState(String key) {
        bindingStates.remove(key);
        setDirty();
    }

    /**
     * 获取所有绑定状态的副本（防止外部直接修改）
     */
    public Map<String, Boolean> getAllBindingStates() {
        Map<String, Boolean> result = new HashMap<>();
        bindingStates.forEach((k, v) -> result.put(k, v == 1));
        return result;
    }

    /**
     * 重置所有绑定状态
     */
    public void resetAll() {
        bindingStates.clear();
        setDirty();
    }

    // ==================== 存档读写 ====================

    /**
     * 将数据保存到 NBT（本地存档）
     */
    public CompoundTag save(CompoundTag tag) {
        bindingStates.forEach(tag::putInt);
        return tag;
    }

    /**
     * 从 NBT 加载数据（本地存档）
     */
    public static WorldBindingConfig load(CompoundTag tag) {
        WorldBindingConfig config = new WorldBindingConfig();
        for (String key : tag.keySet()) {
            config.bindingStates.put(key, tag.getIntOr(key,0));
        }
        return config;
    }

    /**
     * 将配置中的绑定状态同步到 BindingTypeSwitch（内存开关）
     * 在世界加载时调用
     */
    public void syncToSwitch() {
        for (BindingType type : BindingType.values()) {
            BindingTypeSwitch.setEnabled(type, getBindingState(type.getName()));
        }
    }
}