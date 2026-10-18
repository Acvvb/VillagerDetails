package com.villagerdetails.client.setting.type.server;

import com.villagerdetails.client.setting.dto.EntryListSource;
import com.villagerdetails.client.setting.util.McIds;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 列表详情页（{@code ListDetailScreen}）的数据来源：那一页只管「一串 id 的增删」。
 *
 * <p>目前只有本机配置项的列表用它（{@link EntryListSource}）。
 *
 * <p><b>服务端配置项的逐条规则不走这里</b>：它们统一走 {@code RuleDataDetailScreen}，
 * 由服务端下发的模板决定是「id + 值」还是「id + 是/否 + 重置」——
 * 纯名单规则虽然业务上也是「是 / 否」，但它需要一个能表达 false 的开关和一个
 * 「重置到默认值」的按钮，不是单纯的一串 id。
 */
public interface ListSourceServer {

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
     * 按「完成」时写回（本机配置直接落盘）
     */
    void apply(List<String> values);

    /**
     * 这一项接受的 id 合法吗
     */
    boolean isValidId(String id);

    /**
     * id 输入框的补全候选
     */
    List<McIds.Entry> suggestions(String query, int limit);

    /**
     * 收进工作副本之前把玩家敲的 id 规范化。
     *
     * <p>本机配置项原样收（{@code #标签}、裸路径都允许，存的就是玩家写的那串）。
     */
    default String normalizeId(String id) {
        return id;
    }

    /**
     * 打开详情页时调一次（需要向服务端要数据的实现在这里发请求）
     */
    default void prepare() {
    }
}
