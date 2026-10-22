package com.villagerdetails.client;

import com.villagerdetails.client.cache.ClientSessionStates;
import com.villagerdetails.client.feature.ClientFeatureScanner;
import com.villagerdetails.client.network.ClientChannels;
import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.input.VillagerDetailsKeyMappings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * 客户端入口：只做「按顺序把几件事接上线」，具体注册内容都在各自的扫包注册器里。
 *
 * <p>客户端一共有三组注册，都是「扫包 + 失败兜底」的同一套写法，加东西不用回来改这里：
 * <ul>
 *     <li>{@link ClientSessionStates} —— 缓存的生命周期（断线清哪些、启动时接管哪些）</li>
 *     <li>{@link ClientChannels} —— 同步包收包器 + 各通道自己的客户端事件</li>
 *     <li>{@link ClientFeatureScanner} —— 渲染器 / 客户端功能</li>
 * </ul>
 */
public class VillagerDetailsClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 1. 读取客户端渲染配置（文件不存在时会生成一份默认配置）
        ClientRenderConfig.load();

        // 2. 快捷键（默认 J）打开配置界面
        VillagerDetailsKeyMappings.register();

        // 3. 接管所有客户端的会话缓存，并注册断线清理
        //    扫包发生在 registerAll() 里（不是静态块），所以启动日志里就能看到「接管了哪些缓存」
        ClientSessionStates.registerAll();
        //    全客户端唯一的一处 DISCONNECT 回调：断线 / 切世界时把这些缓存整体清空
        ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> ClientSessionStates.clearAll());

        // 4. 所有同步包的客户端接收器（自动扫包注册）
        ClientChannels.registerAll();

        // 5. 所有渲染器与客户端功能（自动扫包注册，替代了之前手写的 5 个 Renderer）
        ClientFeatureScanner.registerAll();
    }
}
