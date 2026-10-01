package com.villagerdetails.config;

import com.villagerdetails.config.impl.BlockCollectableConfig;
import com.villagerdetails.config.impl.BlockMiningResistanceConfig;
import com.villagerdetails.config.impl.IdTranslationConfig;
import com.villagerdetails.config.impl.RuleConfig;
import com.villagerdetails.config.impl.ServerLangConfig;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigRegistry {

    private ConfigRegistry() {}

    // ★ 字段声明放最前
    private static final Logger log = LogManager.getLogger(ConfigRegistry.class);
    private static final Map<String, ReloadableConfig> REGISTRY = new LinkedHashMap<>();

    // 静态注册——此时 REGISTRY 已经初始化
    static {
        register(IdTranslationConfig.INSTANCE);
        register(ServerLangConfig.INSTANCE);
        register(RuleConfig.INSTANCE);
        register(BlockMiningResistanceConfig.INSTANCE);
        register(BlockCollectableConfig.INSTANCE);
    }

    public static synchronized void register(ReloadableConfig config) {
        if (config == null) return;
        String name = config.configName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("配置名不能为空");
        }
        if (REGISTRY.putIfAbsent(name, config) != null) {
            throw new IllegalStateException("配置名重复：" + name);
        }
        log.debug("已注册：{}", name);
    }

    public static ReloadableConfig get(String name) { return REGISTRY.get(name); }
    public static List<ReloadableConfig> getAll() { return List.copyOf(REGISTRY.values()); }
    public static List<String> getAllNames() { return List.copyOf(REGISTRY.keySet()); }

    public record Result(List<String> loaded, List<String> failed) {
        public boolean success() { return failed.isEmpty(); }
    }

    public static Result reloadAll(MinecraftServer server) {
        List<String> loaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (ReloadableConfig config : REGISTRY.values()) {
            try {
                config.reload(server);
                loaded.add(config.configName());
                log.info("已重载：{}", config.configName());
            } catch (Exception e) {
                log.error("重载 {} 失败", config.configName(), e);
                failed.add(config.configName() + ": " + e.getMessage());
            }
        }

        return new Result(loaded, failed);
    }
}