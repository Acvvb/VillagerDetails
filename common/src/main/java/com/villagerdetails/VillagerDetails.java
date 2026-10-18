package com.villagerdetails;

import com.villagerdetails.command.RuleCommand;
import com.villagerdetails.config.ConfigRegistry;
import com.villagerdetails.event.BBSelectionListener;
import com.villagerdetails.event.EBSectionManager;
import com.villagerdetails.event.ServerSessionCaches;
import com.villagerdetails.event.ServerStoppingListener;
import com.villagerdetails.handler.monster.noBrainOnPortal.NoBrainOnPortalHandler;
import com.villagerdetails.network.channel.ServerChannels;
import com.villagerdetails.rule.registry.RuleDataRegistry;
import com.villagerdetails.rule.registry.RuleModuleRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;

public class VillagerDetails implements ModInitializer {

    /**
     * 命令根字面量（{@code /ec ...}）。
     *
     * <p><b>值不要改</b>：{@code ModActivities} 用它拼出 {@code ec:go_to_destination} 这个 activity 名，
     * 而 activity 名会写进实体存档（brain memories），改了值老存档就对不上了。
     */
    public static final String BASE_COMMAND = "ec";

    public static final String MOD_ID = "villagerdetails";


    @Override
    public void onInitialize() {

        // 所有同步包（包类型注册 + 服务端收包 + 事件）由 ServerChannels 扫 network.sync 的四个子包自动注册，
        // 加新包只要按目录放类，见那个类的表格
        ServerChannels.registerAll();

        // 启动时的配置回灌只有这一条路：ConfigRegistry 里注册了 RuleConfig，
        // reloadAll 会调它的 reload（以前 ServerLifecycleListener 又单独回灌一遍，等于每次开服跑两遍）
        ServerLifecycleEvents.SERVER_STARTED.register(ConfigRegistry::reloadAll);
        ServerLifecycleEvents.SERVER_STOPPING.register(new ServerStoppingListener());

        RuleModuleRegistry.initAll();

        // 启动自检：RuleType 的 RuleConfigKind（哪些规则要下发列表内容）必须和
        // RuleDataRegistry 的 Entry（怎么下发）一一对应，不一致在这里直接抛异常
        RuleDataRegistry.verifyConsistency();

        // 注册指令
        CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> RuleCommand.register(dispatcher));

        //实体-方块选择工具
        EBSectionManager.init();

        //方块-实体选择工具
        BBSelectionListener.init();

        //玩家断开时摘掉各类选区缓存（静态 Map 不摘会一直堆，集成服换存档还会串 UUID）
        ServerSessionCaches.init();

        //穿过地狱门回调（暂时写死怪物去脑子）
        NoBrainOnPortalHandler.register();
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
