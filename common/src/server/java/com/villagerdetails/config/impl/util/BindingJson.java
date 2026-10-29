package com.villagerdetails.config.impl.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * 绑定类配置里「坐标 / 维度」这两种 JSON 值的读写。
 *
 * <p>传送门绑定、禁刷僵尸猪灵这些区域类配置都要落盘坐标和维度，
 * 之前每个配置类各抄一份 {@code posToJson} / {@code posFromJson} / {@code dimensionFromJson}，
 * 复制粘贴一多就必然走样（改一处漏一处），所以收拢到这里。
 *
 * <p>统一口径：
 * <ul>
 *   <li>坐标写成 {@code [x, y, z]} 三元素数组；</li>
 *   <li>维度写成 {@code "命名空间:路径"} 字符串（或用它当对象键）；</li>
 *   <li>读不懂就返回 {@code null}，由调用方决定「整条丢掉」还是「按单格区域算」。</li>
 * </ul>
 */
public final class BindingJson {

    private BindingJson() {
    }

    /**
     * 坐标 → {@code [x, y, z]}
     */
    public static JsonArray posToJson(BlockPos pos) {
        JsonArray array = new JsonArray(3);
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    /**
     * {@code [x, y, z]} → 坐标；不是三元素数字数组时返回 {@code null}。
     *
     * <p>元素写成非数字之类也会被当成坏数据吞掉（{@code getAsInt} 抛的运行时异常在这里收口），
     * 免得一条脏配置把整个加载流程打断。
     */
    public static @Nullable BlockPos posFromJson(@Nullable JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() < 3) {
            return null;
        }
        try {
            return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 维度对象键（{@code "minecraft:overworld"}）→ 维度 key；解析失败返回 {@code null}。
     *
     * <p>传送门绑定把维度写在条目对象的键上，所以走这个入口。
     */
    public static @Nullable ResourceKey<Level> dimensionFromKey(@Nullable String key) {
        Identifier id = key == null ? null : Identifier.tryParse(key);
        return id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
    }

    /**
     * 维度字段值（{@code {"dimension": "minecraft:overworld"}}）→ 维度 key；
     * 不是字符串或解析失败返回 {@code null}。
     */
    public static @Nullable ResourceKey<Level> dimensionFromJson(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        return dimensionFromKey(element.getAsString());
    }
}
