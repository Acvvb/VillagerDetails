package com.villagerdetails.network.codec;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 同步包公用的编解码片段。
 *
 * <p>原来是每个包都写一遍 {@code StreamCodec.of(FriendlyByteBuf::writeUtf, FriendlyByteBuf::readUtf)}、
 * 手写循环读列表、手写 Optional 开关……这里收成常量和泛型工厂，包定义里只拼结构。
 *
 * <p>缓冲类型固定用 {@link FriendlyByteBuf}：它既是 {@code RegistryFriendlyByteBuf} 的父类
 * （所以这些 codec 能直接塞进 {@code StreamCodec.composite} 里当元素），
 * 元素参数又收 {@code ? super FriendlyByteBuf}（所以 {@code BlockPos.STREAM_CODEC} 这种
 * 以 {@code ByteBuf} 为缓冲的原版 codec 也能直接用）。
 */
public final class ModCodecs {

    private ModCodecs() {
    }

    public static final StreamCodec<FriendlyByteBuf, String> STRING =
            StreamCodec.of(FriendlyByteBuf::writeUtf, FriendlyByteBuf::readUtf);

    public static final StreamCodec<FriendlyByteBuf, Boolean> BOOL =
            StreamCodec.of(FriendlyByteBuf::writeBoolean, FriendlyByteBuf::readBoolean);

    public static final StreamCodec<FriendlyByteBuf, Integer> VAR_INT =
            StreamCodec.of(FriendlyByteBuf::writeVarInt, FriendlyByteBuf::readVarInt);

    /** {@code List<T>}：先写个数，再逐个写 */
    public static <T> StreamCodec<FriendlyByteBuf, List<T>> listOf(
            StreamCodec<? super FriendlyByteBuf, T> element) {
        return StreamCodec.of(
                (buf, list) -> {
                    buf.writeVarInt(list.size());
                    for (T item : list) {
                        element.encode(buf, item);
                    }
                },
                buf -> {
                    int size = buf.readVarInt();
                    List<T> list = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        list.add(element.decode(buf));
                    }
                    return List.copyOf(list);
                });
    }

    /** {@code Optional<T>}：先写一个布尔，再决定要不要写值 */
    public static <T> StreamCodec<FriendlyByteBuf, Optional<T>> optionalOf(
            StreamCodec<? super FriendlyByteBuf, T> element) {
        return StreamCodec.of(
                (buf, value) -> {
                    if (value.isPresent()) {
                        buf.writeBoolean(true);
                        element.encode(buf, value.get());
                    } else {
                        buf.writeBoolean(false);
                    }
                },
                buf -> buf.readBoolean() ? Optional.of(element.decode(buf)) : Optional.empty());
    }
}
