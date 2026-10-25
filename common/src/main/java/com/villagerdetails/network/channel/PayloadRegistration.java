package com.villagerdetails.network.channel;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.lang.reflect.Field;

/**
 * 包类型的<b>登记动作</b>：读实现类上声明的 {@code ID} / {@code CODEC}，登记进对应方向的包类型表。
 *
 * <p>只在 {@link ClientboundPayload} / {@link ServerboundPayload} 的 {@code register} 里用
 * （包私有），所以方向那两个接口只负责「说清自己是哪个方向」，反射细节收在这里。
 */
final class PayloadRegistration {

    private PayloadRegistration() {
    }

    /**
     * 把实现类登记进指定方向的 {@code PayloadTypeRegistry}。
     *
     * @param clientbound {@code true} = S→C，{@code false} = C→S
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void register(Class<?> payloadClass, boolean clientbound) {
        PayloadId id = idOf(payloadClass);
        StreamCodec codec = codecOf(payloadClass);
        if (clientbound) {
            PayloadTypeRegistry.clientboundPlay().register(id.type(), codec);
        } else {
            PayloadTypeRegistry.serverboundPlay().register(id.type(), codec);
        }
    }

    /**
     * 读实现类上的 {@code public static final PayloadId<Xxx> ID}
     */
    static PayloadId<?> idOf(Class<?> payloadClass) {
        try {
            return (PayloadId<?>) staticField(payloadClass, "ID");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(payloadClass.getName()
                    + " 缺少 public static final ID 字段（格式：PayloadId.of(\"xxx\")）", e);
        }
    }

    /**
     * 读实现类上的 {@code public static final StreamCodec<...> CODEC}
     */
    @SuppressWarnings("unchecked")
    static StreamCodec<? super RegistryFriendlyByteBuf, ?> codecOf(Class<?> payloadClass) {
        try {
            return (StreamCodec<? super RegistryFriendlyByteBuf, ?>) staticField(payloadClass, "CODEC");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(payloadClass.getName() + " 缺少 public static final CODEC 字段", e);
        }
    }

    private static Object staticField(Class<?> clazz, String name) throws ReflectiveOperationException {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
