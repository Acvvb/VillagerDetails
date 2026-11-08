package com.villagerdetails.config;

import com.villagerdetails.config.impl.*;
import com.villagerdetails.config.impl.base.AbstractWorldConfig;
import com.villagerdetails.config.migration.MigrationManager;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConfigRegistry {

    private ConfigRegistry() {
    }

    private static final Logger log = LogManager.getLogger(ConfigRegistry.class);
    private static final Map<String, ReloadableConfig> REGISTRY = new LinkedHashMap<>();
    private static final AtomicBoolean startedHookRegistered = new AtomicBoolean();

    static {
        register(TradeIndexConfig.INSTANCE);
        register(RuleSwitchConfig.INSTANCE);
        register(RuleConfig.INSTANCE);
        register(VillagerTradeCacheConfig.INSTANCE);
    }

    public static void registerStartedHook() {
        if (!startedHookRegistered.compareAndSet(false, true)) {
            log.warn("SERVER_STARTED 回灌回调重复注册被忽略");
            return;
        }
        ServerLifecycleEvents.SERVER_STARTED.register(ConfigRegistry::reloadAll);
    }

    private static void register(ReloadableConfig config) {
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

    public static List<String> getAllNames() {
        return List.copyOf(REGISTRY.keySet());
    }

    // ==============================================================
    // 重载逻辑
    // ==============================================================

    /**
     * 开服回灌（生命周期事件调用）：即使某个配置坏了也不能崩服，捕获后只打日志。
     */
    public static void reloadAll(MinecraftServer server) {
        MigrationManager.migrateIfNeeded(server);
        for (ReloadableConfig config : REGISTRY.values()) {
            try {
                config.reload(server);
                log.debug("已重载：{}", config.configName());
            } catch (Exception e) {
                log.error("开服回灌 {} 失败", config.configName(), e);
            }
        }
    }

    /**
     * {@code /ec c reload}（无参数）：把所有物理文件从磁盘整份重读。
     *
     * <p><b>超时语义</b>：读盘前先 {@code flushAndAwait}，等待上限统一取
     * {@link AbstractWorldConfig#RELOAD_AWAIT_MS}。超时只会记日志并继续按内存内容处理
     * （最坏情况是读到磁盘上的旧版本），<b>不会清空内存</b>。
     *
     * @return 成功加载的配置名列表
     * @throws RuntimeException 如果任何一个配置重载失败
     */
    public static List<String> reloadAllFromDisk(MinecraftServer server) {
        RuleConfig.INSTANCE.flushAndAwait(server, AbstractWorldConfig.RELOAD_AWAIT_MS);
        MigrationManager.migrateIfNeeded(server);

        List<String> loaded = new ArrayList<>();
        for (ReloadableConfig config : REGISTRY.values()) {
            try {
                config.reloadFromDisk(server);
                loaded.add(config.configName());
                log.debug("已重载（磁盘）：{}", config.configName());
            } catch (Exception e) {
                log.error("重载 {} 失败", config.configName(), e);
                throw new RuntimeException(config.configName() + " 重载失败: " + e.getMessage(), e);
            }
        }
        return loaded;
    }

    /**
     * {@code /ec c reload <name>}：只从磁盘重读指定的那一份配置。
     *
     * <p>超时语义同 {@link #reloadAllFromDisk(MinecraftServer)}：等不到也不清内存。
     *
     * @return 成功加载的配置名列表
     * @throws RuntimeException 如果配置名不存在或重载失败
     */
    public static List<String> reloadSpecificFromDisk(MinecraftServer server, String name) {
        RuleConfig.INSTANCE.flushAndAwait(server, AbstractWorldConfig.RELOAD_AWAIT_MS);
        MigrationManager.migrateIfNeeded(server);

        ReloadableConfig config = REGISTRY.get(name);
        if (config == null) {
            throw new IllegalArgumentException("未知的配置名: " + name);
        }

        try {
            config.reloadFromDisk(server);
            log.debug("已重载单份配置（磁盘）：{}", config.configName());
            return List.of(config.configName());
        } catch (Exception e) {
            log.error("重载 {} 失败", config.configName(), e);
            throw new RuntimeException(config.configName() + " 重载失败: " + e.getMessage(), e);
        }
    }

    public static List<String> getReloadableNames() {
        return new ArrayList<>(REGISTRY.keySet());
    }

    public static void verifyReloadableConfigs() {
        Map<ReloadableConfig, RuleType> byInstance = new IdentityHashMap<>();
        Map<String, RuleType> byName = new HashMap<>();

        for (RuleType rule : RuleType.values()) {
            ReloadableConfig config = rule.getReloadableConfig();
            if (config == null) continue;

            RuleType previous = byInstance.putIfAbsent(config, rule);
            if (previous != null) {
                throw new IllegalStateException("配置实例冲突：" + config.configName() + " 同时被两处使用");
            }

            previous = byName.putIfAbsent(config.configName(), rule);
            if (previous != null) {
                throw new IllegalStateException("配置名冲突：" + config.configName() + " 被两处使用");
            }

            if (REGISTRY.containsKey(config.configName())) {
                throw new IllegalStateException("规则配置 " + config.configName() + " 不应再注册进 ConfigRegistry");
            }
        }
    }
}