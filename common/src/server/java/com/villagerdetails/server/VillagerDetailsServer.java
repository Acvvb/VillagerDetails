package com.villagerdetails.server;

import com.villagerdetails.cache.ServerSessionStates;
import com.villagerdetails.command.RuleCommand;
import com.villagerdetails.config.ConfigRegistry;
import com.villagerdetails.event.ServerEventManager;
import com.villagerdetails.network.ServerChannels;
import com.villagerdetails.rule.registry.RuleDataRegistry;
import com.villagerdetails.rule.registry.RuleModuleRegistry;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;

/**
 * 服务端初始化（<b>逻辑服务端</b>：专用服 + 单人里的集成服）。
 *
 * <p>Fabric 的两个入口点：
 * <ul>
 *     <li>{@code server} 入口点 → 只在专用服调用 {@link #onInitializeServer()}（见 {@link DedicatedServerModInitializer}）</li>
 *     <li>{@code main} 入口点 → 两端都调用 {@link #onInitialize()}</li>
 * </ul>
 *
 * <p>本类同时实现两者：产出的 <b>server 包</b>用 {@code server} 入口点；
 * 如果打开了 {@code includeServerInClient=true}，构建脚本会把入口点改挂到 {@code main}，
 * 这样单人存档里的集成服也能跑到同一段初始化。
 *
 * <p>只做「接线」，具体逻辑在各子包里；这里不写业务。
 */
public final class VillagerDetailsServer implements ModInitializer, DedicatedServerModInitializer {

    @Override
    public void onInitialize() {
        init();
    }

    @Override
    public void onInitializeServer() {
        init();
    }

    private static void init() {

        //配置注册
        ConfigRegistry.registerStartedHook();

        //事件注册
        ServerEventManager.registerAll();

        //优先注册模块
        RuleModuleRegistry.initAll();

        //规则配置同步客户端注册，必须在规则注册完成之后注册
        RuleDataRegistry.verifyConsistency();

        // 注册指令，必须在规则注册完成之后注册
        RuleCommand.registerHook();

        //接管服务器会话
        ServerSessionStates.register();

        //网络包注册
        ServerChannels.registerAll();
    }
}
