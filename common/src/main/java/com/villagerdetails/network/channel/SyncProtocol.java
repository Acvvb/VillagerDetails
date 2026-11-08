package com.villagerdetails.network.channel;

/**
 * 同步协议版本。
 *
 * <p>本轮把配置界面「服务器 / 服务端配置」两个分类改成<b>服务端全量下发描述符</b>：
 * 客户端不再靠自己的 {@code RuleType} / {@code RuleDataKind} 去查规则语义，
 * 收到什么就按模板渲染什么（{@code rule_descriptor_sync}）。
 *
 * <p>版本号在两个地方用：
 * <ul>
 *     <li>客户端 {@code rule_descriptor_hello} 里声明自己的版本，服务端版本对不上就只记日志不同步
 *         （两边是同一份构建，正常情况下不会不一致；混用新旧 jar 时能一眼看出原因）</li>
 *     <li>描述符包自己带一份，方便将来升 v3 时在新包/新字段上做判断</li>
 * </ul>
 *
 * <p><b>不做旧版本兼容</b>：v1 的 {@code server_rule_sync} / {@code server_rule_request} 已经删掉，
 * 老客户端连新服务端（或者反过来）时这两个分类会空掉，不再有回退路径。
 * 协议形状一变就升版本号 + 加新包 id，不在同一个包 id 里塞兼容字段。
 *
 * <p><b>发布 checklist</b>：任何「改包字段 / 增删包 id / 改描述符结构」的改动都必须顺手抬
 * {@link #VERSION} —— 服务端对版本不一致<b>只记日志、不拒绝</b>，忘了抬就会变成
 * "能连上、但字段错位"的静默故障（混用新旧 jar 时尤其难查）。
 */
public final class SyncProtocol {

    /**
     * 当前协议版本
     */
    public static final int VERSION = 2;

    private SyncProtocol() {
    }
}
