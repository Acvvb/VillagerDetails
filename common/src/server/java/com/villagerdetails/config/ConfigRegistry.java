package com.villagerdetails.config;

import com.villagerdetails.config.impl.IdTranslationConfig;
import com.villagerdetails.config.impl.RuleConfig;
import com.villagerdetails.config.impl.UnifiedConfigFile;
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

    public static List<String> getAllNames() {
        return List.copyOf(REGISTRY.keySet());
    }

    public record Result(List<String> loaded, List<String> failed) {
        public boolean success() {
            return failed.isEmpty();
        }
    }

    public static Result reloadAll(MinecraftServer server) {
        // 迁移必须跑在「读任何配置」之前：它可能会整份重置统一配置或改写字段，
        // 子配置随后加载到的才是迁移后的内容。
        MigrationManager.migrateIfNeeded(server);

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

    /**
     * {@code /ec c reload} 用：把「物理文件」当单位整份重读。
     *
     * <p>和 {@link #reloadAll(MinecraftServer)} 的区别只有一处：{@link UnifiedConfigFile}
     * 走 {@link UnifiedConfigFile#reloadFromDisk(MinecraftServer)} 真的去读磁盘，
     * 而不是被 {@code ensureLoaded} 短路成「内存里的旧 root 再灌一遍」。
     * 不带参数、带参数都走这一条 —— 所有 section 都在同一个 {@code config.json} 里，
     * 只重载一份既省不了 IO，还会多出一条驱动路径。
     */
    public static Result reloadAllFromDisk(MinecraftServer server) {
        // 与 reloadAll 同一套保证：先迁移，再从磁盘读
        MigrationManager.migrateIfNeeded(server);

        List<String> loaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (ReloadableConfig config : REGISTRY.values()) {
            try {
                if (config == UnifiedConfigFile.INSTANCE) {
                    UnifiedConfigFile.reloadFromDisk(server);
                } else {
                    config.reload(server);
                }
                loaded.add(config.configName());
                log.debug("已重载（磁盘）：{}", config.configName());
            } catch (Exception e) {
                log.error("重载 {} 失败", config.configName(), e);
                failed.add(config.configName() + ": " + e.getMessage());
            }
        }

        return new Result(loaded, failed);
    }

    /**
     * {@code /ec c reload <name>} 的可点名清单：
     * {@link #getAllNames()} 的 3 个父配置 + 规则侧 12 个 section 配置（按 {@code configName()} 去重保序）。
     *
     * <p>列出来只是「让人知道有这些名字」；执行时一律整份重读（见 {@link #reloadAllFromDisk}）。
     */
    public static List<String> getReloadableNames() {
        List<String> names = new ArrayList<>(REGISTRY.keySet());
        for (ReloadableConfig config : RuleType.getReloadableConfigs()) {
            String name = config.configName();
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 启动期自检：规则侧配置必须「一实例一规则、一名一配置、且不在 {@link #REGISTRY} 里」。
     *
     * <p>为什么要查：
     * <ul>
     *   <li>两条规则共用同一个配置实例 → 同一次重载会应用两遍，两份规则的状态互相覆盖；</li>
     *   <li>两个配置 {@code configName()} 相同 → 同一个 section 被两份配置抢着写；</li>
     *   <li>规则配置又被注册进 {@link #REGISTRY} → {@link #reloadAll} 会把它重载两次
     *       （直接一次 + {@link UnifiedConfigFile} 的子配置循环一次）。</li>
     * </ul>
     * 三种都是「静默生效」的错误，跑起来看不出来，所以放到启动期直接炸掉。
     */
    public static void verifyReloadableConfigs() {
        Map<ReloadableConfig, RuleType> byInstance = new IdentityHashMap<>();
        Map<String, RuleType> byName = new HashMap<>();

        for (RuleType rule : RuleType.values()) {
            ReloadableConfig config = rule.getReloadableConfig();
            if (config == null) {
                continue;
            }

            RuleType previous = byInstance.putIfAbsent(config, rule);
            if (previous != null) {
                throw new IllegalStateException("配置实例冲突：" + config.configName()
                        + " 同时被 " + previous.getRegisterName() + " 与 " + rule.getRegisterName()
                        + " 使用（会被重载两遍、两份规则互相覆盖）");
            }

            previous = byName.putIfAbsent(config.configName(), rule);
            if (previous != null) {
                throw new IllegalStateException("配置名冲突：" + config.configName()
                        + " 被 " + previous.getRegisterName() + " 与 " + rule.getRegisterName()
                        + " 同时使用（会争抢同一个 section）");
            }

            if (REGISTRY.containsKey(config.configName())) {
                throw new IllegalStateException("规则配置 " + config.configName()
                        + "（" + rule.getRegisterName() + "）不应再注册进 ConfigRegistry："
                        + "UnifiedConfigFile 已经会驱动它，再注册会被重载两次");
            }
        }
    }
}