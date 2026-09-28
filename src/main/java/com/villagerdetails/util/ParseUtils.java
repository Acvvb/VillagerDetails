package com.villagerdetails.util;

/**
 * 字符串类型转换工具。
 */
public final class ParseUtils {

    private ParseUtils() {}

    /** 转换失败返回 0 */
    public static int toInt(String s) {
        return toInt(s, 0);
    }

    /** 转换失败返回默认值 */
    public static int toInt(String s, int defaultValue) {
        if (s == null) return defaultValue;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 转换失败返回 0 */
    public static float toFloat(String s) {
        return toFloat(s, 0f);
    }

    /** 转换失败返回默认值 */
    public static float toFloat(String s, float defaultValue) {
        if (s == null) return defaultValue;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return defaultValue;
        try {
            return Float.parseFloat(trimmed);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static double toDouble(String s) {
        return toDouble(s, 0d);
    }

    public static double toDouble(String s, double defaultValue) {
        if (s == null) return defaultValue;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return defaultValue;
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static long toLong(String s) {
        return toLong(s, 0L);
    }

    public static long toLong(String s, long defaultValue) {
        if (s == null) return defaultValue;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 只有 "true"（忽略大小写）返回 true */
    public static boolean toBoolean(String s) {
        return toBoolean(s, false);
    }

    public static boolean toBoolean(String s, boolean defaultValue) {
        if (s == null) return defaultValue;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return defaultValue;
        if ("true".equalsIgnoreCase(trimmed)) return true;
        if ("false".equalsIgnoreCase(trimmed)) return false;
        return defaultValue;
    }

    public static int toIntOrLog(String s, int defaultValue, String context) {
        if (s == null) return defaultValue;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            System.err.println("[ParseUtils] " + context + " 无法转为 int：" + s);
            return defaultValue;
        }
    }

    public static float toFloatOrLog(String s, float defaultValue, String context) {
        if (s == null) return defaultValue;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return defaultValue;
        try {
            return Float.parseFloat(trimmed);
        } catch (NumberFormatException e) {
            System.err.println("[ParseUtils] " + context + " 无法转为 float：" + s);
            return defaultValue;
        }
    }
}