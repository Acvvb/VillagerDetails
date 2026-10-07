package com.villagerdetails.client.setting.row;

import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.type.ConfigEntry;
import com.villagerdetails.client.setting.type.SubCategory;
import net.minecraft.network.chat.Component;

/**
 * 本机配置项的一行（{@code ConfigEntry}）。
 */
public record ClientEntryRow(ConfigEntry entry) implements ConfigRow {

    @Override
    public String getId() {
        return entry.getRegisterName();
    }

    @Override
    public Component getDisplayName() {
        return entry.getDisplayName();
    }

    @Override
    public Component getDisplayInfo() {
        return entry.getDisplayInfo();
    }

    @Override
    public SubCategory.Style getStyle() {
        return entry.style();
    }

    @Override
    public boolean hasKeyBind() {
        return entry.style() == SubCategory.Style.TOGGLE;
    }

    @Override
    public boolean getToggle() {
        return entry.getToggle();
    }

    @Override
    public void setToggle(boolean value) {
        entry.setToggle(value);
    }

    @Override
    public double getNumber() {
        return entry.getNumber();
    }

    @Override
    public void setNumber(double value) {
        entry.setNumber(value);
    }

    @Override
    public double minNumber() {
        return entry.minNumber();
    }

    @Override
    public double maxNumber() {
        return entry.maxNumber();
    }

    @Override
    public double stepNumber() {
        return entry.stepNumber();
    }

    @Override
    public Component formatNumber(double value) {
        return entry.formatNumber(value);
    }

    @Override
    public int getColor() {
        return entry.getColor();
    }

    @Override
    public int[] getKeyBind() {
        return entry.getKeyBind();
    }

    @Override
    public void setKeyBind(int[] keys) {
        entry.setKeyBind(keys);
    }

    @Override
    public void reset() {
        entry.reset();
    }

    @Override
    public boolean isDefault() {
        return entry.isDefault();
    }

    @Override
    public void save() {
        ClientRenderConfig.get().save();
    }
}
