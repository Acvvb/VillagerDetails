package com.villagerdetails.config.migration;

import net.minecraft.server.MinecraftServer;

/**
 * 一次「配置版本步进」迁移。
 *
 * <p>用途：当某次更新对 {@code villagerdetails/config.json} 做了<b>破坏性</b>更改
 * （改字段名、改嵌套结构、改语义……）时，不需要在业务代码里到处写「旧值兼容」，
 * 只要在 {@code com.villagerdetails.config.migration.version} 子包下新建一个实现类
 * （类名形如 {@code V1_2_0_Migration}，与它的 {@code targetVersion()} 对应），
 * 把「旧格式 → 新格式」的转换写进 {@link #migrate(MinecraftServer)}，
 * 再在 {@link MigrationManager} 的登记表里加一行即可。
 *
 * <p>执行时机与顺序由 {@link MigrationManager} 统一负责：
 * <ul>
 *     <li>每次服务器启动（以及 {@code /ec c reload}）时，先于所有子配置加载执行；</li>
 *     <li>只执行 {@code targetVersion()} 高于「存档里记录的版本」的那些，按目标版本升序；</li>
 *     <li>每一步执行完就把版本推进到该步的 {@code targetVersion()} 并落盘 ——
 *         中途崩溃的存档下次从断点继续，不会重复跑已经完成的步骤。</li>
 * </ul>
 *
 * <p><b>实现要求</b>：
 * <ul>
 *     <li><b>幂等</b>。每步只跑一次是执行器的保证，但手改版本号 / 重复触发时要能承受
 *         （例如「字段已存在就跳过」而不是「先删再加」）；</li>
 *     <li>只碰配置（{@link com.villagerdetails.config.impl.UnifiedConfigFile} 的 section），
 *         不要在这里做业务初始化；</li>
 *     <li>抛异常时本次迁移会被中止、版本不推进（日志有 ERROR），下次启动重试 ——
 *         所以宁可抛，也不要把半成品写进配置。</li>
 * </ul>
 *
 * <p>当前（1.1.0）没有登记任何迁移：1.1.0 之前的存档直接走
 * {@link MigrationManager} 的「版本过低 → 整份重置」路径，不逐条迁移。
 */
public interface ConfigMigration {

    /**
     * 这条迁移逻辑对应的<b>目标版本</b>，例如 {@code "1.2.0"}。
     *
     * <p>存档版本低于它才会执行；执行成功后存档的 {@code __configVersion__} 会被写成它
     * （最后再统一写成 {@link MigrationManager#CURRENT_CONFIG_VERSION}）。
     * 必须是合法 semver（构建元数据如 {@code +26.2} 不参与比较）。
     */
    String targetVersion();

    /**
     * 执行迁移：例如读取旧字段写入新字段、重命名字段、重置某些子结构。
     *
     * @param server 当前服务器（需要落盘 / 读其它配置文件时用）
     */
    void migrate(MinecraftServer server);
}
