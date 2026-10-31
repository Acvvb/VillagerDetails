/**
 * 具体的版本迁移实现类。
 *
 * <p>此目录用于存放具体的版本迁移类。例如 1.2.0 的迁移类命名为
 * {@code V1_2_0_Migration} 并放在此目录下。
 *
 * <p>约定：
 * <ul>
 *     <li>类名 = {@code V<主>_<次>_<修订>_Migration}，与它 {@code targetVersion()} 返回的版本号对应
 *         （{@code 1.2.0} → {@code V1_2_0_Migration}）；</li>
 *     <li>每个类只做「一个版本」的迁移，只碰配置，要求幂等；</li>
 *     <li>写完以后要到 {@code MigrationManager.MIGRATIONS} 里手动登记一行
 *         （框架<b>不</b>扫包自动发现）；</li>
 *     <li>记得把 {@code MigrationManager.CURRENT_CONFIG_VERSION} 抬到这次发布的版本。</li>
 * </ul>
 *
 * <p>该子包目前是空的：1.1.0 之前的存档直接走「版本过低 → 整份重置」，
 * 1.1.0 起还没有破坏性配置更改。
 */
package com.villagerdetails.config.migration.version;