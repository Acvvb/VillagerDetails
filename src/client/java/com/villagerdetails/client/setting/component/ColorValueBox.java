package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.config.ClientRenderConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * 可输入的色值框组件：支持 {@code #RRGGBB} 或 {@code RRGGBB}，输入合法值就回调。
 */
public final class ColorValueBox {

    private static final int MAX_LENGTH = 7;

    private ColorValueBox() {
    }

    public static EditBox create(int x, int y, int width, int height, IntSupplier color, Consumer<Integer> onColor) {
        EditBox box = new EditBox(Minecraft.getInstance().font, x, y, width, height,
                Component.translatable("villagerdetails.ui.color_value"));
        box.setMaxLength(MAX_LENGTH);
        box.setValue(hex(color.getAsInt()));
        box.setResponder(text -> {
            int rgb = parseHex(text);
            if (rgb >= 0) {
                onColor.accept(rgb);
            }
        });
        return box;
    }

    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & ClientRenderConfig.RGB_MASK);
    }

    /** 解析 {@code #RRGGBB} / {@code RRGGBB}，不合法返回 -1 */
    public static int parseHex(String text) {
        if (text == null) {
            return -1;
        }
        String value = text.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() != 6) {
            return -1;
        }
        try {
            return Integer.parseInt(value, 16) & ClientRenderConfig.RGB_MASK;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
