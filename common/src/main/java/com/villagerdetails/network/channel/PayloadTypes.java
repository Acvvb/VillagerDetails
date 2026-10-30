package com.villagerdetails.network.channel;

import com.villagerdetails.util.ScanService;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 自定义包<b>类型</b>的登记（两端共用）。
 *
 * <p>Fabric 的规则：{@code PayloadTypeRegistry} 里的类型必须<b>在物理两端都登记一遍</b>
 * —— 客户端要按同一份 codec 解码服务端发来的包，否则会直接 disconnect。
 * 所以这部分留 {@code main}，由两端都会执行的 {@code main} 入口点调用；
 * 它只依赖包定义（{@code payload} 包）与 {@link PayloadId}，不碰任何服务端逻辑。
 *
 * <p>服务端专属的「收包 / 事件 / 兜底」注册在 {@code ServerChannels}（server 源集），
 * 它会先调用本类把类型登记好，再注册接收器。
 *
 * <p><b>方向由接口决定</b>：扫 {@value #PAYLOAD_PACKAGE}（含子包），对每个具体的
 * {@link SyncPayload} 子类看它实现的是 {@link ClientboundPayload} 还是
 * {@link ServerboundPayload}，然后<b>调那个接口的 {@code register(Class)}</b> 完成登记。
 * 放错目录最多是看着乱；忘了实现任何一个接口（或两个都实现）会直接报成失败项，
 * 不会再静默登记到错误的方向上。
 */
public final class PayloadTypes {

    private static final Logger log = LogManager.getLogger(PayloadTypes.class);

    /**
     * 同步相关代码的根包
     */
    private static final String SYNC_PACKAGE = "com.villagerdetails.network.sync";

    /**
     * 包定义所在的包（含子包）；方向看接口，不看子目录
     */
    private static final String PAYLOAD_PACKAGE = SYNC_PACKAGE + ".payload";

    private static volatile Registered registered;

    private PayloadTypes() {
    }

    /**
     * 已登记的包 id（按方向分开），体检用。
     */
    public record Registered(Set<Identifier> clientbound, Set<Identifier> serverbound) {
    }

    /**
     * 扫包登记两端所有包类型；重复调用只登记一次（两端入口 + 服务端注册都会调）。
     */
    public static Registered registerAll() {
        Registered local = registered;
        if (local != null) {
            return local;
        }
        synchronized (PayloadTypes.class) {
            if (registered == null) {
                List<ChannelScanner.Failure> failures = new ArrayList<>();
                Set<Identifier> clientbound = new LinkedHashSet<>();
                Set<Identifier> serverbound = new LinkedHashSet<>();
                List<String> clientboundNames = new ArrayList<>();
                List<String> serverboundNames = new ArrayList<>();

                scan(clientbound, clientboundNames, serverbound, serverboundNames, failures);

                ChannelScanner.checkNotEmpty(clientboundNames.size(), PAYLOAD_PACKAGE, "包类型（S→C）", failures);
                ChannelScanner.checkNotEmpty(serverboundNames.size(), PAYLOAD_PACKAGE, "包类型（C→S）", failures);

                log.info("包类型登记完成（S→C）：{} 个 {}", clientbound.size(), clientboundNames);
                log.info("包类型登记完成（C→S）：{} 个 {}", serverbound.size(), serverboundNames);

                ChannelScanner.reportFailures(log, "包类型注册", failures);
                registered = new Registered(clientbound, serverbound);
            }
            return registered;
        }
    }

    /**
     * 取包目录（含子包）里的类：按实现的接口分派到 {@link ClientboundPayload#register(Class)} /
     * {@link ServerboundPayload#register(Class)}，顺带收好两边的 id 与类名。
     *
     * <p>类名由 {@link ScanService} 给出，<b>已按类名排序</b> —— 改造前是文件系统 / jar 条目顺序，
     * 同一份代码在不同机器上可能不同。登记进 {@code PayloadTypeRegistry} 是按 id 建表，顺序不影响结果，
     * 但排序后每次启动的日志与顺序完全一致。
     */
    private static void scan(Set<Identifier> clientbound, List<String> clientboundNames,
                             Set<Identifier> serverbound, List<String> serverboundNames,
                             List<ChannelScanner.Failure> failures) {
        for (Class<?> candidate : ScanService.loadClasses(PAYLOAD_PACKAGE, PayloadTypes.class,
                (name, cause) -> failures.add(new ChannelScanner.Failure("包类型", name,
                        "类加载失败（扫包时被跳过），这个包不会生效", cause)))) {
            if (!SyncPayload.class.isAssignableFrom(candidate)
                    || candidate.isInterface()
                    || Modifier.isAbstract(candidate.getModifiers())) {
                continue;
            }
            boolean toClient = ClientboundPayload.class.isAssignableFrom(candidate);
            boolean toServer = ServerboundPayload.class.isAssignableFrom(candidate);
            if (toClient == toServer) {
                // 两个都没实现 / 两个都实现：方向不明确，宁可不登记也要报出来
                failures.add(new ChannelScanner.Failure("包类型", candidate.getName(),
                        toClient
                                ? "同时实现了 ClientboundPayload 和 ServerboundPayload，方向不明确"
                                : "既没实现 ClientboundPayload 也没实现 ServerboundPayload，不会被登记",
                        null));
                continue;
            }
            try {
                if (toClient) {
                    ClientboundPayload.register(candidate);
                    clientbound.add(PayloadRegistration.idOf(candidate).id());
                    clientboundNames.add(candidate.getSimpleName());
                } else {
                    ServerboundPayload.register(candidate);
                    serverbound.add(PayloadRegistration.idOf(candidate).id());
                    serverboundNames.add(candidate.getSimpleName());
                }
            } catch (Throwable t) {
                failures.add(new ChannelScanner.Failure("包类型", candidate.getName(),
                        "登记失败，这个包不会生效", t));
            }
        }
    }
}
