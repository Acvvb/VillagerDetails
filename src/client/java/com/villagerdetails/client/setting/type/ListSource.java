package com.villagerdetails.client.setting.type;

import com.villagerdetails.client.network.sync.ClientRequests;
import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.network.ServerRuleClientState;
import com.villagerdetails.rule.RuleDataKind;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表详情页（{@code ListDetailScreen}）的数据来源：那一页只管「一串 id 的增删」，
 * 具体是本机配置项的列表、还是服务器规则的名单，由这个接口的实现决定。
 *
 * <p>这样两种列表就能共用同一个页面 —— 比如「无挤压 / 可采集」业务上就是「是 / 否」
 * （在名单里就是是），所以它们的详情页和「配置项 - 列表」完全一致，不需要值那一列。
 */
public interface ListSource {

    /**
     * 页面标题（{@code [ xxx ]} 里的 xxx）
     */
    Component title();

    /**
     * 顶部那行说明
     */
    Component description();

    /**
     * 进来时的值（有序快照）
     */
    List<String> current();

    /**
     * 按「完成」时写回（本机配置直接落盘；服务器规则发差异给服务端）
     */
    void apply(List<String> values);

    /**
     * 这一项接受的 id 合法吗
     */
    boolean isValidId(String id);

    /**
     * 补全范围（哪个注册表）；返回 null 表示用默认的全量补全
     */
    Registry<?> idRegistry();

    /**
     * 本机配置项的列表（配置项 - 列表，例如床列表）。
     */
    record EntryList(ConfigEntry entry) implements ListSource {

        @Override
        public Component title() {
            return entry.getDisplayName();
        }

        @Override
        public Component description() {
            return entry.getDisplayInfo();
        }

        @Override
        public List<String> current() {
            return entry.getList();
        }

        @Override
        public void apply(List<String> values) {
            entry.setList(values);
            ClientRenderConfig.get().save();
        }

        @Override
        public boolean isValidId(String id) {
            // 本机配置项接受 MC 里任何存在的 id（条目 / 标签 / 注册表分类）
            return McIds.isKnown(id);
        }

        @Override
        public Registry<?> idRegistry() {
            return null;
        }
    }

    /**
     * 服务器规则的名单（「服务器配置项」里那些纯名单规则，例如无挤压、可采集）。
     *
     * <p>列表形态是服务端同步下来的（{@code kind.isPlainList()} 为 true 才会用这一页），
     * 所以这里拿到的 id 范围 / 校验规则也来自 {@link RuleDataKind}。
     */
    record RuleList(RuleType rule, RuleDataKind kind) implements ListSource {

        @Override
        public Component title() {
            return Component.translatable(rule.getDisplayNameKey());
        }

        @Override
        public Component description() {
            return Component.translatable(rule.getDisplayInfoKey());
        }

        @Override
        public List<String> current() {
            ServerRuleClientState.RuleData data = ServerRuleClientState.ruleData(rule);
            return data == null ? List.of() : new ArrayList<>(data.data().keySet());
        }

        @Override
        public void apply(List<String> values) {
            ServerRuleClientState.RuleData data = ServerRuleClientState.ruleData(rule);
            List<String> before = data == null ? List.of() : new ArrayList<>(data.data().keySet());

            // 新增的（名单里只有 true 这一种值）
            for (String id : values) {
                if (!before.contains(id)) {
                    ClientRequests.sendRuleData(rule, id, "true");
                }
            }
            // 删掉的
            for (String id : before) {
                if (!values.contains(id)) {
                    ClientRequests.sendRuleDataRemove(rule, id);
                }
            }
        }

        @Override
        public boolean isValidId(String id) {
            return kind.isValidId(id);
        }

        @Override
        public Registry<?> idRegistry() {
            return kind.registry();
        }
    }
}
