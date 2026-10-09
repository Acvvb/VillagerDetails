package com.villagerdetails.client.setting.row;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.client.network.sync.ClientRequests;
import com.villagerdetails.client.setting.type.style.StyleType;
import com.villagerdetails.rule.RuleSwitches;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器规则的一行（{@code RuleType}）。
 *
 * <p>样式和功能开关一样，但没有快捷键绑定框；按钮组按 {@link RuleType#getQuickSwitches()} 渲染，
 * 文字 / 颜色 / 取值逻辑都和 {@code /ec} 命令一致。
 * 开关状态直接读客户端镜像的 {@link RuleCache}，操作则发请求给服务端，由服务端做权限校验后落盘。
 */
public record ServerRuleRow(RuleType rule) implements ConfigRow {

    @Override
    public String getRegisterName() {
        return rule.getRegisterName();
    }

    @Override
    public Component getDisplayName() {
        // 用和 /ec 命令同一条翻译键，保证显示一致
        return Component.translatable(rule.getDisplayNameKey());
    }

    @Override
    public Component getDisplayInfo() {
        return Component.translatable(rule.getDisplayInfoKey());
    }

    @Override
    public StyleType getStyle() {
        return StyleType.TOGGLE;
    }

    @Override
    public boolean getToggle() {
        return RuleCache.isEnabled(rule);
    }

    @Override
    public void setToggle(boolean value) {
        ClientRequests.sendRuleState(rule, Boolean.toString(value));
    }

    /**
     * 快捷开关按钮组：一个选项一个按钮，和 /ec 里那一排完全对应
     */
    @Override
    public List<SwitchOption> getSwitchOptions() {
        List<String> options = rule.getQuickSwitches();
        if (options == null || options.isEmpty()) {
            return List.of();
        }

        String state = RuleCache.getState(rule);
        // 悬停提示用命令里那条文案：点击切换规则状态 / 当前状态: xxx
        Component tooltip = Component.translatable("command.entity_binder.toggle.hover",
                RuleSwitches.currentDisplay(state));

        List<SwitchOption> result = new ArrayList<>(options.size());
        for (String option : options) {
            result.add(new SwitchOption(
                    Component.literal(RuleSwitches.displayNameOf(option)),
                    RuleSwitches.colorOf(rule, option, state),
                    tooltip,
                    () -> ClientRequests.sendRuleState(
                            rule, RuleSwitches.nextState(rule, state, option))));
        }
        return result;
    }

    @Override
    public boolean isDefault() {
        return RuleSwitches.isDefaultState(rule);
    }

    @Override
    public void reset() {
        ClientRequests.sendRuleReset(rule);
    }
}
