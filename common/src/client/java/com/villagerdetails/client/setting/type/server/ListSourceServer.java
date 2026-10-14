package com.villagerdetails.client.setting.type.server;

import com.villagerdetails.client.setting.dto.EntryListSource;
import com.villagerdetails.client.setting.dto.RuleListSource;
import com.villagerdetails.client.setting.util.McIds;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 列表详情页（{@code ListDetailScreen}）的数据来源：那一页只管「一串 id 的增删」，
 * 具体是本机配置项的列表、还是服务器规则的名单，由这个接口的实现决定。
 *
 * <p>这样两种列表就能共用同一个页面 —— 比如「无挤压 / 可采集」业务上就是「是 / 否」
 * （在名单里就是是），所以它们的详情页和「配置项 - 列表」完全一致，不需要值那一列。
 *
 * <p>实现见 {@link EntryListSource}（本机配置项，本地注册表补全）与
 * {@link RuleListSource}（服务器规则名单，候选清单由服务端按需下发）。
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
     * 按「完成」时写回（本机配置直接落盘；服务器规则发差异给服务端）
     */
    void apply(List<String> values);

    /**
     * 这一项接受的 id 合法吗
     */
    boolean isValidId(String id);

    /**
     * id 输入框的补全候选；客户端只做过滤 / 本地注册表查询，规则语义在服务端
     */
    List<McIds.Entry> suggestions(String query, int limit);

    /**
     * 收进工作副本之前把玩家敲的 id 规范化。
     *
     * <p>本机配置项原样收（{@code #标签}、裸路径都允许，存的就是玩家写的那串）；
     * 服务器规则要按注册表 id 的规范写法收（{@code stone} → {@code minecraft:stone}），
     * 否则和已有条目比对时会被当成「新的一条」，反手把原来的删掉。
     */
    default String normalizeId(String id) {
        return id;
    }

    /**
     * 打开详情页时调一次；候选清单要问服务端要的实现（{@link RuleListSource}）在这里发请求
     */
    default void prepare() {
    }
}
