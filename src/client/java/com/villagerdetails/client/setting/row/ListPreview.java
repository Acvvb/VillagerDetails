package com.villagerdetails.client.setting.row;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 行上那个「列表」按钮的预览文字：{@code [a,b,…]}。
 *
 * <p>最多显示 {@link #MAX_ITEMS} 项，还有更多就补个 {@code …}；一项都没有时显示 {@code []}。
 * 本机配置项的列表（{@link com.villagerdetails.client.setting.type.ConfigEntry#listDisplay}）和
 * 「服务器配置项」那张 id → 值的表（{@link ServerRuleDataRow#listDisplay}）共用这一套排版。
 */
public final class ListPreview {

    /**
     * 预览最多显示几项
     */
    public static final int MAX_ITEMS = 2;

    private ListPreview() {
    }

    /**
     * @param items 已经渲染好的每一项（纯名单就是 id，键值表是 {@code id=值}）
     */
    public static Component of(List<String> items) {
        if (items.isEmpty()) {
            return Component.literal("[]");
        }
        StringBuilder text = new StringBuilder("[");
        int shown = Math.min(MAX_ITEMS, items.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(items.get(i));
        }
        if (items.size() > shown) {
            text.append(",…");
        }
        text.append(']');
        return Component.literal(text.toString());
    }
}
