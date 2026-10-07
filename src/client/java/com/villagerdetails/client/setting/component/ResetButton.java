package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.row.ConfigRow;
import com.villagerdetails.client.setting.type.ConfigEntry;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/**
 * 「重置」按钮组件。
 *
 * <p>自带「已经是默认值就置灰」的判定：构造后立刻读一次（见 {@link #forRow} / {@link #forEntry}），
 * 值变化后再调 {@link #refreshState()} 刷新。行上用 {@link #forRow}，颜色详情页用 {@link #forEntry}。
 */
public class ResetButton extends Button.Plain {

    private final BooleanSupplier isDefault;

    /**
     * 只给下面两个工厂用。
     *
     * <p>{@code refreshState()} 是公开方法（子类可以覆盖），所以不放在构造里调 ——
     * 那会让 {@code this} 在子类初始化完之前逸出；改由工厂构造完再调。
     */
    private ResetButton(int x, int y, int width, int height, Component label,
                        BooleanSupplier isDefault, Runnable onReset) {
        super(x, y, width, height, label, _ -> onReset.run(), DEFAULT_NARRATION);
        this.isDefault = isDefault;
    }

    /**
     * 配置项行上的「重置」：重置该项并回调（一般是重建界面）
     */
    public static ResetButton forRow(ConfigRow row, int x, int y, int width, int height, Runnable onReset) {
        ResetButton button = new ResetButton(x, y, width, height,
                Component.translatable("villagerdetails.ui.reset_rule"),
                row::isDefault,
                () -> {
                    row.reset();
                    onReset.run();
                });
        button.refreshState();
        return button;
    }

    /**
     * 详情页的「恢复默认」
     */
    public static ResetButton forEntry(ConfigEntry entry, int x, int y, int width, int height, Runnable onReset) {
        ResetButton button = new ResetButton(x, y, width, height,
                Component.translatable("villagerdetails.ui.reset_option"),
                entry::isDefault,
                () -> {
                    entry.reset();
                    onReset.run();
                });
        button.refreshState();
        return button;
    }

    /**
     * 值变了之后调一下，刷新灰 / 亮状态
     */
    public void refreshState() {
        this.active = !isDefault.getAsBoolean();
    }
}
