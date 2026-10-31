/**
 * 配置版本步进迁移。
 *
 * <p>本包（{@code config.migration}）只放<b>框架代码</b>：
 * <ul>
 *     <li>{@link com.villagerdetails.config.migration.ConfigMigration} —— 迁移接口；</li>
 *     <li>{@link com.villagerdetails.config.migration.MigrationManager} —— 执行器
 *         （读版本、比对、整份重置、按序执行、推进版本）。</li>
 * </ul>
 *
 * <p><b>具体的版本迁移实现类一律放在子包 {@link com.villagerdetails.config.migration.version}
 * 下</b>（类名形如 {@code V1_2_0_Migration}），框架代码留在本包，保持目录结构清晰。
 *
 * <p><b>当前（1.1.0）子包里没有任何迁移实现</b>：1.1.0 之前的存档直接走「版本过低 → 整份重置」，
 * 1.1.0 起还没有破坏性配置更改。
 *
 * <p>以后要加迁移，按这个顺序做：
 * <ol>
 *     <li>在 {@code config.migration.version} 子包下新建一个
 *         {@code V1_2_0_Migration implements ConfigMigration}（类名与 {@code targetVersion()} 对应，
 *         {@code targetVersion()} 填这次发布的版本）；</li>
 *     <li>在 {@code MigrationManager.MIGRATIONS} 里<b>手动</b>登记一行
 *         （刻意不扫包：迁移类极少，扫包只会多一层不确定性）；</li>
 *     <li>把 {@code MigrationManager.CURRENT_CONFIG_VERSION} 抬到同一个版本。</li>
 * </ol>
 */
package com.villagerdetails.config.migration;
