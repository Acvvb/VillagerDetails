package com.villagerdetails.network.channel;

import com.villagerdetails.network.sync.payload.clientbound.VillagerPacket;
import com.villagerdetails.util.PackageScanner;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 自定义包<b>类型</b>的注册（两端共用）。
 *
 * <p>Fabric 的规则：{@code PayloadTypeRegistry} 里的类型必须<b>在物理两端都登记一遍</b>
 * —— 客户端要按同一份 codec 解码服务端发来的包，否则会直接 disconnect。
 * 所以这部分留 {@code main}，由两端都会执行的 {@code main} 入口点调用；
 * 它只依赖包定义（{@code payload} 包）与 {@link PayloadId}，不碰任何服务端逻辑。
 *
 * <p>服务端专属的「收包 / 事件 / 兜底」注册在 {@code ServerChannels}（server 源集），
 * 它会先调用本类把类型登记好，再注册接收器。
 *
 * <p>扫包规则和 {@link com.villagerdetails.util.PackageScanner} 一致：
 * 只认顶层、非抽象、非 interface 的 {@code SyncPayload} 子类，读它自己声明的
 * {@code ID} 与 {@code CODEC}。
 */
public final class PayloadTypes {

    private static final Logger LOGGER = LogManager.getLogger(PayloadTypes.class);

    private static final String SYNC_PACKAGE = "com.villagerdetails.network.sync";
    public static final String PAYLOAD_CLIENTBOUND_PACKAGE = SYNC_PACKAGE + ".payload.clientbound";
    public static final String PAYLOAD_SERVERBOUND_PACKAGE = SYNC_PACKAGE + ".payload.serverbound";

    private static volatile Registered registered;

    private PayloadTypes() {
    }

    /**
     * 已登记的包 id（按方向分开），体检用。
     */
    public record Registered(Set<Identifier> clientbound, Set<Identifier> serverbound) {
    }

    /**
     * 扫两个 payload 目录并登记包类型；重复调用只登记一次（两端入口 + 服务端注册都会调）。
     */
    public static Registered registerAll() {
        Registered local = registered;
        if (local != null) {
            return local;
        }
        synchronized (PayloadTypes.class) {
            if (registered == null) {
                Set<Identifier> clientbound = register(PAYLOAD_CLIENTBOUND_PACKAGE, true);
                Set<Identifier> serverbound = register(PAYLOAD_SERVERBOUND_PACKAGE, false);
                LOGGER.info("包类型注册完成：S→C {} 个、C→S {} 个", clientbound.size(), serverbound.size());
                registered = new Registered(clientbound, serverbound);
            }
            return registered;
        }
    }

    private static Set<Identifier> register(String packageName, boolean clientbound) {
        Set<Identifier> ids = new LinkedHashSet<>();
        for (Class<?> candidate : PackageScanner.scan(packageName, VillagerPacket.class,
                (name, cause) -> LOGGER.error("包类型 {} 类加载失败（扫包时被跳过），这个包不会生效", name, cause))) {
            if (!SyncPayload.class.isAssignableFrom(candidate)
                    || candidate.isInterface()
                    || Modifier.isAbstract(candidate.getModifiers())) {
                continue;
            }
            try {
                registerType(candidate, clientbound);
                ids.add(((PayloadId<?>) staticField(candidate, "ID")).id());
            } catch (Throwable t) {
                LOGGER.error("包类型 {} 注册失败，这个包不会生效", candidate.getName(), t);
            }
        }
        return ids;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerType(Class<?> payloadClass, boolean clientbound) throws ReflectiveOperationException {
        PayloadId id = (PayloadId) staticField(payloadClass, "ID");
        StreamCodec<? super RegistryFriendlyByteBuf, ?> codec =
                (StreamCodec<? super RegistryFriendlyByteBuf, ?>) staticField(payloadClass, "CODEC");
        if (clientbound) {
            PayloadTypeRegistry.clientboundPlay().register(id.type(), (StreamCodec) codec);
        } else {
            PayloadTypeRegistry.serverboundPlay().register(id.type(), (StreamCodec) codec);
        }
    }

    private static Object staticField(Class<?> clazz, String name) throws ReflectiveOperationException {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
