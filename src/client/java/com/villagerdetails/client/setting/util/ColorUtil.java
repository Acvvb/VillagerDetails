package com.villagerdetails.client.setting.util;

/**
 * 颜色换算工具：配置里只存 24 位 RGB，渲染时需要补满 Alpha。
 */
public final class ColorUtil {

    private ColorUtil() {
    }

    /**
     * 纯 RGB 掩码（配置里只存 RGB，Alpha 由渲染时补满）
     */
    public static final int RGB_MASK = 0xFFFFFF;

    /**
     * 不透明 Alpha
     */
    public static final int OPAQUE = 0xFF000000;

    /**
     * 把配置里的 RGB 补齐 Alpha，给渲染用
     */
    public static int toArgb(int rgb) {
        return OPAQUE | (rgb & RGB_MASK);
    }

    /**
     * 从 {@code "#RRGGBB"} 解析出 RGB；解析失败返回白色
     */
    public static int parseRgb(String raw) {
        if (raw != null && raw.startsWith("#")) {
            try {
                return Integer.parseInt(raw.substring(1), 16) & RGB_MASK;
            } catch (NumberFormatException ignored) {
                // 写坏了退回白色
            }
        }
        return RGB_MASK;
    }

    /**
     * 把 RGB 格式化成 {@code "#RRGGBB"}
     */
    public static String formatRgb(int rgb) {
        return String.format("#%06X", rgb & RGB_MASK);
    }
}