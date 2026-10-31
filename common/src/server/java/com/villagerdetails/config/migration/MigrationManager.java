package com.villagerdetails.config.migration;

import com.villagerdetails.config.impl.UnifiedConfigFile;
import com.villagerdetails.util.ModVersion;
import net.fabricmc.loader.api.Version;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 配置版本迁移的执行器：<b>启动时先于所有子配置</b>比对版本、执行迁移或整份重置。
 *
 * <h2>版本从哪来</h2>
 * 版本标记写在统一配置的根节点上（{@code villagerdetails/config.json} 顶层）：
 * <pre>{@code
 * {
 *   "__configVersion__": "1.1.0",
 *   "PortalBinding": { ... },
 *   ...
 * }
 * }</pre>
 *
 * <h2>决策</h2>
 * <ol>
 *     <li><b>全新存档</b>（文件不存在 / 只有版本标记）：不迁移，直接写入当前版本；</li>
 *     <li><b>没有版本标记但有内容</b>：视为极其古老的版本（{@link #UNKNOWN_VERSION}，
 *         即 1.0.x 及更早）—— 1.1.0 起不再兼容旧格式，走整份重置；</li>
 *     <li><b>版本 &lt; {@link #LEGACY_RESET_BELOW}</b>（1.1.0）：整份重置统一配置；</li>
 *     <li><b>{@code LEGACY_RESET_BELOW <= 版本 < 当前版本}</b>：按
 *         {@link #MIGRATIONS} 的顺序执行登记过的迁移（每步执行完立刻推进版本）；</li>
 *     <li><b>版本 >= 当前版本</b>：什么都不做（连文件都不重写）。</li>
 * </ol>
 *
 * <h2>怎么加一条迁移</h2>
 * <ol>
 *     <li>在 {@code com.villagerdetails.config.migration.version} 子包下写一个
 *         {@link ConfigMigration} 实现（类名形如 {@code V1_2_0_Migration}，
 *         与它的 {@code targetVersion()} 对应）；</li>
 *     <li>在 {@link #MIGRATIONS} 里追加一个实例（<b>手动登记</b>，不扫包 —— 迁移类极少，
 *         扫包带来的不确定性和启动开销都不划算）；</li>
 *     <li>把 {@link #CURRENT_CONFIG_VERSION} 抬到这次发布的版本。</li>
 * </ol>
 *
 * <h2>为什么失败只记日志、不拦启动</h2>
 * 配置坏了还能进服、服主能看日志修；拦启动会把整个服务器锁死。失败时<b>不</b>推进版本号，
 * 下次启动会重试同一步。
 *
 * <h2>范围</h2>
 * 本框架管的是<b>统一配置文件</b>（{@code config.json}）的版本。规则开关
 * （{@code villager_details_binding.json}）与交易索引（{@code trade_index.json}）各有各的
 * 容错加载器（未知规则名会被清掉、非法条目会被丢弃），不参与这里的版本判定；
 * 将来它们真需要破坏性迁移时，在 {@link ConfigMigration#migrate(MinecraftServer)} 里
 * 显式处理对应文件即可。
 */
public final class MigrationManager {

    private static final Logger log = LogManager.getLogger(MigrationManager.class);

    /**
     * 当前代码期望的配置版本。
     *
     * <p><b>发生破坏性配置更改时手动抬这个值</b>（和 {@code NetworkConstants.MIN_COMPATIBLE_VERSION}
     * 同一种约定：协议/数据格式的版本号属于发布决定，不跟着 {@code gradle.properties} 自动漂移）。
     */
    public static final String CURRENT_CONFIG_VERSION = "1.1.0";

    /**
     * 低于这个版本的存档视为「不兼容的旧格式」：不逐条迁移，直接整份重置统一配置。
     *
     * <p>1.1.0 是重新整理配置格式的分界线，之前的存档一律放弃兼容。
     */
    public static final String LEGACY_RESET_BELOW = "1.1.0";

    /**
     * 文件里有内容、却没有 {@code __configVersion__} 字段时按这个版本处理
     * （比 1.0.x 更早；必然低于 {@link #LEGACY_RESET_BELOW}，所以会走整份重置）。
     */
    private static final String UNKNOWN_VERSION = "0.0.0";

    /**
     * 已登记的迁移，按 {@link ConfigMigration#targetVersion()} 升序执行。
     *
     * <p><b>具体的迁移类请写在 {@code com.villagerdetails.config.migration.version}
     * 包下，然后在这里手动登记。</b>本包只留框架代码（接口 / 执行器 / 包说明）。
     *
     * <p>当前（1.1.0）为空：1.1.0 之前直接整份重置，1.1.0 起没有破坏性更改。
     * 加迁移时在这里追加一项即可，例如：
     * <pre>{@code
     * import com.villagerdetails.config.migration.version.V1_2_0_Migration;
     *
     * private static final List<ConfigMigration> MIGRATIONS = List.of(
     *         new V1_2_0_Migration()   // targetVersion() = "1.2.0"
     * );
     * }</pre>
     *
     * <p>登记顺序不重要：执行前会按目标版本重新排序，每步执行完立即推进版本。
     */
    private static final List<ConfigMigration> MIGRATIONS = List.of();

    private MigrationManager() {
    }

    /**
     * 配置加载前调用：按需执行迁移 / 重置。永远不抛异常（失败只记 ERROR，下次启动重试）。
     *
     * @param server 当前服务器；{@code null} 时直接返回（离线单测 / 未启动场景）
     */
    public static synchronized void migrateIfNeeded(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            migrate(server);
        } catch (Exception e) {
            // 不拦启动：配置坏了还能进服修；版本号没推进，下次启动会重试
            log.error("配置迁移失败（本次沿用现有配置，下次启动会重试）：", e);
        }
    }

    private static void migrate(MinecraftServer server) {
        boolean fileExists = UnifiedConfigFile.fileExists(server);
        // 先把磁盘内容读进内存 root（不驱动子配置），迁移直接改 root
        UnifiedConfigFile.loadForMigration(server);
        boolean hasContent = UnifiedConfigFile.hasContent();
        String stored = UnifiedConfigFile.storedConfigVersion();

        // 1) 全新存档：没有文件、或文件里什么都没有 → 直接盖章
        if (!fileExists || !hasContent) {
            stamp(stored, CURRENT_CONFIG_VERSION, "全新存档，写入配置版本");
            return;
        }

        // 2) 有内容但没有版本标记 → 极其古老（1.0.x 及更早）
        if (stored == null) {
            log.warn("统一配置缺少 {} 字段，按 {} 处理（1.1.0 之前的存档不再兼容）",
                    UnifiedConfigFile.CONFIG_VERSION_KEY, UNKNOWN_VERSION);
            stored = UNKNOWN_VERSION;
        } else if (compareVersions(stored, UNKNOWN_VERSION) < 0 || ModVersion.parse(stored).isEmpty()) {
            // 版本串无法解析（手改坏了 / 未来格式）→ 当成最老版本，走重置
            log.warn("统一配置的版本号无法解析（{}），按 {} 处理", stored, UNKNOWN_VERSION);
            stored = UNKNOWN_VERSION;
        }

        // 3) 旧格式：不逐条迁移，整份重置
        if (!atLeast(stored, LEGACY_RESET_BELOW)) {
            legacyReset(stored);
            return;
        }

        // 4) 已是最新：不动文件（避免每次启动都整份重写）
        if (atLeast(stored, CURRENT_CONFIG_VERSION)) {
            log.debug("配置版本 {} 已是最新（{}），跳过迁移", stored, CURRENT_CONFIG_VERSION);
            return;
        }

        // 5) 逐条执行迁移
        runMigrations(server, stored);
    }

    /**
     * 版本过低：清空统一配置的全部 section，然后盖上当前版本。
     *
     * <p>只清「统一配置」：各子配置随后会按默认值重新生成并落盘；规则开关 / 交易索引
     * 各有各的容错加载器（见类注释），不在这里动。
     */
    private static void legacyReset(String stored) {
        log.warn("配置版本 {} 低于 {}：1.1.0 起不再兼容旧格式，整份重置统一配置（各 section 恢复默认）",
                stored, LEGACY_RESET_BELOW);
        UnifiedConfigFile.clearAllSections();
        UnifiedConfigFile.setConfigVersion(CURRENT_CONFIG_VERSION);
        UnifiedConfigFile.save();
    }

    /**
     * 依次执行「目标版本高于存档版本」的迁移；每步执行完立刻推进版本并落盘。
     *
     * <p>任一步抛异常会中断整个流程（异常由 {@link #migrateIfNeeded} 记录），
     * 已经完成并被推进的步骤下次不会重跑。
     */
    private static void runMigrations(MinecraftServer server, String fromVersion) {
        List<ConfigMigration> pending = MIGRATIONS.stream()
                .filter(migration -> !atLeast(fromVersion, migration.targetVersion()))
                .sorted(Comparator.comparing(ConfigMigration::targetVersion, MigrationManager::compareVersions))
                .toList();

        if (pending.isEmpty()) {
            log.info("配置版本 {} 低于 {}，但没有登记需要执行的迁移 —— 直接盖章", fromVersion,
                    CURRENT_CONFIG_VERSION);
        }
        for (ConfigMigration migration : pending) {
            log.info("执行配置迁移：→ {}", migration.targetVersion());
            migration.migrate(server);
            // 每步之后立刻推进版本：中途崩溃时下次从这里继续，已完成的步骤不会重跑
            UnifiedConfigFile.setConfigVersion(migration.targetVersion());
            UnifiedConfigFile.save();
        }

        UnifiedConfigFile.setConfigVersion(CURRENT_CONFIG_VERSION);
        UnifiedConfigFile.save();
        log.info("配置迁移完成：{} → {}", fromVersion, CURRENT_CONFIG_VERSION);
    }

    /**
     * 把版本号写进统一配置并落盘（已经是目标版本时什么也不做）。
     */
    private static void stamp(@Nullable String stored, String version, String reason) {
        if (version.equals(stored)) {
            return;
        }
        UnifiedConfigFile.setConfigVersion(version);
        UnifiedConfigFile.save();
        log.info("{}：{} → {}", reason, stored == null ? "<无>" : stored, version);
    }

    // ==============================================================
    // 版本比较
    // ==============================================================

    /**
     * {@code a} 是否不低于 {@code b}。
     *
     * <p>任一解析失败返回 {@code false}（调用方已把无法解析的版本归一成 {@link #UNKNOWN_VERSION}）。
     */
    private static boolean atLeast(String a, String b) {
        return ModVersion.isAtLeast(a, b);
    }

    /**
     * 按 semver 比较两个版本串（构建元数据不参与比较）；解析失败时退回字符串比较，
     * 保证排序不会因为一个坏条目抛异常。
     */
    private static int compareVersions(String a, String b) {
        Optional<Version> left = ModVersion.parse(a);
        Optional<Version> right = ModVersion.parse(b);
        if (left.isEmpty() || right.isEmpty()) {
            return a.compareTo(b);
        }
        return left.get().compareTo(right.get());
    }
}
