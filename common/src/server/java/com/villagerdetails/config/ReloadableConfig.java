package com.villagerdetails.config;

import net.minecraft.server.MinecraftServer;

/**
 * 可重载配置的统一契约：{@code ConfigRegistry} 只认这一个接口。
 *
 * <p>两个入口的区别和 {@code UnifiedConfigFile} 对外的两个入口完全一致：
 * <ul>
 *     <li>{@link #reload(MinecraftServer)}：<b>开服回灌</b>用的轻量入口 ——
 *         已经加载过同一台服务器时只把内存里的内容重新应用一遍，不重复读盘；</li>
 *     <li>{@link #reloadFromDisk(MinecraftServer)}：<b>{@code /ec c reload}</b> 用的重入口 ——
 *         先把还没落盘的改动 flush 出去并等它真正落地，再从磁盘整份重读。
 *         默认实现等同于 {@link #reload(MinecraftServer)}，有独立文件的配置自己覆盖。</li>
 * </ul>
 */
public interface ReloadableConfig {

    String configName();

    void reload(MinecraftServer server);

    /**
     * 从磁盘整份重读。
     *
     * <p>默认 = {@link #reload(MinecraftServer)}（数据不在独立文件里的 section 配置就是这样）；
     * 有独立 JSON 文件的配置要覆盖成「{@code flushAndAwait} → 读盘 → 应用」。
     */
    default void reloadFromDisk(MinecraftServer server) {
        reload(server);
    }
}
