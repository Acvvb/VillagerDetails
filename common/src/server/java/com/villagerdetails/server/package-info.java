/**
 * 服务端（逻辑服务端：专用服 + 单人里的集成服）专属代码。
 *
 * <h2>这里有什么</h2>
 * <ul>
 *     <li>指令：{@code command/**}（{@code /ec ...}）</li>
 *     <li>规则实现：{@code handler/**}、{@code rule/**}（规则表、校验、回调）</li>
 *     <li>持久化：{@code config/**}（世界存档里的配置）、{@code cache/**}</li>
 *     <li>服务端收包 / 事件：{@code network/sync/receiver}、{@code network/sync/event}、
 *         {@code network/channel/ServerChannels}</li>
 *     <li>游戏逻辑 Mixin：{@code mixin/**}（配置在 {@code villagerdetails.server.mixins.json}）</li>
 *     <li>入口点：{@link com.villagerdetails.server.VillagerDetailsServer}（Fabric {@code server} 入口点）</li>
 * </ul>
 *
 * <h2>这里不能有什么</h2>
 * <ul>
 *     <li>任何 {@code com.villagerdetails.client.*}（物理客户端专属，客户端模块看不见这里）</li>
 *     <li>被 {@code main} 引用的类 —— {@code main} 看不见 {@code server}，反向引用会编译失败</li>
 * </ul>
 *
 * <h2>两端共用的东西在哪</h2>
 * <p>{@code main} 只留「网络契约 + 纯数据」：包定义（{@code network.sync.payload}）、
 * 编解码、{@link com.villagerdetails.network.channel.PayloadTypes 包类型注册}、
 * {@code RuleDescriptor}、{@code RuleSwitches}、{@code VillagerDetails} 常量。
 *
 * <p>依赖方向只有一条：{@code server → main}。
 */
package com.villagerdetails.server;
