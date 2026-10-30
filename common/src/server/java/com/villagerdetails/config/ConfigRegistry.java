package com.villagerdetails.config;

import com.villagerdetails.config.impl.IdTranslationConfig;
import com.villagerdetails.config.impl.RuleConfig;
import com.villagerdetails.config.impl.UnifiedConfigFile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigRegistry {

    private ConfigRegistry() {
    }

    private static final Logger log = LogManager.getLogger(ConfigRegistry.class);
    private static final Map<String, ReloadableConfig> REGISTRY = new LinkedHashMap<>();

    /** SERVER_STARTED 回灌钩子是否已登记（防历史坑：以前被单独注册过两次） */
    private static boolean startedHookRegistered;

    static {
        register(IdTranslationConfig.INSTANCE);
        // 顺序有讲究：RuleConfig 必须排在 UnifiedConfigFile 前面 ——
        // 先把持久化的规则状态回灌进 RuleCache，随后 UnifiedConfigFile 里的子配置
        // （ServerLangConfig 等）才能读到「规则现在是什么」并据此对齐自己的状态。
        register(RuleConfig.INSTANCE);
        register(UnifiedConfigFile.INSTANCE);
        // 注意：网络版本兼容策略【不在这里注册】—— 它已固化成编译期常量
        // （com.villagerdetails.network.channel.NetworkConstants），不读也不写外部配置。
    }

    // ==============================================================
    // 事件注册：把「开服回灌一次」收进类里
    // ==============================================================

    /**
     * 登记 {@code SERVER_STARTED → reloadAll}。
     *
     * <p>由集中初始化入口调用一次；重复调用只打 warn、不重复注册。
     * 以前这条是散在外面 register 的，配合 {@code ServerLifecycleListener}
     * 曾导致每次开服回灌两遍。
     */
    public static synchronized void registerStartedHook() {
        if (startedHookRegistered) {
            log.warn("SERVER_STARTED 回灌回调重复注册被忽略");
            return;
        }
        startedHookRegistered = true;
        ServerLifecycleEvents.SERVER_STARTED.register(ConfigRegistry::reloadAll);
    }

    // ==============================================================
    // 配置注册（原有）
    // ==============================================================

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

    public static ReloadableConfig get(String name) {
        return REGISTRY.get(name);
    }

    public static List<String> getAllNames() {
        return List.copyOf(REGISTRY.keySet());
    }

    public record Result(List<String> loaded, List<String> failed) {
        public boolean success() {
            return failed.isEmpty();
        }
    }

    public static Result reloadAll(MinecraftServer server) {
        List<String> loaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (ReloadableConfig config : REGISTRY.values()) {
            try {
                config.reload(server);
                loaded.add(config.configName());
                // 刻意用 debug：每份配置自己都会打一条带名字和关键值的 INFO
                // （「已加载交易索引」「[NoSqueeze] 已加载 …」「[PlayerHeadSitting] …」），
                // 这里再来一条 INFO 的话，启动日志里同一份配置会连着出现两行，看着就像加载了两遍。
                log.debug("已重载：{}", config.configName());
            } catch (Exception e) {
                log.error("重载 {} 失败", config.configName(), e);
                failed.add(config.configName() + ": " + e.getMessage());
            }
        }

        return new Result(loaded, failed);
    }
}