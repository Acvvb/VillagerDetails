package com.villagerdetails.server;

import com.villagerdetails.cache.ServerSessionStates;
import com.villagerdetails.command.RuleCommand;
import com.villagerdetails.concurrent.ServerThreadManager;
import com.villagerdetails.config.ConfigRegistry;
import com.villagerdetails.config.impl.RuleConfig;
import com.villagerdetails.config.impl.UnifiedConfigFile;
import com.villagerdetails.event.ServerEventManager;
import com.villagerdetails.network.ServerChannels;
import com.villagerdetails.rule.registry.RuleDataRegistry;
import com.villagerdetails.rule.registry.RuleModuleRegistry;
import com.villagerdetails.util.io.AsyncConfigManager;
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

        // 服务端线程池：必须在一切事件 / 规则之前就绪（区块卸载的村民交易快照要用它）
        // 内部会挂 SERVER_STARTED / SERVER_STOPPING，单人反复换存档时能自动重建 / 关闭
        ServerThreadManager.init();

        // 停服收尾三步，顺序不能乱，全部挂在「关池之前」：
        //   ① 两个配置把内存改动交给 AsyncConfigManager（序列化 + 入队，不阻塞）；
        //   ② 异步写线程排干所有待写文件（最多等 5 秒）；
        //   ③ 然后才轮到 ServerThreadManager 关池。
        // ServerThreadManager 的 SERVER_STOPPING 比下面 ServerEventManager 注册的回调更早，
        // 不显式挂这里就会变成「先关池、再刷盘 / 排干已经结束、配置才入队」，最后一次改动直接丢。
        // 注：AsyncConfigWriter 自己也注册了 JVM 关机钩子兜底，客户端不写停机关闭代码也不会丢。
        ServerThreadManager.addPreShutdownHook(UnifiedConfigFile.INSTANCE::flush);
        ServerThreadManager.addPreShutdownHook(RuleConfig::flushAllAndClear);
        ServerThreadManager.addPreShutdownHook(() -> AsyncConfigManager.flushAndShutdown(5000L));

        //配置注册
        ConfigRegistry.registerStartedHook();

        //事件注册
        ServerEventManager.registerAll();

        //优先注册模块
        RuleModuleRegistry.initAll();

        //规则配置同步客户端注册，必须在规则注册完成之后注册
        RuleDataRegistry.verifyConsistency();

        // 配置侧自检：一实例一规则 / 一名一配置 / 规则配置不许再注册进 ConfigRegistry
        // （放在规则注册之后，和上面的 RuleDataRegistry 自检同一批跑）
        ConfigRegistry.verifyReloadableConfigs();

        // 注册指令，必须在规则注册完成之后注册
        RuleCommand.registerHook();

        //接管服务器会话
        ServerSessionStates.register();

        //网络包注册
        ServerChannels.registerAll();
    }
}
