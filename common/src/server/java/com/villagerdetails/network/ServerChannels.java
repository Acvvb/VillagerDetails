package com.villagerdetails.network;

import com.villagerdetails.network.channel.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 所有同步通道的<b>唯一注册入口</b>（服务端侧对应物是客户端的 {@code ClientChannels}）。
 *
 * <p>{@code com.villagerdetails.network.sync} 下<b>每个目录对应一个角色接口</b>，
 * 这里就按「目录 → 接口 → 初始化方法」一张表逐行扫包、逐行调方法（见 {@link #ROLES}）：
 *
 * <table border="1">
 *     <caption>目录与角色接口</caption>
 *     <tr><th>目录</th><th>接口</th><th>初始化方法</th></tr>
 *     <tr><td>{@code payload.clientbound}</td><td>{@code ClientboundPayload}</td>
 *         <td>{@code static register(Class)}（由 {@link PayloadTypes} 调用）</td></tr>
 *     <tr><td>{@code payload.serverbound}</td><td>{@code ServerboundPayload}</td>
 *         <td>{@code static register(Class)}（由 {@link PayloadTypes} 调用）</td></tr>
 *     <tr><td>{@code clientbound}</td><td>{@link ClientboundChannel}</td><td>{@code register()}（自检包定义）</td></tr>
 *     <tr><td>{@code serverbound}</td><td>{@link ServerboundChannel}</td><td>{@code register()}（自检包定义）</td></tr>
 *     <tr><td>{@code receiver}</td><td>{@link ServerReceiver}</td><td>{@code registerServerReceiver()}</td></tr>
 *     <tr><td>{@code event}</td><td>{@link SyncEvents}</td><td>{@code registerEvents()}</td></tr>
 *     <tr><td>{@code other}</td><td>{@link SyncRegistrar}</td><td>{@code register()}</td></tr>
 * </table>
 *
 * <p>扫包 / 实例化 / 失败汇总复用 main 的 {@link ChannelScanner}，和
 * {@code ClientChannels}、{@code ServerEventManager}、{@code ServerSessionStates} 同一套底座。
 *
 * <p>幂等：{@code VillagerDetailsServer} 的 {@code main} 与 {@code server} 两个入口点都会调
 * {@link #registerAll()}，所以这里只真正跑一次 —— 否则收包器 / 事件会被注册两遍、回调触发两次。
 */
public final class ServerChannels {

    private static final Logger LOGGER = LogManager.getLogger(ServerChannels.class);

    /**
     * 同步相关的根包
     */
    private static final String SYNC_PACKAGE = "com.villagerdetails.network.sync";

    private static final String CLIENTBOUND_PACKAGE = SYNC_PACKAGE + ".clientbound";
    private static final String SERVERBOUND_PACKAGE = SYNC_PACKAGE + ".serverbound";
    private static final String RECEIVER_PACKAGE = SYNC_PACKAGE + ".receiver";
    private static final String EVENT_PACKAGE = SYNC_PACKAGE + ".event";
    private static final String OTHER_PACKAGE = SYNC_PACKAGE + ".other";

    /**
     * 一条「目录 → 角色接口 → 初始化动作」的登记项。
     *
     * @param packageName 扫哪个目录
     * @param stage       日志 / 失败项里的阶段名
     * @param required    这个目录允许不允许是空的（{@code .other} / {@code .serverbound} 现在就是空的）
     * @param role        只认实现了这个接口的类
     * @param initializer 对每个实例调它的初始化方法
     */
    private record Role(String packageName, String stage, boolean required,
                        Class<?> role, Consumer<Object> initializer) {
    }

    /**
     * 目录清单：顺序就是初始化顺序（包类型 → 通道自检 → 收包 → 事件 → 兜底）。
     */
    private static final List<Role> ROLES = List.of(
            new Role(CLIENTBOUND_PACKAGE, "S→C 通道", true, ClientboundChannel.class,
                    item -> ((ClientboundChannel<?>) item).register()),
            new Role(SERVERBOUND_PACKAGE, "C→S 封装", false, ServerboundChannel.class,
                    item -> ((ServerboundChannel<?>) item).register()),
            new Role(RECEIVER_PACKAGE, "服务端收包", true, ServerReceiver.class,
                    item -> ((ServerReceiver<?>) item).registerServerReceiver()),
            new Role(EVENT_PACKAGE, "服务端事件", true, SyncEvents.class,
                    item -> ((SyncEvents) item).registerEvents()),
            new Role(OTHER_PACKAGE, "兜底同步", false, SyncRegistrar.class,
                    item -> ((SyncRegistrar) item).register()));

    private static boolean registered;

    private ServerChannels() {
    }

    /**
     * 扫包注册全部同步内容；服务端初始化时调一次（重复调用是空操作）。
     */
    public static void registerAll() {
        if (registered) {
            return;
        }
        registered = true;

        List<ChannelScanner.Failure> failures = new ArrayList<>();

        // 包类型（两端共用，内部自带幂等）：客户端要靠同一份 codec 解码，缺一条都收不到包；
        // 两个 payload 目录的角色接口就是在这里被按方向分派调用的
        PayloadTypes.registerAll();

        // 其余目录：逐行扫包 → 只认该行指定的接口 → 调它的初始化方法
        List<String> summary = new ArrayList<>();
        for (Role role : ROLES) {
            summary.add(role.stage() + " " + scan(role, failures));
        }

        LOGGER.info("同步扫包注册完成：{}", String.join("、", summary));

        ChannelScanner.reportFailures(LOGGER, "同步注册", failures);
    }

    /**
     * 扫一行（一个目录），返回「阶段名 + 注册成功的类名清单」，例如
     * {@code 服务端收包 10 个 [BBClearReceiver, ...]}。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String scan(Role role, List<ChannelScanner.Failure> failures) {
        List<String> names = new ArrayList<>();
        int count = ChannelScanner.forEach(role.packageName(), ServerChannels.class, (Class) role.role(),
                item -> {
                    names.add(item.getClass().getSimpleName());
                    role.initializer().accept(item);
                }, failures);
        if (role.required()) {
            ChannelScanner.checkNotEmpty(count, role.packageName(), role.stage(), failures);
        }
        return count + " 个 " + names;
    }
}
