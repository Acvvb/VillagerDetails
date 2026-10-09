package com.villagerdetails.client.setting.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 快捷键冲突检查：拿配置项绑定的按键组合，和
 * ①本模组其它配置项、②原版与其它模组注册的 {@link KeyMapping} 做比对。
 *
 * <p>判定规则（按需求）：
 * <ul>
 *     <li><b>红（EXACT）</b>：按键组合完全相同</li>
 *     <li><b>黄（PARTIAL）</b>：别人的组合严格包含我的（我 {@code X+C}，别人 {@code X+C+F}）</li>
 *     <li>别人只是我的子集（我 {@code X+C}，别人 {@code X} 或 {@code C}）→ 不标色</li>
 * </ul>
 */
public final class KeyBindConflicts {

    public enum Level {
        NONE,
        PARTIAL,
        EXACT
    }

    public record Result(Level level, List<Component> sources) {

        public boolean hasConflict() {
            return level != Level.NONE;
        }

        /**
         * 悬停提示：冲突标题 + 每一条来源
         */
        public Component tooltip() {
            MutableComponent text = Component.translatable(level == Level.EXACT
                    ? "villagerdetails.ui.keybind.conflict.exact"
                    : "villagerdetails.ui.keybind.conflict.partial");
            for (Component source : sources) {
                text.append("\n").append(Component.literal("• ")).append(source);
            }
            return text;
        }
    }

    private static final Result NONE = new Result(Level.NONE, List.of());

    private KeyBindConflicts() {
    }

    public static Result check(ConfigEntryType entry) {
        int[] mine = entry.getKeyBind();
        if (mine.length == 0) {
            return NONE;
        }

        List<Component> exact = new ArrayList<>();
        List<Component> partial = new ArrayList<>();

        // ① 本模组其它配置项的绑定
        for (ConfigEntryType other : ConfigEntryType.values()) {
            if (other == entry) {
                continue;
            }
            int[] keys = other.getKeyBind();
            if (keys.length == 0) {
                continue;
            }
            Level level = compare(mine, keys);
            if (level == Level.NONE) {
                continue;
            }
            Component description = Component.translatable("villagerdetails.ui.keybind.conflict.entry",
                    modName("villagerdetails"), other.getDisplayName(), ConfigEntryType.displayKeys(keys));
            (level == Level.EXACT ? exact : partial).add(description);
        }

        // ② 原版 / 其它模组注册的按键绑定
        Minecraft minecraft = Minecraft.getInstance();
        for (KeyMapping mapping : minecraft.options.keyMappings) {
            if (mapping.isUnbound()) {
                continue;
            }
            InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(mapping);
            if (bound.getType() != InputConstants.Type.KEYSYM) {
                continue;
            }
            int[] keys = {bound.getValue()};
            Level level = compare(mine, keys);
            if (level == Level.NONE) {
                continue;
            }
            Component description = Component.translatable("villagerdetails.ui.keybind.conflict.entry",
                    modName(mapping.getCategory().id().getNamespace()),
                    mapping.getCategory().label(),
                    bound.getDisplayName());
            (level == Level.EXACT ? exact : partial).add(description);
        }

        if (!exact.isEmpty()) {
            return new Result(Level.EXACT, List.copyOf(exact));
        }
        if (!partial.isEmpty()) {
            return new Result(Level.PARTIAL, List.copyOf(partial));
        }
        return NONE;
    }

    /**
     * 完全相同 → EXACT；别人严格包含我的 → PARTIAL；其余（含别人是我的子集）→ NONE
     */
    private static Level compare(int[] mine, int[] other) {
        Set<Integer> a = toSet(mine);
        Set<Integer> b = toSet(other);
        if (a.isEmpty() || b.isEmpty()) {
            return Level.NONE;
        }
        if (a.equals(b)) {
            return Level.EXACT;
        }
        if (b.containsAll(a) && b.size() > a.size()) {
            return Level.PARTIAL;
        }
        return Level.NONE;
    }

    private static Set<Integer> toSet(int[] keys) {
        Set<Integer> set = new LinkedHashSet<>();
        for (int key : keys) {
            set.add(key);
        }
        return set;
    }

    /**
     * 按键分类 id 的 namespace 就是模组 id，用它换成可读的模组名
     */
    private static String modName(String namespace) {
        return FabricLoader.getInstance().getModContainer(namespace)
                .map(container -> container.getMetadata().getName())
                .orElse(namespace);
    }
}
