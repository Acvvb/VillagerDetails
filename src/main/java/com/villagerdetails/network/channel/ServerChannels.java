package com.villagerdetails.network.channel;

import com.villagerdetails.util.PackageScanner;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 所有同步注册的<b>唯一入口</b>（服务端侧）。
 *
 * <p>这里不一条条手写登记，而是<b>扫包</b>：往 {@code com.villagerdetails.network.sync} 下的
 * 几个目录里放个类，注册这件事自己就完成了 —— 加新包<b>不用改这个文件</b>。
 *
 * <table>
 *     <tr><th>目录</th><th>放什么</th><th>自动干什么</th></tr>
 *     <tr><td>{@code sync.payload.clientbound}</td><td>{@link SyncPayload} 子类（包定义）</td>
 *         <td>用它自己的 {@code ID} + {@code CODEC} 注册「服务端 → 客户端」包类型</td></tr>
 *     <tr><td>{@code sync.payload.serverbound}</td><td>{@link SyncPayload} 子类（包定义）</td>
 *         <td>同上，注册「客户端 → 服务端」包类型</td></tr>
 *     <tr><td>{@code sync.receiver}</td><td>{@link ServerReceiver} 实现</td>
 *         <td>注册服务端收包（自动切回主线程）</td></tr>
 *     <tr><td>{@code sync.event}</td><td>{@link SyncEvents} 实现</td>
 *         <td>调 {@link SyncEvents#registerEvents()}</td></tr>
 *     <tr><td>{@code sync.other}</td><td>{@link SyncRegistrar} 实现</td>
 *         <td>调 {@link SyncRegistrar#register()}（兜底，前几类对不上号才用）</td></tr>
 * </table>
 *
 * <p>{@code sync.clientbound} / {@code sync.serverbound} 放的是<b>发包 API</b>
 * （{@link SyncChannel} 子类），不参与注册；其中 {@code serverbound} 目前是空的。
 *
 * <p>包定义和发包通道分在两个目录，配错了会静默失效，所以注册完会顺手比一次：
 * 通道对应的包定义没放进 payload 目录，会在日志里报 ERROR。
 *
 * <p>被扫到的类要能被实例化：给一个自己的 {@code public static final Xxx INSTANCE}
 * （本项目统一写法），或者一个无参构造。包定义类不用实例化，读它的静态常量就行。
 */
public final class ServerChannels {

    private static final Logger LOGGER = LogManager.getLogger(ServerChannels.class);

    private static final String SYNC_PACKAGE = "com.villagerdetails.network.sync";

    /**
     * 包定义（S→C）：放 {@link SyncPayload} 子类，自动注册成「服务端 → 客户端」包类型
     */
    public static final String PAYLOAD_CLIENTBOUND_PACKAGE = SYNC_PACKAGE + ".payload.clientbound";

    /**
     * 包定义（C→S）：放 {@link SyncPayload} 子类，自动注册成「客户端 → 服务端」包类型
     */
    public static final String PAYLOAD_SERVERBOUND_PACKAGE = SYNC_PACKAGE + ".payload.serverbound";

    /**
     * 发包 API（S→C）：放 {@link SyncChannel} 子类，只负责发，不参与注册
     */
    public static final String CLIENTBOUND_PACKAGE = SYNC_PACKAGE + ".clientbound";

    /**
     * 发包 API（C→S）：同上，目前为空
     */
    public static final String SERVERBOUND_PACKAGE = SYNC_PACKAGE + ".serverbound";

    /**
     * 服务端收包：放 {@link ServerReceiver} 实现
     */
    public static final String RECEIVER_PACKAGE = SYNC_PACKAGE + ".receiver";

    /**
     * 事件：放 {@link SyncEvents} 实现
     */
    public static final String EVENT_PACKAGE = SYNC_PACKAGE + ".event";

    /**
     * 兜底：放 {@link SyncRegistrar} 实现
     */
    public static final String OTHER_PACKAGE = SYNC_PACKAGE + ".other";

    private ServerChannels() {
    }

    /**
     * 扫包注册全部包类型、服务端接收器、事件和兜底项；模组初始化时调一次。
     *
     * <p>顺序不能动：包类型必须先注册完，Fabric 才认收包注册。
     */
    public static void registerAll() {
        // 1) 包类型：注册内容全部来自包类自己的 ID / CODEC，这里不碰任何具体实现
        Set<Identifier> clientboundIds = registerPayloads(PAYLOAD_CLIENTBOUND_PACKAGE, true);
        Set<Identifier> serverboundIds = registerPayloads(PAYLOAD_SERVERBOUND_PACKAGE, false);

        // 2) 服务端收包、3) 事件、4) 兜底
        int receivers = forEach(RECEIVER_PACKAGE, ServerReceiver.class, ServerReceiver::registerServerReceiver);
        int events = forEach(EVENT_PACKAGE, SyncEvents.class, SyncEvents::registerEvents);
        int others = forEach(OTHER_PACKAGE, SyncRegistrar.class, SyncRegistrar::register);

        // 体检：通道的包定义有没有放对目录（放错 = 这个包静默失效，不主动查是查不出来的）
        checkChannels(CLIENTBOUND_PACKAGE, clientboundIds);
        checkChannels(SERVERBOUND_PACKAGE, serverboundIds);

        LOGGER.info("同步扫包注册完成：S→C 包 {} 个、C→S 包 {} 个、服务端收包 {} 个、事件 {} 个、兜底 {} 个",
                clientboundIds.size(), serverboundIds.size(), receivers, events, others);
        if (clientboundIds.isEmpty() && serverboundIds.isEmpty()) {
            LOGGER.error("一个同步包都没扫到！检查 {} 下的子包有没有被打进包里", SYNC_PACKAGE);
        }
    }

    // ==============================================================
    // 包类型（注册内容由包类自己声明）
    // ==============================================================

    /**
     * 扫一个 payload 目录，把里面的包逐个登记到对应方向。
     *
     * @return 登记成功的包 id
     */
    private static Set<Identifier> registerPayloads(String packageName, boolean clientbound) {
        Set<Identifier> registered = new LinkedHashSet<>();
        for (Class<?> candidate : PackageScanner.scan(packageName, ServerChannels.class)) {
            if (!SyncPayload.class.isAssignableFrom(candidate)
                    || candidate.isInterface()
                    || Modifier.isAbstract(candidate.getModifiers())) {
                continue;
            }
            try {
                registerPayloadType(candidate, clientbound);
                registered.add(((PayloadId<?>) staticField(candidate, "ID")).id());
            } catch (Throwable t) {
                LOGGER.error("注册包 {} 失败，已跳过", candidate.getName(), t);
            }
        }
        return registered;
    }

    /**
     * 包类自己声明的 {@code ID} / {@code CODEC} → 登记到对应方向的注册表。
     *
     * <p>扫包时泛型已经擦掉了，这里只能按原始类型交过去；类型对不对由包自己的声明保证。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerPayloadType(Class<?> payloadClass, boolean clientbound) throws ReflectiveOperationException {
        PayloadId id = (PayloadId) staticField(payloadClass, "ID");
        StreamCodec<? super RegistryFriendlyByteBuf, ?> codec =
                (StreamCodec<? super RegistryFriendlyByteBuf, ?>) staticField(payloadClass, "CODEC");
        if (clientbound) {
            PayloadTypeRegistry.clientboundPlay().register(id.type(), (StreamCodec) codec);
        } else {
            PayloadTypeRegistry.serverboundPlay().register(id.type(), (StreamCodec) codec);
        }
    }

    /**
     * 通道里的包定义是不是也放进了 payload 目录。
     *
     * <p>通道在 {@code sync.clientbound}、包定义在 {@code sync.payload.clientbound}，
     * 少放一边这个包就静默失效，所以这里主动比一次。
     */
    private static void checkChannels(String channelPackage, Set<Identifier> registeredIds) {
        forEach(channelPackage, SyncChannel.class, channel -> {
            Identifier id = channel.id().id();
            if (!registeredIds.contains(id)) {
                LOGGER.error("{} 对应的包定义没放进 payload 目录，这个包不会生效（缺 {}）",
                        channel.getClass().getName(), id);
            }
        });
    }

    // ==============================================================
    // 收包 / 事件 / 兜底
    // ==============================================================

    /**
     * 扫一个包，把实现 {@code role} 的类实例化后交给 {@code action}。
     *
     * @return 处理了几个；单个类失败只记日志跳过，不影响其它类
     */
    private static <T> int forEach(String packageName, Class<T> role, Consumer<T> action) {
        int count = 0;
        for (Class<?> candidate : PackageScanner.scan(packageName, ServerChannels.class)) {
            if (!role.isAssignableFrom(candidate)
                    || candidate.isInterface()
                    || Modifier.isAbstract(candidate.getModifiers())) {
                continue;
            }
            try {
                action.accept(role.cast(instanceOf(candidate)));
                count++;
            } catch (Throwable t) {
                LOGGER.error("注册 {} 失败，已跳过", candidate.getName(), t);
            }
        }
        return count;
    }

    /**
     * 拿一个类的实例：优先它自己声明的 {@code INSTANCE} 常量（本项目统一写法），
     * 没有就退回无参构造。
     */
    private static Object instanceOf(Class<?> clazz) throws ReflectiveOperationException {
        try {
            Field field = clazz.getDeclaredField("INSTANCE");
            if (Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) {
                    return value;
                }
            }
        } catch (NoSuchFieldException ignored) {
            // 没有 INSTANCE 就退回无参构造
        }
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /**
     * 读一个类的静态常量（包定义就是这么把自己的 ID / CODEC 交出来的）
     */
    private static Object staticField(Class<?> clazz, String name) throws ReflectiveOperationException {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
