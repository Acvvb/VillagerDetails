package com.villagerdetails.client.setting.row;

import com.villagerdetails.client.network.requests.ClientRequests;
import com.villagerdetails.client.setting.type.style.StyleType;
import com.villagerdetails.rule.RuleSwitches;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 「服务器」分类里的一行：{@code 规则名 … [状态按钮组] [重置]}。
 *
 * <p>这一行的<b>全部内容都来自服务端下发的描述符</b>（名称 / 描述 / 状态 / 按钮组），
 * 客户端不再查自己的规则枚举 —— 所以服务端新增的规则、改过的选项，老客户端也照样画得出来。
 *
 * <p>操作只按注册名发请求给服务端，由服务端做权限校验与合法性判定后落盘。
 */
public record ServerRuleRow(RuleDescriptor rule) implements ConfigRow {

    @Override
    public String getRegisterName() {
        return rule.registerName();
    }

    @Override
    public Component getDisplayName() {
        // 服务端已经按我们的界面语言翻译好了，这里直接用
        return Component.literal(rule.name());
    }

    @Override
    public Component getDisplayInfo() {
        return Component.literal(rule.info());
    }

    @Override
    public StyleType getStyle() {
        return StyleType.TOGGLE;
    }

    @Override
    public boolean getToggle() {
        return rule.enabled();
    }

    @Override
    public void setToggle(boolean value) {
        ClientRequests.INSTANCE.sendRuleState(rule.registerName(), Boolean.toString(value));
    }

    /**
     * 快捷开关按钮组：一个选项一个按钮，文字和取值都由服务端给。
     */
    @Override
    public List<SwitchOption> getSwitchOptions() {
        if (rule.switches().isEmpty()) {
            return List.of();
        }

        // 悬停提示用命令里那条文案：点击切换规则状态 / 当前状态: xxx
        Component tooltip = Component.translatable("command.entity_binder.toggle.hover", currentText());

        List<SwitchOption> result = new ArrayList<>(rule.switches().size());
        for (RuleDescriptor.Switch option : rule.switches()) {
            // 单选：点哪个按钮就把哪个选项原样发给服务端（选项本身就是状态值）
            result.add(new SwitchOption(
                    Component.literal(option.text()),
                    RuleSwitches.colorOf(option.value(), rule.state()),
                    tooltip,
                    () -> ClientRequests.INSTANCE.sendRuleState(rule.registerName(), option.value())));
        }
        return result;
    }

    @Override
    public boolean isDefault() {
        return normalize(rule.state()).equalsIgnoreCase(normalize(rule.defaultState()));
    }

    @Override
    public void reset() {
        ClientRequests.INSTANCE.sendRuleReset(rule.registerName());
    }

    /**
     * 当前状态对应的按钮文字；状态不在按钮组里（自定义取值）就原样显示状态值
     */
    private String currentText() {
        for (RuleDescriptor.Switch option : rule.switches()) {
            if (option.value().equalsIgnoreCase(rule.state())) {
                return option.text();
            }
        }
        return rule.state();
    }

    private static String normalize(String state) {
        return state == null ? "" : state.trim();
    }
}
