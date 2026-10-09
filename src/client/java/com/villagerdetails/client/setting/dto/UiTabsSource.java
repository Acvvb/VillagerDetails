package com.villagerdetails.client.setting.dto;

import com.villagerdetails.client.setting.row.ConfigRow;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 界面右上那一排二级分类按钮中的一项。
 *
 * @param id          稳定 id（记住上次停留位置用）
 * @param displayName 显示名
 * @param rowSupplier 该分类下的行（每次取都重新构建，开关状态变化 / 搜索过滤都能反映出来）
 */
public record UiTabsSource(String id, Component displayName, Supplier<List<ConfigRow>> rowSupplier) {

    public List<ConfigRow> rows() {
        return rowSupplier.get();
    }
}
