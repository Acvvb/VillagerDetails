package com.villagerdetails.client.setting.type.server;

import com.villagerdetails.client.setting.dto.EntryListSource;
import com.villagerdetails.client.setting.dto.RuleListSource;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 列表详情页（{@code ListDetailScreen}）的数据来源：那一页只管「一串 id 的增删」，
 * 具体是本机配置项的列表、还是服务器规则的名单，由这个接口的实现决定。
 *
 * <p>这样两种列表就能共用同一个页面 —— 比如「无挤压 / 可采集」业务上就是「是 / 否」
 * （在名单里就是是），所以它们的详情页和「配置项 - 列表」完全一致，不需要值那一列。
 *
 * <p>实现见 {@link EntryListSource}（本机配置项）与 {@link RuleListSource}（服务器规则名单）。
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
     * 补全范围（哪个注册表）；返回 null 表示用默认的全量补全
     */
    Registry<?> idRegistry();
}